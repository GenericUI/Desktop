@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopInteractiveSurfaceSpec
import com.nexustools.genericui.enums.ui.ObjectFit
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
import platform.windows.BLACK_BRUSH
import platform.windows.BitBlt
import platform.windows.COLORONCOLOR
import platform.windows.CreateCompatibleDC
import platform.windows.CreateDIBSection
import platform.windows.DIB_RGB_COLORS
import platform.windows.DeleteDC
import platform.windows.DeleteObject
import platform.windows.FillRect
import platform.windows.GA_ROOT
import platform.windows.GetAncestor
import platform.windows.GetClientRect
import platform.windows.GetStockObject
import platform.windows.GdiFlush
import platform.windows.HBITMAP
import platform.windows.HBRUSH
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
import platform.windows.SetDIBitsToDevice
import platform.windows.SetStretchBltMode
import platform.windows.StretchDIBits
import platform.windows.STRETCH_HALFTONE
import platform.windows.WS_CHILD
import platform.windows.WS_CLIPSIBLINGS
import platform.windows.WS_VISIBLE
import platform.posix.memcpy
import platform.posix.memset
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
            if (handle.actualWin32ClassName != "GenericUIInteractiveSurfaceW") {
                surfaceCachesByHwnd.remove(existing.toLong())?.release()
                Win32ControlMessageRouter.replaceChildHwndInPlace(
                    handle = handle,
                    owner = owner,
                    newWin32ClassName = "GenericUIInteractiveSurfaceW",
                    newDwStyle = WS_CHILD or WS_VISIBLE or WS_CLIPSIBLINGS,
                    installSubclass = false
                )
            }
            trackedSurfaceHandles.add(handle)
            handle.customPaintCallback = { hwnd, hdc, w, h ->
                paintSurfaceToHdc(handle, owner, hwnd, hdc, w, h)
            }
            return handle.hwnd
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
     * Computes the aspect-correct presentation rectangle for [handle] mapped into [hostHwnd] client
     * coordinates and syncs it with [Win32NativeWindowHost.updateVideoStageRect].
     */
    internal fun syncSurfaceStageRectForHost(handle: Win32HwndHandle, hostHwnd: HWND): Boolean {
        val surfaceHwnd = handle.hwnd ?: return false
        if (IsWindow(surfaceHwnd) == 0 || IsWindow(hostHwnd) == 0) return false
        val spec = resolveSpec(handle) ?: return false
        if (!spec.isActiveStream() && !spec.shouldPaintBlackBackdrop()) return false

        val (cw, ch) = memScoped {
            val rc = alloc<RECT>()
            if (GetClientRect(surfaceHwnd, rc.ptr) == 0) return false
            (rc.right - rc.left).coerceAtLeast(0) to (rc.bottom - rc.top).coerceAtLeast(0)
        }
        if (cw <= 0 || ch <= 0) return false

        val relX: Int
        val relY: Int
        val relW: Int
        val relH: Int
        if (fillsContainerDirectly(handle, spec)) {
            relX = 0
            relY = 0
            relW = cw
            relH = ch
        } else {
            val srcW = spec.surfaceWidth().coerceAtLeast(1)
            val srcH = spec.surfaceHeight().coerceAtLeast(1)
            val rect = DesktopInteractiveSurfaceSpec.computePresentationRect(
                cw.toDouble(),
                ch.toDouble(),
                srcW,
                srcH,
                spec
            )
            if (rect.clippedDstW <= 0 || rect.clippedDstH <= 0) return false
            relX = rect.clippedDstX
            relY = rect.clippedDstY
            relW = rect.clippedDstW
            relH = rect.clippedDstH
        }

        val (mappedX, mappedY) = if (surfaceHwnd == hostHwnd) {
            relX to relY
        } else {
            memScoped {
                val pt = alloc<POINT>()
                pt.x = relX
                pt.y = relY
                MapWindowPoints(surfaceHwnd, hostHwnd, pt.ptr, 1u)
                pt.x to pt.y
            }
        }
        Win32NativeWindowHost.updateVideoStageRect(mappedX, mappedY, relW, relH)
        return true
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

        // Direct 1:1 / fast stretch blit path for full-container interactive surfaces (e.g. embedded WebView
        // with ObjectFit.FILL) that avoids allocating/clearing/alpha-fixing an intermediate 8MB DIB on every paint.
        if (spec != null && fillsContainerDirectly(handle, spec) && !spec.isActiveStream()) {
            spec.resizeSurface?.invoke(width, height)
            val sc = spec.swapCount()
            spec.dispatchBeforePresentFrame(sc, null)
            val actualSrcW = spec.surfaceWidth().coerceAtLeast(0)
            val actualSrcH = spec.surfaceHeight().coerceAtLeast(0)
            targetCache.lastSwapCount = sc
            if (actualSrcW > 0 && actualSrcH > 0) {
                blitSpecPixelsIntoHdc(
                    spec = spec,
                    srcW = actualSrcW,
                    srcH = actualSrcH,
                    srcX = 0,
                    srcY = 0,
                    srcCropW = actualSrcW,
                    srcCropH = actualSrcH,
                    dstDc = hdc,
                    dstX = 0,
                    dstY = 0,
                    dstW = width,
                    dstH = height,
                    fastScale = true
                )
            } else {
                memScoped {
                    val rc = alloc<RECT>()
                    rc.left = 0
                    rc.top = 0
                    rc.right = width
                    rc.bottom = height
                    val blackBrush = GetStockObject(BLACK_BRUSH)?.reinterpret<platform.windows.HBRUSH__>()
                    if (blackBrush != null) {
                        FillRect(hdc, rc.ptr, blackBrush)
                    }
                }
            }
            if (spec.shouldPaintBlackBackdrop()) {
                recordStagePlacementForHwnd(hwnd, sc, 0, 0, width, height)
            }
            return
        }

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
                    recordStagePlacementForHwnd(
                        hwnd = hwnd,
                        swapCount = presented.swapCount,
                        vx = actualRect.clippedDstX,
                        vy = actualRect.clippedDstY,
                        vw = actualRect.clippedDstW,
                        vh = actualRect.clippedDstH
                    )
                }
            }
        }

        GdiFlush()
        BitBlt(hdc, 0, 0, width, height, dibDc, 0, 0, SRCCOPY)
    }

    private fun recordStagePlacementForHwnd(
        hwnd: HWND,
        swapCount: Long,
        vx: Int,
        vy: Int,
        vw: Int,
        vh: Int
    ) {
        val rootAncestor = GetAncestor(hwnd, GA_ROOT.toUInt())
            ?.takeIf { IsWindow(it) != 0 && it != Win32ControlMessageRouter.getOrCreateParkingHwnd() }
        val topWindow = rootAncestor ?: Win32NativeWindowHost.activeHwnd
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
            swapCount,
            mapped[0],
            mapped[1],
            mapped[2],
            mapped[3]
        )
        if (vw > 0 && vh > 0) {
            Win32NativeWindowHost.updateVideoStageRect(mapped[0], mapped[1], vw, vh)
        }
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
        val cacheKey = handle.hwnd?.toLong() ?: handle.nativePointer
        val cache = surfaceCachesByHwnd.getOrPut(cacheKey) { SurfaceDibCache() }
        val dib = ensureDibForCache(refDc, cache, dstW, dstH) ?: return
        val dibDc = cache.dc ?: return

        val pixelCount = dstW * dstH
        val byteCount = (pixelCount * 4).toULong()
        memset(dib, 0, byteCount)
        val presented = presentSpecToDibDc(handle, spec, dibDc, dstW, dstH)
        if (presented != null) {
            cache.lastSwapCount = presented.swapCount
        }
        GdiFlush()
        dstArgb.usePinned { pinned ->
            memcpy(pinned.addressOf(0), dib, byteCount)
        }
        for (i in 0 until pixelCount) {
            dstArgb[i] = dstArgb[i] or 0xFF000000.toInt()
        }
    }

    private data class PresentedFrame(
        val rect: DesktopInteractiveSurfaceSpec.PresentationRect,
        val swapCount: Long,
        val blitted: Boolean
    )

    private fun fillsContainerDirectly(handle: Win32HwndHandle, spec: DesktopInteractiveSurfaceSpec): Boolean {
        if (spec.resolveContentFitMode() == ObjectFit.FILL) return true
        val role = handle.attributes["interactiveSurfaceRole"]?.toString().orEmpty()
        val isCanvasOrWebView = handle.nativeClassName.contains("Canvas", ignoreCase = true) ||
            handle.nativeClassName.contains("WebView", ignoreCase = true) ||
            role.contains("Canvas", ignoreCase = true) ||
            role.contains("WebView", ignoreCase = true)
        return isCanvasOrWebView &&
            spec.defaultAspectRatio == null &&
            spec.contentAspectRatio == null &&
            spec.contentFitMode == null
    }

    private fun presentSpecToDibDc(
        handle: Win32HwndHandle,
        spec: DesktopInteractiveSurfaceSpec,
        dibDc: HDC,
        containerW: Int,
        containerH: Int
    ): PresentedFrame? {
        val fillsContainerDirectly = fillsContainerDirectly(handle, spec)
        val srcW = spec.surfaceWidth().coerceAtLeast(1)
        val srcH = spec.surfaceHeight().coerceAtLeast(1)
        val initialRect = if (fillsContainerDirectly) {
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
        val actualRect = if (fillsContainerDirectly) {
            DesktopInteractiveSurfaceSpec.PresentationRect(
                dstX = 0.0,
                dstY = 0.0,
                dstW = containerW.toDouble(),
                dstH = containerH.toDouble(),
                srcX = 0,
                srcY = 0,
                srcW = actualSrcW.coerceAtLeast(1),
                srcH = actualSrcH.coerceAtLeast(1),
                clippedDstX = 0,
                clippedDstY = 0,
                clippedDstW = containerW,
                clippedDstH = containerH
            )
        } else if (initialRect != null && (actualSrcW <= 0 || actualSrcH <= 0 || (actualSrcW == srcW && actualSrcH == srcH))) {
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
        dstH: Int,
        fastScale: Boolean = false
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
            drawRawBgraPtrToDc(bytePtr, strideBytes, srcW, srcH, srcX, srcY, srcCropW, srcCropH, dstDc, dstX, dstY, dstW, dstH, fastScale)
            return
        }

        val bgraBytes = spec.captureBgra?.invoke()
        if (bgraBytes != null && bgraBytes.size >= srcW * srcH * 4) {
            bgraBytes.usePinned { pinned ->
                drawRawBgraPtrToDc(pinned.addressOf(0), srcW * 4, srcW, srcH, srcX, srcY, srcCropW, srcCropH, dstDc, dstX, dstY, dstW, dstH, fastScale)
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
                drawRawBgraPtrToDc(pinned.addressOf(0), srcW * 4, srcW, srcH, srcX, srcY, srcCropW, srcCropH, dstDc, dstX, dstY, dstW, dstH, fastScale)
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
        dstH: Int,
        fastScale: Boolean = false
    ) {
        if (fullSrcW <= 0 || fullSrcH <= 0 || srcCropW <= 0 || srcCropH <= 0 || dstW <= 0 || dstH <= 0) return
        val isDirectOneToOne = srcX == 0 && srcY == 0 && srcCropW == fullSrcW && srcCropH == fullSrcH &&
            dstW == srcCropW && dstH == srcCropH
        memScoped {
            val bmi = alloc<BITMAPINFO>()
            bmi.bmiHeader.biSize = sizeOf<BITMAPINFOHEADER>().toUInt()
            bmi.bmiHeader.biWidth = strideBytes / 4
            bmi.bmiHeader.biHeight = -fullSrcH
            bmi.bmiHeader.biPlanes = 1u
            bmi.bmiHeader.biBitCount = 32u
            bmi.bmiHeader.biCompression = BI_RGB.toUInt()
            if (isDirectOneToOne) {
                SetDIBitsToDevice(
                    dstDc,
                    dstX,
                    dstY,
                    dstW.toUInt(),
                    dstH.toUInt(),
                    0,
                    0,
                    0u,
                    fullSrcH.toUInt(),
                    srcBytes,
                    bmi.ptr,
                    DIB_RGB_COLORS.toUInt()
                )
            } else {
                SetStretchBltMode(dstDc, if (fastScale) COLORONCOLOR else STRETCH_HALFTONE)
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
