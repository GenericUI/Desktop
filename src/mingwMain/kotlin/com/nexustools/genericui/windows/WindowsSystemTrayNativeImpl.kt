@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.desktop.shell.DesktopContextMenuNativeImpl
import com.nexustools.genericui.desktop.system.DesktopSystemTrayNativeImpl
import com.nexustools.genericui.image.ImageDecodeOptions
import com.nexustools.genericui.image.ImageLoader
import com.nexustools.genericui.image.ImageSource
import com.nexustools.genericui.nativeimpl.shell.ContextMenuNativeImpl
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.wcstr
import platform.windows.CreatePopupMenu
import platform.windows.DestroyIcon
import platform.windows.DestroyMenu
import platform.windows.GetCursorPos
import platform.windows.HICON
import platform.windows.HWND
import platform.windows.IDI_APPLICATION
import platform.windows.IMAGE_ICON
import platform.windows.LR_DEFAULTSIZE
import platform.windows.LR_LOADFROMFILE
import platform.windows.LoadIconW
import platform.windows.LoadImageW
import platform.windows.NIF_ICON
import platform.windows.NIF_MESSAGE
import platform.windows.NIF_TIP
import platform.windows.NIM_ADD
import platform.windows.NIM_DELETE
import platform.windows.NIM_MODIFY
import platform.windows.NOTIFYICONDATAW
import platform.windows.POINT
import platform.windows.SetForegroundWindow
import platform.windows.Shell_NotifyIconW
import platform.windows.TPM_NONOTIFY
import platform.windows.TPM_RETURNCMD
import platform.windows.TPM_RIGHTBUTTON
import platform.windows.TrackPopupMenu
import platform.windows.WCHARVar
import platform.windows.WM_CONTEXTMENU
import platform.windows.WM_LBUTTONUP
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
    }

    internal fun syncNativeTrayIcon() {
        if (!WindowsApplicationNativeImpl.interactiveWin32GuiEnabled || isDisposed) return
        val hwnd = Win32NativeWindowHost.activeHwnd ?: return
        val resolvedIcon = resolveTrayHIcon(iconPath)
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
            nid.cbSize = sizeOf<NOTIFYICONDATAW>().toUInt()
            nid.hWnd = hwnd
            nid.uID = TRAY_ICON_UID
            nid.uCallbackMessage = Win32NativeWindowHost.WM_APP_TRAY_ICON
            var flags = NIF_MESSAGE
            if (resolvedIcon != null) {
                nid.hIcon = resolvedIcon
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
            registeredHwnd = hwnd
            installedIconAdded = true
        }
    }

    private fun removeNativeTrayIcon() {
        val hwnd = registeredHwnd ?: Win32NativeWindowHost.activeHwnd
        if (installedIconAdded && hwnd != null) {
            memScoped {
                val nid = alloc<NOTIFYICONDATAW>()
                nid.cbSize = sizeOf<NOTIFYICONDATAW>().toUInt()
                nid.hWnd = hwnd
                nid.uID = TRAY_ICON_UID
                Shell_NotifyIconW(NIM_DELETE.toUInt(), nid.ptr)
            }
        }
        installedIconAdded = false
        registeredHwnd = null
        ownedHIcon?.let {
            DestroyIcon(it)
            ownedHIcon = null
        }
    }

    private fun resolveTrayHIcon(pathOrUrl: String?): HICON? {
        ownedHIcon?.let {
            DestroyIcon(it)
            ownedHIcon = null
        }
        val raw = pathOrUrl?.trim().orEmpty()
        if (raw.isNotEmpty()) {
            if (raw.endsWith(".ico", ignoreCase = true)) {
                val hIcon: HICON? = memScoped {
                    LoadImageW(
                        null,
                        WindowsIoHelper.normalizeWin32Path(raw).wcstr.ptr,
                        IMAGE_ICON.toUInt(),
                        16,
                        16,
                        (LR_LOADFROMFILE or LR_DEFAULTSIZE).toUInt()
                    )?.reinterpret()
                }
                if (hIcon != null) {
                    ownedHIcon = hIcon
                    return hIcon
                }
            }
            val source = when {
                raw.startsWith("http://", ignoreCase = true) ||
                    raw.startsWith("https://", ignoreCase = true) ||
                    raw.startsWith("file://", ignoreCase = true) -> ImageSource.fromUrl(raw)
                raw.startsWith("res/", ignoreCase = true) ||
                    raw.startsWith("drawable/", ignoreCase = true) -> ImageSource.fromResource(raw)
                else -> ImageSource.fromFile(raw)
            }
            val buffer = ImageLoader.activeNativeCodec?.decode(
                source,
                ImageDecodeOptions(targetWidth = 32, targetHeight = 32, antialiasing = true)
            )
            if (buffer != null) {
                val created = WindowsCurCodec.createHIconFromImage(buffer)
                if (created != null) {
                    ownedHIcon = created
                    return created
                }
            }
        }
        return Win32NativeWindowHost.appIconSmall
            ?: Win32NativeWindowHost.appIconBig
            ?: LoadIconW(null, IDI_APPLICATION)
    }

    private fun handleEvent(hwnd: HWND, mouseMsg: Int) {
        if (isDisposed) return
        when (mouseMsg) {
            WM_LBUTTONUP -> {
                val listener = onClickedListener ?: return
                GenericUiThreading.runOnLogic {
                    listener.invoke()
                }
            }
            WM_RBUTTONUP, WM_CONTEXTMENU -> {
                val desktopMenu = contextMenu as? DesktopContextMenuNativeImpl<*> ?: return
                val hPopup = CreatePopupMenu() ?: return
                val trayCmdHandlers = LinkedHashMap<Int, () -> Unit>()
                try {
                    Win32NativeWindowHost.populateWin32PopupMenu(hPopup, desktopMenu.nativeWidget, trayCmdHandlers)
                    val selectedCmd = memScoped {
                        val pt = alloc<POINT>()
                        GetCursorPos(pt.ptr)
                        SetForegroundWindow(hwnd)
                        TrackPopupMenu(
                            hPopup,
                            (TPM_RIGHTBUTTON or TPM_RETURNCMD or TPM_NONOTIFY).toUInt(),
                            pt.x,
                            pt.y,
                            0,
                            hwnd,
                            null
                        )
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
        }
    }

    internal companion object {
        private const val TRAY_ICON_UID = 1001u
        private var activeTrayInstance: WindowsSystemTrayNativeImpl? = null

        internal fun syncActiveTrayIcon() {
            activeTrayInstance?.syncNativeTrayIcon()
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
