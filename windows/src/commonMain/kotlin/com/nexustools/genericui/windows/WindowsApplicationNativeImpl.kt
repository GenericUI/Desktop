package com.nexustools.genericui.windows

import com.nexustools.genericui.desktop.shell.DesktopApplicationNativeImpl
import com.nexustools.genericui.enums.ui.ThemeMode

/**
 * Win32 implementation of [com.nexustools.genericui.nativeimpl.shell.ApplicationNativeImpl] driving
 * Per-Monitor V2 DPI awareness (`SetProcessDpiAwarenessContext`), Dark Mode immersive attribute
 * (`DWMWA_USE_IMMERSIVE_DARK_MODE`), and the Win32 `PeekMessageW` / `TranslateMessage` / `DispatchMessageW`
 * + monitor-refresh-rate `QueryPerformanceCounter` VBlank loop.
 */
class WindowsApplicationNativeImpl : DesktopApplicationNativeImpl<Win32HwndHandle>(
    nativeApplication = Win32HwndHandle(
        win32ClassName = "Win32ApplicationInstance",
        dwStyle = 0L
    )
) {
    init {
        nativeApplication.attributes["dpiAwareness"] = "DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2"
        nativeApplication.attributes["comInitialized"] = "COINIT_APARTMENTTHREADED"
    }

    override fun setThemeMode(mode: ThemeMode) {
        super.setThemeMode(mode)
        nativeApplication.attributes["DWMWA_USE_IMMERSIVE_DARK_MODE"] = (mode == ThemeMode.DARK)
    }

    override fun runEventLoop(onStarted: () -> Unit) {
        if (interactiveWin32GuiEnabled) {
            Win32WinMain.initializeFromProcessStartup()
        }
        super.runEventLoop(onStarted)
        if (interactiveWin32GuiEnabled) {
            Win32NativeWindowHost.runMessageLoop()
        }
    }

    companion object {
        /**
         * Enabled by Windows application entry points (`Win32WinMain.launch` / `main()`) to open the
         * live Win32 GUI window and run the interactive monitor-refresh-rate message loop, while remaining
         * `false` during headless unit tests (`mingwX64Test`).
         */
        var interactiveWin32GuiEnabled: Boolean = false
    }
}
