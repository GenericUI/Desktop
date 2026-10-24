package com.nexustools.genericui.desktop.dock

import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.enums.dock.DockPosition
import com.nexustools.genericui.nativeimpl.dock.DockAreaNativeImpl

/**
 * Shared desktop base implementation of [DockAreaNativeImpl].
 */
open class DesktopDockAreaNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W
) : DesktopBaseContainerNativeImpl<W>(nativeWidget, "dock-area"), DockAreaNativeImpl<W> {

    protected var dockPosition: DockPosition = DockPosition.LEFT
    protected var collapsedState: Boolean = false

    override fun setPosition(position: DockPosition) {
        dockPosition = position
        nativeWidget.attributes["dockPosition"] = position
    }

    override fun setCollapsed(collapsed: Boolean) {
        collapsedState = collapsed
        nativeWidget.attributes["collapsed"] = collapsed
    }

    override fun isCollapsed(): Boolean = collapsedState
}
