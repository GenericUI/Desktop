package com.nexustools.genericui.gtk

import com.nexustools.genericui.desktop.system.DesktopNetworkMonitorNativeImpl

/**
 * GTK / GIO `GNetworkMonitor` (`g_network_monitor_get_default` / `g_network_monitor_get_network_available`)
 * implementation of [com.nexustools.genericui.nativeimpl.system.NetworkMonitorNativeImpl].
 */
class GtkNetworkMonitorNativeImpl : DesktopNetworkMonitorNativeImpl(
    backendToolkit = "GIO GNetworkMonitor"
)
