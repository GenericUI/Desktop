package com.nexustools.genericui.swing

import com.nexustools.genericui.nativeimpl.system.DefaultUiTimerNativeImpl
import com.nexustools.genericui.nativeimpl.system.UiTimerNativeImpl
import javax.swing.Timer

/**
 * Java Swing (`:desktop:swing`) implementation of [UiTimerNativeImpl] backed by [javax.swing.Timer]
 * on the AWT Event Dispatch Thread, while also supporting deterministic [advanceTimeBy] and [flushAll]
 * via [DefaultUiTimerNativeImpl] in unit tests.
 */
class SwingUiTimerImpl : DefaultUiTimerNativeImpl() {

    private val swingTimers = linkedMapOf<Int, Timer>()

    override fun setTimeout(delayMs: Int, callback: () -> Unit): Int {
        if (isDisposed) return -1
        var timerId = -1
        timerId = super.setTimeout(delayMs) {
            swingTimers.remove(timerId)?.stop()
            callback()
        }
        if (timerId > 0) {
            val normalizedDelay = delayMs.coerceAtLeast(0)
            val swingTimer = Timer(normalizedDelay) {
                swingTimers.remove(timerId)?.stop()
                if (isTimerActive(timerId)) {
                    super.clearTimer(timerId)
                    callback()
                }
            }.apply {
                isRepeats = false
                start()
            }
            swingTimers[timerId] = swingTimer
        }
        return timerId
    }

    override fun setInterval(intervalMs: Int, callback: () -> Unit): Int {
        if (isDisposed) return -1
        val timerId = super.setInterval(intervalMs, callback)
        if (timerId > 0) {
            val normalizedInterval = intervalMs.coerceAtLeast(1)
            val swingTimer = Timer(normalizedInterval) {
                if (isTimerActive(timerId)) {
                    callback()
                }
            }.apply {
                isRepeats = true
                start()
            }
            swingTimers[timerId] = swingTimer
        }
        return timerId
    }

    override fun clearTimer(timerId: Int) {
        swingTimers.remove(timerId)?.stop()
        super.clearTimer(timerId)
    }

    override fun clearAllTimers() {
        val snapshot = swingTimers.values.toList()
        swingTimers.clear()
        for (timer in snapshot) {
            timer.stop()
        }
        super.clearAllTimers()
    }
}
