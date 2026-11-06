package com.nexustools.genericui.desktop.widget

import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.nativeimpl.layout.StyledContainerNativeImpl
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.css.StyledVisualSpec

/**
 * Shared desktop base implementation of [StyledContainerNativeImpl] across `:windows`, `:macos`, `:gtk`, and `:qt`.
 */
open class DesktopStyledContainerNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W,
    classPrefix: String = "styled-container"
) : DesktopContainerNativeImpl<W>(nativeWidget, classPrefix), StyledContainerNativeImpl<W> {

    private var styledVisuals: StyledVisualSpec = StyledVisualSpec()
    private var cachedMergedVisuals: StyledVisualSpec? = null

    override fun setStyledVisuals(visuals: StyledVisualSpec) {
        if (styledVisuals == visuals && nativeWidget.attributes["styledVisuals"] == visuals) return
        styledVisuals = visuals
        cachedMergedVisuals = null
        nativeWidget.attributes["styledVisuals"] = visuals
        visuals.backgroundColor?.let { nativeWidget.attributes["backgroundColor"] = it }
        visuals.backgroundGradient?.let {
            setBackgroundGradient(it)
            nativeWidget.attributes["backgroundGradient"] = it
        }
        visuals.textColor?.let { nativeWidget.attributes["textColor"] = it }
        visuals.borderColor?.let { nativeWidget.attributes["borderColor"] = it }
        visuals.borderWidthPx?.let { nativeWidget.attributes["borderWidthPx"] = it }
        visuals.borderStyle?.let { nativeWidget.attributes["borderStyle"] = it }
        visuals.borderRadiusPx?.let { nativeWidget.attributes["borderRadiusPx"] = it }
        visuals.opacity?.let {
            val op = it.coerceIn(0f, 1f)
            nativeWidget.opacity = op
            nativeWidget.attributes["opacity"] = op
        }
        visuals.padding?.let { nativeWidget.padding = it }
        nativeWidget.notifyTreeInvalidated(visualOnly = true)
    }

    override fun applyComponentStyle(style: ComponentStyle) {
        cachedMergedVisuals = null
        super<DesktopContainerNativeImpl>.applyComponentStyle(style)
    }

    override fun applyComputedStyle(style: ComputedStyle) {
        cachedMergedVisuals = null
        super<DesktopContainerNativeImpl>.applyComputedStyle(style)
    }

    override fun getStyledVisuals(): StyledVisualSpec {
        cachedMergedVisuals?.let { return it }
        val computedBase = StyledVisualSpec.fromComponentAndComputed(lastComponentStyle, getComputedStyle())
        return computedBase.mergeWith(styledVisuals).also { cachedMergedVisuals = it }
    }
}
