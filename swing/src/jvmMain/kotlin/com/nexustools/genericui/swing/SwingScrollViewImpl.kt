package com.nexustools.genericui.swing

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.nativeimpl.layout.ScrollViewNativeImpl
import java.awt.Point
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane

/**
 * Java Swing `JScrollPane` implementation of [ScrollViewNativeImpl].
 */
class SwingScrollViewImpl : SwingBaseContainerNativeImpl(JScrollPane(JPanel()), "overflow-auto"), ScrollViewNativeImpl<JComponent> {
    private val scrollPane: JScrollPane = nativeWidget as JScrollPane
    private val contentPanel: JPanel = scrollPane.viewport.view as JPanel

    override fun addChildNative(childImpl: NativeImpl<*>, index: Int) {
        val comp = childImpl.nativeWidget as? JComponent ?: return
        if (index < 0 || index >= contentPanel.componentCount) {
            contentPanel.add(comp)
        } else {
            contentPanel.add(comp, index)
        }
    }

    override fun removeChildNative(childImpl: NativeImpl<*>) {
        val comp = childImpl.nativeWidget as? JComponent ?: return
        contentPanel.remove(comp)
    }

    override fun clearChildrenNative() {
        contentPanel.removeAll()
    }

    override fun scrollTo(x: Int, y: Int) {
        scrollPane.viewport.viewPosition = Point(x.coerceAtLeast(0), y.coerceAtLeast(0))
    }

    override fun scrollToBottom() {
        val maxH = contentPanel.height.coerceAtLeast(0)
        scrollPane.viewport.viewPosition = Point(0, maxH)
    }
}
