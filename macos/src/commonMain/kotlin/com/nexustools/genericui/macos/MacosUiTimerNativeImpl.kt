package com.nexustools.genericui.macos

import com.nexustools.genericui.desktop.system.DesktopUiTimerNativeImpl

/**
 * macOS (`:desktop:macos`) implementation of [com.nexustools.genericui.nativeimpl.system.UiTimerNativeImpl]
 * backed by AppKit `NSTimer` (`NSRunLoopCommonModes`) when running an interactive GUI window and
 * deterministic virtual-clock stepping in headless unit tests.
 */
class MacosUiTimerNativeImpl : DesktopUiTimerNativeImpl(
    backendToolkit = "macOS NSTimer / GCD"
) {
    init {
        activeSchedulers.add(this)
    }

    override fun dispose() {
        activeSchedulers.remove(this)
        super.dispose()
    }

    companion object {
        private val activeSchedulers = linkedSetOf<MacosUiTimerNativeImpl>()

        fun advanceActiveTimers(deltaMs: Int): Int {
            if (deltaMs <= 0 || activeSchedulers.isEmpty()) return 0
            var totalFired = 0
            for (scheduler in activeSchedulers.toList()) {
                if (!scheduler.isDisposed && scheduler.activeTimerCount > 0) {
                    totalFired += scheduler.advanceTimeBy(deltaMs)
                }
            }
            return totalFired
        }
    }
}
