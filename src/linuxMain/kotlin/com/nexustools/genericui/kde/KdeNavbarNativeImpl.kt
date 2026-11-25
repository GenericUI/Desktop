@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopNavbarNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.style.theme.SystemThemeDetector
import kotlinx.cinterop.COpaquePointer

/**
 * Retained KDE navigation toolbar widget backed by a styled child `QWidget` container
 * with optional brand caption and flex-arranged navbar children.
 */
open class KdeNavbarNativeImpl(
    nativeWidget: KdeWidgetHandle = KdeWidgetHandle("QToolBar_Navbar")
) : DesktopNavbarNativeImpl<KdeWidgetHandle>(nativeWidget) {

    init {
        KdeDynamicBindings.ensureQApplication()
        val ptr = KdeDynamicBindings.createQWidget(null, 0)
        nativeWidget.attachToQWidget(ptr, "QToolBar_Navbar", customPainted = true)
        KdePainterSupport.attachStandardInputHooks(
            widgetPtr = ptr,
            impl = this,
            hookMask = KdeVTableBridge.HOOK_PAINT or KdeVTableBridge.HOOK_RESIZE or KdeVTableBridge.HOOK_SIZE_HINT,
            dispatchLeftClickOnRelease = false,
            onPaint = { wPtr, painter, w, h -> paintNavbar(wPtr, painter, w, h) },
            onSizeHint = { _ ->
                val s = measureIntrinsicSize(null, null)
                s.width.coerceAtLeast(240) to s.height.coerceAtLeast(48)
            }
        )
    }

    private fun paintNavbar(wPtr: COpaquePointer, painter: COpaquePointer, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val isDark = SystemThemeDetector.resolveTheme(ThemeMode.AUTO) == ThemeMode.DARK
        val computed = getComputedStyle()
        val bg = getEffectiveBackgroundColor() ?: computed?.backgroundColor ?: if (isDark) RgbaColor(35, 38, 41) else RgbaColor(239, 240, 241)
        val border = computed?.borderColor ?: if (isDark) RgbaColor(60, 64, 67) else RgbaColor(210, 213, 216)

        KdeDynamicBindings.painterFillRect(painter, 0.0, 0.0, w.toDouble(), h.toDouble(), bg)
        KdeDynamicBindings.painterFillRect(painter, 0.0, (h - 1).coerceAtLeast(0).toDouble(), w.toDouble(), 1.0, border)

        if (isBrandVisible() && brandTitleText.isNotBlank()) {
            val fg = getEffectiveTextColor() ?: computed?.textColor ?: if (isDark) RgbaColor(239, 240, 241) else RgbaColor(35, 38, 41)
            KdeDynamicBindings.painterDrawText(
                p = painter,
                text = brandTitleText,
                x = 14.0,
                y = 0.0,
                w = (w / 3).coerceAtLeast(80).toDouble(),
                h = h.toDouble(),
                color = fg,
                fontSizePx = computed?.fontSizePx ?: 14f,
                fontWeight = 700,
                italic = false,
                fontFamily = computed?.fontFamily,
                alignmentFlags = KdeDynamicBindings.ALIGN_LEFT or KdeDynamicBindings.ALIGN_VCENTER
            )
        }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val base = layoutController?.measurePreferredSize(maxWidth, maxHeight) ?: super.measureIntrinsicSize(maxWidth, maxHeight)
        return Size(base.width.coerceAtLeast(240), base.height.coerceAtLeast(48))
    }

    override fun layoutChildrenInBounds(width: Float, height: Float) {
        val brandReserve = if (isBrandVisible() && brandTitleText.isNotBlank()) {
            (brandTitleText.length * 8 + 28).coerceAtMost((width * 0.45f).toInt()).toFloat()
        } else {
            0f
        }
        val availW = (width - brandReserve).coerceAtLeast(0f)
        super.layoutChildrenInBounds(availW, height)
        if (brandReserve > 0f) {
            for (child in childImpls) {
                if (child is DesktopBaseNativeImpl<*>) {
                    val b = child.nativeWidget.bounds
                    child.setBounds(Rect((b.x + brandReserve.toInt()), b.y, b.width, b.height))
                }
            }
        }
    }

    override fun onChildAddedNative(childImpl: NativeImpl<*>, index: Int) {
        super.onChildAddedNative(childImpl, index)
        (childImpl.nativeWidget as? KdeWidgetHandle)?.syncChildParentAndVisibility(nativeWidget)
        val b = nativeWidget.bounds
        if (b.width > 0 && b.height > 0) layoutChildrenInBounds(b.width.toFloat(), b.height.toFloat())
    }

    override fun onChildRemovedNative(childImpl: NativeImpl<*>) {
        (childImpl.nativeWidget as? KdeWidgetHandle)?.syncChildParentAndVisibility(null)
        super.onChildRemovedNative(childImpl)
    }

    override fun onChildrenClearedNative() {
        for (c in childImpls) (c.nativeWidget as? KdeWidgetHandle)?.syncChildParentAndVisibility(null)
        super.onChildrenClearedNative()
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        nativeWidget.applyBoundsToNative(rect)
        layoutChildrenInBounds(rect.width.toFloat(), rect.height.toFloat())
        nativeWidget.requestRepaint()
    }

    override fun onNativeVisibilityChanged(visible: Boolean) { nativeWidget.syncVisibilityToNative(visible) }
    override fun onNativeEnabledChanged(enabled: Boolean) { nativeWidget.qWidgetPtr?.let { KdeDynamicBindings.setWidgetEnabled(it, enabled) } }
    override fun onNativeOpacityChanged(opacity: Float) { nativeWidget.requestRepaint() }
    override fun onNativeTooltipChanged(tooltip: String?) { KdePainterSupport.applyCommonWidgetProperties(this, nativeWidget) }
    override fun onNativeCursorChanged(cursor: CssCursorValue?) { KdePainterSupport.applyCommonWidgetProperties(this, nativeWidget) }
    override fun onNativePointerEventsChanged(pointerEventsNone: Boolean) { KdePainterSupport.applyCommonWidgetProperties(this, nativeWidget) }
    override fun onNativeZIndexChanged(zIndex: Int) { nativeWidget.qWidgetPtr?.let { KdeDynamicBindings.raiseWidget(it) } }
    override fun onNativeTransformChanged(transform: CssTransform?) { nativeWidget.requestRepaint() }
    override fun onNativeStyleSynced() {
        KdePainterSupport.applyCommonWidgetProperties(this, nativeWidget)
        nativeWidget.requestRepaint()
    }

    override fun dispose() {
        super.dispose()
        nativeWidget.destroyQWidget()
    }
}
