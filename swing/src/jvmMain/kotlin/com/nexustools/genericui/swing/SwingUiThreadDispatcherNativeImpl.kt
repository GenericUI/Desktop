package com.nexustools.genericui.swing

import com.nexustools.genericui.threading.DefaultUiThreadDispatcherNativeImpl
import java.awt.GraphicsEnvironment
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/**
 * Java Swing (`:desktop:swing`) implementation of [com.nexustools.genericui.threading.UiThreadDispatcherNativeImpl].
 *
 * Separates UI rendering/widget mutation onto the AWT/Swing UI thread (`SwingUtilities.isEventDispatchThread()`
 * / `SwingUtilities.invokeLater`) and executes event handlers and application logic on a dedicated
 * `"GenericUI-Swing-Logic"` worker thread.
 */
class SwingUiThreadDispatcherNativeImpl : DefaultUiThreadDispatcherNativeImpl(
    isSplitThreadingEnabled = true
) {

    @Volatile
    private var logicWorkerThread: Thread? = null

    @Volatile
    private var pumpingUiThread: Thread? = null

    private val blockingUiRequests = ConcurrentLinkedQueue<() -> Unit>()

    private val logicExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, LOGIC_THREAD_NAME).apply {
            isDaemon = true
            logicWorkerThread = this
        }
    }

    override fun isOnLogicThread(): Boolean {
        if (uiBlockingOverrideDepth > 0) return false
        return Thread.currentThread() === logicWorkerThread || super.isOnLogicThread()
    }

    override fun isOnUiThread(): Boolean {
        if (uiBlockingOverrideDepth > 0) return true
        if (Thread.currentThread() === logicWorkerThread || super.isOnLogicThread()) return false
        return SwingUtilities.isEventDispatchThread() || super.isOnUiThread()
    }

    override fun onUiTaskQueuedFromLogic() {
        if (!GraphicsEnvironment.isHeadless() && pumpingUiThread == null && !SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater {
                drainUiQueue()
            }
        }
    }

    override fun <T> runOnUiBlocking(block: () -> T): T {
        if (Thread.currentThread() === logicWorkerThread) {
            val activePumper = pumpingUiThread
            if (activePumper != null && activePumper !== Thread.currentThread()) {
                var result: Any? = null
                var failure: Throwable? = null
                val latch = CountDownLatch(1)
                blockingUiRequests.add {
                    try {
                        result = super.runOnUiBlocking(block)
                    } catch (t: Throwable) {
                        failure = t
                    } finally {
                        latch.countDown()
                    }
                }
                latch.await()
                failure?.let { throw it }
                @Suppress("UNCHECKED_CAST")
                return result as T
            }
            if (!GraphicsEnvironment.isHeadless() && !SwingUtilities.isEventDispatchThread()) {
                var result: Any? = null
                var failure: Throwable? = null
                SwingUtilities.invokeAndWait {
                    try {
                        result = super.runOnUiBlocking(block)
                    } catch (t: Throwable) {
                        failure = t
                    }
                }
                failure?.let { throw it }
                @Suppress("UNCHECKED_CAST")
                return result as T
            }
        }
        return super.runOnUiBlocking(block)
    }

    override fun dispatchToLogic(block: () -> Unit) {
        if (Thread.currentThread() === logicWorkerThread) {
            super.dispatchToLogic(block)
            return
        }
        enqueueLogicTask(block)
        val latch = CountDownLatch(1)
        var logicFailure: Throwable? = null
        val previousPumper = pumpingUiThread
        pumpingUiThread = Thread.currentThread()
        try {
            logicExecutor.execute {
                try {
                    drainLogicQueue()
                } catch (t: Throwable) {
                    logicFailure = t
                } finally {
                    latch.countDown()
                }
            }
            while (!latch.await(1, TimeUnit.MILLISECONDS)) {
                while (true) {
                    val req = blockingUiRequests.poll() ?: break
                    req()
                }
            }
            while (true) {
                val req = blockingUiRequests.poll() ?: break
                req()
            }
        } finally {
            pumpingUiThread = previousPumper
        }
        if (logicExecutionDepth == 0 && uiBlockingOverrideDepth == 0) {
            drainUiQueue()
        }
        logicFailure?.let { throw it }
    }

    companion object {
        const val LOGIC_THREAD_NAME: String = "GenericUI-Swing-Logic"
    }
}
