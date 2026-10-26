@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import kotlinx.cinterop.*
import platform.windows.*
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Dynamic bridge to Windows `dwmapi.dll`, `uxtheme.dll`, `ntdll.dll`, and Registry personalization settings.
 *
 * Enables `:desktop:windows` to:
 * 1. Detect the exact Windows version (`Vista`/`7` Aero Glass, `8`/`8.1` Aero, `10` Fluent, `11` Mica/Fluent,
 *    `UxTheme` Basic, or `Win32` Classic) via `RtlGetVersion`, `DwmIsCompositionEnabled`, and `IsThemeActive`.
 * 2. Extend the real native DWM non-client frame into the titlebar area (`DwmExtendFrameIntoClientArea`)
 *    and delegate native caption button hit-testing to `DwmDefWindowProc`.
 * 3. Configure Windows 10/11 dark mode (`DWMWA_USE_IMMERSIVE_DARK_MODE`), Windows 11 Mica backdrop
 *    (`DWMWA_SYSTEMBACKDROP_TYPE`), and rounded window corners (`DWMWA_WINDOW_CORNER_PREFERENCE`).
 * 4. Query the user's live Windows titlebar personalization (`ColorPrevalence`, `AccentColor`,
 *    `AccentColorInactive`, `DwmGetColorizationColor`, `AppsUseLightTheme`, and `GetSysColor`).
 */
object Win32DwmUxThemeBridge {

    // DWMWA_* constants
    const val DWMWA_NCRENDERING_ENABLED: UInt = 1u
    const val DWMWA_CAPTION_BUTTON_BOUNDS: UInt = 5u
    const val DWMWA_EXTENDED_FRAME_BOUNDS: UInt = 9u
    const val DWMWA_USE_IMMERSIVE_DARK_MODE_OLD: UInt = 19u
    const val DWMWA_USE_IMMERSIVE_DARK_MODE: UInt = 20u
    const val DWMWA_WINDOW_CORNER_PREFERENCE: UInt = 33u
    const val DWMWA_BORDER_COLOR: UInt = 34u
    const val DWMWA_CAPTION_COLOR: UInt = 35u
    const val DWMWA_TEXT_COLOR: UInt = 36u
    const val DWMWA_SYSTEMBACKDROP_TYPE: UInt = 38u

    // DWM_WINDOW_CORNER_PREFERENCE values
    const val DWMWCP_DEFAULT: Int = 0
    const val DWMWCP_DONOTROUND: Int = 1
    const val DWMWCP_ROUND: Int = 2
    const val DWMWCP_ROUNDSMALL: Int = 3

    // DWM_SYSTEMBACKDROP_TYPE values (Windows 11 22H2+)
    const val DWMSBT_AUTO: Int = 0
    const val DWMSBT_NONE: Int = 1
    const val DWMSBT_MAINWINDOW: Int = 2      // Mica
    const val DWMSBT_TRANSIENTWINDOW: Int = 3 // Acrylic
    const val DWMSBT_TABBEDWINDOW: Int = 4    // Mica Alt

    // UxTheme WINDOW parts & states
    const val WP_CAPTION: Int = 1
    const val WP_SMALLCAPTION: Int = 2
    const val WP_MINBUTTON: Int = 15
    const val WP_MAXBUTTON: Int = 17
    const val WP_CLOSEBUTTON: Int = 18
    const val WP_SMALLCLOSEBUTTON: Int = 19
    const val WP_RESTOREBUTTON: Int = 21

    const val CS_ACTIVE: Int = 1
    const val CS_INACTIVE: Int = 2

    const val CBS_NORMAL: Int = 1
    const val CBS_HOT: Int = 2
    const val CBS_PUSHED: Int = 3
    const val CBS_DISABLED: Int = 4

    private var ntdllModule: HMODULE? = null
    private var dwmModule: HMODULE? = null
    private var uxThemeModule: HMODULE? = null
    private var modulesResolved: Boolean = false

    private var cachedOsVersion: Triple<Int, Int, Int>? = null
    private var lastBackdropAppliedByHwnd = mutableMapOf<Long, Pair<Int, Boolean>>()

    private fun ensureModulesLoaded() {
        if (modulesResolved) return
        modulesResolved = true
        ntdllModule = GetModuleHandleW("ntdll.dll") ?: LoadLibraryW("ntdll.dll")
        dwmModule = GetModuleHandleW("dwmapi.dll") ?: LoadLibraryW("dwmapi.dll")
        uxThemeModule = GetModuleHandleW("uxtheme.dll") ?: LoadLibraryW("uxtheme.dll")
    }

    /**
     * Queries the true Windows OS `(major, minor, build)` version via `ntdll.dll!RtlGetVersion`.
     */
    fun queryOsVersionTriple(): Triple<Int, Int, Int> {
        cachedOsVersion?.let { return it }
        ensureModulesLoaded()
        val result = memScoped {
            val mod = ntdllModule
            val proc = if (mod != null) GetProcAddress(mod, "RtlGetVersion") else null
            if (proc != null) {
                val fn = proc.reinterpret<CFunction<(CPointer<OSVERSIONINFOW>) -> Int>>()
                val info = alloc<OSVERSIONINFOW>()
                info.dwOSVersionInfoSize = sizeOf<OSVERSIONINFOW>().toUInt()
                if (fn(info.ptr) == 0) {
                    return@memScoped Triple(
                        info.dwMajorVersion.toInt(),
                        info.dwMinorVersion.toInt(),
                        info.dwBuildNumber.toInt()
                    )
                }
            }
            Triple(10, 0, 19045)
        }
        cachedOsVersion = result
        return result
    }

    /**
     * Returns `true` when Desktop Window Manager (DWM) composition is active.
     */
    fun isDwmCompositionEnabled(): Boolean = memScoped {
        ensureModulesLoaded()
        val mod = dwmModule ?: return@memScoped false
        val proc = GetProcAddress(mod, "DwmIsCompositionEnabled") ?: return@memScoped false
        val fn = proc.reinterpret<CFunction<(CPointer<BOOLVar>) -> HRESULT>>()
        val enabled = alloc<BOOLVar>()
        enabled.value = 0
        val hr = fn(enabled.ptr)
        hr >= 0 && enabled.value != 0
    }

    /**
     * Returns `true` when `uxtheme.dll` visual styles are active (`IsThemeActive` & `IsAppThemed`)
     * and Windows High Contrast mode is not forcing classic framing.
     */
    fun isUxThemeActive(): Boolean = memScoped {
        ensureModulesLoaded()
        val mod = uxThemeModule ?: return@memScoped false
        val isThemeActiveProc = GetProcAddress(mod, "IsThemeActive") ?: return@memScoped false
        val isAppThemedProc = GetProcAddress(mod, "IsAppThemed") ?: return@memScoped false
        val fnTheme = isThemeActiveProc.reinterpret<CFunction<() -> Int>>()
        val fnApp = isAppThemedProc.reinterpret<CFunction<() -> Int>>()
        fnTheme() != 0 && fnApp() != 0
    }

    /**
     * Resolves the active [Win32VisualStyleGeneration] for the current OS version and DWM/UxTheme state.
     */
    fun detectVisualStyleGeneration(
        forceMajor: Int? = null,
        forceMinor: Int? = null,
        forceBuild: Int? = null,
        dwmEnabled: Boolean = isDwmCompositionEnabled(),
        uxThemeActive: Boolean = isUxThemeActive()
    ): Win32VisualStyleGeneration {
        val (osMajor, osMinor, osBuild) = queryOsVersionTriple()
        val major = forceMajor ?: osMajor
        val minor = forceMinor ?: osMinor
        val build = forceBuild ?: osBuild

        if (!dwmEnabled) {
            return if (uxThemeActive) {
                Win32VisualStyleGeneration.UXTHEME_BASIC
            } else {
                Win32VisualStyleGeneration.WIN32_CLASSIC
            }
        }

        return when {
            major > 10 || (major == 10 && build >= 22000) -> Win32VisualStyleGeneration.WIN11_MICA_FLUENT
            major == 10 -> Win32VisualStyleGeneration.WIN10_FLUENT
            major == 6 && minor >= 2 -> Win32VisualStyleGeneration.WIN8_AERO
            major == 6 && minor in 0..1 -> Win32VisualStyleGeneration.VISTA_7_AERO_GLASS
            uxThemeActive -> Win32VisualStyleGeneration.UXTHEME_BASIC
            else -> Win32VisualStyleGeneration.WIN32_CLASSIC
        }
    }

    /**
     * Extends the native DWM non-client frame into the client area (`DwmExtendFrameIntoClientArea`).
     * Passing `top > 0` extends the top caption frame by `top` pixels so Aero Glass (Vista/7),
     * Aero (Win8), or Mica (Win11) and native DWM caption buttons operate in that region.
     */
    fun extendFrameIntoClientArea(
        hwnd: HWND,
        left: Int = 0,
        right: Int = 0,
        top: Int = 0,
        bottom: Int = 0
    ): Boolean = memScoped {
        ensureModulesLoaded()
        val mod = dwmModule ?: return@memScoped false
        val proc = GetProcAddress(mod, "DwmExtendFrameIntoClientArea") ?: return@memScoped false
        val fn = proc.reinterpret<CFunction<(HWND?, CPointer<IntVar>) -> HRESULT>>()
        val margins = allocArray<IntVar>(4)
        margins[0] = left
        margins[1] = right
        margins[2] = top
        margins[3] = bottom
        fn(hwnd, margins) >= 0
    }

    /**
     * Forwards non-client and hit-test messages to `DwmDefWindowProc` so DWM can natively highlight
     * and operate its Minimize, Maximize/Restore, and Close caption buttons.
     * Returns the `LRESULT` when `DwmDefWindowProc` handled the message, or `null` otherwise.
     */
    fun dwmDefWindowProc(
        hwnd: HWND?,
        msg: UINT,
        wParam: WPARAM,
        lParam: LPARAM
    ): Long? = memScoped {
        if (hwnd == null) return@memScoped null
        ensureModulesLoaded()
        val mod = dwmModule ?: return@memScoped null
        val proc = GetProcAddress(mod, "DwmDefWindowProc") ?: return@memScoped null
        val fn = proc.reinterpret<CFunction<(HWND?, UINT, WPARAM, LPARAM, CPointer<LRESULTVar>) -> Int>>()
        val lResult = alloc<LRESULTVar>()
        lResult.value = 0L
        val handled = fn(hwnd, msg, wParam, lParam, lResult.ptr)
        if (handled != 0) lResult.value else null
    }

    /**
     * Queries `DWMWA_CAPTION_BUTTON_BOUNDS` (`dwAttribute = 5`) for the width in pixels reserved
     * on the right side of the titlebar by the native Minimize/Maximize/Close button cluster.
     */
    fun queryDwmCaptionButtonBoundsWidth(
        hwnd: HWND?,
        isToolWindow: Boolean = false,
        kind: Win32TitleBarKind = if (isToolWindow) Win32TitleBarKind.TOOL_WINDOW else Win32TitleBarKind.STANDARD_WINDOW
    ): Int = memScoped {
        if (hwnd != null) {
            ensureModulesLoaded()
            val mod = dwmModule
            val proc = if (mod != null) GetProcAddress(mod, "DwmGetWindowAttribute") else null
            if (proc != null) {
                val fn = proc.reinterpret<CFunction<(HWND?, DWORD, CValuesRef<*>?, DWORD) -> HRESULT>>()
                val rc = alloc<RECT>()
                val hr = fn(hwnd, DWMWA_CAPTION_BUTTON_BOUNDS, rc.ptr, sizeOf<RECT>().toUInt())
                val w = rc.right - rc.left
                if (hr >= 0 && w in 20..320) {
                    return@memScoped w
                }
            }
        }
        kind.defaultCaptionButtonWidthPx * (if (kind.allowMinimizeMaximizeButtons) 3 else 1)
    }

    /**
     * Applies DWM dark mode, Windows 11 corner preference, Windows 11 Mica/Acrylic backdrop,
     * and `DwmExtendFrameIntoClientArea` for [hwnd].
     */
    fun applyWindowDwmAttributes(
        hwnd: HWND,
        isToolWindow: Boolean = false,
        extendTopPx: Int,
        isDark: Boolean = isSystemDarkTheme(),
        kind: Win32TitleBarKind = if (isToolWindow) Win32TitleBarKind.TOOL_WINDOW else Win32TitleBarKind.STANDARD_WINDOW
    ) = memScoped {
        ensureModulesLoaded()
        val mod = dwmModule ?: return@memScoped
        val setAttrProc = GetProcAddress(mod, "DwmSetWindowAttribute")
        if (setAttrProc != null) {
            val setAttr = setAttrProc.reinterpret<CFunction<(HWND?, DWORD, CValuesRef<*>?, DWORD) -> HRESULT>>()

            val darkFlag = alloc<IntVar>()
            darkFlag.value = if (isDark) 1 else 0
            setAttr(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, darkFlag.ptr, sizeOf<IntVar>().toUInt())
            setAttr(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE_OLD, darkFlag.ptr, sizeOf<IntVar>().toUInt())

            val gen = detectVisualStyleGeneration()
            if (gen == Win32VisualStyleGeneration.WIN11_MICA_FLUENT) {
                val cornerPref = alloc<IntVar>()
                cornerPref.value = kind.win11CornerPreference
                setAttr(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, cornerPref.ptr, sizeOf<IntVar>().toUInt())

                val useBackdrop = extendTopPx > 0 && !isDwmColorPrevalenceEnabled()
                val backdrop = alloc<IntVar>()
                backdrop.value = if (useBackdrop) kind.win11BackdropType else DWMSBT_AUTO
                setAttr(hwnd, DWMWA_SYSTEMBACKDROP_TYPE, backdrop.ptr, sizeOf<IntVar>().toUInt())
            }
        }

        val hwndKey = hwnd.toLong()
        val newState = extendTopPx to isDark
        if (lastBackdropAppliedByHwnd[hwndKey] != newState) {
            lastBackdropAppliedByHwnd[hwndKey] = newState
            extendFrameIntoClientArea(hwnd, left = 0, right = 0, top = max(0, extendTopPx), bottom = 0)
        }
    }

    fun clearCachedHwndState(hwnd: HWND?) {
        if (hwnd == null) {
            lastBackdropAppliedByHwnd.clear()
        } else {
            lastBackdropAppliedByHwnd.remove(hwnd.toLong())
        }
    }

    /**
     * Queries whether Windows is currently using Dark Mode (`AppsUseLightTheme == 0`).
     */
    fun isSystemDarkTheme(): Boolean {
        val appsLight = readHkcuDword(
            "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
            "AppsUseLightTheme"
        )
        if (appsLight != null) {
            return appsLight == 0u
        }
        val sysLight = readHkcuDword(
            "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
            "SystemUsesLightTheme"
        )
        if (sysLight != null) {
            return sysLight == 0u
        }
        return false
    }

    /**
     * Returns `true` when the user has enabled *"Show accent color on title bars and window borders"*
     * in Windows Personalization settings (`HKCU\Software\Microsoft\Windows\DWM\ColorPrevalence == 1`).
     */
    fun isDwmColorPrevalenceEnabled(): Boolean {
        val cp = readHkcuDword("Software\\Microsoft\\Windows\\DWM", "ColorPrevalence")
        return cp != null && cp != 0u
    }

    /**
     * Queries the user's Windows DWM titlebar accent color (`COLORREF` in `0x00BBGGRR` format),
     * reading `AccentColor` (or `AccentColorInactive` when `[active] == false`) on Windows 10/11
     * and `ColorizationColor` / `DwmGetColorizationColor` on Windows Vista/7/8.
     */
    fun queryDwmAccentColor(active: Boolean = true): UInt? {
        if (!active) {
            val inactiveAbgr = readHkcuDword("Software\\Microsoft\\Windows\\DWM", "AccentColorInactive")
            if (inactiveAbgr != null && inactiveAbgr != 0u) {
                return abgrToColorRef(inactiveAbgr)
            }
        }
        val accentAbgr = readHkcuDword("Software\\Microsoft\\Windows\\DWM", "AccentColor")
        if (accentAbgr != null && accentAbgr != 0u) {
            return abgrToColorRef(accentAbgr)
        }
        val colorizationArgb = readHkcuDword("Software\\Microsoft\\Windows\\DWM", "ColorizationColor")
            ?: queryDwmApiColorizationArgb()
        if (colorizationArgb != null && colorizationArgb != 0u) {
            return argbToColorRef(colorizationArgb)
        }
        return null
    }

    private fun queryDwmApiColorizationArgb(): UInt? = memScoped {
        ensureModulesLoaded()
        val mod = dwmModule ?: return@memScoped null
        val proc = GetProcAddress(mod, "DwmGetColorizationColor") ?: return@memScoped null
        val fn = proc.reinterpret<CFunction<(CPointer<DWORDVar>, CPointer<BOOLVar>) -> HRESULT>>()
        val color = alloc<DWORDVar>()
        val opaque = alloc<BOOLVar>()
        if (fn(color.ptr, opaque.ptr) >= 0) color.value else null
    }

    private fun readHkcuDword(subKey: String, valueName: String): UInt? = memScoped {
        val hKey = alloc<HKEYVar>()
        val openRes = RegOpenKeyExW(
            HKEY_CURRENT_USER,
            subKey,
            0u,
            KEY_READ.toUInt(),
            hKey.ptr
        )
        if (openRes != ERROR_SUCCESS) return@memScoped null
        val data = alloc<DWORDVar>()
        val dataSize = alloc<DWORDVar>()
        dataSize.value = sizeOf<DWORDVar>().toUInt()
        val queryRes = RegQueryValueExW(
            hKey.value,
            valueName,
            null,
            null,
            data.ptr.reinterpret(),
            dataSize.ptr
        )
        RegCloseKey(hKey.value)
        if (queryRes == ERROR_SUCCESS) data.value else null
    }

    /**
     * Converts a Windows Registry `AccentColor` (`0xAABBGGRR`) to a GDI `COLORREF` (`0x00BBGGRR`).
     */
    fun abgrToColorRef(abgr: UInt): UInt = abgr and 0x00FFFFFFu

    /**
     * Converts a Windows DWM `ColorizationColor` (`0xAARRGGBB`) to a GDI `COLORREF` (`0x00BBGGRR`).
     */
    fun argbToColorRef(argb: UInt): UInt {
        val r = (argb shr 16) and 0xFFu
        val g = (argb shr 8) and 0xFFu
        val b = argb and 0xFFu
        return r or (g shl 8) or (b shl 16)
    }

    fun colorRefRgb(r: Int, g: Int, b: Int): UInt =
        ((r and 0xFF) or ((g and 0xFF) shl 8) or ((b and 0xFF) shl 16)).toUInt()

    /**
     * Computes perceived luminance (`0.0..1.0`) of a GDI `COLORREF` (`0x00BBGGRR`) so foreground
     * text and caption button icons automatically choose white vs. dark text on accented titlebars.
     */
    fun computeColorRefLuminance(colorRef: UInt): Float {
        val r = (colorRef and 0xFFu).toInt() / 255f
        val g = ((colorRef shr 8) and 0xFFu).toInt() / 255f
        val b = ((colorRef shr 16) and 0xFFu).toInt() / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    /**
     * Resolves the complete native [Win32TitleBarThemePalette] for a window or panel header.
     */
    fun resolveTitleBarPalette(
        hwnd: HWND? = null,
        isWindowActive: Boolean = true,
        isToolWindow: Boolean = false,
        kind: Win32TitleBarKind = if (isToolWindow) Win32TitleBarKind.TOOL_WINDOW else Win32TitleBarKind.STANDARD_WINDOW,
        generation: Win32VisualStyleGeneration = detectVisualStyleGeneration(),
        isDark: Boolean = isSystemDarkTheme(),
        colorPrevalence: Boolean = isDwmColorPrevalenceEnabled(),
        accentColorRef: UInt? = queryDwmAccentColor(isWindowActive)
    ): Win32TitleBarThemePalette {
        val nativeBtnClusterW = queryDwmCaptionButtonBoundsWidth(hwnd, isToolWindow, kind)
        val singleBtnW = kind.defaultCaptionButtonWidthPx
        val allowDwmButtons = kind == Win32TitleBarKind.STANDARD_WINDOW && !isToolWindow

        when (generation) {
            Win32VisualStyleGeneration.VISTA_7_AERO_GLASS -> {
                val glassTint = accentColorRef ?: colorRefRgb(116, 184, 252)
                val activeText = colorRefRgb(0, 0, 0)
                val inactiveText = colorRefRgb(68, 68, 68)
                return Win32TitleBarThemePalette(
                    generation = generation,
                    isWindowActive = isWindowActive,
                    isDark = false,
                    hasAccentColorOnTitleBar = false,
                    useTransparentDwmBackdrop = kind.isTopLevelNonClientFrame,
                    dwmDrawsCaptionButtons = allowDwmButtons,
                    centerWindowTitle = false,
                    drawAeroTextGlow = true,
                    captionBgColor = glassTint,
                    captionBgGradientEndColor = null,
                    bottomBorderColor = null,
                    captionTextColor = if (isWindowActive) activeText else inactiveText,
                    captionSecondaryTextColor = inactiveText,
                    buttonHoverBgColor = colorRefRgb(255, 255, 255),
                    buttonHoverAlpha = 96,
                    buttonPressBgColor = colorRefRgb(220, 235, 252),
                    buttonPressAlpha = 140,
                    closeButtonHoverBgColor = colorRefRgb(218, 59, 42),
                    closeButtonPressBgColor = colorRefRgb(182, 38, 24),
                    closeButtonHoverTextColor = colorRefRgb(255, 255, 255),
                    componentRestBgColor = colorRefRgb(255, 255, 255),
                    componentRestBgAlpha = 45,
                    componentHoverBgColor = colorRefRgb(255, 255, 255),
                    componentHoverBgAlpha = 120,
                    componentPressBgColor = colorRefRgb(210, 230, 250),
                    componentPressBgAlpha = 160,
                    componentBorderColor = colorRefRgb(255, 255, 255),
                    componentBorderAlpha = 110,
                    captionButtonWidthPx = singleBtnW,
                    nativeCaptionButtonsRightInsetPx = nativeBtnClusterW
                )
            }

            Win32VisualStyleGeneration.WIN8_AERO -> {
                val frameBg = if (isWindowActive) {
                    accentColorRef ?: colorRefRgb(110, 180, 230)
                } else {
                    colorRefRgb(235, 235, 235)
                }
                val isDarkBg = computeColorRefLuminance(frameBg) < 0.45f
                val fg = if (isDarkBg) colorRefRgb(255, 255, 255) else colorRefRgb(0, 0, 0)
                val secFg = if (isDarkBg) colorRefRgb(220, 220, 220) else colorRefRgb(90, 90, 90)
                return Win32TitleBarThemePalette(
                    generation = generation,
                    isWindowActive = isWindowActive,
                    isDark = isDarkBg,
                    hasAccentColorOnTitleBar = true,
                    useTransparentDwmBackdrop = false,
                    dwmDrawsCaptionButtons = allowDwmButtons,
                    centerWindowTitle = kind == Win32TitleBarKind.STANDARD_WINDOW,
                    drawAeroTextGlow = false,
                    captionBgColor = frameBg,
                    captionBgGradientEndColor = null,
                    bottomBorderColor = null,
                    captionTextColor = fg,
                    captionSecondaryTextColor = secFg,
                    buttonHoverBgColor = if (isDarkBg) colorRefRgb(255, 255, 255) else colorRefRgb(0, 0, 0),
                    buttonHoverAlpha = 32,
                    buttonPressBgColor = if (isDarkBg) colorRefRgb(255, 255, 255) else colorRefRgb(0, 0, 0),
                    buttonPressAlpha = 56,
                    closeButtonHoverBgColor = colorRefRgb(224, 67, 67),
                    closeButtonPressBgColor = colorRefRgb(180, 45, 45),
                    closeButtonHoverTextColor = colorRefRgb(255, 255, 255),
                    componentRestBgColor = if (isDarkBg) colorRefRgb(255, 255, 255) else colorRefRgb(0, 0, 0),
                    componentRestBgAlpha = 0,
                    componentHoverBgColor = if (isDarkBg) colorRefRgb(255, 255, 255) else colorRefRgb(0, 0, 0),
                    componentHoverBgAlpha = 30,
                    componentPressBgColor = if (isDarkBg) colorRefRgb(255, 255, 255) else colorRefRgb(0, 0, 0),
                    componentPressBgAlpha = 54,
                    componentBorderColor = fg,
                    componentBorderAlpha = 45,
                    captionButtonWidthPx = singleBtnW,
                    nativeCaptionButtonsRightInsetPx = nativeBtnClusterW
                )
            }

            Win32VisualStyleGeneration.WIN11_MICA_FLUENT,
            Win32VisualStyleGeneration.WIN10_FLUENT -> {
                val isWin11 = generation == Win32VisualStyleGeneration.WIN11_MICA_FLUENT
                val hasInactiveAccent = !isWindowActive && colorPrevalence &&
                    readHkcuDword("Software\\Microsoft\\Windows\\DWM", "AccentColorInactive") != null
                val useAccent = colorPrevalence && accentColorRef != null && (isWindowActive || hasInactiveAccent)

                val bgColor = when {
                    useAccent -> accentColorRef
                    isWin11 && isDark -> if (isWindowActive) colorRefRgb(32, 32, 32) else colorRefRgb(32, 32, 32)
                    isWin11 && !isDark -> if (isWindowActive) colorRefRgb(243, 243, 243) else colorRefRgb(243, 243, 243)
                    isDark -> if (isWindowActive) colorRefRgb(0, 0, 0) else colorRefRgb(43, 43, 43)
                    else -> if (isWindowActive) colorRefRgb(255, 255, 255) else colorRefRgb(242, 242, 242)
                }

                val effectiveDarkSurface = if (useAccent) {
                    computeColorRefLuminance(bgColor) < 0.48f
                } else {
                    isDark
                }

                val primaryText = when {
                    useAccent -> if (effectiveDarkSurface) colorRefRgb(255, 255, 255) else colorRefRgb(0, 0, 0)
                    effectiveDarkSurface -> if (isWindowActive) colorRefRgb(255, 255, 255) else colorRefRgb(118, 118, 118)
                    else -> if (isWindowActive) colorRefRgb(26, 26, 26) else colorRefRgb(138, 138, 138)
                }

                val secondaryText = when {
                    effectiveDarkSurface -> if (isWindowActive) colorRefRgb(214, 214, 214) else colorRefRgb(118, 118, 118)
                    else -> if (isWindowActive) colorRefRgb(68, 68, 68) else colorRefRgb(138, 138, 138)
                }

                val hoverColor = if (effectiveDarkSurface) colorRefRgb(255, 255, 255) else colorRefRgb(0, 0, 0)
                val hoverAlpha = if (isWin11) {
                    if (effectiveDarkSurface) 16 else 11
                } else {
                    if (effectiveDarkSurface) 26 else 23
                }
                val pressAlpha = if (isWin11) {
                    if (effectiveDarkSurface) 11 else 20
                } else {
                    if (effectiveDarkSurface) 51 else 46
                }

                val closeHoverBg = if (isWin11) colorRefRgb(196, 43, 28) else colorRefRgb(232, 17, 35)
                val closePressBg = if (isWin11) colorRefRgb(177, 38, 25) else colorRefRgb(241, 112, 122)

                return Win32TitleBarThemePalette(
                    generation = generation,
                    isWindowActive = isWindowActive,
                    isDark = effectiveDarkSurface,
                    hasAccentColorOnTitleBar = useAccent,
                    useTransparentDwmBackdrop = false,
                    dwmDrawsCaptionButtons = false,
                    centerWindowTitle = false,
                    drawAeroTextGlow = false,
                    captionBgColor = bgColor,
                    captionBgGradientEndColor = null,
                    bottomBorderColor = null,
                    captionTextColor = primaryText,
                    captionSecondaryTextColor = secondaryText,
                    buttonHoverBgColor = hoverColor,
                    buttonHoverAlpha = hoverAlpha,
                    buttonPressBgColor = hoverColor,
                    buttonPressAlpha = pressAlpha,
                    closeButtonHoverBgColor = closeHoverBg,
                    closeButtonPressBgColor = closePressBg,
                    closeButtonHoverTextColor = colorRefRgb(255, 255, 255),
                    componentRestBgColor = hoverColor,
                    componentRestBgAlpha = 0,
                    componentHoverBgColor = hoverColor,
                    componentHoverBgAlpha = max(20, hoverAlpha + 6),
                    componentPressBgColor = hoverColor,
                    componentPressBgAlpha = max(32, pressAlpha + 8),
                    componentBorderColor = primaryText,
                    componentBorderAlpha = 38,
                    captionButtonWidthPx = singleBtnW,
                    nativeCaptionButtonsRightInsetPx = nativeBtnClusterW
                )
            }

            Win32VisualStyleGeneration.UXTHEME_BASIC -> {
                val activeBg = colorRefRgb(185, 209, 234)
                val inactiveBg = colorRefRgb(215, 228, 242)
                return Win32TitleBarThemePalette(
                    generation = generation,
                    isWindowActive = isWindowActive,
                    isDark = false,
                    hasAccentColorOnTitleBar = false,
                    useTransparentDwmBackdrop = false,
                    dwmDrawsCaptionButtons = false,
                    centerWindowTitle = false,
                    drawAeroTextGlow = false,
                    captionBgColor = if (isWindowActive) activeBg else inactiveBg,
                    captionBgGradientEndColor = null,
                    bottomBorderColor = colorRefRgb(130, 155, 185),
                    captionTextColor = if (isWindowActive) colorRefRgb(0, 0, 0) else colorRefRgb(68, 84, 106),
                    captionSecondaryTextColor = colorRefRgb(45, 60, 80),
                    buttonHoverBgColor = colorRefRgb(255, 255, 255),
                    buttonHoverAlpha = 90,
                    buttonPressBgColor = colorRefRgb(140, 175, 210),
                    buttonPressAlpha = 130,
                    closeButtonHoverBgColor = colorRefRgb(218, 59, 42),
                    closeButtonPressBgColor = colorRefRgb(182, 38, 24),
                    closeButtonHoverTextColor = colorRefRgb(255, 255, 255),
                    componentRestBgColor = colorRefRgb(255, 255, 255),
                    componentRestBgAlpha = 35,
                    componentHoverBgColor = colorRefRgb(255, 255, 255),
                    componentHoverBgAlpha = 95,
                    componentPressBgColor = colorRefRgb(150, 180, 215),
                    componentPressBgAlpha = 130,
                    componentBorderColor = colorRefRgb(90, 120, 155),
                    componentBorderAlpha = 90,
                    captionButtonWidthPx = singleBtnW,
                    nativeCaptionButtonsRightInsetPx = nativeBtnClusterW
                )
            }

            Win32VisualStyleGeneration.WIN32_CLASSIC -> {
                val startBg = GetSysColor(if (isWindowActive) COLOR_ACTIVECAPTION else COLOR_INACTIVECAPTION)
                val endBg = GetSysColor(if (isWindowActive) 27 /* COLOR_GRADIENTACTIVECAPTION */ else 28 /* COLOR_GRADIENTINACTIVECAPTION */)
                val textCol = GetSysColor(if (isWindowActive) COLOR_CAPTIONTEXT else COLOR_INACTIVECAPTIONTEXT)
                return Win32TitleBarThemePalette(
                    generation = generation,
                    isWindowActive = isWindowActive,
                    isDark = computeColorRefLuminance(startBg) < 0.45f,
                    hasAccentColorOnTitleBar = false,
                    useTransparentDwmBackdrop = false,
                    dwmDrawsCaptionButtons = false,
                    centerWindowTitle = false,
                    drawAeroTextGlow = false,
                    captionBgColor = startBg,
                    captionBgGradientEndColor = endBg,
                    bottomBorderColor = GetSysColor(COLOR_WINDOWFRAME),
                    captionTextColor = textCol,
                    captionSecondaryTextColor = textCol,
                    buttonHoverBgColor = GetSysColor(COLOR_BTNFACE),
                    buttonHoverAlpha = 255,
                    buttonPressBgColor = GetSysColor(COLOR_BTNSHADOW),
                    buttonPressAlpha = 255,
                    closeButtonHoverBgColor = GetSysColor(COLOR_BTNFACE),
                    closeButtonPressBgColor = GetSysColor(COLOR_BTNSHADOW),
                    closeButtonHoverTextColor = GetSysColor(COLOR_BTNTEXT),
                    componentRestBgColor = GetSysColor(COLOR_BTNFACE),
                    componentRestBgAlpha = 220,
                    componentHoverBgColor = GetSysColor(COLOR_BTNHIGHLIGHT),
                    componentHoverBgAlpha = 240,
                    componentPressBgColor = GetSysColor(COLOR_BTNSHADOW),
                    componentPressBgAlpha = 255,
                    componentBorderColor = GetSysColor(COLOR_3DDKSHADOW),
                    componentBorderAlpha = 200,
                    captionButtonWidthPx = 22,
                    nativeCaptionButtonsRightInsetPx = 68
                )
            }
        }
    }

    /**
     * Draws the caption background using `uxtheme.dll!DrawThemeBackground` (`WP_CAPTION` / `WP_SMALLCAPTION`)
     * when `UXTHEME_BASIC` is active. Returns `true` if `uxtheme.dll` painted the background.
     */
    fun drawUxThemeCaptionBackground(
        hwnd: HWND,
        hdc: HDC?,
        width: Int,
        height: Int,
        isWindowActive: Boolean,
        isToolWindow: Boolean,
        kind: Win32TitleBarKind = if (isToolWindow) Win32TitleBarKind.TOOL_WINDOW else Win32TitleBarKind.STANDARD_WINDOW
    ): Boolean = memScoped {
        if (hdc == null) return@memScoped false
        ensureModulesLoaded()
        val mod = uxThemeModule ?: return@memScoped false
        val openProc = GetProcAddress(mod, "OpenThemeData") ?: return@memScoped false
        val closeProc = GetProcAddress(mod, "CloseThemeData") ?: return@memScoped false
        val drawBgProc = GetProcAddress(mod, "DrawThemeBackground") ?: return@memScoped false

        val openTheme = openProc.reinterpret<CFunction<(HWND?, CPointer<WCHARVar>) -> COpaquePointer?>>()
        val closeTheme = closeProc.reinterpret<CFunction<(COpaquePointer?) -> HRESULT>>()
        val drawThemeBg = drawBgProc.reinterpret<
            CFunction<(COpaquePointer?, HDC?, Int, Int, CPointer<RECT>, CPointer<RECT>?) -> HRESULT>
            >()

        val hTheme = openTheme(hwnd, "WINDOW".wcstr.ptr) ?: return@memScoped false
        return@memScoped try {
            val rc = alloc<RECT>()
            rc.left = 0
            rc.top = 0
            rc.right = width
            rc.bottom = height
            val partId = kind.uxThemeCaptionPartId
            val stateId = if (isWindowActive) CS_ACTIVE else CS_INACTIVE
            drawThemeBg(hTheme, hdc, partId, stateId, rc.ptr, null) >= 0
        } finally {
            closeTheme(hTheme)
        }
    }

    /**
     * Draws a caption button (`WP_MINBUTTON`, `WP_MAXBUTTON`, `WP_RESTOREBUTTON`, `WP_CLOSEBUTTON`, `WP_SMALLCLOSEBUTTON`)
     * via `uxtheme.dll!DrawThemeBackground`.
     */
    fun drawUxThemeCaptionButton(
        hwnd: HWND,
        hdc: HDC?,
        partId: Int,
        stateId: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Boolean = memScoped {
        if (hdc == null) return@memScoped false
        ensureModulesLoaded()
        val mod = uxThemeModule ?: return@memScoped false
        val openProc = GetProcAddress(mod, "OpenThemeData") ?: return@memScoped false
        val closeProc = GetProcAddress(mod, "CloseThemeData") ?: return@memScoped false
        val drawBgProc = GetProcAddress(mod, "DrawThemeBackground") ?: return@memScoped false

        val openTheme = openProc.reinterpret<CFunction<(HWND?, CPointer<WCHARVar>) -> COpaquePointer?>>()
        val closeTheme = closeProc.reinterpret<CFunction<(COpaquePointer?) -> HRESULT>>()
        val drawThemeBg = drawBgProc.reinterpret<
            CFunction<(COpaquePointer?, HDC?, Int, Int, CPointer<RECT>, CPointer<RECT>?) -> HRESULT>
            >()

        val hTheme = openTheme(hwnd, "WINDOW".wcstr.ptr) ?: return@memScoped false
        return@memScoped try {
            val rc = alloc<RECT>()
            rc.left = left
            rc.top = top
            rc.right = right
            rc.bottom = bottom
            drawThemeBg(hTheme, hdc, partId, stateId, rc.ptr, null) >= 0
        } finally {
            closeTheme(hTheme)
        }
    }
}
