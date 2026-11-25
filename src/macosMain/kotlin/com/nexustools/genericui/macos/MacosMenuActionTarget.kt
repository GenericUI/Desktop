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

    private val actionsByTag = HashMap<Long, () -> Unit>(64)
    private var nextTag: Long = 1L

    fun bindAction(item: NSMenuItem, action: () -> Unit) {
        val tag = nextTag++
        item.setTag(tag)
        actionsByTag[tag] = action
    }

    fun clearActions() {
        actionsByTag.clear()
    }

    fun boundActionCount(): Int = actionsByTag.size

    @ObjCAction
    fun onMenuItemTriggered(sender: NSObject?) {
        val menuItem = sender as? NSMenuItem ?: return
        val tag = menuItem.tag.toLong()
        if (tag <= 0L) return
        actionsByTag[tag]?.invoke()
    }

    @ObjCAction
    fun onEditCut(sender: NSObject?) {
        MacosNativeWindowHost.performTextCut()
    }

    @ObjCAction
    fun onEditCopy(sender: NSObject?) {
        MacosNativeWindowHost.performTextCopy()
    }

    @ObjCAction
    fun onEditPaste(sender: NSObject?) {
        MacosNativeWindowHost.performTextPaste()
    }

    @ObjCAction
    fun onEditSelectAll(sender: NSObject?) {
        MacosNativeWindowHost.performTextSelectAll()
    }

    @ObjCAction
    fun onAppQuit(sender: NSObject?) {
        MacosNativeWindowHost.requestApplicationQuit()
    }
}
