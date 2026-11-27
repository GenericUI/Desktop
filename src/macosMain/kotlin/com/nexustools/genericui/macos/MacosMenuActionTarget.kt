@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.desktop.shell.DesktopMenuItemNativeImpl
import kotlinx.cinterop.ObjCAction
import platform.AppKit.NSControlStateValueOff
import platform.AppKit.NSControlStateValueOn
import platform.AppKit.NSMenuItem
import platform.AppKit.NSMenuItemValidationProtocol
import platform.darwin.NSObject

/**
 * Objective-C action target bridging AppKit `NSMenuItem` clicks (`#selector`) to Kotlin callbacks,
 * live `NSMenuItemValidationProtocol` state/title/enabled sync, and standard application/text-editing
 * commands.
 */
internal class MacosMenuActionTarget : NSObject(), NSMenuItemValidationProtocol {

    private val actionsByTag = HashMap<Long, () -> Unit>(64)
    private val itemImplsByTag = HashMap<Long, DesktopMenuItemNativeImpl<*>>(64)
    private var nextTag: Long = 1L

    fun bindAction(
        item: NSMenuItem,
        menuItemImpl: DesktopMenuItemNativeImpl<*>? = null,
        action: () -> Unit
    ) {
        val tag = nextTag++
        item.setTag(tag)
        actionsByTag[tag] = action
        if (menuItemImpl != null) {
            itemImplsByTag[tag] = menuItemImpl
        }
    }

    fun clearActions() {
        actionsByTag.clear()
        itemImplsByTag.clear()
    }

    fun boundActionCount(): Int = actionsByTag.size

    override fun validateMenuItem(menuItem: NSMenuItem): Boolean {
        val tag = menuItem.tag.toLong()
        val impl = if (tag > 0L) itemImplsByTag[tag] else null
        if (impl == null) {
            return true
        }
        val liveTitle = impl.getLabel()
        if (menuItem.title != liveTitle) {
            menuItem.title = liveTitle
        }
        if (impl.checkable) {
            menuItem.state = if (impl.isChecked()) NSControlStateValueOn else NSControlStateValueOff
        }
        return impl.isEnabled()
    }

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
    fun onAppAbout(sender: NSObject?) {
        MacosNativeWindowHost.showStandardAboutPanel()
    }

    @ObjCAction
    fun onAppQuit(sender: NSObject?) {
        MacosNativeWindowHost.requestApplicationQuit()
    }
}
