@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.linux

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.memcpy

internal object LinuxGdkPixbufBindings {
    const val GDK_COLORSPACE_RGB = 0
    const val GDK_INTERP_NEAREST = 0
    const val GDK_INTERP_BILINEAR = 2

    private val lock = LinuxReentrantLock()
    private var loaded = false
    private var pixbufAvailable = false
    private var webpAvailable = false

    private var fnLoaderNew: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnLoaderWrite: CPointer<CFunction<(COpaquePointer?, CPointer<UByteVar>?, ULong, CPointer<COpaquePointerVar>?) -> Int>>? = null
    private var fnLoaderClose: CPointer<CFunction<(COpaquePointer?, CPointer<COpaquePointerVar>?) -> Int>>? = null
    private var fnLoaderGetPixbuf: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGetPixels: CPointer<CFunction<(COpaquePointer?) -> CPointer<UByteVar>?>>? = null
    private var fnGetWidth: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnGetHeight: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnGetRowstride: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnGetNChannels: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnGetHasAlpha: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnNewFromData: CPointer<CFunction<(CPointer<UByteVar>?, Int, Int, Int, Int, Int, Int, COpaquePointer?, COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnSaveToBufferv: CPointer<CFunction<(COpaquePointer?, CPointer<CPointerVar<ByteVar>>?, CPointer<ULongVar>?, CPointer<ByteVar>?, CPointer<CPointerVar<ByteVar>>?, CPointer<CPointerVar<ByteVar>>?, CPointer<COpaquePointerVar>?) -> Int>>? = null
    private var fnScaleSimple: CPointer<CFunction<(COpaquePointer?, Int, Int, Int) -> COpaquePointer?>>? = null

    private var fnWebpGetInfo: CPointer<CFunction<(CPointer<UByteVar>?, ULong, CPointer<IntVar>?, CPointer<IntVar>?) -> Int>>? = null
    private var fnWebpDecodeRgba: CPointer<CFunction<(CPointer<UByteVar>?, ULong, CPointer<IntVar>?, CPointer<IntVar>?) -> CPointer<UByteVar>?>>? = null
    private var fnWebpEncodeRgba: CPointer<CFunction<(CPointer<UByteVar>?, Int, Int, Int, Float, CPointer<CPointerVar<UByteVar>>?) -> ULong>>? = null
    private var fnWebpFree: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null

    fun ensureLoaded(): Boolean = lock.withLock {
        if (loaded) return pixbufAvailable || webpAvailable
        loaded = true
        LinuxGlibGioBindings.ensureLoaded()

        val pixbuf = LinuxDynamicLibraryLoader.openFirst("libgdk_pixbuf-2.0.so.0", "libgdk_pixbuf-2.0.so", global = true)
        if (pixbuf != null) {
            fnLoaderNew = LinuxDynamicLibraryLoader.symbol(pixbuf, "gdk_pixbuf_loader_new")?.reinterpret()
            fnLoaderWrite = LinuxDynamicLibraryLoader.symbol(pixbuf, "gdk_pixbuf_loader_write")?.reinterpret()
            fnLoaderClose = LinuxDynamicLibraryLoader.symbol(pixbuf, "gdk_pixbuf_loader_close")?.reinterpret()
            fnLoaderGetPixbuf = LinuxDynamicLibraryLoader.symbol(pixbuf, "gdk_pixbuf_loader_get_pixbuf")?.reinterpret()
            fnGetPixels = LinuxDynamicLibraryLoader.symbol(pixbuf, "gdk_pixbuf_get_pixels")?.reinterpret()
            fnGetWidth = LinuxDynamicLibraryLoader.symbol(pixbuf, "gdk_pixbuf_get_width")?.reinterpret()
            fnGetHeight = LinuxDynamicLibraryLoader.symbol(pixbuf, "gdk_pixbuf_get_height")?.reinterpret()
            fnGetRowstride = LinuxDynamicLibraryLoader.symbol(pixbuf, "gdk_pixbuf_get_rowstride")?.reinterpret()
            fnGetNChannels = LinuxDynamicLibraryLoader.symbol(pixbuf, "gdk_pixbuf_get_n_channels")?.reinterpret()
            fnGetHasAlpha = LinuxDynamicLibraryLoader.symbol(pixbuf, "gdk_pixbuf_get_has_alpha")?.reinterpret()
            fnNewFromData = LinuxDynamicLibraryLoader.symbol(pixbuf, "gdk_pixbuf_new_from_data")?.reinterpret()
            fnSaveToBufferv = LinuxDynamicLibraryLoader.symbol(pixbuf, "gdk_pixbuf_save_to_bufferv")?.reinterpret()
            fnScaleSimple = LinuxDynamicLibraryLoader.symbol(pixbuf, "gdk_pixbuf_scale_simple")?.reinterpret()
            pixbufAvailable = fnLoaderNew != null && fnLoaderWrite != null && fnLoaderClose != null && fnLoaderGetPixbuf != null && fnGetPixels != null
        }

        val webp = LinuxDynamicLibraryLoader.openFirst("libwebp.so.7", "libwebp.so", global = false)
        if (webp != null) {
            fnWebpGetInfo = LinuxDynamicLibraryLoader.symbol(webp, "WebPGetInfo")?.reinterpret()
            fnWebpDecodeRgba = LinuxDynamicLibraryLoader.symbol(webp, "WebPDecodeRGBA")?.reinterpret()
            fnWebpEncodeRgba = LinuxDynamicLibraryLoader.symbol(webp, "WebPEncodeRGBA")?.reinterpret()
            fnWebpFree = LinuxDynamicLibraryLoader.symbol(webp, "WebPFree")?.reinterpret()
            webpAvailable = fnWebpGetInfo != null && fnWebpDecodeRgba != null && fnWebpFree != null
        }

        pixbufAvailable || webpAvailable
    }

    fun decodeViaGdkPixbuf(
        bytes: ByteArray,
        targetWidth: Int? = null,
        targetHeight: Int? = null,
        antialiasing: Boolean = true
    ): DecodedRgba? {
        if (!ensureLoaded() || !pixbufAvailable || bytes.isEmpty()) return null
        val loader = fnLoaderNew?.invoke() ?: return null
        try {
            val writeOk = memScoped {
                val err = alloc<COpaquePointerVar>()
                err.value = null
                val ok = bytes.usePinned { pinned ->
                    fnLoaderWrite?.invoke(loader, pinned.addressOf(0).reinterpret(), bytes.size.convert(), err.ptr) ?: 0
                }
                LinuxGlibGioBindings.readAndFreeError(err.ptr)
                ok != 0
            }
            val closeOk = memScoped {
                val err = alloc<COpaquePointerVar>()
                err.value = null
                val ok = fnLoaderClose?.invoke(loader, err.ptr) ?: 0
                LinuxGlibGioBindings.readAndFreeError(err.ptr)
                ok != 0
            }
            if (!writeOk && !closeOk) return null

            val basePixbuf = fnLoaderGetPixbuf?.invoke(loader) ?: return null
            val srcW = fnGetWidth?.invoke(basePixbuf) ?: 0
            val srcH = fnGetHeight?.invoke(basePixbuf) ?: 0
            if (srcW <= 0 || srcH <= 0) return null

            val dstW = targetWidth?.coerceAtLeast(1) ?: srcW
            val dstH = targetHeight?.coerceAtLeast(1) ?: srcH

            val scaledPixbuf = if ((dstW != srcW || dstH != srcH) && fnScaleSimple != null) {
                val interp = if (antialiasing) GDK_INTERP_BILINEAR else GDK_INTERP_NEAREST
                fnScaleSimple?.invoke(basePixbuf, dstW, dstH, interp)
            } else {
                null
            }
            val activePixbuf = scaledPixbuf ?: basePixbuf
            try {
                val w = fnGetWidth?.invoke(activePixbuf) ?: 0
                val h = fnGetHeight?.invoke(activePixbuf) ?: 0
                val rowstride = fnGetRowstride?.invoke(activePixbuf) ?: 0
                val channels = fnGetNChannels?.invoke(activePixbuf) ?: 0
                val hasAlpha = (fnGetHasAlpha?.invoke(activePixbuf) ?: 0) != 0
                val srcPixels = fnGetPixels?.invoke(activePixbuf) ?: return null
                if (w <= 0 || h <= 0 || rowstride <= 0 || channels < 3) return null

                val rowBytes = w * channels
                val raw = ByteArray(rowBytes * h)
                raw.usePinned { pinned ->
                    val dstBase = pinned.addressOf(0).rawValue
                    for (y in 0 until h) {
                        val srcRow = (srcPixels.rawValue + y.toLong() * rowstride.toLong())
                        memcpy(
                            kotlinx.cinterop.interpretCPointer<ByteVar>(dstBase + y.toLong() * rowBytes.toLong()),
                            kotlinx.cinterop.interpretCPointer<ByteVar>(srcRow),
                            rowBytes.convert()
                        )
                    }
                }

                val rgba = if (channels == 4) {
                    raw
                } else {
                    val out = ByteArray(w * h * 4)
                    val total = w * h
                    var srcIdx = 0
                    var dstIdx = 0
                    for (i in 0 until total) {
                        out[dstIdx++] = raw[srcIdx++]
                        out[dstIdx++] = raw[srcIdx++]
                        out[dstIdx++] = raw[srcIdx++]
                        out[dstIdx++] = 0xFF.toByte()
                    }
                    out
                }
                return DecodedRgba(width = w, height = h, rgbaBytes = rgba, hasAlpha = hasAlpha)
            } finally {
                if (scaledPixbuf != null) {
                    LinuxGlibGioBindings.objectUnref(scaledPixbuf)
                }
            }
        } finally {
            LinuxGlibGioBindings.objectUnref(loader)
        }
    }

    fun encodeViaGdkPixbuf(rgbaBytes: ByteArray, width: Int, height: Int, formatName: String, quality: Int): ByteArray? {
        if (!ensureLoaded() || !pixbufAvailable || width <= 0 || height <= 0) return null
        val expected = width * height * 4
        if (rgbaBytes.size < expected) return null

        return rgbaBytes.usePinned { pinned ->
            val pixbuf = fnNewFromData?.invoke(
                pinned.addressOf(0).reinterpret(),
                GDK_COLORSPACE_RGB,
                1,
                8,
                width,
                height,
                width * 4,
                null,
                null
            ) ?: return@usePinned null

            try {
                memScoped {
                    val outBufVar = alloc<CPointerVar<ByteVar>>()
                    val outSizeVar = alloc<ULongVar>()
                    val errVar = alloc<COpaquePointerVar>()
                    outBufVar.value = null
                    outSizeVar.value = 0uL
                    errVar.value = null

                    val ok = if (formatName == "jpeg") {
                        val keys = allocArray<CPointerVar<ByteVar>>(2)
                        val vals = allocArray<CPointerVar<ByteVar>>(2)
                        keys[0] = "quality".cstr.ptr
                        keys[1] = null
                        vals[0] = quality.coerceIn(1, 100).toString().cstr.ptr
                        vals[1] = null
                        fnSaveToBufferv?.invoke(pixbuf, outBufVar.ptr, outSizeVar.ptr, "jpeg".cstr.ptr, keys, vals, errVar.ptr) ?: 0
                    } else {
                        fnSaveToBufferv?.invoke(pixbuf, outBufVar.ptr, outSizeVar.ptr, formatName.cstr.ptr, null, null, errVar.ptr) ?: 0
                    }
                    LinuxGlibGioBindings.readAndFreeError(errVar.ptr)
                    val outPtr = outBufVar.value
                    val outSize = outSizeVar.value.toInt()
                    if (ok == 0 || outPtr == null || outSize <= 0) {
                        LinuxGlibGioBindings.free(outPtr)
                        return@usePinned null
                    }
                    try {
                        val encoded = ByteArray(outSize)
                        encoded.usePinned { dstPinned ->
                            memcpy(dstPinned.addressOf(0), outPtr, outSize.convert())
                        }
                        encoded
                    } finally {
                        LinuxGlibGioBindings.free(outPtr)
                    }
                }
            } finally {
                LinuxGlibGioBindings.objectUnref(pixbuf)
            }
        }
    }

    fun probeWebpDimensions(bytes: ByteArray): Pair<Int, Int>? {
        if (!ensureLoaded() || !webpAvailable || bytes.isEmpty()) return null
        return memScoped {
            val w = alloc<IntVar>()
            val h = alloc<IntVar>()
            val ok = bytes.usePinned { pinned ->
                fnWebpGetInfo?.invoke(pinned.addressOf(0).reinterpret(), bytes.size.convert(), w.ptr, h.ptr) ?: 0
            }
            if (ok != 0 && w.value > 0 && h.value > 0) w.value to h.value else null
        }
    }

    fun decodeWebpRgba(bytes: ByteArray): DecodedRgba? {
        if (!ensureLoaded() || !webpAvailable || bytes.isEmpty()) return null
        return memScoped {
            val wVar = alloc<IntVar>()
            val hVar = alloc<IntVar>()
            val decodedPtr = bytes.usePinned { pinned ->
                fnWebpDecodeRgba?.invoke(pinned.addressOf(0).reinterpret(), bytes.size.convert(), wVar.ptr, hVar.ptr)
            } ?: return@memScoped null
            try {
                val w = wVar.value
                val h = hVar.value
                if (w <= 0 || h <= 0) return@memScoped null
                val out = ByteArray(w * h * 4)
                out.usePinned { dstPinned ->
                    memcpy(dstPinned.addressOf(0), decodedPtr, out.size.convert())
                }
                DecodedRgba(width = w, height = h, rgbaBytes = out, hasAlpha = true)
            } finally {
                fnWebpFree?.invoke(decodedPtr)
            }
        }
    }

    fun encodeWebpRgba(rgbaBytes: ByteArray, width: Int, height: Int, quality: Float): ByteArray? {
        if (!ensureLoaded() || !webpAvailable || width <= 0 || height <= 0) return null
        val fn = fnWebpEncodeRgba ?: return null
        val freeFn = fnWebpFree ?: return null
        return memScoped {
            val outPtrVar = alloc<CPointerVar<UByteVar>>()
            outPtrVar.value = null
            val outSize = rgbaBytes.usePinned { pinned ->
                fn.invoke(pinned.addressOf(0).reinterpret(), width, height, width * 4, quality.coerceIn(1f, 100f), outPtrVar.ptr)
            }
            val outPtr = outPtrVar.value ?: return@memScoped null
            try {
                val len = outSize.toInt()
                if (len <= 0) return@memScoped null
                val encoded = ByteArray(len)
                encoded.usePinned { dstPinned ->
                    memcpy(dstPinned.addressOf(0), outPtr, len.convert())
                }
                encoded
            } finally {
                freeFn.invoke(outPtr)
            }
        }
    }

    data class DecodedRgba(
        val width: Int,
        val height: Int,
        val rgbaBytes: ByteArray,
        val hasAlpha: Boolean
    )
}
