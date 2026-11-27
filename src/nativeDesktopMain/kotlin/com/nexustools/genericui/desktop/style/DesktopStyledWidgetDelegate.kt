package com.nexustools.genericui.desktop.style

import com.nexustools.genericui.component.layout.LayoutBackendTarget
import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.style.css.StyledVisualSpec
import com.nexustools.genericui.style.theme.SystemThemeDetector
import com.nexustools.genericui.system.style.StyledWidgetVisualController

/**
 * Shared desktop delegate wrapping `:system`'s [StyledWidgetVisualController] for
 * [com.nexustools.genericui.desktop.widget.DesktopStyledContainerNativeImpl],
 * [com.nexustools.genericui.desktop.widget.DesktopStyledComponentNativeImpl], and
 * [com.nexustools.genericui.desktop.widget.DesktopStyledButtonNativeImpl].
 *
 * Synchronizes resolved base and pseudo-state visuals (`:hover`, `:focus`, `:active`, `:disabled`),
 * `backgroundColor`, gradients, borders, box shadows, focus rings, `opacity`, and all 10
 * `backdrop-filter` / `filter` operations onto the host [DesktopBaseNativeImpl.nativeWidget].
 */
class DesktopStyledWidgetDelegate(
    private val owner: DesktopBaseNativeImpl<*>,
    private val visualsAttributeKey: String,
    allowsBootstrapTypeTint: Boolean,
    private val backendTarget: LayoutBackendTarget? = null,
    private val onOpacitySynced: (Float) -> Unit = { op -> owner.setOpacity(op) },
    private val onStyleSynced: () -> Unit = {}
) {
    internal val controller = StyledWidgetVisualController(allowsBootstrapTypeTint = allowsBootstrapTypeTint)

    fun invalidate() {
        controller.invalidate()
    }

    fun setBaseVisuals(visuals: StyledVisualSpec, visualOnly: Boolean = false) {
        syncControllerInputs()
        if (controller.baseVisuals == visuals && owner.nativeWidget.attributes["baseVisuals"] == visuals) return
        val prev = controller.baseVisuals
        controller.baseVisuals = visuals
        val cur = controller.baseVisuals
        if (prev == cur && owner.nativeWidget.attributes["baseVisuals"] == cur) return
        owner.nativeWidget.attributes["baseVisuals"] = cur
        syncToWidget(visualOnly = visualOnly)
    }

    fun getBaseVisuals(): StyledVisualSpec = controller.baseVisuals

    fun setHoverVisuals(visuals: StyledVisualSpec?) {
        syncControllerInputs()
        if (controller.hoverVisuals == visuals && matchesNullableAttr("hoverVisuals", visuals)) return
        val prev = controller.hoverVisuals
        controller.hoverVisuals = visuals
        val cur = controller.hoverVisuals
        if (prev == cur && matchesNullableAttr("hoverVisuals", cur)) return
        setOrRemoveAttr("hoverVisuals", cur)
        syncToWidget()
    }

    fun getHoverVisuals(): StyledVisualSpec? = controller.hoverVisuals

    fun setFocusVisuals(visuals: StyledVisualSpec?) {
        syncControllerInputs()
        if (controller.focusVisuals == visuals && matchesNullableAttr("focusVisuals", visuals)) return
        val prev = controller.focusVisuals
        controller.focusVisuals = visuals
        val cur = controller.focusVisuals
        if (prev == cur && matchesNullableAttr("focusVisuals", cur)) return
        setOrRemoveAttr("focusVisuals", cur)
        syncToWidget()
    }

    fun getFocusVisuals(): StyledVisualSpec? = controller.focusVisuals

    fun setActiveVisuals(visuals: StyledVisualSpec?) {
        syncControllerInputs()
        if (controller.activeVisuals == visuals && matchesNullableAttr("activeVisuals", visuals)) return
        val prev = controller.activeVisuals
        controller.activeVisuals = visuals
        val cur = controller.activeVisuals
        if (prev == cur && matchesNullableAttr("activeVisuals", cur)) return
        setOrRemoveAttr("activeVisuals", cur)
        syncToWidget()
    }

    fun getActiveVisuals(): StyledVisualSpec? = controller.activeVisuals

    fun setDisabledVisuals(visuals: StyledVisualSpec?) {
        syncControllerInputs()
        if (controller.disabledVisuals == visuals && matchesNullableAttr("disabledVisuals", visuals)) return
        val prev = controller.disabledVisuals
        controller.disabledVisuals = visuals
        val cur = controller.disabledVisuals
        if (prev == cur && matchesNullableAttr("disabledVisuals", cur)) return
        setOrRemoveAttr("disabledVisuals", cur)
        syncToWidget()
    }

    fun getDisabledVisuals(): StyledVisualSpec? = controller.disabledVisuals

    fun resolveBaseVisuals(): StyledVisualSpec {
        syncControllerInputs()
        val isDark = resolveIsDarkTheme()
        val snapshot = controller.sanitizeBootstrapSnapshot(owner.getStyleSnapshot(), backendTarget)
        val computedBase = StyledVisualSpec.fromComponentAndComputed(
            componentStyle = owner.getComponentStyle(),
            computedStyle = owner.getComputedStyle(),
            snapshot = snapshot,
            darkTheme = isDark
        )
        val sanitizedBase = controller.sanitizeVisuals(
            spec = controller.baseVisuals,
            isDarkTheme = isDark,
            backendTarget = backendTarget
        )
        return computedBase.mergeWith(sanitizedBase)
    }

    fun resolveCurrentVisuals(): StyledVisualSpec {
        syncControllerInputs()
        return controller.resolveCurrentVisuals(
            isDarkTheme = resolveIsDarkTheme(),
            backendTarget = backendTarget
        )
    }

    fun syncToWidget(visualOnly: Boolean = false) {
        controller.invalidate()
        val resolved = resolveCurrentVisuals()
        val attrs = owner.nativeWidget.attributes
        val prevResolved = attrs["styledVisuals"] as? StyledVisualSpec
        val prevPadding = owner.nativeWidget.padding
        attrs[visualsAttributeKey] = resolved
        attrs["styledVisuals"] = resolved
        attrs["useSystemControlAppearance"] = "false"

        setOrRemoveAttr("backgroundColor", resolved.backgroundColor)
        setOrRemoveAttr("backgroundGradient", resolved.backgroundGradient)
        setOrRemoveAttr("textColor", resolved.textColor)
        setOrRemoveAttr("borderColor", resolved.borderColor)
        setOrRemoveAttr("borderWidthPx", resolved.borderWidthPx)
        setOrRemoveAttr("borderStyle", resolved.borderStyle)
        setOrRemoveAttr("borderRadiusPx", resolved.borderRadiusPx)
        setOrRemoveAttr("boxShadowColor", resolved.boxShadowColor)
        setOrRemoveAttr("boxShadowBlurPx", resolved.boxShadowBlurPx)
        setOrRemoveAttr("focusRingColor", resolved.focusRingColor)
        setOrRemoveAttr("focusRingWidthPx", resolved.focusRingWidthPx)

        resolved.opacity?.let { op ->
            onOpacitySynced(op.coerceIn(0f, 1f))
        }
        resolved.padding?.let { pad ->
            owner.nativeWidget.padding = pad
        }

        val effBackdrop = controller.resolveEffectiveBackdropFilter(resolved)
        if (effBackdrop != null && !effBackdrop.isNone) {
            attrs["backdropFilter"] = effBackdrop
            val blurPx = resolved.backdropBlurPx ?: effBackdrop.blur?.resolvePx()
            if (blurPx != null && blurPx > 0f) {
                attrs["backdropBlurPx"] = blurPx
            } else {
                attrs.remove("backdropBlurPx")
            }
        } else {
            attrs.remove("backdropFilter")
            attrs.remove("backdropBlurPx")
        }

        val effFilter = controller.resolveEffectiveForegroundFilter(resolved)
        if (effFilter != null && !effFilter.isNone) {
            attrs["cssFilter"] = effFilter
        } else {
            attrs.remove("cssFilter")
        }

        val layoutPropsChanged = prevPadding != owner.nativeWidget.padding ||
            prevResolved?.borderWidthPx != resolved.borderWidthPx ||
            prevResolved?.widthPx != resolved.widthPx ||
            prevResolved?.heightPx != resolved.heightPx ||
            prevResolved?.minWidthPx != resolved.minWidthPx ||
            prevResolved?.minHeightPx != resolved.minHeightPx ||
            prevResolved?.gapPx != resolved.gapPx
        val effectiveVisualOnly = visualOnly || !layoutPropsChanged

        onStyleSynced()
        owner.nativeWidget.notifyTreeInvalidated(visualOnly = effectiveVisualOnly)
    }

    private fun syncControllerInputs() {
        val attrs = owner.nativeWidget.attributes
        controller.componentTagName = (attrs["tagName"] as? String) ?: owner.nativeWidget.nativeClassName
        controller.componentClassPrefix = owner.componentClassPrefix
        controller.isCustomStyleEngine = owner.isHostedInCustomStyleEngine()
        controller.lastComponentStyle = owner.getComponentStyle()
        controller.lastComputedStyle = owner.getComputedStyle()
        controller.bootstrapSnapshot = owner.getStyleSnapshot()
        controller.enabled = owner.isEnabled()
        controller.hovered = owner.isHoveredState()
        controller.focused = owner.isFocusedState() && owner.isFocusable() && owner.nativeWidget.isEffectivelyFocusable()
        controller.activePressed = owner.isActivePressedState()
    }

    private fun matchesNullableAttr(key: String, value: Any?): Boolean =
        if (value == null) !owner.nativeWidget.attributes.containsKey(key)
        else owner.nativeWidget.attributes[key] == value

    private fun resolveIsDarkTheme(): Boolean =
        (Application.current?.getResolvedTheme()
            ?: SystemThemeDetector.detectSystemTheme()) == ThemeMode.DARK

    private fun setOrRemoveAttr(key: String, value: Any?) {
        if (value != null) {
            owner.nativeWidget.attributes[key] = value
        } else {
            owner.nativeWidget.attributes.remove(key)
        }
    }
}
