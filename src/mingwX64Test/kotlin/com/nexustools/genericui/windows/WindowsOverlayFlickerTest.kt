@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.component.action.StyledButton
import com.nexustools.genericui.component.form.Slider
import com.nexustools.genericui.component.layout.Container
import com.nexustools.genericui.component.layout.HStack
import com.nexustools.genericui.component.layout.LayeredContainer
import com.nexustools.genericui.component.layout.StyledContainer
import com.nexustools.genericui.component.layout.VStack
import com.nexustools.genericui.component.media.Image
import com.nexustools.genericui.component.shell.Window
import com.nexustools.genericui.component.typography.Text
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.desktop.core.DesktopInteractiveSurfaceSpec
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.core.DesktopStageOverlaySpec
import com.nexustools.genericui.enums.bootstrap.BootstrapSize
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssLength
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.windows.GetClientRect
import platform.windows.HWND
import platform.windows.MapWindowPoints
import platform.windows.POINT
import platform.windows.RECT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WindowsOverlayFlickerTest {

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

    private fun buildFilledBgraFrame(width: Int, height: Int, b: Int, g: Int, r: Int): ByteArray {
        val bytes = ByteArray(width * height * 4)
        val bByte = b.toByte()
        val gByte = g.toByte()
        val rByte = r.toByte()
        val aByte = 0xFF.toByte()
        var i = 0
        while (i < bytes.size) {
            bytes[i] = bByte
            bytes[i + 1] = gByte
            bytes[i + 2] = rByte
            bytes[i + 3] = aByte
            i += 4
        }
        return bytes
    }

    private fun readScreenshotWordAt(shot: ImageBuffer, x: Int, y: Int): UInt {
        if (x !in 0 until shot.width || y !in 0 until shot.height) return 0u
        val off = (y * shot.width + x) * 4
        val r = shot.bytes[off].toInt() and 0xFF
        val g = shot.bytes[off + 1].toInt() and 0xFF
        val b = shot.bytes[off + 2].toInt() and 0xFF
        return ((r shl 16) or (g shl 8) or b).toUInt()
    }

    private fun hwndBoundsInHost(childHwnd: HWND, hostHwnd: HWND): IntArray = memScoped {
        val rc = alloc<RECT>()
        GetClientRect(childHwnd, rc.ptr)
        val pt = alloc<POINT>()
        pt.x = 0
        pt.y = 0
        MapWindowPoints(childHwnd, hostHwnd, pt.ptr, 1u)
        intArrayOf(pt.x, pt.y, pt.x + (rc.right - rc.left), pt.y + (rc.bottom - rc.top))
    }

    @Test
    fun testMultiFrameVideoStageWithOverlayAtomicCompositionAndNoCalibrationBleed() = withVisualHost { factory ->
        Win32NativeWindowHost.visualTestBannerText = "Overlay Flicker Test: Multi-frame video stage composition"

        val rootStack = VStack(gapPx = 8).apply { addClass("w-100 h-100") }
        val stageLayered = LayeredContainer().apply { style.height = CssLength.px(420f) }

        val surfW = 320
        val surfH = 180
        var currentSwapCount = 1L
        var currentFrameBgra = buildFilledBgraFrame(surfW, surfH, b = 200, g = 40, r = 30)

        val videoSurface = Container("div", "video-stage w-100 h-100")
        val overlayRoot = Container("div", "video-overlay w-100 h-100")

        val controlsLayer = VStack(gapPx = 0).apply {
            addClass("w-100 h-100")
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_CONTROLS_LAYER)
        }
        val topBar = StyledContainer().apply {
            backgroundColor = RgbaColor(18, 20, 26, 175)
            style.height = CssLength.px(36f)
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_TOP_BAR)
        }
        val titleText = Text("Episode 1 - Atomic Overlay Sync")
        val statusText = Text("Direct Play 1080p")
        topBar.addChild(titleText)
        topBar.addChild(statusText)

        val centerZone = VStack(gapPx = 0).apply {
            addClass("w-100")
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_CENTER_ZONE)
        }

        val bottomBar = StyledContainer().apply {
            backgroundColor = RgbaColor(18, 20, 26, 175)
            style.height = CssLength.px(64f)
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_BOTTOM_BAR)
        }
        val seekContainer = VStack(gapPx = 0).apply {
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_SEEK_CONTAINER)
        }
        val seekSlider = Slider(min = 0.0, max = 100.0, value = 10.0, step = 1.0)
        seekContainer.addChild(seekSlider)

        val controlsRow = HStack(gapPx = 8).apply {
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_CONTROLS_ROW)
        }
        val controlsLeft = HStack(gapPx = 6).apply {
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_CONTROLS_LEFT)
        }
        val playPauseBtn = StyledButton("Pause", BootstrapType.PRIMARY, BootstrapSize.SM)
        val timePill = StyledContainer().apply {
            backgroundColor = RgbaColor(18, 20, 26, 180)
            style.width = CssLength.px(130f)
            style.height = CssLength.px(26f)
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_TIME_READOUT)
        }
        val timeReadoutText = Text("0:01 / 24:00")
        timePill.addChild(timeReadoutText)
        controlsLeft.addChild(playPauseBtn)
        controlsLeft.addChild(timePill)

        val controlsRight = HStack(gapPx = 6).apply {
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_CONTROLS_RIGHT)
        }
        val fullscreenBtn = StyledButton("Fullscreen", BootstrapType.SECONDARY, BootstrapSize.SM)
        controlsRight.addChild(fullscreenBtn)

        controlsRow.addChild(controlsLeft)
        controlsRow.addChild(controlsRight)
        bottomBar.addChild(seekContainer)
        bottomBar.addChild(controlsRow)

        val captionBanner = StyledContainer().apply {
            backgroundColor = RgbaColor(15, 18, 24, 185)
            style.height = CssLength.px(32f)
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_CAPTION_BANNER)
        }
        val captionText = Text("Locked Subtitle Line Frame 1")
        captionBanner.addChild(captionText)

        controlsLayer.addChild(topBar)
        controlsLayer.addChild(centerZone)
        controlsLayer.addChild(bottomBar)
        overlayRoot.addChild(controlsLayer)
        overlayRoot.addChild(captionBanner)

        stageLayered.addChild(videoSurface)
        stageLayered.addChild(overlayRoot)
        rootStack.addChild(stageLayered)

        val window = Window(title = "Overlay Flicker Test", initialSize = Size(800, 520)).also {
            it.setContent(rootStack)
        }
        window.show(factory)

        val winImpl = window.ensureNativeImpl() as WindowsWindowNativeImpl
        val stageWidget = videoSurface.ensureNativeImpl()!!.nativeWidget as Win32HwndHandle
        DesktopInteractiveSurfaceSpec(
            surfaceWidth = { surfW },
            surfaceHeight = { surfH },
            swapCount = { currentSwapCount },
            captureBgra = { currentFrameBgra },
            preferHardwareStretchBlit = true,
            suppressDefaultBorder = true,
            defaultAspectRatio = 16f / 9f,
            isActiveStream = { true },
            activeCaptionText = { "Locked Subtitle Line Frame $currentSwapCount" }
        ).attachTo(stageWidget)

        val overlayWidget = overlayRoot.ensureNativeImpl()!!.nativeWidget as Win32HwndHandle
        DesktopStageOverlaySpec(
            isTargetActive = { true },
            isOverlayVisible = { true },
            activeCaptionText = { "Locked Subtitle Line Frame $currentSwapCount" }
        ).attachTo(overlayWidget)

        val hwnd = assertNotNull(Win32NativeWindowHost.activeHwnd, "Expected active HWND")
        Win32NativeWindowHost.layoutAndSyncWindowChildren(hwnd)
        Win32NativeWindowHost.pumpVisualWindowEvents(24L)
        Win32NativeWindowHost.resetFrameMetrics()

        val layeredHwnd = assertNotNull((stageLayered.ensureNativeImpl()!!.nativeWidget as Win32HwndHandle).hwnd)
        assertTrue(Win32LayeredStageHostHwnd.isStageHostHwnd(layeredHwnd), "LayeredContainer must host Win32LayeredStageHostHwnd")

        val stageRect = Win32NativeWindowHost.lastRenderedVideoStageRect
        assertTrue(stageRect[2] > stageRect[0] && stageRect[3] > stageRect[1], "Expected valid video stage rect")

        val topBox = hwndBoundsInHost(assertNotNull((topBar.ensureNativeImpl()!!.nativeWidget as Win32HwndHandle).hwnd), hwnd)
        val pillBox = hwndBoundsInHost(assertNotNull((timePill.ensureNativeImpl()!!.nativeWidget as Win32HwndHandle).hwnd), hwnd)
        val topMidX = ((topBox[0] + topBox[2]) / 2).coerceIn(0, 799)
        val topMidY = ((topBox[1] + topBox[3]) / 2).coerceIn(0, 519)
        val pillMidX = ((pillBox[0] + pillBox[2]) / 2).coerceIn(0, 799)
        val pillMidY = ((pillBox[1] + pillBox[3]) / 2).coerceIn(0, 519)

        val frameColorsRgb = listOf(
            Triple(30, 60, 210),
            Triple(200, 40, 40),
            Triple(30, 190, 60),
            Triple(180, 160, 30),
            Triple(140, 40, 190),
            Triple(30, 170, 180)
        )

        var whiteBorderCorruptionCount = 0
        var uncompositedTopHudFrames = 0
        var uncompositedBottomHudFrames = 0

        for (step in 1..12) {
            val (r, g, b) = frameColorsRgb[(step - 1) % frameColorsRgb.size]
            val rawVideoWord = ((r and 0xFF) shl 16).toUInt() or ((g and 0xFF) shl 8).toUInt() or (b and 0xFF).toUInt()
            currentFrameBgra = buildFilledBgraFrame(surfW, surfH, b = b, g = g, r = r)
            currentSwapCount = step.toLong()

            seekSlider.value = (step * 5).toDouble()
            timeReadoutText.text = if (step % 2 == 0) "0:0$step / 24:00" else "01:0$step / 01:24:00"
            captionText.text = "Locked Subtitle Line Frame $step"

            Win32LayeredStageHostHwnd.notifyStageSurfaceFrameAdvanced(layeredHwnd)
            val shot = assertNotNull(Win32NativeWindowHost.captureWindowScreenshot(winImpl, 800, 520))

            if (readScreenshotWordAt(shot, topMidX, topMidY) == rawVideoWord) {
                uncompositedTopHudFrames++
            }
            if (readScreenshotWordAt(shot, pillMidX, pillMidY) == rawVideoWord) {
                uncompositedBottomHudFrames++
            }
            val borderLeftTop = readScreenshotWordAt(shot, stageRect[0], (stageRect[1] + 16).coerceIn(0, shot.height - 1))
            val borderLeftBottom = readScreenshotWordAt(shot, stageRect[0], (stageRect[3] - 30).coerceIn(0, shot.height - 1))
            if (borderLeftTop == 0xFFFFFFu || borderLeftBottom == 0xFFFFFFu) {
                whiteBorderCorruptionCount++
            }
        }

        val metrics = Win32NativeWindowHost.getFrameMetricsSnapshot()
        assertTrue(metrics.videoStageOnlyPaints >= 12, "Expected isolated stage paints across 12 frames, got ${metrics.videoStageOnlyPaints}")
        assertEquals(0, uncompositedTopHudFrames, "Top overlay HUD child HWND must remain composited on every video frame")
        assertEquals(0, uncompositedBottomHudFrames, "Bottom overlay HUD pill child HWND must remain composited on every video frame")
        assertEquals(0, whiteBorderCorruptionCount, "Stage host must never leak 0xFFFFFF white border artifacts")
    }

    @Test
    fun testCanvasBgraSurfacePresentsUpdatedFramesViaLayeredStageAndPrintWindow() = withVisualHost { factory ->
        Win32NativeWindowHost.visualTestBannerText = "Overlay Flicker Test: Canvas BGRA Swap Presentation"

        val rootStack = VStack(gapPx = 8).apply { addClass("w-100 h-100") }
        val surfW = 240
        val surfH = 160
        var swapCount = 1L
        var frameBgra = buildFilledBgraFrame(surfW, surfH, b = 180, g = 30, r = 30)

        val canvasSurface = Container("div", "canvas-surface w-100").apply {
            style.height = CssLength.px(240f)
        }
        rootStack.addChild(canvasSurface)

        val window = Window(title = "Canvas BGRA Swap Test", initialSize = Size(480, 320)).also {
            it.setContent(rootStack)
        }
        window.show(factory)

        val winImpl = window.ensureNativeImpl() as WindowsWindowNativeImpl
        val canvasWidget = canvasSurface.ensureNativeImpl()!!.nativeWidget as Win32HwndHandle
        DesktopInteractiveSurfaceSpec(
            surfaceWidth = { surfW },
            surfaceHeight = { surfH },
            swapCount = { swapCount },
            captureBgra = { frameBgra },
            preferHardwareStretchBlit = false,
            suppressDefaultBorder = true
        ).attachTo(canvasWidget)

        val hwnd = assertNotNull(Win32NativeWindowHost.activeHwnd)
        Win32NativeWindowHost.layoutAndSyncWindowChildren(hwnd)
        val shot1 = assertNotNull(Win32NativeWindowHost.captureWindowScreenshot(winImpl, 480, 320))
        val cHwnd = assertNotNull(canvasWidget.hwnd)
        val cBox = hwndBoundsInHost(cHwnd, hwnd)
        val cx = (cBox[0] + cBox[2]) / 2
        val cy = (cBox[1] + cBox[3]) / 2
        val pixel1 = readScreenshotWordAt(shot1, cx, cy)
        val blue1 = (pixel1 and 0xFFu).toInt()
        val green1 = ((pixel1 shr 8) and 0xFFu).toInt()
        assertTrue(blue1 > green1 + 60, "Frame 1 must be blue-dominant, got RGB=0x${pixel1.toString(16)}")

        // Present Green frame 2 via VBlank step
        frameBgra = buildFilledBgraFrame(surfW, surfH, b = 20, g = 210, r = 40)
        swapCount = 2L
        Win32NativeWindowHost.stepLiveVBlankFrame(32.0)
        val shot2 = assertNotNull(Win32NativeWindowHost.captureWindowScreenshot(winImpl, 480, 320))
        val pixel2 = readScreenshotWordAt(shot2, cx, cy)
        val green2 = ((pixel2 shr 8) and 0xFFu).toInt()
        val blue2 = (pixel2 and 0xFFu).toInt()
        assertTrue(green2 > blue2 + 80, "Frame 2 must be green-dominant after VBlank swap, got RGB=0x${pixel2.toString(16)}")
    }

    @Test
    fun testCanvas2DAnd3DOverlayHostRendersGeneralOverlayChildrenWithoutForcingVideoPlayerChrome() = withVisualHost { factory ->
        Win32NativeWindowHost.visualTestBannerText = "Overlay Flicker Test: General overlay on interactive surface"

        val rootStack = VStack(gapPx = 8).apply { addClass("w-100 h-100") }
        val hostLayered = LayeredContainer().apply { style.height = CssLength.px(320f) }

        val surfW = 240
        val surfH = 160
        var swapCount = 1L
        val frameBgra = buildFilledBgraFrame(surfW, surfH, b = 180, g = 30, r = 20)

        val canvasSurface = Container("div", "canvas-2d-stage w-100 h-100")
        val overlayRoot = Container("div", "canvas-overlay w-100 h-100").apply {
            setAttribute("data-overlay-root", "true")
        }
        var btnClicks = 0
        val customOverlayButton = StyledButton("Custom Canvas Action", BootstrapType.SUCCESS, BootstrapSize.SM).apply {
            style.width = CssLength.px(180f)
            style.height = CssLength.px(36f)
            onClick = { btnClicks++ }
        }
        overlayRoot.addChild(customOverlayButton)

        hostLayered.addChild(canvasSurface)
        hostLayered.addChild(overlayRoot)
        rootStack.addChild(hostLayered)

        val window = Window(title = "Canvas Overlay Host Test", initialSize = Size(480, 360)).also {
            it.setContent(rootStack)
        }
        window.show(factory)

        var prePresentSurfaceCalls = 0
        var lastPrePresentSwapCount = 0L
        val canvasWidget = canvasSurface.ensureNativeImpl()!!.nativeWidget as Win32HwndHandle
        DesktopInteractiveSurfaceSpec(
            surfaceWidth = { surfW },
            surfaceHeight = { surfH },
            swapCount = { swapCount },
            captureBgra = { frameBgra },
            preferHardwareStretchBlit = false,
            suppressDefaultBorder = true,
            onBeforePresentFrame = { sc, _ ->
                prePresentSurfaceCalls++
                lastPrePresentSwapCount = sc
            }
        ).attachTo(canvasWidget)

        val overlayBtnHandle = customOverlayButton.ensureNativeImpl()!!.nativeWidget as Win32HwndHandle
        val hwnd = assertNotNull(Win32NativeWindowHost.activeHwnd)
        Win32NativeWindowHost.layoutAndSyncWindowChildren(hwnd)
        Win32NativeWindowHost.pumpVisualWindowEvents(24L)

        val btnHwnd = assertNotNull(overlayBtnHandle.hwnd)
        val btnBox = hwndBoundsInHost(btnHwnd, hwnd)
        val hit = Win32ControlMessageRouter.findDeepestHitChildHwnd(hwnd, (btnBox[0] + btnBox[2]) / 2, (btnBox[1] + btnBox[3]) / 2)
        assertEquals(btnHwnd, hit, "General overlay button HWND must be hit-testable above Canvas2D/Canvas3D stage HWND")

        Win32NativeWindowHost.simulateMouseClick((btnBox[0] + btnBox[2]) / 2, (btnBox[1] + btnBox[3]) / 2, hwnd)
        Win32NativeWindowHost.pumpVisualWindowEvents(16L)
        assertEquals(1, btnClicks)

        swapCount = 2L
        Win32NativeWindowHost.stepLiveVBlankFrame(32.0)
        Win32NativeWindowHost.pumpVisualWindowEvents(16L)
        assertTrue(
            prePresentSurfaceCalls >= 1 && lastPrePresentSwapCount >= 1L,
            "Expected DesktopInteractiveSurfaceSpec.onBeforePresentFrame to fire"
        )
    }

    @Test
    fun testRoundedStyledSurfaceBlendsTranslucentPillOverNonUniformVideoBackground() = withVisualHost { factory ->
        val rootStack = VStack(gapPx = 0).apply { addClass("w-100 h-100") }
        val layered = LayeredContainer().apply { addClass("w-100 h-100") }

        val surfW = 240
        val surfH = 120
        val splitBgra = ByteArray(surfW * surfH * 4)
        for (y in 0 until surfH) {
            for (x in 0 until surfW) {
                val off = (y * surfW + x) * 4
                if (x < surfW / 2) {
                    // Black left half (R=0, G=0, B=0)
                    splitBgra[off] = 0
                    splitBgra[off + 1] = 0
                    splitBgra[off + 2] = 0
                    splitBgra[off + 3] = 0xFF.toByte()
                } else {
                    // Bright blue right half (R=0x40, G=0x80, B=0xC0)
                    splitBgra[off] = 0xC0.toByte()
                    splitBgra[off + 1] = 0x80.toByte()
                    splitBgra[off + 2] = 0x40.toByte()
                    splitBgra[off + 3] = 0xFF.toByte()
                }
            }
        }

        val stage = Container("div", "w-100 h-100")
        val overlay = Container("div", "w-100 h-100")
        val pill = StyledContainer().apply {
            backgroundColor = RgbaColor(15, 18, 24, 160)
            borderColor = RgbaColor(255, 255, 255, 40)
            borderWidthPx = 1f
            style.borderRadius = com.nexustools.genericui.style.css.CssCornerRadii.all(CssLength.px(16f))
            style.width = CssLength.px(200f)
            style.height = CssLength.px(60f)
        }
        overlay.addChild(pill)
        layered.addChild(stage)
        layered.addChild(overlay)
        rootStack.addChild(layered)

        val window = Window(title = "Rounded Translucent Blend Test", initialSize = Size(240, 120)).also {
            it.setContent(rootStack)
        }
        window.show(factory)

        val winImpl = window.ensureNativeImpl() as WindowsWindowNativeImpl
        DesktopInteractiveSurfaceSpec(
            surfaceWidth = { surfW },
            surfaceHeight = { surfH },
            swapCount = { 1L },
            captureBgra = { splitBgra }
        ).attachTo(stage.ensureNativeImpl()!!.nativeWidget as DesktopNativeWidget)
        DesktopStageOverlaySpec(isTargetActive = { true }, isOverlayVisible = { true })
            .attachTo(overlay.ensureNativeImpl()!!.nativeWidget as DesktopNativeWidget)

        val shot = assertNotNull(Win32NativeWindowHost.captureWindowScreenshot(winImpl, 240, 120))
        val pillBox = hwndBoundsInHost(
            assertNotNull((pill.ensureNativeImpl()!!.nativeWidget as Win32HwndHandle).hwnd),
            assertNotNull(Win32NativeWindowHost.activeHwnd)
        )
        val sampleY = ((pillBox[1] + pillBox[3]) / 2).coerceIn(0, shot.height - 1)
        val blackSideSample = readScreenshotWordAt(shot, (surfW / 4).coerceIn(pillBox[0] + 4, surfW / 2 - 4), sampleY)
        val blueSideSample = readScreenshotWordAt(shot, ((surfW * 3) / 4).coerceIn(surfW / 2 + 4, pillBox[2] - 4), sampleY)

        assertNotEquals(blackSideSample, blueSideSample, "Translucent pill interior must blend against live non-uniform stage pixels")
        val blackBlue = (blackSideSample and 0xFFu).toInt()
        val videoBlue = (blueSideSample and 0xFFu).toInt()
        assertTrue(videoBlue > blackBlue + 30, "Blue channel over bright video ($videoBlue) must exceed black side ($blackBlue)")
    }

    @Test
    fun testMultiFrameRenderingWithoutMouseMovementUpdatesSeekBarAndTimeReadoutWithTranslucentHud() = withVisualHost { factory ->
        val rootStack = VStack(gapPx = 8).apply { addClass("w-100 h-100") }
        val stageLayered = LayeredContainer().apply { style.height = CssLength.px(360f) }

        val surfW = 320
        val surfH = 180
        var currentSwapCount = 1L
        var currentFrameBgra = buildFilledBgraFrame(surfW, surfH, b = 220, g = 40, r = 20)

        val videoSurface = Container("div", "video-stage w-100 h-100")
        val overlayRoot = Container("div", "video-overlay w-100 h-100")
        val controlsLayer = VStack(gapPx = 0).apply {
            addClass("w-100 h-100")
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_CONTROLS_LAYER)
        }
        val bottomBar = VStack(gapPx = 4).apply {
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_BOTTOM_BAR)
        }
        val seekContainer = VStack(gapPx = 0).apply {
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_SEEK_CONTAINER)
        }
        val seekSlider = Slider(min = 0.0, max = 100.0, value = 5.0, step = 1.0)
        seekContainer.addChild(seekSlider)

        val controlsRow = HStack(gapPx = 8).apply {
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_CONTROLS_ROW)
        }
        val controlsLeft = HStack(gapPx = 6).apply {
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_CONTROLS_LEFT)
        }
        val playPauseBtn = StyledButton("Pause", BootstrapType.PRIMARY, BootstrapSize.SM)
        val timePill = StyledContainer().apply {
            backgroundColor = RgbaColor(18, 20, 26, 170)
            style.width = CssLength.px(120f)
            style.height = CssLength.px(28f)
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_TIME_READOUT)
        }
        val timeReadoutText = Text("0:01 / 10:00")
        timePill.addChild(timeReadoutText)
        controlsLeft.addChild(playPauseBtn)
        controlsLeft.addChild(timePill)
        controlsRow.addChild(controlsLeft)
        bottomBar.addChild(seekContainer)
        bottomBar.addChild(controlsRow)
        controlsLayer.addChild(bottomBar)
        overlayRoot.addChild(controlsLayer)
        stageLayered.addChild(videoSurface)
        stageLayered.addChild(overlayRoot)
        rootStack.addChild(stageLayered)

        val window = Window(title = "No-Mouse Multi-Frame Overlay Test", initialSize = Size(640, 380)).also {
            it.setContent(rootStack)
        }
        window.show(factory)

        val winImpl = window.ensureNativeImpl() as WindowsWindowNativeImpl
        DesktopInteractiveSurfaceSpec(
            surfaceWidth = { surfW },
            surfaceHeight = { surfH },
            swapCount = { currentSwapCount },
            captureBgra = { currentFrameBgra },
            preferHardwareStretchBlit = true,
            suppressDefaultBorder = true,
            defaultAspectRatio = 16f / 9f,
            isActiveStream = { true }
        ).attachTo(videoSurface.ensureNativeImpl()!!.nativeWidget as DesktopNativeWidget)

        DesktopStageOverlaySpec(
            isTargetActive = { true },
            isOverlayVisible = { true }
        ).attachTo(overlayRoot.ensureNativeImpl()!!.nativeWidget as DesktopNativeWidget)

        val hwnd = assertNotNull(Win32NativeWindowHost.activeHwnd)
        val layeredHwnd = assertNotNull((stageLayered.ensureNativeImpl()!!.nativeWidget as Win32HwndHandle).hwnd)
        Win32NativeWindowHost.layoutAndSyncWindowChildren(hwnd)
        val pillBox = hwndBoundsInHost(assertNotNull((timePill.ensureNativeImpl()!!.nativeWidget as Win32HwndHandle).hwnd), hwnd)
        val pillX = (pillBox[0] + pillBox[2]) / 2
        val pillY = (pillBox[1] + pillBox[3]) / 2

        // Frame 1: Blue-dominant video frame without mouse movement
        currentFrameBgra = buildFilledBgraFrame(surfW, surfH, b = 220, g = 40, r = 20)
        currentSwapCount = 2L
        seekSlider.value = 25.0
        timeReadoutText.text = "0:02 / 10:00"
        Win32LayeredStageHostHwnd.notifyStageSurfaceFrameAdvanced(layeredHwnd)
        val shotBlue = assertNotNull(Win32NativeWindowHost.captureWindowScreenshot(winImpl, 640, 380))
        val sampleBlue = readScreenshotWordAt(shotBlue, pillX, pillY)
        assertNotEquals(0x000000u, sampleBlue, "HUD pill pixel must never flash solid 0x000000")
        assertNotEquals(0xFFFFFFu, sampleBlue, "HUD pill pixel must never flash solid 0xFFFFFF")

        // Frame 2: Red-dominant video frame without mouse movement
        currentFrameBgra = buildFilledBgraFrame(surfW, surfH, b = 20, g = 40, r = 220)
        currentSwapCount = 3L
        seekSlider.value = 50.0
        timeReadoutText.text = "0:03 / 10:00"
        Win32LayeredStageHostHwnd.notifyStageSurfaceFrameAdvanced(layeredHwnd)
        val shotRed = assertNotNull(Win32NativeWindowHost.captureWindowScreenshot(winImpl, 640, 380))
        val sampleRed = readScreenshotWordAt(shotRed, pillX, pillY)
        assertNotEquals(0x000000u, sampleRed)
        assertNotEquals(0xFFFFFFu, sampleRed)
        assertNotEquals(sampleBlue, sampleRed, "Semi-transparent HUD pill must alpha-blend with each changing video frame")
    }

    @Test
    fun testHudButtonIconAlphaBlendsWithoutOpaqueDarkBlackBox() = withVisualHost { factory ->
        val rootStack = VStack(gapPx = 8).apply { addClass("w-100 h-100") }
        val stageLayered = LayeredContainer().apply { style.height = CssLength.px(360f) }

        val surfW = 320
        val surfH = 180
        val frameBgra = buildFilledBgraFrame(surfW, surfH, b = 220, g = 160, r = 80)

        val videoSurface = Container("div", "video-stage w-100 h-100")
        val overlayRoot = Container("div", "video-overlay w-100 h-100")
        val controlsLayer = VStack(gapPx = 0).apply {
            addClass("w-100 h-100")
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_CONTROLS_LAYER)
        }
        val bottomBar = VStack(gapPx = 4).apply {
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_BOTTOM_BAR)
        }
        val controlsRow = HStack(gapPx = 8).apply {
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_CONTROLS_ROW)
        }
        val controlsLeft = HStack(gapPx = 6).apply {
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_CONTROLS_LEFT)
        }
        val iconBtn = StyledButton("", BootstrapType.SECONDARY, BootstrapSize.SM).apply {
            style.width = CssLength.px(44f)
            style.height = CssLength.px(32f)
        }
        controlsLeft.addChild(iconBtn)
        controlsRow.addChild(controlsLeft)
        bottomBar.addChild(controlsRow)
        controlsLayer.addChild(bottomBar)
        overlayRoot.addChild(controlsLayer)
        stageLayered.addChild(videoSurface)
        stageLayered.addChild(overlayRoot)
        rootStack.addChild(stageLayered)

        val window = Window(title = "HUD Button Icon Alpha Blend Test", initialSize = Size(480, 380)).also {
            it.setContent(rootStack)
        }
        window.show(factory)

        val iconW = 16
        val iconH = 16
        val iconRgba = ByteArray(iconW * iconH * 4)
        for (y in 4 until 12) {
            for (x in 4 until 12) {
                val idx = (y * iconW + x) * 4
                iconRgba[idx] = 0xFF.toByte()
                iconRgba[idx + 1] = 0xFF.toByte()
                iconRgba[idx + 2] = 0xFF.toByte()
                iconRgba[idx + 3] = 0xFF.toByte()
            }
        }
        val btnWidget = iconBtn.ensureNativeImpl()!!.nativeWidget as Win32HwndHandle
        val iconImpl = factory.createImage()
        iconImpl.setSourceUrl("res://drawable/ic_test_transparent_icon.png")
        val iconWidget = iconImpl.nativeWidget as DesktopNativeWidget
        iconWidget.attributes["activeRawBuffer"] = ImageBuffer(iconW, iconH, bytes = iconRgba)
        btnWidget.addChildWidget(iconWidget)

        DesktopInteractiveSurfaceSpec(
            surfaceWidth = { surfW },
            surfaceHeight = { surfH },
            swapCount = { 1L },
            captureBgra = { frameBgra },
            preferHardwareStretchBlit = true,
            suppressDefaultBorder = true,
            defaultAspectRatio = 16f / 9f,
            isActiveStream = { true }
        ).attachTo(videoSurface.ensureNativeImpl()!!.nativeWidget as DesktopNativeWidget)

        DesktopStageOverlaySpec(
            isTargetActive = { true },
            isOverlayVisible = { true }
        ).attachTo(overlayRoot.ensureNativeImpl()!!.nativeWidget as DesktopNativeWidget)

        val winImpl = window.ensureNativeImpl() as WindowsWindowNativeImpl
        val hwnd = assertNotNull(Win32NativeWindowHost.activeHwnd)
        val shot = assertNotNull(Win32NativeWindowHost.captureWindowScreenshot(winImpl, 480, 380))
        val btnBox = hwndBoundsInHost(assertNotNull(btnWidget.hwnd), hwnd)
        val btnMidX = (btnBox[0] + btnBox[2]) / 2
        val btnMidY = (btnBox[1] + btnBox[3]) / 2
        val transparentCornerWord = readScreenshotWordAt(shot, btnMidX - 6, btnMidY - 6)
        assertNotEquals(
            0x0F1218u,
            transparentCornerWord,
            "Transparent icon pixels inside HUD button must alpha-blend cleanly without drawing an opaque dark box"
        )
    }

    @Test
    fun testOverlayHudHideLeavesCaptionBannerVisibleAndMouseMoveRestoresHud() = withVisualHost { factory ->
        val rootStack = VStack(gapPx = 8).apply { addClass("w-100 h-100") }
        val stageLayered = LayeredContainer().apply { style.height = CssLength.px(360f) }

        val surfW = 320
        val surfH = 180
        val r = 40
        val g = 150
        val b = 220
        val rawVideoWord = ((r and 0xFF) shl 16).toUInt() or ((g and 0xFF) shl 8).toUInt() or (b and 0xFF).toUInt()
        val frameBgra = buildFilledBgraFrame(surfW, surfH, b = b, g = g, r = r)

        val videoSurface = Container("div", "video-stage w-100 h-100")
        val overlayRoot = Container("div", "video-overlay w-100 h-100")
        val controlsLayer = VStack(gapPx = 0).apply {
            addClass("w-100 h-100")
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_CONTROLS_LAYER)
        }
        val topBar = StyledContainer().apply {
            backgroundColor = RgbaColor(18, 20, 26, 180)
            style.height = CssLength.px(32f)
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_TOP_BAR)
        }
        topBar.addChild(Text("Episode Title"))
        val bottomBar = VStack(gapPx = 4).apply {
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_BOTTOM_BAR)
        }
        val controlsRow = HStack(gapPx = 8).apply {
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_CONTROLS_ROW)
        }
        val controlsLeft = HStack(gapPx = 6).apply {
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_CONTROLS_LEFT)
        }
        val playPauseBtn = StyledButton("Pause", BootstrapType.PRIMARY, BootstrapSize.SM)
        val timePill = StyledContainer().apply {
            backgroundColor = RgbaColor(18, 20, 26, 180)
            style.width = CssLength.px(120f)
            style.height = CssLength.px(28f)
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_TIME_READOUT)
        }
        timePill.addChild(Text("0:15 / 24:00"))
        controlsLeft.addChild(playPauseBtn)
        controlsLeft.addChild(timePill)
        controlsRow.addChild(controlsLeft)
        bottomBar.addChild(controlsRow)
        controlsLayer.addChild(topBar)
        controlsLayer.addChild(bottomBar)

        val captionBanner = StyledContainer().apply {
            backgroundColor = RgbaColor(15, 18, 24, 185)
            style.height = CssLength.px(32f)
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_CAPTION_BANNER)
        }
        captionBanner.addChild(Text("Always-Visible Subtitle"))

        overlayRoot.addChild(controlsLayer)
        overlayRoot.addChild(captionBanner)

        var mouseMovedOnOverlayRootCount = 0
        overlayRoot.onPointerMove = {
            mouseMovedOnOverlayRootCount++
            overlayRoot.setAttribute("hudVisible", "true")
        }

        stageLayered.addChild(videoSurface)
        stageLayered.addChild(overlayRoot)
        rootStack.addChild(stageLayered)

        val window = Window(title = "HUD Hide & Mouse Reveal Test", initialSize = Size(640, 380)).also {
            it.setContent(rootStack)
        }
        window.show(factory)

        val winImpl = window.ensureNativeImpl() as WindowsWindowNativeImpl
        DesktopInteractiveSurfaceSpec(
            surfaceWidth = { surfW },
            surfaceHeight = { surfH },
            swapCount = { 1L },
            captureBgra = { frameBgra },
            preferHardwareStretchBlit = true,
            suppressDefaultBorder = true,
            defaultAspectRatio = 16f / 9f,
            isActiveStream = { true },
            activeCaptionText = { "Always-Visible Subtitle" }
        ).attachTo(videoSurface.ensureNativeImpl()!!.nativeWidget as DesktopNativeWidget)

        val overlayWidget = overlayRoot.ensureNativeImpl()!!.nativeWidget as DesktopNativeWidget
        DesktopStageOverlaySpec(
            isTargetActive = { true },
            isOverlayVisible = { overlayWidget.attributes["hudVisible"] != "false" },
            activeCaptionText = { "Always-Visible Subtitle" }
        ).attachTo(overlayWidget)
        overlayRoot.setAttribute("hudVisible", "true")

        val hwnd = assertNotNull(Win32NativeWindowHost.activeHwnd)
        Win32NativeWindowHost.layoutAndSyncWindowChildren(hwnd)
        val topBox = hwndBoundsInHost(assertNotNull((topBar.ensureNativeImpl()!!.nativeWidget as Win32HwndHandle).hwnd), hwnd)
        val captionBox = hwndBoundsInHost(assertNotNull((captionBanner.ensureNativeImpl()!!.nativeWidget as Win32HwndHandle).hwnd), hwnd)
        val stageRect = Win32NativeWindowHost.lastRenderedVideoStageRect
        val stageMidX = (stageRect[0] + stageRect[2]) / 2
        val stageMidY = (stageRect[1] + stageRect[3]) / 2

        val initialShot = assertNotNull(Win32NativeWindowHost.captureWindowScreenshot(winImpl, 640, 380))
        assertNotEquals(rawVideoWord, readScreenshotWordAt(initialShot, (topBox[0] + topBox[2]) / 2, (topBox[1] + topBox[3]) / 2), "Top HUD must be visible initially")

        // Auto-hide HUD: HUD controls hide while locked subtitle banner stays visible
        overlayRoot.setAttribute("hudVisible", "false")
        Win32LayeredStageHostHwnd.setHudAutoHiddenOnAllStages(true)
        val hiddenShot = assertNotNull(Win32NativeWindowHost.captureWindowScreenshot(winImpl, 640, 380))
        assertEquals(rawVideoWord, readScreenshotWordAt(hiddenShot, (topBox[0] + topBox[2]) / 2, (topBox[1] + topBox[3]) / 2), "Top HUD must reveal raw video when auto-hidden")
        assertNotEquals(rawVideoWord, readScreenshotWordAt(hiddenShot, (captionBox[0] + captionBox[2]) / 2, (captionBox[1] + captionBox[3]) / 2), "Caption banner must stay visible when HUD is hidden")

        // Moving mouse inside video stage wakes HUD up and restores overlayRoot
        Win32NativeWindowHost.simulateMouseMove(stageMidX, stageMidY, hwnd)
        Win32NativeWindowHost.pumpVisualWindowEvents(16L)
        assertTrue(mouseMovedOnOverlayRootCount >= 1, "Moving mouse inside stage must dispatch onPointerMove to overlayRoot")
        assertEquals("true", overlayRoot.getAttribute("hudVisible"))
        val revealedShot = assertNotNull(Win32NativeWindowHost.captureWindowScreenshot(winImpl, 640, 380))
        assertNotEquals(rawVideoWord, readScreenshotWordAt(revealedShot, (topBox[0] + topBox[2]) / 2, (topBox[1] + topBox[3]) / 2), "Top HUD must be visible again after mouse reveal")
    }
}
