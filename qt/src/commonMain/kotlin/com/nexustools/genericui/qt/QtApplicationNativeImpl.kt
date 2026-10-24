package com.nexustools.genericui.qt

import com.nexustools.genericui.desktop.shell.DesktopApplicationNativeImpl
import com.nexustools.genericui.enums.ui.ThemeMode

/**
 * Qt 6 `QApplication` implementation of [com.nexustools.genericui.nativeimpl.shell.ApplicationNativeImpl]
 * managing `QGuiApplication::styleHints()->setColorScheme(...)` (`Qt::ColorScheme::Dark` / `Light` / `Unknown`).
 */
class QtApplicationNativeImpl : DesktopApplicationNativeImpl<QtWidgetHandle>(
    nativeApplication = QtWidgetHandle("QApplication")
) {
    override fun setThemeMode(mode: ThemeMode) {
        super.setThemeMode(mode)
        nativeApplication.attributes["qtColorScheme"] = when (mode) {
            ThemeMode.DARK -> "Qt::ColorScheme::Dark"
            ThemeMode.LIGHT -> "Qt::ColorScheme::Light"
            ThemeMode.SYSTEM -> "Qt::ColorScheme::Unknown"
        }
    }
}
