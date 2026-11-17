@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.component.system.Notification
import com.nexustools.genericui.desktop.system.DesktopNotificationManagerNativeImpl
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import platform.windows.IDI_INFORMATION
import platform.windows.LoadIconW
import platform.windows.NIF_ICON
import platform.windows.NIF_INFO
import platform.windows.NIF_MESSAGE
import platform.windows.NIF_TIP
import platform.windows.NIIF_INFO
import platform.windows.NIIF_WARNING
import platform.windows.NIM_ADD
import platform.windows.NIM_DELETE
import platform.windows.NIM_MODIFY
import platform.windows.NOTIFYICONDATAW
import platform.windows.Shell_NotifyIconW

/**
 * Windows notification manager backed by `Shell_NotifyIconW` (`NOTIFYICONDATAW` / `NIF_INFO`
 * system balloon & Windows Action Center toast integration).
 */
class WindowsNotificationManagerNativeImpl : DesktopNotificationManagerNativeImpl(
    backendToolkit = "Windows Shell_NotifyIconW (NIF_INFO)"
) {
    private var iconInstalled: Boolean = false
    private var currentActiveNotificationId: String? = null
    private var currentActionCallback: ((String) -> Unit)? = null
    private var currentDefaultActionId: String = "default"

    init {
        activeManager = this
    }

    override fun showNotification(notification: Notification, onActionClicked: ((String) -> Unit)?) {
        super.showNotification(notification, onActionClicked)
        if (!WindowsApplicationNativeImpl.interactiveWin32GuiEnabled || isDisposed) return
        val hwnd = Win32NativeWindowHost.activeHwnd ?: return

        currentActiveNotificationId = notification.id
        currentActionCallback = onActionClicked
        currentDefaultActionId = notification.actions.firstOrNull()?.first ?: "default"

        memScoped {
            if (!iconInstalled) {
                val addNid = alloc<NOTIFYICONDATAW>()
                addNid.cbSize = sizeOf<NOTIFYICONDATAW>().toUInt()
                addNid.hWnd = hwnd
                addNid.uID = NOTIFICATION_ICON_UID
                addNid.uCallbackMessage = Win32NativeWindowHost.WM_APP_NOTIFY_BALLOON
                addNid.hIcon = Win32NativeWindowHost.appIconSmall ?: LoadIconW(null, IDI_INFORMATION)
                WindowsSystemTrayNativeImpl.copyUtf16Truncated(
                    notification.title.ifBlank { "Notification" },
                    addNid.szTip,
                    127
                )
                addNid.uFlags = (NIF_MESSAGE or NIF_ICON or NIF_TIP).toUInt()
                if (Shell_NotifyIconW(NIM_ADD.toUInt(), addNid.ptr) != 0) {
                    iconInstalled = true
                }
            }

            val nid = alloc<NOTIFYICONDATAW>()
            nid.cbSize = sizeOf<NOTIFYICONDATAW>().toUInt()
            nid.hWnd = hwnd
            nid.uID = NOTIFICATION_ICON_UID
            nid.uCallbackMessage = Win32NativeWindowHost.WM_APP_NOTIFY_BALLOON
            nid.uFlags = NIF_INFO.toUInt()
            WindowsSystemTrayNativeImpl.copyUtf16Truncated(notification.title, nid.szInfoTitle, 63)
            WindowsSystemTrayNativeImpl.copyUtf16Truncated(notification.body, nid.szInfo, 255)
            nid.dwInfoFlags = resolveInfoFlags(notification).toUInt()
            Shell_NotifyIconW(NIM_MODIFY.toUInt(), nid.ptr)
        }
    }

    override fun cancelNotification(notificationId: String) {
        super.cancelNotification(notificationId)
        if (currentActiveNotificationId == notificationId) {
            currentActiveNotificationId = null
            currentActionCallback = null
            if (iconInstalled) {
                val hwnd = Win32NativeWindowHost.activeHwnd
                if (hwnd != null) {
                    memScoped {
                        val nid = alloc<NOTIFYICONDATAW>()
                        nid.cbSize = sizeOf<NOTIFYICONDATAW>().toUInt()
                        nid.hWnd = hwnd
                        nid.uID = NOTIFICATION_ICON_UID
                        nid.uFlags = NIF_INFO.toUInt()
                        nid.szInfo[0] = 0u
                        nid.szInfoTitle[0] = 0u
                        Shell_NotifyIconW(NIM_MODIFY.toUInt(), nid.ptr)
                    }
                }
            }
        }
    }

    override fun dispose() {
        if (iconInstalled) {
            val hwnd = Win32NativeWindowHost.activeHwnd
            if (hwnd != null) {
                memScoped {
                    val nid = alloc<NOTIFYICONDATAW>()
                    nid.cbSize = sizeOf<NOTIFYICONDATAW>().toUInt()
                    nid.hWnd = hwnd
                    nid.uID = NOTIFICATION_ICON_UID
                    Shell_NotifyIconW(NIM_DELETE.toUInt(), nid.ptr)
                }
            }
            iconInstalled = false
        }
        currentActiveNotificationId = null
        currentActionCallback = null
        if (activeManager === this) {
            activeManager = null
        }
        super.dispose()
    }

    private fun resolveInfoFlags(notification: Notification): Int {
        val ch = channels[notification.channelId]
        return if (ch?.highImportance == true) NIIF_WARNING else NIIF_INFO
    }

    private fun dispatchBalloonClick() {
        val cb = currentActionCallback ?: return
        val actionId = currentDefaultActionId
        GenericUiThreading.runOnLogic {
            cb.invoke(actionId)
        }
    }

    internal companion object {
        private const val NOTIFICATION_ICON_UID = 1002u
        private const val NIN_BALLOONUSERCLICK = 0x0405
        private var activeManager: WindowsNotificationManagerNativeImpl? = null

        internal fun handleNotificationCallback(eventCode: Int) {
            if (eventCode == NIN_BALLOONUSERCLICK) {
                activeManager?.dispatchBalloonClick()
            }
        }
    }
}
