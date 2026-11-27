package com.nexustools.genericui.swing

import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.css.CssCornerRadii
import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.css.CssLength
import com.nexustools.genericui.style.css.CssShadow
import com.nexustools.genericui.style.css.StyledVisualSpec
import java.awt.Color
import java.awt.Graphics
import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwingBackdropFilterParityTest {

    private fun runEdtAware(block: () -> Unit) {
        if (GraphicsEnvironment.isHeadless() || SwingUtilities.isEventDispatchThread()) {
            block()
        } else {
            SwingUtilities.invokeAndWait { block() }
        }
    }

    private class SplitBackdropPanel(
        private val leftColor: Color = Color(220, 30, 30),
        private val rightColor: Color = Color(30, 60, 230),
        private val splitX: Int = 60
    ) : JPanel(null) {
        init {
            isOpaque = true
            background = leftColor
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            g.color = leftColor
            g.fillRect(0, 0, splitX.coerceAtMost(width), height)
            g.color = rightColor
            g.fillRect(splitX.coerceAtMost(width), 0, (width - splitX).coerceAtLeast(0), height)
        }
    }

    private fun renderHierarchy(root: JPanel, w: Int, h: Int): BufferedImage {
        root.setSize(w, h)
        root.doLayout()
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            root.paint(g)
        } finally {
            g.dispose()
        }
        return img
    }

    private fun createSplitRoot(
        leftColor: Color,
        rightColor: Color,
        splitX: Int,
        overlayComp: javax.swing.JComponent,
        w: Int,
        h: Int
    ): JPanel {
        val root = JPanel(null).apply {
            isOpaque = true
            background = leftColor
            setSize(w, h)
        }
        val backdrop = SplitBackdropPanel(leftColor, rightColor, splitX).apply {
            setBounds(0, 0, w, h)
        }
        // Swing paints componentCount-1 downTo 0 (index 1 back, index 0 front)
        root.add(overlayComp)
        root.add(backdrop)
        return root
    }

    private fun renderWithFilter(
        filter: CssFilter?,
        leftColor: Color = Color(220, 40, 40),
        rightColor: Color = Color(40, 200, 80),
        splitX: Int = 70,
        boxX: Int = 30,
        boxY: Int = 20,
        boxW: Int = 80,
        boxH: Int = 60,
        radii: CssCornerRadii? = null,
        uniformRadius: Float? = null
    ): BufferedImage {
        val overlay = SwingStyledComponentImpl()
        overlay.setStyledVisuals(
            StyledVisualSpec(
                backdropFilter = filter,
                borderRadiusPx = uniformRadius
            )
        )
        if (radii != null) {
            overlay.applyComputedStyle(ComputedStyle(borderRadius = radii))
        }
        overlay.nativeWidget.setBounds(boxX, boxY, boxW, boxH)
        val root = createSplitRoot(leftColor, rightColor, splitX, overlay.nativeWidget, 160, 100)
        return renderHierarchy(root, 160, 100)
    }

    @Test
    fun allTenBackdropFilterOpsProduceDistinctExpectedPixelTransforms() {
        runEdtAware {
            val base = renderWithFilter(null)
            val basePx = base.getRGB(50, 50)

            val ops = listOf(
                "blur" to CssFilter.parse("blur(12px)"),
                "brightness" to CssFilter.parse("brightness(1.5)"),
                "contrast" to CssFilter.parse("contrast(1.8)"),
                "grayscale" to CssFilter.parse("grayscale(1)"),
                "hue-rotate" to CssFilter.parse("hue-rotate(120deg)"),
                "invert" to CssFilter.parse("invert(1)"),
                "opacity" to CssFilter.parse("invert(1) opacity(0.5)"),
                "saturate" to CssFilter.parse("saturate(2.5)"),
                "sepia" to CssFilter.parse("sepia(1)"),
                "drop-shadow" to CssFilter(
                    blur = CssLength.px(4f),
                    dropShadow = CssShadow(
                        offsetX = CssLength.px(6f),
                        offsetY = CssLength.px(4f),
                        blurRadius = CssLength.px(6f),
                        color = RgbaColor(0, 0, 0, 200)
                    )
                )
            )

            for ((name, filter) in ops) {
                assertNotNull(filter, "Filter $name must parse")
                if (name == "drop-shadow") {
                    val shadowRoot = JPanel(null).apply {
                        isOpaque = false
                        setSize(120, 100)
                    }
                    val behind = JPanel(null).apply {
                        isOpaque = false
                        setBounds(0, 0, 120, 100)
                    }
                    val innerSquare = object : JPanel(null) {
                        override fun paintComponent(g: Graphics) {
                            g.color = Color(240, 240, 240, 255)
                            g.fillRect(20, 20, 40, 40)
                        }
                    }.apply {
                        isOpaque = false
                        setBounds(0, 0, 120, 100)
                    }
                    behind.add(innerSquare)
                    val overlay = SwingStyledComponentImpl().apply {
                        setStyledVisuals(StyledVisualSpec(backdropFilter = filter))
                    }
                    overlay.nativeWidget.setBounds(10, 10, 100, 80)
                    shadowRoot.add(overlay.nativeWidget)
                    shadowRoot.add(behind)
                    val img = renderHierarchy(shadowRoot, 120, 100)
                    // At (66, 30) - outside the inner opaque square (20..60) shifted right by +6px offset
                    val shadowSample = img.getRGB(64, 30)
                    val shadowAlpha = (shadowSample ushr 24) and 0xFF
                    assertTrue(shadowAlpha > 0, "drop-shadow must cast blurred alpha halo outside opaque foreground region")
                } else {
                    val img = renderWithFilter(filter)
                    val sample = if (name == "blur") img.getRGB(68, 50) else img.getRGB(45, 50)
                    val compareBase = if (name == "blur") base.getRGB(68, 50) else basePx
                    assertNotEquals(
                        compareBase,
                        sample,
                        "backdrop-filter $name should visibly modify backdrop pixels"
                    )
                }
            }

            // Verify opacity(0.5) over invert(1) blends ~50% between base and invert(1)
            val inverted = renderWithFilter(CssFilter.parse("invert(1)"))
            val halfInverted = renderWithFilter(CssFilter.parse("invert(1) opacity(0.5)"))
            val bR = (base.getRGB(45, 50) ushr 16) and 0xFF
            val iR = (inverted.getRGB(45, 50) ushr 16) and 0xFF
            val hR = (halfInverted.getRGB(45, 50) ushr 16) and 0xFF
            val expectedMid = (bR + iR) / 2
            assertTrue(
                abs(hR - expectedMid) <= 4,
                "opacity(0.5) should blend halfway between $bR and $iR, got $hR"
            )
        }
    }

    @Test
    fun blur22Saturate14ExactRadiusAndHaloEdgeContinuity() {
        runEdtAware {
            // Left half (0..100) is bright red, right half (100..200) is bright blue.
            // Component sits strictly inside the red half at x=[30..96], right next to the blue boundary at x=100.
            // With blur(22px) + halo padding (~33px), pixels inside [90..95] MUST pick up blue from x>=100!
            val overlay = SwingStyledContainerImpl()
            overlay.setStyledVisuals(
                StyledVisualSpec(backdropFilter = CssFilter.parse("blur(22px) saturate(1.4)"))
            )
            overlay.nativeWidget.setBounds(20, 10, 76, 80) // x in [20..96), 4px away from blue region at x=100
            val root = createSplitRoot(Color(230, 30, 30), Color(20, 80, 240), 100, overlay.nativeWidget, 200, 100)
            val img22 = renderHierarchy(root, 200, 100)

            // Pixel at x=94 is inside the component, 6px from the blue boundary outside the component
            val edgePixel = img22.getRGB(94, 50)
            val edgeBlue = edgePixel and 0xFF
            assertTrue(
                edgeBlue > 60,
                "Halo padding must sample neighbor pixels outside component bounds (got blue=$edgeBlue)"
            )

            // Verify exact blur(Npx) radius sensitivity: blur(4px) must NOT spread as far as blur(22px)
            val overlay4 = SwingStyledContainerImpl()
            overlay4.setStyledVisuals(
                StyledVisualSpec(backdropFilter = CssFilter.parse("blur(4px) saturate(1.4)"))
            )
            overlay4.nativeWidget.setBounds(20, 10, 76, 80)
            val root4 = createSplitRoot(Color(230, 30, 30), Color(20, 80, 240), 100, overlay4.nativeWidget, 200, 100)
            val img4 = renderHierarchy(root4, 200, 100)
            val edgeBlue4 = img4.getRGB(94, 50) and 0xFF
            assertTrue(
                edgeBlue > edgeBlue4 + 10,
                "blur(22px) ($edgeBlue) must spread farther than blur(4px) ($edgeBlue4)"
            )

            // Verify blur(22px) saturate(1.4) differs from blur(22px) alone
            val ovPlain = SwingStyledContainerImpl().apply {
                setStyledVisuals(StyledVisualSpec(backdropFilter = CssFilter.parse("blur(22px)")))
                nativeWidget.setBounds(20, 10, 76, 80)
            }
            val rootPlain = createSplitRoot(Color(230, 60, 60), Color(60, 80, 220), 100, ovPlain.nativeWidget, 200, 100)
            val plainImg = renderHierarchy(rootPlain, 200, 100)

            val ovSat = SwingStyledContainerImpl().apply {
                setStyledVisuals(StyledVisualSpec(backdropFilter = CssFilter.parse("blur(22px) saturate(1.4)")))
                nativeWidget.setBounds(20, 10, 76, 80)
            }
            val rootSat = createSplitRoot(Color(230, 60, 60), Color(60, 80, 220), 100, ovSat.nativeWidget, 200, 100)
            val satImg = renderHierarchy(rootSat, 200, 100)

            val plainPx = plainImg.getRGB(80, 50)
            val satPx = satImg.getRGB(80, 50)
            val plainSpread = max((plainPx ushr 16) and 0xFF, plainPx and 0xFF) - ((plainPx ushr 8) and 0xFF)
            val satSpread = max((satPx ushr 16) and 0xFF, satPx and 0xFF) - ((satPx ushr 8) and 0xFF)
            assertTrue(satSpread > plainSpread, "saturate(1.4) must increase chroma spread ($satSpread vs $plainSpread)")
        }
    }

    @Test
    fun perCornerRoundingClipsBackdropCleanly() {
        runEdtAware {
            val img = renderWithFilter(
                filter = CssFilter.parse("invert(1)"),
                leftColor = Color(200, 40, 40),
                rightColor = Color(200, 40, 40),
                boxX = 20,
                boxY = 20,
                boxW = 80,
                boxH = 60,
                radii = CssCornerRadii(
                    topLeft = CssLength.px(24f),
                    topRight = CssLength.px(0f),
                    bottomRight = CssLength.px(0f),
                    bottomLeft = CssLength.px(0f)
                )
            )
            // Top-left corner pixel (21, 21) is outside 24px rounded arc -> retains un-inverted red backdrop
            val tlPx = img.getRGB(21, 21)
            assertTrue(((tlPx ushr 16) and 0xFF) > 150, "Top-left rounded corner must remain un-inverted backdrop")
            // Top-right corner pixel (98, 21) has radius 0 -> inverted cyan
            val trPx = img.getRGB(98, 21)
            assertTrue(((trPx ushr 16) and 0xFF) < 90, "Top-right sharp corner must apply inverted backdrop")
        }
    }

    @Test
    fun setAttributeAndStateVisualsParityAcrossStyledContainerComponentAndButton() {
        runEdtAware {
            val container = SwingStyledContainerImpl()
            val component = SwingStyledComponentImpl()
            val button = SwingStyledButtonImpl()

            val impls: List<SwingBaseNativeImpl> = listOf(container, component, button)
            for (impl in impls) {
                impl.setAttribute("backdrop-filter", "blur(14px) saturate(1.4)")
                assertEquals(
                    "blur(14px) saturate(1.4)",
                    impl.nativeWidget.getClientProperty("-webkit-backdrop-filter"),
                    "backdrop-filter attribute must sync on ${impl::class.simpleName}"
                )

                impl.setAttribute("backdrop-filter", null)
                impl.setAttribute("backdropFilter", "invert(1)")
                assertEquals(
                    "invert(1)",
                    impl.nativeWidget.getClientProperty("-webkit-backdrop-filter"),
                    "backdropFilter attribute must sync on ${impl::class.simpleName}"
                )

                impl.setAttribute("backdropFilter", null)
                impl.setAttribute("backdropBlurPx", "18")
                assertEquals(
                    "blur(18px)",
                    impl.nativeWidget.getClientProperty("-webkit-backdrop-filter"),
                    "backdropBlurPx attribute must sync on ${impl::class.simpleName}"
                )

                impl.setAttribute("backdropBlurPx", null)
                assertNull(impl.nativeWidget.getClientProperty("-webkit-backdrop-filter"))
            }

            val hoverFilter = CssFilter.parse("blur(10px) brightness(1.3)")
            val focusFilter = CssFilter.parse("blur(8px) contrast(1.4)")
            val activeFilter = CssFilter.parse("blur(6px) invert(1)")
            val disabledFilter = CssFilter.parse("grayscale(1) opacity(0.5)")
            val stateStyle = ComputedStyle(
                backdropFilter = CssFilter.parse("blur(4px)"),
                hoverStyle = ComputedStyle(backdropFilter = hoverFilter),
                focusStyle = ComputedStyle(backdropFilter = focusFilter),
                activeStyle = ComputedStyle(backdropFilter = activeFilter),
                disabledStyle = ComputedStyle(backdropFilter = disabledFilter)
            )

            container.applyComputedStyle(stateStyle)
            component.applyComputedStyle(stateStyle)
            button.applyComputedStyle(stateStyle)

            assertEquals("blur(4px)", container.resolveCurrentVisuals().backdropFilter?.toCssString())
            assertEquals("blur(4px)", component.resolveCurrentVisuals().backdropFilter?.toCssString())
            assertEquals("blur(4px)", button.resolveCurrentVisuals().backdropFilter?.toCssString())

            container.setHoveredState(true)
            component.setHoveredState(true)
            button.setHoveredState(true)
            assertEquals(hoverFilter, container.resolveCurrentVisuals().backdropFilter)
            assertEquals(hoverFilter, component.resolveCurrentVisuals().backdropFilter)
            assertEquals(hoverFilter, button.resolveCurrentVisuals().backdropFilter)

            container.setHoveredState(false)
            component.setHoveredState(false)
            button.setHoveredState(false)
            container.nativeWidget.isFocusable = true
            component.nativeWidget.isFocusable = true
            button.nativeWidget.isFocusable = true
            container.setFocusedState(true)
            component.setFocusedState(true)
            button.setFocusedState(true)
            assertEquals(focusFilter, container.resolveCurrentVisuals().backdropFilter)
            assertEquals(focusFilter, component.resolveCurrentVisuals().backdropFilter)
            assertEquals(focusFilter, button.resolveCurrentVisuals().backdropFilter)

            container.setActivePressedState(true)
            component.setActivePressedState(true)
            button.setActivePressedState(true)
            assertEquals(activeFilter, container.resolveCurrentVisuals().backdropFilter)
            assertEquals(activeFilter, component.resolveCurrentVisuals().backdropFilter)
            assertEquals(activeFilter, button.resolveCurrentVisuals().backdropFilter)

            container.setEnabled(false)
            component.setEnabled(false)
            button.setEnabled(false)
            assertEquals(disabledFilter, container.resolveCurrentVisuals().backdropFilter)
            assertEquals(disabledFilter, component.resolveCurrentVisuals().backdropFilter)
            assertEquals(disabledFilter, button.resolveCurrentVisuals().backdropFilter)
        }
    }
}
