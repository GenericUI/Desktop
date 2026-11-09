@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.render

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.core.DesktopStageOverlaySpec
import com.nexustools.genericui.desktop.dialog.DesktopDialogWindowNativeImpl
import com.nexustools.genericui.desktop.dock.DesktopDockPanelNativeImpl
import com.nexustools.genericui.desktop.dock.DesktopDockWorkspaceNativeImpl
import com.nexustools.genericui.desktop.lazy.DesktopLazyListNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopBookContainerNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopBottomNavigationBarNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopButtonNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopCheckboxNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopDividerNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopImageNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopNavbarNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopProgressBarNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopRangeSliderNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopScrollViewNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopSelectNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopSpinnerNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopStyledButtonNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopStyledContainerNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTabContainerNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTabNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTextInputNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTextNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.image.ImageDecodeOptions
import com.nexustools.genericui.image.ImageLoader
import com.nexustools.genericui.image.ImageSource
import com.nexustools.genericui.image.cache.ImageCacheManager
import com.nexustools.genericui.net.http.HttpRequest
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.css.CssLength
import com.nexustools.genericui.style.gradient.Gradient.Gradient
import com.nexustools.genericui.util.WeakReference
import com.nexustools.genericui.windows.Win32DwmUxThemeBridge
import com.nexustools.genericui.windows.Win32NativeWindowHost
import com.nexustools.genericui.windows.WindowsHttpClientNativeImpl
import com.nexustools.genericui.windows.WindowsWicImageCodecNativeImpl
import com.nexustools.genericui.windows.impl.WindowsContainerNativeImpl
import com.nexustools.genericui.windows.impl.WindowsLazyListNativeImpl
import com.nexustools.genericui.windows.impl.WindowsRenderableNativeImpl
import com.nexustools.genericui.windows.impl.WindowsStyledContainerNativeImpl
import com.nexustools.genericui.windows.impl.WindowsTabContainerNativeImpl
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import platform.windows.BITMAPINFO
import platform.windows.BITMAPINFOHEADER
import platform.windows.BI_RGB
import platform.windows.DIB_RGB_COLORS
import platform.windows.DT_CENTER
import platform.windows.DT_END_ELLIPSIS
import platform.windows.DT_LEFT
import platform.windows.DT_NOPREFIX
import platform.windows.DT_RIGHT
import platform.windows.DT_WORDBREAK
import platform.windows.DrawTextW
import platform.windows.GetTickCount64
import platform.windows.HALFTONE
import platform.windows.HDC
import platform.windows.HFONT
import platform.windows.HWND
import platform.windows.IntersectClipRect
import platform.windows.InvalidateRect
import platform.windows.RECT
import platform.windows.RestoreDC
import platform.windows.SRCCOPY
import platform.windows.SaveDC
import platform.windows.SelectObject
import platform.windows.SetBrushOrgEx
import platform.windows.SetStretchBltMode
import platform.windows.SetTextColor
import platform.windows.StretchDIBits
import kotlin.concurrent.AtomicInt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
object Win32WorkspaceWindowRenderUtil {

    fun canRender(widget: DesktopNativeWidget): Boolean {
        val impl = widget.attributes["nativeImpl"]
        if (impl is DesktopScrollViewNativeImpl<*> ||
            impl is DesktopDockWorkspaceNativeImpl<*> ||
            impl is DesktopDockPanelNativeImpl<*> ||
            impl is DesktopBookContainerNativeImpl<*>
        ) {
            return true
        }
        val cls = widget.nativeClassName.lowercase()
        val role = (widget.attributes["componentRole"] as? String ?: "").lowercase()
        return cls in WORKSPACE_CLASSES || role in WORKSPACE_ROLES
    }

    fun render(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        left: Int,
        top: Int,
        right: Int
    ): Int {
        val impl = widget.attributes["nativeImpl"]
        val cls = widget.nativeClassName.lowercase()
        val role = (widget.attributes["componentRole"] as? String ?: "").lowercase()

        return when {
            impl is DesktopScrollViewNativeImpl<*> || cls == "scrollview" || role == "scroll-view" ->
                renderScrollView(ctx, widget, impl as? DesktopScrollViewNativeImpl<*>, left, top, right)
            cls == "splitpane" || role == "split-pane" ->
                renderSplitPane(ctx, widget, left, top, right)
            impl is DesktopDockWorkspaceNativeImpl<*> || cls == "dockworkspace" || role == "dock-workspace" ->
                renderDockWorkspace(ctx, widget, impl as? DesktopDockWorkspaceNativeImpl<*>, left, top, right)
            impl is DesktopBookContainerNativeImpl<*> || cls == "bookcontainer" || role == "book-container" ->
                renderBookContainer(ctx, widget, impl as? DesktopBookContainerNativeImpl<*>, left, top, right)
            cls == "mdicontainer" || role == "mdi-container" || cls == "mdiwindow" || role == "mdi-window" ->
                renderMdiContainerOrWindow(ctx, widget, left, top, right)
            cls == "dialoghost" || role == "dialog-host" || cls == "toasthost" || role == "toast-host" ->
                renderDialogOrToastHost(ctx, widget, left, top, right)
            else ->
                Win32ContainerChromeRenderUtil.render(ctx, widget, left, top, right)
        }
    }

    /**
     * Renders a `ScrollView` with clipped viewport content and a native `uxtheme.dll` `SCROLLBAR`
     * vertical track and thumb when content overflows.
     */
    fun renderScrollView(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        scrollImpl: DesktopScrollViewNativeImpl<*>?,
        left: Int,
        top: Int,
        right: Int
    ): Int {
        val viewportH = (widget.preferredSize?.height ?: 180).coerceIn(48, 600)
        val bottom = top + viewportH
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), viewportH)

        val scrollY = scrollImpl?.currentScrollY() ?: 0
        val scrollbarW = 14
        val contentRight = (right - scrollbarW - 2).coerceAtLeast(left + 16)

        val savedClip = Win32NativeWindowHost.pushClipRect(ctx.hdc, left, top, right, bottom)
        var unclippedBottom = top - scrollY
        try {
            for (child in ctx.visibleChildrenOf(widget)) {
                unclippedBottom = ctx.renderChild(child, left, unclippedBottom, contentRight) + 6
            }
        } finally {
            Win32NativeWindowHost.popClipRect(ctx.hdc, savedClip)
        }

        val totalContentH = (unclippedBottom - (top - scrollY)).coerceAtLeast(viewportH)
        if (totalContentH > viewportH) {
            val sbLeft = right - scrollbarW
            val sbClassList = Win32DwmUxThemeBridge.resolveExplorerClassList("SCROLLBAR", ctx.darkMode)
            Win32DwmUxThemeBridge.drawUxThemeControlBackground(
                hwnd = ctx.hwnd,
                hdc = ctx.hdc,
                classList = sbClassList,
                partId = Win32DwmUxThemeBridge.SBP_LOWERTRACKVERT,
                stateId = Win32DwmUxThemeBridge.SCRBS_NORMAL,
                left = sbLeft,
                top = top,
                right = right,
                bottom = bottom
            )
            val thumbH = ((viewportH.toFloat() / totalContentH.toFloat()) * viewportH).roundToInt().coerceIn(20, viewportH)
            val maxScroll = (totalContentH - viewportH).coerceAtLeast(1)
            val thumbTop = top + ((scrollY.coerceIn(0, maxScroll).toFloat() / maxScroll.toFloat()) * (viewportH - thumbH)).roundToInt()
            Win32DwmUxThemeBridge.drawUxThemeControlBackground(
                hwnd = ctx.hwnd,
                hdc = ctx.hdc,
                classList = sbClassList,
                partId = Win32DwmUxThemeBridge.SBP_THUMBBTNVERT,
                stateId = Win32DwmUxThemeBridge.SCRBS_NORMAL,
                left = sbLeft,
                top = thumbTop,
                right = right,
                bottom = (thumbTop + thumbH).coerceAtMost(bottom)
            )
        }
        return bottom
    }

    /**
     * Renders a `SplitPane` with two resizable child panes and a native splitter bar.
     */
    fun renderSplitPane(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        left: Int,
        top: Int,
        right: Int
    ): Int {
        val children = ctx.visibleChildrenOf(widget)
        val ratio = ((widget.attributes["splitRatio"] as? Number)?.toFloat() ?: 0.5f).coerceIn(0.15f, 0.85f)
        val totalW = (right - left).coerceAtLeast(64)
        val splitX = left + (totalW * ratio).roundToInt()
        val firstPane = children.getOrNull(0)
        val secondPane = children.getOrNull(1)
        val b1 = if (firstPane != null) ctx.renderChild(firstPane, left, top, splitX - 3) else top + 60
        val b2 = if (secondPane != null) ctx.renderChild(secondPane, splitX + 3, top, right) else top + 60
        val bottom = max(b1, b2)
        Win32NativeWindowHost.fillRectColor(ctx.hdc, splitX - 1, top, splitX + 1, bottom, ctx.borderSubtleColorRef)
        widget.bounds = Rect(left, top, totalW, (bottom - top).coerceAtLeast(24))
        return bottom
    }

    /**
     * Renders a `DockWorkspace` and its docked panels.
     */
    fun renderDockWorkspace(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        dockImpl: DesktopDockWorkspaceNativeImpl<*>?,
        left: Int,
        top: Int,
        right: Int
    ): Int {
        val children = ctx.visibleChildrenOf(widget)
        var curY = top
        for (panel in children) {
            val panelImpl = panel.attributes["nativeImpl"] as? DesktopDockPanelNativeImpl<*>
            val title = panelImpl?.getTitle() ?: (panel.attributes["title"] as? String) ?: "Dock Panel"
            Win32ControlTintRenderUtil.drawNativeOrTintedControlWithTextOverlay(
                ctx = ctx,
                widget = panel,
                baseClass = "HEADER",
                partId = Win32DwmUxThemeBridge.HP_HEADERITEM,
                stateId = Win32DwmUxThemeBridge.HIS_NORMAL,
                left = left,
                top = curY,
                right = right,
                bottom = curY + 26,
                text = title,
                font = ctx.fontSmallBold ?: ctx.fontNormal,
                centerText = false
            )
            curY += 28
            if (panelImpl?.isCollapsed() != true) {
                curY = ctx.renderChild(panel, left + 4, curY, right - 4) + 6
            }
        }
        val bottom = max(top + 48, curY)
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), bottom - top)
        return bottom
    }

    /**
     * Renders a `BookContainer` page spread.
     */
    fun renderBookContainer(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        bookImpl: DesktopBookContainerNativeImpl<*>?,
        left: Int,
        top: Int,
        right: Int
    ): Int {
        val pages = ctx.visibleChildrenOf(widget)
        val attrs = bookImpl?.nativeWidget?.attributes ?: widget.attributes
        val curPage = ((attrs["currentPage"] as? Number)?.toInt() ?: 0).coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        val activePage = pages.getOrNull(curPage)
        val bottom = if (activePage != null) {
            ctx.renderChild(activePage, left + 8, top + 8, right - 8) + 8
        } else {
            top + 120
        }
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), bottom - top)
        return bottom
    }

    /**
     * Renders an `MdiContainer` or `MdiWindow` with native `uxtheme.dll` `WINDOW` caption chrome.
     */
    fun renderMdiContainerOrWindow(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        left: Int,
        top: Int,
        right: Int
    ): Int {
        val title = (widget.attributes["title"] as? String) ?: widget.nativeClassName.ifBlank { "Window" }
        val captionH = 28
        Win32DwmUxThemeBridge.drawUxThemeControlBackground(
            hwnd = ctx.hwnd,
            hdc = ctx.hdc,
            classList = "WINDOW",
            partId = Win32DwmUxThemeBridge.WP_CAPTION,
            stateId = Win32DwmUxThemeBridge.CS_ACTIVE,
            left = left,
            top = top,
            right = right,
            bottom = top + captionH
        )
        ctx.selectFont(ctx.fontBold ?: ctx.fontNormal)
        Win32NativeWindowHost.drawTextInRect(
            hdc = ctx.hdc,
            text = title,
            left = left + 10,
            top = top,
            right = right - 32,
            bottom = top + captionH,
            colorRef = ctx.textPrimaryColorRef
        )
        var curY = top + captionH + 6
        for (child in ctx.visibleChildrenOf(widget)) {
            curY = ctx.renderChild(child, left + 8, curY, right - 8) + 6
        }
        val bottom = max(top + 80, curY + 6)
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), bottom - top)
        return bottom
    }

    /**
     * Renders a `DialogHost` or `ToastHost`.
     */
    fun renderDialogOrToastHost(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        left: Int,
        top: Int,
        right: Int
    ): Int {
        var curY = top
        for (child in ctx.visibleChildrenOf(widget)) {
            curY = ctx.renderChild(child, left, curY, right) + 6
        }
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), (curY - top).coerceAtLeast(0))
        return curY
    }

    private val WORKSPACE_CLASSES = setOf(
        "splitpane",
        "scrollview",
        "mdicontainer",
        "mdiwindow",
        "dockworkspace",
        "dockpanel",
        "dialoghost",
        "toasthost",
        "swiperefreshpane",
        "bookcontainer",
        "horizontalcarouselselector"
    )
    private val WORKSPACE_ROLES = setOf(
        "split-pane",
        "scroll-view",
        "mdi-container",
        "mdi-window",
        "dock-workspace",
        "dock-panel",
        "dialog-host",
        "toast-host",
        "swipe-refresh-pane",
        "book-container",
        "horizontal-carousel-selector"
    )

    private class CompositeBgraEntry(
        val bgra: ByteArray,
        val width: Int,
        val height: Int,
        val rgba: ByteArray? = null
    )

    private class RelativeHitRegion(
        val relLeft: Int,
        val relTop: Int,
        val relRight: Int,
        val relBottom: Int,
        val tooltip: String?,
        val widget: DesktopNativeWidget?,
        val isTextInput: Boolean,
        val onClick: ((mouseX: Int, mouseY: Int) -> Unit)?,
        val onDrag: ((mouseX: Int, mouseY: Int) -> Unit)?,
        val onRelease: ((mouseX: Int, mouseY: Int) -> Unit)?
    )

    private class CachedSurfaceRegion(
        val width: Int,
        val height: Int,
        val bgra: ByteArray,
        val hitRegions: List<RelativeHitRegion>,
        val scrollRegions: List<IntArray> = emptyList(),
        val subtitleBannerRelBounds: com.nexustools.genericui.geometry.Rect? = null,
        val timePillRelBounds: com.nexustools.genericui.geometry.Rect? = null,
        val videoStageRect: IntArray? = null,
        val videoSwapCount: Long = -1L,
        val overlaySubtreeVer: Long = -1L,
        val hudAlphaQuantized: Int = -1
    )

    private data class RowSurfaceCacheKey(
        val widgetPtr: Long,
        val mutVer: Long,
        val width: Int,
        val isRowHovered: Boolean,
        val hoveredRightBtnPtr: Long,
        val pressedRightBtnPtr: Long,
        val hasActiveBgra: Boolean,
        val isDark: Boolean,
        val clipOffsetTop: Int = 0,
        val visibleHeight: Int = 56
    )

    private data class StripSurfaceCacheKey(
        val widgetPtr: Long,
        val mutVer: Long,
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val extraState: Int,
        val hoveredWidgetPtr: Long,
        val pressedWidgetPtr: Long,
        val isDark: Boolean
    )

    private data class VideoStageSurfaceCacheKey(
        val videoWidgetPtr: Long,
        val swapCount: Long,
        val miniSpectrumSwapCount: Long,
        val overlaySubtreeVer: Long = 0L,
        val otherChildrenMutHash: Int,
        val hudAlphaQuantized: Int,
        val isPlaying: Boolean,
        val titleTextHash: Int,
        val subtitleTextHash: Int,
        val activeSubtitleCueHash: Int,
        val settingsPanelVisible: Boolean,
        val settingsPanelMutVer: Long,
        val timePillWidthBucket: Int,
        val isStageHovered: Boolean,
        val hoveredStageWidgetPtr: Long,
        val pressedStageWidgetPtr: Long,
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val fullViewportMode: Boolean,
        val isDark: Boolean
    )

    private const val MAX_CACHED_ROW_REGIONS = 256
    private const val MAX_CACHED_VIDEO_STAGE_SURFACES = 8
    private const val MAX_CACHED_LAZY_VIEWPORT_SURFACES = 24
    private const val MAX_CACHED_SECTION_SURFACES = 48
    private const val MAX_CACHED_STRIP_SURFACES = 16
    private const val MAX_CACHED_COLUMN_SURFACES = 8
    private val cachedCompositeRowRegions = LinkedHashMap<RowSurfaceCacheKey, CachedSurfaceRegion>()
    private val cachedFullRowByWidgetPtr = HashMap<Long, Pair<RowSurfaceCacheKey, CachedSurfaceRegion>>(256)
    private val cachedNavbarSurfaces = LinkedHashMap<StripSurfaceCacheKey, CachedSurfaceRegion>(16)
    private val cachedTabBarSurfaces = LinkedHashMap<StripSurfaceCacheKey, CachedSurfaceRegion>(16)
    private val cachedVideoStageSurfaces = LinkedHashMap<VideoStageSurfaceCacheKey, CachedSurfaceRegion>(8)
    private val cachedLazyViewportSurfaces = LinkedHashMap<StripSurfaceCacheKey, CachedSurfaceRegion>(24)
    private val cachedSectionSurfaces = LinkedHashMap<StripSurfaceCacheKey, CachedSurfaceRegion>(48)
    private val cachedColumnSurfaces = LinkedHashMap<StripSurfaceCacheKey, CachedSurfaceRegion>(8)
    private val lastBackBufferStripKeys = HashMap<Long, StripSurfaceCacheKey>(32)
    private var lastLazyViewportStateKey: StripSurfaceCacheKey? = null
    private var lastLazyViewportBaseKey: StripSurfaceCacheKey? = null
    private var lastLazyHoveredRelTop: Int = -1
    private var lastLazyHoveredRelBottom: Int = -1
    private var lastLazyViewportHitRegions: List<RelativeHitRegion> = emptyList()
    private var suppressRowHoverForBaseViewportCapture: Boolean = false
    private var lastPaintedVideoStageCacheKey: VideoStageSurfaceCacheKey? = null
    private var lastPaintedVideoStageHwnd: HWND? = null
    private var lastPaintedTimePillTxt: String = ""
    private var hadVisibleVideoStagePopupLastFrame: Boolean = false
    private var isFirstPaintOfLazyList: Boolean = false
    private var hadFirstLazyPaintInFrame: Boolean = false
    private data class RowBaseTemplateKey(
        val width: Int,
        val rowH: Int,
        val isRowHovered: Boolean,
        val padLeft: Int,
        val hasLeadingBox: Boolean,
        val isDark: Boolean,
        val backdropPixel: UInt
    )

    private data class RightItemTemplateKey(
        val width: Int,
        val height: Int,
        val isRowHovered: Boolean,
        val isDark: Boolean,
        val kind: Int,
        val isPrimary: Boolean,
        val text: String,
        val icon: String
    )

    private data class DeferredRowCapture(
        val rowCacheKey: RowSurfaceCacheKey,
        val fullRowCacheKey: RowSurfaceCacheKey?,
        val widgetPtr: Long,
        val left: Int,
        val visTop: Int,
        val right: Int,
        val visBottom: Int,
        val hitStartIdx: Int,
        val hitEndIdx: Int,
        val postHasActiveBgra: Boolean
    )

    private data class DeferredRightItemCapture(
        val itemKey: RightItemTemplateKey,
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int
    )

    private val cachedRowBaseTemplates = HashMap<RowBaseTemplateKey, ByteArray>(16)
    private val cachedRightItemTemplates = HashMap<RightItemTemplateKey, ByteArray>(32)

    private fun fullRowPtrKey(widgetPtr: Long, isRowHovered: Boolean): Long =
        if (isRowHovered) (widgetPtr xor -0x4000_0000_0000_0000L) else widgetPtr
    private val pendingRowCaptures = ArrayList<DeferredRowCapture>(32)
    private val pendingRightItemCaptures = ArrayList<DeferredRightItemCapture>(32)
    private var deferRowCaptureInLazyLoop: Boolean = false
    private var lastIconCompositeUrl: String = ""
    private var lastIconCompositeW: Int = 0
    private var lastIconCompositeH: Int = 0
    private var lastIconCompositeFilter: CssFilter? = null
    private var lastIconCompositeDark: Boolean = false
    private var lastIconCompositeBg: UInt = 0u
    private var lastIconCompositeEntry: CompositeBgraEntry? = null
    private var lastRenderedClientW: Int = 0
    private var lastRenderedClientH: Int = 0
    private var isResizeFrame: Boolean = false

    private fun packRectKey(left: Int, top: Int, right: Int, bottom: Int): Long =
        ((left.toLong() and 0xFFFFL) shl 48) or
            ((top.toLong() and 0xFFFFL) shl 32) or
            ((right.toLong() and 0xFFFFL) shl 16) or
            (bottom.toLong() and 0xFFFFL)

    internal fun clearBackBufferRegionKeys() {
        lastBackBufferStripKeys.clear()
        lastLazyViewportStateKey = null
        lastLazyViewportBaseKey = null
        lastLazyHoveredRelTop = -1
        lastLazyHoveredRelBottom = -1
        lastLazyViewportHitRegions = emptyList()
        hadVisibleVideoStagePopupLastFrame = false
    }

    internal fun invalidateBackBufferRectKeys(left: Int, top: Int, right: Int, bottom: Int) {
        if (lastBackBufferStripKeys.isEmpty()) return
        val iter = lastBackBufferStripKeys.entries.iterator()
        while (iter.hasNext()) {
            val k = iter.next().key
            val rL = ((k ushr 48) and 0xFFFFL).toShort().toInt()
            val rT = ((k ushr 32) and 0xFFFFL).toShort().toInt()
            val rR = ((k ushr 16) and 0xFFFFL).toShort().toInt()
            val rB = (k and 0xFFFFL).toShort().toInt()
            if (right > rL && left < rR && bottom > rT && top < rB) {
                iter.remove()
            }
        }
    }

    private var lastExplicitHudHidden: Boolean = false

    internal fun resetLastPaintedVideoStageCacheKey() {
        lastPaintedVideoStageCacheKey = null
        lastPaintedVideoStageHwnd = null
        hadVisibleVideoStagePopupLastFrame = false
        lastExplicitHudHidden = false
    }

    internal fun invalidateFullscreenAndLayoutCaches() {
        cachedVideoFullViewportMode = false
        lastPaintedVideoStageCacheKey = null
        lastPaintedVideoStageHwnd = null
        lastPaintedTimePillTxt = ""
        lastExplicitHudHidden = false
        cachedColumnSurfaces.clear()
        cachedSectionSurfaces.clear()
        cachedVideoStageSurfaces.clear()
        clearBackBufferRegionKeys()
    }

    private fun canUseSurfaceRegionCache(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        checkScrollClip: Boolean = false
    ): Boolean {
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return false
        val bufPixels = Win32NativeWindowHost.activeBackBufferPixels ?: return false
        val bufW = Win32NativeWindowHost.activeBackBufferWidth
        val bufH = Win32NativeWindowHost.activeBackBufferHeight
        if (left < 0 || top < 0 || right > bufW || bottom > bufH) return false
        if (Win32NativeWindowHost.softClipDepth > 0) {
            if (left < Win32NativeWindowHost.softClipLeft ||
                top < Win32NativeWindowHost.softClipTop ||
                right > Win32NativeWindowHost.softClipRight ||
                bottom > Win32NativeWindowHost.softClipBottom
            ) {
                return false
            }
        }
        if (checkScrollClip) {
            if (top < currentClipTop || bottom > currentClipBottom) return false
        }
        return true
    }

    private fun tryBlitCachedRegion(
        region: CachedSurfaceRegion,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        rowStart: Int = 0,
        rowEnd: Int = bottom - top,
        restoreHitRegions: Boolean = true,
        cacheKey: StripSurfaceCacheKey? = null
    ): Boolean {
        val w = right - left
        val h = bottom - top
        if (w != region.width || h != region.height) return false
        val bufPixels = Win32NativeWindowHost.activeBackBufferPixels ?: return false
        val bufW = Win32NativeWindowHost.activeBackBufferWidth
        val bufH = Win32NativeWindowHost.activeBackBufferHeight
        val clampedStart = rowStart.coerceIn(0, h)
        val clampedEnd = rowEnd.coerceIn(clampedStart, h)
        val dstTop = top + clampedStart
        val dstBottom = top + clampedEnd
        if (left < 0 || dstTop < 0 || right > bufW || dstBottom > bufH) return false
        if (region.bgra.size < w * h * 4) return false

        val rectKey = if (cacheKey != null && clampedStart == 0 && clampedEnd == h) {
            packRectKey(left, top, right, bottom)
        } else {
            0L
        }
        val skipPixelCopy = Win32NativeWindowHost.activeMainBackBufferPreserved &&
            cacheKey != null &&
            clampedStart == 0 &&
            clampedEnd == h &&
            lastBackBufferStripKeys[rectKey] == cacheKey

        if (!skipPixelCopy && clampedEnd > clampedStart) {
            if (cacheKey != null && clampedStart == 0 && clampedEnd == h) {
                invalidateBackBufferRectKeys(left, top, right, bottom)
                lastBackBufferStripKeys[rectKey] = cacheKey
            }
            Win32NativeWindowHost.flushGdiIfOverlapping(left, dstTop, right, dstBottom)
            Win32NativeWindowHost.markFrameBufferDirty(left, dstTop, right, dstBottom)
            val rowBytes = (w * 4).toULong()
            region.bgra.usePinned { pinned ->
                for (r in clampedStart until clampedEnd) {
                    val dstOff = ((top + r) * bufW + left) * 4
                    val srcOff = (r * w) * 4
                    platform.posix.memcpy(
                        kotlinx.cinterop.interpretCPointer<kotlinx.cinterop.ByteVar>(bufPixels.rawValue + dstOff.toLong()),
                        pinned.addressOf(srcOff),
                        rowBytes
                    )
                }
            }
        }
        if (restoreHitRegions) {
            for (hr in region.hitRegions) {
                val hrTop = max(dstTop, top + hr.relTop)
                val hrBottom = min(dstBottom, top + hr.relBottom)
                if (hrBottom > hrTop) {
                    if (hr.isTextInput && hr.widget != null) {
                        Win32NativeWindowHost.markTextInputVisitedFromCache(
                            hr.widget,
                            left + hr.relLeft,
                            hrTop,
                            left + hr.relRight,
                            hrBottom
                        )
                    }
                    Win32NativeWindowHost.addHitRegion(
                        Win32NativeWindowHost.HitRegion(
                            left = left + hr.relLeft,
                            top = hrTop,
                            right = left + hr.relRight,
                            bottom = hrBottom,
                            tooltip = hr.tooltip,
                            widget = hr.widget,
                            isTextInput = hr.isTextInput,
                            onClick = hr.onClick,
                            onDrag = hr.onDrag,
                            onRelease = hr.onRelease
                        )
                    )
                }
            }
            for (sr in region.scrollRegions) {
                activeScrollRegions.add(
                    intArrayOf(
                        left + sr[0],
                        top + sr[1],
                        left + sr[2],
                        top + sr[3],
                        sr[4]
                    )
                )
            }
        }
        return true
    }

    private fun captureCachedRegion(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        hitStartIdx: Int,
        scrollStartIdx: Int = activeScrollRegions.size,
        subtitleBannerRelBounds: com.nexustools.genericui.geometry.Rect? = null,
        timePillRelBounds: com.nexustools.genericui.geometry.Rect? = null,
        reuseBuffer: ByteArray? = null,
        cacheKey: StripSurfaceCacheKey? = null,
        hitEndIdxOverride: Int = -1,
        alreadyFlushedGdi: Boolean = false,
        videoStageRect: IntArray? = null,
        videoSwapCount: Long = -1L,
        overlaySubtreeVer: Long = -1L,
        hudAlphaQuantized: Int = -1
    ): CachedSurfaceRegion? {
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return null
        val bufPixels = Win32NativeWindowHost.activeBackBufferPixels ?: return null
        val bufW = Win32NativeWindowHost.activeBackBufferWidth
        val bufH = Win32NativeWindowHost.activeBackBufferHeight
        if (left < 0 || top < 0 || right > bufW || bottom > bufH) return null

        if (!alreadyFlushedGdi) {
            Win32NativeWindowHost.flushGdiIfOverlapping(left, top, right, bottom)
        }
        Win32NativeWindowHost.markFrameBufferDirty(left, top, right, bottom)
        val totalBytes = w * h * 4
        val bgra = if (reuseBuffer != null && reuseBuffer.size >= totalBytes) reuseBuffer else ByteArray(totalBytes)
        val rowBytes = (w * 4).toULong()
        bgra.usePinned { pinned ->
            for (r in 0 until h) {
                val srcOff = ((top + r) * bufW + left) * 4
                val dstOff = (r * w) * 4
                platform.posix.memcpy(
                    pinned.addressOf(dstOff),
                    kotlinx.cinterop.interpretCPointer<kotlinx.cinterop.ByteVar>(bufPixels.rawValue + srcOff.toLong()),
                    rowBytes
                )
            }
        }
        if (cacheKey != null) {
            invalidateBackBufferRectKeys(left, top, right, bottom)
            lastBackBufferStripKeys[packRectKey(left, top, right, bottom)] = cacheKey
        }
        val totalHits = Win32NativeWindowHost.getHitRegionsCount()
        val hitEndIdx = if (hitEndIdxOverride in 0..totalHits) hitEndIdxOverride else totalHits
        val relHits = if (hitEndIdx > hitStartIdx) {
            val list = ArrayList<RelativeHitRegion>(hitEndIdx - hitStartIdx)
            for (idx in hitStartIdx until hitEndIdx) {
                val hr = Win32NativeWindowHost.getHitRegionAt(idx)
                list.add(
                    RelativeHitRegion(
                        relLeft = hr.left - left,
                        relTop = hr.top - top,
                        relRight = hr.right - left,
                        relBottom = hr.bottom - top,
                        tooltip = hr.tooltip,
                        widget = hr.widget,
                        isTextInput = hr.isTextInput,
                        onClick = hr.onClick,
                        onDrag = hr.onDrag,
                        onRelease = hr.onRelease
                    )
                )
            }
            list
        } else {
            emptyList()
        }
        val scrollEndIdx = activeScrollRegions.size
        val relScrolls = if (scrollEndIdx > scrollStartIdx) {
            val list = ArrayList<IntArray>(scrollEndIdx - scrollStartIdx)
            for (idx in scrollStartIdx until scrollEndIdx) {
                val sr = activeScrollRegions[idx]
                list.add(intArrayOf(sr[0] - left, sr[1] - top, sr[2] - left, sr[3] - top, sr[4]))
            }
            list
        } else {
            emptyList()
        }
        return CachedSurfaceRegion(
            width = w,
            height = h,
            bgra = bgra,
            hitRegions = relHits,
            scrollRegions = relScrolls,
            subtitleBannerRelBounds = subtitleBannerRelBounds,
            timePillRelBounds = timePillRelBounds,
            videoStageRect = videoStageRect,
            videoSwapCount = videoSwapCount,
            overlaySubtreeVer = overlaySubtreeVer,
            hudAlphaQuantized = hudAlphaQuantized
        )
    }

    // Scroll state keyed by container identity hash
    private val scrollOffsetsY = mutableMapOf<Int, Int>()
    private val maxScrollOffsetsY = mutableMapOf<Int, Int>()
    // Cached flow node height estimates keyed by (nativePointer, subtreeMutationVersion, availW)
    private val heightEstimateCache = HashMap<Long, Int>(1024)
    // Active scrollable regions in current frame: [left, top, right, bottom, containerKey]
    private val activeScrollRegions = mutableListOf<IntArray>()
    private var currentClipTop: Int = 0
    private var currentClipBottom: Int = 10000
    internal var cachedVideoWidget: DesktopNativeWidget? = null
    internal var cachedOverlayWidget: DesktopNativeWidget? = null
    private var cachedOtherStageChildren: List<DesktopNativeWidget> = emptyList()
    private var cachedTitleBarWidgets: Set<DesktopNativeWidget> = emptySet()
    private var cachedVideoFullViewportMode: Boolean = false

    internal fun resolveVideoStageHeight(
        availW: Int,
        videoWidget: DesktopNativeWidget? = null,
        overlayWidget: DesktopNativeWidget? = null
    ): Int {
        val explicitH = videoWidget?.resolveCssValue("height")?.removeSuffix("px")?.trim()?.toFloatOrNull()
            ?: videoWidget?.parentWidget?.resolveCssValue("height")?.removeSuffix("px")?.trim()?.toFloatOrNull()
        if (explicitH != null && explicitH > 40f) {
            return explicitH.roundToInt().coerceAtLeast(180)
        }
        val ratioAttr = videoWidget?.interactiveSurfaceSpec?.defaultAspectRatio
            ?: (videoWidget?.attributes?.get("defaultAspectRatio") as? Float)
            ?: (videoWidget?.attributes?.get("aspectRatio") as? Number)?.toFloat()
            ?: (videoWidget?.parentWidget?.attributes?.get("aspectRatio") as? Number)?.toFloat()
            ?: (overlayWidget?.parentWidget?.attributes?.get("aspectRatio") as? Number)?.toFloat()
            ?: videoWidget?.resolveCssValue("aspect-ratio")?.toFloatOrNull()
            ?: videoWidget?.parentWidget?.resolveCssValue("aspect-ratio")?.toFloatOrNull()
        val aspect = if (ratioAttr != null && ratioAttr > 0.1f) ratioAttr else (16f / 9f)
        return (availW.toFloat() / aspect).roundToInt().coerceAtLeast(180)
    }

    private fun isFullViewportVideoStage(vararg widgets: DesktopNativeWidget?): Boolean {
        if (Win32NativeWindowHost.isFullscreenActiveForMainWindow()) return true
        for (w in widgets) {
            var cur = w
            var depth = 0
            while (cur != null && depth < 10) {
                if (Win32NativeWindowHost.widgetHasClass(cur, "full-viewport-video-stage")) {
                    return true
                }
                cur = cur.parentWidget
                depth++
            }
        }
        return false
    }

    internal fun renderCachedVideoStageFastPath(
        hwnd: HWND,
        hdc: HDC?,
        theme: Win32NativeWindowHost.SystemThemePalette,
        fontSmall: HFONT?,
        fontSmallBold: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        fontTitle: HFONT?
    ): Boolean {
        val vWidget = cachedVideoWidget ?: return false
        val oWidget = cachedOverlayWidget ?: return false
        if (vWidget.disposed || !vWidget.visible || oWidget.disposed || !oWidget.visible) return false
        if (cachedVideoFullViewportMode != isFullViewportVideoStage(vWidget, oWidget)) return false
        val vr = Win32NativeWindowHost.lastRenderedVideoStageRect
        if (vr[2] <= vr[0] || vr[3] <= vr[1]) return false
        val savedClip = Win32NativeWindowHost.pushClipRect(hdc, vr[0], vr[1], vr[2], vr[3])
        try {
            renderVideoStageWithOverlay(
                hwnd = hwnd,
                hdc = hdc,
                theme = theme,
                fontSmall = fontSmall,
                fontSmallBold = fontSmallBold,
                fontNormal = fontNormal,
                fontBold = fontBold,
                fontTitle = fontTitle,
                videoWidget = vWidget,
                overlayWidget = oWidget,
                otherChildren = cachedOtherStageChildren,
                titleBarWidgets = cachedTitleBarWidgets,
                left = vr[0],
                top = vr[1],
                right = vr[2],
                bottomLimit = vr[3],
                fullViewportMode = cachedVideoFullViewportMode,
                isFastPathStagePaint = true
            )
        } finally {
            Win32NativeWindowHost.popClipRect(hdc, savedClip)
        }
        return true
    }

    // Async Image / Row Icon Strong-LRU + Weak-Reference Cache & Coroutine Decoder
    private val imageCacheLock = AtomicInt(0)
    private val strongRawImageLru = LinkedHashMap<String, ImageBuffer>()
    private val strongCompositeBgraLru = LinkedHashMap<String, CompositeBgraEntry>()
    private val rawImageBufferCache = mutableMapOf<String, WeakReference<ImageBuffer>>()
    private val decodedImageBgraCache = mutableMapOf<String, WeakReference<CompositeBgraEntry>>()
    private val inFlightImageUrls = mutableSetOf<String>()
    private val inFlightCompositeKeys = mutableSetOf<String>()
    private val failedImageUrls = mutableSetOf<String>()
    private val sharedWicImageCodec = WindowsWicImageCodecNativeImpl()
    private val sharedHttpClient = WindowsHttpClientNativeImpl()
    private val imageFetchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val imageFetchSemaphore = Semaphore(4)

    private fun putStrongRawImageLruLocked(key: String, value: ImageBuffer) {
        strongRawImageLru.remove(key)
        strongRawImageLru[key] = value
        while (strongRawImageLru.size > 128) {
            val eldestKey = strongRawImageLru.keys.firstOrNull() ?: break
            strongRawImageLru.remove(eldestKey)
        }
    }

    private fun putStrongCompositeBgraLruLocked(key: String, value: CompositeBgraEntry) {
        strongCompositeBgraLru.remove(key)
        strongCompositeBgraLru[key] = value
        while (strongCompositeBgraLru.size > 192) {
            val eldestKey = strongCompositeBgraLru.keys.firstOrNull() ?: break
            strongCompositeBgraLru.remove(eldestKey)
        }
    }

    private inline fun <T> withImageCacheLock(block: () -> T): T {
        while (!imageCacheLock.compareAndSet(0, 1)) {
            // Spin-wait
        }
        return try {
            block()
        } finally {
            imageCacheLock.value = 0
        }
    }

    internal fun handleMouseWheel(hwnd: HWND, mouseX: Int, mouseY: Int, wheelDelta: Int): Boolean {
        val region = activeScrollRegions.lastOrNull {
            mouseX in it[0]..it[2] && mouseY in it[1]..it[3]
        } ?: activeScrollRegions.lastOrNull() ?: return false

        val key = region[4]
        val maxScroll = maxScrollOffsetsY[key] ?: 0
        if (maxScroll <= 0) return false

        val curScroll = scrollOffsetsY[key] ?: 0
        val step = when {
            wheelDelta > 0 -> -max(48, (wheelDelta * 72) / 120)
            else -> max(48, (-wheelDelta * 72) / 120)
        }
        val next = (curScroll + step).coerceIn(0, maxScroll)
        if (next != curScroll) {
            scrollOffsetsY[key] = next
            return true
        }
        return false
    }

    internal fun renderGeneralComponentTree(
        hwnd: HWND,
        hdc: HDC?,
        theme: Win32NativeWindowHost.SystemThemePalette,
        fontSmall: HFONT?,
        fontSmallBold: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        fontTitle: HFONT?,
        fontMetricBig: HFONT?,
        rootWidget: DesktopNativeWidget?,
        titleBarWidgets: Set<DesktopNativeWidget>,
        clientW: Int,
        clientH: Int,
        bodyTop: Int
    ) {
        activeScrollRegions.clear()
        isFirstPaintOfLazyList = false
        hadFirstLazyPaintInFrame = false
        isResizeFrame = (lastRenderedClientW != 0 && (clientW != lastRenderedClientW || clientH != lastRenderedClientH))
        lastRenderedClientW = clientW
        lastRenderedClientH = clientH
        if (rootWidget == null || !rootWidget.visible || rootWidget.disposed) return

        val fsWidget = if (Win32NativeWindowHost.isFullscreenActiveForMainWindow()) {
            Win32NativeWindowHost.activeFullscreenElementWidget?.takeIf { !it.disposed && it.visible }
                ?: findFullscreenDescendant(rootWidget)
        } else {
            null
        }
        if (fsWidget != null && !fsWidget.disposed) {
            val stageAndOverlay = findStageAndOverlay(fsWidget, titleBarWidgets)
                ?: visibleChildrenOf(fsWidget, titleBarWidgets).firstNotNullOfOrNull {
                    findStageAndOverlay(it, titleBarWidgets)
                }
            if (stageAndOverlay != null) {
                val (videoChild, overlayChild) = stageAndOverlay
                renderVideoStageWithOverlay(
                    hwnd = hwnd,
                    hdc = hdc,
                    theme = theme,
                    fontSmall = fontSmall,
                    fontSmallBold = fontSmallBold,
                    fontNormal = fontNormal,
                    fontBold = fontBold,
                    fontTitle = fontTitle,
                    videoWidget = videoChild,
                    overlayWidget = overlayChild,
                    otherChildren = emptyList(),
                    titleBarWidgets = titleBarWidgets,
                    left = 0,
                    top = 0,
                    right = clientW,
                    bottomLimit = clientH,
                    fullViewportMode = true
                )
            } else if (isInteractiveSurfaceWidget(fsWidget)) {
                Win32NativeWindowHost.renderInlineCanvasSurface(
                    hdc = hdc,
                    canvasWidget = fsWidget,
                    left = 0,
                    top = 0,
                    right = clientW,
                    bottom = clientH,
                    theme = theme,
                    registerAsPrimaryCanvas = false
                )
            } else {
                renderRootContainer(
                    hwnd = hwnd,
                    hdc = hdc,
                    theme = theme,
                    fontSmall = fontSmall,
                    fontSmallBold = fontSmallBold,
                    fontNormal = fontNormal,
                    fontBold = fontBold,
                    fontTitle = fontTitle,
                    fontMetricBig = fontMetricBig,
                    node = fsWidget,
                    titleBarWidgets = titleBarWidgets,
                    left = 0,
                    top = 0,
                    right = clientW,
                    bottom = clientH
                )
            }
            return
        }

        val contentRoot = unwrapShellContainers(rootWidget, titleBarWidgets)
        renderRootContainer(
            hwnd = hwnd,
            hdc = hdc,
            theme = theme,
            fontSmall = fontSmall,
            fontSmallBold = fontSmallBold,
            fontNormal = fontNormal,
            fontBold = fontBold,
            fontTitle = fontTitle,
            fontMetricBig = fontMetricBig,
            node = contentRoot,
            titleBarWidgets = titleBarWidgets,
            left = 0,
            top = bodyTop,
            right = clientW,
            bottom = clientH
        )
    }

    private fun findFullscreenDescendant(widget: DesktopNativeWidget?): DesktopNativeWidget? {
        if (widget == null || widget.disposed || !widget.visible) return null
        if (widget.attributes["fullscreenActive"] == true) return widget
        for (child in safeChildrenSnapshot(widget)) {
            val found = findFullscreenDescendant(child)
            if (found != null) return found
        }
        return null
    }

    internal fun renderModalDialogBody(
        hwnd: HWND,
        hdc: HDC?,
        theme: Win32NativeWindowHost.SystemThemePalette,
        fontSmall: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        fontTitle: HFONT?,
        dialogWidget: DesktopNativeWidget,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        val allVisible = visibleChildrenOf(dialogWidget, emptySet())
        val footerWidget = allVisible.firstOrNull { Win32NativeWindowHost.widgetHasClass(it, "modal-footer") }
        val bodyChildren = if (footerWidget != null) {
            allVisible.filter { it !== footerWidget }
        } else {
            allVisible
        }
        val footerH = if (footerWidget != null) 44 else 0
        val footerTop = if (footerWidget != null) (bottom - footerH).coerceAtLeast(top) else bottom
        val bodyBottom = if (footerWidget != null) (footerTop - 6).coerceAtLeast(top) else bottom

        renderScrollableVerticalStack(
            hwnd = hwnd,
            hdc = hdc,
            theme = theme,
            fontSmall = fontSmall,
            fontSmallBold = fontBold ?: fontSmall,
            fontNormal = fontNormal,
            fontBold = fontBold,
            fontTitle = fontTitle,
            fontMetricBig = fontTitle,
            container = dialogWidget,
            children = bodyChildren,
            titleBarWidgets = emptySet(),
            left = left,
            top = top,
            right = right,
            bottom = bodyBottom,
            padX = 8,
            padY = 6
        )

        if (footerWidget != null) {
            Win32NativeWindowHost.fillRectColor(hdc, left, footerTop, right, footerTop + 1, theme.borderSubtle)
            val footerChildren = visibleChildrenOf(footerWidget, emptySet())
            renderHorizontalRow(
                hwnd = hwnd,
                hdc = hdc,
                theme = theme,
                fontSmall = fontSmall,
                fontSmallBold = fontBold ?: fontSmall,
                fontNormal = fontNormal,
                fontBold = fontBold,
                fontTitle = fontTitle,
                fontMetricBig = fontTitle,
                rowWidget = footerWidget,
                children = footerChildren,
                titleBarWidgets = emptySet(),
                left = left + 8,
                top = footerTop + 8,
                right = right - 8
            )
        }
    }

    private fun measureDialogChildIntrinsicWidth(widget: DesktopNativeWidget, maxAvailW: Int): Int {
        if (!widget.visible || widget.disposed) return 0
        if (isSpacerWidget(widget)) {
            return if (isFillSpacerWidget(widget)) 0 else getFixedSpacerWidth(widget)
        }
        return when (val impl = widget.attributes["nativeImpl"]) {
            is DesktopTextNativeImpl<*> -> {
                val txt = impl.getText().trim()
                if (txt.isEmpty()) 0
                else if (Win32NativeWindowHost.widgetHasClass(widget, "badge")) {
                    max(
                        measureSingleLineTextWidth(txt, 7),
                        Win32NativeWindowHost.measureSingleLineTextWidthWithFont(txt, 0)
                    ) + 16
                } else {
                    val tag = widgetTag(widget)
                    val isHeading = tag == "h1" || tag == "h2" || tag == "h3" || tag == "h4" || tag == "h5" || tag == "h6" ||
                        tag.contains("heading")
                    val fontKind = when {
                        tag == "h1" || tag == "h2" -> 3
                        tag == "h3" || tag == "h4" -> 2
                        isHeading -> 1
                        else -> 0
                    }
                    val avgCharPx = when {
                        tag == "h1" || tag == "h2" -> 11
                        tag == "h3" || tag == "h4" -> 9
                        else -> 7
                    }
                    val rawW = max(
                        measureSingleLineTextWidth(txt, avgCharPx),
                        Win32NativeWindowHost.measureSingleLineTextWidthWithFont(txt, fontKind)
                    ) + 16
                    val maxLines = impl.getMaxLines() ?: (widget.attributes["maxLines"] as? Number)?.toInt()
                    val forceSingleLine = maxLines == 1 ||
                        Win32NativeWindowHost.widgetHasClass(widget, "text-nowrap") ||
                        Win32NativeWindowHost.widgetHasClass(widget, "text-truncate") ||
                        widget.resolveCssValue("white-space")?.lowercase() == "nowrap"
                    if (forceSingleLine || isHeading) {
                        min(maxAvailW, rawW)
                    } else {
                        min(maxAvailW, min(rawW, 420))
                    }
                }
            }
            is DesktopButtonNativeImpl<*> -> measureButtonWidth(impl)
            is DesktopTextInputNativeImpl<*> -> min(maxAvailW, 300)
            is DesktopSelectNativeImpl<*> -> {
                val curVal = impl.getSelectedValue() ?: ""
                val maxOpt = impl.getOptions().maxOfOrNull {
                    max(
                        measureSingleLineTextWidth(it.second, 7),
                        Win32NativeWindowHost.measureSingleLineTextWidthWithFont(it.second, 0)
                    )
                } ?: max(
                    measureSingleLineTextWidth(curVal, 7),
                    Win32NativeWindowHost.measureSingleLineTextWidthWithFont(curVal, 0)
                )
                (maxOpt + 44).coerceIn(140, maxAvailW)
            }
            is DesktopCheckboxNativeImpl<*> -> {
                val lbl = impl.getLabel()
                val w = max(
                    measureSingleLineTextWidth(lbl, 7),
                    Win32NativeWindowHost.measureSingleLineTextWidthWithFont(lbl, 0)
                )
                (w + 36).coerceIn(80, maxAvailW)
            }
            is DesktopImageNativeImpl<*> -> {
                ((widget.attributes["width"] as? Number)?.toInt()
                    ?: widget.preferredSize?.width
                    ?: 48).coerceIn(16, maxAvailW)
            }
            is DesktopLazyListNativeImpl<*> -> {
                val minW = widget.resolveCssValue("min-width")?.removeSuffix("px")?.trim()?.toFloatOrNull()?.roundToInt() ?: 340
                min(maxAvailW, max(minW, 340))
            }
            else -> {
                if (widget.attributes["isLazyList"] == true) {
                    val minW = widget.resolveCssValue("min-width")?.removeSuffix("px")?.trim()?.toFloatOrNull()?.roundToInt() ?: 340
                    min(maxAvailW, max(minW, 340))
                } else {
                    val explicitMinW = widget.resolveCssValue("min-width")?.removeSuffix("px")?.trim()?.toFloatOrNull()?.roundToInt()
                        ?: widget.resolveCssValue("width")?.removeSuffix("px")?.trim()?.toFloatOrNull()?.roundToInt()
                        ?: 0
                    val kids = visibleChildrenOf(widget, emptySet())
                    val contentW = if (kids.isEmpty()) {
                        0
                    } else if (isHorizontalRowContainer(widget)) {
                        val nonFillSum = kids.filter { !isFillSpacerWidget(it) }.sumOf { c ->
                            if (isSpacerWidget(c)) getFixedSpacerWidth(c) else measureInlineChildWidth(c, maxAvailW) + 6
                        }
                        val fillGap = if (kids.any { isFillSpacerWidget(it) }) 24 else 0
                        min(maxAvailW, nonFillSum + fillGap)
                    } else {
                        kids.maxOfOrNull { measureDialogChildIntrinsicWidth(it, maxAvailW) } ?: 0
                    }
                    min(maxAvailW, max(explicitMinW, contentW))
                }
            }
        }
    }

    internal fun measureDialogIntrinsicSize(
        dialogWidget: DesktopNativeWidget,
        maxViewportWidth: Int,
        maxViewportHeight: Int
    ): Size {
        val allVisible = visibleChildrenOf(dialogWidget, emptySet())
        val footerWidget = allVisible.firstOrNull { Win32NativeWindowHost.widgetHasClass(it, "modal-footer") }
        val bodyChildren = if (footerWidget != null) {
            allVisible.filter { it !== footerWidget }
        } else {
            allVisible
        }
        val maxClientW = (maxViewportWidth - 32).coerceAtLeast(260)
        val maxClientH = (maxViewportHeight - 48).coerceAtLeast(220)
        val titleStr = (dialogWidget.attributes["title"] as? String)
            ?: (dialogWidget.attributes["nativeImpl"] as? DesktopDialogWindowNativeImpl<*>)?.getTitle()
            ?: ""
        val headerIntrinsicW = max(
            measureSingleLineTextWidth(titleStr, 8),
            Win32NativeWindowHost.measureSingleLineTextWidthWithFont(titleStr, 2)
        ) + 36 + 48
        val maxBodyAvailW = (maxClientW - 40).coerceAtLeast(220)
        val bodyIntrinsicW = (bodyChildren.maxOfOrNull { measureDialogChildIntrinsicWidth(it, maxBodyAvailW) } ?: 180) + 40
        val footerChildren = if (footerWidget != null) visibleChildrenOf(footerWidget, emptySet()) else emptyList()
        val footerIntrinsicW = if (footerChildren.isNotEmpty()) {
            val nonFillSum = footerChildren.filter { !isFillSpacerWidget(it) }.sumOf { c ->
                if (isSpacerWidget(c)) getFixedSpacerWidth(c) else measureInlineChildWidth(c, maxBodyAvailW) + 6
            }
            val fillGap = if (footerChildren.any { isFillSpacerWidget(it) }) 24 else 0
            nonFillSum + fillGap + 40
        } else {
            0
        }
        val intrinsicW = maxOf(headerIntrinsicW, bodyIntrinsicW, footerIntrinsicW)
            .coerceIn(240, maxClientW)
        val bodyAvailW = (intrinsicW - 40).coerceAtLeast(160)
        val measuredStackH = estimateVerticalStackHeight(
            container = dialogWidget,
            children = bodyChildren,
            titleBarWidgets = emptySet(),
            availW = bodyAvailW
        )
        val chromeAndPaddingH = if (footerWidget != null) 94 else 44
        val intrinsicH = (measuredStackH + chromeAndPaddingH)
            .coerceIn(140, maxClientH)
        return Size(intrinsicW, intrinsicH)
    }

    private fun widgetTag(widget: DesktopNativeWidget): String {
        val textTag = (widget.attributes["nativeImpl"] as? DesktopTextNativeImpl<*>)?.tagName?.takeIf { it.isNotBlank() }
        val attrTag = (widget.attributes["htmlTag"] as? String)?.takeIf { it.isNotBlank() }
            ?: (widget.attributes["data-genericui-tag"] as? String)?.takeIf { it.isNotBlank() }
        val prefixTag = (widget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>)?.componentClassPrefix?.takeIf { it.isNotBlank() }
        return (textTag ?: attrTag ?: prefixTag ?: widget.nativeClassName).lowercase()
    }

    private fun safeChildrenSnapshot(widget: DesktopNativeWidget?): Array<DesktopNativeWidget> {
        if (widget == null) return emptyArray()
        return runCatching { widget.children.toTypedArray() }.getOrElse { emptyArray() }
    }

    private fun visibleChildrenOf(
        widget: DesktopNativeWidget?,
        titleBarWidgets: Set<DesktopNativeWidget>
    ): List<DesktopNativeWidget> {
        if (widget == null || !widget.visible || widget.disposed) return emptyList()
        val useCache = titleBarWidgets.isEmpty()
        val mutVer = widget.subtreeMutationVersion
        if (useCache && (widget.attributes["_cachedVisibleKidsVer"] as? Long) == mutVer) {
            @Suppress("UNCHECKED_CAST")
            (widget.attributes["_cachedVisibleKids"] as? List<DesktopNativeWidget>)?.let { return it }
        }
        val rawKids = widget.children
        if (rawKids.isEmpty()) return emptyList()
        val result = ArrayList<DesktopNativeWidget>(rawKids.size)
        for (i in 0 until rawKids.size) {
            val child = rawKids.getOrNull(i) ?: continue
            if (child.visible &&
                !child.disposed &&
                (useCache || !Win32NativeWindowHost.isWidgetMountedInTitleBar(child, titleBarWidgets)) &&
                child.attributes["nativeImpl"] !is DesktopDialogWindowNativeImpl<*> &&
                child.attributes["data-modal-host"] != "true" &&
                child.resolveCssValue("display")?.lowercase() != "none"
            ) {
                result.add(child)
            }
        }
        if (useCache) {
            widget.attributes["_cachedVisibleKidsVer"] = mutVer
            widget.attributes["_cachedVisibleKids"] = result
        }
        return result
    }

    private fun unwrapShellContainers(
        root: DesktopNativeWidget,
        titleBarWidgets: Set<DesktopNativeWidget>
    ): DesktopNativeWidget {
        var cur = root
        var depth = 0
        while (depth < 10) {
            val impl = cur.attributes["nativeImpl"]
            if (impl is DesktopNavbarNativeImpl<*> ||
                impl is DesktopTabContainerNativeImpl<*> ||
                Win32NativeWindowHost.widgetHasClass(cur, "hybrid-column-layout") ||
                Win32NativeWindowHost.widgetHasClass(cur, "gui-hybrid-split-navigation") ||
                Win32NativeWindowHost.widgetHasClass(cur, "gui-collapsible-bottom-dock") ||
                Win32NativeWindowHost.widgetHasClass(cur, "gui-adaptive-card-layout") ||
                Win32NativeWindowHost.widgetHasClass(cur, "card") ||
                (Win32NativeWindowHost.widgetHasClass(cur, "align-items-center") &&
                    Win32NativeWindowHost.widgetHasClass(cur, "justify-content-center"))
            ) {
                return cur
            }
            val children = visibleChildrenOf(cur, titleBarWidgets)
            if (children.size == 1) {
                val single = children.first()
                val singleImpl = single.attributes["nativeImpl"]
                val isLeafControl = isInteractiveSurfaceWidget(single) ||
                    single.interactiveSurfaceSpec != null ||
                    singleImpl is DesktopImageNativeImpl<*> ||
                    singleImpl is DesktopButtonNativeImpl<*> ||
                    singleImpl is DesktopTextNativeImpl<*> ||
                    singleImpl is DesktopTextInputNativeImpl<*> ||
                    singleImpl is DesktopSelectNativeImpl<*> ||
                    singleImpl is DesktopCheckboxNativeImpl<*> ||
                    singleImpl is DesktopRangeSliderNativeImpl<*> ||
                    singleImpl is DesktopSpinnerNativeImpl<*>
                if (!isLeafControl) {
                    cur = single
                    depth++
                    continue
                }
            }
            break
        }
        return cur
    }

    private fun renderRootContainer(
        hwnd: HWND,
        hdc: HDC?,
        theme: Win32NativeWindowHost.SystemThemePalette,
        fontSmall: HFONT?,
        fontSmallBold: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        fontTitle: HFONT?,
        fontMetricBig: HFONT?,
        node: DesktopNativeWidget,
        titleBarWidgets: Set<DesktopNativeWidget>,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        if (!node.visible || node.disposed || right <= left || bottom <= top) return

        if (isInteractiveSurfaceWidget(node)) {
            Win32NativeWindowHost.renderInlineCanvasSurface(
                hdc = hdc,
                canvasWidget = node,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                theme = theme,
                registerAsPrimaryCanvas = true
            )
            return
        }

        // Direct specialized layout containers
        if (Win32NativeWindowHost.widgetHasClass(node, "gui-collapsible-bottom-dock")) {
            renderCollapsibleBottomDockLayout(
                hwnd, hdc, theme, fontSmall, fontSmallBold, fontNormal, fontBold, fontTitle, fontMetricBig,
                node, titleBarWidgets, left, top, right, bottom
            )
            return
        }
        if (Win32NativeWindowHost.widgetHasClass(node, "hybrid-column-layout")) {
            renderHybridColumnLayout(
                hwnd, hdc, theme, fontSmall, fontSmallBold, fontNormal, fontBold, fontTitle, fontMetricBig,
                node, titleBarWidgets, left, top, right, bottom
            )
            return
        }
        if (Win32NativeWindowHost.widgetHasClass(node, "gui-hybrid-split-navigation")) {
            renderHybridSplitNavigationLayout(
                hwnd, hdc, theme, fontSmall, fontSmallBold, fontNormal, fontBold, fontTitle, fontMetricBig,
                node, titleBarWidgets, left, top, right, bottom
            )
            return
        }
        if (node.attributes["nativeImpl"] is DesktopTabContainerNativeImpl<*>) {
            val tabH = 38
            val tabImpl = node.attributes["nativeImpl"] as? DesktopTabContainerNativeImpl<*>
            val tabPos = (node.attributes["tabPosition"] as? com.nexustools.genericui.component.tabs.TabPosition)
                ?: tabImpl?.getTabPosition()
                ?: com.nexustools.genericui.component.tabs.TabPosition.TOP
            val activeContentList = resolveActiveTabContentWidgets(node, titleBarWidgets)
            if (tabPos == com.nexustools.genericui.component.tabs.TabPosition.BOTTOM) {
                val tabTop = max(top, bottom - tabH)
                renderTabContentList(
                    hwnd = hwnd,
                    hdc = hdc,
                    theme = theme,
                    fontSmall = fontSmall,
                    fontSmallBold = fontSmallBold,
                    fontNormal = fontNormal,
                    fontBold = fontBold,
                    fontTitle = fontTitle,
                    fontMetricBig = fontMetricBig,
                    container = node,
                    bodyList = activeContentList,
                    titleBarWidgets = titleBarWidgets,
                    left = left,
                    top = top,
                    right = right,
                    bottom = tabTop
                )
                renderTabBarStrip(
                    hwnd = hwnd,
                    hdc = hdc,
                    theme = theme,
                    fontSmall = fontSmall,
                    fontBold = fontBold,
                    tabContainerWidget = node,
                    left = left,
                    top = tabTop,
                    right = right,
                    bottom = bottom
                )
            } else {
                val afterTabTop = min(bottom, top + tabH)
                renderTabBarStrip(
                    hwnd = hwnd,
                    hdc = hdc,
                    theme = theme,
                    fontSmall = fontSmall,
                    fontBold = fontBold,
                    tabContainerWidget = node,
                    left = left,
                    top = top,
                    right = right,
                    bottom = afterTabTop
                )
                renderTabContentList(
                    hwnd = hwnd,
                    hdc = hdc,
                    theme = theme,
                    fontSmall = fontSmall,
                    fontSmallBold = fontSmallBold,
                    fontNormal = fontNormal,
                    fontBold = fontBold,
                    fontTitle = fontTitle,
                    fontMetricBig = fontMetricBig,
                    container = node,
                    bodyList = activeContentList,
                    titleBarWidgets = titleBarWidgets,
                    left = left,
                    top = afterTabTop,
                    right = right,
                    bottom = bottom
                )
            }
            return
        }
        if (
            Win32NativeWindowHost.widgetHasClass(node, "fragment-stage") ||
            (
                (
                    Win32NativeWindowHost.widgetHasClass(node, "gui-layered-container") ||
                        Win32NativeWindowHost.widgetHasClass(node, "gui-layered-layout")
                    ) &&
                    !containsStageWithOverlay(node, titleBarWidgets)
                )
        ) {
            val layerChildren = visibleChildrenOf(node, titleBarWidgets).sortedBy { child ->
                (child.attributes["layerZIndex"] as? String)?.toIntOrNull()
                    ?: (child.attributes["zIndex"] as? Number)?.toInt()
                    ?: 0
            }
            for (layerChild in layerChildren) {
                val unwrappedLayer = unwrapShellContainers(layerChild, titleBarWidgets)
                renderRootContainer(
                    hwnd = hwnd,
                    hdc = hdc,
                    theme = theme,
                    fontSmall = fontSmall,
                    fontSmallBold = fontSmallBold,
                    fontNormal = fontNormal,
                    fontBold = fontBold,
                    fontTitle = fontTitle,
                    fontMetricBig = fontMetricBig,
                    node = unwrappedLayer,
                    titleBarWidgets = titleBarWidgets,
                    left = left,
                    top = top,
                    right = right,
                    bottom = bottom
                )
            }
            return
        }

        val children = visibleChildrenOf(node, titleBarWidgets)
        if (children.isEmpty()) return

        // Check if this container has a top Navbar + main body
        val navbarChild = children.firstOrNull { c ->
            c.attributes["nativeImpl"] is DesktopNavbarNativeImpl<*> ||
                Win32NativeWindowHost.widgetHasClass(c, "navbar") ||
                widgetTag(c).contains("navbar", ignoreCase = true)
        }
        val bottomNavChild = children.lastOrNull {
            it.attributes["nativeImpl"] is DesktopBottomNavigationBarNativeImpl<*> ||
                Win32NativeWindowHost.widgetHasClass(it, "bottom-navigation-bar")
        }

        var curTop = top
        var curBottom = bottom

        if (navbarChild != null) {
            val navH = 48
            renderNavbar(
                hwnd = hwnd,
                hdc = hdc,
                theme = theme,
                fontSmall = fontSmall,
                fontNormal = fontNormal,
                fontBold = fontBold,
                fontTitle = fontTitle,
                navbarWidget = navbarChild,
                titleBarWidgets = titleBarWidgets,
                left = left,
                top = curTop,
                right = right,
                bottom = min(curBottom, curTop + navH)
            )
            curTop = min(curBottom, curTop + navH)
        }

        if (bottomNavChild != null && bottomNavChild !== navbarChild) {
            val bNavH = 46
            renderBottomNavigationBar(
                hwnd = hwnd,
                hdc = hdc,
                theme = theme,
                fontSmall = fontSmall,
                fontBold = fontBold,
                bottomNavWidget = bottomNavChild,
                left = left,
                top = max(curTop, curBottom - bNavH),
                right = right,
                bottom = curBottom
            )
            curBottom = max(curTop, curBottom - bNavH)
        }

        val remainingChildren = children.filter { it !== navbarChild && it !== bottomNavChild }
        if (remainingChildren.isEmpty()) return

        // If the primary remaining child is a CollapsibleBottomDockLayout, HybridColumnLayout, or HybridSplitNavigationLayout,
        // allocate the full remaining viewport to it (and reserve space for any status footer or bottom player bar if visible).
        val dockOrHybrid = remainingChildren.firstOrNull {
            Win32NativeWindowHost.widgetHasClass(it, "gui-collapsible-bottom-dock") ||
                Win32NativeWindowHost.widgetHasClass(it, "hybrid-column-layout") ||
                Win32NativeWindowHost.widgetHasClass(it, "gui-hybrid-split-navigation") ||
                Win32NativeWindowHost.widgetHasClass(it, "music-main-vstack") ||
                Win32NativeWindowHost.widgetHasClass(it, "fragment-stage") ||
                Win32NativeWindowHost.widgetHasClass(it, "gui-layered-container")
        }
        if (dockOrHybrid != null) {
            val otherChildren = remainingChildren.filter { it !== dockOrHybrid }
            var mainBottom = curBottom
            if (otherChildren.isNotEmpty()) {
                val availFooterW = (right - left - 24).coerceAtLeast(120)
                val footerHeights = IntArray(otherChildren.size) { idx ->
                    val footer = otherChildren[idx]
                    if (Win32NativeWindowHost.widgetHasClass(footer, "music-player-bar")) {
                        estimateFlowNodeHeight(footer, titleBarWidgets, availFooterW).coerceIn(76, 110)
                    } else {
                        26
                    }
                }
                val footerH = footerHeights.sum()
                mainBottom = max(curTop + 100, curBottom - footerH)
                var fy = mainBottom
                for (idx in otherChildren.indices) {
                    val footer = otherChildren[idx]
                    val fh = footerHeights[idx]
                    val fBottom = min(curBottom, fy + fh)
                    if (Win32NativeWindowHost.widgetHasClass(footer, "music-player-bar")) {
                        Win32NativeWindowHost.fillRectColor(hdc, left, fy, right, fBottom, theme.bgPanel)
                        Win32NativeWindowHost.drawLineColor(hdc, left, fy, right, fy, theme.borderSubtle)
                        renderFlowNode(
                            hwnd, hdc, theme, fontSmall, fontSmallBold, fontNormal, fontBold, fontTitle, fontMetricBig,
                            footer, titleBarWidgets, left + 12, fy + 4, right - 12, fBottom - 2
                        )
                    } else {
                        renderFlowNode(
                            hwnd, hdc, theme, fontSmall, fontSmallBold, fontNormal, fontBold, fontTitle, fontMetricBig,
                            footer, titleBarWidgets, left + 12, fy, right - 12, fBottom
                        )
                    }
                    fy += fh
                }
            }
            renderRootContainer(
                hwnd, hdc, theme, fontSmall, fontSmallBold, fontNormal, fontBold, fontTitle, fontMetricBig,
                dockOrHybrid, titleBarWidgets, left, curTop, right, mainBottom
            )
            return
        }

        // If node itself is an interactive stage with overlaid HUD, render it directly
        val directStageAndOverlay = findStageAndOverlay(node, titleBarWidgets)
        if (directStageAndOverlay != null) {
            val (stageWidget, overlayWidget) = directStageAndOverlay
            val otherChildren = visibleChildrenOf(node, titleBarWidgets).filter {
                it !== stageWidget && it !== overlayWidget
            }
            val fullViewport = isFullViewportVideoStage(node, stageWidget, overlayWidget)
            renderVideoStageWithOverlay(
                hwnd = hwnd,
                hdc = hdc,
                theme = theme,
                fontSmall = fontSmall,
                fontSmallBold = fontSmallBold,
                fontNormal = fontNormal,
                fontBold = fontBold,
                fontTitle = fontTitle,
                videoWidget = stageWidget,
                overlayWidget = overlayWidget,
                otherChildren = otherChildren,
                titleBarWidgets = titleBarWidgets,
                left = if (fullViewport) 0 else left + 12,
                top = if (fullViewport) curTop else curTop + 10,
                right = if (fullViewport) right else right - 12,
                bottomLimit = curBottom,
                fullViewportMode = fullViewport
            )
            return
        }

        // Check if this is a full-column stage Card
        val isNodeItselfCard = Win32NativeWindowHost.widgetHasClass(node, "card") || widgetTag(node).contains("card")
        val isPlayerOrFullWidthCard = isZeroFrameStageCard(node, titleBarWidgets) ||
            Win32NativeWindowHost.widgetHasClass(node, "w-100") ||
            containsStageWithOverlay(node, titleBarWidgets)
        if (isNodeItselfCard && isPlayerOrFullWidthCard) {
            renderScrollableVerticalStack(
                hwnd = hwnd,
                hdc = hdc,
                theme = theme,
                fontSmall = fontSmall,
                fontSmallBold = fontSmallBold,
                fontNormal = fontNormal,
                fontBold = fontBold,
                fontTitle = fontTitle,
                fontMetricBig = fontMetricBig,
                container = node,
                children = listOf(node),
                titleBarWidgets = titleBarWidgets,
                left = left,
                top = curTop,
                right = right,
                bottom = curBottom,
                padX = 12,
                padY = 10
            )
            return
        }

        // Check if this is a centered connection/dialog card screen (like ServerConnectionFragment or ConnectingFragment)
        val isCenteredScreen = !isPlayerOrFullWidthCard && (
            Win32NativeWindowHost.widgetHasClass(node, "gui-adaptive-card-layout") ||
                isNodeItselfCard ||
                (children.size == 1 && (Win32NativeWindowHost.widgetHasClass(children.first(), "card") || widgetTag(children.first()).contains("card"))) ||
                (Win32NativeWindowHost.widgetHasClass(node, "align-items-center") &&
                    Win32NativeWindowHost.widgetHasClass(node, "justify-content-center"))
            )
        if (isCenteredScreen) {
            val cardNodes = if (isNodeItselfCard) listOf(node) else remainingChildren
            val availW = right - left
            val availH = curBottom - curTop
            val rawMaxWStr = node.resolveCssValue("max-width")
                ?: cardNodes.firstOrNull()?.resolveCssValue("max-width")
                ?: cardNodes.firstOrNull()?.let { visibleChildrenOf(it, titleBarWidgets).firstOrNull()?.resolveCssValue("max-width") }
            val configuredMaxW = rawMaxWStr?.removeSuffix("px")?.trim()?.toIntOrNull()
                ?: (node.attributes["maxWidthPx"] as? Number)?.toInt()
                ?: (cardNodes.firstOrNull()?.attributes?.get("maxWidthPx") as? Number)?.toInt()
                ?: 520
            val cardMaxW = min(configuredMaxW, availW - 48).coerceAtLeast(320)
            val centeredLeft = left + (availW - cardMaxW) / 2
            val centeredRight = centeredLeft + cardMaxW
            val estimatedCardH = estimateVerticalStackHeight(node, cardNodes, titleBarWidgets, cardMaxW) + 56
            val centeredTop = curTop + max(16, (availH - estimatedCardH) / 2)
            renderScrollableVerticalStack(
                hwnd = hwnd,
                hdc = hdc,
                theme = theme,
                fontSmall = fontSmall,
                fontSmallBold = fontSmallBold,
                fontNormal = fontNormal,
                fontBold = fontBold,
                fontTitle = fontTitle,
                fontMetricBig = fontMetricBig,
                container = node,
                children = cardNodes,
                titleBarWidgets = titleBarWidgets,
                left = centeredLeft,
                top = centeredTop,
                right = centeredRight,
                bottom = curBottom - 16,
                padX = 0,
                padY = 0
            )
            return
        }

        // Check if this container starts with a TabContainer
        val firstChild = remainingChildren.first()
        if (firstChild.attributes["nativeImpl"] is DesktopTabContainerNativeImpl<*>) {
            val tabH = 38
            renderTabBarStrip(
                hwnd = hwnd,
                hdc = hdc,
                theme = theme,
                fontSmall = fontSmall,
                fontBold = fontBold,
                tabContainerWidget = firstChild,
                left = left,
                top = curTop,
                right = right,
                bottom = min(curBottom, curTop + tabH)
            )
            val afterTabTop = min(curBottom, curTop + tabH)
            val siblingBodyList = remainingChildren.drop(1)
            val bodyList = if (siblingBodyList.isNotEmpty()) {
                siblingBodyList
            } else {
                resolveActiveTabContentWidgets(firstChild, titleBarWidgets)
            }
            if (bodyList.isNotEmpty()) {
                renderTabContentList(
                    hwnd = hwnd,
                    hdc = hdc,
                    theme = theme,
                    fontSmall = fontSmall,
                    fontSmallBold = fontSmallBold,
                    fontNormal = fontNormal,
                    fontBold = fontBold,
                    fontTitle = fontTitle,
                    fontMetricBig = fontMetricBig,
                    container = node,
                    bodyList = bodyList,
                    titleBarWidgets = titleBarWidgets,
                    left = left,
                    top = afterTabTop,
                    right = right,
                    bottom = curBottom
                )
            }
            return
        }

        // Check if this container ends with a bottom TabContainer (portrait small layout with tabs on bottom)
        val lastChild = remainingChildren.last()
        if (remainingChildren.size > 1 && lastChild.attributes["nativeImpl"] is DesktopTabContainerNativeImpl<*>) {
            val tabH = 38
            val tabTop = max(curTop, curBottom - tabH)
            val siblingBodyList = remainingChildren.dropLast(1)
            val bodyList = if (siblingBodyList.isNotEmpty()) {
                siblingBodyList
            } else {
                resolveActiveTabContentWidgets(lastChild, titleBarWidgets)
            }
            if (bodyList.isNotEmpty()) {
                renderTabContentList(
                    hwnd = hwnd,
                    hdc = hdc,
                    theme = theme,
                    fontSmall = fontSmall,
                    fontSmallBold = fontSmallBold,
                    fontNormal = fontNormal,
                    fontBold = fontBold,
                    fontTitle = fontTitle,
                    fontMetricBig = fontMetricBig,
                    container = node,
                    bodyList = bodyList,
                    titleBarWidgets = titleBarWidgets,
                    left = left,
                    top = curTop,
                    right = right,
                    bottom = tabTop
                )
            }
            renderTabBarStrip(
                hwnd = hwnd,
                hdc = hdc,
                theme = theme,
                fontSmall = fontSmall,
                fontBold = fontBold,
                tabContainerWidget = lastChild,
                left = left,
                top = tabTop,
                right = right,
                bottom = curBottom
            )
            return
        }

        renderScrollableVerticalStack(
            hwnd = hwnd,
            hdc = hdc,
            theme = theme,
            fontSmall = fontSmall,
            fontSmallBold = fontSmallBold,
            fontNormal = fontNormal,
            fontBold = fontBold,
            fontTitle = fontTitle,
            fontMetricBig = fontMetricBig,
            container = node,
            children = remainingChildren,
            titleBarWidgets = titleBarWidgets,
            left = left,
            top = curTop,
            right = right,
            bottom = curBottom,
            padX = 12,
            padY = 10
        )
    }

    private fun resolveActiveTabContentWidgets(
        tabContainerWidget: DesktopNativeWidget,
        titleBarWidgets: Set<DesktopNativeWidget>
    ): List<DesktopNativeWidget> {
        val nonTabChildren = visibleChildrenOf(tabContainerWidget, titleBarWidgets).filter { child ->
            child.attributes["nativeImpl"] !is DesktopTabNativeImpl<*> &&
                !Win32NativeWindowHost.widgetHasClass(child, "gui-hybrid-tab-placeholder") &&
                !Win32NativeWindowHost.widgetHasClass(child, "gui-split-nav-tab-placeholder")
        }
        val tabImpl = tabContainerWidget.attributes["nativeImpl"] as? DesktopTabContainerNativeImpl<*>
        val explicitSelectedWidget = tabImpl?.getSelectedContentWidget()?.takeIf { it.visible && !it.disposed }
        if (explicitSelectedWidget != null) {
            return listOf(explicitSelectedWidget)
        }
        if (nonTabChildren.size <= 1) return nonTabChildren
        val selectedIdx = tabImpl?.getSelectedTabIndex()
            ?: (tabContainerWidget.attributes["selectedTabIndex"] as? Number)?.toInt()
            ?: 0
        val chosen = nonTabChildren.getOrNull(selectedIdx.coerceIn(0, nonTabChildren.lastIndex))
            ?: nonTabChildren.first()
        return listOf(chosen)
    }

    private fun renderTabContentList(
        hwnd: HWND,
        hdc: HDC?,
        theme: Win32NativeWindowHost.SystemThemePalette,
        fontSmall: HFONT?,
        fontSmallBold: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        fontTitle: HFONT?,
        fontMetricBig: HFONT?,
        container: DesktopNativeWidget,
        bodyList: List<DesktopNativeWidget>,
        titleBarWidgets: Set<DesktopNativeWidget>,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        if (bodyList.isEmpty() || right <= left || bottom <= top) return
        if (bodyList.size == 1) {
            val single = unwrapShellContainers(bodyList.first(), titleBarWidgets)
            if (containsLazyListInSubtree(single, titleBarWidgets) ||
                Win32NativeWindowHost.widgetHasClass(single, "gui-layered-container") ||
                Win32NativeWindowHost.widgetHasClass(single, "gui-layered-layout")
            ) {
                renderBoundedSectionContainer(
                    hwnd = hwnd,
                    hdc = hdc,
                    theme = theme,
                    fontSmall = fontSmall,
                    fontSmallBold = fontSmallBold,
                    fontNormal = fontNormal,
                    fontBold = fontBold,
                    fontTitle = fontTitle,
                    fontMetricBig = fontMetricBig,
                    container = single,
                    titleBarWidgets = titleBarWidgets,
                    left = left,
                    top = top,
                    right = right,
                    bottom = bottom
                )
                return
            }
        }
        renderScrollableVerticalStack(
            hwnd = hwnd,
            hdc = hdc,
            theme = theme,
            fontSmall = fontSmall,
            fontSmallBold = fontSmallBold,
            fontNormal = fontNormal,
            fontBold = fontBold,
            fontTitle = fontTitle,
            fontMetricBig = fontMetricBig,
            container = container,
            children = bodyList,
            titleBarWidgets = titleBarWidgets,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            padX = 12,
            padY = 10
        )
    }

    private fun renderCollapsibleBottomDockLayout(
        hwnd: HWND,
        hdc: HDC?,
        theme: Win32NativeWindowHost.SystemThemePalette,
        fontSmall: HFONT?,
        fontSmallBold: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        fontTitle: HFONT?,
        fontMetricBig: HFONT?,
        dockLayoutWidget: DesktopNativeWidget,
        titleBarWidgets: Set<DesktopNativeWidget>,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        val dockSnapshot = safeChildrenSnapshot(dockLayoutWidget)
        val mainContentHost = dockSnapshot.firstOrNull {
            Win32NativeWindowHost.widgetHasClass(it, "gui-bottom-dock-main-content")
        } ?: dockSnapshot.firstOrNull { it.visible && !it.disposed }

        val bottomDockBar = dockSnapshot.firstOrNull {
            Win32NativeWindowHost.widgetHasClass(it, "gui-bottom-dock-bar")
        }
        val showBottomDock = bottomDockBar != null &&
            bottomDockBar.visible &&
            !bottomDockBar.disposed &&
            visibleChildrenOf(bottomDockBar, titleBarWidgets).isNotEmpty()

        val dockBarH = if (showBottomDock) 84 else 0
        val mainBottom = max(top + 80, bottom - dockBarH)

        if (mainContentHost != null && mainContentHost.visible && !mainContentHost.disposed) {
            val unwrappedMain = unwrapShellContainers(mainContentHost, titleBarWidgets)
            renderRootContainer(
                hwnd, hdc, theme, fontSmall, fontSmallBold, fontNormal, fontBold, fontTitle, fontMetricBig,
                unwrappedMain, titleBarWidgets, left, top, right, mainBottom
            )
        }

        if (showBottomDock && bottomDockBar != null) {
            val dockTop = mainBottom
            Win32NativeWindowHost.fillRectColor(hdc, left, dockTop, right, bottom, theme.bgPanel)
            Win32NativeWindowHost.drawLineColor(hdc, left, dockTop, right, dockTop, theme.borderSubtle)
            val dockChildren = visibleChildrenOf(bottomDockBar, titleBarWidgets)
            var dy = dockTop + 6
            for (child in dockChildren) {
                dy = renderFlowNode(
                    hwnd, hdc, theme, fontSmall, fontSmallBold, fontNormal, fontBold, fontTitle, fontMetricBig,
                    child, titleBarWidgets, left + 12, dy, right - 12, bottom - 4
                )
            }
        }
    }

    private fun resolveBootstrapColumnWeight(col: DesktopNativeWidget, totalWidthPx: Int): Float {
        // Ensure class tokens cache is populated
        Win32NativeWindowHost.widgetHasClass(col, "col")
        val tokens = col.cachedAllClassTokens
        fun findColSpan(prefix: String): Int? {
            for (span in 1..12) {
                if ("$prefix-$span" in tokens) return span
            }
            return null
        }
        val span = when {
            totalWidthPx >= 1200 -> findColSpan("col-xxl") ?: findColSpan("col-xl") ?: findColSpan("col-lg") ?: findColSpan("col-md") ?: findColSpan("col-sm") ?: findColSpan("col")
            totalWidthPx >= 992 -> findColSpan("col-lg") ?: findColSpan("col-md") ?: findColSpan("col-sm") ?: findColSpan("col")
            totalWidthPx >= 768 -> findColSpan("col-md") ?: findColSpan("col-sm") ?: findColSpan("col")
            totalWidthPx >= 576 -> findColSpan("col-sm") ?: findColSpan("col")
            else -> findColSpan("col")
        }
        if (span != null) {
            return span.toFloat()
        }
        val impl = col.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
        val growStr = col.resolveCssValue("flex-grow")
            ?: impl?.nativeWidget?.resolveCssValue("flex-grow")
        return growStr?.toFloatOrNull()?.coerceAtLeast(0.2f) ?: 1f
    }

    private fun renderHybridColumnLayout(
        hwnd: HWND,
        hdc: HDC?,
        theme: Win32NativeWindowHost.SystemThemePalette,
        fontSmall: HFONT?,
        fontSmallBold: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        fontTitle: HFONT?,
        fontMetricBig: HFONT?,
        hybridWidget: DesktopNativeWidget,
        titleBarWidgets: Set<DesktopNativeWidget>,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        val hybridSnapshot = safeChildrenSnapshot(hybridWidget)
        val columnsRow = hybridSnapshot.firstOrNull {
            Win32NativeWindowHost.widgetHasClass(it, "gui-hybrid-columns-row")
        }
        val singleStack = hybridSnapshot.firstOrNull {
            Win32NativeWindowHost.widgetHasClass(it, "gui-hybrid-single-stack")
        }

        if (columnsRow != null && columnsRow.visible && !columnsRow.disposed) {
            val cols = visibleChildrenOf(columnsRow, titleBarWidgets)
            if (cols.isNotEmpty()) {
                val totalW = (right - left).coerceAtLeast(1)
                val weights = cols.map { col -> resolveBootstrapColumnWeight(col, totalW) }
                val totalWeight = weights.sum().coerceAtLeast(0.1f)
                var curX = left
                for (i in cols.indices) {
                    val colStartUs = Win32NativeWindowHost.nowMicros()
                    val colWidget = cols[i]
                    val colRight = if (i == cols.lastIndex) {
                        right
                    } else {
                        (curX + (totalW * (weights[i] / totalWeight)).roundToInt()).coerceIn(curX + 120, right - 120)
                    }
                    val padL = colWidget.padding.left.coerceIn(0, 24)
                    val padR = colWidget.padding.right.coerceIn(0, 24)
                    val padT = colWidget.padding.top.coerceIn(0, 24)
                    val padB = colWidget.padding.bottom.coerceIn(0, 24)
                    val unwrappedCol = unwrapShellContainers(colWidget, titleBarWidgets)
                    val colSlotLeft = curX
                    val colSlotTop = top
                    val colSlotRight = colRight
                    val colSlotBottom = bottom
                    val hasLazyInCol = containsLazyListInSubtree(unwrappedCol, titleBarWidgets)
                    val stageRect = Win32NativeWindowHost.lastRenderedVideoStageRect
                    val hasStageInCol = containsStageWithOverlay(unwrappedCol, titleBarWidgets) ||
                        (!hasLazyInCol &&
                            cachedVideoWidget != null &&
                            stageRect[2] > stageRect[0] &&
                            stageRect[0] >= colSlotLeft &&
                            stageRect[2] <= colSlotRight)
                    val isHoveredInCol = Win32NativeWindowHost.isRectHovered(hwnd, colSlotLeft, colSlotTop, colSlotRight, colSlotBottom)
                    val canCacheCol = !isHoveredInCol &&
                        Win32NativeWindowHost.pressedWidget == null &&
                        !com.nexustools.genericui.event.PopupMouseCaptureManager.hasActivePopup &&
                        canUseSurfaceRegionCache(colSlotLeft, colSlotTop, colSlotRight, colSlotBottom)
                    val colCacheKey = if (canCacheCol) {
                        val colExtraState = if (!hasLazyInCol) {
                            scrollOffsetsY[unwrappedCol.hashCode()] ?: 0
                        } else {
                            var sHash = 0
                            for ((k, v) in scrollOffsetsY) {
                                sHash = sHash * 31 + k * 17 + v
                            }
                            sHash
                        }
                        val colMutVer = if (hasLazyInCol) {
                            unwrappedCol.subtreeMutationVersion xor computeLazySubtreeMutationHash(unwrappedCol, titleBarWidgets)
                        } else {
                            unwrappedCol.subtreeMutationVersion
                        }
                        StripSurfaceCacheKey(
                            widgetPtr = unwrappedCol.nativePointer,
                            mutVer = colMutVer,
                            left = colSlotLeft,
                            top = colSlotTop,
                            width = colSlotRight - colSlotLeft,
                            height = colSlotBottom - colSlotTop,
                            extraState = colExtraState,
                            hoveredWidgetPtr = 0L,
                            pressedWidgetPtr = 0L,
                            isDark = theme.isDark
                        )
                    } else {
                        null
                    }
                    if (colCacheKey != null) {
                        val cachedCol = cachedColumnSurfaces[colCacheKey]
                        if (cachedCol != null &&
                            tryBlitCachedRegion(
                                region = cachedCol,
                                left = colSlotLeft,
                                top = colSlotTop,
                                right = colSlotRight,
                                bottom = colSlotBottom,
                                cacheKey = colCacheKey
                            )
                        ) {
                            if (hasStageInCol) {
                                if (cachedCol.videoStageRect != null && cachedCol.videoStageRect.size >= 4) {
                                    Win32NativeWindowHost.lastRenderedVideoStageRect = cachedCol.videoStageRect.copyOf()
                                }
                                val curSwapCount = (cachedVideoWidget?.attributes?.get("swapCount") as? (() -> Long))?.invoke() ?: 0L
                                val curOverlayVer = cachedOverlayWidget?.subtreeMutationVersion ?: -1L
                                val curHudAlphaQ = (Win32NativeWindowHost.overlayHudCurrentAlpha.coerceIn(0f, 1f) * 100f).roundToInt()
                                val stageUnchanged = cachedCol.videoSwapCount >= 0L &&
                                    curSwapCount == cachedCol.videoSwapCount &&
                                    curOverlayVer == cachedCol.overlaySubtreeVer &&
                                    curHudAlphaQ == cachedCol.hudAlphaQuantized
                                if (!stageUnchanged) {
                                    renderCachedVideoStageFastPath(
                                        hwnd = hwnd,
                                        hdc = hdc,
                                        theme = theme,
                                        fontSmall = fontSmall,
                                        fontSmallBold = fontSmallBold,
                                        fontNormal = fontNormal,
                                        fontBold = fontBold,
                                        fontTitle = fontTitle
                                    )
                                }
                                lastBackBufferStripKeys[packRectKey(colSlotLeft, colSlotTop, colSlotRight, colSlotBottom)] = colCacheKey
                            }
                            if (i < cols.lastIndex && !Win32NativeWindowHost.activeMainBackBufferPreserved) {
                                Win32NativeWindowHost.drawLineColor(hdc, colRight, top, colRight, bottom, theme.borderSubtle)
                            }
                            Win32NativeWindowHost.currentPaintStageBreakdown += ",col$i=hit(${Win32NativeWindowHost.nowMicros() - colStartUs}us)"
                            curX = colRight + 1
                            continue
                        }
                    }
                    val colHitStartIdx = Win32NativeWindowHost.getHitRegionsCount()
                    val colScrollStartIdx = activeScrollRegions.size
                    if (Win32NativeWindowHost.activeMainBackBufferPreserved && !hasLazyInCol) {
                        invalidateBackBufferRectKeys(colSlotLeft, colSlotTop, colSlotRight, colSlotBottom)
                        Win32NativeWindowHost.fillRectColor(hdc, colSlotLeft, colSlotTop, colSlotRight, colSlotBottom, theme.bgBase)
                    }
                    renderRootContainer(
                        hwnd, hdc, theme, fontSmall, fontSmallBold, fontNormal, fontBold, fontTitle, fontMetricBig,
                        unwrappedCol, titleBarWidgets,
                        curX + padL,
                        top + padT,
                        (colRight - padR).coerceAtLeast(curX + padL + 60),
                        (bottom - padB).coerceAtLeast(top + padT + 60)
                    )
                    if (colCacheKey != null && !isResizeFrame && !(hasLazyInCol && hadFirstLazyPaintInFrame)) {
                        var reuseBuffer: ByteArray? = null
                        if (!cachedColumnSurfaces.containsKey(colCacheKey) && cachedColumnSurfaces.size >= MAX_CACHED_COLUMN_SURFACES) {
                            val oldestKey = cachedColumnSurfaces.keys.firstOrNull()
                            if (oldestKey != null) {
                                reuseBuffer = cachedColumnSurfaces.remove(oldestKey)?.bgra
                            }
                        }
                        val capturedStageRect = if (hasStageInCol) Win32NativeWindowHost.lastRenderedVideoStageRect.copyOf() else null
                        val capturedSwapCount = if (hasStageInCol) ((cachedVideoWidget?.attributes?.get("swapCount") as? (() -> Long))?.invoke() ?: 0L) else -1L
                        val capturedOverlayVer = if (hasStageInCol) (cachedOverlayWidget?.subtreeMutationVersion ?: -1L) else -1L
                        val capturedHudAlphaQ = if (hasStageInCol) (Win32NativeWindowHost.overlayHudCurrentAlpha.coerceIn(0f, 1f) * 100f).roundToInt() else -1
                        val capturedCol = captureCachedRegion(
                            left = colSlotLeft,
                            top = colSlotTop,
                            right = colSlotRight,
                            bottom = colSlotBottom,
                            hitStartIdx = colHitStartIdx,
                            scrollStartIdx = colScrollStartIdx,
                            reuseBuffer = reuseBuffer,
                            cacheKey = colCacheKey,
                            videoStageRect = capturedStageRect,
                            videoSwapCount = capturedSwapCount,
                            overlaySubtreeVer = capturedOverlayVer,
                            hudAlphaQuantized = capturedHudAlphaQ
                        )
                        if (capturedCol != null) {
                            cachedColumnSurfaces[colCacheKey] = capturedCol
                        }
                    }
                    if (i < cols.lastIndex) {
                        Win32NativeWindowHost.drawLineColor(hdc, colRight, top, colRight, bottom, theme.borderSubtle)
                    }
                    Win32NativeWindowHost.currentPaintStageBreakdown += ",col$i=miss(${Win32NativeWindowHost.nowMicros() - colStartUs}us)"
                    curX = colRight + 1
                }
                return
            }
        }

        val activeStack = if (singleStack != null && singleStack.visible) singleStack else hybridWidget
        renderScrollableVerticalStack(
            hwnd = hwnd,
            hdc = hdc,
            theme = theme,
            fontSmall = fontSmall,
            fontSmallBold = fontSmallBold,
            fontNormal = fontNormal,
            fontBold = fontBold,
            fontTitle = fontTitle,
            fontMetricBig = fontMetricBig,
            container = activeStack,
            children = visibleChildrenOf(activeStack, titleBarWidgets),
            titleBarWidgets = titleBarWidgets,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            padX = 12,
            padY = 10
        )
    }

    private fun containsLazyListInSubtree(
        widget: DesktopNativeWidget?,
        titleBarWidgets: Set<DesktopNativeWidget>,
        depth: Int = 0
    ): Boolean {
        if (widget == null || !widget.visible || widget.disposed || depth > 16) return false
        if (widget.attributes["nativeImpl"] is DesktopLazyListNativeImpl<*> || widget.attributes["isLazyList"] == true) {
            return true
        }
        for (child in visibleChildrenOf(widget, titleBarWidgets)) {
            if (containsLazyListInSubtree(child, titleBarWidgets, depth + 1)) return true
        }
        return false
    }

    private fun findFirstLazyListWidget(
        widget: DesktopNativeWidget?,
        titleBarWidgets: Set<DesktopNativeWidget>,
        depth: Int = 0
    ): DesktopNativeWidget? {
        if (widget == null || !widget.visible || widget.disposed || depth > 16) return null
        if (widget.attributes["nativeImpl"] is DesktopLazyListNativeImpl<*> || widget.attributes["isLazyList"] == true) {
            return widget
        }
        for (child in visibleChildrenOf(widget, titleBarWidgets)) {
            findFirstLazyListWidget(child, titleBarWidgets, depth + 1)?.let { return it }
        }
        return null
    }

    private fun computeLazySubtreeMutationHash(
        widget: DesktopNativeWidget?,
        titleBarWidgets: Set<DesktopNativeWidget>,
        depth: Int = 0
    ): Long {
        if (widget == null || !widget.visible || widget.disposed || depth > 16) return 0L
        val impl = widget.attributes["nativeImpl"]
        if (impl is DesktopLazyListNativeImpl<*> || widget.attributes["isLazyList"] == true) {
            val totalCount = (widget.attributes["totalItemCount"] as? Int)
                ?: (impl as? DesktopLazyListNativeImpl<*>)?.viewportEngine?.itemCount
                ?: 0
            val kids = visibleChildrenOf(widget, titleBarWidgets)
            var h = widget.subtreeMutationVersion * 31L + totalCount.toLong() * 17L + kids.size.toLong()
            for (c in kids) {
                h = h * 31L + c.nativePointer * 17L + c.subtreeMutationVersion
            }
            return h
        }
        var acc = 0L
        for (child in visibleChildrenOf(widget, titleBarWidgets)) {
            val ch = computeLazySubtreeMutationHash(child, titleBarWidgets, depth + 1)
            if (ch != 0L) {
                acc = acc * 31L + ch
            }
        }
        return acc
    }

    private fun renderBoundedSectionContainer(
        hwnd: HWND,
        hdc: HDC?,
        theme: Win32NativeWindowHost.SystemThemePalette,
        fontSmall: HFONT?,
        fontSmallBold: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        fontTitle: HFONT?,
        fontMetricBig: HFONT?,
        container: DesktopNativeWidget,
        titleBarWidgets: Set<DesktopNativeWidget>,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        if (!container.visible || container.disposed || right <= left || bottom <= top) return
        val impl = container.attributes["nativeImpl"]
        if (impl is DesktopLazyListNativeImpl<*> || container.attributes["isLazyList"] == true) {
            renderVirtualizedLazyListViewport(
                hwnd = hwnd,
                hdc = hdc,
                theme = theme,
                fontSmall = fontSmall,
                fontSmallBold = fontSmallBold,
                fontNormal = fontNormal,
                fontBold = fontBold,
                fontTitle = fontTitle,
                fontMetricBig = fontMetricBig,
                node = container,
                titleBarWidgets = titleBarWidgets,
                left = left,
                top = top,
                right = right,
                bottom = bottom
            )
            return
        }
        val children = visibleChildrenOf(container, titleBarWidgets)
        if (children.isEmpty()) return

        if (Win32NativeWindowHost.widgetHasClass(container, "gui-layered-container") ||
            Win32NativeWindowHost.widgetHasClass(container, "gui-layered-layout")
        ) {
            val baseLayer = children.first()
            renderBoundedSectionContainer(
                hwnd = hwnd,
                hdc = hdc,
                theme = theme,
                fontSmall = fontSmall,
                fontSmallBold = fontSmallBold,
                fontNormal = fontNormal,
                fontBold = fontBold,
                fontTitle = fontTitle,
                fontMetricBig = fontMetricBig,
                container = baseLayer,
                titleBarWidgets = titleBarWidgets,
                left = left,
                top = top,
                right = right,
                bottom = bottom
            )
            for (overlayLayer in children.drop(1)) {
                renderLayeredOverlayChild(
                    hwnd = hwnd,
                    hdc = hdc,
                    theme = theme,
                    fontSmall = fontSmall,
                    fontSmallBold = fontSmallBold,
                    fontNormal = fontNormal,
                    fontBold = fontBold,
                    fontTitle = fontTitle,
                    fontMetricBig = fontMetricBig,
                    overlayLayer = overlayLayer,
                    titleBarWidgets = titleBarWidgets,
                    left = left,
                    top = top,
                    right = right,
                    bottom = bottom
                )
            }
            return
        }

        // Vertical flex column with fixed header children + a flex-grow LazyList or LayeredContainer child
        val padL = container.padding.left.coerceIn(0, 20)
        val padR = container.padding.right.coerceIn(0, 20)
        val padT = container.padding.top.coerceIn(0, 20)
        val padB = container.padding.bottom.coerceIn(0, 20)
        val innerL = left + if (padL > 0) padL else 8
        val innerR = (right - if (padR > 0) padR else 8).coerceAtLeast(innerL + 40)
        var curY = top + if (padT > 0) padT else 6
        val innerBottom = (bottom - if (padB > 0) padB else 6).coerceAtLeast(curY + 40)

        val lazyIdx = children.indexOfFirst { containsLazyListInSubtree(it, titleBarWidgets) }
        if (lazyIdx >= 0) {
            if (lazyIdx > 0) {
                val hdrTop = curY
                var hdrMutVer = container.nativePointer * 31L + lazyIdx
                for (i in 0 until lazyIdx) {
                    val hc = children[i]
                    hdrMutVer = hdrMutVer * 31L + hc.nativePointer * 17L + hc.subtreeMutationVersion
                }
                val hdrWidth = innerR - innerL
                val cachedHdrH = if ((container.attributes["_cachedLazyHdrVer"] as? Long) == hdrMutVer) {
                    (container.attributes["_cachedLazyHdrH_$hdrWidth"] as? Int)
                        ?: if ((container.attributes["_cachedLazyHdrW"] as? Int) == hdrWidth) {
                            container.attributes["_cachedLazyHdrH"] as? Int
                        } else {
                            null
                        }
                } else {
                    null
                }
                val estHdrBottom = if (cachedHdrH != null && cachedHdrH > 0) {
                    (hdrTop + cachedHdrH).coerceAtMost(innerBottom)
                } else {
                    0
                }
                val isHoveredInLazyHdr = estHdrBottom > hdrTop &&
                    Win32NativeWindowHost.isRectHovered(hwnd, innerL, hdrTop, innerR, estHdrBottom)
                val hoveredLazyHdrPtr = if (isHoveredInLazyHdr) (Win32NativeWindowHost.hoveredWidget?.nativePointer ?: 0L) else 0L
                val pressedLazyHdrPtr = Win32NativeWindowHost.pressedWidget?.nativePointer ?: 0L
                val lazyHdrCacheKey = if (estHdrBottom > hdrTop && canUseSurfaceRegionCache(innerL, hdrTop, innerR, estHdrBottom)) {
                    StripSurfaceCacheKey(
                        widgetPtr = container.nativePointer xor 0x5A17L,
                        mutVer = hdrMutVer,
                        left = innerL,
                        top = hdrTop,
                        width = innerR - innerL,
                        height = estHdrBottom - hdrTop,
                        extraState = (Win32NativeWindowHost.focusedWidget?.nativePointer ?: 0L).toInt(),
                        hoveredWidgetPtr = hoveredLazyHdrPtr,
                        pressedWidgetPtr = pressedLazyHdrPtr,
                        isDark = theme.isDark
                    )
                } else {
                    null
                }
                val cachedLazyHdr = if (lazyHdrCacheKey != null) cachedSectionSurfaces[lazyHdrCacheKey] else null
                if (cachedLazyHdr != null &&
                    tryBlitCachedRegion(
                        region = cachedLazyHdr,
                        left = innerL,
                        top = hdrTop,
                        right = innerR,
                        bottom = estHdrBottom,
                        cacheKey = lazyHdrCacheKey
                    )
                ) {
                    curY = estHdrBottom
                } else {
                    val hdrHitStartIdx = Win32NativeWindowHost.getHitRegionsCount()
                    if (Win32NativeWindowHost.activeMainBackBufferPreserved && estHdrBottom > hdrTop) {
                        Win32NativeWindowHost.fillRectColor(hdc, innerL, hdrTop, innerR, estHdrBottom, theme.bgBase)
                    }
                    for (i in 0 until lazyIdx) {
                        val hdr = children[i]
                        curY = renderFlowNode(
                            hwnd = hwnd,
                            hdc = hdc,
                            theme = theme,
                            fontSmall = fontSmall,
                            fontSmallBold = fontSmallBold,
                            fontNormal = fontNormal,
                            fontBold = fontBold,
                            fontTitle = fontTitle,
                            fontMetricBig = fontMetricBig,
                            node = hdr,
                            titleBarWidgets = titleBarWidgets,
                            left = innerL,
                            top = curY,
                            right = innerR,
                            bottomLimit = innerBottom
                        )
                        curY += 6
                    }
                    val actualHdrH = (curY - hdrTop).coerceAtLeast(0)
                    container.attributes["_cachedLazyHdrVer"] = hdrMutVer
                    container.attributes["_cachedLazyHdrW"] = hdrWidth
                    container.attributes["_cachedLazyHdrH"] = actualHdrH
                    container.attributes["_cachedLazyHdrH_$hdrWidth"] = actualHdrH
                    val actualHdrBottom = (hdrTop + actualHdrH).coerceAtMost(innerBottom)
                    if (!isResizeFrame && actualHdrBottom > hdrTop && canUseSurfaceRegionCache(innerL, hdrTop, innerR, actualHdrBottom)) {
                        val captureKey = StripSurfaceCacheKey(
                            widgetPtr = container.nativePointer xor 0x5A17L,
                            mutVer = hdrMutVer,
                            left = innerL,
                            top = hdrTop,
                            width = innerR - innerL,
                            height = actualHdrBottom - hdrTop,
                            extraState = (Win32NativeWindowHost.focusedWidget?.nativePointer ?: 0L).toInt(),
                            hoveredWidgetPtr = if (Win32NativeWindowHost.isRectHovered(hwnd, innerL, hdrTop, innerR, actualHdrBottom)) {
                                Win32NativeWindowHost.hoveredWidget?.nativePointer ?: 0L
                            } else {
                                0L
                            },
                            pressedWidgetPtr = pressedLazyHdrPtr,
                            isDark = theme.isDark
                        )
                        var reuseBuffer: ByteArray? = null
                        if (!cachedSectionSurfaces.containsKey(captureKey) && cachedSectionSurfaces.size >= MAX_CACHED_SECTION_SURFACES) {
                            val oldestKey = cachedSectionSurfaces.keys.firstOrNull()
                            if (oldestKey != null) {
                                reuseBuffer = cachedSectionSurfaces.remove(oldestKey)?.bgra
                            }
                        }
                        val capturedHdr = captureCachedRegion(
                            left = innerL,
                            top = hdrTop,
                            right = innerR,
                            bottom = actualHdrBottom,
                            hitStartIdx = hdrHitStartIdx,
                            reuseBuffer = reuseBuffer,
                            cacheKey = captureKey
                        )
                        if (capturedHdr != null) {
                            cachedSectionSurfaces[captureKey] = capturedHdr
                        }
                    }
                }
            }
            val lazyContainer = children[lazyIdx]
            renderBoundedSectionContainer(
                hwnd = hwnd,
                hdc = hdc,
                theme = theme,
                fontSmall = fontSmall,
                fontSmallBold = fontSmallBold,
                fontNormal = fontNormal,
                fontBold = fontBold,
                fontTitle = fontTitle,
                fontMetricBig = fontMetricBig,
                container = lazyContainer,
                titleBarWidgets = titleBarWidgets,
                left = innerL,
                top = curY.coerceAtMost(innerBottom - 40),
                right = innerR,
                bottom = innerBottom
            )
            return
        }

        renderScrollableVerticalStack(
            hwnd = hwnd,
            hdc = hdc,
            theme = theme,
            fontSmall = fontSmall,
            fontSmallBold = fontSmallBold,
            fontNormal = fontNormal,
            fontBold = fontBold,
            fontTitle = fontTitle,
            fontMetricBig = fontMetricBig,
            container = container,
            children = children,
            titleBarWidgets = titleBarWidgets,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            padX = 10,
            padY = 8
        )
    }

    private fun renderLayeredOverlayChild(
        hwnd: HWND,
        hdc: HDC?,
        theme: Win32NativeWindowHost.SystemThemePalette,
        fontSmall: HFONT?,
        fontSmallBold: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        fontTitle: HFONT?,
        fontMetricBig: HFONT?,
        overlayLayer: DesktopNativeWidget,
        titleBarWidgets: Set<DesktopNativeWidget>,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        if (!overlayLayer.visible || overlayLayer.disposed) return
        val layerImpl = overlayLayer.attributes["nativeImpl"]
        val bottomOffsetPx = overlayLayer.resolveCssValue("bottom")?.removeSuffix("px")?.trim()?.toFloatOrNull()?.roundToInt()
        val rightOffsetPx = overlayLayer.resolveCssValue("right")?.removeSuffix("px")?.trim()?.toFloatOrNull()?.roundToInt()
        val isFloatingAnchor = bottomOffsetPx != null || rightOffsetPx != null || layerImpl is DesktopButtonNativeImpl<*>
        val overlayKids = visibleChildrenOf(overlayLayer, titleBarWidgets)
        val floatingBtnWidget = when {
            layerImpl is DesktopButtonNativeImpl<*> -> overlayLayer
            isFloatingAnchor && overlayKids.size == 1 && overlayKids.first().attributes["nativeImpl"] is DesktopButtonNativeImpl<*> -> overlayKids.first()
            overlayKids.size == 1 && overlayKids.first().let {
                it.attributes["nativeImpl"] is DesktopButtonNativeImpl<*> &&
                    (it.resolveCssValue("bottom") != null || it.resolveCssValue("right") != null || overlayLayer.resolveCssValue("pointer-events") == "none")
            } -> overlayKids.first()
            else -> null
        }
        if (floatingBtnWidget != null) {
            val btnImpl = floatingBtnWidget.attributes["nativeImpl"] as DesktopButtonNativeImpl<*>
            val bOff = bottomOffsetPx
                ?: floatingBtnWidget.resolveCssValue("bottom")?.removeSuffix("px")?.trim()?.toFloatOrNull()?.roundToInt()
                ?: 20
            val rOff = rightOffsetPx
                ?: floatingBtnWidget.resolveCssValue("right")?.removeSuffix("px")?.trim()?.toFloatOrNull()?.roundToInt()
                ?: 20
            val btnW = measureButtonWidth(btnImpl).coerceIn(72, 220)
            val btnH = 34
            val bR = (right - rOff).coerceAtLeast(left + btnW)
            val bL = (bR - btnW).coerceAtLeast(left + 4)
            val bB = (bottom - bOff).coerceAtLeast(top + btnH)
            val bT = (bB - btnH).coerceAtLeast(top + 4)
            SelectObject(hdc, fontSmallBold ?: fontBold ?: fontSmall)
            Win32NativeWindowHost.drawButtonControl(
                hdc = hdc,
                theme = theme,
                label = btnImpl.getText(),
                icon = btnImpl.getIcon(),
                left = bL,
                top = bT,
                right = bR,
                bottom = bB,
                primary = btnImpl.getType() == BootstrapType.PRIMARY,
                widget = floatingBtnWidget,
                hwndForHover = hwnd
            )
            Win32NativeWindowHost.addHitRegion(
                Win32NativeWindowHost.HitRegion(
                    left = bL,
                    top = bT,
                    right = bR,
                    bottom = bB,
                    tooltip = nonRedundantTooltip(btnImpl.getTooltipText(), btnImpl.getText()),
                    widget = floatingBtnWidget,
                    onClick = { _, _ -> btnImpl.dispatchClick() }
                )
            )
            return
        }

        val layerVisuals = (layerImpl as? DesktopStyledContainerNativeImpl<*>)?.getStyledVisuals()
        val firstChildVisuals = (overlayKids.firstOrNull()?.attributes?.get("nativeImpl") as? DesktopStyledContainerNativeImpl<*>)?.getStyledVisuals()
        val hasBackdropOverlay = layerVisuals?.backgroundColor != null ||
            firstChildVisuals?.backgroundColor != null ||
            overlayLayer.resolveCssValue("background-color") != null ||
            overlayLayer.resolveCssValue("backdrop-filter") != null ||
            (Win32NativeWindowHost.widgetHasClass(overlayLayer, "align-items-center") &&
                Win32NativeWindowHost.widgetHasClass(overlayLayer, "justify-content-center")) ||
            overlayLayer.resolveCssValue("justify-content")?.lowercase() == "center"
        val fillCol = layerVisuals?.backgroundColor
            ?: firstChildVisuals?.backgroundColor
            ?: if (hasBackdropOverlay) {
                if (theme.isDark) RgbaColor(14, 14, 18, 210) else RgbaColor(245, 245, 248, 218)
            } else null
        if (fillCol != null || layerVisuals?.borderColor != null || layerVisuals?.backgroundGradient != null) {
            Win32NativeWindowHost.drawAntialiasedRoundedRectRgba(
                hdc = hdc,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                radiusPx = layerVisuals?.borderRadiusPx ?: 8f,
                fillColor = fillCol,
                borderColor = layerVisuals?.borderColor,
                borderWidthPx = layerVisuals?.borderWidthPx ?: 0f,
                opacity = layerVisuals?.opacity ?: overlayLayer.opacity,
                gradient = layerVisuals?.backgroundGradient
            )
        }
        val overlayTxt = Win32NativeWindowHost.extractWidgetText(overlayLayer).trim()
        if (overlayTxt.isNotEmpty() && hasBackdropOverlay && overlayKids.none {
                it.attributes["nativeImpl"] is DesktopButtonNativeImpl<*> ||
                    visibleChildrenOf(it, titleBarWidgets).any { sub -> sub.attributes["nativeImpl"] is DesktopButtonNativeImpl<*> }
            }
        ) {
            SelectObject(hdc, fontBold ?: fontNormal)
            val fg = layerVisuals?.textColor?.let { Win32NativeWindowHost.rgbaToColorRef(it) } ?: theme.textPrimary
            Win32NativeWindowHost.drawTextInRect(
                hdc = hdc,
                text = overlayTxt,
                left = left + 12,
                top = top,
                right = right - 12,
                bottom = bottom,
                colorRef = fg,
                center = true
            )
            Win32NativeWindowHost.addHitRegion(
                Win32NativeWindowHost.HitRegion(
                    left = left,
                    top = top,
                    right = right,
                    bottom = bottom,
                    widget = overlayLayer
                )
            )
        } else {
            val estH = estimateVerticalStackHeight(overlayLayer, overlayKids, titleBarWidgets, (right - left - 32).coerceAtLeast(100))
            val startY = if (hasBackdropOverlay) {
                top + ((bottom - top - estH) / 2).coerceAtLeast(16)
            } else {
                top
            }
            renderFlowNode(
                hwnd = hwnd,
                hdc = hdc,
                theme = theme,
                fontSmall = fontSmall,
                fontSmallBold = fontSmallBold,
                fontNormal = fontNormal,
                fontBold = fontBold,
                fontTitle = fontTitle,
                fontMetricBig = fontMetricBig,
                node = overlayLayer,
                titleBarWidgets = titleBarWidgets,
                left = left + if (hasBackdropOverlay) 24 else 0,
                top = startY,
                right = right - if (hasBackdropOverlay) 24 else 0,
                bottomLimit = bottom
            )
        }
    }

    private fun renderVirtualizedLazyListViewport(
        hwnd: HWND,
        hdc: HDC?,
        theme: Win32NativeWindowHost.SystemThemePalette,
        fontSmall: HFONT?,
        fontSmallBold: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        fontTitle: HFONT?,
        fontMetricBig: HFONT?,
        node: DesktopNativeWidget,
        titleBarWidgets: Set<DesktopNativeWidget>,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Int {
        val viewportH = (bottom - top).coerceAtLeast(64)
        val availW = (right - left - 10).coerceAtLeast(48)
        val key = node.hashCode()
        val impl = node.attributes["nativeImpl"]
        val hostSyncVer = node.attributes["hostSyncedScrollVer"] as? Long
        if (hostSyncVer != null && hostSyncVer != (node.attributes["_lastAppliedHostSyncVer"] as? Long)) {
            node.attributes["_lastAppliedHostSyncVer"] = hostSyncVer
            val syncedScroll = ((node.attributes["hostSyncedScrollOffsetPx"] as? Number)?.toInt() ?: 0).coerceAtLeast(0)
            scrollOffsetsY[key] = syncedScroll
            node.attributes["_lastDispatchedScrollPx"] = syncedScroll.toFloat()
            val syncedTotal = ((node.attributes["totalContentExtentPx"] as? Number)?.toFloat() ?: 0f).roundToInt()
            if (syncedTotal > 0) {
                maxScrollOffsetsY[key] = max(0, syncedTotal - viewportH)
            }
        }
        val prevScroll = scrollOffsetsY[key] ?: 0
        val prevMax = maxScrollOffsetsY[key] ?: 0
        val scrollY = if (prevMax > 0) prevScroll.coerceIn(0, prevMax) else prevScroll.coerceAtLeast(0)

        val localScrollPx = scrollY.toFloat()
        val viewportExtentPx = viewportH.toFloat()
        val lastScroll = (node.attributes["_lastDispatchedScrollPx"] as? Float) ?: -1f
        val lastExtent = (node.attributes["_lastDispatchedExtentPx"] as? Float) ?: -1f
        val curCount = (node.attributes["totalItemCount"] as? Number)?.toInt() ?: -1
        val lastCount = (node.attributes["_lastDispatchedItemCount"] as? Int) ?: -2
        val curMaterializedCount = visibleChildrenOf(node, titleBarWidgets).size
        val prevChildCount = curMaterializedCount
        val prevMutVer = node.subtreeMutationVersion
        val alreadyMaterializedForInitialZeroScroll = lastExtent < 0f &&
            localScrollPx <= 0.5f &&
            curCount >= 0 &&
            (curMaterializedCount >= curCount || curMaterializedCount * 56f >= viewportExtentPx + 56f)
        if (alreadyMaterializedForInitialZeroScroll) {
            node.attributes["_lastDispatchedScrollPx"] = localScrollPx
            node.attributes["_lastDispatchedExtentPx"] = viewportExtentPx
            node.attributes["_lastDispatchedItemCount"] = curCount
            node.attributes["_lastAppliedHostSyncVer"] = node.attributes["hostSyncedScrollVer"]
        } else {
            val needsMoreItemsForExtent = viewportExtentPx > lastExtent + 0.5f &&
                curMaterializedCount < curCount &&
                curMaterializedCount * 56f < localScrollPx + viewportExtentPx + 56f
            if (kotlin.math.abs(localScrollPx - lastScroll) > 0.5f ||
                lastExtent < 0f ||
                needsMoreItemsForExtent ||
                curCount != lastCount
            ) {
                node.attributes["_lastDispatchedScrollPx"] = localScrollPx
                node.attributes["_lastDispatchedExtentPx"] = max(lastExtent, viewportExtentPx)
                node.attributes["_lastDispatchedItemCount"] = curCount
                if (impl is DesktopLazyListNativeImpl<*>) {
                    impl.dispatchHostViewportScroll(localScrollPx, viewportExtentPx)
                } else {
                    @Suppress("UNCHECKED_CAST")
                    (node.attributes["onHostViewportScroll"] as? ((Float, Float) -> Unit))?.invoke(
                        localScrollPx,
                        viewportExtentPx
                    )
                }
                val postSyncVer = node.attributes["hostSyncedScrollVer"] as? Long
                if (postSyncVer != null && postSyncVer != hostSyncVer) {
                    val syncedScroll = ((node.attributes["hostSyncedScrollOffsetPx"] as? Number)?.toInt() ?: 0).coerceAtLeast(0)
                    scrollOffsetsY[key] = syncedScroll
                    node.attributes["_lastDispatchedScrollPx"] = syncedScroll.toFloat()
                    val syncedTotal = ((node.attributes["totalContentExtentPx"] as? Number)?.toFloat() ?: 0f).roundToInt()
                    if (syncedTotal > 0) {
                        maxScrollOffsetsY[key] = max(0, syncedTotal - viewportH)
                    }
                }
                node.attributes["_lastAppliedHostSyncVer"] = postSyncVer
            }
        }

        val currentLazyChildren = visibleChildrenOf(node, titleBarWidgets)
        var lazyChildrenSig = currentLazyChildren.size.toLong() * 31L + curCount.toLong()
        for (c in currentLazyChildren) {
            lazyChildrenSig = lazyChildrenSig * 31L + c.nativePointer * 17L + c.subtreeMutationVersion
        }
        val lastRenderedChildSig = (node.attributes["_lastRenderedLazyChildSig"] as? Long) ?: -1L
        node.attributes["_lastRenderedLazyChildSig"] = lazyChildrenSig
        val childrenChangedInPass = currentLazyChildren.size != prevChildCount ||
            node.subtreeMutationVersion != prevMutVer ||
            lazyChildrenSig != lastRenderedChildSig
        if (childrenChangedInPass) {
            hadFirstLazyPaintInFrame = true
            lastLazyViewportStateKey = null
            lastLazyViewportBaseKey = null
            invalidateBackBufferRectKeys(left, top, right, bottom)
        }

        val lastScrolledY = (node.attributes["_lastRenderedLazyScrollY"] as? Int) ?: -1
        node.attributes["_lastRenderedLazyScrollY"] = scrollY
        val firstPaintOfThisLazy = (lastScrolledY == -1)
        if (firstPaintOfThisLazy || (childrenChangedInPass && scrollY != 0)) {
            hadFirstLazyPaintInFrame = true
        }
        val effectiveMutVer = node.subtreeMutationVersion xor lazyChildrenSig
        val canLookupLazyViewport = !firstPaintOfThisLazy && canUseSurfaceRegionCache(left, top, right, bottom)
        val canCaptureLazyViewport = canLookupLazyViewport && !isResizeFrame && (scrollY == 0 || (!childrenChangedInPass && scrollY == lastScrolledY))
        val isHoveredInLazy = Win32NativeWindowHost.isRectHovered(hwnd, left, top, right, bottom)
        val hoveredLazyPtr = if (isHoveredInLazy) (Win32NativeWindowHost.hoveredWidget?.nativePointer ?: 0L) else 0L
        val pressedLazyPtr = Win32NativeWindowHost.pressedWidget?.nativePointer ?: 0L
        val baseLazyCacheKey = if (canLookupLazyViewport) {
            StripSurfaceCacheKey(
                widgetPtr = node.nativePointer,
                mutVer = effectiveMutVer,
                left = left,
                top = top,
                width = right - left,
                height = bottom - top,
                extraState = scrollY,
                hoveredWidgetPtr = 0L,
                pressedWidgetPtr = 0L,
                isDark = theme.isDark
            )
        } else {
            null
        }
        val effectiveLazyStateKey = if (canLookupLazyViewport) {
            StripSurfaceCacheKey(
                widgetPtr = node.nativePointer,
                mutVer = effectiveMutVer,
                left = left,
                top = top,
                width = right - left,
                height = bottom - top,
                extraState = scrollY,
                hoveredWidgetPtr = hoveredLazyPtr,
                pressedWidgetPtr = pressedLazyPtr,
                isDark = theme.isDark
            )
        } else {
            null
        }
        val lazyRectKey = packRectKey(left, top, right, bottom)
        val leadingSpacer = ((node.attributes["leadingSpacerPx"] as? Number)?.toFloat() ?: 0f)
            .roundToInt().coerceAtLeast(0)
        val trailingSpacer = ((node.attributes["trailingSpacerPx"] as? Number)?.toFloat() ?: 0f)
            .roundToInt().coerceAtLeast(0)
        val itemRight = right - 10

        fun renderHoveredRowOverlayInViewport(): Pair<Int, Int>? {
            if (!isHoveredInLazy && pressedLazyPtr == 0L) return null
            val lazyChildren = visibleChildrenOf(node, titleBarWidgets)
            var curY = top - scrollY + leadingSpacer
            val prevClipTop = currentClipTop
            val prevClipBottom = currentClipBottom
            currentClipTop = top
            currentClipBottom = bottom
            var minRelTop = Int.MAX_VALUE
            var maxRelBottom = Int.MIN_VALUE
            for (child in lazyChildren) {
                val estH = estimateFlowNodeHeight(child, titleBarWidgets, availW).coerceAtLeast(20)
                if (curY + estH > top && curY < bottom) {
                    val visTop = max(top, curY)
                    val visBottom = min(bottom, curY + estH)
                    val isThisRowActive = Win32NativeWindowHost.isRectHovered(hwnd, left, visTop, itemRight, visBottom) ||
                        Win32NativeWindowHost.hoveredWidget === child ||
                        Win32NativeWindowHost.pressedWidget === child
                    if (isThisRowActive) {
                        Win32NativeWindowHost.removeHitRegionsInRect(left, visTop, itemRight, visBottom)
                        val needsClip = curY < top || curY + estH > bottom
                        val savedRowDc = if (needsClip) Win32NativeWindowHost.pushClipRect(hdc, left, top, right, bottom) else 0
                        renderFlowNode(
                            hwnd = hwnd,
                            hdc = hdc,
                            theme = theme,
                            fontSmall = fontSmall,
                            fontSmallBold = fontSmallBold,
                            fontNormal = fontNormal,
                            fontBold = fontBold,
                            fontTitle = fontTitle,
                            fontMetricBig = fontMetricBig,
                            node = child,
                            titleBarWidgets = titleBarWidgets,
                            left = left,
                            top = curY,
                            right = itemRight,
                            bottomLimit = bottom + 64
                        )
                        if (needsClip) {
                            Win32NativeWindowHost.popClipRect(hdc, savedRowDc)
                        }
                        val relT = (visTop - top).coerceIn(0, bottom - top)
                        val relB = (visBottom - top).coerceIn(relT, bottom - top)
                        if (relT < minRelTop) minRelTop = relT
                        if (relB > maxRelBottom) maxRelBottom = relB
                    }
                    curY += estH + 6
                } else {
                    curY += estH + 6
                }
            }
            currentClipTop = prevClipTop
            currentClipBottom = prevClipBottom
            return if (maxRelBottom > minRelTop) (minRelTop to maxRelBottom) else null
        }

        if (effectiveLazyStateKey != null &&
            Win32NativeWindowHost.activeMainBackBufferPreserved &&
            lastBackBufferStripKeys[lazyRectKey] == effectiveLazyStateKey &&
            lastLazyViewportStateKey == effectiveLazyStateKey
        ) {
            for (hr in lastLazyViewportHitRegions) {
                Win32NativeWindowHost.addHitRegion(
                    Win32NativeWindowHost.HitRegion(
                        left = left + hr.relLeft,
                        top = top + hr.relTop,
                        right = left + hr.relRight,
                        bottom = top + hr.relBottom,
                        tooltip = hr.tooltip,
                        widget = hr.widget,
                        isTextInput = hr.isTextInput,
                        onClick = hr.onClick,
                        onDrag = hr.onDrag,
                        onRelease = hr.onRelease
                    )
                )
            }
            val cachedContentH = (node.attributes["scrollContentHeightPx"] as? Number)?.toInt() ?: 0
            if (cachedContentH > viewportH + 2) {
                activeScrollRegions.add(intArrayOf(left, top, right, bottom, key))
            }
            return bottom
        }

        if (baseLazyCacheKey != null) {
            val cachedBaseLazy = cachedLazyViewportSurfaces[baseLazyCacheKey]
            if (cachedBaseLazy != null) {
                val hitStart = Win32NativeWindowHost.getHitRegionsCount()
                val canIncrementalRowRestore = Win32NativeWindowHost.activeMainBackBufferPreserved &&
                    lastLazyViewportBaseKey == baseLazyCacheKey &&
                    lastBackBufferStripKeys.containsKey(lazyRectKey)
                val blitted = if (canIncrementalRowRestore) {
                    val sliceOk = if (lastLazyHoveredRelBottom > lastLazyHoveredRelTop) {
                        tryBlitCachedRegion(
                            region = cachedBaseLazy,
                            left = left,
                            top = top,
                            right = right,
                            bottom = bottom,
                            rowStart = lastLazyHoveredRelTop,
                            rowEnd = lastLazyHoveredRelBottom,
                            restoreHitRegions = false,
                            cacheKey = null
                        )
                    } else {
                        true
                    }
                    if (sliceOk) {
                        for (hr in cachedBaseLazy.hitRegions) {
                            val hrTop = max(top, top + hr.relTop)
                            val hrBottom = min(bottom, top + hr.relBottom)
                            if (hrBottom > hrTop) {
                                Win32NativeWindowHost.addHitRegion(
                                    Win32NativeWindowHost.HitRegion(
                                        left = left + hr.relLeft,
                                        top = hrTop,
                                        right = left + hr.relRight,
                                        bottom = hrBottom,
                                        tooltip = hr.tooltip,
                                        widget = hr.widget,
                                        isTextInput = hr.isTextInput,
                                        onClick = hr.onClick,
                                        onDrag = hr.onDrag,
                                        onRelease = hr.onRelease
                                    )
                                )
                            }
                        }
                        for (sr in cachedBaseLazy.scrollRegions) {
                            activeScrollRegions.add(
                                intArrayOf(
                                    left + sr[0],
                                    top + sr[1],
                                    left + sr[2],
                                    top + sr[3],
                                    sr[4]
                                )
                            )
                        }
                    }
                    sliceOk
                } else {
                    tryBlitCachedRegion(
                        region = cachedBaseLazy,
                        left = left,
                        top = top,
                        right = right,
                        bottom = bottom,
                        cacheKey = if (hoveredLazyPtr == 0L && pressedLazyPtr == 0L) baseLazyCacheKey else null
                    )
                }
                if (blitted) {
                    val hoveredRelSpan = if (hoveredLazyPtr != 0L || pressedLazyPtr != 0L) {
                        renderHoveredRowOverlayInViewport()
                    } else {
                        null
                    }
                    lastLazyViewportBaseKey = baseLazyCacheKey
                    lastLazyHoveredRelTop = hoveredRelSpan?.first ?: -1
                    lastLazyHoveredRelBottom = hoveredRelSpan?.second ?: -1
                    if (effectiveLazyStateKey != null) {
                        lastBackBufferStripKeys[lazyRectKey] = effectiveLazyStateKey
                        lastLazyViewportStateKey = effectiveLazyStateKey
                        val hitEnd = Win32NativeWindowHost.getHitRegionsCount()
                        val list = ArrayList<RelativeHitRegion>((hitEnd - hitStart).coerceAtLeast(0))
                        for (idx in hitStart until hitEnd) {
                            val hr = Win32NativeWindowHost.getHitRegionAt(idx)
                            list.add(
                                RelativeHitRegion(
                                    relLeft = hr.left - left,
                                    relTop = hr.top - top,
                                    relRight = hr.right - left,
                                    relBottom = hr.bottom - top,
                                    tooltip = hr.tooltip,
                                    widget = hr.widget,
                                    isTextInput = hr.isTextInput,
                                    onClick = hr.onClick,
                                    onDrag = hr.onDrag,
                                    onRelease = hr.onRelease
                                )
                            )
                        }
                        lastLazyViewportHitRegions = list
                    }
                    val cachedContentH = (node.attributes["scrollContentHeightPx"] as? Number)?.toInt() ?: 0
                    if (cachedContentH > viewportH + 2) {
                        activeScrollRegions.add(intArrayOf(left, top, right, bottom, key))
                    }
                    return bottom
                }
            }
        }
        val lazyHitStartIdx = Win32NativeWindowHost.getHitRegionsCount()
        val lazyScrollStartIdx = activeScrollRegions.size
        if (Win32NativeWindowHost.activeMainBackBufferPreserved) {
            Win32NativeWindowHost.fillRectColor(hdc, left, top, right, bottom, theme.bgBase)
        }

        val savedDc = Win32NativeWindowHost.pushClipRect(hdc, left, top, right, bottom)
        val prevClipTop = currentClipTop
        val prevClipBottom = currentClipBottom
        currentClipTop = top
        currentClipBottom = bottom
        val prevFirstPaintLazy = isFirstPaintOfLazyList
        isFirstPaintOfLazyList = firstPaintOfThisLazy
        suppressRowHoverForBaseViewportCapture = baseLazyCacheKey != null
        val prevDeferRowCapture = deferRowCaptureInLazyLoop
        if (!prevDeferRowCapture) {
            pendingRowCaptures.clear()
        }
        deferRowCaptureInLazyLoop = true

        val lazyChildren = visibleChildrenOf(node, titleBarWidgets)
        var curY = top - scrollY + leadingSpacer
        for (child in lazyChildren) {
            val estH = estimateFlowNodeHeight(child, titleBarWidgets, availW).coerceAtLeast(20)
            if (curY + estH > top && curY < bottom) {
                val renderedBottom = renderFlowNode(
                    hwnd = hwnd,
                    hdc = hdc,
                    theme = theme,
                    fontSmall = fontSmall,
                    fontSmallBold = fontSmallBold,
                    fontNormal = fontNormal,
                    fontBold = fontBold,
                    fontTitle = fontTitle,
                    fontMetricBig = fontMetricBig,
                    node = child,
                    titleBarWidgets = titleBarWidgets,
                    left = left,
                    top = curY,
                    right = itemRight,
                    bottomLimit = bottom + 64
                )
                curY = max(curY + estH, renderedBottom) + 6
            } else {
                curY += estH + 6
            }
        }
        deferRowCaptureInLazyLoop = prevDeferRowCapture
        isFirstPaintOfLazyList = prevFirstPaintLazy
        val measuredContentH = (curY + scrollY - top) + trailingSpacer
        val reportedExtentH = ((node.attributes["totalContentExtentPx"] as? Number)?.toFloat() ?: 0f).roundToInt()
        val totalContentH = max(measuredContentH, reportedExtentH)
        val scrollNeeded = totalContentH > viewportH + 2
        val maxScroll = if (scrollNeeded) max(0, totalContentH - viewportH) else 0
        maxScrollOffsetsY[key] = maxScroll
        if (scrollY > maxScroll) {
            scrollOffsetsY[key] = maxScroll
        }
        node.attributes["scrollContentHeightPx"] = totalContentH
        node.attributes["scrollViewportHeightPx"] = viewportH
        node.attributes["scrollOffsetYPx"] = scrollY
        if (scrollNeeded) {
            activeScrollRegions.add(intArrayOf(left, top, right, bottom, key))
        }

        if (scrollNeeded && maxScroll > 0 && right - left > 40) {
            val railLeft = right - 7
            val railRight = right - 2
            val railTop = top + 4
            val railBottom = bottom - 4
            val railH = max(16, railBottom - railTop)
            Win32NativeWindowHost.fillRectColor(hdc, railLeft, railTop, railRight, railBottom, theme.bgPanel)
            val thumbH = max(24, (railH * viewportH) / totalContentH.coerceAtLeast(1))
            val thumbTravel = max(1, railH - thumbH)
            val thumbTop = railTop + ((scrollY.toFloat() / maxScroll.toFloat()) * thumbTravel).roundToInt()
            Win32NativeWindowHost.drawRoundedRect(
                hdc = hdc,
                left = railLeft,
                top = thumbTop,
                right = railRight,
                bottom = min(railBottom, thumbTop + thumbH),
                radius = 2,
                fillColor = theme.borderStrong,
                borderColor = theme.borderStrong
            )
        }

        suppressRowHoverForBaseViewportCapture = false
        Win32NativeWindowHost.popClipRect(hdc, savedDc)
        currentClipTop = prevClipTop
        currentClipBottom = prevClipBottom
        if (!prevDeferRowCapture && pendingRowCaptures.isNotEmpty()) {
            Win32NativeWindowHost.flushGdiIfOverlapping(left, top, right, bottom)
            for (dc in pendingRowCaptures) {
                val evicted = if (!cachedCompositeRowRegions.containsKey(dc.rowCacheKey) && cachedCompositeRowRegions.size >= MAX_CACHED_ROW_REGIONS) {
                    val firstKey = cachedCompositeRowRegions.keys.firstOrNull()
                    if (firstKey != null) cachedCompositeRowRegions.remove(firstKey) else null
                } else {
                    cachedCompositeRowRegions[dc.rowCacheKey]
                }
                val capturedRow = captureCachedRegion(
                    left = dc.left,
                    top = dc.visTop,
                    right = dc.right,
                    bottom = dc.visBottom,
                    hitStartIdx = dc.hitStartIdx,
                    hitEndIdxOverride = dc.hitEndIdx,
                    reuseBuffer = evicted?.bgra,
                    alreadyFlushedGdi = true
                )
                if (capturedRow != null) {
                    cachedCompositeRowRegions[dc.rowCacheKey] = capturedRow
                    if (dc.postHasActiveBgra != dc.rowCacheKey.hasActiveBgra) {
                        cachedCompositeRowRegions[dc.rowCacheKey.copy(hasActiveBgra = dc.postHasActiveBgra)] = capturedRow
                    }
                    if (dc.fullRowCacheKey != null &&
                        dc.fullRowCacheKey.hoveredRightBtnPtr == 0L &&
                        dc.fullRowCacheKey.pressedRightBtnPtr == 0L
                    ) {
                        cachedFullRowByWidgetPtr[fullRowPtrKey(dc.widgetPtr, dc.fullRowCacheKey.isRowHovered)] = dc.fullRowCacheKey to capturedRow
                    }
                }
            }
            pendingRowCaptures.clear()
        }
        if (canCaptureLazyViewport && baseLazyCacheKey != null) {
            var reuseBuffer: ByteArray? = null
            if (!cachedLazyViewportSurfaces.containsKey(baseLazyCacheKey) && cachedLazyViewportSurfaces.size >= MAX_CACHED_LAZY_VIEWPORT_SURFACES) {
                val oldestKey = cachedLazyViewportSurfaces.keys.firstOrNull()
                if (oldestKey != null) {
                    reuseBuffer = cachedLazyViewportSurfaces.remove(oldestKey)?.bgra
                }
            }
            val captured = captureCachedRegion(
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                hitStartIdx = lazyHitStartIdx,
                scrollStartIdx = lazyScrollStartIdx,
                reuseBuffer = reuseBuffer,
                cacheKey = baseLazyCacheKey
            )
            if (captured != null) {
                cachedLazyViewportSurfaces[baseLazyCacheKey] = captured
                val hoveredRelSpan = if (hoveredLazyPtr != 0L || pressedLazyPtr != 0L) {
                    renderHoveredRowOverlayInViewport()
                } else {
                    null
                }
                lastLazyViewportBaseKey = baseLazyCacheKey
                lastLazyHoveredRelTop = hoveredRelSpan?.first ?: -1
                lastLazyHoveredRelBottom = hoveredRelSpan?.second ?: -1
                if (effectiveLazyStateKey != null) {
                    lastBackBufferStripKeys[lazyRectKey] = effectiveLazyStateKey
                    lastLazyViewportStateKey = effectiveLazyStateKey
                    val hitEnd = Win32NativeWindowHost.getHitRegionsCount()
                    val list = ArrayList<RelativeHitRegion>((hitEnd - lazyHitStartIdx).coerceAtLeast(0))
                    for (idx in lazyHitStartIdx until hitEnd) {
                        val hr = Win32NativeWindowHost.getHitRegionAt(idx)
                        list.add(
                            RelativeHitRegion(
                                relLeft = hr.left - left,
                                relTop = hr.top - top,
                                relRight = hr.right - left,
                                relBottom = hr.bottom - top,
                                tooltip = hr.tooltip,
                                widget = hr.widget,
                                isTextInput = hr.isTextInput,
                                onClick = hr.onClick,
                                onDrag = hr.onDrag,
                                onRelease = hr.onRelease
                            )
                        )
                    }
                    lastLazyViewportHitRegions = list
                }
            }
        }
        return bottom
    }

    private fun renderHybridSplitNavigationLayout(
        hwnd: HWND,
        hdc: HDC?,
        theme: Win32NativeWindowHost.SystemThemePalette,
        fontSmall: HFONT?,
        fontSmallBold: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        fontTitle: HFONT?,
        fontMetricBig: HFONT?,
        splitNavWidget: DesktopNativeWidget,
        titleBarWidgets: Set<DesktopNativeWidget>,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        var curTop = top
        var curBottom = bottom

        val splitSnapshot = safeChildrenSnapshot(splitNavWidget)
        val topTabWidget = splitSnapshot.firstOrNull {
            it.attributes["nativeImpl"] is DesktopTabContainerNativeImpl<*> ||
                Win32NativeWindowHost.widgetHasClass(it, "gui-split-nav-tabs") ||
                Win32NativeWindowHost.widgetHasClass(it, "gui-hybrid-split-top-tabs")
        }
        val headerSlot = splitSnapshot.firstOrNull {
            Win32NativeWindowHost.widgetHasClass(it, "gui-split-nav-header") ||
                Win32NativeWindowHost.widgetHasClass(it, "gui-hybrid-split-header-slot")
        }
        val contentHost = splitSnapshot.firstOrNull {
            Win32NativeWindowHost.widgetHasClass(it, "gui-split-nav-content") ||
                Win32NativeWindowHost.widgetHasClass(it, "gui-hybrid-split-content-host")
        }
        val bottomNavWidget = splitSnapshot.firstOrNull {
            it.attributes["nativeImpl"] is DesktopBottomNavigationBarNativeImpl<*> ||
                Win32NativeWindowHost.widgetHasClass(it, "gui-split-nav-bottom-bar") ||
                Win32NativeWindowHost.widgetHasClass(it, "gui-hybrid-split-bottom-nav")
        }

        if (topTabWidget != null && topTabWidget.visible && !topTabWidget.disposed) {
            val t0 = Win32NativeWindowHost.nowMicros()
            val tabH = 38
            renderTabBarStrip(
                hwnd = hwnd,
                hdc = hdc,
                theme = theme,
                fontSmall = fontSmall,
                fontBold = fontBold,
                tabContainerWidget = topTabWidget,
                left = left,
                top = curTop,
                right = right,
                bottom = min(curBottom, curTop + tabH)
            )
            Win32NativeWindowHost.currentPaintStageBreakdown += ",tab=${Win32NativeWindowHost.nowMicros() - t0}us"
            curTop = min(curBottom, curTop + tabH)
        }

        if (headerSlot != null && headerSlot.visible && !headerSlot.disposed) {
            val headerChildren = visibleChildrenOf(headerSlot, titleBarWidgets)
            val neededH = estimateVerticalStackHeight(
                headerSlot,
                headerChildren,
                titleBarWidgets,
                (right - left - 20).coerceAtLeast(80)
            )
            if (headerChildren.isNotEmpty() && neededH > 0) {
                val slotH = (neededH + 10).coerceIn(38, 84)
                val slotBottom = min(curBottom, curTop + slotH)
                val isHoveredInHeader = Win32NativeWindowHost.isRectHovered(hwnd, left, curTop, right, slotBottom)
                val hoveredHdrPtr = if (isHoveredInHeader) (Win32NativeWindowHost.hoveredWidget?.nativePointer ?: 0L) else 0L
                val pressedHdrPtr = Win32NativeWindowHost.pressedWidget?.nativePointer ?: 0L
                val hdrCacheKey = if (canUseSurfaceRegionCache(left, curTop, right, slotBottom)) {
                    StripSurfaceCacheKey(
                        widgetPtr = headerSlot.nativePointer,
                        mutVer = headerSlot.subtreeMutationVersion,
                        left = left,
                        top = curTop,
                        width = right - left,
                        height = slotBottom - curTop,
                        extraState = (Win32NativeWindowHost.focusedWidget?.nativePointer ?: 0L).toInt(),
                        hoveredWidgetPtr = hoveredHdrPtr,
                        pressedWidgetPtr = pressedHdrPtr,
                        isDark = theme.isDark
                    )
                } else {
                    null
                }
                val cachedHdr = if (hdrCacheKey != null) cachedSectionSurfaces[hdrCacheKey] else null
                if (cachedHdr != null &&
                    tryBlitCachedRegion(
                        region = cachedHdr,
                        left = left,
                        top = curTop,
                        right = right,
                        bottom = slotBottom,
                        cacheKey = hdrCacheKey
                    )
                ) {
                    curTop = slotBottom
                } else {
                    val hdrHitStartIdx = Win32NativeWindowHost.getHitRegionsCount()
                    Win32NativeWindowHost.fillRectColor(hdc, left, curTop, right, slotBottom, theme.bgPanel)
                    Win32NativeWindowHost.drawLineColor(hdc, left, slotBottom - 1, right, slotBottom - 1, theme.borderSubtle)
                    var hy = curTop + 6
                    for (hc in headerChildren) {
                        hy = renderFlowNode(
                            hwnd, hdc, theme, fontSmall, fontSmallBold, fontNormal, fontBold, fontTitle, fontMetricBig,
                            hc, titleBarWidgets, left + 10, hy, right - 10, slotBottom
                        )
                    }
                    if (hdrCacheKey != null && !isResizeFrame) {
                        var reuseBuffer: ByteArray? = null
                        if (!cachedSectionSurfaces.containsKey(hdrCacheKey) && cachedSectionSurfaces.size >= MAX_CACHED_SECTION_SURFACES) {
                            val oldestKey = cachedSectionSurfaces.keys.firstOrNull()
                            if (oldestKey != null) {
                                reuseBuffer = cachedSectionSurfaces.remove(oldestKey)?.bgra
                            }
                        }
                        val capturedHdr = captureCachedRegion(
                            left = left,
                            top = curTop,
                            right = right,
                            bottom = slotBottom,
                            hitStartIdx = hdrHitStartIdx,
                            reuseBuffer = reuseBuffer,
                            cacheKey = hdrCacheKey
                        )
                        if (capturedHdr != null) {
                            cachedSectionSurfaces[hdrCacheKey] = capturedHdr
                        }
                    }
                    curTop = slotBottom
                }
            }
        }

        if (bottomNavWidget != null && bottomNavWidget.visible && !bottomNavWidget.disposed) {
            val bNavH = 44
            renderBottomNavigationBar(
                hwnd = hwnd,
                hdc = hdc,
                theme = theme,
                fontSmall = fontSmall,
                fontBold = fontBold,
                bottomNavWidget = bottomNavWidget,
                left = left,
                top = max(curTop, curBottom - bNavH),
                right = right,
                bottom = curBottom
            )
            curBottom = max(curTop, curBottom - bNavH)
        }

        val targetContent = contentHost ?: splitNavWidget
        val contentChildren = visibleChildrenOf(targetContent, titleBarWidgets).filter {
            it !== topTabWidget && it !== headerSlot && it !== bottomNavWidget
        }
        if (contentChildren.size == 1 && containsLazyListInSubtree(contentChildren.first(), titleBarWidgets)) {
            renderBoundedSectionContainer(
                hwnd = hwnd,
                hdc = hdc,
                theme = theme,
                fontSmall = fontSmall,
                fontSmallBold = fontSmallBold,
                fontNormal = fontNormal,
                fontBold = fontBold,
                fontTitle = fontTitle,
                fontMetricBig = fontMetricBig,
                container = contentChildren.first(),
                titleBarWidgets = titleBarWidgets,
                left = left,
                top = curTop,
                right = right,
                bottom = curBottom
            )
            return
        }
        if (containsLazyListInSubtree(targetContent, titleBarWidgets)) {
            renderBoundedSectionContainer(
                hwnd = hwnd,
                hdc = hdc,
                theme = theme,
                fontSmall = fontSmall,
                fontSmallBold = fontSmallBold,
                fontNormal = fontNormal,
                fontBold = fontBold,
                fontTitle = fontTitle,
                fontMetricBig = fontMetricBig,
                container = targetContent,
                titleBarWidgets = titleBarWidgets,
                left = left,
                top = curTop,
                right = right,
                bottom = curBottom
            )
            return
        }
        renderScrollableVerticalStack(
            hwnd = hwnd,
            hdc = hdc,
            theme = theme,
            fontSmall = fontSmall,
            fontSmallBold = fontSmallBold,
            fontNormal = fontNormal,
            fontBold = fontBold,
            fontTitle = fontTitle,
            fontMetricBig = fontMetricBig,
            container = targetContent,
            children = contentChildren,
            titleBarWidgets = titleBarWidgets,
            left = left,
            top = curTop,
            right = right,
            bottom = curBottom,
            padX = 10,
            padY = 8
        )
    }

    private fun renderNavbar(
        hwnd: HWND,
        hdc: HDC?,
        theme: Win32NativeWindowHost.SystemThemePalette,
        fontSmall: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        fontTitle: HFONT?,
        navbarWidget: DesktopNativeWidget,
        titleBarWidgets: Set<DesktopNativeWidget>,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        val isHoveredInNavbar = Win32NativeWindowHost.isRectHovered(hwnd, left, top, right, bottom)
        val hoveredNavPtr = if (isHoveredInNavbar) (Win32NativeWindowHost.hoveredWidget?.nativePointer ?: 0L) else 0L
        val pressedNavPtr = Win32NativeWindowHost.pressedWidget?.nativePointer ?: 0L
        val canCacheNavbar = canUseSurfaceRegionCache(left, top, right, bottom)
        val navCacheKey = if (canCacheNavbar) {
            StripSurfaceCacheKey(
                widgetPtr = navbarWidget.nativePointer,
                mutVer = navbarWidget.subtreeMutationVersion,
                left = left,
                top = top,
                width = right - left,
                height = bottom - top,
                extraState = 0,
                hoveredWidgetPtr = hoveredNavPtr,
                pressedWidgetPtr = pressedNavPtr,
                isDark = theme.isDark
            )
        } else {
            null
        }
        if (navCacheKey != null) {
            val cachedRegion = cachedNavbarSurfaces[navCacheKey]
            if (cachedRegion != null && tryBlitCachedRegion(cachedRegion, left, top, right, bottom, cacheKey = navCacheKey)) {
                return
            }
        }
        val navHitStartIdx = Win32NativeWindowHost.getHitRegionsCount()

        val navImpl = navbarWidget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
        val bgColor = navImpl?.getEffectiveBackgroundColor()?.let { Win32NativeWindowHost.rgbaToColorRef(it) }
            ?: theme.bgPanel
        Win32NativeWindowHost.fillRectColor(hdc, left, top, right, bottom, bgColor)
        Win32NativeWindowHost.drawLineColor(hdc, left, bottom - 1, right, bottom - 1, theme.borderSubtle)

        val navbarNative = navImpl as? DesktopNavbarNativeImpl<*>
        val brandStr = navbarNative?.getBrandTitle()?.takeIf { it.isNotBlank() }
            ?: (navbarWidget.attributes["brandTitle"] as? String)?.takeIf { it.isNotBlank() }

        // Flatten row children inside Navbar (typically Navbar -> barRow: HStack -> [Heading, Badge, Spacer, Buttons...])
        val rowContainer = visibleChildrenOf(navbarWidget, titleBarWidgets).firstOrNull {
            safeChildrenSnapshot(it).isNotEmpty()
        } ?: navbarWidget
        val items = visibleChildrenOf(rowContainer, titleBarWidgets)

        var leftX = left + 14
        val buttonsRightToLeft = mutableListOf<DesktopNativeWidget>()
        val leftItems = mutableListOf<DesktopNativeWidget>()
        var seenSpacer = false

        fun collectLeaves(nodeItem: DesktopNativeWidget, targetList: MutableList<DesktopNativeWidget>) {
            val nImpl = nodeItem.attributes["nativeImpl"]
            if (nImpl is DesktopButtonNativeImpl<*> ||
                nImpl is DesktopTextNativeImpl<*> ||
                nImpl is DesktopSelectNativeImpl<*>
            ) {
                targetList.add(nodeItem)
                return
            }
            for (sub in visibleChildrenOf(nodeItem, titleBarWidgets)) {
                if (!isSpacerWidget(sub)) {
                    collectLeaves(sub, targetList)
                }
            }
        }

        for (item in items) {
            if (isSpacerWidget(item)) {
                seenSpacer = true
                continue
            }
            val itemImpl = item.attributes["nativeImpl"]
            val isRightGroupContainer = itemImpl !is DesktopButtonNativeImpl<*> &&
                itemImpl !is DesktopTextNativeImpl<*> &&
                itemImpl !is DesktopSelectNativeImpl<*> &&
                (seenSpacer ||
                    Win32NativeWindowHost.widgetHasClass(item, "ms-auto") ||
                    Win32NativeWindowHost.widgetHasClass(item, "navbar-nav") ||
                    Win32NativeWindowHost.widgetHasClass(item, "justify-content-end") ||
                    visibleChildrenOf(item, titleBarWidgets).any {
                        it.attributes["nativeImpl"] is DesktopButtonNativeImpl<*> ||
                            it.attributes["nativeImpl"] is DesktopSelectNativeImpl<*>
                    })
            if (isRightGroupContainer) {
                seenSpacer = true
                collectLeaves(item, buttonsRightToLeft)
            } else if (seenSpacer || itemImpl is DesktopButtonNativeImpl<*> || itemImpl is DesktopSelectNativeImpl<*>) {
                seenSpacer = true
                collectLeaves(item, buttonsRightToLeft)
            } else {
                collectLeaves(item, leftItems)
            }
        }

        // Draw brand title & left badges
        if (leftItems.isEmpty() && !brandStr.isNullOrBlank()) {
            SelectObject(hdc, fontTitle ?: fontBold)
            val w = measureSingleLineTextWidth(brandStr, 9) + 12
            Win32NativeWindowHost.drawTextInRect(hdc, brandStr, leftX, top + 4, leftX + w, bottom - 4, theme.textPrimary)
            leftX += w + 8
        } else {
            for (item in leftItems) {
                val impl = item.attributes["nativeImpl"]
                if (impl is DesktopTextNativeImpl<*>) {
                    val txt = impl.getText().trim()
                    if (txt.isEmpty()) continue
                    if (Win32NativeWindowHost.widgetHasClass(item, "badge")) {
                        val bw = drawBadgePill(hdc, theme, fontSmall, item, txt, leftX, (top + bottom - 20) / 2)
                        leftX += bw + 8
                    } else {
                        SelectObject(hdc, fontTitle ?: fontBold)
                        val tw = measureSingleLineTextWidth(txt, 9) + 12
                        Win32NativeWindowHost.drawTextInRect(hdc, txt, leftX, top + 4, leftX + tw, bottom - 4, theme.textPrimary)
                        leftX += tw + 10
                    }
                }
            }
        }

        fun measureNavActionButtonWidth(item: DesktopNativeWidget, impl: DesktopButtonNativeImpl<*>): Int {
            val txt = impl.getText().trim()
            val hasImgChild = safeChildrenSnapshot(item).any {
                it.visible && !it.disposed && it.attributes["nativeImpl"] is DesktopImageNativeImpl<*>
            }
            val isCompactIconBtn = (txt.isEmpty() && (!impl.getIcon().isNullOrBlank() || hasImgChild)) ||
                (txt.length in 1..2 && impl.getIcon().isNullOrBlank())
            return if (isCompactIconBtn) 32 else measureButtonWidth(impl).coerceIn(32, 140)
        }

        // Draw action buttons / badges on the right side of the navbar (left-to-right from the right edge)
        val totalActionsW = buttonsRightToLeft.sumOf { item ->
            val impl = item.attributes["nativeImpl"]
            when (impl) {
                is DesktopButtonNativeImpl<*> -> measureNavActionButtonWidth(item, impl) + 6
                is DesktopTextNativeImpl<*> -> {
                    val t = impl.getText().trim()
                    if (t.isEmpty()) 0 else measureSingleLineTextWidth(t, 7) + 22
                }
                is DesktopSelectNativeImpl<*> -> measureInlineChildWidth(item, 180).coerceIn(90, 180) + 6
                else -> 80
            }
        }
        var curX = max(leftX + 12, right - 12 - totalActionsW)
        val btnTop = top + 9
        val btnBottom = bottom - 9

        for (item in buttonsRightToLeft) {
            val impl = item.attributes["nativeImpl"]
            when (impl) {
                is DesktopButtonNativeImpl<*> -> {
                    val bw = measureNavActionButtonWidth(item, impl)
                    if (curX + bw > right - 6) break
                    SelectObject(hdc, fontSmall)
                    val isPrimary = impl.getType() == BootstrapType.PRIMARY
                    Win32NativeWindowHost.drawButtonControl(
                        hdc = hdc,
                        theme = theme,
                        label = impl.getText(),
                        icon = impl.getIcon(),
                        left = curX,
                        top = btnTop,
                        right = curX + bw,
                        bottom = btnBottom,
                        primary = isPrimary,
                        widget = item,
                        hwndForHover = hwnd
                    )
                    drawCustomButtonIconIfPresent(
                        hwnd = hwnd,
                        hdc = hdc,
                        theme = theme,
                        fontFallback = fontSmall,
                        buttonWidget = item,
                        left = curX,
                        top = btnTop,
                        right = curX + bw,
                        bottom = btnBottom,
                        surfaceBgColorRef = theme.bgCard
                    )
                    val bx = curX
                    Win32NativeWindowHost.addHitRegion(
                        Win32NativeWindowHost.HitRegion(
                            left = bx,
                            top = btnTop,
                            right = bx + bw,
                            bottom = btnBottom,
                            tooltip = nonRedundantTooltip(impl.getTooltipText(), impl.getText()),
                            widget = item,
                            onClick = { _, _ -> impl.dispatchClick() }
                        )
                    )
                    curX += bw + 6
                }
                is DesktopTextNativeImpl<*> -> {
                    val txt = impl.getText().trim()
                    if (txt.isNotEmpty()) {
                        val bw = drawBadgePill(hdc, theme, fontSmall, item, txt, curX, (top + bottom - 20) / 2)
                        curX += bw + 6
                    }
                }
                is DesktopSelectNativeImpl<*> -> {
                    val sw = measureInlineChildWidth(item, 180).coerceIn(90, 180)
                    if (curX + sw > right - 6) break
                    val curVal = impl.getSelectedValue() ?: ""
                    val curLabel = impl.getOptions().firstOrNull { it.first == curVal }?.second ?: curVal
                    SelectObject(hdc, fontSmall)
                    Win32NativeWindowHost.drawSelectControl(
                        hdc = hdc,
                        theme = theme,
                        label = curLabel.ifBlank { "Select..." },
                        left = curX,
                        top = btnTop,
                        right = curX + sw,
                        bottom = btnBottom,
                        widget = item,
                        hwndForHover = hwnd
                    )
                    val sx = curX
                    Win32NativeWindowHost.addHitRegion(
                        Win32NativeWindowHost.HitRegion(
                            left = sx,
                            top = btnTop,
                            right = sx + sw,
                            bottom = btnBottom,
                            tooltip = nonRedundantTooltip(impl.getTooltipText(), curLabel),
                            widget = item,
                            onClick = { mx, my -> Win32NativeWindowHost.openSelectPopupMenu(hwnd, impl, mx, my) }
                        )
                    )
                    curX += sw + 6
                }
            }
        }

        if (navCacheKey != null && !isResizeFrame) {
            var reuseBuffer: ByteArray? = null
            if (!cachedNavbarSurfaces.containsKey(navCacheKey) && cachedNavbarSurfaces.size >= MAX_CACHED_STRIP_SURFACES) {
                val oldestKey = cachedNavbarSurfaces.keys.firstOrNull()
                if (oldestKey != null) {
                    reuseBuffer = cachedNavbarSurfaces.remove(oldestKey)?.bgra
                }
            }
            val captured = captureCachedRegion(left, top, right, bottom, navHitStartIdx, reuseBuffer = reuseBuffer, cacheKey = navCacheKey)
            if (captured != null) {
                cachedNavbarSurfaces[navCacheKey] = captured
            }
        }
    }

    private fun nonRedundantTooltip(rawTooltip: String?, visibleText: String? = null): String? {
        val trimmed = rawTooltip?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        val vis = visibleText?.trim().orEmpty()
        if (vis.isNotEmpty() && trimmed.equals(vis, ignoreCase = true)) return null
        return trimmed
    }

    private fun renderTabBarStrip(
        hwnd: HWND,
        hdc: HDC?,
        theme: Win32NativeWindowHost.SystemThemePalette,
        fontSmall: HFONT?,
        fontBold: HFONT?,
        tabContainerWidget: DesktopNativeWidget,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        val tabContainerImpl = tabContainerWidget.attributes["nativeImpl"] as? DesktopTabContainerNativeImpl<*>
        val activeIdxRaw = tabContainerImpl?.getSelectedTabIndex() ?: 0
        val isHoveredInTabBar = Win32NativeWindowHost.isRectHovered(hwnd, left, top, right, bottom)
        val hoveredTabPtr = if (isHoveredInTabBar) (Win32NativeWindowHost.hoveredWidget?.nativePointer ?: 0L) else 0L
        val pressedTabPtr = Win32NativeWindowHost.pressedWidget?.nativePointer ?: 0L
        val canCacheTabBar = canUseSurfaceRegionCache(left, top, right, bottom)
        val tabCacheKey = if (canCacheTabBar) {
            StripSurfaceCacheKey(
                widgetPtr = tabContainerWidget.nativePointer,
                mutVer = tabContainerWidget.subtreeMutationVersion,
                left = left,
                top = top,
                width = right - left,
                height = bottom - top,
                extraState = activeIdxRaw,
                hoveredWidgetPtr = hoveredTabPtr,
                pressedWidgetPtr = pressedTabPtr,
                isDark = theme.isDark
            )
        } else {
            null
        }
        if (tabCacheKey != null) {
            val cachedRegion = cachedTabBarSurfaces[tabCacheKey]
            if (cachedRegion != null && tryBlitCachedRegion(cachedRegion, left, top, right, bottom, cacheKey = tabCacheKey)) {
                return
            }
        }
        val tabHitStartIdx = Win32NativeWindowHost.getHitRegionsCount()

        Win32NativeWindowHost.fillRectColor(hdc, left, top, right, bottom, theme.bgPanel)
        Win32NativeWindowHost.drawLineColor(hdc, left, bottom - 1, right, bottom - 1, theme.borderSubtle)

        val tabWidgets = safeChildrenSnapshot(tabContainerWidget).filter {
            it.attributes["nativeImpl"] is DesktopTabNativeImpl<*>
        }
        val orderedTabImpls = tabContainerImpl?.getTabImpls().orEmpty()
        val orderedTitles = tabContainerImpl?.getTabTitles().orEmpty()
        val titles = if (orderedTitles.isNotEmpty()) {
            orderedTitles.mapIndexed { idx, fallbackTitle ->
                val tabImpl = orderedTabImpls.getOrNull(idx) as? DesktopTabNativeImpl<*>
                tabImpl?.titleVal?.takeIf { it.isNotEmpty() } ?: fallbackTitle
            }
        } else {
            tabWidgets.map { (it.attributes["nativeImpl"] as DesktopTabNativeImpl<*>).titleVal }
        }
        if (titles.isEmpty()) return

        val activeIdx = (tabContainerImpl?.getSelectedTabIndex() ?: 0).coerceIn(0, titles.lastIndex)
        val fullWidth = tabContainerImpl?.isFullWidthTabs() == true
        val availW = (right - left - 16).coerceAtLeast(80)
        val equalTabW = if (fullWidth) {
            ((availW - (titles.size - 1).coerceAtLeast(0) * 4) / titles.size).coerceAtLeast(48)
        } else {
            (availW / titles.size).coerceIn(72, 180)
        }

        var tx = left + 8
        val tabLefts = IntArray(titles.size)
        val tabRights = IntArray(titles.size)
        var renderedTabCount = 0
        for (idx in titles.indices) {
            val title = titles[idx]
            val isActive = idx == activeIdx
            val tabImplForMeasure = (orderedTabImpls.getOrNull(idx) as? DesktopTabNativeImpl<*>)
                ?: (tabWidgets.getOrNull(idx)?.attributes?.get("nativeImpl") as? DesktopTabNativeImpl<*>)
            val hasIcon = !tabImplForMeasure?.iconVal.isNullOrBlank()
            val iconExtraW = if (hasIcon) (tabImplForMeasure?.iconSizePxVal ?: 18).coerceIn(14, 22) + 6 else 0
            val tabW = if (fullWidth) {
                equalTabW
            } else {
                max(equalTabW, min(220, measureSingleLineTextWidth(title, 7) + 26 + iconExtraW))
            }
            val tabRight = min(right - 4, tx + tabW)
            if (tabRight <= tx + 24) break
            tabLefts[idx] = tx
            tabRights[idx] = tabRight
            renderedTabCount = idx + 1

            val isHovered = Win32NativeWindowHost.isRectHovered(hwnd, tx, top + 4, tabRight, bottom - 1)
            val tabBg = when {
                isActive -> theme.bgCard
                isHovered -> theme.bgElevated
                else -> theme.bgPanel
            }
            Win32NativeWindowHost.drawRoundedRect(
                hdc = hdc,
                left = tx,
                top = top + 4,
                right = tabRight,
                bottom = bottom,
                radius = 5,
                fillColor = tabBg,
                borderColor = if (isActive) theme.accentBlueBorder else theme.borderSubtle
            )
            if (isActive) {
                Win32NativeWindowHost.fillRectColor(hdc, tx + 2, bottom - 3, tabRight - 2, bottom, theme.accentBlue)
            }
            tx = tabRight + 4
        }

        for (idx in 0 until renderedTabCount) {
            val title = titles[idx]
            val isActive = idx == activeIdx
            val hitL = tabLefts[idx]
            val hitR = tabRights[idx]
            val capturedIdx = idx
            val tabImpl = (orderedTabImpls.getOrNull(idx) as? DesktopTabNativeImpl<*>)
                ?: (tabWidgets.getOrNull(idx)?.attributes?.get("nativeImpl") as? DesktopTabNativeImpl<*>)
            val tabWidget = (tabImpl?.nativeWidget as? DesktopNativeWidget)
                ?: tabWidgets.getOrNull(idx)
                ?: tabContainerWidget
            val iconUrl = tabImpl?.iconVal?.trim().orEmpty()
            var textLeft = hitL + 8
            if (iconUrl.isNotEmpty()) {
                val iconBoxSize = (tabImpl?.iconSizePxVal ?: 18).coerceIn(14, (bottom - top - 6).coerceAtLeast(14))
                val iconLeft = hitL + 8
                val iconTop = top + 4 + ((bottom - (top + 4) - iconBoxSize) / 2).coerceAtLeast(0)
                val isHovered = Win32NativeWindowHost.isRectHovered(hwnd, hitL, top + 4, hitR, bottom - 1)
                val tabBg = when {
                    isActive -> theme.bgCard
                    isHovered -> theme.bgElevated
                    else -> theme.bgPanel
                }
                drawCachedOrAsyncImageBox(
                    hwnd = hwnd,
                    hdc = hdc,
                    theme = theme,
                    fontFallback = fontSmall,
                    url = iconUrl,
                    ownerWidget = tabWidget,
                    fallbackLabel = title,
                    cssFilter = resolveWidgetCssFilter(tabWidget, tabContainerWidget),
                    left = iconLeft,
                    top = iconTop,
                    right = iconLeft + iconBoxSize,
                    bottom = iconTop + iconBoxSize,
                    surfaceBgColorRef = tabBg,
                    drawBoxBackground = false,
                    drawBackground = true,
                    drawForeground = false,
                    transparentBackground = true
                )
                textLeft = iconLeft + iconBoxSize + 4
            }
            SelectObject(hdc, if (isActive) fontBold else fontSmall)
            Win32NativeWindowHost.drawTextInRect(
                hdc = hdc,
                text = title,
                left = textLeft,
                top = top + 4,
                right = hitR - 8,
                bottom = bottom - 2,
                colorRef = if (isActive) theme.accentBlueText else theme.textSecondary,
                center = true
            )
            val isButtonTab = tabWidget.attributes["isButtonTab"] == true ||
                Win32NativeWindowHost.widgetHasClass(tabWidget, "nav-button-tab")
            Win32NativeWindowHost.addHitRegion(
                Win32NativeWindowHost.HitRegion(
                    left = hitL,
                    top = top + 4,
                    right = hitR,
                    bottom = bottom,
                    tooltip = nonRedundantTooltip(tabImpl?.getTooltipText(), title),
                    widget = tabWidget,
                    onClick = { _, _ ->
                        if (isButtonTab) {
                            (tabImpl ?: (tabWidget.attributes["nativeImpl"] as? DesktopTabNativeImpl<*>))?.dispatchClick()
                        } else {
                            tabContainerImpl?.requestSelectTab(capturedIdx)
                            (tabImpl ?: (tabWidget.attributes["nativeImpl"] as? DesktopTabNativeImpl<*>))?.dispatchClick()
                        }
                    }
                )
            )
        }

        if (tabCacheKey != null) {
            var reuseBuffer: ByteArray? = null
            if (!cachedTabBarSurfaces.containsKey(tabCacheKey) && cachedTabBarSurfaces.size >= MAX_CACHED_STRIP_SURFACES) {
                val oldestKey = cachedTabBarSurfaces.keys.firstOrNull()
                if (oldestKey != null) {
                    reuseBuffer = cachedTabBarSurfaces.remove(oldestKey)?.bgra
                }
            }
            val captured = captureCachedRegion(left, top, right, bottom, tabHitStartIdx, reuseBuffer = reuseBuffer, cacheKey = tabCacheKey)
            if (captured != null) {
                cachedTabBarSurfaces[tabCacheKey] = captured
            }
        }
    }

    private fun renderBottomNavigationBar(
        hwnd: HWND,
        hdc: HDC?,
        theme: Win32NativeWindowHost.SystemThemePalette,
        fontSmall: HFONT?,
        fontBold: HFONT?,
        bottomNavWidget: DesktopNativeWidget,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        Win32NativeWindowHost.fillRectColor(hdc, left, top, right, bottom, theme.bgPanel)
        Win32NativeWindowHost.drawLineColor(hdc, left, top, right, top, theme.borderSubtle)

        val impl = bottomNavWidget.attributes["nativeImpl"] as? DesktopBottomNavigationBarNativeImpl<*> ?: return
        val items = impl.getItems()
        if (items.isEmpty()) return
        val selectedId = impl.getSelectedItemId()
        val itemW = max(60, (right - left) / items.size)

        items.forEachIndexed { idx, (itemId, label, icon) ->
            val ix = left + idx * itemW
            val ir = if (idx == items.lastIndex) right else ix + itemW
            val isSelected = itemId == selectedId
            val isHovered = Win32NativeWindowHost.isRectHovered(hwnd, ix, top, ir, bottom)
            if (isSelected || isHovered) {
                Win32NativeWindowHost.fillRectColor(
                    hdc,
                    ix + 4,
                    top + 4,
                    ir - 4,
                    bottom - 4,
                    if (isSelected) theme.bgCard else theme.bgElevated
                )
            }
            if (isSelected) {
                Win32NativeWindowHost.fillRectColor(hdc, ix + 12, top, ir - 12, top + 3, theme.accentBlue)
            }
            val display = if (!icon.isNullOrBlank()) "$icon $label" else label
            SelectObject(hdc, if (isSelected) fontBold else fontSmall)
            Win32NativeWindowHost.drawTextInRect(
                hdc = hdc,
                text = display,
                left = ix + 4,
                top = top + 4,
                right = ir - 4,
                bottom = bottom - 4,
                colorRef = if (isSelected) theme.accentBlueText else theme.textSecondary,
                center = true
            )
            Win32NativeWindowHost.addHitRegion(
                Win32NativeWindowHost.HitRegion(
                    left = ix,
                    top = top,
                    right = ir,
                    bottom = bottom,
                    tooltip = null,
                    widget = bottomNavWidget,
                    onClick = { _, _ -> impl.setSelectedItemId(itemId) }
                )
            )
        }
    }

    private fun renderScrollableVerticalStack(
        hwnd: HWND,
        hdc: HDC?,
        theme: Win32NativeWindowHost.SystemThemePalette,
        fontSmall: HFONT?,
        fontSmallBold: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        fontTitle: HFONT?,
        fontMetricBig: HFONT?,
        container: DesktopNativeWidget,
        children: List<DesktopNativeWidget>,
        titleBarWidgets: Set<DesktopNativeWidget>,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        padX: Int,
        padY: Int
    ) {
        if (right <= left + 16 || bottom <= top + 16) return
        val key = container.hashCode()
        val nestedLazy = findFirstLazyListWidget(container, titleBarWidgets)
        if (nestedLazy != null) {
            val hostSyncVer = nestedLazy.attributes["hostSyncedScrollVer"] as? Long
            if (hostSyncVer != null && hostSyncVer != (nestedLazy.attributes["_lastAppliedFlowHostSyncVer"] as? Long)) {
                nestedLazy.attributes["_lastAppliedFlowHostSyncVer"] = hostSyncVer
                nestedLazy.attributes["_lastAppliedHostSyncVer"] = hostSyncVer
                val syncedScroll = ((nestedLazy.attributes["hostSyncedScrollOffsetPx"] as? Number)?.toInt() ?: 0).coerceAtLeast(0)
                scrollOffsetsY[key] = syncedScroll
                scrollOffsetsY[nestedLazy.hashCode()] = syncedScroll
                nestedLazy.attributes["_lastDispatchedScrollPx"] = syncedScroll.toFloat()
            }
        }
        val scrollY = (scrollOffsetsY[key] ?: 0).coerceAtLeast(0)
        val viewportH = (bottom - top).coerceAtLeast(1)

        val prevClipTop = currentClipTop
        val prevClipBottom = currentClipBottom
        currentClipTop = top
        currentClipBottom = bottom

        val savedDc = Win32NativeWindowHost.pushClipRect(hdc, left, top, right, bottom)

        val innerLeft = left + padX
        val prelimAvailW = (right - left - padX * 2).coerceAtLeast(32)
        val prelimContentH = estimateVerticalStackHeight(container, children, titleBarWidgets, prelimAvailW) + padY * 2
        val prelimScrollNeeded = prelimContentH > viewportH + 2 || scrollY > 0
        val innerRight = if (prelimScrollNeeded) right - padX - 8 else right - padX
        val availW = (innerRight - innerLeft).coerceAtLeast(32)
        var curY = top + padY - scrollY

        for (child in children) {
            if (!child.visible || child.disposed) continue
            val estH = estimateFlowNodeHeight(child, titleBarWidgets, availW)
            if (estH <= 0) continue
            if (curY + estH >= top - 64 && curY <= bottom + 64) {
                val renderedBottom = renderFlowNode(
                    hwnd = hwnd,
                    hdc = hdc,
                    theme = theme,
                    fontSmall = fontSmall,
                    fontSmallBold = fontSmallBold,
                    fontNormal = fontNormal,
                    fontBold = fontBold,
                    fontTitle = fontTitle,
                    fontMetricBig = fontMetricBig,
                    node = child,
                    titleBarWidgets = titleBarWidgets,
                    left = innerLeft,
                    top = curY,
                    right = innerRight,
                    bottomLimit = bottom + 256
                )
                val actualH = max(estH, renderedBottom - curY)
                if (actualH > 0) {
                    curY += actualH + 6
                }
            } else {
                curY += estH + 6
            }
        }

        val totalContentH = (curY + scrollY) - top + padY
        val scrollNeeded = totalContentH > viewportH + 2
        container.attributes["scrollContentHeightPx"] = totalContentH
        container.attributes["scrollViewportHeightPx"] = viewportH
        container.attributes["scrollOffsetYPx"] = scrollY
        val maxScroll = if (scrollNeeded) max(0, totalContentH - viewportH) else 0
        maxScrollOffsetsY[key] = maxScroll
        if (scrollY > maxScroll) {
            scrollOffsetsY[key] = maxScroll
        }
        if (scrollNeeded) {
            activeScrollRegions.add(intArrayOf(left, top, right, bottom, key))
        }

        // Draw scrollbar rail & thumb only if content overflows viewport (`scrollNeeded`)
        if (scrollNeeded && maxScroll > 0 && right - left > 40) {
            val railLeft = right - 7
            val railRight = right - 2
            val railTop = top + 4
            val railBottom = bottom - 4
            val railH = max(16, railBottom - railTop)
            Win32NativeWindowHost.fillRectColor(hdc, railLeft, railTop, railRight, railBottom, theme.bgPanel)
            val thumbH = max(24, (railH * viewportH) / totalContentH.coerceAtLeast(1))
            val thumbTravel = max(1, railH - thumbH)
            val thumbTop = railTop + ((scrollY.toFloat() / maxScroll.toFloat()) * thumbTravel).roundToInt()
            Win32NativeWindowHost.drawRoundedRect(
                hdc = hdc,
                left = railLeft,
                top = thumbTop,
                right = railRight,
                bottom = min(railBottom, thumbTop + thumbH),
                radius = 2,
                fillColor = theme.borderStrong,
                borderColor = theme.borderStrong
            )
        }

        Win32NativeWindowHost.popClipRect(hdc, savedDc)
        currentClipTop = prevClipTop
        currentClipBottom = prevClipBottom
    }

    private fun renderFlowNode(
        hwnd: HWND,
        hdc: HDC?,
        theme: Win32NativeWindowHost.SystemThemePalette,
        fontSmall: HFONT?,
        fontSmallBold: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        fontTitle: HFONT?,
        fontMetricBig: HFONT?,
        node: DesktopNativeWidget,
        titleBarWidgets: Set<DesktopNativeWidget>,
        left: Int,
        top: Int,
        right: Int,
        bottomLimit: Int
    ): Int {
        if (!node.visible || node.disposed || right <= left) return top
        val availW = (right - left).coerceAtLeast(32)
        val impl = node.attributes["nativeImpl"]
        val tag = widgetTag(node)

        // 1. Nested specialized layout containers
        if (Win32NativeWindowHost.widgetHasClass(node, "gui-hybrid-split-navigation")) {
            val subH = max(320, bottomLimit - top)
            renderHybridSplitNavigationLayout(
                hwnd, hdc, theme, fontSmall, fontSmallBold, fontNormal, fontBold, fontTitle, fontMetricBig,
                node, titleBarWidgets, left, top, right, top + subH
            )
            return top + subH
        }
        if (impl is DesktopTabContainerNativeImpl<*>) {
            val tabH = 38
            renderTabBarStrip(hwnd, hdc, theme, fontSmall, fontBold, node, left, top, right, top + tabH)
            val activeContentList = resolveActiveTabContentWidgets(node, titleBarWidgets)
            if (activeContentList.isNotEmpty()) {
                var curTabY = top + tabH + 6
                for (contentWidget in activeContentList) {
                    curTabY = renderFlowNode(
                        hwnd, hdc, theme, fontSmall, fontSmallBold, fontNormal, fontBold, fontTitle, fontMetricBig,
                        contentWidget, titleBarWidgets, left, curTabY, right, bottomLimit
                    ) + 6
                }
                return curTabY
            }
            return top + tabH
        }
        if (impl is DesktopNavbarNativeImpl<*> || Win32NativeWindowHost.widgetHasClass(node, "navbar")) {
            val navH = 44
            renderNavbar(hwnd, hdc, theme, fontSmall, fontNormal, fontBold, fontTitle, node, titleBarWidgets, left, top, right, top + navH)
            return top + navH
        }
        if (impl is DesktopDividerNativeImpl<*> || tag.contains("divider") || tag == "hr") {
            val midY = top + 6
            Win32NativeWindowHost.drawLineColor(hdc, left, midY, right, midY, theme.borderSubtle)
            return top + 12
        }

        // 2. Standalone interactive surface placeholder
        if (node.interactiveSurfaceSpec != null &&
            !node.attributes.containsKey("captureBgraPtr") &&
            !node.attributes.containsKey("captureBgra") &&
            !node.attributes.containsKey("captureRgba")
        ) {
            val stageH = resolveVideoStageHeight(availW, node)
            val stageBottom = top + stageH
            Win32NativeWindowHost.drawRoundedRect(
                hdc = hdc,
                left = left,
                top = top,
                right = right,
                bottom = stageBottom,
                radius = 6,
                fillColor = Win32NativeWindowHost.rgb(12, 16, 22),
                borderColor = theme.borderSubtle
            )
            val cx = (left + right) / 2
            val cy = (top + stageBottom) / 2
            Win32NativeWindowHost.drawRoundedRect(
                hdc = hdc,
                left = cx - 28,
                top = cy - 20,
                right = cx + 28,
                bottom = cy + 20,
                radius = 8,
                fillColor = theme.bgElevated,
                borderColor = theme.accentBlueBorder
            )
            SelectObject(hdc, fontTitle ?: fontBold)
            Win32NativeWindowHost.drawTextInRect(
                hdc = hdc,
                text = "▶",
                left = cx - 24,
                top = cy - 18,
                right = cx + 24,
                bottom = cy + 18,
                colorRef = theme.accentBlueText,
                center = true
            )
            SelectObject(hdc, fontSmall)
            val src = (node.attributes["src"] as? String)?.takeIf { it.isNotBlank() } ?: "Ready for Playback"
            Win32NativeWindowHost.drawTextInRect(
                hdc = hdc,
                text = src,
                left = left + 12,
                top = stageBottom - 26,
                right = right - 12,
                bottom = stageBottom - 6,
                colorRef = theme.textMuted,
                center = true
            )
            return stageBottom
        }

        // 3. Embedded interactive surface
        if (isInteractiveSurfaceWidget(node)) {
            val surfWFn = node.attributes["surfaceWidth"] as? (() -> Int)
            val surfHFn = node.attributes["surfaceHeight"] as? (() -> Int)
            val rawW = surfWFn?.invoke() ?: 0
            val rawH = surfHFn?.invoke() ?: 0
            val isMiniSpectrum = (rawH in 1..90 && rawW in 1..360)
            val drawW = if (isMiniSpectrum) min(availW, max(140, rawW)) else availW
            val drawH = if (isMiniSpectrum) max(36, rawH) else ((availW * 9) / 16).coerceIn(200, 380)
            Win32NativeWindowHost.renderInlineCanvasSurface(
                hdc = hdc,
                canvasWidget = node,
                left = left,
                top = top,
                right = left + drawW,
                bottom = top + drawH,
                theme = theme,
                registerAsPrimaryCanvas = !isMiniSpectrum
            )
            return top + drawH
        }

        // 4. Leaf controls
        when (impl) {
            is DesktopImageNativeImpl<*> -> {
                val imgW = ((node.attributes["width"] as? Number)?.toInt()
                    ?: node.preferredSize?.width
                    ?: 48).coerceIn(16, availW)
                val imgH = ((node.attributes["height"] as? Number)?.toInt()
                    ?: node.preferredSize?.height
                    ?: 48).coerceIn(16, 240)
                val url = impl.getSourceUrl()?.trim().orEmpty()
                val placeholderUrl = impl.getPlaceholderUrl()?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: ImageLoader.DEFAULT_PLACEHOLDER_URI
                val antialiasing = impl.isAntialiasing()
                val fallbackTxt = (node.attributes["fallbackText"] as? String)?.trim().orEmpty()
                val cssFilter = resolveWidgetCssFilter(node, null)
                drawCachedOrAsyncImageBox(
                    hwnd = hwnd,
                    hdc = hdc,
                    theme = theme,
                    fontFallback = fontSmallBold ?: fontBold,
                    url = url,
                    placeholderUrl = placeholderUrl,
                    antialiasing = antialiasing,
                    ownerWidget = node,
                    fallbackLabel = fallbackTxt,
                    cssFilter = cssFilter,
                    left = left,
                    top = top,
                    right = left + imgW,
                    bottom = top + imgH,
                    surfaceBgColorRef = theme.bgElevated
                )
                return top + imgH
            }

            is DesktopTextNativeImpl<*> -> {
                val txt = impl.getText().trim()
                if (txt.isEmpty()) return top
                if (Win32NativeWindowHost.widgetHasClass(node, "badge")) {
                    drawBadgePill(hdc, theme, fontSmall, node, txt, left, top)
                    return top + 22
                }
                val isHeading = tag == "h1" || tag == "h2" || tag == "h3" || tag == "h4" || tag == "h5" || tag == "h6" ||
                    tag.contains("heading")
                val lineH = when {
                    tag == "h1" || tag == "h2" -> 30
                    tag == "h3" || tag == "h4" -> 24
                    else -> 20
                }
                val avgCharPx = when {
                    tag == "h1" || tag == "h2" -> 11
                    tag == "h3" || tag == "h4" -> 9
                    else -> 7
                }
                val font = when {
                    tag == "h1" || tag == "h2" -> fontMetricBig ?: fontTitle
                    tag == "h3" || tag == "h4" -> fontTitle ?: fontBold
                    isHeading -> fontBold
                    else -> fontNormal
                }
                val fgColor = resolveContrastSafeTextColor(impl, theme, isHeading)
                SelectObject(hdc, font)
                val maxLines = impl.getMaxLines() ?: (node.attributes["maxLines"] as? Number)?.toInt()
                val forceSingleLine = maxLines == 1 ||
                    Win32NativeWindowHost.widgetHasClass(node, "text-nowrap") ||
                    Win32NativeWindowHost.widgetHasClass(node, "text-truncate") ||
                    node.resolveCssValue("white-space")?.lowercase() == "nowrap"
                val parent = node.parentWidget
                val isCentered = Win32NativeWindowHost.widgetHasClass(node, "text-center") ||
                    Win32NativeWindowHost.widgetHasClass(node, "capabilities-info") ||
                    node.attributes["id"] == "capabilities-info" ||
                    node.resolveCssValue("text-align")?.lowercase() == "center" ||
                    (node.attributes["textAlignment"] as? String)?.equals("CENTER", ignoreCase = true) == true ||
                    (parent != null && (
                        Win32NativeWindowHost.widgetHasClass(parent, "text-center") ||
                            parent.resolveCssValue("text-align")?.lowercase() == "center" ||
                            (Win32NativeWindowHost.widgetHasClass(parent, "align-items-center") && !isHorizontalRowContainer(parent))
                        ))
                val isRightAligned = !isCentered && (
                    Win32NativeWindowHost.widgetHasClass(node, "text-end") ||
                        node.resolveCssValue("text-align")?.lowercase() == "right" ||
                        node.resolveCssValue("text-align")?.lowercase() == "end" ||
                        (node.attributes["textAlignment"] as? String)?.equals("END", ignoreCase = true) == true ||
                        (node.attributes["textAlignment"] as? String)?.equals("RIGHT", ignoreCase = true) == true
                    )
                val fontKind = when {
                    tag == "h1" || tag == "h2" -> 3
                    tag == "h3" || tag == "h4" -> 2
                    isHeading -> 1
                    else -> 0
                }
                val singleLineW = max(
                    measureSingleLineTextWidth(txt, avgCharPx),
                    Win32NativeWindowHost.measureSingleLineTextWidthWithFont(txt, fontKind)
                )
                val shouldWrap = !forceSingleLine && (
                    txt.contains('\n') || singleLineW > (availW - 8).coerceAtLeast(24)
                    )
                val boxH = if (!shouldWrap) {
                    lineH
                } else {
                    val measuredH = Win32NativeWindowHost.measureMultilineTextHeight(hdc, txt, (availW - 4).coerceAtLeast(32), lineH)
                    if (maxLines != null && maxLines > 1) {
                        min(measuredH, maxLines * lineH + 4)
                    } else {
                        measuredH
                    }
                }
                val textBottom = top + boxH
                if (!shouldWrap) {
                    Win32NativeWindowHost.drawTextInRect(
                        hdc = hdc,
                        text = txt,
                        left = left,
                        top = top,
                        right = right,
                        bottom = textBottom,
                        colorRef = fgColor,
                        center = isCentered,
                        alignRight = isRightAligned
                    )
                } else {
                    drawWrappedTextInRect(
                        hdc = hdc,
                        text = txt,
                        left = left,
                        top = top,
                        right = right,
                        bottom = textBottom,
                        colorRef = fgColor,
                        center = isCentered,
                        rightAlign = isRightAligned
                    )
                }
                return textBottom
            }

            is DesktopButtonNativeImpl<*> -> {
                val btnH = 28
                val isFullWidthBtn = Win32NativeWindowHost.widgetHasClass(node, "w-100") ||
                    node.resolveCssValue("width") == "100%" ||
                    impl.resolveBootstrapWidthFraction() == 1.0f
                val bw = if (isFullWidthBtn) availW else min(availW, max(32, measureButtonWidth(impl)))
                val isPrimary = impl.getType() == BootstrapType.PRIMARY
                SelectObject(hdc, fontSmall)
                Win32NativeWindowHost.drawButtonControl(
                    hdc = hdc,
                    theme = theme,
                    label = impl.getText(),
                    icon = impl.getIcon(),
                    left = left,
                    top = top,
                    right = left + bw,
                    bottom = top + btnH,
                    primary = isPrimary,
                    widget = node,
                    hwndForHover = hwnd
                )
                drawCustomButtonIconIfPresent(
                    hwnd = hwnd,
                    hdc = hdc,
                    theme = theme,
                    fontFallback = fontSmall,
                    buttonWidget = node,
                    left = left,
                    top = top,
                    right = left + bw,
                    bottom = top + btnH,
                    surfaceBgColorRef = theme.bgCard
                )
                Win32NativeWindowHost.addHitRegion(
                    Win32NativeWindowHost.HitRegion(
                        left = left,
                        top = top,
                        right = left + bw,
                        bottom = top + btnH,
                        tooltip = nonRedundantTooltip(impl.getTooltipText(), impl.getText()),
                        widget = node,
                        onClick = { _, _ -> impl.dispatchClick() }
                    )
                )
                return top + btnH
            }

            is DesktopTextInputNativeImpl<*> -> {
                val inpH = if (impl.isMultiline()) {
                    max(84, ((node.attributes["rows"] as? Number)?.toInt() ?: 4) * 20)
                } else {
                    30
                }
                renderTextInputControl(hwnd, hdc, theme, fontNormal, impl, node, left, top, right, top + inpH)
                return top + inpH
            }

            is DesktopSelectNativeImpl<*> -> {
                val selH = 28
                val curVal = impl.getSelectedValue() ?: ""
                val curLabel = impl.getOptions().firstOrNull { it.first == curVal }?.second ?: curVal
                SelectObject(hdc, fontSmall)
                Win32NativeWindowHost.drawSelectControl(
                    hdc = hdc,
                    theme = theme,
                    label = curLabel.ifBlank { "Select..." },
                    left = left,
                    top = top,
                    right = right,
                    bottom = top + selH,
                    widget = node,
                    hwndForHover = hwnd
                )
                Win32NativeWindowHost.addHitRegion(
                    Win32NativeWindowHost.HitRegion(
                        left = left,
                        top = top,
                        right = right,
                        bottom = top + selH,
                        tooltip = nonRedundantTooltip(impl.getTooltipText(), curLabel),
                        widget = node,
                        onClick = { mx, my -> Win32NativeWindowHost.openSelectPopupMenu(hwnd, impl, mx, my) }
                    )
                )
                return top + selH
            }

            is DesktopCheckboxNativeImpl<*> -> {
                val cbH = 22
                SelectObject(hdc, fontSmall)
                Win32NativeWindowHost.drawCheckboxControl(
                    hdc = hdc,
                    theme = theme,
                    label = impl.getLabel(),
                    checked = impl.isChecked(),
                    left = left,
                    top = top,
                    right = right,
                    bottom = top + cbH,
                    widget = node,
                    hwndForHover = hwnd
                )
                Win32NativeWindowHost.addHitRegion(
                    Win32NativeWindowHost.HitRegion(
                        left = left,
                        top = top,
                        right = right,
                        bottom = top + cbH,
                        tooltip = nonRedundantTooltip(impl.getTooltipText(), impl.getLabel()),
                        widget = node,
                        onClick = { _, _ -> impl.setChecked(!impl.isChecked()) }
                    )
                )
                return top + cbH
            }

            is DesktopRangeSliderNativeImpl<*> -> {
                val sH = 20
                Win32NativeWindowHost.drawSliderControl(hdc, theme, impl, left, top + 2, right, top + sH - 2)
                val sL = left
                val sR = right
                Win32NativeWindowHost.addHitRegion(
                    Win32NativeWindowHost.HitRegion(
                        left = sL,
                        top = top,
                        right = sR,
                        bottom = top + sH,
                        tooltip = impl.getTooltipText(),
                        widget = node,
                        onClick = { mx, _ ->
                            impl.beginDragFromFraction((mx - sL).toDouble() / max(1, sR - sL).toDouble())
                        },
                        onDrag = { mx, _ ->
                            impl.updateDragFromFraction((mx - sL).toDouble() / max(1, sR - sL).toDouble())
                        },
                        onRelease = { mx, _ ->
                            impl.endDragFromFraction((mx - sL).toDouble() / max(1, sR - sL).toDouble())
                        }
                    )
                )
                return top + sH
            }

            is DesktopProgressBarNativeImpl<*> -> {
                val pH = 12
                val pct = impl.getProgress().coerceIn(0f, 1f)
                Win32NativeWindowHost.drawRoundedRect(hdc, left, top + 2, right, top + pH, 4, theme.bgElevated, theme.borderSubtle)
                val fillW = ((right - left) * pct).roundToInt()
                if (fillW > 2) {
                    Win32NativeWindowHost.drawRoundedRect(hdc, left, top + 2, left + fillW, top + pH, 4, theme.accentBlue, theme.accentBlue)
                }
                return top + pH + 2
            }

            is DesktopSpinnerNativeImpl<*> -> {
                val spH = 26
                val spW = min(availW, 130)
                Win32NativeWindowHost.drawRoundedRect(hdc, left, top, left + spW, top + spH, 12, theme.accentBlueSoftBg, theme.accentBlueBorder)
                SelectObject(hdc, fontBold ?: fontSmall)
                Win32NativeWindowHost.drawTextInRect(hdc, "⟳ Loading...", left + 8, top + 2, left + spW - 8, top + spH - 2, theme.accentBlueText, center = true)
                return top + spH
            }

            is WindowsRenderableNativeImpl -> {
                if (impl !is WindowsContainerNativeImpl &&
                    impl !is WindowsStyledContainerNativeImpl &&
                    impl !is WindowsTabContainerNativeImpl &&
                    impl !is WindowsLazyListNativeImpl
                ) {
                    val ctx = buildFlowRenderContext(
                        hwnd = hwnd,
                        hdc = hdc,
                        theme = theme,
                        fontSmall = fontSmall,
                        fontSmallBold = fontSmallBold,
                        fontNormal = fontNormal,
                        fontBold = fontBold,
                        fontTitle = fontTitle,
                        fontMetricBig = fontMetricBig,
                        titleBarWidgets = titleBarWidgets,
                        left = left,
                        top = top,
                        right = right,
                        bottomLimit = bottomLimit
                    )
                    return impl.renderWin32(ctx, left, top, right)
                }
            }
        }

        // 5. Alert container (`Alert`)
        if (Win32NativeWindowHost.widgetHasClass(node, "alert") || tag.contains("alert")) {
            val alertText = Win32NativeWindowHost.extractWidgetText(node).ifBlank {
                (node.attributes["text"] as? String).orEmpty()
            }
            val baseImpl = impl as? DesktopBaseNativeImpl<*>
            val type = baseImpl?.getType() ?: when {
                Win32NativeWindowHost.widgetHasClass(node, "alert-danger") -> BootstrapType.DANGER
                Win32NativeWindowHost.widgetHasClass(node, "alert-warning") -> BootstrapType.WARNING
                Win32NativeWindowHost.widgetHasClass(node, "alert-success") -> BootstrapType.SUCCESS
                else -> BootstrapType.INFO
            }
            val (bg, border, fg) = when (type) {
                BootstrapType.DANGER -> Triple(theme.accentRedSoftBg, theme.accentRedBorder, theme.accentRed)
                BootstrapType.WARNING -> Triple(theme.accentAmberSoftBg, theme.accentAmber, theme.accentAmber)
                BootstrapType.SUCCESS -> Triple(theme.accentGreenSoftBg, theme.accentGreenBorder, theme.accentGreen)
                else -> Triple(theme.accentBlueSoftBg, theme.accentBlueBorder, theme.accentBlueText)
            }
            val charsPerLine = max(20, (availW - 24) / 7)
            val lines = ((alertText.length + charsPerLine - 1) / charsPerLine).coerceIn(1, 4)
            val alertH = max(34, lines * 18 + 14)
            Win32NativeWindowHost.drawRoundedRect(hdc, left, top, right, top + alertH, 6, bg, border)
            SelectObject(hdc, fontSmall)
            if (lines == 1) {
                Win32NativeWindowHost.drawTextInRect(hdc, alertText, left + 12, top + 4, right - 12, top + alertH - 4, fg)
            } else {
                drawWrappedTextInRect(hdc, alertText, left + 12, top + 6, right - 12, top + alertH - 6, fg)
            }
            return top + alertH
        }

        // 5a. Virtualized LazyList / LazyColumn (`DesktopLazyListNativeImpl`)
        if (impl is DesktopLazyListNativeImpl<*> || node.attributes["isLazyList"] == true) {
            val localScrollPx = (currentClipTop - top).coerceAtLeast(0).toFloat()
            val viewportExtentPx = (currentClipBottom - currentClipTop).coerceAtLeast(120).toFloat()
            val lastScroll = (node.attributes["_lastDispatchedScrollPx"] as? Float) ?: -1f
            val lastExtent = (node.attributes["_lastDispatchedExtentPx"] as? Float) ?: -1f
            val curCount = (node.attributes["totalItemCount"] as? Number)?.toInt() ?: -1
            val lastCount = (node.attributes["_lastDispatchedItemCount"] as? Int) ?: -2
            if (kotlin.math.abs(localScrollPx - lastScroll) > 0.5f ||
                kotlin.math.abs(viewportExtentPx - lastExtent) > 0.5f ||
                curCount != lastCount
            ) {
                node.attributes["_lastDispatchedScrollPx"] = localScrollPx
                node.attributes["_lastDispatchedExtentPx"] = viewportExtentPx
                node.attributes["_lastDispatchedItemCount"] = curCount
                if (impl is DesktopLazyListNativeImpl<*>) {
                    impl.dispatchHostViewportScroll(localScrollPx, viewportExtentPx)
                } else {
                    @Suppress("UNCHECKED_CAST")
                    (node.attributes["onHostViewportScroll"] as? ((Float, Float) -> Unit))?.invoke(
                        localScrollPx,
                        viewportExtentPx
                    )
                }
            }
            val leadingSpacer = ((node.attributes["leadingSpacerPx"] as? Number)?.toFloat() ?: 0f)
                .roundToInt().coerceAtLeast(0)
            val trailingSpacer = ((node.attributes["trailingSpacerPx"] as? Number)?.toFloat() ?: 0f)
                .roundToInt().coerceAtLeast(0)
            val lazyChildren = visibleChildrenOf(node, titleBarWidgets)
            var curY = top + leadingSpacer
            for (child in lazyChildren) {
                val estH = estimateFlowNodeHeight(child, titleBarWidgets, availW).coerceAtLeast(20)
                if (curY + estH >= currentClipTop - 64 && curY <= currentClipBottom + 64) {
                    val renderedBottom = renderFlowNode(
                        hwnd = hwnd,
                        hdc = hdc,
                        theme = theme,
                        fontSmall = fontSmall,
                        fontSmallBold = fontSmallBold,
                        fontNormal = fontNormal,
                        fontBold = fontBold,
                        fontTitle = fontTitle,
                        fontMetricBig = fontMetricBig,
                        node = child,
                        titleBarWidgets = titleBarWidgets,
                        left = left,
                        top = curY,
                        right = right,
                        bottomLimit = bottomLimit
                    )
                    curY = max(curY + estH, renderedBottom) + 6
                } else {
                    curY += estH + 6
                }
            }
            return curY + trailingSpacer
        }

        if (isSpacerWidget(node)) {
            return top + getFixedSpacerHeight(node)
        }

        val children = visibleChildrenOf(node, titleBarWidgets)
        if (children.isEmpty()) return top

        // 5b. Interactive Stage with Overlaid HUD
        val stageAndOverlay = findStageAndOverlay(node, titleBarWidgets)
        if (stageAndOverlay != null) {
            val (videoChild, overlayChild) = stageAndOverlay
            val fullViewport = isFullViewportVideoStage(node, videoChild, overlayChild)
            return renderVideoStageWithOverlay(
                hwnd = hwnd,
                hdc = hdc,
                theme = theme,
                fontSmall = fontSmall,
                fontSmallBold = fontSmallBold,
                fontNormal = fontNormal,
                fontBold = fontBold,
                fontTitle = fontTitle,
                videoWidget = videoChild,
                overlayWidget = overlayChild,
                otherChildren = children.filter { it !== videoChild && it !== overlayChild },
                titleBarWidgets = titleBarWidgets,
                left = left,
                top = top,
                right = right,
                bottomLimit = bottomLimit,
                fullViewportMode = fullViewport
            )
        }

        // 5c. General LayeredContainer (`.gui-layered-container` / `.gui-layered-layout`)
        if (
            Win32NativeWindowHost.widgetHasClass(node, "gui-layered-container") ||
            Win32NativeWindowHost.widgetHasClass(node, "gui-layered-layout")
        ) {
            val baseLayer = children.first()
            val baseBottom = if (containsLazyListInSubtree(baseLayer, titleBarWidgets)) {
                val targetBottom = bottomLimit.coerceAtLeast(top + 160)
                renderBoundedSectionContainer(
                    hwnd = hwnd,
                    hdc = hdc,
                    theme = theme,
                    fontSmall = fontSmall,
                    fontSmallBold = fontSmallBold,
                    fontNormal = fontNormal,
                    fontBold = fontBold,
                    fontTitle = fontTitle,
                    fontMetricBig = fontMetricBig,
                    container = baseLayer,
                    titleBarWidgets = titleBarWidgets,
                    left = left,
                    top = top,
                    right = right,
                    bottom = targetBottom
                )
                targetBottom
            } else {
                renderFlowNode(
                    hwnd = hwnd,
                    hdc = hdc,
                    theme = theme,
                    fontSmall = fontSmall,
                    fontSmallBold = fontSmallBold,
                    fontNormal = fontNormal,
                    fontBold = fontBold,
                    fontTitle = fontTitle,
                    fontMetricBig = fontMetricBig,
                    node = baseLayer,
                    titleBarWidgets = titleBarWidgets,
                    left = left,
                    top = top,
                    right = right,
                    bottomLimit = bottomLimit
                ).coerceAtLeast(top + 80)
            }
            val layerBottom = min(bottomLimit, max(baseBottom, currentClipBottom))
            for (overlayLayer in children.drop(1)) {
                renderLayeredOverlayChild(
                    hwnd = hwnd,
                    hdc = hdc,
                    theme = theme,
                    fontSmall = fontSmall,
                    fontSmallBold = fontSmallBold,
                    fontNormal = fontNormal,
                    fontBold = fontBold,
                    fontTitle = fontTitle,
                    fontMetricBig = fontMetricBig,
                    overlayLayer = overlayLayer,
                    titleBarWidgets = titleBarWidgets,
                    left = left,
                    top = top,
                    right = right,
                    bottom = layerBottom
                )
            }
            return baseBottom
        }

        // 6. Card container (`Card`)
        val isCard = Win32NativeWindowHost.widgetHasClass(node, "card") || tag.contains("card")
        if (isCard) {
            val fullW = (right - left).coerceAtLeast(120)
            val isZeroFrameCard = isZeroFrameStageCard(node, titleBarWidgets)
            val isBorderlessCard = isZeroFrameCard ||
                node.resolveCssValue("border") == "none" ||
                node.resolveCssValue("border") == "0"
            val isPlayerOrFullWidthCard = isZeroFrameCard ||
                Win32NativeWindowHost.widgetHasClass(node, "w-100") ||
                children.any { hasVideoAndOverlay(it, titleBarWidgets) } ||
                containsStageWithOverlay(node, titleBarWidgets)
            val isCenteredDialogCard = !isPlayerOrFullWidthCard && fullW > 560 && top < 140
            val cardW = if (isCenteredDialogCard) 500 else fullW
            val cardLeft = if (isCenteredDialogCard) left + (fullW - cardW) / 2 else left
            val cardRight = cardLeft + cardW
            val cardTop = if (isCenteredDialogCard && top < 48) top + 32 else top

            val cardPadX = if (isZeroFrameCard) 0 else if (isPlayerOrFullWidthCard) 14 else 24
            val cardPadY = if (isZeroFrameCard) 0 else if (isPlayerOrFullWidthCard) 14 else 20
            val estimatedH = estimateVerticalStackHeight(node, children, titleBarWidgets, cardW - cardPadX * 2) + cardPadY * 2 + 8
            val cardBottom = cardTop + max(56, estimatedH)
            val canCacheCard = !isPlayerOrFullWidthCard &&
                !containsLazyListInSubtree(node, titleBarWidgets) &&
                children.none { it.attributes.containsKey("captureBgra") || it.attributes.containsKey("captureRgba") } &&
                canUseSurfaceRegionCache(cardLeft, cardTop, cardRight, cardBottom, checkScrollClip = true)
            val cardCacheKey = if (canCacheCard) {
                val isHoveredInCard = Win32NativeWindowHost.isRectHovered(hwnd, cardLeft, cardTop, cardRight, min(bottomLimit, cardBottom + 32))
                val hoveredCardPtr = if (isHoveredInCard) (Win32NativeWindowHost.hoveredWidget?.nativePointer ?: 0L) else 0L
                val pressedCardPtr = Win32NativeWindowHost.pressedWidget?.nativePointer ?: 0L
                StripSurfaceCacheKey(
                    widgetPtr = node.nativePointer,
                    mutVer = node.subtreeMutationVersion,
                    left = cardLeft,
                    top = cardTop,
                    width = cardW,
                    height = cardBottom - cardTop,
                    extraState = (Win32NativeWindowHost.focusedWidget?.nativePointer ?: 0L).toInt(),
                    hoveredWidgetPtr = hoveredCardPtr,
                    pressedWidgetPtr = pressedCardPtr,
                    isDark = theme.isDark
                )
            } else {
                null
            }
            if (cardCacheKey != null) {
                val cachedCard = cachedSectionSurfaces[cardCacheKey]
                if (cachedCard != null && tryBlitCachedRegion(cachedCard, cardLeft, cardTop, cardRight, cardTop + cachedCard.height)) {
                    return cardTop + cachedCard.height
                }
            }
            val cardHitStartIdx = Win32NativeWindowHost.getHitRegionsCount()
            if (!isBorderlessCard) {
                Win32NativeWindowHost.drawRoundedRect(
                    hdc = hdc,
                    left = cardLeft,
                    top = cardTop,
                    right = cardRight,
                    bottom = cardBottom,
                    radius = 10,
                    fillColor = theme.bgCard,
                    borderColor = theme.borderStrong
                )
            }
            var cy = cardTop + cardPadY
            for (idx in children.indices) {
                val child = children[idx]
                val nextChild = children.getOrNull(idx + 1)
                val childImpl = child.attributes["nativeImpl"]
                val nextImpl = nextChild?.attributes?.get("nativeImpl")
                val isFormLabelBeforeInput = childImpl is DesktopTextNativeImpl<*> && nextImpl is DesktopTextInputNativeImpl<*>
                val isButtonBar = isHorizontalRowContainer(child) && idx == children.lastIndex
                if (isButtonBar && !isZeroFrameCard) {
                    cy += 6
                }
                if (isFormLabelBeforeInput) {
                    val labelTxt = childImpl.getText().trim()
                    if (labelTxt.isNotEmpty()) {
                        SelectObject(hdc, fontSmallBold ?: fontBold)
                        Win32NativeWindowHost.drawTextInRect(
                            hdc = hdc,
                            text = labelTxt,
                            left = cardLeft + cardPadX,
                            top = cy,
                            right = cardRight - cardPadX,
                            bottom = cy + 18,
                            colorRef = theme.textPrimary
                        )
                        cy += 20
                    }
                    continue
                }
                cy = renderFlowNode(
                    hwnd = hwnd,
                    hdc = hdc,
                    theme = theme,
                    fontSmall = fontSmall,
                    fontSmallBold = fontSmallBold,
                    fontNormal = fontNormal,
                    fontBold = fontBold,
                    fontTitle = fontTitle,
                    fontMetricBig = fontMetricBig,
                    node = child,
                    titleBarWidgets = titleBarWidgets,
                    left = cardLeft + cardPadX,
                    top = cy,
                    right = cardRight - cardPadX,
                    bottomLimit = bottomLimit
                )
                if (!isSpacerWidget(child)) {
                    if (childImpl is DesktopTextInputNativeImpl<*>) {
                        cy += 10
                    } else if (!isZeroFrameCard) {
                        cy += 6
                    }
                }
            }
            val finalCardBottom = max(cardBottom, cy + if (isZeroFrameCard) 0 else 8)
            if (cardCacheKey != null && !isResizeFrame && canUseSurfaceRegionCache(cardLeft, cardTop, cardRight, finalCardBottom, checkScrollClip = true)) {
                var reuseBuffer: ByteArray? = null
                if (!cachedSectionSurfaces.containsKey(cardCacheKey) && cachedSectionSurfaces.size >= MAX_CACHED_SECTION_SURFACES) {
                    val oldestKey = cachedSectionSurfaces.keys.firstOrNull()
                    if (oldestKey != null) {
                        reuseBuffer = cachedSectionSurfaces.remove(oldestKey)?.bgra
                    }
                }
                val captured = captureCachedRegion(
                    left = cardLeft,
                    top = cardTop,
                    right = cardRight,
                    bottom = finalCardBottom,
                    hitStartIdx = cardHitStartIdx,
                    reuseBuffer = reuseBuffer
                )
                if (captured != null) {
                    cachedSectionSurfaces[cardCacheKey] = captured
                }
            }
            return finalCardBottom
        }

        val styledContainerVisuals = (impl as? DesktopStyledContainerNativeImpl<*>)?.getStyledVisuals()

        // 7. Horizontal Row (`HStack`, `.hstack`, `.row`, or `flex-direction: row`)
        if (isHorizontalRowContainer(node)) {
            if (!isCompositeListRow(node, children, titleBarWidgets)) {
                val estRowH = (styledContainerVisuals?.padding?.let { it.top + it.bottom + 28 }
                    ?: estimateFlowNodeHeight(node, titleBarWidgets, availW)).coerceAtLeast(32)
                if (styledContainerVisuals != null && (styledContainerVisuals.backgroundColor != null || styledContainerVisuals.backgroundGradient != null || styledContainerVisuals.borderColor != null)) {
                    Win32NativeWindowHost.drawAntialiasedRoundedRectRgba(
                        hdc = hdc,
                        left = left,
                        top = top,
                        right = right,
                        bottom = top + estRowH,
                        radiusPx = styledContainerVisuals.borderRadiusPx ?: 0f,
                        fillColor = styledContainerVisuals.backgroundColor,
                        borderColor = styledContainerVisuals.borderColor,
                        borderWidthPx = styledContainerVisuals.borderWidthPx ?: if (styledContainerVisuals.borderColor != null) 1f else 0f,
                        opacity = styledContainerVisuals.opacity ?: node.opacity,
                        gradient = styledContainerVisuals.backgroundGradient
                    )
                }
            }
            return renderHorizontalRow(
                hwnd = hwnd,
                hdc = hdc,
                theme = theme,
                fontSmall = fontSmall,
                fontSmallBold = fontSmallBold,
                fontNormal = fontNormal,
                fontBold = fontBold,
                fontTitle = fontTitle,
                fontMetricBig = fontMetricBig,
                rowWidget = node,
                children = children,
                titleBarWidgets = titleBarWidgets,
                left = left,
                top = top,
                right = right
            )
        }

        // 8. Default Vertical Stack (`VStack`, `Container`, `ScrollView`, `Collapse`, `StyledContainer`, etc.) with viewport culling
        val hasStyledBox = styledContainerVisuals != null &&
            (styledContainerVisuals.backgroundColor != null || styledContainerVisuals.backgroundGradient != null || styledContainerVisuals.borderColor != null)
        val estStackH = if (hasStyledBox) estimateVerticalStackHeight(node, children, titleBarWidgets, availW).coerceAtLeast(32) else 0
        val stackCacheKey = if (hasStyledBox &&
            !containsStageWithOverlay(node, titleBarWidgets) &&
            !containsLazyListInSubtree(node, titleBarWidgets) &&
            children.none { it.attributes.containsKey("captureBgra") || it.attributes.containsKey("captureRgba") } &&
            canUseSurfaceRegionCache(left, top, right, top + estStackH, checkScrollClip = true)
        ) {
            val isHoveredInStack = Win32NativeWindowHost.isRectHovered(hwnd, left, top, right, min(bottomLimit, top + estStackH + 32))
            val hoveredStackPtr = if (isHoveredInStack) (Win32NativeWindowHost.hoveredWidget?.nativePointer ?: 0L) else 0L
            val pressedStackPtr = Win32NativeWindowHost.pressedWidget?.nativePointer ?: 0L
            StripSurfaceCacheKey(
                widgetPtr = node.nativePointer,
                mutVer = node.subtreeMutationVersion,
                left = left,
                top = top,
                width = right - left,
                height = estStackH,
                extraState = (Win32NativeWindowHost.focusedWidget?.nativePointer ?: 0L).toInt(),
                hoveredWidgetPtr = hoveredStackPtr,
                pressedWidgetPtr = pressedStackPtr,
                isDark = theme.isDark
            )
        } else {
            null
        }
        if (stackCacheKey != null) {
            val cachedStack = cachedSectionSurfaces[stackCacheKey]
            if (cachedStack != null && tryBlitCachedRegion(cachedStack, left, top, right, top + cachedStack.height)) {
                return top + cachedStack.height
            }
        }
        val stackHitStartIdx = Win32NativeWindowHost.getHitRegionsCount()
        if (hasStyledBox && styledContainerVisuals != null) {
            Win32NativeWindowHost.drawAntialiasedRoundedRectRgba(
                hdc = hdc,
                left = left,
                top = top,
                right = right,
                bottom = top + estStackH,
                radiusPx = styledContainerVisuals.borderRadiusPx ?: 0f,
                fillColor = styledContainerVisuals.backgroundColor,
                borderColor = styledContainerVisuals.borderColor,
                borderWidthPx = styledContainerVisuals.borderWidthPx ?: if (styledContainerVisuals.borderColor != null) 1f else 0f,
                opacity = styledContainerVisuals.opacity ?: node.opacity,
                gradient = styledContainerVisuals.backgroundGradient
            )
        }
        var curY = top
        for (child in children) {
            val estH = estimateFlowNodeHeight(child, titleBarWidgets, availW)
            if (estH <= 0) continue
            if (curY + estH >= currentClipTop - 64 && curY <= currentClipBottom + 64) {
                val renderedBottom = renderFlowNode(
                    hwnd = hwnd,
                    hdc = hdc,
                    theme = theme,
                    fontSmall = fontSmall,
                    fontSmallBold = fontSmallBold,
                    fontNormal = fontNormal,
                    fontBold = fontBold,
                    fontTitle = fontTitle,
                    fontMetricBig = fontMetricBig,
                    node = child,
                    titleBarWidgets = titleBarWidgets,
                    left = left,
                    top = curY,
                    right = right,
                    bottomLimit = bottomLimit
                )
                val actualH = max(estH, renderedBottom - curY)
                if (actualH > 0) {
                    curY += actualH + 6
                }
            } else {
                curY += estH + 6
            }
        }
        if (stackCacheKey != null && curY > top && canUseSurfaceRegionCache(left, top, right, curY, checkScrollClip = true)) {
            var reuseBuffer: ByteArray? = null
            if (!cachedSectionSurfaces.containsKey(stackCacheKey) && cachedSectionSurfaces.size >= MAX_CACHED_SECTION_SURFACES) {
                val oldestKey = cachedSectionSurfaces.keys.firstOrNull()
                if (oldestKey != null) {
                    reuseBuffer = cachedSectionSurfaces.remove(oldestKey)?.bgra
                }
            }
            val captured = captureCachedRegion(
                left = left,
                top = top,
                right = right,
                bottom = curY,
                hitStartIdx = stackHitStartIdx,
                reuseBuffer = reuseBuffer
            )
            if (captured != null) {
                cachedSectionSurfaces[stackCacheKey] = captured
            }
        }
        return curY
    }

    private fun renderVideoStageWithOverlay(
        hwnd: HWND,
        hdc: HDC?,
        theme: Win32NativeWindowHost.SystemThemePalette,
        fontSmall: HFONT?,
        fontSmallBold: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        fontTitle: HFONT?,
        videoWidget: DesktopNativeWidget,
        overlayWidget: DesktopNativeWidget,
        otherChildren: List<DesktopNativeWidget>,
        titleBarWidgets: Set<DesktopNativeWidget>,
        left: Int,
        top: Int,
        right: Int,
        bottomLimit: Int,
        fullViewportMode: Boolean = false,
        isFastPathStagePaint: Boolean = false
    ): Int {
        val availW = (right - left).coerceAtLeast(160)
        val stageH = if (fullViewportMode) {
            (bottomLimit - top).coerceAtLeast(180)
        } else {
            resolveVideoStageHeight(availW, videoWidget, overlayWidget)
        }
        val stageBottom = top + stageH
        Win32NativeWindowHost.lastRenderedVideoStageRect = intArrayOf(left, top, right, stageBottom)
        cachedVideoWidget = videoWidget
        cachedOverlayWidget = overlayWidget
        cachedOtherStageChildren = otherChildren
        cachedTitleBarWidgets = titleBarWidgets
        cachedVideoFullViewportMode = fullViewportMode

        // 1. Check active video surface state
        val hasVideoSurface = videoWidget.attributes.containsKey("captureBgraPtr") ||
            videoWidget.attributes.containsKey("captureBgra") ||
            videoWidget.attributes.containsKey("captureRgba")
        val videoSwapCount = (videoWidget.attributes["swapCount"] as? (() -> Long))?.invoke() ?: 0L
        if (videoSwapCount >= 0L && videoSwapCount != Win32NativeWindowHost.lastRenderedVideoSwapCount) {
            @Suppress("UNCHECKED_CAST")
            (videoWidget.attributes["onBeforePresentFrame"] as? ((Long, Double?) -> Unit))?.invoke(videoSwapCount, null)
            @Suppress("UNCHECKED_CAST")
            (overlayWidget.attributes["onBeforeTargetFramePresent"] as? ((Long, Double?) -> Unit))?.invoke(videoSwapCount, null)
        }
        Win32NativeWindowHost.lastRenderedVideoSwapCount = videoSwapCount
        val hasActiveVideoFrames = hasVideoSurface && videoSwapCount > 0L

        // Extract overlay sections from `overlayWidget` (`topHudBar`, optional `centerOverlay`, and `transportBar`)
        val rawOverlayChildren = overlayWidget.children.filter { !it.disposed && it !in titleBarWidgets }
        val hudControlsLayer = rawOverlayChildren.firstOrNull {
            hasOverlayRole(it, DesktopStageOverlaySpec.ROLE_CONTROLS_LAYER)
        }
        val overlayChildren = if (hudControlsLayer != null) {
            hudControlsLayer.children.filter { !it.disposed && it !in titleBarWidgets }
        } else {
            rawOverlayChildren
        }
        val subtitleBannerWidget = (rawOverlayChildren + overlayChildren + otherChildren).firstOrNull {
            it.visible && hasOverlayRole(it, DesktopStageOverlaySpec.ROLE_CAPTION_BANNER)
        }
        val hasExplicitStageHudRoles = hudControlsLayer != null ||
            subtitleBannerWidget != null ||
            overlayWidget.stageOverlaySpec != null ||
            overlayChildren.any {
                hasOverlayRole(it, DesktopStageOverlaySpec.ROLE_TOP_BAR) ||
                    hasOverlayRole(it, DesktopStageOverlaySpec.ROLE_CENTER_ZONE) ||
                    hasOverlayRole(it, DesktopStageOverlaySpec.ROLE_BOTTOM_BAR) ||
                    hasOverlayRole(it, DesktopStageOverlaySpec.ROLE_CAPTION_BANNER)
            } ||
            (overlayChildren.size >= 2 && overlayChildren.all {
                val cImpl = it.attributes["nativeImpl"]
                cImpl !is DesktopButtonNativeImpl<*> &&
                    cImpl !is DesktopTextNativeImpl<*> &&
                    cImpl !is DesktopTextInputNativeImpl<*> &&
                    cImpl !is DesktopSelectNativeImpl<*> &&
                    cImpl !is DesktopCheckboxNativeImpl<*> &&
                    cImpl !is DesktopRangeSliderNativeImpl<*> &&
                    safeChildrenSnapshot(it).isNotEmpty()
            })
        val topHudBar = overlayChildren.firstOrNull {
            hasOverlayRole(it, DesktopStageOverlaySpec.ROLE_TOP_BAR)
        } ?: if (hasExplicitStageHudRoles) overlayChildren.getOrNull(0) else null
        val centerOverlay = overlayChildren.firstOrNull {
            hasOverlayRole(it, DesktopStageOverlaySpec.ROLE_CENTER_ZONE)
        } ?: if (hasExplicitStageHudRoles) overlayChildren.getOrNull(1)?.takeIf { overlayChildren.size >= 3 } else null
        val transportBar = overlayChildren.firstOrNull {
            hasOverlayRole(it, DesktopStageOverlaySpec.ROLE_BOTTOM_BAR)
        } ?: if (hasExplicitStageHudRoles) {
            overlayChildren.lastOrNull {
                it !== topHudBar &&
                    it !== centerOverlay &&
                    !isOverlayPopoverWidget(it) &&
                    !hasOverlayRole(it, DesktopStageOverlaySpec.ROLE_CAPTION_BANNER)
            }
        } else {
            null
        }

        val generalOverlayChildren = visibleChildrenOf(overlayWidget, titleBarWidgets)
        if (topHudBar == null &&
            centerOverlay == null &&
            transportBar == null &&
            hudControlsLayer == null &&
            subtitleBannerWidget == null &&
            generalOverlayChildren.isNotEmpty()
        ) {
            val inset = if (fullViewportMode) 0 else 2
            if (fullViewportMode) {
                Win32NativeWindowHost.fillRectColor(hdc, left, top, right, stageBottom, 0x0A0705u)
            } else {
                Win32NativeWindowHost.drawRoundedRect(
                    hdc = hdc,
                    left = left,
                    top = top,
                    right = right,
                    bottom = stageBottom,
                    radius = 8,
                    fillColor = 0x0A0705u,
                    borderColor = theme.borderSubtle
                )
            }
            Win32NativeWindowHost.renderInlineCanvasSurface(
                hdc = hdc,
                canvasWidget = videoWidget,
                left = left + inset,
                top = top + inset,
                right = right - inset,
                bottom = stageBottom - inset,
                theme = theme,
                registerAsPrimaryCanvas = true
            )
            for (child in generalOverlayChildren) {
                renderLayeredOverlayChild(
                    hwnd = hwnd,
                    hdc = hdc,
                    theme = theme,
                    fontSmall = fontSmall,
                    fontSmallBold = fontSmallBold,
                    fontNormal = fontNormal,
                    fontBold = fontBold,
                    fontTitle = fontTitle,
                    fontMetricBig = null,
                    overlayLayer = child,
                    titleBarWidgets = titleBarWidgets,
                    left = left + inset,
                    top = top + inset,
                    right = right - inset,
                    bottom = stageBottom - inset
                )
            }
            for (extra in otherChildren) {
                if (!extra.visible || extra.disposed) continue
                renderLayeredOverlayChild(
                    hwnd = hwnd,
                    hdc = hdc,
                    theme = theme,
                    fontSmall = fontSmall,
                    fontSmallBold = fontSmallBold,
                    fontNormal = fontNormal,
                    fontBold = fontBold,
                    fontTitle = fontTitle,
                    fontMetricBig = null,
                    overlayLayer = extra,
                    titleBarWidgets = titleBarWidgets,
                    left = left + inset,
                    top = top + inset,
                    right = right - inset,
                    bottom = stageBottom - inset
                )
            }
            Win32NativeWindowHost.lastVideoStageFastBlitRect[0] = left
            Win32NativeWindowHost.lastVideoStageFastBlitRect[1] = top
            Win32NativeWindowHost.lastVideoStageFastBlitRect[2] = right
            Win32NativeWindowHost.lastVideoStageFastBlitRect[3] = stageBottom
            Win32NativeWindowHost.markFrameBufferDirty(left, top, right, stageBottom)
            return stageBottom
        }

        var titleText = "Nothing playing"
        var subtitleText = ""
        val diagnosticItems = mutableListOf<DesktopNativeWidget>()
        var miniSpectrumWidget: DesktopNativeWidget? = null

        if (topHudBar != null) {
            val topHudChildren = visibleChildrenOf(topHudBar, titleBarWidgets)
            val directTexts = topHudChildren.filter { it.attributes["nativeImpl"] is DesktopTextNativeImpl<*> }
            if (directTexts.isNotEmpty()) {
                val t0 = (directTexts.getOrNull(0)?.attributes?.get("nativeImpl") as? DesktopTextNativeImpl<*>)?.getText()?.trim()
                val t1 = (directTexts.getOrNull(1)?.attributes?.get("nativeImpl") as? DesktopTextNativeImpl<*>)?.getText()?.trim()
                if (!t0.isNullOrEmpty()) titleText = t0
                if (!t1.isNullOrEmpty()) subtitleText = t1
                for (child in topHudChildren) {
                    if (child.attributes.containsKey("captureBgra") || child.attributes.containsKey("captureRgba")) {
                        miniSpectrumWidget = child
                    } else if (child !in directTexts && !isSpacerWidget(child)) {
                        if (child.attributes["nativeImpl"] is DesktopButtonNativeImpl<*>) {
                            diagnosticItems.add(child)
                        } else {
                            for (sub in visibleChildrenOf(child, titleBarWidgets)) {
                                if (sub.attributes.containsKey("captureBgra") || sub.attributes.containsKey("captureRgba")) {
                                    miniSpectrumWidget = sub
                                } else if (!isSpacerWidget(sub)) {
                                    diagnosticItems.add(sub)
                                }
                            }
                        }
                    }
                }
            } else {
                val titleStack = topHudChildren.getOrNull(0)
                val badgeGroup = topHudChildren.getOrNull(1)
                if (titleStack != null) {
                    val textNodes = visibleChildrenOf(titleStack, titleBarWidgets)
                    val t0 = (textNodes.getOrNull(0)?.attributes?.get("nativeImpl") as? DesktopTextNativeImpl<*>)?.getText()?.trim()
                    val t1 = (textNodes.getOrNull(1)?.attributes?.get("nativeImpl") as? DesktopTextNativeImpl<*>)?.getText()?.trim()
                    if (!t0.isNullOrEmpty()) titleText = t0
                    if (!t1.isNullOrEmpty()) subtitleText = t1
                }
                if (badgeGroup != null) {
                    for (item in visibleChildrenOf(badgeGroup, titleBarWidgets)) {
                        if (item.attributes.containsKey("captureBgra") || item.attributes.containsKey("captureRgba")) {
                            miniSpectrumWidget = item
                        } else {
                            diagnosticItems.add(item)
                        }
                    }
                }
            }
        }

        val hudPadX = 12
        val topBannerBottom = if (subtitleText.isNotEmpty()) top + 52 else top + 36
        val bottomHudTop = stageBottom - 76
        val bottomHudBottom = stageBottom

        val transportChildren = if (transportBar != null) visibleChildrenOf(transportBar, titleBarWidgets) else emptyList()
        val seekContainerWidget = transportChildren.firstOrNull {
            hasOverlayRole(it, DesktopStageOverlaySpec.ROLE_SEEK_CONTAINER) ||
                Win32NativeWindowHost.widgetHasClass(it, "gui-layered-layout")
        }
        val seekWidget = transportChildren.firstOrNull { it.attributes["nativeImpl"] is DesktopRangeSliderNativeImpl<*> }
            ?: seekContainerWidget?.let { seekContainer ->
                visibleChildrenOf(seekContainer, titleBarWidgets).firstOrNull {
                    it.attributes["nativeImpl"] is DesktopRangeSliderNativeImpl<*>
                } ?: visibleChildrenOf(seekContainer, titleBarWidgets).firstNotNullOfOrNull { layer ->
                    visibleChildrenOf(layer, titleBarWidgets).firstOrNull {
                        it.attributes["nativeImpl"] is DesktopRangeSliderNativeImpl<*>
                    }
                }
            }
        val seekHoverBubbleWidget = (if (seekContainerWidget != null) visibleChildrenOf(seekContainerWidget, titleBarWidgets) else transportChildren)
            .firstOrNull {
                hasOverlayRole(it, DesktopStageOverlaySpec.ROLE_HOVER_BUBBLE) ||
                    Win32NativeWindowHost.widgetHasClass(it, "gui-text-bubble")
            }
        val controlsRowWidget = transportChildren.firstOrNull {
            hasOverlayRole(it, DesktopStageOverlaySpec.ROLE_CONTROLS_ROW) || isHorizontalRowContainer(it)
        }

        fun flattenControlRowItems(container: DesktopNativeWidget): List<DesktopNativeWidget> {
            val result = mutableListOf<DesktopNativeWidget>()
            for (child in visibleChildrenOf(container, titleBarWidgets)) {
                if (isOverlayPopoverWidget(child)) {
                    continue
                }
                val impl = child.attributes["nativeImpl"]
                val isPillGroup = impl is DesktopStyledContainerNativeImpl<*> ||
                    isExpandingSliderPillWidget(child) ||
                    hasOverlayRole(child, DesktopStageOverlaySpec.ROLE_TIME_READOUT)
                val isLeaf = isPillGroup ||
                    impl is DesktopButtonNativeImpl<*> ||
                    impl is DesktopTextNativeImpl<*> ||
                    impl is DesktopRangeSliderNativeImpl<*> ||
                    impl is DesktopSelectNativeImpl<*> ||
                    impl is DesktopCheckboxNativeImpl<*> ||
                    isSpacerWidget(child)
                if (isLeaf) {
                    result.add(child)
                } else {
                    result.addAll(flattenControlRowItems(child))
                }
            }
            return result
        }

        val (leftItems, rightItems) = if (controlsRowWidget != null) {
            val rawRowItems = visibleChildrenOf(controlsRowWidget, titleBarWidgets)
            val leftGroup = rawRowItems.firstOrNull { hasOverlayRole(it, DesktopStageOverlaySpec.ROLE_CONTROLS_LEFT) }
            val rightGroup = rawRowItems.firstOrNull { hasOverlayRole(it, DesktopStageOverlaySpec.ROLE_CONTROLS_RIGHT) }
            if (leftGroup != null || rightGroup != null) {
                val l = if (leftGroup != null) {
                    flattenControlRowItems(leftGroup).filter { !isSpacerWidget(it) }
                } else {
                    flattenControlRowItems(controlsRowWidget).filter { it !== rightGroup && !isSpacerWidget(it) }
                }
                val r = if (rightGroup != null) {
                    if (rightGroup.attributes["nativeImpl"] is DesktopStyledContainerNativeImpl<*>) {
                        listOf(rightGroup)
                    } else {
                        flattenControlRowItems(rightGroup).filter { !isSpacerWidget(it) }
                    }
                } else {
                    emptyList()
                }
                l to r
            } else {
                val flatItems = flattenControlRowItems(controlsRowWidget)
                val fillSpacerIdx = flatItems.indexOfFirst {
                    isFillSpacerWidget(it) || Win32NativeWindowHost.widgetHasClass(it, "ms-auto")
                }
                val spacerIdx = if (fillSpacerIdx >= 0) fillSpacerIdx else flatItems.indexOfFirst { isSpacerWidget(it) }
                if (spacerIdx >= 0) {
                    val l = flatItems.subList(0, spacerIdx).filter { !isSpacerWidget(it) }
                    val r = flatItems.subList(spacerIdx + 1, flatItems.size).filter { !isSpacerWidget(it) }
                    l to r
                } else {
                    val nonSpacerItems = flatItems.filter { !isSpacerWidget(it) }
                    val splitIdx = nonSpacerItems.indexOfFirst {
                        hasOverlayRole(it, DesktopStageOverlaySpec.ROLE_OUTLINED_FEATURE_BADGE) ||
                            Win32NativeWindowHost.widgetHasClass(it, "badge") ||
                            it.attributes["nativeImpl"] is DesktopSelectNativeImpl<*>
                    }
                    if (splitIdx >= 0) {
                        nonSpacerItems.subList(0, splitIdx) to nonSpacerItems.subList(splitIdx, nonSpacerItems.size)
                    } else {
                        nonSpacerItems to emptyList()
                    }
                }
            }
        } else {
            emptyList<DesktopNativeWidget>() to emptyList()
        }

        fun findFirstButtonIn(node: DesktopNativeWidget): DesktopNativeWidget? {
            if (node.attributes["nativeImpl"] is DesktopButtonNativeImpl<*>) return node
            for (c in visibleChildrenOf(node, titleBarWidgets)) {
                val found = findFirstButtonIn(c)
                if (found != null) return found
            }
            return null
        }

        val playPauseBtnWidget = leftItems.firstNotNullOfOrNull { findFirstButtonIn(it) }
        val playPauseBtnImpl = playPauseBtnWidget?.attributes?.get("nativeImpl") as? DesktopButtonNativeImpl<*>
        val playPauseLabel = playPauseBtnImpl?.getText().orEmpty()
        val settingsPanelWidget = findVisibleSettingsPanel(overlayWidget, titleBarWidgets)

        val isPlaying = videoWidget.interactiveSurfaceSpec?.isActiveStream?.invoke() == true ||
            overlayWidget.stageOverlaySpec?.isTargetActive?.invoke(overlayWidget) == true ||
            videoWidget.attributes["playing"] == true ||
            videoWidget.attributes["isPlaying"] == true ||
            videoWidget.attributes["playing"] == "true" ||
            playPauseLabel.contains("Pause", ignoreCase = true) ||
            playPauseLabel.contains("❚❚") ||
            playPauseLabel.contains("⏸")

        val nowMs = GetTickCount64().toLong()
        val explicitHudHidden = overlayWidget.stageOverlaySpec?.isOverlayVisible?.invoke(overlayWidget) == false ||
            overlayWidget.attributes["hudVisible"] == "false" ||
            overlayWidget.attributes["hudVisible"] == false ||
            overlayWidget.attributes["isHudVisible"] == "false" ||
            overlayWidget.attributes["isHudVisible"] == false
        if (lastExplicitHudHidden && !explicitHudHidden) {
            Win32NativeWindowHost.lastVideoStageMouseMoveTickMs = 0L
        }
        lastExplicitHudHidden = explicitHudHidden
        val pointerIdleOrLeft = Win32NativeWindowHost.lastVideoStageMouseMoveTickMs > 0L &&
            (!Win32NativeWindowHost.isPointerInVideoStage ||
                (nowMs - Win32NativeWindowHost.lastVideoStageMouseMoveTickMs) > 5000L)
        val hasPopupOpen = com.nexustools.genericui.event.PopupMouseCaptureManager.hasActivePopup ||
            settingsPanelWidget != null ||
            overlayWidget.attributes["popupOpen"] == "true" ||
            overlayWidget.attributes["popupOpen"] == true
        val hudAutoHidden = !Win32NativeWindowHost.isOverlayDragActive() &&
            !hasPopupOpen &&
            (explicitHudHidden || (isPlaying && pointerIdleOrLeft))
        Win32NativeWindowHost.isVideoStageHudAutoHidden = hudAutoHidden
        Win32NativeWindowHost.overlayHudTargetAlpha = if (hudAutoHidden) 0f else 1f
        if (Win32NativeWindowHost.lastOverlayFadeTickMs == 0.0) {
            Win32NativeWindowHost.overlayHudCurrentAlpha = Win32NativeWindowHost.overlayHudTargetAlpha
        }
        val hudAlpha = Win32NativeWindowHost.overlayHudCurrentAlpha.coerceIn(0f, 1f)

        val suppressInternalBanner = Win32NativeWindowHost.widgetHasClass(videoWidget, "suppress-internal-subtitle-banner") ||
            Win32NativeWindowHost.widgetHasClass(overlayWidget, "suppress-internal-subtitle-banner")

        // Active Subtitle Cue Text (locked at the bottom of the video stage at 1.0 opacity even when HUD fades out!)
        val activeSubtitleCue = if (suppressInternalBanner) {
            if (subtitleBannerWidget != null && subtitleBannerWidget.visible) {
                (subtitleBannerWidget.attributes["bubbleText"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: Win32NativeWindowHost.extractWidgetText(subtitleBannerWidget).trim().takeIf { it.isNotEmpty() }
            } else {
                null
            }
        } else {
            videoWidget.interactiveSurfaceSpec?.activeCaptionText?.invoke()?.trim()?.takeIf { it.isNotEmpty() }
                ?: overlayWidget.stageOverlaySpec?.activeCaptionText?.invoke(overlayWidget)?.trim()?.takeIf { it.isNotEmpty() }
                ?: (videoWidget.attributes["activeSubtitleCueText"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                ?: (overlayWidget.attributes["subtitleText"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                ?: (subtitleBannerWidget?.attributes?.get("bubbleText") as? String)?.trim()?.takeIf { it.isNotEmpty() }
                ?: subtitleBannerWidget?.let { Win32NativeWindowHost.extractWidgetText(it).trim().takeIf { s -> s.isNotEmpty() } }
        }

        val cueLines = activeSubtitleCue?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val lineCount = cueLines.size.coerceAtLeast(1)
        val subBottomFixed = stageBottom - 18
        val subHFixed = 22 * lineCount + 6
        val subTopFixed = (subBottomFixed - subHFixed).coerceAtLeast(top + 48)
        val subWFixed = if (cueLines.isNotEmpty()) {
            val maxLineW = cueLines.maxOf { measureSingleLineTextWidth(it, 8) }
            (maxLineW + 28).coerceIn(80, (availW * 88 / 100).coerceAtLeast(120))
        } else {
            160.coerceAtMost(availW)
        }
        val subLeftFixed = left + (availW - subWFixed) / 2
        val subRightFixed = subLeftFixed + subWFixed
        subtitleBannerWidget?.bounds = com.nexustools.genericui.geometry.Rect(
            subLeftFixed.toFloat(),
            subTopFixed.toFloat(),
            subWFixed.toFloat(),
            subHFixed.toFloat()
        )

        fun drawLockedBottomSubtitleBanner(
            drawBackground: Boolean = true,
            drawForeground: Boolean = true
        ) {
            if (cueLines.isEmpty()) return
            if (drawBackground) {
                Win32NativeWindowHost.drawAntialiasedRoundedRectRgba(
                    hdc = hdc,
                    left = subLeftFixed,
                    top = subTopFixed,
                    right = subRightFixed,
                    bottom = subBottomFixed,
                    radiusPx = 6f,
                    fillColor = RgbaColor(0, 0, 0, 209),
                    borderColor = RgbaColor.TRANSPARENT,
                    borderWidthPx = 0f
                )
            }
            if (drawForeground) {
                SelectObject(hdc, fontBold ?: fontNormal)
                for ((idx, lineText) in cueLines.withIndex()) {
                    val lineTop = subTopFixed + 3 + idx * 22
                    val lineBottom = (lineTop + 22).coerceAtMost(subBottomFixed - 2)
                    Win32NativeWindowHost.drawTextInRect(
                        hdc = hdc,
                        text = lineText,
                        left = subLeftFixed + 12,
                        top = lineTop,
                        right = subRightFixed - 12,
                        bottom = lineBottom,
                        colorRef = 0xFFFFFFu,
                        center = true
                    )
                }
            }
        }

        fun unwrapPillChildren(pillWidget: DesktopNativeWidget): List<DesktopNativeWidget> {
            val direct = visibleChildrenOf(pillWidget, titleBarWidgets)
                .filter { !isOverlayPopoverWidget(it) && !isSpacerWidget(it) }
            if (direct.size == 1) {
                val single = direct.first()
                val singleImpl = single.attributes["nativeImpl"]
                if (singleImpl !is DesktopButtonNativeImpl<*> &&
                    singleImpl !is DesktopTextNativeImpl<*> &&
                    singleImpl !is DesktopRangeSliderNativeImpl<*> &&
                    singleImpl !is DesktopSelectNativeImpl<*> &&
                    singleImpl !is DesktopCheckboxNativeImpl<*>
                ) {
                    val nested = visibleChildrenOf(single, titleBarWidgets)
                        .filter { !isOverlayPopoverWidget(it) && !isSpacerWidget(it) }
                    if (nested.isNotEmpty()) return nested
                }
            }
            return direct
        }

        fun isTimeReadoutPill(w: DesktopNativeWidget): Boolean {
            if (hasOverlayRole(w, DesktopStageOverlaySpec.ROLE_TIME_READOUT)) return true
            if (w.attributes["nativeImpl"] !is DesktopStyledContainerNativeImpl<*>) return false
            val kids = unwrapPillChildren(w)
            return kids.isNotEmpty() && kids.all { it.attributes["nativeImpl"] is DesktopTextNativeImpl<*> }
        }

        val isStageHovered = Win32NativeWindowHost.isRectHovered(hwnd, left, top, right, stageBottom)
        val rawHoveredStagePtr = if (isStageHovered) (Win32NativeWindowHost.hoveredWidget?.nativePointer ?: 0L) else 0L
        val timePillWidget = leftItems.firstOrNull { hasOverlayRole(it, DesktopStageOverlaySpec.ROLE_TIME_READOUT) }
            ?: leftItems.firstOrNull { isTimeReadoutPill(it) }
        val timePillTxt = timePillWidget?.let { Win32NativeWindowHost.extractWidgetText(it).trim() }.orEmpty()
        val timePillWidthBucket = if (timePillTxt.isEmpty()) 0 else if (timePillTxt.length > 13) 136 else 116
        val hoveredStagePtr = if (
            rawHoveredStagePtr == seekWidget?.nativePointer ||
            rawHoveredStagePtr == seekContainerWidget?.nativePointer ||
            rawHoveredStagePtr == seekHoverBubbleWidget?.nativePointer ||
            rawHoveredStagePtr == timePillWidget?.nativePointer ||
            rawHoveredStagePtr == centerOverlay?.nativePointer ||
            rawHoveredStagePtr == videoWidget.nativePointer ||
            rawHoveredStagePtr == overlayWidget.nativePointer ||
            rawHoveredStagePtr == transportBar?.nativePointer ||
            rawHoveredStagePtr == controlsRowWidget?.nativePointer
        ) {
            0L
        } else {
            rawHoveredStagePtr
        }
        val rawPressedStagePtr = Win32NativeWindowHost.pressedWidget?.nativePointer ?: 0L
        val pressedStagePtr = if (
            rawPressedStagePtr == seekWidget?.nativePointer ||
            rawPressedStagePtr == seekContainerWidget?.nativePointer ||
            rawPressedStagePtr == seekHoverBubbleWidget?.nativePointer ||
            rawPressedStagePtr == timePillWidget?.nativePointer ||
            rawPressedStagePtr == centerOverlay?.nativePointer ||
            rawPressedStagePtr == videoWidget.nativePointer ||
            rawPressedStagePtr == overlayWidget.nativePointer ||
            rawPressedStagePtr == transportBar?.nativePointer ||
            rawPressedStagePtr == controlsRowWidget?.nativePointer
        ) {
            0L
        } else {
            rawPressedStagePtr
        }
        val miniSpecSwapCount = (miniSpectrumWidget?.attributes?.get("swapCount") as? (() -> Long))?.invoke() ?: 0L
        var otherMutHash = 0
        for (extra in otherChildren) {
            if (!extra.visible || extra.disposed) continue
            if (extra === subtitleBannerWidget ||
                extra === seekHoverBubbleWidget ||
                hasOverlayRole(extra, DesktopStageOverlaySpec.ROLE_CAPTION_BANNER)
            ) {
                continue
            }
            otherMutHash = 31 * otherMutHash + extra.subtreeMutationVersion.toInt()
        }

        fun drawDynamicVideoStageHudLayer(
            timePillRelBounds: com.nexustools.genericui.geometry.Rect?,
            registerHitRegions: Boolean = true,
            drawTimePill: Boolean = true,
            drawBackground: Boolean = true,
            drawForeground: Boolean = true,
            drawMiniSpectrum: Boolean = false
        ) {
            if (hudAlpha <= 0.01f) return
            if (drawBackground && drawMiniSpectrum && miniSpectrumWidget != null && miniSpectrumWidget.visible) {
                val specW = 148
                val specH = 34
                val specL = right - 14 - specW
                val specT = top + 11
                Win32NativeWindowHost.renderInlineCanvasSurface(
                    hdc = hdc,
                    canvasWidget = miniSpectrumWidget,
                    left = specL,
                    top = specT,
                    right = specL + specW,
                    bottom = specT + specH,
                    theme = theme,
                    registerAsPrimaryCanvas = false
                )
            }
            val sliderImpl = seekWidget?.attributes?.get("nativeImpl") as? DesktopRangeSliderNativeImpl<*>
            val sL = left + hudPadX
            val sR = right - hudPadX
            val sT = bottomHudTop + 6
            val sB = bottomHudTop + 22
            if (drawBackground && sliderImpl != null && seekWidget != null) {
                Win32NativeWindowHost.drawSliderControl(hdc, theme, sliderImpl, sL, sT, sR, sB, opacity = hudAlpha)
                if (registerHitRegions) {
                    Win32NativeWindowHost.addHitRegion(
                        Win32NativeWindowHost.HitRegion(
                            left = sL,
                            top = sT,
                            right = sR,
                            bottom = sB,
                            tooltip = nonRedundantTooltip(sliderImpl.getTooltipText()),
                            widget = seekWidget,
                            onClick = { mx, _ ->
                                sliderImpl.beginDragFromFraction((mx - sL).toDouble() / max(1, sR - sL).toDouble())
                            },
                            onDrag = { mx, _ ->
                                sliderImpl.updateDragFromFraction((mx - sL).toDouble() / max(1, sR - sL).toDouble())
                            },
                            onRelease = { mx, _ ->
                                sliderImpl.endDragFromFraction((mx - sL).toDouble() / max(1, sR - sL).toDouble())
                            }
                        )
                    )
                }
            }
            var pendingBubbleText: String? = null
            var bubbleL = 0
            var bubbleT = 0
            var bubbleR = 0
            var bubbleB = 0
            if (seekHoverBubbleWidget != null && seekHoverBubbleWidget.visible) {
                val bubbleText = Win32NativeWindowHost.extractWidgetText(seekHoverBubbleWidget).trim()
                if (bubbleText.isNotEmpty()) {
                    pendingBubbleText = bubbleText
                    val bubbleW = (measureSingleLineTextWidth(bubbleText, 7) + 18).coerceIn(48, 120)
                    val bubbleH = 24
                    val leftProp = seekHoverBubbleWidget.resolveCssValue("left")?.trim().orEmpty()
                    val pctAttr = (seekHoverBubbleWidget.attributes["layerLeftPercent"] as? String)?.toFloatOrNull()
                        ?: leftProp.takeIf { it.endsWith("%") }?.removeSuffix("%")?.trim()?.toFloatOrNull()
                    val rawBubbleL = if (pctAttr != null) {
                        sL + ((pctAttr.coerceIn(0f, 100f) / 100f) * (sR - sL)).roundToInt() - bubbleW / 2
                    } else {
                        val styleLeftPx = leftProp.removeSuffix("px").trim().toFloatOrNull()?.roundToInt() ?: 0
                        sL + styleLeftPx
                    }
                    bubbleL = rawBubbleL.coerceIn(sL, (sR - bubbleW).coerceAtLeast(sL))
                    bubbleR = bubbleL + bubbleW
                    bubbleB = sT - 4
                    bubbleT = bubbleB - bubbleH
                    if (drawBackground) {
                        Win32NativeWindowHost.drawAntialiasedRoundedRectRgba(
                            hdc = hdc,
                            left = bubbleL,
                            top = bubbleT,
                            right = bubbleR,
                            bottom = bubbleB,
                            radiusPx = 6f,
                            fillColor = RgbaColor(16, 14, 18, 240),
                            borderColor = RgbaColor(255, 255, 255, 56),
                            borderWidthPx = 1f,
                            opacity = hudAlpha
                        )
                    }
                }
            }
            if (!drawForeground) return
            if (drawTimePill && timePillRelBounds != null && timePillTxt.isNotEmpty()) {
                val pL = left + timePillRelBounds.x
                val pT = top + timePillRelBounds.y
                val pR = pL + timePillRelBounds.width
                val pB = pT + timePillRelBounds.height
                SelectObject(hdc, fontSmallBold ?: fontSmall)
                Win32NativeWindowHost.drawTextInRect(
                    hdc = hdc,
                    text = timePillTxt,
                    left = pL + 8,
                    top = pT + 2,
                    right = pR - 8,
                    bottom = pB - 2,
                    colorRef = 0xFAF5F2u,
                    center = true
                )
            }
            if (pendingBubbleText != null) {
                SelectObject(hdc, fontSmallBold ?: fontSmall)
                Win32NativeWindowHost.drawTextInRect(
                    hdc = hdc,
                    text = pendingBubbleText,
                    left = bubbleL + 4,
                    top = bubbleT + 1,
                    right = bubbleR - 4,
                    bottom = bubbleB - 1,
                    colorRef = 0xFFFFFFu,
                    center = true
                )
            }
        }

        val centerChildren = if (centerOverlay != null) visibleChildrenOf(centerOverlay, titleBarWidgets) else emptyList()
        val hasCenterSpinner = centerChildren.any {
            it.attributes["nativeImpl"] is DesktopSpinnerNativeImpl<*> ||
                it.attributes["nativeImpl"] is DesktopProgressBarNativeImpl<*> ||
                Win32NativeWindowHost.widgetHasClass(it, "spinner-border")
        }
        val showCenterButton = !(isPlaying && hasActiveVideoFrames && !hasCenterSpinner) || isStageHovered
        val hasVisiblePopup = settingsPanelWidget != null

        val stageCacheKey = if (!isResizeFrame && !hasVisiblePopup && !hadVisibleVideoStagePopupLastFrame && canUseSurfaceRegionCache(left, top, right, stageBottom)) {
            VideoStageSurfaceCacheKey(
                videoWidgetPtr = videoWidget.nativePointer,
                swapCount = videoSwapCount,
                miniSpectrumSwapCount = miniSpecSwapCount,
                overlaySubtreeVer = overlayWidget.subtreeMutationVersion,
                otherChildrenMutHash = otherMutHash,
                hudAlphaQuantized = (hudAlpha * 100f).roundToInt(),
                isPlaying = isPlaying,
                titleTextHash = titleText.hashCode(),
                subtitleTextHash = subtitleText.hashCode(),
                activeSubtitleCueHash = activeSubtitleCue.orEmpty().hashCode(),
                settingsPanelVisible = false,
                settingsPanelMutVer = 0L,
                timePillWidthBucket = timePillWidthBucket,
                isStageHovered = showCenterButton,
                hoveredStageWidgetPtr = hoveredStagePtr,
                pressedStageWidgetPtr = pressedStagePtr,
                left = left,
                top = top,
                width = availW,
                height = stageH,
                fullViewportMode = fullViewportMode,
                isDark = theme.isDark
            )
        } else {
            null
        }
        val captureStaticStageSurface = stageCacheKey != null && !hasActiveVideoFrames
        if (captureStaticStageSurface) {
            val cachedRegion = cachedVideoStageSurfaces[stageCacheKey!!]
            if (cachedRegion != null) {
                val sameAsLastFrame = isFastPathStagePaint &&
                    stageCacheKey == lastPaintedVideoStageCacheKey &&
                    lastPaintedVideoStageHwnd == hwnd
                val timePillUnchanged = sameAsLastFrame && timePillTxt == lastPaintedTimePillTxt
                val dirtyTopY = if (sameAsLastFrame) max(top, bottomHudTop - 26) else top
                val dirtyBottomY = if (timePillUnchanged) min(stageBottom, bottomHudTop + 24) else stageBottom
                val rowStart = (dirtyTopY - top).coerceIn(0, stageH)
                val rowEnd = (dirtyBottomY - top).coerceIn(rowStart, stageH)
                if (!sameAsLastFrame && isFastPathStagePaint) {
                    Win32NativeWindowHost.removeHitRegionsInRect(left, top, right, stageBottom)
                }
                if (tryBlitCachedRegion(
                        region = cachedRegion,
                        left = left,
                        top = top,
                        right = right,
                        bottom = stageBottom,
                        rowStart = rowStart,
                        rowEnd = rowEnd,
                        restoreHitRegions = !sameAsLastFrame
                    )
                ) {
                    drawDynamicVideoStageHudLayer(
                        timePillRelBounds = cachedRegion.timePillRelBounds,
                        registerHitRegions = !sameAsLastFrame,
                        drawTimePill = !timePillUnchanged,
                        drawMiniSpectrum = !sameAsLastFrame
                    )
                    lastPaintedVideoStageCacheKey = stageCacheKey
                    lastPaintedVideoStageHwnd = hwnd
                    lastPaintedTimePillTxt = timePillTxt
                    hadVisibleVideoStagePopupLastFrame = false
                    Win32NativeWindowHost.lastVideoStageFastBlitRect[0] = left
                    Win32NativeWindowHost.lastVideoStageFastBlitRect[1] = dirtyTopY
                    Win32NativeWindowHost.lastVideoStageFastBlitRect[2] = right
                    Win32NativeWindowHost.lastVideoStageFastBlitRect[3] = dirtyBottomY
                    return stageBottom
                }
            }
        }
        val inset = if (fullViewportMode) 0 else 2
        if (isFastPathStagePaint) {
            Win32NativeWindowHost.removeHitRegionsInRect(left, top, right, stageBottom)
        }
        val stageHitStartIdx = Win32NativeWindowHost.getHitRegionsCount()
        var capturedTimePillRelBounds: com.nexustools.genericui.geometry.Rect? = null

        fun drawVideoStageFrameBackdropAndVideo() {
            val canSkipOuterBorderFill = hasActiveVideoFrames &&
                isFastPathStagePaint &&
                Win32NativeWindowHost.activeMainBackBufferPreserved &&
                lastPaintedVideoStageHwnd == hwnd &&
                lastPaintedVideoStageCacheKey != null &&
                lastPaintedVideoStageCacheKey!!.left == left &&
                lastPaintedVideoStageCacheKey!!.top == top &&
                lastPaintedVideoStageCacheKey!!.width == availW &&
                lastPaintedVideoStageCacheKey!!.height == stageH &&
                lastPaintedVideoStageCacheKey!!.fullViewportMode == fullViewportMode
            if (!canSkipOuterBorderFill) {
                if (fullViewportMode) {
                    Win32NativeWindowHost.fillRectColor(hdc, left, top, right, stageBottom, 0x0A0705u)
                } else {
                    Win32NativeWindowHost.drawRoundedRect(
                        hdc = hdc,
                        left = left,
                        top = top,
                        right = right,
                        bottom = stageBottom,
                        radius = 8,
                        fillColor = 0x0A0705u,
                        borderColor = theme.borderSubtle
                    )
                }
            }
            if (hasActiveVideoFrames) {
                Win32NativeWindowHost.renderInlineCanvasSurface(
                    hdc = hdc,
                    canvasWidget = videoWidget,
                    left = left + inset,
                    top = top + inset,
                    right = right - inset,
                    bottom = stageBottom - inset,
                    theme = theme,
                    registerAsPrimaryCanvas = false
                )
            }
        }

        fun renderStaticHudBandsPass() {
            // 2. Top-left Overlaid Title & Subtitle touching the top/left/right edges of the video stage
            val topStyledVisuals = (topHudBar?.attributes?.get("nativeImpl") as? DesktopStyledContainerNativeImpl<*>)?.getStyledVisuals()
            val topGrad = topStyledVisuals?.backgroundGradient
                ?: (topHudBar?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>)?.getEffectiveBackgroundGradient()
                ?: com.nexustools.genericui.style.gradient.Gradient.LinearGradient(180f, RgbaColor(0, 0, 0, 217), RgbaColor(0, 0, 0, 0))
            Win32NativeWindowHost.drawGradientRect(hdc, left, top, right, topBannerBottom, topGrad, opacity = hudAlpha)

            // Optional Mini-Spectrum & Top-Right Overlay Badges/Buttons in top-right of video stage
            var topRightCursorX = right - 14
            if (miniSpectrumWidget != null) {
                val specW = 148
                val specH = 34
                val specL = topRightCursorX - specW
                val specT = top + 11
                Win32NativeWindowHost.renderInlineCanvasSurface(
                    hdc = hdc,
                    canvasWidget = miniSpectrumWidget,
                    left = specL,
                    top = specT,
                    right = specL + specW,
                    bottom = specT + specH,
                    theme = theme,
                    registerAsPrimaryCanvas = false
                )
                topRightCursorX = specL - 8
            }
            val topDiagnosticStartX = topRightCursorX
            for ( pass in 1..2) {
                val drawBg = pass == 1
                val drawFg = pass == 2
                if (drawFg) {
                    SelectObject(hdc, fontBold)
                    Win32NativeWindowHost.drawTextInRect(
                        hdc = hdc,
                        text = titleText,
                        left = left + hudPadX,
                        top = top + 12,
                        right = right - (if (miniSpectrumWidget != null) 180 else hudPadX),
                        bottom = top + 32,
                        colorRef = 0xFAF5F2u
                    )
                    if (subtitleText.isNotEmpty()) {
                        SelectObject(hdc, fontSmall)
                        Win32NativeWindowHost.drawTextInRect(
                            hdc = hdc,
                            text = subtitleText,
                            left = left + hudPadX,
                            top = top + 30,
                            right = right - (if (miniSpectrumWidget != null) 180 else hudPadX),
                            bottom = top + 48,
                            colorRef = 0xB8A99Eu
                        )
                    }
                }
                topRightCursorX = topDiagnosticStartX
                for (item in diagnosticItems.asReversed()) {
                    val btnImpl = item.attributes["nativeImpl"] as? DesktopButtonNativeImpl<*>
                    val txtImpl = item.attributes["nativeImpl"] as? DesktopTextNativeImpl<*>
                    val label = (btnImpl?.getText() ?: txtImpl?.getText() ?: Win32NativeWindowHost.extractWidgetText(item)).trim()
                    val icon = btnImpl?.getIcon()?.trim()?.takeIf { it.isNotEmpty() }
                    if (label.isEmpty() && icon == null) continue
                    val pillW = if (label.isEmpty()) 28 else (measureSingleLineTextWidth(label, 7) + 18).coerceIn(28, 128)
                    val pillL = topRightCursorX - pillW
                    val pillT = top + 10
                    val pillB = pillT + 26
                    if (pillL <= left + 160) break
                    if (hasOverlayRole(item, DesktopStageOverlaySpec.ROLE_OUTLINED_FEATURE_BADGE) ||
                        item.resolveCssValue("border-radius") == "999px"
                    ) {
                        if (drawBg) {
                            Win32NativeWindowHost.drawAntialiasedRoundedRectRgba(
                                hdc = hdc,
                                left = pillL,
                                top = pillT,
                                right = topRightCursorX,
                                bottom = pillB,
                                radiusPx = 13f,
                                fillColor = null,
                                borderColor = RgbaColor(255, 255, 255, 255),
                                borderWidthPx = 1f,
                                opacity = hudAlpha
                            )
                        }
                        if (drawFg) {
                            SelectObject(hdc, fontSmallBold ?: fontSmall)
                            Win32NativeWindowHost.drawTextInRect(
                                hdc = hdc,
                                text = label,
                                left = pillL + 6,
                                top = pillT + 1,
                                right = topRightCursorX - 6,
                                bottom = pillB - 1,
                                colorRef = 0xFFFFFFu,
                                center = true
                            )
                        }
                        topRightCursorX = pillL - 6
                        continue
                    }
                    if (btnImpl is DesktopStyledButtonNativeImpl<*>) {
                        if (drawBg) {
                            Win32NativeWindowHost.drawButtonControl(
                                hdc = hdc,
                                theme = theme,
                                label = label,
                                icon = icon,
                                left = pillL,
                                top = pillT,
                                right = topRightCursorX,
                                bottom = pillB,
                                primary = false,
                                widget = item,
                                hwndForHover = hwnd,
                                drawBackground = true,
                                drawForeground = false,
                                opacity = hudAlpha
                            )
                        }
                        if (drawFg) {
                            SelectObject(hdc, fontSmallBold ?: fontSmall)
                            val drewCustomImg = drawCustomButtonIconIfPresent(
                                hwnd = hwnd,
                                hdc = hdc,
                                theme = theme,
                                fontFallback = fontSmallBold ?: fontSmall,
                                buttonWidget = item,
                                left = pillL,
                                top = pillT,
                                right = topRightCursorX,
                                bottom = pillB,
                                surfaceBgColorRef = 0x18120Fu,
                                transparentBackground = true,
                                opacity = hudAlpha
                            )
                            if (!drewCustomImg) {
                                Win32NativeWindowHost.drawButtonControl(
                                    hdc = hdc,
                                    theme = theme,
                                    label = label,
                                    icon = icon,
                                    left = pillL,
                                    top = pillT,
                                    right = topRightCursorX,
                                    bottom = pillB,
                                    primary = false,
                                    widget = item,
                                    hwndForHover = hwnd,
                                    drawBackground = false,
                                    drawForeground = true,
                                    opacity = hudAlpha
                                )
                            }
                        }
                    } else {
                        val isSuccess = btnImpl?.getType() == BootstrapType.SUCCESS || Win32NativeWindowHost.widgetHasClass(item, "bg-success")
                        val isWarning = btnImpl?.getType() == BootstrapType.WARNING || Win32NativeWindowHost.widgetHasClass(item, "bg-warning")
                        val bgCol = when {
                            isSuccess -> theme.accentGreenSoftBg
                            isWarning -> theme.accentAmberSoftBg
                            else -> 0x261C15u
                        }
                        val borderCol = when {
                            isSuccess -> theme.accentGreenBorder
                            isWarning -> theme.accentAmber
                            else -> 0x3D2E24u
                        }
                        val fgCol = when {
                            isSuccess -> theme.accentGreen
                            isWarning -> theme.accentAmber
                            else -> 0xFAF5F2u
                        }
                        if (drawBg) {
                            Win32NativeWindowHost.drawRoundedButton(
                                hdc = hdc,
                                font = fontSmallBold ?: fontSmall,
                                label = label,
                                left = pillL,
                                top = pillT,
                                right = topRightCursorX,
                                bottom = pillB,
                                bg = bgCol,
                                border = borderCol,
                                fg = fgCol,
                                radius = 5,
                                icon = icon,
                                widget = item,
                                hwndForHover = hwnd,
                                drawBackground = true,
                                drawForeground = false,
                                opacity = hudAlpha
                            )
                        }
                        if (drawFg) {
                            val drewCustomImg = drawCustomButtonIconIfPresent(
                                hwnd = hwnd,
                                hdc = hdc,
                                theme = theme,
                                fontFallback = fontSmallBold ?: fontSmall,
                                buttonWidget = item,
                                left = pillL,
                                top = pillT,
                                right = topRightCursorX,
                                bottom = pillB,
                                surfaceBgColorRef = 0x18120Fu,
                                transparentBackground = true,
                                opacity = hudAlpha
                            )
                            if (!drewCustomImg) {
                                Win32NativeWindowHost.drawRoundedButton(
                                    hdc = hdc,
                                    font = fontSmallBold ?: fontSmall,
                                    label = label,
                                    left = pillL,
                                    top = pillT,
                                    right = topRightCursorX,
                                    bottom = pillB,
                                    bg = bgCol,
                                    border = borderCol,
                                    fg = fgCol,
                                    radius = 5,
                                    icon = icon,
                                    widget = item,
                                    hwndForHover = hwnd,
                                    drawBackground = false,
                                    drawForeground = true,
                                    opacity = hudAlpha
                                )
                            }
                        }
                    }
                    if (drawBg && btnImpl != null) {
                        Win32NativeWindowHost.addHitRegion(
                            Win32NativeWindowHost.HitRegion(
                                left = pillL,
                                top = pillT,
                                right = topRightCursorX,
                                bottom = pillB,
                                tooltip = nonRedundantTooltip(btnImpl.getTooltipText(), label),
                                widget = item,
                                onClick = { _, _ -> btnImpl.dispatchClick() }
                            )
                        )
                    }
                    topRightCursorX = pillL - 6
                }

                // 3. Center Play/Pause, Rewind/FFWD, & Spinner Zone inside the 16:9 stage box
                val cx = (left + right) / 2
                val cy = (top + stageBottom) / 2 - 8
                val centerButtons = centerChildren.filter { it.attributes["nativeImpl"] is DesktopButtonNativeImpl<*> }

                val stageHitTop = (topBannerBottom + 4).coerceAtMost(cy - 36)
                val stageHitBottom = (bottomHudTop - 4).coerceAtLeast(cy + 36)
                val dispatchCenterPlayPause: () -> Unit = {
                    if (playPauseBtnImpl != null) {
                        playPauseBtnImpl.dispatchClick()
                    } else {
                        (centerOverlay?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>)?.dispatchClick()
                    }
                }
                if (drawBg && settingsPanelWidget == null) {
                    Win32NativeWindowHost.addHitRegion(
                        Win32NativeWindowHost.HitRegion(
                            left = left + 8,
                            top = stageHitTop,
                            right = right - 8,
                            bottom = stageHitBottom,
                            tooltip = nonRedundantTooltip(playPauseBtnImpl?.getTooltipText()),
                            widget = centerOverlay ?: playPauseBtnWidget ?: videoWidget,
                            onClick = { _, _ ->
                                dispatchCenterPlayPause()
                            }
                        )
                    )
                }

                if (showCenterButton) {
                    if (centerButtons.isNotEmpty()) {
                        val centerGap = 18
                        val buttonDims = centerButtons.map { btnW ->
                            val isMainPlay = hasOverlayRole(btnW, DesktopStageOverlaySpec.ROLE_PRIMARY_CENTER_ACTION) ||
                                btnW.resolveCssValue("width") == "64px" ||
                                centerButtons.size == 1
                            val dim = if (isMainPlay) 64 else 56
                            Triple(btnW, dim, isMainPlay)
                        }
                        val totalClusterW = buttonDims.sumOf { it.second } + centerGap * (buttonDims.size - 1).coerceAtLeast(0)
                        var curBtnX = cx - totalClusterW / 2
                        for ((btnWidget, dim, isMainPlay) in buttonDims) {
                            val btnImpl = btnWidget.attributes["nativeImpl"] as DesktopButtonNativeImpl<*>
                            val bL = curBtnX
                            val bR = curBtnX + dim
                            val bT = cy - dim / 2
                            val bB = cy + dim / 2
                            val isBtnHovered = (Win32NativeWindowHost.hoveredWidget === btnWidget) ||
                                Win32NativeWindowHost.isRectHovered(hwnd, bL, bT, bR, bB)
                            val isBtnPressed = Win32NativeWindowHost.pressedWidget === btnWidget
                            if (drawBg) {
                                val styledVisuals = (btnImpl as? DesktopStyledButtonNativeImpl<*>)?.let {
                                    it.setHoveredState(isBtnHovered)
                                    it.setActivePressedState(isBtnPressed)
                                    it.resolveCurrentVisuals()
                                }
                                val fillCol = styledVisuals?.backgroundColor ?: when {
                                    isBtnPressed -> RgbaColor(38, 44, 58, 235)
                                    isBtnHovered -> RgbaColor(28, 34, 46, 225)
                                    else -> RgbaColor(15, 18, 24, 195)
                                }
                                val borderCol = styledVisuals?.borderColor
                                    ?: RgbaColor(255, 255, 255, if (isBtnHovered) 92 else 56)
                                Win32NativeWindowHost.drawAntialiasedRoundedRectRgba(
                                    hdc = hdc,
                                    left = bL,
                                    top = bT,
                                    right = bR,
                                    bottom = bB,
                                    radiusPx = (dim / 2f),
                                    fillColor = fillCol,
                                    borderColor = borderCol,
                                    borderWidthPx = styledVisuals?.borderWidthPx ?: 1.5f,
                                    opacity = hudAlpha
                                )
                                Win32NativeWindowHost.addHitRegion(
                                    Win32NativeWindowHost.HitRegion(
                                        left = bL,
                                        top = bT,
                                        right = bR,
                                        bottom = bB,
                                        tooltip = nonRedundantTooltip(btnImpl.getTooltipText(), btnImpl.getText()),
                                        widget = btnWidget,
                                        onClick = { _, _ -> btnImpl.dispatchClick() }
                                    )
                                )
                            }
                            if (drawFg) {
                                val fontToUse = if (isMainPlay) fontTitle else (fontBold ?: fontNormal)
                                SelectObject(hdc, fontToUse)
                                val drewImg = if (!(hasCenterSpinner && isMainPlay)) {
                                    drawCustomButtonIconIfPresent(
                                        hwnd = hwnd,
                                        hdc = hdc,
                                        theme = theme,
                                        fontFallback = fontToUse,
                                        buttonWidget = btnWidget,
                                        left = bL,
                                        top = bT,
                                        right = bR,
                                        bottom = bB,
                                        surfaceBgColorRef = 0x18120Fu,
                                        transparentBackground = true,
                                        opacity = hudAlpha
                                    )
                                } else {
                                    false
                                }
                                if (!drewImg) {
                                    val lbl = if (hasCenterSpinner && isMainPlay) "⟳" else btnImpl.getText().trim()
                                    val iconSpec = btnImpl.getIcon()?.trim()?.takeIf { it.isNotEmpty() }
                                    if (lbl.isNotEmpty() || iconSpec != null) {
                                        Win32NativeWindowHost.drawButtonControl(
                                            hdc = hdc,
                                            theme = theme,
                                            label = lbl,
                                            icon = iconSpec,
                                            left = bL,
                                            top = bT,
                                            right = bR,
                                            bottom = bB,
                                            primary = false,
                                            widget = btnWidget,
                                            hwndForHover = hwnd,
                                            drawBackground = false,
                                            drawForeground = true
                                        )
                                    } else {
                                        val fallbackGlyph = if (isPlaying && isMainPlay) "❚❚" else "▶"
                                        Win32NativeWindowHost.drawTextInRect(
                                            hdc = hdc,
                                            text = fallbackGlyph,
                                            left = bL + 4,
                                            top = bT + 2,
                                            right = bR - 4,
                                            bottom = bB - 2,
                                            colorRef = 0xFFFFFFu,
                                            center = true
                                        )
                                    }
                                }
                            }
                            curBtnX = bR + centerGap
                        }
                    } else {
                        if (drawBg) {
                            val isCenterPlayHovered = Win32NativeWindowHost.isRectHovered(hwnd, cx - 32, cy - 32, cx + 32, cy + 32)
                            Win32NativeWindowHost.drawAntialiasedRoundedRectRgba(
                                hdc = hdc,
                                left = cx - 32,
                                top = cy - 32,
                                right = cx + 32,
                                bottom = cy + 32,
                                radiusPx = 32f,
                                fillColor = if (isCenterPlayHovered) RgbaColor(28, 34, 46, 225) else RgbaColor(16, 14, 18, 199),
                                borderColor = RgbaColor(255, 255, 255, if (isCenterPlayHovered) 92 else 56),
                                borderWidthPx = 1.5f,
                                opacity = hudAlpha
                            )
                            Win32NativeWindowHost.addHitRegion(
                                Win32NativeWindowHost.HitRegion(
                                    left = cx - 32,
                                    top = cy - 32,
                                    right = cx + 32,
                                    bottom = cy + 32,
                                    tooltip = nonRedundantTooltip(playPauseBtnImpl?.getTooltipText()),
                                    widget = playPauseBtnWidget ?: centerOverlay ?: videoWidget,
                                    onClick = { _, _ ->
                                        dispatchCenterPlayPause()
                                    }
                                )
                            )
                        }
                        if (drawFg) {
                            SelectObject(hdc, fontTitle)
                            Win32NativeWindowHost.drawTextInRect(
                                hdc = hdc,
                                text = when {
                                    hasCenterSpinner -> "⟳"
                                    isPlaying -> "❚❚"
                                    else -> "▶"
                                },
                                left = cx - 28,
                                top = cy - 22,
                                right = cx + 28,
                                bottom = cy + 22,
                                colorRef = 0xFFFFFFu,
                                center = true
                            )
                        }
                    }
                }

                // 4. Bottom Overlaid Transport HUD touching the bottom/left/right edges of the video stage
                if (drawBg) {
                    val bottomStyledVisuals = (transportBar?.attributes?.get("nativeImpl") as? DesktopStyledContainerNativeImpl<*>)?.getStyledVisuals()
                    val bottomBgRgba = bottomStyledVisuals?.backgroundColor?.let {
                        it.copy(a = (it.a * hudAlpha).roundToInt().coerceIn(0, 255))
                    }
                    val bottomBorderRgba = bottomStyledVisuals?.borderColor?.let {
                        it.copy(a = (it.a * hudAlpha).roundToInt().coerceIn(0, 255))
                    }
                    val bottomGrad = bottomStyledVisuals?.backgroundGradient
                        ?: (transportBar?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>)?.getEffectiveBackgroundGradient()
                        ?: if (transportBar != null && bottomBgRgba == null && bottomBorderRgba == null) {
                            com.nexustools.genericui.style.gradient.Gradient.LinearGradient(180f, RgbaColor(0, 0, 0, 0), RgbaColor(0, 0, 0, 217))
                        } else {
                            null
                        }
                    if (bottomGrad != null) {
                        Win32NativeWindowHost.drawGradientRect(hdc, left, bottomHudTop, right, bottomHudBottom, bottomGrad, opacity = hudAlpha)
                    } else if ((bottomBgRgba != null && bottomBgRgba.a > 0) || (bottomBorderRgba != null && bottomBorderRgba.a > 0 && ((bottomStyledVisuals?.borderWidthPx ?: 0f) > 0f))) {
                        Win32NativeWindowHost.drawAntialiasedRoundedRectRgba(
                            hdc = hdc,
                            left = left,
                            top = bottomHudTop,
                            right = right,
                            bottom = bottomHudBottom,
                            radiusPx = bottomStyledVisuals?.borderRadiusPx ?: 0f,
                            fillColor = bottomBgRgba,
                            borderColor = bottomBorderRgba,
                            borderWidthPx = bottomStyledVisuals?.borderWidthPx ?: 0f
                        )
                    }
                }

                // 4a. Full-width Seek Slider at top of bottom HUD (drawn in dynamic pass when captureStaticStageSurface is true)
                if (!captureStaticStageSurface) {
                    drawDynamicVideoStageHudLayer(null, drawBackground = drawBg, drawForeground = drawFg)
                }

                // 4b. Single-line Transport Controls Row (`bottomHudTop + 26` .. `bottomHudTop + 66`)
                if (controlsRowWidget != null) {
                    val ctrlY = bottomHudTop + 26
                    val ctrlH = 40
                    val gap = 6

                    fun compactOverlayLabel(raw: String): String {
                        val bracketIdx = raw.indexOf(" [")
                        return if (bracketIdx > 0) raw.substring(0, bracketIdx).trim() else raw.trim()
                    }

                    fun measureCompactControlWidth(w: DesktopNativeWidget): Int {
                        if (isSpacerWidget(w)) return 0
                        if (isExpandingSliderPillWidget(w)) {
                            val sliderHost = w.children.getOrNull(1)
                            val animSliderW = (w.attributes["data-animated-slider-width-px"] as? String)?.toIntOrNull()
                                ?: (sliderHost?.attributes?.get("data-animated-width-px") as? String)?.toIntOrNull()
                                ?: sliderHost?.resolveCssValue("width")?.removeSuffix("px")?.trim()?.toIntOrNull()
                                ?: if (w.attributes["data-expanded"] == "true") 92 else 0
                            return if (animSliderW > 0) 40 + 4 + animSliderW + 8 else 40
                        }
                        return when (val impl = w.attributes["nativeImpl"]) {
                            is DesktopStyledContainerNativeImpl<*> -> {
                                if (isTimeReadoutPill(w)) {
                                    val txt = Win32NativeWindowHost.extractWidgetText(w).trim()
                                    if (txt.isEmpty()) 0 else if (txt.length > 13) 136 else 116
                                } else {
                                    val pillKids = unwrapPillChildren(w)
                                    if (pillKids.isEmpty()) 0
                                    else 8 + pillKids.sumOf { measureCompactControlWidth(it) } + 2 * (pillKids.size - 1).coerceAtLeast(0)
                                }
                            }
                            is DesktopButtonNativeImpl<*> -> {
                                val explicitW = w.resolveCssValue("width")?.removeSuffix("px")?.trim()?.toIntOrNull()
                                val lbl = compactOverlayLabel(impl.getText())
                                val hasImgChild = safeChildrenSnapshot(w).any {
                                    it.visible && !it.disposed && it.attributes["nativeImpl"] is DesktopImageNativeImpl<*>
                                }
                                if (explicitW != null && explicitW in 28..72) {
                                    explicitW.coerceAtMost(40)
                                } else if (lbl.length <= 2 || (lbl.isEmpty() && (!impl.getIcon().isNullOrBlank() || hasImgChild))) {
                                    36
                                } else {
                                    (measureSingleLineTextWidth(lbl, 7) + 18).coerceIn(38, 114)
                                }
                            }
                            is DesktopRangeSliderNativeImpl<*> -> 76
                            is DesktopSelectNativeImpl<*> -> {
                                val curVal = impl.getSelectedValue() ?: ""
                                val curLabel = impl.getOptions().firstOrNull { it.first == curVal }?.second ?: curVal
                                val display = curLabel.ifBlank {
                                    if (w === rightItems.firstOrNull()) "Audio" else "Subtitles"
                                }
                                (measureSingleLineTextWidth(display, 7) + 26).coerceIn(66, 120)
                            }
                            is DesktopTextNativeImpl<*> -> {
                                val txt = impl.getText().trim()
                                if (txt.isEmpty()) 0
                                else if (Win32NativeWindowHost.widgetHasClass(w, "badge") ||
                                    hasOverlayRole(w, DesktopStageOverlaySpec.ROLE_OUTLINED_FEATURE_BADGE)
                                ) {
                                    measureSingleLineTextWidth(txt, 7) + 16
                                } else {
                                    measureSingleLineTextWidth(txt, 7) + 8
                                }
                            }
                            else -> 56
                        }
                    }

                    fun renderOverlayRowControl(
                        item: DesktopNativeWidget,
                        itemL: Int,
                        itemR: Int,
                        fallbackIdx: Int,
                        itemTop: Int = ctrlY,
                        itemBottom: Int = ctrlY + ctrlH,
                        inPillContainer: Boolean = false
                    ) {
                        val itemH = itemBottom - itemTop
                        if (isExpandingSliderPillWidget(item)) {
                            if (drawBg) {
                                val visuals = (item.attributes["nativeImpl"] as? DesktopStyledContainerNativeImpl<*>)?.getStyledVisuals()
                                Win32NativeWindowHost.drawAntialiasedRoundedRectRgba(
                                    hdc = hdc,
                                    left = itemL,
                                    top = itemTop,
                                    right = itemR,
                                    bottom = itemBottom,
                                    radiusPx = (visuals?.borderRadiusPx ?: 20f).coerceAtLeast(itemH / 2f),
                                    fillColor = visuals?.backgroundColor ?: RgbaColor(15, 18, 24, 200),
                                    borderColor = visuals?.borderColor ?: RgbaColor(255, 255, 255, 36),
                                    borderWidthPx = (visuals?.borderWidthPx ?: 1f).coerceAtLeast(1f),
                                    opacity = hudAlpha
                                )
                                Win32NativeWindowHost.addHitRegion(
                                    Win32NativeWindowHost.HitRegion(
                                        left = itemL,
                                        top = itemTop,
                                        right = itemR,
                                        bottom = itemBottom,
                                        tooltip = null,
                                        widget = item
                                    )
                                )
                            }
                            val muteBtn = item.children.getOrNull(0)
                            if (muteBtn != null && muteBtn.visible && !muteBtn.disposed) {
                                renderOverlayRowControl(muteBtn, itemL + 2, min(itemR - 2, itemL + 38), fallbackIdx, itemTop + 2, itemBottom - 2, inPillContainer = true)
                            }
                            if (drawBg) {
                                val sliderHost = item.children.getOrNull(1)
                                val animSliderW = (item.attributes["data-animated-slider-width-px"] as? String)?.toIntOrNull()
                                    ?: (sliderHost?.attributes?.get("data-animated-width-px") as? String)?.toIntOrNull()
                                    ?: sliderHost?.resolveCssValue("width")?.removeSuffix("px")?.trim()?.toIntOrNull()
                                    ?: if (item.attributes["data-expanded"] == "true") 92 else 0
                                val sliderWidget = sliderHost?.children?.firstOrNull()
                                val sliderImpl = sliderWidget?.attributes?.get("nativeImpl") as? DesktopRangeSliderNativeImpl<*>
                                if (animSliderW >= 8 && sliderWidget != null && sliderImpl != null && itemR - (itemL + 40) >= 8) {
                                    val slL = itemL + 40
                                    val slR = (itemR - 8).coerceAtLeast(slL + 8)
                                    val savedClip = Win32NativeWindowHost.pushClipRect(hdc, itemL + 38, itemTop, itemR - 4, itemBottom)
                                    try {
                                        Win32NativeWindowHost.drawSliderControl(hdc, theme, sliderImpl, slL, itemTop + 6, slR, itemBottom - 6, opacity = hudAlpha)
                                    } finally {
                                        Win32NativeWindowHost.popClipRect(hdc, savedClip)
                                    }
                                    val thumbR = 5
                                    val trackL = min(slR - 2, slL + thumbR)
                                    val trackR = max(trackL + 2, slR - thumbR)
                                    Win32NativeWindowHost.addHitRegion(
                                        Win32NativeWindowHost.HitRegion(
                                            left = slL,
                                            top = itemTop,
                                            right = slR,
                                            bottom = itemBottom,
                                            tooltip = nonRedundantTooltip(sliderImpl.getTooltipText()),
                                            widget = sliderWidget,
                                            onClick = { mx, _ ->
                                                sliderImpl.beginDragFromFraction((mx - trackL).toDouble() / max(1, trackR - trackL).toDouble())
                                            },
                                            onDrag = { mx, _ ->
                                                sliderImpl.updateDragFromFraction((mx - trackL).toDouble() / max(1, trackR - trackL).toDouble())
                                            },
                                            onRelease = { mx, _ ->
                                                sliderImpl.endDragFromFraction((mx - trackL).toDouble() / max(1, trackR - trackL).toDouble())
                                            }
                                        )
                                    )
                                }
                            }
                            return
                        }
                        when (val impl = item.attributes["nativeImpl"]) {
                            is DesktopStyledContainerNativeImpl<*> -> {
                                if (drawBg) {
                                    val visuals = impl.getStyledVisuals()
                                    Win32NativeWindowHost.drawAntialiasedRoundedRectRgba(
                                        hdc = hdc,
                                        left = itemL,
                                        top = itemTop,
                                        right = itemR,
                                        bottom = itemBottom,
                                        radiusPx = (visuals.borderRadiusPx ?: 0f).coerceAtLeast(itemH / 2f),
                                        fillColor = visuals.backgroundColor ?: RgbaColor(15, 18, 24, 200),
                                        borderColor = visuals.borderColor ?: RgbaColor(255, 255, 255, 36),
                                        borderWidthPx = (visuals.borderWidthPx ?: 1f).coerceAtLeast(1f),
                                        opacity = hudAlpha
                                    )
                                }
                                if (isTimeReadoutPill(item)) {
                                    if (drawBg) {
                                        capturedTimePillRelBounds = com.nexustools.genericui.geometry.Rect(
                                            itemL - left,
                                            itemTop - top,
                                            itemR - itemL,
                                            itemBottom - itemTop
                                        )
                                    }
                                    if (drawFg && !captureStaticStageSurface) {
                                        val txt = Win32NativeWindowHost.extractWidgetText(item).trim()
                                        if (txt.isNotEmpty()) {
                                            SelectObject(hdc, fontSmallBold ?: fontSmall)
                                            Win32NativeWindowHost.drawTextInRect(
                                                hdc = hdc,
                                                text = txt,
                                                left = itemL + 8,
                                                top = itemTop + 2,
                                                right = itemR - 8,
                                                bottom = itemBottom - 2,
                                                colorRef = 0xFAF5F2u,
                                                center = true
                                            )
                                        }
                                    }
                                } else {
                                    val pillKids = unwrapPillChildren(item)
                                    var cx = itemL + 4
                                    for ((kIdx, kid) in pillKids.withIndex()) {
                                        val kw = measureCompactControlWidth(kid)
                                        if (kw <= 0) continue
                                        val kr = (cx + kw).coerceAtMost(itemR - 4)
                                        if (cx >= kr) break
                                        renderOverlayRowControl(kid, cx, kr, kIdx, itemTop + 2, itemBottom - 2, inPillContainer = true)
                                        cx = kr + 2
                                    }
                                }
                            }
                            is DesktopButtonNativeImpl<*> -> {
                                val lbl = compactOverlayLabel(impl.getText())
                                val isBtnHovered = (Win32NativeWindowHost.hoveredWidget === item) ||
                                    Win32NativeWindowHost.isRectHovered(hwnd, itemL, itemTop, itemR, itemBottom)
                                val isBtnPressed = Win32NativeWindowHost.pressedWidget === item
                                if (drawBg) {
                                    val btnRadius = (itemBottom - itemTop) / 2f
                                    if (inPillContainer) {
                                        if (isBtnHovered || isBtnPressed) {
                                            Win32NativeWindowHost.drawAntialiasedRoundedRectRgba(
                                                hdc = hdc,
                                                left = itemL + 2,
                                                top = itemTop + 2,
                                                right = itemR - 2,
                                                bottom = itemBottom - 2,
                                                radiusPx = (btnRadius - 2f).coerceAtLeast(8f),
                                                fillColor = if (isBtnPressed) RgbaColor(255, 255, 255, 48) else RgbaColor(255, 255, 255, 28),
                                                borderColor = null,
                                                borderWidthPx = 0f,
                                                opacity = hudAlpha
                                            )
                                        }
                                    } else {
                                        val styledVisuals = (impl as? DesktopStyledButtonNativeImpl<*>)?.let {
                                            it.setHoveredState(isBtnHovered)
                                            it.setActivePressedState(isBtnPressed)
                                            it.resolveCurrentVisuals()
                                        }
                                        val fillCol = styledVisuals?.backgroundColor ?: when {
                                            isBtnPressed -> RgbaColor(38, 44, 58, 230)
                                            isBtnHovered -> RgbaColor(28, 34, 46, 218)
                                            else -> RgbaColor(15, 18, 24, 195)
                                        }
                                        val borderCol = styledVisuals?.borderColor
                                            ?: RgbaColor(255, 255, 255, if (isBtnHovered) 76 else 40)
                                        Win32NativeWindowHost.drawAntialiasedRoundedRectRgba(
                                            hdc = hdc,
                                            left = itemL,
                                            top = itemTop,
                                            right = itemR,
                                            bottom = itemBottom,
                                            radiusPx = btnRadius,
                                            fillColor = fillCol,
                                            borderColor = borderCol,
                                            borderWidthPx = (styledVisuals?.borderWidthPx ?: 1f).coerceAtLeast(1f),
                                            opacity = hudAlpha
                                        )
                                    }
                                    Win32NativeWindowHost.addHitRegion(
                                        Win32NativeWindowHost.HitRegion(
                                            left = itemL,
                                            top = itemTop,
                                            right = itemR,
                                            bottom = itemBottom,
                                            tooltip = nonRedundantTooltip(impl.getTooltipText(), impl.getText()),
                                            widget = item,
                                            onClick = { _, _ -> impl.dispatchClick() }
                                        )
                                    )
                                }
                                if (drawFg) {
                                    SelectObject(hdc, fontSmallBold ?: fontSmall)
                                    val drewCustomImg = drawCustomButtonIconIfPresent(
                                        hwnd = hwnd,
                                        hdc = hdc,
                                        theme = theme,
                                        fontFallback = fontSmallBold ?: fontSmall,
                                        buttonWidget = item,
                                        left = itemL,
                                        top = itemTop,
                                        right = itemR,
                                        bottom = itemBottom,
                                        surfaceBgColorRef = 0x18120Fu,
                                        transparentBackground = true,
                                        opacity = hudAlpha
                                    )
                                    if (!drewCustomImg) {
                                        Win32NativeWindowHost.drawButtonControl(
                                            hdc = hdc,
                                            theme = theme,
                                            label = lbl,
                                            icon = impl.getIcon(),
                                            left = itemL,
                                            top = itemTop,
                                            right = itemR,
                                            bottom = itemBottom,
                                            primary = impl.getType() == BootstrapType.PRIMARY,
                                            widget = item,
                                            hwndForHover = hwnd,
                                            drawBackground = false,
                                            drawForeground = true
                                        )
                                    }
                                }
                            }
                            is DesktopRangeSliderNativeImpl<*> -> {
                                if (drawBg) {
                                    Win32NativeWindowHost.drawSliderControl(hdc, theme, impl, itemL, itemTop + 6, itemR, itemBottom - 6, opacity = hudAlpha)
                                    Win32NativeWindowHost.addHitRegion(
                                        Win32NativeWindowHost.HitRegion(
                                            left = itemL,
                                            top = itemTop,
                                            right = itemR,
                                            bottom = itemBottom,
                                            tooltip = nonRedundantTooltip(impl.getTooltipText()),
                                            widget = item,
                                            onClick = { mx, _ ->
                                                impl.beginDragFromFraction((mx - itemL).toDouble() / max(1, itemR - itemL).toDouble())
                                            },
                                            onDrag = { mx, _ ->
                                                impl.updateDragFromFraction((mx - itemL).toDouble() / max(1, itemR - itemL).toDouble())
                                            },
                                            onRelease = { mx, _ ->
                                                impl.endDragFromFraction((mx - itemL).toDouble() / max(1, itemR - itemL).toDouble())
                                            }
                                        )
                                    )
                                }
                            }
                            is DesktopTextNativeImpl<*> -> {
                                val txt = impl.getText().trim()
                                if (txt.isNotEmpty()) {
                                    if (Win32NativeWindowHost.widgetHasClass(item, "badge") ||
                                        hasOverlayRole(item, DesktopStageOverlaySpec.ROLE_OUTLINED_FEATURE_BADGE)
                                    ) {
                                        drawBadgePill(hdc, theme, fontSmall, item, txt, itemL, itemTop + (itemH - 20) / 2, drawBackground = drawBg, drawForeground = drawFg, opacity = hudAlpha)
                                    } else if (drawFg) {
                                        SelectObject(hdc, fontSmallBold ?: fontSmall)
                                        Win32NativeWindowHost.drawTextInRect(
                                            hdc = hdc,
                                            text = txt,
                                            left = itemL + 2,
                                            top = itemTop + 2,
                                            right = itemR,
                                            bottom = itemBottom - 2,
                                            colorRef = 0xE2D9D0u
                                        )
                                    }
                                }
                            }
                            is DesktopSelectNativeImpl<*> -> {
                                val curVal = impl.getSelectedValue() ?: ""
                                val curLabel = impl.getOptions().firstOrNull { it.first == curVal }?.second ?: curVal
                                val fallback = if (fallbackIdx == 0) "Audio" else "Subtitles"
                                if (drawFg) SelectObject(hdc, fontSmall)
                                Win32NativeWindowHost.drawSelectControl(
                                    hdc = hdc,
                                    theme = theme,
                                    label = curLabel.ifBlank { fallback },
                                    left = itemL,
                                    top = itemTop,
                                    right = itemR,
                                    bottom = itemBottom,
                                    widget = item,
                                    hwndForHover = hwnd,
                                    drawBackground = drawBg,
                                    drawForeground = drawFg,
                                    opacity = hudAlpha
                                )
                                if (drawBg) {
                                    Win32NativeWindowHost.addHitRegion(
                                        Win32NativeWindowHost.HitRegion(
                                            left = itemL,
                                            top = itemTop,
                                            right = itemR,
                                            bottom = itemBottom,
                                            tooltip = nonRedundantTooltip(impl.getTooltipText(), curLabel.ifBlank { fallback }),
                                            widget = item,
                                            onClick = { mx, my -> Win32NativeWindowHost.openSelectPopupMenu(hwnd, impl, mx, my) }
                                        )
                                    )
                                }
                            }
                        }
                    }

                    var curLeftX = left + hudPadX
                    for ((idx, item) in leftItems.withIndex()) {
                        val w = measureCompactControlWidth(item)
                        if (w <= 0) continue
                        val itemR = (curLeftX + w).coerceAtMost(right - hudPadX)
                        if (curLeftX >= itemR) break
                        renderOverlayRowControl(item, curLeftX, itemR, idx)
                        curLeftX = itemR + gap
                    }

                    val visibleRightItems = rightItems.filter { measureCompactControlWidth(it) > 0 }
                    val wrapRightInUnifiedPill = visibleRightItems.isNotEmpty() &&
                        visibleRightItems.none { it.attributes["nativeImpl"] is DesktopStyledContainerNativeImpl<*> }
                    val rightGap = if (wrapRightInUnifiedPill) 2 else gap
                    val totalRightW = visibleRightItems.sumOf { measureCompactControlWidth(it) } +
                        rightGap * (visibleRightItems.size - 1).coerceAtLeast(0) +
                        (if (wrapRightInUnifiedPill) 8 else 0)
                    var curRightX = max(curLeftX + 6, right - hudPadX - totalRightW)
                    if (wrapRightInUnifiedPill) {
                        val pillL = curRightX
                        val pillR = (curRightX + totalRightW).coerceAtMost(right - hudPadX)
                        if (drawBg && pillR > pillL + 8) {
                            Win32NativeWindowHost.drawAntialiasedRoundedRectRgba(
                                hdc = hdc,
                                left = pillL,
                                top = ctrlY,
                                right = pillR,
                                bottom = ctrlY + ctrlH,
                                radiusPx = ctrlH / 2f,
                                fillColor = RgbaColor(15, 18, 24, 200),
                                borderColor = RgbaColor(255, 255, 255, 36),
                                borderWidthPx = 1f,
                                opacity = hudAlpha
                            )
                        }
                        curRightX += 4
                        for ((idx, item) in visibleRightItems.withIndex()) {
                            val w = measureCompactControlWidth(item)
                            val itemR = (curRightX + w).coerceAtMost(pillR - 4)
                            if (curRightX >= itemR) break
                            renderOverlayRowControl(item, curRightX, itemR, idx, ctrlY + 2, ctrlY + ctrlH - 2, inPillContainer = true)
                            curRightX = itemR + rightGap
                        }
                    } else {
                        for ((idx, item) in visibleRightItems.withIndex()) {
                            val w = measureCompactControlWidth(item)
                            val itemR = (curRightX + w).coerceAtMost(right - hudPadX)
                            if (curRightX >= itemR) break
                            renderOverlayRowControl(item, curRightX, itemR, idx)
                            curRightX = itemR + gap
                        }
                    }
                }

                // Draw active subtitle banner locked at the bottom over the bottom HUD gradient, below popovers
                drawLockedBottomSubtitleBanner(drawBackground = drawBg, drawForeground = drawFg)
            }
        }

        if (hudAlpha <= 0.01f) {
            val tVid0 = Win32NativeWindowHost.nowMicros()
            drawVideoStageFrameBackdropAndVideo()
            val tVid1 = Win32NativeWindowHost.nowMicros()
            drawLockedBottomSubtitleBanner()
            val tSub1 = Win32NativeWindowHost.nowMicros()
            Win32NativeWindowHost.currentPaintStageBreakdown += "hidHud(vid=${tVid1 - tVid0}us,sub=${tSub1 - tVid1}us)"
            Win32NativeWindowHost.addHitRegion(
                Win32NativeWindowHost.HitRegion(
                    left = left + 4,
                    top = top + 4,
                    right = right - 4,
                    bottom = stageBottom - 4,
                    tooltip = nonRedundantTooltip(playPauseBtnImpl?.getTooltipText()),
                    widget = overlayWidget ?: videoWidget,
                    onClick = { _, _ ->
                        Win32NativeWindowHost.lastVideoStageMouseMoveTickMs = GetTickCount64().toLong()
                        Win32NativeWindowHost.isPointerInVideoStage = true
                        Win32NativeWindowHost.isVideoStageHudAutoHidden = false
                        Win32NativeWindowHost.overlayHudTargetAlpha = 1f
                        playPauseBtnImpl?.dispatchClick()
                    }
                )
            )
        } else {
            val tVid0 = Win32NativeWindowHost.nowMicros()
            drawVideoStageFrameBackdropAndVideo()
            val tVid1 = Win32NativeWindowHost.nowMicros()
            renderStaticHudBandsPass()
            val tHud1 = Win32NativeWindowHost.nowMicros()
            Win32NativeWindowHost.currentPaintStageBreakdown += "singlePass(vid=${tVid1 - tVid0}us,hud=${tHud1 - tVid1}us)"

            // 4c. Anchored Stage Popover when visible
            if (settingsPanelWidget != null) {
                val isInlineSliderPopover = hasOverlayRole(settingsPanelWidget, DesktopStageOverlaySpec.ROLE_INLINE_SLIDER_POPOVER) ||
                    (visibleChildrenOf(settingsPanelWidget, titleBarWidgets).any { it.attributes["nativeImpl"] is DesktopRangeSliderNativeImpl<*> } &&
                        visibleChildrenOf(settingsPanelWidget, titleBarWidgets).none { it.attributes["nativeImpl"] is DesktopSelectNativeImpl<*> })
                val isCompactMenu = hasOverlayRole(settingsPanelWidget, DesktopStageOverlaySpec.ROLE_COMPACT_POPOVER_MENU) ||
                    settingsPanelWidget.attributes["data-popover-kind"] == "compact-menu"
                if (isInlineSliderPopover) {
                    val popW = 216.coerceAtMost((availW - 24).coerceAtLeast(140))
                    val popH = 44
                    val popRight = (right - 12).coerceAtLeast(left + popW)
                    val popLeft = (popRight - popW).coerceAtLeast(left + 12)
                    val popBottom = stageBottom - 54
                    val popTop = (popBottom - popH).coerceAtLeast(top + 12)
                    settingsPanelWidget.bounds = com.nexustools.genericui.geometry.Rect(
                        popLeft,
                        popTop,
                        popRight - popLeft,
                        popBottom - popTop
                    )
                    Win32NativeWindowHost.removeHitRegionsInRect(popLeft, popTop, popRight, popBottom)
                    Win32NativeWindowHost.addHitRegion(
                        Win32NativeWindowHost.HitRegion(
                            left = popLeft,
                            top = popTop,
                            right = popRight,
                            bottom = popBottom,
                            widget = settingsPanelWidget,
                            onClick = { _, _ -> }
                        )
                    )
                    val popVisuals = (settingsPanelWidget.attributes["nativeImpl"] as? DesktopStyledContainerNativeImpl<*>)?.getStyledVisuals()
                    Win32NativeWindowHost.drawAntialiasedRoundedRectRgba(
                        hdc = hdc,
                        left = popLeft,
                        top = popTop,
                        right = popRight,
                        bottom = popBottom,
                        radiusPx = popVisuals?.borderRadiusPx ?: 10f,
                        fillColor = popVisuals?.backgroundColor ?: RgbaColor(16, 14, 18, 240),
                        borderColor = popVisuals?.borderColor ?: RgbaColor(255, 255, 255, 46),
                        borderWidthPx = (popVisuals?.borderWidthPx ?: 1f).coerceAtLeast(1f)
                    )
                    val volChildren = visibleChildrenOf(settingsPanelWidget, titleBarWidgets)
                    val muteBtnWidget = volChildren.firstOrNull { it.attributes["nativeImpl"] is DesktopButtonNativeImpl<*> }
                    val volSliderWidget = volChildren.firstOrNull { it.attributes["nativeImpl"] is DesktopRangeSliderNativeImpl<*> }
                    val volLabelWidget = volChildren.firstOrNull { it.attributes["nativeImpl"] is DesktopTextNativeImpl<*> }

                    val muteBtnImpl = muteBtnWidget?.attributes?.get("nativeImpl") as? DesktopButtonNativeImpl<*>
                    if (muteBtnWidget != null && muteBtnImpl != null) {
                        val bL = popLeft + 8
                        val bT = popTop + 8
                        val bR = popLeft + 38
                        val bB = popBottom - 8
                        muteBtnWidget.bounds = com.nexustools.genericui.geometry.Rect(bL, bT, bR - bL, bB - bT)
                        SelectObject(hdc, fontSmall)
                        val drewMuteCustomImg = drawCustomButtonIconIfPresent(
                            hwnd = hwnd,
                            hdc = hdc,
                            theme = theme,
                            fontFallback = fontSmall,
                            buttonWidget = muteBtnWidget,
                            left = bL,
                            top = bT,
                            right = bR,
                            bottom = bB,
                            surfaceBgColorRef = 0x18120Fu,
                            transparentBackground = true
                        )
                        if (!drewMuteCustomImg) {
                            Win32NativeWindowHost.drawButtonControl(
                                hdc = hdc,
                                theme = theme,
                                label = muteBtnImpl.getText(),
                                icon = muteBtnImpl.getIcon(),
                                left = bL,
                                top = bT,
                                right = bR,
                                bottom = bB,
                                primary = muteBtnImpl.getType() == BootstrapType.PRIMARY,
                                widget = muteBtnWidget,
                                hwndForHover = hwnd
                            )
                        }
                        Win32NativeWindowHost.addHitRegion(
                            Win32NativeWindowHost.HitRegion(
                                left = bL,
                                top = bT,
                                right = bR,
                                bottom = bB,
                                tooltip = nonRedundantTooltip(muteBtnImpl.getTooltipText(), muteBtnImpl.getText()),
                                widget = muteBtnWidget,
                                onClick = { _, _ -> muteBtnImpl.dispatchClick() }
                            )
                        )
                    }

                    val volLabelImpl = volLabelWidget?.attributes?.get("nativeImpl") as? DesktopTextNativeImpl<*>
                    if (volLabelWidget != null && volLabelImpl != null) {
                        val lL = popRight - 46
                        val lT = popTop + 8
                        val lR = popRight - 8
                        val lB = popBottom - 8
                        volLabelWidget.bounds = com.nexustools.genericui.geometry.Rect(lL, lT, (lR - lL).coerceAtLeast(1), lB - lT)
                        val txt = volLabelImpl.getText().trim()
                        if (txt.isNotEmpty()) {
                            SelectObject(hdc, fontSmallBold ?: fontSmall)
                            Win32NativeWindowHost.drawTextInRect(
                                hdc = hdc,
                                text = txt,
                                left = lL,
                                top = lT,
                                right = lR,
                                bottom = lB,
                                colorRef = 0xE2D9D0u,
                                center = true
                            )
                        }
                    }

                    val volSliderImpl = volSliderWidget?.attributes?.get("nativeImpl") as? DesktopRangeSliderNativeImpl<*>
                    if (volSliderWidget != null && volSliderImpl != null) {
                        val sL = popLeft + 44
                        val sR = (popRight - 50).coerceAtLeast(sL + 24)
                        val sT = popTop + 12
                        val sB = popBottom - 12
                        volSliderWidget.bounds = com.nexustools.genericui.geometry.Rect(sL, sT, sR - sL, sB - sT)
                        Win32NativeWindowHost.drawSliderControl(hdc, theme, volSliderImpl, sL, sT, sR, sB)
                        Win32NativeWindowHost.addHitRegion(
                            Win32NativeWindowHost.HitRegion(
                                left = sL,
                                top = sT,
                                right = sR,
                                bottom = sB,
                                tooltip = nonRedundantTooltip(volSliderImpl.getTooltipText()),
                                widget = volSliderWidget,
                                onClick = { mx, _ ->
                                    volSliderImpl.beginDragFromFraction((mx - sL).toDouble() / max(1, sR - sL).toDouble())
                                },
                                onDrag = { mx, _ ->
                                    volSliderImpl.updateDragFromFraction((mx - sL).toDouble() / max(1, sR - sL).toDouble())
                                },
                                onRelease = { mx, _ ->
                                    volSliderImpl.endDragFromFraction((mx - sL).toDouble() / max(1, sR - sL).toDouble())
                                }
                            )
                        )
                    }
                } else {
                    var popoverScrollListWidget: DesktopNativeWidget? = null
                    fun collectSettingsPanelRows(container: DesktopNativeWidget): List<DesktopNativeWidget> {
                        val out = ArrayList<DesktopNativeWidget>()
                        for (child in visibleChildrenOf(container, titleBarWidgets)) {
                            if (hasOverlayRole(child, DesktopStageOverlaySpec.ROLE_POPOVER_SCROLL_LIST) ||
                                child.attributes["nativeImpl"] is DesktopScrollViewNativeImpl<*>
                            ) {
                                popoverScrollListWidget = child
                                out.addAll(visibleChildrenOf(child, titleBarWidgets))
                            } else {
                                out.add(child)
                            }
                        }
                        return out
                    }
                    val panelRows = collectSettingsPanelRows(settingsPanelWidget)
                    fun measurePopoverChildIntrinsicW(rowNode: DesktopNativeWidget): Int {
                        if (isSpacerWidget(rowNode)) return 0
                        return when (val rImpl = rowNode.attributes["nativeImpl"]) {
                            is DesktopTextNativeImpl<*> -> measureSingleLineTextWidth(rImpl.getText().trim(), 7) + 18
                            is DesktopButtonNativeImpl<*> -> {
                                if (isCompactMenu || hasOverlayRole(rowNode, DesktopStageOverlaySpec.ROLE_POPOVER_OPTION)) {
                                    measureSingleLineTextWidth(rImpl.getText().trim(), 7) + 44
                                } else {
                                    measureButtonWidth(rImpl) + 16
                                }
                            }
                            is DesktopSelectNativeImpl<*> -> {
                                val curVal = rImpl.getSelectedValue() ?: ""
                                val maxOptW = rImpl.getOptions().maxOfOrNull { measureSingleLineTextWidth(it.second, 7) }
                                    ?: measureSingleLineTextWidth(curVal, 7)
                                maxOptW + 44
                            }
                            else -> {
                                val kids = visibleChildrenOf(rowNode, titleBarWidgets)
                                val lblW = kids.firstOrNull { it.attributes["nativeImpl"] is DesktopTextNativeImpl<*> }
                                    ?.let { (it.attributes["nativeImpl"] as? DesktopTextNativeImpl<*>)?.getText()?.trim().orEmpty() }
                                    ?.let { if (it.isNotEmpty()) measureSingleLineTextWidth(it, 7) + 12 else 0 } ?: 0
                                val selImpl = kids.firstOrNull { it.attributes["nativeImpl"] is DesktopSelectNativeImpl<*> }
                                    ?.attributes?.get("nativeImpl") as? DesktopSelectNativeImpl<*>
                                val btnImpl = kids.firstOrNull { it.attributes["nativeImpl"] is DesktopButtonNativeImpl<*> }
                                    ?.attributes?.get("nativeImpl") as? DesktopButtonNativeImpl<*>
                                when {
                                    selImpl != null -> {
                                        val curVal = selImpl.getSelectedValue() ?: ""
                                        val optW = selImpl.getOptions().maxOfOrNull { measureSingleLineTextWidth(it.second, 7) }
                                            ?: measureSingleLineTextWidth(curVal, 7)
                                        lblW + optW + 48
                                    }
                                    btnImpl != null && kids.size <= 2 && lblW > 0 -> lblW + measureButtonWidth(btnImpl) + 16
                                    kids.isNotEmpty() -> kids.maxOfOrNull { measurePopoverChildIntrinsicW(it) } ?: 120
                                    else -> 120
                                }
                            }
                        }
                    }
                    fun estimatePopoverChildH(rowNode: DesktopNativeWidget): Int {
                        if (isSpacerWidget(rowNode)) return getFixedSpacerHeight(rowNode).coerceIn(2, if (isCompactMenu) 6 else 12)
                        val btnRowH = if (isCompactMenu) 26 else 30
                        return when (val rImpl = rowNode.attributes["nativeImpl"]) {
                            is DesktopTextNativeImpl<*> -> if (rImpl.getText().trim().isNotEmpty()) (if (isCompactMenu) 18 else 22) else 0
                            is DesktopButtonNativeImpl<*> -> btnRowH
                            is DesktopSelectNativeImpl<*> -> 30
                            else -> {
                                val kids = visibleChildrenOf(rowNode, titleBarWidgets)
                                val hasSelectOrLabelPair = kids.any { it.attributes["nativeImpl"] is DesktopSelectNativeImpl<*> } ||
                                    (kids.size <= 2 && kids.any { it.attributes["nativeImpl"] is DesktopTextNativeImpl<*> } &&
                                        kids.any { it.attributes["nativeImpl"] is DesktopButtonNativeImpl<*> })
                                if (hasSelectOrLabelPair) {
                                    30
                                } else if (kids.isNotEmpty()) {
                                    kids.sumOf { estimatePopoverChildH(it) } + 2
                                } else {
                                    btnRowH
                                }
                            }
                        }
                    }
                    val widestChildW = panelRows.maxOfOrNull { measurePopoverChildIntrinsicW(it) } ?: 120
                    val popW = if (isCompactMenu) {
                        (widestChildW + 18).coerceIn(148, 240)
                    } else {
                        (widestChildW + 24).coerceIn(180, 250)
                    }
                    val totalContentH = panelRows.sumOf { estimatePopoverChildH(it) } + (if (isCompactMenu) 14 else 16)
                    val maxPopH = (stageH - 76).coerceAtLeast(120)
                    val popH = totalContentH.coerceIn(44, maxPopH)
                    val popRight = if (isCompactMenu) right - 44 else right - 12
                    val popLeft = (popRight - popW).coerceAtLeast(left + 12)
                    val popBottom = stageBottom - 54
                    val popTop = (popBottom - popH).coerceAtLeast(top + 12)
                    settingsPanelWidget.bounds = com.nexustools.genericui.geometry.Rect(
                        popLeft,
                        popTop,
                        popRight - popLeft,
                        popBottom - popTop
                    )
                    popoverScrollListWidget?.bounds = com.nexustools.genericui.geometry.Rect(
                        popLeft + 4,
                        (popTop + 22).coerceAtMost(popBottom - 4),
                        (popRight - popLeft - 8).coerceAtLeast(1),
                        (popBottom - popTop - 26).coerceAtLeast(1)
                    )

                    // Clear any underlying control/seekbar hit regions inside the popover bounds and add a base absorber
                    Win32NativeWindowHost.removeHitRegionsInRect(popLeft, popTop, popRight, popBottom)
                    Win32NativeWindowHost.addHitRegion(
                        Win32NativeWindowHost.HitRegion(
                            left = popLeft,
                            top = popTop,
                            right = popRight,
                            bottom = popBottom,
                            widget = settingsPanelWidget,
                            onClick = { _, _ -> }
                        )
                    )

                    val popVisuals = (settingsPanelWidget.attributes["nativeImpl"] as? DesktopStyledContainerNativeImpl<*>)?.getStyledVisuals()
                    Win32NativeWindowHost.drawAntialiasedRoundedRectRgba(
                        hdc = hdc,
                        left = popLeft,
                        top = popTop,
                        right = popRight,
                        bottom = popBottom,
                        radiusPx = popVisuals?.borderRadiusPx ?: 10f,
                        fillColor = popVisuals?.backgroundColor ?: RgbaColor(16, 14, 18, 240),
                        borderColor = popVisuals?.borderColor ?: RgbaColor(255, 255, 255, 46),
                        borderWidthPx = (popVisuals?.borderWidthPx ?: 1f).coerceAtLeast(1f)
                    )

                    fun drawPopoverOptionButton(
                        btnWidget: DesktopNativeWidget,
                        btnImpl: DesktopButtonNativeImpl<*>,
                        btnL: Int,
                        hitY: Int,
                        btnR: Int,
                        btnH: Int
                    ) {
                        btnWidget.bounds = com.nexustools.genericui.geometry.Rect(btnL, hitY, (btnR - btnL).coerceAtLeast(1), btnH)
                        val isActiveOpt = Win32NativeWindowHost.widgetHasClass(btnWidget, "active") || btnImpl.getType() == BootstrapType.PRIMARY
                        val isCompactOpt = isCompactMenu ||
                            hasOverlayRole(btnWidget, DesktopStageOverlaySpec.ROLE_POPOVER_OPTION)
                        if (isCompactOpt) {
                            val isOptHovered = (Win32NativeWindowHost.hoveredWidget === btnWidget) ||
                                Win32NativeWindowHost.isRectHovered(hwnd, btnL, hitY, btnR, hitY + btnH)
                            if (isActiveOpt) {
                                Win32NativeWindowHost.drawAntialiasedRoundedRectRgba(
                                    hdc = hdc,
                                    left = btnL,
                                    top = hitY,
                                    right = btnR,
                                    bottom = hitY + btnH,
                                    radiusPx = 6f,
                                    fillColor = RgbaColor(79, 140, 255, if (isOptHovered) 96 else 72),
                                    borderColor = RgbaColor(79, 140, 255, 220),
                                    borderWidthPx = 1f
                                )
                            } else if (isOptHovered) {
                                Win32NativeWindowHost.drawAntialiasedRoundedRectRgba(
                                    hdc = hdc,
                                    left = btnL,
                                    top = hitY,
                                    right = btnR,
                                    bottom = hitY + btnH,
                                    radiusPx = 6f,
                                    fillColor = RgbaColor(255, 255, 255, 28),
                                    borderColor = RgbaColor(255, 255, 255, 52),
                                    borderWidthPx = 1f
                                )
                            }
                            val rawText = btnImpl.getText().trim()
                            val displayText = if (isActiveOpt && !rawText.startsWith("✓")) "✓  $rawText" else rawText
                            SelectObject(hdc, if (isActiveOpt) (fontSmallBold ?: fontSmall) else fontSmall)
                            Win32NativeWindowHost.drawTextInRect(
                                hdc = hdc,
                                text = displayText,
                                left = btnL + 8,
                                top = hitY + 2,
                                right = btnR - 8,
                                bottom = hitY + btnH - 2,
                                colorRef = if (isActiveOpt) 0xFFFFFFu else 0xE2D9D0u
                            )
                        } else {
                            SelectObject(hdc, fontSmall)
                            Win32NativeWindowHost.drawButtonControl(
                                hdc = hdc,
                                theme = theme,
                                label = btnImpl.getText(),
                                icon = btnImpl.getIcon(),
                                left = btnL,
                                top = hitY,
                                right = btnR,
                                bottom = hitY + btnH,
                                primary = isActiveOpt,
                                widget = btnWidget,
                                hwndForHover = hwnd
                            )
                        }
                        Win32NativeWindowHost.addHitRegion(
                            Win32NativeWindowHost.HitRegion(
                                left = btnL,
                                top = hitY,
                                right = btnR,
                                bottom = hitY + btnH,
                                tooltip = nonRedundantTooltip(btnImpl.getTooltipText(), btnImpl.getText()),
                                widget = btnWidget,
                                onClick = { _, _ -> btnImpl.dispatchClick() }
                            )
                        )
                    }

                    var py = popTop + (if (isCompactMenu) 6 else 8)
                    val availInnerH = (popBottom - popTop - 16).coerceAtLeast(32)
                    if (totalContentH > popBottom - popTop) {
                        renderScrollableVerticalStack(
                            hwnd = hwnd,
                            hdc = hdc,
                            theme = theme,
                            fontSmall = fontSmall,
                            fontSmallBold = fontSmallBold ?: fontSmall,
                            fontNormal = fontNormal,
                            fontBold = fontBold,
                            fontTitle = fontTitle,
                            fontMetricBig = fontTitle,
                            container = settingsPanelWidget,
                            children = panelRows,
                            titleBarWidgets = titleBarWidgets,
                            left = popLeft + 4,
                            top = popTop + 6,
                            right = popRight - 4,
                            bottom = popBottom - 6,
                            padX = 4,
                            padY = 2
                        )
                    } else {
                        for (rowNode in panelRows) {
                            if (py + 6 > popBottom - 4) break
                            if (isSpacerWidget(rowNode)) {
                                py += getFixedSpacerHeight(rowNode).coerceIn(2, if (isCompactMenu) 6 else 12)
                                continue
                            }
                            val rowImpl = rowNode.attributes["nativeImpl"]
                            if (rowImpl is DesktopTextNativeImpl<*>) {
                                val headerTxt = rowImpl.getText().trim()
                                if (headerTxt.isNotEmpty()) {
                                    val hdrH = if (isCompactMenu) 16 else 20
                                    val hPad = if (isCompactMenu) 8 else 12
                                    rowNode.bounds = com.nexustools.genericui.geometry.Rect(
                                        popLeft + hPad,
                                        py,
                                        (popRight - popLeft - hPad * 2).coerceAtLeast(1),
                                        hdrH
                                    )
                                    SelectObject(hdc, fontSmallBold ?: fontSmall)
                                    Win32NativeWindowHost.drawTextInRect(
                                        hdc = hdc,
                                        text = headerTxt,
                                        left = popLeft + hPad,
                                        top = py,
                                        right = popRight - hPad,
                                        bottom = py + hdrH,
                                        colorRef = 0xB8A99Eu
                                    )
                                    py += if (isCompactMenu) 18 else 22
                                }
                                continue
                            }
                            if (rowImpl is DesktopSelectNativeImpl<*>) {
                                val curVal = rowImpl.getSelectedValue() ?: ""
                                val curLabel = rowImpl.getOptions().firstOrNull { it.first == curVal }?.second ?: curVal
                                val selL = popLeft + 8
                                val selR = popRight - 8
                                val hitY = py
                                rowNode.bounds = com.nexustools.genericui.geometry.Rect(selL, hitY, (selR - selL).coerceAtLeast(1), 26)
                                SelectObject(hdc, fontSmall)
                                Win32NativeWindowHost.drawSelectControl(
                                    hdc = hdc,
                                    theme = theme,
                                    label = curLabel.ifBlank { "Select" },
                                    left = selL,
                                    top = hitY,
                                    right = selR,
                                    bottom = hitY + 26,
                                    widget = rowNode,
                                    hwndForHover = hwnd
                                )
                                Win32NativeWindowHost.addHitRegion(
                                    Win32NativeWindowHost.HitRegion(
                                        left = selL,
                                        top = hitY,
                                        right = selR,
                                        bottom = hitY + 26,
                                        tooltip = nonRedundantTooltip(rowImpl.getTooltipText(), curLabel),
                                        widget = rowNode,
                                        onClick = { mx, my -> Win32NativeWindowHost.openSelectPopupMenu(hwnd, rowImpl, mx, my) }
                                    )
                                )
                                py += 30
                                continue
                            }
                            if (rowImpl is DesktopButtonNativeImpl<*>) {
                                val btnH = if (isCompactMenu) 23 else 26
                                val padH = if (isCompactMenu) 6 else 8
                                drawPopoverOptionButton(rowNode, rowImpl, popLeft + padH, py, popRight - padH, btnH)
                                py += if (isCompactMenu) 26 else 30
                                continue
                            }

                            val rowKids = visibleChildrenOf(rowNode, titleBarWidgets)
                            val labelWidget = rowKids.firstOrNull { it.attributes["nativeImpl"] is DesktopTextNativeImpl<*> }
                            val selectWidget = rowKids.firstOrNull { it.attributes["nativeImpl"] is DesktopSelectNativeImpl<*> }
                            val btnWidget = rowKids.firstOrNull { it.attributes["nativeImpl"] is DesktopButtonNativeImpl<*> }
                            val isScrollableSublist = selectWidget == null && labelWidget == null &&
                                (rowImpl is DesktopScrollViewNativeImpl<*> || rowKids.count { it.attributes["nativeImpl"] is DesktopButtonNativeImpl<*> } > 1)
                            if (isScrollableSublist) {
                                val subBottom = (popBottom - 6).coerceAtLeast(py + 24)
                                rowNode.bounds = com.nexustools.genericui.geometry.Rect(
                                    popLeft + 4,
                                    py,
                                    (popRight - popLeft - 8).coerceAtLeast(1),
                                    (subBottom - py).coerceAtLeast(1)
                                )
                                val btnRowH = if (isCompactMenu) 26 else 30
                                val btnDrawH = if (isCompactMenu) 23 else 26
                                val padH = if (isCompactMenu) 6 else 8
                                val fitsInline = rowKids.all { it.attributes["nativeImpl"] is DesktopButtonNativeImpl<*> || isSpacerWidget(it) } &&
                                    (rowKids.count { it.attributes["nativeImpl"] is DesktopButtonNativeImpl<*> } * btnRowH) <= (subBottom - py + 4)
                                if (fitsInline) {
                                    for (subKid in rowKids) {
                                        if (isSpacerWidget(subKid)) {
                                            py += getFixedSpacerHeight(subKid).coerceIn(2, 4)
                                            continue
                                        }
                                        val subBtnImpl = subKid.attributes["nativeImpl"] as? DesktopButtonNativeImpl<*> ?: continue
                                        if (py + btnDrawH > popBottom - 4) break
                                        drawPopoverOptionButton(subKid, subBtnImpl, popLeft + padH, py, popRight - padH, btnDrawH)
                                        py += btnRowH
                                    }
                                } else {
                                    renderScrollableVerticalStack(
                                        hwnd = hwnd,
                                        hdc = hdc,
                                        theme = theme,
                                        fontSmall = fontSmall,
                                        fontSmallBold = fontSmallBold ?: fontSmall,
                                        fontNormal = fontNormal,
                                        fontBold = fontBold,
                                        fontTitle = fontTitle,
                                        fontMetricBig = fontTitle,
                                        container = rowNode,
                                        children = rowKids,
                                        titleBarWidgets = titleBarWidgets,
                                        left = popLeft + 4,
                                        top = py,
                                        right = popRight - 4,
                                        bottom = subBottom,
                                        padX = 4,
                                        padY = 2
                                    )
                                    py = subBottom + 4
                                }
                            } else if (selectWidget != null) {
                                rowNode.bounds = com.nexustools.genericui.geometry.Rect(
                                    popLeft + 4,
                                    py,
                                    (popRight - popLeft - 8).coerceAtLeast(1),
                                    28
                                )
                                val lblTxt = (labelWidget?.attributes?.get("nativeImpl") as? DesktopTextNativeImpl<*>)?.getText()?.trim().orEmpty()
                                val selImpl = selectWidget.attributes["nativeImpl"] as DesktopSelectNativeImpl<*>
                                val curVal = selImpl.getSelectedValue() ?: ""
                                val curLabel = selImpl.getOptions().firstOrNull { it.first == curVal }?.second ?: curVal
                                val hitY = py
                                val selL = if (lblTxt.isNotEmpty()) {
                                    val lblRight = (popLeft + 10 + measureSingleLineTextWidth(lblTxt, 7) + 8).coerceAtMost(popLeft + 88)
                                    labelWidget?.bounds = com.nexustools.genericui.geometry.Rect(
                                        popLeft + 10,
                                        py + 2,
                                        (lblRight - (popLeft + 10)).coerceAtLeast(1),
                                        24
                                    )
                                    SelectObject(hdc, fontSmall)
                                    Win32NativeWindowHost.drawTextInRect(
                                        hdc = hdc,
                                        text = lblTxt,
                                        left = popLeft + 10,
                                        top = py + 2,
                                        right = lblRight,
                                        bottom = py + 26,
                                        colorRef = 0xE2D9D0u
                                    )
                                    lblRight + 4
                                } else {
                                    popLeft + 8
                                }
                                val selR = popRight - 8
                                selectWidget.bounds = com.nexustools.genericui.geometry.Rect(
                                    selL,
                                    hitY,
                                    (selR - selL).coerceAtLeast(1),
                                    26
                                )
                                Win32NativeWindowHost.drawSelectControl(
                                    hdc = hdc,
                                    theme = theme,
                                    label = curLabel.ifBlank { lblTxt.ifBlank { "Select" } },
                                    left = selL,
                                    top = hitY,
                                    right = selR,
                                    bottom = hitY + 26,
                                    widget = selectWidget,
                                    hwndForHover = hwnd
                                )
                                Win32NativeWindowHost.addHitRegion(
                                    Win32NativeWindowHost.HitRegion(
                                        left = selL,
                                        top = hitY,
                                        right = selR,
                                        bottom = hitY + 26,
                                        tooltip = nonRedundantTooltip(selImpl.getTooltipText(), lblTxt),
                                        widget = selectWidget,
                                        onClick = { mx, my -> Win32NativeWindowHost.openSelectPopupMenu(hwnd, selImpl, mx, my) }
                                    )
                                )
                                py += 30
                            } else if (btnWidget != null) {
                                rowNode.bounds = com.nexustools.genericui.geometry.Rect(
                                    popLeft + 4,
                                    py,
                                    (popRight - popLeft - 8).coerceAtLeast(1),
                                    28
                                )
                                val btnImpl = btnWidget.attributes["nativeImpl"] as DesktopButtonNativeImpl<*>
                                drawPopoverOptionButton(btnWidget, btnImpl, popLeft + 8, py, popRight - 8, 26)
                                py += 30
                            } else {
                                py += availInnerH.coerceAtMost(30)
                            }
                        }
                    }
                }
            }
        }

        // 5. Render any additional visible layered overlay children inside the stage
        for (extra in otherChildren) {
            if (!extra.visible || extra.disposed) continue
            if (extra === subtitleBannerWidget ||
                hasOverlayRole(extra, DesktopStageOverlaySpec.ROLE_CAPTION_BANNER)
            ) {
                continue
            }
            renderLayeredOverlayChild(
                hwnd = hwnd,
                hdc = hdc,
                theme = theme,
                fontSmall = fontSmall,
                fontSmallBold = fontSmallBold,
                fontNormal = fontNormal,
                fontBold = fontBold,
                fontTitle = fontTitle,
                fontMetricBig = null,
                overlayLayer = extra,
                titleBarWidgets = titleBarWidgets,
                left = left,
                top = top,
                right = right,
                bottom = stageBottom
            )
        }

        if (captureStaticStageSurface && !isResizeFrame) {
            var reuseBuffer: ByteArray? = null
            if (!cachedVideoStageSurfaces.containsKey(stageCacheKey!!) && cachedVideoStageSurfaces.size >= MAX_CACHED_VIDEO_STAGE_SURFACES) {
                val oldestKey = cachedVideoStageSurfaces.keys.firstOrNull()
                if (oldestKey != null) {
                    reuseBuffer = cachedVideoStageSurfaces.remove(oldestKey)?.bgra
                }
            }
            val captured = captureCachedRegion(
                left = left,
                top = top,
                right = right,
                bottom = stageBottom,
                hitStartIdx = stageHitStartIdx,
                timePillRelBounds = capturedTimePillRelBounds,
                reuseBuffer = reuseBuffer
            )
            if (captured != null) {
                cachedVideoStageSurfaces[stageCacheKey] = captured
                lastPaintedVideoStageCacheKey = stageCacheKey
                lastPaintedVideoStageHwnd = hwnd
                lastPaintedTimePillTxt = timePillTxt
            } else {
                lastPaintedVideoStageCacheKey = null
                lastPaintedTimePillTxt = ""
            }
            drawDynamicVideoStageHudLayer(capturedTimePillRelBounds, drawTimePill = true)
            Win32NativeWindowHost.lastVideoStageFastBlitRect[0] = left
            Win32NativeWindowHost.lastVideoStageFastBlitRect[1] = top
            Win32NativeWindowHost.lastVideoStageFastBlitRect[2] = right
            Win32NativeWindowHost.lastVideoStageFastBlitRect[3] = stageBottom
        } else if (stageCacheKey != null && !isResizeFrame && hasActiveVideoFrames) {
            lastPaintedVideoStageCacheKey = stageCacheKey
            lastPaintedVideoStageHwnd = hwnd
            lastPaintedTimePillTxt = timePillTxt
            Win32NativeWindowHost.lastVideoStageFastBlitRect[0] = left
            Win32NativeWindowHost.lastVideoStageFastBlitRect[1] = top
            Win32NativeWindowHost.lastVideoStageFastBlitRect[2] = right
            Win32NativeWindowHost.lastVideoStageFastBlitRect[3] = stageBottom
        } else {
            lastPaintedVideoStageCacheKey = null
            lastPaintedTimePillTxt = ""
            Win32NativeWindowHost.lastVideoStageFastBlitRect[0] = left
            Win32NativeWindowHost.lastVideoStageFastBlitRect[1] = top
            Win32NativeWindowHost.lastVideoStageFastBlitRect[2] = right
            Win32NativeWindowHost.lastVideoStageFastBlitRect[3] = stageBottom
        }
        hadVisibleVideoStagePopupLastFrame = hasVisiblePopup

        return stageBottom
    }

    private fun isCompositeListRow(
        rowWidget: DesktopNativeWidget,
        children: List<DesktopNativeWidget>,
        titleBarWidgets: Set<DesktopNativeWidget>
    ): Boolean {
        if (rowWidget.attributes.containsKey("rowHeader") ||
            rowWidget.attributes.containsKey("rowIndented") ||
            rowWidget.attributes.containsKey("rowDisabled") ||
            rowWidget.attributes.containsKey("rowEmojiIcon") ||
            rowWidget.attributes.containsKey("rowIconUrl") ||
            Win32NativeWindowHost.widgetHasClass(rowWidget, "native-row")
        ) {
            return children.isNotEmpty()
        }
        if (children.size != 3) return false
        if (!isSpacerWidget(children[1])) return false
        val leftChildren = visibleChildrenOf(children[0], titleBarWidgets)
        val rightChildren = visibleChildrenOf(children[2], titleBarWidgets)
        if (leftChildren.isEmpty() && rightChildren.isEmpty()) return false
        val leftHasNestedStack = leftChildren.any { visibleChildrenOf(it, titleBarWidgets).isNotEmpty() }
        return leftHasNestedStack
    }

    private fun renderCompositeListRow(
        hwnd: HWND,
        hdc: HDC?,
        theme: Win32NativeWindowHost.SystemThemePalette,
        fontSmall: HFONT?,
        fontSmallBold: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        fontTitle: HFONT?,
        rowWidget: DesktopNativeWidget,
        children: List<DesktopNativeWidget>,
        titleBarWidgets: Set<DesktopNativeWidget>,
        left: Int,
        top: Int,
        right: Int
    ): Int {
        val leftSection = children[0]
        val rightSection = when {
            children.size >= 3 -> children.last()
            children.size == 2 && !isSpacerWidget(children[1]) -> children[1]
            else -> null
        }
        val leftItems = visibleChildrenOf(leftSection, titleBarWidgets)
        val rightItems = if (rightSection != null) visibleChildrenOf(rightSection, titleBarWidgets) else emptyList()

        val isHeader = rowWidget.attributes["rowHeader"] == "true" ||
            rowWidget.attributes["rowHeader"] == true ||
            leftSection.attributes["rowHeader"] == "true"
        val isIndented = rowWidget.attributes["rowIndented"] == "true" ||
            rowWidget.attributes["rowIndented"] == true ||
            leftSection.attributes["rowIndented"] == "true"
        val isDisabled = rowWidget.attributes["rowDisabled"] == "true" ||
            rowWidget.attributes["rowDisabled"] == true ||
            leftSection.attributes["rowDisabled"] == "true"
        val attrEmojiIcon = (rowWidget.attributes["rowEmojiIcon"] as? String)?.trim()
            ?: (leftSection.attributes["rowEmojiIcon"] as? String)?.trim()
        val attrIconUrl = (rowWidget.attributes["rowIconUrl"] as? String)?.trim()
            ?: (leftSection.attributes["rowIconUrl"] as? String)?.trim()

        val rowH = 56
        val bottom = top + rowH
        val visTop = max(top, currentClipTop)
        val visBottom = min(bottom, currentClipBottom)
        val visH = visBottom - visTop
        if (visH <= 0) return bottom
        val rowBaseImpl = rowWidget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
        val isClickableRow = rowBaseImpl != null && rowBaseImpl.hasClickListener() && !isDisabled
        val isRowHovered = !suppressRowHoverForBaseViewportCapture && isClickableRow && Win32NativeWindowHost.hoveredWidget === rowWidget
        val hoveredRightBtnPtr = if (suppressRowHoverForBaseViewportCapture) 0L else (rightItems.firstOrNull { Win32NativeWindowHost.hoveredWidget === it }?.nativePointer ?: 0L)
        val pressedRightBtnPtr = rightItems.firstOrNull { Win32NativeWindowHost.pressedWidget === it }?.nativePointer ?: 0L
        val hasActiveBgra = leftItems.any { it.attributes.containsKey("activeBgraEntry") } || rowWidget.attributes.containsKey("activeBgraEntry")
        val canCacheRowSlice = canUseSurfaceRegionCache(left, visTop, right, visBottom, checkScrollClip = false)
        val fullRowCacheKey = if (canCacheRowSlice) {
            RowSurfaceCacheKey(
                widgetPtr = rowWidget.nativePointer,
                mutVer = rowWidget.subtreeMutationVersion,
                width = right - left,
                isRowHovered = isRowHovered,
                hoveredRightBtnPtr = hoveredRightBtnPtr,
                pressedRightBtnPtr = pressedRightBtnPtr,
                isDark = theme.isDark,
                hasActiveBgra = hasActiveBgra,
                clipOffsetTop = 0,
                visibleHeight = rowH
            )
        } else {
            null
        }
        val rowCacheKey = if (canCacheRowSlice) {
            if (visTop == top && visH == rowH) {
                fullRowCacheKey
            } else {
                RowSurfaceCacheKey(
                    widgetPtr = rowWidget.nativePointer,
                    mutVer = rowWidget.subtreeMutationVersion,
                    width = right - left,
                    isRowHovered = isRowHovered,
                    hoveredRightBtnPtr = hoveredRightBtnPtr,
                    pressedRightBtnPtr = pressedRightBtnPtr,
                    isDark = theme.isDark,
                    hasActiveBgra = hasActiveBgra,
                    clipOffsetTop = visTop - top,
                    visibleHeight = visH
                )
            }
        } else {
            null
        }
        if (fullRowCacheKey != null) {
            val cachedFullRow = cachedCompositeRowRegions[fullRowCacheKey]
                ?: cachedCompositeRowRegions[fullRowCacheKey.copy(hasActiveBgra = !hasActiveBgra)]
            if (cachedFullRow != null && tryBlitCachedRegion(
                    region = cachedFullRow,
                    left = left,
                    top = top,
                    right = right,
                    bottom = bottom,
                    rowStart = visTop - top,
                    rowEnd = visBottom - top
                )
            ) {
                return bottom
            }
        }
        if (rowCacheKey != null && rowCacheKey != fullRowCacheKey) {
            val cachedRow = cachedCompositeRowRegions[rowCacheKey]
                ?: cachedCompositeRowRegions[rowCacheKey.copy(hasActiveBgra = !hasActiveBgra)]
            if (cachedRow != null && tryBlitCachedRegion(cachedRow, left, visTop, right, visBottom)) {
                return bottom
            }
        }
        // 1. Measure rightSection controls right-aligned inside the card
        val padRight = 14
        val gap = 6
        val rightWidths = rightItems.map { item ->
            when (val impl = item.attributes["nativeImpl"]) {
                is DesktopButtonNativeImpl<*> -> measureButtonWidth(impl).coerceIn(48, 120)
                is DesktopTextNativeImpl<*> -> {
                    val txt = impl.getText().trim()
                    if (txt.isEmpty()) 0 else measureSingleLineTextWidth(txt, 7) + 16
                }
                else -> 64
            }
        }
        val totalRightW = rightWidths.sum() + gap * (rightItems.size - 1).coerceAtLeast(0)
        var rx = (right - padRight - totalRightW).coerceAtLeast(left + 140)
        val rightStart = rx

        val bufPixels = Win32NativeWindowHost.activeBackBufferPixels
        val bufW = Win32NativeWindowHost.activeBackBufferWidth
        if (visTop == top && visH in 1..rowH && canCacheRowSlice && bufPixels != null && bufW > 0) {
            val prevFullPair = cachedFullRowByWidgetPtr[fullRowPtrKey(rowWidget.nativePointer, isRowHovered)]
                ?: if (isRowHovered) null else cachedFullRowByWidgetPtr[rowWidget.nativePointer]
            if (prevFullPair != null) {
                val (prevKey, prevRegion) = prevFullPair
                val oldW = prevRegion.width
                val newW = right - left
                if (oldW != newW &&
                    prevRegion.height == rowH &&
                    prevRegion.bgra.size >= oldW * rowH * 4 &&
                    prevKey.mutVer == rowWidget.subtreeMutationVersion &&
                    prevKey.isRowHovered == isRowHovered &&
                    prevKey.hoveredRightBtnPtr == hoveredRightBtnPtr &&
                    prevKey.pressedRightBtnPtr == pressedRightBtnPtr &&
                    prevKey.isDark == theme.isDark
                ) {
                    val rightSliceW = (totalRightW + padRight + 10).coerceIn(16, min(oldW, newW) - 64)
                    val leftSliceW = min(oldW - rightSliceW, newW - rightSliceW)
                    if (leftSliceW >= 96) {
                        Win32NativeWindowHost.flushGdiIfOverlapping(left, top, right, visBottom)
                        Win32NativeWindowHost.markFrameBufferDirty(left, top, right, visBottom)
                        val leftBytes = (leftSliceW * 4).toULong()
                        val rightBytes = (rightSliceW * 4).toULong()
                        val rightDstCol = newW - rightSliceW
                        val rightSrcCol = oldW - rightSliceW
                        prevRegion.bgra.usePinned { pinned ->
                            for (r in 0 until visH) {
                                val dstRowBase = ((top + r) * bufW + left) * 4
                                val srcRowBase = (r * oldW) * 4
                                platform.posix.memcpy(
                                    kotlinx.cinterop.interpretCPointer<kotlinx.cinterop.ByteVar>(bufPixels.rawValue + dstRowBase.toLong()),
                                    pinned.addressOf(srcRowBase),
                                    leftBytes
                                )
                                if (rightDstCol > leftSliceW) {
                                    val fillWord = kotlinx.cinterop.interpretCPointer<kotlinx.cinterop.UIntVar>(
                                        pinned.addressOf(srcRowBase + (leftSliceW - 1) * 4).rawValue
                                    )!![0]
                                    val dstWords = kotlinx.cinterop.interpretCPointer<kotlinx.cinterop.UIntVar>(
                                        bufPixels.rawValue + (dstRowBase + leftSliceW * 4).toLong()
                                    )!!
                                    for (c in 0 until (rightDstCol - leftSliceW)) {
                                        dstWords[c] = fillWord
                                    }
                                }
                                platform.posix.memcpy(
                                    kotlinx.cinterop.interpretCPointer<kotlinx.cinterop.ByteVar>(bufPixels.rawValue + (dstRowBase + rightDstCol * 4).toLong()),
                                    pinned.addressOf(srcRowBase + rightSrcCol * 4),
                                    rightBytes
                                )
                            }
                        }
                        val dx = newW - oldW
                        for (hr in prevRegion.hitRegions) {
                            val hL = if (hr.widget === rowWidget) left else (left + hr.relLeft + dx)
                            val hR = if (hr.widget === rowWidget) {
                                if (rightItems.isNotEmpty()) (rightStart - 4).coerceAtLeast(left + 60) else right
                            } else {
                                left + hr.relRight + dx
                            }
                            Win32NativeWindowHost.addHitRegion(
                                Win32NativeWindowHost.HitRegion(
                                    left = hL,
                                    top = top + hr.relTop,
                                    right = hR,
                                    bottom = top + hr.relBottom,
                                    tooltip = hr.tooltip,
                                    widget = hr.widget,
                                    isTextInput = hr.isTextInput,
                                    onClick = hr.onClick,
                                    onDrag = hr.onDrag,
                                    onRelease = hr.onRelease
                                )
                            )
                        }
                        return bottom
                    }
                }
            }
        }
        val rowHitStartIdx = Win32NativeWindowHost.getHitRegionsCount()

        if (isClickableRow && rowBaseImpl != null) {
            val rowHitRight = if (rightItems.isNotEmpty()) (rightStart - 4).coerceAtLeast(left + 60) else right
            Win32NativeWindowHost.addHitRegion(
                Win32NativeWindowHost.HitRegion(
                    left = left,
                    top = top,
                    right = rowHitRight,
                    bottom = bottom,
                    tooltip = nonRedundantTooltip(rowBaseImpl.getTooltipText()),
                    widget = rowWidget,
                    onClick = { _, _ -> rowBaseImpl.dispatchClick() }
                )
            )
        }

        // 2. Resolve optional leading icon (emoji or async image) and 1-or-2-line text stack BEFORE drawing
        val imgChild = leftItems.firstOrNull {
            it.attributes["nativeImpl"] is DesktopImageNativeImpl<*> ||
                widgetTag(it) == "img" ||
                it.attributes.containsKey("sourceUrl")
        }
        val imgImpl = imgChild?.attributes?.get("nativeImpl") as? DesktopImageNativeImpl<*>
        val iconUrl = attrIconUrl?.takeIf { it.isNotEmpty() }
            ?: imgImpl?.getSourceUrl()?.trim()?.takeIf { it.isNotEmpty() }
            ?: (imgChild?.attributes?.get("sourceUrl") as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: (imgChild?.attributes?.get("src") as? String)?.trim()?.takeIf { it.isNotEmpty() }

        val leadingTextChild = leftItems.firstOrNull {
            it !== imgChild &&
                it.attributes["nativeImpl"] is DesktopTextNativeImpl<*> &&
                visibleChildrenOf(it, titleBarWidgets).isEmpty() &&
                (Win32NativeWindowHost.widgetHasClass(it, "badge") ||
                    Win32NativeWindowHost.widgetHasClass(it, "row-emoji-icon") ||
                    leftItems.size >= 2)
        }
        val leadingBadgeText = (leadingTextChild?.attributes?.get("nativeImpl") as? DesktopTextNativeImpl<*>)?.getText()?.trim().orEmpty()
        val isNoisyBracketBadge = leadingBadgeText.startsWith("[") && leadingBadgeText.endsWith("]")
        val emojiIcon = attrEmojiIcon?.takeIf { it.isNotEmpty() }
            ?: leadingBadgeText.takeIf { it.isNotEmpty() && !isNoisyBracketBadge }

        val stackContainer = leftItems.firstOrNull {
            it !== imgChild && it !== leadingTextChild && visibleChildrenOf(it, titleBarWidgets).isNotEmpty()
        }
        val stackLines = if (stackContainer != null) {
            visibleChildrenOf(stackContainer, titleBarWidgets)
                .mapNotNull { (it.attributes["nativeImpl"] as? DesktopTextNativeImpl<*>)?.getText()?.trim() }
                .filter { it.isNotEmpty() }
        } else {
            leftItems.filter { it !== imgChild && it !== leadingTextChild }
                .mapNotNull { (it.attributes["nativeImpl"] as? DesktopTextNativeImpl<*>)?.getText()?.trim() }
                .filter { it.isNotEmpty() }
        }
        val titleText = (rowWidget.attributes["rowTitle"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: stackLines.getOrNull(0).orEmpty()
        val detailText = (rowWidget.attributes["rowDetail"] as? String)?.trim()
            ?: stackLines.getOrNull(1).orEmpty()

        val padLeft = if (isIndented) 36 else 16
        val iconBoxSize = 48
        val iconLeft = left + padLeft
        val iconTop = top + (rowH - iconBoxSize) / 2
        val iconRight = iconLeft + iconBoxSize
        val iconBottom = iconTop + iconBoxSize

        val rowW = right - left
        val canUseRowTemplate = visTop == top &&
            visBottom == bottom &&
            rowW > 64 &&
            canUseSurfaceRegionCache(left, top, right, bottom, checkScrollClip = false)
        val resolvedCssFilter = if (emojiIcon.isNullOrBlank() && !iconUrl.isNullOrBlank()) {
            resolveWidgetCssFilter(imgChild, rowWidget)
        } else {
            null
        }
        val hasLeadingBox = !emojiIcon.isNullOrBlank() || !iconUrl.isNullOrBlank()
        val topLeftBgWord = if (canUseRowTemplate && bufPixels != null && bufW > 0) {
            kotlinx.cinterop.interpretCPointer<kotlinx.cinterop.UIntVar>(
                bufPixels.rawValue + ((top * bufW + left) * 4).toLong()
            )!![0]
        } else {
            0u
        }
        val templateKey = if (canUseRowTemplate) {
            RowBaseTemplateKey(
                width = rowW,
                rowH = rowH,
                isRowHovered = isRowHovered,
                padLeft = padLeft,
                hasLeadingBox = hasLeadingBox,
                isDark = theme.isDark,
                backdropPixel = topLeftBgWord
            )
        } else {
            null
        }
        val neededTemplateBytes = rowW * rowH * 4
        val cachedTemplateBytes = if (templateKey != null) cachedRowBaseTemplates[templateKey] else null
        if (cachedTemplateBytes != null &&
            cachedTemplateBytes.size >= neededTemplateBytes &&
            bufPixels != null
        ) {
            Win32NativeWindowHost.flushGdiIfOverlapping(left, top, right, bottom)
            Win32NativeWindowHost.markFrameBufferDirty(left, top, right, bottom)
            val rowBytes = (rowW * 4).toULong()
            cachedTemplateBytes.usePinned { pinned ->
                for (r in 0 until rowH) {
                    val dstOff = ((top + r) * bufW + left) * 4
                    val srcOff = (r * rowW) * 4
                    platform.posix.memcpy(
                        kotlinx.cinterop.interpretCPointer<kotlinx.cinterop.ByteVar>(bufPixels.rawValue + dstOff.toLong()),
                        pinned.addressOf(srcOff),
                        rowBytes
                    )
                }
            }
        } else {
            Win32NativeWindowHost.drawRoundedRect(
                hdc = hdc,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                radius = 6,
                fillColor = if (isRowHovered) theme.bgElevated else theme.bgCard,
                borderColor = if (isRowHovered) theme.accentBlueBorder else theme.borderSubtle
            )
            if (hasLeadingBox) {
                Win32NativeWindowHost.drawRoundedRect(
                    hdc = hdc,
                    left = iconLeft,
                    top = iconTop,
                    right = iconRight,
                    bottom = iconBottom,
                    radius = 8,
                    fillColor = theme.bgElevated,
                    borderColor = theme.borderSubtle
                )
            }
            if (templateKey != null && bufPixels != null) {
                if (cachedRowBaseTemplates.size >= 24) {
                    cachedRowBaseTemplates.clear()
                }
                val newTemplateBytes = ByteArray(neededTemplateBytes)
                val rowBytes = (rowW * 4).toULong()
                newTemplateBytes.usePinned { pinned ->
                    for (r in 0 until rowH) {
                        val srcOff = ((top + r) * bufW + left) * 4
                        val dstOff = (r * rowW) * 4
                        platform.posix.memcpy(
                            pinned.addressOf(dstOff),
                            kotlinx.cinterop.interpretCPointer<kotlinx.cinterop.ByteVar>(bufPixels.rawValue + srcOff.toLong()),
                            rowBytes
                        )
                    }
                }
                cachedRowBaseTemplates[templateKey] = newTemplateBytes
            }
        }

        val placeholderUrl = if (emojiIcon.isNullOrBlank() && !iconUrl.isNullOrBlank()) {
            imgImpl?.getPlaceholderUrl()?.trim().takeUnless { it.isNullOrEmpty() }
                ?: ImageLoader.DEFAULT_PLACEHOLDER_URI
        } else {
            ImageLoader.DEFAULT_PLACEHOLDER_URI
        }
        val antialiasing = imgImpl?.isAntialiasing() ?: true
        val drewIconImage = if (emojiIcon.isNullOrBlank() && !iconUrl.isNullOrBlank()) {
            drawCachedOrAsyncImageBox(
                hwnd = hwnd,
                hdc = hdc,
                theme = theme,
                fontFallback = fontSmallBold ?: fontBold,
                url = iconUrl,
                placeholderUrl = placeholderUrl,
                antialiasing = antialiasing,
                ownerWidget = imgChild ?: rowWidget,
                fallbackLabel = titleText,
                cssFilter = resolvedCssFilter,
                left = iconLeft,
                top = iconTop,
                right = iconRight,
                bottom = iconBottom,
                surfaceBgColorRef = theme.bgElevated,
                drawBoxBackground = false,
                drawBackground = true,
                drawForeground = false
            )
        } else {
            false
        }

        // Pass 1 over rightItems: draw all software rounded-rect backgrounds (or blit cached right item templates) BEFORE any GDI text on this row
        val canCacheRightItems = canUseRowTemplate && bufPixels != null && bufW > 0 && hoveredRightBtnPtr == 0L && pressedRightBtnPtr == 0L
        val rightItemHitCache = if (rightItems.isNotEmpty()) BooleanArray(rightItems.size) else null
        var localMissingRightItems: ArrayList<DeferredRightItemCapture>? = null
        for (i in rightItems.indices) {
            val item = rightItems[i]
            val w = rightWidths[i]
            if (w <= 0) continue
            val itemR = (rx + w).coerceAtMost(right - padRight)
            val itemW = itemR - rx
            when (val impl = item.attributes["nativeImpl"]) {
                is DesktopTextNativeImpl<*> -> {
                    val txt = impl.getText().trim()
                    if (txt.isNotEmpty()) {
                        val pillTop = top + (rowH - 20) / 2
                        val pillH = 20
                        val typeOrd = (impl.getType() ?: impl.getBackgroundType())?.ordinal ?: -1
                        val itemKey = if (canCacheRightItems && itemW > 8) {
                            RightItemTemplateKey(itemW, pillH, isRowHovered, theme.isDark, 100 + typeOrd, false, txt, "")
                        } else {
                            null
                        }
                        val cachedBytes = if (itemKey != null) cachedRightItemTemplates[itemKey] else null
                        if (cachedBytes != null && bufPixels != null && cachedBytes.size >= itemW * pillH * 4) {
                            val rowBytes = (itemW * 4).toULong()
                            cachedBytes.usePinned { pinned ->
                                for (r in 0 until pillH) {
                                    val dstOff = ((pillTop + r) * bufW + rx) * 4
                                    val srcOff = (r * itemW) * 4
                                    platform.posix.memcpy(
                                        kotlinx.cinterop.interpretCPointer<kotlinx.cinterop.ByteVar>(bufPixels.rawValue + dstOff.toLong()),
                                        pinned.addressOf(srcOff),
                                        rowBytes
                                    )
                                }
                            }
                            rightItemHitCache?.set(i, true)
                        } else {
                            drawBadgePill(
                                hdc = hdc,
                                theme = theme,
                                fontSmall = fontSmall,
                                widget = item,
                                text = txt,
                                left = rx,
                                top = pillTop,
                                drawBackground = true,
                                drawForeground = false
                            )
                            if (itemKey != null) {
                                if (localMissingRightItems == null) localMissingRightItems = ArrayList(4)
                                localMissingRightItems.add(DeferredRightItemCapture(itemKey, rx, pillTop, itemW, pillH))
                            }
                        }
                    }
                }
                is DesktopButtonNativeImpl<*> -> {
                    val bh = 28
                    val by = top + (rowH - bh) / 2
                    val isPrimary = impl.getType() == BootstrapType.PRIMARY || impl.getType() == BootstrapType.SUCCESS
                    val lbl = impl.getText()
                    val icn = impl.getIcon().orEmpty()
                    val itemKey = if (canCacheRightItems && itemW > 8 && !(impl is DesktopStyledButtonNativeImpl<*>)) {
                        RightItemTemplateKey(itemW, bh, isRowHovered, theme.isDark, impl.getType()?.ordinal ?: -1, isPrimary, lbl, icn)
                    } else {
                        null
                    }
                    val cachedBytes = if (itemKey != null) cachedRightItemTemplates[itemKey] else null
                    if (cachedBytes != null && bufPixels != null && cachedBytes.size >= itemW * bh * 4) {
                        val rowBytes = (itemW * 4).toULong()
                        cachedBytes.usePinned { pinned ->
                            for (r in 0 until bh) {
                                val dstOff = ((by + r) * bufW + rx) * 4
                                val srcOff = (r * itemW) * 4
                                platform.posix.memcpy(
                                    kotlinx.cinterop.interpretCPointer<kotlinx.cinterop.ByteVar>(bufPixels.rawValue + dstOff.toLong()),
                                    pinned.addressOf(srcOff),
                                    rowBytes
                                )
                            }
                        }
                        rightItemHitCache?.set(i, true)
                    } else {
                        Win32NativeWindowHost.drawButtonControl(
                            hdc = hdc,
                            theme = theme,
                            label = lbl,
                            icon = impl.getIcon(),
                            left = rx,
                            top = by,
                            right = itemR,
                            bottom = by + bh,
                            primary = isPrimary,
                            widget = item,
                            hwndForHover = hwnd,
                            drawBackground = true,
                            drawForeground = false
                        )
                        if (itemKey != null) {
                            if (localMissingRightItems == null) localMissingRightItems = ArrayList(4)
                            localMissingRightItems.add(DeferredRightItemCapture(itemKey, rx, by, itemW, bh))
                        }
                    }
                    val bx = rx
                    Win32NativeWindowHost.addHitRegion(
                        Win32NativeWindowHost.HitRegion(
                            left = bx,
                            top = by,
                            right = itemR,
                            bottom = by + bh,
                            tooltip = nonRedundantTooltip(impl.getTooltipText(), lbl),
                            widget = item,
                            onClick = { _, _ -> impl.dispatchClick() }
                        )
                    )
                }
            }
            rx = itemR + gap
        }

        // Pass 2 over rightItems: draw GDI text labels only for uncached rightItems in a single contiguous GDI pass
        rx = rightStart
        for (i in rightItems.indices) {
            val item = rightItems[i]
            val w = rightWidths[i]
            if (w <= 0) continue
            val itemR = (rx + w).coerceAtMost(right - padRight)
            if (rightItemHitCache != null && rightItemHitCache[i]) {
                rx = itemR + gap
                continue
            }
            when (val impl = item.attributes["nativeImpl"]) {
                is DesktopTextNativeImpl<*> -> {
                    val txt = impl.getText().trim()
                    if (txt.isNotEmpty()) {
                        drawBadgePill(
                            hdc = hdc,
                            theme = theme,
                            fontSmall = fontSmall,
                            widget = item,
                            text = txt,
                            left = rx,
                            top = top + (rowH - 20) / 2,
                            drawBackground = false,
                            drawForeground = true
                        )
                    }
                }
                is DesktopButtonNativeImpl<*> -> {
                    val bh = 28
                    val by = top + (rowH - bh) / 2
                    val isPrimary = impl.getType() == BootstrapType.PRIMARY || impl.getType() == BootstrapType.SUCCESS
                    SelectObject(hdc, fontSmall)
                    Win32NativeWindowHost.drawButtonControl(
                        hdc = hdc,
                        theme = theme,
                        label = impl.getText(),
                        icon = impl.getIcon(),
                        left = rx,
                        top = by,
                        right = itemR,
                        bottom = by + bh,
                        primary = isPrimary,
                        widget = item,
                        hwndForHover = hwnd,
                        drawBackground = false,
                        drawForeground = true
                    )
                }
            }
            rx = itemR + gap
        }
        if (!emojiIcon.isNullOrBlank()) {
            SelectObject(hdc, fontTitle ?: fontBold)
            Win32NativeWindowHost.drawTextInRect(
                hdc = hdc,
                text = emojiIcon,
                left = iconLeft + 2,
                top = iconTop + 2,
                right = iconRight - 2,
                bottom = iconBottom - 2,
                colorRef = if (isDisabled) theme.textMuted else theme.textPrimary,
                center = true
            )
        } else if (!iconUrl.isNullOrBlank() && !drewIconImage) {
            val iconOwner = imgChild ?: rowWidget
            val monogram = if (iconOwner.attributes["_cachedMonogramSrc"] == titleText) {
                iconOwner.attributes["_cachedMonogram"] as? String ?: ""
            } else {
                buildMonogram(titleText).also {
                    iconOwner.attributes["_cachedMonogramSrc"] = titleText
                    iconOwner.attributes["_cachedMonogram"] = it
                }
            }
            if (monogram.isNotEmpty()) {
                SelectObject(hdc, fontSmallBold ?: fontBold)
                Win32NativeWindowHost.drawTextInRect(
                    hdc = hdc,
                    text = monogram,
                    left = iconLeft + 2,
                    top = iconTop + 2,
                    right = iconRight - 2,
                    bottom = iconBottom - 2,
                    colorRef = theme.textSecondary,
                    center = true
                )
            }
        }

        val textLeft = if (hasLeadingBox) {
            iconRight + 12
        } else {
            left + padLeft
        }

        val maxLeftRight = (rightStart - 10).coerceAtLeast(textLeft + 60)
        val primaryColor = if (isDisabled) theme.textMuted else theme.textPrimary
        val secondaryColor = if (isDisabled) theme.textMuted else theme.textSecondary
        val titleFont = if (isHeader) (fontTitle ?: fontBold) else fontBold

        if (titleText.isNotEmpty()) {
            if (detailText.isBlank()) {
                SelectObject(hdc, titleFont)
                Win32NativeWindowHost.drawTextInRect(
                    hdc = hdc,
                    text = titleText,
                    left = textLeft,
                    top = top + 14,
                    right = maxLeftRight,
                    bottom = bottom - 14,
                    colorRef = primaryColor
                )
            } else {
                SelectObject(hdc, titleFont)
                Win32NativeWindowHost.drawTextInRect(
                    hdc = hdc,
                    text = titleText,
                    left = textLeft,
                    top = top + 8,
                    right = maxLeftRight,
                    bottom = top + 28,
                    colorRef = primaryColor
                )
                SelectObject(hdc, fontSmall)
                Win32NativeWindowHost.drawTextInRect(
                    hdc = hdc,
                    text = detailText,
                    left = textLeft,
                    top = top + 29,
                    right = maxLeftRight,
                    bottom = bottom - 7,
                    colorRef = secondaryColor
                )
            }
        }
        if (localMissingRightItems != null && localMissingRightItems.isNotEmpty() && bufPixels != null) {
            Win32NativeWindowHost.flushGdiIfOverlapping(rightStart, top, right, bottom)
            for (ri in localMissingRightItems) {
                if (cachedRightItemTemplates.size >= 64) {
                    cachedRightItemTemplates.clear()
                }
                val itemBytes = ByteArray(ri.width * ri.height * 4)
                val rowBytes = (ri.width * 4).toULong()
                itemBytes.usePinned { pinned ->
                    for (r in 0 until ri.height) {
                        val srcOff = ((ri.top + r) * bufW + ri.left) * 4
                        val dstOff = (r * ri.width) * 4
                        platform.posix.memcpy(
                            pinned.addressOf(dstOff),
                            kotlinx.cinterop.interpretCPointer<kotlinx.cinterop.ByteVar>(bufPixels.rawValue + srcOff.toLong()),
                            rowBytes
                        )
                    }
                }
                cachedRightItemTemplates[ri.itemKey] = itemBytes
            }
        }

        if (rowCacheKey != null &&
            !isResizeFrame &&
            !isFirstPaintOfLazyList &&
            canUseSurfaceRegionCache(left, visTop, right, visBottom, checkScrollClip = false)
        ) {
            val postHasActiveBgra = leftItems.any { it.attributes.containsKey("activeBgraEntry") } || rowWidget.attributes.containsKey("activeBgraEntry")
            if (deferRowCaptureInLazyLoop) {
                pendingRowCaptures.add(
                    DeferredRowCapture(
                        rowCacheKey = rowCacheKey,
                        fullRowCacheKey = if (visTop == top && visH == rowH) fullRowCacheKey else null,
                        widgetPtr = rowWidget.nativePointer,
                        left = left,
                        visTop = visTop,
                        right = right,
                        visBottom = visBottom,
                        hitStartIdx = rowHitStartIdx,
                        hitEndIdx = Win32NativeWindowHost.getHitRegionsCount(),
                        postHasActiveBgra = postHasActiveBgra
                    )
                )
            } else {
                val evicted = if (!cachedCompositeRowRegions.containsKey(rowCacheKey) && cachedCompositeRowRegions.size >= MAX_CACHED_ROW_REGIONS) {
                    val firstKey = cachedCompositeRowRegions.keys.firstOrNull()
                    if (firstKey != null) cachedCompositeRowRegions.remove(firstKey) else null
                } else {
                    cachedCompositeRowRegions[rowCacheKey]
                }
                val captured = captureCachedRegion(left, visTop, right, visBottom, rowHitStartIdx, reuseBuffer = evicted?.bgra)
                if (captured != null) {
                    cachedCompositeRowRegions[rowCacheKey] = captured
                    if (postHasActiveBgra != rowCacheKey.hasActiveBgra) {
                        cachedCompositeRowRegions[rowCacheKey.copy(hasActiveBgra = postHasActiveBgra)] = captured
                    }
                    if (visTop == top &&
                        visH == rowH &&
                        fullRowCacheKey != null &&
                        fullRowCacheKey.hoveredRightBtnPtr == 0L &&
                        fullRowCacheKey.pressedRightBtnPtr == 0L
                    ) {
                        cachedFullRowByWidgetPtr[fullRowPtrKey(rowWidget.nativePointer, fullRowCacheKey.isRowHovered)] = fullRowCacheKey to captured
                    }
                }
            }
        }

        return bottom
    }

    private fun renderHorizontalRow(
        hwnd: HWND,
        hdc: HDC?,
        theme: Win32NativeWindowHost.SystemThemePalette,
        fontSmall: HFONT?,
        fontSmallBold: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        fontTitle: HFONT?,
        fontMetricBig: HFONT?,
        rowWidget: DesktopNativeWidget,
        children: List<DesktopNativeWidget>,
        titleBarWidgets: Set<DesktopNativeWidget>,
        left: Int,
        top: Int,
        right: Int
    ): Int {
        if (children.isEmpty()) return top
        val availW = (right - left).coerceAtLeast(40)

        // Composite list row (`NativeRow.buildComponent`: `[leftSection, Spacer, rightSection]`)
        if (isCompositeListRow(rowWidget, children, titleBarWidgets)) {
            return renderCompositeListRow(
                hwnd = hwnd,
                hdc = hdc,
                theme = theme,
                fontSmall = fontSmall,
                fontSmallBold = fontSmallBold,
                fontNormal = fontNormal,
                fontBold = fontBold,
                fontTitle = fontTitle,
                rowWidget = rowWidget,
                children = children,
                titleBarWidgets = titleBarWidgets,
                left = left,
                top = top,
                right = right
            )
        }

        // Check if this row is a multi-column layout row where children are container columns (e.g., 2 cards side-by-side)
        val allContainerColumns = children.size in 2..4 && children.all { child ->
            val impl = child.attributes["nativeImpl"]
            impl !is DesktopButtonNativeImpl<*> &&
                impl !is DesktopTextNativeImpl<*> &&
                impl !is DesktopTextInputNativeImpl<*> &&
                impl !is DesktopSelectNativeImpl<*> &&
                impl !is DesktopCheckboxNativeImpl<*> &&
                impl !is DesktopRangeSliderNativeImpl<*> &&
                impl !is DesktopImageNativeImpl<*> &&
                !isSpacerWidget(child) &&
                safeChildrenSnapshot(child).isNotEmpty()
        }
        if (allContainerColumns) {
            val gap = 10
            val colW = max(80, (availW - gap * (children.size - 1)) / children.size)
            var maxBottom = top
            children.forEachIndexed { idx, colChild ->
                val cLeft = left + idx * (colW + gap)
                val cRight = if (idx == children.lastIndex) right else cLeft + colW
                val b = renderFlowNode(
                    hwnd, hdc, theme, fontSmall, fontSmallBold, fontNormal, fontBold, fontTitle, fontMetricBig,
                    colChild, titleBarWidgets, cLeft, top, cRight, top + 2000
                )
                if (b > maxBottom) maxBottom = b
            }
            return maxBottom
        }

        // Otherwise flow inline controls horizontally (with automatic line wrapping if the row overflows!)
        var curX = left
        var curY = top
        var rowHeight = 28
        val gapX = 6
        val gapY = 6

        val hasFillSpacer = children.any { isFillSpacerWidget(it) }
        val firstFillSpacerIndex = children.indexOfFirst { isFillSpacerWidget(it) }
        val totalFillWeight = children.filter { isFillSpacerWidget(it) }.sumOf { getSpacerWeight(it).toDouble() }.toFloat()
        val fixedAndContentWidth = children.filter { !isFillSpacerWidget(it) }.sumOf { child ->
            if (isSpacerWidget(child)) {
                getFixedSpacerWidth(child)
            } else {
                measureInlineChildWidth(child, availW).coerceAtMost(availW) + gapX
            }
        }
        val remainingForFill = (availW - fixedAndContentWidth).coerceAtLeast(0)

        val isCenteredRow = !hasFillSpacer && (
            Win32NativeWindowHost.widgetHasClass(rowWidget, "justify-content-center") ||
                rowWidget.resolveCssValue("justify-content")?.lowercase() == "center"
            )
        if (isCenteredRow) {
            val totalItemsW = children.sumOf { measureInlineChildWidth(it, availW).coerceAtMost(availW) } +
                gapX * (children.size - 1).coerceAtLeast(0)
            if (totalItemsW in 1 until availW) {
                curX = left + (availW - totalItemsW) / 2
            }
        }

        for (i in children.indices) {
            val child = children[i]
            if (isSpacerWidget(child)) {
                if (isFillSpacerWidget(child)) {
                    val weight = getSpacerWeight(child)
                    val share = if (totalFillWeight > 0f) {
                        (remainingForFill * (weight / totalFillWeight)).roundToInt()
                    } else {
                        remainingForFill
                    }
                    curX = min(right, curX + share)
                } else {
                    curX = min(right, curX + getFixedSpacerWidth(child))
                }
                continue
            }

            val rawItemW = measureInlineChildWidth(child, availW).coerceAtMost(availW)
            val impl = child.attributes["nativeImpl"]
            val itemW = if (impl is DesktopTextInputNativeImpl<*> && children.size in 2..3) {
                val othersW = children.filter { it !== child }.sumOf { measureInlineChildWidth(it, availW) + gapX }
                (availW - othersW).coerceIn(120, availW)
            } else {
                rawItemW
            }
            if (curX > left && curX + itemW > right && (!hasFillSpacer || i > firstFillSpacerIndex)) {
                curX = left
                curY += rowHeight + gapY
                rowHeight = 28
            }

            val itemRight = min(right, curX + itemW)
            when (impl) {
                is DesktopImageNativeImpl<*> -> {
                    val imgH = ((child.attributes["height"] as? Number)?.toInt()
                        ?: child.preferredSize?.height
                        ?: 28).coerceIn(16, 96)
                    val cssFilter = resolveWidgetCssFilter(child, rowWidget)
                    val placeholderUrl = impl.getPlaceholderUrl()?.trim().takeUnless { it.isNullOrEmpty() }
                        ?: ImageLoader.DEFAULT_PLACEHOLDER_URI
                    drawCachedOrAsyncImageBox(
                        hwnd = hwnd,
                        hdc = hdc,
                        theme = theme,
                        fontFallback = fontSmallBold ?: fontBold,
                        url = impl.getSourceUrl()?.trim().orEmpty(),
                        placeholderUrl = placeholderUrl,
                        antialiasing = impl.isAntialiasing(),
                        ownerWidget = child,
                        fallbackLabel = (child.attributes["fallbackText"] as? String)?.trim().orEmpty(),
                        cssFilter = cssFilter,
                        left = curX,
                        top = curY,
                        right = itemRight,
                        bottom = curY + imgH,
                        surfaceBgColorRef = theme.bgCard
                    )
                    rowHeight = max(rowHeight, imgH)
                }

                is DesktopButtonNativeImpl<*> -> {
                    val bh = 28
                    val isPrimary = impl.getType() == BootstrapType.PRIMARY
                    SelectObject(hdc, fontSmall)
                    Win32NativeWindowHost.drawButtonControl(
                        hdc = hdc,
                        theme = theme,
                        label = impl.getText(),
                        icon = impl.getIcon(),
                        left = curX,
                        top = curY,
                        right = itemRight,
                        bottom = curY + bh,
                        primary = isPrimary,
                        widget = child,
                        hwndForHover = hwnd
                    )
                    drawCustomButtonIconIfPresent(
                        hwnd = hwnd,
                        hdc = hdc,
                        theme = theme,
                        fontFallback = fontSmall,
                        buttonWidget = child,
                        left = curX,
                        top = curY,
                        right = itemRight,
                        bottom = curY + bh,
                        surfaceBgColorRef = theme.bgCard
                    )
                    val bx = curX
                    val by = curY
                    Win32NativeWindowHost.addHitRegion(
                        Win32NativeWindowHost.HitRegion(
                            left = bx,
                            top = by,
                            right = itemRight,
                            bottom = by + bh,
                            tooltip = nonRedundantTooltip(impl.getTooltipText(), impl.getText()),
                            widget = child,
                            onClick = { _, _ -> impl.dispatchClick() }
                        )
                    )
                    rowHeight = max(rowHeight, bh)
                }

                is DesktopTextNativeImpl<*> -> {
                    val txt = impl.getText().trim()
                    if (txt.isEmpty()) continue
                    if (Win32NativeWindowHost.widgetHasClass(child, "badge")) {
                        drawBadgePill(hdc, theme, fontSmall, child, txt, curX, curY + 3)
                        rowHeight = max(rowHeight, 24)
                    } else {
                        val isHeading = widgetTag(child).let { it.startsWith("h") && (it.length == 2 || it.contains("heading")) }
                        SelectObject(hdc, if (isHeading) fontBold else fontNormal)
                        val fg = resolveContrastSafeTextColor(impl, theme, isHeading)
                        Win32NativeWindowHost.drawTextInRect(hdc, txt, curX, curY + 2, itemRight, curY + 26, fg)
                        rowHeight = max(rowHeight, 26)
                    }
                }

                is DesktopTextInputNativeImpl<*> -> {
                    val ih = 28
                    renderTextInputControl(hwnd, hdc, theme, fontNormal, impl, child, curX, curY, itemRight, curY + ih)
                    rowHeight = max(rowHeight, ih)
                }

                is DesktopSelectNativeImpl<*> -> {
                    val sh = 28
                    val curVal = impl.getSelectedValue() ?: ""
                    val curLabel = impl.getOptions().firstOrNull { it.first == curVal }?.second ?: curVal
                    SelectObject(hdc, fontSmall)
                    Win32NativeWindowHost.drawSelectControl(
                        hdc = hdc,
                        theme = theme,
                        label = curLabel.ifBlank { "Select..." },
                        left = curX,
                        top = curY,
                        right = itemRight,
                        bottom = curY + sh,
                        widget = child,
                        hwndForHover = hwnd
                    )
                    val sx = curX
                    val sy = curY
                    Win32NativeWindowHost.addHitRegion(
                        Win32NativeWindowHost.HitRegion(
                            left = sx,
                            top = sy,
                            right = itemRight,
                            bottom = sy + sh,
                            tooltip = nonRedundantTooltip(impl.getTooltipText(), curLabel.ifBlank { "Select..." }),
                            widget = child,
                            onClick = { mx, my -> Win32NativeWindowHost.openSelectPopupMenu(hwnd, impl, mx, my) }
                        )
                    )
                    rowHeight = max(rowHeight, sh)
                }

                is DesktopCheckboxNativeImpl<*> -> {
                    val ch = 24
                    SelectObject(hdc, fontSmall)
                    Win32NativeWindowHost.drawCheckboxControl(
                        hdc = hdc,
                        theme = theme,
                        label = impl.getLabel(),
                        checked = impl.isChecked(),
                        left = curX,
                        top = curY + 2,
                        right = itemRight,
                        bottom = curY + ch,
                        widget = child,
                        hwndForHover = hwnd
                    )
                    val cx = curX
                    val cy = curY
                    Win32NativeWindowHost.addHitRegion(
                        Win32NativeWindowHost.HitRegion(
                            left = cx,
                            top = cy,
                            right = itemRight,
                            bottom = cy + ch,
                            tooltip = nonRedundantTooltip(impl.getTooltipText(), impl.getLabel()),
                            widget = child,
                            onClick = { _, _ -> impl.setChecked(!impl.isChecked()) }
                        )
                    )
                    rowHeight = max(rowHeight, ch)
                }

                is DesktopRangeSliderNativeImpl<*> -> {
                    val slH = 24
                    Win32NativeWindowHost.drawSliderControl(hdc, theme, impl, curX, curY + 4, itemRight, curY + slH - 2)
                    val sL = curX
                    val sR = itemRight
                    val sY = curY
                    Win32NativeWindowHost.addHitRegion(
                        Win32NativeWindowHost.HitRegion(
                            left = sL,
                            top = sY,
                            right = sR,
                            bottom = sY + slH,
                            tooltip = impl.getTooltipText(),
                            widget = child,
                            onClick = { mx, _ ->
                                impl.beginDragFromFraction((mx - sL).toDouble() / max(1, sR - sL).toDouble())
                            },
                            onDrag = { mx, _ ->
                                impl.updateDragFromFraction((mx - sL).toDouble() / max(1, sR - sL).toDouble())
                            },
                            onRelease = { mx, _ ->
                                impl.endDragFromFraction((mx - sL).toDouble() / max(1, sR - sL).toDouble())
                            }
                        )
                    )
                    rowHeight = max(rowHeight, slH)
                }

                else -> {
                    val subBottom = if (isHorizontalRowContainer(child)) {
                        val nestedChildren = visibleChildrenOf(child, titleBarWidgets)
                        if (nestedChildren.isEmpty()) curY else renderHorizontalRow(
                            hwnd, hdc, theme, fontSmall, fontSmallBold, fontNormal, fontBold, fontTitle, fontMetricBig,
                            child, nestedChildren, titleBarWidgets, curX, curY, itemRight
                        )
                    } else {
                        renderFlowNode(
                            hwnd, hdc, theme, fontSmall, fontSmallBold, fontNormal, fontBold, fontTitle, fontMetricBig,
                            child, titleBarWidgets, curX, curY, itemRight, curY + 400
                        )
                    }
                    rowHeight = max(rowHeight, subBottom - curY)
                }
            }
            curX = itemRight + gapX
        }

        return curY + rowHeight
    }

    private fun renderTextInputControl(
        hwnd: HWND?,
        hdc: HDC?,
        theme: Win32NativeWindowHost.SystemThemePalette,
        fontNormal: HFONT?,
        inputImpl: DesktopTextInputNativeImpl<*>,
        widget: DesktopNativeWidget,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        Win32NativeWindowHost.renderAndMountTextInputControl(
            hwnd = hwnd,
            hdc = hdc,
            theme = theme,
            font = fontNormal,
            inputImpl = inputImpl,
            widget = widget,
            left = left,
            top = top,
            right = right,
            bottom = bottom
        )
    }

    private fun drawBadgePill(
        hdc: HDC?,
        theme: Win32NativeWindowHost.SystemThemePalette,
        fontSmall: HFONT?,
        widget: DesktopNativeWidget,
        text: String,
        left: Int,
        top: Int,
        drawBackground: Boolean = true,
        drawForeground: Boolean = true,
        opacity: Float = 1f
    ): Int {
        val w = measureSingleLineTextWidth(text, 7) + 16
        val h = 20
        val impl = widget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
        val type = impl?.getType()?.takeIf { it.isSemantic }
            ?: impl?.getBackgroundType()?.takeIf { it.isSemantic }
            ?: when {
                Win32NativeWindowHost.widgetHasClass(widget, "bg-success") || Win32NativeWindowHost.widgetHasClass(widget, "text-bg-success") -> BootstrapType.SUCCESS
                Win32NativeWindowHost.widgetHasClass(widget, "bg-warning") || Win32NativeWindowHost.widgetHasClass(widget, "text-bg-warning") -> BootstrapType.WARNING
                Win32NativeWindowHost.widgetHasClass(widget, "bg-danger") || Win32NativeWindowHost.widgetHasClass(widget, "text-bg-danger") -> BootstrapType.DANGER
                Win32NativeWindowHost.widgetHasClass(widget, "bg-primary") || Win32NativeWindowHost.widgetHasClass(widget, "text-bg-primary") -> BootstrapType.PRIMARY
                Win32NativeWindowHost.widgetHasClass(widget, "bg-info") || Win32NativeWindowHost.widgetHasClass(widget, "text-bg-info") -> BootstrapType.INFO
                else -> null
            }
        val (bg, border, fg) = when (type) {
            BootstrapType.SUCCESS -> Triple(theme.accentGreenSoftBg, theme.accentGreenBorder, theme.accentGreen)
            BootstrapType.WARNING -> Triple(theme.accentAmberSoftBg, theme.accentAmber, theme.accentAmber)
            BootstrapType.DANGER -> Triple(theme.accentRedSoftBg, theme.accentRedBorder, theme.accentRed)
            BootstrapType.PRIMARY, BootstrapType.INFO -> Triple(theme.accentBlueSoftBg, theme.accentBlueBorder, theme.accentBlueText)
            else -> Triple(theme.bgElevated, theme.borderSubtle, theme.textSecondary)
        }
        if (drawBackground) {
            Win32NativeWindowHost.drawRoundedRect(hdc, left, top, left + w, top + h, 10, bg, border, opacity = opacity)
        }
        if (drawForeground) {
            SelectObject(hdc, fontSmall)
            Win32NativeWindowHost.drawTextInRect(hdc, text, left + 6, top + 1, left + w - 6, top + h - 1, fg, center = true)
        }
        return w
    }

    private fun estimateFlowNodeHeight(
        child: DesktopNativeWidget,
        titleBarWidgets: Set<DesktopNativeWidget>,
        availW: Int
    ): Int {
        if (!child.visible || child.disposed) return 0
        val hasSurfaceFlag = if (
            child.interactiveSurfaceSpec != null ||
            child.attributes.containsKey("captureBgra") ||
            child.attributes.containsKey("captureBgraPtr")
        ) 1L else 0L
        val cacheKey = (child.nativePointer shl 24) xor (child.subtreeMutationVersion * 31L) xor (hasSurfaceFlag shl 20) xor availW.toLong()
        heightEstimateCache[cacheKey]?.let { return it }

        val impl = child.attributes["nativeImpl"]
        val tag = widgetTag(child)
        val estimated = when {
            isSpacerWidget(child) -> getFixedSpacerHeight(child)
            impl is DesktopLazyListNativeImpl<*> || child.attributes["isLazyList"] == true -> {
                val minH = child.resolveCssValue("min-height")?.removeSuffix("px")?.trim()?.toFloatOrNull()?.roundToInt() ?: 56
                val maxH = child.resolveCssValue("max-height")?.removeSuffix("px")?.trim()?.toFloatOrNull()?.roundToInt() ?: Int.MAX_VALUE
                val rawExtent = (child.attributes["totalContentExtentPx"] as? Number)?.toFloat()?.roundToInt() ?: 0
                val childCount = (child.attributes["totalItemCount"] as? Number)?.toInt()
                    ?: visibleChildrenOf(child, titleBarWidgets).size
                val fallbackExtent = if (rawExtent > 0) rawExtent else (childCount * 56)
                fallbackExtent.coerceIn(minH, maxH.coerceAtLeast(minH))
            }
            impl is DesktopTabContainerNativeImpl<*> -> {
                val activeContentList = resolveActiveTabContentWidgets(child, titleBarWidgets)
                if (activeContentList.isNotEmpty()) {
                    38 + estimateVerticalStackHeight(child, activeContentList, titleBarWidgets, availW) + 8
                } else {
                    38
                }
            }
            impl is DesktopNavbarNativeImpl<*> || Win32NativeWindowHost.widgetHasClass(child, "navbar") -> 44
            impl is DesktopDividerNativeImpl<*> || tag.contains("divider") || tag == "hr" -> 12
            findStageAndOverlay(child, titleBarWidgets) != null -> {
                val (stageW, overlayW) = findStageAndOverlay(child, titleBarWidgets)!!
                resolveVideoStageHeight(availW, stageW, overlayW)
            }
            child.interactiveSurfaceSpec != null -> resolveVideoStageHeight(availW, child, null)
            child.attributes.containsKey("captureBgra") || child.attributes.containsKey("captureBgraPtr") -> {
                val rawH = (child.attributes["surfaceHeight"] as? (() -> Int))?.invoke() ?: 44
                if (rawH in 1..90) max(36, rawH) else ((availW * 9) / 16).coerceIn(200, 380)
            }
            impl is DesktopImageNativeImpl<*> -> {
                ((child.attributes["height"] as? Number)?.toInt()
                    ?: child.preferredSize?.height
                    ?: 48).coerceIn(16, 240)
            }
            impl is DesktopTextNativeImpl<*> -> {
                val t = impl.getText().trim()
                if (t.isEmpty()) 0
                else if (Win32NativeWindowHost.widgetHasClass(child, "badge")) 22
                else {
                    val isHeading = tag == "h1" || tag == "h2" || tag == "h3" || tag == "h4" || tag == "h5" || tag == "h6" ||
                        tag.contains("heading")
                    val lineH = when {
                        tag == "h1" || tag == "h2" -> 30
                        tag == "h3" || tag == "h4" -> 24
                        else -> 20
                    }
                    val avgCharPx = when {
                        tag == "h1" || tag == "h2" -> 11
                        tag == "h3" || tag == "h4" -> 9
                        else -> 7
                    }
                    val fontKind = when {
                        tag == "h1" || tag == "h2" -> 3
                        tag == "h3" || tag == "h4" -> 2
                        isHeading -> 1
                        else -> 0
                    }
                    val maxLines = impl.getMaxLines() ?: (child.attributes["maxLines"] as? Number)?.toInt()
                    val forceSingleLine = maxLines == 1 ||
                        Win32NativeWindowHost.widgetHasClass(child, "text-nowrap") ||
                        Win32NativeWindowHost.widgetHasClass(child, "text-truncate") ||
                        child.resolveCssValue("white-space")?.lowercase() == "nowrap"
                    val singleLineW = max(
                        measureSingleLineTextWidth(t, avgCharPx),
                        Win32NativeWindowHost.measureSingleLineTextWidthWithFont(t, fontKind)
                    )
                    val shouldWrap = !forceSingleLine && (
                        t.contains('\n') || singleLineW > (availW - 8).coerceAtLeast(24)
                        )
                    if (!shouldWrap) {
                        lineH
                    } else {
                        val measuredH = Win32NativeWindowHost.measureTextHeightWithFont(
                            t,
                            (availW - 4).coerceAtLeast(32),
                            lineH,
                            fontKind
                        )
                        if (maxLines != null && maxLines > 1) {
                            min(measuredH, maxLines * lineH + 4)
                        } else {
                            measuredH
                        }
                    }
                }
            }
            impl is DesktopButtonNativeImpl<*> -> 28
            impl is DesktopTextInputNativeImpl<*> -> {
                if (impl.isMultiline()) {
                    max(84, ((child.attributes["rows"] as? Number)?.toInt() ?: 4) * 20)
                } else {
                    30
                }
            }
            impl is DesktopSelectNativeImpl<*> -> 28
            impl is DesktopCheckboxNativeImpl<*> -> 22
            impl is DesktopRangeSliderNativeImpl<*> -> 20
            impl is DesktopProgressBarNativeImpl<*> -> 14
            impl is DesktopSpinnerNativeImpl<*> -> 26
            Win32NativeWindowHost.widgetHasClass(child, "alert") || tag.contains("alert") -> {
                val alertText = Win32NativeWindowHost.extractWidgetText(child).ifBlank {
                    (child.attributes["text"] as? String).orEmpty()
                }
                val charsPerLine = max(20, (availW - 24) / 7)
                val lines = ((alertText.length + charsPerLine - 1) / charsPerLine).coerceIn(1, 4)
                max(34, lines * 18 + 14)
            }
            Win32NativeWindowHost.widgetHasClass(child, "card") -> {
                estimateVerticalStackHeight(child, visibleChildrenOf(child, titleBarWidgets), titleBarWidgets, availW - 48) + 88
            }
            isHorizontalRowContainer(child) -> {
                val rowChildren = visibleChildrenOf(child, titleBarWidgets)
                if (rowChildren.isEmpty()) {
                    0
                } else if (isCompositeListRow(child, rowChildren, titleBarWidgets)) {
                    56
                } else {
                    val sumW = rowChildren.sumOf { c ->
                        if (isSpacerWidget(c)) getFixedSpacerWidth(c) else measureInlineChildWidth(c, availW) + 6
                    }
                    val rows = ((sumW + availW - 1) / availW.coerceAtLeast(1)).coerceIn(1, 4)
                    val maxImgH = rowChildren.maxOfOrNull { c ->
                        if (c.attributes["nativeImpl"] is DesktopImageNativeImpl<*>) {
                            ((c.attributes["height"] as? Number)?.toInt()
                                ?: c.preferredSize?.height
                                ?: 28).coerceIn(16, 96)
                        } else {
                            28
                        }
                    } ?: 28
                    maxImgH + (rows - 1) * 34
                }
            }
            else -> estimateVerticalStackHeight(child, visibleChildrenOf(child, titleBarWidgets), titleBarWidgets, availW)
        }
        if (heightEstimateCache.size > 4096) {
            heightEstimateCache.clear()
        }
        heightEstimateCache[cacheKey] = estimated
        return estimated
    }

    private fun estimateVerticalStackHeight(
        container: DesktopNativeWidget,
        children: List<DesktopNativeWidget>,
        titleBarWidgets: Set<DesktopNativeWidget>,
        availW: Int
    ): Int {
        if (children.isEmpty()) return 0
        var totalH = 0
        for (child in children) {
            val h = estimateFlowNodeHeight(child, titleBarWidgets, availW)
            if (h > 0) {
                totalH += h + 6
            }
        }
        return totalH
    }

    private fun resolveContrastSafeTextColor(
        impl: DesktopTextNativeImpl<*>,
        theme: Win32NativeWindowHost.SystemThemePalette,
        isHeading: Boolean
    ): UInt {
        val explicitRgba = impl.getEffectiveTextColor()
            ?: return if (isHeading) theme.textPrimary else theme.textSecondary
        if (theme.isDark && explicitRgba.r < 65 && explicitRgba.g < 65 && explicitRgba.b < 75) {
            return if (isHeading) theme.textPrimary else theme.textSecondary
        }
        if (!theme.isDark && explicitRgba.r > 220 && explicitRgba.g > 220 && explicitRgba.b > 220) {
            return if (isHeading) theme.textPrimary else theme.textSecondary
        }
        return Win32NativeWindowHost.rgbaToColorRef(explicitRgba)
    }

    private fun resolveWidgetCssFilter(
        widget: DesktopNativeWidget?,
        rowWidget: DesktopNativeWidget?
    ): CssFilter? {
        val cacheHost = widget ?: rowWidget
        val rawStr = (widget?.attributes?.get("cssFilter") as? String)
            ?: (widget?.attributes?.get("rowImageFilter") as? String)
            ?: widget?.resolveCssValue("filter")
            ?: (rowWidget?.attributes?.get("rowImageFilter") as? String)
            ?: (rowWidget?.attributes?.get("cssFilter") as? String)
            ?: rowWidget?.resolveCssValue("filter")
        if (rawStr != null) {
            if (rawStr == "none") return CssFilter.NONE
            if (cacheHost != null && cacheHost.attributes["_cachedFilterRaw"] == rawStr) {
                return cacheHost.attributes["_cachedFilterObj"] as? CssFilter
            }
            val parsedFromStr = if (rawStr.isBlank()) null else CssFilter.parse(rawStr)
            val resolved = if (parsedFromStr != null && parsedFromStr.isNone) CssFilter.NONE else parsedFromStr
            if (cacheHost != null) {
                cacheHost.attributes["_cachedFilterRaw"] = rawStr
                if (resolved != null) {
                    cacheHost.attributes["_cachedFilterObj"] = resolved
                } else {
                    cacheHost.attributes.remove("_cachedFilterObj")
                }
            }
            if (resolved != null) return resolved
        }
        val imgImpl = widget?.attributes?.get("nativeImpl") as? DesktopImageNativeImpl<*>
        imgImpl?.getCssFilter()?.let { return it }
        (widget?.attributes?.get("cssFilter") as? CssFilter)?.let { return it }
        (rowWidget?.attributes?.get("cssFilter") as? CssFilter)?.let { return it }
        return null
    }

    private fun drawCachedOrAsyncImageBox(
        hwnd: HWND,
        hdc: HDC?,
        theme: Win32NativeWindowHost.SystemThemePalette,
        fontFallback: HFONT?,
        url: String,
        placeholderUrl: String = ImageLoader.DEFAULT_PLACEHOLDER_URI,
        antialiasing: Boolean = true,
        ownerWidget: DesktopNativeWidget? = null,
        fallbackLabel: String,
        cssFilter: CssFilter?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        surfaceBgColorRef: UInt,
        drawBoxBackground: Boolean = true,
        drawBackground: Boolean = true,
        drawForeground: Boolean = true,
        transparentBackground: Boolean = false,
        opacity: Float = 1f
    ): Boolean {
        val boxW = (right - left).coerceAtLeast(1)
        val boxH = (bottom - top).coerceAtLeast(1)
        if (drawBoxBackground && drawBackground && !transparentBackground) {
            Win32NativeWindowHost.drawRoundedRect(
                hdc = hdc,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                radius = 8,
                fillColor = surfaceBgColorRef,
                borderColor = theme.borderSubtle
            )
        }

        val inset = 2
        val dstL = left + inset
        val dstT = top + inset
        val dstW = (boxW - inset * 2).coerceAtLeast(1)
        val dstH = (boxH - inset * 2).coerceAtLeast(1)

        val cleanUrl = url.trim()
        val isUnresolvableRelative = cleanUrl.startsWith("/") && !cleanUrl.startsWith("//")
        val isKnownFailedOnOwner = ownerWidget != null && ownerWidget.attributes["_imgFailedUrl"] == cleanUrl
        if (!isUnresolvableRelative && !isKnownFailedOnOwner) {
            val expectedFilter = cssFilter ?: CssFilter.NONE
            val fastLastHit = if (
                cleanUrl.isNotEmpty() &&
                cleanUrl == lastIconCompositeUrl &&
                dstW == lastIconCompositeW &&
                dstH == lastIconCompositeH &&
                cssFilter == lastIconCompositeFilter &&
                theme.isDark == lastIconCompositeDark &&
                surfaceBgColorRef == lastIconCompositeBg
            ) {
                lastIconCompositeEntry
            } else {
                null
            }
            val fastOwnerHit = fastLastHit ?: if (
                cleanUrl.isNotEmpty() &&
                ownerWidget != null &&
                ownerWidget.attributes["activeBgraUrl"] == cleanUrl &&
                ownerWidget.attributes["activeBgraFilter"] == expectedFilter &&
                ownerWidget.attributes["activeBgraBg"] == surfaceBgColorRef
            ) {
                (ownerWidget.attributes["activeBgraEntry"] as? CompositeBgraEntry)?.takeIf { it.width == dstW && it.height == dstH }
            } else {
                null
            }
            val primaryEntry = fastOwnerHit ?: if (cleanUrl.isNotEmpty()) {
                getOrFetchImageBgra(
                    hwnd = hwnd,
                    url = cleanUrl,
                    targetW = dstW,
                    targetH = dstH,
                    antialiasing = antialiasing,
                    cssFilter = cssFilter,
                    isDarkTheme = theme.isDark,
                    surfaceBgColorRef = surfaceBgColorRef,
                    ownerWidget = ownerWidget
                )?.also { fetched ->
                    lastIconCompositeUrl = cleanUrl
                    lastIconCompositeW = dstW
                    lastIconCompositeH = dstH
                    lastIconCompositeFilter = cssFilter
                    lastIconCompositeDark = theme.isDark
                    lastIconCompositeBg = surfaceBgColorRef
                    lastIconCompositeEntry = fetched
                }
            } else {
                null
            }

            val entryToDraw = primaryEntry ?: run {
                // While loading asynchronously (or if primary URL is empty/loading), render the placeholder image (`loading.svg` by default)
                val cleanPlaceholder = placeholderUrl.trim().ifEmpty { ImageLoader.DEFAULT_PLACEHOLDER_URI }
                val isLoading = cleanUrl.isNotEmpty() && withImageCacheLock { cleanUrl in inFlightImageUrls }
                if (isLoading || cleanUrl.isEmpty()) {
                    val fastPlaceholder = (ownerWidget?.attributes?.get("activePlaceholderEntry") as? CompositeBgraEntry)
                        ?.takeIf { it.width == dstW && it.height == dstH }
                    fastPlaceholder ?: getOrFetchImageBgra(
                        hwnd = hwnd,
                        url = cleanPlaceholder,
                        targetW = dstW,
                        targetH = dstH,
                        antialiasing = true,
                        cssFilter = null,
                        isDarkTheme = theme.isDark,
                        surfaceBgColorRef = surfaceBgColorRef,
                        ownerWidget = ownerWidget
                    )
                } else {
                    if (cleanUrl.isNotEmpty() && ownerWidget != null) {
                        ownerWidget.attributes["_imgFailedUrl"] = cleanUrl
                    }
                    null
                }
            }

            if (entryToDraw != null) {
                val imgW = entryToDraw.width.coerceAtLeast(1)
                val imgH = entryToDraw.height.coerceAtLeast(1)
                val bgraBytes = entryToDraw.bgra
                val rgbaBytes = entryToDraw.rgba
                if (transparentBackground && rgbaBytes != null && rgbaBytes.size >= imgW * imgH * 4) {
                    if (drawBackground) {
                        val blendBytes = if (imgW == dstW && imgH == dstH) {
                            rgbaBytes
                        } else {
                            ImageLoader.resize(
                                ImageBuffer(imgW, imgH, bytes = rgbaBytes),
                                dstW,
                                dstH,
                                antialiasing = antialiasing
                            ).toRgba8888().bytes
                        }
                        if (Win32NativeWindowHost.blendRgbaToBackBuffer(
                                hdc = hdc,
                                dstL = dstL,
                                dstT = dstT,
                                dstW = dstW,
                                dstH = dstH,
                                rgba = blendBytes,
                                opacity = opacity
                            )
                        ) {
                            return true
                        }
                    } else {
                        return true
                    }
                }
                if (bgraBytes.size >= imgW * imgH * 4) {
                    if (drawBackground) {
                        if (imgW == dstW && imgH == dstH) {
                            Win32NativeWindowHost.blitScaledBgraToHdc(hdc, dstL, dstT, dstW, dstH, bgraBytes)
                        } else {
                            memScoped {
                                val bmi = alloc<BITMAPINFO>()
                                bmi.bmiHeader.biSize = sizeOf<BITMAPINFOHEADER>().toUInt()
                                bmi.bmiHeader.biWidth = imgW
                                bmi.bmiHeader.biHeight = -imgH
                                bmi.bmiHeader.biPlanes = 1u
                                bmi.bmiHeader.biBitCount = 32u
                                bmi.bmiHeader.biCompression = BI_RGB.toUInt()

                                SetStretchBltMode(hdc, if (antialiasing) HALFTONE else platform.windows.COLORONCOLOR)
                                if (antialiasing) {
                                    SetBrushOrgEx(hdc, 0, 0, null)
                                }
                                bgraBytes.usePinned { pinned ->
                                    StretchDIBits(
                                        hdc,
                                        dstL,
                                        dstT,
                                        dstW,
                                        dstH,
                                        0,
                                        0,
                                        imgW,
                                        imgH,
                                        pinned.addressOf(0),
                                        bmi.ptr,
                                        DIB_RGB_COLORS.toUInt(),
                                        SRCCOPY
                                    )
                                }
                                Win32NativeWindowHost.markGdiRectDirty(dstL, dstT, dstL + dstW, dstT + dstH)
                            }
                        }
                    }
                    return true
                }
            }
        }

        if (!drawForeground) return false

        // Fallback monogram if image failed to load and placeholder is unavailable
        val monogram = if (ownerWidget != null && ownerWidget.attributes["_cachedMonogramSrc"] == fallbackLabel) {
            ownerWidget.attributes["_cachedMonogram"] as? String ?: ""
        } else {
            buildMonogram(fallbackLabel).also {
                if (ownerWidget != null) {
                    ownerWidget.attributes["_cachedMonogramSrc"] = fallbackLabel
                    ownerWidget.attributes["_cachedMonogram"] = it
                }
            }
        }
        if (monogram.isNotEmpty()) {
            SelectObject(hdc, fontFallback)
            Win32NativeWindowHost.drawTextInRect(
                hdc = hdc,
                text = monogram,
                left = left + 2,
                top = top + 2,
                right = right - 2,
                bottom = bottom - 2,
                colorRef = theme.textSecondary,
                center = true
            )
        }
        return true
    }

    private fun buildMonogram(label: String): String {
        val words = label.trim()
            .split(' ', '-', '_', ':')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it[0].isLetterOrDigit() }
        if (words.isEmpty()) return "•"
        return if (words.size >= 2) {
            "${words[0][0].uppercaseChar()}${words[1][0].uppercaseChar()}"
        } else {
            words[0].take(2).uppercase()
        }
    }

    private fun isDefaultLoadingPlaceholderUrl(url: String): Boolean {
        if (url == ImageLoader.DEFAULT_PLACEHOLDER_URI) return true
        val lower = url.trim().lowercase()
        return lower == "res://drawable/loading" ||
            lower == "drawable/loading.svg" ||
            lower == "loading.svg" ||
            lower.startsWith("data:image/svg+xml") ||
            lower.startsWith("data:image/png;base64,")
    }

    private fun buildCompositeBgraFromRawBuffer(
        rawBuffer: ImageBuffer,
        dstW: Int,
        dstH: Int,
        antialiasing: Boolean,
        cssFilter: CssFilter?,
        isDarkTheme: Boolean,
        surfaceBgColorRef: UInt
    ): CompositeBgraEntry {
        val resizedBuf = if (rawBuffer.width != dstW || rawBuffer.height != dstH) {
            ImageLoader.resize(rawBuffer, dstW, dstH, antialiasing = antialiasing).toRgba8888()
        } else {
            rawBuffer.toRgba8888()
        }
        val w = resizedBuf.width
        val h = resizedBuf.height
        val rawRgba = resizedBuf.bytes
        val filteredRgba = when {
            cssFilter != null && cssFilter.isNone -> rawRgba
            cssFilter != null -> cssFilter.applyToRgbaBytes(rawRgba, w, h)
            isDarkTheme && isDarkMonocolorOnTransparent(rawRgba, w, h) ->
                CssFilter.monocolorWhite().applyToRgbaBytes(rawRgba, w, h)
            else -> rawRgba
        }
        val bgra = rgbaToCompositeBgra(filteredRgba, w, h, surfaceBgColorRef)
        return CompositeBgraEntry(bgra, w, h, filteredRgba)
    }

    private fun getOrFetchImageBgra(
        hwnd: HWND,
        url: String,
        targetW: Int,
        targetH: Int,
        antialiasing: Boolean,
        cssFilter: CssFilter?,
        isDarkTheme: Boolean,
        surfaceBgColorRef: UInt,
        ownerWidget: DesktopNativeWidget?
    ): CompositeBgraEntry? {
        val dstW = targetW.coerceIn(8, 512)
        val dstH = targetH.coerceIn(8, 512)
        val filterKey = cssFilter?.toCssString().orEmpty()
        val compositeKey = "$url|w=$dstW|h=$dstH|aa=$antialiasing|f=$filterKey|dark=$isDarkTheme|bg=$surfaceBgColorRef"

        var skipBecauseInFlightOrFailed = false
        val existingComposite = withImageCacheLock {
            val hit = strongCompositeBgraLru[compositeKey]?.also { found ->
                putStrongCompositeBgraLruLocked(compositeKey, found)
            } ?: run {
                val ref = decodedImageBgraCache[compositeKey]
                val target = ref?.get()
                if (ref != null && target == null) {
                    decodedImageBgraCache.remove(compositeKey)
                } else if (target != null) {
                    putStrongCompositeBgraLruLocked(compositeKey, target)
                }
                target
            }
            if (hit == null && (url in inFlightImageUrls || url in failedImageUrls)) {
                skipBecauseInFlightOrFailed = true
            }
            hit
        }
        if (existingComposite != null) {
            if (isDefaultLoadingPlaceholderUrl(url)) {
                ownerWidget?.attributes?.set("activePlaceholderEntry", existingComposite)
            } else {
                ownerWidget?.attributes?.set("activeBgraEntry", existingComposite)
                ownerWidget?.attributes?.set("activeBgraUrl", url)
                ownerWidget?.attributes?.set("activeBgraFilter", cssFilter ?: CssFilter.NONE)
                ownerWidget?.attributes?.set("activeBgraBg", surfaceBgColorRef)
            }
            return existingComposite
        }
        if (skipBecauseInFlightOrFailed) {
            return null
        }

        // Fast-path for built-in default `loading.svg` placeholder
        if (isDefaultLoadingPlaceholderUrl(url)) {
            val placeholderBuf = ImageLoader.createDefaultPlaceholderBuffer(dstW, dstH, isDarkTheme).toRgba8888()
            val bgra = rgbaToCompositeBgra(placeholderBuf.bytes, dstW, dstH, surfaceBgColorRef)
            val entry = CompositeBgraEntry(bgra, dstW, dstH, placeholderBuf.bytes)
            withImageCacheLock {
                putStrongCompositeBgraLruLocked(compositeKey, entry)
                decodedImageBgraCache[compositeKey] = WeakReference(entry)
            }
            ownerWidget?.attributes?.set("activePlaceholderEntry", entry)
            return entry
        }

        val rawBuffer = (ownerWidget?.attributes?.get("activeRawBuffer") as? ImageBuffer) ?: withImageCacheLock {
            strongRawImageLru[url]?.also { hit ->
                putStrongRawImageLruLocked(url, hit)
            } ?: run {
                val ref = rawImageBufferCache[url]
                val target = ref?.get()
                if (ref != null && target == null) {
                    rawImageBufferCache.remove(url)
                } else if (target != null) {
                    putStrongRawImageLruLocked(url, target)
                }
                target
            }
        } ?: ImageLoader.getWeakImageReference(url) ?: ImageCacheManager.getMemoryCached(url)
            ?: if (dstW <= 96 && dstH <= 96 && (url.startsWith("data:", ignoreCase = true) || url.startsWith("res://", ignoreCase = true))) {
                runCatching {
                    val bytes = ImageCacheManager.fetchAndCacheBytes(url)
                    if (bytes != null && bytes.isNotEmpty()) {
                        (sharedWicImageCodec.decode(ImageSource.fromBytes(bytes), ImageDecodeOptions(antialiasing = antialiasing))
                            ?: ImageLoader.loadFromBytes(bytes, ImageDecodeOptions(antialiasing = antialiasing)))?.toRgba8888()?.also { decodedRgba ->
                            ImageLoader.trackWeakImageReference(url, decodedRgba)
                            ImageCacheManager.putInCache(url, decodedRgba)
                            withImageCacheLock {
                                putStrongRawImageLruLocked(url, decodedRgba)
                                rawImageBufferCache[url] = WeakReference(decodedRgba)
                            }
                        }
                    } else {
                        null
                    }
                }.getOrNull()
            } else {
                null
            }

        if (rawBuffer != null && rawBuffer.width > 0 && rawBuffer.height > 0) {
            ownerWidget?.attributes?.set("activeRawBuffer", rawBuffer)
            if (dstW <= 96 && dstH <= 96) {
                val syncEntry = runCatching {
                    buildCompositeBgraFromRawBuffer(
                        rawBuffer = rawBuffer,
                        dstW = dstW,
                        dstH = dstH,
                        antialiasing = antialiasing,
                        cssFilter = cssFilter,
                        isDarkTheme = isDarkTheme,
                        surfaceBgColorRef = surfaceBgColorRef
                    )
                }.getOrNull()
                if (syncEntry != null) {
                    withImageCacheLock {
                        putStrongRawImageLruLocked(url, rawBuffer)
                        putStrongCompositeBgraLruLocked(compositeKey, syncEntry)
                        decodedImageBgraCache[compositeKey] = WeakReference(syncEntry)
                    }
                    ownerWidget?.attributes?.set("activeBgraEntry", syncEntry)
                    ownerWidget?.attributes?.set("activeBgraUrl", url)
                    ownerWidget?.attributes?.set("activeBgraFilter", cssFilter ?: CssFilter.NONE)
                    ownerWidget?.attributes?.set("activeBgraBg", surfaceBgColorRef)
                    return syncEntry
                }
            }
            val shouldStartComposite = withImageCacheLock {
                putStrongRawImageLruLocked(url, rawBuffer)
                if (compositeKey in inFlightCompositeKeys) {
                    false
                } else {
                    inFlightCompositeKeys.add(compositeKey)
                    true
                }
            }
            if (shouldStartComposite) {
                imageFetchScope.launch(Dispatchers.Default) {
                    val entry = runCatching {
                        buildCompositeBgraFromRawBuffer(
                            rawBuffer = rawBuffer,
                            dstW = dstW,
                            dstH = dstH,
                            antialiasing = antialiasing,
                            cssFilter = cssFilter,
                            isDarkTheme = isDarkTheme,
                            surfaceBgColorRef = surfaceBgColorRef
                        )
                    }.getOrNull()
                    withImageCacheLock {
                        inFlightCompositeKeys.remove(compositeKey)
                        if (entry != null) {
                            if (decodedImageBgraCache.size > 512) {
                                val deadKeys = decodedImageBgraCache.entries.filter { it.value.get() == null }.map { it.key }
                                for (k in deadKeys) decodedImageBgraCache.remove(k)
                            }
                            putStrongCompositeBgraLruLocked(compositeKey, entry)
                            decodedImageBgraCache[compositeKey] = WeakReference(entry)
                        }
                    }
                    if (entry != null) {
                        ownerWidget?.attributes?.set("activeBgraEntry", entry)
                        ownerWidget?.attributes?.set("activeBgraUrl", url)
                        ownerWidget?.attributes?.set("activeBgraFilter", cssFilter ?: CssFilter.NONE)
                        ownerWidget?.attributes?.set("activeBgraBg", surfaceBgColorRef)
                        ownerWidget?.notifyTreeInvalidated()
                        Win32NativeWindowHost.markNonOverlayUiDirty(hwnd)
                    }
                }
            }
            return null
        }

        // Check if should launch non-blocking background coroutine fetch
        val shouldStartFetch = withImageCacheLock {
            if (url in failedImageUrls || url in inFlightImageUrls) {
                false
            } else if (url.startsWith("/") && !url.startsWith("//")) {
                failedImageUrls.add(url)
                false
            } else {
                inFlightImageUrls.add(url)
                true
            }
        }

        if (shouldStartFetch) {
            imageFetchScope.launch(Dispatchers.Default) {
                val decoded = imageFetchSemaphore.withPermit {
                    runCatching {
                        val bytes = when {
                            url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) -> {
                                ImageCacheManager.cacheDirectory.getCachedBytes(url)?.takeIf { it.isNotEmpty() }
                                    ?: sharedHttpClient.execute(HttpRequest(url = url, timeoutMs = 6000L)).bodyBytes
                                        .takeIf { it.isNotEmpty() }
                                        ?.also { fetched ->
                                            runCatching { ImageCacheManager.cacheDirectory.putCachedBytes(url, fetched) }
                                        }
                            }
                            else -> ImageCacheManager.fetchAndCacheBytes(url)
                        }
                        if (bytes != null && bytes.isNotEmpty()) {
                            sharedWicImageCodec.decode(ImageSource.fromBytes(bytes), ImageDecodeOptions(antialiasing = antialiasing))
                                ?: ImageLoader.loadFromBytes(bytes, ImageDecodeOptions(antialiasing = antialiasing))
                        } else {
                            null
                        }
                    }.getOrNull()
                }

                if (decoded != null && decoded.width > 0 && decoded.height > 0) {
                    val rgbaBuf = decoded.toRgba8888()
                    val entry = runCatching {
                        buildCompositeBgraFromRawBuffer(
                            rawBuffer = rgbaBuf,
                            dstW = dstW,
                            dstH = dstH,
                            antialiasing = antialiasing,
                            cssFilter = cssFilter,
                            isDarkTheme = isDarkTheme,
                            surfaceBgColorRef = surfaceBgColorRef
                        )
                    }.getOrNull()
                    ownerWidget?.attributes?.set("activeRawBuffer", rgbaBuf)
                    if (entry != null) {
                        ownerWidget?.attributes?.set("activeBgraEntry", entry)
                        ownerWidget?.attributes?.set("activeBgraUrl", url)
                    }
                    ownerWidget?.notifyTreeInvalidated()
                    ImageLoader.trackWeakImageReference(url, rgbaBuf)
                    ImageCacheManager.putInCache(url, rgbaBuf)
                    withImageCacheLock {
                        if (rawImageBufferCache.size > 512) {
                            val deadKeys = rawImageBufferCache.entries.filter { it.value.get() == null }.map { it.key }
                            for (k in deadKeys) rawImageBufferCache.remove(k)
                        }
                        putStrongRawImageLruLocked(url, rgbaBuf)
                        rawImageBufferCache[url] = WeakReference(rgbaBuf)
                        if (entry != null) {
                            putStrongCompositeBgraLruLocked(compositeKey, entry)
                            decodedImageBgraCache[compositeKey] = WeakReference(entry)
                        }
                        inFlightImageUrls.remove(url)
                    }
                    Win32NativeWindowHost.markNonOverlayUiDirty(hwnd)
                } else {
                    withImageCacheLock {
                        inFlightImageUrls.remove(url)
                        failedImageUrls.add(url)
                    }
                }
            }
        }

        return null
    }

    private fun isDarkMonocolorOnTransparent(rgba: ByteArray, width: Int, height: Int): Boolean {
        val total = width * height * 4
        if (total <= 0 || rgba.size < total) return false
        var transparentCount = 0
        var visibleCount = 0
        var darkVisibleCount = 0
        var brightVisibleCount = 0
        var i = 0
        while (i < total) {
            val a = rgba[i + 3].toInt() and 0xFF
            if (a < 48) {
                transparentCount++
            } else {
                visibleCount++
                val r = rgba[i].toInt() and 0xFF
                val g = rgba[i + 1].toInt() and 0xFF
                val b = rgba[i + 2].toInt() and 0xFF
                if (r < 55 && g < 55 && b < 60) {
                    darkVisibleCount++
                } else if (r > 95 || g > 95 || b > 95) {
                    brightVisibleCount++
                }
            }
            i += 4
        }
        return transparentCount > (width * height) / 10 &&
            visibleCount > 0 &&
            brightVisibleCount == 0 &&
            darkVisibleCount * 10 >= visibleCount * 8
    }

    private fun rgbaToCompositeBgra(rgba: ByteArray, width: Int, height: Int, bgColorRef: UInt): ByteArray {
        val total = width * height * 4
        val out = ByteArray(total)
        val bgR = (bgColorRef and 0xFFu).toInt()
        val bgG = ((bgColorRef shr 8) and 0xFFu).toInt()
        val bgB = ((bgColorRef shr 16) and 0xFFu).toInt()
        var i = 0
        while (i + 3 < total && i + 3 < rgba.size) {
            val r = rgba[i].toInt() and 0xFF
            val g = rgba[i + 1].toInt() and 0xFF
            val b = rgba[i + 2].toInt() and 0xFF
            val a = rgba[i + 3].toInt() and 0xFF
            val invA = 255 - a
            out[i] = ((b * a + bgB * invA) / 255).toByte()
            out[i + 1] = ((g * a + bgG * invA) / 255).toByte()
            out[i + 2] = ((r * a + bgR * invA) / 255).toByte()
            out[i + 3] = 0xFF.toByte()
            i += 4
        }
        return out
    }

    private fun hasOverlayRole(w: DesktopNativeWidget, role: String): Boolean =
        w.attributes[DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE] == role

    private fun isExpandingSliderPillWidget(w: DesktopNativeWidget): Boolean {
        return hasOverlayRole(w, DesktopStageOverlaySpec.ROLE_EXPANDING_SLIDER_PILL) ||
            w.attributes.containsKey("data-expanded") ||
            w.attributes.containsKey("data-animated-slider-width-px")
    }

    private fun isOverlayPopoverWidget(widget: DesktopNativeWidget): Boolean {
        return hasOverlayRole(widget, DesktopStageOverlaySpec.ROLE_POPOVER_PANEL) ||
            hasOverlayRole(widget, DesktopStageOverlaySpec.ROLE_COMPACT_POPOVER_MENU) ||
            hasOverlayRole(widget, DesktopStageOverlaySpec.ROLE_INLINE_SLIDER_POPOVER) ||
            widget.attributes["data-overlay-popover"] == "true"
    }

    private fun isZeroFrameStageCard(
        node: DesktopNativeWidget,
        titleBarWidgets: Set<DesktopNativeWidget>
    ): Boolean {
        if (node.attributes["data-zero-frame-card"] == "true") return true
        val pad = node.resolveCssValue("padding")?.trim()?.lowercase()
        val border = node.resolveCssValue("border")?.trim()?.lowercase()
        if ((pad == "0" || pad == "0px") && (border == "none" || border == "0")) return true
        return containsStageWithOverlay(node, titleBarWidgets)
    }

    private fun isHorizontalRowContainer(widget: DesktopNativeWidget): Boolean {
        val tag = widgetTag(widget)
        if (tag.contains("hstack") || Win32NativeWindowHost.widgetHasClass(widget, "hstack")) return true
        if (Win32NativeWindowHost.widgetHasClass(widget, "row") || Win32NativeWindowHost.widgetHasClass(widget, "flex-row")) return true
        if ((Win32NativeWindowHost.widgetHasClass(widget, "d-flex") || Win32NativeWindowHost.widgetHasClass(widget, "d-inline-flex")) &&
            !Win32NativeWindowHost.widgetHasClass(widget, "flex-column")
        ) return true
        if (hasOverlayRole(widget, DesktopStageOverlaySpec.ROLE_CONTROLS_ROW) ||
            Win32NativeWindowHost.widgetHasClass(widget, "settings-row")
        ) return true
        val flexDir = widget.resolveCssValue("flex-direction")?.lowercase()
        if (flexDir == "row") return true
        return false
    }

    private fun isOverlayHudWidget(widget: DesktopNativeWidget): Boolean {
        return widget.stageOverlaySpec != null ||
            widget.attributes["data-overlay-root"] == "true" ||
            widget.attributes["data-overlay-layer"] == "true" ||
            Win32NativeWindowHost.widgetHasClass(widget, "gui-overlay")
    }

    private fun findStageAndOverlay(
        container: DesktopNativeWidget,
        titleBarWidgets: Set<DesktopNativeWidget>
    ): Pair<DesktopNativeWidget, DesktopNativeWidget>? {
        val children = visibleChildrenOf(container, titleBarWidgets)
        if (children.size < 2) return null
        val overlayChild = children.firstOrNull { isOverlayHudWidget(it) } ?: return null
        val stageChild = children.firstOrNull { child ->
            child !== overlayChild && isInteractiveSurfaceWidget(child)
        } ?: return null
        return stageChild to overlayChild
    }

    private fun hasVideoAndOverlay(
        container: DesktopNativeWidget,
        titleBarWidgets: Set<DesktopNativeWidget>
    ): Boolean = findStageAndOverlay(container, titleBarWidgets) != null

    private fun containsStageWithOverlay(
        widget: DesktopNativeWidget,
        titleBarWidgets: Set<DesktopNativeWidget>
    ): Boolean {
        if (findStageAndOverlay(widget, titleBarWidgets) != null) return true
        for (child in visibleChildrenOf(widget, titleBarWidgets)) {
            if (findStageAndOverlay(child, titleBarWidgets) != null) return true
            for (grandChild in visibleChildrenOf(child, titleBarWidgets)) {
                if (findStageAndOverlay(grandChild, titleBarWidgets) != null) return true
            }
        }
        return false
    }

    private fun findVisibleSettingsPanel(
        root: DesktopNativeWidget,
        titleBarWidgets: Set<DesktopNativeWidget>
    ): DesktopNativeWidget? {
        for (child in visibleChildrenOf(root, titleBarWidgets)) {
            if (isOverlayPopoverWidget(child)) {
                return child
            }
            val nested = findVisibleSettingsPanel(child, titleBarWidgets)
            if (nested != null) return nested
        }
        return null
    }

    private fun isSpacerWidget(widget: DesktopNativeWidget): Boolean {
        val tag = widgetTag(widget)
        if (tag.contains("spacer") || Win32NativeWindowHost.widgetHasClass(widget, "spacer")) return true
        val impl = widget.attributes["nativeImpl"]
        if (impl !is DesktopButtonNativeImpl<*> &&
            impl !is DesktopTextNativeImpl<*> &&
            impl !is DesktopTextInputNativeImpl<*> &&
            impl !is DesktopSelectNativeImpl<*> &&
            impl !is DesktopCheckboxNativeImpl<*> &&
            impl !is DesktopRangeSliderNativeImpl<*> &&
            safeChildrenSnapshot(widget).isEmpty() &&
            !widget.attributes.containsKey("captureBgra") &&
            (widget.resolveCssValue("flex-grow")?.toFloatOrNull() ?: 0f) >= 1f
        ) {
            return true
        }
        return false
    }

    private fun getSpacerWeight(widget: DesktopNativeWidget): Float {
        return (widget.attributes["spacerWeight"] as? Number)?.toFloat()
            ?: widget.resolveCssValue("flex-grow")?.toFloatOrNull()
            ?: 0f
    }

    private fun isFillSpacerWidget(widget: DesktopNativeWidget): Boolean {
        return isSpacerWidget(widget) && getSpacerWeight(widget) > 0f
    }

    private fun getFixedSpacerWidth(widget: DesktopNativeWidget): Int {
        return ((widget.attributes["spacerWidthPx"] as? Number)?.toFloat()
            ?: widget.resolveCssValue("width")?.removeSuffix("px")?.trim()?.toFloatOrNull()
            ?: widget.resolveCssValue("min-width")?.removeSuffix("px")?.trim()?.toFloatOrNull()
            ?: 0f).roundToInt().coerceAtLeast(0)
    }

    private fun getFixedSpacerHeight(widget: DesktopNativeWidget): Int {
        return ((widget.attributes["spacerHeightPx"] as? Number)?.toFloat()
            ?: widget.resolveCssValue("height")?.removeSuffix("px")?.trim()?.toFloatOrNull()
            ?: widget.resolveCssValue("min-height")?.removeSuffix("px")?.trim()?.toFloatOrNull()
            ?: 0f).roundToInt().coerceAtLeast(0)
    }

    private fun isInteractiveSurfaceWidget(widget: DesktopNativeWidget): Boolean {
        return widget.interactiveSurfaceSpec != null ||
            widget.attributes.containsKey("captureBgraPtr") ||
            widget.attributes.containsKey("captureBgra") ||
            widget.attributes.containsKey("captureRgba")
    }

    private fun drawCustomButtonIconIfPresent(
        hwnd: HWND,
        hdc: HDC?,
        theme: Win32NativeWindowHost.SystemThemePalette,
        fontFallback: HFONT?,
        buttonWidget: DesktopNativeWidget,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        surfaceBgColorRef: UInt = theme.bgCard,
        transparentBackground: Boolean = true,
        opacity: Float = 1f
    ): Boolean {
        val imgChild = safeChildrenSnapshot(buttonWidget).firstOrNull {
            it.visible && !it.disposed && it.attributes["nativeImpl"] is DesktopImageNativeImpl<*>
        }
        if (imgChild != null) {
            val imgImpl = imgChild.attributes["nativeImpl"] as DesktopImageNativeImpl<*>
            val url = imgImpl.getSourceUrl()?.trim().orEmpty()
            if (url.isNotEmpty()) {
                val cssFilter = resolveWidgetCssFilter(imgChild, buttonWidget)
                val padX = ((right - left - 18).coerceAtLeast(4)) / 2
                val padY = ((bottom - top - 18).coerceAtLeast(4)) / 2
                return drawCachedOrAsyncImageBox(
                    hwnd = hwnd,
                    hdc = hdc,
                    theme = theme,
                    fontFallback = fontFallback,
                    url = url,
                    placeholderUrl = imgImpl.getPlaceholderUrl()?.trim().takeUnless { it.isNullOrEmpty() }
                        ?: ImageLoader.DEFAULT_PLACEHOLDER_URI,
                    antialiasing = imgImpl.isAntialiasing(),
                    ownerWidget = imgChild,
                    fallbackLabel = "",
                    cssFilter = cssFilter,
                    left = left + padX - 2,
                    top = top + padY - 2,
                    right = right - padX + 2,
                    bottom = bottom - padY + 2,
                    surfaceBgColorRef = surfaceBgColorRef,
                    drawBoxBackground = false,
                    transparentBackground = transparentBackground,
                    opacity = opacity
                )
            }
        }
        return false
    }

    private fun measureButtonWidth(btn: DesktopButtonNativeImpl<*>): Int {
        val label = btn.getText().trim()
        val hasIcon = !btn.getIcon().isNullOrBlank() ||
            safeChildrenSnapshot(btn.nativeWidget).any {
                it.visible && !it.disposed && it.attributes["nativeImpl"] is DesktopImageNativeImpl<*>
            }
        if (label.isEmpty() && hasIcon) return 32
        val textW = max(
            measureSingleLineTextWidth(label, 7),
            Win32NativeWindowHost.measureSingleLineTextWidthWithFont(label, 0)
        )
        return (textW + (if (hasIcon) 32 else 20)).coerceIn(32, 280)
    }

    private fun measureInlineChildWidth(widget: DesktopNativeWidget, availW: Int): Int {
        if (isSpacerWidget(widget)) {
            return if (isFillSpacerWidget(widget)) 0 else getFixedSpacerWidth(widget)
        }
        val impl = widget.attributes["nativeImpl"]
        return when (impl) {
            is DesktopImageNativeImpl<*> -> {
                ((widget.attributes["width"] as? Number)?.toInt()
                    ?: widget.preferredSize?.width
                    ?: 32).coerceIn(16, availW)
            }
            is DesktopButtonNativeImpl<*> -> measureButtonWidth(impl)
            is DesktopTextNativeImpl<*> -> {
                val txt = impl.getText().trim()
                if (txt.isEmpty()) 0
                else if (Win32NativeWindowHost.widgetHasClass(widget, "badge")) {
                    max(
                        measureSingleLineTextWidth(txt, 7),
                        Win32NativeWindowHost.measureSingleLineTextWidthWithFont(txt, 0)
                    ) + 16
                } else {
                    val isHeading = widgetTag(widget).let { it.startsWith("h") && (it.length == 2 || it.contains("heading")) }
                    val w = max(
                        measureSingleLineTextWidth(txt, 7),
                        Win32NativeWindowHost.measureSingleLineTextWidthWithFont(txt, if (isHeading) 1 else 0)
                    )
                    min(availW, w + 8)
                }
            }
            is DesktopTextInputNativeImpl<*> -> min(availW, max(160, availW / 2))
            is DesktopSelectNativeImpl<*> -> {
                val curVal = impl.getSelectedValue() ?: ""
                val curLabel = (impl.getOptions().firstOrNull { it.first == curVal }?.second ?: curVal).ifBlank { "Select..." }
                val w = max(
                    measureSingleLineTextWidth(curLabel, 7),
                    Win32NativeWindowHost.measureSingleLineTextWidthWithFont(curLabel, 0)
                )
                (w + 34).coerceIn(110, 240)
            }
            is DesktopCheckboxNativeImpl<*> -> {
                val lbl = impl.getLabel()
                val w = max(
                    measureSingleLineTextWidth(lbl, 7),
                    Win32NativeWindowHost.measureSingleLineTextWidthWithFont(lbl, 0)
                )
                (w + 28).coerceIn(72, 240)
            }
            is DesktopRangeSliderNativeImpl<*> -> min(availW, 140)
            is DesktopSpinnerNativeImpl<*> -> 120
            else -> {
                if (widget.attributes.containsKey("captureBgra")) {
                    val rawW = (widget.attributes["surfaceWidth"] as? (() -> Int))?.invoke() ?: 160
                    min(availW, max(120, rawW))
                } else {
                    val visChildren = safeChildrenSnapshot(widget).filter { it.visible && !it.disposed }
                    if (visChildren.isNotEmpty()) {
                        if (isHorizontalRowContainer(widget)) {
                            val sum = visChildren.sumOf { c ->
                                if (isSpacerWidget(c)) getFixedSpacerWidth(c) else measureInlineChildWidth(c, availW) + 6
                            }
                            min(availW, max(48, sum))
                        } else {
                            val maxChild = visChildren.maxOfOrNull { measureInlineChildWidth(it, availW) } ?: 160
                            min(availW, max(48, maxChild))
                        }
                    } else if (isHorizontalRowContainer(widget)) {
                        0
                    } else {
                        min(availW, 160)
                    }
                }
            }
        }
    }

    private fun measureSingleLineTextWidth(text: String, avgCharPx: Int): Int {
        var width = 0
        for (ch in text) {
            width += if (ch.code > 0x2000) avgCharPx + 5 else avgCharPx
        }
        return width
    }

    private fun drawWrappedTextInRect(
        hdc: HDC?,
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        colorRef: UInt,
        center: Boolean = false,
        rightAlign: Boolean = false
    ) = memScoped {
        val r = alloc<RECT>()
        r.left = left
        r.top = top
        r.right = right
        r.bottom = bottom
        SetTextColor(hdc, colorRef)
        val alignFlag = when {
            center -> DT_CENTER
            rightAlign -> DT_RIGHT
            else -> DT_LEFT
        }
        val flags = (alignFlag or DT_WORDBREAK or DT_END_ELLIPSIS or DT_NOPREFIX).toUInt()
        DrawTextW(hdc, text, -1, r.ptr, flags)
        Win32NativeWindowHost.markGdiRectDirty(left, top, right, bottom)
    }

    private fun DesktopNativeWidget.resolveCssValue(prop: String): String? {
        val compStyle = (attributes["componentStyle"] as? ComponentStyle)
            ?: (attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>)?.getComponentStyle()
        val fromStyle: String? = when (prop) {
            "width" -> (attributes["styleWidth"] as? CssLength)?.toCssString()
                ?: compStyle?.width?.toCssString()
            "height" -> (attributes["styleHeight"] as? CssLength)?.toCssString()
                ?: compStyle?.height?.toCssString()
            "min-width" -> (attributes["styleMinWidth"] as? CssLength)?.toCssString()
                ?: compStyle?.minWidth?.toCssString()
            "min-height" -> (attributes["styleMinHeight"] as? CssLength)?.toCssString()
                ?: compStyle?.minHeight?.toCssString()
            "max-width" -> (attributes["styleMaxWidth"] as? CssLength)?.toCssString()
                ?: compStyle?.maxWidth?.toCssString()
            "max-height" -> (attributes["styleMaxHeight"] as? CssLength)?.toCssString()
                ?: compStyle?.maxHeight?.toCssString()
            "top" -> (attributes["styleTop"] as? CssLength)?.toCssString()
                ?: compStyle?.top?.toCssString()
            "right" -> (attributes["styleRight"] as? CssLength)?.toCssString()
                ?: compStyle?.right?.toCssString()
            "bottom" -> (attributes["styleBottom"] as? CssLength)?.toCssString()
                ?: compStyle?.bottom?.toCssString()
            "left" -> (attributes["styleLeft"] as? CssLength)?.toCssString()
                ?: compStyle?.left?.toCssString()
            "flex-grow" -> (attributes["flexGrow"] as? Number)?.toString()
                ?: compStyle?.flexGrow?.toString()
            "flex-direction" -> (attributes["flexDirection"] as? String)
                ?: compStyle?.flexDirection?.name?.lowercase()?.replace('_', '-')
            "justify-content" -> (attributes["justifyContent"] as? String)
                ?: compStyle?.justifyContent?.name?.lowercase()?.replace('_', '-')
            "display" -> (attributes["display"] as? String)
                ?: compStyle?.display?.name?.lowercase()?.replace('_', '-')
            "aspect-ratio" -> attributes["aspectRatio"]?.toString()
                ?: compStyle?.aspectRatio?.toString()
            "white-space" -> compStyle?.whiteSpace?.name?.lowercase()?.replace('_', '-')
            "text-align" -> compStyle?.textAlign?.name?.lowercase()
            "border" -> compStyle?.borderWidth?.top?.resolvePx()?.let { if (it <= 0f) "0" else "{it}px" }
            "padding" -> compStyle?.padding?.top?.resolvePx()?.let { if (it <= 0f) "0" else "{it}px" }
            "border-radius" -> (attributes["borderRadiusPx"] as? Number)?.let { "{it.toInt()}px" }
                ?: compStyle?.borderRadius?.topLeft?.toCssString()
            "background-color" -> compStyle?.backgroundColor?.toString()
            "backdrop-filter" -> compStyle?.backdropFilter?.toCssString()
            "pointer-events" -> compStyle?.pointerEvents?.name?.lowercase()
            "filter" -> compStyle?.filter?.toCssString()
            else -> null
        }
        if (fromStyle != null) return fromStyle
        return attributes[prop] as? String
    }

    private fun buildFlowRenderContext(
        hwnd: HWND,
        hdc: HDC?,
        theme: Win32NativeWindowHost.SystemThemePalette,
        fontSmall: HFONT?,
        fontSmallBold: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        fontTitle: HFONT?,
        fontMetricBig: HFONT?,
        titleBarWidgets: Set<DesktopNativeWidget>,
        left: Int,
        top: Int,
        right: Int,
        bottomLimit: Int
    ): Win32RenderContext = Win32RenderContext(
        hwnd = hwnd,
        hdc = hdc,
        bounds = Rect(left, top, (right - left).coerceAtLeast(1), (bottomLimit - top).coerceAtLeast(1)),
        darkMode = theme.isDark,
        fontSmall = fontSmall,
        fontSmallBold = fontSmallBold,
        fontNormal = fontNormal,
        fontBold = fontBold,
        fontTitle = fontTitle,
        fontMetricBig = fontMetricBig,
        titleBarWidgets = titleBarWidgets,
        renderChildCallback = { childCtx, childWidget, cLeft, cTop, cRight ->
            renderFlowNode(
                hwnd = childCtx.hwnd,
                hdc = childCtx.hdc,
                theme = theme,
                fontSmall = childCtx.fontSmall,
                fontSmallBold = childCtx.fontSmallBold,
                fontNormal = childCtx.fontNormal,
                fontBold = childCtx.fontBold,
                fontTitle = childCtx.fontTitle,
                fontMetricBig = childCtx.fontMetricBig,
                node = childWidget,
                titleBarWidgets = childCtx.titleBarWidgets,
                left = cLeft,
                top = cTop,
                right = cRight,
                bottomLimit = bottomLimit
            )
        },
        measureInlineChildWidthCallback = { _, childWidget, cAvailW ->
            measureInlineChildWidth(childWidget, cAvailW)
        },
        drawImageBoxCallback = { childCtx, imgWidget, url, placeholderUrl, antialiasing, fallbackLabel, iLeft, iTop, iRight, iBottom, bgColorRef ->
            drawCachedOrAsyncImageBox(
                hwnd = childCtx.hwnd,
                hdc = childCtx.hdc,
                theme = theme,
                fontFallback = childCtx.fontSmallBold ?: childCtx.fontBold,
                url = url,
                placeholderUrl = placeholderUrl,
                antialiasing = antialiasing,
                ownerWidget = imgWidget,
                fallbackLabel = fallbackLabel,
                cssFilter = resolveWidgetCssFilter(imgWidget, null),
                left = iLeft,
                top = iTop,
                right = iRight,
                bottom = iBottom,
                surfaceBgColorRef = bgColorRef
            )
        }
    )
}
