@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.apple.AppleDataBridge
import com.nexustools.genericui.apple.AppleSvgCoreGraphicsRasterizer
import com.nexustools.genericui.desktop.system.DesktopSystemTrayNativeImpl
import com.nexustools.genericui.desktop.system.DesktopSystemTrayTemplateAnalysis
import com.nexustools.genericui.image.ImageBuffer
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
import platform.Foundation.dataWithContentsOfFile
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * macOS menu bar status item implementation using `NSStatusBar.systemStatusBar`, [NSStatusItem],
 * and [MacosMenuBarBridge].
 */
class MacosSystemTrayNativeImpl : DesktopSystemTrayNativeImpl(
    isSupported = true,
    defaultIconPath = "tray_icon.png"
) {
    private var statusItem: NSStatusItem? = null
    private var attachedMenu: NSMenu? = null
    private val clickTarget = MacosTrayClickTarget { handleStatusItemClicked() }
    private val resourceResolver = MacosResourceResolverImpl()

    init {
        activeTrayInstances.add(this)
        MacosNativeWindowHost.syncActivationPolicyForCurrentState()
    }

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
        activeTrayInstances.remove(this)
        super.dispose()
        if (!MacosApplicationNativeImpl.interactiveMacosGuiEnabled) {
            statusItem = null
            attachedMenu = null
            return
        }
        val itemToRemove = statusItem
        statusItem = null
        attachedMenu = null
        val cleanup = {
            if (itemToRemove != null) {
                itemToRemove.menu = null
                itemToRemove.button?.target = null
                NSStatusBar.systemStatusBar.removeStatusItem(itemToRemove)
            }
            MacosNativeWindowHost.onTrayDisposed(hasRemainingActiveTrays = hasActiveTray())
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
        val resolvedTray = resolveTrayNsImageAndTemplate(iconPath)
        val trayImage = resolvedTray?.first
        if (trayImage != null) {
            trayImage.setSize(NSMakeSize(18.0, 18.0))
            trayImage.setTemplate(resolvedTray.second)
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

    private fun resolveTrayNsImageAndTemplate(pathOrUrl: String?): Pair<NSImage, Boolean>? {
        val raw = pathOrUrl?.trim()?.takeIf { it.isNotEmpty() } ?: "tray_icon.png"

        val isRemoteOrFileUrl = raw.startsWith("http://", ignoreCase = true) ||
            raw.startsWith("https://", ignoreCase = true) ||
            raw.startsWith("file://", ignoreCase = true)

        if (!isRemoteOrFileUrl) {
            val cleanRel = raw
                .replace('\\', '/')
                .trimStart('/')
                .removePrefix("app/")
                .removePrefix("gui/")
                .removePrefix("res/")
            val exactKey = cleanRel
                .replace('/', '_')
                .replace('-', '_')
                .replace('.', '_')
                .replace(Regex("_+"), "_")
                .trim('_')

            val candidateKeys = buildList {
                if (cleanRel.equals("tray_icon.png", ignoreCase = true) ||
                    cleanRel.equals("icon.png", ignoreCase = true)
                ) {
                    add("tray_icon_svg")
                    if (exactKey.isNotEmpty()) add(exactKey)
                    add("tray_icon_png")
                } else {
                    if (exactKey.isNotEmpty()) add(exactKey)
                    if (!cleanRel.contains('/')) {
                        add("drawable_${exactKey}")
                    }
                }
            }.distinct()

            for (key in candidateKeys) {
                val embeddedBytes = resourceResolver.lookupEmbeddedAppBytes(key) ?: continue
                if (embeddedBytes.isEmpty()) continue
                val isSvg = key.endsWith("_svg", ignoreCase = true) || isLikelySvgBytes(embeddedBytes)
                val decoded = decodeBytesToBuffer(embeddedBytes, isSvg)
                if (decoded != null) {
                    val nsImg = imageBufferToNsImage(decoded)
                    if (nsImg != null) {
                        val isTemplate = DesktopSystemTrayTemplateAnalysis.shouldTreatAsTemplateImage(decoded)
                        return nsImg to isTemplate
                    }
                }
            }
        }

        val bundlePath = NSBundle.mainBundle.pathForResource(
            raw.substringBeforeLast('.'),
            ofType = raw.substringAfterLast('.', "")
        )
        val diskCandidate = when {
            bundlePath != null && NSFileManager.defaultManager.fileExistsAtPath(bundlePath) -> bundlePath
            NSFileManager.defaultManager.fileExistsAtPath(raw) -> raw
            raw.startsWith("file://", ignoreCase = true) -> {
                raw.removePrefix("file://").takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }
            }
            else -> null
        }

        val decodedBuffer: ImageBuffer? = when {
            diskCandidate != null -> {
                val nsData = NSData.dataWithContentsOfFile(diskCandidate)
                val bytes = nsData?.let { AppleDataBridge.nsDataToByteArray(it) }
                if (bytes != null && bytes.isNotEmpty()) {
                    val isSvg = diskCandidate.lowercase().endsWith(".svg") || isLikelySvgBytes(bytes)
                    decodeBytesToBuffer(bytes, isSvg)
                } else {
                    null
                }
            }
            else -> {
                val source = when {
                    isRemoteOrFileUrl -> ImageSource.fromUrl(raw)
                    else -> ImageSource.fromResource(raw)
                }
                ImageLoader.activeNativeCodec?.decode(
                    source,
                    ImageDecodeOptions(targetWidth = 36, targetHeight = 36, antialiasing = true)
                )
            }
        }

        if (decodedBuffer != null) {
            val nsImg = imageBufferToNsImage(decodedBuffer) ?: return null
            val isTemplate = DesktopSystemTrayTemplateAnalysis.shouldTreatAsTemplateImage(decodedBuffer)
            return nsImg to isTemplate
        }

        if (diskCandidate != null) {
            NSImage(contentsOfFile = diskCandidate)?.let { return it to false }
        }
        return null
    }

    private fun decodeBytesToBuffer(bytes: ByteArray, isSvg: Boolean): ImageBuffer? {
        if (isSvg) {
            return AppleSvgCoreGraphicsRasterizer.rasterize(
                bytes,
                ImageDecodeOptions(targetWidth = 36, targetHeight = 36, antialiasing = true)
            )
        }
        return ImageLoader.activeNativeCodec?.decode(
            ImageSource.fromBytes(bytes),
            ImageDecodeOptions(targetWidth = 36, targetHeight = 36, antialiasing = true)
        ) ?: runCatching { LibPngCodec.decode(bytes) }.getOrNull()
    }

    private fun imageBufferToNsImage(buffer: ImageBuffer): NSImage? {
        val pngBytes = LibPngCodec.encode(buffer)
        if (pngBytes.isEmpty()) return null
        val data = pngBytes.usePinned { pinned ->
            NSData.dataWithBytes(pinned.addressOf(0), pngBytes.size.toULong())
        }
        return NSImage(data = data)
    }

    private fun isLikelySvgBytes(bytes: ByteArray): Boolean {
        val sample = bytes.decodeToString(0, minOf(bytes.size, 256)).trimStart().lowercase()
        return sample.startsWith("<svg") || (sample.startsWith("<?xml") && sample.contains("<svg"))
    }

    private class MacosTrayClickTarget(
        private val onActivated: () -> Unit
    ) : NSObject() {
        @ObjCAction
        fun onStatusItemActivated(sender: NSObject?) {
            onActivated()
        }
    }

    internal companion object {
        private val activeTrayInstances = mutableListOf<MacosSystemTrayNativeImpl>()

        internal fun hasActiveTray(): Boolean =
            activeTrayInstances.any { !it.isDisposed }

        internal fun syncAllTrayIcons() {
            for (tray in activeTrayInstances.toList()) {
                if (!tray.isDisposed) {
                    tray.scheduleSyncNativeStatusItem()
                }
            }
        }

        internal fun disposeAll() {
            for (tray in activeTrayInstances.toList()) {
                tray.dispose()
            }
        }
    }
}
