package com.nexustools.genericui.windows

import com.nexustools.genericui.desktop.threading.DesktopNativeUiThreadDispatcherImpl

/**
 * Windows (`:desktop:windows`) implementation of [com.nexustools.genericui.threading.UiThreadDispatcherNativeImpl]
 * separating Win32 message-loop (`HWND` / Direct2D) UI updates from the Logic/event-handler thread.
 */
class WindowsUiThreadDispatcherNativeImpl : DesktopNativeUiThreadDispatcherImpl(
    backendToolkit = "Win32 PostMessageW / UI Queue"
)
