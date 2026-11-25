@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.component.action.Button
import com.nexustools.genericui.component.dialog.DialogWindow
import com.nexustools.genericui.component.layout.Container
import com.nexustools.genericui.component.layout.LayeredContainer
import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.component.shell.Window
import com.nexustools.genericui.component.typography.Text
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.desktop.core.DesktopInteractiveSurfaceSpec
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.core.DesktopStageOverlaySpec
import com.nexustools.genericui.event.PointerEvent
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.useContents
import platform.AppKit.NSApplication
import platform.AppKit.NSApplicationActivationPolicy
import platform.AppKit.NSScreen
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MacosStandaloneDialogAndStageTest {

    @Test
    fun standaloneDialogOpensWithoutMainWindowFallbackAndHandlesWillClose() {
        val factory = MacosNativeFactory()
        val prevFactory = GenericUI.activeFactory
        val prevApp = Application.current
        val prevInteractive = MacosApplicationNativeImpl.interactiveMacosGuiEnabled
        val prevVisual = MacosNativeWindowHost.visualTestMode
        Application.current = null
        MacosApplicationNativeImpl.interactiveMacosGuiEnabled = true
        MacosNativeWindowHost.visualTestMode = true
        GenericUI.initialize(factory)

        val mainWindow = Window(title = "Unrelated Visible Main Window", initialSize = Size(640, 400)).apply {
            setContent(Container())
        }
        val standaloneDialog = DialogWindow(title = "Standalone macOS Dialog").apply {
            setContent(Container().apply { addChild(Text("Independent Dialog")) })
            setWindowSize(Size(360, 220))
        }
        try {
            mainWindow.show(factory)

            var closedCount = 0
            standaloneDialog.onCloseHandler = { closedCount++ }
            standaloneDialog.open()

            val dialogImpl = standaloneDialog.ensureNativeImpl() as MacosDialogWindowNativeImpl
            assertFalse(dialogImpl.attachedToMainWindow)
            assertNull(MacosNativeWindowHost.dialogSessionOwnerKey(dialogImpl))

            val panel = assertNotNull(
                MacosNativeWindowHost.dialogSessionNsWindow(dialogImpl),
                "Standalone DialogWindow must create a native NSPanel session"
            )
            assertNull(panel.parentWindow, "Standalone dialog must not attach as child window of unrelated mainWindow")

            val mainScreen = NSScreen.mainScreen ?: (NSScreen.screens.firstOrNull() as? NSScreen)
            if (mainScreen != null) {
                val visibleFrame = mainScreen.visibleFrame
                val screenMidX = visibleFrame.useContents { origin.x + size.width * 0.5 }
                val panelMidX = panel.frame.useContents { origin.x + size.width * 0.5 }
                assertTrue(abs(screenMidX - panelMidX) < 120.0, "Standalone dialog should center cleanly on screen")
            }

            mainWindow.close()
            assertTrue(
                MacosNativeWindowHost.hasAnyVisiblePresentationWindowForTest(),
                "Visible standalone dialog must count as active presentation window after mainWindow closes"
            )
            assertEquals(
                NSApplicationActivationPolicy.NSApplicationActivationPolicyRegular,
                MacosNativeWindowHost.computeProductionActivationPolicy()
            )
            assertEquals(
                NSApplicationActivationPolicy.NSApplicationActivationPolicyAccessory,
                NSApplication.sharedApplication.activationPolicy()
            )

            MacosNativeWindowHost.onNativeWindowWillClose(
                windowKey = dialogImpl.nativeWidget.nativePointer,
                isDialog = true
            )
            GenericUiThreading.flushAllQueues()

            assertFalse(standaloneDialog.isWindowShown)
            assertFalse(MacosNativeWindowHost.hasAnyVisiblePresentationWindowForTest())
            assertEquals(1, closedCount)
        } finally {
            if (standaloneDialog.isWindowShown) {
                standaloneDialog.close()
            }
            if (mainWindow.isWindowShown) {
                mainWindow.close()
            }
            GenericUiThreading.flushAllQueues()
            MacosNativeWindowHost.visualTestMode = prevVisual
            MacosApplicationNativeImpl.interactiveMacosGuiEnabled = prevInteractive
            Application.current = prevApp
            GenericUI.activeFactory = prevFactory
        }
    }

    @Test
    fun stageOverlayHitRegionPrefersOverlayRootWithoutDuplicateClicks() {
        val factory = MacosNativeFactory()
        val prevFactory = GenericUI.activeFactory
        val prevApp = Application.current
        val prevInteractive = MacosApplicationNativeImpl.interactiveMacosGuiEnabled
        val prevVisual = MacosNativeWindowHost.visualTestMode
        Application.current = null
        MacosApplicationNativeImpl.interactiveMacosGuiEnabled = true
        MacosNativeWindowHost.visualTestMode = true
        GenericUI.initialize(factory)

        val window = Window(title = "Stage HitRegion Priority", initialSize = Size(800, 480))
        try {
            var overlayClicks = 0
            var overlayDoubleClicks = 0
            var centerClicks = 0

            val surfaceBox = Container("div", "w-100 h-100")
            val centerZone = Container("div", "center-zone").apply {
                onClick = { centerClicks++ }
            }
            val overlayBox = Container("div", "gui-overlay w-100 h-100").apply {
                setAttribute("data-overlay-root", "true")
                onClick = { overlayClicks++ }
                onDoubleClick = { overlayDoubleClicks++ }
                addChild(centerZone)
            }
            val stageContainer = LayeredContainer().apply {
                addClass("w-100")
                addClass("h-100")
                addFillLayer(surfaceBox, zIndex = 0)
                addFillLayer(overlayBox, zIndex = 10)
            }
            window.setContent(stageContainer)
            window.show(factory)

            val winImpl = window.ensureNativeImpl() as MacosWindowNativeImpl
            val surfaceNative = surfaceBox.ensureNativeImpl()!!.nativeWidget as DesktopNativeWidget
            val overlayNative = overlayBox.ensureNativeImpl()!!.nativeWidget as DesktopNativeWidget
            val centerNative = centerZone.ensureNativeImpl()!!.nativeWidget as DesktopNativeWidget
            centerNative.attributes[DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE] = DesktopStageOverlaySpec.ROLE_CENTER_ZONE

            DesktopInteractiveSurfaceSpec(
                surfaceWidth = { 800 },
                surfaceHeight = { 480 },
                swapCount = { 1L },
                isActiveStream = { true }
            ).attachTo(surfaceNative)
            DesktopStageOverlaySpec(
                isTargetActive = { true },
                isOverlayVisible = { true }
            ).attachTo(overlayNative)

            MacosNativeWindowHost.flushPendingDisplay()

            val rootView = assertNotNull((winImpl.nativeWidget as MacosViewHandle).nsView as? MacosWindowRootView)
            val hitView = rootView.hitTestClientPoint(400.0, 240.0)
            val overlayView = assertNotNull((overlayNative as MacosViewHandle).nsView)
            assertEquals(
                overlayView,
                hitView,
                "AppKit hitTest on stage center zone spacer must resolve to overlayBox NSView"
            )

            MacosNativeWindowHost.simulateMouseClick(400f, 240f, button = 0, clickCount = 1)
            GenericUiThreading.flushAllQueues()
            assertEquals(1, overlayClicks, "Single stage click must fire once on overlayBox")
            assertEquals(0, centerClicks, "Stage click must not double-fire into centerZone")

            MacosNativeWindowHost.simulateMouseClick(400f, 240f, button = 0, clickCount = 2)
            GenericUiThreading.flushAllQueues()
            assertEquals(1, overlayDoubleClicks, "Double click must dispatch cleanly to overlayBox")
        } finally {
            window.close()
            GenericUiThreading.flushAllQueues()
            MacosNativeWindowHost.visualTestMode = prevVisual
            MacosApplicationNativeImpl.interactiveMacosGuiEnabled = prevInteractive
            Application.current = prevApp
            GenericUI.activeFactory = prevFactory
        }
    }

    @Test
    fun undecoratedStageClickDoubleClickAndDragSlopWorkEndToEnd() {
        val factory = MacosNativeFactory()
        val prevFactory = GenericUI.activeFactory
        val prevApp = Application.current
        val prevInteractive = MacosApplicationNativeImpl.interactiveMacosGuiEnabled
        val prevVisual = MacosNativeWindowHost.visualTestMode
        Application.current = null
        MacosApplicationNativeImpl.interactiveMacosGuiEnabled = true
        MacosNativeWindowHost.visualTestMode = true
        GenericUI.initialize(factory)

        val window = Window(title = "Undecorated Stage Gestures", initialSize = Size(800, 480))
        try {
            var clicks = 0
            var doubleClicks = 0
            val moveEvents = mutableListOf<PointerEvent>()

            val stageSurface = Container("div", "w-100 h-100")
            val centerAction = Button("Play")
            val centerZone = Container("div", "center-zone").apply {
                addChild(centerAction)
            }
            val overlay = Container("div", "gui-overlay w-100 h-100").apply {
                setAttribute("data-overlay-root", "true")
                onClick = { clicks++ }
                onDoubleClick = { doubleClicks++ }
                onPointerMove = { ev -> moveEvents.add(ev) }
                addChild(centerZone)
            }
            val stageHost = LayeredContainer().apply {
                addClass("w-100")
                addClass("h-100")
                addFillLayer(stageSurface, zIndex = 0)
                addFillLayer(overlay, zIndex = 10)
            }
            window.setUndecorated(true)
            window.setContent(stageHost)
            window.show(factory)

            DesktopInteractiveSurfaceSpec(
                surfaceWidth = { 800 },
                surfaceHeight = { 480 },
                swapCount = { 1L },
                isActiveStream = { true }
            ).attachTo(stageSurface.ensureNativeImpl()!!.nativeWidget as DesktopNativeWidget)
            val overlayNative = overlay.ensureNativeImpl()!!.nativeWidget as DesktopNativeWidget
            DesktopStageOverlaySpec(
                isTargetActive = { true },
                isOverlayVisible = { true }
            ).attachTo(overlayNative)
            val centerNative = centerZone.ensureNativeImpl()!!.nativeWidget as DesktopNativeWidget
            centerNative.attributes[DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE] = DesktopStageOverlaySpec.ROLE_CENTER_ZONE

            MacosNativeWindowHost.flushPendingDisplay()

            MacosNativeWindowHost.simulateMouseClick(180f, 160f, button = 0, clickCount = 1)
            assertEquals(1, clicks)
            assertEquals(0, doubleClicks)

            MacosNativeWindowHost.simulateMouseClick(180f, 160f, button = 0, clickCount = 2)
            assertEquals(1, clicks, "Double click must not increment single click count")
            assertEquals(1, doubleClicks)

            val moveCountBeforeDrag = moveEvents.size
            MacosNativeWindowHost.simulateMouseDrag(fromX = 180f, fromY = 160f, toX = 210f, toY = 184f, steps = 4)
            val dragMoves = moveEvents.drop(moveCountBeforeDrag)
            assertTrue(dragMoves.any { (it.buttonsMask and 1) != 0 }, "Drag motion must deliver pressed buttonsMask=1")
            assertEquals(1, clicks, "Drag beyond 5px slop must suppress release click")
        } finally {
            window.close()
            GenericUiThreading.flushAllQueues()
            MacosNativeWindowHost.visualTestMode = prevVisual
            MacosApplicationNativeImpl.interactiveMacosGuiEnabled = prevInteractive
            Application.current = prevApp
            GenericUI.activeFactory = prevFactory
        }
    }
}
