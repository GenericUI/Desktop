@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopInteractiveSurfaceSpec
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.style.css.StyledVisualSpec
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toLong
import platform.windows.CreateSolidBrush
import platform.windows.DeleteObject
import platform.windows.FillRect
import platform.windows.GA_ROOT
import platform.windows.GetAncestor
import platform.windows.GetClientRect
import platform.windows.GetParent
import platform.windows.HDC
import platform.windows.HWND
import platform.windows.InvalidateRect
import platform.windows.IsWindow
import platform.windows.IsWindowVisible
import platform.windows.MapWindowPoints
import platform.windows.POINT
import platform.windows.RECT
import platform.windows.WS_CHILD
import platform.windows.WS_CLIPCHILDREN
import platform.windows.WS_CLIPSIBLINGS
import platform.windows.WS_VISIBLE
import kotlin.math.min
import kotlin.time.TimeSource

/**
 * Retained child `HWND` host (`"GenericUILayeredStageHostW"`, `WS_CHILD | WS_VISIBLE | WS_CLIPCHILDREN`)
 * backing `LayeredContainer` (`OverlayHostBox` / stage-overlay roots).
 *
 * Composites the `zIndex = 0` interactive/video stage child `HWND` cleanly beneath translucent and
 * `backdrop-filter` overlay child `HWND`s (`OverlayWindowTitleBar`, `subtitleBanner`, `SeekBar`,
 * `ExpandingVolumeSlider`, popovers), maintains the live offscreen stage backdrop array for
 * sub-millisecond in-memory `backdrop-filter` sampling, isolates VBlank frame repaints to the stage
 * and translucent overlays (`videoStageOnlyPaints`), and supports full `PrintWindow(..., PW_RENDERFULLCONTENT)`
 * screenshot capture.
 */
object Win32LayeredStageHostHwnd {

    private class StageHostState(
        val hostHandle: Win32HwndHandle,
        val container: DesktopBaseContainerNativeImpl<*>,
        var backdropPixels: IntArray = IntArray(0),
        var backdropW: Int = 0,
        var backdropH: Int = 0,
        var lastSyncedSwapCount: Long = -1L,
        var hudAutoHidden: Boolean = false
    )

    private val stageHostsByHwnd = HashMap<Long, StageHostState>()

    /**
     * Registers or updates [handle] as a `"GenericUILayeredStageHostW"` stage host.
     */
    fun registerStageHost(
        handle: Win32HwndHandle,
        container: DesktopBaseContainerNativeImpl<*>
    ) {
        val hwnd = handle.hwnd ?: return
        val key = hwnd.toLong()
        stageHostsByHwnd[key] = StageHostState(hostHandle = handle, container = container)
        handle.customPaintCallback = { h, hdc, w, hgt ->
            paintStageHostBackground(h, hdc, w, hgt)
        }
        if (handle.stageOverlaySpec == null && handle.attributes["data-overlay-root"] != "true") {
            val wasLayered = handle.attributes["isLayeredHost"] == true
            if (!wasLayered) {
                handle.attributes["isLayeredHost"] = true
                handle.notifyTreeInvalidated()
                val bounds = container.getBounds()
                if (bounds.width > 0 && bounds.height > 0) {
                    container.layoutChildrenInBounds(bounds.width.toFloat(), bounds.height.toFloat())
                }
            }
        }
    }

    fun unregisterStageHost(hwnd: HWND?) {
        if (hwnd != null) {
            stageHostsByHwnd.remove(hwnd.toLong())
        }
    }

    fun isStageHostHwnd(hwnd: HWND?): Boolean =
        hwnd != null && stageHostsByHwnd.containsKey(hwnd.toLong())

    internal fun hasAnyStageHosts(): Boolean = stageHostsByHwnd.isNotEmpty()

    internal fun isDescendantOfStageHost(hwnd: HWND): Boolean {
        if (stageHostsByHwnd.isEmpty()) return false
        var cur: HWND? = GetParent(hwnd)
        var hops = 0
        while (cur != null && hops < 64) {
            if (stageHostsByHwnd.containsKey(cur.toLong())) return true
            cur = GetParent(cur)
            hops++
        }
        return false
    }

    /**
     * Detects behaviorally (zero class/ID/text hardcoding) whether [container] is a layered stage
     * container (`OverlayHostBox` / `LayeredContainer` hosting an interactive stage surface plus
     * overlay layers).
     */
    fun isLayeredStageContainer(container: DesktopBaseContainerNativeImpl<*>): Boolean {
        val w = container.nativeWidget
        val attrs = w.attributes
        if (attrs["isLayeredHost"] == true) return true
        var hasStage = false
        var hasOtherChild = false
        for (child in container.getLogicalChildren()) {
            val base = child as? DesktopBaseNativeImpl<*> ?: continue
            val cw = base.nativeWidget
            if (cw.interactiveSurfaceSpec != null) {
                hasStage = true
            } else {
                hasOtherChild = true
            }
        }
        return hasStage && hasOtherChild
    }

    /**
     * Paints the base stage host client background (`0xFF000000` or resolved container backdrop),
     * ensuring [StageHostState.backdropPixels] holds an up-to-date `zIndex = 0` stage frame for any
     * child overlays using `backdrop-filter`.
     */
    fun paintStageHostBackground(hwnd: HWND, hdc: HDC, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val state = stageHostsByHwnd[hwnd.toLong()]
        if (state == null) {
            Win32StyledWidgetHelper.fillSolidParentBackdrop(hwnd, hdc, width, height, isDark = true)
            return
        }

        // Sync backdrop pixels & stage presentation rect cleanly without miscounting normal child
        // WM_PAINT repaints (e.g. window resize or fullscreen transitions) as videoStageOnlyPaints.
        refreshBackdropForState(state, width, height, hdc)

        val bgColor = state.container.getEffectiveBackgroundColor()
            ?: com.nexustools.genericui.style.color.RgbaColor(0, 0, 0)
        val brush = CreateSolidBrush(Win32DwmUxThemeBridge.colorRefRgb(bgColor.r, bgColor.g, bgColor.b))
        if (brush != null) {
            try {
                memScoped {
                    val rc = alloc<RECT>()
                    rc.left = 0
                    rc.top = 0
                    rc.right = width
                    rc.bottom = height
                    FillRect(hdc, rc.ptr, brush)
                }
            } finally {
                DeleteObject(brush)
            }
        }
    }

    /**
     * Called when the stage surface child `HWND` advances a video/canvas frame. Synchronizes
     * [StageHostState.backdropPixels] and invalidates ONLY the stage child `HWND` plus translucent /
     * blurred overlay descendants (`videoStageOnlyPaints`), leaving unrelated siblings untouched.
     */
    fun notifyStageSurfaceFrameAdvanced(hostHwnd: HWND) {
        val state = stageHostsByHwnd[hostHwnd.toLong()] ?: return
        if (IsWindow(hostHwnd) == 0) return

        val startMark = TimeSource.Monotonic.markNow()
        val rect = memScoped {
            val r = alloc<RECT>()
            GetClientRect(hostHwnd, r.ptr)
            (r.right - r.left).coerceAtLeast(0) to (r.bottom - r.top).coerceAtLeast(0)
        }
        if (rect.first > 0 && rect.second > 0) {
            refreshBackdropForState(state, rect.first, rect.second, null)
        }

        // Invalidate stage surface HWNDs and translucent / blurred overlay child HWNDs
        invalidateStageAndTranslucentOverlays(state.container)
        val elapsedUs = startMark.elapsedNow().inWholeMicroseconds
        Win32NativeWindowHost.recordFramePaintDurationUs(elapsedUs, stageOnly = true)
    }

    /**
     * Copies live stage backdrop pixels into [dstArgb] (`capW x capH`) for any descendant child `HWND`
     * at [relX, relY] inside that child's coordinate space. Returns `true` when [childHwnd] sits
     * under a registered stage host and backdrop pixels were populated.
     */
    fun tryCopyBackdropForChild(
        childHwnd: HWND,
        relX: Int,
        relY: Int,
        capW: Int,
        capH: Int,
        dstArgb: IntArray
    ): Boolean {
        if (stageHostsByHwnd.isEmpty() || capW <= 0 || capH <= 0 || dstArgb.size < capW * capH) return false
        var ancestor: HWND? = GetParent(childHwnd)
        var state: StageHostState? = null
        while (ancestor != null) {
            val found = stageHostsByHwnd[ancestor.toLong()]
            if (found != null) {
                state = found
                break
            }
            ancestor = GetParent(ancestor)
        }
        val hostHwnd = ancestor ?: return false
        val hostState = state ?: return false

        if (hostState.backdropW <= 0 || hostState.backdropH <= 0 || hostState.backdropPixels.isEmpty()) {
            val hostSize = memScoped {
                val r = alloc<RECT>()
                GetClientRect(hostHwnd, r.ptr)
                (r.right - r.left).coerceAtLeast(0) to (r.bottom - r.top).coerceAtLeast(0)
            }
            if (hostSize.first > 0 && hostSize.second > 0) {
                refreshBackdropForState(hostState, hostSize.first, hostSize.second, null)
            }
        }

        val bw = hostState.backdropW
        val bh = hostState.backdropH
        val buf = hostState.backdropPixels
        if (bw <= 0 || bh <= 0 || buf.size < bw * bh) return false

        val originInHost = memScoped {
            val pt = alloc<POINT>()
            pt.x = relX
            pt.y = relY
            MapWindowPoints(childHwnd, hostHwnd, pt.ptr, 1u)
            pt.x to pt.y
        }
        val ox = originInHost.first
        val oy = originInHost.second
        val maxX = bw - 1
        val maxY = bh - 1
        for (py in 0 until capH) {
            val sy = (oy + py).coerceIn(0, maxY)
            val srcRow = sy * bw
            val dstRow = py * capW
            for (px in 0 until capW) {
                val sx = (ox + px).coerceIn(0, maxX)
                dstArgb[dstRow + px] = buf[srcRow + sx]
            }
        }
        return true
    }

    /**
     * Refreshes all stage host backdrops synchronously prior to `PrintWindow` screenshot capture so
     * blurred HUD overlays reflect the active video/stage pixels deterministically.
     */
    fun refreshAllStageBackdropsSynchronously() {
        for (state in stageHostsByHwnd.values) {
            val hwnd = state.hostHandle.hwnd ?: continue
            if (IsWindow(hwnd) == 0 || IsWindowVisible(hwnd) == 0) continue
            val size = memScoped {
                val r = alloc<RECT>()
                GetClientRect(hwnd, r.ptr)
                (r.right - r.left).coerceAtLeast(0) to (r.bottom - r.top).coerceAtLeast(0)
            }
            if (size.first > 0 && size.second > 0) {
                refreshBackdropForState(state, size.first, size.second, null)
                invalidateStageAndTranslucentOverlays(state.container)
            }
        }
    }

    /**
     * Applies HUD auto-hide visibility/opacity to overlay layer children (`zIndex > 0` / overlay roles)
     * inside any mounted stage host without affecting the `zIndex = 0` video stage child `HWND`.
     */
    fun setHudAutoHiddenOnAllStages(hidden: Boolean) {
        for (state in stageHostsByHwnd.values) {
            if (state.hudAutoHidden == hidden) continue
            state.hudAutoHidden = hidden
            applyHudHiddenStateRecursive(state.container, hidden, isRoot = true)
        }
    }

    private fun applyHudHiddenStateRecursive(
        node: DesktopBaseNativeImpl<*>,
        hidden: Boolean,
        isRoot: Boolean
    ) {
        if (!isRoot) {
            val w = node.nativeWidget
            val handle = w as? Win32HwndHandle
            val isStage = w.interactiveSurfaceSpec != null && (handle?.desiredZIndex ?: 0) <= 0
            if (isStage) return
            val role = w.attributes[com.nexustools.genericui.desktop.core.DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE] as? String
            if (role == com.nexustools.genericui.desktop.core.DesktopStageOverlaySpec.ROLE_CAPTION_BANNER) {
                return
            }
            val isOverlayRoot = w.stageOverlaySpec != null || w.attributes["data-overlay-root"] == "true"
            val isOverlayChild = !isOverlayRoot && (role != null || (handle != null && handle.desiredZIndex > 0))
            if (isOverlayChild && handle != null) {
                Win32ControlMessageRouter.applyOpacity(handle, if (hidden) 0f else w.opacity)
                return
            }
        }
        if (node is DesktopBaseContainerNativeImpl<*>) {
            for (child in node.getLogicalChildren()) {
                val base = child as? DesktopBaseNativeImpl<*> ?: continue
                applyHudHiddenStateRecursive(base, hidden, isRoot = false)
            }
        }
    }

    private fun refreshBackdropForState(
        state: StageHostState,
        width: Int,
        height: Int,
        refDc: HDC?
    ) {
        val needed = width * height
        if (state.backdropPixels.size < needed) {
            state.backdropPixels = IntArray(needed)
        }
        state.backdropW = width
        state.backdropH = height

        val stagePair = findPrimaryStageSurface(state.container)
        if (stagePair != null) {
            val (surfaceHandle, spec) = stagePair
            Win32InteractiveSurfaceHwnd.renderSpecIntoArgbBuffer(
                handle = surfaceHandle,
                spec = spec,
                dstArgb = state.backdropPixels,
                dstW = width,
                dstH = height,
                refDc = refDc
            )
            val sc = spec.swapCount()
            state.lastSyncedSwapCount = sc
            if (spec.isActiveStream() || spec.shouldPaintBlackBackdrop()) {
                val hostHwnd = state.hostHandle.hwnd
                val ancestorRoot = hostHwnd?.let { GetAncestor(it, GA_ROOT.toUInt()) }
                    ?.takeIf { IsWindow(it) != 0 && it != Win32ControlMessageRouter.getOrCreateParkingHwnd() }
                val topWindow = Win32NativeWindowHost.activeHwnd
                val rootHost = ancestorRoot ?: (if (topWindow != null && IsWindow(topWindow) != 0) topWindow else hostHwnd)
                val synced = if (rootHost != null) {
                    Win32InteractiveSurfaceHwnd.syncSurfaceStageRectForHost(surfaceHandle, rootHost)
                } else {
                    false
                }
                if (synced) {
                    Win32NativeWindowHost.lastRenderedVideoSwapCount = sc
                } else {
                    val pres = DesktopInteractiveSurfaceSpec.computePresentationRect(
                        width.toDouble(),
                        height.toDouble(),
                        spec.surfaceWidth().coerceAtLeast(1),
                        spec.surfaceHeight().coerceAtLeast(1),
                        spec
                    )
                    if (pres.clippedDstW > 0 && pres.clippedDstH > 0) {
                        val mappedOrigin = if (hostHwnd != null && rootHost != null && hostHwnd != rootHost) {
                            memScoped {
                                val pt = alloc<POINT>()
                                pt.x = pres.clippedDstX
                                pt.y = pres.clippedDstY
                                MapWindowPoints(hostHwnd, rootHost, pt.ptr, 1u)
                                pt.x to pt.y
                            }
                        } else {
                            pres.clippedDstX to pres.clippedDstY
                        }
                        Win32NativeWindowHost.recordVideoStageSwap(
                            sc,
                            mappedOrigin.first,
                            mappedOrigin.second,
                            mappedOrigin.first + pres.clippedDstW,
                            mappedOrigin.second + pres.clippedDstH
                        )
                        Win32NativeWindowHost.updateVideoStageRect(
                            mappedOrigin.first,
                            mappedOrigin.second,
                            pres.clippedDstW,
                            pres.clippedDstH
                        )
                    }
                }
            }
        } else {
            val bg = state.container.getEffectiveBackgroundColor()
                ?: com.nexustools.genericui.style.color.RgbaColor(0, 0, 0)
            val fill = (0xFF shl 24) or (bg.r shl 16) or (bg.g shl 8) or bg.b
            state.backdropPixels.fill(fill, 0, needed)
        }
    }

    private fun findPrimaryStageSurface(
        container: DesktopBaseContainerNativeImpl<*>
    ): Pair<Win32HwndHandle, DesktopInteractiveSurfaceSpec>? {
        for (child in container.getLogicalChildren()) {
            val base = child as? DesktopBaseNativeImpl<*> ?: continue
            val h = base.nativeWidget as? Win32HwndHandle ?: continue
            val spec = Win32InteractiveSurfaceHwnd.resolveSpec(h)
            if (spec != null) {
                return h to spec
            }
            if (base is DesktopBaseContainerNativeImpl<*>) {
                val nested = findPrimaryStageSurface(base)
                if (nested != null) return nested
            }
        }
        return null
    }

    private fun invalidateStageAndTranslucentOverlays(node: DesktopBaseNativeImpl<*>) {
        val handle = node.nativeWidget as? Win32HwndHandle
        val hwnd = handle?.hwnd
        if (handle != null && hwnd != null && IsWindow(hwnd) != 0) {
            val spec = Win32InteractiveSurfaceHwnd.resolveSpec(handle)
            val visual = handle.attributes["styledVisuals"] as? StyledVisualSpec
            val isTranslucent = visual?.hasBackdropFilter == true ||
                (visual?.backgroundColor?.a ?: 255) < 250 ||
                handle.attributes["backdropFilter"] != null ||
                (handle.actualWin32ClassName == "Static" && handle.isTransparentCtlColor)
            if (spec != null || isTranslucent) {
                InvalidateRect(hwnd, null, 0)
            }
        }
        if (node is DesktopBaseContainerNativeImpl<*>) {
            for (child in node.getLogicalChildren()) {
                val base = child as? DesktopBaseNativeImpl<*> ?: continue
                invalidateStageAndTranslucentOverlays(base)
            }
        }
    }
}
