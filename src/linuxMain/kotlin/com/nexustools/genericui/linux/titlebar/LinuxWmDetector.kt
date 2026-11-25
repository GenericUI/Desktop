@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.linux.titlebar

import com.nexustools.genericui.linux.LinuxGlibGioBindings
import com.nexustools.genericui.linux.LinuxPosixPaths
import com.nexustools.genericui.linux.LinuxX11WindowHintsBridge
import com.nexustools.genericui.style.color.RgbaColor
import kotlinx.cinterop.toKString
import platform.posix.F_OK
import platform.posix.access
import platform.posix.getenv

/**
 * Pure-Kotlin Linux window manager & titlebar theme detector.
 *
 * Supports live environment probing via [probeLiveEnvironment] (safe GSettings schema lookup,
 * XDG Desktop Portal `org.freedesktop.portal.Settings`, EWMH `_NET_SUPPORTING_WM_CHECK` / `_NET_WM_NAME`,
 * KDE `kwinrc`/`breezerc`/`kdeglobals`, Xfce4 `xfwm4.xml`/`xsettings.xml` + `themerc`/`.xpm`/`.png`,
 * and user GTK CSS inspection) as well as deterministic unit testing via [detectFromSnapshot].
 */
object LinuxWmDetector {

    private val TILING_OR_MINIMAL_WMS = setOf(
        "sway",
        "hyprland",
        "i3",
        "i3wm",
        "bspwm",
        "dwm",
        "awesome",
        "qtile",
        "xmonad",
        "niri",
        "river",
        "dwl",
        "herbstluftwm",
        "spectrwm"
    )

    private val UNSUPPORTED_STACKING_WMS = setOf(
        "openbox",
        "labwc",
        "wayfire",
        "fluxbox",
        "icewm",
        "cosmic",
        "cosmic-comp",
        "enlightenment",
        "e16",
        "moksha",
        "pekwm",
        "jwm",
        "twm",
        "weston",
        "compiz"
    )

    private val SUPPORTED_KWIN_BREEZE_THEMES = setOf(
        "",
        "breeze",
        "breezedark",
        "breezelight",
        "breezeclassic",
        "breezehighcontrast"
    )

    fun detectCurrent(): LinuxTitleBarProfile = detectFromSnapshot(probeLiveEnvironment())

    fun probeLiveEnvironment(): LinuxWmEnvironmentSnapshot {
        val probeErrors = ArrayList<String>()
        val xdgCurrentDesktop = readEnv("XDG_CURRENT_DESKTOP")
        val xdgSessionDesktop = readEnv("XDG_SESSION_DESKTOP")
        val xdgSessionType = readEnv("XDG_SESSION_TYPE")
        val desktopSession = readEnv("DESKTOP_SESSION")
        val waylandDisplay = readEnv("WAYLAND_DISPLAY")
        val display = readEnv("DISPLAY")
        val swaySock = readEnv("SWAYSOCK")
        val hyprlandSig = readEnv("HYPRLAND_INSTANCE_SIGNATURE")
        val i3Sock = readEnv("I3SOCK")

        val ewmhWmName = if (!display.isNullOrBlank()) {
            try {
                LinuxX11WindowHintsBridge.queryEwmhWmName()
            } catch (t: Throwable) {
                probeErrors.add("EWMH WM query failed: ${t.message ?: t::class.simpleName}")
                null
            }
        } else {
            null
        }

        val configHome = resolveXdgConfigHome()
        val gtk4SettingsIni = readTextFile("$configHome/gtk-4.0/settings.ini")
            ?.let { LinuxIniAndXmlConfigParser.parseIni(it) }
        val gtk3SettingsIni = readTextFile("$configHome/gtk-3.0/settings.ini")
            ?.let { LinuxIniAndXmlConfigParser.parseIni(it) }

        val iniGtkTheme = gtk4SettingsIni?.let {
            LinuxIniAndXmlConfigParser.getIniValue(it, "Settings", "gtk-theme-name")
        } ?: gtk3SettingsIni?.let {
            LinuxIniAndXmlConfigParser.getIniValue(it, "Settings", "gtk-theme-name")
        }
        val iniPreferDark = gtk4SettingsIni?.let {
            LinuxIniAndXmlConfigParser.getIniValue(it, "Settings", "gtk-application-prefer-dark-theme")
        } ?: gtk3SettingsIni?.let {
            LinuxIniAndXmlConfigParser.getIniValue(it, "Settings", "gtk-application-prefer-dark-theme")
        }

        val gnomeGtkTheme = LinuxGlibGioBindings.gsettingsReadString("org.gnome.desktop.interface", "gtk-theme")
            ?: iniGtkTheme
        val gnomeColorScheme = LinuxGlibGioBindings.gsettingsReadString("org.gnome.desktop.interface", "color-scheme")
            ?: if (LinuxIniAndXmlConfigParser.parseBoolean(iniPreferDark, false)) "prefer-dark" else null
        val gnomeAccentColor = LinuxGlibGioBindings.gsettingsReadString("org.gnome.desktop.interface", "accent-color")
        val gnomeWmButtonLayout = LinuxGlibGioBindings.gsettingsReadString("org.gnome.desktop.wm.preferences", "button-layout")

        val cinnamonThemeName = LinuxGlibGioBindings.gsettingsReadString("org.cinnamon.desktop.interface", "gtk-theme")
        val cinnamonWmTheme = LinuxGlibGioBindings.gsettingsReadString("org.cinnamon.desktop.wm.preferences", "theme")
        val cinnamonWmButtonLayout = LinuxGlibGioBindings.gsettingsReadString("org.cinnamon.desktop.wm.preferences", "button-layout")

        val marcoTheme = LinuxGlibGioBindings.gsettingsReadString("org.mate.Marco.general", "theme")
        val marcoButtonLayout = LinuxGlibGioBindings.gsettingsReadString("org.mate.Marco.general", "button-layout")
        val mateGtkTheme = LinuxGlibGioBindings.gsettingsReadString("org.mate.interface", "gtk-theme")

        var portalColorScheme: Int? = null
        var portalAccentColor: RgbaColor? = null
        if (isSessionBusAvailable()) {
            portalColorScheme = LinuxGlibGioBindings.readPortalAppearanceColorScheme(probeErrors)
            portalAccentColor = LinuxGlibGioBindings.readPortalAppearanceAccentColor(probeErrors)
                ?.let { (r, g, b) ->
                    RgbaColor(
                        r = (r * 255.0 + 0.5).toInt().coerceIn(0, 255),
                        g = (g * 255.0 + 0.5).toInt().coerceIn(0, 255),
                        b = (b * 255.0 + 0.5).toInt().coerceIn(0, 255),
                        a = 255
                    )
                }
        }

        val kwinRcContent = readTextFile("$configHome/kwinrc")
        val breezeRcContent = readTextFile("$configHome/breezerc")
        val kdeGlobalsContent = readTextFile("$configHome/kdeglobals")
        val xfwm4XmlContent = readTextFile("$configHome/xfce4/xfconf/xfce-perchannel-xml/xfwm4.xml")
        val xsettingsXmlContent = readTextFile("$configHome/xfce4/xfconf/xfce-perchannel-xml/xsettings.xml")
        val gtk4UserCssContent = readTextFile("$configHome/gtk-4.0/gtk.css")
        val gtk3UserCssContent = readTextFile("$configHome/gtk-3.0/gtk.css")

        return LinuxWmEnvironmentSnapshot(
            xdgCurrentDesktop = xdgCurrentDesktop,
            xdgSessionDesktop = xdgSessionDesktop,
            xdgSessionType = xdgSessionType,
            desktopSession = desktopSession,
            waylandDisplay = waylandDisplay,
            display = display,
            swaySock = swaySock,
            hyprlandInstanceSignature = hyprlandSig,
            i3Sock = i3Sock,
            ewmhWmName = ewmhWmName,
            gnomeGtkTheme = gnomeGtkTheme,
            gnomeColorScheme = gnomeColorScheme,
            gnomeAccentColor = gnomeAccentColor,
            gnomeWmButtonLayout = gnomeWmButtonLayout,
            cinnamonThemeName = cinnamonThemeName,
            cinnamonWmTheme = cinnamonWmTheme,
            cinnamonWmButtonLayout = cinnamonWmButtonLayout,
            marcoTheme = marcoTheme,
            marcoButtonLayout = marcoButtonLayout,
            mateGtkTheme = mateGtkTheme,
            portalColorScheme = portalColorScheme,
            portalAccentColor = portalAccentColor,
            kwinRcContent = kwinRcContent,
            breezeRcContent = breezeRcContent,
            kdeGlobalsContent = kdeGlobalsContent,
            xfwm4XmlContent = xfwm4XmlContent,
            xsettingsXmlContent = xsettingsXmlContent,
            gtk4UserCssContent = gtk4UserCssContent,
            gtk3UserCssContent = gtk3UserCssContent,
            probeErrors = probeErrors
        )
    }

    fun detectFromSnapshot(snapshot: LinuxWmEnvironmentSnapshot): LinuxTitleBarProfile {
        val isDarkHint = resolveGeneralDarkHint(snapshot)

        // 1. Reject tiling / minimal WM environment sockets immediately.
        if (!snapshot.swaySock.isNullOrBlank()) {
            return LinuxThemePaletteCatalog.buildUnsupportedProfile(
                wmType = LinuxWmType.UNKNOWN_OR_UNSUPPORTED,
                themeName = "Sway",
                reason = "Unsupported tiling compositor Sway (SWAYSOCK present)",
                isDark = isDarkHint
            )
        }
        if (!snapshot.hyprlandInstanceSignature.isNullOrBlank()) {
            return LinuxThemePaletteCatalog.buildUnsupportedProfile(
                wmType = LinuxWmType.UNKNOWN_OR_UNSUPPORTED,
                themeName = "Hyprland",
                reason = "Unsupported tiling compositor Hyprland (HYPRLAND_INSTANCE_SIGNATURE present)",
                isDark = isDarkHint
            )
        }
        if (!snapshot.i3Sock.isNullOrBlank()) {
            return LinuxThemePaletteCatalog.buildUnsupportedProfile(
                wmType = LinuxWmType.UNKNOWN_OR_UNSUPPORTED,
                themeName = "i3",
                reason = "Unsupported tiling WM i3 (I3SOCK present)",
                isDark = isDarkHint
            )
        }

        val wmTokens = tokenizeEnvIdentifier(snapshot.ewmhWmName)
        val desktopTokens = (
            tokenizeEnvIdentifier(snapshot.xdgCurrentDesktop) +
                tokenizeEnvIdentifier(snapshot.xdgSessionDesktop) +
                tokenizeEnvIdentifier(snapshot.desktopSession)
            )

        // 2. Reject explicit tiling/minimal WMs or unsupported stacking compositors by WM/session tokens.
        for (token in wmTokens + desktopTokens) {
            if (token in TILING_OR_MINIMAL_WMS) {
                return LinuxThemePaletteCatalog.buildUnsupportedProfile(
                    wmType = LinuxWmType.UNKNOWN_OR_UNSUPPORTED,
                    themeName = snapshot.ewmhWmName ?: token,
                    reason = "Unsupported tiling/minimal window manager '$token'",
                    isDark = isDarkHint
                )
            }
            if (token in UNSUPPORTED_STACKING_WMS) {
                return LinuxThemePaletteCatalog.buildUnsupportedProfile(
                    wmType = LinuxWmType.UNKNOWN_OR_UNSUPPORTED,
                    themeName = snapshot.ewmhWmName ?: token,
                    reason = "Unsupported stacking window manager/compositor '$token'",
                    isDark = isDarkHint
                )
            }
        }

        // 3. Identify the active window manager stack.
        val detectedWm = identifySupportedWm(snapshot, wmTokens, desktopTokens)
            ?: return LinuxThemePaletteCatalog.buildUnsupportedProfile(
                wmType = LinuxWmType.UNKNOWN_OR_UNSUPPORTED,
                themeName = snapshot.ewmhWmName ?: snapshot.xdgCurrentDesktop ?: "Unknown",
                reason = "Unrecognized or unsupported Linux window manager " +
                    "(ewmhWmName=${snapshot.ewmhWmName}, XDG_CURRENT_DESKTOP=${snapshot.xdgCurrentDesktop})",
                isDark = isDarkHint
            )

        // 4. For GTK/X11-themed WMs, reject structural overrides in user gtk-4.0 / gtk-3.0 CSS.
        if (detectedWm != LinuxWmType.KWIN_BREEZE) {
            val cssInspection = LinuxGtkCssOverrideInspector.inspect(
                gtk4Css = snapshot.gtk4UserCssContent,
                gtk3Css = snapshot.gtk3UserCssContent
            )
            if (cssInspection.hasStructuralOverride) {
                return LinuxThemePaletteCatalog.buildUnsupportedProfile(
                    wmType = detectedWm,
                    themeName = snapshot.gnomeGtkTheme ?: detectedWm.name,
                    reason = "Structural GTK CSS overrides in ${cssInspection.sourceLabels.joinToString(", ")} " +
                        "targeting: ${cssInspection.matchedSelectors.joinToString("; ")}",
                    isDark = isDarkHint
                )
            }
        }

        return when (detectedWm) {
            LinuxWmType.KWIN_BREEZE -> resolveKwinProfile(snapshot, isDarkHint)
            LinuxWmType.MUTTER_YARU -> resolveMutterYaruProfile(snapshot, isDarkHint)
            LinuxWmType.MUTTER_LIBADWAITA -> resolveMutterLibadwaitaProfile(snapshot, isDarkHint)
            LinuxWmType.MUFFIN_MINT_Y -> resolveMuffinProfile(snapshot, isDarkHint)
            LinuxWmType.XFWM4 -> resolveXfwm4Profile(snapshot, isDarkHint)
            LinuxWmType.MARCO -> resolveMarcoProfile(snapshot, isDarkHint)
            LinuxWmType.UNKNOWN_OR_UNSUPPORTED -> LinuxThemePaletteCatalog.buildUnsupportedProfile(
                wmType = LinuxWmType.UNKNOWN_OR_UNSUPPORTED,
                themeName = "Unknown",
                reason = "Unsupported Linux window manager",
                isDark = isDarkHint
            )
        }
    }

    private fun identifySupportedWm(
        snapshot: LinuxWmEnvironmentSnapshot,
        wmTokens: List<String>,
        desktopTokens: List<String>
    ): LinuxWmType? {
        val wmLower = snapshot.ewmhWmName?.trim()?.lowercase() ?: ""
        if (wmLower.isNotEmpty()) {
            when {
                wmLower.contains("kwin") -> return LinuxWmType.KWIN_BREEZE
                wmLower.contains("muffin") -> return LinuxWmType.MUFFIN_MINT_Y
                wmLower.contains("xfwm4") -> return LinuxWmType.XFWM4
                wmLower.contains("marco") -> return LinuxWmType.MARCO
                wmLower.contains("gnome shell") || wmLower.contains("mutter") -> {
                    return chooseMutterVariant(snapshot, desktopTokens)
                }
                else -> {
                    // An explicit unrecognized EWMH WM name (e.g. custom stacking WM) must be rejected.
                    return null
                }
            }
        }

        return when {
            desktopTokens.any { it == "kde" || it == "plasma" || it == "plasmawayland" || it == "plasmax11" } ->
                LinuxWmType.KWIN_BREEZE
            desktopTokens.any { it == "cinnamon" || it == "x-cinnamon" } ->
                LinuxWmType.MUFFIN_MINT_Y
            desktopTokens.any { it == "xfce" || it == "xfce4" || it == "xubuntu" } ->
                LinuxWmType.XFWM4
            desktopTokens.any { it == "mate" } ->
                LinuxWmType.MARCO
            desktopTokens.any { it == "gnome" || it == "ubuntu" || it == "pop" || it == "zorin" || it == "gnome-xorg" || it == "gnome-wayland" } ->
                chooseMutterVariant(snapshot, desktopTokens)
            else -> null
        }
    }

    private fun chooseMutterVariant(
        snapshot: LinuxWmEnvironmentSnapshot,
        desktopTokens: List<String>
    ): LinuxWmType {
        val gtkThemeLower = snapshot.gnomeGtkTheme?.trim()?.lowercase() ?: ""
        return if (gtkThemeLower.startsWith("yaru") || (gtkThemeLower.isEmpty() && "ubuntu" in desktopTokens)) {
            LinuxWmType.MUTTER_YARU
        } else {
            LinuxWmType.MUTTER_LIBADWAITA
        }
    }

    private fun resolveMutterYaruProfile(
        snapshot: LinuxWmEnvironmentSnapshot,
        isDarkHint: Boolean
    ): LinuxTitleBarProfile {
        val themeName = snapshot.gnomeGtkTheme?.trim()?.takeIf { it.isNotEmpty() } ?: "Yaru"
        val parsed = LinuxThemePaletteCatalog.parseYaruVariant(themeName)
            ?: return LinuxThemePaletteCatalog.buildUnsupportedProfile(
                wmType = LinuxWmType.MUTTER_YARU,
                themeName = themeName,
                reason = "Unrecognized Yaru/GTK theme variant '$themeName'",
                isDark = isDarkHint
            )

        var (accentKey, themeDark) = parsed
        // Ubuntu 24.10+ (GNOME 47+) can pair base `Yaru`/`Yaru-dark` with `accent-color` in GSettings.
        if (accentKey == "default" && !snapshot.gnomeAccentColor.isNullOrBlank()) {
            val gAccent = snapshot.gnomeAccentColor.trim().lowercase()
            if (gAccent in LinuxThemePaletteCatalog.YARU_VARIANTS) {
                accentKey = gAccent
            }
        }

        val isDark = when {
            themeName.endsWith("-dark", ignoreCase = true) -> true
            themeName.endsWith("-light", ignoreCase = true) -> false
            else -> themeDark || isDarkHint
        }
        return LinuxThemePaletteCatalog.resolveYaruProfile(
            themeName = themeName,
            accentKey = accentKey,
            isDark = isDark,
            buttonLayout = snapshot.gnomeWmButtonLayout
        )
    }

    private fun resolveMutterLibadwaitaProfile(
        snapshot: LinuxWmEnvironmentSnapshot,
        isDarkHint: Boolean
    ): LinuxTitleBarProfile {
        val gtkTheme = snapshot.gnomeGtkTheme?.trim()
        if (!LinuxThemePaletteCatalog.isRecognizedAdwaitaTheme(gtkTheme)) {
            return LinuxThemePaletteCatalog.buildUnsupportedProfile(
                wmType = LinuxWmType.MUTTER_LIBADWAITA,
                themeName = gtkTheme ?: "",
                reason = "Unrecognized GTK theme '$gtkTheme' under GNOME/Mutter",
                isDark = isDarkHint
            )
        }

        val isDark = when {
            gtkTheme?.contains("dark", ignoreCase = true) == true -> true
            snapshot.gnomeColorScheme?.equals("prefer-dark", ignoreCase = true) == true -> true
            snapshot.gnomeColorScheme?.equals("prefer-light", ignoreCase = true) == true -> false
            else -> isDarkHint
        }

        return LinuxThemePaletteCatalog.resolveLibadwaitaProfile(
            isDark = isDark,
            accentKey = snapshot.gnomeAccentColor,
            portalAccent = snapshot.portalAccentColor,
            buttonLayout = snapshot.gnomeWmButtonLayout
        )
    }

    private fun resolveKwinProfile(
        snapshot: LinuxWmEnvironmentSnapshot,
        isDarkHint: Boolean
    ): LinuxTitleBarProfile {
        val kwinSections = snapshot.kwinRcContent?.let { LinuxIniAndXmlConfigParser.parseIni(it) } ?: emptyMap()
        val breezeSections = snapshot.breezeRcContent?.let { LinuxIniAndXmlConfigParser.parseIni(it) } ?: emptyMap()
        val kdeGlobalsSections = snapshot.kdeGlobalsContent?.let { LinuxIniAndXmlConfigParser.parseIni(it) } ?: emptyMap()

        val library = LinuxIniAndXmlConfigParser.getIniValue(
            kwinSections, "org.kde.kdecoration2", "library", ignoreCaseSection = true, ignoreCaseKey = true
        )?.trim() ?: ""
        val theme = LinuxIniAndXmlConfigParser.getIniValue(
            kwinSections, "org.kde.kdecoration2", "theme", ignoreCaseSection = true, ignoreCaseKey = true
        )?.trim() ?: ""

        if (library.isNotEmpty() && !library.equals("org.kde.breeze", ignoreCase = true)) {
            return LinuxThemePaletteCatalog.buildUnsupportedProfile(
                wmType = LinuxWmType.KWIN_BREEZE,
                themeName = theme.ifEmpty { library },
                reason = "Unsupported KWin kdecoration2 plugin library='$library' (only org.kde.breeze is supported)",
                isDark = isDarkHint
            )
        }

        val normalizedTheme = theme.lowercase()
        if (normalizedTheme !in SUPPORTED_KWIN_BREEZE_THEMES) {
            return LinuxThemePaletteCatalog.buildUnsupportedProfile(
                wmType = LinuxWmType.KWIN_BREEZE,
                themeName = theme,
                reason = "Unsupported KWin kdecoration2 theme='$theme' (custom/Aurorae decorations are not replicated)",
                isDark = isDarkHint
            )
        }

        val hasLeftKey = LinuxIniAndXmlConfigParser.hasIniKey(
            kwinSections, "org.kde.kdecoration2", "ButtonsOnLeft", ignoreCaseSection = true, ignoreCaseKey = true
        )
        val hasRightKey = LinuxIniAndXmlConfigParser.hasIniKey(
            kwinSections, "org.kde.kdecoration2", "ButtonsOnRight", ignoreCaseSection = true, ignoreCaseKey = true
        )
        val leftSpec = if (hasLeftKey) {
            LinuxIniAndXmlConfigParser.getIniValue(
                kwinSections, "org.kde.kdecoration2", "ButtonsOnLeft", ignoreCaseSection = true, ignoreCaseKey = true
            ) ?: ""
        } else {
            "M"
        }
        val rightSpec = if (hasRightKey) {
            LinuxIniAndXmlConfigParser.getIniValue(
                kwinSections, "org.kde.kdecoration2", "ButtonsOnRight", ignoreCaseSection = true, ignoreCaseKey = true
            ) ?: ""
        } else {
            "IAX"
        }

        val leftButtons = LinuxThemePaletteCatalog.parseKwinButtonChars(leftSpec)
        val rightButtons = LinuxThemePaletteCatalog.parseKwinButtonChars(rightSpec)

        return LinuxThemePaletteCatalog.resolveBreezeProfile(
            themeName = theme,
            leftButtons = leftButtons,
            rightButtons = rightButtons,
            kdeGlobalsSections = kdeGlobalsSections,
            breezeRcSections = breezeSections,
            portalColorScheme = snapshot.portalColorScheme,
            portalAccent = snapshot.portalAccentColor,
            isDarkHint = if (snapshot.kdeGlobalsContent.isNullOrBlank() && theme.isEmpty()) isDarkHint else null
        )
    }

    private fun resolveMuffinProfile(
        snapshot: LinuxWmEnvironmentSnapshot,
        isDarkHint: Boolean
    ): LinuxTitleBarProfile {
        val rawTheme = snapshot.cinnamonWmTheme?.trim()?.takeIf { it.isNotEmpty() }
            ?: snapshot.cinnamonThemeName?.trim()?.takeIf { it.isNotEmpty() }
            ?: snapshot.gnomeGtkTheme?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Mint-Y"

        val parsed = LinuxThemePaletteCatalog.parseMintYVariant(rawTheme)
            ?: return LinuxThemePaletteCatalog.buildUnsupportedProfile(
                wmType = LinuxWmType.MUFFIN_MINT_Y,
                themeName = rawTheme,
                reason = "Unsupported Cinnamon/Muffin Metacity theme '$rawTheme' (only Mint-Y variants are supported)",
                isDark = isDarkHint
            )

        val (accentKey, variantDark) = parsed
        val isDark = variantDark || (rawTheme.equals("Mint-Y", ignoreCase = true) && isDarkHint)
        return LinuxThemePaletteCatalog.resolveMintYProfile(
            themeName = rawTheme,
            accentKey = accentKey,
            isDark = isDark,
            buttonLayout = snapshot.cinnamonWmButtonLayout ?: snapshot.gnomeWmButtonLayout
        )
    }

    private fun resolveXfwm4Profile(
        snapshot: LinuxWmEnvironmentSnapshot,
        isDarkHint: Boolean
    ): LinuxTitleBarProfile {
        val xfwm4Props = snapshot.xfwm4XmlContent?.let { LinuxIniAndXmlConfigParser.parseXfconfXml(it) } ?: emptyMap()
        val xsettingsProps = snapshot.xsettingsXmlContent?.let { LinuxIniAndXmlConfigParser.parseXfconfXml(it) } ?: emptyMap()

        val themeName = snapshot.xfwm4ThemeNameOverride?.trim()?.takeIf { it.isNotEmpty() }
            ?: xfwm4Props["general/theme"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: xsettingsProps["Net/ThemeName"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Default"

        val assets: LinuxXfwm4ThemeAssets
        if (snapshot.xfwm4Assets != null) {
            assets = snapshot.xfwm4Assets
        } else {
            val loadResult = when {
                snapshot.xfwm4ThemeFiles != null ->
                    LinuxXfwm4ThemeLoader.loadFromFileMap(themeName, snapshot.xfwm4ThemeFiles)
                !snapshot.xfwm4ThemeDir.isNullOrBlank() ->
                    LinuxXfwm4ThemeLoader.loadFromDirectory(themeName, snapshot.xfwm4ThemeDir)
                else ->
                    LinuxXfwm4ThemeLoader.loadFromSystemThemes(themeName)
            }
            val loaded = loadResult.assets
            if (loaded == null) {
                return LinuxThemePaletteCatalog.buildUnsupportedProfile(
                    wmType = LinuxWmType.XFWM4,
                    themeName = themeName,
                    reason = loadResult.errorReason ?: "Missing Xfwm4 theme assets for '$themeName'",
                    isDark = isDarkHint
                )
            }
            assets = loaded
        }

        return LinuxThemePaletteCatalog.resolveXfwm4Profile(
            themeName = themeName,
            assets = assets,
            buttonLayout = xfwm4Props["general/button_layout"]
        )
    }

    private fun resolveMarcoProfile(
        snapshot: LinuxWmEnvironmentSnapshot,
        isDarkHint: Boolean
    ): LinuxTitleBarProfile {
        val themeName = snapshot.marcoTheme?.trim()?.takeIf { it.isNotEmpty() }
            ?: snapshot.mateGtkTheme?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Menta"

        return LinuxThemePaletteCatalog.resolveMarcoProfile(
            themeName = themeName,
            buttonLayout = snapshot.marcoButtonLayout ?: snapshot.gnomeWmButtonLayout
        ) ?: LinuxThemePaletteCatalog.buildUnsupportedProfile(
            wmType = LinuxWmType.MARCO,
            themeName = themeName,
            reason = "Unsupported Marco/Metacity theme '$themeName' " +
                "(supported: Menta, BlackMATE, TraditionalOk, TraditionalGreen, Yaru-MATE*)",
            isDark = isDarkHint
        )
    }

    private fun resolveGeneralDarkHint(snapshot: LinuxWmEnvironmentSnapshot): Boolean {
        val colorScheme = snapshot.gnomeColorScheme?.trim()?.lowercase()
        if (colorScheme == "prefer-dark") return true
        if (colorScheme == "prefer-light") return false
        if (snapshot.portalColorScheme == 1) return true
        if (snapshot.portalColorScheme == 2) return false
        val gtkLower = snapshot.gnomeGtkTheme?.lowercase() ?: ""
        return gtkLower.endsWith("-dark")
    }

    private fun tokenizeEnvIdentifier(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return value.lowercase()
            .split(':', ',', ';', ' ', '\t', '(', ')', '/')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun readEnv(name: String): String? =
        getenv(name)?.toKString()?.trim()?.takeIf { it.isNotEmpty() }

    private fun resolveXdgConfigHome(): String {
        val xdg = readEnv("XDG_CONFIG_HOME")
        if (xdg != null) return xdg.trimEnd('/')
        val home = readEnv("HOME")
        if (home != null) return "${home.trimEnd('/')}/.config"
        return ".config"
    }

    private fun readTextFile(path: String): String? =
        LinuxPosixPaths.readAllBytes(path)?.decodeToString()

    private fun isSessionBusAvailable(): Boolean {
        if (!readEnv("DBUS_SESSION_BUS_ADDRESS").isNullOrEmpty()) return true
        val runtimeDir = readEnv("XDG_RUNTIME_DIR") ?: return false
        return access("${runtimeDir.trimEnd('/')}/bus", F_OK) == 0
    }
}
