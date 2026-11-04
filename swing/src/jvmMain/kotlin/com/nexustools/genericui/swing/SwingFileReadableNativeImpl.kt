package com.nexustools.genericui.swing

import com.nexustools.genericui.io.ByteOrder
import com.nexustools.genericui.nativeimpl.io.DefaultFileReadableNativeImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer as NioByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ReadableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext

/**
 * Java Swing / JVM (`:desktop:swing`) implementation of [com.nexustools.genericui.nativeimpl.io.FileReadableNativeImpl]
 * wrapping a [ByteArray], [File], [Path], [InputStream], or [ReadableByteChannel] and executing all channel reads
 * on [Dispatchers.IO] so the Swing Event Dispatch Thread (EDT) is never blocked.
 */
class SwingFileReadableNativeImpl(
    initialBytes: ByteArray = ByteArray(0),
    filePath: String? = null,
    byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN,
    onReadChunk: (suspend (Int) -> ByteArray?)? = null,
    onCloseCallback: (suspend () -> Unit)? = null
) : DefaultFileReadableNativeImpl(
    initialBytes = initialBytes,
    filePath = filePath,
    byteOrder = byteOrder,
    onReadChunk = onReadChunk,
    onCloseCallback = onCloseCallback
) {
    val nativeApi: String = "Java_NIO_ReadableByteChannel_Dispatchers_IO"

    constructor(
        file: File,
        byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN
    ) : this(
        channelHolder = ChannelReadHolder { FileInputStream(file).channel },
        filePath = file.absolutePath,
        byteOrder = byteOrder
    )

    constructor(
        path: Path,
        byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN
    ) : this(
        channelHolder = ChannelReadHolder { Files.newByteChannel(path) },
        filePath = path.toAbsolutePath().toString(),
        byteOrder = byteOrder
    )

    constructor(
        inputStream: InputStream,
        filePath: String? = null,
        byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN
    ) : this(
        channelHolder = ChannelReadHolder { Channels.newChannel(inputStream) },
        filePath = filePath,
        byteOrder = byteOrder
    )

    constructor(
        channel: ReadableByteChannel,
        filePath: String? = null,
        byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN
    ) : this(
        channelHolder = ChannelReadHolder { channel },
        filePath = filePath,
        byteOrder = byteOrder
    )

    private constructor(
        channelHolder: ChannelReadHolder,
        filePath: String?,
        byteOrder: ByteOrder
    ) : this(
        initialBytes = ByteArray(0),
        filePath = filePath,
        byteOrder = byteOrder,
        onReadChunk = { maxBytes -> channelHolder.readChunk(maxBytes) },
        onCloseCallback = { channelHolder.closeChannel() }
    )

    private class ChannelReadHolder(
        private val opener: () -> ReadableByteChannel
    ) {
        @Volatile
        private var activeChannel: ReadableByteChannel? = null

        @Volatile
        private var closed: Boolean = false

        suspend fun readChunk(maxBytes: Int): ByteArray? = runOnSwingIo {
            synchronized(this) {
                if (closed) return@runOnSwingIo null
                val ch = activeChannel ?: try {
                    opener().also { activeChannel = it }
                } catch (_: Throwable) {
                    closed = true
                    return@runOnSwingIo null
                }
                val limit = maxBytes.coerceIn(1, 1024 * 1024)
                val dst = NioByteBuffer.allocate(limit)
                val count = try {
                    ch.read(dst)
                } catch (_: Throwable) {
                    -1
                }
                if (count < 0) {
                    closed = true
                    runCatching { ch.close() }
                    return@runOnSwingIo null
                }
                if (count == 0) {
                    return@runOnSwingIo ByteArray(0)
                }
                val out = ByteArray(count)
                dst.flip()
                dst.get(out, 0, count)
                out
            }
        }

        suspend fun closeChannel() = runOnSwingIo {
            synchronized(this) {
                closed = true
                val ch = activeChannel
                activeChannel = null
                if (ch != null) {
                    runCatching { ch.close() }
                }
            }
        }

        private suspend fun <T> runOnSwingIo(block: () -> T): T {
            val hasInterceptor = coroutineContext[ContinuationInterceptor] != null
            return if (hasInterceptor || SwingUtilities.isEventDispatchThread()) {
                withContext(Dispatchers.IO) { block() }
            } else {
                block()
            }
        }
    }
}
