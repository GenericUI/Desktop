@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.desktop.shell.DesktopContextMenuNativeImpl
import com.nexustools.genericui.desktop.system.DesktopSystemTrayNativeImpl
import com.nexustools.genericui.nativeimpl.shell.ContextMenuNativeImpl
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import platform.posix.memset
import platform.windows.CreatePopupMenu
import platform.windows.DestroyIcon
import platform.windows.DestroyMenu
import platform.windows.GetCursorPos
import platform.windows.GetSystemMetrics
import platform.windows.HICON
import platform.windows.HWND
import platform.windows.IDI_APPLICATION
import platform.windows.IsWindow
import platform.windows.KillTimer
import platform.windows.LoadIconW
import platform.windows.NIF_ICON
import platform.windows.NIF_MESSAGE
import platform.windows.NIF_TIP
import platform.windows.NIM_ADD
import platform.windows.NIM_DELETE
import platform.windows.NIM_MODIFY
import platform.windows.NOTIFYICONDATAW
import platform.windows.POINT
import platform.windows.PostMessageW
import platform.windows.SM_CXSMICON
import platform.windows.SM_CYSMICON
import platform.windows.SetForegroundWindow
import platform.windows.SetTimer
import platform.windows.Shell_NotifyIconW
import platform.windows.TPM_NONOTIFY
import platform.windows.TPM_RETURNCMD
import platform.windows.TPM_RIGHTBUTTON
import platform.windows.TrackPopupMenu
import platform.windows.WCHARVar
import platform.windows.WM_CONTEXTMENU
import platform.windows.WM_LBUTTONUP
import platform.windows.WM_NULL
import platform.windows.WM_RBUTTONUP

/**
 * Windows system tray icon implementation using `Shell_NotifyIconW` (`NOTIFYICONDATAW`,
 * `NIM_ADD`/`NIM_MODIFY`/`NIM_DELETE`, `NIF_ICON`, `NIF_TIP`, `NIF_MESSAGE`) and native
 * Win32 `TrackPopupMenu`.
 */
class WindowsSystemTrayNativeImpl : DesktopSystemTrayNativeImpl(
    isSupported = true,
    defaultIconPath = "icon.ico"
) {
    private var registeredHwnd: HWND? = null
    private var ownedHIcon: HICON? = null
    private var installedIconAdded: Boolean = false
    private var retryAttempts: Int = 0

    init {
        activeTrayInstance = this
    }

    override fun setIcon(iconPathOrUrl: String?) {
        super.setIcon(iconPathOrUrl)
        syncNativeTrayIcon()
    }

    override fun setTooltip(tooltip: String?) {
        super.setTooltip(tooltip)
        syncNativeTrayIcon()
    }

    override fun setBadgeText(badge: String?) {
        super.setBadgeText(badge)
        syncNativeTrayIcon()
    }

    override fun setContextMenu(menuImpl: ContextMenuNativeImpl<*>?) {
        super.setContextMenu(menuImpl)
        syncNativeTrayIcon()
    }

    override fun dispose() {
        removeNativeTrayIcon()
        if (activeTrayInstance === this) {
            activeTrayInstance = null
        }
        super.dispose()
        if (!hasActiveTray()) {
            WindowsHeadlessTrayLoop.destroyTrayMessageHwnd()
        }
    }

    internal fun syncNativeTrayIcon(preferredHwnd: HWND? = null) {
        if (!WindowsApplicationNativeImpl.interactiveWin32GuiEnabled || isDisposed) return
        val hwnd = preferredHwnd
            ?: Win32NativeWindowHost.activeHwnd
            ?: WindowsHeadlessTrayLoop.ensureTrayMessageHwnd()
            ?: return
        val previousHwnd = registeredHwnd
        if (previousHwnd != null && previousHwnd != hwnd && IsWindow(previousHwnd) != 0) {
            KillTimer(previousHwnd, TRAY_RETRY_TIMER_ID.toULong())
            if (installedIconAdded) {
                memScoped {
                    val del = alloc<NOTIFYICONDATAW>()
                    memset(del.ptr, 0, sizeOf<NOTIFYICONDATAW>().toULong())
                    del.cbSize = sizeOf<NOTIFYICONDATAW>().toUInt()
                    del.hWnd = previousHwnd
                    del.uID = TRAY_ICON_UID
                    Shell_NotifyIconW(NIM_DELETE.toUInt(), del.ptr)
                }
            }
            installedIconAdded = false
            registeredHwnd = null
        }
        val prevOwned = ownedHIcon
        val (newIcon, ownsNew) = resolveTrayHIcon(iconPath)
        val tipText = buildString {
            val base = tooltipText?.trim().orEmpty()
            val badge = badgeText?.trim().orEmpty()
            if (base.isNotEmpty() && badge.isNotEmpty()) {
                append(base).append(" [").append(badge).append(']')
            } else if (base.isNotEmpty()) {
                append(base)
            } else if (badge.isNotEmpty()) {
                append(badge)
            }
        }

        val ok = memScoped {
            val nid = alloc<NOTIFYICONDATAW>()
            memset(nid.ptr, 0, sizeOf<NOTIFYICONDATAW>().toULong())
            nid.cbSize = sizeOf<NOTIFYICONDATAW>().toUInt()
            nid.hWnd = hwnd
            nid.uID = TRAY_ICON_UID
            nid.uCallbackMessage = Win32NativeWindowHost.WM_APP_TRAY_ICON
            var flags = NIF_MESSAGE
            if (newIcon != null) {
                nid.hIcon = newIcon
                flags = flags or NIF_ICON
            }
            if (tipText.isNotEmpty()) {
                copyUtf16Truncated(tipText, nid.szTip, 127)
                flags = flags or NIF_TIP
            } else {
                nid.szTip[0] = 0u
                flags = flags or NIF_TIP
            }
            nid.uFlags = flags.toUInt()
            val msg = if (installedIconAdded) NIM_MODIFY else NIM_ADD
            Shell_NotifyIconW(msg.toUInt(), nid.ptr) != 0
        }
        if (ok) {
            if (prevOwned != null && prevOwned != newIcon) {
                DestroyIcon(prevOwned)
            }
            ownedHIcon = if (ownsNew && newIcon != null) newIcon else null
            registeredHwnd = hwnd
            installedIconAdded = true
            retryAttempts = 0
            KillTimer(hwnd, TRAY_RETRY_TIMER_ID.toULong())
        } else {
            if (ownsNew && newIcon != null && newIcon != prevOwned) {
                DestroyIcon(newIcon)
            }
            if (!installedIconAdded) {
                registeredHwnd = hwnd
                if (retryAttempts < MAX_TRAY_RETRY_ATTEMPTS) {
                    retryAttempts++
                    SetTimer(hwnd, TRAY_RETRY_TIMER_ID.toULong(), 1000u, null)
                }
            }
        }
    }

    private fun removeNativeTrayIcon() {
        val hwnd = registeredHwnd ?: Win32NativeWindowHost.activeHwnd
        if (hwnd != null && IsWindow(hwnd) != 0) {
            KillTimer(hwnd, TRAY_RETRY_TIMER_ID.toULong())
            if (installedIconAdded) {
                memScoped {
                    val nid = alloc<NOTIFYICONDATAW>()
                    memset(nid.ptr, 0, sizeOf<NOTIFYICONDATAW>().toULong())
                    nid.cbSize = sizeOf<NOTIFYICONDATAW>().toUInt()
                    nid.hWnd = hwnd
                    nid.uID = TRAY_ICON_UID
                    Shell_NotifyIconW(NIM_DELETE.toUInt(), nid.ptr)
                }
            }
        }
        WindowsHeadlessTrayLoop.currentTrayHwnd()?.let { headlessHwnd ->
            if (headlessHwnd != hwnd && IsWindow(headlessHwnd) != 0) {
                KillTimer(headlessHwnd, TRAY_RETRY_TIMER_ID.toULong())
            }
        }
        installedIconAdded = false
        registeredHwnd = null
        retryAttempts = 0
        ownedHIcon?.let {
            DestroyIcon(it)
            ownedHIcon = null
        }
    }

    private fun onRetryTimerFired(hwnd: HWND?): Boolean {
        val targetHwnd = hwnd
            ?: registeredHwnd
            ?: Win32NativeWindowHost.activeHwnd
            ?: WindowsHeadlessTrayLoop.currentTrayHwnd()
        if (targetHwnd != null && IsWindow(targetHwnd) != 0) {
            KillTimer(targetHwnd, TRAY_RETRY_TIMER_ID.toULong())
        }
        if (isDisposed || installedIconAdded) {
            return true
        }
        syncNativeTrayIcon(preferredHwnd = targetHwnd)
        return true
    }

    internal fun resolveTrayHIconForTesting(pathOrUrl: String?): Pair<HICON?, Boolean> =
        resolveTrayHIcon(pathOrUrl)

    private fun resolveTrayHIcon(pathOrUrl: String?): Pair<HICON?, Boolean> {
        val smW = GetSystemMetrics(SM_CXSMICON).coerceAtLeast(16)
        val smH = GetSystemMetrics(SM_CYSMICON).coerceAtLeast(16)
        val raw = pathOrUrl?.trim().takeIf { !it.isNullOrEmpty() } ?: defaultIconPath
        val resolved = Win32NativeWindowHost.resolveHIconForPath(
            iconPath = raw,
            widthPx = maxOf(smW, 32),
            heightPx = maxOf(smH, 32),
            allowEmbeddedExeFallback = true
        )
        if (resolved.first != null) {
            return resolved
        }
        Win32NativeWindowHost.appIconSmall?.let { return it to false }
        Win32NativeWindowHost.appIconBig?.let { return it to false }
        return LoadIconW(null, IDI_APPLICATION) to false
    }

    private fun showTrayContextMenu(hwnd: HWND) {
        val desktopMenu = contextMenu as? DesktopContextMenuNativeImpl<*> ?: return
        val hPopup = CreatePopupMenu() ?: return
        val trayCmdHandlers = LinkedHashMap<Int, () -> Unit>()
        try {
            Win32NativeWindowHost.populateWin32PopupMenu(hPopup, desktopMenu.nativeWidget, trayCmdHandlers)
            val selectedCmd = memScoped {
                val pt = alloc<POINT>()
                GetCursorPos(pt.ptr)
                SetForegroundWindow(hwnd)
                val cmd = TrackPopupMenu(
                    hPopup,
                    (TPM_RIGHTBUTTON or TPM_RETURNCMD or TPM_NONOTIFY).toUInt(),
                    pt.x,
                    pt.y,
                    0,
                    hwnd,
                    null
                )
                // Per MSDN Q135788: post WM_NULL after TrackPopupMenu so menu dismisses cleanly on outside clicks.
                PostMessageW(hwnd, WM_NULL.toUInt(), 0uL, 0L)
                cmd
            }
            if (selectedCmd > 0) {
                val action = trayCmdHandlers[selectedCmd] ?: return
                GenericUiThreading.runOnLogic {
                    action.invoke()
                }
            }
        } finally {
            DestroyMenu(hPopup)
        }
    }

    private fun handleEvent(hwnd: HWND, mouseMsg: Int) {
        if (isDisposed) return
        when (mouseMsg and 0xFFFF) {
            WM_LBUTTONUP -> {
                val listener = onClickedListener
                if (listener != null) {
                    GenericUiThreading.runOnLogic {
                        listener.invoke()
                    }
                } else if (contextMenu != null) {
                    showTrayContextMenu(hwnd)
                }
            }
            WM_RBUTTONUP, WM_CONTEXTMENU -> {
                showTrayContextMenu(hwnd)
            }
        }
    }

    internal companion object {
        private const val TRAY_ICON_UID = 1001u
        private const val TRAY_RETRY_TIMER_ID = 0x7A02
        private const val MAX_TRAY_RETRY_ATTEMPTS = 15
        private var activeTrayInstance: WindowsSystemTrayNativeImpl? = null

        internal fun hasActiveTray(): Boolean =
            activeTrayInstance?.let { !it.isDisposed } == true

        internal fun isNativeTrayInstalled(): Boolean =
            activeTrayInstance?.let { !it.isDisposed && it.installedIconAdded } == true

        internal fun syncActiveTrayIcon() {
            activeTrayInstance?.syncNativeTrayIcon()
        }

        internal fun reRegisterAfterTaskbarCreated(sourceHwnd: HWND? = null) {
            val instance = activeTrayInstance ?: return
            if (instance.isDisposed) return
            val existingHwnd = instance.registeredHwnd
            if (sourceHwnd != null && existingHwnd != null && sourceHwnd != existingHwnd && IsWindow(existingHwnd) != 0) {
                return
            }
            instance.installedIconAdded = false
            instance.retryAttempts = 0
            instance.syncNativeTrayIcon(preferredHwnd = existingHwnd?.takeIf { IsWindow(it) != 0 })
        }

        internal fun handleRetryTimer(hwnd: HWND?, timerId: Int): Boolean {
            if (timerId != TRAY_RETRY_TIMER_ID) return false
            val instance = activeTrayInstance
            if (instance == null) {
                if (hwnd != null && IsWindow(hwnd) != 0) {
                    KillTimer(hwnd, TRAY_RETRY_TIMER_ID.toULong())
                }
                return true
            }
            return instance.onRetryTimerFired(hwnd)
        }

        internal fun migrateTrayToHeadlessHwnd() {
            val instance = activeTrayInstance ?: return
            if (instance.isDisposed) return
            val headlessHwnd = WindowsHeadlessTrayLoop.ensureTrayMessageHwnd() ?: return
            instance.syncNativeTrayIcon(preferredHwnd = headlessHwnd)
        }

        internal fun disposeAll() {
            activeTrayInstance?.dispose()
        }

        internal fun handleTrayCallback(hwnd: HWND, eventMsg: Int) {
            activeTrayInstance?.handleEvent(hwnd, eventMsg)
        }

        internal fun copyUtf16Truncated(
            src: String,
            dst: kotlinx.cinterop.CPointer<WCHARVar>,
            maxChars: Int
        ) {
            val count = minOf(src.length, maxChars)
            for (i in 0 until count) {
                dst[i] = src[i].code.toUShort()
            }
            dst[count] = 0u
        }
    }
}
