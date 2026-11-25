@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.event.PointerDragManager
import com.nexustools.genericui.event.PopupMouseCaptureManager
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssCustomCursor
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.CValue
import kotlinx.cinterop.useContents
import platform.AppKit.NSButton
import platform.AppKit.NSCursor
import platform.AppKit.NSEvent
import platform.AppKit.NSTextField
import platform.AppKit.NSTextView
import platform.AppKit.NSTrackingActiveAlways
import platform.AppKit.NSTrackingArea
import platform.AppKit.NSTrackingInVisibleRect
import platform.AppKit.NSTrackingMouseEnteredAndExited
import platform.AppKit.NSTrackingMouseMoved
import platform.AppKit.NSView
import platform.AppKit.NSWindowStyleMaskFullScreen
import platform.CoreGraphics.CGPoint
import platform.Foundation.NSMakePoint
import platform.Foundation.NSMakeRect
import platform.Foundation.NSRect
import platform.Foundation.NSSize

/**
 * Root flipped AppKit [NSView] mounted as the `contentView` of GenericUI desktop windows and
 * dialogs, hosting the retained native widget subtree and dispatching real AppKit pointer,
 * hover, scroll, resize, and keyboard events.
 */
class MacosWindowRootView(
    val windowKey: Long,
    frameRect: CValue<NSRect> = NSMakeRect(0.0, 0.0, 800.0, 600.0)
) : NSView(frameRect) {

    internal var isUndecoratedChrome: Boolean = false
    internal var isInNativeLiveResize: Boolean = false
    internal var activeCustomResizeEdge: MacosNativeWindowHost.UndecoratedResizeEdge =
        MacosNativeWindowHost.UndecoratedResizeEdge.NONE
    internal var lockedResizeCursor: NSCursor? = null
    internal var lockedDragCursor: NSCursor? = null
    internal var contentRootView: NSView? = null
    internal var titleBarOverlayView: MacosTitleBarView? = null
    internal val unifiedHeaderView = MacosUnifiedHeaderBarView()
    internal var unifiedHeaderHeightPx: Double = 0.0
    internal var inLayoutCallback: Boolean = false
    internal var onNeedsRelayout: (() -> Unit)? = null
    internal var onUndecoratedEdgeMouseDown: ((NSEvent) -> Boolean)? = null
    internal var onUndecoratedEdgeMouseDragged: ((NSEvent) -> Boolean)? = null
    internal var onUndecoratedEdgeMouseUp: ((NSEvent) -> Boolean)? = null
    internal var onWindowMouseMoved: ((Double, Double) -> Unit)? = null
    internal var onWindowMouseExited: (() -> Unit)? = null
    internal var onWindowKeyDown: ((NSEvent) -> Boolean)? = null
    internal var onWindowKeyUp: ((NSEvent) -> Boolean)? = null

    private var rootTrackingArea: NSTrackingArea? = null
    internal var suppressCursorSync: Boolean = false
    internal var lastAppliedCursor: NSCursor? = null
        private set
    internal var lastKnownClientX: Double = Double.NaN
        private set
    internal var lastKnownClientY: Double = Double.NaN
        private set
    private var lastCursorEventTimestamp: Double = -1.0
    private var lastCursorEventClientX: Double = Double.NaN
    private var lastCursorEventClientY: Double = Double.NaN

    init {
        setWantsLayer(true)
        setAutoresizesSubviews(false)
        unifiedHeaderView.setHidden(true)
        addSubview(unifiedHeaderView)
    }

    override fun isFlipped(): Boolean = true

    override fun mouseDownCanMoveWindow(): Boolean = false

    override fun acceptsFirstResponder(): Boolean = true

    override fun acceptsFirstMouse(event: NSEvent?): Boolean = true

    override fun setFrameSize(newSize: CValue<NSSize>) {
        super.setFrameSize(newSize)
        onNeedsRelayout?.invoke()
    }

    override fun layout() {
        super.layout()
        inLayoutCallback = true
        try {
            onNeedsRelayout?.invoke()
        } finally {
            inLayoutCallback = false
        }
    }

    override fun updateTrackingAreas() {
        super.updateTrackingAreas()
        if (rootTrackingArea != null) return
        val options = NSTrackingMouseEnteredAndExited or
            NSTrackingMouseMoved or
            NSTrackingActiveAlways or
            NSTrackingInVisibleRect
        val area = NSTrackingArea(
            rect = bounds,
            options = options,
            owner = this,
            userInfo = null
        )
        addTrackingArea(area)
        rootTrackingArea = area
    }

    internal fun isWindowInFullscreen(): Boolean =
        ((window?.styleMask ?: 0uL) and NSWindowStyleMaskFullScreen) != 0uL

    internal fun isWindowControlHitViewInternal(hitView: NSView?): Boolean =
        isWindowControlHitView(hitView)

    private fun isResizingNow(): Boolean =
        isInNativeLiveResize || activeCustomResizeEdge != MacosNativeWindowHost.UndecoratedResizeEdge.NONE

    internal fun isCursorLockedNow(): Boolean =
        isResizingNow() || PointerDragManager.isCursorLocked || lockedDragCursor != null

    private fun resolveNsCursorForValue(cursorValue: CssCursorValue): NSCursor =
        when (cursorValue) {
            is CssCustomCursor -> MacosCursorTranslator.resolveCursor(
                rawCss = cursorValue.fallback.cssValue,
                customCursor = cursorValue
            ).second
            else -> MacosCursorTranslator.resolveCursor(
                rawCss = cursorValue.toCssString(),
                customCursor = null
            ).second
        }

    private fun currentLockedCursorOrNull(): NSCursor? =
        lockedResizeCursor
            ?: lockedDragCursor
            ?: PointerDragManager.lockedCursor?.let { resolveNsCursorForValue(it) }

    internal fun applyDragCursorLock(locked: Boolean, cursorValue: CssCursorValue?) {
        if (locked) {
            val targetNsCursor = if (cursorValue != null) {
                resolveNsCursorForValue(cursorValue)
            } else {
                lockedResizeCursor ?: lastAppliedCursor ?: NSCursor.currentCursor
            }
            lockedDragCursor = targetNsCursor
            window?.disableCursorRects()
            discardDescendantCursorRects(this)
            window?.invalidateCursorRectsForView(this)
            targetNsCursor.set()
        } else {
            lockedDragCursor = null
            if (!isResizingNow()) {
                window?.enableCursorRects()
                window?.invalidateCursorRectsForView(this)
            }
        }
    }

    override fun hitTest(point: CValue<CGPoint>): NSView? {
        val superHit = super.hitTest(point) ?: return null
        if (!isCursorLockedNow() && isUndecoratedChrome && !isWindowInFullscreen()) {
            val ptInSelf = superview?.let { convertPoint(point, fromView = it) } ?: point
            val (lx, ly) = ptInSelf.useContents { x to y }
            val (bw, bh) = bounds.useContents { size.width to size.height }
            val isControlHit = isWindowControlHitView(superHit)
            val edge = MacosNativeWindowHost.detectUndecoratedResizeEdge(
                viewX = lx,
                viewY = ly,
                viewWidth = bw,
                viewHeight = bh,
                isWindowControlHit = isControlHit
            )
            if (edge != MacosNativeWindowHost.UndecoratedResizeEdge.NONE) {
                return this
            }
        }
        return superHit
    }

    override fun resetCursorRects() {
        super.resetCursorRects()
        if (isCursorLockedNow()) {
            discardDescendantCursorRects(this)
            currentLockedCursorOrNull()?.let { locked ->
                addCursorRect(bounds, cursor = locked)
            }
            return
        }
        syncDescendantCursorRects(this, ancestorHasHiddenCursor = false)
        if (isUndecoratedChrome && !isWindowInFullscreen()) {
            addUndecoratedEdgeCursorRects()
        }
    }

    private fun discardDescendantCursorRects(parent: NSView) {
        for (sub in parent.subviews) {
            val child = sub as? NSView ?: continue
            child.discardCursorRects()
            discardDescendantCursorRects(child)
        }
    }

    private fun syncDescendantCursorRects(
        parent: NSView,
        ancestorHasHiddenCursor: Boolean,
        ancestorPointerEventsNone: Boolean = false
    ) {
        for (sub in parent.subviews) {
            val child = sub as? NSView ?: continue
            child.discardCursorRects()
            if (child.isHidden() || child.alphaValue <= 0.004) {
                discardDescendantCursorRects(child)
                continue
            }
            val handle = (child as? MacosInteractiveSurfaceView)?.surfaceWidget
                ?: MacosFocusSupport.directHandleForView(child)
            val selfPointerEvents = handle?.attributes?.get("pointerEvents") as? String
            val effectivePointerEventsNone = when (selfPointerEvents) {
                "none" -> true
                "auto" -> false
                else -> ancestorPointerEventsNone
            }
            val customCursor = handle?.attributes?.get("customCursor") as? CssCustomCursor
            val rawCss = handle?.attributes?.get("cursor") as? String
            val hasDirectCursor = customCursor != null || !rawCss.isNullOrBlank()
            val selfHidden = hasDirectCursor && MacosCursorTranslator.isHiddenCursor(
                MacosCursorTranslator.resolveCursor(rawCss = rawCss, customCursor = customCursor).second
            )
            val effectiveHidden = if (hasDirectCursor) selfHidden else ancestorHasHiddenCursor
            if (!effectivePointerEventsNone && (!ancestorHasHiddenCursor || hasDirectCursor)) {
                child.resetCursorRects()
            }
            syncDescendantCursorRects(
                parent = child,
                ancestorHasHiddenCursor = effectiveHidden,
                ancestorPointerEventsNone = effectivePointerEventsNone
            )
        }
    }

    private fun addUndecoratedEdgeCursorRects() {
        val (bw, bh) = bounds.useContents { size.width to size.height }
        val edge = MacosNativeWindowHost.UNDECORATED_RESIZE_EDGE_PT
        val corner = MacosNativeWindowHost.UNDECORATED_RESIZE_CORNER_PT
        if (bw <= corner * 2.0 || bh <= corner * 2.0) return
        val nw = MacosNativeWindowHost.resolveCursorForResizeEdge(MacosNativeWindowHost.UndecoratedResizeEdge.NW).second
        val ne = MacosNativeWindowHost.resolveCursorForResizeEdge(MacosNativeWindowHost.UndecoratedResizeEdge.NE).second
        val sw = MacosNativeWindowHost.resolveCursorForResizeEdge(MacosNativeWindowHost.UndecoratedResizeEdge.SW).second
        val se = MacosNativeWindowHost.resolveCursorForResizeEdge(MacosNativeWindowHost.UndecoratedResizeEdge.SE).second
        val n = MacosNativeWindowHost.resolveCursorForResizeEdge(MacosNativeWindowHost.UndecoratedResizeEdge.N).second
        val s = MacosNativeWindowHost.resolveCursorForResizeEdge(MacosNativeWindowHost.UndecoratedResizeEdge.S).second
        val w = MacosNativeWindowHost.resolveCursorForResizeEdge(MacosNativeWindowHost.UndecoratedResizeEdge.W).second
        val e = MacosNativeWindowHost.resolveCursorForResizeEdge(MacosNativeWindowHost.UndecoratedResizeEdge.E).second
        addCursorRect(NSMakeRect(0.0, 0.0, corner, edge), cursor = nw)
        addCursorRect(NSMakeRect(0.0, edge, edge, corner - edge), cursor = nw)
        addCursorRect(NSMakeRect(bw - corner, 0.0, corner, edge), cursor = ne)
        addCursorRect(NSMakeRect(bw - edge, edge, edge, corner - edge), cursor = ne)
        addCursorRect(NSMakeRect(0.0, bh - edge, corner, edge), cursor = sw)
        addCursorRect(NSMakeRect(0.0, bh - corner, edge, corner - edge), cursor = sw)
        addCursorRect(NSMakeRect(bw - corner, bh - edge, corner, edge), cursor = se)
        addCursorRect(NSMakeRect(bw - edge, bh - corner, edge, corner - edge), cursor = se)
        addCursorRect(NSMakeRect(corner, 0.0, bw - corner * 2.0, edge), cursor = n)
        addCursorRect(NSMakeRect(corner, bh - edge, bw - corner * 2.0, edge), cursor = s)
        addCursorRect(NSMakeRect(0.0, corner, edge, bh - corner * 2.0), cursor = w)
        addCursorRect(NSMakeRect(bw - edge, corner, edge, bh - corner * 2.0), cursor = e)
    }

    override fun cursorUpdate(event: NSEvent) {
        if (isCursorLockedNow()) {
            currentLockedCursorOrNull()?.set()
            return
        }
        if (!suppressCursorSync) {
            updateEffectiveCursorForWindowLocation(event.locationInWindow, event = event, forceCheckCurrent = true)
        } else {
            super.cursorUpdate(event)
        }
    }

    override fun mouseDown(event: NSEvent) {
        if (isUndecoratedChrome && !isWindowInFullscreen() && onUndecoratedEdgeMouseDown?.invoke(event) == true) {
            return
        }
        val (wx, wy) = convertPoint(event.locationInWindow, fromView = null).useContents { x to y }
        if (PopupMouseCaptureManager.interceptPointerDown(wx.toFloat(), wy.toFloat(), null)) {
            return
        }
        if (unifiedHeaderHeightPx > 0.0 && wy in 0.0..unifiedHeaderHeightPx) {
            val hit = super.hitTest(platform.Foundation.NSMakePoint(wx, wy))
            if (hit == null || hit === this || hit === unifiedHeaderView) {
                if (event.clickCount >= 2L) {
                    (window as? MacosHostWindow)?.handleTitleBarDoubleClick() ?: window?.performZoom(null)
                    return
                }
                val win = window
                if (win != null) {
                    MacosNativeWindowHost.performWindowDragWithCursorLock(win, event)
                    return
                }
            }
        }
        super.mouseDown(event)
    }

    override fun mouseDragged(event: NSEvent) {
        if (isCursorLockedNow()) {
            currentLockedCursorOrNull()?.set()
        }
        if (isUndecoratedChrome && !isWindowInFullscreen() && onUndecoratedEdgeMouseDragged?.invoke(event) == true) {
            currentLockedCursorOrNull()?.set()
            return
        }
        if (PointerDragManager.isDragging) {
            val pe = MacosViewInteractionController.toRootWindowPointerEvent(this, event)
            GenericUiThreading.runOnLogic {
                PointerDragManager.notifyGlobalPointerMove(pe, coordinatesAreTargetLocal = false)
            }
            return
        }
        super.mouseDragged(event)
    }

    override fun mouseUp(event: NSEvent) {
        if ((isUndecoratedChrome && !isWindowInFullscreen() ||
                activeCustomResizeEdge != MacosNativeWindowHost.UndecoratedResizeEdge.NONE) &&
            onUndecoratedEdgeMouseUp?.invoke(event) == true
        ) {
            return
        }
        if (PointerDragManager.isDragging) {
            val pe = MacosViewInteractionController.toRootWindowPointerEvent(this, event)
            GenericUiThreading.runOnLogic {
                PointerDragManager.notifyGlobalPointerUp(pe, coordinatesAreTargetLocal = false)
            }
        }
        val (wx, wy) = convertPoint(event.locationInWindow, fromView = null).useContents { x to y }
        if (PopupMouseCaptureManager.interceptPointerUp(wx.toFloat(), wy.toFloat(), null)) {
            return
        }
        super.mouseUp(event)
    }

    override fun mouseEntered(event: NSEvent) {
        val (wx, wy) = convertPoint(event.locationInWindow, fromView = null).useContents { x to y }
        recordLastKnownClientPoint(wx, wy)
        if (isCursorLockedNow()) {
            currentLockedCursorOrNull()?.set()
        } else if (!suppressCursorSync) {
            updateEffectiveCursorForWindowEvent(event, wx, wy, forceCheckCurrent = true)
        }
        super.mouseEntered(event)
    }

    override fun mouseExited(event: NSEvent) {
        lastKnownClientX = Double.NaN
        lastKnownClientY = Double.NaN
        if (!isCursorLockedNow()) {
            lastAppliedCursor = null
            val current = NSCursor.currentCursor
            if (MacosCursorTranslator.isHiddenCursor(current) ||
                !MacosCursorTranslator.isSameCursor(current, NSCursor.arrowCursor)
            ) {
                NSCursor.arrowCursor.set()
            }
        } else {
            currentLockedCursorOrNull()?.set()
        }
        onWindowMouseExited?.invoke()
        super.mouseExited(event)
    }

    override fun mouseMoved(event: NSEvent) {
        val (wx, wy) = convertPoint(event.locationInWindow, fromView = null).useContents { x to y }
        recordLastKnownClientPoint(wx, wy)
        if (isCursorLockedNow()) {
            currentLockedCursorOrNull()?.set()
            onWindowMouseMoved?.invoke(wx, wy)
            super.mouseMoved(event)
            return
        }
        if (!suppressCursorSync) {
            updateEffectiveCursorForWindowEvent(event, wx, wy, forceCheckCurrent = false)
        }
        onWindowMouseMoved?.invoke(wx, wy)
        super.mouseMoved(event)
    }

    internal fun updateEffectiveCursorForWindowLocation(
        locationInWindow: CValue<CGPoint>,
        event: NSEvent? = null,
        forceCheckCurrent: Boolean = false
    ): NSCursor {
        if (isCursorLockedNow()) {
            val locked = currentLockedCursorOrNull() ?: NSCursor.arrowCursor
            applyCursorIfChanged(locked, forceCheckCurrent = true)
            return locked
        }
        if (suppressCursorSync) {
            return lastAppliedCursor ?: NSCursor.arrowCursor
        }
        val (clientX, clientY) = convertPoint(locationInWindow, fromView = null).useContents { x to y }
        return if (event != null) {
            updateEffectiveCursorForWindowEvent(event, clientX, clientY, forceCheckCurrent = forceCheckCurrent)
        } else {
            updateEffectiveCursorForClientPoint(clientX, clientY, forceCheckCurrent = forceCheckCurrent)
        }
    }

    private fun updateEffectiveCursorForWindowEvent(
        event: NSEvent,
        clientX: Double,
        clientY: Double,
        forceCheckCurrent: Boolean
    ): NSCursor {
        recordLastKnownClientPoint(clientX, clientY)
        if (isCursorLockedNow()) {
            val locked = currentLockedCursorOrNull() ?: NSCursor.arrowCursor
            applyCursorIfChanged(locked, forceCheckCurrent = true)
            return locked
        }
        if (suppressCursorSync) {
            return lastAppliedCursor ?: NSCursor.arrowCursor
        }
        val ts = event.timestamp
        val existing = lastAppliedCursor
        if (!forceCheckCurrent &&
            existing != null &&
            ts == lastCursorEventTimestamp &&
            clientX == lastCursorEventClientX &&
            clientY == lastCursorEventClientY
        ) {
            return existing
        }
        val resolved = updateEffectiveCursorForClientPoint(clientX, clientY, forceCheckCurrent = forceCheckCurrent)
        lastCursorEventTimestamp = ts
        lastCursorEventClientX = clientX
        lastCursorEventClientY = clientY
        return resolved
    }

    internal fun updateEffectiveCursorForClientPoint(
        clientX: Double,
        clientY: Double,
        forceCheckCurrent: Boolean = false
    ): NSCursor {
        recordLastKnownClientPoint(clientX, clientY)
        if (isCursorLockedNow()) {
            val locked = currentLockedCursorOrNull() ?: NSCursor.arrowCursor
            applyCursorIfChanged(locked, forceCheckCurrent = true)
            return locked
        }
        val hitView = hitTestClientPoint(clientX, clientY)
        return updateEffectiveCursorForResolvedHit(
            hitView = hitView,
            clientX = clientX,
            clientY = clientY,
            forceCheckCurrent = forceCheckCurrent
        )
    }

    internal fun updateEffectiveCursorForResolvedHit(
        hitView: NSView?,
        clientX: Double,
        clientY: Double,
        forceCheckCurrent: Boolean = false
    ): NSCursor {
        recordLastKnownClientPoint(clientX, clientY)
        if (isCursorLockedNow()) {
            val locked = currentLockedCursorOrNull() ?: NSCursor.arrowCursor
            applyCursorIfChanged(locked, forceCheckCurrent = true)
            return locked
        }
        if (isUndecoratedChrome && !isWindowInFullscreen()) {
            val isControlHit = isWindowControlHitView(hitView)
            val (bw, bh) = bounds.useContents { size.width to size.height }
            val edge = MacosNativeWindowHost.detectUndecoratedResizeEdge(
                viewX = clientX,
                viewY = clientY,
                viewWidth = bw,
                viewHeight = bh,
                isWindowControlHit = isControlHit
            )
            if (edge != MacosNativeWindowHost.UndecoratedResizeEdge.NONE) {
                val resizeCursor = MacosNativeWindowHost.resolveCursorForResizeEdge(edge).second
                applyCursorIfChanged(resizeCursor, forceCheckCurrent = forceCheckCurrent)
                return resizeCursor
            }
        }

        val isNativeTextInput = (hitView is NSTextView && hitView.isEditable()) ||
            (hitView is NSTextField && hitView.isEditable())
        val startWidget: DesktopNativeWidget? = when {
            hitView is MacosInteractiveSurfaceView -> hitView.surfaceWidget
            hitView != null -> MacosFocusSupport.findOwningHandle(hitView)
            else -> null
        }
        var resolved: NSCursor? = null
        var curWidget = startWidget
        while (curWidget != null) {
            val customCursor = curWidget.attributes["customCursor"] as? CssCustomCursor
            val rawCss = curWidget.attributes["cursor"] as? String
            if (customCursor != null || !rawCss.isNullOrBlank()) {
                resolved = MacosCursorTranslator.resolveCursor(
                    rawCss = rawCss,
                    customCursor = customCursor,
                    isTextInput = isNativeTextInput
                ).second
                break
            }
            val impl = curWidget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
            if (impl is com.nexustools.genericui.macos.impl.MacosTextNativeImpl &&
                (impl.hasClickListener() || impl.isLinkTag())
            ) {
                resolved = NSCursor.pointingHandCursor
                break
            }
            curWidget = curWidget.parentWidget
        }
        val targetCursor = resolved ?: if (isNativeTextInput) NSCursor.IBeamCursor else NSCursor.arrowCursor
        applyCursorIfChanged(targetCursor, forceCheckCurrent = forceCheckCurrent)
        return targetCursor
    }

    private fun isWindowControlHitView(hitView: NSView?): Boolean {
        var cur: NSView? = hitView
        while (cur != null && cur !== this) {
            if (cur is NSButton || cur is MacosStyledButtonControl) {
                return true
            }
            val handle = MacosFocusSupport.directHandleForView(cur)
            if (handle?.attributes?.containsKey("data-window-control") == true) {
                return true
            }
            cur = cur.superview
        }
        return false
    }

    private fun recordLastKnownClientPoint(clientX: Double, clientY: Double) {
        if (clientX.isFinite() && clientY.isFinite()) {
            lastKnownClientX = clientX
            lastKnownClientY = clientY
        }
    }

    internal fun clearLastAppliedCursor() {
        lastAppliedCursor = null
    }

    private fun applyCursorIfChanged(cursor: NSCursor, forceCheckCurrent: Boolean = false) {
        if (window?.isKeyWindow() == false && MacosCursorTranslator.isHiddenCursor(cursor)) {
            return
        }
        if (!MacosCursorTranslator.isSameCursor(lastAppliedCursor, cursor) ||
            (forceCheckCurrent && !MacosCursorTranslator.isSameCursor(NSCursor.currentCursor, cursor))
        ) {
            cursor.set()
            lastAppliedCursor = cursor
        }
    }

    override fun keyDown(event: NSEvent) {
        if (window?.firstResponder is NSTextView) {
            super.keyDown(event)
            return
        }
        if (onWindowKeyDown?.invoke(event) == true) {
            return
        }
        super.keyDown(event)
    }

    override fun keyUp(event: NSEvent) {
        if (window?.firstResponder is NSTextView) {
            super.keyUp(event)
            return
        }
        if (onWindowKeyUp?.invoke(event) == true) {
            return
        }
        super.keyUp(event)
    }

    /**
     * Evaluates AppKit's real [NSView.hitTest] tree for a client-space coordinate `(clientX, clientY)`
     * relative to this flipped [MacosWindowRootView]'s top-left origin.
     */
    fun hitTestClientPoint(clientX: Double, clientY: Double): NSView? {
        val ptInSelf = NSMakePoint(clientX, clientY)
        val sv = superview
        val ptInSuper = if (sv != null) convertPoint(ptInSelf, toView = sv) else ptInSelf
        return hitTest(ptInSuper)
    }

    fun convertClientPointToWindow(clientX: Double, clientY: Double): CValue<CGPoint> =
        convertPoint(NSMakePoint(clientX, clientY), toView = null)
}
