@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.impl

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.layout.DesktopContainerLayoutController
import com.nexustools.genericui.desktop.widget.DesktopScrollViewNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.gtk.widget.GtkCustomLayoutManager
import com.nexustools.genericui.gtk.widget.GtkRetainedContainerSupport
import com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge
import com.nexustools.genericui.linux.LinuxGlibGioBindings
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlin.math.roundToInt

/**
 * Retained GTK4 scrollable container mounting `GtkScrolledWindow` over an inner `GtkCustomLayout`
 * content box and synchronizing native `GtkAdjustment` scroll positions.
 */
class GtkScrollViewNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkScrolledWindow")
) : DesktopScrollViewNativeImpl<GtkWidgetHandle>(nativeWidget) {

    private var signalRef: StableRef<GtkScrollViewNativeImpl>? = null
    private var vAdjPtr: COpaquePointer? = null
    private var hAdjPtr: COpaquePointer? = null
    private var syncingScroll = false

    init {
        nativeWidget.ownerImpl = this
        layoutController = DesktopContainerLayoutController(
            host = this,
            applyScaleToBounds = false
        )
        nativeWidget.customLayoutController = layoutController
        nativeWidget.widgetMaterializer = { materializeScrollView() }
    }

    private fun materializeScrollView(): COpaquePointer? {
        nativeWidget.realGtkWidgetPtr?.let { return it }
        if (!Gtk4DynamicBindings.ensureGtkInitialized()) return null

        val sw = Gtk4DynamicBindings.gtkScrolledWindowNew() ?: return null
        val contentBox = Gtk4DynamicBindings.gtkBoxNew(Gtk4DynamicBindings.GTK_ORIENTATION_VERTICAL, 0) ?: return null
        val controller = layoutController ?: return null

        Gtk4DynamicBindings.gtkScrolledWindowSetPolicy(
            sw,
            Gtk4DynamicBindings.GTK_POLICY_AUTOMATIC,
            Gtk4DynamicBindings.GTK_POLICY_AUTOMATIC
        )
        GtkCustomLayoutManager.attachToContainer(this, contentBox, controller, null)
        Gtk4DynamicBindings.gtkScrolledWindowSetChild(sw, contentBox)

        nativeWidget.scrolledWindowPtr = sw
        nativeWidget.containerLayoutBoxPtr = contentBox

        signalRef?.dispose()
        val ref = StableRef.create(this)
        signalRef = ref

        vAdjPtr = Gtk4DynamicBindings.gtkScrolledWindowGetVadjustment(sw)?.also { vAdj ->
            LinuxGlibGioBindings.signalConnect(vAdj, "value-changed", adjChangedCb.reinterpret(), ref.asCPointer())
        }
        hAdjPtr = Gtk4DynamicBindings.gtkScrolledWindowGetHadjustment(sw)?.also { hAdj ->
            LinuxGlibGioBindings.signalConnect(hAdj, "value-changed", adjChangedCb.reinterpret(), ref.asCPointer())
        }

        GtkWidgetLifecycleBridge.attachControllersAndState(
            impl = this,
            rootPtr = sw,
            focusTargetPtr = sw,
            isTextInput = false,
            dispatchClickOnPrimaryRelease = false,
            attachClickGesture = true
        )

        for (child in getLogicalChildren()) {
            GtkRetainedContainerSupport.attachChildWidgetIfRealized(this, child)
        }
        return sw
    }

    override fun scrollTo(x: Int, y: Int) {
        super.scrollTo(x, y)
        syncingScroll = true
        try {
            hAdjPtr?.let { Gtk4DynamicBindings.gtkAdjustmentSetValue(it, x.toDouble()) }
            vAdjPtr?.let { Gtk4DynamicBindings.gtkAdjustmentSetValue(it, y.toDouble()) }
        } finally {
            syncingScroll = false
        }
    }

    override fun scrollToBottom() {
        super.scrollToBottom()
        vAdjPtr?.let { adj ->
            val upper = Gtk4DynamicBindings.gtkAdjustmentGetUpper(adj)
            val page = Gtk4DynamicBindings.gtkAdjustmentGetPageSize(adj)
            val target = (upper - page).coerceAtLeast(0.0)
            syncingScroll = true
            try {
                Gtk4DynamicBindings.gtkAdjustmentSetValue(adj, target)
                updateScrollPositionFromHost(currentScrollX(), target.roundToInt())
            } finally {
                syncingScroll = false
            }
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

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size =
        Size(maxWidth ?: 300f, maxHeight ?: 240f)

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
        private val adjChangedCb = staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { _, userData ->
            val impl = userData?.asStableRef<GtkScrollViewNativeImpl>()?.get() ?: return@staticCFunction
            if (impl.syncingScroll) return@staticCFunction
            val sx = impl.hAdjPtr?.let { Gtk4DynamicBindings.gtkAdjustmentGetValue(it).roundToInt() } ?: impl.currentScrollX()
            val sy = impl.vAdjPtr?.let { Gtk4DynamicBindings.gtkAdjustmentGetValue(it).roundToInt() } ?: impl.currentScrollY()
            impl.updateScrollPositionFromHost(sx, sy)
        }
    }
}
