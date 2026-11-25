@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.apple.style.AppleStyledSurfaceHelper
import com.nexustools.genericui.style.color.RgbaColor
import kotlinx.cinterop.CValue
import kotlinx.cinterop.useContents
import platform.AppKit.NSAppearance
import platform.AppKit.NSAppearanceNameAqua
import platform.AppKit.NSAppearanceNameDarkAqua
import platform.AppKit.NSGraphicsContext
import platform.AppKit.NSView
import platform.AppKit.NSVisualEffectBlendingMode
import platform.AppKit.NSVisualEffectMaterialTitlebar
import platform.AppKit.NSVisualEffectState
import platform.AppKit.NSVisualEffectView
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextSetFillColorWithColor
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSMakeRect
import platform.Foundation.NSPoint
import platform.Foundation.NSRect
import platform.Foundation.NSSize

private class MacosUnifiedHeaderForegroundView(
    frameRect: CValue<NSRect> = NSMakeRect(0.0, 0.0, 0.0, 0.0)
) : NSView(frameRect) {

    var darkTheme: Boolean = true

    init {
        setWantsLayer(true)
    }

    override fun isFlipped(): Boolean = true

    override fun hitTest(point: CValue<NSPoint>): NSView? = null

    override fun drawRect(dirtyRect: CValue<NSRect>) {
        val (w, h) = bounds.useContents { size.width to size.height }
        if (w <= 0.0 || h <= 0.0) return
        val ctx = NSGraphicsContext.currentContext?.CGContext ?: return
        val tintFill = if (darkTheme) {
            RgbaColor(32, 34, 40, 242)
        } else {
            RgbaColor(246, 247, 250, 242)
        }
        AppleStyledSurfaceHelper.withCgColor(tintFill) { cg ->
            CGContextSetFillColorWithColor(ctx, cg)
            CGContextFillRect(ctx, CGRectMake(0.0, 0.0, w, h))
        }
        if (h >= 2.0) {
            val hairline = if (darkTheme) {
                RgbaColor(54, 58, 68, 255)
            } else {
                RgbaColor(214, 218, 226, 255)
            }
            AppleStyledSurfaceHelper.withCgColor(hairline) { cg ->
                CGContextSetFillColorWithColor(ctx, cg)
                CGContextFillRect(ctx, CGRectMake(0.0, h - 1.0, w, 1.0))
            }
        }
    }
}

/**
 * Unified AppKit backdrop view spanning both the native macOS titlebar band (`0 until titleBarInsetTopPx`)
 * and a stacked [MacosNavbarNativeImpl] directly beneath it so there is zero horizontal seam between
 * the titlebar and navbar chrome.
 */
internal class MacosUnifiedHeaderBarView(
    frameRect: CValue<NSRect> = NSMakeRect(0.0, 0.0, 0.0, 0.0)
) : NSView(frameRect) {

    private var darkTheme: Boolean = true
    private var lastChromeDarkTheme: Boolean? = null
    private var lastChromeWidthPx: Int = -1
    private var lastChromeHeightPx: Int = -1

    private val visualEffectView = NSVisualEffectView(NSMakeRect(0.0, 0.0, 0.0, 0.0)).apply {
        setMaterial(NSVisualEffectMaterialTitlebar)
        setBlendingMode(NSVisualEffectBlendingMode.NSVisualEffectBlendingModeWithinWindow)
        setState(NSVisualEffectState.NSVisualEffectStateActive)
    }

    private val foregroundView = MacosUnifiedHeaderForegroundView(NSMakeRect(0.0, 0.0, 0.0, 0.0))

    init {
        setWantsLayer(true)
        addSubview(visualEffectView)
        addSubview(foregroundView)
        applyThemeFill()
    }

    override fun isFlipped(): Boolean = true

    override fun hitTest(point: CValue<NSPoint>): NSView? = null

    override fun setFrameSize(newSize: CValue<NSSize>) {
        super.setFrameSize(newSize)
        val (w, h) = newSize.useContents { width to height }
        val r = CGRectMake(0.0, 0.0, w.coerceAtLeast(0.0), h.coerceAtLeast(0.0))
        visualEffectView.setFrame(r)
        foregroundView.setFrame(r)
        foregroundView.setNeedsDisplay(true)
    }

    override fun drawRect(dirtyRect: CValue<NSRect>) {
        val (w, h) = bounds.useContents { size.width to size.height }
        if (w <= 0.0 || h <= 0.0) return
        val ctx = NSGraphicsContext.currentContext?.CGContext ?: return
        val baseFill = if (darkTheme) {
            RgbaColor(32, 34, 40, 255)
        } else {
            RgbaColor(246, 247, 250, 255)
        }
        AppleStyledSurfaceHelper.withCgColor(baseFill) { cg ->
            CGContextSetFillColorWithColor(ctx, cg)
            CGContextFillRect(ctx, CGRectMake(0.0, 0.0, w, h))
        }
    }

    fun updateChromeAppearance(darkTheme: Boolean, widthPx: Int, heightPx: Int) {
        val themeChanged = lastChromeDarkTheme != darkTheme
        val sizeChanged = lastChromeWidthPx != widthPx || lastChromeHeightPx != heightPx
        if (!themeChanged && !sizeChanged) return
        lastChromeDarkTheme = darkTheme
        lastChromeWidthPx = widthPx
        lastChromeHeightPx = heightPx
        this.darkTheme = darkTheme
        foregroundView.darkTheme = darkTheme
        if (themeChanged) {
            val appearanceName = if (darkTheme) NSAppearanceNameDarkAqua else NSAppearanceNameAqua
            visualEffectView.setAppearance(NSAppearance.appearanceNamed(appearanceName))
            applyThemeFill()
        }
        if (sizeChanged) {
            val w = widthPx.coerceAtLeast(0).toDouble()
            val h = heightPx.coerceAtLeast(0).toDouble()
            setFrame(CGRectMake(0.0, 0.0, w, h))
            val inner = CGRectMake(0.0, 0.0, w, h)
            visualEffectView.setFrame(inner)
            foregroundView.setFrame(inner)
        }
        setNeedsDisplay(true)
        foregroundView.setNeedsDisplay(true)
    }

    private fun applyThemeFill() {
        val baseFill = if (darkTheme) {
            RgbaColor(32, 34, 40, 255)
        } else {
            RgbaColor(246, 247, 250, 255)
        }
        AppleStyledSurfaceHelper.withCgColor(baseFill) { cg ->
            layer?.backgroundColor = cg
        }
    }
}
