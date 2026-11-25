package com.nexustools.genericui.linux.titlebar

import com.nexustools.genericui.style.color.RgbaColor

/**
 * Immutable snapshot of Linux desktop environment variables, EWMH WM name, GSettings/Portal
 * theme values, KWin/Xfce configuration files, GTK CSS overrides, and optional Xfwm4 assets.
 *
 * Constructed either from the live OS by [LinuxWmDetector.probeLiveEnvironment] or directly in
 * deterministic unit tests for [LinuxWmDetector.detectFromSnapshot].
 */
data class LinuxWmEnvironmentSnapshot(
    val xdgCurrentDesktop: String? = null,
    val xdgSessionDesktop: String? = null,
    val xdgSessionType: String? = null,
    val desktopSession: String? = null,
    val waylandDisplay: String? = null,
    val display: String? = null,
    val swaySock: String? = null,
    val hyprlandInstanceSignature: String? = null,
    val i3Sock: String? = null,
    val ewmhWmName: String? = null,
    val gnomeGtkTheme: String? = null,
    val gnomeColorScheme: String? = null,
    val gnomeAccentColor: String? = null,
    val gnomeWmButtonLayout: String? = null,
    val cinnamonThemeName: String? = null,
    val cinnamonWmTheme: String? = null,
    val cinnamonWmButtonLayout: String? = null,
    val marcoTheme: String? = null,
    val marcoButtonLayout: String? = null,
    val mateGtkTheme: String? = null,
    val portalColorScheme: Int? = null,
    val portalAccentColor: RgbaColor? = null,
    val kwinRcContent: String? = null,
    val breezeRcContent: String? = null,
    val kdeGlobalsContent: String? = null,
    val xfwm4XmlContent: String? = null,
    val xsettingsXmlContent: String? = null,
    val xfwm4ThemeNameOverride: String? = null,
    val xfwm4ThemeDir: String? = null,
    val xfwm4ThemeFiles: Map<String, ByteArray>? = null,
    val xfwm4Assets: LinuxXfwm4ThemeAssets? = null,
    val gtk4UserCssContent: String? = null,
    val gtk3UserCssContent: String? = null,
    val probeErrors: List<String> = emptyList()
)
