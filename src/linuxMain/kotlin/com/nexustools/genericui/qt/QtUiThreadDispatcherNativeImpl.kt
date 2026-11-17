@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.nexustools.genericui.qt

import com.nexustools.genericui.linux.LinuxGlibGioBindings
import com.nexustools.genericui.linux.LinuxReentrantLock
import com.nexustools.genericui.threading.UiThreadDispatcherNativeImpl
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.posix.pthread_equal
import platform.posix.pthread_self
import platform.posix.pthread_t
import platform.posix.usleep
import kotlin.coroutines.CoroutineContext

/**
 * Qt 6 dual-thread dispatcher separating the Qt UI thread from the single serial GenericUI Logic
 * thread, waking the Qt / GLib main loop on UI dispatches.
 */
class QtUiThreadDispatcherNativeImpl(
    private val isSplitDispatchActive: () -> Boolean = { true },
    private val onUiDrainComplete: (() -> Unit)? = null
) : UiThreadDispatcherNativeImpl {
    private val lock = LinuxReentrantLock()
    private val uiPendingTasks = ArrayDeque<() -> Unit>()
    private val serialLogicDispatcher = Dispatchers.IO.limitedParallelism(1)
    private var inFlightLogicCount = 0
    private var drainedLogicCounter = 0
    private var uiMainThreadId: pthread_t = pthread_self()
    private var logicThreadId: pthread_t? = null
    private var uiIdleScheduled = false
    private val selfRef = StableRef.create(this)

    init {
        val previousHook = QtNativeWindowHost.onLoopTickHook
        QtNativeWindowHost.onLoopTickHook = {
            previousHook?.invoke()
            if (isOnUiThread()) {
                drainUiQueueOnMain()
            }
        }
    }

    override val isSplitThreadingEnabled: Boolean
        get() = isSplitDispatchActive()

    override val uiDispatcher: CoroutineDispatcher = object : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = !isOnUiThread()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatchToUi { block.run() }
        }
    }

    override val logicDispatcher: CoroutineDispatcher = object : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = !isOnLogicThread()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatchToLogic { block.run() }
        }
    }

    override val logicScope: CoroutineScope = CoroutineScope(SupervisorJob() + logicDispatcher)

    internal fun bindCurrentThreadAsUiThread() {
        lock.withLock {
            uiMainThreadId = pthread_self()
        }
    }

    override fun isOnUiThread(): Boolean {
        if (!isSplitThreadingEnabled) return true
        val uiTid = lock.withLock { uiMainThreadId }
        return pthread_equal(pthread_self(), uiTid) != 0
    }

    override fun isOnLogicThread(): Boolean {
        if (!isSplitThreadingEnabled) return true
        val logicTid = lock.withLock { logicThreadId } ?: return false
        return pthread_equal(pthread_self(), logicTid) != 0
    }

    override fun dispatchToUi(block: () -> Unit) {
        if (!isSplitThreadingEnabled) {
            block()
            onUiDrainComplete?.invoke()
            return
        }
        val needIdle = lock.withLock {
            uiPendingTasks.addLast(block)
            if (QtApplicationNativeImpl.interactiveQtGuiEnabled && !uiIdleScheduled) {
                uiIdleScheduled = true
                true
            } else {
                false
            }
        }
        if (needIdle && LinuxGlibGioBindings.ensureLoaded()) {
            val cb = staticCFunction { userData: COpaquePointer? ->
                if (userData != null) {
                    val dispatcher = userData.asStableRef<QtUiThreadDispatcherNativeImpl>().get()
                    dispatcher.drainUiQueueFromIdle()
                }
                LinuxGlibGioBindings.G_SOURCE_REMOVE
            }
            val sourceId = LinuxGlibGioBindings.idleAdd(cb, selfRef.asCPointer())
            if (sourceId != 0u) {
                LinuxGlibGioBindings.mainContextWakeup(LinuxGlibGioBindings.mainContextDefault())
            } else {
                lock.withLock { uiIdleScheduled = false }
            }
        }
    }

    override fun <T> runOnUiBlocking(block: () -> T): T {
        if (!isSplitThreadingEnabled || isOnUiThread()) {
            return block()
        }
        var resultBox: Result<T>? = null
        val doneLock = LinuxReentrantLock()
        dispatchToUi {
            val res = runCatching(block)
            doneLock.withLock { resultBox = res }
        }
        while (true) {
            val done = doneLock.withLock { resultBox }
            if (done != null) return done.getOrThrow()
            usleep(1000u)
        }
    }

    override fun dispatchToLogic(block: () -> Unit) {
        if (!isSplitThreadingEnabled) {
            block()
            return
        }
        lock.withLock { inFlightLogicCount++ }
        serialLogicDispatcher.dispatch(logicScope.coroutineContext) {
            lock.withLock { logicThreadId = pthread_self() }
            try {
                block()
            } finally {
                lock.withLock {
                    if (inFlightLogicCount > 0) inFlightLogicCount--
                    drainedLogicCounter++
                }
            }
        }
    }

    override fun dispatchToLogicAsync(block: suspend CoroutineScope.() -> Unit) {
        lock.withLock { inFlightLogicCount++ }
        logicScope.launch {
            lock.withLock { logicThreadId = pthread_self() }
            try {
                block()
            } finally {
                lock.withLock {
                    if (inFlightLogicCount > 0) inFlightLogicCount--
                    drainedLogicCounter++
                }
            }
        }
    }

    override fun drainUiQueue(): Int {
        if (!isOnUiThread() && isSplitThreadingEnabled) {
            return runOnUiBlocking { drainUiQueueOnMain() }
        }
        return drainUiQueueOnMain()
    }

    private fun drainUiQueueOnMain(): Int {
        var executed = 0
        while (true) {
            val next = lock.withLock { uiPendingTasks.removeFirstOrNull() } ?: break
            next.invoke()
            executed++
        }
        if (executed > 0) {
            onUiDrainComplete?.invoke()
        }
        return executed
    }

    override fun drainLogicQueue(): Int {
        if (isOnLogicThread()) {
            return 0
        }
        val startCount = lock.withLock { drainedLogicCounter }
        val interactive = QtApplicationNativeImpl.interactiveQtGuiEnabled
        while (true) {
            if (isOnUiThread()) {
                drainUiQueueOnMain()
                if (interactive) {
                    Qt6DynamicBindings.processEvents(4)
                }
            }
            val remaining = lock.withLock { inFlightLogicCount }
            if (remaining <= 0) break
            usleep(1000u)
        }
        val endCount = lock.withLock { drainedLogicCounter }
        return (endCount - startCount).coerceAtLeast(0)
    }

    override fun flushAllQueues() {
        val interactive = QtApplicationNativeImpl.interactiveQtGuiEnabled
        while (true) {
            val drainedUi = if (isOnUiThread()) drainUiQueueOnMain() else 0
            if (interactive && isOnUiThread()) {
                Qt6DynamicBindings.processEvents(4)
            }
            val pending = lock.withLock { uiPendingTasks.size + inFlightLogicCount }
            if (pending == 0) {
                if (drainedUi == 0) break
                continue
            }
            usleep(1000u)
        }
    }

    private fun drainUiQueueFromIdle() {
        lock.withLock { uiIdleScheduled = false }
        drainUiQueueOnMain()
    }
}
