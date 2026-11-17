@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.qt

import com.nexustools.genericui.desktop.system.DesktopSystemTrayNativeImpl
import com.nexustools.genericui.linux.LinuxReentrantLock
import com.nexustools.genericui.nativeimpl.shell.ContextMenuNativeImpl
import kotlinx.cinterop.COpaquePointer
import platform.posix.F_OK
import platform.posix.access

/**
 * Qt 6 (`QSystemTrayIcon`) implementation of
 * [com.nexustools.genericui.nativeimpl.system.SystemTrayNativeImpl].
 */
class QtSystemTrayNativeImpl : DesktopSystemTrayNativeImpl(
    isSupported = true
) {
    private val lock = LinuxReentrantLock()
    private var qSystemTrayIcon: COpaquePointer? = null

    override fun setIcon(iconPathOrUrl: String?) {
        iconPath = iconPathOrUrl
        syncNativeTrayIcon()
    }

    override fun setTooltip(tooltip: String?) {
        tooltipText = tooltip
        syncNativeTooltip()
    }

    override fun setBadgeText(badge: String?) {
        badgeText = badge
        syncNativeTooltip()
        syncNativeTrayIcon()
    }

    override fun setContextMenu(menuImpl: ContextMenuNativeImpl<*>?) {
        contextMenu = menuImpl
        ensureNativeTrayIfInteractive()
    }

    override fun setOnTrayIconClicked(listener: (() -> Unit)?) {
        onClickedListener = listener
        ensureNativeTrayIfInteractive()
    }

    internal fun triggerTrayClicked() {
        onClickedListener?.invoke()
    }

    internal fun hasNativeHandle(): Boolean = lock.withLock { qSystemTrayIcon != null }

    override fun dispose() {
        super.dispose()
        val handle = lock.withLock {
            val h = qSystemTrayIcon
            qSystemTrayIcon = null
            h
        }
        if (handle != null) {
            Qt6DynamicBindings.setSystemTrayVisible(handle, false)
            Qt6DynamicBindings.destroySystemTrayIcon(handle)
        }
    }

    private fun ensureNativeTrayIfInteractive(): COpaquePointer? {
        if (isDisposed || !QtApplicationNativeImpl.interactiveQtGuiEnabled) return null
        if (!Qt6DynamicBindings.isSystemTrayAvailable()) return null
        val existing = lock.withLock { qSystemTrayIcon }
        if (existing != null) return existing
        val created = Qt6DynamicBindings.createSystemTrayIcon() ?: return null
        val activeHandle = lock.withLock {
            if (isDisposed) {
                Qt6DynamicBindings.destroySystemTrayIcon(created)
                return null
            }
            val current = qSystemTrayIcon
            if (current != null) {
                Qt6DynamicBindings.destroySystemTrayIcon(created)
                current
            } else {
                qSystemTrayIcon = created
                created
            }
        }
        applyIconToHandle(activeHandle)
        applyTooltipToHandle(activeHandle)
        Qt6DynamicBindings.setSystemTrayVisible(activeHandle, true)
        return activeHandle
    }

    private fun syncNativeTrayIcon() {
        val handle = ensureNativeTrayIfInteractive() ?: return
        applyIconToHandle(handle)
    }

    private fun syncNativeTooltip() {
        val handle = ensureNativeTrayIfInteractive() ?: return
        applyTooltipToHandle(handle)
    }

    private fun applyTooltipToHandle(handle: COpaquePointer) {
        val tip = tooltipText?.takeIf { it.isNotBlank() } ?: "GenericUI"
        val badge = badgeText?.takeIf { it.isNotBlank() }
        val fullTip = if (badge != null) "$tip [$badge]" else tip
        Qt6DynamicBindings.setSystemTrayTooltip(handle, fullTip)
    }

    private fun applyIconToHandle(handle: COpaquePointer) {
        val path = iconPath?.takeIf { it.isNotBlank() }
        if (path != null && access(path, F_OK) == 0) {
            Qt6DynamicBindings.setSystemTrayIconFromPath(handle, path)
            return
        }
        val size = 16
        val pixels = IntArray(size * size)
        val fillColor = if (badgeText.isNullOrBlank()) 0xFF0D6EFD.toInt() else 0xFFDC3545.toInt()
        for (y in 2 until (size - 2)) {
            for (x in 2 until (size - 2)) {
                pixels[y * size + x] = fillColor
            }
        }
        Qt6DynamicBindings.setSystemTrayIconFromArgb(handle, pixels, size, size)
    }
}
