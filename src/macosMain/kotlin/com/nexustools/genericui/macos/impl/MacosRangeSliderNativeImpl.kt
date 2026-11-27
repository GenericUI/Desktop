@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos.impl

import com.nexustools.genericui.desktop.widget.DesktopRangeSliderNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.macos.MacosAppKitDispatch
import com.nexustools.genericui.macos.MacosFocusSupport
import com.nexustools.genericui.macos.MacosControlActionTarget
import com.nexustools.genericui.macos.MacosViewHandle
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.useContents
import platform.AppKit.NSSlider
import platform.AppKit.alignmentRectForFrame
import platform.AppKit.fittingSize
import platform.Foundation.NSMakeRect
import kotlin.math.ceil
import kotlin.math.round

/**
 * macOS AppKit retained [NSSlider] implementation of [DesktopRangeSliderNativeImpl].
 */
class MacosRangeSliderNativeImpl(
    nativeWidget: MacosViewHandle
) : DesktopRangeSliderNativeImpl<MacosViewHandle>(nativeWidget) {

    private var suppressCallback: Boolean = false

    private val sliderTarget = MacosControlActionTarget { sender ->
        if (suppressCallback || !nativeWidget.enabled || isPointerInteractionDisabled()) return@MacosControlActionTarget
        val s = sender as? NSSlider ?: return@MacosControlActionTarget
        val raw = s.doubleValue
        val stepped = if (stepVal > 0.0) {
            minVal + round((raw - minVal) / stepVal) * stepVal
        } else {
            raw
        }.coerceIn(minVal, maxVal)
        val changed = kotlin.math.abs(currentVal - stepped) > 1e-4
        setValue(stepped)
        if (changed) {
            onValueChangedListener?.invoke(stepped)
        }
    }

    private var sliderView: NSSlider? = null

    init {
        nativeWidget.attributes["usesAppKitAlignmentRect"] = true
        nativeWidget.viewMaterializer = {
            ensureSlider().also { syncSliderState() }
        }
        if (MacosAppKitDispatch.isMainThread()) {
            ensureSlider()
            syncSliderState()
        }
    }

    private fun ensureSlider(): NSSlider {
        sliderView?.let { return it }
        val s = NSSlider(frame = NSMakeRect(0.0, 0.0, 180.0, 22.0)).apply {
            setContinuous(true)
            setTarget(sliderTarget)
            setAction(sliderTarget.actionSelector)
        }
        sliderView = s
        nativeWidget.nsView = s
        return s
    }

    private fun syncSliderState() {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { syncSliderState() }
            return
        }
        val s = ensureSlider()
        suppressCallback = true
        try {
            s.setMinValue(minVal)
            s.setMaxValue(maxVal)
            s.setDoubleValue(currentVal)
            s.setEnabled(nativeWidget.enabled)
            s.setHidden(!nativeWidget.visible)
            s.setAlphaValue(nativeWidget.opacity.coerceIn(0f, 1f).toDouble())
            s.setToolTip(nativeWidget.tooltipText)
        } finally {
            suppressCallback = false
        }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val s = sliderView
        if (s != null) {
            val fit = s.fittingSize()
            val aligned = s.alignmentRectForFrame(fit.useContents { NSMakeRect(0.0, 0.0, width, height) })
            return aligned.useContents {
                Size(ceil(size.width).toInt().coerceAtLeast(120), ceil(size.height).toInt().coerceAtLeast(22))
            }
        }
        return Size(180, 22)
    }

    override fun setRange(min: Double, max: Double, step: Double) {
        super.setRange(min, max, step)
        syncSliderState()
    }

    override fun setValue(value: Double) {
        super.setValue(value)
        syncSliderState()
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { onNativeBoundsChanged(nativeWidget.bounds) }
            return
        }
        nativeWidget.ensureNsViewOnMain()
        val s = sliderView ?: return
        MacosAppKitDispatch.applyFrameAndTransform(s, rect, nativeWidget.cssTransform, useAlignmentRectExpansion = true)
    }

    override fun onNativeVisibilityChanged(visible: Boolean) {
        sliderView?.setHidden(!visible)
    }

    override fun onNativeEnabledChanged(enabled: Boolean) {
        syncSliderState()
    }

    override fun onNativeOpacityChanged(opacity: Float) {
        sliderView?.setAlphaValue(opacity.coerceIn(0f, 1f).toDouble())
    }

    override fun onNativeTooltipChanged(tooltip: String?) {
        sliderView?.setToolTip(tooltip)
    }

    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        sliderView?.let { v -> v.window?.invalidateCursorRectsForView(v) }
    }

    override fun onNativeTransformChanged(transform: CssTransform?) {
        nativeWidget.cssTransform = transform
        val s = sliderView ?: return
        MacosAppKitDispatch.applyFrameAndTransform(s, nativeWidget.bounds, transform, useAlignmentRectExpansion = true)
    }

    override fun onNativeStyleSynced() {
        syncSliderState()
    }

    override fun setFocusedState(focused: Boolean) {
        super.setFocusedState(focused)
        MacosFocusSupport.syncViewFirstResponder(
            targetView = sliderView,
            focused = isFocusedState(),
            effectivelyFocusable = nativeWidget.isEffectivelyFocusable(),
            enabled = nativeWidget.enabled
        )
    }

    override fun dispose() {
        val view = sliderView
        sliderView = null
        MacosAppKitDispatch.detachViewOnMain(view)
        super.dispose()
    }
}
