@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import kotlinx.cinterop.CValue
import platform.AppKit.NSScreen
import platform.AppKit.NSWindow
import platform.AppKit.NSWindowDelegateProtocol
import platform.Foundation.NSNotification
import platform.Foundation.NSRect
import platform.darwin.NSObject

/**
 * AppKit `NSWindowDelegate` forwarding window resize, focus, fullscreen, and close lifecycle events
 * to [MacosNativeWindowHost].
 */
internal class MacosWindowDelegate(
    val windowKey: Long,
    val isDialog: Boolean
) : NSObject(), NSWindowDelegateProtocol {

    override fun windowShouldClose(sender: NSWindow): Boolean =
        MacosNativeWindowHost.onNativeWindowShouldClose(windowKey, isDialog)

    override fun windowWillClose(notification: NSNotification) {
        MacosNativeWindowHost.onNativeWindowWillClose(windowKey, isDialog)
    }

    override fun windowDidResize(notification: NSNotification) {
        MacosNativeWindowHost.onNativeWindowDidResize(windowKey, isDialog)
    }

    override fun windowWillStartLiveResize(notification: NSNotification) {
        MacosNativeWindowHost.onNativeWindowLiveResizeChanged(windowKey, active = true)
    }

    override fun windowDidEndLiveResize(notification: NSNotification) {
        MacosNativeWindowHost.onNativeWindowLiveResizeChanged(windowKey, active = false)
    }

    override fun windowDidMove(notification: NSNotification) {
        MacosNativeWindowHost.onNativeWindowDidMove(windowKey, isDialog)
    }

    override fun windowWillUseStandardFrame(window: NSWindow, defaultFrame: CValue<NSRect>): CValue<NSRect> {
        if ((window as? MacosHostWindow)?.isNativeOverlayTitleBarHostWindow == true) {
            return window.screen?.visibleFrame ?: NSScreen.mainScreen?.visibleFrame ?: defaultFrame
        }
        return defaultFrame
    }

    override fun windowShouldZoom(window: NSWindow, toFrame: CValue<NSRect>): Boolean {
        MacosNativeWindowHost.onNativeWindowShouldZoom(windowKey, isDialog, window)
        if ((window as? MacosHostWindow)?.let {
                it.isNativeOverlayTitleBarHostWindow && !it.isToolModal && !it.isPerformingNativeZoom && !window.isZoomed()
            } == true
        ) {
            window.suspendAspectRatioForNativeZoom()
        }
        return true
    }

    override fun windowDidMiniaturize(notification: NSNotification) {
        MacosNativeWindowHost.onNativeWindowMiniaturized(windowKey, isDialog, miniaturized = true)
    }

    override fun windowDidDeminiaturize(notification: NSNotification) {
        MacosNativeWindowHost.onNativeWindowMiniaturized(windowKey, isDialog, miniaturized = false)
    }

    override fun windowDidBecomeKey(notification: NSNotification) {
        MacosNativeWindowHost.onNativeWindowFocusChanged(windowKey, isDialog, focused = true)
    }

    override fun windowDidResignKey(notification: NSNotification) {
        MacosNativeWindowHost.onNativeWindowFocusChanged(windowKey, isDialog, focused = false)
    }

    override fun windowDidBecomeMain(notification: NSNotification) {
        MacosNativeWindowHost.onNativeWindowFocusChanged(windowKey, isDialog, focused = true)
    }

    override fun windowDidResignMain(notification: NSNotification) {
        MacosNativeWindowHost.onNativeWindowFocusChanged(windowKey, isDialog, focused = false)
    }

    override fun windowWillEnterFullScreen(notification: NSNotification) {
        MacosNativeWindowHost.onNativeWindowFullscreenWillChange(windowKey, isDialog, enteringFullscreen = true)
    }

    override fun windowDidEnterFullScreen(notification: NSNotification) {
        MacosNativeWindowHost.onNativeWindowFullscreenChanged(windowKey, isDialog, fullscreen = true)
    }

    override fun windowDidFailToEnterFullScreen(window: NSWindow) {
        MacosNativeWindowHost.onNativeWindowFullscreenFailed(windowKey, isDialog)
    }

    override fun windowWillExitFullScreen(notification: NSNotification) {
        MacosNativeWindowHost.onNativeWindowFullscreenWillChange(windowKey, isDialog, enteringFullscreen = false)
    }

    override fun windowDidExitFullScreen(notification: NSNotification) {
        MacosNativeWindowHost.onNativeWindowFullscreenChanged(windowKey, isDialog, fullscreen = false)
    }

    override fun windowDidFailToExitFullScreen(window: NSWindow) {
        MacosNativeWindowHost.onNativeWindowFullscreenFailed(windowKey, isDialog)
    }
}
