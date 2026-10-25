package com.nexustools.genericui.swing

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.nativeimpl.layout.DividerNativeImpl
import com.nexustools.genericui.nativeimpl.shell.MenuItemNativeImpl
import com.nexustools.genericui.nativeimpl.shell.MenuNativeImpl
import java.awt.Component
import javax.swing.JComponent
import javax.swing.JMenu
import javax.swing.JPopupMenu

/**
 * Java Swing `JMenu` implementation of [MenuNativeImpl].
 */
class SwingMenuImpl : SwingBaseContainerNativeImpl(JMenu(), "menu"), MenuNativeImpl<JComponent> {
    override val nativeWidget: JMenu = super.nativeWidget as JMenu

    val menu: JMenu
        get() = nativeWidget

    private var iconName: String? = null
    private val childrenList = mutableListOf<NativeImpl<*>>()
    private val separatorMap = mutableMapOf<NativeImpl<*>, Component>()

    val items: List<NativeImpl<*>>
        get() = childrenList

    override fun setLabel(label: String) {
        nativeWidget.text = label
    }

    override fun getLabel(): String = nativeWidget.text ?: ""

    override fun setIcon(icon: String?) {
        iconName = icon
        nativeWidget.putClientProperty("icon", icon)
    }

    override fun getIcon(): String? = iconName

    override fun addChildNative(childImpl: NativeImpl<*>, index: Int) {
        childrenList.remove(childImpl)
        if (index < 0 || index >= childrenList.size) {
            childrenList.add(childImpl)
        } else {
            childrenList.add(index, childImpl)
        }

        val count = nativeWidget.menuComponentCount
        when (childImpl) {
            is SwingDividerImpl, is DividerNativeImpl<*> -> {
                if (index < 0 || index >= count) {
                    nativeWidget.addSeparator()
                    separatorMap[childImpl] = nativeWidget.popupMenu.getComponent(nativeWidget.menuComponentCount - 1)
                } else {
                    val sep = JPopupMenu.Separator()
                    nativeWidget.popupMenu.insert(sep, index)
                    separatorMap[childImpl] = sep
                }
            }
            is SwingMenuImpl -> {
                if (index < 0 || index >= count) {
                    nativeWidget.add(childImpl.menu)
                } else {
                    nativeWidget.popupMenu.insert(childImpl.menu, index)
                }
            }
            is SwingMenuItemImpl -> {
                if (index < 0 || index >= count) {
                    nativeWidget.add(childImpl.nativeWidget)
                } else {
                    nativeWidget.insert(childImpl.nativeWidget, index)
                }
            }
            else -> {
                val comp = childImpl.nativeWidget as? Component ?: return
                if (index < 0 || index >= count) {
                    nativeWidget.add(comp)
                } else {
                    nativeWidget.popupMenu.insert(comp, index)
                }
            }
        }
        nativeWidget.revalidate()
        nativeWidget.repaint()
    }

    override fun removeChildNative(childImpl: NativeImpl<*>) {
        childrenList.remove(childImpl)
        val sep = separatorMap.remove(childImpl)
        if (sep != null) {
            nativeWidget.popupMenu.remove(sep)
        } else if (childImpl is SwingMenuImpl) {
            nativeWidget.remove(childImpl.menu)
        } else {
            (childImpl.nativeWidget as? Component)?.let { nativeWidget.remove(it) }
        }
        nativeWidget.revalidate()
        nativeWidget.repaint()
    }

    override fun clearChildrenNative() {
        childrenList.clear()
        separatorMap.clear()
        nativeWidget.removeAll()
        nativeWidget.revalidate()
        nativeWidget.repaint()
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
            return true
        }
        return false
    }
}
