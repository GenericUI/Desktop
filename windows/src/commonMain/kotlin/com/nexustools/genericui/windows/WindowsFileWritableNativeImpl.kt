package com.nexustools.genericui.windows

import com.nexustools.genericui.io.ByteOrder
import com.nexustools.genericui.nativeimpl.io.DefaultFileWritableNativeImpl

/**
 * Windows Win32 (`WriteFile` / `FlushFileBuffers` / `OVERLAPPED` I/O completion) implementation of [com.nexustools.genericui.nativeimpl.io.FileWritableNativeImpl].
 */
class WindowsFileWritableNativeImpl(
    initialBytes: ByteArray = ByteArray(0),
    filePath: String? = null,
    appendMode: Boolean = false,
    byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN,
    onWriteChunk: ((ByteArray, Int, Int) -> Int)? = null,
    onFlushCallback: (suspend (ByteArray) -> Unit)? = null,
    onCloseCallback: (suspend () -> Unit)? = null,
    onCommit: (suspend (ByteArray) -> Unit)? = onFlushCallback
) : DefaultFileWritableNativeImpl(
    initialBytes = initialBytes,
    filePath = filePath,
    appendMode = appendMode,
    byteOrder = byteOrder,
    onWriteChunk = onWriteChunk,
    onFlushCallback = onFlushCallback,
    onCloseCallback = onCloseCallback,
    onCommit = onCommit
) {
    val nativeApi: String = "Win32_WriteFile_FlushFileBuffers"

    var flushCount: Int = 0
        private set

    override suspend fun flush() {
        if (!isDisposed) {
            flushCount++
        }
        super.flush()
    }
}
