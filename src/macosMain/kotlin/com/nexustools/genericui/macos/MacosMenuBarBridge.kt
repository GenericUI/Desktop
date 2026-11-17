@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.shell.DesktopContextMenuNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopMenuBarNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopMenuItemNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopMenuNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.nativeimpl.layout.DividerNativeImpl
import com.nexustools.genericui.nativeimpl.shell.ContextMenuNativeImpl
import com.nexustools.genericui.nativeimpl.shell.MenuBarNativeImpl
import com.nexustools.genericui.threading.GenericUiThreading
import platform.AppKit.NSApplication
import platform.AppKit.NSControlStateValueOff
import platform.AppKit.NSControlStateValueOn
import platform.AppKit.NSEventModifierFlagCommand
import platform.AppKit.NSEventModifierFlagControl
import platform.AppKit.NSEventModifierFlagOption
import platform.AppKit.NSEventModifierFlagShift
import platform.AppKit.NSMenu
import platform.AppKit.NSMenuItem
import platform.AppKit.NSView
import platform.Foundation.NSMakePoint
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSSelectorFromString

/**
 * Bridges GenericUI [MenuBarNativeImpl], `Select`, and `Dropdown` menus to native AppKit `NSMenu`
 * and `NSApplication.sharedApplication.mainMenu`.
 */
internal object MacosMenuBarBridge {

    private val mainMenuTarget = MacosMenuActionTarget()
    private val popupMenuTarget = MacosMenuActionTarget()
    private val trayMenuTarget = MacosMenuActionTarget()

    fun buildContextMenu(contextMenu: ContextMenuNativeImpl<*>?): NSMenu? {
        val desktopContextMenu = contextMenu as? DesktopContextMenuNativeImpl<*> ?: return null
        trayMenuTarget.clearActions()
        val menu = NSMenu(title = "TrayMenu")
        menu.autoenablesItems = false
        populateMenuFromChildren(menu, desktopContextMenu.items, trayMenuTarget)
        return menu
    }

    fun installMainMenu(appName: String, menuBarImpl: MenuBarNativeImpl<*>?) {
        mainMenuTarget.clearActions()
        val resolvedAppName = appName.ifBlank {
            NSProcessInfo.processInfo.processName.ifBlank { "Application" }
        }
        val mainMenu = NSMenu(title = "MainMenu")

        // 1. Standard macOS Application menu
        val appMenuItem = NSMenuItem(title = resolvedAppName, action = null, keyEquivalent = "")
        val appMenu = NSMenu(title = resolvedAppName)
        appMenu.addItemWithTitle(
            string = "About $resolvedAppName",
            action = NSSelectorFromString("orderFrontStandardAboutPanel:"),
            keyEquivalent = ""
        )
        appMenu.addItem(NSMenuItem.separatorItem())

        val hideItem = NSMenuItem(
            title = "Hide $resolvedAppName",
            action = NSSelectorFromString("hide:"),
            keyEquivalent = "h"
        )
        hideItem.keyEquivalentModifierMask = NSEventModifierFlagCommand
        appMenu.addItem(hideItem)

        val hideOthersItem = NSMenuItem(
            title = "Hide Others",
            action = NSSelectorFromString("hideOtherApplications:"),
            keyEquivalent = "h"
        )
        hideOthersItem.keyEquivalentModifierMask = NSEventModifierFlagCommand or NSEventModifierFlagOption
        appMenu.addItem(hideOthersItem)

        appMenu.addItemWithTitle(
            string = "Show All",
            action = NSSelectorFromString("unhideAllApplications:"),
            keyEquivalent = ""
        )
        appMenu.addItem(NSMenuItem.separatorItem())

        val quitItem = NSMenuItem(
            title = "Quit $resolvedAppName",
            action = NSSelectorFromString("onAppQuit:"),
            keyEquivalent = "q"
        )
        quitItem.target = mainMenuTarget
        quitItem.keyEquivalentModifierMask = NSEventModifierFlagCommand
        appMenu.addItem(quitItem)

        appMenuItem.submenu = appMenu
        mainMenu.addItem(appMenuItem)

        // 2. Application-supplied menus (if any)
        var hasEditMenu = false
        var hasWindowMenu = false
        val desktopMenuBar = menuBarImpl as? DesktopMenuBarNativeImpl<*>
        if (desktopMenuBar != null) {
            for (childWidget in desktopMenuBar.nativeWidget.children) {
                val menuImpl = childWidget.attributes["nativeImpl"] as? DesktopMenuNativeImpl<*> ?: continue
                if (!menuImpl.isVisible()) continue
                val title = menuImpl.getLabel().ifBlank { "Menu" }
                if (title.equals("Edit", ignoreCase = true)) hasEditMenu = true
                if (title.equals("Window", ignoreCase = true)) hasWindowMenu = true
                val topItem = NSMenuItem(title = title, action = null, keyEquivalent = "")
                topItem.submenu = buildSubmenu(title, menuImpl)
                mainMenu.addItem(topItem)
            }
        }

        // 3. Standard Edit menu (ensures Cmd+X / Cmd+C / Cmd+V / Cmd+A work everywhere)
        if (!hasEditMenu) {
            val editMenuItem = NSMenuItem(title = "Edit", action = null, keyEquivalent = "")
            val editMenu = NSMenu(title = "Edit")

            val cutItem = NSMenuItem(title = "Cut", action = NSSelectorFromString("onEditCut:"), keyEquivalent = "x")
            cutItem.target = mainMenuTarget
            cutItem.keyEquivalentModifierMask = NSEventModifierFlagCommand
            editMenu.addItem(cutItem)

            val copyItem = NSMenuItem(title = "Copy", action = NSSelectorFromString("onEditCopy:"), keyEquivalent = "c")
            copyItem.target = mainMenuTarget
            copyItem.keyEquivalentModifierMask = NSEventModifierFlagCommand
            editMenu.addItem(copyItem)

            val pasteItem = NSMenuItem(title = "Paste", action = NSSelectorFromString("onEditPaste:"), keyEquivalent = "v")
            pasteItem.target = mainMenuTarget
            pasteItem.keyEquivalentModifierMask = NSEventModifierFlagCommand
            editMenu.addItem(pasteItem)

            editMenu.addItem(NSMenuItem.separatorItem())

            val selectAllItem = NSMenuItem(title = "Select All", action = NSSelectorFromString("onEditSelectAll:"), keyEquivalent = "a")
            selectAllItem.target = mainMenuTarget
            selectAllItem.keyEquivalentModifierMask = NSEventModifierFlagCommand
            editMenu.addItem(selectAllItem)

            editMenuItem.submenu = editMenu
            mainMenu.addItem(editMenuItem)
        }

        // 4. Standard Window menu
        if (!hasWindowMenu) {
            val windowMenuItem = NSMenuItem(title = "Window", action = null, keyEquivalent = "")
            val windowMenu = NSMenu(title = "Window")

            val minItem = NSMenuItem(
                title = "Minimize",
                action = NSSelectorFromString("performMiniaturize:"),
                keyEquivalent = "m"
            )
            minItem.keyEquivalentModifierMask = NSEventModifierFlagCommand
            windowMenu.addItem(minItem)

            windowMenu.addItemWithTitle(
                string = "Zoom",
                action = NSSelectorFromString("performZoom:"),
                keyEquivalent = ""
            )
            windowMenu.addItem(NSMenuItem.separatorItem())

            val fullScreenItem = NSMenuItem(
                title = "Enter Full Screen",
                action = NSSelectorFromString("toggleFullScreen:"),
                keyEquivalent = "f"
            )
            fullScreenItem.keyEquivalentModifierMask = NSEventModifierFlagCommand or NSEventModifierFlagControl
            windowMenu.addItem(fullScreenItem)

            windowMenuItem.submenu = windowMenu
            mainMenu.addItem(windowMenuItem)
        }

        NSApplication.sharedApplication.mainMenu = mainMenu
    }

    private fun buildSubmenu(
        title: String,
        menuImpl: DesktopMenuNativeImpl<*>,
        target: MacosMenuActionTarget = mainMenuTarget
    ): NSMenu {
        val menu = NSMenu(title = title)
        menu.autoenablesItems = false
        populateMenuFromChildren(menu, menuImpl.items, target)
        return menu
    }

    private fun populateMenuFromChildren(
        menu: NSMenu,
        children: List<NativeImpl<*>>,
        target: MacosMenuActionTarget
    ) {
        for (child in children) {
            when (child) {
                is DividerNativeImpl<*> -> menu.addItem(NSMenuItem.separatorItem())
                is DesktopMenuNativeImpl<*> -> {
                    if (!child.isVisible()) continue
                    val subTitle = child.getLabel().ifBlank { "Submenu" }
                    val subItem = NSMenuItem(title = subTitle, action = null, keyEquivalent = "")
                    subItem.enabled = child.isEnabled()
                    subItem.submenu = buildSubmenu(subTitle, child, target)
                    menu.addItem(subItem)
                }
                is DesktopMenuItemNativeImpl<*> -> {
                    if (!child.isVisible()) continue
                    val shortcut = child.getShortcut()
                    val keyEq = shortcut?.key?.lowercase()?.takeIf { it.length == 1 } ?: ""
                    val nsItem = NSMenuItem(
                        title = child.getLabel(),
                        action = NSSelectorFromString("onMenuItemTriggered:"),
                        keyEquivalent = keyEq
                    )
                    if (shortcut != null && keyEq.isNotEmpty()) {
                        var mask = 0UL
                        if (shortcut.ctrlOrCmd) mask = mask or NSEventModifierFlagCommand
                        if (shortcut.alt) mask = mask or NSEventModifierFlagOption
                        if (shortcut.shift) mask = mask or NSEventModifierFlagShift
                        nsItem.keyEquivalentModifierMask = mask
                    }
                    nsItem.enabled = child.isEnabled()
                    if (child.checkable) {
                        nsItem.state = if (child.isChecked()) NSControlStateValueOn else NSControlStateValueOff
                    }
                    target.bindAction(nsItem) {
                        GenericUiThreading.runOnLogic {
                            if (child.isEnabled()) {
                                if (child.checkable && !child.radio) {
                                    child.setChecked(!child.isChecked())
                                }
                                child.dispatchClick()
                            }
                        }
                    }
                    nsItem.target = target
                    menu.addItem(nsItem)
                }
            }
        }
    }

    fun popUpSelectMenu(
        options: List<Pair<String, String>>,
        selectedValue: String?,
        anchorBounds: Rect,
        inView: NSView,
        onSelected: (String) -> Unit
    ) {
        if (options.isEmpty()) return
        popupMenuTarget.clearActions()
        val menu = NSMenu(title = "Select")
        menu.autoenablesItems = false
        menu.minimumWidth = anchorBounds.width.coerceAtLeast(120).toDouble()
        var activeItem: NSMenuItem? = null
        for ((value, label) in options) {
            val item = NSMenuItem(
                title = label.ifEmpty { value },
                action = NSSelectorFromString("onMenuItemTriggered:"),
                keyEquivalent = ""
            )
            val isSel = value == selectedValue
            item.state = if (isSel) NSControlStateValueOn else NSControlStateValueOff
            if (isSel) activeItem = item
            popupMenuTarget.bindAction(item) {
                GenericUiThreading.runOnLogic {
                    onSelected(value)
                }
            }
            item.target = popupMenuTarget
            menu.addItem(item)
        }
        val location = NSMakePoint(anchorBounds.x.toDouble(), (anchorBounds.bottom + 2).toDouble())
        menu.popUpMenuPositioningItem(activeItem, atLocation = location, inView = inView)
    }

    fun popUpDropdownMenu(
        items: List<Pair<String, String>>,
        anchorBounds: Rect,
        inView: NSView,
        onItemSelected: (String) -> Unit
    ) {
        if (items.isEmpty()) return
        popupMenuTarget.clearActions()
        val menu = NSMenu(title = "Dropdown")
        menu.autoenablesItems = false
        menu.minimumWidth = anchorBounds.width.coerceAtLeast(140).toDouble()
        for ((id, label) in items) {
            if (id == "---" || label == "---") {
                menu.addItem(NSMenuItem.separatorItem())
                continue
            }
            val item = NSMenuItem(
                title = label.ifEmpty { id },
                action = NSSelectorFromString("onMenuItemTriggered:"),
                keyEquivalent = ""
            )
            popupMenuTarget.bindAction(item) {
                GenericUiThreading.runOnLogic {
                    onItemSelected(id)
                }
            }
            item.target = popupMenuTarget
            menu.addItem(item)
        }
        val location = NSMakePoint(anchorBounds.x.toDouble(), (anchorBounds.bottom + 2).toDouble())
        menu.popUpMenuPositioningItem(null, atLocation = location, inView = inView)
    }
}
