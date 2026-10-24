package com.nexustools.genericui.qt

import com.nexustools.genericui.desktop.system.DesktopNetworkMonitorNativeImpl

/**
 * Qt 6 Network (`QNetworkInformation::instance()->reachability()`) implementation of
 * [com.nexustools.genericui.nativeimpl.system.NetworkMonitorNativeImpl].
 */
class QtNetworkMonitorNativeImpl : DesktopNetworkMonitorNativeImpl(
    backendToolkit = "Qt6 QNetworkInformation"
)
