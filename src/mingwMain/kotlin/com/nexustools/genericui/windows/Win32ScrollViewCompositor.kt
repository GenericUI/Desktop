@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.widget.DesktopTextNativeImpl
import com.nexustools.genericui.windows.impl.WindowsButtonNativeImpl
import com.nexustools.genericui.windows.impl.WindowsScrollViewNativeImpl
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.alloc
import kotlinx.cinterop.ptr
import platform.windows.BitBlt
import platform.windows.CreateCompatibleBitmap
import platform.windows.CreateCompatibleDC
import platform.windows.DeleteDC
import platform.windows.DeleteObject
import platform.windows.GetClientRect
import platform.windows.GetDCEx
import platform.windows.GetUpdateRect
import platform.windows.HBITMAP
import platform.windows.HDC
import platform.windows.HGDIOBJ
import platform.windows.HWND
import platform.windows.IntersectClipRect
import platform.windows.InvalidateRect
import platform.windows.IsWindow
import platform.windows.IsWindowVisible
import platform.windows.RECT
import platform.windows.ReleaseDC
import platform.windows.SRCCOPY
import platform.windows.ScrollDC
import platform.windows.SelectClipRgn
import platform.windows.SelectObject
import platform.windows.SetViewportOrgEx
import platform.windows.ValidateRect
import platform.windows.WS_EX_LAYERED
import kotlin.math.abs

/**
 * Single-pass offscreen GDI backbuffer compositor and retained viewport cache for eligible
 * retained `ScrollView` hierarchies.
 *
 * Preserves all genuine parent-child `HWND`s for hit-testing, accessibility, scrolling, and Win32
 * queries, while coalescing eligible child repaints (`GenericUIContainerW`,
 * `GenericUISemanticSurfaceW`, `GenericUIStyledContainerW`, `GenericUIBadgeW`, cached `"Static"`,
 * and `"Button"`) into a retained offscreen `HBITMAP` blitted to `svHwnd` (`DCX_CACHE |
 * DCX_CLIPSIBLINGS`) followed by `ValidateRect` across composited descendants. Supports exact
 * unchanged-frame blits and incremental scroll-strip shifts during drag/wheel scrolling.
 */
object Win32ScrollViewCompositor {

    private const val DCX_CACHE_FLAG: UInt = 0x00000002u
    private const val DCX_CLIPSIBLINGS_FLAG: UInt = 0x00000010u
    private const val BS_TYPEMASK_L: Long = 0x0000000FL

    private const val OP_INELIGIBLE = -1
    private const val KIND_STD_CACHED = 0
    private const val KIND_BOOTSTRAP_BUTTON = 1
    private const val KIND_CUSTOM_STYLED = 2
    private const val KIND_SKIP_TRANSPARENT = 3

    private class DrawOp {
        var kind: Int = 0
        var handle: Win32HwndHandle? = null
        var hwnd: HWND? = null
        var x: Int = 0
        var y: Int = 0
        var w: Int = 0
        var h: Int = 0
        var clipL: Int = 0
        var clipT: Int = 0
        var clipR: Int = 0
        var clipB: Int = 0
    }

    private var backbufferDc: HDC? = null
    private var backbufferBmp: HBITMAP? = null
    private var backbufferOldBmp: HGDIOBJ? = null
    private var backbufferW: Int = 0
    private var backbufferH: Int = 0

    private var backbufferOwnerHwnd: HWND? = null
    private var backbufferViewW: Int = 0
    private var backbufferViewH: Int = 0
    private var backbufferScrollX: Int = 0
    private var backbufferScrollY: Int = 0
    private var backbufferRingY: Int = 0
    private var backbufferIsDark: Boolean = false
    private var backbufferNonOverlayVer: Long = -1L
    private var backbufferAncestorEpoch: Long = -1L
    private var backbufferContentEpoch: Long = -1L
    private var contentEpoch: Long = 0L

    private var stripDc: HDC? = null
    private var stripBmp: HBITMAP? = null
    private var stripOldBmp: HGDIOBJ? = null
    private var stripBufW: Int = 0
    private var stripBufH: Int = 0

    private var compositeDepth: Int = 0
    private var scratchClientRc: RECT? = null
    private var scratchUpdateRc: RECT? = null
    private var scratchScrollUpdateRc: RECT? = null

    private val drawOps = ArrayList<DrawOp>(128)
    private var drawOpCount: Int = 0
    private val validatedHwnds = ArrayList<HWND>(128)
    private val scratchShownHandles = ArrayList<Win32HwndHandle>(16)
    private var totalVisibleDescendants: Int = 0

    /**
     * Marks retained `ScrollView` composited backbuffers stale after visual/state changes.
     */
    internal fun invalidateBackbufferContent() {
        contentEpoch++
    }

    /**
     * Releases any retained offscreen backbuffer GDI objects (called when closing visual windows).
     */
    fun releaseBackbuffer() {
        backbufferOwnerHwnd = null
        backbufferRingY = 0
        stripDc?.let { dc ->
            stripOldBmp?.let { SelectObject(dc, it) }
            stripOldBmp = null
            stripBmp?.let { DeleteObject(it) }
            stripBmp = null
            DeleteDC(dc)
            stripDc = null
        }
        stripBufW = 0
        stripBufH = 0
        val dc = backbufferDc ?: return
        backbufferOldBmp?.let { SelectObject(dc, it) }
        backbufferOldBmp = null
        backbufferBmp?.let { DeleteObject(it) }
        backbufferBmp = null
        DeleteDC(dc)
        backbufferDc = null
        backbufferW = 0
        backbufferH = 0
    }

    private fun ensureBackbuffer(formatDc: HDC, reqW: Int, reqH: Int): HDC? {
        if (reqW <= 0 || reqH <= 0) return null
        val dc = backbufferDc ?: CreateCompatibleDC(formatDc)?.also { backbufferDc = it } ?: return null
        if (backbufferBmp == null || backbufferW < reqW || backbufferH < reqH) {
            backbufferOwnerHwnd = null
            val allocW = maxOf(reqW, backbufferW)
            val allocH = maxOf(reqH, backbufferH)
            val newBmp = CreateCompatibleBitmap(formatDc, allocW, allocH) ?: return null
            backbufferOldBmp?.let { SelectObject(dc, it) }
            backbufferBmp?.let { DeleteObject(it) }
            backbufferOldBmp = SelectObject(dc, newBmp)
            backbufferBmp = newBmp
            backbufferW = allocW
            backbufferH = allocH
        }
        return dc
    }

    private fun ensureStripBuffer(formatDc: HDC, reqW: Int, reqH: Int): HDC? {
        if (reqW <= 0 || reqH <= 0) return null
        val dc = stripDc ?: CreateCompatibleDC(formatDc)?.also { stripDc = it } ?: return null
        if (stripBmp == null || stripBufW < reqW || stripBufH < reqH) {
            val allocW = maxOf(reqW, stripBufW)
            val allocH = maxOf(reqH, stripBufH)
            val newBmp = CreateCompatibleBitmap(formatDc, allocW, allocH) ?: return null
            stripOldBmp?.let { SelectObject(dc, it) }
            stripBmp?.let { DeleteObject(it) }
            stripOldBmp = SelectObject(dc, newBmp)
            stripBmp = newBmp
            stripBufW = allocW
            stripBufH = allocH
        }
        return dc
    }

    private fun blitRingRectToDc(
        dstDc: HDC,
        srcBbDc: HDC,
        viewH: Int,
        ringY: Int,
        x: Int,
        y: Int,
        w: Int,
        h: Int
    ): Boolean {
        if (w <= 0 || h <= 0) return true
        val physY = (ringY + y) % viewH
        val firstH = minOf(h, viewH - physY)
        if (firstH > 0 && BitBlt(dstDc, x, y, w, firstH, srcBbDc, x, physY, SRCCOPY) == 0) return false
        val remH = h - firstH
        if (remH > 0 && BitBlt(dstDc, x, y + firstH, w, remH, srcBbDc, x, 0, SRCCOPY) == 0) return false
        return true
    }

    private fun copyStripIntoRingBackbuffer(
        bbDc: HDC,
        srcStripDc: HDC,
        viewH: Int,
        ringY: Int,
        stripL: Int,
        stripT: Int,
        stripW: Int,
        stripH: Int
    ) {
        val physY = (ringY + stripT) % viewH
        val firstH = minOf(stripH, viewH - physY)
        if (firstH > 0) {
            BitBlt(bbDc, stripL, physY, stripW, firstH, srcStripDc, 0, 0, SRCCOPY)
        }
        val remH = stripH - firstH
        if (remH > 0) {
            BitBlt(bbDc, stripL, 0, stripW, remH, srcStripDc, 0, firstH, SRCCOPY)
        }
    }

    private fun emitDrawOp(
        kind: Int,
        handle: Win32HwndHandle,
        hwnd: HWND,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        clipL: Int,
        clipT: Int,
        clipR: Int,
        clipB: Int
    ) {
        val op = if (drawOpCount < drawOps.size) {
            drawOps[drawOpCount]
        } else {
            DrawOp().also { drawOps.add(it) }
        }
        op.kind = kind
        op.handle = handle
        op.hwnd = hwnd
        op.x = x
        op.y = y
        op.w = w
        op.h = h
        op.clipL = clipL
        op.clipT = clipT
        op.clipR = clipR
        op.clipB = clipB
        drawOpCount++
    }

    private fun resolveEligibleOpKind(
        c: Win32HwndHandle,
        cls: String,
        cHwnd: HWND,
        isDark: Boolean
    ): Int {
        if (c.effectiveOpacity < 0.996f ||
            (c.dwExStyle and WS_EX_LAYERED.toLong()) != 0L ||
            c.customPaintCallback != null ||
            Win32LayeredStageHostHwnd.isStageHostHwnd(cHwnd)
        ) {
            return OP_INELIGIBLE
        }
        return when (cls) {
            "Static" -> {
                if (c.ownerImpl is DesktopTextNativeImpl<*> &&
                    !Win32ControlMessageRouter.shouldUseTransparentStageCtlColor(cHwnd, c)
                ) {
                    KIND_STD_CACHED
                } else {
                    OP_INELIGIBLE
                }
            }
            "Button" -> {
                val btnOwner = c.ownerImpl as? WindowsButtonNativeImpl ?: return OP_INELIGIBLE
                if ((c.dwStyle and BS_TYPEMASK_L) > 1L) return OP_INELIGIBLE
                if (Win32ControlMessageRouter.shouldUseTransparentStageCtlColor(cHwnd, c)) return OP_INELIGIBLE
                if (btnOwner.isNativeDefaultButtonMode()) {
                    KIND_STD_CACHED
                } else {
                    val spec = Win32StyledWidgetHelper.resolveEffectiveVisualSpec(c, btnOwner, cls, isDark)
                    if (spec.hasBackdropFilter || spec.filter?.hasActiveEffects == true) {
                        OP_INELIGIBLE
                    } else {
                        KIND_BOOTSTRAP_BUTTON
                    }
                }
            }
            "GenericUIContainerW",
            "GenericUISemanticSurfaceW",
            "GenericUIStyledContainerW",
            "GenericUIBadgeW" -> {
                val owner = c.ownerImpl ?: return OP_INELIGIBLE
                val spec = Win32StyledWidgetHelper.resolveEffectiveVisualSpec(c, owner, cls, isDark)
                if (spec.hasBackdropFilter || spec.filter?.hasActiveEffects == true) {
                    OP_INELIGIBLE
                } else if (spec.hasCustomBackground ||
                    spec.hasBorder ||
                    spec.hasFocusRing ||
                    spec.boxShadowColor != null ||
                    cls == "GenericUIBadgeW"
                ) {
                    KIND_CUSTOM_STYLED
                } else {
                    KIND_SKIP_TRANSPARENT
                }
            }
            else -> OP_INELIGIBLE
        }
    }

    private fun isScrollViewRootEligible(scrollView: WindowsScrollViewNativeImpl, svHwnd: HWND): Boolean {
        val svHandle = scrollView.nativeWidget
        if (svHandle.effectiveOpacity < 0.996f ||
            (svHandle.dwExStyle and WS_EX_LAYERED.toLong()) != 0L ||
            svHandle.customPaintCallback != null
        ) {
            return false
        }
        if (Win32LayeredStageHostHwnd.hasAnyStageHosts() &&
            Win32LayeredStageHostHwnd.isDescendantOfStageHost(svHwnd)
        ) {
            return false
        }
        return true
    }

    private fun checkSubtreeEligibilityOnly(
        parent: DesktopNativeWidget,
        isDark: Boolean
    ): Boolean {
        val children = parent.children
        for (i in children.indices) {
            val c = children[i] as? Win32HwndHandle ?: continue
            if (!c.effectiveVisible || c.effectiveOpacity <= 0.001f) {
                continue
            }
            val cHwnd = c.hwnd ?: return false
            if (IsWindow(cHwnd) == 0) return false
            if (resolveEligibleOpKind(c, c.actualWin32ClassName, cHwnd, isDark) == OP_INELIGIBLE) {
                return false
            }
            totalVisibleDescendants++
            if (c.children.isNotEmpty() && !checkSubtreeEligibilityOnly(c, isDark)) {
                return false
            }
        }
        return true
    }

    /**
     * Returns `true` if [scrollView] and all its active non-culled descendants qualify for
     * single-pass offscreen backbuffer compositing and `SWP_NOREDRAW` scroll repositioning.
     */
    internal fun canUseFastScrollComposite(scrollView: WindowsScrollViewNativeImpl): Boolean {
        val svHwnd = scrollView.nativeWidget.hwnd ?: return false
        if (IsWindow(svHwnd) == 0) return false
        if (!isScrollViewRootEligible(scrollView, svHwnd)) return false
        val isDark = Win32NativeWindowHost.isEffectivelyDarkTheme()
        val prevCount = totalVisibleDescendants
        totalVisibleDescendants = 0
        return try {
            checkSubtreeEligibilityOnly(scrollView.nativeWidget, isDark) && totalVisibleDescendants >= 2
        } finally {
            totalVisibleDescendants = prevCount
        }
    }

    private fun collectSubtree(
        parent: DesktopNativeWidget,
        originX: Int,
        originY: Int,
        clipL: Int,
        clipT: Int,
        clipR: Int,
        clipB: Int,
        dl: Int,
        dt: Int,
        dr: Int,
        db: Int,
        isFullViewport: Boolean,
        scrollX: Int,
        scrollY: Int,
        isDirectChildOfScrollView: Boolean,
        isDark: Boolean,
        collectStripOnly: Boolean = false
    ): Boolean {
        val children = parent.children
        val count = children.size
        if (count == 0) return true

        var hasCustomZ = false
        for (i in 0 until count) {
            val h = children[i] as? Win32HwndHandle
            if (h != null && h.desiredZIndex != 0) {
                hasCustomZ = true
                break
            }
        }

        val ordered: List<DesktopNativeWidget> = if (!hasCustomZ) {
            children
        } else {
            children.sortedWith(compareBy<DesktopNativeWidget> {
                (it as? Win32HwndHandle)?.desiredZIndex ?: 0
            })
        }

        for (i in ordered.indices) {
            val c = ordered[i] as? Win32HwndHandle ?: continue
            if (!c.effectiveVisible ||
                c.effectiveOpacity <= 0.001f ||
                Win32ControlMessageRouter.isViewportCulled(c)
            ) {
                continue
            }

            val localX = if (c.lastAppliedX != Int.MIN_VALUE) {
                c.lastAppliedX
            } else if (isDirectChildOfScrollView) {
                c.bounds.x - scrollX
            } else {
                c.bounds.x
            }
            val localY = if (c.lastAppliedY != Int.MIN_VALUE) {
                c.lastAppliedY
            } else if (isDirectChildOfScrollView) {
                c.bounds.y - scrollY
            } else {
                c.bounds.y
            }
            val cw = if (c.lastAppliedW > 0) c.lastAppliedW else c.bounds.width.coerceAtLeast(0)
            val ch = if (c.lastAppliedH > 0) c.lastAppliedH else c.bounds.height.coerceAtLeast(0)
            val cx = originX + localX
            val cy = originY + localY

            val nClipL = maxOf(clipL, cx)
            val nClipT = maxOf(clipT, cy)
            val nClipR = minOf(clipR, cx + cw)
            val nClipB = minOf(clipB, cy + ch)
            val intersectsClip = nClipR > nClipL && nClipB > nClipT && cw > 0 && ch > 0
            if (collectStripOnly && !intersectsClip) {
                continue
            }

            val cHwnd = c.hwnd ?: return false
            if (IsWindow(cHwnd) == 0) return false
            val opKind = resolveEligibleOpKind(c, c.actualWin32ClassName, cHwnd, isDark)
            if (opKind == OP_INELIGIBLE) return false
            totalVisibleDescendants++

            if (!collectStripOnly && (isFullViewport || (cx >= dl && cy >= dt && cx + cw <= dr && cy + ch <= db))) {
                validatedHwnds.add(cHwnd)
            }

            if (intersectsClip) {
                if (opKind != KIND_SKIP_TRANSPARENT) {
                    emitDrawOp(opKind, c, cHwnd, cx, cy, cw, ch, nClipL, nClipT, nClipR, nClipB)
                }
                if (c.children.isNotEmpty()) {
                    if (!collectSubtree(
                            parent = c,
                            originX = cx,
                            originY = cy,
                            clipL = nClipL,
                            clipT = nClipT,
                            clipR = nClipR,
                            clipB = nClipB,
                            dl = dl,
                            dt = dt,
                            dr = dr,
                            db = db,
                            isFullViewport = isFullViewport,
                            scrollX = scrollX,
                            scrollY = scrollY,
                            isDirectChildOfScrollView = false,
                            isDark = isDark,
                            collectStripOnly = collectStripOnly
                        )
                    ) {
                        return false
                    }
                }
            } else if (!collectStripOnly && isFullViewport && c.children.isNotEmpty()) {
                if (!collectSubtree(
                        parent = c,
                        originX = cx,
                        originY = cy,
                        clipL = 0,
                        clipT = 0,
                        clipR = 0,
                        clipB = 0,
                        dl = dl,
                        dt = dt,
                        dr = dr,
                        db = db,
                        isFullViewport = true,
                        scrollX = scrollX,
                        scrollY = scrollY,
                        isDirectChildOfScrollView = false,
                        isDark = isDark,
                        collectStripOnly = false
                    )
                ) {
                    return false
                }
            }
        }
        return true
    }

    private fun hasAnyDirtyDirectChild(svHandle: DesktopNativeWidget): Boolean {
        val children = svHandle.children
        for (i in children.indices) {
            val c = children[i] as? Win32HwndHandle ?: continue
            if (!c.effectiveVisible ||
                c.effectiveOpacity <= 0.001f ||
                Win32ControlMessageRouter.isViewportCulled(c)
            ) {
                continue
            }
            val ch = c.hwnd ?: continue
            if (GetUpdateRect(ch, null, 0) != 0) return true
        }
        return false
    }

    private fun validateHandleSubtree(handle: Win32HwndHandle) {
        val h = handle.hwnd
        if (h != null && IsWindow(h) != 0) {
            ValidateRect(h, null)
        }
        val children = handle.children
        for (i in children.indices) {
            val c = children[i] as? Win32HwndHandle ?: continue
            if (c.effectiveVisible && c.effectiveOpacity > 0.001f && !Win32ControlMessageRouter.isViewportCulled(c)) {
                validateHandleSubtree(c)
            }
        }
    }

    private fun validateCollectedHwnds(checkIsWindow: Boolean) {
        for (i in validatedHwnds.indices) {
            val h = validatedHwnds[i]
            if (!checkIsWindow || IsWindow(h) != 0) {
                ValidateRect(h, null)
            }
        }
    }

    private fun renderOpsIntoBackbuffer(
        svHwnd: HWND,
        svHandle: Win32HwndHandle,
        svHasCustomPaint: Boolean,
        compDc: HDC,
        bbDc: HDC,
        viewW: Int,
        viewH: Int,
        dirtyL: Int,
        dirtyT: Int,
        dirtyR: Int,
        dirtyB: Int,
        isDark: Boolean,
        dstOffX: Int = 0,
        dstOffY: Int = 0
    ): Boolean {
        if (svHasCustomPaint) {
            if (dstOffX != 0 || dstOffY != 0) {
                SelectClipRgn(bbDc, null)
                IntersectClipRect(bbDc, dirtyL + dstOffX, dirtyT + dstOffY, dirtyR + dstOffX, dirtyB + dstOffY)
                SetViewportOrgEx(bbDc, dstOffX, dstOffY, null)
                try {
                    Win32StyledWidgetHelper.paintCustomEntry(
                        entry = svHandle,
                        hwnd = svHwnd,
                        hdc = bbDc,
                        width = viewW,
                        height = viewH,
                        dirtyLeft = dirtyL,
                        dirtyTop = dirtyT,
                        dirtyRight = dirtyR,
                        dirtyBottom = dirtyB
                    )
                } finally {
                    SetViewportOrgEx(bbDc, 0, 0, null)
                    SelectClipRgn(bbDc, null)
                }
            } else {
                Win32StyledWidgetHelper.paintCustomEntry(
                    entry = svHandle,
                    hwnd = svHwnd,
                    hdc = bbDc,
                    width = viewW,
                    height = viewH,
                    dirtyLeft = dirtyL,
                    dirtyTop = dirtyT,
                    dirtyRight = dirtyR,
                    dirtyBottom = dirtyB
                )
            }
        } else {
            val fillW = if (dstOffX != 0) dirtyR + dstOffX else viewW
            val fillH = if (dstOffY != 0) dirtyB + dstOffY else viewH
            Win32StyledWidgetHelper.fillSolidParentBackdrop(
                hwnd = svHwnd,
                hdc = bbDc,
                width = fillW,
                height = fillH,
                isDark = isDark,
                dirtyLeft = dirtyL + dstOffX,
                dirtyTop = dirtyT + dstOffY,
                dirtyRight = dirtyR + dstOffX,
                dirtyBottom = dirtyB + dstOffY
            )
        }

        for (i in 0 until drawOpCount) {
            val op = drawOps[i]
            val opHandle = op.handle ?: continue
            val opHwnd = op.hwnd ?: continue
            when (op.kind) {
                KIND_STD_CACHED -> {
                    val ok = Win32RetainedControlPaintCache.paintCachedStandardControlToDc(
                        hwnd = opHwnd,
                        entry = opHandle,
                        targetDc = bbDc,
                        formatDc = compDc,
                        dstX = op.x + dstOffX,
                        dstY = op.y + dstOffY,
                        clipLeft = op.clipL + dstOffX,
                        clipTop = op.clipT + dstOffY,
                        clipRight = op.clipR + dstOffX,
                        clipBottom = op.clipB + dstOffY,
                        isDark = isDark,
                        isTransparentStage = false
                    )
                    if (!ok) return false
                }
                KIND_BOOTSTRAP_BUTTON -> {
                    val btnOwner = opHandle.ownerImpl as? WindowsButtonNativeImpl ?: return false
                    SelectClipRgn(bbDc, null)
                    IntersectClipRect(bbDc, op.clipL + dstOffX, op.clipT + dstOffY, op.clipR + dstOffX, op.clipB + dstOffY)
                    SetViewportOrgEx(bbDc, op.x + dstOffX, op.y + dstOffY, null)
                    try {
                        Win32StyledWidgetHelper.paintCustomBootstrapButtonToHdc(
                            entry = opHandle,
                            hwnd = opHwnd,
                            hdc = bbDc,
                            width = op.w,
                            height = op.h,
                            owner = btnOwner
                        )
                    } finally {
                        SetViewportOrgEx(bbDc, 0, 0, null)
                        SelectClipRgn(bbDc, null)
                    }
                }
                KIND_CUSTOM_STYLED -> {
                    SelectClipRgn(bbDc, null)
                    IntersectClipRect(bbDc, op.clipL + dstOffX, op.clipT + dstOffY, op.clipR + dstOffX, op.clipB + dstOffY)
                    SetViewportOrgEx(bbDc, op.x + dstOffX, op.y + dstOffY, null)
                    try {
                        Win32StyledWidgetHelper.paintCustomEntry(
                            entry = opHandle,
                            hwnd = opHwnd,
                            hdc = bbDc,
                            width = op.w,
                            height = op.h,
                            dirtyLeft = (op.clipL - op.x).coerceIn(0, op.w),
                            dirtyTop = (op.clipT - op.y).coerceIn(0, op.h),
                            dirtyRight = (op.clipR - op.x).coerceIn(0, op.w),
                            dirtyBottom = (op.clipB - op.y).coerceIn(0, op.h)
                        )
                    } finally {
                        SetViewportOrgEx(bbDc, 0, 0, null)
                        SelectClipRgn(bbDc, null)
                    }
                }
            }
        }
        return true
    }

    /**
     * Attempts to service `WM_PAINT` for [scrollView] (`[svHwnd]`) from the retained offscreen
     * backbuffer via unchanged-frame blit, incremental scroll-strip shift + paint, or full viewport
     * compositing. Returns `true` if handled (with [svHwnd] and composited descendants validated),
     * or `false` cleanly to fall back to per-`HWND` Win32 painting.
     */
    internal fun tryCompositeScrollViewPaint(
        scrollView: WindowsScrollViewNativeImpl,
        svHwnd: HWND
    ): Boolean {
        if (compositeDepth > 0 || IsWindow(svHwnd) == 0 || IsWindowVisible(svHwnd) == 0) return false
        if (!isScrollViewRootEligible(scrollView, svHwnd)) return false

        val clientRc = scratchClientRc ?: nativeHeap.alloc<RECT>().also { scratchClientRc = it }
        val updateRc = scratchUpdateRc ?: nativeHeap.alloc<RECT>().also { scratchUpdateRc = it }
        if (GetClientRect(svHwnd, clientRc.ptr) == 0) return false
        val viewW = clientRc.right - clientRc.left
        val viewH = clientRc.bottom - clientRc.top
        if (viewW <= 0 || viewH <= 0) return false

        if (GetUpdateRect(svHwnd, updateRc.ptr, 0) == 0) return false
        val dl = updateRc.left.coerceIn(0, viewW)
        val dt = updateRc.top.coerceIn(0, viewH)
        val dr = updateRc.right.coerceIn(dl, viewW)
        val db = updateRc.bottom.coerceIn(dt, viewH)
        if (dr <= dl || db <= dt) {
            ValidateRect(svHwnd, null)
            return true
        }

        val isDark = Win32NativeWindowHost.isEffectivelyDarkTheme()
        val svHandle = scrollView.nativeWidget
        val svSpec = Win32StyledWidgetHelper.resolveEffectiveVisualSpec(
            entry = svHandle,
            owner = scrollView,
            win32Class = svHandle.actualWin32ClassName,
            isDark = isDark
        )
        if (svSpec.hasBackdropFilter || svSpec.filter?.hasActiveEffects == true) return false
        val svHasCustomPaint = svSpec.hasCustomBackground ||
            svSpec.hasBorder ||
            svSpec.hasFocusRing ||
            svSpec.boxShadowColor != null

        val curNonOverlayVer = DesktopNativeWidget.globalNonOverlayMutationVersion
        val curAncestorEpoch = Win32StyledWidgetHelper.ancestorBgCacheEpoch
        val curContentEpoch = contentEpoch
        val curScrollX = scrollView.currentScrollX()
        val curScrollY = scrollView.currentScrollY()
        val isFullViewport = (dl == 0 && dt == 0 && dr == viewW && db == viewH)

        val baseBackbufferMatches = backbufferDc != null &&
            backbufferBmp != null &&
            backbufferOwnerHwnd == svHwnd &&
            backbufferViewW == viewW &&
            backbufferViewH == viewH &&
            backbufferIsDark == isDark &&
            backbufferNonOverlayVer == curNonOverlayVer &&
            backbufferAncestorEpoch == curAncestorEpoch &&
            backbufferContentEpoch == curContentEpoch

        // Fast Path A: exact retained backbuffer hit (unchanged frame / RedrawWindow benchmark)
        if (baseBackbufferMatches && backbufferScrollX == curScrollX && backbufferScrollY == curScrollY) {
            compositeDepth++
            try {
                val bbDc = backbufferDc ?: return false
                ValidateRect(svHwnd, null)
                val compDc = GetDCEx(svHwnd, null, DCX_CACHE_FLAG or DCX_CLIPSIBLINGS_FLAG) ?: return false
                val blitted = try {
                    blitRingRectToDc(compDc, bbDc, viewH, backbufferRingY, dl, dt, dr - dl, db - dt)
                } finally {
                    ReleaseDC(svHwnd, compDc)
                }
                if (!blitted) return false
                if (isFullViewport) {
                    validateCollectedHwnds(checkIsWindow = true)
                }
                return true
            } finally {
                compositeDepth--
            }
        }

        // Fast Path B: incremental vertical (O(1) ring buffer + strip) or horizontal scroll shift
        val canShiftVert = baseBackbufferMatches &&
            isFullViewport &&
            backbufferScrollX == curScrollX &&
            curScrollY != backbufferScrollY &&
            abs(curScrollY - backbufferScrollY) < viewH
        val canShiftHorz = !canShiftVert &&
            backbufferRingY == 0 &&
            baseBackbufferMatches &&
            isFullViewport &&
            backbufferScrollY == curScrollY &&
            curScrollX != backbufferScrollX &&
            abs(curScrollX - backbufferScrollX) < viewW

        if (canShiftVert || canShiftHorz) {
            val dy = curScrollY - backbufferScrollY
            val dx = curScrollX - backbufferScrollX
            val stripL: Int
            val stripT: Int
            val stripR: Int
            val stripB: Int
            if (canShiftVert) {
                if (dy > 0) {
                    stripL = 0
                    stripT = viewH - dy
                    stripR = viewW
                    stripB = viewH
                } else {
                    stripL = 0
                    stripT = 0
                    stripR = viewW
                    stripB = -dy
                }
            } else {
                if (dx > 0) {
                    stripL = viewW - dx
                    stripT = 0
                    stripR = viewW
                    stripB = viewH
                } else {
                    stripL = 0
                    stripT = 0
                    stripR = -dx
                    stripB = viewH
                }
            }

            val dirtyDirectChild = hasAnyDirtyDirectChild(svHandle)
            val collectStripOnly = !dirtyDirectChild
            scratchShownHandles.clear()
            val hasShown = scrollView.drainPendingShownHandles(scratchShownHandles)
            drawOpCount = 0
            if (!collectStripOnly) {
                validatedHwnds.clear()
            }
            totalVisibleDescendants = 0
            val collectedOk = collectSubtree(
                parent = svHandle,
                originX = 0,
                originY = 0,
                clipL = stripL,
                clipT = stripT,
                clipR = stripR,
                clipB = stripB,
                dl = 0,
                dt = 0,
                dr = viewW,
                db = viewH,
                isFullViewport = true,
                scrollX = curScrollX,
                scrollY = curScrollY,
                isDirectChildOfScrollView = true,
                isDark = isDark,
                collectStripOnly = collectStripOnly
            )
            if (!collectedOk || (!collectStripOnly && totalVisibleDescendants < 2)) {
                scratchShownHandles.clear()
                backbufferOwnerHwnd = null
                return false
            }

            compositeDepth++
            try {
                ValidateRect(svHwnd, null)
                val compDc = GetDCEx(svHwnd, null, DCX_CACHE_FLAG or DCX_CLIPSIBLINGS_FLAG) ?: run {
                    scratchShownHandles.clear()
                    return false
                }
                var updatedRingY = backbufferRingY
                val rendered = try {
                    val bbDc = backbufferDc ?: run {
                        backbufferOwnerHwnd = null
                        return false
                    }
                    if (canShiftVert) {
                        val nextRingY = ((backbufferRingY + dy) % viewH + viewH) % viewH
                        val stripW = stripR - stripL
                        val stripH = stripB - stripT
                        val sDc = ensureStripBuffer(compDc, stripW, stripH) ?: run {
                            backbufferOwnerHwnd = null
                            return false
                        }
                        if (!renderOpsIntoBackbuffer(
                                svHwnd = svHwnd,
                                svHandle = svHandle,
                                svHasCustomPaint = svHasCustomPaint,
                                compDc = compDc,
                                bbDc = sDc,
                                viewW = viewW,
                                viewH = viewH,
                                dirtyL = stripL,
                                dirtyT = stripT,
                                dirtyR = stripR,
                                dirtyB = stripB,
                                isDark = isDark,
                                dstOffX = -stripL,
                                dstOffY = -stripT
                            )
                        ) {
                            backbufferOwnerHwnd = null
                            return false
                        }
                        copyStripIntoRingBackbuffer(bbDc, sDc, viewH, nextRingY, stripL, stripT, stripW, stripH)
                        updatedRingY = nextRingY
                        val scrollUpdRc = scratchScrollUpdateRc ?: nativeHeap.alloc<RECT>().also { scratchScrollUpdateRc = it }
                        val scrolledOk = ScrollDC(compDc, 0, -dy, null, null, null, scrollUpdRc.ptr) != 0
                        if (scrolledOk) {
                            val ul = scrollUpdRc.left.coerceIn(0, viewW)
                            val ut = scrollUpdRc.top.coerceIn(0, viewH)
                            val ur = scrollUpdRc.right.coerceIn(ul, viewW)
                            val ub = scrollUpdRc.bottom.coerceIn(ut, viewH)
                            if (ur <= ul || ub <= ut || (ul >= stripL && ut >= stripT && ur <= stripR && ub <= stripB)) {
                                BitBlt(compDc, stripL, stripT, stripW, stripH, sDc, 0, 0, SRCCOPY) != 0
                            } else {
                                val bl = minOf(stripL, ul)
                                val bt = minOf(stripT, ut)
                                val br = maxOf(stripR, ur)
                                val bb = maxOf(stripB, ub)
                                blitRingRectToDc(compDc, bbDc, viewH, nextRingY, bl, bt, br - bl, bb - bt)
                            }
                        } else {
                            blitRingRectToDc(compDc, bbDc, viewH, nextRingY, 0, 0, viewW, viewH)
                        }
                    } else {
                        if (dx > 0) {
                            BitBlt(bbDc, 0, 0, viewW - dx, viewH, bbDc, dx, 0, SRCCOPY)
                        } else {
                            BitBlt(bbDc, -dx, 0, viewW + dx, viewH, bbDc, 0, 0, SRCCOPY)
                        }
                        if (!renderOpsIntoBackbuffer(
                                svHwnd = svHwnd,
                                svHandle = svHandle,
                                svHasCustomPaint = svHasCustomPaint,
                                compDc = compDc,
                                bbDc = bbDc,
                                viewW = viewW,
                                viewH = viewH,
                                dirtyL = stripL,
                                dirtyT = stripT,
                                dirtyR = stripR,
                                dirtyB = stripB,
                                isDark = isDark
                            )
                        ) {
                            backbufferOwnerHwnd = null
                            return false
                        }
                        val scrollUpdRc = scratchScrollUpdateRc ?: nativeHeap.alloc<RECT>().also { scratchScrollUpdateRc = it }
                        val scrolledOk = ScrollDC(compDc, -dx, 0, null, null, null, scrollUpdRc.ptr) != 0
                        if (scrolledOk) {
                            val ul = scrollUpdRc.left.coerceIn(0, viewW)
                            val ut = scrollUpdRc.top.coerceIn(0, viewH)
                            val ur = scrollUpdRc.right.coerceIn(ul, viewW)
                            val ub = scrollUpdRc.bottom.coerceIn(ut, viewH)
                            val bl = if (ur > ul) minOf(stripL, ul) else stripL
                            val bt = if (ub > ut) minOf(stripT, ut) else stripT
                            val br = if (ur > ul) maxOf(stripR, ur) else stripR
                            val bb = if (ub > ut) maxOf(stripB, ub) else stripB
                            if (br > bl && bb > bt) {
                                BitBlt(compDc, bl, bt, br - bl, bb - bt, bbDc, bl, bt, SRCCOPY) != 0
                            } else {
                                true
                            }
                        } else {
                            BitBlt(compDc, 0, 0, viewW, viewH, bbDc, 0, 0, SRCCOPY) != 0
                        }
                    }
                } finally {
                    ReleaseDC(svHwnd, compDc)
                }
                if (!rendered) {
                    InvalidateRect(svHwnd, null, 0)
                    scratchShownHandles.clear()
                    backbufferOwnerHwnd = null
                    return false
                }
                backbufferScrollX = curScrollX
                backbufferScrollY = curScrollY
                backbufferRingY = updatedRingY
                ValidateRect(svHwnd, null)
                if (dirtyDirectChild) {
                    validateCollectedHwnds(checkIsWindow = false)
                } else if (hasShown) {
                    for (i in scratchShownHandles.indices) {
                        validateHandleSubtree(scratchShownHandles[i])
                    }
                }
                scratchShownHandles.clear()
                return true
            } finally {
                compositeDepth--
            }
        }

        // Normal full or partial viewport composite path
        if (isFullViewport) {
            scratchShownHandles.clear()
            scrollView.drainPendingShownHandles(scratchShownHandles)
            scratchShownHandles.clear()
        }
        drawOpCount = 0
        validatedHwnds.clear()
        totalVisibleDescendants = 0
        if (!collectSubtree(
                parent = svHandle,
                originX = 0,
                originY = 0,
                clipL = dl,
                clipT = dt,
                clipR = dr,
                clipB = db,
                dl = dl,
                dt = dt,
                dr = dr,
                db = db,
                isFullViewport = isFullViewport,
                scrollX = curScrollX,
                scrollY = curScrollY,
                isDirectChildOfScrollView = true,
                isDark = isDark,
                collectStripOnly = false
            ) || totalVisibleDescendants < 2
        ) {
            return false
        }

        compositeDepth++
        try {
            val compDc = GetDCEx(svHwnd, null, DCX_CACHE_FLAG or DCX_CLIPSIBLINGS_FLAG) ?: return false
            val rendered = try {
                backbufferOwnerHwnd = null
                backbufferRingY = 0
                val bbDc = ensureBackbuffer(compDc, viewW, viewH) ?: return false
                if (!renderOpsIntoBackbuffer(
                        svHwnd = svHwnd,
                        svHandle = svHandle,
                        svHasCustomPaint = svHasCustomPaint,
                        compDc = compDc,
                        bbDc = bbDc,
                        viewW = viewW,
                        viewH = viewH,
                        dirtyL = dl,
                        dirtyT = dt,
                        dirtyR = dr,
                        dirtyB = db,
                        isDark = isDark
                    )
                ) {
                    return false
                }
                BitBlt(compDc, dl, dt, dr - dl, db - dt, bbDc, dl, dt, SRCCOPY) != 0
            } finally {
                ReleaseDC(svHwnd, compDc)
            }
            if (!rendered) return false

            if (isFullViewport) {
                backbufferOwnerHwnd = svHwnd
                backbufferViewW = viewW
                backbufferViewH = viewH
                backbufferScrollX = curScrollX
                backbufferScrollY = curScrollY
                backbufferIsDark = isDark
                backbufferNonOverlayVer = curNonOverlayVer
                backbufferAncestorEpoch = curAncestorEpoch
                backbufferContentEpoch = curContentEpoch
            }
            ValidateRect(svHwnd, null)
            validateCollectedHwnds(checkIsWindow = false)
            return true
        } finally {
            compositeDepth--
        }
    }
}
