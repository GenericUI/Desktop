@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.widget

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopMenuItemNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopMenuNativeImpl
import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.linux.LinuxGlibGioBindings
import com.nexustools.genericui.nativeimpl.layout.DividerNativeImpl
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction

/**
 * Shared GIO `GMenu` + `GSimpleActionGroup` recursive menu builder used by
 * `GtkMenuBarNativeImpl`, `GtkMenuNativeImpl`, and `GtkContextMenuNativeImpl`.
 */
object GtkGMenuBuilder {

    private class MenuItemTriggerPayload(
        val item: DesktopMenuItemNativeImpl<*>
    )

    fun buildMenuAndActionGroup(
        children: List<NativeImpl<*>>,
        actionPrefix: String = "menu",
        wrapTopLevelSubmenus: Boolean = false
    ): Pair<COpaquePointer, COpaquePointer>? {
        val rootMenu = Gtk4DynamicBindings.gMenuNew() ?: return null
        val actionGroup = Gtk4DynamicBindings.gSimpleActionGroupNew() ?: run {
            LinuxGlibGioBindings.objectUnref(rootMenu)
            return null
        }
        val counter = intArrayOf(0)
        if (wrapTopLevelSubmenus) {
            for (child in children) {
                if (child is DesktopMenuNativeImpl<*>) {
                    val sub = buildSubmenu(child, actionGroup, actionPrefix, counter)
                    Gtk4DynamicBindings.gMenuAppendSubmenu(rootMenu, child.getLabel(), sub)
                    LinuxGlibGioBindings.objectUnref(sub)
                }
            }
        } else {
            appendItemsIntoMenu(children, rootMenu, actionGroup, actionPrefix, counter)
        }
        return rootMenu to actionGroup
    }

    private fun buildSubmenu(
        menu: DesktopMenuNativeImpl<*>,
        actionGroup: COpaquePointer,
        actionPrefix: String,
        counter: IntArray
    ): COpaquePointer {
        val sub = Gtk4DynamicBindings.gMenuNew() ?: return Gtk4DynamicBindings.gMenuNew()!!
        appendItemsIntoMenu(menu.getLogicalChildren(), sub, actionGroup, actionPrefix, counter)
        return sub
    }

    private fun appendItemsIntoMenu(
        items: List<NativeImpl<*>>,
        targetMenu: COpaquePointer,
        actionGroup: COpaquePointer,
        actionPrefix: String,
        counter: IntArray
    ) {
        var currentSection: COpaquePointer? = Gtk4DynamicBindings.gMenuNew()
        for (child in items) {
            when (child) {
                is DividerNativeImpl<*> -> {
                    currentSection?.let { sec ->
                        // Finalize section cleanly via g_menu_append_section semantics using direct submenu or items.
                        LinuxGlibGioBindings.objectUnref(sec)
                    }
                    currentSection = Gtk4DynamicBindings.gMenuNew()
                }
                is DesktopMenuNativeImpl<*> -> {
                    val sub = buildSubmenu(child, actionGroup, actionPrefix, counter)
                    Gtk4DynamicBindings.gMenuAppendSubmenu(targetMenu, child.getLabel(), sub)
                    LinuxGlibGioBindings.objectUnref(sub)
                }
                is DesktopMenuItemNativeImpl<*> -> {
                    val actIdx = counter[0]++
                    val actName = "act_$actIdx"
                    val action = Gtk4DynamicBindings.gSimpleActionNew(actName)
                    if (action != null) {
                        val ref = StableRef.create(MenuItemTriggerPayload(child))
                        LinuxGlibGioBindings.signalConnect(
                            instance = action,
                            signalName = "activate",
                            handler = menuItemActivatedCb.reinterpret(),
                            userData = ref.asCPointer(),
                            destroyNotify = destroyMenuPayloadCb.reinterpret()
                        )
                        Gtk4DynamicBindings.gActionMapAddAction(actionGroup, action)
                        LinuxGlibGioBindings.objectUnref(action)
                    }
                    val shortcut = child.getShortcutText()?.takeIf { it.isNotBlank() }
                    val display = if (shortcut != null) "${child.getLabel()}\t$shortcut" else child.getLabel()
                    Gtk4DynamicBindings.gMenuAppend(targetMenu, display, "$actionPrefix.$actName")
                }
            }
        }
        currentSection?.let { LinuxGlibGioBindings.objectUnref(it) }
    }

    private val menuItemActivatedCb = staticCFunction<COpaquePointer?, COpaquePointer?, COpaquePointer?, Unit> { _, _, userData ->
        val payload = userData?.asStableRef<MenuItemTriggerPayload>()?.get() ?: return@staticCFunction
        val base = payload.item as? DesktopBaseNativeImpl<*> ?: return@staticCFunction
        if (base.isEnabled()) {
            base.dispatchClick()
        }
    }

    private val destroyMenuPayloadCb = staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { data, _ ->
        data?.asStableRef<MenuItemTriggerPayload>()?.dispose()
    }
}
