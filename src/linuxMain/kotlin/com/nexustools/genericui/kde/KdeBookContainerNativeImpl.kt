@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.widget.DesktopBookContainerNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.enums.document.PrintPageOrientation
import com.nexustools.genericui.enums.document.PrintPageSize
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.style.theme.SystemThemeDetector

/**
 * Retained KDE paged book/document container (`QWidget_BookPrintPreview`) supporting paged CSS print
 * preview layout and KDE print job dispatch metadata.
 */
open class KdeBookContainerNativeImpl(
    nativeWidget: KdeWidgetHandle = KdeWidgetHandle("QWidget_BookPrintPreview")
) : DesktopBookContainerNativeImpl<KdeWidgetHandle>(nativeWidget) {

    init {
        KdeDynamicBindings.ensureQApplication()
        val ptr = KdeDynamicBindings.createQWidget(null, 0)
        nativeWidget.attachToQWidget(ptr, "QWidget_BookPrintPreview", customPainted = true)
        KdePainterSupport.attachStandardInputHooks(
            widgetPtr = ptr,
            impl = this,
            hookMask = KdeVTableBridge.HOOK_PAINT or KdeVTableBridge.HOOK_RESIZE,
            dispatchLeftClickOnRelease = true,
            onPaint = { _, painter, w, h ->
                val isDark = SystemThemeDetector.resolveTheme(ThemeMode.AUTO) == ThemeMode.DARK
                val bg = getEffectiveBackgroundColor() ?: getComputedStyle()?.backgroundColor
                    ?: if (isDark) RgbaColor(42, 44, 48) else RgbaColor(248, 249, 250)
                KdeDynamicBindings.painterFillRect(painter, 0.0, 0.0, w.toDouble(), h.toDouble(), bg)
            }
        )
    }

    override fun printBook(
        documentTitle: String,
        pageSize: PrintPageSize,
        orientation: PrintPageOrientation,
        marginMm: Float
    ): Boolean {
        val ok = super.printBook(documentTitle, pageSize, orientation, marginMm)
        nativeWidget.attributes["kdePrintBackend"] = "QtPrintSupport/CUPS"
        nativeWidget.requestRepaint()
        return ok
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

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size =
        layoutController?.measurePreferredSize(maxWidth, maxHeight) ?: Size(520, 680)

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
    override fun onNativeStyleSynced() { KdePainterSupport.applyCommonWidgetProperties(this, nativeWidget); nativeWidget.requestRepaint() }

    override fun dispose() {
        super.dispose()
        nativeWidget.destroyQWidget()
    }
}
