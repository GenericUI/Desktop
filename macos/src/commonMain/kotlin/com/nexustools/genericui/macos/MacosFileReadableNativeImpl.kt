package com.nexustools.genericui.macos

import com.nexustools.genericui.io.ByteOrder
import com.nexustools.genericui.nativeimpl.io.DefaultFileReadableNativeImpl

/**
 * macOS Foundation (`NSInputStream` / `NSFileHandle` on GCD) implementation of [com.nexustools.genericui.nativeimpl.io.FileReadableNativeImpl].
 */
class MacosFileReadableNativeImpl(
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
    val nativeApi: String = "Foundation_NSInputStream_NSFileHandle_GCD"
}
