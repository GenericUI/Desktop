package com.nexustools.genericui.desktop.widget

import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.nativeimpl.surface.CollapseNativeImpl

/**
 * Shared desktop base implementation of [CollapseNativeImpl].
 */
open class DesktopCollapseNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W
) : DesktopBaseContainerNativeImpl<W>(nativeWidget, "collapse"), CollapseNativeImpl<W> {

    protected var titleText: String = ""
    protected var expandedState: Boolean = false
    protected var onExpandedChangedListener: ((Boolean) -> Unit)? = null

    override fun setTitle(title: String) {
        titleText = title
        nativeWidget.attributes["title"] = title
    }

    override fun setExpanded(expanded: Boolean) {
        if (expandedState != expanded) {
            expandedState = expanded
            nativeWidget.attributes["expanded"] = expanded
            onExpandedChangedListener?.invoke(expanded)
        }
    }

    override fun isExpanded(): Boolean = expandedState

    override fun setOnExpandedChanged(listener: ((Boolean) -> Unit)?) {
        onExpandedChangedListener = listener
    }
}
