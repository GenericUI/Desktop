@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.component.action.Button
import com.nexustools.genericui.component.action.DropdownButton
import com.nexustools.genericui.component.action.IconButton
import com.nexustools.genericui.component.action.SplitDropdownButton
import com.nexustools.genericui.component.action.ToggleButton
import com.nexustools.genericui.component.content.Table
import com.nexustools.genericui.component.content.TableCell
import com.nexustools.genericui.component.content.TableColumn
import com.nexustools.genericui.component.content.TableRow
import com.nexustools.genericui.component.feedback.ProgressBar
import com.nexustools.genericui.component.feedback.Spinner
import com.nexustools.genericui.component.form.CheckBox
import com.nexustools.genericui.component.form.ComboBox
import com.nexustools.genericui.component.form.NumberField
import com.nexustools.genericui.component.form.RadioButton
import com.nexustools.genericui.component.form.RangeSlider
import com.nexustools.genericui.component.form.Select
import com.nexustools.genericui.component.form.SelectOption
import com.nexustools.genericui.component.form.Slider
import com.nexustools.genericui.component.form.Switch
import com.nexustools.genericui.component.form.TextArea
import com.nexustools.genericui.component.form.TextField
import com.nexustools.genericui.component.layout.Container
import com.nexustools.genericui.component.layout.ScrollView
import com.nexustools.genericui.component.lazy.LazyList
import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.component.shell.MdiContainer
import com.nexustools.genericui.component.shell.MdiWindow
import com.nexustools.genericui.component.shell.Window
import com.nexustools.genericui.component.surface.CollapsiblePanel
import com.nexustools.genericui.component.tabs.Tab
import com.nexustools.genericui.component.tabs.TabContainer
import com.nexustools.genericui.component.typography.Label
import com.nexustools.genericui.core.AbstractComponent
import com.nexustools.genericui.core.AbstractContainer
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.desktop.widget.DesktopScrollViewNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.image.PixelFormat
import com.nexustools.genericui.macos.impl.MacosSplitDropdownView
import com.nexustools.genericui.macos.impl.MacosSwitchLabeledView
import com.nexustools.genericui.style.css.CssLength
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.AppKit.NSAppearance
import platform.AppKit.NSAppearanceNameAqua
import platform.AppKit.NSAppearanceNameDarkAqua
import platform.AppKit.NSApplication
import platform.AppKit.NSBackingStoreBuffered
import platform.AppKit.NSButton
import platform.AppKit.NSComboBox
import platform.AppKit.NSGraphicsContext
import platform.AppKit.NSPopUpButton
import platform.AppKit.NSProgressIndicator
import platform.AppKit.NSProgressIndicatorStyleBar
import platform.AppKit.NSProgressIndicatorStyleSpinning
import platform.AppKit.NSScrollView
import platform.AppKit.NSScroller
import platform.AppKit.NSScrollerStyleLegacy
import platform.AppKit.NSScrollerStyleOverlay
import platform.AppKit.NSSlider
import platform.AppKit.NSSwitch
import platform.AppKit.NSTableView
import platform.AppKit.NSTextField
import platform.AppKit.NSTextView
import platform.AppKit.NSView
import platform.AppKit.NSWindow
import platform.AppKit.NSWindowStyleMaskBorderless
import platform.AppKit.appearance
import platform.AppKit.setAppearance
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextGetData
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRef
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextRestoreGState
import platform.CoreGraphics.CGContextSaveGState
import platform.CoreGraphics.CGContextScaleCTM
import platform.CoreGraphics.CGContextTranslateCTM
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGBitmapByteOrder32Big
import platform.Foundation.NSMakePoint
import platform.Foundation.NSMakeRect
import platform.Foundation.NSThread
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_sync
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MacosNativeControlsAndButtonOnlyTintTest {

    private inline fun runOnMainThreadSync(crossinline block: () -> Unit) {
        if (NSThread.isMainThread) {
            block()
            return
        }
        var failure: Throwable? = null
        dispatch_sync(dispatch_get_main_queue()) {
            try {
                block()
            } catch (t: Throwable) {
                failure = t
            }
        }
        failure?.let { throw it }
    }

    private fun imageBufferToArgb(buffer: ImageBuffer): IntArray {
        val count = buffer.width * buffer.height
        val out = IntArray(count)
        val bytes = buffer.bytes
        val bpp = when (buffer.pixelFormat) {
            PixelFormat.RGBA_8888, PixelFormat.BGRA_8888 -> 4
            else -> 4
        }
        for (i in 0 until count) {
            val base = i * bpp
            val r = bytes[base].toInt() and 0xFF
            val g = bytes[base + 1].toInt() and 0xFF
            val b = bytes[base + 2].toInt() and 0xFF
            val a = bytes[base + 3].toInt() and 0xFF
            out[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    private fun captureNsViewArgb(
        view: NSView,
        width: Int,
        height: Int,
        darkMode: Boolean,
        afterLayout: (() -> Unit)? = null
    ): IntArray {
        val app = NSApplication.sharedApplication
        val prevAppearance = app.appearance()
        app.setAppearance(
            NSAppearance.appearanceNamed(if (darkMode) NSAppearanceNameDarkAqua else NSAppearanceNameAqua)
        )
        val win = NSWindow(
            contentRect = NSMakeRect(0.0, 0.0, width.toDouble(), height.toDouble()),
            styleMask = NSWindowStyleMaskBorderless,
            backing = NSBackingStoreBuffered,
            defer = false
        )
        win.setReleasedWhenClosed(false)
        val hostView = NSView(NSMakeRect(0.0, 0.0, width.toDouble(), height.toDouble()))
        view.setFrame(NSMakeRect(0.0, 0.0, width.toDouble(), height.toDouble()))
        hostView.addSubview(view)
        win.setContentView(hostView)
        win.appearance = NSAppearance.appearanceNamed(if (darkMode) NSAppearanceNameDarkAqua else NSAppearanceNameAqua)
        hostView.layoutSubtreeIfNeeded()
        afterLayout?.invoke()
        if (view is NSScroller) {
            view.checkSpaceForParts()
            view.setNeedsDisplay(true)
        }
        if (view is NSScrollView) {
            view.tile()
            view.verticalScroller?.let { vs ->
                vs.checkSpaceForParts()
                vs.setNeedsDisplay(true)
            }
        }
        hostView.layoutSubtreeIfNeeded()
        return try {
            val rect = NSMakeRect(0.0, 0.0, width.toDouble(), height.toDouble())
            val rep = hostView.bitmapImageRepForCachingDisplayInRect(rect)
                ?: error("bitmapImageRepForCachingDisplayInRect returned null")
            hostView.cacheDisplayInRect(rect, toBitmapImageRep = rep)
            val cg = rep.CGImage ?: error("CGImage null for NSBitmapImageRep")
            val cs = CGColorSpaceCreateDeviceRGB() ?: error("CGColorSpaceCreateDeviceRGB returned null")
            val ctx = CGBitmapContextCreate(
                data = null,
                width = width.convert(),
                height = height.convert(),
                bitsPerComponent = 8.convert(),
                bytesPerRow = (width * 4).convert(),
                space = cs,
                bitmapInfo = kCGBitmapByteOrder32Big or CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value
            ) ?: error("CGBitmapContextCreate returned null")
            CGColorSpaceRelease(cs)
            try {
                CGContextDrawImage(ctx, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), cg)
                val rootH = hostView.bounds.useContents { size.height }
                val rootFlipped = hostView.isFlipped()
                fun compositeScrollers(v: NSView) {
                    if (v.isHidden() || v.alphaValue <= 0.004) return
                    val scroller = v as? NSScroller
                    if (scroller != null && scroller.isEnabled() && scroller.knobProportion > 0.0 && scroller.knobProportion < 1.0) {
                        val rInRoot = scroller.convertRect(scroller.bounds, toView = hostView)
                        val (rx, ry, rh) = rInRoot.useContents { Triple(origin.x, origin.y, size.height) }
                        val topDownY = if (rootFlipped) ry else (rootH - ry - rh)
                        CGContextSaveGState(ctx)
                        CGContextTranslateCTM(ctx, rx, height.toDouble() - topDownY)
                        CGContextScaleCTM(ctx, 1.0, -1.0)
                        NSGraphicsContext.saveGraphicsState()
                        NSGraphicsContext.setCurrentContext(
                            NSGraphicsContext.graphicsContextWithCGContext(ctx, flipped = scroller.isFlipped())
                        )
                        val parentSv = scroller.superview as? NSScrollView
                        scroller.effectiveAppearance.performAsCurrentDrawingAppearance {
                            if (scroller.scrollerStyle == NSScrollerStyleLegacy && parentSv?.drawsBackground != false) {
                                scroller.drawKnobSlotInRect(scroller.bounds, highlight = false)
                            }
                            scroller.drawKnob()
                        }
                        NSGraphicsContext.restoreGraphicsState()
                        CGContextRestoreGState(ctx)
                        return
                    }
                    for (sub in v.subviews.filterIsInstance<NSView>()) {
                        compositeScrollers(sub)
                    }
                }
                compositeScrollers(hostView)
                val ptr = CGBitmapContextGetData(ctx)?.reinterpret<ByteVar>() ?: error("CGBitmapContextGetData null")
                val rgba = ByteArray(width * height * 4)
                rgba.usePinned { pinned ->
                    platform.posix.memcpy(pinned.addressOf(0), ptr, rgba.size.toULong())
                }
                val out = IntArray(width * height)
                for (i in out.indices) {
                    val b = i shl 2
                    val a = rgba[b + 3].toInt() and 0xFF
                    if (a == 0) continue
                    val rr = rgba[b].toInt() and 0xFF
                    val gg = rgba[b + 1].toInt() and 0xFF
                    val bb = rgba[b + 2].toInt() and 0xFF
                    val r = if (a < 255) ((rr * 255) / a).coerceIn(0, 255) else rr
                    val g = if (a < 255) ((gg * 255) / a).coerceIn(0, 255) else gg
                    val bl = if (a < 255) ((bb * 255) / a).coerceIn(0, 255) else bb
                    out[i] = (a shl 24) or (r shl 16) or (g shl 8) or bl
                }
                out
            } finally {
                CGContextRelease(ctx)
            }
        } finally {
            view.removeFromSuperview()
            win.close()
            app.setAppearance(prevAppearance)
        }
    }

    private fun countOpaque(
        pixels: IntArray,
        width: Int,
        xRange: IntRange = 0 until width,
        yRange: IntRange = 0 until (pixels.size / width)
    ): Int {
        var count = 0
        for (y in yRange) {
            val row = y * width
            for (x in xRange) {
                if (((pixels[row + x] ushr 24) and 0xFF) > 0) count++
            }
        }
        return count
    }

    private fun countDifferingPixels(
        a: IntArray,
        b: IntArray,
        width: Int,
        xRange: IntRange = 0 until width,
        yRange: IntRange = 0 until (a.size / width)
    ): Int {
        var diffs = 0
        for (y in yRange) {
            val row = y * width
            for (x in xRange) {
                if (a[row + x] != b[row + x]) diffs++
            }
        }
        return diffs
    }

    private fun verticalAlphaCentroid(
        pixels: IntArray,
        width: Int,
        xRange: IntRange,
        yRange: IntRange
    ): Double {
        var weightedY = 0.0
        var totalAlpha = 0.0
        for (y in yRange) {
            val row = y * width
            for (x in xRange) {
                val a = (pixels[row + x] ushr 24) and 0xFF
                if (a > 0) {
                    weightedY += y * a
                    totalAlpha += a
                }
            }
        }
        return if (totalAlpha > 0.0) weightedY / totalAlpha else -1.0
    }

    private fun verticalKnobDeviationCentroid(
        pixels: IntArray,
        width: Int,
        xRange: IntRange,
        yRange: IntRange,
        refY: Int = (yRange.first + yRange.last) / 2
    ): Double {
        var weightedY = 0.0
        var totalWeight = 0.0
        for (x in xRange) {
            val ref = pixels[refY * width + x]
            val refA = (ref ushr 24) and 0xFF
            val refR = (ref ushr 16) and 0xFF
            val refG = (ref ushr 8) and 0xFF
            val refB = ref and 0xFF
            for (y in yRange) {
                val p = pixels[y * width + x]
                val a = (p ushr 24) and 0xFF
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                val d = kotlin.math.abs(a - refA) + kotlin.math.abs(r - refR) +
                    kotlin.math.abs(g - refG) + kotlin.math.abs(b - refB)
                if (d > 12) {
                    weightedY += y * d.toDouble()
                    totalWeight += d.toDouble()
                }
            }
        }
        return if (totalWeight > 0.0) weightedY / totalWeight else -1.0
    }

    private fun countRedDominantPixels(pixels: IntArray): Int {
        var count = 0
        for (argb in pixels) {
            val a = (argb ushr 24) and 0xFF
            if (a < 32) continue
            val r = (argb ushr 16) and 0xFF
            val g = (argb ushr 8) and 0xFF
            val b = argb and 0xFF
            if (r - g >= 60 && r - b >= 60) count++
        }
        return count
    }

    private inline fun withMacosBackend(crossinline block: (MacosNativeFactory) -> Unit) {
        val prevFactory = GenericUI.activeFactory
        val prevApp = Application.current
        val prevInteractive = MacosApplicationNativeImpl.interactiveMacosGuiEnabled
        val prevVisual = MacosNativeWindowHost.visualTestMode
        val prevAppearance = NSApplication.sharedApplication.appearance()

        Application.current = null
        MacosApplicationNativeImpl.interactiveMacosGuiEnabled = false
        MacosNativeWindowHost.visualTestMode = true
        val factory = MacosNativeFactory()
        GenericUI.initialize(factory)
        try {
            runOnMainThreadSync {
                block(factory)
            }
        } finally {
            runOnMainThreadSync {
                MacosNativeWindowHost.closeAllVisualWindows()
                GenericUiThreading.flushAllQueues()
            }
            NSApplication.sharedApplication.setAppearance(prevAppearance)
            MacosNativeWindowHost.visualTestMode = prevVisual
            MacosApplicationNativeImpl.interactiveMacosGuiEnabled = prevInteractive
            Application.current = prevApp
            GenericUI.activeFactory = prevFactory
        }
    }

    private fun captureSingleComponentArgb(
        factory: MacosNativeFactory,
        comp: AbstractComponent,
        width: Int,
        height: Int,
        darkMode: Boolean = false
    ): Pair<IntArray, MacosViewHandle> {
        val app = NSApplication.sharedApplication
        val prevAppAppearance = app.appearance()
        app.setAppearance(
            NSAppearance.appearanceNamed(if (darkMode) NSAppearanceNameDarkAqua else NSAppearanceNameAqua)
        )
        MacosNativeWindowHost.syncThemeAppearance(if (darkMode) ThemeMode.DARK else ThemeMode.LIGHT)

        comp.getStyle().apply {
            setWidth(CssLength.px(width))
            setHeight(CssLength.px(height))
        }
        val window = Window(title = "Control Capture", initialSize = Size(width, height))
        window.addChild(comp)
        window.show(factory)
        return try {
            val winImpl = window.nativeImpl as MacosWindowNativeImpl
            winImpl.nativeWidget.attributes["darkTheme"] = darkMode
            val handle = comp.ensureNativeImpl()!!.nativeWidget as MacosViewHandle
            handle.attributes["darkTheme"] = darkMode
            val shot = MacosNativeWindowHost.captureWindowScreenshot(winImpl, width, height)
            assertNotNull(shot, "captureWindowScreenshot must succeed for $comp")
            imageBufferToArgb(shot) to handle
        } finally {
            window.removeChild(comp)
            window.close()
            window.dispose()
            app.setAppearance(prevAppAppearance)
        }
    }

    @Test
    fun testProgressBarUsesGenuineNsProgressIndicatorAndIgnoresBootstrapType() = withMacosBackend { factory ->
        val pNone = ProgressBar(progress = 0.25f, indeterminate = false).apply { type = BootstrapType.NONE }
        val pDanger = ProgressBar(progress = 0.25f, indeterminate = false).apply { type = BootstrapType.DANGER }
        val pHigh = ProgressBar(progress = 0.85f, indeterminate = false).apply { type = BootstrapType.PRIMARY }
        val pIndet = ProgressBar(progress = 0f, indeterminate = true).apply { type = BootstrapType.SUCCESS }

        val w = 200
        val h = 20
        val (noneLight, hNone) = captureSingleComponentArgb(factory, pNone, w, h, darkMode = false)
        val (dangerLight, hDanger) = captureSingleComponentArgb(factory, pDanger, w, h, darkMode = false)
        val (highLight, hHigh) = captureSingleComponentArgb(factory, pHigh, w, h, darkMode = false)
        val (indetLight, hIndet) = captureSingleComponentArgb(factory, pIndet, w, h, darkMode = false)
        val (noneDark, _) = captureSingleComponentArgb(factory, ProgressBar(progress = 0.25f, indeterminate = false), w, h, darkMode = true)

        for (hnd in listOf(hNone, hDanger, hHigh, hIndet)) {
            val indicator = hnd.nsView as? NSProgressIndicator
            assertNotNull(indicator, "ProgressBar must mount genuine NSProgressIndicator")
            assertEquals(NSProgressIndicatorStyleBar, indicator.style)
        }
        assertFalse((hNone.nsView as NSProgressIndicator).isIndeterminate())
        assertEquals(0.25, (hNone.nsView as NSProgressIndicator).doubleValue, 0.01)
        assertEquals(0.85, (hHigh.nsView as NSProgressIndicator).doubleValue, 0.01)
        assertTrue((hIndet.nsView as NSProgressIndicator).isIndeterminate())

        assertTrue(countOpaque(noneLight, w) > 0, "Expected non-empty NSProgressIndicator pixels")
        assertTrue(countOpaque(indetLight, w) > 0, "Expected non-empty indeterminate NSProgressIndicator pixels")
        assertEquals(
            0,
            countDifferingPixels(noneLight, dangerLight, w),
            "ProgressBar must ignore BootstrapType.DANGER and render identical native NSProgressIndicator pixels"
        )
        assertTrue(
            countDifferingPixels(noneLight, highLight, w, xRange = (w / 2) until w) > 0,
            "Determinate 0.85 NSProgressIndicator must differ from 0.25 in right half"
        )
        assertTrue(
            countDifferingPixels(noneLight, noneDark, w) > 0,
            "NSProgressIndicator must adapt between Aqua and Dark Aqua"
        )
    }

    @Test
    fun testGenuineNsScrollerInScrollViewLazyListTableAndMdiContainer() = withMacosBackend { factory ->
        val w = 360
        val h = 180

        fun captureScrollerStrip(style: platform.AppKit.NSScrollerStyle, scrollAtBottom: Boolean, darkMode: Boolean): IntArray {
            val svW = 120
            val sv = NSScrollView(frame = NSMakeRect(0.0, 0.0, svW.toDouble(), h.toDouble())).apply {
                setHasVerticalScroller(true)
                setAutohidesScrollers(false)
                setDrawsBackground(false)
                setScrollerStyle(style)
                val doc = com.nexustools.genericui.macos.impl.MacosScrollDocumentView(
                    frameRect = NSMakeRect(0.0, 0.0, svW.toDouble(), (h * 5).toDouble())
                )
                documentView = doc
            }
            val full = captureNsViewArgb(sv, svW, h, darkMode = darkMode) {
                sv.tile()
                val clip = sv.contentView()
                val docH = (h * 5).toDouble()
                val clipH = clip.bounds.useContents { size.height }.coerceAtLeast(h.toDouble())
                val targetY = if (scrollAtBottom) (docH - clipH).coerceAtLeast(0.0) else 0.0
                clip.scrollToPoint(NSMakePoint(0.0, targetY))
                sv.reflectScrolledClipView(clip)
                sv.verticalScroller?.let { vs ->
                    vs.setEnabled(true)
                    vs.checkSpaceForParts()
                    vs.setNeedsDisplay(true)
                }
            }
            val strip = IntArray(15 * h)
            val xStart = svW - 15
            for (y in 0 until h) {
                for (x in 0 until 15) {
                    strip[y * 15 + x] = full[y * svW + xStart + x]
                }
            }
            return strip
        }

        val legacyTop = captureScrollerStrip(NSScrollerStyleLegacy, scrollAtBottom = false, darkMode = false)
        val legacyBottom = captureScrollerStrip(NSScrollerStyleLegacy, scrollAtBottom = true, darkMode = false)
        assertTrue(countDifferingPixels(legacyTop, legacyBottom, 15) > 0, "NSScroller must visually shift knob")
        assertTrue(
            verticalAlphaCentroid(legacyBottom, 15, 0 until 15, 0 until h) >
                verticalAlphaCentroid(legacyTop, 15, 0 until 15, 0 until h) + 40.0,
            "NSScroller knob must move from top to bottom as doubleValue goes from 0.0 to 1.0"
        )

        val overlayTop = captureScrollerStrip(NSScrollerStyleOverlay, scrollAtBottom = false, darkMode = true)
        val overlayBottom = captureScrollerStrip(NSScrollerStyleOverlay, scrollAtBottom = true, darkMode = true)
        if (countOpaque(overlayTop, 15) > 0 && countOpaque(overlayBottom, 15) > 0) {
            assertTrue(
                verticalAlphaCentroid(overlayBottom, 15, 0 until 15, 0 until h) >
                    verticalAlphaCentroid(overlayTop, 15, 0 until 15, 0 until h) + 40.0,
                "Overlay NSScroller knob must also move from top to bottom cleanly"
            )
        }

        // Retained NSScrollView verification across ScrollView, LazyList, Table, and MdiWindow.contentScroll
        val scrollView = ScrollView().apply {
            type = BootstrapType.DANGER
            for (i in 0 until 24) {
                addChild(Label("ScrollView row $i"))
            }
        }
        val lazyList = LazyList(estimatedItemExtentPx = 44f).apply {
            type = BootstrapType.DANGER
            setItems(24, onLoadItem = { idx -> Label("Lazy item $idx") })
        }
        val table = Table().apply {
            type = BootstrapType.DANGER
            setColumns(listOf(TableColumn("colA", "Name"), TableColumn("colB", "Status")))
            setRows((0 until 24).map { r ->
                TableRow("r$r", listOf(TableCell("Row $r"), TableCell("Active")))
            })
        }
        val mdi = MdiContainer(showWindowToolbar = false, showTaskbar = false).apply {
            autoTileInitialWindows = false
        }
        val mdiWin1 = MdiWindow("win1", "Doc 1", initialBounds = Rect(0, 0, w - 24, 140)).apply {
            for (i in 0 until 20) {
                contentContainer.addChild(Label("MDI body row $i"))
            }
        }
        mdi.addWindow(mdiWin1)

        val scrollerKnobX = (w - 15).coerceAtLeast(0) until w

        fun verifyWidgetScrollsAndMovesNsScroller(targetComp: AbstractComponent, yStart: Int) {
            val prevParent = targetComp.parent as? AbstractContainer
            val prevIndex = prevParent?.children?.indexOf(targetComp) ?: -1
            targetComp.getStyle().apply {
                setWidth(CssLength.px(w))
                setHeight(CssLength.px(h))
            }
            val hostWin = Window(title = "Scroll Capture", initialSize = Size(w, h))
            hostWin.addChild(targetComp)
            hostWin.show(factory)
            try {
                val winImpl = hostWin.nativeImpl as MacosWindowNativeImpl
                val nativeImpl = targetComp.ensureNativeImpl()!!
                val handle = nativeImpl.nativeWidget as MacosViewHandle
                val sv = assertNotNull(handle.nsView as? NSScrollView, "${handle.nativeClassName} must mount NSScrollView")
                sv.setAutohidesScrollers(false)
                sv.setScrollerStyle(NSScrollerStyleLegacy)
                assertTrue(sv.hasVerticalScroller, "${handle.nativeClassName} must enable vertical NSScroller")

                val topShot = assertNotNull(MacosNativeWindowHost.captureWindowScreenshot(winImpl, w, h))
                val topPx = imageBufferToArgb(topShot)

                if (nativeImpl is DesktopScrollViewNativeImpl<*>) {
                    nativeImpl.scrollTo(0, 800)
                } else {
                    val clip = sv.contentView()
                    val docH = sv.documentView?.frame?.useContents { size.height } ?: 0.0
                    val clipH = clip.bounds.useContents { size.height }
                    val targetY = (docH - clipH).coerceIn(0.0, 800.0)
                    clip.scrollToPoint(NSMakePoint(0.0, targetY))
                    sv.reflectScrolledClipView(clip)
                }

                val scrolledOriginY = sv.contentView().bounds.useContents { origin.y }
                assertTrue(scrolledOriginY > 0.0, "NSScrollView contentView must scroll vertically for ${handle.nativeClassName}")

                val botShot = assertNotNull(MacosNativeWindowHost.captureWindowScreenshot(winImpl, w, h))
                val botPx = imageBufferToArgb(botShot)
                assertTrue(countDifferingPixels(topPx, botPx, w) > 0, "Scrolled NSScrollView must update pixels")

                val yScan = (yStart + 2) until (h - 2)
                val topCentroid = verticalKnobDeviationCentroid(topPx, w, scrollerKnobX, yScan, refY = yScan.last)
                val botCentroid = verticalKnobDeviationCentroid(botPx, w, scrollerKnobX, yScan, refY = yScan.first)
                assertTrue(
                    topCentroid > 0.0 && botCentroid > topCentroid + 15.0,
                    "Expected NSScroller centroid to move down for ${handle.nativeClassName}: top=$topCentroid, bot=$botCentroid"
                )
            } finally {
                hostWin.removeChild(targetComp)
                if (prevParent != null) {
                    if (prevIndex in 0..prevParent.children.size) {
                        prevParent.addChild(targetComp, prevIndex)
                    } else {
                        prevParent.addChild(targetComp)
                    }
                }
                hostWin.dispose()
            }
        }

        verifyWidgetScrollsAndMovesNsScroller(scrollView, yStart = 0)
        verifyWidgetScrollsAndMovesNsScroller(lazyList, yStart = 0)
        verifyWidgetScrollsAndMovesNsScroller(table, yStart = 30)
        verifyWidgetScrollsAndMovesNsScroller(mdiWin1.contentScroll, yStart = 0)

        val mdiHost = Window(title = "MDI Desktop Check", initialSize = Size(w, h))
        mdiHost.addChild(mdi)
        mdiHost.show(factory)
        try {
            val mdiWinImpl = mdiHost.nativeImpl as MacosWindowNativeImpl
            MacosNativeWindowHost.captureWindowScreenshot(mdiWinImpl, w, h)
            val tableImpl = table.nativeImpl!!
            assertTrue(((tableImpl.nativeWidget as MacosViewHandle).nsView as NSScrollView).documentView is NSTableView)
            val mdiDeskView = (mdi.desktopSurface.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView
            assertTrue(mdiDeskView is MacosContainerView, "MdiContainer.desktopSurface must mount retained MacosContainerView")
        } finally {
            mdiHost.dispose()
        }
    }

    @Test
    fun testTabContainerAndTabIgnoreBootstrapTypeAndReflectSelectedTab() = withMacosBackend { factory ->
        val tcDanger = TabContainer().apply {
            type = BootstrapType.DANGER
            backgroundType = BootstrapType.DANGER
        }
        val t1 = Tab(title = "General").apply { type = BootstrapType.DANGER }
        val t2 = Tab(title = "Network").apply { type = BootstrapType.SUCCESS }
        val t3 = Tab(title = "Privacy").apply { type = BootstrapType.WARNING }
        tcDanger.addTab(t1, Label("General panel"))
        tcDanger.addTab(t2, Label("Network panel"))
        tcDanger.addTab(t3, Label("Privacy panel"))

        val tcNone = TabContainer()
        tcNone.addTab(Tab(title = "General"), Label("General panel"))
        tcNone.addTab(Tab(title = "Network"), Label("Network panel"))
        tcNone.addTab(Tab(title = "Privacy"), Label("Privacy panel"))

        val w = 360
        val h = 80
        val (dangerPx, dangerHandle) = captureSingleComponentArgb(factory, tcDanger, w, h, darkMode = false)
        val (nonePx, _) = captureSingleComponentArgb(factory, tcNone, w, h, darkMode = false)

        val tabsView = assertNotNull(dangerHandle.nsView as? MacosTabContainerView)
        assertEquals(3, tabsView.stripView.subviews.size, "TabContainer strip must mount 3 tab cell views")

        assertTrue(countOpaque(dangerPx, w, yRange = 0 until 38) > 0, "Expected non-empty TabContainer strip pixels")
        assertEquals(
            0,
            countDifferingPixels(dangerPx, nonePx, w),
            "TabContainer & Tab must ignore BootstrapType.DANGER and render identical pixels to untinted tabs"
        )

        // Switch selection on a mounted TabContainer window and confirm pixels change in both deselected & newly selected segments
        tcDanger.getStyle().apply {
            setWidth(CssLength.px(w))
            setHeight(CssLength.px(h))
        }
        val win = Window(title = "Tab Switch Capture", initialSize = Size(w, h))
        win.addChild(tcDanger)
        win.show(factory)
        try {
            val winImpl = win.nativeImpl as MacosWindowNativeImpl
            val firstPx = imageBufferToArgb(assertNotNull(MacosNativeWindowHost.captureWindowScreenshot(winImpl, w, h)))
            tcDanger.selectedIndex = 2
            val tab3Px = imageBufferToArgb(assertNotNull(MacosNativeWindowHost.captureWindowScreenshot(winImpl, w, h)))
            assertTrue(
                countDifferingPixels(firstPx, tab3Px, w, xRange = 0 until (w / 3), yRange = 0 until 38) > 0,
                "Deselected segment 0 must visually change when segment 2 becomes active"
            )
            assertTrue(
                countDifferingPixels(firstPx, tab3Px, w, xRange = (2 * w / 3) until w, yRange = 0 until 38) > 0,
                "Selected segment 2 must visually change when activated"
            )
        } finally {
            win.dispose()
        }
    }

    @Test
    fun testStandardNonButtonControlsIgnoreBootstrapTypeAndNeverTint() = withMacosBackend { factory ->
        val checkNone = CheckBox("Agree", checked = true)
        val checkDanger = CheckBox("Agree", checked = true).apply { type = BootstrapType.DANGER }
        val radioNone = RadioButton("opt", "Option A", checked = true)
        val radioDanger = RadioButton("opt", "Option A", checked = true).apply { type = BootstrapType.DANGER }
        val switchNone = Switch("Wi-Fi", checked = true)
        val switchDanger = Switch("Wi-Fi", checked = true).apply { type = BootstrapType.DANGER }
        val sliderNone = Slider(value = 25.0, min = 0.0, max = 100.0)
        val sliderDanger = Slider(value = 25.0, min = 0.0, max = 100.0).apply { type = BootstrapType.DANGER }
        val sliderHigh = Slider(value = 80.0, min = 0.0, max = 100.0).apply { type = BootstrapType.DANGER }
        val rangeDanger = RangeSlider(minValue = 30.0, maxValue = 70.0).apply { type = BootstrapType.DANGER }
        val selectNone = Select(listOf(SelectOption("a", "Alpha"), SelectOption("b", "Beta")))
        val selectDanger = Select(listOf(SelectOption("a", "Alpha"), SelectOption("b", "Beta"))).apply {
            type = BootstrapType.DANGER
        }
        val comboDanger = ComboBox(listOf(SelectOption("x", "Combo"))).apply {
            type = BootstrapType.DANGER
        }
        val textDanger = TextField(value = "hello").apply { type = BootstrapType.DANGER }
        val numDanger = NumberField(numericValue = 42.0).apply { type = BootstrapType.DANGER }
        val textAreaDanger = TextArea(value = "notes").apply { type = BootstrapType.DANGER }
        val spinnerDanger = Spinner(label = "").apply { type = BootstrapType.DANGER }
        val collapsibleNone = CollapsiblePanel(title = "Advanced", expanded = true)
        val collapsibleDanger = CollapsiblePanel(title = "Advanced", expanded = true).apply {
            type = BootstrapType.DANGER
        }
        val containerDanger = Container().apply {
            type = BootstrapType.DANGER
            backgroundType = BootstrapType.DANGER
        }

        // Standard Container with BootstrapType must NOT render a tinted/opaque box
        val (containerPx, containerHandle) = captureSingleComponentArgb(factory, containerDanger, 160, 60)
        assertTrue(containerHandle.nsView is MacosContainerView)
        assertEquals(0, countOpaque(containerPx, 160), "Standard Container must remain transparent regardless of BootstrapType")

        // Verify mounted AppKit classes inside an attached Window
        val inspectWin = Window(title = "Non-Button Classes", initialSize = Size(320, 420))
        val inspectHost = Container().apply {
            addChild(checkDanger)
            addChild(radioDanger)
            addChild(switchDanger)
            addChild(sliderDanger)
            addChild(rangeDanger)
            addChild(selectDanger)
            addChild(comboDanger)
            addChild(textDanger)
            addChild(numDanger)
            addChild(textAreaDanger)
            addChild(spinnerDanger)
            addChild(collapsibleDanger)
        }
        inspectWin.setContent(inspectHost)
        inspectWin.show(factory)
        try {
            assertTrue((checkDanger.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView is NSButton)
            assertTrue((radioDanger.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView is NSButton)
            val switchView = (switchDanger.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView
            assertTrue(switchView is NSSwitch || switchView is MacosSwitchLabeledView)
            assertTrue((sliderDanger.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView is NSSlider)
            assertTrue((rangeDanger.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView is NSSlider)
            assertTrue((selectDanger.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView is NSPopUpButton)
            assertTrue((comboDanger.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView is NSComboBox)
            assertTrue((textDanger.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView is NSTextField)
            assertNotNull((numDanger.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView)
            val taScroll = (textAreaDanger.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView as? NSScrollView
            assertNotNull(taScroll)
            assertTrue(taScroll.documentView is NSTextView)
            val spView = (spinnerDanger.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView as? NSProgressIndicator
            assertNotNull(spView)
            assertEquals(NSProgressIndicatorStyleSpinning, spView.style)
            assertTrue((collapsibleDanger.headerButton.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView is NSButton)
        } finally {
            inspectHost.clearChildren()
            inspectWin.close()
            inspectWin.dispose()
        }

        // Compare identical pixels between NONE and DANGER across non-button native AppKit controls
        fun assertPairIdentical(a: AbstractComponent, b: AbstractComponent, width: Int = 180, height: Int = 32) {
            val (pxA, _) = captureSingleComponentArgb(factory, a, width, height)
            val (pxB, _) = captureSingleComponentArgb(factory, b, width, height)
            assertTrue(countOpaque(pxA, width) > 0, "Expected non-empty AppKit pixels for $a")
            assertEquals(0, countDifferingPixels(pxA, pxB, width), "Non-button $b must match untinted $a identically")
        }

        assertPairIdentical(checkNone, checkDanger)
        assertPairIdentical(radioNone, radioDanger)
        assertPairIdentical(switchNone, switchDanger)
        assertPairIdentical(sliderNone, sliderDanger, width = 200, height = 28)
        assertPairIdentical(selectNone, selectDanger, width = 180, height = 30)
        assertPairIdentical(collapsibleNone, collapsibleDanger, width = 200, height = 44)

        // Slider 25 vs 80 moves AppKit knob/fill visibly
        val (sLowPx, _) = captureSingleComponentArgb(factory, sliderNone, 200, 28)
        val (sHighPx, _) = captureSingleComponentArgb(factory, sliderHigh, 200, 28)
        assertTrue(countDifferingPixels(sLowPx, sHighPx, 200) > 20, "NSSlider 25 vs 80 must differ visually")
    }

    @Test
    fun testButtonsOnlyRetainAppKitNsButtonAndBootstrapTintControl() = withMacosBackend { factory ->
        val btnNative = Button("Save", type = BootstrapType.NONE)
        val btnDanger = Button("Delete", type = BootstrapType.DANGER)
        val btnPrimary = Button("Apply", type = BootstrapType.PRIMARY)
        val iconDanger = IconButton(icon = "+", type = BootstrapType.DANGER).apply { outline = false }
        val toggleSelected = ToggleButton("Bold", selected = true, type = BootstrapType.DANGER)
        val dropdownDanger = DropdownButton(label = "Actions").apply { type = BootstrapType.DANGER }
        val splitDanger = SplitDropdownButton(label = "Export").apply { type = BootstrapType.DANGER }

        val w = 120
        val h = 36

        val (nativePx, nativeHandle) = captureSingleComponentArgb(factory, btnNative, w, h)
        assertTrue((btnNative.ensureNativeImpl() as MacosButtonNativeImpl).isNativeDefaultButtonMode())
        val nativeNsBtn = assertNotNull(nativeHandle.nsView as? NSButton, "Default Button(NONE) must mount genuine unmodified AppKit NSButton")
        assertTrue(nativeNsBtn.isBordered())
        assertTrue(nativeNsBtn.bezelColor == null && nativeNsBtn.contentTintColor == null, "Default Button(NONE) must leave bezelColor and contentTintColor null")
        assertTrue(countOpaque(nativePx, w) > 0)

        for (btn in listOf(btnDanger, iconDanger, toggleSelected)) {
            val (_, tintedHandle) = captureSingleComponentArgb(factory, btn, w, h)
            val impl = btn.ensureNativeImpl() as MacosButtonNativeImpl
            assertFalse(impl.isNativeDefaultButtonMode())
            val nsBtn = assertNotNull(tintedHandle.nsView as? NSButton, "Typed button $btn must mount genuine NSButton")
            assertTrue(nsBtn.isBordered(), "Filled DANGER button $btn must stay bordered NSButton")
            val bezel = assertNotNull(nsBtn.bezelColor, "Filled DANGER button $btn must set native NSButton.bezelColor")
            val bezelRgba = MacosAppKitAppearance.toRgbaColor(bezel)
            assertTrue(
                bezelRgba.r - bezelRgba.g >= 60 && bezelRgba.r - bezelRgba.b >= 60,
                "Button $btn with BootstrapType.DANGER must set red-dominant native bezelColor, got $bezelRgba"
            )
        }

        // PRIMARY sets a distinct blue-dominant native bezelColor on NSButton
        val (_, primaryHandle) = captureSingleComponentArgb(factory, btnPrimary, w, h)
        val primaryBezel = assertNotNull((primaryHandle.nsView as? NSButton)?.bezelColor)
        val primaryBezelRgba = MacosAppKitAppearance.toRgbaColor(primaryBezel)
        assertTrue(
            primaryBezelRgba.b - primaryBezelRgba.r >= 60,
            "PRIMARY NSButton must set blue-dominant native bezelColor, got $primaryBezelRgba"
        )

        // Outline & Link buttons use native borderless NSButton + contentTintColor / showsBorderOnlyWhileMouseInside
        val btnOutlineDanger = Button("Outline", type = BootstrapType.DANGER).apply { outline = true }
        val btnOutlinePrimary = Button("Outline", type = BootstrapType.PRIMARY).apply { outline = true }
        val (outlineDangerPx, outlineHandle) = captureSingleComponentArgb(factory, btnOutlineDanger, w, h)
        val (outlinePrimaryPx, _) = captureSingleComponentArgb(factory, btnOutlinePrimary, w, h)
        val outlineNsBtn = assertNotNull(outlineHandle.nsView as? NSButton)
        assertFalse(outlineNsBtn.isBordered())
        assertTrue(outlineNsBtn.showsBorderOnlyWhileMouseInside())
        val tintColor = assertNotNull(outlineNsBtn.contentTintColor, "Outline DANGER button must set NSButton.contentTintColor")
        val tintRgba = MacosAppKitAppearance.toRgbaColor(tintColor)
        assertTrue(tintRgba.r - tintRgba.g >= 60 && tintRgba.r - tintRgba.b >= 60)
        assertTrue(
            countRedDominantPixels(outlineDangerPx) > 100,
            "Outline DANGER NSButton must render native contentTintColor red-dominant pixels in cacheDisplayInRect"
        )
        assertTrue(
            countDifferingPixels(outlineDangerPx, outlinePrimaryPx, w) > 100,
            "Outline DANGER and PRIMARY NSButtons must render distinct native contentTintColor pixels"
        )
        assertTrue(
            countDifferingPixels(nativePx, outlineDangerPx, w) > 100,
            "Outline DANGER NSButton must differ visually from untinted native NSButton"
        )

        // DropdownButton & SplitDropdownButton must remain native AppKit popups and ignore BootstrapType.DANGER
        val (dropPx, dropHandle) = captureSingleComponentArgb(factory, dropdownDanger, w, h)
        assertTrue(dropHandle.nsView is NSPopUpButton, "DropdownButton must stay NSPopUpButton even with BootstrapType.DANGER")
        assertEquals(0, countRedDominantPixels(dropPx), "DropdownButton must ignore semantic Bootstrap danger tint")

        val (splitPx, splitHandle) = captureSingleComponentArgb(factory, splitDanger, 160, h)
        assertTrue(splitHandle.nsView is MacosSplitDropdownView, "SplitDropdownButton must stay MacosSplitDropdownView")
        assertEquals(0, countRedDominantPixels(splitPx), "SplitDropdownButton must ignore semantic Bootstrap danger tint")
    }
}
