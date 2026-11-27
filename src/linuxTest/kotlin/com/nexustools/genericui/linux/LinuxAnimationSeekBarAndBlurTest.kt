@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.linux

import com.nexustools.genericui.animation.AnimationSpec
import com.nexustools.genericui.component.action.styled.StyledButton
import com.nexustools.genericui.component.form.styled.StyledSlider
import com.nexustools.genericui.component.layout.Container
import com.nexustools.genericui.component.shell.Window
import com.nexustools.genericui.component.surface.styled.StyledComponent
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.widget.DesktopStyledComponentNativeImpl
import com.nexustools.genericui.enums.css.CssCursor
import com.nexustools.genericui.enums.css.CssPointerEvents
import com.nexustools.genericui.enums.css.CssPosition
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.gtk.GtkAnimationNativeImpl
import com.nexustools.genericui.gtk.GtkNativeFactory
import com.nexustools.genericui.gtk.GtkNativeWindowHost
import com.nexustools.genericui.gtk.GtkStyledComponentNativeImpl
import com.nexustools.genericui.gtk.GtkStyledContainerNativeImpl
import com.nexustools.genericui.gtk.GtkTabContainerNativeImpl
import com.nexustools.genericui.gtk.GtkWindowNativeImpl
import com.nexustools.genericui.gtk.impl.GtkRangeSliderNativeImpl
import com.nexustools.genericui.linux.titlebar.LinuxOverlayTitleBarResolver
import com.nexustools.genericui.linux.titlebar.LinuxThemePaletteCatalog
import com.nexustools.genericui.kde.KdeAnimationNativeImpl
import com.nexustools.genericui.kde.KdeNativeFactory
import com.nexustools.genericui.kde.KdeNativeWindowHost
import com.nexustools.genericui.kde.KdeRangeSliderNativeImpl
import com.nexustools.genericui.kde.KdeStyledComponentNativeImpl
import com.nexustools.genericui.kde.KdeStyledContainerNativeImpl
import com.nexustools.genericui.kde.KdeTabContainerNativeImpl
import com.nexustools.genericui.kde.KdeWindowNativeImpl
import com.nexustools.genericui.linux.style.LinuxBackdropFilterController
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssCornerRadii
import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.css.CssLength
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.style.transition.CssTimingFunction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxAnimationSeekBarAndBlurTest {

    @Test
    fun testGtkAndKdeNativeFactoriesCreateNativeAnimationSchedulersWithCleanIdleLifecycle() {
        val gtkFactory = GtkNativeFactory()
        GenericUI.initialize(gtkFactory)
        val gtkScheduler = gtkFactory.createAnimationScheduler()
        assertTrue(gtkScheduler is GtkAnimationNativeImpl)
        try {
            assertTrue(gtkScheduler.getRefreshRateHz() in 10.0..500.0)
            var ticks = 0
            val watchId = gtkScheduler.watchVBlank { _, _, _ -> ticks++ }
            assertEquals(1, gtkScheduler.activeVBlankConsumerCount)
            gtkScheduler.stepFrame(16.0)
            assertTrue(ticks > 0)

            var done = false
            val anim = gtkScheduler.startAnimation(
                spec = AnimationSpec(durationMs = 100, timingFunction = CssTimingFunction.EASE),
                target = null,
                onUpdate = {},
                onComplete = { cancelled -> if (!cancelled) done = true }
            )
            gtkScheduler.advanceTimeBy(120.0)
            assertTrue(done)
            gtkScheduler.unwatchVBlank(watchId)
            assertFalse(anim.isRunning)
            assertEquals(0, gtkScheduler.activeVBlankConsumerCount)
        } finally {
            gtkScheduler.dispose()
            assertTrue(gtkScheduler.isDisposed)
        }

        val kdeFactory = KdeNativeFactory()
        GenericUI.initialize(kdeFactory)
        val kdeScheduler = kdeFactory.createAnimationScheduler()
        assertTrue(kdeScheduler is KdeAnimationNativeImpl)
        try {
            assertTrue(kdeScheduler.getRefreshRateHz() in 10.0..500.0)
            var kdeTicks = 0
            val watchId = kdeScheduler.watchVBlank { _, _, _ -> kdeTicks++ }
            assertEquals(1, kdeScheduler.activeVBlankConsumerCount)
            kdeScheduler.stepFrame(16.0)
            assertTrue(kdeTicks > 0)
            kdeScheduler.unwatchVBlank(watchId)
            assertEquals(0, kdeScheduler.activeVBlankConsumerCount)
        } finally {
            kdeScheduler.dispose()
            assertTrue(kdeScheduler.isDisposed)
        }
    }

    @Test
    fun testLinuxTabContainersDisableNativeFadeTransitionFlag() {
        assertFalse(GtkTabContainerNativeImpl().supportsNativeFadeTransition)
        assertFalse(KdeTabContainerNativeImpl().supportsNativeFadeTransition)
    }

    @Test
    fun testLinuxOverlayTitleBarResolverDoesNotForceBlurWhenZero() {
        val profile = LinuxThemePaletteCatalog.resolveLibadwaitaProfile(
            isDark = true,
            accentKey = null,
            portalAccent = null,
            buttonLayout = null
        )

        val unsetBar = DesktopNativeWidget("OverlayTitleBar", "div")
        val unsetAppearance = LinuxOverlayTitleBarResolver.resolveAppearance(
            windowTitleBar = unsetBar,
            titleLabelWidget = null,
            effectiveProfile = profile,
            contextIsDark = true,
            isUndecoratedWindow = true,
            isMaximized = false
        )
        assertNotNull(unsetAppearance.backdropFilter?.blur, "Unset translucent titlebar preserves default 12px blur")

        val zeroBlurBar = DesktopNativeWidget("OverlayTitleBar", "div").apply {
            attributes["backdropBlurPx"] = 0f
        }
        assertNull(LinuxOverlayTitleBarResolver.resolveBackdropFilter(zeroBlurBar))
        val zeroAppearance = LinuxOverlayTitleBarResolver.resolveAppearance(
            windowTitleBar = zeroBlurBar,
            titleLabelWidget = null,
            effectiveProfile = profile,
            contextIsDark = true,
            isUndecoratedWindow = true,
            isMaximized = false
        )
        assertNull(zeroAppearance.backdropFilter, "Explicit backdropBlurPx=0 must not force 12px CPU blur")

        val zeroDataAttrBar = DesktopNativeWidget("OverlayTitleBar", "div").apply {
            attributes["data-backdrop-blur-px"] = "0px"
        }
        val zeroDataAppearance = LinuxOverlayTitleBarResolver.resolveAppearance(
            windowTitleBar = zeroDataAttrBar,
            titleLabelWidget = null,
            effectiveProfile = profile,
            contextIsDark = true,
            isUndecoratedWindow = true,
            isMaximized = false
        )
        assertNull(zeroDataAppearance.backdropFilter, "Explicit data-backdrop-blur-px=0px must not force 12px CPU blur")
    }

    @Test
    fun testGtkAndKdeStyledSliderProximalRelativeZIndexPointerEventsAndWideRangeSlider() {
        val gtkFactory = GtkNativeFactory()
        val kdeFactory = KdeNativeFactory()

        fun windowRectOf(w: DesktopNativeWidget, root: DesktopNativeWidget): Rect {
            var gx = w.bounds.x
            var gy = w.bounds.y
            var p = w.parentWidget
            while (p != null && p !== root) {
                gx += p.bounds.x
                gy += p.bounds.y
                p = p.parentWidget
            }
            return Rect(gx, gy, w.bounds.width, w.bounds.height)
        }

        fun buildProximalScene(): List<Any> {
            val viewportRoot = Container().apply {
                style.setPosition(CssPosition.STATIC)
                style.setWidthPx(800)
                style.setHeightPx(120)
            }
            val relAncestor = Container().apply {
                style.setPosition(CssPosition.RELATIVE)
                style.setMarginLeft(CssLength.px(100))
                style.setMarginTop(CssLength.px(20))
                style.setWidthPx(400)
                style.setHeightPx(60)
                style.setCursor(CssCursor.POINTER)
            }
            val staticMiddle = Container().apply {
                style.setPosition(CssPosition.STATIC)
                style.setWidthPx(400)
                style.setHeightPx(60)
            }
            val trackBtn = StyledButton(text = "").apply {
                onClick = {}
                style.apply {
                    setPosition(CssPosition.ABSOLUTE)
                    setLeft(CssLength.px(0))
                    setTop(CssLength.percent(50f))
                    setWidthPx(400)
                    setHeightPx(4)
                    setTransform(CssTransform.parse("translate(0px, -50%)")!!)
                    setZIndex(1)
                    setBackgroundColor(RgbaColor(40, 40, 40, 255))
                }
            }
            val progressLeaf = StyledComponent().apply {
                style.apply {
                    setPosition(CssPosition.ABSOLUTE)
                    setLeft(CssLength.px(0))
                    setTop(CssLength.percent(50f))
                    setWidthPx(200)
                    setHeightPx(4)
                    setTransform(CssTransform.parse("translate(0px, -50%)")!!)
                    setZIndex(3)
                    setPointerEvents(CssPointerEvents.NONE)
                    setCursor(CssCursor.NONE)
                    setBackgroundColor(RgbaColor(240, 30, 30, 255))
                }
            }
            val knobBtn = StyledButton(text = "").apply {
                onClick = {}
                style.apply {
                    setPosition(CssPosition.ABSOLUTE)
                    setLeft(CssLength.percent(50f))
                    setTop(CssLength.percent(50f))
                    setWidthPx(14)
                    setHeightPx(14)
                    setBorderRadius(CssCornerRadii.percent(50))
                    setTransform(CssTransform.parse("translate(-50%, -50%)")!!)
                    setZIndex(4)
                    setBackgroundColor(RgbaColor(255, 255, 255, 255))
                }
            }
            staticMiddle.addChild(knobBtn)
            staticMiddle.addChild(progressLeaf)
            staticMiddle.addChild(trackBtn)
            relAncestor.addChild(staticMiddle)
            viewportRoot.addChild(relAncestor)
            return listOf(viewportRoot, trackBtn, progressLeaf, knobBtn)
        }

        // --- Part 1: GTK4 StyledComponent + StyledSlider + Proximal Layout + z-index + pointer-events: none ---
        GenericUI.initialize(gtkFactory)
        val gtkStyledComp = gtkFactory.createStyledComponent("styled-leaf", "div")
        assertTrue(gtkStyledComp is GtkStyledComponentNativeImpl)
        assertTrue(gtkStyledComp is DesktopStyledComponentNativeImpl<*>)

        val gtkSlider = StyledSlider(min = 0.0, max = 100.0, value = 25.0, step = 1.0)
        val gtkSliderHost = Window("GTK4 StyledSlider Host", Size(320, 48)).apply { addChild(gtkSlider) }
        try {
            gtkSliderHost.show(gtkFactory)
            assertTrue(gtkSlider.ensureNativeImpl() is GtkStyledContainerNativeImpl)
        } finally {
            gtkSliderHost.close()
        }

        val gtkScene = buildProximalScene()
        val gtkWin = Window("GTK4 Proximal Test", Size(800, 120)).apply {
            addChild(gtkScene[0] as Container)
        }
        try {
            gtkWin.show(gtkFactory)
            val gtkWinImpl = gtkWin.nativeImpl as GtkWindowNativeImpl
            val gtkShot = GtkNativeWindowHost.captureWindowScreenshot(gtkWinImpl, 800, 120)
            assertNotNull(gtkShot)
            val trW = ((gtkScene[1] as StyledButton).ensureNativeImpl() as DesktopBaseNativeImpl<*>).nativeWidget
            val prW = ((gtkScene[2] as StyledComponent).ensureNativeImpl() as DesktopBaseNativeImpl<*>).nativeWidget
            val knW = ((gtkScene[3] as StyledButton).ensureNativeImpl() as DesktopBaseNativeImpl<*>).nativeWidget
            val trRoot = windowRectOf(trW, gtkWinImpl.nativeWidget)
            val knRoot = windowRectOf(knW, gtkWinImpl.nativeWidget)
            assertEquals(100, trRoot.x)
            assertEquals(500, trRoot.right)
            assertEquals(4, trRoot.height)
            assertEquals(48, trRoot.y)
            assertTrue(prW.isSelfPointerEventsNone())
            assertEquals("none", prW.attributes["cursor"])
            assertEquals(293, knRoot.x)
            assertEquals(43, knRoot.y)
            val orderedNonPassthrough = listOf(knW, prW, trW)
                .filter { !it.isSelfPointerEventsNone() && windowRectOf(it, gtkWinImpl.nativeWidget).contains(150, 50) }
                .sortedBy { it.effectiveZIndex() }
            assertEquals(trW, orderedNonPassthrough.lastOrNull())
        } finally {
            gtkWin.close()
        }

        // --- Part 2: KDE StyledComponent + StyledSlider + Proximal Layout + z-index + pointer-events: none ---
        GenericUI.initialize(kdeFactory)
        val kdeStyledComp = kdeFactory.createStyledComponent("styled-leaf", "div")
        assertTrue(kdeStyledComp is KdeStyledComponentNativeImpl)
        assertTrue(kdeStyledComp is DesktopStyledComponentNativeImpl<*>)

        val kdeSlider = StyledSlider(min = 0.0, max = 100.0, value = 25.0, step = 1.0)
        val kdeSliderHost = Window("KDE StyledSlider Host", Size(320, 48)).apply { addChild(kdeSlider) }
        try {
            kdeSliderHost.show(kdeFactory)
            assertTrue(kdeSlider.ensureNativeImpl() is KdeStyledContainerNativeImpl)
        } finally {
            kdeSliderHost.close()
        }

        val kdeScene = buildProximalScene()
        val kdeWin = Window("KDE Proximal Test", Size(800, 120)).apply {
            addChild(kdeScene[0] as Container)
        }
        try {
            kdeWin.show(kdeFactory)
            val kdeWinImpl = kdeWin.nativeImpl as KdeWindowNativeImpl
            val shot = KdeNativeWindowHost.captureWindowScreenshot(kdeWinImpl, 800, 120)
            assertNotNull(shot)
            val trW = ((kdeScene[1] as StyledButton).ensureNativeImpl() as DesktopBaseNativeImpl<*>).nativeWidget
            val prW = ((kdeScene[2] as StyledComponent).ensureNativeImpl() as DesktopBaseNativeImpl<*>).nativeWidget
            val knW = ((kdeScene[3] as StyledButton).ensureNativeImpl() as DesktopBaseNativeImpl<*>).nativeWidget
            val trRoot = windowRectOf(trW, kdeWinImpl.nativeWidget)
            val knRoot = windowRectOf(knW, kdeWinImpl.nativeWidget)
            assertEquals(100, trRoot.x)
            assertEquals(500, trRoot.right)
            assertEquals(4, trRoot.height)
            assertEquals(48, trRoot.y)
            assertTrue(prW.isSelfPointerEventsNone())
            assertEquals("none", prW.attributes["cursor"])
            assertEquals(293, knRoot.x)
            assertEquals(43, knRoot.y)
            val orderedNonPassthrough = listOf(knW, prW, trW)
                .filter { !it.isSelfPointerEventsNone() && windowRectOf(it, kdeWinImpl.nativeWidget).contains(150, 50) }
                .sortedBy { it.effectiveZIndex() }
            assertEquals(trW, orderedNonPassthrough.lastOrNull())
        } finally {
            kdeWin.close()
        }

        // Wide RangeSlider (> 512 / 1024 px) retains native OS rendering & click-to-seek without SeekBar fast paths
        val gtkWideSlider = gtkFactory.createRangeSlider() as GtkRangeSliderNativeImpl
        gtkWideSlider.setRange(0.0, 100.0, 1.0)
        gtkWideSlider.setValue(0.0)
        gtkWideSlider.setBounds(Rect(0, 0, 1600, 32))
        assertEquals("GtkScale", gtkWideSlider.nativeWidget.activeGtkClassName)
        assertEquals(1600, gtkWideSlider.nativeWidget.bounds.width)
        assertNull(gtkWideSlider.nativeWidget.attributes["seekBarLastRenderedMode"])
        gtkWideSlider.setValue(75.0)
        assertTrue(gtkWideSlider.getValue() > 60.0)

        val kdeWideSlider = kdeFactory.createRangeSlider() as KdeRangeSliderNativeImpl
        kdeWideSlider.setRange(0.0, 100.0, 1.0)
        kdeWideSlider.setValue(0.0)
        kdeWideSlider.setBounds(Rect(0, 0, 1600, 32))
        assertEquals("QSlider", kdeWideSlider.nativeWidget.activeKdeWidgetType)
        assertEquals(1600, kdeWideSlider.nativeWidget.bounds.width)
        assertNull(kdeWideSlider.nativeWidget.attributes["seekBarLastRenderedMode"])
        kdeWideSlider.setValue(75.0)
        assertTrue(kdeWideSlider.getValue() > 60.0)
    }

    @Test
    fun testKdeBackdropFilterHonorsCurrentOpacityWithoutSnap() {
        val w = 80
        val h = 40
        fun makeSplitBuffer(): IntArray = IntArray(w * h) { idx ->
            val x = idx % w
            if (x < w / 2) 0xFFE01010.toInt() else 0xFF1010E0.toInt()
        }
        val filter = CssFilter(blur = CssLength.px(12f))

        val zeroBuf = makeSplitBuffer()
        val expectedZero = makeSplitBuffer()
        LinuxBackdropFilterController.shared.applyToPremulBgraBuffer(
            filter = filter,
            pixels = zeroBuf,
            strideWords = w,
            bufferWidth = w,
            bufferHeight = h,
            regionX = 4,
            regionY = 4,
            regionWidth = w - 8,
            regionHeight = h - 8,
            cornerRadiusPx = 0f,
            componentOpacity = 0f
        )
        assertTrue(zeroBuf.contentEquals(expectedZero), "KDE backdrop blur at opacity=0 must be a no-op")

        val halfBuf = makeSplitBuffer()
        LinuxBackdropFilterController.shared.applyToPremulBgraBuffer(
            filter = filter,
            pixels = halfBuf,
            strideWords = w,
            bufferWidth = w,
            bufferHeight = h,
            regionX = 4,
            regionY = 4,
            regionWidth = w - 8,
            regionHeight = h - 8,
            cornerRadiusPx = 0f,
            componentOpacity = 0.5f
        )

        val fullBuf = makeSplitBuffer()
        LinuxBackdropFilterController.shared.applyToPremulBgraBuffer(
            filter = filter,
            pixels = fullBuf,
            strideWords = w,
            bufferWidth = w,
            bufferHeight = h,
            regionX = 4,
            regionY = 4,
            regionWidth = w - 8,
            regionHeight = h - 8,
            cornerRadiusPx = 0f,
            componentOpacity = 1f
        )

        val seamIdx = (h / 2) * w + (w / 2 - 2)
        assertNotEquals(expectedZero[seamIdx], fullBuf[seamIdx])
        assertNotEquals(expectedZero[seamIdx], halfBuf[seamIdx])
        assertNotEquals(fullBuf[seamIdx], halfBuf[seamIdx])
    }
}
