@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk

import com.nexustools.genericui.desktop.dialog.DesktopDialogWindowNativeImpl
import com.nexustools.genericui.gtk.titlebar.GtkCustomTitleBarWidget
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlin.time.TimeMark

/**
 * Retained GTK4 window/dialog session state holding the live `GtkWindow`, root `GtkBox`,
 * `GtkCustomTitleBarWidget`, and content layout container.
 */
internal class GtkWindowSession(
    val windowKey: Long,
    val gtkWindow: COpaquePointer,
    val rootVBox: COpaquePointer,
    val rootContentHost: COpaquePointer,
    val contentLayoutBox: COpaquePointer,
    var titleBarWidget: GtkCustomTitleBarWidget? = null,
    var surfaceBackgroundDa: COpaquePointer? = null,
    var mountedMenuBarPtr: COpaquePointer? = null,
    var mountedNavBarPtr: COpaquePointer? = null,
    var mountedBottomNavPtr: COpaquePointer? = null,
    val windowImpl: GtkWindowNativeImpl? = null,
    val dialogImpl: DesktopDialogWindowNativeImpl<*>? = null,
    val ownerWindowKey: Long? = null
) {
    var sessionRef: StableRef<GtkWindowSession>? = null
    var usesCustomLinuxTitleBar: Boolean = false
    var paintedFrameCount: Int = 0
    var lastPaintedWidth: Int = 0
    var lastPaintedHeight: Int = 0
    var lastClampUnclampedInput: Pair<Int, Int>? = null
    var lastClampTargetSize: Pair<Int, Int>? = null
    var lastAppliedCursorKey: String = ""
    var pendingTargetFullscreen: Boolean? = null
    var fullscreenTransitionMark: TimeMark? = null
    var lastReportedX: Int = Int.MIN_VALUE
    var lastReportedY: Int = Int.MIN_VALUE
    var suppressMoveUntilMark: TimeMark? = null
    var lastMouseX: Float = -1f
    var lastMouseY: Float = -1f
    var closed: Boolean = false
}
