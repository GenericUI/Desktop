package com.nexustools.genericui.macos

import com.nexustools.genericui.io.ByteOrder
import com.nexustools.genericui.nativeimpl.io.DefaultFileWritableNativeImpl

/**
 * macOS Foundation (`NSOutputStream` / `NSFileHandle` on GCD) implementation of [com.nexustools.genericui.nativeimpl.io.FileWritableNativeImpl].
 */
class MacosFileWritableNativeImpl(
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
    val nativeApi: String = "Foundation_NSOutputStream_NSFileHandle_GCD"

    var flushCount: Int = 0
        private set

    override suspend fun flush() {
        if (!isDisposed) {
            flushCount++
        }
        super.flush()
    }
}
