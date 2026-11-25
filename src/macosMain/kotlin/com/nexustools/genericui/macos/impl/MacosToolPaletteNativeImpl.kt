@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos.impl

import com.nexustools.genericui.component.dock.ToolPaletteItem
import com.nexustools.genericui.desktop.dock.DesktopToolPaletteNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
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
import kotlinx.cinterop.CValue
import platform.AppKit.NSButton
import platform.AppKit.NSView
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSMakeRect
import platform.Foundation.NSRect

internal class MacosPaletteColorSwatchView(
    private val onSwap: () -> Unit,
    frameRect: CValue<NSRect> = NSMakeRect(0.0, 0.0, 44.0, 44.0)
) : NSView(frameRect) {

    private val bgView = NSView(NSMakeRect(16.0, 16.0, 22.0, 22.0)).apply { setWantsLayer(true) }
    private val fgView = NSView(NSMakeRect(6.0, 6.0, 22.0, 22.0)).apply { setWantsLayer(true) }

    init {
        setWantsLayer(true)
        addSubview(bgView)
        addSubview(fgView)
    }

    override fun isFlipped(): Boolean = true

    override fun mouseUp(event: platform.AppKit.NSEvent) {
        onSwap()
    }

    fun updateColors(fg: RgbaColor, bg: RgbaColor) {
        com.nexustools.genericui.apple.style.AppleStyledSurfaceHelper.withCgColor(bg) { cg ->
            bgView.layer?.backgroundColor = cg
        }
        com.nexustools.genericui.apple.style.AppleStyledSurfaceHelper.withCgColor(fg) { cg ->
            fgView.layer?.backgroundColor = cg
        }
    }
}

internal class MacosToolPaletteRootView(
    onSwapColors: () -> Unit,
    frameRect: CValue<NSRect> = NSMakeRect(0.0, 0.0, 80.0, 320.0)
) : NSView(frameRect) {

    val surfaceSupport = MacosSurfaceLayerSupport(this)
    val swatchView = MacosPaletteColorSwatchView(onSwap = onSwapColors)

    init {
        setWantsLayer(true)
        addSubview(swatchView)
    }

    override fun isFlipped(): Boolean = true
}

/**
 * macOS AppKit retained tool palette implementation of [DesktopToolPaletteNativeImpl].
 */
class MacosToolPaletteNativeImpl(
    nativeWidget: MacosViewHandle
) : DesktopToolPaletteNativeImpl<MacosViewHandle>(nativeWidget) {

    private var rootView: MacosToolPaletteRootView? = null
    private val toolButtons = mutableListOf<NSButton>()
    private val toolTargets = mutableListOf<MacosControlActionTarget>()

    init {
        nativeWidget.viewMaterializer = {
            ensureRootView().also { rebuildPalette() }
        }
        if (MacosAppKitDispatch.isMainThread()) {
            ensureRootView()
            rebuildPalette()
        }
    }

    private fun ensureRootView(): MacosToolPaletteRootView {
        rootView?.let { return it }
        val v = MacosToolPaletteRootView(onSwapColors = { dispatchSwapColors() })
        rootView = v
        nativeWidget.nsView = v
        v.setHidden(!nativeWidget.visible)
        v.setAlphaValue(nativeWidget.opacity.coerceIn(0f, 1f).toDouble())
        v.setToolTip(nativeWidget.tooltipText)
        return v
    }

    private fun rebuildPalette() {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { rebuildPalette() }
            return
        }
        val v = ensureRootView()
        for (b in toolButtons) b.removeFromSuperview()
        toolButtons.clear()
        toolTargets.clear()

        val darkTheme = MacosAppKitAppearance.isDarkTheme(nativeWidget)
        val accent = BootstrapColorPalette.resolveColor(BootstrapType.PRIMARY, darkTheme)

        for (item in toolsList) {
            val isActive = item.id == currentToolId
            val target = MacosControlActionTarget {
                setActiveToolId(item.id)
            }
            toolTargets.add(target)
            val img = item.iconSvgOrPath.takeIf { it.isNotBlank() }?.let {
                MacosAppKitAppearance.resolveNsImage(it, 18, templateIfMonochrome = true)
            }
            val btn = NSButton.buttonWithTitle(
                if (img == null) item.name.take(3) else "",
                target = target,
                action = target.actionSelector
            ).apply {
                setToolTip(item.tooltip ?: item.name)
                setWantsLayer(true)
                if (isActive) {
                    com.nexustools.genericui.apple.style.AppleStyledSurfaceHelper.withCgColor(accent.copy(a = 70)) { cg ->
                        layer?.backgroundColor = cg
                    }
                    layer?.cornerRadius = 6.0
                } else {
                    layer?.backgroundColor = null
                }
                if (img != null) {
                    setImage(img)
                    setImagePosition(platform.AppKit.NSImageOnly)
                }
            }
            v.addSubview(btn)
            toolButtons.add(btn)
        }
        v.swatchView.updateColors(fgColor, bgColor)
        layoutPaletteItems()
    }

    private fun layoutPaletteItems() {
        val v = rootView ?: return
        val cols = columnCount.coerceAtLeast(1)
        val btnSize = 32.0
        val pad = 4.0
        for ((idx, btn) in toolButtons.withIndex()) {
            val r = idx / cols
            val c = idx % cols
            btn.setFrame(CGRectMake(pad + c * (btnSize + pad), pad + r * (btnSize + pad), btnSize, btnSize))
        }
        val rows = if (toolButtons.isEmpty()) 0 else ((toolButtons.size + cols - 1) / cols)
        val swatchY = pad + rows * (btnSize + pad) + 6.0
        v.swatchView.setFrame(CGRectMake(pad, swatchY, 44.0, 44.0))
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val cols = columnCount.coerceAtLeast(1)
        val rows = if (toolsList.isEmpty()) 1 else ((toolsList.size + cols - 1) / cols)
        return Size((cols * 36 + 8).coerceAtLeast(48), rows * 36 + 56)
    }

    override fun setColumns(columns: Int) {
        super.setColumns(columns)
        rebuildPalette()
    }

    override fun setTools(tools: List<ToolPaletteItem>, activeToolId: String?) {
        super.setTools(tools, activeToolId)
        rebuildPalette()
    }

    override fun setActiveToolId(toolId: String?) {
        super.setActiveToolId(toolId)
        rebuildPalette()
    }

    override fun setForegroundColor(color: RgbaColor) {
        super.setForegroundColor(color)
        rootView?.swatchView?.updateColors(fgColor, bgColor)
    }

    override fun setBackgroundColor(color: RgbaColor) {
        super.setBackgroundColor(color)
        rootView?.swatchView?.updateColors(fgColor, bgColor)
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { onNativeBoundsChanged(nativeWidget.bounds) }
            return
        }
        val v = (nativeWidget.ensureNsViewOnMain() as? MacosToolPaletteRootView) ?: rootView ?: return
        MacosAppKitDispatch.applyFrameAndTransform(v, rect, nativeWidget.cssTransform, useAlignmentRectExpansion = false)
        layoutPaletteItems()
    }

    override fun onNativeVisibilityChanged(visible: Boolean) {
        rootView?.setHidden(!visible)
    }

    override fun onNativeOpacityChanged(opacity: Float) {
        rootView?.setAlphaValue(opacity.coerceIn(0f, 1f).toDouble())
    }

    override fun onNativeTooltipChanged(tooltip: String?) {
        rootView?.setToolTip(tooltip)
    }

    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        rootView?.let { v -> v.window?.invalidateCursorRectsForView(v) }
    }

    override fun onNativeTransformChanged(transform: CssTransform?) {
        nativeWidget.cssTransform = transform
        val v = rootView ?: return
        MacosAppKitDispatch.applyFrameAndTransform(v, nativeWidget.bounds, transform, useAlignmentRectExpansion = false)
    }

    override fun dispose() {
        toolButtons.clear()
        toolTargets.clear()
        val view = rootView
        rootView = null
        MacosAppKitDispatch.detachViewOnMain(view)
        super.dispose()
    }
}
