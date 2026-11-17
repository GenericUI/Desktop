package com.nexustools.genericui.linux

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import platform.posix.PTHREAD_MUTEX_RECURSIVE
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock
import platform.posix.pthread_mutexattr_destroy
import platform.posix.pthread_mutexattr_init
import platform.posix.pthread_mutexattr_settype
import platform.posix.pthread_mutexattr_t

@OptIn(ExperimentalForeignApi::class)
internal class LinuxReentrantLock {
    private val attr = nativeHeap.alloc<pthread_mutexattr_t>()
    private val mutex = nativeHeap.alloc<pthread_mutex_t>()

    init {
        pthread_mutexattr_init(attr.ptr)
        pthread_mutexattr_settype(attr.ptr, PTHREAD_MUTEX_RECURSIVE.convert())
        pthread_mutex_init(mutex.ptr, attr.ptr)
        pthread_mutexattr_destroy(attr.ptr)
    }

    inline fun <T> withLock(block: () -> T): T {
        pthread_mutex_lock(mutex.ptr)
        return try {
            block()
        } finally {
            pthread_mutex_unlock(mutex.ptr)
        }
    }
}
