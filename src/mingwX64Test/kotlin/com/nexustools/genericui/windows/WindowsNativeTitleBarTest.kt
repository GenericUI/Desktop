@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.component.action.Button
import com.nexustools.genericui.component.dialog.DialogWindow
import com.nexustools.genericui.component.dock.PanelWindow
import com.nexustools.genericui.component.layout.Container
import com.nexustools.genericui.component.layout.ScrollView
import com.nexustools.genericui.component.shell.Window
import com.nexustools.genericui.component.surface.Card
import com.nexustools.genericui.component.typography.Heading
import com.nexustools.genericui.component.typography.Text
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.dialog.DesktopDialogWindowNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopScrollViewNativeImpl
import com.nexustools.genericui.window.TitleBar
import com.nexustools.genericui.windows.impl.WindowsScrollViewNativeImpl
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import platform.windows.GetClientRect
import platform.windows.GetScrollInfo
import platform.windows.GetWindowLongW
import platform.windows.GWL_STYLE
import platform.windows.HTCAPTION
import platform.windows.HTCLIENT
import platform.windows.HTCLOSE
import platform.windows.HTMAXBUTTON
import platform.windows.HTMINBUTTON
import platform.windows.HTSYSMENU
import platform.windows.HWND
import platform.windows.InvalidateRect
import platform.windows.IsWindow
import platform.windows.IsWindowVisible
import platform.windows.MapWindowPoints
import platform.windows.POINT
import platform.windows.RDW_ALLCHILDREN
import platform.windows.RDW_INVALIDATE
import platform.windows.RDW_UPDATENOW
import platform.windows.RECT
import platform.windows.RedrawWindow
import platform.windows.SB_LINEDOWN
import platform.windows.SB_LINEUP
import platform.windows.SB_PAGEDOWN
import platform.windows.SB_VERT
import platform.windows.SCROLLINFO
import platform.windows.SIF_PAGE
import platform.windows.SIF_POS
import platform.windows.SIF_RANGE
import platform.windows.SendMessageW
import platform.windows.UpdateWindow
import platform.windows.WM_NCLBUTTONDOWN
import platform.windows.WM_NCLBUTTONUP
import platform.windows.WM_NCMOUSELEAVE
import platform.windows.WM_NCMOUSEMOVE
import platform.windows.WM_VSCROLL
import platform.windows.WS_VSCROLL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WindowsNativeTitleBarTest {

    @Test
    fun testVisualStyleGenerationDetectionAcrossAllWindowsVersions() {
        assertEquals(
            Win32VisualStyleGeneration.WIN11_MICA_FLUENT,
            Win32DwmUxThemeBridge.detectVisualStyleGeneration(
                forceMajor = 10,
                forceMinor = 0,
                forceBuild = 22631,
                dwmEnabled = true,
                uxThemeActive = true
            )
        )
        assertEquals(
            Win32VisualStyleGeneration.WIN10_FLUENT,
            Win32DwmUxThemeBridge.detectVisualStyleGeneration(
                forceMajor = 10,
                forceMinor = 0,
                forceBuild = 19045,
                dwmEnabled = true,
                uxThemeActive = true
            )
        )
        assertEquals(
            Win32VisualStyleGeneration.WIN8_AERO,
            Win32DwmUxThemeBridge.detectVisualStyleGeneration(
                forceMajor = 6,
                forceMinor = 3,
                forceBuild = 9600,
                dwmEnabled = true,
                uxThemeActive = true
            )
        )
        assertEquals(
            Win32VisualStyleGeneration.VISTA_7_AERO_GLASS,
            Win32DwmUxThemeBridge.detectVisualStyleGeneration(
                forceMajor = 6,
                forceMinor = 1,
                forceBuild = 7601,
                dwmEnabled = true,
                uxThemeActive = true
            )
        )
        assertEquals(
            Win32VisualStyleGeneration.UXTHEME_BASIC,
            Win32DwmUxThemeBridge.detectVisualStyleGeneration(
                forceMajor = 6,
                forceMinor = 1,
                forceBuild = 7601,
                dwmEnabled = false,
                uxThemeActive = true
            )
        )
        assertEquals(
            Win32VisualStyleGeneration.WIN32_CLASSIC,
            Win32DwmUxThemeBridge.detectVisualStyleGeneration(
                forceMajor = 6,
                forceMinor = 1,
                forceBuild = 7601,
                dwmEnabled = false,
                uxThemeActive = false
            )
        )
    }

    @Test
    fun testTitleBarKindDistinguishesStandardWindowAndToolWindow() {
        val standard = Win32TitleBarKind.STANDARD_WINDOW
        val tool = Win32TitleBarKind.TOOL_WINDOW
        val dialog = Win32TitleBarKind.DIALOG_WINDOW
        val dockedHeader = Win32TitleBarKind.DOCKED_PANEL_HEADER

        assertFalse(standard.usesSmallCaptionMetrics)
        assertTrue(standard.allowMinimizeMaximizeButtons)
        assertTrue(standard.defaultShowWindowIcon)
        assertTrue(standard.defaultShowMinimizeButton)
        assertTrue(standard.defaultShowMaximizeButton)
        assertTrue(standard.defaultShowCloseButton)
        assertEquals(46, standard.defaultCaptionButtonWidthPx)
        assertEquals(10, standard.defaultGlyphSizePx)
        assertEquals(Win32DwmUxThemeBridge.WP_CAPTION, standard.uxThemeCaptionPartId)
        assertEquals(Win32DwmUxThemeBridge.WP_CLOSEBUTTON, standard.uxThemeCloseButtonPartId)
        assertEquals(Win32DwmUxThemeBridge.DWMWCP_ROUND, standard.win11CornerPreference)
        assertEquals(Win32DwmUxThemeBridge.DWMSBT_MAINWINDOW, standard.win11BackdropType)

        assertTrue(tool.usesSmallCaptionMetrics)
        assertFalse(tool.allowMinimizeMaximizeButtons)
        assertFalse(tool.defaultShowWindowIcon)
        assertFalse(tool.defaultShowMinimizeButton)
        assertFalse(tool.defaultShowMaximizeButton)
        assertTrue(tool.defaultShowCloseButton)
        assertEquals(28, tool.defaultCaptionButtonWidthPx)
        assertEquals(8, tool.defaultGlyphSizePx)
        assertEquals(Win32DwmUxThemeBridge.WP_SMALLCAPTION, tool.uxThemeCaptionPartId)
        assertEquals(Win32DwmUxThemeBridge.WP_SMALLCLOSEBUTTON, tool.uxThemeCloseButtonPartId)
        assertEquals(Win32DwmUxThemeBridge.DWMWCP_ROUNDSMALL, tool.win11CornerPreference)
        assertEquals(Win32DwmUxThemeBridge.DWMSBT_TRANSIENTWINDOW, tool.win11BackdropType)

        assertTrue(tool.defaultFallbackHeightPx < standard.defaultFallbackHeightPx)
        assertFalse(dialog.allowMinimizeMaximizeButtons)
        assertFalse(dockedHeader.defaultShowCloseButton)
    }

    @Test
    fun testPanelWindowAndDockWindowUseToolTitleBarKindWithSharedComponentEmbedding() {
        val prevInteractive = WindowsApplicationNativeImpl.interactiveWin32GuiEnabled
        WindowsApplicationNativeImpl.interactiveWin32GuiEnabled = false
        try {
            GenericUI.initialize(WindowsNativeFactory())

            val mainWindow = Window(title = "Main App Window")
            val mainBackBtn = Button(text = "← Back")
            mainWindow.setTitleBar(TitleBar(customTitleBar = true).addLeading(mainBackBtn))
            mainWindow.show()
            val mainImpl = mainWindow.ensureNativeImpl() as WindowsWindowNativeImpl
            assertEquals(Win32TitleBarKind.STANDARD_WINDOW, mainImpl.titleBarKind)
            assertEquals(1, mainImpl.getTitleBarInstructions()?.leadingComponents?.size)

            val panelWindow = PanelWindow(title = "Layers Tool Panel", ownerWindow = mainWindow)
            val panelActionBtn = Button(text = "+ Layer")
            panelWindow.setTitleBar(TitleBar(customTitleBar = true).addTrailing(panelActionBtn))
            panelWindow.show()
            val panelImpl = panelWindow.ensureNativeImpl() as WindowsWindowNativeImpl
            assertTrue(panelImpl.isToolModalWindow)
            assertEquals(Win32TitleBarKind.TOOL_WINDOW, panelImpl.titleBarKind)
            assertEquals(false, panelImpl.getTitleBarInstructions()?.showMinimizeButton)
            assertEquals(false, panelImpl.getTitleBarInstructions()?.showMaximizeButton)
            assertEquals(false, panelImpl.getTitleBarInstructions()?.showWindowIcon)
            assertEquals(1, panelImpl.getTitleBarInstructions()?.trailingComponents?.size)
            assertTrue((panelImpl.nativeWidget.dwExStyle and Win32HwndHandle.WS_EX_TOOLWINDOW) != 0L)
            assertEquals(0L, panelImpl.nativeWidget.dwStyle and Win32HwndHandle.WS_MINIMIZEBOX)
            assertEquals(0L, panelImpl.nativeWidget.dwStyle and Win32HwndHandle.WS_MAXIMIZEBOX)

            val dockWindowImpl = WindowsDockWindowNativeImpl()
            dockWindowImpl.applyTitleBarInstructions(TitleBar().addLeading(Button(text = "Dock Action")))
            assertEquals(Win32TitleBarKind.TOOL_WINDOW, dockWindowImpl.titleBarKind)
            assertEquals(1, dockWindowImpl.getTitleBarInstructions()?.leadingComponents?.size)
            assertEquals(false, dockWindowImpl.getTitleBarInstructions()?.showMinimizeButton)
            assertEquals(false, dockWindowImpl.getTitleBarInstructions()?.showMaximizeButton)
        } finally {
            WindowsApplicationNativeImpl.interactiveWin32GuiEnabled = prevInteractive
        }
    }

    @Test
    fun testPaletteResolutionAcrossThemesAndAccentLuminance() {
        val win11Dark = Win32DwmUxThemeBridge.resolveTitleBarPalette(
            hwnd = null,
            isWindowActive = true,
            kind = Win32TitleBarKind.STANDARD_WINDOW,
            generation = Win32VisualStyleGeneration.WIN11_MICA_FLUENT,
            isDark = true,
            colorPrevalence = false,
            accentColorRef = null
        )
        assertTrue(win11Dark.isDark)
        assertEquals(Win32DwmUxThemeBridge.colorRefRgb(32, 32, 32), win11Dark.captionBgColor)
        assertEquals(Win32DwmUxThemeBridge.colorRefRgb(255, 255, 255), win11Dark.captionTextColor)
        assertEquals(46, win11Dark.captionButtonWidthPx)

        val win11ToolLight = Win32DwmUxThemeBridge.resolveTitleBarPalette(
            hwnd = null,
            isWindowActive = true,
            isToolWindow = true,
            kind = Win32TitleBarKind.TOOL_WINDOW,
            generation = Win32VisualStyleGeneration.WIN11_MICA_FLUENT,
            isDark = false,
            colorPrevalence = false,
            accentColorRef = null
        )
        assertFalse(win11ToolLight.isDark)
        assertEquals(Win32DwmUxThemeBridge.colorRefRgb(243, 243, 243), win11ToolLight.captionBgColor)
        assertEquals(28, win11ToolLight.captionButtonWidthPx)

        // Bright yellow accent with ColorPrevalence -> dark text for contrast
        val brightAccent = Win32DwmUxThemeBridge.colorRefRgb(250, 235, 60)
        val accentedLight = Win32DwmUxThemeBridge.resolveTitleBarPalette(
            hwnd = null,
            isWindowActive = true,
            kind = Win32TitleBarKind.STANDARD_WINDOW,
            generation = Win32VisualStyleGeneration.WIN10_FLUENT,
            isDark = true,
            colorPrevalence = true,
            accentColorRef = brightAccent
        )
        assertTrue(accentedLight.hasAccentColorOnTitleBar)
        assertEquals(brightAccent, accentedLight.captionBgColor)
        assertEquals(Win32DwmUxThemeBridge.colorRefRgb(0, 0, 0), accentedLight.captionTextColor)

        // Vista/7 Aero Glass -> transparent DWM backdrop + Aero text glow
        val aeroGlass = Win32DwmUxThemeBridge.resolveTitleBarPalette(
            hwnd = null,
            isWindowActive = true,
            kind = Win32TitleBarKind.STANDARD_WINDOW,
            generation = Win32VisualStyleGeneration.VISTA_7_AERO_GLASS
        )
        assertTrue(aeroGlass.useTransparentDwmBackdrop)
        assertTrue(aeroGlass.drawAeroTextGlow)
        assertTrue(aeroGlass.dwmDrawsCaptionButtons)
    }

    @Test
    fun testHitTestingStandardWindowVsToolWindowAndEmbeddedComponents() {
        val winW = 800

        // Standard Window (34px height, 46px buttons: Minimize, Maximize, Close + left icon)
        assertEquals(
            HTSYSMENU,
            Win32NativeTitleBarRenderer.hitTestLocalCoordinates(
                cx = 14,
                cy = 16,
                windowWidth = winW,
                titleBarHeight = 34,
                kind = Win32TitleBarKind.STANDARD_WINDOW
            )
        )
        assertEquals(
            HTCLOSE,
            Win32NativeTitleBarRenderer.hitTestLocalCoordinates(
                cx = winW - 10,
                cy = 16,
                windowWidth = winW,
                titleBarHeight = 34,
                kind = Win32TitleBarKind.STANDARD_WINDOW
            )
        )
        assertEquals(
            HTMAXBUTTON,
            Win32NativeTitleBarRenderer.hitTestLocalCoordinates(
                cx = winW - 60,
                cy = 16,
                windowWidth = winW,
                titleBarHeight = 34,
                kind = Win32TitleBarKind.STANDARD_WINDOW
            )
        )
        assertEquals(
            HTMINBUTTON,
            Win32NativeTitleBarRenderer.hitTestLocalCoordinates(
                cx = winW - 110,
                cy = 16,
                windowWidth = winW,
                titleBarHeight = 34,
                kind = Win32TitleBarKind.STANDARD_WINDOW
            )
        )
        assertEquals(
            HTCLIENT,
            Win32NativeTitleBarRenderer.hitTestLocalCoordinates(
                cx = 60,
                cy = 16,
                windowWidth = winW,
                titleBarHeight = 34,
                kind = Win32TitleBarKind.STANDARD_WINDOW,
                hasInteractiveComponentAtPoint = true
            )
        )

        // Tool Window (26px height, 28px close button only, no icon, no minimize/maximize)
        assertEquals(
            HTCAPTION,
            Win32NativeTitleBarRenderer.hitTestLocalCoordinates(
                cx = 14,
                cy = 12,
                windowWidth = 400,
                titleBarHeight = 26,
                kind = Win32TitleBarKind.TOOL_WINDOW
            )
        )
        assertEquals(
            HTCLOSE,
            Win32NativeTitleBarRenderer.hitTestLocalCoordinates(
                cx = 400 - 10,
                cy = 12,
                windowWidth = 400,
                titleBarHeight = 26,
                kind = Win32TitleBarKind.TOOL_WINDOW
            )
        )
        // Area to the left of the 28px tool close button is HTCAPTION (not HTMAXBUTTON/HTMINBUTTON!)
        assertEquals(
            HTCAPTION,
            Win32NativeTitleBarRenderer.hitTestLocalCoordinates(
                cx = 400 - 40,
                cy = 12,
                windowWidth = 400,
                titleBarHeight = 26,
                kind = Win32TitleBarKind.TOOL_WINDOW
            )
        )
        // Embedded component in tool titlebar returns HTCLIENT
        assertEquals(
            HTCLIENT,
            Win32NativeTitleBarRenderer.hitTestLocalCoordinates(
                cx = 400 - 50,
                cy = 12,
                windowWidth = 400,
                titleBarHeight = 26,
                kind = Win32TitleBarKind.TOOL_WINDOW,
                hasInteractiveComponentAtPoint = true
            )
        )
    }

    @Test
    fun testClassTokenCacheAndInteractiveCardPageScrollbarHoverDragAndWheel() {
        val prevInteractive = WindowsApplicationNativeImpl.interactiveWin32GuiEnabled
        val prevVisualTest = Win32NativeWindowHost.visualTestMode
        WindowsApplicationNativeImpl.interactiveWin32GuiEnabled = true
        Win32NativeWindowHost.visualTestMode = true
        try {
            val factory = WindowsNativeFactory()
            GenericUI.initialize(factory)

            val rootContainer = Container("div", "w-100 h-100")
            val scrollView = ScrollView().apply { addClass("overflow-auto w-100 h-100") }
            val pageHost = Container("div", "page-host")
            val activePage = Container("div", "asset-page")
            val outerCard = Card().apply { addClass("card") }
            val cardBody = Container("div", "card-body")
            cardBody.addChild(Heading("Asset Manager", level = 1))
            cardBody.addChild(Heading("Select an asset category to view or edit", level = 5))

            for (i in 1..45) {
                val innerCard = Card().apply { addClass("card") }
                val innerBody = Container("div", "card-body")
                innerBody.addChild(Heading("Asset Category $i", level = 5))
                innerBody.addChild(Text("Description for asset category $i"))
                innerBody.addChild(Button("Open"))
                innerCard.addChild(innerBody)
                cardBody.addChild(innerCard)
            }

            outerCard.addChild(cardBody)
            activePage.addChild(outerCard)
            pageHost.addChild(activePage)
            scrollView.addChild(pageHost)
            rootContainer.addChild(scrollView)

            val mainWindow = Window(title = "Asset Page Scrollbar Test")
            mainWindow.setContent(rootContainer)
            mainWindow.show(factory)
            Win32NativeWindowHost.pumpVisualWindowEvents(24L)

            val rootImpl = rootContainer.ensureNativeImpl() as DesktopBaseNativeImpl<*>
            val firstTokenSet = rootImpl.getClassTokensSet()
            val secondTokenSet = rootImpl.getClassTokensSet()
            assertSame(firstTokenSet, secondTokenSet)
            assertTrue(rootImpl.hasClass("w-100"))
            assertFalse(rootImpl.hasClass("non-existent-class"))

            val hwnd = Win32NativeWindowHost.activeHwnd
            assertTrue(hwnd != null, "Expected active Win32 HWND")
            val scrollViewImpl = scrollView.ensureNativeImpl() as WindowsScrollViewNativeImpl
            val svHwnd = scrollViewImpl.nativeWidget.hwnd
            assertNotNull(svHwnd, "Expected retained ScrollView HWND")
            assertTrue((GetWindowLongW(svHwnd, GWL_STYLE) and WS_VSCROLL) != 0, "ScrollView HWND must expose WS_VSCROLL")

            fun queryScrollInfo(targetHwnd: HWND): Triple<Int, Int, Int> = memScoped {
                val si = alloc<SCROLLINFO>()
                si.cbSize = sizeOf<SCROLLINFO>().toUInt()
                si.fMask = (SIF_RANGE or SIF_PAGE or SIF_POS).toUInt()
                GetScrollInfo(targetHwnd, SB_VERT, si.ptr)
                Triple(si.nMax, si.nPage.toInt(), si.nPos)
            }

            val (maxRange, pageSize, initialPos) = queryScrollInfo(svHwnd)
            val maxScroll = (maxRange + 1 - pageSize).coerceAtLeast(0)
            assertTrue(maxScroll > 0, "Expected positive Win32 SCROLLINFO maxScroll for 45 cards, got max=$maxRange page=$pageSize")
            assertEquals(0, initialPos)
            assertEquals(0, scrollViewImpl.currentScrollY())

            val svBounds = scrollViewImpl.nativeWidget.bounds
            val svCenterX = svBounds.x + svBounds.width / 2
            val svCenterY = svBounds.y + svBounds.height / 2

            // 1. Hover over the retained child HWND hierarchy inside ScrollView
            val hoveredTarget = Win32ControlMessageRouter.findDeepestHitChildHwnd(hwnd, svCenterX, svCenterY)
            assertTrue(hoveredTarget != hwnd, "Expected hover hit inside ScrollView retained child hierarchy")
            Win32NativeWindowHost.simulateMouseMove(svCenterX, svCenterY, hwnd)

            // 2. Native WM_VSCROLL paging -> updates ScrollView offset and Win32 SCROLLINFO.nPos
            SendMessageW(svHwnd, WM_VSCROLL.toUInt(), SB_PAGEDOWN.toULong(), 0L)
            Win32NativeWindowHost.pumpVisualWindowEvents(16L)
            val scrolledPos = scrollViewImpl.currentScrollY()
            assertTrue(scrolledPos > 0, "Expected WM_VSCROLL SB_PAGEDOWN to increase scrollY, got $scrolledPos")
            val (_, _, win32PosAfterPage) = queryScrollInfo(svHwnd)
            assertEquals(scrolledPos, win32PosAfterPage, "SCROLLINFO nPos must stay synchronized with ScrollView scrollY")

            // 3. Mouse wheel down -> scrollY increases further via WM_MOUSEWHEEL bubbling
            Win32NativeWindowHost.handleMouseWheelAt(hwnd, svCenterX, svCenterY, -120)
            Win32NativeWindowHost.pumpVisualWindowEvents(16L)
            val afterWheelScrollY = scrollViewImpl.currentScrollY()
            assertTrue(afterWheelScrollY > scrolledPos, "Expected wheel to advance scrollY from $scrolledPos, got $afterWheelScrollY")

            // 4. Programmatic scrollView.scrollTo(0, 0) -> resets scrollY and Win32 SCROLLINFO.nPos to 0
            scrollView.scrollTo(0, 0)
            RedrawWindow(hwnd, null, null, (RDW_INVALIDATE or RDW_UPDATENOW or RDW_ALLCHILDREN).toUInt())
            assertEquals(0, scrollViewImpl.currentScrollY())
            assertEquals(0, queryScrollInfo(svHwnd).third)
        } finally {
            Win32NativeWindowHost.closeAllVisualWindows()
            Win32NativeWindowHost.visualTestMode = prevVisualTest
            WindowsApplicationNativeImpl.interactiveWin32GuiEnabled = prevInteractive
        }
    }

    @Test
    fun testAssetPanelRenderingAndInteractionLagBenchmark() {
        val prevInteractive = WindowsApplicationNativeImpl.interactiveWin32GuiEnabled
        val prevVisualTest = Win32NativeWindowHost.visualTestMode
        WindowsApplicationNativeImpl.interactiveWin32GuiEnabled = true
        Win32NativeWindowHost.visualTestMode = true
        try {
            val factory = WindowsNativeFactory()
            GenericUI.initialize(factory)

            // Build the full DCApp AssetManager hierarchy:
            // rootContainer -> ScrollView -> page-host -> Fragment -> pageRoot (.container.page.page-asset)
            // -> outerCard (.card) -> cardBody (.card-body) -> Row (.row) -> 60 Col (.col-12.col-md-6.col-lg-4)
            // -> innerCard (.card) -> innerBody (.card-body) -> Heading, Text, Button
            val rootContainer = Container("div", "dcapp-root w-100 h-100")
            val scrollView = ScrollView().apply { addClass("overflow-auto w-100 h-100") }
            val pageHost = Container("div", "page-host")
            val assetFragmentWrapper = Container("div", "fragment")
            val pageRoot = Container("div", "container page page-asset mb-2")
            val outerCard = Card().apply { addClass("card mt-2 mb-2") }
            val cardBody = Container("div", "card-body flex-fill d-flex flex-column text-center")
            cardBody.addChild(Heading("Asset Manager", level = 1))
            cardBody.addChild(
                Heading(
                    "Here you can manage your own asset, as well as search for and view asset created by others",
                    level = 5
                )
            )

            val gridRow = Container("div", "row")
            for (i in 1..60) {
                val col = Container("div", "col-12 col-md-6 col-lg-4 d-flex flex-column")
                val innerCard = Card().apply { addClass("card flex-fill mb-3") }
                val innerBody = Container("div", "card-body d-flex flex-column")
                innerBody.addChild(Heading("Asset Category $i", level = 5))
                innerBody.addChild(
                    Text("Edit the structure, properties, and references of Asset Category $i here")
                )
                innerBody.addChild(Container("div", "flex-grow-1"))
                innerBody.addChild(Button("View/Edit Category $i"))
                innerCard.addChild(innerBody)
                col.addChild(innerCard)
                gridRow.addChild(col)
            }
            cardBody.addChild(gridRow)
            outerCard.addChild(cardBody)
            pageRoot.addChild(outerCard)
            assetFragmentWrapper.addChild(pageRoot)
            pageHost.addChild(assetFragmentWrapper)
            scrollView.addChild(pageHost)
            rootContainer.addChild(scrollView)

            val mainWindow = Window(title = "Asset Manager ~ DC App")
            mainWindow.setContent(rootContainer)
            mainWindow.show(factory)
            Win32NativeWindowHost.pumpVisualWindowEvents(24L)

            val hwnd = Win32NativeWindowHost.activeHwnd
            assertTrue(hwnd != null, "Expected active Win32 HWND for Asset Panel benchmark")
            val scrollViewImpl = scrollView.ensureNativeImpl() as WindowsScrollViewNativeImpl
            val svHwnd = scrollViewImpl.nativeWidget.hwnd
            assertNotNull(svHwnd, "Expected retained ScrollView HWND for Asset Panel benchmark")

            // Warm-up frames
            repeat(3) {
                RedrawWindow(hwnd, null, null, (RDW_INVALIDATE or RDW_UPDATENOW or RDW_ALLCHILDREN).toUInt())
            }

            val maxScroll = memScoped {
                val si = alloc<SCROLLINFO>()
                si.cbSize = sizeOf<SCROLLINFO>().toUInt()
                si.fMask = (SIF_RANGE or SIF_PAGE or SIF_POS).toUInt()
                GetScrollInfo(svHwnd, SB_VERT, si.ptr)
                (si.nMax + 1 - si.nPage.toInt()).coerceAtLeast(0)
            }
            assertTrue(
                maxScroll > 0,
                "Expected positive maxScrollY for 60 nested DCApp Asset Manager cards, got $maxScroll"
            )

            val clock = kotlin.time.TimeSource.Monotonic

            // 1. Benchmark 30 full retained window repaints
            val paintTimesMs = DoubleArray(30)
            for (i in paintTimesMs.indices) {
                val mark = clock.markNow()
                RedrawWindow(hwnd, null, null, (RDW_INVALIDATE or RDW_UPDATENOW or RDW_ALLCHILDREN).toUInt())
                paintTimesMs[i] = mark.elapsedNow().inWholeMicroseconds / 1000.0
            }

            // 2. Benchmark 60 mouse-move hover sweeps across retained cards, buttons, and viewport
            val hoverTimesMs = DoubleArray(60)
            for (i in hoverTimesMs.indices) {
                val mx = 120 + (i * 17) % 980
                val my = 110 + (i * 23) % 580
                val mark = clock.markNow()
                Win32NativeWindowHost.simulateMouseMove(mx, my, hwnd)
                UpdateWindow(hwnd)
                hoverTimesMs[i] = mark.elapsedNow().inWholeMicroseconds / 1000.0
            }

            // 3. Benchmark 40 continuous retained scrollbar scroll steps via WM_VSCROLL
            val dragTimesMs = DoubleArray(40)
            for (i in dragTimesMs.indices) {
                val scrollCmd = if (i < 20) SB_LINEDOWN else SB_LINEUP
                val mark = clock.markNow()
                SendMessageW(svHwnd, WM_VSCROLL.toUInt(), scrollCmd.toULong(), 0L)
                UpdateWindow(hwnd)
                dragTimesMs[i] = mark.elapsedNow().inWholeMicroseconds / 1000.0
            }

            // 4. Benchmark 30 mouse wheel scroll steps
            val svBounds = scrollViewImpl.nativeWidget.bounds
            val svCenterX = (svBounds.x + svBounds.width / 2).coerceAtLeast(10)
            val svCenterY = (svBounds.y + svBounds.height / 2).coerceAtLeast(10)
            val wheelTimesMs = DoubleArray(30)
            for (i in wheelTimesMs.indices) {
                val delta = if (i < 15) -120 else 120
                val mark = clock.markNow()
                Win32NativeWindowHost.handleMouseWheelAt(hwnd, svCenterX, svCenterY, delta)
                wheelTimesMs[i] = mark.elapsedNow().inWholeMicroseconds / 1000.0
            }

            fun percentile95(samples: DoubleArray): Double {
                val sorted = samples.sortedArray()
                val idx = ((sorted.size - 1) * 0.95).toInt().coerceIn(0, sorted.lastIndex)
                return sorted[idx]
            }

            val avgPaintMs = paintTimesMs.average()
            val p95PaintMs = percentile95(paintTimesMs)
            val avgHoverMs = hoverTimesMs.average()
            val p95HoverMs = percentile95(hoverTimesMs)
            val avgDragMs = dragTimesMs.average()
            val p95DragMs = percentile95(dragTimesMs)
            val avgWheelMs = wheelTimesMs.average()
            val p95WheelMs = percentile95(wheelTimesMs)

            println(
                "[AssetPanelLagBenchmark] " +
                    "paint(avg=${avgPaintMs}ms, p95=${p95PaintMs}ms) | " +
                    "hover(avg=${avgHoverMs}ms, p95=${p95HoverMs}ms) | " +
                    "drag(avg=${avgDragMs}ms, p95=${p95DragMs}ms) | " +
                    "wheel(avg=${avgWheelMs}ms, p95=${p95WheelMs}ms)"
            )

            assertTrue(
                avgPaintMs < 2.5,
                "Expected Asset Panel full paint avg < 2.5ms for native responsiveness, got ${avgPaintMs}ms (p95=${p95PaintMs}ms)"
            )
            assertTrue(
                avgHoverMs < 1.0,
                "Expected Asset Panel mouse hover avg < 1.0ms for native responsiveness, got ${avgHoverMs}ms (p95=${p95HoverMs}ms)"
            )
            assertTrue(
                avgDragMs < 2.5,
                "Expected Asset Panel scrollbar drag step avg < 2.5ms for native responsiveness, got ${avgDragMs}ms (p95=${p95DragMs}ms)"
            )
            assertTrue(
                avgWheelMs < 2.5,
                "Expected Asset Panel wheel scroll step avg < 2.5ms for native responsiveness, got ${avgWheelMs}ms (p95=${p95WheelMs}ms)"
            )
        } finally {
            Win32NativeWindowHost.closeAllVisualWindows()
            Win32NativeWindowHost.visualTestMode = prevVisualTest
            WindowsApplicationNativeImpl.interactiveWin32GuiEnabled = prevInteractive
        }
    }

    @Test
    fun testDialogWindowCreatesSeparateAttachedNativeWindowInsteadOfOverlay() {
        val prevInteractive = WindowsApplicationNativeImpl.interactiveWin32GuiEnabled
        WindowsApplicationNativeImpl.interactiveWin32GuiEnabled = false
        try {
            val factory = WindowsNativeFactory()
            GenericUI.initialize(factory)

            val mainWindow = Window(title = "Main Studio")
            mainWindow.setContent(Container("div", "w-100 h-100"))
            mainWindow.show(factory)
            val mainImpl = mainWindow.ensureNativeImpl() as WindowsWindowNativeImpl

            val dialog = DialogWindow(title = "Preferences", modal = true)
            dialog.setWindowSize(com.nexustools.genericui.geometry.Size(480, 320))
            dialog.open(mainWindow)

            assertTrue(dialog.isWindowShown)
            assertTrue(mainWindow.attachedDialogWindows.contains(dialog))
            assertTrue(mainWindow.childWindows.contains(dialog))

            @Suppress("UNCHECKED_CAST")
            val dialogImpl = dialog.ensureNativeImpl() as DesktopDialogWindowNativeImpl<Win32HwndHandle>
            assertEquals(true, dialogImpl.nativeWidget.attributes["isSeparateNativeWindow"])
            assertEquals(false, dialogImpl.nativeWidget.attributes["inWindowOverlay"])
            assertEquals(true, dialogImpl.nativeWidget.attributes["attachedToMainWindow"])
            assertTrue((dialogImpl.nativeWidget.dwExStyle and Win32HwndHandle.WS_EX_DLGMODALFRAME) != 0L)

            mainWindow.bringToFront()
            assertTrue(dialogImpl.zOrderLayer > mainImpl.zOrderLayer)

            dialog.close()
            assertFalse(dialog.isWindowShown)
            assertFalse(mainWindow.attachedDialogWindows.contains(dialog))
        } finally {
            WindowsApplicationNativeImpl.interactiveWin32GuiEnabled = prevInteractive
        }
    }

    @Test
    fun testWin32DialogBackdropDismissPopupMouseCaptureAndTextInputFocusTracking() {
        val prevInteractive = WindowsApplicationNativeImpl.interactiveWin32GuiEnabled
        WindowsApplicationNativeImpl.interactiveWin32GuiEnabled = false
        com.nexustools.genericui.event.PopupMouseCaptureManager.clear()
        com.nexustools.genericui.event.InputFocusManager.reset()
        try {
            val factory = WindowsNativeFactory()
            GenericUI.initialize(factory)
            GenericUI.activeFactory = factory

            val mainWindow = Window(title = "Main Window")
            val root = Container("div", "w-100 h-100")

            var underlyingClicks = 0
            val underlyingButton = Button("Play").apply {
                onClick = { underlyingClicks++ }
            }

            var popupChildClicks = 0
            val popupPanel = Container("div", "settings-panel").apply {
                hookMouseWhenPopupVisible = true
                isVisible = false
            }
            val popupChildButton = Button("1080p").apply {
                onClick = { popupChildClicks++ }
            }
            popupPanel.addChild(popupChildButton)

            val searchInput = com.nexustools.genericui.component.form.TextField(placeholder = "Search")
            root.addChild(underlyingButton)
            root.addChild(popupPanel)
            root.addChild(searchInput)
            mainWindow.addChild(root)
            mainWindow.setContent(root)
            mainWindow.show(factory)

            val rootImpl = root.ensureNativeImpl() as DesktopBaseNativeImpl<*>
            val underlyingImpl = underlyingButton.ensureNativeImpl() as DesktopBaseNativeImpl<*>
            val searchImpl = searchInput.ensureNativeImpl() as com.nexustools.genericui.desktop.widget.DesktopTextInputNativeImpl<*>

            // 1. Popup Mouse Capture & Outside-Click Swallow
            popupPanel.isVisible = true
            assertTrue(com.nexustools.genericui.event.PopupMouseCaptureManager.hasActivePopup)
            val popupChildImpl = popupChildButton.ensureNativeImpl() as DesktopBaseNativeImpl<*>

            // Clicking a child button inside the popup executes its click handler and keeps the popup open
            popupChildImpl.dispatchClick()
            assertEquals(1, popupChildClicks)
            assertTrue(popupPanel.isVisible)

            // Clicking the underlying button outside the popup swallows the click and dismisses the popup
            val outsidePt = com.nexustools.genericui.event.PointerEvent(x = 200f, y = 200f)
            underlyingImpl.dispatchPointerDown(outsidePt)
            underlyingImpl.dispatchPointerUp(outsidePt)
            underlyingImpl.dispatchClick()
            assertEquals(0, underlyingClicks)
            assertFalse(popupPanel.isVisible)
            assertFalse(com.nexustools.genericui.event.PopupMouseCaptureManager.hasActivePopup)

            // Subsequent click on the underlying button now fires normally
            underlyingImpl.dispatchClick()
            assertEquals(1, underlyingClicks)

            // 2. Modal Dialog Backdrop Dismiss
            val dialog = DialogWindow(title = "Details", modal = true)
            dialog.open(mainWindow)
            assertTrue(dialog.isWindowShown)

            @Suppress("UNCHECKED_CAST")
            val dialogImpl = dialog.ensureNativeImpl() as DesktopDialogWindowNativeImpl<Win32HwndHandle>
            assertTrue(dialogImpl.isBackdropDismissAllowed())
            dialogImpl.dismissOnBackdropClick()
            assertFalse(dialog.isWindowShown)

            // 3. TextInput Focus & Window Shortcut Suppression
            var shortcutHits = 0
            root.onKeyDown = { shortcutHits++ }

            searchImpl.setFocusedState(true)
            assertTrue(com.nexustools.genericui.event.InputFocusManager.isTypingInTextInput)
            rootImpl.dispatchKeyDown(com.nexustools.genericui.event.KeyEvent(key = " ", code = "Space"))
            assertEquals(0, shortcutHits)

            searchImpl.setFocusedState(false)
            assertFalse(com.nexustools.genericui.event.InputFocusManager.isTypingInTextInput)
            rootImpl.dispatchKeyDown(com.nexustools.genericui.event.KeyEvent(key = " ", code = "Space"))
            assertEquals(1, shortcutHits)
        } finally {
            com.nexustools.genericui.event.PopupMouseCaptureManager.clear()
            com.nexustools.genericui.event.InputFocusManager.reset()
            WindowsApplicationNativeImpl.interactiveWin32GuiEnabled = prevInteractive
        }
    }

    @Test
    fun testDwmExtendTopMarginCollapsesToOnePixelOnSolidWin10And11TitleBars() {
        val win11Palette = Win32DwmUxThemeBridge.resolveTitleBarPalette(
            hwnd = null,
            generation = Win32VisualStyleGeneration.WIN11_MICA_FLUENT,
            isDark = true,
            colorPrevalence = false
        )
        assertFalse(win11Palette.useTransparentDwmBackdrop)
        assertFalse(win11Palette.dwmDrawsCaptionButtons)
        assertEquals(1, Win32DwmUxThemeBridge.resolveEffectiveExtendTopMarginPx(40, win11Palette))
        assertEquals(0, Win32DwmUxThemeBridge.resolveEffectiveExtendTopMarginPx(0, win11Palette))

        val win10Palette = Win32DwmUxThemeBridge.resolveTitleBarPalette(
            hwnd = null,
            generation = Win32VisualStyleGeneration.WIN10_FLUENT,
            isDark = false,
            colorPrevalence = false
        )
        assertEquals(1, Win32DwmUxThemeBridge.resolveEffectiveExtendTopMarginPx(40, win10Palette))

        val aeroGlassPalette = Win32DwmUxThemeBridge.resolveTitleBarPalette(
            hwnd = null,
            generation = Win32VisualStyleGeneration.VISTA_7_AERO_GLASS,
            isDark = false,
            colorPrevalence = false
        )
        assertTrue(aeroGlassPalette.useTransparentDwmBackdrop)
        assertTrue(aeroGlassPalette.dwmDrawsCaptionButtons)
        assertEquals(40, Win32DwmUxThemeBridge.resolveEffectiveExtendTopMarginPx(40, aeroGlassPalette))
    }

    @Test
    fun testCustomCaptionButtonsEatNcHoverAndDragOffCancelOnLiveHwnd() {
        val prevInteractive = WindowsApplicationNativeImpl.interactiveWin32GuiEnabled
        val prevVisualTest = Win32NativeWindowHost.visualTestMode
        WindowsApplicationNativeImpl.interactiveWin32GuiEnabled = true
        Win32NativeWindowHost.visualTestMode = true
        try {
            val factory = WindowsNativeFactory()
            GenericUI.initialize(factory)

            val mainWindow = Window(title = "NC Caption Test")
            mainWindow.setWindowSize(com.nexustools.genericui.geometry.Size(480, 320))
            mainWindow.setTitleBar(
                TitleBar(
                    customTitleBar = true,
                    showWindowIcon = false,
                    showMinimizeButton = true,
                    showMaximizeButton = true,
                    showCloseButton = true,
                    heightPx = 40
                )
            )
            mainWindow.setContent(Container("div", "w-100 h-100"))
            mainWindow.show(factory)
            Win32NativeWindowHost.pumpVisualWindowEvents(24L)

            val mainImpl = mainWindow.ensureNativeImpl() as WindowsWindowNativeImpl
            val hwnd = assertNotNull(Win32NativeWindowHost.resolveHwndForWindow(mainImpl))
            assertTrue(IsWindow(hwnd) != 0)
            assertEquals(0, Win32ControlMessageRouter.batchingHostLayoutDepth)

            // Icon disabled -> icon hit-test must yield HTCAPTION instead of HTSYSMENU
            assertEquals(
                HTCAPTION,
                Win32NativeTitleBarRenderer.hitTestLocalCoordinates(
                    cx = 16,
                    cy = 16,
                    windowWidth = 480,
                    titleBarHeight = 40,
                    showWindowIcon = false
                )
            )
            assertFalse(Win32NativeTitleBarRenderer.isPointInCaptionButtonOrIcon(hwnd, 16, 16))
            assertTrue(Win32NativeTitleBarRenderer.isPointInCaptionButtonOrIcon(hwnd, 468, 16))

            val dwmButtons = Win32NativeTitleBarRenderer.doesDwmDrawCaptionButtons(hwnd)
            if (!dwmButtons) {
                val moveRes = Win32NativeWindowHost.handleHostWndProc(hwnd, WM_NCMOUSEMOVE.toUInt(), HTCLOSE.toULong(), 0L)
                assertEquals(0L, moveRes, "WM_NCMOUSEMOVE over custom close button must return 0L to prevent DefWindowProcW ghost buttons")
                assertEquals(HTCLOSE, Win32NativeTitleBarRenderer.getHoveredNcButton(hwnd))

                val leaveRes = Win32NativeWindowHost.handleHostWndProc(hwnd, WM_NCMOUSELEAVE.toUInt(), 0uL, 0L)
                assertEquals(0L, leaveRes, "WM_NCMOUSELEAVE must return 0L when custom caption buttons are active")
                assertEquals(0, Win32NativeTitleBarRenderer.getHoveredNcButton(hwnd))
            }

            // Press close, drag off to HTCAPTION, release -> must cancel safely without closing window
            val downRes = Win32NativeWindowHost.handleHostWndProc(hwnd, WM_NCLBUTTONDOWN.toUInt(), HTCLOSE.toULong(), 0L)
            assertEquals(0L, downRes)
            assertEquals(HTCLOSE, Win32NativeTitleBarRenderer.getPressedNcButton(hwnd))

            val dragOffUpRes = Win32NativeWindowHost.handleHostWndProc(hwnd, WM_NCLBUTTONUP.toUInt(), HTCAPTION.toULong(), 0L)
            assertEquals(0L, dragOffUpRes, "Dragged-off WM_NCLBUTTONUP must consume message cleanly without closing")
            assertEquals(0, Win32NativeTitleBarRenderer.getPressedNcButton(hwnd))
            assertTrue(IsWindow(hwnd) != 0 && IsWindowVisible(hwnd) != 0)
        } finally {
            Win32NativeWindowHost.closeAllVisualWindows()
            Win32NativeWindowHost.visualTestMode = prevVisualTest
            WindowsApplicationNativeImpl.interactiveWin32GuiEnabled = prevInteractive
        }
    }
}
