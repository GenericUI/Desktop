package com.nexustools.genericui.macos

import com.nexustools.genericui.desktop.shell.DesktopApplicationNativeImpl
import com.nexustools.genericui.enums.ui.ThemeMode

/**
 * macOS AppKit `NSApplication` (`NSApp`) implementation of
 * [com.nexustools.genericui.nativeimpl.shell.ApplicationNativeImpl] managing `NSApplicationActivationPolicyRegular`,
 * `NSAppearanceNameDarkAqua` / `NSAppearanceNameAqua`, and `NSApplication.sharedApplication.mainMenu`.
 */
class MacosApplicationNativeImpl : DesktopApplicationNativeImpl<MacosViewHandle>(
    nativeApplication = MacosViewHandle("NSApplication")
) {
    init {
        nativeApplication.attributes["activationPolicy"] = "NSApplicationActivationPolicyRegular"
        nativeApplication.attributes["appearance"] = "NSAppearanceNameAqua"
    }

    override fun setThemeMode(mode: ThemeMode) {
        super.setThemeMode(mode)
        nativeApplication.attributes["appearance"] = when (mode) {
            ThemeMode.DARK -> "NSAppearanceNameDarkAqua"
            ThemeMode.LIGHT -> "NSAppearanceNameAqua"
            ThemeMode.SYSTEM -> "SystemAppearance"
        }
    }
}
