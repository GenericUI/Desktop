package com.nexustools.genericui.desktop.threading

import com.nexustools.genericui.threading.DefaultUiThreadDispatcherNativeImpl

/**
 * Shared Kotlin/Native desktop implementation of [com.nexustools.genericui.threading.UiThreadDispatcherNativeImpl]
 * across `:desktop:windows`, `:desktop:macos`, `:desktop:gtk`, and `:desktop:qt`.
 *
 * Enforces strict separation between OS UI thread rendering/widget updates and the Logic/event-handler
 * execution queue ([isSplitThreadingEnabled] == `true`).
 */
open class DesktopNativeUiThreadDispatcherImpl(
    val backendToolkit: String = "DesktopNative"
) : DefaultUiThreadDispatcherNativeImpl(
    isSplitThreadingEnabled = true
)
