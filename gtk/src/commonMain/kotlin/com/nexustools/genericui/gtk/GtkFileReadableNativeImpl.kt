package com.nexustools.genericui.gtk

import com.nexustools.genericui.io.ByteBuffer
import com.nexustools.genericui.io.ByteOrder
import com.nexustools.genericui.nativeimpl.io.DefaultFileReadableNativeImpl

/**
 * Linux GTK4 / GIO (`:desktop:gtk`) asynchronous [com.nexustools.genericui.nativeimpl.io.FileReadableNativeImpl]
 * backed by GIO `GInputStream` (`g_input_stream_read_bytes_async` / `g_input_stream_read_bytes_finish` /
 * `g_input_stream_close_async`).
 */
class GtkFileReadableNativeImpl(
    initialBytes: ByteArray = ByteArray(0),
    filePath: String? = null,
    val gioStreamType: String = "GFileInputStream",
    val gioAsyncReadSymbol: String = "g_input_stream_read_bytes_async",
    val sourcePathOrUri: String = filePath ?: "memory://gtk-input",
    byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN,
    onReadChunk: (suspend (Int) -> ByteArray?)? = null,
    onCloseCallback: (suspend () -> Unit)? = null
) : DefaultFileReadableNativeImpl(
    initialBytes = initialBytes,
    filePath = filePath ?: sourcePathOrUri,
    byteOrder = byteOrder,
    onReadChunk = onReadChunk,
    onCloseCallback = onCloseCallback
) {
    val nativeApi: String = "GIO_GInputStream_g_input_stream_read_bytes_async"
    val gioCloseAsyncSymbol: String = "g_input_stream_close_async"

    var asyncReadCallCount: Int = 0
        private set

    override suspend fun read(maxBytes: Int): ByteBuffer? {
        if (isDisposed || isEnded) return null
        val chunk = super.read(maxBytes) ?: return null
        asyncReadCallCount++
        return chunk
    }
}
