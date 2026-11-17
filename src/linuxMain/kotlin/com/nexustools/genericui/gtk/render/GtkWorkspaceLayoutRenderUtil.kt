package com.nexustools.genericui.gtk.render

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.dialog.DesktopDialogWindowNativeImpl
import com.nexustools.genericui.desktop.lazy.DesktopLazyGridNativeImpl
import com.nexustools.genericui.desktop.lazy.DesktopLazyListNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopWindowNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopBottomNavigationBarNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopButtonNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopCheckboxNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopDividerNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopDropdownNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopImageNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopMarkdownViewNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopNavbarNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopProgressBarNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopRangeSliderNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopScrollViewNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopSelectNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopSpinnerNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopStyledButtonNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTabContainerNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTableNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTextInputNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTextNativeImpl
import com.nexustools.genericui.enums.bootstrap.TextAlignment
import com.nexustools.genericui.enums.css.CssUnit
import com.nexustools.genericui.geometry.EdgeInsets
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssLength
import com.nexustools.genericui.threading.GenericUiThreading
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object GtkWorkspaceLayoutRenderUtil {

    private val hostScrollOffsets = LinkedHashMap<Long, Float>(32)

    fun renderMainWindow(
        ctx: GtkCairoRenderContext,
        windowImpl: DesktopWindowNativeImpl<DesktopNativeWidget>,
        fullscreenElement: DesktopNativeWidget? = null
    ) {
        val vp = ctx.viewport
        GtkGraphicsDrawUtil.fillRect(ctx, vp.x, vp.y, vp.width, vp.height, ctx.palette.bgWindow)

        if (fullscreenElement != null && !fullscreenElement.disposed && fullscreenElement.visible) {
            windowImpl.nativeWidget.bounds = vp
            fullscreenElement.bounds = vp
            renderWidgetSubtree(ctx, fullscreenElement, vp)
            flushDeferredOverlays(ctx)
            return
        }

        val rootWidget = windowImpl.nativeWidget
        rootWidget.bounds = vp
        val rootBg = (rootWidget.attributes["backgroundColor"] as? RgbaColor)?.takeIf { it.a > 0 }
        if (rootBg != null) {
            GtkGraphicsDrawUtil.fillRect(ctx, vp.x, vp.y, vp.width, vp.height, rootBg)
        }

        var contentY = vp.y
        var contentBottom = vp.bottom

        val explicitNavbar = (windowImpl.nativeWidget.attributes["navBar"] as? DesktopBaseNativeImpl<*>)?.nativeWidget
        val explicitBottomNav = (windowImpl.nativeWidget.attributes["bottomNav"] as? DesktopBaseNativeImpl<*>)?.nativeWidget

        if (explicitNavbar != null && explicitNavbar.visible && !explicitNavbar.disposed) {
            val navH = 48
            renderNavbarWidget(ctx, explicitNavbar, Rect(vp.x, contentY, vp.width, navH))
            contentY += navH
        }
        if (explicitBottomNav != null && explicitBottomNav.visible && !explicitBottomNav.disposed) {
            val botH = 54
            contentBottom -= botH
            renderBottomNavWidget(ctx, explicitBottomNav, Rect(vp.x, contentBottom, vp.width, botH))
        }

        val bodyRect = Rect(vp.x, contentY, vp.width, (contentBottom - contentY).coerceAtLeast(1))
        val unwrappedRoot = unwrapShellContainers(rootWidget)
        val kids = visibleChildrenOf(unwrappedRoot).filter { it !== explicitNavbar && it !== explicitBottomNav }

        when {
            unwrappedRoot !== rootWidget -> {
                unwrappedRoot.bounds = bodyRect
                renderWidgetSubtree(ctx, unwrappedRoot, bodyRect)
            }
            kids.size == 1 -> {
                renderWidgetSubtree(ctx, kids[0], bodyRect)
            }
            kids.size > 1 -> {
                val isRow = isHorizontalRow(unwrappedRoot)
                if (isRow) {
                    renderFlexRow(ctx, unwrappedRoot, kids, bodyRect, defaultGap = 6)
                } else {
                    renderFlexColumn(ctx, unwrappedRoot, kids, bodyRect, defaultGap = 6)
                }
            }
        }
        flushDeferredOverlays(ctx)
    }

    fun renderDialogWindow(
        ctx: GtkCairoRenderContext,
        dialogImpl: DesktopDialogWindowNativeImpl<*>
    ) {
        val vp = ctx.viewport
        GtkGraphicsDrawUtil.fillRect(ctx, vp.x, vp.y, vp.width, vp.height, ctx.palette.bgCard)

        val root = dialogImpl.nativeWidget
        root.bounds = vp
        val visibleKids = visibleChildrenOf(root)
        if (visibleKids.isEmpty()) return

        val container = if (visibleKids.size == 1 && visibleKids[0].children.isNotEmpty() && !isLeafWidget(visibleKids[0])) {
            visibleKids[0]
        } else {
            root
        }
        val sections = visibleChildrenOf(container)
        val header = sections.firstOrNull { "modal-header" in it.cachedAllClassTokens }
        val footer = sections.lastOrNull { "modal-footer" in it.cachedAllClassTokens && it !== header }
        val bodyItems = sections.filter { it !== header && it !== footer }

        var topY = vp.y
        var botY = vp.bottom

        if (header != null) {
            val hh = 48
            val hRect = Rect(vp.x, topY, vp.width, hh)
            header.bounds = hRect
            GtkGraphicsDrawUtil.fillRect(ctx, hRect.x, hRect.y, hRect.width, hRect.height, ctx.palette.bgHeader)
            GtkGraphicsDrawUtil.fillRect(ctx, hRect.x, hRect.bottom - 1, hRect.width, 1, ctx.palette.borderSubtle)
            renderFlexRow(ctx, header, visibleChildrenOf(header), insetRect(hRect, EdgeInsets(8, 16, 8, 16)), defaultGap = 8)
            topY += hh
        }

        if (footer != null) {
            val fh = 56
            botY -= fh
            val fRect = Rect(vp.x, botY, vp.width, fh)
            footer.bounds = fRect
            GtkGraphicsDrawUtil.fillRect(ctx, fRect.x, fRect.y, fRect.width, fRect.height, ctx.palette.bgHeader)
            GtkGraphicsDrawUtil.fillRect(ctx, fRect.x, fRect.y, fRect.width, 1, ctx.palette.borderSubtle)
            renderFlexRow(ctx, footer, visibleChildrenOf(footer), insetRect(fRect, EdgeInsets(10, 16, 10, 16)), defaultGap = 8, forceRightAlign = true)
        }

        val bodyRect = Rect(vp.x + 16, topY + 14, (vp.width - 32).coerceAtLeast(40), (botY - topY - 28).coerceAtLeast(24))
        if (bodyItems.size == 1) {
            renderWidgetSubtree(ctx, bodyItems[0], bodyRect)
        } else if (bodyItems.isNotEmpty()) {
            renderFlexColumn(ctx, container, bodyItems, bodyRect, defaultGap = 10)
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
        return Size(w, estH.coerceIn(200, maxViewportHeight.coerceAtLeast(220)))
    }

    @Suppress("UNCHECKED_CAST")
    fun renderWidgetSubtree(
        ctx: GtkCairoRenderContext,
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
            val impl = widget.attributes["nativeImpl"]
            val surfaceSpec = widget.interactiveSurfaceSpec
            if (surfaceSpec != null) {
                GtkGraphicsDrawUtil.drawInteractiveSurface(ctx, surfaceSpec, bounds)
                return
            }

            when (impl) {
                is DesktopNavbarNativeImpl<*> -> {
                    renderNavbarWidget(ctx, widget, bounds)
                    return
                }
                is DesktopBottomNavigationBarNativeImpl<*> -> {
                    renderBottomNavWidget(ctx, widget, bounds)
                    return
                }
                is DesktopTabContainerNativeImpl<*> -> {
                    renderTabContainerWidget(ctx, impl, bounds)
                    return
                }
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
                is DesktopTableNativeImpl<*>,
                is DesktopImageNativeImpl<*> -> {
                    GtkControlRenderUtil.renderLeafControl(impl, ctx, bounds)
                    return
                }
                is DesktopDividerNativeImpl<*> -> {
                    if (bounds.width >= bounds.height) {
                        GtkGraphicsDrawUtil.fillRect(ctx, bounds.x, bounds.y + bounds.height / 2, bounds.width, 1, ctx.palette.borderSubtle)
                    } else {
                        GtkGraphicsDrawUtil.fillRect(ctx, bounds.x + bounds.width / 2, bounds.y, 1, bounds.height, ctx.palette.borderSubtle)
                    }
                    return
                }
                is DesktopLazyListNativeImpl<*> -> {
                    renderLazyList(ctx, widget, impl, bounds)
                    return
                }
                is DesktopLazyGridNativeImpl<*> -> {
                    renderLazyGrid(ctx, widget, impl as DesktopLazyGridNativeImpl<GtkWidgetHandle>, bounds)
                    return
                }
                is DesktopScrollViewNativeImpl<*> -> {
                    renderScrollView(ctx, widget, impl, bounds)
                    return
                }
            }

            if (widget.attributes["isLazyList"] == true) {
                renderLazyList(ctx, widget, null, bounds)
                return
            }

            renderContainerBackgroundAndBorder(ctx, widget, bounds)
            val pad = resolveEffectivePadding(widget, widget.cachedAllClassTokens)
            val innerBounds = insetRect(bounds, pad)
            if (innerBounds.width <= 0 || innerBounds.height <= 0) return

            val kids = visibleChildrenOf(widget)
            if (kids.isEmpty()) return

            val hasAbsoluteChildren = kids.any {
                it.attributes["position"] == "absolute" || "position-absolute" in it.cachedAllClassTokens
            }
            if (hasAbsoluteChildren && kids.size > 1) {
                for (child in sortLayeredChildren(kids)) {
                    renderWidgetSubtree(ctx, child, innerBounds)
                }
                return
            }

            val isRow = isHorizontalRow(widget)
            val gap = resolveGapPx(widget, defaultGap = if (isRow) 6 else 4)
            if (isRow) {
                renderFlexRow(ctx, widget, kids, innerBounds, defaultGap = gap)
            } else {
                renderFlexColumn(ctx, widget, kids, innerBounds, defaultGap = gap)
            }
        } finally {
            ctx.currentOpacity = savedOpacity
        }
    }

    private fun renderNavbarWidget(
        ctx: GtkCairoRenderContext,
        widget: DesktopNativeWidget,
        bounds: Rect
    ) {
        GtkGraphicsDrawUtil.fillRect(ctx, bounds.x, bounds.y, bounds.width, bounds.height, ctx.palette.bgHeader)
        GtkGraphicsDrawUtil.fillRect(ctx, bounds.x, bounds.bottom - 1, bounds.width, 1, ctx.palette.borderSubtle)
        val inner = insetRect(bounds, EdgeInsets(6, 12, 6, 12))
        val navImpl = widget.attributes["nativeImpl"] as? DesktopNavbarNativeImpl<*>
        val kids = visibleChildrenOf(widget)
        if (kids.isNotEmpty()) {
            renderFlexRow(ctx, widget, kids, inner, defaultGap = 8)
        } else if (navImpl != null && navImpl.isBrandVisible()) {
            val title = navImpl.getBrandTitle()
            if (title.isNotEmpty()) {
                GtkGraphicsDrawUtil.drawText(ctx, title, inner.x, inner.y, inner.width, inner.height, ctx.palette.textPrimary, 15f, 600, singleLine = true)
            }
        }
    }

    private fun renderBottomNavWidget(
        ctx: GtkCairoRenderContext,
        widget: DesktopNativeWidget,
        bounds: Rect
    ) {
        GtkGraphicsDrawUtil.fillRect(ctx, bounds.x, bounds.y, bounds.width, bounds.height, ctx.palette.bgHeader)
        GtkGraphicsDrawUtil.fillRect(ctx, bounds.x, bounds.y, bounds.width, 1, ctx.palette.borderSubtle)
        val impl = widget.attributes["nativeImpl"] as? DesktopBottomNavigationBarNativeImpl<*> ?: return
        val items = impl.getItems()
        if (items.isEmpty()) return
        val selectedId = impl.getSelectedItemId()
        val itemW = (bounds.width / items.size).coerceAtLeast(48)
        for ((i, item) in items.withIndex()) {
            val ix = bounds.x + i * itemW
            val ir = Rect(ix, bounds.y, if (i == items.lastIndex) bounds.right - ix else itemW, bounds.height)
            val active = item.first == selectedId
            if (active) {
                GtkGraphicsDrawUtil.fillRoundedRect(ctx, ir.x + 8, ir.y + 6, (ir.width - 16).coerceAtLeast(16), (ir.height - 12).coerceAtLeast(16), 8f, ctx.palette.bgActiveRow)
            }
            GtkGraphicsDrawUtil.drawText(
                ctx = ctx,
                text = item.second,
                x = ir.x + 6,
                y = ir.y,
                w = (ir.width - 12).coerceAtLeast(1),
                h = ir.height,
                color = if (active) ctx.palette.accentPrimary else ctx.palette.textSecondary,
                fontSizePx = 12.5f,
                weightNumeric = if (active) 600 else 500,
                alignment = TextAlignment.CENTER,
                singleLine = true
            )
            ctx.registerHitRegion(
                GtkHitRegion(
                    left = ir.x,
                    top = ir.y,
                    right = ir.right,
                    bottom = ir.bottom,
                    widget = widget,
                    cursorCss = "pointer",
                    onClick = {
                        GenericUiThreading.runOnLogic { impl.setSelectedItemId(item.first) }
                    }
                )
            )
        }
    }

    private fun renderTabContainerWidget(
        ctx: GtkCairoRenderContext,
        impl: DesktopTabContainerNativeImpl<*>,
        bounds: Rect
    ) {
        val titles = impl.getTabTitles()
        val selectedIdx = impl.getSelectedTabIndex()
        val barH = if (titles.isNotEmpty()) 38 else 0
        if (barH > 0) {
            val barRect = Rect(bounds.x, bounds.y, bounds.width, min(barH, bounds.height))
            GtkGraphicsDrawUtil.fillRect(ctx, barRect.x, barRect.y, barRect.width, barRect.height, ctx.palette.bgHeader)
            GtkGraphicsDrawUtil.fillRect(ctx, barRect.x, barRect.bottom - 1, barRect.width, 1, ctx.palette.borderSubtle)

            val tabW = if (impl.isFullWidthTabs()) {
                (barRect.width / titles.size).coerceAtLeast(48)
            } else {
                min(180, (barRect.width / titles.size).coerceAtLeast(72))
            }
            for (i in titles.indices) {
                val tx = barRect.x + i * tabW
                val tw = if (impl.isFullWidthTabs() && i == titles.lastIndex) barRect.right - tx else min(tabW, barRect.right - tx)
                if (tw <= 0) break
                val selected = i == selectedIdx
                if (selected) {
                    GtkGraphicsDrawUtil.fillRect(ctx, tx, barRect.y, tw, barRect.height, ctx.palette.bgCard)
                    GtkGraphicsDrawUtil.fillRect(ctx, tx + 6, barRect.bottom - 2, (tw - 12).coerceAtLeast(4), 2, ctx.palette.accentPrimary)
                }
                GtkGraphicsDrawUtil.drawText(
                    ctx = ctx,
                    text = titles[i],
                    x = tx + 8,
                    y = barRect.y,
                    w = (tw - 16).coerceAtLeast(1),
                    h = barRect.height,
                    color = if (selected) ctx.palette.textPrimary else ctx.palette.textSecondary,
                    fontSizePx = 13f,
                    weightNumeric = if (selected) 600 else 500,
                    alignment = TextAlignment.CENTER,
                    singleLine = true
                )
                ctx.registerHitRegion(
                    GtkHitRegion(
                        left = tx,
                        top = barRect.y,
                        right = tx + tw,
                        bottom = barRect.bottom,
                        widget = impl.nativeWidget,
                        cursorCss = "pointer",
                        onClick = {
                            GenericUiThreading.runOnLogic { impl.requestSelectTab(i) }
                        }
                    )
                )
            }
        }
        val contentRect = Rect(bounds.x, bounds.y + barH, bounds.width, (bounds.height - barH).coerceAtLeast(0))
        val contentWidget = impl.getSelectedContentWidget()
        if (contentWidget != null && contentRect.height > 0) {
            renderWidgetSubtree(ctx, contentWidget, contentRect)
        }
    }

    private fun renderScrollView(
        ctx: GtkCairoRenderContext,
        widget: DesktopNativeWidget,
        scrollImpl: DesktopScrollViewNativeImpl<*>,
        bounds: Rect
    ) {
        renderContainerBackgroundAndBorder(ctx, widget, bounds)
        val viewport = insetRect(bounds, resolveEffectivePadding(widget, widget.cachedAllClassTokens))
        val kids = visibleChildrenOf(widget)
        if (kids.isEmpty() || viewport.width <= 0 || viewport.height <= 0) return

        val gap = resolveGapPx(widget, 8)
        val itemHeights = IntArray(kids.size) { idx ->
            measurePreferredHeight(kids[idx], (viewport.width - 10).coerceAtLeast(32))
        }
        val totalH = itemHeights.sum() + gap * (kids.size - 1).coerceAtLeast(0)
        val maxScroll = (totalH - viewport.height).coerceAtLeast(0).toFloat()
        val key = widget.nativePointer
        val requestedVer = (widget.attributes["scrollRequestVersion"] as? Long) ?: 0L
        val appliedVer = (widget.attributes["_gtkAppliedScrollVer"] as? Long) ?: -1L
        if (requestedVer != appliedVer) {
            widget.attributes["_gtkAppliedScrollVer"] = requestedVer
            hostScrollOffsets[key] = scrollImpl.currentScrollY().toFloat()
        }
        val scrollY = (hostScrollOffsets[key] ?: 0f).coerceIn(0f, maxScroll)
        hostScrollOffsets[key] = scrollY

        ctx.registerScrollRegion(
            GtkScrollRegion(
                left = viewport.x,
                top = viewport.y,
                right = viewport.right,
                bottom = viewport.bottom,
                widget = widget,
                viewportHeight = viewport.height,
                contentHeight = totalH,
                currentScrollY = scrollY,
                onScrollBy = { deltaY ->
                    if (maxScroll <= 0f) false else {
                        val next = ((hostScrollOffsets[key] ?: 0f) + deltaY).coerceIn(0f, maxScroll)
                        val changed = abs(next - (hostScrollOffsets[key] ?: 0f)) > 0.25f
                        if (changed) {
                            hostScrollOffsets[key] = next
                            scrollImpl.updateScrollPositionFromHost(0, next.roundToInt())
                        }
                        changed
                    }
                }
            )
        )

        if (!GtkGraphicsDrawUtil.pushClip(ctx, viewport.x, viewport.y, viewport.width, viewport.height)) return
        try {
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
        } finally {
            GtkGraphicsDrawUtil.popClip(ctx)
        }
    }

    private fun renderLazyList(
        ctx: GtkCairoRenderContext,
        widget: DesktopNativeWidget,
        lazyImpl: DesktopLazyListNativeImpl<*>?,
        bounds: Rect
    ) {
        renderContainerBackgroundAndBorder(ctx, widget, bounds)
        val viewport = insetRect(bounds, resolveEffectivePadding(widget, widget.cachedAllClassTokens))
        if (viewport.width <= 0 || viewport.height <= 0) return

        val key = widget.nativePointer
        val currentScroll = (hostScrollOffsets[key] ?: 0f).coerceAtLeast(0f)
        lazyImpl?.dispatchHostViewportScroll(currentScroll, viewport.height.toFloat())

        val estExtent = ((widget.attributes["estimatedItemExtentPx"] as? Number)?.toFloat() ?: 56f).coerceAtLeast(28f)
        val leadingPx = (widget.attributes["leadingSpacerPx"] as? Number)?.toFloat() ?: 0f
        val materialized = visibleChildrenOf(widget)
        val totalExtent = max(
            (widget.attributes["totalContentExtentPx"] as? Number)?.toFloat() ?: 0f,
            leadingPx + materialized.size * estExtent
        )
        val maxScroll = (totalExtent - viewport.height.toFloat()).coerceAtLeast(0f)
        val clampedScroll = currentScroll.coerceIn(0f, maxScroll)
        hostScrollOffsets[key] = clampedScroll

        ctx.registerScrollRegion(
            GtkScrollRegion(
                left = viewport.x,
                top = viewport.y,
                right = viewport.right,
                bottom = viewport.bottom,
                widget = widget,
                viewportHeight = viewport.height,
                contentHeight = totalExtent.roundToInt(),
                currentScrollY = clampedScroll,
                onScrollBy = { deltaY ->
                    if (maxScroll <= 0f) false else {
                        val next = ((hostScrollOffsets[key] ?: 0f) + deltaY).coerceIn(0f, maxScroll)
                        val changed = abs(next - (hostScrollOffsets[key] ?: 0f)) > 0.25f
                        if (changed) {
                            hostScrollOffsets[key] = next
                            lazyImpl?.dispatchHostViewportScroll(next, viewport.height.toFloat())
                        }
                        changed
                    }
                }
            )
        )

        val rowW = if (maxScroll > 0f) (viewport.width - 8).coerceAtLeast(32) else viewport.width
        if (!GtkGraphicsDrawUtil.pushClip(ctx, viewport.x, viewport.y, viewport.width, viewport.height)) return
        try {
            var yCursor = viewport.y + (leadingPx - clampedScroll).roundToInt()
            for (rowWidget in materialized) {
                val rowH = resolveCssLengthPx(rowWidget.attributes["styleHeight"], viewport.height)
                    ?: rowWidget.preferredSize?.height?.takeIf { it > 0 }
                    ?: estExtent.roundToInt()
                val rowRect = Rect(viewport.x, yCursor, rowW, rowH)
                if (rowRect.bottom >= viewport.y && rowRect.y <= viewport.bottom) {
                    renderWidgetSubtree(ctx, rowWidget, rowRect)
                }
                yCursor += rowH
            }
            if (maxScroll > 0f) {
                drawOverlayScrollbar(ctx, viewport, clampedScroll, totalExtent)
            }
        } finally {
            GtkGraphicsDrawUtil.popClip(ctx)
        }
    }

    private fun renderLazyGrid(
        ctx: GtkCairoRenderContext,
        widget: DesktopNativeWidget,
        gridImpl: DesktopLazyGridNativeImpl<GtkWidgetHandle>,
        bounds: Rect
    ) {
        GtkControlRenderUtil.renderLazyGrid(gridImpl, 160, ctx, bounds)
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
            GtkScrollRegion(
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
                    val changed = abs(next - (hostScrollOffsets[key] ?: 0f)) > 0.25f
                    if (changed) hostScrollOffsets[key] = next
                    changed
                }
            )
        )

        if (!GtkGraphicsDrawUtil.pushClip(ctx, viewport.x, viewport.y, viewport.width, viewport.height)) return
        try {
            for ((idx, item) in items.withIndex()) {
                val r = idx / cols
                val c = idx % cols
                val cx = viewport.x + c * (cellW + gap)
                val cy = viewport.y + r * (cellH + gap) - scrollY.roundToInt()
                if (cy + cellH >= viewport.y && cy <= viewport.bottom) {
                    renderWidgetSubtree(ctx, item, Rect(cx, cy, cellW, cellH))
                }
            }
            if (maxScroll > 0f) {
                drawOverlayScrollbar(ctx, viewport, scrollY, totalH.toFloat())
            }
        } finally {
            GtkGraphicsDrawUtil.popClip(ctx)
        }
    }

    private fun renderContainerBackgroundAndBorder(
        ctx: GtkCairoRenderContext,
        widget: DesktopNativeWidget,
        bounds: Rect
    ) {
        val tokens = widget.cachedAllClassTokens
        val radius = ((widget.attributes["borderRadiusPx"] as? Number)?.toFloat()
            ?: if ("card" in tokens || "alert" in tokens) 10f else 0f)
        val explicitBg = widget.attributes["backgroundColor"] as? RgbaColor
        val bg = when {
            explicitBg != null && explicitBg.a > 0 -> explicitBg
            "card" in tokens -> ctx.palette.bgCard
            "alert" in tokens -> ctx.palette.bgElevated
            else -> null
        }
        if (bg != null) {
            GtkGraphicsDrawUtil.fillRoundedRect(ctx, bounds.x, bounds.y, bounds.width, bounds.height, radius, bg)
        }
        val borderColor = (widget.attributes["borderColor"] as? RgbaColor)?.takeIf { it.a > 0 }
            ?: if ("card" in tokens || "alert" in tokens) ctx.palette.borderSubtle else null
        val borderW = (widget.attributes["borderWidthPx"] as? Number)?.toFloat() ?: if (borderColor != null) 1f else 0f
        if (borderColor != null && borderW > 0f) {
            GtkGraphicsDrawUtil.strokeRoundedRect(ctx, bounds.x, bounds.y, bounds.width, bounds.height, radius, borderW, borderColor)
        }
    }

    private fun renderFlexColumn(
        ctx: GtkCairoRenderContext,
        parent: DesktopNativeWidget,
        kids: List<DesktopNativeWidget>,
        bounds: Rect,
        defaultGap: Int
    ) {
        if (kids.isEmpty() || bounds.width <= 0 || bounds.height <= 0) return
        val gap = resolveGapPx(parent, defaultGap)
        val totalGaps = gap * (kids.size - 1).coerceAtLeast(0)
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
                val h = measurePreferredHeight(child, (bounds.width - m.left - m.right).coerceAtLeast(16))
                fixedHeights[i] = h
                fixedSum += h + m.top + m.bottom
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
        } else 0

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
                    "w-100" !in child.cachedAllClassTokens ->
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
        ctx: GtkCairoRenderContext,
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

        val remainingW = (bounds.width - totalGaps - fixedSum).coerceAtLeast(0)
        val justify = parent.attributes["justifyContent"] as? String
        var curX = when {
            growSum > 0f -> bounds.x
            forceRightAlign || justify == "flex-end" -> bounds.x + remainingW
            justify == "center" -> bounds.x + remainingW / 2
            else -> bounds.x
        }
        val extraBetween = if (growSum <= 0f && !forceRightAlign && justify == "space-between" && kids.size > 1) {
            remainingW / (kids.size - 1)
        } else 0

        for ((i, child) in kids.withIndex()) {
            val m = child.margin
            curX += m.left
            val w = if (growWeights[i] > 0f && growSum > 0f) {
                ((remainingW * growWeights[i]) / growSum).roundToInt().coerceAtLeast(if (isSpacerWidget(child)) 4 else 16)
            } else {
                fixedWidths[i].coerceAtMost((bounds.right - curX).coerceAtLeast(0))
            }
            val maxH = (bounds.height - m.top - m.bottom).coerceAtLeast(16)
            val childH = (resolveCssLengthPx(child.attributes["styleHeight"], maxH)
                ?: child.preferredSize?.height?.takeIf { it in 1..maxH }
                ?: if (isLeafWidget(child)) measurePreferredHeight(child, max(16, w)) else maxH).coerceAtMost(maxH)
            val alignItems = parent.attributes["alignItems"] as? String
            val childY = when (alignItems) {
                "flex-start" -> bounds.y + m.top
                "flex-end" -> bounds.bottom - m.bottom - childH
                else -> bounds.y + m.top + (maxH - childH) / 2
            }
            if (w > 0 && childH > 0) {
                renderWidgetSubtree(ctx, child, Rect(curX, childY, w, childH))
            }
            curX += w + m.right + gap + extraBetween
        }
    }

    private fun drawOverlayScrollbar(ctx: GtkCairoRenderContext, vp: Rect, scrollY: Float, totalH: Float) {
        if (totalH <= vp.height || vp.height < 24) return
        val trackX = vp.right - 6
        val thumbH = max(24, ((vp.height / totalH) * vp.height).roundToInt()).coerceAtMost(vp.height)
        val maxScroll = (totalH - vp.height).coerceAtLeast(1f)
        val thumbY = vp.y + ((scrollY / maxScroll) * (vp.height - thumbH)).roundToInt()
        GtkGraphicsDrawUtil.fillRoundedRect(ctx, trackX, thumbY, 4, thumbH, 2f, ctx.palette.borderStrong)
    }

    private fun flushDeferredOverlays(ctx: GtkCairoRenderContext) {
        if (ctx.deferredOverlays.isEmpty()) return
        val copy = ctx.deferredOverlays.toList()
        ctx.deferredOverlays.clear()
        for (drawFn in copy) drawFn()
    }

    private fun unwrapShellContainers(start: DesktopNativeWidget): DesktopNativeWidget {
        var cur = start
        repeat(8) {
            val impl = cur.attributes["nativeImpl"]
            if (impl is DesktopTabContainerNativeImpl<*> ||
                impl is DesktopScrollViewNativeImpl<*> ||
                impl is DesktopLazyListNativeImpl<*> ||
                "card" in cur.cachedAllClassTokens
            ) {
                return cur
            }
            val kids = visibleChildrenOf(cur)
            if (kids.size != 1) return cur
            val only = kids[0]
            if (isLeafWidget(only)) return cur
            cur = only
        }
        return cur
    }

    private fun sortLayeredChildren(kids: List<DesktopNativeWidget>): List<DesktopNativeWidget> =
        if (kids.size <= 1) kids else kids.sortedBy { child ->
            (child.attributes["layerZIndex"] as? Number)?.toInt()
                ?: (child.attributes["zIndex"] as? Number)?.toInt()
                ?: 0
        }

    private fun measurePreferredWidth(widget: DesktopNativeWidget, availableWidth: Int): Int {
        resolveCssLengthPx(widget.attributes["styleWidth"], availableWidth)?.let { return it }
        widget.preferredSize?.width?.takeIf { it > 0 }?.let { return min(it, availableWidth) }
        return when (val impl = widget.attributes["nativeImpl"]) {
            is DesktopButtonNativeImpl<*> -> GtkControlRenderUtil.measureButton(impl as DesktopButtonNativeImpl<GtkWidgetHandle>).width
            is DesktopStyledButtonNativeImpl<*> -> max(80, impl.getText().length * 8 + 28)
            is DesktopTextNativeImpl<*> -> GtkControlRenderUtil.measureText(impl as DesktopTextNativeImpl<GtkWidgetHandle>).width
            is DesktopCheckboxNativeImpl<*> -> max(36, impl.getLabel().length * 8 + 44)
            is DesktopTextInputNativeImpl<*> -> min(availableWidth, 220)
            is DesktopSelectNativeImpl<*> -> min(availableWidth, 180)
            is DesktopDropdownNativeImpl<*> -> min(availableWidth, 160)
            is DesktopDividerNativeImpl<*> -> 1
            else -> if (isHorizontalRow(widget)) {
                visibleChildrenOf(widget).sumOf { measurePreferredWidth(it, availableWidth) + it.margin.left + it.margin.right }.coerceIn(48, availableWidth)
            } else {
                availableWidth
            }
        }
    }

    private fun measurePreferredHeight(widget: DesktopNativeWidget, availableWidth: Int): Int {
        if (isSpacerWidget(widget)) return resolveSpacerFixedHeight(widget)
        resolveCssLengthPx(widget.attributes["styleHeight"], 1000)?.let { return it }
        widget.preferredSize?.height?.takeIf { it > 0 }?.let { return it }
        return when (val impl = widget.attributes["nativeImpl"]) {
            is DesktopButtonNativeImpl<*> -> GtkControlRenderUtil.measureButton(impl as DesktopButtonNativeImpl<GtkWidgetHandle>).height
            is DesktopStyledButtonNativeImpl<*> -> 34
            is DesktopTextNativeImpl<*> -> {
                val fs = (widget.attributes["fontSizePx"] as? Float) ?: 14f
                val w = (widget.attributes["fontWeightNumeric"] as? Int) ?: 400
                GtkGraphicsDrawUtil.measureText(impl.getText(), fs, w, maxWidth = availableWidth, singleLine = false).height.coerceAtLeast(20)
            }
            is DesktopTextInputNativeImpl<*> -> if (impl.isMultiline()) 88 else 38
            is DesktopCheckboxNativeImpl<*> -> 28
            is DesktopSelectNativeImpl<*>, is DesktopDropdownNativeImpl<*> -> 34
            is DesktopRangeSliderNativeImpl<*> -> 28
            is DesktopProgressBarNativeImpl<*> -> 18
            is DesktopSpinnerNativeImpl<*> -> 32
            is DesktopDividerNativeImpl<*> -> 2
            is DesktopMarkdownViewNativeImpl<*> -> 120
            is DesktopTableNativeImpl<*> -> 180
            else -> {
                val kids = visibleChildrenOf(widget)
                if (kids.isEmpty()) 48
                else if (isHorizontalRow(widget)) {
                    kids.maxOfOrNull { measurePreferredHeight(it, availableWidth / kids.size.coerceAtLeast(1)) }?.plus(12) ?: 40
                } else {
                    val gap = resolveGapPx(widget, 6)
                    kids.sumOf { measurePreferredHeight(it, availableWidth) + it.margin.top + it.margin.bottom } + gap * (kids.size - 1).coerceAtLeast(0) + 12
                }
            }
        }
    }

    private fun isLeafWidget(widget: DesktopNativeWidget): Boolean = when (widget.attributes["nativeImpl"]) {
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
        is DesktopTableNativeImpl<*>,
        is DesktopImageNativeImpl<*>,
        is DesktopDividerNativeImpl<*> -> true
        else -> isSpacerWidget(widget)
    }

    private fun visibleChildrenOf(widget: DesktopNativeWidget): List<DesktopNativeWidget> =
        widget.children.filter { it.visible && !it.disposed && it.attributes["visibilityHidden"] != true }

    private fun isHorizontalRow(widget: DesktopNativeWidget): Boolean {
        val dir = widget.attributes["flexDirection"] as? String
        if (dir == "row" || dir == "row-reverse") return true
        if (dir == "column" || dir == "column-reverse") return false
        val tokens = widget.cachedAllClassTokens
        return "row" in tokens || "flex-row" in tokens || "hstack" in tokens || "btn-group" in tokens
    }

    private fun isSpacerWidget(widget: DesktopNativeWidget): Boolean =
        widget.nativeClassName.contains("Spacer", ignoreCase = true) || widget.attributes["isSpacer"] == true

    private fun resolveSpacerFixedHeight(widget: DesktopNativeWidget): Int =
        resolveCssLengthPx(widget.attributes["styleHeight"], 64) ?: widget.preferredSize?.height ?: 8

    private fun resolveFlexGrow(child: DesktopNativeWidget, parentIsRow: Boolean, siblingCount: Int): Float {
        (child.attributes["flexGrow"] as? Number)?.toFloat()?.let { if (it > 0f) return it }
        if ("flex-grow-1" in child.cachedAllClassTokens || "flex-fill" in child.cachedAllClassTokens) return 1f
        if (!parentIsRow && (child.attributes["nativeImpl"] is DesktopLazyListNativeImpl<*> ||
                child.attributes["nativeImpl"] is DesktopScrollViewNativeImpl<*> ||
                child.attributes["nativeImpl"] is DesktopTabContainerNativeImpl<*>)
        ) {
            return 1f
        }
        return if (isSpacerWidget(child) && siblingCount > 1 && child.attributes["styleWidth"] == null && child.attributes["styleHeight"] == null) 1f else 0f
    }

    private fun resolveGapPx(widget: DesktopNativeWidget, defaultGap: Int): Int =
        resolveCssLengthPx(widget.attributes["gap"], 100) ?: defaultGap

    private fun resolveEffectivePadding(widget: DesktopNativeWidget, tokens: Set<String>): EdgeInsets {
        val p = widget.padding
        if (p.top != 0 || p.right != 0 || p.bottom != 0 || p.left != 0) return p
        return if ("card" in tokens || "alert" in tokens) EdgeInsets.all(14) else EdgeInsets.ZERO
    }

    private fun resolveCssLengthPx(raw: Any?, referencePx: Int): Int? {
        return when (raw) {
            is Number -> raw.toInt().takeIf { it > 0 }
            is CssLength -> {
                if (raw.unit == CssUnit.PERCENT) ((referencePx * raw.value) / 100f).roundToInt().takeIf { it > 0 }
                else raw.resolvePx()?.roundToInt()?.takeIf { it > 0 }
            }
            else -> null
        }
    }

    private fun insetRect(r: Rect, insets: EdgeInsets): Rect = Rect(
        x = r.x + insets.left,
        y = r.y + insets.top,
        width = (r.width - insets.left - insets.right).coerceAtLeast(0),
        height = (r.height - insets.top - insets.bottom).coerceAtLeast(0)
    )
}
