package com.nexustools.genericui.desktop.dock

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.enums.dock.DockPosition
import com.nexustools.genericui.nativeimpl.dock.DockAreaNativeImpl
import com.nexustools.genericui.nativeimpl.dock.DockWorkspaceNativeImpl

/**
 * Shared desktop base implementation of [DockWorkspaceNativeImpl] for GIMP/Photoshop-style
 * Single-Window and Multi-Window dock workspaces.
 */
open class DesktopDockWorkspaceNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W
) : DesktopBaseContainerNativeImpl<W>(nativeWidget, "dock-workspace"), DockWorkspaceNativeImpl<W> {

    val coordinator: DesktopMultiWindowDockCoordinator = DesktopMultiWindowDockCoordinator()
    protected var centralDocument: NativeImpl<*>? = null
    protected val dockAreas: MutableMap<DockPosition, DockAreaNativeImpl<*>> = linkedMapOf()
    protected var currentWindowMode: String = "SINGLE_WINDOW_MODE"

    override fun setCentralDocumentNative(centralImpl: NativeImpl<*>?) {
        centralDocument = centralImpl
        nativeWidget.attributes["centralDocument"] = centralImpl
    }

    override fun attachDockAreaNative(position: DockPosition, areaImpl: DockAreaNativeImpl<*>) {
        dockAreas[position] = areaImpl
        addChildNative(areaImpl)
    }

    override fun setWindowModeNative(modeName: String) {
        currentWindowMode = modeName
        nativeWidget.attributes["windowMode"] = modeName
        requestRepaintNative()
    }

    fun getWindowMode(): String = currentWindowMode
}
