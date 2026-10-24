package com.nexustools.genericui.swing

import com.nexustools.genericui.nativeimpl.surface.CollapseNativeImpl
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Java Swing collapsible `JPanel` implementation of [CollapseNativeImpl].
 */
class SwingCollapseImpl : SwingBaseContainerNativeImpl(JPanel(), "collapse"), CollapseNativeImpl<JComponent> {
    private var titleText: String = ""
    private var expandedState: Boolean = false
    private var onExpandedChangedListener: ((Boolean) -> Unit)? = null

    init {
        nativeWidget.isVisible = false
    }

    override fun setTitle(title: String) {
        titleText = title
        nativeWidget.putClientProperty("title", title)
    }

    override fun setExpanded(expanded: Boolean) {
        expandedState = expanded
        nativeWidget.isVisible = expanded
        onExpandedChangedListener?.invoke(expanded)
    }

    override fun isExpanded(): Boolean = expandedState

    override fun setOnExpandedChanged(listener: ((Boolean) -> Unit)?) {
        onExpandedChangedListener = listener
    }
}
