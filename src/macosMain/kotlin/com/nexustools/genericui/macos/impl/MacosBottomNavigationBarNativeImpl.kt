@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos.impl

import com.nexustools.genericui.desktop.widget.DesktopBottomNavigationBarNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.css.CssBorderStyle
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.macos.MacosAppKitAppearance
import com.nexustools.genericui.macos.MacosAppKitDispatch
import com.nexustools.genericui.macos.MacosControlActionTarget
import com.nexustools.genericui.macos.MacosSurfaceLayerSupport
import com.nexustools.genericui.macos.MacosViewHandle
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.style.css.StyledVisualSpec
import kotlinx.cinterop.CValue
import kotlinx.cinterop.useContents
import platform.AppKit.NSButton
import platform.AppKit.NSImageAbove
import platform.AppKit.NSNoImage
import platform.AppKit.NSView
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSMakeRect
import platform.Foundation.NSRect

internal class MacosBottomNavView(
    frameRect: CValue<NSRect> = NSMakeRect(0.0, 0.0, 400.0, 56.0)
) : NSView(frameRect) {

    val surfaceSupport = MacosSurfaceLayerSupport(this)

    init {
        setWantsLayer(true)
    }

    override fun isFlipped(): Boolean = true
}

/**
 * macOS AppKit retained navigation bar implementation of [DesktopBottomNavigationBarNativeImpl].
 */
class MacosBottomNavigationBarNativeImpl(
    nativeWidget: MacosViewHandle
) : DesktopBottomNavigationBarNativeImpl<MacosViewHandle>(nativeWidget) {

    private var barView: MacosBottomNavView? = null
    private val itemTargets = mutableListOf<MacosControlActionTarget>()
    private val itemButtons = mutableListOf<NSButton>()

    init {
        nativeWidget.viewMaterializer = {
            ensureBarView().also { rebuildItems() }
        }
        if (MacosAppKitDispatch.isMainThread()) {
            ensureBarView()
            rebuildItems()
        }
    }

    private fun ensureBarView(): MacosBottomNavView {
        barView?.let { return it }
        val v = MacosBottomNavView()
        barView = v
        nativeWidget.nsView = v
        v.setHidden(!nativeWidget.visible)
        v.setAlphaValue(nativeWidget.opacity.coerceIn(0f, 1f).toDouble())
        v.setToolTip(nativeWidget.tooltipText)
        return v
    }

    private fun rebuildItems() {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { rebuildItems() }
            return
        }
        val v = ensureBarView()
        val darkTheme = MacosAppKitAppearance.isDarkTheme(nativeWidget)
        val bg = lastComputedStyle?.backgroundColor
            ?: if (darkTheme) RgbaColor(30, 32, 38, 240) else RgbaColor(246, 247, 250, 240)
        val border = if (darkTheme) RgbaColor(60, 64, 72, 255) else RgbaColor(218, 222, 230, 255)
        v.surfaceSupport.applyStyledVisuals(
            spec = StyledVisualSpec(
                backgroundColor = bg,
                borderColor = border,
                borderWidthPx = 1f,
                borderStyle = CssBorderStyle.SOLID
            ),
            cornerRadii = null,
            boxShadow = null,
            isFocused = false,
            clipOverflow = false,
            darkTheme = darkTheme,
            effectiveBackdropFilter = null,
            effectiveForegroundFilter = null
        )

        for (btn in itemButtons) btn.removeFromSuperview()
        itemButtons.clear()
        itemTargets.clear()

        val accent = BootstrapColorPalette.resolveColor(BootstrapType.PRIMARY, darkTheme)
        val normalFg = if (darkTheme) RgbaColor(185, 190, 200, 255) else RgbaColor(90, 96, 108, 255)

        for ((id, label, icon) in itemsList) {
            val isSelected = id == selectedId
            val target = MacosControlActionTarget {
                setSelectedItemId(id)
            }
            itemTargets.add(target)
            val btn = NSButton.buttonWithTitle(label, target = target, action = target.actionSelector).apply {
                setBordered(false)
                val fg = if (isSelected) accent else normalFg
                val font = MacosAppKitAppearance.resolveFont(null, null, 11.5, defaultBold = isSelected)
                setAttributedTitle(MacosAppKitAppearance.buildAttributedString(label, null, fg, font))
                val img = icon?.let { MacosAppKitAppearance.resolveNsImage(it, 18, tintColor = fg) }
                if (img != null) {
                    setImage(img)
                    setImagePosition(NSImageAbove)
                } else {
                    setImagePosition(NSNoImage)
                }
            }
            v.addSubview(btn)
            itemButtons.add(btn)
        }
        layoutItems()
    }

    private fun layoutItems() {
        val v = barView ?: return
        val count = itemButtons.size
        if (count == 0) return
        val (w, h) = v.bounds.useContents { size.width to size.height }
        if (w <= 0.0 || h <= 0.0) return
        val slotW = w / count.toDouble()
        for ((idx, btn) in itemButtons.withIndex()) {
            btn.setFrame(CGRectMake(idx * slotW, 2.0, slotW, (h - 4.0).coerceAtLeast(0.0)))
        }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size =
        Size((maxWidth?.toInt() ?: 400).coerceAtLeast(160), 56)

    override fun setItems(items: List<Triple<String, String, String?>>) {
        super.setItems(items)
        rebuildItems()
    }

    override fun setSelectedItemId(itemId: String) {
        super.setSelectedItemId(itemId)
        rebuildItems()
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer {
                if (!isDisposed) onNativeBoundsChanged(nativeWidget.bounds)
            }
            return
        }
        val v = ensureBarView()
        MacosAppKitDispatch.applyFrameAndTransform(v, rect, nativeWidget.cssTransform, useAlignmentRectExpansion = false)
        layoutItems()
    }

    override fun onNativeVisibilityChanged(visible: Boolean) {
        barView?.setHidden(!visible)
    }

    override fun onNativeOpacityChanged(opacity: Float) {
        barView?.setAlphaValue(opacity.coerceIn(0f, 1f).toDouble())
    }

    override fun onNativeTooltipChanged(tooltip: String?) {
        barView?.setToolTip(tooltip)
    }

    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        barView?.let { v -> v.window?.invalidateCursorRectsForView(v) }
    }

    override fun onNativeTransformChanged(transform: CssTransform?) {
        nativeWidget.cssTransform = transform
        val v = barView ?: return
        MacosAppKitDispatch.applyFrameAndTransform(v, nativeWidget.bounds, transform, useAlignmentRectExpansion = false)
    }

    override fun onNativeStyleSynced() {
        rebuildItems()
    }

    override fun dispose() {
        itemButtons.clear()
        itemTargets.clear()
        val view = barView
        barView = null
        MacosAppKitDispatch.detachViewOnMain(view)
        super.dispose()
    }
}
