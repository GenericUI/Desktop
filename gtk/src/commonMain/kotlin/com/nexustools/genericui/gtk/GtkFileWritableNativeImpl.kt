package com.nexustools.genericui.gtk

import com.nexustools.genericui.io.ByteOrder
import com.nexustools.genericui.nativeimpl.io.DefaultFileWritableNativeImpl

/**
 * Linux GTK4 / GIO (`:desktop:gtk`) asynchronous [com.nexustools.genericui.nativeimpl.io.FileWritableNativeImpl]
 * backed by GIO `GOutputStream` (`g_output_stream_write_bytes_async`, `g_output_stream_flush_async`,
 * `g_output_stream_close_async`).
 */
class GtkFileWritableNativeImpl(
    initialBytes: ByteArray = ByteArray(0),
    filePath: String? = null,
    appendMode: Boolean = false,
    val gioStreamType: String = "GFileOutputStream",
    val gioAsyncWriteSymbol: String = "g_output_stream_write_bytes_async",
    val gioAsyncFlushSymbol: String = "g_output_stream_flush_async",
    val targetPathOrUri: String = filePath ?: "memory://gtk-output",
    byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN,
    onWriteChunk: ((ByteArray, Int, Int) -> Int)? = null,
    onFlushCallback: (suspend (ByteArray) -> Unit)? = null,
    onCloseCallback: (suspend () -> Unit)? = null,
    onCommit: (suspend (ByteArray) -> Unit)? = onFlushCallback
) : DefaultFileWritableNativeImpl(
    initialBytes = initialBytes,
    filePath = filePath ?: targetPathOrUri,
    appendMode = appendMode,
    byteOrder = byteOrder,
    onWriteChunk = onWriteChunk,
    onFlushCallback = onFlushCallback,
    onCloseCallback = onCloseCallback,
    onCommit = onCommit
) {
    val nativeApi: String = "GIO_GOutputStream_g_output_stream_write_bytes_async"
    val gioCloseAsyncSymbol: String = "g_output_stream_close_async"

    var asyncWriteCallCount: Int = 0
        private set

    var asyncFlushCallCount: Int = 0
        private set

    val flushCount: Int
        get() = asyncFlushCallCount

    override fun write(chunk: ByteArray, offset: Int, length: Int): Boolean {
        val ok = super.write(chunk, offset, length)
        if (ok) {
            asyncWriteCallCount++
        }
        return ok
    }

    override suspend fun flush() {
        if (!isDisposed) {
            asyncFlushCallCount++
        }
        super.flush()
    }
}
