@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.fd_set
import platform.posix.memcpy
import platform.posix.memset
import platform.posix.posix_FD_ZERO
import platform.posix.recv
import platform.posix.send
import platform.posix.timeval
import platform.windows.GetModuleHandleW
import platform.windows.GetProcAddress
import platform.windows.LoadLibraryW

/**
 * Windows SSPI / Schannel (`secur32.dll`) TLS 1.2/1.3 client session wrapper for WinSock2 TCP sockets.
 * Supports self-signed LAN device certificates (such as Google Cast V2 on port 8009) via
 * `SCH_CRED_MANUAL_CRED_VALIDATION` and `ISC_REQ_MANUAL_CRED_VALIDATION`.
 */
class Win32SchannelTlsClient(
    private val sock: ULong,
    private val host: String
) {
    private var credHandlePtr: CPointer<ByteVar>? = null
    private var ctxtHandlePtr: CPointer<ByteVar>? = null
    private var hasCredHandle: Boolean = false
    private var hasCtxtHandle: Boolean = false

    private var cbHeader: Int = 5
    private var cbTrailer: Int = 64
    private var cbMaximumMessage: Int = 16384

    private var encryptedBuffer = ByteArray(0)
    private var decryptedQueue = ByteArray(0)

    fun performHandshake(timeoutMs: Long): Boolean = memScoped {
        ensureSspiLoaded()
        val acquireCredFn = acquireCredentialsHandleAFn ?: return@memScoped false
        val initCtxFn = initializeSecurityContextAFn ?: return@memScoped false
        val queryAttrFn = queryContextAttributesAFn ?: return@memScoped false
        val freeBufFn = freeContextBufferFn

        val credMem = nativeHeap.allocArray<ByteVar>(16)
        val ctxtMem = nativeHeap.allocArray<ByteVar>(16)
        memset(credMem, 0, 16u)
        memset(ctxtMem, 0, 16u)
        credHandlePtr = credMem
        ctxtHandlePtr = ctxtMem

        val schannelCred = allocArray<ByteVar>(80)
        memset(schannelCred, 0, 80u)
        writeUIntAt(schannelCred, 0, SCHANNEL_CRED_VERSION)
        writeUIntAt(schannelCred, 72, SCH_CRED_NO_DEFAULT_CREDS or SCH_CRED_MANUAL_CRED_VALIDATION)

        val expiry = allocArray<ByteVar>(16)
        val packageName = UNISP_NAME_A.cstr.ptr
        val acqStatus = acquireCredFn(
            null,
            packageName,
            SECPKG_CRED_OUTBOUND,
            null,
            schannelCred,
            null,
            null,
            credMem,
            expiry
        )
        if (acqStatus != SEC_E_OK) {
            dispose()
            return@memScoped false
        }
        hasCredHandle = true

        val reqFlags = ISC_REQ_SEQUENCE_DETECT or
            ISC_REQ_REPLAY_DETECT or
            ISC_REQ_CONFIDENTIALITY or
            ISC_REQ_EXTENDED_ERROR or
            ISC_REQ_ALLOCATE_MEMORY or
            ISC_REQ_STREAM or
            ISC_REQ_MANUAL_CRED_VALIDATION

        val targetName = host.cstr.ptr
        val contextAttr = alloc<UIntVar>().apply { value = 0u }

        // Initial ClientHello generation
        val outBuffer = allocArray<ByteVar>(16)
        memset(outBuffer, 0, 16u)
        writeUIntAt(outBuffer, 0, 0u)
        writeUIntAt(outBuffer, 4, SECBUFFER_TOKEN)
        writePtrAt(outBuffer, 8, null)

        val outDesc = allocArray<ByteVar>(16)
        writeUIntAt(outDesc, 0, SECBUFFER_VERSION)
        writeUIntAt(outDesc, 4, 1u)
        writePtrAt(outDesc, 8, outBuffer)

        var status = initCtxFn(
            credMem,
            null,
            targetName,
            reqFlags,
            0u,
            0u,
            null,
            0u,
            ctxtMem,
            outDesc,
            contextAttr.ptr,
            expiry
        )
        hasCtxtHandle = true

        if (status != SEC_E_OK && status != SEC_I_CONTINUE_NEEDED) {
            dispose()
            return@memScoped false
        }

        val initialTokenLen = readUIntAt(outBuffer, 0).toInt()
        val initialTokenPtr = readPtrAt(outBuffer, 8)
        if (initialTokenLen > 0 && initialTokenPtr != null) {
            val sent = sendRawPtr(initialTokenPtr, initialTokenLen, timeoutMs)
            freeBufFn?.invoke(initialTokenPtr)
            if (!sent) {
                dispose()
                return@memScoped false
            }
        }

        var handshakeBuf = ByteArray(65536)
        var handshakeLen = 0

        while (status == SEC_I_CONTINUE_NEEDED || status == SEC_E_INCOMPLETE_MESSAGE) {
            if (handshakeLen == 0 || status == SEC_E_INCOMPLETE_MESSAGE) {
                val readBytes = recvRawChunk(timeoutMs.coerceIn(1000L, 15_000L))
                if (readBytes == null || readBytes.isEmpty()) {
                    dispose()
                    return@memScoped false
                }
                if (handshakeLen + readBytes.size > handshakeBuf.size) {
                    handshakeBuf = handshakeBuf.copyOf((handshakeLen + readBytes.size) * 2)
                }
                readBytes.copyInto(handshakeBuf, destinationOffset = handshakeLen)
                handshakeLen += readBytes.size
            }

            val inBuffers = allocArray<ByteVar>(32)
            memset(inBuffers, 0, 32u)
            val inDesc = allocArray<ByteVar>(16)
            writeUIntAt(inDesc, 0, SECBUFFER_VERSION)
            writeUIntAt(inDesc, 4, 2u)
            writePtrAt(inDesc, 8, inBuffers)

            val stepOutBuf = allocArray<ByteVar>(16)
            memset(stepOutBuf, 0, 16u)
            writeUIntAt(stepOutBuf, 0, 0u)
            writeUIntAt(stepOutBuf, 4, SECBUFFER_TOKEN)
            writePtrAt(stepOutBuf, 8, null)

            val stepOutDesc = allocArray<ByteVar>(16)
            writeUIntAt(stepOutDesc, 0, SECBUFFER_VERSION)
            writeUIntAt(stepOutDesc, 4, 1u)
            writePtrAt(stepOutDesc, 8, stepOutBuf)

            status = handshakeBuf.usePinned { pinnedIn ->
                writeUIntAt(inBuffers, 0, handshakeLen.toUInt())
                writeUIntAt(inBuffers, 4, SECBUFFER_TOKEN)
                writePtrAt(inBuffers, 8, pinnedIn.addressOf(0))

                writeUIntAt(inBuffers, 16, 0u)
                writeUIntAt(inBuffers, 20, SECBUFFER_EMPTY)
                writePtrAt(inBuffers, 24, null)

                initCtxFn(
                    credMem,
                    ctxtMem,
                    targetName,
                    reqFlags,
                    0u,
                    0u,
                    inDesc,
                    0u,
                    ctxtMem,
                    stepOutDesc,
                    contextAttr.ptr,
                    expiry
                )
            }

            if (status == SEC_E_INCOMPLETE_MESSAGE) {
                continue
            }

            val stepOutLen = readUIntAt(stepOutBuf, 0).toInt()
            val stepOutPtr = readPtrAt(stepOutBuf, 8)
            if (stepOutLen > 0 && stepOutPtr != null) {
                val sentOk = sendRawPtr(stepOutPtr, stepOutLen, timeoutMs)
                freeBufFn?.invoke(stepOutPtr)
                if (!sentOk) {
                    dispose()
                    return@memScoped false
                }
            }

            if (status != SEC_E_OK && status != SEC_I_CONTINUE_NEEDED) {
                dispose()
                return@memScoped false
            }

            val secondBufType = readUIntAt(inBuffers, 20)
            val secondBufLen = readUIntAt(inBuffers, 16).toInt()
            if (secondBufType == SECBUFFER_EXTRA && secondBufLen in 1..handshakeLen) {
                handshakeBuf.copyInto(
                    handshakeBuf,
                    destinationOffset = 0,
                    startIndex = handshakeLen - secondBufLen,
                    endIndex = handshakeLen
                )
                handshakeLen = secondBufLen
            } else {
                handshakeLen = 0
            }
        }

        if (handshakeLen > 0) {
            encryptedBuffer = handshakeBuf.copyOf(handshakeLen)
        }

        val sizesMem = allocArray<ByteVar>(20)
        memset(sizesMem, 0, 20u)
        if (queryAttrFn(ctxtMem, SECPKG_ATTR_STREAM_SIZES, sizesMem) == SEC_E_OK) {
            cbHeader = readUIntAt(sizesMem, 0).toInt().coerceAtLeast(1)
            cbTrailer = readUIntAt(sizesMem, 4).toInt().coerceAtLeast(1)
            cbMaximumMessage = readUIntAt(sizesMem, 8).toInt().coerceIn(512, 65536)
        }
        true
    }

    fun encryptAndSend(plainBytes: ByteArray, timeoutMs: Long = 10_000L): Boolean {
        val ctxt = ctxtHandlePtr ?: return false
        val encryptFn = encryptMessageFn ?: return false
        if (plainBytes.isEmpty()) return true

        var offset = 0
        while (offset < plainBytes.size) {
            val chunkLen = minOf(plainBytes.size - offset, cbMaximumMessage)
            val ok = memScoped {
                val totalPacketCapacity = cbHeader + chunkLen + cbTrailer
                val packetMem = allocArray<ByteVar>(totalPacketCapacity)
                memset(packetMem, 0, totalPacketCapacity.toULong())

                plainBytes.usePinned { pinnedPlain ->
                    memcpy(packetMem + cbHeader, pinnedPlain.addressOf(offset), chunkLen.toULong())
                }

                val secBuffers = allocArray<ByteVar>(64)
                memset(secBuffers, 0, 64u)

                // Buffer 0: SECBUFFER_STREAM_HEADER
                writeUIntAt(secBuffers, 0, cbHeader.toUInt())
                writeUIntAt(secBuffers, 4, SECBUFFER_STREAM_HEADER)
                writePtrAt(secBuffers, 8, packetMem)

                // Buffer 1: SECBUFFER_DATA
                writeUIntAt(secBuffers, 16, chunkLen.toUInt())
                writeUIntAt(secBuffers, 20, SECBUFFER_DATA)
                writePtrAt(secBuffers, 24, packetMem + cbHeader)

                // Buffer 2: SECBUFFER_STREAM_TRAILER
                writeUIntAt(secBuffers, 32, cbTrailer.toUInt())
                writeUIntAt(secBuffers, 36, SECBUFFER_STREAM_TRAILER)
                writePtrAt(secBuffers, 40, packetMem + cbHeader + chunkLen)

                // Buffer 3: SECBUFFER_EMPTY
                writeUIntAt(secBuffers, 48, 0u)
                writeUIntAt(secBuffers, 52, SECBUFFER_EMPTY)
                writePtrAt(secBuffers, 56, null)

                val desc = allocArray<ByteVar>(16)
                writeUIntAt(desc, 0, SECBUFFER_VERSION)
                writeUIntAt(desc, 4, 4u)
                writePtrAt(desc, 8, secBuffers)

                val rc = encryptFn(ctxt, 0u, desc, 0u)
                if (rc != SEC_E_OK) return@memScoped false

                val actualHeader = readUIntAt(secBuffers, 0).toInt()
                val actualData = readUIntAt(secBuffers, 16).toInt()
                val actualTrailer = readUIntAt(secBuffers, 32).toInt()
                val totalEncrypted = actualHeader + actualData + actualTrailer
                sendRawPtr(packetMem, totalEncrypted, timeoutMs)
            }
            if (!ok) return false
            offset += chunkLen
        }
        return true
    }

    fun receiveAndDecrypt(waitMs: Long): ByteArray? {
        val ctxt = ctxtHandlePtr ?: return null
        val decryptFn = decryptMessageFn ?: return null

        val incoming = recvRawChunk(waitMs)
        if (incoming != null && incoming.isNotEmpty()) {
            val combined = ByteArray(encryptedBuffer.size + incoming.size)
            encryptedBuffer.copyInto(combined, 0)
            incoming.copyInto(combined, encryptedBuffer.size)
            encryptedBuffer = combined
        }

        if (encryptedBuffer.isNotEmpty()) {
            memScoped {
                while (encryptedBuffer.isNotEmpty()) {
                    val secBuffers = allocArray<ByteVar>(64)
                    memset(secBuffers, 0, 64u)
                    val desc = allocArray<ByteVar>(16)
                    writeUIntAt(desc, 0, SECBUFFER_VERSION)
                    writeUIntAt(desc, 4, 4u)
                    writePtrAt(desc, 8, secBuffers)

                    val workCopy = encryptedBuffer.copyOf()
                    var shouldBreak = false

                    workCopy.usePinned { pinnedWork ->
                        writeUIntAt(secBuffers, 0, workCopy.size.toUInt())
                        writeUIntAt(secBuffers, 4, SECBUFFER_DATA)
                        writePtrAt(secBuffers, 8, pinnedWork.addressOf(0))

                        for (b in 1..3) {
                            writeUIntAt(secBuffers, b * 16, 0u)
                            writeUIntAt(secBuffers, b * 16 + 4, SECBUFFER_EMPTY)
                            writePtrAt(secBuffers, b * 16 + 8, null)
                        }

                        val rc = decryptFn(ctxt, desc, 0u, null)
                        if (rc == SEC_E_INCOMPLETE_MESSAGE) {
                            shouldBreak = true
                            return@usePinned
                        }
                        if (rc != SEC_E_OK && rc != SEC_I_RENEGOTIATE && rc != SEC_I_CONTEXT_EXPIRED) {
                            encryptedBuffer = ByteArray(0)
                            shouldBreak = true
                            return@usePinned
                        }

                        var extraBytes: ByteArray? = null
                        for (b in 0..3) {
                            val bLen = readUIntAt(secBuffers, b * 16).toInt()
                            val bType = readUIntAt(secBuffers, b * 16 + 4)
                            val bPtr = readPtrAt(secBuffers, b * 16 + 8)
                            if (bType == SECBUFFER_DATA && bLen > 0 && bPtr != null) {
                                val plainChunk = ByteArray(bLen)
                                plainChunk.usePinned { pinnedPlain ->
                                    memcpy(pinnedPlain.addressOf(0), bPtr, bLen.toULong())
                                }
                                appendDecrypted(plainChunk)
                            } else if (bType == SECBUFFER_EXTRA && bLen > 0 && bPtr != null) {
                                val extra = ByteArray(bLen)
                                extra.usePinned { pinnedExtra ->
                                    memcpy(pinnedExtra.addressOf(0), bPtr, bLen.toULong())
                                }
                                extraBytes = extra
                            }
                        }
                        encryptedBuffer = extraBytes ?: ByteArray(0)
                        if (rc == SEC_I_CONTEXT_EXPIRED) {
                            shouldBreak = true
                        }
                    }

                    if (shouldBreak) break
                }
            }
        }

        if (decryptedQueue.isEmpty()) return null
        val out = decryptedQueue
        decryptedQueue = ByteArray(0)
        return out
    }

    fun dispose() {
        val ctxt = ctxtHandlePtr
        ctxtHandlePtr = null
        if (ctxt != null) {
            if (hasCtxtHandle) {
                hasCtxtHandle = false
                deleteSecurityContextFn?.invoke(ctxt)
            }
            nativeHeap.free(ctxt.rawValue)
        }

        val cred = credHandlePtr
        credHandlePtr = null
        if (cred != null) {
            if (hasCredHandle) {
                hasCredHandle = false
                freeCredentialsHandleFn?.invoke(cred)
            }
            nativeHeap.free(cred.rawValue)
        }
        encryptedBuffer = ByteArray(0)
        decryptedQueue = ByteArray(0)
    }

    private fun appendDecrypted(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        val next = ByteArray(decryptedQueue.size + chunk.size)
        decryptedQueue.copyInto(next, 0)
        chunk.copyInto(next, decryptedQueue.size)
        decryptedQueue = next
    }

    private fun sendRawPtr(ptr: CPointer<ByteVar>, length: Int, timeoutMs: Long): Boolean = memScoped {
        var sentTotal = 0
        while (sentTotal < length) {
            val writeSet = alloc<fd_set>()
            posix_FD_ZERO(writeSet.ptr)
            writeSet.fd_count = 1u
            writeSet.fd_array[0] = sock

            val tv = alloc<timeval>()
            val wait = timeoutMs.coerceIn(250L, 15_000L)
            tv.tv_sec = (wait / 1000L).toInt()
            tv.tv_usec = ((wait % 1000L) * 1000L).toInt()

            val sel = platform.windows.select(0, null, writeSet.ptr, null, tv.ptr)
            if (sel <= 0) return@memScoped false

            val n = send(sock, ptr + sentTotal, length - sentTotal, 0)
            if (n > 0) {
                sentTotal += n
            } else {
                val err = platform.posix.WSAGetLastError()
                if (err == 10035) continue
                return@memScoped false
            }
        }
        true
    }

    private fun recvRawChunk(waitMs: Long): ByteArray? = memScoped {
        val readSet = alloc<fd_set>()
        posix_FD_ZERO(readSet.ptr)
        readSet.fd_count = 1u
        readSet.fd_array[0] = sock

        val tv = alloc<timeval>()
        val clamped = waitMs.coerceIn(0L, 30_000L)
        tv.tv_sec = (clamped / 1000L).toInt()
        tv.tv_usec = ((clamped % 1000L) * 1000L).toInt()

        val ready = platform.windows.select(0, readSet.ptr, null, null, tv.ptr)
        if (ready <= 0) return@memScoped null

        val cap = 32768
        val buf = allocArray<ByteVar>(cap)
        val n = recv(sock, buf, cap, 0)
        if (n <= 0) return@memScoped null

        val out = ByteArray(n)
        out.usePinned { pinned ->
            memcpy(pinned.addressOf(0), buf, n.toULong())
        }
        out
    }

    private fun writeUIntAt(base: CPointer<ByteVar>, byteOffset: Int, value: UInt) {
        (base + byteOffset)!!.reinterpret<UIntVar>().pointed.value = value
    }

    private fun readUIntAt(base: CPointer<ByteVar>, byteOffset: Int): UInt =
        (base + byteOffset)!!.reinterpret<UIntVar>().pointed.value

    private fun writePtrAt(base: CPointer<ByteVar>, byteOffset: Int, ptr: COpaquePointer?) {
        (base + byteOffset)!!.reinterpret<CPointerVar<ByteVar>>().pointed.value = ptr?.reinterpret()
    }

    private fun readPtrAt(base: CPointer<ByteVar>, byteOffset: Int): CPointer<ByteVar>? =
        (base + byteOffset)!!.reinterpret<CPointerVar<ByteVar>>().pointed.value

    companion object {
        private const val UNISP_NAME_A = "Microsoft Unified Security Protocol Provider"
        private const val SCHANNEL_CRED_VERSION: UInt = 4u
        private const val SECPKG_CRED_OUTBOUND: UInt = 2u
        private const val SCH_CRED_MANUAL_CRED_VALIDATION: UInt = 0x00000008u
        private const val SCH_CRED_NO_DEFAULT_CREDS: UInt = 0x00000010u

        private const val ISC_REQ_REPLAY_DETECT: UInt = 0x00000004u
        private const val ISC_REQ_SEQUENCE_DETECT: UInt = 0x00000008u
        private const val ISC_REQ_CONFIDENTIALITY: UInt = 0x00000010u
        private const val ISC_REQ_ALLOCATE_MEMORY: UInt = 0x00000100u
        private const val ISC_REQ_EXTENDED_ERROR: UInt = 0x00004000u
        private const val ISC_REQ_STREAM: UInt = 0x00008000u
        private const val ISC_REQ_MANUAL_CRED_VALIDATION: UInt = 0x00080000u

        private const val SECBUFFER_VERSION: UInt = 0u
        private const val SECBUFFER_EMPTY: UInt = 0u
        private const val SECBUFFER_DATA: UInt = 1u
        private const val SECBUFFER_TOKEN: UInt = 2u
        private const val SECBUFFER_EXTRA: UInt = 5u
        private const val SECBUFFER_STREAM_TRAILER: UInt = 6u
        private const val SECBUFFER_STREAM_HEADER: UInt = 7u

        private const val SECPKG_ATTR_STREAM_SIZES: UInt = 4u

        private const val SEC_E_OK: Int = 0
        private const val SEC_I_CONTINUE_NEEDED: Int = 0x00090312
        private const val SEC_I_CONTEXT_EXPIRED: Int = 0x00090317
        private const val SEC_I_RENEGOTIATE: Int = 0x00090321
        private val SEC_E_INCOMPLETE_MESSAGE: Int = 0x80090318u.toInt()

        private var sspiResolved: Boolean = false

        private var acquireCredentialsHandleAFn: CPointer<
            CFunction<
                (
                    CPointer<ByteVar>?,
                    CPointer<ByteVar>?,
                    UInt,
                    COpaquePointer?,
                    COpaquePointer?,
                    COpaquePointer?,
                    COpaquePointer?,
                    COpaquePointer?,
                    COpaquePointer?
                ) -> Int
            >
        >? = null

        private var initializeSecurityContextAFn: CPointer<
            CFunction<
                (
                    COpaquePointer?,
                    COpaquePointer?,
                    CPointer<ByteVar>?,
                    UInt,
                    UInt,
                    UInt,
                    COpaquePointer?,
                    UInt,
                    COpaquePointer?,
                    COpaquePointer?,
                    CPointer<UIntVar>?,
                    COpaquePointer?
                ) -> Int
            >
        >? = null

        private var queryContextAttributesAFn: CPointer<
            CFunction<(COpaquePointer?, UInt, COpaquePointer?) -> Int>
        >? = null

        private var encryptMessageFn: CPointer<
            CFunction<(COpaquePointer?, UInt, COpaquePointer?, UInt) -> Int>
        >? = null

        private var decryptMessageFn: CPointer<
            CFunction<(COpaquePointer?, COpaquePointer?, UInt, CPointer<UIntVar>?) -> Int>
        >? = null

        private var freeContextBufferFn: CPointer<
            CFunction<(COpaquePointer?) -> Int>
        >? = null

        private var deleteSecurityContextFn: CPointer<
            CFunction<(COpaquePointer?) -> Int>
        >? = null

        private var freeCredentialsHandleFn: CPointer<
            CFunction<(COpaquePointer?) -> Int>
        >? = null

        private fun ensureSspiLoaded() {
            if (sspiResolved) return
            sspiResolved = true
            val mod = GetModuleHandleW("secur32.dll") ?: LoadLibraryW("secur32.dll") ?: return
            acquireCredentialsHandleAFn = GetProcAddress(mod, "AcquireCredentialsHandleA")?.reinterpret()
            initializeSecurityContextAFn = GetProcAddress(mod, "InitializeSecurityContextA")?.reinterpret()
            queryContextAttributesAFn = GetProcAddress(mod, "QueryContextAttributesA")?.reinterpret()
            encryptMessageFn = GetProcAddress(mod, "EncryptMessage")?.reinterpret()
            decryptMessageFn = GetProcAddress(mod, "DecryptMessage")?.reinterpret()
            freeContextBufferFn = GetProcAddress(mod, "FreeContextBuffer")?.reinterpret()
            deleteSecurityContextFn = GetProcAddress(mod, "DeleteSecurityContext")?.reinterpret()
            freeCredentialsHandleFn = GetProcAddress(mod, "FreeCredentialsHandle")?.reinterpret()
        }
    }
}
