@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.impl

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.dock.DesktopDockAreaNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.enums.css.CssDisplay
import com.nexustools.genericui.enums.dock.DockPosition
import com.nexustools.genericui.enums.bootstrap.FlexDirection
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.gtk.widget.GtkRetainedContainerSupport
import com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge
import com.nexustools.genericui.style.css.CssTransform

/**
 * Retained GTK4 dock edge area mounting a `GtkBox + GtkCustomLayout` region collapsible on toggle.
 */
class GtkDockAreaNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkBox_DockArea")
) : DesktopDockAreaNativeImpl<GtkWidgetHandle>(nativeWidget) {

    init {
        layoutController = GtkRetainedContainerSupport.initializeContainer(
            host = this,
            defaultDisplay = CssDisplay.FLEX,
            defaultFlexDirection = FlexDirection.COLUMN,
            defaultGapPx = 4
        )
    }

    override fun setPosition(position: DockPosition) {
        super.setPosition(position)
        GtkWidgetLifecycleBridge.requestParentRelayout(this)
    }

    override fun setCollapsed(collapsed: Boolean) {
        super.setCollapsed(collapsed)
        nativeWidget.realGtkWidgetPtr?.let {
            Gtk4DynamicBindings.gtkWidgetSetVisible(it, !collapsed && nativeWidget.visible)
        }
        GtkWidgetLifecycleBridge.requestParentRelayout(this)
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
    override fun onNativeStyleSynced() = GtkWidgetLifecycleBridge.onStyleSynced(this)
}
