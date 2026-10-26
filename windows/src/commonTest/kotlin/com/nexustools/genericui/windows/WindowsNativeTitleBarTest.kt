@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.component.action.Button
import com.nexustools.genericui.component.dock.PanelWindow
import com.nexustools.genericui.component.layout.Container
import com.nexustools.genericui.component.layout.ScrollView
import com.nexustools.genericui.component.shell.Window
import com.nexustools.genericui.component.surface.Card
import com.nexustools.genericui.component.typography.Heading
import com.nexustools.genericui.component.typography.Text
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopScrollViewNativeImpl
import com.nexustools.genericui.window.TitleBar
import platform.windows.HTCAPTION
import platform.windows.HTCLIENT
import platform.windows.HTCLOSE
import platform.windows.HTMAXBUTTON
import platform.windows.HTMINBUTTON
import platform.windows.HTSYSMENU
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
            val maxScroll = Win32NativeWindowHost.getCardPageMaxScrollY()
            assertTrue(maxScroll > 0, "Expected positive maxScrollY for 45 cards, got $maxScroll")
            assertEquals(0, Win32NativeWindowHost.getCardPageScrollY())

            val thumbBounds = Win32NativeWindowHost.getCardPageScrollThumbBounds()
            val thumbCenterX = (thumbBounds[0] + thumbBounds[2]) / 2
            val thumbCenterY = (thumbBounds[1] + thumbBounds[3]) / 2
            assertTrue(thumbBounds[2] > thumbBounds[0] && thumbBounds[3] > thumbBounds[1])

            // 1. Hover over the scrollbar thumb -> hover state becomes active
            Win32NativeWindowHost.handleMouseMove(hwnd, thumbCenterX, thumbCenterY)
            assertTrue(Win32NativeWindowHost.isCardScrollbarThumbHovered())

            // 2. Click and drag the scrollbar thumb downward -> scrollY increases and syncs to ScrollView
            Win32NativeWindowHost.handleMouseDown(hwnd, thumbCenterX, thumbCenterY)
            assertTrue(Win32NativeWindowHost.isCardScrollbarThumbDragging())
            Win32NativeWindowHost.handleMouseMove(hwnd, thumbCenterX, thumbCenterY + 120)
            val draggedScrollY = Win32NativeWindowHost.getCardPageScrollY()
            assertTrue(draggedScrollY > 0, "Expected thumb drag to increase scrollY, got $draggedScrollY")
            val scrollViewImpl = scrollView.ensureNativeImpl() as DesktopScrollViewNativeImpl<*>
            assertEquals(draggedScrollY, scrollViewImpl.currentScrollY())
            Win32NativeWindowHost.handleMouseUp(hwnd, thumbCenterX, thumbCenterY + 120)
            assertFalse(Win32NativeWindowHost.isCardScrollbarThumbDragging())

            // 3. Mouse wheel down -> scrollY increases further
            Win32NativeWindowHost.handleMouseWheel(hwnd, -120)
            val afterWheelScrollY = Win32NativeWindowHost.getCardPageScrollY()
            assertTrue(afterWheelScrollY > draggedScrollY)

            // 4. Programmatic scrollView.scrollTo(0, 0) -> resets scrollY to 0 on repaint
            scrollView.scrollTo(0, 0)
            Win32NativeWindowHost.paintWindow(hwnd)
            assertEquals(0, Win32NativeWindowHost.getCardPageScrollY())
        } finally {
            Win32NativeWindowHost.closeAllVisualWindows()
            Win32NativeWindowHost.visualTestMode = prevVisualTest
            WindowsApplicationNativeImpl.interactiveWin32GuiEnabled = prevInteractive
        }
    }
}
