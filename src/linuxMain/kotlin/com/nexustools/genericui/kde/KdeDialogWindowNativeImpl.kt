@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.dialog.DesktopDialogWindowNativeImpl
import com.nexustools.genericui.enums.dialog.DialogPresentationStyle
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl
import kotlin.math.roundToInt

/**
 * KDE (`QDialog` / translucent backdrop-dismissible modal overlay) implementation of
 * [DesktopDialogWindowNativeImpl].
 */
open class KdeDialogWindowNativeImpl(
    nativeWidget: KdeWidgetHandle = KdeWidgetHandle("QDialog").apply {
        attributes["isSeparateNativeWindow"] = true
        attributes["inWindowOverlay"] = false
        attributes["attachedToMainWindow"] = true
        isTopLevelWindow = true
    }
) : DesktopDialogWindowNativeImpl<KdeWidgetHandle>(nativeWidget) {

    val isSeparateNativeWindow: Boolean
        get() = nativeWidget.attributes["isSeparateNativeWindow"] as? Boolean ?: true

    val inWindowOverlay: Boolean
        get() = nativeWidget.attributes["inWindowOverlay"] as? Boolean ?: false

    val attachedToMainWindow: Boolean
        get() = nativeWidget.attributes["attachedToMainWindow"] as? Boolean ?: (ownerWindow != null)

    init {
        if (KdeApplicationNativeImpl.interactiveKdeGuiEnabled || nativeWidget.qWidgetPtr == null) {
            KdeDynamicBindings.ensureQApplication()
            val dlgPtr = KdeDynamicBindings.createQDialog(null, KdeDynamicBindings.WINDOW_DIALOG)
            nativeWidget.attachToQWidget(dlgPtr, "QDialog", customPainted = false)
            KdeDynamicBindings.setDialogModal(dlgPtr, modalState)
            KdeVTableBridge.attachWidgetHooks(dlgPtr, KdeVTableBridge.HOOK_RESIZE or KdeVTableBridge.HOOK_CLOSE) {
                onResize = { _, w, h ->
                    layoutChildrenInBounds(w.toFloat(), h.toFloat())
                }
                onClose = { _ ->
                    closeDialog()
                    false
                }
            }
        }
    }

    override fun onChildAddedNative(childImpl: NativeImpl<*>, index: Int) {
        super.onChildAddedNative(childImpl, index)
        (childImpl.nativeWidget as? KdeWidgetHandle)?.syncChildParentAndVisibility(nativeWidget)
        val w = nativeWidget.bounds.width.takeIf { it > 0 } ?: 480
        val h = nativeWidget.bounds.height.takeIf { it > 0 } ?: 360
        layoutChildrenInBounds(w.toFloat(), h.toFloat())
    }

    override fun onChildRemovedNative(childImpl: NativeImpl<*>) {
        (childImpl.nativeWidget as? KdeWidgetHandle)?.syncChildParentAndVisibility(null)
        super.onChildRemovedNative(childImpl)
    }

    override fun onChildrenClearedNative() {
        for (child in childImpls) {
            (child.nativeWidget as? KdeWidgetHandle)?.syncChildParentAndVisibility(null)
        }
        super.onChildrenClearedNative()
    }

    override fun layoutChildrenInBounds(width: Float, height: Float) {
        super.layoutChildrenInBounds(width, height)
        for (child in childImpls) {
            if (child is DesktopBaseNativeImpl<*>) {
                val pref = child.measureIntrinsicSize(width, height)
                val cw = if (pref.width > 0 && presentationStyleState != DialogPresentationStyle.MODELESS) {
                    minOf(width, pref.width.toFloat().coerceAtLeast(width * 0.5f))
                } else width
                val ch = if (pref.height > 0) minOf(height, pref.height.toFloat()) else height
                child.setBounds(Rect(0f, 0f, cw.coerceAtLeast(1f), ch.coerceAtLeast(1f)))
            }
        }
    }

    override fun setTitle(title: String) {
        super.setTitle(title)
        nativeWidget.qWidgetPtr?.let { ptr ->
            if (!nativeWidget.kdeDestroyed) KdeDynamicBindings.setWindowTitle(ptr, title)
        }
    }

    override fun setModal(modal: Boolean) {
        super.setModal(modal)
        nativeWidget.qWidgetPtr?.let { ptr ->
            if (!nativeWidget.kdeDestroyed) KdeDynamicBindings.setDialogModal(ptr, modal)
        }
    }

    override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
        super.setBounds(x, y, width, height)
        nativeWidget.applyBoundsToNative(Rect(x, y, width, height))
        layoutChildrenInBounds(width.toFloat(), height.toFloat())
    }

    override fun setPreferredSize(size: Size?) {
        super.setPreferredSize(size)
        if (size != null && nativeWidget.qWidgetPtr != null) {
            KdeDynamicBindings.resizeWidget(nativeWidget.qWidgetPtr!!, size.width, size.height)
            layoutChildrenInBounds(size.width.toFloat(), size.height.toFloat())
        }
    }

    override fun showDialog(ownerWindow: WindowNativeImpl<*>?) {
        super.showDialog(ownerWindow)
        if (KdeApplicationNativeImpl.interactiveKdeGuiEnabled) {
            KdeNativeWindowHost.presentDialog(this, ownerWindow)
        } else {
            nativeWidget.qWidgetPtr?.let { ptr ->
                val bw = nativeWidget.bounds.width.takeIf { it > 0 } ?: 480
                val bh = nativeWidget.bounds.height.takeIf { it > 0 } ?: 360
                layoutChildrenInBounds(bw.toFloat(), bh.toFloat())
                KdeDynamicBindings.resizeWidget(ptr, bw, bh)
            }
        }
    }

    override fun closeDialog() {
        super.closeDialog()
        if (KdeApplicationNativeImpl.interactiveKdeGuiEnabled) {
            KdeNativeWindowHost.closeDialog(this)
        } else {
            nativeWidget.qWidgetPtr?.let { KdeDynamicBindings.setWidgetVisible(it, false) }
        }
    }

    override fun bringToFront() {
        super.bringToFront()
        nativeWidget.qWidgetPtr?.let {
            KdeDynamicBindings.raiseWidget(it)
            KdeDynamicBindings.activateWindow(it)
        }
    }

    override fun dispose() {
        nativeWidget.destroyQWidget()
        super.dispose()
    }
}
