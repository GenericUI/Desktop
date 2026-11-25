@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.impl

import com.nexustools.genericui.desktop.widget.DesktopRangeSliderNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.gtk.widget.GtkCustomLayoutManager
import com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge
import com.nexustools.genericui.linux.LinuxGlibGioBindings
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction

/**
 * Retained GTK4 slider mounting a live `GtkScale` wired to `"value-changed"`.
 */
class GtkRangeSliderNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkScale"),
    classPrefix: String = "range"
) : DesktopRangeSliderNativeImpl<GtkWidgetHandle>(nativeWidget) {

    private var syncingFromModel = false
    private var signalRef: StableRef<GtkRangeSliderNativeImpl>? = null

    init {
        nativeWidget.ownerImpl = this
        nativeWidget.widgetMaterializer = { materializeScale() }
    }

    private fun materializeScale(): COpaquePointer? {
        nativeWidget.realGtkWidgetPtr?.let { return it }
        if (!Gtk4DynamicBindings.ensureGtkInitialized()) return null

        val minV = getMin()
        val maxV = getMax().coerceAtLeast(minV + 0.001)
        val stepV = getStep().coerceAtLeast(0.001)
        val scale = Gtk4DynamicBindings.gtkScaleNewWithRange(
            Gtk4DynamicBindings.GTK_ORIENTATION_HORIZONTAL,
            minV,
            maxV,
            stepV
        ) ?: return null

        syncingFromModel = true
        try {
            Gtk4DynamicBindings.gtkRangeSetValue(scale, getValue())
        } finally {
            syncingFromModel = false
        }

        signalRef?.dispose()
        val ref = StableRef.create(this)
        signalRef = ref
        LinuxGlibGioBindings.signalConnect(
            instance = scale,
            signalName = "value-changed",
            handler = scaleValueChangedCb.reinterpret(),
            userData = ref.asCPointer()
        )
        nativeWidget.primaryLeafPtr = scale
        GtkWidgetLifecycleBridge.attachControllersAndState(
            impl = this,
            rootPtr = scale,
            focusTargetPtr = scale,
            isTextInput = false,
            dispatchClickOnPrimaryRelease = false,
            attachClickGesture = true
        )
        return scale
    }

    override fun setRange(min: Double, max: Double, step: Double) {
        super.setRange(min, max, step)
        val ptr = nativeWidget.realGtkWidgetPtr ?: return
        syncingFromModel = true
        try {
            Gtk4DynamicBindings.gtkRangeSetRange(ptr, min, max.coerceAtLeast(min + 0.001))
        } finally {
            syncingFromModel = false
        }
    }

    override fun setValue(value: Double) {
        super.setValue(value)
        val ptr = nativeWidget.realGtkWidgetPtr ?: return
        syncingFromModel = true
        try {
            Gtk4DynamicBindings.gtkRangeSetValue(ptr, getValue())
        } finally {
            syncingFromModel = false
        }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val ptr = nativeWidget.realGtkWidgetPtr
        if (ptr != null) {
            val (w, h) = GtkCustomLayoutManager.measureGtkWidgetIntrinsic(ptr, maxWidth, maxHeight)
            if (w > 0 && h > 0) return Size(w.coerceAtLeast(120), h.coerceAtLeast(24))
        }
        return Size(160, 28)
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

    override fun dispose() {
        signalRef?.dispose()
        signalRef = null
        super.dispose()
    }

    companion object {
        private val scaleValueChangedCb = staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { scale, userData ->
            val impl = userData?.asStableRef<GtkRangeSliderNativeImpl>()?.get() ?: return@staticCFunction
            if (impl.syncingFromModel || scale == null) return@staticCFunction
            val newVal = Gtk4DynamicBindings.gtkRangeGetValue(scale)
            impl.beginDragFromFraction(
                ((newVal - impl.getMin()) / (impl.getMax() - impl.getMin()).coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
            )
            impl.endDragFromFraction(
                ((newVal - impl.getMin()) / (impl.getMax() - impl.getMin()).coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
            )
        }
    }
}
