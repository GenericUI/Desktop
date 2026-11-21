@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.wcstr
import platform.windows.CreateWindowExW
import platform.windows.DefWindowProcW
import platform.windows.DestroyWindow
import platform.windows.DispatchMessageW
import platform.windows.GetCurrentThreadId
import platform.windows.GetModuleHandleW
import platform.windows.HWND
import platform.windows.INFINITE
import platform.windows.IsWindow
import platform.windows.LPARAM
import platform.windows.LRESULT
import platform.windows.MSG
import platform.windows.MsgWaitForMultipleObjectsEx
import platform.windows.PM_REMOVE
import platform.windows.PeekMessageW
import platform.windows.PostMessageW
import platform.windows.PostQuitMessage
import platform.windows.PostThreadMessageW
import platform.windows.QS_ALLINPUT
import platform.windows.RegisterClassExW
import platform.windows.TranslateMessage
import platform.windows.UINT
import platform.windows.WAIT_FAILED
import platform.windows.WM_CLOSE
import platform.windows.WM_DESTROY
import platform.windows.WM_QUIT
import platform.windows.WM_TIMER
import platform.windows.WNDCLASSEXW
import platform.windows.WPARAM
import platform.windows.WS_EX_TOOLWINDOW
import platform.windows.WS_POPUP

private const val HEADLESS_TRAY_CLASS_NAME = "GenericUiHeadlessTrayWindow"

private fun win32HeadlessTrayWndProc(
    hwnd: HWND?,
    msg: UINT,
    wParam: WPARAM,
    lParam: LPARAM
): LRESULT {
    when (msg.toInt()) {
        Win32NativeWindowHost.WM_APP_TRAY_ICON.toInt() -> {
            if (hwnd != null) {
                runCatching { WindowsSystemTrayNativeImpl.handleTrayCallback(hwnd, lParam.toInt() and 0xFFFF) }
            }
            return 0L
        }
        Win32NativeWindowHost.WM_APP_UI_DISPATCH.toInt() -> {
            runCatching { WindowsUiThreadDispatcherNativeImpl.drainPendingUiQueue() }
            return 0L
        }
        WM_TIMER -> {
            if (WindowsUiTimerNativeImpl.dispatchWin32Timer(wParam.toInt())) {
                return 0L
            }
        }
        WM_CLOSE -> {
            if (hwnd != null && IsWindow(hwnd) != 0) {
                DestroyWindow(hwnd)
            }
            return 0L
        }
        WM_DESTROY -> {
            WindowsHeadlessTrayLoop.onTrayHwndDestroyed(hwnd)
            return 0L
        }
    }
    return DefWindowProcW(hwnd, msg, wParam, lParam)
}

/**
 * Headless Win32 message loop and hidden top-level notification-area host window for Windows
 * desktop executables running with a system tray icon and zero open GUI windows (such as background
 * services or apps closed to the tray).
 */
object WindowsHeadlessTrayLoop {
    private var classRegistered: Boolean = false
    private var trayHwnd: HWND? = null
    private var activeLoopThreadId: UInt = 0u

    @kotlin.concurrent.Volatile
    internal var isQuitRequested: Boolean = false
        private set

    internal fun resetQuitFlag() {
        isQuitRequested = false
    }

    internal fun isTrayMessageHwnd(hwnd: HWND?): Boolean =
        hwnd != null && hwnd == trayHwnd

    internal fun onTrayHwndDestroyed(hwnd: HWND?) {
        if (hwnd == null || hwnd == trayHwnd) {
            trayHwnd = null
        }
    }

    internal fun ensureTrayMessageHwnd(): HWND? {
        val existing = trayHwnd
        if (existing != null && IsWindow(existing) != 0) {
            return existing
        }
        val hInstance = GetModuleHandleW(null)
        memScoped {
            if (!classRegistered) {
                val wc = alloc<WNDCLASSEXW>()
                wc.cbSize = sizeOf<WNDCLASSEXW>().toUInt()
                wc.lpfnWndProc = staticCFunction(::win32HeadlessTrayWndProc)
                wc.hInstance = hInstance
                wc.lpszClassName = HEADLESS_TRAY_CLASS_NAME.wcstr.ptr
                RegisterClassExW(wc.ptr)
                classRegistered = true
            }
        }
        // Top-level hidden window (no WS_VISIBLE, no HWND_MESSAGE parent) so Shell_NotifyIconW
        // and TrackPopupMenu interact properly with the Explorer notification area.
        val created = CreateWindowExW(
            dwExStyle = WS_EX_TOOLWINDOW.toUInt(),
            lpClassName = HEADLESS_TRAY_CLASS_NAME,
            lpWindowName = "GenericUI Tray Host",
            dwStyle = WS_POPUP,
            X = 0,
            Y = 0,
            nWidth = 0,
            nHeight = 0,
            hWndParent = null,
            hMenu = null,
            hInstance = hInstance,
            lpParam = null
        )
        trayHwnd = created
        return created
    }

    internal fun destroyTrayMessageHwnd() {
        val current = trayHwnd ?: return
        trayHwnd = null
        if (IsWindow(current) != 0) {
            DestroyWindow(current)
        }
    }

    fun requestQuit() {
        isQuitRequested = true
        val hwnd = trayHwnd
        if (hwnd != null && IsWindow(hwnd) != 0) {
            PostMessageW(hwnd, WM_CLOSE.toUInt(), 0uL, 0L)
        }
        val targetThreadId = activeLoopThreadId
        val currentThreadId = GetCurrentThreadId()
        if (targetThreadId != 0u && targetThreadId != currentThreadId) {
            PostThreadMessageW(targetThreadId, WM_QUIT.toUInt(), 0uL, 0L)
        } else if (targetThreadId == currentThreadId) {
            PostQuitMessage(0)
        }
    }

    /**
     * Runs the Win32 headless tray message pump while a tray icon is active and no main GUI window
     * is open. Returns `true` if execution ended due to `WM_QUIT` or [requestQuit], or `false`
     * if execution handed back control because a GUI window became active or all tray icons were
     * disposed.
     */
    internal fun runHeadlessPumpIfActive(): Boolean = memScoped {
        if (!WindowsApplicationNativeImpl.interactiveWin32GuiEnabled || isQuitRequested) {
            return@memScoped isQuitRequested
        }
        if (!WindowsSystemTrayNativeImpl.hasActiveTray() || Win32NativeWindowHost.activeHwnd != null) {
            return@memScoped false
        }
        ensureTrayMessageHwnd() ?: return@memScoped false
        WindowsSystemTrayNativeImpl.syncActiveTrayIcon()
        WindowsUiThreadDispatcherNativeImpl.attachCurrentThreadAsUiThread()
        activeLoopThreadId = GetCurrentThreadId()
        val msg = alloc<MSG>()
        var receivedQuit = false
        try {
            while (!isQuitRequested && WindowsSystemTrayNativeImpl.hasActiveTray()) {
                var drained = 0
                while (PeekMessageW(msg.ptr, null, 0u, 0u, PM_REMOVE.toUInt()) != 0) {
                    if (msg.message == WM_QUIT.toUInt()) {
                        isQuitRequested = true
                        receivedQuit = true
                        break
                    }
                    if (msg.hwnd == null && msg.message == Win32NativeWindowHost.WM_APP_UI_DISPATCH) {
                        runCatching { WindowsUiThreadDispatcherNativeImpl.drainPendingUiQueue() }
                    } else {
                        TranslateMessage(msg.ptr)
                        DispatchMessageW(msg.ptr)
                    }
                    drained++
                    if (drained >= 64) break
                }
                if (receivedQuit || isQuitRequested) break
                if (Win32NativeWindowHost.activeHwnd != null) {
                    return@memScoped false
                }
                if (!WindowsSystemTrayNativeImpl.hasActiveTray()) {
                    break
                }
                // MWMO_INPUTAVAILABLE (0x0004) sleeps with zero CPU usage until a tray, timer,
                // UI-dispatch thread message, or WM_QUIT arrives.
                val waitRes = MsgWaitForMultipleObjectsEx(0u, null, INFINITE, QS_ALLINPUT.toUInt(), 0x0004u)
                if (waitRes == WAIT_FAILED) {
                    break
                }
            }
        } finally {
            activeLoopThreadId = 0u
            if (receivedQuit || isQuitRequested || !WindowsSystemTrayNativeImpl.hasActiveTray()) {
                if (Win32NativeWindowHost.activeHwnd == null) {
                    WindowsUiThreadDispatcherNativeImpl.clearUiThreadBinding()
                }
                destroyTrayMessageHwnd()
            }
        }
        receivedQuit || isQuitRequested
    }
}
