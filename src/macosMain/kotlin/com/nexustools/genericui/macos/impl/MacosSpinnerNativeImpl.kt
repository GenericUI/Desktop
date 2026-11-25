@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos.impl

import com.nexustools.genericui.desktop.widget.DesktopSpinnerNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapSize
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.macos.MacosAppKitDispatch
import com.nexustools.genericui.macos.MacosViewHandle
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.useContents
import platform.AppKit.NSControlSizeLarge
import platform.AppKit.NSControlSizeRegular
import platform.AppKit.NSControlSizeSmall
import platform.AppKit.NSProgressIndicator
import platform.AppKit.NSProgressIndicatorStyleSpinning
import platform.AppKit.fittingSize
import platform.Foundation.NSMakeRect
import kotlin.math.ceil

/**
 * macOS AppKit retained spinning [NSProgressIndicator] implementation of [DesktopSpinnerNativeImpl].
 */
class MacosSpinnerNativeImpl(
    nativeWidget: MacosViewHandle
) : DesktopSpinnerNativeImpl<MacosViewHandle>(nativeWidget) {

    private var spinnerView: NSProgressIndicator? = null

    init {
        nativeWidget.viewMaterializer = {
            ensureSpinner().also { syncSpinnerState() }
        }
        if (MacosAppKitDispatch.isMainThread()) {
            ensureSpinner()
            syncSpinnerState()
        }
    }

    private fun ensureSpinner(): NSProgressIndicator {
        spinnerView?.let { return it }
        val pi = NSProgressIndicator(frame = NSMakeRect(0.0, 0.0, 24.0, 24.0)).apply {
            setStyle(NSProgressIndicatorStyleSpinning)
            setIndeterminate(true)
            setDisplayedWhenStopped(false)
        }
        spinnerView = pi
        nativeWidget.nsView = pi
        return pi
    }

    private fun syncSpinnerState() {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { syncSpinnerState() }
            return
        }
        val pi = ensureSpinner()
        val cs = when (styleSnapshot.size) {
            BootstrapSize.SM -> NSControlSizeSmall
            BootstrapSize.LG -> NSControlSizeLarge
            else -> NSControlSizeRegular
        }
        pi.setControlSize(cs)
        pi.setHidden(!nativeWidget.visible)
        pi.setAlphaValue(nativeWidget.opacity.coerceIn(0f, 1f).toDouble())
        pi.setToolTip(labelText ?: nativeWidget.tooltipText)
        if (nativeWidget.visible) {
            pi.startAnimation(null)
        } else {
            pi.stopAnimation(null)
        }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val pi = spinnerView
        if (pi != null) {
            val fit = pi.fittingSize()
            return fit.useContents {
                Size(ceil(width).toInt().coerceAtLeast(16), ceil(height).toInt().coerceAtLeast(16))
            }
        }
        return when (styleSnapshot.size) {
            BootstrapSize.SM -> Size(16, 16)
            BootstrapSize.LG -> Size(32, 32)
            else -> Size(24, 24)
        }
    }

    override fun setLabel(label: String?) {
        super.setLabel(label)
        syncSpinnerState()
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { onNativeBoundsChanged(nativeWidget.bounds) }
            return
        }
        nativeWidget.ensureNsViewOnMain()
        val pi = spinnerView ?: return
        MacosAppKitDispatch.applyFrameAndTransform(pi, rect, nativeWidget.cssTransform, useAlignmentRectExpansion = false)
    }

    override fun onNativeVisibilityChanged(visible: Boolean) {
        syncSpinnerState()
    }

    override fun onNativeOpacityChanged(opacity: Float) {
        spinnerView?.setAlphaValue(opacity.coerceIn(0f, 1f).toDouble())
    }

    override fun onNativeTooltipChanged(tooltip: String?) {
        spinnerView?.setToolTip(tooltip)
    }

    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        spinnerView?.let { v -> v.window?.invalidateCursorRectsForView(v) }
    }

    override fun onNativeTransformChanged(transform: CssTransform?) {
        nativeWidget.cssTransform = transform
        val pi = spinnerView ?: return
        MacosAppKitDispatch.applyFrameAndTransform(pi, nativeWidget.bounds, transform, useAlignmentRectExpansion = false)
    }

    override fun onNativeStyleSynced() {
        syncSpinnerState()
    }

    override fun dispose() {
        val sp = spinnerView
        spinnerView = null
        MacosAppKitDispatch.onMainOrDefer {
            sp?.stopAnimation(null)
            sp?.removeFromSuperview()
        }
        super.dispose()
    }
}
