package com.nexustools.genericui.macos

import com.nexustools.genericui.desktop.system.DesktopNetworkMonitorNativeImpl

/**
 * macOS Network framework (`nw_path_monitor_t` / `NWPathMonitor`) implementation of
 * [com.nexustools.genericui.nativeimpl.system.NetworkMonitorNativeImpl].
 */
class MacosNetworkMonitorNativeImpl : DesktopNetworkMonitorNativeImpl(
    backendToolkit = "macOS Network.framework NWPathMonitor"
)
