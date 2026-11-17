package com.nexustools.genericui.qt

import com.nexustools.genericui.desktop.dock.DesktopDockWindowNativeImpl

/**
 * Qt 6 floating utility/tool dock window (`QDockWidget`) implementation of
 * [com.nexustools.genericui.nativeimpl.dock.DockWindowNativeImpl].
 */
class QtDockWindowNativeImpl : DesktopDockWindowNativeImpl<QtWidgetHandle>(
    nativeWidget = QtWidgetHandle("QDockWidget_Floating")
) {
    init {
        nativeWidget.attributes["floatingUtilityWindow"] = true
        nativeWidget.attributes["qtToolWindow"] = true
        nativeWidget.qtWindowFlags = QtWidgetHandle.Qt_Window or QtWidgetHandle.Qt_WindowTitleHint or QtWidgetHandle.Qt_WindowCloseButtonHint
    }
}
