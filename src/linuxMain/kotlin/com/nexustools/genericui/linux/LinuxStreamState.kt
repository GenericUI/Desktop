package com.nexustools.genericui.linux

import com.nexustools.genericui.io.ByteBuffer
import com.nexustools.genericui.io.ByteOrder

internal class LinuxStreamState(
    initialByteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN,
    initialHighWaterMark: Int = 64 * 1024
) {
    val lock = LinuxReentrantLock()

    private val pushbackQueue = ArrayDeque<ByteBuffer>()
    private val dataListeners = ArrayList<suspend (ByteBuffer) -> Unit>()
    private val endListeners = ArrayList<suspend () -> Unit>()
    private val writeListeners = ArrayList<(ByteBuffer) -> Unit>()
    private val drainListeners = ArrayList<suspend () -> Unit>()
    private val finishListeners = ArrayList<suspend () -> Unit>()
    private val errorListeners = ArrayList<(Throwable) -> Unit>()
    private val closeListeners = ArrayList<() -> Unit>()

    var byteOrder: ByteOrder = initialByteOrder
    var highWaterMark: Int = initialHighWaterMark
    var bytesRead: Long = 0L
    var bytesWritten: Long = 0L
    var isPaused: Boolean = false
    var isEnded: Boolean = false
    var isFinished: Boolean = false
    var isDisposed: Boolean = false
    var bufferedOutboundBytes: Int = 0
    var drainPending: Boolean = false

    fun hasPushback(): Boolean = lock.withLock { pushbackQueue.isNotEmpty() }

    fun unshift(buffer: ByteBuffer) {
        if (!buffer.hasRemaining) return
        lock.withLock {
            if (!isDisposed) {
                pushbackQueue.addFirst(buffer)
                if (isEnded) isEnded = false
            }
        }
    }

    fun pollPushback(maxBytes: Int): ByteBuffer? {
        if (maxBytes <= 0) return null
        return lock.withLock {
            if (isPaused || isDisposed || pushbackQueue.isEmpty()) return null
            val head = pushbackQueue.removeFirst()
            val take = minOf(maxBytes, head.remaining)
            val sliceBytes = head.readBytes(take)
            if (head.hasRemaining) {
                pushbackQueue.addFirst(head)
            }
            bytesRead += sliceBytes.size
            ByteBuffer.wrap(sliceBytes, byteOrder = byteOrder)
        }
    }

    fun recordRead(byteCount: Int) {
        if (byteCount <= 0) return
        lock.withLock { bytesRead += byteCount }
    }

    fun recordWrite(byteCount: Int): Boolean {
        return lock.withLock {
            bytesWritten += byteCount
            true
        }
    }

    fun pause() {
        lock.withLock { isPaused = true }
    }

    fun resume() {
        lock.withLock { isPaused = false }
    }

    fun addDataListener(listener: suspend (ByteBuffer) -> Unit) {
        lock.withLock { dataListeners.add(listener) }
    }

    fun addEndListener(listener: suspend () -> Unit) {
        lock.withLock { endListeners.add(listener) }
    }

    fun addWriteListener(listener: (ByteBuffer) -> Unit) {
        lock.withLock { writeListeners.add(listener) }
    }

    fun addDrainListener(listener: suspend () -> Unit) {
        lock.withLock { drainListeners.add(listener) }
    }

    fun addFinishListener(listener: suspend () -> Unit) {
        lock.withLock { finishListeners.add(listener) }
    }

    fun addErrorListener(listener: (Throwable) -> Unit) {
        lock.withLock { errorListeners.add(listener) }
    }

    fun addCloseListener(listener: () -> Unit) {
        lock.withLock { closeListeners.add(listener) }
    }

    suspend fun emitData(chunk: ByteBuffer) {
        if (!chunk.hasRemaining) return
        val snapshot = lock.withLock { dataListeners.toList() }
        for (listener in snapshot) {
            listener(chunk.duplicate())
        }
    }

    suspend fun emitEnd() {
        val snapshot = lock.withLock {
            if (isEnded) return
            isEnded = true
            endListeners.toList()
        }
        for (listener in snapshot) {
            listener()
        }
    }

    fun emitWrite(chunk: ByteBuffer) {
        val snapshot = lock.withLock { writeListeners.toList() }
        for (listener in snapshot) {
            listener(chunk.duplicate())
        }
    }

    suspend fun emitDrain() {
        val snapshot = lock.withLock { drainListeners.toList() }
        for (listener in snapshot) {
            listener()
        }
    }

    suspend fun emitFinish() {
        val snapshot = lock.withLock {
            if (isFinished) return
            isFinished = true
            finishListeners.toList()
        }
        for (listener in snapshot) {
            listener()
        }
    }

    fun emitError(error: Throwable) {
        val snapshot = lock.withLock { errorListeners.toList() }
        for (listener in snapshot) {
            listener(error)
        }
    }

    fun markDisposedAndNotifyClose() {
        val snapshot = lock.withLock {
            if (isDisposed) return
            isDisposed = true
            isEnded = true
            isFinished = true
            closeListeners.toList()
        }
        for (listener in snapshot) {
            listener()
        }
    }

    fun readableClosedOrPaused(): Boolean = lock.withLock { isDisposed || isEnded || isPaused }
}
