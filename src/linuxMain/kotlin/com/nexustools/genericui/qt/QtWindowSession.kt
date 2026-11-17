@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.qt

import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.dialog.DesktopDialogWindowNativeImpl
import com.nexustools.genericui.qt.render.QtHitRegion
import com.nexustools.genericui.qt.render.QtScrollRegion
import kotlinx.cinterop.COpaquePointer

internal class QtWindowSession(
    val windowKey: Long,
    val qWidget: COpaquePointer,
    val qLabel: COpaquePointer,
    val windowImpl: QtWindowNativeImpl? = null,
    val dialogImpl: DesktopDialogWindowNativeImpl<*>? = null,
    val ownerWindowKey: Long? = null
) {
    var argbBuffer: IntArray = IntArray(0)
    var bufferWidth: Int = 0
    var bufferHeight: Int = 0
    var needsRepaint: Boolean = true

    var hitRegions: List<QtHitRegion> = emptyList()
    var scrollRegions: List<QtScrollRegion> = emptyList()

    var hoveredRegion: QtHitRegion? = null
    var hoveredWidget: DesktopNativeWidget? = null
    var pressedRegion: QtHitRegion? = null
    var pressedWidget: DesktopNativeWidget? = null
    var focusedWidget: DesktopNativeWidget? = null

    var lastMouseX: Float = -1f
    var lastMouseY: Float = -1f
    var wasMouseDown: Boolean = false
    var paintedFrameCount: Int = 0
    var lastPaintedWidth: Int = 0
    var lastPaintedHeight: Int = 0
    var lastClampUnclampedInput: Pair<Int, Int>? = null
    var lastClampTargetSize: Pair<Int, Int>? = null
    var lastAppliedCursorShape: Int = Qt6DynamicBindings.QT_CURSOR_ARROW
    var lastAppliedAlwaysOnTop: Boolean = false
    var closed: Boolean = false
}
