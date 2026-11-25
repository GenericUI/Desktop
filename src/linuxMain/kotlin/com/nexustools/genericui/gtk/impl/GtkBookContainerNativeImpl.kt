@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.impl

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.layout.DesktopContainerLayoutController
import com.nexustools.genericui.desktop.widget.DesktopBookContainerNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.enums.document.PrintPageOrientation
import com.nexustools.genericui.enums.document.PrintPageSize
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.gtk.widget.GtkBookContainerWidget
import com.nexustools.genericui.gtk.widget.GtkCustomLayoutManager
import com.nexustools.genericui.gtk.widget.GtkRetainedContainerSupport
import com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.COpaquePointer

/**
 * Retained GTK4 document/book container mounting `GtkScrolledWindow` around an overlay print-sheet
 * canvas ([GtkBookContainerWidget]) and `GtkCustomLayout` child pages.
 */
class GtkBookContainerNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkBox_BookPrintPreview")
) : DesktopBookContainerNativeImpl<GtkWidgetHandle>(nativeWidget) {

    private var bookTitle: String = "Document"

    init {
        nativeWidget.ownerImpl = this
        layoutController = DesktopContainerLayoutController(host = this, applyScaleToBounds = false)
        nativeWidget.customLayoutController = layoutController
        nativeWidget.hasCustomSurfaceBackground = true
        nativeWidget.onDrawSurfaceCairo = { cr, w, h ->
            GtkBookContainerWidget.paintPageSheet(
                cr = cr,
                width = w,
                height = h,
                darkTheme = GtkRetainedContainerSupport.resolveDarkTheme(),
                documentTitle = bookTitle,
                opacity = getOpacity()
            )
        }
        nativeWidget.widgetMaterializer = { materializeBookContainer() }
    }

    private fun materializeBookContainer(): COpaquePointer? {
        nativeWidget.realGtkWidgetPtr?.let { return it }
        if (!Gtk4DynamicBindings.ensureGtkInitialized()) return null

        val sw = Gtk4DynamicBindings.gtkScrolledWindowNew() ?: return null
        val overlay = Gtk4DynamicBindings.gtkOverlayNew() ?: return null
        val bgDa = GtkWidgetLifecycleBridge.createDrawingSurfaceForPainter(nativeWidget) { cr, w, h ->
            GtkBookContainerWidget.paintPageSheet(
                cr = cr,
                width = w,
                height = h,
                darkTheme = GtkRetainedContainerSupport.resolveDarkTheme(),
                documentTitle = bookTitle,
                opacity = getOpacity()
            )
        }
        val contentBox = Gtk4DynamicBindings.gtkBoxNew(Gtk4DynamicBindings.GTK_ORIENTATION_VERTICAL, 0) ?: return null
        val controller = layoutController ?: return null

        if (bgDa != null) {
            Gtk4DynamicBindings.gtkWidgetSetCanTarget(bgDa, false)
            Gtk4DynamicBindings.gtkOverlaySetChild(overlay, bgDa)
            nativeWidget.backgroundSurfacePtr = bgDa
        }
        Gtk4DynamicBindings.gtkOverlayAddOverlay(overlay, contentBox)
        GtkCustomLayoutManager.attachToContainer(this, contentBox, controller, null)
        Gtk4DynamicBindings.gtkScrolledWindowSetPolicy(
            sw,
            Gtk4DynamicBindings.GTK_POLICY_AUTOMATIC,
            Gtk4DynamicBindings.GTK_POLICY_AUTOMATIC
        )
        Gtk4DynamicBindings.gtkScrolledWindowSetChild(sw, overlay)

        nativeWidget.scrolledWindowPtr = sw
        nativeWidget.containerLayoutBoxPtr = contentBox
        GtkWidgetLifecycleBridge.attachControllersAndState(
            impl = this,
            rootPtr = sw,
            focusTargetPtr = contentBox,
            isTextInput = false,
            dispatchClickOnPrimaryRelease = false,
            attachClickGesture = true
        )
        for (child in getLogicalChildren()) {
            GtkRetainedContainerSupport.attachChildWidgetIfRealized(this, child)
        }
        return sw
    }

    override fun setDocumentTitle(title: String) {
        super.setDocumentTitle(title)
        bookTitle = title
        nativeWidget.backgroundSurfacePtr?.let { Gtk4DynamicBindings.gtkWidgetQueueDraw(it) }
    }

    override fun setPageSize(
        pageSize: PrintPageSize,
        orientation: PrintPageOrientation,
        marginMm: Float
    ) {
        super.setPageSize(pageSize, orientation, marginMm)
        nativeWidget.backgroundSurfacePtr?.let { Gtk4DynamicBindings.gtkWidgetQueueDraw(it) }
        GtkCustomLayoutManager.requestRelayout(nativeWidget.containerLayoutBoxPtr)
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
