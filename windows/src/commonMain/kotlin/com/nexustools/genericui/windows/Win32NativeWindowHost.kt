@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.dialog.DesktopDialogWindowNativeImpl
import com.nexustools.genericui.desktop.dock.DesktopDockPanelNativeImpl
import com.nexustools.genericui.desktop.dock.DesktopDockWorkspaceNativeImpl
import com.nexustools.genericui.desktop.dock.DesktopToolPaletteNativeImpl
import com.nexustools.genericui.desktop.lazy.DesktopLazyGridNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopMenuItemNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopMenuNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopButtonNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopCheckboxNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopNavbarNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopProgressBarNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopRangeSliderNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopScrollViewNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopSelectNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopStyledButtonNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopStyledContainerNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTabContainerNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTabNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTextInputNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTextNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.css.CssBorderStyle
import com.nexustools.genericui.enums.css.CssCursor
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.event.KeyEvent
import com.nexustools.genericui.event.PointerDragManager
import com.nexustools.genericui.event.PointerEvent
import com.nexustools.genericui.event.WheelEvent
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.res.Resources
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssCustomCursor
import com.nexustools.genericui.style.gradient.Gradient.Gradient
import com.nexustools.genericui.style.theme.SystemThemeDetector
import com.nexustools.genericui.window.TitleBar
import kotlinx.cinterop.*
import platform.windows.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Native Win32 (`user32.dll` / `gdi32.dll` / `comdlg32.dll` / `dwmapi.dll`) window host and
 * monitor-refresh-rate VBlank compositor for `:desktop:windows` on `mingwX64`.
 */
object Win32NativeWindowHost {

    var activeWindowImpl: WindowsWindowNativeImpl? = null
        private set

    var activeHwnd: HWND? = null
        private set

    val panelHwnds: MutableMap<DesktopDockPanelNativeImpl<*>, HWND> = mutableMapOf()
    private val hwndToPanel: MutableMap<Long, DesktopDockPanelNativeImpl<*>> = mutableMapOf()

    val dialogHwnds: MutableMap<DesktopDialogWindowNativeImpl<*>, HWND> = mutableMapOf()
    private val hwndToDialog: MutableMap<Long, DesktopDialogWindowNativeImpl<*>> = mutableMapOf()

    fun isDialogWindow(hwnd: HWND?): Boolean =
        hwnd != null && hwndToDialog.containsKey(hwnd.toLong())

    fun getDialogForHwnd(hwnd: HWND?): DesktopDialogWindowNativeImpl<*>? =
        if (hwnd != null) hwndToDialog[hwnd.toLong()] else null

    fun isPanelToolWindow(hwnd: HWND?): Boolean =
        hwnd != null && (
            hwndToPanel.containsKey(hwnd.toLong()) ||
                (hwnd == activeHwnd && activeWindowImpl?.isToolModalWindow == true)
            )

    fun hasCustomMainWindowTitleBar(): Boolean {
        val winImpl = activeWindowImpl ?: return false
        if (activeFullscreenElementWidget != null || winImpl.isFullscreen()) return false
        if (winImpl.isToolModalWindow) return true
        val tb = winImpl.getTitleBarInstructions() ?: return false
        return tb.customTitleBar ||
            tb.leadingComponents.isNotEmpty() ||
            tb.centerComponents.isNotEmpty() ||
            tb.trailingComponents.isNotEmpty()
    }

    fun getMainWindowTitleBarHeight(): Int {
        val winImpl = activeWindowImpl ?: return 0
        if (!hasCustomMainWindowTitleBar()) return 0
        val tb = winImpl.getTitleBarInstructions()
        val kind = winImpl.titleBarKind
        return Win32NativeTitleBarRenderer.getDefaultTitleBarHeightPx(
            isToolWindow = kind.usesSmallCaptionMetrics,
            titleBar = tb,
            kind = kind
        )
    }

    fun getPanelWindowTitleBarHeight(panel: DesktopDockPanelNativeImpl<*>? = null): Int =
        Win32NativeTitleBarRenderer.getDefaultTitleBarHeightPx(
            isToolWindow = true,
            titleBar = panel?.getTitleBarInstructions(),
            kind = Win32TitleBarKind.TOOL_WINDOW
        )

    fun hitTestMainWindow(hwnd: HWND, screenX: Int, screenY: Int): LRESULT =
        Win32NativeTitleBarRenderer.hitTestWindow(
            hwnd = hwnd,
            screenX = screenX,
            screenY = screenY,
            isToolWindow = activeWindowImpl?.isToolModalWindow == true,
            hasInteractiveClientRegionAt = { cx, cy ->
                val regions = hitRegionsByHwnd[hwnd.toLong()].orEmpty()
                regions.any { it.widget != null && it.contains(cx, cy) }
            }
        )

    fun hitTestPanelWindow(hwnd: HWND, screenX: Int, screenY: Int): LRESULT =
        Win32NativeTitleBarRenderer.hitTestWindow(
            hwnd = hwnd,
            screenX = screenX,
            screenY = screenY,
            isToolWindow = true,
            hasInteractiveClientRegionAt = { cx, cy ->
                val regions = hitRegionsByHwnd[hwnd.toLong()].orEmpty()
                regions.any { it.widget != null && it.contains(cx, cy) }
            }
        )

    private var lastMultiWindowVisibilityState: Boolean? = null

    var monitorRefreshRateHz: Int = 60
        private set

    var appIconBig: HICON? = null
        private set

    var appIconSmall: HICON? = null
        private set

    private var windowClassRegistered = false
    private val menuCommandHandlers = mutableMapOf<Int, () -> Unit>()
    private val shortcutCommandHandlers = mutableMapOf<String, () -> Unit>()
    private var nextMenuCommandId = 1001

    class HitRegion(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val tooltip: String? = null,
        val widget: DesktopNativeWidget? = null,
        val isTextInput: Boolean = false,
        val onClick: ((mouseX: Int, mouseY: Int) -> Unit)? = null,
        val onDrag: ((mouseX: Int, mouseY: Int) -> Unit)? = null,
        val onRelease: ((mouseX: Int, mouseY: Int) -> Unit)? = null
    ) {
        fun contains(x: Int, y: Int): Boolean = x in left..right && y in top..bottom
    }

    internal class SystemThemePalette(
        val isDark: Boolean,
        val bgBase: UInt,
        val bgPanel: UInt,
        val bgCard: UInt,
        val bgElevated: UInt,
        val borderSubtle: UInt,
        val borderDash: UInt,
        val textPrimary: UInt,
        val textSecondary: UInt,
        val textMuted: UInt,
        val accentBlue: UInt,
        val accentBlueHover: UInt,
        val accentBlueSoftBg: UInt,
        val accentBlueBorder: UInt,
        val accentBlueText: UInt,
        val accentGreen: UInt,
        val accentGreenSoftBg: UInt,
        val accentGreenBorder: UInt,
        val accentAmber: UInt,
        val accentAmberSoftBg: UInt,
        val accentRed: UInt,
        val accentRedSoftBg: UInt,
        val accentRedBorder: UInt
    ) {
        val borderStrong: UInt get() = accentBlueBorder
        val dangerBg: UInt get() = accentRedSoftBg
        val dangerBorder: UInt get() = accentRedBorder
        val dangerText: UInt get() = accentRed
        val successText: UInt get() = accentGreen
        val warningText: UInt get() = accentAmber
    }

    private val darkSystemPalette = SystemThemePalette(
        isDark = true,
        bgBase = rgb(32, 32, 32),
        bgPanel = rgb(43, 43, 43),
        bgCard = rgb(52, 52, 54),
        bgElevated = rgb(62, 62, 66),
        borderSubtle = rgb(74, 74, 78),
        borderDash = rgb(86, 86, 90),
        textPrimary = rgb(245, 245, 245),
        textSecondary = rgb(196, 196, 196),
        textMuted = rgb(154, 154, 154),
        accentBlue = rgb(0, 120, 212),
        accentBlueHover = rgb(24, 138, 232),
        accentBlueSoftBg = rgb(50, 50, 50),
        accentBlueBorder = rgb(0, 120, 212),
        accentBlueText = rgb(96, 205, 255),
        accentGreen = rgb(108, 203, 95),
        accentGreenSoftBg = rgb(50, 50, 50),
        accentGreenBorder = rgb(80, 140, 80),
        accentAmber = rgb(252, 225, 0),
        accentAmberSoftBg = rgb(50, 50, 50),
        accentRed = rgb(255, 153, 164),
        accentRedSoftBg = rgb(50, 50, 50),
        accentRedBorder = rgb(160, 70, 70)
    )

    private val lightSystemPalette = SystemThemePalette(
        isDark = false,
        bgBase = rgb(240, 240, 240),
        bgPanel = rgb(255, 255, 255),
        bgCard = rgb(250, 250, 252),
        bgElevated = rgb(229, 229, 234),
        borderSubtle = rgb(198, 198, 204),
        borderDash = rgb(180, 180, 180),
        textPrimary = rgb(0, 0, 0),
        textSecondary = rgb(68, 68, 68),
        textMuted = rgb(109, 109, 109),
        accentBlue = rgb(0, 120, 212),
        accentBlueHover = rgb(0, 98, 184),
        accentBlueSoftBg = rgb(240, 240, 240),
        accentBlueBorder = rgb(0, 120, 212),
        accentBlueText = rgb(0, 102, 204),
        accentGreen = rgb(16, 124, 16),
        accentGreenSoftBg = rgb(240, 240, 240),
        accentGreenBorder = rgb(16, 124, 16),
        accentAmber = rgb(157, 93, 0),
        accentAmberSoftBg = rgb(240, 240, 240),
        accentRed = rgb(196, 43, 28),
        accentRedSoftBg = rgb(240, 240, 240),
        accentRedBorder = rgb(196, 43, 28)
    )

    private val hitRegionsByHwnd = mutableMapOf<Long, MutableList<HitRegion>>()
    private var currentPaintHwndKey: Long = 0L
    private val hitRegions: MutableList<HitRegion>
        get() = hitRegionsByHwnd.getOrPut(currentPaintHwndKey) { mutableListOf() }

    internal fun addHitRegion(region: HitRegion) {
        hitRegions.add(region)
    }

    internal fun getHitRegionsCount(): Int = hitRegions.size

    internal fun getHitRegionAt(index: Int): HitRegion = hitRegions[index]

    internal fun removeHitRegionsInRect(left: Int, top: Int, right: Int, bottom: Int) {
        hitRegions.removeAll { r ->
            r.left >= left - 4 && r.right <= right + 4 && r.top >= top - 4 && r.bottom <= bottom + 4
        }
    }

    internal fun truncateHitRegionsTo(targetSize: Int) {
        val list = hitRegions
        while (list.size > targetSize && list.isNotEmpty()) {
            list.removeAt(list.lastIndex)
        }
    }

    private var isPaintingWindow: Boolean = false
    private var isDispatchingVBlank: Boolean = false
    private var vblankTreeInvalidated: Boolean = false
    internal var overlayMutatedDuringVBlank: Boolean = false
    private var isVBlankVideoStageOnlyRepaint: Boolean = false

    internal fun ensureOverlayInvalidationListenerInstalled() {
        DesktopNativeWidget.overlayInvalidationListener = { _ ->
            if (isDispatchingVBlank) {
                vblankTreeInvalidated = true
                overlayMutatedDuringVBlank = true
            } else if (!isPaintingWindow && !isSyncingFromNativeEdit) {
                overlayMutatedDuringVBlank = true
                markAllVideoStagesDirty()
            }
        }
    }
    private const val MAX_GDI_DIRTY_RECTS = 128
    internal var gdiDirtySinceLastFlush: Boolean = false
    private val gdiDirtyRects = IntArray(MAX_GDI_DIRTY_RECTS * 4)
    private var gdiDirtyRectCount: Int = 0
    internal var frameBufferDirtyLeft: Int = Int.MAX_VALUE
    internal var frameBufferDirtyTop: Int = Int.MAX_VALUE
    internal var frameBufferDirtyRight: Int = Int.MIN_VALUE
    internal var frameBufferDirtyBottom: Int = Int.MIN_VALUE
    internal var activeMainBackBufferPreserved: Boolean = false
    private var lastFullPaintHwndKey: Long = 0L
    private var lastFullPaintW: Int = 0
    private var lastFullPaintH: Int = 0
    private var lastFullPaintDark: Boolean = false
    private var lastFullPaintFullscreen: Boolean = false
    private var lastFullPaintCustomTitleBar: Boolean = false
    private var lastFullPaintRootWidget: DesktopNativeWidget? = null
    private var lastFrameHadTooltipOverlay: Boolean = false

    internal fun resetFrameBufferDirtyRect() {
        frameBufferDirtyLeft = Int.MAX_VALUE
        frameBufferDirtyTop = Int.MAX_VALUE
        frameBufferDirtyRight = Int.MIN_VALUE
        frameBufferDirtyBottom = Int.MIN_VALUE
    }

    internal fun markFrameBufferDirty(left: Int, top: Int, right: Int, bottom: Int) {
        if (right <= left || bottom <= top) return
        if (left < frameBufferDirtyLeft) frameBufferDirtyLeft = left
        if (top < frameBufferDirtyTop) frameBufferDirtyTop = top
        if (right > frameBufferDirtyRight) frameBufferDirtyRight = right
        if (bottom > frameBufferDirtyBottom) frameBufferDirtyBottom = bottom
    }

    internal fun markGdiRectDirty(left: Int, top: Int, right: Int, bottom: Int) {
        if (right <= left || bottom <= top) return
        markFrameBufferDirty(left, top, right, bottom)
        gdiDirtySinceLastFlush = true
        if (gdiDirtyRectCount < MAX_GDI_DIRTY_RECTS) {
            val base = gdiDirtyRectCount * 4
            gdiDirtyRects[base] = left
            gdiDirtyRects[base + 1] = top
            gdiDirtyRects[base + 2] = right
            gdiDirtyRects[base + 3] = bottom
            gdiDirtyRectCount++
        } else {
            val base = (MAX_GDI_DIRTY_RECTS - 1) * 4
            if (left < gdiDirtyRects[base]) gdiDirtyRects[base] = left
            if (top < gdiDirtyRects[base + 1]) gdiDirtyRects[base + 1] = top
            if (right > gdiDirtyRects[base + 2]) gdiDirtyRects[base + 2] = right
            if (bottom > gdiDirtyRects[base + 3]) gdiDirtyRects[base + 3] = bottom
        }
    }

    internal fun resetGdiDirtyRect() {
        gdiDirtySinceLastFlush = false
        gdiDirtyRectCount = 0
    }

    internal fun flushGdiIfOverlapping(left: Int, top: Int, right: Int, bottom: Int) {
        if (!gdiDirtySinceLastFlush || gdiDirtyRectCount <= 0) return
        var i = 0
        val count = gdiDirtyRectCount
        while (i < count) {
            val base = i * 4
            if (right > gdiDirtyRects[base] &&
                left < gdiDirtyRects[base + 2] &&
                bottom > gdiDirtyRects[base + 1] &&
                top < gdiDirtyRects[base + 3]
            ) {
                GdiFlush()
                resetGdiDirtyRect()
                return
            }
            i++
        }
    }

    private val paintDurationsUs = LongArray(512)
    private var paintDurationsCount: Int = 0
    private var paintDurationsWriteIdx: Int = 0
    private var totalPaintsCount: Int = 0
    private var videoStageOnlyPaintsCount: Int = 0
    private var fullWindowPaintsCount: Int = 0
    private var lastPaintDurationUs: Long = 0L
    private var totalPaintDurationSumUs: Long = 0L
    internal var lastSlowPaintBreakdown: String = ""
    internal var currentPaintStageBreakdown: String = ""
    private var cachedQpcTicksPerUs: Double = 0.0

    internal fun nowMicros(): Long = memScoped {
        var ticksPerUs = cachedQpcTicksPerUs
        if (ticksPerUs <= 0.0) {
            val freq = alloc<LARGE_INTEGER>()
            QueryPerformanceFrequency(freq.ptr)
            ticksPerUs = (freq.QuadPart.toDouble() / 1_000_000.0).coerceAtLeast(1.0)
            cachedQpcTicksPerUs = ticksPerUs
        }
        val counter = alloc<LARGE_INTEGER>()
        QueryPerformanceCounter(counter.ptr)
        (counter.QuadPart.toDouble() / ticksPerUs).toLong()
    }

    internal fun recordPaintMetrics(durationUs: Long, isVideoStageOnly: Boolean) {
        val clamped = durationUs.coerceAtLeast(0L)
        if (clamped >= 10_000L && currentPaintStageBreakdown.isNotEmpty()) {
            lastSlowPaintBreakdown = "dur=${clamped}us[$currentPaintStageBreakdown]"
        }
        lastPaintDurationUs = clamped
        totalPaintsCount++
        if (isVideoStageOnly) {
            videoStageOnlyPaintsCount++
        } else {
            fullWindowPaintsCount++
        }
        totalPaintDurationSumUs += clamped
        paintDurationsUs[paintDurationsWriteIdx] = clamped
        paintDurationsWriteIdx = (paintDurationsWriteIdx + 1) % paintDurationsUs.size
        if (paintDurationsCount < paintDurationsUs.size) {
            paintDurationsCount++
        }
    }

    fun resetFrameMetrics() {
        paintDurationsCount = 0
        paintDurationsWriteIdx = 0
        totalPaintsCount = 0
        videoStageOnlyPaintsCount = 0
        fullWindowPaintsCount = 0
        lastPaintDurationUs = 0L
        totalPaintDurationSumUs = 0L
        lastSlowPaintBreakdown = ""
        currentPaintStageBreakdown = ""
        lastOverlayFadeTickMs = 0.0
        overlayHudCurrentAlpha = overlayHudTargetAlpha
    }

    fun getFrameMetricsSnapshot(): Win32FrameMetricsSnapshot {
        val count = paintDurationsCount
        if (count <= 0) {
            return Win32FrameMetricsSnapshot(
                totalPaints = totalPaintsCount,
                videoStageOnlyPaints = videoStageOnlyPaintsCount,
                fullWindowPaints = fullWindowPaintsCount,
                lastPaintUs = lastPaintDurationUs,
                p50PaintUs = 0L,
                p95PaintUs = 0L,
                maxPaintUs = 0L,
                avgPaintUs = 0L,
                lastSlowPaintBreakdown = lastSlowPaintBreakdown
            )
        }
        val sample = LongArray(count)
        for (i in 0 until count) {
            sample[i] = paintDurationsUs[i]
        }
        sample.sort()
        val p50 = sample[((count - 1) * 50) / 100]
        val p95 = sample[((count - 1) * 95) / 100]
        val maxUs = sample[count - 1]
        val avgUs = if (totalPaintsCount > 0) totalPaintDurationSumUs / totalPaintsCount else 0L
        return Win32FrameMetricsSnapshot(
            totalPaints = totalPaintsCount,
            videoStageOnlyPaints = videoStageOnlyPaintsCount,
            fullWindowPaints = fullWindowPaintsCount,
            lastPaintUs = lastPaintDurationUs,
            p50PaintUs = p50,
            p95PaintUs = p95,
            maxPaintUs = maxUs,
            avgPaintUs = avgUs,
            lastSlowPaintBreakdown = lastSlowPaintBreakdown
        )
    }

    fun getLastRenderedHitRegionsSnapshot(): List<HitRegion> {
        val key = activeHwnd?.toLong() ?: currentPaintHwndKey
        return hitRegionsByHwnd[key]?.toList().orEmpty()
    }

    fun simulateMouseMove(x: Int, y: Int) {
        val hwnd = activeHwnd ?: return
        handleMouseMove(hwnd, x, y)
        UpdateWindow(hwnd)
    }

    fun simulateMouseWheel(x: Int, y: Int, deltaY: Int) {
        val hwnd = activeHwnd ?: return
        hoverHwndKey = hwnd.toLong()
        hoverMouseX = x
        hoverMouseY = y
        handleMouseWheel(hwnd, deltaY)
        UpdateWindow(hwnd)
    }

    fun simulateMouseClick(x: Int, y: Int) {
        val hwnd = activeHwnd ?: return
        handleMouseMove(hwnd, x, y)
        handleMouseDown(hwnd, x, y)
        handleMouseUp(hwnd, x, y)
        UpdateWindow(hwnd)
    }

    private var activeDragRegion: HitRegion? = null
    private var mouseDownX: Int = 0
    private var mouseDownY: Int = 0
    private var dragMouseMovedSinceDown: Boolean = false

    internal fun isOverlayDragActive(): Boolean =
        activeDragRegion != null || PointerDragManager.isDragging
    private var hoveredHitRegion: HitRegion? = null
    internal var hoveredWidget: DesktopNativeWidget? = null
        private set
    internal var pressedWidget: DesktopNativeWidget? = null
        private set
    internal var focusedWidget: DesktopNativeWidget? = null
        private set
    private var focusedTextInput: DesktopTextInputNativeImpl<*>? = null
    private var activeLazyGridImpl: DesktopLazyGridNativeImpl<*>? = null
    private var hoveredTooltip: String? = null
    internal var hoverMouseX: Int = 0
        private set
    internal var hoverMouseY: Int = 0
        private set
    private var hoverHwndKey: Long = 0L
    private var canvasViewportRect: IntArray = intArrayOf(-1, -1, -1, -1)
    private var activeCanvasImpl: DesktopBaseNativeImpl<*>? = null
    private var activeCanvasSurfaceSize: Pair<Int, Int> = 320 to 240
    private var isDraggingCanvas: Boolean = false
    private val WHITESPACE_REGEX = Regex("\\s+")

    // Video Stage Pointer Activity, Auto-Hide & Smooth 220ms Opacity Fade State
    internal var lastVideoStageMouseMoveTickMs: Long = 0L
    internal var isPointerInVideoStage: Boolean = false
    var lastRenderedVideoStageRect: IntArray = intArrayOf(-1, -1, -1, -1)
    var lastRenderedVideoSwapCount: Long = 0L
    internal var lastVideoStageFastBlitRect: IntArray = intArrayOf(-1, -1, -1, -1)
    internal var isVideoStageHudAutoHidden: Boolean = false
    internal var overlayHudCurrentAlpha: Float = 1f
    internal var overlayHudTargetAlpha: Float = 1f
    internal var lastOverlayFadeTickMs: Double = 0.0
    private var mouseLeaveTrackInstalledForHwnd: Long = 0L

    // Element-Level & Window Fullscreen State
    internal var activeFullscreenElementWidget: DesktopNativeWidget? = null
    private var isWindowFullscreenActive: Boolean = false
    private var savedWindowStyle: Int = 0
    private var savedWindowExStyle: Int = 0
    private var savedWindowRect: IntArray = intArrayOf(80, 50, 1280, 800)

    internal fun isFullscreenActiveForMainWindow(): Boolean =
        activeFullscreenElementWidget != null || isWindowFullscreenActive || activeWindowImpl?.isFullscreen() == true

    internal class NativeEditControlEntry(
        val parentHwnd: HWND,
        var childHwnd: HWND? = null,
        val commandId: Int,
        var inputImpl: DesktopTextInputNativeImpl<*>,
        var widget: DesktopNativeWidget,
        var desiredLeft: Int = 0,
        var desiredTop: Int = 0,
        var desiredWidth: Int = 100,
        var desiredHeight: Int = 20,
        var desiredFont: HFONT? = null,
        var desiredDarkTheme: Boolean = true,
        var left: Int = -1,
        var top: Int = -1,
        var width: Int = -1,
        var height: Int = -1,
        var visible: Boolean = false,
        var lastPlaceholder: String = "",
        var lastDarkTheme: Boolean? = null,
        var visitedThisFrame: Boolean = false
    )

    private val nativeEditEntriesByWidget = mutableMapOf<Pair<Long, DesktopNativeWidget>, NativeEditControlEntry>()
    private val nativeEditEntriesByChildHwnd = mutableMapOf<Long, NativeEditControlEntry>()
    private val origEditWndProcByChildHwnd = mutableMapOf<Long, Long>()
    private var nextEditControlCommandId = 30001
    private var isSyncingFromNativeEdit = false
    private var editBgBrushDark: HBRUSH? = null
    private var editBgBrushLight: HBRUSH? = null

    init {
        DesktopNativeWidget.onNativeTreeInvalidated = {
            if (isDispatchingVBlank) {
                vblankTreeInvalidated = true
            } else if (!isPaintingWindow && !isSyncingFromNativeEdit) {
                syncActiveDialogWindows()
                invalidateAllWindows()
            }
        }
        DesktopNativeWidget.overlayInvalidationListener = { _ ->
            if (isDispatchingVBlank) {
                vblankTreeInvalidated = true
            } else if (!isPaintingWindow && !isSyncingFromNativeEdit) {
                markAllVideoStagesDirty()
            }
        }
        DesktopDialogWindowNativeImpl.onShowDialogHook = { dialog, _ ->
            syncActiveDialogWindows()
            dialogHwnds[dialog]?.let { dHwnd ->
                if (IsWindow(dHwnd) != 0) {
                    BringWindowToTop(dHwnd)
                    SetForegroundWindow(dHwnd)
                    InvalidateRect(dHwnd, null, 1)
                    UpdateWindow(dHwnd)
                }
            }
            invalidateAllWindows()
        }
        DesktopDialogWindowNativeImpl.onCloseDialogHook = { dialog ->
            closeNativeDialogWindow(dialog)
            invalidateAllWindows()
        }
        DesktopDialogWindowNativeImpl.onTitleChangedHook = { dialog, title ->
            dialogHwnds[dialog]?.let { dHwnd ->
                if (IsWindow(dHwnd) != 0) {
                    SetWindowTextW(dHwnd, title.ifBlank { "Dialog" })
                    InvalidateRect(dHwnd, null, 0)
                }
            }
        }
    }

    private var cachedSystemDarkTheme: Boolean? = null
    var forcedThemeMode: ThemeMode = ThemeMode.AUTO
    private var cachedFontSmall: HFONT? = null
    private var cachedFontSmallBold: HFONT? = null
    private var cachedFontNormal: HFONT? = null
    private var cachedFontBold: HFONT? = null
    private var cachedFontTitle: HFONT? = null
    private var cachedFontMetricBig: HFONT? = null
    private var cachedMeasureDc: HDC? = null

    private var cachedMainMemDc: HDC? = null
    private var cachedMainMemBmp: HBITMAP? = null
    private var cachedMainOldBmp: HGDIOBJ? = null
    internal var cachedMainBackBufferPixels: CPointer<ByteVar>? = null
        private set
    internal var cachedMainBackBufferWidth: Int = 0
        private set
    internal var cachedMainBackBufferHeight: Int = 0
        private set
    internal var activeBackBufferDc: HDC? = null
    internal var activeBackBufferPixels: CPointer<ByteVar>? = null
    internal var activeBackBufferWidth: Int = 0
    internal var activeBackBufferHeight: Int = 0
    private var cachedMainBufferW: Int = 0
    private var cachedMainBufferH: Int = 0
    private var cachedMainBufferHwndKey: Long = 0L
    private var lastPaintedNonOverlayVersion: Long = -1L
    private var lastCursorHwndKey: Long = 0L
    private var lastCursorIdKey: Long = 0L

    private var cachedScaledVideoPixels: ByteArray? = null
    private var cachedScaledVideoW: Int = 0
    private var cachedScaledVideoH: Int = 0
    private var cachedScaledVideoSwapCount: Long = -2L
    private var cachedScaledVideoWidget: DesktopNativeWidget? = null

    private var cachedCardGridDc: HDC? = null
    private var cachedCardGridBmp: HBITMAP? = null
    private var cachedCardGridOldBmp: HGDIOBJ? = null
    private var cachedCardGridPixels: CPointer<ByteVar>? = null
    private var cachedCardGridAllocW: Int = 0
    private var cachedCardGridAllocH: Int = 0
    private var cachedCardGridWidget: DesktopNativeWidget? = null
    private var cachedCardGridMutVer: Long = -1L
    private var cachedCardGridW: Int = 0
    private var cachedCardGridH: Int = 0
    private var cachedCardGridDark: Boolean = false

    private var cachedCardPageFrameHwnd: Long = 0L
    private var cachedCardPageFrameRootVer: Long = -1L
    private var cachedCardPageFrameW: Int = 0
    private var cachedCardPageFrameH: Int = 0
    private var cachedCardPageFrameBodyTop: Int = 0
    private var cachedCardPageFrameDark: Boolean = false
    private var cachedCardPageFramePage: DesktopNativeWidget? = null
    private var cachedCardPageFrameHeaderBottom: Int = 0

    internal fun markVideoStageDirty(hwnd: HWND?) {
        val targetHwnd = hwnd ?: activeHwnd ?: return
        if (IsWindow(targetHwnd) == 0) return
        val vr = lastRenderedVideoStageRect
        if (lastPaintedNonOverlayVersion != DesktopNativeWidget.globalNonOverlayMutationVersion ||
            lastFullPaintFullscreen != isFullscreenActiveForMainWindow() ||
            vr[0] < 0 ||
            vr[2] <= vr[0] ||
            vr[3] <= vr[1]
        ) {
            isVBlankVideoStageOnlyRepaint = false
            InvalidateRect(targetHwnd, null, 0)
            return
        }
        isVBlankVideoStageOnlyRepaint = true
        memScoped {
            val stageRect = alloc<RECT>()
            stageRect.left = vr[0]
            stageRect.top = vr[1]
            stageRect.right = vr[2]
            stageRect.bottom = vr[3]
            InvalidateRect(targetHwnd, stageRect.ptr, 0)
        }
    }

    internal fun markAllVideoStagesDirty() {
        val hwnd = activeHwnd ?: return
        markVideoStageDirty(hwnd)
    }

    internal fun markNonOverlayUiDirty(hwnd: HWND?) {
        ++DesktopNativeWidget.globalNonOverlayMutationVersion
        val targetHwnd = hwnd ?: activeHwnd ?: return
        if (IsWindow(targetHwnd) != 0) {
            InvalidateRect(targetHwnd, null, 0)
        }
    }

    private fun isPointInVideoStage(x: Int, y: Int): Boolean {
        val vr = lastRenderedVideoStageRect
        return vr[2] > vr[0] && vr[3] > vr[1] && x in vr[0]..vr[2] && y in vr[1]..vr[3]
    }

    private fun isHitRegionInVideoStage(region: HitRegion?): Boolean {
        if (region == null) return false
        val vr = lastRenderedVideoStageRect
        if (vr[2] <= vr[0] || vr[3] <= vr[1]) return false
        return region.left >= vr[0] - 4 && region.right <= vr[2] + 4 &&
            region.top >= vr[1] - 4 && region.bottom <= vr[3] + 4
    }

    private fun isOverlayOrStageSubtree(widget: DesktopNativeWidget?): Boolean =
        widget?.isInsideIsolatedStageSubtree() == true

    fun invalidateSystemThemeCache() {
        cachedSystemDarkTheme = null
    }

    fun queryMonitorRefreshRateHz(): Int = memScoped {
        val devMode = alloc<DEVMODEW>()
        devMode.dmSize = sizeOf<DEVMODEW>().toUShort()
        val ok = EnumDisplaySettingsW(null, ENUM_CURRENT_SETTINGS, devMode.ptr)
        val freq = if (ok != 0) devMode.dmDisplayFrequency.toInt() else 60
        if (freq in 30..500) freq else 60
    }

    fun isWindowsSystemDarkTheme(): Boolean {
        when (forcedThemeMode) {
            ThemeMode.DARK -> return true
            ThemeMode.LIGHT -> return false
            ThemeMode.AUTO -> Unit
        }
        SystemThemeDetector.overrideDetectedTheme?.let {
            return it == ThemeMode.DARK
        }
        cachedSystemDarkTheme?.let { return it }
        val resolved = Win32DwmUxThemeBridge.isSystemDarkTheme()
        cachedSystemDarkTheme = resolved
        return resolved
    }

    fun onSystemThemeChanged(hwnd: HWND?) {
        val appsUseLight = WindowsApplicationNativeImpl.queryRegistryAppsUseLightTheme()
        val detectedSystemTheme = if (appsUseLight) ThemeMode.LIGHT else ThemeMode.DARK
        invalidateSystemThemeCache()
        cachedSystemDarkTheme = (detectedSystemTheme == ThemeMode.DARK)
        SystemThemeDetector.notifySystemThemeChanged(detectedSystemTheme)
        val isDark = isWindowsSystemDarkTheme()
        if (hwnd != null) {
            applyWindowsImmersiveTitleBarTheme(hwnd, isDark)
            Win32DwmUxThemeBridge.clearCachedHwndState(hwnd)
            Win32DwmUxThemeBridge.setControlWindowTheme(hwnd, isDark)
            InvalidateRect(hwnd, null, 1)
        }
        val mainHwnd = activeHwnd
        if (mainHwnd != null && mainHwnd != hwnd) {
            applyWindowsImmersiveTitleBarTheme(mainHwnd, isDark)
            Win32DwmUxThemeBridge.clearCachedHwndState(mainHwnd)
            Win32DwmUxThemeBridge.setControlWindowTheme(mainHwnd, isDark)
        }
        invalidateAllWindows()
    }

    fun setWindowFullscreen(
        windowImpl: WindowsWindowNativeImpl,
        fullscreen: Boolean,
        targetElementWidget: DesktopNativeWidget?
    ) {
        activeFullscreenElementWidget = if (fullscreen) targetElementWidget else null
        lastRenderedVideoStageRect = intArrayOf(-1, -1, -1, -1)
        lastVideoStageFastBlitRect = intArrayOf(-1, -1, -1, -1)
        isVBlankVideoStageOnlyRepaint = false
        lastPaintedNonOverlayVersion = -1L
        lastFullPaintHwndKey = 0L
        ++DesktopNativeWidget.globalNonOverlayMutationVersion
        Win32GeneralComponentTreeRenderer.invalidateFullscreenAndLayoutCaches()
        val hwnd = activeHwnd
        if (hwnd != null && activeWindowImpl === windowImpl && IsWindow(hwnd) != 0) {
            if (fullscreen) {
                if (!isWindowFullscreenActive) {
                    isWindowFullscreenActive = true
                    savedWindowStyle = GetWindowLongW(hwnd, GWL_STYLE)
                    savedWindowExStyle = GetWindowLongW(hwnd, GWL_EXSTYLE)
                    memScoped {
                        val r = alloc<RECT>()
                        if (GetWindowRect(hwnd, r.ptr) != 0) {
                            savedWindowRect = intArrayOf(
                                r.left,
                                r.top,
                                (r.right - r.left).coerceAtLeast(320),
                                (r.bottom - r.top).coerceAtLeast(240)
                            )
                        }
                    }
                }
                memScoped {
                    val hMon = MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST.toUInt())
                    val mi = alloc<MONITORINFO>()
                    mi.cbSize = sizeOf<MONITORINFO>().toUInt()
                    val hasMon = hMon != null && GetMonitorInfoW(hMon, mi.ptr) != 0
                    val monLeft = if (hasMon) mi.rcMonitor.left else 0
                    val monTop = if (hasMon) mi.rcMonitor.top else 0
                    val monW = if (hasMon) (mi.rcMonitor.right - mi.rcMonitor.left).coerceAtLeast(640) else GetSystemMetrics(SM_CXSCREEN).coerceAtLeast(640)
                    val monH = if (hasMon) (mi.rcMonitor.bottom - mi.rcMonitor.top).coerceAtLeast(480) else GetSystemMetrics(SM_CYSCREEN).coerceAtLeast(480)
                    val stripMask = (WS_CAPTION or WS_THICKFRAME or WS_MINIMIZEBOX or WS_MAXIMIZEBOX or WS_SYSMENU).inv()
                    val newStyle = (savedWindowStyle and stripMask) or WS_POPUP.toInt() or WS_VISIBLE
                    SetWindowLongW(hwnd, GWL_STYLE, newStyle)
                    Win32DwmUxThemeBridge.extendFrameIntoClientArea(hwnd, 0, 0, 0, 0)
                    SetWindowPos(
                        hwnd,
                        HWND_TOP,
                        monLeft,
                        monTop,
                        monW,
                        monH,
                        (SWP_FRAMECHANGED or SWP_SHOWWINDOW).toUInt()
                    )
                    handleWindowResize(hwnd)
                    InvalidateRect(hwnd, null, 1)
                    UpdateWindow(hwnd)
                }
            } else {
                activeFullscreenElementWidget = null
                if (isWindowFullscreenActive) {
                    isWindowFullscreenActive = false
                    if (savedWindowStyle != 0) {
                        SetWindowLongW(hwnd, GWL_STYLE, savedWindowStyle)
                    }
                    if (savedWindowExStyle != 0) {
                        SetWindowLongW(hwnd, GWL_EXSTYLE, savedWindowExStyle)
                    }
                    Win32DwmUxThemeBridge.clearCachedHwndState(hwnd)
                    applyWindowsImmersiveTitleBarTheme(hwnd, isWindowsSystemDarkTheme())
                    SetWindowPos(
                        hwnd,
                        null,
                        savedWindowRect[0],
                        savedWindowRect[1],
                        savedWindowRect[2],
                        savedWindowRect[3],
                        (SWP_NOZORDER or SWP_FRAMECHANGED or SWP_SHOWWINDOW).toUInt()
                    )
                    onMainWindowTitleBarChanged(windowImpl)
                    handleWindowResize(hwnd)
                    InvalidateRect(hwnd, null, 1)
                    UpdateWindow(hwnd)
                } else {
                    handleWindowResize(hwnd)
                    InvalidateRect(hwnd, null, 1)
                    UpdateWindow(hwnd)
                }
            }
        }
    }

    private fun resolveAppIcons(hInstance: HINSTANCE?) = memScoped {
        if (appIconBig != null && appIconSmall != null) return@memScoped

        val bigW = GetSystemMetrics(SM_CXICON).coerceAtLeast(32)
        val bigH = GetSystemMetrics(SM_CYICON).coerceAtLeast(32)
        val smallW = GetSystemMetrics(SM_CXSMICON).coerceAtLeast(16)
        val smallH = GetSystemMetrics(SM_CYSMICON).coerceAtLeast(16)

        // 1. Try embedded PE icon resource ID 1 (compiled via windres.exe)
        val resName = (1L).toCPointer<UShortVar>()
        var big: HICON? = LoadImageW(
            hInstance,
            resName,
            IMAGE_ICON.toUInt(),
            bigW,
            bigH,
            0u
        )?.reinterpret()
        var small: HICON? = LoadImageW(
            hInstance,
            resName,
            IMAGE_ICON.toUInt(),
            smallW,
            smallH,
            0u
        )?.reinterpret()

        // 2. Fallback to icon.ico on disk (next to executable or in working directory)
        if (big == null || small == null) {
            val exePathBuf = allocArray<WCHARVar>(MAX_PATH)
            GetModuleFileNameW(null, exePathBuf, MAX_PATH.toUInt())
            val exePath = exePathBuf.toKString()
            val exeDir = exePath.substringBeforeLast('\\', "")
            val candidatePaths = buildList {
                if (exeDir.isNotEmpty()) {
                    add("$exeDir\\icon.ico")
                    add("$exeDir\\favicon.ico")
                    add("$exeDir\\..\\..\\..\\..\\icon.ico")
                }
                add("icon.ico")
                add("favicon.ico")
            }
            for (path in candidatePaths) {
                if (big == null) {
                    big = LoadImageW(
                        null,
                        path.wcstr.ptr,
                        IMAGE_ICON.toUInt(),
                        bigW,
                        bigH,
                        LR_LOADFROMFILE.toUInt()
                    )?.reinterpret()
                }
                if (small == null) {
                    small = LoadImageW(
                        null,
                        path.wcstr.ptr,
                        IMAGE_ICON.toUInt(),
                        smallW,
                        smallH,
                        LR_LOADFROMFILE.toUInt()
                    )?.reinterpret()
                }
                if (big != null && small != null) break
            }
        }

        if (big == null) {
            big = LoadIconW(null, IDI_APPLICATION)
        }
        if (small == null) {
            small = big
        }
        appIconBig = big
        appIconSmall = small
    }

    internal fun applyWindowsImmersiveTitleBarTheme(hwnd: HWND, dark: Boolean) = memScoped {
        val dwmModule = LoadLibraryW("dwmapi.dll") ?: return@memScoped
        val proc = GetProcAddress(dwmModule, "DwmSetWindowAttribute") ?: return@memScoped
        val fn = proc.reinterpret<CFunction<(HWND?, DWORD, CValuesRef<*>?, DWORD) -> HRESULT>>()
        val flag = alloc<IntVar>()
        flag.value = if (dark) 1 else 0
        // 20 = DWMWA_USE_IMMERSIVE_DARK_MODE (Windows 11 / Win10 20H1+), 19 = older Win10
        fn(hwnd, 20u, flag.ptr, sizeOf<IntVar>().toUInt())
        fn(hwnd, 19u, flag.ptr, sizeOf<IntVar>().toUInt())
    }

    private fun isStudioMultiWindowMode(rootWidget: DesktopNativeWidget?): Boolean {
        if (rootWidget == null) return false
        val impl = rootWidget.attributes["nativeImpl"]
        if (impl is DesktopDockWorkspaceNativeImpl<*>) {
            val mode = impl.getWindowMode().uppercase()
            return mode.contains("MULTI")
        }
        val modeAttr = (rootWidget.attributes["windowMode"] as? String)?.uppercase()
        if (modeAttr != null) {
            return modeAttr.contains("MULTI")
        }
        val children = runCatching { rootWidget.children.toTypedArray() }.getOrElse { emptyArray() }
        for (child in children) {
            if (isStudioMultiWindowMode(child)) return true
        }
        return false
    }

    private fun createAuxiliaryDockWindow(
        hInstance: HINSTANCE?,
        className: String,
        title: String,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        ownerHwnd: HWND?
    ): HWND? {
        val hwnd = CreateWindowExW(
            dwExStyle = WS_EX_TOOLWINDOW.toUInt(),
            lpClassName = className,
            lpWindowName = title,
            dwStyle = WS_POPUP or WS_CAPTION.toUInt() or WS_SYSMENU.toUInt() or WS_THICKFRAME.toUInt() or WS_CLIPCHILDREN.toUInt(),
            X = x,
            Y = y,
            nWidth = width,
            nHeight = height,
            hWndParent = ownerHwnd,
            hMenu = null,
            hInstance = hInstance,
            lpParam = null
        )
        if (hwnd != null) {
            val isDark = isWindowsSystemDarkTheme()
            applyWindowsImmersiveTitleBarTheme(hwnd, isDark)
            Win32DwmUxThemeBridge.applyWindowDwmAttributes(
                hwnd = hwnd,
                isToolWindow = true,
                extendTopPx = getPanelWindowTitleBarHeight(null),
                isDark = isDark,
                kind = Win32TitleBarKind.TOOL_WINDOW
            )
            appIconBig?.let { icon ->
                SendMessageW(hwnd, WM_SETICON.toUInt(), ICON_BIG.toULong(), icon.toLong())
            }
            appIconSmall?.let { icon ->
                SendMessageW(hwnd, WM_SETICON.toUInt(), ICON_SMALL.toULong(), icon.toLong())
            }
        }
        return hwnd
    }

    private fun isToolboxPanel(panel: DesktopDockPanelNativeImpl<*>): Boolean =
        panel.getTitle().contains("Toolbox", ignoreCase = true) ||
            panel.getTitle().contains("Tool Options", ignoreCase = true)

    private fun isTimelinePanel(panel: DesktopDockPanelNativeImpl<*>): Boolean =
        panel.getTitle().contains("Timeline", ignoreCase = true)

    fun syncMultiWindowDocks() {
        val mainHwnd = activeHwnd ?: return
        val rootWidget = activeWindowImpl?.nativeWidget
        val toolPalettes = mutableListOf<DesktopToolPaletteNativeImpl<*>>()
        val dockPanels = mutableListOf<DesktopDockPanelNativeImpl<*>>()
        val canvases = mutableListOf<DesktopNativeWidget>()
        if (rootWidget != null) {
            var structVer = rootWidget.nativePointer * 31L + rootWidget.children.size
            for (c in rootWidget.children) {
                structVer = structVer * 31L + c.nativePointer + (if (c.visible) 17L else 0L) + c.children.size
                for (gc in c.children) {
                    structVer = structVer * 31L + gc.nativePointer + (if (gc.visible) 13L else 0L) + gc.children.size
                }
            }
            if (rootWidget.attributes["cachedStudioWidgetsVer"] as? Long == structVer) {
                @Suppress("UNCHECKED_CAST")
                (rootWidget.attributes["cachedToolPalettes"] as? List<DesktopToolPaletteNativeImpl<*>>)?.let {
                    toolPalettes.addAll(it)
                }
                @Suppress("UNCHECKED_CAST")
                (rootWidget.attributes["cachedDockPanels"] as? List<DesktopDockPanelNativeImpl<*>>)?.let {
                    dockPanels.addAll(it)
                }
                @Suppress("UNCHECKED_CAST")
                (rootWidget.attributes["cachedStudioCanvases"] as? List<DesktopNativeWidget>)?.let {
                    canvases.addAll(it)
                }
            } else {
                collectStudioWidgets(rootWidget, toolPalettes, dockPanels, canvases)
                rootWidget.attributes["cachedStudioWidgetsVer"] = structVer
                rootWidget.attributes["cachedToolPalettes"] = toolPalettes.toList()
                rootWidget.attributes["cachedDockPanels"] = dockPanels.toList()
                rootWidget.attributes["cachedStudioCanvases"] = canvases.toList()
            }
        }
        val isStudioApp = toolPalettes.isNotEmpty() || dockPanels.isNotEmpty()
        if (!isStudioApp) return

        val multiWindow = isStudioMultiWindowMode(rootWidget)
        if (lastMultiWindowVisibilityState == multiWindow && (!multiWindow || panelHwnds.size == dockPanels.size)) return
        lastMultiWindowVisibilityState = multiWindow

        val hInstance = Win32WinMain.hInstance ?: GetModuleHandleW(null)
        val className = "GenericUIWin32HostWindowW"

        if (multiWindow) {
            var rightIndex = 0
            for (panel in dockPanels) {
                if (panelHwnds[panel] == null) {
                    val (px, py, pw, ph) = when {
                        isToolboxPanel(panel) -> intArrayOf(16, 40, 248, 820)
                        isTimelinePanel(panel) -> intArrayOf(272, 24, 760, 168)
                        else -> {
                            val idx = rightIndex++
                            val col = idx % 2
                            val row = idx / 2
                            val wx = if (col == 0) 1040 else 1476
                            val wy = if (col == 0) 532 + row * 220 else 24 + row * 250
                            val ww = 428
                            val wh = if (col == 0) 212 else 242
                            intArrayOf(wx, wy, ww, wh)
                        }
                    }
                    val created = createAuxiliaryDockWindow(
                        hInstance = hInstance,
                        className = className,
                        title = panel.getTitle().ifBlank { "Panel" },
                        x = px,
                        y = py,
                        width = pw,
                        height = ph,
                        ownerHwnd = mainHwnd
                    )
                    if (created != null) {
                        panelHwnds[panel] = created
                        hwndToPanel[created.toLong()] = panel
                        SetWindowPos(
                            created,
                            HWND_TOP,
                            px,
                            py,
                            pw,
                            ph,
                            (SWP_NOACTIVATE or SWP_FRAMECHANGED).toUInt()
                        )
                    }
                }
            }
            SetWindowPos(
                mainHwnd,
                HWND_TOP,
                272,
                200,
                760,
                660,
                (SWP_SHOWWINDOW or SWP_FRAMECHANGED).toUInt()
            )
            for (panelHwnd in panelHwnds.values) {
                ShowWindow(panelHwnd, SW_SHOWNOACTIVATE)
                InvalidateRect(panelHwnd, null, 1)
                UpdateWindow(panelHwnd)
            }
            BringWindowToTop(mainHwnd)
            SetForegroundWindow(mainHwnd)
            InvalidateRect(mainHwnd, null, 1)
            UpdateWindow(mainHwnd)
        } else {
            for (panelHwnd in panelHwnds.values) {
                ShowWindow(panelHwnd, SW_HIDE)
            }
            SetWindowPos(
                mainHwnd,
                HWND_TOP,
                60,
                40,
                1440,
                860,
                (SWP_SHOWWINDOW or SWP_FRAMECHANGED).toUInt()
            )
            InvalidateRect(mainHwnd, null, 1)
            UpdateWindow(mainHwnd)
        }
    }

    fun invalidateAllWindows() {
        activeHwnd?.let { InvalidateRect(it, null, 0) }
        for (panelHwnd in panelHwnds.values) {
            if (IsWindowVisible(panelHwnd) != 0) {
                InvalidateRect(panelHwnd, null, 0)
            }
        }
        for (dialogHwnd in dialogHwnds.values) {
            if (IsWindow(dialogHwnd) != 0 && IsWindowVisible(dialogHwnd) != 0) {
                InvalidateRect(dialogHwnd, null, 0)
            }
        }
    }

    private fun computeDialogFrameDelta(
        style: UInt,
        exStyle: UInt,
        existingHwnd: HWND? = null
    ): Pair<Int, Int> = memScoped {
        if (existingHwnd != null && IsWindow(existingHwnd) != 0) {
            val winRect = alloc<RECT>()
            val cliRect = alloc<RECT>()
            if (GetWindowRect(existingHwnd, winRect.ptr) != 0 && GetClientRect(existingHwnd, cliRect.ptr) != 0) {
                val cw = cliRect.right - cliRect.left
                val ch = cliRect.bottom - cliRect.top
                if (cw > 0 && ch > 0) {
                    val deltaW = ((winRect.right - winRect.left) - cw).coerceAtLeast(0)
                    val deltaH = ((winRect.bottom - winRect.top) - ch).coerceAtLeast(0)
                    return@memScoped deltaW to deltaH
                }
            }
        }
        val rect = alloc<RECT>()
        rect.left = 0
        rect.top = 0
        rect.right = 400
        rect.bottom = 300
        AdjustWindowRectEx(rect.ptr, style, 0, exStyle)
        val deltaW = ((rect.right - rect.left) - 400).coerceAtLeast(0)
        val deltaH = ((rect.bottom - rect.top) - 300).coerceAtLeast(0)
        deltaW to deltaH
    }

    private fun computeDesiredDialogWindowBounds(
        dialog: DesktopDialogWindowNativeImpl<*>,
        ownerHwnd: HWND,
        style: UInt,
        exStyle: UInt,
        existingHwnd: HWND? = null
    ): IntArray = memScoped {
        val ownerRect = alloc<RECT>()
        val hasOwnerRect = GetWindowRect(ownerHwnd, ownerRect.ptr) != 0
        val ownerW = if (hasOwnerRect) (ownerRect.right - ownerRect.left).coerceAtLeast(320) else 960
        val ownerH = if (hasOwnerRect) (ownerRect.bottom - ownerRect.top).coerceAtLeast(240) else 720
        val maxWindowW = (ownerW - 32).coerceAtLeast(220)
        val maxWindowH = (ownerH - 32).coerceAtLeast(140)
        val (nonClientW, nonClientH) = computeDialogFrameDelta(style, exStyle, existingHwnd)
        val b = dialog.nativeWidget.bounds
        val hasExplicitBounds = dialog.nativeWidget.attributes["hasExplicitBounds"] == true
        val (dw, dh) = if (hasExplicitBounds && b.width > 0 && b.height > 0) {
            b.width.coerceIn(220.coerceAtMost(maxWindowW), maxWindowW) to
                b.height.coerceIn(140.coerceAtMost(maxWindowH), maxWindowH)
        } else {
            ensureSharedFonts()
            val maxClientW = (maxWindowW - nonClientW).coerceAtLeast(180)
            val maxClientH = (maxWindowH - nonClientH).coerceAtLeast(100)
            val intrinsicClient = Win32GeneralComponentTreeRenderer.measureDialogIntrinsicSize(
                dialogWidget = dialog.nativeWidget,
                maxViewportWidth = maxClientW,
                maxViewportHeight = maxClientH
            )
            val targetW = (intrinsicClient.width + nonClientW).coerceIn(220.coerceAtMost(maxWindowW), maxWindowW)
            val targetH = (intrinsicClient.height + nonClientH).coerceIn(140.coerceAtMost(maxWindowH), maxWindowH)
            dialog.nativeWidget.bounds = Rect(b.x, b.y, targetW, targetH)
            targetW to targetH
        }
        val dx = (if (hasOwnerRect) ownerRect.left else 80) + (ownerW - dw) / 2
        val dy = (if (hasOwnerRect) ownerRect.top else 50) + (ownerH - dh) / 2
        intArrayOf(dx, dy, dw, dh, ownerW, ownerH)
    }

    private fun createNativeDialogWindow(dialog: DesktopDialogWindowNativeImpl<*>): HWND? = memScoped {
        val ownerHwnd = activeHwnd ?: return@memScoped null
        if (IsWindow(ownerHwnd) == 0) return@memScoped null
        val hInstance = Win32WinMain.hInstance ?: GetModuleHandleW(null)
        val className = "GenericUIWin32HostWindowW"
        val title = dialog.getTitle().ifBlank { "Dialog" }
        val style = (WS_POPUP or WS_CAPTION.toUInt() or WS_SYSMENU.toUInt() or WS_THICKFRAME.toUInt() or WS_CLIPCHILDREN.toUInt())
        val exStyle = Win32HwndHandle.WS_EX_DLGMODALFRAME.toUInt()
        val initial = computeDesiredDialogWindowBounds(dialog, ownerHwnd, style, exStyle, null)
        val dx = initial[0]
        val dy = initial[1]
        val dw = initial[2]
        val dh = initial[3]
        val created = CreateWindowExW(
            dwExStyle = exStyle,
            lpClassName = className,
            lpWindowName = title,
            dwStyle = style,
            X = dx,
            Y = dy,
            nWidth = dw,
            nHeight = dh,
            hWndParent = ownerHwnd,
            hMenu = null,
            hInstance = hInstance,
            lpParam = null
        )
        if (created != null) {
            val isDark = isWindowsSystemDarkTheme()
            applyWindowsImmersiveTitleBarTheme(created, isDark)
            appIconSmall?.let { icon ->
                SendMessageW(created, WM_SETICON.toUInt(), ICON_SMALL.toULong(), icon.toLong())
            }
            dialogHwnds[dialog] = created
            hwndToDialog[created.toLong()] = dialog
            ShowWindow(created, SW_SHOWNORMAL)
            val adjusted = computeDesiredDialogWindowBounds(dialog, ownerHwnd, style, exStyle, created)
            SetWindowPos(
                created,
                HWND_TOP,
                adjusted[0],
                adjusted[1],
                adjusted[2],
                adjusted[3],
                (SWP_SHOWWINDOW or SWP_FRAMECHANGED).toUInt()
            )
            dialog.nativeWidget.attributes["_lastSyncedDialogMutVer"] = dialog.nativeWidget.subtreeMutationVersion
            dialog.nativeWidget.attributes["_lastSyncedDialogExplicit"] = (dialog.nativeWidget.attributes["hasExplicitBounds"] == true)
            dialog.nativeWidget.attributes["_lastSyncedDialogExplicitW"] = dialog.nativeWidget.bounds.width
            dialog.nativeWidget.attributes["_lastSyncedDialogExplicitH"] = dialog.nativeWidget.bounds.height
            dialog.nativeWidget.attributes["_lastSyncedOwnerW"] = adjusted[4]
            dialog.nativeWidget.attributes["_lastSyncedOwnerH"] = adjusted[5]
            BringWindowToTop(created)
            InvalidateRect(created, null, 1)
            UpdateWindow(created)
        }
        created
    }

    internal fun closeNativeDialogWindow(dialog: DesktopDialogWindowNativeImpl<*>) {
        val dHwnd = dialogHwnds.remove(dialog) ?: return
        val hwndKey = dHwnd.toLong()
        hwndToDialog.remove(hwndKey)
        hitRegionsByHwnd.remove(hwndKey)
        val editKeys = nativeEditEntriesByWidget.keys.filter { it.first == hwndKey }
        for (k in editKeys) {
            val entry = nativeEditEntriesByWidget.remove(k)
            val ch = entry?.childHwnd
            if (ch != null) {
                nativeEditEntriesByChildHwnd.remove(ch.toLong())
                origEditWndProcByChildHwnd.remove(ch.toLong())
            }
        }
        if (IsWindow(dHwnd) != 0) {
            ShowWindow(dHwnd, SW_HIDE)
            DestroyWindow(dHwnd)
        }
    }

    fun syncActiveDialogWindows() = memScoped {
        if (DesktopDialogWindowNativeImpl.activeDialogs.isEmpty() && dialogHwnds.isEmpty()) return@memScoped
        val ownerHwnd = activeHwnd ?: return@memScoped
        if (IsWindow(ownerHwnd) == 0) return@memScoped
        val ownerRect = alloc<RECT>()
        val hasOwnerRect = GetWindowRect(ownerHwnd, ownerRect.ptr) != 0
        val curOwnerW = if (hasOwnerRect) (ownerRect.right - ownerRect.left).coerceAtLeast(320) else 960
        val curOwnerH = if (hasOwnerRect) (ownerRect.bottom - ownerRect.top).coerceAtLeast(240) else 720
        val style = (WS_POPUP or WS_CAPTION.toUInt() or WS_SYSMENU.toUInt() or WS_THICKFRAME.toUInt() or WS_CLIPCHILDREN.toUInt())
        val exStyle = Win32HwndHandle.WS_EX_DLGMODALFRAME.toUInt()
        val currentActive = DesktopDialogWindowNativeImpl.activeDialogs
            .filter { it.nativeWidget.visible && !it.nativeWidget.disposed }
        for (existing in dialogHwnds.keys.toList()) {
            val existingHwnd = dialogHwnds[existing]
            if (existing !in currentActive || existingHwnd == null || IsWindow(existingHwnd) == 0) {
                closeNativeDialogWindow(existing)
            }
        }
        for (dialog in currentActive) {
            val existingHwnd = dialogHwnds[dialog]
            if (existingHwnd == null || IsWindow(existingHwnd) == 0) {
                createNativeDialogWindow(dialog)
            } else {
                if (IsWindowVisible(existingHwnd) == 0) {
                    ShowWindow(existingHwnd, SW_SHOWNORMAL)
                }
                val attrs = dialog.nativeWidget.attributes
                val mutVer = dialog.nativeWidget.subtreeMutationVersion
                val hasExplicit = attrs["hasExplicitBounds"] == true
                val b = dialog.nativeWidget.bounds
                val lastMutVer = attrs["_lastSyncedDialogMutVer"] as? Long
                val lastExplicit = attrs["_lastSyncedDialogExplicit"] as? Boolean
                val lastExplicitW = attrs["_lastSyncedDialogExplicitW"] as? Int
                val lastExplicitH = attrs["_lastSyncedDialogExplicitH"] as? Int
                val lastOwnerW = attrs["_lastSyncedOwnerW"] as? Int
                val lastOwnerH = attrs["_lastSyncedOwnerH"] as? Int
                val needsResync = mutVer != lastMutVer ||
                    hasExplicit != lastExplicit ||
                    (hasExplicit && (b.width != lastExplicitW || b.height != lastExplicitH)) ||
                    curOwnerW != lastOwnerW ||
                    curOwnerH != lastOwnerH
                if (needsResync) {
                    val next = computeDesiredDialogWindowBounds(dialog, ownerHwnd, style, exStyle, existingHwnd)
                    attrs["_lastSyncedDialogMutVer"] = dialog.nativeWidget.subtreeMutationVersion
                    attrs["_lastSyncedDialogExplicit"] = hasExplicit
                    attrs["_lastSyncedDialogExplicitW"] = dialog.nativeWidget.bounds.width
                    attrs["_lastSyncedDialogExplicitH"] = dialog.nativeWidget.bounds.height
                    attrs["_lastSyncedOwnerW"] = next[4]
                    attrs["_lastSyncedOwnerH"] = next[5]
                    SetWindowPos(
                        existingHwnd,
                        HWND_TOP,
                        next[0],
                        next[1],
                        next[2],
                        next[3],
                        (SWP_NOACTIVATE or SWP_NOZORDER).toUInt()
                    )
                    InvalidateRect(existingHwnd, null, 1)
                }
            }
        }
    }

    fun updateMainWindowTitle(windowImpl: WindowsWindowNativeImpl, title: String) {
        if (activeWindowImpl === windowImpl) {
            activeHwnd?.let { hwnd ->
                SetWindowTextW(hwnd, title.ifBlank { "GenericUI" })
                InvalidateRect(hwnd, null, 0)
            }
        }
    }

    fun onMainWindowTitleBarChanged(windowImpl: WindowsWindowNativeImpl) {
        if (activeWindowImpl === windowImpl) {
            activeHwnd?.let { hwnd ->
                SetWindowPos(
                    hwnd,
                    null,
                    0,
                    0,
                    0,
                    0,
                    (SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_FRAMECHANGED).toUInt()
                )
                InvalidateRect(hwnd, null, 0)
            }
        }
    }

    fun createAndShowWin32Window(windowImpl: WindowsWindowNativeImpl) {
        val existingHwnd = activeHwnd
        if (existingHwnd != null && activeWindowImpl === windowImpl && IsWindow(existingHwnd) != 0) {
            SetWindowTextW(existingHwnd, windowImpl.getTitle().ifBlank { "GenericUI" })
            InvalidateRect(existingHwnd, null, 1)
            UpdateWindow(existingHwnd)
            return
        }
        Win32WinMain.initializeFromProcessStartup()
        activeWindowImpl = windowImpl
        monitorRefreshRateHz = queryMonitorRefreshRateHz()
        lastMultiWindowVisibilityState = null

        memScoped {
            val hInstance = Win32WinMain.hInstance ?: GetModuleHandleW(null)
            resolveAppIcons(hInstance)

            val className = "GenericUIWin32HostWindowW"
            if (!windowClassRegistered) {
                val wc = alloc<WNDCLASSEXW>()
                wc.cbSize = sizeOf<WNDCLASSEXW>().toUInt()
                wc.style = (CS_HREDRAW or CS_VREDRAW).toUInt()
                wc.lpfnWndProc = staticCFunction(::win32HostWndProc)
                wc.hInstance = hInstance
                wc.hIcon = appIconBig
                wc.hIconSm = appIconSmall
                wc.hCursor = LoadCursorW(null, IDC_ARROW)
                wc.hbrBackground = CreateSolidBrush(
                    if (isWindowsSystemDarkTheme()) darkSystemPalette.bgBase else lightSystemPalette.bgBase
                )
                wc.lpszClassName = className.wcstr.ptr
                RegisterClassExW(wc.ptr)
                windowClassRegistered = true
            }

            val minSize = windowImpl.getMinWindowSize()
            val attrMinW = (windowImpl.nativeWidget.attributes["minWidthPx"] as? Number)?.toInt() ?: 0
            val attrMinH = (windowImpl.nativeWidget.attributes["minHeightPx"] as? Number)?.toInt() ?: 0
            val minWidthPx = maxOf(minSize?.width ?: 0, attrMinW)
            val minHeightPx = maxOf(minSize?.height ?: 0, attrMinH)
            val size = windowImpl.getWindowSize()
            val width = maxOf(size.width, minWidthPx, 860)
            val height = maxOf(size.height, minHeightPx, 620)
            val title = windowImpl.getTitle().ifBlank { "GenericUI" }

            val initialStyle = if (visualTestMode) {
                (WS_OVERLAPPEDWINDOW or WS_CLIPCHILDREN).toUInt()
            } else {
                (WS_OVERLAPPEDWINDOW or WS_VISIBLE or WS_CLIPCHILDREN).toUInt()
            }
            val hwnd = CreateWindowExW(
                dwExStyle = WS_EX_APPWINDOW.toUInt(),
                lpClassName = className,
                lpWindowName = title,
                dwStyle = initialStyle,
                X = 284,
                Y = 198,
                nWidth = width,
                nHeight = height,
                hWndParent = null,
                hMenu = null,
                hInstance = hInstance,
                lpParam = null
            )
            activeHwnd = hwnd
            if (hwnd != null) {
                applyWindowsImmersiveTitleBarTheme(hwnd, isWindowsSystemDarkTheme())
                appIconBig?.let { icon ->
                    SendMessageW(hwnd, WM_SETICON.toUInt(), ICON_BIG.toULong(), icon.toLong())
                }
                appIconSmall?.let { icon ->
                    SendMessageW(hwnd, WM_SETICON.toUInt(), ICON_SMALL.toULong(), icon.toLong())
                }
                val hMenuBar = buildWin32MenuBar(windowImpl)
                if (hMenuBar != null) {
                    SetMenu(hwnd, hMenuBar)
                    DrawMenuBar(hwnd)
                }
                if (visualTestMode) {
                    ShowWindow(hwnd, SW_SHOWNOACTIVATE)
                    syncMultiWindowDocks()
                    syncActiveDialogWindows()
                    if (lastMultiWindowVisibilityState != true) {
                        SetWindowPos(
                            hwnd,
                            HWND_TOP,
                            80,
                            50,
                            width,
                            height,
                            (SWP_SHOWWINDOW or SWP_FRAMECHANGED or SWP_NOACTIVATE).toUInt()
                        )
                    }
                } else {
                    ShowWindow(hwnd, Win32WinMain.nCmdShow)
                    ShowWindow(hwnd, SW_SHOWNORMAL)
                    syncMultiWindowDocks()
                    syncActiveDialogWindows()
                    if (lastMultiWindowVisibilityState != true) {
                        SetWindowPos(
                            hwnd,
                            HWND_TOP,
                            80,
                            50,
                            width,
                            height,
                            (SWP_SHOWWINDOW or SWP_FRAMECHANGED).toUInt()
                        )
                    }
                    BringWindowToTop(hwnd)
                    SetForegroundWindow(hwnd)
                    SetFocus(hwnd)
                }
                InvalidateRect(hwnd, null, 1)
                UpdateWindow(hwnd)
            }
        }
    }

    private fun formatMenuIconPrefix(rawIcon: String?): String {
        val spec = rawIcon?.trim().orEmpty()
        if (spec.isEmpty()) return ""
        if (!spec.startsWith("res://") && !spec.startsWith("@") && !spec.startsWith("ic_") && spec.length <= 4) {
            return "$spec "
        }
        val key = spec.lowercase()
            .removePrefix("res://drawable/")
            .removePrefix("@drawable/")
            .removePrefix("ic_tool_")
            .removePrefix("ic_")
            .replace('_', '-')
        val glyph = when {
            key.contains("move") -> "✥"
            key.contains("rect") -> "▭"
            key.contains("ellipse") -> "◯"
            key.contains("lasso") -> "➰"
            key.contains("wand") -> "🪄"
            key.contains("crop") -> "✂"
            key.contains("brush") -> "🖌"
            key.contains("pencil") -> "✏"
            key.contains("airbrush") -> "💨"
            key.contains("eraser") -> "⌫"
            key.contains("bucket") || key.contains("fill") -> "🪣"
            key.contains("gradient") -> "🌈"
            key.contains("eyedropper") || key.contains("picker") -> "💧"
            key.contains("text") -> "T"
            key.contains("shape") -> "⬢"
            key.contains("bezier") || key.contains("pen") -> "✒"
            key.contains("node") -> "◈"
            else -> "▸"
        }
        return "$glyph "
    }

    private fun populateWin32PopupMenu(hPopup: HMENU, menuWidget: DesktopNativeWidget) {
        for (itemWidget in menuWidget.children) {
            val itemImpl = itemWidget.attributes["nativeImpl"]
            when (itemImpl) {
                is DesktopMenuNativeImpl<*> -> {
                    val hSubPopup = CreatePopupMenu() ?: continue
                    populateWin32PopupMenu(hSubPopup, itemWidget)
                    val iconPrefix = formatMenuIconPrefix(itemImpl.getIcon())
                    val subLabel = itemImpl.getLabel().ifBlank { "Submenu" }
                    AppendMenuW(hPopup, MF_POPUP.toUInt(), hSubPopup.toLong().toULong(), "$iconPrefix$subLabel")
                }
                is DesktopMenuItemNativeImpl<*> -> {
                    val cmdId = nextMenuCommandId++
                    val rawLabel = itemImpl.getLabel().ifBlank { "Item $cmdId" }
                    val iconPrefix = formatMenuIconPrefix(itemImpl.getIcon())
                    val shortcutStr = itemImpl.getShortcutText()?.takeIf { it.isNotBlank() }
                        ?: itemImpl.getShortcut()?.displayText?.takeIf { it.isNotBlank() }
                    val fullLabel = if (!shortcutStr.isNullOrBlank()) {
                        "$iconPrefix$rawLabel\t$shortcutStr"
                    } else {
                        "$iconPrefix$rawLabel"
                    }
                    var flags = MF_STRING
                    if (itemImpl.isChecked()) {
                        flags = flags or MF_CHECKED
                    }
                    if (!itemImpl.isEnabled()) {
                        flags = flags or MF_GRAYED
                    }
                    val action: () -> Unit = {
                        itemImpl.dispatchClick()
                        syncMultiWindowDocks()
                        invalidateAllWindows()
                    }
                    menuCommandHandlers[cmdId] = action
                    if (!shortcutStr.isNullOrBlank()) {
                        val normKey = shortcutStr.uppercase().replace(" ", "")
                        shortcutCommandHandlers[normKey] = action
                    }
                    AppendMenuW(hPopup, flags.toUInt(), cmdId.toULong(), fullLabel)
                }
                else -> {
                    AppendMenuW(hPopup, MF_SEPARATOR.toUInt(), 0u, null)
                }
            }
        }
    }

    private fun buildWin32MenuBar(windowImpl: WindowsWindowNativeImpl): HMENU? {
        menuCommandHandlers.clear()
        shortcutCommandHandlers.clear()
        nextMenuCommandId = 1001
        val menuBarWidget = windowImpl.attachedMenuBarWidget() ?: return null
        val hMenuBar = CreateMenu() ?: return null

        for (menuWidget in menuBarWidget.children) {
            val menuImpl = menuWidget.attributes["nativeImpl"] as? DesktopMenuNativeImpl<*>
            val rawMenuLabel = menuImpl?.getLabel()?.ifBlank { "Menu" }
                ?: (menuWidget.attributes["label"] as? String)
                ?: "Menu"
            val iconPrefix = formatMenuIconPrefix(menuImpl?.getIcon())
            val hPopup = CreatePopupMenu() ?: continue
            populateWin32PopupMenu(hPopup, menuWidget)
            AppendMenuW(hMenuBar, MF_POPUP.toUInt(), hPopup.toLong().toULong(), "$iconPrefix$rawMenuLabel")
        }
        return hMenuBar
    }

    var visualTestMode: Boolean = false
    var visualTestBannerText: String? = null

    fun pumpVisualWindowEvents(durationMs: Long = 32L) = memScoped {
        val hwnd = activeHwnd ?: return@memScoped
        val qpcFreq = alloc<LARGE_INTEGER>()
        val qpcCounter = alloc<LARGE_INTEGER>()
        QueryPerformanceFrequency(qpcFreq.ptr)
        val ticksPerMs = (qpcFreq.QuadPart.toDouble() / 1000.0).coerceAtLeast(1.0)
        QueryPerformanceCounter(qpcCounter.ptr)
        val startMs = qpcCounter.QuadPart.toDouble() / ticksPerMs
        val msg = alloc<MSG>()

        ensureOverlayInvalidationListenerInstalled()
        invalidateAllWindows()
        do {
            while (PeekMessageW(msg.ptr, null, 0u, 0u, PM_REMOVE.toUInt()) != 0) {
                if (msg.message == WM_QUIT.toUInt()) {
                    return@memScoped
                }
                if (preTranslateNativeEditMessage(msg)) {
                    continue
                }
                TranslateMessage(msg.ptr)
                DispatchMessageW(msg.ptr)
            }
            QueryPerformanceCounter(qpcCounter.ptr)
            val nowMs = qpcCounter.QuadPart.toDouble() / ticksPerMs
            isDispatchingVBlank = true
            vblankTreeInvalidated = false
            overlayMutatedDuringVBlank = false
            val anyVBlank = try {
                dispatchActiveVBlankWidgets(activeWindowImpl?.nativeWidget, nowMs)
            } finally {
                isDispatchingVBlank = false
            }
            var hudAlphaAnimating = false
            val alphaDiff = overlayHudTargetAlpha - overlayHudCurrentAlpha
            if (kotlin.math.abs(alphaDiff) > 0.004f) {
                val fadeElapsedMs = if (lastOverlayFadeTickMs > 0.0) (nowMs - lastOverlayFadeTickMs).coerceIn(4.0, 50.0) else 16.0
                lastOverlayFadeTickMs = nowMs
                val step = (fadeElapsedMs.toFloat() / 220f).coerceIn(0.02f, 1f)
                overlayHudCurrentAlpha = if (alphaDiff > 0f) {
                    (overlayHudCurrentAlpha + step).coerceAtMost(overlayHudTargetAlpha)
                } else {
                    (overlayHudCurrentAlpha - step).coerceAtLeast(overlayHudTargetAlpha)
                }
                hudAlphaAnimating = true
            } else if (overlayHudCurrentAlpha != overlayHudTargetAlpha) {
                overlayHudCurrentAlpha = overlayHudTargetAlpha
                hudAlphaAnimating = true
            }
            if (anyVBlank || vblankTreeInvalidated || overlayMutatedDuringVBlank || hudAlphaAnimating) {
                activeHwnd?.let { markVideoStageDirty(it) }
            }
            activeHwnd?.let { UpdateWindow(it) }
            for (panelHwnd in panelHwnds.values) {
                if (IsWindowVisible(panelHwnd) != 0) {
                    UpdateWindow(panelHwnd)
                }
            }
            for (dialogHwnd in dialogHwnds.values) {
                if (IsWindow(dialogHwnd) != 0 && IsWindowVisible(dialogHwnd) != 0) {
                    UpdateWindow(dialogHwnd)
                }
            }
            if (nowMs - startMs < durationMs) {
                Sleep(4u)
            }
        } while (nowMs - startMs < durationMs && activeHwnd != null)
    }

    fun closeAllVisualWindows() {
        for (dialogHwnd in dialogHwnds.values.toList()) {
            if (IsWindow(dialogHwnd) != 0) {
                DestroyWindow(dialogHwnd)
            }
        }
        dialogHwnds.clear()
        hwndToDialog.clear()
        for (panelHwnd in panelHwnds.values.toList()) {
            DestroyWindow(panelHwnd)
        }
        panelHwnds.clear()
        hwndToPanel.clear()
        activeHwnd?.let { DestroyWindow(it) }
        clearActiveWindow()
    }

    fun runMessageLoop() = memScoped {
        if (visualTestMode) {
            pumpVisualWindowEvents(64L)
            return@memScoped
        }
        val hwnd = activeHwnd ?: return@memScoped
        monitorRefreshRateHz = queryMonitorRefreshRateHz()
        val targetFrameIntervalMs = 1000.0 / monitorRefreshRateHz.toDouble()

        val qpcFreq = alloc<LARGE_INTEGER>()
        val qpcCounter = alloc<LARGE_INTEGER>()
        QueryPerformanceFrequency(qpcFreq.ptr)
        val ticksPerMs = (qpcFreq.QuadPart.toDouble() / 1000.0).coerceAtLeast(1.0)

        QueryPerformanceCounter(qpcCounter.ptr)
        var lastFrameTimeMs = qpcCounter.QuadPart.toDouble() / ticksPerMs

        val msg = alloc<MSG>()
        val nextMoveMsg = alloc<MSG>()
        var running = true

        ensureOverlayInvalidationListenerInstalled()

        while (running) {
            var drainedMessages = 0
            while (PeekMessageW(msg.ptr, null, 0u, 0u, PM_REMOVE.toUInt()) != 0) {
                if (msg.message == WM_QUIT.toUInt()) {
                    running = false
                    break
                }
                if (msg.message == WM_MOUSEMOVE.toUInt() && msg.hwnd != null) {
                    while (PeekMessageW(
                            nextMoveMsg.ptr,
                            msg.hwnd,
                            WM_MOUSEMOVE.toUInt(),
                            WM_MOUSEMOVE.toUInt(),
                            PM_REMOVE.toUInt()
                        ) != 0
                    ) {
                        msg.lParam = nextMoveMsg.lParam
                        msg.wParam = nextMoveMsg.wParam
                        msg.pt.x = nextMoveMsg.pt.x
                        msg.pt.y = nextMoveMsg.pt.y
                        msg.time = nextMoveMsg.time
                    }
                } else if (msg.message == WM_MOUSEWHEEL.toUInt() && msg.hwnd != null) {
                    var totalWheelDelta = ((msg.wParam.toLong() ushr 16) and 0xFFFF).toShort().toInt()
                    val lowWord = msg.wParam.toLong() and 0xFFFF
                    while (PeekMessageW(
                            nextMoveMsg.ptr,
                            msg.hwnd,
                            WM_MOUSEWHEEL.toUInt(),
                            WM_MOUSEWHEEL.toUInt(),
                            PM_REMOVE.toUInt()
                        ) != 0
                    ) {
                        val nextDelta = ((nextMoveMsg.wParam.toLong() ushr 16) and 0xFFFF).toShort().toInt()
                        totalWheelDelta = (totalWheelDelta + nextDelta).coerceIn(-32768, 32767)
                        msg.lParam = nextMoveMsg.lParam
                        msg.pt.x = nextMoveMsg.pt.x
                        msg.pt.y = nextMoveMsg.pt.y
                        msg.time = nextMoveMsg.time
                    }
                    val packedWheel = ((totalWheelDelta.toShort().toInt() and 0xFFFF).toLong() shl 16) or lowWord
                    msg.wParam = packedWheel.toULong()
                }
                if (preTranslateNativeEditMessage(msg)) {
                    continue
                }
                TranslateMessage(msg.ptr)
                DispatchMessageW(msg.ptr)
                drainedMessages++
                if (drainedMessages >= 64) {
                    break
                }
                QueryPerformanceCounter(qpcCounter.ptr)
                val drainNowMs = qpcCounter.QuadPart.toDouble() / ticksPerMs
                if (drainNowMs - lastFrameTimeMs >= targetFrameIntervalMs) {
                    break
                }
            }
            if (!running || activeHwnd == null) break

            QueryPerformanceCounter(qpcCounter.ptr)
            val nowMs = qpcCounter.QuadPart.toDouble() / ticksPerMs
            val elapsedMs = nowMs - lastFrameTimeMs

            if (elapsedMs >= targetFrameIntervalMs) {
                lastFrameTimeMs = nowMs
                lastOverlayFadeTickMs = nowMs
                isDispatchingVBlank = true
                vblankTreeInvalidated = false
                overlayMutatedDuringVBlank = false
                val anyVBlankDispatched = try {
                    dispatchActiveVBlankWidgets(activeWindowImpl?.nativeWidget, nowMs)
                } finally {
                    isDispatchingVBlank = false
                }
                val hasActivePopup = com.nexustools.genericui.event.PopupMouseCaptureManager.hasActivePopup
                if (hasActivePopup) {
                    isVideoStageHudAutoHidden = false
                    overlayHudTargetAlpha = 1f
                }
                val hudTimedOut = !hasActivePopup && isPointerInVideoStage && !isVideoStageHudAutoHidden &&
                    lastVideoStageMouseMoveTickMs > 0L &&
                    (GetTickCount64().toLong() - lastVideoStageMouseMoveTickMs) > 5000L
                if (hudTimedOut) {
                    isVideoStageHudAutoHidden = true
                    overlayHudTargetAlpha = 0f
                }
                var hudAlphaAnimating = false
                val alphaDiff = overlayHudTargetAlpha - overlayHudCurrentAlpha
                if (kotlin.math.abs(alphaDiff) > 0.004f) {
                    val step = (elapsedMs.coerceIn(4.0, 50.0).toFloat() / 220f).coerceIn(0.02f, 1f)
                    overlayHudCurrentAlpha = if (alphaDiff > 0f) {
                        (overlayHudCurrentAlpha + step).coerceAtMost(overlayHudTargetAlpha)
                    } else {
                        (overlayHudCurrentAlpha - step).coerceAtLeast(overlayHudTargetAlpha)
                    }
                    hudAlphaAnimating = true
                } else if (overlayHudCurrentAlpha != overlayHudTargetAlpha) {
                    overlayHudCurrentAlpha = overlayHudTargetAlpha
                    hudAlphaAnimating = true
                }
                if (anyVBlankDispatched || vblankTreeInvalidated || overlayMutatedDuringVBlank || hudTimedOut || hudAlphaAnimating) {
                    val vr = lastRenderedVideoStageRect
                    if (vr[2] > vr[0] && vr[3] > vr[1]) {
                        markVideoStageDirty(hwnd)
                    } else {
                        isVBlankVideoStageOnlyRepaint = false
                        InvalidateRect(hwnd, null, 0)
                    }
                    UpdateWindow(hwnd)
                }
            } else {
                val remainingMs = targetFrameIntervalMs - elapsedMs
                val waitMs = (remainingMs - 1.0).toInt().coerceIn(1, 16).toUInt()
                // 0x0004u = MWMO_INPUTAVAILABLE so any message or WM_PAINT already in the queue wakes immediately
                val waitRes = MsgWaitForMultipleObjectsEx(0u, null, waitMs, QS_ALLINPUT.toUInt(), 0x0004u)
                if (waitRes == 0u && PeekMessageW(msg.ptr, null, 0u, 0u, PM_NOREMOVE.toUInt()) == 0) {
                    Sleep(1u)
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun dispatchActiveVBlankWidgets(rootWidget: DesktopNativeWidget?, timestampMs: Double): Boolean {
        val registered = DesktopNativeWidget.activeVBlankWidgetsSnapshot()
        if (registered.isNotEmpty()) {
            var dispatched = false
            for (widget in registered) {
                if (!widget.visible || widget.disposed) continue
                val vblankFn = widget.attributes["vblankDispatch"] as? ((Double?) -> Unit) ?: continue
                val swapFn = widget.attributes["swapCount"] as? (() -> Long)
                val prevSwap = swapFn?.invoke() ?: -1L
                runCatching { vblankFn.invoke(timestampMs) }
                val nextSwap = swapFn?.invoke() ?: -1L
                if ((prevSwap != -1L && nextSwap != prevSwap) || widget.attributes["vblankDirtied"] == true) {
                    widget.attributes["vblankDirtied"] = false
                    dispatched = true
                }
            }
            return dispatched
        }
        return dispatchVBlankTree(rootWidget, timestampMs)
    }

    @Suppress("UNCHECKED_CAST")
    private fun dispatchVBlankTree(widget: DesktopNativeWidget?, timestampMs: Double): Boolean {
        if (widget == null || !widget.visible || widget.disposed) return false
        var dispatched = false
        val vblankFn = widget.attributes["vblankDispatch"] as? ((Double?) -> Unit)
        if (vblankFn != null) {
            val swapFn = widget.attributes["swapCount"] as? (() -> Long)
            val prevSwap = swapFn?.invoke() ?: -1L
            runCatching { vblankFn.invoke(timestampMs) }
            val nextSwap = swapFn?.invoke() ?: -1L
            if ((prevSwap != -1L && nextSwap != prevSwap) || widget.attributes["vblankDirtied"] == true) {
                widget.attributes["vblankDirtied"] = false
                dispatched = true
            }
        }
        val children = runCatching { widget.children.toTypedArray() }.getOrElse { emptyArray() }
        for (child in children) {
            if (dispatchVBlankTree(child, timestampMs)) {
                dispatched = true
            }
        }
        return dispatched
    }

    fun handleCommand(commandId: Int, notifyCode: Int = 0, childHwnd: HWND? = null) {
        if (childHwnd != null) {
            val editEntry = nativeEditEntriesByChildHwnd[childHwnd.toLong()]
            if (editEntry != null) {
                when (notifyCode) {
                    0x0300 /* EN_CHANGE */ -> {
                        if (!isSyncingFromNativeEdit) {
                            val newText = readWin32WindowText(childHwnd)
                            if (newText != editEntry.inputImpl.getValue()) {
                                isSyncingFromNativeEdit = true
                                try {
                                    editEntry.inputImpl.setValue(newText)
                                } finally {
                                    isSyncingFromNativeEdit = false
                                }
                            }
                        }
                    }
                    0x0100 /* EN_SETFOCUS */ -> {
                        onNativeEditChildFocused(editEntry)
                    }
                    0x0200 /* EN_KILLFOCUS */ -> {
                        onNativeEditChildBlurred(editEntry)
                    }
                }
                return
            }
        }
        runCatching {
            menuCommandHandlers[commandId]?.invoke()
        }
    }

    private fun preTranslateNativeEditMessage(msg: MSG): Boolean {
        val targetHwnd = msg.hwnd ?: return false
        val entry = nativeEditEntriesByChildHwnd[targetHwnd.toLong()] ?: return false
        when (msg.message.toInt()) {
            WM_KEYDOWN -> {
                val vk = msg.wParam.toInt()
                val ctrlDown = (GetKeyState(VK_CONTROL).toInt() and 0x8000) != 0
                val shiftDown = (GetKeyState(VK_SHIFT).toInt() and 0x8000) != 0
                if (vk == 'A'.code && ctrlDown) {
                    SendMessageW(targetHwnd, 0x00B1u /* EM_SETSEL */, 0uL, -1L)
                    return true
                }
                if (vk == VK_TAB) {
                    focusNextNativeEditControl(targetHwnd, reverse = shiftDown)
                    return true
                }
                if (vk == VK_RETURN && !entry.inputImpl.isMultiline()) {
                    submitNativeEditControl(targetHwnd)
                    return true
                }
                if (vk == VK_ESCAPE) {
                    SetFocus(entry.parentHwnd)
                    return true
                }
            }
            WM_CHAR -> {
                val ch = msg.wParam.toInt()
                if (ch == 0x01 /* Ctrl+A */ || ch == '\t'.code) {
                    return true
                }
                if ((ch == '\r'.code || ch == '\n'.code) && !entry.inputImpl.isMultiline()) {
                    return true
                }
            }
        }
        return false
    }

    internal fun submitNativeEditControl(childHwnd: HWND) {
        val entry = nativeEditEntriesByChildHwnd[childHwnd.toLong()] ?: return
        val newText = readWin32WindowText(childHwnd)
        if (newText != entry.inputImpl.getValue()) {
            isSyncingFromNativeEdit = true
            try {
                entry.inputImpl.setValue(newText)
            } finally {
                isSyncingFromNativeEdit = false
            }
        }
        runCatching { entry.inputImpl.submitValue() }
        invalidateAllWindows()
    }

    internal fun focusNextNativeEditControl(currentChildHwnd: HWND, reverse: Boolean) {
        val curEntry = nativeEditEntriesByChildHwnd[currentChildHwnd.toLong()] ?: return
        val parentKey = curEntry.parentHwnd.toLong()
        val visibleEntries = nativeEditEntriesByWidget.entries
            .filter { it.key.first == parentKey && it.value.visible && it.value.childHwnd != null && it.value.inputImpl.isEnabled() }
            .map { it.value }
            .sortedWith(compareBy<NativeEditControlEntry> { it.top }.thenBy { it.left })
        if (visibleEntries.isEmpty()) return
        val idx = visibleEntries.indexOfFirst { it.childHwnd == currentChildHwnd }
        val nextIdx = if (idx < 0) {
            0
        } else if (reverse) {
            (idx - 1 + visibleEntries.size) % visibleEntries.size
        } else {
            (idx + 1) % visibleEntries.size
        }
        val targetHwnd = visibleEntries[nextIdx].childHwnd ?: return
        SetFocus(targetHwnd)
        SendMessageW(targetHwnd, 0x00B1u /* EM_SETSEL */, 0uL, -1L)
    }

    private fun onNativeEditChildFocused(entry: NativeEditControlEntry) {
        val prevFocused = focusedWidget
        if (prevFocused !== entry.widget) {
            (prevFocused?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>)?.setFocusedState(false)
            entry.inputImpl.setFocusedState(true)
            focusedWidget = entry.widget
            focusedTextInput = entry.inputImpl
            com.nexustools.genericui.event.InputFocusManager.isNativeTextInputFocused = true
            InvalidateRect(entry.parentHwnd, null, 0)
        }
    }

    private fun onNativeEditChildBlurred(entry: NativeEditControlEntry) {
        entry.inputImpl.setFocusedState(false)
        if (focusedWidget === entry.widget) {
            focusedWidget = null
            focusedTextInput = null
            com.nexustools.genericui.event.InputFocusManager.isNativeTextInputFocused = false
            InvalidateRect(entry.parentHwnd, null, 0)
        }
    }

    internal fun handleCtlColorEdit(hdcChild: HDC, childHwnd: HWND?): Long {
        val isDark = isWindowsSystemDarkTheme()
        val theme = if (isDark) darkSystemPalette else lightSystemPalette
        SetTextColor(hdcChild, theme.textPrimary)
        SetBkColor(hdcChild, theme.bgBase)
        SetBkMode(hdcChild, OPAQUE)
        val brush = if (isDark) {
            editBgBrushDark ?: CreateSolidBrush(theme.bgBase).also { editBgBrushDark = it }
        } else {
            editBgBrushLight ?: CreateSolidBrush(theme.bgBase).also { editBgBrushLight = it }
        }
        return brush?.toLong() ?: 0L
    }

    private fun readWin32WindowText(hwnd: HWND): String = memScoped {
        val len = GetWindowTextLengthW(hwnd)
        if (len <= 0) return@memScoped ""
        val buf = allocArray<WCHARVar>(len + 2)
        GetWindowTextW(hwnd, buf, len + 1)
        buf.toKString()
    }

    private fun beginPaintFrameNativeControls(hwnd: HWND) {
        if (nativeEditEntriesByWidget.isEmpty()) return
        val parentKey = hwnd.toLong()
        for ((key, entry) in nativeEditEntriesByWidget) {
            if (key.first == parentKey) {
                entry.visitedThisFrame = false
            }
        }
    }

    internal fun markTextInputVisitedFromCache(
        widget: DesktopNativeWidget?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        if (widget == null || currentPaintHwndKey == 0L) return
        val entry = nativeEditEntriesByWidget[currentPaintHwndKey to widget] ?: return
        entry.visitedThisFrame = true
        entry.desiredLeft = left + 8
        entry.desiredTop = top + 5
        entry.desiredWidth = (right - left - 16).coerceAtLeast(24)
        entry.desiredHeight = (bottom - top - 10).coerceAtLeast(16)
    }

    private fun endPaintFrameNativeControls(hwnd: HWND) {
        if (nativeEditEntriesByWidget.isEmpty()) return
        if (IsWindow(hwnd) == 0) return
        val parentKey = hwnd.toLong()
        for ((key, entry) in nativeEditEntriesByWidget) {
            if (key.first != parentKey) continue
            if (!entry.visitedThisFrame) {
                val existingHwnd = entry.childHwnd
                if (entry.visible && existingHwnd != null && IsWindow(existingHwnd) != 0) {
                    entry.visible = false
                    ShowWindow(existingHwnd, SW_HIDE)
                }
                continue
            }

            val inputImpl = entry.inputImpl
            val widget = entry.widget
            val value = inputImpl.getValue()
            val placeholder = inputImpl.getPlaceholder()
            val editLeft = entry.desiredLeft
            val editTop = entry.desiredTop
            val editWidth = entry.desiredWidth
            val editHeight = entry.desiredHeight
            val effectiveFont = entry.desiredFont ?: cachedFontNormal
            val isDark = entry.desiredDarkTheme

            var childHwnd = entry.childHwnd
            if (childHwnd != null && IsWindow(childHwnd) == 0) {
                nativeEditEntriesByChildHwnd.remove(childHwnd.toLong())
                origEditWndProcByChildHwnd.remove(childHwnd.toLong())
                entry.childHwnd = null
                childHwnd = null
            }

            if (childHwnd == null) {
                val hInstance = Win32WinMain.hInstance ?: GetModuleHandleW(null)
                val isPassword = inputImpl.isPasswordMode() ||
                    widget.nativeClassName.contains("password", ignoreCase = true) ||
                    (widget.attributes["inputType"] as? String)?.equals("password", ignoreCase = true) == true
                var style = WS_CHILD or WS_TABSTOP or ES_AUTOHSCROLL
                if (inputImpl.isMultiline()) {
                    style = style or ES_MULTILINE or ES_AUTOVSCROLL or WS_VSCROLL or ES_WANTRETURN
                }
                if (isPassword) {
                    style = style or ES_PASSWORD
                }
                if (inputImpl.isReadOnly()) {
                    style = style or ES_READONLY
                }
                if (!inputImpl.isEnabled()) {
                    style = style or WS_DISABLED
                }
                val createdHwnd = CreateWindowExW(
                    dwExStyle = 0u,
                    lpClassName = "EDIT",
                    lpWindowName = null,
                    dwStyle = style.toUInt(),
                    X = editLeft,
                    Y = editTop,
                    nWidth = editWidth,
                    nHeight = editHeight,
                    hWndParent = hwnd,
                    hMenu = entry.commandId.toLong().toCPointer(),
                    hInstance = hInstance,
                    lpParam = null
                )
                if (createdHwnd != null) {
                    entry.childHwnd = createdHwnd
                    entry.left = editLeft
                    entry.top = editTop
                    entry.width = editWidth
                    entry.height = editHeight
                    entry.lastPlaceholder = placeholder
                    entry.lastDarkTheme = isDark
                    nativeEditEntriesByChildHwnd[createdHwnd.toLong()] = entry

                    if (effectiveFont != null) {
                        SendMessageW(createdHwnd, WM_SETFONT.toUInt(), effectiveFont.toLong().toULong(), 0L)
                    }
                    Win32DwmUxThemeBridge.setControlWindowTheme(createdHwnd, isDark)
                    if (placeholder.isNotEmpty()) {
                        memScoped {
                            SendMessageW(createdHwnd, 0x1501u /* EM_SETCUEBANNER */, 1uL, placeholder.wcstr.ptr.toLong())
                        }
                    }
                    isSyncingFromNativeEdit = true
                    try {
                        SetWindowTextW(createdHwnd, value)
                    } finally {
                        isSyncingFromNativeEdit = false
                    }
                    entry.visible = true
                    ShowWindow(createdHwnd, SW_SHOWNOACTIVATE)
                }
            } else {
                if (entry.lastDarkTheme != isDark) {
                    entry.lastDarkTheme = isDark
                    Win32DwmUxThemeBridge.setControlWindowTheme(childHwnd, isDark)
                }
                if (entry.lastPlaceholder != placeholder) {
                    entry.lastPlaceholder = placeholder
                    memScoped {
                        SendMessageW(childHwnd, 0x1501u /* EM_SETCUEBANNER */, 1uL, placeholder.wcstr.ptr.toLong())
                    }
                }
                if (!isSyncingFromNativeEdit) {
                    val curHwndText = readWin32WindowText(childHwnd)
                    if (curHwndText != value) {
                        isSyncingFromNativeEdit = true
                        try {
                            SetWindowTextW(childHwnd, value)
                        } finally {
                            isSyncingFromNativeEdit = false
                        }
                    }
                }
                EnableWindow(childHwnd, if (inputImpl.isEnabled()) 1 else 0)
                if (entry.left != editLeft || entry.top != editTop || entry.width != editWidth || entry.height != editHeight) {
                    entry.left = editLeft
                    entry.top = editTop
                    entry.width = editWidth
                    entry.height = editHeight
                    SetWindowPos(
                        childHwnd,
                        null,
                        editLeft,
                        editTop,
                        editWidth,
                        editHeight,
                        (SWP_NOZORDER or SWP_NOACTIVATE).toUInt()
                    )
                }
                if (!entry.visible) {
                    entry.visible = true
                    ShowWindow(childHwnd, SW_SHOWNOACTIVATE)
                }
            }
        }
    }

    internal fun renderAndMountTextInputControl(
        hwnd: HWND?,
        hdc: HDC?,
        theme: SystemThemePalette,
        font: HFONT?,
        inputImpl: DesktopTextInputNativeImpl<*>,
        widget: DesktopNativeWidget = inputImpl.nativeWidget,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        val isFocused = focusedWidget == widget || focusedTextInput === inputImpl
        val isHovered = hoveredWidget == widget || (hwnd != null && isRectHovered(hwnd, left, top, right, bottom))
        val bg = theme.bgBase
        val border = when {
            isFocused -> theme.accentBlueBorder
            isHovered -> theme.borderStrong
            else -> theme.borderSubtle
        }
        drawRoundedRect(hdc, left, top, right, bottom, 4, bg, border)

        val value = inputImpl.getValue()
        val placeholder = inputImpl.getPlaceholder()
        val effectiveFont = font ?: cachedFontNormal

        var targetEntry: NativeEditControlEntry? = null
        if (isFocused && hwnd != null && IsWindow(hwnd) != 0) {
            val parentKey = hwnd.toLong()
            val editLeft = left + 6
            val editTop = if (inputImpl.isMultiline()) top + 4 else top + max(3, (bottom - top - 18) / 2)
            val editWidth = max(24, (right - left) - 12)
            val editHeight = if (inputImpl.isMultiline()) max(20, (bottom - top) - 8) else max(18, (bottom - top) - 6)

            val entry = nativeEditEntriesByWidget.getOrPut(parentKey to widget) {
                NativeEditControlEntry(
                    parentHwnd = hwnd,
                    childHwnd = null,
                    commandId = nextEditControlCommandId++,
                    inputImpl = inputImpl,
                    widget = widget
                )
            }
            entry.inputImpl = inputImpl
            entry.widget = widget
            entry.desiredLeft = editLeft
            entry.desiredTop = editTop
            entry.desiredWidth = editWidth
            entry.desiredHeight = editHeight
            entry.desiredFont = effectiveFont
            entry.desiredDarkTheme = theme.isDark
            entry.visitedThisFrame = true
            targetEntry = entry
        } else {
            val display = value.ifEmpty { placeholder }
            val fg = if (value.isEmpty()) theme.textMuted else theme.textPrimary
            if (effectiveFont != null) {
                SelectObject(hdc, effectiveFont)
            }
            drawTextInRect(hdc, display, left + 8, top + 2, right - 8, bottom - 2, fg)
        }

        addHitRegion(
            HitRegion(
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                tooltip = inputImpl.getTooltipText(),
                widget = widget,
                isTextInput = true,
                onClick = { _, _ ->
                    setWidgetFocused(widget)
                    val chHwnd = targetEntry?.childHwnd
                    if (targetEntry?.visible == true && chHwnd != null && IsWindow(chHwnd) != 0) {
                        SetFocus(chHwnd)
                    } else if (hwnd != null) {
                        InvalidateRect(hwnd, null, 0)
                    }
                }
            )
        )
    }

    fun handleKeyDown(hwnd: HWND, vkCode: Int): Boolean {
        if ((vkCode == VK_ESCAPE || vkCode == VK_F11) && isFullscreenActiveForMainWindow()) {
            activeFullscreenElementWidget?.attributes?.remove("data-genericui-fullscreen-element")
            val mainWin = com.nexustools.genericui.component.shell.Application.current?.mainWindow
            if (mainWin != null) {
                if (mainWin.fullscreenElement != null) {
                    mainWin.exitElementFullscreen()
                } else if (mainWin.isFullscreen()) {
                    mainWin.setFullscreen(false)
                }
            }
            activeWindowImpl?.let { winImpl ->
                winImpl.setFullscreenElement(null, com.nexustools.genericui.enums.window.DisplayCutoutMode.SHORT_EDGES)
                winImpl.setFullscreen(false, com.nexustools.genericui.enums.window.DisplayCutoutMode.SHORT_EDGES)
                setWindowFullscreen(winImpl, false, null)
            }
            return true
        }
        val textInput = focusedTextInput
        if (textInput != null) {
            when (vkCode) {
                VK_BACK -> {
                    val cur = textInput.getValue()
                    if (cur.isNotEmpty()) {
                        textInput.setValue(cur.dropLast(1))
                        invalidateAllWindows()
                    }
                    return true
                }
                VK_RETURN -> {
                    textInput.submitValue()
                    invalidateAllWindows()
                    return true
                }
                VK_ESCAPE -> {
                    (focusedWidget?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>)?.setFocusedState(false)
                    focusedWidget = null
                    focusedTextInput = null
                    com.nexustools.genericui.event.InputFocusManager.isNativeTextInputFocused = false
                    invalidateAllWindows()
                    return true
                }
            }
        }

        if (vkCode == VK_ESCAPE && com.nexustools.genericui.event.PopupMouseCaptureManager.hasActivePopup) {
            com.nexustools.genericui.event.PopupMouseCaptureManager.interceptPointerDown(Float.NaN, Float.NaN, null)
            com.nexustools.genericui.event.PopupMouseCaptureManager.interceptClick(Float.NaN, Float.NaN, null)
            markVideoStageDirty(hwnd)
            invalidateAllWindows()
            UpdateWindow(hwnd)
            return true
        }

        val ctrlDown = (GetKeyState(VK_CONTROL).toInt() and 0x8000) != 0
        val shiftDown = (GetKeyState(VK_SHIFT).toInt() and 0x8000) != 0
        val altDown = (GetKeyState(VK_MENU).toInt() and 0x8000) != 0
        if (textInput != null && !ctrlDown && !altDown && vkCode !in VK_F1..VK_F12) {
            return false
        }

        val keyName = when (vkCode) {
            in 0x41..0x5A -> vkCode.toChar().toString()
            in 0x30..0x39 -> vkCode.toChar().toString()
            in VK_F1..VK_F12 -> "F${vkCode - VK_F1 + 1}"
            VK_DELETE -> "DELETE"
            VK_OEM_PLUS, VK_ADD -> "+"
            VK_OEM_MINUS, VK_SUBTRACT -> "-"
            else -> null
        }

        if (keyName != null) {
            val sig = buildString {
                if (ctrlDown) append("CTRL+")
                if (altDown) append("ALT+")
                if (shiftDown) append("SHIFT+")
                append(keyName)
            }
            val handler = shortcutCommandHandlers[sig]
            if (handler != null) {
                runCatching { handler.invoke() }
                return true
            }
        }

        val canvasImpl = activeCanvasImpl
        val windowImpl = activeWindowImpl
        if ((canvasImpl != null || windowImpl != null) && textInput == null) {
            val mappedKey = when (vkCode) {
                VK_BACK -> "Backspace"
                VK_RETURN -> "Enter"
                VK_ESCAPE -> "Escape"
                VK_TAB -> "Tab"
                VK_SPACE -> " "
                VK_DELETE -> "Delete"
                VK_LEFT -> "ArrowLeft"
                VK_RIGHT -> "ArrowRight"
                VK_UP -> "ArrowUp"
                VK_DOWN -> "ArrowDown"
                VK_HOME -> "Home"
                VK_END -> "End"
                VK_PRIOR -> "PageUp"
                VK_NEXT -> "PageDown"
                in VK_F1..VK_F12 -> "F${vkCode - VK_F1 + 1}"
                in 0x41..0x5A -> if (!shiftDown && !ctrlDown && !altDown) vkCode.toChar().lowercaseChar().toString() else vkCode.toChar().toString()
                in 0x30..0x39 -> vkCode.toChar().toString()
                else -> if (ctrlDown || altDown) keyName else null
            }
            if (mappedKey != null) {
                val keyEv = KeyEvent(
                    key = mappedKey,
                    code = if (vkCode == VK_SPACE) "Space" else mappedKey,
                    keyCode = vkCode,
                    shiftKey = shiftDown,
                    ctrlKey = ctrlDown,
                    altKey = altDown,
                    isTargetTextInput = false
                )
                runCatching { windowImpl?.dispatchKeyDown(keyEv) }
                if (canvasImpl != null) {
                    runCatching { canvasImpl.dispatchKeyDown(keyEv) }
                }
                InvalidateRect(hwnd, null, 0)
                return true
            }
        }
        return false
    }

    fun handleKeyUp(hwnd: HWND, vkCode: Int): Boolean {
        if (focusedTextInput != null) return false
        val canvasImpl = activeCanvasImpl
        val windowImpl = activeWindowImpl
        if (canvasImpl == null && windowImpl == null) return false
        val ctrlDown = (GetKeyState(VK_CONTROL).toInt() and 0x8000) != 0
        val shiftDown = (GetKeyState(VK_SHIFT).toInt() and 0x8000) != 0
        val altDown = (GetKeyState(VK_MENU).toInt() and 0x8000) != 0
        val mappedKey = when (vkCode) {
            VK_BACK -> "Backspace"
            VK_RETURN -> "Enter"
            VK_ESCAPE -> "Escape"
            VK_TAB -> "Tab"
            VK_SPACE -> " "
            VK_DELETE -> "Delete"
            VK_LEFT -> "ArrowLeft"
            VK_RIGHT -> "ArrowRight"
            VK_UP -> "ArrowUp"
            VK_DOWN -> "ArrowDown"
            VK_HOME -> "Home"
            VK_END -> "End"
            VK_PRIOR -> "PageUp"
            VK_NEXT -> "PageDown"
            in 0x41..0x5A -> if (!shiftDown && !ctrlDown && !altDown) vkCode.toChar().lowercaseChar().toString() else vkCode.toChar().toString()
            in 0x30..0x39 -> vkCode.toChar().toString()
            in VK_F1..VK_F12 -> "F${vkCode - VK_F1 + 1}"
            else -> null
        } ?: return false
        val keyEv = KeyEvent(
            key = mappedKey,
            code = if (vkCode == VK_SPACE) "Space" else mappedKey,
            keyCode = vkCode,
            shiftKey = shiftDown,
            ctrlKey = ctrlDown,
            altKey = altDown,
            isTargetTextInput = false
        )
        runCatching { windowImpl?.dispatchKeyUp(keyEv) }
        if (canvasImpl != null) {
            runCatching { canvasImpl.dispatchKeyUp(keyEv) }
        }
        InvalidateRect(hwnd, null, 0)
        return true
    }

    fun handleCharInput(hwnd: HWND, charCode: Int): Boolean {
        if (charCode < 32 || charCode == 127) return false
        val ctrlDown = (GetKeyState(VK_CONTROL).toInt() and 0x8000) != 0
        if (ctrlDown) return false
        val ch = charCode.toChar()
        val textInput = focusedTextInput
        if (textInput != null) {
            textInput.setValue(textInput.getValue() + ch)
            invalidateAllWindows()
            return true
        }
        val canvasImpl = activeCanvasImpl
        if (canvasImpl != null) {
            val shiftDown = (GetKeyState(VK_SHIFT).toInt() and 0x8000) != 0
            val altDown = (GetKeyState(VK_MENU).toInt() and 0x8000) != 0
            val keyStr = ch.toString()
            runCatching {
                canvasImpl.dispatchKeyDown(
                    KeyEvent(
                        key = keyStr,
                        code = keyStr,
                        keyCode = charCode,
                        shiftKey = shiftDown,
                        ctrlKey = false,
                        altKey = altDown
                    )
                )
            }
            InvalidateRect(hwnd, null, 0)
            return true
        }
        return false
    }

    private var cachedDpiScaleHwndKey: Long = 0L
    private var cachedDpiScaleValue: Float = 1f

    fun resolveMinWindowTrackSize(hwnd: HWND? = activeHwnd): Pair<Int, Int> {
        val winImpl = activeWindowImpl
        val minSize = winImpl?.getMinWindowSize()
        val attrMinW = (winImpl?.nativeWidget?.attributes?.get("minWidthPx") as? Number)?.toInt() ?: 0
        val attrMinH = (winImpl?.nativeWidget?.attributes?.get("minHeightPx") as? Number)?.toInt() ?: 0
        val rawMinW = maxOf(320, minSize?.width ?: 0, attrMinW)
        val rawMinH = maxOf(240, minSize?.height ?: 0, attrMinH)
        val dpiScale = if (hwnd != null) {
            val hwndKey = hwnd.toLong()
            if (hwndKey == cachedDpiScaleHwndKey) {
                cachedDpiScaleValue
            } else {
                val hdc = GetDC(hwnd)
                val resolved = if (hdc != null) {
                    val dpiX = GetDeviceCaps(hdc, LOGPIXELSX)
                    ReleaseDC(hwnd, hdc)
                    if (dpiX > 96) dpiX.toFloat() / 96f else 1f
                } else 1f
                cachedDpiScaleHwndKey = hwndKey
                cachedDpiScaleValue = resolved
                resolved
            }
        } else 1f
        return (rawMinW * dpiScale).roundToInt() to (rawMinH * dpiScale).roundToInt()
    }

    fun handleWindowResize(hwnd: HWND) = memScoped {
        if (hwnd != activeHwnd) return@memScoped
        val rect = alloc<RECT>()
        if (GetClientRect(hwnd, rect.ptr) != 0) {
            val w = (rect.right - rect.left).coerceAtLeast(1)
            val h = (rect.bottom - rect.top).coerceAtLeast(1)
            runCatching { activeWindowImpl?.setWindowSize(Size(w, h)) }
        }
    }

    fun updateCursorForWindow(hwnd: HWND, forceSet: Boolean = true): Boolean {
        val hit = hoveredHitRegion
        val targetWidget = hit?.widget ?: hoveredWidget
        val targetImpl = targetWidget?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>
        val customCursor = (targetWidget?.attributes?.get("customCursor") as? CssCustomCursor)
            ?: (targetImpl?.getCustomCursor() as? CssCustomCursor)
            ?: if (
                hwnd == activeHwnd &&
                hoverMouseX in canvasViewportRect[0]..canvasViewportRect[2] &&
                hoverMouseY in canvasViewportRect[1]..canvasViewportRect[3]
            ) {
                (activeCanvasImpl?.nativeWidget?.attributes?.get("customCursor") as? CssCustomCursor)
                    ?: (activeCanvasImpl?.getCustomCursor() as? CssCustomCursor)
            } else {
                null
            }

        if (customCursor != null) {
            val hCustom = WindowsCurCodec.createWin32Cursor(customCursor)
            if (hCustom != null) {
                lastCursorHwndKey = 0L
                SetCursor(hCustom)
                return true
            }
        }

        val hitCursor = (targetWidget?.attributes?.get("cursor") as? String)?.lowercase()
            ?: targetWidget?.styleProperties?.get("cursor")?.lowercase()
            ?: customCursor?.fallback?.cssValue
        val canvasCursor = (activeCanvasImpl?.nativeWidget?.attributes?.get("cursor") as? String)?.lowercase()
        if (hitCursor == "none") {
            lastCursorHwndKey = 0L
            SetCursor(null)
            return true
        }
        val cursorId = when {
            targetWidget != null && (!targetWidget.enabled || targetImpl?.isEnabled() == false) -> IDC_NO
            hit?.isTextInput == true || hitCursor == "text" -> IDC_IBEAM
            hitCursor == "crosshair" -> IDC_CROSS
            hitCursor == "pointer" || hitCursor == "grab" || hitCursor == "grabbing" -> IDC_HAND
            hitCursor == "wait" -> IDC_WAIT
            hitCursor == "progress" -> IDC_APPSTARTING
            hitCursor == "help" -> IDC_HELP
            hitCursor == "move" -> IDC_SIZEALL
            hitCursor == "not-allowed" || hitCursor == "no-drop" -> IDC_NO
            hitCursor == "ew-resize" || hitCursor == "col-resize" || hitCursor == "e-resize" || hitCursor == "w-resize" -> IDC_SIZEWE
            hitCursor == "ns-resize" || hitCursor == "row-resize" || hitCursor == "n-resize" || hitCursor == "s-resize" -> IDC_SIZENS
            hitCursor == "nwse-resize" || hitCursor == "nw-resize" || hitCursor == "se-resize" -> IDC_SIZENWSE
            hitCursor == "nesw-resize" || hitCursor == "ne-resize" || hitCursor == "sw-resize" -> IDC_SIZENESW
            hit != null && (targetWidget == null || (targetWidget.enabled && targetImpl?.isEnabled() != false)) &&
                (hit.onClick != null || hit.onDrag != null || hit.onRelease != null ||
                    hitCursor == "pointer" ||
                    targetImpl is DesktopButtonNativeImpl<*> ||
                    targetImpl is DesktopTabNativeImpl<*> ||
                    targetImpl is DesktopSelectNativeImpl<*> ||
                    targetImpl is DesktopCheckboxNativeImpl<*>) -> IDC_HAND
            hwnd == activeHwnd &&
                canvasCursor == "crosshair" &&
                hoverMouseX in canvasViewportRect[0]..canvasViewportRect[2] &&
                hoverMouseY in canvasViewportRect[1]..canvasViewportRect[3] -> IDC_CROSS
            else -> IDC_ARROW
        }
        val hwndKey = hwnd.toLong()
        val idKey = cursorId.toLong()
        if (forceSet || hwndKey != lastCursorHwndKey || idKey != lastCursorIdKey) {
            lastCursorHwndKey = hwndKey
            lastCursorIdKey = idKey
            SetCursor(LoadCursorW(null, cursorId))
        }
        return true
    }

    internal fun setWidgetFocused(newFocused: DesktopNativeWidget?) {
        val effectiveFocused = if (newFocused?.enabled == false) null else newFocused
        val prevFocused = focusedWidget
        if (prevFocused !== effectiveFocused) {
            val prevTextInput = focusedTextInput
            (prevFocused?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>)?.setFocusedState(false)
            (effectiveFocused?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>)?.setFocusedState(true)
            focusedWidget = effectiveFocused
            focusedTextInput = effectiveFocused?.attributes?.get("nativeImpl") as? DesktopTextInputNativeImpl<*>
            com.nexustools.genericui.event.InputFocusManager.isNativeTextInputFocused = (focusedTextInput != null)
            if (prevTextInput != null && prevTextInput !== focusedTextInput) {
                runCatching { prevTextInput.submitValue() }
            }
        }
    }

    private var cardPageScrollY: Int = 0
    private var cardPageMaxScrollY: Int = 0
    private var cardPageLastKey: String = ""
    private var cardPageLastScrollRequestVersion: Long = -1L
    private var activeCardPageScrollViewImpl: DesktopScrollViewNativeImpl<*>? = null
    private var isDraggingCardScrollbarThumb: Boolean = false
    private var cardScrollbarDragGrabOffsetY: Int = 0
    private var cardPageScrollRailRect: IntArray = intArrayOf(-1, -1, -1, -1)
    private var cardPageScrollThumbRect: IntArray = intArrayOf(-1, -1, -1, -1)
    private val cardScrollbarThumbWidget = DesktopNativeWidget("Win32", "scrollbar-thumb")
    private val cardScrollbarRailWidget = DesktopNativeWidget("Win32", "scrollbar-rail")
    private val cardScrollbarUpBtnWidget = DesktopNativeWidget("Win32", "scrollbar-up")
    private val cardScrollbarDownBtnWidget = DesktopNativeWidget("Win32", "scrollbar-down")
    private val lazyGridScrollbarThumbWidget = DesktopNativeWidget("Win32", "lazygrid-scrollbar-thumb")
    private val lazyGridScrollbarRailWidget = DesktopNativeWidget("Win32", "lazygrid-scrollbar-rail")
    private var isDraggingLazyGridScrollbarThumb: Boolean = false
    private var lazyGridScrollbarDragGrabOffsetY: Int = 0

    fun getCardPageScrollY(): Int = cardPageScrollY
    fun getCardPageMaxScrollY(): Int = cardPageMaxScrollY
    fun getCardPageScrollThumbBounds(): IntArray = cardPageScrollThumbRect.copyOf()
    fun getCardPageScrollRailBounds(): IntArray = cardPageScrollRailRect.copyOf()
    fun isCardScrollbarThumbHovered(): Boolean =
        isDraggingCardScrollbarThumb ||
            hoveredWidget === cardScrollbarThumbWidget ||
            (cardPageScrollThumbRect[2] > cardPageScrollThumbRect[0] &&
                hoverMouseX in cardPageScrollThumbRect[0]..cardPageScrollThumbRect[2] &&
                hoverMouseY in cardPageScrollThumbRect[1]..cardPageScrollThumbRect[3])
    fun isCardScrollbarThumbDragging(): Boolean = isDraggingCardScrollbarThumb

    fun handleMouseWheel(hwnd: HWND, wheelDelta: Int) {
        if (Win32GeneralComponentTreeRenderer.handleMouseWheel(hwnd, hoverMouseX, hoverMouseY, wheelDelta)) {
            InvalidateRect(hwnd, null, 0)
            UpdateWindow(hwnd)
            return
        }
        val grid = activeLazyGridImpl
        if (grid != null) {
            val totalDataRows = (grid.getTotalRowCount() - 1).coerceAtLeast(0)
            if (totalDataRows <= 0) return
            val rawRows = (-wheelDelta * 3) / 120
            val step = when {
                rawRows != 0 -> rawRows
                wheelDelta > 0 -> -2
                else -> 2
            }
            val maxFirst = (totalDataRows - 4).coerceAtLeast(0)
            val next = (grid.firstVisibleDataRow + step).coerceIn(0, maxFirst)
            if (next != grid.firstVisibleDataRow) {
                grid.firstVisibleDataRow = next
                InvalidateRect(hwnd, null, 0)
                UpdateWindow(hwnd)
            }
            return
        }
        if (cardPageMaxScrollY > 0) {
            val rawLines = (-wheelDelta * 96) / 120
            val step = when {
                rawLines != 0 -> rawLines
                wheelDelta > 0 -> -96
                else -> 96
            }
            val next = (cardPageScrollY + step).coerceIn(0, cardPageMaxScrollY)
            if (next != cardPageScrollY) {
                cardPageScrollY = next
                activeCardPageScrollViewImpl?.updateScrollPositionFromHost(0, next)
                InvalidateRect(hwnd, null, 0)
                UpdateWindow(hwnd)
            }
            return
        }
        val canvasImpl = activeCanvasImpl
        if (canvasImpl != null && hwnd == activeHwnd) {
            val (cx, cy) = mapWindowPointToCanvas(hoverMouseX, hoverMouseY)
            val deltaY = if (wheelDelta > 0) -48f else 48f
            runCatching {
                canvasImpl.dispatchWheel(
                    WheelEvent(
                        x = cx,
                        y = cy,
                        deltaX = 0f,
                        deltaY = deltaY
                    )
                )
            }
            InvalidateRect(hwnd, null, 0)
            UpdateWindow(hwnd)
        }
    }

    fun handleMouseDown(hwnd: HWND, x: Int, y: Int) {
        mouseDownX = x
        mouseDownY = y
        dragMouseMovedSinceDown = false
        if (hwnd == activeHwnd) {
            val activeModalDialog = DesktopDialogWindowNativeImpl.activeDialogs
                .lastOrNull { it.isModal && it.nativeWidget.visible && !it.nativeWidget.disposed }
            if (activeModalDialog != null && dialogHwnds[activeModalDialog] != hwnd) {
                if (activeModalDialog.dismissOnBackdropClick()) {
                    syncActiveDialogWindows()
                    invalidateAllWindows()
                    UpdateWindow(hwnd)
                }
                return
            }
        }
        val regions = hitRegionsByHwnd[hwnd.toLong()].orEmpty()
        val hit = regions.lastOrNull { it.contains(x, y) }
        if (DesktopNativeWidget.interceptPopupPointerDown(x.toFloat(), y.toFloat(), hit?.widget)) {
            syncMultiWindowDocks()
            syncActiveDialogWindows()
            if (hwnd == activeHwnd) {
                markVideoStageDirty(hwnd)
            }
            invalidateAllWindows()
            UpdateWindow(hwnd)
            return
        }
        if (hit != null && hit.widget?.enabled == false) {
            SetFocus(hwnd)
            setWidgetFocused(null)
            InvalidateRect(hwnd, null, 0)
            return
        }
        val hitEditEntry = if (hit?.widget != null) nativeEditEntriesByWidget[hwnd.toLong() to hit.widget] else null
        val hitEditHwnd = hitEditEntry?.childHwnd
        if (hitEditEntry != null && hitEditEntry.visible && hitEditHwnd != null && IsWindow(hitEditHwnd) != 0) {
            SetFocus(hitEditHwnd)
        } else {
            SetFocus(hwnd)
        }

        val prevFocused = focusedWidget
        setWidgetFocused(hit?.widget)

        if (hwnd == activeHwnd && isPointInVideoStage(x, y)) {
            lastVideoStageMouseMoveTickMs = GetTickCount64().toLong()
            isPointerInVideoStage = true
            isVideoStageHudAutoHidden = false
            overlayHudTargetAlpha = 1f
            if (lastOverlayFadeTickMs == 0.0) {
                overlayHudCurrentAlpha = 1f
            }
            val stageContainer = Win32GeneralComponentTreeRenderer.cachedOverlayWidget?.parentWidget
                ?: Win32GeneralComponentTreeRenderer.cachedVideoWidget?.parentWidget
            if (stageContainer != null) {
                val stageDownEv = PointerEvent(
                    x = (x - lastRenderedVideoStageRect[0]).toFloat(),
                    y = (y - lastRenderedVideoStageRect[1]).toFloat(),
                    screenX = x.toFloat(),
                    screenY = y.toFloat(),
                    button = 0,
                    buttonsMask = 1
                )
                for (child in stageContainer.children) {
                    if (child !== hit?.widget && isOverlayOrStageSubtree(child)) {
                        val childImpl = child.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
                        runCatching { childImpl?.dispatchPointerDown(stageDownEv) }
                    }
                }
            }
        }

        if (hit != null) {
            pressedWidget = hit.widget
            hit.widget?.bounds = Rect(
                x = hit.left,
                y = hit.top,
                width = max(1, hit.right - hit.left),
                height = max(1, hit.bottom - hit.top)
            )
            val baseImpl = pressedWidget?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>
            baseImpl?.setActivePressedState(true)
            val downEv = PointerEvent(
                x = (x - hit.left).toFloat(),
                y = (y - hit.top).toFloat(),
                screenX = x.toFloat(),
                screenY = y.toFloat(),
                button = 0,
                buttonsMask = 1
            )
            runCatching { baseImpl?.dispatchPointerDown(downEv) }
            runCatching { hit.onClick?.invoke(x, y) }
            if (hit.onClick == null) {
                runCatching { hit.onDrag?.invoke(x, y) }
            }
            if (hit.onDrag != null || hit.onRelease != null || PointerDragManager.isDragging) {
                activeDragRegion = hit
                SetCapture(hwnd)
            }
            syncMultiWindowDocks()
            syncActiveDialogWindows()
            if (hwnd == activeHwnd && isHitRegionInVideoStage(hit) && (prevFocused == null || isOverlayOrStageSubtree(prevFocused))) {
                markVideoStageDirty(hwnd)
            } else {
                invalidateAllWindows()
            }
            UpdateWindow(hwnd)
            return
        }

        if (hwnd == activeHwnd) {
            val cLeft = canvasViewportRect[0]
            val cTop = canvasViewportRect[1]
            val cRight = canvasViewportRect[2]
            val cBottom = canvasViewportRect[3]
            if (x in cLeft..cRight && y in cTop..cBottom) {
                val canvasImpl = activeCanvasImpl
                if (canvasImpl != null) {
                    isDraggingCanvas = true
                    SetCapture(hwnd)
                    val (cx, cy) = mapWindowPointToCanvas(x, y)
                    runCatching {
                        canvasImpl.dispatchPointerDown(
                            PointerEvent(
                                x = cx,
                                y = cy,
                                button = 0
                            )
                        )
                    }
                    InvalidateRect(hwnd, null, 0)
                    UpdateWindow(hwnd)
                    return
                }
            }
        }
        InvalidateRect(hwnd, null, 0)
    }

    fun handleMouseMove(hwnd: HWND, x: Int, y: Int) {
        val hwndLong = hwnd.toLong()
        if (hoverHwndKey == hwndLong && x == hoverMouseX && y == hoverMouseY && !isOverlayDragActive() && !isDraggingCanvas) {
            return
        }
        hoverHwndKey = hwndLong
        hoverMouseX = x
        hoverMouseY = y
        if (mouseLeaveTrackInstalledForHwnd != hwndLong) {
            mouseLeaveTrackInstalledForHwnd = hwndLong
            memScoped {
                val tme = alloc<TRACKMOUSEEVENT>()
                tme.cbSize = sizeOf<TRACKMOUSEEVENT>().toUInt()
                tme.dwFlags = TME_LEAVE.toUInt()
                tme.hwndTrack = hwnd
                tme.dwHoverTime = 0u
                TrackMouseEvent(tme.ptr)
            }
        }
        val drag = activeDragRegion
        val isGlobalDragActive = drag != null || PointerDragManager.isDragging
        val inVideoStageNow = hwnd == activeHwnd && isPointInVideoStage(x, y)
        if (hwnd == activeHwnd && lastRenderedVideoStageRect[2] > lastRenderedVideoStageRect[0]) {
            if (inVideoStageNow || isGlobalDragActive) {
                val wasInStage = isPointerInVideoStage
                val wasHidden = isVideoStageHudAutoHidden
                lastVideoStageMouseMoveTickMs = GetTickCount64().toLong()
                isPointerInVideoStage = true
                overlayHudTargetAlpha = 1f
                val stageContainer = Win32GeneralComponentTreeRenderer.cachedOverlayWidget?.parentWidget
                    ?: Win32GeneralComponentTreeRenderer.cachedVideoWidget?.parentWidget
                if (stageContainer != null) {
                    val stageMoveEv = PointerEvent(
                        x = (x - lastRenderedVideoStageRect[0]).toFloat(),
                        y = (y - lastRenderedVideoStageRect[1]).toFloat(),
                        screenX = x.toFloat(),
                        screenY = y.toFloat(),
                        button = -1,
                        buttonsMask = 0
                    )
                    for (child in stageContainer.children) {
                        if (isOverlayOrStageSubtree(child)) {
                            val childImpl = child.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
                            runCatching { childImpl?.dispatchPointerMove(stageMoveEv) }
                        }
                    }
                }
                if (!wasInStage || wasHidden || overlayHudCurrentAlpha < 1f) {
                    isVideoStageHudAutoHidden = false
                    if (lastOverlayFadeTickMs == 0.0) {
                        overlayHudCurrentAlpha = 1f
                    }
                    markVideoStageDirty(hwnd)
                }
            } else if (isPointerInVideoStage) {
                isPointerInVideoStage = false
                if (!com.nexustools.genericui.event.PopupMouseCaptureManager.hasActivePopup) {
                    overlayHudTargetAlpha = 0f
                    if (lastOverlayFadeTickMs == 0.0) {
                        overlayHudCurrentAlpha = 0f
                    }
                }
                markVideoStageDirty(hwnd)
            }
        }
        if (isDraggingCanvas && hwnd == activeHwnd) {
            val canvasImpl = activeCanvasImpl
            if (canvasImpl != null) {
                val (cx, cy) = mapWindowPointToCanvas(x, y)
                runCatching {
                    canvasImpl.dispatchPointerMove(
                        PointerEvent(
                            x = cx,
                            y = cy,
                            screenX = x.toFloat(),
                            screenY = y.toFloat(),
                            button = 0,
                            buttonsMask = 1
                        )
                    )
                }
                InvalidateRect(hwnd, null, 0)
                return
            }
        }
        if (PointerDragManager.isDragging) {
            runCatching {
                PointerDragManager.notifyGlobalPointerMove(
                    PointerEvent(
                        x = x.toFloat(),
                        y = y.toFloat(),
                        screenX = x.toFloat(),
                        screenY = y.toFloat(),
                        button = 0,
                        buttonsMask = 1
                    )
                )
            }
        }
        if (x != mouseDownX || y != mouseDownY) {
            dragMouseMovedSinceDown = true
        }
        if (drag != null) {
            if (dragMouseMovedSinceDown) {
                runCatching { drag.onDrag?.invoke(x, y) }
            }
            val dragImpl = drag.widget?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>
            if (dragImpl != null) {
                runCatching {
                    dragImpl.dispatchPointerMove(
                        PointerEvent(
                            x = (x - drag.left).toFloat(),
                            y = (y - drag.top).toFloat(),
                            screenX = x.toFloat(),
                            screenY = y.toFloat(),
                            button = 0,
                            buttonsMask = 1
                        )
                    )
                }
            }
            if (hwnd == activeHwnd && (inVideoStageNow || isHitRegionInVideoStage(drag))) {
                markVideoStageDirty(hwnd)
            } else {
                InvalidateRect(hwnd, null, 0)
                UpdateWindow(hwnd)
            }
            return
        }
        if (PointerDragManager.isDragging) {
            if (hwnd == activeHwnd && inVideoStageNow) {
                markVideoStageDirty(hwnd)
            } else {
                InvalidateRect(hwnd, null, 0)
                UpdateWindow(hwnd)
            }
            return
        }
        val regions = hitRegionsByHwnd[hwndLong].orEmpty()
        val hit = regions.lastOrNull { it.contains(x, y) }
        val prevHit = hoveredHitRegion
        val prevWidget = hoveredWidget
        val newWidget = hit?.widget
        hoveredHitRegion = hit

        val hitSliderImpl = newWidget?.attributes?.get("nativeImpl") as? DesktopRangeSliderNativeImpl<*>
        if (hit != null && hitSliderImpl != null) {
            runCatching {
                hitSliderImpl.dispatchPointerMove(
                    PointerEvent(
                        x = (x - hit.left).toFloat(),
                        y = (y - hit.top).toFloat(),
                        screenX = x.toFloat(),
                        screenY = y.toFloat(),
                        button = -1,
                        buttonsMask = 0
                    )
                )
            }
        }

        if (hit === prevHit && prevWidget === newWidget) {
            updateCursorForWindow(hwnd, forceSet = false)
            if (hitSliderImpl != null && hwnd == activeHwnd && (inVideoStageNow || isHitRegionInVideoStage(hit))) {
                markVideoStageDirty(hwnd)
            }
            return
        }

        if (prevWidget !== newWidget) {
            val prevChain = buildAncestorWidgetChain(prevWidget)
            val newChain = buildAncestorWidgetChain(newWidget)
            val pointerEv = PointerEvent(x = x.toFloat(), y = y.toFloat())
            for (exited in prevChain) {
                if (exited !in newChain) {
                    val exitedImpl = exited.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
                    exitedImpl?.setHoveredState(false)
                    exitedImpl?.dispatchPointerLeave(pointerEv)
                }
            }
            for (entered in newChain.asReversed()) {
                if (entered !in prevChain && entered.enabled) {
                    val enteredImpl = entered.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
                    enteredImpl?.setHoveredState(true)
                    enteredImpl?.dispatchPointerEnter(pointerEv)
                }
            }
            hoveredWidget = if (newWidget?.enabled == false) null else newWidget
        }
        updateCursorForWindow(hwnd, forceSet = false)

        val newTooltip = hit?.tooltip?.takeIf { it.isNotBlank() }
        if (newTooltip != hoveredTooltip || prevHit !== hit || prevWidget !== newWidget) {
            val tooltipChanged = newTooltip != hoveredTooltip
            hoveredTooltip = newTooltip
            val prevInVideoStage = isHitRegionInVideoStage(prevHit) || (prevHit == null && inVideoStageNow)
            val nextInVideoStage = inVideoStageNow && (hit == null || isHitRegionInVideoStage(hit) || isOverlayOrStageSubtree(newWidget))
            val bothInVideoStage = !tooltipChanged &&
                hwnd == activeHwnd &&
                lastRenderedVideoStageRect[2] > lastRenderedVideoStageRect[0] &&
                prevInVideoStage &&
                nextInVideoStage &&
                (inVideoStageNow || prevHit != null || hit != null)
            if (bothInVideoStage) {
                markVideoStageDirty(hwnd)
            } else {
                if (isVBlankVideoStageOnlyRepaint) {
                    lastPaintedNonOverlayVersion = -1L
                    isVBlankVideoStageOnlyRepaint = false
                }
                InvalidateRect(hwnd, null, 0)
            }
        }

        if (hit == null && hwnd == activeHwnd) {
            val cLeft = canvasViewportRect[0]
            val cTop = canvasViewportRect[1]
            val cRight = canvasViewportRect[2]
            val cBottom = canvasViewportRect[3]
            if (x in cLeft..cRight && y in cTop..cBottom) {
                val canvasImpl = activeCanvasImpl
                if (canvasImpl != null) {
                    val (cx, cy) = mapWindowPointToCanvas(x, y)
                    runCatching {
                        canvasImpl.dispatchPointerMove(
                            PointerEvent(
                                x = cx,
                                y = cy,
                                button = -1
                            )
                        )
                    }
                    if (inVideoStageNow) {
                        markVideoStageDirty(hwnd)
                    } else {
                        InvalidateRect(hwnd, null, 0)
                    }
                }
            }
        }
    }

    fun handleMouseUp(hwnd: HWND, x: Int, y: Int) {
        if (DesktopNativeWidget.interceptPopupPointerUp(x.toFloat(), y.toFloat(), null)) {
            DesktopNativeWidget.interceptPopupClick(x.toFloat(), y.toFloat(), null)
            isDraggingCardScrollbarThumb = false
            isDraggingLazyGridScrollbarThumb = false
            activeDragRegion = null
            pressedWidget?.let {
                (it.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>)?.setActivePressedState(false)
            }
            pressedWidget = null
            return
        }
        val wasScrollbarDragging = isDraggingCardScrollbarThumb || isDraggingLazyGridScrollbarThumb
        isDraggingCardScrollbarThumb = false
        isDraggingLazyGridScrollbarThumb = false
        val drag = activeDragRegion
        activeDragRegion = null
        val pressed = pressedWidget
        pressedWidget = null

        if (pressed != null) {
            (pressed.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>)?.setActivePressedState(false)
        }
        if (drag != null) {
            val isSliderDrag = drag.widget?.attributes?.get("nativeImpl") is DesktopRangeSliderNativeImpl<*>
            val relX = if (isSliderDrag && !dragMouseMovedSinceDown) mouseDownX else x
            val relY = if (isSliderDrag && !dragMouseMovedSinceDown) mouseDownY else y
            runCatching { drag.onRelease?.invoke(relX, relY) }
            val dragImpl = drag.widget?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>
            if (dragImpl != null) {
                runCatching {
                    dragImpl.dispatchPointerUp(
                        PointerEvent(
                            x = (x - drag.left).toFloat(),
                            y = (y - drag.top).toFloat(),
                            screenX = x.toFloat(),
                            screenY = y.toFloat(),
                            button = 0,
                            buttonsMask = 0
                        )
                    )
                }
            }
        } else if (pressed != null) {
            val pressedImpl = pressed.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
            if (pressedImpl != null) {
                runCatching {
                    pressedImpl.dispatchPointerUp(
                        PointerEvent(
                            x = x.toFloat(),
                            y = y.toFloat(),
                            screenX = x.toFloat(),
                            screenY = y.toFloat(),
                            button = 0,
                            buttonsMask = 0
                        )
                    )
                }
            }
        }
        val hadGlobalDrag = PointerDragManager.isDragging
        if (hadGlobalDrag) {
            runCatching {
                PointerDragManager.notifyGlobalPointerUp(
                    PointerEvent(
                        x = x.toFloat(),
                        y = y.toFloat(),
                        screenX = x.toFloat(),
                        screenY = y.toFloat(),
                        button = 0,
                        buttonsMask = 0
                    )
                )
            }
        }
        if (isDraggingCanvas) {
            isDraggingCanvas = false
            val canvasImpl = activeCanvasImpl
            if (canvasImpl != null) {
                val (cx, cy) = mapWindowPointToCanvas(x, y)
                runCatching {
                    canvasImpl.dispatchPointerUp(
                        PointerEvent(
                            x = cx,
                            y = cy,
                            screenX = x.toFloat(),
                            screenY = y.toFloat(),
                            button = 0,
                            buttonsMask = 0
                        )
                    )
                }
            }
        }
        if (pressed != null || drag != null || hadGlobalDrag || wasScrollbarDragging) {
            if (hwnd == activeHwnd &&
                !wasScrollbarDragging &&
                (drag == null || isHitRegionInVideoStage(drag)) &&
                (pressed == null || isOverlayOrStageSubtree(pressed)) &&
                (!hadGlobalDrag || isPointInVideoStage(x, y))
            ) {
                markVideoStageDirty(hwnd)
            } else {
                InvalidateRect(hwnd, null, 0)
            }
            UpdateWindow(hwnd)
        }
    }

    private fun mapWindowPointToCanvas(winX: Int, winY: Int): Pair<Float, Float> {
        val cLeft = canvasViewportRect[0]
        val cTop = canvasViewportRect[1]
        val cW = max(1, canvasViewportRect[2] - cLeft)
        val cH = max(1, canvasViewportRect[3] - cTop)
        val (surfW, surfH) = activeCanvasSurfaceSize
        val normX = (winX - cLeft).toFloat() / cW.toFloat()
        val normY = (winY - cTop).toFloat() / cH.toFloat()
        return (normX * surfW.toFloat()) to (normY * surfH.toFloat())
    }

    internal fun openSelectPopupMenu(hwnd: HWND, selectImpl: DesktopSelectNativeImpl<*>, x: Int, y: Int) = memScoped {
        val options = selectImpl.getOptions()
        if (options.isEmpty()) return@memScoped
        val hPopup = CreatePopupMenu() ?: return@memScoped
        val current = selectImpl.getSelectedValue()
        options.forEachIndexed { idx, (value, label) ->
            val flags = if (value == current) (MF_STRING or MF_CHECKED).toUInt() else MF_STRING.toUInt()
            AppendMenuW(hPopup, flags, (5000 + idx).toULong(), label.ifBlank { value })
        }
        val pt = alloc<POINT>()
        pt.x = x
        pt.y = y
        ClientToScreen(hwnd, pt.ptr)
        val chosen = TrackPopupMenu(
            hPopup,
            (TPM_RETURNCMD or TPM_NONOTIFY or TPM_LEFTALIGN or TPM_TOPALIGN).toUInt(),
            pt.x,
            pt.y,
            0,
            hwnd,
            null
        )
        DestroyMenu(hPopup)
        if (chosen >= 5000) {
            val optIndex = chosen - 5000
            options.getOrNull(optIndex)?.let { (valStr, _) ->
                selectImpl.notifyUserSelectionChanged(valStr)
            }
        }
    }

    private fun ensureSharedFonts() {
        if (cachedFontSmall == null) cachedFontSmall = createSegoeFont(13, FW_NORMAL)
        if (cachedFontSmallBold == null) cachedFontSmallBold = createSegoeFont(13, FW_SEMIBOLD)
        if (cachedFontNormal == null) cachedFontNormal = createSegoeFont(15, FW_NORMAL)
        if (cachedFontBold == null) cachedFontBold = createSegoeFont(15, FW_SEMIBOLD)
        if (cachedFontTitle == null) cachedFontTitle = createSegoeFont(19, FW_BOLD)
        if (cachedFontMetricBig == null) cachedFontMetricBig = createSegoeFont(26, FW_BOLD)
    }

    private fun obtainMainBackBufferDc(hdc: HDC?, hwndKey: Long, width: Int, height: Int): HDC? = memScoped {
        if (cachedMainMemDc != null &&
            cachedMainMemBmp != null &&
            cachedMainBackBufferPixels != null &&
            cachedMainBufferHwndKey == hwndKey &&
            width <= cachedMainBufferW &&
            height <= cachedMainBufferH
        ) {
            return@memScoped cachedMainMemDc
        }
        releaseMainBackBufferDc()
        val memDc = CreateCompatibleDC(hdc) ?: return@memScoped null
        val bmi = alloc<BITMAPINFO>()
        bmi.bmiHeader.biSize = sizeOf<BITMAPINFOHEADER>().toUInt()
        bmi.bmiHeader.biWidth = width
        bmi.bmiHeader.biHeight = -height
        bmi.bmiHeader.biPlanes = 1u
        bmi.bmiHeader.biBitCount = 32u
        bmi.bmiHeader.biCompression = BI_RGB.toUInt()
        val bitsPtr = alloc<COpaquePointerVar>()
        val memBmp = CreateDIBSection(hdc, bmi.ptr, DIB_RGB_COLORS.toUInt(), bitsPtr.ptr, null, 0u)
        if (memBmp == null || bitsPtr.value == null) {
            if (memBmp != null) DeleteObject(memBmp)
            DeleteDC(memDc)
            return@memScoped null
        }
        cachedMainOldBmp = SelectObject(memDc, memBmp)
        cachedMainMemDc = memDc
        cachedMainMemBmp = memBmp
        cachedMainBackBufferPixels = bitsPtr.value?.reinterpret()
        cachedMainBackBufferWidth = width
        cachedMainBackBufferHeight = height
        cachedMainBufferW = width
        cachedMainBufferH = height
        cachedMainBufferHwndKey = hwndKey
        lastPaintedNonOverlayVersion = -1L
        memDc
    }

    private fun releaseMainBackBufferDc() {
        val dc = cachedMainMemDc
        val bmp = cachedMainMemBmp
        val old = cachedMainOldBmp
        if (dc != null && old != null) {
            SelectObject(dc, old)
        }
        if (bmp != null) {
            DeleteObject(bmp)
        }
        if (dc != null) {
            DeleteDC(dc)
        }
        cachedMainMemDc = null
        cachedMainMemBmp = null
        cachedMainOldBmp = null
        cachedMainBackBufferPixels = null
        cachedMainBackBufferWidth = 0
        cachedMainBackBufferHeight = 0
        cachedMainBufferW = 0
        cachedMainBufferH = 0
        cachedMainBufferHwndKey = 0L
        lastPaintedNonOverlayVersion = -1L
        lastFullPaintHwndKey = 0L
        lastFullPaintRootWidget = null
        Win32GeneralComponentTreeRenderer.clearBackBufferRegionKeys()
        releaseCardGridBackBuffer()
    }

    private fun obtainCardGridBackBuffer(hdc: HDC?, width: Int, height: Int): HDC? = memScoped {
        if (cachedCardGridDc != null &&
            cachedCardGridBmp != null &&
            cachedCardGridPixels != null &&
            cachedCardGridAllocW == width &&
            cachedCardGridAllocH >= height
        ) {
            return@memScoped cachedCardGridDc
        }
        releaseCardGridBackBuffer()
        val memDc = CreateCompatibleDC(hdc) ?: return@memScoped null
        val bmi = alloc<BITMAPINFO>()
        bmi.bmiHeader.biSize = sizeOf<BITMAPINFOHEADER>().toUInt()
        bmi.bmiHeader.biWidth = width
        bmi.bmiHeader.biHeight = -height
        bmi.bmiHeader.biPlanes = 1u
        bmi.bmiHeader.biBitCount = 32u
        bmi.bmiHeader.biCompression = BI_RGB.toUInt()
        val bitsPtr = alloc<COpaquePointerVar>()
        val memBmp = CreateDIBSection(hdc, bmi.ptr, DIB_RGB_COLORS.toUInt(), bitsPtr.ptr, null, 0u)
        if (memBmp == null || bitsPtr.value == null) {
            if (memBmp != null) DeleteObject(memBmp)
            DeleteDC(memDc)
            return@memScoped null
        }
        cachedCardGridOldBmp = SelectObject(memDc, memBmp)
        cachedCardGridDc = memDc
        cachedCardGridBmp = memBmp
        cachedCardGridPixels = bitsPtr.value?.reinterpret()
        cachedCardGridAllocW = width
        cachedCardGridAllocH = height
        cachedCardGridWidget = null
        cachedCardGridMutVer = -1L
        memDc
    }

    private fun releaseCardGridBackBuffer() {
        val dc = cachedCardGridDc
        val bmp = cachedCardGridBmp
        val old = cachedCardGridOldBmp
        if (dc != null && old != null) {
            SelectObject(dc, old)
        }
        if (bmp != null) {
            DeleteObject(bmp)
        }
        if (dc != null) {
            DeleteDC(dc)
        }
        cachedCardGridDc = null
        cachedCardGridBmp = null
        cachedCardGridOldBmp = null
        cachedCardGridPixels = null
        cachedCardGridAllocW = 0
        cachedCardGridAllocH = 0
        cachedCardGridWidget = null
        cachedCardGridMutVer = -1L
        cachedCardGridW = 0
        cachedCardGridH = 0
        cachedCardPageFrameHwnd = 0L
        cachedCardPageFrameRootVer = -1L
        cachedCardPageFramePage = null
    }

    fun paintWindow(hwnd: HWND) = memScoped {
        val ps = alloc<PAINTSTRUCT>()
        val hdc = BeginPaint(hwnd, ps.ptr) ?: return@memScoped
        val clientRect = alloc<RECT>()
        GetClientRect(hwnd, clientRect.ptr)
        val clientW = max(180, clientRect.right - clientRect.left)
        val clientH = max(90, clientRect.bottom - clientRect.top)

        val isMainWin = hwnd == activeHwnd
        var tempMemDc: HDC? = null
        var tempMemBmp: HBITMAP? = null
        var tempOldBmp: HGDIOBJ? = null
        val memDc = if (isMainWin) {
            val mainDc = obtainMainBackBufferDc(hdc, hwnd.toLong(), clientW, clientH)
            activeBackBufferDc = mainDc
            activeBackBufferPixels = cachedMainBackBufferPixels
            activeBackBufferWidth = cachedMainBackBufferWidth
            activeBackBufferHeight = cachedMainBackBufferHeight
            mainDc
        } else {
            val dc = CreateCompatibleDC(hdc)
            val bmi = alloc<BITMAPINFO>()
            bmi.bmiHeader.biSize = sizeOf<BITMAPINFOHEADER>().toUInt()
            bmi.bmiHeader.biWidth = clientW
            bmi.bmiHeader.biHeight = -clientH
            bmi.bmiHeader.biPlanes = 1u
            bmi.bmiHeader.biBitCount = 32u
            bmi.bmiHeader.biCompression = BI_RGB.toUInt()
            val bitsPtr = alloc<COpaquePointerVar>()
            val bmp = CreateDIBSection(hdc, bmi.ptr, DIB_RGB_COLORS.toUInt(), bitsPtr.ptr, null, 0u)
                ?: CreateCompatibleBitmap(hdc, clientW, clientH)
            tempOldBmp = SelectObject(dc, bmp)
            tempMemDc = dc
            tempMemBmp = bmp
            activeBackBufferDc = dc
            activeBackBufferPixels = bitsPtr.value?.reinterpret()
            activeBackBufferWidth = clientW
            activeBackBufferHeight = clientH
            dc
        }

        ensureSharedFonts()
        val fontSmall = cachedFontSmall
        val fontSmallBold = cachedFontSmallBold
        val fontNormal = cachedFontNormal
        val fontBold = cachedFontBold
        val fontTitle = cachedFontTitle
        val fontMetricBig = cachedFontMetricBig

        val oldFont = SelectObject(memDc, fontNormal)
        SetBkMode(memDc, TRANSPARENT)
        resetSoftClipRect()
        resetGdiDirtyRect()
        resetFrameBufferDirtyRect()
        activeMainBackBufferPreserved = false
        currentPaintStageBreakdown = ""

        val paintQpcFreq = alloc<LARGE_INTEGER>()
        val paintQpcStart = alloc<LARGE_INTEGER>()
        val paintQpcEnd = alloc<LARGE_INTEGER>()
        QueryPerformanceFrequency(paintQpcFreq.ptr)
        QueryPerformanceCounter(paintQpcStart.ptr)
        val ticksPerUs = (paintQpcFreq.QuadPart.toDouble() / 1_000_000.0).coerceAtLeast(1.0)

        fun finishPaint() {
            QueryPerformanceCounter(paintQpcEnd.ptr)
            val preBltUs = ((paintQpcEnd.QuadPart - paintQpcStart.QuadPart).toDouble() / ticksPerUs).toLong()
            val rcL = ps.rcPaint.left.coerceIn(0, clientW)
            val rcT = ps.rcPaint.top.coerceIn(0, clientH)
            val rcR = ps.rcPaint.right.coerceIn(rcL, clientW)
            val rcB = ps.rcPaint.bottom.coerceIn(rcT, clientH)
            var actualBltW = 0
            var actualBltH = 0
            if (activeMainBackBufferPreserved && !lastFrameHadTooltipOverlay) {
                if (frameBufferDirtyRight > frameBufferDirtyLeft && frameBufferDirtyBottom > frameBufferDirtyTop) {
                    val bltL = max(rcL, frameBufferDirtyLeft).coerceIn(0, clientW)
                    val bltT = max(rcT, frameBufferDirtyTop).coerceIn(0, clientH)
                    val bltR = min(rcR, frameBufferDirtyRight).coerceIn(bltL, clientW)
                    val bltB = min(rcB, frameBufferDirtyBottom).coerceIn(bltT, clientH)
                    if (bltR > bltL && bltB > bltT) {
                        actualBltW = bltR - bltL
                        actualBltH = bltB - bltT
                        BitBlt(hdc, bltL, bltT, actualBltW, actualBltH, memDc, bltL, bltT, SRCCOPY)
                    }
                }
            } else if (rcR > rcL && rcB > rcT) {
                actualBltW = rcR - rcL
                actualBltH = rcB - rcT
                BitBlt(hdc, rcL, rcT, actualBltW, actualBltH, memDc, rcL, rcT, SRCCOPY)
            } else {
                actualBltW = clientW
                actualBltH = clientH
                BitBlt(hdc, 0, 0, clientW, clientH, memDc, 0, 0, SRCCOPY)
            }
            QueryPerformanceCounter(paintQpcEnd.ptr)
            val postBltUs = ((paintQpcEnd.QuadPart - paintQpcStart.QuadPart).toDouble() / ticksPerUs).toLong()
            val preservedFlag = activeMainBackBufferPreserved
            activeMainBackBufferPreserved = false
            SelectObject(memDc, oldFont)
            if (tempMemDc != null) {
                SelectObject(tempMemDc, tempOldBmp)
                if (tempMemBmp != null) DeleteObject(tempMemBmp)
                DeleteDC(tempMemDc)
            }
            activeBackBufferDc = cachedMainMemDc
            activeBackBufferPixels = cachedMainBackBufferPixels
            activeBackBufferWidth = cachedMainBackBufferWidth
            activeBackBufferHeight = cachedMainBackBufferHeight
            if (isMainWin) {
                lastPaintedNonOverlayVersion = DesktopNativeWidget.globalNonOverlayMutationVersion
                isVBlankVideoStageOnlyRepaint = false
            }
            EndPaint(hwnd, ps.ptr)
            QueryPerformanceCounter(paintQpcEnd.ptr)
            val postEndPaintUs = ((paintQpcEnd.QuadPart - paintQpcStart.QuadPart).toDouble() / ticksPerUs).toLong()
            runCatching { endPaintFrameNativeControls(hwnd) }
            QueryPerformanceCounter(paintQpcEnd.ptr)
            val elapsedUs = ((paintQpcEnd.QuadPart - paintQpcStart.QuadPart).toDouble() / ticksPerUs).toLong()
            currentPaintStageBreakdown = "$currentPaintStageBreakdown,preBlt=${preBltUs}us,blt=${postBltUs - preBltUs}us(${actualBltW}x${actualBltH},pres=$preservedFlag),endPaint=${postEndPaintUs - postBltUs}us,ctrl=${elapsedUs - postEndPaintUs}us"
            recordPaintMetrics(elapsedUs, isVideoStageOnly = false)
            isPaintingWindow = false
        }

        // Fast path for VBlank video-stage-only partial repaints (avoids redrawing TitleBar, Navbar, and Library LazyColumn)
        val nonOverlayUnchanged = lastPaintedNonOverlayVersion == DesktopNativeWidget.globalNonOverlayMutationVersion
        val fullscreenNow = isFullscreenActiveForMainWindow()
        val vr = lastRenderedVideoStageRect
        val rcL = ps.rcPaint.left
        val rcT = ps.rcPaint.top
        val rcR = ps.rcPaint.right
        val rcB = ps.rcPaint.bottom
        val stageRectValid = vr[0] >= 0 && vr[1] >= 0 &&
            vr[2] > vr[0] && vr[3] > vr[1] &&
            vr[2] <= clientW && vr[3] <= clientH
        val rcInsideVideoStage = stageRectValid &&
            rcL >= vr[0] - 2 && rcT >= vr[1] - 2 && rcR <= vr[2] + 2 && rcB <= vr[3] + 2
        val allowStageOnlyFastPath = isMainWin &&
            nonOverlayUnchanged &&
            lastFullPaintHwndKey == hwnd.toLong() &&
            lastFullPaintW == clientW &&
            lastFullPaintH == clientH &&
            lastFullPaintFullscreen == fullscreenNow &&
            rcInsideVideoStage &&
            (isVBlankVideoStageOnlyRepaint || (rcR - rcL) < clientW || (rcB - rcT) < clientH || fullscreenNow)
        if (allowStageOnlyFastPath) {
            isPaintingWindow = true
            isVBlankVideoStageOnlyRepaint = false
            activeMainBackBufferPreserved = true
            currentPaintHwndKey = hwnd.toLong()
            lastVideoStageFastBlitRect[0] = -1
            lastVideoStageFastBlitRect[2] = -1
            val theme = if (isWindowsSystemDarkTheme()) darkSystemPalette else lightSystemPalette
            QueryPerformanceCounter(paintQpcEnd.ptr)
            val preStageUs = ((paintQpcEnd.QuadPart - paintQpcStart.QuadPart).toDouble() / ticksPerUs).toLong()
            val fastPathRendered = Win32GeneralComponentTreeRenderer.renderCachedVideoStageFastPath(
                hwnd = hwnd,
                hdc = memDc,
                theme = theme,
                fontSmall = fontSmall,
                fontSmallBold = fontSmallBold,
                fontNormal = fontNormal,
                fontBold = fontBold,
                fontTitle = fontTitle
            )
            if (fastPathRendered) {
                QueryPerformanceCounter(paintQpcEnd.ptr)
                val postStageUs = ((paintQpcEnd.QuadPart - paintQpcStart.QuadPart).toDouble() / ticksPerUs).toLong()
                Win32GeneralComponentTreeRenderer.invalidateBackBufferRectKeys(vr[0], vr[1], vr[2], vr[3])
                if (hoverHwndKey == hwnd.toLong()) {
                    renderHoveredTooltipOverlay(memDc, fontSmall, theme, clientW, clientH)
                }
                val fbr = lastVideoStageFastBlitRect
                val useFastSubBlit = fbr[2] > fbr[0] && fbr[3] > fbr[1] && hoveredTooltip == null
                val rawL = if (useFastSubBlit) max(rcL, fbr[0]) else rcL
                val rawT = if (useFastSubBlit) max(rcT, fbr[1]) else rcT
                val rawR = if (useFastSubBlit) min(rcR, fbr[2]) else rcR
                val rawB = if (useFastSubBlit) min(rcB, fbr[3]) else rcB
                val bltL = rawL.coerceIn(0, clientW)
                val bltT = rawT.coerceIn(0, clientH)
                val bltR = rawR.coerceIn(bltL, clientW)
                val bltB = rawB.coerceIn(bltT, clientH)
                val bltW = bltR - bltL
                val bltH = bltB - bltT
                if (bltW > 0 && bltH > 0) {
                    BitBlt(hdc, bltL, bltT, bltW, bltH, memDc, bltL, bltT, SRCCOPY)
                }
                QueryPerformanceCounter(paintQpcEnd.ptr)
                val postBltUs = ((paintQpcEnd.QuadPart - paintQpcStart.QuadPart).toDouble() / ticksPerUs).toLong()
                activeMainBackBufferPreserved = false
                SelectObject(memDc, oldFont)
                EndPaint(hwnd, ps.ptr)
                QueryPerformanceCounter(paintQpcEnd.ptr)
                val elapsedUs = ((paintQpcEnd.QuadPart - paintQpcStart.QuadPart).toDouble() / ticksPerUs).toLong()
                currentPaintStageBreakdown = "vFast($currentPaintStageBreakdown,pre=${preStageUs}us,stage=${postStageUs - preStageUs}us,blt=${postBltUs - postStageUs}us[${bltW}x${bltH}],end=${elapsedUs - postBltUs}us)"
                recordPaintMetrics(elapsedUs, isVideoStageOnly = true)
                isPaintingWindow = false
                return@memScoped
            }
            activeMainBackBufferPreserved = false
        }
        isVBlankVideoStageOnlyRepaint = false
        Win32GeneralComponentTreeRenderer.resetLastPaintedVideoStageCacheKey()

        isPaintingWindow = true
        beginPaintFrameNativeControls(hwnd)
        currentPaintHwndKey = hwnd.toLong()
        hitRegions.clear()
        if (hwnd == activeHwnd) {
            canvasViewportRect = intArrayOf(-1, -1, -1, -1)
            activeCanvasImpl = null
        }

        val rootWidget = activeWindowImpl?.nativeWidget
        val toolPalettes = mutableListOf<DesktopToolPaletteNativeImpl<*>>()
        val dockPanels = mutableListOf<DesktopDockPanelNativeImpl<*>>()
        val canvases = mutableListOf<DesktopNativeWidget>()
        if (rootWidget != null) {
            var structVer = rootWidget.nativePointer * 31L + rootWidget.children.size
            for (c in rootWidget.children) {
                structVer = structVer * 31L + c.nativePointer + (if (c.visible) 17L else 0L) + c.children.size
                for (gc in c.children) {
                    structVer = structVer * 31L + gc.nativePointer + (if (gc.visible) 13L else 0L) + gc.children.size
                }
            }
            if (rootWidget.attributes["cachedStudioWidgetsVer"] as? Long == structVer) {
                @Suppress("UNCHECKED_CAST")
                (rootWidget.attributes["cachedToolPalettes"] as? List<DesktopToolPaletteNativeImpl<*>>)?.let {
                    toolPalettes.addAll(it)
                }
                @Suppress("UNCHECKED_CAST")
                (rootWidget.attributes["cachedDockPanels"] as? List<DesktopDockPanelNativeImpl<*>>)?.let {
                    dockPanels.addAll(it)
                }
                @Suppress("UNCHECKED_CAST")
                (rootWidget.attributes["cachedStudioCanvases"] as? List<DesktopNativeWidget>)?.let {
                    canvases.addAll(it)
                }
            } else {
                collectStudioWidgets(rootWidget, toolPalettes, dockPanels, canvases)
                rootWidget.attributes["cachedStudioWidgetsVer"] = structVer
                rootWidget.attributes["cachedToolPalettes"] = toolPalettes.toList()
                rootWidget.attributes["cachedDockPanels"] = dockPanels.toList()
                rootWidget.attributes["cachedStudioCanvases"] = canvases.toList()
            }
        }

        val theme = if (isWindowsSystemDarkTheme()) darkSystemPalette else lightSystemPalette

        // Attached Native Dialog Window (DialogWindow has its own owned Win32 HWND!)
        val dialogForHwnd = hwndToDialog[hwnd.toLong()]
        if (dialogForHwnd != null) {
            fillRectColor(memDc, 0, 0, clientW, clientH, theme.bgPanel)
            drawRectBorder(memDc, 0, 0, clientW, clientH, theme.borderSubtle)
            Win32GeneralComponentTreeRenderer.renderModalDialogBody(
                hwnd = hwnd,
                hdc = memDc,
                theme = theme,
                fontSmall = fontSmall,
                fontNormal = fontNormal,
                fontBold = fontBold,
                fontTitle = fontTitle,
                dialogWidget = dialogForHwnd.nativeWidget,
                left = 12,
                top = 12,
                right = clientW - 12,
                bottom = clientH - 12
            )
            if (hoverHwndKey == hwnd.toLong()) {
                renderHoveredTooltipOverlay(memDc, fontSmall, theme, clientW, clientH)
            }
            finishPaint()
            return@memScoped
        }

        val toolboxDockPanel = dockPanels.firstOrNull {
            it.getTitle().contains("Toolbox", ignoreCase = true) ||
                it.getTitle().contains("Tool", ignoreCase = true)
        }
        val timelinePanel = dockPanels.firstOrNull {
            it.getTitle().contains("Timeline", ignoreCase = true)
        }
        val rightPanels = dockPanels.filter {
            it !== toolboxDockPanel && it !== timelinePanel
        }

        // Auxiliary Panel Window (Multi-Window Mode — each DockPanel has its own PanelWindow!)
        val panelForHwnd = hwndToPanel[hwnd.toLong()]
        if (panelForHwnd != null) {
            fillRectColor(memDc, 0, 0, clientW, clientH, theme.bgPanel)
            val titleBarH = getPanelWindowTitleBarHeight(panelForHwnd)
            renderPanelWindowTitleBar(
                hwnd = hwnd,
                hdc = memDc,
                theme = theme,
                fontSmall = fontSmall,
                fontBold = fontBold,
                panel = panelForHwnd,
                clientW = clientW,
                titleBarH = titleBarH
            )
            val bodyTop = titleBarH + 6
            val bodyBottom = clientH - 6
            when {
                isToolboxPanel(panelForHwnd) -> {
                    renderLeftToolbox(
                        hwnd = hwnd,
                        hdc = memDc,
                        theme = theme,
                        fontSmall = fontSmall,
                        fontNormal = fontNormal,
                        fontBold = fontBold,
                        toolPalette = toolPalettes.firstOrNull(),
                        toolboxDockPanel = panelForHwnd,
                        width = clientW,
                        top = bodyTop,
                        bottom = bodyBottom,
                        isSeparatePanelWindow = true
                    )
                }
                isTimelinePanel(panelForHwnd) -> {
                    renderTopTimelineDock(
                        hwnd = hwnd,
                        hdc = memDc,
                        theme = theme,
                        fontSmall = fontSmall,
                        fontNormal = fontNormal,
                        fontBold = fontBold,
                        timelinePanel = panelForHwnd,
                        left = 6,
                        top = bodyTop,
                        right = clientW - 6,
                        bottom = bodyBottom,
                        isSeparatePanelWindow = true
                    )
                }
                else -> {
                    renderStandardDockPanelBody(
                        hwnd = hwnd,
                        hdc = memDc,
                        theme = theme,
                        fontSmall = fontSmall,
                        fontNormal = fontNormal,
                        fontBold = fontBold,
                        panel = panelForHwnd,
                        left = 6,
                        top = bodyTop,
                        right = clientW - 6,
                        bottomLimit = bodyBottom
                    )
                }
            }
            if (hoverHwndKey == hwnd.toLong()) {
                renderHoveredTooltipOverlay(memDc, fontSmall, theme, clientW, clientH)
            }
            finishPaint()
            return@memScoped
        }

        if (toolPalettes.isEmpty() && dockPanels.isEmpty()) {
            val isFullscreenActive = activeFullscreenElementWidget != null || isWindowFullscreenActive || activeWindowImpl?.isFullscreen() == true
            val hasCustomTitleBar = hasCustomMainWindowTitleBar()
            val canPreserveMainBackBuffer = isMainWin &&
                !isFullscreenActive &&
                !lastFrameHadTooltipOverlay &&
                hoveredTooltip.isNullOrBlank() &&
                rootWidget != null &&
                lastFullPaintHwndKey == hwnd.toLong() &&
                lastFullPaintW == clientW &&
                lastFullPaintH == clientH &&
                lastFullPaintDark == theme.isDark &&
                lastFullPaintFullscreen == isFullscreenActive &&
                lastFullPaintCustomTitleBar == hasCustomTitleBar &&
                lastFullPaintRootWidget === rootWidget
            activeMainBackBufferPreserved = canPreserveMainBackBuffer
            if (!canPreserveMainBackBuffer) {
                cachedCardPageFrameRootVer = -1L
                Win32GeneralComponentTreeRenderer.clearBackBufferRegionKeys()
                fillRectColor(memDc, 0, 0, clientW, clientH, theme.bgBase)
                markFrameBufferDirty(0, 0, clientW, clientH)
            }
            val mainTitleBarH = if (isFullscreenActive) {
                0
            } else {
                renderMainWindowTitleBar(
                    hwnd = hwnd,
                    hdc = memDc,
                    theme = theme,
                    fontSmall = fontSmall,
                    fontBold = fontBold,
                    clientW = clientW
                )
            }
            renderGeneralApplicationLayout(
                hwnd = hwnd,
                hdc = memDc,
                theme = theme,
                fontSmall = fontSmall,
                fontSmallBold = fontSmallBold,
                fontNormal = fontNormal,
                fontBold = fontBold,
                fontTitle = fontTitle,
                fontMetricBig = fontMetricBig,
                rootWidget = rootWidget,
                canvases = canvases,
                clientW = clientW,
                clientH = clientH,
                bodyTop = mainTitleBarH
            )
            if (hoverHwndKey == hwnd.toLong() && !hoveredTooltip.isNullOrBlank()) {
                cachedCardPageFrameRootVer = -1L
                renderHoveredTooltipOverlay(memDc, fontSmall, theme, clientW, clientH)
            }
            if (isMainWin) {
                lastPaintedNonOverlayVersion = DesktopNativeWidget.globalNonOverlayMutationVersion
                lastFullPaintHwndKey = hwnd.toLong()
                lastFullPaintW = clientW
                lastFullPaintH = clientH
                lastFullPaintDark = theme.isDark
                lastFullPaintFullscreen = isFullscreenActive
                lastFullPaintCustomTitleBar = hasCustomTitleBar
                lastFullPaintRootWidget = rootWidget
                lastFrameHadTooltipOverlay = hoverHwndKey == hwnd.toLong() && !hoveredTooltip.isNullOrBlank()
            }
            finishPaint()
            return@memScoped
        }

        val multiWindowMode = isStudioMultiWindowMode(rootWidget)
        val canvasWidget = canvases.firstOrNull()
        activeCanvasImpl = canvasWidget?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>

        if (multiWindowMode) {
            // Multi-Window Mode Main Canvas Window:
            // MenuBar is attached directly to this window; displays Document Tab / Viewport Toolbar strip,
            // full-bleed Hybrid Vector + Raster Canvas Viewport, and bottom Status Bar.
            fillRectColor(memDc, 0, 0, clientW, clientH, theme.bgBase)
            val mainTitleBarH = renderMainWindowTitleBar(
                hwnd = hwnd,
                hdc = memDc,
                theme = theme,
                fontSmall = fontSmall,
                fontBold = fontBold,
                clientW = clientW
            )
            val topBarH = mainTitleBarH + 38
            val statusBarH = 26
            val bodyBottom = max(topBarH + 120, clientH - statusBarH)

            fillRectColor(memDc, 0, mainTitleBarH, clientW, topBarH, theme.bgPanel)
            drawLineColor(memDc, 0, topBarH, clientW, topBarH, theme.borderSubtle)
            renderTopViewportToolbar(hwnd, memDc, theme, fontSmall, fontNormal, fontBold, rootWidget, dockPanels, clientW, topBarH)

            fillRectColor(memDc, 6, topBarH + 6, clientW - 6, bodyBottom - 6, theme.bgCard)
            drawRectBorder(memDc, 6, topBarH + 6, clientW - 6, bodyBottom - 6, theme.borderSubtle)
            renderCenterSurfaceViewport(
                hwnd = hwnd,
                hdc = memDc,
                theme = theme,
                fontSmall = fontSmall,
                fontSmallBold = fontSmallBold,
                fontNormal = fontNormal,
                fontBold = fontBold,
                fontTitle = fontTitle,
                rootWidget = rootWidget,
                canvasWidget = canvasWidget,
                left = 10,
                top = topBarH + 10,
                right = clientW - 10,
                bottom = bodyBottom - 10
            )

            fillRectColor(memDc, 0, bodyBottom, clientW, clientH, theme.bgPanel)
            drawLineColor(memDc, 0, bodyBottom, clientW, bodyBottom, theme.borderSubtle)
            renderStudioStatusBar(memDc, theme, fontSmall, rootWidget, dockPanels, clientW, bodyBottom, clientH)

            if (hoverHwndKey == hwnd.toLong()) {
                renderHoveredTooltipOverlay(memDc, fontSmall, theme, clientW, clientH)
            }
            finishPaint()
            return@memScoped
        }

        // Studio Single-Window Dock Layout — GenericUI Base Native OS System Theme
        fillRectColor(memDc, 0, 0, clientW, clientH, theme.bgBase)
        val mainTitleBarH = renderMainWindowTitleBar(
            hwnd = hwnd,
            hdc = memDc,
            theme = theme,
            fontSmall = fontSmall,
            fontBold = fontBold,
            clientW = clientW
        )
        val topBarH = mainTitleBarH + 40
        val statusBarH = 26
        val leftCollapsed = toolboxDockPanel?.isCollapsed() == true
        val leftDockW = if (leftCollapsed) 168 else 212
        val rightDockW = 376
        val bodyBottom = max(topBarH + 220, clientH - statusBarH)
        val centerLeft = leftDockW + 6
        val centerRight = max(centerLeft + 260, clientW - rightDockW - 6)

        val timelineCollapsed = timelinePanel?.isCollapsed() == true
        val timelineDockH = if (timelinePanel == null) 0 else if (timelineCollapsed) 28 else 112

        val timelineTop = topBarH + 6
        val timelineBottom = timelineTop + timelineDockH
        val canvasTop = if (timelineDockH > 0) timelineBottom + 6 else topBarH + 6
        val canvasBottom = max(canvasTop + 140, bodyBottom - 6)

        // 1. Top Tool Options & Document Tab Bar (no app icon/name duplicated)
        fillRectColor(memDc, 0, mainTitleBarH, clientW, topBarH, theme.bgPanel)
        drawLineColor(memDc, 0, topBarH, clientW, topBarH, theme.borderSubtle)
        renderTopViewportToolbar(hwnd, memDc, theme, fontSmall, fontNormal, fontBold, rootWidget, dockPanels, clientW, topBarH)

        // 2. Left Toolbox Dock (Collapsible, Icon-Only Tool Palette Buttons + Hover Tooltips + FG/BG Swatches)
        fillRectColor(memDc, 0, topBarH + 1, leftDockW, bodyBottom, theme.bgPanel)
        drawLineColor(memDc, leftDockW, topBarH + 1, leftDockW, bodyBottom, theme.borderSubtle)
        renderLeftToolbox(
            hwnd = hwnd,
            hdc = memDc,
            theme = theme,
            fontSmall = fontSmall,
            fontNormal = fontNormal,
            fontBold = fontBold,
            toolPalette = toolPalettes.firstOrNull(),
            toolboxDockPanel = toolboxDockPanel,
            width = leftDockW,
            top = topBarH + 6,
            bottom = bodyBottom - 6
        )

        // 3. Center Top: Animation Timeline & Keyframe Curves (Positioned ABOVE the Canvas Viewport!)
        if (timelinePanel != null) {
            fillRectColor(memDc, centerLeft, timelineTop, centerRight, timelineBottom, theme.bgPanel)
            drawRectBorder(memDc, centerLeft, timelineTop, centerRight, timelineBottom, theme.borderSubtle)
            renderTopTimelineDock(
                hwnd = hwnd,
                hdc = memDc,
                theme = theme,
                fontSmall = fontSmall,
                fontNormal = fontNormal,
                fontBold = fontBold,
                timelinePanel = timelinePanel,
                left = centerLeft,
                top = timelineTop,
                right = centerRight,
                bottom = timelineBottom
            )
        }

        // 4. Center Below Timeline: Hybrid Vector + Raster Canvas Viewport
        fillRectColor(memDc, centerLeft, canvasTop, centerRight, canvasBottom, theme.bgCard)
        drawRectBorder(memDc, centerLeft, canvasTop, centerRight, canvasBottom, theme.borderSubtle)
        renderCenterSurfaceViewport(
            hwnd = hwnd,
            hdc = memDc,
            theme = theme,
            fontSmall = fontSmall,
            fontSmallBold = fontSmallBold,
            fontNormal = fontNormal,
            fontBold = fontBold,
            fontTitle = fontTitle,
            rootWidget = rootWidget,
            canvasWidget = canvasWidget,
            left = centerLeft + 6,
            top = canvasTop + 6,
            right = centerRight - 6,
            bottom = canvasBottom - 6
        )

        // 5. Right Collapsible Inspector Dock (AI Assistant, Shared Objects, Asset Library, Layers, Fonts, Filters)
        val rightX = clientW - rightDockW
        fillRectColor(memDc, rightX, topBarH + 1, clientW, bodyBottom, theme.bgPanel)
        drawLineColor(memDc, rightX, topBarH + 1, rightX, bodyBottom, theme.borderSubtle)
        renderRightInspectorDock(
            hwnd = hwnd,
            hdc = memDc,
            theme = theme,
            fontSmall = fontSmall,
            fontNormal = fontNormal,
            fontBold = fontBold,
            panels = rightPanels,
            left = rightX + 6,
            top = topBarH + 6,
            right = clientW - 6,
            bottom = bodyBottom - 6
        )

        // 6. Bottom Status Bar
        fillRectColor(memDc, 0, bodyBottom, clientW, clientH, theme.bgPanel)
        drawLineColor(memDc, 0, bodyBottom, clientW, bodyBottom, theme.borderSubtle)
        renderStudioStatusBar(memDc, theme, fontSmall, rootWidget, dockPanels, clientW, bodyBottom, clientH)

        // 7. Native Hover Tooltip Popup Overlay
        if (hoverHwndKey == hwnd.toLong()) {
            renderHoveredTooltipOverlay(memDc, fontSmall, theme, clientW, clientH)
        }

        finishPaint()
    }

    private fun createSegoeFont(heightPx: Int, weight: Int): HFONT? =
        CreateFontW(
            heightPx, 0, 0, 0, weight, 0u, 0u, 0u,
            DEFAULT_CHARSET.toUInt(), OUT_DEFAULT_PRECIS.toUInt(),
            CLIP_DEFAULT_PRECIS.toUInt(), CLEARTYPE_QUALITY.toUInt(),
            DEFAULT_PITCH.toUInt(), "Segoe UI"
        )

    internal fun widgetHasClass(widget: DesktopNativeWidget?, className: String): Boolean {
        if (widget == null || className.isEmpty()) return false
        if (widget.cachedAllClassTokens.isEmpty() &&
            (widget.styleProperties["class"] != null ||
                widget.attributes["class"] != null ||
                widget.attributes["bootstrapClass"] != null ||
                widget.attributes["enumClasses"] != null ||
                widget.attributes["nativeImpl"] != null)
        ) {
            widget.rebuildClassTokensCache(
                (widget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>)?.componentClassPrefix ?: ""
            )
        }
        return className in widget.cachedAllClassTokens
    }

    internal fun findWidgetByClass(root: DesktopNativeWidget?, className: String): DesktopNativeWidget? {
        if (root == null || !root.visible || root.disposed) return null
        if (widgetHasClass(root, className)) return root
        val list = root.children
        var i = 0
        while (i < list.size) {
            val child = list.getOrNull(i)
            if (child != null) {
                val found = findWidgetByClass(child, className)
                if (found != null) return found
            }
            i++
        }
        return null
    }

    internal fun findWidgetsByClass(
        root: DesktopNativeWidget?,
        className: String,
        out: MutableList<DesktopNativeWidget> = mutableListOf()
    ): List<DesktopNativeWidget> {
        if (root == null || !root.visible || root.disposed) return out
        if (widgetHasClass(root, className)) {
            out.add(root)
        }
        val list = root.children
        var i = 0
        while (i < list.size) {
            val child = list.getOrNull(i)
            if (child != null) {
                findWidgetsByClass(child, className, out)
            }
            i++
        }
        return out
    }

    internal fun extractWidgetText(widget: DesktopNativeWidget?): String {
        if (widget == null) return ""
        val impl = widget.attributes["nativeImpl"]
        if (impl is DesktopTextNativeImpl<*>) return impl.getText()
        if (impl is DesktopButtonNativeImpl<*>) return impl.getText()
        val list = widget.children
        var i = 0
        while (i < list.size) {
            val child = list.getOrNull(i)
            if (child != null) {
                val t = extractWidgetText(child)
                if (t.isNotEmpty()) return t
            }
            i++
        }
        return ""
    }

    internal fun extractProgressBarValue(widget: DesktopNativeWidget?): Float {
        if (widget == null) return 0f
        val impl = widget.attributes["nativeImpl"]
        if (impl is DesktopProgressBarNativeImpl<*>) return impl.getProgress()
        val list = widget.children
        var i = 0
        while (i < list.size) {
            val child = list.getOrNull(i)
            val childImpl = child?.attributes?.get("nativeImpl")
            if (childImpl is DesktopProgressBarNativeImpl<*>) return childImpl.getProgress()
            i++
        }
        return 0f
    }

    private fun containsNavbarWidget(root: DesktopNativeWidget?): Boolean {
        if (root == null || !root.visible || root.disposed) return false
        if (root.attributes["nativeImpl"] is DesktopNavbarNativeImpl<*> || widgetHasClass(root, "navbar")) return true
        val list = root.children
        var i = 0
        while (i < list.size) {
            val child = list.getOrNull(i)
            if (child != null && containsNavbarWidget(child)) return true
            i++
        }
        return false
    }

    private fun findBootstrapCardPageRoot(rootWidget: DesktopNativeWidget?): DesktopNativeWidget? {
        if (rootWidget == null) return null
        val mutVer = rootWidget.subtreeMutationVersion
        if (rootWidget.attributes["cachedBootstrapCardPageRootVer"] as? Long == mutVer) {
            return if (rootWidget.attributes["cachedBootstrapCardPageRootMatch"] == true) rootWidget else null
        }
        val matched = findWidgetByClass(rootWidget, "menu") == null &&
            findWidgetByClass(rootWidget, "hybrid-column-layout") == null &&
            !containsNavbarWidget(rootWidget) &&
            (findWidgetByClass(rootWidget, "page-host")?.let { findWidgetByClass(it, "card") != null } == true ||
                findWidgetByClass(rootWidget, "card-body") != null)
        rootWidget.attributes["cachedBootstrapCardPageRootVer"] = mutVer
        rootWidget.attributes["cachedBootstrapCardPageRootMatch"] = matched
        return if (matched) rootWidget else null
    }

    internal fun collectTitleBarWidgetSet(vararg titleBars: TitleBar?): Set<DesktopNativeWidget> {
        val result = mutableSetOf<DesktopNativeWidget>()
        for (tb in titleBars) {
            if (tb == null) continue
            val allComponents = tb.leadingComponents + tb.centerComponents + tb.trailingComponents
            for (comp in allComponents) {
                val widget = (comp.nativeImpl as? DesktopBaseNativeImpl<*>)?.nativeWidget ?: continue
                widget.attributes["mountedInTitleBar"] = true
                result.add(widget)
            }
        }
        return result
    }

    internal fun isWidgetMountedInTitleBar(
        widget: DesktopNativeWidget?,
        titleBarWidgets: Set<DesktopNativeWidget> = emptySet()
    ): Boolean {
        if (widget == null) return false
        if (widget in titleBarWidgets) return true
        if (widget.attributes["mountedInTitleBar"] == true) return true
        if (widget.attributes["data-mounted-in-titlebar"] == "true") return true
        return false
    }

    /**
     * Returns the single full-window canvas widget ONLY when the application root
     * (after unwrapping single-child shell wrappers) is a standalone full-window surface
     * (such as a standalone full-window interactive surface), and NEVER when a canvas
     * is an embedded widget inside a native UI component hierarchy.
     */
    private fun findFullWindowCanvasWidget(
        rootWidget: DesktopNativeWidget?,
        titleBarWidgets: Set<DesktopNativeWidget>
    ): DesktopNativeWidget? {
        var cur: DesktopNativeWidget? = rootWidget ?: return null
        var depth = 0
        while (cur != null && depth < 12) {
            if (!cur.visible || cur.disposed) return null
            if (cur.attributes.containsKey("captureRgba") || cur.attributes.containsKey("captureBgra")) {
                return cur
            }
            if (widgetHasClass(cur, "hybrid-column-layout") ||
                widgetHasClass(cur, "gui-hybrid-split-navigation") ||
                widgetHasClass(cur, "gui-collapsible-bottom-dock") ||
                cur.attributes["nativeImpl"] is DesktopNavbarNativeImpl<*> ||
                widgetHasClass(cur, "navbar") ||
                widgetHasClass(cur, "card")
            ) {
                return null
            }
            val snapshot = runCatching { cur.children.toTypedArray() }.getOrElse { emptyArray() }
            val visibleChildren = snapshot.filter {
                it.visible && !it.disposed && !isWidgetMountedInTitleBar(it, titleBarWidgets) &&
                    it.attributes["nativeImpl"] !is DesktopDialogWindowNativeImpl<*>
            }
            if (visibleChildren.size != 1) {
                return null
            }
            cur = visibleChildren.first()
            depth++
        }
        return null
    }

    /**
     * Renders general non-dock applications using native Windows system theming.
     */
    private fun renderGeneralApplicationLayout(
        hwnd: HWND,
        hdc: HDC?,
        theme: SystemThemePalette,
        fontSmall: HFONT?,
        fontSmallBold: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        fontTitle: HFONT?,
        fontMetricBig: HFONT?,
        rootWidget: DesktopNativeWidget?,
        canvases: List<DesktopNativeWidget>,
        clientW: Int,
        clientH: Int,
        bodyTop: Int = 0
    ) {
        val titleBarWidgets = collectTitleBarWidgetSet(activeWindowImpl?.getTitleBarInstructions())

        val rootMutVer = if (rootWidget != null) {
            var structVer = rootWidget.nativePointer * 31L + rootWidget.children.size
            for (c in rootWidget.children) {
                structVer = structVer * 31L + c.nativePointer + (if (c.visible) 17L else 0L) + c.children.size
                for (gc in c.children) {
                    structVer = structVer * 31L + gc.nativePointer + (if (gc.visible) 13L else 0L) + gc.children.size
                }
            }
            structVer
        } else {
            -1L
        }
        val cachedLayoutScanVer = rootWidget?.attributes?.get("cachedGeneralLayoutScanVer") as? Long
        val dataEditorRoot: DesktopNativeWidget?
        val appHeader: DesktopNativeWidget?
        val metricsStrip: DesktopNativeWidget?
        val queuesContainer: DesktopNativeWidget?
        val cardPageRoot: DesktopNativeWidget?
        val fullWindowCanvas: DesktopNativeWidget?
        val pageMenuNode: DesktopNativeWidget?
        if (rootWidget != null && cachedLayoutScanVer == rootMutVer) {
            dataEditorRoot = rootWidget.attributes["cachedDataEditorRoot"] as? DesktopNativeWidget
            appHeader = rootWidget.attributes["cachedAppHeader"] as? DesktopNativeWidget
            metricsStrip = rootWidget.attributes["cachedMetricsStrip"] as? DesktopNativeWidget
            queuesContainer = rootWidget.attributes["cachedQueuesContainer"] as? DesktopNativeWidget
            cardPageRoot = rootWidget.attributes["cachedCardPageRoot"] as? DesktopNativeWidget
            fullWindowCanvas = rootWidget.attributes["cachedFullWindowCanvas"] as? DesktopNativeWidget
            pageMenuNode = rootWidget.attributes["cachedPageMenuNode"] as? DesktopNativeWidget
        } else {
            dataEditorRoot = findWidgetByClass(rootWidget, "data-editor-root")
                ?: findWidgetByClass(rootWidget, "lazy-grid-tree")
            appHeader = if (dataEditorRoot == null) findWidgetByClass(rootWidget, "app-header") else null
            metricsStrip = if (appHeader != null) findWidgetByClass(rootWidget, "metrics-strip") else null
            queuesContainer = if (appHeader != null) findWidgetByClass(rootWidget, "queues-container") else null
            cardPageRoot = if (dataEditorRoot == null && appHeader == null) findBootstrapCardPageRoot(rootWidget) else null
            fullWindowCanvas = if (dataEditorRoot == null && appHeader == null && cardPageRoot == null) {
                findFullWindowCanvasWidget(rootWidget, titleBarWidgets)
            } else {
                null
            }
            pageMenuNode = if (dataEditorRoot == null && appHeader == null && cardPageRoot == null && fullWindowCanvas == null) {
                findWidgetByClass(rootWidget, "menu")
            } else {
                null
            }
            if (rootWidget != null) {
                rootWidget.attributes["cachedGeneralLayoutScanVer"] = rootMutVer
                if (dataEditorRoot != null) rootWidget.attributes["cachedDataEditorRoot"] = dataEditorRoot else rootWidget.attributes.remove("cachedDataEditorRoot")
                if (appHeader != null) rootWidget.attributes["cachedAppHeader"] = appHeader else rootWidget.attributes.remove("cachedAppHeader")
                if (metricsStrip != null) rootWidget.attributes["cachedMetricsStrip"] = metricsStrip else rootWidget.attributes.remove("cachedMetricsStrip")
                if (queuesContainer != null) rootWidget.attributes["cachedQueuesContainer"] = queuesContainer else rootWidget.attributes.remove("cachedQueuesContainer")
                if (cardPageRoot != null) rootWidget.attributes["cachedCardPageRoot"] = cardPageRoot else rootWidget.attributes.remove("cachedCardPageRoot")
                if (fullWindowCanvas != null) rootWidget.attributes["cachedFullWindowCanvas"] = fullWindowCanvas else rootWidget.attributes.remove("cachedFullWindowCanvas")
                if (pageMenuNode != null) rootWidget.attributes["cachedPageMenuNode"] = pageMenuNode else rootWidget.attributes.remove("cachedPageMenuNode")
            }
        }
        if (dataEditorRoot != null) {
            renderDataEditorLazyGridTreeLayout(
                hwnd = hwnd,
                hdc = hdc,
                theme = theme,
                fontSmall = fontSmall,
                fontSmallBold = fontSmallBold,
                fontNormal = fontNormal,
                fontBold = fontBold,
                rootWidget = rootWidget,
                clientW = clientW,
                clientH = clientH
            )
            return
        }

        if (appHeader != null && metricsStrip != null && queuesContainer != null) {
            renderHeaderMetricsQueuesLayout(
                hdc = hdc,
                theme = theme,
                fontSmall = fontSmall,
                fontSmallBold = fontSmallBold,
                fontNormal = fontNormal,
                fontBold = fontBold,
                fontTitle = fontTitle,
                fontMetricBig = fontMetricBig,
                appHeader = appHeader,
                metricsStrip = metricsStrip,
                queuesContainer = queuesContainer,
                clientW = clientW,
                clientH = clientH
            )
            return
        }

        if (cardPageRoot != null) {
            renderBootstrapCardPageLayout(
                hwnd = hwnd,
                hdc = hdc,
                theme = theme,
                fontSmall = fontSmall,
                fontSmallBold = fontSmallBold,
                fontNormal = fontNormal,
                fontBold = fontBold,
                fontTitle = fontTitle,
                fontMetricBig = fontMetricBig,
                cardPageRoot = cardPageRoot,
                clientW = clientW,
                clientH = clientH,
                bodyTop = bodyTop
            )
            return
        }

        if (fullWindowCanvas != null) {
            renderGeneralSurfaceViewport(
                hdc = hdc,
                canvasWidget = fullWindowCanvas,
                clientW = clientW,
                clientH = clientH,
                bodyTop = bodyTop
            )
            return
        }

        if (pageMenuNode != null || widgetHasClass(rootWidget, "page-host")) {
            renderPageHostLayout(
                hwnd = hwnd,
                hdc = hdc,
                theme = theme,
                fontSmall = fontSmall,
                fontSmallBold = fontSmallBold,
                fontNormal = fontNormal,
                fontBold = fontBold,
                fontTitle = fontTitle,
                fontMetricBig = fontMetricBig,
                rootWidget = rootWidget,
                menuWidget = pageMenuNode ?: rootWidget,
                clientW = clientW,
                clientH = clientH
            )
            return
        }

        Win32GeneralComponentTreeRenderer.renderGeneralComponentTree(
            hwnd = hwnd,
            hdc = hdc,
            theme = theme,
            fontSmall = fontSmall,
            fontSmallBold = fontSmallBold,
            fontNormal = fontNormal,
            fontBold = fontBold,
            fontTitle = fontTitle,
            fontMetricBig = fontMetricBig,
            rootWidget = rootWidget,
            titleBarWidgets = titleBarWidgets,
            clientW = clientW,
            clientH = clientH,
            bodyTop = bodyTop
        )
    }

    internal fun blitScaledBgraToHdc(
        hdc: HDC?,
        left: Int,
        top: Int,
        drawW: Int,
        drawH: Int,
        bgra: ByteArray
    ) {
        if (drawW <= 0 || drawH <= 0 || bgra.size < drawW * drawH * 4) return
        val bufPixels = when {
            hdc != null && hdc == activeBackBufferDc && activeBackBufferPixels != null -> activeBackBufferPixels
            else -> cachedMainBackBufferPixels
        }
        val bufW = when {
            hdc != null && hdc == activeBackBufferDc && activeBackBufferPixels != null -> activeBackBufferWidth
            else -> cachedMainBackBufferWidth
        }
        val bufH = when {
            hdc != null && hdc == activeBackBufferDc && activeBackBufferPixels != null -> activeBackBufferHeight
            else -> cachedMainBackBufferHeight
        }
        if (bufPixels != null && left >= 0 && top >= 0 && left + drawW <= bufW && top + drawH <= bufH) {
            flushGdiIfOverlapping(left, top, left + drawW, top + drawH)
            markFrameBufferDirty(left, top, left + drawW, top + drawH)
            bgra.usePinned { pinned ->
                val srcBase = pinned.addressOf(0)
                val rowBytes = (drawW * 4).toULong()
                for (row in 0 until drawH) {
                    val dstRowPtr = bufPixels + ((top + row) * bufW + left) * 4
                    val srcRowPtr = srcBase + (row * drawW * 4)
                    platform.posix.memcpy(dstRowPtr, srcRowPtr, rowBytes)
                }
            }
            return
        }
        memScoped {
            val bmi = alloc<BITMAPINFO>()
            bmi.bmiHeader.biSize = sizeOf<BITMAPINFOHEADER>().toUInt()
            bmi.bmiHeader.biWidth = drawW
            bmi.bmiHeader.biHeight = -drawH
            bmi.bmiHeader.biPlanes = 1u
            bmi.bmiHeader.biBitCount = 32u
            bmi.bmiHeader.biCompression = BI_RGB.toUInt()
            SetStretchBltMode(hdc, HALFTONE)
            SetBrushOrgEx(hdc, 0, 0, null)
            bgra.usePinned { pinned ->
                StretchDIBits(
                    hdc,
                    left,
                    top,
                    drawW,
                    drawH,
                    0,
                    0,
                    drawW,
                    drawH,
                    pinned.addressOf(0),
                    bmi.ptr,
                    DIB_RGB_COLORS.toUInt(),
                    SRCCOPY
                )
            }
            markGdiRectDirty(left, top, left + drawW, top + drawH)
        }
    }

    private var sharedResampleSrcRow0 = IntArray(4096)
    private var sharedResampleSrcRow1 = IntArray(4096)
    private var sharedResampleDstRow = IntArray(4096)
    private var cachedResampleTableKey: Long = 0L
    private var cachedResampleX0 = IntArray(0)
    private var cachedResampleX1 = IntArray(0)
    private var cachedResampleWx16 = IntArray(0)
    private var cachedResampleXMap = IntArray(0)

    private fun ensureResampleHorizontalTables(srcW: Int, dstW: Int) {
        val key = (srcW.toLong() shl 32) or (dstW.toLong() and 0xFFFFFFFFL)
        if (cachedResampleTableKey == key && cachedResampleX0.size >= dstW) return
        cachedResampleTableKey = key
        if (cachedResampleX0.size < dstW) {
            cachedResampleX0 = IntArray(dstW)
            cachedResampleX1 = IntArray(dstW)
            cachedResampleWx16 = IntArray(dstW)
            cachedResampleXMap = IntArray(dstW)
        }
        val maxSrcX = (srcW - 1).coerceAtLeast(0)
        for (dx in 0 until dstW) {
            val srcXf = ((dx + 0.5f) * srcW.toFloat() / dstW.toFloat() - 0.5f).coerceIn(0f, maxSrcX.toFloat())
            val x0 = srcXf.toInt().coerceIn(0, maxSrcX)
            val x1 = (x0 + 1).coerceAtMost(maxSrcX)
            cachedResampleX0[dx] = x0
            cachedResampleX1[dx] = x1
            cachedResampleWx16[dx] = ((srcXf - x0) * 16f + 0.5f).toInt().coerceIn(0, 16)
            cachedResampleXMap[dx] = (dx * srcW) / dstW
        }
    }

    private fun resampleBgraBilinearFromPtr(
        srcPtr: CPointer<ByteVar>,
        srcW: Int,
        srcH: Int,
        dstW: Int,
        dstH: Int,
        out: ByteArray,
        directDstPixels: CPointer<ByteVar>? = null,
        directDstBufW: Int = 0,
        directLeft: Int = 0,
        directTop: Int = 0
    ) {
        if (sharedResampleSrcRow0.size < srcW) sharedResampleSrcRow0 = IntArray(max(srcW, 4096))
        if (sharedResampleSrcRow1.size < srcW) sharedResampleSrcRow1 = IntArray(max(srcW, 4096))
        if (sharedResampleDstRow.size < dstW) sharedResampleDstRow = IntArray(max(dstW, 4096))
        ensureResampleHorizontalTables(srcW, dstW)
        val x0Arr = cachedResampleX0
        val x1Arr = cachedResampleX1
        val wx16Arr = cachedResampleWx16
        val row0Arr = sharedResampleSrcRow0
        val row1Arr = sharedResampleSrcRow1
        val dstRowArr = sharedResampleDstRow
        val maxSrcY = (srcH - 1).coerceAtLeast(0)
        val srcRowBytes = (srcW * 4).toULong()
        val dstRowBytes = (dstW * 4).toULong()

        row0Arr.usePinned { row0Pin ->
            row1Arr.usePinned { row1Pin ->
                dstRowArr.usePinned { dstRowPin ->
                    out.usePinned { outPin ->
                        val row0Ptr = row0Pin.addressOf(0)
                        val row1Ptr = row1Pin.addressOf(0)
                        val dstRowPtr = dstRowPin.addressOf(0)
                        val outBasePtr = outPin.addressOf(0)
                        var loadedY0 = -1
                        var loadedY1 = -1

                        for (dy in 0 until dstH) {
                            val srcYf = ((dy + 0.5f) * srcH.toFloat() / dstH.toFloat() - 0.5f).coerceIn(0f, maxSrcY.toFloat())
                            val y0 = srcYf.toInt().coerceIn(0, maxSrcY)
                            val y1 = (y0 + 1).coerceAtMost(maxSrcY)
                            val wy16 = ((srcYf - y0) * 16f + 0.5f).toInt().coerceIn(0, 16)
                            val invWy16 = 16 - wy16

                            if (y0 != loadedY0) {
                                if (y0 == loadedY1) {
                                    platform.posix.memcpy(row0Ptr, row1Ptr, srcRowBytes)
                                } else {
                                    platform.posix.memcpy(row0Ptr, srcPtr + (y0 * srcW * 4), srcRowBytes)
                                }
                                loadedY0 = y0
                            }

                            if (wy16 == 0 || y0 == y1) {
                                for (dx in 0 until dstW) {
                                    val wx16 = wx16Arr[dx]
                                    val c00 = row0Arr[x0Arr[dx]]
                                    if (wx16 == 0) {
                                        dstRowArr[dx] = -0x1000000 or (c00 and 0x00FFFFFF)
                                    } else {
                                        val c10 = row0Arr[x1Arr[dx]]
                                        val invWx16 = 16 - wx16
                                        val rb = (((c00 and 0x00FF00FF) * invWx16 + (c10 and 0x00FF00FF) * wx16) ushr 4) and 0x00FF00FF
                                        val g = (((c00 and 0x0000FF00) * invWx16 + (c10 and 0x0000FF00) * wx16) ushr 4) and 0x0000FF00
                                        dstRowArr[dx] = -0x1000000 or rb or g
                                    }
                                }
                            } else {
                                if (y1 != loadedY1) {
                                    platform.posix.memcpy(row1Ptr, srcPtr + (y1 * srcW * 4), srcRowBytes)
                                    loadedY1 = y1
                                }
                                for (dx in 0 until dstW) {
                                    val wx16 = wx16Arr[dx]
                                    val invWx16 = 16 - wx16
                                    val w00 = invWx16 * invWy16
                                    val w10 = wx16 * invWy16
                                    val w01 = invWx16 * wy16
                                    val w11 = wx16 * wy16
                                    val i0 = x0Arr[dx]
                                    val i1 = x1Arr[dx]
                                    val c00 = row0Arr[i0]
                                    val c10 = row0Arr[i1]
                                    val c01 = row1Arr[i0]
                                    val c11 = row1Arr[i1]
                                    val rb = (
                                        (c00 and 0x00FF00FF) * w00 +
                                            (c10 and 0x00FF00FF) * w10 +
                                            (c01 and 0x00FF00FF) * w01 +
                                            (c11 and 0x00FF00FF) * w11
                                        ) ushr 8
                                    val g = (
                                        (c00 and 0x0000FF00) * w00 +
                                            (c10 and 0x0000FF00) * w10 +
                                            (c01 and 0x0000FF00) * w01 +
                                            (c11 and 0x0000FF00) * w11
                                        ) ushr 8
                                    dstRowArr[dx] = -0x1000000 or (rb and 0x00FF00FF) or (g and 0x0000FF00)
                                }
                            }

                            platform.posix.memcpy(outBasePtr + (dy * dstW * 4), dstRowPtr, dstRowBytes)
                            if (directDstPixels != null) {
                                val backRowPtr = directDstPixels + ((directTop + dy) * directDstBufW + directLeft) * 4
                                platform.posix.memcpy(backRowPtr, dstRowPtr, dstRowBytes)
                            }
                        }
                    }
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    internal fun renderInlineCanvasSurface(
        hdc: HDC?,
        canvasWidget: DesktopNativeWidget,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        theme: SystemThemePalette,
        registerAsPrimaryCanvas: Boolean = false
    ) {
        val drawW = (right - left).coerceAtLeast(1)
        val drawH = (bottom - top).coerceAtLeast(1)
        val surfWFn = canvasWidget.attributes["surfaceWidth"] as? (() -> Int)
        val surfHFn = canvasWidget.attributes["surfaceHeight"] as? (() -> Int)
        val resizeFn = canvasWidget.attributes["resizeSurface"] as? ((Int, Int) -> Unit)
        val captureBgraPtrFn = canvasWidget.attributes["captureBgraPtr"] as? (() -> Pair<COpaquePointer?, Int>)
        val captureBgraFn = canvasWidget.attributes["captureBgra"] as? (() -> ByteArray)

        if (registerAsPrimaryCanvas) {
            val curW = (surfWFn?.invoke() ?: drawW).coerceAtLeast(1)
            val curH = (surfHFn?.invoke() ?: drawH).coerceAtLeast(1)
            if (curW != drawW || curH != drawH) {
                runCatching { resizeFn?.invoke(drawW, drawH) }
            }
        }

        val surfW = (surfWFn?.invoke() ?: drawW).coerceAtLeast(1)
        val surfH = (surfHFn?.invoke() ?: drawH).coerceAtLeast(1)
        if (registerAsPrimaryCanvas || activeCanvasImpl == null) {
            activeCanvasImpl = canvasWidget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
            activeCanvasSurfaceSize = surfW to surfH
            canvasViewportRect = intArrayOf(left, top, right, bottom)
        }

        val swapCount: Long = when (val sc = canvasWidget.attributes["swapCount"]) {
            is Number -> sc.toLong()
            is Function0<*> -> (sc.invoke() as? Number)?.toLong() ?: -1L
            else -> -1L
        }
        val useAntialiasing = (canvasWidget.attributes["antialiasing"] as? Boolean) != false
        val surfaceSpec = canvasWidget.interactiveSurfaceSpec
        val suppressBorder = surfaceSpec?.suppressDefaultBorder == true || canvasWidget.attributes["suppressDefaultBorder"] == true
        val preferHardwareStretch = surfaceSpec?.preferHardwareStretchBlit == true || canvasWidget.attributes["preferHardwareStretchBlit"] == true

        val prevScaledSwap = (canvasWidget.attributes["_inlineScaledSwap"] as? Long) ?: -2L
        val prevScaledW = (canvasWidget.attributes["_inlineScaledW"] as? Int) ?: 0
        val prevScaledH = (canvasWidget.attributes["_inlineScaledH"] as? Int) ?: 0
        val cachedScaled = canvasWidget.attributes["_inlineScaledBgra"] as? ByteArray
        if (swapCount >= 0L &&
            swapCount == prevScaledSwap &&
            drawW == prevScaledW &&
            drawH == prevScaledH &&
            cachedScaled != null &&
            cachedScaled.size >= drawW * drawH * 4
        ) {
            blitScaledBgraToHdc(hdc, left, top, drawW, drawH, cachedScaled)
            if (!suppressBorder) {
                drawRectBorder(hdc, left, top, right, bottom, theme.borderSubtle)
            }
            return
        }

        if (swapCount >= 0L && swapCount != prevScaledSwap) {
            runCatching {
                (canvasWidget.attributes["onBeforePresentFrame"] as? ((Long, Double?) -> Unit))?.invoke(swapCount, null)
            }
        }

        if (captureBgraPtrFn != null) {
            val (bgraPtr, byteLen) = runCatching { captureBgraPtrFn.invoke() }.getOrNull() ?: (null to 0)
            if (bgraPtr != null && byteLen >= surfW * surfH * 4) {
                val srcBytePtr = bgraPtr.reinterpret<ByteVar>()
                val bufPixels = if (hdc != null && hdc == activeBackBufferDc && activeBackBufferPixels != null) activeBackBufferPixels else cachedMainBackBufferPixels
                val bufW = if (hdc != null && hdc == activeBackBufferDc && activeBackBufferPixels != null) activeBackBufferWidth else cachedMainBackBufferWidth
                val bufH = if (hdc != null && hdc == activeBackBufferDc && activeBackBufferPixels != null) activeBackBufferHeight else cachedMainBackBufferHeight
                val canWriteDirectToBackBuffer = bufPixels != null && left >= 0 && top >= 0 && left + drawW <= bufW && top + drawH <= bufH

                if (preferHardwareStretch && canWriteDirectToBackBuffer && bufPixels != null && drawW in 1..4096 && drawH in 1..4096) {
                    flushGdiIfOverlapping(left, top, left + drawW, top + drawH)
                    memScoped {
                        val bmi = alloc<BITMAPINFO>()
                        bmi.bmiHeader.biSize = sizeOf<BITMAPINFOHEADER>().toUInt()
                        bmi.bmiHeader.biWidth = surfW
                        bmi.bmiHeader.biHeight = -surfH
                        bmi.bmiHeader.biPlanes = 1u
                        bmi.bmiHeader.biBitCount = 32u
                        bmi.bmiHeader.biCompression = BI_RGB.toUInt()

                        SetStretchBltMode(hdc, COLORONCOLOR)
                        StretchDIBits(
                            hdc,
                            left,
                            top,
                            drawW,
                            drawH,
                            0,
                            0,
                            surfW,
                            surfH,
                            bgraPtr,
                            bmi.ptr,
                            DIB_RGB_COLORS.toUInt(),
                            SRCCOPY
                        )
                    }
                    GdiFlush()
                    markFrameBufferDirty(left, top, left + drawW, top + drawH)
                    val neededBytes = drawW * drawH * 4
                    val targetArray = if (cachedScaled != null && cachedScaled.size == neededBytes) {
                        cachedScaled
                    } else {
                        ByteArray(neededBytes).also { canvasWidget.attributes["_inlineScaledBgra"] = it }
                    }
                    val dstRowBytes = (drawW * 4).toULong()
                    targetArray.usePinned { dstPin ->
                        val dstBase = dstPin.addressOf(0)
                        for (dy in 0 until drawH) {
                            val srcRowPtr = bufPixels + ((top + dy) * bufW + left) * 4
                            platform.posix.memcpy(dstBase + (dy * drawW * 4), srcRowPtr, dstRowBytes)
                        }
                    }
                    canvasWidget.attributes["_inlineScaledSwap"] = swapCount
                    canvasWidget.attributes["_inlineScaledW"] = drawW
                    canvasWidget.attributes["_inlineScaledH"] = drawH
                    return
                }
                val sizeJustChanged = prevScaledW != 0 && (drawW != prevScaledW || drawH != prevScaledH)
                if (sizeJustChanged && canWriteDirectToBackBuffer && bufPixels != null) {
                    canvasWidget.attributes["_inlineScaledW"] = drawW
                    canvasWidget.attributes["_inlineScaledH"] = drawH
                    canvasWidget.attributes["_inlineScaledSwap"] = -1L
                    flushGdiIfOverlapping(left, top, left + drawW, top + drawH)
                    markFrameBufferDirty(left, top, left + drawW, top + drawH)
                    if (sharedResampleSrcRow0.size < surfW) sharedResampleSrcRow0 = IntArray(max(surfW, 4096))
                    if (sharedResampleDstRow.size < drawW) sharedResampleDstRow = IntArray(max(drawW, 4096))
                    ensureResampleHorizontalTables(surfW, drawW)
                    val xMap = cachedResampleXMap
                    val row0Arr = sharedResampleSrcRow0
                    val dstRowArr = sharedResampleDstRow
                    val srcRowBytes = (surfW * 4).toULong()
                    val dstRowBytes = (drawW * 4).toULong()
                    row0Arr.usePinned { row0Pin ->
                        dstRowArr.usePinned { dstRowPin ->
                            val row0Ptr = row0Pin.addressOf(0)
                            val dstRowPtr = dstRowPin.addressOf(0)
                            var loadedSy = -1
                            for (dy in 0 until drawH) {
                                val sy = (dy * surfH) / drawH
                                if (sy != loadedSy) {
                                    platform.posix.memcpy(row0Ptr, srcBytePtr + (sy * surfW * 4), srcRowBytes)
                                    loadedSy = sy
                                }
                                for (dx in 0 until drawW) {
                                    dstRowArr[dx] = row0Arr[xMap[dx]]
                                }
                                val dstRowBackPtr = bufPixels + ((top + dy) * bufW + left) * 4
                                platform.posix.memcpy(dstRowBackPtr, dstRowPtr, dstRowBytes)
                            }
                        }
                    }
                    if (!suppressBorder) {
                        drawRectBorder(hdc, left, top, right, bottom, theme.borderSubtle)
                    }
                    return
                }
                if (useAntialiasing && !sizeJustChanged && drawW in 1..4096 && drawH in 1..4096) {
                    val neededBytes = drawW * drawH * 4
                    val targetArray = if (cachedScaled != null && cachedScaled.size == neededBytes) {
                        cachedScaled
                    } else {
                        ByteArray(neededBytes).also { canvasWidget.attributes["_inlineScaledBgra"] = it }
                    }
                    if (drawW == surfW && drawH == surfH) {
                        targetArray.usePinned { pinned ->
                            platform.posix.memcpy(pinned.addressOf(0), srcBytePtr, neededBytes.toULong())
                        }
                        blitScaledBgraToHdc(hdc, left, top, drawW, drawH, targetArray)
                    } else if (canWriteDirectToBackBuffer && bufPixels != null) {
                        flushGdiIfOverlapping(left, top, left + drawW, top + drawH)
                        markFrameBufferDirty(left, top, left + drawW, top + drawH)
                        resampleBgraBilinearFromPtr(
                            srcPtr = srcBytePtr,
                            srcW = surfW,
                            srcH = surfH,
                            dstW = drawW,
                            dstH = drawH,
                            out = targetArray,
                            directDstPixels = bufPixels,
                            directDstBufW = bufW,
                            directLeft = left,
                            directTop = top
                        )
                    } else {
                        resampleBgraBilinearFromPtr(srcBytePtr, surfW, surfH, drawW, drawH, targetArray)
                        blitScaledBgraToHdc(hdc, left, top, drawW, drawH, targetArray)
                    }
                    canvasWidget.attributes["_inlineScaledSwap"] = swapCount
                    canvasWidget.attributes["_inlineScaledW"] = drawW
                    canvasWidget.attributes["_inlineScaledH"] = drawH
                    if (!suppressBorder) {
                        drawRectBorder(hdc, left, top, right, bottom, theme.borderSubtle)
                    }
                    return
                }
                memScoped {
                    val bmi = alloc<BITMAPINFO>()
                    bmi.bmiHeader.biSize = sizeOf<BITMAPINFOHEADER>().toUInt()
                    bmi.bmiHeader.biWidth = surfW
                    bmi.bmiHeader.biHeight = -surfH
                    bmi.bmiHeader.biPlanes = 1u
                    bmi.bmiHeader.biBitCount = 32u
                    bmi.bmiHeader.biCompression = BI_RGB.toUInt()

                    SetStretchBltMode(hdc, if (sizeJustChanged) COLORONCOLOR else HALFTONE)
                    SetBrushOrgEx(hdc, 0, 0, null)
                    StretchDIBits(
                        hdc,
                        left,
                        top,
                        drawW,
                        drawH,
                        0,
                        0,
                        surfW,
                        surfH,
                        bgraPtr,
                        bmi.ptr,
                        DIB_RGB_COLORS.toUInt(),
                        SRCCOPY
                    )
                }
                GdiFlush()
                markFrameBufferDirty(left, top, left + drawW, top + drawH)
                markGdiRectDirty(left, top, left + drawW, top + drawH)
                if (!suppressBorder) {
                    drawRectBorder(hdc, left, top, right, bottom, theme.borderSubtle)
                }
                return
            }
        }

        val prevRawSwap = (canvasWidget.attributes["_inlineRawSwap"] as? Long) ?: -2L
        val prevRawBgra = canvasWidget.attributes["_inlineRawBgra"] as? ByteArray
        val bgra = if (swapCount >= 0L && swapCount == prevRawSwap && prevRawBgra != null && prevRawBgra.size >= surfW * surfH * 4) {
            prevRawBgra
        } else {
            runCatching { captureBgraFn?.invoke() }.getOrNull()?.also { fetched ->
                if (fetched.size >= surfW * surfH * 4) {
                    canvasWidget.attributes["_inlineRawSwap"] = swapCount
                    canvasWidget.attributes["_inlineRawBgra"] = fetched
                }
            }
        }
        if (bgra != null && bgra.size >= surfW * surfH * 4) {
            val sizeJustChanged = prevScaledW != 0 && (drawW != prevScaledW || drawH != prevScaledH)
            if (sizeJustChanged) {
                canvasWidget.attributes["_inlineScaledW"] = drawW
                canvasWidget.attributes["_inlineScaledH"] = drawH
                canvasWidget.attributes["_inlineScaledSwap"] = -1L
                val bufPixels = if (hdc != null && hdc == activeBackBufferDc && activeBackBufferPixels != null) activeBackBufferPixels else cachedMainBackBufferPixels
                val bufW = if (hdc != null && hdc == activeBackBufferDc && activeBackBufferPixels != null) activeBackBufferWidth else cachedMainBackBufferWidth
                val bufH = if (hdc != null && hdc == activeBackBufferDc && activeBackBufferPixels != null) activeBackBufferHeight else cachedMainBackBufferHeight
                if (bufPixels != null && left >= 0 && top >= 0 && left + drawW <= bufW && top + drawH <= bufH) {
                    flushGdiIfOverlapping(left, top, left + drawW, top + drawH)
                    markFrameBufferDirty(left, top, left + drawW, top + drawH)
                    val dstWords = bufPixels.reinterpret<UIntVar>()
                    val xMap = IntArray(drawW) { dx -> (dx * surfW) / drawW }
                    bgra.usePinned { pinned ->
                        val srcWords = pinned.addressOf(0).reinterpret<UIntVar>()
                        for (dy in 0 until drawH) {
                            val sy = (dy * surfH) / drawH
                            val srcRowOff = sy * surfW
                            val dstRowOff = (top + dy) * bufW + left
                            for (dx in 0 until drawW) {
                                dstWords[dstRowOff + dx] = srcWords[srcRowOff + xMap[dx]]
                            }
                        }
                    }
                    if (!suppressBorder) {
                        drawRectBorder(hdc, left, top, right, bottom, theme.borderSubtle)
                    }
                    return
                }
            }
            if (useAntialiasing && drawW in 1..4096 && drawH in 1..4096) {
                val neededBytes = drawW * drawH * 4
                val targetArray = if (cachedScaled != null && cachedScaled.size == neededBytes) {
                    cachedScaled
                } else {
                    ByteArray(neededBytes).also { canvasWidget.attributes["_inlineScaledBgra"] = it }
                }
                if (drawW == surfW && drawH == surfH) {
                    bgra.copyInto(targetArray, 0, 0, neededBytes)
                } else {
                    bgra.usePinned { pinned ->
                        resampleBgraBilinearFromPtr(pinned.addressOf(0), surfW, surfH, drawW, drawH, targetArray)
                    }
                }
                canvasWidget.attributes["_inlineScaledSwap"] = swapCount
                canvasWidget.attributes["_inlineScaledW"] = drawW
                canvasWidget.attributes["_inlineScaledH"] = drawH
                blitScaledBgraToHdc(hdc, left, top, drawW, drawH, targetArray)
                if (!suppressBorder) {
                    drawRectBorder(hdc, left, top, right, bottom, theme.borderSubtle)
                }
                return
            }
            memScoped {
                val bmi = alloc<BITMAPINFO>()
                bmi.bmiHeader.biSize = sizeOf<BITMAPINFOHEADER>().toUInt()
                bmi.bmiHeader.biWidth = surfW
                bmi.bmiHeader.biHeight = -surfH
                bmi.bmiHeader.biPlanes = 1u
                bmi.bmiHeader.biBitCount = 32u
                bmi.bmiHeader.biCompression = BI_RGB.toUInt()

                SetStretchBltMode(hdc, HALFTONE)
                SetBrushOrgEx(hdc, 0, 0, null)
                bgra.usePinned { pinned ->
                    StretchDIBits(
                        hdc,
                        left,
                        top,
                        drawW,
                        drawH,
                        0,
                        0,
                        surfW,
                        surfH,
                        pinned.addressOf(0),
                        bmi.ptr,
                        DIB_RGB_COLORS.toUInt(),
                        SRCCOPY
                    )
                }
            }
            GdiFlush()
            markFrameBufferDirty(left, top, left + drawW, top + drawH)
            markGdiRectDirty(left, top, left + drawW, top + drawH)
        } else {
            fillRectColor(hdc, left, top, right, bottom, rgb(14, 18, 24))
        }
        if (!suppressBorder) {
            drawRectBorder(hdc, left, top, right, bottom, theme.borderSubtle)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun renderGeneralSurfaceViewport(
        hdc: HDC?,
        canvasWidget: DesktopNativeWidget,
        clientW: Int,
        clientH: Int,
        bodyTop: Int = 0
    ) {
        val viewportH = (clientH - bodyTop).coerceAtLeast(1)
        activeCanvasImpl = canvasWidget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
        val surfWFn = canvasWidget.attributes["surfaceWidth"] as? (() -> Int)
        val surfHFn = canvasWidget.attributes["surfaceHeight"] as? (() -> Int)
        val resizeFn = canvasWidget.attributes["resizeSurface"] as? ((Int, Int) -> Unit)
        val captureBgraPtrFn = canvasWidget.attributes["captureBgraPtr"] as? (() -> Pair<COpaquePointer?, Int>)
        val captureBgraFn = canvasWidget.attributes["captureBgra"] as? (() -> ByteArray)

        val curW = (surfWFn?.invoke() ?: clientW).coerceAtLeast(1)
        val curH = (surfHFn?.invoke() ?: viewportH).coerceAtLeast(1)
        if (curW != clientW || curH != viewportH) {
            runCatching { resizeFn?.invoke(clientW, viewportH) }
        }

        val surfW = (surfWFn?.invoke() ?: clientW).coerceAtLeast(1)
        val surfH = (surfHFn?.invoke() ?: viewportH).coerceAtLeast(1)
        activeCanvasSurfaceSize = surfW to surfH
        canvasViewportRect = intArrayOf(0, bodyTop, clientW, clientH)

        val swapCount: Long = when (val sc = canvasWidget.attributes["swapCount"]) {
            is Number -> sc.toLong()
            is Function0<*> -> (sc.invoke() as? Number)?.toLong() ?: -1L
            else -> -1L
        }
        val prevViewportSwap = (canvasWidget.attributes["_viewportSwap"] as? Long) ?: -2L
        if (swapCount >= 0L && swapCount != prevViewportSwap) {
            canvasWidget.attributes["_viewportSwap"] = swapCount
            runCatching {
                (canvasWidget.attributes["onBeforePresentFrame"] as? ((Long, Double?) -> Unit))?.invoke(swapCount, null)
            }
        }

        if (captureBgraPtrFn != null) {
            val (bgraPtr, byteLen) = runCatching { captureBgraPtrFn.invoke() }.getOrNull() ?: (null to 0)
            if (bgraPtr != null && byteLen >= surfW * surfH * 4) {
                memScoped {
                    val bmi = alloc<BITMAPINFO>()
                    bmi.bmiHeader.biSize = sizeOf<BITMAPINFOHEADER>().toUInt()
                    bmi.bmiHeader.biWidth = surfW
                    bmi.bmiHeader.biHeight = -surfH
                    bmi.bmiHeader.biPlanes = 1u
                    bmi.bmiHeader.biBitCount = 32u
                    bmi.bmiHeader.biCompression = BI_RGB.toUInt()

                    SetStretchBltMode(hdc, HALFTONE)
                    SetBrushOrgEx(hdc, 0, 0, null)
                    StretchDIBits(
                        hdc,
                        0,
                        bodyTop,
                        clientW,
                        viewportH,
                        0,
                        0,
                        surfW,
                        surfH,
                        bgraPtr,
                        bmi.ptr,
                        DIB_RGB_COLORS.toUInt(),
                        SRCCOPY
                    )
                }
                GdiFlush()
                markFrameBufferDirty(0, bodyTop, clientW, bodyTop + viewportH)
                markGdiRectDirty(0, bodyTop, clientW, bodyTop + viewportH)
                return
            }
        }

        val bgra = captureBgraFn?.invoke()
        if (bgra != null && bgra.size >= surfW * surfH * 4) {
            memScoped {
                val bmi = alloc<BITMAPINFO>()
                bmi.bmiHeader.biSize = sizeOf<BITMAPINFOHEADER>().toUInt()
                bmi.bmiHeader.biWidth = surfW
                bmi.bmiHeader.biHeight = -surfH
                bmi.bmiHeader.biPlanes = 1u
                bmi.bmiHeader.biBitCount = 32u
                bmi.bmiHeader.biCompression = BI_RGB.toUInt()

                SetStretchBltMode(hdc, HALFTONE)
                SetBrushOrgEx(hdc, 0, 0, null)
                bgra.usePinned { pinned ->
                    StretchDIBits(
                        hdc,
                        0,
                        bodyTop,
                        clientW,
                        viewportH,
                        0,
                        0,
                        surfW,
                        surfH,
                        pinned.addressOf(0),
                        bmi.ptr,
                        DIB_RGB_COLORS.toUInt(),
                        SRCCOPY
                    )
                }
            }
            GdiFlush()
            markFrameBufferDirty(0, bodyTop, clientW, bodyTop + viewportH)
            markGdiRectDirty(0, bodyTop, clientW, bodyTop + viewportH)
        }
    }

    private fun findFirstScrollView(root: DesktopNativeWidget?): DesktopScrollViewNativeImpl<*>? {
        if (root == null || !root.visible || root.disposed) return null
        val mutVer = root.subtreeMutationVersion
        if (root.attributes["cachedScrollViewVer"] as? Long == mutVer) {
            return root.attributes["cachedScrollViewRef"] as? DesktopScrollViewNativeImpl<*>
        }
        val found = findFirstScrollViewUncached(root)
        root.attributes["cachedScrollViewVer"] = mutVer
        if (found != null) {
            root.attributes["cachedScrollViewRef"] = found
        } else {
            root.attributes.remove("cachedScrollViewRef")
        }
        return found
    }

    private fun findFirstScrollViewUncached(root: DesktopNativeWidget?): DesktopScrollViewNativeImpl<*>? {
        if (root == null || !root.visible || root.disposed) return null
        val impl = root.attributes["nativeImpl"]
        if (impl is DesktopScrollViewNativeImpl<*>) return impl
        val list = root.children
        var i = 0
        while (i < list.size) {
            val child = list.getOrNull(i)
            if (child != null) {
                val found = findFirstScrollViewUncached(child)
                if (found != null) return found
            }
            i++
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun getCachedDirectOrNestedTexts(widget: DesktopNativeWidget): List<DesktopTextNativeImpl<*>> {
        val mutVer = widget.subtreeMutationVersion
        val cachedVer = widget.attributes["cachedTextsMutVer"] as? Long
        val cachedList = widget.attributes["cachedTextsList"] as? List<DesktopTextNativeImpl<*>>
        if (cachedVer == mutVer && cachedList != null) {
            return cachedList
        }
        val collected = collectDirectOrNestedTexts(widget)
        widget.attributes["cachedTextsMutVer"] = mutVer
        widget.attributes["cachedTextsList"] = collected
        return collected
    }

    @Suppress("UNCHECKED_CAST")
    private fun getCachedFirstButton(widget: DesktopNativeWidget): DesktopButtonNativeImpl<*>? {
        val mutVer = widget.subtreeMutationVersion
        val cachedVer = widget.attributes["cachedFirstBtnMutVer"] as? Long
        if (cachedVer == mutVer && widget.attributes.containsKey("cachedFirstBtnPresent")) {
            return widget.attributes["cachedFirstBtnRef"] as? DesktopButtonNativeImpl<*>
        }
        val firstBtn = collectDirectOrNestedButtons(widget).firstOrNull()
        widget.attributes["cachedFirstBtnMutVer"] = mutVer
        widget.attributes["cachedFirstBtnPresent"] = firstBtn != null
        if (firstBtn != null) {
            widget.attributes["cachedFirstBtnRef"] = firstBtn
        } else {
            widget.attributes.remove("cachedFirstBtnRef")
        }
        return firstBtn
    }

    @Suppress("UNCHECKED_CAST")
    private fun renderBootstrapCardPageLayout(
        hwnd: HWND,
        hdc: HDC?,
        theme: SystemThemePalette,
        fontSmall: HFONT?,
        fontSmallBold: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        fontTitle: HFONT?,
        fontMetricBig: HFONT?,
        cardPageRoot: DesktopNativeWidget,
        clientW: Int,
        clientH: Int,
        bodyTop: Int = 0
    ) {
        val scrollViewImpl = findFirstScrollView(cardPageRoot)
        activeCardPageScrollViewImpl = scrollViewImpl
        if (scrollViewImpl != null) {
            val reqVer = scrollViewImpl.scrollRequestVersion
            if (reqVer != cardPageLastScrollRequestVersion) {
                cardPageLastScrollRequestVersion = reqVer
                cardPageScrollY = scrollViewImpl.currentScrollY()
            }
        }

        val rootMutVer = cardPageRoot.subtreeMutationVersion
        val pageHost: DesktopNativeWidget
        val currentPage: DesktopNativeWidget
        val outerCard: DesktopNativeWidget
        val cardBody: DesktopNativeWidget
        val cachedNodesVer = cardPageRoot.attributes["cachedCardPageNodesVer"] as? Long
        val cachedPageHost = cardPageRoot.attributes["cachedPageHostRef"] as? DesktopNativeWidget
        val cachedCurrentPage = cardPageRoot.attributes["cachedCurrentPageRef"] as? DesktopNativeWidget
        val cachedOuterCard = cardPageRoot.attributes["cachedOuterCardRef"] as? DesktopNativeWidget
        val cachedCardBody = cardPageRoot.attributes["cachedCardBodyRef"] as? DesktopNativeWidget
        if (cachedNodesVer == rootMutVer &&
            cachedPageHost != null &&
            cachedCurrentPage != null &&
            cachedOuterCard != null &&
            cachedCardBody != null
        ) {
            pageHost = cachedPageHost
            currentPage = cachedCurrentPage
            outerCard = cachedOuterCard
            cardBody = cachedCardBody
        } else {
            pageHost = findWidgetByClass(cardPageRoot, "page-host") ?: cardPageRoot
            currentPage = pageHost.children.lastOrNull { it.visible && !it.disposed } ?: pageHost
            outerCard = currentPage.children.firstOrNull { widgetHasClass(it, "card") }
                ?: findWidgetByClass(currentPage, "card")
                ?: currentPage
            cardBody = outerCard.children.firstOrNull { widgetHasClass(it, "card-body") }
                ?: findWidgetByClass(outerCard, "card-body")
                ?: outerCard
            cardPageRoot.attributes["cachedCardPageNodesVer"] = rootMutVer
            cardPageRoot.attributes["cachedPageHostRef"] = pageHost
            cardPageRoot.attributes["cachedCurrentPageRef"] = currentPage
            cardPageRoot.attributes["cachedOuterCardRef"] = outerCard
            cardPageRoot.attributes["cachedCardBodyRef"] = cardBody
        }

        val pageKey = "${currentPage.hashCode()}:${(currentPage.attributes["bootstrapClass"] as? String).orEmpty()}"
        if (pageKey != cardPageLastKey) {
            cardPageLastKey = pageKey
            cardPageScrollY = 0
            scrollViewImpl?.updateScrollPositionFromHost(0, 0)
        }

        // Outer Card Container (starts directly below the window titlebar)
        val outerMarginX = max(16, (clientW - 1220) / 2)
        val outerLeft = outerMarginX
        val outerRight = clientW - outerMarginX
        val outerTop = bodyTop + 12
        val outerBottom = clientH - 12

        val innerLeft = outerLeft + 20
        val scrollRailW = 18
        val innerRight = outerRight - 20 - scrollRailW
        val innerW = max(240, innerRight - innerLeft)

        // Case A: Inner Cards Grid (Index, AssetManager, ItemMenu, DBMenu, Templates)
        val bodyMutVer = cardBody.subtreeMutationVersion
        val cachedInnerCardsVer = cardBody.attributes["cachedInnerCardsVer"] as? Long
        val cachedInnerCards = cardBody.attributes["cachedInnerCardsList"] as? List<DesktopNativeWidget>
        val innerCards: List<DesktopNativeWidget> = if (cachedInnerCardsVer == bodyMutVer && cachedInnerCards != null) {
            cachedInnerCards
        } else {
            val rawInnerCards = mutableListOf<DesktopNativeWidget>()
            fun collectRawInnerCards(node: DesktopNativeWidget) {
                if (!node.visible || node.disposed) return
                if (node !== outerCard && widgetHasClass(node, "card")) {
                    rawInnerCards.add(node)
                    return
                }
                for (c in node.children) collectRawInnerCards(c)
            }
            collectRawInnerCards(cardBody)

            val isMenuTileGrid = rawInnerCards.isNotEmpty() && rawInnerCards.all { cardNode ->
                findFirstTextInput(cardNode) == null &&
                    findFirstSelect(cardNode) == null &&
                    findFirstCheckbox(cardNode) == null &&
                    findFirstPixelSurface(cardNode) == null &&
                    collectDirectOrNestedButtons(cardNode).size <= 1
            }
            val resolved = if (isMenuTileGrid) rawInnerCards else emptyList()
            cardBody.attributes["cachedInnerCardsVer"] = bodyMutVer
            cardBody.attributes["cachedInnerCardsList"] = resolved
            resolved
        }

        // Extract direct header texts (h1, h5) from cardBody
        var mainHeading: String? = null
        var subHeading: String? = null
        for (child in cardBody.children) {
            val txt = (child.attributes["nativeImpl"] as? DesktopTextNativeImpl<*>)?.getText()?.trim()
            if (!txt.isNullOrEmpty()) {
                if (mainHeading == null) {
                    mainHeading = txt
                } else if (subHeading == null) {
                    subHeading = txt
                    break
                }
            }
        }

        val alertModal = cardPageRoot.children.firstOrNull { widgetHasClass(it, "modal") && it.visible }
        val outerFrameValid = hwnd == activeHwnd &&
            hdc == cachedMainMemDc &&
            activeBackBufferPixels != null &&
            alertModal == null &&
            hoveredTooltip.isNullOrBlank() &&
            innerCards.isNotEmpty() &&
            cachedCardPageFrameHwnd == hwnd.toLong() &&
            cachedCardPageFrameRootVer == rootMutVer &&
            cachedCardPageFrameW == clientW &&
            cachedCardPageFrameH == clientH &&
            cachedCardPageFrameBodyTop == bodyTop &&
            cachedCardPageFrameDark == theme.isDark &&
            cachedCardPageFramePage === currentPage

        var headerBottom = outerTop + 14
        if (outerFrameValid) {
            headerBottom = cachedCardPageFrameHeaderBottom
        } else {
            drawRoundedRect(
                hdc = hdc,
                left = outerLeft,
                top = outerTop,
                right = outerRight,
                bottom = outerBottom,
                radius = 8,
                fillColor = theme.bgPanel,
                borderColor = theme.borderSubtle
            )
            if (!mainHeading.isNullOrEmpty()) {
                SelectObject(hdc, fontMetricBig)
                drawTextInRect(
                    hdc = hdc,
                    text = mainHeading,
                    left = innerLeft,
                    top = headerBottom,
                    right = innerRight,
                    bottom = headerBottom + 32,
                    colorRef = theme.textPrimary,
                    center = true
                )
                headerBottom += 34
            }
            if (!subHeading.isNullOrEmpty()) {
                SelectObject(hdc, fontNormal)
                drawTextInRect(
                    hdc = hdc,
                    text = subHeading,
                    left = innerLeft,
                    top = headerBottom,
                    right = innerRight,
                    bottom = headerBottom + 22,
                    colorRef = theme.textSecondary,
                    center = true
                )
                headerBottom += 26
            }
            drawLineColor(hdc, innerLeft, headerBottom, innerRight, headerBottom, theme.borderSubtle)
            if (hwnd == activeHwnd && hdc == cachedMainMemDc && alertModal == null && hoveredTooltip.isNullOrBlank() && innerCards.isNotEmpty()) {
                cachedCardPageFrameHwnd = hwnd.toLong()
                cachedCardPageFrameRootVer = rootMutVer
                cachedCardPageFrameW = clientW
                cachedCardPageFrameH = clientH
                cachedCardPageFrameBodyTop = bodyTop
                cachedCardPageFrameDark = theme.isDark
                cachedCardPageFramePage = currentPage
                cachedCardPageFrameHeaderBottom = headerBottom
            } else {
                cachedCardPageFrameRootVer = -1L
            }
        }
        val viewportTop = headerBottom + 10
        val viewportBottom = outerBottom - 12
        val viewportH = max(80, viewportBottom - viewportTop)

        if (outerFrameValid) {
            fillRectColor(hdc, innerRight, viewportTop, outerRight - 2, viewportBottom, theme.bgPanel)
        }

        val useGdiViewportClip = innerCards.isEmpty()
        val savedDc = if (useGdiViewportClip) {
            pushClipRect(hdc, innerLeft - 4, viewportTop, innerRight + 4, viewportBottom)
        } else {
            softClipDepth = 1
            softClipLeft = innerLeft - 4
            softClipTop = viewportTop
            softClipRight = innerRight + 4
            softClipBottom = viewportBottom
            0
        }

        var contentY = viewportTop - cardPageScrollY
        val renderedWidgets = mutableSetOf<DesktopNativeWidget>()

        fun addClippedHitRegion(
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            widget: DesktopNativeWidget? = null,
            isTextInput: Boolean = false,
            onClick: ((Int, Int) -> Unit)? = null
        ) {
            val cTop = max(top, viewportTop)
            val cBottom = min(bottom, viewportBottom)
            if (cBottom > cTop) {
                hitRegions.add(
                    HitRegion(
                        left = left,
                        top = cTop,
                        right = right,
                        bottom = cBottom,
                        widget = widget,
                        isTextInput = isTextInput,
                        onClick = onClick
                    )
                )
            }
        }

        if (innerCards.isNotEmpty()) {
            val cols = when {
                innerW >= 780 -> 3
                innerW >= 500 -> 2
                else -> 1
            }
            val gap = 12
            val cardW = (innerW - gap * (cols - 1)) / cols
            val cardH = 128
            val rowStride = cardH + gap
            val totalRows = (innerCards.size + cols - 1) / cols
            val totalCardGridH = totalRows * rowStride

            val canUseGridSurfaceCache = totalCardGridH in 1..8192 && innerW in 64..4096 && activeBackBufferPixels != null
            var blittedFromCache = false
            if (canUseGridSurfaceCache) {
                val cacheValid = cachedCardGridWidget === cardBody &&
                    cachedCardGridMutVer == bodyMutVer &&
                    cachedCardGridW == innerW &&
                    cachedCardGridH == totalCardGridH &&
                    cachedCardGridDark == theme.isDark &&
                    cachedCardGridPixels != null
                if (!cacheValid) {
                    val gridDc = obtainCardGridBackBuffer(hdc, innerW, totalCardGridH)
                    val gridPixels = cachedCardGridPixels
                    if (gridDc != null && gridPixels != null) {
                        val prevDc = activeBackBufferDc
                        val prevPixels = activeBackBufferPixels
                        val prevW = activeBackBufferWidth
                        val prevH = activeBackBufferHeight
                        val prevClipDepth = softClipDepth
                        val prevClipL = softClipLeft
                        val prevClipT = softClipTop
                        val prevClipR = softClipRight
                        val prevClipB = softClipBottom

                        activeBackBufferDc = gridDc
                        activeBackBufferPixels = gridPixels
                        activeBackBufferWidth = innerW
                        activeBackBufferHeight = totalCardGridH
                        resetSoftClipRect()
                        val oldGridFont = SelectObject(gridDc, fontNormal)
                        SetBkMode(gridDc, TRANSPARENT)
                        resetGdiDirtyRect()

                        fillRectColor(gridDc, 0, 0, innerW, totalCardGridH, theme.bgPanel)

                        // Pass 1 on offscreen surface: all card & unhovered button backgrounds
                        innerCards.forEachIndexed { idx, cardWidget ->
                            val col = idx % cols
                            val row = idx / cols
                            val cx = col * (cardW + gap)
                            val cy = row * rowStride
                            drawRoundedRect(
                                hdc = gridDc,
                                left = cx,
                                top = cy,
                                right = cx + cardW,
                                bottom = cy + cardH,
                                radius = 6,
                                fillColor = theme.bgCard,
                                borderColor = theme.borderSubtle
                            )
                            val cardBtn = getCachedFirstButton(cardWidget)
                            if (cardBtn != null) {
                                val btnTop = cy + cardH - 38
                                val btnBottom = cy + cardH - 10
                                drawRoundedRect(
                                    hdc = gridDc,
                                    left = cx + 12,
                                    top = btnTop,
                                    right = cx + cardW - 12,
                                    bottom = btnBottom,
                                    radius = 5,
                                    fillColor = theme.accentBlue,
                                    borderColor = theme.accentBlueBorder
                                )
                            }
                        }

                        // Pass 2 on offscreen surface: all card titles, descriptions, & button labels
                        innerCards.forEachIndexed { idx, cardWidget ->
                            val col = idx % cols
                            val row = idx / cols
                            val cx = col * (cardW + gap)
                            val cy = row * rowStride
                            val cardTextImpls = getCachedDirectOrNestedTexts(cardWidget)
                            var cTitle = ""
                            var cDesc = ""
                            for (tImpl in cardTextImpls) {
                                val str = tImpl.getText().trim()
                                if (str.isNotEmpty()) {
                                    if (cTitle.isEmpty()) {
                                        cTitle = str
                                    } else if (cDesc.isEmpty()) {
                                        cDesc = str
                                        break
                                    }
                                }
                            }
                            if (cTitle.isNotEmpty()) {
                                SelectObject(gridDc, fontBold)
                                drawTextInRect(
                                    hdc = gridDc,
                                    text = cTitle,
                                    left = cx + 10,
                                    top = cy + 10,
                                    right = cx + cardW - 10,
                                    bottom = cy + 32,
                                    colorRef = theme.textPrimary,
                                    center = true
                                )
                            }
                            if (cDesc.isNotEmpty()) {
                                SelectObject(gridDc, fontSmall)
                                drawTextInRect(
                                    hdc = gridDc,
                                    text = cDesc,
                                    left = cx + 10,
                                    top = cy + 36,
                                    right = cx + cardW - 10,
                                    bottom = cy + 78,
                                    colorRef = theme.textSecondary,
                                    center = true
                                )
                            }
                            val cardBtn = getCachedFirstButton(cardWidget)
                            if (cardBtn != null) {
                                val btnTop = cy + cardH - 38
                                val btnBottom = cy + cardH - 10
                                drawRoundedButtonForeground(
                                    hdc = gridDc,
                                    font = fontSmallBold,
                                    label = cardBtn.getText().ifBlank { "Open" },
                                    left = cx + 12,
                                    top = btnTop,
                                    right = cx + cardW - 12,
                                    bottom = btnBottom,
                                    fg = rgb(255, 255, 255)
                                )
                            }
                        }

                        GdiFlush()
                        resetGdiDirtyRect()
                        SelectObject(gridDc, oldGridFont)

                        activeBackBufferDc = prevDc
                        activeBackBufferPixels = prevPixels
                        activeBackBufferWidth = prevW
                        activeBackBufferHeight = prevH
                        softClipDepth = prevClipDepth
                        softClipLeft = prevClipL
                        softClipTop = prevClipT
                        softClipRight = prevClipR
                        softClipBottom = prevClipB

                        cachedCardGridWidget = cardBody
                        cachedCardGridMutVer = bodyMutVer
                        cachedCardGridW = innerW
                        cachedCardGridH = totalCardGridH
                        cachedCardGridDark = theme.isDark
                    }
                }

                val srcPixels = cachedCardGridPixels
                val dstPixels = activeBackBufferPixels
                val dstW = activeBackBufferWidth
                val dstH = activeBackBufferHeight
                if (srcPixels != null && dstPixels != null && dstW > 0 && dstH > 0) {
                    val visTop = max(viewportTop, contentY).coerceIn(0, dstH)
                    val visBottom = min(viewportBottom, contentY + totalCardGridH).coerceIn(visTop, dstH)
                    val copyLeft = innerLeft.coerceIn(0, dstW)
                    val copyRight = (innerLeft + innerW).coerceIn(copyLeft, dstW)
                    val copyW = copyRight - copyLeft
                    if (visBottom > visTop && copyW > 0) {
                        if (gdiDirtySinceLastFlush) {
                            GdiFlush()
                            resetGdiDirtyRect()
                        }
                        val srcStartRow = (visTop - contentY).coerceIn(0, totalCardGridH)
                        val rowCount = (visBottom - visTop).coerceAtMost(totalCardGridH - srcStartRow)
                        val rowBytes = (copyW * 4).toULong()
                        for (r in 0 until rowCount) {
                            platform.posix.memcpy(
                                dstPixels + (((visTop + r) * dstW + copyLeft) * 4),
                                srcPixels + (((srcStartRow + r) * innerW) * 4),
                                rowBytes
                            )
                        }
                        if (visBottom < viewportBottom) {
                            fillRectColor(hdc, copyLeft, visBottom, copyRight, viewportBottom, theme.bgPanel)
                        }
                    } else if (outerFrameValid) {
                        fillRectColor(hdc, copyLeft, viewportTop, copyRight, viewportBottom, theme.bgPanel)
                    }

                    val firstVisRow = ((viewportTop - 20 - contentY) / rowStride).coerceAtLeast(0)
                    val lastVisRow = ((viewportBottom + 20 - contentY) / rowStride).coerceAtMost(totalRows - 1)
                    if (lastVisRow >= firstVisRow) {
                        val startIdx = firstVisRow * cols
                        val endIdxExclusive = min(innerCards.size, (lastVisRow + 1) * cols)
                        for (idx in startIdx until endIdxExclusive) {
                            val cardWidget = innerCards[idx]
                            val col = idx % cols
                            val row = idx / cols
                            val cx = innerLeft + col * (cardW + gap)
                            val cy = contentY + row * rowStride
                            val cardBtn = getCachedFirstButton(cardWidget)
                            if (cardBtn != null) {
                                val btnLeft = cx + 12
                                val btnRight = cx + cardW - 12
                                val btnTop = cy + cardH - 38
                                val btnBottom = cy + cardH - 10
                                addClippedHitRegion(
                                    left = btnLeft,
                                    top = btnTop,
                                    right = btnRight,
                                    bottom = btnBottom,
                                    widget = cardBtn.nativeWidget,
                                    onClick = { _, _ -> cardBtn.dispatchClick() }
                                )
                                val btnWidget = cardBtn.nativeWidget
                                val isBtnHovered = hoveredWidget == btnWidget ||
                                    isRectHovered(hwnd, btnLeft, btnTop, btnRight, btnBottom)
                                val isBtnPressed = pressedWidget == btnWidget
                                if (isBtnHovered || isBtnPressed) {
                                    val needsGdiClip = btnTop < viewportTop || btnBottom > viewportBottom
                                    val btnClipDc = if (needsGdiClip && hdc != null) {
                                        val s = SaveDC(hdc)
                                        IntersectClipRect(hdc, innerLeft - 4, viewportTop, innerRight + 4, viewportBottom)
                                        s
                                    } else 0
                                    drawRoundedButton(
                                        hdc = hdc,
                                        font = fontSmallBold,
                                        label = cardBtn.getText().ifBlank { "Open" },
                                        left = btnLeft,
                                        top = btnTop,
                                        right = btnRight,
                                        bottom = btnBottom,
                                        bg = theme.accentBlue,
                                        border = theme.accentBlueBorder,
                                        fg = rgb(255, 255, 255),
                                        radius = 5,
                                        widget = btnWidget,
                                        hwndForHover = hwnd
                                    )
                                    if (btnClipDc != 0 && hdc != null) {
                                        RestoreDC(hdc, btnClipDc)
                                    }
                                }
                            }
                        }
                    }
                    blittedFromCache = true
                }
            }

            if (!blittedFromCache) {
                // Fallback direct 2-pass rendering when surface cache is not applicable
                innerCards.forEachIndexed { idx, cardWidget ->
                    val col = idx % cols
                    val row = idx / cols
                    val cx = innerLeft + col * (cardW + gap)
                    val cy = contentY + row * rowStride

                    if (cy + cardH >= viewportTop - 20 && cy <= viewportBottom + 20) {
                        drawRoundedRect(
                            hdc = hdc,
                            left = cx,
                            top = cy,
                            right = cx + cardW,
                            bottom = cy + cardH,
                            radius = 6,
                            fillColor = theme.bgCard,
                            borderColor = theme.borderSubtle
                        )
                        val cardBtn = getCachedFirstButton(cardWidget)
                        if (cardBtn != null) {
                            val btnTop = cy + cardH - 38
                            val btnBottom = cy + cardH - 10
                            drawRoundedButtonBackground(
                                hdc = hdc,
                                left = cx + 12,
                                top = btnTop,
                                right = cx + cardW - 12,
                                bottom = btnBottom,
                                bg = theme.accentBlue,
                                border = theme.accentBlueBorder,
                                radius = 5,
                                widget = cardBtn.nativeWidget,
                                hwndForHover = hwnd
                            )
                            addClippedHitRegion(
                                left = cx + 12,
                                top = btnTop,
                                right = cx + cardW - 12,
                                bottom = btnBottom,
                                widget = cardBtn.nativeWidget,
                                onClick = { _, _ -> cardBtn.dispatchClick() }
                            )
                        }
                    }
                }

                innerCards.forEachIndexed { idx, cardWidget ->
                    val col = idx % cols
                    val row = idx / cols
                    val cx = innerLeft + col * (cardW + gap)
                    val cy = contentY + row * rowStride

                    if (cy + cardH >= viewportTop - 20 && cy <= viewportBottom + 20) {
                        val cardTextImpls = getCachedDirectOrNestedTexts(cardWidget)
                        var cTitle = ""
                        var cDesc = ""
                        for (tImpl in cardTextImpls) {
                            val str = tImpl.getText().trim()
                            if (str.isNotEmpty()) {
                                if (cTitle.isEmpty()) {
                                    cTitle = str
                                } else if (cDesc.isEmpty()) {
                                    cDesc = str
                                    break
                                }
                            }
                        }
                        if (cTitle.isNotEmpty()) {
                            SelectObject(hdc, fontBold)
                            drawTextInRect(
                                hdc = hdc,
                                text = cTitle,
                                left = cx + 10,
                                top = cy + 10,
                                right = cx + cardW - 10,
                                bottom = cy + 32,
                                colorRef = theme.textPrimary,
                                center = true
                            )
                        }
                        if (cDesc.isNotEmpty()) {
                            SelectObject(hdc, fontSmall)
                            drawTextInRect(
                                hdc = hdc,
                                text = cDesc,
                                left = cx + 10,
                                top = cy + 36,
                                right = cx + cardW - 10,
                                bottom = cy + 78,
                                colorRef = theme.textSecondary,
                                center = true
                            )
                        }
                        val cardBtn = getCachedFirstButton(cardWidget)
                        if (cardBtn != null) {
                            val btnTop = cy + cardH - 38
                            val btnBottom = cy + cardH - 10
                            drawRoundedButtonForeground(
                                hdc = hdc,
                                font = fontSmallBold,
                                label = cardBtn.getText().ifBlank { "Open" },
                                left = cx + 12,
                                top = btnTop,
                                right = cx + cardW - 12,
                                bottom = btnBottom,
                                fg = rgb(255, 255, 255)
                            )
                        }
                    }
                }
            }
            contentY += totalCardGridH
        }

        // Case B: DataSheet Table (.data-sheet)
        val dataSheetNode = if (innerCards.isEmpty()) findWidgetByClass(cardBody, "data-sheet") else null
        if (dataSheetNode != null) {
            renderedWidgets.add(dataSheetNode)
            for (child in cardBody.children) {
                if (child === dataSheetNode) break
                val btns = collectDirectOrNestedButtons(child)
                if (btns.isNotEmpty()) {
                    renderedWidgets.add(child)
                    var bx = innerLeft
                    for (btn in btns) {
                        val label = btn.getText().trim()
                        if (label.isEmpty()) continue
                        val btnW = max(110, label.length * 8 + 24)
                        val isCreate = label.startsWith("Create", ignoreCase = true)
                        val drawLeft = if (isCreate && btns.size <= 2) max(bx, innerRight - btnW) else bx
                        drawRoundedButton(
                            hdc = hdc,
                            font = fontSmallBold,
                            label = label,
                            left = drawLeft,
                            top = contentY,
                            right = drawLeft + btnW,
                            bottom = contentY + 30,
                            bg = if (isCreate) theme.accentBlue else theme.bgCard,
                            border = if (isCreate) theme.accentBlueBorder else theme.borderSubtle,
                            fg = if (isCreate) rgb(255, 255, 255) else theme.textPrimary,
                            radius = 5,
                            widget = btn.nativeWidget,
                            hwndForHover = hwnd
                        )
                        addClippedHitRegion(
                            left = drawLeft,
                            top = contentY,
                            right = drawLeft + btnW,
                            bottom = contentY + 30,
                            widget = btn.nativeWidget,
                            onClick = { _, _ -> btn.dispatchClick() }
                        )
                        bx += btnW + 8
                    }
                    contentY += 38
                }
            }

            val headerNode = dataSheetNode.children.firstOrNull { widgetHasClass(it, "header") }
            val contentNode = dataSheetNode.children.firstOrNull { widgetHasClass(it, "content") }
            val headCells = headerNode?.children.orEmpty()
            val colCount = max(1, headCells.size)
            val colW = innerW / colCount
            val rowH = 34

            if (headCells.isNotEmpty()) {
                fillRectColor(hdc, innerLeft, contentY, innerRight, contentY + rowH, theme.bgElevated)
                drawRectBorder(hdc, innerLeft, contentY, innerRight, contentY + rowH, theme.borderSubtle)
                headCells.forEachIndexed { cIdx, cellWidget ->
                    val cLeft = innerLeft + cIdx * colW
                    val cRight = if (cIdx == colCount - 1) innerRight else cLeft + colW
                    val sortBtn = getCachedFirstButton(cellWidget)
                    val titleStr = sortBtn?.getText()?.ifBlank { extractWidgetText(cellWidget) }
                        ?: extractWidgetText(cellWidget)
                    SelectObject(hdc, fontSmallBold)
                    drawTextInRect(
                        hdc = hdc,
                        text = titleStr,
                        left = cLeft + 8,
                        top = contentY + 6,
                        right = cRight - 6,
                        bottom = contentY + rowH - 6,
                        colorRef = theme.textPrimary
                    )
                    if (sortBtn != null) {
                        addClippedHitRegion(
                            left = cLeft,
                            top = contentY,
                            right = cRight,
                            bottom = contentY + rowH,
                            widget = sortBtn.nativeWidget,
                            onClick = { _, _ -> sortBtn.dispatchClick() }
                        )
                    }
                }
                contentY += rowH
            }

            val dataRows = contentNode?.children.orEmpty()
            dataRows.forEachIndexed { rIdx, rowWidget ->
                val ry = contentY + rIdx * rowH
                if (ry + rowH >= viewportTop - 20 && ry <= viewportBottom + 20) {
                    val rowBg = if (rIdx % 2 == 0) theme.bgCard else theme.bgBase
                    fillRectColor(hdc, innerLeft, ry, innerRight, ry + rowH, rowBg)
                    drawLineColor(hdc, innerLeft, ry + rowH, innerRight, ry + rowH, theme.borderSubtle)

                    val rowCells = rowWidget.children
                    rowCells.forEachIndexed { cIdx, cellWidget ->
                        val cLeft = if (rowCells.size == 1) innerLeft else innerLeft + cIdx * colW
                        val cRight = if (rowCells.size == 1 || cIdx == colCount - 1) innerRight else cLeft + colW
                        val cellBtn = getCachedFirstButton(cellWidget)
                        if (cellBtn != null) {
                            drawRoundedButton(
                                hdc = hdc,
                                font = fontSmallBold,
                                label = cellBtn.getText().ifBlank { "View / Edit" },
                                left = cLeft + 6,
                                top = ry + 4,
                                right = cRight - 6,
                                bottom = ry + rowH - 4,
                                bg = theme.accentBlue,
                                border = theme.accentBlueBorder,
                                fg = rgb(255, 255, 255),
                                radius = 4,
                                widget = cellBtn.nativeWidget,
                                hwndForHover = hwnd
                            )
                            addClippedHitRegion(
                                left = cLeft + 6,
                                top = ry + 4,
                                right = cRight - 6,
                                bottom = ry + rowH - 4,
                                widget = cellBtn.nativeWidget,
                                onClick = { _, _ -> cellBtn.dispatchClick() }
                            )
                        } else {
                            val cellText = extractWidgetText(cellWidget)
                            SelectObject(hdc, fontSmall)
                            drawTextInRect(
                                hdc = hdc,
                                text = cellText,
                                left = cLeft + 8,
                                top = ry + 6,
                                right = cRight - 6,
                                bottom = ry + rowH - 6,
                                colorRef = theme.textPrimary
                            )
                        }
                    }
                }
            }
            contentY += dataRows.size * rowH + 12

            for (child in cardBody.children) {
                if (child in renderedWidgets || child === dataSheetNode) continue
                val pagerBtns = collectDirectOrNestedButtons(child)
                if (pagerBtns.isNotEmpty()) {
                    renderedWidgets.add(child)
                    var px = innerLeft
                    for (pBtn in pagerBtns) {
                        val pLabel = pBtn.getText().trim()
                        val pW = max(36, pLabel.length * 8 + 18)
                        val enabled = pBtn.isEnabled()
                        drawRoundedButton(
                            hdc = hdc,
                            font = fontSmallBold,
                            label = pLabel,
                            left = px,
                            top = contentY,
                            right = px + pW,
                            bottom = contentY + 28,
                            bg = if (!enabled) theme.accentBlue else theme.bgCard,
                            border = if (!enabled) theme.accentBlueBorder else theme.borderSubtle,
                            fg = if (!enabled) rgb(255, 255, 255) else theme.textPrimary,
                            radius = 4,
                            widget = if (enabled) pBtn.nativeWidget else null,
                            hwndForHover = if (enabled) hwnd else null
                        )
                        if (enabled) {
                            addClippedHitRegion(
                                left = px,
                                top = contentY,
                                right = px + pW,
                                bottom = contentY + 28,
                                widget = pBtn.nativeWidget,
                                onClick = { _, _ -> pBtn.dispatchClick() }
                            )
                        }
                        px += pW + 6
                    }
                    contentY += 36
                }
            }
        }

        // Case C: Form Editor / Remaining Controls (MetaEditor, Alignment, etc.)
        if (innerCards.isEmpty() && dataSheetNode == null) {
            val alertNode = findWidgetByClass(cardBody, "alert")
            if (alertNode != null && alertNode.visible) {
                val alertMsg = extractWidgetText(alertNode).trim()
                if (alertMsg.isNotEmpty()) {
                    val isDanger = widgetHasClass(alertNode, "alert-danger")
                    drawRoundedRect(
                        hdc = hdc,
                        left = innerLeft,
                        top = contentY,
                        right = innerRight,
                        bottom = contentY + 34,
                        radius = 5,
                        fillColor = if (isDanger) theme.dangerBg else theme.accentBlueSoftBg,
                        borderColor = if (isDanger) theme.dangerBorder else theme.accentBlueBorder
                    )
                    SelectObject(hdc, fontSmallBold)
                    drawTextInRect(
                        hdc = hdc,
                        text = alertMsg,
                        left = innerLeft + 12,
                        top = contentY + 7,
                        right = innerRight - 12,
                        bottom = contentY + 28,
                        colorRef = if (isDanger) theme.dangerText else theme.accentBlueText
                    )
                    contentY += 42
                }
            }

            val embeddedCanvas = findFirstPixelSurface(cardBody)
            if (embeddedCanvas != null) {
                activeCanvasImpl = embeddedCanvas.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
                val surfWFn = embeddedCanvas.attributes["surfaceWidth"] as? (() -> Int)
                val surfHFn = embeddedCanvas.attributes["surfaceHeight"] as? (() -> Int)
                val vblankFn = embeddedCanvas.attributes["vblankDispatch"] as? ((Double?) -> Unit)
                val renderHookFn = embeddedCanvas.attributes["surfaceRenderHook"] as? (() -> Unit)
                val captureBgraFn = embeddedCanvas.attributes["captureBgra"] as? (() -> ByteArray)
                val captureRgbaFn = embeddedCanvas.attributes["captureRgba"] as? (() -> ByteArray)

                runCatching { renderHookFn?.invoke() }
                runCatching { vblankFn?.invoke(null) }

                val surfW = (surfWFn?.invoke() ?: embeddedCanvas.bounds.width.toInt().takeIf { it > 0 } ?: 240).coerceAtLeast(1)
                val surfH = (surfHFn?.invoke() ?: embeddedCanvas.bounds.height.toInt().takeIf { it > 0 } ?: 240).coerceAtLeast(1)
                activeCanvasSurfaceSize = surfW to surfH

                val boxH = 200
                val boxTop = contentY
                val boxBottom = boxTop + boxH
                val scale = min((innerW - 24).toFloat() / surfW.toFloat(), (boxH - 16).toFloat() / surfH.toFloat())
                    .coerceAtLeast(0.1f)
                val drawW = (surfW * scale).roundToInt().coerceAtLeast(1)
                val drawH = (surfH * scale).roundToInt().coerceAtLeast(1)
                val drawLeft = innerLeft + (innerW - drawW) / 2
                val drawTop = boxTop + (boxH - drawH) / 2
                canvasViewportRect = intArrayOf(drawLeft, drawTop, drawLeft + drawW, drawTop + drawH)

                if (boxBottom >= viewportTop - 20 && boxTop <= viewportBottom + 20) {
                    drawRoundedRect(
                        hdc = hdc,
                        left = innerLeft,
                        top = boxTop,
                        right = innerRight,
                        bottom = boxBottom,
                        radius = 6,
                        fillColor = theme.bgCard,
                        borderColor = theme.borderSubtle
                    )
                    val bgra = captureBgraFn?.invoke() ?: captureRgbaFn?.invoke()?.let { rgba ->
                        val out = ByteArray(rgba.size)
                        var i = 0
                        while (i + 3 < rgba.size) {
                            out[i] = rgba[i + 2]
                            out[i + 1] = rgba[i + 1]
                            out[i + 2] = rgba[i]
                            out[i + 3] = rgba[i + 3]
                            i += 4
                        }
                        out
                    }
                    if (bgra != null && bgra.size >= surfW * surfH * 4) {
                        memScoped {
                            val bmi = alloc<BITMAPINFO>()
                            bmi.bmiHeader.biSize = sizeOf<BITMAPINFOHEADER>().toUInt()
                            bmi.bmiHeader.biWidth = surfW
                            bmi.bmiHeader.biHeight = -surfH
                            bmi.bmiHeader.biPlanes = 1u
                            bmi.bmiHeader.biBitCount = 32u
                            bmi.bmiHeader.biCompression = BI_RGB.toUInt()

                            SetStretchBltMode(hdc, HALFTONE)
                            bgra.usePinned { pinned ->
                                StretchDIBits(
                                    hdc,
                                    drawLeft,
                                    drawTop,
                                    drawW,
                                    drawH,
                                    0,
                                    0,
                                    surfW,
                                    surfH,
                                    pinned.addressOf(0),
                                    bmi.ptr,
                                    DIB_RGB_COLORS.toUInt(),
                                    SRCCOPY
                                )
                            }
                        }
                    } else {
                        fillRectColor(hdc, drawLeft, drawTop, drawLeft + drawW, drawTop + drawH, theme.bgBase)
                    }
                    drawRectBorder(hdc, drawLeft - 1, drawTop - 1, drawLeft + drawW + 1, drawTop + drawH + 1, theme.accentBlueBorder)
                }
                contentY += boxH + 12
            }

            class FormFieldRenderItem(
                val label: String,
                val textInput: DesktopTextInputNativeImpl<*>?,
                val select: DesktopSelectNativeImpl<*>?,
                val checkbox: DesktopCheckboxNativeImpl<*>?,
                val buttons: List<DesktopButtonNativeImpl<*>>,
                val readOnlyText: String? = null
            )

            val fieldItems = mutableListOf<FormFieldRenderItem>()
            val actionButtons = mutableListOf<DesktopButtonNativeImpl<*>>()
            var pendingOrphanLabel = ""

            fun isFieldColWidget(w: DesktopNativeWidget): Boolean =
                widgetHasClass(w, "col-lg-4") ||
                    widgetHasClass(w, "col-lg-6") ||
                    widgetHasClass(w, "col-md-6") ||
                    widgetHasClass(w, "col-12") ||
                    widgetHasClass(w, "form-group")

            fun countLeafControlInputs(w: DesktopNativeWidget): Int {
                if (!w.visible || w.disposed) return 0
                val impl = w.attributes["nativeImpl"]
                var total = if (
                    impl is DesktopTextInputNativeImpl<*> ||
                    impl is DesktopSelectNativeImpl<*> ||
                    impl is DesktopCheckboxNativeImpl<*>
                ) 1 else 0
                for (c in w.children) {
                    total += countLeafControlInputs(c)
                }
                return total
            }

            fun hasDescendantFieldCol(w: DesktopNativeWidget): Boolean {
                for (c in w.children) {
                    if (!c.visible || c.disposed) continue
                    if (isFieldColWidget(c) || hasDescendantFieldCol(c)) return true
                }
                return false
            }

            fun collectFormNodes(node: DesktopNativeWidget) {
                if (!node.visible || node.disposed || widgetHasClass(node, "alert") || isPixelSurfaceWidget(node)) return
                val isLeafFieldCol = isFieldColWidget(node) &&
                    !widgetHasClass(node, "row") &&
                    !hasDescendantFieldCol(node) &&
                    countLeafControlInputs(node) <= 1
                if (isLeafFieldCol) {
                    val tInput = findFirstTextInput(node)
                    val sel = findFirstSelect(node)
                    val chk = findFirstCheckbox(node)
                    val btns = collectDirectOrNestedButtons(node)
                    val nonEmptyTexts = getCachedDirectOrNestedTexts(node)
                        .map { it.getText().trim() }
                        .filter { it.isNotEmpty() }
                    val lbl = nonEmptyTexts.firstOrNull() ?: pendingOrphanLabel
                    val isActionBtnBar = tInput == null && sel == null && chk == null && btns.size > 1 && nonEmptyTexts.isEmpty()
                    if (!isActionBtnBar) {
                        if (tInput != null || sel != null || chk != null || btns.isNotEmpty()) {
                            pendingOrphanLabel = ""
                            fieldItems.add(
                                FormFieldRenderItem(
                                    label = lbl,
                                    textInput = tInput,
                                    select = sel,
                                    checkbox = chk,
                                    buttons = btns
                                )
                            )
                            return
                        }
                        if (findFirstPixelSurface(node) == null && nonEmptyTexts.size >= 2) {
                            pendingOrphanLabel = ""
                            fieldItems.add(
                                FormFieldRenderItem(
                                    label = nonEmptyTexts.first(),
                                    textInput = null,
                                    select = null,
                                    checkbox = null,
                                    buttons = emptyList(),
                                    readOnlyText = nonEmptyTexts.drop(1).joinToString(" ")
                                )
                            )
                            return
                        }
                    }
                }
                when (val impl = node.attributes["nativeImpl"]) {
                    is DesktopButtonNativeImpl<*> -> {
                        actionButtons.add(impl)
                        return
                    }
                    is DesktopTextInputNativeImpl<*> -> {
                        val lbl = pendingOrphanLabel.ifEmpty { impl.getPlaceholder() }
                        pendingOrphanLabel = ""
                        fieldItems.add(
                            FormFieldRenderItem(
                                label = lbl,
                                textInput = impl,
                                select = null,
                                checkbox = null,
                                buttons = emptyList()
                            )
                        )
                        return
                    }
                    is DesktopSelectNativeImpl<*> -> {
                        val lbl = pendingOrphanLabel
                        pendingOrphanLabel = ""
                        fieldItems.add(
                            FormFieldRenderItem(
                                label = lbl,
                                textInput = null,
                                select = impl,
                                checkbox = null,
                                buttons = emptyList()
                            )
                        )
                        return
                    }
                    is DesktopCheckboxNativeImpl<*> -> {
                        val lbl = pendingOrphanLabel.ifEmpty { impl.getLabel() }
                        pendingOrphanLabel = ""
                        fieldItems.add(
                            FormFieldRenderItem(
                                label = lbl,
                                textInput = null,
                                select = null,
                                checkbox = impl,
                                buttons = emptyList()
                            )
                        )
                        return
                    }
                    is DesktopTextNativeImpl<*> -> {
                        val t = impl.getText().trim()
                        if (t.isNotEmpty() && t != mainHeading && t != subHeading) {
                            pendingOrphanLabel = t
                        }
                        return
                    }
                }
                for (c in node.children) collectFormNodes(c)
            }
            collectFormNodes(cardBody)

            if (fieldItems.isNotEmpty()) {
                val cols = if (innerW >= 760) 3 else 2
                val gap = 12
                val colW = (innerW - gap * (cols - 1)) / cols
                val fieldH = 58

                fieldItems.forEachIndexed { idx, item ->
                    val col = idx % cols
                    val row = idx / cols
                    val fx = innerLeft + col * (colW + gap)
                    val fy = contentY + row * (fieldH + 8)

                    if (fy + fieldH >= viewportTop - 20 && fy <= viewportBottom + 20) {
                        SelectObject(hdc, fontSmallBold)
                        drawTextInRect(
                            hdc = hdc,
                            text = item.label,
                            left = fx,
                            top = fy,
                            right = fx + colW,
                            bottom = fy + 18,
                            colorRef = theme.textSecondary
                        )
                        val ctrlTop = fy + 22
                        val ctrlBottom = fy + 52

                        fun renderInlineFieldButtons(btns: List<DesktopButtonNativeImpl<*>>, leftLimit: Int, rightEdge: Int): Int {
                            if (btns.isEmpty()) return rightEdge
                            var curRight = rightEdge
                            for (btn in btns.asReversed()) {
                                val bLabel = btn.getText().trim().ifBlank { "..." }
                                val bW = (bLabel.length * 7 + 16).coerceIn(36, 78)
                                val bLeft = curRight - bW
                                if (bLeft <= leftLimit + 60) break
                                drawRoundedButton(
                                    hdc = hdc,
                                    font = fontSmallBold,
                                    label = bLabel,
                                    left = bLeft,
                                    top = ctrlTop,
                                    right = curRight,
                                    bottom = ctrlBottom,
                                    bg = theme.accentBlue,
                                    border = theme.accentBlueBorder,
                                    fg = rgb(255, 255, 255),
                                    radius = 4,
                                    widget = btn.nativeWidget,
                                    hwndForHover = hwnd
                                )
                                addClippedHitRegion(
                                    left = bLeft,
                                    top = ctrlTop,
                                    right = curRight,
                                    bottom = ctrlBottom,
                                    widget = btn.nativeWidget,
                                    onClick = { _, _ -> btn.dispatchClick() }
                                )
                                curRight = bLeft - 4
                            }
                            return curRight
                        }

                        when {
                            item.textInput != null -> {
                                val ctrlRight = renderInlineFieldButtons(item.buttons, fx, fx + colW)
                                val tIn = item.textInput
                                val isFocused = focusedTextInput === tIn
                                val valStr = tIn.getValue()
                                val disp = if (isFocused) "$valStr|" else valStr
                                drawRoundedRect(
                                    hdc = hdc,
                                    left = fx,
                                    top = ctrlTop,
                                    right = ctrlRight,
                                    bottom = ctrlBottom,
                                    radius = 4,
                                    fillColor = theme.bgBase,
                                    borderColor = if (isFocused) theme.accentBlueBorder else theme.borderSubtle
                                )
                                SelectObject(hdc, fontSmall)
                                drawTextInRect(
                                    hdc = hdc,
                                    text = disp,
                                    left = fx + 8,
                                    top = ctrlTop + 5,
                                    right = ctrlRight - 8,
                                    bottom = ctrlBottom - 4,
                                    colorRef = theme.textPrimary
                                )
                                addClippedHitRegion(
                                    left = fx,
                                    top = ctrlTop,
                                    right = ctrlRight,
                                    bottom = ctrlBottom,
                                    widget = tIn.nativeWidget,
                                    isTextInput = true
                                )
                            }
                            item.select != null -> {
                                val ctrlRight = renderInlineFieldButtons(item.buttons, fx, fx + colW)
                                val sel = item.select
                                val selectedVal = sel.getSelectedValue() ?: ""
                                val selectedLabel = sel.getOptions()
                                    .firstOrNull { it.first == selectedVal }?.second
                                    ?: selectedVal.ifBlank { "Select..." }
                                drawRoundedButton(
                                    hdc = hdc,
                                    font = fontSmall,
                                    label = "$selectedLabel  ▾",
                                    left = fx,
                                    top = ctrlTop,
                                    right = ctrlRight,
                                    bottom = ctrlBottom,
                                    bg = theme.bgCard,
                                    border = theme.borderSubtle,
                                    fg = theme.textPrimary,
                                    radius = 4,
                                    widget = sel.nativeWidget,
                                    hwndForHover = hwnd
                                )
                                addClippedHitRegion(
                                    left = fx,
                                    top = ctrlTop,
                                    right = ctrlRight,
                                    bottom = ctrlBottom,
                                    widget = sel.nativeWidget,
                                    onClick = { mx, my -> openSelectPopupMenu(hwnd, sel, mx, my) }
                                )
                            }
                            item.checkbox != null -> {
                                val ctrlRight = renderInlineFieldButtons(item.buttons, fx, fx + colW)
                                val chk = item.checkbox
                                val checked = chk.isChecked()
                                drawRoundedButton(
                                    hdc = hdc,
                                    font = fontSmallBold,
                                    label = if (checked) "☑ Enabled" else "☐ Disabled",
                                    left = fx,
                                    top = ctrlTop,
                                    right = ctrlRight,
                                    bottom = ctrlBottom,
                                    bg = if (checked) theme.accentBlueSoftBg else theme.bgCard,
                                    border = if (checked) theme.accentBlueBorder else theme.borderSubtle,
                                    fg = theme.textPrimary,
                                    radius = 4,
                                    widget = chk.nativeWidget,
                                    hwndForHover = hwnd
                                )
                                addClippedHitRegion(
                                    left = fx,
                                    top = ctrlTop,
                                    right = ctrlRight,
                                    bottom = ctrlBottom,
                                    widget = chk.nativeWidget,
                                    onClick = { _, _ -> chk.setChecked(!chk.isChecked()) }
                                )
                            }
                            item.buttons.isNotEmpty() -> {
                                val bCount = item.buttons.size
                                val bGap = 6
                                val bW = (colW - bGap * (bCount - 1)).coerceAtLeast(32) / bCount
                                item.buttons.forEachIndexed { bIdx, btn ->
                                    val bLeft = fx + bIdx * (bW + bGap)
                                    val bRight = if (bIdx == bCount - 1) fx + colW else bLeft + bW
                                    drawRoundedButton(
                                        hdc = hdc,
                                        font = fontSmallBold,
                                        label = btn.getText().ifBlank { item.label.ifBlank { "Action" } },
                                        left = bLeft,
                                        top = ctrlTop,
                                        right = bRight,
                                        bottom = ctrlBottom,
                                        bg = theme.bgCard,
                                        border = theme.borderSubtle,
                                        fg = theme.textPrimary,
                                        radius = 4,
                                        widget = btn.nativeWidget,
                                        hwndForHover = hwnd
                                    )
                                    addClippedHitRegion(
                                        left = bLeft,
                                        top = ctrlTop,
                                        right = bRight,
                                        bottom = ctrlBottom,
                                        widget = btn.nativeWidget,
                                        onClick = { _, _ -> btn.dispatchClick() }
                                    )
                                }
                            }
                            item.readOnlyText != null -> {
                                drawRoundedRect(
                                    hdc = hdc,
                                    left = fx,
                                    top = ctrlTop,
                                    right = fx + colW,
                                    bottom = ctrlBottom,
                                    radius = 4,
                                    fillColor = theme.bgCard,
                                    borderColor = theme.borderSubtle
                                )
                                SelectObject(hdc, fontSmall)
                                drawTextInRect(
                                    hdc = hdc,
                                    text = item.readOnlyText,
                                    left = fx + 8,
                                    top = ctrlTop + 5,
                                    right = fx + colW - 8,
                                    bottom = ctrlBottom - 4,
                                    colorRef = theme.textPrimary
                                )
                            }
                        }
                    }
                }
                val totalRows = (fieldItems.size + cols - 1) / cols
                contentY += totalRows * (fieldH + 8) + 8
            }

            if (actionButtons.isNotEmpty()) {
                val cols = if (actionButtons.size == 9) 3 else min(4, max(1, actionButtons.size))
                val gap = 10
                val btnW = min(220, (innerW - gap * (cols - 1)) / cols)
                actionButtons.forEachIndexed { idx, btn ->
                    val col = idx % cols
                    val row = idx / cols
                    val bx = innerLeft + col * (btnW + gap)
                    val by = contentY + row * 38
                    val label = btn.getText().ifBlank { "Action ${idx + 1}" }
                    val isPrimary = label.equals("Save", ignoreCase = true) ||
                        label.equals("Create", ignoreCase = true) ||
                        widgetHasClass(btn.nativeWidget, "btn-primary")
                    drawRoundedButton(
                        hdc = hdc,
                        font = fontSmallBold,
                        label = label,
                        left = bx,
                        top = by,
                        right = bx + btnW,
                        bottom = by + 30,
                        bg = if (isPrimary) theme.accentBlue else theme.bgCard,
                        border = if (isPrimary) theme.accentBlueBorder else theme.borderSubtle,
                        fg = if (isPrimary) rgb(255, 255, 255) else theme.textPrimary,
                        radius = 5,
                        widget = btn.nativeWidget,
                        hwndForHover = hwnd
                    )
                    addClippedHitRegion(
                        left = bx,
                        top = by,
                        right = bx + btnW,
                        bottom = by + 30,
                        widget = btn.nativeWidget,
                        onClick = { _, _ -> btn.dispatchClick() }
                    )
                }
                val totalBtnRows = (actionButtons.size + cols - 1) / cols
                contentY += totalBtnRows * 38 + 8
            }
        }

        val totalContentH = (contentY + cardPageScrollY) - viewportTop
        cardPageMaxScrollY = max(0, totalContentH - viewportH)
        if (cardPageScrollY > cardPageMaxScrollY) {
            cardPageScrollY = cardPageMaxScrollY
            scrollViewImpl?.updateScrollPositionFromHost(0, cardPageScrollY)
        }

        if (useGdiViewportClip) {
            popClipRect(hdc, savedDc)
        } else {
            resetSoftClipRect()
        }

        // Interactive Vertical Scrollbar Rail, Arrow Buttons, & Thumb with Hover/Active State
        if (cardPageMaxScrollY > 0) {
            val railLeft = outerRight - 20
            val railRight = outerRight - 6
            cardPageScrollRailRect = intArrayOf(railLeft, viewportTop, railRight, viewportBottom)

            val showArrowButtons = viewportH >= 88
            val arrowBtnH = if (showArrowButtons) 18 else 0
            val trackTop = if (showArrowButtons) viewportTop + arrowBtnH + 2 else viewportTop
            val trackBottom = if (showArrowButtons) viewportBottom - arrowBtnH - 2 else viewportBottom
            val trackH = max(24, trackBottom - trackTop)

            val isRailHovered = isDraggingCardScrollbarThumb ||
                hoveredWidget === cardScrollbarRailWidget ||
                hoveredWidget === cardScrollbarThumbWidget ||
                isRectHovered(hwnd, railLeft, viewportTop, railRight, viewportBottom)

            drawRoundedRect(
                hdc = hdc,
                left = railLeft,
                top = viewportTop,
                right = railRight,
                bottom = viewportBottom,
                radius = 5,
                fillColor = if (isRailHovered) theme.bgElevated else theme.bgBase,
                borderColor = if (isRailHovered) theme.borderStrong else theme.borderSubtle
            )

            val upBtnTop = viewportTop + 1
            val upBtnBottom = viewportTop + arrowBtnH
            val downBtnTop = viewportBottom - arrowBtnH
            val downBtnBottom = viewportBottom - 1

            if (showArrowButtons) {
                drawRoundedButtonBackground(
                    hdc = hdc,
                    left = railLeft + 1,
                    top = upBtnTop,
                    right = railRight - 1,
                    bottom = upBtnBottom,
                    bg = theme.bgCard,
                    border = theme.borderSubtle,
                    radius = 3,
                    widget = cardScrollbarUpBtnWidget,
                    hwndForHover = hwnd
                )
                hitRegions.add(
                    HitRegion(
                        left = railLeft,
                        top = viewportTop,
                        right = railRight,
                        bottom = upBtnBottom,
                        widget = cardScrollbarUpBtnWidget,
                        onClick = { _, _ ->
                            val next = (cardPageScrollY - 96).coerceIn(0, cardPageMaxScrollY)
                            cardPageScrollY = next
                            scrollViewImpl?.updateScrollPositionFromHost(0, next)
                        }
                    )
                )

                drawRoundedButtonBackground(
                    hdc = hdc,
                    left = railLeft + 1,
                    top = downBtnTop,
                    right = railRight - 1,
                    bottom = downBtnBottom,
                    bg = theme.bgCard,
                    border = theme.borderSubtle,
                    radius = 3,
                    widget = cardScrollbarDownBtnWidget,
                    hwndForHover = hwnd
                )
                hitRegions.add(
                    HitRegion(
                        left = railLeft,
                        top = downBtnTop,
                        right = railRight,
                        bottom = viewportBottom,
                        widget = cardScrollbarDownBtnWidget,
                        onClick = { _, _ ->
                            val next = (cardPageScrollY + 96).coerceIn(0, cardPageMaxScrollY)
                            cardPageScrollY = next
                            scrollViewImpl?.updateScrollPositionFromHost(0, next)
                        }
                    )
                )
            }

            val thumbH = max(
                32,
                (trackH.toFloat() * (viewportH.toFloat() / max(1, totalContentH).toFloat())).roundToInt()
            ).coerceAtMost(trackH)
            val trackRange = max(1, trackH - thumbH)
            val thumbTop = trackTop +
                ((cardPageScrollY.toFloat() / cardPageMaxScrollY.toFloat()) * trackRange.toFloat())
                    .roundToInt()
                    .coerceIn(0, trackRange)
            val thumbBottom = thumbTop + thumbH
            cardPageScrollThumbRect = intArrayOf(railLeft, thumbTop, railRight, thumbBottom)

            val isThumbDragging = isDraggingCardScrollbarThumb
            val isThumbHovered = isThumbDragging ||
                hoveredWidget === cardScrollbarThumbWidget ||
                isRectHovered(hwnd, railLeft - 1, thumbTop, railRight + 1, thumbBottom)

            val thumbFill = when {
                isThumbDragging -> lightenColorRef(theme.accentBlue, -18)
                isThumbHovered -> lightenColorRef(theme.accentBlue, 28)
                else -> theme.accentBlue
            }
            val thumbBorder = when {
                isThumbDragging -> rgb(255, 255, 255)
                isThumbHovered -> lightenColorRef(theme.accentBlueBorder, 40)
                else -> theme.accentBlueBorder
            }
            val thumbInset = if (isThumbHovered || isThumbDragging) 1 else 2
            drawRoundedRect(
                hdc = hdc,
                left = railLeft + thumbInset,
                top = thumbTop,
                right = railRight - thumbInset,
                bottom = thumbBottom,
                radius = 4,
                fillColor = thumbFill,
                borderColor = thumbBorder
            )
            if (thumbH >= 28) {
                val gripMidY = (thumbTop + thumbBottom) / 2
                val gripLeft = railLeft + 4
                val gripRight = railRight - 4
                val gripColor = if (isThumbHovered || isThumbDragging) {
                    rgb(255, 255, 255)
                } else {
                    lightenColorRef(theme.accentBlue, 45)
                }
                drawLineColor(hdc, gripLeft, gripMidY - 3, gripRight, gripMidY - 3, gripColor)
                drawLineColor(hdc, gripLeft, gripMidY, gripRight, gripMidY, gripColor)
                drawLineColor(hdc, gripLeft, gripMidY + 3, gripRight, gripMidY + 3, gripColor)
            }

            if (showArrowButtons) {
                val arrowCx = (railLeft + railRight) / 2
                val upMidY = (upBtnTop + upBtnBottom) / 2
                for (row in 0..3) {
                    val y = upMidY - 2 + row
                    fillRectColor(hdc, arrowCx - row, y, arrowCx + row + 1, y + 1, theme.textPrimary)
                }
                val downMidY = (downBtnTop + downBtnBottom) / 2
                for (row in 0..3) {
                    val halfSpan = 3 - row
                    val y = downMidY - 1 + row
                    fillRectColor(hdc, arrowCx - halfSpan, y, arrowCx + halfSpan + 1, y + 1, theme.textPrimary)
                }
            }

            val maxScrollSnapshot = cardPageMaxScrollY
            val applyScrollFromMouseY: (Int) -> Unit = { mouseY ->
                val newThumbTop = (mouseY - cardScrollbarDragGrabOffsetY).coerceIn(trackTop, trackTop + trackRange)
                val frac = (newThumbTop - trackTop).toFloat() / trackRange.toFloat()
                val nextScroll = (frac * maxScrollSnapshot.toFloat()).roundToInt().coerceIn(0, maxScrollSnapshot)
                if (nextScroll != cardPageScrollY) {
                    cardPageScrollY = nextScroll
                    scrollViewImpl?.updateScrollPositionFromHost(0, nextScroll)
                }
            }

            // Track hit region (for clicking above/below the thumb to jump & drag)
            hitRegions.add(
                HitRegion(
                    left = railLeft - 2,
                    top = trackTop,
                    right = railRight + 2,
                    bottom = trackBottom,
                    widget = cardScrollbarRailWidget,
                    onClick = { _, my ->
                        isDraggingCardScrollbarThumb = true
                        cardScrollbarDragGrabOffsetY = if (my in thumbTop..thumbBottom) {
                            my - thumbTop
                        } else {
                            thumbH / 2
                        }
                        applyScrollFromMouseY(my)
                    },
                    onDrag = { _, my ->
                        applyScrollFromMouseY(my)
                    }
                )
            )

            // Thumb hit region (on top of track region so hovering the thumb triggers hover state)
            hitRegions.add(
                HitRegion(
                    left = railLeft - 2,
                    top = thumbTop,
                    right = railRight + 2,
                    bottom = thumbBottom,
                    widget = cardScrollbarThumbWidget,
                    onClick = { _, my ->
                        isDraggingCardScrollbarThumb = true
                        cardScrollbarDragGrabOffsetY = (my - thumbTop).coerceIn(0, thumbH)
                    },
                    onDrag = { _, my ->
                        applyScrollFromMouseY(my)
                    }
                )
            )
        } else {
            cardPageScrollRailRect = intArrayOf(-1, -1, -1, -1)
            cardPageScrollThumbRect = intArrayOf(-1, -1, -1, -1)
        }

        // Modal Overlay
        if (alertModal != null) {
            cachedCardPageFrameRootVer = -1L
            val modalW = min(460, clientW - 60)
            val modalH = 200
            val mLeft = (clientW - modalW) / 2
            val mTop = (clientH - modalH) / 2
            val mRight = mLeft + modalW
            val mBottom = mTop + modalH
            drawRoundedRect(
                hdc = hdc,
                left = mLeft,
                top = mTop,
                right = mRight,
                bottom = mBottom,
                radius = 8,
                fillColor = theme.bgElevated,
                borderColor = theme.accentBlueBorder
            )
            val modalTexts = collectDirectOrNestedTexts(alertModal).map { it.getText().trim() }.filter { it.isNotEmpty() }
            val mTitle = modalTexts.getOrNull(0) ?: "Alert"
            val mBody = modalTexts.drop(1).joinToString("\n")
            SelectObject(hdc, fontTitle)
            drawTextInRect(hdc, mTitle, mLeft + 16, mTop + 14, mRight - 16, mTop + 40, theme.textPrimary)
            SelectObject(hdc, fontNormal)
            drawTextInRect(hdc, mBody, mLeft + 16, mTop + 48, mRight - 16, mBottom - 52, theme.textSecondary)

            val modalBtns = collectDirectOrNestedButtons(alertModal)
            var mbX = mRight - 16
            for (mBtn in modalBtns.reversed()) {
                val lbl = mBtn.getText().ifBlank { "Okay" }
                val bw = max(80, lbl.length * 8 + 20)
                mbX -= bw
                drawRoundedButton(
                    hdc = hdc,
                    font = fontSmallBold,
                    label = lbl,
                    left = mbX,
                    top = mBottom - 42,
                    right = mbX + bw,
                    bottom = mBottom - 12,
                    bg = theme.accentBlue,
                    border = theme.accentBlueBorder,
                    fg = rgb(255, 255, 255),
                    radius = 5,
                    widget = mBtn.nativeWidget,
                    hwndForHover = hwnd
                )
                val capL = mbX
                val capR = mbX + bw
                hitRegions.add(
                    HitRegion(
                        left = capL,
                        top = mBottom - 42,
                        right = capR,
                        bottom = mBottom - 12,
                        widget = mBtn.nativeWidget,
                        onClick = { _, _ -> mBtn.dispatchClick() }
                    )
                )
                mbX -= 8
            }
        }
    }

    private class PageActionItem(
        val label: String,
        val widget: DesktopNativeWidget,
        val impl: DesktopBaseNativeImpl<*>,
        val isActive: Boolean,
        val isDisabled: Boolean,
        val isPrimary: Boolean
    )

    private fun isCarouselOptionWidget(widget: DesktopNativeWidget): Boolean {
        if (widgetHasClass(widget, "carousel-item")) return true
        val impl = widget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*> ?: return false
        if (impl is DesktopButtonNativeImpl<*> || widgetHasClass(widget, "btn")) return false
        return impl.hasClickListener() && collectDirectOrNestedTexts(widget).size >= 2
    }

    private fun findWidgetByExactText(root: DesktopNativeWidget?, targetTexts: Set<String>): DesktopNativeWidget? {
        if (root == null || !root.visible || root.disposed) return null
        val impl = root.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
        if (impl != null && (impl is DesktopButtonNativeImpl<*> || impl.hasClickListener() || widgetHasClass(root, "btn"))) {
            val txt = extractWidgetText(root).trim()
            if (txt in targetTexts) return root
        }
        for (child in root.children) {
            val found = findWidgetByExactText(child, targetTexts)
            if (found != null) return found
        }
        return null
    }

    private fun collectCarouselOptionWidgets(
        root: DesktopNativeWidget?,
        out: MutableList<DesktopNativeWidget> = mutableListOf()
    ): List<DesktopNativeWidget> {
        if (root == null || !root.visible || root.disposed) return out
        if (isCarouselOptionWidget(root)) {
            out.add(root)
            return out
        }
        for (child in root.children) {
            collectCarouselOptionWidgets(child, out)
        }
        return out
    }

    private fun collectPageActionItems(
        root: DesktopNativeWidget?,
        out: MutableList<PageActionItem> = mutableListOf()
    ): List<PageActionItem> {
        if (root == null || !root.visible || root.disposed) return out
        val impl = root.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
        if (impl != null) {
            val isBtnClass = widgetHasClass(root, "btn") ||
                widgetHasClass(root, "list-group-item")
            val isClickable = impl is DesktopButtonNativeImpl<*> || impl.hasClickListener() || isBtnClass
            if (isClickable && !isCarouselOptionWidget(root)) {
                val label = extractWidgetText(root).trim()
                if (label.isNotEmpty()) {
                    val active = widgetHasClass(root, "btn-active") || widgetHasClass(root, "active")
                    val disabled = !impl.isEnabled() || widgetHasClass(root, "disabled")
                    val primary = widgetHasClass(root, "btn-primary") ||
                        widgetHasClass(root, "continue") ||
                        widgetHasClass(root, "create")
                    out.add(
                        PageActionItem(
                            label = label,
                            widget = root,
                            impl = impl,
                            isActive = active,
                            isDisabled = disabled,
                            isPrimary = primary
                        )
                    )
                    return out
                }
            }
        }
        for (child in root.children) {
            collectPageActionItems(child, out)
        }
        return out
    }

    private fun renderPageHostLayout(
        hwnd: HWND,
        hdc: HDC?,
        theme: SystemThemePalette,
        fontSmall: HFONT?,
        fontSmallBold: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        fontTitle: HFONT?,
        fontMetricBig: HFONT?,
        rootWidget: DesktopNativeWidget?,
        menuWidget: DesktopNativeWidget?,
        clientW: Int,
        clientH: Int
    ) {
        val activePageWidget = rootWidget?.children?.lastOrNull {
            it.visible && !it.disposed && !widgetHasClass(it, "loader")
        } ?: menuWidget
        val activeMenu = findWidgetByClass(activePageWidget, "menu") ?: menuWidget ?: rootWidget ?: return

        val stepNavNode = findWidgetByClass(activeMenu, "step-nav")
            ?: activeMenu.children.firstOrNull { child ->
                val items = collectPageActionItems(child)
                items.size >= 3 && items.any { it.isActive }
            }
        val isWideStepWizard = stepNavNode != null || findWidgetByClass(activeMenu, "step-content") != null
        val cardW = if (isWideStepWizard) {
            (clientW - 64).coerceIn(720, 1180)
        } else {
            (clientW - 120).coerceIn(420, 560)
        }
        val cardH = if (isWideStepWizard) {
            (clientH - 56).coerceIn(480, 760)
        } else {
            (clientH - 120).coerceIn(340, 460)
        }
        val cardLeft = (clientW - cardW) / 2
        val cardTop = (clientH - cardH) / 2
        val cardRight = cardLeft + cardW
        val cardBottom = cardTop + cardH

        drawRoundedRect(
            hdc = hdc,
            left = cardLeft,
            top = cardTop,
            right = cardRight,
            bottom = cardBottom,
            radius = 10,
            fillColor = theme.bgPanel,
            borderColor = theme.borderSubtle
        )

        var curY = cardTop + 18
        val innerLeft = cardLeft + 24
        val innerRight = cardRight - 24
        val innerW = innerRight - innerLeft

        if (stepNavNode != null) {
            val wizardTitle = activeWindowImpl?.getTitle()?.ifBlank { "GenericUI" } ?: "GenericUI"
            SelectObject(hdc, fontTitle)
            drawTextInRect(
                hdc = hdc,
                text = wizardTitle,
                left = innerLeft,
                top = curY,
                right = innerRight,
                bottom = curY + 28,
                colorRef = theme.textPrimary,
                center = true
            )
            curY += 34

            val stepBtns = collectPageActionItems(stepNavNode)
            if (stepBtns.isNotEmpty()) {
                val gap = 4
                val btnW = ((innerW - gap * (stepBtns.size - 1)) / stepBtns.size).coerceAtLeast(52)
                var sx = innerLeft + (innerW - (btnW * stepBtns.size + gap * (stepBtns.size - 1))) / 2
                val stepBarH = 26
                for (stepBtn in stepBtns) {
                    val bg = when {
                        stepBtn.isActive -> theme.accentBlue
                        stepBtn.isDisabled -> theme.bgBase
                        else -> theme.bgCard
                    }
                    val border = when {
                        stepBtn.isActive -> theme.accentBlueBorder
                        stepBtn.isDisabled -> theme.borderSubtle
                        else -> theme.accentBlueBorder
                    }
                    val fg = when {
                        stepBtn.isActive -> rgb(255, 255, 255)
                        stepBtn.isDisabled -> theme.textMuted
                        else -> theme.textPrimary
                    }
                    drawRoundedButton(
                        hdc = hdc,
                        font = if (stepBtn.isActive) fontSmallBold else fontSmall,
                        label = stepBtn.label,
                        left = sx,
                        top = curY,
                        right = sx + btnW,
                        bottom = curY + stepBarH,
                        bg = bg,
                        border = border,
                        fg = fg,
                        radius = 4,
                        widget = if (stepBtn.isDisabled) null else stepBtn.widget,
                        hwndForHover = if (stepBtn.isDisabled) null else hwnd
                    )
                    if (!stepBtn.isDisabled) {
                        val capLeft = sx
                        val capRight = sx + btnW
                        val capTop = curY
                        val capBottom = curY + stepBarH
                        hitRegions.add(
                            HitRegion(
                                left = capLeft,
                                top = capTop,
                                right = capRight,
                                bottom = capBottom,
                                widget = stepBtn.widget,
                                onClick = { _, _ -> stepBtn.impl.dispatchClick() }
                            )
                        )
                    }
                    sx += btnW + gap
                }
                curY += stepBarH + 16
            }
        } else {
            val winTitle = activeWindowImpl?.getTitle()?.ifBlank { "GenericUI" } ?: "GenericUI"
            SelectObject(hdc, fontMetricBig)
            drawTextInRect(
                hdc = hdc,
                text = winTitle,
                left = innerLeft,
                top = curY,
                right = innerRight,
                bottom = curY + 36,
                colorRef = theme.accentBlueText,
                center = true
            )
            curY += 48
        }

        val contentRoot = findWidgetByClass(activeMenu, "step-content") ?: activeMenu

        // Case 1: Horizontal Carousel Selector
        val carouselPrevNode = findWidgetByClass(contentRoot, "carousel-prev")
            ?: findWidgetByExactText(contentRoot, setOf("«", "‹", "<"))
        val carouselNextNode = findWidgetByClass(contentRoot, "carousel-next")
            ?: findWidgetByExactText(contentRoot, setOf("»", "›", ">"))
        val carouselItems = collectCarouselOptionWidgets(contentRoot)
        if (carouselPrevNode != null && carouselNextNode != null && carouselItems.isNotEmpty()) {
            val footerH = 44
            val carouselBottom = cardBottom - 18 - footerH - 12
            val carouselTop = curY
            val navBtnW = 44

            val prevImpl = carouselPrevNode.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
            drawRoundedButton(
                hdc = hdc,
                font = fontTitle,
                label = "«",
                left = innerLeft,
                top = carouselTop,
                right = innerLeft + navBtnW,
                bottom = carouselBottom,
                bg = theme.accentBlue,
                border = theme.accentBlueBorder,
                fg = rgb(255, 255, 255),
                radius = 6,
                widget = carouselPrevNode,
                hwndForHover = hwnd
            )
            if (prevImpl != null) {
                hitRegions.add(
                    HitRegion(
                        left = innerLeft,
                        top = carouselTop,
                        right = innerLeft + navBtnW,
                        bottom = carouselBottom,
                        widget = carouselPrevNode,
                        onClick = { _, _ -> prevImpl.dispatchClick() }
                    )
                )
            }

            val nextImpl = carouselNextNode.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
            drawRoundedButton(
                hdc = hdc,
                font = fontTitle,
                label = "»",
                left = innerRight - navBtnW,
                top = carouselTop,
                right = innerRight,
                bottom = carouselBottom,
                bg = theme.accentBlue,
                border = theme.accentBlueBorder,
                fg = rgb(255, 255, 255),
                radius = 6,
                widget = carouselNextNode,
                hwndForHover = hwnd
            )
            if (nextImpl != null) {
                hitRegions.add(
                    HitRegion(
                        left = innerRight - navBtnW,
                        top = carouselTop,
                        right = innerRight,
                        bottom = carouselBottom,
                        widget = carouselNextNode,
                        onClick = { _, _ -> nextImpl.dispatchClick() }
                    )
                )
            }

            val activeIdx = carouselItems.indexOfFirst { it.opacity >= 0.9f }.let { if (it >= 0) it else 0 }
            val viewportLeft = innerLeft + navBtnW + 10
            val viewportRight = innerRight - navBtnW - 10
            val viewportW = (viewportRight - viewportLeft).coerceAtLeast(240)

            val visibleIndices = listOf(activeIdx - 1, activeIdx, activeIdx + 1)
                .filter { it in carouselItems.indices }
            val slotW = (viewportW - 16) / 3
            for (idx in visibleIndices) {
                val itemWidget = carouselItems[idx]
                val itemImpl = itemWidget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
                val isSelected = idx == activeIdx
                val relSlot = idx - activeIdx + 1
                val itemLeft = if (visibleIndices.size == 1) {
                    viewportLeft + (viewportW - slotW * 2) / 2
                } else {
                    viewportLeft + relSlot * (slotW + 8)
                }
                val itemRight = if (visibleIndices.size == 1) {
                    itemLeft + slotW * 2
                } else {
                    itemLeft + slotW
                }
                val cardBg = if (isSelected) theme.bgCard else theme.bgBase
                val cardBorder = if (isSelected) theme.accentBlueBorder else theme.borderSubtle
                drawRoundedRect(
                    hdc = hdc,
                    left = itemLeft,
                    top = carouselTop,
                    right = itemRight,
                    bottom = carouselBottom,
                    radius = 8,
                    fillColor = cardBg,
                    borderColor = cardBorder
                )
                val texts = collectDirectOrNestedTexts(itemWidget)
                    .map { it.getText().trim() }
                    .filter { it.isNotEmpty() }
                var ty = carouselTop + 12
                val titleStr = texts.firstOrNull() ?: "Option #${idx + 1}"
                SelectObject(hdc, if (isSelected) fontTitle else fontBold)
                drawTextInRect(
                    hdc = hdc,
                    text = titleStr,
                    left = itemLeft + 10,
                    top = ty,
                    right = itemRight - 10,
                    bottom = ty + 24,
                    colorRef = if (isSelected) theme.accentBlueText else theme.textSecondary,
                    center = true
                )
                ty += 28

                // Preview box
                val silHeight = (carouselBottom - carouselTop - 160).coerceIn(70, 150)
                val silTop = ty
                val silBottom = silTop + silHeight
                drawRoundedRect(
                    hdc = hdc,
                    left = itemLeft + 16,
                    top = silTop,
                    right = itemRight - 16,
                    bottom = silBottom,
                    radius = 6,
                    fillColor = theme.bgElevated,
                    borderColor = theme.borderSubtle
                )
                val midX = (itemLeft + itemRight) / 2
                val figColor = if (isSelected) theme.accentBlueText else theme.textMuted
                fillRectColor(hdc, midX - 28, silTop + 14, midX - 16, silTop + 26, figColor)
                fillRectColor(hdc, midX - 32, silTop + 28, midX - 12, silBottom - 12, figColor)
                fillRectColor(hdc, midX + 16, silTop + 20, midX + 28, silTop + 32, figColor)
                fillRectColor(hdc, midX + 12, silTop + 34, midX + 32, silBottom - 12, figColor)
                ty = silBottom + 8

                for (line in texts.drop(1)) {
                    if (ty + 18 > carouselBottom - 8) break
                    val isSubHeader = !line.contains(".") && line.length < 28
                    SelectObject(hdc, if (isSubHeader) fontSmallBold else fontSmall)
                    drawTextInRect(
                        hdc = hdc,
                        text = line,
                        left = itemLeft + 10,
                        top = ty,
                        right = itemRight - 10,
                        bottom = ty + 18,
                        colorRef = if (isSelected) {
                            if (isSubHeader) theme.textPrimary else theme.textSecondary
                        } else {
                            theme.textMuted
                        },
                        center = true
                    )
                    ty += 18
                }

                if (!isSelected && itemImpl != null) {
                    hitRegions.add(
                        HitRegion(
                            left = itemLeft,
                            top = carouselTop,
                            right = itemRight,
                            bottom = carouselBottom,
                            widget = itemWidget,
                            onClick = { _, _ -> itemImpl.dispatchClick() }
                        )
                    )
                }
            }

            // Footer Continue + Save buttons
            val footerTop = carouselBottom + 10
            val footerBottom = cardBottom - 16
            val continueNode = findWidgetByClass(contentRoot, "continue")
            val saveNode = findWidgetByClass(contentRoot, "save")
            val saveW = 100
            val contRight = if (saveNode != null) innerRight - saveW - 8 else innerRight
            if (continueNode != null) {
                val contImpl = continueNode.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
                drawRoundedButton(
                    hdc = hdc,
                    font = fontBold,
                    label = "Continue",
                    left = innerLeft,
                    top = footerTop,
                    right = contRight,
                    bottom = footerBottom,
                    bg = theme.accentBlue,
                    border = theme.accentBlueBorder,
                    fg = rgb(255, 255, 255),
                    radius = 6,
                    widget = continueNode,
                    hwndForHover = hwnd
                )
                if (contImpl != null) {
                    hitRegions.add(
                        HitRegion(
                            left = innerLeft,
                            top = footerTop,
                            right = contRight,
                            bottom = footerBottom,
                            widget = continueNode,
                            onClick = { _, _ -> contImpl.dispatchClick() }
                        )
                    )
                }
            }
            if (saveNode != null) {
                val saveImpl = saveNode.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
                drawRoundedButton(
                    hdc = hdc,
                    font = fontBold,
                    label = "Save",
                    left = innerRight - saveW,
                    top = footerTop,
                    right = innerRight,
                    bottom = footerBottom,
                    bg = theme.bgCard,
                    border = theme.accentBlueBorder,
                    fg = theme.accentBlueText,
                    radius = 6,
                    widget = saveNode,
                    hwndForHover = hwnd
                )
                if (saveImpl != null) {
                    hitRegions.add(
                        HitRegion(
                            left = innerRight - saveW,
                            top = footerTop,
                            right = innerRight,
                            bottom = footerBottom,
                            widget = saveNode,
                            onClick = { _, _ -> saveImpl.dispatchClick() }
                        )
                    )
                }
            }
            return
        }

        // Case 2: Level Grid (.level-select)
        val levelSelectNodes = findWidgetsByClass(contentRoot, "level-select")
        if (levelSelectNodes.isNotEmpty()) {
            SelectObject(hdc, fontTitle)
            drawTextInRect(
                hdc = hdc,
                text = "Select Starting Level",
                left = innerLeft,
                top = curY,
                right = innerRight,
                bottom = curY + 28,
                colorRef = theme.textPrimary,
                center = true
            )
            curY += 42

            val cols = 10
            val gap = 10
            val btnW = ((innerW - gap * (cols - 1)) / cols).coerceIn(44, 76)
            val btnH = 44
            val rowW = btnW * cols + gap * (cols - 1)
            val startX = innerLeft + (innerW - rowW) / 2

            levelSelectNodes.forEachIndexed { idx, lvlWidget ->
                val impl = lvlWidget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*> ?: return@forEachIndexed
                val row = idx / cols
                val col = idx % cols
                val bx = startX + col * (btnW + gap)
                val by = curY + row * (btnH + 14)
                val label = extractWidgetText(lvlWidget).ifBlank { "${idx + 1}" }
                val isSelected = widgetHasClass(lvlWidget, "btn-active")
                drawRoundedButton(
                    hdc = hdc,
                    font = fontBold,
                    label = label,
                    left = bx,
                    top = by,
                    right = bx + btnW,
                    bottom = by + btnH,
                    bg = if (isSelected) theme.accentBlue else theme.bgCard,
                    border = if (isSelected) theme.accentBlueBorder else theme.borderSubtle,
                    fg = if (isSelected) rgb(255, 255, 255) else theme.textPrimary,
                    radius = 6,
                    widget = lvlWidget,
                    hwndForHover = hwnd
                )
                hitRegions.add(
                    HitRegion(
                        left = bx,
                        top = by,
                        right = bx + btnW,
                        bottom = by + btnH,
                        widget = lvlWidget,
                        onClick = { _, _ -> impl.dispatchClick() }
                    )
                )
            }
            return
        }

        // Case 3: General Page / Menu / Step View
        val actions = collectPageActionItems(contentRoot)
        val actionWidgets = actions.map { it.widget }.toSet()
        val nonButtonTexts = mutableListOf<String>()
        fun collectPlainTexts(w: DesktopNativeWidget?) {
            if (w == null || !w.visible || w.disposed || w in actionWidgets) return
            val impl = w.attributes["nativeImpl"]
            if (impl is DesktopTextNativeImpl<*>) {
                val t = impl.getText().trim()
                if (t.isNotEmpty()) nonButtonTexts.add(t)
            }
            for (c in w.children) collectPlainTexts(c)
        }
        collectPlainTexts(contentRoot)

        for (txt in nonButtonTexts.distinct().take(8)) {
            if (curY + 24 > cardBottom - 60) break
            SelectObject(hdc, fontNormal)
            drawTextInRect(
                hdc = hdc,
                text = txt,
                left = innerLeft,
                top = curY,
                right = innerRight,
                bottom = curY + 22,
                colorRef = theme.textPrimary,
                center = !isWideStepWizard
            )
            curY += 24
        }
        if (nonButtonTexts.isNotEmpty()) {
            curY += 8
        }

        val textInputs = mutableListOf<DesktopTextInputNativeImpl<*>>()
        fun collectInputs(w: DesktopNativeWidget?) {
            if (w == null || !w.visible || w.disposed) return
            val impl = w.attributes["nativeImpl"]
            if (impl is DesktopTextInputNativeImpl<*>) textInputs.add(impl)
            for (c in w.children) collectInputs(c)
        }
        collectInputs(contentRoot)
        for (input in textInputs.take(4)) {
            if (curY + 36 > cardBottom - 50) break
            val isFocused = focusedTextInput === input
            val valStr = input.getValue()
            val displayStr = when {
                isFocused -> "$valStr|"
                valStr.isNotEmpty() -> valStr
                else -> input.getPlaceholder().ifBlank { "Enter value..." }
            }
            val inputLeft = innerLeft + (innerW - min(innerW, 380)) / 2
            val inputRight = inputLeft + min(innerW, 380)
            drawRoundedRect(
                hdc = hdc,
                left = inputLeft,
                top = curY,
                right = inputRight,
                bottom = curY + 32,
                radius = 5,
                fillColor = theme.bgBase,
                borderColor = if (isFocused) theme.accentBlueBorder else theme.borderSubtle
            )
            SelectObject(hdc, fontNormal)
            drawTextInRect(
                hdc = hdc,
                text = displayStr,
                left = inputLeft + 10,
                top = curY + 4,
                right = inputRight - 10,
                bottom = curY + 28,
                colorRef = if (valStr.isEmpty() && !isFocused) theme.textMuted else theme.textPrimary
            )
            val capTop = curY
            hitRegions.add(
                HitRegion(
                    left = inputLeft,
                    top = capTop,
                    right = inputRight,
                    bottom = capTop + 32,
                    widget = input.nativeWidget,
                    isTextInput = true
                )
            )
            curY += 40
        }

        if (actions.isNotEmpty()) {
            val cols = if (!isWideStepWizard || actions.size <= 4) 1 else 3
            val btnH = if (cols == 1) 38 else 32
            val gap = 10
            val maxRowW = if (cols == 1) min(innerW, 340) else innerW
            val startX = innerLeft + (innerW - maxRowW) / 2
            val btnW = (maxRowW - gap * (cols - 1)) / cols
            actions.take(24).forEachIndexed { idx, act ->
                val row = idx / cols
                val col = idx % cols
                val bx = startX + col * (btnW + gap)
                val by = curY + row * (btnH + gap)
                if (by + btnH <= cardBottom - 14) {
                    val bg = when {
                        act.isActive || act.isPrimary -> theme.accentBlue
                        else -> theme.bgCard
                    }
                    val border = when {
                        act.isActive || act.isPrimary -> theme.accentBlueBorder
                        else -> theme.borderSubtle
                    }
                    val fg = when {
                        act.isActive || act.isPrimary -> rgb(255, 255, 255)
                        else -> theme.textPrimary
                    }
                    drawRoundedButton(
                        hdc = hdc,
                        font = fontBold,
                        label = act.label,
                        left = bx,
                        top = by,
                        right = bx + btnW,
                        bottom = by + btnH,
                        bg = bg,
                        border = border,
                        fg = fg,
                        radius = 6,
                        widget = act.widget,
                        hwndForHover = hwnd
                    )
                    hitRegions.add(
                        HitRegion(
                            left = bx,
                            top = by,
                            right = bx + btnW,
                            bottom = by + btnH,
                            widget = act.widget,
                            onClick = { _, _ -> act.impl.dispatchClick() }
                        )
                    )
                }
            }
        }
    }

    private fun findFirstTextInput(root: DesktopNativeWidget?): DesktopTextInputNativeImpl<*>? {
        if (root == null || !root.visible || root.disposed) return null
        val impl = root.attributes["nativeImpl"]
        if (impl is DesktopTextInputNativeImpl<*>) return impl
        for (child in root.children) {
            val found = findFirstTextInput(child)
            if (found != null) return found
        }
        return null
    }

    private fun findFirstCheckbox(root: DesktopNativeWidget?): DesktopCheckboxNativeImpl<*>? {
        if (root == null || !root.visible || root.disposed) return null
        val impl = root.attributes["nativeImpl"]
        if (impl is DesktopCheckboxNativeImpl<*>) return impl
        for (child in root.children) {
            val found = findFirstCheckbox(child)
            if (found != null) return found
        }
        return null
    }

    private fun findFirstSelect(root: DesktopNativeWidget?): DesktopSelectNativeImpl<*>? {
        if (root == null || !root.visible || root.disposed) return null
        val impl = root.attributes["nativeImpl"]
        if (impl is DesktopSelectNativeImpl<*>) return impl
        for (child in root.children) {
            val found = findFirstSelect(child)
            if (found != null) return found
        }
        return null
    }

    private fun isPixelSurfaceWidget(widget: DesktopNativeWidget?): Boolean {
        if (widget == null || !widget.visible || widget.disposed) return false
        return widget.attributes.containsKey("captureRgba") ||
            widget.attributes.containsKey("captureBgra") ||
            widget.attributes.containsKey("frameBufferRgba") ||
            widget.attributes.containsKey("frameBufferBgra") ||
            widget.attributes.containsKey("surfaceRenderHook")
    }

    private fun findFirstPixelSurface(root: DesktopNativeWidget?): DesktopNativeWidget? {
        if (root == null || !root.visible || root.disposed) return null
        if (isPixelSurfaceWidget(root)) return root
        for (child in root.children) {
            val found = findFirstPixelSurface(child)
            if (found != null) return found
        }
        return null
    }

    private fun findFirstTabContainer(root: DesktopNativeWidget?): DesktopTabContainerNativeImpl<*>? {
        if (root == null || !root.visible || root.disposed) return null
        val impl = root.attributes["nativeImpl"]
        if (impl is DesktopTabContainerNativeImpl<*>) return impl
        for (child in root.children) {
            val found = findFirstTabContainer(child)
            if (found != null) return found
        }
        return null
    }

    private fun findFirstLazyGrid(root: DesktopNativeWidget?): DesktopLazyGridNativeImpl<*>? {
        if (root == null || !root.visible || root.disposed) return null
        val impl = root.attributes["nativeImpl"]
        if (impl is DesktopLazyGridNativeImpl<*>) return impl
        for (child in root.children) {
            val found = findFirstLazyGrid(child)
            if (found != null) return found
        }
        return null
    }

    private fun collectDirectOrNestedButtons(
        root: DesktopNativeWidget?,
        out: MutableList<DesktopButtonNativeImpl<*>> = mutableListOf()
    ): List<DesktopButtonNativeImpl<*>> {
        if (root == null || !root.visible || root.disposed || isWidgetMountedInTitleBar(root)) return out
        val impl = root.attributes["nativeImpl"]
        if (impl is DesktopButtonNativeImpl<*>) {
            out.add(impl)
        }
        for (child in root.children) {
            collectDirectOrNestedButtons(child, out)
        }
        return out
    }

    private fun collectDirectOrNestedTexts(
        root: DesktopNativeWidget?,
        out: MutableList<DesktopTextNativeImpl<*>> = mutableListOf()
    ): List<DesktopTextNativeImpl<*>> {
        if (root == null || !root.visible || root.disposed || isWidgetMountedInTitleBar(root)) return out
        val impl = root.attributes["nativeImpl"]
        if (impl is DesktopTextNativeImpl<*>) {
            out.add(impl)
        }
        for (child in root.children) {
            collectDirectOrNestedTexts(child, out)
        }
        return out
    }

    private fun collectDirectOrNestedProgressBars(
        root: DesktopNativeWidget?,
        out: MutableList<DesktopProgressBarNativeImpl<*>> = mutableListOf()
    ): List<DesktopProgressBarNativeImpl<*>> {
        if (root == null || !root.visible || root.disposed || isWidgetMountedInTitleBar(root)) return out
        val impl = root.attributes["nativeImpl"]
        if (impl is DesktopProgressBarNativeImpl<*>) {
            out.add(impl)
        }
        for (child in root.children) {
            collectDirectOrNestedProgressBars(child, out)
        }
        return out
    }

    /**
     * Renders the visual BSON / JSON / Binary Plist Editor powered by `LazyGridTree`
     * (`DataEditorFragment`) with interactive tabs, filter bar, format converter & byte-size strip,
     * 4-column `LazyGridTree` (with inline editable Key & Value `TextField`s, `CheckBox` toggles,
     * 12-datatype `Select` popup menus, expand/collapse branch toggles, and `+ Leaf`, `+ Branch`,
     * `↑`, `↓`, `Dup`, `Del` row buttons), and bottom status bar.
     */
    private fun renderDataEditorLazyGridTreeLayout(
        hwnd: HWND,
        hdc: HDC?,
        theme: SystemThemePalette,
        fontSmall: HFONT?,
        fontSmallBold: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        rootWidget: DesktopNativeWidget?,
        clientW: Int,
        clientH: Int
    ) {
        val padX = 10
        var curY = 8

        // 1. Top Toolbar (.editor-toolbar)
        val toolbarNode = findWidgetByClass(rootWidget, "editor-toolbar")
        val toolbarH = 36
        drawRoundedRect(
            hdc = hdc,
            left = padX,
            top = curY,
            right = clientW - padX,
            bottom = curY + toolbarH,
            radius = 6,
            fillColor = theme.bgPanel,
            borderColor = theme.borderSubtle
        )
        val toolbarButtons = collectDirectOrNestedButtons(toolbarNode)
        var tbX = padX + 8
        val tbBtnTop = curY + 5
        val tbBtnBottom = curY + toolbarH - 5
        for (btn in toolbarButtons) {
            val label = btn.getText().trim()
            if (label.isEmpty()) continue
            val isPrimaryNew = label.startsWith("+")
            val btnW = max(68, label.length * 7 + 16)
            if (tbX + btnW > clientW - padX - 6) break
            val bg = when {
                label.contains("JSON") && isPrimaryNew -> theme.accentBlueSoftBg
                label.contains("BSON") && isPrimaryNew -> rgb(58, 38, 82)
                label.contains("Plist") && isPrimaryNew -> theme.accentGreenSoftBg
                else -> theme.bgCard
            }
            val border = when {
                label.contains("JSON") && isPrimaryNew -> theme.accentBlueBorder
                label.contains("BSON") && isPrimaryNew -> rgb(148, 92, 220)
                label.contains("Plist") && isPrimaryNew -> theme.accentGreenBorder
                else -> theme.borderSubtle
            }
            drawRoundedButton(
                hdc = hdc,
                font = fontSmallBold,
                label = label,
                left = tbX,
                top = tbBtnTop,
                right = tbX + btnW,
                bottom = tbBtnBottom,
                bg = bg,
                border = border,
                fg = theme.textPrimary,
                radius = 4,
                widget = btn.nativeWidget,
                hwndForHover = hwnd
            )
            val capturedLeft = tbX
            val capturedRight = tbX + btnW
            hitRegions.add(
                HitRegion(
                    left = capturedLeft,
                    top = tbBtnTop,
                    right = capturedRight,
                    bottom = tbBtnBottom,
                    widget = btn.nativeWidget,
                    onClick = { _, _ -> btn.dispatchClick() }
                )
            )
            tbX += btnW + 6
        }
        curY += toolbarH + 6

        // 2. Open Document Tabs Bar (DesktopTabContainerNativeImpl)
        val tabContainer = findFirstTabContainer(rootWidget)
        val tabBarH = 30
        if (tabContainer != null) {
            val titles = tabContainer.getTabTitles()
            val activeIdx = tabContainer.getSelectedTabIndex()
            var tabX = padX
            for (idx in titles.indices) {
                val title = titles[idx].ifBlank { "Tab ${idx + 1}" }
                val isSelected = idx == activeIdx
                val tabW = (title.length * 7 + 44).coerceIn(110, 260)
                if (tabX + tabW > clientW - padX) break
                val tabBg = if (isSelected) theme.accentBlueSoftBg else theme.bgPanel
                val tabBorder = if (isSelected) theme.accentBlueBorder else theme.borderSubtle
                val tabFg = if (isSelected) theme.accentBlueText else theme.textSecondary
                drawRoundedRect(
                    hdc = hdc,
                    left = tabX,
                    top = curY,
                    right = tabX + tabW,
                    bottom = curY + tabBarH,
                    radius = 5,
                    fillColor = tabBg,
                    borderColor = tabBorder
                )
                SelectObject(hdc, if (isSelected) fontSmallBold else fontSmall)
                drawTextInRect(
                    hdc = hdc,
                    text = title,
                    left = tabX + 10,
                    top = curY + 2,
                    right = tabX + tabW - 26,
                    bottom = curY + tabBarH - 2,
                    colorRef = tabFg
                )
                // Close '×' button inside tab
                val closeLeft = tabX + tabW - 22
                val closeRight = tabX + tabW - 5
                val closeTop = curY + 6
                val closeBottom = curY + tabBarH - 6
                drawRoundedRect(
                    hdc = hdc,
                    left = closeLeft,
                    top = closeTop,
                    right = closeRight,
                    bottom = closeBottom,
                    radius = 3,
                    fillColor = theme.bgElevated,
                    borderColor = theme.borderSubtle
                )
                SelectObject(hdc, fontSmallBold)
                drawTextInRect(hdc, "×", closeLeft, closeTop - 1, closeRight, closeBottom, theme.textSecondary, center = true)

                val capturedIdx = idx
                val capturedTabLeft = tabX
                hitRegions.add(
                    HitRegion(
                        left = capturedTabLeft,
                        top = curY,
                        right = closeLeft - 2,
                        bottom = curY + tabBarH,
                        onClick = { _, _ -> tabContainer.requestSelectTab(capturedIdx) }
                    )
                )
                hitRegions.add(
                    HitRegion(
                        left = closeLeft,
                        top = closeTop,
                        right = closeRight,
                        bottom = closeBottom,
                        onClick = { _, _ -> tabContainer.requestCloseTab(capturedIdx) }
                    )
                )
                tabX += tabW + 6
            }
            curY += tabBarH + 6
        }

        // 3. Filter & Tree Controls Bar (.editor-filter-bar / .grid-tree-toolbar)
        val filterBarNode = findWidgetByClass(rootWidget, "editor-filter-bar")
            ?: findWidgetByClass(rootWidget, "grid-tree-toolbar")
        val filterBarH = 34
        if (filterBarNode != null) {
            drawRoundedRect(
                hdc = hdc,
                left = padX,
                top = curY,
                right = clientW - padX,
                bottom = curY + filterBarH,
                radius = 6,
                fillColor = theme.bgPanel,
                borderColor = theme.borderSubtle
            )
            var fx = padX + 10
            SelectObject(hdc, fontSmallBold)
            drawTextInRect(hdc, "Filter:", fx, curY, fx + 42, curY + filterBarH, theme.textSecondary)
            fx += 44

            val filterInput = findFirstTextInput(filterBarNode)
            val inputW = 230
            val inputTop = curY + 5
            val inputBottom = curY + filterBarH - 5
            if (filterInput != null) {
                val isFocused = focusedTextInput === filterInput
                val valStr = filterInput.getValue()
                val displayStr = when {
                    isFocused -> "$valStr|"
                    valStr.isNotEmpty() -> valStr
                    else -> filterInput.getPlaceholder().ifBlank { "Filter by key, path, type, or value..." }
                }
                val fgColor = if (valStr.isEmpty() && !isFocused) theme.textMuted else theme.textPrimary
                drawRoundedRect(
                    hdc = hdc,
                    left = fx,
                    top = inputTop,
                    right = fx + inputW,
                    bottom = inputBottom,
                    radius = 4,
                    fillColor = theme.bgBase,
                    borderColor = if (isFocused) theme.accentBlueBorder else theme.borderSubtle
                )
                SelectObject(hdc, fontSmall)
                drawTextInRect(hdc, displayStr, fx + 8, inputTop, fx + inputW - 6, inputBottom, fgColor)
                val capLeft = fx
                hitRegions.add(
                    HitRegion(
                        left = capLeft,
                        top = inputTop,
                        right = capLeft + inputW,
                        bottom = inputBottom,
                        widget = filterInput.nativeWidget,
                        isTextInput = true
                    )
                )
                fx += inputW + 8
            }

            val filterButtons = collectDirectOrNestedButtons(filterBarNode)
            for (btn in filterButtons) {
                val label = btn.getText().trim()
                if (label.isEmpty()) continue
                val isAddBtn = label.startsWith("+")
                val bw = max(62, label.length * 7 + 16)
                if (fx + bw > clientW - padX - 95) break
                drawRoundedButton(
                    hdc = hdc,
                    font = fontSmallBold,
                    label = label,
                    left = fx,
                    top = inputTop,
                    right = fx + bw,
                    bottom = inputBottom,
                    bg = if (isAddBtn) theme.accentGreenSoftBg else theme.bgCard,
                    border = if (isAddBtn) theme.accentGreenBorder else theme.borderSubtle,
                    fg = if (isAddBtn) theme.accentGreen else theme.textPrimary,
                    radius = 4,
                    widget = btn.nativeWidget,
                    hwndForHover = hwnd
                )
                val capLeft = fx
                val capRight = fx + bw
                hitRegions.add(
                    HitRegion(
                        left = capLeft,
                        top = inputTop,
                        right = capRight,
                        bottom = inputBottom,
                        widget = btn.nativeWidget,
                        onClick = { _, _ -> btn.dispatchClick() }
                    )
                )
                fx += bw + 6
            }

            val filterTexts = collectDirectOrNestedTexts(filterBarNode)
            val nodeCountStr = filterTexts.lastOrNull()?.getText()?.trim().orEmpty()
            if (nodeCountStr.isNotEmpty()) {
                SelectObject(hdc, fontSmallBold)
                drawTextInRect(
                    hdc = hdc,
                    text = nodeCountStr,
                    left = clientW - padX - 110,
                    top = curY,
                    right = clientW - padX - 10,
                    bottom = curY + filterBarH,
                    colorRef = theme.accentBlueText,
                    alignRight = true
                )
            }
            curY += filterBarH + 6
        }

        // 4. Format Converter & Byte Comparison Strip (.format-converter-strip / .format-comparison-card)
        val formatStripNode = findWidgetByClass(rootWidget, "format-converter-strip")
            ?: findWidgetByClass(rootWidget, "format-comparison-card")
        val formatStripH = 34
        if (formatStripNode != null) {
            drawRoundedRect(
                hdc = hdc,
                left = padX,
                top = curY,
                right = clientW - padX,
                bottom = curY + formatStripH,
                radius = 6,
                fillColor = theme.bgPanel,
                borderColor = theme.borderSubtle
            )
            val stripTexts = collectDirectOrNestedTexts(formatStripNode).map { it.getText().trim() }.filter { it.isNotEmpty() }
            val stripBtns = collectDirectOrNestedButtons(formatStripNode)
            val stripBars = collectDirectOrNestedProgressBars(formatStripNode)

            var sx = padX + 10
            SelectObject(hdc, fontSmallBold)
            drawTextInRect(hdc, "Active Format:", sx, curY, sx + 88, curY + formatStripH, theme.textSecondary)
            sx += 90

            val activeFmtText = stripTexts.getOrNull(1) ?: "JSON"
            val fmtBadgeW = 54
            val fmtTop = curY + 6
            val fmtBottom = curY + formatStripH - 6
            drawRoundedRect(
                hdc = hdc,
                left = sx,
                top = fmtTop,
                right = sx + fmtBadgeW,
                bottom = fmtBottom,
                radius = 4,
                fillColor = theme.accentBlueSoftBg,
                borderColor = theme.accentBlueBorder
            )
            SelectObject(hdc, fontSmallBold)
            drawTextInRect(hdc, activeFmtText, sx, fmtTop, sx + fmtBadgeW, fmtBottom, theme.accentBlueText, center = true)
            sx += fmtBadgeW + 8

            for (btn in stripBtns) {
                val label = btn.getText().trim()
                if (label.isEmpty()) continue
                val bw = max(92, label.length * 6 + 16)
                drawRoundedButton(
                    hdc = hdc,
                    font = fontSmall,
                    label = label,
                    left = sx,
                    top = fmtTop,
                    right = sx + bw,
                    bottom = fmtBottom,
                    bg = theme.bgCard,
                    border = theme.borderSubtle,
                    fg = theme.textPrimary,
                    radius = 4,
                    widget = btn.nativeWidget,
                    hwndForHover = hwnd
                )
                val capL = sx
                val capR = sx + bw
                hitRegions.add(
                    HitRegion(
                        left = capL,
                        top = fmtTop,
                        right = capR,
                        bottom = fmtBottom,
                        widget = btn.nativeWidget,
                        onClick = { _, _ -> btn.dispatchClick() }
                    )
                )
                sx += bw + 6
            }

            // Byte size comparison bars on the right
            val sizeTexts = stripTexts.filter { it.startsWith("JSON:") || it.startsWith("BSON:") || it.startsWith("Plist:") }
            var rightEdge = clientW - padX - 10
            for (i in sizeTexts.indices.reversed()) {
                val sTxt = sizeTexts[i]
                val pct = stripBars.getOrNull(i)?.getProgress()?.coerceIn(0f, 1f) ?: 0.5f
                val blockW = max(112, sTxt.length * 6 + 18)
                val bLeft = rightEdge - blockW
                if (bLeft < sx + 8) break
                SelectObject(hdc, fontSmall)
                drawTextInRect(hdc, sTxt, bLeft, curY + 2, rightEdge, curY + 18, theme.textSecondary, center = true)
                val barT = curY + 21
                val barB = curY + 27
                drawRoundedRect(hdc, bLeft + 4, barT, rightEdge - 4, barB, 3, theme.bgBase, theme.borderSubtle)
                val fillW = (((rightEdge - 4) - (bLeft + 4)) * pct).roundToInt()
                if (fillW > 2) {
                    val barColor = when (i) {
                        0 -> theme.accentBlue
                        1 -> rgb(168, 85, 247)
                        else -> theme.accentGreen
                    }
                    drawRoundedRect(hdc, bLeft + 4, barT, bLeft + 4 + fillW, barB, 3, barColor, barColor)
                }
                rightEdge = bLeft - 8
            }
            curY += formatStripH + 6
        }

        // 5. Primary 4-Column LazyGridTree Visual Editor
        val statusBarH = 30
        val gridTop = curY
        val gridBottom = clientH - statusBarH - 12
        val gridLeft = padX
        val gridRight = clientW - padX
        val gridW = max(200, gridRight - gridLeft)
        val gridH = max(120, gridBottom - gridTop)

        drawRoundedRect(
            hdc = hdc,
            left = gridLeft,
            top = gridTop,
            right = gridRight,
            bottom = gridBottom,
            radius = 6,
            fillColor = theme.bgPanel,
            borderColor = theme.borderSubtle
        )

        val gridImpl = findFirstLazyGrid(rootWidget)
        activeLazyGridImpl = gridImpl

        if (gridImpl != null && gridImpl.getTotalRowCount() > 0) {
            val scrollRailW = 20
            val contentW = max(160, gridW - scrollRailW)
            val col0W = (contentW * 0.34).roundToInt()
            val col1W = (contentW * 0.27).roundToInt()
            val col2W = (contentW * 0.15).roundToInt()
            val col3W = contentW - col0W - col1W - col2W

            val colLefts = intArrayOf(
                gridLeft,
                gridLeft + col0W,
                gridLeft + col0W + col1W,
                gridLeft + col0W + col1W + col2W
            )
            val colRights = intArrayOf(
                gridLeft + col0W,
                gridLeft + col0W + col1W,
                gridLeft + col0W + col1W + col2W,
                gridLeft + contentW
            )

            // Header Row (Row 0 of LazyGridTree)
            val headerH = 32
            val headerBottom = gridTop + headerH
            fillRectColor(hdc, gridLeft + 1, gridTop + 1, gridRight - 1, headerBottom, theme.bgElevated)
            drawLineColor(hdc, gridLeft + 1, headerBottom, gridRight - 1, headerBottom, theme.borderSubtle)

            val defaultHeaders = arrayOf("Key / Hierarchy", "Value", "Type (12 DataTypes)", "Node Actions")
            for (c in 0..3) {
                val hCell = gridImpl.getOrMaterializeCellWidget(c)
                val hTitle = extractWidgetText(hCell).ifBlank { defaultHeaders[c] }
                SelectObject(hdc, fontSmallBold)
                drawTextInRect(
                    hdc = hdc,
                    text = hTitle,
                    left = colLefts[c] + 10,
                    top = gridTop + 2,
                    right = colRights[c] - 6,
                    bottom = headerBottom - 2,
                    colorRef = theme.textPrimary
                )
                if (c > 0) {
                    drawLineColor(hdc, colLefts[c], gridTop + 1, colLefts[c], gridBottom - 1, theme.borderSubtle)
                }
            }

            // Data Rows (Rows 1..totalRows-1 of LazyGridTree)
            val totalDataRows = (gridImpl.getTotalRowCount() - 1).coerceAtLeast(0)
            val rowH = 34
            val bodyTop = headerBottom + 1
            val bodyH = max(rowH, gridBottom - bodyTop - 2)
            val maxVisibleRows = max(1, bodyH / rowH)
            val maxFirstRow = (totalDataRows - maxVisibleRows).coerceAtLeast(0)
            val firstDataRow = gridImpl.firstVisibleDataRow.coerceIn(0, maxFirstRow)
            gridImpl.firstVisibleDataRow = firstDataRow

            val rowsToDraw = min(maxVisibleRows, (totalDataRows - firstDataRow).coerceAtLeast(0))
            for (visIdx in 0 until rowsToDraw) {
                val dataRowIdx = firstDataRow + visIdx
                val gridRowIdx = 1 + dataRowIdx
                val rTop = bodyTop + visIdx * rowH
                val rBottom = min(gridBottom - 1, rTop + rowH)

                val rowBg = if (dataRowIdx % 2 == 0) theme.bgBase else theme.bgPanel
                fillRectColor(hdc, gridLeft + 1, rTop, gridLeft + contentW, rBottom, rowBg)
                drawLineColor(hdc, gridLeft + 1, rBottom, gridLeft + contentW, rBottom, theme.borderSubtle)
                for (c in 1..3) {
                    drawLineColor(hdc, colLefts[c], rTop, colLefts[c], rBottom, theme.borderSubtle)
                }

                // --- Column 0: Tree Indentation + Expand/Collapse Toggle + Type Badge + Editable Key ---
                val cell0 = gridImpl.getOrMaterializeCellWidget(gridRowIdx * 4 + 0)
                if (cell0 != null) {
                    val colWrapper = findWidgetByClass(cell0, "lazy-grid-tree-hierarchy-cell")
                        ?: findWidgetByClass(cell0, "lazy-grid-tree-col-wrapper")
                        ?: cell0
                    val attrIndent = (colWrapper.attributes["data-tree-indent-px"] as? String)?.toIntOrNull()
                    val attrDepth = (colWrapper.attributes["data-tree-depth"] as? String)?.toIntOrNull()
                    val computedPadLeft = (colWrapper.attributes["computedStyle"] as? com.nexustools.genericui.style.css.ComputedStyle)
                        ?.rawDeclarations?.get("padding-left")?.removeSuffix("px")?.trim()?.toIntOrNull()
                    val stylePadLeft = colWrapper.styleProperties["padding-left"]
                        ?.removeSuffix("px")?.trim()?.toIntOrNull()
                    val indentPx = when {
                        colWrapper.padding.left > 0 -> colWrapper.padding.left
                        attrIndent != null && attrIndent > 0 -> attrIndent
                        computedPadLeft != null && computedPadLeft > 0 -> computedPadLeft
                        stylePadLeft != null && stylePadLeft > 0 -> stylePadLeft
                        attrDepth != null && attrDepth > 0 -> attrDepth * 20
                        else -> 0
                    }
                    val baseLeftX = colLefts[0] + 8
                    var c0X = (baseLeftX + indentPx).coerceAtMost(colRights[0] - 90)
                    if (indentPx > 0) {
                        val stepPx = 20
                        val levels = (indentPx / stepPx).coerceAtLeast(1)
                        val midY = (rTop + rBottom) / 2
                        for (lvl in 0 until levels) {
                            val guideX = (baseLeftX + lvl * stepPx + 8).coerceAtMost(c0X - 2)
                            drawLineColor(hdc, guideX, rTop, guideX, rBottom, theme.borderSubtle)
                            if (lvl == levels - 1) {
                                drawLineColor(hdc, guideX, midY, (c0X - 3).coerceAtLeast(guideX), midY, theme.borderSubtle)
                            }
                        }
                    }

                    val toggleBtnNode = findWidgetByClass(cell0, "lazy-grid-tree-toggle-btn")
                    val toggleBtnImpl = toggleBtnNode?.attributes?.get("nativeImpl") as? DesktopButtonNativeImpl<*>
                    if (toggleBtnImpl != null) {
                        val tTop = rTop + 6
                        val tBottom = rBottom - 6
                        val tRight = c0X + 22
                        drawRoundedButton(
                            hdc = hdc,
                            font = fontSmallBold,
                            label = toggleBtnImpl.getText().ifBlank { "▼" },
                            left = c0X,
                            top = tTop,
                            right = tRight,
                            bottom = tBottom,
                            bg = theme.bgElevated,
                            border = theme.borderSubtle,
                            fg = theme.accentBlueText,
                            radius = 4,
                            widget = toggleBtnImpl.nativeWidget,
                            hwndForHover = hwnd
                        )
                        val capL = c0X
                        val capR = tRight
                        hitRegions.add(
                            HitRegion(
                                left = capL,
                                top = tTop,
                                right = capR,
                                bottom = tBottom,
                                tooltip = toggleBtnImpl.getTooltipText(),
                                widget = toggleBtnImpl.nativeWidget,
                                onClick = { _, _ -> toggleBtnImpl.dispatchClick() }
                            )
                        )
                        c0X = tRight + 5
                    } else {
                        SelectObject(hdc, fontSmallBold)
                        drawTextInRect(hdc, "•", c0X, rTop, c0X + 14, rBottom, theme.textMuted, center = true)
                        c0X += 16
                    }

                    val keyCellTexts = collectDirectOrNestedTexts(cell0)
                        .map { it.getText().trim() }
                        .filter { it.isNotEmpty() && it != "•" }
                    val typeBadgeLabel = keyCellTexts.firstOrNull() ?: ""
                    if (typeBadgeLabel.isNotEmpty()) {
                        val badgeW = max(34, typeBadgeLabel.length * 6 + 10)
                        val bTop = rTop + 7
                        val bBottom = rBottom - 7
                        val isBranchBadge = typeBadgeLabel in setOf("OBJ", "MAP", "ARR")
                        drawRoundedRect(
                            hdc = hdc,
                            left = c0X,
                            top = bTop,
                            right = c0X + badgeW,
                            bottom = bBottom,
                            radius = 3,
                            fillColor = if (isBranchBadge) theme.accentBlueSoftBg else theme.bgCard,
                            borderColor = if (isBranchBadge) theme.accentBlueBorder else theme.borderSubtle
                        )
                        SelectObject(hdc, fontSmallBold)
                        drawTextInRect(
                            hdc = hdc,
                            text = typeBadgeLabel,
                            left = c0X,
                            top = bTop,
                            right = c0X + badgeW,
                            bottom = bBottom,
                            colorRef = if (isBranchBadge) theme.accentBlueText else theme.textSecondary,
                            center = true
                        )
                        c0X += badgeW + 6
                    }

                    val keyInput = findFirstTextInput(cell0)
                    if (keyInput != null) {
                        val kLeft = c0X
                        val kRight = max(kLeft + 48, colRights[0] - 6)
                        val kTop = rTop + 5
                        val kBottom = rBottom - 5
                        val isFocused = focusedTextInput === keyInput
                        val keyText = keyInput.getValue()
                        val shownText = if (isFocused) "$keyText|" else keyText
                        drawRoundedRect(
                            hdc = hdc,
                            left = kLeft,
                            top = kTop,
                            right = kRight,
                            bottom = kBottom,
                            radius = 4,
                            fillColor = theme.bgCard,
                            borderColor = if (isFocused) theme.accentBlueBorder else theme.borderSubtle
                        )
                        SelectObject(hdc, fontNormal)
                        drawTextInRect(hdc, shownText, kLeft + 6, kTop, kRight - 6, kBottom, theme.textPrimary)
                        hitRegions.add(
                            HitRegion(
                                left = kLeft,
                                top = kTop,
                                right = kRight,
                                bottom = kBottom,
                                tooltip = keyInput.getTooltipText(),
                                widget = keyInput.nativeWidget,
                                isTextInput = true
                            )
                        )
                    } else {
                        val selectButtons = collectDirectOrNestedButtons(cell0)
                            .filter { it !== toggleBtnImpl }
                        val selectBtn = selectButtons.firstOrNull()
                        val staticKeyLabel = selectBtn?.getText()?.takeIf { it.isNotBlank() }
                            ?: keyCellTexts.drop(1).firstOrNull { it != typeBadgeLabel }
                            ?: keyCellTexts.getOrNull(1)
                            ?: "root"
                        SelectObject(hdc, fontBold)
                        drawTextInRect(hdc, staticKeyLabel, c0X + 2, rTop, colRights[0] - 6, rBottom, theme.accentBlueText)
                        if (selectBtn != null) {
                            hitRegions.add(
                                HitRegion(
                                    left = c0X,
                                    top = rTop + 4,
                                    right = colRights[0] - 6,
                                    bottom = rBottom - 4,
                                    tooltip = selectBtn.getTooltipText(),
                                    widget = selectBtn.nativeWidget,
                                    onClick = { _, _ -> selectBtn.dispatchClick() }
                                )
                            )
                        }
                    }
                }

                // --- Column 1: Inline Editable Value (TextField / CheckBox / Summary Badge) ---
                val cell1 = gridImpl.getOrMaterializeCellWidget(gridRowIdx * 4 + 1)
                if (cell1 != null) {
                    val c1Left = colLefts[1] + 6
                    val c1Right = colRights[1] - 6
                    val c1Top = rTop + 5
                    val c1Bottom = rBottom - 5

                    val valInput = findFirstTextInput(cell1)
                    val boolCheck = findFirstCheckbox(cell1)
                    when {
                        valInput != null -> {
                            val isFocused = focusedTextInput === valInput
                            val vText = valInput.getValue()
                            val shownText = if (isFocused) "$vText|" else vText
                            drawRoundedRect(
                                hdc = hdc,
                                left = c1Left,
                                top = c1Top,
                                right = c1Right,
                                bottom = c1Bottom,
                                radius = 4,
                                fillColor = theme.bgCard,
                                borderColor = if (isFocused) theme.accentBlueBorder else theme.borderSubtle
                            )
                            SelectObject(hdc, fontNormal)
                            drawTextInRect(hdc, shownText, c1Left + 6, c1Top, c1Right - 6, c1Bottom, theme.textPrimary)
                            hitRegions.add(
                                HitRegion(
                                    left = c1Left,
                                    top = c1Top,
                                    right = c1Right,
                                    bottom = c1Bottom,
                                    tooltip = valInput.getTooltipText(),
                                    widget = valInput.nativeWidget,
                                    isTextInput = true
                                )
                            )
                        }
                        boolCheck != null -> {
                            val checked = boolCheck.isChecked()
                            SelectObject(hdc, fontSmallBold)
                            drawCheckboxControl(
                                hdc = hdc,
                                theme = theme,
                                label = if (checked) "true" else "false",
                                checked = checked,
                                left = c1Left + 4,
                                top = c1Top,
                                right = c1Right,
                                bottom = c1Bottom,
                                widget = boolCheck.nativeWidget,
                                hwndForHover = hwnd
                            )
                            hitRegions.add(
                                HitRegion(
                                    left = c1Left,
                                    top = c1Top,
                                    right = c1Right,
                                    bottom = c1Bottom,
                                    tooltip = boolCheck.getTooltipText(),
                                    widget = boolCheck.nativeWidget,
                                    onClick = { _, _ ->
                                        val next = !boolCheck.isChecked()
                                        boolCheck.setChecked(next)
                                        boolCheck.dispatchToggle(next)
                                    }
                                )
                            )
                        }
                        else -> {
                            val summaryText = extractWidgetText(cell1)
                            SelectObject(hdc, fontSmallBold)
                            drawTextInRect(hdc, summaryText, c1Left + 4, rTop, c1Right, rBottom, theme.textSecondary)
                        }
                    }
                }

                // --- Column 2: Inline Type Dropdown (12 DataTypes) ---
                val cell2 = gridImpl.getOrMaterializeCellWidget(gridRowIdx * 4 + 2)
                if (cell2 != null) {
                    val c2Left = colLefts[2] + 6
                    val c2Right = colRights[2] - 6
                    val c2Top = rTop + 5
                    val c2Bottom = rBottom - 5
                    val selectImpl = findFirstSelect(cell2)
                    if (selectImpl != null) {
                        val selectedVal = selectImpl.getSelectedValue()
                        val selectedLabel = selectImpl.getOptions()
                            .firstOrNull { it.first == selectedVal }?.second
                            ?: selectedVal.orEmpty()
                        SelectObject(hdc, fontSmall)
                        drawSelectControl(
                            hdc = hdc,
                            theme = theme,
                            label = selectedLabel,
                            left = c2Left,
                            top = c2Top,
                            right = c2Right,
                            bottom = c2Bottom,
                            widget = selectImpl.nativeWidget,
                            hwndForHover = hwnd
                        )
                        hitRegions.add(
                            HitRegion(
                                left = c2Left,
                                top = c2Top,
                                right = c2Right,
                                bottom = c2Bottom,
                                tooltip = selectImpl.getTooltipText(),
                                widget = selectImpl.nativeWidget,
                                onClick = { _, _ ->
                                    openSelectPopupMenu(hwnd, selectImpl, c2Left, c2Bottom)
                                }
                            )
                        )
                    }
                }

                // --- Column 3: Inline Node Action Buttons (+ Leaf, + Branch, ↑, ↓, Dup, Del) ---
                val cell3 = gridImpl.getOrMaterializeCellWidget(gridRowIdx * 4 + 3)
                if (cell3 != null) {
                    val actionBtns = collectDirectOrNestedButtons(cell3)
                    var ax = colLefts[3] + 6
                    val aTop = rTop + 5
                    val aBottom = rBottom - 5
                    for (btn in actionBtns) {
                        val label = btn.getText().trim()
                        if (label.isEmpty()) continue
                        val enabled = btn.isEnabled()
                        val bw = when {
                            label.length <= 1 -> 26
                            label.length <= 3 -> 36
                            else -> label.length * 6 + 14
                        }
                        if (ax + bw > colRights[3] - 4) break

                        val isDanger = label == "Del"
                        val isAdd = label.startsWith("+")
                        val bg = when {
                            !enabled -> theme.bgBase
                            isDanger -> theme.dangerBg
                            isAdd -> theme.accentBlueSoftBg
                            else -> theme.bgCard
                        }
                        val border = when {
                            !enabled -> theme.borderSubtle
                            isDanger -> theme.dangerBorder
                            isAdd -> theme.accentBlueBorder
                            else -> theme.borderSubtle
                        }
                        val fg = when {
                            !enabled -> theme.textMuted
                            isDanger -> theme.dangerText
                            isAdd -> theme.accentBlueText
                            else -> theme.textPrimary
                        }
                        drawRoundedButton(
                            hdc = hdc,
                            font = fontSmallBold,
                            label = label,
                            left = ax,
                            top = aTop,
                            right = ax + bw,
                            bottom = aBottom,
                            bg = bg,
                            border = border,
                            fg = fg,
                            radius = 4,
                            widget = if (enabled) btn.nativeWidget else null,
                            hwndForHover = hwnd
                        )
                        if (enabled) {
                            val capL = ax
                            val capR = ax + bw
                            hitRegions.add(
                                HitRegion(
                                    left = capL,
                                    top = aTop,
                                    right = capR,
                                    bottom = aBottom,
                                    widget = btn.nativeWidget,
                                    onClick = { _, _ -> btn.dispatchClick() }
                                )
                            )
                        }
                        ax += bw + 4
                    }
                }
            }

            // Right Vertical Scrollbar Rail for LazyGridTree
            val railLeft = gridRight - scrollRailW
            val railRight = gridRight - 1
            fillRectColor(hdc, railLeft, headerBottom + 1, railRight, gridBottom - 1, theme.bgElevated)
            drawLineColor(hdc, railLeft, gridTop + 1, railLeft, gridBottom - 1, theme.borderSubtle)

            val upBtnTop = headerBottom + 2
            val upBtnBottom = upBtnTop + 22
            val downBtnBottom = gridBottom - 2
            val downBtnTop = downBtnBottom - 22

            drawRoundedButton(
                hdc = hdc,
                font = fontSmallBold,
                label = "▲",
                left = railLeft + 2,
                top = upBtnTop,
                right = railRight - 2,
                bottom = upBtnBottom,
                bg = theme.bgCard,
                border = theme.borderSubtle,
                fg = theme.textPrimary,
                radius = 3
            )
            hitRegions.add(
                HitRegion(
                    left = railLeft + 2,
                    top = upBtnTop,
                    right = railRight - 2,
                    bottom = upBtnBottom,
                    onClick = { _, _ ->
                        gridImpl.firstVisibleDataRow = (gridImpl.firstVisibleDataRow - 2).coerceAtLeast(0)
                    }
                )
            )

            drawRoundedButton(
                hdc = hdc,
                font = fontSmallBold,
                label = "▼",
                left = railLeft + 2,
                top = downBtnTop,
                right = railRight - 2,
                bottom = downBtnBottom,
                bg = theme.bgCard,
                border = theme.borderSubtle,
                fg = theme.textPrimary,
                radius = 3
            )
            hitRegions.add(
                HitRegion(
                    left = railLeft + 2,
                    top = downBtnTop,
                    right = railRight - 2,
                    bottom = downBtnBottom,
                    onClick = { _, _ ->
                        gridImpl.firstVisibleDataRow = (gridImpl.firstVisibleDataRow + 2).coerceAtMost(maxFirstRow)
                    }
                )
            )

            val trackTop = upBtnBottom + 4
            val trackBottom = downBtnTop - 4
            val trackH = (trackBottom - trackTop).coerceAtLeast(10)
            if (totalDataRows > 0 && trackBottom > trackTop) {
                val thumbRatio = (rowsToDraw.toFloat() / totalDataRows.toFloat()).coerceIn(0.12f, 1f)
                val thumbH = max(18, (trackH * thumbRatio).roundToInt()).coerceAtMost(trackH)
                val trackRange = max(1, trackH - thumbH)
                val scrollFrac = if (maxFirstRow > 0) firstDataRow.toFloat() / maxFirstRow.toFloat() else 0f
                val thumbTop = trackTop + (trackRange * scrollFrac).roundToInt().coerceIn(0, trackRange)
                val thumbBottom = thumbTop + thumbH

                val isThumbDragging = isDraggingLazyGridScrollbarThumb
                val isThumbHovered = isThumbDragging ||
                    hoveredWidget === lazyGridScrollbarThumbWidget ||
                    isRectHovered(hwnd, railLeft, thumbTop, railRight, thumbBottom)

                val thumbFill = when {
                    isThumbDragging -> lightenColorRef(theme.accentBlue, -18)
                    isThumbHovered -> lightenColorRef(theme.accentBlue, 28)
                    else -> theme.accentBlue
                }
                val thumbBorder = when {
                    isThumbDragging -> rgb(255, 255, 255)
                    isThumbHovered -> lightenColorRef(theme.accentBlueBorder, 40)
                    else -> theme.accentBlueBorder
                }
                val thumbInset = if (isThumbHovered || isThumbDragging) 2 else 3
                drawRoundedRect(
                    hdc = hdc,
                    left = railLeft + thumbInset,
                    top = thumbTop,
                    right = railRight - thumbInset,
                    bottom = thumbBottom,
                    radius = 4,
                    fillColor = thumbFill,
                    borderColor = thumbBorder
                )

                val applyLazyGridScrollFromMouseY: (Int) -> Unit = { mouseY ->
                    val newThumbTop = (mouseY - lazyGridScrollbarDragGrabOffsetY).coerceIn(trackTop, trackTop + trackRange)
                    val frac = (newThumbTop - trackTop).toFloat() / trackRange.toFloat()
                    gridImpl.firstVisibleDataRow = (frac * maxFirstRow.toFloat()).roundToInt().coerceIn(0, maxFirstRow)
                }

                hitRegions.add(
                    HitRegion(
                        left = railLeft,
                        top = trackTop,
                        right = railRight,
                        bottom = trackBottom,
                        widget = lazyGridScrollbarRailWidget,
                        onClick = { _, my ->
                            isDraggingLazyGridScrollbarThumb = true
                            lazyGridScrollbarDragGrabOffsetY = if (my in thumbTop..thumbBottom) {
                                my - thumbTop
                            } else {
                                thumbH / 2
                            }
                            applyLazyGridScrollFromMouseY(my)
                        },
                        onDrag = { _, my ->
                            applyLazyGridScrollFromMouseY(my)
                        }
                    )
                )
                hitRegions.add(
                    HitRegion(
                        left = railLeft,
                        top = thumbTop,
                        right = railRight,
                        bottom = thumbBottom,
                        widget = lazyGridScrollbarThumbWidget,
                        onClick = { _, my ->
                            isDraggingLazyGridScrollbarThumb = true
                            lazyGridScrollbarDragGrabOffsetY = (my - thumbTop).coerceIn(0, thumbH)
                        },
                        onDrag = { _, my ->
                            applyLazyGridScrollFromMouseY(my)
                        }
                    )
                )
            }
        }

        // 6. Bottom Status Bar (.status-bar)
        val statusBarNode = findWidgetByClass(rootWidget, "status-bar")
        val statusTop = clientH - statusBarH - 6
        val statusBottom = clientH - 6
        drawRoundedRect(
            hdc = hdc,
            left = padX,
            top = statusTop,
            right = clientW - padX,
            bottom = statusBottom,
            radius = 5,
            fillColor = theme.bgPanel,
            borderColor = theme.borderSubtle
        )
        if (statusBarNode != null) {
            val statusTexts = collectDirectOrNestedTexts(statusBarNode).map { it.getText().trim() }.filter { it.isNotEmpty() }
            val pathTxt = statusTexts.getOrNull(1) ?: "root"
            val typeTxt = statusTexts.getOrNull(2) ?: "OBJECT"
            val msgTxt = statusTexts.getOrNull(3) ?: statusTexts.lastOrNull().orEmpty()

            SelectObject(hdc, fontSmallBold)
            drawTextInRect(hdc, "Selected: $pathTxt  [$typeTxt]", padX + 10, statusTop, padX + 340, statusBottom, theme.accentBlueText)
            SelectObject(hdc, fontSmall)
            drawTextInRect(hdc, msgTxt, padX + 350, statusTop, clientW - padX - 10, statusBottom, theme.textSecondary)
        }
    }

    /**
     * Renders an application structure with `<header class="app-header">`,
     * `<section class="metrics-strip">`, and `<main class="queues-container">` using native OS theming.
     */
    private fun renderHeaderMetricsQueuesLayout(
        hdc: HDC?,
        theme: SystemThemePalette,
        fontSmall: HFONT?,
        fontSmallBold: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        fontTitle: HFONT?,
        fontMetricBig: HFONT?,
        appHeader: DesktopNativeWidget,
        metricsStrip: DesktopNativeWidget,
        queuesContainer: DesktopNativeWidget,
        clientW: Int,
        clientH: Int
    ) {
        val headerH = 44
        val padX = 16

        // 1. Top Toolbar Header (<header class="app-header">) — no icon or app name (titlebar has both)
        fillRectColor(hdc, 0, 0, clientW, headerH, theme.bgPanel)
        drawLineColor(hdc, 0, headerH, clientW, headerH, theme.borderSubtle)

        val brandSubtitleNode = findWidgetByClass(appHeader, "brand-subtitle")
        val gpuBadgeNode = findWidgetByClass(appHeader, "gpu-badge")
        val headerActionsNode = findWidgetByClass(appHeader, "header-actions")

        val brandSubText = extractWidgetText(brandSubtitleNode).ifBlank { "Select a directory to begin conversion..." }
        val gpuBadgeText = extractWidgetText(gpuBadgeNode).ifBlank { "Probing GPU Hardware Encoder..." }

        // GPU Hardware Encoder Status Box on the Left
        val gpuBadgeLeft = padX
        val gpuBadgeW = max(180, gpuBadgeText.length * 7 + 20)
        val gpuBadgeTop = (headerH - 24) / 2
        fillRectColor(hdc, gpuBadgeLeft, gpuBadgeTop, gpuBadgeLeft + gpuBadgeW, gpuBadgeTop + 24, theme.bgCard)
        drawRectBorder(hdc, gpuBadgeLeft, gpuBadgeTop, gpuBadgeLeft + gpuBadgeW, gpuBadgeTop + 24, theme.borderSubtle)
        SelectObject(hdc, fontSmallBold)
        drawTextInRect(
            hdc = hdc,
            text = gpuBadgeText,
            left = gpuBadgeLeft + 8,
            top = gpuBadgeTop,
            right = gpuBadgeLeft + gpuBadgeW - 8,
            bottom = gpuBadgeTop + 24,
            colorRef = theme.textPrimary,
            center = true
        )

        // Active Directory Path Label next to GPU status
        val dirLabelLeft = gpuBadgeLeft + gpuBadgeW + 12
        SelectObject(hdc, fontNormal)
        drawTextInRect(hdc, brandSubText, dirLabelLeft, 10, clientW - 320, headerH - 10, theme.textSecondary)

        // Header Right Actions: Quality Status + Add Directory Button
        val headerButtons = mutableListOf<DesktopButtonNativeImpl<*>>()
        val headerTexts = mutableListOf<DesktopTextNativeImpl<*>>()
        collectPanelControls(
            headerActionsNode,
            headerButtons,
            mutableListOf(),
            mutableListOf(),
            mutableListOf(),
            headerTexts
        )

        val addDirBtn = headerButtons.firstOrNull()
        val addDirLabel = addDirBtn?.getText()?.ifBlank { "Add Directory" } ?: "Add Directory"
        val btnW = max(108, addDirLabel.length * 8 + 20)
        val btnH = 26
        val btnRight = clientW - padX
        val btnLeft = btnRight - btnW
        val btnTop = (headerH - btnH) / 2

        fillRectColor(hdc, btnLeft, btnTop, btnRight, btnTop + btnH, theme.bgCard)
        drawRectBorder(hdc, btnLeft, btnTop, btnRight, btnTop + btnH, theme.borderSubtle)
        SelectObject(hdc, fontNormal)
        drawTextInRect(
            hdc = hdc,
            text = addDirLabel,
            left = btnLeft + 6,
            top = btnTop,
            right = btnRight - 6,
            bottom = btnTop + btnH,
            colorRef = theme.textPrimary,
            center = true
        )
        if (addDirBtn != null) {
            hitRegions.add(
                HitRegion(
                    left = btnLeft,
                    top = btnTop,
                    right = btnRight,
                    bottom = btnTop + btnH,
                    onClick = { _, _ -> addDirBtn.dispatchClick() }
                )
            )
        }

        val qualityText = headerTexts.firstOrNull()?.getText()?.ifBlank { "Quality: 24 (Pending)" }
            ?: "Quality: 24 (Pending)"
        val qPillW = max(130, qualityText.length * 7 + 18)
        val qPillRight = btnLeft - 8
        val qPillLeft = qPillRight - qPillW
        val qPillTop = (headerH - 24) / 2
        fillRectColor(hdc, qPillLeft, qPillTop, qPillRight, qPillTop + 24, theme.bgCard)
        drawRectBorder(hdc, qPillLeft, qPillTop, qPillRight, qPillTop + 24, theme.borderSubtle)
        SelectObject(hdc, fontSmall)
        drawTextInRect(
            hdc = hdc,
            text = qualityText,
            left = qPillLeft + 6,
            top = qPillTop,
            right = qPillRight - 6,
            bottom = qPillTop + 24,
            colorRef = theme.textSecondary,
            center = true
        )

        // 2. Top 3-Column Metrics Strip (<section class="metrics-strip">, 1.5fr : 1.0fr : 1.1fr)
        val stripTop = headerH + 14
        val stripH = 100
        val stripBottom = stripTop + stripH
        val availW = clientW - padX * 2
        val gap = 14
        val totalCardsW = availW - gap * 2
        val card1W = (totalCardsW * 1.5 / 3.6).roundToInt()
        val card2W = (totalCardsW * 1.0 / 3.6).roundToInt()
        val card3W = totalCardsW - card1W - card2W

        val metricCards = findWidgetsByClass(metricsStrip, "metric-card")
        val cardRects = listOf(
            intArrayOf(padX, stripTop, padX + card1W, stripBottom),
            intArrayOf(padX + card1W + gap, stripTop, padX + card1W + gap + card2W, stripBottom),
            intArrayOf(padX + card1W + gap + card2W + gap, stripTop, clientW - padX, stripBottom)
        )

        for (i in 0 until min(3, metricCards.size)) {
            val cardNode = metricCards[i]
            val rect = cardRects[i]
            val cL = rect[0]
            val cT = rect[1]
            val cR = rect[2]
            val cB = rect[3]

            drawRoundedRect(
                hdc = hdc,
                left = cL,
                top = cT,
                right = cR,
                bottom = cB,
                radius = 10,
                fillColor = theme.bgPanel,
                borderColor = theme.borderSubtle
            )

            val headerLeftNode = findWidgetByClass(cardNode, "metric-header-title")
            val headerRightNode = findWidgetByClass(cardNode, "metric-header-right")
            val bigNode = findWidgetByClass(cardNode, "metric-big")
            val subNodes = findWidgetsByClass(cardNode, "metric-sub")
            val progressNode = findWidgetByClass(cardNode, "progress-track")

            val headerLeftText = extractWidgetText(headerLeftNode).uppercase()
            val headerRightText = extractWidgetText(headerRightNode).uppercase()
            val bigText = extractWidgetText(bigNode)
            val sub1Text = subNodes.getOrNull(0)?.let { extractWidgetText(it) }.orEmpty()
            val sub2Text = subNodes.getOrNull(1)?.let { extractWidgetText(it) }.orEmpty()

            SelectObject(hdc, fontSmallBold)
            drawTextInRect(hdc, headerLeftText, cL + 16, cT + 12, cR - 120, cT + 28, theme.textSecondary)
            drawTextInRect(
                hdc = hdc,
                text = headerRightText,
                left = cR - 145,
                top = cT + 12,
                right = cR - 16,
                bottom = cT + 28,
                colorRef = theme.textSecondary,
                alignRight = true
            )

            val isEtaHighlight = widgetHasClass(bigNode, "eta-highlight")
            SelectObject(hdc, fontMetricBig)
            drawTextInRect(
                hdc = hdc,
                text = bigText,
                left = cL + 16,
                top = cT + 33,
                right = cL + 140,
                bottom = cT + 66,
                colorRef = if (isEtaHighlight) theme.accentBlueText else theme.textPrimary
            )

            SelectObject(hdc, fontSmall)
            drawTextInRect(
                hdc = hdc,
                text = sub1Text,
                left = cL + 125,
                top = cT + 41,
                right = cR - 16,
                bottom = cT + 61,
                colorRef = theme.textSecondary,
                alignRight = true
            )

            if (progressNode != null) {
                val pct = extractProgressBarValue(progressNode).coerceIn(0f, 1f)
                val isProbing = widgetHasClass(progressNode, "probing-fill")
                val barL = cL + 16
                val barR = cR - 16
                val barT = cB - 20
                val barB = cB - 12
                drawRoundedRect(
                    hdc = hdc,
                    left = barL,
                    top = barT,
                    right = barR,
                    bottom = barB,
                    radius = 4,
                    fillColor = theme.bgBase,
                    borderColor = theme.borderSubtle
                )
                val fillW = ((barR - barL) * pct).roundToInt()
                if (fillW > 2) {
                    val barColor = if (isProbing) theme.accentAmber else theme.accentBlue
                    drawRoundedRect(
                        hdc = hdc,
                        left = barL,
                        top = barT,
                        right = barL + fillW,
                        bottom = barB,
                        radius = 4,
                        fillColor = barColor,
                        borderColor = barColor
                    )
                }
            } else if (sub2Text.isNotEmpty()) {
                SelectObject(hdc, fontSmall)
                drawTextInRect(
                    hdc = hdc,
                    text = sub2Text,
                    left = cL + 16,
                    top = cB - 26,
                    right = cR - 16,
                    bottom = cB - 8,
                    colorRef = theme.textMuted
                )
            }
        }

        // 3. Main Dual Queue View (<main class="queues-container">, 1fr : 1fr)
        val queuesTop = stripBottom + 14
        val queuesBottom = clientH - 18
        val colGap = 16
        val colW = (availW - colGap) / 2
        val queueColumns = findWidgetsByClass(queuesContainer, "queue-column")

        val colRects = listOf(
            intArrayOf(padX, queuesTop, padX + colW, queuesBottom),
            intArrayOf(padX + colW + colGap, queuesTop, clientW - padX, queuesBottom)
        )

        for (colIdx in 0 until min(2, queueColumns.size)) {
            val colNode = queueColumns[colIdx]
            val rect = colRects[colIdx]
            val qL = rect[0]
            val qT = rect[1]
            val qR = rect[2]
            val qB = rect[3]

            drawRoundedRect(
                hdc = hdc,
                left = qL,
                top = qT,
                right = qR,
                bottom = qB,
                radius = 10,
                fillColor = theme.bgPanel,
                borderColor = theme.borderSubtle
            )

            // Column Header (height = 44px)
            val colHeaderBottom = qT + 44
            drawLineColor(hdc, qL + 1, colHeaderBottom, qR - 1, colHeaderBottom, theme.borderSubtle)

            val colTitleNode = findWidgetByClass(colNode, "queue-column-title")
            val colTitleText = extractWidgetText(colTitleNode)
            SelectObject(hdc, fontBold)
            drawTextInRect(hdc, colTitleText, qL + 16, qT + 11, qL + 240, colHeaderBottom - 8, theme.textPrimary)

            // Queue Counter Pills (Active, Queued, Done, Failed)
            val countersContainer = findWidgetByClass(colNode, "queue-counters")
            val pillWidgets = countersContainer?.children.orEmpty()
            var pillRight = qR - 14
            val pillTop = qT + 11
            val pillH = 22

            for (pillNode in pillWidgets.asReversed()) {
                val pText = extractWidgetText(pillNode)
                if (pText.isEmpty()) continue
                val pW = max(58, pText.length * 6 + 20)
                val pLeft = pillRight - pW

                val isActivePill = widgetHasClass(pillNode, "active")
                val isDonePill = widgetHasClass(pillNode, "done")
                val isFailedPill = widgetHasClass(pillNode, "failed")

                val pBg = when {
                    isActivePill -> theme.accentBlueSoftBg
                    isDonePill -> theme.accentGreenSoftBg
                    isFailedPill -> theme.accentRedSoftBg
                    else -> theme.bgCard
                }
                val pBorder = when {
                    isActivePill -> theme.accentBlueBorder
                    isDonePill -> theme.accentGreenBorder
                    isFailedPill -> theme.accentRedBorder
                    else -> theme.borderSubtle
                }
                val pFg = when {
                    isActivePill -> theme.accentBlueText
                    isDonePill -> theme.accentGreen
                    isFailedPill -> theme.accentRed
                    else -> theme.textSecondary
                }

                drawRoundedRect(
                    hdc = hdc,
                    left = pLeft,
                    top = pillTop,
                    right = pillRight,
                    bottom = pillTop + pillH,
                    radius = 11,
                    fillColor = pBg,
                    borderColor = pBorder
                )
                SelectObject(hdc, fontSmallBold)
                drawTextInRect(
                    hdc = hdc,
                    text = pText,
                    left = pLeft + 6,
                    top = pillTop,
                    right = pillRight - 6,
                    bottom = pillTop + pillH,
                    colorRef = pFg,
                    center = true
                )
                pillRight = pLeft - 6
            }

            // Column Body (.queue-body)
            val queueBody = findWidgetByClass(colNode, "queue-body")
            val bodyLeft = qL + 14
            val bodyRight = qR - 14
            var curY = colHeaderBottom + 14
            val maxBodyBottom = qB - 14

            for (bodyChild in queueBody?.children.orEmpty()) {
                if (curY + 26 > maxBodyBottom) break

                when {
                    widgetHasClass(bodyChild, "empty-state") -> {
                        val emptyH = 64
                        val boxBottom = min(maxBodyBottom, curY + emptyH)
                        drawDashedRoundedRect(
                            hdc = hdc,
                            left = bodyLeft,
                            top = curY,
                            right = bodyRight,
                            bottom = boxBottom,
                            radius = 8,
                            fillColor = theme.bgBase,
                            borderColor = theme.borderDash
                        )
                        val msg = extractWidgetText(bodyChild).ifBlank { "Waiting for directory selection..." }
                        SelectObject(hdc, fontNormal)
                        drawTextInRect(
                            hdc = hdc,
                            text = msg,
                            left = bodyLeft + 12,
                            top = curY,
                            right = bodyRight - 12,
                            bottom = boxBottom,
                            colorRef = theme.textMuted,
                            center = true
                        )
                        curY = boxBottom + 10
                    }

                    widgetHasClass(bodyChild, "section-title") -> {
                        val secText = extractWidgetText(bodyChild).uppercase()
                        SelectObject(hdc, fontSmallBold)
                        drawTextInRect(
                            hdc = hdc,
                            text = secText,
                            left = bodyLeft + 2,
                            top = curY,
                            right = bodyRight,
                            bottom = curY + 18,
                            colorRef = theme.textMuted
                        )
                        curY += 22
                    }

                    widgetHasClass(bodyChild, "slot-card") -> {
                        val cardH = 82
                        val cardB = min(maxBodyBottom, curY + cardH)
                        drawRoundedRect(
                            hdc = hdc,
                            left = bodyLeft,
                            top = curY,
                            right = bodyRight,
                            bottom = cardB,
                            radius = 8,
                            fillColor = theme.bgCard,
                            borderColor = theme.borderSubtle
                        )
                        val fileNode = findWidgetByClass(bodyChild, "slot-filename")
                        val pctNode = findWidgetByClass(bodyChild, "slot-pct")
                        val progNode = findWidgetByClass(bodyChild, "progress-track")
                        val tagNodes = findWidgetsByClass(bodyChild, "tag")

                        SelectObject(hdc, fontBold)
                        drawTextInRect(
                            hdc = hdc,
                            text = extractWidgetText(fileNode),
                            left = bodyLeft + 12,
                            top = curY + 8,
                            right = bodyRight - 72,
                            bottom = curY + 26,
                            colorRef = theme.textPrimary
                        )
                        drawTextInRect(
                            hdc = hdc,
                            text = extractWidgetText(pctNode),
                            left = bodyRight - 70,
                            top = curY + 8,
                            right = bodyRight - 12,
                            bottom = curY + 26,
                            colorRef = theme.accentBlueText,
                            alignRight = true
                        )

                        val pct = extractProgressBarValue(progNode).coerceIn(0f, 1f)
                        val pL = bodyLeft + 12
                        val pR = bodyRight - 12
                        val pT = curY + 32
                        val pB = curY + 38
                        drawRoundedRect(hdc, pL, pT, pR, pB, 3, theme.bgBase, theme.borderSubtle)
                        val fillW = ((pR - pL) * pct).roundToInt()
                        if (fillW > 2) {
                            drawRoundedRect(hdc, pL, pT, pL + fillW, pB, 3, theme.accentBlue, theme.accentBlue)
                        }

                        var tagX = bodyLeft + 12
                        val tagY = curY + 46
                        SelectObject(hdc, fontSmallBold)
                        for (tagNode in tagNodes) {
                            val tStr = extractWidgetText(tagNode)
                            if (tStr.isEmpty()) continue
                            val tW = max(48, tStr.length * 6 + 16)
                            if (tagX + tW > bodyRight - 10) break
                            val isVid = widgetHasClass(tagNode, "video")
                            val isAud = widgetHasClass(tagNode, "audio")
                            val isSub = widgetHasClass(tagNode, "sub")
                            val tBg = when {
                                isVid -> theme.accentBlueSoftBg
                                isAud -> theme.accentGreenSoftBg
                                isSub -> theme.accentAmberSoftBg
                                else -> theme.bgElevated
                            }
                            val tFg = when {
                                isVid -> theme.accentBlueText
                                isAud -> theme.accentGreen
                                isSub -> theme.accentAmber
                                else -> theme.textSecondary
                            }
                            drawRoundedRect(hdc, tagX, tagY, tagX + tW, tagY + 20, 4, tBg, theme.borderSubtle)
                            drawTextInRect(hdc, tStr, tagX + 6, tagY, tagX + tW - 6, tagY + 20, tFg, center = true)
                            tagX += tW + 6
                        }
                        curY = cardB + 8
                    }

                    widgetHasClass(bodyChild, "queue-item") -> {
                        val rowH = 34
                        val rowB = min(maxBodyBottom, curY + rowH)
                        drawRoundedRect(
                            hdc = hdc,
                            left = bodyLeft,
                            top = curY,
                            right = bodyRight,
                            bottom = rowB,
                            radius = 6,
                            fillColor = theme.bgCard,
                            borderColor = theme.borderSubtle
                        )
                        val nameNode = findWidgetByClass(bodyChild, "queue-item-name")
                        val tagNodes = findWidgetsByClass(bodyChild, "tag")

                        SelectObject(hdc, fontNormal)
                        drawTextInRect(
                            hdc = hdc,
                            text = extractWidgetText(nameNode),
                            left = bodyLeft + 10,
                            top = curY,
                            right = bodyRight - 180,
                            bottom = rowB,
                            colorRef = theme.textPrimary
                        )

                        var tagRight = bodyRight - 10
                        val tagTop = curY + 7
                        SelectObject(hdc, fontSmallBold)
                        for (tagNode in tagNodes.asReversed()) {
                            val tStr = extractWidgetText(tagNode)
                            if (tStr.isEmpty()) continue
                            val tW = max(52, tStr.length * 6 + 14)
                            val tagLeft = tagRight - tW
                            val isDone = widgetHasClass(tagNode, "done")
                            val isErr = widgetHasClass(tagNode, "err")
                            val isVid = widgetHasClass(tagNode, "video")
                            val isAud = widgetHasClass(tagNode, "audio")
                            val tBg = when {
                                isDone || isAud -> theme.accentGreenSoftBg
                                isErr -> theme.accentRedSoftBg
                                isVid -> theme.accentBlueSoftBg
                                else -> theme.bgElevated
                            }
                            val tFg = when {
                                isDone || isAud -> theme.accentGreen
                                isErr -> theme.accentRed
                                isVid -> theme.accentBlueText
                                else -> theme.textSecondary
                            }
                            drawRoundedRect(hdc, tagLeft, tagTop, tagRight, tagTop + 20, 4, tBg, theme.borderSubtle)
                            drawTextInRect(hdc, tStr, tagLeft + 5, tagTop, tagRight - 5, tagTop + 20, tFg, center = true)
                            tagRight = tagLeft - 6
                        }
                        curY = rowB + 6
                    }
                }
            }
        }
    }

    private fun collectStudioWidgets(
        widget: DesktopNativeWidget?,
        toolPalettes: MutableList<DesktopToolPaletteNativeImpl<*>>,
        dockPanels: MutableList<DesktopDockPanelNativeImpl<*>>,
        canvases: MutableList<DesktopNativeWidget>
    ) {
        if (widget == null || !widget.visible || widget.disposed) return
        val impl = widget.attributes["nativeImpl"]
        if (impl is DesktopToolPaletteNativeImpl<*>) {
            toolPalettes.add(impl)
        }
        if (impl is DesktopDockPanelNativeImpl<*>) {
            dockPanels.add(impl)
        }
        if (widget.attributes.containsKey("captureRgba")) {
            canvases.add(widget)
        }
        val children = runCatching { widget.children.toTypedArray() }.getOrElse { emptyArray() }
        for (child in children) {
            collectStudioWidgets(child, toolPalettes, dockPanels, canvases)
        }
    }

    private fun collectPanelControls(
        widget: DesktopNativeWidget?,
        buttons: MutableList<DesktopButtonNativeImpl<*>>,
        checkboxes: MutableList<DesktopCheckboxNativeImpl<*>>,
        selects: MutableList<DesktopSelectNativeImpl<*>>,
        sliders: MutableList<DesktopRangeSliderNativeImpl<*>>,
        texts: MutableList<DesktopTextNativeImpl<*>>
    ) {
        if (widget == null || !widget.visible || widget.disposed || isWidgetMountedInTitleBar(widget)) return
        when (val impl = widget.attributes["nativeImpl"]) {
            is DesktopButtonNativeImpl<*> -> buttons.add(impl)
            is DesktopCheckboxNativeImpl<*> -> checkboxes.add(impl)
            is DesktopSelectNativeImpl<*> -> selects.add(impl)
            is DesktopRangeSliderNativeImpl<*> -> sliders.add(impl)
            is DesktopTextNativeImpl<*> -> texts.add(impl)
        }
        val children = runCatching { widget.children.toTypedArray() }.getOrElse { emptyArray() }
        for (child in children) {
            collectPanelControls(child, buttons, checkboxes, selects, sliders, texts)
        }
    }

    private fun renderTopViewportToolbar(
        hwnd: HWND,
        hdc: HDC?,
        theme: SystemThemePalette,
        fontSmall: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        rootWidget: DesktopNativeWidget?,
        dockPanels: List<DesktopDockPanelNativeImpl<*>>,
        clientW: Int,
        topBarH: Int
    ) {
        val dockPanelWidgets = dockPanels.map { it.nativeWidget }.toSet()
        val windowTitleBarWidgets = collectTitleBarWidgetSet(activeWindowImpl?.getTitleBarInstructions())
        val buttons = mutableListOf<DesktopButtonNativeImpl<*>>()
        val checkboxes = mutableListOf<DesktopCheckboxNativeImpl<*>>()
        val selects = mutableListOf<DesktopSelectNativeImpl<*>>()
        val sliders = mutableListOf<DesktopRangeSliderNativeImpl<*>>()
        val texts = mutableListOf<DesktopTextNativeImpl<*>>()

        fun collectOutsideDockPanels(w: DesktopNativeWidget?) {
            if (w == null || !w.visible || w.disposed || w in dockPanelWidgets || isWidgetMountedInTitleBar(w, windowTitleBarWidgets)) return
            when (val impl = w.attributes["nativeImpl"]) {
                is DesktopButtonNativeImpl<*> -> buttons.add(impl)
                is DesktopCheckboxNativeImpl<*> -> checkboxes.add(impl)
                is DesktopSelectNativeImpl<*> -> selects.add(impl)
                is DesktopRangeSliderNativeImpl<*> -> sliders.add(impl)
                is DesktopTextNativeImpl<*> -> texts.add(impl)
            }
            for (c in w.children) collectOutsideDockPanels(c)
        }
        collectOutsideDockPanels(rootWidget)

        var x = 10
        SelectObject(hdc, fontSmall)
        val toolbarButtons = buttons.filterNot {
            val t = it.getText().trim()
            t == "✨ New" || t == "📂 Open"
        }
        for (btn in toolbarButtons.take(10)) {
            val iconSpec = btn.getIcon()
            val label = btn.getText()
            val isIconOnly = !iconSpec.isNullOrBlank() && label.isBlank()
            val w = when {
                isIconOnly -> 28
                !iconSpec.isNullOrBlank() -> max(64, label.length * 7 + 34)
                else -> max(54, label.ifBlank { "Action" }.length * 7 + 18)
            }
            if (x + w > clientW - 220) break
            drawButtonControl(
                hdc = hdc,
                theme = theme,
                label = if (isIconOnly) "" else label.ifBlank { "Action" },
                icon = iconSpec,
                left = x,
                top = 6,
                right = x + w,
                bottom = topBarH - 6,
                primary = false,
                widget = btn.nativeWidget
            )
            val bLeft = x
            val bRight = x + w
            hitRegions.add(
                HitRegion(
                    left = bLeft,
                    top = 6,
                    right = bRight,
                    bottom = topBarH - 6,
                    tooltip = btn.getTooltipText(),
                    widget = btn.nativeWidget,
                    onClick = { _, _ -> btn.dispatchClick() }
                )
            )
            x += w + 5
        }

        for (sel in selects.take(2)) {
            val curVal = sel.getSelectedValue() ?: ""
            val curLabel = sel.getOptions().firstOrNull { it.first == curVal }?.second ?: curVal
            val w = max(96, min(160, curLabel.length * 7 + 28))
            if (x + w > clientW - 200) break
            drawSelectControl(hdc, theme, curLabel.ifBlank { "Select..." }, x, 6, x + w, topBarH - 6, sel.nativeWidget)
            val sLeft = x
            hitRegions.add(
                HitRegion(
                    left = sLeft,
                    top = 6,
                    right = sLeft + w,
                    bottom = topBarH - 6,
                    tooltip = sel.getTooltipText(),
                    widget = sel.nativeWidget,
                    onClick = { mx, my -> openSelectPopupMenu(hwnd, sel, mx, my) }
                )
            )
            x += w + 6
        }

        for (cb in checkboxes.take(3)) {
            val label = cb.getLabel()
            val w = max(64, label.length * 7 + 24)
            if (x + w > clientW - 180) break
            drawCheckboxControl(hdc, theme, label, cb.isChecked(), x, 8, x + w, topBarH - 8, cb.nativeWidget)
            val cLeft = x
            val cRight = x + w
            hitRegions.add(
                HitRegion(
                    left = cLeft,
                    top = 6,
                    right = cRight,
                    bottom = topBarH - 6,
                    tooltip = cb.getTooltipText(),
                    widget = cb.nativeWidget,
                    onClick = { _, _ -> cb.setChecked(!cb.isChecked()) }
                )
            )
            x += w + 6
        }

        val statusText = texts.lastOrNull {
            it.getText().contains("Zoom:", ignoreCase = true) || it.getText().contains("Tool:", ignoreCase = true)
        }?.getText() ?: texts.firstOrNull()?.getText() ?: "Zoom: 100%"
        drawTextInRect(hdc, statusText, x + 6, 8, clientW - 12, topBarH - 8, theme.accentBlueText, alignRight = true)
    }

    private fun renderLeftToolbox(
        hwnd: HWND,
        hdc: HDC?,
        theme: SystemThemePalette,
        fontSmall: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        toolPalette: DesktopToolPaletteNativeImpl<*>?,
        toolboxDockPanel: DesktopDockPanelNativeImpl<*>?,
        width: Int,
        top: Int,
        bottom: Int,
        isSeparatePanelWindow: Boolean = false
    ) {
        var y = top
        val collapsed = if (isSeparatePanelWindow) false else (toolboxDockPanel?.isCollapsed() == true)
        if (!isSeparatePanelWindow) {
            if (toolboxDockPanel != null) {
                renderCollapsiblePanelHeader(
                    hwnd = hwnd,
                    hdc = hdc,
                    theme = theme,
                    fontSmall = fontSmall,
                    fontBold = fontBold,
                    panel = toolboxDockPanel,
                    left = 6,
                    top = y,
                    right = width - 6,
                    height = 24
                )
            } else {
                fillRectColor(hdc, 6, y, width - 6, y + 24, theme.bgElevated)
                drawRectBorder(hdc, 6, y, width - 6, y + 24, theme.borderSubtle)
                SelectObject(hdc, fontBold)
                drawTextInRect(hdc, "▼  Toolbox - Tool Options", 12, y + 2, width - 10, y + 22, theme.textPrimary)
            }
            y += 28
        }

        // Context-sensitive Layer Mode Badge (Vector Tools vs. Raster Tools)
        if (toolboxDockPanel != null) {
            val badgeTexts = mutableListOf<DesktopTextNativeImpl<*>>()
            collectPanelControls(
                toolboxDockPanel.nativeWidget,
                mutableListOf(),
                mutableListOf(),
                mutableListOf(),
                mutableListOf(),
                badgeTexts
            )
            val contextBadgeText = badgeTexts.firstOrNull()?.getText()?.trim().orEmpty()
            if (contextBadgeText.isNotEmpty() && y + 24 < bottom) {
                val isRasterMode = contextBadgeText.contains("RASTER", ignoreCase = true)
                val badgeBg = if (isRasterMode) theme.bgCard else theme.accentBlueSoftBg
                val badgeBorder = if (isRasterMode) theme.borderStrong else theme.accentBlueBorder
                val badgeFg = if (isRasterMode) theme.textPrimary else theme.accentBlueText
                drawRoundedRect(hdc, 6, y, width - 6, y + 22, 4, badgeBg, badgeBorder)
                SelectObject(hdc, fontBold)
                drawTextInRect(hdc, contextBadgeText, 10, y + 2, width - 10, y + 20, badgeFg, center = true)
                y += 26
            }
        }

        // ToolPalette tools ALWAYS render as icon-only buttons with a tooltip for what they do!
        SelectObject(hdc, fontNormal)
        val tools = toolPalette?.getTools().orEmpty()
        val activeToolId = toolPalette?.getActiveToolId()
        val cols = if (width >= 180) 4 else 3
        val padX = 8
        val gap = 6
        val btnW = max(32, (width - padX * 2 - gap * (cols - 1)) / cols)
        val btnH = 32

        tools.forEachIndexed { idx, item ->
            val col = idx % cols
            val row = idx / cols
            val bx = padX + col * (btnW + gap)
            val by = y + row * (btnH + gap)
            if (by + btnH < bottom - 80) {
                val isSelected = item.id == activeToolId
                val fullTooltip = item.tooltip?.takeIf { it.isNotBlank() }
                val iconKey = item.iconSvgOrPath.ifBlank { item.id }
                drawButtonControl(
                    hdc = hdc,
                    theme = theme,
                    label = "", // Icon-only! No text inside tool palette buttons.
                    icon = iconKey,
                    left = bx,
                    top = by,
                    right = bx + btnW,
                    bottom = by + btnH,
                    primary = isSelected,
                    hwndForHover = hwnd
                )
                val toolId = item.id
                hitRegions.add(
                    HitRegion(
                        left = bx,
                        top = by,
                        right = bx + btnW,
                        bottom = by + btnH,
                        tooltip = fullTooltip,
                        onClick = { _, _ -> toolPalette?.setActiveToolId(toolId) }
                    )
                )
            }
        }

        val rowsCount = (tools.size + cols - 1) / cols
        y += rowsCount * (btnH + gap) + 10

        // GIMP-Style Overlapping Foreground / Background Swatch Widget + Swap (⇄) & Default Reset (⬛⬜)
        if (toolPalette != null && y + 48 < bottom) {
            val fg = toolPalette.getForegroundColor()
            val bg = toolPalette.getBackgroundColor()
            val swatchOriginX = 12
            val swatchOriginY = y

            // Background color box (bottom-right overlapping)
            fillRectColor(hdc, swatchOriginX + 18, swatchOriginY + 14, swatchOriginX + 50, swatchOriginY + 42, rgb(bg.r, bg.g, bg.b))
            drawRectBorder(hdc, swatchOriginX + 18, swatchOriginY + 14, swatchOriginX + 50, swatchOriginY + 42, theme.textPrimary)

            // Foreground color box (top-left overlapping)
            fillRectColor(hdc, swatchOriginX, swatchOriginY, swatchOriginX + 32, swatchOriginY + 28, rgb(fg.r, fg.g, fg.b))
            drawRectBorder(hdc, swatchOriginX, swatchOriginY, swatchOriginX + 32, swatchOriginY + 28, rgb(255, 255, 255))

            hitRegions.add(
                HitRegion(
                    left = swatchOriginX,
                    top = swatchOriginY,
                    right = swatchOriginX + 52,
                    bottom = swatchOriginY + 44,
                    onClick = { _, _ -> toolPalette.dispatchSwapColors() }
                )
            )

            // Swap FG/BG icon button (top-right of swatch cluster)
            drawButtonControl(
                hdc = hdc,
                theme = theme,
                label = "",
                icon = "swap",
                left = swatchOriginX + 58,
                top = swatchOriginY,
                right = swatchOriginX + 86,
                bottom = swatchOriginY + 22,
                primary = false,
                hwndForHover = hwnd
            )
            val sy = swatchOriginY
            hitRegions.add(
                HitRegion(
                    left = swatchOriginX + 58,
                    top = sy,
                    right = swatchOriginX + 86,
                    bottom = sy + 22,
                    onClick = { _, _ -> toolPalette.dispatchSwapColors() }
                )
            )

            // Reset Default Black/White icon button (bottom-right of swatch cluster)
            drawButtonControl(
                hdc = hdc,
                theme = theme,
                label = "D",
                icon = null,
                left = swatchOriginX + 58,
                top = swatchOriginY + 24,
                right = swatchOriginX + 86,
                bottom = swatchOriginY + 44,
                primary = false,
                hwndForHover = hwnd
            )
            hitRegions.add(
                HitRegion(
                    left = swatchOriginX + 58,
                    top = sy + 24,
                    right = swatchOriginX + 86,
                    bottom = sy + 44,
                    onClick = { _, _ ->
                        toolPalette.setForegroundColor(RgbaColor(0, 0, 0, 255))
                        toolPalette.setBackgroundColor(RgbaColor(255, 255, 255, 255))
                    }
                )
            )
            y += 50
        }

        if (!collapsed && toolboxDockPanel != null) {
            // GIMP "Tool Options" section divider header below the tool grid & color swatches
            fillRectColor(hdc, 6, y, width - 6, y + 22, theme.bgElevated)
            drawRectBorder(hdc, 6, y, width - 6, y + 22, theme.borderSubtle)
            SelectObject(hdc, fontBold)
            val activeToolObj = tools.firstOrNull { it.id == activeToolId }
            val toolOptsHeader = activeToolObj?.name?.let { "Tool Options — $it" } ?: "Tool Options"
            drawTextInRect(hdc, toolOptsHeader, 10, y + 2, width - 10, y + 20, theme.textPrimary)
            y += 28

            val buttons = mutableListOf<DesktopButtonNativeImpl<*>>()
            val checkboxes = mutableListOf<DesktopCheckboxNativeImpl<*>>()
            val selects = mutableListOf<DesktopSelectNativeImpl<*>>()
            val sliders = mutableListOf<DesktopRangeSliderNativeImpl<*>>()
            val texts = mutableListOf<DesktopTextNativeImpl<*>>()
            collectPanelControls(toolboxDockPanel.nativeWidget, buttons, checkboxes, selects, sliders, texts)

            SelectObject(hdc, fontSmall)
            for (txt in texts.take(2)) {
                val t = txt.getText().trim()
                if (t.isNotEmpty() && y + 18 < bottom) {
                    drawTextInRect(hdc, t, 10, y, width - 10, y + 16, theme.textSecondary)
                    y += 18
                }
            }

            val defaultSliderLabels = listOf("Size (px)", "Stroke / Opacity", "Hardness / Tolerance")
            sliders.take(3).forEachIndexed { i, slider ->
                if (y + 36 < bottom) {
                    val labelPrefix = defaultSliderLabels.getOrElse(i) { "Value" }
                    val title = "$labelPrefix: ${slider.getValue().roundToInt()}"
                    drawTextInRect(hdc, title, 10, y, width - 10, y + 15, theme.textSecondary)
                    y += 16
                    drawSliderControl(hdc, theme, slider, 10, y, width - 12, y + 14)
                    val sTop = y - 2
                    val sBottom = y + 16
                    val sLeft = 10
                    val sRight = width - 12
                    hitRegions.add(
                        HitRegion(
                            left = sLeft,
                            top = sTop,
                            right = sRight,
                            bottom = sBottom,
                            tooltip = slider.getTooltipText(),
                            widget = slider.nativeWidget,
                            onClick = { mx, _ ->
                                slider.setNormalizedFraction((mx - sLeft).toDouble() / max(1, sRight - sLeft).toDouble())
                            },
                            onDrag = { mx, _ ->
                                slider.setNormalizedFraction((mx - sLeft).toDouble() / max(1, sRight - sLeft).toDouble())
                            }
                        )
                    )
                    y += 20
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun renderCenterSurfaceViewport(
        hwnd: HWND,
        hdc: HDC?,
        theme: SystemThemePalette,
        fontSmall: HFONT?,
        fontSmallBold: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        fontTitle: HFONT?,
        rootWidget: DesktopNativeWidget?,
        canvasWidget: DesktopNativeWidget?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        val availW = max(64, right - left)
        val availH = max(64, bottom - top)
        val tabContainer = findTabContainer(rootWidget)
        val hasOpenCanvasFn = canvasWidget?.attributes?.get("hasOpenCanvas") as? (() -> Boolean)
        val hasOpenCanvas = hasOpenCanvasFn?.invoke()
            ?: (tabContainer != null && tabContainer.getTabTitles().isNotEmpty())

        if (!hasOpenCanvas || canvasWidget == null) {
            canvasViewportRect = intArrayOf(-1, -1, -1, -1)
            renderEmptyCanvasViewportState(
                hwnd = hwnd,
                hdc = hdc,
                theme = theme,
                fontSmall = fontSmall,
                fontSmallBold = fontSmallBold,
                fontNormal = fontNormal,
                fontBold = fontBold,
                fontTitle = fontTitle,
                rootWidget = rootWidget,
                canvasWidget = canvasWidget,
                left = left,
                top = top,
                right = right,
                bottom = bottom
            )
            return
        }

        // Document Tabs Bar at the top of the canvas viewport
        val tabBarHeight = 28
        renderDocumentTabsBar(
            hwnd = hwnd,
            hdc = hdc,
            theme = theme,
            fontSmall = fontSmall,
            fontBold = fontBold,
            rootWidget = rootWidget,
            canvasWidget = canvasWidget,
            left = left - 4,
            top = top - 4,
            right = right + 4,
            bottom = top - 4 + tabBarHeight
        )

        val stageTop = top + tabBarHeight
        val stageAvailH = max(64, bottom - stageTop)
        fillRectColor(hdc, left, stageTop, right, bottom, theme.bgBase)

        val captureBgraFn = canvasWidget.attributes["captureBgra"] as? (() -> ByteArray)
        val widthFn = canvasWidget.attributes["surfaceWidth"] as? (() -> Int)
        val heightFn = canvasWidget.attributes["surfaceHeight"] as? (() -> Int)
        val surfW = (widthFn?.invoke() ?: 320).coerceAtLeast(1)
        val surfH = (heightFn?.invoke() ?: 240).coerceAtLeast(1)
        activeCanvasSurfaceSize = surfW to surfH

        val pad = 12
        val maxStageW = max(32, availW - pad * 2)
        val maxStageH = max(32, stageAvailH - pad * 2)
        val scale = min(maxStageW.toFloat() / surfW.toFloat(), maxStageH.toFloat() / surfH.toFloat()).coerceAtLeast(0.1f)
        val drawW = (surfW * scale).roundToInt().coerceAtLeast(1)
        val drawH = (surfH * scale).roundToInt().coerceAtLeast(1)
        val drawLeft = left + (availW - drawW) / 2
        val drawTop = stageTop + (stageAvailH - drawH) / 2
        canvasViewportRect = intArrayOf(drawLeft, drawTop, drawLeft + drawW, drawTop + drawH)

        val bgra = captureBgraFn?.invoke()
        if (bgra != null && bgra.size >= surfW * surfH * 4) {
            memScoped {
                val bmi = alloc<BITMAPINFO>()
                bmi.bmiHeader.biSize = sizeOf<BITMAPINFOHEADER>().toUInt()
                bmi.bmiHeader.biWidth = surfW
                bmi.bmiHeader.biHeight = -surfH
                bmi.bmiHeader.biPlanes = 1u
                bmi.bmiHeader.biBitCount = 32u
                bmi.bmiHeader.biCompression = BI_RGB.toUInt()

                SetStretchBltMode(hdc, HALFTONE)
                bgra.usePinned { pinned ->
                    StretchDIBits(
                        hdc,
                        drawLeft,
                        drawTop,
                        drawW,
                        drawH,
                        0,
                        0,
                        surfW,
                        surfH,
                        pinned.addressOf(0),
                        bmi.ptr,
                        DIB_RGB_COLORS.toUInt(),
                        SRCCOPY
                    )
                }
            }
        } else {
            fillRectColor(hdc, drawLeft, drawTop, drawLeft + drawW, drawTop + drawH, rgb(255, 255, 255))
        }
        drawRectBorder(hdc, drawLeft - 1, drawTop - 1, drawLeft + drawW + 1, drawTop + drawH + 1, theme.accentBlueBorder)
    }

    /**
     * Renders the document tab strip above the open canvas stage,
     * allowing users to switch between open documents, close individual tabs (`×`),
     * or create a new tab (`+`).
     */
    @Suppress("UNCHECKED_CAST")
    private fun renderDocumentTabsBar(
        hwnd: HWND,
        hdc: HDC?,
        theme: SystemThemePalette,
        fontSmall: HFONT?,
        fontBold: HFONT?,
        rootWidget: DesktopNativeWidget?,
        canvasWidget: DesktopNativeWidget?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        fillRectColor(hdc, left, top, right, bottom, theme.bgPanel)
        drawLineColor(hdc, left, bottom, right, bottom, theme.borderSubtle)

        val tabContainer = findTabContainer(rootWidget)
        val titles = tabContainer?.getTabTitles().orEmpty().ifEmpty { listOf("Document 1") }
        val activeIndex = (tabContainer?.getActiveTabIndex() ?: 0).coerceIn(0, max(0, titles.lastIndex))

        var tabX = left + 4
        for ((idx, title) in titles.withIndex()) {
            val isActive = idx == activeIndex
            val tabW = (title.length * 6 + 44).coerceIn(136, 260)
            if (tabX + tabW > right - 36) break
            val tabRight = tabX + tabW
            val closeLeft = tabRight - 22
            val closeRight = tabRight - 4
            val closeTop = top + 5
            val closeBottom = bottom - 5

            val tabHovered = isRectHovered(hwnd, tabX, top + 2, tabRight, bottom)
            val closeHovered = isRectHovered(hwnd, closeLeft, closeTop, closeRight, closeBottom)

            val tabBg = when {
                isActive -> theme.bgCard
                tabHovered -> theme.bgElevated
                else -> theme.bgPanel
            }
            fillRectColor(hdc, tabX, top + 2, tabRight, bottom, tabBg)
            drawRectBorder(hdc, tabX, top + 2, tabRight, bottom + 1, theme.borderSubtle)
            if (isActive) {
                // Active top indicator bar + seamless bottom blend into canvas area
                fillRectColor(hdc, tabX, top + 2, tabRight, top + 4, theme.accentBlue)
                drawLineColor(hdc, tabX + 1, bottom, tabRight - 1, bottom, theme.bgCard)
            }

            SelectObject(hdc, if (isActive) fontBold else fontSmall)
            drawTextInRect(
                hdc,
                title,
                tabX + 8,
                top + 3,
                closeLeft - 4,
                bottom - 1,
                if (isActive) theme.textPrimary else theme.textSecondary
            )

            // Tab close button (×)
            if (closeHovered) {
                drawRoundedRect(hdc, closeLeft, closeTop, closeRight, closeBottom, 3, theme.dangerBg, theme.dangerBorder)
            }
            SelectObject(hdc, fontSmall)
            drawTextInRect(
                hdc,
                "×",
                closeLeft,
                closeTop - 1,
                closeRight,
                closeBottom,
                if (closeHovered) theme.dangerText else theme.textMuted,
                center = true
            )

            val capturedIdx = idx
            val selectRight = closeLeft - 1
            val tLeft = tabX
            hitRegions.add(
                HitRegion(
                    left = tLeft,
                    top = top + 2,
                    right = selectRight,
                    bottom = bottom,
                    onClick = { _, _ -> tabContainer?.requestSelectTab(capturedIdx) }
                )
            )
            hitRegions.add(
                HitRegion(
                    left = closeLeft,
                    top = closeTop,
                    right = closeRight,
                    bottom = closeBottom,
                    onClick = { _, _ -> tabContainer?.requestCloseTab(capturedIdx) }
                )
            )

            tabX = tabRight + 3
        }

        // "+" New Canvas tab button at the end of the tab strip
        val plusLeft = tabX + 2
        val plusRight = min(right - 4, plusLeft + 26)
        if (plusRight - plusLeft >= 20) {
            drawButtonControl(
                hdc = hdc,
                theme = theme,
                label = "+",
                icon = null,
                left = plusLeft,
                top = top + 3,
                right = plusRight,
                bottom = bottom - 3,
                primary = false,
                hwndForHover = hwnd
            )
            val onNewFn = canvasWidget?.attributes?.get("onSplashNewCanvas") as? (() -> Unit)
            hitRegions.add(
                HitRegion(
                    left = plusLeft,
                    top = top + 3,
                    right = plusRight,
                    bottom = bottom - 3,
                    onClick = { _, _ -> onNewFn?.invoke() }
                )
            )
        }
    }

    private fun findTabContainer(root: DesktopNativeWidget?): DesktopTabContainerNativeImpl<*>? {
        if (root == null || !root.visible || root.disposed) return null
        val impl = root.attributes["nativeImpl"]
        if (impl is DesktopTabContainerNativeImpl<*>) return impl
        for (child in root.children) {
            val found = findTabContainer(child)
            if (found != null) return found
        }
        return null
    }

    private fun findButtonByLabelOrClass(
        root: DesktopNativeWidget?,
        cssClass: String,
        labelSubstring: String
    ): DesktopButtonNativeImpl<*>? {
        if (root == null || root.disposed) return null
        val impl = root.attributes["nativeImpl"]
        if (impl is DesktopButtonNativeImpl<*>) {
            if (widgetHasClass(root, cssClass) || impl.getText().contains(labelSubstring, ignoreCase = true)) {
                return impl
            }
        }
        for (child in root.children) {
            val found = findButtonByLabelOrClass(child, cssClass, labelSubstring)
            if (found != null) return found
        }
        return null
    }

    /**
     * Renders an empty document placeholder inside the canvas viewport when no canvas
     * document is open (`hasOpenCanvas == false`), with `New` and `Open` action buttons.
     */
    @Suppress("UNCHECKED_CAST")
    private fun renderEmptyCanvasViewportState(
        hwnd: HWND,
        hdc: HDC?,
        theme: SystemThemePalette,
        fontSmall: HFONT?,
        fontSmallBold: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        fontTitle: HFONT?,
        rootWidget: DesktopNativeWidget?,
        canvasWidget: DesktopNativeWidget?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        fillRectColor(hdc, left, top, right, bottom, theme.bgBase)
        val availW = max(240, right - left)
        val availH = max(200, bottom - top)

        val cardW = min(560, availW - 24).coerceAtLeast(240)
        val cardH = min(220, availH - 20).coerceAtLeast(160)
        val cardLeft = left + (availW - cardW) / 2
        val cardTop = top + max(8, (availH - cardH) / 2)
        val cardRight = cardLeft + cardW
        val cardBottom = min(bottom - 6, cardTop + cardH)

        drawRoundedRect(hdc, cardLeft, cardTop, cardRight, cardBottom, 10, theme.bgPanel, theme.borderSubtle)
        drawLineColor(hdc, cardLeft + 8, cardTop + 1, cardRight - 8, cardTop + 1, theme.bgElevated)

        var y = cardTop + 24
        val appTitle = activeWindowImpl?.getTitle()?.ifBlank { "Canvas Workspace" } ?: "Canvas Workspace"
        SelectObject(hdc, fontTitle)
        drawTextInRect(
            hdc,
            appTitle,
            cardLeft + 16,
            y,
            cardRight - 16,
            y + 28,
            theme.textPrimary,
            center = true
        )
        y += 34

        SelectObject(hdc, fontSmall)
        drawTextInRect(
            hdc,
            "Create a new document or open an existing file to begin.",
            cardLeft + 18,
            y,
            cardRight - 18,
            y + 20,
            theme.textSecondary,
            center = true
        )
        y += 32

        val btnW = 148
        val btnH = 34
        val btnGap = 14
        val totalBtnsW = btnW * 2 + btnGap
        val newBtnLeft = cardLeft + (cardW - totalBtnsW) / 2
        val openBtnLeft = newBtnLeft + btnW + btnGap

        val onNewFn = (canvasWidget?.attributes?.get("onSplashNewCanvas") as? (() -> Unit))
            ?: findButtonByLabelOrClass(rootWidget, "splash-new-btn", "New")?.let { btn -> { btn.dispatchClick() } }
        val onOpenFn = (canvasWidget?.attributes?.get("onSplashOpenFile") as? (() -> Unit))
            ?: findButtonByLabelOrClass(rootWidget, "splash-open-btn", "Open")?.let { btn -> { btn.dispatchClick() } }

        SelectObject(hdc, fontBold)
        drawButtonControl(
            hdc = hdc,
            theme = theme,
            label = "New",
            icon = null,
            left = newBtnLeft,
            top = y,
            right = newBtnLeft + btnW,
            bottom = y + btnH,
            primary = true,
            hwndForHover = hwnd
        )
        val btnTop = y
        hitRegions.add(
            HitRegion(
                left = newBtnLeft,
                top = btnTop,
                right = newBtnLeft + btnW,
                bottom = btnTop + btnH,
                onClick = { _, _ -> onNewFn?.invoke() }
            )
        )

        drawButtonControl(
            hdc = hdc,
            theme = theme,
            label = "Open",
            icon = null,
            left = openBtnLeft,
            top = y,
            right = openBtnLeft + btnW,
            bottom = y + btnH,
            primary = false,
            hwndForHover = hwnd
        )
        hitRegions.add(
            HitRegion(
                left = openBtnLeft,
                top = btnTop,
                right = openBtnLeft + btnW,
                bottom = btnTop + btnH,
                onClick = { _, _ -> onOpenFn?.invoke() }
            )
        )
    }

    /**
     * Renders a single GenericUI [com.nexustools.genericui.core.Component] mounted inside a [TitleBar]
     * (`leadingComponents`, `centerComponents`, or `trailingComponents`) — either in the collapsible header
     * of a docked [DesktopDockPanelNativeImpl] or in the actual window titlebar of a detached `PanelWindow`.
     * Returns the horizontal pixel width consumed.
     */
    private fun renderTitleBarComponent(
        hwnd: HWND,
        hdc: HDC?,
        theme: SystemThemePalette,
        fontSmall: HFONT?,
        comp: com.nexustools.genericui.core.AbstractComponent,
        x: Int,
        top: Int,
        bottom: Int,
        alignFromRight: Boolean = false
    ): Int {
        val impl = comp.nativeImpl ?: comp.ensureNativeImpl()
        val height = max(18, bottom - top)
        when (impl) {
            is DesktopButtonNativeImpl<*> -> {
                val label = impl.getText()
                val icon = impl.getIcon()
                val isIconOnly = !icon.isNullOrBlank() && label.isBlank()
                val w = when {
                    isIconOnly -> height + 4
                    else -> max(46, label.length * 6 + 14)
                }
                val left = if (alignFromRight) x - w else x
                val right = left + w
                SelectObject(hdc, fontSmall)
                drawButtonControl(
                    hdc = hdc,
                    theme = theme,
                    label = if (isIconOnly) "" else label,
                    icon = icon,
                    left = left,
                    top = top,
                    right = right,
                    bottom = bottom,
                    primary = false,
                    widget = impl.nativeWidget,
                    hwndForHover = hwnd
                )
                hitRegions.add(
                    HitRegion(
                        left = left,
                        top = top,
                        right = right,
                        bottom = bottom,
                        tooltip = impl.getTooltipText()?.takeIf { it.isNotBlank() && (isIconOnly || it != label) },
                        widget = impl.nativeWidget,
                        onClick = { _, _ -> impl.dispatchClick() }
                    )
                )
                return w
            }
            is DesktopSelectNativeImpl<*> -> {
                val curVal = impl.getSelectedValue() ?: ""
                val curLabel = impl.getOptions().firstOrNull { it.first == curVal }?.second ?: curVal
                val w = max(72, curLabel.length * 6 + 24)
                val left = if (alignFromRight) x - w else x
                val right = left + w
                SelectObject(hdc, fontSmall)
                drawSelectControl(hdc, theme, curLabel, left, top, right, bottom, impl.nativeWidget, hwnd)
                hitRegions.add(
                    HitRegion(
                        left = left,
                        top = top,
                        right = right,
                        bottom = bottom,
                        tooltip = impl.getTooltipText(),
                        widget = impl.nativeWidget,
                        onClick = { mx, my -> openSelectPopupMenu(hwnd, impl, mx, my) }
                    )
                )
                return w
            }
            is DesktopCheckboxNativeImpl<*> -> {
                val label = impl.getLabel()
                val w = max(48, label.length * 6 + 22)
                val left = if (alignFromRight) x - w else x
                val right = left + w
                SelectObject(hdc, fontSmall)
                drawCheckboxControl(hdc, theme, label, impl.isChecked(), left, top, right, bottom, impl.nativeWidget, hwnd)
                hitRegions.add(
                    HitRegion(
                        left = left,
                        top = top,
                        right = right,
                        bottom = bottom,
                        tooltip = impl.getTooltipText(),
                        widget = impl.nativeWidget,
                        onClick = { _, _ -> impl.setChecked(!impl.isChecked()) }
                    )
                )
                return w
            }
            is DesktopTextNativeImpl<*> -> {
                val label = impl.getText()
                val w = max(32, label.length * 6 + 8)
                val left = if (alignFromRight) x - w else x
                val right = left + w
                SelectObject(hdc, fontSmall)
                drawTextInRect(hdc, label, left, top, right, bottom, theme.textSecondary, center = true)
                return w
            }
            else -> return 0
        }
    }

    /**
     * Renders the custom or component-populated [TitleBar] (`activeWindowImpl?.getTitleBarInstructions()`)
     * on the main top-level window (`activeHwnd`) at `y = 0..titleBarH` using [Win32NativeTitleBarRenderer].
     * Returns the rendered titlebar height in pixels (`0` when no `TitleBar` is configured).
     */
    private fun renderMainWindowTitleBar(
        hwnd: HWND,
        hdc: HDC?,
        theme: SystemThemePalette,
        fontSmall: HFONT?,
        fontBold: HFONT?,
        clientW: Int
    ): Int {
        val winImpl = activeWindowImpl ?: return 0
        if (!hasCustomMainWindowTitleBar()) return 0
        val tb = winImpl.getTitleBarInstructions()
        val kind = winImpl.titleBarKind
        val titleBarH = getMainWindowTitleBarHeight()
        val winTitle = winImpl.getTitle().ifBlank { "GenericUI" }

        return Win32NativeTitleBarRenderer.renderWindowTitleBar(
            hwnd = hwnd,
            targetHdc = hdc,
            left = 0,
            top = 0,
            right = clientW,
            titleBarHeight = titleBarH,
            windowTitle = winTitle,
            titleBar = tb,
            isToolWindow = kind.usesSmallCaptionMetrics,
            isInWindowModalHeader = false,
            kind = kind,
            appIcon = appIconSmall ?: appIconBig,
            headerActions = emptyList(),
            fontSmall = fontSmall,
            fontCaption = fontBold ?: fontSmall,
            hoveredWidget = hoveredWidget,
            pressedWidget = pressedWidget,
            isRectHoveredFn = { l, t, r, b -> isRectHovered(hwnd, l, t, r, b) },
            registerHitRegionFn = { l, t, r, b, tip, widget, onClick ->
                hitRegions.add(
                    HitRegion(
                        left = l,
                        top = t,
                        right = r,
                        bottom = b,
                        tooltip = tip,
                        widget = widget,
                        onClick = onClick
                    )
                )
            },
            openSelectPopupFn = { selectImpl, mx, my ->
                openSelectPopupMenu(hwnd, selectImpl, mx, my)
            }
        )
    }

    /**
     * Renders the native Windows **Tool Window** titlebar ([Win32TitleBarKind.TOOL_WINDOW], `SM_CYSMCAPTION`,
     * `WP_SMALLCAPTION`, `WP_SMALLCLOSEBUTTON`, compact close button, no minimize/maximize buttons)
     * of a separate `PanelWindow` (`y = 0..titleBarH`) while reusing the shared component-embedding
     * pipeline (`panel.getTitleBarInstructions()` and `panel.getHeaderActions()`).
     */
    private fun renderPanelWindowTitleBar(
        hwnd: HWND,
        hdc: HDC?,
        theme: SystemThemePalette,
        fontSmall: HFONT?,
        fontBold: HFONT?,
        panel: DesktopDockPanelNativeImpl<*>,
        clientW: Int,
        titleBarH: Int
    ) {
        val tb: TitleBar? = panel.getTitleBarInstructions()
        Win32NativeTitleBarRenderer.renderWindowTitleBar(
            hwnd = hwnd,
            targetHdc = hdc,
            left = 0,
            top = 0,
            right = clientW,
            titleBarHeight = titleBarH,
            windowTitle = panel.getTitle(),
            titleBar = tb,
            isToolWindow = true,
            isInWindowModalHeader = false,
            kind = Win32TitleBarKind.TOOL_WINDOW,
            appIcon = appIconSmall ?: appIconBig,
            headerActions = panel.getHeaderActions(),
            fontSmall = fontSmall,
            fontCaption = fontSmall ?: fontBold,
            hoveredWidget = hoveredWidget,
            pressedWidget = pressedWidget,
            isRectHoveredFn = { l, t, r, b -> isRectHovered(hwnd, l, t, r, b) },
            registerHitRegionFn = { l, t, r, b, tip, widget, onClick ->
                hitRegions.add(
                    HitRegion(
                        left = l,
                        top = t,
                        right = r,
                        bottom = b,
                        tooltip = tip,
                        widget = widget,
                        onClick = onClick
                    )
                )
            },
            openSelectPopupFn = { selectImpl, mx, my ->
                openSelectPopupMenu(hwnd, selectImpl, mx, my)
            },
            onCloseClicked = {
                ShowWindow(hwnd, SW_HIDE)
            }
        )
    }

    /**
     * Renders the collapsible panel title header bar in Single-Window Mode (`isSeparatePanelWindow == false`)
     * using [Win32TitleBarKind.DOCKED_PANEL_HEADER] and the shared [Win32NativeTitleBarRenderer] component
     * embedding pipeline (`panel.getTitleBarInstructions()` and `panel.getHeaderActions()`).
     */
    private fun renderCollapsiblePanelHeader(
        hwnd: HWND,
        hdc: HDC?,
        theme: SystemThemePalette,
        fontSmall: HFONT?,
        fontBold: HFONT?,
        panel: DesktopDockPanelNativeImpl<*>,
        left: Int,
        top: Int,
        right: Int,
        height: Int = 26
    ) {
        val bottom = top + height
        val collapsed = panel.isCollapsed()
        val chevron = if (collapsed) "▶" else "▼"

        // Register the base collapse-toggle HitRegion FIRST so any TitleBar button HitRegions added
        // afterward by `Win32NativeTitleBarRenderer.renderEmbeddedTitleBarComponents` take precedence on click!
        hitRegions.add(
            HitRegion(
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                tooltip = panel.getTooltipText(),
                widget = panel.nativeWidget,
                onClick = { _, _ -> panel.toggleCollapsed() }
            )
        )

        Win32NativeTitleBarRenderer.renderWindowTitleBar(
            hwnd = hwnd,
            targetHdc = hdc,
            left = left,
            top = top,
            right = right,
            titleBarHeight = height,
            windowTitle = panel.getTitle(),
            titleBar = panel.getTitleBarInstructions(),
            isToolWindow = true,
            isInWindowModalHeader = true,
            kind = Win32TitleBarKind.DOCKED_PANEL_HEADER,
            appIcon = null,
            leadingPrefixGlyph = chevron,
            headerActions = panel.getHeaderActions(),
            fontSmall = fontSmall,
            fontCaption = fontBold ?: fontSmall,
            hoveredWidget = hoveredWidget,
            pressedWidget = pressedWidget,
            isRectHoveredFn = { l, t, r, b -> isRectHovered(hwnd, l, t, r, b) },
            registerHitRegionFn = { l, t, r, b, tip, widget, onClick ->
                hitRegions.add(
                    HitRegion(
                        left = l,
                        top = t,
                        right = r,
                        bottom = b,
                        tooltip = tip,
                        widget = widget,
                        onClick = onClick
                    )
                )
            },
            openSelectPopupFn = { selectImpl, mx, my ->
                openSelectPopupMenu(hwnd, selectImpl, mx, my)
            }
        )
    }

    private fun renderRightInspectorDock(
        hwnd: HWND,
        hdc: HDC?,
        theme: SystemThemePalette,
        fontSmall: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        panels: List<DesktopDockPanelNativeImpl<*>>,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        var y = top
        val headerH = 26
        val headerStep = headerH + 3
        val expandedPanels = panels.filter { !it.isCollapsed() }
        val expandedCount = expandedPanels.size.coerceAtLeast(1)
        val totalHeaderSpace = panels.size * headerStep
        val availBodySpace = max(120, (bottom - top) - totalHeaderSpace)

        for ((panelIdx, panel) in panels.withIndex()) {
            if (y + headerH > bottom) break
            renderCollapsiblePanelHeader(
                hwnd = hwnd,
                hdc = hdc,
                theme = theme,
                fontSmall = fontSmall,
                fontBold = fontBold,
                panel = panel,
                left = left,
                top = y,
                right = right,
                height = headerH
            )
            y += headerStep

            if (panel.isCollapsed()) {
                continue
            }

            val remainingPanelsBelow = (panels.size - 1 - panelIdx).coerceAtLeast(0)
            val reservedForHeadersBelow = remainingPanelsBelow * headerStep
            val panelBodyQuota = max(92, availBodySpace / expandedCount)
            val panelBottomLimit = min(bottom - reservedForHeadersBelow, y + panelBodyQuota)

            y = renderStandardDockPanelBody(
                hwnd = hwnd,
                hdc = hdc,
                theme = theme,
                fontSmall = fontSmall,
                fontNormal = fontNormal,
                fontBold = fontBold,
                panel = panel,
                left = left,
                top = y,
                right = right,
                bottomLimit = panelBottomLimit
            )
            y += 4
        }
    }

    /**
     * Walks a standard [DesktopDockPanelNativeImpl]'s widget hierarchy (`LayersPanel`, `PropertiesPanel`,
     * `SharedObjectsPanel`, `LibraryPanel`, `FontsPanel`) in layout order so `HStack` rows, `TextField`
     * inputs, `Select` dropdowns, labeled `Slider`s, `CheckBox`es, `Text` headers, and `Button`s all
     * render in their natural position and work interactively.
     */
    private fun renderStandardDockPanelBody(
        hwnd: HWND,
        hdc: HDC?,
        theme: SystemThemePalette,
        fontSmall: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        panel: DesktopDockPanelNativeImpl<*>,
        left: Int,
        top: Int,
        right: Int,
        bottomLimit: Int
    ): Int {
        var y = top
        val width = max(80, right - left)
        val titleBarWidgets = collectTitleBarWidgetSet(
            panel.getTitleBarInstructions(),
            activeWindowImpl?.getTitleBarInstructions()
        )

        fun renderTextInputWidget(inputImpl: DesktopTextInputNativeImpl<*>, xL: Int, xR: Int, rowTop: Int, rowH: Int) {
            renderAndMountTextInputControl(
                hwnd = hwnd,
                hdc = hdc,
                theme = theme,
                font = fontSmall,
                inputImpl = inputImpl,
                widget = inputImpl.nativeWidget,
                left = xL,
                top = rowTop,
                right = xR,
                bottom = rowTop + rowH
            )
        }

        fun renderHStackRow(rowWidget: DesktopNativeWidget) {
            if (isWidgetMountedInTitleBar(rowWidget, titleBarWidgets)) return
            val visibleChildren = rowWidget.children.filter {
                it.visible && !it.disposed && !isWidgetMountedInTitleBar(it, titleBarWidgets)
            }
            if (visibleChildren.isEmpty() || y + 22 > bottomLimit) return

            // Dedicated layout for LayersPanel layer selector rows (.ha-layer-row)
            if (widgetHasClass(rowWidget, "ha-layer-row")) {
                val rowH = 24
                if (y + rowH > bottomLimit) return
                val gap = 4
                var cx = left
                for ((idx, child) in visibleChildren.withIndex()) {
                    val cImpl = child.attributes["nativeImpl"] as? DesktopButtonNativeImpl<*> ?: continue
                    val itemW = when {
                        idx == 0 && visibleChildren.size >= 2 -> 26
                        idx == visibleChildren.lastIndex && visibleChildren.size >= 3 -> 52
                        visibleChildren.size >= 3 -> max(60, (right - cx) - 52 - gap)
                        else -> max(60, right - cx)
                    }
                    val cRight = if (idx == visibleChildren.lastIndex) right else min(right, cx + itemW)
                    val isActiveLayerBtn = cImpl.getType() == BootstrapType.PRIMARY || widgetHasClass(child, "active-layer-btn") || widgetHasClass(child, "btn-primary")
                    SelectObject(hdc, if (isActiveLayerBtn) fontBold else fontSmall)
                    drawButtonControl(
                        hdc = hdc,
                        theme = theme,
                        label = if (cImpl.isIconOnly()) "" else cImpl.getText(),
                        icon = cImpl.getIcon(),
                        left = cx,
                        top = y,
                        right = cRight,
                        bottom = y + rowH,
                        primary = isActiveLayerBtn,
                        widget = child,
                        hwndForHover = hwnd
                    )
                    val bL = cx
                    val bR = cRight
                    val ry = y
                    hitRegions.add(
                        HitRegion(
                            left = bL,
                            top = ry,
                            right = bR,
                            bottom = ry + rowH,
                            tooltip = cImpl.getTooltipText()?.takeIf { it.isNotBlank() && (cImpl.isIconOnly() || it != cImpl.getText()) },
                            widget = child,
                            onClick = { _, _ ->
                                cImpl.dispatchClick()
                                InvalidateRect(hwnd, null, 0)
                            }
                        )
                    )
                    cx = cRight + gap
                }
                y += rowH + 3
                return
            }

            val rowH = 22
            val gap = 4
            val count = visibleChildren.size
            val itemW = max(28, (width - gap * (count - 1)) / count)
            var cx = left
            for ((idx, child) in visibleChildren.withIndex()) {
                val cRight = if (idx == count - 1) right else min(right, cx + itemW)
                when (val cImpl = child.attributes["nativeImpl"]) {
                    is DesktopButtonNativeImpl<*> -> {
                        val isPrimary = cImpl.getType() == BootstrapType.PRIMARY || widgetHasClass(child, "btn-primary") || widgetHasClass(child, "active-layer-btn")
                        SelectObject(hdc, if (isPrimary) fontBold else fontSmall)
                        drawButtonControl(
                            hdc = hdc,
                            theme = theme,
                            label = if (cImpl.isIconOnly()) "" else cImpl.getText(),
                            icon = cImpl.getIcon(),
                            left = cx,
                            top = y,
                            right = cRight,
                            bottom = y + rowH,
                            primary = isPrimary,
                            widget = child,
                            hwndForHover = hwnd
                        )
                        val bL = cx
                        val bR = cRight
                        val ry = y
                        hitRegions.add(
                            HitRegion(
                                left = bL,
                                top = ry,
                                right = bR,
                                bottom = ry + rowH,
                                tooltip = cImpl.getTooltipText()?.takeIf { it.isNotBlank() && (cImpl.isIconOnly() || it != cImpl.getText()) },
                                widget = child,
                                onClick = { _, _ -> cImpl.dispatchClick() }
                            )
                        )
                    }
                    is DesktopSelectNativeImpl<*> -> {
                        val curVal = cImpl.getSelectedValue() ?: ""
                        val curLabel = cImpl.getOptions().firstOrNull { it.first == curVal }?.second ?: curVal
                        SelectObject(hdc, fontSmall)
                        drawSelectControl(hdc, theme, curLabel.ifBlank { "Select..." }, cx, y, cRight, y + rowH, child, hwnd)
                        val sL = cx
                        val sR = cRight
                        val ry = y
                        hitRegions.add(
                            HitRegion(
                                left = sL,
                                top = ry,
                                right = sR,
                                bottom = ry + rowH,
                                tooltip = cImpl.getTooltipText(),
                                widget = child,
                                onClick = { mx, my -> openSelectPopupMenu(hwnd, cImpl, mx, my) }
                            )
                        )
                    }
                    is DesktopCheckboxNativeImpl<*> -> {
                        SelectObject(hdc, fontSmall)
                        drawCheckboxControl(hdc, theme, cImpl.getLabel(), cImpl.isChecked(), cx, y + 2, cRight, y + rowH - 2, child, hwnd)
                        val cL = cx
                        val cR = cRight
                        val ry = y
                        hitRegions.add(
                            HitRegion(
                                left = cL,
                                top = ry,
                                right = cR,
                                bottom = ry + rowH,
                                tooltip = cImpl.getTooltipText(),
                                widget = child,
                                onClick = { _, _ -> cImpl.setChecked(!cImpl.isChecked()) }
                            )
                        )
                    }
                    is DesktopTextInputNativeImpl<*> -> {
                        renderTextInputWidget(cImpl, cx, cRight, y, rowH)
                    }
                    is DesktopRangeSliderNativeImpl<*> -> {
                        drawSliderControl(hdc, theme, cImpl, cx + 2, y + 4, cRight - 2, y + rowH - 4)
                        val sL = cx + 2
                        val sR = cRight - 2
                        val ry = y
                        hitRegions.add(
                            HitRegion(
                                left = sL,
                                top = ry,
                                right = sR,
                                bottom = ry + rowH,
                                tooltip = cImpl.getTooltipText(),
                                widget = child,
                                onClick = { mx, _ ->
                                    cImpl.setNormalizedFraction((mx - sL).toDouble() / max(1, sR - sL).toDouble())
                                },
                                onDrag = { mx, _ ->
                                    cImpl.setNormalizedFraction((mx - sL).toDouble() / max(1, sR - sL).toDouble())
                                }
                            )
                        )
                    }
                    is DesktopTextNativeImpl<*> -> {
                        SelectObject(hdc, fontSmall)
                        drawTextInRect(hdc, cImpl.getText(), cx + 2, y + 2, cRight - 2, y + rowH - 2, theme.textPrimary)
                    }
                }
                cx = cRight + gap
            }
            y += rowH + 3
        }

        fun walkContainer(container: DesktopNativeWidget?) {
            if (container == null || !container.visible || container.disposed || isWidgetMountedInTitleBar(container, titleBarWidgets)) return
            val children = container.children.filter {
                it.visible && !it.disposed && !isWidgetMountedInTitleBar(it, titleBarWidgets)
            }
            var i = 0
            while (i < children.size && y + 16 <= bottomLimit) {
                val child = children[i]
                val impl = child.attributes["nativeImpl"]
                val tag = child.nativeClassName.lowercase()

                // Group consecutive standalone Button siblings into a 2-column row
                if (impl is DesktopButtonNativeImpl<*>) {
                    val btnRun = mutableListOf<DesktopButtonNativeImpl<*>>()
                    while (i < children.size) {
                        val nextBtn = children[i].attributes["nativeImpl"] as? DesktopButtonNativeImpl<*> ?: break
                        btnRun.add(nextBtn)
                        i++
                    }
                    val cols = if (btnRun.size == 1) 1 else if (btnRun.all { it.isIconOnly() }) min(6, btnRun.size) else 2
                    val gap = 4
                    val colW = (width - gap * (cols - 1)) / cols
                    btnRun.forEachIndexed { bIdx, btn ->
                        val col = bIdx % cols
                        val bx = left + col * (colW + gap)
                        if (y + 22 <= bottomLimit) {
                            SelectObject(hdc, fontSmall)
                            drawButtonControl(
                                hdc = hdc,
                                theme = theme,
                                label = if (btn.isIconOnly()) "" else btn.getText(),
                                icon = btn.getIcon(),
                                left = bx,
                                top = y,
                                right = bx + colW,
                                bottom = y + 22,
                                primary = false,
                                widget = btn.nativeWidget,
                                hwndForHover = hwnd
                            )
                            val ry = y
                            hitRegions.add(
                                HitRegion(
                                    left = bx,
                                    top = ry,
                                    right = bx + colW,
                                    bottom = ry + 22,
                                    tooltip = btn.getTooltipText()?.takeIf { it.isNotBlank() && (btn.isIconOnly() || it != btn.getText()) },
                                    widget = btn.nativeWidget,
                                    onClick = { _, _ -> btn.dispatchClick() }
                                )
                            )
                        }
                        if (col == cols - 1 || bIdx == btnRun.lastIndex) {
                            if (y + 22 <= bottomLimit) y += 25
                        }
                    }
                    continue
                }

                when {
                    impl is DesktopTextNativeImpl<*> -> {
                        val txt = impl.getText().trim()
                        if (txt.isNotEmpty() && y + 16 <= bottomLimit) {
                            val isHeader = txt.endsWith(":") || txt.startsWith("🎨") || txt.startsWith("🧩") ||
                                txt.startsWith("✨") || txt.startsWith("🔤") || txt.startsWith("🗂") || txt.startsWith("📐")
                            SelectObject(hdc, if (isHeader) fontBold else fontSmall)
                            drawTextInRect(
                                hdc,
                                txt,
                                left + 2,
                                y,
                                right - 2,
                                y + 16,
                                if (isHeader) theme.textPrimary else theme.textSecondary
                            )
                            y += 17
                        }
                    }
                    impl is DesktopTextInputNativeImpl<*> -> {
                        if (y + 22 <= bottomLimit) {
                            renderTextInputWidget(impl, left, right, y, 22)
                            y += 25
                        }
                    }
                    impl is DesktopSelectNativeImpl<*> -> {
                        if (y + 22 <= bottomLimit) {
                            val curVal = impl.getSelectedValue() ?: ""
                            val curLabel = impl.getOptions().firstOrNull { it.first == curVal }?.second ?: curVal
                            SelectObject(hdc, fontSmall)
                            drawSelectControl(hdc, theme, curLabel.ifBlank { "Select..." }, left, y, right, y + 22, child, hwnd)
                            val sy = y
                            hitRegions.add(
                                HitRegion(
                                    left = left,
                                    top = sy,
                                    right = right,
                                    bottom = sy + 22,
                                    tooltip = impl.getTooltipText(),
                                    widget = child,
                                    onClick = { mx, my -> openSelectPopupMenu(hwnd, impl, mx, my) }
                                )
                            )
                            y += 25
                        }
                    }
                    impl is DesktopRangeSliderNativeImpl<*> -> {
                        if (y + 18 <= bottomLimit) {
                            val valStr = "${impl.getValue().roundToInt()}"
                            val sliderRight = right - 34
                            drawSliderControl(hdc, theme, impl, left + 2, y + 2, sliderRight, y + 15)
                            SelectObject(hdc, fontSmall)
                            drawTextInRect(hdc, valStr, sliderRight + 4, y, right, y + 16, theme.textSecondary, alignRight = true)
                            val sTop = y
                            val sBottom = y + 17
                            val sLeft = left + 2
                            hitRegions.add(
                                HitRegion(
                                    left = sLeft,
                                    top = sTop,
                                    right = sliderRight,
                                    bottom = sBottom,
                                    tooltip = impl.getTooltipText(),
                                    widget = child,
                                    onClick = { mx, _ ->
                                        impl.setNormalizedFraction((mx - sLeft).toDouble() / max(1, sliderRight - sLeft).toDouble())
                                    },
                                    onDrag = { mx, _ ->
                                        impl.setNormalizedFraction((mx - sLeft).toDouble() / max(1, sliderRight - sLeft).toDouble())
                                    }
                                )
                            )
                            y += 19
                        }
                    }
                    impl is DesktopCheckboxNativeImpl<*> -> {
                        if (y + 19 <= bottomLimit) {
                            SelectObject(hdc, fontSmall)
                            drawCheckboxControl(hdc, theme, impl.getLabel(), impl.isChecked(), left, y, right, y + 18, child, hwnd)
                            val ry = y
                            hitRegions.add(
                                HitRegion(
                                    left = left,
                                    top = ry,
                                    right = right,
                                    bottom = ry + 18,
                                    tooltip = impl.getTooltipText(),
                                    widget = child,
                                    onClick = { _, _ -> impl.setChecked(!impl.isChecked()) }
                                )
                            )
                            y += 20
                        }
                    }
                    tag.contains("hstack") || widgetHasClass(child, "hstack") -> {
                        renderHStackRow(child)
                    }
                    child.children.isNotEmpty() -> {
                        val nonTitleBarChildren = child.children.filter {
                            it.visible && !it.disposed && !isWidgetMountedInTitleBar(it, titleBarWidgets)
                        }
                        if (nonTitleBarChildren.isEmpty()) {
                            i++
                            continue
                        }
                        val directLeafControls = nonTitleBarChildren.all {
                            val cImpl = it.attributes["nativeImpl"]
                            cImpl is DesktopButtonNativeImpl<*> ||
                                cImpl is DesktopCheckboxNativeImpl<*> ||
                                cImpl is DesktopSelectNativeImpl<*> ||
                                cImpl is DesktopTextNativeImpl<*>
                        } && nonTitleBarChildren.any { it.attributes["nativeImpl"] is DesktopButtonNativeImpl<*> } &&
                            nonTitleBarChildren.size in 2..5 &&
                            !tag.contains("vstack") && !tag.contains("scroll")
                        if (directLeafControls) {
                            renderHStackRow(child)
                        } else {
                            walkContainer(child)
                        }
                    }
                }
                i++
            }
        }

        walkContainer(panel.nativeWidget)
        return y
    }

    /**
     * Renders the Animation Timeline & Keyframe Curves Dock ABOVE the Canvas Viewport in the center column
     * (or inside its own separate `PanelWindow` when [isSeparatePanelWindow] is `true`).
     */
    private fun renderTopTimelineDock(
        hwnd: HWND,
        hdc: HDC?,
        theme: SystemThemePalette,
        fontSmall: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        timelinePanel: DesktopDockPanelNativeImpl<*>?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        isSeparatePanelWindow: Boolean = false
    ) {
        if (timelinePanel == null) return
        val collapsed = if (isSeparatePanelWindow) false else timelinePanel.isCollapsed()
        var contentTop = top
        if (!isSeparatePanelWindow) {
            renderCollapsiblePanelHeader(
                hwnd = hwnd,
                hdc = hdc,
                theme = theme,
                fontSmall = fontSmall,
                fontBold = fontBold,
                panel = timelinePanel,
                left = left,
                top = top,
                right = right,
                height = 24
            )
            if (collapsed) return
            contentTop = top + 29
        } else {
            contentTop = top + 4
        }

        SelectObject(hdc, fontSmall)
        val buttons = mutableListOf<DesktopButtonNativeImpl<*>>()
        val checkboxes = mutableListOf<DesktopCheckboxNativeImpl<*>>()
        val selects = mutableListOf<DesktopSelectNativeImpl<*>>()
        val sliders = mutableListOf<DesktopRangeSliderNativeImpl<*>>()
        val texts = mutableListOf<DesktopTextNativeImpl<*>>()
        collectPanelControls(timelinePanel.nativeWidget, buttons, checkboxes, selects, sliders, texts)

        var x = left + 8
        val rowY = contentTop
        for (btn in buttons.take(8)) {
            val iconSpec = btn.getIcon()
            val label = btn.getText()
            val isIconOnly = !iconSpec.isNullOrBlank() && label.isBlank()
            val w = when {
                isIconOnly -> 28
                !iconSpec.isNullOrBlank() -> max(68, label.length * 7 + 30)
                else -> max(68, label.length * 7 + 16)
            }
            if (x + w > right - 8) break
            val isPlay = label.contains("Play", ignoreCase = true) || iconSpec == "play"
            drawButtonControl(
                hdc = hdc,
                theme = theme,
                label = if (isIconOnly) "" else label,
                icon = iconSpec,
                left = x,
                top = rowY,
                right = x + w,
                bottom = rowY + 24,
                primary = isPlay,
                widget = btn.nativeWidget,
                hwndForHover = hwnd
            )
            val bLeft = x
            hitRegions.add(
                HitRegion(
                    left = bLeft,
                    top = rowY,
                    right = bLeft + w,
                    bottom = rowY + 24,
                    tooltip = btn.getTooltipText()?.takeIf { it.isNotBlank() && (isIconOnly || it != label) },
                    widget = btn.nativeWidget,
                    onClick = { _, _ -> btn.dispatchClick() }
                )
            )
            x += w + 6
        }

        for (sel in selects.take(2)) {
            val curVal = sel.getSelectedValue() ?: ""
            val curLabel = sel.getOptions().firstOrNull { it.first == curVal }?.second ?: curVal
            val w = 168
            if (x + w <= right - 8) {
                drawSelectControl(hdc, theme, curLabel.ifBlank { "Transition..." }, x, rowY, x + w, rowY + 24, sel.nativeWidget, hwnd)
                val sLeft = x
                hitRegions.add(
                    HitRegion(
                        left = sLeft,
                        top = rowY,
                        right = sLeft + w,
                        bottom = rowY + 24,
                        tooltip = sel.getTooltipText(),
                        widget = sel.nativeWidget,
                        onClick = { mx, my -> openSelectPopupMenu(hwnd, sel, mx, my) }
                    )
                )
                x += w + 8
            }
        }

        for (cb in checkboxes.take(2)) {
            val label = cb.getLabel()
            val w = max(68, label.length * 7 + 22)
            if (x + w <= right - 8) {
                drawCheckboxControl(hdc, theme, label, cb.isChecked(), x, rowY + 2, x + w, rowY + 22, cb.nativeWidget, hwnd)
                val cLeft = x
                hitRegions.add(
                    HitRegion(
                        left = cLeft,
                        top = rowY,
                        right = cLeft + w,
                        bottom = rowY + 24,
                        tooltip = cb.getTooltipText(),
                        widget = cb.nativeWidget,
                        onClick = { _, _ -> cb.setChecked(!cb.isChecked()) }
                    )
                )
                x += w + 8
            }
        }

        val summary = texts.lastOrNull()?.getText() ?: ""
        if (x + 80 < right - 8) {
            drawTextInRect(hdc, summary, x, rowY + 2, right - 8, rowY + 22, theme.textSecondary, alignRight = true)
        }

        val playhead = sliders.firstOrNull()
        if (playhead != null) {
            val sTop = rowY + 30
            val sBottom = min(bottom - 6, sTop + 20)
            drawSliderControl(hdc, theme, playhead, left + 8, sTop, right - 8, sBottom)
            hitRegions.add(
                HitRegion(
                    left = left + 8,
                    top = sTop,
                    right = right - 8,
                    bottom = sBottom,
                    tooltip = playhead.getTooltipText(),
                    widget = playhead.nativeWidget,
                    onClick = { mx, _ ->
                        playhead.setNormalizedFraction((mx - (left + 8)).toDouble() / max(1, (right - 8) - (left + 8)).toDouble())
                    },
                    onDrag = { mx, _ ->
                        playhead.setNormalizedFraction((mx - (left + 8)).toDouble() / max(1, (right - 8) - (left + 8)).toDouble())
                    }
                )
            )
        }
    }

    private fun renderStudioStatusBar(
        hdc: HDC?,
        theme: SystemThemePalette,
        fontSmall: HFONT?,
        rootWidget: DesktopNativeWidget?,
        dockPanels: List<DesktopDockPanelNativeImpl<*>>,
        clientW: Int,
        top: Int,
        bottom: Int
    ) {
        SelectObject(hdc, fontSmall)
        val dockSet = dockPanels.map { it.nativeWidget }.toSet()
        val texts = mutableListOf<DesktopTextNativeImpl<*>>()
        fun collectStatusTexts(w: DesktopNativeWidget?) {
            if (w == null || !w.visible || w.disposed || w in dockSet) return
            val impl = w.attributes["nativeImpl"]
            if (impl is DesktopTextNativeImpl<*>) texts.add(impl)
            for (c in w.children) collectStatusTexts(c)
        }
        collectStatusTexts(rootWidget)
        val leftStatus = texts.firstOrNull()?.getText().orEmpty()
        val rightStatus = texts.lastOrNull { it != texts.firstOrNull() }?.getText().orEmpty()
        drawTextInRect(hdc, leftStatus, 10, top + 2, clientW / 2, bottom - 2, theme.textSecondary)
        drawTextInRect(hdc, rightStatus, clientW / 2, top + 2, clientW - 10, bottom - 2, theme.textMuted, alignRight = true)
    }

    private fun renderHoveredTooltipOverlay(
        hdc: HDC?,
        fontSmall: HFONT?,
        theme: SystemThemePalette,
        clientW: Int,
        clientH: Int
    ) {
        val tip = hoveredTooltip?.trim().orEmpty()
        if (tip.isEmpty()) return
        SelectObject(hdc, fontSmall)
        val tipW = (tip.length * 7 + 18).coerceIn(80, max(120, clientW - 24))
        val tipH = 24
        val tx = (hoverMouseX + 12).coerceIn(8, max(8, clientW - tipW - 8))
        val ty = if (hoverMouseY + 22 + tipH < clientH - 6) {
            hoverMouseY + 20
        } else {
            max(6, hoverMouseY - tipH - 8)
        }
        fillRectColor(hdc, tx, ty, tx + tipW, ty + tipH, theme.bgElevated)
        drawRectBorder(hdc, tx, ty, tx + tipW, ty + tipH, theme.accentBlueBorder)
        drawTextInRect(hdc, tip, tx + 8, ty + 2, tx + tipW - 8, ty + tipH - 2, theme.textPrimary)
    }

    private val softClipStack = IntArray(32)
    internal var softClipDepth: Int = 0
    internal var softClipLeft: Int = 0
    internal var softClipTop: Int = 0
    internal var softClipRight: Int = Int.MAX_VALUE
    internal var softClipBottom: Int = Int.MAX_VALUE

    internal fun resetSoftClipRect() {
        softClipDepth = 0
        softClipLeft = 0
        softClipTop = 0
        softClipRight = Int.MAX_VALUE
        softClipBottom = Int.MAX_VALUE
    }

    internal fun pushClipRect(hdc: HDC?, left: Int, top: Int, right: Int, bottom: Int): Int {
        val savedDc = if (hdc != null) SaveDC(hdc) else 0
        if (hdc != null) {
            IntersectClipRect(hdc, left, top, right, bottom)
        }
        val stackIdx = softClipDepth * 4
        if (stackIdx + 3 < softClipStack.size) {
            softClipStack[stackIdx] = softClipLeft
            softClipStack[stackIdx + 1] = softClipTop
            softClipStack[stackIdx + 2] = softClipRight
            softClipStack[stackIdx + 3] = softClipBottom
        }
        if (softClipDepth == 0) {
            softClipLeft = left
            softClipTop = top
            softClipRight = right
            softClipBottom = bottom
        } else {
            softClipLeft = max(softClipLeft, left)
            softClipTop = max(softClipTop, top)
            softClipRight = min(softClipRight, right)
            softClipBottom = min(softClipBottom, bottom)
        }
        softClipDepth++
        return savedDc
    }

    internal fun popClipRect(hdc: HDC?, savedDc: Int) {
        if (hdc != null && savedDc != 0) {
            RestoreDC(hdc, savedDc)
        }
        if (softClipDepth > 0) {
            softClipDepth--
            val stackIdx = softClipDepth * 4
            if (softClipDepth == 0) {
                softClipLeft = 0
                softClipTop = 0
                softClipRight = Int.MAX_VALUE
                softClipBottom = Int.MAX_VALUE
            } else if (stackIdx + 3 < softClipStack.size) {
                softClipLeft = softClipStack[stackIdx]
                softClipTop = softClipStack[stackIdx + 1]
                softClipRight = softClipStack[stackIdx + 2]
                softClipBottom = softClipStack[stackIdx + 3]
            }
        }
    }

    internal fun colorRefToBgraWord(colorRef: UInt): UInt {
        val r = colorRef and 0xFFu
        val g = (colorRef shr 8) and 0xFFu
        val b = (colorRef shr 16) and 0xFFu
        return 0xFF000000u or (r shl 16) or (g shl 8) or b
    }

    private val sharedScratchRect: RECT = nativeHeap.alloc<RECT>()

    private inline fun readBackBufferWord(bufPixels: CPointer<ByteVar>, wordIndex: Int): UInt =
        bufPixels.reinterpret<UIntVar>()[wordIndex]

    private inline fun writeBackBufferWord(bufPixels: CPointer<ByteVar>, wordIndex: Int, value: UInt) {
        bufPixels.reinterpret<UIntVar>()[wordIndex] = value
    }

    private fun fillBackBufferSpanWords(
        bufPixels: CPointer<ByteVar>,
        startWordIndex: Int,
        wordCount: Int,
        packedBgra: UInt
    ) {
        if (wordCount <= 0) return
        val wordsPtr = bufPixels.reinterpret<UIntVar>()
        if (wordCount <= 32) {
            var i = startWordIndex
            val end = startWordIndex + wordCount
            while (i < end) {
                wordsPtr[i] = packedBgra
                i++
            }
            return
        }
        val basePtr = (bufPixels + (startWordIndex * 4))!!
        var i = 0
        while (i < 16) {
            wordsPtr[startWordIndex + i] = packedBgra
            i++
        }
        var filled = 16
        while (filled < wordCount) {
            val copyWords = min(filled, wordCount - filled)
            platform.posix.memcpy(
                basePtr + (filled * 4),
                basePtr,
                (copyWords * 4).toULong()
            )
            filled += copyWords
        }
    }

    internal fun drawRoundedButtonBackground(
        hdc: HDC?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        bg: UInt,
        border: UInt,
        radius: Int = 6,
        widget: DesktopNativeWidget? = null,
        hwndForHover: HWND? = null,
        opacity: Float = 1f
    ) {
        val isHovered = (widget != null && hoveredWidget == widget) ||
            (hwndForHover != null && isRectHovered(hwndForHover, left, top, right, bottom))
        val isPressed = widget != null && pressedWidget == widget
        val effectiveBg = when {
            isPressed -> lightenColorRef(bg, -16)
            isHovered -> lightenColorRef(bg, 16)
            else -> bg
        }
        val effectiveBorder = if (isHovered) lightenColorRef(border, 24) else border
        drawRoundedRect(hdc, left, top, right, bottom, radius, effectiveBg, effectiveBorder, opacity = opacity)
    }

    internal fun drawRoundedButtonForeground(
        hdc: HDC?,
        font: HFONT?,
        label: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        fg: UInt,
        icon: String? = null
    ) {
        if (font != null) {
            SelectObject(hdc, font)
        }
        drawButtonLabelAndIcon(hdc, label, icon, left, top, right, bottom, fg)
    }

    internal fun drawRoundedButton(
        hdc: HDC?,
        font: HFONT?,
        label: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        bg: UInt,
        border: UInt,
        fg: UInt,
        radius: Int = 6,
        icon: String? = null,
        widget: DesktopNativeWidget? = null,
        hwndForHover: HWND? = null,
        drawBackground: Boolean = true,
        drawForeground: Boolean = true,
        opacity: Float = 1f
    ) {
        if (drawBackground) {
            drawRoundedButtonBackground(
                hdc = hdc,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                bg = bg,
                border = border,
                radius = radius,
                widget = widget,
                hwndForHover = hwndForHover,
                opacity = opacity
            )
        }
        if (drawForeground) {
            drawRoundedButtonForeground(
                hdc = hdc,
                font = font,
                label = label,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                fg = fg,
                icon = icon
            )
        }
    }

    internal fun colorRefToRgba(colorRef: UInt, alpha: Int = 255): RgbaColor =
        RgbaColor(
            r = colorRef.toInt() and 0xFF,
            g = (colorRef.toInt() ushr 8) and 0xFF,
            b = (colorRef.toInt() ushr 16) and 0xFF,
            a = alpha.coerceIn(0, 255)
        )

    private data class RoundedRectSpriteKey(
        val w: Int,
        val h: Int,
        val rQuantized: Int,
        val packedFillRgba: Int,
        val packedBorderRgba: Int,
        val bwQuantized: Int
    )

    private class RoundedRectSprite(
        val w: Int,
        val h: Int,
        val entries: LongArray,
        val rowConstStart: IntArray,
        val rowConstEnd: IntArray,
        val rowConstEntry: LongArray
    )

    private const val MAX_ROUNDED_RECT_SPRITES = 256
    private val cachedRoundedRectSprites = LinkedHashMap<RoundedRectSpriteKey, RoundedRectSprite>()
    private val cachedGradientLuts = LinkedHashMap<Pair<Gradient, Int>, IntArray>(32)

    private fun packSpritePixelEntry(
        dOuter: Float,
        hasBorder: Boolean,
        bw: Float,
        fillR: Int,
        fillG: Int,
        fillB: Int,
        fillBaseAlpha: Float,
        borderR: Int,
        borderG: Int,
        borderB: Int,
        borderBaseAlpha: Float
    ): Long {
        val aOuter = (0.5f - dOuter).coerceIn(0f, 1f)
        if (aOuter <= 0.001f) return 0L
        val aInner = if (hasBorder) (0.5f - (dOuter + bw)).coerceIn(0f, 1f) else aOuter
        val aBorder = aOuter - aInner
        val srcFillA = fillBaseAlpha * aInner
        val srcBorderA = borderBaseAlpha * aBorder
        val totalSrcA = (srcFillA + srcBorderA).coerceIn(0f, 1f)
        if (totalSrcA <= 0.001f) return 0L
        val a255 = (totalSrcA * 255f).roundToInt().coerceIn(0, 255)
        if (a255 <= 0) return 0L
        val invA = 255 - a255
        if (invA <= 0) {
            val outR = (fillR * srcFillA + borderR * srcBorderA).roundToInt().coerceIn(0, 255)
            val outG = (fillG * srcFillA + borderG * srcBorderA).roundToInt().coerceIn(0, 255)
            val outB = (fillB * srcFillA + borderB * srcBorderA).roundToInt().coerceIn(0, 255)
            val packedBgra = 0xFF000000u or (outR.toUInt() shl 16) or (outG.toUInt() shl 8) or outB.toUInt()
            return (-1L shl 32) or (packedBgra.toLong() and 0xFFFFFFFFL)
        }
        val premulR = (fillR * srcFillA * 255f + borderR * srcBorderA * 255f).roundToInt().coerceIn(0, 65025)
        val premulG = (fillG * srcFillA * 255f + borderG * srcBorderA * 255f).roundToInt().coerceIn(0, 65025)
        val premulB = (fillB * srcFillA * 255f + borderB * srcBorderA * 255f).roundToInt().coerceIn(0, 65025)
        return ((invA.toLong() and 0xFFL) shl 48) or
            ((premulR.toLong() and 0xFFFFL) shl 32) or
            ((premulG.toLong() and 0xFFFFL) shl 16) or
            (premulB.toLong() and 0xFFFFL)
    }

    private fun getOrBuildRoundedRectSprite(
        w: Int,
        h: Int,
        r: Float,
        bw: Float,
        hasBorder: Boolean,
        fillR: Int,
        fillG: Int,
        fillB: Int,
        fillAInt: Int,
        fillBaseAlpha: Float,
        borderR: Int,
        borderG: Int,
        borderB: Int,
        borderAInt: Int,
        borderBaseAlpha: Float
    ): RoundedRectSprite {
        val rQuantized = (r * 16f).roundToInt()
        val bwQuantized = (bw * 16f).roundToInt()
        val capExtent = kotlin.math.ceil(r + bw + 2f).toInt().coerceAtLeast(2)
        val canonW = min(w, capExtent * 2 + 4)
        val canonH = min(h, capExtent * 2 + 4)
        val packedFill = (fillAInt shl 24) or ((fillR and 0xFF) shl 16) or ((fillG and 0xFF) shl 8) or (fillB and 0xFF)
        val packedBorder = if (hasBorder) {
            (borderAInt shl 24) or ((borderR and 0xFF) shl 16) or ((borderG and 0xFF) shl 8) or (borderB and 0xFF)
        } else {
            0
        }
        val key = RoundedRectSpriteKey(canonW, canonH, rQuantized, packedFill, packedBorder, bwQuantized)
        cachedRoundedRectSprites[key]?.let { return it }

        if (cachedRoundedRectSprites.size >= MAX_ROUNDED_RECT_SPRITES) {
            val firstKey = cachedRoundedRectSprites.keys.firstOrNull()
            if (firstKey != null) cachedRoundedRectSprites.remove(firstKey)
        }

        val halfW = canonW * 0.5f
        val halfH = canonH * 0.5f
        val innerBoxX = halfW - r
        val innerBoxY = halfH - r
        val spanHalfX = innerBoxX - bw - 0.5f
        val entries = LongArray(canonW * canonH)
        val rowConstStart = IntArray(canonH)
        val rowConstEnd = IntArray(canonH)
        val rowConstEntry = LongArray(canonH)

        val midStart = if (spanHalfX > 1f) kotlin.math.ceil(halfW - spanHalfX - 0.5f).toInt().coerceIn(0, canonW) else 0
        val midEnd = if (spanHalfX > 1f) (kotlin.math.floor(halfW + spanHalfX - 0.5f).toInt() + 1).coerceIn(midStart, canonW) else 0

        var firstFlatMidSy = -1
        for (sy in 0 until canonH) {
            val dy = kotlin.math.abs((sy + 0.5f) - halfH)
            val qy = dy - innerBoxY
            val rowOff = sy * canonW
            if (midEnd > midStart) {
                if (qy <= -bw - 0.5f && firstFlatMidSy >= 0) {
                    val srcOff = firstFlatMidSy * canonW
                    rowConstStart[sy] = midStart
                    rowConstEnd[sy] = midEnd
                    rowConstEntry[sy] = rowConstEntry[firstFlatMidSy]
                    if (midStart > 0) {
                        entries.copyInto(entries, rowOff, srcOff, srcOff + midStart)
                    }
                    if (midEnd < canonW) {
                        entries.copyInto(entries, rowOff + midEnd, srcOff + midEnd, srcOff + canonW)
                    }
                    continue
                }
                val constEntry = packSpritePixelEntry(
                    dOuter = qy - r,
                    hasBorder = hasBorder,
                    bw = bw,
                    fillR = fillR,
                    fillG = fillG,
                    fillB = fillB,
                    fillBaseAlpha = fillBaseAlpha,
                    borderR = borderR,
                    borderG = borderG,
                    borderB = borderB,
                    borderBaseAlpha = borderBaseAlpha
                )
                rowConstStart[sy] = midStart
                rowConstEnd[sy] = midEnd
                rowConstEntry[sy] = constEntry
                for (sx in 0 until midStart) {
                    val dx = kotlin.math.abs((sx + 0.5f) - halfW)
                    val qx = dx - innerBoxX
                    val dOuter = if (qy <= 0f) {
                        if (qx >= 0f) qx - r else max(qx, qy) - r
                    } else if (qx <= 0f) {
                        qy - r
                    } else {
                        kotlin.math.sqrt(qx * qx + qy * qy) - r
                    }
                    entries[rowOff + sx] = packSpritePixelEntry(
                        dOuter, hasBorder, bw, fillR, fillG, fillB, fillBaseAlpha, borderR, borderG, borderB, borderBaseAlpha
                    )
                }
                for (sx in midEnd until canonW) {
                    val dx = kotlin.math.abs((sx + 0.5f) - halfW)
                    val qx = dx - innerBoxX
                    val dOuter = if (qy <= 0f) {
                        if (qx >= 0f) qx - r else max(qx, qy) - r
                    } else if (qx <= 0f) {
                        qy - r
                    } else {
                        kotlin.math.sqrt(qx * qx + qy * qy) - r
                    }
                    entries[rowOff + sx] = packSpritePixelEntry(
                        dOuter, hasBorder, bw, fillR, fillG, fillB, fillBaseAlpha, borderR, borderG, borderB, borderBaseAlpha
                    )
                }
                if (qy <= -bw - 0.5f && firstFlatMidSy < 0) {
                    firstFlatMidSy = sy
                }
            } else {
                for (sx in 0 until canonW) {
                    val dx = kotlin.math.abs((sx + 0.5f) - halfW)
                    val qx = dx - innerBoxX
                    val dOuter = if (qy <= 0f) {
                        if (qx >= 0f) qx - r else max(qx, qy) - r
                    } else if (qx <= 0f) {
                        qy - r
                    } else {
                        kotlin.math.sqrt(qx * qx + qy * qy) - r
                    }
                    entries[rowOff + sx] = packSpritePixelEntry(
                        dOuter, hasBorder, bw, fillR, fillG, fillB, fillBaseAlpha, borderR, borderG, borderB, borderBaseAlpha
                    )
                }
            }
        }

        val sprite = RoundedRectSprite(canonW, canonH, entries, rowConstStart, rowConstEnd, rowConstEntry)
        cachedRoundedRectSprites[key] = sprite
        return sprite
    }

    private var sharedBlendScanlineWords = IntArray(4096)

    private inline fun blendSpriteEntryIntoIntWord(dstWord: Int, entry: Long): Int {
        if (entry == 0L) return dstWord
        if (entry < 0L) return entry.toInt()
        val invA = (entry ushr 48).toInt() and 0xFF
        val premulR = (entry ushr 32).toInt() and 0xFFFF
        val premulG = (entry ushr 16).toInt() and 0xFFFF
        val premulB = entry.toInt() and 0xFFFF
        val outB = (premulB + (dstWord and 0xFF) * invA + 128) ushr 8
        val outG = (premulG + ((dstWord ushr 8) and 0xFF) * invA + 128) ushr 8
        val outR = (premulR + ((dstWord ushr 16) and 0xFF) * invA + 128) ushr 8
        return -0x1000000 or ((outR and 0xFF) shl 16) or ((outG and 0xFF) shl 8) or (outB and 0xFF)
    }

    private inline fun blendSpriteEntryToBackBuffer(
        wordsPtr: CPointer<UIntVar>,
        wIdx: Int,
        entry: Long
    ) {
        if (entry == 0L) return
        if (entry < 0L) {
            wordsPtr[wIdx] = entry.toUInt()
            return
        }
        val invA = (entry ushr 48).toInt() and 0xFF
        val premulR = (entry ushr 32).toInt() and 0xFFFF
        val premulG = (entry ushr 16).toInt() and 0xFFFF
        val premulB = entry.toInt() and 0xFFFF
        val dstWord = wordsPtr[wIdx].toInt()
        val outB = ((premulB + (dstWord and 0xFF) * invA) / 255).toUInt()
        val outG = ((premulG + ((dstWord ushr 8) and 0xFF) * invA) / 255).toUInt()
        val outR = ((premulR + ((dstWord ushr 16) and 0xFF) * invA) / 255).toUInt()
        wordsPtr[wIdx] = 0xFF000000u or (outR shl 16) or (outG shl 8) or outB
    }

    /**
     * Analytical Signed Distance Field (SDF) sub-pixel antialiased rounded-rectangle and border
     * rasterizer with true 32-bit backbuffer alpha blending and active clip-box respect.
     */
    internal fun drawAntialiasedRoundedRectRgba(
        hdc: HDC?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        radiusPx: Float,
        fillColor: RgbaColor?,
        borderColor: RgbaColor?,
        borderWidthPx: Float = 1f,
        opacity: Float = 1f,
        gradient: Gradient? = null
    ) {
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return
        val effOpacity = opacity.coerceIn(0f, 1f)
        if (effOpacity <= 0.001f) return

        val isActiveDc = (hdc == null || hdc == activeBackBufferDc) && activeBackBufferPixels != null
        val bufPixels = if (isActiveDc) activeBackBufferPixels else cachedMainBackBufferPixels
        val bufW = if (isActiveDc) activeBackBufferWidth else cachedMainBackBufferWidth
        val bufH = if (isActiveDc) activeBackBufferHeight else cachedMainBackBufferHeight

        if (bufPixels == null || bufW <= 0 || bufH <= 0) {
            val fallbackFill = fillColor?.let { rgbaToColorRef(it) } ?: rgb(45, 45, 48)
            val fallbackBorder = borderColor?.let { rgbaToColorRef(it) } ?: fallbackFill
            val brush = CreateSolidBrush(fallbackFill)
            val pen = CreatePen(PS_SOLID, borderWidthPx.roundToInt().coerceAtLeast(1), fallbackBorder)
            val oldBrush = SelectObject(hdc, brush)
            val oldPen = SelectObject(hdc, pen)
            val rInt = radiusPx.roundToInt().coerceAtLeast(0)
            RoundRect(hdc, left, top, right, bottom, rInt * 2, rInt * 2)
            SelectObject(hdc, oldBrush)
            SelectObject(hdc, oldPen)
            DeleteObject(brush)
            DeleteObject(pen)
            markGdiRectDirty(left, top, right, bottom)
            return
        }

        val clipL: Int
        val clipT: Int
        val clipR: Int
        val clipB: Int
        if (softClipDepth > 0) {
            clipL = softClipLeft.coerceIn(0, bufW)
            clipT = softClipTop.coerceIn(0, bufH)
            clipR = softClipRight.coerceIn(clipL, bufW)
            clipB = softClipBottom.coerceIn(clipT, bufH)
        } else {
            clipL = 0
            clipT = 0
            clipR = bufW
            clipB = bufH
        }

        val x0 = max(left, clipL)
        val y0 = max(top, clipT)
        val x1 = min(right, clipR)
        val y1 = min(bottom, clipB)
        if (x1 <= x0 || y1 <= y0) return

        flushGdiIfOverlapping(x0, y0, x1, y1)
        markFrameBufferDirty(x0, y0, x1, y1)

        val wordsPtr = bufPixels.reinterpret<UIntVar>()
        val halfW = w * 0.5f
        val halfH = h * 0.5f
        val cx = left + halfW
        val cy = top + halfH
        val r = radiusPx.coerceIn(0f, min(halfW, halfH))
        val innerBoxX = halfW - r
        val innerBoxY = halfH - r
        val bw = if (borderColor != null && borderColor.a > 0 && borderWidthPx > 0f) borderWidthPx else 0f

        val hasFill = (fillColor != null && fillColor.a > 0) || gradient != null
        val fillR = fillColor?.r ?: 0
        val fillG = fillColor?.g ?: 0
        val fillB = fillColor?.b ?: 0
        val fillBaseAlpha = if (fillColor != null) (fillColor.a / 255f) * effOpacity else 0f
        val fillAInt = (fillBaseAlpha * 255f).roundToInt().coerceIn(0, 255)

        val hasBorder = bw > 0f && borderColor != null && borderColor.a > 0
        val borderR = borderColor?.r ?: 0
        val borderG = borderColor?.g ?: 0
        val borderB = borderColor?.b ?: 0
        val borderBaseAlpha = if (borderColor != null) (borderColor.a / 255f) * effOpacity else 0f
        val borderAInt = (borderBaseAlpha * 255f).roundToInt().coerceIn(0, 255)

        if (!hasFill && !hasBorder) return

        // Fast pre-rendered canonical RoundedRectSprite cache for UI controls, buttons, pills, badges, rows, and cards
        if (gradient == null && w >= 4 && h >= 4) {
            val sprite = getOrBuildRoundedRectSprite(
                w = w,
                h = h,
                r = r,
                bw = bw,
                hasBorder = hasBorder,
                fillR = fillR,
                fillG = fillG,
                fillB = fillB,
                fillAInt = fillAInt,
                fillBaseAlpha = fillBaseAlpha,
                borderR = borderR,
                borderG = borderG,
                borderB = borderB,
                borderAInt = borderAInt,
                borderBaseAlpha = borderBaseAlpha
            )
            val canonW = sprite.w
            val canonH = sprite.h
            val rowCap = (canonH - 4) / 2
            val isOpaqueRect = fillAInt >= 255 && (!hasBorder || borderAInt >= 255)
            var firstFlatMidRowByteOffset = -1
            val rowSpanWords = x1 - x0
            val rowSpanBytes = (rowSpanWords * 4).toULong()
            if (sharedBlendScanlineWords.size < rowSpanWords) {
                sharedBlendScanlineWords = IntArray(max(rowSpanWords, 4096))
            }
            val scanArr = sharedBlendScanlineWords
            val entries = sprite.entries
            val cStarts = sprite.rowConstStart
            val cEnds = sprite.rowConstEnd
            val cEntries = sprite.rowConstEntry
            val sx0 = x0 - left
            val sx1 = x1 - left
            scanArr.usePinned { scanPin ->
                val scanPtr = scanPin.addressOf(0)
                for (py in y0 until y1) {
                    val sy = py - top
                    val isFlatMidRow = canonH < h && sy in rowCap until (h - rowCap)
                    val curRowByteOffset = (py * bufW + x0) * 4
                    if (isFlatMidRow && isOpaqueRect && firstFlatMidRowByteOffset >= 0) {
                        platform.posix.memcpy(
                            bufPixels + curRowByteOffset,
                            bufPixels + firstFlatMidRowByteOffset,
                            rowSpanBytes
                        )
                        continue
                    }
                    val canonSy = when {
                        canonH >= h -> sy
                        sy < rowCap -> sy
                        sy >= h - rowCap -> canonH - (h - sy)
                        else -> rowCap
                    }
                    val sRowOff = canonSy * canonW
                    val rawCStart = cStarts[canonSy]
                    val rawCEnd = cEnds[canonSy]
                    val actualCStart = if (rawCEnd > rawCStart) rawCStart else 0
                    val actualCEnd = if (rawCEnd > rawCStart) w - (canonW - rawCEnd) else 0
                    val cStart = max(sx0, actualCStart)
                    val cEnd = min(sx1, actualCEnd)
                    val constEntry = if (cEnd > cStart) cEntries[canonSy] else 0L

                    if (cStart == sx0 && cEnd == sx1 && constEntry < 0L) {
                        fillBackBufferSpanWords(
                            bufPixels = bufPixels,
                            startWordIndex = py * bufW + x0,
                            wordCount = rowSpanWords,
                            packedBgra = constEntry.toUInt()
                        )
                    } else {
                        platform.posix.memcpy(scanPtr, bufPixels + curRowByteOffset, rowSpanBytes)
                        if (cEnd > cStart) {
                            for (sx in sx0 until cStart) {
                                val canonSx = if (canonW >= w || sx < actualCStart) sx else canonW - (w - sx)
                                val idx = sx - sx0
                                scanArr[idx] = blendSpriteEntryIntoIntWord(scanArr[idx], entries[sRowOff + canonSx])
                            }
                            if (constEntry < 0L) {
                                val opaqueInt = constEntry.toInt()
                                for (sx in cStart until cEnd) {
                                    scanArr[sx - sx0] = opaqueInt
                                }
                            } else if (constEntry > 0L) {
                                val invA = (constEntry ushr 48).toInt() and 0xFF
                                val premulR = (constEntry ushr 32).toInt() and 0xFFFF
                                val premulG = (constEntry ushr 16).toInt() and 0xFFFF
                                val premulB = constEntry.toInt() and 0xFFFF
                                val iStart = cStart - sx0
                                val iEnd = cEnd - sx0
                                for (i in iStart until iEnd) {
                                    val dstWord = scanArr[i]
                                    val outB = (premulB + (dstWord and 0xFF) * invA + 128) ushr 8
                                    val outG = (premulG + ((dstWord ushr 8) and 0xFF) * invA + 128) ushr 8
                                    val outR = (premulR + ((dstWord ushr 16) and 0xFF) * invA + 128) ushr 8
                                    scanArr[i] = -0x1000000 or ((outR and 0xFF) shl 16) or ((outG and 0xFF) shl 8) or (outB and 0xFF)
                                }
                            }
                            for (sx in cEnd until sx1) {
                                val canonSx = if (canonW >= w || sx < actualCStart) sx else canonW - (w - sx)
                                val idx = sx - sx0
                                scanArr[idx] = blendSpriteEntryIntoIntWord(scanArr[idx], entries[sRowOff + canonSx])
                            }
                        } else {
                            for (sx in sx0 until sx1) {
                                val canonSx = if (canonW >= w || sx < actualCStart) sx else canonW - (w - sx)
                                val idx = sx - sx0
                                scanArr[idx] = blendSpriteEntryIntoIntWord(scanArr[idx], entries[sRowOff + canonSx])
                            }
                        }
                        platform.posix.memcpy(bufPixels + curRowByteOffset, scanPtr, rowSpanBytes)
                    }
                    if (isFlatMidRow && isOpaqueRect && firstFlatMidRowByteOffset < 0) {
                        firstFlatMidRowByteOffset = curRowByteOffset
                    }
                }
            }
            return
        }

        val packedOpaqueFill = 0xFF000000u or (fillR.toUInt() shl 16) or (fillG.toUInt() shl 8) or fillB.toUInt()
        val packedOpaqueBorder = 0xFF000000u or (borderR.toUInt() shl 16) or (borderG.toUInt() shl 8) or borderB.toUInt()
        val isStandardOpaque1PxBorder = gradient == null && fillAInt >= 255 &&
            (!hasBorder || (bw == 1f && borderAInt >= 255))

        val spanHalfX = innerBoxX - bw - 0.5f
        val invW = 1f / w.toFloat()
        val invH = 1f / h.toFloat()

        // Precompute 256-entry gradient LUT when gradient is present (zero per-pixel allocations!)
        val gradLut: IntArray?
        val linGrad = gradient as? com.nexustools.genericui.style.gradient.Gradient.LinearGradient
        val radGrad = gradient as? com.nexustools.genericui.style.gradient.Gradient.RadialGradient
        val linDirX: Float
        val linDirY: Float
        val linInvMaxExtent: Float
        if (gradient != null) {
            val opQuant = (effOpacity * 255f).roundToInt().coerceIn(0, 255)
            val lutKey = gradient to opQuant
            gradLut = cachedGradientLuts[lutKey] ?: run {
                if (cachedGradientLuts.size >= 32) {
                    cachedGradientLuts.keys.firstOrNull()?.let { cachedGradientLuts.remove(it) }
                }
                IntArray(256).also { arr ->
                    for (i in 0..255) {
                        val gc = gradient.interpolateAt(i / 255f)
                        val ga = ((gc.a / 255f) * effOpacity * 255f).roundToInt().coerceIn(0, 255)
                        arr[i] = (ga shl 24) or ((gc.r and 0xFF) shl 16) or ((gc.g and 0xFF) shl 8) or (gc.b and 0xFF)
                    }
                    cachedGradientLuts[lutKey] = arr
                }
            }
            if (linGrad != null) {
                val rad = (linGrad.angleDegrees.toDouble() * kotlin.math.PI) / 180.0
                linDirX = kotlin.math.sin(rad).toFloat()
                linDirY = (-kotlin.math.cos(rad)).toFloat()
                linInvMaxExtent = 1f / (kotlin.math.abs(linDirX) + kotlin.math.abs(linDirY)).coerceAtLeast(0.0001f)
            } else {
                linDirX = 0f
                linDirY = 1f
                linInvMaxExtent = 1f
            }
        } else {
            gradLut = null
            linDirX = 0f
            linDirY = 1f
            linInvMaxExtent = 1f
        }

        // Fast-path for unbordered, unrounded gradient rects (e.g., top/bottom HUD banners)
        if (gradLut != null && linGrad != null && r <= 0.5f && !hasBorder) {
            if (kotlin.math.abs(linDirX) < 0.001f) {
                // Pure vertical linear gradient (180deg or 0deg) — constant color per scanline!
                val rowSpanWords = x1 - x0
                val rowSpanBytes = (rowSpanWords * 4).toULong()
                if (sharedBlendScanlineWords.size < rowSpanWords) {
                    sharedBlendScanlineWords = IntArray(max(rowSpanWords, 4096))
                }
                val scanArr = sharedBlendScanlineWords
                scanArr.usePinned { scanPin ->
                    val scanPtr = scanPin.addressOf(0)
                    for (py in y0 until y1) {
                        val v = ((py + 0.5f - top) * invH).coerceIn(0f, 1f)
                        val proj = ((v - 0.5f) * linDirY * linInvMaxExtent + 0.5f).coerceIn(0f, 1f)
                        val packed = gradLut[(proj * 255f).toInt().coerceIn(0, 255)]
                        val ga = (packed ushr 24) and 0xFF
                        if (ga <= 0) continue
                        val rowWordOffset = py * bufW
                        if (ga >= 255) {
                            fillBackBufferSpanWords(
                                bufPixels = bufPixels,
                                startWordIndex = rowWordOffset + x0,
                                wordCount = rowSpanWords,
                                packedBgra = 0xFF000000u or (packed.toUInt() and 0x00FFFFFFu)
                            )
                        } else {
                            val gr = (packed ushr 16) and 0xFF
                            val gg = (packed ushr 8) and 0xFF
                            val gb = packed and 0xFF
                            val premulRB = ((gr * ga) shl 16) or (gb * ga)
                            val premulG = (gg * ga) shl 8
                            val invA256 = 256 - ga
                            val rowByteOffset = (rowWordOffset + x0) * 4
                            platform.posix.memcpy(scanPtr, bufPixels + rowByteOffset, rowSpanBytes)
                            for (i in 0 until rowSpanWords) {
                                val dst = scanArr[i]
                                val rb = (((dst and 0x00FF00FF) * invA256 + premulRB) ushr 8) and 0x00FF00FF
                                val g = (((dst and 0x0000FF00) * invA256 + premulG) ushr 8) and 0x0000FF00
                                scanArr[i] = -0x1000000 or rb or g
                            }
                            platform.posix.memcpy(bufPixels + rowByteOffset, scanPtr, rowSpanBytes)
                        }
                    }
                }
                return
            }
        }

        fun sampleGradPacked(u: Float, v: Float): Int {
            val lut = gradLut ?: return 0
            if (linGrad != null) {
                val proj = (((u - 0.5f) * linDirX + (v - 0.5f) * linDirY) * linInvMaxExtent + 0.5f).coerceIn(0f, 1f)
                return lut[(proj * 255f).toInt().coerceIn(0, 255)]
            }
            if (radGrad != null) {
                val rx = radGrad.radiusX.coerceAtLeast(0.0001f)
                val ry = if (radGrad.shape == com.nexustools.genericui.style.gradient.Gradient.RadialGradientShape.CIRCLE) rx else radGrad.radiusY.coerceAtLeast(0.0001f)
                val dx = (u - radGrad.centerX) / rx
                val dy = (v - radGrad.centerY) / ry
                val dist = kotlin.math.sqrt(dx * dx + dy * dy).coerceIn(0f, 1f)
                return lut[(dist * 255f).toInt().coerceIn(0, 255)]
            }
            val gc = gradient!!.sampleAt(u, v)
            val ga = ((gc.a / 255f) * effOpacity * 255f).roundToInt().coerceIn(0, 255)
            return (ga shl 24) or ((gc.r and 0xFF) shl 16) or ((gc.g and 0xFF) shl 8) or (gc.b and 0xFF)
        }

        fun blendSdfPixel(px: Int, py: Int, qy: Float, rowWordOffset: Int) {
            val dx = kotlin.math.abs((px + 0.5f) - cx)
            val qx = dx - innerBoxX
            val dOuter = if (qy <= 0f) {
                if (qx >= 0f) qx - r else max(qx, qy) - r
            } else if (qx <= 0f) {
                qy - r
            } else {
                kotlin.math.sqrt(qx * qx + qy * qy) - r
            }
            val aOuter = (0.5f - dOuter).coerceIn(0f, 1f)
            if (aOuter <= 0.001f) return

            val aInner = if (hasBorder) (0.5f - (dOuter + bw)).coerceIn(0f, 1f) else aOuter
            val aBorder = aOuter - aInner

            var fR = fillR
            var fG = fillG
            var fB = fillB
            var fBaseA = fillBaseAlpha
            if (gradient != null && aInner > 0.001f) {
                val u = ((px + 0.5f - left) * invW).coerceIn(0f, 1f)
                val v = ((py + 0.5f - top) * invH).coerceIn(0f, 1f)
                val packed = sampleGradPacked(u, v)
                fR = (packed ushr 16) and 0xFF
                fG = (packed ushr 8) and 0xFF
                fB = packed and 0xFF
                fBaseA = ((packed ushr 24) and 0xFF) / 255f
            }

            val srcFillA = fBaseA * aInner
            val srcBorderA = borderBaseAlpha * aBorder
            val totalSrcA = (srcFillA + srcBorderA).coerceIn(0f, 1f)
            if (totalSrcA <= 0.001f) return

            val wordIdx = rowWordOffset + px
            val dstWord = wordsPtr[wordIdx].toInt()
            val dstB = dstWord and 0xFF
            val dstG = (dstWord ushr 8) and 0xFF
            val dstR = (dstWord ushr 16) and 0xFF
            val invA = 1f - totalSrcA
            val outR = (fR * srcFillA + borderR * srcBorderA + dstR * invA).roundToInt().coerceIn(0, 255)
            val outG = (fG * srcFillA + borderG * srcBorderA + dstG * invA).roundToInt().coerceIn(0, 255)
            val outB = (fB * srcFillA + borderB * srcBorderA + dstB * invA).roundToInt().coerceIn(0, 255)
            wordsPtr[wordIdx] = 0xFF000000u or (outR.toUInt() shl 16) or (outG.toUInt() shl 8) or outB.toUInt()
        }

        var cachedMidRowOffsetBytes: Int = -1
        val midRowSpanBytes: ULong = ((x1 - x0) * 4).toULong()

        for (py in y0 until y1) {
            val dy = kotlin.math.abs((py + 0.5f) - cy)
            val qy = dy - innerBoxY
            val rowWordOffset = py * bufW
            if (gradient == null && qy <= -bw - 0.5f) {
                if (isStandardOpaque1PxBorder && hasFill && w >= 2) {
                    val curRowByteOffset = (rowWordOffset + x0) * 4
                    if (cachedMidRowOffsetBytes >= 0) {
                        platform.posix.memcpy(
                            bufPixels + curRowByteOffset,
                            bufPixels + cachedMidRowOffsetBytes,
                            midRowSpanBytes
                        )
                    } else {
                        if (hasBorder) {
                            if (left in x0 until x1) {
                                wordsPtr[rowWordOffset + left] = packedOpaqueBorder
                            }
                            val fillStart = max(x0, left + 1)
                            val fillEnd = min(x1, right - 1)
                            if (fillEnd > fillStart) {
                                fillBackBufferSpanWords(
                                    bufPixels = bufPixels,
                                    startWordIndex = rowWordOffset + fillStart,
                                    wordCount = fillEnd - fillStart,
                                    packedBgra = packedOpaqueFill
                                )
                            }
                            if (right - 1 in x0 until x1 && right - 1 > left) {
                                wordsPtr[rowWordOffset + right - 1] = packedOpaqueBorder
                            }
                        } else {
                            fillBackBufferSpanWords(
                                bufPixels = bufPixels,
                                startWordIndex = rowWordOffset + x0,
                                wordCount = x1 - x0,
                                packedBgra = packedOpaqueFill
                            )
                        }
                        cachedMidRowOffsetBytes = curRowByteOffset
                    }
                    continue
                }
                if (spanHalfX > 1f) {
                    val spanStart = max(x0, kotlin.math.ceil(cx - spanHalfX - 0.5f).toInt())
                    val spanEnd = min(x1, kotlin.math.floor(cx + spanHalfX - 0.5f).toInt() + 1)
                    if (spanEnd > spanStart) {
                        for (px in x0 until spanStart) {
                            blendSdfPixel(px, py, qy, rowWordOffset)
                        }
                        if (hasFill && fillAInt > 0) {
                            if (fillAInt >= 255) {
                                fillBackBufferSpanWords(
                                    bufPixels = bufPixels,
                                    startWordIndex = rowWordOffset + spanStart,
                                    wordCount = spanEnd - spanStart,
                                    packedBgra = packedOpaqueFill
                                )
                            } else {
                                val invAInt = 255 - fillAInt
                                val premulR = fillR * fillAInt
                                val premulG = fillG * fillAInt
                                val premulB = fillB * fillAInt
                                var wIdx = rowWordOffset + spanStart
                                val wEnd = rowWordOffset + spanEnd
                                while (wIdx < wEnd) {
                                    val dstWord = wordsPtr[wIdx].toInt()
                                    val dstB = dstWord and 0xFF
                                    val dstG = (dstWord ushr 8) and 0xFF
                                    val dstR = (dstWord ushr 16) and 0xFF
                                    val outB = ((premulB + dstB * invAInt) / 255).toUInt()
                                    val outG = ((premulG + dstG * invAInt) / 255).toUInt()
                                    val outR = ((premulR + dstR * invAInt) / 255).toUInt()
                                    wordsPtr[wIdx] = 0xFF000000u or (outR shl 16) or (outG shl 8) or outB
                                    wIdx++
                                }
                            }
                        }
                        for (px in spanEnd until x1) {
                            blendSdfPixel(px, py, qy, rowWordOffset)
                        }
                        continue
                    }
                }
            }
            if (isStandardOpaque1PxBorder && hasFill && spanHalfX > 1f) {
                val spanStart = max(x0, kotlin.math.ceil(cx - spanHalfX - 0.5f).toInt())
                val spanEnd = min(x1, kotlin.math.floor(cx + spanHalfX - 0.5f).toInt() + 1)
                if (spanEnd > spanStart) {
                    for (px in x0 until spanStart) {
                        blendSdfPixel(px, py, qy, rowWordOffset)
                    }
                    if (hasBorder && py == top && top in y0 until y1) {
                        fillBackBufferSpanWords(
                            bufPixels = bufPixels,
                            startWordIndex = rowWordOffset + spanStart,
                            wordCount = spanEnd - spanStart,
                            packedBgra = packedOpaqueBorder
                        )
                    } else if (hasBorder && py == bottom - 1 && (bottom - 1) in y0 until y1) {
                        fillBackBufferSpanWords(
                            bufPixels = bufPixels,
                            startWordIndex = rowWordOffset + spanStart,
                            wordCount = spanEnd - spanStart,
                            packedBgra = packedOpaqueBorder
                        )
                    } else {
                        fillBackBufferSpanWords(
                            bufPixels = bufPixels,
                            startWordIndex = rowWordOffset + spanStart,
                            wordCount = spanEnd - spanStart,
                            packedBgra = packedOpaqueFill
                        )
                    }
                    for (px in spanEnd until x1) {
                        blendSdfPixel(px, py, qy, rowWordOffset)
                    }
                    continue
                }
            }
            // Constant horizontal middle-span (qx <= 0f) optimization for top/bottom rows of non-gradient rounded rects
            if (gradient == null && spanHalfX > 1f) {
                val spanStart = max(x0, kotlin.math.ceil(cx - spanHalfX - 0.5f).toInt())
                val spanEnd = min(x1, kotlin.math.floor(cx + spanHalfX - 0.5f).toInt() + 1)
                if (spanEnd > spanStart) {
                    for (px in x0 until spanStart) {
                        blendSdfPixel(px, py, qy, rowWordOffset)
                    }
                    val constEntry = packSpritePixelEntry(
                        dOuter = qy - r,
                        hasBorder = hasBorder,
                        bw = bw,
                        fillR = fillR,
                        fillG = fillG,
                        fillB = fillB,
                        fillBaseAlpha = fillBaseAlpha,
                        borderR = borderR,
                        borderG = borderG,
                        borderB = borderB,
                        borderBaseAlpha = borderBaseAlpha
                    )
                    if (constEntry < 0L) {
                        fillBackBufferSpanWords(
                            bufPixels = bufPixels,
                            startWordIndex = rowWordOffset + spanStart,
                            wordCount = spanEnd - spanStart,
                            packedBgra = constEntry.toUInt()
                        )
                    } else if (constEntry > 0L) {
                        val invA = (constEntry ushr 48).toInt() and 0xFF
                        val premulR = (constEntry ushr 32).toInt() and 0xFFFF
                        val premulG = (constEntry ushr 16).toInt() and 0xFFFF
                        val premulB = constEntry.toInt() and 0xFFFF
                        var wIdx = rowWordOffset + spanStart
                        val wEnd = rowWordOffset + spanEnd
                        while (wIdx < wEnd) {
                            val dstWord = wordsPtr[wIdx].toInt()
                            val outB = ((premulB + (dstWord and 0xFF) * invA) / 255).toUInt()
                            val outG = ((premulG + ((dstWord ushr 8) and 0xFF) * invA) / 255).toUInt()
                            val outR = ((premulR + ((dstWord ushr 16) and 0xFF) * invA) / 255).toUInt()
                            wordsPtr[wIdx] = 0xFF000000u or (outR shl 16) or (outG shl 8) or outB
                            wIdx++
                        }
                    }
                    for (px in spanEnd until x1) {
                        blendSdfPixel(px, py, qy, rowWordOffset)
                    }
                    continue
                }
            }
            for (px in x0 until x1) {
                blendSdfPixel(px, py, qy, rowWordOffset)
            }
        }
    }

    internal fun drawRoundedRect(
        hdc: HDC?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        radius: Int,
        fillColor: UInt,
        borderColor: UInt,
        opacity: Float = 1f
    ) {
        val fillRgba = colorRefToRgba(fillColor, 255)
        val borderRgba = if (borderColor == fillColor) null else colorRefToRgba(borderColor, 255)
        drawAntialiasedRoundedRectRgba(
            hdc = hdc,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            radiusPx = radius.toFloat(),
            fillColor = fillRgba,
            borderColor = borderRgba,
            borderWidthPx = if (borderRgba != null) 1f else 0f,
            opacity = opacity
        )
    }

    internal fun drawDashedRoundedRect(
        hdc: HDC?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        radius: Int,
        fillColor: UInt,
        borderColor: UInt
    ) {
        val brush = CreateSolidBrush(fillColor)
        val pen = CreatePen(PS_DASH, 1, borderColor)
        val oldBrush = SelectObject(hdc, brush)
        val oldPen = SelectObject(hdc, pen)
        RoundRect(hdc, left, top, right, bottom, radius * 2, radius * 2)
        SelectObject(hdc, oldBrush)
        SelectObject(hdc, oldPen)
        DeleteObject(brush)
        DeleteObject(pen)
        markGdiRectDirty(left, top, right, bottom)
    }

    internal fun isRectHovered(hwnd: HWND?, left: Int, top: Int, right: Int, bottom: Int): Boolean {
        if (hwnd != null && hoverHwndKey != 0L && hoverHwndKey != hwnd.toLong()) return false
        return hoverMouseX in left..right && hoverMouseY in top..bottom
    }

    internal fun lightenColorRef(colorRef: UInt, delta: Int): UInt {
        val r = ((colorRef.toInt() and 0xFF) + delta).coerceIn(0, 255)
        val g = (((colorRef.toInt() ushr 8) and 0xFF) + delta).coerceIn(0, 255)
        val b = (((colorRef.toInt() ushr 16) and 0xFF) + delta).coerceIn(0, 255)
        return rgb(r, g, b)
    }

    internal fun rgbaToColorRef(color: RgbaColor, backdropColorRef: UInt? = null): UInt {
        if (backdropColorRef == null || color.a >= 250) {
            return rgb(color.r, color.g, color.b)
        }
        val a = color.a.coerceIn(0, 255)
        val invA = 255 - a
        val bgR = (backdropColorRef.toInt() and 0xFF)
        val bgG = ((backdropColorRef.toInt() ushr 8) and 0xFF)
        val bgB = ((backdropColorRef.toInt() ushr 16) and 0xFF)
        val outR = ((color.r * a + bgR * invA) / 255).coerceIn(0, 255)
        val outG = ((color.g * a + bgG * invA) / 255).coerceIn(0, 255)
        val outB = ((color.b * a + bgB * invA) / 255).coerceIn(0, 255)
        return rgb(outR, outG, outB)
    }

    /**
     * Renders a [Gradient] ([com.nexustools.genericui.style.gradient.Gradient.LinearGradient] or
     * [com.nexustools.genericui.style.gradient.Gradient.RadialGradient]) into `[left..right, top..bottom]`
     * with full per-pixel alpha-blending over the 32-bit backbuffer.
     */
    internal fun drawGradientRect(
        hdc: HDC?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        gradient: Gradient,
        opacity: Float = 1f
    ) {
        if (right <= left || bottom <= top) return
        drawAntialiasedRoundedRectRgba(
            hdc = hdc,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            radiusPx = 0f,
            fillColor = null,
            borderColor = null,
            borderWidthPx = 0f,
            opacity = opacity,
            gradient = gradient
        )
    }

    /**
     * Renders a native system button with support for:
     * - Standard native OS button surface (`primary == false` and no custom `tint`)
     * - Tinted / Primary native OS button surface (`style { tint: RgbaColor(...) }` or `primary == true`)
     * - Gradient backgrounds (`style { background = LinearGradient(...) }` or `RadialGradient(...)`)
     * - CSS pseudo-class states (`:hover`, `:active`, `:focus`) with distinct hover shading, pressed bevel, and focus ring!
     */
    fun resolveButtonThemeClassList(darkTheme: Boolean = isWindowsSystemDarkTheme()): String =
        if (darkTheme) "DarkMode_Explorer::BUTTON" else "Explorer::BUTTON"

    fun usesNativeSystemControlRendering(widget: DesktopNativeWidget?): Boolean {
        val impl = widget?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*> ?: return true
        return impl.getEffectiveBackgroundColor() == null &&
            impl.getEffectiveBackgroundGradient() == null &&
            impl.getType()?.takeIf { it.isSemantic } == null &&
            impl.getBackgroundType()?.takeIf { it.isSemantic } == null &&
            impl.getComponentStyle()?.accentColor == null
    }

    fun resolveControlBackgroundColor(
        widget: DesktopNativeWidget?,
        darkTheme: Boolean = isWindowsSystemDarkTheme()
    ): RgbaColor {
        val impl = widget?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>
        impl?.getEffectiveBackgroundColor()?.let { return it }
        impl?.getComponentStyle()?.accentColor?.let { return it }
        val semanticType = impl?.getType()?.takeIf { it.isSemantic }
            ?: impl?.getBackgroundType()?.takeIf { it.isSemantic }
        if (semanticType != null) {
            if (impl?.isOutline() == true) {
                return if (darkTheme) RgbaColor(45, 45, 48) else RgbaColor(250, 250, 252)
            }
            return BootstrapColorPalette.resolveColor(semanticType, darkTheme)
        }
        return if (darkTheme) RgbaColor(45, 45, 48) else RgbaColor(250, 250, 252)
    }

    fun resolveControlForegroundColor(
        widget: DesktopNativeWidget?,
        darkTheme: Boolean = isWindowsSystemDarkTheme()
    ): RgbaColor {
        val impl = widget?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>
        impl?.getEffectiveTextColor()?.let { return it }
        impl?.resolveSemanticBootstrapForeground(darkTheme)?.let { return it }
        return if (darkTheme) RgbaColor(242, 242, 242) else RgbaColor(0, 0, 0)
    }

    internal fun drawButtonControl(
        hdc: HDC?,
        theme: SystemThemePalette = if (isWindowsSystemDarkTheme()) darkSystemPalette else lightSystemPalette,
        label: String,
        icon: String? = null,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        primary: Boolean,
        widget: DesktopNativeWidget? = null,
        tintOverride: UInt? = null,
        hwndForHover: HWND? = null,
        drawBackground: Boolean = true,
        drawForeground: Boolean = true,
        opacity: Float = 1f
    ) {
        val impl = widget?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>
        val isEnabled = (widget?.enabled ?: true) && (impl?.isEnabled() ?: true)
        val isHovered = isEnabled && ((widget != null && hoveredWidget == widget) || isRectHovered(hwndForHover, left, top, right, bottom))
        val isPressed = isEnabled && (widget != null && pressedWidget == widget)
        val isFocused = isEnabled && (widget != null && focusedWidget == widget)

        if (impl is DesktopStyledButtonNativeImpl<*>) {
            impl.setHoveredState(isHovered)
            impl.setFocusedState(isFocused)
            impl.setActivePressedState(isPressed)
            val visuals = impl.resolveCurrentVisuals()
            val radius = (visuals.borderRadiusPx ?: 6f).coerceAtLeast(0f)
            val borderW = (visuals.borderWidthPx ?: if (visuals.borderColor != null) 1f else 0f).coerceAtLeast(0f)
            val borderStyle = visuals.borderStyle
            val baseOpacity = ((visuals.opacity ?: widget?.opacity ?: 1f) * opacity).coerceIn(0f, 1f)
            val effectiveOpacity = if (!isEnabled && visuals.opacity == null) (baseOpacity * 0.45f).coerceIn(0f, 1f) else baseOpacity
            if (drawBackground) {
                val grad = visuals.backgroundGradient ?: impl.getEffectiveBackgroundGradient()
                val effectiveFillRgba: RgbaColor? = visuals.backgroundColor ?: when {
                    grad != null -> null
                    !isEnabled -> colorRefToRgba(theme.bgPanel, 180)
                    isPressed -> colorRefToRgba(lightenColorRef(theme.bgElevated, -14), 255)
                    isHovered -> colorRefToRgba(lightenColorRef(theme.bgElevated, 14), 255)
                    else -> colorRefToRgba(theme.bgCard, 255)
                }
                val effectiveBorderRgba: RgbaColor? = if (borderW <= 0f || borderStyle == CssBorderStyle.NONE || borderStyle == CssBorderStyle.HIDDEN) {
                    null
                } else {
                    visuals.borderColor ?: when {
                        !isEnabled -> colorRefToRgba(theme.borderSubtle, 140)
                        isFocused -> colorRefToRgba(theme.accentBlueBorder, 255)
                        isHovered -> colorRefToRgba(theme.borderStrong, 255)
                        else -> colorRefToRgba(theme.borderSubtle, 255)
                    }
                }

                if (borderStyle == CssBorderStyle.DASHED || borderStyle == CssBorderStyle.DOTTED) {
                    val bgColorRef = effectiveFillRgba?.let { rgbaToColorRef(it, theme.bgCard) } ?: theme.bgCard
                    val borderColorRef = effectiveBorderRgba?.let { rgbaToColorRef(it, theme.bgCard) } ?: theme.borderSubtle
                    drawDashedRoundedRect(hdc, left, top, right, bottom, radius.roundToInt(), bgColorRef, borderColorRef)
                } else {
                    drawAntialiasedRoundedRectRgba(
                        hdc = hdc,
                        left = left,
                        top = top,
                        right = right,
                        bottom = bottom,
                        radiusPx = radius,
                        fillColor = effectiveFillRgba,
                        borderColor = effectiveBorderRgba,
                        borderWidthPx = borderW,
                        opacity = effectiveOpacity,
                        gradient = grad
                    )
                }
                if (isFocused) {
                    val ringColor = visuals.focusRingColor ?: colorRefToRgba(theme.accentBlue, 255)
                    drawAntialiasedRoundedRectRgba(
                        hdc = hdc,
                        left = left + 1,
                        top = top + 1,
                        right = right - 1,
                        bottom = bottom - 1,
                        radiusPx = (radius - 1f).coerceAtLeast(0f),
                        fillColor = null,
                        borderColor = ringColor,
                        borderWidthPx = visuals.focusRingWidthPx ?: 1f,
                        opacity = effectiveOpacity
                    )
                }
            }
            if (!drawForeground) return
            val rawFgColorRef = visuals.textColor?.let { rgbaToColorRef(it) }
                ?: if (!isEnabled) theme.textMuted else theme.textPrimary
            val fgColorRef = if (effectiveOpacity < 0.98f) {
                val r = (rawFgColorRef and 0xFFu).toInt()
                val g = ((rawFgColorRef shr 8) and 0xFFu).toInt()
                val b = ((rawFgColorRef shr 16) and 0xFFu).toInt()
                rgb(
                    (r * effectiveOpacity).roundToInt().coerceIn(0, 255),
                    (g * effectiveOpacity).roundToInt().coerceIn(0, 255),
                    (b * effectiveOpacity).roundToInt().coerceIn(0, 255)
                )
            } else {
                rawFgColorRef
            }
            val pad = visuals.padding ?: impl.getEffectivePaddingInsets()
            val hasExplicitPad = pad.left > 0 || pad.right > 0 || pad.top > 0 || pad.bottom > 0
            val contentLeft = if (hasExplicitPad && label.isNotBlank()) (left + pad.left).coerceAtMost(right) else left
            val contentTop = (if (hasExplicitPad && label.isNotBlank()) (top + pad.top).coerceAtMost(bottom) else top) + (if (isPressed) 1 else 0)
            val contentRight = if (hasExplicitPad && label.isNotBlank()) (right - pad.right).coerceAtLeast(contentLeft) else right
            val contentBottom = if (hasExplicitPad && label.isNotBlank()) (bottom - pad.bottom).coerceAtLeast(contentTop) else bottom
            drawButtonLabelAndIcon(hdc, label, icon, contentLeft, contentTop, contentRight, contentBottom, fgColorRef)
            return
        }

        val semanticType = impl?.getType()?.takeIf { it.isSemantic }
            ?: impl?.getBackgroundType()?.takeIf { it.isSemantic }
        val isOutline = impl?.isOutline() == true
        val semanticColorRef = semanticType?.let {
            rgbaToColorRef(BootstrapColorPalette.resolveColor(it, theme.isDark))
        }
        val styleAccent = impl?.getComponentStyle()?.accentColor?.let { rgbaToColorRef(it) }
        val styleTint = styleAccent ?: semanticColorRef ?: tintOverride
        val styleBgColor = impl?.getEffectiveBackgroundColor()?.let { rgbaToColorRef(it) }
        val styleGradient = impl?.getEffectiveBackgroundGradient()
        val isNativeUnstyled = styleBgColor == null && styleTint == null && styleGradient == null && !primary

        if (isNativeUnstyled) {
            if (drawBackground) {
                val fallbackBg = when {
                    isPressed -> lightenColorRef(theme.bgCard, -18)
                    isHovered -> theme.bgElevated
                    else -> theme.bgCard
                }
                val fallbackBorder = when {
                    isFocused -> theme.accentBlueBorder
                    isHovered -> theme.borderStrong
                    else -> theme.borderSubtle
                }
                drawRoundedRect(hdc, left, top, right, bottom, 5, fallbackBg, fallbackBorder, opacity = opacity)
                if (!isPressed && right - left > 10 && bottom - top > 10 && opacity >= 0.98f) {
                    drawLineColor(hdc, left + 3, top + 1, right - 3, top + 1, lightenColorRef(fallbackBg, 14))
                }
                if (isFocused) {
                    drawRectBorder(hdc, left + 1, top + 1, right - 1, bottom - 1, theme.accentBlue)
                }
            }
            if (!drawForeground) return
            val rawNativeFg = impl?.getEffectiveTextColor()?.let { rgbaToColorRef(it) }
                ?: if (!isEnabled) theme.textMuted else theme.textPrimary
            val nativeFg = if (theme.isDark &&
                (rawNativeFg and 0xFFu) < 75u &&
                ((rawNativeFg shr 8) and 0xFFu) < 75u &&
                ((rawNativeFg shr 16) and 0xFFu) < 85u
            ) {
                if (!isEnabled) theme.textMuted else theme.textPrimary
            } else {
                rawNativeFg
            }
            drawButtonLabelAndIcon(hdc, label, icon, left, top + (if (isPressed) 1 else 0), right, bottom, nativeFg)
            return
        }

        val baseBg = when {
            styleBgColor != null -> styleBgColor
            isOutline && semanticColorRef != null && !isHovered && !isPressed -> theme.bgCard
            styleTint != null -> styleTint
            primary -> theme.accentBlue
            else -> theme.bgCard
        }
        val bg = when {
            isPressed -> lightenColorRef(if (isOutline && semanticColorRef != null) semanticColorRef else baseBg, -18)
            isHovered && isOutline && semanticColorRef != null -> semanticColorRef
            isHovered && (styleTint != null || styleBgColor != null || primary) -> lightenColorRef(baseBg, 22)
            isHovered -> theme.bgElevated
            else -> baseBg
        }
        val border = when {
            isFocused -> theme.accentBlueBorder
            isOutline && semanticColorRef != null -> {
                if (theme.isDark &&
                    (semanticColorRef and 0xFFu) < 75u &&
                    ((semanticColorRef shr 8) and 0xFFu) < 75u &&
                    ((semanticColorRef shr 16) and 0xFFu) < 85u
                ) {
                    theme.borderStrong
                } else {
                    semanticColorRef
                }
            }
            styleTint != null -> lightenColorRef(styleTint, 35)
            primary -> theme.accentBlueBorder
            isHovered -> theme.borderStrong
            else -> theme.borderSubtle
        }
        if (drawBackground) {
            drawRoundedRect(hdc, left, top, right, bottom, 5, bg, border, opacity = opacity)
            if (styleGradient != null && right - left > 4 && bottom - top > 4) {
                drawGradientRect(hdc, left + 1, top + 1, right - 1, bottom - 1, styleGradient, opacity = opacity)
                drawRectBorder(hdc, left, top, right, bottom, border)
            }
            // Native top specular bevel highlight (like macOS/Win11 native controls)
            if (!isPressed && right - left > 10 && bottom - top > 10 && opacity >= 0.98f) {
                val highlightColor = lightenColorRef(bg, if (primary || styleTint != null || styleGradient != null) 28 else 14)
                drawLineColor(hdc, left + 3, top + 1, right - 3, top + 1, highlightColor)
            }
            if (isFocused) {
                drawRectBorder(hdc, left + 1, top + 1, right - 1, bottom - 1, theme.accentBlue)
            }
        }
        if (!drawForeground) return
        val rawFg = impl?.getEffectiveTextColor()?.let { rgbaToColorRef(it) }
            ?: when {
                isOutline && semanticColorRef != null && !isHovered && !isPressed -> semanticColorRef
                semanticType != null -> rgbaToColorRef(BootstrapColorPalette.resolveForegroundOn(semanticType))
                primary || styleTint != null || styleGradient != null -> rgb(255, 255, 255)
                else -> theme.textPrimary
            }
        val isBgDark = (bg and 0xFFu) < 85u && ((bg shr 8) and 0xFFu) < 85u && ((bg shr 16) and 0xFFu) < 95u
        val isFgDark = (rawFg and 0xFFu) < 75u && ((rawFg shr 8) and 0xFFu) < 75u && ((rawFg shr 16) and 0xFFu) < 85u
        val fg = if (isBgDark && isFgDark) {
            if (!isEnabled) theme.textMuted else theme.textPrimary
        } else {
            rawFg
        }
        drawButtonLabelAndIcon(hdc, label, icon, left, top + (if (isPressed) 1 else 0), right, bottom, fg)
    }

    private fun drawButtonLabelAndIcon(
        hdc: HDC?,
        label: String,
        icon: String?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        fg: UInt
    ) {
        val hasIcon = !icon.isNullOrBlank()
        val hasText = label.isNotBlank()
        if (hasIcon && !hasText) {
            val pad = min(6, max(3, min(right - left, bottom - top) / 5))
            drawIconGlyphOrVector(hdc, icon!!, left + pad, top + pad, right - pad, bottom - pad, fg)
        } else if (hasIcon && hasText) {
            val iconSize = min(16, max(12, (bottom - top) - 8))
            val iconLeft = left + 6
            val iconTop = top + ((bottom - top) - iconSize) / 2
            drawIconGlyphOrVector(hdc, icon!!, iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize, fg)
            drawTextInRect(hdc, label, iconLeft + iconSize + 5, top + 2, right - 6, bottom - 2, fg, center = false)
        } else {
            drawTextInRect(hdc, label, left + 6, top + 2, right - 6, bottom - 2, fg, center = true)
        }
    }

    private val cachedDrawableResources = HashMap<String, com.nexustools.genericui.res.DrawableResource?>(128)
    private val SVG_PATH_TOKEN_REGEX = Regex("([MmLlHhVvCcSsQqTtAaZz])|(-?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?)")
    private val cachedSvgPathTokens = HashMap<String, List<String>>(128)

    /**
     * Renders crisp GDI vector/glyph icons inside `[left..right, top..bottom]` for
     * viewport tools, action buttons, and SVG/Unicode icon identifiers.
     */
    private fun drawIconGlyphOrVector(
        hdc: HDC?,
        iconSpec: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        colorRef: UInt
    ) {
        if (iconSpec.length in 1..3 &&
            iconSpec != "▭" &&
            iconSpec != "⚪" &&
            iconSpec != "⬭" &&
            iconSpec != "⬡" &&
            iconSpec != "⭐" &&
            iconSpec != "╱" &&
            !iconSpec.equals("add", ignoreCase = true)
        ) {
            drawTextInRect(hdc, iconSpec, left - 2, top - 2, right + 2, bottom + 2, colorRef, center = true)
            return
        }
        val resolvedDrawable = if (cachedDrawableResources.containsKey(iconSpec)) {
            cachedDrawableResources[iconSpec]
        } else {
            Resources.getDrawable(iconSpec).also { cachedDrawableResources[iconSpec] = it }
        }
        if (resolvedDrawable != null && resolvedDrawable.svgPathDataList.isNotEmpty()) {
            val pen = CreatePen(PS_SOLID, 2, colorRef)
            val oldPen = SelectObject(hdc, pen)
            val nullBrush = GetStockObject(NULL_BRUSH)
            val oldBrush = SelectObject(hdc, nullBrush)
            val vbW = resolvedDrawable.viewBoxWidth.coerceAtLeast(1f)
            val vbH = resolvedDrawable.viewBoxHeight.coerceAtLeast(1f)
            val boxW = max(1, right - left).toFloat()
            val boxH = max(1, bottom - top).toFloat()
            for (pathD in resolvedDrawable.svgPathDataList) {
                renderSvgPathGdi(hdc, pathD, left, top, boxW / vbW, boxH / vbH)
            }
            SelectObject(hdc, oldBrush)
            SelectObject(hdc, oldPen)
            DeleteObject(pen)
            return
        }

        val key = (resolvedDrawable?.resId?.name ?: iconSpec)
            .trim()
            .lowercase()
            .removePrefix("res://drawable/")
            .removePrefix("@drawable/")
            .removePrefix("ic_tool_")
            .removePrefix("ic_")
            .replace('_', '-')
        val cx = (left + right) / 2
        val cy = (top + bottom) / 2
        val pen = CreatePen(PS_SOLID, 2, colorRef)
        val oldPen = SelectObject(hdc, pen)
        val nullBrush = GetStockObject(NULL_BRUSH)
        val oldBrush = SelectObject(hdc, nullBrush)

        when {
            key == "move" || key.contains("move") -> {
                MoveToEx(hdc, cx, top + 1, null); LineTo(hdc, cx, bottom - 1)
                MoveToEx(hdc, left + 1, cy, null); LineTo(hdc, right - 1, cy)
                MoveToEx(hdc, cx - 3, top + 4, null); LineTo(hdc, cx, top + 1); LineTo(hdc, cx + 3, top + 4)
                MoveToEx(hdc, cx - 3, bottom - 4, null); LineTo(hdc, cx, bottom - 1); LineTo(hdc, cx + 3, bottom - 4)
            }
            key == "rect-select" || key.contains("rect-select") -> {
                val dashPen = CreatePen(PS_DOT, 1, colorRef)
                SelectObject(hdc, dashPen)
                Rectangle(hdc, left + 1, top + 2, right - 1, bottom - 2)
                SelectObject(hdc, pen)
                DeleteObject(dashPen)
            }
            key == "ellipse-select" || key.contains("ellipse-select") -> {
                val dashPen = CreatePen(PS_DOT, 1, colorRef)
                SelectObject(hdc, dashPen)
                Ellipse(hdc, left + 1, top + 2, right - 1, bottom - 2)
                SelectObject(hdc, pen)
                DeleteObject(dashPen)
            }
            key == "lasso-select" || key.contains("lasso") -> {
                Ellipse(hdc, left + 2, top + 2, right - 2, bottom - 5)
                MoveToEx(hdc, cx + 2, bottom - 6, null)
                LineTo(hdc, left + 3, bottom - 1)
            }
            key == "magic-wand" || key.contains("wand") -> {
                MoveToEx(hdc, left + 2, bottom - 2, null); LineTo(hdc, right - 4, top + 4)
                MoveToEx(hdc, right - 4, top + 1, null); LineTo(hdc, right - 4, top + 7)
                MoveToEx(hdc, right - 7, top + 4, null); LineTo(hdc, right - 1, top + 4)
            }
            key == "crop" || key.contains("crop") -> {
                MoveToEx(hdc, left + 4, top + 1, null); LineTo(hdc, left + 4, bottom - 4); LineTo(hdc, right - 1, bottom - 4)
                MoveToEx(hdc, left + 1, top + 4, null); LineTo(hdc, right - 4, top + 4); LineTo(hdc, right - 4, bottom - 1)
            }
            key == "brush" || key.contains("brush") -> {
                MoveToEx(hdc, right - 2, top + 2, null); LineTo(hdc, cx - 1, cy + 1)
                Ellipse(hdc, left + 2, cy, cx + 2, bottom - 1)
            }
            key == "pencil" || key.contains("pencil") -> {
                MoveToEx(hdc, left + 2, bottom - 2, null)
                LineTo(hdc, right - 3, top + 3)
                MoveToEx(hdc, left + 2, bottom - 2, null)
                LineTo(hdc, left + 6, bottom - 2)
            }
            key == "airbrush" || key.contains("airbrush") -> {
                Ellipse(hdc, cx - 2, cy - 2, cx + 3, cy + 3)
                SetPixel(hdc, left + 3, top + 4, colorRef)
                SetPixel(hdc, left + 6, top + 2, colorRef)
                SetPixel(hdc, left + 3, cy, colorRef)
                MoveToEx(hdc, cx + 2, cy + 2, null); LineTo(hdc, right - 2, bottom - 2)
            }
            key == "eraser" || key.contains("eraser") -> {
                Rectangle(hdc, left + 2, top + 4, right - 2, bottom - 3)
                MoveToEx(hdc, left + 2, bottom - 1, null); LineTo(hdc, right - 1, bottom - 1)
            }
            key == "bucket-fill" || key.contains("bucket") || key.contains("fill") -> {
                Rectangle(hdc, left + 3, top + 4, right - 4, bottom - 3)
                MoveToEx(hdc, right - 4, cy, null); LineTo(hdc, right - 1, bottom - 2)
            }
            key == "gradient" || key.contains("gradient") -> {
                Rectangle(hdc, left + 1, top + 2, right - 1, bottom - 2)
                MoveToEx(hdc, left + 3, bottom - 4, null); LineTo(hdc, right - 3, top + 4)
            }
            key == "eyedropper" || key.contains("pipette") || key.contains("picker") -> {
                MoveToEx(hdc, left + 2, bottom - 2, null); LineTo(hdc, right - 3, top + 3)
                Ellipse(hdc, right - 6, top + 1, right - 1, top + 6)
            }
            key == "text" || key == "type" || key == "vector-text" -> {
                MoveToEx(hdc, left + 3, top + 3, null); LineTo(hdc, right - 3, top + 3)
                MoveToEx(hdc, cx, top + 3, null); LineTo(hdc, cx, bottom - 2)
                MoveToEx(hdc, cx - 3, bottom - 2, null); LineTo(hdc, cx + 4, bottom - 2)
            }
            key == "vector-rect" || key == "rect-shape" || key == "▭" -> {
                Rectangle(hdc, left + 2, top + 3, right - 2, bottom - 3)
            }
            key == "vector-circle" || key == "circle-shape" || key == "⚪" -> {
                Ellipse(hdc, left + 2, top + 2, right - 2, bottom - 2)
            }
            key == "vector-ellipse" || key == "ellipse-shape" || key == "⬭" -> {
                Ellipse(hdc, left + 1, top + 4, right - 1, bottom - 4)
            }
            key == "vector-polygon" || key == "polygon-shape" || key == "⬡" -> {
                MoveToEx(hdc, cx, top + 1, null)
                LineTo(hdc, right - 2, top + 5)
                LineTo(hdc, right - 2, bottom - 5)
                LineTo(hdc, cx, bottom - 1)
                LineTo(hdc, left + 2, bottom - 5)
                LineTo(hdc, left + 2, top + 5)
                LineTo(hdc, cx, top + 1)
            }
            key == "vector-star" || key == "star-shape" || key == "⭐" -> {
                MoveToEx(hdc, cx, top + 1, null)
                LineTo(hdc, cx + 3, cy - 2)
                LineTo(hdc, right - 1, cy - 2)
                LineTo(hdc, cx + 4, cy + 2)
                LineTo(hdc, right - 3, bottom - 1)
                LineTo(hdc, cx, cy + 5)
                LineTo(hdc, left + 3, bottom - 1)
                LineTo(hdc, cx - 4, cy + 2)
                LineTo(hdc, left + 1, cy - 2)
                LineTo(hdc, cx - 3, cy - 2)
                LineTo(hdc, cx, top + 1)
            }
            key == "vector-line" || key == "line-shape" || key == "╱" -> {
                MoveToEx(hdc, left + 3, bottom - 3, null)
                LineTo(hdc, right - 3, top + 3)
                Rectangle(hdc, left + 1, bottom - 5, left + 5, bottom - 1)
                Rectangle(hdc, right - 5, top + 1, right - 1, top + 5)
            }
            key == "shape" || key.contains("shape") || key.contains("polygon") -> {
                Rectangle(hdc, left + 2, top + 2, cx + 2, cy + 2)
                Ellipse(hdc, cx - 2, cy - 2, right - 1, bottom - 1)
            }
            key == "bezier-pen" || key == "vector-path" || key.contains("bezier") || key.contains("pen") -> {
                MoveToEx(hdc, left + 2, bottom - 2, null)
                LineTo(hdc, cx, top + 2)
                LineTo(hdc, right - 2, bottom - 2)
                Ellipse(hdc, cx - 2, cy - 1, cx + 2, cy + 3)
            }
            key == "node-edit" || key == "vector-deform" || key.contains("node") || key.contains("vertex") || key.contains("deform") -> {
                MoveToEx(hdc, left + 2, bottom - 3, null)
                LineTo(hdc, cx, top + 4)
                LineTo(hdc, right - 2, bottom - 3)
                Rectangle(hdc, left + 1, bottom - 5, left + 5, bottom - 1)
                Rectangle(hdc, cx - 2, top + 2, cx + 2, top + 6)
                Rectangle(hdc, right - 5, bottom - 5, right - 1, bottom - 1)
            }
            key == "swap" || key.contains("swap") -> {
                MoveToEx(hdc, left + 2, top + 4, null); LineTo(hdc, right - 2, top + 4)
                MoveToEx(hdc, right - 5, top + 1, null); LineTo(hdc, right - 2, top + 4); LineTo(hdc, right - 5, top + 7)
                MoveToEx(hdc, right - 2, bottom - 4, null); LineTo(hdc, left + 2, bottom - 4)
                MoveToEx(hdc, left + 5, bottom - 7, null); LineTo(hdc, left + 2, bottom - 4); LineTo(hdc, left + 5, bottom - 1)
            }
            key == "play" -> {
                MoveToEx(hdc, left + 3, top + 2, null)
                LineTo(hdc, right - 2, cy)
                LineTo(hdc, left + 3, bottom - 2)
                LineTo(hdc, left + 3, top + 2)
            }
            key == "pause" -> {
                MoveToEx(hdc, cx - 3, top + 2, null); LineTo(hdc, cx - 3, bottom - 2)
                MoveToEx(hdc, cx + 3, top + 2, null); LineTo(hdc, cx + 3, bottom - 2)
            }
            key == "stop" -> {
                Rectangle(hdc, left + 3, top + 3, right - 3, bottom - 3)
            }
            key == "plus" || key == "add" -> {
                MoveToEx(hdc, cx, top + 2, null); LineTo(hdc, cx, bottom - 2)
                MoveToEx(hdc, left + 2, cy, null); LineTo(hdc, right - 2, cy)
            }
            key == "trash" || key == "delete" || key == "remove" -> {
                MoveToEx(hdc, left + 2, top + 4, null); LineTo(hdc, right - 2, top + 4)
                Rectangle(hdc, left + 4, top + 4, right - 4, bottom - 2)
            }
            else -> {
                val glyph = if (iconSpec.length <= 3) iconSpec else "◈"
                drawTextInRect(hdc, glyph, left - 2, top - 2, right + 2, bottom + 2, colorRef, center = true)
            }
        }

        SelectObject(hdc, oldBrush)
        SelectObject(hdc, oldPen)
        DeleteObject(pen)
        markGdiRectDirty(left - 2, top - 2, right + 2, bottom + 2)
    }

    /**
     * Lightweight SVG `<path d="...">` command renderer (`M`, `L`, `H`, `V`, `C`, `Q`, `Z`) using Win32 GDI.
     */
    private fun renderSvgPathGdi(
        hdc: HDC?,
        pathD: String,
        originX: Int,
        originY: Int,
        scaleX: Float,
        scaleY: Float
    ) {
        val tokens = cachedSvgPathTokens.getOrPut(pathD) {
            SVG_PATH_TOKEN_REGEX.findAll(pathD).map { it.value }.toList()
        }
        var idx = 0
        var cmd = 'M'
        var curX = 0f
        var curY = 0f
        var startX = 0f
        var startY = 0f

        fun toWinX(x: Float): Int = originX + (x * scaleX).roundToInt()
        fun toWinY(y: Float): Int = originY + (y * scaleY).roundToInt()

        while (idx < tokens.size) {
            val t = tokens[idx]
            val firstChar = t[0]
            if (firstChar.isLetter()) {
                cmd = firstChar
                idx++
                if (cmd == 'Z' || cmd == 'z') {
                    LineTo(hdc, toWinX(startX), toWinY(startY))
                    curX = startX
                    curY = startY
                    continue
                }
                if (idx >= tokens.size) break
            }
            fun nextFloat(): Float = if (idx < tokens.size) tokens[idx++].toFloatOrNull() ?: 0f else 0f

            when (cmd) {
                'M' -> {
                    curX = nextFloat()
                    curY = nextFloat()
                    startX = curX
                    startY = curY
                    MoveToEx(hdc, toWinX(curX), toWinY(curY), null)
                    cmd = 'L'
                }
                'm' -> {
                    curX += nextFloat()
                    curY += nextFloat()
                    startX = curX
                    startY = curY
                    MoveToEx(hdc, toWinX(curX), toWinY(curY), null)
                    cmd = 'l'
                }
                'L', 'T' -> {
                    curX = nextFloat()
                    curY = nextFloat()
                    LineTo(hdc, toWinX(curX), toWinY(curY))
                }
                'l', 't' -> {
                    curX += nextFloat()
                    curY += nextFloat()
                    LineTo(hdc, toWinX(curX), toWinY(curY))
                }
                'H' -> {
                    curX = nextFloat()
                    LineTo(hdc, toWinX(curX), toWinY(curY))
                }
                'h' -> {
                    curX += nextFloat()
                    LineTo(hdc, toWinX(curX), toWinY(curY))
                }
                'V' -> {
                    curY = nextFloat()
                    LineTo(hdc, toWinX(curX), toWinY(curY))
                }
                'v' -> {
                    curY += nextFloat()
                    LineTo(hdc, toWinX(curX), toWinY(curY))
                }
                'C' -> {
                    val x1 = nextFloat(); val y1 = nextFloat()
                    val x2 = nextFloat(); val y2 = nextFloat()
                    val x = nextFloat(); val y = nextFloat()
                    val midX = (curX + 3f * x1 + 3f * x2 + x) / 8f
                    val midY = (curY + 3f * y1 + 3f * y2 + y) / 8f
                    LineTo(hdc, toWinX(midX), toWinY(midY))
                    curX = x; curY = y
                    LineTo(hdc, toWinX(curX), toWinY(curY))
                }
                'c' -> {
                    val x1 = curX + nextFloat(); val y1 = curY + nextFloat()
                    val x2 = curX + nextFloat(); val y2 = curY + nextFloat()
                    val x = curX + nextFloat(); val y = curY + nextFloat()
                    val midX = (curX + 3f * x1 + 3f * x2 + x) / 8f
                    val midY = (curY + 3f * y1 + 3f * y2 + y) / 8f
                    LineTo(hdc, toWinX(midX), toWinY(midY))
                    curX = x; curY = y
                    LineTo(hdc, toWinX(curX), toWinY(curY))
                }
                'S', 'Q' -> {
                    val x1 = nextFloat(); val y1 = nextFloat()
                    val x = nextFloat(); val y = nextFloat()
                    val midX = (curX + 2f * x1 + x) / 4f
                    val midY = (curY + 2f * y1 + y) / 4f
                    LineTo(hdc, toWinX(midX), toWinY(midY))
                    curX = x; curY = y
                    LineTo(hdc, toWinX(curX), toWinY(curY))
                }
                's', 'q' -> {
                    val x1 = curX + nextFloat(); val y1 = curY + nextFloat()
                    val x = curX + nextFloat(); val y = curY + nextFloat()
                    val midX = (curX + 2f * x1 + x) / 4f
                    val midY = (curY + 2f * y1 + y) / 4f
                    LineTo(hdc, toWinX(midX), toWinY(midY))
                    curX = x; curY = y
                    LineTo(hdc, toWinX(curX), toWinY(curY))
                }
                'A' -> {
                    nextFloat(); nextFloat(); nextFloat(); nextFloat(); nextFloat()
                    val x = nextFloat(); val y = nextFloat()
                    curX = x; curY = y
                    LineTo(hdc, toWinX(curX), toWinY(curY))
                }
                'a' -> {
                    nextFloat(); nextFloat(); nextFloat(); nextFloat(); nextFloat()
                    val dx = nextFloat(); val dy = nextFloat()
                    curX += dx; curY += dy
                    LineTo(hdc, toWinX(curX), toWinY(curY))
                }
                else -> idx++
            }
        }
    }

    internal fun drawCheckboxControl(
        hdc: HDC?,
        theme: SystemThemePalette,
        label: String,
        checked: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        widget: DesktopNativeWidget? = null,
        hwndForHover: HWND? = null
    ) {
        val boxSize = 14
        val boxTop = top + ((bottom - top) - boxSize) / 2
        val impl = widget?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>
        val tintColor = impl?.getEffectiveAccentColor(theme.isDark)?.let { rgbaToColorRef(it) }
        val hovered = (widget != null && hoveredWidget == widget) || isRectHovered(hwndForHover, left, top, right, bottom)
        val focused = widget != null && focusedWidget == widget
        val boxBg = when {
            checked && tintColor != null -> tintColor
            checked -> theme.accentBlue
            hovered -> theme.bgElevated
            else -> theme.bgCard
        }
        val boxBorder = when {
            focused -> theme.accentBlueBorder
            hovered -> theme.borderStrong
            checked -> theme.accentBlueBorder
            else -> theme.borderSubtle
        }
        fillRectColor(hdc, left, boxTop, left + boxSize, boxTop + boxSize, boxBg)
        drawRectBorder(hdc, left, boxTop, left + boxSize, boxTop + boxSize, boxBorder)
        if (checked) {
            drawTextInRect(hdc, "✓", left, boxTop - 1, left + boxSize, boxTop + boxSize, rgb(255, 255, 255), center = true)
        }
        drawTextInRect(hdc, label, left + boxSize + 6, top, right, bottom, theme.textPrimary)
    }

    internal fun drawSelectControl(
        hdc: HDC?,
        theme: SystemThemePalette,
        label: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        widget: DesktopNativeWidget? = null,
        hwndForHover: HWND? = null,
        drawBackground: Boolean = true,
        drawForeground: Boolean = true,
        opacity: Float = 1f
    ) {
        val impl = widget?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>
        val tintColor = impl?.getEffectiveAccentColor(theme.isDark)?.let { rgbaToColorRef(it) }
        val hovered = (widget != null && hoveredWidget == widget) || isRectHovered(hwndForHover, left, top, right, bottom)
        val focused = widget != null && focusedWidget == widget
        if (drawBackground) {
            val bg = when {
                tintColor != null -> tintColor
                hovered -> theme.bgElevated
                else -> theme.bgCard
            }
            val border = when {
                focused -> theme.accentBlueBorder
                hovered -> theme.borderStrong
                else -> theme.borderSubtle
            }
            drawRoundedRect(hdc, left, top, right, bottom, 4, bg, border, opacity = opacity)
        }
        if (drawForeground) {
            drawTextInRect(hdc, label, left + 8, top + 2, right - 22, bottom - 2, theme.textPrimary)
            drawTextInRect(hdc, "▼", right - 20, top + 2, right - 4, bottom - 2, theme.textSecondary, center = true)
        }
    }

    internal fun blendRgbaToBackBuffer(
        hdc: HDC?,
        dstL: Int,
        dstT: Int,
        dstW: Int,
        dstH: Int,
        rgba: ByteArray,
        opacity: Float = 1f
    ): Boolean {
        if (dstW <= 0 || dstH <= 0 || rgba.size < dstW * dstH * 4) return false
        val effOpacity = opacity.coerceIn(0f, 1f)
        if (effOpacity <= 0.001f) return true

        val isActiveDc = (hdc == null || hdc == activeBackBufferDc) && activeBackBufferPixels != null
        val bufPixels = if (isActiveDc) activeBackBufferPixels else cachedMainBackBufferPixels
        val bufW = if (isActiveDc) activeBackBufferWidth else cachedMainBackBufferWidth
        val bufH = if (isActiveDc) activeBackBufferHeight else cachedMainBackBufferHeight
        if (bufPixels == null || bufW <= 0 || bufH <= 0) return false

        val clipL: Int
        val clipT: Int
        val clipR: Int
        val clipB: Int
        if (softClipDepth > 0) {
            clipL = softClipLeft.coerceIn(0, bufW)
            clipT = softClipTop.coerceIn(0, bufH)
            clipR = softClipRight.coerceIn(clipL, bufW)
            clipB = softClipBottom.coerceIn(clipT, bufH)
        } else {
            clipL = 0
            clipT = 0
            clipR = bufW
            clipB = bufH
        }

        val x0 = max(dstL, clipL)
        val y0 = max(dstT, clipT)
        val x1 = min(dstL + dstW, clipR)
        val y1 = min(dstT + dstH, clipB)
        if (x1 <= x0 || y1 <= y0) return true

        flushGdiIfOverlapping(x0, y0, x1, y1)
        markFrameBufferDirty(x0, y0, x1, y1)

        val wordsPtr = bufPixels.reinterpret<UIntVar>()
        val op255 = (effOpacity * 255f).roundToInt().coerceIn(0, 255)
        for (py in y0 until y1) {
            val sy = py - dstT
            val dstRowOff = py * bufW
            val srcRowOff = sy * dstW * 4
            for (px in x0 until x1) {
                val sx = px - dstL
                val sIdx = srcRowOff + sx * 4
                val rawA = rgba[sIdx + 3].toInt() and 0xFF
                if (rawA <= 0) continue
                val a = (rawA * op255 + 127) / 255
                if (a <= 0) continue
                val r = rgba[sIdx].toInt() and 0xFF
                val g = rgba[sIdx + 1].toInt() and 0xFF
                val b = rgba[sIdx + 2].toInt() and 0xFF
                val wIdx = dstRowOff + px
                if (a >= 255) {
                    wordsPtr[wIdx] = 0xFF000000u or (r.toUInt() shl 16) or (g.toUInt() shl 8) or b.toUInt()
                } else {
                    val dstWord = wordsPtr[wIdx].toInt()
                    val dstB = dstWord and 0xFF
                    val dstG = (dstWord ushr 8) and 0xFF
                    val dstR = (dstWord ushr 16) and 0xFF
                    val invA = 255 - a
                    val outB = ((b * a + dstB * invA + 127) / 255).coerceIn(0, 255)
                    val outG = ((g * a + dstG * invA + 127) / 255).coerceIn(0, 255)
                    val outR = ((r * a + dstR * invA + 127) / 255).coerceIn(0, 255)
                    wordsPtr[wIdx] = 0xFF000000u or (outR.toUInt() shl 16) or (outG.toUInt() shl 8) or outB.toUInt()
                }
            }
        }
        return true
    }

    internal fun drawSliderControl(
        hdc: HDC?,
        theme: SystemThemePalette,
        slider: DesktopRangeSliderNativeImpl<*>,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        opacity: Float = 1f
    ) {
        if (right <= left + 4 || bottom <= top + 2) return
        val effOpacity = opacity.coerceIn(0f, 1f)
        if (effOpacity <= 0.001f) return
        val midY = (top + bottom) / 2
        val thumbR = min(6, max(4, (bottom - top) / 2 - 1))
        val trackLeft = min(right - 2, left + thumbR)
        val trackRight = max(trackLeft + 2, right - thumbR)
        val tintColor = slider.getEffectiveAccentColor(theme.isDark)?.let { rgbaToColorRef(it) }
            ?: (slider.nativeWidget.attributes["accentColor"] as? String)?.let { RgbaColor.fromCss(it) }?.let { rgbaToColorRef(it) }
        val thumbAttrColor = (slider.nativeWidget.attributes["thumbColor"] as? String)?.let { RgbaColor.fromCss(it) }?.let { rgbaToColorRef(it) }
        val hovered = hoveredWidget == slider.nativeWidget
        val activeColor = tintColor ?: theme.accentBlue
        val baseThumbColor = thumbAttrColor ?: rgb(242, 245, 250)
        val thumbColor = if (hovered) rgb(255, 255, 255) else baseThumbColor
        if (effOpacity >= 0.99f) {
            fillRectColor(hdc, trackLeft, midY - 2, trackRight, midY + 2, theme.bgElevated)
        } else {
            drawAntialiasedRoundedRectRgba(
                hdc = hdc,
                left = trackLeft,
                top = midY - 2,
                right = trackRight,
                bottom = midY + 2,
                radiusPx = 2f,
                fillColor = colorRefToRgba(theme.bgElevated, 255),
                borderColor = null,
                borderWidthPx = 0f,
                opacity = effOpacity
            )
        }
        val span = (slider.getMax() - slider.getMin()).coerceAtLeast(0.0001)
        val frac = ((slider.getValue() - slider.getMin()) / span).coerceIn(0.0, 1.0)
        val bufferedFrac = (slider.nativeWidget.attributes["bufferedFraction"] as? String)?.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.0
        if (bufferedFrac > frac) {
            val bufferedX = trackLeft + ((trackRight - trackLeft) * bufferedFrac).roundToInt()
            val bufferedColorRef = lightenColorRef(theme.bgElevated, 42)
            if (effOpacity >= 0.99f) {
                fillRectColor(hdc, trackLeft, midY - 2, bufferedX, midY + 2, bufferedColorRef)
            } else {
                drawAntialiasedRoundedRectRgba(
                    hdc = hdc,
                    left = trackLeft,
                    top = midY - 2,
                    right = bufferedX,
                    bottom = midY + 2,
                    radiusPx = 2f,
                    fillColor = colorRefToRgba(bufferedColorRef, 255),
                    borderColor = null,
                    borderWidthPx = 0f,
                    opacity = effOpacity
                )
            }
        }
        val fillX = trackLeft + ((trackRight - trackLeft) * frac).roundToInt()
        if (fillX > trackLeft) {
            if (effOpacity >= 0.99f) {
                fillRectColor(hdc, trackLeft, midY - 2, fillX, midY + 2, activeColor)
            } else {
                drawAntialiasedRoundedRectRgba(
                    hdc = hdc,
                    left = trackLeft,
                    top = midY - 2,
                    right = fillX,
                    bottom = midY + 2,
                    radiusPx = 2f,
                    fillColor = colorRefToRgba(activeColor, 255),
                    borderColor = null,
                    borderWidthPx = 0f,
                    opacity = effOpacity
                )
            }
        }
        drawAntialiasedRoundedRectRgba(
            hdc = hdc,
            left = fillX - thumbR,
            top = midY - thumbR,
            right = fillX + thumbR,
            bottom = midY + thumbR,
            radiusPx = thumbR.toFloat(),
            fillColor = colorRefToRgba(thumbColor, 255),
            borderColor = colorRefToRgba(activeColor, 255),
            borderWidthPx = 1.5f,
            opacity = effOpacity
        )
    }

    internal fun fillRectColor(hdc: HDC?, left: Int, top: Int, right: Int, bottom: Int, colorRef: UInt) {
        if (right <= left || bottom <= top) return
        val isActiveDc = hdc != null && hdc == activeBackBufferDc && activeBackBufferPixels != null
        val bufPixels = if (isActiveDc) activeBackBufferPixels else null
        val bufW = activeBackBufferWidth
        val bufH = activeBackBufferHeight
        if (bufPixels != null && bufW > 0 && bufH > 0) {
            val clipL = if (softClipDepth > 0) softClipLeft.coerceIn(0, bufW) else 0
            val clipT = if (softClipDepth > 0) softClipTop.coerceIn(0, bufH) else 0
            val clipR = if (softClipDepth > 0) softClipRight.coerceIn(clipL, bufW) else bufW
            val clipB = if (softClipDepth > 0) softClipBottom.coerceIn(clipT, bufH) else bufH
            val x0 = max(left, clipL)
            val y0 = max(top, clipT)
            val x1 = min(right, clipR)
            val y1 = min(bottom, clipB)
            if (x1 <= x0 || y1 <= y0) return
            flushGdiIfOverlapping(x0, y0, x1, y1)
            markFrameBufferDirty(x0, y0, x1, y1)
            val spanWords = x1 - x0
            val packedBgra = colorRefToBgraWord(colorRef)
            val firstRowWordIdx = y0 * bufW + x0
            fillBackBufferSpanWords(bufPixels, firstRowWordIdx, spanWords, packedBgra)
            if (y1 - y0 > 1) {
                val firstRowPtr = (bufPixels + (firstRowWordIdx * 4))!!
                val rowBytes = (spanWords * 4).toULong()
                for (py in (y0 + 1) until y1) {
                    platform.posix.memcpy(
                        bufPixels + ((py * bufW + x0) * 4),
                        firstRowPtr,
                        rowBytes
                    )
                }
            }
            return
        }
        sharedScratchRect.left = left
        sharedScratchRect.top = top
        sharedScratchRect.right = right
        sharedScratchRect.bottom = bottom
        val brush = CreateSolidBrush(colorRef)
        FillRect(hdc, sharedScratchRect.ptr, brush)
        DeleteObject(brush)
        markGdiRectDirty(left, top, right, bottom)
    }

    internal fun drawRectBorder(hdc: HDC?, left: Int, top: Int, right: Int, bottom: Int, colorRef: UInt) {
        if (right <= left || bottom <= top) return
        if (hdc != null && hdc == activeBackBufferDc && activeBackBufferPixels != null) {
            fillRectColor(hdc, left, top, right, top + 1, colorRef)
            fillRectColor(hdc, left, bottom - 1, right, bottom, colorRef)
            if (bottom - top > 2) {
                fillRectColor(hdc, left, top + 1, left + 1, bottom - 1, colorRef)
                fillRectColor(hdc, right - 1, top + 1, right, bottom - 1, colorRef)
            }
            return
        }
        sharedScratchRect.left = left
        sharedScratchRect.top = top
        sharedScratchRect.right = right
        sharedScratchRect.bottom = bottom
        val brush = CreateSolidBrush(colorRef)
        FrameRect(hdc, sharedScratchRect.ptr, brush)
        DeleteObject(brush)
        markGdiRectDirty(left, top, right, bottom)
    }

    internal fun drawLineColor(hdc: HDC?, x0: Int, y0: Int, x1: Int, y1: Int, colorRef: UInt) {
        if (hdc != null && hdc == activeBackBufferDc && activeBackBufferPixels != null) {
            if (y0 == y1) {
                val l = min(x0, x1)
                val r = max(x0, x1)
                if (r > l) {
                    fillRectColor(hdc, l, y0, r, y0 + 1, colorRef)
                }
                return
            } else if (x0 == x1) {
                val t = min(y0, y1)
                val b = max(y0, y1)
                if (b > t) {
                    fillRectColor(hdc, x0, t, x0 + 1, b, colorRef)
                }
                return
            }
        }
        val pen = CreatePen(PS_SOLID, 1, colorRef)
        val oldPen = SelectObject(hdc, pen)
        MoveToEx(hdc, x0, y0, null)
        LineTo(hdc, x1, y1)
        SelectObject(hdc, oldPen)
        DeleteObject(pen)
        markGdiRectDirty(min(x0, x1), min(y0, y1), max(x0, x1) + 1, max(y0, y1) + 1)
    }

    internal fun drawTextInRect(
        hdc: HDC?,
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        colorRef: UInt,
        center: Boolean = false,
        alignRight: Boolean = false
    ) {
        if (text.isEmpty() || right <= left || bottom <= top) return
        sharedScratchRect.left = left
        sharedScratchRect.top = top
        sharedScratchRect.right = right
        sharedScratchRect.bottom = bottom
        SetTextColor(hdc, colorRef)
        val horizFlag = when {
            center -> DT_CENTER
            alignRight -> DT_RIGHT
            else -> DT_LEFT
        }
        val ellipsisFlag = if (text.length * 10 > (right - left)) DT_END_ELLIPSIS else 0
        val flags = (DT_SINGLELINE or DT_VCENTER or horizFlag or ellipsisFlag or DT_NOPREFIX).toUInt()
        DrawTextW(hdc, text, -1, sharedScratchRect.ptr, flags)
        markGdiRectDirty(left, top, right, bottom)
    }

    internal fun drawMultilineTextInRect(
        hdc: HDC?,
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        colorRef: UInt,
        center: Boolean = false,
        alignRight: Boolean = false
    ) {
        if (text.isEmpty() || right <= left || bottom <= top) return
        sharedScratchRect.left = left
        sharedScratchRect.top = top
        sharedScratchRect.right = right
        sharedScratchRect.bottom = bottom
        SetTextColor(hdc, colorRef)
        val horizFlag = when {
            center -> DT_CENTER
            alignRight -> DT_RIGHT
            else -> DT_LEFT
        }
        val flags = (DT_WORDBREAK or horizFlag or DT_END_ELLIPSIS or DT_NOPREFIX).toUInt()
        DrawTextW(hdc, text, -1, sharedScratchRect.ptr, flags)
        markGdiRectDirty(left, top, right, bottom)
    }

    internal fun measureMultilineTextHeight(
        hdc: HDC?,
        text: String,
        maxWidth: Int,
        lineHeightEstimate: Int = 20
    ): Int {
        if (text.isEmpty() || maxWidth <= 8) return lineHeightEstimate
        if (hdc == null) {
            val explicitLines = text.split('\n')
            val wrappedCount = explicitLines.sumOf { line ->
                max(1, ((line.length * 7) + maxWidth - 1) / maxWidth.coerceAtLeast(40))
            }
            return (wrappedCount * lineHeightEstimate + 4).coerceAtLeast(lineHeightEstimate)
        }
        sharedScratchRect.left = 0
        sharedScratchRect.top = 0
        sharedScratchRect.right = maxWidth
        sharedScratchRect.bottom = 2000
        val flags = (DT_WORDBREAK or DT_CALCRECT or DT_NOPREFIX).toUInt()
        val measured = DrawTextW(hdc, text, -1, sharedScratchRect.ptr, flags)
        val calcH = max(measured, sharedScratchRect.bottom - sharedScratchRect.top)
        return (calcH + 4).coerceAtLeast(lineHeightEstimate)
    }

    internal fun measureTextHeightWithFont(
        text: String,
        maxWidth: Int,
        lineHeightEstimate: Int,
        fontKind: Int = 0
    ): Int = memScoped {
        ensureSharedFonts()
        val dc = cachedMeasureDc ?: CreateCompatibleDC(null)?.also { cachedMeasureDc = it }
        val font = when (fontKind) {
            2 -> cachedFontTitle
            3 -> cachedFontMetricBig
            1 -> cachedFontBold
            -1 -> cachedFontSmall
            else -> cachedFontNormal
        }
        if (dc != null && font != null) {
            SelectObject(dc, font)
        }
        measureMultilineTextHeight(dc, text, maxWidth, lineHeightEstimate)
    }

    internal fun measureSingleLineTextWidthWithFont(
        text: String,
        fontKind: Int = 0
    ): Int = memScoped {
        if (text.isEmpty()) return@memScoped 0
        ensureSharedFonts()
        val dc = cachedMeasureDc ?: CreateCompatibleDC(null)?.also { cachedMeasureDc = it } ?: return@memScoped text.length * 7
        val font = when (fontKind) {
            2 -> cachedFontTitle
            3 -> cachedFontMetricBig
            1 -> cachedFontBold
            -1 -> cachedFontSmall
            else -> cachedFontNormal
        }
        if (font != null) {
            SelectObject(dc, font)
        }
        val rect = alloc<RECT>()
        rect.left = 0
        rect.top = 0
        rect.right = 4096
        rect.bottom = 100
        val flags = (DT_SINGLELINE or DT_CALCRECT or DT_NOPREFIX).toUInt()
        DrawTextW(dc, text, -1, rect.ptr, flags)
        (rect.right - rect.left).coerceAtLeast(0)
    }

    internal fun rgb(r: Int, g: Int, b: Int): UInt =
        ((r and 0xFF) or ((g and 0xFF) shl 8) or ((b and 0xFF) shl 16)).toUInt()

    fun isMainHostWindow(hwnd: HWND?): Boolean = hwnd != null && hwnd == activeHwnd

    private fun buildAncestorWidgetChain(widget: DesktopNativeWidget?): List<DesktopNativeWidget> {
        if (widget == null) return emptyList()
        val chain = mutableListOf<DesktopNativeWidget>()
        var cur: DesktopNativeWidget? = widget
        var depth = 0
        while (cur != null && depth < 32) {
            chain.add(cur)
            cur = cur.parentWidget
            depth++
        }
        return chain
    }

    internal fun handleMouseLeave(hwnd: HWND) {
        mouseLeaveTrackInstalledForHwnd = 0L
        if (activeDragRegion != null || isDraggingCanvas || PointerDragManager.isDragging) {
            return
        }
        val prevWidget = hoveredWidget
        if (prevWidget != null) {
            val pointerEv = PointerEvent(x = hoverMouseX.toFloat(), y = hoverMouseY.toFloat())
            for (exited in buildAncestorWidgetChain(prevWidget)) {
                val exitedImpl = exited.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
                exitedImpl?.setHoveredState(false)
                exitedImpl?.dispatchPointerLeave(pointerEv)
            }
            hoveredWidget = null
            hoveredHitRegion = null
            InvalidateRect(hwnd, null, 0)
        }
        if (isPointerInVideoStage) {
            isPointerInVideoStage = false
            if (!com.nexustools.genericui.event.PopupMouseCaptureManager.hasActivePopup) {
                overlayHudTargetAlpha = 0f
                if (lastOverlayFadeTickMs == 0.0) {
                    overlayHudCurrentAlpha = 0f
                }
            }
            markVideoStageDirty(hwnd)
        }
    }

    fun clearActiveWindow() {
        Win32NativeTitleBarRenderer.clearWindowState(null)
        releaseMainBackBufferDc()
        activeHwnd = null
        cachedDpiScaleHwndKey = 0L
        activeFullscreenElementWidget = null
        isWindowFullscreenActive = false
        isPointerInVideoStage = false
        overlayHudCurrentAlpha = 1f
        overlayHudTargetAlpha = 1f
        lastOverlayFadeTickMs = 0.0
        lastRenderedVideoStageRect = intArrayOf(-1, -1, -1, -1)
        mouseLeaveTrackInstalledForHwnd = 0L
        panelHwnds.clear()
        hwndToPanel.clear()
        dialogHwnds.clear()
        hwndToDialog.clear()
        hitRegionsByHwnd.clear()
        nativeEditEntriesByWidget.clear()
        nativeEditEntriesByChildHwnd.clear()
        origEditWndProcByChildHwnd.clear()
        isDraggingCardScrollbarThumb = false
        isDraggingLazyGridScrollbarThumb = false
    }
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun win32HostWndProc(hwnd: HWND?, msg: UINT, wParam: WPARAM, lParam: LPARAM): LRESULT {
    val isPanelTool = Win32NativeWindowHost.isPanelToolWindow(hwnd)
    val isMainCustom = Win32NativeWindowHost.isMainHostWindow(hwnd) &&
        Win32NativeWindowHost.hasCustomMainWindowTitleBar()
    val hasCustomTitleBarFrame = isPanelTool || isMainCustom

    if (hasCustomTitleBarFrame && hwnd != null) {
        Win32DwmUxThemeBridge.dwmDefWindowProc(hwnd, msg, wParam, lParam)?.let { dwmRes ->
            return dwmRes
        }
    }

    when (msg.toInt()) {
        WM_GETMINMAXINFO -> {
            if (hwnd != null && lParam != 0L && Win32NativeWindowHost.isMainHostWindow(hwnd)) {
                val mmiPtr = lParam.toCPointer<MINMAXINFO>()
                if (mmiPtr != null) {
                    val (minW, minH) = Win32NativeWindowHost.resolveMinWindowTrackSize(hwnd)
                    mmiPtr.pointed.ptMinTrackSize.x = minW
                    mmiPtr.pointed.ptMinTrackSize.y = minH
                    return 0L
                }
            }
        }
        WM_NCCALCSIZE -> {
            if (wParam != 0uL && hasCustomTitleBarFrame && hwnd != null) {
                return Win32NativeTitleBarRenderer.handleNcCalcSize(hwnd, wParam, lParam)
            }
        }
        WM_NCHITTEST -> {
            if (hwnd != null && hasCustomTitleBarFrame) {
                val screenX = (lParam and 0xFFFF).toShort().toInt()
                val screenY = ((lParam ushr 16) and 0xFFFF).toShort().toInt()
                val hit = if (isPanelTool) {
                    Win32NativeWindowHost.hitTestPanelWindow(hwnd, screenX, screenY)
                } else {
                    Win32NativeWindowHost.hitTestMainWindow(hwnd, screenX, screenY)
                }
                Win32NativeTitleBarRenderer.updateHoveredNcButton(hwnd, hit.toInt())
                return hit
            }
        }
        WM_NCMOUSEMOVE -> {
            if (hwnd != null && hasCustomTitleBarFrame) {
                if (Win32NativeTitleBarRenderer.handleNcMouseMove(hwnd, wParam.toInt())) {
                    return 0L
                }
            }
        }
        0x02A2 /* WM_NCMOUSELEAVE */ -> {
            if (hwnd != null && hasCustomTitleBarFrame) {
                Win32NativeTitleBarRenderer.handleNcMouseLeave(hwnd)
                return 0L
            }
        }
        WM_NCLBUTTONDOWN -> {
            if (hwnd != null && hasCustomTitleBarFrame) {
                val screenX = (lParam and 0xFFFF).toShort().toInt()
                val screenY = ((lParam ushr 16) and 0xFFFF).toShort().toInt()
                if (Win32NativeTitleBarRenderer.handleNcLButtonDown(hwnd, wParam.toInt(), screenX, screenY)) {
                    return 0L
                }
            }
        }
        WM_NCLBUTTONUP -> {
            if (hwnd != null && hasCustomTitleBarFrame) {
                if (Win32NativeTitleBarRenderer.handleNcLButtonUp(
                        hwnd = hwnd,
                        hitCode = wParam.toInt(),
                        isMainWindow = Win32NativeWindowHost.isMainHostWindow(hwnd) && !isPanelTool
                    )
                ) {
                    return 0L
                }
            }
        }
        WM_NCRBUTTONUP -> {
            if (hwnd != null && hasCustomTitleBarFrame) {
                val hitCode = wParam.toInt()
                if (hitCode == HTCAPTION || hitCode == HTSYSMENU) {
                    val screenX = (lParam and 0xFFFF).toShort().toInt()
                    val screenY = ((lParam ushr 16) and 0xFFFF).toShort().toInt()
                    Win32NativeTitleBarRenderer.showNativeSystemMenu(hwnd, screenX, screenY)
                    return 0L
                }
            }
        }
        WM_ACTIVATE -> {
            if (hwnd != null) {
                val active = (wParam.toInt() and 0xFFFF) != WA_INACTIVE
                Win32NativeTitleBarRenderer.setWindowActive(hwnd, active)
                InvalidateRect(hwnd, null, 0)
            }
        }
        WM_NCACTIVATE -> {
            if (hwnd != null) {
                Win32NativeTitleBarRenderer.setWindowActive(hwnd, wParam != 0uL)
                InvalidateRect(hwnd, null, 0)
                if (hasCustomTitleBarFrame) {
                    return 1L
                }
            }
        }
        WM_SETTINGCHANGE,
        0x031A /* WM_THEMECHANGED */,
        0x0320 /* WM_DWMCOLORIZATIONCOLORCHANGED */ -> {
            Win32NativeWindowHost.onSystemThemeChanged(hwnd)
        }
        WM_ERASEBKGND -> return 1L
        WM_SETCURSOR -> {
            val hitTest = (lParam.toLong() and 0xFFFF).toInt()
            if (hwnd != null && hitTest == HTCLIENT && wParam.toLong() == hwnd.toLong()) {
                runCatching { Win32NativeWindowHost.updateCursorForWindow(hwnd) }
                return 1L
            }
        }
        WM_PAINT -> {
            if (hwnd != null) {
                runCatching { Win32NativeWindowHost.paintWindow(hwnd) }
            }
            return 0L
        }
        WM_SIZE -> {
            if (hwnd != null) {
                runCatching { Win32NativeWindowHost.handleWindowResize(hwnd) }
                InvalidateRect(hwnd, null, 0)
            }
            return 0L
        }
        0x0133 /* WM_CTLCOLOREDIT */,
        0x0138 /* WM_CTLCOLORSTATIC */ -> {
            val hdcChild = wParam.toLong().toCPointer<HDC__>()
            val childHwnd = if (lParam != 0L) lParam.toCPointer<HWND__>() else null
            if (hdcChild != null) {
                return Win32NativeWindowHost.handleCtlColorEdit(hdcChild, childHwnd)
            }
        }
        WM_COMMAND -> {
            val cmdId = (wParam.toLong() and 0xFFFF).toInt()
            val notifyCode = ((wParam.toLong() ushr 16) and 0xFFFF).toInt()
            val childHwnd = if (lParam != 0L) lParam.toCPointer<HWND__>() else null
            runCatching { Win32NativeWindowHost.handleCommand(cmdId, notifyCode, childHwnd) }
            return 0L
        }
        WM_KEYDOWN -> {
            if (hwnd != null) {
                runCatching { Win32NativeWindowHost.handleKeyDown(hwnd, wParam.toInt()) }
            }
            return 0L
        }
        WM_KEYUP -> {
            if (hwnd != null) {
                runCatching { Win32NativeWindowHost.handleKeyUp(hwnd, wParam.toInt()) }
            }
            return 0L
        }
        WM_CHAR -> {
            if (hwnd != null) {
                runCatching { Win32NativeWindowHost.handleCharInput(hwnd, wParam.toInt()) }
            }
            return 0L
        }
        WM_LBUTTONDOWN -> {
            if (hwnd != null) {
                val x = (lParam.toLong() and 0xFFFF).toShort().toInt()
                val y = ((lParam.toLong() ushr 16) and 0xFFFF).toShort().toInt()
                runCatching { Win32NativeWindowHost.handleMouseDown(hwnd, x, y) }
            }
            return 0L
        }
        WM_MOUSEMOVE -> {
            if (hwnd != null) {
                Win32NativeTitleBarRenderer.updateHoveredNcButton(hwnd, 0)
                val x = (lParam.toLong() and 0xFFFF).toShort().toInt()
                val y = ((lParam.toLong() ushr 16) and 0xFFFF).toShort().toInt()
                runCatching { Win32NativeWindowHost.handleMouseMove(hwnd, x, y) }
            }
            return 0L
        }
        0x02A3 /* WM_MOUSELEAVE */ -> {
            if (hwnd != null) {
                runCatching { Win32NativeWindowHost.handleMouseLeave(hwnd) }
            }
            return 0L
        }
        WM_LBUTTONUP -> {
            if (hwnd != null) {
                val x = (lParam.toLong() and 0xFFFF).toShort().toInt()
                val y = ((lParam.toLong() ushr 16) and 0xFFFF).toShort().toInt()
                ReleaseCapture()
                runCatching { Win32NativeWindowHost.handleMouseUp(hwnd, x, y) }
            }
            return 0L
        }
        0x0215 /* WM_CAPTURECHANGED */ -> {
            if (hwnd != null && Win32NativeWindowHost.isOverlayDragActive()) {
                runCatching {
                    Win32NativeWindowHost.handleMouseUp(
                        hwnd,
                        Win32NativeWindowHost.hoverMouseX,
                        Win32NativeWindowHost.hoverMouseY
                    )
                }
            }
            return 0L
        }
        WM_MOUSEWHEEL -> {
            if (hwnd != null) {
                val wheelDelta = ((wParam.toLong() ushr 16) and 0xFFFF).toShort().toInt()
                runCatching { Win32NativeWindowHost.handleMouseWheel(hwnd, wheelDelta) }
            }
            return 0L
        }
        WM_CLOSE -> {
            val dialog = Win32NativeWindowHost.getDialogForHwnd(hwnd)
            if (dialog != null) {
                dialog.closeDialog()
                return 0L
            }
            if (Win32NativeWindowHost.visualTestMode) {
                return 0L
            }
            if (hwnd != null && !Win32NativeWindowHost.isMainHostWindow(hwnd)) {
                ShowWindow(hwnd, SW_HIDE)
                return 0L
            }
        }
        WM_DESTROY -> {
            Win32NativeTitleBarRenderer.clearWindowState(hwnd)
            if (Win32NativeWindowHost.isMainHostWindow(hwnd)) {
                Win32NativeWindowHost.clearActiveWindow()
                PostQuitMessage(0)
            }
            return 0L
        }
    }
    return DefWindowProcW(hwnd, msg, wParam, lParam)
}
