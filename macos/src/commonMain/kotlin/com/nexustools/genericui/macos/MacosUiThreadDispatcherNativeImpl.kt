package com.nexustools.genericui.macos

import com.nexustools.genericui.desktop.threading.DesktopNativeUiThreadDispatcherImpl

/**
 * macOS (`:desktop:macos`) implementation of [com.nexustools.genericui.threading.UiThreadDispatcherNativeImpl]
 * separating AppKit main-thread (`NSApplication` / GCD main queue) UI updates from the Logic/event-handler thread.
 */
class MacosUiThreadDispatcherNativeImpl : DesktopNativeUiThreadDispatcherImpl(
    backendToolkit = "AppKit GCD Main Queue"
)
