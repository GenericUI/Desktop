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

    private fun isTextBubbleComponent(): Boolean {
        val tokens = componentClassPrefix.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return tokens.any { token ->
            val norm = token.removePrefix("genericui:")
            norm == "text-bubble" || norm == "textbubble" || norm == "gui-text-bubble"
        }
    }

    override fun setStyledVisuals(visuals: StyledVisualSpec) {
        val effective = if (isTextBubbleComponent()) visuals.withoutBackdropFilter() else visuals
        if (styledVisuals == effective && nativeWidget.attributes["styledVisuals"] == effective) return
        styledVisuals = effective
        cachedMergedVisuals = null
        nativeWidget.attributes["styledVisuals"] = effective
        effective.backgroundColor?.let { nativeWidget.attributes["backgroundColor"] = it }
        effective.backgroundGradient?.let {
            setBackgroundGradient(it)
            nativeWidget.attributes["backgroundGradient"] = it
        }
        effective.textColor?.let { nativeWidget.attributes["textColor"] = it }
        effective.borderColor?.let { nativeWidget.attributes["borderColor"] = it }
        effective.borderWidthPx?.let { nativeWidget.attributes["borderWidthPx"] = it }
        effective.borderStyle?.let { nativeWidget.attributes["borderStyle"] = it }
        effective.borderRadiusPx?.let { nativeWidget.attributes["borderRadiusPx"] = it }
        val blurPx = effective.backdropBlurPx ?: effective.backdropFilter?.blur?.resolvePx()
        if (!isTextBubbleComponent() && blurPx != null && blurPx > 0f) {
            nativeWidget.attributes["backdropBlurPx"] = blurPx
        } else {
            nativeWidget.attributes.remove("backdropBlurPx")
        }
        val bf = effective.backdropFilter
        if (!isTextBubbleComponent() && bf != null && !bf.isNone) {
            nativeWidget.attributes["backdropFilter"] = bf
        } else {
            nativeWidget.attributes.remove("backdropFilter")
        }
        effective.opacity?.let {
            val op = it.coerceIn(0f, 1f)
            nativeWidget.opacity = op
            nativeWidget.attributes["opacity"] = op
        }
        effective.padding?.let { nativeWidget.padding = it }
        nativeWidget.notifyTreeInvalidated(visualOnly = true)
    }

    override fun acceptsAestheticSkinStyles(): Boolean = true

    override fun acceptsTypographyStyles(): Boolean = true

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
        val merged = computedBase.mergeWith(styledVisuals)
        val result = if (isTextBubbleComponent()) merged.withoutBackdropFilter() else merged
        return result.also { cachedMergedVisuals = it }
    }
}
