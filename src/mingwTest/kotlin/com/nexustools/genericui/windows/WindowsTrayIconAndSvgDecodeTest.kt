@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.image.ImageDecodeOptions
import com.nexustools.genericui.image.ImageFormat
import com.nexustools.genericui.image.ImageLoader
import com.nexustools.genericui.image.ImageSource
import com.nexustools.genericui.res.DrawableResource
import com.nexustools.genericui.res.Resources
import com.nexustools.genericui.resources.ResourceID
import com.nexustools.genericui.resources.ResourceType
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.windows.BITMAP
import platform.windows.DeleteObject
import platform.windows.DestroyIcon
import platform.windows.GetIconInfo
import platform.windows.GetObjectW
import platform.windows.HICON
import platform.windows.ICONINFO
import platform.windows.IDI_APPLICATION
import platform.windows.IsWindow
import platform.windows.LoadIconW
import platform.windows.MSG
import platform.windows.PM_REMOVE
import platform.windows.PeekMessageW
import platform.windows.SendMessageW
import platform.windows.WM_QUIT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class WindowsTrayIconAndSvgDecodeTest {

    private fun runWithDeadline(timeoutSeconds: Int = 10, block: () -> Unit) {
        val deadline = TimeSource.Monotonic.markNow() + timeoutSeconds.seconds
        runBlocking {
            withTimeout(timeoutSeconds.seconds) {
                block()
            }
        }
        assertFalse(deadline.hasPassedNow(), "Test exceeded ${timeoutSeconds}s deadline")
    }

    private fun assertValidColorBitmapHIcon(hIcon: HICON?, label: String) {
        val nonNullIcon = assertNotNull(hIcon, "$label must resolve to a non-null HICON")
        memScoped {
            val info = alloc<ICONINFO>()
            assertTrue(GetIconInfo(nonNullIcon, info.ptr) != 0, "$label GetIconInfo must succeed")
            try {
                val colorBmp = assertNotNull(info.hbmColor, "$label ICONINFO.hbmColor must be non-null")
                val bm = alloc<BITMAP>()
                val copied = GetObjectW(colorBmp, sizeOf<BITMAP>().toInt(), bm.ptr)
                assertTrue(copied > 0, "$label GetObjectW on hbmColor must succeed")
                assertTrue(
                    bm.bmWidth > 0 && bm.bmHeight > 0,
                    "$label icon color bitmap must have non-zero size (${bm.bmWidth}x${bm.bmHeight})"
                )
            } finally {
                info.hbmColor?.let { DeleteObject(it) }
                info.hbmMask?.let { DeleteObject(it) }
            }
        }
    }

    @Test
    fun wicCodecDecodesSvgToNonEmptyImageBuffer() = runWithDeadline {
        val prevCodec = ImageLoader.activeNativeCodec
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32" width="32" height="32">
              <rect x="2" y="2" width="28" height="28" fill="#1E88E5"/>
              <circle cx="16" cy="16" r="8" fill="#FFC107" stroke="#FF5722" stroke-width="2"/>
              <path d="M6 26 L26 6" stroke="#FFFFFF" stroke-width="2"/>
            </svg>
        """.trimIndent()
        val svgBytes = svg.encodeToByteArray()

        try {
            val codec = WindowsWicImageCodecNativeImpl()
            val metadata = codec.probeMetadata(ImageSource.fromBytes(svgBytes, ImageFormat.SVG))
            assertNotNull(metadata, "probeMetadata must succeed for SVG bytes")
            assertEquals(ImageFormat.SVG, metadata.format)
            assertEquals(32, metadata.width)
            assertEquals(32, metadata.height)

            val buffer = codec.decode(
                ImageSource.fromBytes(svgBytes, ImageFormat.SVG),
                ImageDecodeOptions(targetWidth = 32, targetHeight = 32)
            )
            assertNotNull(buffer, "WindowsWicImageCodecNativeImpl.decode must rasterize SVG bytes")
            assertEquals(32, buffer.width)
            assertEquals(32, buffer.height)
            assertFalse(buffer.isFullyTransparent(), "Decoded SVG buffer must not be fully transparent")

            var opaquePixelCount = 0
            for (y in 0 until buffer.height) {
                for (x in 0 until buffer.width) {
                    if (buffer.getPixel(x, y).a > 0) {
                        opaquePixelCount++
                    }
                }
            }
            assertTrue(opaquePixelCount > 200, "Expected substantial opaque SVG raster coverage, got $opaquePixelCount")

            val rectPixel = buffer.getPixel(4, 4)
            assertEquals(0x1E, rectPixel.r)
            assertEquals(0x88, rectPixel.g)
            assertEquals(0xE5, rectPixel.b)
            assertEquals(255, rectPixel.a)

            val circlePixel = buffer.getPixel(13, 13)
            assertEquals(0xFF, circlePixel.r)
            assertEquals(0xC1, circlePixel.g)
            assertEquals(0x07, circlePixel.b)
            assertEquals(255, circlePixel.a)

            val pathPixel = buffer.getPixel(16, 16)
            assertEquals(255, pathPixel.r)
            assertEquals(255, pathPixel.g)
            assertEquals(255, pathPixel.b)
            assertEquals(255, pathPixel.a)
        } finally {
            ImageLoader.activeNativeCodec = prevCodec
        }
    }

    @Test
    fun systemTrayResolvesSvgDrawableResourceIntoDistinctHIcon() = runWithDeadline {
        val prevCodec = ImageLoader.activeNativeCodec
        val appIconId = ResourceID(0x7f010001, ResourceType.DRAWABLE, "icon", "app/icon.svg")
        val customIconId = ResourceID(0x7f010099, ResourceType.DRAWABLE, "tray_custom_svg", "app/drawable/tray_custom_svg.svg")
        val prevAppDrawable = Resources.activeTable.getDrawable(appIconId)
        val prevCustomDrawable = Resources.activeTable.getDrawable(customIconId)

        val appSvg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48" width="48" height="48">
              <rect x="4" y="4" width="40" height="40" fill="#009688"/>
              <circle cx="24" cy="24" r="12" fill="#FF7043"/>
            </svg>
        """.trimIndent()
        val customSvg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32" width="32" height="32">
              <circle cx="16" cy="16" r="13" fill="#7E57C2" stroke="#FFFFFF" stroke-width="2"/>
              <path d="M10 16 L15 21 L23 11" stroke="#FFFFFF" stroke-width="2.5"/>
            </svg>
        """.trimIndent()

        var tray: WindowsSystemTrayNativeImpl? = null
        var appHIcon: HICON? = null
        var ownsAppHIcon = false
        var customHIcon: HICON? = null
        var ownsCustomHIcon = false

        try {
            Resources.activeTable.registerDrawable(
                DrawableResource(
                    resId = appIconId,
                    format = "svg",
                    svgSource = appSvg,
                    viewBoxWidth = 48f,
                    viewBoxHeight = 48f
                )
            )
            Resources.activeTable.registerDrawable(
                DrawableResource(
                    resId = customIconId,
                    format = "svg",
                    svgSource = customSvg,
                    viewBoxWidth = 32f,
                    viewBoxHeight = 32f
                )
            )

            tray = WindowsSystemTrayNativeImpl()
            val defaultSystemIcon = LoadIconW(null, IDI_APPLICATION)

            val resolvedApp = tray.resolveTrayHIconForTesting("res://app/icon")
            appHIcon = resolvedApp.first
            ownsAppHIcon = resolvedApp.second
            assertValidColorBitmapHIcon(appHIcon, "res://app/icon")
            assertNotEquals(defaultSystemIcon, appHIcon, "res://app/icon must not fall back to IDI_APPLICATION")

            val resolvedCustom = tray.resolveTrayHIconForTesting(customIconId.toResourceUri())
            customHIcon = resolvedCustom.first
            ownsCustomHIcon = resolvedCustom.second
            assertTrue(ownsCustomHIcon, "Custom SVG drawable HICON must be owned by caller")
            assertValidColorBitmapHIcon(customHIcon, customIconId.toResourceUri())
            assertNotEquals(defaultSystemIcon, customHIcon, "Custom SVG tray icon must not fall back to IDI_APPLICATION")
            assertNotEquals(appHIcon, customHIcon, "Distinct SVG resources must produce distinct HICON handles")
        } finally {
            if (ownsAppHIcon && appHIcon != null) {
                DestroyIcon(appHIcon)
            }
            if (ownsCustomHIcon && customHIcon != null) {
                DestroyIcon(customHIcon)
            }
            tray?.dispose()
            if (prevAppDrawable != null) {
                Resources.activeTable.registerDrawable(prevAppDrawable)
            }
            if (prevCustomDrawable != null) {
                Resources.activeTable.registerDrawable(prevCustomDrawable)
            }
            ImageLoader.activeNativeCodec = prevCodec
        }
    }

    @Test
    fun headlessTrayLoopHandlesTaskbarCreatedMessage() = runWithDeadline {
        val prevVisual = Win32NativeWindowHost.visualTestMode
        Win32NativeWindowHost.visualTestMode = true
        try {
            val hwnd = WindowsHeadlessTrayLoop.ensureTrayMessageHwnd()
            assertNotNull(hwnd, "ensureTrayMessageHwnd must create a valid hidden tray host HWND")
            assertTrue(IsWindow(hwnd) != 0, "Headless tray HWND must be a live Win32 window")
            assertTrue(WindowsHeadlessTrayLoop.isTrayMessageHwnd(hwnd))

            val taskbarCreatedMsg = Win32NativeWindowHost.WM_TASKBARCREATED
            assertTrue(taskbarCreatedMsg != 0u, "RegisterWindowMessageW(\"TaskbarCreated\") must return a valid message ID")

            val result = SendMessageW(hwnd, taskbarCreatedMsg, 0uL, 0L)
            assertEquals(0L, result, "win32HeadlessTrayWndProc must handle WM_TASKBARCREATED and return 0")
            assertFalse(WindowsSystemTrayNativeImpl.handleRetryTimer(hwnd, 1), "Non-retry timer IDs must not be consumed")
        } finally {
            WindowsHeadlessTrayLoop.destroyTrayMessageHwnd()
            memScoped {
                val msg = alloc<MSG>()
                PeekMessageW(msg.ptr, null, WM_QUIT.toUInt(), WM_QUIT.toUInt(), PM_REMOVE.toUInt())
            }
            Win32NativeWindowHost.visualTestMode = prevVisual
        }
    }
}
