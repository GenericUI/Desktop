package com.nexustools.genericui.swing

import com.nexustools.genericui.io.ByteOrder
import com.nexustools.genericui.jvm.JvmStreamFileReadableNativeImpl
import java.io.File
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.channels.ReadableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities

/**
 * Java Swing implementation of [com.nexustools.genericui.nativeimpl.io.FileReadableNativeImpl]
 * extending [JvmStreamFileReadableNativeImpl] and ensuring disk/stream reads avoid blocking the
 * Swing Event Dispatch Thread.
 */
class SwingFileReadableNativeImpl : JvmStreamFileReadableNativeImpl {

    override val nativeApi: String = "Java_NIO_ReadableByteChannel_Dispatchers_IO"

    constructor(
        initialBytes: ByteArray = ByteArray(0),
        filePath: String? = null,
        byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN,
        onReadChunk: (suspend (Int) -> ByteArray?)? = null,
        onCloseCallback: (suspend () -> Unit)? = null
    ) : super(
        initialBytes = initialBytes,
        filePath = filePath,
        byteOrder = byteOrder,
        onReadChunk = onReadChunk,
        onCloseCallback = onCloseCallback
    )

    constructor(
        file: File,
        byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN
    ) : super(
        file = file,
        byteOrder = byteOrder,
        isOnMainThread = { SwingUtilities.isEventDispatchThread() }
    )

    constructor(
        path: Path,
        byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN
    ) : super(
        streamOpener = { Channels.newInputStream(Files.newByteChannel(path)) },
        filePath = path.toAbsolutePath().toString(),
        byteOrder = byteOrder,
        isOnMainThread = { SwingUtilities.isEventDispatchThread() }
    )

    constructor(
        inputStream: InputStream,
        filePath: String? = null,
        byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN
    ) : super(
        inputStream = inputStream,
        filePath = filePath,
        byteOrder = byteOrder,
        isOnMainThread = { SwingUtilities.isEventDispatchThread() }
    )

    constructor(
        channel: ReadableByteChannel,
        filePath: String? = null,
        byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN
    ) : super(
        channel = channel,
        filePath = filePath,
        byteOrder = byteOrder,
        isOnMainThread = { SwingUtilities.isEventDispatchThread() }
    )
}
