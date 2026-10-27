@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk

import com.nexustools.genericui.desktop.shell.DesktopApplicationNativeImpl
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.style.css.CssCustomCursor
import com.nexustools.genericui.style.theme.SystemThemeDetector
import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * Linux GTK4 / Libadwaita `AdwApplication` native implementation (`:desktop:gtk`).
 */
class GtkApplicationNativeImpl :
    DesktopApplicationNativeImpl<GtkWidgetHandle>(
        nativeApplication = GtkWidgetHandle(gtkTypeName = "AdwApplication")
    ) {

    init {
        installGtkThemeDetector()
    }

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
    }

    override fun onNativeRun() {
        nativeWidget.attributes["gApplicationRunning"] = true
    }

    override fun onNativeExit(exitCode: Int) {
        nativeWidget.attributes["gApplicationRunning"] = false
        nativeWidget.attributes["gApplicationExitCode"] = exitCode
    }

    companion object {
        fun detectGtkSystemTheme(): ThemeMode {
            val gtkTheme = getenv("GTK_THEME")?.toKString()?.trim().orEmpty()
            val colorScheme = getenv("COLOR_SCHEME")?.toKString()?.trim().orEmpty()
            if (
                gtkTheme.contains("dark", ignoreCase = true) ||
                colorScheme.contains("dark", ignoreCase = true)
            ) {
                return ThemeMode.DARK
            }
            return ThemeMode.LIGHT
        }

        fun installGtkThemeDetector() {
            SystemThemeDetector.backendDetector = { detectGtkSystemTheme() }
        }

        /**
         * Resolves [cursor] to an [ImageBuffer] for `gdk_cursor_new_from_texture(texture, hotspotX, hotspotY, fallback)`.
         */
        fun resolveGdkCursorTexture(cursor: CssCustomCursor): ImageBuffer? =
            cursor.resolveToImageBuffer()
    }
}
