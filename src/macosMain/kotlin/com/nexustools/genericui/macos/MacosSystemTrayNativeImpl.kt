@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.apple.AppleDataBridge
import com.nexustools.genericui.apple.AppleSvgCoreGraphicsRasterizer
import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.shell.DesktopContextMenuNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopMenuItemNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopMenuNativeImpl
import com.nexustools.genericui.desktop.system.DesktopSystemTrayNativeImpl
import com.nexustools.genericui.desktop.system.DesktopSystemTraySilhouetteTransformer
import com.nexustools.genericui.desktop.system.DesktopSystemTrayTemplateAnalysis
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.image.ImageDecodeOptions
import com.nexustools.genericui.image.ImageLoader
import com.nexustools.genericui.image.ImageSource
import com.nexustools.genericui.image.codec.LibPngCodec
import com.nexustools.genericui.nativeimpl.layout.DividerNativeImpl
import com.nexustools.genericui.nativeimpl.shell.ContextMenuNativeImpl
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.AppKit.NSApplication
import platform.AppKit.NSBitmapImageRep
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
import kotlin.math.roundToInt

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
    private var explicitMacosIconPath: String? = null
    private val menuActionTarget = MacosMenuActionTarget()
    private val clickTarget = MacosTrayClickTarget { handleStatusItemClicked() }
    private val resourceResolver = MacosResourceResolverImpl()
    private var contextMenuDirty: Boolean = false
    private var lastSyncedContextMenu: ContextMenuNativeImpl<*>? = null
    private var lastSyncedMenuSignature: String? = null

    internal var menuRebuildCountForTesting: Int = 0
        private set

    internal val currentStatusItemForTesting: NSStatusItem?
        get() = statusItem

    internal val currentAttachedMenuForTesting: NSMenu?
        get() = attachedMenu

    internal val menuActionTargetForTesting: MacosMenuActionTarget
        get() = menuActionTarget

    init {
        activeTrayInstances.add(this)
        MacosNativeWindowHost.syncActivationPolicyForCurrentState()
    }

    override fun setIcon(iconPathOrUrl: String?) {
        super.setIcon(iconPathOrUrl)
        scheduleSyncNativeStatusItem(forceRebuildMenu = false)
    }

    override fun setMacosIcon(iconPathOrUrl: String?) {
        explicitMacosIconPath = iconPathOrUrl?.trim()?.takeIf { it.isNotEmpty() }
        scheduleSyncNativeStatusItem(forceRebuildMenu = false)
    }

    override fun setTooltip(tooltip: String?) {
        super.setTooltip(tooltip)
        scheduleSyncNativeStatusItem(forceRebuildMenu = false)
    }

    override fun setBadgeText(badge: String?) {
        super.setBadgeText(badge)
        scheduleSyncNativeStatusItem(forceRebuildMenu = false)
    }

    override fun setContextMenu(menuImpl: ContextMenuNativeImpl<*>?) {
        super.setContextMenu(menuImpl)
        contextMenuDirty = true
        scheduleSyncNativeStatusItem(forceRebuildMenu = true)
    }

    override fun setOnTrayIconClicked(listener: (() -> Unit)?) {
        super.setOnTrayIconClicked(listener)
        scheduleSyncNativeStatusItem(forceRebuildMenu = false)
    }

    override fun dispose() {
        activeTrayInstances.remove(this)
        super.dispose()
        menuActionTarget.clearActions()
        contextMenuDirty = false
        lastSyncedContextMenu = null
        lastSyncedMenuSignature = null
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
            MacosNativeWindowHost.onTrayDisposed(hasRemainingActiveTrays = hasActiveTrayIconsInProcess())
        }
        if (NSThread.isMainThread) {
            cleanup()
        } else {
            dispatch_async(dispatch_get_main_queue()) { cleanup() }
        }
    }

    private fun scheduleSyncNativeStatusItem(forceRebuildMenu: Boolean = false) {
        if (!MacosApplicationNativeImpl.interactiveMacosGuiEnabled || isDisposed) return
        if (NSThread.isMainThread) {
            syncNativeStatusItem(forceRebuildMenu = forceRebuildMenu)
        } else {
            dispatch_async(dispatch_get_main_queue()) {
                if (!isDisposed) {
                    syncNativeStatusItem(forceRebuildMenu = forceRebuildMenu)
                }
            }
        }
    }

    internal fun syncNativeStatusItem(forceRebuildMenu: Boolean = false) {
        val item = statusItem ?: NSStatusBar.systemStatusBar.statusItemWithLength(
            NSVariableStatusItemLength
        ).also { statusItem = it }

        val button = item.button
        val hasExplicitMacosIcon = explicitMacosIconPath != null
        val effectiveIconPath = explicitMacosIconPath ?: iconPath
        val resolvedTray = resolveTrayNsImageAndTemplate(
            pathOrUrl = effectiveIconPath,
            autoTransformToSilhouette = !hasExplicitMacosIcon
        )
        val trayImage = resolvedTray?.first
        if (trayImage != null) {
            val currentSize = trayImage.size.useContents { width to height }
            if (currentSize.first <= 0.0 || currentSize.second <= 0.0 ||
                currentSize.first > TRAY_POINT_SIZE.toDouble() ||
                currentSize.second > TRAY_POINT_SIZE.toDouble()
            ) {
                trayImage.setSize(NSMakeSize(TRAY_POINT_SIZE.toDouble(), TRAY_POINT_SIZE.toDouble()))
            }
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

        val currentContextMenu = contextMenu
        val currentMenuSignature = computeContextMenuSignature(currentContextMenu)
        val shouldRebuildMenu = forceRebuildMenu ||
            contextMenuDirty ||
            currentContextMenu !== lastSyncedContextMenu ||
            currentMenuSignature != lastSyncedMenuSignature

        if (shouldRebuildMenu) {
            menuRebuildCountForTesting++
            contextMenuDirty = false
            lastSyncedContextMenu = currentContextMenu
            lastSyncedMenuSignature = currentMenuSignature
            val nsMenu = MacosMenuBarBridge.buildContextMenu(currentContextMenu, menuActionTarget)
            attachedMenu = nsMenu
            if (nsMenu != null) {
                item.menu = nsMenu
                button?.target = null
                button?.action = null
            } else {
                syncButtonClickTarget(item, button)
            }
        } else if (attachedMenu == null) {
            syncButtonClickTarget(item, button)
        }
        item.visible = true
    }

    private fun syncButtonClickTarget(item: NSStatusItem, button: platform.AppKit.NSStatusBarButton?) {
        if (onClickedListener != null) {
            if (item.menu != null) {
                item.menu = null
            }
            button?.target = clickTarget
            button?.action = NSSelectorFromString("onStatusItemActivated:")
            button?.sendActionOn(NSEventMaskLeftMouseUp or NSEventMaskRightMouseUp)
        } else {
            if (item.menu != null) {
                item.menu = null
            }
            button?.target = null
            button?.action = null
        }
    }

    private fun computeContextMenuSignature(menuImpl: ContextMenuNativeImpl<*>?): String? {
        val desktopMenu = menuImpl as? DesktopContextMenuNativeImpl<*> ?: return null
        val sb = StringBuilder(128)
        appendMenuItemsSignature(sb, desktopMenu.items)
        return sb.toString()
    }

    private fun appendMenuItemsSignature(sb: StringBuilder, children: List<NativeImpl<*>>) {
        sb.append('[')
        for (child in children) {
            when (child) {
                is DividerNativeImpl<*> -> sb.append("sep;")
                is DesktopMenuNativeImpl<*> -> {
                    sb.append("sub:")
                        .append(child.nativeWidget.nativePointer).append(':')
                        .append(child.isVisible()).append(':')
                        .append(child.isEnabled()).append(':')
                        .append(child.getLabel())
                    appendMenuItemsSignature(sb, child.items)
                    sb.append(';')
                }
                is DesktopMenuItemNativeImpl<*> -> {
                    sb.append("item:")
                        .append(child.nativeWidget.nativePointer).append(':')
                        .append(child.isVisible()).append(':')
                        .append(child.isEnabled()).append(':')
                        .append(child.checkable).append(':')
                        .append(child.radio).append(':')
                        .append(child.isChecked()).append(':')
                        .append(child.getShortcut()).append(':')
                        .append(child.getLabel())
                        .append(';')
                }
            }
        }
        sb.append(']')
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

    private fun resolveTrayNsImageAndTemplate(
        pathOrUrl: String?,
        autoTransformToSilhouette: Boolean
    ): Pair<NSImage, Boolean>? {
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
                    convertBufferToTrayPair(decoded, autoTransformToSilhouette)?.let { return it }
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
                    ImageDecodeOptions(
                        targetWidth = TRAY_SUPERSAMPLE_SIZE,
                        targetHeight = TRAY_SUPERSAMPLE_SIZE,
                        antialiasing = true
                    )
                )
            }
        }

        if (decodedBuffer != null) {
            return convertBufferToTrayPair(decodedBuffer, autoTransformToSilhouette)
        }

        if (diskCandidate != null) {
            NSImage(contentsOfFile = diskCandidate)?.let { return it to false }
        }
        return null
    }

    private fun convertBufferToTrayPair(
        buffer: ImageBuffer,
        autoTransformToSilhouette: Boolean
    ): Pair<NSImage, Boolean>? {
        val processed = if (autoTransformToSilhouette) {
            DesktopSystemTraySilhouetteTransformer.toMacosTemplateSilhouette(buffer)
        } else {
            buffer
        }
        val (w2x, h2x) = aspectFitDimensions(processed.width, processed.height, TRAY_RETINA_2X_SIZE)
        val (w1x, h1x) = aspectFitDimensions(processed.width, processed.height, TRAY_POINT_SIZE)
        val buf2x = DesktopSystemTraySilhouetteTransformer.downsampleAreaBox(processed, w2x, h2x)
        val buf1x = DesktopSystemTraySilhouetteTransformer.downsampleAreaBox(processed, w1x, h1x)

        val pointW = w1x.toDouble()
        val pointH = h1x.toDouble()
        val rep1x = imageBufferToBitmapRep(buf1x, pointW, pointH)
        val rep2x = imageBufferToBitmapRep(buf2x, pointW, pointH)
        val nsImg = if (rep1x != null || rep2x != null) {
            NSImage(size = NSMakeSize(pointW, pointH)).apply {
                if (rep1x != null) addRepresentation(rep1x)
                if (rep2x != null) addRepresentation(rep2x)
            }
        } else {
            imageBufferToNsImage(buf2x)
        } ?: return null

        val isTemplate = if (autoTransformToSilhouette) {
            true
        } else {
            DesktopSystemTrayTemplateAnalysis.shouldTreatAsTemplateImage(buf2x)
        }
        return nsImg to isTemplate
    }

    private fun aspectFitDimensions(srcW: Int, srcH: Int, maxDim: Int): Pair<Int, Int> {
        if (srcW <= 0 || srcH <= 0 || srcW == srcH) return maxDim to maxDim
        return if (srcW > srcH) {
            maxDim to (srcH.toDouble() * maxDim / srcW.toDouble()).roundToInt().coerceIn(1, maxDim)
        } else {
            (srcW.toDouble() * maxDim / srcH.toDouble()).roundToInt().coerceIn(1, maxDim) to maxDim
        }
    }

    private fun decodeBytesToBuffer(bytes: ByteArray, isSvg: Boolean): ImageBuffer? {
        if (isSvg) {
            return AppleSvgCoreGraphicsRasterizer.rasterize(
                bytes,
                ImageDecodeOptions(
                    targetWidth = TRAY_SUPERSAMPLE_SIZE,
                    targetHeight = TRAY_SUPERSAMPLE_SIZE,
                    antialiasing = true
                )
            )
        }
        return ImageLoader.activeNativeCodec?.decode(
            ImageSource.fromBytes(bytes),
            ImageDecodeOptions(antialiasing = true)
        ) ?: runCatching { LibPngCodec.decode(bytes) }.getOrNull()
    }

    private fun imageBufferToBitmapRep(buffer: ImageBuffer, pointW: Double, pointH: Double): NSBitmapImageRep? {
        val pngBytes = LibPngCodec.encode(buffer)
        if (pngBytes.isEmpty()) return null
        val data = pngBytes.usePinned { pinned ->
            NSData.dataWithBytes(pinned.addressOf(0), pngBytes.size.toULong())
        }
        val rep = NSBitmapImageRep.imageRepWithData(data) as? NSBitmapImageRep ?: return null
        rep.setSize(NSMakeSize(pointW, pointH))
        return rep
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
        private const val TRAY_POINT_SIZE = 18
        private const val TRAY_RETINA_2X_SIZE = 36
        private const val TRAY_SUPERSAMPLE_SIZE = 144

        private val activeTrayInstances = mutableListOf<MacosSystemTrayNativeImpl>()

        internal fun hasActiveTrayIconsInProcess(): Boolean =
            activeTrayInstances.any { !it.isDisposed }

        internal fun hasActiveTray(): Boolean =
            hasActiveTrayIconsInProcess()

        internal fun activeTraysSnapshotForTesting(): List<MacosSystemTrayNativeImpl> =
            activeTrayInstances.toList()

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
