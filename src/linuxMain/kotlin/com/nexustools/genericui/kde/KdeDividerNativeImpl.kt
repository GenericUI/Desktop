@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.desktop.widget.DesktopDividerNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssTransform

/**
 * Retained KDE visual divider backed by KF6 **`KSeparator`** (`libKF6WidgetsAddons.so.6`) with
 * graceful fallback to Qt 6 **`QFrame`** (`HLine`/`VLine`).
 */
class KdeDividerNativeImpl(
    nativeWidget: KdeWidgetHandle = KdeWidgetHandle("KSeparator")
) : DesktopDividerNativeImpl<KdeWidgetHandle>(nativeWidget) {

    val isUsingKSeparator: Boolean

    init {
        KdeDynamicBindings.ensureQApplication()
        val kSep = KdeDynamicBindings.createKSeparator(KdeDynamicBindings.HORIZONTAL, null)
        if (kSep != null) {
            isUsingKSeparator = true
            nativeWidget.attachToQWidget(kSep, "KSeparator", customPainted = false)
            KdePainterSupport.attachStandardInputHooks(kSep, this, KdeVTableBridge.HOOK_RESIZE, dispatchLeftClickOnRelease = false)
        } else {
            isUsingKSeparator = false
            val frame = KdeDynamicBindings.createQFrame(null, 0)
            KdeDynamicBindings.configureFrameSeparator(frame, vertical = false)
            nativeWidget.attachToQWidget(frame, "QFrame_Separator", customPainted = false)
            KdePainterSupport.attachStandardInputHooks(frame, this, KdeVTableBridge.HOOK_RESIZE, dispatchLeftClickOnRelease = false)
        }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val b = nativeWidget.bounds
        val isVert = b.height > b.width && b.width in 1..6
        return if (isVert) Size(2f, maxHeight ?: 24f) else Size(maxWidth ?: 200f, 2f)
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        nativeWidget.applyBoundsToNative(rect)
        val ptr = nativeWidget.qWidgetPtr ?: return
        val vertical = rect.height > rect.width && rect.width <= 8f
        if (isUsingKSeparator) {
            KdeDynamicBindings.setKSeparatorOrientation(
                ptr,
                if (vertical) KdeDynamicBindings.VERTICAL else KdeDynamicBindings.HORIZONTAL
            )
        } else {
            KdeDynamicBindings.configureFrameSeparator(ptr, vertical)
        }
    }

    override fun onNativeVisibilityChanged(visible: Boolean) { nativeWidget.syncVisibilityToNative(visible) }
    override fun onNativeEnabledChanged(enabled: Boolean) { nativeWidget.qWidgetPtr?.let { KdeDynamicBindings.setWidgetEnabled(it, enabled) } }
    override fun onNativeTooltipChanged(tooltip: String?) {
        KdePainterSupport.applyCommonWidgetProperties(nativeWidget, customCursorState, nativeWidget.isSelfPointerEventsNone(), tooltip, getOpacity())
    }
    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        KdePainterSupport.applyCommonWidgetProperties(nativeWidget, cursor, nativeWidget.isSelfPointerEventsNone(), nativeWidget.tooltipText, getOpacity())
    }
    override fun onNativePointerEventsChanged(pointerEventsNone: Boolean) {
        KdePainterSupport.applyCommonWidgetProperties(nativeWidget, customCursorState, pointerEventsNone, nativeWidget.tooltipText, getOpacity())
    }
    override fun onNativeTransformChanged(transform: CssTransform?) { nativeWidget.requestRepaint() }

    override fun dispose() {
        nativeWidget.destroyQWidget()
        super.dispose()
    }
}
