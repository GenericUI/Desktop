@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.widget.DesktopStyledContainerNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.gtk.widget.GtkInteractiveSurfaceWidget
import com.nexustools.genericui.gtk.widget.GtkRetainedContainerSupport
import com.nexustools.genericui.gtk.widget.GtkSurfaceRenderHelper
import com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge
import com.nexustools.genericui.linux.style.LinuxBackdropFilterController
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.style.css.StyledVisualSpec
import kotlinx.cinterop.COpaquePointer

/**
 * Retained GTK4 custom-styled container rendering gradients, CSS borders (solid/dashed/dotted/double),
 * box shadows, all 10 `backdrop-filter` ops via [LinuxBackdropFilterController], and GSK/Cairo
 * foreground filters behind a `GtkCustomLayout` child container (`GtkOverlay`).
 */
open class GtkStyledContainerNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkOverlay_StyledContainer"),
    classPrefix: String = "styled-container",
    tagName: String = "div"
) : DesktopStyledContainerNativeImpl<GtkWidgetHandle>(nativeWidget, classPrefix, tagName) {

    init {
        nativeWidget.attributes["tagName"] = tagName
        nativeWidget.attributes["useSystemControlAppearance"] = "false"
        nativeWidget.hasCustomSurfaceBackground = true
        val isLayered = GtkRetainedContainerSupport.isLayeredHostTagOrClass(
            classPrefix = classPrefix,
            tagName = tagName,
            classes = nativeWidget.cachedAllClassTokens
        )
        layoutController = GtkRetainedContainerSupport.initializeContainer(
            host = this,
            isLayeredHost = isLayered,
            useSurfaceOverlay = true,
            surfacePainter = { cr, width, height -> paintStyledContainerSurface(cr, width, height) }
        )
    }

    private fun paintStyledContainerSurface(cr: COpaquePointer, width: Int, height: Int) {
        if (nativeWidget.interactiveSurfaceSpec != null) {
            if (GtkInteractiveSurfaceWidget.paintInteractiveSurface(nativeWidget, cr, width, height)) {
                return
            }
        }
        val dark = GtkRetainedContainerSupport.resolveDarkTheme()
        val visuals = resolveCurrentVisuals()
        val computed = getComputedStyle()
        GtkSurfaceRenderHelper.paintStyledWidgetSurface(
            impl = this,
            cr = cr,
            width = width,
            height = height,
            visuals = visuals,
            cornerRadii = computed?.borderRadius,
            opacity = visuals.opacity ?: getOpacity(),
            focused = isFocusedState(),
            darkTheme = dark
        )
    }

    private fun resolveCurrentVisuals(): StyledVisualSpec {
        var effective = getStyledVisuals()
        val computed = getComputedStyle()
        if (!isEnabled()) {
            val dis = computed?.disabledStyle?.let { StyledVisualSpec.fromComputedStyle(it) }
            return effective.mergeWith(dis)
        }
        if (isHoveredState()) {
            val hov = computed?.hoverStyle?.let { StyledVisualSpec.fromComputedStyle(it) }
            effective = effective.mergeWith(hov)
        }
        if (isFocusedState()) {
            val foc = computed?.focusStyle?.let { StyledVisualSpec.fromComputedStyle(it) }
            effective = effective.mergeWith(foc)
        }
        if (isActivePressedState()) {
            val act = computed?.activeStyle?.let { StyledVisualSpec.fromComputedStyle(it) }
            effective = effective.mergeWith(act)
        }
        return effective
    }

    override fun setAttribute(name: String, value: String?) {
        super.setAttribute(name, value)
        GtkWidgetLifecycleBridge.handleStyledBackdropAttribute(this, name, value)
    }

    override fun setHoveredState(hovered: Boolean) {
        val prev = isHoveredState()
        super.setHoveredState(hovered)
        if (prev != isHoveredState()) {
            syncResolvedVisualsToWidget(visualOnly = true)
            nativeWidget.backgroundSurfacePtr?.let { Gtk4DynamicBindings.gtkWidgetQueueDraw(it) }
            nativeWidget.realGtkWidgetPtr?.let { Gtk4DynamicBindings.gtkWidgetQueueDraw(it) }
        }
    }

    override fun setFocusedState(focused: Boolean) {
        val prev = isFocusedState()
        super.setFocusedState(focused)
        if (prev != isFocusedState()) {
            syncResolvedVisualsToWidget(visualOnly = true)
            nativeWidget.backgroundSurfacePtr?.let { Gtk4DynamicBindings.gtkWidgetQueueDraw(it) }
            nativeWidget.realGtkWidgetPtr?.let { Gtk4DynamicBindings.gtkWidgetQueueDraw(it) }
        }
    }

    override fun setActivePressedState(active: Boolean) {
        val prev = isActivePressedState()
        super.setActivePressedState(active)
        if (prev != isActivePressedState()) {
            syncResolvedVisualsToWidget(visualOnly = true)
            nativeWidget.backgroundSurfacePtr?.let { Gtk4DynamicBindings.gtkWidgetQueueDraw(it) }
            nativeWidget.realGtkWidgetPtr?.let { Gtk4DynamicBindings.gtkWidgetQueueDraw(it) }
        }
    }

    override fun setEnabled(enabled: Boolean) {
        val prev = isEnabled()
        super.setEnabled(enabled)
        if (prev != isEnabled()) {
            syncResolvedVisualsToWidget(visualOnly = true)
            nativeWidget.backgroundSurfacePtr?.let { Gtk4DynamicBindings.gtkWidgetQueueDraw(it) }
            nativeWidget.realGtkWidgetPtr?.let { Gtk4DynamicBindings.gtkWidgetQueueDraw(it) }
        }
    }

    override fun onChildAddedNative(childImpl: NativeImpl<*>, index: Int) {
        GtkRetainedContainerSupport.attachChildWidgetIfRealized(this, childImpl)
    }

    override fun onChildRemovedNative(childImpl: NativeImpl<*>) {
        GtkRetainedContainerSupport.detachChildWidget(this, childImpl)
    }

    override fun onChildrenClearedNative() {
        GtkRetainedContainerSupport.clearChildWidgets(this)
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

    override fun onNativeStyleSynced() {
        GtkRetainedContainerSupport.syncOverflowPolicy(this)
        GtkWidgetLifecycleBridge.onStyleSynced(this)
    }
}
