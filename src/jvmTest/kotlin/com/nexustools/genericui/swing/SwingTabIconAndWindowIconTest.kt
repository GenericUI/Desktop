package com.nexustools.genericui.swing

import com.nexustools.genericui.component.shell.Window
import com.nexustools.genericui.component.tabs.CloseableTab
import com.nexustools.genericui.component.tabs.Tab
import com.nexustools.genericui.component.tabs.TabContainer
import com.nexustools.genericui.component.typography.Text
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.image.ImageColorSpace
import com.nexustools.genericui.image.ImageDecodeOptions
import com.nexustools.genericui.image.ImageFormat
import com.nexustools.genericui.image.ImageLoader
import com.nexustools.genericui.image.ImageSource
import com.nexustools.genericui.image.PixelFormat
import com.nexustools.genericui.image.codec.LibPngCodec
import com.nexustools.genericui.style.color.RgbaColor
import java.awt.Color
import java.awt.image.BufferedImage
import java.util.Base64
import javax.swing.ImageIcon
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SwingTabIconAndWindowIconTest {

    @AfterTest
    fun tearDown() {
        GenericUI.activeFactory = null
    }

    private fun build2x2MultiColorDibIcoBytes(): ByteArray {
        val out = ByteArray(6 + 16 + 40 + 16 + 8)
        // ICONDIR: reserved=0, type=1, count=1
        out[2] = 1
        out[4] = 1
        // ICONDIRENTRY (bytes 6..21): 2x2, 32bpp, bytesInRes=64, offset=22
        out[6] = 2
        out[7] = 2
        out[10] = 1
        out[12] = 32
        out[14] = 64
        out[18] = 22
        // BITMAPINFOHEADER at 22: biSize=40, w=2, h=4 (XOR+AND), planes=1, bpp=32
        out[22] = 40
        out[26] = 2
        out[30] = 4
        out[34] = 1
        out[36] = 32
        // Bottom-up 32bpp BGRA pixels at 62 (row y=1 first, then y=0):
        // y=1, x=0: Blue (0, 0, 255, 255) -> B, G, R, A
        out[62] = 0xFF.toByte(); out[63] = 0; out[64] = 0; out[65] = 0xFF.toByte()
        // y=1, x=1: Yellow (255, 255, 0, 255) -> B=0, G=255, R=255, A=255
        out[66] = 0; out[67] = 0xFF.toByte(); out[68] = 0xFF.toByte(); out[69] = 0xFF.toByte()
        // y=0, x=0: Red (255, 0, 0, 255) -> B=0, G=0, R=255, A=255
        out[70] = 0; out[71] = 0; out[72] = 0xFF.toByte(); out[73] = 0xFF.toByte()
        // y=0, x=1: Green (0, 255, 0, 255) -> B=0, G=255, R=0, A=255
        out[74] = 0; out[75] = 0xFF.toByte(); out[76] = 0; out[77] = 0xFF.toByte()
        return out
    }

    private fun awaitEdtAndCondition(timeoutMs: Long = 3000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            SwingUtilities.invokeAndWait {}
            if (predicate()) return
            Thread.sleep(15)
        }
        SwingUtilities.invokeAndWait {}
    }

    @Test
    fun swingTabPreservesFullColorIcoAndDataFaviconsWhenUntintedAndTintsMonochromeSvg() {
        val factory = SwingNativeFactory()
        GenericUI.initialize(factory)

        val icoBytes = build2x2MultiColorDibIcoBytes()
        val decodedIco = SwingImageIoCodecImpl().decode(ImageSource.fromBytes(icoBytes), ImageDecodeOptions())
        assertNotNull(decodedIco, "SwingImageIoCodecImpl must decode DIB .ico favicons")
        assertEquals(2, decodedIco.width)
        assertEquals(2, decodedIco.height)
        assertEquals(RgbaColor(255, 0, 0, 1f), decodedIco.getPixel(0, 0))
        assertEquals(RgbaColor(0, 255, 0, 1f), decodedIco.getPixel(1, 0))

        val icoDataUri = "data:image/x-icon;base64,${Base64.getEncoder().encodeToString(icoBytes)}"
        val fullColorTab = SwingTabImpl(initialClosable = false).apply {
            setTitleNative("Example")
            setIconSizePxNative(16)
            setTintImageToThemeNative(false)
            setIconNative(icoDataUri)
        }

        awaitEdtAndCondition { fullColorTab.iconLabel.icon != null }
        val fullIcon = assertNotNull(fullColorTab.iconLabel.icon as? ImageIcon)
        val fullImg = fullIcon.image as BufferedImage
        assertEquals(16, fullImg.width)
        assertEquals(16, fullImg.height)

        val topLeft = Color(fullImg.getRGB(1, 1), true)
        val topRight = Color(fullImg.getRGB(14, 1), true)
        val bottomLeft = Color(fullImg.getRGB(1, 14), true)
        assertTrue(topLeft.red > 180 && topLeft.blue < 80, "Untinted favicon top-left must stay red (got $topLeft)")
        assertTrue(topRight.green > 180 && topRight.red < 80, "Untinted favicon top-right must stay green (got $topRight)")
        assertTrue(bottomLeft.blue > 180 && bottomLeft.red < 80, "Untinted favicon bottom-left must stay blue (got $bottomLeft)")

        // Monochrome SVG icon with tintImageToTheme=true must be recolored to tab foreground color
        val blackSvg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 16 16\"><rect x=\"2\" y=\"2\" width=\"12\" height=\"12\" fill=\"#000000\"/></svg>"
        val tintedTab = SwingTabImpl(initialClosable = false).apply {
            titleLabel.foreground = Color(12, 130, 240)
            setTitleNative("Vector")
            setIconSizePxNative(16)
            setTintImageToThemeNative(true)
            setIconNative(blackSvg)
        }
        awaitEdtAndCondition { tintedTab.iconLabel.icon != null }
        val tintedIcon = assertNotNull(tintedTab.iconLabel.icon as? ImageIcon)
        val tintedImg = tintedIcon.image as BufferedImage
        val center = Color(tintedImg.getRGB(8, 8), true)
        assertEquals(12, center.red)
        assertEquals(130, center.green)
        assertEquals(240, center.blue)

        fullColorTab.dispose()
        tintedTab.dispose()
    }

    @Test
    fun swingTabDynamicClosableTogglesBeforeAndAfterAttachAndPinnedHidesCloseButton() {
        val factory = SwingNativeFactory()
        val prevFactory = GenericUI.activeFactory
        GenericUI.initialize(factory)

        val testWindow = Window("SwingTabClosableTest").apply {
            setWindowSize(com.nexustools.genericui.geometry.Size(480, 240))
        }
        val container = TabContainer()
        val preClosableTab = Tab(title = "Pre")
        val postClosableTab = Tab(title = "Post")
        val closeableTab = CloseableTab(title = "Doc", isCloseable = true)

        var closeCount = 0
        closeableTab.onClosed = { closeCount++ }

        try {
            val rootBox = com.nexustools.genericui.component.layout.Container().apply {
                addChild(container)
            }
            testWindow.setContent(rootBox)
            testWindow.show(factory)
            container.ensureNativeImpl()

            container.addTab(preClosableTab, Text("PreBody"))
            val preImpl = assertNotNull(preClosableTab.nativeImpl as? SwingTabImpl)
            assertFalse(preImpl.closeButton.isVisible)

            // Toggle closable AFTER attach
            preImpl.setClosableNative(true)
            assertTrue(preImpl.closeButton.isVisible, "setClosableNative(true) after attach must show Swing closeButton")

            // Pin hides closeButton; unpin restores
            preImpl.setPinnedNative(true)
            assertFalse(preImpl.closeButton.isVisible)
            preImpl.setPinnedNative(false)
            assertTrue(preImpl.closeButton.isVisible)

            // Toggle closable BEFORE attach on SwingTabImpl directly
            val standaloneImpl = SwingTabImpl(initialClosable = false)
            standaloneImpl.setClosableNative(true)
            assertTrue(standaloneImpl.closeButton.isVisible, "setClosableNative(true) before attach must show closeButton")
            val swingContainer = assertNotNull(container.nativeImpl as? SwingTabContainerImpl)
            swingContainer.addTabNative(standaloneImpl, SwingTextImpl().apply { setText("Body") }, -1)
            assertTrue(standaloneImpl.closeButton.isVisible)

            // CloseableTab click invokes onClosed
            container.addTab(closeableTab, Text("DocBody"))
            container.addTab(postClosableTab, Text("PostBody"))
            val closeImpl = assertNotNull(closeableTab.nativeImpl as? SwingTabImpl)
            assertTrue(closeImpl.closeButton.isVisible)
            closeImpl.closeButton.doClick()
            assertEquals(1, closeCount)
        } finally {
            testWindow.close()
            testWindow.dispose()
            GenericUI.activeFactory = prevFactory
        }
    }

    @Test
    fun swingWindowSetWindowIconDecodesPngAndSvgAndPopulatesClientState() {
        val factory = SwingNativeFactory()
        GenericUI.initialize(factory)

        val buf = ImageBuffer(4, 4, PixelFormat.RGBA_8888, ImageColorSpace.SRGB)
        for (y in 0 until 4) {
            for (x in 0 until 4) {
                buf.setPixel(x, y, RgbaColor(40, 120, 240, 1f))
            }
        }
        val pngBytes = LibPngCodec.encode(buf)
        val pngDataUrl = "data:image/png;base64,${Base64.getEncoder().encodeToString(pngBytes)}"

        val winImpl = SwingWindowImpl()
        try {
            assertFalse(winImpl.supportsCustomTitleBar)
            assertTrue(winImpl.getTitleCapabilities().supportsWindowIcon)

            winImpl.setWindowIcon(pngDataUrl)
            assertEquals(pngDataUrl, winImpl.getWindowIconPath())
            assertEquals(pngDataUrl, winImpl.nativeWidget.getClientProperty("windowIcon"))
            assertEquals(true, winImpl.nativeWidget.getClientProperty("windowIconLoaded"))

            val svgSpec = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 16 16\"><circle cx=\"8\" cy=\"8\" r=\"6\" fill=\"#e11d48\"/></svg>"
            winImpl.setWindowIcon(svgSpec)
            assertEquals(svgSpec, winImpl.getWindowIconPath())
            assertEquals(true, winImpl.nativeWidget.getClientProperty("windowIconLoaded"))

            // Clearing reverts client property back to defaultWindowIconPath
            winImpl.setWindowIcon(null)
            assertNotEquals(svgSpec, winImpl.nativeWidget.getClientProperty("windowIcon"))
            assertEquals(winImpl.defaultWindowIconPath, winImpl.nativeWidget.getClientProperty("windowIcon"))
        } finally {
            winImpl.dispose()
        }
    }
}
