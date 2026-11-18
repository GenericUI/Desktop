@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.component.layout.Container
import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.component.shell.Window
import com.nexustools.genericui.component.system.PreferenceStore
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.core.NativeFactory
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.enums.window.WindowState
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.image.ImageColorSpace
import com.nexustools.genericui.image.PixelFormat
import com.nexustools.genericui.nativeimpl.shell.ApplicationNativeImpl
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl
import com.nexustools.genericui.res.DrawableResource
import com.nexustools.genericui.resources.ResourceID
import com.nexustools.genericui.resources.ResourceType
import com.nexustools.genericui.window.MainWindowSavedState
import com.nexustools.genericui.window.WindowAspectRatioHelper
import com.nexustools.genericui.window.WindowConfiguration
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.alloc
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import platform.AppKit.NSBackingStoreBuffered
import platform.AppKit.NSWindow
import platform.AppKit.NSWindowStyleMaskClosable
import platform.AppKit.NSWindowStyleMaskFullSizeContentView
import platform.AppKit.NSWindowStyleMaskMiniaturizable
import platform.AppKit.NSWindowStyleMaskResizable
import platform.AppKit.NSWindowStyleMaskTitled
import platform.Foundation.NSMakeRect
import platform.Foundation.NSMakeSize
import platform.Foundation.NSSize
import platform.objc.class_getMethodImplementation
import platform.objc.object_getClass
import platform.objc.sel_registerName
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MacosWindowAspectRatioAlwaysOnTopAndDockIconTest {

    private var savedAppleIconExplicitFlag: Boolean = false

    @BeforeTest
    fun saveGlobals() {
        savedAppleIconExplicitFlag = GenericUI.appleIconExplicitlyProvided
    }

    @AfterTest
    fun restoreGlobals() {
        GenericUI.appleIconExplicitlyProvided = savedAppleIconExplicitFlag
    }

    @Test
    fun alwaysOnTopTogglesWindowLevelAttributesAndToolModalPrecedence() {
        val impl = MacosWindowNativeImpl()
        assertFalse(impl.isAlwaysOnTop())

        impl.setAlwaysOnTop(true)
        assertTrue(impl.isAlwaysOnTop())
        assertEquals(true, impl.nativeWidget.attributes["alwaysOnTop"])
        assertEquals("NSFloatingWindowLevel", impl.nativeWidget.attributes["level"])

        impl.setAlwaysOnTop(false)
        assertFalse(impl.isAlwaysOnTop())
        assertEquals(false, impl.nativeWidget.attributes["alwaysOnTop"])
        assertEquals("NSNormalWindowLevel", impl.nativeWidget.attributes["level"])

        // Tool-modal owned floating window preserves floating level while alwaysOnTopOfOwner is true
        val owner = MacosWindowNativeImpl()
        impl.configureAsToolModalWindow(
            ownerWindow = owner,
            showInTaskbar = false,
            minimizable = false,
            alwaysOnTopOfOwner = true
        )
        assertEquals("NSFloatingWindowLevel", impl.nativeWidget.attributes["level"])
        impl.setAlwaysOnTop(false)
        assertEquals("NSFloatingWindowLevel", impl.nativeWidget.attributes["level"])
    }

    @Test
    fun aspectRatioEnforcesNormalSizeAndAllowsUnconstrainedZoomedAndFullscreen() {
        val impl = MacosWindowNativeImpl()
        val sixteenByNine = 16.0 / 9.0

        assertNull(impl.getAspectRatio())
        impl.setAspectRatio(sixteenByNine)
        assertEquals(sixteenByNine, impl.getAspectRatio())
        assertEquals(sixteenByNine, impl.nativeWidget.attributes["aspectRatio"])

        impl.setWindowSize(Size(1600, 1000))
        assertEquals(Size(1600, 900), impl.getWindowSize())
        assertTrue(WindowAspectRatioHelper.matchesAspectRatio(impl.getWindowSize(), sixteenByNine, tolerancePx = 2))

        // Native zoom (maximized) reports unrestricted full work-area bounds
        impl.notifyHostResizedFromOs(widthPx = 1920, heightPx = 1175, fullscreenNow = false, maximizedNow = true)
        assertEquals(WindowState.MAXIMIZED, impl.getWindowState())
        assertEquals(Size(1920, 1175), impl.getWindowSize())

        // Native fullscreen reports unrestricted display bounds
        impl.notifyHostResizedFromOs(widthPx = 2560, heightPx = 1600, fullscreenNow = true, maximizedNow = false)
        assertEquals(WindowState.FULLSCREEN, impl.getWindowState())
        assertTrue(impl.isFullscreen())
        assertEquals(Size(2560, 1600), impl.getWindowSize())

        // Returning to NORMAL restores 16:9 aspect enforcement on subsequent setWindowSize calls
        impl.notifyHostResizedFromOs(widthPx = 1400, heightPx = 900, fullscreenNow = false, maximizedNow = false)
        assertEquals(WindowState.NORMAL, impl.getWindowState())
        assertFalse(impl.isFullscreen())
        impl.setWindowSize(Size(1280, 900))
        assertEquals(Size(1280, 720), impl.getWindowSize())

        impl.setAspectRatio(null)
        assertNull(impl.getAspectRatio())
        impl.setWindowSize(Size(1111, 777))
        assertEquals(Size(1111, 777), impl.getWindowSize())
    }

    @Test
    fun runtimeDockIconWrapsGenericIconWithWhiteSquircleOnlyWhenAppleIconNotExplicit() {
        val size = 64
        val solidBlue = ByteArray(size * size * 4)
        for (i in 0 until size * size) {
            val base = i * 4
            solidBlue[base] = 0x10.toByte()
            solidBlue[base + 1] = 0x55.toByte()
            solidBlue[base + 2] = 0xFF.toByte()
            solidBlue[base + 3] = 0xFF.toByte()
        }
        val sourceBuffer = ImageBuffer(
            width = size,
            height = size,
            pixelFormat = PixelFormat.RGBA_8888,
            colorSpace = ImageColorSpace.SRGB,
            bytes = solidBlue
        )

        // Generic fallback icon -> composed >= 512x512 white squircle tile with transparent corners
        GenericUI.appleIconExplicitlyProvided = false
        assertFalse(
            MacosNativeWindowHost.isAlreadyWrappedAppleSquircleIcon(sourceBuffer),
            "Raw full-bleed blue icon must not be mistaken for a wrapped Apple squircle tile"
        )
        val composed = MacosNativeWindowHost.composeRuntimeDockIconBuffer(sourceBuffer)
        assertEquals(512, composed.width)
        assertEquals(512, composed.height)
        assertEquals(PixelFormat.RGBA_8888, composed.pixelFormat)
        assertTrue(
            MacosNativeWindowHost.isAlreadyWrappedAppleSquircleIcon(composed),
            "Composed Apple squircle icon must be recognized as already wrapped"
        )

        val cornerAlpha = composed.bytes[3].toInt() and 0xFF
        assertEquals(0, cornerAlpha, "Outer squircle corner must remain transparent")

        val cx = composed.width / 2
        val cy = composed.height / 2
        val centerIdx = (cy * composed.width + cx) * 4
        val centerR = composed.bytes[centerIdx].toInt() and 0xFF
        val centerB = composed.bytes[centerIdx + 2].toInt() and 0xFF
        val centerA = composed.bytes[centerIdx + 3].toInt() and 0xFF
        assertTrue(centerA == 255 && centerB > centerR, "Center should preserve scaled blue artwork")

        val borderStartY = (composed.height * 0.11).toInt()
        val borderEndY = (composed.height * 0.18).toInt()
        val hasWhiteTileBorder = (borderStartY..borderEndY).any { y ->
            val idx = (y * composed.width + cx) * 4
            val r = composed.bytes[idx].toInt() and 0xFF
            val g = composed.bytes[idx + 1].toInt() and 0xFF
            val b = composed.bytes[idx + 2].toInt() and 0xFF
            val a = composed.bytes[idx + 3].toInt() and 0xFF
            r >= 240 && g >= 240 && b >= 240 && a >= 240
        }
        assertTrue(hasWhiteTileBorder, "Generic fallback icon should sit on Chrome-style white squircle tile")

        // Explicit appleIcon -> returned unwrapped with full-bleed blue corner untouched
        GenericUI.appleIconExplicitlyProvided = true
        val explicitUnwrapped = MacosNativeWindowHost.composeRuntimeDockIconBuffer(sourceBuffer)
        assertEquals(64, explicitUnwrapped.width)
        assertEquals(64, explicitUnwrapped.height)
        assertEquals(0xFF, explicitUnwrapped.bytes[3].toInt() and 0xFF)
        assertEquals(0xFF, explicitUnwrapped.bytes[2].toInt() and 0xFF)
    }

    @Test
    fun svgFallbackDrawableDecodesAndTilesViaCoreGraphicsRasterizer() {
        val drawable = DrawableResource(
            resId = ResourceID(id = 0x7F010099, type = ResourceType.DRAWABLE, name = "icon", relativePath = "drawable/icon.svg"),
            format = "svg",
            svgSource = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><rect width="64" height="64" fill="#1E88E5"/></svg>"""
        )
        val decoded = MacosNativeWindowHost.decodeDrawableToBuffer(drawable)
        assertNotNull(decoded)
        assertTrue(decoded.width >= 64 && decoded.height >= 64)

        GenericUI.appleIconExplicitlyProvided = false
        val composed = MacosNativeWindowHost.composeRuntimeDockIconBuffer(decoded)
        assertTrue(composed.width >= 256 && composed.height >= 256)
        assertEquals(0, composed.bytes[3].toInt() and 0xFF)
    }

    @Test
    fun svgCompactLeadingDotPathTokensRasterizeWithoutHanging() {
        val drawable = DrawableResource(
            resId = ResourceID(id = 0x7F01009A, type = ResourceType.DRAWABLE, name = "ic_prev_dot", relativePath = "drawable/ic_prev_dot.svg"),
            format = "svg",
            svgSource = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16" fill="#FFFFFF"><path d="M4 4a.5.5 0 0 1 1 0v3.248l6.267-3.636c.54-.313 1.232.066 1.232.696v7.384c0 .63-.692 1.01-1.232.697L5 8.753V12a.5.5 0 0 1-1 0z"/></svg>"""
        )
        val decoded = MacosNativeWindowHost.decodeDrawableToBuffer(drawable)
        assertNotNull(decoded, "SVG path containing compact leading-dot decimals must decode promptly")
        assertTrue(decoded.width >= 16 && decoded.height >= 16)
        var nonTransparentPixels = 0
        var i = 3
        while (i < decoded.bytes.size) {
            if ((decoded.bytes[i].toInt() and 0xFF) > 0) {
                nonTransparentPixels++
            }
            i += 4
        }
        assertTrue(nonTransparentPixels > 10, "Rasterized leading-dot SVG path must draw visible pixels")
    }

    @Test
    fun invalidAspectRatioThrowsIllegalArgument() {
        val impl = MacosWindowNativeImpl()
        assertFailsWith<IllegalArgumentException> { impl.setAspectRatio(0.0) }
        assertFailsWith<IllegalArgumentException> { impl.setAspectRatio(-4.0 / 3.0) }
        assertFailsWith<IllegalArgumentException> { impl.setAspectRatio(Double.NaN) }
    }

    @Test
    fun hiddenInitialWindowNeverReceivesVisibleTrueOnNativeAttach() {
        val baseFactory = MacosNativeFactory()
        val setVisibleCalls = mutableListOf<Boolean>()
        val recordingWindowImpl = object : WindowNativeImpl<DesktopNativeWidget> by (baseFactory.createWindow() as MacosWindowNativeImpl) {
            override fun setVisible(visible: Boolean) {
                setVisibleCalls.add(visible)
            }
        }
        val factory = object : NativeFactory by baseFactory {
            override fun createWindow(): WindowNativeImpl<*> = recordingWindowImpl
        }

        val window = Window(title = "Hidden Receiver", initialSize = Size(960, 540))
        window.setContent(Container())
        window.hide()
        assertTrue(setVisibleCalls.isEmpty())

        window.show(factory)

        assertFalse(setVisibleCalls.contains(true), "Starting hidden must never pass visible=true on native attach: $setVisibleCalls")
        assertFalse(window.isVisible)
        window.close()
    }

    @Test
    fun applicationExitGuardsAgainstRecursiveWindowCloseAndExitApplication() {
        val baseFactory = MacosNativeFactory()
        var closeCalls = 0
        var exitCalls = 0
        lateinit var testApp: Application

        val baseWin = baseFactory.createWindow() as MacosWindowNativeImpl
        val recWin = object : WindowNativeImpl<DesktopNativeWidget> by baseWin {
            override fun closeWindow() {
                closeCalls++
                baseWin.closeWindow()
                testApp.exit(0)
            }
        }
        val baseAppImpl = baseFactory.createApplication() as MacosApplicationNativeImpl
        val recAppImpl = object : ApplicationNativeImpl<MacosViewHandle> by baseAppImpl {
            override fun runEventLoop(onStarted: () -> Unit) {
                onStarted()
            }

            override fun exitApplication(exitCode: Int) {
                exitCalls++
            }
        }
        val factory = object : NativeFactory by baseFactory {
            override fun createWindow(): WindowNativeImpl<*> = recWin
            override fun createApplication(): ApplicationNativeImpl<*> = recAppImpl
        }

        val prevCurrent = Application.current
        testApp = object : Application() {
            override fun onApplicationStarted() {
                mainWindow.hide()
            }
        }
        try {
            testApp.run(factory)
            testApp.exit(0)
            assertEquals(1, closeCalls)
            assertEquals(1, exitCalls)
        } finally {
            Application.current = prevCurrent
        }
    }

    @Test
    fun fullscreenExitRestoresLastWindowedNormalSizeAndAspectClampConverges() {
        val baseFactory = MacosNativeFactory()
        val winImpl = MacosWindowNativeImpl()
        val setWindowSizeHistory = mutableListOf<Size>()
        val wrappedWinImpl = object : WindowNativeImpl<DesktopNativeWidget> by winImpl {
            private var resizeCb: ((WindowConfiguration) -> Unit)? = null
            var simulateRejectOversizedTo: Size? = null

            override fun setOnWindowResize(listener: ((WindowConfiguration) -> Unit)?) {
                resizeCb = listener
                winImpl.setOnWindowResize(listener)
            }

            override fun setWindowSize(size: Size) {
                setWindowSizeHistory.add(size)
                winImpl.setWindowSize(size)
                val rejectSize = simulateRejectOversizedTo
                if (rejectSize != null && size != rejectSize) {
                    resizeCb?.invoke(
                        WindowConfiguration(
                            widthPx = rejectSize.width,
                            heightPx = rejectSize.height,
                            windowState = WindowState.NORMAL,
                            isFullscreen = false
                        )
                    )
                }
            }

            fun fireResize(w: Int, h: Int, fs: Boolean) {
                winImpl.notifyHostResizedFromOs(w, h, fullscreenNow = fs, maximizedNow = false)
            }
        }
        val factory = object : NativeFactory by baseFactory {
            override fun createWindow(): WindowNativeImpl<*> = wrappedWinImpl
        }

        val window = Window(title = "Receiver", initialSize = Size(960, 540))
        window.setContent(Container())
        window.setAspectRatio(16, 9)
        window.setMinWindowSize(Size(640, 360))
        window.show(factory)

        setWindowSizeHistory.clear()
        wrappedWinImpl.fireResize(1440, 900, fs = true)
        assertTrue(window.isFullscreen())

        // Exiting fullscreen restores saved 960x540 normal size even if OS reports 1440x875 first
        wrappedWinImpl.fireResize(1440, 875, fs = false)
        assertFalse(window.isFullscreen())
        assertEquals(WindowState.NORMAL, window.getWindowState())
        assertEquals(Size(960, 540), window.getWindowSize())

        // Simulate OS rejecting an enlarged clamp request by echoing back 1440x875: must converge without looping
        setWindowSizeHistory.clear()
        wrappedWinImpl.simulateRejectOversizedTo = Size(1440, 875)
        wrappedWinImpl.fireResize(1440, 875, fs = false)
        assertTrue(setWindowSizeHistory.size <= 1, "Aspect clamp must converge in <=1 correction, got $setWindowSizeHistory")

        window.close()
    }

    @Test
    fun clearWindowContentAspectRatioUsesResizeIncrementsAndPreventsNaNOnFullscreenExit() {
        val styleMask = NSWindowStyleMaskTitled or
            NSWindowStyleMaskClosable or
            NSWindowStyleMaskMiniaturizable or
            NSWindowStyleMaskResizable or
            NSWindowStyleMaskFullSizeContentView
        val nsWindow = NSWindow(
            contentRect = NSMakeRect(0.0, 0.0, 1280.0, 720.0),
            styleMask = styleMask,
            backing = NSBackingStoreBuffered,
            defer = false
        )
        nsWindow.releasedWhenClosed = false
        nsWindow.contentAspectRatio = NSMakeSize(16.0 / 9.0, 1.0)

        MacosNativeWindowHost.clearWindowContentAspectRatio(nsWindow)

        var incW = 0.0
        var incH = 0.0
        nsWindow.contentResizeIncrements.useContents {
            incW = width
            incH = height
        }
        assertEquals(1.0, incW, 1e-6)
        assertEquals(1.0, incH, 1e-6)

        var aspectW = -1.0
        var aspectH = -1.0
        nsWindow.contentAspectRatio.useContents {
            aspectW = width
            aspectH = height
        }
        assertEquals(0.0, aspectW, 1e-6)
        assertEquals(0.0, aspectH, 1e-6)

        val validSizeSel = sel_registerName("_validSize:force:")
        val nsWindowClass = object_getClass(nsWindow)
        val rawImp = class_getMethodImplementation(nsWindowClass, validSizeSel)
        assertNotNull(rawImp, "Expected -[NSWindow _validSize:force:] IMP")
        val validSizeFn = rawImp.reinterpret<CFunction<(COpaquePointer?, COpaquePointer?, CPointer<NSSize>, Boolean) -> Unit>>()
        val winPtr = interpretCPointer<CPointed>(nsWindow.objcPtr())

        memScoped {
            val sizeArg = alloc<NSSize>()
            sizeArg.width = 1280.0
            sizeArg.height = 748.0
            validSizeFn(winPtr, validSizeSel, sizeArg.ptr, false)
            assertTrue(sizeArg.width.isFinite() && !sizeArg.width.isNaN(), "Expected finite width, got ${sizeArg.width}")
            assertTrue(sizeArg.height.isFinite() && !sizeArg.height.isNaN(), "Expected finite height, got ${sizeArg.height}")
            assertEquals(1280.0, sizeArg.width, 1e-6)
            assertEquals(748.0, sizeArg.height, 1e-6)

            nsWindow.setFrame(NSMakeRect(100.0, 100.0, sizeArg.width, sizeArg.height), display = false)
            var frameW = 0.0
            var frameH = 0.0
            nsWindow.frame.useContents {
                frameW = size.width
                frameH = size.height
            }
            assertTrue(frameW.isFinite() && frameH.isFinite() && frameW > 0.0 && frameH > 0.0)

            // Re-applying 16:9 ratio clamps (1000, 700) to (1000, 562)
            nsWindow.contentAspectRatio = NSMakeSize(16.0 / 9.0, 1.0)
            sizeArg.width = 1000.0
            sizeArg.height = 700.0
            validSizeFn(winPtr, validSizeSel, sizeArg.ptr, false)
            assertEquals(1000.0, sizeArg.width, 1e-6)
            assertEquals(562.0, sizeArg.height, 2.0)
        }
    }

    @Test
    fun mainWindowDefaultsToTwoThirdsPrimaryMonitorCenteredAndRecoversOffscreenSavedPosition() {
        val baseFactory = MacosNativeFactory()
        val prevFactory = GenericUI.activeFactory
        val prevCurrent = Application.current
        try {
            val bounds1080p = Rect(0, 0, 1920, 1080)
            val factory1080p = object : NativeFactory by baseFactory {
                override fun getPrimaryMonitorBounds(): Rect = bounds1080p
                override fun isWindowRectOnScreen(rect: Rect): Boolean =
                    MainWindowSavedState.intersectsVisibleArea(rect, bounds1080p)
            }
            GenericUI.initialize(factory1080p)

            val store = PreferenceStore("macos_two_thirds_default_${kotlin.random.Random.nextInt()}").apply {
                clear()
                flush()
            }
            val app1 = object : Application() {
                init {
                    mainWindow.setContent(Container())
                }

                override fun getPreferenceStore(): PreferenceStore = store
            }
            app1.mainWindow.show(factory1080p)
            assertFalse(app1.mainWindow.isFullscreen())
            assertEquals(Rect(320, 180, 1280, 720), app1.mainWindow.windowBounds)

            // User moves off-screen / saves an off-screen position, then restarts -> re-centers cleanly
            store.putElement(
                MainWindowSavedState.PREF_KEY,
                MainWindowSavedState(
                    x = -5000,
                    y = -5000,
                    width = 960,
                    height = 540,
                    fullscreen = false,
                    windowState = WindowState.NORMAL
                ).toPreferenceElement()
            )
            store.flush()
            app1.dispose()

            val app2 = object : Application() {
                init {
                    mainWindow.setContent(Container())
                }

                override fun getPreferenceStore(): PreferenceStore = store
            }
            app2.mainWindow.show(factory1080p)
            assertFalse(app2.mainWindow.isFullscreen())
            assertEquals(Rect(480, 270, 960, 540), app2.mainWindow.windowBounds)
            app2.dispose()

            // Aspect ratio 16:9 with 1920x1200 primary monitor clamps 2/3 (1280x800) to 1280x720 centered
            val bounds1200p = Rect(0, 0, 1920, 1200)
            val factory1200p = object : NativeFactory by baseFactory {
                override fun getPrimaryMonitorBounds(): Rect = bounds1200p
                override fun isWindowRectOnScreen(rect: Rect): Boolean =
                    MainWindowSavedState.intersectsVisibleArea(rect, bounds1200p)
            }
            val store2 = PreferenceStore("macos_two_thirds_aspect_${kotlin.random.Random.nextInt()}").apply {
                clear()
                flush()
            }
            val app3 = object : Application() {
                init {
                    mainWindow.setAspectRatio(16, 9)
                    mainWindow.setMinWindowSize(Size(640, 360))
                    mainWindow.setContent(Container())
                }

                override fun getPreferenceStore(): PreferenceStore = store2
            }
            app3.mainWindow.show(factory1200p)
            assertEquals(Rect(320, 240, 1280, 720), app3.mainWindow.windowBounds)
            app3.dispose()

            // Backend reporting supportsWindowStatePersistence=false ignores saved state & monitor bounds
            val nonWindowedFactory = object : NativeFactory by factory1080p {
                override val supportsWindowStatePersistence: Boolean = false
            }
            GenericUI.initialize(nonWindowedFactory)
            val app4 = object : Application() {
                init {
                    mainWindow.setContent(Container())
                }

                override fun getPreferenceStore(): PreferenceStore = store
            }
            app4.mainWindow.show(nonWindowedFactory)
            assertEquals(Size(1280, 800), app4.mainWindow.getWindowSize())
            app4.dispose()
        } finally {
            Application.current = prevCurrent
            GenericUI.activeFactory = prevFactory
        }
    }

    @Test
    fun macosWindowPositionAndRapidFullscreenHideShowPreserveState() {
        val baseFactory = MacosNativeFactory()
        val prevFactory = GenericUI.activeFactory
        val prevCurrent = Application.current
        try {
            val factory = object : NativeFactory by baseFactory {
                override fun getPrimaryMonitorBounds(): Rect = Rect(0, 0, 1920, 1080)
            }
            GenericUI.initialize(factory)

            val store = PreferenceStore("macos_rapid_fs_hide_show_${kotlin.random.Random.nextInt()}").apply {
                clear()
                flush()
            }
            val app = object : Application() {
                init {
                    defaultMainWindowFullscreen = true
                    mainWindow.setMinWindowSize(Size(640, 360))
                    mainWindow.setAspectRatio(16, 9)
                    mainWindow.setContent(Container())
                    mainWindow.hide()
                }

                override fun getPreferenceStore(): PreferenceStore = store
            }

            // Hidden before first cast -> not fullscreen yet
            app.mainWindow.show(factory)
            assertFalse(app.mainWindow.isVisible)
            assertFalse(app.mainWindow.isFullscreen())

            // Cast 1 shows window -> enters default fullscreen
            app.mainWindow.show()
            assertTrue(app.mainWindow.isVisible)
            assertTrue(app.mainWindow.isFullscreen())

            // Rapid hide (simulating OS tearing down fullscreen while hidden) + Cast 2 show
            app.mainWindow.hide()
            assertFalse(app.mainWindow.isVisible)
            val impl = app.mainWindow.nativeImpl as MacosWindowNativeImpl
            impl.notifyHostResizedFromOs(widthPx = 1280, heightPx = 720, fullscreenNow = false, maximizedNow = false)
            assertTrue(app.mainWindow.isFullscreen(), "Hidden window teardown must not wipe Window.fullscreenState")

            app.mainWindow.setAspectRatio(16, 9)
            app.mainWindow.show()
            assertTrue(app.mainWindow.isVisible)
            assertTrue(app.mainWindow.isFullscreen())

            // Exit fullscreen -> move & resize -> persist across hide/show and native move notification
            app.mainWindow.setFullscreen(false)
            assertFalse(app.mainWindow.isFullscreen())
            impl.notifyHostMovedFromOs(160, 120)
            app.mainWindow.setWindowSize(Size(1024, 576))
            app.mainWindow.hide()
            app.mainWindow.show()

            assertFalse(app.mainWindow.isFullscreen())
            assertEquals(Rect(160, 120, 1024, 576), app.mainWindow.windowBounds)
            val saved = MainWindowSavedState.fromPreferenceElement(store.getElement(MainWindowSavedState.PREF_KEY))
            assertNotNull(saved)
            assertFalse(saved.fullscreen)
            assertEquals(Rect(160, 120, 1024, 576), saved.toBounds())
            app.dispose()
        } finally {
            Application.current = prevCurrent
            GenericUI.activeFactory = prevFactory
        }
    }
}
