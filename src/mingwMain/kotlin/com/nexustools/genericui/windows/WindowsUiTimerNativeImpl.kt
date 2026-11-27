@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.nativeimpl.system.DefaultUiTimerNativeImpl
import com.nexustools.genericui.threading.GenericUiThreading
import platform.windows.HWND
import platform.windows.IsWindow
import platform.windows.KillTimer
import platform.windows.SetTimer

/**
 * Windows Win32 (`SetTimer` / `KillTimer` via `WM_TIMER` on any live host `HWND` resolved by
 * [Win32NativeWindowHost.resolveTimerHostHwnd]) implementation of
 * [com.nexustools.genericui.nativeimpl.system.UiTimerNativeImpl], preserving deterministic
 * virtual-clock scheduling (`advanceTimeBy`, `flushAll`) in headless unit tests.
 */
class WindowsUiTimerNativeImpl : DefaultUiTimerNativeImpl() {

    private class NativeTimerRecord(
        val id: Int,
        val periodMs: Int,
        val repeating: Boolean,
        val callback: () -> Unit,
        var armedHwnd: HWND? = null
    )

    private val lock = WindowsSpinLock()
    private val nativeTimers = LinkedHashMap<Int, NativeTimerRecord>()

    init {
        schedulersLock.withLock { activeSchedulers.add(this) }
    }

    override fun setTimeout(delayMs: Int, callback: () -> Unit): Int {
        val id = lock.withLock { super.setTimeout(delayMs, callback) }
        if (id > 0) {
            installNativeTimer(id, delayMs.coerceAtLeast(1), repeating = false, callback = callback)
        }
        return id
    }

    override fun setInterval(intervalMs: Int, callback: () -> Unit): Int {
        val id = lock.withLock { super.setInterval(intervalMs, callback) }
        if (id > 0) {
            installNativeTimer(id, intervalMs.coerceAtLeast(1), repeating = true, callback = callback)
        }
        return id
    }

    override fun clearTimer(timerId: Int) {
        val removed = lock.withLock {
            super.clearTimer(timerId)
            nativeTimers.remove(timerId)
        }
        val armed = removed?.armedHwnd
        if (armed != null) {
            removed.armedHwnd = null
            if (IsWindow(armed) != 0) {
                KillTimer(armed, timerId.toULong())
            }
        }
    }

    override fun clearAllTimers() {
        val removedList = lock.withLock {
            super.clearAllTimers()
            val copy = nativeTimers.values.toList()
            nativeTimers.clear()
            copy
        }
        for (record in removedList) {
            val armed = record.armedHwnd
            record.armedHwnd = null
            if (armed != null && IsWindow(armed) != 0) {
                KillTimer(armed, record.id.toULong())
            }
        }
    }

    override fun dispose() {
        clearAllTimers()
        schedulersLock.withLock { activeSchedulers.remove(this) }
        super.dispose()
    }

    private fun installNativeTimer(
        timerId: Int,
        periodMs: Int,
        repeating: Boolean,
        callback: () -> Unit
    ) {
        if (!WindowsApplicationNativeImpl.interactiveWin32GuiEnabled) return
        val normalizedPeriod = periodMs.coerceAtLeast(1)
        val record = NativeTimerRecord(
            id = timerId,
            periodMs = normalizedPeriod,
            repeating = repeating,
            callback = callback
        )
        lock.withLock {
            if (isDisposed) return
            nativeTimers[timerId] = record
        }
        val hostHwnd = Win32NativeWindowHost.resolveTimerHostHwnd()
        if (hostHwnd != null && IsWindow(hostHwnd) != 0) {
            val res = SetTimer(hostHwnd, timerId.toULong(), normalizedPeriod.toUInt(), null)
            if (res != 0uL) {
                val keepArmed = lock.withLock {
                    if (!isDisposed && nativeTimers[timerId] === record) {
                        record.armedHwnd = hostHwnd
                        true
                    } else {
                        false
                    }
                }
                if (!keepArmed) {
                    KillTimer(hostHwnd, timerId.toULong())
                }
            }
        }
    }

    private fun attachPendingTimers(hwnd: HWND) {
        if (IsWindow(hwnd) == 0) return
        val pending = lock.withLock {
            if (isDisposed) return@withLock emptyList()
            nativeTimers.values.filter { rec ->
                val armed = rec.armedHwnd
                armed == null || IsWindow(armed) == 0
            }
        }
        for (record in pending) {
            if (IsWindow(hwnd) == 0) break
            val period = record.periodMs.coerceAtLeast(1).toUInt()
            val res = SetTimer(hwnd, record.id.toULong(), period, null)
            if (res != 0uL) {
                val keepArmed = lock.withLock {
                    if (!isDisposed && nativeTimers[record.id] === record) {
                        record.armedHwnd = hwnd
                        true
                    } else {
                        false
                    }
                }
                if (!keepArmed) {
                    KillTimer(hwnd, record.id.toULong())
                }
            }
        }
    }

    private fun rehomeTimersFromHwnd(destroyedHwnd: HWND) {
        val toKill = lock.withLock {
            val matches = nativeTimers.values.filter { it.armedHwnd == destroyedHwnd }
            for (rec in matches) {
                rec.armedHwnd = null
            }
            matches
        }
        for (rec in toKill) {
            KillTimer(destroyedHwnd, rec.id.toULong())
        }
        val next = Win32NativeWindowHost.resolveTimerHostHwnd()
            ?.takeIf { it != destroyedHwnd && IsWindow(it) != 0 }
        if (next != null) {
            attachPendingTimers(next)
        }
    }

    private fun handleNativeTimerFired(timerId: Int): Boolean {
        var armedToKill: HWND? = null
        val record = lock.withLock {
            val found = nativeTimers[timerId] ?: return@withLock null
            if (!found.repeating) {
                nativeTimers.remove(timerId)
                super.clearTimer(timerId)
                armedToKill = found.armedHwnd
                found.armedHwnd = null
            }
            found
        } ?: return false

        val killHwnd = armedToKill
        if (killHwnd != null && IsWindow(killHwnd) != 0) {
            KillTimer(killHwnd, timerId.toULong())
        }
        GenericUiThreading.runOnLogic {
            record.callback.invoke()
        }
        return true
    }

    companion object {
        private val schedulersLock = WindowsSpinLock()
        private val activeSchedulers = mutableListOf<WindowsUiTimerNativeImpl>()

        internal fun dispatchWin32Timer(timerId: Int): Boolean {
            val snapshot = schedulersLock.withLock { activeSchedulers.toList() }
            for (scheduler in snapshot) {
                if (scheduler.handleNativeTimerFired(timerId)) {
                    return true
                }
            }
            return false
        }

        internal fun attachPendingTimersToHwnd(hwnd: HWND) {
            if (IsWindow(hwnd) == 0) return
            val snapshot = schedulersLock.withLock { activeSchedulers.toList() }
            for (scheduler in snapshot) {
                scheduler.attachPendingTimers(hwnd)
            }
        }

        internal fun rehomeTimersFromDestroyedHwnd(destroyedHwnd: HWND) {
            val snapshot = schedulersLock.withLock { activeSchedulers.toList() }
            for (scheduler in snapshot) {
                scheduler.rehomeTimersFromHwnd(destroyedHwnd)
            }
        }
    }
}
