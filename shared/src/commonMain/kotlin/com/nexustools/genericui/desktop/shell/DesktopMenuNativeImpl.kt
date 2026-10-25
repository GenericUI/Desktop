package com.nexustools.genericui.desktop.shell

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.nativeimpl.layout.DividerNativeImpl
import com.nexustools.genericui.nativeimpl.shell.MenuItemNativeImpl
import com.nexustools.genericui.nativeimpl.shell.MenuNativeImpl

/**
 * Shared desktop base implementation of [MenuNativeImpl] supporting [MenuItemNativeImpl] items,
 * [DividerNativeImpl] (`MenuSeparator`) separators, and nested [MenuNativeImpl] submenus.
 */
open class DesktopMenuNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W
) : DesktopBaseContainerNativeImpl<W>(nativeWidget, "menu"), MenuNativeImpl<W> {

    protected var labelText: String = ""
    protected var iconName: String? = null

    val items: List<NativeImpl<*>>
        get() = childImpls

    override fun setLabel(label: String) {
        labelText = label
        nativeWidget.attributes["label"] = label
    }

    override fun getLabel(): String = labelText

    override fun setIcon(icon: String?) {
        iconName = icon
        nativeWidget.attributes["icon"] = icon
    }

    override fun getIcon(): String? = iconName

    override fun addChildNative(childImpl: NativeImpl<*>, index: Int) {
        super.addChildNative(childImpl, index)
        val childWidget = childImpl.nativeWidget as? DesktopNativeWidget
        when (childImpl) {
            is DividerNativeImpl<*> -> {
                childWidget?.attributes?.set("role", "separator")
                childWidget?.attributes?.set("isMenuSeparator", true)
            }
            is MenuNativeImpl<*> -> {
                childWidget?.attributes?.set("role", "submenu")
                childWidget?.attributes?.set("isSubmenu", true)
                childWidget?.attributes?.set("label", childImpl.getLabel())
            }
            is MenuItemNativeImpl<*> -> {
                childWidget?.attributes?.set("role", "menuitem")
                childWidget?.attributes?.set("isMenuItem", true)
                childWidget?.attributes?.set("label", childImpl.getLabel())
            }
        }
        syncMenuAttributes()
    }

    override fun removeChildNative(childImpl: NativeImpl<*>) {
        super.removeChildNative(childImpl)
        syncMenuAttributes()
    }

    override fun clearChildrenNative() {
        super.clearChildrenNative()
        syncMenuAttributes()
    }

    fun getItemLabels(): List<String> = childImpls.map { child ->
        when (child) {
            is MenuItemNativeImpl<*> -> child.getLabel()
            is MenuNativeImpl<*> -> child.getLabel()
            is DividerNativeImpl<*> -> "---"
            else -> ""
        }
    }

    fun getSeparatorCount(): Int = childImpls.count { it is DividerNativeImpl<*> }

    fun getSubmenus(): List<MenuNativeImpl<*>> = childImpls.filterIsInstance<MenuNativeImpl<*>>()

    fun triggerItemAt(index: Int): Boolean {
        val child = childImpls.getOrNull(index) ?: return false
        if (child is DividerNativeImpl<*>) return false
        if (child is DesktopBaseNativeImpl<*>) {
            if (!child.isEnabled()) return false
            child.dispatchClick()
            return true
        }
        return false
    }

    private fun syncMenuAttributes() {
        nativeWidget.attributes["itemCount"] = childImpls.size
        nativeWidget.attributes["separatorCount"] = getSeparatorCount()
        nativeWidget.attributes["submenuCount"] = getSubmenus().size
        nativeWidget.attributes["itemLabels"] = getItemLabels()
    }
}
