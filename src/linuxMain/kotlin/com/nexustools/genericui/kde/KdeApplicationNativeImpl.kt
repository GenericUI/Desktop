@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.desktop.shell.DesktopApplicationNativeImpl
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.linux.titlebar.LinuxIniAndXmlConfigParser
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssCustomCursor
import com.nexustools.genericui.style.theme.SystemThemeDetector
import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * KDE Frameworks 6 + Breeze (`Qt 6`) implementation of [DesktopApplicationNativeImpl].
 */
class KdeApplicationNativeImpl :
    DesktopApplicationNativeImpl<KdeWidgetHandle>(
        nativeApplication = KdeWidgetHandle(kdeTypeName = "QApplication_KDE_Breeze")
    ) {

    init {
        installKdeThemeDetector()
    }

    override fun detectSystemThemeMode(): ThemeMode =
        SystemThemeDetector.overrideDetectedTheme?.takeIf { !it.isAuto } ?: detectKdeSystemTheme()

    override fun setThemeMode(mode: ThemeMode) {
        super.setThemeMode(mode)
        installKdeThemeDetector()
        val effective = SystemThemeDetector.resolveTheme(mode)
        val isDark = effective == ThemeMode.DARK
        nativeWidget.attributes["effectiveThemeMode"] = effective
        nativeWidget.attributes["kdeColorScheme"] = if (isDark) "BreezeDark" else "BreezeLight"
        if (interactiveKdeGuiEnabled) {
            KdeDynamicBindings.applyBreezeStyleAndPalette(isDark)
            KdeNativeWindowHost.syncThemeAppearance(mode)
        }
    }

    override fun onNativeRun() {
        nativeWidget.attributes["qApplicationRunning"] = true
    }

    override fun runEventLoop(onStarted: () -> Unit) {
        if (interactiveKdeGuiEnabled) {
            KdeNativeWindowHost.prepareEventLoop()
        }
        super.runEventLoop(onStarted)
        if (interactiveKdeGuiEnabled && running) {
            KdeNativeWindowHost.runEventLoop()
        }
    }

    override fun onNativeExit(exitCode: Int) {
        nativeWidget.attributes["qApplicationRunning"] = false
        nativeWidget.attributes["qApplicationExitCode"] = exitCode
        KdeSystemTrayNativeImpl.disposeAll()
        if (interactiveKdeGuiEnabled) {
            KdeNativeWindowHost.terminateEventLoop()
        }
    }

    companion object {
        var interactiveKdeGuiEnabled: Boolean = false

        fun detectKdeSystemTheme(): ThemeMode {
            val colorScheme = getenv("COLOR_SCHEME")?.toKString()?.trim().orEmpty()
            val gtkTheme = getenv("GTK_THEME")?.toKString()?.trim().orEmpty()
            val kdeTheme = getenv("KDE_COLOR_SCHEME")?.toKString()?.trim().orEmpty()
            if (colorScheme.contains("dark", ignoreCase = true) ||
                gtkTheme.contains("dark", ignoreCase = true) ||
                kdeTheme.contains("dark", ignoreCase = true)
            ) {
                return ThemeMode.DARK
            }
            val home = getenv("HOME")?.toKString()
            if (!home.isNullOrBlank()) {
                val ini = KdeDynamicBindings.readUtf8FileOrNull("$home/.config/kdeglobals")
                if (ini != null) {
                    val parsed = LinuxIniAndXmlConfigParser.parseIni(ini)
                    val generalScheme = parsed["General"]?.get("ColorScheme").orEmpty()
                    if (generalScheme.contains("dark", ignoreCase = true)) {
                        return ThemeMode.DARK
                    }
                    if (generalScheme.contains("light", ignoreCase = true) ||
                        generalScheme.contains("breeze", ignoreCase = true)
                    ) {
                        return ThemeMode.LIGHT
                    }
                }
            }
            return ThemeMode.LIGHT
        }

        fun installKdeThemeDetector() {
            SystemThemeDetector.backendDetector = { detectKdeSystemTheme() }
        }

        fun resolveKdeCursorTexture(cursor: CssCustomCursor): ImageBuffer? =
            cursor.resolveToImageBuffer()

        fun breezeHighlightColor(): RgbaColor =
            KdeDynamicBindings.resolveBreezeAccentColor(detectKdeSystemTheme() == ThemeMode.DARK)
    }
}
