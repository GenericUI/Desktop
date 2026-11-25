@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.widget

import com.nexustools.genericui.desktop.core.DesktopInteractiveSurfaceSpec
import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.gtk.GtkWidgetHandle
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.plus
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import platform.posix.memcpy
import platform.posix.size_t

/**
 * Live retained surface painter for video / WebGL / custom framebuffer [DesktopInteractiveSurfaceSpec]
 * containers inside GTK4 `GtkDrawingArea` overlays.
 */
object GtkInteractiveSurfaceWidget {

    fun paintInteractiveSurface(
        handle: GtkWidgetHandle,
        cr: COpaquePointer,
        width: Int,
        height: Int
    ): Boolean {
        val spec = handle.interactiveSurfaceSpec ?: return false
        if (width <= 0 || height <= 0) return true

        val swapCount = spec.swapCount()
        spec.onBeforePresentFrame?.invoke(swapCount, null)

        val srcW = spec.surfaceWidth()
        val srcH = spec.surfaceHeight()
        val rect = if (srcW > 0 && srcH > 0) {
            DesktopInteractiveSurfaceSpec.computePresentationRect(
                width.toDouble(),
                height.toDouble(),
                srcW,
                srcH,
                spec
            )
        } else {
            null
        }

        var surface: COpaquePointer? = null
        try {
            if (srcW > 0 && srcH > 0) {
                val bgraPtrPair = spec.acquireBgraPtr?.invoke()
                val ptr = bgraPtrPair?.first as? COpaquePointer
                val strideBytes = DesktopInteractiveSurfaceSpec.resolveBgraRowStrideBytes(srcW, srcH, bgraPtrPair?.second ?: 0)
                if (ptr != null) {
                    surface = createCairoSurfaceFromPremulBgraPtr(ptr, srcW, srcH, strideBytes)
                } else {
                    val bgraBytes = spec.captureBgra?.invoke()
                    if (bgraBytes != null && bgraBytes.size >= srcW * srcH * 4) {
                        surface = createCairoSurfaceFromBgraBytes(bgraBytes, srcW, srcH)
                    } else {
                        val rgbaBytes = spec.captureRgba?.invoke()
                        if (rgbaBytes != null && rgbaBytes.size >= srcW * srcH * 4) {
                            surface = createCairoSurfaceFromRgbaBytes(rgbaBytes, srcW, srcH)
                        }
                    }
                }
            }

            Gtk4DynamicBindings.cairoSave(cr)
            try {
                val wD = width.toDouble()
                val hD = height.toDouble()
                Gtk4DynamicBindings.cairoNewPath(cr)
                Gtk4DynamicBindings.cairoRectangle(cr, 0.0, 0.0, wD, hD)
                Gtk4DynamicBindings.cairoClip(cr)
                Gtk4DynamicBindings.cairoNewPath(cr)
                if (spec.shouldPaintBlackBackdrop()) {
                    Gtk4DynamicBindings.cairoSetSourceRgba(cr, 0.0, 0.0, 0.0, 1.0)
                    Gtk4DynamicBindings.cairoRectangle(cr, 0.0, 0.0, wD, hD)
                    Gtk4DynamicBindings.cairoFill(cr)
                }
                if (surface != null && rect != null && rect.dstW > 0.0 && rect.dstH > 0.0) {
                    Gtk4DynamicBindings.cairoTranslate(cr, rect.dstX, rect.dstY)
                    Gtk4DynamicBindings.cairoScale(cr, rect.dstW / srcW.toDouble(), rect.dstH / srcH.toDouble())
                    Gtk4DynamicBindings.cairoSetSourceSurface(cr, surface, 0.0, 0.0)
                    Gtk4DynamicBindings.cairoPaint(cr)
                }
            } finally {
                Gtk4DynamicBindings.cairoRestore(cr)
            }
        } finally {
            Gtk4DynamicBindings.cairoSurfaceDestroy(surface)
        }
        return true
    }

    private fun createCairoSurfaceFromPremulBgraPtr(
        srcPtr: COpaquePointer,
        width: Int,
        height: Int,
        srcStrideBytes: Int
    ): COpaquePointer? {
        val surface = Gtk4DynamicBindings.cairoImageSurfaceCreate(width, height) ?: return null
        Gtk4DynamicBindings.cairoSurfaceFlush(surface)
        val dst = Gtk4DynamicBindings.cairoImageSurfaceGetData(surface)?.reinterpret<ByteVar>()
        val dstStride = Gtk4DynamicBindings.cairoImageSurfaceGetStride(surface)
        if (dst != null && dstStride >= width * 4) {
            val src = srcPtr.reinterpret<ByteVar>()
            val rowBytesInt = width * 4
            val rowBytes: size_t = rowBytesInt.convert()
            for (y in 0 until height) {
                val rowDst = dst + (y * dstStride)
                memcpy(rowDst, src + (y * srcStrideBytes), rowBytes)
                if (rowDst != null) {
                    var aOff = 3
                    while (aOff < rowBytesInt) {
                        rowDst[aOff] = (-1).toByte()
                        aOff += 4
                    }
                }
            }
            Gtk4DynamicBindings.cairoSurfaceMarkDirty(surface)
        }
        return surface
    }

    private fun createCairoSurfaceFromBgraBytes(bgra: ByteArray, width: Int, height: Int): COpaquePointer? {
        val surface = Gtk4DynamicBindings.cairoImageSurfaceCreate(width, height) ?: return null
        Gtk4DynamicBindings.cairoSurfaceFlush(surface)
        val dst = Gtk4DynamicBindings.cairoImageSurfaceGetData(surface)?.reinterpret<ByteVar>()
        val dstStride = Gtk4DynamicBindings.cairoImageSurfaceGetStride(surface)
        if (dst != null && dstStride >= width * 4) {
            val rowBytes: size_t = (width * 4).convert()
            bgra.usePinned { pinned ->
                val src = pinned.addressOf(0).reinterpret<ByteVar>()
                for (y in 0 until height) {
                    memcpy(dst + (y * dstStride), src + (y * width * 4), rowBytes)
                }
            }
            Gtk4DynamicBindings.cairoSurfaceMarkDirty(surface)
        }
        return surface
    }

    private fun createCairoSurfaceFromRgbaBytes(rgba: ByteArray, width: Int, height: Int): COpaquePointer? {
        val premulBgra = ByteArray(width * height * 4)
        for (i in 0 until width * height) {
            val o = i * 4
            val r = rgba[o].toInt() and 0xFF
            val g = rgba[o + 1].toInt() and 0xFF
            val b = rgba[o + 2].toInt() and 0xFF
            val a = rgba[o + 3].toInt() and 0xFF
            premulBgra[o] = ((b * a + 127) / 255).toByte()
            premulBgra[o + 1] = ((g * a + 127) / 255).toByte()
            premulBgra[o + 2] = ((r * a + 127) / 255).toByte()
            premulBgra[o + 3] = a.toByte()
        }
        return createCairoSurfaceFromBgraBytes(premulBgra, width, height)
    }
}
