package com.nexustools.genericui.macos

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
 * macOS AppKit `NSButton` implementation of [com.nexustools.genericui.nativeimpl.action.ButtonNativeImpl].
 *
 * Non-styled [MacosButtonNativeImpl] instances use native AppKit control rendering (`NSAppearanceNameAqua` /
 * `NSAppearanceNameDarkAqua` for Light / Dark / Auto themes) and ignore custom CSS gradients and `backdrop-filter` blur.
 */
open class MacosButtonNativeImpl(
    nativeWidget: MacosViewHandle = MacosViewHandle("NSButton"),
    classPrefix: String = "btn"
) : DesktopButtonNativeImpl<MacosViewHandle>(nativeWidget, classPrefix) {

    init {
        syncMacosButtonAppearance()
    }

    protected open fun syncMacosButtonAppearance() {
        val effectiveTheme = Application.current?.getResolvedTheme() ?: SystemThemeDetector.detectSystemTheme()
        val isDark = effectiveTheme == ThemeMode.DARK
        nativeWidget.attributes["effectiveThemeMode"] = effectiveTheme
        nativeWidget.attributes["nsAppearance"] = if (isDark) "NSAppearanceNameDarkAqua" else "NSAppearanceNameAqua"
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
        syncMacosButtonAppearance()
    }

    override fun setBackgroundType(type: BootstrapType?) {
        super.setBackgroundType(type)
        syncMacosButtonAppearance()
    }

    override fun setTextColorType(type: BootstrapType?) {
        super.setTextColorType(type)
        syncMacosButtonAppearance()
    }

    override fun setOutline(outline: Boolean) {
        super.setOutline(outline)
        syncMacosButtonAppearance()
    }

    override fun applyComponentBootstrap(bootstrap: ComponentBootstrap) {
        super.applyComponentBootstrap(bootstrap)
        syncMacosButtonAppearance()
    }

    override fun applyComponentStyle(style: ComponentStyle) {
        super.applyComponentStyle(style)
        syncMacosButtonAppearance()
    }

    override fun applyComputedStyle(style: ComputedStyle) {
        super.applyComputedStyle(style)
        syncMacosButtonAppearance()
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
