@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import platform.AppKit.NSWindow
import platform.AppKit.NSWindowDelegateProtocol
import platform.Foundation.NSNotification
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

    override fun windowDidResize(notification: NSNotification) {
        MacosNativeWindowHost.onNativeWindowDidResize(windowKey, isDialog)
    }

    override fun windowDidBecomeKey(notification: NSNotification) {
        MacosNativeWindowHost.onNativeWindowFocusChanged(windowKey, isDialog, focused = true)
    }

    override fun windowDidResignKey(notification: NSNotification) {
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
