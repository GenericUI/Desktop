package com.nexustools.genericui.gtk

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.dialog.DesktopDialogWindowNativeImpl
import com.nexustools.genericui.desktop.layout.DesktopContainerLayoutController
import com.nexustools.genericui.enums.bootstrap.FlexDirection
import com.nexustools.genericui.enums.css.CssDisplay
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.gtk.widget.GtkRetainedContainerSupport
import com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge
import kotlin.math.roundToInt

open class GtkDialogWindowNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkWindow_Modal").apply {
        attributes["isSeparateNativeWindow"] = true
        attributes["inWindowOverlay"] = false
        attributes["attachedToMainWindow"] = true
    }
) : DesktopDialogWindowNativeImpl<GtkWidgetHandle>(nativeWidget) {

    init {
        nativeWidget.ownerImpl = this
        layoutController = DesktopContainerLayoutController(
            host = this,
            defaultDisplay = CssDisplay.FLEX,
            defaultFlexDirection = FlexDirection.COLUMN,
            applyScaleToBounds = false
        )
        nativeWidget.customLayoutController = layoutController
    }

    val isSeparateNativeWindow: Boolean
        get() = nativeWidget.attributes["isSeparateNativeWindow"] as? Boolean ?: true

    val inWindowOverlay: Boolean
        get() = nativeWidget.attributes["inWindowOverlay"] as? Boolean ?: false

    val attachedToMainWindow: Boolean
        get() = nativeWidget.attributes["attachedToMainWindow"] as? Boolean ?: (ownerWindow != null)

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        if (childImpls.isEmpty()) {
            return Size(boundsWidth.coerceAtLeast(320), boundsHeight.coerceAtLeast(180))
        }
        val measured = layoutController?.measurePreferredSize(maxWidth, maxHeight) ?: return super.measureIntrinsicSize(maxWidth, maxHeight)
        val maxW = (maxWidth?.roundToInt() ?: 960).coerceAtLeast(280)
        val maxH = (maxHeight?.roundToInt() ?: 760).coerceAtLeast(140)
        return Size(
            width = measured.width.coerceIn(280, maxW),
            height = measured.height.coerceIn(140, maxH)
        )
    }

    override fun onChildAddedNative(childImpl: NativeImpl<*>, index: Int) {
        GtkRetainedContainerSupport.attachChildWidgetIfRealized(this, childImpl)
    }

    override fun onChildRemovedNative(childImpl: NativeImpl<*>) {
        GtkRetainedContainerSupport.detachChildWidget(this, childImpl)
    }

    override fun onChildrenClearedNative() {
        GtkRetainedContainerSupport.clearChildWidgets(this)
    }

    override fun onNativeStyleSynced() {
        GtkWidgetLifecycleBridge.onStyleSynced(this)
    }
}
