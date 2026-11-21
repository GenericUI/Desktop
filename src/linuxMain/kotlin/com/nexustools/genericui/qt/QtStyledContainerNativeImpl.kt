package com.nexustools.genericui.qt

import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.desktop.widget.DesktopStyledContainerNativeImpl
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.qt.render.QtControlRenderUtil
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.css.StyledVisualSpec
import com.nexustools.genericui.style.theme.SystemThemeDetector
import com.nexustools.genericui.style.transition.CssTransitionSpec

/**
 * Qt 6 (`QWidget` with `QPainter` custom skinning) implementation of
 * [com.nexustools.genericui.nativeimpl.layout.StyledContainerNativeImpl].
 */
class QtStyledContainerNativeImpl(
    nativeWidget: QtWidgetHandle = QtWidgetHandle("QWidget_Styled"),
    classPrefix: String = "styled-container",
    val tagName: String = "div"
) : DesktopStyledContainerNativeImpl<QtWidgetHandle>(nativeWidget, classPrefix) {

    private var explicitStyledVisuals: StyledVisualSpec = StyledVisualSpec()

    init {
        nativeWidget.attributes["tagName"] = tagName
        nativeWidget.attributes["useSystemControlAppearance"] = "false"
        syncQtStyledContainerVisuals()
    }

    private fun isTextBubbleComponent(): Boolean {
        val raw = "$tagName $componentClassPrefix".trim().lowercase()
        val tokens = raw.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return tokens.any {
            val norm = it.removePrefix("genericui:")
            norm == "text-bubble" || norm == "textbubble" || norm == "gui-text-bubble"
        }
    }

    private fun syncQtStyledContainerVisuals() {
        val effectiveTheme = Application.current?.getResolvedTheme() ?: SystemThemeDetector.detectSystemTheme()
        val isDark = effectiveTheme == ThemeMode.DARK
        nativeWidget.attributes["effectiveThemeMode"] = effectiveTheme
        nativeWidget.attributes["qPaletteDarkMode"] = isDark
        nativeWidget.attributes["qtColorScheme"] = if (isDark) "Qt::ColorScheme::Dark" else "Qt::ColorScheme::Light"
        nativeWidget.attributes["useSystemControlAppearance"] = "false"

        val resolved = getStyledVisuals()
        nativeWidget.attributes["styledVisuals"] = resolved
        val blurPx = resolved.backdropBlurPx ?: resolved.backdropFilter?.blur?.resolvePx()
        if (!isTextBubbleComponent() && blurPx != null && blurPx > 0f) {
            nativeWidget.attributes["backdropBlurPx"] = blurPx
        } else {
            nativeWidget.attributes.remove("backdropBlurPx")
        }
        val bf = resolved.backdropFilter
        if (!isTextBubbleComponent() && bf != null && !bf.isNone) {
            nativeWidget.attributes["backdropFilter"] = bf
        } else {
            nativeWidget.attributes.remove("backdropFilter")
        }
        resolved.backgroundColor?.let { nativeWidget.attributes["backgroundColor"] = it }
        resolved.backgroundGradient?.let {
            setBackgroundGradient(it)
            nativeWidget.attributes["backgroundGradient"] = it
        }
        resolved.textColor?.let { nativeWidget.attributes["textColor"] = it }
        resolved.borderColor?.let { nativeWidget.attributes["borderColor"] = it }
        resolved.borderWidthPx?.let { nativeWidget.attributes["borderWidthPx"] = it }
        resolved.borderStyle?.let { nativeWidget.attributes["borderStyle"] = it }
        resolved.borderRadiusPx?.let { nativeWidget.attributes["borderRadiusPx"] = it }
        resolved.opacity?.let {
            val op = it.coerceIn(0f, 1f)
            nativeWidget.opacity = op
            nativeWidget.attributes["opacity"] = op
        }
        resolved.padding?.let { nativeWidget.padding = it }
        nativeWidget.notifyTreeInvalidated()
    }

    override fun renderDesktopNode(renderContext: Any, bounds: Rect): Boolean =
        QtControlRenderUtil.renderStyledContainer(this, renderContext, bounds)

    override fun setStyledVisuals(visuals: StyledVisualSpec) {
        explicitStyledVisuals = visuals
        super.setStyledVisuals(visuals)
        syncQtStyledContainerVisuals()
    }

    override fun getStyledVisuals(): StyledVisualSpec {
        val isDark = (Application.current?.getResolvedTheme() ?: SystemThemeDetector.detectSystemTheme()) == ThemeMode.DARK
        val computedBase = StyledVisualSpec.fromComponentAndComputed(
            componentStyle = lastComponentStyle,
            computedStyle = getComputedStyle(),
            snapshot = styleSnapshot,
            darkTheme = isDark
        )
        val merged = computedBase.mergeWith(explicitStyledVisuals)
        return if (isTextBubbleComponent()) merged.withoutBackdropFilter() else merged
    }

    override fun setAttribute(name: String, value: String?) {
        if (
            isTextBubbleComponent() &&
            (
                name.equals("backdropBlurPx", ignoreCase = true) ||
                    name.equals("backdrop-filter", ignoreCase = true) ||
                    name.equals("-webkit-backdrop-filter", ignoreCase = true)
                )
        ) {
            nativeWidget.attributes.remove("backdropBlurPx")
            nativeWidget.attributes.remove("backdropFilter")
            return
        }
        super.setAttribute(name, value)
    }

    override fun applyComponentBootstrap(bootstrap: ComponentBootstrap) {
        super.applyComponentBootstrap(bootstrap)
        syncQtStyledContainerVisuals()
    }

    override fun applyComponentStyle(style: ComponentStyle) {
        super.applyComponentStyle(style)
        syncQtStyledContainerVisuals()
    }

    override fun applyComputedStyle(style: ComputedStyle) {
        super.applyComputedStyle(style)
        syncQtStyledContainerVisuals()
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
