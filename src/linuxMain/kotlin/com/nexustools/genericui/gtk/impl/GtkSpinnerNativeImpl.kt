@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.impl

import com.nexustools.genericui.desktop.widget.DesktopSpinnerNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.gtk.widget.GtkCustomLayoutManager
import com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.COpaquePointer

/**
 * Retained GTK4 spinner mounting Libadwaita `AdwSpinner` (or `GtkSpinner` fallback on older runtimes).
 */
class GtkSpinnerNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("AdwSpinner"),
    classPrefix: String = "spinner"
) : DesktopSpinnerNativeImpl<GtkWidgetHandle>(nativeWidget) {

    private var usingAdwSpinner = true

    init {
        nativeWidget.ownerImpl = this
        nativeWidget.widgetMaterializer = { materializeSpinner() }
    }

    private fun materializeSpinner(): COpaquePointer? {
        nativeWidget.realGtkWidgetPtr?.let { return it }
        if (!Gtk4DynamicBindings.ensureGtkInitialized()) return null
        val (sp, isAdw) = Gtk4DynamicBindings.gtkSpinnerOrAdwSpinnerNew()
        val ptr = sp ?: return null
        usingAdwSpinner = isAdw
        nativeWidget.updateActiveGtkClassName(if (isAdw) "AdwSpinner" else "GtkSpinner")
        if (!isAdw) {
            Gtk4DynamicBindings.gtkSpinnerSetSpinning(ptr, true)
        }
        nativeWidget.primaryLeafPtr = ptr
        GtkWidgetLifecycleBridge.attachControllersAndState(
            impl = this,
            rootPtr = ptr,
            focusTargetPtr = ptr,
            isTextInput = false,
            dispatchClickOnPrimaryRelease = false,
            attachClickGesture = false
        )
        return ptr
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val ptr = nativeWidget.realGtkWidgetPtr
        if (ptr != null) {
            val (w, h) = GtkCustomLayoutManager.measureGtkWidgetIntrinsic(ptr, maxWidth, maxHeight)
            if (w > 0 && h > 0) return Size(w.coerceAtLeast(16), h.coerceAtLeast(16))
        }
        val grow = nativeWidget.attributes["grow"] as? Boolean == true
        return if (grow) Size(28, 28) else Size(24, 24)
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
