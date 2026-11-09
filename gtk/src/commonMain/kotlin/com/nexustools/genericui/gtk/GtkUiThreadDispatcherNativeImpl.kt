package com.nexustools.genericui.gtk

import com.nexustools.genericui.desktop.threading.DesktopNativeUiThreadDispatcherImpl

/**
 * GTK 4 (`:desktop:gtk`) implementation of [com.nexustools.genericui.threading.UiThreadDispatcherNativeImpl]
 * separating GLib/GTK main-context (`g_main_context_invoke`) UI updates from the Logic/event-handler thread.
 */
class GtkUiThreadDispatcherNativeImpl : DesktopNativeUiThreadDispatcherImpl(
    backendToolkit = "GTK4 g_main_context_invoke"
)
