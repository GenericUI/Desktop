@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.impl

import com.nexustools.genericui.desktop.widget.DesktopDividerNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.COpaquePointer

/**
 * Retained GTK4 separator widget mounting `GtkSeparator`.
 */
class GtkDividerNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkSeparator")
) : DesktopDividerNativeImpl<GtkWidgetHandle>(nativeWidget) {

    init {
        nativeWidget.ownerImpl = this
        nativeWidget.widgetMaterializer = { materializeDivider() }
    }

    private fun isVertical(): Boolean {
        val raw = (nativeWidget.attributes["orientation"] as? String)?.lowercase() ?: ""
        return raw == "vertical" || "vr" in nativeWidget.cachedAllClassTokens
    }

    private fun materializeDivider(): COpaquePointer? {
        nativeWidget.realGtkWidgetPtr?.let { return it }
        if (!Gtk4DynamicBindings.ensureGtkInitialized()) return null
        val orientation = if (isVertical()) {
            Gtk4DynamicBindings.GTK_ORIENTATION_VERTICAL
        } else {
            Gtk4DynamicBindings.GTK_ORIENTATION_HORIZONTAL
        }
        val sep = Gtk4DynamicBindings.gtkSeparatorNew(orientation) ?: return null
        nativeWidget.primaryLeafPtr = sep
        GtkWidgetLifecycleBridge.attachControllersAndState(
            impl = this,
            rootPtr = sep,
            focusTargetPtr = sep,
            isTextInput = false,
            dispatchClickOnPrimaryRelease = false,
            attachClickGesture = false
        )
        return sep
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size =
        if (isVertical()) Size(1, 24) else Size(64, 1)

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
