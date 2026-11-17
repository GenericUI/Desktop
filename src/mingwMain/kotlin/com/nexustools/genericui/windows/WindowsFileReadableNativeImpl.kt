@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.io.ByteBuffer
import com.nexustools.genericui.io.ByteOrder
import com.nexustools.genericui.io.Readable
import com.nexustools.genericui.nativeimpl.io.FileReadableNativeImpl
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.windows.CloseHandle
import platform.windows.CreateFileW
import platform.windows.FILE_ATTRIBUTE_NORMAL
import platform.windows.FILE_SHARE_READ
import platform.windows.FILE_SHARE_WRITE
import platform.windows.GENERIC_READ
import platform.windows.GetLastError
import platform.windows.HANDLE
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.OPEN_EXISTING
import platform.windows.ReadFile

/**
 * Windows Win32 (`CreateFileW` / `ReadFile` / `CloseHandle`) implementation of [FileReadableNativeImpl].
 */
class WindowsFileReadableNativeImpl(
    override val filePath: String,
    initialByteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN
) : FileReadableNativeImpl {

    val nativeApi: String = "Win32_CreateFileW_ReadFile_CloseHandle"

    private val state = WindowsStreamState(initialByteOrder = initialByteOrder)
    private var fileHandle: HANDLE? = null
    private var openAttempted: Boolean = false

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
        val chunk = WindowsIoHelper.runIo {
            readNextChunkSync(boundedMax)
        }
        if (chunk == null) {
            state.emitEnd()
            return null
        }
        state.emitData(chunk)
        return chunk
    }

    private fun readNextChunkSync(maxBytes: Int): ByteBuffer? {
        val handle = ensureHandleOpened() ?: return null
        val buffer = ByteArray(maxBytes)
        var bytesReadCount = 0
        var lastErr = 0u
        val ok = memScoped {
            val readVar = alloc<UIntVar>()
            readVar.value = 0u
            val res = buffer.usePinned { pinned ->
                ReadFile(
                    hFile = handle,
                    lpBuffer = pinned.addressOf(0),
                    nNumberOfBytesToRead = maxBytes.toUInt(),
                    lpNumberOfBytesRead = readVar.ptr,
                    lpOverlapped = null
                )
            }
            bytesReadCount = readVar.value.toInt()
            if (res == 0) {
                lastErr = GetLastError()
            }
            res != 0
        }
        if (!ok) {
            closeHandleOnly()
            state.emitError(IllegalStateException("Win32 ReadFile failed ($filePath): error=$lastErr"))
            return null
        }
        if (bytesReadCount <= 0) {
            closeHandleOnly()
            return null
        }
        val slice = if (bytesReadCount == buffer.size) buffer else buffer.copyOf(bytesReadCount)
        state.recordRead(slice.size)
        return ByteBuffer.wrap(slice, byteOrder = byteOrder)
    }

    private fun ensureHandleOpened(): HANDLE? {
        return state.lock.withLock {
            if (state.isDisposed || state.isEnded) return@withLock null
            fileHandle?.let { return@withLock it }
            if (openAttempted) return@withLock null
            openAttempted = true
            val normalized = WindowsIoHelper.normalizeWin32Path(filePath)
            val handle = CreateFileW(
                lpFileName = normalized,
                dwDesiredAccess = GENERIC_READ,
                dwShareMode = (FILE_SHARE_READ or FILE_SHARE_WRITE).toUInt(),
                lpSecurityAttributes = null,
                dwCreationDisposition = OPEN_EXISTING.toUInt(),
                dwFlagsAndAttributes = FILE_ATTRIBUTE_NORMAL.toUInt(),
                hTemplateFile = null
            )
            if (handle == null || handle == INVALID_HANDLE_VALUE) {
                return@withLock null
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
            CloseHandle(toClose)
        }
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
        closeHandleOnly()
        state.emitEnd()
        state.markDisposedAndNotifyClose()
    }

    override fun dispose() {
        closeHandleOnly()
        state.markDisposedAndNotifyClose()
    }
}
