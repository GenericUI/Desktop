@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import kotlinx.cinterop.CValue
import platform.AppKit.NSCursor
import platform.AppKit.NSEvent
import platform.AppKit.NSTrackingActiveAlways
import platform.AppKit.NSTrackingArea
import platform.AppKit.NSTrackingCursorUpdate
import platform.AppKit.NSTrackingInVisibleRect
import platform.AppKit.NSTrackingMouseEnteredAndExited
import platform.AppKit.NSTrackingMouseMoved
import platform.AppKit.NSView
import platform.AppKit.NSViewHeightSizable
import platform.AppKit.NSViewWidthSizable
import platform.Foundation.NSRect

/**
 * Custom flipped AppKit `NSView` surface that renders GenericUI's CoreGraphics tree and routes
 * mouse, trackpad scroll, cursor rect, and keyboard events to [MacosNativeWindowHost].
 */
internal class MacosCanvasView(
    val windowKey: Long,
    frameRect: CValue<NSRect>
) : NSView(frame = frameRect) {

    private var activeTrackingArea: NSTrackingArea? = null
    var activeCursor: NSCursor = NSCursor.arrowCursor

    init {
        autoresizingMask = NSViewWidthSizable or NSViewHeightSizable
    }

    override fun isFlipped(): Boolean = true

    override fun isOpaque(): Boolean = true

    override fun acceptsFirstResponder(): Boolean = true

    override fun acceptsFirstMouse(event: NSEvent?): Boolean = true

    override fun updateTrackingAreas() {
        super.updateTrackingAreas()
        activeTrackingArea?.let { removeTrackingArea(it) }
        val options = NSTrackingMouseMoved or
            NSTrackingMouseEnteredAndExited or
            NSTrackingCursorUpdate or
            NSTrackingActiveAlways or
            NSTrackingInVisibleRect
        val area = NSTrackingArea(
            rect = bounds,
            options = options,
            owner = this,
            userInfo = null
        )
        addTrackingArea(area)
        activeTrackingArea = area
    }

    override fun resetCursorRects() {
        super.resetCursorRects()
        discardCursorRects()
        addCursorRect(bounds, cursor = activeCursor)
    }

    override fun cursorUpdate(event: NSEvent) {
        activeCursor.set()
    }

    override fun drawRect(dirtyRect: CValue<NSRect>) {
        MacosNativeWindowHost.drawWindow(windowKey, dirtyRect)
    }

    override fun mouseDown(event: NSEvent) {
        window?.makeFirstResponder(this)
        MacosNativeWindowHost.onMouseDown(windowKey, event, button = 0)
    }

    override fun rightMouseDown(event: NSEvent) {
        window?.makeFirstResponder(this)
        MacosNativeWindowHost.onMouseDown(windowKey, event, button = 2)
    }

    override fun otherMouseDown(event: NSEvent) {
        MacosNativeWindowHost.onMouseDown(windowKey, event, button = 1)
    }

    override fun mouseDragged(event: NSEvent) {
        MacosNativeWindowHost.onMouseDragged(windowKey, event, button = 0)
    }

    override fun rightMouseDragged(event: NSEvent) {
        MacosNativeWindowHost.onMouseDragged(windowKey, event, button = 2)
    }

    override fun otherMouseDragged(event: NSEvent) {
        MacosNativeWindowHost.onMouseDragged(windowKey, event, button = 1)
    }

    override fun mouseUp(event: NSEvent) {
        MacosNativeWindowHost.onMouseUp(windowKey, event, button = 0)
    }

    override fun rightMouseUp(event: NSEvent) {
        MacosNativeWindowHost.onMouseUp(windowKey, event, button = 2)
    }

    override fun otherMouseUp(event: NSEvent) {
        MacosNativeWindowHost.onMouseUp(windowKey, event, button = 1)
    }

    override fun mouseMoved(event: NSEvent) {
        MacosNativeWindowHost.onMouseMoved(windowKey, event)
    }

    override fun mouseEntered(event: NSEvent) {
        MacosNativeWindowHost.onMouseEntered(windowKey, event)
    }

    override fun mouseExited(event: NSEvent) {
        MacosNativeWindowHost.onMouseExited(windowKey)
    }

    override fun scrollWheel(event: NSEvent) {
        MacosNativeWindowHost.onScrollWheel(windowKey, event)
    }

    override fun keyDown(event: NSEvent) {
        MacosNativeWindowHost.onKeyDown(windowKey, event)
    }

    override fun keyUp(event: NSEvent) {
        MacosNativeWindowHost.onKeyUp(windowKey, event)
    }

    override fun performKeyEquivalent(event: NSEvent): Boolean {
        if (MacosNativeWindowHost.onPerformKeyEquivalent(windowKey, event)) {
            return true
        }
        return super.performKeyEquivalent(event)
    }
}
