package com.nexustools.genericui.gtk

import com.nexustools.genericui.desktop.shell.DesktopApplicationNativeImpl
import com.nexustools.genericui.enums.ui.ThemeMode

/**
 * GTK 4 / Libadwaita (`AdwApplication` / `GtkApplication` + `GMainLoop`) implementation of
 * [com.nexustools.genericui.nativeimpl.shell.ApplicationNativeImpl] managing `AdwStyleManager` color schemes
 * (`ADW_COLOR_SCHEME_PREFER_DARK` / `ADW_COLOR_SCHEME_FORCE_LIGHT`).
 */
class GtkApplicationNativeImpl : DesktopApplicationNativeImpl<GtkWidgetHandle>(
    nativeApplication = GtkWidgetHandle("AdwApplication")
) {
    override fun setThemeMode(mode: ThemeMode) {
        super.setThemeMode(mode)
        nativeApplication.attributes["adwColorScheme"] = when (mode) {
            ThemeMode.DARK -> "ADW_COLOR_SCHEME_FORCE_DARK"
            ThemeMode.LIGHT -> "ADW_COLOR_SCHEME_FORCE_LIGHT"
            ThemeMode.SYSTEM -> "ADW_COLOR_SCHEME_DEFAULT"
        }
    }
}
