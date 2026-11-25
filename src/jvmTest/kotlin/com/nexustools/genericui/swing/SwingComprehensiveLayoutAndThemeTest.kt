package com.nexustools.genericui.swing

import com.nexustools.genericui.component.layout.LayeredContainer
import com.nexustools.genericui.component.surface.Card
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.enums.bootstrap.AlignItems
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.bootstrap.DisplayMode
import com.nexustools.genericui.enums.bootstrap.FlexDirection
import com.nexustools.genericui.enums.css.CssPosition
import com.nexustools.genericui.enums.dock.DockPosition
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.geometry.EdgeInsets
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.CssLength
import java.awt.Color
import java.awt.Rectangle
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.UIManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SwingComprehensiveLayoutAndThemeTest {

    @Test
    fun eagerSystemLafInitializesAppearanceDetectsOsThemesAndPreservesButtonDefaults() {
        val factory = SwingNativeFactory()
        assertNotNull(factory)

        if (SwingApplicationImpl.isMacOs()) {
            assertEquals("system", System.getProperty("apple.awt.application.appearance"))
        }

        assertEquals(
            "com.sun.java.swing.plaf.gtk.GTKLookAndFeel",
            SwingApplicationImpl.resolvePreferredSystemLafClassName(
                osName = "Linux",
                systemLafClassName = "javax.swing.plaf.metal.MetalLookAndFeel",
                gtkAvailable = true
            )
        )
        assertEquals(ThemeMode.DARK, SwingApplicationImpl.parseMacOsInterfaceStyleOutput("Dark\n"))
        assertEquals(ThemeMode.LIGHT, SwingApplicationImpl.parseMacOsInterfaceStyleOutput(null))
        assertEquals(
            ThemeMode.DARK,
            SwingApplicationImpl.parseWindowsAppsUseLightThemeOutput("    AppsUseLightTheme    REG_DWORD    0x0")
        )
        assertEquals(
            ThemeMode.LIGHT,
            SwingApplicationImpl.parseWindowsAppsUseLightThemeOutput("    AppsUseLightTheme    REG_DWORD    0x1")
        )
        assertEquals(ThemeMode.DARK, SwingApplicationImpl.parseGsettingsColorScheme("'prefer-dark'"))
        assertEquals(ThemeMode.DARK, SwingApplicationImpl.parseKdeGlobalsTheme("[General]\nColorScheme=BreezeDark\n"))

        val pristineButtonBg = UIManager.getLookAndFeelDefaults()["Button.background"] ?: UIManager.get("Button.background")
        SwingApplicationImpl.applySwingThemeDefaults(isDark = true)
        val darkButtonBg = UIManager.get("Button.background")
        val darkPanelBg = UIManager.getColor("Panel.background")
        assertEquals(pristineButtonBg, darkButtonBg, "Native Button.background should be preserved in dark mode")
        assertTrue(darkPanelBg.red < 80 && darkPanelBg.green < 80 && darkPanelBg.blue < 80)

        SwingApplicationImpl.applySwingThemeDefaults(isDark = false)
        assertEquals(pristineButtonBg, UIManager.get("Button.background"), "Native Button.background should be preserved in light mode")
        val lightLabelFg = UIManager.getColor("Label.foreground")
        assertTrue(lightLabelFg.red < 80, "Light theme Label.foreground should have dark contrasting ink")
    }

    @Test
    fun flexboxAbsoluteAndSingleAxisZeroCollapsePreventionWorkCorrectly() {
        val row = SwingContainerImpl("container").apply {
            setDisplay(DisplayMode.FLEX)
            setFlexDirection(FlexDirection.ROW)
            setAlignItems(AlignItems.CENTER)
            setPadding(EdgeInsets(8, 8, 8, 8))
            setAttribute("gapPx", "10")
        }

        val leftBtn = SwingButtonImpl().apply {
            setText("Left")
            setPreferredSize(Size(100, 0)) // single-axis fixed width, auto height -> must NOT collapse height to 0
        }
        val spacer = SwingContainerImpl("spacer").apply {
            setAttribute("spacerFill", "true")
            setAttribute("spacerWeight", "1")
        }
        val rightLbl = SwingTextImpl("span", "").apply {
            setText("Right")
            setPreferredSize(Size(0, 34)) // single-axis fixed height, auto width -> must NOT collapse width to 0
        }

        row.addChildNative(leftBtn, -1)
        row.addChildNative(spacer, -1)
        row.addChildNative(rightLbl, -1)

        row.nativeWidget.setBounds(0, 0, 420, 100)
        SwingFlexLayoutManager.layoutTreeNow(row.nativeWidget)

        assertFalse(row.nativeWidget.isOpaque, "Plain layout JPanel must stay non-opaque for compositing")
        assertEquals(100, leftBtn.nativeWidget.width)
        assertTrue(leftBtn.nativeWidget.height > 0, "Single-axis explicit width must not collapse height to 0")
        assertEquals(34, rightLbl.nativeWidget.height)
        assertTrue(rightLbl.nativeWidget.width > 0, "Single-axis explicit height must not collapse width to 0")
        assertEquals(8, leftBtn.nativeWidget.x)
        assertTrue(spacer.nativeWidget.width > 100, "Spacer should expand to consume remaining flex width")
        assertEquals(420 - 8 - rightLbl.nativeWidget.width, rightLbl.nativeWidget.x)

        // Absolute positioning & z-index ordering
        val relHost = SwingContainerImpl("mdi-desktop-surface", "genericui:mdidesktop").apply {
            setPosition(com.nexustools.genericui.enums.bootstrap.PositionMode.RELATIVE)
        }
        val lowerBox = SwingContainerImpl("mdi-window", "genericui:mdiwindow").apply {
            applyComponentStyle(
                ComponentStyle().apply {
                    position = CssPosition.ABSOLUTE
                    left = CssLength.px(20)
                    top = CssLength.px(24)
                    width = CssLength.px(180)
                    height = CssLength.px(120)
                    zIndex = 10
                }
            )
        }
        val upperBox = SwingContainerImpl("mdi-window", "genericui:mdiwindow").apply {
            applyComponentStyle(
                ComponentStyle().apply {
                    position = CssPosition.ABSOLUTE
                    left = CssLength.px(60)
                    top = CssLength.px(50)
                    width = CssLength.px(200)
                    height = CssLength.px(140)
                    zIndex = 20
                }
            )
        }
        relHost.addChildNative(lowerBox, -1)
        relHost.addChildNative(upperBox, -1)
        relHost.nativeWidget.setBounds(0, 0, 500, 320)
        SwingFlexLayoutManager.layoutTreeNow(relHost.nativeWidget)

        assertEquals(Rectangle(20, 24, 180, 120), lowerBox.nativeWidget.bounds)
        assertEquals(Rectangle(60, 50, 200, 140), upperBox.nativeWidget.bounds)
        assertSame(upperBox.nativeWidget, relHost.nativeWidget.getComponent(0), "Higher zIndex child must be at Swing index 0 (painted on top)")
    }

    @Test
    fun layeredContainerStacksFillAndFloatingLayersOverFullBoundsInZOrder() {
        val factory = SwingNativeFactory()
        val layered = SwingContainerImpl("gui-layered-container", "genericui:layered-container")

        val baseCanvas = SwingContainerImpl("canvas-layer").apply {
            setAttribute("layerSizingMode", "FILL")
            setAttribute("layerZIndex", "0")
        }
        val hud = SwingContainerImpl("hud-layer").apply {
            addClass("gui-layer-float")
            setAttribute("layerSizingMode", "FLOAT")
            setAttribute("layerAlignment", "BOTTOM_END")
            setAttribute("layerOffsetXPx", "-14")
            setAttribute("layerOffsetYPx", "-10")
            setAttribute("layerZIndex", "5")
            setPreferredSize(Size(120, 36))
        }
        layered.addChildNative(hud, -1)
        layered.addChildNative(baseCanvas, -1)

        layered.nativeWidget.setBounds(0, 0, 480, 300)
        SwingFlexLayoutManager.layoutTreeNow(layered.nativeWidget)

        assertEquals(Rectangle(0, 0, 480, 300), baseCanvas.nativeWidget.bounds)
        assertEquals(Rectangle(480 - 120 - 14, 300 - 36 - 10, 120, 36), hud.nativeWidget.bounds)
        assertSame(hud.nativeWidget, layered.nativeWidget.getComponent(0), "Higher layerZIndex must be painted on top at Swing index 0")

        val prevFactory = GenericUI.activeFactory
        GenericUI.initialize(factory)
        val testWindow = com.nexustools.genericui.component.shell.Window("LayeredContainerTest").apply {
            setWindowSize(com.nexustools.genericui.geometry.Size(400, 240))
        }
        var winImplRef: SwingWindowImpl? = null
        try {
            val componentLayered = LayeredContainer()
            componentLayered.widthPx = 400
            componentLayered.heightPx = 240
            val card = Card("Summary Card")
            componentLayered.addChild(card)
            testWindow.setContent(componentLayered)
            testWindow.show(factory)
            val winImpl = testWindow.ensureNativeImpl() as SwingWindowImpl
            winImplRef = winImpl
            val layeredComp = componentLayered.ensureNativeImpl() as SwingContainerImpl
            val cardComp = card.ensureNativeImpl() as SwingContainerImpl
            assertNotNull(layeredComp)
            assertNotNull(cardComp)
            assertEquals(400, layeredComp.nativeWidget.width)
            assertEquals(240, layeredComp.nativeWidget.height)
            assertEquals(400, cardComp.nativeWidget.width)
            assertEquals(240, cardComp.nativeWidget.height)

            val offscreen = BufferedImage(400, 240, BufferedImage.TYPE_INT_ARGB)
            val g2 = offscreen.createGraphics()
            try {
                g2.color = Color.MAGENTA
                g2.fillRect(0, 0, 400, 240)
                winImpl.nativeWidget.paint(g2)
            } finally {
                g2.dispose()
            }
            val uniqueColors = HashSet<Int>()
            for (y in 0 until 240 step 2) {
                for (x in 0 until 400 step 2) {
                    uniqueColors.add(offscreen.getRGB(x, y))
                }
            }
            assertTrue(uniqueColors.size > 1, "LayeredContainerTest window must render non-blank card/heading pixels, distinctRgb=${uniqueColors.size}")
        } finally {
            testWindow.close()
            testWindow.dispose()
            assertFalse(winImplRef?.jFrame?.isDisplayable == true, "Disposed SwingWindowImpl JFrame must no longer be displayable")
            GenericUI.activeFactory = prevFactory
        }
    }

    @Test
    fun tableWrapsJTableInsideJScrollPaneWithHeaderAndColumnWeights() {
        val tableImpl = SwingTableImpl()
        tableImpl.setHeaders(listOf("ID", "Description", "Status"))
        tableImpl.setRows(
            listOf(
                listOf("1", "Render Pipeline", "OK"),
                listOf("2", "System Theme", "OK")
            )
        )
        tableImpl.setColumnWeights(floatArrayOf(1f, 4f, 2f))
        tableImpl.setStriped(true)
        tableImpl.setHoverable(true)

        assertTrue(tableImpl.nativeWidget is JScrollPane)
        assertSame(tableImpl.table, tableImpl.nativeWidget.viewport.view)
        assertNotNull(tableImpl.table.tableHeader)

        tableImpl.nativeWidget.setBounds(0, 0, 490, 220)
        SwingFlexLayoutManager.layoutTreeNow(tableImpl.nativeWidget)
        val w0 = tableImpl.table.columnModel.getColumn(0).preferredWidth
        val w1 = tableImpl.table.columnModel.getColumn(1).preferredWidth
        assertTrue(w1 > w0, "Weighted column width ($w1) should exceed narrow ID column ($w0)")
    }

    @Test
    fun headlessWindowLayoutPassScrollDialogDockAndOffscreenCaptureRenderNonZeroWidgets() {
        val win = SwingWindowImpl()
        try {
            win.setWindowSize(Size(520, 380))

            val rootVStack = SwingContainerImpl("vstack").apply {
                setDisplay(DisplayMode.FLEX)
                setFlexDirection(FlexDirection.COLUMN)
                setPadding(EdgeInsets(16, 16, 16, 16))
                setAttribute("gapPx", "10")
            }
            val heading = SwingTextImpl("h2", "h2").apply {
                setText("Section Overview")
            }
            val bodyParagraph = SwingTextImpl("p", "").apply {
                setText("This is a very long multi-line descriptive paragraph that should wrap cleanly inside the card width without clipping when laid out in headless mode.")
            }
            val badge = SwingTextImpl("span", "badge rounded-pill").apply {
                setType(BootstrapType.PRIMARY)
                setText("NEW")
            }
            val card = SwingContainerImpl("card", "genericui:card").apply {
                addChildNative(heading, -1)
                addChildNative(bodyParagraph, -1)
                addChildNative(badge, -1)
            }
            val alert = SwingContainerImpl("alert", "genericui:alert").apply {
                setType(BootstrapType.WARNING)
                addChildNative(SwingTextImpl("p", "").apply { setText("Attention required") }, -1)
            }
            val markdown = SwingMarkdownViewImpl().apply {
                setMarkdown("# Notes\n\n- First **bold** item\n- Second `inline` item")
            }

            rootVStack.addChildNative(card, -1)
            rootVStack.addChildNative(alert, -1)
            rootVStack.addChildNative(markdown, -1)

            win.addChildNative(rootVStack, -1)
            win.showWindow()

            assertEquals(520, rootVStack.nativeWidget.width)
            assertEquals(380, rootVStack.nativeWidget.height)
            assertTrue(card.nativeWidget.width > 400 && card.nativeWidget.height > 60)
            assertTrue(alert.nativeWidget.height >= 30)
            assertTrue(heading.nativeWidget.font.size2D > bodyParagraph.nativeWidget.font.size2D)
            assertTrue(bodyParagraph.nativeWidget.height > 28, "Long wrapped paragraph should expand across multiple lines, got h=${bodyParagraph.nativeWidget.height}")
            val markdownHtml = markdown.nativeWidget.getClientProperty("renderedHtml") as? String ?: markdown.nativeWidget.text
            assertTrue(markdownHtml.contains("<h1>Notes</h1>") && markdownHtml.contains("<b>bold</b>"))

            val offscreen = BufferedImage(520, 380, BufferedImage.TYPE_INT_ARGB)
            val g2 = offscreen.createGraphics()
            try {
                win.nativeWidget.paint(g2)
            } finally {
                g2.dispose()
            }
            val bgRgb = offscreen.getRGB(4, 4)
            val alertRgb = offscreen.getRGB(alert.nativeWidget.x + 12, alert.nativeWidget.y + 12)
            assertTrue(bgRgb != alertRgb, "Rendered offscreen window image must distinguish Alert surface chrome from window background")
        } finally {
            win.closeWindow()
            win.dispose()
        }

        // Verify DockWorkspace proportional split & non-collapsed area
        val ws = SwingDockWorkspaceImpl()
        val area = SwingDockAreaImpl().apply { setPosition(DockPosition.LEFT) }
        val center = SwingContainerImpl("center")
        ws.attachDockAreaNative(DockPosition.LEFT, area)
        ws.setCentralDocumentNative(center)
        ws.nativeWidget.setBounds(0, 0, 800, 500)
        SwingFlexLayoutManager.layoutTreeNow(ws.nativeWidget)
        val split = ws.nativeWidget.getComponent(0) as JSplitPane
        assertTrue(split.dividerLocation > 80, "DockWorkspace split should reserve non-zero proportional sidebar width")

        // Verify DialogWindow supports multiple stacked children cleanly
        val dlg = SwingDialogWindowImpl()
        try {
            val firstLine = SwingTextImpl("p", "").apply { setText("First line") }
            val secondLine = SwingTextImpl("p", "").apply { setText("Second line") }
            dlg.addChildNative(firstLine, -1)
            dlg.addChildNative(secondLine, -1)
            dlg.setBounds(100, 100, 400, 260)
            assertTrue(
                secondLine.nativeWidget.y > firstLine.nativeWidget.y,
                "Dialog body should stack multiple children vertically without overwriting CENTER (firstY=${firstLine.nativeWidget.y}, secondY=${secondLine.nativeWidget.y}, dlgSize=${dlg.nativeWidget.size}, bodySize=${firstLine.nativeWidget.parent?.size})"
            )
        } finally {
            dlg.dispose()
        }
    }

    @Test
    fun consumedMouseEventsDoNotTriggerNativeClickAndOverlayHostRootDescendsToTitleBar() {
        val btn = SwingButtonImpl()
        var clicks = 0
        btn.setOnClick { clicks++ }
        val now = System.currentTimeMillis()
        val consumedEvent = MouseEvent(btn.nativeWidget, MouseEvent.MOUSE_CLICKED, now, 0, 10, 10, 1, false, MouseEvent.BUTTON1).apply {
            consume()
        }
        btn.nativeWidget.dispatchEvent(consumedEvent)
        assertEquals(0, clicks, "Consumed MouseEvent must not fire onClickListener")

        val unconsumedEvent = MouseEvent(btn.nativeWidget, MouseEvent.MOUSE_CLICKED, now + 5, 0, 10, 10, 1, false, MouseEvent.BUTTON1)
        btn.nativeWidget.dispatchEvent(unconsumedEvent)
        assertEquals(1, clicks, "Unconsumed MouseEvent should fire onClickListener")

        val win = SwingWindowImpl()
        try {
            val overlayHost = SwingContainerImpl("gui-layered-container", "genericui:layered-container").apply {
                setAttribute("data-overlay-host-root", "true")
                setAttribute("data-titlebar-theme", "auto")
            }
            val overlay = SwingContainerImpl("gui-overlay").apply {
                setAttribute("data-overlay-root", "true")
            }
            val titleBar = SwingStyledContainerImpl("overlay-titlebar", "div").apply {
                setAttribute("data-titlebar-theme", "dark")
                setAttribute("id", "custom-titlebar")
            }
            val closeBtn = SwingButtonImpl().apply {
                setAttribute("data-window-control", "close")
                setAttribute("id", "titlebar-close")
            }
            titleBar.addChildNative(closeBtn, -1)
            overlay.addChildNative(titleBar, -1)
            overlayHost.addChildNative(overlay, -1)
            win.addChildNative(overlayHost, -1)

            val discovered = win.findOverlayTitleBars()
            assertEquals(1, discovered.size)
            assertSame(titleBar.nativeWidget, discovered.first())
            assertEquals("dark", titleBar.nativeWidget.getClientProperty("data-titlebar-theme"))
            assertEquals("close", closeBtn.nativeWidget.getClientProperty("data-window-control"))
            assertEquals("titlebar-close", closeBtn.nativeWidget.getClientProperty("id"))
        } finally {
            win.closeWindow()
            win.dispose()
        }
    }
}
