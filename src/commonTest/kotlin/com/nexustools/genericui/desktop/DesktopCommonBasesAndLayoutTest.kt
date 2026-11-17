package com.nexustools.genericui.desktop

import com.nexustools.genericui.component.dialog.FileBrowserCallback
import com.nexustools.genericui.component.dialog.FileBrowserRequest
import com.nexustools.genericui.component.dialog.FileTypeFilter
import com.nexustools.genericui.component.dialog.SelectedFile
import com.nexustools.genericui.component.layout.AdaptiveCardLayout
import com.nexustools.genericui.component.system.Notification
import com.nexustools.genericui.desktop.dialog.DesktopFileBrowserDialogNativeImpl
import com.nexustools.genericui.desktop.dialog.DesktopStandardDialogsNativeImpl
import com.nexustools.genericui.desktop.layout.DesktopAdaptiveCardLayoutImpl
import com.nexustools.genericui.desktop.layout.DesktopLayoutFactory
import com.nexustools.genericui.desktop.system.DesktopNetworkMonitorNativeImpl
import com.nexustools.genericui.desktop.system.DesktopNotificationManagerNativeImpl
import com.nexustools.genericui.desktop.system.DesktopPermissionManagerNativeImpl
import com.nexustools.genericui.desktop.system.DesktopSystemTrayNativeImpl
import com.nexustools.genericui.desktop.system.DesktopUiTimerNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.system.PermissionStatus
import com.nexustools.genericui.enums.system.PermissionType
import com.nexustools.genericui.style.color.RgbaColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopCommonBasesAndLayoutTest {

    @Test
    fun desktopLayoutFactoryCreatesLargeScreenDesktopImpls() {
        val impl = DesktopLayoutFactory.createAdaptiveCardLayout(AdaptiveCardLayout())
        assertTrue(impl is DesktopAdaptiveCardLayoutImpl)
    }

    @Test
    fun unimplementedDesktopOsServiceBasesFailFastWithoutSilentStubs() {
        val dialogs = DesktopStandardDialogsNativeImpl("TestToolkit")
        assertFailsWith<UnsupportedOperationException> {
            dialogs.showAlert(null, "Title", "Body", BootstrapType.INFO, "OK") {}
        }
        assertFailsWith<UnsupportedOperationException> {
            dialogs.showConfirm(null, "Title", "Body", "Yes", "No", BootstrapType.WARNING) {}
        }
        assertFailsWith<UnsupportedOperationException> {
            dialogs.showColorChooser(null, "Color", RgbaColor(10, 20, 30, 255)) {}
        }

        val network = DesktopNetworkMonitorNativeImpl("TestToolkit")
        assertFailsWith<UnsupportedOperationException> { network.isOnline() }
        val changes = mutableListOf<Boolean>()
        network.setOnConnectivityChanged { changes.add(it) }
        network.setSimulatedOnlineState(false)
        assertFalse(network.isOnline())
        network.setSimulatedOnlineState(true)
        assertTrue(network.isOnline())
        assertEquals(listOf(false, true), changes)

        val notifications = DesktopNotificationManagerNativeImpl("TestToolkit")
        assertFailsWith<UnsupportedOperationException> {
            notifications.showNotification(Notification(id = "n1", title = "Hi", body = "Test"))
        }

        val permissions = DesktopPermissionManagerNativeImpl("TestToolkit")
        assertFailsWith<UnsupportedOperationException> {
            permissions.checkPermission(PermissionType.CAMERA)
        }
        permissions.setPermissionOverride(PermissionType.CAMERA, PermissionStatus.DENIED)
        assertEquals(PermissionStatus.DENIED, permissions.checkPermission(PermissionType.CAMERA))

        val tray = DesktopSystemTrayNativeImpl()
        assertFalse(tray.isSupported)
        assertFailsWith<UnsupportedOperationException> { tray.setTooltip("Test") }
    }

    @Test
    fun fileBrowserBaseFailsWithoutSimulatedPathsAndDispatchesExplicitSelections() {
        val browser = DesktopFileBrowserDialogNativeImpl("TestDialog")
        val req = FileBrowserRequest(title = "Open")
        var selectedNames = emptyList<String>()
        val cb = object : FileBrowserCallback {
            override fun onFilesSelected(files: List<SelectedFile>, selectedFilter: FileTypeFilter?) {
                selectedNames = files.map { it.name }
            }

            override fun onCancelled() {}
        }
        assertFailsWith<UnsupportedOperationException> {
            browser.showFileBrowser(null, req, cb)
        }
        browser.simulatedSelectedPaths = listOf("/tmp/sample.txt")
        browser.showFileBrowser(null, req, cb)
        assertEquals(listOf("sample.txt"), selectedNames)
    }

    @Test
    fun uiTimerSchedulesAndAdvances() {
        val timer = DesktopUiTimerNativeImpl("TestTimer")
        var fired = 0
        timer.setTimeout(50) { fired++ }
        timer.advanceTimeBy(40)
        assertEquals(0, fired)
        timer.advanceTimeBy(15)
        assertEquals(1, fired)
        timer.dispose()
    }
}
