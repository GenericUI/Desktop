@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopContextMenuNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopMenuItemNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopMenuNativeImpl
import com.nexustools.genericui.desktop.system.DesktopSystemTrayNativeImpl
import com.nexustools.genericui.image.ImageDecodeOptions
import com.nexustools.genericui.image.ImageEncodeOptions
import com.nexustools.genericui.image.ImageFormat
import com.nexustools.genericui.image.ImageLoader
import com.nexustools.genericui.image.ImageSource
import com.nexustools.genericui.linux.LinuxReentrantLock
import com.nexustools.genericui.nativeimpl.layout.DividerNativeImpl
import com.nexustools.genericui.nativeimpl.shell.ContextMenuNativeImpl
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.COpaquePointer

/**
 * KDE Plasma / Qt6 system tray implementation using native KF6 **`KStatusNotifierItem`**
 * (`libKF6StatusNotifierItem.so.6` via D-Bus SNI protocol with zero mouse polling) and
 * automatic graceful fallback to **`QSystemTrayIcon`** when `KStatusNotifierItem` is absent.
 */
class KdeSystemTrayNativeImpl : DesktopSystemTrayNativeImpl(
    isSupported = true
) {

    private val lock = LinuxReentrantLock()
    private var sniPtr: COpaquePointer? = null
    private var qTrayPtr: COpaquePointer? = null
    private var qMenuPtr: COpaquePointer? = null
    private var lastIconPathOrUrl: String? = null
    private var usesKStatusNotifier: Boolean = false

    init {
        activeTrayInstances.add(this)
    }

    val isUsingKStatusNotifierItem: Boolean
        get() = usesKStatusNotifier

    override fun setIcon(iconPathOrUrl: String?) {
        super.setIcon(iconPathOrUrl)
        lastIconPathOrUrl = iconPathOrUrl
        syncTrayOnUi()
    }

    override fun setTooltip(tooltip: String?) {
        super.setTooltip(tooltip)
        syncTrayOnUi()
    }

    override fun setBadgeText(badge: String?) {
        super.setBadgeText(badge)
        syncTrayOnUi()
    }

    override fun setContextMenu(menuImpl: ContextMenuNativeImpl<*>?) {
        super.setContextMenu(menuImpl)
        syncTrayOnUi()
    }

    override fun setOnTrayIconClicked(listener: (() -> Unit)?) {
        super.setOnTrayIconClicked(listener)
        syncTrayOnUi()
    }

    private fun syncTrayOnUi() {
        if (isDisposed || !KdeApplicationNativeImpl.interactiveKdeGuiEnabled) return
        val runSync = {
            if (!isDisposed) {
                ensureNativeTrayCreatedLocked()
                applyStateToNativeTrayLocked()
            }
        }
        if (GenericUiThreading.isOnUiThread()) {
            runSync()
        } else {
            GenericUiThreading.runOnUi(runSync)
        }
    }

    private fun ensureNativeTrayCreatedLocked() {
        lock.withLock {
            if (sniPtr != null || qTrayPtr != null) return
            KdeDynamicBindings.ensureQApplication()
            val appName = GenericUI.applicationName.ifBlank { "genericui-kde" }
            val createdSni = KdeDynamicBindings.createKStatusNotifierItem(appName, null)
            if (createdSni != null) {
                sniPtr = createdSni
                usesKStatusNotifier = true
                KdeVTableBridge.connectFirstSignal(
                    createdSni,
                    "activateRequested(bool,QPoint)",
                    "secondaryActivateRequested(QPoint)"
                ) {
                    GenericUiThreading.runOnLogic {
                        onClickedListener?.invoke()
                    }
                }
            } else {
                val createdTray = KdeDynamicBindings.createQSystemTrayIcon(null)
                qTrayPtr = createdTray
                usesKStatusNotifier = false
                KdeVTableBridge.connectSignal(createdTray, "activated(QSystemTrayIcon::ActivationReason)") { args ->
                    val reason = KdeVTableBridge.readSignalArgInt(args, 1) ?: 0
                    // QSystemTrayIcon::Trigger = 3, DoubleClick = 2, MiddleClick = 4
                    if (reason == 3 || reason == 2 || reason == 4) {
                        GenericUiThreading.runOnLogic {
                            onClickedListener?.invoke()
                        }
                    }
                }
                KdeDynamicBindings.setQSystemTrayIconVisible(createdTray, true)
            }
        }
    }

    private fun applyStateToNativeTrayLocked() {
        val menuHandle = rebuildQMenuLocked()
        val tip = buildString {
            val base = tooltipText?.takeIf { it.isNotBlank() } ?: GenericUI.applicationName
            append(base)
            badgeText?.takeIf { it.isNotBlank() }?.let { b ->
                append(" ($b)")
            }
        }
        val iconSource = lastIconPathOrUrl
        val pngBytes = resolveTrayIconPngBytes(iconSource)

        val sni = lock.withLock { sniPtr }
        if (sni != null && KdeVTableBridge.isPeerAlive(sni)) {
            KdeDynamicBindings.updateKStatusNotifierItem(
                sni = sni,
                title = badgeText?.takeIf { it.isNotBlank() } ?: GenericUI.applicationName,
                tooltip = tip,
                iconName = if (pngBytes == null && !iconSource.isNullOrBlank() && !iconSource.contains('/')) iconSource else null,
                menu = menuHandle,
                visible = true
            )
            if (pngBytes != null) {
                KdeDynamicBindings.setKStatusNotifierIconBytes(sni, pngBytes)
            }
            return
        }

        val qTray = lock.withLock { qTrayPtr }
        if (qTray != null && KdeVTableBridge.isPeerAlive(qTray)) {
            KdeDynamicBindings.setQSystemTrayIconTooltip(qTray, tip)
            KdeDynamicBindings.setQSystemTrayIconMenu(qTray, menuHandle)
            if (pngBytes != null || !iconSource.isNullOrBlank()) {
                KdeDynamicBindings.setQSystemTrayIconFileOrBytes(qTray, iconSource, pngBytes)
            }
            KdeDynamicBindings.setQSystemTrayIconVisible(qTray, true)
        }
    }

    private fun rebuildQMenuLocked(): COpaquePointer? {
        val menuImpl = contextMenu
        val existing = lock.withLock { qMenuPtr }
        if (menuImpl == null) return existing
        val menu = existing ?: KdeDynamicBindings.createQMenu(null).also { created ->
            lock.withLock { qMenuPtr = created }
        }
        KdeDynamicBindings.clearMenu(menu)
        populateMenuFromChildren(menu, (menuImpl as? DesktopContextMenuNativeImpl<*>)?.items.orEmpty())
        return menu
    }

    private fun populateMenuFromChildren(targetMenu: COpaquePointer, children: List<NativeImpl<*>>) {
        for (child in children) {
            when (child) {
                is DividerNativeImpl<*> -> KdeDynamicBindings.addMenuSeparator(targetMenu)
                is DesktopMenuNativeImpl<*> -> {
                    val sub = KdeDynamicBindings.createQMenu(targetMenu)
                    KdeDynamicBindings.setMenuTitle(sub, child.getLabel())
                    populateMenuFromChildren(sub, child.items)
                    KdeDynamicBindings.addSubmenu(targetMenu, sub)
                }
                is DesktopMenuItemNativeImpl<*> -> {
                    val action = KdeDynamicBindings.addMenuAction(
                        menu = targetMenu,
                        text = child.getLabel(),
                        checkable = child.checkable || child.radio,
                        checked = child.isChecked(),
                        enabled = child.isEnabled()
                    )
                    if (action != null) {
                        KdeVTableBridge.connectSignal(action, "triggered(bool)") {
                            GenericUiThreading.runOnLogic {
                                if (child is DesktopBaseNativeImpl<*> && child.isEnabled()) {
                                    child.dispatchClick()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun resolveTrayIconPngBytes(pathOrUrl: String?): ByteArray? {
        if (pathOrUrl.isNullOrBlank()) return null
        val src = when {
            pathOrUrl.startsWith("data:", ignoreCase = true) ||
                pathOrUrl.startsWith("http://", ignoreCase = true) ||
                pathOrUrl.startsWith("https://", ignoreCase = true) ||
                pathOrUrl.startsWith("file://", ignoreCase = true) -> ImageSource.fromUrl(pathOrUrl)
            pathOrUrl.startsWith("/") -> ImageSource.fromFile(pathOrUrl)
            else -> ImageSource.fromResource(pathOrUrl)
        }
        val decoded = runCatching {
            ImageLoader.activeNativeCodec?.decode(src, ImageDecodeOptions(targetWidth = 32, targetHeight = 32, antialiasing = true))
                ?: ImageLoader.load(src, ImageDecodeOptions(targetWidth = 32, targetHeight = 32, antialiasing = true))
        }.getOrNull() ?: return null
        return runCatching {
            ImageLoader.encode(decoded, ImageEncodeOptions(format = ImageFormat.PNG))
        }.getOrNull()
    }

    override fun dispose() {
        activeTrayInstances.remove(this)
        val (sni, tray, menu) = lock.withLock {
            val s = sniPtr
            val t = qTrayPtr
            val m = qMenuPtr
            sniPtr = null
            qTrayPtr = null
            qMenuPtr = null
            Triple(s, t, m)
        }
        val cleanup = {
            if (sni != null && KdeVTableBridge.isPeerAlive(sni)) {
                KdeVTableBridge.destroyObject(sni)
            }
            if (tray != null && KdeVTableBridge.isPeerAlive(tray)) {
                KdeDynamicBindings.setQSystemTrayIconVisible(tray, false)
                KdeVTableBridge.destroyObject(tray)
            }
            if (menu != null && KdeVTableBridge.isPeerAlive(menu)) {
                KdeVTableBridge.destroyObject(menu)
            }
        }
        if (GenericUiThreading.isOnUiThread()) {
            cleanup()
        } else if (KdeApplicationNativeImpl.interactiveKdeGuiEnabled) {
            GenericUiThreading.runOnUi(cleanup)
        }
        super.dispose()
    }

    companion object {
        private val activeTrayInstances = LinkedHashSet<KdeSystemTrayNativeImpl>()

        fun disposeAll() {
            val copy = activeTrayInstances.toList()
            for (tray in copy) {
                tray.dispose()
            }
        }
    }
}
