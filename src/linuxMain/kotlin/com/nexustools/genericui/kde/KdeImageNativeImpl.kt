@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.desktop.widget.DesktopImageNativeImpl
import com.nexustools.genericui.enums.ui.ObjectFit
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.linux.style.LinuxBackdropFilterController
import com.nexustools.genericui.style.color.ImageTintMode
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Retained KDE image display widget (`KdeImageWidget`) painting decoded [ImageBuffer]s cleanly
 * via Qt 6 `QPainter` (`Format_ARGB32_Premultiplied`), honoring [ObjectFit], tinting, antialiasing,
 * foreground [CssFilter], and fallback text.
 */
open class KdeImageNativeImpl(
    nativeWidget: KdeWidgetHandle = KdeWidgetHandle("KdeImageWidget")
) : DesktopImageNativeImpl<KdeWidgetHandle>(nativeWidget) {

    init {
        KdeDynamicBindings.ensureQApplication()
        val ptr = KdeDynamicBindings.createQWidget(null, 0)
        nativeWidget.attachToQWidget(ptr, "KdeImageWidget", customPainted = true)
        KdePainterSupport.attachStandardInputHooks(
            widgetPtr = ptr,
            impl = this,
            hookMask = KdeVTableBridge.HOOK_PAINT or KdeVTableBridge.HOOK_RESIZE or KdeVTableBridge.HOOK_SIZE_HINT,
            dispatchLeftClickOnRelease = true,
            onPaint = { _, painter, w, h -> paintImage(painter, w, h) },
            onSizeHint = { _ ->
                val sz = measureIntrinsicSize(null, null)
                sz.width.coerceAtLeast(16) to sz.height.coerceAtLeast(16)
            }
        )
    }

    private fun paintImage(painter: COpaquePointer, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val buf = nativeWidget.attributes["imageBuffer"] as? ImageBuffer
        if (buf == null) {
            val fb = fallbackTextState
            if (!fb.isNullOrEmpty()) {
                KdeDynamicBindings.painterDrawText(
                    painter,
                    fb,
                    0.0,
                    0.0,
                    w.toDouble(),
                    h.toDouble(),
                    color = getEffectiveTextColor() ?: RgbaColor(120, 126, 134),
                    fontSizePx = 12f,
                    alignmentFlags = KdeDynamicBindings.ALIGN_CENTER
                )
            }
            return
        }

        val iw = buf.width.coerceAtLeast(1)
        val ih = buf.height.coerceAtLeast(1)
        val pixels = IntArray(iw * ih)
        val tint = tintColorState
        val mode = tintModeState
        val rgba = buf.asRgba8888()
        val srcBytes = rgba.bytes
        for (i in pixels.indices) {
            val off = i * 4
            var r = srcBytes[off].toInt() and 0xFF
            var g = srcBytes[off + 1].toInt() and 0xFF
            var b = srcBytes[off + 2].toInt() and 0xFF
            val a = srcBytes[off + 3].toInt() and 0xFF
            if (tint != null && mode != ImageTintMode.NONE && a > 0) {
                if (mode == ImageTintMode.SRC_IN) {
                    r = tint.r
                    g = tint.g
                    b = tint.b
                } else {
                    r = (r * tint.r) / 255
                    g = (g * tint.g) / 255
                    b = (b * tint.b) / 255
                }
            }
            val pmR = if (a == 255) r else (r * a + 127) / 255
            val pmG = if (a == 255) g else (g * a + 127) / 255
            val pmB = if (a == 255) b else (b * a + 127) / 255
            pixels[i] = (a shl 24) or (pmR shl 16) or (pmG shl 8) or pmB
        }

        val filter = cssFilterState
        if (filter != null && LinuxBackdropFilterController.shared.hasActiveEffects(filter)) {
            LinuxBackdropFilterController.shared.applyToPremulBgraBuffer(
                filter = filter,
                pixels = pixels,
                strideWords = iw,
                bufferWidth = iw,
                bufferHeight = ih,
                regionX = 0,
                regionY = 0,
                regionWidth = iw,
                regionHeight = ih
            )
        }

        val qImg = KdeDynamicBindings.createQImage(iw, ih, KdeDynamicBindings.IMAGE_FORMAT_ARGB32_PREMULTIPLIED)
        try {
            val bits = KdeDynamicBindings.qImageBits(qImg)?.reinterpret<IntVar>()
            if (bits != null) {
                for (i in pixels.indices) bits[i] = pixels[i]
            }

            val dstX: Double
            val dstY: Double
            val dstW: Double
            val dstH: Double
            val sx: Double
            val sy: Double
            val sw: Double
            val sh: Double

            val wf = w.toDouble()
            val hf = h.toDouble()
            val iwf = iw.toDouble()
            val ihf = ih.toDouble()

            when (objectFitState) {
                ObjectFit.FILL -> {
                    dstX = 0.0; dstY = 0.0; dstW = wf; dstH = hf
                    sx = 0.0; sy = 0.0; sw = iwf; sh = ihf
                }
                ObjectFit.COVER -> {
                    val scale = max(wf / iwf, hf / ihf)
                    val cropW = wf / scale
                    val cropH = hf / scale
                    sx = ((iwf - cropW) / 2.0).coerceAtLeast(0.0)
                    sy = ((ihf - cropH) / 2.0).coerceAtLeast(0.0)
                    sw = min(iwf, cropW)
                    sh = min(ihf, cropH)
                    dstX = 0.0; dstY = 0.0; dstW = wf; dstH = hf
                }
                ObjectFit.NONE -> {
                    dstX = (wf - iwf) / 2.0
                    dstY = (hf - ihf) / 2.0
                    dstW = iwf; dstH = ihf
                    sx = 0.0; sy = 0.0; sw = iwf; sh = ihf
                }
                ObjectFit.SCALE_DOWN -> {
                    val scale = min(1.0, min(wf / iwf, hf / ihf))
                    dstW = iwf * scale; dstH = ihf * scale
                    dstX = (wf - dstW) / 2.0; dstY = (hf - dstH) / 2.0
                    sx = 0.0; sy = 0.0; sw = iwf; sh = ihf
                }
                else -> {
                    // ObjectFit.CONTAIN
                    val scale = min(wf / iwf, hf / ihf)
                    dstW = iwf * scale; dstH = ihf * scale
                    dstX = (wf - dstW) / 2.0; dstY = (hf - dstH) / 2.0
                    sx = 0.0; sy = 0.0; sw = iwf; sh = ihf
                }
            }

            KdeDynamicBindings.painterSave(painter)
            if (getOpacity() < 1f) KdeDynamicBindings.painterSetOpacity(painter, getOpacity())
            KdeDynamicBindings.painterSetClipRect(painter, 0.0, 0.0, wf, hf, KdeDynamicBindings.CLIP_INTERSECT)
            KdeDynamicBindings.painterDrawImageRect(painter, dstX, dstY, dstW, dstH, qImg, sx, sy, sw, sh)
            KdeDynamicBindings.painterRestore(painter)
        } finally {
            KdeDynamicBindings.destroyQImage(qImg)
        }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val buf = nativeWidget.attributes["imageBuffer"] as? ImageBuffer
        if (buf != null) {
            val w = buf.width.toFloat().let { if (maxWidth != null) minOf(it, maxWidth) else it }
            val h = buf.height.toFloat().let { if (maxHeight != null) minOf(it, maxHeight) else it }
            return Size(w.coerceAtLeast(16f), h.coerceAtLeast(16f))
        }
        return super.measureIntrinsicSize(maxWidth, maxHeight)
    }

    override fun requestRepaintNative() { nativeWidget.requestRepaint() }
    override fun onNativeBoundsChanged(rect: Rect) { nativeWidget.applyBoundsToNative(rect); nativeWidget.requestRepaint() }
    override fun onNativeVisibilityChanged(visible: Boolean) { nativeWidget.syncVisibilityToNative(visible) }
    override fun onNativeEnabledChanged(enabled: Boolean) { nativeWidget.qWidgetPtr?.let { KdeDynamicBindings.setWidgetEnabled(it, enabled) } }
    override fun onNativeOpacityChanged(opacity: Float) { nativeWidget.requestRepaint() }
    override fun onNativeTooltipChanged(tooltip: String?) {
        KdePainterSupport.applyCommonWidgetProperties(nativeWidget, customCursorState, nativeWidget.isSelfPointerEventsNone(), tooltip, getOpacity())
    }
    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        KdePainterSupport.applyCommonWidgetProperties(nativeWidget, cursor, nativeWidget.isSelfPointerEventsNone(), nativeWidget.tooltipText, getOpacity())
    }
    override fun onNativePointerEventsChanged(pointerEventsNone: Boolean) {
        KdePainterSupport.applyCommonWidgetProperties(nativeWidget, customCursorState, pointerEventsNone, nativeWidget.tooltipText, getOpacity())
    }
    override fun onNativeTransformChanged(transform: CssTransform?) { nativeWidget.requestRepaint() }

    override fun dispose() {
        nativeWidget.destroyQWidget()
        super.dispose()
    }
}
