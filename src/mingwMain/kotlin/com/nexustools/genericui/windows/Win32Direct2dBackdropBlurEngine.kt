@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class
)

package com.nexustools.genericui.windows

import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.css.FastBackdropBlurKernel
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.GUID
import platform.windows.GetModuleHandleW
import platform.windows.GetProcAddress
import platform.windows.HMODULE
import platform.windows.LoadLibraryW
import platform.windows.SwitchToThread
import kotlin.concurrent.atomics.AtomicInt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Hardware-accelerated Direct2D 1.1 + Direct3D 11 (`d3d11.dll` + `d2d1.dll`) backdrop blur and
 * CSS filter effect engine for Windows (`ID2D1DeviceContext` + `CLSID_D2D1GaussianBlur` +
 * `CLSID_D2D1ColorMatrix`), with seamless fallback to the zero-allocation [FastBackdropBlurKernel]
 * on environments where Direct3D 11 hardware device creation is unavailable.
 */
object Win32Direct2dBackdropBlurEngine {

    private val lockState = AtomicInt(0)
    private inline fun <T> withEngineLock(block: () -> T): T {
        while (!lockState.compareAndSet(0, 1)) {
            SwitchToThread()
        }
        try {
            return block()
        } finally {
            lockState.store(0)
        }
    }
    private var initAttempted = false
    private var gpuAvailable = false

    private var d3d11Module: HMODULE? = null
    private var d2d1Module: HMODULE? = null

    private var d3d11Device: COpaquePointer? = null
    private var dxgiDevice: COpaquePointer? = null
    private var d2dFactory1: COpaquePointer? = null
    private var d2dDevice: COpaquePointer? = null
    private var d2dContext: COpaquePointer? = null
    private var gaussianBlurEffect: COpaquePointer? = null
    private var colorMatrixEffect: COpaquePointer? = null

    private var cachedCapW = 0
    private var cachedCapH = 0
    private var cachedSrcBitmap: COpaquePointer? = null
    private var cachedTargetBitmap: COpaquePointer? = null

    private var cachedStagingW = 0
    private var cachedStagingH = 0
    private var cachedStagingBitmap: COpaquePointer? = null

    private var reusableCaptureBuffer = IntArray(0)

    /**
     * Returns `true` when hardware Direct2D 1.1 (`ID2D1DeviceContext` + `CLSID_D2D1GaussianBlur`)
     * initialized successfully on this Windows host.
     */
    fun isHardwareGpuActive(): Boolean = withEngineLock {
        ensureGpuInitialized()
        gpuAvailable
    }

    /**
     * Applies [filter] onto `[x0..x1, y0..y1]` of the `bufW x bufH` top-down 32-bit `0xAARRGGBB`
     * backbuffer [wordsPtr], sampling real neighbor context from `[capX0..capX1, capY0..capY1]`.
     */
    fun applyBackdropToBackBufferRegion(
        filter: CssFilter,
        wordsPtr: CPointer<UIntVar>,
        bufW: Int,
        bufH: Int,
        capX0: Int,
        capY0: Int,
        capX1: Int,
        capY1: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        regionX: Int,
        regionY: Int,
        regionWidth: Int,
        regionHeight: Int,
        cornerRadiusPx: Float,
        opacity: Float = 1f
    ): Boolean {
        val effOpacity = opacity.coerceIn(0f, 1f)
        if (effOpacity <= 0.002f) return true
        val capW = capX1 - capX0
        val capH = capY1 - capY0
        val copyW = x1 - x0
        val copyH = y1 - y0
        if (capW <= 0 || capH <= 0 || copyW <= 0 || copyH <= 0 || !filter.hasActiveEffects) return false

        val appliedGpu = withEngineLock {
            tryApplyViaDirect2d(
                filter = filter,
                wordsPtr = wordsPtr,
                bufW = bufW,
                capX0 = capX0,
                capY0 = capY0,
                capW = capW,
                capH = capH,
                x0 = x0,
                y0 = y0,
                copyW = copyW,
                copyH = copyH,
                regionX = regionX,
                regionY = regionY,
                regionWidth = regionWidth,
                regionHeight = regionHeight,
                cornerRadiusPx = cornerRadiusPx,
                opacity = effOpacity
            )
        }
        if (appliedGpu) return true

        return applyCpuFallback(
            filter = filter,
            wordsPtr = wordsPtr,
            bufW = bufW,
            capX0 = capX0,
            capY0 = capY0,
            capX1 = capX1,
            capY1 = capY1,
            x0 = x0,
            y0 = y0,
            x1 = x1,
            y1 = y1,
            regionX = regionX,
            regionY = regionY,
            regionWidth = regionWidth,
            regionHeight = regionHeight,
            cornerRadiusPx = cornerRadiusPx,
            opacity = effOpacity
        )
    }

    /**
     * Applies [filter] in-place onto an `0xAARRGGBB` [IntArray] framebuffer using hardware Direct2D 1.1
     * when available, falling back to [FastBackdropBlurKernel] when Direct3D hardware is unavailable.
     */
    fun applyBackdropToArgbArray(
        filter: CssFilter,
        argbPixels: IntArray,
        bufferWidth: Int,
        bufferHeight: Int,
        regionX: Int,
        regionY: Int,
        regionWidth: Int,
        regionHeight: Int,
        cornerRadiusPx: Float = 0f,
        opacity: Float = 1f
    ): Boolean {
        if (!filter.hasActiveEffects || bufferWidth <= 0 || bufferHeight <= 0) return false
        val effOpacity = opacity.coerceIn(0f, 1f)
        if (effOpacity <= 0.002f) return true
        if (argbPixels.size < bufferWidth * bufferHeight) return false
        val x0 = regionX.coerceIn(0, bufferWidth)
        val y0 = regionY.coerceIn(0, bufferHeight)
        val x1 = (regionX + regionWidth).coerceIn(0, bufferWidth)
        val y1 = (regionY + regionHeight).coerceIn(0, bufferHeight)
        if (x1 <= x0 || y1 <= y0) return false
        val blurPx = (filter.blur?.resolvePx() ?: 0f).coerceIn(0f, 48f)
        val shadow = filter.dropShadow?.takeIf { filter.hasDropShadow }
        val blurPad = if (blurPx > 0f) ceil(blurPx * 1.5f).toInt().coerceIn(1, 32) else 0
        val shadowPad = if (shadow != null) {
            val sBlur = (shadow.blurRadius.resolvePx() ?: 0f).coerceIn(0f, 48f)
            val sOff = max(kotlin.math.abs(shadow.offsetX.resolvePx() ?: 0f), kotlin.math.abs(shadow.offsetY.resolvePx() ?: 0f))
            (ceil(sBlur * 1.5f).toInt() + ceil(sOff).toInt()).coerceIn(1, 64)
        } else {
            0
        }
        val pad = max(blurPad, shadowPad)
        val capX0 = (x0 - pad).coerceAtLeast(0)
        val capY0 = (y0 - pad).coerceAtLeast(0)
        val capX1 = (x1 + pad).coerceAtMost(bufferWidth)
        val capY1 = (y1 + pad).coerceAtMost(bufferHeight)

        var handled = false
        argbPixels.usePinned { pinned ->
            val ptr = pinned.addressOf(0).reinterpret<UIntVar>()
            handled = applyBackdropToBackBufferRegion(
                filter = filter,
                wordsPtr = ptr,
                bufW = bufferWidth,
                bufH = bufferHeight,
                capX0 = capX0,
                capY0 = capY0,
                capX1 = capX1,
                capY1 = capY1,
                x0 = x0,
                y0 = y0,
                x1 = x1,
                y1 = y1,
                regionX = regionX,
                regionY = regionY,
                regionWidth = regionWidth,
                regionHeight = regionHeight,
                cornerRadiusPx = cornerRadiusPx,
                opacity = effOpacity
            )
        }
        return handled
    }

    private fun tryApplyViaDirect2d(
        filter: CssFilter,
        wordsPtr: CPointer<UIntVar>,
        bufW: Int,
        capX0: Int,
        capY0: Int,
        capW: Int,
        capH: Int,
        x0: Int,
        y0: Int,
        copyW: Int,
        copyH: Int,
        regionX: Int,
        regionY: Int,
        regionWidth: Int,
        regionHeight: Int,
        cornerRadiusPx: Float,
        opacity: Float
    ): Boolean {
        if (filter.hasDropShadow) return false
        if (!ensureGpuInitialized()) return false
        val ctx = d2dContext ?: return false
        val blurPx = if (filter.hasBlur) (filter.blur?.resolvePx() ?: 0f).coerceIn(0f, 48f) else 0f
        val downsample = when {
            blurPx >= 12f && capW >= 32 && capH >= 32 -> 4
            blurPx >= 6f && capW >= 16 && capH >= 16 -> 2
            else -> 1
        }
        val uploadW = if (downsample > 1) ((capW + downsample - 1) / downsample).coerceAtLeast(2) else capW
        val uploadH = if (downsample > 1) ((capH + downsample - 1) / downsample).coerceAtLeast(2) else capH
        val readW = if (downsample > 1) uploadW else copyW
        val readH = if (downsample > 1) uploadH else copyH

        if (!ensureBitmaps(ctx, uploadW, uploadH, readW, readH)) return false
        val srcBmp = cachedSrcBitmap ?: return false
        val dstBmp = cachedTargetBitmap ?: return false
        val stagingBmp = cachedStagingBitmap ?: return false

        ensureScratchSize(uploadW * uploadH)
        val captureBuf = reusableCaptureBuffer
        if (downsample == 1) {
            var dstIdx = 0
            for (py in capY0 until capY0 + capH) {
                val rowOff = py * bufW
                for (px in capX0 until capX0 + capW) {
                    captureBuf[dstIdx++] = wordsPtr[rowOff + px].toInt() or 0xFF000000.toInt()
                }
            }
        } else {
            val capXEnd = capX0 + capW
            val capYEnd = capY0 + capH
            var dstIdx = 0
            for (dsY in 0 until uploadH) {
                val sy0 = capY0 + dsY * downsample
                val sy1 = min(capYEnd, sy0 + downsample)
                for (dsX in 0 until uploadW) {
                    val sx0 = capX0 + dsX * downsample
                    val sx1 = min(capXEnd, sx0 + downsample)
                    var rSum = 0
                    var gSum = 0
                    var bSum = 0
                    var cnt = 0
                    for (sy in sy0 until sy1) {
                        val rowOff = sy * bufW
                        for (sx in sx0 until sx1) {
                            val w = wordsPtr[rowOff + sx].toInt()
                            rSum += (w ushr 16) and 0xFF
                            gSum += (w ushr 8) and 0xFF
                            bSum += w and 0xFF
                            cnt++
                        }
                    }
                    if (cnt <= 0) {
                        val w = wordsPtr[(min(capYEnd - 1, sy0)) * bufW + min(capXEnd - 1, sx0)].toInt()
                        captureBuf[dstIdx++] = w or 0xFF000000.toInt()
                    } else {
                        captureBuf[dstIdx++] = (0xFF000000.toInt()) or
                            ((rSum / cnt) shl 16) or
                            ((gSum / cnt) shl 8) or
                            (bSum / cnt)
                    }
                }
            }
        }

        val okCopyIn = memScoped {
            val dstRect = allocArray<UIntVar>(4)
            dstRect[0] = 0u
            dstRect[1] = 0u
            dstRect[2] = uploadW.toUInt()
            dstRect[3] = uploadH.toUInt()
            captureBuf.usePinned { pinned ->
                val fnCopyFromMem = comMethod<CFunction<(COpaquePointer, COpaquePointer?, COpaquePointer, UInt) -> Int>>(
                    srcBmp,
                    SLOT_BITMAP_COPY_FROM_MEMORY
                )
                fnCopyFromMem(srcBmp, dstRect, pinned.addressOf(0), (uploadW * 4).toUInt()) >= 0
            }
        }
        if (!okCopyIn) return false

        val effectiveBlurPx = blurPx / downsample.toFloat()
        var outputEffect: COpaquePointer? = null

        if (effectiveBlurPx > 0f) {
            val blurEff = gaussianBlurEffect ?: return false
            val setInputFn = comMethod<CFunction<(COpaquePointer, UInt, COpaquePointer?, Int) -> Unit>>(
                blurEff,
                SLOT_EFFECT_SET_INPUT
            )
            setInputFn(blurEff, 0u, srcBmp, 1)

            val okStdDev = setEffectFloatProperty(blurEff, D2D1_GAUSSIANBLUR_PROP_STANDARD_DEVIATION, effectiveBlurPx)
            val okOpt = setEffectUIntProperty(blurEff, D2D1_GAUSSIANBLUR_PROP_OPTIMIZATION, D2D1_GAUSSIANBLUR_OPTIMIZATION_BALANCED)
            val okBorder = setEffectUIntProperty(blurEff, D2D1_GAUSSIANBLUR_PROP_BORDER_MODE, D2D1_BORDER_MODE_HARD)
            if (!okStdDev || !okOpt || !okBorder) return false
            outputEffect = blurEff
        }

        if (filter.hasPixelOps) {
            val cmEff = colorMatrixEffect ?: return false
            if (outputEffect != null) {
                val outImg = getEffectOutputImage(outputEffect) ?: return false
                try {
                    val setInputFn = comMethod<CFunction<(COpaquePointer, UInt, COpaquePointer?, Int) -> Unit>>(
                        cmEff,
                        SLOT_EFFECT_SET_INPUT
                    )
                    setInputFn(cmEff, 0u, outImg, 1)
                } finally {
                    comRelease(outImg)
                }
            } else {
                val setInputFn = comMethod<CFunction<(COpaquePointer, UInt, COpaquePointer?, Int) -> Unit>>(
                    cmEff,
                    SLOT_EFFECT_SET_INPUT
                )
                setInputFn(cmEff, 0u, srcBmp, 1)
            }
            if (!applyCssFilterToColorMatrixEffect(cmEff, filter)) return false
            outputEffect = cmEff
        }

        val finalEffect = outputEffect ?: return false
        val outImage = getEffectOutputImage(finalEffect) ?: return false

        val drewOk = memScoped {
            val setTargetFn = comMethod<CFunction<(COpaquePointer, COpaquePointer?) -> Unit>>(ctx, SLOT_CTX_SET_TARGET)
            val beginDrawFn = comMethod<CFunction<(COpaquePointer) -> Unit>>(ctx, SLOT_RT_BEGIN_DRAW)
            val drawImageFn = comMethod<CFunction<(COpaquePointer, COpaquePointer, COpaquePointer?, COpaquePointer?, UInt, UInt) -> Unit>>(
                ctx,
                SLOT_CTX_DRAW_IMAGE
            )
            val endDrawFn = comMethod<CFunction<(COpaquePointer, COpaquePointer?, COpaquePointer?) -> Int>>(ctx, SLOT_RT_END_DRAW)

            setTargetFn(ctx, dstBmp)
            beginDrawFn(ctx)
            val srcImageRect = allocArray<FloatVar>(4)
            srcImageRect[0] = 0f
            srcImageRect[1] = 0f
            srcImageRect[2] = uploadW.toFloat()
            srcImageRect[3] = uploadH.toFloat()
            drawImageFn(ctx, outImage, null, srcImageRect, D2D1_INTERPOLATION_MODE_LINEAR, D2D1_COMPOSITE_MODE_SOURCE_COPY)
            val hrEnd = endDrawFn(ctx, null, null)
            setTargetFn(ctx, null)
            hrEnd
        }
        comRelease(outImage)
        if (drewOk < 0) {
            if (drewOk == D2DERR_RECREATE_TARGET) {
                resetGpuResources()
            }
            return false
        }

        val effOp255 = (opacity * 255f).roundToInt().coerceIn(0, 255)
        if (effOp255 <= 0) return true

        val mappedOk = memScoped {
            val srcRect = allocArray<UIntVar>(4)
            if (downsample == 1) {
                srcRect[0] = (x0 - capX0).toUInt()
                srcRect[1] = (y0 - capY0).toUInt()
                srcRect[2] = (x0 - capX0 + copyW).toUInt()
                srcRect[3] = (y0 - capY0 + copyH).toUInt()
            } else {
                srcRect[0] = 0u
                srcRect[1] = 0u
                srcRect[2] = uploadW.toUInt()
                srcRect[3] = uploadH.toUInt()
            }
            val copyFn = comMethod<CFunction<(COpaquePointer, COpaquePointer?, COpaquePointer, COpaquePointer) -> Int>>(
                stagingBmp,
                SLOT_BITMAP_COPY_FROM_BITMAP
            )
            if (copyFn(stagingBmp, null, dstBmp, srcRect) < 0) return@memScoped false

            val mappedRect = allocArray<LongVar>(2)
            val mapFn = comMethod<CFunction<(COpaquePointer, UInt, COpaquePointer) -> Int>>(stagingBmp, SLOT_BITMAP1_MAP)
            if (mapFn(stagingBmp, D2D1_MAP_OPTIONS_READ, mappedRect) < 0) return@memScoped false

            try {
                val pitchBytes = (mappedRect[0] and 0xFFFFFFFFL).toInt()
                val bitsAddr = mappedRect[1]
                val baseBytePtr = bitsAddr.toCPointer<ByteVar>() ?: return@memScoped false

                val effectiveRadius = cornerRadiusPx.coerceIn(0f, min(regionWidth, regionHeight) * 0.5f)
                val checkRounded = effectiveRadius > 0.5f

                if (downsample == 1) {
                    for (row in 0 until copyH) {
                        val py = y0 + row
                        val dstRow = py * bufW
                        val srcRowPtr = (baseBytePtr + row.toLong() * pitchBytes.toLong())!!.reinterpret<UIntVar>()
                        val localY = (py - regionY).toFloat()
                        for (col in 0 until copyW) {
                            val px = x0 + col
                            if (checkRounded && !isPointInsideRoundedRect(
                                    x = (px - regionX).toFloat(),
                                    y = localY,
                                    width = regionWidth.toFloat(),
                                    height = regionHeight.toFloat(),
                                    radius = effectiveRadius
                                )
                            ) {
                                continue
                            }
                            val word = srcRowPtr[col].toInt()
                            val fa = (word ushr 24) and 0xFF
                            if (fa <= 0) continue
                            val outA = (fa * effOp255 + 127) / 255
                            if (outA >= 255) {
                                wordsPtr[dstRow + px] = word.toUInt()
                            } else if (outA > 0) {
                                val pr = (((word ushr 16) and 0xFF) * effOp255 + 127) / 255
                                val pg = (((word ushr 8) and 0xFF) * effOp255 + 127) / 255
                                val pb = ((word and 0xFF) * effOp255 + 127) / 255
                                val orig = wordsPtr[dstRow + px].toInt()
                                val or = (orig ushr 16) and 0xFF
                                val og = (orig ushr 8) and 0xFF
                                val ob = orig and 0xFF
                                val invA = 255 - outA
                                val outR = (pr + (or * invA + 127) / 255).coerceIn(0, 255)
                                val outG = (pg + (og * invA + 127) / 255).coerceIn(0, 255)
                                val outB = (pb + (ob * invA + 127) / 255).coerceIn(0, 255)
                                wordsPtr[dstRow + px] =
                                    0xFF000000u or (outR.toUInt() shl 16) or (outG.toUInt() shl 8) or outB.toUInt()
                            }
                        }
                    }
                } else {
                    val maxDsX = (uploadW - 1).coerceAtLeast(0)
                    val maxDsY = (uploadH - 1).coerceAtLeast(0)
                    val maxDsXf = maxDsX.toFloat()
                    val maxDsYf = maxDsY.toFloat()
                    val invDs = 1f / downsample.toFloat()
                    for (row in 0 until copyH) {
                        val py = y0 + row
                        val dstRow = py * bufW
                        val localY = (py - regionY).toFloat()
                        val fy = ((py - capY0 + 0.5f) * invDs - 0.5f).coerceIn(0f, maxDsYf)
                        val iy0 = fy.toInt().coerceIn(0, maxDsY)
                        val iy1 = min(iy0 + 1, maxDsY)
                        val wy1 = ((fy - iy0) * 256f).roundToInt().coerceIn(0, 256)
                        val wy0 = 256 - wy1
                        val r0Ptr = (baseBytePtr + iy0.toLong() * pitchBytes.toLong())!!.reinterpret<UIntVar>()
                        val r1Ptr = (baseBytePtr + iy1.toLong() * pitchBytes.toLong())!!.reinterpret<UIntVar>()
                        for (col in 0 until copyW) {
                            val px = x0 + col
                            if (checkRounded && !isPointInsideRoundedRect(
                                    x = (px - regionX).toFloat(),
                                    y = localY,
                                    width = regionWidth.toFloat(),
                                    height = regionHeight.toFloat(),
                                    radius = effectiveRadius
                                )
                            ) {
                                continue
                            }
                            val fx = ((px - capX0 + 0.5f) * invDs - 0.5f).coerceIn(0f, maxDsXf)
                            val ix0 = fx.toInt().coerceIn(0, maxDsX)
                            val ix1 = min(ix0 + 1, maxDsX)
                            val wx1 = ((fx - ix0) * 256f).roundToInt().coerceIn(0, 256)
                            val wx0 = 256 - wx1

                            val c00 = r0Ptr[ix0].toInt()
                            val c10 = r0Ptr[ix1].toInt()
                            val c01 = r1Ptr[ix0].toInt()
                            val c11 = r1Ptr[ix1].toInt()

                            val w00 = wx0 * wy0
                            val w10 = wx1 * wy0
                            val w01 = wx0 * wy1
                            val w11 = wx1 * wy1

                            val fa = ((((c00 ushr 24) and 0xFF) * w00 + ((c10 ushr 24) and 0xFF) * w10 +
                                ((c01 ushr 24) and 0xFF) * w01 + ((c11 ushr 24) and 0xFF) * w11) shr 16).coerceIn(0, 255)
                            if (fa <= 0) continue
                            val pr = ((((c00 ushr 16) and 0xFF) * w00 + ((c10 ushr 16) and 0xFF) * w10 +
                                ((c01 ushr 16) and 0xFF) * w01 + ((c11 ushr 16) and 0xFF) * w11) shr 16).coerceIn(0, 255)
                            val pg = ((((c00 ushr 8) and 0xFF) * w00 + ((c10 ushr 8) and 0xFF) * w10 +
                                ((c01 ushr 8) and 0xFF) * w01 + ((c11 ushr 8) and 0xFF) * w11) shr 16).coerceIn(0, 255)
                            val pb = (((c00 and 0xFF) * w00 + (c10 and 0xFF) * w10 +
                                (c01 and 0xFF) * w01 + (c11 and 0xFF) * w11) shr 16).coerceIn(0, 255)

                            val outA = (fa * effOp255 + 127) / 255
                            if (outA >= 255) {
                                wordsPtr[dstRow + px] =
                                    0xFF000000u or (pr.toUInt() shl 16) or (pg.toUInt() shl 8) or pb.toUInt()
                            } else if (outA > 0) {
                                val sPr = (pr * effOp255 + 127) / 255
                                val sPg = (pg * effOp255 + 127) / 255
                                val sPb = (pb * effOp255 + 127) / 255
                                val orig = wordsPtr[dstRow + px].toInt()
                                val or = (orig ushr 16) and 0xFF
                                val og = (orig ushr 8) and 0xFF
                                val ob = orig and 0xFF
                                val invA = 255 - outA
                                val outR = (sPr + (or * invA + 127) / 255).coerceIn(0, 255)
                                val outG = (sPg + (og * invA + 127) / 255).coerceIn(0, 255)
                                val outB = (sPb + (ob * invA + 127) / 255).coerceIn(0, 255)
                                wordsPtr[dstRow + px] =
                                    0xFF000000u or (outR.toUInt() shl 16) or (outG.toUInt() shl 8) or outB.toUInt()
                            }
                        }
                    }
                }
                true
            } finally {
                val unmapFn = comMethod<CFunction<(COpaquePointer) -> Int>>(stagingBmp, SLOT_BITMAP1_UNMAP)
                unmapFn(stagingBmp)
            }
        }
        return mappedOk
    }

    private fun applyCpuFallback(
        filter: CssFilter,
        wordsPtr: CPointer<UIntVar>,
        bufW: Int,
        capX0: Int,
        capY0: Int,
        capX1: Int,
        capY1: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        regionX: Int,
        regionY: Int,
        regionWidth: Int,
        regionHeight: Int,
        cornerRadiusPx: Float,
        opacity: Float
    ): Boolean {
        val capW = capX1 - capX0
        val capH = capY1 - capY0
        val copyW = x1 - x0
        val copyH = y1 - y0
        if (capW <= 0 || capH <= 0 || copyW <= 0 || copyH <= 0) return false
        val effOp255 = (opacity * 255f).roundToInt().coerceIn(0, 255)
        if (effOp255 <= 0) return true

        ensureScratchSize(capW * capH)
        val subArgb = reusableCaptureBuffer
        var dstIdx = 0
        for (py in capY0 until capY1) {
            val rowOff = py * bufW
            for (px in capX0 until capX1) {
                subArgb[dstIdx++] = wordsPtr[rowOff + px].toInt()
            }
        }
        val effectiveRadius = if (x0 == regionX && y0 == regionY && x1 == regionX + regionWidth && y1 == regionY + regionHeight) {
            cornerRadiusPx.coerceAtLeast(0f)
        } else {
            0f
        }

        if (filter.hasDropShadow && filter.dropShadow != null) {
            return applyCpuDropShadowFallback(
                filter = filter,
                shadow = filter.dropShadow!!,
                subArgb = subArgb,
                wordsPtr = wordsPtr,
                bufW = bufW,
                capX0 = capX0,
                capY0 = capY0,
                capX1 = capX1,
                capY1 = capY1,
                x0 = x0,
                y0 = y0,
                x1 = x1,
                y1 = y1,
                regionX = regionX,
                regionY = regionY,
                regionWidth = regionWidth,
                regionHeight = regionHeight,
                effectiveRadius = effectiveRadius,
                effOp255 = effOp255
            )
        }

        val applied = FastBackdropBlurKernel.applyBackdropToArgbRegion(
            filter = filter,
            argbPixels = subArgb,
            bufferWidth = capW,
            bufferHeight = capH,
            regionX = x0 - capX0,
            regionY = y0 - capY0,
            regionWidth = copyW,
            regionHeight = copyH,
            cornerRadiusPx = effectiveRadius
        )
        if (!applied) return false

        for (py in y0 until y1) {
            val dstRow = py * bufW
            val srcRow = (py - capY0) * capW
            for (px in x0 until x1) {
                val filteredWord = subArgb[srcRow + (px - capX0)]
                val rawFa = (filteredWord ushr 24) and 0xFF
                val fa = if (effOp255 >= 255) rawFa else ((rawFa * effOp255 + 127) / 255)
                if (fa >= 255) {
                    wordsPtr[dstRow + px] = filteredWord.toUInt()
                } else if (fa > 0) {
                    val orig = wordsPtr[dstRow + px].toInt()
                    val da = (orig ushr 24) and 0xFF
                    if (da <= 0) {
                        wordsPtr[dstRow + px] = ((fa shl 24) or (filteredWord and 0x00FFFFFF)).toUInt()
                    } else {
                        val or = (orig ushr 16) and 0xFF
                        val og = (orig ushr 8) and 0xFF
                        val ob = orig and 0xFF
                        val fr = (filteredWord ushr 16) and 0xFF
                        val fg = (filteredWord ushr 8) and 0xFF
                        val fb = filteredWord and 0xFF
                        val invA = 255 - fa
                        val outA = (fa + (da * invA + 127) / 255).coerceIn(1, 255)
                        val outR = (fr * fa + (or * da * invA + 127) / 255 + outA / 2) / outA
                        val outG = (fg * fa + (og * da * invA + 127) / 255 + outA / 2) / outA
                        val outB = (fb * fa + (ob * da * invA + 127) / 255 + outA / 2) / outA
                        wordsPtr[dstRow + px] =
                            (outA.coerceIn(0, 255).toUInt() shl 24) or
                                (outR.coerceIn(0, 255).toUInt() shl 16) or
                                (outG.coerceIn(0, 255).toUInt() shl 8) or
                                outB.coerceIn(0, 255).toUInt()
                    }
                } else if (effOp255 >= 255) {
                    wordsPtr[dstRow + px] = 0u
                }
            }
        }
        return true
    }

    private var reusableShadowMask = FloatArray(0)
    private var reusableShadowTmp = FloatArray(0)

    private fun applyCpuDropShadowFallback(
        filter: CssFilter,
        shadow: com.nexustools.genericui.style.css.CssShadow,
        subArgb: IntArray,
        wordsPtr: CPointer<UIntVar>,
        bufW: Int,
        capX0: Int,
        capY0: Int,
        capX1: Int,
        capY1: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        regionX: Int,
        regionY: Int,
        regionWidth: Int,
        regionHeight: Int,
        effectiveRadius: Float,
        effOp255: Int
    ): Boolean {
        val capW = capX1 - capX0
        val capH = capY1 - capY0
        val count = capW * capH
        if (reusableShadowMask.size < count) reusableShadowMask = FloatArray(count)
        if (reusableShadowTmp.size < count) reusableShadowTmp = FloatArray(count)
        val mask = reusableShadowMask
        val tmp = reusableShadowTmp
        mask.fill(0f, 0, count)

        val hw = regionWidth * 0.5f
        val hh = regionHeight * 0.5f
        val rx = effectiveRadius.coerceIn(0f, min(hw, hh))
        val checkR = rx > 0.5f
        val filterOp = (filter.opacity ?: 1f).coerceIn(0f, 1f)
        val centerX = regionX + hw
        val centerY = regionY + hh

        for (py in y0 until y1) {
            val maskRow = (py - capY0) * capW
            val bufRow = py * bufW
            val dy = if (checkR) abs(py + 0.5f - centerY) - (hh - rx) else 0f
            for (px in x0 until x1) {
                val cov = if (!checkR) {
                    1f
                } else {
                    val dx = abs(px + 0.5f - centerX) - (hw - rx)
                    val qx = max(dx, 0f)
                    val qy = max(dy, 0f)
                    val sd = sqrt(qx * qx + qy * qy) + min(max(dx, dy), 0f) - rx
                    (0.5f - sd).coerceIn(0f, 1f)
                }
                if (cov <= 0f) continue
                val srcA = ((wordsPtr[bufRow + px].toInt() ushr 24) and 0xFF) / 255f
                if (srcA > 0f) {
                    mask[maskRow + (px - capX0)] = cov * srcA * filterOp
                }
            }
        }

        if (filter.hasBlur || filter.hasPixelOps) {
            val noShadowFilter = filter.copy(dropShadow = null)
            FastBackdropBlurKernel.applyBackdropToArgbRegion(
                filter = noShadowFilter,
                argbPixels = subArgb,
                bufferWidth = capW,
                bufferHeight = capH,
                regionX = x0 - capX0,
                regionY = y0 - capY0,
                regionWidth = x1 - x0,
                regionHeight = y1 - y0,
                cornerRadiusPx = effectiveRadius
            )
        }

        val blurPx = (shadow.blurRadius.resolvePx() ?: 0f).coerceIn(0f, 48f)
        val blurSigma = blurPx * 0.5f
        val radii = if (blurSigma > 0f) CssFilter.boxBlurRadiiForGaussianSigma(blurSigma) else IntArray(0)
        for (radius in radii) {
            if (radius <= 0) continue
            boxBlurFloatHorizontalZeroPadded(mask, tmp, capW, capH, radius)
            boxBlurFloatVerticalZeroPadded(tmp, mask, capW, capH, radius)
        }

        val offX = shadow.offsetX.resolvePx() ?: 0f
        val offY = shadow.offsetY.resolvePx() ?: 0f
        val scR = shadow.color.r / 255f
        val scG = shadow.color.g / 255f
        val scB = shadow.color.b / 255f
        val scA = shadow.color.alphaFloat
        val effOp = effOp255 / 255f
        val hasBaseEffects = filter.hasBlur || filter.hasPixelOps

        for (py in capY0 until capY1) {
            val dstRow = py * bufW
            val dy = if (checkR) abs(py + 0.5f - centerY) - (hh - rx) else 0f
            val insideY = py in y0 until y1
            for (px in capX0 until capX1) {
                val regionCov = if (!insideY || px !in x0 until x1) {
                    0f
                } else if (!checkR) {
                    1f
                } else {
                    val dx = abs(px + 0.5f - centerX) - (hw - rx)
                    val qx = max(dx, 0f)
                    val qy = max(dy, 0f)
                    val sd = sqrt(qx * qx + qy * qy) + min(max(dx, dy), 0f) - rx
                    (0.5f - sd).coerceIn(0f, 1f)
                }

                val maskA = sampleFloatMaskBilinearZeroOutside(
                    mask,
                    capW,
                    capH,
                    (px - capX0).toFloat() - offX,
                    (py - capY0).toFloat() - offY
                )
                val shadowA = (maskA * scA * effOp).coerceIn(0f, 1f)

                if (regionCov <= 0f) {
                    if (shadowA <= 0.002f) continue
                    val orig = wordsPtr[dstRow + px].toInt()
                    val da = ((orig ushr 24) and 0xFF) / 255f
                    val dr = ((orig ushr 16) and 0xFF) / 255f
                    val dg = ((orig ushr 8) and 0xFF) / 255f
                    val db = (orig and 0xFF) / 255f
                    val dstBehindA = da * (1f - shadowA)
                    val outA = (shadowA + dstBehindA).coerceIn(0f, 1f)
                    if (outA <= 0.0005f) continue
                    val invOut = 1f / outA
                    val outR = ((scR * shadowA + dr * dstBehindA) * invOut * 255f).roundToInt().coerceIn(0, 255)
                    val outG = ((scG * shadowA + dg * dstBehindA) * invOut * 255f).roundToInt().coerceIn(0, 255)
                    val outB = ((scB * shadowA + db * dstBehindA) * invOut * 255f).roundToInt().coerceIn(0, 255)
                    val outA255 = (outA * 255f).roundToInt().coerceIn(0, 255)
                    wordsPtr[dstRow + px] =
                        (outA255.toUInt() shl 24) or (outR.toUInt() shl 16) or (outG.toUInt() shl 8) or outB.toUInt()
                    continue
                }

                if (!hasBaseEffects && shadowA <= 0.002f) continue

                val fgWord = subArgb[(py - capY0) * capW + (px - capX0)]
                val rawFgA = ((fgWord ushr 24) and 0xFF) / 255f
                val fgA = (rawFgA * regionCov * effOp).coerceIn(0f, 1f)
                val fgR = ((fgWord ushr 16) and 0xFF) / 255f
                val fgG = ((fgWord ushr 8) and 0xFF) / 255f
                val fgB = (fgWord and 0xFF) / 255f

                val orig = wordsPtr[dstRow + px].toInt()
                val origA = ((orig ushr 24) and 0xFF) / 255f
                val dstBaseA = origA * (1f - regionCov)
                val invFg = 1f - fgA
                val shadowVisA = shadowA * invFg
                val dstVisA = dstBaseA * (1f - shadowA) * invFg
                val outA = (fgA + shadowVisA + dstVisA).coerceIn(0f, 1f)
                if (outA <= 0.0005f) {
                    wordsPtr[dstRow + px] = 0u
                    continue
                }
                val dr = ((orig ushr 16) and 0xFF) / 255f
                val dg = ((orig ushr 8) and 0xFF) / 255f
                val db = (orig and 0xFF) / 255f
                val invOut = 1f / outA
                val outR = ((fgR * fgA + scR * shadowVisA + dr * dstVisA) * invOut * 255f).roundToInt().coerceIn(0, 255)
                val outG = ((fgG * fgA + scG * shadowVisA + dg * dstVisA) * invOut * 255f).roundToInt().coerceIn(0, 255)
                val outB = ((fgB * fgA + scB * shadowVisA + db * dstVisA) * invOut * 255f).roundToInt().coerceIn(0, 255)
                val outA255 = (outA * 255f).roundToInt().coerceIn(0, 255)
                wordsPtr[dstRow + px] =
                    (outA255.toUInt() shl 24) or (outR.toUInt() shl 16) or (outG.toUInt() shl 8) or outB.toUInt()
            }
        }
        return true
    }

    private fun boxBlurFloatHorizontalZeroPadded(
        src: FloatArray,
        dst: FloatArray,
        width: Int,
        height: Int,
        radius: Int
    ) {
        val invWin = 1f / (radius * 2 + 1).toFloat()
        for (y in 0 until height) {
            val rowOff = y * width
            var sum = 0f
            val initEnd = min(radius, width - 1)
            for (k in 0..initEnd) {
                sum += src[rowOff + k]
            }
            for (x in 0 until width) {
                dst[rowOff + x] = (sum * invWin).coerceIn(0f, 1f)
                val outX = x - radius
                if (outX >= 0) sum -= src[rowOff + outX]
                val inX = x + radius + 1
                if (inX < width) sum += src[rowOff + inX]
            }
        }
    }

    private fun boxBlurFloatVerticalZeroPadded(
        src: FloatArray,
        dst: FloatArray,
        width: Int,
        height: Int,
        radius: Int
    ) {
        val invWin = 1f / (radius * 2 + 1).toFloat()
        for (x in 0 until width) {
            var sum = 0f
            val initEnd = min(radius, height - 1)
            for (k in 0..initEnd) {
                sum += src[k * width + x]
            }
            for (y in 0 until height) {
                dst[y * width + x] = (sum * invWin).coerceIn(0f, 1f)
                val outY = y - radius
                if (outY >= 0) sum -= src[outY * width + x]
                val inY = y + radius + 1
                if (inY < height) sum += src[inY * width + x]
            }
        }
    }

    private fun sampleFloatMaskBilinearZeroOutside(
        mask: FloatArray,
        width: Int,
        height: Int,
        fx: Float,
        fy: Float
    ): Float {
        if (fx <= -1f || fy <= -1f || fx >= width.toFloat() || fy >= height.toFloat()) return 0f
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val tx = fx - x0
        val ty = fy - y0
        val x1 = x0 + 1
        val y1 = y0 + 1
        val m00 = if (x0 in 0 until width && y0 in 0 until height) mask[y0 * width + x0] else 0f
        val m10 = if (x1 in 0 until width && y0 in 0 until height) mask[y0 * width + x1] else 0f
        val m01 = if (x0 in 0 until width && y1 in 0 until height) mask[y1 * width + x0] else 0f
        val m11 = if (x1 in 0 until width && y1 in 0 until height) mask[y1 * width + x1] else 0f
        val top = m00 + (m10 - m00) * tx
        val bottom = m01 + (m11 - m01) * tx
        return (top + (bottom - top) * ty).coerceIn(0f, 1f)
    }

    private fun ensureGpuInitialized(): Boolean {
        if (initAttempted) return gpuAvailable
        initAttempted = true

        val d3d = GetModuleHandleW("d3d11.dll") ?: LoadLibraryW("d3d11.dll")
        val d2d = GetModuleHandleW("d2d1.dll") ?: LoadLibraryW("d2d1.dll")
        d3d11Module = d3d
        d2d1Module = d2d
        if (d3d == null || d2d == null) return false

        val fnD3dCreate = GetProcAddress(d3d, "D3D11CreateDevice")?.reinterpret<
            CFunction<(COpaquePointer?, UInt, COpaquePointer?, UInt, COpaquePointer?, UInt, UInt, CPointer<COpaquePointerVar>?, CPointer<UIntVar>?, CPointer<COpaquePointerVar>?) -> Int>
            >() ?: return false
        val fnD2dCreateFactory = GetProcAddress(d2d, "D2D1CreateFactory")?.reinterpret<
            CFunction<(UInt, COpaquePointer, COpaquePointer?, CPointer<COpaquePointerVar>) -> Int>
            >() ?: return false

        val ok = memScoped {
            val d3dDevOut = alloc<COpaquePointerVar>()
            val hrD3d = fnD3dCreate(
                null,
                D3D_DRIVER_TYPE_HARDWARE,
                null,
                D3D11_CREATE_DEVICE_BGRA_SUPPORT,
                null,
                0u,
                D3D11_SDK_VERSION,
                d3dDevOut.ptr,
                null,
                null
            )
            if (hrD3d < 0) return@memScoped false
            val devPtr = d3dDevOut.value ?: return@memScoped false
            d3d11Device = devPtr

            val iidDxgi = alloc<GUID>()
            fillGuid(iidDxgi, 0x54ec77fau, 0x1377u, 0x44e6u, 0x8cu, 0x32u, 0x88u, 0xfdu, 0x5fu, 0x44u, 0xc8u, 0x4cu)
            val dxgiOut = alloc<COpaquePointerVar>()
            val qiFn = comMethod<CFunction<(COpaquePointer, COpaquePointer, CPointer<COpaquePointerVar>) -> Int>>(devPtr, SLOT_UNKNOWN_QI)
            if (qiFn(devPtr, iidDxgi.ptr, dxgiOut.ptr) < 0) return@memScoped false
            val dxgiPtr = dxgiOut.value ?: return@memScoped false
            dxgiDevice = dxgiPtr

            val iidFactory1 = alloc<GUID>()
            fillGuid(iidFactory1, 0xbb12d362u, 0xdaeeu, 0x4b9au, 0xaau, 0x1du, 0x14u, 0xbau, 0x40u, 0x1cu, 0xfau, 0x1fu)
            val facOut = alloc<COpaquePointerVar>()
            if (fnD2dCreateFactory(D2D1_FACTORY_TYPE_SINGLE_THREADED, iidFactory1.ptr, null, facOut.ptr) < 0) {
                return@memScoped false
            }
            val facPtr = facOut.value ?: return@memScoped false
            d2dFactory1 = facPtr

            val createDevFn = comMethod<CFunction<(COpaquePointer, COpaquePointer, CPointer<COpaquePointerVar>) -> Int>>(
                facPtr,
                SLOT_FACTORY1_CREATE_DEVICE
            )
            val d2dDevOut = alloc<COpaquePointerVar>()
            if (createDevFn(facPtr, dxgiPtr, d2dDevOut.ptr) < 0) return@memScoped false
            val d2dDevPtr = d2dDevOut.value ?: return@memScoped false
            d2dDevice = d2dDevPtr

            val createCtxFn = comMethod<CFunction<(COpaquePointer, UInt, CPointer<COpaquePointerVar>) -> Int>>(
                d2dDevPtr,
                SLOT_DEVICE_CREATE_DEVICE_CONTEXT
            )
            val ctxOut = alloc<COpaquePointerVar>()
            if (createCtxFn(d2dDevPtr, 0u, ctxOut.ptr) < 0) return@memScoped false
            val ctxPtr = ctxOut.value ?: return@memScoped false
            d2dContext = ctxPtr

            val createEffectFn = comMethod<CFunction<(COpaquePointer, COpaquePointer, CPointer<COpaquePointerVar>) -> Int>>(
                ctxPtr,
                SLOT_CTX_CREATE_EFFECT
            )
            val clsidBlur = alloc<GUID>()
            fillGuid(clsidBlur, 0x1feb6d69u, 0x2fe6u, 0x4ac9u, 0x8cu, 0x58u, 0x1du, 0x7fu, 0x93u, 0xe7u, 0xa6u, 0xa5u)
            val blurOut = alloc<COpaquePointerVar>()
            if (createEffectFn(ctxPtr, clsidBlur.ptr, blurOut.ptr) < 0) return@memScoped false
            gaussianBlurEffect = blurOut.value ?: return@memScoped false

            val clsidColorMat = alloc<GUID>()
            fillGuid(clsidColorMat, 0x921f03d6u, 0x641cu, 0x47dfu, 0x85u, 0x2du, 0xb4u, 0xbbu, 0x61u, 0x53u, 0xaeu, 0x11u)
            val cmOut = alloc<COpaquePointerVar>()
            if (createEffectFn(ctxPtr, clsidColorMat.ptr, cmOut.ptr) < 0) return@memScoped false
            colorMatrixEffect = cmOut.value ?: return@memScoped false
            true
        }

        if (!ok) {
            resetGpuResources()
            return false
        }
        gpuAvailable = true
        return true
    }

    private fun resetGpuResources() {
        comRelease(cachedStagingBitmap); cachedStagingBitmap = null; cachedStagingW = 0; cachedStagingH = 0
        comRelease(cachedTargetBitmap); cachedTargetBitmap = null
        comRelease(cachedSrcBitmap); cachedSrcBitmap = null; cachedCapW = 0; cachedCapH = 0
        comRelease(colorMatrixEffect); colorMatrixEffect = null
        comRelease(gaussianBlurEffect); gaussianBlurEffect = null
        comRelease(d2dContext); d2dContext = null
        comRelease(d2dDevice); d2dDevice = null
        comRelease(d2dFactory1); d2dFactory1 = null
        comRelease(dxgiDevice); dxgiDevice = null
        comRelease(d3d11Device); d3d11Device = null
        gpuAvailable = false
        initAttempted = false
    }

    private fun ensureBitmaps(ctx: COpaquePointer, capW: Int, capH: Int, copyW: Int, copyH: Int): Boolean {
        if (cachedSrcBitmap == null || cachedTargetBitmap == null || capW != cachedCapW || capH != cachedCapH) {
            comRelease(cachedSrcBitmap); cachedSrcBitmap = null
            comRelease(cachedTargetBitmap); cachedTargetBitmap = null
            val src = createD2dBitmap1(ctx, capW, capH, D2D1_BITMAP_OPTIONS_NONE) ?: return false
            val dst = createD2dBitmap1(ctx, capW, capH, D2D1_BITMAP_OPTIONS_TARGET) ?: run {
                comRelease(src)
                return false
            }
            cachedSrcBitmap = src
            cachedTargetBitmap = dst
            cachedCapW = capW
            cachedCapH = capH
        }
        if (cachedStagingBitmap == null || copyW != cachedStagingW || copyH != cachedStagingH) {
            comRelease(cachedStagingBitmap); cachedStagingBitmap = null
            val stg = createD2dBitmap1(ctx, copyW, copyH, D2D1_BITMAP_OPTIONS_CPU_READ or D2D1_BITMAP_OPTIONS_CANNOT_DRAW)
                ?: return false
            cachedStagingBitmap = stg
            cachedStagingW = copyW
            cachedStagingH = copyH
        }
        return true
    }

    private fun createD2dBitmap1(ctx: COpaquePointer, w: Int, h: Int, options: UInt): COpaquePointer? = memScoped {
        // D2D1_BITMAP_PROPERTIES1 (Win64 = 32 bytes)
        val props = allocArray<UIntVar>(8)
        props[0] = DXGI_FORMAT_B8G8R8A8_UNORM
        props[1] = D2D1_ALPHA_MODE_PREMULTIPLIED
        props[2] = 0x42C00000u // 96.0f
        props[3] = 0x42C00000u // 96.0f
        props[4] = options
        props[5] = 0u
        props[6] = 0u
        props[7] = 0u

        val sizePacked = (w.toULong() and 0xFFFFFFFFuL) or ((h.toULong() and 0xFFFFFFFFuL) shl 32)
        val outBmp = alloc<COpaquePointerVar>()
        val fn = comMethod<CFunction<(COpaquePointer, ULong, COpaquePointer?, UInt, COpaquePointer, CPointer<COpaquePointerVar>) -> Int>>(
            ctx,
            SLOT_CTX_CREATE_BITMAP
        )
        val hr = fn(ctx, sizePacked, null, 0u, props, outBmp.ptr)
        if (hr >= 0) outBmp.value else null
    }

    private fun setEffectFloatProperty(effect: COpaquePointer, propId: UInt, value: Float): Boolean = memScoped {
        val data = alloc<FloatVar>()
        data.value = value
        val setVal = comMethod<CFunction<(COpaquePointer, UInt, UInt, COpaquePointer, UInt) -> Int>>(effect, SLOT_PROPS_SET_VALUE)
        setVal(effect, propId, 0u, data.ptr, 4u) >= 0
    }

    private fun setEffectUIntProperty(effect: COpaquePointer, propId: UInt, value: UInt): Boolean = memScoped {
        val data = alloc<UIntVar>()
        data.value = value
        val setVal = comMethod<CFunction<(COpaquePointer, UInt, UInt, COpaquePointer, UInt) -> Int>>(effect, SLOT_PROPS_SET_VALUE)
        setVal(effect, propId, 0u, data.ptr, 4u) >= 0
    }

    private fun getEffectOutputImage(effect: COpaquePointer): COpaquePointer? = memScoped {
        val out = alloc<COpaquePointerVar>()
        val fn = comMethod<CFunction<(COpaquePointer, CPointer<COpaquePointerVar>) -> Unit>>(effect, SLOT_EFFECT_GET_OUTPUT)
        fn(effect, out.ptr)
        out.value
    }

    private fun applyCssFilterToColorMatrixEffect(effect: COpaquePointer, filter: CssFilter): Boolean {
        val m = buildRowMajorColorMatrix5x4(filter)
        val okMat = memScoped {
            val matBuf = allocArray<FloatVar>(20)
            // Transpose 4x4 linear block + keep row 4 offset for Direct2D's row-vector convention: out = in * M.
            for (r in 0 until 4) {
                for (c in 0 until 4) {
                    matBuf[c * 4 + r] = m[r * 5 + c]
                }
            }
            for (c in 0 until 4) {
                matBuf[16 + c] = m[c * 5 + 4]
            }
            val setVal = comMethod<CFunction<(COpaquePointer, UInt, UInt, COpaquePointer, UInt) -> Int>>(effect, SLOT_PROPS_SET_VALUE)
            setVal(effect, D2D1_COLORMATRIX_PROP_COLOR_MATRIX, 0u, matBuf, 80u) >= 0
        }
        if (!okMat) return false
        val okAlpha = setEffectUIntProperty(effect, D2D1_COLORMATRIX_PROP_ALPHA_MODE, D2D1_COLORMATRIX_ALPHA_MODE_PREMULTIPLIED)
        val okClamp = setEffectUIntProperty(effect, D2D1_COLORMATRIX_PROP_CLAMP_OUTPUT, 1u)
        return okAlpha && okClamp
    }

    private fun buildRowMajorColorMatrix5x4(filter: CssFilter): FloatArray {
        val mat = FloatArray(20).also {
            it[0] = 1f; it[6] = 1f; it[12] = 1f; it[18] = 1f
        }
        val gs = filter.grayscale
        if (gs != null && gs != 0f) {
            val g = gs.coerceIn(0f, 1f)
            val ig = 1f - g
            multiply5x4(
                mat,
                floatArrayOf(
                    ig + 0.2126f * g, 0.7152f * g, 0.0722f * g, 0f, 0f,
                    0.2126f * g, ig + 0.7152f * g, 0.0722f * g, 0f, 0f,
                    0.2126f * g, 0.7152f * g, ig + 0.0722f * g, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        }
        val sp = filter.sepia
        if (sp != null && sp != 0f) {
            val s = sp.coerceIn(0f, 1f)
            val inv = 1f - s
            multiply5x4(
                mat,
                floatArrayOf(
                    inv + 0.393f * s, 0.769f * s, 0.189f * s, 0f, 0f,
                    0.349f * s, inv + 0.686f * s, 0.168f * s, 0f, 0f,
                    0.272f * s, 0.534f * s, inv + 0.131f * s, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        }
        val sat = filter.saturate
        if (sat != null && sat != 1f) {
            val st = sat.coerceAtLeast(0f)
            val ist = 1f - st
            multiply5x4(
                mat,
                floatArrayOf(
                    st + 0.2126f * ist, 0.7152f * ist, 0.0722f * ist, 0f, 0f,
                    0.2126f * ist, st + 0.7152f * ist, 0.0722f * ist, 0f, 0f,
                    0.2126f * ist, 0.7152f * ist, st + 0.0722f * ist, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        }
        val hueDeg = filter.hueRotateDeg
        if (hueDeg != null && hueDeg != 0f) {
            val rad = hueDeg * (PI.toFloat() / 180f)
            val c = cos(rad)
            val s = sin(rad)
            multiply5x4(
                mat,
                floatArrayOf(
                    0.213f + c * 0.787f - s * 0.213f, 0.715f - c * 0.715f - s * 0.715f, 0.072f - c * 0.072f + s * 0.928f, 0f, 0f,
                    0.213f - c * 0.213f + s * 0.143f, 0.715f + c * 0.285f + s * 0.140f, 0.072f - c * 0.072f - s * 0.283f, 0f, 0f,
                    0.213f - c * 0.213f - s * 0.787f, 0.715f - c * 0.715f + s * 0.715f, 0.072f + c * 0.928f + s * 0.072f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        }
        val brVal = filter.brightness
        val invVal = filter.invert
        val brBeforeInv = brVal == 0f && invVal != null && invVal != 0f
        if (brBeforeInv) {
            val br = brVal!!.coerceAtLeast(0f)
            multiply5x4(
                mat,
                floatArrayOf(
                    br, 0f, 0f, 0f, 0f,
                    0f, br, 0f, 0f, 0f,
                    0f, 0f, br, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        }
        if (invVal != null && invVal != 0f) {
            val iv = invVal.coerceIn(0f, 1f)
            val scale = 1f - 2f * iv
            multiply5x4(
                mat,
                floatArrayOf(
                    scale, 0f, 0f, 0f, iv,
                    0f, scale, 0f, 0f, iv,
                    0f, 0f, scale, 0f, iv,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        }
        if (!brBeforeInv && brVal != null && brVal != 1f) {
            val br = brVal.coerceAtLeast(0f)
            multiply5x4(
                mat,
                floatArrayOf(
                    br, 0f, 0f, 0f, 0f,
                    0f, br, 0f, 0f, 0f,
                    0f, 0f, br, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        }
        val ctVal = filter.contrast
        if (ctVal != null && ctVal != 1f) {
            val ct = ctVal.coerceAtLeast(0f)
            val off = 0.5f * (1f - ct)
            multiply5x4(
                mat,
                floatArrayOf(
                    ct, 0f, 0f, 0f, off,
                    0f, ct, 0f, 0f, off,
                    0f, 0f, ct, 0f, off,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        }
        val opVal = filter.opacity
        if (opVal != null && opVal != 1f) {
            val op = opVal.coerceIn(0f, 1f)
            multiply5x4(
                mat,
                floatArrayOf(
                    1f, 0f, 0f, 0f, 0f,
                    0f, 1f, 0f, 0f, 0f,
                    0f, 0f, 1f, 0f, 0f,
                    0f, 0f, 0f, op, 0f
                )
            )
        }
        return mat
    }

    private fun multiply5x4(acc: FloatArray, next: FloatArray) {
        val prev = acc.copyOf()
        for (r in 0 until 4) {
            for (c in 0 until 4) {
                acc[r * 5 + c] =
                    next[r * 5 + 0] * prev[0 * 5 + c] +
                    next[r * 5 + 1] * prev[1 * 5 + c] +
                    next[r * 5 + 2] * prev[2 * 5 + c] +
                    next[r * 5 + 3] * prev[3 * 5 + c]
            }
            acc[r * 5 + 4] =
                next[r * 5 + 0] * prev[0 * 5 + 4] +
                next[r * 5 + 1] * prev[1 * 5 + 4] +
                next[r * 5 + 2] * prev[2 * 5 + 4] +
                next[r * 5 + 3] * prev[3 * 5 + 4] +
                next[r * 5 + 4]
        }
    }

    private fun ensureScratchSize(needed: Int) {
        if (needed <= reusableCaptureBuffer.size) return
        var cap = max(4096, reusableCaptureBuffer.size)
        while (cap < needed) cap = cap shl 1
        reusableCaptureBuffer = IntArray(cap)
    }

    private fun isPointInsideRoundedRect(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float
    ): Boolean {
        val px = x + 0.5f
        val py = y + 0.5f
        val cx = when {
            px < radius -> radius
            px > width - radius -> width - radius
            else -> return true
        }
        val cy = when {
            py < radius -> radius
            py > height - radius -> height - radius
            else -> return true
        }
        val dx = px - cx
        val dy = py - cy
        return (dx * dx + dy * dy) <= (radius * radius)
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

    private const val D3D_DRIVER_TYPE_HARDWARE = 1u
    private const val D3D11_CREATE_DEVICE_BGRA_SUPPORT = 0x20u
    private const val D3D11_SDK_VERSION = 7u
    private const val D2D1_FACTORY_TYPE_SINGLE_THREADED = 0u
    private const val DXGI_FORMAT_B8G8R8A8_UNORM = 87u
    private const val D2D1_ALPHA_MODE_PREMULTIPLIED = 1u
    private const val D2D1_BITMAP_OPTIONS_NONE = 0u
    private const val D2D1_BITMAP_OPTIONS_TARGET = 1u
    private const val D2D1_BITMAP_OPTIONS_CANNOT_DRAW = 2u
    private const val D2D1_BITMAP_OPTIONS_CPU_READ = 4u
    private const val D2D1_MAP_OPTIONS_READ = 1u
    private const val D2D1_INTERPOLATION_MODE_LINEAR = 1u
    private const val D2D1_COMPOSITE_MODE_SOURCE_COPY = 10u
    private const val D2D1_GAUSSIANBLUR_PROP_STANDARD_DEVIATION = 0u
    private const val D2D1_GAUSSIANBLUR_PROP_OPTIMIZATION = 1u
    private const val D2D1_GAUSSIANBLUR_PROP_BORDER_MODE = 2u
    private const val D2D1_GAUSSIANBLUR_OPTIMIZATION_BALANCED = 1u
    private const val D2D1_BORDER_MODE_HARD = 1u
    private const val D2D1_COLORMATRIX_PROP_COLOR_MATRIX = 0u
    private const val D2D1_COLORMATRIX_PROP_ALPHA_MODE = 1u
    private const val D2D1_COLORMATRIX_PROP_CLAMP_OUTPUT = 2u
    private const val D2D1_COLORMATRIX_ALPHA_MODE_PREMULTIPLIED = 1u
    private const val D2DERR_RECREATE_TARGET = -2003238900 // 0x8899000C

    private const val SLOT_UNKNOWN_QI = 0
    private const val SLOT_UNKNOWN_RELEASE = 2
    private const val SLOT_FACTORY1_CREATE_DEVICE = 17
    private const val SLOT_DEVICE_CREATE_DEVICE_CONTEXT = 4
    private const val SLOT_BITMAP_COPY_FROM_BITMAP = 8
    private const val SLOT_BITMAP_COPY_FROM_MEMORY = 10
    private const val SLOT_BITMAP1_MAP = 14
    private const val SLOT_BITMAP1_UNMAP = 15
    private const val SLOT_PROPS_SET_VALUE = 9
    private const val SLOT_EFFECT_SET_INPUT = 14
    private const val SLOT_EFFECT_GET_OUTPUT = 18
    private const val SLOT_RT_BEGIN_DRAW = 48
    private const val SLOT_RT_END_DRAW = 49
    private const val SLOT_CTX_CREATE_BITMAP = 57
    private const val SLOT_CTX_CREATE_EFFECT = 63
    private const val SLOT_CTX_SET_TARGET = 74
    private const val SLOT_CTX_DRAW_IMAGE = 83
}
