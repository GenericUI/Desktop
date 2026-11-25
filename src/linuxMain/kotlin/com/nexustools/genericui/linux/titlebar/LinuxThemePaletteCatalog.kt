package com.nexustools.genericui.linux.titlebar

import com.nexustools.genericui.style.color.RgbaColor

/**
 * Canonical color palettes, accent tables, button layout parsers, and titlebar metrics
 * across all 6 replicable Linux desktop stacks (Ubuntu Yaru, GNOME Libadwaita, KDE KWin Breeze,
 * Linux Mint Muffin Mint-Y, Xfce4 Xfwm4, and MATE Marco).
 */
object LinuxThemePaletteCatalog {

    val YARU_VARIANTS: Map<String, RgbaColor> = linkedMapOf(
        "default" to RgbaColor(233, 84, 32),        // #E95420 Ubuntu orange
        "purple" to RgbaColor(119, 100, 216),       // #7764D8
        "bark" to RgbaColor(120, 120, 89),          // #787859
        "sage" to RgbaColor(101, 123, 105),         // #657B69
        "olive" to RgbaColor(75, 133, 1),           // #4B8501
        "viridian" to RgbaColor(3, 135, 91),        // #03875B
        "prussianblue" to RgbaColor(48, 130, 128),  // #308280
        "blue" to RgbaColor(0, 115, 229),           // #0073E5
        "red" to RgbaColor(218, 52, 80),            // #DA3450
        "magenta" to RgbaColor(179, 76, 179),       // #B34CB3
        "yellow" to RgbaColor(200, 136, 0)          // #C88800
    )

    val LIBADWAITA_ACCENTS: Map<String, RgbaColor> = linkedMapOf(
        "blue" to RgbaColor(53, 132, 228),          // #3584E4
        "teal" to RgbaColor(33, 144, 164),          // #2190A4
        "green" to RgbaColor(58, 148, 74),          // #3A944A
        "yellow" to RgbaColor(200, 136, 0),         // #C88800
        "orange" to RgbaColor(237, 91, 0),          // #ED5B00
        "red" to RgbaColor(230, 45, 66),            // #E62D42
        "pink" to RgbaColor(213, 97, 153),          // #D56199
        "purple" to RgbaColor(145, 65, 172),        // #9141AC
        "slate" to RgbaColor(111, 131, 150)         // #6F8396
    )

    val MINT_Y_ACCENTS: Map<String, RgbaColor> = linkedMapOf(
        "default" to RgbaColor(53, 168, 84),        // #35A854 Mint-Y green
        "green" to RgbaColor(53, 168, 84),          // #35A854
        "aqua" to RgbaColor(31, 158, 222),          // #1F9EDE
        "blue" to RgbaColor(12, 117, 222),          // #0C75DE
        "brown" to RgbaColor(170, 135, 106),        // #AA876A
        "grey" to RgbaColor(112, 115, 122),         // #70737A
        "orange" to RgbaColor(255, 113, 57),        // #FF7139
        "pink" to RgbaColor(229, 73, 128),          // #E54980
        "purple" to RgbaColor(140, 93, 217),        // #8C5DD9
        "red" to RgbaColor(232, 33, 39),            // #E82127
        "sand" to RgbaColor(197, 160, 124),         // #C5A07C
        "teal" to RgbaColor(25, 156, 168)           // #199CA8
    )

    private val STOCK_ADWAITA_THEMES = setOf(
        "adwaita", "adwaita-dark", "adwaita-empty", "default", "highcontrast", "highcontrastinverse"
    )

    fun isRecognizedAdwaitaTheme(themeName: String?): Boolean {
        val clean = themeName?.trim()?.lowercase() ?: ""
        return clean.isEmpty() || clean in STOCK_ADWAITA_THEMES
    }

    /**
     * Parses a Yaru theme string (e.g. `"Yaru"`, `"Yaru-purple-dark"`, `"Yaru-prussianblue-light"`)
     * into `(accentKey, isDark)` or `null` if not one of the 11 supported Yaru variants.
     */
    fun parseYaruVariant(themeName: String?): Pair<String, Boolean>? {
        val raw = themeName?.trim()?.takeIf { it.isNotEmpty() } ?: return "default" to false
        val lower = raw.lowercase()
        if (lower != "yaru" && !lower.startsWith("yaru-")) return null

        var body = if (lower == "yaru") "" else lower.removePrefix("yaru-")
        var isDark = false
        if (body == "dark") {
            isDark = true
            body = ""
        } else if (body == "light") {
            isDark = false
            body = ""
        } else if (body.endsWith("-dark")) {
            isDark = true
            body = body.removeSuffix("-dark")
        } else if (body.endsWith("-light")) {
            isDark = false
            body = body.removeSuffix("-light")
        }

        val accentKey = if (body.isEmpty()) "default" else body
        if (accentKey !in YARU_VARIANTS) return null
        return accentKey to isDark
    }

    fun resolveYaruProfile(
        themeName: String,
        accentKey: String,
        isDark: Boolean,
        buttonLayout: String?
    ): LinuxTitleBarProfile {
        val accent = YARU_VARIANTS[accentKey] ?: YARU_VARIANTS.getValue("default")
        val palette = if (isDark) {
            LinuxTitleBarPalette(
                activeBgTop = RgbaColor(48, 48, 48),
                activeBgBottom = RgbaColor(40, 40, 40),
                inactiveBgTop = RgbaColor(36, 36, 36),
                inactiveBgBottom = RgbaColor(36, 36, 36),
                activeBorderBottom = RgbaColor(24, 24, 24),
                inactiveBorderBottom = RgbaColor(28, 28, 28),
                topHighlight = RgbaColor(255, 255, 255, 18),
                activeTitleText = RgbaColor(247, 247, 247),
                inactiveTitleText = RgbaColor(156, 156, 156),
                subtitleText = RgbaColor(180, 180, 180),
                accentColor = accent,
                buttonBgNormal = RgbaColor(62, 62, 62),
                buttonBgHover = RgbaColor(78, 78, 78),
                buttonBgPressed = RgbaColor(96, 96, 96),
                buttonIconActive = RgbaColor(245, 245, 245),
                buttonIconInactive = RgbaColor(150, 150, 150),
                closeBgNormal = RgbaColor(66, 66, 66),
                closeBgHover = accent,
                closeBgPressed = accent.adjustBrightness(-26),
                closeIconNormal = RgbaColor(245, 245, 245),
                closeIconHover = RgbaColor.WHITE,
                closeIconPressed = RgbaColor.WHITE,
                outlineCloseButton = false,
                isDark = true
            )
        } else {
            LinuxTitleBarPalette(
                activeBgTop = RgbaColor(238, 238, 238),
                activeBgBottom = RgbaColor(227, 227, 227),
                inactiveBgTop = RgbaColor(246, 246, 246),
                inactiveBgBottom = RgbaColor(246, 246, 246),
                activeBorderBottom = RgbaColor(204, 204, 204),
                inactiveBorderBottom = RgbaColor(220, 220, 220),
                topHighlight = RgbaColor(255, 255, 255, 160),
                activeTitleText = RgbaColor(61, 61, 61),
                inactiveTitleText = RgbaColor(142, 142, 142),
                subtitleText = RgbaColor(110, 110, 110),
                accentColor = accent,
                buttonBgNormal = RgbaColor(216, 216, 216),
                buttonBgHover = RgbaColor(198, 198, 198),
                buttonBgPressed = RgbaColor(180, 180, 180),
                buttonIconActive = RgbaColor(61, 61, 61),
                buttonIconInactive = RgbaColor(142, 142, 142),
                closeBgNormal = RgbaColor(216, 216, 216),
                closeBgHover = accent,
                closeBgPressed = accent.adjustBrightness(-26),
                closeIconNormal = RgbaColor(61, 61, 61),
                closeIconHover = RgbaColor.WHITE,
                closeIconPressed = RgbaColor.WHITE,
                outlineCloseButton = false,
                isDark = false
            )
        }

        val (left, right) = parseColonButtonLayout(buttonLayout ?: ":minimize,maximize,close")
        return LinuxTitleBarProfile(
            wmType = LinuxWmType.MUTTER_YARU,
            themeFamily = LinuxTitleBarThemeFamily.YARU,
            themeName = themeName,
            isReplicable = true,
            fallbackReason = null,
            leftButtons = left,
            rightButtons = right,
            palette = palette,
            metrics = LinuxTitleBarMetrics(
                heightPx = 38,
                compactHeightPx = 32,
                topCornerRadiusPx = 10f,
                horizontalPaddingPx = 8,
                buttonWidthPx = 24,
                buttonHeightPx = 24,
                buttonSpacingPx = 6,
                buttonCircular = true,
                centerTitle = true,
                fontSizePx = 13f,
                fontBold = true,
                drawBottomSeparator = true,
                drawMaximizedBorder = false
            )
        )
    }

    fun resolveLibadwaitaProfile(
        isDark: Boolean,
        accentKey: String?,
        portalAccent: RgbaColor?,
        buttonLayout: String?
    ): LinuxTitleBarProfile {
        val cleanKey = accentKey?.trim()?.lowercase()
        val accent = LIBADWAITA_ACCENTS[cleanKey]
            ?: portalAccent
            ?: LIBADWAITA_ACCENTS.getValue("blue")

        val palette = if (isDark) {
            LinuxTitleBarPalette(
                activeBgTop = RgbaColor(48, 48, 48),
                activeBgBottom = RgbaColor(48, 48, 48),
                inactiveBgTop = RgbaColor(36, 36, 36),
                inactiveBgBottom = RgbaColor(36, 36, 36),
                activeBorderBottom = RgbaColor(27, 27, 27),
                inactiveBorderBottom = RgbaColor(27, 27, 27),
                topHighlight = RgbaColor(255, 255, 255, 18),
                activeTitleText = RgbaColor(255, 255, 255),
                inactiveTitleText = RgbaColor(154, 153, 150),
                subtitleText = RgbaColor(192, 191, 188),
                accentColor = accent,
                buttonBgNormal = RgbaColor(255, 255, 255, 18),
                buttonBgHover = RgbaColor(255, 255, 255, 34),
                buttonBgPressed = RgbaColor(255, 255, 255, 56),
                buttonIconActive = RgbaColor(255, 255, 255),
                buttonIconInactive = RgbaColor(154, 153, 150),
                closeBgNormal = RgbaColor(255, 255, 255, 18),
                closeBgHover = RgbaColor(255, 255, 255, 38),
                closeBgPressed = RgbaColor(255, 255, 255, 60),
                closeIconNormal = RgbaColor(255, 255, 255),
                closeIconHover = RgbaColor(255, 255, 255),
                closeIconPressed = RgbaColor(255, 255, 255),
                outlineCloseButton = false,
                isDark = true
            )
        } else {
            LinuxTitleBarPalette(
                activeBgTop = RgbaColor(250, 250, 250),
                activeBgBottom = RgbaColor(250, 250, 250),
                inactiveBgTop = RgbaColor(250, 250, 250),
                inactiveBgBottom = RgbaColor(250, 250, 250),
                activeBorderBottom = RgbaColor(220, 220, 220),
                inactiveBorderBottom = RgbaColor(232, 232, 232),
                topHighlight = RgbaColor(255, 255, 255, 180),
                activeTitleText = RgbaColor(46, 52, 54),
                inactiveTitleText = RgbaColor(148, 148, 148),
                subtitleText = RgbaColor(110, 110, 110),
                accentColor = accent,
                buttonBgNormal = RgbaColor(0, 0, 0, 14),
                buttonBgHover = RgbaColor(0, 0, 0, 26),
                buttonBgPressed = RgbaColor(0, 0, 0, 46),
                buttonIconActive = RgbaColor(46, 52, 54),
                buttonIconInactive = RgbaColor(148, 148, 148),
                closeBgNormal = RgbaColor(0, 0, 0, 14),
                closeBgHover = RgbaColor(0, 0, 0, 30),
                closeBgPressed = RgbaColor(0, 0, 0, 50),
                closeIconNormal = RgbaColor(46, 52, 54),
                closeIconHover = RgbaColor(46, 52, 54),
                closeIconPressed = RgbaColor(46, 52, 54),
                outlineCloseButton = false,
                isDark = false
            )
        }

        val (left, right) = parseColonButtonLayout(buttonLayout ?: ":close")
        val themeName = buildString {
            append(if (isDark) "Adwaita-dark" else "Adwaita")
            if (!cleanKey.isNullOrEmpty() && cleanKey in LIBADWAITA_ACCENTS) {
                append("-").append(cleanKey)
            }
        }
        return LinuxTitleBarProfile(
            wmType = LinuxWmType.MUTTER_LIBADWAITA,
            themeFamily = LinuxTitleBarThemeFamily.LIBADWAITA,
            themeName = themeName,
            isReplicable = true,
            fallbackReason = null,
            leftButtons = left,
            rightButtons = right,
            palette = palette,
            metrics = LinuxTitleBarMetrics(
                heightPx = 40,
                compactHeightPx = 34,
                topCornerRadiusPx = 12f,
                horizontalPaddingPx = 8,
                buttonWidthPx = 24,
                buttonHeightPx = 24,
                buttonSpacingPx = 6,
                buttonCircular = true,
                centerTitle = true,
                fontSizePx = 13f,
                fontBold = true,
                drawBottomSeparator = true,
                drawMaximizedBorder = false
            )
        )
    }

    /**
     * Parses a Mint-Y theme string (supporting base `Mint-Y`, `Mint-Y-Dark`, `Mint-Y-Light`,
     * `Mint-Y-Darker`, and all 10 Mint-Y accents with `-Dark`/`-Light`/`-Darker` in either order).
     */
    fun parseMintYVariant(themeName: String?): Pair<String, Boolean>? {
        val raw = themeName?.trim()?.takeIf { it.isNotEmpty() } ?: return "default" to false
        val lower = raw.lowercase()
        if (lower != "mint-y" && !lower.startsWith("mint-y-")) return null

        val parts = if (lower == "mint-y") emptyList() else lower.removePrefix("mint-y-").split('-').filter { it.isNotEmpty() }
        var isDark = false
        var accentKey = "default"
        for (tok in parts) {
            when (tok) {
                "dark", "darker" -> isDark = true
                "light" -> isDark = false
                in MINT_Y_ACCENTS -> accentKey = tok
                else -> return null
            }
        }
        return accentKey to isDark
    }

    fun resolveMintYProfile(
        themeName: String,
        accentKey: String,
        isDark: Boolean,
        buttonLayout: String?
    ): LinuxTitleBarProfile {
        val accent = MINT_Y_ACCENTS[accentKey] ?: MINT_Y_ACCENTS.getValue("default")
        val palette = if (isDark) {
            LinuxTitleBarPalette(
                activeBgTop = RgbaColor(47, 47, 47),
                activeBgBottom = RgbaColor(42, 42, 42),
                inactiveBgTop = RgbaColor(54, 54, 54),
                inactiveBgBottom = RgbaColor(54, 54, 54),
                activeBorderBottom = RgbaColor(30, 30, 30),
                inactiveBorderBottom = RgbaColor(36, 36, 36),
                topHighlight = RgbaColor(255, 255, 255, 16),
                activeTitleText = RgbaColor(240, 240, 240),
                inactiveTitleText = RgbaColor(148, 148, 148),
                subtitleText = RgbaColor(176, 176, 176),
                accentColor = accent,
                buttonBgNormal = RgbaColor.TRANSPARENT,
                buttonBgHover = RgbaColor(72, 72, 72),
                buttonBgPressed = RgbaColor(92, 92, 92),
                buttonIconActive = RgbaColor(235, 235, 235),
                buttonIconInactive = RgbaColor(140, 140, 140),
                closeBgNormal = accent,
                closeBgHover = accent.adjustBrightness(22),
                closeBgPressed = accent.adjustBrightness(-26),
                closeIconNormal = RgbaColor.WHITE,
                closeIconHover = RgbaColor.WHITE,
                closeIconPressed = RgbaColor.WHITE,
                outlineCloseButton = false,
                isDark = true
            )
        } else {
            LinuxTitleBarPalette(
                activeBgTop = RgbaColor(240, 240, 240),
                activeBgBottom = RgbaColor(230, 230, 230),
                inactiveBgTop = RgbaColor(245, 245, 245),
                inactiveBgBottom = RgbaColor(245, 245, 245),
                activeBorderBottom = RgbaColor(204, 204, 204),
                inactiveBorderBottom = RgbaColor(218, 218, 218),
                topHighlight = RgbaColor(255, 255, 255, 170),
                activeTitleText = RgbaColor(56, 56, 56),
                inactiveTitleText = RgbaColor(145, 145, 145),
                subtitleText = RgbaColor(112, 112, 112),
                accentColor = accent,
                buttonBgNormal = RgbaColor.TRANSPARENT,
                buttonBgHover = RgbaColor(0, 0, 0, 24),
                buttonBgPressed = RgbaColor(0, 0, 0, 45),
                buttonIconActive = RgbaColor(60, 60, 60),
                buttonIconInactive = RgbaColor(145, 145, 145),
                closeBgNormal = accent,
                closeBgHover = accent.adjustBrightness(20),
                closeBgPressed = accent.adjustBrightness(-24),
                closeIconNormal = RgbaColor.WHITE,
                closeIconHover = RgbaColor.WHITE,
                closeIconPressed = RgbaColor.WHITE,
                outlineCloseButton = false,
                isDark = false
            )
        }

        val (left, right) = parseColonButtonLayout(buttonLayout ?: ":minimize,maximize,close")
        return LinuxTitleBarProfile(
            wmType = LinuxWmType.MUFFIN_MINT_Y,
            themeFamily = LinuxTitleBarThemeFamily.MINT_Y,
            themeName = themeName,
            isReplicable = true,
            fallbackReason = null,
            leftButtons = left,
            rightButtons = right,
            palette = palette,
            metrics = LinuxTitleBarMetrics(
                heightPx = 36,
                compactHeightPx = 30,
                topCornerRadiusPx = 6f,
                horizontalPaddingPx = 8,
                buttonWidthPx = 22,
                buttonHeightPx = 22,
                buttonSpacingPx = 6,
                buttonCircular = true,
                centerTitle = true,
                fontSizePx = 12.5f,
                fontBold = true,
                drawBottomSeparator = true,
                drawMaximizedBorder = false
            )
        )
    }

    fun resolveBreezeProfile(
        themeName: String,
        leftButtons: List<LinuxTitleButtonKind>,
        rightButtons: List<LinuxTitleButtonKind>,
        kdeGlobalsSections: Map<String, Map<String, String>>,
        breezeRcSections: Map<String, Map<String, String>>,
        portalColorScheme: Int?,
        portalAccent: RgbaColor?,
        isDarkHint: Boolean? = null
    ): LinuxTitleBarProfile {
        val colorSchemeName = LinuxIniAndXmlConfigParser.getIniValue(
            kdeGlobalsSections, "General", "ColorScheme", ignoreCaseSection = true, ignoreCaseKey = true
        ) ?: ""
        val headerBg = LinuxIniAndXmlConfigParser.parseColor(
            LinuxIniAndXmlConfigParser.getIniValue(kdeGlobalsSections, "Colors:Header", "BackgroundNormal", ignoreCaseSection = true, ignoreCaseKey = true)
                ?: LinuxIniAndXmlConfigParser.getIniValue(kdeGlobalsSections, "Colors:Window", "BackgroundNormal", ignoreCaseSection = true, ignoreCaseKey = true)
        )
        val headerInactiveBg = LinuxIniAndXmlConfigParser.parseColor(
            LinuxIniAndXmlConfigParser.getIniValue(kdeGlobalsSections, "Colors:Header][Inactive", "BackgroundNormal", ignoreCaseSection = true, ignoreCaseKey = true)
                ?: LinuxIniAndXmlConfigParser.getIniValue(kdeGlobalsSections, "Colors:Window", "BackgroundAlternate", ignoreCaseSection = true, ignoreCaseKey = true)
        )
        val headerFg = LinuxIniAndXmlConfigParser.parseColor(
            LinuxIniAndXmlConfigParser.getIniValue(kdeGlobalsSections, "Colors:Header", "ForegroundNormal", ignoreCaseSection = true, ignoreCaseKey = true)
                ?: LinuxIniAndXmlConfigParser.getIniValue(kdeGlobalsSections, "Colors:Window", "ForegroundNormal", ignoreCaseSection = true, ignoreCaseKey = true)
        )
        val headerInactiveFg = LinuxIniAndXmlConfigParser.parseColor(
            LinuxIniAndXmlConfigParser.getIniValue(kdeGlobalsSections, "Colors:Header][Inactive", "ForegroundNormal", ignoreCaseSection = true, ignoreCaseKey = true)
                ?: LinuxIniAndXmlConfigParser.getIniValue(kdeGlobalsSections, "Colors:Header", "ForegroundInactive", ignoreCaseSection = true, ignoreCaseKey = true)
                ?: LinuxIniAndXmlConfigParser.getIniValue(kdeGlobalsSections, "Colors:Window", "ForegroundInactive", ignoreCaseSection = true, ignoreCaseKey = true)
        )
        val accent = LinuxIniAndXmlConfigParser.parseColor(
            LinuxIniAndXmlConfigParser.getIniValue(kdeGlobalsSections, "General", "AccentColor", ignoreCaseSection = true, ignoreCaseKey = true)
        ) ?: portalAccent ?: RgbaColor(61, 174, 233) // #3DAEE9 Breeze Plasma blue

        val isDark = when {
            isDarkHint != null -> isDarkHint
            headerBg != null -> (headerBg.r * 299 + headerBg.g * 587 + headerBg.b * 114) / 1000 < 128
            colorSchemeName.contains("dark", ignoreCase = true) -> true
            themeName.contains("dark", ignoreCase = true) -> true
            portalColorScheme == 1 -> true
            else -> false
        }

        val drawGradient = LinuxIniAndXmlConfigParser.parseBoolean(
            LinuxIniAndXmlConfigParser.getIniValue(breezeRcSections, "Windeco", "DrawBackgroundGradient", ignoreCaseSection = true, ignoreCaseKey = true)
                ?: LinuxIniAndXmlConfigParser.getIniValue(breezeRcSections, "Common", "DrawBackgroundGradient", ignoreCaseSection = true, ignoreCaseKey = true),
            default = true
        )
        val outlineCloseButton = LinuxIniAndXmlConfigParser.parseBoolean(
            LinuxIniAndXmlConfigParser.getIniValue(breezeRcSections, "Windeco", "OutlineCloseButton", ignoreCaseSection = true, ignoreCaseKey = true),
            default = false
        )
        val drawBorderOnMaximized = LinuxIniAndXmlConfigParser.parseBoolean(
            LinuxIniAndXmlConfigParser.getIniValue(breezeRcSections, "Windeco", "DrawBorderOnMaximizedWindows", ignoreCaseSection = true, ignoreCaseKey = true),
            default = false
        )
        val buttonSizePx = parseBreezeButtonSize(
            LinuxIniAndXmlConfigParser.getIniValue(breezeRcSections, "Windeco", "ButtonSize", ignoreCaseSection = true, ignoreCaseKey = true)
        )

        val defaultActiveBg = if (isDark) RgbaColor(49, 54, 59) else RgbaColor(222, 224, 226)
        val defaultInactiveBg = if (isDark) RgbaColor(42, 46, 50) else RgbaColor(239, 240, 241)
        val defaultActiveText = if (isDark) RgbaColor(239, 240, 241) else RgbaColor(35, 38, 41)
        val defaultInactiveText = if (isDark) RgbaColor(127, 140, 141) else RgbaColor(127, 140, 141)

        val baseActiveBg = headerBg ?: defaultActiveBg
        val baseInactiveBg = headerInactiveBg ?: defaultInactiveBg
        val activeBgTop = if (drawGradient) baseActiveBg.adjustBrightness(6) else baseActiveBg
        val activeBgBottom = if (drawGradient) baseActiveBg.adjustBrightness(-4) else baseActiveBg
        val activeText = headerFg ?: defaultActiveText
        val inactiveText = headerInactiveFg ?: defaultInactiveText

        val breezeCrimson = RgbaColor(218, 68, 83) // #DA4453
        val palette = LinuxTitleBarPalette(
            activeBgTop = activeBgTop,
            activeBgBottom = activeBgBottom,
            inactiveBgTop = baseInactiveBg,
            inactiveBgBottom = baseInactiveBg,
            activeBorderBottom = if (isDark) RgbaColor(31, 35, 38) else RgbaColor(188, 192, 196),
            inactiveBorderBottom = if (isDark) RgbaColor(35, 39, 43) else RgbaColor(206, 209, 212),
            topHighlight = if (isDark) RgbaColor(255, 255, 255, 24) else RgbaColor(255, 255, 255, 160),
            activeTitleText = activeText,
            inactiveTitleText = inactiveText,
            subtitleText = inactiveText,
            accentColor = accent,
            buttonBgNormal = RgbaColor.TRANSPARENT,
            buttonBgHover = if (isDark) RgbaColor(255, 255, 255, 32) else RgbaColor(0, 0, 0, 24),
            buttonBgPressed = if (isDark) RgbaColor(255, 255, 255, 56) else RgbaColor(0, 0, 0, 44),
            buttonIconActive = activeText,
            buttonIconInactive = inactiveText,
            closeBgNormal = if (outlineCloseButton) breezeCrimson else RgbaColor.TRANSPARENT,
            closeBgHover = breezeCrimson,
            closeBgPressed = RgbaColor(182, 46, 59),
            closeIconNormal = activeText,
            closeIconHover = RgbaColor.WHITE,
            closeIconPressed = RgbaColor.WHITE,
            outlineCloseButton = outlineCloseButton,
            isDark = isDark
        )

        val effectiveHeight = maxOf(28, buttonSizePx + 10)
        return LinuxTitleBarProfile(
            wmType = LinuxWmType.KWIN_BREEZE,
            themeFamily = LinuxTitleBarThemeFamily.BREEZE,
            themeName = themeName.ifBlank { if (isDark) "BreezeDark" else "Breeze" },
            isReplicable = true,
            fallbackReason = null,
            leftButtons = leftButtons,
            rightButtons = rightButtons,
            palette = palette,
            metrics = LinuxTitleBarMetrics(
                heightPx = effectiveHeight,
                compactHeightPx = (effectiveHeight - 4).coerceAtLeast(24),
                topCornerRadiusPx = 6f,
                horizontalPaddingPx = 6,
                buttonWidthPx = buttonSizePx,
                buttonHeightPx = buttonSizePx,
                buttonSpacingPx = 4,
                buttonCircular = true,
                centerTitle = true,
                fontSizePx = 12.5f,
                fontBold = false,
                drawBottomSeparator = true,
                drawMaximizedBorder = drawBorderOnMaximized
            )
        )
    }

    fun resolveMarcoProfile(themeName: String, buttonLayout: String?): LinuxTitleBarProfile? {
        val trimmed = themeName.trim()
        val lower = trimmed.lowercase()
        val isDark: Boolean
        val activeTop: RgbaColor
        val activeBottom: RgbaColor
        val inactiveBg: RgbaColor
        val activeText: RgbaColor
        val inactiveText: RgbaColor
        val accent: RgbaColor
        val circularButtons: Boolean

        when {
            lower == "menta" -> {
                isDark = false
                activeTop = RgbaColor(235, 235, 235)
                activeBottom = RgbaColor(220, 220, 220)
                inactiveBg = RgbaColor(240, 240, 240)
                activeText = RgbaColor(48, 48, 48)
                inactiveText = RgbaColor(138, 138, 138)
                accent = RgbaColor(106, 155, 67)
                circularButtons = false
            }
            lower == "blackmate" -> {
                isDark = true
                activeTop = RgbaColor(54, 54, 54)
                activeBottom = RgbaColor(38, 38, 38)
                inactiveBg = RgbaColor(46, 46, 46)
                activeText = RgbaColor(235, 235, 235)
                inactiveText = RgbaColor(138, 138, 138)
                accent = RgbaColor(106, 155, 67)
                circularButtons = false
            }
            lower == "traditionalok" -> {
                isDark = false
                activeTop = RgbaColor(96, 144, 200)
                activeBottom = RgbaColor(72, 118, 174)
                inactiveBg = RgbaColor(220, 217, 212)
                activeText = RgbaColor.WHITE
                inactiveText = RgbaColor(110, 110, 110)
                accent = RgbaColor(74, 144, 217)
                circularButtons = false
            }
            lower == "traditionalgreen" -> {
                isDark = false
                activeTop = RgbaColor(108, 160, 82)
                activeBottom = RgbaColor(86, 136, 62)
                inactiveBg = RgbaColor(220, 217, 212)
                activeText = RgbaColor.WHITE
                inactiveText = RgbaColor(110, 110, 110)
                accent = RgbaColor(98, 154, 72)
                circularButtons = false
            }
            lower.startsWith("yaru-mate") -> {
                isDark = lower.contains("dark")
                activeTop = if (isDark) RgbaColor(46, 46, 46) else RgbaColor(238, 238, 238)
                activeBottom = if (isDark) RgbaColor(38, 38, 38) else RgbaColor(226, 226, 226)
                inactiveBg = if (isDark) RgbaColor(36, 36, 36) else RgbaColor(244, 244, 244)
                activeText = if (isDark) RgbaColor(244, 244, 244) else RgbaColor(60, 60, 60)
                inactiveText = RgbaColor(142, 142, 142)
                accent = RgbaColor(135, 165, 86) // MATE Yaru green
                circularButtons = true
            }
            else -> return null
        }

        val (left, right) = parseColonButtonLayout(buttonLayout ?: "menu:minimize,maximize,close")
        val palette = LinuxTitleBarPalette(
            activeBgTop = activeTop,
            activeBgBottom = activeBottom,
            inactiveBgTop = inactiveBg,
            inactiveBgBottom = inactiveBg,
            activeBorderBottom = if (isDark) RgbaColor(24, 24, 24) else RgbaColor(186, 186, 186),
            inactiveBorderBottom = if (isDark) RgbaColor(32, 32, 32) else RgbaColor(208, 208, 208),
            topHighlight = if (isDark) RgbaColor(255, 255, 255, 20) else RgbaColor(255, 255, 255, 130),
            activeTitleText = activeText,
            inactiveTitleText = inactiveText,
            subtitleText = inactiveText,
            accentColor = accent,
            buttonBgNormal = RgbaColor.TRANSPARENT,
            buttonBgHover = if (isDark) RgbaColor(255, 255, 255, 32) else RgbaColor(0, 0, 0, 28),
            buttonBgPressed = if (isDark) RgbaColor(255, 255, 255, 56) else RgbaColor(0, 0, 0, 48),
            buttonIconActive = activeText,
            buttonIconInactive = inactiveText,
            closeBgNormal = if (circularButtons) RgbaColor(accent.r, accent.g, accent.b, 200) else RgbaColor.TRANSPARENT,
            closeBgHover = accent,
            closeBgPressed = accent.adjustBrightness(-24),
            closeIconNormal = if (circularButtons) RgbaColor.WHITE else activeText,
            closeIconHover = RgbaColor.WHITE,
            closeIconPressed = RgbaColor.WHITE,
            outlineCloseButton = false,
            isDark = isDark
        )

        return LinuxTitleBarProfile(
            wmType = LinuxWmType.MARCO,
            themeFamily = LinuxTitleBarThemeFamily.MARCO_THEME,
            themeName = trimmed,
            isReplicable = true,
            fallbackReason = null,
            leftButtons = left,
            rightButtons = right,
            palette = palette,
            metrics = LinuxTitleBarMetrics(
                heightPx = 32,
                compactHeightPx = 28,
                topCornerRadiusPx = 6f,
                horizontalPaddingPx = 6,
                buttonWidthPx = 22,
                buttonHeightPx = 22,
                buttonSpacingPx = 4,
                buttonCircular = circularButtons,
                centerTitle = true,
                fontSizePx = 12.5f,
                fontBold = true,
                drawBottomSeparator = true,
                drawMaximizedBorder = false
            )
        )
    }

    fun resolveXfwm4Profile(
        themeName: String,
        assets: LinuxXfwm4ThemeAssets,
        buttonLayout: String?
    ): LinuxTitleBarProfile {
        val props = assets.properties
        val activeColor1 = LinuxIniAndXmlConfigParser.parseColor(props["active_color_1"])
            ?: sampleSpriteAverageColor(assets.titleActiveSlices.firstOrNull() ?: assets.topLeftActive)
            ?: RgbaColor(224, 224, 224)
        val activeColor2 = LinuxIniAndXmlConfigParser.parseColor(props["active_color_2"])
            ?: activeColor1.adjustBrightness(-12)
        val inactiveColor1 = LinuxIniAndXmlConfigParser.parseColor(props["inactive_color_1"])
            ?: sampleSpriteAverageColor(assets.titleInactiveSlices.firstOrNull() ?: assets.topLeftInactive)
            ?: RgbaColor(238, 238, 238)
        val inactiveColor2 = LinuxIniAndXmlConfigParser.parseColor(props["inactive_color_2"])
            ?: inactiveColor1
        val isDark = ((activeColor1.r * 299 + activeColor1.g * 587 + activeColor1.b * 114) / 1000) < 128

        val activeText = LinuxIniAndXmlConfigParser.parseColor(props["active_text_color"])
            ?: if (isDark) RgbaColor(242, 242, 242) else RgbaColor(42, 42, 42)
        val inactiveText = LinuxIniAndXmlConfigParser.parseColor(props["inactive_text_color"])
            ?: if (isDark) RgbaColor(150, 150, 150) else RgbaColor(135, 135, 135)
        val borderColor = LinuxIniAndXmlConfigParser.parseColor(props["active_border_color"])
            ?: activeColor1.adjustBrightness(-30)

        val closeSprite = assets.buttonActive[LinuxTitleButtonKind.CLOSE]
        val barHeight = (assets.topLeftActive?.height
            ?: assets.titleActiveSlices.firstOrNull()?.height
            ?: closeSprite?.height
            ?: 28).coerceIn(20, 48)
        val btnW = (closeSprite?.width ?: (barHeight - 6)).coerceIn(14, 36)
        val btnH = (closeSprite?.height ?: (barHeight - 6)).coerceIn(14, 36)
        val spacing = props["button_spacing"]?.toIntOrNull()?.coerceIn(0, 16) ?: 2
        val padX = props["button_offset"]?.toIntOrNull()?.coerceIn(2, 16) ?: 4
        val centerTitle = props["title_alignment"]?.lowercase() != "left"

        val layoutStr = buttonLayout?.takeIf { it.isNotBlank() }
            ?: props["button_layout"]?.takeIf { it.isNotBlank() }
            ?: "O|HMC"
        val (left, right) = parseXfwm4ButtonLayout(layoutStr)

        val palette = LinuxTitleBarPalette(
            activeBgTop = activeColor1,
            activeBgBottom = activeColor2,
            inactiveBgTop = inactiveColor1,
            inactiveBgBottom = inactiveColor2,
            activeBorderBottom = borderColor,
            inactiveBorderBottom = inactiveColor1.adjustBrightness(-18),
            topHighlight = RgbaColor(255, 255, 255, if (isDark) 20 else 90),
            activeTitleText = activeText,
            inactiveTitleText = inactiveText,
            subtitleText = inactiveText,
            accentColor = RgbaColor(57, 142, 231),
            buttonBgNormal = RgbaColor.TRANSPARENT,
            buttonBgHover = if (isDark) RgbaColor(255, 255, 255, 32) else RgbaColor(0, 0, 0, 24),
            buttonBgPressed = if (isDark) RgbaColor(255, 255, 255, 54) else RgbaColor(0, 0, 0, 44),
            buttonIconActive = activeText,
            buttonIconInactive = inactiveText,
            closeBgNormal = RgbaColor.TRANSPARENT,
            closeBgHover = RgbaColor(218, 68, 83),
            closeBgPressed = RgbaColor(180, 45, 58),
            closeIconNormal = activeText,
            closeIconHover = RgbaColor.WHITE,
            closeIconPressed = RgbaColor.WHITE,
            outlineCloseButton = false,
            isDark = isDark
        )

        return LinuxTitleBarProfile(
            wmType = LinuxWmType.XFWM4,
            themeFamily = LinuxTitleBarThemeFamily.XFWM4_THEME,
            themeName = themeName,
            isReplicable = true,
            fallbackReason = null,
            leftButtons = left,
            rightButtons = right,
            palette = palette,
            metrics = LinuxTitleBarMetrics(
                heightPx = barHeight,
                compactHeightPx = (barHeight - 4).coerceAtLeast(18),
                topCornerRadiusPx = 0f,
                horizontalPaddingPx = padX,
                buttonWidthPx = btnW,
                buttonHeightPx = btnH,
                buttonSpacingPx = spacing,
                buttonCircular = false,
                centerTitle = centerTitle,
                fontSizePx = 12f,
                fontBold = true,
                drawBottomSeparator = false,
                drawMaximizedBorder = false
            ),
            xfwm4Assets = assets
        )
    }

    fun buildUnsupportedProfile(
        wmType: LinuxWmType,
        themeName: String,
        reason: String,
        isDark: Boolean = false
    ): LinuxTitleBarProfile {
        val palette = if (isDark) {
            LinuxTitleBarPalette(
                activeBgTop = RgbaColor(45, 45, 45),
                activeBgBottom = RgbaColor(45, 45, 45),
                inactiveBgTop = RgbaColor(36, 36, 36),
                inactiveBgBottom = RgbaColor(36, 36, 36),
                activeBorderBottom = RgbaColor(24, 24, 24),
                inactiveBorderBottom = RgbaColor(28, 28, 28),
                topHighlight = RgbaColor.TRANSPARENT,
                activeTitleText = RgbaColor(240, 240, 240),
                inactiveTitleText = RgbaColor(150, 150, 150),
                subtitleText = RgbaColor(175, 175, 175),
                accentColor = RgbaColor(53, 132, 228),
                buttonBgNormal = RgbaColor.TRANSPARENT,
                buttonBgHover = RgbaColor(255, 255, 255, 28),
                buttonBgPressed = RgbaColor(255, 255, 255, 48),
                buttonIconActive = RgbaColor(240, 240, 240),
                buttonIconInactive = RgbaColor(150, 150, 150),
                closeBgNormal = RgbaColor.TRANSPARENT,
                closeBgHover = RgbaColor(218, 68, 83),
                closeBgPressed = RgbaColor(180, 45, 58),
                closeIconNormal = RgbaColor(240, 240, 240),
                closeIconHover = RgbaColor.WHITE,
                closeIconPressed = RgbaColor.WHITE,
                outlineCloseButton = false,
                isDark = true
            )
        } else {
            LinuxTitleBarPalette(
                activeBgTop = RgbaColor(242, 242, 242),
                activeBgBottom = RgbaColor(242, 242, 242),
                inactiveBgTop = RgbaColor(248, 248, 248),
                inactiveBgBottom = RgbaColor(248, 248, 248),
                activeBorderBottom = RgbaColor(210, 210, 210),
                inactiveBorderBottom = RgbaColor(225, 225, 225),
                topHighlight = RgbaColor.TRANSPARENT,
                activeTitleText = RgbaColor(46, 52, 54),
                inactiveTitleText = RgbaColor(145, 145, 145),
                subtitleText = RgbaColor(110, 110, 110),
                accentColor = RgbaColor(53, 132, 228),
                buttonBgNormal = RgbaColor.TRANSPARENT,
                buttonBgHover = RgbaColor(0, 0, 0, 24),
                buttonBgPressed = RgbaColor(0, 0, 0, 42),
                buttonIconActive = RgbaColor(46, 52, 54),
                buttonIconInactive = RgbaColor(145, 145, 145),
                closeBgNormal = RgbaColor.TRANSPARENT,
                closeBgHover = RgbaColor(218, 68, 83),
                closeBgPressed = RgbaColor(180, 45, 58),
                closeIconNormal = RgbaColor(46, 52, 54),
                closeIconHover = RgbaColor.WHITE,
                closeIconPressed = RgbaColor.WHITE,
                outlineCloseButton = false,
                isDark = false
            )
        }

        return LinuxTitleBarProfile(
            wmType = wmType,
            themeFamily = LinuxTitleBarThemeFamily.NONE,
            themeName = themeName,
            isReplicable = false,
            fallbackReason = reason,
            leftButtons = emptyList(),
            rightButtons = listOf(
                LinuxTitleButtonKind.MINIMIZE,
                LinuxTitleButtonKind.MAXIMIZE_OR_RESTORE,
                LinuxTitleButtonKind.CLOSE
            ),
            palette = palette,
            metrics = LinuxTitleBarMetrics(
                heightPx = 36,
                compactHeightPx = 30,
                topCornerRadiusPx = 0f,
                horizontalPaddingPx = 8,
                buttonWidthPx = 24,
                buttonHeightPx = 24,
                buttonSpacingPx = 6,
                buttonCircular = false,
                centerTitle = true,
                fontSizePx = 13f,
                fontBold = true,
                drawBottomSeparator = true,
                drawMaximizedBorder = false
            )
        )
    }

    /**
     * Parses colon-separated button layouts (`":minimize,maximize,close"`, `"close,minimize,maximize:"`,
     * `"menu:minimize,maximize,close"`).
     */
    fun parseColonButtonLayout(layout: String): Pair<List<LinuxTitleButtonKind>, List<LinuxTitleButtonKind>> {
        val colonIdx = layout.indexOf(':')
        val leftStr: String
        val rightStr: String
        if (colonIdx < 0) {
            leftStr = layout
            rightStr = ""
        } else {
            leftStr = layout.substring(0, colonIdx)
            rightStr = layout.substring(colonIdx + 1)
        }
        return parseCommaButtonTokens(leftStr) to parseCommaButtonTokens(rightStr)
    }

    private fun parseCommaButtonTokens(spec: String): List<LinuxTitleButtonKind> {
        val out = ArrayList<LinuxTitleButtonKind>()
        for (raw in spec.split(',')) {
            when (raw.trim().lowercase()) {
                "menu" -> out.add(LinuxTitleButtonKind.MENU)
                "minimize" -> out.add(LinuxTitleButtonKind.MINIMIZE)
                "maximize" -> out.add(LinuxTitleButtonKind.MAXIMIZE_OR_RESTORE)
                "close" -> out.add(LinuxTitleButtonKind.CLOSE)
                "above" -> out.add(LinuxTitleButtonKind.KEEP_ABOVE)
                "below" -> out.add(LinuxTitleButtonKind.KEEP_BELOW)
                "stick" -> out.add(LinuxTitleButtonKind.ON_ALL_DESKTOPS)
                "shade" -> out.add(LinuxTitleButtonKind.SHADE)
                "spacer" -> out.add(LinuxTitleButtonKind.SPACER)
            }
        }
        return out
    }

    /**
     * Parses KWin `s_buttonNames` strings (`M`, `N`, `S`, `H`, `I`, `A`, `X`, `F`, `B`, `_`, `L`).
     */
    fun parseKwinButtonChars(spec: String): List<LinuxTitleButtonKind> {
        val out = ArrayList<LinuxTitleButtonKind>(spec.length)
        for (ch in spec) {
            when (ch) {
                'M', 'N' -> out.add(LinuxTitleButtonKind.MENU)
                'S' -> out.add(LinuxTitleButtonKind.ON_ALL_DESKTOPS)
                'H' -> out.add(LinuxTitleButtonKind.CONTEXT_HELP)
                'I' -> out.add(LinuxTitleButtonKind.MINIMIZE)
                'A' -> out.add(LinuxTitleButtonKind.MAXIMIZE_OR_RESTORE)
                'X' -> out.add(LinuxTitleButtonKind.CLOSE)
                'F' -> out.add(LinuxTitleButtonKind.KEEP_ABOVE)
                'B' -> out.add(LinuxTitleButtonKind.KEEP_BELOW)
                'L' -> out.add(LinuxTitleButtonKind.SHADE)
                '_' -> out.add(LinuxTitleButtonKind.SPACER)
            }
        }
        return out
    }

    /**
     * Parses Xfwm4 `button_layout` (`O/T/H/S/M/C/|`).
     */
    fun parseXfwm4ButtonLayout(layout: String): Pair<List<LinuxTitleButtonKind>, List<LinuxTitleButtonKind>> {
        val pipeIdx = layout.indexOf('|')
        val leftChars: String
        val rightChars: String
        if (pipeIdx < 0) {
            leftChars = ""
            rightChars = layout
        } else {
            leftChars = layout.substring(0, pipeIdx)
            rightChars = layout.substring(pipeIdx + 1)
        }
        return parseXfwm4ButtonChars(leftChars) to parseXfwm4ButtonChars(rightChars)
    }

    private fun parseXfwm4ButtonChars(chars: String): List<LinuxTitleButtonKind> {
        val out = ArrayList<LinuxTitleButtonKind>(chars.length)
        for (ch in chars) {
            when (ch.uppercaseChar()) {
                'O' -> out.add(LinuxTitleButtonKind.MENU)
                'T' -> out.add(LinuxTitleButtonKind.ON_ALL_DESKTOPS)
                'H' -> out.add(LinuxTitleButtonKind.MINIMIZE)
                'S' -> out.add(LinuxTitleButtonKind.SHADE)
                'M' -> out.add(LinuxTitleButtonKind.MAXIMIZE_OR_RESTORE)
                'C' -> out.add(LinuxTitleButtonKind.CLOSE)
                '_' -> out.add(LinuxTitleButtonKind.SPACER)
            }
        }
        return out
    }

    private fun parseBreezeButtonSize(raw: String?): Int {
        val clean = raw?.trim()?.lowercase() ?: return 20
        return when (clean) {
            "buttontiny", "0" -> 16
            "buttonsmall", "1" -> 18
            "buttondefault", "2" -> 20
            "buttonlarge", "3" -> 24
            "buttonverylarge", "4" -> 28
            else -> 20
        }
    }

    private fun sampleSpriteAverageColor(sprite: LinuxXfwm4SpriteSet?): RgbaColor? {
        if (sprite == null || sprite.isEmpty) return null
        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var count = 0L
        for (px in sprite.pixels) {
            val a = (px ushr 24) and 0xFF
            if (a < 128) continue
            rSum += (px ushr 16) and 0xFF
            gSum += (px ushr 8) and 0xFF
            bSum += px and 0xFF
            count++
        }
        if (count == 0L) return null
        return RgbaColor(
            r = (rSum / count).toInt().coerceIn(0, 255),
            g = (gSum / count).toInt().coerceIn(0, 255),
            b = (bSum / count).toInt().coerceIn(0, 255),
            a = 255
        )
    }
}
