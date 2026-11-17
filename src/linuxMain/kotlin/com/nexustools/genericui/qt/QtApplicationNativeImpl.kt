@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.qt

import com.nexustools.genericui.desktop.shell.DesktopApplicationNativeImpl
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.style.css.CssCustomCursor
import com.nexustools.genericui.style.theme.SystemThemeDetector
import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * Qt 6 (`QApplication`) implementation of
 * [com.nexustools.genericui.nativeimpl.shell.ApplicationNativeImpl].
 */
class QtApplicationNativeImpl :
    DesktopApplicationNativeImpl<QtWidgetHandle>(
        nativeApplication = QtWidgetHandle(qtClassName = "QApplication")
    ) {

    init {
        installQtThemeDetector()
    }

    override fun detectSystemThemeMode(): ThemeMode =
        SystemThemeDetector.overrideDetectedTheme?.takeIf { !it.isAuto } ?: detectQtSystemTheme()

    override fun setThemeMode(mode: ThemeMode) {
        super.setThemeMode(mode)
        installQtThemeDetector()
        val effective = SystemThemeDetector.resolveTheme(mode)
        nativeWidget.attributes["effectiveThemeMode"] = effective
        nativeWidget.attributes["qPaletteDarkMode"] = (effective == ThemeMode.DARK)
        nativeWidget.attributes["qtColorScheme"] = when (mode) {
            ThemeMode.DARK -> "Qt::ColorScheme::Dark"
            ThemeMode.LIGHT -> "Qt::ColorScheme::Light"
            ThemeMode.AUTO -> "Qt::ColorScheme::Unknown"
        }
        if (interactiveQtGuiEnabled) {
            QtNativeWindowHost.syncThemeAppearance(mode)
        }
    }

    override fun onNativeRun() {
        nativeWidget.attributes["qApplicationRunning"] = true
    }

    override fun runEventLoop(onStarted: () -> Unit) {
        if (interactiveQtGuiEnabled) {
            QtNativeWindowHost.prepareEventLoop()
        }
        super.runEventLoop(onStarted)
        if (interactiveQtGuiEnabled && running) {
            QtNativeWindowHost.runEventLoop()
        }
    }

    override fun onNativeExit(exitCode: Int) {
        nativeWidget.attributes["qApplicationRunning"] = false
        nativeWidget.attributes["qApplicationExitCode"] = exitCode
        if (interactiveQtGuiEnabled) {
            QtNativeWindowHost.terminateEventLoop()
        }
    }

    companion object {
        var interactiveQtGuiEnabled: Boolean = false

        fun detectQtSystemTheme(): ThemeMode {
            val qtOverride = getenv("QT_STYLE_OVERRIDE")?.toKString()?.trim().orEmpty()
            val gtkTheme = getenv("GTK_THEME")?.toKString()?.trim().orEmpty()
            val colorScheme = getenv("COLOR_SCHEME")?.toKString()?.trim().orEmpty()
            if (
                qtOverride.contains("dark", ignoreCase = true) ||
                gtkTheme.contains("dark", ignoreCase = true) ||
                colorScheme.contains("dark", ignoreCase = true)
            ) {
                return ThemeMode.DARK
            }
            Qt6DynamicBindings.queryDarkColorScheme()?.let { isDark ->
                return if (isDark) ThemeMode.DARK else ThemeMode.LIGHT
            }
            return ThemeMode.LIGHT
        }

        fun installQtThemeDetector() {
            SystemThemeDetector.backendDetector = { detectQtSystemTheme() }
        }

        fun resolveQCursorPixmap(cursor: CssCustomCursor): ImageBuffer? =
            cursor.resolveToImageBuffer()
    }
}
