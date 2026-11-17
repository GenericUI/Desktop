package com.nexustools.genericui.swing

import com.nexustools.genericui.io.ByteOrder
import com.nexustools.genericui.jvm.JvmStreamFileWritableNativeImpl
import java.io.File
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.WritableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import javax.swing.SwingUtilities

/**
 * Java Swing implementation of [com.nexustools.genericui.nativeimpl.io.FileWritableNativeImpl]
 * extending [JvmStreamFileWritableNativeImpl] and ensuring disk flushes avoid blocking the
 * Swing Event Dispatch Thread.
 */
class SwingFileWritableNativeImpl : JvmStreamFileWritableNativeImpl {

    override val nativeApi: String = "Java_NIO_WritableByteChannel_Dispatchers_IO"

    constructor(
        initialBytes: ByteArray = ByteArray(0),
        filePath: String? = null,
        appendMode: Boolean = false,
        byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN,
        onWriteChunk: ((ByteArray, Int, Int) -> Int)? = null,
        onFlushCallback: (suspend (ByteArray) -> Unit)? = null,
        onCloseCallback: (suspend () -> Unit)? = null,
        onCommit: (suspend (ByteArray) -> Unit)? = onFlushCallback
    ) : super(
        initialBytes = initialBytes,
        filePath = filePath,
        appendMode = appendMode,
        byteOrder = byteOrder,
        onWriteChunk = onWriteChunk,
        onFlushCallback = onFlushCallback,
        onCloseCallback = onCloseCallback,
        onCommit = onCommit
    )

    constructor(
        outputStream: OutputStream,
        filePath: String? = null,
        appendMode: Boolean = false,
        byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN,
        onCommit: (suspend () -> Unit)? = null
    ) : super(
        outputStream = outputStream,
        filePath = filePath,
        appendMode = appendMode,
        byteOrder = byteOrder,
        isOnMainThread = { SwingUtilities.isEventDispatchThread() },
        onCommit = onCommit
    )

    constructor(
        channel: WritableByteChannel,
        filePath: String? = null,
        appendMode: Boolean = false,
        byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN,
        onCommit: (suspend () -> Unit)? = null
    ) : super(
        channel = channel,
        filePath = filePath,
        appendMode = appendMode,
        byteOrder = byteOrder,
        isOnMainThread = { SwingUtilities.isEventDispatchThread() },
        onCommit = onCommit
    )

    constructor(
        file: File,
        append: Boolean = false,
        byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN,
        onCommit: (suspend () -> Unit)? = null
    ) : super(
        file = file,
        append = append,
        byteOrder = byteOrder,
        isOnMainThread = { SwingUtilities.isEventDispatchThread() },
        onCommit = onCommit
    )

    constructor(
        path: Path,
        append: Boolean = false,
        byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN,
        onCommit: (suspend () -> Unit)? = null
    ) : super(
        streamOpener = {
            path.parent?.let { Files.createDirectories(it) }
            val ch = if (append) {
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
            Channels.newOutputStream(ch)
        },
        filePath = path.toAbsolutePath().toString(),
        appendMode = append,
        byteOrder = byteOrder,
        isOnMainThread = { SwingUtilities.isEventDispatchThread() },
        onCommit = onCommit
    )
}
