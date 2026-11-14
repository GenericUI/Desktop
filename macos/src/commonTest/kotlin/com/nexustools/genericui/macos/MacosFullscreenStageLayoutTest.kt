@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.component.fragment.Fragment
import com.nexustools.genericui.component.layout.Container
import com.nexustools.genericui.component.layout.LayeredContainer
import com.nexustools.genericui.component.shell.Window
import com.nexustools.genericui.desktop.core.DesktopInteractiveSurfaceSpec
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.core.DesktopStageOverlaySpec
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.macos.render.MacosRenderContext
import com.nexustools.genericui.macos.render.MacosThemePalette
import com.nexustools.genericui.macos.render.MacosWorkspaceLayoutRenderUtil
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssLength
import com.nexustools.genericui.style.theme.ThemeConfig
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.kCGBitmapByteOrderDefault
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MacosFullscreenStageLayoutTest {

    @Test
    fun fullscreenFragmentStageFillsEntireViewportInsteadOfHalfWindow() {
        val factory = MacosNativeFactory()
        val window = Window(title = "Receiver Test", initialSize = Size(1280, 720))

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

        val fsW = 1920
        val fsH = 1200
        val expectedViewport = Rect(0, 0, fsW, fsH)
        val pixels = ByteArray(64 * 64 * 4)
        val colorSpace = CGColorSpaceCreateDeviceRGB()
        assertNotNull(colorSpace)
        try {
            pixels.usePinned { pinned ->
                val cgCtx = CGBitmapContextCreate(
                    data = pinned.addressOf(0),
                    width = 64.convert(),
                    height = 64.convert(),
                    bitsPerComponent = 8.convert(),
                    bytesPerRow = (64 * 4).convert(),
                    space = colorSpace,
                    bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrderDefault
                )
                assertNotNull(cgCtx)
                try {
                    val renderCtx = MacosRenderContext(
                        cgContext = cgCtx,
                        windowKey = winImpl.nativeWidget.nativePointer,
                        viewport = expectedViewport,
                        backingScale = 2.0,
                        darkMode = true,
                        themeConfig = ThemeConfig.DEFAULT,
                        palette = MacosThemePalette.resolve(darkMode = true)
                    )
                    val fsElem = winImpl.getFullscreenElement()?.nativeWidget as? DesktopNativeWidget
                    assertNotNull(fsElem, "Fullscreen element should be attached on Window")

                    MacosWorkspaceLayoutRenderUtil.renderMainWindow(renderCtx, winImpl, fsElem)

                    assertEquals(expectedViewport, renderCtx.renderedStageBounds)
                    assertEquals(expectedViewport, fragmentNative.bounds)
                    assertEquals(expectedViewport, stageNative.bounds)
                    assertEquals(expectedViewport, surfaceNative.bounds)
                    assertEquals(expectedViewport, overlayNative.bounds)
                } finally {
                    CGContextRelease(cgCtx)
                }
            }
        } finally {
            CGColorSpaceRelease(colorSpace)
            window.dispose()
        }
    }
}
