@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopStageOverlaySpec
import com.nexustools.genericui.macos.impl.MacosTextNativeImpl
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.toLong
import kotlinx.cinterop.useContents
import platform.AppKit.NSButton
import platform.AppKit.NSControl
import platform.AppKit.NSEvent
import platform.AppKit.NSResponder
import platform.AppKit.NSScrollView
import platform.AppKit.NSTextField
import platform.AppKit.NSTextView
import platform.AppKit.NSView

/**
 * Internal macOS AppKit focusability, first-responder suppression, and view-to-handle lookup helper.
 */
internal object MacosFocusSupport {

    private val viewToHandle = HashMap<Long, MacosViewHandle>()
    private val savedRefusesFirstResponder = HashMap<Long, Boolean>()

    fun registerView(view: NSView, handle: MacosViewHandle) {
        viewToHandle[view.objcPtr().toLong()] = handle
    }

    fun directHandleForView(view: NSView): MacosViewHandle? =
        viewToHandle[view.objcPtr().toLong()]

    fun unregisterView(view: NSView, handle: MacosViewHandle) {
        val key = view.objcPtr().toLong()
        if (viewToHandle[key] === handle) {
            viewToHandle.remove(key)
        }
        cleanupSavedFlagsRecursive(view)
    }

    private fun cleanupSavedFlagsRecursive(v: NSView) {
        savedRefusesFirstResponder.remove(v.objcPtr().toLong())
        for (sub in v.subviews) {
            if (sub is NSView) {
                cleanupSavedFlagsRecursive(sub)
            }
        }
        if (v is NSScrollView) {
            val doc = v.documentView as? NSView
            if (doc != null && doc.superview != v) {
                cleanupSavedFlagsRecursive(doc)
            }
        }
    }

    fun findOwningHandle(responder: NSResponder?): MacosViewHandle? {
        if (responder == null) return null
        (responder as? NSView)?.let { directView ->
            findHandleWalkingUp(directView)?.let { return it }
        }
        if (responder is NSTextView) {
            (responder.delegate() as? NSView)?.let { delegateView ->
                findHandleWalkingUp(delegateView)?.let { return it }
            }
        }
        return null
    }

    private fun findHandleWalkingUp(view: NSView): MacosViewHandle? {
        var cur: NSView? = view
        while (cur != null) {
            val handle = viewToHandle[cur.objcPtr().toLong()]
            if (handle != null && !handle.disposed) {
                return handle
            }
            cur = cur.superview
        }
        return null
    }

    fun isResponderInNonFocusableSubtree(responder: NSResponder?): Boolean {
        val handle = findOwningHandle(responder) ?: return false
        return !handle.isEffectivelyFocusable()
    }

    fun shouldPassThroughPointerHit(hitView: NSView, containerView: NSView): Boolean {
        var cur: NSView? = hitView
        while (cur != null && cur != containerView) {
            val handle = viewToHandle[cur.objcPtr().toLong()]
            if (handle != null && !handle.disposed) {
                val pe = handle.attributes["pointerEvents"] as? String
                return pe == "none"
            }
            cur = cur.superview
        }
        return true
    }

    fun syncHandleFocusableOnAttach(view: NSView) {
        val handle = viewToHandle[view.objcPtr().toLong()] ?: return
        if (handle.disposed) return
        if (!handle.isEffectivelyFocusable() || savedRefusesFirstResponder.containsKey(view.objcPtr().toLong())) {
            applyFocusable(view, handle.isEffectivelyFocusable())
        }
    }

    fun applyFocusable(view: NSView, focusable: Boolean) {
        applyControlsRecursive(view, focusable)
        if (!focusable) {
            resignIfInside(view)
            view.window?.recalculateKeyViewLoop()
        } else {
            view.window?.recalculateKeyViewLoop()
        }
    }

    fun resignIfInside(rootView: NSView?) {
        val root = rootView ?: return
        val win = root.window ?: return
        val fr = win.firstResponder ?: return
        val isInside = when {
            fr is NSView && isViewInSubtree(fr, root) -> true
            fr is NSTextView -> (fr.delegate() as? NSView)?.let { isViewInSubtree(it, root) } == true
            else -> false
        }
        if (isInside) {
            win.makeFirstResponder(null)
        }
    }

    private fun applyControlsRecursive(v: NSView, inheritedFocusable: Boolean) {
        val ownHandle = viewToHandle[v.objcPtr().toLong()]?.takeIf { !it.disposed }
        val effective = if (ownHandle != null) {
            inheritedFocusable && ownHandle.isEffectivelyFocusable()
        } else {
            inheritedFocusable
        }
        if (v is NSControl) {
            val key = v.objcPtr().toLong()
            if (!effective) {
                if (!savedRefusesFirstResponder.containsKey(key)) {
                    savedRefusesFirstResponder[key] = v.refusesFirstResponder
                }
                v.refusesFirstResponder = true
            } else {
                val restored = savedRefusesFirstResponder.remove(key) ?: false
                v.refusesFirstResponder = restored
            }
        }
        for (sub in v.subviews) {
            if (sub is NSView) {
                applyControlsRecursive(sub, effective)
            }
        }
        if (v is NSScrollView) {
            val doc = v.documentView as? NSView
            if (doc != null && doc.superview != v) {
                applyControlsRecursive(doc, effective)
            }
        }
    }

    fun isResponderMatchingView(responder: NSResponder?, targetView: NSView): Boolean {
        if (responder == null) return false
        if (responder === targetView) return true
        if (responder is NSTextView) {
            val del = responder.delegate() as? NSView
            if (del === targetView) return true
        }
        return false
    }

    fun syncViewFirstResponder(
        targetView: NSView?,
        focused: Boolean,
        effectivelyFocusable: Boolean,
        enabled: Boolean
    ) {
        val effective = focused && enabled && effectivelyFocusable
        MacosAppKitDispatch.onMainOrDefer {
            val view = targetView ?: return@onMainOrDefer
            val win = view.window ?: return@onMainOrDefer
            val currentFr = win.firstResponder
            if (effective) {
                if (!isResponderMatchingView(currentFr, view) && view.acceptsFirstResponder()) {
                    win.makeFirstResponder(view)
                }
            } else if (isResponderMatchingView(currentFr, view)) {
                win.makeFirstResponder(null)
            }
        }
    }

    private fun isViewInSubtree(candidate: NSView, root: NSView): Boolean {
        var cur: NSView? = candidate
        while (cur != null) {
            if (cur == root) return true
            cur = cur.superview
        }
        return false
    }
}

internal fun handleOverlayTitleBarMouseDown(view: NSView, event: NSEvent): Boolean {
    val rootView = view.window?.contentView as? MacosWindowRootView
    if (rootView != null && rootView.isUndecoratedChrome && !rootView.isWindowInFullscreen()) {
        val (rx, ry) = rootView.convertPoint(event.locationInWindow, fromView = null).useContents { x to y }
        val (bw, bh) = rootView.bounds.useContents { size.width to size.height }
        val hit = rootView.hitTestClientPoint(rx, ry)
        val isControlHit = hit != null && hit !== rootView && rootView.isWindowControlHitViewInternal(hit)
        if (MacosNativeWindowHost.detectUndecoratedResizeEdge(rx, ry, bw, bh, isControlHit) !=
            MacosNativeWindowHost.UndecoratedResizeEdge.NONE
        ) {
            return false
        }
    }
    val localPt = view.convertPoint(event.locationInWindow, fromView = null)
    val ptInSuper = view.superview?.convertPoint(localPt, fromView = view) ?: localPt
    val hitTarget = view.hitTest(ptInSuper) ?: view
    if (!isWithinOverlayTitleBarNonControlArea(hitTarget)) {
        return false
    }
    if (event.clickCount >= 2L) {
        (view.window as? MacosHostWindow)?.handleTitleBarDoubleClick() ?: view.window?.performZoom(null)
        return true
    }
    val win = view.window ?: return false
    MacosNativeWindowHost.performWindowDragWithCursorLock(win, event)
    return true
}

internal fun isWithinOverlayTitleBarNonControlArea(view: NSView): Boolean {
    var cur: NSView? = view
    while (cur != null) {
        if (cur is NSButton || cur is MacosStyledButtonControl) {
            return false
        }
        if (cur is NSControl && !(cur is NSTextField && !cur.isEditable())) {
            return false
        }
        if (cur is NSTextView && cur.isEditable()) {
            return false
        }
        val handle = MacosFocusSupport.directHandleForView(cur)
        if (handle != null) {
            if (handle.attributes["data-window-control"] != null) {
                return false
            }
            val role = (handle.attributes[DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE] as? String)
                ?.trim()
                ?.lowercase()
            if (role == DesktopStageOverlaySpec.ROLE_WINDOW_TITLE_BAR) {
                val activeAttr = (handle.attributes["data-titlebar-active"] as? String)
                    ?.trim()
                    ?.lowercase()
                if (activeAttr == "false") return false
                if (!handle.visible || cur.isHidden() || cur.alphaValue <= 0.004) return false
                return true
            }
            if (role != null && role.startsWith("window-")) {
                return false
            }
            val impl = handle.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
            if (impl != null && (impl.hasClickListener() || (impl is MacosTextNativeImpl && impl.isLinkTag()))) {
                return false
            }
        }
        cur = cur.superview
    }
    return false
}
