package com.nexustools.genericui.swing

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.nativeimpl.layout.DividerNativeImpl
import com.nexustools.genericui.nativeimpl.shell.ContextMenuNativeImpl
import com.nexustools.genericui.nativeimpl.shell.MenuItemNativeImpl
import com.nexustools.genericui.nativeimpl.shell.MenuNativeImpl
import java.awt.Component
import javax.swing.JComponent
import javax.swing.JPopupMenu

/**
 * Java Swing `JPopupMenu` implementation of [ContextMenuNativeImpl].
 */
class SwingContextMenuImpl : SwingBaseContainerNativeImpl(JPopupMenu(), "dropdown-menu"), ContextMenuNativeImpl<JComponent> {
    override val nativeWidget: JPopupMenu = super.nativeWidget as JPopupMenu

    val popupMenu: JPopupMenu
        get() = nativeWidget

    private val childrenList = mutableListOf<NativeImpl<*>>()
    private val separatorMap = mutableMapOf<NativeImpl<*>, Component>()
    private var showingState: Boolean = false

    val items: List<NativeImpl<*>>
        get() = childrenList

    var lastAnchor: NativeImpl<*>? = null
        private set
    var lastPopupX: Int = 0
        private set
    var lastPopupY: Int = 0
        private set

    override fun addChildNative(childImpl: NativeImpl<*>, index: Int) {
        childrenList.remove(childImpl)
        if (index < 0 || index >= childrenList.size) {
            childrenList.add(childImpl)
        } else {
            childrenList.add(index, childImpl)
        }

        when (childImpl) {
            is SwingDividerImpl, is DividerNativeImpl<*> -> {
                if (index < 0 || index >= popupMenu.componentCount) {
                    popupMenu.addSeparator()
                    separatorMap[childImpl] = popupMenu.getComponent(popupMenu.componentCount - 1)
                } else {
                    val sep = JPopupMenu.Separator()
                    popupMenu.insert(sep, index)
                    separatorMap[childImpl] = sep
                }
            }
            is SwingMenuImpl -> {
                if (index < 0 || index >= popupMenu.componentCount) {
                    popupMenu.add(childImpl.menu)
                } else {
                    popupMenu.insert(childImpl.menu, index)
                }
            }
            is SwingMenuItemImpl -> {
                if (index < 0 || index >= popupMenu.componentCount) {
                    popupMenu.add(childImpl.nativeWidget)
                } else {
                    popupMenu.insert(childImpl.nativeWidget, index)
                }
            }
            else -> {
                val comp = childImpl.nativeWidget as? Component ?: return
                if (index < 0 || index >= popupMenu.componentCount) {
                    popupMenu.add(comp)
                } else {
                    popupMenu.insert(comp, index)
                }
            }
        }
        popupMenu.revalidate()
        popupMenu.repaint()
    }

    override fun removeChildNative(childImpl: NativeImpl<*>) {
        childrenList.remove(childImpl)
        val sep = separatorMap.remove(childImpl)
        if (sep != null) {
            popupMenu.remove(sep)
        } else if (childImpl is SwingMenuImpl) {
            popupMenu.remove(childImpl.menu)
        } else {
            (childImpl.nativeWidget as? Component)?.let { popupMenu.remove(it) }
        }
        popupMenu.revalidate()
        popupMenu.repaint()
    }

    override fun clearChildrenNative() {
        childrenList.clear()
        separatorMap.clear()
        popupMenu.removeAll()
        popupMenu.revalidate()
        popupMenu.repaint()
    }

    fun getItemLabels(): List<String> = childrenList.map { child ->
        when (child) {
            is MenuItemNativeImpl<*> -> child.getLabel()
            is MenuNativeImpl<*> -> child.getLabel()
            is DividerNativeImpl<*> -> "---"
            else -> ""
        }
    }

    fun getSeparatorCount(): Int = childrenList.count { it is DividerNativeImpl<*> }

    fun getSubmenus(): List<MenuNativeImpl<*>> = childrenList.filterIsInstance<MenuNativeImpl<*>>()

    fun triggerItemAt(index: Int): Boolean {
        val child = childrenList.getOrNull(index) ?: return false
        if (child is DividerNativeImpl<*>) return false
        if (child is SwingMenuItemImpl) {
            if (!child.isEnabled()) return false
            child.trigger()
            hide()
            return true
        }
        return false
    }

    override fun showAt(anchor: NativeImpl<*>?, x: Int, y: Int) {
        lastAnchor = anchor
        lastPopupX = x
        lastPopupY = y
        showingState = true
        val comp = anchor?.nativeWidget as? Component
        if (comp != null && comp.isShowing) {
            nativeWidget.show(comp, x, y)
        } else {
            nativeWidget.setLocation(x, y)
        }
    }

    override fun hide() {
        showingState = false
        if (nativeWidget.isShowing) {
            nativeWidget.isVisible = false
        }
    }

    override fun setVisible(visible: Boolean) {
        showingState = visible
        if (!visible && nativeWidget.isShowing) {
            nativeWidget.isVisible = false
        }
    }

    override fun isVisible(): Boolean = showingState || nativeWidget.isVisible
}
