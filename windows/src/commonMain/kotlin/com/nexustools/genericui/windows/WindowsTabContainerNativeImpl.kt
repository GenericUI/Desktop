package com.nexustools.genericui.windows

import com.nexustools.genericui.desktop.widget.DesktopTabContainerNativeImpl

/**
 * Win32 native tab control (`WC_TABCONTROLW` from `comctl32.dll`) implementation of
 * [com.nexustools.genericui.nativeimpl.navigation.TabContainerNativeImpl].
 */
class WindowsTabContainerNativeImpl : DesktopTabContainerNativeImpl<Win32HwndHandle>(
    nativeWidget = Win32HwndHandle("SysTabControl32"),
    supportsNativeFadeTransition = true
)
