package com.nexustools.genericui.desktop.widget

import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.nativeimpl.layout.ContainerNativeImpl
import com.nexustools.genericui.system.style.SemanticSurfacePaintSpec
import com.nexustools.genericui.system.style.SemanticSurfaceStyleResolver

/**
 * Shared desktop base implementation of [ContainerNativeImpl].
 */
open class DesktopContainerNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W,
    classPrefix: String = "container",
    val tagName: String = "div"
) : DesktopBaseContainerNativeImpl<W>(nativeWidget, classPrefix), ContainerNativeImpl<W> {

    init {
        if ("tagName" !in nativeWidget.attributes) {
            nativeWidget.attributes["tagName"] = tagName
        }
    }

    fun resolveSemanticSurfaceSpec(darkTheme: Boolean): SemanticSurfacePaintSpec? {
        val effectiveTag = (nativeWidget.attributes["tagName"] as? String)
            ?.takeIf { it.isNotBlank() }
            ?: tagName
        val tokens = LinkedHashSet<String>()
        if (componentClassPrefix.isNotBlank()) {
            tokens.add(componentClassPrefix)
        }
        tokens.addAll(getClassTokensSet())
        tokens.addAll(nativeWidget.cachedAllClassTokens)
        return SemanticSurfaceStyleResolver.resolveForTagAndClasses(
            tagName = effectiveTag,
            classTokens = tokens,
            snapshot = styleSnapshot,
            darkTheme = darkTheme
        )
    }
}
