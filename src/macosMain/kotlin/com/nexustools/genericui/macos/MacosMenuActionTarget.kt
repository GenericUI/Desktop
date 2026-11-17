@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.nexustools.genericui.macos

import kotlinx.cinterop.ObjCAction
import platform.AppKit.NSMenuItem
import platform.darwin.NSObject

/**
 * Objective-C action target bridging AppKit `NSMenuItem` clicks (`#selector`) to Kotlin callbacks
 * and standard application/text-editing commands.
 */
internal class MacosMenuActionTarget : NSObject() {

    private val actionsByItem = LinkedHashMap<NSMenuItem, () -> Unit>(64)

    fun bindAction(item: NSMenuItem, action: () -> Unit) {
        actionsByItem[item] = action
    }

    fun clearActions() {
        actionsByItem.clear()
    }

    @ObjCAction
    fun onMenuItemTriggered(sender: NSMenuItem?) {
        if (sender == null) return
        actionsByItem[sender]?.invoke()
    }

    @ObjCAction
    fun onEditCut(sender: NSMenuItem?) {
        MacosNativeWindowHost.performTextCut()
    }

    @ObjCAction
    fun onEditCopy(sender: NSMenuItem?) {
        MacosNativeWindowHost.performTextCopy()
    }

    @ObjCAction
    fun onEditPaste(sender: NSMenuItem?) {
        MacosNativeWindowHost.performTextPaste()
    }

    @ObjCAction
    fun onEditSelectAll(sender: NSMenuItem?) {
        MacosNativeWindowHost.performTextSelectAll()
    }

    @ObjCAction
    fun onAppQuit(sender: NSMenuItem?) {
        MacosNativeWindowHost.requestApplicationQuit()
    }
}
