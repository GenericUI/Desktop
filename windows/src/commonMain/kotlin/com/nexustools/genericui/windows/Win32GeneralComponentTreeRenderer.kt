@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.dialog.DesktopDialogWindowNativeImpl
import com.nexustools.genericui.desktop.lazy.DesktopLazyListNativeImpl
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
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.image.ImageDecodeOptions
import com.nexustools.genericui.image.ImageLoader
import com.nexustools.genericui.image.ImageSource
import com.nexustools.genericui.image.cache.ImageCacheManager
import com.nexustools.genericui.net.http.HttpRequest
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.gradient.Gradient.Gradient
import com.nexustools.genericui.util.WeakReference
import kotlinx.cinterop.addressOf
import platform.windows.DT_CENTER
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
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
import platform.windows.DT_END_ELLIPSIS
import platform.windows.DT_LEFT
import platform.windows.DT_NOPREFIX
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

/**
 * Recursive Win32 GDI layout and rendering engine for general non-studio `GenericUI` applications
 * (`Navbar`, `CollapsibleBottomDockLayout`, `HybridColumnLayout`, `HybridSplitNavigationLayout`,
 * `TabContainer`, `BottomNavigationBar`, `Card`, `Alert`, `VStack`, `HStack`, `ScrollView`,
 * `LazyList`/`LazyColumn`, `VideoPlayer`, inline `Canvas2D`/`GLCanvas`, and all form/typography controls).
 */
internal object Win32GeneralComponentTreeRenderer {

    private class CompositeBgraEntry(
        val bgra: ByteArray,
        val width: Int,
        val height: Int
    )

    // Scroll state keyed by container identity hash
    private val scrollOffsetsY = mutableMapOf<Int, Int>()
    private val maxScrollOffsetsY = mutableMapOf<Int, Int>()
    // Active scrollable regions in current frame: [left, top, right, bottom, containerKey]
    private val activeScrollRegions = mutableListOf<IntArray>()
    private var currentClipTop: Int = 0
    private var currentClipBottom: Int = 10000
    private var cachedVideoWidget: DesktopNativeWidget? = null
    private var cachedOverlayWidget: DesktopNativeWidget? = null
    private var cachedTitleBarWidgets: Set<DesktopNativeWidget> = emptySet()
    private var cachedVideoFullViewportMode: Boolean = false

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
        val vr = Win32NativeWindowHost.lastRenderedVideoStageRect
        if (vr[2] <= vr[0] || vr[3] <= vr[1]) return false
        Win32NativeWindowHost.removeHitRegionsInRect(vr[0], vr[1], vr[2], vr[3])
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
                otherChildren = emptyList(),
                titleBarWidgets = cachedTitleBarWidgets,
                left = vr[0],
                top = vr[1],
                right = vr[2],
                bottomLimit = vr[3],
                fullViewportMode = cachedVideoFullViewportMode
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
        if (rootWidget == null || !rootWidget.visible || rootWidget.disposed) return

        val fsWidget = Win32NativeWindowHost.activeFullscreenElementWidget
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
            } else if (isVideoPlayerWidget(fsWidget) ||
                fsWidget.attributes.containsKey("captureBgra") ||
                fsWidget.attributes.containsKey("captureRgba")
            ) {
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
        if (!widget.visible || widget.disposed || isSpacerWidget(widget)) return 0
        return when (val impl = widget.attributes["nativeImpl"]) {
            is DesktopTextNativeImpl<*> -> {
                val txt = impl.getText().trim()
                if (txt.isEmpty()) 0 else min(maxAvailW, measureSingleLineTextWidth(txt, 7) + 16)
            }
            is DesktopButtonNativeImpl<*> -> measureButtonWidth(impl)
            is DesktopTextInputNativeImpl<*> -> min(maxAvailW, 260)
            is DesktopSelectNativeImpl<*> -> {
                val curVal = impl.getSelectedValue() ?: ""
                val maxOpt = impl.getOptions().maxOfOrNull { measureSingleLineTextWidth(it.second, 7) }
                    ?: measureSingleLineTextWidth(curVal, 7)
                (maxOpt + 40).coerceIn(120, maxAvailW)
            }
            is DesktopCheckboxNativeImpl<*> -> (measureSingleLineTextWidth(impl.getLabel(), 7) + 32).coerceIn(80, maxAvailW)
            else -> {
                val kids = visibleChildrenOf(widget, emptySet())
                if (kids.isEmpty()) {
                    0
                } else if (isHorizontalRowContainer(widget)) {
                    val sum = kids.sumOf { measureInlineChildWidth(it, maxAvailW) + 6 }
                    min(maxAvailW, sum)
                } else {
                    kids.maxOfOrNull { measureDialogChildIntrinsicWidth(it, maxAvailW) } ?: 0
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
        val titleStr = (dialogWidget.attributes["title"] as? String)
            ?: (dialogWidget.attributes["nativeImpl"] as? DesktopDialogWindowNativeImpl<*>)?.getTitle()
            ?: ""
        val headerIntrinsicW = measureSingleLineTextWidth(titleStr, 8) + 36 + 48
        val maxBodyAvailW = (maxViewportWidth - 60).coerceAtLeast(220)
        val bodyIntrinsicW = (bodyChildren.maxOfOrNull { measureDialogChildIntrinsicWidth(it, maxBodyAvailW) } ?: 180) + 28
        val footerChildren = if (footerWidget != null) visibleChildrenOf(footerWidget, emptySet()) else emptyList()
        val footerIntrinsicW = if (footerChildren.isNotEmpty()) {
            footerChildren.sumOf { measureInlineChildWidth(it, maxBodyAvailW) } +
                (footerChildren.size - 1).coerceAtLeast(0) * 8 + 28
        } else {
            0
        }
        val intrinsicW = maxOf(headerIntrinsicW, bodyIntrinsicW, footerIntrinsicW)
            .coerceIn(220, (maxViewportWidth - 32).coerceAtLeast(260))
        val measuredBodyH = estimateVerticalStackHeight(
            container = dialogWidget,
            children = bodyChildren,
            titleBarWidgets = emptySet(),
            availW = (intrinsicW - 28).coerceAtLeast(160)
        ) + 20
        val intrinsicH = (measuredBodyH + 96)
            .coerceIn(140, (maxViewportHeight - 48).coerceAtLeast(220))
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
        val snapshot = safeChildrenSnapshot(widget)
        return snapshot.filter { child ->
            child.visible &&
                !child.disposed &&
                !Win32NativeWindowHost.isWidgetMountedInTitleBar(child, titleBarWidgets) &&
                child.attributes["nativeImpl"] !is DesktopDialogWindowNativeImpl<*> &&
                !Win32NativeWindowHost.widgetHasClass(child, "episodecaster-modal-host")
        }
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
                val isLeafControl = singleImpl is DesktopButtonNativeImpl<*> ||
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

        // Check if this container has a top Navbar + main body (like AbstractPlayerFragment: navbar + bottomDockLayout)
        val navbarChild = children.firstOrNull {
            it.attributes["nativeImpl"] is DesktopNavbarNativeImpl<*> ||
                Win32NativeWindowHost.widgetHasClass(it, "navbar") ||
                Win32NativeWindowHost.widgetHasClass(it, "episodecaster-navbar") ||
                widgetTag(it).contains("navbar", ignoreCase = true)
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
        // allocate the full remaining viewport to it (and reserve space for any status footer if visible).
        val dockOrHybrid = remainingChildren.firstOrNull {
            Win32NativeWindowHost.widgetHasClass(it, "gui-collapsible-bottom-dock") ||
                Win32NativeWindowHost.widgetHasClass(it, "hybrid-column-layout") ||
                Win32NativeWindowHost.widgetHasClass(it, "gui-hybrid-split-navigation") ||
                Win32NativeWindowHost.widgetHasClass(it, "fragment-stage") ||
                Win32NativeWindowHost.widgetHasClass(it, "gui-layered-container")
        }
        if (dockOrHybrid != null) {
            val otherChildren = remainingChildren.filter { it !== dockOrHybrid }
            var mainBottom = curBottom
            if (otherChildren.isNotEmpty()) {
                val footerH = 26 * otherChildren.size
                mainBottom = max(curTop + 100, curBottom - footerH)
                var fy = mainBottom
                for (footer in otherChildren) {
                    renderFlowNode(
                        hwnd, hdc, theme, fontSmall, fontSmallBold, fontNormal, fontBold, fontTitle, fontMetricBig,
                        footer, titleBarWidgets, left + 12, fy, right - 12, curBottom
                    )
                    fy += 26
                }
            }
            renderRootContainer(
                hwnd, hdc, theme, fontSmall, fontSmallBold, fontNormal, fontBold, fontTitle, fontMetricBig,
                dockOrHybrid, titleBarWidgets, left, curTop, right, mainBottom
            )
            return
        }

        // If node itself is a VideoPlayer / Visualizer stage with overlaid HUD, render it directly
        val directStageAndOverlay = findStageAndOverlay(node, titleBarWidgets)
        if (directStageAndOverlay != null) {
            val (stageWidget, overlayWidget) = directStageAndOverlay
            val otherChildren = visibleChildrenOf(node, titleBarWidgets).filter {
                it !== stageWidget && it !== overlayWidget
            }
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
                left = left + 12,
                top = curTop + 10,
                right = right - 12,
                bottomLimit = curBottom
            )
            return
        }

        // Check if this is a full-column player Card (e.g. EpisodeFragment / MusicFragment left column)
        val isNodeItselfCard = Win32NativeWindowHost.widgetHasClass(node, "card") || widgetTag(node).contains("card")
        val isPlayerOrFullWidthCard = Win32NativeWindowHost.widgetHasClass(node, "episode-player-card") ||
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
            val rawMaxWStr = node.styleProperties["max-width"]
                ?: cardNodes.firstOrNull()?.styleProperties?.get("max-width")
                ?: cardNodes.firstOrNull()?.let { visibleChildrenOf(it, titleBarWidgets).firstOrNull()?.styleProperties?.get("max-width") }
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

        // Check if this container starts with a TabContainer (like rightTabbedColumn in MusicFragment)
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
            val bodyList = remainingChildren.drop(1)
            if (bodyList.isNotEmpty()) {
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
                    children = bodyList,
                    titleBarWidgets = titleBarWidgets,
                    left = left,
                    top = afterTabTop,
                    right = right,
                    bottom = curBottom,
                    padX = 12,
                    padY = 10
                )
            }
            return
        }

        // Check if this container ends with a bottom TabContainer (portrait small layout with tabs on bottom)
        val lastChild = remainingChildren.last()
        if (remainingChildren.size > 1 && lastChild.attributes["nativeImpl"] is DesktopTabContainerNativeImpl<*>) {
            val tabH = 38
            val tabTop = max(curTop, curBottom - tabH)
            val bodyList = remainingChildren.dropLast(1)
            if (bodyList.isNotEmpty()) {
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
                    children = bodyList,
                    titleBarWidgets = titleBarWidgets,
                    left = left,
                    top = curTop,
                    right = right,
                    bottom = tabTop,
                    padX = 12,
                    padY = 10
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
                val weights = cols.map { col ->
                    val impl = col.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
                    val growStr = col.styleProperties["flex-grow"]
                        ?: impl?.nativeWidget?.styleProperties?.get("flex-grow")
                    growStr?.toFloatOrNull()?.coerceAtLeast(0.2f) ?: 1f
                }
                val totalWeight = weights.sum().coerceAtLeast(0.1f)
                val totalW = (right - left).coerceAtLeast(1)
                var curX = left
                for (i in cols.indices) {
                    val colWidget = cols[i]
                    val colRight = if (i == cols.lastIndex) {
                        right
                    } else {
                        (curX + (totalW * (weights[i] / totalWeight)).roundToInt()).coerceIn(curX + 120, right - 120)
                    }
                    val unwrappedCol = unwrapShellContainers(colWidget, titleBarWidgets)
                    renderRootContainer(
                        hwnd, hdc, theme, fontSmall, fontSmallBold, fontNormal, fontBold, fontTitle, fontMetricBig,
                        unwrappedCol, titleBarWidgets, curX, top, colRight, bottom
                    )
                    if (i < cols.lastIndex) {
                        Win32NativeWindowHost.drawLineColor(hdc, colRight, top, colRight, bottom, theme.borderSubtle)
                    }
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
                Win32NativeWindowHost.fillRectColor(hdc, left, curTop, right, slotBottom, theme.bgPanel)
                Win32NativeWindowHost.drawLineColor(hdc, left, slotBottom, right, slotBottom, theme.borderSubtle)
                var hy = curTop + 6
                for (hc in headerChildren) {
                    hy = renderFlowNode(
                        hwnd, hdc, theme, fontSmall, fontSmallBold, fontNormal, fontBold, fontTitle, fontMetricBig,
                        hc, titleBarWidgets, left + 10, hy, right - 10, slotBottom
                    )
                }
                curTop = slotBottom
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

        for (item in items) {
            if (isSpacerWidget(item)) {
                seenSpacer = true
                continue
            }
            val itemImpl = item.attributes["nativeImpl"]
            if (seenSpacer || itemImpl is DesktopButtonNativeImpl<*> || itemImpl is DesktopSelectNativeImpl<*>) {
                buttonsRightToLeft.add(item)
            } else {
                leftItems.add(item)
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
                        val bw = drawBadgePill(hdc, theme, fontSmall, item, txt, leftX, (top + bottom - 22) / 2)
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

        // Draw action buttons / badges on the right side of the navbar (left-to-right from the right edge)
        val totalActionsW = buttonsRightToLeft.sumOf { item ->
            val impl = item.attributes["nativeImpl"]
            when (impl) {
                is DesktopButtonNativeImpl<*> -> measureButtonWidth(impl) + 6
                is DesktopTextNativeImpl<*> -> {
                    val t = impl.getText().trim()
                    if (t.isEmpty()) 0 else measureSingleLineTextWidth(t, 7) + 22
                }
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
                    val bw = measureButtonWidth(impl)
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
                        val bw = drawBadgePill(hdc, theme, fontSmall, item, txt, curX, (top + bottom - 22) / 2)
                        curX += bw + 6
                    }
                }
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
        Win32NativeWindowHost.fillRectColor(hdc, left, top, right, bottom, theme.bgPanel)
        Win32NativeWindowHost.drawLineColor(hdc, left, bottom - 1, right, bottom - 1, theme.borderSubtle)

        val tabContainerImpl = tabContainerWidget.attributes["nativeImpl"] as? DesktopTabContainerNativeImpl<*>
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
        val availW = (right - left - 16).coerceAtLeast(80)
        val equalTabW = (availW / titles.size).coerceIn(72, 180)

        var tx = left + 8
        for (idx in titles.indices) {
            val title = titles[idx]
            val isActive = idx == activeIdx
            val tabW = max(equalTabW, min(200, measureSingleLineTextWidth(title, 7) + 26))
            val tabRight = min(right - 4, tx + tabW)
            if (tabRight <= tx + 24) break

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

            SelectObject(hdc, if (isActive) fontBold else fontSmall)
            Win32NativeWindowHost.drawTextInRect(
                hdc = hdc,
                text = title,
                left = tx + 8,
                top = top + 4,
                right = tabRight - 8,
                bottom = bottom - 2,
                colorRef = if (isActive) theme.accentBlueText else theme.textSecondary,
                center = true
            )

            val capturedIdx = idx
            val tabImpl = orderedTabImpls.getOrNull(idx) as? DesktopTabNativeImpl<*>
            val tabWidget = (tabImpl?.nativeWidget as? DesktopNativeWidget)
                ?: tabWidgets.getOrNull(idx)
                ?: tabContainerWidget
            val hitL = tx
            val hitR = tabRight
            Win32NativeWindowHost.addHitRegion(
                Win32NativeWindowHost.HitRegion(
                    left = hitL,
                    top = top + 4,
                    right = hitR,
                    bottom = bottom,
                    tooltip = nonRedundantTooltip(tabImpl?.getTooltipText(), title),
                    widget = tabWidget,
                    onClick = { _, _ ->
                        tabContainerImpl?.requestSelectTab(capturedIdx)
                        (tabImpl ?: (tabWidget.attributes["nativeImpl"] as? DesktopTabNativeImpl<*>))?.dispatchClick()
                    }
                )
            )
            tx = tabRight + 4
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
            val estH = estimateFlowNodeHeight(child, titleBarWidgets, availW).coerceAtLeast(20)
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
                curY = max(curY + estH, renderedBottom) + 6
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
            val tabH = 36
            renderTabBarStrip(hwnd, hdc, theme, fontSmall, fontBold, node, left, top, right, top + tabH)
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

        // 2. VideoPlayer stage
        if (isVideoPlayerWidget(node)) {
            val stageH = ((availW * 9) / 16).coerceIn(180, 380)
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

        // 3. Embedded Canvas2D / GLCanvas surface (e.g. miniSpectrumCanvas or MilkDrop GLCanvas)
        if (node.attributes.containsKey("captureBgra") || node.attributes.containsKey("captureRgba")) {
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
                val imgW = ((node.attributes["width"] as? Number)?.toInt() ?: 48).coerceIn(16, availW)
                val imgH = ((node.attributes["height"] as? Number)?.toInt() ?: 48).coerceIn(16, 240)
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
                val font = when {
                    tag == "h1" || tag == "h2" -> fontMetricBig ?: fontTitle
                    tag == "h3" || tag == "h4" -> fontTitle ?: fontBold
                    isHeading -> fontBold
                    else -> fontNormal
                }
                val fgColor = resolveContrastSafeTextColor(impl, theme, isHeading)
                SelectObject(hdc, font)
                val lineH = when {
                    tag == "h1" || tag == "h2" -> 30
                    tag == "h3" || tag == "h4" -> 24
                    else -> 20
                }
                val charsPerLine = max(18, availW / 7)
                val lineCount = ((txt.length + charsPerLine - 1) / charsPerLine).coerceIn(1, 6)
                val boxH = lineCount * lineH
                val isCentered = Win32NativeWindowHost.widgetHasClass(node, "text-center") ||
                    Win32NativeWindowHost.widgetHasClass(node, "capabilities-info") ||
                    node.attributes["id"] == "capabilities-info" ||
                    node.styleProperties["text-align"]?.lowercase() == "center"
                if (lineCount == 1) {
                    Win32NativeWindowHost.drawTextInRect(hdc, txt, left, top, right, top + boxH, fgColor, center = isCentered)
                } else {
                    drawWrappedTextInRect(hdc, txt, left, top, right, top + boxH, fgColor, center = isCentered)
                }
                return top + boxH
            }

            is DesktopButtonNativeImpl<*> -> {
                val btnH = 28
                val isFullWidthBtn = Win32NativeWindowHost.widgetHasClass(node, "w-100") ||
                    node.styleProperties["width"] == "100%" ||
                    impl.resolveBootstrapWidthFraction() == 1.0f
                val bw = if (isFullWidthBtn) availW else min(availW, max(96, measureButtonWidth(impl)))
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

        // 5b. Video Player / Visualizer Stage with Overlaid HUD (`primaryVideoStack` / `.episodecaster-player-stage`)
        val stageAndOverlay = findStageAndOverlay(node, titleBarWidgets)
        if (stageAndOverlay != null) {
            val (videoChild, overlayChild) = stageAndOverlay
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
                bottomLimit = bottomLimit
            )
        }

        // 5c. General LayeredContainer (`.gui-layered-container` / `.gui-layered-layout`, e.g. Queue + Binging Mode Overlay)
        if (
            Win32NativeWindowHost.widgetHasClass(node, "gui-layered-container") ||
            Win32NativeWindowHost.widgetHasClass(node, "gui-layered-layout")
        ) {
            val baseLayer = children.first()
            val baseBottom = renderFlowNode(
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
            val layerBottom = min(bottomLimit, max(baseBottom, currentClipBottom))
            for (overlayLayer in children.drop(1)) {
                val layerVisuals = (overlayLayer.attributes["nativeImpl"] as? DesktopStyledContainerNativeImpl<*>)?.getStyledVisuals()
                val isBingingLock = Win32NativeWindowHost.widgetHasClass(overlayLayer, "binging-mode-queue-overlay")
                val fillCol = layerVisuals?.backgroundColor
                    ?: if (isBingingLock) {
                        if (theme.isDark) RgbaColor(14, 14, 18, 210) else RgbaColor(245, 245, 248, 218)
                    } else null
                if (fillCol != null || layerVisuals?.borderColor != null || layerVisuals?.backgroundGradient != null) {
                    Win32NativeWindowHost.drawAntialiasedRoundedRectRgba(
                        hdc = hdc,
                        left = left,
                        top = top,
                        right = right,
                        bottom = layerBottom,
                        radiusPx = layerVisuals?.borderRadiusPx ?: 8f,
                        fillColor = fillCol,
                        borderColor = layerVisuals?.borderColor,
                        borderWidthPx = layerVisuals?.borderWidthPx ?: 0f,
                        opacity = layerVisuals?.opacity ?: overlayLayer.opacity,
                        gradient = layerVisuals?.backgroundGradient
                    )
                }
                val overlayTxt = Win32NativeWindowHost.extractWidgetText(overlayLayer).trim()
                if (overlayTxt.isNotEmpty() && isBingingLock) {
                    SelectObject(hdc, fontBold ?: fontNormal)
                    val fg = layerVisuals?.textColor?.let { Win32NativeWindowHost.rgbaToColorRef(it) } ?: theme.textPrimary
                    Win32NativeWindowHost.drawTextInRect(
                        hdc = hdc,
                        text = overlayTxt,
                        left = left + 12,
                        top = top,
                        right = right - 12,
                        bottom = layerBottom,
                        colorRef = fg,
                        center = true
                    )
                    Win32NativeWindowHost.addHitRegion(
                        Win32NativeWindowHost.HitRegion(
                            left = left,
                            top = top,
                            right = right,
                            bottom = layerBottom,
                            widget = overlayLayer
                        )
                    )
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
                        node = overlayLayer,
                        titleBarWidgets = titleBarWidgets,
                        left = left,
                        top = top,
                        right = right,
                        bottomLimit = layerBottom
                    )
                }
            }
            return baseBottom
        }

        // 6. Card container (`Card`)
        val isCard = Win32NativeWindowHost.widgetHasClass(node, "card") || tag.contains("card")
        if (isCard) {
            val fullW = (right - left).coerceAtLeast(120)
            val isEpisodePlayerCard = Win32NativeWindowHost.widgetHasClass(node, "episode-player-card")
            val isBorderlessCard = isEpisodePlayerCard ||
                node.styleProperties["border"] == "none" ||
                node.styleProperties["border"] == "0"
            val isPlayerOrFullWidthCard = isEpisodePlayerCard ||
                Win32NativeWindowHost.widgetHasClass(node, "w-100") ||
                children.any { hasVideoAndOverlay(it, titleBarWidgets) } ||
                containsStageWithOverlay(node, titleBarWidgets)
            val isCenteredDialogCard = !isPlayerOrFullWidthCard && fullW > 560 && top < 140
            val cardW = if (isCenteredDialogCard) 500 else fullW
            val cardLeft = if (isCenteredDialogCard) left + (fullW - cardW) / 2 else left
            val cardRight = cardLeft + cardW
            val cardTop = if (isCenteredDialogCard && top < 48) top + 32 else top

            val cardPadX = if (isEpisodePlayerCard) 0 else if (isPlayerOrFullWidthCard) 14 else 24
            val cardPadY = if (isEpisodePlayerCard) 0 else if (isPlayerOrFullWidthCard) 14 else 20
            val estimatedH = estimateVerticalStackHeight(node, children, titleBarWidgets, cardW - cardPadX * 2) + cardPadY * 2 + 8
            val cardBottom = cardTop + max(56, estimatedH)
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
                if (isButtonBar && !isEpisodePlayerCard) {
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
                    } else if (!isEpisodePlayerCard) {
                        cy += 6
                    }
                }
            }
            return max(cardBottom, cy + if (isEpisodePlayerCard) 0 else 8)
        }

        val styledContainerVisuals = (impl as? DesktopStyledContainerNativeImpl<*>)?.getStyledVisuals()

        // 7. Horizontal Row (`HStack`, `.hstack`, `.row`, or `flex-direction: row`)
        if (isHorizontalRowContainer(node)) {
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
        if (styledContainerVisuals != null && (styledContainerVisuals.backgroundColor != null || styledContainerVisuals.backgroundGradient != null || styledContainerVisuals.borderColor != null)) {
            val estStackH = estimateVerticalStackHeight(node, children, titleBarWidgets, availW).coerceAtLeast(32)
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
        fullViewportMode: Boolean = false
    ): Int {
        val availW = (right - left).coerceAtLeast(160)
        val stageH = if (fullViewportMode) {
            (bottomLimit - top).coerceAtLeast(180)
        } else {
            ((availW * 9) / 16).coerceIn(220, 430)
        }
        val stageBottom = top + stageH
        Win32NativeWindowHost.lastRenderedVideoStageRect = intArrayOf(left, top, right, stageBottom)
        cachedVideoWidget = videoWidget
        cachedOverlayWidget = overlayWidget
        cachedTitleBarWidgets = titleBarWidgets
        cachedVideoFullViewportMode = fullViewportMode

        // 1. Draw the 16:9 (or fullscreen) video stage background or active video frame
        val hasVideoSurface = videoWidget.attributes.containsKey("captureBgraPtr") ||
            videoWidget.attributes.containsKey("captureBgra") ||
            videoWidget.attributes.containsKey("captureRgba")
        val hasActiveVideoFrames = hasVideoSurface && ((videoWidget.attributes["swapCount"] as? (() -> Long))?.invoke() ?: 0L) > 0L
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
        if (hasActiveVideoFrames) {
            val inset = if (fullViewportMode) 0 else 2
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

        // Extract overlay sections from `overlayWidget` (`topHudBar`, optional `centerOverlay`, and `transportBar`)
        val rawOverlayChildren = overlayWidget.children.filter { !it.disposed && it !in titleBarWidgets }
        val hudControlsLayer = rawOverlayChildren.firstOrNull {
            Win32NativeWindowHost.widgetHasClass(it, "player-overlay-controls-layer") ||
                it.attributes["id"] == "player-overlay-controls-layer"
        }
        val overlayChildren = if (hudControlsLayer != null) {
            hudControlsLayer.children.filter { !it.disposed && it !in titleBarWidgets }
        } else {
            rawOverlayChildren
        }
        val topHudBar = overlayChildren.firstOrNull {
            Win32NativeWindowHost.widgetHasClass(it, "player-overlay-top") ||
                it.attributes["id"] == "player-overlay-top"
        } ?: overlayChildren.getOrNull(0)
        val centerOverlay = overlayChildren.firstOrNull {
            Win32NativeWindowHost.widgetHasClass(it, "player-overlay-center") ||
                it.attributes["id"] == "player-overlay-center"
        }
        val transportBar = overlayChildren.firstOrNull {
            Win32NativeWindowHost.widgetHasClass(it, "player-overlay-bottom") ||
                it.attributes["id"] == "player-overlay-bottom"
        } ?: overlayChildren.lastOrNull {
            it !== topHudBar &&
                it !== centerOverlay &&
                !Win32NativeWindowHost.widgetHasClass(it, "player-settings-panel") &&
                !Win32NativeWindowHost.widgetHasClass(it, "player-popup-menu") &&
                !Win32NativeWindowHost.widgetHasClass(it, "subtitle-banner") &&
                it.attributes["id"] != "player-settings-panel" &&
                it.attributes["id"] != "subtitle-banner"
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
            Win32NativeWindowHost.widgetHasClass(it, "seek-container") ||
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
                Win32NativeWindowHost.widgetHasClass(it, "gui-text-bubble") ||
                    Win32NativeWindowHost.widgetHasClass(it, "seek-hover-bubble")
            }
        val controlsRowWidget = transportChildren.firstOrNull {
            Win32NativeWindowHost.widgetHasClass(it, "player-controls-row") || isHorizontalRowContainer(it)
        }

        fun flattenControlRowItems(container: DesktopNativeWidget): List<DesktopNativeWidget> {
            val result = mutableListOf<DesktopNativeWidget>()
            for (child in visibleChildrenOf(container, titleBarWidgets)) {
                if (Win32NativeWindowHost.widgetHasClass(child, "player-settings-panel") ||
                    child.attributes["id"] == "player-settings-panel" ||
                    Win32NativeWindowHost.widgetHasClass(child, "volume-popup-panel") ||
                    child.attributes["id"] == "volume-popup-panel"
                ) {
                    continue
                }
                val impl = child.attributes["nativeImpl"]
                val isPillGroup = impl is DesktopStyledContainerNativeImpl<*> ||
                    Win32NativeWindowHost.widgetHasClass(child, "player-controls-pill") ||
                    Win32NativeWindowHost.widgetHasClass(child, "expanding-volume-pill") ||
                    Win32NativeWindowHost.widgetHasClass(child, "player-time-pill")
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
            val leftGroup = rawRowItems.firstOrNull { Win32NativeWindowHost.widgetHasClass(it, "player-controls-left") }
            val rightGroup = rawRowItems.firstOrNull { Win32NativeWindowHost.widgetHasClass(it, "player-controls-right") }
            if (leftGroup != null || rightGroup != null) {
                val l = if (leftGroup != null) flattenControlRowItems(leftGroup) else emptyList()
                val r = if (rightGroup != null) flattenControlRowItems(rightGroup) else emptyList()
                l to r
            } else {
                val flatItems = flattenControlRowItems(controlsRowWidget)
                val spacerIdx = flatItems.indexOfFirst { isSpacerWidget(it) }
                if (spacerIdx >= 0) {
                    flatItems.subList(0, spacerIdx) to flatItems.subList(spacerIdx + 1, flatItems.size)
                } else {
                    val splitIdx = flatItems.indexOfFirst {
                        val idStr = (it.attributes["id"] as? String).orEmpty()
                        idStr == "credit-skip-badge" ||
                            idStr == "right-controls-pill" ||
                            idStr == "btn-settings" ||
                            idStr == "btn-fullscreen" ||
                            Win32NativeWindowHost.widgetHasClass(it, "credit-skip-badge") ||
                            it.attributes["nativeImpl"] is DesktopSelectNativeImpl<*>
                    }
                    if (splitIdx >= 0) {
                        flatItems.subList(0, splitIdx) to flatItems.subList(splitIdx, flatItems.size)
                    } else {
                        flatItems to emptyList()
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

        val isPlaying = videoWidget.attributes["playing"] == true ||
            videoWidget.attributes["isPlaying"] == true ||
            videoWidget.attributes["playing"] == "true" ||
            playPauseLabel.contains("Pause", ignoreCase = true) ||
            playPauseLabel.contains("❚❚") ||
            playPauseLabel.contains("⏸")

        val nowMs = GetTickCount64().toLong()
        val explicitHudHidden = overlayWidget.attributes["hudVisible"] == "false" || overlayWidget.attributes["hudVisible"] == false
        val pointerIdleOrLeft = !Win32NativeWindowHost.isPointerInVideoStage ||
            (Win32NativeWindowHost.lastVideoStageMouseMoveTickMs > 0L &&
                (nowMs - Win32NativeWindowHost.lastVideoStageMouseMoveTickMs) > 5000L)
        val hudAutoHidden = !Win32NativeWindowHost.isOverlayDragActive() &&
            (explicitHudHidden || (isPlaying && settingsPanelWidget == null && pointerIdleOrLeft))
        Win32NativeWindowHost.isVideoStageHudAutoHidden = hudAutoHidden

        // Active Subtitle Cue Text (locked at the bottom of the video stage even when HUD is auto-hidden!)
        val subtitleBannerWidget = (rawOverlayChildren + overlayChildren).firstOrNull {
            it.visible && (
                Win32NativeWindowHost.widgetHasClass(it, "subtitle-banner") ||
                    Win32NativeWindowHost.widgetHasClass(it, "player-subtitle-cue") ||
                    Win32NativeWindowHost.widgetHasClass(it, "subtitle-overlay") ||
                    it.attributes["id"] == "subtitle-banner" ||
                    it.attributes["id"] == "player-subtitle-cue"
                )
        }
        val activeSubtitleCue = (videoWidget.attributes["activeSubtitleCueText"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: (overlayWidget.attributes["subtitleText"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: (subtitleBannerWidget?.attributes?.get("bubbleText") as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: subtitleBannerWidget?.let { Win32NativeWindowHost.extractWidgetText(it).trim().takeIf { s -> s.isNotEmpty() } }

        fun drawLockedBottomSubtitleBanner() {
            val cue = activeSubtitleCue ?: return
            if (cue.isEmpty()) return
            val subBottom = stageBottom - 18
            val subH = 28
            val subTop = (subBottom - subH).coerceAtLeast(top + 48)
            val subW = (measureSingleLineTextWidth(cue, 8) + 24).coerceIn(80, availW - 32)
            val subLeft = left + (availW - subW) / 2
            val subRight = subLeft + subW
            Win32NativeWindowHost.drawAntialiasedRoundedRectRgba(
                hdc = hdc,
                left = subLeft,
                top = subTop,
                right = subRight,
                bottom = subBottom,
                radiusPx = 8f,
                fillColor = RgbaColor(0, 0, 0, 184),
                borderColor = RgbaColor.TRANSPARENT,
                borderWidthPx = 0f
            )
            SelectObject(hdc, fontBold ?: fontNormal)
            Win32NativeWindowHost.drawTextInRect(
                hdc = hdc,
                text = cue,
                left = subLeft + 12,
                top = subTop + 2,
                right = subRight - 12,
                bottom = subBottom - 2,
                colorRef = 0xFFFFFFu,
                center = true
            )
        }

        if (hudAutoHidden) {
            drawLockedBottomSubtitleBanner()
            Win32NativeWindowHost.addHitRegion(
                Win32NativeWindowHost.HitRegion(
                    left = left + 4,
                    top = top + 4,
                    right = right - 4,
                    bottom = stageBottom - 4,
                    tooltip = nonRedundantTooltip(playPauseBtnImpl?.getTooltipText()),
                    widget = centerOverlay ?: playPauseBtnWidget ?: videoWidget,
                    onClick = { _, _ ->
                        Win32NativeWindowHost.lastVideoStageMouseMoveTickMs = GetTickCount64().toLong()
                        Win32NativeWindowHost.isPointerInVideoStage = true
                        Win32NativeWindowHost.isVideoStageHudAutoHidden = false
                        playPauseBtnImpl?.dispatchClick()
                    }
                )
            )
        } else {
            // 2. Top-left Overlaid Title & Subtitle touching the top/left/right edges of the video stage
            val topStyledVisuals = (topHudBar?.attributes?.get("nativeImpl") as? DesktopStyledContainerNativeImpl<*>)?.getStyledVisuals()
            val topGrad = topStyledVisuals?.backgroundGradient
                ?: (topHudBar?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>)?.getEffectiveBackgroundGradient()
                ?: com.nexustools.genericui.style.gradient.Gradient.LinearGradient(180f, RgbaColor(0, 0, 0, 255), RgbaColor(0, 0, 0, 0))
            Win32NativeWindowHost.drawGradientRect(hdc, left, top, right, topBannerBottom, topGrad)
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
                if (Win32NativeWindowHost.widgetHasClass(item, "overlay-active-feature-badge")) {
                    Win32NativeWindowHost.drawAntialiasedRoundedRectRgba(
                        hdc = hdc,
                        left = pillL,
                        top = pillT,
                        right = topRightCursorX,
                        bottom = pillB,
                        radiusPx = 13f,
                        fillColor = null,
                        borderColor = RgbaColor(255, 255, 255, 255),
                        borderWidthPx = 1f
                    )
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
                    topRightCursorX = pillL - 6
                    continue
                }
                if (btnImpl is DesktopStyledButtonNativeImpl<*>) {
                    SelectObject(hdc, fontSmallBold ?: fontSmall)
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
                        hwndForHover = hwnd
                    )
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
                        hwndForHover = hwnd
                    )
                }
                if (btnImpl != null) {
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

            // 3. Center Play/Pause, Rewind/FFWD, & Spinner Zone inside the 16:9 video box (`#player-overlay-center`)
            val cx = (left + right) / 2
            val cy = (top + stageBottom) / 2 - 8
            val centerChildren = if (centerOverlay != null) visibleChildrenOf(centerOverlay, titleBarWidgets) else emptyList()
            val hasCenterSpinner = centerChildren.any {
                it.attributes["nativeImpl"] is DesktopSpinnerNativeImpl<*> ||
                    it.attributes["nativeImpl"] is DesktopProgressBarNativeImpl<*> ||
                    Win32NativeWindowHost.widgetHasClass(it, "spinner-border")
            }
            val centerButtons = centerChildren.filter { it.attributes["nativeImpl"] is DesktopButtonNativeImpl<*> }
            val showCenterButton = !(isPlaying && hasActiveVideoFrames && !hasCenterSpinner) ||
                Win32NativeWindowHost.isRectHovered(hwnd, left, top, right, stageBottom)

            val stageHitTop = (topBannerBottom + 4).coerceAtMost(cy - 36)
            val stageHitBottom = (bottomHudTop - 4).coerceAtLeast(cy + 36)
            if (settingsPanelWidget == null) {
                Win32NativeWindowHost.addHitRegion(
                    Win32NativeWindowHost.HitRegion(
                        left = left + 8,
                        top = stageHitTop,
                        right = right - 8,
                        bottom = stageHitBottom,
                        tooltip = nonRedundantTooltip(playPauseBtnImpl?.getTooltipText()),
                        widget = centerOverlay ?: playPauseBtnWidget ?: videoWidget,
                        onClick = { _, _ ->
                            playPauseBtnImpl?.dispatchClick()
                        }
                    )
                )
            }

            if (showCenterButton) {
                if (centerButtons.isNotEmpty()) {
                    val centerGap = 18
                    val buttonDims = centerButtons.map { btnW ->
                        val isMainPlay = Win32NativeWindowHost.widgetHasClass(btnW, "overlay-center-play-btn") ||
                            btnW.styleProperties["width"] == "64px" ||
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
                        SelectObject(hdc, if (isMainPlay) fontTitle else (fontBold ?: fontNormal))
                        Win32NativeWindowHost.drawButtonControl(
                            hdc = hdc,
                            theme = theme,
                            label = if (hasCenterSpinner && isMainPlay) "⟳" else btnImpl.getText(),
                            icon = btnImpl.getIcon(),
                            left = bL,
                            top = bT,
                            right = bR,
                            bottom = bB,
                            primary = false,
                            widget = btnWidget,
                            hwndForHover = hwnd
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
                        curBtnX = bR + centerGap
                    }
                } else {
                    Win32NativeWindowHost.drawAntialiasedRoundedRectRgba(
                        hdc = hdc,
                        left = cx - 32,
                        top = cy - 32,
                        right = cx + 32,
                        bottom = cy + 32,
                        radiusPx = 32f,
                        fillColor = RgbaColor(16, 14, 18, 199),
                        borderColor = RgbaColor(255, 255, 255, 56),
                        borderWidthPx = 1.5f
                    )
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
                    if (settingsPanelWidget != null) {
                        Win32NativeWindowHost.addHitRegion(
                            Win32NativeWindowHost.HitRegion(
                                left = cx - 32,
                                top = cy - 32,
                                right = cx + 32,
                                bottom = cy + 32,
                                tooltip = nonRedundantTooltip(playPauseBtnImpl?.getTooltipText()),
                                widget = centerOverlay ?: playPauseBtnWidget ?: videoWidget,
                                onClick = { _, _ ->
                                    playPauseBtnImpl?.dispatchClick()
                                }
                            )
                        )
                    }
                }
            }

            // 4. Bottom Overlaid Transport HUD touching the bottom/left/right edges of the video stage
            val bottomStyledVisuals = (transportBar?.attributes?.get("nativeImpl") as? DesktopStyledContainerNativeImpl<*>)?.getStyledVisuals()
            val bottomBgRgba = bottomStyledVisuals?.backgroundColor
            val bottomBorderRgba = bottomStyledVisuals?.borderColor
            val bottomGrad = bottomStyledVisuals?.backgroundGradient ?: (transportBar?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>)?.getEffectiveBackgroundGradient()
            if (bottomGrad != null) {
                Win32NativeWindowHost.drawGradientRect(hdc, left, bottomHudTop, right, bottomHudBottom, bottomGrad)
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

            // 4a. Full-width Seek Slider at top of bottom HUD (`bottomHudTop + 6` .. `bottomHudTop + 22`)
            val sliderImpl = seekWidget?.attributes?.get("nativeImpl") as? DesktopRangeSliderNativeImpl<*>
            val sL = left + hudPadX
            val sR = right - hudPadX
            val sT = bottomHudTop + 6
            val sB = bottomHudTop + 22
            if (sliderImpl != null && seekWidget != null) {
                Win32NativeWindowHost.drawSliderControl(hdc, theme, sliderImpl, sL, sT, sR, sB)
                Win32NativeWindowHost.addHitRegion(
                    Win32NativeWindowHost.HitRegion(
                        left = sL,
                        top = sT,
                        right = sR,
                        bottom = sB,
                        tooltip = sliderImpl.getTooltipText(),
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
            if (seekHoverBubbleWidget != null && seekHoverBubbleWidget.visible) {
                val bubbleText = Win32NativeWindowHost.extractWidgetText(seekHoverBubbleWidget).trim()
                if (bubbleText.isNotEmpty()) {
                    val bubbleW = (measureSingleLineTextWidth(bubbleText, 7) + 18).coerceIn(48, 120)
                    val bubbleH = 24
                    val leftProp = seekHoverBubbleWidget.styleProperties["left"]?.trim().orEmpty()
                    val pctAttr = (seekHoverBubbleWidget.attributes["layerLeftPercent"] as? String)?.toFloatOrNull()
                        ?: leftProp.takeIf { it.endsWith("%") }?.removeSuffix("%")?.trim()?.toFloatOrNull()
                    val rawBubbleL = if (pctAttr != null) {
                        sL + ((pctAttr.coerceIn(0f, 100f) / 100f) * (sR - sL)).roundToInt() - bubbleW / 2
                    } else {
                        val styleLeftPx = leftProp.removeSuffix("px").trim().toFloatOrNull()?.roundToInt() ?: 0
                        sL + styleLeftPx
                    }
                    val bubbleL = rawBubbleL.coerceIn(sL, (sR - bubbleW).coerceAtLeast(sL))
                    val bubbleR = bubbleL + bubbleW
                    val bubbleB = sT - 4
                    val bubbleT = bubbleB - bubbleH
                    Win32NativeWindowHost.drawAntialiasedRoundedRectRgba(
                        hdc = hdc,
                        left = bubbleL,
                        top = bubbleT,
                        right = bubbleR,
                        bottom = bubbleB,
                        radiusPx = 6f,
                        fillColor = RgbaColor(16, 14, 18, 240),
                        borderColor = RgbaColor(255, 255, 255, 56),
                        borderWidthPx = 1f
                    )
                    SelectObject(hdc, fontSmallBold ?: fontSmall)
                    Win32NativeWindowHost.drawTextInRect(
                        hdc = hdc,
                        text = bubbleText,
                        left = bubbleL + 4,
                        top = bubbleT + 1,
                        right = bubbleR - 4,
                        bottom = bubbleB - 1,
                        colorRef = 0xFFFFFFu,
                        center = true
                    )
                }
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
                    if (Win32NativeWindowHost.widgetHasClass(w, "expanding-volume-pill") ||
                        widgetTag(w).contains("expanding-volume-slider")
                    ) {
                        val sliderHost = w.children.getOrNull(1)
                        val animSliderW = (w.attributes["data-animated-slider-width-px"] as? String)?.toIntOrNull()
                            ?: (sliderHost?.attributes?.get("data-animated-width-px") as? String)?.toIntOrNull()
                            ?: sliderHost?.styleProperties?.get("width")?.removeSuffix("px")?.trim()?.toIntOrNull()
                            ?: if (w.attributes["data-expanded"] == "true") 92 else 0
                        return if (animSliderW > 0) 40 + 4 + animSliderW + 8 else 40
                    }
                    return when (val impl = w.attributes["nativeImpl"]) {
                        is DesktopStyledContainerNativeImpl<*> -> {
                            if (Win32NativeWindowHost.widgetHasClass(w, "player-time-pill")) {
                                val txt = Win32NativeWindowHost.extractWidgetText(w).trim()
                                if (txt.isEmpty()) 0 else (measureSingleLineTextWidth(txt, 7) + 28).coerceAtLeast(96)
                            } else {
                                val pillKids = visibleChildrenOf(w, titleBarWidgets)
                                if (pillKids.isEmpty()) 0
                                else 8 + pillKids.sumOf { measureCompactControlWidth(it) } + 2 * (pillKids.size - 1).coerceAtLeast(0)
                            }
                        }
                        is DesktopButtonNativeImpl<*> -> {
                            val explicitW = w.styleProperties["width"]?.removeSuffix("px")?.trim()?.toIntOrNull()
                            val lbl = compactOverlayLabel(impl.getText())
                            if (explicitW != null && explicitW in 28..72) {
                                explicitW.coerceAtMost(38)
                            } else if (lbl.length <= 2) {
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
                                Win32NativeWindowHost.widgetHasClass(w, "credit-skip-badge") ||
                                w.attributes["id"] == "credit-skip-badge"
                            ) {
                                measureSingleLineTextWidth(txt, 7) + 16
                            } else {
                                measureSingleLineTextWidth(txt, 7) + 8
                            }
                        }
                        else -> 56
                    }
                }

                fun renderOverlayRowControl(item: DesktopNativeWidget, itemL: Int, itemR: Int, fallbackIdx: Int, itemTop: Int = ctrlY, itemBottom: Int = ctrlY + ctrlH) {
                    val itemH = itemBottom - itemTop
                    if (Win32NativeWindowHost.widgetHasClass(item, "expanding-volume-pill") ||
                        widgetTag(item).contains("expanding-volume-slider")
                    ) {
                        val visuals = (item.attributes["nativeImpl"] as? DesktopStyledContainerNativeImpl<*>)?.getStyledVisuals()
                        Win32NativeWindowHost.drawAntialiasedRoundedRectRgba(
                            hdc = hdc,
                            left = itemL,
                            top = itemTop,
                            right = itemR,
                            bottom = itemBottom,
                            radiusPx = (visuals?.borderRadiusPx ?: 20f).coerceAtLeast(itemH / 2f),
                            fillColor = visuals?.backgroundColor ?: RgbaColor(16, 14, 18, 199),
                            borderColor = visuals?.borderColor ?: RgbaColor(255, 255, 255, 36),
                            borderWidthPx = (visuals?.borderWidthPx ?: 1f).coerceAtLeast(1f)
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
                        val muteBtn = item.children.getOrNull(0)
                        if (muteBtn != null && muteBtn.visible && !muteBtn.disposed) {
                            renderOverlayRowControl(muteBtn, itemL + 2, min(itemR - 2, itemL + 38), fallbackIdx, itemTop + 2, itemBottom - 2)
                        }
                        val sliderHost = item.children.getOrNull(1)
                        val animSliderW = (item.attributes["data-animated-slider-width-px"] as? String)?.toIntOrNull()
                            ?: (sliderHost?.attributes?.get("data-animated-width-px") as? String)?.toIntOrNull()
                            ?: sliderHost?.styleProperties?.get("width")?.removeSuffix("px")?.trim()?.toIntOrNull()
                            ?: if (item.attributes["data-expanded"] == "true") 92 else 0
                        val sliderWidget = sliderHost?.children?.firstOrNull()
                        val sliderImpl = sliderWidget?.attributes?.get("nativeImpl") as? DesktopRangeSliderNativeImpl<*>
                        if (animSliderW >= 8 && sliderWidget != null && sliderImpl != null && itemR - (itemL + 40) >= 8) {
                            val slL = itemL + 40
                            val slR = (itemR - 8).coerceAtLeast(slL + 8)
                            val savedClip = Win32NativeWindowHost.pushClipRect(hdc, itemL + 38, itemTop, itemR - 4, itemBottom)
                            try {
                                Win32NativeWindowHost.drawSliderControl(hdc, theme, sliderImpl, slL, itemTop + 6, slR, itemBottom - 6)
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
                        return
                    }
                    when (val impl = item.attributes["nativeImpl"]) {
                        is DesktopStyledContainerNativeImpl<*> -> {
                            val visuals = impl.getStyledVisuals()
                            Win32NativeWindowHost.drawAntialiasedRoundedRectRgba(
                                hdc = hdc,
                                left = itemL,
                                top = itemTop,
                                right = itemR,
                                bottom = itemBottom,
                                radiusPx = (visuals.borderRadiusPx ?: 0f).coerceAtLeast(itemH / 2f),
                                fillColor = visuals.backgroundColor ?: RgbaColor(16, 14, 18, 199),
                                borderColor = visuals.borderColor ?: RgbaColor(255, 255, 255, 36),
                                borderWidthPx = (visuals.borderWidthPx ?: 1f).coerceAtLeast(1f)
                            )
                            if (Win32NativeWindowHost.widgetHasClass(item, "player-time-pill")) {
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
                            } else {
                                val pillKids = visibleChildrenOf(item, titleBarWidgets)
                                var cx = itemL + 4
                                for ((kIdx, kid) in pillKids.withIndex()) {
                                    val kw = measureCompactControlWidth(kid)
                                    if (kw <= 0) continue
                                    val kr = (cx + kw).coerceAtMost(itemR - 4)
                                    if (cx >= kr) break
                                    renderOverlayRowControl(kid, cx, kr, kIdx, itemTop + 2, itemBottom - 2)
                                    cx = kr + 2
                                }
                            }
                        }
                        is DesktopButtonNativeImpl<*> -> {
                            val lbl = compactOverlayLabel(impl.getText())
                            val isPrimary = impl.getType() == BootstrapType.PRIMARY
                            SelectObject(hdc, fontSmallBold ?: fontSmall)
                            Win32NativeWindowHost.drawButtonControl(
                                hdc = hdc,
                                theme = theme,
                                label = lbl,
                                icon = impl.getIcon(),
                                left = itemL,
                                top = itemTop,
                                right = itemR,
                                bottom = itemBottom,
                                primary = isPrimary,
                                widget = item,
                                hwndForHover = hwnd
                            )
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
                        is DesktopRangeSliderNativeImpl<*> -> {
                            Win32NativeWindowHost.drawSliderControl(hdc, theme, impl, itemL, itemTop + 6, itemR, itemBottom - 6)
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
                        is DesktopTextNativeImpl<*> -> {
                            val txt = impl.getText().trim()
                            if (txt.isNotEmpty()) {
                                if (Win32NativeWindowHost.widgetHasClass(item, "badge") ||
                                    Win32NativeWindowHost.widgetHasClass(item, "credit-skip-badge") ||
                                    item.attributes["id"] == "credit-skip-badge"
                                ) {
                                    drawBadgePill(hdc, theme, fontSmall, item, txt, itemL, itemTop + (itemH - 20) / 2)
                                } else {
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
                            SelectObject(hdc, fontSmall)
                            Win32NativeWindowHost.drawSelectControl(
                                hdc = hdc,
                                theme = theme,
                                label = curLabel.ifBlank { fallback },
                                left = itemL,
                                top = itemTop,
                                right = itemR,
                                bottom = itemBottom,
                                widget = item,
                                hwndForHover = hwnd
                            )
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
                val totalRightW = visibleRightItems.sumOf { measureCompactControlWidth(it) } +
                    gap * (visibleRightItems.size - 1).coerceAtLeast(0)
                var curRightX = max(curLeftX + 6, right - hudPadX - totalRightW)
                for ((idx, item) in visibleRightItems.withIndex()) {
                    val w = measureCompactControlWidth(item)
                    val itemR = (curRightX + w).coerceAtMost(right - hudPadX)
                    if (curRightX >= itemR) break
                    renderOverlayRowControl(item, curRightX, itemR, idx)
                    curRightX = itemR + gap
                }
            }

            // Draw active subtitle banner locked at the bottom over the bottom HUD gradient, below popovers
            drawLockedBottomSubtitleBanner()

            // 4c. Anchored Playback Settings / CC Popover (`.player-popup-menu` / `#player-settings-panel`) when visible
            if (settingsPanelWidget != null) {
                val isCcMenu = Win32NativeWindowHost.widgetHasClass(settingsPanelWidget, "cc-popup-menu")
                val panelRows = visibleChildrenOf(settingsPanelWidget, titleBarWidgets)
                fun measurePopoverChildIntrinsicW(rowNode: DesktopNativeWidget): Int {
                    if (isSpacerWidget(rowNode)) return 0
                    return when (val rImpl = rowNode.attributes["nativeImpl"]) {
                        is DesktopTextNativeImpl<*> -> measureSingleLineTextWidth(rImpl.getText().trim(), 7) + 18
                        is DesktopButtonNativeImpl<*> -> measureButtonWidth(rImpl) + (if (isCcMenu) 8 else 16)
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
                    if (isSpacerWidget(rowNode)) return getFixedSpacerHeight(rowNode).coerceIn(2, if (isCcMenu) 6 else 12)
                    val btnRowH = if (isCcMenu) 25 else 30
                    return when (val rImpl = rowNode.attributes["nativeImpl"]) {
                        is DesktopTextNativeImpl<*> -> if (rImpl.getText().trim().isNotEmpty()) (if (isCcMenu) 18 else 22) else 0
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
                val popW = if (isCcMenu) {
                    (widestChildW + 14).coerceIn(130, 220)
                } else {
                    (widestChildW + 24).coerceIn(180, 250)
                }
                val totalContentH = panelRows.sumOf { estimatePopoverChildH(it) } + (if (isCcMenu) 12 else 16)
                val maxPopH = (stageH - 76).coerceAtLeast(120)
                val popH = totalContentH.coerceIn(44, maxPopH)
                val popRight = if (isCcMenu) right - 44 else right - 12
                val popLeft = (popRight - popW).coerceAtLeast(left + 12)
                val popBottom = stageBottom - 54
                val popTop = (popBottom - popH).coerceAtLeast(top + 12)

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

                var py = popTop + (if (isCcMenu) 6 else 8)
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
                            py += getFixedSpacerHeight(rowNode).coerceIn(2, if (isCcMenu) 6 else 12)
                            continue
                        }
                        val rowImpl = rowNode.attributes["nativeImpl"]
                        if (rowImpl is DesktopTextNativeImpl<*>) {
                            val headerTxt = rowImpl.getText().trim()
                            if (headerTxt.isNotEmpty()) {
                                val hdrH = if (isCcMenu) 16 else 20
                                SelectObject(hdc, fontSmallBold ?: fontSmall)
                                Win32NativeWindowHost.drawTextInRect(
                                    hdc = hdc,
                                    text = headerTxt,
                                    left = popLeft + (if (isCcMenu) 8 else 12),
                                    top = py,
                                    right = popRight - (if (isCcMenu) 8 else 12),
                                    bottom = py + hdrH,
                                    colorRef = 0xB8A99Eu
                                )
                                py += if (isCcMenu) 18 else 22
                            }
                            continue
                        }
                        if (rowImpl is DesktopSelectNativeImpl<*>) {
                            val curVal = rowImpl.getSelectedValue() ?: ""
                            val curLabel = rowImpl.getOptions().firstOrNull { it.first == curVal }?.second ?: curVal
                            val selL = popLeft + 8
                            val selR = popRight - 8
                            val hitY = py
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
                            val isPrimary = rowImpl.getType() == BootstrapType.PRIMARY
                            val btnH = if (isCcMenu) 22 else 26
                            val padH = if (isCcMenu) 6 else 8
                            SelectObject(hdc, fontSmall)
                            Win32NativeWindowHost.drawButtonControl(
                                hdc = hdc,
                                theme = theme,
                                label = rowImpl.getText(),
                                icon = rowImpl.getIcon(),
                                left = popLeft + padH,
                                top = py,
                                right = popRight - padH,
                                bottom = py + btnH,
                                primary = isPrimary,
                                widget = rowNode,
                                hwndForHover = hwnd
                            )
                            val hitY = py
                            Win32NativeWindowHost.addHitRegion(
                                Win32NativeWindowHost.HitRegion(
                                    left = popLeft + padH,
                                    top = hitY,
                                    right = popRight - padH,
                                    bottom = hitY + btnH,
                                    tooltip = nonRedundantTooltip(rowImpl.getTooltipText(), rowImpl.getText()),
                                    widget = rowNode,
                                    onClick = { _, _ -> rowImpl.dispatchClick() }
                                )
                            )
                            py += if (isCcMenu) 25 else 30
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
                            val btnRowH = if (isCcMenu) 25 else 30
                            val btnDrawH = if (isCcMenu) 22 else 26
                            val padH = if (isCcMenu) 6 else 8
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
                                    val hitY = py
                                    SelectObject(hdc, fontSmall)
                                    Win32NativeWindowHost.drawButtonControl(
                                        hdc = hdc,
                                        theme = theme,
                                        label = subBtnImpl.getText(),
                                        icon = subBtnImpl.getIcon(),
                                        left = popLeft + padH,
                                        top = hitY,
                                        right = popRight - padH,
                                        bottom = hitY + btnDrawH,
                                        primary = subBtnImpl.getType() == BootstrapType.PRIMARY,
                                        widget = subKid,
                                        hwndForHover = hwnd
                                    )
                                    Win32NativeWindowHost.addHitRegion(
                                        Win32NativeWindowHost.HitRegion(
                                            left = popLeft + padH,
                                            top = hitY,
                                            right = popRight - padH,
                                            bottom = hitY + btnDrawH,
                                            tooltip = nonRedundantTooltip(subBtnImpl.getTooltipText(), subBtnImpl.getText()),
                                            widget = subKid,
                                            onClick = { _, _ -> subBtnImpl.dispatchClick() }
                                        )
                                    )
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
                            val lblTxt = (labelWidget?.attributes?.get("nativeImpl") as? DesktopTextNativeImpl<*>)?.getText()?.trim().orEmpty()
                            val selImpl = selectWidget.attributes["nativeImpl"] as DesktopSelectNativeImpl<*>
                            val curVal = selImpl.getSelectedValue() ?: ""
                            val curLabel = selImpl.getOptions().firstOrNull { it.first == curVal }?.second ?: curVal
                            val hitY = py
                            val selL = if (lblTxt.isNotEmpty()) {
                                val lblRight = (popLeft + 10 + measureSingleLineTextWidth(lblTxt, 7) + 8).coerceAtMost(popLeft + 88)
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
                            val btnImpl = btnWidget.attributes["nativeImpl"] as DesktopButtonNativeImpl<*>
                            val hitY = py
                            SelectObject(hdc, fontSmall)
                            Win32NativeWindowHost.drawButtonControl(
                                hdc = hdc,
                                theme = theme,
                                label = btnImpl.getText(),
                                icon = btnImpl.getIcon(),
                                left = popLeft + 8,
                                top = hitY,
                                right = popRight - 8,
                                bottom = hitY + 26,
                                primary = btnImpl.getType() == BootstrapType.PRIMARY,
                                widget = btnWidget,
                                hwndForHover = hwnd
                            )
                            Win32NativeWindowHost.addHitRegion(
                                Win32NativeWindowHost.HitRegion(
                                    left = popLeft + 8,
                                    top = hitY,
                                    right = popRight - 8,
                                    bottom = hitY + 26,
                                    tooltip = nonRedundantTooltip(btnImpl.getTooltipText(), btnImpl.getText()),
                                    widget = btnWidget,
                                    onClick = { _, _ -> btnImpl.dispatchClick() }
                                )
                            )
                            py += 30
                        } else {
                            py += availInnerH.coerceAtMost(30)
                        }
                    }
                }
            }
        }

        if (fullViewportMode) {
            return stageBottom
        }

        // 5. Below the 16:9 Video Box: Centered "Client Features: ..." summary & diagnostic badge/button bar
        var curY = stageBottom + 8
        if (diagnosticItems.isNotEmpty()) {
            // Collect badge text summary for "Client Features:" line
            val featureParts = mutableListOf<String>()
            val interactiveButtons = mutableListOf<DesktopNativeWidget>()
            for (item in diagnosticItems) {
                when (val impl = item.attributes["nativeImpl"]) {
                    is DesktopTextNativeImpl<*> -> {
                        val t = impl.getText().trim()
                        if (t.isNotEmpty()) featureParts.add(t)
                    }
                    is DesktopButtonNativeImpl<*> -> {
                        interactiveButtons.add(item)
                    }
                }
            }
            val summaryLine = if (featureParts.isNotEmpty()) {
                "Client Features: ${featureParts.joinToString(" • ")}"
            } else {
                "Client Features: Direct Play"
            }
            SelectObject(hdc, fontSmall)
            Win32NativeWindowHost.drawTextInRect(
                hdc = hdc,
                text = summaryLine,
                left = left + 8,
                top = curY,
                right = right - 8,
                bottom = curY + 20,
                colorRef = theme.textSecondary,
                center = true
            )
            curY += 24

            // Centered row of diagnostic / capability buttons (`[4K] [HDR] [Direct] [V: H264] [A: AAC] ...`)
            if (interactiveButtons.isNotEmpty()) {
                val btnGap = 6
                val btnH = 26
                val widths = interactiveButtons.map { item ->
                    val impl = item.attributes["nativeImpl"] as DesktopButtonNativeImpl<*>
                    measureButtonWidth(impl).coerceIn(44, 150)
                }
                val totalBtnW = widths.sum() + btnGap * (interactiveButtons.size - 1).coerceAtLeast(0)
                var bx = left + ((availW - totalBtnW) / 2).coerceAtLeast(4)
                for (i in interactiveButtons.indices) {
                    val item = interactiveButtons[i]
                    val impl = item.attributes["nativeImpl"] as DesktopButtonNativeImpl<*>
                    val bw = widths[i]
                    val br = (bx + bw).coerceAtMost(right - 4)
                    if (bx >= br) break
                    val isPrimary = impl.getType() == BootstrapType.PRIMARY || impl.getType() == BootstrapType.SUCCESS
                    SelectObject(hdc, fontSmall)
                    Win32NativeWindowHost.drawButtonControl(
                        hdc = hdc,
                        theme = theme,
                        label = impl.getText(),
                        icon = impl.getIcon(),
                        left = bx,
                        top = curY,
                        right = br,
                        bottom = curY + btnH,
                        primary = isPrimary,
                        widget = item,
                        hwndForHover = hwnd
                    )
                    val hitL = bx
                    val hitY = curY
                    Win32NativeWindowHost.addHitRegion(
                        Win32NativeWindowHost.HitRegion(
                            left = hitL,
                            top = hitY,
                            right = br,
                            bottom = hitY + btnH,
                            tooltip = nonRedundantTooltip(impl.getTooltipText(), impl.getText()),
                            widget = item,
                            onClick = { _, _ -> impl.dispatchClick() }
                        )
                    )
                    bx = br + btnGap
                }
                curY += btnH + 6
            }
        }

        // Render any additional children in primaryVideoStack below the stage
        for (extra in otherChildren) {
            curY = renderFlowNode(
                hwnd = hwnd,
                hdc = hdc,
                theme = theme,
                fontSmall = fontSmall,
                fontSmallBold = fontSmallBold,
                fontNormal = fontNormal,
                fontBold = fontBold,
                fontTitle = fontTitle,
                fontMetricBig = null,
                node = extra,
                titleBarWidgets = titleBarWidgets,
                left = left,
                top = curY,
                right = right,
                bottomLimit = bottomLimit
            )
            curY += 6
        }

        return curY
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
        val rowBaseImpl = rowWidget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
        val isClickableRow = rowBaseImpl != null && rowBaseImpl.hasClickListener() && !isDisabled
        val isRowHovered = isClickableRow && Win32NativeWindowHost.hoveredWidget === rowWidget
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

        // 1. Measure & render rightSection controls right-aligned inside the card
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

        for (i in rightItems.indices) {
            val item = rightItems[i]
            val w = rightWidths[i]
            if (w <= 0) continue
            val itemR = (rx + w).coerceAtMost(right - padRight)
            when (val impl = item.attributes["nativeImpl"]) {
                is DesktopTextNativeImpl<*> -> {
                    val txt = impl.getText().trim()
                    if (txt.isNotEmpty()) {
                        drawBadgePill(hdc, theme, fontSmall, item, txt, rx, top + (rowH - 20) / 2)
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
                        hwndForHover = hwnd
                    )
                    val bx = rx
                    Win32NativeWindowHost.addHitRegion(
                        Win32NativeWindowHost.HitRegion(
                            left = bx,
                            top = by,
                            right = itemR,
                            bottom = by + bh,
                            tooltip = nonRedundantTooltip(impl.getTooltipText(), impl.getText()),
                            widget = item,
                            onClick = { _, _ -> impl.dispatchClick() }
                        )
                    )
                }
            }
            rx = itemR + gap
        }

        // 2. Resolve optional leading icon (emoji or async image) and 1-or-2-line text stack
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

        val textLeft = if (!emojiIcon.isNullOrBlank()) {
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
            iconRight + 12
        } else if (!iconUrl.isNullOrBlank()) {
            val cssFilter = resolveWidgetCssFilter(imgChild, rowWidget)
            val placeholderUrl = imgImpl?.getPlaceholderUrl()?.trim().takeUnless { it.isNullOrEmpty() }
                ?: ImageLoader.DEFAULT_PLACEHOLDER_URI
            val antialiasing = imgImpl?.isAntialiasing() ?: true
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
                cssFilter = cssFilter,
                left = iconLeft,
                top = iconTop,
                right = iconRight,
                bottom = iconBottom,
                surfaceBgColorRef = theme.bgElevated
            )
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
                Win32NativeWindowHost.widgetHasClass(rowWidget, "badge-4k-hdr-container") ||
                rowWidget.attributes["id"] == "badge-4k-hdr-container"
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
                    val imgH = ((child.attributes["height"] as? Number)?.toInt() ?: 28).coerceIn(16, 96)
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
        top: Int
    ): Int {
        val w = measureSingleLineTextWidth(text, 7) + 16
        val h = 20
        val impl = widget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
        val type = impl?.getType() ?: impl?.getBackgroundType()
        val (bg, border, fg) = when (type) {
            BootstrapType.SUCCESS -> Triple(theme.accentGreenSoftBg, theme.accentGreenBorder, theme.accentGreen)
            BootstrapType.WARNING -> Triple(theme.accentAmberSoftBg, theme.accentAmber, theme.accentAmber)
            BootstrapType.DANGER -> Triple(theme.accentRedSoftBg, theme.accentRedBorder, theme.accentRed)
            BootstrapType.PRIMARY, BootstrapType.INFO -> Triple(theme.accentBlueSoftBg, theme.accentBlueBorder, theme.accentBlueText)
            else -> Triple(theme.bgElevated, theme.borderSubtle, theme.textSecondary)
        }
        Win32NativeWindowHost.drawRoundedRect(hdc, left, top, left + w, top + h, 10, bg, border)
        SelectObject(hdc, fontSmall)
        Win32NativeWindowHost.drawTextInRect(hdc, text, left + 6, top + 1, left + w - 6, top + h - 1, fg, center = true)
        return w
    }

    private fun estimateFlowNodeHeight(
        child: DesktopNativeWidget,
        titleBarWidgets: Set<DesktopNativeWidget>,
        availW: Int
    ): Int {
        if (!child.visible || child.disposed) return 0
        val impl = child.attributes["nativeImpl"]
        val tag = widgetTag(child)
        return when {
            isSpacerWidget(child) -> getFixedSpacerHeight(child)
            impl is DesktopLazyListNativeImpl<*> || child.attributes["isLazyList"] == true -> {
                ((child.attributes["totalContentExtentPx"] as? Number)?.toFloat()?.roundToInt() ?: 0)
                    .coerceAtLeast(56)
            }
            findStageAndOverlay(child, titleBarWidgets) != null -> {
                val (stageW, overlayW) = findStageAndOverlay(child, titleBarWidgets)!!
                val extraChildren = visibleChildrenOf(child, titleBarWidgets).filter { it !== stageW && it !== overlayW }
                val stageBoxH = ((availW * 9) / 16).coerceIn(220, 430)
                stageBoxH + 8 + estimateVerticalStackHeight(child, extraChildren, titleBarWidgets, availW)
            }
            isVideoPlayerWidget(child) -> ((availW * 9) / 16).coerceIn(180, 380)
            child.attributes.containsKey("captureBgra") || child.attributes.containsKey("captureBgraPtr") -> {
                val rawH = (child.attributes["surfaceHeight"] as? (() -> Int))?.invoke() ?: 44
                if (rawH in 1..90) max(36, rawH) else ((availW * 9) / 16).coerceIn(200, 380)
            }
            impl is DesktopImageNativeImpl<*> -> ((child.attributes["height"] as? Number)?.toInt() ?: 48).coerceIn(16, 240)
            impl is DesktopTextNativeImpl<*> -> {
                val t = impl.getText().trim()
                if (t.isEmpty()) 0 else {
                    val charsPerLine = max(18, availW / 7)
                    val lines = ((t.length + charsPerLine - 1) / charsPerLine).coerceIn(1, 6)
                    val lineH = if (tag == "h1" || tag == "h2") 30 else if (tag == "h3" || tag == "h4") 24 else 20
                    lines * lineH
                }
            }
            impl is DesktopButtonNativeImpl<*> -> 28
            impl is DesktopTextInputNativeImpl<*> -> 30
            impl is DesktopSelectNativeImpl<*> -> 28
            impl is DesktopCheckboxNativeImpl<*> -> 22
            impl is DesktopRangeSliderNativeImpl<*> -> 20
            impl is DesktopProgressBarNativeImpl<*> -> 14
            impl is DesktopSpinnerNativeImpl<*> -> 26
            Win32NativeWindowHost.widgetHasClass(child, "alert") -> 38
            Win32NativeWindowHost.widgetHasClass(child, "card") -> {
                estimateVerticalStackHeight(child, visibleChildrenOf(child, titleBarWidgets), titleBarWidgets, availW - 48) + 88
            }
            isHorizontalRowContainer(child) -> {
                val rowChildren = visibleChildrenOf(child, titleBarWidgets)
                if (isCompositeListRow(child, rowChildren, titleBarWidgets)) {
                    56
                } else {
                    val sumW = rowChildren.sumOf { measureInlineChildWidth(it, availW) + 6 }
                    val rows = ((sumW + availW - 1) / availW.coerceAtLeast(1)).coerceIn(1, 4)
                    rows * 34
                }
            }
            else -> estimateVerticalStackHeight(child, visibleChildrenOf(child, titleBarWidgets), titleBarWidgets, availW)
        }
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
        val rawStr = (widget?.attributes?.get("cssFilter") as? String)
            ?: (widget?.attributes?.get("rowImageFilter") as? String)
            ?: widget?.styleProperties?.get("filter")
            ?: (rowWidget?.attributes?.get("rowImageFilter") as? String)
            ?: (rowWidget?.attributes?.get("cssFilter") as? String)
            ?: rowWidget?.styleProperties?.get("filter")
        val parsedFromStr = CssFilter.parse(rawStr)
        if (parsedFromStr != null && parsedFromStr.isNone) {
            return CssFilter.NONE
        }
        val imgImpl = widget?.attributes?.get("nativeImpl") as? DesktopImageNativeImpl<*>
        imgImpl?.getCssFilter()?.let { return it }
        (widget?.attributes?.get("cssFilter") as? CssFilter)?.let { return it }
        (rowWidget?.attributes?.get("cssFilter") as? CssFilter)?.let { return it }
        return parsedFromStr
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
        surfaceBgColorRef: UInt
    ) {
        val boxW = (right - left).coerceAtLeast(1)
        val boxH = (bottom - top).coerceAtLeast(1)
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

        val inset = 2
        val dstL = left + inset
        val dstT = top + inset
        val dstW = (boxW - inset * 2).coerceAtLeast(1)
        val dstH = (boxH - inset * 2).coerceAtLeast(1)

        val cleanUrl = url.trim()
        val primaryEntry = if (cleanUrl.isNotEmpty()) {
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
            )
        } else {
            null
        }

        val entryToDraw = primaryEntry ?: run {
            // While loading asynchronously (or if primary URL is empty/loading), render the placeholder image (`loading.svg` by default)
            val cleanPlaceholder = placeholderUrl.trim().ifEmpty { ImageLoader.DEFAULT_PLACEHOLDER_URI }
            val isLoading = cleanUrl.isNotEmpty() && withImageCacheLock { cleanUrl in inFlightImageUrls }
            if (isLoading || cleanUrl.isEmpty()) {
                getOrFetchImageBgra(
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
                null
            }
        }

        if (entryToDraw != null) {
            val imgW = entryToDraw.width.coerceAtLeast(1)
            val imgH = entryToDraw.height.coerceAtLeast(1)
            val bgraBytes = entryToDraw.bgra
            if (bgraBytes.size >= imgW * imgH * 4) {
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
                    Win32NativeWindowHost.gdiDirtySinceLastFlush = true
                }
                return
            }
        }

        // Fallback monogram if image failed to load and placeholder is unavailable
        val monogram = buildMonogram(fallbackLabel)
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
        val lower = url.trim().lowercase()
        return lower == "res://drawable/loading" ||
            lower == "drawable/loading.svg" ||
            lower == "loading.svg" ||
            lower.startsWith("data:image/svg+xml")
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
        return CompositeBgraEntry(bgra, w, h)
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

        val existingComposite = withImageCacheLock {
            strongCompositeBgraLru[compositeKey]?.also { hit ->
                putStrongCompositeBgraLruLocked(compositeKey, hit)
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
        }
        if (existingComposite != null) {
            ownerWidget?.attributes?.set("activeBgraEntry", existingComposite)
            return existingComposite
        }

        // Fast-path for built-in default `loading.svg` placeholder
        if (isDefaultLoadingPlaceholderUrl(url)) {
            val placeholderBuf = ImageLoader.createDefaultPlaceholderBuffer(dstW, dstH, isDarkTheme)
            val bgra = rgbaToCompositeBgra(placeholderBuf.bytes, dstW, dstH, surfaceBgColorRef)
            val entry = CompositeBgraEntry(bgra, dstW, dstH)
            withImageCacheLock {
                putStrongCompositeBgraLruLocked(compositeKey, entry)
                decodedImageBgraCache[compositeKey] = WeakReference(entry)
            }
            ownerWidget?.attributes?.set("activePlaceholderEntry", entry)
            return entry
        }

        val rawBuffer = withImageCacheLock {
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

        if (rawBuffer != null && rawBuffer.width > 0 && rawBuffer.height > 0) {
            ownerWidget?.attributes?.set("activeRawBuffer", rawBuffer)
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
                    }
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

    private fun isHorizontalRowContainer(widget: DesktopNativeWidget): Boolean {
        val tag = widgetTag(widget)
        if (tag.contains("hstack") || Win32NativeWindowHost.widgetHasClass(widget, "hstack")) return true
        if (Win32NativeWindowHost.widgetHasClass(widget, "row") || Win32NativeWindowHost.widgetHasClass(widget, "flex-row")) return true
        if (Win32NativeWindowHost.widgetHasClass(widget, "d-flex") && !Win32NativeWindowHost.widgetHasClass(widget, "flex-column")) return true
        if (Win32NativeWindowHost.widgetHasClass(widget, "badge-4k-hdr-container") ||
            widget.attributes["id"] == "badge-4k-hdr-container" ||
            Win32NativeWindowHost.widgetHasClass(widget, "player-controls-row") ||
            Win32NativeWindowHost.widgetHasClass(widget, "settings-row")
        ) return true
        val flexDir = widget.styleProperties["flex-direction"]?.lowercase()
        if (flexDir == "row") return true
        return false
    }

    private fun isOverlayHudWidget(widget: DesktopNativeWidget): Boolean {
        return Win32NativeWindowHost.widgetHasClass(widget, "episodecaster-overlay-hud") ||
            Win32NativeWindowHost.widgetHasClass(widget, "desktop-web-video-overlay") ||
            Win32NativeWindowHost.widgetHasClass(widget, "mobile-touch-video-overlay") ||
            Win32NativeWindowHost.widgetHasClass(widget, "desktop-web-visualizer-overlay") ||
            Win32NativeWindowHost.widgetHasClass(widget, "mobile-touch-visualizer-overlay") ||
            Win32NativeWindowHost.widgetHasClass(widget, "player-overlay") ||
            widget.attributes["id"] == "player-overlay"
    }

    private fun findStageAndOverlay(
        container: DesktopNativeWidget,
        titleBarWidgets: Set<DesktopNativeWidget>
    ): Pair<DesktopNativeWidget, DesktopNativeWidget>? {
        val children = visibleChildrenOf(container, titleBarWidgets)
        if (children.size < 2) return null
        val overlayChild = children.firstOrNull { isOverlayHudWidget(it) } ?: return null
        val stageChild = children.firstOrNull { child ->
            child !== overlayChild && (
                isVideoPlayerWidget(child) ||
                    child.attributes.containsKey("captureBgra") ||
                    child.attributes.containsKey("captureRgba")
                )
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
            if (Win32NativeWindowHost.widgetHasClass(child, "player-settings-panel") ||
                Win32NativeWindowHost.widgetHasClass(child, "player-popup-menu") ||
                child.attributes["id"] == "player-settings-panel"
            ) {
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
            (widget.styleProperties["flex-grow"]?.toFloatOrNull() ?: 0f) >= 1f
        ) {
            return true
        }
        return false
    }

    private fun getSpacerWeight(widget: DesktopNativeWidget): Float {
        return (widget.attributes["spacerWeight"] as? Number)?.toFloat()
            ?: widget.styleProperties["flex-grow"]?.toFloatOrNull()
            ?: 0f
    }

    private fun isFillSpacerWidget(widget: DesktopNativeWidget): Boolean {
        return isSpacerWidget(widget) && getSpacerWeight(widget) > 0f
    }

    private fun getFixedSpacerWidth(widget: DesktopNativeWidget): Int {
        return ((widget.attributes["spacerWidthPx"] as? Number)?.toFloat()
            ?: widget.styleProperties["width"]?.removeSuffix("px")?.trim()?.toFloatOrNull()
            ?: widget.styleProperties["min-width"]?.removeSuffix("px")?.trim()?.toFloatOrNull()
            ?: 0f).roundToInt().coerceAtLeast(0)
    }

    private fun getFixedSpacerHeight(widget: DesktopNativeWidget): Int {
        return ((widget.attributes["spacerHeightPx"] as? Number)?.toFloat()
            ?: widget.styleProperties["height"]?.removeSuffix("px")?.trim()?.toFloatOrNull()
            ?: widget.styleProperties["min-height"]?.removeSuffix("px")?.trim()?.toFloatOrNull()
            ?: 0f).roundToInt().coerceAtLeast(0)
    }

    private fun isVideoPlayerWidget(widget: DesktopNativeWidget): Boolean {
        val tag = widgetTag(widget)
        val cls = widget.nativeClassName.lowercase()
        return tag == "video" ||
            tag.contains("video-player") ||
            cls.contains("video") ||
            Win32NativeWindowHost.widgetHasClass(widget, "video-player")
    }

    private fun measureButtonWidth(btn: DesktopButtonNativeImpl<*>): Int {
        val label = btn.getText().trim()
        val hasIcon = !btn.getIcon().isNullOrBlank()
        if (label.isEmpty() && hasIcon) return 32
        val textW = measureSingleLineTextWidth(label, 7)
        return (textW + (if (hasIcon) 32 else 20)).coerceIn(40, 280)
    }

    private fun measureInlineChildWidth(widget: DesktopNativeWidget, availW: Int): Int {
        if (isSpacerWidget(widget)) {
            return if (isFillSpacerWidget(widget)) 0 else getFixedSpacerWidth(widget)
        }
        val impl = widget.attributes["nativeImpl"]
        return when (impl) {
            is DesktopImageNativeImpl<*> -> ((widget.attributes["width"] as? Number)?.toInt() ?: 32).coerceIn(16, availW)
            is DesktopButtonNativeImpl<*> -> measureButtonWidth(impl)
            is DesktopTextNativeImpl<*> -> {
                val txt = impl.getText().trim()
                if (txt.isEmpty()) 0
                else if (Win32NativeWindowHost.widgetHasClass(widget, "badge")) {
                    measureSingleLineTextWidth(txt, 7) + 16
                } else {
                    min(availW, measureSingleLineTextWidth(txt, 7) + 8)
                }
            }
            is DesktopTextInputNativeImpl<*> -> min(availW, max(160, availW / 2))
            is DesktopSelectNativeImpl<*> -> {
                val curVal = impl.getSelectedValue() ?: ""
                val curLabel = impl.getOptions().firstOrNull { it.first == curVal }?.second ?: curVal
                (measureSingleLineTextWidth(curLabel.ifBlank { "Select..." }, 7) + 34).coerceIn(110, 240)
            }
            is DesktopCheckboxNativeImpl<*> -> (measureSingleLineTextWidth(impl.getLabel(), 7) + 28).coerceIn(72, 240)
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
                            val sum = visChildren.sumOf { measureInlineChildWidth(it, availW) + 6 }
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
        center: Boolean = false
    ) = memScoped {
        val r = alloc<RECT>()
        r.left = left
        r.top = top
        r.right = right
        r.bottom = bottom
        SetTextColor(hdc, colorRef)
        val alignFlag = if (center) DT_CENTER else DT_LEFT
        val flags = (alignFlag or DT_WORDBREAK or DT_END_ELLIPSIS or DT_NOPREFIX).toUInt()
        DrawTextW(hdc, text, -1, r.ptr, flags)
        Win32NativeWindowHost.gdiDirtySinceLastFlush = true
    }
}
