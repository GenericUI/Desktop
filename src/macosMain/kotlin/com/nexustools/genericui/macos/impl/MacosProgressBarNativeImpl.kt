@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos.impl

import com.nexustools.genericui.desktop.widget.DesktopProgressBarNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.macos.MacosAppKitDispatch
import com.nexustools.genericui.macos.MacosViewHandle
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.useContents
import platform.AppKit.NSProgressIndicator
import platform.AppKit.NSProgressIndicatorStyleBar
import platform.AppKit.fittingSize
import platform.Foundation.NSMakeRect
import kotlin.math.ceil

/**
 * macOS AppKit retained [NSProgressIndicator] bar implementation of [DesktopProgressBarNativeImpl].
 */
class MacosProgressBarNativeImpl(
    nativeWidget: MacosViewHandle
) : DesktopProgressBarNativeImpl<MacosViewHandle>(nativeWidget) {

    private var indicatorView: NSProgressIndicator? = null

    init {
        nativeWidget.viewMaterializer = {
            ensureIndicator().also { syncIndicatorState() }
        }
        if (MacosAppKitDispatch.isMainThread()) {
            ensureIndicator()
            syncIndicatorState()
        }
    }

    private fun ensureIndicator(): NSProgressIndicator {
        indicatorView?.let { return it }
        val pi = NSProgressIndicator(frame = NSMakeRect(0.0, 0.0, 180.0, 20.0)).apply {
            setStyle(NSProgressIndicatorStyleBar)
            setMinValue(0.0)
            setMaxValue(1.0)
            setDisplayedWhenStopped(true)
        }
        indicatorView = pi
        nativeWidget.nsView = pi
        return pi
    }

    private fun syncIndicatorState() {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { syncIndicatorState() }
            return
        }
        val pi = ensureIndicator()
        val effectiveIndeterminate = indeterminateState || busyState
        pi.setIndeterminate(effectiveIndeterminate)
        pi.setDoubleValue(progressValue.toDouble())
        pi.setHidden(!nativeWidget.visible)
        pi.setAlphaValue(nativeWidget.opacity.coerceIn(0f, 1f).toDouble())
        pi.setToolTip(nativeWidget.tooltipText)
        if (effectiveIndeterminate && nativeWidget.visible) {
            pi.startAnimation(null)
        } else {
            pi.stopAnimation(null)
        }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val pi = indicatorView
        if (pi != null) {
            val fit = pi.fittingSize()
            return fit.useContents { Size(ceil(width).toInt().coerceAtLeast(120), ceil(height).toInt().coerceAtLeast(16)) }
        }
        return Size(180, 20)
    }

    override fun setProgress(progress: Float) {
        super.setProgress(progress)
        syncIndicatorState()
    }

    override fun setIndeterminate(indeterminate: Boolean) {
        super.setIndeterminate(indeterminate)
        syncIndicatorState()
    }

    override fun setBusy(busy: Boolean) {
        super.setBusy(busy)
        syncIndicatorState()
    }

    override fun setLabel(label: String?) {
        super.setLabel(label)
        indicatorView?.setToolTip(label ?: nativeWidget.tooltipText)
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { onNativeBoundsChanged(nativeWidget.bounds) }
            return
        }
        nativeWidget.ensureNsViewOnMain()
        val pi = indicatorView ?: return
        MacosAppKitDispatch.applyFrameAndTransform(pi, rect, nativeWidget.cssTransform, useAlignmentRectExpansion = false)
    }

    override fun onNativeVisibilityChanged(visible: Boolean) {
        syncIndicatorState()
    }

    override fun onNativeOpacityChanged(opacity: Float) {
        indicatorView?.setAlphaValue(opacity.coerceIn(0f, 1f).toDouble())
    }

    override fun onNativeTooltipChanged(tooltip: String?) {
        indicatorView?.setToolTip(tooltip)
    }

    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        indicatorView?.let { v -> v.window?.invalidateCursorRectsForView(v) }
    }

    override fun onNativeTransformChanged(transform: CssTransform?) {
        nativeWidget.cssTransform = transform
        val pi = indicatorView ?: return
        MacosAppKitDispatch.applyFrameAndTransform(pi, nativeWidget.bounds, transform, useAlignmentRectExpansion = false)
    }

    override fun onNativeStyleSynced() {
        syncIndicatorState()
    }

    override fun dispose() {
        val ind = indicatorView
        indicatorView = null
        MacosAppKitDispatch.onMainOrDefer {
            ind?.stopAnimation(null)
            ind?.removeFromSuperview()
        }
        super.dispose()
    }
}
