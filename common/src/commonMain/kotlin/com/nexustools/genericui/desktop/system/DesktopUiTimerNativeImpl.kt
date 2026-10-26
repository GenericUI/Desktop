package com.nexustools.genericui.desktop.system

import com.nexustools.genericui.nativeimpl.system.DefaultUiTimerNativeImpl
import com.nexustools.genericui.nativeimpl.system.UiTimerNativeImpl

/**
 * Shared desktop implementation of [UiTimerNativeImpl] across `:windows`, `:macos`, `:gtk`, and `:qt`.
 */
open class DesktopUiTimerNativeImpl(
    val backendToolkit: String = "Desktop"
) : DefaultUiTimerNativeImpl()
