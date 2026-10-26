package com.nexustools.genericui.desktop.dock

import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.enums.dock.DockGroupMode
import com.nexustools.genericui.nativeimpl.dock.DockGroupNativeImpl

/**
 * Shared desktop base implementation of [DockGroupNativeImpl].
 */
open class DesktopDockGroupNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W
) : DesktopBaseContainerNativeImpl<W>(nativeWidget, "dock-group"), DockGroupNativeImpl<W> {

    protected var groupModeState: DockGroupMode = DockGroupMode.TABBED
    protected var activePanelIdx: Int = 0

    override fun setGroupMode(mode: DockGroupMode) {
        groupModeState = mode
        nativeWidget.attributes["groupMode"] = mode
    }

    override fun getGroupMode(): DockGroupMode = groupModeState

    override fun setActivePanelIndex(index: Int) {
        activePanelIdx = index.coerceAtLeast(0)
        nativeWidget.attributes["activePanelIndex"] = activePanelIdx
    }

    override fun getActivePanelIndex(): Int = activePanelIdx
}
