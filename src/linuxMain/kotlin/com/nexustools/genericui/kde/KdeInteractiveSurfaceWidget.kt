@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopInteractiveSurfaceSpec
import com.nexustools.genericui.style.color.RgbaColor
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned

/**
 * Retained KDE zero-copy pixel surface painter (`QImage(Format_ARGB32_Premultiplied)`) for
 * [DesktopInteractiveSurfaceSpec] (Canvas 2D, VideoPlayer, WebView, FloatingPiP).
 */
internal object KdeInteractiveSurfaceWidget {

    fun createCanvas2DWidgetHandle(): KdeWidgetHandle {
        KdeDynamicBindings.ensureQApplication()
        val ptr = KdeDynamicBindings.createQWidget(null, 0)
        val handle = KdeWidgetHandle("KdeInteractiveSurfaceWidget", ptr).apply {
            usesCustomPainting = true
        }
        attachSurfaceHooks(handle, impl = null)
        return handle
    }

    fun attachSurfaceHooks(handle: KdeWidgetHandle, impl: DesktopBaseNativeImpl<KdeWidgetHandle>?) {
        val ptr = handle.qWidgetPtr ?: return
        KdeVTableBridge.attachWidgetHooks(
            ptr,
            KdeVTableBridge.HOOK_PAINT or KdeVTableBridge.HOOK_RESIZE or KdeVTableBridge.HOOK_INPUT
        ) {
            onPaint = { _, painter, w, h ->
                paintInteractiveSurface(handle, painter, w, h)
            }
            onResize = { _, w, h ->
                handle.interactiveSurfaceSpec?.resizeSurface?.invoke(w.coerceAtLeast(1), h.coerceAtLeast(1))
            }
        }
        if (impl != null) {
            KdePainterSupport.attachStandardInputHooks(
                widgetPtr = ptr,
                impl = impl,
                hookMask = KdeVTableBridge.HOOK_PAINT or KdeVTableBridge.HOOK_RESIZE,
                dispatchLeftClickOnRelease = true,
                onPaint = { _, painter, w, h -> paintInteractiveSurface(handle, painter, w, h) }
            )
        }
    }

    fun paintInteractiveSurface(handle: KdeWidgetHandle, painter: COpaquePointer, widthPx: Int, heightPx: Int): Boolean {
        val spec = handle.interactiveSurfaceSpec ?: return false
        if (widthPx <= 0 || heightPx <= 0) return true

        val swap = spec.swapCount()
        spec.dispatchBeforePresentFrame(swap, null)

        val srcW = spec.surfaceWidth().coerceAtLeast(1)
        val srcH = spec.surfaceHeight().coerceAtLeast(1)
        val rect = DesktopInteractiveSurfaceSpec.computePresentationRect(
            widthPx.toDouble(),
            heightPx.toDouble(),
            srcW,
            srcH,
            spec
        )

        KdeDynamicBindings.painterSave(painter)
        try {
            val wD = widthPx.toDouble()
            val hD = heightPx.toDouble()
            KdeDynamicBindings.painterSetClipRect(painter, 0.0, 0.0, wD, hD)
            if (spec.shouldPaintBlackBackdrop()) {
                KdeDynamicBindings.painterFillRect(painter, 0.0, 0.0, wD, hD, RgbaColor.BLACK)
            }

            val ptrAndStride = spec.acquireBgraPtr?.invoke()
            val rawPtr = ptrAndStride?.first as? COpaquePointer
            val stride = DesktopInteractiveSurfaceSpec.resolveBgraRowStrideBytes(srcW, srcH, ptrAndStride?.second ?: 0)
            if (rawPtr != null && stride > 0) {
                val zeroCopyImg = KdeDynamicBindings.createZeroCopyBgraQImage(rawPtr, srcW, srcH, stride, ignoreAlpha = true)
                if (zeroCopyImg != null) {
                    try {
                        drawImageIntoPresentationRect(painter, zeroCopyImg, srcW, srcH, rect)
                        return true
                    } finally {
                        KdeDynamicBindings.destroyQImage(zeroCopyImg)
                    }
                }
            }

            val bgraBytes = spec.captureBgra?.invoke()
            if (bgraBytes != null && bgraBytes.size >= srcW * srcH * 4) {
                val img = KdeDynamicBindings.createQImage(srcW, srcH, KdeDynamicBindings.IMAGE_FORMAT_ARGB32_PREMULTIPLIED)
                try {
                    val dst = KdeDynamicBindings.qImageBits(img)?.reinterpret<IntVar>()
                    if (dst != null) {
                        bgraBytes.usePinned { pinned ->
                            val srcInts = pinned.addressOf(0).reinterpret<IntVar>()
                            for (i in 0 until srcW * srcH) {
                                dst[i] = srcInts[i]
                            }
                        }
                        drawImageIntoPresentationRect(painter, img, srcW, srcH, rect)
                    }
                    return true
                } finally {
                    KdeDynamicBindings.destroyQImage(img)
                }
            }

            val rgbaBytes = spec.captureRgba?.invoke()
            if (rgbaBytes != null && rgbaBytes.size >= srcW * srcH * 4) {
                val img = KdeDynamicBindings.createQImage(srcW, srcH, KdeDynamicBindings.IMAGE_FORMAT_ARGB32_PREMULTIPLIED)
                try {
                    val dst = KdeDynamicBindings.qImageBits(img)?.reinterpret<IntVar>()
                    if (dst != null) {
                        val count = srcW * srcH
                        for (i in 0 until count) {
                            val off = i * 4
                            val r = rgbaBytes[off].toInt() and 0xFF
                            val g = rgbaBytes[off + 1].toInt() and 0xFF
                            val b = rgbaBytes[off + 2].toInt() and 0xFF
                            val a = rgbaBytes[off + 3].toInt() and 0xFF
                            val pmR = if (a == 255) r else (r * a + 127) / 255
                            val pmG = if (a == 255) g else (g * a + 127) / 255
                            val pmB = if (a == 255) b else (b * a + 127) / 255
                            dst[i] = (a shl 24) or (pmR shl 16) or (pmG shl 8) or pmB
                        }
                        drawImageIntoPresentationRect(painter, img, srcW, srcH, rect)
                    }
                    return true
                } finally {
                    KdeDynamicBindings.destroyQImage(img)
                }
            }
            return spec.shouldPaintBlackBackdrop()
        } finally {
            KdeDynamicBindings.painterRestore(painter)
        }
    }

    private fun drawImageIntoPresentationRect(
        painter: COpaquePointer,
        qImage: COpaquePointer,
        srcW: Int,
        srcH: Int,
        rect: DesktopInteractiveSurfaceSpec.PresentationRect
    ) {
        if (rect.dstW <= 0.0 || rect.dstH <= 0.0) return
        KdeDynamicBindings.painterDrawImageRect(
            painter,
            rect.dstX,
            rect.dstY,
            rect.dstW,
            rect.dstH,
            qImage,
            0.0,
            0.0,
            srcW.toDouble(),
            srcH.toDouble()
        )
    }
}
