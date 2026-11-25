@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.enums.css.CssCursor
import com.nexustools.genericui.event.PointerDragManager
import com.nexustools.genericui.event.PointerEvent
import com.nexustools.genericui.event.PopupMouseCaptureManager
import com.nexustools.genericui.event.WheelEvent
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssCustomCursor
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.useContents
import platform.AppKit.NSCursor
import platform.AppKit.NSEvent
import platform.AppKit.NSEventModifierFlagCommand
import platform.AppKit.NSEventModifierFlagControl
import platform.AppKit.NSEventModifierFlagOption
import platform.AppKit.NSEventModifierFlagShift
import platform.AppKit.NSEventTypeLeftMouseDown
import platform.AppKit.NSEventTypeLeftMouseDragged
import platform.AppKit.NSEventTypeOtherMouseDown
import platform.AppKit.NSEventTypeOtherMouseDragged
import platform.AppKit.NSEventTypeRightMouseDown
import platform.AppKit.NSEventTypeRightMouseDragged
import platform.AppKit.NSTrackingActiveAlways
import platform.AppKit.NSTrackingArea
import platform.AppKit.NSTrackingCursorUpdate
import platform.AppKit.NSTrackingInVisibleRect
import platform.AppKit.NSTrackingMouseEnteredAndExited
import platform.AppKit.NSTrackingMouseMoved
import platform.AppKit.NSView
import platform.AppKit.NSWindow
import platform.Foundation.NSMakeRect
import kotlin.math.hypot

internal class MacosViewInteractionController(
    private val owner: DesktopBaseNativeImpl<*>,
    private val view: NSView,
    private val hasWheelListener: () -> Boolean = { false }
) {
    private var trackingArea: NSTrackingArea? = null
    private var isPressed: Boolean = false
    private var pressWindowX: Double = 0.0
    private var pressWindowY: Double = 0.0
    private var draggedBeyondSlop: Boolean = false
    var isHovered: Boolean = false
        private set

    var onVisualStateChanged: (() -> Unit)? = null

    fun refreshTrackingAreas() {
        if (trackingArea != null) return
        trackingArea?.let { view.removeTrackingArea(it) }
        val options = NSTrackingMouseEnteredAndExited or
            NSTrackingMouseMoved or
            NSTrackingCursorUpdate or
            NSTrackingActiveAlways or
            NSTrackingInVisibleRect
        val area = NSTrackingArea(
            rect = view.bounds,
            options = options,
            owner = view,
            userInfo = null
        )
        view.addTrackingArea(area)
        trackingArea = area
    }

    private fun enclosingRootView(): MacosWindowRootView? =
        view.window?.contentView as? MacosWindowRootView

    private fun isCursorLocked(rootView: MacosWindowRootView?): Boolean =
        rootView?.isCursorLockedNow() == true || PointerDragManager.isCursorLocked

    fun applyCursorRects() {
        val rootView = enclosingRootView()
        if (isCursorLocked(rootView)) return
        val ownCursor = resolveDirectCursorForWidget(owner.nativeWidget) ?: return
        if (rootView != null && rootView.isUndecoratedChrome && !rootView.isWindowInFullscreen()) {
            val inset = MacosNativeWindowHost.UNDECORATED_RESIZE_EDGE_PT
            val (rx, ry, rw, rh) = rootView.bounds.useContents {
                listOf(origin.x, origin.y, size.width, size.height)
            }
            val interiorW = rw - 2.0 * inset
            val interiorH = rh - 2.0 * inset
            if (interiorW <= 0.0 || interiorH <= 0.0) return
            val interiorInRoot = NSMakeRect(rx + inset, ry + inset, interiorW, interiorH)
            val interiorInView = view.convertRect(interiorInRoot, fromView = rootView)
            val (vx1, vy1, vx2, vy2) = view.bounds.useContents {
                val x1 = minOf(origin.x, origin.x + size.width)
                val y1 = minOf(origin.y, origin.y + size.height)
                val x2 = maxOf(origin.x, origin.x + size.width)
                val y2 = maxOf(origin.y, origin.y + size.height)
                listOf(x1, y1, x2, y2)
            }
            val (ix1, iy1, ix2, iy2) = interiorInView.useContents {
                val x1 = minOf(origin.x, origin.x + size.width)
                val y1 = minOf(origin.y, origin.y + size.height)
                val x2 = maxOf(origin.x, origin.x + size.width)
                val y2 = maxOf(origin.y, origin.y + size.height)
                listOf(x1, y1, x2, y2)
            }
            val cx1 = maxOf(vx1, ix1)
            val cy1 = maxOf(vy1, iy1)
            val cx2 = minOf(vx2, ix2)
            val cy2 = minOf(vy2, iy2)
            val cw = cx2 - cx1
            val ch = cy2 - cy1
            if (cw > 0.0 && ch > 0.0) {
                view.addCursorRect(NSMakeRect(cx1, cy1, cw, ch), cursor = ownCursor.second)
            }
            return
        }
        view.addCursorRect(view.bounds, cursor = ownCursor.second)
    }

    fun handleCursorUpdate(event: NSEvent) {
        if (isCursorLocked(enclosingRootView())) return
        syncCursorForEvent(event, owner.nativeWidget, forceCheckCurrent = true)
    }

    private fun syncCursorForEvent(
        event: NSEvent,
        fallbackWidget: DesktopNativeWidget?,
        forceCheckCurrent: Boolean = false
    ) {
        val rootView = enclosingRootView()
        if (isCursorLocked(rootView)) return
        if (rootView != null) {
            if (!rootView.suppressCursorSync) {
                rootView.updateEffectiveCursorForWindowLocation(
                    locationInWindow = event.locationInWindow,
                    event = event,
                    forceCheckCurrent = forceCheckCurrent
                )
            }
            return
        }
        val winContent = view.window?.contentView
        if (winContent != null) {
            val ptInContent = winContent.convertPoint(event.locationInWindow, fromView = null)
            val sv = winContent.superview
            val ptInSuper = if (sv != null) winContent.convertPoint(ptInContent, toView = sv) else ptInContent
            val topmost = winContent.hitTest(ptInSuper)
            if (topmost != null && topmost !== view) {
                return
            }
        }
        val cursor = resolveEffectiveCursorForWidget(fallbackWidget)?.second ?: NSCursor.arrowCursor
        if (view.window?.isKeyWindow() == false && MacosCursorTranslator.isHiddenCursor(cursor)) {
            return
        }
        if (!MacosCursorTranslator.isSameCursor(NSCursor.currentCursor, cursor)) {
            cursor.set()
        }
    }

    private fun resolveDirectCursorForWidget(widget: DesktopNativeWidget): Pair<String, NSCursor>? {
        val customCursor = widget.attributes["customCursor"] as? CssCustomCursor
        val rawCss = widget.attributes["cursor"] as? String
        if (customCursor != null || !rawCss.isNullOrBlank()) {
            return MacosCursorTranslator.resolveCursor(
                rawCss = rawCss,
                customCursor = customCursor,
                isTextInput = false
            )
        }
        return null
    }

    private fun resolveEffectiveCursorForWidget(startWidget: DesktopNativeWidget?): Pair<String, NSCursor>? {
        var cur = startWidget
        while (cur != null) {
            resolveDirectCursorForWidget(cur)?.let { return it }
            cur = cur.parentWidget
        }
        return null
    }

    fun resolveCssCursor(): CssCursorValue? {
        val custom = owner.nativeWidget.attributes["customCursor"] as? CssCustomCursor
        if (custom != null) return custom
        val raw = owner.nativeWidget.attributes["cursor"] as? String ?: return null
        return CssCursor.fromCss(raw)
    }

    fun handleMouseDown(event: NSEvent, buttonIndex: Int = 0) {
        if (!owner.nativeWidget.enabled || owner.isPointerInteractionDisabled()) return
        val pe = toPointerEvent(event, button = buttonIndex, clickCount = event.clickCount.toInt())
        if (PopupMouseCaptureManager.interceptPointerDown(pe.screenX, pe.screenY, owner.nativeWidget)) {
            isPressed = false
            return
        }
        if (buttonIndex == 0 && event.type == NSEventTypeLeftMouseDown) {
            MacosNativeWindowHost.recordLeftMouseDownEvent(view.window, event)
        }
        isPressed = true
        draggedBeyondSlop = false
        pressWindowX = pe.screenX.toDouble()
        pressWindowY = pe.screenY.toDouble()
        owner.dispatchPointerDown(pe)
        onVisualStateChanged?.invoke()
    }

    fun handleMouseDragged(event: NSEvent, buttonIndex: Int = 0) {
        val pe = toPointerEvent(event, button = buttonIndex, clickCount = event.clickCount.toInt())
        val wasPressed = isPressed
        if (wasPressed) {
            val dist = hypot(pe.screenX.toDouble() - pressWindowX, pe.screenY.toDouble() - pressWindowY)
            if (dist >= 5.0) {
                draggedBeyondSlop = true
            }
        }
        GenericUiThreading.runOnLogic {
            if (PointerDragManager.isDragging) {
                PointerDragManager.notifyGlobalPointerMove(pe, coordinatesAreTargetLocal = false)
            } else if (wasPressed) {
                owner.dispatchPointerMove(pe)
            }
        }
    }

    fun handleMouseUp(event: NSEvent, buttonIndex: Int = 0) {
        if (buttonIndex == 0) {
            MacosNativeWindowHost.clearLeftMouseDownEvent(view.window)
        }
        val pe = toPointerEvent(event, button = buttonIndex, clickCount = event.clickCount.toInt())
        val wasPressed = isPressed
        val hadDrag = draggedBeyondSlop
        isPressed = false
        draggedBeyondSlop = false
        var wasDragging = PointerDragManager.isDragging

        GenericUiThreading.runOnLogic {
            if (PointerDragManager.isDragging) {
                wasDragging = true
                PointerDragManager.notifyGlobalPointerUp(pe, coordinatesAreTargetLocal = false)
            }
        }

        if (PopupMouseCaptureManager.interceptPointerUp(pe.screenX, pe.screenY, owner.nativeWidget)) {
            onVisualStateChanged?.invoke()
            return
        }

        if (wasPressed) {
            owner.dispatchPointerUp(pe)
            val inside = isPointInsideView(pe.x.toDouble(), pe.y.toDouble())
            if (inside && !hadDrag && !wasDragging && buttonIndex == 0 && !owner.isPointerInteractionDisabled() && owner.nativeWidget.enabled) {
                if (event.clickCount >= 2L) {
                    owner.dispatchDoubleClick()
                } else {
                    owner.dispatchClick()
                }
            }
            onVisualStateChanged?.invoke()
        }
    }

    fun handleRightMouseDown(event: NSEvent) {
        if (!owner.nativeWidget.enabled || owner.isPointerInteractionDisabled()) return
        val pe = toPointerEvent(event, button = 2, clickCount = event.clickCount.toInt())
        owner.dispatchContextMenu(pe)
    }

    private fun shouldIgnoreStaleHoverEvent(event: NSEvent): Boolean {
        if (MacosNativeWindowHost.visualTestMode) return false
        if (NSEvent.pressedMouseButtons != 0uL) return false
        val win = view.window ?: return false
        if (event.window !== win || !win.isVisible()) return false
        val mousePt = NSEvent.mouseLocation
        val (mx, my) = mousePt.useContents { x to y }
        val insideWin = win.frame.useContents {
            mx >= origin.x - 1.0 && mx <= origin.x + size.width + 1.0 &&
                my >= origin.y - 1.0 && my <= origin.y + size.height + 1.0
        }
        if (!insideWin) return true
        val topWinNumber = NSWindow.windowNumberAtPoint(mousePt, belowWindowWithWindowNumber = 0L)
        if (topWinNumber > 0L && topWinNumber != win.windowNumber) {
            val isOwnedChild = win.childWindows?.any { cw ->
                (cw as? NSWindow)?.windowNumber == topWinNumber
            } == true
            if (!isOwnedChild) return true
        }
        return false
    }

    fun handleMouseEntered(event: NSEvent) {
        if (shouldIgnoreStaleHoverEvent(event)) return
        isHovered = true
        syncCursorForEvent(event, owner.nativeWidget)
        val pe = toPointerEvent(event, button = 0)
        owner.dispatchPointerEnter(pe)
        onVisualStateChanged?.invoke()
    }

    fun handleMouseExited(event: NSEvent) {
        isHovered = false
        val rootView = enclosingRootView()
        if (!isCursorLocked(rootView) && !PointerDragManager.isDragging && rootView == null) {
            syncCursorForEvent(event, owner.nativeWidget.parentWidget)
        }
        val pe = toPointerEvent(event, button = 0)
        owner.dispatchPointerLeave(pe)
        onVisualStateChanged?.invoke()
    }

    fun handleMouseMoved(event: NSEvent) {
        if (!PointerDragManager.isDragging && shouldIgnoreStaleHoverEvent(event)) return
        val rootView = enclosingRootView()
        if (rootView != null && !rootView.suppressCursorSync && !isCursorLocked(rootView)) {
            rootView.updateEffectiveCursorForWindowLocation(event.locationInWindow, event = event)
        }
        val pe = toPointerEvent(event, button = 0)
        if (PointerDragManager.isDragging) {
            GenericUiThreading.runOnLogic {
                if (PointerDragManager.isDragging) {
                    PointerDragManager.notifyGlobalPointerMove(pe, coordinatesAreTargetLocal = false)
                } else {
                    owner.dispatchPointerMove(pe)
                }
            }
            return
        }
        owner.dispatchPointerMove(pe)
    }

    fun handleScrollWheel(event: NSEvent): Boolean {
        if (!hasWheelListener() || !owner.nativeWidget.enabled || owner.isPointerInteractionDisabled()) {
            return false
        }
        val (lx, ly) = view.convertPoint(event.locationInWindow, fromView = null).useContents { x.toFloat() to y.toFloat() }
        val mod = event.modifierFlags
        val wheel = WheelEvent(
            x = lx,
            y = ly,
            deltaX = (-event.scrollingDeltaX).toFloat(),
            deltaY = (-event.scrollingDeltaY).toFloat(),
            shiftKey = (mod and NSEventModifierFlagShift) != 0uL,
            ctrlKey = (mod and NSEventModifierFlagControl) != 0uL,
            altKey = (mod and NSEventModifierFlagOption) != 0uL,
            metaKey = (mod and NSEventModifierFlagCommand) != 0uL
        )
        owner.dispatchWheel(wheel)
        return true
    }

    private fun toPointerEvent(event: NSEvent, button: Int, clickCount: Int = 1): PointerEvent {
        val locInWindow = event.locationInWindow
        val (lx, ly) = view.convertPoint(locInWindow, fromView = null).useContents { x.toFloat() to y.toFloat() }
        val rootView = view.window?.contentView
        val (wx, wy) = if (rootView != null) {
            rootView.convertPoint(locInWindow, fromView = null).useContents { x.toFloat() to y.toFloat() }
        } else {
            lx to ly
        }
        val mod = event.modifierFlags
        val pressure = when (event.type) {
            NSEventTypeLeftMouseDown,
            NSEventTypeLeftMouseDragged,
            NSEventTypeRightMouseDown,
            NSEventTypeRightMouseDragged,
            NSEventTypeOtherMouseDown,
            NSEventTypeOtherMouseDragged -> event.pressure
            else -> if (isPressed) 0.5f else 0f
        }
        return PointerEvent(
            x = lx,
            y = ly,
            screenX = wx,
            screenY = wy,
            button = button,
            buttonsMask = if (isPressed) (1 shl button) else 0,
            pressure = pressure,
            shiftKey = (mod and NSEventModifierFlagShift) != 0uL,
            ctrlKey = (mod and NSEventModifierFlagControl) != 0uL,
            altKey = (mod and NSEventModifierFlagOption) != 0uL,
            metaKey = (mod and NSEventModifierFlagCommand) != 0uL,
            clickCount = clickCount.coerceAtLeast(1)
        )
    }

    private fun isPointInsideView(x: Double, y: Double): Boolean =
        view.bounds.useContents {
            x >= origin.x && y >= origin.y &&
                x <= origin.x + size.width && y <= origin.y + size.height
        }

    companion object {
        internal fun toRootWindowPointerEvent(rootView: NSView, event: NSEvent, button: Int = 0): PointerEvent {
            val (wx, wy) = rootView.convertPoint(event.locationInWindow, fromView = null).useContents { x.toFloat() to y.toFloat() }
            val mod = event.modifierFlags
            return PointerEvent(
                x = wx,
                y = wy,
                screenX = wx,
                screenY = wy,
                button = button,
                buttonsMask = 1 shl button,
                pressure = event.pressure,
                shiftKey = (mod and NSEventModifierFlagShift) != 0uL,
                ctrlKey = (mod and NSEventModifierFlagControl) != 0uL,
                altKey = (mod and NSEventModifierFlagOption) != 0uL,
                metaKey = (mod and NSEventModifierFlagCommand) != 0uL,
                clickCount = event.clickCount.toInt().coerceAtLeast(1)
            )
        }
    }
}
