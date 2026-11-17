@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.enums.window.WindowState
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.window.WindowAspectRatioHelper
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toLong
import platform.windows.GetClientRect
import platform.windows.GetWindowLongW
import platform.windows.GetWindowRect
import platform.windows.GWL_EXSTYLE
import platform.windows.RECT
import platform.windows.SendMessageW
import platform.windows.WMSZ_BOTTOM
import platform.windows.WMSZ_BOTTOMRIGHT
import platform.windows.WMSZ_RIGHT
import platform.windows.WMSZ_TOPLEFT
import platform.windows.WM_SIZING
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsWindowAspectRatioAndAlwaysOnTopTest {

    @Test
    fun alwaysOnTopUpdatesExStyleAndStateModel() {
        val impl = WindowsWindowNativeImpl()
        assertFalse(impl.isAlwaysOnTop())
        assertEquals(0L, impl.nativeWidget.dwExStyle and Win32HwndHandle.WS_EX_TOPMOST)

        impl.setAlwaysOnTop(true)
        assertTrue(impl.isAlwaysOnTop())
        assertTrue((impl.nativeWidget.dwExStyle and Win32HwndHandle.WS_EX_TOPMOST) != 0L)
        assertEquals(true, impl.nativeWidget.attributes["alwaysOnTop"])

        impl.setAlwaysOnTop(false)
        assertFalse(impl.isAlwaysOnTop())
        assertEquals(0L, impl.nativeWidget.dwExStyle and Win32HwndHandle.WS_EX_TOPMOST)
        assertEquals(false, impl.nativeWidget.attributes["alwaysOnTop"])
    }

    @Test
    fun syncHostWindowStateAndSizeEnforcesNormalAspectRatioAndAllowsUnconstrainedMaximizedAndFullscreen() {
        val impl = WindowsWindowNativeImpl()
        val sixteenByNine = 16.0 / 9.0

        assertNull(impl.getAspectRatio())
        impl.setAspectRatio(sixteenByNine)
        assertEquals(sixteenByNine, impl.getAspectRatio())

        impl.setWindowSize(Size(1600, 1000))
        assertEquals(Size(1600, 900), impl.getWindowSize())

        // Maximized keeps full work-area bounds unconstrained
        impl.syncHostWindowStateAndSize(
            widthPx = 1920,
            heightPx = 1200,
            fullscreenNow = false,
            zoomedNow = true,
            iconicNow = false
        )
        assertEquals(WindowState.MAXIMIZED, impl.getWindowState())
        assertEquals(Size(1920, 1200), impl.getWindowSize())

        // Fullscreen keeps full monitor bounds unconstrained
        impl.syncHostWindowStateAndSize(
            widthPx = 2560,
            heightPx = 1600,
            fullscreenNow = true,
            zoomedNow = false,
            iconicNow = false
        )
        assertEquals(WindowState.FULLSCREEN, impl.getWindowState())
        assertTrue(impl.isFullscreen())
        assertEquals(Size(2560, 1600), impl.getWindowSize())

        // Restoring to NORMAL re-applies 16:9 constraint
        impl.syncHostWindowStateAndSize(
            widthPx = 1280,
            heightPx = 900,
            fullscreenNow = false,
            zoomedNow = false,
            iconicNow = false
        )
        assertEquals(WindowState.NORMAL, impl.getWindowState())
        assertFalse(impl.isFullscreen())
        assertEquals(Size(1280, 720), impl.getWindowSize())

        impl.setAspectRatio(null)
        assertFailsWith<IllegalArgumentException> { impl.setAspectRatio(0.0) }
        assertFailsWith<IllegalArgumentException> { impl.setAspectRatio(-16.0 / 9.0) }
    }

    @Test
    fun adjustRectForAspectRatioRespectsNonClientChromeDraggedEdgesAndMinTrack() = memScoped {
        val frameW = 16
        val frameH = 39
        val ratio = 16.0 / 9.0
        val rect = alloc<RECT>()

        // Right-edge drag keeps left & top anchored and adjusts height to 16:9 client area
        rect.left = 100
        rect.top = 100
        rect.right = 100 + 1600 + frameW
        rect.bottom = 100 + 1000 + frameH
        Win32NativeWindowHost.adjustRectForAspectRatio(
            rect = rect,
            edge = WMSZ_RIGHT,
            frameW = frameW,
            frameH = frameH,
            currentClientW = 1280,
            currentClientH = 720,
            minTrackW = 320 + frameW,
            minTrackH = 180 + frameH,
            aspectRatio = ratio
        )
        assertEquals(100, rect.left)
        assertEquals(100, rect.top)
        assertEquals(1600, (rect.right - rect.left) - frameW)
        assertEquals(900, (rect.bottom - rect.top) - frameH)

        // Bottom-edge drag preserves height and adjusts width to 16:9 client area
        rect.left = 100
        rect.top = 100
        rect.right = 100 + 1000 + frameW
        rect.bottom = 100 + 900 + frameH
        Win32NativeWindowHost.adjustRectForAspectRatio(
            rect = rect,
            edge = WMSZ_BOTTOM,
            frameW = frameW,
            frameH = frameH,
            currentClientW = 1280,
            currentClientH = 720,
            minTrackW = 320 + frameW,
            minTrackH = 180 + frameH,
            aspectRatio = ratio
        )
        assertEquals(1600, (rect.right - rect.left) - frameW)
        assertEquals(900, (rect.bottom - rect.top) - frameH)

        // Top-left corner drag anchors right & bottom and enforces minimum track size
        rect.left = 1500
        rect.top = 1100
        rect.right = 1716
        rect.bottom = 1239
        Win32NativeWindowHost.adjustRectForAspectRatio(
            rect = rect,
            edge = WMSZ_TOPLEFT,
            frameW = frameW,
            frameH = frameH,
            currentClientW = 1280,
            currentClientH = 720,
            minTrackW = 640 + frameW,
            minTrackH = 360 + frameH,
            aspectRatio = ratio
        )
        assertEquals(1716, rect.right)
        assertEquals(1239, rect.bottom)
        val clientSize = Size((rect.right - rect.left) - frameW, (rect.bottom - rect.top) - frameH)
        assertTrue(clientSize.width >= 640)
        assertTrue(clientSize.height >= 360)
        assertTrue(WindowAspectRatioHelper.matchesAspectRatio(clientSize, ratio, tolerancePx = 2))
    }

    @Test
    fun liveWin32HostWindowAppliesTopmostExStyleAndEnforcesWmSizingAspectRatio() = memScoped {
        val prevVisualTest = Win32NativeWindowHost.visualTestMode
        Win32NativeWindowHost.visualTestMode = true
        try {
            val impl = WindowsWindowNativeImpl()
            impl.setAlwaysOnTop(true)
            impl.setAspectRatio(16.0 / 9.0)
            impl.setWindowSize(Size(1280, 720))

            Win32NativeWindowHost.createAndShowWin32Window(impl, startHidden = true)
            Win32NativeWindowHost.pumpVisualWindowEvents(32L)

            val hwnd = Win32NativeWindowHost.activeHwnd
            if (hwnd != null) {
                assertTrue((GetWindowLongW(hwnd, GWL_EXSTYLE) and platform.windows.WS_EX_TOPMOST) != 0)

                impl.setAlwaysOnTop(false)
                Win32NativeWindowHost.pumpVisualWindowEvents(32L)
                assertEquals(0, GetWindowLongW(hwnd, GWL_EXSTYLE) and platform.windows.WS_EX_TOPMOST)

                val winRect = alloc<RECT>()
                val clientRect = alloc<RECT>()
                assertTrue(GetWindowRect(hwnd, winRect.ptr) != 0)
                assertTrue(GetClientRect(hwnd, clientRect.ptr) != 0)
                val frameW = ((winRect.right - winRect.left) - (clientRect.right - clientRect.left)).coerceAtLeast(0)
                val frameH = ((winRect.bottom - winRect.top) - (clientRect.bottom - clientRect.top)).coerceAtLeast(0)

                val dragRect = alloc<RECT>()
                dragRect.left = 100
                dragRect.top = 100
                dragRect.right = 100 + 1600 + frameW
                dragRect.bottom = 100 + 1200 + frameH

                val sizingResult = SendMessageW(
                    hwnd,
                    WM_SIZING.toUInt(),
                    WMSZ_BOTTOMRIGHT.toULong(),
                    dragRect.ptr.toLong()
                ).toLong()
                assertEquals(1L, sizingResult)

                val clampedClient = Size(
                    (dragRect.right - dragRect.left) - frameW,
                    (dragRect.bottom - dragRect.top) - frameH
                )
                assertTrue(WindowAspectRatioHelper.matchesAspectRatio(clampedClient, 16.0 / 9.0, tolerancePx = 2))

                // While fullscreen, WM_SIZING must not impose windowed aspect clamping
                Win32NativeWindowHost.setWindowFullscreen(impl, true, null)
                dragRect.left = 0
                dragRect.top = 0
                dragRect.right = 1920 + frameW
                dragRect.bottom = 1200 + frameH
                SendMessageW(
                    hwnd,
                    WM_SIZING.toUInt(),
                    WMSZ_BOTTOMRIGHT.toULong(),
                    dragRect.ptr.toLong()
                )
                assertEquals(1920 + frameW, dragRect.right - dragRect.left)
                assertEquals(1200 + frameH, dragRect.bottom - dragRect.top)
            }
        } finally {
            Win32NativeWindowHost.closeAllVisualWindows()
            Win32NativeWindowHost.visualTestMode = prevVisualTest
        }
    }
}
