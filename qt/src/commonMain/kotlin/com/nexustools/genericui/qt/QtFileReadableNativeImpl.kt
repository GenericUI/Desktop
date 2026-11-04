package com.nexustools.genericui.qt

import com.nexustools.genericui.io.ByteBuffer
import com.nexustools.genericui.io.ByteOrder
import com.nexustools.genericui.nativeimpl.io.DefaultFileReadableNativeImpl

/**
 * Qt 6 (`:desktop:qt`) asynchronous [com.nexustools.genericui.nativeimpl.io.FileReadableNativeImpl]
 * backed by `QIODevice` (`QFile`, `QTcpSocket`, `QNetworkReply`) with `QIODevice::readyRead` and
 * `QIODevice::readChannelFinished` signals connected to Kotlin coroutine continuations.
 */
class QtFileReadableNativeImpl(
    initialBytes: ByteArray = ByteArray(0),
    filePath: String? = null,
    val qtDeviceClass: String = "QFile",
    val qtSignalName: String = "QIODevice::readyRead",
    val sourcePathOrUrl: String = filePath ?: "memory://qt-iodevice",
    byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN,
    onReadChunk: (suspend (Int) -> ByteArray?)? = null,
    onCloseCallback: (suspend () -> Unit)? = null
) : DefaultFileReadableNativeImpl(
    initialBytes = initialBytes,
    filePath = filePath ?: sourcePathOrUrl,
    byteOrder = byteOrder,
    onReadChunk = onReadChunk,
    onCloseCallback = onCloseCallback
) {
    val nativeApi: String = "Qt6_QIODevice_readyRead"
    val qtCloseMethod: String = "QIODevice::close"

    var readyReadEmitCount: Int = 0
        private set

    override suspend fun read(maxBytes: Int): ByteBuffer? {
        if (isDisposed || isEnded) return null
        val chunk = super.read(maxBytes) ?: return null
        readyReadEmitCount++
        return chunk
    }
}
