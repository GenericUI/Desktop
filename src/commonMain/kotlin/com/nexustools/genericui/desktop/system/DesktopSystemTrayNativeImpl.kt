package com.nexustools.genericui.desktop.system

import com.nexustools.genericui.nativeimpl.shell.ContextMenuNativeImpl
import com.nexustools.genericui.nativeimpl.system.SystemTrayNativeImpl
import kotlin.jvm.JvmName

/**
 * Shared desktop base implementation of [SystemTrayNativeImpl].
 *
 * Subclasses providing native OS tray integration must override icon, tooltip, badge, context
 * menu, and click listener methods rather than inheriting silent no-op behavior.
 */
open class DesktopSystemTrayNativeImpl(
    override val isSupported: Boolean = false,
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
        throw UnsupportedOperationException("Desktop system tray requires a platform tray backend implementation.")
    }

    override fun setTooltip(tooltip: String?) {
        throw UnsupportedOperationException("Desktop system tray requires a platform tray backend implementation.")
    }

    override fun setBadgeText(badge: String?) {
        throw UnsupportedOperationException("Desktop system tray requires a platform tray backend implementation.")
    }

    override fun setContextMenu(menuImpl: ContextMenuNativeImpl<*>?) {
        throw UnsupportedOperationException("Desktop system tray requires a platform tray backend implementation.")
    }

    override fun setOnTrayIconClicked(listener: (() -> Unit)?) {
        throw UnsupportedOperationException("Desktop system tray requires a platform tray backend implementation.")
    }

    override fun dispose() {
        isDisposed = true
        contextMenu = null
        onClickedListener = null
    }
}
