@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.impl

import com.nexustools.genericui.desktop.widget.DesktopProgressBarNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.gtk.widget.GtkCustomLayoutManager
import com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction

/**
 * Retained GTK4 progress indicator mounting a live `GtkProgressBar` with indeterminate pulse tick support.
 */
class GtkProgressBarNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkProgressBar"),
    classPrefix: String = "progress"
) : DesktopProgressBarNativeImpl<GtkWidgetHandle>(nativeWidget) {

    private var pulseTickId: UInt = 0u
    private var pulseRef: StableRef<GtkProgressBarNativeImpl>? = null

    init {
        nativeWidget.ownerImpl = this
        nativeWidget.widgetMaterializer = { materializeProgressBar() }
    }

    private fun materializeProgressBar(): COpaquePointer? {
        nativeWidget.realGtkWidgetPtr?.let { return it }
        if (!Gtk4DynamicBindings.ensureGtkInitialized()) return null
        val pb = Gtk4DynamicBindings.gtkProgressBarNew() ?: return null
        nativeWidget.primaryLeafPtr = pb
        GtkWidgetLifecycleBridge.attachControllersAndState(
            impl = this,
            rootPtr = pb,
            focusTargetPtr = pb,
            isTextInput = false,
            dispatchClickOnPrimaryRelease = false,
            attachClickGesture = false
        )
        syncProgressToLive(pb)
        return pb
    }

    private fun syncProgressToLive(pb: COpaquePointer? = nativeWidget.realGtkWidgetPtr) {
        pb ?: return
        val indet = (nativeWidget.attributes["indeterminate"] as? Boolean == true) ||
            (nativeWidget.attributes["busy"] as? Boolean == true)
        val lbl = nativeWidget.attributes["label"] as? String
        Gtk4DynamicBindings.gtkProgressBarSetText(pb, lbl)
        Gtk4DynamicBindings.gtkProgressBarSetShowText(pb, !lbl.isNullOrEmpty())
        if (indet) {
            Gtk4DynamicBindings.gtkProgressBarPulse(pb)
            if (pulseTickId == 0u) {
                pulseRef?.dispose()
                val ref = StableRef.create(this)
                pulseRef = ref
                pulseTickId = Gtk4DynamicBindings.gtkWidgetAddTickCallback(
                    widget = pb,
                    callback = pulseTickCb.reinterpret(),
                    userData = ref.asCPointer(),
                    destroyNotify = null
                )
            }
        } else {
            if (pulseTickId != 0u) {
                Gtk4DynamicBindings.gtkWidgetRemoveTickCallback(pb, pulseTickId)
                pulseTickId = 0u
                pulseRef?.dispose()
                pulseRef = null
            }
            Gtk4DynamicBindings.gtkProgressBarSetFraction(pb, getProgress().toDouble())
        }
    }

    override fun setProgress(progress: Float) {
        super.setProgress(progress)
        syncProgressToLive()
    }

    override fun setIndeterminate(indeterminate: Boolean) {
        super.setIndeterminate(indeterminate)
        syncProgressToLive()
    }

    override fun setBusy(busy: Boolean) {
        super.setBusy(busy)
        syncProgressToLive()
    }

    override fun setLabel(label: String?) {
        super.setLabel(label)
        syncProgressToLive()
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val ptr = nativeWidget.realGtkWidgetPtr
        if (ptr != null) {
            val (w, h) = GtkCustomLayoutManager.measureGtkWidgetIntrinsic(ptr, maxWidth, maxHeight)
            if (w > 0 && h > 0) return Size(w.coerceAtLeast(120), h.coerceAtLeast(12))
        }
        return Size(160, 16)
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
        val ptr = nativeWidget.realGtkWidgetPtr
        if (ptr != null && pulseTickId != 0u) {
            Gtk4DynamicBindings.gtkWidgetRemoveTickCallback(ptr, pulseTickId)
            pulseTickId = 0u
        }
        pulseRef?.dispose()
        pulseRef = null
        super.dispose()
    }

    companion object {
        private val pulseTickCb = staticCFunction<COpaquePointer?, COpaquePointer?, COpaquePointer?, Int> { pb, _, userData ->
            val impl = userData?.asStableRef<GtkProgressBarNativeImpl>()?.get() ?: return@staticCFunction 0
            if (pb == null || impl.isDisposed) return@staticCFunction 0
            val indet = (impl.nativeWidget.attributes["indeterminate"] as? Boolean == true) ||
                (impl.nativeWidget.attributes["busy"] as? Boolean == true)
            if (!indet) return@staticCFunction 0
            Gtk4DynamicBindings.gtkProgressBarPulse(pb)
            1
        }
    }
}
