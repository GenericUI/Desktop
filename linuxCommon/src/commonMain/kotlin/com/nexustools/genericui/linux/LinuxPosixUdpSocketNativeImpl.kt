package com.nexustools.genericui.linux

import com.nexustools.genericui.nativeimpl.net.DefaultUdpSocketNativeImpl

/**
 * Linux POSIX BSD socket (`SOCK_DGRAM` / `SO_REUSEADDR` / `SO_BROADCAST` / `IP_ADD_MEMBERSHIP` /
 * `IP_MULTICAST_IF`) base implementation of [com.nexustools.genericui.nativeimpl.net.UdpSocketNativeImpl]
 * shared by `:desktop:gtk` and `:desktop:qt`.
 */
expect open class LinuxPosixUdpSocketNativeImpl() : DefaultUdpSocketNativeImpl
