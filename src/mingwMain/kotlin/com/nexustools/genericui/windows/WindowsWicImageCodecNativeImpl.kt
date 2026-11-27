@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.desktop.image.DesktopLibJpegImageCodecImpl
import com.nexustools.genericui.image.AnimatedImage
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.image.ImageColorSpace
import com.nexustools.genericui.image.ImageDecodeOptions
import com.nexustools.genericui.image.ImageEncodeOptions
import com.nexustools.genericui.image.ImageFormat
import com.nexustools.genericui.image.ImageFrame
import com.nexustools.genericui.image.ImageMetadata
import com.nexustools.genericui.image.ImageSource
import com.nexustools.genericui.image.PixelFormat
import com.nexustools.genericui.image.cache.ImageCacheManager
import com.nexustools.genericui.resources.ResourceResolver
import com.nexustools.genericui.style.css.CssCustomCursor
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.GUID
import platform.posix.memcpy
import platform.windows.CLSCTX_INPROC_SERVER
import platform.windows.COINIT_APARTMENTTHREADED
import platform.windows.CoCreateInstance
import platform.windows.CoInitializeEx
import platform.windows.CreateStreamOnHGlobal
import platform.windows.GMEM_MOVEABLE
import platform.windows.GetHGlobalFromStream
import platform.windows.GlobalAlloc
import platform.windows.GlobalFree
import platform.windows.GlobalLock
import platform.windows.GlobalSize
import platform.windows.GlobalUnlock
import platform.windows.HGLOBALVar
import platform.windows.LPSTREAMVar

/**
 * Windows native image loader & codec implementation backed by Windows Imaging Component
 * (`CoCreateInstance(CLSID_WICImagingFactory)` -> `IWICImagingFactory`, `IWICBitmapDecoder`,
 * `IWICFormatConverter`, `IWICBitmapEncoder`, `IWICBitmapFrameEncode`) with fallback to
 * `libjpeg-turbo`, `libpng`, and `libwebp` via [DesktopLibJpegImageCodecImpl].
 */
class WindowsWicImageCodecNativeImpl : DesktopLibJpegImageCodecImpl(
    codecBackendName = "Windows WIC (IWICImagingFactory) + libjpeg-turbo/libpng/libwebp"
) {

    override fun probeMetadata(source: ImageSource): ImageMetadata? {
        source.memoryBuffer?.let { return super.probeMetadata(source) }
        val bytes = resolveSourceBytes(source) ?: return null
        if (bytes.isEmpty()) return null
        if (isSvgPayload(bytes, source.formatHint)) {
            return probeSvgMetadata(bytes) ?: super.probeMetadata(source)
        }
        probeViaWic(bytes)?.let { return it }
        return super.probeMetadata(source)
    }

    override fun decode(source: ImageSource, options: ImageDecodeOptions): ImageBuffer? {
        source.memoryBuffer?.let { return it.copy() }
        val bytes = resolveSourceBytes(source) ?: return null
        if (bytes.isEmpty()) return null
        if (isSvgPayload(bytes, source.formatHint)) {
            return decodeSvg(bytes, options)
        }
        decodeViaWic(bytes)?.let { return it }
        return super.decode(source, options)
    }

    override fun decodeAnimated(source: ImageSource, options: ImageDecodeOptions): AnimatedImage? {
        val bytes = resolveSourceBytes(source) ?: return super.decodeAnimated(source, options)
        if (bytes.isNotEmpty() && !isSvgPayload(bytes, source.formatHint)) {
            decodeAnimatedViaWic(bytes)?.let { return it }
        }
        return super.decodeAnimated(source, options)
    }

    protected override fun decodeSvg(svgBytes: ByteArray, options: ImageDecodeOptions): ImageBuffer? {
        val markup = svgBytes.decodeToString(throwOnInvalidSequence = false)
        if (markup.isBlank() || !markup.contains("<svg", ignoreCase = true)) {
            return null
        }
        val widthPx = (options.targetWidth?.takeIf { it > 0 } ?: 48).coerceIn(1, 256)
        val heightPx = (options.targetHeight?.takeIf { it > 0 } ?: 48).coerceIn(1, 256)
        Win32SvgIconRasterizer.rasterize(
            svgMarkup = markup,
            widthPx = widthPx,
            heightPx = heightPx
        )?.let { return it }
        return CssCustomCursor.fromSvg(
            svgMarkup = markup,
            widthPx = widthPx,
            heightPx = heightPx
        ).resolveToImageBuffer()
    }

    override fun encode(buffer: ImageBuffer, options: ImageEncodeOptions): ByteArray? {
        if (options.format == ImageFormat.RAW_RGBA) {
            return buffer.toRgba8888().bytes
        }
        encodeViaWic(buffer, options.format)?.let { return it }
        return super.encode(buffer, options)
    }

    private fun resolveSourceBytes(source: ImageSource): ByteArray? =
        source.encodedBytes
            ?: source.resId?.let { ResourceResolver.getBytes(it) }
            ?: source.uriOrPath?.let { ImageCacheManager.fetchAndCacheBytes(it) }

    private fun isSvgPayload(bytes: ByteArray, formatHint: ImageFormat): Boolean {
        val detected = ImageFormat.detectFromHeader(bytes)
        if (detected == ImageFormat.SVG) return true
        if (detected != ImageFormat.UNKNOWN) return false
        val head = bytes.decodeToString(0, minOf(bytes.size, 2048), throwOnInvalidSequence = false)
            .trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        return (formatHint == ImageFormat.SVG || head.startsWith("<")) &&
            head.contains("<svg", ignoreCase = true)
    }

    private fun probeSvgMetadata(bytes: ByteArray): ImageMetadata? {
        val markup = bytes.decodeToString(throwOnInvalidSequence = false)
        if (markup.isBlank() || !markup.contains("<svg", ignoreCase = true)) return null
        val svgTagAttrs = SVG_OPEN_TAG_REGEX.find(markup)?.groupValues?.get(1).orEmpty()
        var widthPx = parseSvgLengthAttr(svgTagAttrs, "width")
        var heightPx = parseSvgLengthAttr(svgTagAttrs, "height")
        val viewBoxMatch = SVG_VIEWBOX_REGEX.find(svgTagAttrs) ?: SVG_VIEWBOX_REGEX.find(markup)
        if (viewBoxMatch != null) {
            val nums = viewBoxMatch.groupValues[1].trim()
                .split(WHITESPACE_OR_COMMA_REGEX)
                .mapNotNull { it.toFloatOrNull() }
            if (nums.size >= 4 && nums[2] > 0f && nums[3] > 0f) {
                val vbW = kotlin.math.round(nums[2]).toInt().coerceAtLeast(1)
                val vbH = kotlin.math.round(nums[3]).toInt().coerceAtLeast(1)
                if (widthPx == null && heightPx == null) {
                    widthPx = vbW
                    heightPx = vbH
                } else if (widthPx == null) {
                    widthPx = kotlin.math.round(heightPx!! * (nums[2] / nums[3])).toInt().coerceAtLeast(1)
                } else if (heightPx == null) {
                    heightPx = kotlin.math.round(widthPx * (nums[3] / nums[2])).toInt().coerceAtLeast(1)
                }
            }
        }
        return ImageMetadata(
            width = widthPx ?: 48,
            height = heightPx ?: 48,
            format = ImageFormat.SVG,
            pixelFormat = PixelFormat.RGBA_8888,
            colorSpace = ImageColorSpace.SRGB,
            hasAlpha = true
        )
    }

    private fun parseSvgLengthAttr(attrs: String, attrName: String): Int? {
        if (attrs.isBlank()) return null
        val match = Regex("\\b$attrName\\s*=\\s*['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE).find(attrs)
            ?: return null
        val raw = match.groupValues[1].trim()
        if (raw.isEmpty() || raw.endsWith("%")) return null
        val numeric = if (raw.endsWith("px", ignoreCase = true)) {
            raw.dropLast(2).trim()
        } else {
            raw
        }
        val parsed = numeric.toFloatOrNull()?.takeIf { it > 0f } ?: return null
        return kotlin.math.round(parsed).toInt().coerceAtLeast(1)
    }

    private fun probeViaWic(bytes: ByteArray): ImageMetadata? {
        return withWicFactory { factory ->
            val stream = createIStreamFromBytes(bytes) ?: return@withWicFactory null
            try {
                val decoder = createDecoderFromStream(factory, stream) ?: return@withWicFactory null
                try {
                    val containerFormat = readDecoderContainerFormat(decoder) ?: ImageFormat.detectFromHeader(bytes)
                    val frame = getDecoderFrame(decoder, 0u) ?: return@withWicFactory null
                    try {
                        val size = getBitmapSourceSize(frame) ?: return@withWicFactory null
                        ImageMetadata(
                            width = size.first,
                            height = size.second,
                            format = containerFormat,
                            pixelFormat = PixelFormat.RGBA_8888,
                            colorSpace = ImageColorSpace.SRGB
                        )
                    } finally {
                        comRelease(frame)
                    }
                } finally {
                    comRelease(decoder)
                }
            } finally {
                comRelease(stream)
            }
        }
    }

    private fun decodeViaWic(bytes: ByteArray): ImageBuffer? {
        return withWicFactory { factory ->
            val stream = createIStreamFromBytes(bytes) ?: return@withWicFactory null
            try {
                val decoder = createDecoderFromStream(factory, stream) ?: return@withWicFactory null
                try {
                    val frame = getDecoderFrame(decoder, 0u) ?: return@withWicFactory null
                    try {
                        convertFrameToRgbaBuffer(factory, frame)
                    } finally {
                        comRelease(frame)
                    }
                } finally {
                    comRelease(decoder)
                }
            } finally {
                comRelease(stream)
            }
        }
    }

    private fun decodeAnimatedViaWic(bytes: ByteArray): AnimatedImage? {
        return withWicFactory { factory ->
            val stream = createIStreamFromBytes(bytes) ?: return@withWicFactory null
            try {
                val decoder = createDecoderFromStream(factory, stream) ?: return@withWicFactory null
                try {
                    val frameCount = getDecoderFrameCount(decoder).coerceAtLeast(1)
                    val frames = ArrayList<ImageFrame>(frameCount)
                    var animW = 0
                    var animH = 0
                    for (idx in 0 until frameCount) {
                        val frame = getDecoderFrame(decoder, idx.toUInt()) ?: continue
                        try {
                            val buf = convertFrameToRgbaBuffer(factory, frame) ?: continue
                            if (animW == 0) {
                                animW = buf.width
                                animH = buf.height
                            }
                            frames.add(ImageFrame(frameIndex = idx, buffer = buf, durationMs = 100))
                        } finally {
                            comRelease(frame)
                        }
                    }
                    if (frames.isEmpty() || animW <= 0 || animH <= 0) {
                        null
                    } else {
                        AnimatedImage(width = animW, height = animH, frames = frames)
                    }
                } finally {
                    comRelease(decoder)
                }
            } finally {
                comRelease(stream)
            }
        }
    }

    private fun encodeViaWic(buffer: ImageBuffer, format: ImageFormat): ByteArray? {
        if (buffer.width <= 0 || buffer.height <= 0) return null
        val rgba = buffer.toRgba8888()
        return withWicFactory { factory ->
            val hStream = createEmptyMemoryIStream() ?: return@withWicFactory null
            try {
                val encoder = memScoped {
                    val containerGuid = alloc<GUID>()
                    if (!fillContainerGuid(containerGuid, format)) return@withWicFactory null
                    val encoderVar = alloc<COpaquePointerVar>()
                    val fnCreateEncoder = comMethod<
                        CFunction<(COpaquePointer, CPointer<GUID>, CPointer<GUID>?, CPointer<COpaquePointerVar>) -> Int>
                    >(factory, SLOT_FACTORY_CREATE_ENCODER)
                    val hr = fnCreateEncoder(factory, containerGuid.ptr, null, encoderVar.ptr)
                    if (hr < 0) null else encoderVar.value
                } ?: return@withWicFactory null

                try {
                    val fnInitEncoder = comMethod<CFunction<(COpaquePointer, COpaquePointer, UInt) -> Int>>(
                        encoder,
                        SLOT_ENCODER_INITIALIZE
                    )
                    if (fnInitEncoder(encoder, hStream, WIC_BITMAP_ENCODER_NO_CACHE) < 0) {
                        return@withWicFactory null
                    }

                    val frameEncode = memScoped {
                        val frameVar = alloc<COpaquePointerVar>()
                        val fnCreateNewFrame = comMethod<
                            CFunction<(COpaquePointer, CPointer<COpaquePointerVar>, CPointer<COpaquePointerVar>?) -> Int>
                        >(encoder, SLOT_ENCODER_CREATE_NEW_FRAME)
                        val hr = fnCreateNewFrame(encoder, frameVar.ptr, null)
                        if (hr < 0) null else frameVar.value
                    } ?: return@withWicFactory null

                    try {
                        val fnFrameInit = comMethod<CFunction<(COpaquePointer, COpaquePointer?) -> Int>>(
                            frameEncode,
                            SLOT_FRAME_ENCODE_INITIALIZE
                        )
                        if (fnFrameInit(frameEncode, null) < 0) return@withWicFactory null

                        val fnFrameSetSize = comMethod<CFunction<(COpaquePointer, UInt, UInt) -> Int>>(
                            frameEncode,
                            SLOT_FRAME_ENCODE_SET_SIZE
                        )
                        if (fnFrameSetSize(frameEncode, rgba.width.toUInt(), rgba.height.toUInt()) < 0) {
                            return@withWicFactory null
                        }

                        val pixelFormatHr = memScoped {
                            val fmtGuid = alloc<GUID>()
                            fillPixelFormat32bppBgra(fmtGuid)
                            val fnSetPixelFormat = comMethod<CFunction<(COpaquePointer, CPointer<GUID>) -> Int>>(
                                frameEncode,
                                SLOT_FRAME_ENCODE_SET_PIXEL_FORMAT
                            )
                            fnSetPixelFormat(frameEncode, fmtGuid.ptr)
                        }
                        if (pixelFormatHr < 0) return@withWicFactory null

                        val stride = rgba.width * 4
                        val totalBytes = stride * rgba.height
                        val bgra = ByteArray(totalBytes)
                        val src = rgba.bytes
                        var i = 0
                        while (i + 3 < totalBytes) {
                            bgra[i] = src[i + 2]
                            bgra[i + 1] = src[i + 1]
                            bgra[i + 2] = src[i]
                            bgra[i + 3] = src[i + 3]
                            i += 4
                        }

                        val writeHr = bgra.usePinned { pinned ->
                            val fnWritePixels = comMethod<
                                CFunction<(COpaquePointer, UInt, UInt, UInt, COpaquePointer) -> Int>
                            >(frameEncode, SLOT_FRAME_ENCODE_WRITE_PIXELS)
                            fnWritePixels(
                                frameEncode,
                                rgba.height.toUInt(),
                                stride.toUInt(),
                                totalBytes.toUInt(),
                                pinned.addressOf(0)
                            )
                        }
                        if (writeHr < 0) return@withWicFactory null

                        val fnFrameCommit = comMethod<CFunction<(COpaquePointer) -> Int>>(
                            frameEncode,
                            SLOT_FRAME_ENCODE_COMMIT
                        )
                        if (fnFrameCommit(frameEncode) < 0) return@withWicFactory null
                    } finally {
                        comRelease(frameEncode)
                    }

                    val fnEncoderCommit = comMethod<CFunction<(COpaquePointer) -> Int>>(
                        encoder,
                        SLOT_ENCODER_COMMIT
                    )
                    if (fnEncoderCommit(encoder) < 0) return@withWicFactory null

                    readBytesFromMemoryIStream(hStream)
                } finally {
                    comRelease(encoder)
                }
            } finally {
                comRelease(hStream)
            }
        }
    }

    private inline fun <T> withWicFactory(block: (COpaquePointer) -> T?): T? {
        val hrCo = CoInitializeEx(null, COINIT_APARTMENTTHREADED)
        if (hrCo < 0 && hrCo != RPC_E_CHANGED_MODE) return null
        val factory = memScoped {
            val clsid = alloc<GUID>()
            fillGuid(clsid, 0xcacaf262u, 0x9370u, 0x4615u, 0xa1u, 0x3bu, 0x9fu, 0x55u, 0x39u, 0xdau, 0x4cu, 0x0au)
            val iid = alloc<GUID>()
            fillGuid(iid, 0xec5ec8a9u, 0xc395u, 0x4314u, 0x9cu, 0x77u, 0x54u, 0xd7u, 0xa9u, 0x35u, 0xffu, 0x70u)
            val outPtr = alloc<COpaquePointerVar>()
            val hr = CoCreateInstance(clsid.ptr, null, CLSCTX_INPROC_SERVER.toUInt(), iid.ptr, outPtr.ptr)
            if (hr < 0) null else outPtr.value
        } ?: return null
        return try {
            block(factory)
        } finally {
            comRelease(factory)
        }
    }

    private fun createDecoderFromStream(factory: COpaquePointer, stream: COpaquePointer): COpaquePointer? = memScoped {
        val decoderVar = alloc<COpaquePointerVar>()
        val fn = comMethod<
            CFunction<(COpaquePointer, COpaquePointer, CPointer<GUID>?, UInt, CPointer<COpaquePointerVar>) -> Int>
        >(factory, SLOT_FACTORY_CREATE_DECODER_FROM_STREAM)
        val hr = fn(factory, stream, null, WIC_DECODE_METADATA_CACHE_ON_LOAD, decoderVar.ptr)
        if (hr < 0) null else decoderVar.value
    }

    private fun getDecoderFrameCount(decoder: COpaquePointer): Int = memScoped {
        val countVar = alloc<UIntVar>()
        countVar.value = 0u
        val fn = comMethod<CFunction<(COpaquePointer, CPointer<UIntVar>) -> Int>>(
            decoder,
            SLOT_DECODER_GET_FRAME_COUNT
        )
        if (fn(decoder, countVar.ptr) < 0) 1 else countVar.value.toInt()
    }

    private fun getDecoderFrame(decoder: COpaquePointer, index: UInt): COpaquePointer? = memScoped {
        val frameVar = alloc<COpaquePointerVar>()
        val fn = comMethod<CFunction<(COpaquePointer, UInt, CPointer<COpaquePointerVar>) -> Int>>(
            decoder,
            SLOT_DECODER_GET_FRAME
        )
        val hr = fn(decoder, index, frameVar.ptr)
        if (hr < 0) null else frameVar.value
    }

    private fun readDecoderContainerFormat(decoder: COpaquePointer): ImageFormat? = memScoped {
        val guid = alloc<GUID>()
        val fn = comMethod<CFunction<(COpaquePointer, CPointer<GUID>) -> Int>>(
            decoder,
            SLOT_DECODER_GET_CONTAINER_FORMAT
        )
        if (fn(decoder, guid.ptr) < 0) return@memScoped null
        when (guid.Data1) {
            0x1b7cfaf4u -> ImageFormat.PNG
            0x19e4a5aau -> ImageFormat.JPEG
            0x0af1d87eu -> ImageFormat.BMP
            0x1f8a5601u -> ImageFormat.GIF
            else -> null
        }
    }

    private fun getBitmapSourceSize(source: COpaquePointer): Pair<Int, Int>? = memScoped {
        val wVar = alloc<UIntVar>()
        val hVar = alloc<UIntVar>()
        val fn = comMethod<CFunction<(COpaquePointer, CPointer<UIntVar>, CPointer<UIntVar>) -> Int>>(
            source,
            SLOT_SOURCE_GET_SIZE
        )
        if (fn(source, wVar.ptr, hVar.ptr) < 0) return@memScoped null
        val w = wVar.value.toInt()
        val h = hVar.value.toInt()
        if (w <= 0 || h <= 0) null else (w to h)
    }

    private fun convertFrameToRgbaBuffer(factory: COpaquePointer, frame: COpaquePointer): ImageBuffer? {
        val (w, h) = getBitmapSourceSize(frame) ?: return null
        val converter = memScoped {
            val convVar = alloc<COpaquePointerVar>()
            val fn = comMethod<CFunction<(COpaquePointer, CPointer<COpaquePointerVar>) -> Int>>(
                factory,
                SLOT_FACTORY_CREATE_FORMAT_CONVERTER
            )
            if (fn(factory, convVar.ptr) < 0) null else convVar.value
        } ?: return null

        try {
            val initHr = memScoped {
                val rgbaGuid = alloc<GUID>()
                fillGuid(rgbaGuid, 0xf5c7ad2du, 0x6a8du, 0x43ddu, 0xa7u, 0xa8u, 0xa2u, 0x99u, 0x35u, 0x26u, 0x1au, 0xe9u)
                val fnInit = comMethod<
                    CFunction<(COpaquePointer, COpaquePointer, CPointer<GUID>, UInt, COpaquePointer?, Double, UInt) -> Int>
                >(converter, SLOT_CONVERTER_INITIALIZE)
                fnInit(converter, frame, rgbaGuid.ptr, 0u, null, 0.0, 0u)
            }
            if (initHr < 0) return null

            val stride = w * 4
            val totalBytes = stride * h
            val outBytes = ByteArray(totalBytes)
            val copyHr = outBytes.usePinned { pinned ->
                val fnCopy = comMethod<
                    CFunction<(COpaquePointer, COpaquePointer?, UInt, UInt, COpaquePointer) -> Int>
                >(converter, SLOT_SOURCE_COPY_PIXELS)
                fnCopy(converter, null, stride.toUInt(), totalBytes.toUInt(), pinned.addressOf(0))
            }
            if (copyHr < 0) return null

            return ImageBuffer(
                width = w,
                height = h,
                pixelFormat = PixelFormat.RGBA_8888,
                colorSpace = ImageColorSpace.SRGB,
                bytes = outBytes
            )
        } finally {
            comRelease(converter)
        }
    }

    private fun createIStreamFromBytes(bytes: ByteArray): COpaquePointer? {
        val hGlobal = GlobalAlloc(GMEM_MOVEABLE.toUInt(), bytes.size.toULong()) ?: return null
        val locked = GlobalLock(hGlobal)
        if (locked == null) {
            GlobalFree(hGlobal)
            return null
        }
        if (bytes.isNotEmpty()) {
            bytes.usePinned { pinned ->
                memcpy(locked, pinned.addressOf(0), bytes.size.toULong())
            }
        }
        GlobalUnlock(hGlobal)
        val stream = memScoped {
            val streamVar = alloc<LPSTREAMVar>()
            val hr = CreateStreamOnHGlobal(hGlobal, 1, streamVar.ptr)
            if (hr < 0) null else streamVar.value
        }
        if (stream == null) {
            GlobalFree(hGlobal)
        }
        return stream
    }

    private fun createEmptyMemoryIStream(): COpaquePointer? = memScoped {
        val streamVar = alloc<LPSTREAMVar>()
        val hr = CreateStreamOnHGlobal(null, 1, streamVar.ptr)
        if (hr < 0) null else streamVar.value
    }

    private fun readBytesFromMemoryIStream(stream: COpaquePointer): ByteArray? = memScoped {
        val hGlobalVar = alloc<HGLOBALVar>()
        if (GetHGlobalFromStream(stream.reinterpret(), hGlobalVar.ptr) < 0) return@memScoped null
        val hGlobal = hGlobalVar.value ?: return@memScoped null
        val size = GlobalSize(hGlobal).toInt()
        if (size <= 0) return@memScoped null
        val locked = GlobalLock(hGlobal) ?: return@memScoped null
        try {
            val out = ByteArray(size)
            out.usePinned { pinned ->
                memcpy(pinned.addressOf(0), locked, size.toULong())
            }
            out
        } finally {
            GlobalUnlock(hGlobal)
        }
    }

    private inline fun <reified T : CFunction<*>> comMethod(obj: COpaquePointer, slot: Int): CPointer<T> {
        val vtblPtr = obj.reinterpret<CPointerVar<COpaquePointerVar>>().pointed.value
            ?: error("Null COM vtable pointer")
        return vtblPtr[slot]!!.reinterpret()
    }

    private fun comRelease(obj: COpaquePointer?) {
        if (obj == null) return
        val fnRelease = comMethod<CFunction<(COpaquePointer) -> UInt>>(obj, SLOT_UNKNOWN_RELEASE)
        fnRelease(obj)
    }

    private fun fillContainerGuid(guid: GUID, format: ImageFormat): Boolean {
        when (format) {
            ImageFormat.PNG -> fillGuid(guid, 0x1b7cfaf4u, 0x713fu, 0x473cu, 0xbbu, 0xcdu, 0x61u, 0x37u, 0x42u, 0x5fu, 0xaeu, 0xafu)
            ImageFormat.JPEG -> fillGuid(guid, 0x19e4a5aau, 0x5662u, 0x4fc5u, 0xa0u, 0xc0u, 0x17u, 0x58u, 0x02u, 0x8eu, 0x10u, 0x57u)
            ImageFormat.BMP -> fillGuid(guid, 0x0af1d87eu, 0xfcfeu, 0x4188u, 0xbdu, 0xebu, 0xa7u, 0x90u, 0x64u, 0x71u, 0xcbu, 0xe3u)
            ImageFormat.GIF -> fillGuid(guid, 0x1f8a5601u, 0x7d4du, 0x4cbdu, 0x9cu, 0x82u, 0x1bu, 0xc8u, 0xd4u, 0xeeu, 0xb9u, 0xa5u)
            ImageFormat.ICO -> fillGuid(guid, 0xa3a860c4u, 0x338fu, 0x4c17u, 0x91u, 0x9au, 0xfbu, 0xa4u, 0xb5u, 0x62u, 0x8fu, 0x21u)
            else -> return false
        }
        return true
    }

    private fun fillPixelFormat32bppBgra(guid: GUID) {
        fillGuid(guid, 0x6fddc324u, 0x4e03u, 0x4bfeu, 0xb1u, 0x85u, 0x3du, 0x77u, 0x76u, 0x8du, 0xc9u, 0x0fu)
    }

    private fun fillGuid(
        guid: GUID,
        d1: UInt,
        d2: UShort,
        d3: UShort,
        b0: UByte,
        b1: UByte,
        b2: UByte,
        b3: UByte,
        b4: UByte,
        b5: UByte,
        b6: UByte,
        b7: UByte
    ) {
        guid.Data1 = d1
        guid.Data2 = d2
        guid.Data3 = d3
        guid.Data4[0] = b0
        guid.Data4[1] = b1
        guid.Data4[2] = b2
        guid.Data4[3] = b3
        guid.Data4[4] = b4
        guid.Data4[5] = b5
        guid.Data4[6] = b6
        guid.Data4[7] = b7
    }

    private companion object {
        const val RPC_E_CHANGED_MODE = -2147417850
        const val WIC_DECODE_METADATA_CACHE_ON_LOAD = 1u
        const val WIC_BITMAP_ENCODER_NO_CACHE = 2u

        const val SLOT_UNKNOWN_RELEASE = 2
        const val SLOT_FACTORY_CREATE_DECODER_FROM_STREAM = 4
        const val SLOT_FACTORY_CREATE_ENCODER = 8
        const val SLOT_FACTORY_CREATE_FORMAT_CONVERTER = 10
        const val SLOT_DECODER_GET_CONTAINER_FORMAT = 5
        const val SLOT_DECODER_GET_FRAME_COUNT = 12
        const val SLOT_DECODER_GET_FRAME = 13
        const val SLOT_SOURCE_GET_SIZE = 3
        const val SLOT_SOURCE_COPY_PIXELS = 7
        const val SLOT_CONVERTER_INITIALIZE = 8
        const val SLOT_ENCODER_INITIALIZE = 3
        const val SLOT_ENCODER_CREATE_NEW_FRAME = 10
        const val SLOT_ENCODER_COMMIT = 11
        const val SLOT_FRAME_ENCODE_INITIALIZE = 3
        const val SLOT_FRAME_ENCODE_SET_SIZE = 4
        const val SLOT_FRAME_ENCODE_SET_PIXEL_FORMAT = 6
        const val SLOT_FRAME_ENCODE_WRITE_PIXELS = 10
        const val SLOT_FRAME_ENCODE_COMMIT = 12

        val SVG_OPEN_TAG_REGEX = Regex("<svg\\b([^>]*)>", RegexOption.IGNORE_CASE)
        val SVG_VIEWBOX_REGEX = Regex("viewBox\\s*=\\s*['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE)
        val WHITESPACE_OR_COMMA_REGEX = Regex("[\\s,]+")
    }
}
