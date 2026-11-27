@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk

import com.nexustools.genericui.desktop.widget.DesktopStyledButtonNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.gtk.widget.GtkRetainedContainerSupport
import com.nexustools.genericui.gtk.widget.GtkSurfaceRenderHelper
import com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge
import com.nexustools.genericui.linux.style.LinuxBackdropFilterController
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.COpaquePointer
import kotlin.math.ceil
import kotlin.math.max

/**
 * Retained GTK4 styled button (`GtkDrawingArea` with focus/hover/press/disabled visual states,
 * multi-stop gradients, borders, box-shadows, 10-op `backdrop-filter`, foreground `CssFilter`,
 * and centered Pango typography).
 */
open class GtkStyledButtonNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkDrawingArea_StyledButton"),
    classPrefix: String = "styled-btn"
) : DesktopStyledButtonNativeImpl<GtkWidgetHandle>(nativeWidget, classPrefix) {

    init {
        nativeWidget.ownerImpl = this
        nativeWidget.attributes["useSystemControlAppearance"] = "false"
        nativeWidget.hasCustomSurfaceBackground = true
        nativeWidget.onDrawSurfaceCairo = { cr, width, height -> paintStyledButton(cr, width, height) }
        nativeWidget.widgetMaterializer = { materializeStyledButton() }
    }

    private fun materializeStyledButton(): COpaquePointer? {
        nativeWidget.realGtkWidgetPtr?.let { return it }
        if (!Gtk4DynamicBindings.ensureGtkInitialized()) return null
        val da = GtkWidgetLifecycleBridge.createDrawingSurfaceForPainter(nativeWidget) { cr, w, h ->
            paintStyledButton(cr, w, h)
        } ?: return null
        Gtk4DynamicBindings.gtkWidgetSetFocusable(da, true)
        GtkWidgetLifecycleBridge.attachControllersAndState(
            impl = this,
            rootPtr = da,
            focusTargetPtr = da,
            isTextInput = false,
            dispatchClickOnPrimaryRelease = true,
            attachClickGesture = true
        )
        return da
    }

    private fun paintStyledButton(cr: COpaquePointer, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val dark = GtkRetainedContainerSupport.resolveDarkTheme()
        val visuals = resolveCurrentVisuals()
        val computed = getComputedStyle()
        val paintSpec = GtkSurfaceRenderHelper.fromStyledVisualSpec(
            spec = visuals,
            cornerRadii = computed?.borderRadius,
            backdropFilter = nativeWidget.attributes["backdropFilter"] as? com.nexustools.genericui.style.css.CssFilter ?: visuals.backdropFilter,
            foregroundFilter = nativeWidget.attributes["cssFilter"] as? com.nexustools.genericui.style.css.CssFilter ?: visuals.filter,
            opacity = visuals.opacity ?: getOpacity(),
            darkTheme = dark
        )
        val hasBackdrop = GtkSurfaceRenderHelper.effectiveBackdropFilter(paintSpec)?.let {
            LinuxBackdropFilterController.shared.hasActiveEffects(it)
        } == true
        val behindCapture: (() -> IntArray?)? = if (hasBackdrop) {
            { GtkWidgetLifecycleBridge.captureBehindWidgetPremulBgra(this, width, height) }
        } else {
            null
        }
        GtkSurfaceRenderHelper.paintSurface(
            cr = cr,
            width = width,
            height = height,
            spec = paintSpec,
            behindBgraCapture = behindCapture
        )

        val icon = getIcon()?.takeIf { it.isNotBlank() }
        val text = getText()
        val display = if (icon != null && text.isNotEmpty()) "$icon $text" else (icon ?: text)
        if (display.isNotEmpty()) {
            val fg = visuals.textColor ?: if (dark) RgbaColor.WHITE else RgbaColor(20, 22, 26, 255)
            val fontSize = (nativeWidget.attributes["fontSizePx"] as? Float) ?: 14f
            GtkSurfaceRenderHelper.drawPangoText(
                cr = cr,
                text = display,
                x = 6.0,
                y = 0.0,
                width = (width - 12.0).coerceAtLeast(1.0),
                height = height.toDouble(),
                color = fg,
                fontSizePx = fontSize,
                fontWeight = Gtk4DynamicBindings.PANGO_WEIGHT_SEMIBOLD,
                align = Gtk4DynamicBindings.PANGO_ALIGN_CENTER,
                singleLine = true,
                opacity = visuals.opacity ?: getOpacity()
            )
        }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val visuals = resolveCurrentVisuals()
        val icon = getIcon()?.takeIf { it.isNotBlank() }
        val text = getText()
        val display = if (icon != null && text.isNotEmpty()) "$icon $text" else (icon ?: text).ifEmpty { " " }
        val fontSize = (nativeWidget.attributes["fontSizePx"] as? Float) ?: 14f
        val (tw, th) = GtkSurfaceRenderHelper.measureTextSize(
            text = display,
            fontSizePx = fontSize,
            fontWeight = Gtk4DynamicBindings.PANGO_WEIGHT_SEMIBOLD
        )
        val prefW = visuals.widthPx ?: max(visuals.minWidthPx ?: 44, tw + 24)
        val prefH = visuals.heightPx ?: max(visuals.minHeightPx ?: 34, th + 12)
        return Size(prefW, prefH)
    }

    override fun setText(text: String) {
        super.setText(text)
        nativeWidget.realGtkWidgetPtr?.let { Gtk4DynamicBindings.gtkWidgetQueueDraw(it) }
    }

    override fun setIcon(icon: String?) {
        super.setIcon(icon)
        nativeWidget.realGtkWidgetPtr?.let { Gtk4DynamicBindings.gtkWidgetQueueDraw(it) }
    }

    override fun onNativeBoundsChanged(rect: Rect) = GtkWidgetLifecycleBridge.onBoundsChanged(this, rect)
    override fun onNativeVisibilityChanged(visible: Boolean) = GtkWidgetLifecycleBridge.onVisibilityChanged(this, visible)
    override fun onNativeEnabledChanged(enabled: Boolean) = GtkWidgetLifecycleBridge.onEnabledChanged(this, enabled)
    override fun onNativeOpacityChanged(opacity: Float) = GtkWidgetLifecycleBridge.onOpacityChanged(this, opacity)
    override fun onNativeTooltipChanged(tooltip: String?) = GtkWidgetLifecycleBridge.onTooltipChanged(this, tooltip)
    override fun onNativeCursorChanged(cursor: CssCursorValue?) = GtkWidgetLifecycleBridge.onCursorChanged(this, cursor)
    override fun onNativePointerEventsChanged(pointerEventsNone: Boolean) = GtkWidgetLifecycleBridge.onPointerEventsChanged(this, pointerEventsNone)
    override fun onNativeZIndexChanged(zIndex: Int) = GtkWidgetLifecycleBridge.onZIndexChanged(this, zIndex)
    override fun onNativeTransformChanged(transform: CssTransform?) = GtkWidgetLifecycleBridge.onTransformChanged(this, transform)
    override fun onNativeStyleSynced() = GtkWidgetLifecycleBridge.onStyleSynced(this)

    override fun setFocusedState(focused: Boolean) {
        super.setFocusedState(focused)
        GtkWidgetLifecycleBridge.syncNativeFocus(this, isFocusedState())
    }
}
