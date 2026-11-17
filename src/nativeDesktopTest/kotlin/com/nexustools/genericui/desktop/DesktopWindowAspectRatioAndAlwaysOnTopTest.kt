package com.nexustools.genericui.desktop

import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.shell.DesktopWindowNativeImpl
import com.nexustools.genericui.enums.window.WindowState
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.window.WindowAspectRatioHelper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopWindowAspectRatioAndAlwaysOnTopTest {

    @Test
    fun alwaysOnTopTogglesStateAndAttribute() {
        val widget = DesktopNativeWidget("TestToolkit", "TestWindow")
        val impl = DesktopWindowNativeImpl(widget)

        assertFalse(impl.isAlwaysOnTop())

        impl.setAlwaysOnTop(true)
        assertTrue(impl.isAlwaysOnTop())
        assertEquals(true, widget.attributes["alwaysOnTop"])

        impl.setAlwaysOnTop(false)
        assertFalse(impl.isAlwaysOnTop())
        assertEquals(false, widget.attributes["alwaysOnTop"])
    }

    @Test
    fun aspectRatioClampsNormalWindowSizeAndAllowsUnconstrainedMaximizedAndFullscreen() {
        val widget = DesktopNativeWidget("TestToolkit", "TestWindow")
        val impl = DesktopWindowNativeImpl(widget)
        val sixteenByNine = 16.0 / 9.0

        assertNull(impl.getAspectRatio())

        impl.setAspectRatio(sixteenByNine)
        assertEquals(sixteenByNine, impl.getAspectRatio())
        assertEquals(sixteenByNine, widget.attributes["aspectRatio"])

        impl.setWindowSize(Size(1600, 1000))
        assertEquals(Size(1600, 900), impl.getWindowSize())
        assertTrue(WindowAspectRatioHelper.matchesAspectRatio(impl.getWindowSize(), sixteenByNine, tolerancePx = 2))

        // Respect minWindowSize together with aspect ratio while in NORMAL state
        impl.setMinWindowSize(Size(800, 600))
        impl.setWindowSize(Size(800, 600))
        val minConstrained = impl.getWindowSize()
        assertTrue(minConstrained.width >= 800)
        assertTrue(minConstrained.height >= 600)
        assertTrue(WindowAspectRatioHelper.matchesAspectRatio(minConstrained, sixteenByNine, tolerancePx = 2))

        // Maximized fills display freely without aspect clamping
        impl.setWindowState(WindowState.MAXIMIZED)
        impl.setWindowSize(Size(1920, 1200))
        assertEquals(Size(1920, 1200), impl.getWindowSize())

        // Fullscreen fills display freely without aspect clamping
        impl.setFullscreen(true)
        impl.setWindowSize(Size(2560, 1600))
        assertEquals(Size(2560, 1600), impl.getWindowSize())

        // Returning to NORMAL restores clamping
        impl.setFullscreen(false)
        impl.setWindowState(WindowState.NORMAL)
        impl.setWindowSize(Size(1920, 1200))
        assertEquals(Size(1920, 1080), impl.getWindowSize())

        // Clearing aspect ratio removes constraint and attribute
        impl.setAspectRatio(null)
        assertNull(impl.getAspectRatio())
        assertNull(widget.attributes["aspectRatio"])
        impl.setWindowSize(Size(1400, 950))
        assertEquals(Size(1400, 950), impl.getWindowSize())
    }

    @Test
    fun invalidAspectRatioThrowsIllegalArgument() {
        val impl = DesktopWindowNativeImpl(DesktopNativeWidget("TestToolkit", "TestWindow"))
        assertFailsWith<IllegalArgumentException> { impl.setAspectRatio(0.0) }
        assertFailsWith<IllegalArgumentException> { impl.setAspectRatio(-16.0 / 9.0) }
        assertFailsWith<IllegalArgumentException> { impl.setAspectRatio(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { impl.setAspectRatio(Double.POSITIVE_INFINITY) }
    }
}
