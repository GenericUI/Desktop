package com.nexustools.genericui.gtk

import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.desktop.widget.DesktopButtonNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.gradient.Gradient.Gradient
import com.nexustools.genericui.style.theme.SystemThemeDetector
import com.nexustools.genericui.style.transition.CssTransitionSpec

/**
 * GTK4 / Libadwaita `GtkButton` implementation of [com.nexustools.genericui.nativeimpl.action.ButtonNativeImpl].
 *
 * Non-styled [GtkButtonNativeImpl] instances use native GTK4 / Libadwaita control rendering (`ADW_COLOR_SCHEME_FORCE_DARK` /
 * `ADW_COLOR_SCHEME_FORCE_LIGHT` / `ADW_COLOR_SCHEME_DEFAULT` for Dark / Light / Auto themes) and ignore custom CSS gradients
 * and `backdrop-filter` blur.
 */
open class GtkButtonNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkButton"),
    classPrefix: String = "btn"
) : DesktopButtonNativeImpl<GtkWidgetHandle>(nativeWidget, classPrefix) {

    init {
        syncGtkButtonAppearance()
    }

    protected open fun syncGtkButtonAppearance() {
        val effectiveTheme = Application.current?.getResolvedTheme() ?: SystemThemeDetector.detectSystemTheme()
        val isDark = effectiveTheme == ThemeMode.DARK
        nativeWidget.attributes["effectiveThemeMode"] = effectiveTheme
        nativeWidget.attributes["gtkPreferDarkTheme"] = isDark
        nativeWidget.attributes["adwColorScheme"] = if (isDark) "ADW_COLOR_SCHEME_FORCE_DARK" else "ADW_COLOR_SCHEME_FORCE_LIGHT"
        nativeWidget.attributes.remove("backdropBlurPx")
        nativeWidget.styleProperties.remove("backdrop-filter")
        nativeWidget.styleProperties.remove("-webkit-backdrop-filter")

        nativeWidget.attributes.remove("backgroundGradient")
        nativeWidget.styleProperties.remove("background-image")
        val semanticType = styleSnapshot.type?.takeIf { it.isSemantic }
            ?: styleSnapshot.backgroundType?.takeIf { it.isSemantic }
        val explicitBg = lastComponentStyle?.backgroundColor ?: getComputedStyle()?.backgroundColor
        if (semanticType != null || explicitBg != null) {
            nativeWidget.attributes["useSystemControlAppearance"] = "false"
            val resolvedBg = explicitBg ?: BootstrapColorPalette.resolveColor(semanticType!!, isDark)
            nativeWidget.attributes["bootstrapTintColor"] = resolvedBg.toHex()
        } else {
            nativeWidget.attributes["useSystemControlAppearance"] = "true"
            nativeWidget.attributes.remove("bootstrapTintColor")
        }
    }

    override fun setAttribute(name: String, value: String?) {
        if (
            name.equals("backdropBlurPx", ignoreCase = true) ||
            name.equals("backdrop-filter", ignoreCase = true) ||
            name.equals("-webkit-backdrop-filter", ignoreCase = true)
        ) {
            nativeWidget.attributes.remove("backdropBlurPx")
            nativeWidget.styleProperties.remove("backdrop-filter")
            nativeWidget.styleProperties.remove("-webkit-backdrop-filter")
            return
        }
        super.setAttribute(name, value)
    }

    override fun setBackgroundGradient(gradient: Gradient?) {
        super.setBackgroundGradient(null)
        nativeWidget.attributes.remove("backgroundGradient")
        nativeWidget.styleProperties.remove("background-image")
    }

    override fun getBackgroundGradient(): Gradient? = null

    override fun setType(type: BootstrapType?) {
        super.setType(type)
        syncGtkButtonAppearance()
    }

    override fun setBackgroundType(type: BootstrapType?) {
        super.setBackgroundType(type)
        syncGtkButtonAppearance()
    }

    override fun setTextColorType(type: BootstrapType?) {
        super.setTextColorType(type)
        syncGtkButtonAppearance()
    }

    override fun setOutline(outline: Boolean) {
        super.setOutline(outline)
        syncGtkButtonAppearance()
    }

    override fun applyComponentBootstrap(bootstrap: ComponentBootstrap) {
        super.applyComponentBootstrap(bootstrap)
        syncGtkButtonAppearance()
    }

    override fun applyComponentStyle(style: ComponentStyle) {
        super.applyComponentStyle(style)
        syncGtkButtonAppearance()
    }

    override fun applyComputedStyle(style: ComputedStyle) {
        super.applyComputedStyle(style)
        syncGtkButtonAppearance()
    }

    override fun runNativeTransition(
        property: String,
        fromValue: String?,
        toValue: String,
        spec: CssTransitionSpec,
        onComplete: () -> Unit
    ): Boolean {
        val handled = super.runNativeTransition(property, fromValue, toValue, spec, onComplete)
        if (property.equals("opacity", ignoreCase = true)) {
            val fromAlpha = (fromValue?.toFloatOrNull() ?: getOpacity()).coerceIn(0f, 1f)
            val targetAlpha = (toValue.toFloatOrNull() ?: getOpacity()).coerceIn(0f, 1f)
            nativeWidget.attributes["transitionFromOpacity"] = fromAlpha
            nativeWidget.attributes["transitionToOpacity"] = targetAlpha
            setOpacity(targetAlpha)
            if (spec.durationMs <= 0f) {
                onComplete()
            }
        }
        return handled
    }
}
