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

    // UxTheme BUTTON parts & states (BP_PUSHBUTTON / PBS_* / CBS_* / RBS_* / GBS_*)
    const val BP_PUSHBUTTON: Int = 1
    const val BP_RADIOBUTTON: Int = 2
    const val BP_CHECKBOX: Int = 3
    const val BP_GROUPBOX: Int = 4
    const val BP_COMMANDLINK: Int = 6

    const val PBS_NORMAL: Int = 1
    const val PBS_HOT: Int = 2
    const val PBS_PRESSED: Int = 3
    const val PBS_DISABLED: Int = 4
    const val PBS_DEFAULTED: Int = 5
    const val PBS_DEFAULTED_ANIMATING: Int = 6

    const val CBS_UNCHECKEDNORMAL: Int = 1
    const val CBS_UNCHECKEDHOT: Int = 2
    const val CBS_UNCHECKEDPRESSED: Int = 3
    const val CBS_UNCHECKEDDISABLED: Int = 4
    const val CBS_CHECKEDNORMAL: Int = 5
    const val CBS_CHECKEDHOT: Int = 6
    const val CBS_CHECKEDPRESSED: Int = 7
    const val CBS_CHECKEDDISABLED: Int = 8
    const val CBS_MIXEDNORMAL: Int = 9
    const val CBS_MIXEDHOT: Int = 10
    const val CBS_MIXEDPRESSED: Int = 11
    const val CBS_MIXEDDISABLED: Int = 12

    const val RBS_UNCHECKEDNORMAL: Int = 1
    const val RBS_UNCHECKEDHOT: Int = 2
    const val RBS_UNCHECKEDPRESSED: Int = 3
    const val RBS_UNCHECKEDDISABLED: Int = 4
    const val RBS_CHECKEDNORMAL: Int = 5
    const val RBS_CHECKEDHOT: Int = 6
    const val RBS_CHECKEDPRESSED: Int = 7
    const val RBS_CHECKEDDISABLED: Int = 8

    const val GBS_NORMAL: Int = 1
    const val GBS_DISABLED: Int = 2

    // UxTheme EDIT parts & states
    const val EP_EDITTEXT: Int = 1
    const val EP_CARET: Int = 2
    const val EP_BACKGROUND: Int = 3
    const val EP_PASSWORD: Int = 4
    const val EP_BACKGROUNDWITHBORDER: Int = 5
    const val EP_EDITBORDER_NOSCROLL: Int = 6

    const val ETS_NORMAL: Int = 1
    const val ETS_HOT: Int = 2
    const val ETS_SELECTED: Int = 3
    const val ETS_DISABLED: Int = 4
    const val ETS_FOCUSED: Int = 5
    const val ETS_READONLY: Int = 6
    const val ETS_ASSIST: Int = 7
    const val ETS_CUEBANNER: Int = 8

    // UxTheme COMBOBOX parts & states
    const val CP_DROPDOWNBUTTON: Int = 1
    const val CP_BACKGROUND: Int = 2
    const val CP_TRANSPARENTBACKGROUND: Int = 3
    const val CP_BORDER: Int = 4
    const val CP_READONLY: Int = 5
    const val CP_DROPDOWNBUTTONRIGHT: Int = 6
    const val CP_DROPDOWNBUTTONLEFT: Int = 7
    const val CP_CUEBANNER: Int = 8

    const val CBXS_NORMAL: Int = 1
    const val CBXS_HOT: Int = 2
    const val CBXS_PRESSED: Int = 3
    const val CBXS_DISABLED: Int = 4

    // UxTheme PROGRESS parts & states
    const val PP_BAR: Int = 1
    const val PP_BARVERT: Int = 2
    const val PP_CHUNK: Int = 3
    const val PP_CHUNKVERT: Int = 4
    const val PP_FILL: Int = 5
    const val PP_FILLVERT: Int = 6
    const val PP_PULSEOVERLAY: Int = 7
    const val PP_MOVEOVERLAY: Int = 8
    const val PP_PULSEOVERLAYVERT: Int = 9
    const val PP_MOVEOVERLAYVERT: Int = 10
    const val PP_TRANSPARENTBAR: Int = 11
    const val PP_TRANSPARENTBARVERT: Int = 12

    const val PBFS_NORMAL: Int = 1
    const val PBFS_ERROR: Int = 2
    const val PBFS_PAUSED: Int = 3
    const val PBFS_PARTIAL: Int = 4

    // UxTheme TRACKBAR parts & states
    const val TKP_TRACK: Int = 1
    const val TKP_TRACKVERT: Int = 2
    const val TKP_THUMB: Int = 3
    const val TKP_THUMBBOTTOM: Int = 4
    const val TKP_THUMBTOP: Int = 5
    const val TKP_THUMBVERT: Int = 6
    const val TKP_THUMBLEFT: Int = 7
    const val TKP_THUMBRIGHT: Int = 8
    const val TKP_TICS: Int = 9
    const val TKP_TICSVERT: Int = 10

    const val TRS_NORMAL: Int = 1
    const val TUS_NORMAL: Int = 1
    const val TUS_HOT: Int = 2
    const val TUS_PRESSED: Int = 3
    const val TUS_FOCUSED: Int = 4
    const val TUS_DISABLED: Int = 5

    // UxTheme TAB parts & states
    const val TABP_TABITEM: Int = 1
    const val TABP_TABITEMLEFTEDGE: Int = 2
    const val TABP_TABITEMRIGHTEDGE: Int = 3
    const val TABP_TABITEMBOTHEDGE: Int = 4
    const val TABP_TOPTABITEM: Int = 5
    const val TABP_TOPTABITEMLEFTEDGE: Int = 6
    const val TABP_TOPTABITEMRIGHTEDGE: Int = 7
    const val TABP_TOPTABITEMBOTHEDGE: Int = 8
    const val TABP_PANE: Int = 9
    const val TABP_BODY: Int = 10

    const val TIS_NORMAL: Int = 1
    const val TIS_HOT: Int = 2
    const val TIS_SELECTED: Int = 3
    const val TIS_DISABLED: Int = 4
    const val TIS_FOCUSED: Int = 5

    // UxTheme HEADER parts & states
    const val HP_HEADERITEM: Int = 1
    const val HP_HEADERITEMLEFT: Int = 2
    const val HP_HEADERITEMRIGHT: Int = 3
    const val HP_HEADERSORTARROW: Int = 4

    const val HIS_NORMAL: Int = 1
    const val HIS_HOT: Int = 2
    const val HIS_PRESSED: Int = 3
    const val HIS_SORTEDNORMAL: Int = 4
    const val HIS_SORTEDHOT: Int = 5
    const val HIS_SORTEDPRESSED: Int = 6

    // UxTheme SPIN parts & states
    const val SPNP_UP: Int = 1
    const val SPNP_DOWN: Int = 2
    const val SPNP_UPHORZ: Int = 3
    const val SPNP_DOWNHORZ: Int = 4

    const val UPS_NORMAL: Int = 1
    const val UPS_HOT: Int = 2
    const val UPS_PRESSED: Int = 3
    const val UPS_DISABLED: Int = 4

    // UxTheme TOOLBAR parts & states
    const val TP_BUTTON: Int = 1
    const val TP_DROPDOWNBUTTON: Int = 2
    const val TP_SPLITBUTTON: Int = 3
    const val TP_SPLITBUTTONDROPDOWN: Int = 4
    const val TP_SEPARATOR: Int = 5
    const val TP_SEPARATORVERT: Int = 6

    const val TS_NORMAL: Int = 1
    const val TS_HOT: Int = 2
    const val TS_PRESSED: Int = 3
    const val TS_DISABLED: Int = 4
    const val TS_CHECKED: Int = 5
    const val TS_HOTCHECKED: Int = 6

    // UxTheme STATUS parts & states
    const val SP_PANE: Int = 1
    const val SP_GRIPPERPANE: Int = 2
    const val SP_GRIPPER: Int = 3

    // UxTheme SCROLLBAR parts & states
    const val SBP_ARROWBTN: Int = 1
    const val SBP_THUMBBTNHORZ: Int = 2
    const val SBP_THUMBBTNVERT: Int = 3
    const val SBP_LOWERTRACKHORZ: Int = 4
    const val SBP_UPPERTRACKHORZ: Int = 5
    const val SBP_LOWERTRACKVERT: Int = 6
    const val SBP_UPPERTRACKVERT: Int = 7
    const val SBP_GRIPPERHORZ: Int = 8
    const val SBP_GRIPPERVERT: Int = 9
    const val SBP_SIZEBOX: Int = 10

    const val SCRBS_NORMAL: Int = 1
    const val SCRBS_HOT: Int = 2
    const val SCRBS_PRESSED: Int = 3
    const val SCRBS_DISABLED: Int = 4
    const val SCRBS_HOVER: Int = 5

    // UxTheme SCROLLBAR arrow button state IDs (SBP_ARROWBTN)
    const val ABS_UPNORMAL: Int = 1
    const val ABS_UPHOT: Int = 2
    const val ABS_UPPRESSED: Int = 3
    const val ABS_UPDISABLED: Int = 4
    const val ABS_DOWNNORMAL: Int = 5
    const val ABS_DOWNHOT: Int = 6
    const val ABS_DOWNPRESSED: Int = 7
    const val ABS_DOWNDISABLED: Int = 8
    const val ABS_LEFTNORMAL: Int = 9
    const val ABS_LEFTHOT: Int = 10
    const val ABS_LEFTPRESSED: Int = 11
    const val ABS_LEFTDISABLED: Int = 12
    const val ABS_RIGHTNORMAL: Int = 13
    const val ABS_RIGHTHOT: Int = 14
    const val ABS_RIGHTPRESSED: Int = 15
    const val ABS_RIGHTDISABLED: Int = 16

    // UxTheme TREEVIEW parts & states
    const val TVP_TREEITEM: Int = 1
    const val TVP_GLYPH: Int = 2
    const val TVP_BRANCH: Int = 3
    const val TVP_HOTGLYPH: Int = 4

    const val TREIS_NORMAL: Int = 1
    const val TREIS_HOT: Int = 2
    const val TREIS_SELECTED: Int = 3
    const val TREIS_DISABLED: Int = 4
    const val TREIS_SELECTEDNOTFOCUS: Int = 5
    const val GLPS_CLOSED: Int = 1
    const val GLPS_OPENED: Int = 2

    private var ntdllModule: HMODULE? = null
    private var dwmModule: HMODULE? = null
    private var uxThemeModule: HMODULE? = null
    private var modulesResolved: Boolean = false

    private var rtlGetVersionFn: CPointer<CFunction<(CPointer<OSVERSIONINFOW>) -> Int>>? = null
    private var dwmIsCompositionEnabledFn: CPointer<CFunction<(CPointer<BOOLVar>) -> HRESULT>>? = null
    private var dwmSetWindowAttributeFn: CPointer<CFunction<(HWND?, DWORD, CValuesRef<*>?, DWORD) -> HRESULT>>? = null
    private var dwmGetWindowAttributeFn: CPointer<CFunction<(HWND?, DWORD, CValuesRef<*>?, DWORD) -> HRESULT>>? = null
    private var dwmExtendFrameIntoClientAreaFn: CPointer<CFunction<(HWND?, CPointer<IntVar>) -> HRESULT>>? = null
    private var dwmDefWindowProcFn: CPointer<CFunction<(HWND?, UINT, WPARAM, LPARAM, CPointer<LRESULTVar>) -> Int>>? = null
    private var dwmGetColorizationColorFn: CPointer<CFunction<(CPointer<DWORDVar>, CPointer<BOOLVar>) -> HRESULT>>? = null
    private var dwmFlushFn: CPointer<CFunction<() -> HRESULT>>? = null
    private var dwmGetCompositionTimingInfoFn: CPointer<CFunction<(HWND?, CPointer<DWM_TIMING_INFO>) -> HRESULT>>? = null
    private var isAppThemedFn: CPointer<CFunction<() -> Int>>? = null
    private var isThemeActiveFn: CPointer<CFunction<() -> Int>>? = null
    private var setWindowThemeFn: CPointer<CFunction<(HWND?, CPointer<WCHARVar>?, CPointer<WCHARVar>?) -> HRESULT>>? = null
    private var openThemeDataFn: CPointer<CFunction<(HWND?, CPointer<WCHARVar>) -> COpaquePointer?>>? = null
    private var drawThemeBackgroundFn: CPointer<CFunction<(COpaquePointer?, HDC?, Int, Int, CPointer<RECT>, CPointer<RECT>?) -> HRESULT>>? = null
    private var closeThemeDataFn: CPointer<CFunction<(COpaquePointer?) -> HRESULT>>? = null

    private var cachedOsVersion: Triple<Int, Int, Int>? = null
    private var cachedVisualStyleGeneration: Win32VisualStyleGeneration? = null
    private var cachedDwmCompositionEnabled: Boolean? = null
    private var cachedUxThemeActive: Boolean? = null
    private var cachedSystemDarkTheme: Boolean? = null
    private var cachedColorPrevalence: Boolean? = null
    private var cachedAccentColorActiveResolved: Boolean = false
    private var cachedAccentColorActive: UInt? = null
    private var cachedAccentColorInactiveResolved: Boolean = false
    private var cachedAccentColorInactive: UInt? = null
    private var cachedHasInactiveAccentRegistry: Boolean? = null
    private val cachedCaptionButtonBoundsWidthByHwnd = HashMap<Long, Int>()
    private val cachedTitleBarPaletteByKey = HashMap<String, Win32TitleBarThemePalette>()
    private val lastBackdropAppliedByHwnd = HashMap<Long, Int>()
    private val cachedThemeHandles = HashMap<String, COpaquePointer>()

    private class UxThemeControlSprite(
        val width: Int,
        val height: Int,
        val premulRgbWords: IntArray,
        val invAlphaBytes: ByteArray,
        val isFullyOpaque: Boolean
    )

    private val cachedUxThemeControlSprites = LinkedHashMap<String, UxThemeControlSprite>()

    private fun ensureModulesLoaded() {
        if (modulesResolved) return
        modulesResolved = true
        val ntdll = GetModuleHandleW("ntdll.dll") ?: LoadLibraryW("ntdll.dll")
        val dwm = GetModuleHandleW("dwmapi.dll") ?: LoadLibraryW("dwmapi.dll")
        val ux = GetModuleHandleW("uxtheme.dll") ?: LoadLibraryW("uxtheme.dll")
        ntdllModule = ntdll
        dwmModule = dwm
        uxThemeModule = ux

        if (ntdll != null) {
            rtlGetVersionFn = GetProcAddress(ntdll, "RtlGetVersion")?.reinterpret()
        }
        if (dwm != null) {
            dwmIsCompositionEnabledFn = GetProcAddress(dwm, "DwmIsCompositionEnabled")?.reinterpret()
            dwmSetWindowAttributeFn = GetProcAddress(dwm, "DwmSetWindowAttribute")?.reinterpret()
            dwmGetWindowAttributeFn = GetProcAddress(dwm, "DwmGetWindowAttribute")?.reinterpret()
            dwmExtendFrameIntoClientAreaFn = GetProcAddress(dwm, "DwmExtendFrameIntoClientArea")?.reinterpret()
            dwmDefWindowProcFn = GetProcAddress(dwm, "DwmDefWindowProc")?.reinterpret()
            dwmGetColorizationColorFn = GetProcAddress(dwm, "DwmGetColorizationColor")?.reinterpret()
            dwmFlushFn = GetProcAddress(dwm, "DwmFlush")?.reinterpret()
            dwmGetCompositionTimingInfoFn = GetProcAddress(dwm, "DwmGetCompositionTimingInfo")?.reinterpret()
        }
        if (ux != null) {
            isAppThemedFn = GetProcAddress(ux, "IsAppThemed")?.reinterpret()
            isThemeActiveFn = GetProcAddress(ux, "IsThemeActive")?.reinterpret()
            setWindowThemeFn = GetProcAddress(ux, "SetWindowTheme")?.reinterpret()
            openThemeDataFn = GetProcAddress(ux, "OpenThemeData")?.reinterpret()
            drawThemeBackgroundFn = GetProcAddress(ux, "DrawThemeBackground")?.reinterpret()
            closeThemeDataFn = GetProcAddress(ux, "CloseThemeData")?.reinterpret()
        }
    }

    /**
     * Queries the true Windows OS `(major, minor, build)` version via `ntdll.dll!RtlGetVersion`.
     */
    fun queryOsVersionTriple(): Triple<Int, Int, Int> {
        cachedOsVersion?.let { return it }
        ensureModulesLoaded()
        val result = memScoped {
            val fn = rtlGetVersionFn
            if (fn != null) {
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
    fun isDwmCompositionEnabled(): Boolean {
        cachedDwmCompositionEnabled?.let { return it }
        val resolved = memScoped {
            ensureModulesLoaded()
            val fn = dwmIsCompositionEnabledFn ?: return@memScoped false
            val enabled = alloc<BOOLVar>()
            enabled.value = 0
            val hr = fn(enabled.ptr)
            hr >= 0 && enabled.value != 0
        }
        cachedDwmCompositionEnabled = resolved
        return resolved
    }

    /**
     * Returns `true` when `uxtheme.dll` visual styles are active (`IsThemeActive` & `IsAppThemed`)
     * and Windows High Contrast mode is not forcing classic framing.
     */
    fun isUxThemeActive(): Boolean {
        cachedUxThemeActive?.let { return it }
        ensureModulesLoaded()
        val fnTheme = isThemeActiveFn
        val fnApp = isAppThemedFn
        val resolved = fnTheme != null && fnApp != null && fnTheme() != 0 && fnApp() != 0
        cachedUxThemeActive = resolved
        return resolved
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
        val isDefaultQuery = forceMajor == null && forceMinor == null && forceBuild == null &&
            dwmEnabled == isDwmCompositionEnabled() && uxThemeActive == isUxThemeActive()
        if (isDefaultQuery) {
            cachedVisualStyleGeneration?.let { return it }
        }

        val (osMajor, osMinor, osBuild) = queryOsVersionTriple()
        val major = forceMajor ?: osMajor
        val minor = forceMinor ?: osMinor
        val build = forceBuild ?: osBuild

        val resolved = if (!dwmEnabled) {
            if (uxThemeActive) {
                Win32VisualStyleGeneration.UXTHEME_BASIC
            } else {
                Win32VisualStyleGeneration.WIN32_CLASSIC
            }
        } else {
            when {
                major > 10 || (major == 10 && build >= 22000) -> Win32VisualStyleGeneration.WIN11_MICA_FLUENT
                major == 10 -> Win32VisualStyleGeneration.WIN10_FLUENT
                major == 6 && minor >= 2 -> Win32VisualStyleGeneration.WIN8_AERO
                major == 6 && minor in 0..1 -> Win32VisualStyleGeneration.VISTA_7_AERO_GLASS
                uxThemeActive -> Win32VisualStyleGeneration.UXTHEME_BASIC
                else -> Win32VisualStyleGeneration.WIN32_CLASSIC
            }
        }

        if (isDefaultQuery) {
            cachedVisualStyleGeneration = resolved
        }
        return resolved
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
        val fn = dwmExtendFrameIntoClientAreaFn ?: return@memScoped false
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
        val fn = dwmDefWindowProcFn ?: return@memScoped null
        val lResult = alloc<LRESULTVar>()
        lResult.value = 0L
        val handled = fn(hwnd, msg, wParam, lParam, lResult.ptr)
        if (handled != 0) lResult.value else null
    }

    fun hasDwmFlush(): Boolean {
        ensureModulesLoaded()
        return dwmFlushFn != null
    }

    /**
     * Blocks until the next DWM vertical blank (`DwmFlush`), returning `E_FAIL` (`-2147467259`)
     * when `DwmFlush` is unavailable.
     */
    fun dwmFlush(): HRESULT {
        ensureModulesLoaded()
        val fn = dwmFlushFn ?: return -2147467259
        return fn()
    }

    /**
     * Queries `DwmGetCompositionTimingInfo` (`DWM_TIMING_INFO.rateRefresh`) in Hz.
     * Falls back to `hwnd = null` when a window-specific query returns failure (Windows 7).
     */
    fun queryDwmRefreshRateHz(hwnd: HWND? = null): Double? = memScoped {
        ensureModulesLoaded()
        val fn = dwmGetCompositionTimingInfoFn ?: return@memScoped null
        val timing = alloc<DWM_TIMING_INFO>()
        timing.cbSize = sizeOf<DWM_TIMING_INFO>().toUInt()
        var hr = fn(hwnd, timing.ptr)
        if (hr < 0 && hwnd != null) {
            timing.cbSize = sizeOf<DWM_TIMING_INFO>().toUInt()
            hr = fn(null, timing.ptr)
        }
        if (hr < 0) return@memScoped null
        val num = timing.rateRefresh.uiNumerator
        val den = timing.rateRefresh.uiDenominator
        if (den == 0u || num == 0u) return@memScoped null
        val hz = num.toDouble() / den.toDouble()
        if (hz.isFinite() && hz in 24.0..500.0) hz else null
    }

    /**
     * Queries `DWMWA_CAPTION_BUTTON_BOUNDS` (`dwAttribute = 5`) for the width in pixels reserved
     * on the right side of the titlebar by the native Minimize/Maximize/Close button cluster.
     */
    fun queryDwmCaptionButtonBoundsWidth(
        hwnd: HWND?,
        isToolWindow: Boolean = false,
        kind: Win32TitleBarKind = if (isToolWindow) Win32TitleBarKind.TOOL_WINDOW else Win32TitleBarKind.STANDARD_WINDOW
    ): Int {
        val cacheKey = if (hwnd != null) (hwnd.toLong() xor (kind.ordinal.toLong() shl 48)) else 0L
        if (hwnd != null) {
            cachedCaptionButtonBoundsWidthByHwnd[cacheKey]?.let { return it }
            ensureModulesLoaded()
            val fn = dwmGetWindowAttributeFn
            if (fn != null) {
                memScoped {
                    val rc = alloc<RECT>()
                    val hr = fn(hwnd, DWMWA_CAPTION_BUTTON_BOUNDS, rc.ptr, sizeOf<RECT>().toUInt())
                    val w = rc.right - rc.left
                    if (hr >= 0 && w in 20..320) {
                        cachedCaptionButtonBoundsWidthByHwnd[cacheKey] = w
                        return w
                    }
                }
            }
        }
        val fallback = kind.defaultCaptionButtonWidthPx * (if (kind.allowMinimizeMaximizeButtons) 3 else 1)
        if (hwnd != null) {
            cachedCaptionButtonBoundsWidthByHwnd[cacheKey] = fallback
        }
        return fallback
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
    ) {
        val colorPrevalence = isDwmColorPrevalenceEnabled()
        val hwndKey = hwnd.toLong()
        val newState = (extendTopPx and 0xFFFF) or
            (if (isDark) (1 shl 16) else 0) or
            (if (isToolWindow) (1 shl 17) else 0) or
            (kind.ordinal shl 18) or
            (if (colorPrevalence) (1 shl 22) else 0)
        if (lastBackdropAppliedByHwnd[hwndKey] == newState) {
            return
        }
        lastBackdropAppliedByHwnd[hwndKey] = newState

        memScoped {
            ensureModulesLoaded()
            val setAttr = dwmSetWindowAttributeFn
            if (setAttr != null) {
                val darkFlag = alloc<IntVar>()
                darkFlag.value = if (isDark) 1 else 0
                setAttr(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, darkFlag.ptr, sizeOf<IntVar>().toUInt())
                setAttr(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE_OLD, darkFlag.ptr, sizeOf<IntVar>().toUInt())

                val gen = detectVisualStyleGeneration()
                if (gen == Win32VisualStyleGeneration.WIN11_MICA_FLUENT) {
                    val cornerPref = alloc<IntVar>()
                    cornerPref.value = kind.win11CornerPreference
                    setAttr(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, cornerPref.ptr, sizeOf<IntVar>().toUInt())

                    val useBackdrop = extendTopPx > 0 && !colorPrevalence
                    val backdrop = alloc<IntVar>()
                    backdrop.value = if (useBackdrop) kind.win11BackdropType else DWMSBT_AUTO
                    setAttr(hwnd, DWMWA_SYSTEMBACKDROP_TYPE, backdrop.ptr, sizeOf<IntVar>().toUInt())
                }
            }
        }
        extendFrameIntoClientArea(hwnd, left = 0, right = 0, top = max(0, extendTopPx), bottom = 0)
    }

    fun applyWindowCornerPreference(hwnd: HWND, doNotRound: Boolean) = memScoped {
        ensureModulesLoaded()
        val setAttr = dwmSetWindowAttributeFn ?: return@memScoped
        val cornerPref = alloc<IntVar>()
        cornerPref.value = if (doNotRound) DWMWCP_DONOTROUND else DWMWCP_ROUND
        setAttr(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, cornerPref.ptr, sizeOf<IntVar>().toUInt())
    }

    fun clearCachedHwndState(hwnd: HWND?) {
        cachedVisualStyleGeneration = null
        cachedDwmCompositionEnabled = null
        cachedUxThemeActive = null
        cachedSystemDarkTheme = null
        cachedColorPrevalence = null
        cachedAccentColorActiveResolved = false
        cachedAccentColorActive = null
        cachedAccentColorInactiveResolved = false
        cachedAccentColorInactive = null
        cachedHasInactiveAccentRegistry = null
        cachedTitleBarPaletteByKey.clear()
        cachedUxThemeControlSprites.clear()

        val closeFn = closeThemeDataFn
        if (closeFn != null) {
            for (handle in cachedThemeHandles.values) {
                closeFn(handle)
            }
        }
        cachedThemeHandles.clear()

        if (hwnd == null) {
            lastBackdropAppliedByHwnd.clear()
            cachedCaptionButtonBoundsWidthByHwnd.clear()
        } else {
            val hLong = hwnd.toLong()
            lastBackdropAppliedByHwnd.remove(hLong)
            val toRemove = cachedCaptionButtonBoundsWidthByHwnd.keys.filter { (it and 0xFFFFFFFFFFFFL) == (hLong and 0xFFFFFFFFFFFFL) }
            for (k in toRemove) {
                cachedCaptionButtonBoundsWidthByHwnd.remove(k)
            }
        }
    }

    /**
     * Queries whether Windows is currently using Dark Mode (`AppsUseLightTheme == 0`).
     */
    fun isSystemDarkTheme(): Boolean {
        cachedSystemDarkTheme?.let { return it }
        val resolved = run {
            val appsLight = readHkcuDword(
                "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                "AppsUseLightTheme"
            )
            if (appsLight != null) {
                return@run appsLight == 0u
            }
            val sysLight = readHkcuDword(
                "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                "SystemUsesLightTheme"
            )
            if (sysLight != null) {
                return@run sysLight == 0u
            }
            false
        }
        cachedSystemDarkTheme = resolved
        return resolved
    }

    /**
     * Returns `true` when the user has enabled *"Show accent color on title bars and window borders"*
     * in Windows Personalization settings (`HKCU\Software\Microsoft\Windows\DWM\ColorPrevalence == 1`).
     */
    fun isDwmColorPrevalenceEnabled(): Boolean {
        cachedColorPrevalence?.let { return it }
        val cp = readHkcuDword("Software\\Microsoft\\Windows\\DWM", "ColorPrevalence")
        val resolved = cp != null && cp != 0u
        cachedColorPrevalence = resolved
        return resolved
    }

    /**
     * Queries the user's Windows DWM titlebar accent color (`COLORREF` in `0x00BBGGRR` format),
     * reading `AccentColor` (or `AccentColorInactive` when `[active] == false`) on Windows 10/11
     * and `ColorizationColor` / `DwmGetColorizationColor` on Windows Vista/7/8.
     */
    fun queryDwmAccentColor(active: Boolean = true): UInt? {
        if (active) {
            if (cachedAccentColorActiveResolved) return cachedAccentColorActive
        } else {
            if (cachedAccentColorInactiveResolved) return cachedAccentColorInactive
        }

        val resolved: UInt? = run {
            if (!active) {
                val inactiveAbgr = readHkcuDword("Software\\Microsoft\\Windows\\DWM", "AccentColorInactive")
                cachedHasInactiveAccentRegistry = inactiveAbgr != null
                if (inactiveAbgr != null && inactiveAbgr != 0u) {
                    return@run abgrToColorRef(inactiveAbgr)
                }
            }
            val accentAbgr = readHkcuDword("Software\\Microsoft\\Windows\\DWM", "AccentColor")
            if (accentAbgr != null && accentAbgr != 0u) {
                return@run abgrToColorRef(accentAbgr)
            }
            val colorizationArgb = readHkcuDword("Software\\Microsoft\\Windows\\DWM", "ColorizationColor")
                ?: queryDwmApiColorizationArgb()
            if (colorizationArgb != null && colorizationArgb != 0u) {
                return@run argbToColorRef(colorizationArgb)
            }
            null
        }

        if (active) {
            cachedAccentColorActive = resolved
            cachedAccentColorActiveResolved = true
        } else {
            cachedAccentColorInactive = resolved
            cachedAccentColorInactiveResolved = true
        }
        return resolved
    }

    private fun queryDwmApiColorizationArgb(): UInt? = memScoped {
        ensureModulesLoaded()
        val fn = dwmGetColorizationColorFn ?: return@memScoped null
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
        val paletteKey = "${hwnd?.toLong() ?: 0L}|$isWindowActive|$isToolWindow|${kind.ordinal}|${generation.ordinal}|$isDark|$colorPrevalence|${accentColorRef ?: 0u}|$nativeBtnClusterW"
        cachedTitleBarPaletteByKey[paletteKey]?.let { return it }

        val singleBtnW = kind.defaultCaptionButtonWidthPx
        val allowDwmButtons = kind == Win32TitleBarKind.STANDARD_WINDOW && !isToolWindow

        val resolved = when (generation) {
            Win32VisualStyleGeneration.VISTA_7_AERO_GLASS -> {
                val glassTint = accentColorRef ?: colorRefRgb(116, 184, 252)
                val activeText = colorRefRgb(0, 0, 0)
                val inactiveText = colorRefRgb(68, 68, 68)
                Win32TitleBarThemePalette(
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
                Win32TitleBarThemePalette(
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
                    (cachedHasInactiveAccentRegistry ?: (readHkcuDword("Software\\Microsoft\\Windows\\DWM", "AccentColorInactive") != null).also {
                        cachedHasInactiveAccentRegistry = it
                    })
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

                Win32TitleBarThemePalette(
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
                Win32TitleBarThemePalette(
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
                Win32TitleBarThemePalette(
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
        cachedTitleBarPaletteByKey[paletteKey] = resolved
        return resolved
    }

    private fun obtainThemeHandle(hwnd: HWND?, classList: String): COpaquePointer? = memScoped {
        val key = "${hwnd?.toLong() ?: 0L}|$classList"
        cachedThemeHandles[key]?.let { return@memScoped it }
        ensureModulesLoaded()
        val openTheme = openThemeDataFn ?: return@memScoped null
        val primaryTheme = openTheme(hwnd, classList.wcstr.ptr)
        val hTheme = primaryTheme ?: if (classList.contains("::")) {
            val baseClass = classList.substringAfterLast("::")
            openTheme(hwnd, baseClass.wcstr.ptr)
        } else {
            null
        } ?: return@memScoped null
        cachedThemeHandles[key] = hTheme
        hTheme
    }

    /** Returns true when `uxtheme.dll` resolves a visual style handle for [classList]. */
    fun isThemeDataAvailable(hwnd: HWND?, classList: String): Boolean =
        obtainThemeHandle(hwnd, classList) != null

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
        val drawThemeBg = drawThemeBackgroundFn ?: return@memScoped false
        val hTheme = obtainThemeHandle(hwnd, "WINDOW") ?: return@memScoped false
        val rc = alloc<RECT>()
        rc.left = 0
        rc.top = 0
        rc.right = width
        rc.bottom = height
        val partId = kind.uxThemeCaptionPartId
        val stateId = if (isWindowActive) CS_ACTIVE else CS_INACTIVE
        drawThemeBg(hTheme, hdc, partId, stateId, rc.ptr, null) >= 0
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
        val drawThemeBg = drawThemeBackgroundFn ?: return@memScoped false
        val hTheme = obtainThemeHandle(hwnd, "WINDOW") ?: return@memScoped false
        val rc = alloc<RECT>()
        rc.left = left
        rc.top = top
        rc.right = right
        rc.bottom = bottom
        drawThemeBg(hTheme, hdc, partId, stateId, rc.ptr, null) >= 0
    }

    /**
     * Applies `"DarkMode_Explorer"` or `"Explorer"` visual style theme to a control or window [hwnd]
     * via `uxtheme.dll!SetWindowTheme` and updates `AllowDarkModeForWindow`.
     */
    fun setControlWindowTheme(
        hwnd: HWND?,
        isDark: Boolean,
        subAppOverride: String? = null
    ): Boolean = memScoped {
        if (hwnd == null) return@memScoped false
        ensureModulesLoaded()
        Win32WinMain.allowDarkModeForWindow(hwnd, isDark)
        val setWindowTheme = setWindowThemeFn ?: return@memScoped false
        val subAppName = subAppOverride ?: if (isDark) "DarkMode_Explorer" else "Explorer"
        setWindowTheme(hwnd, subAppName.wcstr.ptr, null) >= 0
    }

    private fun renderUxThemeControlSprite(
        hTheme: COpaquePointer,
        partId: Int,
        stateId: Int,
        w: Int,
        h: Int
    ): UxThemeControlSprite? = memScoped {
        val drawThemeBg = drawThemeBackgroundFn ?: return@memScoped null
        val dibDc = CreateCompatibleDC(null) ?: return@memScoped null
        val bmi = alloc<BITMAPINFO>()
        bmi.bmiHeader.biSize = sizeOf<BITMAPINFOHEADER>().toUInt()
        bmi.bmiHeader.biWidth = w
        bmi.bmiHeader.biHeight = -h
        bmi.bmiHeader.biPlanes = 1u
        bmi.bmiHeader.biBitCount = 32u
        bmi.bmiHeader.biCompression = BI_RGB.toUInt()

        val bitsPtrVar = alloc<COpaquePointerVar>()
        val dibBmp = CreateDIBSection(dibDc, bmi.ptr, DIB_RGB_COLORS.toUInt(), bitsPtrVar.ptr, null, 0u)
        val pixelsPtr = bitsPtrVar.value?.reinterpret<UIntVar>()
        if (dibBmp == null || pixelsPtr == null) {
            if (dibBmp != null) DeleteObject(dibBmp)
            DeleteDC(dibDc)
            return@memScoped null
        }

        val oldBmp = SelectObject(dibDc, dibBmp)
        val count = w * h
        val rc = alloc<RECT>()
        rc.left = 0
        rc.top = 0
        rc.right = w
        rc.bottom = h

        // Pass 1: Render against black (0x000000)
        for (i in 0 until count) {
            pixelsPtr[i] = 0x00000000u
        }
        val hr1 = drawThemeBg(hTheme, dibDc, partId, stateId, rc.ptr, null)
        if (hr1 < 0) {
            SelectObject(dibDc, oldBmp)
            DeleteObject(dibBmp)
            DeleteDC(dibDc)
            return@memScoped null
        }
        GdiFlush()
        val blackPixels = IntArray(count)
        for (i in 0 until count) {
            blackPixels[i] = pixelsPtr[i].toInt()
        }

        // Pass 2: Render against white (0xFFFFFF) to extract exact per-pixel coverage alpha
        for (i in 0 until count) {
            pixelsPtr[i] = 0x00FFFFFFu
        }
        drawThemeBg(hTheme, dibDc, partId, stateId, rc.ptr, null)
        GdiFlush()

        val premulWords = IntArray(count)
        val invAlphaBytes = ByteArray(count)
        var allOpaque = true

        for (i in 0 until count) {
            val pBlack = blackPixels[i]
            val pWhite = pixelsPtr[i].toInt()
            val bBlack = pBlack and 0xFF
            val gBlack = (pBlack ushr 8) and 0xFF
            val rBlack = (pBlack ushr 16) and 0xFF
            val bWhite = pWhite and 0xFF
            val gWhite = (pWhite ushr 8) and 0xFF
            val rWhite = (pWhite ushr 16) and 0xFF

            val invAlpha = (((bWhite - bBlack) + (gWhite - gBlack) + (rWhite - rBlack)) / 3).coerceIn(0, 255)
            if (invAlpha != 0) {
                allOpaque = false
            }
            premulWords[i] = (0xFF shl 24) or (rBlack shl 16) or (gBlack shl 8) or bBlack
            invAlphaBytes[i] = invAlpha.toByte()
        }

        SelectObject(dibDc, oldBmp)
        DeleteObject(dibBmp)
        DeleteDC(dibDc)

        UxThemeControlSprite(
            width = w,
            height = h,
            premulRgbWords = premulWords,
            invAlphaBytes = invAlphaBytes,
            isFullyOpaque = allOpaque
        )
    }

    /**
     * Draws a native UxTheme control part & state (such as `Explorer::BUTTON` / `DarkMode_Explorer::BUTTON`
     * with [BP_PUSHBUTTON] and [PBS_NORMAL]..[PBS_DEFAULTED]) via cached pre-rendered 32-bit sprites
     * composited directly into the active 32-bit backbuffer (with fallback to `DrawThemeBackground`).
     */
    fun drawUxThemeControlBackground(
        hwnd: HWND?,
        hdc: HDC?,
        classList: String,
        partId: Int = BP_PUSHBUTTON,
        stateId: Int = PBS_NORMAL,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Boolean {
        if (hdc == null) return false
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return false
        ensureModulesLoaded()

        return memScoped {
            val drawThemeBg = drawThemeBackgroundFn ?: return@memScoped false
            val hTheme = obtainThemeHandle(hwnd, classList) ?: return@memScoped false
            val rc = alloc<RECT>()
            rc.left = left
            rc.top = top
            rc.right = right
            rc.bottom = bottom
            drawThemeBg(hTheme, hdc, partId, stateId, rc.ptr, null) >= 0
        }
    }

    /**
     * Builds the `uxtheme.dll` class list string for the requested [baseClass] (e.g. `"BUTTON"`,
     * `"EDIT"`, `"COMBOBOX"`, `"PROGRESS"`, `"TRACKBAR"`, `"TAB"`, `"HEADER"`, `"SPIN"`,
     * `"TOOLBAR"`, `"STATUS"`, `"SCROLLBAR"`, `"TREEVIEW"`) respecting [darkMode].
     */
    fun resolveExplorerClassList(baseClass: String, darkMode: Boolean): String =
        if (darkMode) {
            "DarkMode_Explorer::$baseClass;Explorer::$baseClass;$baseClass"
        } else {
            "Explorer::$baseClass;$baseClass"
        }

    /**
     * Renders an empty-text native `uxtheme.dll` control part (`BUTTON`, `EDIT`, `COMBOBOX`,
     * `PROGRESS`, `TRACKBAR`, `TAB`, `HEADER`, `SPIN`, `TOOLBAR`, `STATUS`, etc.) into a newly
     * allocated 32-bpp straight-ARGB pixel buffer (`IntArray(width * height)`).
     */
    fun captureEmptyTextControlArgbBuffer(
        hwnd: HWND?,
        classList: String,
        partId: Int,
        stateId: Int,
        width: Int,
        height: Int
    ): IntArray? {
        if (width <= 0 || height <= 0) return null
        ensureModulesLoaded()
        val hTheme = obtainThemeHandle(hwnd, classList) ?: return null
        val sprite = obtainOrRenderBaseControlSprite(classList, hTheme, partId, stateId, width, height) ?: return null
        return unpackSpriteToStraightArgb(sprite)
    }

    private fun obtainOrRenderBaseControlSprite(
        classList: String,
        hTheme: COpaquePointer,
        partId: Int,
        stateId: Int,
        w: Int,
        h: Int
    ): UxThemeControlSprite? {
        val baseKey = "$classList|$partId|$stateId|$w|$h"
        cachedUxThemeControlSprites[baseKey]?.let { return it }
        val rendered = renderUxThemeControlSprite(hTheme, partId, stateId, w, h) ?: return null
        if (w <= 800 && h <= 300) {
            if (cachedUxThemeControlSprites.size >= 384) {
                val eldest = cachedUxThemeControlSprites.keys.firstOrNull()
                if (eldest != null) cachedUxThemeControlSprites.remove(eldest)
            }
            cachedUxThemeControlSprites[baseKey] = rendered
        }
        return rendered
    }

    private fun unpackSpriteToStraightArgb(sprite: UxThemeControlSprite): IntArray {
        val count = sprite.width * sprite.height
        val out = IntArray(count)
        val premulWords = sprite.premulRgbWords
        val invAlphaBytes = sprite.invAlphaBytes
        for (i in 0 until count) {
            val invA = invAlphaBytes[i].toInt() and 0xFF
            val alpha = (255 - invA).coerceIn(0, 255)
            if (alpha <= 0) {
                out[i] = 0
                continue
            }
            val premul = premulWords[i]
            val rPremul = (premul ushr 16) and 0xFF
            val gPremul = (premul ushr 8) and 0xFF
            val bPremul = premul and 0xFF
            val r = if (alpha == 255) rPremul else ((rPremul * 255 + alpha / 2) / alpha).coerceIn(0, 255)
            val g = if (alpha == 255) gPremul else ((gPremul * 255 + alpha / 2) / alpha).coerceIn(0, 255)
            val b = if (alpha == 255) bPremul else ((bPremul * 255 + alpha / 2) / alpha).coerceIn(0, 255)
            out[i] = (alpha shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }
}
