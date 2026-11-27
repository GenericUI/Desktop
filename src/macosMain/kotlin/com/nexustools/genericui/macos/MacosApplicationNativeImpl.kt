@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.desktop.shell.DesktopApplicationNativeImpl
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.nativeimpl.shell.MenuNativeImpl
import com.nexustools.genericui.style.theme.SystemThemeDetector
import kotlinx.cinterop.toKString
import platform.Foundation.NSProcessInfo
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

    override fun setMacApplicationMenu(menuImpl: MenuNativeImpl<*>?, appTitle: String?) {
        nativeWidget.attributes["applicationMenu"] = menuImpl
        MacosNativeWindowHost.syncMacApplicationMenu(menuImpl, appTitle)
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
        MacosSystemTrayNativeImpl.disposeAll()
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

        private var cachedDefaultsThemeMode: ThemeMode? = null
        private var cachedDefaultsTimestampMs: Double = -10_000.0

        fun detectMacosSystemTheme(): ThemeMode {
            if (interactiveMacosGuiEnabled) {
                val nowMs = NSProcessInfo.processInfo.systemUptime * 1000.0
                val cached = cachedDefaultsThemeMode
                if (cached != null && (nowMs - cachedDefaultsTimestampMs) in 0.0..250.0) {
                    return cached
                }
                val appleStyle = getenv("APPLE_INTERFACE_STYLE")?.toKString()?.trim()
                    ?: getenv("AppleInterfaceStyle")?.toKString()?.trim()
                val resolved = if (appleStyle != null) {
                    if (appleStyle.equals("Dark", ignoreCase = true)) ThemeMode.DARK else ThemeMode.LIGHT
                } else {
                    val defaultsStyle = NSUserDefaults.standardUserDefaults
                        .stringForKey("AppleInterfaceStyle")
                        ?.trim()
                    if (defaultsStyle != null && defaultsStyle.equals("Dark", ignoreCase = true)) {
                        ThemeMode.DARK
                    } else {
                        ThemeMode.LIGHT
                    }
                }
                cachedDefaultsThemeMode = resolved
                cachedDefaultsTimestampMs = nowMs
                return resolved
            }
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
