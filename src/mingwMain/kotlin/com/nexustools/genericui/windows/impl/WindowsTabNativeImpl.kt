@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.component.tabs.TabIconPlacement
import com.nexustools.genericui.desktop.system.DesktopConfigDirectoryHelper
import com.nexustools.genericui.desktop.widget.DesktopTabNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.image.ImageDecodeOptions
import com.nexustools.genericui.image.ImageFormat
import com.nexustools.genericui.image.ImageLoader
import com.nexustools.genericui.image.ImageSource
import com.nexustools.genericui.image.PixelFormat
import com.nexustools.genericui.image.cache.ImageCacheManager
import com.nexustools.genericui.res.DataUrlCodec
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.StyledVisualSpec
import com.nexustools.genericui.threading.GenericUiThreading
import com.nexustools.genericui.windows.Win32ControlMessageRouter
import com.nexustools.genericui.windows.Win32DwmUxThemeBridge
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.Win32NativeWindowHost
import com.nexustools.genericui.windows.Win32RetainedControlSupport
import com.nexustools.genericui.windows.Win32StyledWidgetHelper
import com.nexustools.genericui.windows.WindowsWicImageCodecNativeImpl
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.windows.BITMAPINFO
import platform.windows.BITMAPINFOHEADER
import platform.windows.BI_RGB
import platform.windows.BitBlt
import platform.windows.CreateCompatibleDC
import platform.windows.CreateDIBSection
import platform.windows.DEFAULT_GUI_FONT
import platform.windows.DIB_RGB_COLORS
import platform.windows.DT_CENTER
import platform.windows.DT_END_ELLIPSIS
import platform.windows.DT_LEFT
import platform.windows.DT_NOPREFIX
import platform.windows.DT_SINGLELINE
import platform.windows.DT_VCENTER
import platform.windows.DeleteDC
import platform.windows.DeleteObject
import platform.windows.DrawTextW
import platform.windows.GdiFlush
import platform.windows.GetStockObject
import platform.windows.HBITMAP
import platform.windows.HDC
import platform.windows.HFONT
import platform.windows.HGDIOBJ
import platform.windows.InvalidateRect
import platform.windows.IsWindow
import platform.windows.RECT
import platform.windows.ReleaseCapture
import platform.windows.SRCCOPY
import platform.windows.SelectObject
import platform.windows.SetBkMode
import platform.windows.SetCapture
import platform.windows.SetTextColor
import platform.windows.TRANSPARENT
import platform.windows.WM_LBUTTONDOWN
import platform.windows.WM_LBUTTONUP
import platform.windows.WM_MOUSELEAVE
import platform.windows.WM_MOUSEMOVE
import platform.windows.WS_CHILD
import platform.windows.WS_CLIPSIBLINGS
import platform.windows.WS_TABSTOP
import platform.windows.WS_VISIBLE
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Retained Win32 implementation for individual `Tab` items (`"GenericUITabItemW"` when standalone,
 * synced natively to parent `SysTabControl32` `TCITEMW` entries and owner-draw headers when hosted
 * in [WindowsTabContainerNativeImpl]).
 */
class WindowsTabNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("GenericUITabItemW"),
    closable: Boolean = false
) : DesktopTabNativeImpl<Win32HwndHandle>(widget, closable) {

    internal data class TabHeaderMetrics(
        val iconRect: Rect?,
        val closeRect: Rect?,
        val textRect: Rect
    )

    companion object {
        private const val WM_CAPTURECHANGED_MSG: Int = 0x0215

        private val fallbackIconCodec: WindowsWicImageCodecNativeImpl by lazy {
            WindowsWicImageCodecNativeImpl()
        }

        private var scratchDibDc: HDC? = null
        private var scratchDibBmp: HBITMAP? = null
        private var scratchDibOldBmp: HGDIOBJ? = null
        private var scratchDibPixels: CPointer<UIntVar>? = null
        private var scratchDibW: Int = 0
        private var scratchDibH: Int = 0

        private fun ensureScratchDib(refDc: HDC, w: Int, h: Int): CPointer<UIntVar>? {
            if (scratchDibDc != null && scratchDibPixels != null && scratchDibW >= w && scratchDibH >= h) {
                return scratchDibPixels
            }
            scratchDibOldBmp?.let { old -> scratchDibDc?.let { d -> SelectObject(d, old) } }
            scratchDibOldBmp = null
            scratchDibBmp?.let { DeleteObject(it) }
            scratchDibBmp = null
            scratchDibDc?.let { DeleteDC(it) }
            scratchDibDc = null
            scratchDibPixels = null

            val allocW = max(w, 32)
            val allocH = max(h, 32)
            val dc = CreateCompatibleDC(refDc) ?: return null
            val ok = memScoped {
                val bmi = alloc<BITMAPINFO>()
                bmi.bmiHeader.biSize = sizeOf<BITMAPINFOHEADER>().toUInt()
                bmi.bmiHeader.biWidth = allocW
                bmi.bmiHeader.biHeight = -allocH
                bmi.bmiHeader.biPlanes = 1u
                bmi.bmiHeader.biBitCount = 32u
                bmi.bmiHeader.biCompression = BI_RGB.toUInt()
                val bitsVar = alloc<COpaquePointerVar>()
                val bmp = CreateDIBSection(dc, bmi.ptr, DIB_RGB_COLORS.toUInt(), bitsVar.ptr, null, 0u)
                val ptr = bitsVar.value?.reinterpret<UIntVar>()
                if (bmp == null || ptr == null) {
                    if (bmp != null) DeleteObject(bmp)
                    false
                } else {
                    scratchDibDc = dc
                    scratchDibOldBmp = SelectObject(dc, bmp)
                    scratchDibBmp = bmp
                    scratchDibPixels = ptr
                    scratchDibW = allocW
                    scratchDibH = allocH
                    true
                }
            }
            if (!ok) {
                DeleteDC(dc)
                return null
            }
            return scratchDibPixels
        }

        internal fun tryDecodeTabIconSynchronously(url: String, targetSizePx: Int): ImageBuffer? {
            val trimmed = url.trim()
            if (trimmed.isEmpty()) return null
            val options = ImageDecodeOptions(
                preferredPixelFormat = PixelFormat.RGBA_8888,
                targetWidth = targetSizePx,
                targetHeight = targetSizePx,
                antialiasing = true
            )
            if (trimmed.startsWith("memory://", ignoreCase = true)) {
                ImageLoader.resolveMemoryBuffer(trimmed)?.let { return it }
            }
            ImageLoader.getWeakImageReference(trimmed)?.let { return it }
            ImageCacheManager.getMemoryCached(trimmed)?.let { return it }

            fun decodeWithCodec(source: ImageSource): ImageBuffer? {
                val active = ImageLoader.activeNativeCodec
                return active?.decode(source, options)
                    ?: if (active !is WindowsWicImageCodecNativeImpl) {
                        fallbackIconCodec.decode(source, options)
                    } else {
                        null
                    }
            }

            return when {
                trimmed.startsWith("data:", ignoreCase = true) -> {
                    val bytes = DataUrlCodec.decodeBytes(trimmed) ?: return null
                    decodeWithCodec(ImageSource.fromBytes(bytes, ImageFormat.detectFromHeader(bytes)))
                }
                trimmed.startsWith("res://", ignoreCase = true) || trimmed.startsWith("drawable/", ignoreCase = true) -> {
                    val bytes = DesktopConfigDirectoryHelper.readResourceOrLocalFile(trimmed)
                        ?: Win32NativeWindowHost.resolveGenericUiIconBytes(trimmed)
                        ?: return null
                    decodeWithCodec(ImageSource.fromBytes(bytes, ImageFormat.detectFromHeader(bytes)))
                }
                !trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true) -> {
                    val resolvedPath = DesktopConfigDirectoryHelper.resolveLocalAssetFilePath(trimmed)
                    if (resolvedPath != null) {
                        val fileBytes = DesktopConfigDirectoryHelper.readBytesFromFile(resolvedPath)
                        if (fileBytes != null && fileBytes.isNotEmpty()) {
                            return decodeWithCodec(ImageSource.fromBytes(fileBytes, ImageFormat.detectFromHeader(fileBytes)))
                        }
                        decodeWithCodec(ImageSource.fromFile(resolvedPath))
                    } else {
                        decodeWithCodec(ImageSource.fromFile(trimmed.removePrefix("file:///").removePrefix("file://")))
                    }
                }
                else -> null
            }
        }

        internal fun computeTabHeaderMetrics(
            rectLeft: Int,
            rectTop: Int,
            rectRight: Int,
            rectBottom: Int,
            hasIcon: Boolean,
            iconSizePx: Int,
            iconPlacement: TabIconPlacement,
            closable: Boolean
        ): TabHeaderMetrics {
            val w = (rectRight - rectLeft).coerceAtLeast(1)
            val h = (rectBottom - rectTop).coerceAtLeast(1)
            val closeSize = 16
            val canFitClose = closable && w >= closeSize + 14
            val closeRect = if (canFitClose) {
                val cx = (rectRight - 6 - closeSize).coerceAtLeast(rectLeft + 2)
                val cy = rectTop + ((h - closeSize) / 2).coerceAtLeast(1)
                Rect(cx, cy, closeSize, closeSize)
            } else {
                null
            }
            val rightLimit = closeRect?.let { (it.x - 4).coerceAtLeast(rectLeft + 4) } ?: (rectRight - 6).coerceAtLeast(rectLeft + 4)

            if (iconPlacement == TabIconPlacement.TOP && hasIcon && h >= 32) {
                val effIconSize = iconSizePx.coerceIn(12, min(18, (h - 16).coerceAtLeast(12)))
                val ix = rectLeft + ((rightLimit - rectLeft - effIconSize) / 2).coerceAtLeast(2)
                val iy = rectTop + 4
                val iconRect = Rect(ix, iy, effIconSize, effIconSize)
                val textTop = (iy + effIconSize + 2).coerceAtMost(rectBottom - 10)
                val textLeft = rectLeft + 6
                val textRect = Rect(textLeft, textTop, (rightLimit - textLeft).coerceAtLeast(1), (rectBottom - textTop).coerceAtLeast(1))
                return TabHeaderMetrics(iconRect = iconRect, closeRect = closeRect, textRect = textRect)
            }

            val effIconSize = iconSizePx.coerceIn(12, min(18, (h - 6).coerceAtLeast(12)))
            val canFitIcon = hasIcon && (rightLimit - rectLeft) >= effIconSize + 12
            val iconRect = if (canFitIcon) {
                val ix = rectLeft + 7
                val iy = rectTop + ((h - effIconSize) / 2).coerceAtLeast(1)
                Rect(ix, iy, effIconSize, effIconSize)
            } else {
                null
            }
            val textLeft = if (iconRect != null) iconRect.x + iconRect.width + 6 else rectLeft + 8
            val textW = (rightLimit - textLeft).coerceAtLeast(1)
            return TabHeaderMetrics(
                iconRect = iconRect,
                closeRect = closeRect,
                textRect = Rect(textLeft, rectTop, textW, h)
            )
        }

        private fun isMonoColorRgba(bytes: ByteArray, w: Int, h: Int): Boolean {
            var baseR = -1
            var baseG = -1
            var baseB = -1
            val total = w * h * 4
            var i = 0
            while (i + 3 < total) {
                val a = bytes[i + 3].toInt() and 0xFF
                if (a >= 32) {
                    val r = bytes[i].toInt() and 0xFF
                    val g = bytes[i + 1].toInt() and 0xFF
                    val b = bytes[i + 2].toInt() and 0xFF
                    if (baseR < 0) {
                        baseR = r; baseG = g; baseB = b
                    } else if (abs(r - baseR) > 18 || abs(g - baseG) > 18 || abs(b - baseB) > 18) {
                        return false
                    }
                }
                i += 4
            }
            return baseR >= 0
        }

        private fun blendPixelOverWord(dstWord: Int, sr: Int, sg: Int, sb: Int, sa: Int): UInt {
            if (sa <= 0) return dstWord.toUInt()
            if (sa >= 255) {
                return ((0xFF shl 24) or (sr shl 16) or (sg shl 8) or sb).toUInt()
            }
            val dr = (dstWord ushr 16) and 0xFF
            val dg = (dstWord ushr 8) and 0xFF
            val db = dstWord and 0xFF
            val invA = 255 - sa
            val outR = ((sr * sa + dr * invA + 127) / 255).coerceIn(0, 255)
            val outG = ((sg * sa + dg * invA + 127) / 255).coerceIn(0, 255)
            val outB = ((sb * sa + db * invA + 127) / 255).coerceIn(0, 255)
            return ((0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB).toUInt()
        }

        private fun pointSegmentDist(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
            val vx = bx - ax
            val vy = by - ay
            val lenSq = vx * vx + vy * vy
            if (lenSq <= 1e-6f) {
                val dx = px - ax
                val dy = py - ay
                return sqrt(dx * dx + dy * dy)
            }
            val t = (((px - ax) * vx + (py - ay) * vy) / lenSq).coerceIn(0f, 1f)
            val dx = px - (ax + t * vx)
            val dy = py - (ay + t * vy)
            return sqrt(dx * dx + dy * dy)
        }

        private fun drawIconToHdc(
            hdc: HDC,
            rect: Rect,
            iconBuffer: ImageBuffer,
            tintToTheme: Boolean,
            fgColor: RgbaColor
        ) {
            val dw = rect.width
            val dh = rect.height
            if (dw <= 0 || dh <= 0) return
            val dibPixels = ensureScratchDib(hdc, dw, dh) ?: return
            val dibDc = scratchDibDc ?: return
            val stride = scratchDibW

            BitBlt(dibDc, 0, 0, dw, dh, hdc, rect.x, rect.y, SRCCOPY)
            GdiFlush()

            val rgba = iconBuffer.asRgba8888()
            val sw = rgba.width
            val sh = rgba.height
            val srcBytes = rgba.bytes
            if (sw <= 0 || sh <= 0) return
            val applyTint = tintToTheme && isMonoColorRgba(srcBytes, sw, sh)
            val downscaleRatio = max(sw.toFloat() / dw.toFloat(), sh.toFloat() / dh.toFloat())
            val supersample = downscaleRatio > 1.4f

            for (py in 0 until dh) {
                val rowOff = py * stride
                for (px in 0 until dw) {
                    var accR = 0
                    var accG = 0
                    var accB = 0
                    var accA = 0
                    if (supersample) {
                        for (sySub in 0 until 2) {
                            val sy = (((py + (sySub + 0.5f) * 0.5f) / dh.toFloat()) * sh).toInt().coerceIn(0, sh - 1)
                            val sRow = sy * sw * 4
                            for (sxSub in 0 until 2) {
                                val sx = (((px + (sxSub + 0.5f) * 0.5f) / dw.toFloat()) * sw).toInt().coerceIn(0, sw - 1)
                                val off = sRow + sx * 4
                                val a = srcBytes[off + 3].toInt() and 0xFF
                                accR += (srcBytes[off].toInt() and 0xFF) * a
                                accG += (srcBytes[off + 1].toInt() and 0xFF) * a
                                accB += (srcBytes[off + 2].toInt() and 0xFF) * a
                                accA += a
                            }
                        }
                        val sa = accA / 4
                        if (sa <= 0) continue
                        val sr = if (applyTint) fgColor.r else (accR / accA).coerceIn(0, 255)
                        val sg = if (applyTint) fgColor.g else (accG / accA).coerceIn(0, 255)
                        val sb = if (applyTint) fgColor.b else (accB / accA).coerceIn(0, 255)
                        val effA = if (applyTint) (sa * fgColor.a + 127) / 255 else sa
                        dibPixels[rowOff + px] = blendPixelOverWord(dibPixels[rowOff + px].toInt(), sr, sg, sb, effA)
                    } else {
                        val sx = (((px + 0.5f) / dw.toFloat()) * sw).toInt().coerceIn(0, sw - 1)
                        val sy = (((py + 0.5f) / dh.toFloat()) * sh).toInt().coerceIn(0, sh - 1)
                        val off = (sy * sw + sx) * 4
                        val sa = srcBytes[off + 3].toInt() and 0xFF
                        if (sa <= 0) continue
                        val sr = if (applyTint) fgColor.r else (srcBytes[off].toInt() and 0xFF)
                        val sg = if (applyTint) fgColor.g else (srcBytes[off + 1].toInt() and 0xFF)
                        val sb = if (applyTint) fgColor.b else (srcBytes[off + 2].toInt() and 0xFF)
                        val effA = if (applyTint) (sa * fgColor.a + 127) / 255 else sa
                        dibPixels[rowOff + px] = blendPixelOverWord(dibPixels[rowOff + px].toInt(), sr, sg, sb, effA)
                    }
                }
            }
            GdiFlush()
            BitBlt(hdc, rect.x, rect.y, dw, dh, dibDc, 0, 0, SRCCOPY)
        }

        private fun drawCloseButtonToHdc(
            hdc: HDC,
            rect: Rect,
            hovered: Boolean,
            pressed: Boolean,
            fgColor: RgbaColor,
            isDark: Boolean
        ) {
            val cw = rect.width
            val ch = rect.height
            if (cw <= 0 || ch <= 0) return
            val dibPixels = ensureScratchDib(hdc, cw, ch) ?: return
            val dibDc = scratchDibDc ?: return
            val stride = scratchDibW

            BitBlt(dibDc, 0, 0, cw, ch, hdc, rect.x, rect.y, SRCCOPY)
            GdiFlush()

            val cx = cw * 0.5f
            val cy = ch * 0.5f
            val bgRadius = min(cw, ch) * 0.5f - 0.5f
            val bgCol = when {
                pressed -> if (isDark) RgbaColor(232, 72, 72, 180) else RgbaColor(220, 53, 69, 175)
                hovered -> if (isDark) RgbaColor(255, 255, 255, 42) else RgbaColor(0, 0, 0, 26)
                else -> null
            }
            val glyphCol = when {
                pressed -> RgbaColor(255, 255, 255, 255)
                hovered -> if (isDark) RgbaColor(255, 255, 255, 255) else RgbaColor(17, 24, 39, 255)
                else -> fgColor
            }
            val arm = (min(cw, ch) * 0.22f).coerceIn(2.6f, 4.0f)

            for (py in 0 until ch) {
                val rowOff = py * stride
                val fpy = py + 0.5f
                for (px in 0 until cw) {
                    val fpx = px + 0.5f
                    var curWord = dibPixels[rowOff + px].toInt()
                    if (bgCol != null) {
                        val distC = sqrt((fpx - cx) * (fpx - cx) + (fpy - cy) * (fpy - cy))
                        val circleCov = (bgRadius + 0.5f - distC).coerceIn(0f, 1f)
                        if (circleCov > 0f) {
                            val ba = (bgCol.a * circleCov).roundToInt().coerceIn(0, 255)
                            curWord = blendPixelOverWord(curWord, bgCol.r, bgCol.g, bgCol.b, ba).toInt()
                        }
                    }
                    val d1 = pointSegmentDist(fpx, fpy, cx - arm, cy - arm, cx + arm, cy + arm)
                    val d2 = pointSegmentDist(fpx, fpy, cx - arm, cy + arm, cx + arm, cy - arm)
                    val d = min(d1, d2)
                    val strokeCov = (1.15f - d).coerceIn(0f, 1f)
                    if (strokeCov > 0f) {
                        val ga = (glyphCol.a * strokeCov).roundToInt().coerceIn(0, 255)
                        curWord = blendPixelOverWord(curWord, glyphCol.r, glyphCol.g, glyphCol.b, ga).toInt()
                    }
                    dibPixels[rowOff + px] = curWord.toUInt()
                }
            }
            GdiFlush()
            BitBlt(hdc, rect.x, rect.y, cw, ch, dibDc, 0, 0, SRCCOPY)
        }

        internal fun paintTabHeaderContent(
            hdc: HDC,
            rectLeft: Int,
            rectTop: Int,
            rectRight: Int,
            rectBottom: Int,
            label: String,
            fontHandle: HFONT?,
            iconBuffer: ImageBuffer?,
            iconSizePx: Int,
            iconPlacement: TabIconPlacement,
            tintToTheme: Boolean,
            fgColor: RgbaColor,
            closable: Boolean,
            closeHovered: Boolean,
            closePressed: Boolean,
            isDark: Boolean
        ) {
            val metrics = computeTabHeaderMetrics(
                rectLeft = rectLeft,
                rectTop = rectTop,
                rectRight = rectRight,
                rectBottom = rectBottom,
                hasIcon = iconBuffer != null,
                iconSizePx = iconSizePx,
                iconPlacement = iconPlacement,
                closable = closable
            )
            if (iconBuffer != null && metrics.iconRect != null) {
                drawIconToHdc(hdc, metrics.iconRect, iconBuffer, tintToTheme, fgColor)
            }
            if (metrics.closeRect != null) {
                drawCloseButtonToHdc(hdc, metrics.closeRect, closeHovered, closePressed, fgColor, isDark)
            }
            if (label.isNotEmpty() && metrics.textRect.width > 2 && metrics.textRect.height > 2) {
                val effFont = fontHandle ?: GetStockObject(DEFAULT_GUI_FONT)?.reinterpret<platform.windows.HFONT__>()
                val oldFont = if (effFont != null) SelectObject(hdc, effFont) else null
                val oldBk = SetBkMode(hdc, TRANSPARENT)
                SetTextColor(hdc, Win32DwmUxThemeBridge.colorRefRgb(fgColor.r, fgColor.g, fgColor.b))
                memScoped {
                    val tr = alloc<RECT>()
                    tr.left = metrics.textRect.x
                    tr.top = metrics.textRect.y
                    tr.right = metrics.textRect.x + metrics.textRect.width
                    tr.bottom = metrics.textRect.y + metrics.textRect.height
                    val alignFlag = if (iconPlacement == TabIconPlacement.TOP && metrics.iconRect != null) DT_CENTER else DT_LEFT
                    val flags = (DT_SINGLELINE or DT_VCENTER or alignFlag or DT_END_ELLIPSIS or DT_NOPREFIX).toUInt()
                    DrawTextW(hdc, label, -1, tr.ptr, flags)
                }
                if (oldBk != 0) SetBkMode(hdc, oldBk)
                if (oldFont != null) SelectObject(hdc, oldFont)
            }
        }
    }

    internal var parentTabContainer: WindowsTabContainerNativeImpl? = null
    internal var decodedIconBuffer: ImageBuffer? = null
        private set
    private var iconLoadGeneration: Int = 0
    private var closeHovered: Boolean = false
    private var closePressed: Boolean = false

    init {
        nativeWidget.attributes["nativeImpl"] = this
        Win32ControlMessageRouter.createChildHwnd(
            handle = nativeWidget,
            owner = this,
            win32ClassName = "GenericUITabItemW",
            dwStyle = WS_CHILD or WS_VISIBLE or WS_TABSTOP or WS_CLIPSIBLINGS,
            initialText = titleVal,
            initialWidth = 100,
            initialHeight = 32,
            installSubclass = false
        )
        nativeWidget.customPaintCallback = { hwnd, hdc, w, h ->
            val dark = Win32NativeWindowHost.isEffectivelyDarkTheme()
            val bg = if (selectedVal) {
                if (dark) RgbaColor(54, 58, 66) else RgbaColor(255, 255, 255)
            } else {
                if (dark) RgbaColor(36, 39, 45) else RgbaColor(238, 240, 244)
            }
            val fg = if (selectedVal) {
                if (dark) RgbaColor(242, 245, 250) else RgbaColor(25, 28, 34)
            } else {
                if (dark) RgbaColor(180, 185, 195) else RgbaColor(88, 94, 104)
            }
            val border = if (selectedVal) RgbaColor(13, 110, 253) else RgbaColor(180, 185, 194, 128)
            Win32StyledWidgetHelper.renderStyledSpecToHdc(
                hwnd = hwnd,
                hdc = hdc,
                width = w,
                height = h,
                spec = StyledVisualSpec(
                    backgroundColor = bg,
                    textColor = fg,
                    borderColor = border,
                    borderWidthPx = 1f,
                    borderRadiusPx = 6f
                ),
                isDark = dark,
                focused = isFocusedState(),
                fontHandle = nativeWidget.fontHandle,
                text = null,
                textColorOverride = fg
            )
            paintTabHeaderContent(
                hdc = hdc,
                rectLeft = 0,
                rectTop = 0,
                rectRight = w,
                rectBottom = h,
                label = buildDisplayTitle(),
                fontHandle = nativeWidget.fontHandle,
                iconBuffer = decodedIconBuffer,
                iconSizePx = iconSizePxVal,
                iconPlacement = iconPlacementVal,
                tintToTheme = tintImageToThemeVal,
                fgColor = fg,
                closable = closableVal,
                closeHovered = closeHovered,
                closePressed = closePressed,
                isDark = dark
            )
        }
        nativeWidget.customMessageCallback = { hwnd, msg, _, lParam ->
            val mx = (lParam and 0xFFFFL).toShort().toInt()
            val my = ((lParam shr 16) and 0xFFFFL).toShort().toInt()
            when (msg.toInt()) {
                WM_MOUSEMOVE -> {
                    val nowOver = isPointInCloseButton(mx, my)
                    if (nowOver != closeHovered) {
                        closeHovered = nowOver
                        InvalidateRect(hwnd, null, 0)
                    }
                    null
                }
                WM_MOUSELEAVE, WM_CAPTURECHANGED_MSG -> {
                    if (closeHovered || closePressed) {
                        closeHovered = false
                        closePressed = false
                        InvalidateRect(hwnd, null, 0)
                    }
                    null
                }
                WM_LBUTTONDOWN -> {
                    if (isPointInCloseButton(mx, my)) {
                        closePressed = true
                        closeHovered = true
                        SetCapture(hwnd)
                        InvalidateRect(hwnd, null, 0)
                        0L
                    } else {
                        null
                    }
                }
                WM_LBUTTONUP -> {
                    if (closePressed) {
                        ReleaseCapture()
                        closePressed = false
                        val stillOver = isPointInCloseButton(mx, my)
                        closeHovered = stillOver
                        InvalidateRect(hwnd, null, 0)
                        if (stillOver) {
                            onCloseClickedListener?.invoke()
                        }
                        0L
                    } else {
                        null
                    }
                }
                else -> null
            }
        }
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = false)
    }

    internal fun currentCloseButtonRect(): Rect? {
        val bw = nativeWidget.bounds.width.takeIf { it > 0 } ?: nativeWidget.lastAppliedW.coerceAtLeast(0)
        val bh = nativeWidget.bounds.height.takeIf { it > 0 } ?: nativeWidget.lastAppliedH.coerceAtLeast(0)
        if (bw <= 0 || bh <= 0) return null
        return computeTabHeaderMetrics(
            rectLeft = 0,
            rectTop = 0,
            rectRight = bw,
            rectBottom = bh,
            hasIcon = decodedIconBuffer != null,
            iconSizePx = iconSizePxVal,
            iconPlacement = iconPlacementVal,
            closable = closableVal
        ).closeRect
    }

    internal fun isPointInCloseButton(mx: Int, my: Int): Boolean {
        val rc = currentCloseButtonRect() ?: return false
        return mx in rc.x until (rc.x + rc.width) && my in rc.y until (rc.y + rc.height)
    }

    internal fun buildDisplayTitle(): String {
        val dirtySuffix = if (dirtyVal) " \u2022" else ""
        val badgeSuffix = badgeTextVal?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
        return "$titleVal$dirtySuffix$badgeSuffix"
    }

    private fun reloadTabIcon(icon: String?) {
        val gen = ++iconLoadGeneration
        val trimmed = icon?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            decodedIconBuffer = null
            notifyTabChanged()
            return
        }
        val targetPx = iconSizePxVal.coerceIn(12, 32)
        val syncDecoded = tryDecodeTabIconSynchronously(trimmed, targetPx)
        if (syncDecoded != null) {
            decodedIconBuffer = syncDecoded
            notifyTabChanged()
            return
        }
        decodedIconBuffer = null
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            val decodeOpts = ImageDecodeOptions(
                preferredPixelFormat = PixelFormat.RGBA_8888,
                targetWidth = targetPx,
                targetHeight = targetPx,
                antialiasing = true
            )
            ImageLoader.launchLoad(
                source = ImageSource.fromUrl(trimmed),
                options = decodeOpts,
                onLoaded = { buf ->
                    GenericUiThreading.runOnUi {
                        if (gen == iconLoadGeneration) {
                            decodedIconBuffer = buf
                            notifyTabChanged()
                        }
                    }
                },
                onError = {
                    GenericUiThreading.runOnUi {
                        if (gen == iconLoadGeneration) {
                            decodedIconBuffer = null
                        }
                    }
                }
            )
        }
        notifyTabChanged()
    }

    private fun notifyTabChanged() {
        parentTabContainer?.syncTabItemFromChild(this)
        nativeWidget.hwnd?.let { hwnd ->
            if (IsWindow(hwnd) != 0) InvalidateRect(hwnd, null, 0)
        }
    }

    override fun setTitleNative(title: String) {
        super.setTitleNative(title)
        Win32RetainedControlSupport.setWindowTextIfChanged(nativeWidget.hwnd, title)
        notifyTabChanged()
    }

    override fun setIconNative(icon: String?) {
        super.setIconNative(icon)
        reloadTabIcon(icon)
    }

    override fun setIconSizePxNative(sizePx: Int) {
        super.setIconSizePxNative(sizePx)
        reloadTabIcon(iconVal)
    }

    override fun setIconPlacementNative(placement: TabIconPlacement) {
        super.setIconPlacementNative(placement)
        notifyTabChanged()
    }

    override fun setTintImageToThemeNative(tintToTheme: Boolean) {
        super.setTintImageToThemeNative(tintToTheme)
        notifyTabChanged()
    }

    override fun setTooltipNative(tooltip: String?) {
        super.setTooltipNative(tooltip)
        Win32RetainedControlSupport.onTooltipChanged(nativeWidget, tooltip)
        notifyTabChanged()
    }

    override fun setClosableNative(closable: Boolean) {
        super.setClosableNative(closable)
        notifyTabChanged()
    }

    override fun setDirtyNative(dirty: Boolean) {
        super.setDirtyNative(dirty)
        notifyTabChanged()
    }

    override fun setBadgeNative(text: String?, variantCss: String?) {
        super.setBadgeNative(text, variantCss)
        notifyTabChanged()
    }

    override fun setSelectedNative(selected: Boolean) {
        super.setSelectedNative(selected)
        nativeWidget.hwnd?.let { InvalidateRect(it, null, 0) }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val hasIconSlot = decodedIconBuffer != null || !iconVal.isNullOrBlank()
        val effIcon = if (hasIconSlot) iconSizePxVal.coerceIn(12, 20) + 6 else 0
        val extraW = (if (closableVal) 24 else 14) + effIcon + 6
        return Win32RetainedControlSupport.measureTextSize(
            text = buildDisplayTitle().ifBlank { "Tab" },
            fontHandle = nativeWidget.fontHandle,
            maxWidthPx = null,
            extraW = extraW,
            extraH = if (iconPlacementVal == TabIconPlacement.TOP && hasIconSlot) 28 else 12,
            minW = 56,
            minH = if (iconPlacementVal == TabIconPlacement.TOP && hasIconSlot) 44 else 30
        )
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        Win32RetainedControlSupport.onLeafBoundsChanged(nativeWidget, rect)
    }

    override fun onNativeVisibilityChanged(visible: Boolean) {
        Win32RetainedControlSupport.onVisibilityChanged(nativeWidget, visible)
    }

    override fun onNativeEnabledChanged(enabled: Boolean) {
        Win32RetainedControlSupport.onEnabledChanged(nativeWidget, enabled)
    }

    override fun onNativeOpacityChanged(opacity: Float) {
        Win32RetainedControlSupport.onOpacityChanged(nativeWidget, opacity)
    }

    override fun onNativeTooltipChanged(tooltip: String?) {
        Win32RetainedControlSupport.onTooltipChanged(nativeWidget, tooltip)
    }

    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        Win32RetainedControlSupport.onCursorChanged(nativeWidget, cursor)
    }

    override fun onNativePointerEventsChanged(pointerEventsNone: Boolean) {
        Win32RetainedControlSupport.onPointerEventsChanged(nativeWidget, pointerEventsNone)
    }

    override fun onNativeZIndexChanged(zIndex: Int) {
        Win32RetainedControlSupport.onZIndexChanged(nativeWidget, zIndex)
    }

    override fun onNativeStyleSynced() {
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = false)
        nativeWidget.hwnd?.let { InvalidateRect(it, null, 0) }
    }

    override fun dispose() {
        parentTabContainer = null
        Win32RetainedControlSupport.onDispose(nativeWidget)
        super.dispose()
    }
}
