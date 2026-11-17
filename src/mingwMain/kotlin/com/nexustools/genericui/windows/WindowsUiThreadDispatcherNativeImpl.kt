@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.desktop.threading.DesktopNativeUiThreadDispatcherImpl
import com.nexustools.genericui.threading.GenericUiThreading
import platform.windows.GetCurrentThreadId
import platform.windows.PostMessageW
import platform.windows.PostThreadMessageW
import kotlin.concurrent.AtomicInt

/**
 * Windows (`mingwMain`) implementation of [com.nexustools.genericui.threading.UiThreadDispatcherNativeImpl]
 * separating Win32 message-loop (`HWND` / Direct2D) UI updates from the Logic/event-handler thread
 * and posting `WM_APP_UI_DISPATCH` wakeups to the live Win32 message loop thread.
 */
class WindowsUiThreadDispatcherNativeImpl : DesktopNativeUiThreadDispatcherImpl(
    backendToolkit = "Win32 PostMessageW / UI Queue"
) {
    init {
        activeDispatcher = this
    }

    override fun isOnUiThread(): Boolean {
        val registeredThreadId = registeredUiThreadId.value
        if (registeredThreadId != 0 && GetCurrentThreadId().toInt() != registeredThreadId) {
            return false
        }
        return super.isOnUiThread()
    }

    override fun onUiTaskQueuedFromLogic() {
        if (!WindowsApplicationNativeImpl.interactiveWin32GuiEnabled) return
        val hwnd = Win32NativeWindowHost.activeHwnd
        if (hwnd != null) {
            PostMessageW(hwnd, Win32NativeWindowHost.WM_APP_UI_DISPATCH, 0uL, 0L)
            return
        }
        val threadId = registeredUiThreadId.value
        if (threadId != 0 && GetCurrentThreadId().toInt() != threadId) {
            PostThreadMessageW(threadId.toUInt(), Win32NativeWindowHost.WM_APP_UI_DISPATCH, 0uL, 0L)
        }
    }

    companion object {
        private val registeredUiThreadId = AtomicInt(0)
        internal var activeDispatcher: WindowsUiThreadDispatcherNativeImpl? = null

        internal fun attachCurrentThreadAsUiThread() {
            registeredUiThreadId.value = GetCurrentThreadId().toInt()
        }

        internal fun clearUiThreadBinding() {
            registeredUiThreadId.value = 0
        }

        internal fun drainPendingUiQueue() {
            val dispatcher = activeDispatcher
            if (dispatcher != null) {
                dispatcher.drainUiQueue()
            } else {
                GenericUiThreading.flushAllQueues()
            }
        }
    }
}
