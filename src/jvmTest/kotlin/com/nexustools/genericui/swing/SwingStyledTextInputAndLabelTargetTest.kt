package com.nexustools.genericui.swing

import com.nexustools.genericui.component.action.Button
import com.nexustools.genericui.component.action.styled.StyledButton
import com.nexustools.genericui.component.form.CheckBox
import com.nexustools.genericui.component.form.Select
import com.nexustools.genericui.component.form.SelectOption
import com.nexustools.genericui.component.form.TextArea
import com.nexustools.genericui.component.form.TextField
import com.nexustools.genericui.component.form.styled.StyledTextArea
import com.nexustools.genericui.component.form.styled.StyledTextField
import com.nexustools.genericui.component.layout.Container
import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.component.shell.Window
import com.nexustools.genericui.component.typography.Label
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.geometry.EdgeInsets
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.enums.css.CssBorderStyle
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.StyledVisualSpec
import com.nexustools.genericui.window.TitleBar
import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JLabel
import javax.swing.JPasswordField
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SwingStyledTextInputAndLabelTargetTest {

    private fun runEdtAware(block: () -> Unit) {
        if (GraphicsEnvironment.isHeadless() || SwingUtilities.isEventDispatchThread()) {
            block()
        } else {
            SwingUtilities.invokeAndWait { block() }
        }
    }

    @Test
    fun styledTextFieldAndAreaCreateSwingStyledTextInputImplWhilePlainTextFieldStaysNative() {
        runEdtAware {
            val factory = SwingNativeFactory()
            val prevFactory = GenericUI.activeFactory
            val prevApp = Application.current
            Application.current = null
            GenericUI.initialize(factory)
            val window = Window("Swing Factory Realization Check")
            try {
                val plainField = TextField(value = "plain", placeholder = "Plain")
                val plainArea = TextArea(value = "area")
                val styledField = StyledTextField(value = "https://example.org", placeholder = "Search or enter URL")
                val styledArea = StyledTextArea(value = "Line 1\nLine 2", placeholder = "Notes")
                val pwdField = StyledTextField(value = "secret", passwordMode = true)
                val root = Container().apply {
                    addChild(plainField)
                    addChild(plainArea)
                    addChild(styledField)
                    addChild(styledArea)
                    addChild(pwdField)
                }
                window.setContent(root)
                window.show(factory)

                val plainFieldImpl = plainField.ensureNativeImpl()
                val plainAreaImpl = plainArea.ensureNativeImpl()
                val styledFieldImpl = styledField.ensureNativeImpl()
                val styledAreaImpl = styledArea.ensureNativeImpl()

                assertIs<SwingTextInputImpl>(plainFieldImpl)
                assertFalse(plainFieldImpl is SwingStyledTextInputImpl)
                assertIs<SwingTextInputImpl>(plainAreaImpl)
                assertFalse(plainAreaImpl is SwingStyledTextInputImpl)

                assertIs<SwingStyledTextInputImpl>(styledFieldImpl)
                assertIs<SwingStyledTextInputImpl>(styledAreaImpl)
                assertIs<JTextField>(styledFieldImpl.activeEditor)
                assertIs<JTextArea>(styledAreaImpl.activeEditor)
                assertFalse(styledFieldImpl.activeEditor.isOpaque)
                assertFalse(styledAreaImpl.activeEditor.isOpaque)

                styledFieldImpl.setPasswordMode(true)
                assertIs<JPasswordField>(styledFieldImpl.activeEditor)
                assertEquals("https://example.org", styledFieldImpl.getValue())

                styledFieldImpl.setPasswordMode(false)
                assertEquals("https://example.org", styledFieldImpl.getValue())

                val pwdImpl = assertIs<SwingStyledTextInputImpl>(pwdField.ensureNativeImpl())
                assertIs<JPasswordField>(pwdImpl.activeEditor)
            } finally {
                if (window.isWindowShown) {
                    window.close()
                }
                Application.current = prevApp
                GenericUI.activeFactory = prevFactory
            }
        }
    }

    @Test
    fun swingStyledTextInputAppliesVisualStatesTypographyPlaceholderAndPaintsPillOmnibox() {
        runEdtAware {
            val factory = SwingNativeFactory()
            val prevFactory = GenericUI.activeFactory
            val prevApp = Application.current
            Application.current = null
            GenericUI.initialize(factory)
            val window = Window("Swing Visual Pill Check")
            try {
                val field = StyledTextField(placeholder = "Search Google or type a URL")
                window.setContent(Container().apply { addChild(field) })
                window.show(factory)
                val impl = assertIs<SwingStyledTextInputImpl>(field.ensureNativeImpl())

                val baseBg = RgbaColor(241, 243, 244, 255)
                val baseBorder = RgbaColor(218, 220, 224, 255)
                val hoverBg = RgbaColor(232, 234, 237, 255)
                val focusBorder = RgbaColor(26, 115, 232, 255)
                val focusRing = RgbaColor(26, 115, 232, 96)
                val disabledBg = RgbaColor(248, 249, 250, 180)
                val textCol = RgbaColor(32, 33, 36, 255)
                val placeholderCol = RgbaColor(95, 99, 104, 255)

                impl.setBaseVisuals(
                    StyledVisualSpec(
                        backgroundColor = baseBg,
                        textColor = textCol,
                        borderColor = baseBorder,
                        borderWidthPx = 1f,
                        borderStyle = CssBorderStyle.SOLID,
                        borderRadiusPx = 9999f,
                        padding = EdgeInsets(6, 16, 6, 16),
                        heightPx = 36,
                        placeholderColor = placeholderCol,
                        fontSizePx = 15f,
                        fontWeightNumeric = 600
                    )
                )
                impl.setHoverVisuals(StyledVisualSpec(backgroundColor = hoverBg))
                impl.setFocusVisuals(
                    StyledVisualSpec(
                        borderColor = focusBorder,
                        borderWidthPx = 2f,
                        focusRingColor = focusRing,
                        focusRingWidthPx = 2f
                    )
                )
                impl.setActiveVisuals(StyledVisualSpec(backgroundColor = RgbaColor(225, 229, 234, 255)))
                impl.setDisabledVisuals(StyledVisualSpec(backgroundColor = disabledBg))

                val baseResolved = impl.resolveCurrentVisuals()
                assertEquals(baseBg, baseResolved.backgroundColor)
                assertEquals(9999f, baseResolved.borderRadiusPx)
                assertEquals(placeholderCol, baseResolved.placeholderColor)
                assertEquals(36, impl.nativeWidget.preferredSize.height)
                assertEquals(Color(textCol.r, textCol.g, textCol.b, textCol.a), impl.activeEditor.foreground)
                assertEquals(15f, impl.activeEditor.font.size2D, 0.01f)
                assertTrue(impl.activeEditor.font.isBold)

                impl.setHoveredState(true)
                assertEquals(hoverBg, impl.resolveCurrentVisuals().backgroundColor)

                impl.setFocusedState(true)
                assertTrue(impl.isFocusedState())
                val focusResolved = impl.resolveCurrentVisuals()
                assertEquals(focusBorder, focusResolved.borderColor)
                assertEquals(focusRing, focusResolved.focusRingColor)

                impl.setActivePressedState(true)
                assertEquals(RgbaColor(225, 229, 234, 255), impl.resolveCurrentVisuals().backgroundColor)

                impl.setEnabled(false)
                assertFalse(impl.isFocusedState())
                assertEquals(disabledBg, impl.resolveCurrentVisuals().backgroundColor)

                impl.setEnabled(true)
                impl.setFocusedState(true)
                impl.nativeWidget.setSize(320, 36)
                impl.nativeWidget.doLayout()
                val img = BufferedImage(320, 36, BufferedImage.TYPE_INT_ARGB)
                val g2 = img.createGraphics()
                try {
                    impl.nativeWidget.paint(g2)
                } finally {
                    g2.dispose()
                }
                val centerAlpha = (img.getRGB(160, 18) ushr 24) and 0xFF
                assertNotEquals(0, centerAlpha, "Styled pill surface should render opaque/translucent fill in center")
            } finally {
                if (window.isWindowShown) {
                    window.close()
                }
                Application.current = prevApp
                GenericUI.activeFactory = prevFactory
            }
        }
    }

    @Test
    fun labelTargetMouseClickAndReleaseFocusesTargetControlsOnSwing() {
        runEdtAware {
            val factory = SwingNativeFactory()
            val prevFactory = GenericUI.activeFactory
            val prevApp = Application.current
            Application.current = null
            GenericUI.initialize(factory)
            val window = Window("Label Target Swing Test", initialSize = Size(480, 360))
            try {
                val plainField = TextField(placeholder = "Plain")
                val styledField = StyledTextField(placeholder = "Styled")
                val button = Button("Action")
                val styledButton = StyledButton("StyledAction")
                val checkbox = CheckBox("Agree")
                val select = Select(options = listOf(SelectOption("a", "Alpha"), SelectOption("b", "Beta")))

                val plainLabel = Label("Plain", target = plainField)
                val styledLabel = Label("Styled", target = styledField)
                val btnLabel = Label("Btn", target = button)
                val styledBtnLabel = Label("StyledBtn", target = styledButton)
                val checkLabel = Label("Check", target = checkbox)
                val selectLabel = Label("Select", target = select)

                val root = Container().apply {
                    addChild(plainLabel)
                    addChild(plainField)
                    addChild(styledLabel)
                    addChild(styledField)
                    addChild(btnLabel)
                    addChild(button)
                    addChild(styledBtnLabel)
                    addChild(styledButton)
                    addChild(checkLabel)
                    addChild(checkbox)
                    addChild(selectLabel)
                    addChild(select)
                }
                window.setContent(root)
                window.show(factory)

                val cases = listOf(
                    plainLabel to plainField,
                    styledLabel to styledField,
                    btnLabel to button,
                    styledBtnLabel to styledButton,
                    checkLabel to checkbox,
                    selectLabel to select
                )
                for ((labelComp, targetComp) in cases) {
                    val labelImpl = assertIs<SwingTextImpl>(labelComp.ensureNativeImpl())
                    val jLabel: JLabel = labelImpl.nativeWidget
                    jLabel.setBounds(0, 0, 120, 24)

                    val targetImpl = assertNotNull(targetComp.ensureNativeImpl())
                    targetImpl.setFocusedState(false)
                    assertFalse(targetImpl.isFocusedState())

                    // Press + release (without synthetic MOUSE_CLICKED) must dispatch Label click and focus target.
                    val now = System.currentTimeMillis()
                    jLabel.dispatchEvent(
                        MouseEvent(jLabel, MouseEvent.MOUSE_PRESSED, now, MouseEvent.BUTTON1_DOWN_MASK, 10, 10, 1, false, MouseEvent.BUTTON1)
                    )
                    jLabel.dispatchEvent(
                        MouseEvent(jLabel, MouseEvent.MOUSE_RELEASED, now + 10, 0, 11, 11, 1, false, MouseEvent.BUTTON1)
                    )
                    assertTrue(targetImpl.isFocusedState(), "Press+release on Label must focus ${targetComp::class.simpleName}")

                    // Direct MOUSE_CLICKED must also focus target reliably.
                    targetImpl.setFocusedState(false)
                    assertFalse(targetImpl.isFocusedState())
                    jLabel.dispatchEvent(
                        MouseEvent(jLabel, MouseEvent.MOUSE_CLICKED, now + 30, 0, 10, 10, 1, false, MouseEvent.BUTTON1)
                    )
                    assertTrue(targetImpl.isFocusedState(), "MOUSE_CLICKED on Label must focus ${targetComp::class.simpleName}")
                }
            } finally {
                if (window.isWindowShown) {
                    window.close()
                }
                Application.current = prevApp
                GenericUI.activeFactory = prevFactory
            }
        }
    }

    @Test
    fun swingNeverEmbedsComponentsInOsTitleBar() {
        runEdtAware {
            val factory = SwingNativeFactory()
            val prevFactory = GenericUI.activeFactory
            val prevApp = Application.current
            Application.current = null
            GenericUI.initialize(factory)
            val window = Window("Swing TitleBar Check")
            try {
                window.setContent(Container())
                window.show(factory)
                val impl = assertIs<SwingWindowImpl>(window.ensureNativeImpl())
                val caps = impl.getTitleCapabilities()
                assertFalse(caps.canEmbedComponentsInTitleBar)
                assertFalse(caps.supportsCustomTitleBar)
                assertFalse(caps.supportsCustomComponents)
                assertFalse(caps.supportsLeadingComponents)
                assertFalse(caps.supportsCenterComponents)
                assertFalse(caps.supportsTrailingComponents)
                assertEquals(caps, factory.defaultTitleCapabilities)

                val tabStrip = Container()
                val titleBar = TitleBar(customTitleBar = true).addLeading(tabStrip)
                window.setTitleBar(titleBar)
                assertFalse(window.titleCapabilities.canEmbedComponentsInTitleBar)
                assertFalse(tabStrip.mountedInTitleBar)
            } finally {
                if (window.isWindowShown) {
                    window.close()
                }
                Application.current = prevApp
                GenericUI.activeFactory = prevFactory
            }
        }
    }
}
