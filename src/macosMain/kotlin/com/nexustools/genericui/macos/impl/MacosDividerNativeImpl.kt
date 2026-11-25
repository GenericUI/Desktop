@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos.impl

import com.nexustools.genericui.desktop.widget.DesktopDividerNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.macos.MacosAppKitDispatch
import com.nexustools.genericui.macos.MacosViewHandle
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssTransform
import platform.AppKit.NSBox
import platform.AppKit.NSBoxSeparator
import platform.Foundation.NSMakeRect

/**
 * macOS AppKit retained [NSBox] (`NSBoxSeparator`) implementation of [DesktopDividerNativeImpl].
 */
class MacosDividerNativeImpl(
    nativeWidget: MacosViewHandle
) : DesktopDividerNativeImpl<MacosViewHandle>(nativeWidget) {

    private var separatorBox: NSBox? = null

    init {
        nativeWidget.viewMaterializer = { ensureBox() }
        if (MacosAppKitDispatch.isMainThread()) {
            ensureBox()
        }
    }

    private fun ensureBox(): NSBox {
        separatorBox?.let { return it }
        val box = NSBox(frame = NSMakeRect(0.0, 0.0, 100.0, 1.0)).apply {
            setBoxType(NSBoxSeparator)
        }
        separatorBox = box
        nativeWidget.nsView = box
        box.setHidden(!nativeWidget.visible)
        box.setAlphaValue(nativeWidget.opacity.coerceIn(0f, 1f).toDouble())
        box.setToolTip(nativeWidget.tooltipText)
        return box
    }

    private fun isVertical(): Boolean =
        "vr" in nativeWidget.cachedAllClassTokens ||
            "divider-vertical" in nativeWidget.cachedAllClassTokens

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        return if (isVertical()) Size(1, 24) else Size(64, 1)
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { onNativeBoundsChanged(nativeWidget.bounds) }
            return
        }
        nativeWidget.ensureNsViewOnMain()
        val box = separatorBox ?: return
        MacosAppKitDispatch.applyFrameAndTransform(box, rect, nativeWidget.cssTransform, useAlignmentRectExpansion = false)
    }

    override fun onNativeVisibilityChanged(visible: Boolean) {
        separatorBox?.setHidden(!visible)
    }

    override fun onNativeOpacityChanged(opacity: Float) {
        separatorBox?.setAlphaValue(opacity.coerceIn(0f, 1f).toDouble())
    }

    override fun onNativeTooltipChanged(tooltip: String?) {
        separatorBox?.setToolTip(tooltip)
    }

    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        separatorBox?.let { v -> v.window?.invalidateCursorRectsForView(v) }
    }

    override fun onNativeTransformChanged(transform: CssTransform?) {
        nativeWidget.cssTransform = transform
        val box = separatorBox ?: return
        MacosAppKitDispatch.applyFrameAndTransform(box, nativeWidget.bounds, transform, useAlignmentRectExpansion = false)
    }

    override fun dispose() {
        val view = separatorBox
        separatorBox = null
        MacosAppKitDispatch.detachViewOnMain(view)
        super.dispose()
    }
}
