package com.nexustools.genericui.swing

import com.nexustools.genericui.enums.window.WindowState
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.window.WindowAspectRatioHelper
import java.awt.GraphicsEnvironment
import javax.swing.JFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwingWindowAspectRatioAndAlwaysOnTopTest {

    @Test
    fun alwaysOnTopTogglesFrame() {
        val impl = SwingWindowImpl()
        try {
            assertFalse(impl.isAlwaysOnTop())
            if (!GraphicsEnvironment.isHeadless()) {
                assertFalse(impl.jFrame?.isAlwaysOnTop == true)
            }

            impl.setAlwaysOnTop(true)
            assertTrue(impl.isAlwaysOnTop())
            assertEquals(true, impl.nativeWidget.getClientProperty("alwaysOnTop"))
            if (!GraphicsEnvironment.isHeadless()) {
                assertTrue(impl.jFrame?.isAlwaysOnTop == true)
            }

            impl.setAlwaysOnTop(false)
            assertFalse(impl.isAlwaysOnTop())
            assertEquals(false, impl.nativeWidget.getClientProperty("alwaysOnTop"))
            if (!GraphicsEnvironment.isHeadless()) {
                assertFalse(impl.jFrame?.isAlwaysOnTop == true)
            }
        } finally {
            impl.closeWindow()
        }
    }

    @Test
    fun aspectRatioEnforcesClientSizeAccountingForInsetsAndClearsInMaximizedAndFullscreen() {
        val impl = SwingWindowImpl()
        try {
            val sixteenByNine = 16.0 / 9.0
            assertNull(impl.getAspectRatio())

            impl.setAspectRatio(sixteenByNine)
            assertEquals(sixteenByNine, impl.getAspectRatio())
            assertEquals(sixteenByNine, impl.nativeWidget.getClientProperty("aspectRatio"))

            // Programmatic setWindowSize clamps client size in NORMAL state
            impl.setWindowSize(Size(1600, 1000))
            assertEquals(Size(1600, 900), impl.getWindowSize())

            // Simulate user dragging bottom/height outward beyond 2px tolerance with non-zero frame insets
            val resizedClient = impl.handleHostFrameResized(
                outerWidth = 1600 + 16,
                outerHeight = 1100 + 38,
                insetsLeft = 8,
                insetsTop = 30,
                insetsRight = 8,
                insetsBottom = 8,
                extendedState = JFrame.NORMAL
            )
            assertTrue(WindowAspectRatioHelper.matchesAspectRatio(resizedClient, sixteenByNine, tolerancePx = 2))
            assertEquals(resizedClient, impl.getWindowSize())

            // Small <= 2px rounding jitter is tolerated without extra resize
            val tolerated = impl.handleHostFrameResized(
                outerWidth = resizedClient.width + 16,
                outerHeight = resizedClient.height + 1 + 38,
                insetsLeft = 8,
                insetsTop = 30,
                insetsRight = 8,
                insetsBottom = 8,
                extendedState = JFrame.NORMAL
            )
            assertEquals(Size(resizedClient.width, resizedClient.height + 1), tolerated)

            // Maximized state ignores aspect clamping so window can fill the monitor work area freely
            impl.setWindowState(WindowState.MAXIMIZED)
            val maxClient = impl.handleHostFrameResized(
                outerWidth = 1920 + 16,
                outerHeight = 1200 + 38,
                insetsLeft = 8,
                insetsTop = 30,
                insetsRight = 8,
                insetsBottom = 8,
                extendedState = JFrame.MAXIMIZED_BOTH
            )
            assertEquals(Size(1920, 1200), maxClient)
            assertEquals(WindowState.MAXIMIZED, impl.getWindowState())

            // Restoring from MAXIMIZED to NORMAL re-applies 16:9 clamping
            impl.setWindowState(WindowState.NORMAL)
            val restoredClient = impl.handleHostFrameResized(
                outerWidth = 1600 + 16,
                outerHeight = 1000 + 38,
                insetsLeft = 8,
                insetsTop = 30,
                insetsRight = 8,
                insetsBottom = 8,
                extendedState = JFrame.NORMAL
            )
            assertEquals(WindowState.NORMAL, impl.getWindowState())
            assertTrue(WindowAspectRatioHelper.matchesAspectRatio(restoredClient, sixteenByNine, tolerancePx = 2))

            // Fullscreen leaves display dimensions unconstrained and restores clamping upon exit
            impl.setFullscreen(true)
            impl.setWindowSize(Size(2560, 1600))
            assertEquals(Size(2560, 1600), impl.getWindowSize())

            impl.setFullscreen(false)
            assertEquals(WindowState.NORMAL, impl.getWindowState())
            assertTrue(WindowAspectRatioHelper.matchesAspectRatio(impl.getWindowSize(), sixteenByNine, tolerancePx = 2))
        } finally {
            impl.closeWindow()
        }
    }

    @Test
    fun invalidAspectRatioThrowsIllegalArgument() {
        val impl = SwingWindowImpl()
        try {
            assertFailsWith<IllegalArgumentException> { impl.setAspectRatio(0.0) }
            assertFailsWith<IllegalArgumentException> { impl.setAspectRatio(-1.0) }
            assertFailsWith<IllegalArgumentException> { impl.setAspectRatio(Double.NaN) }
            assertFailsWith<IllegalArgumentException> { impl.setAspectRatio(Double.POSITIVE_INFINITY) }
        } finally {
            impl.closeWindow()
        }
    }
}
