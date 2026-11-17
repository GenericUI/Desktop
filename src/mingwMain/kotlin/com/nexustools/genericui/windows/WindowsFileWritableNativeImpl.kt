@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.io.ByteBuffer
import com.nexustools.genericui.io.ByteOrder
import com.nexustools.genericui.io.Writable
import com.nexustools.genericui.nativeimpl.io.FileWritableNativeImpl
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cValue
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.windows.CREATE_ALWAYS
import platform.windows.CloseHandle
import platform.windows.CreateFileW
import platform.windows.FILE_ATTRIBUTE_NORMAL
import platform.windows.FILE_END
import platform.windows.FILE_SHARE_READ
import platform.windows.FILE_SHARE_WRITE
import platform.windows.FlushFileBuffers
import platform.windows.GENERIC_WRITE
import platform.windows.GetLastError
import platform.windows.HANDLE
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.LARGE_INTEGER
import platform.windows.OPEN_ALWAYS
import platform.windows.SetFilePointerEx
import platform.windows.WriteFile

/**
 * Windows Win32 (`CreateFileW` / `WriteFile` / `FlushFileBuffers` / `CloseHandle`) implementation
 * of [FileWritableNativeImpl].
 */
class WindowsFileWritableNativeImpl(
    override val filePath: String,
    override val appendMode: Boolean = false,
    initialHighWaterMark: Int = 64 * 1024,
    initialByteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN
) : FileWritableNativeImpl {

    val nativeApi: String = "Win32_CreateFileW_WriteFile_FlushFileBuffers"

    private val state = WindowsStreamState(
        initialByteOrder = initialByteOrder,
        initialHighWaterMark = initialHighWaterMark
    )
    private var fileHandle: HANDLE? = null
    private var openAttempted: Boolean = false
    private var openFailed: Boolean = false

    var flushCount: Int = 0
        private set

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
        val handle = ensureHandleOpened() ?: return false
        if (length == 0) return true

        val ok = writeAllSync(handle, chunk, offset, length)
        if (!ok) return false

        val slice = if (offset == 0 && length == chunk.size) chunk.copyOf() else chunk.copyOfRange(offset, offset + length)
        state.recordWrite(length)
        state.emitWrite(ByteBuffer.wrap(slice, byteOrder = byteOrder))
        return true
    }

    private fun writeAllSync(handle: HANDLE, chunk: ByteArray, offset: Int, length: Int): Boolean {
        var remaining = length
        var pos = offset
        while (remaining > 0) {
            var writtenNow = 0
            var lastErr = 0u
            val ok = memScoped {
                val writtenVar = alloc<UIntVar>()
                writtenVar.value = 0u
                val res = chunk.usePinned { pinned ->
                    WriteFile(
                        hFile = handle,
                        lpBuffer = pinned.addressOf(pos),
                        nNumberOfBytesToWrite = remaining.toUInt(),
                        lpNumberOfBytesWritten = writtenVar.ptr,
                        lpOverlapped = null
                    )
                }
                writtenNow = writtenVar.value.toInt()
                if (res == 0) {
                    lastErr = GetLastError()
                }
                res != 0
            }
            if (!ok || writtenNow <= 0) {
                state.emitError(IllegalStateException("Win32 WriteFile failed ($filePath): error=$lastErr"))
                return false
            }
            pos += writtenNow
            remaining -= writtenNow
        }
        return true
    }

    private fun ensureHandleOpened(): HANDLE? {
        return state.lock.withLock {
            if (state.isDisposed || state.isFinished || openFailed) return@withLock null
            fileHandle?.let { return@withLock it }
            if (openAttempted) return@withLock null
            openAttempted = true

            val normalized = WindowsIoHelper.normalizeWin32Path(filePath)
            WindowsIoHelper.ensureParentDirectories(normalized)

            val handle = CreateFileW(
                lpFileName = normalized,
                dwDesiredAccess = GENERIC_WRITE.toUInt(),
                dwShareMode = (FILE_SHARE_READ or FILE_SHARE_WRITE).toUInt(),
                lpSecurityAttributes = null,
                dwCreationDisposition = (if (appendMode) OPEN_ALWAYS else CREATE_ALWAYS).toUInt(),
                dwFlagsAndAttributes = FILE_ATTRIBUTE_NORMAL.toUInt(),
                hTemplateFile = null
            )
            if (handle == null || handle == INVALID_HANDLE_VALUE) {
                openFailed = true
                state.emitError(
                    IllegalStateException("Win32 CreateFileW write open failed ($normalized): error=${GetLastError()}")
                )
                return@withLock null
            }
            if (appendMode) {
                val zeroDistance = cValue<LARGE_INTEGER> { QuadPart = 0L }
                SetFilePointerEx(handle, zeroDistance, null, FILE_END.toUInt())
            }
            fileHandle = handle
            handle
        }
    }

    private fun closeHandleOnly() {
        val toClose = state.lock.withLock {
            val h = fileHandle
            fileHandle = null
            h
        }
        if (toClose != null && toClose != INVALID_HANDLE_VALUE) {
            FlushFileBuffers(toClose)
            CloseHandle(toClose)
        }
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
        if (isDisposed) return
        flushCount++
        val handle = ensureHandleOpened() ?: return
        WindowsIoHelper.runIo {
            FlushFileBuffers(handle)
        }
        state.emitDrain()
    }

    override suspend fun end(finalBuffer: ByteBuffer?) {
        if (finalBuffer != null && finalBuffer.hasRemaining) {
            write(finalBuffer)
        }
        flush()
        closeHandleOnly()
        state.emitFinish()
        state.markDisposedAndNotifyClose()
    }

    override suspend fun close() {
        closeHandleOnly()
        state.markDisposedAndNotifyClose()
    }

    override fun dispose() {
        closeHandleOnly()
        state.markDisposedAndNotifyClose()
    }
}
