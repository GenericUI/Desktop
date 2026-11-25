@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.desktop.widget.DesktopSpinnerNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.css.CssTransform

/**
 * Retained KDE activity spinner backed by KF6 **`KBusyIndicatorWidget`** (`libKF6WidgetsAddons.so.6`)
 * with automatic graceful fallback to an indeterminate Qt 6 **`QProgressBar`** when KF6 is absent.
 */
class KdeSpinnerNativeImpl(
    nativeWidget: KdeWidgetHandle = KdeWidgetHandle("KBusyIndicatorWidget")
) : DesktopSpinnerNativeImpl<KdeWidgetHandle>(nativeWidget) {

    val isUsingKBusyIndicatorWidget: Boolean

    init {
        KdeDynamicBindings.ensureQApplication()
        val kfPtr = KdeDynamicBindings.createKBusyIndicatorWidget(null)
        if (kfPtr != null) {
            isUsingKBusyIndicatorWidget = true
            KdeDynamicBindings.setKBusyIndicatorRunning(kfPtr, true)
            nativeWidget.attachToQWidget(kfPtr, "KBusyIndicatorWidget", customPainted = false)
            KdePainterSupport.attachStandardInputHooks(kfPtr, this, KdeVTableBridge.HOOK_RESIZE, dispatchLeftClickOnRelease = false)
        } else {
            isUsingKBusyIndicatorWidget = false
            val pbPtr = KdeDynamicBindings.createQProgressBar(null)
            KdeDynamicBindings.setProgressBarState(pbPtr, 0, 0, 0, showText = false)
            nativeWidget.attachToQWidget(pbPtr, "QProgressBar_IndeterminateFallback", customPainted = false)
            KdePainterSupport.attachStandardInputHooks(pbPtr, this, KdeVTableBridge.HOOK_RESIZE, dispatchLeftClickOnRelease = false)
        }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val sz = if (growStyleState) 36f else 28f
        return Size(if (isUsingKBusyIndicatorWidget) sz else 96f, sz)
    }

    override fun onNativeBoundsChanged(rect: Rect) { nativeWidget.applyBoundsToNative(rect) }
    override fun onNativeVisibilityChanged(visible: Boolean) {
        nativeWidget.syncVisibilityToNative(visible)
        if (isUsingKBusyIndicatorWidget) {
            nativeWidget.qWidgetPtr?.let { KdeDynamicBindings.setKBusyIndicatorRunning(it, visible) }
        }
    }
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
