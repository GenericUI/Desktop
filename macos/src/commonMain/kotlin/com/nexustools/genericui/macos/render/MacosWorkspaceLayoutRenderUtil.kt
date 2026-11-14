package com.nexustools.genericui.macos.render

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.dialog.DesktopDialogWindowNativeImpl
import com.nexustools.genericui.desktop.lazy.DesktopLazyGridNativeImpl
import com.nexustools.genericui.desktop.lazy.DesktopLazyListNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopWindowNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopButtonNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopCheckboxNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopDividerNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopDropdownNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopImageNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopMarkdownViewNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopProgressBarNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopRangeSliderNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopScrollViewNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopSelectNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopSpinnerNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopStyledButtonNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopStyledContainerNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTextInputNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTextNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.bootstrap.TextAlignment
import com.nexustools.genericui.enums.css.CssBorderStyle
import com.nexustools.genericui.enums.css.CssUnit
import com.nexustools.genericui.geometry.EdgeInsets
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssLength
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Type-grouped macOS workspace, shell, card, split/hybrid-column, scroll-view, virtualized lazy-list,
 * and modal dialog layout renderer for `:desktop:macos`.
 */
object MacosWorkspaceLayoutRenderUtil {

    private val hostScrollOffsets = LinkedHashMap<Long, Float>(32)

    private fun findNestedTopNavbar(
        unwrappedRoot: DesktopNativeWidget,
        visibleRootChildren: List<DesktopNativeWidget>
    ): DesktopNativeWidget? {
        if (MacosNavigationRenderUtil.isNavbarWidget(unwrappedRoot)) return unwrappedRoot
        visibleRootChildren.firstOrNull { MacosNavigationRenderUtil.isNavbarWidget(it) }?.let { return it }
        if (isLayeredContainerWidget(unwrappedRoot) && visibleRootChildren.isNotEmpty()) {
            val base = unwrapShellContainers(visibleRootChildren.first())
            if (MacosNavigationRenderUtil.isNavbarWidget(base)) return base
            visibleChildrenOf(base).firstOrNull { MacosNavigationRenderUtil.isNavbarWidget(it) }?.let { return it }
        }
        return null
    }

    fun hasTopNavbarInSubtree(rootWidget: DesktopNativeWidget): Boolean {
        val unwrapped = unwrapShellContainers(rootWidget)
        return findNestedTopNavbar(unwrapped, visibleChildrenOf(unwrapped)) != null
    }

    fun renderStageSubtreeOnly(
        ctx: MacosRenderContext,
        stageWidget: DesktopNativeWidget,
        stageBounds: Rect
    ) {
        if (!stageWidget.visible || stageWidget.disposed || stageBounds.width <= 0 || stageBounds.height <= 0) return
        MacosStageOverlayRenderUtil.renderStageContainer(ctx, stageWidget, stageBounds, ::renderWidgetSubtree)
        flushDeferredOverlays(ctx)
    }

    fun renderMainWindow(
        ctx: MacosRenderContext,
        windowImpl: DesktopWindowNativeImpl<DesktopNativeWidget>,
        fullscreenElement: DesktopNativeWidget? = null
    ) {
        val vp = ctx.viewport
        MacosGraphicsDrawUtil.fillRect(ctx, vp.x, vp.y, vp.width, vp.height, ctx.palette.bgWindow)

        if (fullscreenElement != null && !fullscreenElement.disposed && fullscreenElement.visible) {
            windowImpl.nativeWidget.bounds = vp
            fullscreenElement.bounds = vp
            val unwrappedFs = unwrapShellContainersWithBounds(fullscreenElement, vp)
            val fsBg = (unwrappedFs.attributes["backgroundColor"] as? RgbaColor)?.takeIf { it.a > 0 }
                ?: (fullscreenElement.attributes["backgroundColor"] as? RgbaColor)?.takeIf { it.a > 0 }
                ?: (windowImpl.nativeWidget.attributes["backgroundColor"] as? RgbaColor)?.takeIf { it.a > 0 }
            if (fsBg != null) {
                MacosGraphicsDrawUtil.fillRect(ctx, vp.x, vp.y, vp.width, vp.height, fsBg)
            }
            if (MacosStageOverlayRenderUtil.isStageContainer(unwrappedFs)) {
                MacosStageOverlayRenderUtil.renderStageContainer(ctx, unwrappedFs, vp, ::renderWidgetSubtree)
            } else {
                renderWidgetSubtree(ctx, unwrappedFs, vp)
            }
            flushDeferredOverlays(ctx)
            return
        }

        val rootWidget = windowImpl.nativeWidget
        rootWidget.bounds = vp

        val rootBg = (rootWidget.attributes["backgroundColor"] as? RgbaColor)?.takeIf { it.a > 0 }
        if (rootBg != null) {
            MacosGraphicsDrawUtil.fillRect(ctx, vp.x, vp.y, vp.width, vp.height, rootBg)
        }

        var contentY = vp.y
        var contentBottom = vp.bottom

        val explicitNavbar = (windowImpl.nativeWidget.attributes["navBar"] as? DesktopBaseNativeImpl<*>)?.nativeWidget
        val explicitBottomNav = (windowImpl.nativeWidget.attributes["bottomNav"] as? DesktopBaseNativeImpl<*>)?.nativeWidget

        val unwrappedRoot = unwrapShellContainers(rootWidget)
        val unwrappedBg = (unwrappedRoot.attributes["backgroundColor"] as? RgbaColor)?.takeIf { it.a > 0 }
        if (unwrappedBg != null && unwrappedRoot !== rootWidget) {
            MacosGraphicsDrawUtil.fillRect(ctx, vp.x, vp.y, vp.width, vp.height, unwrappedBg)
        }

        val visibleRootChildren = visibleChildrenOf(unwrappedRoot).filter { it !in ctx.titleBarWidgets }

        val topNavbar = explicitNavbar?.takeIf { it.visible && !it.disposed }
            ?: visibleRootChildren.firstOrNull { MacosNavigationRenderUtil.isNavbarWidget(it) }
        val seamlessHeaderNavbar = topNavbar ?: findNestedTopNavbar(unwrappedRoot, visibleRootChildren)
        val bottomNav = explicitBottomNav?.takeIf { it.visible && !it.disposed }
            ?: visibleRootChildren.lastOrNull { MacosNavigationRenderUtil.isBottomNavWidget(it) }

        if (!ctx.explicitCustomTitleBar && ctx.customTitleBarInsetTop > 0 && seamlessHeaderNavbar != null) {
            val headerBg = (seamlessHeaderNavbar.attributes["backgroundColor"] as? RgbaColor)?.takeIf { it.a > 0 }
                ?: ctx.palette.bgHeader
            MacosGraphicsDrawUtil.fillRect(ctx, vp.x, contentY, vp.width, ctx.customTitleBarInsetTop, headerBg)
            contentY += ctx.customTitleBarInsetTop
        }

        if (topNavbar != null) {
            val navH = if (ctx.explicitCustomTitleBar && ctx.customTitleBarInsetTop > 0) {
                max(MacosNavigationRenderUtil.NAVBAR_HEIGHT_PX, ctx.customTitleBarInsetTop)
            } else {
                MacosNavigationRenderUtil.NAVBAR_HEIGHT_PX
            }
            val reserveTraffic = if (ctx.explicitCustomTitleBar && ctx.customTitleBarInsetTop > 0) 80 else 0
            MacosNavigationRenderUtil.renderNavbar(
                ctx = ctx,
                widget = topNavbar,
                bounds = Rect(vp.x, contentY, vp.width, navH),
                reserveTrafficLightsLeftPx = reserveTraffic
            )
            contentY += navH
        } else if (ctx.explicitCustomTitleBar && ctx.customTitleBarInsetTop > 0 && !isLayeredContainerWidget(unwrappedRoot)) {
            contentY += ctx.customTitleBarInsetTop
        }

        if (bottomNav != null && bottomNav !== topNavbar) {
            val bNavH = MacosNavigationRenderUtil.BOTTOM_NAV_HEIGHT_PX
            contentBottom -= bNavH
            MacosNavigationRenderUtil.renderBottomNavigationBar(
                ctx = ctx,
                widget = bottomNav,
                bounds = Rect(vp.x, contentBottom, vp.width, bNavH)
            )
        }

        val bodyRect = Rect(vp.x, contentY, vp.width, (contentBottom - contentY).coerceAtLeast(0))
        if (bodyRect.height <= 0) {
            flushDeferredOverlays(ctx)
            return
        }

        val remainingChildren = visibleRootChildren.filter { it !== topNavbar && it !== bottomNav }
        when {
            unwrappedRoot !== rootWidget && topNavbar == null && bottomNav == null -> {
                renderWidgetSubtree(ctx, unwrappedRoot, bodyRect)
            }
            remainingChildren.size == 1 -> {
                val single = unwrapShellContainers(remainingChildren[0])
                renderWidgetSubtree(ctx, single, bodyRect)
            }
            remainingChildren.size > 1 -> {
                renderFlexColumn(ctx, unwrappedRoot, remainingChildren, bodyRect, defaultGap = 0)
            }
            else -> {
                renderWidgetSubtree(ctx, rootWidget, bodyRect)
            }
        }

        flushDeferredOverlays(ctx)
    }

    fun renderDialogWindow(
        ctx: MacosRenderContext,
        dialogImpl: DesktopDialogWindowNativeImpl<*>
    ) {
        val vp = ctx.viewport
        MacosGraphicsDrawUtil.fillRect(ctx, vp.x, vp.y, vp.width, vp.height, ctx.palette.bgCard)

        val root = dialogImpl.nativeWidget
        root.bounds = vp
        val visibleKids = root.children.filter { it.visible && !it.disposed && it.attributes["visibilityHidden"] != true }
        if (visibleKids.isEmpty()) return

        val container = if (visibleKids.size == 1 && visibleKids[0].children.isNotEmpty() && !isLeafWidget(visibleKids[0])) {
            visibleKids[0]
        } else {
            root
        }
        val sections = container.children.filter { it.visible && !it.disposed && it.attributes["visibilityHidden"] != true }
        val header = sections.firstOrNull { "modal-header" in it.cachedAllClassTokens }
        val footer = sections.lastOrNull { "modal-footer" in it.cachedAllClassTokens && it !== header }
        val bodyItems = sections.filter { it !== header && it !== footer }

        var topY = vp.y
        var botY = vp.bottom

        if (header != null) {
            val hh = 48
            val hRect = Rect(vp.x, topY, vp.width, hh)
            header.bounds = hRect
            MacosGraphicsDrawUtil.fillRect(ctx, hRect.x, hRect.y, hRect.width, hRect.height, ctx.palette.bgHeader)
            MacosGraphicsDrawUtil.fillRect(ctx, hRect.x, hRect.bottom - 1, hRect.width, 1, ctx.palette.borderSubtle)
            renderFlexRow(ctx, header, visibleChildrenOf(header), insetRect(hRect, EdgeInsets(8, 16, 8, 16)), defaultGap = 8)
            topY += hh
        }

        if (footer != null) {
            val fh = 56
            botY -= fh
            val fRect = Rect(vp.x, botY, vp.width, fh)
            footer.bounds = fRect
            MacosGraphicsDrawUtil.fillRect(ctx, fRect.x, fRect.y, fRect.width, fRect.height, ctx.palette.bgHeader)
            MacosGraphicsDrawUtil.fillRect(ctx, fRect.x, fRect.y, fRect.width, 1, ctx.palette.borderSubtle)
            renderFlexRow(ctx, footer, visibleChildrenOf(footer), insetRect(fRect, EdgeInsets(10, 16, 10, 16)), defaultGap = 8, forceRightAlign = true)
        }

        val bodyRect = Rect(vp.x + 16, topY + 14, (vp.width - 32).coerceAtLeast(40), (botY - topY - 28).coerceAtLeast(24))
        if (bodyItems.size == 1) {
            renderWidgetSubtree(ctx, bodyItems[0], bodyRect)
        } else if (bodyItems.isNotEmpty()) {
            renderSimulatedScrollableColumn(ctx, container, bodyItems, bodyRect, gap = 10)
        }

        flushDeferredOverlays(ctx)
    }

    fun measureDialogIntrinsicSize(
        dialogWidget: DesktopNativeWidget,
        maxViewportWidth: Int = 860,
        maxViewportHeight: Int = 680
    ): Size {
        val w = 520.coerceAtMost(maxViewportWidth.coerceAtLeast(280))
        var estH = 36
        fun accumulateHeight(node: DesktopNativeWidget, depth: Int) {
            if (!node.visible || node.disposed || depth > 8) return
            if (node.attributes["isLazyList"] == true || node.attributes["nativeImpl"] is DesktopScrollViewNativeImpl<*>) {
                estH += 260
                return
            }
            if (isLeafWidget(node)) {
                estH += measurePreferredHeight(node, w - 48) + 8
                return
            }
            val isRow = isHorizontalRow(node)
            val kids = visibleChildrenOf(node)
            if (isRow && kids.isNotEmpty()) {
                val maxKid = kids.maxOfOrNull { measurePreferredHeight(it, (w - 48) / kids.size.coerceAtLeast(1)) } ?: 32
                estH += maxKid + 10
            } else {
                for (k in kids) accumulateHeight(k, depth + 1)
            }
        }
        accumulateHeight(dialogWidget, 0)
        val clampedH = estH.coerceIn(200, maxViewportHeight.coerceAtLeast(220))
        return Size(w, clampedH)
    }

    private fun unwrapShellContainers(start: DesktopNativeWidget): DesktopNativeWidget =
        unwrapShellContainersWithBounds(start, null)

    private fun unwrapShellContainersWithBounds(start: DesktopNativeWidget, assignBounds: Rect?): DesktopNativeWidget {
        var cur = start
        var depth = 0
        while (depth < 10) {
            if (assignBounds != null) {
                cur.bounds = assignBounds
            }
            depth++
            val tokens = cur.cachedAllClassTokens
            if (MacosNavigationRenderUtil.isNavbarWidget(cur) ||
                MacosNavigationRenderUtil.isTabContainerWidget(cur) ||
                MacosStageOverlayRenderUtil.isStageContainer(cur) ||
                "hybrid-column-layout" in tokens ||
                "gui-hybrid-columns-row" in tokens ||
                "gui-adaptive-card-layout" in tokens ||
                "gui-hybrid-split-navigation" in tokens ||
                "gui-collapsible-bottom-dock" in tokens ||
                "card" in tokens ||
                cur.attributes["isLazyList"] == true ||
                cur.attributes["nativeImpl"] is DesktopScrollViewNativeImpl<*>
            ) {
                return cur
            }
            val kids = visibleChildrenOf(cur)
            if (kids.size != 1) return cur
            val only = kids[0]
            if (isLeafWidget(only)) return cur
            // Stop unwrapping if current container explicitly centers a card in the viewport
            if (!isLayeredContainerWidget(cur) &&
                ((cur.attributes["alignItems"] == "center" && cur.attributes["justifyContent"] == "center") ||
                    ("align-items-center" in tokens && "justify-content-center" in tokens))
            ) {
                return cur
            }
            cur = only
        }
        if (assignBounds != null) {
            cur.bounds = assignBounds
        }
        return cur
    }

    @Suppress("UNCHECKED_CAST")
    fun renderWidgetSubtree(
        ctx: MacosRenderContext,
        widget: DesktopNativeWidget,
        bounds: Rect
    ) {
        if (!widget.visible || widget.disposed || bounds.width <= 0 || bounds.height <= 0) return
        if (widget.attributes["visibilityHidden"] == true || widget.attributes["data-modal-host"] == "true") {
            widget.bounds = bounds
            return
        }
        widget.bounds = bounds
        if (isSpacerWidget(widget)) return

        val savedOpacity = ctx.currentOpacity
        val localOpacity = widget.opacity.coerceIn(0f, 1f)
        if (localOpacity <= 0.001f) return
        ctx.currentOpacity = savedOpacity * localOpacity

        try {
            // 1. Navigation & Tabs
            if (MacosNavigationRenderUtil.isNavbarWidget(widget)) {
                val reserveTraffic = if (ctx.explicitCustomTitleBar && bounds.y <= 2 && bounds.x <= 2 && ctx.customTitleBarInsetTop > 0) 80 else 0
                val navBounds = if (ctx.explicitCustomTitleBar && bounds.y == 0 && ctx.customTitleBarInsetTop > bounds.height) {
                    Rect(bounds.x, bounds.y, bounds.width, ctx.customTitleBarInsetTop)
                } else {
                    bounds
                }
                MacosNavigationRenderUtil.renderNavbar(ctx, widget, navBounds, reserveTrafficLightsLeftPx = reserveTraffic)
                return
            }
            if (MacosNavigationRenderUtil.isTabContainerWidget(widget)) {
                MacosNavigationRenderUtil.renderTabContainer(ctx, widget, bounds, ::renderWidgetSubtree)
                return
            }
            if (MacosNavigationRenderUtil.isBottomNavWidget(widget)) {
                MacosNavigationRenderUtil.renderBottomNavigationBar(ctx, widget, bounds)
                return
            }

            // 2. Interactive Video / 3D Visualizer Stage + HUD Overlay
            if (MacosStageOverlayRenderUtil.isStageContainer(widget)) {
                MacosStageOverlayRenderUtil.renderStageContainer(ctx, widget, bounds, ::renderWidgetSubtree)
                return
            }

            // 3. Leaf Native Controls (delegated to per-component Macos*NativeImpl / MacosControlRenderUtil)
            val impl = widget.attributes["nativeImpl"]
            when (impl) {
                is DesktopButtonNativeImpl<*>,
                is DesktopStyledButtonNativeImpl<*>,
                is DesktopTextInputNativeImpl<*>,
                is DesktopCheckboxNativeImpl<*>,
                is DesktopSelectNativeImpl<*>,
                is DesktopDropdownNativeImpl<*>,
                is DesktopRangeSliderNativeImpl<*>,
                is DesktopProgressBarNativeImpl<*>,
                is DesktopSpinnerNativeImpl<*>,
                is DesktopTextNativeImpl<*>,
                is DesktopMarkdownViewNativeImpl<*>,
                is DesktopImageNativeImpl<*> -> {
                    MacosControlRenderUtil.renderLeafControl(impl, ctx, bounds)
                    return
                }
                is DesktopDividerNativeImpl<*> -> {
                    val horizontal = bounds.width >= bounds.height
                    if (horizontal) {
                        val y = bounds.y + bounds.height / 2
                        MacosGraphicsDrawUtil.fillRect(ctx, bounds.x, y, bounds.width, 1, ctx.palette.borderSubtle)
                    } else {
                        val x = bounds.x + bounds.width / 2
                        MacosGraphicsDrawUtil.fillRect(ctx, x, bounds.y, 1, bounds.height, ctx.palette.borderSubtle)
                    }
                    return
                }
            }

            // 4. Virtualized LazyList (LazyColumn / LazyRow)
            if (impl is DesktopLazyListNativeImpl<*> || widget.attributes["isLazyList"] == true) {
                renderLazyList(ctx, widget, impl as? DesktopLazyListNativeImpl<*>, bounds)
                return
            }

            // 5. Virtualized LazyGrid
            if (impl is DesktopLazyGridNativeImpl<*>) {
                renderLazyGrid(ctx, widget, impl, bounds)
                return
            }

            // 6. ScrollView
            if (impl is DesktopScrollViewNativeImpl<*>) {
                renderScrollView(ctx, widget, impl, bounds)
                return
            }

            // 7. Specialized Layout Fragments & Containers
            val tokens = widget.cachedAllClassTokens
            if ("gui-adaptive-card-layout" in tokens || isCenteredCardWrapper(widget)) {
                renderCenteredCardLayout(ctx, widget, bounds)
                return
            }
            if ("hybrid-column-layout" in tokens || "gui-hybrid-columns-row" in tokens) {
                renderHybridColumnLayout(ctx, widget, bounds)
                return
            }
            if ("gui-hybrid-split-navigation" in tokens) {
                renderHybridSplitNavigation(ctx, widget, bounds)
                return
            }
            if ("gui-collapsible-bottom-dock" in tokens) {
                renderCollapsibleBottomDock(ctx, widget, bounds)
                return
            }
            if (isLayeredContainerWidget(widget)) {
                renderLayeredContainer(ctx, widget, bounds)
                return
            }

            // 8. Card / Alert / StyledContainer / Standard Flex Container
            renderContainerBackgroundAndBorder(ctx, widget, bounds)

            val pad = resolveEffectivePadding(widget, tokens)
            val innerBounds = insetRect(bounds, pad)
            if (innerBounds.width <= 0 || innerBounds.height <= 0) return

            val kids = visibleChildrenOf(widget)
            if (kids.isEmpty()) return

            // Layered container check (absolute children stacked over base)
            val hasAbsoluteChildren = kids.any {
                it.attributes["position"] == "absolute" || "position-absolute" in it.cachedAllClassTokens
            }
            if (hasAbsoluteChildren && kids.size > 1) {
                val sorted = sortLayeredChildren(kids)
                for (child in sorted) {
                    renderWidgetSubtree(ctx, child, innerBounds)
                }
                return
            }

            if ("gui-split-nav-content" in tokens && kids.size == 1) {
                renderWidgetSubtree(ctx, kids[0], innerBounds)
                return
            }

            val isRow = isHorizontalRow(widget)
            val fallbackGap = if (isRow) {
                if (kids.any { isSpacerWidget(it) || it.margin.left > 0 || it.margin.right > 0 }) 0 else 6
            } else {
                val isFlex = widget.attributes["display"] == "flex" || "d-flex" in tokens || "flex-column" in tokens || "vstack" in tokens
                if (isFlex) 0 else 4
            }
            val gap = resolveGapPx(widget, defaultGap = fallbackGap)
            if (isRow) {
                renderFlexRow(ctx, widget, kids, innerBounds, defaultGap = gap)
            } else {
                renderFlexColumn(ctx, widget, kids, innerBounds, defaultGap = gap)
            }
        } finally {
            ctx.currentOpacity = savedOpacity
        }
    }

    private fun renderLayeredContainer(
        ctx: MacosRenderContext,
        widget: DesktopNativeWidget,
        bounds: Rect
    ) {
        renderContainerBackgroundAndBorder(ctx, widget, bounds)
        val inner = insetRect(bounds, resolveEffectivePadding(widget, widget.cachedAllClassTokens))
        if (inner.width <= 0 || inner.height <= 0) return
        val layers = sortLayeredChildren(visibleChildrenOf(widget))
        for (layer in layers) {
            renderWidgetSubtree(ctx, layer, inner)
        }
    }

    private fun sortLayeredChildren(kids: List<DesktopNativeWidget>): List<DesktopNativeWidget> {
        if (kids.size <= 1) return kids
        return kids.sortedBy { child ->
            (child.attributes["layerZIndex"] as? Number)?.toInt()
                ?: (child.attributes["layerZIndex"]?.toString()?.toIntOrNull())
                ?: (child.attributes["zIndex"] as? Number)?.toInt()
                ?: (child.attributes["zIndex"]?.toString()?.toIntOrNull())
                ?: 0
        }
    }

    private fun renderCenteredCardLayout(
        ctx: MacosRenderContext,
        widget: DesktopNativeWidget,
        bounds: Rect
    ) {
        val kids = visibleChildrenOf(widget)
        val target = kids.firstOrNull() ?: return
        val maxCardW = resolveCssLengthPx(target.attributes["styleMaxWidth"], bounds.width)
            ?: resolveCssLengthPx(widget.attributes["styleMaxWidth"], bounds.width)
            ?: 480
        val cardW = min(maxCardW, (bounds.width - 32).coerceAtLeast(240))
        val intrinsicH = measureSubtreeHeight(target, cardW).coerceIn(140, (bounds.height - 32).coerceAtLeast(160))
        val cx = bounds.x + (bounds.width - cardW) / 2
        val cy = bounds.y + ((bounds.height - intrinsicH) / 2).coerceAtLeast(16)
        val cardRect = Rect(cx, cy, cardW, intrinsicH)

        // Ensure card surface is drawn even if inner wrapper wasn't explicitly marked `.card`
        if ("card" !in target.cachedAllClassTokens && target.attributes["backgroundColor"] == null) {
            MacosGraphicsDrawUtil.fillRoundedRect(ctx, cardRect.x, cardRect.y, cardRect.width, cardRect.height, 12f, ctx.palette.bgCard)
            MacosGraphicsDrawUtil.strokeRoundedRect(ctx, cardRect.x, cardRect.y, cardRect.width, cardRect.height, 12f, ctx.palette.borderSubtle, 1f)
            renderWidgetSubtree(ctx, target, insetRect(cardRect, EdgeInsets(20, 22, 20, 22)))
        } else {
            renderWidgetSubtree(ctx, target, cardRect)
        }
    }

    private fun renderHybridColumnLayout(
        ctx: MacosRenderContext,
        widget: DesktopNativeWidget,
        bounds: Rect
    ) {
        renderContainerBackgroundAndBorder(ctx, widget, bounds)
        val pad = resolveEffectivePadding(widget, widget.cachedAllClassTokens)
        val inner = insetRect(bounds, if (pad == EdgeInsets.ZERO) EdgeInsets(8, 12, 8, 12) else pad)
        if (inner.width <= 0 || inner.height <= 0) return

        if ("gui-hybrid-columns-row" !in widget.cachedAllClassTokens) {
            val visKids = visibleChildrenOf(widget)
            val colsRow = visKids.firstOrNull { "gui-hybrid-columns-row" in it.cachedAllClassTokens }
            val singleStack = visKids.firstOrNull { "gui-hybrid-single-stack" in it.cachedAllClassTokens }
            if (colsRow == null && singleStack != null) {
                renderWidgetSubtree(ctx, singleStack, inner)
                return
            }
        }

        val columnsContainer = if ("gui-hybrid-columns-row" in widget.cachedAllClassTokens) {
            widget
        } else {
            visibleChildrenOf(widget).firstOrNull {
                "gui-hybrid-columns-row" in it.cachedAllClassTokens || "row" in it.cachedAllClassTokens
            } ?: widget
        }

        val cols = visibleChildrenOf(columnsContainer)
        if (cols.isEmpty()) return
        if (cols.size == 1 || inner.width < 820) {
            if (cols.size == 1) {
                renderWidgetSubtree(ctx, cols[0], inner)
            } else {
                renderFlexColumn(ctx, columnsContainer, cols, inner, defaultGap = 10)
            }
            return
        }

        val gap = resolveGapPx(columnsContainer, defaultGap = 12).coerceAtLeast(8)
        val vpWidth = ctx.viewport.width.coerceAtLeast(inner.width)
        val spans = IntArray(cols.size) { idx -> parseBootstrapColSpan(cols[idx].cachedAllClassTokens, vpWidth) }
        val totalSpan = spans.sum().coerceAtLeast(cols.size)
        val availW = (inner.width - gap * (cols.size - 1)).coerceAtLeast(cols.size * 80)
        var curX = inner.x
        for ((idx, col) in cols.withIndex()) {
            val span = spans[idx]
            val cw = if (idx == cols.lastIndex) {
                (inner.right - curX).coerceAtLeast(80)
            } else {
                ((availW.toFloat() * span.toFloat()) / totalSpan.toFloat()).roundToInt().coerceAtLeast(180)
            }
            val colRect = Rect(curX, inner.y, cw, inner.height)
            renderWidgetSubtree(ctx, col, colRect)
            curX += cw + gap
        }
    }

    private fun renderHybridSplitNavigation(
        ctx: MacosRenderContext,
        widget: DesktopNativeWidget,
        bounds: Rect
    ) {
        renderContainerBackgroundAndBorder(ctx, widget, bounds)
        val pad = resolveEffectivePadding(widget, widget.cachedAllClassTokens)
        val inner = insetRect(bounds, pad)
        if (inner.width <= 0 || inner.height <= 0) return

        val kids = visibleChildrenOf(widget)
        if (kids.isEmpty()) return

        var topY = inner.y
        var botY = inner.bottom

        val topTab = kids.firstOrNull { MacosNavigationRenderUtil.isTabContainerWidget(it) }
        val bottomNav = kids.lastOrNull { MacosNavigationRenderUtil.isBottomNavWidget(it) }
        val headerSlot = kids.firstOrNull { "gui-split-nav-header" in it.cachedAllClassTokens }
        val contentHost = kids.firstOrNull { "gui-split-nav-content" in it.cachedAllClassTokens }

        if (topTab != null) {
            val stripH = (MacosNavigationRenderUtil.TAB_BAR_HEIGHT_PX + 4).coerceAtMost(inner.height)
            MacosNavigationRenderUtil.renderTabBarStrip(
                ctx = ctx,
                widget = topTab,
                bounds = Rect(inner.x, topY, inner.width, stripH)
            )
            topY += stripH + 4
        }

        if (headerSlot != null && visibleChildrenOf(headerSlot).isNotEmpty() && topY < botY - 28) {
            val hh = measureSubtreeHeight(headerSlot, inner.width).coerceIn(34, 96)
            val headerRect = Rect(inner.x, topY, inner.width, hh.coerceAtMost(botY - topY))
            renderWidgetSubtree(ctx, headerSlot, headerRect)
            topY += headerRect.height + 4
        }

        if (bottomNav != null && bottomNav !== topTab && botY - topY > MacosNavigationRenderUtil.BOTTOM_NAV_HEIGHT_PX) {
            val bNavH = MacosNavigationRenderUtil.BOTTOM_NAV_HEIGHT_PX
            botY -= bNavH
            MacosNavigationRenderUtil.renderBottomNavigationBar(
                ctx = ctx,
                widget = bottomNav,
                bounds = Rect(inner.x, botY, inner.width, bNavH)
            )
        }

        val contentRect = Rect(inner.x, topY, inner.width, (botY - topY).coerceAtLeast(0))
        if (contentRect.height <= 0) return

        if (contentHost != null) {
            renderWidgetSubtree(ctx, contentHost, contentRect)
        } else {
            val rest = kids.filter { it !== topTab && it !== headerSlot && it !== bottomNav }
            if (rest.size == 1) {
                renderWidgetSubtree(ctx, rest[0], contentRect)
            } else if (rest.isNotEmpty()) {
                renderFlexColumn(ctx, widget, rest, contentRect, defaultGap = 4)
            }
        }
    }

    private fun hasVisibleDockContent(dockNode: DesktopNativeWidget?): Boolean {
        if (dockNode == null || !dockNode.visible || dockNode.disposed) return false
        if (isLeafWidget(dockNode)) return true
        val slots = visibleChildrenOf(dockNode)
        if (slots.isEmpty()) return false
        return slots.any { slot ->
            isLeafWidget(slot) || visibleChildrenOf(slot).isNotEmpty()
        }
    }

    private fun renderCollapsibleBottomDock(
        ctx: MacosRenderContext,
        widget: DesktopNativeWidget,
        bounds: Rect
    ) {
        val kids = visibleChildrenOf(widget)
        if (kids.isEmpty()) return
        val mainHost = kids.firstOrNull { "gui-bottom-dock-main-content" in it.cachedAllClassTokens } ?: kids.first()
        val dockNode = kids.lastOrNull { it !== mainHost && ("gui-bottom-dock-bar" in it.cachedAllClassTokens || kids.size >= 2) }

        if (!hasVisibleDockContent(dockNode)) {
            renderWidgetSubtree(ctx, mainHost, bounds)
            return
        }

        val dock = dockNode!!
        val dockH = measureSubtreeHeight(dock, bounds.width).coerceIn(52, (bounds.height * 0.36f).roundToInt().coerceAtLeast(60))
        val mainRect = Rect(bounds.x, bounds.y, bounds.width, (bounds.height - dockH - 1).coerceAtLeast(60))
        val dockRect = Rect(bounds.x, bounds.bottom - dockH, bounds.width, dockH)
        renderWidgetSubtree(ctx, mainHost, mainRect)
        MacosGraphicsDrawUtil.fillRect(ctx, dockRect.x, dockRect.y - 1, dockRect.width, 1, ctx.palette.borderSubtle)
        MacosGraphicsDrawUtil.fillRect(ctx, dockRect.x, dockRect.y, dockRect.width, dockRect.height, ctx.palette.bgPanel)
        renderWidgetSubtree(ctx, dock, dockRect)
    }

    private fun renderScrollView(
        ctx: MacosRenderContext,
        widget: DesktopNativeWidget,
        impl: DesktopScrollViewNativeImpl<DesktopNativeWidget>,
        bounds: Rect
    ) {
        renderContainerBackgroundAndBorder(ctx, widget, bounds)
        val pad = resolveEffectivePadding(widget, widget.cachedAllClassTokens)
        val viewport = insetRect(bounds, pad)
        if (viewport.width <= 0 || viewport.height <= 0) return

        val kids = visibleChildrenOf(widget)
        if (kids.isEmpty()) return

        // If single child is itself a LazyList, hand full viewport directly to LazyList
        if (kids.size == 1 && (kids[0].attributes["isLazyList"] == true || kids[0].attributes["nativeImpl"] is DesktopLazyListNativeImpl<*>)) {
            renderWidgetSubtree(ctx, kids[0], viewport)
            return
        }

        val gap = resolveGapPx(widget, 8)
        val itemHeights = IntArray(kids.size) { idx ->
            measureSubtreeHeight(kids[idx], viewport.width - 8)
        }
        val totalContentH = itemHeights.sum() + gap * (kids.size - 1).coerceAtLeast(0)
        val maxScroll = (totalContentH - viewport.height).coerceAtLeast(0)
        val clampedScroll = impl.currentScrollY().coerceIn(0, maxScroll)
        if (clampedScroll != impl.currentScrollY()) {
            impl.updateScrollPositionFromHost(impl.currentScrollX(), clampedScroll)
        }

        ctx.registerScrollRegion(
            MacosScrollRegion(
                left = viewport.x,
                top = viewport.y,
                right = viewport.right,
                bottom = viewport.bottom,
                widget = widget,
                viewportHeight = viewport.height,
                contentHeight = totalContentH,
                currentScrollY = clampedScroll.toFloat(),
                onScrollBy = { deltaY ->
                    if (maxScroll <= 0) {
                        false
                    } else {
                        val next = (impl.currentScrollY() + deltaY.roundToInt()).coerceIn(0, maxScroll)
                        val changed = next != impl.currentScrollY()
                        if (changed) {
                            impl.updateScrollPositionFromHost(impl.currentScrollX(), next)
                        }
                        changed
                    }
                }
            )
        )

        val contentW = if (maxScroll > 0) (viewport.width - 8).coerceAtLeast(24) else viewport.width
        MacosGraphicsDrawUtil.withClip(ctx, viewport.x, viewport.y, viewport.width, viewport.height) {
            var curY = viewport.y - clampedScroll
            for ((i, child) in kids.withIndex()) {
                val ch = itemHeights[i]
                if (curY + ch >= viewport.y && curY <= viewport.bottom) {
                    renderWidgetSubtree(ctx, child, Rect(viewport.x, curY, contentW, ch))
                }
                curY += ch + gap
            }
            if (maxScroll > 0) {
                drawOverlayScrollbar(ctx, viewport, clampedScroll.toFloat(), totalContentH.toFloat())
            }
        }
    }

    private fun renderSimulatedScrollableColumn(
        ctx: MacosRenderContext,
        ownerWidget: DesktopNativeWidget,
        kids: List<DesktopNativeWidget>,
        viewport: Rect,
        gap: Int
    ) {
        val itemHeights = IntArray(kids.size) { idx -> measureSubtreeHeight(kids[idx], viewport.width - 8) }
        val totalH = itemHeights.sum() + gap * (kids.size - 1).coerceAtLeast(0)
        val maxScroll = (totalH - viewport.height).coerceAtLeast(0).toFloat()
        val key = ownerWidget.nativePointer
        val scrollY = (hostScrollOffsets[key] ?: 0f).coerceIn(0f, maxScroll)
        hostScrollOffsets[key] = scrollY

        if (maxScroll > 0f) {
            ctx.registerScrollRegion(
                MacosScrollRegion(
                    left = viewport.x,
                    top = viewport.y,
                    right = viewport.right,
                    bottom = viewport.bottom,
                    widget = ownerWidget,
                    viewportHeight = viewport.height,
                    contentHeight = totalH,
                    currentScrollY = scrollY,
                    onScrollBy = { dy ->
                        val prev = hostScrollOffsets[key] ?: 0f
                        val next = (prev + dy).coerceIn(0f, maxScroll)
                        hostScrollOffsets[key] = next
                        next != prev
                    }
                )
            )
        }

        MacosGraphicsDrawUtil.withClip(ctx, viewport.x, viewport.y, viewport.width, viewport.height) {
            var curY = viewport.y - scrollY.roundToInt()
            val w = if (maxScroll > 0f) (viewport.width - 8).coerceAtLeast(24) else viewport.width
            for ((i, child) in kids.withIndex()) {
                val ch = itemHeights[i]
                if (curY + ch >= viewport.y && curY <= viewport.bottom) {
                    renderWidgetSubtree(ctx, child, Rect(viewport.x, curY, w, ch))
                }
                curY += ch + gap
            }
            if (maxScroll > 0f) {
                drawOverlayScrollbar(ctx, viewport, scrollY, totalH.toFloat())
            }
        }
    }

    private fun isTruthyAttr(raw: Any?): Boolean = when (raw) {
        is Boolean -> raw
        is String -> raw.equals("true", ignoreCase = true)
        is Number -> raw.toInt() != 0
        else -> false
    }

    private fun renderLazyList(
        ctx: MacosRenderContext,
        widget: DesktopNativeWidget,
        lazyImpl: DesktopLazyListNativeImpl<*>?,
        bounds: Rect
    ) {
        renderContainerBackgroundAndBorder(ctx, widget, bounds)
        val viewport = insetRect(bounds, resolveEffectivePadding(widget, widget.cachedAllClassTokens))
        if (viewport.width <= 0 || viewport.height <= 0) return

        val key = widget.nativePointer
        val syncedVer = (widget.attributes["hostSyncedScrollVer"] as? Long) ?: 0L
        val lastAppliedVer = (widget.attributes["_macosAppliedScrollVer"] as? Long) ?: -1L
        if (syncedVer != lastAppliedVer) {
            widget.attributes["_macosAppliedScrollVer"] = syncedVer
            val syncedPx = (widget.attributes["hostSyncedScrollOffsetPx"] as? Number)?.toFloat() ?: 0f
            hostScrollOffsets[key] = syncedPx
        }

        val currentScroll = (hostScrollOffsets[key] ?: 0f).coerceAtLeast(0f)
        val totalItemCount = (widget.attributes["totalItemCount"] as? Number)?.toInt() ?: -1
        val lastDispScroll = (widget.attributes["_macosLastDispatchedScrollPx"] as? Float) ?: -9999f
        val lastDispVpH = (widget.attributes["_macosLastDispatchedVpH"] as? Int) ?: -1
        val lastDispCount = (widget.attributes["_macosLastDispatchedCount"] as? Int) ?: -2
        if (abs(currentScroll - lastDispScroll) > 0.5f || lastDispVpH != viewport.height || lastDispCount != totalItemCount) {
            widget.attributes["_macosLastDispatchedScrollPx"] = currentScroll
            widget.attributes["_macosLastDispatchedVpH"] = viewport.height
            widget.attributes["_macosLastDispatchedCount"] = totalItemCount
            lazyImpl?.dispatchHostViewportScroll(currentScroll, viewport.height.toFloat())
        }

        val estExtent = ((widget.attributes["estimatedItemExtentPx"] as? Number)?.toFloat() ?: 56f).coerceAtLeast(28f)
        val leadingPx = (widget.attributes["leadingSpacerPx"] as? Number)?.toFloat() ?: 0f
        val materialized = visibleChildrenOf(widget)
        val totalExtent = max(
            ((widget.attributes["totalContentExtentPx"] as? Number)?.toFloat() ?: 0f),
            leadingPx + materialized.size * estExtent
        )
        val maxScroll = (totalExtent - viewport.height.toFloat()).coerceAtLeast(0f)
        val clampedScroll = currentScroll.coerceIn(0f, maxScroll)
        hostScrollOffsets[key] = clampedScroll

        ctx.registerScrollRegion(
            MacosScrollRegion(
                left = viewport.x,
                top = viewport.y,
                right = viewport.right,
                bottom = viewport.bottom,
                widget = widget,
                viewportHeight = viewport.height,
                contentHeight = totalExtent.roundToInt(),
                currentScrollY = clampedScroll,
                onScrollBy = { deltaY ->
                    if (maxScroll <= 0f) {
                        false
                    } else {
                        val next = ((hostScrollOffsets[key] ?: 0f) + deltaY).coerceIn(0f, maxScroll)
                        val changed = abs(next - (hostScrollOffsets[key] ?: 0f)) > 0.25f
                        if (changed) {
                            hostScrollOffsets[key] = next
                            widget.attributes["_macosLastDispatchedScrollPx"] = next
                            widget.attributes["_macosLastDispatchedVpH"] = viewport.height
                            lazyImpl?.dispatchHostViewportScroll(next, viewport.height.toFloat())
                        }
                        changed
                    }
                }
            )
        )

        val rowW = if (maxScroll > 0f) (viewport.width - 8).coerceAtLeast(32) else viewport.width
        MacosGraphicsDrawUtil.withClip(ctx, viewport.x, viewport.y, viewport.width, viewport.height) {
            if (materialized.isEmpty()) {
                return@withClip
            }
            var yCursor = viewport.y + (leadingPx - clampedScroll).roundToInt()
            for (rowWidget in materialized) {
                val isHeader = isTruthyAttr(rowWidget.attributes["rowHeader"]) ||
                    "list-category-header" in rowWidget.cachedAllClassTokens
                val defaultRowH = if (isHeader) 34 else estExtent.roundToInt().coerceIn(44, 72)
                val rowH = resolveCssLengthPx(rowWidget.attributes["styleHeight"], viewport.height)
                    ?: rowWidget.preferredSize?.height?.takeIf { it > 0 }
                    ?: defaultRowH
                val rowRect = Rect(viewport.x, yCursor, rowW, rowH)
                if (rowRect.bottom >= viewport.y && rowRect.y <= viewport.bottom) {
                    renderCompositeListRow(ctx, rowWidget, rowRect)
                }
                yCursor += rowH
            }
            if (maxScroll > 0f) {
                drawOverlayScrollbar(ctx, viewport, clampedScroll, totalExtent)
            }
        }
    }

    private fun renderLazyGrid(
        ctx: MacosRenderContext,
        widget: DesktopNativeWidget,
        gridImpl: DesktopLazyGridNativeImpl<DesktopNativeWidget>,
        bounds: Rect
    ) {
        MacosControlRenderUtil.renderLazyGrid(gridImpl, 160, ctx, bounds)
        val cols = ((widget.attributes["effectiveColumns"] as? Int) ?: gridImpl.getColumnCount()).coerceAtLeast(1)
        val viewport = insetRect(bounds, resolveEffectivePadding(widget, widget.cachedAllClassTokens))
        val items = visibleChildrenOf(widget)
        if (items.isEmpty() || viewport.width <= 0 || viewport.height <= 0) return

        val gap = 10
        val cellW = ((viewport.width - 8 - gap * (cols - 1)) / cols).coerceAtLeast(64)
        val cellH = ((widget.attributes["estimatedItemExtentPx"] as? Number)?.toInt() ?: 160).coerceAtLeast(64)
        val rowsCount = (items.size + cols - 1) / cols
        val totalH = rowsCount * cellH + (rowsCount - 1).coerceAtLeast(0) * gap
        val maxScroll = (totalH - viewport.height).coerceAtLeast(0).toFloat()
        val key = widget.nativePointer
        val scrollY = (hostScrollOffsets[key] ?: 0f).coerceIn(0f, maxScroll)
        hostScrollOffsets[key] = scrollY

        ctx.registerScrollRegion(
            MacosScrollRegion(
                left = viewport.x,
                top = viewport.y,
                right = viewport.right,
                bottom = viewport.bottom,
                widget = widget,
                viewportHeight = viewport.height,
                contentHeight = totalH,
                currentScrollY = scrollY,
                onScrollBy = { dy ->
                    val next = ((hostScrollOffsets[key] ?: 0f) + dy).coerceIn(0f, maxScroll)
                    val changed = next != (hostScrollOffsets[key] ?: 0f)
                    hostScrollOffsets[key] = next
                    changed
                }
            )
        )

        MacosGraphicsDrawUtil.withClip(ctx, viewport.x, viewport.y, viewport.width, viewport.height) {
            for ((idx, item) in items.withIndex()) {
                val r = idx / cols
                val c = idx % cols
                val ix = viewport.x + c * (cellW + gap)
                val iy = viewport.y - scrollY.roundToInt() + r * (cellH + gap)
                if (iy + cellH >= viewport.y && iy <= viewport.bottom) {
                    renderWidgetSubtree(ctx, item, Rect(ix, iy, cellW, cellH))
                }
            }
            if (maxScroll > 0f) {
                drawOverlayScrollbar(ctx, viewport, scrollY, totalH.toFloat())
            }
        }
    }

    private fun renderCompositeListRow(
        ctx: MacosRenderContext,
        rowWidget: DesktopNativeWidget,
        bounds: Rect
    ) {
        rowWidget.bounds = bounds
        val rowImpl = rowWidget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
        val tokens = rowWidget.cachedAllClassTokens
        val isHeader = isTruthyAttr(rowWidget.attributes["rowHeader"]) || "list-category-header" in tokens
        val isIndented = isTruthyAttr(rowWidget.attributes["rowIndented"]) || "native-row-subitem" in tokens
        val isDisabled = isTruthyAttr(rowWidget.attributes["rowDisabled"]) || "disabled" in tokens
        val isSelected = "active" in tokens || "selected" in tokens || isTruthyAttr(rowWidget.attributes["selected"])
        val isHovered = !isDisabled &&
            (ctx.hoveredWidget === rowWidget || rowImpl?.isHoveredState() == true || "row-hovered" in tokens)

        val savedOp = ctx.currentOpacity
        if (isDisabled) {
            ctx.currentOpacity = savedOp * 0.52f
        }
        try {
            val bg = when {
                isSelected -> ctx.palette.bgActiveRow
                isHovered && !isHeader -> ctx.palette.bgHover
                (rowWidget.attributes["backgroundColor"] as? RgbaColor)?.let { it.a > 0 } == true ->
                    rowWidget.attributes["backgroundColor"] as RgbaColor
                else -> null
            }
            if (bg != null) {
                MacosGraphicsDrawUtil.fillRoundedRect(ctx, bounds.x + 2, bounds.y + 1, bounds.width - 4, bounds.height - 2, 6f, bg)
            }
            if (!isHeader) {
                MacosGraphicsDrawUtil.fillRect(ctx, bounds.x + 8, bounds.bottom - 1, (bounds.width - 16).coerceAtLeast(0), 1, ctx.palette.borderSubtle)
            }

            // Register row base click BEFORE rendering trailing child buttons so trailing buttons take priority
            if (rowImpl != null && !isDisabled && (rowImpl.hasClickListener() || !isHeader)) {
                ctx.registerHitRegion(
                    MacosHitRegion(
                        left = bounds.x,
                        top = bounds.y,
                        right = bounds.right,
                        bottom = bounds.bottom,
                        widget = rowWidget,
                        tooltip = rowWidget.tooltipText,
                        cursorCss = if (rowImpl.hasClickListener()) "pointer" else null,
                        onClick = if (rowImpl.hasClickListener()) {
                            { rowImpl.dispatchClick() }
                        } else null,
                        onDoubleClick = { rowImpl.dispatchDoubleClick() },
                        onContextMenu = { wx, wy -> rowImpl.performContextMenu(wx, wy) }
                    )
                )
            }

            val leftInset = if (isIndented) 26 else 10
            val inner = Rect(bounds.x + leftInset, bounds.y + 4, (bounds.width - leftInset - 10).coerceAtLeast(24), (bounds.height - 8).coerceAtLeast(16))

            if (isLeafWidget(rowWidget)) {
                renderWidgetSubtree(ctx, rowWidget, inner)
                return
            }

            val kids = visibleChildrenOf(rowWidget)
            if (kids.isEmpty()) return

            if (isHorizontalRow(rowWidget) || kids.size >= 2) {
                val defaultGap = if (kids.any { isSpacerWidget(it) }) 0 else 8
                renderFlexRow(ctx, rowWidget, kids, inner, defaultGap = defaultGap)
            } else {
                renderFlexColumn(ctx, rowWidget, kids, inner, defaultGap = 2)
            }
        } finally {
            ctx.currentOpacity = savedOp
        }
    }

    private fun renderFlexColumn(
        ctx: MacosRenderContext,
        parent: DesktopNativeWidget,
        kids: List<DesktopNativeWidget>,
        bounds: Rect,
        defaultGap: Int
    ) {
        if (kids.isEmpty() || bounds.width <= 0 || bounds.height <= 0) return
        val gap = resolveGapPx(parent, defaultGap)
        val totalGaps = gap * (kids.size - 1).coerceAtLeast(0)
        val availForFixed = (bounds.height - totalGaps).coerceAtLeast(0)

        val fixedHeights = IntArray(kids.size)
        val growWeights = FloatArray(kids.size)
        var fixedSum = 0
        var growSum = 0f

        for ((i, child) in kids.withIndex()) {
            val m = child.margin
            val grow = resolveFlexGrow(child, parentIsRow = false, siblingCount = kids.size)
            if (grow > 0f) {
                growWeights[i] = grow
                growSum += grow
                fixedSum += m.top + m.bottom
            } else {
                val childAvailH = (availForFixed - m.top - m.bottom).coerceAtLeast(0)
                val ch = measureSubtreeHeight(
                    widget = child,
                    availWidth = (bounds.width - m.left - m.right).coerceAtLeast(24),
                    availHeight = childAvailH
                )
                fixedHeights[i] = ch
                fixedSum += ch + m.top + m.bottom
            }
        }

        // If fixed height overflows (e.g. a 16:9 stage + bottom info rows in a short window), shrink stage/aspect items first
        if (fixedSum > availForFixed) {
            var overflow = fixedSum - availForFixed
            for (i in kids.indices) {
                if (overflow <= 0) break
                if (growWeights[i] <= 0f && isVerticallyCompressibleStage(kids[i]) && fixedHeights[i] > 140) {
                    val cut = min(overflow, fixedHeights[i] - 140)
                    fixedHeights[i] -= cut
                    fixedSum -= cut
                    overflow -= cut
                }
            }
        }

        val remainingH = (bounds.height - totalGaps - fixedSum).coerceAtLeast(0)
        val justify = parent.attributes["justifyContent"] as? String
        var curY = when {
            growSum > 0f -> bounds.y
            justify == "center" -> bounds.y + remainingH / 2
            justify == "flex-end" -> bounds.y + remainingH
            else -> bounds.y
        }
        val extraBetween = if (growSum <= 0f && justify == "space-between" && kids.size > 1) {
            remainingH / (kids.size - 1)
        } else {
            0
        }

        for ((i, child) in kids.withIndex()) {
            val m = child.margin
            curY += m.top
            val minGrowH = if (isSpacerWidget(child)) resolveSpacerFixedHeight(child) else 24
            val h = if (growWeights[i] > 0f && growSum > 0f) {
                ((remainingH * growWeights[i]) / growSum).roundToInt().coerceAtLeast(minGrowH)
            } else {
                fixedHeights[i].coerceAtMost((bounds.bottom - curY).coerceAtLeast(0))
            }

            val alignItems = parent.attributes["alignItems"] as? String
            val maxChildW = (bounds.width - m.left - m.right).coerceAtLeast(16)
            val explicitW = resolveCssLengthPx(child.attributes["styleWidth"], maxChildW)
                ?: child.preferredSize?.width?.takeIf { it in 1..maxChildW }
            val childW = when {
                explicitW != null -> explicitW
                isSpacerWidget(child) -> maxChildW
                (alignItems == "center" || alignItems == "flex-start" || alignItems == "flex-end") &&
                    "w-100" !in child.cachedAllClassTokens &&
                    (child.attributes["widthFraction"] as? Float ?: 0f) < 0.99f ->
                    measurePreferredWidth(child, maxChildW).coerceAtMost(maxChildW)
                else -> maxChildW
            }
            val childX = when (alignItems) {
                "center" -> bounds.x + m.left + (maxChildW - childW) / 2
                "flex-end" -> bounds.right - m.right - childW
                else -> bounds.x + m.left
            }

            if (h > 0 && childW > 0) {
                renderWidgetSubtree(ctx, child, Rect(childX, curY, childW, h))
            }
            curY += h + m.bottom + gap + extraBetween
        }
    }

    private fun renderFlexRow(
        ctx: MacosRenderContext,
        parent: DesktopNativeWidget,
        kids: List<DesktopNativeWidget>,
        bounds: Rect,
        defaultGap: Int,
        forceRightAlign: Boolean = false
    ) {
        if (kids.isEmpty() || bounds.width <= 0 || bounds.height <= 0) return
        val gap = resolveGapPx(parent, defaultGap)
        val totalGaps = gap * (kids.size - 1).coerceAtLeast(0)

        val fixedWidths = IntArray(kids.size)
        val growWeights = FloatArray(kids.size)
        var fixedSum = 0
        var growSum = 0f

        for ((i, child) in kids.withIndex()) {
            val m = child.margin
            val grow = resolveFlexGrow(child, parentIsRow = true, siblingCount = kids.size)
            if (grow > 0f) {
                growWeights[i] = grow
                growSum += grow
                fixedSum += m.left + m.right
            } else {
                val w = measurePreferredWidth(child, bounds.width)
                fixedWidths[i] = w
                fixedSum += w + m.left + m.right
            }
        }

        // Shrink flexible text/container items first when fixed items exceed available row width
        // so icons and trailing action buttons never get clipped off the right edge!
        val availForFixed = (bounds.width - totalGaps).coerceAtLeast(0)
        if (fixedSum > availForFixed && fixedSum > 0) {
            var overflow = fixedSum - availForFixed
            val shrinkableIndices = kids.indices.filter { idx ->
                growWeights[idx] <= 0f && canShrinkHorizontally(kids[idx]) && fixedWidths[idx] > 32
            }
            val shrinkableTotal = shrinkableIndices.sumOf { (fixedWidths[it] - 32).coerceAtLeast(0) }
            if (shrinkableTotal > 0) {
                for (idx in shrinkableIndices) {
                    val cap = (fixedWidths[idx] - 32).coerceAtLeast(0)
                    val cut = min(cap, ((overflow.toFloat() * cap.toFloat()) / shrinkableTotal.toFloat()).roundToInt())
                    fixedWidths[idx] -= cut
                    fixedSum -= cut
                }
                overflow = (fixedSum - availForFixed).coerceAtLeast(0)
                if (overflow > 0) {
                    for (idx in shrinkableIndices) {
                        if (overflow <= 0) break
                        val extraCut = min(overflow, (fixedWidths[idx] - 24).coerceAtLeast(0))
                        fixedWidths[idx] -= extraCut
                        fixedSum -= extraCut
                        overflow -= extraCut
                    }
                }
            }
            if (fixedSum > availForFixed && fixedSum > 0 && availForFixed > 0) {
                val scale = availForFixed.toFloat() / fixedSum.toFloat()
                fixedSum = 0
                for (i in kids.indices) {
                    if (growWeights[i] <= 0f) {
                        val minW = if (isSpacerWidget(kids[i])) 0 else 12
                        fixedWidths[i] = (fixedWidths[i] * scale).roundToInt().coerceAtLeast(minW)
                    }
                    fixedSum += fixedWidths[i] + kids[i].margin.left + kids[i].margin.right
                }
            }
        }

        val remainingW = (bounds.width - totalGaps - fixedSum).coerceAtLeast(0)
        val parentTokens = parent.cachedAllClassTokens
        val justify = if (forceRightAlign) {
            "flex-end"
        } else {
            (parent.attributes["justifyContent"] as? String) ?: when {
                "justify-content-center" in parentTokens -> "center"
                "justify-content-end" in parentTokens -> "flex-end"
                "justify-content-between" in parentTokens -> "space-between"
                else -> null
            }
        }
        var curX = when {
            growSum > 0f -> bounds.x
            justify == "center" -> bounds.x + remainingW / 2
            justify == "flex-end" -> bounds.x + remainingW
            else -> bounds.x
        }
        val extraBetween = if (growSum <= 0f && justify == "space-between" && kids.size > 1) {
            remainingW / (kids.size - 1)
        } else {
            0
        }

        val alignItems = (parent.attributes["alignItems"] as? String) ?: "center"
        for ((i, child) in kids.withIndex()) {
            val m = child.margin
            curX += m.left
            val minGrowW = if (isSpacerWidget(child)) resolveSpacerFixedWidth(child) else 16
            val w = if (growWeights[i] > 0f && growSum > 0f) {
                ((remainingW * growWeights[i]) / growSum).roundToInt().coerceAtLeast(minGrowW)
            } else {
                fixedWidths[i]
            }
            val maxH = (bounds.height - m.top - m.bottom).coerceAtLeast(8)
            val naturalH = measurePreferredHeight(child, w).coerceAtMost(maxH)
            val stretchH = !isSpacerWidget(child) && (
                alignItems == "stretch" ||
                    "h-100" in child.cachedAllClassTokens ||
                    (!isLeafWidget(child) && naturalH >= maxH - 4)
                )
            val ch = when {
                isSpacerWidget(child) -> naturalH
                stretchH -> maxH
                else -> naturalH.coerceAtLeast(min(20, maxH))
            }
            val cy = when (alignItems) {
                "flex-start" -> bounds.y + m.top
                "flex-end" -> bounds.bottom - m.bottom - ch
                else -> bounds.y + m.top + (maxH - ch) / 2
            }

            if (w > 0 && ch > 0 && !isSpacerWidget(child)) {
                renderWidgetSubtree(ctx, child, Rect(curX, cy, w, ch))
            } else if (isSpacerWidget(child)) {
                child.bounds = Rect(curX, cy, w, ch)
            }
            curX += w + m.right + gap + extraBetween
        }
    }

    private fun renderContainerBackgroundAndBorder(
        ctx: MacosRenderContext,
        widget: DesktopNativeWidget,
        bounds: Rect
    ) {
        val tokens = widget.cachedAllClassTokens
        val impl = widget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
        val snapshot = impl?.getStyleSnapshot()
        val zeroFrameCard = isTruthyAttr(widget.attributes["data-zero-frame-card"])
        val isCard = !zeroFrameCard && ("card" in tokens || impl?.componentClassPrefix == "card")
        val isAlert = "alert" in tokens || impl?.componentClassPrefix == "alert"
        val styledImpl = impl as? DesktopStyledContainerNativeImpl<*>
        val styledVisuals = styledImpl?.getStyledVisuals()

        val radius = styledVisuals?.borderRadiusPx
            ?: (widget.attributes["borderRadiusPx"] as? Float)
            ?: when {
                "rounded-pill" in tokens -> min(bounds.width, bounds.height) / 2f
                "rounded-4" in tokens -> 14f
                "rounded-3" in tokens || isCard || isAlert -> 10f
                "rounded-2" in tokens || "rounded" in tokens -> 6f
                else -> 0f
            }

        val explicitBg = styledVisuals?.backgroundColor ?: (widget.attributes["backgroundColor"] as? RgbaColor)
        val bg: RgbaColor? = when {
            explicitBg != null && explicitBg.a > 0 -> explicitBg
            styledImpl != null -> null
            isAlert -> {
                val alertType = snapshot?.type?.takeIf { it != BootstrapType.NONE } ?: BootstrapType.INFO
                val base = BootstrapColorPalette.resolveColor(alertType, ctx.darkMode)
                base.withAlpha(if (ctx.darkMode) 0.22f else 0.14f)
            }
            snapshot?.backgroundType != null && snapshot.backgroundType != BootstrapType.NONE -> {
                BootstrapColorPalette.resolveColor(snapshot.backgroundType, ctx.darkMode)
            }
            isCard -> ctx.palette.bgCard
            "bg-body-tertiary" in tokens || "bg-light" in tokens || "bg-dark" in tokens -> ctx.palette.bgPanel
            else -> null
        }

        if (bg != null && bg.a > 0) {
            MacosGraphicsDrawUtil.fillRoundedRect(ctx, bounds.x, bounds.y, bounds.width, bounds.height, radius, bg)
        }

        val explicitBorder = styledVisuals?.borderColor ?: (widget.attributes["borderColor"] as? RgbaColor)
        val borderCol: RgbaColor? = when {
            explicitBorder != null && explicitBorder.a > 0 -> explicitBorder
            styledImpl != null -> null
            isAlert -> {
                val alertType = snapshot?.type?.takeIf { it != BootstrapType.NONE } ?: BootstrapType.INFO
                BootstrapColorPalette.resolveColor(alertType, ctx.darkMode).withAlpha(0.55f)
            }
            snapshot?.borderType != null && snapshot.borderType != BootstrapType.NONE ->
                BootstrapColorPalette.resolveColor(snapshot.borderType, ctx.darkMode)
            isCard || "border" in tokens -> ctx.palette.borderSubtle
            else -> null
        }

        val borderW = (styledVisuals?.borderWidthPx ?: 1f).coerceAtLeast(1f)
        if (borderCol != null && borderCol.a > 0 && styledVisuals?.borderStyle != CssBorderStyle.NONE) {
            MacosGraphicsDrawUtil.strokeRoundedRect(ctx, bounds.x, bounds.y, bounds.width, bounds.height, radius, borderCol, borderW)
        }

        if (impl != null && impl.hasClickListener()) {
            ctx.registerHitRegion(
                MacosHitRegion(
                    left = bounds.x,
                    top = bounds.y,
                    right = bounds.right,
                    bottom = bounds.bottom,
                    widget = widget,
                    tooltip = widget.tooltipText,
                    cursorCss = "pointer",
                    onClick = { impl.dispatchClick() }
                )
            )
        }
    }

    private fun drawOverlayScrollbar(
        ctx: MacosRenderContext,
        viewport: Rect,
        scrollY: Float,
        totalExtent: Float
    ) {
        if (totalExtent <= viewport.height + 1f) return
        val trackX = viewport.right - 6
        val trackY = viewport.y + 3
        val trackH = (viewport.height - 6).coerceAtLeast(16)
        val thumbH = ((viewport.height.toFloat() / totalExtent) * trackH).roundToInt().coerceIn(24, trackH)
        val maxScroll = (totalExtent - viewport.height.toFloat()).coerceAtLeast(1f)
        val thumbY = trackY + (((scrollY / maxScroll).coerceIn(0f, 1f)) * (trackH - thumbH)).roundToInt()
        MacosGraphicsDrawUtil.fillRoundedRect(
            ctx,
            trackX,
            thumbY,
            4,
            thumbH,
            2f,
            if (ctx.darkMode) RgbaColor(255, 255, 255, 84) else RgbaColor(0, 0, 0, 72)
        )
    }

    private fun flushDeferredOverlays(ctx: MacosRenderContext) {
        if (ctx.deferredOverlays.isEmpty()) return
        val list = ctx.deferredOverlays.toList()
        ctx.deferredOverlays.clear()
        for (task in list) {
            task()
        }
    }

    private fun visibleChildrenOf(node: DesktopNativeWidget): List<DesktopNativeWidget> =
        node.children.filter {
            it.visible &&
                !it.disposed &&
                it.attributes["visibilityHidden"] != true &&
                it.attributes["data-modal-host"] != "true" &&
                it.attributes["nativeImpl"] !is DesktopDialogWindowNativeImpl<*>
        }

    private fun isLayeredContainerWidget(widget: DesktopNativeWidget): Boolean {
        val tokens = widget.cachedAllClassTokens
        return "gui-layered-container" in tokens ||
            "gui-layered-layout" in tokens ||
            "layered-container" in tokens ||
            "fragment-stage" in tokens
    }

    private fun isSpacerWidget(widget: DesktopNativeWidget): Boolean {
        if (widget.attributes["htmlTag"] == "genericui:spacer") return true
        if ("spacer" in widget.cachedAllClassTokens) return true
        return widget.attributes["spacerFill"] != null ||
            widget.attributes["spacerWidthPx"] != null ||
            widget.attributes["spacerHeightPx"] != null ||
            widget.attributes["spacerWeight"] != null
    }

    private fun resolveSpacerFixedWidth(widget: DesktopNativeWidget): Int =
        (widget.attributes["spacerWidthPx"] as? Number)?.toInt()?.coerceAtLeast(0)
            ?: widget.attributes["spacerWidthPx"]?.toString()?.toIntOrNull()?.coerceAtLeast(0)
            ?: resolveCssLengthPx(widget.attributes["styleWidth"], 0)?.coerceAtLeast(0)
            ?: 0

    private fun resolveSpacerFixedHeight(widget: DesktopNativeWidget): Int =
        (widget.attributes["spacerHeightPx"] as? Number)?.toInt()?.coerceAtLeast(0)
            ?: widget.attributes["spacerHeightPx"]?.toString()?.toIntOrNull()?.coerceAtLeast(0)
            ?: resolveCssLengthPx(widget.attributes["styleHeight"], 0)?.coerceAtLeast(0)
            ?: 0

    private fun resolveSpacerGrow(widget: DesktopNativeWidget): Float {
        (widget.attributes["spacerWeight"] as? Number)?.toFloat()?.let { if (it > 0f) return it }
        widget.attributes["spacerWeight"]?.toString()?.toFloatOrNull()?.let { if (it > 0f) return it }
        if (isTruthyAttr(widget.attributes["spacerFill"])) return 1f
        (widget.attributes["flexGrow"] as? Number)?.toFloat()?.let { if (it > 0f) return it }
        return 0f
    }

    private fun isVerticallyCompressibleStage(widget: DesktopNativeWidget, depth: Int = 0): Boolean {
        if (depth > 4) return false
        if (MacosStageOverlayRenderUtil.isStageContainer(widget)) return true
        if (widget.attributes["aspectRatio"] != null) return true
        val kids = visibleChildrenOf(widget)
        return kids.size == 1 && isVerticallyCompressibleStage(kids[0], depth + 1)
    }

    private fun canShrinkHorizontally(widget: DesktopNativeWidget, depth: Int = 0): Boolean {
        if (depth > 5 || isSpacerWidget(widget)) return false
        val tokens = widget.cachedAllClassTokens
        if ("flex-shrink-0" in tokens || (widget.attributes["flexShrink"] as? Number)?.toFloat() == 0f) {
            return false
        }
        val impl = widget.attributes["nativeImpl"]
        return when (impl) {
            is DesktopTextNativeImpl<*> -> "badge" !in tokens
            is DesktopTextInputNativeImpl<*>,
            is DesktopMarkdownViewNativeImpl<*>,
            is DesktopProgressBarNativeImpl<*> -> true
            is DesktopButtonNativeImpl<*>,
            is DesktopStyledButtonNativeImpl<*>,
            is DesktopImageNativeImpl<*>,
            is DesktopCheckboxNativeImpl<*>,
            is DesktopSpinnerNativeImpl<*>,
            is DesktopSelectNativeImpl<*>,
            is DesktopDropdownNativeImpl<*> -> false
            else -> visibleChildrenOf(widget).any { canShrinkHorizontally(it, depth + 1) }
        }
    }

    private fun containsExpandableCollection(widget: DesktopNativeWidget, depth: Int = 0): Boolean {
        if (depth > 5 || !widget.visible || widget.disposed) return false
        val impl = widget.attributes["nativeImpl"]
        if (widget.attributes["isLazyList"] == true ||
            impl is DesktopLazyListNativeImpl<*> ||
            impl is DesktopLazyGridNativeImpl<*> ||
            impl is DesktopScrollViewNativeImpl<*>
        ) {
            return true
        }
        val tokens = widget.cachedAllClassTokens
        if ("gui-hybrid-split-navigation" in tokens ||
            "gui-split-nav-content" in tokens ||
            "hybrid-column-layout" in tokens ||
            "gui-collapsible-bottom-dock" in tokens
        ) {
            return true
        }
        return visibleChildrenOf(widget).any { containsExpandableCollection(it, depth + 1) }
    }

    private fun isLeafWidget(widget: DesktopNativeWidget): Boolean {
        val impl = widget.attributes["nativeImpl"]
        return impl is DesktopButtonNativeImpl<*> ||
            impl is DesktopStyledButtonNativeImpl<*> ||
            impl is DesktopTextInputNativeImpl<*> ||
            impl is DesktopCheckboxNativeImpl<*> ||
            impl is DesktopSelectNativeImpl<*> ||
            impl is DesktopDropdownNativeImpl<*> ||
            impl is DesktopRangeSliderNativeImpl<*> ||
            impl is DesktopProgressBarNativeImpl<*> ||
            impl is DesktopSpinnerNativeImpl<*> ||
            impl is DesktopTextNativeImpl<*> ||
            impl is DesktopMarkdownViewNativeImpl<*> ||
            impl is DesktopImageNativeImpl<*> ||
            impl is DesktopDividerNativeImpl<*>
    }

    private fun isHorizontalRow(widget: DesktopNativeWidget): Boolean {
        val dir = widget.attributes["flexDirection"] as? String
        if (dir == "row" || dir == "row-reverse") return true
        if (dir == "column" || dir == "column-reverse") return false
        val tokens = widget.cachedAllClassTokens
        if ("flex-column" in tokens || "vstack" in tokens) return false
        if ("flex-row" in tokens || "hstack" in tokens || "btn-group" in tokens || "input-group" in tokens || "d-inline-flex" in tokens) {
            return true
        }
        return widget.attributes["display"] == "flex" &&
            ("align-items-center" in tokens || "justify-content-between" in tokens || "justify-content-end" in tokens)
    }

    private fun isCenteredCardWrapper(widget: DesktopNativeWidget): Boolean {
        val tokens = widget.cachedAllClassTokens
        val kids = visibleChildrenOf(widget)
        if (kids.size != 1) return false
        val childTokens = kids[0].cachedAllClassTokens
        val centers = (widget.attributes["alignItems"] == "center" && widget.attributes["justifyContent"] == "center") ||
            ("align-items-center" in tokens && "justify-content-center" in tokens)
        return centers && ("card" in childTokens || kids[0].attributes["styleMaxWidth"] != null)
    }

    private fun resolveFlexGrow(
        widget: DesktopNativeWidget,
        parentIsRow: Boolean = false,
        siblingCount: Int = 1
    ): Float {
        if (isSpacerWidget(widget)) {
            return resolveSpacerGrow(widget)
        }
        val tokens = widget.cachedAllClassTokens

        // Keep fixed chrome bars (Navbar, BottomNav, MusicPlayerBar, search bars) at intrinsic height in columns
        if (!parentIsRow) {
            if (MacosNavigationRenderUtil.isNavbarWidget(widget) ||
                MacosNavigationRenderUtil.isBottomNavWidget(widget) ||
                "gui-split-nav-header" in tokens ||
                "gui-bottom-dock-bar" in tokens ||
                "library-search-bar" in tokens ||
                "player-status-footer" in tokens
            ) {
                return 0f
            }
            // In a multi-child column (e.g. EpisodeFragment video stage + info/codec rows below),
            // let explicit aspectRatio dictate natural 16:9 height rather than stretching vertically.
            if (siblingCount > 1 && widget.attributes["aspectRatio"] != null) {
                return 0f
            }
        }

        (widget.attributes["flexGrow"] as? Number)?.toFloat()?.let {
            if (it > 0f) return it
            if (it == 0f && "flex-grow-0" in tokens) return 0f
        }

        if ("flex-grow-1" in tokens || "flex-fill" in tokens) {
            return 1f
        }

        val impl = widget.attributes["nativeImpl"]
        if (parentIsRow) {
            // Text inputs inside horizontal search/input rows expand to fill remaining width unless given explicit width
            if (impl is DesktopTextInputNativeImpl<*> && widget.attributes["styleWidth"] == null) {
                return 1f
            }
            return 0f
        }

        // Vertical column expansion for full-height shells, split navigation, and scrollable/virtualized lists
        if ("gui-collapsible-bottom-dock" in tokens ||
            "gui-bottom-dock-main-content" in tokens ||
            "hybrid-column-layout" in tokens ||
            "gui-hybrid-columns-row" in tokens ||
            "gui-hybrid-single-stack" in tokens ||
            "gui-hybrid-split-navigation" in tokens ||
            "gui-split-nav-content" in tokens ||
            "music-main-vstack" in tokens ||
            "queue-content-stack" in tokens ||
            isLayeredContainerWidget(widget)
        ) {
            return 1f
        }

        if (widget.attributes["isLazyList"] == true ||
            impl is DesktopLazyListNativeImpl<*> ||
            impl is DesktopLazyGridNativeImpl<*> ||
            impl is DesktopScrollViewNativeImpl<*> ||
            MacosNavigationRenderUtil.isTabContainerWidget(widget)
        ) {
            return 1f
        }

        val styleHeight = widget.attributes["styleHeight"] as? CssLength
        val wantsFullHeight = "h-100" in tokens ||
            (widget.attributes["heightFraction"] as? Float ?: 0f) >= 0.99f ||
            (styleHeight != null && styleHeight.unit == CssUnit.PERCENT && styleHeight.value >= 99f && styleHeight.secondaryPxOffset == 0f)
        if (wantsFullHeight && widget.attributes["aspectRatio"] == null &&
            (siblingCount == 1 || MacosStageOverlayRenderUtil.isStageContainer(widget) || isLayeredContainerWidget(widget))
        ) {
            return 1f
        }

        if (!isLeafWidget(widget) && containsExpandableCollection(widget)) {
            return 1f
        }

        return 0f
    }

    private fun parseBootstrapColSpan(tokens: Set<String>, viewportWidth: Int = 1280): Int {
        val prefixes = buildList {
            if (viewportWidth >= 1400) add("col-xxl-")
            if (viewportWidth >= 1200) add("col-xl-")
            if (viewportWidth >= 992) add("col-lg-")
            if (viewportWidth >= 768) add("col-md-")
            if (viewportWidth >= 576) add("col-sm-")
            add("col-")
        }
        for (prefix in prefixes) {
            for (tok in tokens) {
                if (tok.startsWith(prefix)) {
                    val n = tok.removePrefix(prefix).toIntOrNull()
                    if (n != null && n in 1..12) return n
                }
            }
        }
        for (fallbackPrefix in listOf("col-xl-", "col-lg-", "col-md-", "col-sm-")) {
            for (tok in tokens) {
                if (tok.startsWith(fallbackPrefix)) {
                    val n = tok.removePrefix(fallbackPrefix).toIntOrNull()
                    if (n != null && n in 1..12) return n
                }
            }
        }
        return 6
    }

    private fun resolveGapPx(widget: DesktopNativeWidget, defaultGap: Int): Int =
        (widget.attributes["gapPx"] as? Int)
            ?: (widget.attributes["rowGapPx"] as? Int)
            ?: (widget.attributes["columnGapPx"] as? Int)
            ?: defaultGap

    private fun resolveEffectivePadding(widget: DesktopNativeWidget, tokens: Set<String>): EdgeInsets {
        val p = widget.padding
        if (p != EdgeInsets.ZERO) return p
        if (isTruthyAttr(widget.attributes["data-zero-frame-card"])) return EdgeInsets.ZERO
        if ("card-body" in tokens || ("card" in tokens && widget.children.none { "card-body" in it.cachedAllClassTokens })) {
            return EdgeInsets(16, 16, 16, 16)
        }
        if ("alert" in tokens) {
            return EdgeInsets(10, 14, 10, 14)
        }
        return EdgeInsets.ZERO
    }

    private fun insetRect(r: Rect, p: EdgeInsets): Rect = Rect(
        x = r.x + p.left,
        y = r.y + p.top,
        width = (r.width - p.left - p.right).coerceAtLeast(0),
        height = (r.height - p.top - p.bottom).coerceAtLeast(0)
    )

    private fun resolveCssLengthPx(raw: Any?, referencePx: Int?): Int? {
        return when (raw) {
            is Number -> raw.toInt()
            is CssLength -> {
                if (raw.unit == CssUnit.PERCENT && (referencePx == null || referencePx <= 0)) {
                    null
                } else {
                    raw.resolvePx((referencePx ?: 0).toFloat())?.roundToInt()?.takeIf { it > 0 }
                }
            }
            else -> null
        }
    }

    private fun measurePreferredWidth(widget: DesktopNativeWidget, maxWidth: Int): Int {
        if (isSpacerWidget(widget)) {
            return resolveSpacerFixedWidth(widget).coerceIn(0, maxWidth)
        }
        resolveCssLengthPx(widget.attributes["styleWidth"], maxWidth)?.let { return it.coerceIn(0, maxWidth) }
        (widget.attributes["widthFraction"] as? Float)?.let { frac ->
            if (frac > 0f && frac < 0.99f) return (maxWidth * frac).roundToInt().coerceIn(12, maxWidth)
        }
        widget.preferredSize?.width?.takeIf { it > 0 }?.let { return it.coerceIn(0, maxWidth) }

        val impl = widget.attributes["nativeImpl"]
        return when (impl) {
            is DesktopButtonNativeImpl<*> ->
                MacosControlRenderUtil.measureButton(impl).width.coerceAtMost(maxWidth)
            is DesktopTextNativeImpl<*> -> {
                val txt = impl.getText()
                if (txt.isEmpty()) return 0
                val fs = MacosControlRenderUtil.resolveTextFontSize(impl)
                val fw = MacosControlRenderUtil.resolveTextFontWeight(impl)
                val extra = if ("badge" in widget.cachedAllClassTokens) 18 else 4
                (MacosGraphicsDrawUtil.measureText(txt, fs, fw).width + extra).coerceIn(8, maxWidth)
            }
            is DesktopTextInputNativeImpl<*> -> min(240, maxWidth)
            is DesktopSelectNativeImpl<*> -> min(190, maxWidth)
            is DesktopDropdownNativeImpl<*> -> min(160, maxWidth)
            is DesktopCheckboxNativeImpl<*> -> {
                val lbl = impl.getLabel()
                val base = if (impl.switchStyle) 42 else 24
                if (lbl.isBlank()) base else (base + MacosGraphicsDrawUtil.measureText(lbl, 13.5f).width + 8).coerceAtMost(maxWidth)
            }
            is DesktopRangeSliderNativeImpl<*> -> min(140, maxWidth)
            is DesktopSpinnerNativeImpl<*> -> 28
            is DesktopImageNativeImpl<*> -> 36
            else -> {
                val kids = visibleChildrenOf(widget)
                val pad = resolveEffectivePadding(widget, widget.cachedAllClassTokens)
                if (kids.isEmpty()) {
                    (pad.left + pad.right).coerceAtMost(maxWidth)
                } else if (isHorizontalRow(widget)) {
                    val fallbackGap = if (kids.any { isSpacerWidget(it) || it.margin.left > 0 || it.margin.right > 0 }) 0 else 6
                    val gap = resolveGapPx(widget, fallbackGap)
                    (kids.sumOf { measurePreferredWidth(it, maxWidth) + it.margin.left + it.margin.right } +
                        gap * (kids.size - 1).coerceAtLeast(0) + pad.left + pad.right)
                        .coerceAtMost(maxWidth)
                } else {
                    val maxKidW = kids.maxOfOrNull {
                        measurePreferredWidth(it, maxWidth) + it.margin.left + it.margin.right
                    } ?: 0
                    (maxKidW + pad.left + pad.right).coerceAtMost(maxWidth)
                }
            }
        }
    }

    private fun measurePreferredHeight(
        widget: DesktopNativeWidget,
        availWidth: Int,
        availHeight: Int? = null
    ): Int {
        if (isSpacerWidget(widget)) {
            return resolveSpacerFixedHeight(widget)
        }
        resolveCssLengthPx(widget.attributes["styleHeight"], availHeight)?.let { return it }
        widget.preferredSize?.height?.takeIf { it > 0 }?.let { return it }

        val impl = widget.attributes["nativeImpl"]
        return when (impl) {
            is DesktopButtonNativeImpl<*> ->
                MacosControlRenderUtil.measureButton(impl).height
            is DesktopTextInputNativeImpl<*> -> if (impl.isMultiline()) 84 else 34
            is DesktopSelectNativeImpl<*>, is DesktopDropdownNativeImpl<*> -> 32
            is DesktopCheckboxNativeImpl<*> -> 26
            is DesktopRangeSliderNativeImpl<*> -> 24
            is DesktopProgressBarNativeImpl<*> -> 12
            is DesktopSpinnerNativeImpl<*> -> 24
            is DesktopDividerNativeImpl<*> -> 9
            is DesktopImageNativeImpl<*> -> 36
            is DesktopTextNativeImpl<*> -> {
                val txt = impl.getText()
                if (txt.isEmpty()) return 0
                val fs = MacosControlRenderUtil.resolveTextFontSize(impl)
                val fw = MacosControlRenderUtil.resolveTextFontWeight(impl)
                val tokens = widget.cachedAllClassTokens
                if ("badge" in tokens) return 22
                val singleLine = "text-truncate" in tokens ||
                    "text-nowrap" in tokens ||
                    "native-row-title" in tokens ||
                    "native-row-detail" in tokens ||
                    widget.attributes["whiteSpace"] == "nowrap"
                MacosGraphicsDrawUtil.measureText(txt, fs, fw, maxWidth = availWidth.coerceAtLeast(40), singleLine = singleLine).height + 2
            }
            is DesktopMarkdownViewNativeImpl<*> -> {
                val md = impl.getMarkdown()
                MacosGraphicsDrawUtil.measureText(md, 13.5f, 400, maxWidth = availWidth.coerceAtLeast(60), singleLine = false).height + 8
            }
            else -> measureSubtreeHeight(widget, availWidth)
        }
    }

    private fun measureSubtreeHeight(
        widget: DesktopNativeWidget,
        availWidth: Int,
        depth: Int = 0,
        availHeight: Int? = null
    ): Int {
        if (!widget.visible || widget.disposed || depth > 12) return 0
        if (isSpacerWidget(widget)) {
            return resolveSpacerFixedHeight(widget)
        }
        resolveCssLengthPx(widget.attributes["styleHeight"], availHeight)?.let { return it }
        widget.preferredSize?.height?.takeIf { it > 0 }?.let { return it }

        val aspectRaw = widget.attributes["aspectRatio"]
        val explicitRatio = when (aspectRaw) {
            is Number -> aspectRaw.toFloat()
            is String -> aspectRaw.toFloatOrNull()
            else -> null
        }
        if (explicitRatio != null && explicitRatio > 0.1f) {
            return (availWidth.toFloat() / explicitRatio).roundToInt().coerceIn(120, 2160)
        }
        if (MacosStageOverlayRenderUtil.isStageContainer(widget)) {
            val ratio = explicitRatio ?: (16f / 9f)
            return (availWidth.toFloat() / ratio).roundToInt().coerceIn(120, 2160)
        }
        if (isLeafWidget(widget)) {
            return measurePreferredHeight(widget, availWidth)
        }
        if (widget.attributes["isLazyList"] == true || widget.attributes["nativeImpl"] is DesktopScrollViewNativeImpl<*>) {
            return 280
        }

        val tokens = widget.cachedAllClassTokens
        val pad = resolveEffectivePadding(widget, tokens)
        val innerW = (availWidth - pad.left - pad.right).coerceAtLeast(32)
        val kids = visibleChildrenOf(widget)
        if (kids.isEmpty()) return pad.top + pad.bottom

        val isRow = isHorizontalRow(widget)
        val fallbackGap = if (isRow) {
            0
        } else {
            val isFlex = widget.attributes["display"] == "flex" || "d-flex" in tokens || "flex-column" in tokens || "vstack" in tokens
            if (isFlex) 0 else 4
        }
        val gap = resolveGapPx(widget, fallbackGap)
        val bodyH = if (isRow) {
            val nonSpacers = kids.filter { !isSpacerWidget(it) }
            val perKidW = (innerW / nonSpacers.size.coerceAtLeast(1)).coerceAtLeast(24)
            kids.maxOfOrNull { measureSubtreeHeight(it, perKidW, depth + 1) + it.margin.top + it.margin.bottom } ?: 0
        } else {
            kids.sumOf { measureSubtreeHeight(it, innerW, depth + 1) + it.margin.top + it.margin.bottom } +
                gap * (kids.size - 1).coerceAtLeast(0)
        }
        return pad.top + pad.bottom + bodyH
    }
}
