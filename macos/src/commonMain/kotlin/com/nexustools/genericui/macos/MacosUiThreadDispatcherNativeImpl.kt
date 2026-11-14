@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.desktop.threading.DesktopNativeUiThreadDispatcherImpl
import platform.Foundation.NSThread
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.concurrent.AtomicInt

/**
 * macOS (`:desktop:macos`) implementation of [com.nexustools.genericui.threading.UiThreadDispatcherNativeImpl]
 * separating AppKit main-thread (`NSApplication` / GCD main queue) UI updates from the Logic/event-handler thread.
 */
class MacosUiThreadDispatcherNativeImpl : DesktopNativeUiThreadDispatcherImpl(
    backendToolkit = "AppKit GCD Main Queue"
) {
    private val mainWakeupScheduled = AtomicInt(0)

    override fun isOnUiThread(): Boolean {
        if (MacosApplicationNativeImpl.interactiveMacosGuiEnabled && !NSThread.isMainThread) {
            return false
        }
        return super.isOnUiThread()
    }

    override fun dispatchToUi(block: () -> Unit) {
        if (!MacosApplicationNativeImpl.interactiveMacosGuiEnabled) {
            super.dispatchToUi(block)
            return
        }
        if (!NSThread.isMainThread) {
            dispatch_async(dispatch_get_main_queue()) {
                super.dispatchToUi(block)
                MacosNativeWindowHost.requestRedraw()
            }
            return
        }
        super.dispatchToUi(block)
        MacosNativeWindowHost.requestRedraw()
    }

    override fun dispatchToLogic(block: () -> Unit) {
        if (!MacosApplicationNativeImpl.interactiveMacosGuiEnabled) {
            super.dispatchToLogic(block)
            return
        }
        if (!NSThread.isMainThread) {
            dispatch_async(dispatch_get_main_queue()) {
                super.dispatchToLogic(block)
                MacosNativeWindowHost.requestRedraw()
            }
            return
        }
        super.dispatchToLogic(block)
        MacosNativeWindowHost.requestRedraw()
    }

    override fun onUiTaskQueuedFromLogic() {
        if (!MacosApplicationNativeImpl.interactiveMacosGuiEnabled) {
            super.onUiTaskQueuedFromLogic()
            return
        }
        if (mainWakeupScheduled.compareAndSet(0, 1)) {
            dispatch_async(dispatch_get_main_queue()) {
                mainWakeupScheduled.value = 0
                drainUiQueue()
                MacosNativeWindowHost.requestRedraw()
            }
        }
    }
}
