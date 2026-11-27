@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.widget.DesktopTextNativeImpl
import com.nexustools.genericui.windows.impl.WindowsButtonNativeImpl
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toLong
import platform.windows.BitBlt
import platform.windows.BeginPaint
import platform.windows.CreateCompatibleBitmap
import platform.windows.CreateCompatibleDC
import platform.windows.DeleteObject
import platform.windows.EndPaint
import platform.windows.FillRect
import platform.windows.GetClientRect
import platform.windows.HBITMAP
import platform.windows.HDC
import platform.windows.HWND
import platform.windows.PAINTSTRUCT
import platform.windows.RECT
import platform.windows.SRCCOPY
import platform.windows.SelectObject
import platform.windows.SendMessageW

/**
 * Per-`HWND` offscreen `HBITMAP` paint cache for native Win32 `"Static"` labels and native
 * ComCtl32 `"Button"` push-buttons.
 *
 * On cache miss (initial paint or any change to size, theme, style flags, font, text, colors, or
 * button hot/pressed/focused/enabled state), Win32 renders the genuine native control via
 * `WM_PRINTCLIENT` into a shared offscreen memory DC (`sharedMemDc`). Subsequent repaints (`BitBlt`
 * of the dirty `ps.rcPaint` rectangle) avoid repeated ComCtl32/UxTheme/ClearType re-rasterization.
 */
object Win32RetainedControlPaintCache {

    private const val MAX_CACHED_CONTROLS: Int = 256
    private const val MAX_CACHED_WIDTH_PX: Int = 4096
    private const val MAX_CACHED_HEIGHT_PX: Int = 512
    private const val BM_GETSTATE_MSG: UInt = 0x00F2u
    private const val WS_BORDER_MASK: Long = 0x00800000L
    private const val WS_EX_CLIENTEDGE_MASK: Long = 0x00000200L

    private class CachedControlBitmap(
        var bitmap: HBITMAP,
        var width: Int,
        var height: Int,
        var isDark: Boolean,
        var dwStyle: Long,
        var fontKey: Long,
        var selfAttrVer: Long,
        var ancestorBgToken: Long,
        var bgRef: UInt,
        var fgRef: UInt,
        var text: String,
        var icon: String,
        var stateBits: Int
    ) {
        fun baseFieldsMatch(
            width: Int,
            height: Int,
            isDark: Boolean,
            dwStyle: Long,
            fontKey: Long,
            selfAttrVer: Long,
            fgRef: UInt,
            text: String,
            icon: String,
            stateBits: Int
        ): Boolean =
            this.width == width &&
                this.height == height &&
                this.isDark == isDark &&
                this.dwStyle == dwStyle &&
                this.fontKey == fontKey &&
                this.selfAttrVer == selfAttrVer &&
                this.fgRef == fgRef &&
                this.text == text &&
                this.icon == icon &&
                this.stateBits == stateBits
    }

    private var sharedMemDc: HDC? = null
    private val cacheByHwnd = LinkedHashMap<Long, CachedControlBitmap>()
    private var paintReentryDepth: Int = 0
    private var scratchClientRc: RECT? = null
    private var scratchPaintStruct: PAINTSTRUCT? = null
    private var scratchFullRc: RECT? = null

    private inline fun withPaintStructs(
        block: (clientRc: RECT, ps: PAINTSTRUCT, fullRc: RECT) -> Boolean
    ): Boolean {
        if (paintReentryDepth == 0) {
            val clientRc = scratchClientRc ?: nativeHeap.alloc<RECT>().also { scratchClientRc = it }
            val ps = scratchPaintStruct ?: nativeHeap.alloc<PAINTSTRUCT>().also { scratchPaintStruct = it }
            val fullRc = scratchFullRc ?: nativeHeap.alloc<RECT>().also { scratchFullRc = it }
            paintReentryDepth++
            return try {
                block(clientRc, ps, fullRc)
            } finally {
                paintReentryDepth--
            }
        }
        return memScoped {
            block(alloc(), alloc(), alloc())
        }
    }

    private fun obtainSharedMemDc(): HDC? {
        sharedMemDc?.let { return it }
        return CreateCompatibleDC(null)?.also { sharedMemDc = it }
    }

    /**
     * Deletes and removes any cached offscreen `HBITMAP` associated with [hwnd].
     */
    fun invalidate(hwnd: HWND?) {
        Win32ScrollViewCompositor.invalidateBackbufferContent()
        val key = hwnd?.toLong() ?: return
        val removed = cacheByHwnd.remove(key) ?: return
        DeleteObject(removed.bitmap)
    }

    /**
     * Deletes and clears all cached offscreen `HBITMAP`s (for example on system theme changes).
     */
    fun invalidateAll() {
        Win32ScrollViewCompositor.invalidateBackbufferContent()
        if (cacheByHwnd.isEmpty()) return
        for (entry in cacheByHwnd.values) {
            DeleteObject(entry.bitmap)
        }
        cacheByHwnd.clear()
    }

    private fun evictOldestIfNeeded() {
        while (cacheByHwnd.size >= MAX_CACHED_CONTROLS) {
            val iter = cacheByHwnd.entries.iterator()
            if (!iter.hasNext()) break
            val oldest = iter.next()
            iter.remove()
            DeleteObject(oldest.value.bitmap)
        }
    }

    /**
     * Attempts to handle `WM_PAINT` for a `"Static"` or native default `"Button"` control from the
     * offscreen `HBITMAP` cache, rendering on demand via [renderViaPrintClient]. Returns `false` if
     * the control is ineligible (without calling `BeginPaint`) so callers can fall through normally.
     */
    fun tryPaintCachedStandardControl(
        hwnd: HWND,
        entry: Win32HwndHandle,
        isDark: Boolean,
        isTransparentStage: Boolean,
        renderViaPrintClient: (HDC) -> Unit
    ): Boolean {
        if (isTransparentStage) return false
        val owner = entry.ownerImpl ?: return false
        val className = entry.actualWin32ClassName
        val isStatic = className == "Static" && owner is DesktopTextNativeImpl<*>
        val isDefaultButton = className == "Button" &&
            (entry.dwStyle and 0x0000000FL) <= 1L &&
            owner is WindowsButtonNativeImpl &&
            owner.isNativeDefaultButtonMode()
        if (!isStatic && !isDefaultButton) return false

        val memDc = obtainSharedMemDc() ?: return false

        return withPaintStructs { clientRc, ps, fullRc ->
            val canUseAppliedBounds = entry.lastAppliedW > 0 &&
                entry.lastAppliedH > 0 &&
                (entry.dwStyle and WS_BORDER_MASK) == 0L &&
                (entry.dwExStyle and WS_EX_CLIENTEDGE_MASK) == 0L
            val w: Int
            val h: Int
            if (canUseAppliedBounds) {
                w = entry.lastAppliedW
                h = entry.lastAppliedH
            } else {
                if (GetClientRect(hwnd, clientRc.ptr) == 0) return@withPaintStructs false
                w = clientRc.right - clientRc.left
                h = clientRc.bottom - clientRc.top
            }
            if (w !in 1..MAX_CACHED_WIDTH_PX || h !in 1..MAX_CACHED_HEIGHT_PX) {
                return@withPaintStructs false
            }

            val hdc = BeginPaint(hwnd, ps.ptr)
            try {
                if (hdc != null &&
                    ps.rcPaint.right > ps.rcPaint.left &&
                    ps.rcPaint.bottom > ps.rcPaint.top
                ) {
                    paintCachedBitmapToTarget(
                        hwnd = hwnd,
                        entry = entry,
                        targetDc = hdc,
                        formatDc = hdc,
                        memDc = memDc,
                        dstX = 0,
                        dstY = 0,
                        w = w,
                        h = h,
                        clipLeft = ps.rcPaint.left,
                        clipTop = ps.rcPaint.top,
                        clipRight = ps.rcPaint.right,
                        clipBottom = ps.rcPaint.bottom,
                        isDark = isDark,
                        isStatic = isStatic,
                        isDefaultButton = isDefaultButton,
                        fullRc = fullRc,
                        fallbackOnBitmapFailure = true,
                        renderViaPrintClient = renderViaPrintClient
                    )
                }
            } finally {
                EndPaint(hwnd, ps.ptr)
            }
            true
        }
    }

    /**
     * Composites a cached `"Static"` or native default `"Button"` directly into [targetDc] at
     * `([dstX], [dstY])` clipped to `([clipLeft], [clipTop], [clipRight], [clipBottom])` without
     * opening a per-control `BeginPaint` session. On cache miss, creates a compatible bitmap from
     * [formatDc] and renders the control via `WM_PRINTCLIENT`.
     */
    internal fun paintCachedStandardControlToDc(
        hwnd: HWND,
        entry: Win32HwndHandle,
        targetDc: HDC,
        formatDc: HDC,
        dstX: Int,
        dstY: Int,
        clipLeft: Int,
        clipTop: Int,
        clipRight: Int,
        clipBottom: Int,
        isDark: Boolean,
        isTransparentStage: Boolean
    ): Boolean {
        if (isTransparentStage) return false
        val owner = entry.ownerImpl ?: return false
        val className = entry.actualWin32ClassName
        val isStatic = className == "Static" && owner is DesktopTextNativeImpl<*>
        val isDefaultButton = className == "Button" &&
            (entry.dwStyle and 0x0000000FL) <= 1L &&
            owner is WindowsButtonNativeImpl &&
            owner.isNativeDefaultButtonMode()
        if (!isStatic && !isDefaultButton) return false

        val memDc = obtainSharedMemDc() ?: return false

        return withPaintStructs { clientRc, _, fullRc ->
            val canUseAppliedBounds = entry.lastAppliedW > 0 &&
                entry.lastAppliedH > 0 &&
                (entry.dwStyle and WS_BORDER_MASK) == 0L &&
                (entry.dwExStyle and WS_EX_CLIENTEDGE_MASK) == 0L
            val w: Int
            val h: Int
            if (canUseAppliedBounds) {
                w = entry.lastAppliedW
                h = entry.lastAppliedH
            } else {
                if (GetClientRect(hwnd, clientRc.ptr) == 0) return@withPaintStructs false
                w = clientRc.right - clientRc.left
                h = clientRc.bottom - clientRc.top
            }
            if (w !in 1..MAX_CACHED_WIDTH_PX || h !in 1..MAX_CACHED_HEIGHT_PX) {
                return@withPaintStructs false
            }

            paintCachedBitmapToTarget(
                hwnd = hwnd,
                entry = entry,
                targetDc = targetDc,
                formatDc = formatDc,
                memDc = memDc,
                dstX = dstX,
                dstY = dstY,
                w = w,
                h = h,
                clipLeft = clipLeft,
                clipTop = clipTop,
                clipRight = clipRight,
                clipBottom = clipBottom,
                isDark = isDark,
                isStatic = isStatic,
                isDefaultButton = isDefaultButton,
                fullRc = fullRc,
                fallbackOnBitmapFailure = false,
                renderViaPrintClient = { dc ->
                    SendMessageW(hwnd, 0x0318u /* WM_PRINTCLIENT */, dc.toLong().toULong(), 0x00000006L)
                }
            )
        }
    }

    private inline fun paintCachedBitmapToTarget(
        hwnd: HWND,
        entry: Win32HwndHandle,
        targetDc: HDC,
        formatDc: HDC,
        memDc: HDC,
        dstX: Int,
        dstY: Int,
        w: Int,
        h: Int,
        clipLeft: Int,
        clipTop: Int,
        clipRight: Int,
        clipBottom: Int,
        isDark: Boolean,
        isStatic: Boolean,
        isDefaultButton: Boolean,
        fullRc: RECT,
        fallbackOnBitmapFailure: Boolean,
        renderViaPrintClient: (HDC) -> Unit
    ): Boolean {
        val srcL = (clipLeft - dstX).coerceIn(0, w)
        val srcT = (clipTop - dstY).coerceIn(0, h)
        val srcR = (clipRight - dstX).coerceIn(srcL, w)
        val srcB = (clipBottom - dstY).coerceIn(srcT, h)
        val dw = srcR - srcL
        val dh = srcB - srcT
        if (dw <= 0 || dh <= 0) return true

        val owner = entry.ownerImpl ?: return false
        val fgRef = if (entry.hasCustomColors) {
            entry.fgColorRef
        } else if (isDark) {
            Win32DwmUxThemeBridge.colorRefRgb(242, 242, 245)
        } else {
            Win32DwmUxThemeBridge.colorRefRgb(20, 20, 24)
        }

        val fontKey = entry.fontHandle?.toLong() ?: 0L
        val selfAttrVer = entry.selfAttrMutationVersion
        val dwStyle = entry.dwStyle
        val text: String
        val icon: String
        val stateBits: Int
        if (isStatic) {
            val textOwner = owner as DesktopTextNativeImpl<*>
            text = textOwner.getText()
            icon = ""
            stateBits = 0
        } else {
            val btnOwner = owner as WindowsButtonNativeImpl
            text = btnOwner.getText()
            icon = btnOwner.getIcon() ?: ""
            val needsNativeStateQuery = btnOwner.isHoveredState() ||
                btnOwner.isActivePressedState() ||
                btnOwner.isFocusedState()
            val nativeBtnState = if (needsNativeStateQuery) {
                SendMessageW(hwnd, BM_GETSTATE_MSG, 0uL, 0L).toInt() and 0xFF
            } else {
                0
            }
            stateBits = (if (btnOwner.isHoveredState()) 1 else 0) or
                (if (btnOwner.isActivePressedState()) 2 else 0) or
                (if (btnOwner.isFocusedState()) 4 else 0) or
                (if (btnOwner.isEnabled()) 8 else 0) or
                (nativeBtnState shl 4)
        }

        val isTransparentOrButton = entry.isTransparentCtlColor || isDefaultButton
        val curBgToken = if (isTransparentOrButton) {
            (DesktopNativeWidget.globalNonOverlayMutationVersion shl 20) xor
                Win32StyledWidgetHelper.ancestorBgCacheEpoch
        } else {
            -1L
        }

        val key = hwnd.toLong()
        val existing = cacheByHwnd[key]
        val baseMatches = existing != null && existing.baseFieldsMatch(
            width = w,
            height = h,
            isDark = isDark,
            dwStyle = dwStyle,
            fontKey = fontKey,
            selfAttrVer = selfAttrVer,
            fgRef = fgRef,
            text = text,
            icon = icon,
            stateBits = stateBits
        )

        val resolvedBgRef: UInt
        val isHit: Boolean
        if (baseMatches &&
            existing != null &&
            ((isTransparentOrButton && existing.ancestorBgToken == curBgToken) ||
                (!isTransparentOrButton && existing.bgRef == entry.bgColorRef))
        ) {
            resolvedBgRef = existing.bgRef
            isHit = true
        } else {
            resolvedBgRef = if (isTransparentOrButton) {
                val inheritedBg = Win32StyledWidgetHelper.resolveAncestorBackgroundColor(hwnd, isDark)
                Win32DwmUxThemeBridge.colorRefRgb(inheritedBg.r, inheritedBg.g, inheritedBg.b)
            } else {
                entry.bgColorRef
            }
            if (baseMatches && existing != null && existing.bgRef == resolvedBgRef) {
                existing.ancestorBgToken = curBgToken
                isHit = true
            } else {
                isHit = false
            }
        }

        val bitmap: HBITMAP
        if (isHit && existing != null) {
            bitmap = existing.bitmap
        } else {
            val reusedExisting = existing != null && existing.width == w && existing.height == h
            val reusableBmp = if (reusedExisting && existing != null) {
                existing.bitmap
            } else {
                if (existing != null) {
                    cacheByHwnd.remove(key)
                    DeleteObject(existing.bitmap)
                }
                val createdBmp = CreateCompatibleBitmap(formatDc, w, h)
                if (createdBmp == null) {
                    if (!fallbackOnBitmapFailure) return false
                    fullRc.left = 0
                    fullRc.top = 0
                    fullRc.right = w
                    fullRc.bottom = h
                    Win32ControlMessageRouter.obtainCachedSolidBrush(resolvedBgRef)?.let { brush ->
                        FillRect(targetDc, fullRc.ptr, brush)
                    }
                    renderViaPrintClient(targetDc)
                    return true
                }
                evictOldestIfNeeded()
                createdBmp
            }
            bitmap = reusableBmp
            if (reusedExisting && existing != null) {
                existing.ancestorBgToken = curBgToken
                existing.bgRef = resolvedBgRef
                existing.fgRef = fgRef
                existing.text = text
                existing.icon = icon
                existing.stateBits = stateBits
            } else {
                cacheByHwnd[key] = CachedControlBitmap(
                    bitmap = reusableBmp,
                    width = w,
                    height = h,
                    isDark = isDark,
                    dwStyle = dwStyle,
                    fontKey = fontKey,
                    selfAttrVer = selfAttrVer,
                    ancestorBgToken = curBgToken,
                    bgRef = resolvedBgRef,
                    fgRef = fgRef,
                    text = text,
                    icon = icon,
                    stateBits = stateBits
                )
            }
        }

        val oldBmp = SelectObject(memDc, bitmap)
        try {
            if (!isHit) {
                fullRc.left = 0
                fullRc.top = 0
                fullRc.right = w
                fullRc.bottom = h
                Win32ControlMessageRouter.obtainCachedSolidBrush(resolvedBgRef)?.let { brush ->
                    FillRect(memDc, fullRc.ptr, brush)
                }
                renderViaPrintClient(memDc)
            }
            BitBlt(targetDc, dstX + srcL, dstY + srcT, dw, dh, memDc, srcL, srcT, SRCCOPY)
        } finally {
            SelectObject(memDc, oldBmp)
        }
        return true
    }
}
