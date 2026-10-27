@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.desktop.shell.DesktopApplicationNativeImpl
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.style.theme.SystemThemeDetector
import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * macOS AppKit `NSApplication` native implementation (`:desktop:macos`).
 */
class MacosApplicationNativeImpl :
    DesktopApplicationNativeImpl<MacosViewHandle>(
        nativeApplication = MacosViewHandle(nsClassName = "NSApplication")
    ) {

    init {
        installMacosThemeDetector()
    }

    override fun setThemeMode(mode: ThemeMode) {
        super.setThemeMode(mode)
        installMacosThemeDetector()
        val effective = SystemThemeDetector.resolveTheme(mode)
        nativeWidget.attributes["effectiveThemeMode"] = effective
        nativeWidget.attributes["nsAppearance"] = when (mode) {
            ThemeMode.DARK -> "NSAppearanceNameDarkAqua"
            ThemeMode.LIGHT -> "NSAppearanceNameAqua"
            ThemeMode.AUTO -> if (effective == ThemeMode.DARK) {
                "NSAppearanceNameDarkAqua"
            } else {
                "NSAppearanceNameAqua"
            }
        }
    }

    override fun onNativeRun() {
        nativeWidget.attributes["nsAppRunning"] = true
    }

    override fun onNativeExit(exitCode: Int) {
        nativeWidget.attributes["nsAppRunning"] = false
        nativeWidget.attributes["nsAppTerminateCode"] = exitCode
    }

    companion object {
        fun detectMacosSystemTheme(): ThemeMode {
            val appleStyle = getenv("APPLE_INTERFACE_STYLE")?.toKString()?.trim()
                ?: getenv("AppleInterfaceStyle")?.toKString()?.trim()
            if (appleStyle != null) {
                return if (appleStyle.equals("Dark", ignoreCase = true)) ThemeMode.DARK else ThemeMode.LIGHT
            }
            return ThemeMode.LIGHT
        }

        fun installMacosThemeDetector() {
            SystemThemeDetector.backendDetector = { detectMacosSystemTheme() }
        }
    }
}
