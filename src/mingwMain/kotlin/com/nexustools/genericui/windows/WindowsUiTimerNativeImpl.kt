@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.nativeimpl.system.DefaultUiTimerNativeImpl
import com.nexustools.genericui.threading.GenericUiThreading
import platform.windows.KillTimer
import platform.windows.SetTimer

/**
 * Windows Win32 (`SetTimer` / `KillTimer` via `WM_TIMER` on [Win32NativeWindowHost.activeHwnd])
 * implementation of [com.nexustools.genericui.nativeimpl.system.UiTimerNativeImpl], preserving
 * deterministic virtual-clock scheduling (`advanceTimeBy`, `flushAll`) in headless unit tests.
 */
class WindowsUiTimerNativeImpl : DefaultUiTimerNativeImpl() {

    private class NativeTimerRecord(
        val id: Int,
        val repeating: Boolean,
        val callback: () -> Unit
    )

    private val lock = WindowsSpinLock()
    private val nativeTimers = LinkedHashMap<Int, NativeTimerRecord>()

    init {
        lock.withLock { activeSchedulers.add(this) }
    }

    override fun setTimeout(delayMs: Int, callback: () -> Unit): Int {
        val id = super.setTimeout(delayMs, callback)
        if (id > 0) {
            installNativeTimer(id, delayMs.coerceAtLeast(1), repeating = false, callback = callback)
        }
        return id
    }

    override fun setInterval(intervalMs: Int, callback: () -> Unit): Int {
        val id = super.setInterval(intervalMs, callback)
        if (id > 0) {
            installNativeTimer(id, intervalMs.coerceAtLeast(1), repeating = true, callback = callback)
        }
        return id
    }

    override fun clearTimer(timerId: Int) {
        super.clearTimer(timerId)
        val removed = lock.withLock { nativeTimers.remove(timerId) }
        if (removed != null) {
            Win32NativeWindowHost.activeHwnd?.let { hwnd ->
                KillTimer(hwnd, timerId.toULong())
            }
        }
    }

    override fun clearAllTimers() {
        super.clearAllTimers()
        val ids = lock.withLock {
            val copy = nativeTimers.keys.toList()
            nativeTimers.clear()
            copy
        }
        val hwnd = Win32NativeWindowHost.activeHwnd
        if (hwnd != null) {
            for (id in ids) {
                KillTimer(hwnd, id.toULong())
            }
        }
    }

    override fun dispose() {
        clearAllTimers()
        lock.withLock { activeSchedulers.remove(this) }
        super.dispose()
    }

    private fun installNativeTimer(
        timerId: Int,
        periodMs: Int,
        repeating: Boolean,
        callback: () -> Unit
    ) {
        if (!WindowsApplicationNativeImpl.interactiveWin32GuiEnabled) return
        val hwnd = Win32NativeWindowHost.activeHwnd ?: return
        lock.withLock {
            nativeTimers[timerId] = NativeTimerRecord(timerId, repeating, callback)
        }
        SetTimer(hwnd, timerId.toULong(), periodMs.coerceAtLeast(1).toUInt(), null)
    }

    private fun handleNativeTimerFired(timerId: Int): Boolean {
        val record = lock.withLock {
            val found = nativeTimers[timerId] ?: return@withLock null
            if (!found.repeating) {
                nativeTimers.remove(timerId)
            }
            found
        } ?: return false

        if (!record.repeating) {
            super.clearTimer(timerId)
            Win32NativeWindowHost.activeHwnd?.let { hwnd ->
                KillTimer(hwnd, timerId.toULong())
            }
        }
        GenericUiThreading.runOnLogic {
            record.callback.invoke()
        }
        return true
    }

    companion object {
        private val activeSchedulers = mutableListOf<WindowsUiTimerNativeImpl>()

        internal fun dispatchWin32Timer(timerId: Int): Boolean {
            val snapshot = activeSchedulers.toList()
            for (scheduler in snapshot) {
                if (scheduler.handleNativeTimerFired(timerId)) {
                    return true
                }
            }
            return false
        }
    }
}
