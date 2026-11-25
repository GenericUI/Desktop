@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.impl

import com.nexustools.genericui.desktop.widget.DesktopImageNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.enums.ui.ObjectFit
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.gtk.widget.GtkCustomLayoutManager
import com.nexustools.genericui.gtk.widget.GtkSurfaceRenderHelper
import com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.linux.LinuxGlibGioBindings
import com.nexustools.genericui.style.color.ImageTintMode
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.COpaquePointer

/**
 * Retained GTK4 image surface mounting a live `GtkPicture` backed by `GdkMemoryTexture`, supporting
 * [ObjectFit], tinting, and 10-operation [CssFilter].
 */
class GtkImageNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkPicture"),
    classPrefix: String = "img"
) : DesktopImageNativeImpl<GtkWidgetHandle>(nativeWidget) {

    private var lastBuffer: ImageBuffer? = null

    init {
        nativeWidget.ownerImpl = this
        nativeWidget.widgetMaterializer = { materializePicture() }
    }

    private fun materializePicture(): COpaquePointer? {
        nativeWidget.realGtkWidgetPtr?.let { return it }
        if (!Gtk4DynamicBindings.ensureGtkInitialized()) return null
        val pic = Gtk4DynamicBindings.gtkPictureNewForPaintable(null) ?: return null
        Gtk4DynamicBindings.gtkPictureSetCanShrink(pic, true)
        applyContentFit(pic, objectFitState)
        nativeWidget.primaryLeafPtr = pic
        GtkWidgetLifecycleBridge.attachControllersAndState(
            impl = this,
            rootPtr = pic,
            focusTargetPtr = pic,
            isTextInput = false,
            dispatchClickOnPrimaryRelease = true,
            attachClickGesture = true
        )
        pushBufferToPicture(pic)
        return pic
    }

    private fun applyContentFit(pic: COpaquePointer, fit: ObjectFit) {
        val gtkFit = when (fit) {
            ObjectFit.FILL -> Gtk4DynamicBindings.GTK_CONTENT_FIT_FILL
            ObjectFit.CONTAIN -> Gtk4DynamicBindings.GTK_CONTENT_FIT_CONTAIN
            ObjectFit.COVER -> Gtk4DynamicBindings.GTK_CONTENT_FIT_COVER
            ObjectFit.SCALE_DOWN -> Gtk4DynamicBindings.GTK_CONTENT_FIT_SCALE_DOWN
            ObjectFit.NONE -> Gtk4DynamicBindings.GTK_CONTENT_FIT_CONTAIN
        }
        Gtk4DynamicBindings.gtkPictureSetContentFit(pic, gtkFit)
    }

    private fun pushBufferToPicture(pic: COpaquePointer? = nativeWidget.realGtkWidgetPtr) {
        pic ?: return
        val buf = lastBuffer
        if (buf == null || buf.width <= 0 || buf.height <= 0) {
            Gtk4DynamicBindings.gtkPictureSetPaintable(pic, null)
            return
        }
        val rgbaBuf = buf.toRgba8888()
        val w = rgbaBuf.width
        val h = rgbaBuf.height
        val words = IntArray(w * h)
        val tint = tintColorState
        val tintMode = tintModeState

        for (i in 0 until w * h) {
            val s = i * 4
            var r = rgbaBuf.bytes[s].toInt() and 0xFF
            var g = rgbaBuf.bytes[s + 1].toInt() and 0xFF
            var b = rgbaBuf.bytes[s + 2].toInt() and 0xFF
            val a = rgbaBuf.bytes[s + 3].toInt() and 0xFF

            if (tint != null && tintMode != ImageTintMode.NONE && a > 0) {
                r = ((r * tint.r + 127) / 255).coerceIn(0, 255)
                g = ((g * tint.g + 127) / 255).coerceIn(0, 255)
                b = ((b * tint.b + 127) / 255).coerceIn(0, 255)
            }

            val pr = (r * a + 127) / 255
            val pg = (g * a + 127) / 255
            val pb = (b * a + 127) / 255
            words[i] = (a shl 24) or (pr shl 16) or (pg shl 8) or pb
        }

        cssFilterState?.let { f ->
            GtkSurfaceRenderHelper.applyCssFilterToPremulBgraBuffer(words, w, h, f)
        }

        val premulBgraBytes = ByteArray(w * h * 4)
        for (i in 0 until w * h) {
            val px = words[i]
            val o = i * 4
            premulBgraBytes[o] = (px and 0xFF).toByte()
            premulBgraBytes[o + 1] = ((px ushr 8) and 0xFF).toByte()
            premulBgraBytes[o + 2] = ((px ushr 16) and 0xFF).toByte()
            premulBgraBytes[o + 3] = ((px ushr 24) and 0xFF).toByte()
        }

        val texture = Gtk4DynamicBindings.gdkMemoryTextureNewBgraPremul(w, h, premulBgraBytes)
        if (texture != null) {
            Gtk4DynamicBindings.gtkPictureSetPaintable(pic, texture)
            LinuxGlibGioBindings.objectUnref(texture)
        }
    }

    override fun setImageBuffer(buffer: ImageBuffer?) {
        super.setImageBuffer(buffer)
        lastBuffer = buffer
        pushBufferToPicture()
        GtkWidgetLifecycleBridge.requestParentRelayout(this)
    }

    override fun setObjectFit(objectFit: ObjectFit) {
        super.setObjectFit(objectFit)
        nativeWidget.realGtkWidgetPtr?.let { applyContentFit(it, objectFit) }
    }

    override fun setTintColor(color: RgbaColor?) {
        super.setTintColor(color)
        pushBufferToPicture()
    }

    override fun setTintMode(mode: ImageTintMode) {
        super.setTintMode(mode)
        pushBufferToPicture()
    }

    override fun setCssFilter(filter: CssFilter?) {
        super.setCssFilter(filter)
        pushBufferToPicture()
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val buf = lastBuffer
        if (buf != null && buf.width > 0 && buf.height > 0) {
            return Size(buf.width, buf.height)
        }
        val ptr = nativeWidget.realGtkWidgetPtr
        if (ptr != null) {
            val (w, h) = GtkCustomLayoutManager.measureGtkWidgetIntrinsic(ptr, maxWidth, maxHeight)
            if (w > 0 && h > 0) return Size(w, h)
        }
        return Size(64, 64)
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
