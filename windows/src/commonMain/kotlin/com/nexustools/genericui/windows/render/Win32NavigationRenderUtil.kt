@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.render

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.widget.DesktopBottomNavigationBarNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopNavbarNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTabContainerNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTabNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.windows.Win32DwmUxThemeBridge
import com.nexustools.genericui.windows.Win32NativeWindowHost
import kotlin.math.max
import kotlin.math.min

/**
 * Renders navigation and bar chrome components on Windows (`TabContainer`, `Tab`, `MenuBar`,
 * `ToolBar`, `Breadcrumb`, `Pagination`, `Stepper`, `BottomNavigationBar`, `NavigationRail`,
 * `Drawer`, `StatusBar`).
 *
 * Uses native `uxtheme.dll` `TAB` (`TABP_TOPTABITEM` / `TABP_PANE`), `TOOLBAR` (`TP_BUTTON`),
 * and `STATUS` (`SP_PANE` / `SP_GRIPPER`) control surfaces with `BootstrapType` tinting via
 * [Win32ControlTintRenderUtil].
 */
object Win32NavigationRenderUtil {

    fun canRender(widget: DesktopNativeWidget): Boolean {
        val impl = widget.attributes["nativeImpl"]
        if (impl is DesktopTabContainerNativeImpl<*> ||
            impl is DesktopTabNativeImpl<*> ||
            impl is DesktopNavbarNativeImpl<*> ||
            impl is DesktopBottomNavigationBarNativeImpl<*>
        ) {
            return true
        }
        val cls = widget.nativeClassName.lowercase()
        val role = (widget.attributes["componentRole"] as? String ?: "").lowercase()
        return cls in NAVIGATION_CLASSES || role in NAVIGATION_ROLES
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
            impl is DesktopTabContainerNativeImpl<*> || cls == "tabcontainer" || role == "tab-container" ->
                renderTabContainer(ctx, widget, impl as? DesktopTabContainerNativeImpl<*>, left, top, right)
            impl is DesktopTabNativeImpl<*> || cls == "tab" || role == "tab" ->
                renderStandaloneTab(ctx, widget, impl as? DesktopTabNativeImpl<*>, left, top, right)
            impl is DesktopBottomNavigationBarNativeImpl<*> || cls == "bottomnavigationbar" || role == "bottom-navigation-bar" ->
                renderBottomNavigationBar(ctx, widget, impl as? DesktopBottomNavigationBarNativeImpl<*>, left, top, right)
            cls == "navigationrail" || role == "navigation-rail" || cls == "drawer" || role == "drawer" ->
                renderNavigationRailOrDrawer(ctx, widget, left, top, right)
            cls == "breadcrumb" || role == "breadcrumb" || "breadcrumb" in widget.cachedAllClassTokens ->
                renderBreadcrumb(ctx, widget, left, top, right)
            cls == "pagination" || role == "pagination" || "pagination" in widget.cachedAllClassTokens ->
                renderPagination(ctx, widget, left, top, right)
            cls == "stepper" || role == "stepper" ->
                renderStepper(ctx, widget, left, top, right)
            cls == "statusbar" || role == "status-bar" ->
                renderStatusBar(ctx, widget, left, top, right)
            else ->
                renderMenuOrToolBar(ctx, widget, impl as? DesktopNavbarNativeImpl<*>, left, top, right)
        }
    }

    /**
     * Renders a `TabContainer` using `uxtheme.dll` `TAB` (`TABP_TOPTABITEM` + `TABP_PANE`) and
     * renders the active tab's content below the tab strip.
     */
    fun renderTabContainer(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        tabContainerImpl: DesktopTabContainerNativeImpl<*>?,
        left: Int,
        top: Int,
        right: Int
    ): Int {
        val tabBarH = 34
        val tabChildren = ctx.visibleChildrenOf(widget)
        val tabWidgets = tabChildren.filter {
            it.attributes["nativeImpl"] is DesktopTabNativeImpl<*> ||
                it.nativeClassName.equals("Tab", ignoreCase = true) ||
                it.attributes["componentRole"] == "tab"
        }.ifEmpty { tabChildren }

        val activeIdx = ((tabContainerImpl?.nativeWidget?.attributes?.get("selectedTabIndex") as? Int)
            ?: (widget.attributes["selectedTabIndex"] as? Int)
            ?: 0).coerceIn(0, (tabWidgets.size - 1).coerceAtLeast(0))
        val tabClassList = Win32DwmUxThemeBridge.resolveExplorerClassList("TAB", ctx.darkMode)

        var tabX = left + 4
        for ((idx, tabWidget) in tabWidgets.withIndex()) {
            val tabImpl = tabWidget.attributes["nativeImpl"] as? DesktopTabNativeImpl<*>
            val title = tabImpl?.titleVal?.takeIf { it.isNotBlank() }
                ?: (tabWidget.attributes["title"] as? String)
                ?: Win32NativeWindowHost.extractWidgetText(tabWidget).ifBlank { "Tab ${idx + 1}" }
            val icon = tabImpl?.iconVal ?: (tabWidget.attributes["icon"] as? String)
            val displayTitle = if (!icon.isNullOrBlank()) "$icon  $title" else title
            val tabW = (displayTitle.length * 7 + 28).coerceIn(68, 180)
            val tabR = min(right - 4, tabX + tabW)
            if (tabX >= tabR) break

            val isSelected = idx == activeIdx
            val hovered = ctx.isWidgetHovered(tabWidget, tabX, top, tabR, top + tabBarH)
            val stateId = when {
                !tabWidget.enabled -> Win32DwmUxThemeBridge.TIS_DISABLED
                isSelected -> Win32DwmUxThemeBridge.TIS_SELECTED
                hovered -> Win32DwmUxThemeBridge.TIS_HOT
                else -> Win32DwmUxThemeBridge.TIS_NORMAL
            }
            val drewTab = Win32ControlTintRenderUtil.drawNativeOrTintedControlSurface(
                ctx = ctx,
                widget = tabWidget,
                baseClass = "TAB",
                partId = Win32DwmUxThemeBridge.TABP_TOPTABITEM,
                stateId = stateId,
                left = tabX,
                top = top + (if (isSelected) 0 else 2),
                right = tabR,
                bottom = top + tabBarH,
                explicitType = if (isSelected) Win32ControlTintRenderUtil.resolveWidgetBootstrapType(widget) else null,
                hovered = hovered,
                pressed = isSelected
            )
            if (!drewTab) {
                Win32DwmUxThemeBridge.drawUxThemeControlBackground(
                    hwnd = ctx.hwnd,
                    hdc = ctx.hdc,
                    classList = tabClassList,
                    partId = Win32DwmUxThemeBridge.TABP_TOPTABITEM,
                    stateId = stateId,
                    left = tabX,
                    top = top,
                    right = tabR,
                    bottom = top + tabBarH
                )
            }
            if (isSelected) {
                Win32NativeWindowHost.fillRectColor(ctx.hdc, tabX + 4, top + tabBarH - 3, tabR - 4, top + tabBarH, ctx.accentBlueColorRef)
            }
            ctx.selectFont(if (isSelected) (ctx.fontBold ?: ctx.fontNormal) else ctx.fontNormal)
            Win32NativeWindowHost.drawTextInRect(
                hdc = ctx.hdc,
                text = displayTitle,
                left = tabX + 8,
                top = top + 2,
                right = tabR - 8,
                bottom = top + tabBarH - 2,
                colorRef = if (isSelected) ctx.textPrimaryColorRef else ctx.textSecondaryColorRef,
                center = true
            )
            tabWidget.bounds = Rect(tabX, top, tabR - tabX, tabBarH)
            ctx.registerHitRegion(
                Win32NativeWindowHost.HitRegion(
                    left = tabX,
                    top = top,
                    right = tabR,
                    bottom = top + tabBarH,
                    tooltip = tabImpl?.getTooltipText(),
                    widget = tabWidget,
                    onClick = { _, _ ->
                        tabContainerImpl?.requestSelectTab(idx)
                        tabImpl?.dispatchClick()
                    }
                )
            )
            tabX = tabR + 2
        }

        // Draw active tab pane body
        val paneTop = top + tabBarH
        var contentBottom = paneTop + 12
        val activeTab = tabContainerImpl?.getSelectedContentWidget() ?: tabWidgets.getOrNull(activeIdx)
        val activePaneChildren = if (activeTab != null) ctx.visibleChildrenOf(activeTab) else emptyList()
        val estPaneBottom = paneTop + (activePaneChildren.size * 32 + 20).coerceAtLeast(48)
        Win32DwmUxThemeBridge.drawUxThemeControlBackground(
            hwnd = ctx.hwnd,
            hdc = ctx.hdc,
            classList = tabClassList,
            partId = Win32DwmUxThemeBridge.TABP_PANE,
            stateId = Win32DwmUxThemeBridge.TIS_NORMAL,
            left = left,
            top = paneTop,
            right = right,
            bottom = estPaneBottom
        )
        for (child in activePaneChildren) {
            contentBottom = ctx.renderChild(child, left + 10, contentBottom, right - 10) + 6
        }
        val finalBottom = max(estPaneBottom, contentBottom + 6)
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), finalBottom - top)
        return finalBottom
    }

    /**
     * Renders a standalone `Tab` item.
     */
    fun renderStandaloneTab(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        tabImpl: DesktopTabNativeImpl<*>?,
        left: Int,
        top: Int,
        right: Int,
        height: Int = 32
    ): Int {
        val bottom = top + height
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), height)
        val title = tabImpl?.titleVal?.takeIf { it.isNotBlank() }
            ?: Win32NativeWindowHost.extractWidgetText(widget).ifBlank { "Tab" }
        val active = tabImpl?.selectedVal == true
        Win32ControlTintRenderUtil.drawNativeOrTintedControlWithTextOverlay(
            ctx = ctx,
            widget = widget,
            baseClass = "TAB",
            partId = Win32DwmUxThemeBridge.TABP_TOPTABITEM,
            stateId = if (active) Win32DwmUxThemeBridge.TIS_SELECTED else Win32DwmUxThemeBridge.TIS_NORMAL,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            text = title,
            icon = tabImpl?.iconVal,
            font = if (active) (ctx.fontBold ?: ctx.fontNormal) else ctx.fontNormal,
            centerText = true
        )
        return bottom
    }

    /**
     * Renders a `MenuBar`, `Navbar`, or `ToolBar` using `uxtheme.dll` `TOOLBAR` (`TP_BUTTON`) parts.
     */
    fun renderMenuOrToolBar(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        navbarImpl: DesktopNavbarNativeImpl<*>?,
        left: Int,
        top: Int,
        right: Int,
        height: Int = 38
    ): Int {
        val bottom = top + height
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), height)

        Win32ControlTintRenderUtil.drawNativeOrTintedControlSurface(
            ctx = ctx,
            widget = widget,
            baseClass = "TOOLBAR",
            partId = Win32DwmUxThemeBridge.TP_BUTTON,
            stateId = Win32DwmUxThemeBridge.TS_NORMAL,
            left = left,
            top = top,
            right = right,
            bottom = bottom
        )
        Win32NativeWindowHost.fillRectColor(ctx.hdc, left, bottom - 1, right, bottom, ctx.borderSubtleColorRef)

        var curX = left + 10
        val brand = navbarImpl?.getBrandTitle()?.trim().orEmpty()
        if (brand.isNotEmpty()) {
            ctx.selectFont(ctx.fontBold ?: ctx.fontNormal)
            val brandW = (brand.length * 8 + 16).coerceIn(48, 200)
            Win32NativeWindowHost.drawTextInRect(
                hdc = ctx.hdc,
                text = brand,
                left = curX,
                top = top,
                right = min(right - 8, curX + brandW),
                bottom = bottom,
                colorRef = ctx.textPrimaryColorRef
            )
            curX += brandW + 8
        }

        for (child in ctx.visibleChildrenOf(widget)) {
            val txt = Win32NativeWindowHost.extractWidgetText(child).trim()
            val itemW = (txt.length * 7 + 24).coerceIn(36, 140)
            val itemR = min(right - 6, curX + itemW)
            if (curX >= itemR) break
            Win32ButtonRenderUtil.render(ctx, child, curX, top + 4, itemR, fixedHeight = height - 8)
            curX = itemR + 4
        }
        return bottom
    }

    /**
     * Renders a `Breadcrumb` trail (`Home / Library / Data`).
     */
    fun renderBreadcrumb(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        left: Int,
        top: Int,
        right: Int,
        height: Int = 28
    ): Int {
        val bottom = top + height
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), height)
        val children = ctx.visibleChildrenOf(widget)
        var curX = left + 4
        ctx.selectFont(ctx.fontNormal)
        for ((idx, item) in children.withIndex()) {
            val txt = Win32NativeWindowHost.extractWidgetText(item).trim().ifEmpty { "Item ${idx + 1}" }
            val w = (txt.length * 7 + 12).coerceIn(28, 180)
            val itemR = min(right - 4, curX + w)
            if (curX >= itemR) break
            val isLast = idx == children.lastIndex
            Win32NativeWindowHost.drawTextInRect(
                hdc = ctx.hdc,
                text = txt,
                left = curX,
                top = top,
                right = itemR,
                bottom = bottom,
                colorRef = if (isLast) ctx.textPrimaryColorRef else ctx.accentBlueColorRef
            )
            val baseImpl = item.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
            ctx.registerHitRegion(
                Win32NativeWindowHost.HitRegion(
                    left = curX,
                    top = top,
                    right = itemR,
                    bottom = bottom,
                    tooltip = baseImpl?.getTooltipText(),
                    widget = item,
                    onClick = { _, _ -> baseImpl?.dispatchClick() }
                )
            )
            curX = itemR
            if (!isLast && curX + 16 < right) {
                Win32NativeWindowHost.drawTextInRect(
                    hdc = ctx.hdc,
                    text = "/",
                    left = curX,
                    top = top,
                    right = curX + 14,
                    bottom = bottom,
                    colorRef = ctx.textMutedColorRef,
                    center = true
                )
                curX += 14
            }
        }
        return bottom
    }

    /**
     * Renders a `Pagination` control row of native page buttons.
     */
    fun renderPagination(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        left: Int,
        top: Int,
        right: Int,
        height: Int = 30
    ): Int {
        val bottom = top + height
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), height)
        val children = ctx.visibleChildrenOf(widget)
        var curX = left
        for (pageWidget in children) {
            val txt = Win32NativeWindowHost.extractWidgetText(pageWidget).trim()
            val w = (txt.length * 7 + 20).coerceIn(32, 96)
            val itemR = min(right, curX + w)
            if (curX >= itemR) break
            Win32ButtonRenderUtil.render(ctx, pageWidget, curX, top, itemR, fixedHeight = height)
            curX = itemR + 2
        }
        return bottom
    }

    /**
     * Renders a horizontal `Stepper` wizard progress indicator.
     */
    fun renderStepper(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        left: Int,
        top: Int,
        right: Int,
        height: Int = 36
    ): Int {
        val bottom = top + height
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), height)
        val steps = ctx.visibleChildrenOf(widget)
        if (steps.isEmpty()) return bottom
        val activeStep = (widget.attributes["activeStep"] as? Number)?.toInt() ?: 0
        val stepW = ((right - left) / steps.size).coerceAtLeast(48)
        for ((idx, step) in steps.withIndex()) {
            val sL = left + idx * stepW
            val sR = if (idx == steps.lastIndex) right else (sL + stepW - 6)
            val isCurrent = idx == activeStep
            val isCompleted = idx < activeStep
            val stepType = when {
                isCurrent -> BootstrapType.PRIMARY
                isCompleted -> BootstrapType.SUCCESS
                else -> BootstrapType.NONE
            }
            val label = "${idx + 1}. ${Win32NativeWindowHost.extractWidgetText(step).trim().ifEmpty { "Step ${idx + 1}" }}"
            Win32ControlTintRenderUtil.drawNativeOrTintedControlWithTextOverlay(
                ctx = ctx,
                widget = step,
                baseClass = "BUTTON",
                partId = Win32DwmUxThemeBridge.BP_PUSHBUTTON,
                stateId = if (isCurrent) Win32DwmUxThemeBridge.PBS_DEFAULTED else Win32DwmUxThemeBridge.PBS_NORMAL,
                left = sL,
                top = top + 3,
                right = sR,
                bottom = bottom - 3,
                text = label,
                font = if (isCurrent) (ctx.fontBold ?: ctx.fontNormal) else ctx.fontNormal,
                explicitType = stepType
            )
        }
        return bottom
    }

    /**
     * Renders a `BottomNavigationBar`.
     */
    fun renderBottomNavigationBar(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        bottomNavImpl: DesktopBottomNavigationBarNativeImpl<*>?,
        left: Int,
        top: Int,
        right: Int,
        height: Int = 48
    ): Int {
        val bottom = top + height
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), height)
        Win32NativeWindowHost.fillRectColor(ctx.hdc, left, top, right, bottom, ctx.bgElevatedColorRef)
        Win32NativeWindowHost.fillRectColor(ctx.hdc, left, top, right, top + 1, ctx.borderSubtleColorRef)

        val items = bottomNavImpl?.getItems().orEmpty()
        val selectedId = bottomNavImpl?.getSelectedItemId()
        if (items.isNotEmpty()) {
            val itemW = ((right - left) / items.size).coerceAtLeast(48)
            for ((idx, item) in items.withIndex()) {
                val (itemId, label, icon) = item
                val iL = left + idx * itemW
                val iR = if (idx == items.lastIndex) right else (iL + itemW)
                val selected = itemId == selectedId || (selectedId == null && idx == 0)
                val display = if (!icon.isNullOrBlank()) "$icon  $label" else label
                ctx.selectFont(if (selected) (ctx.fontBold ?: ctx.fontNormal) else ctx.fontNormal)
                Win32NativeWindowHost.drawTextInRect(
                    hdc = ctx.hdc,
                    text = display,
                    left = iL + 4,
                    top = top + 4,
                    right = iR - 4,
                    bottom = bottom - 4,
                    colorRef = if (selected) ctx.accentBlueColorRef else ctx.textSecondaryColorRef,
                    center = true
                )
                ctx.registerHitRegion(
                    Win32NativeWindowHost.HitRegion(
                        left = iL,
                        top = top,
                        right = iR,
                        bottom = bottom,
                        tooltip = label,
                        widget = widget,
                        onClick = { _, _ -> bottomNavImpl?.setSelectedItemId(itemId) }
                    )
                )
            }
        }
        return bottom
    }

    /**
     * Renders a vertical `NavigationRail` or `Drawer`.
     */
    fun renderNavigationRailOrDrawer(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        left: Int,
        top: Int,
        right: Int
    ): Int {
        val children = ctx.visibleChildrenOf(widget)
        var curY = top + 8
        for (child in children) {
            curY = ctx.renderChild(child, left + 6, curY, right - 6) + 4
        }
        val bottom = max(top + 48, curY + 8)
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), bottom - top)
        return bottom
    }

    /**
     * Renders a native `StatusBar` using `uxtheme.dll` `STATUS` (`SP_PANE` / `SP_GRIPPER`).
     */
    fun renderStatusBar(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        left: Int,
        top: Int,
        right: Int,
        height: Int = 24
    ): Int {
        val bottom = top + height
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), height)
        val statusClassList = Win32DwmUxThemeBridge.resolveExplorerClassList("STATUS", ctx.darkMode)
        val drewStatus = Win32DwmUxThemeBridge.drawUxThemeControlBackground(
            hwnd = ctx.hwnd,
            hdc = ctx.hdc,
            classList = statusClassList,
            partId = Win32DwmUxThemeBridge.SP_PANE,
            stateId = 1,
            left = left,
            top = top,
            right = right,
            bottom = bottom
        )
        if (!drewStatus) {
            Win32NativeWindowHost.fillRectColor(ctx.hdc, left, top, right, bottom, ctx.bgElevatedColorRef)
            Win32NativeWindowHost.fillRectColor(ctx.hdc, left, top, right, top + 1, ctx.borderSubtleColorRef)
        }
        val text = Win32NativeWindowHost.extractWidgetText(widget).trim().ifEmpty { "Ready" }
        ctx.selectFont(ctx.fontSmall ?: ctx.fontNormal)
        Win32NativeWindowHost.drawTextInRect(
            hdc = ctx.hdc,
            text = text,
            left = left + 8,
            top = top,
            right = right - 20,
            bottom = bottom,
            colorRef = ctx.textSecondaryColorRef
        )
        return bottom
    }

    private val NAVIGATION_CLASSES = setOf(
        "tabcontainer",
        "tab",
        "menubar",
        "navbar",
        "toolbar",
        "breadcrumb",
        "pagination",
        "stepper",
        "bottomnavigationbar",
        "navigationrail",
        "drawer",
        "statusbar"
    )
    private val NAVIGATION_ROLES = setOf(
        "tab-container",
        "tab",
        "menu-bar",
        "navbar",
        "tool-bar",
        "toolbar",
        "breadcrumb",
        "pagination",
        "stepper",
        "bottom-navigation-bar",
        "navigation-rail",
        "drawer",
        "status-bar"
    )
}
