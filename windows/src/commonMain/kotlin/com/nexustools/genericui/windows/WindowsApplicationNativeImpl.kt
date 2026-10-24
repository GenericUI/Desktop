package com.nexustools.genericui.windows

import com.nexustools.genericui.desktop.shell.DesktopApplicationNativeImpl
import com.nexustools.genericui.enums.ui.ThemeMode

/**
 * Win32 implementation of [com.nexustools.genericui.nativeimpl.shell.ApplicationNativeImpl] driving
 * Per-Monitor V2 DPI awareness (`SetProcessDpiAwarenessContext`), Dark Mode immersive attribute
 * (`DWMWA_USE_IMMERSIVE_DARK_MODE`), and the Win32 `GetMessageW` / `TranslateMessage` / `DispatchMessageW` loop.
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
}
