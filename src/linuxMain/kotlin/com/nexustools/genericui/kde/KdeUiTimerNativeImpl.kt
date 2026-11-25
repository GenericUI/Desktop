@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.linux.LinuxReentrantLock
import com.nexustools.genericui.nativeimpl.system.DefaultUiTimerNativeImpl
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.COpaquePointer

/**
 * KDE (`Qt 6`) `QTimer` scheduler extending [DefaultUiTimerNativeImpl] so headless tests support
 * deterministic virtual clock progression while live sessions execute via real Qt 6 timers.
 */
class KdeUiTimerNativeImpl : DefaultUiTimerNativeImpl() {

    private val lock = LinuxReentrantLock()
    private val qtTimers = LinkedHashMap<Int, COpaquePointer>()

    override fun setTimeout(delayMs: Int, callback: () -> Unit): Int {
        val timerId = super.setTimeout(delayMs, callback)
        if (KdeApplicationNativeImpl.interactiveKdeGuiEnabled && timerId > 0) {
            scheduleQtTimer(timerId, delayMs.coerceAtLeast(1), repeat = false, callback = callback)
        }
        return timerId
    }

    override fun setInterval(intervalMs: Int, callback: () -> Unit): Int {
        val timerId = super.setInterval(intervalMs, callback)
        if (KdeApplicationNativeImpl.interactiveKdeGuiEnabled && timerId > 0) {
            scheduleQtTimer(timerId, intervalMs.coerceAtLeast(1), repeat = true, callback = callback)
        }
        return timerId
    }

    override fun clearTimer(timerId: Int) {
        super.clearTimer(timerId)
        stopAndDestroyQtTimer(timerId)
    }

    override fun clearAllTimers() {
        super.clearAllTimers()
        val ids = lock.withLock { qtTimers.keys.toList() }
        for (id in ids) {
            stopAndDestroyQtTimer(id)
        }
    }

    private fun scheduleQtTimer(timerId: Int, intervalMs: Int, repeat: Boolean, callback: () -> Unit) {
        val startOnUi = {
            if (!isDisposed && isTimerActive(timerId)) {
                KdeDynamicBindings.ensureQApplication()
                val timer = KdeDynamicBindings.createQTimer(null)
                lock.withLock { qtTimers[timerId] = timer }
                KdeVTableBridge.connectSignal(timer, "timeout()") {
                    if (isDisposed || !isTimerActive(timerId)) {
                        stopAndDestroyQtTimer(timerId)
                        return@connectSignal
                    }
                    if (!repeat) {
                        super.clearTimer(timerId)
                        stopAndDestroyQtTimer(timerId)
                    }
                    callback.invoke()
                }
                KdeDynamicBindings.startQTimer(timer, intervalMs)
            }
        }
        if (GenericUiThreading.isOnUiThread()) {
            startOnUi()
        } else {
            GenericUiThreading.runOnUi(startOnUi)
        }
    }

    private fun stopAndDestroyQtTimer(timerId: Int) {
        val timer = lock.withLock { qtTimers.remove(timerId) } ?: return
        val cleanup = {
            if (KdeVTableBridge.isPeerAlive(timer)) {
                KdeDynamicBindings.stopQTimer(timer)
                KdeVTableBridge.destroyObject(timer)
            }
        }
        if (GenericUiThreading.isOnUiThread()) {
            cleanup()
        } else {
            GenericUiThreading.runOnUi(cleanup)
        }
    }

    override fun dispose() {
        clearAllTimers()
        super.dispose()
    }
}
