package com.nexustools.genericui.swing

import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.bootstrap.FontWeight
import com.nexustools.genericui.enums.bootstrap.TextAlignment
import com.nexustools.genericui.enums.bootstrap.TextTransform
import com.nexustools.genericui.enums.css.CssFontStyle
import com.nexustools.genericui.enums.css.CssTextDecoration
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.CssCornerRadii
import com.nexustools.genericui.style.css.CssEdgeLengths
import com.nexustools.genericui.style.css.CssFontFamily
import com.nexustools.genericui.style.css.CssLength
import com.nexustools.genericui.style.theme.ThemeColorSet
import com.nexustools.genericui.style.theme.ThemeConfig
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
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
        val col0W = table.nativeWidget.columnModel.getColumn(0).preferredWidth
        val col1W = table.nativeWidget.columnModel.getColumn(1).preferredWidth
        assertTrue(col1W > col0W, "Column 1 with weight 3f should have larger preferred width than column 0 with weight 1f")

        val grid = SwingLazyGridImpl()
        grid.setColumns(4)
        grid.setMinColumnWidthPx(150)
        assertEquals(150, grid.getMinColumnWidthPx())
        assertEquals(2, grid.resolveEffectiveColumns(availableWidthPx = 340))
        assertEquals(4, grid.resolveEffectiveColumns(availableWidthPx = 800))
    }
}
