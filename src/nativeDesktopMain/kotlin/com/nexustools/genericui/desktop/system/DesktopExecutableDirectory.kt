package com.nexustools.genericui.desktop.system

/**
 * Resolves the directory containing the currently running native executable binary, or `null` if unavailable.
 */
internal expect fun resolveNativeExecutableDirectory(): String?
