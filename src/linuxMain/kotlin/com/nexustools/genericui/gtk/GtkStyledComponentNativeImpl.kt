@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk

import com.nexustools.genericui.desktop.widget.DesktopStyledComponentNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.gtk.widget.GtkInteractiveSurfaceWidget
import com.nexustools.genericui.gtk.widget.GtkRetainedContainerSupport
import com.nexustools.genericui.gtk.widget.GtkSurfaceRenderHelper
import com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge
import com.nexustools.genericui.linux.style.LinuxBackdropFilterController
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.COpaquePointer

/**
 * Retained GTK4 custom-painted `GtkDrawingArea` backing [com.nexustools.genericui.component.layout.StyledComponent]
 * with rounded backgrounds, borders, shadows, 10-op `backdrop-filter`, and foreground `CssFilter`.
 */
open class GtkStyledComponentNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkDrawingArea_StyledComponent"),
    classPrefix: String = "styled-component",
    tagName: String = "genericui:styled-component"
) : DesktopStyledComponentNativeImpl<GtkWidgetHandle>(nativeWidget, classPrefix, tagName) {

    init {
        nativeWidget.ownerImpl = this
        nativeWidget.attributes["useSystemControlAppearance"] = "false"
        nativeWidget.hasCustomSurfaceBackground = true
        nativeWidget.onDrawSurfaceCairo = { cr, width, height -> paintStyledComponent(cr, width, height) }
        nativeWidget.widgetMaterializer = { materializeStyledComponent() }
    }

    private fun materializeStyledComponent(): COpaquePointer? {
        nativeWidget.realGtkWidgetPtr?.let { return it }
        if (!Gtk4DynamicBindings.ensureGtkInitialized()) return null
        val da = GtkWidgetLifecycleBridge.createDrawingSurfaceForPainter(nativeWidget) { cr, w, h ->
            paintStyledComponent(cr, w, h)
        } ?: return null
        Gtk4DynamicBindings.gtkWidgetSetFocusable(da, false)
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

    private fun paintStyledComponent(cr: COpaquePointer, width: Int, height: Int) {
        if (nativeWidget.interactiveSurfaceSpec != null) {
            if (GtkInteractiveSurfaceWidget.paintInteractiveSurface(nativeWidget, cr, width, height)) {
                return
            }
        }
        val dark = GtkRetainedContainerSupport.resolveDarkTheme()
        val visuals = resolveCurrentVisuals()
        val computed = getComputedStyle()
        val paintSpec = GtkSurfaceRenderHelper.fromStyledVisualSpec(
            spec = visuals,
            cornerRadii = computed?.borderRadius,
            backdropFilter = nativeWidget.attributes["backdropFilter"] as? com.nexustools.genericui.style.css.CssFilter ?: visuals.backdropFilter,
            foregroundFilter = nativeWidget.attributes["cssFilter"] as? com.nexustools.genericui.style.css.CssFilter ?: visuals.filter,
            opacity = getOpacity(),
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
}
