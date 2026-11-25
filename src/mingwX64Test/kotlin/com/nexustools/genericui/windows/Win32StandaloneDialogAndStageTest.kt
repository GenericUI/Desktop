@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.component.action.Button
import com.nexustools.genericui.component.dialog.DialogWindow
import com.nexustools.genericui.component.layout.Container
import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.component.shell.Window
import com.nexustools.genericui.component.typography.Text
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.desktop.core.DesktopInteractiveSurfaceSpec
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.core.DesktopStageOverlaySpec
import com.nexustools.genericui.desktop.dialog.DesktopDialogWindowNativeImpl
import com.nexustools.genericui.event.PointerEvent
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.windows.GWL_EXSTYLE
import platform.windows.GWL_STYLE
import platform.windows.GW_OWNER
import platform.windows.GetWindow
import platform.windows.GetWindowLongW
import platform.windows.GetWindowRect
import platform.windows.HWND
import platform.windows.IsWindow
import platform.windows.RECT
import platform.windows.SPI_GETWORKAREA
import platform.windows.SendMessageW
import platform.windows.SystemParametersInfoW
import platform.windows.WM_CLOSE
import platform.windows.WM_LBUTTONDBLCLK
import platform.windows.WS_CAPTION
import platform.windows.WS_EX_APPWINDOW
import platform.windows.WS_SYSMENU
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Win32StandaloneDialogAndStageTest {

    private fun withVisualHost(block: (WindowsNativeFactory) -> Unit) {
        val prevVisualTest = Win32NativeWindowHost.visualTestMode
        val prevInteractive = WindowsApplicationNativeImpl.interactiveWin32GuiEnabled
        val prevFactory = GenericUI.activeFactory
        val prevApp = Application.current
        Win32NativeWindowHost.visualTestMode = true
        WindowsApplicationNativeImpl.interactiveWin32GuiEnabled = true
        Application.current = null
        val factory = WindowsNativeFactory()
        try {
            GenericUI.initialize(factory)
            block(factory)
        } finally {
            for (dialog in DesktopDialogWindowNativeImpl.activeDialogs.toList()) {
                dialog.closeDialog()
            }
            Win32NativeWindowHost.closeAllVisualWindows()
            Win32NativeWindowHost.visualTestMode = prevVisualTest
            WindowsApplicationNativeImpl.interactiveWin32GuiEnabled = prevInteractive
            Application.current = prevApp
            GenericUI.activeFactory = prevFactory
        }
    }

    @Test
    fun standaloneAndHiddenOwnerDialogsCreateTopLevelAppHwndsCenteredInWorkAreaAndCloseViaWmClose() = withVisualHost { factory ->
        val standalone = DialogWindow(title = "Standalone Win32 Dialog").apply {
            setContent(Container().apply { addChild(Text("Independent Win32 Body")) })
            setWindowSize(Size(360, 220))
        }
        var closedCount = 0
        standalone.onCloseHandler = { closedCount++ }

        standalone.open()
        Win32NativeWindowHost.pumpVisualWindowEvents(24L)

        val standaloneImpl = standalone.ensureNativeImpl() as DesktopDialogWindowNativeImpl<*>
        val standaloneHwnd = assertNotNull(
            Win32NativeWindowHost.dialogHwnds[standaloneImpl],
            "Standalone DialogWindow must create a Win32 HWND even when activeHwnd is null"
        )
        assertNull(GetWindow(standaloneHwnd, GW_OWNER.toUInt()), "Standalone dialog must have hWndParent = null")
        val style = GetWindowLongW(standaloneHwnd, GWL_STYLE)
        val exStyle = GetWindowLongW(standaloneHwnd, GWL_EXSTYLE)
        assertTrue((style and WS_CAPTION) == WS_CAPTION)
        assertTrue((style and WS_SYSMENU) == WS_SYSMENU)
        assertTrue((exStyle and WS_EX_APPWINDOW) == WS_EX_APPWINDOW)
        assertTrue(Win32NativeWindowHost.hasOtherVisibleNonDisposedWindow(), "Visible dialog must keep Win32 loop alive")

        memScoped {
            val dlgRect = alloc<RECT>()
            val workRect = alloc<RECT>()
            assertTrue(GetWindowRect(standaloneHwnd, dlgRect.ptr) != 0)
            if (SystemParametersInfoW(SPI_GETWORKAREA.toUInt(), 0u, workRect.ptr, 0u) != 0) {
                val dlgMidX = (dlgRect.left + dlgRect.right) / 2
                val dlgMidY = (dlgRect.top + dlgRect.bottom) / 2
                val workMidX = (workRect.left + workRect.right) / 2
                val workMidY = (workRect.top + workRect.bottom) / 2
                assertTrue(abs(dlgMidX - workMidX) <= 4, "Standalone dialog should center horizontally on work area")
                assertTrue(abs(dlgMidY - workMidY) <= 4, "Standalone dialog should center vertically on work area")
            }
        }

        SendMessageW(standaloneHwnd, WM_CLOSE.toUInt(), 0u, 0L)
        Win32NativeWindowHost.pumpVisualWindowEvents(16L)

        assertEquals(1, closedCount)
        assertFalse(standalone.isWindowShown)
        assertNull(Win32NativeWindowHost.dialogHwnds[standaloneImpl])
        assertEquals(0, IsWindow(standaloneHwnd))
        assertFalse(Win32NativeWindowHost.hasOtherVisibleNonDisposedWindow())

        val hiddenOwner = Window(title = "Hidden Owner", initialSize = Size(640, 420)).apply {
            setContent(Container())
            hide()
        }
        hiddenOwner.show(factory)
        val childOfHidden = DialogWindow(title = "Child Of Hidden").apply {
            setContent(Container().apply { addChild(Text("Should Open Standalone")) })
        }
        childOfHidden.open(hiddenOwner)
        Win32NativeWindowHost.pumpVisualWindowEvents(24L)

        val childImpl = childOfHidden.ensureNativeImpl() as DesktopDialogWindowNativeImpl<*>
        val childHwnd = assertNotNull(Win32NativeWindowHost.dialogHwnds[childImpl])
        assertNull(GetWindow(childHwnd, GW_OWNER.toUInt()), "Invisible owner must not own Win32 dialog HWND")
        childOfHidden.close()
        hiddenOwner.close()
    }

    @Test
    fun stageHitTestingSupportsClickOnMouseUpDragSlopAndDoubleClickAcrossPrimaryAndSecondaryWindows() = withVisualHost { factory ->
        fun buildStageWindow(title: String): Pair<Window, StageTracker> {
            val tracker = StageTracker()
            val root = Container("div", "d-flex flex-column w-100 h-100")
            val stack = Container("div", "video-stack w-100 h-100")
            val surface = Container("div", "video-stage w-100 h-100")
            val centerBtn = Button("Play").apply {
                onClick = { tracker.playButtonClicks++ }
            }
            val centerOverlay = Container("div", "center-zone").apply {
                addChild(centerBtn)
            }
            val overlay = Container("div", "gui-overlay w-100 h-100").apply {
                onClick = { tracker.overlayClicks++ }
                onDoubleClick = { tracker.overlayDoubleClicks++ }
                onPointerDown = { tracker.downEvents.add(it) }
                onPointerMove = { tracker.moveEvents.add(it) }
                onPointerUp = { tracker.upEvents.add(it) }
                addChild(centerOverlay)
            }
            stack.addChild(surface)
            stack.addChild(overlay)
            root.addChild(stack)

            val win = Window(title = title, initialSize = Size(640, 360)).apply {
                setContent(root)
            }
            win.show(factory)

            val surfaceWidget = surface.ensureNativeImpl()!!.nativeWidget as DesktopNativeWidget
            DesktopInteractiveSurfaceSpec(
                surfaceWidth = { 640 },
                surfaceHeight = { 360 },
                swapCount = { 1L },
                isActiveStream = { true }
            ).attachTo(surfaceWidget)
            val overlayWidget = overlay.ensureNativeImpl()!!.nativeWidget as DesktopNativeWidget
            DesktopStageOverlaySpec(
                isTargetActive = { true },
                isOverlayVisible = { true }
            ).attachTo(overlayWidget)
            val centerWidget = centerOverlay.ensureNativeImpl()!!.nativeWidget as DesktopNativeWidget
            centerWidget.attributes[DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE] = DesktopStageOverlaySpec.ROLE_CENTER_ZONE
            return win to tracker
        }

        val (primaryWin, primaryTracker) = buildStageWindow("Primary Stage Window")
        val (secondaryWin, secondaryTracker) = buildStageWindow("Secondary PiP Window")
        Win32NativeWindowHost.pumpVisualWindowEvents(24L)

        val primaryHwnd = assertNotNull(Win32NativeWindowHost.activeHwnd)
        val secondaryImpl = secondaryWin.ensureNativeImpl() as WindowsWindowNativeImpl
        val secondaryHwnd = assertNotNull(Win32NativeWindowHost.secondaryWindowHwnds[secondaryImpl])

        fun verifyWindowStageInteractions(hwnd: HWND, tracker: StageTracker, label: String) {
            Win32NativeWindowHost.layoutAndSyncWindowChildren(hwnd)
            val hitX = 160
            val hitY = 180
            val hitLParam = (((hitY and 0xFFFF) shl 16) or (hitX and 0xFFFF)).toLong()
            val smallMoveLParam = ((((hitY + 1) and 0xFFFF) shl 16) or ((hitX + 2) and 0xFFFF)).toLong()
            val dragMoveLParam = ((((hitY + 14) and 0xFFFF) shl 16) or ((hitX + 18) and 0xFFFF)).toLong()

            platform.windows.SendMessageW(hwnd, platform.windows.WM_LBUTTONDOWN.toUInt(), platform.windows.MK_LBUTTON.toULong(), hitLParam)
            GenericUiThreading.flushAllQueues()
            assertEquals(0, tracker.overlayClicks, "$label: fireClickOnMouseUp must not click during WM_LBUTTONDOWN")
            platform.windows.SendMessageW(hwnd, platform.windows.WM_MOUSEMOVE.toUInt(), platform.windows.MK_LBUTTON.toULong(), smallMoveLParam)
            GenericUiThreading.flushAllQueues()
            platform.windows.SendMessageW(hwnd, platform.windows.WM_LBUTTONUP.toUInt(), 0uL, smallMoveLParam)
            GenericUiThreading.flushAllQueues()
            assertEquals(1, tracker.overlayClicks, "$label: <5px move should click on WM_LBUTTONUP")

            val movesBeforeDrag = tracker.moveEvents.size
            platform.windows.SendMessageW(hwnd, platform.windows.WM_LBUTTONDOWN.toUInt(), platform.windows.MK_LBUTTON.toULong(), hitLParam)
            GenericUiThreading.flushAllQueues()
            platform.windows.SendMessageW(hwnd, platform.windows.WM_MOUSEMOVE.toUInt(), platform.windows.MK_LBUTTON.toULong(), dragMoveLParam)
            GenericUiThreading.flushAllQueues()
            platform.windows.SendMessageW(hwnd, platform.windows.WM_LBUTTONUP.toUInt(), 0uL, dragMoveLParam)
            GenericUiThreading.flushAllQueues()
            val dragMoves = tracker.moveEvents.drop(movesBeforeDrag)
            assertTrue(
                dragMoves.any { it.button == 0 && (it.buttonsMask and 1) != 0 },
                "$label: pressed drag must dispatch pointerMove with button=0, buttonsMask=1"
            )
            assertEquals(1, tracker.overlayClicks, "$label: >=5px drag must suppress click on mouseUp")

            val dblLParam = (((hitY and 0xFFFF) shl 16) or (hitX and 0xFFFF)).toLong()
            SendMessageW(hwnd, WM_LBUTTONDBLCLK.toUInt(), 0u, dblLParam)
            GenericUiThreading.flushAllQueues()
            assertEquals(1, tracker.overlayDoubleClicks, "$label: WM_LBUTTONDBLCLK must dispatch double click once")
        }

        verifyWindowStageInteractions(primaryHwnd, primaryTracker, "PrimaryHWND")
        verifyWindowStageInteractions(secondaryHwnd, secondaryTracker, "SecondaryHWND")

        secondaryWin.close()
        primaryWin.close()
    }

    private class StageTracker {
        var overlayClicks: Int = 0
        var overlayDoubleClicks: Int = 0
        var playButtonClicks: Int = 0
        val downEvents = mutableListOf<PointerEvent>()
        val moveEvents = mutableListOf<PointerEvent>()
        val upEvents = mutableListOf<PointerEvent>()
    }
}
