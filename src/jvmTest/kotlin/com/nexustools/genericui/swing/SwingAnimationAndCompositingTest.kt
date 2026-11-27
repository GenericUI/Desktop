package com.nexustools.genericui.swing

import com.nexustools.genericui.animation.AnimationSpec
import com.nexustools.genericui.animation.Animator
import com.nexustools.genericui.component.layout.styled.StyledContainer
import com.nexustools.genericui.component.shell.Window
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.enums.window.WindowState
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.css.CssLength
import com.nexustools.genericui.style.css.StyledVisualSpec
import com.nexustools.genericui.style.transition.CssTimingFunction
import com.nexustools.genericui.style.transition.CssTransitionSpec
import com.nexustools.genericui.threading.GenericUiThreading
import java.awt.Color
import java.awt.DisplayMode
import java.awt.Graphics
import java.awt.GraphicsConfiguration
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.event.WindowEvent
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.awt.image.ColorModel
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwingAnimationAndCompositingTest {

    private class MockDisplayDevice(
        initialRefreshRate: Int
    ) : GraphicsDevice() {
        @Volatile
        var mode: DisplayMode = DisplayMode(1920, 1080, 32, initialRefreshRate)

        val config: GraphicsConfiguration = object : GraphicsConfiguration() {
            override fun getDevice(): GraphicsDevice = this@MockDisplayDevice
            override fun getColorModel(): ColorModel = ColorModel.getRGBdefault()
            override fun getColorModel(transparency: Int): ColorModel = ColorModel.getRGBdefault()
            override fun getDefaultTransform(): AffineTransform = AffineTransform()
            override fun getNormalizingTransform(): AffineTransform = AffineTransform()
            override fun getBounds(): Rectangle = Rectangle(0, 0, mode.width, mode.height)
        }

        override fun getType(): Int = TYPE_RASTER_SCREEN
        override fun getIDstring(): String = "MockMonitor"
        override fun getConfigurations(): Array<GraphicsConfiguration> = arrayOf(config)
        override fun getDefaultConfiguration(): GraphicsConfiguration = config
        override fun getDisplayMode(): DisplayMode = mode
    }

    private class MockMonitorPanel(
        initialGc: GraphicsConfiguration?
    ) : JPanel() {
        @Volatile
        var currentGc: GraphicsConfiguration? = initialGc

        override fun getGraphicsConfiguration(): GraphicsConfiguration? = currentGc

        fun moveToDisplay(newGc: GraphicsConfiguration?) {
            val old = currentGc
            currentGc = newGc
            firePropertyChange("graphicsConfiguration", old, newGc)
        }
    }

    private fun runEdtAware(block: () -> Unit) {
        if (GraphicsEnvironment.isHeadless() || SwingUtilities.isEventDispatchThread()) {
            block()
        } else {
            SwingUtilities.invokeAndWait { block() }
        }
    }

    private fun awaitCondition(timeoutMs: Long = 1_500L, predicate: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (predicate()) return true
            Thread.sleep(10L)
        }
        return predicate()
    }

    @Test
    fun swingAnimationNativeImplTracksMonitorRefreshRatesAcrossDisplaysAndUnknownFallback() {
        val factory = SwingNativeFactory()
        val created = factory.createAnimationScheduler()
        assertTrue(created is SwingAnimationNativeImpl)
        val scheduler = created

        try {
            val defaultFallback = scheduler.getRefreshRateHz()
            assertTrue(defaultFallback >= 1.0, "Default/headless refresh rate must be positive, got $defaultFallback")
            if (GraphicsEnvironment.isHeadless()) {
                assertEquals(60.0, defaultFallback)
            }

            val dev60 = MockDisplayDevice(60)
            val dev120 = MockDisplayDevice(120)
            val dev144 = MockDisplayDevice(144)
            val dev240 = MockDisplayDevice(240)
            val devUnknown = MockDisplayDevice(DisplayMode.REFRESH_RATE_UNKNOWN)

            val win1 = MockMonitorPanel(dev60.config)
            scheduler.registerWindow(win1)
            assertEquals(60.0, scheduler.getRefreshRateHz())

            // Simulate moving win1 onto 120Hz ProMotion monitor
            win1.moveToDisplay(dev120.config)
            assertEquals(120.0, scheduler.getRefreshRateHz())

            // Simulate moving win1 onto 144Hz monitor, then 240Hz monitor
            win1.moveToDisplay(dev144.config)
            assertEquals(144.0, scheduler.getRefreshRateHz())

            win1.moveToDisplay(dev240.config)
            assertEquals(240.0, scheduler.getRefreshRateHz())

            // Second window on 120Hz becomes most-recently registered; unregistering falls back to win1 (240Hz)
            val win2 = MockMonitorPanel(dev120.config)
            scheduler.registerWindow(win2)
            assertEquals(120.0, scheduler.getRefreshRateHz())

            scheduler.unregisterWindow(win2)
            assertEquals(240.0, scheduler.getRefreshRateHz())

            // REFRESH_RATE_UNKNOWN falls back cleanly to default screen device or 60.0
            win1.moveToDisplay(devUnknown.config)
            assertEquals(defaultFallback, scheduler.getRefreshRateHz())
        } finally {
            scheduler.dispose()
        }
    }

    @Test
    fun swingAnimationNativeImplDrivesVBlankTicksParksWhenIdleAndDisposesCleanly() {
        val scheduler = SwingAnimationNativeImpl()
        try {
            val dev120 = MockDisplayDevice(120)
            scheduler.registerWindow(MockMonitorPanel(dev120.config))

            // Idle before any consumer is registered: pacer has not started or is parked
            assertFalse(scheduler.hasActiveVBlankWork)
            assertFalse(scheduler.isPacerThreadAlive)

            val tickLatch = CountDownLatch(3)
            val frameNumbers = Collections.synchronizedList(mutableListOf<Long>())
            val deltas = Collections.synchronizedList(mutableListOf<Double>())
            val ranOnEdt = AtomicBoolean(true)

            val watcherId = scheduler.watchVBlank { deltaMs, _, frameNumber ->
                if (!SwingUtilities.isEventDispatchThread()) {
                    ranOnEdt.set(false)
                }
                deltas.add(deltaMs)
                frameNumbers.add(frameNumber)
                tickLatch.countDown()
            }

            assertTrue(scheduler.hasActiveVBlankWork)
            assertTrue(scheduler.isPacerThreadAlive)
            assertTrue(tickLatch.await(3, TimeUnit.SECONDS), "Expected live Swing VBlank ticks on EDT")
            assertTrue(ranOnEdt.get(), "Swing VBlank ticks must dispatch on the Swing EDT")
            assertTrue(frameNumbers.size >= 3 && frameNumbers[0] < frameNumbers[1])
            assertTrue(deltas.all { it > 0.0 })

            // Unwatching VBlank returns hasActiveVBlankWork to false and parks pacer thread at 0% CPU
            scheduler.unwatchVBlank(watcherId)
            assertFalse(scheduler.hasActiveVBlankWork)
            assertTrue(
                awaitCondition(1_500L) { scheduler.pacerThreadState == Thread.State.WAITING },
                "Idle pacer thread should park in WAITING state, got ${scheduler.pacerThreadState}"
            )

            // Timed animation wakes pacer, completes via live ticks, then returns pacer to WAITING
            val updates = AtomicInteger(0)
            val animDone = CountDownLatch(1)
            val handle = scheduler.startAnimation(
                spec = AnimationSpec(durationMs = 45, timingFunction = CssTimingFunction.LINEAR),
                onUpdate = { updates.incrementAndGet() },
                onComplete = { cancelled ->
                    if (!cancelled) animDone.countDown()
                }
            )
            assertTrue(animDone.await(3, TimeUnit.SECONDS), "Timed animation should complete on live VBlank")
            assertFalse(handle.isRunning)
            assertTrue(updates.get() >= 1)
            assertTrue(
                awaitCondition(1_500L) { scheduler.pacerThreadState == Thread.State.WAITING },
                "Pacer thread should park back into WAITING after animation completes"
            )

            scheduler.dispose()
            assertFalse(scheduler.isPacerThreadAlive, "dispose() must cleanly shut down pacer thread")
        } finally {
            scheduler.dispose()
        }
    }

    @Test
    fun swingBaseNativeImplDisablesNativeTransitionStubsAndCssTransitionStepsSmoothly() {
        val baseImpl = SwingBaseNativeImpl()
        assertFalse(baseImpl.supportsNativeTransitions())
        assertFalse(
            baseImpl.runNativeTransition(
                property = "opacity",
                fromValue = "1.0",
                toValue = "0.0",
                spec = CssTransitionSpec("opacity", 200f),
                onComplete = {}
            )
        )
        assertFalse(SwingTabContainerImpl().supportsNativeFadeTransition)
        baseImpl.dispose()

        val prevFactory = GenericUI.activeFactory
        val factory = SwingNativeFactory()
        GenericUI.initialize(factory)
        val win = Window("SwingTransitionTest").apply {
            setWindowSize(Size(320, 200))
        }
        try {
            val hud = StyledContainer().apply {
                widthPx = 200
                heightPx = 80
            }
            win.setContent(hud)
            win.show(factory)

            val nativeHud = hud.ensureNativeImpl() as SwingStyledContainerImpl
            val baseStyle = ComputedStyle(
                opacity = 1.0f,
                transition = "opacity 400ms linear"
            )
            val hiddenStyle = ComputedStyle(
                opacity = 0.0f,
                transition = "opacity 400ms linear"
            )

            hud.applyComputedStyle(baseStyle)
            GenericUiThreading.flushAllQueues()
            runEdtAware {}
            assertEquals(1.0f, nativeHud.getOpacity(), 0.001f)

            // Transitioning opacity via ComputedStyle must NOT snap to 0.0f on frame 0
            hud.applyComputedStyle(hiddenStyle)
            GenericUiThreading.flushAllQueues()
            runEdtAware {}
            assertTrue(
                hud.transitionController.hasActiveSoftwareTransition("opacity"),
                "Swing must run software CssTransitionController opacity transition"
            )
            assertTrue(
                nativeHud.getOpacity() >= 0.85f,
                "Initial transition frame must stay near start opacity 1.0f instead of snapping to 0.0f, got ${nativeHud.getOpacity()}"
            )

            Animator.advanceTimeBy(200.0)
            GenericUiThreading.flushAllQueues()
            runEdtAware {}
            val midOpacity = nativeHud.getOpacity()
            assertTrue(
                midOpacity in 0.25f..0.75f,
                "Expected mid-transition Swing opacity near 0.5f at 200ms, got $midOpacity"
            )

            Animator.flushAll()
            GenericUiThreading.flushAllQueues()
            runEdtAware {}
            assertEquals(0.0f, nativeHud.getOpacity(), 0.001f)
            assertFalse(hud.transitionController.hasActiveSoftwareTransition("opacity"))
        } finally {
            Animator.cancelAllAnimations()
            win.close()
            win.dispose()
            GenericUI.resetForTests()
            GenericUI.activeFactory = prevFactory
        }
    }

    @Test
    fun swingStyledContainerSkipsBackdropBlurWhenTransparentAndAlphaCompositesMidFade() {
        val parent = JPanel(null).apply {
            setBounds(0, 0, 200, 100)
            isOpaque = false
        }

        val splitBackdrop = object : JPanel() {
            override fun paintComponent(g: Graphics) {
                g.color = Color.RED
                g.fillRect(0, 0, width / 2, height)
                g.color = Color.BLUE
                g.fillRect(width / 2, 0, width - width / 2, height)
            }
        }.apply {
            setBounds(0, 0, 200, 100)
        }

        val hudGlass = SwingStyledContainerImpl().apply {
            setStyledVisuals(
                StyledVisualSpec(
                    backgroundColor = RgbaColor(0, 0, 0, 0),
                    backdropBlurPx = 8f,
                    backdropFilter = CssFilter.blur(CssLength.px(8))
                )
            )
            nativeWidget.setBounds(0, 0, 200, 100)
        }
        val hudLabel = SwingTextImpl("span", "").apply {
            setText("HUD")
            nativeWidget.foreground = Color.GREEN
            nativeWidget.setBounds(10, 10, 80, 24)
        }
        hudGlass.addChildNative(hudLabel, -1)

        // Swing paints children in reverse order (index 1 before index 0)
        parent.add(hudGlass.nativeWidget)
        parent.add(splitBackdrop)

        fun renderAtOpacity(alpha: Float): BufferedImage {
            hudGlass.setOpacity(alpha)
            val bmp = BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB)
            val g2 = bmp.createGraphics()
            try {
                parent.paint(g2)
            } finally {
                g2.dispose()
            }
            return bmp
        }

        try {
            // 1. At opacity <= 0.004f, backdrop blur and children are skipped completely
            for (hiddenAlpha in listOf(0.0f, 0.004f)) {
                val hiddenBmp = renderAtOpacity(hiddenAlpha)
                val seamPixel = hiddenBmp.getRGB(99, 50)
                val redCh = (seamPixel ushr 16) and 0xFF
                val blueCh = seamPixel and 0xFF
                assertEquals(255, redCh, "At opacity=$hiddenAlpha left seam should stay pure red")
                assertEquals(0, blueCh, "At opacity=$hiddenAlpha backdrop blur must be skipped (blue=$blueCh)")
            }

            // 2. At opacity = 0.5f, backdrop blur composites smoothly at half strength compared to 1.0f
            val midBmp = renderAtOpacity(0.5f)
            val fullBmp = renderAtOpacity(1.0f)

            val midBlue = midBmp.getRGB(99, 50) and 0xFF
            val fullBlue = fullBmp.getRGB(99, 50) and 0xFF
            assertTrue(fullBlue > 30, "Full-opacity backdrop blur should pull blue across seam, got $fullBlue")
            assertTrue(
                midBlue in 10 until fullBlue,
                "Half-opacity backdrop blur ($midBlue) must be strictly between 0 and full-opacity ($fullBlue)"
            )
        } finally {
            hudLabel.dispose()
            hudGlass.dispose()
        }
    }

    @Test
    fun swingWindowFullscreenBeforeShowAndAlwaysOnTopDeiconifyRestoreBehaveCleanly() {
        val scheduler = SwingAnimationNativeImpl()
        try {
            val baseTracked = scheduler.getTrackedWindowCount()
            runEdtAware {
                val win = SwingWindowImpl()
                try {
                    win.setFullscreen(true)
                    win.setAlwaysOnTop(true)
                    win.showWindow()

                    assertTrue(
                        scheduler.getTrackedWindowCount() >= baseTracked + 1,
                        "SwingWindowImpl.showWindow() must register window with SwingAnimationNativeImpl"
                    )

                    val frame = win.jFrame
                    if (frame != null) {
                        assertTrue(frame.isUndecorated, "Fullscreen JFrame must be undecorated on showWindow()")
                        // Verify windowDeiconified restores isAlwaysOnTop when alwaysOnTopFlag is true
                        frame.isAlwaysOnTop = false
                        val deiconifiedEvent = WindowEvent(frame, WindowEvent.WINDOW_DEICONIFIED)
                        frame.windowListeners.forEach { it.windowDeiconified(deiconifiedEvent) }
                        assertTrue(
                            frame.isAlwaysOnTop,
                            "windowDeiconified must restore JFrame.isAlwaysOnTop when alwaysOnTopFlag is true"
                        )
                    } else {
                        assertTrue(win.isFullscreen())
                        assertTrue(win.isAlwaysOnTop())
                    }
                } finally {
                    win.closeWindow()
                    win.dispose()
                }
            }
            assertEquals(
                baseTracked,
                scheduler.getTrackedWindowCount(),
                "SwingWindowImpl.closeWindow()/dispose() must unregister window cleanly"
            )
        } finally {
            scheduler.dispose()
        }
    }
}
