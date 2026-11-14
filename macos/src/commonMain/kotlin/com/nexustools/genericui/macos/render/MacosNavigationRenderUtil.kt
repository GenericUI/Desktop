package com.nexustools.genericui.macos.render

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.widget.DesktopBottomNavigationBarNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopButtonNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopImageNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopNavbarNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopStyledButtonNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTabContainerNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTabNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTextInputNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTextNativeImpl
import com.nexustools.genericui.enums.bootstrap.TextAlignment
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssLength
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Type-grouped macOS renderer for navigation chrome (`Navbar`, `TabContainer`, and `BottomNavigationBar`).
 */
object MacosNavigationRenderUtil {

    const val NAVBAR_HEIGHT_PX: Int = 42
    const val BOTTOM_NAV_HEIGHT_PX: Int = 52
    const val TAB_BAR_HEIGHT_PX: Int = 36

    fun isNavbarWidget(widget: DesktopNativeWidget): Boolean {
        val impl = widget.attributes["nativeImpl"]
        if (impl is DesktopNavbarNativeImpl<*>) return true
        return "navbar" in widget.cachedAllClassTokens || widget.attributes["htmlTag"] == "nav"
    }

    fun isBottomNavWidget(widget: DesktopNativeWidget): Boolean {
        val impl = widget.attributes["nativeImpl"]
        return impl is DesktopBottomNavigationBarNativeImpl<*> || "bottom-nav" in widget.cachedAllClassTokens
    }

    fun isTabContainerWidget(widget: DesktopNativeWidget): Boolean {
        val impl = widget.attributes["nativeImpl"]
        return impl is DesktopTabContainerNativeImpl<*> || "nav-tabs" in widget.cachedAllClassTokens
    }

    @Suppress("UNCHECKED_CAST")
    fun renderNavbar(
        ctx: MacosRenderContext,
        widget: DesktopNativeWidget,
        bounds: Rect,
        reserveTrafficLightsLeftPx: Int = 0
    ) {
        if (!widget.visible || bounds.width <= 0 || bounds.height <= 0) return
        widget.bounds = bounds

        val styleBg = (widget.attributes["backgroundColor"] as? RgbaColor)?.takeIf { it.a > 0 }
        val bg = styleBg ?: ctx.palette.bgHeader
        MacosGraphicsDrawUtil.fillRect(ctx, bounds.x, bounds.y, bounds.width, bounds.height, bg)
        MacosGraphicsDrawUtil.fillRect(ctx, bounds.x, bounds.bottom - 1, bounds.width, 1, ctx.palette.borderSubtle)

        val impl = widget.attributes["nativeImpl"] as? DesktopNavbarNativeImpl<*>
        val showBrand = impl?.isBrandVisible() ?: ((widget.attributes["showBrand"] as? Boolean) ?: false)
        val brandTitle = if (showBrand) {
            impl?.getBrandTitle()?.trim().orEmpty()
                .ifEmpty { (widget.attributes["brandTitle"] as? String)?.trim().orEmpty() }
        } else {
            ""
        }
        val brandIcon = if (showBrand) {
            impl?.getBrandIcon()?.trim().orEmpty()
                .ifEmpty { (widget.attributes["brandIcon"] as? String)?.trim().orEmpty() }
                .takeIf { it.isNotEmpty() }
        } else {
            null
        }

        val padX = 14
        var leftCursor = bounds.x + if (reserveTrafficLightsLeftPx > 0) reserveTrafficLightsLeftPx else padX
        var rightCursor = bounds.right - padX

        if (brandIcon != null) {
            val iconSize = 22
            val iy = bounds.y + (bounds.height - iconSize) / 2
            if (MacosControlRenderUtil.isDrawableUri(brandIcon)) {
                MacosGraphicsDrawUtil.drawIconOrUri(ctx, brandIcon, leftCursor, iy, iconSize, iconSize, cornerRadius = 4f)
            } else {
                MacosGraphicsDrawUtil.drawText(
                    ctx = ctx,
                    text = brandIcon,
                    x = leftCursor,
                    y = bounds.y,
                    w = iconSize + 2,
                    h = bounds.height,
                    color = ctx.palette.textPrimary,
                    fontSizePx = 15f,
                    weightNumeric = 600,
                    alignment = TextAlignment.CENTER
                )
            }
            leftCursor += iconSize + if (brandTitle.isNotEmpty()) 8 else 12
        }

        if (brandTitle.isNotEmpty()) {
            val brandMeasure = MacosGraphicsDrawUtil.measureText(brandTitle, 15f, 700)
            val bw = min(brandMeasure.width + 8, (bounds.width / 2).coerceAtLeast(80))
            MacosGraphicsDrawUtil.drawText(
                ctx = ctx,
                text = brandTitle,
                x = leftCursor,
                y = bounds.y,
                w = bw,
                h = bounds.height,
                color = ctx.palette.textPrimary,
                fontSizePx = 15f,
                weightNumeric = 700,
                alignment = TextAlignment.START
            )
            leftCursor += bw + 12
        }

        // Flatten navbar leaves into leading (text/badges/brand icons) and trailing (actions/buttons/inputs)
        val leadingItems = ArrayList<DesktopNativeWidget>(6)
        val trailingItems = ArrayList<DesktopNativeWidget>(10)
        collectNavbarItems(widget, leadingItems, trailingItems, inTrailingGroup = false)

        // Render trailing items right-to-left so action icons stay pinned cleanly to the right edge
        for (item in trailingItems.asReversed()) {
            if (rightCursor <= leftCursor + 20) break
            val itemSize = measureNavbarChild(item, bounds.height)
            if (itemSize.first <= 0 || itemSize.second <= 0) continue
            val w = min(itemSize.first, (rightCursor - leftCursor).coerceAtLeast(20))
            val h = itemSize.second.coerceIn(22, bounds.height - 8)
            val ix = rightCursor - w
            val iy = bounds.y + (bounds.height - h) / 2
            val itemRect = Rect(ix, iy, w, h)
            item.bounds = itemRect
            renderNavbarLeaf(ctx, item, itemRect)
            rightCursor = ix - 6
        }

        // Render leading items left-to-right
        for (item in leadingItems) {
            if (leftCursor >= rightCursor - 16) break
            val itemSize = measureNavbarChild(item, bounds.height)
            if (itemSize.first <= 0 || itemSize.second <= 0) continue
            val w = min(itemSize.first, (rightCursor - leftCursor).coerceAtLeast(16))
            val h = itemSize.second.coerceIn(20, bounds.height - 8)
            val iy = bounds.y + (bounds.height - h) / 2
            val itemRect = Rect(leftCursor, iy, w, h)
            item.bounds = itemRect
            renderNavbarLeaf(ctx, item, itemRect)
            leftCursor += w + 8
        }
    }

    private fun isSpacerNode(node: DesktopNativeWidget): Boolean {
        val tag = node.attributes["htmlTag"] as? String
        if (tag == "genericui:spacer") return true
        val tokens = node.cachedAllClassTokens
        if ("spacer" in tokens) return true
        return node.attributes["spacerFill"]?.toString() == "true" ||
            node.attributes["spacerWidthPx"] != null ||
            node.attributes["spacerHeightPx"] != null
    }

    private fun collectNavbarItems(
        node: DesktopNativeWidget,
        leading: MutableList<DesktopNativeWidget>,
        trailing: MutableList<DesktopNativeWidget>,
        inTrailingGroup: Boolean
    ) {
        var afterSpacer = false
        for (child in node.children) {
            if (!child.visible || child.disposed || child.attributes["visibilityHidden"] == true) continue
            if (child.attributes["data-modal-host"] == "true") continue
            if (isSpacerNode(child)) {
                val fill = child.attributes["spacerFill"]?.toString() == "true" ||
                    ((child.attributes["spacerWeight"] as? Number)?.toFloat() ?: 0f) > 0f ||
                    ((child.attributes["flexGrow"] as? Number)?.toFloat() ?: 0f) > 0f
                if (fill) {
                    afterSpacer = true
                }
                continue
            }
            val tokens = child.cachedAllClassTokens
            val isRightGroup = inTrailingGroup ||
                afterSpacer ||
                "ms-auto" in tokens ||
                "justify-content-end" in tokens ||
                "navbar-actions" in tokens ||
                child.attributes["justifyContent"] == "flex-end"

            val impl = child.attributes["nativeImpl"]
            val isLeafControl = impl is DesktopButtonNativeImpl<*> ||
                impl is DesktopStyledButtonNativeImpl<*> ||
                impl is DesktopTextNativeImpl<*> ||
                impl is DesktopImageNativeImpl<*> ||
                impl is DesktopTextInputNativeImpl<*>

            if (isLeafControl || child.children.isEmpty()) {
                if (impl is DesktopTextNativeImpl<*> && impl.getText().isBlank()) continue
                if (impl is DesktopButtonNativeImpl<*>) {
                    val sz = MacosControlRenderUtil.measureButton(impl)
                    if (sz.width <= 0 || sz.height <= 0) continue
                }
                if (impl != null) {
                    if (isRightGroup || impl is DesktopButtonNativeImpl<*> || impl is DesktopStyledButtonNativeImpl<*>) {
                        trailing.add(child)
                    } else {
                        leading.add(child)
                    }
                }
            } else {
                val justifyBetween = child.attributes["justifyContent"] == "space-between" || "justify-content-between" in tokens
                if (justifyBetween && child.children.size >= 2) {
                    val visKids = child.children.filter {
                        it.visible && !it.disposed && it.attributes["visibilityHidden"] != true && !isSpacerNode(it)
                    }
                    if (visKids.size >= 2) {
                        visKids.dropLast(1).forEach { collectNavbarLeafOrSubtree(it, leading, trailing, forceTrailing = false) }
                        visKids.lastOrNull()?.let { collectNavbarLeafOrSubtree(it, leading, trailing, forceTrailing = true) }
                    } else {
                        collectNavbarItems(child, leading, trailing, isRightGroup)
                    }
                } else {
                    collectNavbarItems(child, leading, trailing, isRightGroup)
                }
            }
        }
    }

    private fun collectNavbarLeafOrSubtree(
        node: DesktopNativeWidget,
        leading: MutableList<DesktopNativeWidget>,
        trailing: MutableList<DesktopNativeWidget>,
        forceTrailing: Boolean
    ) {
        if (!node.visible || node.disposed || isSpacerNode(node)) return
        val impl = node.attributes["nativeImpl"]
        val isLeaf = impl is DesktopButtonNativeImpl<*> ||
            impl is DesktopStyledButtonNativeImpl<*> ||
            impl is DesktopTextNativeImpl<*> ||
            impl is DesktopImageNativeImpl<*> ||
            impl is DesktopTextInputNativeImpl<*>
        if (isLeaf) {
            if (impl is DesktopTextNativeImpl<*> && impl.getText().isBlank()) return
            if (impl is DesktopButtonNativeImpl<*>) {
                val sz = MacosControlRenderUtil.measureButton(impl)
                if (sz.width <= 0 || sz.height <= 0) return
            }
            if (forceTrailing) trailing.add(node) else leading.add(node)
        } else {
            collectNavbarItems(node, leading, trailing, forceTrailing)
        }
    }

    private fun resolveExplicitPx(raw: Any?): Int? = when (raw) {
        is Number -> raw.toInt().takeIf { it > 0 }
        is CssLength -> raw.resolvePx(0f)?.roundToInt()?.takeIf { it > 0 }
        else -> null
    }

    private fun measureNavbarChild(widget: DesktopNativeWidget, barH: Int): Pair<Int, Int> {
        val expW = resolveExplicitPx(widget.attributes["styleWidth"]) ?: widget.preferredSize?.width?.takeIf { it > 0 }
        val expH = resolveExplicitPx(widget.attributes["styleHeight"]) ?: widget.preferredSize?.height?.takeIf { it > 0 }
        if (expW != null && expH != null) {
            return expW to min(expH, barH - 8)
        }
        val impl = widget.attributes["nativeImpl"]
        return when (impl) {
            is DesktopButtonNativeImpl<*> -> {
                val sz = MacosControlRenderUtil.measureButton(impl)
                if (sz.width <= 0 || sz.height <= 0) return 0 to 0
                (expW ?: sz.width) to (expH ?: sz.height).coerceIn(26, 32)
            }
            is DesktopTextNativeImpl<*> -> {
                val txt = impl.getText().trim()
                if (txt.isEmpty()) return 0 to 0
                val fs = MacosControlRenderUtil.resolveTextFontSize(impl)
                val fw = MacosControlRenderUtil.resolveTextFontWeight(impl)
                val isBadge = "badge" in widget.cachedAllClassTokens || impl.componentClassPrefix.contains("badge")
                val measured = MacosGraphicsDrawUtil.measureText(txt, fs, fw)
                val w = (measured.width + if (isBadge) 16 else 6).coerceIn(16, 340)
                val h = (if (isBadge) 22 else (measured.height + 4)).coerceIn(20, 30)
                (expW ?: w) to (expH ?: h)
            }
            is DesktopImageNativeImpl<*> -> (expW ?: 24) to (expH ?: 24)
            is DesktopTextInputNativeImpl<*> -> (expW ?: 180) to (expH ?: 28)
            else -> (expW ?: 64) to (expH ?: 28)
        }
    }

    private fun renderNavbarLeaf(ctx: MacosRenderContext, widget: DesktopNativeWidget, bounds: Rect) {
        val impl = widget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*> ?: return
        MacosControlRenderUtil.renderLeafControl(impl, ctx, bounds)
    }

    private fun isTabPlaceholder(widget: DesktopNativeWidget): Boolean {
        val tokens = widget.cachedAllClassTokens
        return "gui-split-nav-tab-slot" in tokens || "gui-hybrid-tab-placeholder" in tokens
    }

    fun renderTabBarStrip(
        ctx: MacosRenderContext,
        widget: DesktopNativeWidget,
        bounds: Rect
    ): DesktopTabNativeImpl<*>? {
        if (!widget.visible || bounds.width <= 0 || bounds.height <= 0) return null
        widget.bounds = bounds

        val tabContainerImpl = widget.attributes["nativeImpl"] as? DesktopTabContainerNativeImpl<*>
        val origIndices = ArrayList<Int>(8)
        val entryTitles = ArrayList<String>(8)
        val entryImpls = ArrayList<DesktopTabNativeImpl<*>?>(8)
        val entryWidgets = ArrayList<DesktopNativeWidget>(8)
        val entryIsButton = ArrayList<Boolean>(8)
        val entryIsSelected = ArrayList<Boolean>(8)

        if (tabContainerImpl != null && tabContainerImpl.getTabTitles().isNotEmpty()) {
            val titles = tabContainerImpl.getTabTitles()
            val impls = tabContainerImpl.getTabImpls()
            val selectedIdx = tabContainerImpl.getSelectedTabIndex()
            for (i in titles.indices) {
                val tImpl = impls.getOrNull(i) as? DesktopTabNativeImpl<*>
                val tWidget = tImpl?.nativeWidget ?: widget
                if (tImpl != null && (!tWidget.visible || tWidget.disposed)) continue
                val isBtn = tabContainerImpl.isButtonTab(i) ||
                    tWidget.attributes["isButtonTab"] == true ||
                    tWidget.attributes["isButtonTab"] == "true" ||
                    "nav-button-tab" in tWidget.cachedAllClassTokens
                val sel = !isBtn && (i == selectedIdx || tImpl?.selectedVal == true)
                val label = tImpl?.titleVal?.takeIf { it.isNotEmpty() } ?: titles[i]
                origIndices.add(i)
                entryTitles.add(label)
                entryImpls.add(tImpl)
                entryWidgets.add(tWidget)
                entryIsButton.add(isBtn)
                entryIsSelected.add(sel)
            }
        } else {
            val tabKids = widget.children.filter {
                it.visible && !it.disposed && (it.attributes["nativeImpl"] is DesktopTabNativeImpl<*>)
            }
            for ((idx, tWidget) in tabKids.withIndex()) {
                val tImpl = tWidget.attributes["nativeImpl"] as? DesktopTabNativeImpl<*> ?: continue
                val isBtn = tWidget.attributes["isButtonTab"] == true ||
                    tWidget.attributes["isButtonTab"] == "true" ||
                    "nav-button-tab" in tWidget.cachedAllClassTokens
                val sel = !isBtn && tImpl.selectedVal
                origIndices.add(idx)
                entryTitles.add(tImpl.titleVal)
                entryImpls.add(tImpl)
                entryWidgets.add(tWidget)
                entryIsButton.add(isBtn)
                entryIsSelected.add(sel)
            }
        }

        val tabCount = origIndices.size
        if (tabCount == 0) return null

        val stripPad = 6
        val stripRect = Rect(
            bounds.x + stripPad,
            bounds.y + 3,
            (bounds.width - stripPad * 2).coerceAtLeast(20),
            (bounds.height - 6).coerceAtLeast(24)
        )
        MacosGraphicsDrawUtil.fillRoundedRect(
            ctx,
            stripRect.x,
            stripRect.y,
            stripRect.width,
            stripRect.height,
            8f,
            ctx.palette.bgElevated
        )

        val eachW = ((stripRect.width - 6) / tabCount).coerceAtLeast(32)
        var selectedTabImpl: DesktopTabNativeImpl<*>? = null
        for (i in 0 until tabCount) {
            if (entryIsSelected[i] && entryImpls[i] != null) {
                selectedTabImpl = entryImpls[i]
                break
            }
        }
        if (selectedTabImpl == null) {
            for (i in 0 until tabCount) {
                if (!entryIsButton[i] && entryImpls[i] != null) {
                    selectedTabImpl = entryImpls[i]
                    break
                }
            }
        }

        for (visIdx in 0 until tabCount) {
            val origIdx = origIndices[visIdx]
            val title = entryTitles[visIdx]
            val tImpl = entryImpls[visIdx]
            val tWidget = entryWidgets[visIdx]
            val isBtn = entryIsButton[visIdx]
            val isSelected = entryIsSelected[visIdx]

            val tx = stripRect.x + 3 + visIdx * eachW
            val tw = if (visIdx == tabCount - 1) (stripRect.right - 3 - tx).coerceAtLeast(24) else eachW - 2
            val tabRect = Rect(tx, stripRect.y + 3, tw, (stripRect.height - 6).coerceAtLeast(18))
            if (tWidget !== widget) {
                tWidget.bounds = tabRect
            }

            val isHovered = tImpl?.isHoveredState() == true || ctx.hoveredWidget === tWidget
            if (isSelected) {
                MacosGraphicsDrawUtil.fillRoundedRect(
                    ctx,
                    tabRect.x,
                    tabRect.y,
                    tabRect.width,
                    tabRect.height,
                    6f,
                    ctx.palette.accentPrimary
                )
            } else if (isHovered) {
                MacosGraphicsDrawUtil.fillRoundedRect(
                    ctx,
                    tabRect.x,
                    tabRect.y,
                    tabRect.width,
                    tabRect.height,
                    6f,
                    ctx.palette.bgHover
                )
            }

            val fg = when {
                isSelected -> RgbaColor.WHITE
                isBtn -> ctx.palette.accentPrimary
                else -> ctx.palette.textPrimary
            }
            val badgeTxt = tImpl?.badgeTextVal?.trim()?.takeIf { it.isNotEmpty() }
            val displayLabel = if (badgeTxt != null) "$title ($badgeTxt)" else title

            MacosGraphicsDrawUtil.drawText(
                ctx = ctx,
                text = displayLabel,
                x = tabRect.x + 4,
                y = tabRect.y,
                w = (tabRect.width - 8).coerceAtLeast(1),
                h = tabRect.height,
                color = fg,
                fontSizePx = 12.5f,
                weightNumeric = if (isSelected || isBtn) 600 else 500,
                alignment = TextAlignment.CENTER
            )

            ctx.registerHitRegion(
                MacosHitRegion(
                    left = tabRect.x,
                    top = tabRect.y,
                    right = tabRect.right,
                    bottom = tabRect.bottom,
                    widget = tWidget,
                    tooltip = tWidget.tooltipText,
                    cursorCss = "pointer",
                    onClick = {
                        if (tabContainerImpl != null && origIdx in tabContainerImpl.getTabTitles().indices) {
                            if (isBtn && !tabContainerImpl.isButtonTab(origIdx)) {
                                tImpl?.dispatchClick()
                            } else {
                                tabContainerImpl.requestSelectTab(origIdx)
                                if (!isBtn && tImpl != null && tImpl.hasClickListener()) {
                                    tImpl.dispatchClick()
                                }
                            }
                        } else if (tImpl != null) {
                            if (!isBtn) {
                                for (k in 0 until tabCount) {
                                    if (!entryIsButton[k]) {
                                        entryImpls[k]?.setSelectedNative(entryImpls[k] === tImpl)
                                    }
                                }
                            }
                            tImpl.dispatchClick()
                        }
                    }
                )
            )
        }
        return selectedTabImpl
    }

    fun renderTabContainer(
        ctx: MacosRenderContext,
        widget: DesktopNativeWidget,
        bounds: Rect,
        renderChildSubtree: (MacosRenderContext, DesktopNativeWidget, Rect) -> Unit
    ) {
        if (!widget.visible || bounds.width <= 0 || bounds.height <= 0) return
        widget.bounds = bounds

        val tabContainerImpl = widget.attributes["nativeImpl"] as? DesktopTabContainerNativeImpl<*>
        val hasTabs = (tabContainerImpl != null && tabContainerImpl.getTabTitles().isNotEmpty()) ||
            widget.children.any { !it.disposed && it.attributes["nativeImpl"] is DesktopTabNativeImpl<*> }

        if (!hasTabs) {
            val contentKids = widget.children.filter { it.visible && !it.disposed && !isTabPlaceholder(it) }
            if (contentKids.isNotEmpty()) {
                renderChildSubtree(ctx, contentKids.first(), bounds)
            }
            return
        }

        val stripH = (TAB_BAR_HEIGHT_PX + 4).coerceAtMost(bounds.height)
        val selectedTabImpl = renderTabBarStrip(
            ctx = ctx,
            widget = widget,
            bounds = Rect(bounds.x, bounds.y, bounds.width, stripH)
        )

        val bodyY = bounds.y + stripH + 4
        val bodyH = (bounds.bottom - bodyY).coerceAtLeast(0)
        if (bodyH <= 0) return

        val bodyBounds = Rect(bounds.x, bodyY, bounds.width, bodyH)
        val activeContent = tabContainerImpl?.getSelectedContentWidget()
            ?.takeIf { it.visible && !it.disposed && !isTabPlaceholder(it) }
            ?: widget.children.firstOrNull {
                it.visible && !it.disposed &&
                    it.attributes["nativeImpl"] !is DesktopTabNativeImpl<*> &&
                    !isTabPlaceholder(it)
            }

        if (activeContent != null) {
            renderChildSubtree(ctx, activeContent, bodyBounds)
            return
        }

        val selectedTabWidget = selectedTabImpl?.nativeWidget
        if (selectedTabWidget != null) {
            val activeKids = selectedTabWidget.children.filter { it.visible && !it.disposed && !isTabPlaceholder(it) }
            if (activeKids.size == 1) {
                renderChildSubtree(ctx, activeKids[0], bodyBounds)
            } else if (activeKids.size > 1) {
                renderChildSubtree(ctx, selectedTabWidget, bodyBounds)
            }
        }
    }

    fun renderBottomNavigationBar(
        ctx: MacosRenderContext,
        widget: DesktopNativeWidget,
        bounds: Rect
    ) {
        if (!widget.visible || bounds.width <= 0 || bounds.height <= 0) return
        widget.bounds = bounds
        MacosGraphicsDrawUtil.fillRect(ctx, bounds.x, bounds.y, bounds.width, bounds.height, ctx.palette.bgHeader)
        MacosGraphicsDrawUtil.fillRect(ctx, bounds.x, bounds.y, bounds.width, 1, ctx.palette.borderSubtle)

        val items = widget.children.filter { it.visible && !it.disposed }
        if (items.isEmpty()) return
        val itemW = max(1, bounds.width / items.size)
        for ((idx, item) in items.withIndex()) {
            val ix = bounds.x + idx * itemW
            val iw = if (idx == items.lastIndex) bounds.right - ix else itemW
            val itemRect = Rect(ix, bounds.y + 2, iw, bounds.height - 4)
            item.bounds = itemRect
            val impl = item.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
            val label = (item.attributes["text"] as? String)
                ?: (item.attributes["label"] as? String)
                ?: item.children.firstNotNullOfOrNull { (it.attributes["nativeImpl"] as? DesktopTextNativeImpl<*>)?.getText() }
                ?: ""
            val isActive = "active" in item.cachedAllClassTokens || item.attributes["selected"] == true
            val fg = if (isActive) ctx.palette.accentPrimary else ctx.palette.textSecondary
            MacosGraphicsDrawUtil.drawText(
                ctx = ctx,
                text = label,
                x = itemRect.x + 4,
                y = itemRect.y,
                w = (itemRect.width - 8).coerceAtLeast(1),
                h = itemRect.height,
                color = fg,
                fontSizePx = 12f,
                weightNumeric = if (isActive) 600 else 500,
                alignment = TextAlignment.CENTER
            )
            if (impl != null) {
                ctx.registerHitRegion(
                    MacosHitRegion(
                        left = itemRect.x,
                        top = itemRect.y,
                        right = itemRect.right,
                        bottom = itemRect.bottom,
                        widget = item,
                        tooltip = item.tooltipText,
                        cursorCss = "pointer",
                        onClick = { impl.dispatchClick() }
                    )
                )
            }
        }
    }
}
