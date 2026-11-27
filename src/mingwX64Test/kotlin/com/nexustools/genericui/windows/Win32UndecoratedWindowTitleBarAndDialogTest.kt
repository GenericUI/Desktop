@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.component.action.Button
import com.nexustools.genericui.component.dialog.DialogWindow
import com.nexustools.genericui.component.form.Select
import com.nexustools.genericui.component.form.SelectOption
import com.nexustools.genericui.component.layout.Container
import com.nexustools.genericui.component.layout.ScrollView
import com.nexustools.genericui.component.layout.styled.StyledContainer
import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.component.shell.Window
import com.nexustools.genericui.component.typography.Text
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopInteractiveSurfaceSpec
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.core.DesktopStageOverlaySpec
import com.nexustools.genericui.desktop.dialog.DesktopDialogWindowNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopSelectNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.bootstrap.FlexDirection
import com.nexustools.genericui.enums.css.CssDisplay
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssLength
import com.nexustools.genericui.style.theme.SystemThemeDetector
import com.nexustools.genericui.threading.GenericUiThreading
import com.nexustools.genericui.windows.impl.WindowsContainerNativeImpl
import com.nexustools.genericui.windows.impl.WindowsScrollViewNativeImpl
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.toLong
import platform.windows.GWL_STYLE
import platform.windows.GetClassNameW
import platform.windows.GetClientRect
import platform.windows.GetWindowLongW
import platform.windows.GetWindowRect
import platform.windows.HTBOTTOM
import platform.windows.HTBOTTOMLEFT
import platform.windows.HTBOTTOMRIGHT
import platform.windows.HTCLIENT
import platform.windows.HTLEFT
import platform.windows.HTRIGHT
import platform.windows.HTTOP
import platform.windows.HTTOPLEFT
import platform.windows.HTTOPRIGHT
import platform.windows.HWND
import platform.windows.IDC_SIZENESW
import platform.windows.IDC_SIZENS
import platform.windows.IDC_SIZENWSE
import platform.windows.IDC_SIZEWE
import platform.windows.MapWindowPoints
import platform.windows.POINT
import platform.windows.RECT
import platform.windows.SendMessageW
import platform.windows.WMSZ_BOTTOMRIGHT
import platform.windows.WMSZ_LEFT
import platform.windows.WS_MAXIMIZEBOX
import platform.windows.WS_MINIMIZEBOX
import platform.windows.WS_POPUP
import platform.windows.WS_SYSMENU
import platform.windows.WS_THICKFRAME
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Win32UndecoratedWindowTitleBarAndDialogTest {

    private fun readWin32ClassName(hwnd: HWND): String = memScoped {
        val buf = allocArray<UShortVar>(128)
        GetClassNameW(hwnd, buf, 128)
        buf.toKString()
    }

    private fun childCenterInHost(childHwnd: HWND, hostHwnd: HWND): Pair<Int, Int> = memScoped {
        val rc = alloc<RECT>()
        GetClientRect(childHwnd, rc.ptr)
        val pt = alloc<POINT>()
        pt.x = ((rc.right - rc.left) / 2).coerceAtLeast(1)
        pt.y = ((rc.bottom - rc.top) / 2).coerceAtLeast(1)
        MapWindowPoints(childHwnd, hostHwnd, pt.ptr, 1u)
        pt.x to pt.y
    }

    private fun withVisualHost(block: (WindowsNativeFactory) -> Unit) {
        val prevVisualTest = Win32NativeWindowHost.visualTestMode
        val prevInteractive = WindowsApplicationNativeImpl.interactiveWin32GuiEnabled
        val prevFactory = GenericUI.activeFactory
        val prevApp = Application.current
        val prevThemeOverride = SystemThemeDetector.overrideDetectedTheme
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
            SystemThemeDetector.overrideDetectedTheme = prevThemeOverride
            Win32NativeWindowHost.visualTestMode = prevVisualTest
            WindowsApplicationNativeImpl.interactiveWin32GuiEnabled = prevInteractive
            Application.current = prevApp
            GenericUI.activeFactory = prevFactory
        }
    }

    @Test
    fun undecoratedResizableWindowIncludesMinimizeMaximizeAndSysMenuStyles() = withVisualHost { factory ->
        val win = Window(title = "Undecorated Host", initialSize = Size(640, 400)).apply {
            setUndecorated(true)
            setContent(Container("div"))
        }
        win.show(factory)
        Win32NativeWindowHost.pumpVisualWindowEvents(24L)
        val winImpl = win.ensureNativeImpl() as WindowsWindowNativeImpl
        val expectedMask = Win32HwndHandle.WS_POPUP or
            Win32HwndHandle.WS_THICKFRAME or
            Win32HwndHandle.WS_MINIMIZEBOX or
            Win32HwndHandle.WS_MAXIMIZEBOX or
            Win32HwndHandle.WS_SYSMENU
        assertEquals(
            expectedMask,
            winImpl.nativeWidget.dwStyle and expectedMask,
            "WindowsWindowNativeImpl undecorated dwStyle must include WS_POPUP|WS_THICKFRAME|WS_MINIMIZEBOX|WS_MAXIMIZEBOX|WS_SYSMENU"
        )

        val hwnd = assertNotNull(Win32NativeWindowHost.activeHwnd)
        val liveStyle = GetWindowLongW(hwnd, GWL_STYLE)
        assertTrue((liveStyle and WS_POPUP.toInt()) != 0, "Live undecorated HWND must have WS_POPUP")
        assertTrue((liveStyle and WS_THICKFRAME) != 0, "Live undecorated HWND must have WS_THICKFRAME")
        assertTrue((liveStyle and WS_MINIMIZEBOX) != 0, "Live undecorated HWND must have WS_MINIMIZEBOX")
        assertTrue((liveStyle and WS_MAXIMIZEBOX) != 0, "Live undecorated HWND must have WS_MAXIMIZEBOX")
        assertTrue((liveStyle and WS_SYSMENU) != 0, "Live undecorated HWND must have WS_SYSMENU")
        win.close()
    }

    @Test
    fun overlayTitleBarCaptionButtonsBindOwnChildHwndsAndDoNotTriggerSystemMove() = withVisualHost { factory ->
        var closeClicks = 0
        var minClicks = 0
        var maxClicks = 0
        var systemMoveCount = 0

        val closeBtn = Button("").apply {
            id = "overlay-window-close-btn"
            style.width = CssLength.px(44f)
            style.height = CssLength.px(30f)
            onClick = { closeClicks++ }
        }
        val minBtn = Button("").apply {
            id = "overlay-window-minimize-btn"
            style.width = CssLength.px(44f)
            style.height = CssLength.px(30f)
            onClick = { minClicks++ }
        }
        val maxBtn = Button("").apply {
            id = "overlay-window-maximize-btn"
            style.width = CssLength.px(44f)
            style.height = CssLength.px(30f)
            onClick = { maxClicks++ }
        }
        val titleText = Text("Custom Title").apply {
            id = "overlay-window-title-text"
            style.width = CssLength.px(220f)
            style.height = CssLength.px(30f)
        }
        val titleBar = Container("div", "gui-overlay-window-title-bar").apply {
            style.display = CssDisplay.FLEX
            style.flexDirection = FlexDirection.ROW
            style.height = CssLength.px(34f)
            onPointerDown = { systemMoveCount++ }
            addChild(titleText)
            addChild(minBtn)
            addChild(maxBtn)
            addChild(closeBtn)
        }
        val topBar = Container("div")
        val centerZone = Container("div")
        val surface = Container("div", "w-100 h-100")
        val overlay = Container("div", "gui-overlay w-100 h-100").apply {
            addChild(titleBar)
            addChild(topBar)
            addChild(centerZone)
        }
        val hostRoot = Container("div", "w-100 h-100").apply {
            addChild(overlay)
        }
        val stageStack = Container("div", "gui-layered-container w-100 h-100").apply {
            addChild(surface)
            addChild(hostRoot)
        }

        val win = Window(title = "Overlay Buttons Window", initialSize = Size(640, 360)).apply {
            setUndecorated(true)
            setContent(Container("div", "w-100 h-100").apply { addChild(stageStack) })
        }
        win.show(factory)

        val surfaceW = surface.ensureNativeImpl()!!.nativeWidget as Win32HwndHandle
        DesktopInteractiveSurfaceSpec(surfaceWidth = { 640 }, surfaceHeight = { 360 }, swapCount = { 1L }).attachTo(surfaceW)
        val hostRootW = hostRoot.ensureNativeImpl()!!.nativeWidget as Win32HwndHandle
        hostRootW.attributes["data-overlay-host-root"] = "true"
        val overlayW = overlay.ensureNativeImpl()!!.nativeWidget as Win32HwndHandle
        DesktopStageOverlaySpec(isTargetActive = { true }, isOverlayVisible = { true }).attachTo(overlayW)
        val tbW = titleBar.ensureNativeImpl()!!.nativeWidget as Win32HwndHandle
        tbW.attributes[DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE] = DesktopStageOverlaySpec.ROLE_WINDOW_TITLE_BAR
        tbW.attributes["data-height-px"] = "34"
        (topBar.ensureNativeImpl()!!.nativeWidget as DesktopNativeWidget)
            .attributes[DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE] = DesktopStageOverlaySpec.ROLE_TOP_BAR
        (centerZone.ensureNativeImpl()!!.nativeWidget as DesktopNativeWidget)
            .attributes[DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE] = DesktopStageOverlaySpec.ROLE_CENTER_ZONE

        val closeW = closeBtn.ensureNativeImpl()!!.nativeWidget as Win32HwndHandle
        closeW.attributes["data-window-control"] = "close"
        val minW = minBtn.ensureNativeImpl()!!.nativeWidget as Win32HwndHandle
        minW.attributes["data-window-control"] = "minimize"
        val maxW = maxBtn.ensureNativeImpl()!!.nativeWidget as Win32HwndHandle
        maxW.attributes["data-window-control"] = "maximize"

        val hwnd = assertNotNull(Win32NativeWindowHost.activeHwnd)
        Win32NativeWindowHost.layoutAndSyncWindowChildren(hwnd)
        Win32NativeWindowHost.pumpVisualWindowEvents(24L)

        val closeHwnd = assertNotNull(closeW.hwnd)
        val minHwnd = assertNotNull(minW.hwnd)
        val maxHwnd = assertNotNull(maxW.hwnd)
        val tbHwnd = assertNotNull(tbW.hwnd)
        assertTrue(closeW.bounds.width > 0 && closeW.bounds.height > 0)
        assertTrue(maxW.bounds.width > 0 && maxW.bounds.height > 0)
        assertTrue(minW.bounds.width > 0 && minW.bounds.height > 0)
        assertEquals(34, tbW.bounds.height)

        fun clickAt(cx: Int, cy: Int) {
            Win32NativeWindowHost.simulateMouseClick(cx, cy, hwnd)
            GenericUiThreading.flushAllQueues()
        }

        val closePt = childCenterInHost(closeHwnd, hwnd)
        clickAt(closePt.first, closePt.second)
        assertEquals(1, closeClicks)
        assertEquals(0, systemMoveCount)

        val maxPt = childCenterInHost(maxHwnd, hwnd)
        clickAt(maxPt.first, maxPt.second)
        assertEquals(1, maxClicks)
        assertEquals(0, systemMoveCount)

        val minPt = childCenterInHost(minHwnd, hwnd)
        clickAt(minPt.first, minPt.second)
        assertEquals(1, minClicks)
        assertEquals(0, systemMoveCount)

        // Click open drag region of title bar: dispatches pointerDown on titleBar without clicking close
        val dragPt = memScoped {
            val pt = alloc<POINT>()
            pt.x = tbW.bounds.width - 12
            pt.y = tbW.bounds.height / 2
            MapWindowPoints(tbHwnd, hwnd, pt.ptr, 1u)
            pt.x to pt.y
        }
        clickAt(dragPt.first, dragPt.second)
        assertEquals(1, systemMoveCount)
        assertEquals(1, closeClicks)

        win.close()
    }

    @Test
    fun nativeTitleBarPaletteAndStyledSurfaceTextColorRespectLightAndDarkThemes() = withVisualHost { factory ->
        SystemThemeDetector.overrideDetectedTheme = ThemeMode.LIGHT

        val darkPalette = Win32DwmUxThemeBridge.resolveTitleBarPalette(isDark = true)
        val lightPalette = Win32DwmUxThemeBridge.resolveTitleBarPalette(isDark = false)
        assertNotEquals(darkPalette.captionTextColor, lightPalette.captionTextColor)
        assertNotEquals(darkPalette.captionBgColor, lightPalette.captionBgColor)

        val overlayTb = StyledContainer().apply {
            style.backgroundColor = RgbaColor(18, 20, 26, 255)
        }
        val titleText = Text("Overlay Caption").apply {
            id = "overlay-window-title-text"
            style.color = RgbaColor(242, 245, 250, 255)
        }
        overlayTb.addChild(titleText)

        val hostWin = Window(title = "Palette Host", initialSize = Size(320, 160)).apply {
            setContent(Container("div", "w-100 h-100").apply { addChild(overlayTb) })
        }
        hostWin.show(factory)

        val tbImpl = overlayTb.ensureNativeImpl() as DesktopBaseNativeImpl<*>
        val spec = Win32StyledWidgetHelper.resolveEffectiveVisualSpec(
            tbImpl.nativeWidget as Win32HwndHandle,
            tbImpl,
            "GenericUIStyledContainerW",
            isDark = false
        )
        assertEquals(RgbaColor(18, 20, 26, 255), spec.backgroundColor)
        val lblImpl = titleText.ensureNativeImpl() as DesktopBaseNativeImpl<*>
        assertEquals(RgbaColor(242, 245, 250, 255), lblImpl.getEffectiveTextColor())
        hostWin.close()
    }

    @Test
    fun layeredStageContainerUpgradesToWin32LayeredStageHostHwndWhenSpecAttached() = withVisualHost { factory ->
        val stageContainer = Container("div", "w-100 h-100")
        val surface = Container("div", "w-100 h-100")
        val hostWrapper = Container("div", "w-100 h-100")
        val activeOverlay = Container("div", "w-100 h-100")
        hostWrapper.addChild(activeOverlay)
        stageContainer.addChild(surface)
        stageContainer.addChild(hostWrapper)

        val win = Window(title = "Layered Stage Upgrade Test", initialSize = Size(480, 320)).apply {
            setContent(Container("div", "w-100 h-100").apply { addChild(stageContainer) })
        }
        win.show(factory)

        val surfaceW = surface.ensureNativeImpl()!!.nativeWidget as Win32HwndHandle
        DesktopInteractiveSurfaceSpec(surfaceWidth = { 640 }, surfaceHeight = { 360 }, swapCount = { 1L }).attachTo(surfaceW)
        val hostW = hostWrapper.ensureNativeImpl()!!.nativeWidget as Win32HwndHandle
        hostW.attributes["data-overlay-host-root"] = "true"
        val overlayW = activeOverlay.ensureNativeImpl()!!.nativeWidget as Win32HwndHandle
        DesktopStageOverlaySpec(isTargetActive = { true }, isOverlayVisible = { true }).attachTo(overlayW)

        val stageContainerImpl = stageContainer.ensureNativeImpl() as WindowsContainerNativeImpl
        assertTrue(Win32LayeredStageHostHwnd.isLayeredStageContainer(stageContainerImpl))

        val hostHwnd = assertNotNull(Win32NativeWindowHost.activeHwnd)
        Win32NativeWindowHost.layoutAndSyncWindowChildren(hostHwnd)
        Win32NativeWindowHost.pumpVisualWindowEvents(16L)

        val stageContainerHwnd = assertNotNull(stageContainerImpl.nativeWidget.hwnd)
        assertEquals("GenericUILayeredStageHostW", readWin32ClassName(stageContainerHwnd))
        assertTrue(Win32LayeredStageHostHwnd.isStageHostHwnd(stageContainerHwnd))
        val surfaceHwnd = assertNotNull(surfaceW.hwnd)
        assertEquals("GenericUIInteractiveSurfaceW", readWin32ClassName(surfaceHwnd))
        win.close()
    }

    @Test
    fun undecoratedResizeHitTestCursorsMinSizeAndAspectRatioEnforced() = memScoped {
        val l = 100
        val t = 100
        val r = 500
        val b = 400
        assertEquals(HTTOPLEFT, Win32NativeWindowHost.hitTestUndecoratedRect(l, t, r, b, l + 2, t + 2, resizable = true, isFullscreenOrMaximized = false))
        assertEquals(HTTOPRIGHT, Win32NativeWindowHost.hitTestUndecoratedRect(l, t, r, b, r - 2, t + 2, resizable = true, isFullscreenOrMaximized = false))
        assertEquals(HTBOTTOMLEFT, Win32NativeWindowHost.hitTestUndecoratedRect(l, t, r, b, l + 2, b - 2, resizable = true, isFullscreenOrMaximized = false))
        assertEquals(HTBOTTOMRIGHT, Win32NativeWindowHost.hitTestUndecoratedRect(l, t, r, b, r - 2, b - 2, resizable = true, isFullscreenOrMaximized = false))
        assertEquals(HTLEFT, Win32NativeWindowHost.hitTestUndecoratedRect(l, t, r, b, l + 2, 250, resizable = true, isFullscreenOrMaximized = false))
        assertEquals(HTRIGHT, Win32NativeWindowHost.hitTestUndecoratedRect(l, t, r, b, r - 2, 250, resizable = true, isFullscreenOrMaximized = false))
        assertEquals(HTTOP, Win32NativeWindowHost.hitTestUndecoratedRect(l, t, r, b, 300, t + 2, resizable = true, isFullscreenOrMaximized = false))
        assertEquals(HTBOTTOM, Win32NativeWindowHost.hitTestUndecoratedRect(l, t, r, b, 300, b - 2, resizable = true, isFullscreenOrMaximized = false))
        assertEquals(HTCLIENT, Win32NativeWindowHost.hitTestUndecoratedRect(l, t, r, b, 300, 250, resizable = true, isFullscreenOrMaximized = false))
        assertEquals(HTCLIENT, Win32NativeWindowHost.hitTestUndecoratedRect(l, t, r, b, l + 2, t + 2, resizable = false, isFullscreenOrMaximized = false))

        assertEquals(IDC_SIZEWE, Win32NativeWindowHost.resolveResizeCursorForNcHitTest(HTLEFT))
        assertEquals(IDC_SIZEWE, Win32NativeWindowHost.resolveResizeCursorForNcHitTest(HTRIGHT))
        assertEquals(IDC_SIZENS, Win32NativeWindowHost.resolveResizeCursorForNcHitTest(HTTOP))
        assertEquals(IDC_SIZENS, Win32NativeWindowHost.resolveResizeCursorForNcHitTest(HTBOTTOM))
        assertEquals(IDC_SIZENWSE, Win32NativeWindowHost.resolveResizeCursorForNcHitTest(HTTOPLEFT))
        assertEquals(IDC_SIZENWSE, Win32NativeWindowHost.resolveResizeCursorForNcHitTest(HTBOTTOMRIGHT))
        assertEquals(IDC_SIZENESW, Win32NativeWindowHost.resolveResizeCursorForNcHitTest(HTTOPRIGHT))
        assertEquals(IDC_SIZENESW, Win32NativeWindowHost.resolveResizeCursorForNcHitTest(HTBOTTOMLEFT))
        assertNull(Win32NativeWindowHost.resolveResizeCursorForNcHitTest(HTCLIENT))

        val undecImpl = WindowsWindowNativeImpl().apply {
            setUndecorated(true)
            setMinWindowSize(Size(360, 240))
        }
        assertEquals(360 to 240, Win32NativeWindowHost.resolveMinWindowTrackSize(undecImpl))

        val dragRect = alloc<RECT>().apply {
            left = 400
            top = 150
            right = 600
            bottom = 300
        }
        Win32NativeWindowHost.adjustRectForMinSize(dragRect, WMSZ_LEFT, 360, 240)
        assertEquals(600, dragRect.right)
        assertEquals(240, dragRect.left)
        assertEquals(390, dragRect.bottom)

        val ratioRect = alloc<RECT>().apply {
            left = 100
            top = 100
            right = 420
            bottom = 350
        }
        Win32NativeWindowHost.adjustRectForAspectRatio(
            rect = ratioRect,
            edge = WMSZ_BOTTOMRIGHT,
            frameW = 0,
            frameH = 0,
            currentClientW = 640,
            currentClientH = 360,
            minTrackW = 320,
            minTrackH = 180,
            aspectRatio = 16.0 / 9.0
        )
        val w = ratioRect.right - ratioRect.left
        val h = ratioRect.bottom - ratioRect.top
        assertTrue(w >= 320 && h >= 180)
        assertTrue(kotlin.math.abs((w.toDouble() / h.toDouble()) - (16.0 / 9.0)) < 0.03)
    }

    @Test
    fun standaloneDialogScrollViewMouseWheelScrollsAndSelectAnchorsAtControlBottomLeft() = withVisualHost { _ ->
        val scrollView = ScrollView()
        val selectControl = Select(
            options = listOf(SelectOption("one", "One"), SelectOption("two", "Two")),
            selectedValue = "one"
        )
        scrollView.addChild(selectControl)
        for (idx in 0 until 24) {
            scrollView.addChild(Text("Dialog scroll item line #$idx for wheel test"))
        }

        val dialog = DialogWindow(title = "Scrollable Standalone Dialog").apply {
            setWindowSize(Size(420, 260))
            setContent(Container("div", "w-100 h-100").apply { addChild(scrollView) })
        }
        dialog.open()
        Win32NativeWindowHost.pumpVisualWindowEvents(24L)

        val dialogImpl = dialog.ensureNativeImpl() as DesktopDialogWindowNativeImpl<*>
        val dHwnd = assertNotNull(Win32NativeWindowHost.dialogHwnds[dialogImpl])
        Win32NativeWindowHost.layoutAndSyncWindowChildren(dHwnd)

        val svImpl = scrollView.ensureNativeImpl() as WindowsScrollViewNativeImpl
        val svHwnd = assertNotNull(svImpl.nativeWidget.hwnd)
        assertEquals("GenericUIScrollViewW", readWin32ClassName(svHwnd))
        val svCenter = childCenterInHost(svHwnd, dHwnd)
        val scrollBefore = svImpl.currentScrollY()
        Win32NativeWindowHost.handleMouseWheelAt(dHwnd, svCenter.first, svCenter.second, -120)
        assertTrue(
            svImpl.currentScrollY() > scrollBefore,
            "Mouse wheel inside standalone DialogWindow must scroll WindowsScrollViewNativeImpl (before=$scrollBefore, after=${svImpl.currentScrollY()})"
        )

        val selectImpl = selectControl.ensureNativeImpl() as DesktopSelectNativeImpl<*>
        val selectHandle = selectImpl.nativeWidget as Win32HwndHandle
        val comboHwnd = assertNotNull(selectHandle.hwnd)
        assertEquals("ComboBox", readWin32ClassName(comboHwnd))
        val sb = selectImpl.nativeWidget.bounds
        assertTrue(sb.width > 0 && sb.height > 0, "Select inside DialogWindow must record non-empty bounds")
        val comboRect = memScoped {
            val r = alloc<RECT>()
            assertTrue(
                SendMessageW(comboHwnd, 0x0152u /* CB_GETDROPPEDCONTROLRECT */, 0uL, r.ptr.toLong()) != 0L,
                "CB_GETDROPPEDCONTROLRECT should succeed on ComboBox HWND"
            )
            r.bottom - r.top
        }
        assertTrue(comboRect >= 260, "ComboBox dropped-down HWND height must accommodate drop-down list (got $comboRect)")

        val clickX = (sb.x + sb.width * 0.7f).toInt()
        val clickY = (sb.y + sb.height * 0.5f).toInt()
        val (anchorX, anchorY) = Win32NativeWindowHost.resolveSelectPopupClientAnchor(sb, clickX, clickY)
        assertEquals(sb.x, anchorX, "Select popup must align left edge to Select widget")
        assertEquals(sb.y + sb.height, anchorY, "Select popup must anchor flush below Select widget")

        val syntheticAnchor = Win32NativeWindowHost.resolveSelectPopupClientAnchor(
            Rect(24, 68, 180, 28),
            clickX = 140,
            clickY = 80
        )
        assertEquals(24 to 96, syntheticAnchor)

        dialog.close()
    }
}
