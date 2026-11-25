@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.animation.AnimationSpec
import com.nexustools.genericui.component.form.RangeSlider
import com.nexustools.genericui.component.form.StyledSlider
import com.nexustools.genericui.component.surface.StyledComponent
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopStyledComponentNativeImpl
import com.nexustools.genericui.enums.css.CssCursor
import com.nexustools.genericui.enums.css.CssPointerEvents
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.css.CssLength
import com.nexustools.genericui.style.transition.CssTimingFunction
import com.nexustools.genericui.windows.impl.WindowsContainerNativeImpl
import com.nexustools.genericui.windows.impl.WindowsDropdownNativeImpl
import com.nexustools.genericui.windows.impl.WindowsRangeSliderNativeImpl
import com.nexustools.genericui.windows.impl.WindowsStyledButtonNativeImpl
import com.nexustools.genericui.windows.impl.WindowsStyledComponentNativeImpl
import com.nexustools.genericui.windows.impl.WindowsStyledContainerNativeImpl
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.windows.GetClassNameW
import platform.windows.GetWindowRect
import platform.windows.HTCLIENT
import platform.windows.HTTOP
import platform.windows.HTTOPRIGHT
import platform.windows.HTTRANSPARENT
import platform.windows.HWND
import platform.windows.RECT
import platform.windows.SendMessageW
import platform.windows.WM_NCHITTEST
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsAnimationAndSeekBarTest {

    private fun readWin32ClassName(hwnd: HWND): String = memScoped {
        val buf = allocArray<UShortVar>(128)
        GetClassNameW(hwnd, buf, 128)
        buf.toKString()
    }

    @Test
    fun testWindowsNativeFactoryProvidesWindowsAnimationSchedulerAndIdleVBlankLifecycle() {
        val factory = WindowsNativeFactory()
        GenericUI.initialize(factory)
        val scheduler = factory.createAnimationScheduler()
        assertTrue(scheduler is WindowsAnimationNativeImpl)
        try {
            val hz = scheduler.getRefreshRateHz()
            assertTrue(hz in 10.0..500.0, "Expected realistic refresh rate, got $hz")

            var watcherTicks = 0
            val watchId = scheduler.watchVBlank { _, _, _ -> watcherTicks++ }
            assertEquals(1, scheduler.activeVBlankConsumerCount)
            scheduler.stepFrame(16.0)
            assertTrue(watcherTicks > 0, "Expected watchVBlank listener to tick")

            var completed = false
            var lastProgress = -1f
            val animHandle = scheduler.startAnimation(
                spec = AnimationSpec(durationMs = 120, timingFunction = CssTimingFunction.EASE),
                target = null,
                onUpdate = { lastProgress = it },
                onComplete = { cancelled -> if (!cancelled) completed = true }
            )
            assertTrue(scheduler.activeVBlankConsumerCount >= 1)
            scheduler.advanceTimeBy(140.0)
            assertTrue(completed, "Expected timed animation to complete")
            assertEquals(1f, lastProgress)

            scheduler.unwatchVBlank(watchId)
            assertFalse(animHandle.isRunning)
            assertEquals(0, scheduler.activeVBlankConsumerCount)
        } finally {
            scheduler.dispose()
            assertTrue(scheduler.isDisposed)
        }
    }

    @Test
    fun testUndecoratedNcHitTestShrinksTopRightResizeBorderOverWindowControls() {
        assertEquals(
            HTTOP,
            Win32NativeWindowHost.hitTestUndecoratedRect(
                left = 0, top = 0, right = 800, bottom = 600,
                screenX = 400, screenY = 4,
                resizable = true, isFullscreenOrMaximized = false
            )
        )
        assertEquals(
            HTTOPRIGHT,
            Win32NativeWindowHost.hitTestUndecoratedRect(
                left = 0, top = 0, right = 800, bottom = 600,
                screenX = 796, screenY = 4,
                resizable = true, isFullscreenOrMaximized = false
            )
        )

        assertEquals(
            HTCLIENT,
            Win32NativeWindowHost.hitTestUndecoratedRect(
                left = 0, top = 0, right = 800, bottom = 600,
                screenX = 780, screenY = 4,
                resizable = true, isFullscreenOrMaximized = false,
                borderPx = 6, topBorderPx = 2, rightBorderPx = 2
            )
        )
        assertEquals(
            HTTOPRIGHT,
            Win32NativeWindowHost.hitTestUndecoratedRect(
                left = 0, top = 0, right = 800, bottom = 600,
                screenX = 799, screenY = 1,
                resizable = true, isFullscreenOrMaximized = false,
                borderPx = 6, topBorderPx = 2, rightBorderPx = 2
            )
        )
    }

    @Test
    fun testWin32StyledSliderStyledComponentZIndexPointerEventsWhileRangeSliderUsesTrackBar() = memScoped {
        val factory = WindowsNativeFactory()
        GenericUI.initialize(factory)

        // 1. Factory creates retained WindowsStyledComponentNativeImpl
        val styledCompImpl = factory.createStyledComponent("seek-bar-preload", "div")
        assertTrue(styledCompImpl is WindowsStyledComponentNativeImpl)
        assertTrue(styledCompImpl is DesktopStyledComponentNativeImpl<*>)

        // 2. StyledSlider composes retained StyledContainer + StyledComponent + StyledButton children
        val sliderComp = StyledSlider(min = 0.0, max = 100.0, value = 40.0, step = 1.0)
        val sliderRootImpl = sliderComp.ensureNativeImpl()
        assertTrue(sliderRootImpl is WindowsStyledContainerNativeImpl)
        val sliderWidget = (sliderRootImpl as DesktopBaseNativeImpl<*>).nativeWidget
        assertTrue(sliderWidget.children.mapNotNull { it.attributes["nativeImpl"] }.any { it is WindowsStyledComponentNativeImpl })

        val hostContainer = WindowsContainerNativeImpl()
        val trackImpl = WindowsStyledButtonNativeImpl()
        val progressLeaf = StyledComponent().apply {
            style.apply {
                setWidthPx(200)
                setHeightPx(4)
                setZIndex(3)
                setPointerEvents(CssPointerEvents.NONE)
                setCursor(CssCursor.NONE)
                setBackgroundColor(RgbaColor(240, 30, 30, 255))
            }
        }
        val progressImpl = progressLeaf.ensureNativeImpl() as WindowsStyledComponentNativeImpl
        val knobImpl = WindowsStyledButtonNativeImpl()
        val nativeRangeSlider = RangeSlider(minValue = 50.0, maxValue = 100.0, min = 0.0, max = 100.0).ensureNativeImpl() as WindowsRangeSliderNativeImpl

        try {
            trackImpl.setAttribute("zIndex", "1")
            knobImpl.setAttribute("zIndex", "4")

            hostContainer.addChildNative(trackImpl)
            hostContainer.addChildNative(progressImpl)
            hostContainer.addChildNative(knobImpl)
            hostContainer.addChildNative(nativeRangeSlider)

            val hostHwnd = assertNotNull(hostContainer.nativeWidget.hwnd)
            val trackHwnd = assertNotNull(trackImpl.nativeWidget.hwnd)
            val progressHwnd = assertNotNull(progressImpl.nativeWidget.hwnd)
            val knobHwnd = assertNotNull(knobImpl.nativeWidget.hwnd)
            val rangeHwnd = assertNotNull(nativeRangeSlider.nativeWidget.hwnd)

            trackImpl.setBounds(Rect(40, 48, 400, 4))
            progressImpl.setBounds(Rect(40, 48, 200, 4))
            knobImpl.setBounds(Rect(233, 43, 14, 14))
            nativeRangeSlider.setBounds(Rect(10, 8, 400, 28))
            Win32ControlMessageRouter.layoutChildrenBatch(hostHwnd, listOf(trackImpl, progressImpl, knobImpl, nativeRangeSlider))

            // 3. Direct StyledComponent keeps exact 4px CSS height, cursor: none, and HTTRANSPARENT WM_NCHITTEST
            val pr = alloc<RECT>()
            assertTrue(GetWindowRect(progressHwnd, pr.ptr) != 0)
            assertEquals(4, pr.bottom - pr.top, "StyledComponent HWND must keep exact 4px CSS height")
            assertEquals("none", progressImpl.nativeWidget.attributes["cursor"])
            assertTrue(progressImpl.nativeWidget.isSelfPointerEventsNone())
            assertEquals(HTTRANSPARENT.toLong(), SendMessageW(progressHwnd, WM_NCHITTEST.toUInt(), 0uL, 0L))

            // 4. ChildWindowFromPointEx skips pointer-events: none zIndex=3 overlay -> hits zIndex=1 track, while zIndex=4 knob wins
            val hitTrack = Win32ControlMessageRouter.findDeepestHitChildHwnd(hostHwnd, 120, 50)
            assertEquals(trackHwnd, hitTrack, "pointer-events: none overlay HWND must pass hit-testing to underlying zIndex=1 track HWND")

            val hitKnob = Win32ControlMessageRouter.findDeepestHitChildHwnd(hostHwnd, 238, 50)
            assertEquals(knobHwnd, hitKnob, "zIndex=4 knob HWND must win hit-testing over zIndex=1 track HWND")

            // 5. Base GenericUI RangeSlider stays on genuine ComCtl32 TrackBar HWND
            assertEquals("msctls_trackbar32", readWin32ClassName(rangeHwnd))
            assertNull(nativeRangeSlider.nativeWidget.attributes["seekBarLastRenderedMode"])
        } finally {
            nativeRangeSlider.dispose()
            knobImpl.dispose()
            progressImpl.dispose()
            trackImpl.dispose()
            hostContainer.dispose()
        }
    }

    @Test
    fun testWin32DropdownRetainsItemsAndDispatchesPopupSelection() {
        val dropdown = WindowsDropdownNativeImpl()
        try {
            val items = listOf("a" to "Audio Track", "s" to "Subtitles")
            dropdown.setItems(items)
            assertEquals(items, dropdown.getDropdownItems())

            var selected: String? = null
            dropdown.setOnItemSelected { selected = it }
            dropdown.notifyDropdownItemSelected("s")
            assertEquals("s", selected)
        } finally {
            dropdown.dispose()
        }
    }

    @Test
    fun testDirect2dBackdropBlurSkipsZeroOpacityAndBlendsHalfOpacity() {
        val w = 80
        val h = 40
        fun makeSplitBuffer(): IntArray = IntArray(w * h) { idx ->
            val x = idx % w
            if (x < w / 2) 0xFFE01010.toInt() else 0xFF1010E0.toInt()
        }
        val filter = CssFilter(blur = CssLength.px(12f))

        val zeroBuf = makeSplitBuffer()
        val expectedZero = makeSplitBuffer()
        assertTrue(
            Win32Direct2dBackdropBlurEngine.applyBackdropToArgbArray(
                filter = filter,
                argbPixels = zeroBuf,
                bufferWidth = w,
                bufferHeight = h,
                regionX = 4,
                regionY = 4,
                regionWidth = w - 8,
                regionHeight = h - 8,
                cornerRadiusPx = 0f,
                opacity = 0f
            )
        )
        assertTrue(zeroBuf.contentEquals(expectedZero), "Opacity 0 backdrop blur must leave buffer untouched")

        val halfBuf = makeSplitBuffer()
        val fullBuf = makeSplitBuffer()
        assertTrue(
            Win32Direct2dBackdropBlurEngine.applyBackdropToArgbArray(
                filter = filter,
                argbPixels = halfBuf,
                bufferWidth = w,
                bufferHeight = h,
                regionX = 4,
                regionY = 4,
                regionWidth = w - 8,
                regionHeight = h - 8,
                cornerRadiusPx = 0f,
                opacity = 0.5f
            )
        )
        assertTrue(
            Win32Direct2dBackdropBlurEngine.applyBackdropToArgbArray(
                filter = filter,
                argbPixels = fullBuf,
                bufferWidth = w,
                bufferHeight = h,
                regionX = 4,
                regionY = 4,
                regionWidth = w - 8,
                regionHeight = h - 8,
                cornerRadiusPx = 0f,
                opacity = 1.0f
            )
        )
        val seamIdx = (h / 2) * w + (w / 2 - 2)
        assertNotEquals(expectedZero[seamIdx], fullBuf[seamIdx], "Expected full-opacity blur to blend across split edge")
        assertNotEquals(expectedZero[seamIdx], halfBuf[seamIdx], "Expected half-opacity blur to partially blend")
        assertNotEquals(fullBuf[seamIdx], halfBuf[seamIdx], "Expected 0.5 opacity blur to differ from 1.0 opacity blur")
    }
}
