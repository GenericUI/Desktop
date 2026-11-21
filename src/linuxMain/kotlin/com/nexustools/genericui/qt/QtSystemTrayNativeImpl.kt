@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.qt

import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.shell.DesktopContextMenuNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopMenuItemNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopMenuNativeImpl
import com.nexustools.genericui.desktop.system.DesktopSystemTrayNativeImpl
import com.nexustools.genericui.image.ImageDecodeOptions
import com.nexustools.genericui.image.ImageLoader
import com.nexustools.genericui.image.ImageSource
import com.nexustools.genericui.linux.LinuxReentrantLock
import com.nexustools.genericui.nativeimpl.layout.DividerNativeImpl
import com.nexustools.genericui.nativeimpl.shell.ContextMenuNativeImpl
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.COpaquePointer
import platform.posix.F_OK
import platform.posix.access

/**
 * Qt 6 (`QSystemTrayIcon` + `QMenu` / `QAction`) implementation of
 * [com.nexustools.genericui.nativeimpl.system.SystemTrayNativeImpl].
 */
class QtSystemTrayNativeImpl : DesktopSystemTrayNativeImpl(
    isSupported = true
) {
    private val lock = LinuxReentrantLock()
    private var qSystemTrayIcon: COpaquePointer? = null
    private var rootQMenu: COpaquePointer? = null
    private val ownedSubmenus = mutableListOf<COpaquePointer>()
    private val ownedActions = mutableListOf<COpaquePointer>()
    private val actionBindings = mutableListOf<QtTrayActionBinding>()
    private var lastPopupAction: COpaquePointer? = null
    private var wasMousePressedInPopup: Boolean = false

    init {
        activeTrayInstances.add(this)
    }

    override fun setIcon(iconPathOrUrl: String?) {
        super.setIcon(iconPathOrUrl)
        syncNativeTrayIcon()
    }

    override fun setTooltip(tooltip: String?) {
        super.setTooltip(tooltip)
        syncNativeTooltip()
    }

    override fun setBadgeText(badge: String?) {
        super.setBadgeText(badge)
        syncNativeTooltip()
        syncNativeTrayIcon()
    }

    override fun setContextMenu(menuImpl: ContextMenuNativeImpl<*>?) {
        super.setContextMenu(menuImpl)
        val handle = ensureNativeTrayIfInteractive() ?: return
        syncContextMenuToHandle(handle)
    }

    override fun setOnTrayIconClicked(listener: (() -> Unit)?) {
        super.setOnTrayIconClicked(listener)
        ensureNativeTrayIfInteractive()
    }

    internal fun triggerTrayClicked() {
        onClickedListener?.invoke()
    }

    internal fun hasNativeHandle(): Boolean = lock.withLock { qSystemTrayIcon != null }

    override fun dispose() {
        activeTrayInstances.remove(this)
        super.dispose()
        val handle = lock.withLock {
            val h = qSystemTrayIcon
            qSystemTrayIcon = null
            h
        }
        if (handle != null) {
            Qt6DynamicBindings.setSystemTrayContextMenu(handle, null)
            destroyOwnedMenuObjects()
            Qt6DynamicBindings.setSystemTrayVisible(handle, false)
            Qt6DynamicBindings.destroySystemTrayIcon(handle)
        } else {
            destroyOwnedMenuObjects()
        }
        if (QtApplicationNativeImpl.interactiveQtGuiEnabled) {
            Qt6DynamicBindings.setQuitOnLastWindowClosed(!hasActiveTray())
        }
    }

    private fun destroyOwnedMenuObjects() {
        val (actionsToDestroy, submenusToDestroy, rootToDestroy) = lock.withLock {
            val actions = ownedActions.toList()
            ownedActions.clear()
            val submenus = ownedSubmenus.toList()
            ownedSubmenus.clear()
            val root = rootQMenu
            rootQMenu = null
            actionBindings.clear()
            lastPopupAction = null
            wasMousePressedInPopup = false
            Triple(actions, submenus, root)
        }
        // Destroy leaf QActions & child submenus before root QMenu (matches QtDialog/QWidget pattern).
        for (act in actionsToDestroy) {
            Qt6DynamicBindings.destroyAction(act)
        }
        for (sub in submenusToDestroy.asReversed()) {
            Qt6DynamicBindings.destroyMenu(sub)
        }
        if (rootToDestroy != null) {
            Qt6DynamicBindings.destroyMenu(rootToDestroy)
        }
    }

    private fun syncContextMenuToHandle(handle: COpaquePointer) {
        val desktopMenu = contextMenu as? DesktopContextMenuNativeImpl<*>
        if (desktopMenu == null) {
            Qt6DynamicBindings.setSystemTrayContextMenu(handle, null)
            destroyOwnedMenuObjects()
            return
        }
        val newRoot = Qt6DynamicBindings.createMenu(parent = null, title = null) ?: return
        val newSubmenus = mutableListOf<COpaquePointer>()
        val newActions = mutableListOf<COpaquePointer>()
        val newBindings = mutableListOf<QtTrayActionBinding>()

        fun populateMenu(targetMenu: COpaquePointer, parentWidget: DesktopNativeWidget) {
            for (childWidget in parentWidget.children) {
                val impl = childWidget.attributes["nativeImpl"]
                when {
                    impl is DesktopMenuNativeImpl<*> -> {
                        val sub = Qt6DynamicBindings.createMenu(
                            parent = null,
                            title = impl.getLabel().ifBlank { "Submenu" }
                        ) ?: continue
                        newSubmenus.add(sub)
                        populateMenu(sub, childWidget)
                        Qt6DynamicBindings.addSubmenuToMenu(targetMenu, sub)
                    }
                    impl is DesktopMenuItemNativeImpl<*> -> {
                        val label = impl.getLabel().ifBlank { "Item" }
                        val act = Qt6DynamicBindings.createAction(label) ?: continue
                        newActions.add(act)
                        Qt6DynamicBindings.setActionEnabled(act, impl.isEnabled())
                        if (impl.checkable || impl.radio) {
                            Qt6DynamicBindings.setActionCheckable(act, true)
                            Qt6DynamicBindings.setActionChecked(act, impl.isChecked())
                        }
                        Qt6DynamicBindings.addActionToMenu(targetMenu, act)
                        newBindings.add(
                            QtTrayActionBinding(
                                qAction = act,
                                impl = impl,
                                lastObservedChecked = impl.isChecked()
                            )
                        )
                    }
                    impl is DividerNativeImpl<*> || childWidget.attributes["isMenuSeparator"] == true -> {
                        Qt6DynamicBindings.addSeparatorToMenu(targetMenu)
                    }
                }
            }
        }

        populateMenu(newRoot, desktopMenu.nativeWidget)
        Qt6DynamicBindings.setSystemTrayContextMenu(handle, newRoot)
        destroyOwnedMenuObjects()
        lock.withLock {
            rootQMenu = newRoot
            ownedSubmenus.addAll(newSubmenus)
            ownedActions.addAll(newActions)
            actionBindings.addAll(newBindings)
        }
    }

    private fun pollTrayMenuState() {
        val (bindingsSnapshot, menusSnapshot) = lock.withLock {
            val bindings = actionBindings.toList()
            val menus = buildSet {
                rootQMenu?.let { add(it) }
                addAll(ownedSubmenus)
            }
            bindings to menus
        }
        if (bindingsSnapshot.isEmpty()) return

        for (binding in bindingsSnapshot) {
            if (binding.impl.checkable || binding.impl.radio) {
                val nowChecked = Qt6DynamicBindings.isActionChecked(binding.qAction)
                if (nowChecked != binding.lastObservedChecked) {
                    binding.lastObservedChecked = nowChecked
                    GenericUiThreading.runOnLogic {
                        binding.impl.dispatchClick()
                    }
                }
            }
        }

        val activePopup = Qt6DynamicBindings.activePopupWidget()
        if (activePopup != null && activePopup in menusSnapshot) {
            val hovered = Qt6DynamicBindings.menuActiveAction(activePopup)
            if (hovered != null) {
                lastPopupAction = hovered
            }
            val pressedNow = Qt6DynamicBindings.currentMouseButtonsMask() != 0u
            if (wasMousePressedInPopup && !pressedNow) {
                val triggeredAction = hovered ?: lastPopupAction
                lastPopupAction = null
                if (triggeredAction != null) {
                    val match = bindingsSnapshot.firstOrNull { it.qAction == triggeredAction }
                    if (match != null && !(match.impl.checkable || match.impl.radio)) {
                        GenericUiThreading.runOnLogic {
                            match.impl.dispatchClick()
                        }
                    }
                }
            }
            wasMousePressedInPopup = pressedNow
        } else {
            lastPopupAction = null
            wasMousePressedInPopup = false
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
        Qt6DynamicBindings.setQuitOnLastWindowClosed(false)
        applyIconToHandle(activeHandle)
        applyTooltipToHandle(activeHandle)
        syncContextMenuToHandle(activeHandle)
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
        val path = iconPath?.trim()?.takeIf { it.isNotEmpty() } ?: defaultIconPath
        if (access(path, F_OK) == 0 &&
            !path.endsWith(".svg", ignoreCase = true)
        ) {
            Qt6DynamicBindings.setSystemTrayIconFromPath(handle, path)
            return
        }
        val source = when {
            path.startsWith("http://", ignoreCase = true) ||
                path.startsWith("https://", ignoreCase = true) ||
                path.startsWith("file://", ignoreCase = true) -> ImageSource.fromUrl(path)
            access(path, F_OK) == 0 -> ImageSource.fromFile(path)
            else -> ImageSource.fromResource(path)
        }
        val buffer = ImageLoader.activeNativeCodec?.decode(
            source,
            ImageDecodeOptions(targetWidth = 32, targetHeight = 32, antialiasing = true)
        )
        if (buffer != null) {
            val rgba = buffer.asRgba8888()
            val argb = IntArray(rgba.width * rgba.height)
            val b = rgba.bytes
            for (p in argb.indices) {
                val base = p * 4
                if (base + 3 < b.size) {
                    val r = b[base].toInt() and 0xFF
                    val g = b[base + 1].toInt() and 0xFF
                    val bl = b[base + 2].toInt() and 0xFF
                    val a = b[base + 3].toInt() and 0xFF
                    argb[p] = (a shl 24) or (r shl 16) or (g shl 8) or bl
                }
            }
            Qt6DynamicBindings.setSystemTrayIconFromArgb(handle, argb, rgba.width, rgba.height)
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

    private class QtTrayActionBinding(
        val qAction: COpaquePointer,
        val impl: DesktopMenuItemNativeImpl<*>,
        var lastObservedChecked: Boolean
    )

    internal companion object {
        private val activeTrayInstances = mutableListOf<QtSystemTrayNativeImpl>()

        internal fun hasActiveTray(): Boolean =
            activeTrayInstances.any { !it.isDisposed }

        internal fun ensureAllActiveIfInteractive() {
            for (tray in activeTrayInstances.toList()) {
                if (!tray.isDisposed) {
                    tray.ensureNativeTrayIfInteractive()
                }
            }
        }

        internal fun pollActiveTrays() {
            for (tray in activeTrayInstances.toList()) {
                if (!tray.isDisposed) {
                    tray.pollTrayMenuState()
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
