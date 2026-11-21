@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk

import com.nexustools.genericui.desktop.shell.DesktopApplicationNativeImpl
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.style.css.CssCustomCursor
import com.nexustools.genericui.style.theme.SystemThemeDetector
import kotlinx.cinterop.toKString
import platform.posix.getenv

class GtkApplicationNativeImpl :
    DesktopApplicationNativeImpl<GtkWidgetHandle>(
        nativeApplication = GtkWidgetHandle(gtkTypeName = "AdwApplication")
    ) {

    init {
        installGtkThemeDetector()
    }

    override fun detectSystemThemeMode(): ThemeMode =
        SystemThemeDetector.overrideDetectedTheme?.takeIf { !it.isAuto } ?: detectGtkSystemTheme()

    override fun setThemeMode(mode: ThemeMode) {
        super.setThemeMode(mode)
        installGtkThemeDetector()
        val effective = SystemThemeDetector.resolveTheme(mode)
        nativeWidget.attributes["effectiveThemeMode"] = effective
        nativeWidget.attributes["adwColorScheme"] = when (mode) {
            ThemeMode.DARK -> "ADW_COLOR_SCHEME_FORCE_DARK"
            ThemeMode.LIGHT -> "ADW_COLOR_SCHEME_FORCE_LIGHT"
            ThemeMode.AUTO -> "ADW_COLOR_SCHEME_DEFAULT"
        }
        nativeWidget.attributes["gtkApplicationPreferDarkTheme"] = (effective == ThemeMode.DARK)
        if (interactiveGtkGuiEnabled) {
            GtkNativeWindowHost.syncThemeAppearance(mode)
        }
    }

    override fun onNativeRun() {
        nativeWidget.attributes["gApplicationRunning"] = true
    }

    override fun runEventLoop(onStarted: () -> Unit) {
        if (interactiveGtkGuiEnabled) {
            GtkNativeWindowHost.prepareEventLoop()
        }
        super.runEventLoop(onStarted)
        if (interactiveGtkGuiEnabled && running) {
            GtkNativeWindowHost.runEventLoop()
        }
    }

    override fun onNativeExit(exitCode: Int) {
        nativeWidget.attributes["gApplicationRunning"] = false
        nativeWidget.attributes["gApplicationExitCode"] = exitCode
        GtkSystemTrayNativeImpl.disposeAll()
        if (interactiveGtkGuiEnabled) {
            GtkNativeWindowHost.terminateEventLoop()
        }
    }

    companion object {
        var interactiveGtkGuiEnabled: Boolean = false

        fun detectGtkSystemTheme(): ThemeMode {
            val gtkTheme = getenv("GTK_THEME")?.toKString()?.trim().orEmpty()
            val colorScheme = getenv("COLOR_SCHEME")?.toKString()?.trim().orEmpty()
            if (gtkTheme.contains("dark", ignoreCase = true) || colorScheme.contains("dark", ignoreCase = true)) {
                return ThemeMode.DARK
            }
            Gtk4DynamicBindings.adwStyleManagerGetDark()?.let { isDark ->
                return if (isDark) ThemeMode.DARK else ThemeMode.LIGHT
            }
            return ThemeMode.LIGHT
        }

        fun installGtkThemeDetector() {
            SystemThemeDetector.backendDetector = { detectGtkSystemTheme() }
        }

        fun resolveGdkCursorTexture(cursor: CssCustomCursor): ImageBuffer? =
            cursor.resolveToImageBuffer()
    }
}
