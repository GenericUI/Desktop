@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.render

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.widget.DesktopContainerNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopDividerNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopStyledContainerNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.bootstrap.FlexDirection
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.windows.Win32DwmUxThemeBridge
import com.nexustools.genericui.windows.Win32NativeWindowHost
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Renders container and structural chrome components on Windows (`Container`, `VStack`, `HStack`,
 * `FlexBox`, `Grid`, `Card`, `Accordion`, `AlertBox`, `CollapsibleSection`, `FieldSet`,
 * `Divider`, `StyledContainer`).
 *
 * - Standard containers (`Container`, `VStack`, `HStack`, `FlexBox`, `Grid`, `Card`, `Accordion`,
 *   `AlertBox`, `CollapsibleSection`, `FieldSet`, `Divider`) render native system surfaces or
 *   `BootstrapType` (`ThemeConfig`) tints while ignoring aesthetic `ComponentStyle` skin overrides.
 * - `StyledContainer` (or any container where `component.isHostedInCustomStyleEngine()` is `true`)
 *   honors full `ComponentStyle` and `StyledVisualSpec` background, gradient, border, shadow, and
 *   corner radius properties.
 */
object Win32ContainerChromeRenderUtil {

    fun canRender(widget: DesktopNativeWidget): Boolean {
        val impl = widget.attributes["nativeImpl"]
        if (impl is DesktopContainerNativeImpl<*> || impl is DesktopDividerNativeImpl<*>) return true
        val cls = widget.nativeClassName.lowercase()
        val role = (widget.attributes["componentRole"] as? String ?: "").lowercase()
        return cls in CONTAINER_CLASSES || role in CONTAINER_ROLES
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

        if (impl is DesktopDividerNativeImpl<*> || cls == "divider" || role == "divider" || (widget.attributes["htmlTag"] as? String) == "hr") {
            return renderDivider(ctx, widget, impl as? DesktopDividerNativeImpl<*>, left, top, right)
        }
        if (cls == "alertbox" || role == "alert" || "alert" in widget.cachedAllClassTokens) {
            return renderAlertBox(ctx, widget, left, top, right)
        }
        if (cls == "fieldset" || role == "fieldset" || (widget.attributes["htmlTag"] as? String) == "fieldset") {
            return renderFieldSet(ctx, widget, left, top, right)
        }
        if (cls == "collapsiblesection" || role == "collapsible-section" || cls == "accordion" || role == "accordion") {
            return renderCollapsibleOrAccordion(ctx, widget, left, top, right)
        }
        return renderContainerOrCard(ctx, widget, left, top, right)
    }

    /**
     * Renders a horizontal or vertical `Divider`.
     */
    fun renderDivider(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        dividerImpl: DesktopDividerNativeImpl<*>?,
        left: Int,
        top: Int,
        right: Int
    ): Int {
        val attrs = dividerImpl?.nativeWidget?.attributes ?: widget.attributes
        val thickness = ((attrs["thicknessPx"] as? Number)?.toFloat()?.roundToInt() ?: 1).coerceIn(1, 8)
        val padY = 6
        val bottom = top + padY * 2 + thickness
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), bottom - top)
        val colorRef = (attrs["dividerColor"] as? RgbaColor)?.let { ctx.toColorRef(it) } ?: ctx.borderSubtleColorRef
        Win32NativeWindowHost.fillRectColor(ctx.hdc, left, top + padY, right, top + padY + thickness, colorRef)
        return bottom
    }

    /**
     * Renders an `AlertBox` with `BootstrapType` semantic tinting from the active `ThemeConfig`.
     */
    fun renderAlertBox(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        left: Int,
        top: Int,
        right: Int
    ): Int {
        val bootstrapType = Win32ControlTintRenderUtil.resolveWidgetBootstrapType(widget) ?: BootstrapType.INFO
        val tint = BootstrapColorPalette.resolveColor(bootstrapType, ctx.darkMode, ctx.themeConfig)
        val bgRgba = RgbaColor(tint.r, tint.g, tint.b, if (ctx.darkMode) 48 else 36)
        val borderRgba = RgbaColor(tint.r, tint.g, tint.b, 160)

        val padX = 12
        val padY = 10
        val children = ctx.visibleChildrenOf(widget)
        val text = Win32NativeWindowHost.extractWidgetText(widget).trim()
        val estH = if (children.isEmpty()) 40 else (children.size * 26 + padY * 2)
        val bottom = top + estH

        Win32NativeWindowHost.drawAntialiasedRoundedRectRgba(
            hdc = ctx.hdc,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            radiusPx = 6f,
            fillColor = bgRgba,
            borderColor = borderRgba,
            borderWidthPx = 1f,
            opacity = ctx.opacity
        )
        Win32NativeWindowHost.fillRectColor(ctx.hdc, left, top + 2, left + 4, bottom - 2, ctx.toColorRef(tint))

        var curY = top + padY
        if (children.isEmpty() && text.isNotEmpty()) {
            ctx.selectFont(ctx.fontNormal)
            Win32NativeWindowHost.drawTextInRect(
                hdc = ctx.hdc,
                text = text,
                left = left + padX,
                top = curY,
                right = right - padX,
                bottom = bottom - padY,
                colorRef = ctx.textPrimaryColorRef
            )
            curY = bottom - padY
        } else {
            for (child in children) {
                curY = ctx.renderChild(child, left + padX, curY, right - padX) + 4
            }
        }
        val finalBottom = max(bottom, curY + padY)
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), finalBottom - top)
        return finalBottom
    }

    /**
     * Renders a `FieldSet` using `uxtheme.dll` `BUTTON` `BP_GROUPBOX` frame and legend title.
     */
    fun renderFieldSet(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        left: Int,
        top: Int,
        right: Int
    ): Int {
        val legend = (widget.attributes["legend"] as? String)
            ?: (widget.attributes["title"] as? String)
            ?: ""
        val children = ctx.visibleChildrenOf(widget)
        val padX = 12
        var curY = top + (if (legend.isNotEmpty()) 24 else 12)
        for (child in children) {
            curY = ctx.renderChild(child, left + padX, curY, right - padX) + 6
        }
        val bottom = (curY + 8).coerceAtLeast(top + 40)
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), bottom - top)

        Win32DwmUxThemeBridge.drawUxThemeControlBackground(
            hwnd = ctx.hwnd,
            hdc = ctx.hdc,
            classList = Win32DwmUxThemeBridge.resolveExplorerClassList("BUTTON", ctx.darkMode),
            partId = Win32DwmUxThemeBridge.BP_GROUPBOX,
            stateId = if (widget.enabled) Win32DwmUxThemeBridge.GBS_NORMAL else Win32DwmUxThemeBridge.GBS_DISABLED,
            left = left,
            top = top + 8,
            right = right,
            bottom = bottom
        )
        if (legend.isNotEmpty()) {
            ctx.selectFont(ctx.fontSmallBold ?: ctx.fontNormal)
            Win32NativeWindowHost.drawTextInRect(
                hdc = ctx.hdc,
                text = legend,
                left = left + 12,
                top = top,
                right = right - 12,
                bottom = top + 20,
                colorRef = ctx.textPrimaryColorRef
            )
        }
        return bottom
    }

    /**
     * Renders a `CollapsibleSection` or `Accordion` with native header bar and expandable children.
     */
    fun renderCollapsibleOrAccordion(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        left: Int,
        top: Int,
        right: Int
    ): Int {
        val headerH = 32
        val expanded = widget.attributes["expanded"] != false && widget.attributes["collapsed"] != true
        val title = (widget.attributes["title"] as? String)
            ?: (widget.attributes["header"] as? String)
            ?: "Section"

        Win32ControlTintRenderUtil.drawNativeOrTintedControlWithTextOverlay(
            ctx = ctx,
            widget = widget,
            baseClass = "HEADER",
            partId = Win32DwmUxThemeBridge.HP_HEADERITEM,
            stateId = if (ctx.isWidgetHovered(widget, left, top, right, top + headerH)) {
                Win32DwmUxThemeBridge.HIS_HOT
            } else {
                Win32DwmUxThemeBridge.HIS_NORMAL
            },
            left = left,
            top = top,
            right = right,
            bottom = top + headerH,
            text = "${if (expanded) "▾" else "▸"}  $title",
            font = ctx.fontBold ?: ctx.fontNormal,
            centerText = false
        )

        var curY = top + headerH
        if (expanded) {
            curY += 6
            for (child in ctx.visibleChildrenOf(widget)) {
                curY = ctx.renderChild(child, left + 10, curY, right - 10) + 6
            }
        }
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), (curY - top).coerceAtLeast(headerH))
        return curY
    }

    /**
     * Renders `Container`, `VStack`, `HStack`, `FlexBox`, `Grid`, `Card`, or `StyledContainer`.
     */
    fun renderContainerOrCard(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        left: Int,
        top: Int,
        right: Int
    ): Int {
        val baseImpl = widget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
        val styledImpl = baseImpl as? DesktopStyledContainerNativeImpl<*>
        val acceptsSkin = styledImpl != null || baseImpl?.acceptsAestheticSkinStyles() == true
        val isCard = "card" in widget.cachedAllClassTokens ||
            widget.nativeClassName.equals("Card", ignoreCase = true) ||
            widget.attributes["componentRole"] == "card"

        val pad = baseImpl?.getEffectivePaddingInsets((right - left).toFloat(), 0f)
        val padLeft = pad?.left ?: if (isCard) 12 else 0
        val padRight = pad?.right ?: if (isCard) 12 else 0
        val padTop = pad?.top ?: if (isCard) 10 else 0
        val padBottom = pad?.bottom ?: if (isCard) 10 else 0
        val gap = (widget.attributes["gapPx"] as? Int) ?: 6

        val children = ctx.visibleChildrenOf(widget)
        val isHorizontal = isHorizontalLayout(widget, baseImpl)
        val contentLeft = left + padLeft
        val contentRight = (right - padRight).coerceAtLeast(contentLeft + 8)

        // First measure/estimate height if we need to draw background before children
        val estimatedBottom = top + padTop + estimateChildrenHeight(children, isHorizontal, gap) + padBottom
        drawContainerChrome(ctx, widget, baseImpl, styledImpl, acceptsSkin, isCard, left, top, right, estimatedBottom)

        var curY = top + padTop
        if (isHorizontal && children.isNotEmpty()) {
            val count = children.size
            val totalGap = gap * (count - 1).coerceAtLeast(0)
            val itemW = ((contentRight - contentLeft - totalGap) / count).coerceAtLeast(16)
            var maxBottom = curY
            for ((idx, child) in children.withIndex()) {
                val cL = contentLeft + idx * (itemW + gap)
                val cR = if (idx == count - 1) contentRight else min(contentRight, cL + itemW)
                val childBottom = ctx.renderChild(child, cL, curY, cR)
                if (childBottom > maxBottom) maxBottom = childBottom
            }
            curY = maxBottom
        } else {
            for ((idx, child) in children.withIndex()) {
                curY = ctx.renderChild(child, contentLeft, curY, contentRight)
                if (idx < children.lastIndex) curY += gap
            }
        }

        val finalBottom = (curY + padBottom).coerceAtLeast(top + (widget.preferredSize?.height ?: 0))
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), (finalBottom - top).coerceAtLeast(1))
        return finalBottom
    }

    private fun drawContainerChrome(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        baseImpl: DesktopBaseNativeImpl<*>?,
        styledImpl: DesktopStyledContainerNativeImpl<*>?,
        acceptsSkin: Boolean,
        isCard: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        if (bottom <= top || right <= left) return
        if (acceptsSkin) {
            val visuals = styledImpl?.getStyledVisuals()
            val compStyle = baseImpl?.getComponentStyle()
            val grad = visuals?.backgroundGradient ?: compStyle?.backgroundImage?.gradient
            val bgCol = visuals?.backgroundColor ?: compStyle?.backgroundColor
            val borderCol = visuals?.borderColor ?: compStyle?.borderColor
            val borderWidth = visuals?.borderWidthPx ?: compStyle?.borderWidth?.top?.resolvePx() ?: 0f
            val radiusPx = visuals?.borderRadiusPx ?: compStyle?.borderRadius?.topLeft?.resolvePx() ?: 0f

            if (grad != null) {
                Win32NativeWindowHost.drawGradientRect(ctx.hdc, left, top, right, bottom, grad, opacity = ctx.opacity)
            } else if (bgCol != null || (borderCol != null && borderWidth > 0f)) {
                Win32NativeWindowHost.drawAntialiasedRoundedRectRgba(
                    hdc = ctx.hdc,
                    left = left,
                    top = top,
                    right = right,
                    bottom = bottom,
                    radiusPx = radiusPx,
                    fillColor = bgCol,
                    borderColor = borderCol,
                    borderWidthPx = borderWidth,
                    opacity = ctx.opacity
                )
            }
            return
        }

        // Standard container or Card: check BootstrapType tint first, otherwise native surface on Card
        val bootstrapType = Win32ControlTintRenderUtil.resolveWidgetBootstrapType(widget)
        if (bootstrapType != null && bootstrapType != BootstrapType.NONE) {
            val tint = BootstrapColorPalette.resolveColor(bootstrapType, ctx.darkMode, ctx.themeConfig)
            val outline = Win32ControlTintRenderUtil.isWidgetOutlineMode(widget)
            Win32NativeWindowHost.drawAntialiasedRoundedRectRgba(
                hdc = ctx.hdc,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                radiusPx = if (isCard) 6f else 4f,
                fillColor = if (outline) ctx.fromColorRef(ctx.bgCardColorRef) else tint,
                borderColor = tint,
                borderWidthPx = 1f,
                opacity = ctx.opacity
            )
        } else if (isCard) {
            Win32NativeWindowHost.drawRoundedRect(
                hdc = ctx.hdc,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                radius = 6,
                fillColor = ctx.bgCardColorRef,
                borderColor = ctx.borderSubtleColorRef
            )
        }
    }

    private fun isHorizontalLayout(
        widget: DesktopNativeWidget,
        baseImpl: DesktopBaseNativeImpl<*>?
    ): Boolean {
        if (baseImpl?.getFlexDirection() == FlexDirection.ROW || baseImpl?.getFlexDirection() == FlexDirection.ROW_REVERSE) {
            return true
        }
        val tokens = widget.cachedAllClassTokens
        if ("hstack" in tokens || "flex-row" in tokens || "row" in tokens) return true
        val cls = widget.nativeClassName.lowercase()
        val role = (widget.attributes["componentRole"] as? String ?: "").lowercase()
        return cls == "hstack" || role == "hstack" || widget.attributes["flexDirection"] == "row"
    }

    private fun estimateChildrenHeight(
        children: List<DesktopNativeWidget>,
        isHorizontal: Boolean,
        gap: Int
    ): Int {
        if (children.isEmpty()) return 0
        if (isHorizontal) {
            return children.maxOfOrNull { it.preferredSize?.height ?: it.bounds.height.takeIf { h -> h > 0 } ?: 30 } ?: 30
        }
        var sum = 0
        for ((idx, c) in children.withIndex()) {
            sum += c.preferredSize?.height ?: c.bounds.height.takeIf { it > 0 } ?: 28
            if (idx < children.lastIndex) sum += gap
        }
        return sum
    }

    private val CONTAINER_CLASSES = setOf(
        "container",
        "vstack",
        "hstack",
        "flexbox",
        "grid",
        "card",
        "accordion",
        "alertbox",
        "collapsiblesection",
        "fieldset",
        "divider",
        "styledcontainer"
    )
    private val CONTAINER_ROLES = setOf(
        "container",
        "vstack",
        "hstack",
        "flexbox",
        "grid",
        "card",
        "accordion",
        "alert",
        "collapsible-section",
        "fieldset",
        "divider",
        "styled-container"
    )
}
