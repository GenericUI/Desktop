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
 * Linux Qt6 `QApplication` native implementation (`:desktop:qt`).
 */
class QtApplicationNativeImpl :
    DesktopApplicationNativeImpl<QtWidgetHandle>(
        nativeApplication = QtWidgetHandle(qtClassName = "QApplication")
    ) {

    init {
        installQtThemeDetector()
    }

    override fun setThemeMode(mode: ThemeMode) {
        super.setThemeMode(mode)
        installQtThemeDetector()
        val effective = SystemThemeDetector.resolveTheme(mode)
        nativeWidget.attributes["effectiveThemeMode"] = effective
        nativeWidget.attributes["qtColorScheme"] = when (mode) {
            ThemeMode.DARK -> "Qt::ColorScheme::Dark"
            ThemeMode.LIGHT -> "Qt::ColorScheme::Light"
            ThemeMode.AUTO -> "Qt::ColorScheme::Unknown"
        }
    }

    override fun onNativeRun() {
        nativeWidget.attributes["qAppExecRunning"] = true
    }

    override fun onNativeExit(exitCode: Int) {
        nativeWidget.attributes["qAppExecRunning"] = false
        nativeWidget.attributes["qAppExitCode"] = exitCode
    }

    companion object {
        fun detectQtSystemTheme(): ThemeMode {
            val qtStyle = getenv("QT_STYLE_OVERRIDE")?.toKString()?.trim().orEmpty()
            val gtkTheme = getenv("GTK_THEME")?.toKString()?.trim().orEmpty()
            if (
                qtStyle.contains("dark", ignoreCase = true) ||
                gtkTheme.contains("dark", ignoreCase = true)
            ) {
                return ThemeMode.DARK
            }
            return ThemeMode.LIGHT
        }

        fun installQtThemeDetector() {
            SystemThemeDetector.backendDetector = { detectQtSystemTheme() }
        }

        /**
         * Resolves [cursor] to an [ImageBuffer] for `QCursor(QPixmap::fromImage(img), hotspotX, hotspotY)`.
         */
        fun resolveQCursorImage(cursor: CssCustomCursor): ImageBuffer? =
            cursor.resolveToImageBuffer()
    }
}
