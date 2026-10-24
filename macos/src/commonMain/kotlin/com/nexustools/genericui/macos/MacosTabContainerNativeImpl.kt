package com.nexustools.genericui.macos

import com.nexustools.genericui.desktop.widget.DesktopTabContainerNativeImpl

/**
 * macOS AppKit `NSTabView` implementation of [com.nexustools.genericui.nativeimpl.navigation.TabContainerNativeImpl].
 */
class MacosTabContainerNativeImpl : DesktopTabContainerNativeImpl<MacosViewHandle>(
    nativeWidget = MacosViewHandle("NSTabView"),
    supportsNativeFadeTransition = true
)
