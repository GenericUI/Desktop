@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.linux

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.cstr
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned

internal object LinuxOpenSslBindings {
    const val SSL_CTRL_MODE = 33
    const val SSL_CTRL_SET_TLSEXT_HOSTNAME = 55
    const val TLSEXT_NAMETYPE_HOST_NAME = 0L

    const val SSL_MODE_ENABLE_PARTIAL_WRITE = 0x1L
    const val SSL_MODE_ACCEPT_MOVING_WRITE_BUFFER = 0x2L
    const val SSL_MODE_AUTO_RETRY = 0x4L

    const val SSL_VERIFY_NONE = 0
    const val SSL_VERIFY_PEER = 1

    const val SSL_ERROR_NONE = 0
    const val SSL_ERROR_SSL = 1
    const val SSL_ERROR_WANT_READ = 2
    const val SSL_ERROR_WANT_WRITE = 3
    const val SSL_ERROR_SYSCALL = 5
    const val SSL_ERROR_ZERO_RETURN = 6

    private val lock = LinuxReentrantLock()
    private var loaded = false
    private var available = false

    private var fnTlsClientMethod: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnSslCtxNew: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnSslCtxSetDefaultVerifyPaths: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnSslCtxSetVerify: CPointer<CFunction<(COpaquePointer?, Int, COpaquePointer?) -> Unit>>? = null
    private var fnSslCtxCtrl: CPointer<CFunction<(COpaquePointer?, Int, Long, COpaquePointer?) -> Long>>? = null
    private var fnSslCtxFree: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnSslNew: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnSslSetFd: CPointer<CFunction<(COpaquePointer?, Int) -> Int>>? = null
    private var fnSslCtrl: CPointer<CFunction<(COpaquePointer?, Int, Long, COpaquePointer?) -> Long>>? = null
    private var fnSslSet1Host: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?) -> Int>>? = null
    private var fnSslConnect: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnSslRead: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, Int) -> Int>>? = null
    private var fnSslWrite: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, Int) -> Int>>? = null
    private var fnSslGetError: CPointer<CFunction<(COpaquePointer?, Int) -> Int>>? = null
    private var fnSslShutdown: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnSslFree: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnErrGetError: CPointer<CFunction<() -> ULong>>? = null
    private var fnErrErrorStringN: CPointer<CFunction<(ULong, CPointer<ByteVar>?, ULong) -> Unit>>? = null

    fun ensureLoaded(): Boolean = lock.withLock {
        if (loaded) return available
        loaded = true
        LinuxDynamicLibraryLoader.ensureNoSigPipe()

        val crypto = LinuxDynamicLibraryLoader.openFirst("libcrypto.so.3", "libcrypto.so", global = true)
        val ssl = LinuxDynamicLibraryLoader.openFirst("libssl.so.3", "libssl.so", global = true)
        if (crypto == null || ssl == null) {
            available = false
            return false
        }

        fnTlsClientMethod = LinuxDynamicLibraryLoader.symbol(ssl, "TLS_client_method")?.reinterpret()
        fnSslCtxNew = LinuxDynamicLibraryLoader.symbol(ssl, "SSL_CTX_new")?.reinterpret()
        fnSslCtxSetDefaultVerifyPaths = LinuxDynamicLibraryLoader.symbol(ssl, "SSL_CTX_set_default_verify_paths")?.reinterpret()
        fnSslCtxSetVerify = LinuxDynamicLibraryLoader.symbol(ssl, "SSL_CTX_set_verify")?.reinterpret()
        fnSslCtxCtrl = LinuxDynamicLibraryLoader.symbol(ssl, "SSL_CTX_ctrl")?.reinterpret()
        fnSslCtxFree = LinuxDynamicLibraryLoader.symbol(ssl, "SSL_CTX_free")?.reinterpret()
        fnSslNew = LinuxDynamicLibraryLoader.symbol(ssl, "SSL_new")?.reinterpret()
        fnSslSetFd = LinuxDynamicLibraryLoader.symbol(ssl, "SSL_set_fd")?.reinterpret()
        fnSslCtrl = LinuxDynamicLibraryLoader.symbol(ssl, "SSL_ctrl")?.reinterpret()
        fnSslSet1Host = LinuxDynamicLibraryLoader.symbol(ssl, "SSL_set1_host")?.reinterpret()
        fnSslConnect = LinuxDynamicLibraryLoader.symbol(ssl, "SSL_connect")?.reinterpret()
        fnSslRead = LinuxDynamicLibraryLoader.symbol(ssl, "SSL_read")?.reinterpret()
        fnSslWrite = LinuxDynamicLibraryLoader.symbol(ssl, "SSL_write")?.reinterpret()
        fnSslGetError = LinuxDynamicLibraryLoader.symbol(ssl, "SSL_get_error")?.reinterpret()
        fnSslShutdown = LinuxDynamicLibraryLoader.symbol(ssl, "SSL_shutdown")?.reinterpret()
        fnSslFree = LinuxDynamicLibraryLoader.symbol(ssl, "SSL_free")?.reinterpret()
        fnErrGetError = LinuxDynamicLibraryLoader.symbol(crypto, "ERR_get_error")?.reinterpret()
        fnErrErrorStringN = LinuxDynamicLibraryLoader.symbol(crypto, "ERR_error_string_n")?.reinterpret()

        available = fnTlsClientMethod != null &&
            fnSslCtxNew != null &&
            fnSslCtxFree != null &&
            fnSslNew != null &&
            fnSslSetFd != null &&
            fnSslConnect != null &&
            fnSslRead != null &&
            fnSslWrite != null &&
            fnSslGetError != null &&
            fnSslShutdown != null &&
            fnSslFree != null
        available
    }

    fun tlsClientMethod(): COpaquePointer? = fnTlsClientMethod?.invoke()
    fun sslCtxNew(method: COpaquePointer?): COpaquePointer? = method?.let { fnSslCtxNew?.invoke(it) }
    fun sslCtxSetDefaultVerifyPaths(ctx: COpaquePointer?): Int = ctx?.let { fnSslCtxSetDefaultVerifyPaths?.invoke(it) } ?: 0
    fun sslCtxSetVerify(ctx: COpaquePointer?, mode: Int) {
        if (ctx != null) fnSslCtxSetVerify?.invoke(ctx, mode, null)
    }

    fun sslCtxSetMode(ctx: COpaquePointer?, mode: Long): Long {
        return if (ctx != null) fnSslCtxCtrl?.invoke(ctx, SSL_CTRL_MODE, mode, null) ?: 0L else 0L
    }

    fun sslCtxFree(ctx: COpaquePointer?) {
        if (ctx != null) fnSslCtxFree?.invoke(ctx)
    }

    fun sslNew(ctx: COpaquePointer?): COpaquePointer? = ctx?.let { fnSslNew?.invoke(it) }
    fun sslSetFd(ssl: COpaquePointer?, fd: Int): Int = if (ssl != null) fnSslSetFd?.invoke(ssl, fd) ?: 0 else 0

    fun sslSetTlsExtHostName(ssl: COpaquePointer?, host: String): Boolean = memScoped {
        if (ssl == null) return@memScoped false
        val fn = fnSslCtrl ?: return@memScoped false
        fn.invoke(ssl, SSL_CTRL_SET_TLSEXT_HOSTNAME, TLSEXT_NAMETYPE_HOST_NAME, host.cstr.ptr) != 0L
    }

    fun sslSet1Host(ssl: COpaquePointer?, host: String): Boolean = memScoped {
        if (ssl == null) return@memScoped false
        val fn = fnSslSet1Host ?: return@memScoped true
        fn.invoke(ssl, host.cstr.ptr) == 1
    }

    fun sslConnect(ssl: COpaquePointer?): Int = if (ssl != null) fnSslConnect?.invoke(ssl) ?: -1 else -1
    fun sslRead(ssl: COpaquePointer?, buf: COpaquePointer?, num: Int): Int =
        if (ssl != null && buf != null) fnSslRead?.invoke(ssl, buf, num) ?: -1 else -1

    fun sslWrite(ssl: COpaquePointer?, buf: COpaquePointer?, num: Int): Int =
        if (ssl != null && buf != null) fnSslWrite?.invoke(ssl, buf, num) ?: -1 else -1

    fun sslGetError(ssl: COpaquePointer?, ret: Int): Int =
        if (ssl != null) fnSslGetError?.invoke(ssl, ret) ?: SSL_ERROR_SSL else SSL_ERROR_SSL

    fun sslShutdown(ssl: COpaquePointer?): Int = if (ssl != null) fnSslShutdown?.invoke(ssl) ?: 0 else 0
    fun sslFree(ssl: COpaquePointer?) {
        if (ssl != null) fnSslFree?.invoke(ssl)
    }

    fun drainLastErrorMessage(fallback: String): String {
        val getErr = fnErrGetError ?: return fallback
        var lastCode = 0uL
        while (true) {
            val next = getErr.invoke()
            if (next == 0uL) break
            lastCode = next
        }
        if (lastCode == 0uL) return fallback
        val fmt = fnErrErrorStringN ?: return "$fallback (OpenSSL err=$lastCode)"
        val buf = ByteArray(256)
        buf.usePinned { pinned ->
            fmt.invoke(lastCode, pinned.addressOf(0), buf.size.toULong())
        }
        val text = buf.usePinned { it.addressOf(0).toKString() }
        return text.ifBlank { "$fallback (OpenSSL err=$lastCode)" }
    }
}
