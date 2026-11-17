@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.linux

import com.nexustools.genericui.io.ByteBuffer
import com.nexustools.genericui.io.ByteOrder
import com.nexustools.genericui.io.Writable
import com.nexustools.genericui.nativeimpl.io.FileWritableNativeImpl
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.posix.EINTR
import platform.posix.O_APPEND
import platform.posix.O_CREAT
import platform.posix.O_RDONLY
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.close as posixClose
import platform.posix.errno
import platform.posix.fstat
import platform.posix.fsync
import platform.posix.open as posixOpen
import platform.posix.read as posixRead
import platform.posix.stat
import platform.posix.strerror
import platform.posix.write as posixWrite

class LinuxPosixFileWritableNativeImpl(
    override val filePath: String,
    override val appendMode: Boolean = false,
    initialHighWaterMark: Int = 64 * 1024,
    initialByteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN
) : FileWritableNativeImpl {
    private val state = LinuxStreamState(
        initialByteOrder = initialByteOrder,
        initialHighWaterMark = initialHighWaterMark
    )

    private var fd: Int = -1
    private var openAttempted = false
    private var openFailed = false

    override val isWritable: Boolean
        get() = state.lock.withLock { !state.isDisposed && !state.isFinished && !openFailed }

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
            state.lock.withLock { state.byteOrder = value }
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
        if (!isWritable) return false
        val slice = if (offset == 0 && length == chunk.size) chunk.copyOf() else chunk.copyOfRange(offset, offset + length)
        val activeFd = ensureFdOpened()
        if (activeFd < 0) return false
        if (slice.isEmpty()) return true

        var writtenOffset = 0
        while (writtenOffset < slice.size) {
            val n = slice.usePinned { pinned ->
                posixWrite(activeFd, pinned.addressOf(writtenOffset), (slice.size - writtenOffset).convert()).toInt()
            }
            if (n > 0) {
                writtenOffset += n
            } else {
                val err = errno
                if (err == EINTR) continue
                state.emitError(IllegalStateException("File write error ($filePath): ${strerror(err)?.toKString() ?: err}"))
                return false
            }
        }

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
        val activeFd = ensureFdOpened()
        if (activeFd < 0) return
        val errMsg = withContext(Dispatchers.IO) {
            if (fsync(activeFd) == 0) null else (strerror(errno)?.toKString() ?: "fsync failed")
        }
        if (errMsg != null) {
            state.emitError(IllegalStateException("File flush error ($filePath): $errMsg"))
        } else {
            state.emitDrain()
        }
    }

    override suspend fun end(finalBuffer: ByteBuffer?) {
        if (finalBuffer != null && finalBuffer.hasRemaining) {
            write(finalBuffer)
        }
        ensureFdOpened()
        flush()
        closeFdOnly()
        state.emitFinish()
    }

    override suspend fun close() {
        flush()
        closeFdOnly()
        state.emitFinish()
        state.markDisposedAndNotifyClose()
    }

    override fun dispose() {
        closeFdOnly()
        state.markDisposedAndNotifyClose()
    }

    override fun toByteArray(): ByteArray {
        val readFd = posixOpen(filePath, O_RDONLY)
        if (readFd < 0) return ByteArray(0)
        return try {
            val fileSize = memScoped {
                val st = alloc<stat>()
                if (fstat(readFd, st.ptr) == 0) st.st_size.toInt().coerceAtLeast(0) else 0
            }
            if (fileSize == 0) return ByteArray(0)
            val out = ByteArray(fileSize)
            var off = 0
            while (off < fileSize) {
                val n = out.usePinned { pinned ->
                    posixRead(readFd, pinned.addressOf(off), (fileSize - off).convert()).toInt()
                }
                if (n <= 0) break
                off += n
            }
            if (off == fileSize) out else out.copyOf(off)
        } finally {
            posixClose(readFd)
        }
    }

    override fun toByteBuffer(): ByteBuffer = ByteBuffer.wrap(toByteArray(), byteOrder = byteOrder)

    private fun ensureFdOpened(): Int {
        return state.lock.withLock {
            if (state.isDisposed || state.isFinished || openFailed) return -1
            if (openAttempted) return fd
            openAttempted = true

            val parent = LinuxPosixPaths.parentOf(filePath)
            if (parent.isNotEmpty()) {
                LinuxPosixPaths.ensureDirectoryHierarchy(parent)
            }

            val flags = O_WRONLY or O_CREAT or (if (appendMode) O_APPEND else O_TRUNC)
            val opened = posixOpen(filePath, flags, 0b110_100_100)
            if (opened < 0) {
                openFailed = true
                val err = errno
                state.emitError(IllegalStateException("Failed to open file for writing ($filePath): ${strerror(err)?.toKString() ?: err}"))
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
            fsync(toClose)
            posixClose(toClose)
        }
    }
}
