@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.component.action.Button
import com.nexustools.genericui.component.layout.Container
import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.component.shell.Window
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.desktop.core.DesktopInteractiveSurfaceSpec
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.core.DesktopStageOverlaySpec
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import platform.windows.GWL_STYLE
import platform.windows.GetClientRect
import platform.windows.GetWindowLongW
import platform.windows.GetWindowPlacement
import platform.windows.GetWindowRect
import platform.windows.HWND
import platform.windows.InvalidateRect
import platform.windows.IsZoomed
import platform.windows.RECT
import platform.windows.SW_MAXIMIZE
import platform.windows.ShowWindow
import platform.windows.VK_ESCAPE
import platform.windows.WINDOWPLACEMENT
import platform.windows.WS_CAPTION
import platform.windows.WS_POPUP
import platform.windows.WS_THICKFRAME
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reproduces "fullscreen does not exit properly — the layout stays fullscreen stuck inside the window frame"
 * on the Win32 backend.
 *
 * Scene: a window whose content is `[sidebar(Library button), videoStack(stage + overlay)]`. The video stack is
 * put into element fullscreen (`AbstractComponent.requestFullscreen()` → `Window.requestElementFullscreen()` →
 * `WindowsWindowNativeImpl.setFullscreenElement()` → `Win32NativeWindowHost.setWindowFullscreen()`), then exited
 * via the component API and via the host `Escape` key handler.
 */
class WindowsFullscreenExitTest {

    private class FullscreenScene(
        val window: Window,
        val stack: Container,
        val libraryButtonWidget: DesktopNativeWidget,
        val fullscreenButtonWidget: DesktopNativeWidget,
        val fullscreenEvents: MutableList<Boolean>
    )

    private inline fun withVisualHost(block: (WindowsNativeFactory) -> Unit) {
        val prevInteractive = WindowsApplicationNativeImpl.interactiveWin32GuiEnabled
        val prevVisualTest = Win32NativeWindowHost.visualTestMode
        WindowsApplicationNativeImpl.interactiveWin32GuiEnabled = true
        Win32NativeWindowHost.visualTestMode = true
        try {
            val factory = WindowsNativeFactory()
            GenericUI.initialize(factory)
            block(factory)
        } finally {
            Win32NativeWindowHost.closeAllVisualWindows()
            Win32NativeWindowHost.visualTestMode = prevVisualTest
            WindowsApplicationNativeImpl.interactiveWin32GuiEnabled = prevInteractive
        }
    }

    private fun buildScene(factory: WindowsNativeFactory, useApplicationMainWindow: Boolean): FullscreenScene {
        val root = Container("div", "d-flex flex-column w-100 h-100")
        val sidebar = Container("div", "sidebar")
        val libraryButton = Button("Library")
        sidebar.addChild(libraryButton)
        val stack = Container("div", "video-stack w-100")
        val stage = Container("div", "video-stage")
        val overlay = Container("div", "video-overlay")
        val topHud = Container("div", "top-hud")
        val transportBar = Container("div", "transport-bar")
        val controlsRow = Container("div", "d-flex flex-row")
        val playButton = Button("Play")
        val fullscreenButton = Button("Fullscreen").apply {
            onClick = { stack.toggleFullscreen() }
        }
        controlsRow.addChild(playButton)
        controlsRow.addChild(fullscreenButton)
        transportBar.addChild(controlsRow)
        overlay.addChild(topHud)
        overlay.addChild(transportBar)
        stack.addChild(stage)
        stack.addChild(overlay)
        root.addChild(sidebar)
        root.addChild(stack)

        val window = if (useApplicationMainWindow) {
            Application(root, title = "Fullscreen Exit Test").mainWindow
        } else {
            Window(title = "Fullscreen Exit Test (secondary)").also { it.setContent(root) }
        }
        window.show(factory)

        val stageWidget = stage.ensureNativeImpl()!!.nativeWidget as DesktopNativeWidget
        DesktopInteractiveSurfaceSpec(
            surfaceWidth = { 640 },
            surfaceHeight = { 360 },
            swapCount = { 0L },
            defaultAspectRatio = 16f / 9f
        ).attachTo(stageWidget)
        DesktopStageOverlaySpec().attachTo(overlay.ensureNativeImpl()!!.nativeWidget as DesktopNativeWidget)

        val events = mutableListOf<Boolean>()
        stack.onFullscreenChanged = { events.add(it) }

        Win32NativeWindowHost.pumpVisualWindowEvents(32L)
        return FullscreenScene(
            window = window,
            stack = stack,
            libraryButtonWidget = libraryButton.ensureNativeImpl()!!.nativeWidget as DesktopNativeWidget,
            fullscreenButtonWidget = fullscreenButton.ensureNativeImpl()!!.nativeWidget as DesktopNativeWidget,
            fullscreenEvents = events
        )
    }

    private fun activeHwnd(): HWND {
        val hwnd = Win32NativeWindowHost.activeHwnd
        assertNotNull(hwnd, "Expected an active Win32 HWND")
        return hwnd
    }

    private fun forceFullClientPaint(hwnd: HWND) {
        InvalidateRect(hwnd, null, 0)
        Win32NativeWindowHost.paintWindow(hwnd)
    }

    private fun clientSize(hwnd: HWND): Pair<Int, Int> = memScoped {
        val r = alloc<RECT>()
        GetClientRect(hwnd, r.ptr)
        (r.right - r.left) to (r.bottom - r.top)
    }

    private fun windowRect(hwnd: HWND): List<Int> = memScoped {
        val r = alloc<RECT>()
        GetWindowRect(hwnd, r.ptr)
        listOf(r.left, r.top, r.right, r.bottom)
    }

    private fun normalPlacementRect(hwnd: HWND): List<Int> = memScoped {
        val wp = alloc<WINDOWPLACEMENT>()
        wp.length = sizeOf<WINDOWPLACEMENT>().toUInt()
        GetWindowPlacement(hwnd, wp.ptr)
        listOf(wp.rcNormalPosition.left, wp.rcNormalPosition.top, wp.rcNormalPosition.right, wp.rcNormalPosition.bottom)
    }

    private fun hasHitRegionFor(widget: DesktopNativeWidget): Boolean =
        Win32NativeWindowHost.getLastRenderedHitRegionsSnapshot().any { it.widget === widget }

    /** Enters element fullscreen on [FullscreenScene.stack] and verifies the fullscreen video stage covers the client. */
    private fun enterFullscreenAndVerify(scene: FullscreenScene, hwnd: HWND) {
        scene.stack.requestFullscreen()
        Win32NativeWindowHost.pumpVisualWindowEvents(48L)
        forceFullClientPaint(hwnd)
        assertTrue(scene.stack.isFullscreen(), "Precondition: video stack must report fullscreen after requestFullscreen()")
        assertTrue(scene.window.isFullscreen(), "Precondition: window must report fullscreen after requestFullscreen()")
        val style = GetWindowLongW(hwnd, GWL_STYLE)
        assertTrue(style and WS_POPUP.toInt() != 0, "Precondition: fullscreen window must use WS_POPUP, style=0x${style.toUInt().toString(16)}")
        val (fsW, fsH) = clientSize(hwnd)
        val vr = Win32NativeWindowHost.lastRenderedVideoStageRect.toList()
        assertTrue(
            vr[0] == 0 && vr[1] == 0 && vr[2] == fsW && vr[3] >= fsH,
            "Precondition: fullscreen video stage must fill the whole ${fsW}x$fsH client, got stageRect=$vr"
        )
    }

    /**
     * After exiting, a full-client invalidation must take the full layout path (title bar + sidebar + windowed stage),
     * not the VBlank video-stage-only fast path re-using the stale fullscreen stage rect.
     */
    private fun assertFullLayoutRepaintedAfterExit(scene: FullscreenScene, hwnd: HWND, exitLabel: String) {
        val (clientW, clientH) = clientSize(hwnd)
        Win32NativeWindowHost.resetFrameMetrics()
        forceFullClientPaint(hwnd)
        val metrics = Win32NativeWindowHost.getFrameMetricsSnapshot()
        val vr = Win32NativeWindowHost.lastRenderedVideoStageRect.toList()
        assertEquals(
            0,
            metrics.videoStageOnlyPaints,
            "[$exitLabel] Full-client repaint after fullscreen exit was served by the video-stage-only fast path " +
                "(stale fullscreen stageRect=$vr, restored client=${clientW}x$clientH, metrics=$metrics). " +
                "The fullscreen video stage stays painted over the whole restored window."
        )
        assertEquals(
            1,
            metrics.fullWindowPaints,
            "[$exitLabel] Expected exactly one full window paint after fullscreen exit, metrics=$metrics"
        )
        assertTrue(
            vr[2] <= clientW && vr[3] <= clientH && !(vr[0] == 0 && vr[1] == 0 && vr[2] == clientW && vr[3] >= clientH),
            "[$exitLabel] Video stage rect must shrink back into the windowed layout after fullscreen exit, " +
                "got stageRect=$vr for restored client ${clientW}x$clientH"
        )
        assertTrue(
            hasHitRegionFor(scene.libraryButtonWidget),
            "[$exitLabel] Sibling 'Library' button hidden during fullscreen must be laid out/hit-testable again after exit"
        )
    }

    @Test
    fun testElementFullscreenExitRestoresWin32WindowStyleRectAndFullscreenState() = withVisualHost { factory ->
        val scene = buildScene(factory, useApplicationMainWindow = true)
        val hwnd = activeHwnd()
        forceFullClientPaint(hwnd)
        val preRect = windowRect(hwnd)
        val (preClientW, preClientH) = clientSize(hwnd)
        assertTrue(hasHitRegionFor(scene.libraryButtonWidget), "Precondition: 'Library' button must be laid out before fullscreen")

        enterFullscreenAndVerify(scene, hwnd)
        assertNotNull(Win32NativeWindowHost.activeFullscreenElementWidget)

        scene.stack.exitFullscreen()
        Win32NativeWindowHost.pumpVisualWindowEvents(48L)

        val style = GetWindowLongW(hwnd, GWL_STYLE)
        assertEquals(0, style and WS_POPUP.toInt(), "WS_POPUP must be removed after exit, style=0x${style.toUInt().toString(16)}")
        assertEquals(WS_CAPTION, style and WS_CAPTION, "WS_CAPTION must be restored after exit")
        assertEquals(WS_THICKFRAME, style and WS_THICKFRAME, "WS_THICKFRAME must be restored after exit")
        assertEquals(preRect, windowRect(hwnd), "Window rect must be restored to the pre-fullscreen rect")
        assertEquals(preClientW to preClientH, clientSize(hwnd), "Client size must be restored to the pre-fullscreen client size")
        assertNull(Win32NativeWindowHost.activeFullscreenElementWidget, "Host fullscreen element must be cleared")
        assertFalse(scene.window.isFullscreen(), "Window must not report fullscreen after exit")
        assertNull(scene.window.fullscreenElement, "Window.fullscreenElement must be cleared after exit")
        assertFalse(scene.stack.isFullscreen(), "Video stack must not report fullscreen after exit")
        assertNull(
            scene.stack.getAttribute("data-genericui-fullscreen-element"),
            "data-genericui-fullscreen-element attribute must be removed after exit"
        )
        assertEquals(listOf(true, false), scene.fullscreenEvents, "onFullscreenChanged must fire enter then exit")
    }

    @Test
    fun testElementFullscreenExitRepaintsFullLayoutInsteadOfStaleFullscreenVideoStage() = withVisualHost { factory ->
        val scene = buildScene(factory, useApplicationMainWindow = true)
        val hwnd = activeHwnd()
        forceFullClientPaint(hwnd)
        assertTrue(hasHitRegionFor(scene.libraryButtonWidget), "Precondition: 'Library' button must be laid out before fullscreen")

        enterFullscreenAndVerify(scene, hwnd)

        scene.stack.exitFullscreen()
        Win32NativeWindowHost.pumpVisualWindowEvents(48L)

        assertFullLayoutRepaintedAfterExit(scene, hwnd, exitLabel = "exitFullscreen()")
    }

    @Test
    fun testEscapeKeyFullscreenExitRepaintsFullLayoutInsteadOfStaleFullscreenVideoStage() = withVisualHost { factory ->
        val scene = buildScene(factory, useApplicationMainWindow = true)
        val hwnd = activeHwnd()
        forceFullClientPaint(hwnd)

        enterFullscreenAndVerify(scene, hwnd)

        assertTrue(Win32NativeWindowHost.handleKeyDown(hwnd, VK_ESCAPE), "Escape must be consumed while fullscreen")
        Win32NativeWindowHost.pumpVisualWindowEvents(48L)

        assertFalse(scene.stack.isFullscreen(), "Video stack must not report fullscreen after Escape")
        assertFullLayoutRepaintedAfterExit(scene, hwnd, exitLabel = "Escape")
    }

    @Test
    fun testEscapeKeyExitClearsCommonFullscreenStateWhenWindowIsNotApplicationMainWindow() = withVisualHost { factory ->
        // Application.current.mainWindow is a different (never shown) window; the shown window hosts the fullscreen stack.
        Application(Container("div"), title = "Unrelated main window")
        val scene = buildScene(factory, useApplicationMainWindow = false)
        val hwnd = activeHwnd()
        forceFullClientPaint(hwnd)

        enterFullscreenAndVerify(scene, hwnd)

        assertTrue(Win32NativeWindowHost.handleKeyDown(hwnd, VK_ESCAPE), "Escape must be consumed while fullscreen")
        Win32NativeWindowHost.pumpVisualWindowEvents(48L)

        assertNull(
            scene.window.fullscreenElement,
            "Escape exited native fullscreen but Window.fullscreenElement of the window owning the HWND was never cleared " +
                "(handleKeyDown only exits Application.current.mainWindow)"
        )
        assertFalse(scene.stack.isFullscreen(), "Video stack must not report fullscreen after Escape")
        assertEquals(listOf(true, false), scene.fullscreenEvents, "onFullscreenChanged(false) must be dispatched on Escape")
    }

    @Test
    fun testMaximizedWindowFullscreenExitRestoresMaximizedStateAndNormalPlacement() = withVisualHost { factory ->
        val scene = buildScene(factory, useApplicationMainWindow = true)
        val hwnd = activeHwnd()
        val normalRectBeforeMaximize = normalPlacementRect(hwnd)
        ShowWindow(hwnd, SW_MAXIMIZE)
        Win32NativeWindowHost.pumpVisualWindowEvents(32L)
        assertTrue(IsZoomed(hwnd) != 0, "Precondition: window must be maximized")
        val maximizedRect = windowRect(hwnd)

        enterFullscreenAndVerify(scene, hwnd)

        scene.stack.exitFullscreen()
        Win32NativeWindowHost.pumpVisualWindowEvents(48L)

        assertTrue(IsZoomed(hwnd) != 0, "Window maximized before fullscreen must be maximized again after exit")
        assertEquals(maximizedRect, windowRect(hwnd), "Maximized window rect must be restored after exit")
        assertEquals(
            normalRectBeforeMaximize,
            normalPlacementRect(hwnd),
            "Normal (restore) placement must survive the fullscreen round-trip so Restore returns to the original size"
        )
    }

    @Test
    fun testOverlayFullscreenButtonClickExitRepaintsFullLayoutInsteadOfStaleFullscreenVideoStage() = withVisualHost { factory ->
        val scene = buildScene(factory, useApplicationMainWindow = true)
        val hwnd = activeHwnd()
        forceFullClientPaint(hwnd)

        enterFullscreenAndVerify(scene, hwnd)

        val fsBtnHit = Win32NativeWindowHost.getLastRenderedHitRegionsSnapshot()
            .lastOrNull { it.widget === scene.fullscreenButtonWidget }
        assertNotNull(fsBtnHit, "Precondition: Fullscreen button in stage overlay must have a hit region while in fullscreen")
        val clickX = (fsBtnHit.left + fsBtnHit.right) / 2
        val clickY = (fsBtnHit.top + fsBtnHit.bottom) / 2
        Win32NativeWindowHost.simulateMouseClick(clickX, clickY)
        Win32NativeWindowHost.pumpVisualWindowEvents(48L)

        assertFalse(scene.stack.isFullscreen(), "Video stack must not report fullscreen after clicking Fullscreen button")
        assertFalse(scene.window.isFullscreen(), "Window must not report fullscreen after clicking Fullscreen button")
        assertFullLayoutRepaintedAfterExit(scene, hwnd, exitLabel = "Overlay Fullscreen Button Click")
    }
}
