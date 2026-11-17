package com.nexustools.genericui.linux

import com.nexustools.genericui.io.ByteBuffer
import com.nexustools.genericui.io.ByteOrder
import com.nexustools.genericui.io.Writable
import com.nexustools.genericui.io.WritableByteBuffer
import com.nexustools.genericui.nativeimpl.io.DatabaseWritableNativeImpl

class LinuxDatabaseWritableNativeImpl(
    override val table: String,
    override val key: String,
    override val appendMode: Boolean = false,
    private val commitBytes: suspend (bytes: ByteArray, append: Boolean) -> Unit,
    initialHighWaterMark: Int = 64 * 1024,
    initialByteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN
) : DatabaseWritableNativeImpl {
    private val state = LinuxStreamState(
        initialByteOrder = initialByteOrder,
        initialHighWaterMark = initialHighWaterMark
    )
    private val accumulator = WritableByteBuffer(byteOrder = initialByteOrder)
    private var flushedOnce = false

    override val isWritable: Boolean
        get() = state.lock.withLock { !state.isDisposed && !state.isFinished }

    override val isFinished: Boolean
        get() = state.lock.withLock { state.isFinished }

    override val bytesWritten: Long
        get() = state.lock.withLock { state.bytesWritten }

    override var highWaterMark: Int
        get() = state.lock.withLock { state.highWaterMark }
        set(value) {
            state.lock.withLock { state.highWaterMark = value }
        }

    override var byteOrder: ByteOrder
        get() = state.lock.withLock { state.byteOrder }
        set(value) {
            state.lock.withLock {
                state.byteOrder = value
                accumulator.byteOrder = value
            }
        }

    override val isDisposed: Boolean
        get() = state.lock.withLock { state.isDisposed }

    override fun write(buffer: ByteBuffer): Boolean {
        val slice = buffer.remainingByteArray()
        return write(slice, 0, slice.size)
    }

    override fun write(chunk: ByteArray, offset: Int, length: Int): Boolean {
        require(offset >= 0 && length >= 0 && offset + length <= chunk.size) {
            "Invalid slice offset=$offset length=$length size=${chunk.size}"
        }
        val slice = if (offset == 0 && length == chunk.size) chunk.copyOf() else chunk.copyOfRange(offset, offset + length)
        val ok = state.lock.withLock {
            if (state.isDisposed || state.isFinished) return false
            accumulator.writeBytes(slice, 0, slice.size)
        }
        if (!ok) return false
        state.recordWrite(slice.size)
        state.emitWrite(ByteBuffer.wrap(slice, byteOrder = byteOrder))
        return true
    }

    override fun onWrite(listener: (ByteBuffer) -> Unit): Writable {
        state.addWriteListener(listener)
        return this
    }

    override fun onDrain(listener: suspend () -> Unit): Writable {
        state.addDrainListener(listener)
        return this
    }

    override fun onFinish(listener: suspend () -> Unit): Writable {
        state.addFinishListener(listener)
        return this
    }

    override fun onError(listener: (Throwable) -> Unit): Writable {
        state.addErrorListener(listener)
        return this
    }

    override fun onClose(listener: () -> Unit): Writable {
        state.addCloseListener(listener)
        return this
    }

    override suspend fun flush() {
        val (bytes, appendForCommit) = state.lock.withLock {
            val data = accumulator.toByteArray()
            val shouldAppend = appendMode || flushedOnce
            accumulator.clear()
            flushedOnce = true
            data to shouldAppend
        }
        if (bytes.isNotEmpty() || !appendForCommit) {
            try {
                commitBytes(bytes, appendForCommit)
            } catch (t: Throwable) {
                state.emitError(t)
                throw t
            }
        }
        state.emitDrain()
    }

    override suspend fun end(finalBuffer: ByteBuffer?) {
        if (finalBuffer != null && finalBuffer.hasRemaining) {
            write(finalBuffer)
        }
        flush()
        state.emitFinish()
    }

    override suspend fun close() {
        flush()
        state.emitFinish()
        state.markDisposedAndNotifyClose()
    }

    override fun dispose() {
        state.markDisposedAndNotifyClose()
    }

    override fun toByteArray(): ByteArray = state.lock.withLock { accumulator.toByteArray() }

    override fun toByteBuffer(): ByteBuffer = ByteBuffer.wrap(toByteArray(), byteOrder = byteOrder)
}
