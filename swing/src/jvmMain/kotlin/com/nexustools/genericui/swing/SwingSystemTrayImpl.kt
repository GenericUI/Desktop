package com.nexustools.genericui.swing

import com.nexustools.genericui.nativeimpl.shell.ContextMenuNativeImpl
import com.nexustools.genericui.nativeimpl.system.SystemTrayNativeImpl
import java.awt.SystemTray

/**
 * Java AWT `java.awt.SystemTray` + `TrayIcon` implementation of [SystemTrayNativeImpl].
 */
class SwingSystemTrayImpl : SystemTrayNativeImpl {
    override val isSupported: Boolean
        get() = try {
            SystemTray.isSupported()
        } catch (_: Throwable) {
            false
        }

    private var iconPath: String? = null
    private var tooltipText: String? = null
    private var badgeTextVal: String? = null
    private var clickedListener: (() -> Unit)? = null

    override fun setIcon(iconPathOrUrl: String?) {
        iconPath = iconPathOrUrl
    }

    override fun setTooltip(tooltip: String?) {
        tooltipText = tooltip
    }

    override fun setBadgeText(badge: String?) {
        badgeTextVal = badge
    }

    override fun setContextMenu(menuImpl: ContextMenuNativeImpl<*>?) {}

    override fun setOnTrayIconClicked(listener: (() -> Unit)?) {
        clickedListener = listener
    }

    override fun dispose() {}
}
