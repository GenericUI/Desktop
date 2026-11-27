@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.component.action.styled.StyledButton
import com.nexustools.genericui.component.layout.Container
import com.nexustools.genericui.component.layout.HStack
import com.nexustools.genericui.component.layout.LayeredContainer
import com.nexustools.genericui.component.layout.VStack
import com.nexustools.genericui.component.shell.Window
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.desktop.core.DesktopInteractiveSurfaceSpec
import com.nexustools.genericui.desktop.core.DesktopStageOverlaySpec
import com.nexustools.genericui.desktop.widget.DesktopStyledButtonNativeImpl
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.css.CssLength
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.windows.GetClientRect
import platform.windows.MapWindowPoints
import platform.windows.POINT
import platform.windows.RECT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WindowsBackdropBlurTest {

    private inline fun withVisualHost(block: (WindowsNativeFactory) -> Unit) {
        val prevInteractive = WindowsApplicationNativeImpl.interactiveWin32GuiEnabled
        val prevVisualTest = Win32NativeWindowHost.visualTestMode
        WindowsApplicationNativeImpl.interactiveWin32GuiEnabled = true
        Win32NativeWindowHost.visualTestMode = true
        Win32NativeWindowHost.closeAllVisualWindows()
        try {
            val factory = WindowsNativeFactory()
            GenericUI.initialize(factory)
            block(factory)
        } finally {
            Win32NativeWindowHost.closeAllVisualWindows()
            Win32NativeWindowHost.visualTestBannerText = null
            Win32NativeWindowHost.visualTestMode = prevVisualTest
            WindowsApplicationNativeImpl.interactiveWin32GuiEnabled = prevInteractive
        }
    }

    private fun buildSplitBgraFrame(width: Int, height: Int): ByteArray {
        val bytes = ByteArray(width * height * 4)
        var i = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val leftHalf = x < width / 2
                bytes[i] = (if (leftHalf) 10 else 240).toByte() // B
                bytes[i + 1] = 10.toByte() // G
                bytes[i + 2] = (if (leftHalf) 240 else 10).toByte() // R
                bytes[i + 3] = 0xFF.toByte() // A
                i += 4
            }
        }
        return bytes
    }

    @Test
    fun testDirect2dOrFallbackEngineBlursSplitRegionWithRoundedCorners() {
        val w = 120
        val h = 80
        val argb = IntArray(w * h) { idx ->
            val x = idx % w
            if (x < w / 2) 0xFFF00A0A.toInt() else 0xFF0A0AF0.toInt()
        }
        val filter = CssFilter(blur = CssLength.px(6f), saturate = 1.15f)
        val ok = Win32Direct2dBackdropBlurEngine.applyBackdropToArgbArray(
            filter = filter,
            argbPixels = argb,
            bufferWidth = w,
            bufferHeight = h,
            regionX = 10,
            regionY = 10,
            regionWidth = 100,
            regionHeight = 60,
            cornerRadiusPx = 12f
        )
        assertTrue(ok, "Expected Win32Direct2dBackdropBlurEngine to apply backdrop filter")

        val seamLeft = argb[40 * w + 59]
        val redCh = (seamLeft ushr 16) and 0xFF
        val blueCh = seamLeft and 0xFF
        assertTrue(blueCh > 20, "Expected blue bleed across seam on Windows, got R=$redCh B=$blueCh")
        assertTrue(redCh < 235, "Expected red attenuation at seam on Windows, got R=$redCh B=$blueCh")

        val corner = argb[10 * w + 10]
        assertEquals(0xFFF00A0A.toInt(), corner)
    }

    @Test
    fun testStyledButtonRetainsAndAppliesBackdropBlurOverLiveStageSurface() = withVisualHost { factory ->
        val rootStack = VStack(gapPx = 0).apply { addClass("w-100 h-100") }
        val stageLayered = LayeredContainer().apply { style.height = CssLength.px(320f) }

        val surfW = 320
        val surfH = 180
        val splitBgra = buildSplitBgraFrame(surfW, surfH)

        val videoSurface = Container("div", "video-stage w-100 h-100")
        val overlayRoot = Container("div", "video-overlay w-100 h-100")
        val controlsLayer = VStack(gapPx = 0).apply {
            addClass("w-100 h-100")
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_CONTROLS_LAYER)
        }
        val centerZone = HStack(gapPx = 0).apply {
            addClass("w-100 h-100")
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_CENTER_ZONE)
        }
        val centerBtn = StyledButton("").apply {
            style.width = CssLength.px(120f)
            style.height = CssLength.px(60f)
            backgroundColor = RgbaColor(0, 0, 0, 0)
            borderColor = RgbaColor(255, 255, 255, 0)
            borderWidthPx = 0f
            setBackdropBlur(8)
        }
        centerZone.addChild(centerBtn)
        controlsLayer.addChild(centerZone)
        overlayRoot.addChild(controlsLayer)
        stageLayered.addChild(videoSurface)
        stageLayered.addChild(overlayRoot)
        rootStack.addChild(stageLayered)

        val window = Window(title = "Windows StyledButton Backdrop Blur Test", initialSize = Size(640, 400)).also {
            it.setContent(rootStack)
        }
        window.show(factory)

        val winImpl = window.ensureNativeImpl() as WindowsWindowNativeImpl
        val btnImpl = centerBtn.ensureNativeImpl() as DesktopStyledButtonNativeImpl<*>
        val resolved = btnImpl.resolveCurrentVisuals()
        assertEquals(8f, resolved.backdropBlurPx)
        assertNotNull(resolved.backdropFilter)

        val stageWidget = videoSurface.ensureNativeImpl()!!.nativeWidget as Win32HwndHandle
        DesktopInteractiveSurfaceSpec(
            surfaceWidth = { surfW },
            surfaceHeight = { surfH },
            swapCount = { 1L },
            captureBgra = { splitBgra },
            preferHardwareStretchBlit = true,
            suppressDefaultBorder = true,
            defaultAspectRatio = 16f / 9f,
            isActiveStream = { false }
        ).attachTo(stageWidget)

        val overlayWidget = overlayRoot.ensureNativeImpl()!!.nativeWidget as Win32HwndHandle
        DesktopStageOverlaySpec(
            isTargetActive = { false },
            isOverlayVisible = { true }
        ).attachTo(overlayWidget)
        overlayRoot.setAttribute("hudVisible", "true")

        val hwnd = assertNotNull(Win32NativeWindowHost.activeHwnd)
        val shot = assertNotNull(Win32NativeWindowHost.captureWindowScreenshot(winImpl, 640, 400))

        val btnHwnd = assertNotNull((btnImpl.nativeWidget as Win32HwndHandle).hwnd)
        val btnBox = memScoped {
            val rc = alloc<RECT>()
            GetClientRect(btnHwnd, rc.ptr)
            val pt = alloc<POINT>()
            pt.x = 0
            pt.y = 0
            MapWindowPoints(btnHwnd, hwnd, pt.ptr, 1u)
            intArrayOf(pt.x, pt.y, pt.x + (rc.right - rc.left), pt.y + (rc.bottom - rc.top))
        }
        assertTrue(btnBox[2] > btnBox[0] && btnBox[3] > btnBox[1], "Expected mounted StyledButton HWND rect")

        val stageRect = Win32NativeWindowHost.lastRenderedVideoStageRect
        val midX = (stageRect[0] + stageRect[2]) / 2
        val sampleX = (midX - 2).coerceIn(btnBox[0], btnBox[2] - 1).coerceIn(0, shot.width - 1)
        val sampleY = (btnBox[1] + 8).coerceIn(btnBox[1], btnBox[3] - 1).coerceIn(0, shot.height - 1)
        val off = (sampleY * shot.width + sampleX) * 4
        val r = shot.bytes[off].toInt() and 0xFF
        val b = shot.bytes[off + 2].toInt() and 0xFF
        assertTrue(
            b > 20,
            "Expected PrintWindow StyledButton backdrop blur to bleed blue across seam (btn=${btnBox.toList()} stage=${stageRect.toList()} sample=($sampleX,$sampleY)), got R=$r B=$b"
        )
        assertTrue(
            r < 235,
            "Expected PrintWindow StyledButton backdrop blur to attenuate red near seam (btn=${btnBox.toList()} stage=${stageRect.toList()} sample=($sampleX,$sampleY)), got R=$r B=$b"
        )
    }
}
