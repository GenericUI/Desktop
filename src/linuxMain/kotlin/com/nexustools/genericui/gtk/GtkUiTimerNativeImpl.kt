@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk

import com.nexustools.genericui.linux.LinuxGlibGioBindings
import com.nexustools.genericui.linux.LinuxReentrantLock
import com.nexustools.genericui.nativeimpl.system.DefaultUiTimerNativeImpl
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction

class GtkUiTimerNativeImpl : DefaultUiTimerNativeImpl() {
    private val lock = LinuxReentrantLock()
    private val glibSourceIds = LinkedHashMap<Int, UInt>()

    override fun setTimeout(delayMs: Int, callback: () -> Unit): Int {
        if (!GtkApplicationNativeImpl.interactiveGtkGuiEnabled || !LinuxGlibGioBindings.ensureLoaded()) {
            return super.setTimeout(delayMs, callback)
        }
        val timerId = super.setTimeout(delayMs, callback)
        attachGlibSource(timerId, delayMs.coerceAtLeast(1), repeat = false, callback = callback)
        return timerId
    }

    override fun setInterval(intervalMs: Int, callback: () -> Unit): Int {
        if (!GtkApplicationNativeImpl.interactiveGtkGuiEnabled || !LinuxGlibGioBindings.ensureLoaded()) {
            return super.setInterval(intervalMs, callback)
        }
        val timerId = super.setInterval(intervalMs, callback)
        attachGlibSource(timerId, intervalMs.coerceAtLeast(1), repeat = true, callback = callback)
        return timerId
    }

    override fun clearTimer(timerId: Int) {
        super.clearTimer(timerId)
        cancelGlibSource(timerId)
    }

    override fun clearAllTimers() {
        super.clearAllTimers()
        val ids = lock.withLock { glibSourceIds.keys.toList() }
        for (id in ids) {
            cancelGlibSource(id)
        }
    }

    private fun attachGlibSource(timerId: Int, intervalMs: Int, repeat: Boolean, callback: () -> Unit) {
        val binding = GtkTimerBinding(timerId, repeat, callback, this)
        val ref = StableRef.create(binding)
        val cb = staticCFunction { userData: COpaquePointer? ->
            if (userData == null) return@staticCFunction LinuxGlibGioBindings.G_SOURCE_REMOVE
            val b = userData.asStableRef<GtkTimerBinding>().get()
            if (b.owner.isDisposed || !b.owner.isTimerTracked(b.timerId)) {
                return@staticCFunction LinuxGlibGioBindings.G_SOURCE_REMOVE
            }
            if (!b.repeat) {
                b.owner.markOneShotFired(b.timerId)
            }
            b.callback.invoke()
            if (b.repeat && b.owner.isTimerTracked(b.timerId)) {
                LinuxGlibGioBindings.G_SOURCE_CONTINUE
            } else {
                LinuxGlibGioBindings.G_SOURCE_REMOVE
            }
        }
        val destroy = staticCFunction { userData: COpaquePointer? ->
            userData?.asStableRef<GtkTimerBinding>()?.dispose()
            Unit
        }
        val sourceId = LinuxGlibGioBindings.timeoutAddFull(intervalMs.toUInt(), cb, ref.asCPointer(), destroy)
        if (sourceId != 0u) {
            lock.withLock {
                glibSourceIds[timerId] = sourceId
            }
        } else {
            ref.dispose()
        }
    }

    private fun isTimerTracked(timerId: Int): Boolean =
        lock.withLock { glibSourceIds.containsKey(timerId) }

    private fun markOneShotFired(timerId: Int) {
        super.clearTimer(timerId)
        lock.withLock { glibSourceIds.remove(timerId) }
    }

    private fun cancelGlibSource(timerId: Int) {
        val srcId = lock.withLock { glibSourceIds.remove(timerId) }
        if (srcId != null && srcId != 0u) {
            LinuxGlibGioBindings.sourceRemove(srcId)
        }
    }

    override fun dispose() {
        clearAllTimers()
        super.dispose()
    }

    private class GtkTimerBinding(
        val timerId: Int,
        val repeat: Boolean,
        val callback: () -> Unit,
        val owner: GtkUiTimerNativeImpl
    )
}
