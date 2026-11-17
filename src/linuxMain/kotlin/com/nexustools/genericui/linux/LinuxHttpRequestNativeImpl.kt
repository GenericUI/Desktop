package com.nexustools.genericui.linux

import com.nexustools.genericui.io.ByteBuffer
import com.nexustools.genericui.io.ByteOrder
import com.nexustools.genericui.nativeimpl.io.MemoryWritableNativeImpl
import com.nexustools.genericui.nativeimpl.net.HttpRequestNativeImpl
import com.nexustools.genericui.net.http.HttpMethod
import com.nexustools.genericui.net.http.HttpRequest

class LinuxHttpRequestNativeImpl(
    override val url: String,
    override val method: HttpMethod = HttpMethod.GET,
    headers: Map<String, String> = emptyMap(),
    private val initialBodyBytes: ByteArray? = null,
    override var timeoutMs: Long = 30_000L,
    override var followRedirects: Boolean = true,
    byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN
) : MemoryWritableNativeImpl(
    initialBytes = initialBodyBytes ?: ByteArray(0),
    byteOrder = byteOrder
), HttpRequestNativeImpl {
    private val lock = LinuxReentrantLock()
    private val headerMap = LinkedHashMap<String, String>(headers)

    override var headers: Map<String, String>
        get() = lock.withLock { headerMap.toMap() }
        set(value) {
            lock.withLock {
                headerMap.clear()
                headerMap.putAll(value)
            }
        }

    override fun header(name: String): String? {
        return lock.withLock {
            headerMap[name] ?: headerMap.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        }
    }

    override fun setHeader(name: String, value: String): HttpRequest {
        lock.withLock {
            val existingKey = headerMap.keys.firstOrNull { it.equals(name, ignoreCase = true) }
            if (existingKey != null && existingKey != name) {
                headerMap.remove(existingKey)
            }
            headerMap[name] = value
        }
        return this
    }

    override val bodyBytes: ByteArray?
        get() {
            val bytes = toByteArray()
            return if (bytes.isEmpty() && initialBodyBytes == null) null else bytes
        }

    override val bodyBuffer: ByteBuffer?
        get() = bodyBytes?.let { ByteBuffer.wrap(it, byteOrder = byteOrder) }

    override val bodyText: String?
        get() = bodyBytes?.decodeToString()
}
