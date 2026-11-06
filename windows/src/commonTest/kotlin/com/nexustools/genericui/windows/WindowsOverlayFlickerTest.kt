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
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.style.color.RgbaColor
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import platform.windows.InvalidateRect
import platform.windows.UpdateWindow
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
        Win32NativeWindowHost.clearActiveWindow()
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

    private fun readBackBufferWordAt(x: Int, y: Int): UInt {
        val pixels = Win32NativeWindowHost.cachedMainBackBufferPixels ?: return 0u
        val w = Win32NativeWindowHost.cachedMainBackBufferWidth
        val h = Win32NativeWindowHost.cachedMainBackBufferHeight
        if (x !in 0 until w || y !in 0 until h) return 0u
        return pixels.reinterpret<UIntVar>()[y * w + x] and 0x00FFFFFFu
    }

    @Test
    fun testMultiFrameVideoStageWithOverlayAtomicCompositionAndNoCalibrationBleed() = withVisualHost { factory ->
        Win32NativeWindowHost.visualTestBannerText = "Overlay Flicker Test: Multi-frame video stage composition"

        val rootStack = VStack(gapPx = 8).apply {
            addClass("w-100 h-100")
        }
        val stageLayered = LayeredContainer().apply {
            setStyleProperty("height", "420px")
        }

        val surfW = 320
        val surfH = 180
        var currentSwapCount = 1L
        var currentFrameBgra = buildFilledBgraFrame(surfW, surfH, b = 200, g = 40, r = 30)

        val videoSurface = Container("div", "video-stage")
        val overlayRoot = Container("div", "video-overlay")

        val controlsLayer = VStack(gapPx = 0).apply {
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_CONTROLS_LAYER)
        }
        val topBar = HStack(gapPx = 8).apply {
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_TOP_BAR)
        }
        val titleText = Text("Episode 1 - Atomic Overlay Sync")
        val statusText = Text("Direct Play 1080p")
        topBar.addChild(titleText)
        topBar.addChild(statusText)

        val centerZone = VStack(gapPx = 0).apply {
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_CENTER_ZONE)
        }

        val bottomBar = VStack(gapPx = 4).apply {
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

        val window = Window(title = "Overlay Flicker Test").also {
            it.setContent(rootStack)
        }
        window.show(factory)

        val stageWidget = videoSurface.ensureNativeImpl()!!.nativeWidget as DesktopNativeWidget
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

        val overlayWidget = overlayRoot.ensureNativeImpl()!!.nativeWidget as DesktopNativeWidget
        DesktopStageOverlaySpec(
            isTargetActive = { true },
            isOverlayVisible = { true },
            activeCaptionText = { "Locked Subtitle Line Frame $currentSwapCount" }
        ).attachTo(overlayWidget)

        val hwnd = Win32NativeWindowHost.activeHwnd
        assertNotNull(hwnd, "Expected active HWND")

        Win32NativeWindowHost.pumpVisualWindowEvents(32L)
        InvalidateRect(hwnd, null, 0)
        Win32NativeWindowHost.paintWindow(hwnd)

        val stageRect = Win32NativeWindowHost.lastRenderedVideoStageRect
        assertTrue(stageRect[2] > stageRect[0] && stageRect[3] > stageRect[1], "Expected valid video stage rect")

        val stageLeft = stageRect[0]
        val stageTop = stageRect[1]
        val stageRight = stageRect[2]
        val stageBottom = stageRect[3]
        val stageMidX = (stageLeft + stageRight) / 2
        val stageMidY = (stageTop + stageBottom) / 2

        Win32NativeWindowHost.simulateMouseMove(stageMidX, stageMidY)
        Win32NativeWindowHost.overlayHudCurrentAlpha = 1f
        Win32NativeWindowHost.overlayHudTargetAlpha = 1f

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
        var droppedSurfaceBlitFrames = 0

        for (step in 1..12) {
            val (r, g, b) = frameColorsRgb[(step - 1) % frameColorsRgb.size]
            val rawVideoWord = ((r and 0xFF) shl 16).toUInt() or ((g and 0xFF) shl 8).toUInt() or (b and 0xFF).toUInt()
            currentFrameBgra = buildFilledBgraFrame(surfW, surfH, b = b, g = g, r = r)
            currentSwapCount = step.toLong()

            seekSlider.value = (step * 5).toDouble()
            // Alternate timeReadoutText length to trigger HUD cache rebuilds on fast-path paints
            val timeStr = if (step % 2 == 0) "0:0$step / 24:00" else "01:0$step / 01:24:00"
            timeReadoutText.text = timeStr
            captionText.text = "Locked Subtitle Line Frame $step"

            Win32NativeWindowHost.markVideoStageDirty(hwnd)
            UpdateWindow(hwnd)

            val dirtyR = Win32NativeWindowHost.frameBufferDirtyRight
            val dirtyB = Win32NativeWindowHost.frameBufferDirtyBottom
            if (dirtyR <= stageLeft + 10 || dirtyB <= stageTop + 10) {
                droppedSurfaceBlitFrames++
            }

            val topHudSample = readBackBufferWordAt(stageMidX, stageTop + 12)
            if (topHudSample == rawVideoWord) {
                uncompositedTopHudFrames++
            }

            val bottomHudSample = readBackBufferWordAt(stageMidX, stageBottom - 24)
            val bottomControlPillSample = readBackBufferWordAt(stageLeft + 80, stageBottom - 32)
            if (bottomHudSample == rawVideoWord || bottomControlPillSample == rawVideoWord) {
                uncompositedBottomHudFrames++
            }

            val borderLeftTopBand = readBackBufferWordAt(stageLeft, stageTop + 16)
            val borderLeftBottomBand = readBackBufferWordAt(stageLeft, stageBottom - 30)
            if (borderLeftTopBand == 0xFFFFFFu || borderLeftBottomBand == 0xFFFFFFu) {
                whiteBorderCorruptionCount++
            }
        }

        assertEquals(
            0,
            droppedSurfaceBlitFrames,
            "Every presented surface frame must mark frameBufferDirty so finishPaint() blits the updated surface to the window HDC"
        )
        assertEquals(
            0,
            uncompositedTopHudFrames,
            "Top overlay HUD must be composited onto every presented video frame before display (zero uncomposited flicker frames)"
        )
        assertEquals(
            0,
            uncompositedBottomHudFrames,
            "Bottom overlay HUD must be composited onto every presented video frame before display (zero uncomposited flicker frames)"
        )
        assertEquals(
            0,
            whiteBorderCorruptionCount,
            "CachedVideoHudOverlay 2-pass calibration must never leave 0xFFFFFF white pixels on the 2px stage border during fast-path paints"
        )
    }

    @Test
    fun testCanvasBgraSurfaceMarksFrameBufferDirtyOnPreservedBackBuffer() = withVisualHost { factory ->
        Win32NativeWindowHost.visualTestBannerText = "Overlay Flicker Test: Canvas BGRA dirty rect tracking"

        val rootStack = VStack(gapPx = 8).apply {
            addClass("w-100 h-100")
        }
        val surfW = 240
        val surfH = 160
        var swapCount = 1L
        var frameBgra = buildFilledBgraFrame(surfW, surfH, b = 180, g = 30, r = 30)

        val canvasSurface = Container("div", "canvas-surface").apply {
            setStyleProperty("height", "240px")
        }
        rootStack.addChild(canvasSurface)

        val window = Window(title = "Canvas BGRA Dirty Rect Test").also {
            it.setContent(rootStack)
        }
        window.show(factory)

        val canvasWidget = canvasSurface.ensureNativeImpl()!!.nativeWidget as DesktopNativeWidget
        DesktopInteractiveSurfaceSpec(
            surfaceWidth = { surfW },
            surfaceHeight = { surfH },
            swapCount = { swapCount },
            captureBgra = { frameBgra },
            preferHardwareStretchBlit = false,
            suppressDefaultBorder = true
        ).attachTo(canvasWidget)

        val hwnd = Win32NativeWindowHost.activeHwnd
        assertNotNull(hwnd)
        Win32NativeWindowHost.pumpVisualWindowEvents(24L)
        InvalidateRect(hwnd, null, 0)
        Win32NativeWindowHost.paintWindow(hwnd)

        // Present frame 2 on preserved main back buffer (simulating Canvas2D swapBuffers)
        frameBgra = buildFilledBgraFrame(surfW, surfH, b = 20, g = 210, r = 40)
        swapCount = 2L
        InvalidateRect(hwnd, null, 0)
        Win32NativeWindowHost.paintWindow(hwnd)

        assertTrue(
            Win32NativeWindowHost.frameBufferDirtyRight > Win32NativeWindowHost.frameBufferDirtyLeft &&
                Win32NativeWindowHost.frameBufferDirtyBottom > Win32NativeWindowHost.frameBufferDirtyTop,
            "Expected blitScaledBgraToHdc / renderInlineCanvasSurface to mark frameBufferDirty when presenting a new BGRA frame on a preserved back buffer, but got dirtyRect=[${Win32NativeWindowHost.frameBufferDirtyLeft}, ${Win32NativeWindowHost.frameBufferDirtyTop}, ${Win32NativeWindowHost.frameBufferDirtyRight}, ${Win32NativeWindowHost.frameBufferDirtyBottom}]"
        )
    }

    @Test
    fun testCanvas2DAnd3DOverlayHostRendersGeneralOverlayChildrenWithoutForcingVideoPlayerChrome() = withVisualHost { factory ->
        Win32NativeWindowHost.visualTestBannerText = "Overlay Flicker Test: General overlay on interactive surface"

        val rootStack = VStack(gapPx = 8).apply {
            addClass("w-100 h-100")
        }
        val hostLayered = LayeredContainer().apply {
            setStyleProperty("height", "320px")
        }

        val surfW = 240
        val surfH = 160
        var swapCount = 1L
        // Pure dark blue canvas surface (R=20, G=30, B=180 -> 0x141EB4)
        var frameBgra = buildFilledBgraFrame(surfW, surfH, b = 180, g = 30, r = 20)

        val canvasSurface = Container("div", "canvas-2d-stage")
        val overlayRoot = Container("div", "canvas-overlay").apply {
            setAttribute("data-overlay-root", "true")
        }
        val customOverlayButton = StyledButton("Custom Canvas Action", BootstrapType.SUCCESS, BootstrapSize.SM)
        overlayRoot.addChild(customOverlayButton)

        hostLayered.addChild(canvasSurface)
        hostLayered.addChild(overlayRoot)
        rootStack.addChild(hostLayered)

        val window = Window(title = "Canvas Overlay Host Test").also {
            it.setContent(rootStack)
        }
        window.show(factory)

        var prePresentSurfaceCalls = 0
        var lastPrePresentSwapCount = 0L
        val canvasWidget = canvasSurface.ensureNativeImpl()!!.nativeWidget as DesktopNativeWidget
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

        val overlayBtnWidget = customOverlayButton.ensureNativeImpl()!!.nativeWidget as DesktopNativeWidget

        val hwnd = Win32NativeWindowHost.activeHwnd
        assertNotNull(hwnd)
        Win32NativeWindowHost.pumpVisualWindowEvents(24L)
        InvalidateRect(hwnd, null, 0)
        Win32NativeWindowHost.paintWindow(hwnd)

        val hasCustomOverlayBtnHitRegion = Win32NativeWindowHost.getLastRenderedHitRegionsSnapshot()
            .any { it.widget === overlayBtnWidget }
        assertTrue(
            hasCustomOverlayBtnHitRegion,
            "Expected general overlay children inside data-overlay-root on a Canvas2D/Canvas3D surface to be rendered and hit-testable instead of being swallowed by video-player HUD assumptions"
        )

        swapCount = 2L
        InvalidateRect(hwnd, null, 0)
        Win32NativeWindowHost.paintWindow(hwnd)
        assertTrue(
            prePresentSurfaceCalls >= 1 && lastPrePresentSwapCount == 2L,
            "Expected DesktopInteractiveSurfaceSpec.onBeforePresentFrame to fire before compositing new surface frame"
        )
    }

    @Test
    fun testDrawAntialiasedRoundedRectRgbaBlendsEveryPixelWhenThreeSamplePointsMatch() = withVisualHost { factory ->
        val rootStack = VStack(gapPx = 8)
        val window = Window(title = "RoundedRect Scanline Blend Test").also {
            it.setContent(rootStack)
        }
        window.show(factory)
        val hwnd = Win32NativeWindowHost.activeHwnd
        assertNotNull(hwnd)
        Win32NativeWindowHost.pumpVisualWindowEvents(16L)
        InvalidateRect(hwnd, null, 0)
        Win32NativeWindowHost.paintWindow(hwnd)

        val pixels = Win32NativeWindowHost.cachedMainBackBufferPixels
        assertNotNull(pixels, "Expected backbuffer pixels")
        val bufW = Win32NativeWindowHost.cachedMainBackBufferWidth
        val words = pixels.reinterpret<UIntVar>()

        val rectLeft = 40
        val rectTop = 40
        val rectRight = 140
        val rectBottom = 80
        val testY = 60

        // Fill background of row testY with bright video pixel 0xFF4080C0, except set all potential
        // 3-pixel shortcut sample points (start, mid, end) to pure black 0xFF000000
        for (x in rectLeft until rectRight) {
            words[testY * bufW + x] = 0xFF4080C0u
        }
        for (x in rectLeft..(rectLeft + 24)) {
            words[testY * bufW + x] = 0xFF000000u
        }
        val midX = (rectLeft + rectRight) / 2
        for (x in (midX - 4)..(midX + 4)) {
            words[testY * bufW + x] = 0xFF000000u
        }
        for (x in (rectRight - 24) until rectRight) {
            words[testY * bufW + x] = 0xFF000000u
        }

        // Draw semi-transparent pill (RgbaColor(15, 18, 24, 160)) over the scanline
        Win32NativeWindowHost.drawAntialiasedRoundedRectRgba(
            hdc = null,
            left = rectLeft,
            top = rectTop,
            right = rectRight,
            bottom = rectBottom,
            radiusPx = 16f,
            fillColor = RgbaColor(15, 18, 24, 160),
            borderColor = RgbaColor(255, 255, 255, 40),
            borderWidthPx = 1f,
            opacity = 1f
        )

        val blackBgSample = words[testY * bufW + (rectLeft + 20)] and 0x00FFFFFFu
        val videoBgSample = words[testY * bufW + (rectLeft + 32)] and 0x00FFFFFFu

        assertNotEquals(
            blackBgSample,
            videoBgSample,
            "Interior pixels with non-black video background (0x4080C0) must alpha-blend against their own background pixel, not reuse the black-background sample"
        )
        val videoBlue = (videoBgSample and 0xFFu).toInt()
        val blackBlue = (blackBgSample and 0xFFu).toInt()
        assertTrue(
            videoBlue > blackBlue + 30,
            "Expected video-blended blue channel ($videoBlue) to be significantly brighter than black-blended blue channel ($blackBlue)"
        )
    }

    @Test
    fun testMultiFrameRenderingWithoutMouseMovementUpdatesSeekBarAndTimeReadoutWithTranslucentHud() = withVisualHost { factory ->
        val rootStack = VStack(gapPx = 8).apply { addClass("w-100 h-100") }
        val stageLayered = LayeredContainer().apply { setStyleProperty("height", "360px") }

        val surfW = 320
        val surfH = 180
        var currentSwapCount = 1L
        var currentFrameBgra = buildFilledBgraFrame(surfW, surfH, b = 220, g = 120, r = 40)

        val videoSurface = Container("div", "video-stage")
        val overlayRoot = Container("div", "video-overlay")

        val controlsLayer = VStack(gapPx = 0).apply {
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

        val window = Window(title = "No-Mouse Multi-Frame Overlay Test").also {
            it.setContent(rootStack)
        }
        window.show(factory)

        val stageWidget = videoSurface.ensureNativeImpl()!!.nativeWidget as DesktopNativeWidget
        DesktopInteractiveSurfaceSpec(
            surfaceWidth = { surfW },
            surfaceHeight = { surfH },
            swapCount = { currentSwapCount },
            captureBgra = { currentFrameBgra },
            preferHardwareStretchBlit = true,
            suppressDefaultBorder = true,
            defaultAspectRatio = 16f / 9f,
            isActiveStream = { true }
        ).attachTo(stageWidget)

        val overlayWidget = overlayRoot.ensureNativeImpl()!!.nativeWidget as DesktopNativeWidget
        DesktopStageOverlaySpec(
            isTargetActive = { true },
            isOverlayVisible = { true }
        ).attachTo(overlayWidget)

        val hwnd = Win32NativeWindowHost.activeHwnd
        assertNotNull(hwnd)

        // Ensure NO mouse movement into stage has occurred
        Win32NativeWindowHost.isPointerInVideoStage = false
        Win32NativeWindowHost.lastVideoStageMouseMoveTickMs = 0L
        Win32NativeWindowHost.lastOverlayFadeTickMs = 0.0

        Win32NativeWindowHost.pumpVisualWindowEvents(24L)
        InvalidateRect(hwnd, null, 0)
        Win32NativeWindowHost.paintWindow(hwnd)

        assertEquals(
            1f,
            Win32NativeWindowHost.overlayHudCurrentAlpha,
            "HUD overlay must remain visible when isOverlayVisible=true even before any mouse movement into the stage"
        )

        val stageRect = Win32NativeWindowHost.lastRenderedVideoStageRect
        val stageLeft = stageRect[0]
        val stageBottom = stageRect[3]
        val pillSampleX = stageLeft + 80
        val pillSampleY = stageBottom - 32

        // Frame 1: Blue-dominant video frame (B=220, G=40, R=20)
        currentFrameBgra = buildFilledBgraFrame(surfW, surfH, b = 220, g = 40, r = 20)
        currentSwapCount = 2L
        seekSlider.value = 25.0
        timeReadoutText.text = "0:02 / 10:00"
        assertTrue(
            Win32NativeWindowHost.overlayMutatedDuringVBlank,
            "Updating seekSlider.value and timeReadoutText.text must trigger overlayInvalidationListener without mouse movement"
        )
        UpdateWindow(hwnd)
        val sampleBlueFrame = readBackBufferWordAt(pillSampleX, pillSampleY)
        assertNotEquals(0x000000u, sampleBlueFrame, "HUD pill pixel must never flash solid 0x000000")
        assertNotEquals(0xFFFFFFu, sampleBlueFrame, "HUD pill pixel must never flash solid 0xFFFFFF")

        // Frame 2: Red-dominant video frame (B=20, G=40, R=220) without mouse movement
        currentFrameBgra = buildFilledBgraFrame(surfW, surfH, b = 20, g = 40, r = 220)
        currentSwapCount = 3L
        seekSlider.value = 50.0
        timeReadoutText.text = "0:03 / 10:00"
        UpdateWindow(hwnd)
        val sampleRedFrame = readBackBufferWordAt(pillSampleX, pillSampleY)
        assertNotEquals(0x000000u, sampleRedFrame, "HUD pill pixel must never flash solid 0x000000")
        assertNotEquals(0xFFFFFFu, sampleRedFrame, "HUD pill pixel must never flash solid 0xFFFFFF")
        assertNotEquals(
            sampleBlueFrame,
            sampleRedFrame,
            "Semi-transparent HUD pill/backdrop must alpha-blend with the changing video frame on every frame without mouse movement"
        )
    }

    @Test
    fun testHudButtonIconAlphaBlendsWithoutOpaqueDarkBlackBox() = withVisualHost { factory ->
        val rootStack = VStack(gapPx = 8).apply { addClass("w-100 h-100") }
        val stageLayered = LayeredContainer().apply { setStyleProperty("height", "360px") }

        val surfW = 320
        val surfH = 180
        val frameBgra = buildFilledBgraFrame(surfW, surfH, b = 220, g = 160, r = 80)

        val videoSurface = Container("div", "video-stage")
        val overlayRoot = Container("div", "video-overlay")

        val controlsLayer = VStack(gapPx = 0).apply {
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
        val iconBtn = StyledButton("", BootstrapType.SECONDARY, BootstrapSize.SM)
        controlsLeft.addChild(iconBtn)
        controlsRow.addChild(controlsLeft)
        bottomBar.addChild(controlsRow)
        controlsLayer.addChild(bottomBar)
        overlayRoot.addChild(controlsLayer)
        stageLayered.addChild(videoSurface)
        stageLayered.addChild(overlayRoot)
        rootStack.addChild(stageLayered)

        val window = Window(title = "HUD Button Icon Alpha Blend Test").also {
            it.setContent(rootStack)
        }
        window.show(factory)

        // Attach a 16x16 RGBA_8888 icon buffer with fully transparent corners (alpha=0) and white center (alpha=255)
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
        val btnWidget = iconBtn.ensureNativeImpl()!!.nativeWidget as DesktopNativeWidget
        val iconImpl = factory.createImage()
        iconImpl.setSourceUrl("res://drawable/ic_test_transparent_icon.png")
        val iconWidget = iconImpl.nativeWidget as DesktopNativeWidget
        iconWidget.attributes["activeRawBuffer"] = ImageBuffer(iconW, iconH, bytes = iconRgba)
        btnWidget.addChildWidget(iconWidget)

        val stageWidget = videoSurface.ensureNativeImpl()!!.nativeWidget as DesktopNativeWidget
        DesktopInteractiveSurfaceSpec(
            surfaceWidth = { surfW },
            surfaceHeight = { surfH },
            swapCount = { 1L },
            captureBgra = { frameBgra },
            preferHardwareStretchBlit = true,
            suppressDefaultBorder = true,
            defaultAspectRatio = 16f / 9f,
            isActiveStream = { true }
        ).attachTo(stageWidget)

        val overlayWidget = overlayRoot.ensureNativeImpl()!!.nativeWidget as DesktopNativeWidget
        DesktopStageOverlaySpec(
            isTargetActive = { true },
            isOverlayVisible = { true }
        ).attachTo(overlayWidget)

        val hwnd = Win32NativeWindowHost.activeHwnd
        assertNotNull(hwnd)
        Win32NativeWindowHost.pumpVisualWindowEvents(24L)
        InvalidateRect(hwnd, null, 0)
        Win32NativeWindowHost.paintWindow(hwnd)

        val btnHit = Win32NativeWindowHost.getLastRenderedHitRegionsSnapshot().firstOrNull { it.widget === btnWidget }
        assertNotNull(btnHit, "Expected HUD icon button hit region")

        val btnMidX = (btnHit.left + btnHit.right) / 2
        val btnMidY = (btnHit.top + btnHit.bottom) / 2
        // Sample inside the icon box's transparent corner (btnMidX - 6, btnMidY - 6)
        val transparentCornerWord = readBackBufferWordAt(btnMidX - 6, btnMidY - 6)
        // 0x18120F COLORREF in BGRA 0x00RRGGBB is R=0x0F (15), G=0x12 (18), B=0x18 (24) -> 0x0F1218
        assertNotEquals(
            0x0F1218u,
            transparentCornerWord,
            "Transparent icon pixels inside HUD button must alpha-blend over the button pill & video frame instead of drawing an opaque 0x18120F box"
        )
    }

    @Test
    fun testOverlayHudSmoothOpacityFadeAndMouseReveal() = withVisualHost { factory ->
        val rootStack = VStack(gapPx = 8).apply { addClass("w-100 h-100") }
        val stageLayered = LayeredContainer().apply { setStyleProperty("height", "360px") }

        val surfW = 320
        val surfH = 180
        val r = 40
        val g = 150
        val b = 220
        val rawVideoWord = ((r and 0xFF) shl 16).toUInt() or ((g and 0xFF) shl 8).toUInt() or (b and 0xFF).toUInt()
        val frameBgra = buildFilledBgraFrame(surfW, surfH, b = b, g = g, r = r)

        val videoSurface = Container("div", "video-stage")
        val overlayRoot = Container("div", "video-overlay")

        val controlsLayer = VStack(gapPx = 0).apply {
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_CONTROLS_LAYER)
        }
        val topBar = HStack(gapPx = 8).apply {
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
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_TIME_READOUT)
        }
        timePill.addChild(Text("0:15 / 24:00"))
        controlsLeft.addChild(playPauseBtn)
        controlsLeft.addChild(timePill)
        controlsRow.addChild(controlsLeft)
        bottomBar.addChild(controlsRow)
        controlsLayer.addChild(topBar)
        controlsLayer.addChild(bottomBar)
        overlayRoot.addChild(controlsLayer)

        var mouseMovedOnOverlayRootCount = 0
        overlayRoot.onPointerMove = {
            mouseMovedOnOverlayRootCount++
            overlayRoot.setAttribute("hudVisible", "true")
        }

        stageLayered.addChild(videoSurface)
        stageLayered.addChild(overlayRoot)
        rootStack.addChild(stageLayered)

        val window = Window(title = "HUD Smooth Fade & Mouse Reveal Test").also {
            it.setContent(rootStack)
        }
        window.show(factory)

        val stageWidget = videoSurface.ensureNativeImpl()!!.nativeWidget as DesktopNativeWidget
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
        ).attachTo(stageWidget)

        val overlayWidget = overlayRoot.ensureNativeImpl()!!.nativeWidget as DesktopNativeWidget
        DesktopStageOverlaySpec(
            isTargetActive = { true },
            isOverlayVisible = { overlayWidget.attributes["hudVisible"] != "false" },
            activeCaptionText = { "Always-Visible Subtitle" }
        ).attachTo(overlayWidget)
        overlayRoot.setAttribute("hudVisible", "true")

        val hwnd = Win32NativeWindowHost.activeHwnd
        assertNotNull(hwnd)
        Win32NativeWindowHost.pumpVisualWindowEvents(24L)
        InvalidateRect(hwnd, null, 0)
        Win32NativeWindowHost.paintWindow(hwnd)

        val stageRect = Win32NativeWindowHost.lastRenderedVideoStageRect
        val stageLeft = stageRect[0]
        val stageTop = stageRect[1]
        val stageRight = stageRect[2]
        val stageBottom = stageRect[3]
        val stageMidX = (stageLeft + stageRight) / 2
        val stageMidY = (stageTop + stageBottom) / 2

        assertNotEquals(rawVideoWord, readBackBufferWordAt(stageMidX, stageTop + 12), "Top HUD must be visible initially")
        assertNotEquals(rawVideoWord, readBackBufferWordAt(stageLeft + 80, stageBottom - 32), "Bottom HUD pill must be visible initially")

        // Trigger auto-hide without mouse movement and step smooth fade
        Win32NativeWindowHost.lastOverlayFadeTickMs = 1000.0
        overlayRoot.setAttribute("hudVisible", "false")
        Win32NativeWindowHost.markVideoStageDirty(hwnd)
        UpdateWindow(hwnd)
        assertEquals(0f, Win32NativeWindowHost.overlayHudTargetAlpha, "Target HUD alpha must become 0f when hudVisible=false")

        Win32NativeWindowHost.pumpVisualWindowEvents(60L)
        assertTrue(
            Win32NativeWindowHost.overlayHudCurrentAlpha in 0.01f..0.95f,
            "Expected intermediate HUD alpha during 220ms smooth fade, got ${Win32NativeWindowHost.overlayHudCurrentAlpha}"
        )

        Win32NativeWindowHost.pumpVisualWindowEvents(260L)
        assertEquals(0f, Win32NativeWindowHost.overlayHudCurrentAlpha, "HUD alpha must reach 0f after 220ms fade completes")
        assertEquals(rawVideoWord, readBackBufferWordAt(stageMidX, stageTop + 12), "Top HUD must be completely hidden at alpha=0f")
        assertEquals(rawVideoWord, readBackBufferWordAt(stageLeft + 80, stageBottom - 32), "Bottom HUD pill must be completely hidden at alpha=0f")
        assertNotEquals(rawVideoWord, readBackBufferWordAt(stageMidX, stageBottom - 28), "Locked subtitle banner must remain visible at 1.0 opacity even when HUD is hidden")

        // Move mouse inside video stage: must dispatch onMouseMove to overlayRoot and reveal HUD
        Win32NativeWindowHost.simulateMouseMove(stageMidX, stageMidY)
        assertTrue(mouseMovedOnOverlayRootCount >= 1, "Moving mouse inside video stage must dispatch onMouseMove to overlay root widget")
        assertEquals("true", overlayRoot.getAttribute("hudVisible"), "onMouseMove listener on overlay root must restore hudVisible=true")
        Win32NativeWindowHost.pumpVisualWindowEvents(260L)
        assertEquals(1f, Win32NativeWindowHost.overlayHudCurrentAlpha, "HUD alpha must smoothly fade back to 1f after mouse reveal")
        assertNotEquals(rawVideoWord, readBackBufferWordAt(stageMidX, stageTop + 12), "Top HUD must be visible again after mouse reveal")
    }
}

