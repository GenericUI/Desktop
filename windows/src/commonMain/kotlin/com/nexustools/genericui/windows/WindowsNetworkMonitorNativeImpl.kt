package com.nexustools.genericui.windows

import com.nexustools.genericui.desktop.system.DesktopNetworkMonitorNativeImpl

/**
 * Windows network connectivity monitor using COM `INetworkListManager` (`NLM_CONNECTIVITY`).
 */
class WindowsNetworkMonitorNativeImpl : DesktopNetworkMonitorNativeImpl(
    backendToolkit = "Windows INetworkListManager"
)
