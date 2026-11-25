@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.component.fragment.Fragment
import com.nexustools.genericui.component.layout.Container
import com.nexustools.genericui.component.shell.CheckMenuItem
import com.nexustools.genericui.component.shell.ContextMenu
import com.nexustools.genericui.component.shell.Menu
import com.nexustools.genericui.component.shell.MenuBar
import com.nexustools.genericui.component.shell.MenuItem
import com.nexustools.genericui.component.shell.MenuSeparator
import com.nexustools.genericui.component.shell.Window
import com.nexustools.genericui.component.system.SystemTrayIcon
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.nativeimpl.shell.ContextMenuNativeImpl
import com.nexustools.genericui.nativeimpl.shell.MenuBarNativeImpl
import platform.AppKit.NSMenuItem
import platform.darwin.NSObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MacosMenuAndTrayDispatchTest {

    @Test
    fun menuActionTargetDispatchesByMonotonicTagAndSurvivesMainMenuRebuild() {
        val prevFactory = GenericUI.activeFactory
        val factory = MacosNativeFactory()
        GenericUI.activeFactory = factory
        val window = Window(title = "Menu Test", initialSize = Size(800, 600))
        val tray = SystemTrayIcon(tooltip = "Menu Test Tray")
        try {
            var openClicks = 0
            var settingsClicks = 0
            var toggleCalls = 0
            var toggleCheckedState = false
            var fileExitClicks = 0

            val contextMenu = ContextMenu()
            val openItem = MenuItem("Open App").apply {
                setOnClick { openClicks++ }
            }
            val settingsItem = MenuItem("Settings").apply {
                setOnClick { settingsClicks++ }
            }
            val subMenu = Menu("Playback")
            val muteItem = CheckMenuItem(
                label = "Mute",
                checked = false,
                onToggled = { checked ->
                    toggleCalls++
                    toggleCheckedState = checked
                }
            )
            subMenu.addChild(muteItem)
            contextMenu.addItem(openItem)
            contextMenu.addItem(MenuSeparator())
            contextMenu.addItem(settingsItem)
            contextMenu.addSubmenu(subMenu)
            tray.contextMenu = contextMenu

            val contextMenuImpl = contextMenu.nativeImpl as? ContextMenuNativeImpl<*>
            assertNotNull(contextMenuImpl)

            val trayTarget = MacosMenuActionTarget()
            val nsMenu = MacosMenuBarBridge.buildContextMenu(contextMenuImpl, trayTarget)
            assertNotNull(nsMenu)
            assertEquals(4, nsMenu.numberOfItems.toInt())

            val nsOpen = nsMenu.itemAtIndex(0)!!
            val nsSep = nsMenu.itemAtIndex(1)!!
            val nsSettings = nsMenu.itemAtIndex(2)!!
            val nsSubContainer = nsMenu.itemAtIndex(3)!!
            val nsMute = nsSubContainer.submenu!!.itemAtIndex(0)!!

            assertTrue(nsSep.separatorItem)
            assertTrue(nsOpen.tag > 0L)
            assertTrue(nsSettings.tag > nsOpen.tag)
            assertTrue(nsMute.tag > nsSettings.tag)

            // Rebuilding NSApplication.mainMenu uses mainMenuTarget and must not clear trayTarget.
            val menuBar = MenuBar()
            val fileMenu = Menu("File")
            val exitMenuItem = MenuItem("Exit").apply {
                setOnClick { fileExitClicks++ }
            }
            fileMenu.addChild(exitMenuItem)
            menuBar.addChild(fileMenu)
            window.setMenuBar(menuBar)
            window.setFragment(Fragment().apply { addChild(Container()) })
            window.show(factory)

            MacosMenuBarBridge.installMainMenu(
                "GenericUITest",
                menuBar.nativeImpl as? MenuBarNativeImpl<*>
            )
            MacosMenuBarBridge.installMainMenu("GenericUITestUpdated", null)
            assertEquals(0, fileExitClicks)

            // Invoke via NSObject? signature matching AppKit selector dispatch.
            trayTarget.onMenuItemTriggered(nsOpen as NSObject)
            trayTarget.onMenuItemTriggered(nsSettings as NSObject)
            trayTarget.onMenuItemTriggered(nsMute as NSObject)

            assertEquals(1, openClicks)
            assertEquals(1, settingsClicks)
            assertEquals(1, toggleCalls)
            assertTrue(toggleCheckedState)

            // Rebuilding tray menu assigns higher monotonic tags without colliding with earlier ones.
            val prevOpenTag = nsOpen.tag
            val rebuiltMenu = MacosMenuBarBridge.buildContextMenu(contextMenuImpl, trayTarget)!!
            val rebuiltOpen = rebuiltMenu.itemAtIndex(0)!!
            assertTrue(rebuiltOpen.tag > nsMute.tag)

            // Stale item with old tag must not trigger rebuilt callback.
            val staleItem = NSMenuItem(title = "Stale", action = null, keyEquivalent = "").apply {
                setTag(prevOpenTag)
            }
            trayTarget.onMenuItemTriggered(staleItem as NSObject)
            assertEquals(1, openClicks)

            trayTarget.onMenuItemTriggered(rebuiltOpen as NSObject)
            assertEquals(2, openClicks)
        } finally {
            tray.dispose()
            window.dispose()
            GenericUI.activeFactory = prevFactory
        }
    }

    @Test
    fun systemTrayPreservesStatusItemMenuAcrossIconTooltipAndBadgeUpdates() {
        val prevFactory = GenericUI.activeFactory
        val prevInteractive = MacosApplicationNativeImpl.interactiveMacosGuiEnabled
        val factory = MacosNativeFactory()
        GenericUI.activeFactory = factory
        val tray = SystemTrayIcon(tooltip = "Player Tray")
        try {
            assertTrue(MacosSystemTrayNativeImpl.hasActiveTrayIconsInProcess())
            val trayImpl = MacosSystemTrayNativeImpl.activeTraysSnapshotForTesting().lastOrNull()
            assertNotNull(trayImpl)

            var actionCount = 0
            val contextMenu = ContextMenu()
            contextMenu.addItem(MenuItem("Show Player").apply {
                setOnClick { actionCount++ }
            })
            contextMenu.addItem(MenuItem("Pause").apply {
                setOnClick { actionCount += 10 }
            })
            tray.contextMenu = contextMenu
            trayImpl.syncNativeStatusItem(forceRebuildMenu = false)

            val statusItem = trayImpl.currentStatusItemForTesting
            assertNotNull(statusItem)
            val initialMenu = statusItem.menu
            assertNotNull(initialMenu)
            assertEquals(trayImpl.currentAttachedMenuForTesting, initialMenu)
            assertEquals(1, trayImpl.menuRebuildCountForTesting)

            // Updating tooltip, icon, and badge text must update NSStatusBarButton in place
            // without clearing or replacing statusItem.menu or tray actions.
            trayImpl.setTooltip("Downloading Episode (42%)")
            trayImpl.syncNativeStatusItem(forceRebuildMenu = false)
            assertEquals(initialMenu, statusItem.menu, "setTooltip must preserve NSStatusItem.menu")
            assertEquals(1, trayImpl.menuRebuildCountForTesting, "setTooltip must not rebuild NSMenu")
            assertEquals("Downloading Episode (42%)", statusItem.button?.toolTip)

            trayImpl.setIcon("custom_tray_icon.png")
            trayImpl.syncNativeStatusItem(forceRebuildMenu = false)
            assertEquals(initialMenu, statusItem.menu, "setIcon must preserve NSStatusItem.menu")
            assertEquals(1, trayImpl.menuRebuildCountForTesting, "setIcon must not rebuild NSMenu")

            trayImpl.setBadgeText("3")
            trayImpl.syncNativeStatusItem(forceRebuildMenu = false)
            assertEquals(initialMenu, statusItem.menu, "setBadgeText must preserve NSStatusItem.menu")
            assertEquals(1, trayImpl.menuRebuildCountForTesting, "setBadgeText must not rebuild NSMenu")
            assertTrue(statusItem.button?.title?.contains("3") == true)

            val firstMenuItem = initialMenu.itemAtIndex(0)!!
            trayImpl.menuActionTargetForTesting.onMenuItemTriggered(firstMenuItem as NSObject)
            assertEquals(1, actionCount)

            tray.dispose()
            assertFalse(MacosSystemTrayNativeImpl.hasActiveTrayIconsInProcess())
        } finally {
            tray.dispose()
            MacosApplicationNativeImpl.interactiveMacosGuiEnabled = prevInteractive
            GenericUI.activeFactory = prevFactory
        }
    }
}
