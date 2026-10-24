package com.nexustools.genericui.desktop.core

import com.nexustools.genericui.geometry.EdgeInsets
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size

/**
 * Base desktop native widget handle model encapsulating a platform-specific native pointer/handle
 * (`HWND` on Win32, `NSView*`/`NSWindow*` on macOS AppKit, `GtkWidget*` on GTK4, `QWidget*` on Qt6),
 * native toolkit widget class name, property map, and child widget hierarchy.
 */
open class DesktopNativeWidget(
    val backendToolkit: String,
    val nativeClassName: String,
    val nativePointer: Long = nextPointerId()
) {
    var visible: Boolean = true
    var enabled: Boolean = true
    var opacity: Float = 1.0f
    var tooltipText: String? = null
    var preferredSize: Size? = null
    var bounds: Rect = Rect(0f, 0f, 0f, 0f)
    var padding: EdgeInsets = EdgeInsets.ZERO
    var margin: EdgeInsets = EdgeInsets.ZERO
    var disposed: Boolean = false

    val attributes: MutableMap<String, Any?> = linkedMapOf()
    val children: MutableList<DesktopNativeWidget> = mutableListOf()

    fun addChildWidget(child: DesktopNativeWidget, index: Int = -1) {
        children.remove(child)
        if (index < 0 || index >= children.size) {
            children.add(child)
        } else {
            children.add(index, child)
        }
    }

    fun removeChildWidget(child: DesktopNativeWidget) {
        children.remove(child)
    }

    fun clearChildWidgets() {
        children.clear()
    }

    companion object {
        private var pointerCounter: Long = 0x1000_0000L

        fun nextPointerId(): Long {
            pointerCounter += 0x10L
            return pointerCounter
        }
    }
}
