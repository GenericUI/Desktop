package com.nexustools.genericui.macos

import com.nexustools.genericui.desktop.dialog.DesktopDialogWindowNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size

/**
 * macOS AppKit `NSPanel` / `NSWindow` sheet or modal dialog implementation of
 * [com.nexustools.genericui.nativeimpl.dialog.DialogWindowNativeImpl].
 */
open class MacosDialogWindowNativeImpl(
    nativeWidget: MacosViewHandle = MacosViewHandle("NSPanel_SheetOrModal").apply {
        attributes["isSeparateNativeWindow"] = true
        attributes["inWindowOverlay"] = false
        attributes["attachedToMainWindow"] = true
    }
) : DesktopDialogWindowNativeImpl<MacosViewHandle>(nativeWidget) {

    val isSeparateNativeWindow: Boolean
        get() = nativeWidget.attributes["isSeparateNativeWindow"] as? Boolean ?: true

    val inWindowOverlay: Boolean
        get() = nativeWidget.attributes["inWindowOverlay"] as? Boolean ?: false

    val attachedToMainWindow: Boolean
        get() = nativeWidget.attributes["attachedToMainWindow"] as? Boolean ?: (ownerWindow != null)

    override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
        super.setBounds(x, y, width, height)
        onBoundsChangedHook?.invoke(this)
    }

    override fun setPreferredSize(size: Size?) {
        super.setPreferredSize(size)
        onBoundsChangedHook?.invoke(this)
    }

    override fun closeDialog() {
        super.closeDialog()
        if (MacosApplicationNativeImpl.interactiveMacosGuiEnabled) {
            MacosNativeWindowHost.closeDialogWindow(this)
        }
    }

    internal fun syncHostDialogBounds(widthPx: Int, heightPx: Int) {
        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        val changed = boundsWidth != w || boundsHeight != h ||
            nativeWidget.bounds.width != w || nativeWidget.bounds.height != h
        boundsWidth = w
        boundsHeight = h
        nativeWidget.bounds = Rect(0, 0, w, h)
        if (changed) {
            onBoundsChangedHook?.invoke(this)
        }
    }

    companion object {
        var onBoundsChangedHook: ((DesktopDialogWindowNativeImpl<*>) -> Unit)? = null
    }
}
