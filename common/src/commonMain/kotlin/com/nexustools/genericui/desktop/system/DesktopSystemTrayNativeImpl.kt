package com.nexustools.genericui.desktop.system

import com.nexustools.genericui.nativeimpl.shell.ContextMenuNativeImpl
import com.nexustools.genericui.nativeimpl.system.SystemTrayNativeImpl
import kotlin.jvm.JvmName

/**
 * Shared desktop base implementation of [SystemTrayNativeImpl] for Win32 `Shell_NotifyIconW`,
 * macOS `NSStatusBar`, GTK/Linux `StatusNotifierItem`, and Qt6 `QSystemTrayIcon`.
 */
open class DesktopSystemTrayNativeImpl(
    override val isSupported: Boolean = true,
    val defaultIconPath: String = "icon.png"
) : SystemTrayNativeImpl {

    override var isDisposed: Boolean = false
        protected set

    var iconPath: String? = defaultIconPath
        protected set
    var tooltipText: String? = null
        protected set
    var badgeText: String? = null
        @JvmName("updateBadgeTextProperty")
        protected set
    var contextMenu: ContextMenuNativeImpl<*>? = null
        @JvmName("updateContextMenuProperty")
        protected set
    protected var onClickedListener: (() -> Unit)? = null

    override fun setIcon(iconPathOrUrl: String?) {
        iconPath = iconPathOrUrl?.takeIf { it.isNotBlank() } ?: defaultIconPath
    }

    override fun setTooltip(tooltip: String?) {
        tooltipText = tooltip
    }

    override fun setBadgeText(badge: String?) {
        badgeText = badge
    }

    override fun setContextMenu(menuImpl: ContextMenuNativeImpl<*>?) {
        contextMenu = menuImpl
    }

    override fun setOnTrayIconClicked(listener: (() -> Unit)?) {
        onClickedListener = listener
    }

    override fun dispose() {
        isDisposed = true
    }
}
