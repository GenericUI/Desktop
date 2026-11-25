@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.linux.titlebar

import com.nexustools.genericui.component.action.Button
import com.nexustools.genericui.component.shell.MdiContainer
import com.nexustools.genericui.component.shell.MdiWindow
import com.nexustools.genericui.component.shell.MdiWindowState
import com.nexustools.genericui.enums.window.TitleBarControlsPlacement
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.image.ImageColorSpace
import com.nexustools.genericui.image.PixelFormat
import com.nexustools.genericui.image.codec.LibPngCodec
import com.nexustools.genericui.linux.LinuxGlibGioBindings
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.window.TitleBar
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxTitleBarDetectorAndRendererTest {

    @AfterTest
    fun tearDown() {
        LinuxTitleBarProfileManager.setTestProfileOverride(null)
    }

    @Test
    fun detectsAllElevenYaruVariantsLightDarkAndButtonLayouts() {
        val variants = listOf(
            "Yaru",
            "Yaru-purple",
            "Yaru-bark",
            "Yaru-sage",
            "Yaru-olive",
            "Yaru-viridian",
            "Yaru-prussianblue",
            "Yaru-blue",
            "Yaru-red",
            "Yaru-magenta",
            "Yaru-yellow"
        )
        val seenAccents = HashSet<RgbaColor>()
        for (variant in variants) {
            val lightProfile = LinuxWmDetector.detectFromSnapshot(
                LinuxWmEnvironmentSnapshot(
                    xdgCurrentDesktop = "ubuntu:GNOME",
                    ewmhWmName = "GNOME Shell",
                    gnomeGtkTheme = variant,
                    gnomeWmButtonLayout = ":minimize,maximize,close"
                )
            )
            assertEquals(LinuxWmType.MUTTER_YARU, lightProfile.wmType)
            assertEquals(LinuxTitleBarThemeFamily.YARU, lightProfile.themeFamily)
            assertTrue(lightProfile.isReplicable)
            assertNull(lightProfile.fallbackReason)
            assertFalse(lightProfile.palette.isDark)
            assertTrue(lightProfile.metrics.buttonCircular)
            assertEquals(emptyList(), lightProfile.leftButtons)
            assertEquals(
                listOf(
                    LinuxTitleButtonKind.MINIMIZE,
                    LinuxTitleButtonKind.MAXIMIZE_OR_RESTORE,
                    LinuxTitleButtonKind.CLOSE
                ),
                lightProfile.rightButtons
            )
            seenAccents.add(lightProfile.palette.accentColor)

            val darkProfile = LinuxWmDetector.detectFromSnapshot(
                LinuxWmEnvironmentSnapshot(
                    xdgCurrentDesktop = "ubuntu:GNOME",
                    ewmhWmName = "GNOME Shell",
                    gnomeGtkTheme = "$variant-dark",
                    gnomeWmButtonLayout = "close,minimize,maximize:"
                )
            )
            assertEquals(LinuxWmType.MUTTER_YARU, darkProfile.wmType)
            assertTrue(darkProfile.isReplicable)
            assertTrue(darkProfile.palette.isDark)
            assertEquals(lightProfile.palette.accentColor, darkProfile.palette.accentColor)
            assertEquals(
                listOf(
                    LinuxTitleButtonKind.CLOSE,
                    LinuxTitleButtonKind.MINIMIZE,
                    LinuxTitleButtonKind.MAXIMIZE_OR_RESTORE
                ),
                darkProfile.leftButtons
            )
            assertEquals(emptyList(), darkProfile.rightButtons)
        }
        assertEquals(11, seenAccents.size, "Expected 11 distinct Yaru accent colors")
    }

    @Test
    fun detectsLibadwaitaNineGnomeAccentsAndPortalFallbacks() {
        val gnomeAccents = listOf("blue", "teal", "green", "yellow", "orange", "red", "pink", "purple", "slate")
        val seenAccents = HashSet<RgbaColor>()
        for (accent in gnomeAccents) {
            val profile = LinuxWmDetector.detectFromSnapshot(
                LinuxWmEnvironmentSnapshot(
                    xdgCurrentDesktop = "GNOME",
                    ewmhWmName = "GNOME Shell",
                    gnomeGtkTheme = "Adwaita",
                    gnomeColorScheme = "prefer-dark",
                    gnomeAccentColor = accent,
                    gnomeWmButtonLayout = ":close"
                )
            )
            assertEquals(LinuxWmType.MUTTER_LIBADWAITA, profile.wmType)
            assertEquals(LinuxTitleBarThemeFamily.LIBADWAITA, profile.themeFamily)
            assertTrue(profile.isReplicable)
            assertTrue(profile.palette.isDark)
            assertEquals(listOf(LinuxTitleButtonKind.CLOSE), profile.rightButtons)
            seenAccents.add(profile.palette.accentColor)
        }
        assertEquals(9, seenAccents.size)

        val customPortalAccent = RgbaColor(42, 180, 200)
        val portalProfile = LinuxWmDetector.detectFromSnapshot(
            LinuxWmEnvironmentSnapshot(
                xdgCurrentDesktop = "GNOME",
                ewmhWmName = "Mutter",
                portalColorScheme = 1,
                portalAccentColor = customPortalAccent
            )
        )
        assertEquals(LinuxWmType.MUTTER_LIBADWAITA, portalProfile.wmType)
        assertTrue(portalProfile.isReplicable)
        assertTrue(portalProfile.palette.isDark)
        assertEquals(customPortalAccent, portalProfile.palette.accentColor)
    }

    @Test
    fun detectsKwinBreezeParsesAllButtonNamesBreezeRcAndKdeGlobals() {
        val kwinRc = """
            [org.kde.kdecoration2]
            library=org.kde.breeze
            theme=BreezeDark
            ButtonsOnLeft=MNSH_
            ButtonsOnRight=FBIAX
        """.trimIndent()
        val breezeRc = """
            [Windeco]
            ButtonSize=ButtonLarge
            DrawBackgroundGradient=true
            DrawBorderOnMaximizedWindows=true
            OutlineCloseButton=true
        """.trimIndent()
        val kdeGlobals = """
            [General]
            AccentColor=120,200,80
            ColorScheme=BreezeDark

            [Colors:Header]
            BackgroundNormal=44,48,54
            ForegroundNormal=245,246,247
            ForegroundInactive=140,145,150

            [Colors:Header][Inactive]
            BackgroundNormal=36,40,44
            ForegroundNormal=135,140,145
        """.trimIndent()

        val profile = LinuxWmDetector.detectFromSnapshot(
            LinuxWmEnvironmentSnapshot(
                xdgCurrentDesktop = "KDE",
                ewmhWmName = "KWin",
                kwinRcContent = kwinRc,
                breezeRcContent = breezeRc,
                kdeGlobalsContent = kdeGlobals
            )
        )

        assertEquals(LinuxWmType.KWIN_BREEZE, profile.wmType)
        assertEquals(LinuxTitleBarThemeFamily.BREEZE, profile.themeFamily)
        assertTrue(profile.isReplicable)
        assertNull(profile.fallbackReason)
        assertTrue(profile.palette.isDark)
        assertTrue(profile.palette.outlineCloseButton)
        assertTrue(profile.metrics.drawMaximizedBorder)
        assertEquals(24, profile.metrics.buttonWidthPx)
        assertEquals(RgbaColor(120, 200, 80), profile.palette.accentColor)
        assertEquals(RgbaColor(245, 246, 247), profile.palette.activeTitleText)
        assertEquals(RgbaColor(135, 140, 145), profile.palette.inactiveTitleText)
        assertNotEquals(profile.palette.activeBgTop, profile.palette.activeBgBottom)

        assertEquals(
            listOf(
                LinuxTitleButtonKind.MENU,
                LinuxTitleButtonKind.MENU,
                LinuxTitleButtonKind.ON_ALL_DESKTOPS,
                LinuxTitleButtonKind.CONTEXT_HELP,
                LinuxTitleButtonKind.SPACER
            ),
            profile.leftButtons
        )
        assertEquals(
            listOf(
                LinuxTitleButtonKind.KEEP_ABOVE,
                LinuxTitleButtonKind.KEEP_BELOW,
                LinuxTitleButtonKind.MINIMIZE,
                LinuxTitleButtonKind.MAXIMIZE_OR_RESTORE,
                LinuxTitleButtonKind.CLOSE
            ),
            profile.rightButtons
        )

        // Default KWin button layout when ButtonsOnLeft/ButtonsOnRight keys are absent, plus explicit empty left override
        val defaultButtons = LinuxWmDetector.detectFromSnapshot(
            LinuxWmEnvironmentSnapshot(xdgCurrentDesktop = "KDE", ewmhWmName = "KWin")
        )
        assertEquals(listOf(LinuxTitleButtonKind.MENU), defaultButtons.leftButtons)
        assertEquals(
            listOf(LinuxTitleButtonKind.MINIMIZE, LinuxTitleButtonKind.MAXIMIZE_OR_RESTORE, LinuxTitleButtonKind.CLOSE),
            defaultButtons.rightButtons
        )

        val emptyLeft = LinuxWmDetector.detectFromSnapshot(
            LinuxWmEnvironmentSnapshot(
                xdgCurrentDesktop = "KDE",
                ewmhWmName = "KWin",
                kwinRcContent = "[org.kde.kdecoration2]\nButtonsOnLeft=\nButtonsOnRight=IX\n"
            )
        )
        assertEquals(emptyList(), emptyLeft.leftButtons)
        assertEquals(listOf(LinuxTitleButtonKind.MINIMIZE, LinuxTitleButtonKind.CLOSE), emptyLeft.rightButtons)
    }

    @Test
    fun detectsMuffinMintYTenAccentsAndRejectsCustomMetacity() {
        val accents = listOf("Aqua", "Blue", "Brown", "Grey", "Orange", "Pink", "Purple", "Red", "Sand", "Teal")
        val seenAccents = HashSet<RgbaColor>()
        for (accent in accents) {
            val light = LinuxWmDetector.detectFromSnapshot(
                LinuxWmEnvironmentSnapshot(
                    xdgCurrentDesktop = "X-Cinnamon",
                    ewmhWmName = "Mutter (Muffin)",
                    cinnamonWmTheme = "Mint-Y-$accent",
                    cinnamonWmButtonLayout = ":minimize,maximize,close"
                )
            )
            assertEquals(LinuxWmType.MUFFIN_MINT_Y, light.wmType)
            assertEquals(LinuxTitleBarThemeFamily.MINT_Y, light.themeFamily)
            assertTrue(light.isReplicable)
            assertFalse(light.palette.isDark)
            seenAccents.add(light.palette.accentColor)

            val dark = LinuxWmDetector.detectFromSnapshot(
                LinuxWmEnvironmentSnapshot(
                    xdgCurrentDesktop = "X-Cinnamon",
                    ewmhWmName = "Mutter (Muffin)",
                    cinnamonWmTheme = "Mint-Y-Dark-$accent"
                )
            )
            assertTrue(dark.isReplicable)
            assertTrue(dark.palette.isDark)
            assertEquals(light.palette.accentColor, dark.palette.accentColor)
        }
        assertEquals(10, seenAccents.size)

        val rejected = LinuxWmDetector.detectFromSnapshot(
            LinuxWmEnvironmentSnapshot(
                xdgCurrentDesktop = "X-Cinnamon",
                ewmhWmName = "Mutter (Muffin)",
                cinnamonWmTheme = "Mint-X"
            )
        )
        assertFalse(rejected.isReplicable)
        assertEquals(LinuxTitleBarThemeFamily.NONE, rejected.themeFamily)
        assertNotNull(rejected.fallbackReason)
    }

    @Test
    fun detectsXfwm4DecodesXpmAndPngSpritesAndRejectsMissingAssets() {
        val sampleXpm = """
            /* XPM */
            static char * close_active_xpm[] = {
            "4 4 3 1",
            " 	c None",
            ".	c #FF0000 s active_color_1",
            "+	c #FFFFFFFFFFFF",
            " .++",
            ".++.",
            ".++.",
            "++ ."
            };
        """.trimIndent()
        val tileXpm = """
            /* XPM */
            static char * title_3_active_xpm[] = {
            "2 2 2 2",
            "aa c #224466",
            "bb c #335577",
            "aabb",
            "bbaa"
            };
        """.trimIndent()

        val pngBuffer = ImageBuffer(4, 4, PixelFormat.RGBA_8888, ImageColorSpace.SRGB)
        for (y in 0 until 4) {
            for (x in 0 until 4) {
                pngBuffer.setPixel(x, y, RgbaColor(10 + x * 20, 40 + y * 20, 220, 255))
            }
        }
        val pngBytes = LibPngCodec.encode(pngBuffer)

        val themeFiles = linkedMapOf(
            "themerc" to """
                active_color_1=#306090
                active_text_color=#FFFFFF
                inactive_text_color=#AAAAAA
                title_alignment=center
                button_spacing=3
            """.trimIndent().encodeToByteArray(),
            "top-left-active.png" to pngBytes,
            "top-right-active.png" to pngBytes,
            "title-3-active.xpm" to tileXpm.encodeToByteArray(),
            "close-active.xpm" to sampleXpm.encodeToByteArray(),
            "close-prelight.png" to pngBytes,
            "hide-active.xpm" to sampleXpm.encodeToByteArray(),
            "maximize-active.xpm" to sampleXpm.encodeToByteArray()
        )

        val xfwm4Xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <channel name="xfwm4" version="1.0">
              <property name="general" type="empty">
                <property name="theme" type="string" value="TestXfwmTheme"/>
                <property name="button_layout" type="string" value="OT|HSMC"/>
              </property>
            </channel>
        """.trimIndent()

        val profile = LinuxWmDetector.detectFromSnapshot(
            LinuxWmEnvironmentSnapshot(
                xdgCurrentDesktop = "XFCE",
                ewmhWmName = "Xfwm4",
                xfwm4XmlContent = xfwm4Xml,
                xfwm4ThemeFiles = themeFiles
            )
        )
        assertEquals(LinuxWmType.XFWM4, profile.wmType)
        assertEquals(LinuxTitleBarThemeFamily.XFWM4_THEME, profile.themeFamily)
        assertTrue(profile.isReplicable)
        assertNotNull(profile.xfwm4Assets)
        assertEquals(listOf(LinuxTitleButtonKind.MENU, LinuxTitleButtonKind.ON_ALL_DESKTOPS), profile.leftButtons)
        assertEquals(
            listOf(
                LinuxTitleButtonKind.MINIMIZE,
                LinuxTitleButtonKind.SHADE,
                LinuxTitleButtonKind.MAXIMIZE_OR_RESTORE,
                LinuxTitleButtonKind.CLOSE
            ),
            profile.rightButtons
        )

        val assets = profile.xfwm4Assets!!
        val closeActive = assets.buttonActive[LinuxTitleButtonKind.CLOSE]
        assertNotNull(closeActive)
        assertEquals(4, closeActive.width)
        assertEquals(4, closeActive.height)
        assertEquals(0x00000000, closeActive.pixels[0]) // ' ' -> None
        // '.' had symbolic s active_color_1 = #306090
        assertEquals(RgbaColor(0x30, 0x60, 0x90).toArgbInt(), closeActive.pixels[1])
        // '+' had 12-digit hex #FFFFFFFFFFFF
        assertEquals(RgbaColor(255, 255, 255).toArgbInt(), closeActive.pixels[2])

        val closePrelight = assets.buttonPrelight[LinuxTitleButtonKind.CLOSE]
        assertNotNull(closePrelight)
        assertEquals(4, closePrelight.width)
        assertEquals(RgbaColor(10, 40, 220, 255).toArgbInt(), closePrelight.pixels[0])

        // 2-cpp XPM check
        assertEquals(1, assets.titleActiveSlices.size)
        assertEquals(RgbaColor(0x22, 0x44, 0x66).toArgbInt(), assets.titleActiveSlices[0].pixels[0])
        assertEquals(RgbaColor(0x33, 0x55, 0x77).toArgbInt(), assets.titleActiveSlices[0].pixels[1])

        // Missing Xfwm4 assets must fail cleanly with isReplicable = false
        val missingAssetsProfile = LinuxWmDetector.detectFromSnapshot(
            LinuxWmEnvironmentSnapshot(
                xdgCurrentDesktop = "XFCE",
                ewmhWmName = "Xfwm4",
                xfwm4XmlContent = xfwm4Xml,
                xfwm4ThemeFiles = emptyMap()
            )
        )
        assertFalse(missingAssetsProfile.isReplicable)
        assertEquals(LinuxTitleBarThemeFamily.NONE, missingAssetsProfile.themeFamily)
        assertNotNull(missingAssetsProfile.fallbackReason)

        // Malformed XPM throws cleanly without silent failure
        assertFailsWith<IllegalArgumentException> {
            LinuxXpmPixmapDecoder.decode("not valid xpm")
        }
    }

    @Test
    fun detectsMarcoStockThemesAndRejectsUnknownMetacityThemes() {
        val stockThemes = listOf("Menta", "BlackMATE", "TraditionalOk", "TraditionalGreen", "Yaru-MATE", "Yaru-MATE-dark")
        for (theme in stockThemes) {
            val profile = LinuxWmDetector.detectFromSnapshot(
                LinuxWmEnvironmentSnapshot(
                    xdgCurrentDesktop = "MATE",
                    ewmhWmName = "Metacity (Marco)",
                    marcoTheme = theme,
                    marcoButtonLayout = "menu:minimize,maximize,close"
                )
            )
            assertEquals(LinuxWmType.MARCO, profile.wmType)
            assertEquals(LinuxTitleBarThemeFamily.MARCO_THEME, profile.themeFamily)
            assertTrue(profile.isReplicable, "Expected stock Marco theme $theme to be replicable")
        }

        val unknownMarco = LinuxWmDetector.detectFromSnapshot(
            LinuxWmEnvironmentSnapshot(
                xdgCurrentDesktop = "MATE",
                ewmhWmName = "Marco",
                marcoTheme = "CustomThirdPartyMetacity"
            )
        )
        assertFalse(unknownMarco.isReplicable)
        assertEquals(LinuxTitleBarThemeFamily.NONE, unknownMarco.themeFamily)
        assertNotNull(unknownMarco.fallbackReason)
    }

    @Test
    fun rejectsTilingMinimalWmsUnsupportedCompositorsPluginsThemesAndGtkCssOverrides() {
        val tilingWms = listOf(
            "Sway", "Hyprland", "i3", "bspwm", "dwm", "Awesome",
            "Qtile", "XMonad", "Niri", "River", "dwl", "Herbstluftwm", "Spectrwm"
        )
        for (wm in tilingWms) {
            val res = LinuxWmDetector.detectFromSnapshot(
                LinuxWmEnvironmentSnapshot(xdgCurrentDesktop = wm, ewmhWmName = wm)
            )
            assertFalse(res.isReplicable, "Expected tiling WM '$wm' to be rejected")
            assertEquals(LinuxWmType.UNKNOWN_OR_UNSUPPORTED, res.wmType)
            assertNotNull(res.fallbackReason)
        }

        // Socket-based Sway / Hyprland / i3 rejections
        assertFalse(LinuxWmDetector.detectFromSnapshot(LinuxWmEnvironmentSnapshot(swaySock = "/run/user/1000/sway-ipc.sock")).isReplicable)
        assertFalse(LinuxWmDetector.detectFromSnapshot(LinuxWmEnvironmentSnapshot(hyprlandInstanceSignature = "abc123")).isReplicable)
        assertFalse(LinuxWmDetector.detectFromSnapshot(LinuxWmEnvironmentSnapshot(i3Sock = "/run/user/1000/i3/ipc-socket")).isReplicable)

        val unsupportedCompositors = listOf(
            "Openbox", "Labwc", "Wayfire", "Fluxbox", "IceWM", "COSMIC", "Enlightenment", "UnknownWM"
        )
        for (wm in unsupportedCompositors) {
            val res = LinuxWmDetector.detectFromSnapshot(
                LinuxWmEnvironmentSnapshot(xdgCurrentDesktop = wm, ewmhWmName = wm)
            )
            assertFalse(res.isReplicable, "Expected compositor '$wm' to be rejected")
            assertNotNull(res.fallbackReason)
        }

        // Custom kdecoration2 plugins rejected
        val aurorae = LinuxWmDetector.detectFromSnapshot(
            LinuxWmEnvironmentSnapshot(
                xdgCurrentDesktop = "KDE",
                ewmhWmName = "KWin",
                kwinRcContent = "[org.kde.kdecoration2]\nlibrary=org.kde.kwin.aurorae\ntheme=__aurorae__svg__Sweet\n"
            )
        )
        assertFalse(aurorae.isReplicable)
        assertTrue(aurorae.fallbackReason!!.contains("aurorae", ignoreCase = true))

        val klassy = LinuxWmDetector.detectFromSnapshot(
            LinuxWmEnvironmentSnapshot(
                xdgCurrentDesktop = "KDE",
                ewmhWmName = "KWin",
                kwinRcContent = "[org.kde.kdecoration2]\nlibrary=org.kde.klassy\ntheme=Klassy\n"
            )
        )
        assertFalse(klassy.isReplicable)

        // Unrecognized GTK theme rejected
        val customGtk = LinuxWmDetector.detectFromSnapshot(
            LinuxWmEnvironmentSnapshot(
                xdgCurrentDesktop = "GNOME",
                ewmhWmName = "GNOME Shell",
                gnomeGtkTheme = "Catppuccin-Mocha"
            )
        )
        assertFalse(customGtk.isReplicable)

        // Structural GTK CSS overrides rejected across headerbar / windowcontrols / .titlebar / decoration
        for (cssRule in listOf(
            "headerbar { min-height: 24px; }",
            "windowcontrols > button { margin: 0; }",
            "window.csd .titlebar { padding: 2px; }",
            "decoration { border-radius: 0px; }"
        )) {
            val cssRejected4 = LinuxWmDetector.detectFromSnapshot(
                LinuxWmEnvironmentSnapshot(
                    xdgCurrentDesktop = "GNOME",
                    ewmhWmName = "GNOME Shell",
                    gtk4UserCssContent = cssRule
                )
            )
            assertFalse(cssRejected4.isReplicable, "Expected gtk4 CSS '$cssRule' to be rejected")

            val cssRejected3 = LinuxWmDetector.detectFromSnapshot(
                LinuxWmEnvironmentSnapshot(
                    xdgCurrentDesktop = "ubuntu:GNOME",
                    ewmhWmName = "GNOME Shell",
                    gnomeGtkTheme = "Yaru",
                    gtk3UserCssContent = cssRule
                )
            )
            assertFalse(cssRejected3.isReplicable, "Expected gtk3 CSS '$cssRule' to be rejected")
        }

        // Harmless @define-color or unrelated CSS comments must NOT falsely reject
        val safeCss = LinuxWmDetector.detectFromSnapshot(
            LinuxWmEnvironmentSnapshot(
                xdgCurrentDesktop = "GNOME",
                ewmhWmName = "GNOME Shell",
                gtk4UserCssContent = """
                    /* headerbar comment only */
                    @define-color accent_bg_color #3584e4;
                    .my-sidebar-card { padding: 8px; }
                """.trimIndent()
            )
        )
        assertTrue(safeCss.isReplicable)
    }

    @Test
    fun layoutEngineHitTestsDragBoundsAndActionDispatcher() {
        val profile = LinuxWmDetector.detectFromSnapshot(
            LinuxWmEnvironmentSnapshot(
                xdgCurrentDesktop = "KDE",
                ewmhWmName = "KWin",
                kwinRcContent = "[org.kde.kdecoration2]\nButtonsOnLeft=M_\nButtonsOnRight=FIAX\n"
            )
        )
        val layout = LinuxTitleBarLayoutEngine.computeLayout(
            profile = profile,
            barBounds = Rect(0, 0, 800, 32),
            leadingWidthPx = 48,
            centerWidthPx = 120,
            trailingWidthPx = 64
        )

        // Left has MENU (SPACER skipped from clickable hits, adds horizontal gap)
        assertEquals(1, layout.leftButtons.size)
        assertEquals(LinuxTitleButtonKind.MENU, layout.leftButtons[0].kind)
        // Right has KEEP_ABOVE, MINIMIZE, MAXIMIZE_OR_RESTORE, CLOSE
        assertEquals(4, layout.rightButtons.size)
        val closeHit = layout.rightButtons.last()
        assertEquals(LinuxTitleButtonKind.CLOSE, closeHit.kind)

        assertTrue(layout.leadingSlotBounds.x >= layout.contentSlotBounds.x)
        assertEquals(48, layout.leadingSlotBounds.width)
        assertEquals(120, layout.centerSlotBounds.width)
        assertEquals(64, layout.trailingSlotBounds.width)
        assertTrue(layout.trailingSlotBounds.right <= layout.contentSlotBounds.right)

        val closeCenterX = closeHit.bounds.x + closeHit.bounds.width / 2
        val closeCenterY = closeHit.bounds.y + closeHit.bounds.height / 2
        assertEquals(LinuxTitleButtonKind.CLOSE, LinuxTitleBarLayoutEngine.hitTestButton(layout, closeCenterX, closeCenterY)?.kind)
        assertFalse(LinuxTitleBarLayoutEngine.isDraggableBackground(layout, closeCenterX, closeCenterY))

        val spacerAreaX = layout.leftButtons[0].bounds.right + 4
        assertNull(LinuxTitleBarLayoutEngine.hitTestButton(layout, spacerAreaX, closeCenterY))
        assertTrue(LinuxTitleBarLayoutEngine.isDraggableBackground(layout, spacerAreaX, closeCenterY))

        val widgetRect = Rect(layout.leadingSlotBounds.x + 2, 4, 40, 24)
        assertFalse(LinuxTitleBarLayoutEngine.isDraggableBackground(layout, widgetRect.x + 5, 10, listOf(widgetRect)))
        assertTrue(LinuxTitleBarLayoutEngine.isDraggableBackground(layout, 400, 16, listOf(widgetRect)))

        val actions = FakeWindowActions()
        for (kind in listOf(
            LinuxTitleButtonKind.CLOSE,
            LinuxTitleButtonKind.MINIMIZE,
            LinuxTitleButtonKind.MAXIMIZE_OR_RESTORE,
            LinuxTitleButtonKind.MENU,
            LinuxTitleButtonKind.KEEP_ABOVE,
            LinuxTitleButtonKind.KEEP_BELOW,
            LinuxTitleButtonKind.ON_ALL_DESKTOPS,
            LinuxTitleButtonKind.SHADE
        )) {
            assertTrue(LinuxTitleBarLayoutEngine.dispatchButtonAction(kind, actions, 10, 20))
        }
        assertEquals(1, actions.closeCount)
        assertEquals(1, actions.minimizeCount)
        assertEquals(1, actions.maximizeCount)
        assertEquals(1, actions.menuCount)
        assertEquals(1, actions.keepAboveCount)
        assertEquals(1, actions.keepBelowCount)
        assertEquals(1, actions.allDesktopsCount)
        assertEquals(1, actions.shadeCount)
    }

    @Test
    fun rendererDrawsDistinctVectorAndPixmapStatesAcrossAllSixFamilies() {
        val bar = Rect(0, 0, 640, 38)

        // 1. Yaru active vs backdrop vs close hover
        val yaru = LinuxWmDetector.detectFromSnapshot(
            LinuxWmEnvironmentSnapshot(xdgCurrentDesktop = "ubuntu:GNOME", ewmhWmName = "GNOME Shell", gnomeGtkTheme = "Yaru")
        )
        val yaruLayout = LinuxTitleBarLayoutEngine.computeLayout(yaru, bar)
        val yaruActiveCanvas = RecordingTitleBarCanvas()
        LinuxTitleBarRenderer.render(yaruActiveCanvas, yaru, yaruLayout, "GenericUI", isActiveWindow = true, isMaximized = false, hoveredButton = LinuxTitleButtonKind.CLOSE)
        assertTrue(yaruActiveCanvas.circleFills.any { it.color == yaru.palette.closeBgHover })
        assertTrue(yaruActiveCanvas.drawnTexts.any { it.text == "GenericUI" && it.color == yaru.palette.activeTitleText })

        val yaruBackdropCanvas = RecordingTitleBarCanvas()
        LinuxTitleBarRenderer.render(yaruBackdropCanvas, yaru, yaruLayout, "GenericUI", isActiveWindow = false, isMaximized = false)
        assertTrue(yaruBackdropCanvas.drawnTexts.any { it.color == yaru.palette.inactiveTitleText })

        // 2. Libadwaita translucent disc hover
        val adw = LinuxWmDetector.detectFromSnapshot(
            LinuxWmEnvironmentSnapshot(xdgCurrentDesktop = "GNOME", ewmhWmName = "GNOME Shell", gnomeAccentColor = "purple")
        )
        val adwLayout = LinuxTitleBarLayoutEngine.computeLayout(adw, bar)
        val adwCanvas = RecordingTitleBarCanvas()
        LinuxTitleBarRenderer.render(adwCanvas, adw, adwLayout, "Adwaita App", isActiveWindow = true, isMaximized = false, hoveredButton = LinuxTitleButtonKind.CLOSE)
        assertTrue(adwCanvas.circleFills.any { it.color == adw.palette.closeBgHover })

        // 3. KWin Breeze outlineCloseButton ring + crimson hover + top highlight + separator + maximized border
        val breeze = LinuxWmDetector.detectFromSnapshot(
            LinuxWmEnvironmentSnapshot(
                xdgCurrentDesktop = "KDE",
                ewmhWmName = "KWin",
                kwinRcContent = "[org.kde.kdecoration2]\nButtonsOnLeft=MNSH\nButtonsOnRight=FBIAX\n",
                breezeRcContent = "[Windeco]\nOutlineCloseButton=true\nDrawBorderOnMaximizedWindows=true\nDrawBackgroundGradient=true\n"
            )
        )
        val breezeLayout = LinuxTitleBarLayoutEngine.computeLayout(breeze, Rect(0, 0, 640, 30))
        val breezeNormalCanvas = RecordingTitleBarCanvas()
        LinuxTitleBarRenderer.render(breezeNormalCanvas, breeze, breezeLayout, "KDE Window", isActiveWindow = true, isMaximized = false)
        assertTrue(breezeNormalCanvas.circleStrokes.isNotEmpty(), "Expected Breeze outlineCloseButton + pin ring strokes")
        assertTrue(breezeNormalCanvas.gradientTopRoundedCount > 0)
        assertTrue(breezeNormalCanvas.lines.size >= 4)

        val breezeHoverMaxCanvas = RecordingTitleBarCanvas()
        LinuxTitleBarRenderer.render(
            breezeHoverMaxCanvas, breeze, breezeLayout, "KDE Window",
            isActiveWindow = true, isMaximized = true,
            hoveredButton = LinuxTitleButtonKind.CLOSE,
            buttonStates = setOf(LinuxTitleButtonKind.KEEP_ABOVE)
        )
        assertTrue(breezeHoverMaxCanvas.circleFills.any { it.color == breeze.palette.closeBgHover })

        // 4. Mint-Y accent close disc
        val mintY = LinuxWmDetector.detectFromSnapshot(
            LinuxWmEnvironmentSnapshot(xdgCurrentDesktop = "X-Cinnamon", ewmhWmName = "Mutter (Muffin)", cinnamonWmTheme = "Mint-Y-Teal")
        )
        val mintLayout = LinuxTitleBarLayoutEngine.computeLayout(mintY, bar)
        val mintCanvas = RecordingTitleBarCanvas()
        LinuxTitleBarRenderer.render(mintCanvas, mintY, mintLayout, "Mint Window", isActiveWindow = true, isMaximized = false)
        assertTrue(mintCanvas.circleFills.any { it.color == mintY.palette.accentColor })

        // 5. Marco
        val marco = LinuxWmDetector.detectFromSnapshot(
            LinuxWmEnvironmentSnapshot(xdgCurrentDesktop = "MATE", ewmhWmName = "Marco", marcoTheme = "TraditionalGreen")
        )
        val marcoLayout = LinuxTitleBarLayoutEngine.computeLayout(marco, bar)
        val marcoCanvas = RecordingTitleBarCanvas()
        LinuxTitleBarRenderer.render(marcoCanvas, marco, marcoLayout, "MATE Window", isActiveWindow = true, isMaximized = false)
        assertTrue(marcoCanvas.drawnTexts.any { it.text == "MATE Window" })

        // 6. Xfwm4 pixmap blits
        val sprite = LinuxXfwm4SpriteSet(IntArray(16) { 0xFF336699.toInt() }, 4, 4)
        val xfwmAssets = LinuxXfwm4ThemeAssets(
            themeName = "PixmapTest",
            topLeftActive = sprite,
            topRightActive = sprite,
            titleActiveSlices = listOf(sprite),
            buttonActive = mapOf(LinuxTitleButtonKind.CLOSE to sprite),
            buttonPrelight = mapOf(LinuxTitleButtonKind.CLOSE to LinuxXfwm4SpriteSet(IntArray(16) { 0xFFFF0000.toInt() }, 4, 4))
        )
        val xfwm = LinuxWmDetector.detectFromSnapshot(
            LinuxWmEnvironmentSnapshot(xdgCurrentDesktop = "XFCE", ewmhWmName = "Xfwm4", xfwm4Assets = xfwmAssets)
        )
        val xfwmLayout = LinuxTitleBarLayoutEngine.computeLayout(xfwm, Rect(0, 0, 400, 28))
        val xfwmCanvas = RecordingTitleBarCanvas()
        LinuxTitleBarRenderer.render(xfwmCanvas, xfwm, xfwmLayout, "Xfce Window", isActiveWindow = true, isMaximized = false, hoveredButton = LinuxTitleButtonKind.CLOSE)
        assertTrue(xfwmCanvas.argbBlits.size >= 4)
        assertTrue(xfwmCanvas.argbBlits.any { it.firstPixel == 0xFFFF0000.toInt() })
    }

    @Test
    fun profileManagerCapabilitiesSafeGSettingsLookupAndMdiTitleBarHelper() {
        // Safe GSettings check must NEVER abort on missing schema
        assertFalse(LinuxGlibGioBindings.gsettingsHasSchema("org.nexustools.nonexistent.schema.check"))
        assertNull(LinuxGlibGioBindings.gsettingsReadString("org.nexustools.nonexistent.schema.check", "missing-key"))

        // Live probe executes safely in any host/container environment
        val live = LinuxWmDetector.probeLiveEnvironment()
        assertNotNull(live)

        // Leading controls placement (Ubuntu left controls)
        val leftYaru = LinuxWmDetector.detectFromSnapshot(
            LinuxWmEnvironmentSnapshot(
                xdgCurrentDesktop = "ubuntu:GNOME",
                ewmhWmName = "GNOME Shell",
                gnomeGtkTheme = "Yaru",
                gnomeWmButtonLayout = "close,minimize,maximize:"
            )
        )
        LinuxTitleBarProfileManager.setTestProfileOverride(leftYaru)
        val capsLeading = LinuxTitleBarProfileManager.resolveTitleCapabilities()
        assertTrue(capsLeading.supportsCustomTitleBar)
        assertTrue(capsLeading.canEmbedComponentsInTitleBar)
        assertEquals(TitleBarControlsPlacement.LEADING, capsLeading.defaultControlsPlacement)

        val toolCaps = LinuxTitleBarProfileManager.resolveTitleCapabilities(isToolModalWindow = true, minimizable = false)
        assertTrue(toolCaps.isToolWindowTitleBar)
        assertFalse(toolCaps.supportsMinimizeButton)
        assertFalse(toolCaps.supportsMaximizeButton)

        // Non-replicable WM falls back cleanly in TitleCapabilities, while emulated MDI falls back to Breeze/Libadwaita
        val swayRejected = LinuxWmDetector.detectFromSnapshot(
            LinuxWmEnvironmentSnapshot(swaySock = "/tmp/sway.sock")
        )
        LinuxTitleBarProfileManager.setTestProfileOverride(swayRejected)
        val unsupportedCaps = LinuxTitleBarProfileManager.resolveTitleCapabilities()
        assertFalse(unsupportedCaps.supportsCustomTitleBar)
        assertFalse(unsupportedCaps.canEmbedComponentsInTitleBar)
        assertFalse(unsupportedCaps.supportsEmbeddedMenuBar)

        val mdiQtDark = LinuxTitleBarProfileManager.resolveEmulatedMdiProfile(preferKdeStyle = true, isDarkFallback = true)
        assertTrue(mdiQtDark.isReplicable)
        assertEquals(LinuxTitleBarThemeFamily.BREEZE, mdiQtDark.themeFamily)
        assertTrue(mdiQtDark.palette.isDark)

        val mdiGtkLight = LinuxTitleBarProfileManager.resolveEmulatedMdiProfile(preferKdeStyle = false, isDarkFallback = false)
        assertTrue(mdiGtkLight.isReplicable)
        assertEquals(LinuxTitleBarThemeFamily.LIBADWAITA, mdiGtkLight.themeFamily)
        assertFalse(mdiGtkLight.palette.isDark)

        // MDI helper orchestration with MdiContainer + MdiWindow
        val container = MdiContainer()
        val win1 = container.createWindow("w1", "Doc One", bounds = Rect(10, 10, 420, 300))
        win1.setTitleBar(TitleBar(controlsPlacement = TitleBarControlsPlacement.TRAILING).apply {
            addLeading(Button("New").apply { widthPx = 44 })
        })

        val headerBounds = Rect(0, 0, 420, 30)
        val mdiLayout = LinuxMdiTitleBarHelper.computeMdiLayout(
            mdiWindow = win1,
            headerBarBounds = headerBounds,
            preferKdeStyle = true,
            isDarkFallback = false
        )
        assertEquals(44, mdiLayout.leadingSlotBounds.width)
        assertEquals(3, mdiLayout.rightButtons.size)

        val mdiCanvas = RecordingTitleBarCanvas()
        LinuxMdiTitleBarHelper.renderMdiHeaderBar(mdiCanvas, win1, mdiLayout, preferKdeStyle = true)
        assertTrue(mdiCanvas.drawnTexts.any { it.text == "Doc One" })

        // Double-click background maximizes MDI window
        val bgOutcome = LinuxMdiTitleBarHelper.dispatchMdiHeaderAction(win1, mdiLayout, 200, 15, isDoubleClick = true)
        assertTrue(bgOutcome.handled)
        assertTrue(bgOutcome.toggledMaximizeByDoubleClick)
        assertEquals(MdiWindowState.MAXIMIZED, win1.windowState)

        // Restore via double-click again, then drag on normal background
        LinuxMdiTitleBarHelper.dispatchMdiHeaderAction(win1, mdiLayout, 200, 15, isDoubleClick = true)
        assertEquals(MdiWindowState.NORMAL, win1.windowState)

        val dragOutcome = LinuxMdiTitleBarHelper.dispatchMdiHeaderAction(win1, mdiLayout, 200, 15, isDoubleClick = false)
        assertTrue(dragOutcome.handled)
        assertTrue(dragOutcome.startedDrag)

        // Click close button closes win1 inside container
        val closeBtn = mdiLayout.rightButtons.last()
        val closeOutcome = LinuxMdiTitleBarHelper.dispatchMdiHeaderAction(
            win1,
            mdiLayout,
            closeBtn.bounds.x + closeBtn.bounds.width / 2,
            closeBtn.bounds.y + closeBtn.bounds.height / 2
        )
        assertTrue(closeOutcome.handled)
        assertEquals(LinuxTitleButtonKind.CLOSE, closeOutcome.clickedButton)
        assertNull(container.findWindow("w1"))
    }

    private class FakeWindowActions : LinuxTitleBarWindowActions {
        var closeCount = 0
        var minimizeCount = 0
        var maximizeCount = 0
        var menuCount = 0
        var keepAboveCount = 0
        var keepBelowCount = 0
        var allDesktopsCount = 0
        var shadeCount = 0
        var moveCount = 0
        var dblMaxCount = 0

        override fun onClose() { closeCount++ }
        override fun onMinimize() { minimizeCount++ }
        override fun onMaximizeOrRestore() { maximizeCount++ }
        override fun onShowWindowMenu(screenX: Int, screenY: Int) { menuCount++ }
        override fun onToggleKeepAbove() { keepAboveCount++ }
        override fun onToggleKeepBelow() { keepBelowCount++ }
        override fun onToggleAllDesktops() { allDesktopsCount++ }
        override fun onToggleShade() { shadeCount++ }
        override fun onStartSystemMove() { moveCount++ }
        override fun onDoubleClickMaximize() { dblMaxCount++ }
    }

    private class RecordingTitleBarCanvas : LinuxTitleBarCanvas {
        data class CircleCall(val cx: Float, val cy: Float, val radius: Float, val color: RgbaColor)
        data class LineCall(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val color: RgbaColor)
        data class TextCall(val text: String, val bounds: Rect, val color: RgbaColor, val centered: Boolean)
        data class ArgbBlitCall(val firstPixel: Int, val width: Int, val height: Int, val dstRect: Rect)

        var gradientTopRoundedCount = 0
        val circleFills = ArrayList<CircleCall>()
        val circleStrokes = ArrayList<CircleCall>()
        val lines = ArrayList<LineCall>()
        val drawnTexts = ArrayList<TextCall>()
        val argbBlits = ArrayList<ArgbBlitCall>()

        override fun save() {}
        override fun restore() {}
        override fun clipRect(rect: Rect) {}
        override fun fillRect(rect: Rect, color: RgbaColor) {}
        override fun fillTopRoundedRect(rect: Rect, topRadiusPx: Float, color: RgbaColor) {}
        override fun fillLinearGradientTopRounded(rect: Rect, topRadiusPx: Float, topColor: RgbaColor, bottomColor: RgbaColor) {
            gradientTopRoundedCount++
        }
        override fun fillRoundedRect(rect: Rect, radiusPx: Float, color: RgbaColor) {}
        override fun strokeRoundedRect(rect: Rect, radiusPx: Float, color: RgbaColor, strokeWidthPx: Float) {}
        override fun fillCircle(cx: Float, cy: Float, radius: Float, color: RgbaColor) {
            circleFills.add(CircleCall(cx, cy, radius, color))
        }
        override fun strokeCircle(cx: Float, cy: Float, radius: Float, color: RgbaColor, strokeWidthPx: Float) {
            circleStrokes.add(CircleCall(cx, cy, radius, color))
        }
        override fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float, color: RgbaColor, strokeWidthPx: Float) {
            lines.add(LineCall(x1, y1, x2, y2, color))
        }
        override fun drawText(text: String, bounds: Rect, color: RgbaColor, fontSizePx: Float, bold: Boolean, centered: Boolean) {
            drawnTexts.add(TextCall(text, bounds, color, centered))
        }
        override fun measureTextWidth(text: String, fontSizePx: Float, bold: Boolean): Int =
            text.length * 7
        override fun drawArgbPixels(pixels: IntArray, srcWidth: Int, srcHeight: Int, dstRect: Rect, opacity: Float) {
            argbBlits.add(ArgbBlitCall(pixels.firstOrNull() ?: 0, srcWidth, srcHeight, dstRect))
        }
    }
}
