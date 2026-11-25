package com.nexustools.genericui.swing

import com.nexustools.genericui.component.action.DropdownButton
import com.nexustools.genericui.component.action.SplitDropdownButton
import com.nexustools.genericui.component.composite.Composite
import com.nexustools.genericui.component.feedback.Alert
import com.nexustools.genericui.component.feedback.ProgressBar
import com.nexustools.genericui.component.form.StyledSlider
import com.nexustools.genericui.component.layout.VStack
import com.nexustools.genericui.component.shell.Window
import com.nexustools.genericui.component.surface.Card
import com.nexustools.genericui.component.surface.StyledComponent
import com.nexustools.genericui.component.typography.Badge
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.enums.bootstrap.BootstrapPointerEvents
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.bootstrap.FontWeight
import com.nexustools.genericui.enums.bootstrap.PositionMode
import com.nexustools.genericui.enums.bootstrap.TextAlignment
import com.nexustools.genericui.enums.bootstrap.TextTransform
import com.nexustools.genericui.enums.css.CssCursor
import com.nexustools.genericui.enums.css.CssFontStyle
import com.nexustools.genericui.enums.css.CssPosition
import com.nexustools.genericui.enums.css.CssTextDecoration
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.CssCornerRadii
import com.nexustools.genericui.style.css.CssEdgeLengths
import com.nexustools.genericui.style.css.CssFontFamily
import com.nexustools.genericui.style.css.CssLength
import com.nexustools.genericui.style.css.StyledVisualSpec
import com.nexustools.genericui.style.transition.CssTransform2D
import com.nexustools.genericui.style.theme.ThemeColorSet
import com.nexustools.genericui.style.theme.ThemeConfig
import java.awt.Dimension
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwingSystemThemeAndControlTintTest {

    @AfterTest
    fun resetThemeConfig() {
        com.nexustools.genericui.component.shell.Application.current?.setThemeConfig(ThemeConfig.DEFAULT)
        GenericUI.defaultThemeConfig = ThemeConfig.DEFAULT
    }

    @Test
    fun buttonRendersNativeWhenBootstrapTypeIsNoneAndTintedOffscreenSpriteWhenTyped() {
        com.nexustools.genericui.component.shell.Application.current?.dispose()
        val btn = SwingButtonImpl()
        btn.setText("Save")
        btn.setType(BootstrapType.NONE)
        assertEquals(BootstrapType.NONE, btn.nativeWidget.getClientProperty("bootstrapType"))
        assertTrue(btn.nativeWidget.isContentAreaFilled)
        assertTrue(btn.nativeWidget.isBorderPainted)

        val customPrimary = RgbaColor(210, 45, 95, 255)
        val app = SwingApplicationImpl()
        val customTheme = ThemeConfig(
            lightColors = ThemeColorSet(primary = customPrimary),
            darkColors = ThemeColorSet(primary = customPrimary)
        )
        app.setThemeConfig(customTheme)
        assertEquals(customTheme, app.getThemeConfig())

        btn.setType(BootstrapType.PRIMARY)
        assertEquals(BootstrapType.PRIMARY, btn.nativeWidget.getClientProperty("bootstrapType"))

        val sprite = btn.renderOffscreenButtonSprite(width = 120, height = 36, includeTextOverlay = false)
        assertEquals(120, sprite.width)
        assertEquals(36, sprite.height)

        val centerArgb = sprite.getRGB(60, 18)
        val r = (centerArgb ushr 16) and 0xFF
        val b = centerArgb and 0xFF
        assertTrue(r > 140 && b > 40, "Offscreen tinted button sprite should reflect custom ThemeConfig primary tint (r=$r, b=$b)")
    }

    @Test
    fun standardControlsIgnoreAestheticAndTypographyComponentStyleOverridesWhileStyledHonorsThem() {
        val style = ComponentStyle().apply {
            setBackgroundColor(RgbaColor(255, 0, 0, 255))
            setTextColor(RgbaColor(0, 255, 0, 255))
            setBorderColor(RgbaColor(0, 0, 255, 255))
            setBorderWidth(CssEdgeLengths.all(CssLength.px(4f)))
            setBorderRadius(CssCornerRadii.px(12f))
            setFontFamily(CssFontFamily.named("Monospace"))
            setFontSizePx(24f)
            setFontWeight(FontWeight.BOLD)
            setFontStyle(CssFontStyle.ITALIC)
        }

        val stdBtn = SwingButtonImpl()
        stdBtn.applyComponentStyle(style)
        assertNull(stdBtn.nativeWidget.getClientProperty("backgroundColor"))
        assertNull(stdBtn.nativeWidget.getClientProperty("borderRadiusPx"))
        assertNull(stdBtn.nativeWidget.getClientProperty("fontSizePx"))

        val stdContainer = SwingContainerImpl()
        stdContainer.applyComponentStyle(style)
        assertNull(stdContainer.nativeWidget.getClientProperty("backgroundColor"))
        assertNull(stdContainer.nativeWidget.getClientProperty("borderRadiusPx"))

        val styledBtn = SwingStyledButtonImpl()
        styledBtn.applyComponentStyle(style)
        assertEquals(RgbaColor(255, 0, 0, 255), styledBtn.nativeWidget.getClientProperty("backgroundColor"))
        assertEquals(12f, styledBtn.nativeWidget.getClientProperty("borderRadiusPx"))

        val styledContainer = SwingStyledContainerImpl()
        styledContainer.applyComponentStyle(style)
        assertEquals(RgbaColor(255, 0, 0, 255), styledContainer.nativeWidget.getClientProperty("backgroundColor"))
        assertEquals(12f, styledContainer.nativeWidget.getClientProperty("borderRadiusPx"))
    }

    @Test
    fun textAndMarkdownViewHonorTypographyAndBootstrapStyles() {
        val text = SwingTextImpl()
        text.setText("hello world")
        val typoStyle = ComponentStyle().apply {
            setTextColor(RgbaColor(20, 120, 220, 255))
            setFontFamily(CssFontFamily.named("Serif"))
            setFontSizePx(20f)
            setFontWeight(FontWeight.BOLD)
            setFontStyle(CssFontStyle.ITALIC)
            setTextAlign(TextAlignment.CENTER)
            setTextDecoration(CssTextDecoration.UNDERLINE)
            setTextTransform(TextTransform.UPPERCASE)
            setLetterSpacing(CssLength.px(2f))
            setLineHeight(CssLength.px(24f))
        }
        text.applyComponentStyle(typoStyle)
        assertEquals("HELLO WORLD", text.getText())
        assertEquals(20, text.nativeWidget.foreground.red)
        assertEquals(120, text.nativeWidget.foreground.green)
        assertEquals(220, text.nativeWidget.foreground.blue)
        assertEquals("20.0", text.nativeWidget.getClientProperty("fontSizePx")?.toString())

        val md = SwingMarkdownViewImpl()
        md.setMarkdown("markdown title")
        md.applyComponentStyle(typoStyle)
        md.applyComponentBootstrap(ComponentBootstrap().apply { setTextAlignment(TextAlignment.CENTER) })
        assertTrue(md.nativeWidget.text.contains("MARKDOWN TITLE"))
        assertEquals(20, md.nativeWidget.font.size)
        val renderedHtml = md.nativeWidget.getClientProperty("renderedHtml")?.toString().orEmpty()
        assertTrue(renderedHtml.contains("font-size: 20px"))
        assertTrue(renderedHtml.contains("text-align: center"))
    }

    @Test
    fun tableColumnWeightsAndLazyGridMinColumnWidthAreHonored() {
        val table = SwingTableImpl()
        table.setHeaders(listOf("ID", "Name", "Status"))
        table.setColumnWeights(floatArrayOf(1f, 3f, 2f))
        assertContentEquals(floatArrayOf(1f, 3f, 2f), table.getColumnWeights())
        val col0W = table.table.columnModel.getColumn(0).preferredWidth
        val col1W = table.table.columnModel.getColumn(1).preferredWidth
        assertTrue(col1W > col0W, "Column 1 with weight 3f should have larger preferred width than column 0 with weight 1f")

        val grid = SwingLazyGridImpl()
        grid.setColumns(4)
        grid.setMinColumnWidthPx(150)
        assertEquals(150, grid.getMinColumnWidthPx())
        assertEquals(2, grid.resolveEffectiveColumns(availableWidthPx = 340))
        assertEquals(4, grid.resolveEffectiveColumns(availableWidthPx = 800))
    }

    @Test
    fun strictBootstrapTypePolicyPermitsButtonsAndNonControlsOnlyWhileStyledControlsIgnoreBootstrapType() {
        com.nexustools.genericui.component.shell.Application.current?.dispose()
        val prevFactory = GenericUI.activeFactory
        val factory = SwingNativeFactory()
        GenericUI.initialize(factory)
        val customPrimary = RgbaColor(210, 45, 95, 255)
        val app = SwingApplicationImpl()
        try {
            app.setThemeConfig(
                ThemeConfig(
                    lightColors = ThemeColorSet(primary = customPrimary),
                    darkColors = ThemeColorSet(primary = customPrimary)
                )
            )

            // Buttons & StyledButton DO reflect BootstrapType
            val button = SwingButtonImpl()
            button.setType(BootstrapType.PRIMARY)
            assertEquals(BootstrapType.PRIMARY, button.nativeWidget.getClientProperty("bootstrapType"))

            val styledBtn = SwingStyledButtonImpl()
            styledBtn.setType(BootstrapType.PRIMARY)
            assertEquals(BootstrapType.PRIMARY, styledBtn.nativeWidget.getClientProperty("bootstrapType"))

            val badge = Badge("New", type = BootstrapType.DANGER)
            val alert = Alert("Notice", type = BootstrapType.WARNING)
            val card = Card(title = "Card", type = BootstrapType.PRIMARY)
            val dropdown = DropdownButton("Menu").apply { type = BootstrapType.PRIMARY }
            val split = SplitDropdownButton("Split").apply { type = BootstrapType.DANGER }
            val pb = ProgressBar(progress = 0.5f).apply { type = BootstrapType.DANGER }
            val styledComp = StyledComponent().apply { type = BootstrapType.DANGER }
            val composite = object : Composite() {}.apply { type = BootstrapType.DANGER }
            val slider = StyledSlider().apply { type = BootstrapType.DANGER }

            val host = VStack().apply {
                addChild(badge)
                addChild(alert)
                addChild(card)
                addChild(dropdown)
                addChild(split)
                addChild(pb)
                addChild(styledComp)
                addChild(composite)
                addChild(slider)
            }
            val window = Window("Policy").apply { setContent(host) }
            window.show(factory)

            // Non-control Bootstrap components (Badge, Alert, Card) DO reflect BootstrapType
            val badgeImpl = badge.ensureNativeImpl() as SwingBaseNativeImpl
            assertEquals(BootstrapType.DANGER, badgeImpl.nativeWidget.getClientProperty("bootstrapType"))

            val alertImpl = alert.ensureNativeImpl() as SwingBaseNativeImpl
            assertEquals(BootstrapType.WARNING, alertImpl.nativeWidget.getClientProperty("bootstrapType"))

            val cardImpl = card.ensureNativeImpl() as SwingContainerImpl
            assertEquals(BootstrapType.PRIMARY, cardImpl.nativeWidget.getClientProperty("bootstrapType"))
            assertNotNull(
                SwingSurfacePaintUtil.resolveEffectiveSurfaceSpec(
                    nativeImpl = cardImpl,
                    tagName = cardImpl.componentTagName,
                    classPrefix = cardImpl.componentClassPrefix,
                    classes = cardImpl.getClasses(),
                    explicitSpec = null,
                    isDark = false
                )?.backgroundColor
            )

            // DropdownButton & SplitDropdownButton MUST ignore BootstrapType on Swing
            val dropdownImpl = dropdown.ensureNativeImpl() as SwingDropdownImpl
            assertEquals(BootstrapType.NONE, dropdownImpl.nativeWidget.getClientProperty("bootstrapType"))
            assertEquals("true", dropdownImpl.nativeWidget.getClientProperty("useSystemControlAppearance"))
            val sprite = dropdownImpl.renderOffscreenButtonSprite(width = 120, height = 36, includeTextOverlay = false)
            val centerArgb = sprite.getRGB(60, 18)
            val spriteRgb = RgbaColor((centerArgb ushr 16) and 0xFF, (centerArgb ushr 8) and 0xFF, centerArgb and 0xFF, 255)
            assertNotEquals(customPrimary, spriteRgb)

            val splitImpl = split.ensureNativeImpl() as SwingBaseNativeImpl
            assertEquals(BootstrapType.NONE, splitImpl.nativeWidget.getClientProperty("bootstrapType"))

            // ProgressBar & other non-Button controls + generic StyledComponent/StyledContainer/Composite/StyledSlider MUST ignore BootstrapType
            val pbImpl = pb.ensureNativeImpl() as SwingProgressBarImpl
            assertEquals(BootstrapType.NONE, pbImpl.nativeWidget.getClientProperty("bootstrapType"))
            assertEquals("true", pbImpl.nativeWidget.getClientProperty("useSystemControlAppearance"))

            val styledContainer = SwingStyledContainerImpl()
            styledContainer.setType(BootstrapType.DANGER)
            styledContainer.setBackgroundType(BootstrapType.SUCCESS)
            assertEquals(BootstrapType.NONE, styledContainer.nativeWidget.getClientProperty("bootstrapType"))
            assertNull(styledContainer.getStyledVisuals().backgroundColor)

            val styledCompImpl = styledComp.ensureNativeImpl() as SwingStyledComponentImpl
            assertEquals(BootstrapType.NONE, styledCompImpl.nativeWidget.getClientProperty("bootstrapType"))
            assertNull(styledCompImpl.resolveCurrentVisuals().backgroundColor)

            val compositeImpl = composite.ensureNativeImpl() as SwingStyledContainerImpl
            assertEquals(BootstrapType.NONE, compositeImpl.nativeWidget.getClientProperty("bootstrapType"))
            assertNull(compositeImpl.getStyledVisuals().backgroundColor)

            val sliderImpl = slider.ensureNativeImpl() as SwingStyledContainerImpl
            assertEquals(BootstrapType.NONE, sliderImpl.nativeWidget.getClientProperty("bootstrapType"))
            assertNull(sliderImpl.getStyledVisuals().backgroundColor)

            window.dispose()

            val nonButtonControls = listOf<SwingBaseNativeImpl>(
                SwingDropdownImpl(),
                SwingSpinnerImpl(),
                SwingScrollViewImpl(),
                SwingTabContainerImpl(),
                SwingCheckboxImpl(),
                SwingRangeSliderImpl(),
                SwingSelectImpl(),
                SwingTextInputImpl(),
                SwingContainerImpl(),
                SwingStyledContainerImpl(),
                SwingStyledComponentImpl()
            )
            val typedBootstrap = ComponentBootstrap().apply {
                setType(BootstrapType.DANGER)
                setBackgroundType(BootstrapType.WARNING)
            }
            for (ctrl in nonButtonControls) {
                ctrl.applyComponentBootstrap(typedBootstrap)
                assertEquals(
                    BootstrapType.NONE,
                    ctrl.nativeWidget.getClientProperty("bootstrapType"),
                    "${ctrl::class.simpleName} must ignore BootstrapType on Swing"
                )
            }
        } finally {
            app.dispose()
            if (prevFactory != null) GenericUI.initialize(prevFactory)
        }
    }

    @Test
    fun swingStyledComponentInteractiveStatesProximalRelativeAbsoluteSignedTransformZIndexAndPointerEventsPassThrough() {
        com.nexustools.genericui.component.shell.Application.current?.dispose()
        val app = SwingApplicationImpl()
        try {
            val comp = StyledComponent()
            val scImpl = SwingNativeFactory().createStyledComponent("styled-component", "genericui:styled-component") as SwingStyledComponentImpl
            assertNotNull(comp)
            scImpl.setStyledVisuals(
                StyledVisualSpec(
                    backgroundColor = RgbaColor(20, 40, 60, 255),
                    borderRadiusPx = 8f,
                    opacity = 0.85f
                )
            )
            scImpl.setHoverVisuals(StyledVisualSpec(backgroundColor = RgbaColor(30, 70, 110, 255)))
            scImpl.setFocusVisuals(StyledVisualSpec(focusRingColor = RgbaColor(0, 160, 255, 255), focusRingWidthPx = 2f))
            scImpl.setActiveVisuals(StyledVisualSpec(backgroundColor = RgbaColor(10, 20, 30, 255)))
            scImpl.setDisabledVisuals(StyledVisualSpec(backgroundColor = RgbaColor(120, 120, 120, 255)))
            assertEquals(RgbaColor(20, 40, 60, 255), scImpl.resolveCurrentVisuals().backgroundColor)

            scImpl.setHoveredState(true)
            assertEquals(RgbaColor(30, 70, 110, 255), scImpl.resolveCurrentVisuals().backgroundColor)
            scImpl.setActivePressedState(true)
            assertEquals(RgbaColor(10, 20, 30, 255), scImpl.resolveCurrentVisuals().backgroundColor)
            scImpl.setEnabled(false)
            assertEquals(RgbaColor(120, 120, 120, 255), scImpl.resolveCurrentVisuals().backgroundColor)

            scImpl.setCustomCursor(CssCursor.NONE)
            assertNotNull(scImpl.nativeWidget.cursor)

            // Proximal position: relative ancestor for nested position: static intermediate container + signed transform + zIndex + pointer-events: none
            val relativeAncestor = SwingContainerImpl()
            relativeAncestor.setPosition(PositionMode.RELATIVE)
            relativeAncestor.setPreferredSize(Size(400, 300))
            relativeAncestor.nativeWidget.setBounds(0, 0, 400, 300)

            val staticMiddle = SwingContainerImpl()
            staticMiddle.applyComponentStyle(ComponentStyle().apply {
                setMargin(CssEdgeLengths(top = CssLength.px(50f), left = CssLength.px(20f), right = CssLength.px(0f), bottom = CssLength.px(0f)))
                setWidth(CssLength.px(300f))
                setHeight(CssLength.px(200f))
            })

            val absChild = SwingStyledComponentImpl()
            absChild.applyComponentStyle(ComponentStyle().apply {
                setPosition(CssPosition.ABSOLUTE)
                setTop(CssLength.px(20f))
                setRight(CssLength.px(10f))
                setZIndex(25)
                setWidth(CssLength.px(80f))
                setHeight(CssLength.px(30f))
                setTransform(CssTransform2D(translateX = -10f, translateY = -5f, scaleX = 1.25f))
            })
            absChild.applyComponentBootstrap(ComponentBootstrap().apply { setPointerEvents(BootstrapPointerEvents.NONE) })

            val siblingLowerZ = SwingStyledComponentImpl()
            siblingLowerZ.applyComponentStyle(ComponentStyle().apply {
                setPosition(CssPosition.ABSOLUTE)
                setTop(CssLength.px(20f))
                setRight(CssLength.px(10f))
                setZIndex(5)
                setWidth(CssLength.px(80f))
                setHeight(CssLength.px(30f))
            })

            staticMiddle.addChildNative(siblingLowerZ, -1)
            staticMiddle.addChildNative(absChild, -1)
            relativeAncestor.addChildNative(staticMiddle, -1)

            relativeAncestor.nativeWidget.size = Dimension(400, 300)
            SwingFlexLayoutManager.layoutTreeNow(relativeAncestor.nativeWidget)

            // In relativeAncestor (400x300): right: 10px -> x = 400 - 10 - 80 = 310; top: 20px -> y = 20;
            // signed translate(-10, -5) -> (300, 15) in relativeAncestor.
            // staticMiddle is at (20, 50) inside relativeAncestor -> absChild local bounds = (280, -35, 80, 30)!
            assertEquals(280, absChild.nativeWidget.x, "Expected proximal relative x with negative translate")
            assertEquals(-35, absChild.nativeWidget.y, "Expected proximal relative y above static parent")
            assertEquals(80, absChild.nativeWidget.width)
            assertEquals(30, absChild.nativeWidget.height)

            // Higher z-index (25 > 5) must be at Swing component index 0 (painted last / hit-tested first)
            assertEquals(0, staticMiddle.nativeWidget.getComponentZOrder(absChild.nativeWidget))
            assertEquals(1, staticMiddle.nativeWidget.getComponentZOrder(siblingLowerZ.nativeWidget))

            // pointer-events: none must make Swing contains() return false so lower z-order catches clicks
            assertFalse(absChild.nativeWidget.contains(10, 10))
            assertTrue(siblingLowerZ.nativeWidget.contains(10, 10))

            // Direct Window child with position: absolute & transform: translateX(-50%) is laid out accurately (not forced to 0,0,w,h)
            val win = SwingWindowImpl()
            val winAbs = SwingStyledComponentImpl()
            winAbs.applyComponentStyle(ComponentStyle().apply {
                setPosition(CssPosition.ABSOLUTE)
                setTop(CssLength.px(20f))
                setLeft(CssLength.percent(50f))
                setWidth(CssLength.px(100f))
                setHeight(CssLength.px(40f))
                setTransform(CssTransform2D(translateX = -50f, translateXIsPercent = true))
            })
            win.setWindowSize(Size(800, 600))
            win.addChildNative(winAbs, -1)
            val centerHost = winAbs.nativeWidget.parent!!
            centerHost.setSize(800, 600)
            centerHost.doLayout()
            assertEquals(350, winAbs.nativeWidget.x)
            assertEquals(20, winAbs.nativeWidget.y)
            assertEquals(100, winAbs.nativeWidget.width)
            assertEquals(40, winAbs.nativeWidget.height)
            win.dispose()
        } finally {
            app.dispose()
        }
    }
}
