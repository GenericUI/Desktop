package com.nexustools.genericui.desktop.widget

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.enums.ui.Placement
import com.nexustools.genericui.nativeimpl.overlay.PopoverNativeImpl

/**
 * Shared desktop base implementation of [PopoverNativeImpl].
 */
open class DesktopPopoverNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W
) : DesktopBaseContainerNativeImpl<W>(nativeWidget, "popover"), PopoverNativeImpl<W> {

    protected var titleText: String? = null
    protected var placementState: Placement = Placement.BOTTOM

    override fun setTitle(title: String?) {
        titleText = title
        nativeWidget.attributes["title"] = title
    }

    override fun setPlacement(placement: Placement) {
        placementState = placement
        nativeWidget.attributes["placement"] = placement
    }

    override fun showAnchored(anchor: NativeImpl<*>?, x: Int, y: Int) {
        nativeWidget.attributes["anchor"] = anchor
        nativeWidget.attributes["anchorX"] = x
        nativeWidget.attributes["anchorY"] = y
        setVisible(true)
    }

    override fun hide() {
        setVisible(false)
    }
}
