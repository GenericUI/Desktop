package com.nexustools.genericui.linux

import com.nexustools.genericui.io.ByteBuffer
import com.nexustools.genericui.io.ByteOrder
import com.nexustools.genericui.nativeimpl.io.MemoryReadableNativeImpl
import com.nexustools.genericui.nativeimpl.net.HttpResponseNativeImpl

class LinuxHttpResponseNativeImpl(
    override val statusCode: Int,
    override val statusText: String = "",
    override val headers: Map<String, String> = emptyMap(),
    initialBodyBytes: ByteArray = ByteArray(0),
    override val url: String = "",
    byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN
) : MemoryReadableNativeImpl(initialBodyBytes, byteOrder), HttpResponseNativeImpl {
    private val storedBodyBytes = initialBodyBytes.copyOf()

    override fun header(name: String): String? {
        return headers[name] ?: headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    override val bodyBytes: ByteArray
        get() = storedBodyBytes.copyOf()

    override val bodyBuffer: ByteBuffer
        get() = ByteBuffer.wrap(storedBodyBytes.copyOf(), byteOrder = byteOrder)

    override val bodyText: String
        get() = storedBodyBytes.decodeToString()
}
