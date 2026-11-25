@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.impl

import com.nexustools.genericui.desktop.widget.DesktopMarkdownViewNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.gtk.widget.GtkCustomLayoutManager
import com.nexustools.genericui.gtk.widget.GtkSurfaceRenderHelper
import com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.COpaquePointer
import kotlin.math.ceil
import kotlin.math.max

/**
 * Retained GTK4 read-only `GtkTextView` displaying Markdown document text cleanly inside a scrollable viewport.
 */
class GtkMarkdownViewNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkTextView_Markdown"),
    classPrefix: String = "markdown"
) : DesktopMarkdownViewNativeImpl<GtkWidgetHandle>(nativeWidget) {

    init {
        nativeWidget.ownerImpl = this
        nativeWidget.widgetMaterializer = { materializeMarkdownView() }
    }

    private fun materializeMarkdownView(): COpaquePointer? {
        nativeWidget.realGtkWidgetPtr?.let { return it }
        if (!Gtk4DynamicBindings.ensureGtkInitialized()) return null

        val sw = Gtk4DynamicBindings.gtkScrolledWindowNew() ?: return null
        val tv = Gtk4DynamicBindings.gtkTextViewNew() ?: return null
        Gtk4DynamicBindings.gtkScrolledWindowSetPolicy(
            sw,
            Gtk4DynamicBindings.GTK_POLICY_NEVER,
            Gtk4DynamicBindings.GTK_POLICY_AUTOMATIC
        )
        Gtk4DynamicBindings.gtkTextViewSetEditable(tv, false)
        Gtk4DynamicBindings.gtkTextViewSetWrapMode(tv, Gtk4DynamicBindings.GTK_WRAP_WORD_CHAR)
        Gtk4DynamicBindings.gtkScrolledWindowSetChild(sw, tv)

        Gtk4DynamicBindings.gtkTextViewGetBuffer(tv)?.let { buf ->
            Gtk4DynamicBindings.gtkTextBufferSetText(buf, markdownSource)
        }

        nativeWidget.scrolledWindowPtr = sw
        nativeWidget.primaryLeafPtr = tv
        GtkWidgetLifecycleBridge.attachControllersAndState(
            impl = this,
            rootPtr = sw,
            focusTargetPtr = tv,
            isTextInput = false,
            dispatchClickOnPrimaryRelease = true,
            attachClickGesture = true
        )
        return sw
    }

    override fun setMarkdown(markdown: String) {
        super.setMarkdown(markdown)
        val tv = nativeWidget.primaryLeafPtr ?: return
        Gtk4DynamicBindings.gtkTextViewGetBuffer(tv)?.let { buf ->
            Gtk4DynamicBindings.gtkTextBufferSetText(buf, markdown)
        }
        GtkWidgetLifecycleBridge.requestParentRelayout(this)
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val ptr = nativeWidget.realGtkWidgetPtr
        if (ptr != null) {
            val (w, h) = GtkCustomLayoutManager.measureGtkWidgetIntrinsic(ptr, maxWidth, maxHeight)
            if (w > 0 && h > 0) return Size(max(120, w), max(48, h))
        }
        val (tw, th) = GtkSurfaceRenderHelper.measureTextSize(
            text = markdownSource.ifEmpty { " " },
            fontSizePx = 14f,
            maxWidthPx = maxWidth?.toInt()
        )
        return Size(max(120, tw + 16), max(48, th + 16))
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
