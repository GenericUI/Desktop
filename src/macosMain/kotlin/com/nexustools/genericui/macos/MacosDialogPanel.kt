@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.nexustools.genericui.macos

import kotlinx.cinterop.CValue
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.useContents
import platform.AppKit.NSBackingStoreType
import platform.AppKit.NSEvent
import platform.AppKit.NSEventTypeLeftMouseDown
import platform.AppKit.NSEventTypeLeftMouseUp
import platform.AppKit.NSPanel
import platform.AppKit.NSWindow
import platform.AppKit.NSWindowAbove
import platform.AppKit.NSWindowDidEndLiveResizeNotification
import platform.AppKit.NSWindowDidMoveNotification
import platform.AppKit.NSWindowStyleMask
import platform.AppKit.NSWindowWillMoveNotification
import platform.AppKit.NSWindowWillStartLiveResizeNotification
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSRect
import platform.Foundation.NSSelectorFromString
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSObject
import platform.darwin.dispatch_after
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time

internal fun isSameNsWindow(a: NSWindow?, b: NSWindow?): Boolean =
    a != null && b != null && (a === b || a == b || (a.windowNumber > 0L && a.windowNumber == b.windowNumber))

private class MacosDialogPanelObserver(
    private val onWillMove: () -> Unit,
    private val onDidMove: () -> Unit,
    private val onWillStartLiveResize: () -> Unit,
    private val onDidEndLiveResize: () -> Unit
) : NSObject() {

    val willMoveSelector = NSSelectorFromString("handlePanelWillMove:")
    val didMoveSelector = NSSelectorFromString("handlePanelDidMove:")
    val willStartLiveResizeSelector = NSSelectorFromString("handlePanelWillStartLiveResize:")
    val didEndLiveResizeSelector = NSSelectorFromString("handlePanelDidEndLiveResize:")

    @ObjCAction
    fun handlePanelWillMove(notification: NSNotification) {
        onWillMove()
    }

    @ObjCAction
    fun handlePanelDidMove(notification: NSNotification) {
        onDidMove()
    }

    @ObjCAction
    fun handlePanelWillStartLiveResize(notification: NSNotification) {
        onWillStartLiveResize()
    }

    @ObjCAction
    fun handlePanelDidEndLiveResize(notification: NSNotification) {
        onDidEndLiveResize()
    }
}

/**
 * Custom `NSPanel` host for macOS dialog windows that keeps `owner.addChildWindow(panel, NSWindowAbove)`
 * attached at rest for Z-order ownership, and temporarily detaches from `owner` only during an active
 * drag or live-resize to avoid macOS 27.2+ WindowServer `movementGroupID` stalls.
 */
internal class MacosDialogPanel(
    contentRect: CValue<NSRect>,
    styleMask: NSWindowStyleMask,
    backing: NSBackingStoreType,
    defer: Boolean
) : NSPanel(
    contentRect = contentRect,
    styleMask = styleMask,
    backing = backing,
    defer = defer
) {
    internal var attachedOwnerWindow: NSWindow? = null
        private set
    internal var isActivelyDraggingOrResizing: Boolean = false
        private set
    internal var isClosingPanel: Boolean = false
        private set
    private var settleGeneration: Long = 0L
    private var observersInstalled: Boolean = false

    private val observer = MacosDialogPanelObserver(
        onWillMove = { beginDragOrResize() },
        onDidMove = {
            if (isActivelyDraggingOrResizing && !inLiveResize && NSEvent.pressedMouseButtons == 0uL) {
                scheduleReattachAfterSettle()
            }
        },
        onWillStartLiveResize = { beginDragOrResize() },
        onDidEndLiveResize = { endDragOrResizeNow() }
    )

    init {
        installWindowObservers()
    }

    private fun installWindowObservers() {
        if (observersInstalled) return
        observersInstalled = true
        val center = NSNotificationCenter.defaultCenter
        center.addObserver(observer, observer.willMoveSelector, NSWindowWillMoveNotification, this)
        center.addObserver(observer, observer.didMoveSelector, NSWindowDidMoveNotification, this)
        center.addObserver(observer, observer.willStartLiveResizeSelector, NSWindowWillStartLiveResizeNotification, this)
        center.addObserver(observer, observer.didEndLiveResizeSelector, NSWindowDidEndLiveResizeNotification, this)
    }

    private fun removeWindowObservers() {
        if (!observersInstalled) return
        observersInstalled = false
        NSNotificationCenter.defaultCenter.removeObserver(observer)
    }

    internal fun attachToOwner(owner: NSWindow?) {
        attachedOwnerWindow = owner
        if (owner == null || isClosingPanel || isActivelyDraggingOrResizing) return
        if (!owner.isVisible() || MacosNativeWindowHost.isWindowClosing(owner)) return
        if (!isSameNsWindow(parentWindow, owner)) {
            owner.addChildWindow(this, ordered = NSWindowAbove)
        }
    }

    internal fun beginDragOrResize() {
        if (isClosingPanel) return
        settleGeneration++
        if (!isActivelyDraggingOrResizing) {
            isActivelyDraggingOrResizing = true
            val owner = attachedOwnerWindow ?: parentWindow
            if (owner != null) {
                attachedOwnerWindow = owner
                if (isSameNsWindow(parentWindow, owner)) {
                    owner.removeChildWindow(this)
                }
            }
        }
    }

    internal fun endDragOrResizeNow() {
        settleGeneration++
        reattachOwnerIfSettled()
    }

    private fun scheduleReattachAfterSettle() {
        val gen = ++settleGeneration
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 40_000_000L), dispatch_get_main_queue()) {
            if (gen == settleGeneration && !isClosingPanel && !inLiveResize && NSEvent.pressedMouseButtons == 0uL) {
                reattachOwnerIfSettled()
            }
        }
    }

    private fun reattachOwnerIfSettled() {
        if (isClosingPanel) return
        isActivelyDraggingOrResizing = false
        val owner = attachedOwnerWindow ?: return
        if (!isVisible() || !owner.isVisible() || MacosNativeWindowHost.isWindowClosing(owner)) return
        if (!isSameNsWindow(parentWindow, owner)) {
            owner.addChildWindow(this, ordered = NSWindowAbove)
        }
    }

    override fun sendEvent(event: NSEvent) {
        if (!isClosingPanel && event.type == NSEventTypeLeftMouseDown) {
            val cv = contentView
            val inNonContentChrome = if (cv == null) {
                true
            } else {
                val locY = event.locationInWindow.useContents { y }
                val cvTopY = cv.frame.useContents { origin.y + size.height }
                locY > cvTopY || cv.hitTest(cv.convertPoint(event.locationInWindow, fromView = null)) == null
            }
            if (inNonContentChrome) {
                beginDragOrResize()
            }
        }
        super.sendEvent(event)
        if (!isClosingPanel && event.type == NSEventTypeLeftMouseUp && isActivelyDraggingOrResizing && !inLiveResize) {
            endDragOrResizeNow()
        }
    }

    internal fun detachForClose() {
        isClosingPanel = true
        settleGeneration++
        isActivelyDraggingOrResizing = false
        removeWindowObservers()
        val owner = attachedOwnerWindow ?: parentWindow
        if (owner != null && isSameNsWindow(parentWindow, owner)) {
            owner.removeChildWindow(this)
        }
        attachedOwnerWindow = null
    }

    override fun close() {
        detachForClose()
        super.close()
    }
}
