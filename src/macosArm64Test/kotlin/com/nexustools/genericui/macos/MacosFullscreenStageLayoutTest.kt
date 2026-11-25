@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.component.fragment.Fragment
import com.nexustools.genericui.component.layout.Container
import com.nexustools.genericui.component.layout.LayeredContainer
import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.component.shell.Window
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.desktop.core.DesktopInteractiveSurfaceSpec
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.core.DesktopStageOverlaySpec
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssLength
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.useContents
import platform.AppKit.NSView
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MacosFullscreenStageLayoutTest {

    private fun nsViewFrameAsRect(view: NSView): Rect = view.frame.useContents {
        Rect(
            x = origin.x.roundToInt(),
            y = origin.y.roundToInt(),
            width = size.width.roundToInt(),
            height = size.height.roundToInt()
        )
    }

    @Test
    fun fullscreenFragmentStageFillsEntireViewportInsteadOfHalfWindow() {
        val prevFactory = GenericUI.activeFactory
        val prevApp = Application.current
        val prevInteractive = MacosApplicationNativeImpl.interactiveMacosGuiEnabled
        val prevVisual = MacosNativeWindowHost.visualTestMode

        Application.current = null
        MacosApplicationNativeImpl.interactiveMacosGuiEnabled = false
        MacosNativeWindowHost.visualTestMode = true

        val factory = MacosNativeFactory()
        GenericUI.initialize(factory)
        val window = Window(title = "Receiver Test", initialSize = Size(1280, 720))

        try {
            val surfaceBox = Container().apply {
                addClass("w-100")
                addClass("h-100")
                getStyle().apply {
                    setWidth(CssLength.PERCENT_100)
                    setHeight(CssLength.PERCENT_100)
                    setBackgroundColor(RgbaColor.BLACK)
                }
            }
            val overlayBox = Container().apply {
                addClass("gui-overlay")
                setAttribute("data-overlay-root", "true")
                addClass("w-100")
                addClass("h-100")
                getStyle().apply {
                    setWidth(CssLength.PERCENT_100)
                    setHeight(CssLength.PERCENT_100)
                }
            }
            val stageContainer = LayeredContainer().apply {
                addClass("genericui-video-overlay-host")
                setAttribute("data-overlay-host", "true")
                setAttribute("borderRadiusPx", "0")
                addClass("w-100")
                addClass("h-100")
                getStyle().apply {
                    setWidth(CssLength.PERCENT_100)
                    setHeight(CssLength.PERCENT_100)
                    setBackgroundColor(RgbaColor.BLACK)
                }
                addFillLayer(surfaceBox, zIndex = 0)
                addFillLayer(overlayBox, zIndex = 10)
            }
            val fragment = Fragment().apply {
                addClass("w-100")
                addClass("h-100")
                getStyle().apply {
                    setWidth(CssLength.PERCENT_100)
                    setHeight(CssLength.PERCENT_100)
                    setBackgroundColor(RgbaColor.BLACK)
                }
                addChild(stageContainer)
            }

            window.setFragment(fragment)
            window.setFullscreen(true)
            window.show(factory)

            val winImpl = window.nativeImpl as MacosWindowNativeImpl
            val fragmentContainer = assertNotNull(window.fragmentContainer)
            val fragmentContainerNative = fragmentContainer.ensureNativeImpl()?.nativeWidget as DesktopNativeWidget
            val surfaceNative = surfaceBox.ensureNativeImpl()?.nativeWidget as DesktopNativeWidget
            val overlayNative = overlayBox.ensureNativeImpl()?.nativeWidget as DesktopNativeWidget
            val stageNative = stageContainer.ensureNativeImpl()?.nativeWidget as DesktopNativeWidget
            val fragmentNative = fragment.ensureNativeImpl()?.nativeWidget as DesktopNativeWidget

            DesktopInteractiveSurfaceSpec(
                surfaceWidth = { 1920 },
                surfaceHeight = { 1080 },
                swapCount = { 1L },
                isActiveStream = { true }
            ).attachTo(surfaceNative)

            DesktopStageOverlaySpec(
                isTargetActive = { true },
                isOverlayVisible = { true }
            ).attachTo(overlayNative)

            val fsElem = winImpl.getFullscreenElement()?.nativeWidget as? DesktopNativeWidget
            assertNotNull(fsElem, "Fullscreen element should be attached on Window")
            assertSame(fragmentContainerNative, fsElem)

            val fsW = 1920
            val fsH = 1200
            val expectedViewport = Rect(0, 0, fsW, fsH)
            val screenshot = MacosNativeWindowHost.captureWindowScreenshot(winImpl, fsW, fsH)
            assertNotNull(screenshot, "Retained AppKit fullscreen capture must succeed")
            assertEquals(fsW, screenshot.width)
            assertEquals(fsH, screenshot.height)

            assertEquals(expectedViewport, fragmentContainerNative.bounds)
            assertEquals(expectedViewport, fragmentNative.bounds)
            assertEquals(expectedViewport, stageNative.bounds)
            assertEquals(expectedViewport, surfaceNative.bounds)
            assertEquals(expectedViewport, overlayNative.bounds)

            val fragmentContainerView = assertNotNull((fragmentContainerNative as MacosViewHandle).nsView)
            val fragmentView = assertNotNull((fragmentNative as MacosViewHandle).nsView)
            val stageView = assertNotNull((stageNative as MacosViewHandle).nsView)
            val surfaceView = assertNotNull((surfaceNative as MacosViewHandle).nsView)
            val overlayView = assertNotNull((overlayNative as MacosViewHandle).nsView)

            assertEquals(fragmentContainerView, fragmentView.superview)
            assertEquals(fragmentView, stageView.superview)
            assertEquals(stageView, surfaceView.superview)
            assertEquals(stageView, overlayView.superview)
            assertEquals(expectedViewport, nsViewFrameAsRect(fragmentView))
            assertEquals(expectedViewport, nsViewFrameAsRect(stageView))
            assertEquals(expectedViewport, nsViewFrameAsRect(surfaceView))
            assertEquals(expectedViewport, nsViewFrameAsRect(overlayView))
            assertTrue(
                surfaceView.subviews.any { it is MacosInteractiveSurfaceView },
                "Interactive surface stage must mount retained MacosInteractiveSurfaceView"
            )
        } finally {
            window.dispose()
            MacosNativeWindowHost.closeAllVisualWindows()
            GenericUiThreading.flushAllQueues()
            MacosNativeWindowHost.visualTestMode = prevVisual
            MacosApplicationNativeImpl.interactiveMacosGuiEnabled = prevInteractive
            Application.current = prevApp
            GenericUI.activeFactory = prevFactory
        }
    }
}
