@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import kotlinx.cinterop.COpaquePointer

/**
 * Retained Qt6 `QWidget` window / dialog / dock-window session tracked by [KdeNativeWindowHost].
 */
internal class KdeWindowSession(
    val key: Long,
    val qWidget: COpaquePointer,
    val windowImpl: KdeWindowNativeImpl? = null,
    val dialogImpl: KdeDialogWindowNativeImpl? = null,
    val dockWindowImpl: KdeDockWindowNativeImpl? = null
) {
    var rootHandle: KdeWidgetHandle? = null
    var lastWidth: Int = 1280
    var lastHeight: Int = 800
    var x11WinId: ULong = 0uL
    var closed: Boolean = false
    var shown: Boolean = false
}
