package com.nexustools.genericui.swing

import com.nexustools.genericui.nativeimpl.system.NetworkMonitorNativeImpl
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Java `java.net.NetworkInterface` implementation of [NetworkMonitorNativeImpl] with background
 * [ScheduledExecutorService] polling that dispatches [setOnConnectivityChanged] listeners when the
 * active non-loopback interface connectivity state changes.
 */
class SwingNetworkMonitorImpl(
    val pollIntervalMs: Long = 500L,
    private val onlineProbe: () -> Boolean = ::probeNetworkInterfaces
) : NetworkMonitorNativeImpl {

    override var isDisposed: Boolean = false
        private set

    @Volatile
    private var connectivityListener: ((Boolean) -> Unit)? = null

    @Volatile
    private var lastKnownOnline: Boolean? = null

    private var scheduler: ScheduledExecutorService? = null
    private var pollFuture: ScheduledFuture<*>? = null
    private val lock = Any()

    override fun isOnline(): Boolean {
        val current = onlineProbe()
        lastKnownOnline = current
        return current
    }

    override fun setOnConnectivityChanged(listener: ((Boolean) -> Unit)?) {
        synchronized(lock) {
            connectivityListener = listener
            if (listener != null && !isDisposed) {
                if (lastKnownOnline == null) {
                    lastKnownOnline = runCatching { onlineProbe() }.getOrDefault(false)
                }
                startPollingLocked()
            } else {
                stopPollingLocked()
            }
        }
    }

    /**
     * Executes a single connectivity poll cycle and notifies any listener if status changed.
     */
    fun pollConnectivityOnce(): Boolean {
        if (isDisposed) return false
        val current = runCatching { onlineProbe() }.getOrDefault(false)
        val listenerToNotify: ((Boolean) -> Unit)?
        synchronized(lock) {
            val previous = lastKnownOnline
            lastKnownOnline = current
            listenerToNotify = if (previous != null && previous != current) {
                connectivityListener
            } else {
                null
            }
        }
        listenerToNotify?.invoke(current)
        return current
    }

    private fun startPollingLocked() {
        if (pollFuture != null) return
        val interval = pollIntervalMs.coerceAtLeast(10L)
        val executor = scheduler ?: Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "GenericUI-Swing-NetworkMonitor").apply { isDaemon = true }
        }.also { scheduler = it }
        pollFuture = executor.scheduleAtFixedRate(
            { pollConnectivityOnce() },
            interval,
            interval,
            TimeUnit.MILLISECONDS
        )
    }

    private fun stopPollingLocked() {
        pollFuture?.cancel(false)
        pollFuture = null
        scheduler?.shutdownNow()
        scheduler = null
    }

    override fun dispose() {
        synchronized(lock) {
            isDisposed = true
            connectivityListener = null
            stopPollingLocked()
        }
    }

    companion object {
        fun probeNetworkInterfaces(): Boolean = runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            interfaces != null && interfaces.asSequence().any { it.isUp && !it.isLoopback }
        }.getOrDefault(false)
    }
}
