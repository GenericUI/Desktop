@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.apple.AppleSvgCoreGraphicsRasterizer
import com.nexustools.genericui.desktop.system.DesktopSystemTrayNativeImpl
import com.nexustools.genericui.image.ImageDecodeOptions
import com.nexustools.genericui.image.ImageLoader
import com.nexustools.genericui.image.ImageSource
import com.nexustools.genericui.image.codec.LibPngCodec
import com.nexustools.genericui.nativeimpl.shell.ContextMenuNativeImpl
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.AppKit.NSApplication
import platform.AppKit.currentEvent
import platform.AppKit.NSEventMaskLeftMouseUp
import platform.AppKit.NSEventMaskRightMouseUp
import platform.AppKit.NSEventTypeRightMouseUp
import platform.AppKit.NSImage
import platform.AppKit.NSMenu
import platform.AppKit.NSStatusBar
import platform.AppKit.NSStatusItem
import platform.AppKit.NSVariableStatusItemLength
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSMakeSize
import platform.Foundation.NSSelectorFromString
import platform.Foundation.NSThread
import platform.Foundation.dataWithBytes
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * macOS menu bar status item implementation using `NSStatusBar.systemStatusBar`, [NSStatusItem],
 * and [MacosMenuBarBridge].
 */
class MacosSystemTrayNativeImpl : DesktopSystemTrayNativeImpl(
    isSupported = true
) {
    private var statusItem: NSStatusItem? = null
    private var attachedMenu: NSMenu? = null
    private val clickTarget = MacosTrayClickTarget { handleStatusItemClicked() }

    override fun setIcon(iconPathOrUrl: String?) {
        super.setIcon(iconPathOrUrl)
        scheduleSyncNativeStatusItem()
    }

    override fun setTooltip(tooltip: String?) {
        super.setTooltip(tooltip)
        scheduleSyncNativeStatusItem()
    }

    override fun setBadgeText(badge: String?) {
        super.setBadgeText(badge)
        scheduleSyncNativeStatusItem()
    }

    override fun setContextMenu(menuImpl: ContextMenuNativeImpl<*>?) {
        super.setContextMenu(menuImpl)
        scheduleSyncNativeStatusItem()
    }

    override fun setOnTrayIconClicked(listener: (() -> Unit)?) {
        super.setOnTrayIconClicked(listener)
        scheduleSyncNativeStatusItem()
    }

    override fun dispose() {
        super.dispose()
        if (!MacosApplicationNativeImpl.interactiveMacosGuiEnabled) {
            statusItem = null
            attachedMenu = null
            return
        }
        val itemToRemove = statusItem ?: return
        statusItem = null
        attachedMenu = null
        val cleanup = {
            itemToRemove.menu = null
            itemToRemove.button?.target = null
            NSStatusBar.systemStatusBar.removeStatusItem(itemToRemove)
        }
        if (NSThread.isMainThread) {
            cleanup()
        } else {
            dispatch_async(dispatch_get_main_queue()) { cleanup() }
        }
    }

    private fun scheduleSyncNativeStatusItem() {
        if (!MacosApplicationNativeImpl.interactiveMacosGuiEnabled || isDisposed) return
        if (NSThread.isMainThread) {
            syncNativeStatusItem()
        } else {
            dispatch_async(dispatch_get_main_queue()) {
                if (!isDisposed) {
                    syncNativeStatusItem()
                }
            }
        }
    }

    private fun syncNativeStatusItem() {
        val item = statusItem ?: NSStatusBar.systemStatusBar.statusItemWithLength(
            NSVariableStatusItemLength
        ).also { statusItem = it }

        val button = item.button
        val trayImage = resolveTrayNsImage(iconPath)
        if (trayImage != null) {
            trayImage.setSize(NSMakeSize(18.0, 18.0))
            trayImage.setTemplate(true)
            button?.image = trayImage
        } else {
            button?.image = null
        }

        button?.toolTip = tooltipText
        val badge = badgeText?.trim().orEmpty()
        button?.title = when {
            badge.isNotEmpty() && trayImage != null -> " $badge"
            badge.isNotEmpty() -> badge
            trayImage == null -> tooltipText?.takeIf { it.isNotBlank() }?.take(16) ?: "App"
            else -> ""
        }

        val nsMenu = MacosMenuBarBridge.buildContextMenu(contextMenu)
        attachedMenu = nsMenu
        if (onClickedListener == null) {
            item.menu = nsMenu
            button?.target = null
            button?.action = null
        } else {
            item.menu = null
            button?.target = clickTarget
            button?.action = NSSelectorFromString("onStatusItemActivated:")
            button?.sendActionOn(NSEventMaskLeftMouseUp or NSEventMaskRightMouseUp)
        }
        item.visible = true
    }

    private fun handleStatusItemClicked() {
        if (isDisposed) return
        val currentEvent = NSApplication.sharedApplication.currentEvent()
        val menu = attachedMenu
        val button = statusItem?.button
        if (currentEvent != null && currentEvent.type == NSEventTypeRightMouseUp && menu != null && button != null) {
            NSMenu.popUpContextMenu(menu, withEvent = currentEvent, forView = button)
            return
        }
        val listener = onClickedListener ?: return
        GenericUiThreading.runOnLogic {
            listener.invoke()
        }
    }

    private fun resolveTrayNsImage(pathOrUrl: String?): NSImage? {
        val raw = pathOrUrl?.trim().orEmpty()
        if (raw.isEmpty()) return null

        val bundlePath = NSBundle.mainBundle.pathForResource(raw.substringBeforeLast('.'), ofType = raw.substringAfterLast('.', ""))
        if (bundlePath != null && NSFileManager.defaultManager.fileExistsAtPath(bundlePath)) {
            NSImage(contentsOfFile = bundlePath)?.let { return it }
        }
        if (NSFileManager.defaultManager.fileExistsAtPath(raw)) {
            NSImage(contentsOfFile = raw)?.let { return it }
        }
        val buffer = when {
            raw.lowercase().endsWith(".svg") -> {
                val diskUrl = raw.removePrefix("file://")
                NSFileManager.defaultManager.contentsAtPath(diskUrl)?.let { data ->
                    val bytes = ByteArray(data.length.toInt())
                    if (bytes.isNotEmpty() && data.bytes != null) {
                        bytes.usePinned { pinned ->
                            platform.posix.memcpy(pinned.addressOf(0), data.bytes, data.length)
                        }
                    }
                    AppleSvgCoreGraphicsRasterizer.rasterize(
                        bytes,
                        ImageDecodeOptions(targetWidth = 36, targetHeight = 36, antialiasing = true)
                    )
                }
            }
            else -> {
                val source = when {
                    raw.startsWith("http://", ignoreCase = true) ||
                        raw.startsWith("https://", ignoreCase = true) ||
                        raw.startsWith("file://", ignoreCase = true) -> ImageSource.fromUrl(raw)
                    raw.startsWith("res/", ignoreCase = true) ||
                        raw.startsWith("drawable/", ignoreCase = true) -> ImageSource.fromResource(raw)
                    else -> ImageSource.fromFile(raw)
                }
                ImageLoader.activeNativeCodec?.decode(
                    source,
                    ImageDecodeOptions(targetWidth = 36, targetHeight = 36, antialiasing = true)
                )
            }
        } ?: return null
        val pngBytes = LibPngCodec.encode(buffer)
        if (pngBytes.isEmpty()) return null
        val data = pngBytes.usePinned { pinned ->
            NSData.dataWithBytes(pinned.addressOf(0), pngBytes.size.toULong())
        }
        return NSImage(data = data)
    }

    private class MacosTrayClickTarget(
        private val onActivated: () -> Unit
    ) : NSObject() {
        @ObjCAction
        fun onStatusItemActivated(sender: NSObject?) {
            onActivated()
        }
    }
}
