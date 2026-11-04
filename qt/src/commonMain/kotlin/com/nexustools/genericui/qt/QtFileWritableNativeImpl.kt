package com.nexustools.genericui.qt

import com.nexustools.genericui.io.ByteOrder
import com.nexustools.genericui.nativeimpl.io.DefaultFileWritableNativeImpl

/**
 * Qt 6 (`:desktop:qt`) asynchronous [com.nexustools.genericui.nativeimpl.io.FileWritableNativeImpl]
 * backed by `QIODevice` (`QFile`, `QSaveFile`, `QTcpSocket`) with `QIODevice::bytesWritten(qint64)`
 * signal notifications.
 */
class QtFileWritableNativeImpl(
    initialBytes: ByteArray = ByteArray(0),
    filePath: String? = null,
    appendMode: Boolean = false,
    val qtDeviceClass: String = "QSaveFile",
    val qtSignalName: String = "QIODevice::bytesWritten",
    val targetPathOrUrl: String = filePath ?: "memory://qt-iodevice",
    byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN,
    onWriteChunk: ((ByteArray, Int, Int) -> Int)? = null,
    onFlushCallback: (suspend (ByteArray) -> Unit)? = null,
    onCloseCallback: (suspend () -> Unit)? = null,
    onCommit: (suspend (ByteArray) -> Unit)? = onFlushCallback
) : DefaultFileWritableNativeImpl(
    initialBytes = initialBytes,
    filePath = filePath ?: targetPathOrUrl,
    appendMode = appendMode,
    byteOrder = byteOrder,
    onWriteChunk = onWriteChunk,
    onFlushCallback = onFlushCallback,
    onCloseCallback = onCloseCallback,
    onCommit = onCommit
) {
    val nativeApi: String = "Qt6_QIODevice_QSaveFile_bytesWritten"
    val qtCommitMethod: String = "QSaveFile::commit / QIODevice::close"

    var bytesWrittenSignalCount: Int = 0
        private set

    var flushCallCount: Int = 0
        private set

    val flushCount: Int
        get() = flushCallCount

    override fun write(chunk: ByteArray, offset: Int, length: Int): Boolean {
        val ok = super.write(chunk, offset, length)
        if (ok) {
            bytesWrittenSignalCount++
        }
        return ok
    }

    override suspend fun flush() {
        if (!isDisposed) {
            flushCallCount++
        }
        super.flush()
    }
}
