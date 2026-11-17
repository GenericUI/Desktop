package com.nexustools.genericui.linux

import com.nexustools.genericui.io.ByteBuffer
import com.nexustools.genericui.io.ByteOrder
import com.nexustools.genericui.io.Readable
import com.nexustools.genericui.nativeimpl.io.DatabaseReadableNativeImpl

class LinuxDatabaseReadableNativeImpl(
    override val table: String,
    override val key: String,
    private val readSlice: suspend (offset: Long, maxBytes: Int) -> ByteArray?,
    initialByteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN
) : DatabaseReadableNativeImpl {
    private val state = LinuxStreamState(initialByteOrder = initialByteOrder)
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

        val offset = state.lock.withLock { readOffset }
        val chunkBytes = readSlice(offset, maxBytes)
        if (chunkBytes == null || chunkBytes.isEmpty()) {
            state.emitEnd()
            return null
        }

        state.lock.withLock {
            readOffset += chunkBytes.size
        }
        state.recordRead(chunkBytes.size)
        val chunk = ByteBuffer.wrap(chunkBytes, byteOrder = byteOrder)
        state.emitData(chunk)
        return chunk
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
        state.emitEnd()
        state.markDisposedAndNotifyClose()
    }

    override fun dispose() {
        state.markDisposedAndNotifyClose()
    }
}
