@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopInteractiveSurfaceSpec
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.windows.BITMAPINFO
import platform.windows.BITMAPINFOHEADER
import platform.windows.BI_RGB
import platform.windows.BitBlt
import platform.windows.CreateCompatibleDC
import platform.windows.CreateDIBSection
import platform.windows.DIB_RGB_COLORS
import platform.windows.DeleteDC
import platform.windows.DeleteObject
import platform.windows.GetClientRect
import platform.windows.GdiFlush
import platform.windows.HBITMAP
import platform.windows.HDC
import platform.windows.HGDIOBJ
import platform.windows.HWND
import platform.windows.InvalidateRect
import platform.windows.IsWindow
import platform.windows.MapWindowPoints
import platform.windows.POINT
import platform.windows.RECT
import platform.windows.SRCCOPY
import platform.windows.SelectObject
import platform.windows.SetStretchBltMode
import platform.windows.StretchDIBits
import platform.windows.STRETCH_HALFTONE
import platform.windows.WS_CHILD
import platform.windows.WS_CLIPSIBLINGS
import platform.windows.WS_VISIBLE
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Retained child `HWND` (`"GenericUIInteractiveSurfaceW"`) controller for live pixel/hardware surfaces
 * (`DesktopInteractiveSurfaceSpec` — e.g. `:extensions:media` LibVLC video stage, `:extensions:canvas2d`,
 * and `:extensions:canvas3d`).
 *
 * Uses double-buffered 32-bpp top-down DIBs with aspect-ratio letterboxing, zero-copy BGRA pointer
 * sampling, isolated stage swap-count tracking, and synchronous pixel export for translucent
 * `Win32LayeredStageHostHwnd` overlays.
 */
object Win32InteractiveSurfaceHwnd {

    private class SurfaceDibCache(
        var dc: HDC? = null,
        var bmp: HBITMAP? = null,
        var oldBmp: HGDIOBJ? = null,
        var pixels: CPointer<UIntVar>? = null,
        var width: Int = 0,
        var height: Int = 0,
        var lastSwapCount: Long = -1L
    ) {
        fun release() {
            oldBmp?.let { old -> dc?.let { d -> SelectObject(d, old) } }
            oldBmp = null
            bmp?.let { DeleteObject(it) }
            bmp = null
            dc?.let { DeleteDC(it) }
            dc = null
            pixels = null
            width = 0
            height = 0
        }
    }

    private val surfaceCachesByHwnd = HashMap<Long, SurfaceDibCache>()
    private val trackedSurfaceHandles = LinkedHashSet<Win32HwndHandle>()

    /**
     * Lazily attaches a genuine `"GenericUIInteractiveSurfaceW"` child `HWND` onto a handle whose
     * [DesktopInteractiveSurfaceSpec] was attached by an extension module (e.g. `Canvas2D`, `Video`).
     */
    fun ensureSurfaceChildHwnd(
        handle: Win32HwndHandle,
        owner: DesktopBaseNativeImpl<*>
    ): HWND? {
        if (handle.interactiveSurfaceSpec == null &&
            handle.attributes["captureBgraPtr"] == null &&
            handle.attributes["captureBgra"] == null &&
            handle.attributes["captureRgba"] == null &&
            !handle.nativeClassName.contains("Canvas", ignoreCase = true) &&
            !handle.nativeClassName.contains("Video", ignoreCase = true)
        ) {
            return handle.hwnd
        }

        val existing = handle.hwnd
        if (existing != null && IsWindow(existing) != 0) {
            trackedSurfaceHandles.add(handle)
            handle.customPaintCallback = { hwnd, hdc, w, h ->
                paintSurfaceToHdc(handle, owner, hwnd, hdc, w, h)
            }
            return existing
        }

        val created = Win32ControlMessageRouter.createChildHwnd(
            handle = handle,
            owner = owner,
            win32ClassName = "GenericUIInteractiveSurfaceW",
            dwStyle = WS_CHILD or WS_VISIBLE or WS_CLIPSIBLINGS,
            initialText = "",
            initialWidth = handle.bounds.width.coerceAtLeast(1),
            initialHeight = handle.bounds.height.coerceAtLeast(1),
            installSubclass = false
        )
        handle.customPaintCallback = { hwnd, hdc, w, h ->
            paintSurfaceToHdc(handle, owner, hwnd, hdc, w, h)
        }
        trackedSurfaceHandles.add(handle)
        return created
    }

    fun releaseSurfaceResources(handle: Win32HwndHandle) {
        trackedSurfaceHandles.remove(handle)
        handle.hwnd?.let { hwnd ->
            surfaceCachesByHwnd.remove(hwnd.toLong())?.release()
        }
    }

    /**
     * Paints a surface child `HWND`'s latest frame cleanly into [hdc], respecting
     * [DesktopInteractiveSurfaceSpec] presentation geometry and updating VBlank video swap metrics.
     */
    fun paintSurfaceToHdc(
        handle: Win32HwndHandle,
        owner: DesktopBaseNativeImpl<*>,
        hwnd: HWND,
        hdc: HDC,
        width: Int,
        height: Int
    ) {
        if (width <= 0 || height <= 0) return
        val spec = resolveSpec(handle)
        val targetCache = surfaceCachesByHwnd.getOrPut(hwnd.toLong()) { SurfaceDibCache() }
        val dibPixels = ensureDibForCache(hdc, targetCache, width, height) ?: return
        val dibDc = targetCache.dc ?: return

        val total = width * height
        for (i in 0 until total) {
            dibPixels[i] = 0xFF000000u
        }

        if (spec != null) {
            val presented = presentSpecToDibDc(handle, spec, dibDc, width, height)
            if (presented != null) {
                val actualRect = presented.rect
                if (presented.blitted) {
                    // Force opaque alpha on composited stage pixels
                    GdiFlush()
                    val startY = actualRect.clippedDstY.coerceAtLeast(0)
                    val endY = min(height, actualRect.clippedDstY + actualRect.clippedDstH)
                    val startX = actualRect.clippedDstX.coerceAtLeast(0)
                    val endX = min(width, actualRect.clippedDstX + actualRect.clippedDstW)
                    for (py in startY until endY) {
                        val row = py * width
                        for (px in startX until endX) {
                            dibPixels[row + px] = dibPixels[row + px] or 0xFF000000u
                        }
                    }
                }
                targetCache.lastSwapCount = presented.swapCount
                if (spec.isActiveStream() || spec.shouldPaintBlackBackdrop()) {
                    val vx = actualRect.clippedDstX
                    val vy = actualRect.clippedDstY
                    val vw = actualRect.clippedDstW
                    val vh = actualRect.clippedDstH
                    val topWindow = Win32NativeWindowHost.activeHwnd
                    val mapped = if (topWindow != null && IsWindow(topWindow) != 0) {
                        memScoped {
                            val pt = alloc<POINT>()
                            pt.x = vx
                            pt.y = vy
                            MapWindowPoints(hwnd, topWindow, pt.ptr, 1u)
                            intArrayOf(pt.x, pt.y, pt.x + vw, pt.y + vh)
                        }
                    } else {
                        intArrayOf(vx, vy, vx + vw, vy + vh)
                    }
                    Win32NativeWindowHost.recordVideoStageSwap(
                        presented.swapCount,
                        mapped[0],
                        mapped[1],
                        mapped[2],
                        mapped[3]
                    )
                }
            }
        }

        GdiFlush()
        BitBlt(hdc, 0, 0, width, height, dibDc, 0, 0, SRCCOPY)
    }

    /**
     * Renders [spec] directly into an ARGB top-down pixel array `[0 .. dstW * dstH)` (filling letterbox
     * borders with opaque black) for [Win32LayeredStageHostHwnd] backdrop compositing.
     */
    fun renderSpecIntoArgbBuffer(
        handle: Win32HwndHandle,
        spec: DesktopInteractiveSurfaceSpec,
        dstArgb: IntArray,
        dstW: Int,
        dstH: Int,
        refDc: HDC? = null
    ) {
        if (dstW <= 0 || dstH <= 0 || dstArgb.size < dstW * dstH) return
        val cache = surfaceCachesByHwnd.getOrPut(handle.nativePointer) { SurfaceDibCache() }
        val dib = ensureDibForCache(refDc, cache, dstW, dstH) ?: return
        val dibDc = cache.dc ?: return

        for (i in 0 until dstW * dstH) {
            dib[i] = 0xFF000000u
        }
        val presented = presentSpecToDibDc(handle, spec, dibDc, dstW, dstH)
        if (presented != null) {
            cache.lastSwapCount = presented.swapCount
        }
        GdiFlush()
        for (i in 0 until dstW * dstH) {
            dstArgb[i] = dib[i].toInt() or 0xFF000000.toInt()
        }
    }

    private data class PresentedFrame(
        val rect: DesktopInteractiveSurfaceSpec.PresentationRect,
        val swapCount: Long,
        val blitted: Boolean
    )

    private fun presentSpecToDibDc(
        handle: Win32HwndHandle,
        spec: DesktopInteractiveSurfaceSpec,
        dibDc: HDC,
        containerW: Int,
        containerH: Int
    ): PresentedFrame? {
        val isUnconfiguredCanvas = handle.nativeClassName.contains("Canvas", ignoreCase = true) &&
            spec.defaultAspectRatio == null &&
            spec.contentAspectRatio == null &&
            spec.contentFitMode == null
        val srcW = spec.surfaceWidth().coerceAtLeast(1)
        val srcH = spec.surfaceHeight().coerceAtLeast(1)
        val initialRect = if (isUnconfiguredCanvas) {
            null
        } else {
            DesktopInteractiveSurfaceSpec.computePresentationRect(
                containerW.toDouble(),
                containerH.toDouble(),
                srcW,
                srcH,
                spec
            )
        }
        if (initialRect != null && (initialRect.clippedDstW <= 0 || initialRect.clippedDstH <= 0)) {
            return null
        }

        val resizeW = initialRect?.dstW?.roundToInt()?.coerceAtLeast(1) ?: containerW
        val resizeH = initialRect?.dstH?.roundToInt()?.coerceAtLeast(1) ?: containerH
        spec.resizeSurface?.invoke(resizeW, resizeH)

        val sc = spec.swapCount()
        spec.dispatchBeforePresentFrame(sc, null)

        val actualSrcW = spec.surfaceWidth().coerceAtLeast(0)
        val actualSrcH = spec.surfaceHeight().coerceAtLeast(0)
        val actualRect = if (initialRect != null && (actualSrcW <= 0 || actualSrcH <= 0 || (actualSrcW == srcW && actualSrcH == srcH))) {
            initialRect
        } else {
            DesktopInteractiveSurfaceSpec.computePresentationRect(
                containerW.toDouble(),
                containerH.toDouble(),
                actualSrcW.coerceAtLeast(1),
                actualSrcH.coerceAtLeast(1),
                spec
            )
        }

        var blitted = false
        if (actualSrcW > 0 && actualSrcH > 0 && actualRect.clippedDstW > 0 && actualRect.clippedDstH > 0) {
            blitSpecPixelsIntoHdc(
                spec = spec,
                srcW = actualSrcW,
                srcH = actualSrcH,
                srcX = actualRect.srcX,
                srcY = actualRect.srcY,
                srcCropW = actualRect.srcW,
                srcCropH = actualRect.srcH,
                dstDc = dibDc,
                dstX = actualRect.clippedDstX,
                dstY = actualRect.clippedDstY,
                dstW = actualRect.clippedDstW,
                dstH = actualRect.clippedDstH
            )
            blitted = true
        }
        return PresentedFrame(actualRect, sc, blitted)
    }

    /**
     * Steps all mounted active interactive/video surface `HWND`s on a VBlank tick, returning `true`
     * when at least one surface swapped and scheduled an isolated stage repaint.
     */
    fun stepVBlankSurfaces(nowMs: Double?): Boolean {
        var anySwapped = false
        val snapshot = trackedSurfaceHandles.toList()
        for (handle in snapshot) {
            if (handle.disposed) {
                releaseSurfaceResources(handle)
                continue
            }
            val hwnd = handle.hwnd ?: continue
            if (IsWindow(hwnd) == 0) continue
            val spec = resolveSpec(handle) ?: continue
            val isActive = spec.isActiveStream()
            val sc = spec.swapCount()
            spec.dispatchBeforePresentFrame(sc, nowMs)
            val newSc = spec.swapCount()
            val cache = surfaceCachesByHwnd[hwnd.toLong()]
            if (isActive || cache == null || cache.lastSwapCount != newSc) {
                anySwapped = true
                val parent = handle.parentHwnd
                if (parent != null && Win32LayeredStageHostHwnd.isStageHostHwnd(parent)) {
                    Win32LayeredStageHostHwnd.notifyStageSurfaceFrameAdvanced(parent)
                } else {
                    InvalidateRect(hwnd, null, 0)
                }
            }
        }
        return anySwapped
    }

    fun resolveSpec(handle: Win32HwndHandle): DesktopInteractiveSurfaceSpec? =
        handle.interactiveSurfaceSpec
            ?: (handle.attributes["interactiveSurfaceSpec"] as? DesktopInteractiveSurfaceSpec)

    private fun blitSpecPixelsIntoHdc(
        spec: DesktopInteractiveSurfaceSpec,
        srcW: Int,
        srcH: Int,
        srcX: Int,
        srcY: Int,
        srcCropW: Int,
        srcCropH: Int,
        dstDc: HDC,
        dstX: Int,
        dstY: Int,
        dstW: Int,
        dstH: Int
    ) {
        if (srcCropW <= 0 || srcCropH <= 0 || dstW <= 0 || dstH <= 0) return
        val ptrPair = spec.acquireBgraPtr?.invoke()
        val rawPtr = ptrPair?.first
        val strideBytes = DesktopInteractiveSurfaceSpec.resolveBgraRowStrideBytes(srcW, srcH, ptrPair?.second ?: 0)
        val bytePtr: CPointer<ByteVar>? = when (rawPtr) {
            is CPointer<*> -> rawPtr.reinterpret()
            is Long -> if (rawPtr != 0L) rawPtr.toCPointer() else null
            else -> null
        }

        if (bytePtr != null && strideBytes >= srcW * 4) {
            drawRawBgraPtrToDc(bytePtr, strideBytes, srcW, srcH, srcX, srcY, srcCropW, srcCropH, dstDc, dstX, dstY, dstW, dstH)
            return
        }

        val bgraBytes = spec.captureBgra?.invoke()
        if (bgraBytes != null && bgraBytes.size >= srcW * srcH * 4) {
            bgraBytes.usePinned { pinned ->
                drawRawBgraPtrToDc(pinned.addressOf(0), srcW * 4, srcW, srcH, srcX, srcY, srcCropW, srcCropH, dstDc, dstX, dstY, dstW, dstH)
            }
            return
        }

        val rgbaBytes = spec.captureRgba?.invoke()
        if (rgbaBytes != null && rgbaBytes.size >= srcW * srcH * 4) {
            val converted = ByteArray(srcW * srcH * 4)
            var idx = 0
            val totalBytes = srcW * srcH * 4
            while (idx + 3 < totalBytes) {
                converted[idx] = rgbaBytes[idx + 2]
                converted[idx + 1] = rgbaBytes[idx + 1]
                converted[idx + 2] = rgbaBytes[idx]
                converted[idx + 3] = rgbaBytes[idx + 3]
                idx += 4
            }
            converted.usePinned { pinned ->
                drawRawBgraPtrToDc(pinned.addressOf(0), srcW * 4, srcW, srcH, srcX, srcY, srcCropW, srcCropH, dstDc, dstX, dstY, dstW, dstH)
            }
        }
    }

    private fun drawRawBgraPtrToDc(
        srcBytes: CPointer<ByteVar>,
        strideBytes: Int,
        fullSrcW: Int,
        fullSrcH: Int,
        srcX: Int,
        srcY: Int,
        srcCropW: Int,
        srcCropH: Int,
        dstDc: HDC,
        dstX: Int,
        dstY: Int,
        dstW: Int,
        dstH: Int
    ) {
        if (fullSrcW <= 0 || fullSrcH <= 0 || srcCropW <= 0 || srcCropH <= 0 || dstW <= 0 || dstH <= 0) return
        SetStretchBltMode(dstDc, STRETCH_HALFTONE)
        memScoped {
            val bmi = alloc<BITMAPINFO>()
            bmi.bmiHeader.biSize = sizeOf<BITMAPINFOHEADER>().toUInt()
            bmi.bmiHeader.biWidth = strideBytes / 4
            bmi.bmiHeader.biHeight = -fullSrcH
            bmi.bmiHeader.biPlanes = 1u
            bmi.bmiHeader.biBitCount = 32u
            bmi.bmiHeader.biCompression = BI_RGB.toUInt()
            StretchDIBits(
                dstDc,
                dstX,
                dstY,
                dstW,
                dstH,
                srcX,
                srcY,
                srcCropW,
                srcCropH,
                srcBytes,
                bmi.ptr,
                DIB_RGB_COLORS.toUInt(),
                SRCCOPY
            )
        }
    }

    private fun ensureDibForCache(
        refDc: HDC?,
        cache: SurfaceDibCache,
        w: Int,
        h: Int
    ): CPointer<UIntVar>? {
        if (cache.dc != null && cache.pixels != null && cache.width == w && cache.height == h) {
            return cache.pixels
        }
        cache.release()
        val dc = CreateCompatibleDC(refDc) ?: return null
        val ok = memScoped {
            val bmi = alloc<BITMAPINFO>()
            bmi.bmiHeader.biSize = sizeOf<BITMAPINFOHEADER>().toUInt()
            bmi.bmiHeader.biWidth = w
            bmi.bmiHeader.biHeight = -h
            bmi.bmiHeader.biPlanes = 1u
            bmi.bmiHeader.biBitCount = 32u
            bmi.bmiHeader.biCompression = BI_RGB.toUInt()
            val bitsPtrVar = alloc<COpaquePointerVar>()
            val bmp = CreateDIBSection(dc, bmi.ptr, DIB_RGB_COLORS.toUInt(), bitsPtrVar.ptr, null, 0u)
            val pixels = bitsPtrVar.value?.reinterpret<UIntVar>()
            if (bmp == null || pixels == null) {
                if (bmp != null) DeleteObject(bmp)
                false
            } else {
                cache.dc = dc
                cache.oldBmp = SelectObject(dc, bmp)
                cache.bmp = bmp
                cache.pixels = pixels
                cache.width = w
                cache.height = h
                true
            }
        }
        if (!ok) {
            DeleteDC(dc)
            return null
        }
        return cache.pixels
    }
}
