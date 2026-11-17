@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.io.ByteBuffer
import com.nexustools.genericui.io.ByteOrder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.windows.CreateDirectoryW
import kotlin.concurrent.AtomicInt
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext

internal class WindowsSpinLock {
    private val state = AtomicInt(0)

    inline fun <T> withLock(block: () -> T): T {
        while (!state.compareAndSet(0, 1)) {
            // Brief atomic contention wait
        }
        return try {
            block()
        } finally {
            state.value = 0
        }
    }
}

internal class WindowsStreamState(
    initialByteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN,
    initialHighWaterMark: Int = 64 * 1024
) {
    val lock = WindowsSpinLock()

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

    fun recordWrite(byteCount: Int) {
        if (byteCount <= 0) return
        lock.withLock { bytesWritten += byteCount }
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

    suspend fun emitData(buffer: ByteBuffer) {
        val listeners = lock.withLock { dataListeners.toList() }
        for (listener in listeners) {
            listener(buffer)
        }
    }

    suspend fun emitEnd() {
        val listeners = lock.withLock {
            if (isEnded) return
            isEnded = true
            endListeners.toList()
        }
        for (listener in listeners) {
            listener()
        }
    }

    fun emitWrite(buffer: ByteBuffer) {
        val listeners = lock.withLock { writeListeners.toList() }
        for (listener in listeners) {
            listener(buffer)
        }
    }

    suspend fun emitDrain() {
        val listeners = lock.withLock { drainListeners.toList() }
        for (listener in listeners) {
            listener()
        }
    }

    suspend fun emitFinish() {
        val listeners = lock.withLock {
            if (isFinished) return
            isFinished = true
            finishListeners.toList()
        }
        for (listener in listeners) {
            listener()
        }
    }

    fun emitError(error: Throwable) {
        val listeners = lock.withLock { errorListeners.toList() }
        for (listener in listeners) {
            listener(error)
        }
    }

    fun markDisposedAndNotifyClose(): Boolean {
        val listeners = lock.withLock {
            if (isDisposed) return false
            isDisposed = true
            pushbackQueue.clear()
            closeListeners.toList()
        }
        for (listener in listeners) {
            listener()
        }
        return true
    }
}

internal object WindowsIoHelper {

    fun normalizeWin32Path(rawPath: String): String {
        val trimmed = rawPath.trim()
        if (trimmed.isEmpty()) return ""
        val isUnc = trimmed.startsWith("\\\\") || trimmed.startsWith("//")
        val normalized = trimmed.replace('/', '\\')
        val collapsed = buildString(normalized.length) {
            var prevSlash = false
            for (i in normalized.indices) {
                val c = normalized[i]
                if (c == '\\') {
                    if (i <= 1 && isUnc) {
                        append(c)
                    } else if (!prevSlash) {
                        append(c)
                    }
                    prevSlash = true
                } else {
                    append(c)
                    prevSlash = false
                }
            }
        }
        if (collapsed.length == 3 && collapsed[1] == ':' && collapsed[2] == '\\') {
            return collapsed
        }
        return if (collapsed.length > 1) collapsed.trimEnd('\\') else collapsed
    }

    fun resolveParentDirectory(rawPath: String): String? {
        val norm = normalizeWin32Path(rawPath)
        val sepIdx = norm.lastIndexOf('\\')
        if (sepIdx <= 0) return null
        val parent = norm.substring(0, sepIdx)
        if (parent.length == 2 && parent[1] == ':') return null
        return parent
    }

    fun ensureParentDirectories(filePath: String): Boolean {
        val parent = resolveParentDirectory(filePath) ?: return true
        return ensureDirectoryExists(parent)
    }

    fun ensureDirectoryExists(directoryPath: String): Boolean {
        val norm = normalizeWin32Path(directoryPath)
        if (norm.isEmpty()) return false
        if (norm.length == 2 && norm[1] == ':') return true
        val parts = norm.split('\\')
        val acc = StringBuilder()
        var startIdx = 0
        if (norm.startsWith("\\\\")) {
            val nonEmpty = parts.filter { it.isNotEmpty() }
            if (nonEmpty.size < 2) return true
            acc.append("\\\\").append(nonEmpty[0]).append('\\').append(nonEmpty[1])
            for (i in 2 until nonEmpty.size) {
                acc.append('\\').append(nonEmpty[i])
                CreateDirectoryW(acc.toString(), null)
            }
            return true
        }
        if (parts.isNotEmpty() && parts[0].length == 2 && parts[0][1] == ':') {
            acc.append(parts[0])
            startIdx = 1
        }
        for (i in startIdx until parts.size) {
            val part = parts[i]
            if (part.isEmpty()) continue
            if (acc.isNotEmpty() && !acc.endsWith("\\")) {
                acc.append('\\')
            }
            acc.append(part)
            CreateDirectoryW(acc.toString(), null)
        }
        return true
    }

    fun windowsFileTimeToEpochMs(highDateTime: UInt, lowDateTime: UInt): Long {
        val fileTimeTicks = (highDateTime.toLong() shl 32) or (lowDateTime.toLong() and 0xFFFF_FFFFL)
        if (fileTimeTicks <= 0L) return 0L
        return ((fileTimeTicks - 116_444_736_000_000_000L) / 10_000L).coerceAtLeast(0L)
    }

    suspend inline fun <T> runIo(
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        crossinline block: () -> T
    ): T {
        return if (coroutineContext[ContinuationInterceptor] == null) {
            block()
        } else {
            withContext(dispatcher) { block() }
        }
    }
}
