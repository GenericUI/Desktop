package com.nexustools.genericui.swing

import com.nexustools.genericui.io.ByteOrder
import com.nexustools.genericui.nativeimpl.io.DefaultFileWritableNativeImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.Flushable
import java.io.OutputStream
import java.nio.ByteBuffer as NioByteBuffer
import java.nio.channels.Channels
import java.nio.channels.WritableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import javax.swing.SwingUtilities
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext

/**
 * Java Swing / JVM (`:desktop:swing`) implementation of [com.nexustools.genericui.nativeimpl.io.FileWritableNativeImpl]
 * wrapping a [File], [Path], [OutputStream], [WritableByteChannel], or in-memory buffer and executing all
 * disk flushes on [Dispatchers.IO] so the Swing Event Dispatch Thread (EDT) is never blocked.
 */
class SwingFileWritableNativeImpl(
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
    val nativeApi: String = "Java_NIO_WritableByteChannel_Dispatchers_IO"

    var flushCount: Int = 0
        private set

    constructor(
        outputStream: OutputStream,
        filePath: String? = null,
        appendMode: Boolean = false,
        byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN,
        onCommit: (suspend () -> Unit)? = null
    ) : this(
        holder = ChannelWriteHolder(
            channelOpener = { Channels.newChannel(outputStream) },
            flushTargetProvider = { outputStream },
            onCommit = onCommit
        ),
        filePath = filePath,
        appendMode = appendMode,
        byteOrder = byteOrder
    )

    constructor(
        channel: WritableByteChannel,
        filePath: String? = null,
        appendMode: Boolean = false,
        byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN,
        onCommit: (suspend () -> Unit)? = null
    ) : this(
        holder = ChannelWriteHolder(
            channelOpener = { channel },
            flushTargetProvider = { channel as? Flushable },
            onCommit = onCommit
        ),
        filePath = filePath,
        appendMode = appendMode,
        byteOrder = byteOrder
    )

    constructor(
        file: File,
        append: Boolean = false,
        byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN,
        onCommit: (suspend () -> Unit)? = null
    ) : this(
        holder = ChannelWriteHolder(
            channelOpener = {
                file.parentFile?.mkdirs()
                FileOutputStream(file, append).channel
            },
            flushTargetProvider = { null },
            onCommit = onCommit
        ),
        filePath = file.absolutePath,
        appendMode = append,
        byteOrder = byteOrder
    )

    constructor(
        path: Path,
        append: Boolean = false,
        byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN,
        onCommit: (suspend () -> Unit)? = null
    ) : this(
        holder = ChannelWriteHolder(
            channelOpener = {
                path.parent?.let { Files.createDirectories(it) }
                if (append) {
                    Files.newByteChannel(
                        path,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND
                    )
                } else {
                    Files.newByteChannel(
                        path,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING
                    )
                }
            },
            flushTargetProvider = { null },
            onCommit = onCommit
        ),
        filePath = path.toAbsolutePath().toString(),
        appendMode = append,
        byteOrder = byteOrder
    )

    private constructor(
        holder: ChannelWriteHolder,
        filePath: String?,
        appendMode: Boolean,
        byteOrder: ByteOrder
    ) : this(
        initialBytes = ByteArray(0),
        filePath = filePath,
        appendMode = appendMode,
        byteOrder = byteOrder,
        onWriteChunk = { chunk, offset, length -> holder.writeChunk(chunk, offset, length) },
        onFlushCallback = { holder.flushChannel() },
        onCloseCallback = { holder.closeChannel() },
        onCommit = { holder.flushChannel() }
    )

    override suspend fun flush() {
        if (!isDisposed) {
            flushCount++
        }
        super.flush()
    }

    private class ChannelWriteHolder(
        private val channelOpener: () -> WritableByteChannel,
        private val flushTargetProvider: () -> Flushable?,
        private val onCommit: (suspend () -> Unit)?
    ) {
        @Volatile
        private var activeChannel: WritableByteChannel? = null

        @Volatile
        private var closed: Boolean = false

        fun writeChunk(chunk: ByteArray, offset: Int, length: Int): Int {
            synchronized(this) {
                if (closed) return 0
                val ch = activeChannel ?: try {
                    channelOpener().also { activeChannel = it }
                } catch (_: Throwable) {
                    return 0
                }
                val src = NioByteBuffer.wrap(chunk, offset, length)
                var total = 0
                try {
                    while (src.hasRemaining()) {
                        val n = ch.write(src)
                        if (n <= 0) break
                        total += n
                    }
                } catch (_: Throwable) {
                    return total
                }
                return total
            }
        }

        suspend fun flushChannel() {
            runOnSwingIo {
                synchronized(this) {
                    if (!closed) {
                        runCatching { flushTargetProvider()?.flush() }
                    }
                }
            }
            onCommit?.invoke()
        }

        suspend fun closeChannel() {
            runOnSwingIo {
                synchronized(this) {
                    closed = true
                    val ch = activeChannel
                    activeChannel = null
                    if (ch != null) {
                        runCatching { ch.close() }
                    }
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
