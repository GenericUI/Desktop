@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.desktop.shell.DesktopApplicationNativeImpl
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.style.theme.SystemThemeDetector
import kotlinx.cinterop.toKString
import platform.Foundation.NSUserDefaults
import platform.posix.getenv

/**
 * macOS AppKit `NSApplication` native implementation (`:desktop:macos`).
 */
class MacosApplicationNativeImpl :
    DesktopApplicationNativeImpl<MacosViewHandle>(
        nativeApplication = MacosViewHandle(objcClassName = "NSApplication")
    ) {

    init {
        installMacosThemeDetector()
    }

    override fun detectSystemThemeMode(): ThemeMode =
        SystemThemeDetector.overrideDetectedTheme?.takeIf { !it.isAuto } ?: detectMacosSystemTheme()

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
        if (interactiveMacosGuiEnabled) {
            MacosNativeWindowHost.syncThemeAppearance(mode)
        }
    }

    override fun onNativeRun() {
        nativeWidget.attributes["nsAppRunning"] = true
    }

    override fun runEventLoop(onStarted: () -> Unit) {
        if (interactiveMacosGuiEnabled) {
            MacosNativeWindowHost.prepareEventLoop()
        }
        super.runEventLoop(onStarted)
        if (interactiveMacosGuiEnabled && running) {
            MacosNativeWindowHost.runEventLoop()
        }
    }

    override fun onNativeExit(exitCode: Int) {
        nativeWidget.attributes["nsAppRunning"] = false
        nativeWidget.attributes["nsAppTerminateCode"] = exitCode
        if (interactiveMacosGuiEnabled) {
            MacosNativeWindowHost.terminateEventLoop()
        }
    }

    companion object {
        /**
         * Enabled by [MacosLauncher.launch] when running an interactive AppKit desktop session.
         * Remains `false` during headless unit tests so tests stay deterministic and non-blocking.
         */
        var interactiveMacosGuiEnabled: Boolean = false

        /**
         * Optional hook invoked when `NSApplication` receives an `NSEventTypeSystemDefined` event
         * (e.g. `subtype == 8` hardware media keys).
         */
        var systemDefinedEventHook: ((subtype: Int, data1: Int) -> Boolean)? = null

        fun detectMacosSystemTheme(): ThemeMode {
            val appleStyle = getenv("APPLE_INTERFACE_STYLE")?.toKString()?.trim()
                ?: getenv("AppleInterfaceStyle")?.toKString()?.trim()
            if (appleStyle != null) {
                return if (appleStyle.equals("Dark", ignoreCase = true)) ThemeMode.DARK else ThemeMode.LIGHT
            }
            if (interactiveMacosGuiEnabled) {
                val defaultsStyle = NSUserDefaults.standardUserDefaults
                    .stringForKey("AppleInterfaceStyle")
                    ?.trim()
                if (defaultsStyle != null) {
                    return if (defaultsStyle.equals("Dark", ignoreCase = true)) ThemeMode.DARK else ThemeMode.LIGHT
                }
            }
            return ThemeMode.LIGHT
        }

        fun installMacosThemeDetector() {
            SystemThemeDetector.backendDetector = { detectMacosSystemTheme() }
        }
    }
}
