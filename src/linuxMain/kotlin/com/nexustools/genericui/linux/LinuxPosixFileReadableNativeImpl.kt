@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.linux

import com.nexustools.genericui.io.ByteBuffer
import com.nexustools.genericui.io.ByteOrder
import com.nexustools.genericui.io.Readable
import com.nexustools.genericui.nativeimpl.io.FileReadableNativeImpl
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.posix.EINTR
import platform.posix.O_RDONLY
import platform.posix.close as posixClose
import platform.posix.errno
import platform.posix.open as posixOpen
import platform.posix.pread
import platform.posix.strerror

class LinuxPosixFileReadableNativeImpl(
    override val filePath: String,
    initialByteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN
) : FileReadableNativeImpl {
    private val state = LinuxStreamState(initialByteOrder = initialByteOrder)

    private var fd: Int = -1
    private var openAttempted = false
    private var readOffset = 0L

    override val isReadable: Boolean
        get() = state.lock.withLock { !state.isDisposed && !state.isEnded }

    override val isPaused: Boolean
        get() = state.lock.withLock { state.isPaused }

    override val isEnded: Boolean
        get() = state.lock.withLock { state.isEnded }

    override val bytesRead: Long
        get() = state.lock.withLock { state.bytesRead }

    override var byteOrder: ByteOrder
        get() = state.lock.withLock { state.byteOrder }
        set(value) {
            state.lock.withLock { state.byteOrder = value }
        }

    override val isDisposed: Boolean
        get() = state.lock.withLock { state.isDisposed }

    override suspend fun read(maxBytes: Int): ByteBuffer? {
        if (maxBytes <= 0 || isPaused || isDisposed || isEnded) return null
        state.pollPushback(maxBytes)?.let { return it }

        val boundedMax = maxBytes.coerceIn(1, 1024 * 1024)
        val chunkResult = withContext(Dispatchers.IO) {
            readNextChunkSync(boundedMax)
        }
        return chunkResult
    }

    private suspend fun readNextChunkSync(maxBytes: Int): ByteBuffer? {
        val activeFd = ensureFdOpened()
        if (activeFd < 0) {
            state.emitEnd()
            return null
        }

        val buf = ByteArray(maxBytes)
        val offsetSnapshot = state.lock.withLock { readOffset }
        var totalRead = -1
        while (true) {
            val n = buf.usePinned { pinned ->
                pread(activeFd, pinned.addressOf(0), maxBytes.convert(), offsetSnapshot.convert()).toInt()
            }
            if (n >= 0) {
                totalRead = n
                break
            }
            val err = errno
            if (err == EINTR) continue
            state.emitError(IllegalStateException("File read error ($filePath): ${strerror(err)?.toKString() ?: err}"))
            closeFdOnly()
            state.emitEnd()
            return null
        }

        if (totalRead == 0) {
            closeFdOnly()
            state.emitEnd()
            return null
        }

        val slice = if (totalRead == buf.size) buf else buf.copyOf(totalRead)
        state.lock.withLock {
            readOffset += totalRead
        }
        state.recordRead(totalRead)
        val wrapped = ByteBuffer.wrap(slice, byteOrder = byteOrder)
        state.emitData(wrapped)
        return wrapped
    }

    override fun onData(listener: suspend (ByteBuffer) -> Unit): Readable {
        state.addDataListener(listener)
        return this
    }

    override fun onEnd(listener: suspend () -> Unit): Readable {
        state.addEndListener(listener)
        return this
    }

    override fun onError(listener: (Throwable) -> Unit): Readable {
        state.addErrorListener(listener)
        return this
    }

    override fun onClose(listener: () -> Unit): Readable {
        state.addCloseListener(listener)
        return this
    }

    override fun pause(): Readable {
        state.pause()
        return this
    }

    override fun resume(): Readable {
        state.resume()
        return this
    }

    override fun unshift(buffer: ByteBuffer) {
        state.unshift(buffer)
    }

    override suspend fun close() {
        closeFdOnly()
        state.emitEnd()
        state.markDisposedAndNotifyClose()
    }

    override fun dispose() {
        closeFdOnly()
        state.markDisposedAndNotifyClose()
    }

    private fun ensureFdOpened(): Int {
        return state.lock.withLock {
            if (state.isDisposed) return -1
            if (openAttempted) return fd
            openAttempted = true
            val opened = posixOpen(filePath, O_RDONLY)
            if (opened < 0) {
                val err = errno
                state.emitError(IllegalStateException("Failed to open file for reading ($filePath): ${strerror(err)?.toKString() ?: err}"))
                return -1
            }
            fd = opened
            opened
        }
    }

    private fun closeFdOnly() {
        val toClose = state.lock.withLock {
            val f = fd
            fd = -1
            f
        }
        if (toClose >= 0) {
            posixClose(toClose)
        }
    }
}
