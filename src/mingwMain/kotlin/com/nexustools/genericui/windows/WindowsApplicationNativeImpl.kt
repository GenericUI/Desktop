package com.nexustools.genericui.windows

import com.nexustools.genericui.desktop.shell.DesktopApplicationNativeImpl
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.style.theme.SystemThemeDetector

/**
 * Windows Win32 application implementation (`:desktop:windows`) managing Win32 message loop
 * lifecycle, `SystemThemeDetector` OS dark/light theme detection, and DWM/UxTheme dark mode attributes.
 */
class WindowsApplicationNativeImpl :
    DesktopApplicationNativeImpl<Win32HwndHandle>(
        nativeApplication = Win32HwndHandle(
            win32ClassName = "GenericUiWin32Application",
            dwStyle = 0L,
            dwExStyle = 0L
        )
    ) {

    init {
        installWindowsThemeDetector()
    }

    override fun detectSystemThemeMode(): ThemeMode =
        if (queryRegistryAppsUseLightTheme()) ThemeMode.LIGHT else ThemeMode.DARK

    override fun setThemeMode(mode: ThemeMode) {
        super.setThemeMode(mode)
        installWindowsThemeDetector()
        Win32NativeWindowHost.forcedThemeMode = mode
        val resolved = SystemThemeDetector.resolveTheme(mode)
        val isDark = resolved == ThemeMode.DARK
        nativeApplication.attributes["dwmUseImmersiveDarkMode"] = isDark
        nativeApplication.attributes["effectiveThemeMode"] = resolved
        nativeApplication.attributes["uxThemeClass"] = if (isDark) "DarkMode_Explorer" else "Explorer"
        val hwnd = Win32NativeWindowHost.activeHwnd
        if (hwnd != null) {
            Win32NativeWindowHost.applyWindowsImmersiveTitleBarTheme(hwnd, isDark)
            Win32DwmUxThemeBridge.setControlWindowTheme(hwnd, isDark)
            Win32NativeWindowHost.invalidateAllWindows()
        }
    }

    override fun runEventLoop(onStarted: () -> Unit) {
        nativeApplication.attributes["messageLoopRunning"] = true
        super.runEventLoop(onStarted)
        if (interactiveWin32GuiEnabled) {
            Win32NativeWindowHost.runMessageLoop()
        }
    }

    override fun exitApplication(exitCode: Int) {
        nativeApplication.attributes["messageLoopRunning"] = false
        nativeApplication.attributes["postQuitMessageCode"] = exitCode
        super.exitApplication(exitCode)
    }

    companion object {
        /**
         * Set to `true` by [WindowsLauncher.launch] when running as a standalone Windows desktop
         * executable (`runDebugExecutableMingwX64` / `runReleaseExecutableMingwX64`).
         * Remains `false` during automated unit tests so tests never block on `GetMessageW`.
         */
        var interactiveWin32GuiEnabled: Boolean = false

        private val windowsBackendDetector: () -> ThemeMode = {
            if (queryRegistryAppsUseLightTheme()) ThemeMode.LIGHT else ThemeMode.DARK
        }

        fun queryRegistryAppsUseLightTheme(): Boolean =
            !Win32DwmUxThemeBridge.isSystemDarkTheme()

        fun installWindowsThemeDetector() {
            if (SystemThemeDetector.backendDetector !== windowsBackendDetector) {
                SystemThemeDetector.backendDetector = windowsBackendDetector
            }
        }
    }
}
