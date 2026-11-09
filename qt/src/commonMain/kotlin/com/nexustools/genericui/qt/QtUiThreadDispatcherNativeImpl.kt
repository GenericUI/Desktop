package com.nexustools.genericui.qt

import com.nexustools.genericui.desktop.threading.DesktopNativeUiThreadDispatcherImpl

/**
 * Qt 6 (`:desktop:qt`) implementation of [com.nexustools.genericui.threading.UiThreadDispatcherNativeImpl]
 * separating `QApplication` main-thread (`QMetaObject::invokeMethod`) UI updates from the Logic/event-handler thread.
 */
class QtUiThreadDispatcherNativeImpl : DesktopNativeUiThreadDispatcherImpl(
    backendToolkit = "Qt6 QMetaObject::invokeMethod"
)
