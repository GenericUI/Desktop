package com.nexustools.genericui.linux

import com.nexustools.genericui.io.ByteBuffer
import com.nexustools.genericui.io.ByteOrder
import com.nexustools.genericui.io.Readable
import com.nexustools.genericui.io.Stream
import com.nexustools.genericui.io.Writable
import com.nexustools.genericui.nativeimpl.net.HttpConnectionNativeImpl
import com.nexustools.genericui.net.http.HttpRequest
import com.nexustools.genericui.net.http.HttpResponse

class LinuxHttpConnectionNativeImpl(
    override val request: HttpRequest,
    override val response: HttpResponse
) : HttpConnectionNativeImpl {
    private val lock = LinuxReentrantLock()
    private var disposedState = false

    override var byteOrder: ByteOrder
        get() = response.byteOrder
        set(value) {
            response.byteOrder = value
            request.byteOrder = value
        }

    override val isReadable: Boolean get() = !isDisposed && response.isReadable
    override val isPaused: Boolean get() = response.isPaused
    override val isEnded: Boolean get() = response.isEnded
    override val bytesRead: Long get() = response.bytesRead

    override val isWritable: Boolean get() = !isDisposed && request.isWritable
    override val isFinished: Boolean get() = request.isFinished
    override val bytesWritten: Long get() = request.bytesWritten

    override var highWaterMark: Int
        get() = request.highWaterMark
        set(value) {
            request.highWaterMark = value
        }

    override val isDisposed: Boolean
        get() = lock.withLock { disposedState || (response.isDisposed && request.isDisposed) }

    override suspend fun read(maxBytes: Int): ByteBuffer? = response.read(maxBytes)

    override suspend fun readAllBuffer(): ByteBuffer = response.readAllBuffer()

    override suspend fun readAllBytes(): ByteArray = response.readAllBytes()

    override suspend fun readAllText(encoding: String): String = response.readAllText(encoding)

    override fun onData(listener: suspend (ByteBuffer) -> Unit): Readable {
        response.onData(listener)
        return this
    }

    override fun onEnd(listener: suspend () -> Unit): Readable {
        response.onEnd(listener)
        return this
    }

    override fun pause(): Readable {
        response.pause()
        return this
    }

    override fun resume(): Readable {
        response.resume()
        return this
    }

    override fun unshift(buffer: ByteBuffer) {
        response.unshift(buffer)
    }

    override fun write(buffer: ByteBuffer): Boolean = request.write(buffer)

    override fun write(chunk: ByteArray, offset: Int, length: Int): Boolean {
        return request.write(chunk, offset, length)
    }

    override fun onWrite(listener: (ByteBuffer) -> Unit): Writable {
        request.onWrite(listener)
        return this
    }

    override fun onDrain(listener: suspend () -> Unit): Writable {
        request.onDrain(listener)
        return this
    }

    override fun onFinish(listener: suspend () -> Unit): Writable {
        request.onFinish(listener)
        return this
    }

    override fun onError(listener: (Throwable) -> Unit): Stream {
        response.onError(listener)
        request.onError(listener)
        return this
    }

    override fun onClose(listener: () -> Unit): Stream {
        response.onClose(listener)
        request.onClose(listener)
        return this
    }

    override suspend fun flush() {
        request.flush()
    }

    override suspend fun end(finalBuffer: ByteBuffer?) {
        request.end(finalBuffer)
    }

    override suspend fun close() {
        request.close()
        response.close()
        dispose()
    }

    override fun dispose() {
        val shouldDispose = lock.withLock {
            if (disposedState) false else {
                disposedState = true
                true
            }
        }
        if (shouldDispose) {
            request.dispose()
            response.dispose()
        }
    }

    override fun toByteArray(): ByteArray = request.toByteArray()

    override fun toByteBuffer(): ByteBuffer = request.toByteBuffer()
}
