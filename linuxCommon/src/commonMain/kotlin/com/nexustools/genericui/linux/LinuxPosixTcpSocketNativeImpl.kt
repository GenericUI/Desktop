package com.nexustools.genericui.linux

import com.nexustools.genericui.nativeimpl.net.DefaultTcpSocketNativeImpl

/**
 * Linux POSIX BSD socket (`SOCK_STREAM` / `connect` / `send` / `recv`) base implementation of
 * [com.nexustools.genericui.nativeimpl.net.TcpSocketNativeImpl] shared by `:desktop:gtk` and `:desktop:qt`.
 */
expect open class LinuxPosixTcpSocketNativeImpl() : DefaultTcpSocketNativeImpl
