@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import platform.AppKit.NSButton
import platform.AppKit.NSControl
import platform.AppKit.NSEvent
import platform.AppKit.NSTextField
import platform.AppKit.NSView
import platform.Foundation.NSMakeRect

internal class MacosTitleBarView(
    private val owner: DesktopBaseContainerNativeImpl<*>?,
    frameRect: kotlinx.cinterop.CValue<platform.Foundation.NSRect> = NSMakeRect(0.0, 0.0, 0.0, 0.0)
) : NSView(frameRect) {

    override fun isFlipped(): Boolean = true

    override fun acceptsFirstMouse(event: NSEvent?): Boolean = true

    override fun hitTest(point: kotlinx.cinterop.CValue<platform.Foundation.NSPoint>): NSView? {
        if (isHidden() || alphaValue <= 0.004) return null
        val hit = super.hitTest(point)
        if (owner?.nativeWidget?.isSelfPointerEventsNone() == true && hit == this) {
            return null
        }
        return hit
    }

    override fun mouseDown(event: NSEvent) {
        val localPt = convertPoint(event.locationInWindow, fromView = null)
        val hit = hitTest(superview?.convertPoint(localPt, fromView = this) ?: localPt)
        if (hit != null && hit != this && isInteractiveControlView(hit)) {
            super.mouseDown(event)
            return
        }
        if (event.clickCount >= 2L) {
            (window as? MacosHostWindow)?.handleTitleBarDoubleClick() ?: window?.performZoom(null)
            return
        }
        val win = window
        if (win != null) {
            MacosNativeWindowHost.performWindowDragWithCursorLock(win, event)
        } else {
            super.mouseDown(event)
        }
    }

    private fun isInteractiveControlView(view: NSView): Boolean {
        var cur: NSView? = view
        while (cur != null && cur != this) {
            if (cur is NSButton || cur is NSControl) {
                if (cur is NSTextField && !cur.isEditable()) {
                    cur = cur.superview
                    continue
                }
                return true
            }
            cur = cur.superview
        }
        return false
    }
}
