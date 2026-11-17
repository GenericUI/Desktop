package com.nexustools.genericui.macos.impl

import com.nexustools.genericui.desktop.widget.DesktopProgressBarNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.macos.MacosViewHandle
import com.nexustools.genericui.macos.render.MacosControlRenderUtil
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle

/**
 * macOS AppKit `NSProgressIndicator` (bar style) / `NSLevelIndicatorCell` implementation of
 * [com.nexustools.genericui.nativeimpl.feedback.ProgressBarNativeImpl].
 */
open class MacosProgressBarNativeImpl(
    nativeWidget: MacosViewHandle = MacosViewHandle("NSProgressIndicator_Bar")
) : DesktopProgressBarNativeImpl<MacosViewHandle>(nativeWidget) {

    init {
        syncAppearance()
    }

    protected open fun syncAppearance() {
        MacosControlRenderUtil.syncStandardControlAppearance(this, "NSProgressIndicator_Bar_NSLevelIndicatorCell")
    }

    override fun renderDesktopNode(renderContext: Any, bounds: Rect): Boolean =
        MacosControlRenderUtil.renderProgressBar(this, renderContext, bounds)

    override fun setProgress(progress: Float) {
        super.setProgress(progress)
        syncAppearance()
    }

    override fun setIndeterminate(indeterminate: Boolean) {
        super.setIndeterminate(indeterminate)
        syncAppearance()
    }

    override fun setLabel(label: String?) {
        super.setLabel(label)
        syncAppearance()
    }

    override fun setType(type: BootstrapType?) {
        super.setType(type)
        syncAppearance()
    }

    override fun setBackgroundType(type: BootstrapType?) {
        super.setBackgroundType(type)
        syncAppearance()
    }

    override fun setOutline(outline: Boolean) {
        super.setOutline(outline)
        syncAppearance()
    }

    override fun applyComponentBootstrap(bootstrap: ComponentBootstrap) {
        super.applyComponentBootstrap(bootstrap)
        syncAppearance()
    }

    override fun applyComponentStyle(style: ComponentStyle) {
        super.applyComponentStyle(style)
        syncAppearance()
    }

    override fun applyComputedStyle(style: ComputedStyle) {
        super.applyComputedStyle(style)
        syncAppearance()
    }
}
