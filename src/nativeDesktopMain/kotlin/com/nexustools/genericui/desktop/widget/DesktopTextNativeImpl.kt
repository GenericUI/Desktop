package com.nexustools.genericui.desktop.widget

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.nativeimpl.typography.TextNativeImpl

/**
 * Shared desktop base implementation of [TextNativeImpl].
 */
open class DesktopTextNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W,
    val tagName: String = "span",
    classPrefix: String = ""
) : DesktopBaseNativeImpl<W>(nativeWidget, classPrefix), TextNativeImpl<W> {

    protected var textContent: String = ""
    protected var maxLinesLimit: Int? = null

    init {
        if ("tagName" !in nativeWidget.attributes) {
            nativeWidget.attributes["tagName"] = tagName
        }
    }

    fun isBadgeRole(): Boolean {
        val role = (nativeWidget.attributes["componentRole"] as? String)?.trim()?.lowercase()
        if (role == "badge") return true
        val attrTag = (nativeWidget.attributes["tagName"] as? String)?.trim()?.lowercase()
        if (attrTag == "badge" || tagName.equals("badge", ignoreCase = true)) return true
        if ("badge" in nativeWidget.cachedAllClassTokens) return true
        return componentClassPrefix.split(' ').any { it.equals("badge", ignoreCase = true) }
    }

    private fun normalizedSemanticTag(): String =
        ((nativeWidget.attributes["tagName"] as? String)?.takeIf { it.isNotBlank() } ?: tagName)
            .trim()
            .lowercase()

    fun defaultSemanticFontSizePx(): Float? = when (normalizedSemanticTag()) {
        "h1" -> 32f
        "h2" -> 26f
        "h3" -> 22f
        "h4" -> 18f
        "h5" -> 16f
        "h6" -> 14f
        "small" -> 11f
        else -> null
    }

    fun defaultSemanticFontWeight(): Int? = when (normalizedSemanticTag()) {
        "h1", "h2", "h3", "h4", "h5", "h6", "strong", "b" -> 700
        else -> null
    }

    override fun acceptsTypographyStyles(): Boolean = true

    override fun setText(text: String) {
        if (textContent != text) {
            textContent = text
            nativeWidget.attributes["text"] = text
            nativeWidget.notifyTreeInvalidated()
        }
    }

    override fun getText(): String = textContent

    override fun setMaxLines(maxLines: Int?) {
        if (maxLinesLimit != maxLines) {
            maxLinesLimit = maxLines
            nativeWidget.attributes["maxLines"] = maxLines
            nativeWidget.notifyTreeInvalidated()
        }
    }

    override fun getMaxLines(): Int? = maxLinesLimit
}
