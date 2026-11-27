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
import com.nexustools.genericui.nativeimpl.shell.MenuNativeImpl
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.CValue
import kotlinx.cinterop.useContents
import platform.AppKit.NSApplication
import platform.AppKit.NSControlStateValueOff
import platform.AppKit.NSControlStateValueOn
import platform.AppKit.NSEventModifierFlagCommand
import platform.AppKit.NSEventModifierFlagControl
import platform.AppKit.NSEventModifierFlagOption
import platform.AppKit.NSEventModifierFlagShift
import platform.AppKit.NSMenu
import platform.AppKit.NSMenuItem
import platform.AppKit.NSPopUpButton
import platform.AppKit.NSView
import platform.Foundation.NSMakePoint
import platform.Foundation.NSMakeRect
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSRect
import platform.Foundation.NSSelectorFromString
import platform.Foundation.NSThread
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Bridges GenericUI [MenuBarNativeImpl], `Select`, and `Dropdown` menus to native AppKit `NSMenu`
 * and `NSApplication.sharedApplication.mainMenu`.
 */
internal object MacosMenuBarBridge {

    private val mainMenuTarget = MacosMenuActionTarget()
    private val popupMenuTarget = MacosMenuActionTarget()
    private val trayMenuTarget = MacosMenuActionTarget()

    fun buildContextMenu(
        contextMenu: ContextMenuNativeImpl<*>?,
        menuTarget: MacosMenuActionTarget = trayMenuTarget
    ): NSMenu? {
        val desktopContextMenu = contextMenu as? DesktopContextMenuNativeImpl<*> ?: return null
        menuTarget.clearActions()
        val menu = NSMenu(title = "TrayMenu")
        menu.autoenablesItems = false
        populateMenuFromChildren(menu, desktopContextMenu.items, menuTarget)
        return menu
    }

    fun installMainMenu(
        appName: String,
        menuBarImpl: MenuBarNativeImpl<*>?,
        applicationMenuImpl: MenuNativeImpl<*>? = null
    ) {
        if (!NSThread.isMainThread) {
            MacosAppKitDispatch.onMainOrDefer {
                installMainMenu(appName, menuBarImpl, applicationMenuImpl)
            }
            return
        }
        mainMenuTarget.clearActions()
        val resolvedAppName = appName.ifBlank {
            NSProcessInfo.processInfo.processName.ifBlank { "Application" }
        }
        val mainMenu = NSMenu(title = "MainMenu")

        // 1. Standard macOS Application menu
        val appMenuItem = NSMenuItem(title = resolvedAppName, action = null, keyEquivalent = "")
        val appMenu = NSMenu(title = resolvedAppName)
        appMenu.autoenablesItems = true
        val aboutItem = NSMenuItem(
            title = "About $resolvedAppName",
            action = NSSelectorFromString("onAppAbout:"),
            keyEquivalent = ""
        )
        aboutItem.target = mainMenuTarget
        appMenu.addItem(aboutItem)
        appMenu.addItem(NSMenuItem.separatorItem())

        val desktopAppMenu = applicationMenuImpl as? DesktopMenuNativeImpl<*>
        if (desktopAppMenu != null) {
            val addedCustom = populateMenuFromChildren(
                menu = appMenu,
                children = desktopAppMenu.items,
                target = mainMenuTarget,
                autoenablesSubmenus = true
            )
            if (addedCustom > 0) {
                val lastIdx = appMenu.numberOfItems - 1L
                val lastIsSeparator = lastIdx >= 0L && appMenu.itemAtIndex(lastIdx)?.separatorItem == true
                if (!lastIsSeparator) {
                    appMenu.addItem(NSMenuItem.separatorItem())
                }
            }
        }

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
        target: MacosMenuActionTarget = mainMenuTarget,
        autoenablesItems: Boolean = false
    ): NSMenu {
        val menu = NSMenu(title = title)
        menu.autoenablesItems = autoenablesItems
        populateMenuFromChildren(menu, menuImpl.items, target, autoenablesItems)
        return menu
    }

    private fun populateMenuFromChildren(
        menu: NSMenu,
        children: List<NativeImpl<*>>,
        target: MacosMenuActionTarget,
        autoenablesSubmenus: Boolean = false
    ): Int {
        var addedCount = 0
        for (child in children) {
            when (child) {
                is DividerNativeImpl<*> -> {
                    menu.addItem(NSMenuItem.separatorItem())
                    addedCount++
                }
                is DesktopMenuNativeImpl<*> -> {
                    if (!child.isVisible()) continue
                    val subTitle = child.getLabel().ifBlank { "Submenu" }
                    val subItem = NSMenuItem(title = subTitle, action = null, keyEquivalent = "")
                    subItem.enabled = child.isEnabled()
                    subItem.submenu = buildSubmenu(subTitle, child, target, autoenablesSubmenus)
                    menu.addItem(subItem)
                    addedCount++
                }
                is DesktopMenuItemNativeImpl<*> -> {
                    if (!child.isVisible()) continue
                    val shortcut = child.getShortcut()
                    val keyEq = when (val rawKey = shortcut?.key?.trim()?.lowercase()) {
                        "comma" -> ","
                        else -> rawKey?.takeIf { it.length == 1 } ?: ""
                    }
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
                    target.bindAction(nsItem, menuItemImpl = child) {
                        GenericUiThreading.runOnLogic {
                            if (child.isEnabled()) {
                                child.dispatchClick()
                                if (child.checkable) {
                                    val nextState = if (child.isChecked()) NSControlStateValueOn else NSControlStateValueOff
                                    if (!MacosApplicationNativeImpl.interactiveMacosGuiEnabled || NSThread.isMainThread) {
                                        nsItem.state = nextState
                                    } else {
                                        dispatch_async(dispatch_get_main_queue()) {
                                            nsItem.state = nextState
                                        }
                                    }
                                }
                            }
                        }
                    }
                    nsItem.target = target
                    menu.addItem(nsItem)
                    addedCount++
                }
            }
        }
        return addedCount
    }

    /**
     * Converts top-left flipped [anchorBounds] (bottom-left corner of the anchor control) on a canvas
     * whose bottom-left in global screen coordinates sits at ([contentOriginInScreenX], [contentOriginInScreenY])
     * with height [canvasHeight] into global Cocoa screen coordinates (`+Y` upward).
     */
    internal fun computeSelectPopupScreenPoint(
        anchorBounds: Rect,
        contentOriginInScreenX: Double,
        contentOriginInScreenY: Double,
        canvasHeight: Double
    ): Pair<Double, Double> {
        val sx = contentOriginInScreenX + anchorBounds.x.toDouble()
        val sy = contentOriginInScreenY + (canvasHeight - anchorBounds.bottom.toDouble())
        return sx to sy
    }

    internal fun resolvePopupScreenPointAndView(
        anchorBounds: Rect,
        inView: NSView
    ): Pair<Pair<Double, Double>, NSView?> {
        val win = inView.window
        if (win != null) {
            val localBottomLeft = NSMakePoint(anchorBounds.x.toDouble(), anchorBounds.bottom.toDouble())
            val winPt = inView.convertPoint(localBottomLeft, toView = null)
            val screenPt = win.convertPointToScreen(winPt)
            var sx = 0.0
            var sy = 0.0
            screenPt.useContents {
                sx = x
                sy = y
            }
            return (sx to sy) to null
        }
        return (anchorBounds.x.toDouble() to anchorBounds.bottom.toDouble()) to inView
    }

    /**
     * Persistent or anchor `NSPopUpButton` mounted in [MacosWindowRootView] so AppKit computes
     * native `NSPopUpMenuPopupButtonBounds`, attaches `parentWindow` (including scrolled
     * controls inside `DialogWindow` panels), aligns selected items over the anchor, and applies
     * `NSPopUpMenuPopupButtonLabelOffset` automatically.
     */
    internal class MacosPopupAnchorButton(
        frame: CValue<NSRect>,
        pullsDown: Boolean
    ) : NSPopUpButton(frame = frame, pullsDown = pullsDown) {
        override fun drawRect(dirtyRect: CValue<NSRect>) {
            // Kept lightweight when used purely as a popup menu anchor for tests/helpers.
        }
    }

    internal fun createSelectPopupAnchorButton(
        options: List<Pair<String, String>>,
        selectedValue: String?,
        anchorBounds: Rect,
        onSelected: (String) -> Unit
    ): MacosPopupAnchorButton {
        val anchorFrame = NSMakeRect(
            anchorBounds.x.toDouble(),
            anchorBounds.y.toDouble(),
            anchorBounds.width.coerceAtLeast(1).toDouble(),
            anchorBounds.height.coerceAtLeast(1).toDouble()
        )
        val anchorButton = MacosPopupAnchorButton(anchorFrame, pullsDown = false)
        anchorButton.removeAllItems()
        val menu = NSMenu(title = "Select")
        menu.autoenablesItems = false
        menu.minimumWidth = anchorBounds.width.coerceAtLeast(1).toDouble()
        var selectedIndex = -1
        options.forEachIndexed { idx, (value, label) ->
            val item = NSMenuItem(
                title = label.ifEmpty { value },
                action = NSSelectorFromString("onMenuItemTriggered:"),
                keyEquivalent = ""
            )
            val isSel = value == selectedValue
            if (isSel && selectedIndex < 0) {
                selectedIndex = idx
            }
            item.state = if (isSel) NSControlStateValueOn else NSControlStateValueOff
            popupMenuTarget.bindAction(item) {
                GenericUiThreading.runOnLogic {
                    onSelected(value)
                }
            }
            item.target = popupMenuTarget
            menu.addItem(item)
        }
        anchorButton.menu = menu
        if (selectedIndex >= 0) {
            anchorButton.selectItemAtIndex(selectedIndex.toLong())
        }
        return anchorButton
    }

    internal fun createDropdownPopupAnchorButton(
        items: List<Pair<String, String>>,
        anchorBounds: Rect,
        onItemSelected: (String) -> Unit
    ): MacosPopupAnchorButton {
        val anchorFrame = NSMakeRect(
            anchorBounds.x.toDouble(),
            anchorBounds.y.toDouble(),
            anchorBounds.width.coerceAtLeast(1).toDouble(),
            anchorBounds.height.coerceAtLeast(1).toDouble()
        )
        val anchorButton = MacosPopupAnchorButton(anchorFrame, pullsDown = true)
        anchorButton.removeAllItems()
        val menu = NSMenu(title = "Dropdown")
        menu.autoenablesItems = false
        menu.minimumWidth = anchorBounds.width.coerceAtLeast(1).toDouble()
        // AppKit pull-down menus hide item index 0 (title placeholder) and display items 1..N.
        menu.addItem(NSMenuItem(title = "", action = null, keyEquivalent = ""))
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
        anchorButton.menu = menu
        return anchorButton
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
        val anchorButton = createSelectPopupAnchorButton(options, selectedValue, anchorBounds, onSelected)
        try {
            inView.addSubview(anchorButton)
            if (inView.window != null) {
                anchorButton.performClick(null)
            }
        } finally {
            anchorButton.removeFromSuperview()
        }
    }

    fun popUpDropdownMenu(
        items: List<Pair<String, String>>,
        anchorBounds: Rect,
        inView: NSView,
        onItemSelected: (String) -> Unit
    ) {
        if (items.isEmpty()) return
        popupMenuTarget.clearActions()
        val anchorButton = createDropdownPopupAnchorButton(items, anchorBounds, onItemSelected)
        try {
            inView.addSubview(anchorButton)
            if (inView.window != null) {
                anchorButton.performClick(null)
            }
        } finally {
            anchorButton.removeFromSuperview()
        }
    }
}
