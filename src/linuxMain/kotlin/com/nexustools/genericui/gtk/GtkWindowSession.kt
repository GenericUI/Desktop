@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk

import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.dialog.DesktopDialogWindowNativeImpl
import com.nexustools.genericui.gtk.render.GtkHitRegion
import com.nexustools.genericui.gtk.render.GtkScrollRegion
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlin.time.TimeMark

internal class GtkWindowSession(
    val windowKey: Long,
    val gtkWindow: COpaquePointer,
    val drawingArea: COpaquePointer,
    var headerBar: COpaquePointer? = null,
    val windowImpl: GtkWindowNativeImpl? = null,
    val dialogImpl: DesktopDialogWindowNativeImpl<*>? = null,
    val ownerWindowKey: Long? = null
) {
    var sessionRef: StableRef<GtkWindowSession>? = null
    var hitRegions: List<GtkHitRegion> = emptyList()
    var scrollRegions: List<GtkScrollRegion> = emptyList()

    var hoveredRegion: GtkHitRegion? = null
    var hoveredWidget: DesktopNativeWidget? = null
    var pressedRegion: GtkHitRegion? = null
    var pressedWidget: DesktopNativeWidget? = null
    var focusedWidget: DesktopNativeWidget? = null

    var lastMouseX: Float = -1f
    var lastMouseY: Float = -1f
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
    var closed: Boolean = false
}
