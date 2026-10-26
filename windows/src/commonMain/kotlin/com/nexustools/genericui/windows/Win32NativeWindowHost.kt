@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.dialog.DesktopDialogWindowNativeImpl
import com.nexustools.genericui.desktop.dock.DesktopDockPanelNativeImpl
import com.nexustools.genericui.desktop.dock.DesktopDockWorkspaceNativeImpl
import com.nexustools.genericui.desktop.dock.DesktopToolPaletteNativeImpl
import com.nexustools.genericui.desktop.lazy.DesktopLazyGridNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopMenuItemNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopMenuNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopButtonNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopCheckboxNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopProgressBarNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopRangeSliderNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopScrollViewNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopSelectNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTabContainerNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTextInputNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTextNativeImpl
import com.nexustools.genericui.event.KeyEvent
import com.nexustools.genericui.event.PointerEvent
import com.nexustools.genericui.event.WheelEvent
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.res.Resources
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.gradient.Gradient
import com.nexustools.genericui.window.TitleBar
import kotlinx.cinterop.*
import platform.windows.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Native Win32 (`user32.dll` / `gdi32.dll` / `comdlg32.dll` / `dwmapi.dll`) window host and
 * monitor-refresh-rate VBlank compositor for `:desktop:windows` on `mingwX64`.
 */
object Win32NativeWindowHost {

    var activeWindowImpl: WindowsWindowNativeImpl? = null
        private set

    var activeHwnd: HWND? = null
        private set

    val panelHwnds: MutableMap<DesktopDockPanelNativeImpl<*>, HWND> = mutableMapOf()
    private val hwndToPanel: MutableMap<Long, DesktopDockPanelNativeImpl<*>> = mutableMapOf()

    fun isPanelToolWindow(hwnd: HWND?): Boolean =
        hwnd != null && (
            hwndToPanel.containsKey(hwnd.toLong()) ||
                (hwnd == activeHwnd && activeWindowImpl?.isToolModalWindow == true)
            )

    fun hasCustomMainWindowTitleBar(): Boolean {
        val winImpl = activeWindowImpl ?: return false
        if (winImpl.isToolModalWindow) return true
        val tb = winImpl.getTitleBarInstructions() ?: return false
        return tb.customTitleBar ||
            tb.leadingComponents.isNotEmpty() ||
            tb.centerComponents.isNotEmpty() ||
            tb.trailingComponents.isNotEmpty()
    }

    fun getMainWindowTitleBarHeight(): Int {
        val winImpl = activeWindowImpl ?: return 0
        if (!hasCustomMainWindowTitleBar()) return 0
        val tb = winImpl.getTitleBarInstructions()
        val kind = winImpl.titleBarKind
        return Win32NativeTitleBarRenderer.getDefaultTitleBarHeightPx(
            isToolWindow = kind.usesSmallCaptionMetrics,
            titleBar = tb,
            kind = kind
        )
    }

    fun getPanelWindowTitleBarHeight(panel: DesktopDockPanelNativeImpl<*>? = null): Int =
        Win32NativeTitleBarRenderer.getDefaultTitleBarHeightPx(
            isToolWindow = true,
            titleBar = panel?.getTitleBarInstructions(),
            kind = Win32TitleBarKind.TOOL_WINDOW
        )

    fun hitTestMainWindow(hwnd: HWND, screenX: Int, screenY: Int): LRESULT =
        Win32NativeTitleBarRenderer.hitTestWindow(
            hwnd = hwnd,
            screenX = screenX,
            screenY = screenY,
            isToolWindow = activeWindowImpl?.isToolModalWindow == true,
            hasInteractiveClientRegionAt = { cx, cy ->
                val regions = hitRegionsByHwnd[hwnd.toLong()].orEmpty()
                regions.any { it.widget != null && it.contains(cx, cy) }
            }
        )

    fun hitTestPanelWindow(hwnd: HWND, screenX: Int, screenY: Int): LRESULT =
        Win32NativeTitleBarRenderer.hitTestWindow(
            hwnd = hwnd,
            screenX = screenX,
            screenY = screenY,
            isToolWindow = true,
            hasInteractiveClientRegionAt = { cx, cy ->
                val regions = hitRegionsByHwnd[hwnd.toLong()].orEmpty()
                regions.any { it.widget != null && it.contains(cx, cy) }
            }
        )

    private var lastMultiWindowVisibilityState: Boolean? = null

    var monitorRefreshRateHz: Int = 60
        private set

    var appIconBig: HICON? = null
        private set

    var appIconSmall: HICON? = null
        private set

    private var windowClassRegistered = false
    private val menuCommandHandlers = mutableMapOf<Int, () -> Unit>()
    private val shortcutCommandHandlers = mutableMapOf<String, () -> Unit>()
    private var nextMenuCommandId = 1001

    private class HitRegion(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val tooltip: String? = null,
        val widget: DesktopNativeWidget? = null,
        val isTextInput: Boolean = false,
        val onClick: ((mouseX: Int, mouseY: Int) -> Unit)? = null,
        val onDrag: ((mouseX: Int, mouseY: Int) -> Unit)? = null
    ) {
        fun contains(x: Int, y: Int): Boolean = x in left..right && y in top..bottom
    }

    private class SystemThemePalette(
        val isDark: Boolean,
        val bgBase: UInt,
        val bgPanel: UInt,
        val bgCard: UInt,
        val bgElevated: UInt,
        val borderSubtle: UInt,
        val borderDash: UInt,
        val textPrimary: UInt,
        val textSecondary: UInt,
        val textMuted: UInt,
        val accentBlue: UInt,
        val accentBlueHover: UInt,
        val accentBlueSoftBg: UInt,
        val accentBlueBorder: UInt,
        val accentBlueText: UInt,
        val accentGreen: UInt,
        val accentGreenSoftBg: UInt,
        val accentGreenBorder: UInt,
        val accentAmber: UInt,
        val accentAmberSoftBg: UInt,
        val accentRed: UInt,
        val accentRedSoftBg: UInt,
        val accentRedBorder: UInt
    ) {
        val borderStrong: UInt get() = accentBlueBorder
        val dangerBg: UInt get() = accentRedSoftBg
        val dangerBorder: UInt get() = accentRedBorder
        val dangerText: UInt get() = accentRed
        val successText: UInt get() = accentGreen
        val warningText: UInt get() = accentAmber
    }

    private val darkSystemPalette = SystemThemePalette(
        isDark = true,
        bgBase = rgb(32, 32, 32),
        bgPanel = rgb(43, 43, 43),
        bgCard = rgb(52, 52, 54),
        bgElevated = rgb(62, 62, 66),
        borderSubtle = rgb(74, 74, 78),
        borderDash = rgb(86, 86, 90),
        textPrimary = rgb(245, 245, 245),
        textSecondary = rgb(196, 196, 196),
        textMuted = rgb(154, 154, 154),
        accentBlue = rgb(0, 120, 212),
        accentBlueHover = rgb(24, 138, 232),
        accentBlueSoftBg = rgb(50, 50, 50),
        accentBlueBorder = rgb(0, 120, 212),
        accentBlueText = rgb(96, 205, 255),
        accentGreen = rgb(108, 203, 95),
        accentGreenSoftBg = rgb(50, 50, 50),
        accentGreenBorder = rgb(80, 140, 80),
        accentAmber = rgb(252, 225, 0),
        accentAmberSoftBg = rgb(50, 50, 50),
        accentRed = rgb(255, 153, 164),
        accentRedSoftBg = rgb(50, 50, 50),
        accentRedBorder = rgb(160, 70, 70)
    )

    private val lightSystemPalette = SystemThemePalette(
        isDark = false,
        bgBase = rgb(240, 240, 240),
        bgPanel = rgb(255, 255, 255),
        bgCard = rgb(250, 250, 252),
        bgElevated = rgb(229, 229, 234),
        borderSubtle = rgb(198, 198, 204),
        borderDash = rgb(180, 180, 180),
        textPrimary = rgb(0, 0, 0),
        textSecondary = rgb(68, 68, 68),
        textMuted = rgb(109, 109, 109),
        accentBlue = rgb(0, 120, 212),
        accentBlueHover = rgb(0, 98, 184),
        accentBlueSoftBg = rgb(240, 240, 240),
        accentBlueBorder = rgb(0, 120, 212),
        accentBlueText = rgb(0, 102, 204),
        accentGreen = rgb(16, 124, 16),
        accentGreenSoftBg = rgb(240, 240, 240),
        accentGreenBorder = rgb(16, 124, 16),
        accentAmber = rgb(157, 93, 0),
        accentAmberSoftBg = rgb(240, 240, 240),
        accentRed = rgb(196, 43, 28),
        accentRedSoftBg = rgb(240, 240, 240),
        accentRedBorder = rgb(196, 43, 28)
    )

    private val hitRegionsByHwnd = mutableMapOf<Long, MutableList<HitRegion>>()
    private var currentPaintHwndKey: Long = 0L
    private val hitRegions: MutableList<HitRegion>
        get() = hitRegionsByHwnd.getOrPut(currentPaintHwndKey) { mutableListOf() }

    private var activeDragRegion: HitRegion? = null
    private var hoveredHitRegion: HitRegion? = null
    private var hoveredWidget: DesktopNativeWidget? = null
    private var pressedWidget: DesktopNativeWidget? = null
    private var focusedWidget: DesktopNativeWidget? = null
    private var focusedTextInput: DesktopTextInputNativeImpl<*>? = null
    private var activeLazyGridImpl: DesktopLazyGridNativeImpl<*>? = null
    private var hoveredTooltip: String? = null
    private var hoverMouseX: Int = 0
    private var hoverMouseY: Int = 0
    private var hoverHwndKey: Long = 0L
    private var canvasViewportRect: IntArray = intArrayOf(-1, -1, -1, -1)
    private var activeCanvasImpl: DesktopBaseNativeImpl<*>? = null
    private var activeCanvasSurfaceSize: Pair<Int, Int> = 320 to 240
    private var isDraggingCanvas: Boolean = false
    private val WHITESPACE_REGEX = Regex("\\s+")

    private var cachedSystemDarkTheme: Boolean? = null
    private var cachedFontSmall: HFONT? = null
    private var cachedFontSmallBold: HFONT? = null
    private var cachedFontNormal: HFONT? = null
    private var cachedFontBold: HFONT? = null
    private var cachedFontTitle: HFONT? = null
    private var cachedFontMetricBig: HFONT? = null

    private var cachedMainMemDc: HDC? = null
    private var cachedMainMemBmp: HBITMAP? = null
    private var cachedMainOldBmp: HGDIOBJ? = null
    private var cachedMainBufferW: Int = 0
    private var cachedMainBufferH: Int = 0
    private var cachedMainBufferHwndKey: Long = 0L

    fun invalidateSystemThemeCache() {
        cachedSystemDarkTheme = null
    }

    fun queryMonitorRefreshRateHz(): Int = memScoped {
        val devMode = alloc<DEVMODEW>()
        devMode.dmSize = sizeOf<DEVMODEW>().toUShort()
        val ok = EnumDisplaySettingsW(null, ENUM_CURRENT_SETTINGS, devMode.ptr)
        val freq = if (ok != 0) devMode.dmDisplayFrequency.toInt() else 60
        if (freq in 30..500) freq else 60
    }

    fun isWindowsSystemDarkTheme(): Boolean {
        cachedSystemDarkTheme?.let { return it }
        val resolved = memScoped {
            val hKey = alloc<HKEYVar>()
            val openRes = RegOpenKeyExW(
                HKEY_CURRENT_USER,
                "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                0u,
                KEY_READ.toUInt(),
                hKey.ptr
            )
            if (openRes != ERROR_SUCCESS) {
                return@memScoped true
            }
            val data = alloc<DWORDVar>()
            val dataSize = alloc<DWORDVar>()
            dataSize.value = sizeOf<DWORDVar>().toUInt()
            val queryRes = RegQueryValueExW(
                hKey.value,
                "AppsUseLightTheme",
                null,
                null,
                data.ptr.reinterpret(),
                dataSize.ptr
            )
            RegCloseKey(hKey.value)
            if (queryRes == ERROR_SUCCESS) {
                data.value == 0u
            } else {
                true
            }
        }
        cachedSystemDarkTheme = resolved
        return resolved
    }

    private fun resolveAppIcons(hInstance: HINSTANCE?) = memScoped {
        if (appIconBig != null && appIconSmall != null) return@memScoped

        // 1. Try embedded PE icon resource ID 1 (compiled via windres.exe)
        val resName = (1L).toCPointer<UShortVar>()
        var big: HICON? = LoadImageW(
            hInstance,
            resName,
            IMAGE_ICON.toUInt(),
            32,
            32,
            0u
        )?.reinterpret()
        var small: HICON? = LoadImageW(
            hInstance,
            resName,
            IMAGE_ICON.toUInt(),
            16,
            16,
            0u
        )?.reinterpret()

        // 2. Fallback to icon.ico on disk (next to executable or in working directory)
        if (big == null || small == null) {
            val exePathBuf = allocArray<WCHARVar>(MAX_PATH)
            GetModuleFileNameW(null, exePathBuf, MAX_PATH.toUInt())
            val exePath = exePathBuf.toKString()
            val exeDir = exePath.substringBeforeLast('\\', "")
            val candidatePaths = buildList {
                if (exeDir.isNotEmpty()) {
                    add("$exeDir\\icon.ico")
                    add("$exeDir\\..\\..\\..\\..\\icon.ico")
                }
                add("icon.ico")
            }
            for (path in candidatePaths) {
                if (big == null) {
                    big = LoadImageW(
                        null,
                        path.wcstr.ptr,
                        IMAGE_ICON.toUInt(),
                        32,
                        32,
                        LR_LOADFROMFILE.toUInt()
                    )?.reinterpret()
                }
                if (small == null) {
                    small = LoadImageW(
                        null,
                        path.wcstr.ptr,
                        IMAGE_ICON.toUInt(),
                        16,
                        16,
                        LR_LOADFROMFILE.toUInt()
                    )?.reinterpret()
                }
                if (big != null && small != null) break
            }
        }

        if (big == null) {
            big = LoadIconW(null, IDI_APPLICATION)
        }
        if (small == null) {
            small = big
        }
        appIconBig = big
        appIconSmall = small
    }

    private fun applyWindowsImmersiveTitleBarTheme(hwnd: HWND, dark: Boolean) = memScoped {
        val dwmModule = LoadLibraryW("dwmapi.dll") ?: return@memScoped
        val proc = GetProcAddress(dwmModule, "DwmSetWindowAttribute") ?: return@memScoped
        val fn = proc.reinterpret<CFunction<(HWND?, DWORD, CValuesRef<*>?, DWORD) -> HRESULT>>()
        val flag = alloc<IntVar>()
        flag.value = if (dark) 1 else 0
        // 20 = DWMWA_USE_IMMERSIVE_DARK_MODE (Windows 11 / Win10 20H1+), 19 = older Win10
        fn(hwnd, 20u, flag.ptr, sizeOf<IntVar>().toUInt())
        fn(hwnd, 19u, flag.ptr, sizeOf<IntVar>().toUInt())
    }

    private fun isStudioMultiWindowMode(rootWidget: DesktopNativeWidget?): Boolean {
        if (rootWidget == null) return false
        val impl = rootWidget.attributes["nativeImpl"]
        if (impl is DesktopDockWorkspaceNativeImpl<*>) {
            val mode = impl.getWindowMode().uppercase()
            return mode.contains("MULTI")
        }
        val modeAttr = (rootWidget.attributes["windowMode"] as? String)?.uppercase()
        if (modeAttr != null) {
            return modeAttr.contains("MULTI")
        }
        for (child in rootWidget.children) {
            if (isStudioMultiWindowMode(child)) return true
        }
        return false
    }

    private fun createAuxiliaryDockWindow(
        hInstance: HINSTANCE?,
        className: String,
        title: String,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        ownerHwnd: HWND?
    ): HWND? {
        val hwnd = CreateWindowExW(
            dwExStyle = WS_EX_TOOLWINDOW.toUInt(),
            lpClassName = className,
            lpWindowName = title,
            dwStyle = WS_POPUP or WS_CAPTION.toUInt() or WS_SYSMENU.toUInt() or WS_THICKFRAME.toUInt() or WS_CLIPCHILDREN.toUInt(),
            X = x,
            Y = y,
            nWidth = width,
            nHeight = height,
            hWndParent = ownerHwnd,
            hMenu = null,
            hInstance = hInstance,
            lpParam = null
        )
        if (hwnd != null) {
            val isDark = isWindowsSystemDarkTheme()
            applyWindowsImmersiveTitleBarTheme(hwnd, isDark)
            Win32DwmUxThemeBridge.applyWindowDwmAttributes(
                hwnd = hwnd,
                isToolWindow = true,
                extendTopPx = getPanelWindowTitleBarHeight(null),
                isDark = isDark,
                kind = Win32TitleBarKind.TOOL_WINDOW
            )
            appIconBig?.let { icon ->
                SendMessageW(hwnd, WM_SETICON.toUInt(), ICON_BIG.toULong(), icon.toLong())
            }
            appIconSmall?.let { icon ->
                SendMessageW(hwnd, WM_SETICON.toUInt(), ICON_SMALL.toULong(), icon.toLong())
            }
        }
        return hwnd
    }

    private fun isToolboxPanel(panel: DesktopDockPanelNativeImpl<*>): Boolean =
        panel.getTitle().contains("Toolbox", ignoreCase = true) ||
            panel.getTitle().contains("Tool Options", ignoreCase = true)

    private fun isTimelinePanel(panel: DesktopDockPanelNativeImpl<*>): Boolean =
        panel.getTitle().contains("Timeline", ignoreCase = true)

    fun syncMultiWindowDocks() {
        val mainHwnd = activeHwnd ?: return
        val rootWidget = activeWindowImpl?.nativeWidget
        val toolPalettes = mutableListOf<DesktopToolPaletteNativeImpl<*>>()
        val dockPanels = mutableListOf<DesktopDockPanelNativeImpl<*>>()
        val canvases = mutableListOf<DesktopNativeWidget>()
        collectStudioWidgets(rootWidget, toolPalettes, dockPanels, canvases)
        val isStudioApp = toolPalettes.isNotEmpty() || dockPanels.isNotEmpty()
        if (!isStudioApp) return

        val multiWindow = isStudioMultiWindowMode(rootWidget)
        if (lastMultiWindowVisibilityState == multiWindow && (!multiWindow || panelHwnds.size == dockPanels.size)) return
        lastMultiWindowVisibilityState = multiWindow

        val hInstance = Win32WinMain.hInstance ?: GetModuleHandleW(null)
        val className = "GenericUIWin32HostWindowW"

        if (multiWindow) {
            var rightIndex = 0
            for (panel in dockPanels) {
                if (panelHwnds[panel] == null) {
                    val (px, py, pw, ph) = when {
                        isToolboxPanel(panel) -> intArrayOf(16, 40, 248, 820)
                        isTimelinePanel(panel) -> intArrayOf(272, 24, 760, 168)
                        else -> {
                            val idx = rightIndex++
                            val col = idx % 2
                            val row = idx / 2
                            val wx = if (col == 0) 1040 else 1476
                            val wy = if (col == 0) 532 + row * 220 else 24 + row * 250
                            val ww = 428
                            val wh = if (col == 0) 212 else 242
                            intArrayOf(wx, wy, ww, wh)
                        }
                    }
                    val created = createAuxiliaryDockWindow(
                        hInstance = hInstance,
                        className = className,
                        title = panel.getTitle().ifBlank { "Panel" },
                        x = px,
                        y = py,
                        width = pw,
                        height = ph,
                        ownerHwnd = mainHwnd
                    )
                    if (created != null) {
                        panelHwnds[panel] = created
                        hwndToPanel[created.toLong()] = panel
                        SetWindowPos(
                            created,
                            HWND_TOP,
                            px,
                            py,
                            pw,
                            ph,
                            (SWP_NOACTIVATE or SWP_FRAMECHANGED).toUInt()
                        )
                    }
                }
            }
            SetWindowPos(
                mainHwnd,
                HWND_TOP,
                272,
                200,
                760,
                660,
                (SWP_SHOWWINDOW or SWP_FRAMECHANGED).toUInt()
            )
            for (panelHwnd in panelHwnds.values) {
                ShowWindow(panelHwnd, SW_SHOWNOACTIVATE)
                InvalidateRect(panelHwnd, null, 1)
                UpdateWindow(panelHwnd)
            }
            BringWindowToTop(mainHwnd)
            SetForegroundWindow(mainHwnd)
            InvalidateRect(mainHwnd, null, 1)
            UpdateWindow(mainHwnd)
        } else {
            for (panelHwnd in panelHwnds.values) {
                ShowWindow(panelHwnd, SW_HIDE)
            }
            SetWindowPos(
                mainHwnd,
                HWND_TOP,
                60,
                40,
                1440,
                860,
                (SWP_SHOWWINDOW or SWP_FRAMECHANGED).toUInt()
            )
            InvalidateRect(mainHwnd, null, 1)
            UpdateWindow(mainHwnd)
        }
    }

    fun invalidateAllWindows() {
        activeHwnd?.let { InvalidateRect(it, null, 0) }
        for (panelHwnd in panelHwnds.values) {
            if (IsWindowVisible(panelHwnd) != 0) {
                InvalidateRect(panelHwnd, null, 0)
            }
        }
    }

    fun updateMainWindowTitle(windowImpl: WindowsWindowNativeImpl, title: String) {
        if (activeWindowImpl === windowImpl) {
            activeHwnd?.let { hwnd ->
                SetWindowTextW(hwnd, title.ifBlank { "GenericUI" })
                InvalidateRect(hwnd, null, 0)
            }
        }
    }

    fun onMainWindowTitleBarChanged(windowImpl: WindowsWindowNativeImpl) {
        if (activeWindowImpl === windowImpl) {
            activeHwnd?.let { hwnd ->
                SetWindowPos(
                    hwnd,
                    null,
                    0,
                    0,
                    0,
                    0,
                    (SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_FRAMECHANGED).toUInt()
                )
                InvalidateRect(hwnd, null, 0)
            }
        }
    }

    fun createAndShowWin32Window(windowImpl: WindowsWindowNativeImpl) {
        val existingHwnd = activeHwnd
        if (existingHwnd != null && activeWindowImpl === windowImpl && IsWindow(existingHwnd) != 0) {
            SetWindowTextW(existingHwnd, windowImpl.getTitle().ifBlank { "GenericUI" })
            InvalidateRect(existingHwnd, null, 1)
            UpdateWindow(existingHwnd)
            return
        }
        Win32WinMain.initializeFromProcessStartup()
        activeWindowImpl = windowImpl
        monitorRefreshRateHz = queryMonitorRefreshRateHz()
        lastMultiWindowVisibilityState = null

        memScoped {
            val hInstance = Win32WinMain.hInstance ?: GetModuleHandleW(null)
            resolveAppIcons(hInstance)

            val className = "GenericUIWin32HostWindowW"
            if (!windowClassRegistered) {
                val wc = alloc<WNDCLASSEXW>()
                wc.cbSize = sizeOf<WNDCLASSEXW>().toUInt()
                wc.style = (CS_HREDRAW or CS_VREDRAW).toUInt()
                wc.lpfnWndProc = staticCFunction(::win32HostWndProc)
                wc.hInstance = hInstance
                wc.hIcon = appIconBig
                wc.hIconSm = appIconSmall
                wc.hCursor = LoadCursorW(null, IDC_ARROW)
                wc.hbrBackground = CreateSolidBrush(
                    if (isWindowsSystemDarkTheme()) darkSystemPalette.bgBase else lightSystemPalette.bgBase
                )
                wc.lpszClassName = className.wcstr.ptr
                RegisterClassExW(wc.ptr)
                windowClassRegistered = true
            }

            val size = windowImpl.getWindowSize()
            val width = size.width.coerceAtLeast(860)
            val height = size.height.coerceAtLeast(620)
            val title = windowImpl.getTitle().ifBlank { "GenericUI" }

            val hwnd = CreateWindowExW(
                dwExStyle = WS_EX_APPWINDOW.toUInt(),
                lpClassName = className,
                lpWindowName = title,
                dwStyle = (WS_OVERLAPPEDWINDOW or WS_VISIBLE or WS_CLIPCHILDREN).toUInt(),
                X = 284,
                Y = 198,
                nWidth = width,
                nHeight = height,
                hWndParent = null,
                hMenu = null,
                hInstance = hInstance,
                lpParam = null
            )
            activeHwnd = hwnd
            if (hwnd != null) {
                applyWindowsImmersiveTitleBarTheme(hwnd, isWindowsSystemDarkTheme())
                appIconBig?.let { icon ->
                    SendMessageW(hwnd, WM_SETICON.toUInt(), ICON_BIG.toULong(), icon.toLong())
                }
                appIconSmall?.let { icon ->
                    SendMessageW(hwnd, WM_SETICON.toUInt(), ICON_SMALL.toULong(), icon.toLong())
                }
                val hMenuBar = buildWin32MenuBar(windowImpl)
                if (hMenuBar != null) {
                    SetMenu(hwnd, hMenuBar)
                    DrawMenuBar(hwnd)
                }
                ShowWindow(hwnd, Win32WinMain.nCmdShow)
                ShowWindow(hwnd, SW_SHOWNORMAL)
                syncMultiWindowDocks()
                if (lastMultiWindowVisibilityState != true) {
                    SetWindowPos(
                        hwnd,
                        HWND_TOP,
                        80,
                        50,
                        width,
                        height,
                        (SWP_SHOWWINDOW or SWP_FRAMECHANGED).toUInt()
                    )
                }
                BringWindowToTop(hwnd)
                SetForegroundWindow(hwnd)
                SetFocus(hwnd)
                InvalidateRect(hwnd, null, 1)
                UpdateWindow(hwnd)
            }
        }
    }

    private fun formatMenuIconPrefix(rawIcon: String?): String {
        val spec = rawIcon?.trim().orEmpty()
        if (spec.isEmpty()) return ""
        if (!spec.startsWith("res://") && !spec.startsWith("@") && !spec.startsWith("ic_") && spec.length <= 4) {
            return "$spec "
        }
        val key = spec.lowercase()
            .removePrefix("res://drawable/")
            .removePrefix("@drawable/")
            .removePrefix("ic_tool_")
            .removePrefix("ic_")
            .replace('_', '-')
        val glyph = when {
            key.contains("move") -> "✥"
            key.contains("rect") -> "▭"
            key.contains("ellipse") -> "◯"
            key.contains("lasso") -> "➰"
            key.contains("wand") -> "🪄"
            key.contains("crop") -> "✂"
            key.contains("brush") -> "🖌"
            key.contains("pencil") -> "✏"
            key.contains("airbrush") -> "💨"
            key.contains("eraser") -> "⌫"
            key.contains("bucket") || key.contains("fill") -> "🪣"
            key.contains("gradient") -> "🌈"
            key.contains("eyedropper") || key.contains("picker") -> "💧"
            key.contains("text") -> "T"
            key.contains("shape") -> "⬢"
            key.contains("bezier") || key.contains("pen") -> "✒"
            key.contains("node") -> "◈"
            else -> "▸"
        }
        return "$glyph "
    }

    private fun populateWin32PopupMenu(hPopup: HMENU, menuWidget: DesktopNativeWidget) {
        for (itemWidget in menuWidget.children) {
            val itemImpl = itemWidget.attributes["nativeImpl"]
            when (itemImpl) {
                is DesktopMenuNativeImpl<*> -> {
                    val hSubPopup = CreatePopupMenu() ?: continue
                    populateWin32PopupMenu(hSubPopup, itemWidget)
                    val iconPrefix = formatMenuIconPrefix(itemImpl.getIcon())
                    val subLabel = itemImpl.getLabel().ifBlank { "Submenu" }
                    AppendMenuW(hPopup, MF_POPUP.toUInt(), hSubPopup.toLong().toULong(), "$iconPrefix$subLabel")
                }
                is DesktopMenuItemNativeImpl<*> -> {
                    val cmdId = nextMenuCommandId++
                    val rawLabel = itemImpl.getLabel().ifBlank { "Item $cmdId" }
                    val iconPrefix = formatMenuIconPrefix(itemImpl.getIcon())
                    val shortcutStr = itemImpl.getShortcutText()?.takeIf { it.isNotBlank() }
                        ?: itemImpl.getShortcut()?.displayText?.takeIf { it.isNotBlank() }
                    val fullLabel = if (!shortcutStr.isNullOrBlank()) {
                        "$iconPrefix$rawLabel\t$shortcutStr"
                    } else {
                        "$iconPrefix$rawLabel"
                    }
                    var flags = MF_STRING
                    if (itemImpl.isChecked()) {
                        flags = flags or MF_CHECKED
                    }
                    if (!itemImpl.isEnabled()) {
                        flags = flags or MF_GRAYED
                    }
                    val action: () -> Unit = {
                        itemImpl.dispatchClick()
                        syncMultiWindowDocks()
                        invalidateAllWindows()
                    }
                    menuCommandHandlers[cmdId] = action
                    if (!shortcutStr.isNullOrBlank()) {
                        val normKey = shortcutStr.uppercase().replace(" ", "")
                        shortcutCommandHandlers[normKey] = action
                    }
                    AppendMenuW(hPopup, flags.toUInt(), cmdId.toULong(), fullLabel)
                }
                else -> {
                    AppendMenuW(hPopup, MF_SEPARATOR.toUInt(), 0u, null)
                }
            }
        }
    }

    private fun buildWin32MenuBar(windowImpl: WindowsWindowNativeImpl): HMENU? {
        menuCommandHandlers.clear()
        shortcutCommandHandlers.clear()
        nextMenuCommandId = 1001
        val menuBarWidget = windowImpl.attachedMenuBarWidget() ?: return null
        val hMenuBar = CreateMenu() ?: return null

        for (menuWidget in menuBarWidget.children) {
            val menuImpl = menuWidget.attributes["nativeImpl"] as? DesktopMenuNativeImpl<*>
            val rawMenuLabel = menuImpl?.getLabel()?.ifBlank { "Menu" }
                ?: (menuWidget.attributes["label"] as? String)
                ?: "Menu"
            val iconPrefix = formatMenuIconPrefix(menuImpl?.getIcon())
            val hPopup = CreatePopupMenu() ?: continue
            populateWin32PopupMenu(hPopup, menuWidget)
            AppendMenuW(hMenuBar, MF_POPUP.toUInt(), hPopup.toLong().toULong(), "$iconPrefix$rawMenuLabel")
        }
        return hMenuBar
    }

    var visualTestMode: Boolean = false
    var visualTestBannerText: String? = null

    fun pumpVisualWindowEvents(durationMs: Long = 32L) = memScoped {
        val hwnd = activeHwnd ?: return@memScoped
        val qpcFreq = alloc<LARGE_INTEGER>()
        val qpcCounter = alloc<LARGE_INTEGER>()
        QueryPerformanceFrequency(qpcFreq.ptr)
        val ticksPerMs = (qpcFreq.QuadPart.toDouble() / 1000.0).coerceAtLeast(1.0)
        QueryPerformanceCounter(qpcCounter.ptr)
        val startMs = qpcCounter.QuadPart.toDouble() / ticksPerMs
        val msg = alloc<MSG>()

        invalidateAllWindows()
        do {
            while (PeekMessageW(msg.ptr, null, 0u, 0u, PM_REMOVE.toUInt()) != 0) {
                if (msg.message == WM_QUIT.toUInt()) {
                    return@memScoped
                }
                TranslateMessage(msg.ptr)
                DispatchMessageW(msg.ptr)
            }
            QueryPerformanceCounter(qpcCounter.ptr)
            val nowMs = qpcCounter.QuadPart.toDouble() / ticksPerMs
            dispatchVBlankTree(activeWindowImpl?.nativeWidget, nowMs)
            activeHwnd?.let { UpdateWindow(it) }
            for (panelHwnd in panelHwnds.values) {
                if (IsWindowVisible(panelHwnd) != 0) {
                    UpdateWindow(panelHwnd)
                }
            }
            if (nowMs - startMs < durationMs) {
                Sleep(4u)
            }
        } while (nowMs - startMs < durationMs && activeHwnd != null)
    }

    fun closeAllVisualWindows() {
        for (panelHwnd in panelHwnds.values.toList()) {
            DestroyWindow(panelHwnd)
        }
        panelHwnds.clear()
        hwndToPanel.clear()
        activeHwnd?.let { DestroyWindow(it) }
        clearActiveWindow()
    }

    fun runMessageLoop() = memScoped {
        if (visualTestMode) {
            pumpVisualWindowEvents(64L)
            return@memScoped
        }
        val hwnd = activeHwnd ?: return@memScoped
        monitorRefreshRateHz = queryMonitorRefreshRateHz()
        val targetFrameIntervalMs = 1000.0 / monitorRefreshRateHz.toDouble()

        val qpcFreq = alloc<LARGE_INTEGER>()
        val qpcCounter = alloc<LARGE_INTEGER>()
        QueryPerformanceFrequency(qpcFreq.ptr)
        val ticksPerMs = (qpcFreq.QuadPart.toDouble() / 1000.0).coerceAtLeast(1.0)

        QueryPerformanceCounter(qpcCounter.ptr)
        var lastFrameTimeMs = qpcCounter.QuadPart.toDouble() / ticksPerMs

        val msg = alloc<MSG>()
        var running = true

        while (running) {
            while (PeekMessageW(msg.ptr, null, 0u, 0u, PM_REMOVE.toUInt()) != 0) {
                if (msg.message == WM_QUIT.toUInt()) {
                    running = false
                    break
                }
                TranslateMessage(msg.ptr)
                DispatchMessageW(msg.ptr)
            }
            if (!running || activeHwnd == null) break

            QueryPerformanceCounter(qpcCounter.ptr)
            val nowMs = qpcCounter.QuadPart.toDouble() / ticksPerMs
            val elapsedMs = nowMs - lastFrameTimeMs

            if (elapsedMs >= targetFrameIntervalMs) {
                lastFrameTimeMs = nowMs
                val anyVBlankDispatched = dispatchVBlankTree(activeWindowImpl?.nativeWidget, nowMs)
                if (anyVBlankDispatched) {
                    InvalidateRect(hwnd, null, 0)
                    UpdateWindow(hwnd)
                }
            } else {
                val remainingMs = targetFrameIntervalMs - elapsedMs
                val waitMs = (remainingMs - 1.0).toInt().coerceIn(1, 16).toUInt()
                val waitRes = MsgWaitForMultipleObjectsEx(0u, null, waitMs, QS_ALLINPUT.toUInt(), 0u)
                if (waitRes == 0u && PeekMessageW(msg.ptr, null, 0u, 0u, PM_NOREMOVE.toUInt()) == 0) {
                    Sleep(1u)
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun dispatchVBlankTree(widget: DesktopNativeWidget?, timestampMs: Double): Boolean {
        if (widget == null || !widget.visible || widget.disposed) return false
        var dispatched = false
        val vblankFn = widget.attributes["vblankDispatch"] as? ((Double?) -> Unit)
        if (vblankFn != null) {
            val swapFn = widget.attributes["swapCount"] as? (() -> Long)
            val prevSwap = swapFn?.invoke() ?: -1L
            runCatching { vblankFn.invoke(timestampMs) }
            val nextSwap = swapFn?.invoke() ?: -1L
            if ((prevSwap != -1L && nextSwap != prevSwap) || widget.attributes["vblankDirtied"] == true) {
                widget.attributes["vblankDirtied"] = false
                dispatched = true
            }
        }
        val children = widget.children
        var idx = 0
        while (idx < children.size) {
            val child = children.getOrNull(idx)
            if (child != null && dispatchVBlankTree(child, timestampMs)) {
                dispatched = true
            }
            idx++
        }
        return dispatched
    }

    fun handleCommand(commandId: Int) {
        runCatching {
            menuCommandHandlers[commandId]?.invoke()
        }
    }

    fun handleKeyDown(hwnd: HWND, vkCode: Int): Boolean {
        val textInput = focusedTextInput
        if (textInput != null) {
            when (vkCode) {
                VK_BACK -> {
                    val cur = textInput.getValue()
                    if (cur.isNotEmpty()) {
                        textInput.setValue(cur.dropLast(1))
                        invalidateAllWindows()
                    }
                    return true
                }
                VK_RETURN -> {
                    textInput.submitValue()
                    invalidateAllWindows()
                    return true
                }
                VK_ESCAPE -> {
                    (focusedWidget?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>)?.setFocusedState(false)
                    focusedWidget = null
                    focusedTextInput = null
                    invalidateAllWindows()
                    return true
                }
            }
        }

        val ctrlDown = (GetKeyState(VK_CONTROL).toInt() and 0x8000) != 0
        val shiftDown = (GetKeyState(VK_SHIFT).toInt() and 0x8000) != 0
        val altDown = (GetKeyState(VK_MENU).toInt() and 0x8000) != 0
        if (textInput != null && !ctrlDown && !altDown && vkCode !in VK_F1..VK_F12) {
            return false
        }

        val keyName = when (vkCode) {
            in 0x41..0x5A -> vkCode.toChar().toString()
            in 0x30..0x39 -> vkCode.toChar().toString()
            in VK_F1..VK_F12 -> "F${vkCode - VK_F1 + 1}"
            VK_DELETE -> "DELETE"
            VK_OEM_PLUS, VK_ADD -> "+"
            VK_OEM_MINUS, VK_SUBTRACT -> "-"
            else -> null
        }

        if (keyName != null) {
            val sig = buildString {
                if (ctrlDown) append("CTRL+")
                if (altDown) append("ALT+")
                if (shiftDown) append("SHIFT+")
                append(keyName)
            }
            val handler = shortcutCommandHandlers[sig]
            if (handler != null) {
                runCatching { handler.invoke() }
                return true
            }
        }

        val canvasImpl = activeCanvasImpl
        if (canvasImpl != null && textInput == null) {
            val mappedKey = when (vkCode) {
                VK_BACK -> "Backspace"
                VK_RETURN -> "Enter"
                VK_ESCAPE -> "Escape"
                VK_TAB -> "Tab"
                VK_DELETE -> "Delete"
                VK_LEFT -> "ArrowLeft"
                VK_RIGHT -> "ArrowRight"
                VK_UP -> "ArrowUp"
                VK_DOWN -> "ArrowDown"
                VK_HOME -> "Home"
                VK_END -> "End"
                VK_PRIOR -> "PageUp"
                VK_NEXT -> "PageDown"
                in VK_F1..VK_F12 -> "F${vkCode - VK_F1 + 1}"
                else -> if (ctrlDown || altDown) keyName else null
            }
            if (mappedKey != null) {
                runCatching {
                    canvasImpl.dispatchKeyDown(
                        KeyEvent(
                            key = mappedKey,
                            code = mappedKey,
                            keyCode = vkCode,
                            shiftKey = shiftDown,
                            ctrlKey = ctrlDown,
                            altKey = altDown
                        )
                    )
                }
                InvalidateRect(hwnd, null, 0)
                return true
            }
        }
        return false
    }

    fun handleKeyUp(hwnd: HWND, vkCode: Int): Boolean {
        val canvasImpl = activeCanvasImpl ?: return false
        val ctrlDown = (GetKeyState(VK_CONTROL).toInt() and 0x8000) != 0
        val shiftDown = (GetKeyState(VK_SHIFT).toInt() and 0x8000) != 0
        val altDown = (GetKeyState(VK_MENU).toInt() and 0x8000) != 0
        val mappedKey = when (vkCode) {
            VK_BACK -> "Backspace"
            VK_RETURN -> "Enter"
            VK_ESCAPE -> "Escape"
            VK_TAB -> "Tab"
            VK_SPACE -> " "
            VK_DELETE -> "Delete"
            VK_LEFT -> "ArrowLeft"
            VK_RIGHT -> "ArrowRight"
            VK_UP -> "ArrowUp"
            VK_DOWN -> "ArrowDown"
            VK_HOME -> "Home"
            VK_END -> "End"
            VK_PRIOR -> "PageUp"
            VK_NEXT -> "PageDown"
            in 0x41..0x5A -> vkCode.toChar().toString()
            in 0x30..0x39 -> vkCode.toChar().toString()
            in VK_F1..VK_F12 -> "F${vkCode - VK_F1 + 1}"
            else -> null
        } ?: return false
        runCatching {
            canvasImpl.dispatchKeyUp(
                KeyEvent(
                    key = mappedKey,
                    code = mappedKey,
                    keyCode = vkCode,
                    shiftKey = shiftDown,
                    ctrlKey = ctrlDown,
                    altKey = altDown
                )
            )
        }
        InvalidateRect(hwnd, null, 0)
        return true
    }

    fun handleCharInput(hwnd: HWND, charCode: Int): Boolean {
        if (charCode < 32 || charCode == 127) return false
        val ctrlDown = (GetKeyState(VK_CONTROL).toInt() and 0x8000) != 0
        if (ctrlDown) return false
        val ch = charCode.toChar()
        val textInput = focusedTextInput
        if (textInput != null) {
            textInput.setValue(textInput.getValue() + ch)
            invalidateAllWindows()
            return true
        }
        val canvasImpl = activeCanvasImpl
        if (canvasImpl != null) {
            val shiftDown = (GetKeyState(VK_SHIFT).toInt() and 0x8000) != 0
            val altDown = (GetKeyState(VK_MENU).toInt() and 0x8000) != 0
            val keyStr = ch.toString()
            runCatching {
                canvasImpl.dispatchKeyDown(
                    KeyEvent(
                        key = keyStr,
                        code = keyStr,
                        keyCode = charCode,
                        shiftKey = shiftDown,
                        ctrlKey = false,
                        altKey = altDown
                    )
                )
            }
            InvalidateRect(hwnd, null, 0)
            return true
        }
        return false
    }

    fun handleWindowResize(hwnd: HWND) = memScoped {
        if (hwnd != activeHwnd) return@memScoped
        val rect = alloc<RECT>()
        if (GetClientRect(hwnd, rect.ptr) != 0) {
            val w = (rect.right - rect.left).coerceAtLeast(1)
            val h = (rect.bottom - rect.top).coerceAtLeast(1)
            runCatching { activeWindowImpl?.setWindowSize(Size(w, h)) }
        }
    }

    fun updateCursorForWindow(hwnd: HWND): Boolean {
        val hit = hoveredHitRegion
        val hitCursor = (hit?.widget?.attributes?.get("cursor") as? String)?.lowercase()
        val canvasCursor = (activeCanvasImpl?.nativeWidget?.attributes?.get("cursor") as? String)?.lowercase()
        val cursorId = when {
            hit?.isTextInput == true || hitCursor == "text" -> IDC_IBEAM
            hitCursor == "crosshair" -> IDC_CROSS
            hit != null -> IDC_HAND
            hwnd == activeHwnd &&
                canvasCursor == "crosshair" &&
                hoverMouseX in canvasViewportRect[0]..canvasViewportRect[2] &&
                hoverMouseY in canvasViewportRect[1]..canvasViewportRect[3] -> IDC_CROSS
            else -> IDC_ARROW
        }
        SetCursor(LoadCursorW(null, cursorId))
        return true
    }

    private fun setWidgetFocused(newFocused: DesktopNativeWidget?) {
        val prevFocused = focusedWidget
        if (prevFocused !== newFocused) {
            val prevTextInput = focusedTextInput
            (prevFocused?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>)?.setFocusedState(false)
            (newFocused?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>)?.setFocusedState(true)
            focusedWidget = newFocused
            focusedTextInput = newFocused?.attributes?.get("nativeImpl") as? DesktopTextInputNativeImpl<*>
            if (prevTextInput != null && prevTextInput !== focusedTextInput) {
                runCatching { prevTextInput.submitValue() }
            }
        }
    }

    private var cardPageScrollY: Int = 0
    private var cardPageMaxScrollY: Int = 0
    private var cardPageLastKey: String = ""
    private var cardPageLastScrollRequestVersion: Long = -1L
    private var activeCardPageScrollViewImpl: DesktopScrollViewNativeImpl<*>? = null
    private var isDraggingCardScrollbarThumb: Boolean = false
    private var cardScrollbarDragGrabOffsetY: Int = 0
    private var cardPageScrollRailRect: IntArray = intArrayOf(-1, -1, -1, -1)
    private var cardPageScrollThumbRect: IntArray = intArrayOf(-1, -1, -1, -1)
    private val cardScrollbarThumbWidget = DesktopNativeWidget("Win32", "scrollbar-thumb")
    private val cardScrollbarRailWidget = DesktopNativeWidget("Win32", "scrollbar-rail")
    private val cardScrollbarUpBtnWidget = DesktopNativeWidget("Win32", "scrollbar-up")
    private val cardScrollbarDownBtnWidget = DesktopNativeWidget("Win32", "scrollbar-down")
    private val lazyGridScrollbarThumbWidget = DesktopNativeWidget("Win32", "lazygrid-scrollbar-thumb")
    private val lazyGridScrollbarRailWidget = DesktopNativeWidget("Win32", "lazygrid-scrollbar-rail")
    private var isDraggingLazyGridScrollbarThumb: Boolean = false
    private var lazyGridScrollbarDragGrabOffsetY: Int = 0

    fun getCardPageScrollY(): Int = cardPageScrollY
    fun getCardPageMaxScrollY(): Int = cardPageMaxScrollY
    fun getCardPageScrollThumbBounds(): IntArray = cardPageScrollThumbRect.copyOf()
    fun getCardPageScrollRailBounds(): IntArray = cardPageScrollRailRect.copyOf()
    fun isCardScrollbarThumbHovered(): Boolean =
        isDraggingCardScrollbarThumb ||
            hoveredWidget === cardScrollbarThumbWidget ||
            (cardPageScrollThumbRect[2] > cardPageScrollThumbRect[0] &&
                hoverMouseX in cardPageScrollThumbRect[0]..cardPageScrollThumbRect[2] &&
                hoverMouseY in cardPageScrollThumbRect[1]..cardPageScrollThumbRect[3])
    fun isCardScrollbarThumbDragging(): Boolean = isDraggingCardScrollbarThumb

    fun handleMouseWheel(hwnd: HWND, wheelDelta: Int) {
        val grid = activeLazyGridImpl
        if (grid != null) {
            val totalDataRows = (grid.getTotalRowCount() - 1).coerceAtLeast(0)
            if (totalDataRows <= 0) return
            val rawRows = (-wheelDelta * 3) / 120
            val step = when {
                rawRows != 0 -> rawRows
                wheelDelta > 0 -> -2
                else -> 2
            }
            val maxFirst = (totalDataRows - 4).coerceAtLeast(0)
            val next = (grid.firstVisibleDataRow + step).coerceIn(0, maxFirst)
            if (next != grid.firstVisibleDataRow) {
                grid.firstVisibleDataRow = next
                InvalidateRect(hwnd, null, 0)
                UpdateWindow(hwnd)
            }
            return
        }
        if (cardPageMaxScrollY > 0) {
            val rawLines = (-wheelDelta * 96) / 120
            val step = when {
                rawLines != 0 -> rawLines
                wheelDelta > 0 -> -96
                else -> 96
            }
            val next = (cardPageScrollY + step).coerceIn(0, cardPageMaxScrollY)
            if (next != cardPageScrollY) {
                cardPageScrollY = next
                activeCardPageScrollViewImpl?.updateScrollPositionFromHost(0, next)
                InvalidateRect(hwnd, null, 0)
                UpdateWindow(hwnd)
            }
            return
        }
        val canvasImpl = activeCanvasImpl
        if (canvasImpl != null && hwnd == activeHwnd) {
            val (cx, cy) = mapWindowPointToCanvas(hoverMouseX, hoverMouseY)
            val deltaY = if (wheelDelta > 0) -48f else 48f
            runCatching {
                canvasImpl.dispatchWheel(
                    WheelEvent(
                        x = cx,
                        y = cy,
                        deltaX = 0f,
                        deltaY = deltaY
                    )
                )
            }
            InvalidateRect(hwnd, null, 0)
            UpdateWindow(hwnd)
        }
    }

    fun handleMouseDown(hwnd: HWND, x: Int, y: Int) {
        SetFocus(hwnd)
        val regions = hitRegionsByHwnd[hwnd.toLong()].orEmpty()
        val hit = regions.lastOrNull { it.contains(x, y) }

        setWidgetFocused(hit?.widget)

        if (hit != null) {
            pressedWidget = hit.widget
            (pressedWidget?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>)?.setActivePressedState(true)
            if (hit.onDrag != null) {
                activeDragRegion = hit
                SetCapture(hwnd)
            }
            runCatching { hit.onClick?.invoke(x, y) }
            runCatching { hit.onDrag?.invoke(x, y) }
            syncMultiWindowDocks()
            invalidateAllWindows()
            UpdateWindow(hwnd)
            return
        }

        if (hwnd == activeHwnd) {
            val cLeft = canvasViewportRect[0]
            val cTop = canvasViewportRect[1]
            val cRight = canvasViewportRect[2]
            val cBottom = canvasViewportRect[3]
            if (x in cLeft..cRight && y in cTop..cBottom) {
                val canvasImpl = activeCanvasImpl
                if (canvasImpl != null) {
                    isDraggingCanvas = true
                    SetCapture(hwnd)
                    val (cx, cy) = mapWindowPointToCanvas(x, y)
                    runCatching {
                        canvasImpl.dispatchPointerDown(
                            PointerEvent(
                                x = cx,
                                y = cy,
                                button = 0
                            )
                        )
                    }
                    InvalidateRect(hwnd, null, 0)
                    UpdateWindow(hwnd)
                    return
                }
            }
        }
        InvalidateRect(hwnd, null, 0)
    }

    fun handleMouseMove(hwnd: HWND, x: Int, y: Int) {
        hoverHwndKey = hwnd.toLong()
        hoverMouseX = x
        hoverMouseY = y
        if (isDraggingCanvas && hwnd == activeHwnd) {
            val canvasImpl = activeCanvasImpl
            if (canvasImpl != null) {
                val (cx, cy) = mapWindowPointToCanvas(x, y)
                runCatching {
                    canvasImpl.dispatchPointerMove(
                        PointerEvent(
                            x = cx,
                            y = cy,
                            button = 0
                        )
                    )
                }
                InvalidateRect(hwnd, null, 0)
                UpdateWindow(hwnd)
                return
            }
        }
        val drag = activeDragRegion
        if (drag != null && drag.onDrag != null) {
            runCatching { drag.onDrag.invoke(x, y) }
            InvalidateRect(hwnd, null, 0)
            UpdateWindow(hwnd)
            return
        }
        val regions = hitRegionsByHwnd[hwnd.toLong()].orEmpty()
        val hit = regions.lastOrNull { it.contains(x, y) }
        val prevHit = hoveredHitRegion
        val prevWidget = hoveredWidget
        val newWidget = hit?.widget
        hoveredHitRegion = hit
        if (prevWidget !== newWidget) {
            val prevImpl = prevWidget?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>
            val newImpl = newWidget?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>
            prevImpl?.setHoveredState(false)
            prevImpl?.dispatchPointerLeave(PointerEvent(x = x.toFloat(), y = y.toFloat()))
            newImpl?.setHoveredState(true)
            newImpl?.dispatchPointerEnter(PointerEvent(x = x.toFloat(), y = y.toFloat()))
            hoveredWidget = newWidget
        }
        updateCursorForWindow(hwnd)

        val hoveredTipHit = regions.lastOrNull { it.contains(x, y) && !it.tooltip.isNullOrBlank() }
        val newTooltip = hoveredTipHit?.tooltip
        if (newTooltip != hoveredTooltip || prevHit !== hit || prevWidget !== newWidget) {
            hoveredTooltip = newTooltip
            InvalidateRect(hwnd, null, 0)
            UpdateWindow(hwnd)
        }

        if (hit == null && hwnd == activeHwnd) {
            val cLeft = canvasViewportRect[0]
            val cTop = canvasViewportRect[1]
            val cRight = canvasViewportRect[2]
            val cBottom = canvasViewportRect[3]
            if (x in cLeft..cRight && y in cTop..cBottom) {
                val canvasImpl = activeCanvasImpl
                if (canvasImpl != null) {
                    val (cx, cy) = mapWindowPointToCanvas(x, y)
                    runCatching {
                        canvasImpl.dispatchPointerMove(
                            PointerEvent(
                                x = cx,
                                y = cy,
                                button = -1
                            )
                        )
                    }
                    InvalidateRect(hwnd, null, 0)
                }
            }
        }
    }

    fun handleMouseUp(hwnd: HWND, x: Int, y: Int) {
        val wasScrollbarDragging = isDraggingCardScrollbarThumb || isDraggingLazyGridScrollbarThumb
        isDraggingCardScrollbarThumb = false
        isDraggingLazyGridScrollbarThumb = false
        if (pressedWidget != null || wasScrollbarDragging) {
            (pressedWidget?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>)?.setActivePressedState(false)
            pressedWidget = null
            InvalidateRect(hwnd, null, 0)
            UpdateWindow(hwnd)
        }
        if (isDraggingCanvas) {
            isDraggingCanvas = false
            val canvasImpl = activeCanvasImpl
            if (canvasImpl != null) {
                val (cx, cy) = mapWindowPointToCanvas(x, y)
                runCatching {
                    canvasImpl.dispatchPointerUp(
                        PointerEvent(
                            x = cx,
                            y = cy,
                            button = 0
                        )
                    )
                }
                InvalidateRect(hwnd, null, 0)
                UpdateWindow(hwnd)
            }
        }
        activeDragRegion = null
    }

    private fun mapWindowPointToCanvas(winX: Int, winY: Int): Pair<Float, Float> {
        val cLeft = canvasViewportRect[0]
        val cTop = canvasViewportRect[1]
        val cW = max(1, canvasViewportRect[2] - cLeft)
        val cH = max(1, canvasViewportRect[3] - cTop)
        val (surfW, surfH) = activeCanvasSurfaceSize
        val normX = (winX - cLeft).toFloat() / cW.toFloat()
        val normY = (winY - cTop).toFloat() / cH.toFloat()
        return (normX * surfW.toFloat()) to (normY * surfH.toFloat())
    }

    private fun openSelectPopupMenu(hwnd: HWND, selectImpl: DesktopSelectNativeImpl<*>, x: Int, y: Int) = memScoped {
        val options = selectImpl.getOptions()
        if (options.isEmpty()) return@memScoped
        val hPopup = CreatePopupMenu() ?: return@memScoped
        val current = selectImpl.getSelectedValue()
        options.forEachIndexed { idx, (value, label) ->
            val flags = if (value == current) (MF_STRING or MF_CHECKED).toUInt() else MF_STRING.toUInt()
            AppendMenuW(hPopup, flags, (5000 + idx).toULong(), label.ifBlank { value })
        }
        val pt = alloc<POINT>()
        pt.x = x
        pt.y = y
        ClientToScreen(hwnd, pt.ptr)
        val chosen = TrackPopupMenu(
            hPopup,
            (TPM_RETURNCMD or TPM_NONOTIFY or TPM_LEFTALIGN or TPM_TOPALIGN).toUInt(),
            pt.x,
            pt.y,
            0,
            hwnd,
            null
        )
        DestroyMenu(hPopup)
        if (chosen >= 5000) {
            val optIndex = chosen - 5000
            options.getOrNull(optIndex)?.let { (valStr, _) ->
                selectImpl.setSelectedValue(valStr)
            }
        }
    }

    private fun ensureSharedFonts() {
        if (cachedFontSmall == null) cachedFontSmall = createSegoeFont(13, FW_NORMAL)
        if (cachedFontSmallBold == null) cachedFontSmallBold = createSegoeFont(13, FW_SEMIBOLD)
        if (cachedFontNormal == null) cachedFontNormal = createSegoeFont(15, FW_NORMAL)
        if (cachedFontBold == null) cachedFontBold = createSegoeFont(15, FW_SEMIBOLD)
        if (cachedFontTitle == null) cachedFontTitle = createSegoeFont(19, FW_BOLD)
        if (cachedFontMetricBig == null) cachedFontMetricBig = createSegoeFont(26, FW_BOLD)
    }

    private fun obtainMainBackBufferDc(hdc: HDC?, hwndKey: Long, width: Int, height: Int): HDC? {
        if (cachedMainMemDc != null &&
            cachedMainMemBmp != null &&
            cachedMainBufferHwndKey == hwndKey &&
            cachedMainBufferW == width &&
            cachedMainBufferH == height
        ) {
            return cachedMainMemDc
        }
        releaseMainBackBufferDc()
        val memDc = CreateCompatibleDC(hdc) ?: return null
        val memBmp = CreateCompatibleBitmap(hdc, width, height)
        if (memBmp == null) {
            DeleteDC(memDc)
            return null
        }
        cachedMainOldBmp = SelectObject(memDc, memBmp)
        cachedMainMemDc = memDc
        cachedMainMemBmp = memBmp
        cachedMainBufferW = width
        cachedMainBufferH = height
        cachedMainBufferHwndKey = hwndKey
        return memDc
    }

    private fun releaseMainBackBufferDc() {
        val dc = cachedMainMemDc
        val bmp = cachedMainMemBmp
        val old = cachedMainOldBmp
        if (dc != null && old != null) {
            SelectObject(dc, old)
        }
        if (bmp != null) {
            DeleteObject(bmp)
        }
        if (dc != null) {
            DeleteDC(dc)
        }
        cachedMainMemDc = null
        cachedMainMemBmp = null
        cachedMainOldBmp = null
        cachedMainBufferW = 0
        cachedMainBufferH = 0
        cachedMainBufferHwndKey = 0L
    }

    fun paintWindow(hwnd: HWND) = memScoped {
        val ps = alloc<PAINTSTRUCT>()
        val hdc = BeginPaint(hwnd, ps.ptr) ?: return@memScoped
        val clientRect = alloc<RECT>()
        GetClientRect(hwnd, clientRect.ptr)
        val clientW = max(180, clientRect.right - clientRect.left)
        val clientH = max(90, clientRect.bottom - clientRect.top)

        val isMainWin = hwnd == activeHwnd
        var tempMemDc: HDC? = null
        var tempMemBmp: HBITMAP? = null
        var tempOldBmp: HGDIOBJ? = null
        val memDc = if (isMainWin) {
            obtainMainBackBufferDc(hdc, hwnd.toLong(), clientW, clientH)
        } else {
            val dc = CreateCompatibleDC(hdc)
            val bmp = CreateCompatibleBitmap(hdc, clientW, clientH)
            tempOldBmp = SelectObject(dc, bmp)
            tempMemDc = dc
            tempMemBmp = bmp
            dc
        }

        ensureSharedFonts()
        val fontSmall = cachedFontSmall
        val fontSmallBold = cachedFontSmallBold
        val fontNormal = cachedFontNormal
        val fontBold = cachedFontBold
        val fontTitle = cachedFontTitle
        val fontMetricBig = cachedFontMetricBig

        val oldFont = SelectObject(memDc, fontNormal)
        SetBkMode(memDc, TRANSPARENT)

        fun finishPaint() {
            BitBlt(hdc, 0, 0, clientW, clientH, memDc, 0, 0, SRCCOPY)
            SelectObject(memDc, oldFont)
            if (tempMemDc != null) {
                SelectObject(tempMemDc, tempOldBmp)
                if (tempMemBmp != null) DeleteObject(tempMemBmp)
                DeleteDC(tempMemDc)
            }
            EndPaint(hwnd, ps.ptr)
        }

        currentPaintHwndKey = hwnd.toLong()
        hitRegions.clear()
        if (hwnd == activeHwnd) {
            canvasViewportRect = intArrayOf(-1, -1, -1, -1)
            activeCanvasImpl = null
        }

        val rootWidget = activeWindowImpl?.nativeWidget
        val toolPalettes = mutableListOf<DesktopToolPaletteNativeImpl<*>>()
        val dockPanels = mutableListOf<DesktopDockPanelNativeImpl<*>>()
        val canvases = mutableListOf<DesktopNativeWidget>()
        collectStudioWidgets(rootWidget, toolPalettes, dockPanels, canvases)

        val theme = if (isWindowsSystemDarkTheme()) darkSystemPalette else lightSystemPalette
        val toolboxDockPanel = dockPanels.firstOrNull {
            it.getTitle().contains("Toolbox", ignoreCase = true) ||
                it.getTitle().contains("Tool", ignoreCase = true)
        }
        val timelinePanel = dockPanels.firstOrNull {
            it.getTitle().contains("Timeline", ignoreCase = true)
        }
        val rightPanels = dockPanels.filter {
            it !== toolboxDockPanel && it !== timelinePanel
        }

        // Auxiliary Panel Window (Multi-Window Mode — each DockPanel has its own PanelWindow!)
        val panelForHwnd = hwndToPanel[hwnd.toLong()]
        if (panelForHwnd != null) {
            fillRectColor(memDc, 0, 0, clientW, clientH, theme.bgPanel)
            val titleBarH = getPanelWindowTitleBarHeight(panelForHwnd)
            renderPanelWindowTitleBar(
                hwnd = hwnd,
                hdc = memDc,
                theme = theme,
                fontSmall = fontSmall,
                fontBold = fontBold,
                panel = panelForHwnd,
                clientW = clientW,
                titleBarH = titleBarH
            )
            val bodyTop = titleBarH + 6
            val bodyBottom = clientH - 6
            when {
                isToolboxPanel(panelForHwnd) -> {
                    renderLeftToolbox(
                        hwnd = hwnd,
                        hdc = memDc,
                        theme = theme,
                        fontSmall = fontSmall,
                        fontNormal = fontNormal,
                        fontBold = fontBold,
                        toolPalette = toolPalettes.firstOrNull(),
                        toolboxDockPanel = panelForHwnd,
                        width = clientW,
                        top = bodyTop,
                        bottom = bodyBottom,
                        isSeparatePanelWindow = true
                    )
                }
                isTimelinePanel(panelForHwnd) -> {
                    renderTopTimelineDock(
                        hwnd = hwnd,
                        hdc = memDc,
                        theme = theme,
                        fontSmall = fontSmall,
                        fontNormal = fontNormal,
                        fontBold = fontBold,
                        timelinePanel = panelForHwnd,
                        left = 6,
                        top = bodyTop,
                        right = clientW - 6,
                        bottom = bodyBottom,
                        isSeparatePanelWindow = true
                    )
                }
                else -> {
                    renderStandardDockPanelBody(
                        hwnd = hwnd,
                        hdc = memDc,
                        theme = theme,
                        fontSmall = fontSmall,
                        fontNormal = fontNormal,
                        fontBold = fontBold,
                        panel = panelForHwnd,
                        left = 6,
                        top = bodyTop,
                        right = clientW - 6,
                        bottomLimit = bodyBottom
                    )
                }
            }
            if (hoverHwndKey == hwnd.toLong()) {
                renderHoveredTooltipOverlay(memDc, fontSmall, theme, clientW, clientH)
            }
            finishPaint()
            return@memScoped
        }

        if (toolPalettes.isEmpty() && dockPanels.isEmpty()) {
            fillRectColor(memDc, 0, 0, clientW, clientH, theme.bgBase)
            val mainTitleBarH = renderMainWindowTitleBar(
                hwnd = hwnd,
                hdc = memDc,
                theme = theme,
                fontSmall = fontSmall,
                fontBold = fontBold,
                clientW = clientW
            )
            renderGeneralApplicationLayout(
                hwnd = hwnd,
                hdc = memDc,
                theme = theme,
                fontSmall = fontSmall,
                fontSmallBold = fontSmallBold,
                fontNormal = fontNormal,
                fontBold = fontBold,
                fontTitle = fontTitle,
                fontMetricBig = fontMetricBig,
                rootWidget = rootWidget,
                canvases = canvases,
                clientW = clientW,
                clientH = clientH,
                bodyTop = mainTitleBarH
            )
            if (hoverHwndKey == hwnd.toLong()) {
                renderHoveredTooltipOverlay(memDc, fontSmall, theme, clientW, clientH)
            }
            finishPaint()
            return@memScoped
        }

        val multiWindowMode = isStudioMultiWindowMode(rootWidget)
        val canvasWidget = canvases.firstOrNull()
        activeCanvasImpl = canvasWidget?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>

        if (multiWindowMode) {
            // Multi-Window Mode Main Canvas Window:
            // MenuBar is attached directly to this window; displays Document Tab / Viewport Toolbar strip,
            // full-bleed Hybrid Vector + Raster Canvas Viewport, and bottom Status Bar.
            fillRectColor(memDc, 0, 0, clientW, clientH, theme.bgBase)
            val mainTitleBarH = renderMainWindowTitleBar(
                hwnd = hwnd,
                hdc = memDc,
                theme = theme,
                fontSmall = fontSmall,
                fontBold = fontBold,
                clientW = clientW
            )
            val topBarH = mainTitleBarH + 38
            val statusBarH = 26
            val bodyBottom = max(topBarH + 120, clientH - statusBarH)

            fillRectColor(memDc, 0, mainTitleBarH, clientW, topBarH, theme.bgPanel)
            drawLineColor(memDc, 0, topBarH, clientW, topBarH, theme.borderSubtle)
            renderTopViewportToolbar(hwnd, memDc, theme, fontSmall, fontNormal, fontBold, rootWidget, dockPanels, clientW, topBarH)

            fillRectColor(memDc, 6, topBarH + 6, clientW - 6, bodyBottom - 6, theme.bgCard)
            drawRectBorder(memDc, 6, topBarH + 6, clientW - 6, bodyBottom - 6, theme.borderSubtle)
            renderCenterCanvas2D(
                hwnd = hwnd,
                hdc = memDc,
                theme = theme,
                fontSmall = fontSmall,
                fontSmallBold = fontSmallBold,
                fontNormal = fontNormal,
                fontBold = fontBold,
                fontTitle = fontTitle,
                rootWidget = rootWidget,
                canvasWidget = canvasWidget,
                left = 10,
                top = topBarH + 10,
                right = clientW - 10,
                bottom = bodyBottom - 10
            )

            fillRectColor(memDc, 0, bodyBottom, clientW, clientH, theme.bgPanel)
            drawLineColor(memDc, 0, bodyBottom, clientW, bodyBottom, theme.borderSubtle)
            renderStudioStatusBar(memDc, theme, fontSmall, rootWidget, dockPanels, clientW, bodyBottom, clientH)

            renderActiveModalOverlay(hwnd, memDc, theme, fontSmall, fontNormal, fontBold, fontTitle, clientW, clientH)

            if (hoverHwndKey == hwnd.toLong()) {
                renderHoveredTooltipOverlay(memDc, fontSmall, theme, clientW, clientH)
            }
            finishPaint()
            return@memScoped
        }

        // Studio Single-Window Dock Layout — GenericUI Base Native OS System Theme
        fillRectColor(memDc, 0, 0, clientW, clientH, theme.bgBase)
        val mainTitleBarH = renderMainWindowTitleBar(
            hwnd = hwnd,
            hdc = memDc,
            theme = theme,
            fontSmall = fontSmall,
            fontBold = fontBold,
            clientW = clientW
        )
        val topBarH = mainTitleBarH + 40
        val statusBarH = 26
        val leftCollapsed = toolboxDockPanel?.isCollapsed() == true
        val leftDockW = if (leftCollapsed) 168 else 212
        val rightDockW = 376
        val bodyBottom = max(topBarH + 220, clientH - statusBarH)
        val centerLeft = leftDockW + 6
        val centerRight = max(centerLeft + 260, clientW - rightDockW - 6)

        val timelineCollapsed = timelinePanel?.isCollapsed() == true
        val timelineDockH = if (timelinePanel == null) 0 else if (timelineCollapsed) 28 else 112

        val timelineTop = topBarH + 6
        val timelineBottom = timelineTop + timelineDockH
        val canvasTop = if (timelineDockH > 0) timelineBottom + 6 else topBarH + 6
        val canvasBottom = max(canvasTop + 140, bodyBottom - 6)

        // 1. Top Tool Options & Document Tab Bar (no app icon/name duplicated)
        fillRectColor(memDc, 0, mainTitleBarH, clientW, topBarH, theme.bgPanel)
        drawLineColor(memDc, 0, topBarH, clientW, topBarH, theme.borderSubtle)
        renderTopViewportToolbar(hwnd, memDc, theme, fontSmall, fontNormal, fontBold, rootWidget, dockPanels, clientW, topBarH)

        // 2. Left Toolbox Dock (Collapsible, Icon-Only Tool Palette Buttons + Hover Tooltips + FG/BG Swatches)
        fillRectColor(memDc, 0, topBarH + 1, leftDockW, bodyBottom, theme.bgPanel)
        drawLineColor(memDc, leftDockW, topBarH + 1, leftDockW, bodyBottom, theme.borderSubtle)
        renderLeftToolbox(
            hwnd = hwnd,
            hdc = memDc,
            theme = theme,
            fontSmall = fontSmall,
            fontNormal = fontNormal,
            fontBold = fontBold,
            toolPalette = toolPalettes.firstOrNull(),
            toolboxDockPanel = toolboxDockPanel,
            width = leftDockW,
            top = topBarH + 6,
            bottom = bodyBottom - 6
        )

        // 3. Center Top: Animation Timeline & Keyframe Curves (Positioned ABOVE the Canvas Viewport!)
        if (timelinePanel != null) {
            fillRectColor(memDc, centerLeft, timelineTop, centerRight, timelineBottom, theme.bgPanel)
            drawRectBorder(memDc, centerLeft, timelineTop, centerRight, timelineBottom, theme.borderSubtle)
            renderTopTimelineDock(
                hwnd = hwnd,
                hdc = memDc,
                theme = theme,
                fontSmall = fontSmall,
                fontNormal = fontNormal,
                fontBold = fontBold,
                timelinePanel = timelinePanel,
                left = centerLeft,
                top = timelineTop,
                right = centerRight,
                bottom = timelineBottom
            )
        }

        // 4. Center Below Timeline: Hybrid Vector + Raster Canvas Viewport
        fillRectColor(memDc, centerLeft, canvasTop, centerRight, canvasBottom, theme.bgCard)
        drawRectBorder(memDc, centerLeft, canvasTop, centerRight, canvasBottom, theme.borderSubtle)
        renderCenterCanvas2D(
            hwnd = hwnd,
            hdc = memDc,
            theme = theme,
            fontSmall = fontSmall,
            fontSmallBold = fontSmallBold,
            fontNormal = fontNormal,
            fontBold = fontBold,
            fontTitle = fontTitle,
            rootWidget = rootWidget,
            canvasWidget = canvasWidget,
            left = centerLeft + 6,
            top = canvasTop + 6,
            right = centerRight - 6,
            bottom = canvasBottom - 6
        )

        // 5. Right Collapsible Inspector Dock (AI Assistant, Shared Objects, Asset Library, Layers, Fonts, Filters)
        val rightX = clientW - rightDockW
        fillRectColor(memDc, rightX, topBarH + 1, clientW, bodyBottom, theme.bgPanel)
        drawLineColor(memDc, rightX, topBarH + 1, rightX, bodyBottom, theme.borderSubtle)
        renderRightInspectorDock(
            hwnd = hwnd,
            hdc = memDc,
            theme = theme,
            fontSmall = fontSmall,
            fontNormal = fontNormal,
            fontBold = fontBold,
            panels = rightPanels,
            left = rightX + 6,
            top = topBarH + 6,
            right = clientW - 6,
            bottom = bodyBottom - 6
        )

        // 6. Bottom Status Bar
        fillRectColor(memDc, 0, bodyBottom, clientW, clientH, theme.bgPanel)
        drawLineColor(memDc, 0, bodyBottom, clientW, bodyBottom, theme.borderSubtle)
        renderStudioStatusBar(memDc, theme, fontSmall, rootWidget, dockPanels, clientW, bodyBottom, clientH)

        // 7. Active Modal Dialog Overlay (if any Modal is open)
        renderActiveModalOverlay(hwnd, memDc, theme, fontSmall, fontNormal, fontBold, fontTitle, clientW, clientH)

        // 8. Native Hover Tooltip Popup Overlay
        if (hoverHwndKey == hwnd.toLong()) {
            renderHoveredTooltipOverlay(memDc, fontSmall, theme, clientW, clientH)
        }

        finishPaint()
    }

    private fun createSegoeFont(heightPx: Int, weight: Int): HFONT? =
        CreateFontW(
            heightPx, 0, 0, 0, weight, 0u, 0u, 0u,
            DEFAULT_CHARSET.toUInt(), OUT_DEFAULT_PRECIS.toUInt(),
            CLIP_DEFAULT_PRECIS.toUInt(), CLEARTYPE_QUALITY.toUInt(),
            DEFAULT_PITCH.toUInt(), "Segoe UI"
        )

    private fun widgetHasClass(widget: DesktopNativeWidget?, className: String): Boolean {
        if (widget == null) return false
        val impl = widget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
        if (impl != null) {
            return impl.hasClass(className)
        }
        val clsStr = (widget.attributes["bootstrapClass"] as? String).orEmpty()
        if (clsStr.isEmpty()) return false
        return clsStr.split(WHITESPACE_REGEX).contains(className)
    }

    private fun findWidgetByClass(root: DesktopNativeWidget?, className: String): DesktopNativeWidget? {
        if (root == null || !root.visible || root.disposed) return null
        if (widgetHasClass(root, className)) return root
        for (child in root.children) {
            val found = findWidgetByClass(child, className)
            if (found != null) return found
        }
        return null
    }

    private fun findWidgetsByClass(
        root: DesktopNativeWidget?,
        className: String,
        out: MutableList<DesktopNativeWidget> = mutableListOf()
    ): List<DesktopNativeWidget> {
        if (root == null || !root.visible || root.disposed) return out
        if (widgetHasClass(root, className)) {
            out.add(root)
        }
        for (child in root.children) {
            findWidgetsByClass(child, className, out)
        }
        return out
    }

    private fun extractWidgetText(widget: DesktopNativeWidget?): String {
        if (widget == null) return ""
        val impl = widget.attributes["nativeImpl"]
        if (impl is DesktopTextNativeImpl<*>) return impl.getText()
        if (impl is DesktopButtonNativeImpl<*>) return impl.getText()
        for (child in widget.children) {
            val t = extractWidgetText(child)
            if (t.isNotEmpty()) return t
        }
        return ""
    }

    private fun extractProgressBarValue(widget: DesktopNativeWidget?): Float {
        if (widget == null) return 0f
        val impl = widget.attributes["nativeImpl"]
        if (impl is DesktopProgressBarNativeImpl<*>) return impl.getProgress()
        for (child in widget.children) {
            val childImpl = child.attributes["nativeImpl"]
            if (childImpl is DesktopProgressBarNativeImpl<*>) return childImpl.getProgress()
        }
        return 0f
    }

    private fun findBootstrapCardPageRoot(rootWidget: DesktopNativeWidget?): DesktopNativeWidget? {
        if (rootWidget == null) return null
        if (findWidgetByClass(rootWidget, "menu") != null) return null
        val pageHost = findWidgetByClass(rootWidget, "page-host")
        if (pageHost != null && findWidgetByClass(pageHost, "card") != null) {
            return rootWidget
        }
        if (findWidgetByClass(rootWidget, "card-body") != null) {
            return rootWidget
        }
        return null
    }

    private fun collectTitleBarWidgetSet(vararg titleBars: TitleBar?): Set<DesktopNativeWidget> {
        val result = mutableSetOf<DesktopNativeWidget>()
        for (tb in titleBars) {
            if (tb == null) continue
            val allComponents = tb.leadingComponents + tb.centerComponents + tb.trailingComponents
            for (comp in allComponents) {
                val widget = (comp.nativeImpl as? DesktopBaseNativeImpl<*>)?.nativeWidget ?: continue
                widget.attributes["mountedInTitleBar"] = true
                result.add(widget)
            }
        }
        return result
    }

    private fun isWidgetMountedInTitleBar(
        widget: DesktopNativeWidget?,
        titleBarWidgets: Set<DesktopNativeWidget> = emptySet()
    ): Boolean {
        if (widget == null) return false
        if (widget in titleBarWidgets) return true
        if (widget.attributes["mountedInTitleBar"] == true) return true
        if (widget.attributes["data-mounted-in-titlebar"] == "true") return true
        return false
    }

    /**
     * Renders general non-dock applications using native Windows system theming.
     */
    private fun renderGeneralApplicationLayout(
        hwnd: HWND,
        hdc: HDC?,
        theme: SystemThemePalette,
        fontSmall: HFONT?,
        fontSmallBold: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        fontTitle: HFONT?,
        fontMetricBig: HFONT?,
        rootWidget: DesktopNativeWidget?,
        canvases: List<DesktopNativeWidget>,
        clientW: Int,
        clientH: Int,
        bodyTop: Int = 0
    ) {
        collectTitleBarWidgetSet(activeWindowImpl?.getTitleBarInstructions())
        if (canvases.isNotEmpty() && rootWidget?.attributes?.get("alienHostContainer") == "true") {
            renderGeneralCanvas2DViewport(
                hdc = hdc,
                canvasWidget = canvases.first(),
                clientW = clientW,
                clientH = clientH,
                bodyTop = bodyTop
            )
            return
        }

        val dataEditorRoot = findWidgetByClass(rootWidget, "data-editor-root")
            ?: findWidgetByClass(rootWidget, "lazy-grid-tree")
        if (dataEditorRoot != null) {
            renderDataEditorLazyGridTreeLayout(
                hwnd = hwnd,
                hdc = hdc,
                theme = theme,
                fontSmall = fontSmall,
                fontSmallBold = fontSmallBold,
                fontNormal = fontNormal,
                fontBold = fontBold,
                rootWidget = rootWidget,
                clientW = clientW,
                clientH = clientH
            )
            return
        }

        val appHeader = findWidgetByClass(rootWidget, "app-header")
        val metricsStrip = findWidgetByClass(rootWidget, "metrics-strip")
        val queuesContainer = findWidgetByClass(rootWidget, "queues-container")

        if (appHeader != null && metricsStrip != null && queuesContainer != null) {
            renderHeaderMetricsQueuesLayout(
                hdc = hdc,
                theme = theme,
                fontSmall = fontSmall,
                fontSmallBold = fontSmallBold,
                fontNormal = fontNormal,
                fontBold = fontBold,
                fontTitle = fontTitle,
                fontMetricBig = fontMetricBig,
                appHeader = appHeader,
                metricsStrip = metricsStrip,
                queuesContainer = queuesContainer,
                clientW = clientW,
                clientH = clientH
            )
            return
        }

        if (canvases.isNotEmpty()) {
            renderGeneralCanvas2DViewport(
                hdc = hdc,
                canvasWidget = canvases.first(),
                clientW = clientW,
                clientH = clientH,
                bodyTop = bodyTop
            )
            return
        }

        val cardPageRoot = findBootstrapCardPageRoot(rootWidget)
        if (cardPageRoot != null) {
            renderBootstrapCardPageLayout(
                hwnd = hwnd,
                hdc = hdc,
                theme = theme,
                fontSmall = fontSmall,
                fontSmallBold = fontSmallBold,
                fontNormal = fontNormal,
                fontBold = fontBold,
                fontTitle = fontTitle,
                fontMetricBig = fontMetricBig,
                cardPageRoot = cardPageRoot,
                clientW = clientW,
                clientH = clientH,
                bodyTop = bodyTop
            )
            return
        }

        val pageMenuNode = findWidgetByClass(rootWidget, "menu")
        if (pageMenuNode != null || widgetHasClass(rootWidget, "page-host")) {
            renderPageHostLayout(
                hwnd = hwnd,
                hdc = hdc,
                theme = theme,
                fontSmall = fontSmall,
                fontSmallBold = fontSmallBold,
                fontNormal = fontNormal,
                fontBold = fontBold,
                fontTitle = fontTitle,
                fontMetricBig = fontMetricBig,
                rootWidget = rootWidget,
                menuWidget = pageMenuNode ?: rootWidget,
                clientW = clientW,
                clientH = clientH
            )
            return
        }

        // Generic application fallback
        val buttons = mutableListOf<DesktopButtonNativeImpl<*>>()
        val checkboxes = mutableListOf<DesktopCheckboxNativeImpl<*>>()
        val selects = mutableListOf<DesktopSelectNativeImpl<*>>()
        val sliders = mutableListOf<DesktopRangeSliderNativeImpl<*>>()
        val texts = mutableListOf<DesktopTextNativeImpl<*>>()
        collectPanelControls(rootWidget, buttons, checkboxes, selects, sliders, texts)

        val leftW = if (canvases.isNotEmpty()) min(460, clientW / 2) else clientW - 32
        var y = 16
        val leftX = 16
        val rightX = leftX + leftW

        SelectObject(hdc, fontNormal)
        for (txt in texts.map { it.getText().trim() }.filter { it.isNotEmpty() }.distinct().take(10)) {
            if (y + 22 > clientH - 16) break
            drawTextInRect(hdc, txt, leftX, y, rightX, y + 20, theme.textSecondary)
            y += 22
        }
        y += 8

        if (buttons.isNotEmpty()) {
            val cols = if (leftW > 640) 4 else 2
            val gap = 8
            val btnW = (leftW - gap * (cols - 1)) / cols
            buttons.take(24).forEachIndexed { idx, btn ->
                val col = idx % cols
                val bx = leftX + col * (btnW + gap)
                if (y + 28 < clientH - 16) {
                    val iconSpec = btn.getIcon()
                    val labelStr = if (!iconSpec.isNullOrBlank()) btn.getText() else btn.getText().ifBlank { "Button ${idx + 1}" }
                    drawRoundedButton(
                        hdc = hdc,
                        font = fontNormal,
                        label = labelStr,
                        icon = iconSpec,
                        left = bx,
                        top = y,
                        right = bx + btnW,
                        bottom = y + 28,
                        bg = theme.bgCard,
                        border = theme.borderSubtle,
                        fg = theme.textPrimary,
                        radius = 2
                    )
                    val ry = y
                    hitRegions.add(
                        HitRegion(
                            left = bx,
                            top = ry,
                            right = bx + btnW,
                            bottom = ry + 28,
                            tooltip = btn.getTooltipText(),
                            onClick = { _, _ -> btn.dispatchClick() }
                        )
                    )
                }
                if (col == cols - 1 || idx == min(23, buttons.lastIndex)) {
                    y += 34
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun renderGeneralCanvas2DViewport(
        hdc: HDC?,
        canvasWidget: DesktopNativeWidget,
        clientW: Int,
        clientH: Int,
        bodyTop: Int = 0
    ) {
        val viewportH = (clientH - bodyTop).coerceAtLeast(1)
        activeCanvasImpl = canvasWidget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
        val surfWFn = canvasWidget.attributes["surfaceWidth"] as? (() -> Int)
        val surfHFn = canvasWidget.attributes["surfaceHeight"] as? (() -> Int)
        val resizeFn = canvasWidget.attributes["resizeSurface"] as? ((Int, Int) -> Unit)
        val vblankFn = canvasWidget.attributes["vblankDispatch"] as? ((Double?) -> Unit)
        val captureBgraFn = canvasWidget.attributes["captureBgra"] as? (() -> ByteArray)

        val curW = (surfWFn?.invoke() ?: clientW).coerceAtLeast(1)
        val curH = (surfHFn?.invoke() ?: viewportH).coerceAtLeast(1)
        if (curW != clientW || curH != viewportH) {
            runCatching { resizeFn?.invoke(clientW, viewportH) }
        }
        runCatching { vblankFn?.invoke(null) }

        val surfW = (surfWFn?.invoke() ?: clientW).coerceAtLeast(1)
        val surfH = (surfHFn?.invoke() ?: viewportH).coerceAtLeast(1)
        activeCanvasSurfaceSize = surfW to surfH
        canvasViewportRect = intArrayOf(0, bodyTop, clientW, clientH)

        val bgra = captureBgraFn?.invoke()
        if (bgra != null && bgra.size >= surfW * surfH * 4) {
            memScoped {
                val bmi = alloc<BITMAPINFO>()
                bmi.bmiHeader.biSize = sizeOf<BITMAPINFOHEADER>().toUInt()
                bmi.bmiHeader.biWidth = surfW
                bmi.bmiHeader.biHeight = -surfH
                bmi.bmiHeader.biPlanes = 1u
                bmi.bmiHeader.biBitCount = 32u
                bmi.bmiHeader.biCompression = BI_RGB.toUInt()

                SetStretchBltMode(hdc, HALFTONE)
                bgra.usePinned { pinned ->
                    StretchDIBits(
                        hdc,
                        0,
                        bodyTop,
                        clientW,
                        viewportH,
                        0,
                        0,
                        surfW,
                        surfH,
                        pinned.addressOf(0),
                        bmi.ptr,
                        DIB_RGB_COLORS.toUInt(),
                        SRCCOPY
                    )
                }
            }
        }
    }

    private fun findFirstScrollView(root: DesktopNativeWidget?): DesktopScrollViewNativeImpl<*>? {
        if (root == null || !root.visible || root.disposed) return null
        val impl = root.attributes["nativeImpl"]
        if (impl is DesktopScrollViewNativeImpl<*>) return impl
        for (child in root.children) {
            val found = findFirstScrollView(child)
            if (found != null) return found
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun getCachedDirectOrNestedTexts(widget: DesktopNativeWidget): List<DesktopTextNativeImpl<*>> {
        val childCount = widget.children.size
        val cachedCount = widget.attributes["cachedTextsChildCount"] as? Int
        val cachedList = widget.attributes["cachedTextsList"] as? List<DesktopTextNativeImpl<*>>
        if (cachedCount == childCount && cachedList != null) {
            return cachedList
        }
        val collected = collectDirectOrNestedTexts(widget)
        widget.attributes["cachedTextsChildCount"] = childCount
        widget.attributes["cachedTextsList"] = collected
        return collected
    }

    @Suppress("UNCHECKED_CAST")
    private fun getCachedFirstButton(widget: DesktopNativeWidget): DesktopButtonNativeImpl<*>? {
        val childCount = widget.children.size
        val cachedCount = widget.attributes["cachedFirstBtnChildCount"] as? Int
        if (cachedCount == childCount && widget.attributes.containsKey("cachedFirstBtnRef")) {
            return widget.attributes["cachedFirstBtnRef"] as? DesktopButtonNativeImpl<*>
        }
        val firstBtn = collectDirectOrNestedButtons(widget).firstOrNull()
        widget.attributes["cachedFirstBtnChildCount"] = childCount
        if (firstBtn != null) {
            widget.attributes["cachedFirstBtnRef"] = firstBtn
        } else {
            widget.attributes.remove("cachedFirstBtnRef")
        }
        return firstBtn
    }

    @Suppress("UNCHECKED_CAST")
    private fun renderBootstrapCardPageLayout(
        hwnd: HWND,
        hdc: HDC?,
        theme: SystemThemePalette,
        fontSmall: HFONT?,
        fontSmallBold: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        fontTitle: HFONT?,
        fontMetricBig: HFONT?,
        cardPageRoot: DesktopNativeWidget,
        clientW: Int,
        clientH: Int,
        bodyTop: Int = 0
    ) {
        val scrollViewImpl = findFirstScrollView(cardPageRoot)
        activeCardPageScrollViewImpl = scrollViewImpl
        if (scrollViewImpl != null) {
            val reqVer = scrollViewImpl.scrollRequestVersion
            if (reqVer != cardPageLastScrollRequestVersion) {
                cardPageLastScrollRequestVersion = reqVer
                cardPageScrollY = scrollViewImpl.currentScrollY()
            }
        }

        val pageHost = findWidgetByClass(cardPageRoot, "page-host") ?: cardPageRoot
        val currentPage = pageHost.children.lastOrNull { it.visible && !it.disposed } ?: pageHost
        val outerCard = currentPage.children.firstOrNull { widgetHasClass(it, "card") } ?: currentPage
        val cardBody = outerCard.children.firstOrNull { widgetHasClass(it, "card-body") } ?: outerCard

        val pageKey = "${currentPage.hashCode()}:${(currentPage.attributes["bootstrapClass"] as? String).orEmpty()}"
        if (pageKey != cardPageLastKey) {
            cardPageLastKey = pageKey
            cardPageScrollY = 0
            scrollViewImpl?.updateScrollPositionFromHost(0, 0)
        }

        // Outer Card Container (starts directly below the window titlebar)
        val outerMarginX = max(16, (clientW - 1220) / 2)
        val outerLeft = outerMarginX
        val outerRight = clientW - outerMarginX
        val outerTop = bodyTop + 12
        val outerBottom = clientH - 12

        drawRoundedRect(
            hdc = hdc,
            left = outerLeft,
            top = outerTop,
            right = outerRight,
            bottom = outerBottom,
            radius = 8,
            fillColor = theme.bgPanel,
            borderColor = theme.borderSubtle
        )

        val innerLeft = outerLeft + 20
        val scrollRailW = 18
        val innerRight = outerRight - 20 - scrollRailW
        val innerW = max(240, innerRight - innerLeft)
        var headerBottom = outerTop + 14

        // Extract direct header texts (h1, h5) from cardBody
        var mainHeading: String? = null
        var subHeading: String? = null
        for (child in cardBody.children) {
            val txt = (child.attributes["nativeImpl"] as? DesktopTextNativeImpl<*>)?.getText()?.trim()
            if (!txt.isNullOrEmpty()) {
                if (mainHeading == null) {
                    mainHeading = txt
                } else if (subHeading == null) {
                    subHeading = txt
                    break
                }
            }
        }
        if (!mainHeading.isNullOrEmpty()) {
            SelectObject(hdc, fontMetricBig)
            drawTextInRect(
                hdc = hdc,
                text = mainHeading,
                left = innerLeft,
                top = headerBottom,
                right = innerRight,
                bottom = headerBottom + 32,
                colorRef = theme.textPrimary,
                center = true
            )
            headerBottom += 34
        }
        if (!subHeading.isNullOrEmpty()) {
            SelectObject(hdc, fontNormal)
            drawTextInRect(
                hdc = hdc,
                text = subHeading,
                left = innerLeft,
                top = headerBottom,
                right = innerRight,
                bottom = headerBottom + 22,
                colorRef = theme.textSecondary,
                center = true
            )
            headerBottom += 26
        }
        drawLineColor(hdc, innerLeft, headerBottom, innerRight, headerBottom, theme.borderSubtle)
        val viewportTop = headerBottom + 10
        val viewportBottom = outerBottom - 12
        val viewportH = max(80, viewportBottom - viewportTop)

        val savedDc = SaveDC(hdc)
        IntersectClipRect(hdc, innerLeft - 4, viewportTop, innerRight + 4, viewportBottom)

        var contentY = viewportTop - cardPageScrollY
        val renderedWidgets = mutableSetOf<DesktopNativeWidget>()

        fun addClippedHitRegion(
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            widget: DesktopNativeWidget? = null,
            isTextInput: Boolean = false,
            onClick: ((Int, Int) -> Unit)? = null
        ) {
            val cTop = max(top, viewportTop)
            val cBottom = min(bottom, viewportBottom)
            if (cBottom > cTop) {
                hitRegions.add(
                    HitRegion(
                        left = left,
                        top = cTop,
                        right = right,
                        bottom = cBottom,
                        widget = widget,
                        isTextInput = isTextInput,
                        onClick = onClick
                    )
                )
            }
        }

        // Case A: Inner Cards Grid (Index, AssetManager, ItemMenu, DBMenu, Templates)
        val innerCards = mutableListOf<DesktopNativeWidget>()
        fun findInnerCards(node: DesktopNativeWidget) {
            if (!node.visible || node.disposed) return
            if (node !== outerCard && widgetHasClass(node, "card")) {
                innerCards.add(node)
                return
            }
            for (c in node.children) findInnerCards(c)
        }
        findInnerCards(cardBody)

        if (innerCards.isNotEmpty()) {
            val cols = when {
                innerW >= 780 -> 3
                innerW >= 500 -> 2
                else -> 1
            }
            val gap = 12
            val cardW = (innerW - gap * (cols - 1)) / cols
            val cardH = 128

            innerCards.forEachIndexed { idx, cardWidget ->
                val col = idx % cols
                val row = idx / cols
                val cx = innerLeft + col * (cardW + gap)
                val cy = contentY + row * (cardH + gap)

                if (cy + cardH >= viewportTop - 20 && cy <= viewportBottom + 20) {
                    drawRoundedRect(
                        hdc = hdc,
                        left = cx,
                        top = cy,
                        right = cx + cardW,
                        bottom = cy + cardH,
                        radius = 6,
                        fillColor = theme.bgCard,
                        borderColor = theme.borderSubtle
                    )
                    val cardTextImpls = getCachedDirectOrNestedTexts(cardWidget)
                    var cTitle = ""
                    var cDesc = ""
                    for (tImpl in cardTextImpls) {
                        val str = tImpl.getText().trim()
                        if (str.isNotEmpty()) {
                            if (cTitle.isEmpty()) {
                                cTitle = str
                            } else if (cDesc.isEmpty()) {
                                cDesc = str
                                break
                            }
                        }
                    }
                    if (cTitle.isNotEmpty()) {
                        SelectObject(hdc, fontBold)
                        drawTextInRect(
                            hdc = hdc,
                            text = cTitle,
                            left = cx + 10,
                            top = cy + 10,
                            right = cx + cardW - 10,
                            bottom = cy + 32,
                            colorRef = theme.textPrimary,
                            center = true
                        )
                    }
                    if (cDesc.isNotEmpty()) {
                        SelectObject(hdc, fontSmall)
                        drawTextInRect(
                            hdc = hdc,
                            text = cDesc,
                            left = cx + 10,
                            top = cy + 36,
                            right = cx + cardW - 10,
                            bottom = cy + 78,
                            colorRef = theme.textSecondary,
                            center = true
                        )
                    }
                    val cardBtn = getCachedFirstButton(cardWidget)
                    if (cardBtn != null) {
                        val btnTop = cy + cardH - 38
                        val btnBottom = cy + cardH - 10
                        drawRoundedButton(
                            hdc = hdc,
                            font = fontSmallBold,
                            label = cardBtn.getText().ifBlank { "Open" },
                            left = cx + 12,
                            top = btnTop,
                            right = cx + cardW - 12,
                            bottom = btnBottom,
                            bg = theme.accentBlue,
                            border = theme.accentBlueBorder,
                            fg = rgb(255, 255, 255),
                            radius = 5,
                            widget = cardBtn.nativeWidget,
                            hwndForHover = hwnd
                        )
                        addClippedHitRegion(
                            left = cx + 12,
                            top = btnTop,
                            right = cx + cardW - 12,
                            bottom = btnBottom,
                            widget = cardBtn.nativeWidget,
                            onClick = { _, _ -> cardBtn.dispatchClick() }
                        )
                    }
                }
            }
            val totalRows = (innerCards.size + cols - 1) / cols
            contentY += totalRows * (cardH + gap)
        }

        // Case B: DataSheet Table (.data-sheet)
        val dataSheetNode = if (innerCards.isEmpty()) findWidgetByClass(cardBody, "data-sheet") else null
        if (dataSheetNode != null) {
            renderedWidgets.add(dataSheetNode)
            for (child in cardBody.children) {
                if (child === dataSheetNode) break
                val btns = collectDirectOrNestedButtons(child)
                if (btns.isNotEmpty()) {
                    renderedWidgets.add(child)
                    var bx = innerLeft
                    for (btn in btns) {
                        val label = btn.getText().trim()
                        if (label.isEmpty()) continue
                        val btnW = max(110, label.length * 8 + 24)
                        val isCreate = label.startsWith("Create", ignoreCase = true)
                        val drawLeft = if (isCreate && btns.size <= 2) max(bx, innerRight - btnW) else bx
                        drawRoundedButton(
                            hdc = hdc,
                            font = fontSmallBold,
                            label = label,
                            left = drawLeft,
                            top = contentY,
                            right = drawLeft + btnW,
                            bottom = contentY + 30,
                            bg = if (isCreate) theme.accentBlue else theme.bgCard,
                            border = if (isCreate) theme.accentBlueBorder else theme.borderSubtle,
                            fg = if (isCreate) rgb(255, 255, 255) else theme.textPrimary,
                            radius = 5,
                            widget = btn.nativeWidget,
                            hwndForHover = hwnd
                        )
                        addClippedHitRegion(
                            left = drawLeft,
                            top = contentY,
                            right = drawLeft + btnW,
                            bottom = contentY + 30,
                            widget = btn.nativeWidget,
                            onClick = { _, _ -> btn.dispatchClick() }
                        )
                        bx += btnW + 8
                    }
                    contentY += 38
                }
            }

            val headerNode = dataSheetNode.children.firstOrNull { widgetHasClass(it, "header") }
            val contentNode = dataSheetNode.children.firstOrNull { widgetHasClass(it, "content") }
            val headCells = headerNode?.children.orEmpty()
            val colCount = max(1, headCells.size)
            val colW = innerW / colCount
            val rowH = 34

            if (headCells.isNotEmpty()) {
                fillRectColor(hdc, innerLeft, contentY, innerRight, contentY + rowH, theme.bgElevated)
                drawRectBorder(hdc, innerLeft, contentY, innerRight, contentY + rowH, theme.borderSubtle)
                headCells.forEachIndexed { cIdx, cellWidget ->
                    val cLeft = innerLeft + cIdx * colW
                    val cRight = if (cIdx == colCount - 1) innerRight else cLeft + colW
                    val sortBtn = getCachedFirstButton(cellWidget)
                    val titleStr = sortBtn?.getText()?.ifBlank { extractWidgetText(cellWidget) }
                        ?: extractWidgetText(cellWidget)
                    SelectObject(hdc, fontSmallBold)
                    drawTextInRect(
                        hdc = hdc,
                        text = titleStr,
                        left = cLeft + 8,
                        top = contentY + 6,
                        right = cRight - 6,
                        bottom = contentY + rowH - 6,
                        colorRef = theme.textPrimary
                    )
                    if (sortBtn != null) {
                        addClippedHitRegion(
                            left = cLeft,
                            top = contentY,
                            right = cRight,
                            bottom = contentY + rowH,
                            widget = sortBtn.nativeWidget,
                            onClick = { _, _ -> sortBtn.dispatchClick() }
                        )
                    }
                }
                contentY += rowH
            }

            val dataRows = contentNode?.children.orEmpty()
            dataRows.forEachIndexed { rIdx, rowWidget ->
                val ry = contentY + rIdx * rowH
                if (ry + rowH >= viewportTop - 20 && ry <= viewportBottom + 20) {
                    val rowBg = if (rIdx % 2 == 0) theme.bgCard else theme.bgBase
                    fillRectColor(hdc, innerLeft, ry, innerRight, ry + rowH, rowBg)
                    drawLineColor(hdc, innerLeft, ry + rowH, innerRight, ry + rowH, theme.borderSubtle)

                    val rowCells = rowWidget.children
                    rowCells.forEachIndexed { cIdx, cellWidget ->
                        val cLeft = if (rowCells.size == 1) innerLeft else innerLeft + cIdx * colW
                        val cRight = if (rowCells.size == 1 || cIdx == colCount - 1) innerRight else cLeft + colW
                        val cellBtn = getCachedFirstButton(cellWidget)
                        if (cellBtn != null) {
                            drawRoundedButton(
                                hdc = hdc,
                                font = fontSmallBold,
                                label = cellBtn.getText().ifBlank { "View / Edit" },
                                left = cLeft + 6,
                                top = ry + 4,
                                right = cRight - 6,
                                bottom = ry + rowH - 4,
                                bg = theme.accentBlue,
                                border = theme.accentBlueBorder,
                                fg = rgb(255, 255, 255),
                                radius = 4,
                                widget = cellBtn.nativeWidget,
                                hwndForHover = hwnd
                            )
                            addClippedHitRegion(
                                left = cLeft + 6,
                                top = ry + 4,
                                right = cRight - 6,
                                bottom = ry + rowH - 4,
                                widget = cellBtn.nativeWidget,
                                onClick = { _, _ -> cellBtn.dispatchClick() }
                            )
                        } else {
                            val cellText = extractWidgetText(cellWidget)
                            SelectObject(hdc, fontSmall)
                            drawTextInRect(
                                hdc = hdc,
                                text = cellText,
                                left = cLeft + 8,
                                top = ry + 6,
                                right = cRight - 6,
                                bottom = ry + rowH - 6,
                                colorRef = theme.textPrimary
                            )
                        }
                    }
                }
            }
            contentY += dataRows.size * rowH + 12

            for (child in cardBody.children) {
                if (child in renderedWidgets || child === dataSheetNode) continue
                val pagerBtns = collectDirectOrNestedButtons(child)
                if (pagerBtns.isNotEmpty()) {
                    renderedWidgets.add(child)
                    var px = innerLeft
                    for (pBtn in pagerBtns) {
                        val pLabel = pBtn.getText().trim()
                        val pW = max(36, pLabel.length * 8 + 18)
                        val enabled = pBtn.isEnabled()
                        drawRoundedButton(
                            hdc = hdc,
                            font = fontSmallBold,
                            label = pLabel,
                            left = px,
                            top = contentY,
                            right = px + pW,
                            bottom = contentY + 28,
                            bg = if (!enabled) theme.accentBlue else theme.bgCard,
                            border = if (!enabled) theme.accentBlueBorder else theme.borderSubtle,
                            fg = if (!enabled) rgb(255, 255, 255) else theme.textPrimary,
                            radius = 4,
                            widget = if (enabled) pBtn.nativeWidget else null,
                            hwndForHover = if (enabled) hwnd else null
                        )
                        if (enabled) {
                            addClippedHitRegion(
                                left = px,
                                top = contentY,
                                right = px + pW,
                                bottom = contentY + 28,
                                widget = pBtn.nativeWidget,
                                onClick = { _, _ -> pBtn.dispatchClick() }
                            )
                        }
                        px += pW + 6
                    }
                    contentY += 36
                }
            }
        }

        // Case C: Form Editor / Remaining Controls (MetaEditor, Alignment, etc.)
        if (innerCards.isEmpty() && dataSheetNode == null) {
            val alertNode = findWidgetByClass(cardBody, "alert")
            if (alertNode != null && alertNode.visible) {
                val alertMsg = extractWidgetText(alertNode).trim()
                if (alertMsg.isNotEmpty()) {
                    val isDanger = widgetHasClass(alertNode, "alert-danger")
                    drawRoundedRect(
                        hdc = hdc,
                        left = innerLeft,
                        top = contentY,
                        right = innerRight,
                        bottom = contentY + 34,
                        radius = 5,
                        fillColor = if (isDanger) theme.dangerBg else theme.accentBlueSoftBg,
                        borderColor = if (isDanger) theme.dangerBorder else theme.accentBlueBorder
                    )
                    SelectObject(hdc, fontSmallBold)
                    drawTextInRect(
                        hdc = hdc,
                        text = alertMsg,
                        left = innerLeft + 12,
                        top = contentY + 7,
                        right = innerRight - 12,
                        bottom = contentY + 28,
                        colorRef = if (isDanger) theme.dangerText else theme.accentBlueText
                    )
                    contentY += 42
                }
            }

            class FormFieldRenderItem(
                val label: String,
                val textInput: DesktopTextInputNativeImpl<*>?,
                val select: DesktopSelectNativeImpl<*>?,
                val checkbox: DesktopCheckboxNativeImpl<*>?,
                val buttons: List<DesktopButtonNativeImpl<*>>
            )

            val fieldItems = mutableListOf<FormFieldRenderItem>()
            val actionButtons = mutableListOf<DesktopButtonNativeImpl<*>>()

            fun collectFormNodes(node: DesktopNativeWidget) {
                if (!node.visible || node.disposed || widgetHasClass(node, "alert")) return
                val isFieldCol = widgetHasClass(node, "col-lg-4") ||
                    widgetHasClass(node, "col-lg-6") ||
                    widgetHasClass(node, "form-group")
                if (isFieldCol) {
                    val tInput = findFirstTextInput(node)
                    val sel = findFirstSelect(node)
                    val chk = findFirstCheckbox(node)
                    val btns = collectDirectOrNestedButtons(node)
                    val lbl = getCachedDirectOrNestedTexts(node)
                        .map { it.getText().trim() }
                        .firstOrNull { it.isNotEmpty() } ?: ""
                    if (tInput != null || sel != null || chk != null || btns.isNotEmpty()) {
                        fieldItems.add(
                            FormFieldRenderItem(
                                label = lbl,
                                textInput = tInput,
                                select = sel,
                                checkbox = chk,
                                buttons = btns
                            )
                        )
                        return
                    }
                }
                val directBtn = node.attributes["nativeImpl"] as? DesktopButtonNativeImpl<*>
                if (directBtn != null) {
                    actionButtons.add(directBtn)
                    return
                }
                for (c in node.children) collectFormNodes(c)
            }
            collectFormNodes(cardBody)

            if (fieldItems.isNotEmpty()) {
                val cols = if (innerW >= 760) 3 else 2
                val gap = 12
                val colW = (innerW - gap * (cols - 1)) / cols
                val fieldH = 58

                fieldItems.forEachIndexed { idx, item ->
                    val col = idx % cols
                    val row = idx / cols
                    val fx = innerLeft + col * (colW + gap)
                    val fy = contentY + row * (fieldH + 8)

                    if (fy + fieldH >= viewportTop - 20 && fy <= viewportBottom + 20) {
                        SelectObject(hdc, fontSmallBold)
                        drawTextInRect(
                            hdc = hdc,
                            text = item.label,
                            left = fx,
                            top = fy,
                            right = fx + colW,
                            bottom = fy + 18,
                            colorRef = theme.textSecondary
                        )
                        val ctrlTop = fy + 22
                        val ctrlBottom = fy + 52
                        when {
                            item.textInput != null -> {
                                val tIn = item.textInput
                                val isFocused = focusedTextInput === tIn
                                val valStr = tIn.getValue()
                                val disp = if (isFocused) "$valStr|" else valStr
                                drawRoundedRect(
                                    hdc = hdc,
                                    left = fx,
                                    top = ctrlTop,
                                    right = fx + colW,
                                    bottom = ctrlBottom,
                                    radius = 4,
                                    fillColor = theme.bgBase,
                                    borderColor = if (isFocused) theme.accentBlueBorder else theme.borderSubtle
                                )
                                SelectObject(hdc, fontSmall)
                                drawTextInRect(
                                    hdc = hdc,
                                    text = disp,
                                    left = fx + 8,
                                    top = ctrlTop + 5,
                                    right = fx + colW - 8,
                                    bottom = ctrlBottom - 4,
                                    colorRef = theme.textPrimary
                                )
                                addClippedHitRegion(
                                    left = fx,
                                    top = ctrlTop,
                                    right = fx + colW,
                                    bottom = ctrlBottom,
                                    widget = tIn.nativeWidget,
                                    isTextInput = true
                                )
                            }
                            item.select != null -> {
                                val sel = item.select
                                val selectedVal = sel.getSelectedValue() ?: ""
                                val selectedLabel = sel.getOptions()
                                    .firstOrNull { it.first == selectedVal }?.second
                                    ?: selectedVal.ifBlank { "Select..." }
                                drawRoundedButton(
                                    hdc = hdc,
                                    font = fontSmall,
                                    label = "$selectedLabel  ▾",
                                    left = fx,
                                    top = ctrlTop,
                                    right = fx + colW,
                                    bottom = ctrlBottom,
                                    bg = theme.bgCard,
                                    border = theme.borderSubtle,
                                    fg = theme.textPrimary,
                                    radius = 4,
                                    widget = sel.nativeWidget,
                                    hwndForHover = hwnd
                                )
                                addClippedHitRegion(
                                    left = fx,
                                    top = ctrlTop,
                                    right = fx + colW,
                                    bottom = ctrlBottom,
                                    widget = sel.nativeWidget,
                                    onClick = { mx, my -> openSelectPopupMenu(hwnd, sel, mx, my) }
                                )
                            }
                            item.checkbox != null -> {
                                val chk = item.checkbox
                                val checked = chk.isChecked()
                                drawRoundedButton(
                                    hdc = hdc,
                                    font = fontSmallBold,
                                    label = if (checked) "☑ Enabled" else "☐ Disabled",
                                    left = fx,
                                    top = ctrlTop,
                                    right = fx + colW,
                                    bottom = ctrlBottom,
                                    bg = if (checked) theme.accentBlueSoftBg else theme.bgCard,
                                    border = if (checked) theme.accentBlueBorder else theme.borderSubtle,
                                    fg = theme.textPrimary,
                                    radius = 4,
                                    widget = chk.nativeWidget,
                                    hwndForHover = hwnd
                                )
                                addClippedHitRegion(
                                    left = fx,
                                    top = ctrlTop,
                                    right = fx + colW,
                                    bottom = ctrlBottom,
                                    widget = chk.nativeWidget,
                                    onClick = { _, _ -> chk.setChecked(!chk.isChecked()) }
                                )
                            }
                            item.buttons.isNotEmpty() -> {
                                val btn = item.buttons.first()
                                drawRoundedButton(
                                    hdc = hdc,
                                    font = fontSmallBold,
                                    label = btn.getText().ifBlank { item.label },
                                    left = fx,
                                    top = ctrlTop,
                                    right = fx + colW,
                                    bottom = ctrlBottom,
                                    bg = theme.bgCard,
                                    border = theme.borderSubtle,
                                    fg = theme.textPrimary,
                                    radius = 4,
                                    widget = btn.nativeWidget,
                                    hwndForHover = hwnd
                                )
                                addClippedHitRegion(
                                    left = fx,
                                    top = ctrlTop,
                                    right = fx + colW,
                                    bottom = ctrlBottom,
                                    widget = btn.nativeWidget,
                                    onClick = { _, _ -> btn.dispatchClick() }
                                )
                            }
                        }
                    }
                }
                val totalRows = (fieldItems.size + cols - 1) / cols
                contentY += totalRows * (fieldH + 8) + 8
            }

            if (actionButtons.isNotEmpty()) {
                val cols = if (actionButtons.size == 9) 3 else min(4, max(1, actionButtons.size))
                val gap = 10
                val btnW = min(220, (innerW - gap * (cols - 1)) / cols)
                actionButtons.forEachIndexed { idx, btn ->
                    val col = idx % cols
                    val row = idx / cols
                    val bx = innerLeft + col * (btnW + gap)
                    val by = contentY + row * 38
                    val label = btn.getText().ifBlank { "Action ${idx + 1}" }
                    val isPrimary = label.equals("Save", ignoreCase = true) ||
                        label.equals("Create", ignoreCase = true) ||
                        widgetHasClass(btn.nativeWidget, "btn-primary")
                    drawRoundedButton(
                        hdc = hdc,
                        font = fontSmallBold,
                        label = label,
                        left = bx,
                        top = by,
                        right = bx + btnW,
                        bottom = by + 30,
                        bg = if (isPrimary) theme.accentBlue else theme.bgCard,
                        border = if (isPrimary) theme.accentBlueBorder else theme.borderSubtle,
                        fg = if (isPrimary) rgb(255, 255, 255) else theme.textPrimary,
                        radius = 5,
                        widget = btn.nativeWidget,
                        hwndForHover = hwnd
                    )
                    addClippedHitRegion(
                        left = bx,
                        top = by,
                        right = bx + btnW,
                        bottom = by + 30,
                        widget = btn.nativeWidget,
                        onClick = { _, _ -> btn.dispatchClick() }
                    )
                }
                val totalBtnRows = (actionButtons.size + cols - 1) / cols
                contentY += totalBtnRows * 38 + 8
            }
        }

        val totalContentH = (contentY + cardPageScrollY) - viewportTop
        cardPageMaxScrollY = max(0, totalContentH - viewportH)
        if (cardPageScrollY > cardPageMaxScrollY) {
            cardPageScrollY = cardPageMaxScrollY
            scrollViewImpl?.updateScrollPositionFromHost(0, cardPageScrollY)
        }

        RestoreDC(hdc, savedDc)

        // Interactive Vertical Scrollbar Rail, Arrow Buttons, & Thumb with Hover/Active State
        if (cardPageMaxScrollY > 0) {
            val railLeft = outerRight - 20
            val railRight = outerRight - 6
            cardPageScrollRailRect = intArrayOf(railLeft, viewportTop, railRight, viewportBottom)

            val showArrowButtons = viewportH >= 88
            val arrowBtnH = if (showArrowButtons) 18 else 0
            val trackTop = if (showArrowButtons) viewportTop + arrowBtnH + 2 else viewportTop
            val trackBottom = if (showArrowButtons) viewportBottom - arrowBtnH - 2 else viewportBottom
            val trackH = max(24, trackBottom - trackTop)

            val isRailHovered = isDraggingCardScrollbarThumb ||
                hoveredWidget === cardScrollbarRailWidget ||
                hoveredWidget === cardScrollbarThumbWidget ||
                isRectHovered(hwnd, railLeft, viewportTop, railRight, viewportBottom)

            drawRoundedRect(
                hdc = hdc,
                left = railLeft,
                top = viewportTop,
                right = railRight,
                bottom = viewportBottom,
                radius = 5,
                fillColor = if (isRailHovered) theme.bgElevated else theme.bgBase,
                borderColor = if (isRailHovered) theme.borderStrong else theme.borderSubtle
            )

            if (showArrowButtons) {
                val upBtnTop = viewportTop + 1
                val upBtnBottom = viewportTop + arrowBtnH
                drawRoundedButton(
                    hdc = hdc,
                    font = fontSmallBold,
                    label = "▲",
                    left = railLeft + 1,
                    top = upBtnTop,
                    right = railRight - 1,
                    bottom = upBtnBottom,
                    bg = theme.bgCard,
                    border = theme.borderSubtle,
                    fg = theme.textPrimary,
                    radius = 3,
                    widget = cardScrollbarUpBtnWidget,
                    hwndForHover = hwnd
                )
                hitRegions.add(
                    HitRegion(
                        left = railLeft,
                        top = viewportTop,
                        right = railRight,
                        bottom = upBtnBottom,
                        tooltip = "Scroll up",
                        widget = cardScrollbarUpBtnWidget,
                        onClick = { _, _ ->
                            val next = (cardPageScrollY - 96).coerceIn(0, cardPageMaxScrollY)
                            cardPageScrollY = next
                            scrollViewImpl?.updateScrollPositionFromHost(0, next)
                        }
                    )
                )

                val downBtnTop = viewportBottom - arrowBtnH
                val downBtnBottom = viewportBottom - 1
                drawRoundedButton(
                    hdc = hdc,
                    font = fontSmallBold,
                    label = "▼",
                    left = railLeft + 1,
                    top = downBtnTop,
                    right = railRight - 1,
                    bottom = downBtnBottom,
                    bg = theme.bgCard,
                    border = theme.borderSubtle,
                    fg = theme.textPrimary,
                    radius = 3,
                    widget = cardScrollbarDownBtnWidget,
                    hwndForHover = hwnd
                )
                hitRegions.add(
                    HitRegion(
                        left = railLeft,
                        top = downBtnTop,
                        right = railRight,
                        bottom = viewportBottom,
                        tooltip = "Scroll down",
                        widget = cardScrollbarDownBtnWidget,
                        onClick = { _, _ ->
                            val next = (cardPageScrollY + 96).coerceIn(0, cardPageMaxScrollY)
                            cardPageScrollY = next
                            scrollViewImpl?.updateScrollPositionFromHost(0, next)
                        }
                    )
                )
            }

            val thumbH = max(
                32,
                (trackH.toFloat() * (viewportH.toFloat() / max(1, totalContentH).toFloat())).roundToInt()
            ).coerceAtMost(trackH)
            val trackRange = max(1, trackH - thumbH)
            val thumbTop = trackTop +
                ((cardPageScrollY.toFloat() / cardPageMaxScrollY.toFloat()) * trackRange.toFloat())
                    .roundToInt()
                    .coerceIn(0, trackRange)
            val thumbBottom = thumbTop + thumbH
            cardPageScrollThumbRect = intArrayOf(railLeft, thumbTop, railRight, thumbBottom)

            val isThumbDragging = isDraggingCardScrollbarThumb
            val isThumbHovered = isThumbDragging ||
                hoveredWidget === cardScrollbarThumbWidget ||
                isRectHovered(hwnd, railLeft - 1, thumbTop, railRight + 1, thumbBottom)

            val thumbFill = when {
                isThumbDragging -> lightenColorRef(theme.accentBlue, -18)
                isThumbHovered -> lightenColorRef(theme.accentBlue, 28)
                else -> theme.accentBlue
            }
            val thumbBorder = when {
                isThumbDragging -> rgb(255, 255, 255)
                isThumbHovered -> lightenColorRef(theme.accentBlueBorder, 40)
                else -> theme.accentBlueBorder
            }
            val thumbInset = if (isThumbHovered || isThumbDragging) 1 else 2
            drawRoundedRect(
                hdc = hdc,
                left = railLeft + thumbInset,
                top = thumbTop,
                right = railRight - thumbInset,
                bottom = thumbBottom,
                radius = 4,
                fillColor = thumbFill,
                borderColor = thumbBorder
            )
            if (thumbH >= 28) {
                val gripMidY = (thumbTop + thumbBottom) / 2
                val gripLeft = railLeft + 4
                val gripRight = railRight - 4
                val gripColor = if (isThumbHovered || isThumbDragging) {
                    rgb(255, 255, 255)
                } else {
                    lightenColorRef(theme.accentBlue, 45)
                }
                drawLineColor(hdc, gripLeft, gripMidY - 3, gripRight, gripMidY - 3, gripColor)
                drawLineColor(hdc, gripLeft, gripMidY, gripRight, gripMidY, gripColor)
                drawLineColor(hdc, gripLeft, gripMidY + 3, gripRight, gripMidY + 3, gripColor)
            }

            val maxScrollSnapshot = cardPageMaxScrollY
            val applyScrollFromMouseY: (Int) -> Unit = { mouseY ->
                val newThumbTop = (mouseY - cardScrollbarDragGrabOffsetY).coerceIn(trackTop, trackTop + trackRange)
                val frac = (newThumbTop - trackTop).toFloat() / trackRange.toFloat()
                val nextScroll = (frac * maxScrollSnapshot.toFloat()).roundToInt().coerceIn(0, maxScrollSnapshot)
                if (nextScroll != cardPageScrollY) {
                    cardPageScrollY = nextScroll
                    scrollViewImpl?.updateScrollPositionFromHost(0, nextScroll)
                }
            }

            // Track hit region (for clicking above/below the thumb to jump & drag)
            hitRegions.add(
                HitRegion(
                    left = railLeft - 2,
                    top = trackTop,
                    right = railRight + 2,
                    bottom = trackBottom,
                    widget = cardScrollbarRailWidget,
                    onClick = { _, my ->
                        isDraggingCardScrollbarThumb = true
                        cardScrollbarDragGrabOffsetY = if (my in thumbTop..thumbBottom) {
                            my - thumbTop
                        } else {
                            thumbH / 2
                        }
                        applyScrollFromMouseY(my)
                    },
                    onDrag = { _, my ->
                        applyScrollFromMouseY(my)
                    }
                )
            )

            // Thumb hit region (on top of track region so hovering the thumb triggers hover state)
            hitRegions.add(
                HitRegion(
                    left = railLeft - 2,
                    top = thumbTop,
                    right = railRight + 2,
                    bottom = thumbBottom,
                    widget = cardScrollbarThumbWidget,
                    onClick = { _, my ->
                        isDraggingCardScrollbarThumb = true
                        cardScrollbarDragGrabOffsetY = (my - thumbTop).coerceIn(0, thumbH)
                    },
                    onDrag = { _, my ->
                        applyScrollFromMouseY(my)
                    }
                )
            )
        } else {
            cardPageScrollRailRect = intArrayOf(-1, -1, -1, -1)
            cardPageScrollThumbRect = intArrayOf(-1, -1, -1, -1)
        }

        // Modal Overlay
        val alertModal = cardPageRoot.children.firstOrNull { widgetHasClass(it, "modal") && it.visible }
        if (alertModal != null) {
            val modalW = min(460, clientW - 60)
            val modalH = 200
            val mLeft = (clientW - modalW) / 2
            val mTop = (clientH - modalH) / 2
            val mRight = mLeft + modalW
            val mBottom = mTop + modalH
            drawRoundedRect(
                hdc = hdc,
                left = mLeft,
                top = mTop,
                right = mRight,
                bottom = mBottom,
                radius = 8,
                fillColor = theme.bgElevated,
                borderColor = theme.accentBlueBorder
            )
            val modalTexts = collectDirectOrNestedTexts(alertModal).map { it.getText().trim() }.filter { it.isNotEmpty() }
            val mTitle = modalTexts.getOrNull(0) ?: "Alert"
            val mBody = modalTexts.drop(1).joinToString("\n")
            SelectObject(hdc, fontTitle)
            drawTextInRect(hdc, mTitle, mLeft + 16, mTop + 14, mRight - 16, mTop + 40, theme.textPrimary)
            SelectObject(hdc, fontNormal)
            drawTextInRect(hdc, mBody, mLeft + 16, mTop + 48, mRight - 16, mBottom - 52, theme.textSecondary)

            val modalBtns = collectDirectOrNestedButtons(alertModal)
            var mbX = mRight - 16
            for (mBtn in modalBtns.reversed()) {
                val lbl = mBtn.getText().ifBlank { "Okay" }
                val bw = max(80, lbl.length * 8 + 20)
                mbX -= bw
                drawRoundedButton(
                    hdc = hdc,
                    font = fontSmallBold,
                    label = lbl,
                    left = mbX,
                    top = mBottom - 42,
                    right = mbX + bw,
                    bottom = mBottom - 12,
                    bg = theme.accentBlue,
                    border = theme.accentBlueBorder,
                    fg = rgb(255, 255, 255),
                    radius = 5,
                    widget = mBtn.nativeWidget,
                    hwndForHover = hwnd
                )
                val capL = mbX
                val capR = mbX + bw
                hitRegions.add(
                    HitRegion(
                        left = capL,
                        top = mBottom - 42,
                        right = capR,
                        bottom = mBottom - 12,
                        widget = mBtn.nativeWidget,
                        onClick = { _, _ -> mBtn.dispatchClick() }
                    )
                )
                mbX -= 8
            }
        }
    }

    private class PageActionItem(
        val label: String,
        val widget: DesktopNativeWidget,
        val impl: DesktopBaseNativeImpl<*>,
        val isActive: Boolean,
        val isDisabled: Boolean,
        val isPrimary: Boolean
    )

    private fun isCarouselOptionWidget(widget: DesktopNativeWidget): Boolean {
        if (widgetHasClass(widget, "carousel-item")) return true
        val impl = widget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*> ?: return false
        if (impl is DesktopButtonNativeImpl<*> || widgetHasClass(widget, "btn")) return false
        return impl.hasClickListener() && collectDirectOrNestedTexts(widget).size >= 2
    }

    private fun findWidgetByExactText(root: DesktopNativeWidget?, targetTexts: Set<String>): DesktopNativeWidget? {
        if (root == null || !root.visible || root.disposed) return null
        val impl = root.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
        if (impl != null && (impl is DesktopButtonNativeImpl<*> || impl.hasClickListener() || widgetHasClass(root, "btn"))) {
            val txt = extractWidgetText(root).trim()
            if (txt in targetTexts) return root
        }
        for (child in root.children) {
            val found = findWidgetByExactText(child, targetTexts)
            if (found != null) return found
        }
        return null
    }

    private fun collectCarouselOptionWidgets(
        root: DesktopNativeWidget?,
        out: MutableList<DesktopNativeWidget> = mutableListOf()
    ): List<DesktopNativeWidget> {
        if (root == null || !root.visible || root.disposed) return out
        if (isCarouselOptionWidget(root)) {
            out.add(root)
            return out
        }
        for (child in root.children) {
            collectCarouselOptionWidgets(child, out)
        }
        return out
    }

    private fun collectPageActionItems(
        root: DesktopNativeWidget?,
        out: MutableList<PageActionItem> = mutableListOf()
    ): List<PageActionItem> {
        if (root == null || !root.visible || root.disposed) return out
        val impl = root.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
        if (impl != null) {
            val isBtnClass = widgetHasClass(root, "btn") ||
                widgetHasClass(root, "list-group-item")
            val isClickable = impl is DesktopButtonNativeImpl<*> || impl.hasClickListener() || isBtnClass
            if (isClickable && !isCarouselOptionWidget(root)) {
                val label = extractWidgetText(root).trim()
                if (label.isNotEmpty()) {
                    val active = widgetHasClass(root, "btn-active") || widgetHasClass(root, "active")
                    val disabled = !impl.isEnabled() || widgetHasClass(root, "disabled")
                    val primary = widgetHasClass(root, "btn-primary") ||
                        widgetHasClass(root, "continue") ||
                        widgetHasClass(root, "create")
                    out.add(
                        PageActionItem(
                            label = label,
                            widget = root,
                            impl = impl,
                            isActive = active,
                            isDisabled = disabled,
                            isPrimary = primary
                        )
                    )
                    return out
                }
            }
        }
        for (child in root.children) {
            collectPageActionItems(child, out)
        }
        return out
    }

    private fun renderPageHostLayout(
        hwnd: HWND,
        hdc: HDC?,
        theme: SystemThemePalette,
        fontSmall: HFONT?,
        fontSmallBold: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        fontTitle: HFONT?,
        fontMetricBig: HFONT?,
        rootWidget: DesktopNativeWidget?,
        menuWidget: DesktopNativeWidget?,
        clientW: Int,
        clientH: Int
    ) {
        val activePageWidget = rootWidget?.children?.lastOrNull {
            it.visible && !it.disposed && !widgetHasClass(it, "loader")
        } ?: menuWidget
        val activeMenu = findWidgetByClass(activePageWidget, "menu") ?: menuWidget ?: rootWidget ?: return

        val stepNavNode = findWidgetByClass(activeMenu, "step-nav")
            ?: activeMenu.children.firstOrNull { child ->
                val items = collectPageActionItems(child)
                items.size >= 3 && items.any { it.isActive }
            }
        val isWideStepWizard = stepNavNode != null || findWidgetByClass(activeMenu, "step-content") != null
        val cardW = if (isWideStepWizard) {
            (clientW - 64).coerceIn(720, 1180)
        } else {
            (clientW - 120).coerceIn(420, 560)
        }
        val cardH = if (isWideStepWizard) {
            (clientH - 56).coerceIn(480, 760)
        } else {
            (clientH - 120).coerceIn(340, 460)
        }
        val cardLeft = (clientW - cardW) / 2
        val cardTop = (clientH - cardH) / 2
        val cardRight = cardLeft + cardW
        val cardBottom = cardTop + cardH

        drawRoundedRect(
            hdc = hdc,
            left = cardLeft,
            top = cardTop,
            right = cardRight,
            bottom = cardBottom,
            radius = 10,
            fillColor = theme.bgPanel,
            borderColor = theme.borderSubtle
        )

        var curY = cardTop + 18
        val innerLeft = cardLeft + 24
        val innerRight = cardRight - 24
        val innerW = innerRight - innerLeft

        if (stepNavNode != null) {
            val wizardTitle = activeWindowImpl?.getTitle()?.ifBlank { "GenericUI" } ?: "GenericUI"
            SelectObject(hdc, fontTitle)
            drawTextInRect(
                hdc = hdc,
                text = wizardTitle,
                left = innerLeft,
                top = curY,
                right = innerRight,
                bottom = curY + 28,
                colorRef = theme.textPrimary,
                center = true
            )
            curY += 34

            val stepBtns = collectPageActionItems(stepNavNode)
            if (stepBtns.isNotEmpty()) {
                val gap = 4
                val btnW = ((innerW - gap * (stepBtns.size - 1)) / stepBtns.size).coerceAtLeast(52)
                var sx = innerLeft + (innerW - (btnW * stepBtns.size + gap * (stepBtns.size - 1))) / 2
                val stepBarH = 26
                for (stepBtn in stepBtns) {
                    val bg = when {
                        stepBtn.isActive -> theme.accentBlue
                        stepBtn.isDisabled -> theme.bgBase
                        else -> theme.bgCard
                    }
                    val border = when {
                        stepBtn.isActive -> theme.accentBlueBorder
                        stepBtn.isDisabled -> theme.borderSubtle
                        else -> theme.accentBlueBorder
                    }
                    val fg = when {
                        stepBtn.isActive -> rgb(255, 255, 255)
                        stepBtn.isDisabled -> theme.textMuted
                        else -> theme.textPrimary
                    }
                    drawRoundedButton(
                        hdc = hdc,
                        font = if (stepBtn.isActive) fontSmallBold else fontSmall,
                        label = stepBtn.label,
                        left = sx,
                        top = curY,
                        right = sx + btnW,
                        bottom = curY + stepBarH,
                        bg = bg,
                        border = border,
                        fg = fg,
                        radius = 4,
                        widget = if (stepBtn.isDisabled) null else stepBtn.widget,
                        hwndForHover = if (stepBtn.isDisabled) null else hwnd
                    )
                    if (!stepBtn.isDisabled) {
                        val capLeft = sx
                        val capRight = sx + btnW
                        val capTop = curY
                        val capBottom = curY + stepBarH
                        hitRegions.add(
                            HitRegion(
                                left = capLeft,
                                top = capTop,
                                right = capRight,
                                bottom = capBottom,
                                widget = stepBtn.widget,
                                onClick = { _, _ -> stepBtn.impl.dispatchClick() }
                            )
                        )
                    }
                    sx += btnW + gap
                }
                curY += stepBarH + 16
            }
        } else {
            val winTitle = activeWindowImpl?.getTitle()?.ifBlank { "GenericUI" } ?: "GenericUI"
            SelectObject(hdc, fontMetricBig)
            drawTextInRect(
                hdc = hdc,
                text = winTitle,
                left = innerLeft,
                top = curY,
                right = innerRight,
                bottom = curY + 36,
                colorRef = theme.accentBlueText,
                center = true
            )
            curY += 48
        }

        val contentRoot = findWidgetByClass(activeMenu, "step-content") ?: activeMenu

        // Case 1: Horizontal Carousel Selector
        val carouselPrevNode = findWidgetByClass(contentRoot, "carousel-prev")
            ?: findWidgetByExactText(contentRoot, setOf("«", "‹", "<"))
        val carouselNextNode = findWidgetByClass(contentRoot, "carousel-next")
            ?: findWidgetByExactText(contentRoot, setOf("»", "›", ">"))
        val carouselItems = collectCarouselOptionWidgets(contentRoot)
        if (carouselPrevNode != null && carouselNextNode != null && carouselItems.isNotEmpty()) {
            val footerH = 44
            val carouselBottom = cardBottom - 18 - footerH - 12
            val carouselTop = curY
            val navBtnW = 44

            val prevImpl = carouselPrevNode.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
            drawRoundedButton(
                hdc = hdc,
                font = fontTitle,
                label = "«",
                left = innerLeft,
                top = carouselTop,
                right = innerLeft + navBtnW,
                bottom = carouselBottom,
                bg = theme.accentBlue,
                border = theme.accentBlueBorder,
                fg = rgb(255, 255, 255),
                radius = 6,
                widget = carouselPrevNode,
                hwndForHover = hwnd
            )
            if (prevImpl != null) {
                hitRegions.add(
                    HitRegion(
                        left = innerLeft,
                        top = carouselTop,
                        right = innerLeft + navBtnW,
                        bottom = carouselBottom,
                        widget = carouselPrevNode,
                        onClick = { _, _ -> prevImpl.dispatchClick() }
                    )
                )
            }

            val nextImpl = carouselNextNode.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
            drawRoundedButton(
                hdc = hdc,
                font = fontTitle,
                label = "»",
                left = innerRight - navBtnW,
                top = carouselTop,
                right = innerRight,
                bottom = carouselBottom,
                bg = theme.accentBlue,
                border = theme.accentBlueBorder,
                fg = rgb(255, 255, 255),
                radius = 6,
                widget = carouselNextNode,
                hwndForHover = hwnd
            )
            if (nextImpl != null) {
                hitRegions.add(
                    HitRegion(
                        left = innerRight - navBtnW,
                        top = carouselTop,
                        right = innerRight,
                        bottom = carouselBottom,
                        widget = carouselNextNode,
                        onClick = { _, _ -> nextImpl.dispatchClick() }
                    )
                )
            }

            val activeIdx = carouselItems.indexOfFirst { it.opacity >= 0.9f }.let { if (it >= 0) it else 0 }
            val viewportLeft = innerLeft + navBtnW + 10
            val viewportRight = innerRight - navBtnW - 10
            val viewportW = (viewportRight - viewportLeft).coerceAtLeast(240)

            val visibleIndices = listOf(activeIdx - 1, activeIdx, activeIdx + 1)
                .filter { it in carouselItems.indices }
            val slotW = (viewportW - 16) / 3
            for (idx in visibleIndices) {
                val itemWidget = carouselItems[idx]
                val itemImpl = itemWidget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
                val isSelected = idx == activeIdx
                val relSlot = idx - activeIdx + 1
                val itemLeft = if (visibleIndices.size == 1) {
                    viewportLeft + (viewportW - slotW * 2) / 2
                } else {
                    viewportLeft + relSlot * (slotW + 8)
                }
                val itemRight = if (visibleIndices.size == 1) {
                    itemLeft + slotW * 2
                } else {
                    itemLeft + slotW
                }
                val cardBg = if (isSelected) theme.bgCard else theme.bgBase
                val cardBorder = if (isSelected) theme.accentBlueBorder else theme.borderSubtle
                drawRoundedRect(
                    hdc = hdc,
                    left = itemLeft,
                    top = carouselTop,
                    right = itemRight,
                    bottom = carouselBottom,
                    radius = 8,
                    fillColor = cardBg,
                    borderColor = cardBorder
                )
                val texts = collectDirectOrNestedTexts(itemWidget)
                    .map { it.getText().trim() }
                    .filter { it.isNotEmpty() }
                var ty = carouselTop + 12
                val titleStr = texts.firstOrNull() ?: "Option #${idx + 1}"
                SelectObject(hdc, if (isSelected) fontTitle else fontBold)
                drawTextInRect(
                    hdc = hdc,
                    text = titleStr,
                    left = itemLeft + 10,
                    top = ty,
                    right = itemRight - 10,
                    bottom = ty + 24,
                    colorRef = if (isSelected) theme.accentBlueText else theme.textSecondary,
                    center = true
                )
                ty += 28

                // Preview box
                val silHeight = (carouselBottom - carouselTop - 160).coerceIn(70, 150)
                val silTop = ty
                val silBottom = silTop + silHeight
                drawRoundedRect(
                    hdc = hdc,
                    left = itemLeft + 16,
                    top = silTop,
                    right = itemRight - 16,
                    bottom = silBottom,
                    radius = 6,
                    fillColor = theme.bgElevated,
                    borderColor = theme.borderSubtle
                )
                val midX = (itemLeft + itemRight) / 2
                val figColor = if (isSelected) theme.accentBlueText else theme.textMuted
                fillRectColor(hdc, midX - 28, silTop + 14, midX - 16, silTop + 26, figColor)
                fillRectColor(hdc, midX - 32, silTop + 28, midX - 12, silBottom - 12, figColor)
                fillRectColor(hdc, midX + 16, silTop + 20, midX + 28, silTop + 32, figColor)
                fillRectColor(hdc, midX + 12, silTop + 34, midX + 32, silBottom - 12, figColor)
                ty = silBottom + 8

                for (line in texts.drop(1)) {
                    if (ty + 18 > carouselBottom - 8) break
                    val isSubHeader = !line.contains(".") && line.length < 28
                    SelectObject(hdc, if (isSubHeader) fontSmallBold else fontSmall)
                    drawTextInRect(
                        hdc = hdc,
                        text = line,
                        left = itemLeft + 10,
                        top = ty,
                        right = itemRight - 10,
                        bottom = ty + 18,
                        colorRef = if (isSelected) {
                            if (isSubHeader) theme.textPrimary else theme.textSecondary
                        } else {
                            theme.textMuted
                        },
                        center = true
                    )
                    ty += 18
                }

                if (!isSelected && itemImpl != null) {
                    hitRegions.add(
                        HitRegion(
                            left = itemLeft,
                            top = carouselTop,
                            right = itemRight,
                            bottom = carouselBottom,
                            widget = itemWidget,
                            onClick = { _, _ -> itemImpl.dispatchClick() }
                        )
                    )
                }
            }

            // Footer Continue + Save buttons
            val footerTop = carouselBottom + 10
            val footerBottom = cardBottom - 16
            val continueNode = findWidgetByClass(contentRoot, "continue")
            val saveNode = findWidgetByClass(contentRoot, "save")
            val saveW = 100
            val contRight = if (saveNode != null) innerRight - saveW - 8 else innerRight
            if (continueNode != null) {
                val contImpl = continueNode.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
                drawRoundedButton(
                    hdc = hdc,
                    font = fontBold,
                    label = "Continue",
                    left = innerLeft,
                    top = footerTop,
                    right = contRight,
                    bottom = footerBottom,
                    bg = theme.accentBlue,
                    border = theme.accentBlueBorder,
                    fg = rgb(255, 255, 255),
                    radius = 6,
                    widget = continueNode,
                    hwndForHover = hwnd
                )
                if (contImpl != null) {
                    hitRegions.add(
                        HitRegion(
                            left = innerLeft,
                            top = footerTop,
                            right = contRight,
                            bottom = footerBottom,
                            widget = continueNode,
                            onClick = { _, _ -> contImpl.dispatchClick() }
                        )
                    )
                }
            }
            if (saveNode != null) {
                val saveImpl = saveNode.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
                drawRoundedButton(
                    hdc = hdc,
                    font = fontBold,
                    label = "Save",
                    left = innerRight - saveW,
                    top = footerTop,
                    right = innerRight,
                    bottom = footerBottom,
                    bg = theme.bgCard,
                    border = theme.accentBlueBorder,
                    fg = theme.accentBlueText,
                    radius = 6,
                    widget = saveNode,
                    hwndForHover = hwnd
                )
                if (saveImpl != null) {
                    hitRegions.add(
                        HitRegion(
                            left = innerRight - saveW,
                            top = footerTop,
                            right = innerRight,
                            bottom = footerBottom,
                            widget = saveNode,
                            onClick = { _, _ -> saveImpl.dispatchClick() }
                        )
                    )
                }
            }
            return
        }

        // Case 2: Level Grid (.level-select)
        val levelSelectNodes = findWidgetsByClass(contentRoot, "level-select")
        if (levelSelectNodes.isNotEmpty()) {
            SelectObject(hdc, fontTitle)
            drawTextInRect(
                hdc = hdc,
                text = "Select Starting Level",
                left = innerLeft,
                top = curY,
                right = innerRight,
                bottom = curY + 28,
                colorRef = theme.textPrimary,
                center = true
            )
            curY += 42

            val cols = 10
            val gap = 10
            val btnW = ((innerW - gap * (cols - 1)) / cols).coerceIn(44, 76)
            val btnH = 44
            val rowW = btnW * cols + gap * (cols - 1)
            val startX = innerLeft + (innerW - rowW) / 2

            levelSelectNodes.forEachIndexed { idx, lvlWidget ->
                val impl = lvlWidget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*> ?: return@forEachIndexed
                val row = idx / cols
                val col = idx % cols
                val bx = startX + col * (btnW + gap)
                val by = curY + row * (btnH + 14)
                val label = extractWidgetText(lvlWidget).ifBlank { "${idx + 1}" }
                val isSelected = widgetHasClass(lvlWidget, "btn-active")
                drawRoundedButton(
                    hdc = hdc,
                    font = fontBold,
                    label = label,
                    left = bx,
                    top = by,
                    right = bx + btnW,
                    bottom = by + btnH,
                    bg = if (isSelected) theme.accentBlue else theme.bgCard,
                    border = if (isSelected) theme.accentBlueBorder else theme.borderSubtle,
                    fg = if (isSelected) rgb(255, 255, 255) else theme.textPrimary,
                    radius = 6,
                    widget = lvlWidget,
                    hwndForHover = hwnd
                )
                hitRegions.add(
                    HitRegion(
                        left = bx,
                        top = by,
                        right = bx + btnW,
                        bottom = by + btnH,
                        widget = lvlWidget,
                        onClick = { _, _ -> impl.dispatchClick() }
                    )
                )
            }
            return
        }

        // Case 3: General Page / Menu / Step View
        val actions = collectPageActionItems(contentRoot)
        val actionWidgets = actions.map { it.widget }.toSet()
        val nonButtonTexts = mutableListOf<String>()
        fun collectPlainTexts(w: DesktopNativeWidget?) {
            if (w == null || !w.visible || w.disposed || w in actionWidgets) return
            val impl = w.attributes["nativeImpl"]
            if (impl is DesktopTextNativeImpl<*>) {
                val t = impl.getText().trim()
                if (t.isNotEmpty()) nonButtonTexts.add(t)
            }
            for (c in w.children) collectPlainTexts(c)
        }
        collectPlainTexts(contentRoot)

        for (txt in nonButtonTexts.distinct().take(8)) {
            if (curY + 24 > cardBottom - 60) break
            SelectObject(hdc, fontNormal)
            drawTextInRect(
                hdc = hdc,
                text = txt,
                left = innerLeft,
                top = curY,
                right = innerRight,
                bottom = curY + 22,
                colorRef = theme.textPrimary,
                center = !isWideStepWizard
            )
            curY += 24
        }
        if (nonButtonTexts.isNotEmpty()) {
            curY += 8
        }

        val textInputs = mutableListOf<DesktopTextInputNativeImpl<*>>()
        fun collectInputs(w: DesktopNativeWidget?) {
            if (w == null || !w.visible || w.disposed) return
            val impl = w.attributes["nativeImpl"]
            if (impl is DesktopTextInputNativeImpl<*>) textInputs.add(impl)
            for (c in w.children) collectInputs(c)
        }
        collectInputs(contentRoot)
        for (input in textInputs.take(4)) {
            if (curY + 36 > cardBottom - 50) break
            val isFocused = focusedTextInput === input
            val valStr = input.getValue()
            val displayStr = when {
                isFocused -> "$valStr|"
                valStr.isNotEmpty() -> valStr
                else -> input.getPlaceholder().ifBlank { "Enter value..." }
            }
            val inputLeft = innerLeft + (innerW - min(innerW, 380)) / 2
            val inputRight = inputLeft + min(innerW, 380)
            drawRoundedRect(
                hdc = hdc,
                left = inputLeft,
                top = curY,
                right = inputRight,
                bottom = curY + 32,
                radius = 5,
                fillColor = theme.bgBase,
                borderColor = if (isFocused) theme.accentBlueBorder else theme.borderSubtle
            )
            SelectObject(hdc, fontNormal)
            drawTextInRect(
                hdc = hdc,
                text = displayStr,
                left = inputLeft + 10,
                top = curY + 4,
                right = inputRight - 10,
                bottom = curY + 28,
                colorRef = if (valStr.isEmpty() && !isFocused) theme.textMuted else theme.textPrimary
            )
            val capTop = curY
            hitRegions.add(
                HitRegion(
                    left = inputLeft,
                    top = capTop,
                    right = inputRight,
                    bottom = capTop + 32,
                    widget = input.nativeWidget,
                    isTextInput = true
                )
            )
            curY += 40
        }

        if (actions.isNotEmpty()) {
            val cols = if (!isWideStepWizard || actions.size <= 4) 1 else 3
            val btnH = if (cols == 1) 38 else 32
            val gap = 10
            val maxRowW = if (cols == 1) min(innerW, 340) else innerW
            val startX = innerLeft + (innerW - maxRowW) / 2
            val btnW = (maxRowW - gap * (cols - 1)) / cols
            actions.take(24).forEachIndexed { idx, act ->
                val row = idx / cols
                val col = idx % cols
                val bx = startX + col * (btnW + gap)
                val by = curY + row * (btnH + gap)
                if (by + btnH <= cardBottom - 14) {
                    val bg = when {
                        act.isActive || act.isPrimary -> theme.accentBlue
                        else -> theme.bgCard
                    }
                    val border = when {
                        act.isActive || act.isPrimary -> theme.accentBlueBorder
                        else -> theme.borderSubtle
                    }
                    val fg = when {
                        act.isActive || act.isPrimary -> rgb(255, 255, 255)
                        else -> theme.textPrimary
                    }
                    drawRoundedButton(
                        hdc = hdc,
                        font = fontBold,
                        label = act.label,
                        left = bx,
                        top = by,
                        right = bx + btnW,
                        bottom = by + btnH,
                        bg = bg,
                        border = border,
                        fg = fg,
                        radius = 6,
                        widget = act.widget,
                        hwndForHover = hwnd
                    )
                    hitRegions.add(
                        HitRegion(
                            left = bx,
                            top = by,
                            right = bx + btnW,
                            bottom = by + btnH,
                            widget = act.widget,
                            onClick = { _, _ -> act.impl.dispatchClick() }
                        )
                    )
                }
            }
        }
    }

    private fun findFirstTextInput(root: DesktopNativeWidget?): DesktopTextInputNativeImpl<*>? {
        if (root == null || !root.visible || root.disposed) return null
        val impl = root.attributes["nativeImpl"]
        if (impl is DesktopTextInputNativeImpl<*>) return impl
        for (child in root.children) {
            val found = findFirstTextInput(child)
            if (found != null) return found
        }
        return null
    }

    private fun findFirstCheckbox(root: DesktopNativeWidget?): DesktopCheckboxNativeImpl<*>? {
        if (root == null || !root.visible || root.disposed) return null
        val impl = root.attributes["nativeImpl"]
        if (impl is DesktopCheckboxNativeImpl<*>) return impl
        for (child in root.children) {
            val found = findFirstCheckbox(child)
            if (found != null) return found
        }
        return null
    }

    private fun findFirstSelect(root: DesktopNativeWidget?): DesktopSelectNativeImpl<*>? {
        if (root == null || !root.visible || root.disposed) return null
        val impl = root.attributes["nativeImpl"]
        if (impl is DesktopSelectNativeImpl<*>) return impl
        for (child in root.children) {
            val found = findFirstSelect(child)
            if (found != null) return found
        }
        return null
    }

    private fun findFirstTabContainer(root: DesktopNativeWidget?): DesktopTabContainerNativeImpl<*>? {
        if (root == null || !root.visible || root.disposed) return null
        val impl = root.attributes["nativeImpl"]
        if (impl is DesktopTabContainerNativeImpl<*>) return impl
        for (child in root.children) {
            val found = findFirstTabContainer(child)
            if (found != null) return found
        }
        return null
    }

    private fun findFirstLazyGrid(root: DesktopNativeWidget?): DesktopLazyGridNativeImpl<*>? {
        if (root == null || !root.visible || root.disposed) return null
        val impl = root.attributes["nativeImpl"]
        if (impl is DesktopLazyGridNativeImpl<*>) return impl
        for (child in root.children) {
            val found = findFirstLazyGrid(child)
            if (found != null) return found
        }
        return null
    }

    private fun collectDirectOrNestedButtons(
        root: DesktopNativeWidget?,
        out: MutableList<DesktopButtonNativeImpl<*>> = mutableListOf()
    ): List<DesktopButtonNativeImpl<*>> {
        if (root == null || !root.visible || root.disposed || isWidgetMountedInTitleBar(root)) return out
        val impl = root.attributes["nativeImpl"]
        if (impl is DesktopButtonNativeImpl<*>) {
            out.add(impl)
        }
        for (child in root.children) {
            collectDirectOrNestedButtons(child, out)
        }
        return out
    }

    private fun collectDirectOrNestedTexts(
        root: DesktopNativeWidget?,
        out: MutableList<DesktopTextNativeImpl<*>> = mutableListOf()
    ): List<DesktopTextNativeImpl<*>> {
        if (root == null || !root.visible || root.disposed || isWidgetMountedInTitleBar(root)) return out
        val impl = root.attributes["nativeImpl"]
        if (impl is DesktopTextNativeImpl<*>) {
            out.add(impl)
        }
        for (child in root.children) {
            collectDirectOrNestedTexts(child, out)
        }
        return out
    }

    private fun collectDirectOrNestedProgressBars(
        root: DesktopNativeWidget?,
        out: MutableList<DesktopProgressBarNativeImpl<*>> = mutableListOf()
    ): List<DesktopProgressBarNativeImpl<*>> {
        if (root == null || !root.visible || root.disposed || isWidgetMountedInTitleBar(root)) return out
        val impl = root.attributes["nativeImpl"]
        if (impl is DesktopProgressBarNativeImpl<*>) {
            out.add(impl)
        }
        for (child in root.children) {
            collectDirectOrNestedProgressBars(child, out)
        }
        return out
    }

    /**
     * Renders the visual BSON / JSON / Binary Plist Editor powered by `LazyGridTree`
     * (`DataEditorFragment`) with interactive tabs, filter bar, format converter & byte-size strip,
     * 4-column `LazyGridTree` (with inline editable Key & Value `TextField`s, `CheckBox` toggles,
     * 12-datatype `Select` popup menus, expand/collapse branch toggles, and `+ Leaf`, `+ Branch`,
     * `↑`, `↓`, `Dup`, `Del` row buttons), and bottom status bar.
     */
    private fun renderDataEditorLazyGridTreeLayout(
        hwnd: HWND,
        hdc: HDC?,
        theme: SystemThemePalette,
        fontSmall: HFONT?,
        fontSmallBold: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        rootWidget: DesktopNativeWidget?,
        clientW: Int,
        clientH: Int
    ) {
        val padX = 10
        var curY = 8

        // 1. Top Toolbar (.editor-toolbar)
        val toolbarNode = findWidgetByClass(rootWidget, "editor-toolbar")
        val toolbarH = 36
        drawRoundedRect(
            hdc = hdc,
            left = padX,
            top = curY,
            right = clientW - padX,
            bottom = curY + toolbarH,
            radius = 6,
            fillColor = theme.bgPanel,
            borderColor = theme.borderSubtle
        )
        val toolbarButtons = collectDirectOrNestedButtons(toolbarNode)
        var tbX = padX + 8
        val tbBtnTop = curY + 5
        val tbBtnBottom = curY + toolbarH - 5
        for (btn in toolbarButtons) {
            val label = btn.getText().trim()
            if (label.isEmpty()) continue
            val isPrimaryNew = label.startsWith("+")
            val btnW = max(68, label.length * 7 + 16)
            if (tbX + btnW > clientW - padX - 6) break
            val bg = when {
                label.contains("JSON") && isPrimaryNew -> theme.accentBlueSoftBg
                label.contains("BSON") && isPrimaryNew -> rgb(58, 38, 82)
                label.contains("Plist") && isPrimaryNew -> theme.accentGreenSoftBg
                else -> theme.bgCard
            }
            val border = when {
                label.contains("JSON") && isPrimaryNew -> theme.accentBlueBorder
                label.contains("BSON") && isPrimaryNew -> rgb(148, 92, 220)
                label.contains("Plist") && isPrimaryNew -> theme.accentGreenBorder
                else -> theme.borderSubtle
            }
            drawRoundedButton(
                hdc = hdc,
                font = fontSmallBold,
                label = label,
                left = tbX,
                top = tbBtnTop,
                right = tbX + btnW,
                bottom = tbBtnBottom,
                bg = bg,
                border = border,
                fg = theme.textPrimary,
                radius = 4,
                widget = btn.nativeWidget,
                hwndForHover = hwnd
            )
            val capturedLeft = tbX
            val capturedRight = tbX + btnW
            hitRegions.add(
                HitRegion(
                    left = capturedLeft,
                    top = tbBtnTop,
                    right = capturedRight,
                    bottom = tbBtnBottom,
                    widget = btn.nativeWidget,
                    onClick = { _, _ -> btn.dispatchClick() }
                )
            )
            tbX += btnW + 6
        }
        curY += toolbarH + 6

        // 2. Open Document Tabs Bar (DesktopTabContainerNativeImpl)
        val tabContainer = findFirstTabContainer(rootWidget)
        val tabBarH = 30
        if (tabContainer != null) {
            val titles = tabContainer.getTabTitles()
            val activeIdx = tabContainer.getSelectedTabIndex()
            var tabX = padX
            for (idx in titles.indices) {
                val title = titles[idx].ifBlank { "Tab ${idx + 1}" }
                val isSelected = idx == activeIdx
                val tabW = (title.length * 7 + 44).coerceIn(110, 260)
                if (tabX + tabW > clientW - padX) break
                val tabBg = if (isSelected) theme.accentBlueSoftBg else theme.bgPanel
                val tabBorder = if (isSelected) theme.accentBlueBorder else theme.borderSubtle
                val tabFg = if (isSelected) theme.accentBlueText else theme.textSecondary
                drawRoundedRect(
                    hdc = hdc,
                    left = tabX,
                    top = curY,
                    right = tabX + tabW,
                    bottom = curY + tabBarH,
                    radius = 5,
                    fillColor = tabBg,
                    borderColor = tabBorder
                )
                SelectObject(hdc, if (isSelected) fontSmallBold else fontSmall)
                drawTextInRect(
                    hdc = hdc,
                    text = title,
                    left = tabX + 10,
                    top = curY + 2,
                    right = tabX + tabW - 26,
                    bottom = curY + tabBarH - 2,
                    colorRef = tabFg
                )
                // Close '×' button inside tab
                val closeLeft = tabX + tabW - 22
                val closeRight = tabX + tabW - 5
                val closeTop = curY + 6
                val closeBottom = curY + tabBarH - 6
                drawRoundedRect(
                    hdc = hdc,
                    left = closeLeft,
                    top = closeTop,
                    right = closeRight,
                    bottom = closeBottom,
                    radius = 3,
                    fillColor = theme.bgElevated,
                    borderColor = theme.borderSubtle
                )
                SelectObject(hdc, fontSmallBold)
                drawTextInRect(hdc, "×", closeLeft, closeTop - 1, closeRight, closeBottom, theme.textSecondary, center = true)

                val capturedIdx = idx
                val capturedTabLeft = tabX
                hitRegions.add(
                    HitRegion(
                        left = capturedTabLeft,
                        top = curY,
                        right = closeLeft - 2,
                        bottom = curY + tabBarH,
                        onClick = { _, _ -> tabContainer.requestSelectTab(capturedIdx) }
                    )
                )
                hitRegions.add(
                    HitRegion(
                        left = closeLeft,
                        top = closeTop,
                        right = closeRight,
                        bottom = closeBottom,
                        tooltip = "Close tab",
                        onClick = { _, _ -> tabContainer.requestCloseTab(capturedIdx) }
                    )
                )
                tabX += tabW + 6
            }
            curY += tabBarH + 6
        }

        // 3. Filter & Tree Controls Bar (.editor-filter-bar / .grid-tree-toolbar)
        val filterBarNode = findWidgetByClass(rootWidget, "editor-filter-bar")
            ?: findWidgetByClass(rootWidget, "grid-tree-toolbar")
        val filterBarH = 34
        if (filterBarNode != null) {
            drawRoundedRect(
                hdc = hdc,
                left = padX,
                top = curY,
                right = clientW - padX,
                bottom = curY + filterBarH,
                radius = 6,
                fillColor = theme.bgPanel,
                borderColor = theme.borderSubtle
            )
            var fx = padX + 10
            SelectObject(hdc, fontSmallBold)
            drawTextInRect(hdc, "Filter:", fx, curY, fx + 42, curY + filterBarH, theme.textSecondary)
            fx += 44

            val filterInput = findFirstTextInput(filterBarNode)
            val inputW = 230
            val inputTop = curY + 5
            val inputBottom = curY + filterBarH - 5
            if (filterInput != null) {
                val isFocused = focusedTextInput === filterInput
                val valStr = filterInput.getValue()
                val displayStr = when {
                    isFocused -> "$valStr|"
                    valStr.isNotEmpty() -> valStr
                    else -> filterInput.getPlaceholder().ifBlank { "Filter by key, path, type, or value..." }
                }
                val fgColor = if (valStr.isEmpty() && !isFocused) theme.textMuted else theme.textPrimary
                drawRoundedRect(
                    hdc = hdc,
                    left = fx,
                    top = inputTop,
                    right = fx + inputW,
                    bottom = inputBottom,
                    radius = 4,
                    fillColor = theme.bgBase,
                    borderColor = if (isFocused) theme.accentBlueBorder else theme.borderSubtle
                )
                SelectObject(hdc, fontSmall)
                drawTextInRect(hdc, displayStr, fx + 8, inputTop, fx + inputW - 6, inputBottom, fgColor)
                val capLeft = fx
                hitRegions.add(
                    HitRegion(
                        left = capLeft,
                        top = inputTop,
                        right = capLeft + inputW,
                        bottom = inputBottom,
                        widget = filterInput.nativeWidget,
                        isTextInput = true
                    )
                )
                fx += inputW + 8
            }

            val filterButtons = collectDirectOrNestedButtons(filterBarNode)
            for (btn in filterButtons) {
                val label = btn.getText().trim()
                if (label.isEmpty()) continue
                val isAddBtn = label.startsWith("+")
                val bw = max(62, label.length * 7 + 16)
                if (fx + bw > clientW - padX - 95) break
                drawRoundedButton(
                    hdc = hdc,
                    font = fontSmallBold,
                    label = label,
                    left = fx,
                    top = inputTop,
                    right = fx + bw,
                    bottom = inputBottom,
                    bg = if (isAddBtn) theme.accentGreenSoftBg else theme.bgCard,
                    border = if (isAddBtn) theme.accentGreenBorder else theme.borderSubtle,
                    fg = if (isAddBtn) theme.accentGreen else theme.textPrimary,
                    radius = 4,
                    widget = btn.nativeWidget,
                    hwndForHover = hwnd
                )
                val capLeft = fx
                val capRight = fx + bw
                hitRegions.add(
                    HitRegion(
                        left = capLeft,
                        top = inputTop,
                        right = capRight,
                        bottom = inputBottom,
                        widget = btn.nativeWidget,
                        onClick = { _, _ -> btn.dispatchClick() }
                    )
                )
                fx += bw + 6
            }

            val filterTexts = collectDirectOrNestedTexts(filterBarNode)
            val nodeCountStr = filterTexts.lastOrNull()?.getText()?.trim().orEmpty()
            if (nodeCountStr.isNotEmpty()) {
                SelectObject(hdc, fontSmallBold)
                drawTextInRect(
                    hdc = hdc,
                    text = nodeCountStr,
                    left = clientW - padX - 110,
                    top = curY,
                    right = clientW - padX - 10,
                    bottom = curY + filterBarH,
                    colorRef = theme.accentBlueText,
                    alignRight = true
                )
            }
            curY += filterBarH + 6
        }

        // 4. Format Converter & Byte Comparison Strip (.format-converter-strip / .format-comparison-card)
        val formatStripNode = findWidgetByClass(rootWidget, "format-converter-strip")
            ?: findWidgetByClass(rootWidget, "format-comparison-card")
        val formatStripH = 34
        if (formatStripNode != null) {
            drawRoundedRect(
                hdc = hdc,
                left = padX,
                top = curY,
                right = clientW - padX,
                bottom = curY + formatStripH,
                radius = 6,
                fillColor = theme.bgPanel,
                borderColor = theme.borderSubtle
            )
            val stripTexts = collectDirectOrNestedTexts(formatStripNode).map { it.getText().trim() }.filter { it.isNotEmpty() }
            val stripBtns = collectDirectOrNestedButtons(formatStripNode)
            val stripBars = collectDirectOrNestedProgressBars(formatStripNode)

            var sx = padX + 10
            SelectObject(hdc, fontSmallBold)
            drawTextInRect(hdc, "Active Format:", sx, curY, sx + 88, curY + formatStripH, theme.textSecondary)
            sx += 90

            val activeFmtText = stripTexts.getOrNull(1) ?: "JSON"
            val fmtBadgeW = 54
            val fmtTop = curY + 6
            val fmtBottom = curY + formatStripH - 6
            drawRoundedRect(
                hdc = hdc,
                left = sx,
                top = fmtTop,
                right = sx + fmtBadgeW,
                bottom = fmtBottom,
                radius = 4,
                fillColor = theme.accentBlueSoftBg,
                borderColor = theme.accentBlueBorder
            )
            SelectObject(hdc, fontSmallBold)
            drawTextInRect(hdc, activeFmtText, sx, fmtTop, sx + fmtBadgeW, fmtBottom, theme.accentBlueText, center = true)
            sx += fmtBadgeW + 8

            for (btn in stripBtns) {
                val label = btn.getText().trim()
                if (label.isEmpty()) continue
                val bw = max(92, label.length * 6 + 16)
                drawRoundedButton(
                    hdc = hdc,
                    font = fontSmall,
                    label = label,
                    left = sx,
                    top = fmtTop,
                    right = sx + bw,
                    bottom = fmtBottom,
                    bg = theme.bgCard,
                    border = theme.borderSubtle,
                    fg = theme.textPrimary,
                    radius = 4,
                    widget = btn.nativeWidget,
                    hwndForHover = hwnd
                )
                val capL = sx
                val capR = sx + bw
                hitRegions.add(
                    HitRegion(
                        left = capL,
                        top = fmtTop,
                        right = capR,
                        bottom = fmtBottom,
                        widget = btn.nativeWidget,
                        onClick = { _, _ -> btn.dispatchClick() }
                    )
                )
                sx += bw + 6
            }

            // Byte size comparison bars on the right
            val sizeTexts = stripTexts.filter { it.startsWith("JSON:") || it.startsWith("BSON:") || it.startsWith("Plist:") }
            var rightEdge = clientW - padX - 10
            for (i in sizeTexts.indices.reversed()) {
                val sTxt = sizeTexts[i]
                val pct = stripBars.getOrNull(i)?.getProgress()?.coerceIn(0f, 1f) ?: 0.5f
                val blockW = max(112, sTxt.length * 6 + 18)
                val bLeft = rightEdge - blockW
                if (bLeft < sx + 8) break
                SelectObject(hdc, fontSmall)
                drawTextInRect(hdc, sTxt, bLeft, curY + 2, rightEdge, curY + 18, theme.textSecondary, center = true)
                val barT = curY + 21
                val barB = curY + 27
                drawRoundedRect(hdc, bLeft + 4, barT, rightEdge - 4, barB, 3, theme.bgBase, theme.borderSubtle)
                val fillW = (((rightEdge - 4) - (bLeft + 4)) * pct).roundToInt()
                if (fillW > 2) {
                    val barColor = when (i) {
                        0 -> theme.accentBlue
                        1 -> rgb(168, 85, 247)
                        else -> theme.accentGreen
                    }
                    drawRoundedRect(hdc, bLeft + 4, barT, bLeft + 4 + fillW, barB, 3, barColor, barColor)
                }
                rightEdge = bLeft - 8
            }
            curY += formatStripH + 6
        }

        // 5. Primary 4-Column LazyGridTree Visual Editor
        val statusBarH = 30
        val gridTop = curY
        val gridBottom = clientH - statusBarH - 12
        val gridLeft = padX
        val gridRight = clientW - padX
        val gridW = max(200, gridRight - gridLeft)
        val gridH = max(120, gridBottom - gridTop)

        drawRoundedRect(
            hdc = hdc,
            left = gridLeft,
            top = gridTop,
            right = gridRight,
            bottom = gridBottom,
            radius = 6,
            fillColor = theme.bgPanel,
            borderColor = theme.borderSubtle
        )

        val gridImpl = findFirstLazyGrid(rootWidget)
        activeLazyGridImpl = gridImpl

        if (gridImpl != null && gridImpl.getTotalRowCount() > 0) {
            val scrollRailW = 20
            val contentW = max(160, gridW - scrollRailW)
            val col0W = (contentW * 0.34).roundToInt()
            val col1W = (contentW * 0.27).roundToInt()
            val col2W = (contentW * 0.15).roundToInt()
            val col3W = contentW - col0W - col1W - col2W

            val colLefts = intArrayOf(
                gridLeft,
                gridLeft + col0W,
                gridLeft + col0W + col1W,
                gridLeft + col0W + col1W + col2W
            )
            val colRights = intArrayOf(
                gridLeft + col0W,
                gridLeft + col0W + col1W,
                gridLeft + col0W + col1W + col2W,
                gridLeft + contentW
            )

            // Header Row (Row 0 of LazyGridTree)
            val headerH = 32
            val headerBottom = gridTop + headerH
            fillRectColor(hdc, gridLeft + 1, gridTop + 1, gridRight - 1, headerBottom, theme.bgElevated)
            drawLineColor(hdc, gridLeft + 1, headerBottom, gridRight - 1, headerBottom, theme.borderSubtle)

            val defaultHeaders = arrayOf("Key / Hierarchy", "Value", "Type (12 DataTypes)", "Node Actions")
            for (c in 0..3) {
                val hCell = gridImpl.getOrMaterializeCellWidget(c)
                val hTitle = extractWidgetText(hCell).ifBlank { defaultHeaders[c] }
                SelectObject(hdc, fontSmallBold)
                drawTextInRect(
                    hdc = hdc,
                    text = hTitle,
                    left = colLefts[c] + 10,
                    top = gridTop + 2,
                    right = colRights[c] - 6,
                    bottom = headerBottom - 2,
                    colorRef = theme.textPrimary
                )
                if (c > 0) {
                    drawLineColor(hdc, colLefts[c], gridTop + 1, colLefts[c], gridBottom - 1, theme.borderSubtle)
                }
            }

            // Data Rows (Rows 1..totalRows-1 of LazyGridTree)
            val totalDataRows = (gridImpl.getTotalRowCount() - 1).coerceAtLeast(0)
            val rowH = 34
            val bodyTop = headerBottom + 1
            val bodyH = max(rowH, gridBottom - bodyTop - 2)
            val maxVisibleRows = max(1, bodyH / rowH)
            val maxFirstRow = (totalDataRows - maxVisibleRows).coerceAtLeast(0)
            val firstDataRow = gridImpl.firstVisibleDataRow.coerceIn(0, maxFirstRow)
            gridImpl.firstVisibleDataRow = firstDataRow

            val rowsToDraw = min(maxVisibleRows, (totalDataRows - firstDataRow).coerceAtLeast(0))
            for (visIdx in 0 until rowsToDraw) {
                val dataRowIdx = firstDataRow + visIdx
                val gridRowIdx = 1 + dataRowIdx
                val rTop = bodyTop + visIdx * rowH
                val rBottom = min(gridBottom - 1, rTop + rowH)

                val rowBg = if (dataRowIdx % 2 == 0) theme.bgBase else theme.bgPanel
                fillRectColor(hdc, gridLeft + 1, rTop, gridLeft + contentW, rBottom, rowBg)
                drawLineColor(hdc, gridLeft + 1, rBottom, gridLeft + contentW, rBottom, theme.borderSubtle)
                for (c in 1..3) {
                    drawLineColor(hdc, colLefts[c], rTop, colLefts[c], rBottom, theme.borderSubtle)
                }

                // --- Column 0: Tree Indentation + Expand/Collapse Toggle + Type Badge + Editable Key ---
                val cell0 = gridImpl.getOrMaterializeCellWidget(gridRowIdx * 4 + 0)
                if (cell0 != null) {
                    val colWrapper = findWidgetByClass(cell0, "lazy-grid-tree-hierarchy-cell")
                        ?: findWidgetByClass(cell0, "lazy-grid-tree-col-wrapper")
                        ?: cell0
                    val attrIndent = (colWrapper.attributes["data-tree-indent-px"] as? String)?.toIntOrNull()
                    val attrDepth = (colWrapper.attributes["data-tree-depth"] as? String)?.toIntOrNull()
                    val computedPadLeft = (colWrapper.attributes["computedStyle"] as? com.nexustools.genericui.style.css.ComputedStyle)
                        ?.rawDeclarations?.get("padding-left")?.removeSuffix("px")?.trim()?.toIntOrNull()
                    val stylePadLeft = colWrapper.styleProperties["padding-left"]
                        ?.removeSuffix("px")?.trim()?.toIntOrNull()
                    val indentPx = when {
                        colWrapper.padding.left > 0 -> colWrapper.padding.left
                        attrIndent != null && attrIndent > 0 -> attrIndent
                        computedPadLeft != null && computedPadLeft > 0 -> computedPadLeft
                        stylePadLeft != null && stylePadLeft > 0 -> stylePadLeft
                        attrDepth != null && attrDepth > 0 -> attrDepth * 20
                        else -> 0
                    }
                    val baseLeftX = colLefts[0] + 8
                    var c0X = (baseLeftX + indentPx).coerceAtMost(colRights[0] - 90)
                    if (indentPx > 0) {
                        val stepPx = 20
                        val levels = (indentPx / stepPx).coerceAtLeast(1)
                        val midY = (rTop + rBottom) / 2
                        for (lvl in 0 until levels) {
                            val guideX = (baseLeftX + lvl * stepPx + 8).coerceAtMost(c0X - 2)
                            drawLineColor(hdc, guideX, rTop, guideX, rBottom, theme.borderSubtle)
                            if (lvl == levels - 1) {
                                drawLineColor(hdc, guideX, midY, (c0X - 3).coerceAtLeast(guideX), midY, theme.borderSubtle)
                            }
                        }
                    }

                    val toggleBtnNode = findWidgetByClass(cell0, "lazy-grid-tree-toggle-btn")
                    val toggleBtnImpl = toggleBtnNode?.attributes?.get("nativeImpl") as? DesktopButtonNativeImpl<*>
                    if (toggleBtnImpl != null) {
                        val tTop = rTop + 6
                        val tBottom = rBottom - 6
                        val tRight = c0X + 22
                        drawRoundedButton(
                            hdc = hdc,
                            font = fontSmallBold,
                            label = toggleBtnImpl.getText().ifBlank { "▼" },
                            left = c0X,
                            top = tTop,
                            right = tRight,
                            bottom = tBottom,
                            bg = theme.bgElevated,
                            border = theme.borderSubtle,
                            fg = theme.accentBlueText,
                            radius = 4,
                            widget = toggleBtnImpl.nativeWidget,
                            hwndForHover = hwnd
                        )
                        val capL = c0X
                        val capR = tRight
                        hitRegions.add(
                            HitRegion(
                                left = capL,
                                top = tTop,
                                right = capR,
                                bottom = tBottom,
                                tooltip = "Expand / Collapse Branch",
                                widget = toggleBtnImpl.nativeWidget,
                                onClick = { _, _ -> toggleBtnImpl.dispatchClick() }
                            )
                        )
                        c0X = tRight + 5
                    } else {
                        SelectObject(hdc, fontSmallBold)
                        drawTextInRect(hdc, "•", c0X, rTop, c0X + 14, rBottom, theme.textMuted, center = true)
                        c0X += 16
                    }

                    val keyCellTexts = collectDirectOrNestedTexts(cell0)
                        .map { it.getText().trim() }
                        .filter { it.isNotEmpty() && it != "•" }
                    val typeBadgeLabel = keyCellTexts.firstOrNull() ?: ""
                    if (typeBadgeLabel.isNotEmpty()) {
                        val badgeW = max(34, typeBadgeLabel.length * 6 + 10)
                        val bTop = rTop + 7
                        val bBottom = rBottom - 7
                        val isBranchBadge = typeBadgeLabel in setOf("OBJ", "MAP", "ARR")
                        drawRoundedRect(
                            hdc = hdc,
                            left = c0X,
                            top = bTop,
                            right = c0X + badgeW,
                            bottom = bBottom,
                            radius = 3,
                            fillColor = if (isBranchBadge) theme.accentBlueSoftBg else theme.bgCard,
                            borderColor = if (isBranchBadge) theme.accentBlueBorder else theme.borderSubtle
                        )
                        SelectObject(hdc, fontSmallBold)
                        drawTextInRect(
                            hdc = hdc,
                            text = typeBadgeLabel,
                            left = c0X,
                            top = bTop,
                            right = c0X + badgeW,
                            bottom = bBottom,
                            colorRef = if (isBranchBadge) theme.accentBlueText else theme.textSecondary,
                            center = true
                        )
                        c0X += badgeW + 6
                    }

                    val keyInput = findFirstTextInput(cell0)
                    if (keyInput != null) {
                        val kLeft = c0X
                        val kRight = max(kLeft + 48, colRights[0] - 6)
                        val kTop = rTop + 5
                        val kBottom = rBottom - 5
                        val isFocused = focusedTextInput === keyInput
                        val keyText = keyInput.getValue()
                        val shownText = if (isFocused) "$keyText|" else keyText
                        drawRoundedRect(
                            hdc = hdc,
                            left = kLeft,
                            top = kTop,
                            right = kRight,
                            bottom = kBottom,
                            radius = 4,
                            fillColor = theme.bgCard,
                            borderColor = if (isFocused) theme.accentBlueBorder else theme.borderSubtle
                        )
                        SelectObject(hdc, fontNormal)
                        drawTextInRect(hdc, shownText, kLeft + 6, kTop, kRight - 6, kBottom, theme.textPrimary)
                        hitRegions.add(
                            HitRegion(
                                left = kLeft,
                                top = kTop,
                                right = kRight,
                                bottom = kBottom,
                                tooltip = "Click to edit key name (press Enter or click away to apply)",
                                widget = keyInput.nativeWidget,
                                isTextInput = true
                            )
                        )
                    } else {
                        val selectButtons = collectDirectOrNestedButtons(cell0)
                            .filter { it !== toggleBtnImpl }
                        val selectBtn = selectButtons.firstOrNull()
                        val staticKeyLabel = selectBtn?.getText()?.takeIf { it.isNotBlank() }
                            ?: keyCellTexts.drop(1).firstOrNull { it != typeBadgeLabel }
                            ?: keyCellTexts.getOrNull(1)
                            ?: "root"
                        SelectObject(hdc, fontBold)
                        drawTextInRect(hdc, staticKeyLabel, c0X + 2, rTop, colRights[0] - 6, rBottom, theme.accentBlueText)
                        if (selectBtn != null) {
                            hitRegions.add(
                                HitRegion(
                                    left = c0X,
                                    top = rTop + 4,
                                    right = colRights[0] - 6,
                                    bottom = rBottom - 4,
                                    tooltip = "Select node ''",
                                    widget = selectBtn.nativeWidget,
                                    onClick = { _, _ -> selectBtn.dispatchClick() }
                                )
                            )
                        }
                    }
                }

                // --- Column 1: Inline Editable Value (TextField / CheckBox / Summary Badge) ---
                val cell1 = gridImpl.getOrMaterializeCellWidget(gridRowIdx * 4 + 1)
                if (cell1 != null) {
                    val c1Left = colLefts[1] + 6
                    val c1Right = colRights[1] - 6
                    val c1Top = rTop + 5
                    val c1Bottom = rBottom - 5

                    val valInput = findFirstTextInput(cell1)
                    val boolCheck = findFirstCheckbox(cell1)
                    when {
                        valInput != null -> {
                            val isFocused = focusedTextInput === valInput
                            val vText = valInput.getValue()
                            val shownText = if (isFocused) "$vText|" else vText
                            drawRoundedRect(
                                hdc = hdc,
                                left = c1Left,
                                top = c1Top,
                                right = c1Right,
                                bottom = c1Bottom,
                                radius = 4,
                                fillColor = theme.bgCard,
                                borderColor = if (isFocused) theme.accentBlueBorder else theme.borderSubtle
                            )
                            SelectObject(hdc, fontNormal)
                            drawTextInRect(hdc, shownText, c1Left + 6, c1Top, c1Right - 6, c1Bottom, theme.textPrimary)
                            hitRegions.add(
                                HitRegion(
                                    left = c1Left,
                                    top = c1Top,
                                    right = c1Right,
                                    bottom = c1Bottom,
                                    tooltip = "Click to edit leaf value inline",
                                    widget = valInput.nativeWidget,
                                    isTextInput = true
                                )
                            )
                        }
                        boolCheck != null -> {
                            val checked = boolCheck.isChecked()
                            SelectObject(hdc, fontSmallBold)
                            drawCheckboxControl(
                                hdc = hdc,
                                theme = theme,
                                label = if (checked) "true" else "false",
                                checked = checked,
                                left = c1Left + 4,
                                top = c1Top,
                                right = c1Right,
                                bottom = c1Bottom,
                                widget = boolCheck.nativeWidget,
                                hwndForHover = hwnd
                            )
                            hitRegions.add(
                                HitRegion(
                                    left = c1Left,
                                    top = c1Top,
                                    right = c1Right,
                                    bottom = c1Bottom,
                                    tooltip = "Click to toggle boolean value",
                                    widget = boolCheck.nativeWidget,
                                    onClick = { _, _ ->
                                        val next = !boolCheck.isChecked()
                                        boolCheck.setChecked(next)
                                        boolCheck.dispatchToggle(next)
                                    }
                                )
                            )
                        }
                        else -> {
                            val summaryText = extractWidgetText(cell1)
                            SelectObject(hdc, fontSmallBold)
                            drawTextInRect(hdc, summaryText, c1Left + 4, rTop, c1Right, rBottom, theme.textSecondary)
                        }
                    }
                }

                // --- Column 2: Inline Type Dropdown (12 DataTypes) ---
                val cell2 = gridImpl.getOrMaterializeCellWidget(gridRowIdx * 4 + 2)
                if (cell2 != null) {
                    val c2Left = colLefts[2] + 6
                    val c2Right = colRights[2] - 6
                    val c2Top = rTop + 5
                    val c2Bottom = rBottom - 5
                    val selectImpl = findFirstSelect(cell2)
                    if (selectImpl != null) {
                        val selectedVal = selectImpl.getSelectedValue()
                        val selectedLabel = selectImpl.getOptions()
                            .firstOrNull { it.first == selectedVal }?.second
                            ?: selectedVal.orEmpty()
                        SelectObject(hdc, fontSmall)
                        drawSelectControl(
                            hdc = hdc,
                            theme = theme,
                            label = selectedLabel,
                            left = c2Left,
                            top = c2Top,
                            right = c2Right,
                            bottom = c2Bottom,
                            widget = selectImpl.nativeWidget,
                            hwndForHover = hwnd
                        )
                        hitRegions.add(
                            HitRegion(
                                left = c2Left,
                                top = c2Top,
                                right = c2Right,
                                bottom = c2Bottom,
                                tooltip = "Click to change node DataType (12 supported types)",
                                widget = selectImpl.nativeWidget,
                                onClick = { _, _ ->
                                    openSelectPopupMenu(hwnd, selectImpl, c2Left, c2Bottom)
                                }
                            )
                        )
                    }
                }

                // --- Column 3: Inline Node Action Buttons (+ Leaf, + Branch, ↑, ↓, Dup, Del) ---
                val cell3 = gridImpl.getOrMaterializeCellWidget(gridRowIdx * 4 + 3)
                if (cell3 != null) {
                    val actionBtns = collectDirectOrNestedButtons(cell3)
                    var ax = colLefts[3] + 6
                    val aTop = rTop + 5
                    val aBottom = rBottom - 5
                    for (btn in actionBtns) {
                        val label = btn.getText().trim()
                        if (label.isEmpty()) continue
                        val enabled = btn.isEnabled()
                        val bw = when {
                            label.length <= 1 -> 26
                            label.length <= 3 -> 36
                            else -> label.length * 6 + 14
                        }
                        if (ax + bw > colRights[3] - 4) break

                        val isDanger = label == "Del"
                        val isAdd = label.startsWith("+")
                        val bg = when {
                            !enabled -> theme.bgBase
                            isDanger -> theme.dangerBg
                            isAdd -> theme.accentBlueSoftBg
                            else -> theme.bgCard
                        }
                        val border = when {
                            !enabled -> theme.borderSubtle
                            isDanger -> theme.dangerBorder
                            isAdd -> theme.accentBlueBorder
                            else -> theme.borderSubtle
                        }
                        val fg = when {
                            !enabled -> theme.textMuted
                            isDanger -> theme.dangerText
                            isAdd -> theme.accentBlueText
                            else -> theme.textPrimary
                        }
                        drawRoundedButton(
                            hdc = hdc,
                            font = fontSmallBold,
                            label = label,
                            left = ax,
                            top = aTop,
                            right = ax + bw,
                            bottom = aBottom,
                            bg = bg,
                            border = border,
                            fg = fg,
                            radius = 4,
                            widget = if (enabled) btn.nativeWidget else null,
                            hwndForHover = hwnd
                        )
                        if (enabled) {
                            val capL = ax
                            val capR = ax + bw
                            hitRegions.add(
                                HitRegion(
                                    left = capL,
                                    top = aTop,
                                    right = capR,
                                    bottom = aBottom,
                                    widget = btn.nativeWidget,
                                    onClick = { _, _ -> btn.dispatchClick() }
                                )
                            )
                        }
                        ax += bw + 4
                    }
                }
            }

            // Right Vertical Scrollbar Rail for LazyGridTree
            val railLeft = gridRight - scrollRailW
            val railRight = gridRight - 1
            fillRectColor(hdc, railLeft, headerBottom + 1, railRight, gridBottom - 1, theme.bgElevated)
            drawLineColor(hdc, railLeft, gridTop + 1, railLeft, gridBottom - 1, theme.borderSubtle)

            val upBtnTop = headerBottom + 2
            val upBtnBottom = upBtnTop + 22
            val downBtnBottom = gridBottom - 2
            val downBtnTop = downBtnBottom - 22

            drawRoundedButton(
                hdc = hdc,
                font = fontSmallBold,
                label = "▲",
                left = railLeft + 2,
                top = upBtnTop,
                right = railRight - 2,
                bottom = upBtnBottom,
                bg = theme.bgCard,
                border = theme.borderSubtle,
                fg = theme.textPrimary,
                radius = 3
            )
            hitRegions.add(
                HitRegion(
                    left = railLeft + 2,
                    top = upBtnTop,
                    right = railRight - 2,
                    bottom = upBtnBottom,
                    tooltip = "Scroll tree up",
                    onClick = { _, _ ->
                        gridImpl.firstVisibleDataRow = (gridImpl.firstVisibleDataRow - 2).coerceAtLeast(0)
                    }
                )
            )

            drawRoundedButton(
                hdc = hdc,
                font = fontSmallBold,
                label = "▼",
                left = railLeft + 2,
                top = downBtnTop,
                right = railRight - 2,
                bottom = downBtnBottom,
                bg = theme.bgCard,
                border = theme.borderSubtle,
                fg = theme.textPrimary,
                radius = 3
            )
            hitRegions.add(
                HitRegion(
                    left = railLeft + 2,
                    top = downBtnTop,
                    right = railRight - 2,
                    bottom = downBtnBottom,
                    tooltip = "Scroll tree down",
                    onClick = { _, _ ->
                        gridImpl.firstVisibleDataRow = (gridImpl.firstVisibleDataRow + 2).coerceAtMost(maxFirstRow)
                    }
                )
            )

            val trackTop = upBtnBottom + 4
            val trackBottom = downBtnTop - 4
            val trackH = (trackBottom - trackTop).coerceAtLeast(10)
            if (totalDataRows > 0 && trackBottom > trackTop) {
                val thumbRatio = (rowsToDraw.toFloat() / totalDataRows.toFloat()).coerceIn(0.12f, 1f)
                val thumbH = max(18, (trackH * thumbRatio).roundToInt()).coerceAtMost(trackH)
                val trackRange = max(1, trackH - thumbH)
                val scrollFrac = if (maxFirstRow > 0) firstDataRow.toFloat() / maxFirstRow.toFloat() else 0f
                val thumbTop = trackTop + (trackRange * scrollFrac).roundToInt().coerceIn(0, trackRange)
                val thumbBottom = thumbTop + thumbH

                val isThumbDragging = isDraggingLazyGridScrollbarThumb
                val isThumbHovered = isThumbDragging ||
                    hoveredWidget === lazyGridScrollbarThumbWidget ||
                    isRectHovered(hwnd, railLeft, thumbTop, railRight, thumbBottom)

                val thumbFill = when {
                    isThumbDragging -> lightenColorRef(theme.accentBlue, -18)
                    isThumbHovered -> lightenColorRef(theme.accentBlue, 28)
                    else -> theme.accentBlue
                }
                val thumbBorder = when {
                    isThumbDragging -> rgb(255, 255, 255)
                    isThumbHovered -> lightenColorRef(theme.accentBlueBorder, 40)
                    else -> theme.accentBlueBorder
                }
                val thumbInset = if (isThumbHovered || isThumbDragging) 2 else 3
                drawRoundedRect(
                    hdc = hdc,
                    left = railLeft + thumbInset,
                    top = thumbTop,
                    right = railRight - thumbInset,
                    bottom = thumbBottom,
                    radius = 4,
                    fillColor = thumbFill,
                    borderColor = thumbBorder
                )

                val applyLazyGridScrollFromMouseY: (Int) -> Unit = { mouseY ->
                    val newThumbTop = (mouseY - lazyGridScrollbarDragGrabOffsetY).coerceIn(trackTop, trackTop + trackRange)
                    val frac = (newThumbTop - trackTop).toFloat() / trackRange.toFloat()
                    gridImpl.firstVisibleDataRow = (frac * maxFirstRow.toFloat()).roundToInt().coerceIn(0, maxFirstRow)
                }

                hitRegions.add(
                    HitRegion(
                        left = railLeft,
                        top = trackTop,
                        right = railRight,
                        bottom = trackBottom,
                        widget = lazyGridScrollbarRailWidget,
                        onClick = { _, my ->
                            isDraggingLazyGridScrollbarThumb = true
                            lazyGridScrollbarDragGrabOffsetY = if (my in thumbTop..thumbBottom) {
                                my - thumbTop
                            } else {
                                thumbH / 2
                            }
                            applyLazyGridScrollFromMouseY(my)
                        },
                        onDrag = { _, my ->
                            applyLazyGridScrollFromMouseY(my)
                        }
                    )
                )
                hitRegions.add(
                    HitRegion(
                        left = railLeft,
                        top = thumbTop,
                        right = railRight,
                        bottom = thumbBottom,
                        widget = lazyGridScrollbarThumbWidget,
                        onClick = { _, my ->
                            isDraggingLazyGridScrollbarThumb = true
                            lazyGridScrollbarDragGrabOffsetY = (my - thumbTop).coerceIn(0, thumbH)
                        },
                        onDrag = { _, my ->
                            applyLazyGridScrollFromMouseY(my)
                        }
                    )
                )
            }
        }

        // 6. Bottom Status Bar (.status-bar)
        val statusBarNode = findWidgetByClass(rootWidget, "status-bar")
        val statusTop = clientH - statusBarH - 6
        val statusBottom = clientH - 6
        drawRoundedRect(
            hdc = hdc,
            left = padX,
            top = statusTop,
            right = clientW - padX,
            bottom = statusBottom,
            radius = 5,
            fillColor = theme.bgPanel,
            borderColor = theme.borderSubtle
        )
        if (statusBarNode != null) {
            val statusTexts = collectDirectOrNestedTexts(statusBarNode).map { it.getText().trim() }.filter { it.isNotEmpty() }
            val pathTxt = statusTexts.getOrNull(1) ?: "root"
            val typeTxt = statusTexts.getOrNull(2) ?: "OBJECT"
            val msgTxt = statusTexts.getOrNull(3) ?: statusTexts.lastOrNull().orEmpty()

            SelectObject(hdc, fontSmallBold)
            drawTextInRect(hdc, "Selected: $pathTxt  [$typeTxt]", padX + 10, statusTop, padX + 340, statusBottom, theme.accentBlueText)
            SelectObject(hdc, fontSmall)
            drawTextInRect(hdc, msgTxt, padX + 350, statusTop, clientW - padX - 10, statusBottom, theme.textSecondary)
        }
    }

    /**
     * Renders an application structure with `<header class="app-header">`,
     * `<section class="metrics-strip">`, and `<main class="queues-container">` using native OS theming.
     */
    private fun renderHeaderMetricsQueuesLayout(
        hdc: HDC?,
        theme: SystemThemePalette,
        fontSmall: HFONT?,
        fontSmallBold: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        fontTitle: HFONT?,
        fontMetricBig: HFONT?,
        appHeader: DesktopNativeWidget,
        metricsStrip: DesktopNativeWidget,
        queuesContainer: DesktopNativeWidget,
        clientW: Int,
        clientH: Int
    ) {
        val headerH = 44
        val padX = 16

        // 1. Top Toolbar Header (<header class="app-header">) — no icon or app name (titlebar has both)
        fillRectColor(hdc, 0, 0, clientW, headerH, theme.bgPanel)
        drawLineColor(hdc, 0, headerH, clientW, headerH, theme.borderSubtle)

        val brandSubtitleNode = findWidgetByClass(appHeader, "brand-subtitle")
        val gpuBadgeNode = findWidgetByClass(appHeader, "gpu-badge")
        val headerActionsNode = findWidgetByClass(appHeader, "header-actions")

        val brandSubText = extractWidgetText(brandSubtitleNode).ifBlank { "Select a directory to begin conversion..." }
        val gpuBadgeText = extractWidgetText(gpuBadgeNode).ifBlank { "Probing GPU Hardware Encoder..." }

        // GPU Hardware Encoder Status Box on the Left
        val gpuBadgeLeft = padX
        val gpuBadgeW = max(180, gpuBadgeText.length * 7 + 20)
        val gpuBadgeTop = (headerH - 24) / 2
        fillRectColor(hdc, gpuBadgeLeft, gpuBadgeTop, gpuBadgeLeft + gpuBadgeW, gpuBadgeTop + 24, theme.bgCard)
        drawRectBorder(hdc, gpuBadgeLeft, gpuBadgeTop, gpuBadgeLeft + gpuBadgeW, gpuBadgeTop + 24, theme.borderSubtle)
        SelectObject(hdc, fontSmallBold)
        drawTextInRect(
            hdc = hdc,
            text = gpuBadgeText,
            left = gpuBadgeLeft + 8,
            top = gpuBadgeTop,
            right = gpuBadgeLeft + gpuBadgeW - 8,
            bottom = gpuBadgeTop + 24,
            colorRef = theme.textPrimary,
            center = true
        )

        // Active Directory Path Label next to GPU status
        val dirLabelLeft = gpuBadgeLeft + gpuBadgeW + 12
        SelectObject(hdc, fontNormal)
        drawTextInRect(hdc, brandSubText, dirLabelLeft, 10, clientW - 320, headerH - 10, theme.textSecondary)

        // Header Right Actions: Quality Status + Add Directory Button
        val headerButtons = mutableListOf<DesktopButtonNativeImpl<*>>()
        val headerTexts = mutableListOf<DesktopTextNativeImpl<*>>()
        collectPanelControls(
            headerActionsNode,
            headerButtons,
            mutableListOf(),
            mutableListOf(),
            mutableListOf(),
            headerTexts
        )

        val addDirBtn = headerButtons.firstOrNull()
        val addDirLabel = addDirBtn?.getText()?.ifBlank { "Add Directory" } ?: "Add Directory"
        val btnW = max(108, addDirLabel.length * 8 + 20)
        val btnH = 26
        val btnRight = clientW - padX
        val btnLeft = btnRight - btnW
        val btnTop = (headerH - btnH) / 2

        fillRectColor(hdc, btnLeft, btnTop, btnRight, btnTop + btnH, theme.bgCard)
        drawRectBorder(hdc, btnLeft, btnTop, btnRight, btnTop + btnH, theme.borderSubtle)
        SelectObject(hdc, fontNormal)
        drawTextInRect(
            hdc = hdc,
            text = addDirLabel,
            left = btnLeft + 6,
            top = btnTop,
            right = btnRight - 6,
            bottom = btnTop + btnH,
            colorRef = theme.textPrimary,
            center = true
        )
        if (addDirBtn != null) {
            hitRegions.add(
                HitRegion(
                    left = btnLeft,
                    top = btnTop,
                    right = btnRight,
                    bottom = btnTop + btnH,
                    onClick = { _, _ -> addDirBtn.dispatchClick() }
                )
            )
        }

        val qualityText = headerTexts.firstOrNull()?.getText()?.ifBlank { "Quality: 24 (Pending)" }
            ?: "Quality: 24 (Pending)"
        val qPillW = max(130, qualityText.length * 7 + 18)
        val qPillRight = btnLeft - 8
        val qPillLeft = qPillRight - qPillW
        val qPillTop = (headerH - 24) / 2
        fillRectColor(hdc, qPillLeft, qPillTop, qPillRight, qPillTop + 24, theme.bgCard)
        drawRectBorder(hdc, qPillLeft, qPillTop, qPillRight, qPillTop + 24, theme.borderSubtle)
        SelectObject(hdc, fontSmall)
        drawTextInRect(
            hdc = hdc,
            text = qualityText,
            left = qPillLeft + 6,
            top = qPillTop,
            right = qPillRight - 6,
            bottom = qPillTop + 24,
            colorRef = theme.textSecondary,
            center = true
        )

        // 2. Top 3-Column Metrics Strip (<section class="metrics-strip">, 1.5fr : 1.0fr : 1.1fr)
        val stripTop = headerH + 14
        val stripH = 100
        val stripBottom = stripTop + stripH
        val availW = clientW - padX * 2
        val gap = 14
        val totalCardsW = availW - gap * 2
        val card1W = (totalCardsW * 1.5 / 3.6).roundToInt()
        val card2W = (totalCardsW * 1.0 / 3.6).roundToInt()
        val card3W = totalCardsW - card1W - card2W

        val metricCards = findWidgetsByClass(metricsStrip, "metric-card")
        val cardRects = listOf(
            intArrayOf(padX, stripTop, padX + card1W, stripBottom),
            intArrayOf(padX + card1W + gap, stripTop, padX + card1W + gap + card2W, stripBottom),
            intArrayOf(padX + card1W + gap + card2W + gap, stripTop, clientW - padX, stripBottom)
        )

        for (i in 0 until min(3, metricCards.size)) {
            val cardNode = metricCards[i]
            val rect = cardRects[i]
            val cL = rect[0]
            val cT = rect[1]
            val cR = rect[2]
            val cB = rect[3]

            drawRoundedRect(
                hdc = hdc,
                left = cL,
                top = cT,
                right = cR,
                bottom = cB,
                radius = 10,
                fillColor = theme.bgPanel,
                borderColor = theme.borderSubtle
            )

            val headerLeftNode = findWidgetByClass(cardNode, "metric-header-title")
            val headerRightNode = findWidgetByClass(cardNode, "metric-header-right")
            val bigNode = findWidgetByClass(cardNode, "metric-big")
            val subNodes = findWidgetsByClass(cardNode, "metric-sub")
            val progressNode = findWidgetByClass(cardNode, "progress-track")

            val headerLeftText = extractWidgetText(headerLeftNode).uppercase()
            val headerRightText = extractWidgetText(headerRightNode).uppercase()
            val bigText = extractWidgetText(bigNode)
            val sub1Text = subNodes.getOrNull(0)?.let { extractWidgetText(it) }.orEmpty()
            val sub2Text = subNodes.getOrNull(1)?.let { extractWidgetText(it) }.orEmpty()

            SelectObject(hdc, fontSmallBold)
            drawTextInRect(hdc, headerLeftText, cL + 16, cT + 12, cR - 120, cT + 28, theme.textSecondary)
            drawTextInRect(
                hdc = hdc,
                text = headerRightText,
                left = cR - 145,
                top = cT + 12,
                right = cR - 16,
                bottom = cT + 28,
                colorRef = theme.textSecondary,
                alignRight = true
            )

            val isEtaHighlight = widgetHasClass(bigNode, "eta-highlight")
            SelectObject(hdc, fontMetricBig)
            drawTextInRect(
                hdc = hdc,
                text = bigText,
                left = cL + 16,
                top = cT + 33,
                right = cL + 140,
                bottom = cT + 66,
                colorRef = if (isEtaHighlight) theme.accentBlueText else theme.textPrimary
            )

            SelectObject(hdc, fontSmall)
            drawTextInRect(
                hdc = hdc,
                text = sub1Text,
                left = cL + 125,
                top = cT + 41,
                right = cR - 16,
                bottom = cT + 61,
                colorRef = theme.textSecondary,
                alignRight = true
            )

            if (progressNode != null) {
                val pct = extractProgressBarValue(progressNode).coerceIn(0f, 1f)
                val isProbing = widgetHasClass(progressNode, "probing-fill")
                val barL = cL + 16
                val barR = cR - 16
                val barT = cB - 20
                val barB = cB - 12
                drawRoundedRect(
                    hdc = hdc,
                    left = barL,
                    top = barT,
                    right = barR,
                    bottom = barB,
                    radius = 4,
                    fillColor = theme.bgBase,
                    borderColor = theme.borderSubtle
                )
                val fillW = ((barR - barL) * pct).roundToInt()
                if (fillW > 2) {
                    val barColor = if (isProbing) theme.accentAmber else theme.accentBlue
                    drawRoundedRect(
                        hdc = hdc,
                        left = barL,
                        top = barT,
                        right = barL + fillW,
                        bottom = barB,
                        radius = 4,
                        fillColor = barColor,
                        borderColor = barColor
                    )
                }
            } else if (sub2Text.isNotEmpty()) {
                SelectObject(hdc, fontSmall)
                drawTextInRect(
                    hdc = hdc,
                    text = sub2Text,
                    left = cL + 16,
                    top = cB - 26,
                    right = cR - 16,
                    bottom = cB - 8,
                    colorRef = theme.textMuted
                )
            }
        }

        // 3. Main Dual Queue View (<main class="queues-container">, 1fr : 1fr)
        val queuesTop = stripBottom + 14
        val queuesBottom = clientH - 18
        val colGap = 16
        val colW = (availW - colGap) / 2
        val queueColumns = findWidgetsByClass(queuesContainer, "queue-column")

        val colRects = listOf(
            intArrayOf(padX, queuesTop, padX + colW, queuesBottom),
            intArrayOf(padX + colW + colGap, queuesTop, clientW - padX, queuesBottom)
        )

        for (colIdx in 0 until min(2, queueColumns.size)) {
            val colNode = queueColumns[colIdx]
            val rect = colRects[colIdx]
            val qL = rect[0]
            val qT = rect[1]
            val qR = rect[2]
            val qB = rect[3]

            drawRoundedRect(
                hdc = hdc,
                left = qL,
                top = qT,
                right = qR,
                bottom = qB,
                radius = 10,
                fillColor = theme.bgPanel,
                borderColor = theme.borderSubtle
            )

            // Column Header (height = 44px)
            val colHeaderBottom = qT + 44
            drawLineColor(hdc, qL + 1, colHeaderBottom, qR - 1, colHeaderBottom, theme.borderSubtle)

            val colTitleNode = findWidgetByClass(colNode, "queue-column-title")
            val colTitleText = extractWidgetText(colTitleNode)
            SelectObject(hdc, fontBold)
            drawTextInRect(hdc, colTitleText, qL + 16, qT + 11, qL + 240, colHeaderBottom - 8, theme.textPrimary)

            // Queue Counter Pills (Active, Queued, Done, Failed)
            val countersContainer = findWidgetByClass(colNode, "queue-counters")
            val pillWidgets = countersContainer?.children.orEmpty()
            var pillRight = qR - 14
            val pillTop = qT + 11
            val pillH = 22

            for (pillNode in pillWidgets.asReversed()) {
                val pText = extractWidgetText(pillNode)
                if (pText.isEmpty()) continue
                val pW = max(58, pText.length * 6 + 20)
                val pLeft = pillRight - pW

                val isActivePill = widgetHasClass(pillNode, "active")
                val isDonePill = widgetHasClass(pillNode, "done")
                val isFailedPill = widgetHasClass(pillNode, "failed")

                val pBg = when {
                    isActivePill -> theme.accentBlueSoftBg
                    isDonePill -> theme.accentGreenSoftBg
                    isFailedPill -> theme.accentRedSoftBg
                    else -> theme.bgCard
                }
                val pBorder = when {
                    isActivePill -> theme.accentBlueBorder
                    isDonePill -> theme.accentGreenBorder
                    isFailedPill -> theme.accentRedBorder
                    else -> theme.borderSubtle
                }
                val pFg = when {
                    isActivePill -> theme.accentBlueText
                    isDonePill -> theme.accentGreen
                    isFailedPill -> theme.accentRed
                    else -> theme.textSecondary
                }

                drawRoundedRect(
                    hdc = hdc,
                    left = pLeft,
                    top = pillTop,
                    right = pillRight,
                    bottom = pillTop + pillH,
                    radius = 11,
                    fillColor = pBg,
                    borderColor = pBorder
                )
                SelectObject(hdc, fontSmallBold)
                drawTextInRect(
                    hdc = hdc,
                    text = pText,
                    left = pLeft + 6,
                    top = pillTop,
                    right = pillRight - 6,
                    bottom = pillTop + pillH,
                    colorRef = pFg,
                    center = true
                )
                pillRight = pLeft - 6
            }

            // Column Body (.queue-body)
            val queueBody = findWidgetByClass(colNode, "queue-body")
            val bodyLeft = qL + 14
            val bodyRight = qR - 14
            var curY = colHeaderBottom + 14
            val maxBodyBottom = qB - 14

            for (bodyChild in queueBody?.children.orEmpty()) {
                if (curY + 26 > maxBodyBottom) break

                when {
                    widgetHasClass(bodyChild, "empty-state") -> {
                        val emptyH = 64
                        val boxBottom = min(maxBodyBottom, curY + emptyH)
                        drawDashedRoundedRect(
                            hdc = hdc,
                            left = bodyLeft,
                            top = curY,
                            right = bodyRight,
                            bottom = boxBottom,
                            radius = 8,
                            fillColor = theme.bgBase,
                            borderColor = theme.borderDash
                        )
                        val msg = extractWidgetText(bodyChild).ifBlank { "Waiting for directory selection..." }
                        SelectObject(hdc, fontNormal)
                        drawTextInRect(
                            hdc = hdc,
                            text = msg,
                            left = bodyLeft + 12,
                            top = curY,
                            right = bodyRight - 12,
                            bottom = boxBottom,
                            colorRef = theme.textMuted,
                            center = true
                        )
                        curY = boxBottom + 10
                    }

                    widgetHasClass(bodyChild, "section-title") -> {
                        val secText = extractWidgetText(bodyChild).uppercase()
                        SelectObject(hdc, fontSmallBold)
                        drawTextInRect(
                            hdc = hdc,
                            text = secText,
                            left = bodyLeft + 2,
                            top = curY,
                            right = bodyRight,
                            bottom = curY + 18,
                            colorRef = theme.textMuted
                        )
                        curY += 22
                    }

                    widgetHasClass(bodyChild, "slot-card") -> {
                        val cardH = 82
                        val cardB = min(maxBodyBottom, curY + cardH)
                        drawRoundedRect(
                            hdc = hdc,
                            left = bodyLeft,
                            top = curY,
                            right = bodyRight,
                            bottom = cardB,
                            radius = 8,
                            fillColor = theme.bgCard,
                            borderColor = theme.borderSubtle
                        )
                        val fileNode = findWidgetByClass(bodyChild, "slot-filename")
                        val pctNode = findWidgetByClass(bodyChild, "slot-pct")
                        val progNode = findWidgetByClass(bodyChild, "progress-track")
                        val tagNodes = findWidgetsByClass(bodyChild, "tag")

                        SelectObject(hdc, fontBold)
                        drawTextInRect(
                            hdc = hdc,
                            text = extractWidgetText(fileNode),
                            left = bodyLeft + 12,
                            top = curY + 8,
                            right = bodyRight - 72,
                            bottom = curY + 26,
                            colorRef = theme.textPrimary
                        )
                        drawTextInRect(
                            hdc = hdc,
                            text = extractWidgetText(pctNode),
                            left = bodyRight - 70,
                            top = curY + 8,
                            right = bodyRight - 12,
                            bottom = curY + 26,
                            colorRef = theme.accentBlueText,
                            alignRight = true
                        )

                        val pct = extractProgressBarValue(progNode).coerceIn(0f, 1f)
                        val pL = bodyLeft + 12
                        val pR = bodyRight - 12
                        val pT = curY + 32
                        val pB = curY + 38
                        drawRoundedRect(hdc, pL, pT, pR, pB, 3, theme.bgBase, theme.borderSubtle)
                        val fillW = ((pR - pL) * pct).roundToInt()
                        if (fillW > 2) {
                            drawRoundedRect(hdc, pL, pT, pL + fillW, pB, 3, theme.accentBlue, theme.accentBlue)
                        }

                        var tagX = bodyLeft + 12
                        val tagY = curY + 46
                        SelectObject(hdc, fontSmallBold)
                        for (tagNode in tagNodes) {
                            val tStr = extractWidgetText(tagNode)
                            if (tStr.isEmpty()) continue
                            val tW = max(48, tStr.length * 6 + 16)
                            if (tagX + tW > bodyRight - 10) break
                            val isVid = widgetHasClass(tagNode, "video")
                            val isAud = widgetHasClass(tagNode, "audio")
                            val isSub = widgetHasClass(tagNode, "sub")
                            val tBg = when {
                                isVid -> theme.accentBlueSoftBg
                                isAud -> theme.accentGreenSoftBg
                                isSub -> theme.accentAmberSoftBg
                                else -> theme.bgElevated
                            }
                            val tFg = when {
                                isVid -> theme.accentBlueText
                                isAud -> theme.accentGreen
                                isSub -> theme.accentAmber
                                else -> theme.textSecondary
                            }
                            drawRoundedRect(hdc, tagX, tagY, tagX + tW, tagY + 20, 4, tBg, theme.borderSubtle)
                            drawTextInRect(hdc, tStr, tagX + 6, tagY, tagX + tW - 6, tagY + 20, tFg, center = true)
                            tagX += tW + 6
                        }
                        curY = cardB + 8
                    }

                    widgetHasClass(bodyChild, "queue-item") -> {
                        val rowH = 34
                        val rowB = min(maxBodyBottom, curY + rowH)
                        drawRoundedRect(
                            hdc = hdc,
                            left = bodyLeft,
                            top = curY,
                            right = bodyRight,
                            bottom = rowB,
                            radius = 6,
                            fillColor = theme.bgCard,
                            borderColor = theme.borderSubtle
                        )
                        val nameNode = findWidgetByClass(bodyChild, "queue-item-name")
                        val tagNodes = findWidgetsByClass(bodyChild, "tag")

                        SelectObject(hdc, fontNormal)
                        drawTextInRect(
                            hdc = hdc,
                            text = extractWidgetText(nameNode),
                            left = bodyLeft + 10,
                            top = curY,
                            right = bodyRight - 180,
                            bottom = rowB,
                            colorRef = theme.textPrimary
                        )

                        var tagRight = bodyRight - 10
                        val tagTop = curY + 7
                        SelectObject(hdc, fontSmallBold)
                        for (tagNode in tagNodes.asReversed()) {
                            val tStr = extractWidgetText(tagNode)
                            if (tStr.isEmpty()) continue
                            val tW = max(52, tStr.length * 6 + 14)
                            val tagLeft = tagRight - tW
                            val isDone = widgetHasClass(tagNode, "done")
                            val isErr = widgetHasClass(tagNode, "err")
                            val isVid = widgetHasClass(tagNode, "video")
                            val isAud = widgetHasClass(tagNode, "audio")
                            val tBg = when {
                                isDone || isAud -> theme.accentGreenSoftBg
                                isErr -> theme.accentRedSoftBg
                                isVid -> theme.accentBlueSoftBg
                                else -> theme.bgElevated
                            }
                            val tFg = when {
                                isDone || isAud -> theme.accentGreen
                                isErr -> theme.accentRed
                                isVid -> theme.accentBlueText
                                else -> theme.textSecondary
                            }
                            drawRoundedRect(hdc, tagLeft, tagTop, tagRight, tagTop + 20, 4, tBg, theme.borderSubtle)
                            drawTextInRect(hdc, tStr, tagLeft + 5, tagTop, tagRight - 5, tagTop + 20, tFg, center = true)
                            tagRight = tagLeft - 6
                        }
                        curY = rowB + 6
                    }
                }
            }
        }
    }

    private fun collectStudioWidgets(
        widget: DesktopNativeWidget?,
        toolPalettes: MutableList<DesktopToolPaletteNativeImpl<*>>,
        dockPanels: MutableList<DesktopDockPanelNativeImpl<*>>,
        canvases: MutableList<DesktopNativeWidget>
    ) {
        if (widget == null || !widget.visible || widget.disposed) return
        val impl = widget.attributes["nativeImpl"]
        if (impl is DesktopToolPaletteNativeImpl<*>) {
            toolPalettes.add(impl)
        }
        if (impl is DesktopDockPanelNativeImpl<*>) {
            dockPanels.add(impl)
        }
        if (widget.attributes.containsKey("captureRgba")) {
            canvases.add(widget)
        }
        for (child in widget.children) {
            collectStudioWidgets(child, toolPalettes, dockPanels, canvases)
        }
    }

    private fun collectPanelControls(
        widget: DesktopNativeWidget?,
        buttons: MutableList<DesktopButtonNativeImpl<*>>,
        checkboxes: MutableList<DesktopCheckboxNativeImpl<*>>,
        selects: MutableList<DesktopSelectNativeImpl<*>>,
        sliders: MutableList<DesktopRangeSliderNativeImpl<*>>,
        texts: MutableList<DesktopTextNativeImpl<*>>
    ) {
        if (widget == null || !widget.visible || widget.disposed || isWidgetMountedInTitleBar(widget)) return
        when (val impl = widget.attributes["nativeImpl"]) {
            is DesktopButtonNativeImpl<*> -> buttons.add(impl)
            is DesktopCheckboxNativeImpl<*> -> checkboxes.add(impl)
            is DesktopSelectNativeImpl<*> -> selects.add(impl)
            is DesktopRangeSliderNativeImpl<*> -> sliders.add(impl)
            is DesktopTextNativeImpl<*> -> texts.add(impl)
        }
        for (child in widget.children) {
            collectPanelControls(child, buttons, checkboxes, selects, sliders, texts)
        }
    }

    private fun renderTopViewportToolbar(
        hwnd: HWND,
        hdc: HDC?,
        theme: SystemThemePalette,
        fontSmall: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        rootWidget: DesktopNativeWidget?,
        dockPanels: List<DesktopDockPanelNativeImpl<*>>,
        clientW: Int,
        topBarH: Int
    ) {
        val dockPanelWidgets = dockPanels.map { it.nativeWidget }.toSet()
        val windowTitleBarWidgets = collectTitleBarWidgetSet(activeWindowImpl?.getTitleBarInstructions())
        val buttons = mutableListOf<DesktopButtonNativeImpl<*>>()
        val checkboxes = mutableListOf<DesktopCheckboxNativeImpl<*>>()
        val selects = mutableListOf<DesktopSelectNativeImpl<*>>()
        val sliders = mutableListOf<DesktopRangeSliderNativeImpl<*>>()
        val texts = mutableListOf<DesktopTextNativeImpl<*>>()

        fun collectOutsideDockPanels(w: DesktopNativeWidget?) {
            if (w == null || !w.visible || w.disposed || w in dockPanelWidgets || isWidgetMountedInTitleBar(w, windowTitleBarWidgets)) return
            when (val impl = w.attributes["nativeImpl"]) {
                is DesktopButtonNativeImpl<*> -> buttons.add(impl)
                is DesktopCheckboxNativeImpl<*> -> checkboxes.add(impl)
                is DesktopSelectNativeImpl<*> -> selects.add(impl)
                is DesktopRangeSliderNativeImpl<*> -> sliders.add(impl)
                is DesktopTextNativeImpl<*> -> texts.add(impl)
            }
            for (c in w.children) collectOutsideDockPanels(c)
        }
        collectOutsideDockPanels(rootWidget)

        var x = 10
        SelectObject(hdc, fontSmall)
        val toolbarButtons = buttons.filterNot {
            val t = it.getText().trim()
            t == "✨ New" || t == "📂 Open"
        }
        for (btn in toolbarButtons.take(10)) {
            val iconSpec = btn.getIcon()
            val label = btn.getText()
            val isIconOnly = !iconSpec.isNullOrBlank() && label.isBlank()
            val w = when {
                isIconOnly -> 28
                !iconSpec.isNullOrBlank() -> max(64, label.length * 7 + 34)
                else -> max(54, label.ifBlank { "Action" }.length * 7 + 18)
            }
            if (x + w > clientW - 220) break
            drawButtonControl(
                hdc = hdc,
                theme = theme,
                label = if (isIconOnly) "" else label.ifBlank { "Action" },
                icon = iconSpec,
                left = x,
                top = 6,
                right = x + w,
                bottom = topBarH - 6,
                primary = false,
                widget = btn.nativeWidget
            )
            val bLeft = x
            val bRight = x + w
            hitRegions.add(
                HitRegion(
                    left = bLeft,
                    top = 6,
                    right = bRight,
                    bottom = topBarH - 6,
                    tooltip = btn.getTooltipText(),
                    widget = btn.nativeWidget,
                    onClick = { _, _ -> btn.dispatchClick() }
                )
            )
            x += w + 5
        }

        for (sel in selects.take(2)) {
            val curVal = sel.getSelectedValue() ?: ""
            val curLabel = sel.getOptions().firstOrNull { it.first == curVal }?.second ?: curVal
            val w = max(96, min(160, curLabel.length * 7 + 28))
            if (x + w > clientW - 200) break
            drawSelectControl(hdc, theme, curLabel.ifBlank { "Select..." }, x, 6, x + w, topBarH - 6, sel.nativeWidget)
            val sLeft = x
            hitRegions.add(
                HitRegion(
                    left = sLeft,
                    top = 6,
                    right = sLeft + w,
                    bottom = topBarH - 6,
                    tooltip = sel.getTooltipText(),
                    widget = sel.nativeWidget,
                    onClick = { mx, my -> openSelectPopupMenu(hwnd, sel, mx, my) }
                )
            )
            x += w + 6
        }

        for (cb in checkboxes.take(3)) {
            val label = cb.getLabel()
            val w = max(64, label.length * 7 + 24)
            if (x + w > clientW - 180) break
            drawCheckboxControl(hdc, theme, label, cb.isChecked(), x, 8, x + w, topBarH - 8, cb.nativeWidget)
            val cLeft = x
            val cRight = x + w
            hitRegions.add(
                HitRegion(
                    left = cLeft,
                    top = 6,
                    right = cRight,
                    bottom = topBarH - 6,
                    tooltip = cb.getTooltipText(),
                    widget = cb.nativeWidget,
                    onClick = { _, _ -> cb.setChecked(!cb.isChecked()) }
                )
            )
            x += w + 6
        }

        val statusText = texts.lastOrNull {
            it.getText().contains("Zoom:", ignoreCase = true) || it.getText().contains("Tool:", ignoreCase = true)
        }?.getText() ?: texts.firstOrNull()?.getText() ?: "Zoom: 100%"
        drawTextInRect(hdc, statusText, x + 6, 8, clientW - 12, topBarH - 8, theme.accentBlueText, alignRight = true)
    }

    private fun renderLeftToolbox(
        hwnd: HWND,
        hdc: HDC?,
        theme: SystemThemePalette,
        fontSmall: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        toolPalette: DesktopToolPaletteNativeImpl<*>?,
        toolboxDockPanel: DesktopDockPanelNativeImpl<*>?,
        width: Int,
        top: Int,
        bottom: Int,
        isSeparatePanelWindow: Boolean = false
    ) {
        var y = top
        val collapsed = if (isSeparatePanelWindow) false else (toolboxDockPanel?.isCollapsed() == true)
        if (!isSeparatePanelWindow) {
            if (toolboxDockPanel != null) {
                renderCollapsiblePanelHeader(
                    hwnd = hwnd,
                    hdc = hdc,
                    theme = theme,
                    fontSmall = fontSmall,
                    fontBold = fontBold,
                    panel = toolboxDockPanel,
                    left = 6,
                    top = y,
                    right = width - 6,
                    height = 24
                )
            } else {
                fillRectColor(hdc, 6, y, width - 6, y + 24, theme.bgElevated)
                drawRectBorder(hdc, 6, y, width - 6, y + 24, theme.borderSubtle)
                SelectObject(hdc, fontBold)
                drawTextInRect(hdc, "▼  Toolbox - Tool Options", 12, y + 2, width - 10, y + 22, theme.textPrimary)
            }
            y += 28
        }

        // Context-sensitive Layer Mode Badge (Vector Tools vs. Raster Tools)
        if (toolboxDockPanel != null) {
            val badgeTexts = mutableListOf<DesktopTextNativeImpl<*>>()
            collectPanelControls(
                toolboxDockPanel.nativeWidget,
                mutableListOf(),
                mutableListOf(),
                mutableListOf(),
                mutableListOf(),
                badgeTexts
            )
            val contextBadgeText = badgeTexts.firstOrNull()?.getText()?.trim().orEmpty()
            if (contextBadgeText.isNotEmpty() && y + 24 < bottom) {
                val isRasterMode = contextBadgeText.contains("RASTER", ignoreCase = true)
                val badgeBg = if (isRasterMode) theme.bgCard else theme.accentBlueSoftBg
                val badgeBorder = if (isRasterMode) theme.borderStrong else theme.accentBlueBorder
                val badgeFg = if (isRasterMode) theme.textPrimary else theme.accentBlueText
                drawRoundedRect(hdc, 6, y, width - 6, y + 22, 4, badgeBg, badgeBorder)
                SelectObject(hdc, fontBold)
                drawTextInRect(hdc, contextBadgeText, 10, y + 2, width - 10, y + 20, badgeFg, center = true)
                y += 26
            }
        }

        // ToolPalette tools ALWAYS render as icon-only buttons with a tooltip for what they do!
        SelectObject(hdc, fontNormal)
        val tools = toolPalette?.getTools().orEmpty()
        val activeToolId = toolPalette?.getActiveToolId()
        val cols = if (width >= 180) 4 else 3
        val padX = 8
        val gap = 6
        val btnW = max(32, (width - padX * 2 - gap * (cols - 1)) / cols)
        val btnH = 32

        tools.forEachIndexed { idx, item ->
            val col = idx % cols
            val row = idx / cols
            val bx = padX + col * (btnW + gap)
            val by = y + row * (btnH + gap)
            if (by + btnH < bottom - 80) {
                val isSelected = item.id == activeToolId
                val shortcutSuffix = item.shortcut?.key?.let { " ($it)" } ?: ""
                val fullTooltip = if (item.tooltip.isNotBlank() && item.tooltip != item.name) {
                    "${item.name}$shortcutSuffix — ${item.tooltip}"
                } else {
                    "${item.name}$shortcutSuffix"
                }
                val iconKey = item.iconSvgOrPath.ifBlank { item.id }
                drawButtonControl(
                    hdc = hdc,
                    theme = theme,
                    label = "", // Icon-only! No text inside tool palette buttons.
                    icon = iconKey,
                    left = bx,
                    top = by,
                    right = bx + btnW,
                    bottom = by + btnH,
                    primary = isSelected,
                    hwndForHover = hwnd
                )
                val toolId = item.id
                hitRegions.add(
                    HitRegion(
                        left = bx,
                        top = by,
                        right = bx + btnW,
                        bottom = by + btnH,
                        tooltip = fullTooltip,
                        onClick = { _, _ -> toolPalette?.setActiveToolId(toolId) }
                    )
                )
            }
        }

        val rowsCount = (tools.size + cols - 1) / cols
        y += rowsCount * (btnH + gap) + 10

        // GIMP-Style Overlapping Foreground / Background Swatch Widget + Swap (⇄) & Default Reset (⬛⬜)
        if (toolPalette != null && y + 48 < bottom) {
            val fg = toolPalette.getForegroundColor()
            val bg = toolPalette.getBackgroundColor()
            val swatchOriginX = 12
            val swatchOriginY = y

            // Background color box (bottom-right overlapping)
            fillRectColor(hdc, swatchOriginX + 18, swatchOriginY + 14, swatchOriginX + 50, swatchOriginY + 42, rgb(bg.r, bg.g, bg.b))
            drawRectBorder(hdc, swatchOriginX + 18, swatchOriginY + 14, swatchOriginX + 50, swatchOriginY + 42, theme.textPrimary)

            // Foreground color box (top-left overlapping)
            fillRectColor(hdc, swatchOriginX, swatchOriginY, swatchOriginX + 32, swatchOriginY + 28, rgb(fg.r, fg.g, fg.b))
            drawRectBorder(hdc, swatchOriginX, swatchOriginY, swatchOriginX + 32, swatchOriginY + 28, rgb(255, 255, 255))

            hitRegions.add(
                HitRegion(
                    left = swatchOriginX,
                    top = swatchOriginY,
                    right = swatchOriginX + 52,
                    bottom = swatchOriginY + 44,
                    tooltip = "FG: RGB(${fg.r},${fg.g},${fg.b}) / BG: RGB(${bg.r},${bg.g},${bg.b}) — Click to swap FG/BG (X)",
                    onClick = { _, _ -> toolPalette.dispatchSwapColors() }
                )
            )

            // Swap FG/BG icon button (top-right of swatch cluster)
            drawButtonControl(
                hdc = hdc,
                theme = theme,
                label = "",
                icon = "swap",
                left = swatchOriginX + 58,
                top = swatchOriginY,
                right = swatchOriginX + 86,
                bottom = swatchOriginY + 22,
                primary = false,
                hwndForHover = hwnd
            )
            val sy = swatchOriginY
            hitRegions.add(
                HitRegion(
                    left = swatchOriginX + 58,
                    top = sy,
                    right = swatchOriginX + 86,
                    bottom = sy + 22,
                    tooltip = "Swap Foreground & Background Colors (X)",
                    onClick = { _, _ -> toolPalette.dispatchSwapColors() }
                )
            )

            // Reset Default Black/White icon button (bottom-right of swatch cluster)
            drawButtonControl(
                hdc = hdc,
                theme = theme,
                label = "D",
                icon = null,
                left = swatchOriginX + 58,
                top = swatchOriginY + 24,
                right = swatchOriginX + 86,
                bottom = swatchOriginY + 44,
                primary = false,
                hwndForHover = hwnd
            )
            hitRegions.add(
                HitRegion(
                    left = swatchOriginX + 58,
                    top = sy + 24,
                    right = swatchOriginX + 86,
                    bottom = sy + 44,
                    tooltip = "Reset Foreground/Background to Default Black & White (D)",
                    onClick = { _, _ ->
                        toolPalette.setForegroundColor(RgbaColor(0, 0, 0, 255))
                        toolPalette.setBackgroundColor(RgbaColor(255, 255, 255, 255))
                    }
                )
            )
            y += 50
        }

        if (!collapsed && toolboxDockPanel != null) {
            // GIMP "Tool Options" section divider header below the tool grid & color swatches
            fillRectColor(hdc, 6, y, width - 6, y + 22, theme.bgElevated)
            drawRectBorder(hdc, 6, y, width - 6, y + 22, theme.borderSubtle)
            SelectObject(hdc, fontBold)
            val activeToolObj = tools.firstOrNull { it.id == activeToolId }
            val toolOptsHeader = activeToolObj?.name?.let { "Tool Options — $it" } ?: "Tool Options"
            drawTextInRect(hdc, toolOptsHeader, 10, y + 2, width - 10, y + 20, theme.textPrimary)
            y += 28

            val buttons = mutableListOf<DesktopButtonNativeImpl<*>>()
            val checkboxes = mutableListOf<DesktopCheckboxNativeImpl<*>>()
            val selects = mutableListOf<DesktopSelectNativeImpl<*>>()
            val sliders = mutableListOf<DesktopRangeSliderNativeImpl<*>>()
            val texts = mutableListOf<DesktopTextNativeImpl<*>>()
            collectPanelControls(toolboxDockPanel.nativeWidget, buttons, checkboxes, selects, sliders, texts)

            SelectObject(hdc, fontSmall)
            for (txt in texts.take(2)) {
                val t = txt.getText().trim()
                if (t.isNotEmpty() && y + 18 < bottom) {
                    drawTextInRect(hdc, t, 10, y, width - 10, y + 16, theme.textSecondary)
                    y += 18
                }
            }

            val defaultSliderLabels = listOf("Size (px)", "Stroke / Opacity", "Hardness / Tolerance")
            sliders.take(3).forEachIndexed { i, slider ->
                if (y + 36 < bottom) {
                    val labelPrefix = defaultSliderLabels.getOrElse(i) { "Value" }
                    val title = "$labelPrefix: ${slider.getValue().roundToInt()}"
                    drawTextInRect(hdc, title, 10, y, width - 10, y + 15, theme.textSecondary)
                    y += 16
                    drawSliderControl(hdc, theme, slider, 10, y, width - 12, y + 14)
                    val sTop = y - 2
                    val sBottom = y + 16
                    val sLeft = 10
                    val sRight = width - 12
                    hitRegions.add(
                        HitRegion(
                            left = sLeft,
                            top = sTop,
                            right = sRight,
                            bottom = sBottom,
                            tooltip = slider.getTooltipText() ?: title,
                            widget = slider.nativeWidget,
                            onClick = { mx, _ ->
                                slider.setNormalizedFraction((mx - sLeft).toDouble() / max(1, sRight - sLeft).toDouble())
                            },
                            onDrag = { mx, _ ->
                                slider.setNormalizedFraction((mx - sLeft).toDouble() / max(1, sRight - sLeft).toDouble())
                            }
                        )
                    )
                    y += 20
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun renderCenterCanvas2D(
        hwnd: HWND,
        hdc: HDC?,
        theme: SystemThemePalette,
        fontSmall: HFONT?,
        fontSmallBold: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        fontTitle: HFONT?,
        rootWidget: DesktopNativeWidget?,
        canvasWidget: DesktopNativeWidget?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        val availW = max(64, right - left)
        val availH = max(64, bottom - top)
        val tabContainer = findTabContainer(rootWidget)
        val hasOpenCanvasFn = canvasWidget?.attributes?.get("hasOpenCanvas") as? (() -> Boolean)
        val hasOpenCanvas = hasOpenCanvasFn?.invoke()
            ?: (tabContainer != null && tabContainer.getTabTitles().isNotEmpty())

        if (!hasOpenCanvas || canvasWidget == null) {
            canvasViewportRect = intArrayOf(-1, -1, -1, -1)
            renderEmptyCanvasViewportState(
                hwnd = hwnd,
                hdc = hdc,
                theme = theme,
                fontSmall = fontSmall,
                fontSmallBold = fontSmallBold,
                fontNormal = fontNormal,
                fontBold = fontBold,
                fontTitle = fontTitle,
                rootWidget = rootWidget,
                canvasWidget = canvasWidget,
                left = left,
                top = top,
                right = right,
                bottom = bottom
            )
            return
        }

        // Document Tabs Bar at the top of the canvas viewport
        val tabBarHeight = 28
        renderDocumentTabsBar(
            hwnd = hwnd,
            hdc = hdc,
            theme = theme,
            fontSmall = fontSmall,
            fontBold = fontBold,
            rootWidget = rootWidget,
            canvasWidget = canvasWidget,
            left = left - 4,
            top = top - 4,
            right = right + 4,
            bottom = top - 4 + tabBarHeight
        )

        val stageTop = top + tabBarHeight
        val stageAvailH = max(64, bottom - stageTop)
        fillRectColor(hdc, left, stageTop, right, bottom, theme.bgBase)

        val captureBgraFn = canvasWidget.attributes["captureBgra"] as? (() -> ByteArray)
        val widthFn = canvasWidget.attributes["surfaceWidth"] as? (() -> Int)
        val heightFn = canvasWidget.attributes["surfaceHeight"] as? (() -> Int)
        val surfW = (widthFn?.invoke() ?: 320).coerceAtLeast(1)
        val surfH = (heightFn?.invoke() ?: 240).coerceAtLeast(1)
        activeCanvasSurfaceSize = surfW to surfH

        val pad = 12
        val maxStageW = max(32, availW - pad * 2)
        val maxStageH = max(32, stageAvailH - pad * 2)
        val scale = min(maxStageW.toFloat() / surfW.toFloat(), maxStageH.toFloat() / surfH.toFloat()).coerceAtLeast(0.1f)
        val drawW = (surfW * scale).roundToInt().coerceAtLeast(1)
        val drawH = (surfH * scale).roundToInt().coerceAtLeast(1)
        val drawLeft = left + (availW - drawW) / 2
        val drawTop = stageTop + (stageAvailH - drawH) / 2
        canvasViewportRect = intArrayOf(drawLeft, drawTop, drawLeft + drawW, drawTop + drawH)

        val bgra = captureBgraFn?.invoke()
        if (bgra != null && bgra.size >= surfW * surfH * 4) {
            memScoped {
                val bmi = alloc<BITMAPINFO>()
                bmi.bmiHeader.biSize = sizeOf<BITMAPINFOHEADER>().toUInt()
                bmi.bmiHeader.biWidth = surfW
                bmi.bmiHeader.biHeight = -surfH
                bmi.bmiHeader.biPlanes = 1u
                bmi.bmiHeader.biBitCount = 32u
                bmi.bmiHeader.biCompression = BI_RGB.toUInt()

                SetStretchBltMode(hdc, HALFTONE)
                bgra.usePinned { pinned ->
                    StretchDIBits(
                        hdc,
                        drawLeft,
                        drawTop,
                        drawW,
                        drawH,
                        0,
                        0,
                        surfW,
                        surfH,
                        pinned.addressOf(0),
                        bmi.ptr,
                        DIB_RGB_COLORS.toUInt(),
                        SRCCOPY
                    )
                }
            }
        } else {
            fillRectColor(hdc, drawLeft, drawTop, drawLeft + drawW, drawTop + drawH, rgb(255, 255, 255))
        }
        drawRectBorder(hdc, drawLeft - 1, drawTop - 1, drawLeft + drawW + 1, drawTop + drawH + 1, theme.accentBlueBorder)
    }

    /**
     * Renders the document tab strip above the open canvas stage,
     * allowing users to switch between open documents, close individual tabs (`×`),
     * or create a new tab (`+`).
     */
    @Suppress("UNCHECKED_CAST")
    private fun renderDocumentTabsBar(
        hwnd: HWND,
        hdc: HDC?,
        theme: SystemThemePalette,
        fontSmall: HFONT?,
        fontBold: HFONT?,
        rootWidget: DesktopNativeWidget?,
        canvasWidget: DesktopNativeWidget?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        fillRectColor(hdc, left, top, right, bottom, theme.bgPanel)
        drawLineColor(hdc, left, bottom, right, bottom, theme.borderSubtle)

        val tabContainer = findTabContainer(rootWidget)
        val titles = tabContainer?.getTabTitles().orEmpty().ifEmpty { listOf("Document 1") }
        val activeIndex = (tabContainer?.getActiveTabIndex() ?: 0).coerceIn(0, max(0, titles.lastIndex))

        var tabX = left + 4
        for ((idx, title) in titles.withIndex()) {
            val isActive = idx == activeIndex
            val tabW = (title.length * 6 + 44).coerceIn(136, 260)
            if (tabX + tabW > right - 36) break
            val tabRight = tabX + tabW
            val closeLeft = tabRight - 22
            val closeRight = tabRight - 4
            val closeTop = top + 5
            val closeBottom = bottom - 5

            val tabHovered = isRectHovered(hwnd, tabX, top + 2, tabRight, bottom)
            val closeHovered = isRectHovered(hwnd, closeLeft, closeTop, closeRight, closeBottom)

            val tabBg = when {
                isActive -> theme.bgCard
                tabHovered -> theme.bgElevated
                else -> theme.bgPanel
            }
            fillRectColor(hdc, tabX, top + 2, tabRight, bottom, tabBg)
            drawRectBorder(hdc, tabX, top + 2, tabRight, bottom + 1, theme.borderSubtle)
            if (isActive) {
                // Active top indicator bar + seamless bottom blend into canvas area
                fillRectColor(hdc, tabX, top + 2, tabRight, top + 4, theme.accentBlue)
                drawLineColor(hdc, tabX + 1, bottom, tabRight - 1, bottom, theme.bgCard)
            }

            SelectObject(hdc, if (isActive) fontBold else fontSmall)
            drawTextInRect(
                hdc,
                title,
                tabX + 8,
                top + 3,
                closeLeft - 4,
                bottom - 1,
                if (isActive) theme.textPrimary else theme.textSecondary
            )

            // Tab close button (×)
            if (closeHovered) {
                drawRoundedRect(hdc, closeLeft, closeTop, closeRight, closeBottom, 3, theme.dangerBg, theme.dangerBorder)
            }
            SelectObject(hdc, fontSmall)
            drawTextInRect(
                hdc,
                "×",
                closeLeft,
                closeTop - 1,
                closeRight,
                closeBottom,
                if (closeHovered) theme.dangerText else theme.textMuted,
                center = true
            )

            val capturedIdx = idx
            val selectRight = closeLeft - 1
            val tLeft = tabX
            hitRegions.add(
                HitRegion(
                    left = tLeft,
                    top = top + 2,
                    right = selectRight,
                    bottom = bottom,
                    tooltip = "$title — Click to switch tab",
                    onClick = { _, _ -> tabContainer?.requestSelectTab(capturedIdx) }
                )
            )
            hitRegions.add(
                HitRegion(
                    left = closeLeft,
                    top = closeTop,
                    right = closeRight,
                    bottom = closeBottom,
                    tooltip = "Close $title (Ctrl+W)",
                    onClick = { _, _ -> tabContainer?.requestCloseTab(capturedIdx) }
                )
            )

            tabX = tabRight + 3
        }

        // "+" New Canvas tab button at the end of the tab strip
        val plusLeft = tabX + 2
        val plusRight = min(right - 4, plusLeft + 26)
        if (plusRight - plusLeft >= 20) {
            drawButtonControl(
                hdc = hdc,
                theme = theme,
                label = "+",
                icon = null,
                left = plusLeft,
                top = top + 3,
                right = plusRight,
                bottom = bottom - 3,
                primary = false,
                hwndForHover = hwnd
            )
            val onNewFn = canvasWidget?.attributes?.get("onSplashNewCanvas") as? (() -> Unit)
            hitRegions.add(
                HitRegion(
                    left = plusLeft,
                    top = top + 3,
                    right = plusRight,
                    bottom = bottom - 3,
                    tooltip = "New Canvas Tab (Ctrl+N)",
                    onClick = { _, _ -> onNewFn?.invoke() }
                )
            )
        }
    }

    private fun findTabContainer(root: DesktopNativeWidget?): DesktopTabContainerNativeImpl<*>? {
        if (root == null || !root.visible || root.disposed) return null
        val impl = root.attributes["nativeImpl"]
        if (impl is DesktopTabContainerNativeImpl<*>) return impl
        for (child in root.children) {
            val found = findTabContainer(child)
            if (found != null) return found
        }
        return null
    }

    private fun findButtonByLabelOrClass(
        root: DesktopNativeWidget?,
        cssClass: String,
        labelSubstring: String
    ): DesktopButtonNativeImpl<*>? {
        if (root == null || root.disposed) return null
        val impl = root.attributes["nativeImpl"]
        if (impl is DesktopButtonNativeImpl<*>) {
            if (widgetHasClass(root, cssClass) || impl.getText().contains(labelSubstring, ignoreCase = true)) {
                return impl
            }
        }
        for (child in root.children) {
            val found = findButtonByLabelOrClass(child, cssClass, labelSubstring)
            if (found != null) return found
        }
        return null
    }

    /**
     * Renders an empty document placeholder inside the canvas viewport when no canvas
     * document is open (`hasOpenCanvas == false`), with `New` and `Open` action buttons.
     */
    @Suppress("UNCHECKED_CAST")
    private fun renderEmptyCanvasViewportState(
        hwnd: HWND,
        hdc: HDC?,
        theme: SystemThemePalette,
        fontSmall: HFONT?,
        fontSmallBold: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        fontTitle: HFONT?,
        rootWidget: DesktopNativeWidget?,
        canvasWidget: DesktopNativeWidget?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        fillRectColor(hdc, left, top, right, bottom, theme.bgBase)
        val availW = max(240, right - left)
        val availH = max(200, bottom - top)

        val cardW = min(560, availW - 24).coerceAtLeast(240)
        val cardH = min(220, availH - 20).coerceAtLeast(160)
        val cardLeft = left + (availW - cardW) / 2
        val cardTop = top + max(8, (availH - cardH) / 2)
        val cardRight = cardLeft + cardW
        val cardBottom = min(bottom - 6, cardTop + cardH)

        drawRoundedRect(hdc, cardLeft, cardTop, cardRight, cardBottom, 10, theme.bgPanel, theme.borderSubtle)
        drawLineColor(hdc, cardLeft + 8, cardTop + 1, cardRight - 8, cardTop + 1, theme.bgElevated)

        var y = cardTop + 24
        val appTitle = activeWindowImpl?.getTitle()?.ifBlank { "Canvas Workspace" } ?: "Canvas Workspace"
        SelectObject(hdc, fontTitle)
        drawTextInRect(
            hdc,
            appTitle,
            cardLeft + 16,
            y,
            cardRight - 16,
            y + 28,
            theme.textPrimary,
            center = true
        )
        y += 34

        SelectObject(hdc, fontSmall)
        drawTextInRect(
            hdc,
            "Create a new document or open an existing file to begin.",
            cardLeft + 18,
            y,
            cardRight - 18,
            y + 20,
            theme.textSecondary,
            center = true
        )
        y += 32

        val btnW = 148
        val btnH = 34
        val btnGap = 14
        val totalBtnsW = btnW * 2 + btnGap
        val newBtnLeft = cardLeft + (cardW - totalBtnsW) / 2
        val openBtnLeft = newBtnLeft + btnW + btnGap

        val onNewFn = (canvasWidget?.attributes?.get("onSplashNewCanvas") as? (() -> Unit))
            ?: findButtonByLabelOrClass(rootWidget, "splash-new-btn", "New")?.let { btn -> { btn.dispatchClick() } }
        val onOpenFn = (canvasWidget?.attributes?.get("onSplashOpenFile") as? (() -> Unit))
            ?: findButtonByLabelOrClass(rootWidget, "splash-open-btn", "Open")?.let { btn -> { btn.dispatchClick() } }

        SelectObject(hdc, fontBold)
        drawButtonControl(
            hdc = hdc,
            theme = theme,
            label = "New",
            icon = null,
            left = newBtnLeft,
            top = y,
            right = newBtnLeft + btnW,
            bottom = y + btnH,
            primary = true,
            hwndForHover = hwnd
        )
        val btnTop = y
        hitRegions.add(
            HitRegion(
                left = newBtnLeft,
                top = btnTop,
                right = newBtnLeft + btnW,
                bottom = btnTop + btnH,
                tooltip = "Create a New Document (Ctrl+N)",
                onClick = { _, _ -> onNewFn?.invoke() }
            )
        )

        drawButtonControl(
            hdc = hdc,
            theme = theme,
            label = "Open",
            icon = null,
            left = openBtnLeft,
            top = y,
            right = openBtnLeft + btnW,
            bottom = y + btnH,
            primary = false,
            hwndForHover = hwnd
        )
        hitRegions.add(
            HitRegion(
                left = openBtnLeft,
                top = btnTop,
                right = openBtnLeft + btnW,
                bottom = btnTop + btnH,
                tooltip = "Open an Existing Document (Ctrl+O)",
                onClick = { _, _ -> onOpenFn?.invoke() }
            )
        )
    }

    /**
     * Renders a single GenericUI [com.nexustools.genericui.core.Component] mounted inside a [TitleBar]
     * (`leadingComponents`, `centerComponents`, or `trailingComponents`) — either in the collapsible header
     * of a docked [DesktopDockPanelNativeImpl] or in the actual window titlebar of a detached `PanelWindow`.
     * Returns the horizontal pixel width consumed.
     */
    private fun renderTitleBarComponent(
        hwnd: HWND,
        hdc: HDC?,
        theme: SystemThemePalette,
        fontSmall: HFONT?,
        comp: com.nexustools.genericui.core.AbstractComponent,
        x: Int,
        top: Int,
        bottom: Int,
        alignFromRight: Boolean = false
    ): Int {
        val impl = comp.nativeImpl ?: comp.ensureNativeImpl()
        val height = max(18, bottom - top)
        when (impl) {
            is DesktopButtonNativeImpl<*> -> {
                val label = impl.getText()
                val icon = impl.getIcon()
                val isIconOnly = !icon.isNullOrBlank() && label.isBlank()
                val w = when {
                    isIconOnly -> height + 4
                    else -> max(46, label.length * 6 + 14)
                }
                val left = if (alignFromRight) x - w else x
                val right = left + w
                SelectObject(hdc, fontSmall)
                drawButtonControl(
                    hdc = hdc,
                    theme = theme,
                    label = if (isIconOnly) "" else label,
                    icon = icon,
                    left = left,
                    top = top,
                    right = right,
                    bottom = bottom,
                    primary = false,
                    widget = impl.nativeWidget,
                    hwndForHover = hwnd
                )
                hitRegions.add(
                    HitRegion(
                        left = left,
                        top = top,
                        right = right,
                        bottom = bottom,
                        tooltip = impl.getTooltipText()?.takeIf { it.isNotBlank() && (isIconOnly || it != label) },
                        widget = impl.nativeWidget,
                        onClick = { _, _ -> impl.dispatchClick() }
                    )
                )
                return w
            }
            is DesktopSelectNativeImpl<*> -> {
                val curVal = impl.getSelectedValue() ?: ""
                val curLabel = impl.getOptions().firstOrNull { it.first == curVal }?.second ?: curVal
                val w = max(72, curLabel.length * 6 + 24)
                val left = if (alignFromRight) x - w else x
                val right = left + w
                SelectObject(hdc, fontSmall)
                drawSelectControl(hdc, theme, curLabel, left, top, right, bottom, impl.nativeWidget, hwnd)
                hitRegions.add(
                    HitRegion(
                        left = left,
                        top = top,
                        right = right,
                        bottom = bottom,
                        tooltip = impl.getTooltipText(),
                        widget = impl.nativeWidget,
                        onClick = { mx, my -> openSelectPopupMenu(hwnd, impl, mx, my) }
                    )
                )
                return w
            }
            is DesktopCheckboxNativeImpl<*> -> {
                val label = impl.getLabel()
                val w = max(48, label.length * 6 + 22)
                val left = if (alignFromRight) x - w else x
                val right = left + w
                SelectObject(hdc, fontSmall)
                drawCheckboxControl(hdc, theme, label, impl.isChecked(), left, top, right, bottom, impl.nativeWidget, hwnd)
                hitRegions.add(
                    HitRegion(
                        left = left,
                        top = top,
                        right = right,
                        bottom = bottom,
                        tooltip = impl.getTooltipText(),
                        widget = impl.nativeWidget,
                        onClick = { _, _ -> impl.setChecked(!impl.isChecked()) }
                    )
                )
                return w
            }
            is DesktopTextNativeImpl<*> -> {
                val label = impl.getText()
                val w = max(32, label.length * 6 + 8)
                val left = if (alignFromRight) x - w else x
                val right = left + w
                SelectObject(hdc, fontSmall)
                drawTextInRect(hdc, label, left, top, right, bottom, theme.textSecondary, center = true)
                return w
            }
            else -> return 0
        }
    }

    /**
     * Renders the custom or component-populated [TitleBar] (`activeWindowImpl?.getTitleBarInstructions()`)
     * on the main top-level window (`activeHwnd`) at `y = 0..titleBarH` using [Win32NativeTitleBarRenderer].
     * Returns the rendered titlebar height in pixels (`0` when no `TitleBar` is configured).
     */
    private fun renderMainWindowTitleBar(
        hwnd: HWND,
        hdc: HDC?,
        theme: SystemThemePalette,
        fontSmall: HFONT?,
        fontBold: HFONT?,
        clientW: Int
    ): Int {
        val winImpl = activeWindowImpl ?: return 0
        if (!hasCustomMainWindowTitleBar()) return 0
        val tb = winImpl.getTitleBarInstructions()
        val kind = winImpl.titleBarKind
        val titleBarH = getMainWindowTitleBarHeight()
        val winTitle = winImpl.getTitle().ifBlank { "GenericUI" }

        return Win32NativeTitleBarRenderer.renderWindowTitleBar(
            hwnd = hwnd,
            targetHdc = hdc,
            left = 0,
            top = 0,
            right = clientW,
            titleBarHeight = titleBarH,
            windowTitle = winTitle,
            titleBar = tb,
            isToolWindow = kind.usesSmallCaptionMetrics,
            isInWindowModalHeader = false,
            kind = kind,
            appIcon = appIconSmall ?: appIconBig,
            headerActions = emptyList(),
            fontSmall = fontSmall,
            fontCaption = fontBold ?: fontSmall,
            hoveredWidget = hoveredWidget,
            pressedWidget = pressedWidget,
            isRectHoveredFn = { l, t, r, b -> isRectHovered(hwnd, l, t, r, b) },
            registerHitRegionFn = { l, t, r, b, tip, widget, onClick ->
                hitRegions.add(
                    HitRegion(
                        left = l,
                        top = t,
                        right = r,
                        bottom = b,
                        tooltip = tip,
                        widget = widget,
                        onClick = onClick
                    )
                )
            },
            openSelectPopupFn = { selectImpl, mx, my ->
                openSelectPopupMenu(hwnd, selectImpl, mx, my)
            }
        )
    }

    /**
     * Renders the native Windows **Tool Window** titlebar ([Win32TitleBarKind.TOOL_WINDOW], `SM_CYSMCAPTION`,
     * `WP_SMALLCAPTION`, `WP_SMALLCLOSEBUTTON`, compact close button, no minimize/maximize buttons)
     * of a separate `PanelWindow` (`y = 0..titleBarH`) while reusing the shared component-embedding
     * pipeline (`panel.getTitleBarInstructions()` and `panel.getHeaderActions()`).
     */
    private fun renderPanelWindowTitleBar(
        hwnd: HWND,
        hdc: HDC?,
        theme: SystemThemePalette,
        fontSmall: HFONT?,
        fontBold: HFONT?,
        panel: DesktopDockPanelNativeImpl<*>,
        clientW: Int,
        titleBarH: Int
    ) {
        val tb: TitleBar? = panel.getTitleBarInstructions()
        Win32NativeTitleBarRenderer.renderWindowTitleBar(
            hwnd = hwnd,
            targetHdc = hdc,
            left = 0,
            top = 0,
            right = clientW,
            titleBarHeight = titleBarH,
            windowTitle = panel.getTitle(),
            titleBar = tb,
            isToolWindow = true,
            isInWindowModalHeader = false,
            kind = Win32TitleBarKind.TOOL_WINDOW,
            appIcon = appIconSmall ?: appIconBig,
            headerActions = panel.getHeaderActions(),
            fontSmall = fontSmall,
            fontCaption = fontSmall ?: fontBold,
            hoveredWidget = hoveredWidget,
            pressedWidget = pressedWidget,
            isRectHoveredFn = { l, t, r, b -> isRectHovered(hwnd, l, t, r, b) },
            registerHitRegionFn = { l, t, r, b, tip, widget, onClick ->
                hitRegions.add(
                    HitRegion(
                        left = l,
                        top = t,
                        right = r,
                        bottom = b,
                        tooltip = tip,
                        widget = widget,
                        onClick = onClick
                    )
                )
            },
            openSelectPopupFn = { selectImpl, mx, my ->
                openSelectPopupMenu(hwnd, selectImpl, mx, my)
            },
            onCloseClicked = {
                ShowWindow(hwnd, SW_HIDE)
            }
        )
    }

    /**
     * Renders the collapsible panel title header bar in Single-Window Mode (`isSeparatePanelWindow == false`)
     * using [Win32TitleBarKind.DOCKED_PANEL_HEADER] and the shared [Win32NativeTitleBarRenderer] component
     * embedding pipeline (`panel.getTitleBarInstructions()` and `panel.getHeaderActions()`).
     */
    private fun renderCollapsiblePanelHeader(
        hwnd: HWND,
        hdc: HDC?,
        theme: SystemThemePalette,
        fontSmall: HFONT?,
        fontBold: HFONT?,
        panel: DesktopDockPanelNativeImpl<*>,
        left: Int,
        top: Int,
        right: Int,
        height: Int = 26
    ) {
        val bottom = top + height
        val collapsed = panel.isCollapsed()
        val chevron = if (collapsed) "▶" else "▼"

        // Register the base collapse-toggle HitRegion FIRST so any TitleBar button HitRegions added
        // afterward by `Win32NativeTitleBarRenderer.renderEmbeddedTitleBarComponents` take precedence on click!
        hitRegions.add(
            HitRegion(
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                tooltip = "Click to ${if (collapsed) "expand" else "collapse"} ${panel.getTitle()}",
                widget = panel.nativeWidget,
                onClick = { _, _ -> panel.toggleCollapsed() }
            )
        )

        Win32NativeTitleBarRenderer.renderWindowTitleBar(
            hwnd = hwnd,
            targetHdc = hdc,
            left = left,
            top = top,
            right = right,
            titleBarHeight = height,
            windowTitle = panel.getTitle(),
            titleBar = panel.getTitleBarInstructions(),
            isToolWindow = true,
            isInWindowModalHeader = true,
            kind = Win32TitleBarKind.DOCKED_PANEL_HEADER,
            appIcon = null,
            leadingPrefixGlyph = chevron,
            headerActions = panel.getHeaderActions(),
            fontSmall = fontSmall,
            fontCaption = fontBold ?: fontSmall,
            hoveredWidget = hoveredWidget,
            pressedWidget = pressedWidget,
            isRectHoveredFn = { l, t, r, b -> isRectHovered(hwnd, l, t, r, b) },
            registerHitRegionFn = { l, t, r, b, tip, widget, onClick ->
                hitRegions.add(
                    HitRegion(
                        left = l,
                        top = t,
                        right = r,
                        bottom = b,
                        tooltip = tip,
                        widget = widget,
                        onClick = onClick
                    )
                )
            },
            openSelectPopupFn = { selectImpl, mx, my ->
                openSelectPopupMenu(hwnd, selectImpl, mx, my)
            }
        )
    }

    private fun renderRightInspectorDock(
        hwnd: HWND,
        hdc: HDC?,
        theme: SystemThemePalette,
        fontSmall: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        panels: List<DesktopDockPanelNativeImpl<*>>,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        var y = top
        val headerH = 26
        val headerStep = headerH + 3
        val expandedPanels = panels.filter { !it.isCollapsed() }
        val expandedCount = expandedPanels.size.coerceAtLeast(1)
        val totalHeaderSpace = panels.size * headerStep
        val availBodySpace = max(120, (bottom - top) - totalHeaderSpace)

        for ((panelIdx, panel) in panels.withIndex()) {
            if (y + headerH > bottom) break
            renderCollapsiblePanelHeader(
                hwnd = hwnd,
                hdc = hdc,
                theme = theme,
                fontSmall = fontSmall,
                fontBold = fontBold,
                panel = panel,
                left = left,
                top = y,
                right = right,
                height = headerH
            )
            y += headerStep

            if (panel.isCollapsed()) {
                continue
            }

            val remainingPanelsBelow = (panels.size - 1 - panelIdx).coerceAtLeast(0)
            val reservedForHeadersBelow = remainingPanelsBelow * headerStep
            val panelBodyQuota = max(92, availBodySpace / expandedCount)
            val panelBottomLimit = min(bottom - reservedForHeadersBelow, y + panelBodyQuota)

            y = renderStandardDockPanelBody(
                hwnd = hwnd,
                hdc = hdc,
                theme = theme,
                fontSmall = fontSmall,
                fontNormal = fontNormal,
                fontBold = fontBold,
                panel = panel,
                left = left,
                top = y,
                right = right,
                bottomLimit = panelBottomLimit
            )
            y += 4
        }
    }

    /**
     * Walks a standard [DesktopDockPanelNativeImpl]'s widget hierarchy (`LayersPanel`, `PropertiesPanel`,
     * `SharedObjectsPanel`, `LibraryPanel`, `FontsPanel`) in layout order so `HStack` rows, `TextField`
     * inputs, `Select` dropdowns, labeled `Slider`s, `CheckBox`es, `Text` headers, and `Button`s all
     * render in their natural position and work interactively.
     */
    private fun renderStandardDockPanelBody(
        hwnd: HWND,
        hdc: HDC?,
        theme: SystemThemePalette,
        fontSmall: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        panel: DesktopDockPanelNativeImpl<*>,
        left: Int,
        top: Int,
        right: Int,
        bottomLimit: Int
    ): Int {
        var y = top
        val width = max(80, right - left)
        val titleBarWidgets = collectTitleBarWidgetSet(
            panel.getTitleBarInstructions(),
            activeWindowImpl?.getTitleBarInstructions()
        )

        fun renderTextInputWidget(inputImpl: DesktopTextInputNativeImpl<*>, xL: Int, xR: Int, rowTop: Int, rowH: Int) {
            val isFocused = focusedWidget == inputImpl.nativeWidget
            val inputHovered = isRectHovered(hwnd, xL, rowTop, xR, rowTop + rowH)
            val border = when {
                isFocused -> theme.accentBlueBorder
                inputHovered -> theme.borderStrong
                else -> theme.borderSubtle
            }
            fillRectColor(hdc, xL, rowTop, xR, rowTop + rowH, theme.bgCard)
            drawRectBorder(hdc, xL, rowTop, xR, rowTop + rowH, border)
            SelectObject(hdc, fontSmall)
            val typed = inputImpl.getValue()
            val placeholder = inputImpl.getPlaceholder()
            val display = if (typed.isEmpty()) {
                if (isFocused) "|" else placeholder
            } else {
                if (isFocused) "$typed|" else typed
            }
            val fg = if (typed.isEmpty() && !isFocused) theme.textMuted else theme.textPrimary
            drawTextInRect(hdc, display, xL + 6, rowTop + 1, xR - 6, rowTop + rowH - 1, fg)
            hitRegions.add(
                HitRegion(
                    left = xL,
                    top = rowTop,
                    right = xR,
                    bottom = rowTop + rowH,
                    tooltip = placeholder.ifBlank { "Click to edit text" },
                    widget = inputImpl.nativeWidget,
                    isTextInput = true,
                    onClick = { _, _ ->
                        setWidgetFocused(inputImpl.nativeWidget)
                        InvalidateRect(hwnd, null, 0)
                    }
                )
            )
        }

        fun renderHStackRow(rowWidget: DesktopNativeWidget) {
            if (isWidgetMountedInTitleBar(rowWidget, titleBarWidgets)) return
            val visibleChildren = rowWidget.children.filter {
                it.visible && !it.disposed && !isWidgetMountedInTitleBar(it, titleBarWidgets)
            }
            if (visibleChildren.isEmpty() || y + 22 > bottomLimit) return

            // Dedicated layout for LayersPanel layer selector rows (.ha-layer-row)
            if (widgetHasClass(rowWidget, "ha-layer-row")) {
                val rowH = 24
                if (y + rowH > bottomLimit) return
                val gap = 4
                var cx = left
                for ((idx, child) in visibleChildren.withIndex()) {
                    val cImpl = child.attributes["nativeImpl"] as? DesktopButtonNativeImpl<*> ?: continue
                    val itemW = when {
                        idx == 0 && visibleChildren.size >= 2 -> 26
                        idx == visibleChildren.lastIndex && visibleChildren.size >= 3 -> 52
                        visibleChildren.size >= 3 -> max(60, (right - cx) - 52 - gap)
                        else -> max(60, right - cx)
                    }
                    val cRight = if (idx == visibleChildren.lastIndex) right else min(right, cx + itemW)
                    val isActiveLayerBtn = widgetHasClass(child, "active-layer-btn") || widgetHasClass(child, "btn-primary")
                    SelectObject(hdc, if (isActiveLayerBtn) fontBold else fontSmall)
                    drawButtonControl(
                        hdc = hdc,
                        theme = theme,
                        label = if (cImpl.isIconOnly()) "" else cImpl.getText(),
                        icon = cImpl.getIcon(),
                        left = cx,
                        top = y,
                        right = cRight,
                        bottom = y + rowH,
                        primary = isActiveLayerBtn,
                        widget = child,
                        hwndForHover = hwnd
                    )
                    val bL = cx
                    val bR = cRight
                    val ry = y
                    hitRegions.add(
                        HitRegion(
                            left = bL,
                            top = ry,
                            right = bR,
                            bottom = ry + rowH,
                            tooltip = cImpl.getTooltipText()?.takeIf { it.isNotBlank() && (cImpl.isIconOnly() || it != cImpl.getText()) },
                            widget = child,
                            onClick = { _, _ ->
                                cImpl.dispatchClick()
                                InvalidateRect(hwnd, null, 0)
                            }
                        )
                    )
                    cx = cRight + gap
                }
                y += rowH + 3
                return
            }

            val rowH = 22
            val gap = 4
            val count = visibleChildren.size
            val itemW = max(28, (width - gap * (count - 1)) / count)
            var cx = left
            for ((idx, child) in visibleChildren.withIndex()) {
                val cRight = if (idx == count - 1) right else min(right, cx + itemW)
                when (val cImpl = child.attributes["nativeImpl"]) {
                    is DesktopButtonNativeImpl<*> -> {
                        val isPrimary = widgetHasClass(child, "btn-primary") || widgetHasClass(child, "active-layer-btn")
                        SelectObject(hdc, if (isPrimary) fontBold else fontSmall)
                        drawButtonControl(
                            hdc = hdc,
                            theme = theme,
                            label = if (cImpl.isIconOnly()) "" else cImpl.getText(),
                            icon = cImpl.getIcon(),
                            left = cx,
                            top = y,
                            right = cRight,
                            bottom = y + rowH,
                            primary = isPrimary,
                            widget = child,
                            hwndForHover = hwnd
                        )
                        val bL = cx
                        val bR = cRight
                        val ry = y
                        hitRegions.add(
                            HitRegion(
                                left = bL,
                                top = ry,
                                right = bR,
                                bottom = ry + rowH,
                                tooltip = cImpl.getTooltipText()?.takeIf { it.isNotBlank() && (cImpl.isIconOnly() || it != cImpl.getText()) },
                                widget = child,
                                onClick = { _, _ -> cImpl.dispatchClick() }
                            )
                        )
                    }
                    is DesktopSelectNativeImpl<*> -> {
                        val curVal = cImpl.getSelectedValue() ?: ""
                        val curLabel = cImpl.getOptions().firstOrNull { it.first == curVal }?.second ?: curVal
                        SelectObject(hdc, fontSmall)
                        drawSelectControl(hdc, theme, curLabel.ifBlank { "Select..." }, cx, y, cRight, y + rowH, child, hwnd)
                        val sL = cx
                        val sR = cRight
                        val ry = y
                        hitRegions.add(
                            HitRegion(
                                left = sL,
                                top = ry,
                                right = sR,
                                bottom = ry + rowH,
                                tooltip = cImpl.getTooltipText(),
                                widget = child,
                                onClick = { mx, my -> openSelectPopupMenu(hwnd, cImpl, mx, my) }
                            )
                        )
                    }
                    is DesktopCheckboxNativeImpl<*> -> {
                        SelectObject(hdc, fontSmall)
                        drawCheckboxControl(hdc, theme, cImpl.getLabel(), cImpl.isChecked(), cx, y + 2, cRight, y + rowH - 2, child, hwnd)
                        val cL = cx
                        val cR = cRight
                        val ry = y
                        hitRegions.add(
                            HitRegion(
                                left = cL,
                                top = ry,
                                right = cR,
                                bottom = ry + rowH,
                                tooltip = cImpl.getTooltipText(),
                                widget = child,
                                onClick = { _, _ -> cImpl.setChecked(!cImpl.isChecked()) }
                            )
                        )
                    }
                    is DesktopTextInputNativeImpl<*> -> {
                        renderTextInputWidget(cImpl, cx, cRight, y, rowH)
                    }
                    is DesktopRangeSliderNativeImpl<*> -> {
                        drawSliderControl(hdc, theme, cImpl, cx + 2, y + 4, cRight - 2, y + rowH - 4)
                        val sL = cx + 2
                        val sR = cRight - 2
                        val ry = y
                        hitRegions.add(
                            HitRegion(
                                left = sL,
                                top = ry,
                                right = sR,
                                bottom = ry + rowH,
                                tooltip = cImpl.getTooltipText(),
                                widget = child,
                                onClick = { mx, _ ->
                                    cImpl.setNormalizedFraction((mx - sL).toDouble() / max(1, sR - sL).toDouble())
                                },
                                onDrag = { mx, _ ->
                                    cImpl.setNormalizedFraction((mx - sL).toDouble() / max(1, sR - sL).toDouble())
                                }
                            )
                        )
                    }
                    is DesktopTextNativeImpl<*> -> {
                        SelectObject(hdc, fontSmall)
                        drawTextInRect(hdc, cImpl.getText(), cx + 2, y + 2, cRight - 2, y + rowH - 2, theme.textPrimary)
                    }
                }
                cx = cRight + gap
            }
            y += rowH + 3
        }

        fun walkContainer(container: DesktopNativeWidget?) {
            if (container == null || !container.visible || container.disposed || isWidgetMountedInTitleBar(container, titleBarWidgets)) return
            val children = container.children.filter {
                it.visible && !it.disposed && !isWidgetMountedInTitleBar(it, titleBarWidgets)
            }
            var i = 0
            while (i < children.size && y + 16 <= bottomLimit) {
                val child = children[i]
                val impl = child.attributes["nativeImpl"]
                val tag = child.nativeClassName.lowercase()

                // Group consecutive standalone Button siblings into a 2-column row
                if (impl is DesktopButtonNativeImpl<*>) {
                    val btnRun = mutableListOf<DesktopButtonNativeImpl<*>>()
                    while (i < children.size) {
                        val nextBtn = children[i].attributes["nativeImpl"] as? DesktopButtonNativeImpl<*> ?: break
                        btnRun.add(nextBtn)
                        i++
                    }
                    val cols = if (btnRun.size == 1) 1 else if (btnRun.all { it.isIconOnly() }) min(6, btnRun.size) else 2
                    val gap = 4
                    val colW = (width - gap * (cols - 1)) / cols
                    btnRun.forEachIndexed { bIdx, btn ->
                        val col = bIdx % cols
                        val bx = left + col * (colW + gap)
                        if (y + 22 <= bottomLimit) {
                            SelectObject(hdc, fontSmall)
                            drawButtonControl(
                                hdc = hdc,
                                theme = theme,
                                label = if (btn.isIconOnly()) "" else btn.getText(),
                                icon = btn.getIcon(),
                                left = bx,
                                top = y,
                                right = bx + colW,
                                bottom = y + 22,
                                primary = false,
                                widget = btn.nativeWidget,
                                hwndForHover = hwnd
                            )
                            val ry = y
                            hitRegions.add(
                                HitRegion(
                                    left = bx,
                                    top = ry,
                                    right = bx + colW,
                                    bottom = ry + 22,
                                    tooltip = btn.getTooltipText()?.takeIf { it.isNotBlank() && (btn.isIconOnly() || it != btn.getText()) },
                                    widget = btn.nativeWidget,
                                    onClick = { _, _ -> btn.dispatchClick() }
                                )
                            )
                        }
                        if (col == cols - 1 || bIdx == btnRun.lastIndex) {
                            if (y + 22 <= bottomLimit) y += 25
                        }
                    }
                    continue
                }

                when {
                    impl is DesktopTextNativeImpl<*> -> {
                        val txt = impl.getText().trim()
                        if (txt.isNotEmpty() && y + 16 <= bottomLimit) {
                            val isHeader = txt.endsWith(":") || txt.startsWith("🎨") || txt.startsWith("🧩") ||
                                txt.startsWith("✨") || txt.startsWith("🔤") || txt.startsWith("🗂") || txt.startsWith("📐")
                            SelectObject(hdc, if (isHeader) fontBold else fontSmall)
                            drawTextInRect(
                                hdc,
                                txt,
                                left + 2,
                                y,
                                right - 2,
                                y + 16,
                                if (isHeader) theme.textPrimary else theme.textSecondary
                            )
                            y += 17
                        }
                    }
                    impl is DesktopTextInputNativeImpl<*> -> {
                        if (y + 22 <= bottomLimit) {
                            renderTextInputWidget(impl, left, right, y, 22)
                            y += 25
                        }
                    }
                    impl is DesktopSelectNativeImpl<*> -> {
                        if (y + 22 <= bottomLimit) {
                            val curVal = impl.getSelectedValue() ?: ""
                            val curLabel = impl.getOptions().firstOrNull { it.first == curVal }?.second ?: curVal
                            SelectObject(hdc, fontSmall)
                            drawSelectControl(hdc, theme, curLabel.ifBlank { "Select..." }, left, y, right, y + 22, child, hwnd)
                            val sy = y
                            hitRegions.add(
                                HitRegion(
                                    left = left,
                                    top = sy,
                                    right = right,
                                    bottom = sy + 22,
                                    tooltip = impl.getTooltipText(),
                                    widget = child,
                                    onClick = { mx, my -> openSelectPopupMenu(hwnd, impl, mx, my) }
                                )
                            )
                            y += 25
                        }
                    }
                    impl is DesktopRangeSliderNativeImpl<*> -> {
                        if (y + 18 <= bottomLimit) {
                            val valStr = "${impl.getValue().roundToInt()}"
                            val sliderRight = right - 34
                            drawSliderControl(hdc, theme, impl, left + 2, y + 2, sliderRight, y + 15)
                            SelectObject(hdc, fontSmall)
                            drawTextInRect(hdc, valStr, sliderRight + 4, y, right, y + 16, theme.textSecondary, alignRight = true)
                            val sTop = y
                            val sBottom = y + 17
                            val sLeft = left + 2
                            hitRegions.add(
                                HitRegion(
                                    left = sLeft,
                                    top = sTop,
                                    right = sliderRight,
                                    bottom = sBottom,
                                    tooltip = impl.getTooltipText() ?: valStr,
                                    widget = child,
                                    onClick = { mx, _ ->
                                        impl.setNormalizedFraction((mx - sLeft).toDouble() / max(1, sliderRight - sLeft).toDouble())
                                    },
                                    onDrag = { mx, _ ->
                                        impl.setNormalizedFraction((mx - sLeft).toDouble() / max(1, sliderRight - sLeft).toDouble())
                                    }
                                )
                            )
                            y += 19
                        }
                    }
                    impl is DesktopCheckboxNativeImpl<*> -> {
                        if (y + 19 <= bottomLimit) {
                            SelectObject(hdc, fontSmall)
                            drawCheckboxControl(hdc, theme, impl.getLabel(), impl.isChecked(), left, y, right, y + 18, child, hwnd)
                            val ry = y
                            hitRegions.add(
                                HitRegion(
                                    left = left,
                                    top = ry,
                                    right = right,
                                    bottom = ry + 18,
                                    tooltip = impl.getTooltipText(),
                                    widget = child,
                                    onClick = { _, _ -> impl.setChecked(!impl.isChecked()) }
                                )
                            )
                            y += 20
                        }
                    }
                    tag.contains("hstack") || widgetHasClass(child, "hstack") -> {
                        renderHStackRow(child)
                    }
                    child.children.isNotEmpty() -> {
                        val nonTitleBarChildren = child.children.filter {
                            it.visible && !it.disposed && !isWidgetMountedInTitleBar(it, titleBarWidgets)
                        }
                        if (nonTitleBarChildren.isEmpty()) {
                            i++
                            continue
                        }
                        val directLeafControls = nonTitleBarChildren.all {
                            val cImpl = it.attributes["nativeImpl"]
                            cImpl is DesktopButtonNativeImpl<*> ||
                                cImpl is DesktopCheckboxNativeImpl<*> ||
                                cImpl is DesktopSelectNativeImpl<*> ||
                                cImpl is DesktopTextNativeImpl<*>
                        } && nonTitleBarChildren.any { it.attributes["nativeImpl"] is DesktopButtonNativeImpl<*> } &&
                            nonTitleBarChildren.size in 2..5 &&
                            !tag.contains("vstack") && !tag.contains("scroll")
                        if (directLeafControls) {
                            renderHStackRow(child)
                        } else {
                            walkContainer(child)
                        }
                    }
                }
                i++
            }
        }

        walkContainer(panel.nativeWidget)
        return y
    }

    /**
     * Renders any active [DesktopDialogWindowNativeImpl] (`Modal`) as an interactive centered modal dialog
     * overlay on top of [hwnd].
     */
    private fun renderActiveModalOverlay(
        hwnd: HWND,
        hdc: HDC?,
        theme: SystemThemePalette,
        fontSmall: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        fontTitle: HFONT?,
        clientW: Int,
        clientH: Int
    ) {
        val dialog = DesktopDialogWindowNativeImpl.activeDialogs.lastOrNull { it.nativeWidget.visible && !it.nativeWidget.disposed }
            ?: return
        val cardW = min(520, clientW - 40).coerceAtLeast(260)
        val cardH = min(440, clientH - 40).coerceAtLeast(200)
        val cardLeft = (clientW - cardW) / 2
        val cardTop = (clientH - cardH) / 2
        val cardRight = cardLeft + cardW
        val cardBottom = cardTop + cardH

        // Swallow background clicks behind modal
        hitRegions.add(
            HitRegion(
                left = 0,
                top = 0,
                right = clientW,
                bottom = clientH,
                onClick = { _, _ -> }
            )
        )

        drawRoundedRect(hdc, cardLeft, cardTop, cardRight, cardBottom, 8, theme.bgPanel, theme.accentBlueBorder)
        Win32NativeTitleBarRenderer.renderWindowTitleBar(
            hwnd = hwnd,
            targetHdc = hdc,
            left = cardLeft + 1,
            top = cardTop + 1,
            right = cardRight - 1,
            titleBarHeight = 32,
            windowTitle = dialog.getTitle().ifBlank { "Dialog" },
            titleBar = null,
            isToolWindow = false,
            isInWindowModalHeader = true,
            kind = Win32TitleBarKind.DIALOG_WINDOW,
            appIcon = null,
            headerActions = emptyList(),
            fontSmall = fontSmall,
            fontCaption = fontBold ?: fontSmall,
            hoveredWidget = hoveredWidget,
            pressedWidget = pressedWidget,
            isRectHoveredFn = { l, t, r, b -> isRectHovered(hwnd, l, t, r, b) },
            registerHitRegionFn = { l, t, r, b, tip, widget, onClick ->
                hitRegions.add(
                    HitRegion(
                        left = l,
                        top = t,
                        right = r,
                        bottom = b,
                        tooltip = tip,
                        widget = widget,
                        onClick = onClick
                    )
                )
            },
            openSelectPopupFn = { selectImpl, mx, my ->
                openSelectPopupMenu(hwnd, selectImpl, mx, my)
            },
            onCloseClicked = {
                dialog.closeDialog()
            }
        )

        val buttons = mutableListOf<DesktopButtonNativeImpl<*>>()
        val checkboxes = mutableListOf<DesktopCheckboxNativeImpl<*>>()
        val selects = mutableListOf<DesktopSelectNativeImpl<*>>()
        val sliders = mutableListOf<DesktopRangeSliderNativeImpl<*>>()
        val texts = mutableListOf<DesktopTextNativeImpl<*>>()
        val textInputs = mutableListOf<DesktopTextInputNativeImpl<*>>()

        fun collectModalControls(w: DesktopNativeWidget?) {
            if (w == null || !w.visible || w.disposed) return
            when (val impl = w.attributes["nativeImpl"]) {
                is DesktopButtonNativeImpl<*> -> buttons.add(impl)
                is DesktopCheckboxNativeImpl<*> -> checkboxes.add(impl)
                is DesktopSelectNativeImpl<*> -> selects.add(impl)
                is DesktopRangeSliderNativeImpl<*> -> sliders.add(impl)
                is DesktopTextNativeImpl<*> -> texts.add(impl)
                is DesktopTextInputNativeImpl<*> -> textInputs.add(impl)
            }
            for (c in w.children) collectModalControls(c)
        }
        collectModalControls(dialog.nativeWidget)

        var y = cardTop + 42
        val bodyLeft = cardLeft + 14
        val bodyRight = cardRight - 14
        val bodyBottomLimit = cardBottom - 44

        SelectObject(hdc, fontSmall)
        for (txt in texts.take(5)) {
            val t = txt.getText().trim()
            if (t.isNotEmpty() && y + 18 <= bodyBottomLimit) {
                drawTextInRect(hdc, t, bodyLeft, y, bodyRight, y + 17, theme.textSecondary)
                y += 19
            }
        }
        for (sel in selects.take(4)) {
            if (y + 24 <= bodyBottomLimit) {
                val curVal = sel.getSelectedValue() ?: ""
                val curLabel = sel.getOptions().firstOrNull { it.first == curVal }?.second ?: curVal
                drawSelectControl(hdc, theme, curLabel.ifBlank { "Select..." }, bodyLeft, y, bodyRight, y + 24, sel.nativeWidget, hwnd)
                val sy = y
                hitRegions.add(
                    HitRegion(
                        left = bodyLeft,
                        top = sy,
                        right = bodyRight,
                        bottom = sy + 24,
                        tooltip = sel.getTooltipText(),
                        widget = sel.nativeWidget,
                        onClick = { mx, my -> openSelectPopupMenu(hwnd, sel, mx, my) }
                    )
                )
                y += 28
            }
        }
        for (inp in textInputs.take(4)) {
            if (y + 24 <= bodyBottomLimit) {
                val isFocused = focusedWidget == inp.nativeWidget
                fillRectColor(hdc, bodyLeft, y, bodyRight, y + 24, theme.bgCard)
                drawRectBorder(hdc, bodyLeft, y, bodyRight, y + 24, if (isFocused) theme.accentBlueBorder else theme.borderSubtle)
                val typed = inp.getValue()
                val placeholder = inp.getPlaceholder()
                val display = if (typed.isEmpty()) placeholder else typed
                drawTextInRect(hdc, display, bodyLeft + 6, y + 2, bodyRight - 6, y + 22, if (typed.isEmpty()) theme.textMuted else theme.textPrimary)
                val iy = y
                hitRegions.add(
                    HitRegion(
                        left = bodyLeft,
                        top = iy,
                        right = bodyRight,
                        bottom = iy + 24,
                        widget = inp.nativeWidget,
                        isTextInput = true,
                        onClick = { _, _ -> setWidgetFocused(inp.nativeWidget) }
                    )
                )
                y += 28
            }
        }
        for (cb in checkboxes.take(4)) {
            if (y + 20 <= bodyBottomLimit) {
                drawCheckboxControl(hdc, theme, cb.getLabel(), cb.isChecked(), bodyLeft, y, bodyRight, y + 18, cb.nativeWidget, hwnd)
                val cy = y
                hitRegions.add(
                    HitRegion(
                        left = bodyLeft,
                        top = cy,
                        right = bodyRight,
                        bottom = cy + 18,
                        widget = cb.nativeWidget,
                        onClick = { _, _ -> cb.setChecked(!cb.isChecked()) }
                    )
                )
                y += 22
            }
        }
        if (buttons.isNotEmpty()) {
            val btnY = cardBottom - 34
            val cols = buttons.size.coerceAtLeast(1)
            val gap = 8
            val btnW = ((bodyRight - bodyLeft) - gap * (cols - 1)) / cols
            buttons.forEachIndexed { idx, btn ->
                val bx = bodyLeft + idx * (btnW + gap)
                drawButtonControl(
                    hdc = hdc,
                    theme = theme,
                    label = btn.getText(),
                    icon = btn.getIcon(),
                    left = bx,
                    top = btnY,
                    right = bx + btnW,
                    bottom = btnY + 26,
                    primary = idx == buttons.lastIndex,
                    widget = btn.nativeWidget,
                    hwndForHover = hwnd
                )
                hitRegions.add(
                    HitRegion(
                        left = bx,
                        top = btnY,
                        right = bx + btnW,
                        bottom = btnY + 26,
                        widget = btn.nativeWidget,
                        onClick = { _, _ -> btn.dispatchClick() }
                    )
                )
            }
        }
    }

    /**
     * Renders the Animation Timeline & Keyframe Curves Dock ABOVE the Canvas Viewport in the center column
     * (or inside its own separate `PanelWindow` when [isSeparatePanelWindow] is `true`).
     */
    private fun renderTopTimelineDock(
        hwnd: HWND,
        hdc: HDC?,
        theme: SystemThemePalette,
        fontSmall: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        timelinePanel: DesktopDockPanelNativeImpl<*>?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        isSeparatePanelWindow: Boolean = false
    ) {
        if (timelinePanel == null) return
        val collapsed = if (isSeparatePanelWindow) false else timelinePanel.isCollapsed()
        var contentTop = top
        if (!isSeparatePanelWindow) {
            renderCollapsiblePanelHeader(
                hwnd = hwnd,
                hdc = hdc,
                theme = theme,
                fontSmall = fontSmall,
                fontBold = fontBold,
                panel = timelinePanel,
                left = left,
                top = top,
                right = right,
                height = 24
            )
            if (collapsed) return
            contentTop = top + 29
        } else {
            contentTop = top + 4
        }

        SelectObject(hdc, fontSmall)
        val buttons = mutableListOf<DesktopButtonNativeImpl<*>>()
        val checkboxes = mutableListOf<DesktopCheckboxNativeImpl<*>>()
        val selects = mutableListOf<DesktopSelectNativeImpl<*>>()
        val sliders = mutableListOf<DesktopRangeSliderNativeImpl<*>>()
        val texts = mutableListOf<DesktopTextNativeImpl<*>>()
        collectPanelControls(timelinePanel.nativeWidget, buttons, checkboxes, selects, sliders, texts)

        var x = left + 8
        val rowY = contentTop
        for (btn in buttons.take(8)) {
            val iconSpec = btn.getIcon()
            val label = btn.getText()
            val isIconOnly = !iconSpec.isNullOrBlank() && label.isBlank()
            val w = when {
                isIconOnly -> 28
                !iconSpec.isNullOrBlank() -> max(68, label.length * 7 + 30)
                else -> max(68, label.length * 7 + 16)
            }
            if (x + w > right - 8) break
            val isPlay = label.contains("Play", ignoreCase = true) || iconSpec == "play"
            drawButtonControl(
                hdc = hdc,
                theme = theme,
                label = if (isIconOnly) "" else label,
                icon = iconSpec,
                left = x,
                top = rowY,
                right = x + w,
                bottom = rowY + 24,
                primary = isPlay,
                widget = btn.nativeWidget,
                hwndForHover = hwnd
            )
            val bLeft = x
            hitRegions.add(
                HitRegion(
                    left = bLeft,
                    top = rowY,
                    right = bLeft + w,
                    bottom = rowY + 24,
                    tooltip = btn.getTooltipText()?.takeIf { it.isNotBlank() && (isIconOnly || it != label) },
                    widget = btn.nativeWidget,
                    onClick = { _, _ -> btn.dispatchClick() }
                )
            )
            x += w + 6
        }

        for (sel in selects.take(2)) {
            val curVal = sel.getSelectedValue() ?: ""
            val curLabel = sel.getOptions().firstOrNull { it.first == curVal }?.second ?: curVal
            val w = 168
            if (x + w <= right - 8) {
                drawSelectControl(hdc, theme, curLabel.ifBlank { "Transition..." }, x, rowY, x + w, rowY + 24, sel.nativeWidget, hwnd)
                val sLeft = x
                hitRegions.add(
                    HitRegion(
                        left = sLeft,
                        top = rowY,
                        right = sLeft + w,
                        bottom = rowY + 24,
                        tooltip = sel.getTooltipText(),
                        widget = sel.nativeWidget,
                        onClick = { mx, my -> openSelectPopupMenu(hwnd, sel, mx, my) }
                    )
                )
                x += w + 8
            }
        }

        for (cb in checkboxes.take(2)) {
            val label = cb.getLabel()
            val w = max(68, label.length * 7 + 22)
            if (x + w <= right - 8) {
                drawCheckboxControl(hdc, theme, label, cb.isChecked(), x, rowY + 2, x + w, rowY + 22, cb.nativeWidget, hwnd)
                val cLeft = x
                hitRegions.add(
                    HitRegion(
                        left = cLeft,
                        top = rowY,
                        right = cLeft + w,
                        bottom = rowY + 24,
                        tooltip = cb.getTooltipText(),
                        widget = cb.nativeWidget,
                        onClick = { _, _ -> cb.setChecked(!cb.isChecked()) }
                    )
                )
                x += w + 8
            }
        }

        val summary = texts.lastOrNull()?.getText() ?: ""
        if (x + 80 < right - 8) {
            drawTextInRect(hdc, summary, x, rowY + 2, right - 8, rowY + 22, theme.textSecondary, alignRight = true)
        }

        val playhead = sliders.firstOrNull()
        if (playhead != null) {
            val sTop = rowY + 30
            val sBottom = min(bottom - 6, sTop + 20)
            drawSliderControl(hdc, theme, playhead, left + 8, sTop, right - 8, sBottom)
            hitRegions.add(
                HitRegion(
                    left = left + 8,
                    top = sTop,
                    right = right - 8,
                    bottom = sBottom,
                    tooltip = playhead.getTooltipText() ?: "Timeline Playhead Scrubber",
                    widget = playhead.nativeWidget,
                    onClick = { mx, _ ->
                        playhead.setNormalizedFraction((mx - (left + 8)).toDouble() / max(1, (right - 8) - (left + 8)).toDouble())
                    },
                    onDrag = { mx, _ ->
                        playhead.setNormalizedFraction((mx - (left + 8)).toDouble() / max(1, (right - 8) - (left + 8)).toDouble())
                    }
                )
            )
        }
    }

    private fun renderStudioStatusBar(
        hdc: HDC?,
        theme: SystemThemePalette,
        fontSmall: HFONT?,
        rootWidget: DesktopNativeWidget?,
        dockPanels: List<DesktopDockPanelNativeImpl<*>>,
        clientW: Int,
        top: Int,
        bottom: Int
    ) {
        SelectObject(hdc, fontSmall)
        val dockSet = dockPanels.map { it.nativeWidget }.toSet()
        val texts = mutableListOf<DesktopTextNativeImpl<*>>()
        fun collectStatusTexts(w: DesktopNativeWidget?) {
            if (w == null || !w.visible || w.disposed || w in dockSet) return
            val impl = w.attributes["nativeImpl"]
            if (impl is DesktopTextNativeImpl<*>) texts.add(impl)
            for (c in w.children) collectStatusTexts(c)
        }
        collectStatusTexts(rootWidget)
        val leftStatus = texts.firstOrNull()?.getText().orEmpty()
        val rightStatus = texts.lastOrNull { it != texts.firstOrNull() }?.getText().orEmpty()
        drawTextInRect(hdc, leftStatus, 10, top + 2, clientW / 2, bottom - 2, theme.textSecondary)
        drawTextInRect(hdc, rightStatus, clientW / 2, top + 2, clientW - 10, bottom - 2, theme.textMuted, alignRight = true)
    }

    private fun renderHoveredTooltipOverlay(
        hdc: HDC?,
        fontSmall: HFONT?,
        theme: SystemThemePalette,
        clientW: Int,
        clientH: Int
    ) {
        val tip = hoveredTooltip?.trim().orEmpty()
        if (tip.isEmpty()) return
        SelectObject(hdc, fontSmall)
        val tipW = (tip.length * 7 + 18).coerceIn(80, max(120, clientW - 24))
        val tipH = 24
        val tx = (hoverMouseX + 12).coerceIn(8, max(8, clientW - tipW - 8))
        val ty = if (hoverMouseY + 22 + tipH < clientH - 6) {
            hoverMouseY + 20
        } else {
            max(6, hoverMouseY - tipH - 8)
        }
        fillRectColor(hdc, tx, ty, tx + tipW, ty + tipH, theme.bgElevated)
        drawRectBorder(hdc, tx, ty, tx + tipW, ty + tipH, theme.accentBlueBorder)
        drawTextInRect(hdc, tip, tx + 8, ty + 2, tx + tipW - 8, ty + tipH - 2, theme.textPrimary)
    }

    private fun drawRoundedButton(
        hdc: HDC?,
        font: HFONT?,
        label: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        bg: UInt,
        border: UInt,
        fg: UInt,
        radius: Int = 6,
        icon: String? = null,
        widget: DesktopNativeWidget? = null,
        hwndForHover: HWND? = null
    ) {
        val isHovered = (widget != null && hoveredWidget == widget) ||
            (hwndForHover != null && isRectHovered(hwndForHover, left, top, right, bottom))
        val isPressed = widget != null && pressedWidget == widget
        val effectiveBg = when {
            isPressed -> lightenColorRef(bg, -16)
            isHovered -> lightenColorRef(bg, 16)
            else -> bg
        }
        val effectiveBorder = if (isHovered) lightenColorRef(border, 24) else border
        drawRoundedRect(hdc, left, top, right, bottom, radius, effectiveBg, effectiveBorder)
        if (font != null) {
            SelectObject(hdc, font)
        }
        drawButtonLabelAndIcon(hdc, label, icon, left, top, right, bottom, fg)
    }

    private fun drawRoundedRect(
        hdc: HDC?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        radius: Int,
        fillColor: UInt,
        borderColor: UInt
    ) {
        val brush = CreateSolidBrush(fillColor)
        val pen = CreatePen(PS_SOLID, 1, borderColor)
        val oldBrush = SelectObject(hdc, brush)
        val oldPen = SelectObject(hdc, pen)
        RoundRect(hdc, left, top, right, bottom, radius * 2, radius * 2)
        SelectObject(hdc, oldBrush)
        SelectObject(hdc, oldPen)
        DeleteObject(brush)
        DeleteObject(pen)
    }

    private fun drawDashedRoundedRect(
        hdc: HDC?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        radius: Int,
        fillColor: UInt,
        borderColor: UInt
    ) {
        val brush = CreateSolidBrush(fillColor)
        val pen = CreatePen(PS_DASH, 1, borderColor)
        val oldBrush = SelectObject(hdc, brush)
        val oldPen = SelectObject(hdc, pen)
        RoundRect(hdc, left, top, right, bottom, radius * 2, radius * 2)
        SelectObject(hdc, oldBrush)
        SelectObject(hdc, oldPen)
        DeleteObject(brush)
        DeleteObject(pen)
    }

    private fun isRectHovered(hwnd: HWND?, left: Int, top: Int, right: Int, bottom: Int): Boolean {
        if (hwnd != null && hoverHwndKey != 0L && hoverHwndKey != hwnd.toLong()) return false
        return hoverMouseX in left..right && hoverMouseY in top..bottom
    }

    private fun lightenColorRef(colorRef: UInt, delta: Int): UInt {
        val r = ((colorRef.toInt() and 0xFF) + delta).coerceIn(0, 255)
        val g = (((colorRef.toInt() ushr 8) and 0xFF) + delta).coerceIn(0, 255)
        val b = (((colorRef.toInt() ushr 16) and 0xFF) + delta).coerceIn(0, 255)
        return rgb(r, g, b)
    }

    private fun rgbaToColorRef(color: RgbaColor): UInt = rgb(color.r, color.g, color.b)

    /**
     * Renders a [Gradient] ([com.nexustools.genericui.style.gradient.LinearGradient] or
     * [com.nexustools.genericui.style.gradient.RadialGradient]) into `[left..right, top..bottom]`
     * using a 32-bit BGRA surface buffer and `StretchDIBits`.
     */
    private fun drawGradientRect(
        hdc: HDC?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        gradient: Gradient
    ) {
        val w = (right - left).coerceAtLeast(1)
        val h = (bottom - top).coerceAtLeast(1)
        if (w <= 0 || h <= 0) return

        val sampleW = w.coerceIn(1, 96)
        val sampleH = h.coerceIn(1, 64)
        val bgra = ByteArray(sampleW * sampleH * 4)
        val maxX = (sampleW - 1).coerceAtLeast(1).toFloat()
        val maxY = (sampleH - 1).coerceAtLeast(1).toFloat()

        var offset = 0
        for (py in 0 until sampleH) {
            val v = if (sampleH == 1) 0.5f else py.toFloat() / maxY
            for (px in 0 until sampleW) {
                val u = if (sampleW == 1) 0.5f else px.toFloat() / maxX
                val c = gradient.sampleAt(u, v)
                bgra[offset] = c.b.toByte()
                bgra[offset + 1] = c.g.toByte()
                bgra[offset + 2] = c.r.toByte()
                bgra[offset + 3] = (c.a * 255f).roundToInt().coerceIn(0, 255).toByte()
                offset += 4
            }
        }

        memScoped {
            val bmi = alloc<BITMAPINFO>()
            bmi.bmiHeader.biSize = sizeOf<BITMAPINFOHEADER>().toUInt()
            bmi.bmiHeader.biWidth = sampleW
            bmi.bmiHeader.biHeight = -sampleH
            bmi.bmiHeader.biPlanes = 1u
            bmi.bmiHeader.biBitCount = 32u
            bmi.bmiHeader.biCompression = BI_RGB.toUInt()

            bgra.usePinned { pinned ->
                StretchDIBits(
                    hdc,
                    left,
                    top,
                    w,
                    h,
                    0,
                    0,
                    sampleW,
                    sampleH,
                    pinned.addressOf(0),
                    bmi.ptr,
                    DIB_RGB_COLORS.toUInt(),
                    SRCCOPY
                )
            }
        }
    }

    /**
     * Renders a native system button with support for:
     * - Standard native OS button surface (`primary == false` and no custom `tint`)
     * - Tinted / Primary native OS button surface (`style { tint: ColorRgba(...) }` or `primary == true`)
     * - Gradient backgrounds (`style { background = LinearGradient(...) }` or `RadialGradient(...)`)
     * - CSS pseudo-class states (`:hover`, `:active`, `:focus`) with distinct hover shading, pressed bevel, and focus ring!
     */
    private fun drawButtonControl(
        hdc: HDC?,
        theme: SystemThemePalette = if (isWindowsSystemDarkTheme()) darkSystemPalette else lightSystemPalette,
        label: String,
        icon: String? = null,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        primary: Boolean,
        widget: DesktopNativeWidget? = null,
        tintOverride: UInt? = null,
        hwndForHover: HWND? = null
    ) {
        val impl = widget?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>
        val isHovered = (widget != null && hoveredWidget == widget) || isRectHovered(hwndForHover, left, top, right, bottom)
        val isPressed = widget != null && pressedWidget == widget
        val isFocused = widget != null && focusedWidget == widget
        val styleTint = impl?.getEffectiveTintColor()?.let { rgbaToColorRef(it) } ?: tintOverride
        val styleBgColor = impl?.getEffectiveBackgroundColor()?.let { rgbaToColorRef(it) }
        val styleGradient = impl?.getEffectiveBackgroundGradient()

        val baseBg = when {
            styleBgColor != null -> styleBgColor
            styleTint != null -> styleTint
            primary -> theme.accentBlue
            else -> theme.bgCard
        }
        val bg = when {
            isPressed -> lightenColorRef(baseBg, -18)
            isHovered && (styleTint != null || styleBgColor != null || primary) -> lightenColorRef(baseBg, 22)
            isHovered -> theme.bgElevated
            else -> baseBg
        }
        val border = when {
            isFocused -> theme.accentBlueBorder
            styleTint != null -> lightenColorRef(styleTint, 35)
            primary -> theme.accentBlueBorder
            isHovered -> theme.borderStrong
            else -> theme.borderSubtle
        }
        val fg = if (primary || styleTint != null || styleGradient != null) rgb(255, 255, 255) else theme.textPrimary

        drawRoundedRect(hdc, left, top, right, bottom, 5, bg, border)
        if (styleGradient != null && right - left > 4 && bottom - top > 4) {
            drawGradientRect(hdc, left + 1, top + 1, right - 1, bottom - 1, styleGradient)
            drawRectBorder(hdc, left, top, right, bottom, border)
        }
        // Native top specular bevel highlight (like macOS/Win11 native controls)
        if (!isPressed && right - left > 10 && bottom - top > 10) {
            val highlightColor = lightenColorRef(bg, if (primary || styleTint != null || styleGradient != null) 28 else 14)
            drawLineColor(hdc, left + 3, top + 1, right - 3, top + 1, highlightColor)
        }
        if (isFocused) {
            drawRectBorder(hdc, left + 1, top + 1, right - 1, bottom - 1, theme.accentBlue)
        }
        drawButtonLabelAndIcon(hdc, label, icon, left, top + (if (isPressed) 1 else 0), right, bottom, fg)
    }

    private fun drawButtonLabelAndIcon(
        hdc: HDC?,
        label: String,
        icon: String?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        fg: UInt
    ) {
        val hasIcon = !icon.isNullOrBlank()
        val hasText = label.isNotBlank()
        if (hasIcon && !hasText) {
            val pad = min(6, max(3, min(right - left, bottom - top) / 5))
            drawIconGlyphOrVector(hdc, icon!!, left + pad, top + pad, right - pad, bottom - pad, fg)
        } else if (hasIcon && hasText) {
            val iconSize = min(16, max(12, (bottom - top) - 8))
            val iconLeft = left + 6
            val iconTop = top + ((bottom - top) - iconSize) / 2
            drawIconGlyphOrVector(hdc, icon!!, iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize, fg)
            drawTextInRect(hdc, label, iconLeft + iconSize + 5, top + 2, right - 6, bottom - 2, fg, center = false)
        } else {
            drawTextInRect(hdc, label, left + 6, top + 2, right - 6, bottom - 2, fg, center = true)
        }
    }

    /**
     * Renders crisp GDI vector/glyph icons inside `[left..right, top..bottom]` for
     * viewport tools, action buttons, and SVG/Unicode icon identifiers.
     */
    private fun drawIconGlyphOrVector(
        hdc: HDC?,
        iconSpec: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        colorRef: UInt
    ) {
        val resolvedDrawable = Resources.getDrawable(iconSpec)
        if (resolvedDrawable != null && resolvedDrawable.svgPathDataList.isNotEmpty()) {
            val pen = CreatePen(PS_SOLID, 2, colorRef)
            val oldPen = SelectObject(hdc, pen)
            val nullBrush = GetStockObject(NULL_BRUSH)
            val oldBrush = SelectObject(hdc, nullBrush)
            val vbW = resolvedDrawable.viewBoxWidth.coerceAtLeast(1f)
            val vbH = resolvedDrawable.viewBoxHeight.coerceAtLeast(1f)
            val boxW = max(1, right - left).toFloat()
            val boxH = max(1, bottom - top).toFloat()
            for (pathD in resolvedDrawable.svgPathDataList) {
                renderSvgPathGdi(hdc, pathD, left, top, boxW / vbW, boxH / vbH)
            }
            SelectObject(hdc, oldBrush)
            SelectObject(hdc, oldPen)
            DeleteObject(pen)
            return
        }

        val key = (resolvedDrawable?.resId?.name ?: iconSpec)
            .trim()
            .lowercase()
            .removePrefix("res://drawable/")
            .removePrefix("@drawable/")
            .removePrefix("ic_tool_")
            .removePrefix("ic_")
            .replace('_', '-')
        val cx = (left + right) / 2
        val cy = (top + bottom) / 2
        val pen = CreatePen(PS_SOLID, 2, colorRef)
        val oldPen = SelectObject(hdc, pen)
        val nullBrush = GetStockObject(NULL_BRUSH)
        val oldBrush = SelectObject(hdc, nullBrush)

        when {
            key == "move" || key.contains("move") -> {
                MoveToEx(hdc, cx, top + 1, null); LineTo(hdc, cx, bottom - 1)
                MoveToEx(hdc, left + 1, cy, null); LineTo(hdc, right - 1, cy)
                MoveToEx(hdc, cx - 3, top + 4, null); LineTo(hdc, cx, top + 1); LineTo(hdc, cx + 3, top + 4)
                MoveToEx(hdc, cx - 3, bottom - 4, null); LineTo(hdc, cx, bottom - 1); LineTo(hdc, cx + 3, bottom - 4)
            }
            key == "rect-select" || key.contains("rect-select") -> {
                val dashPen = CreatePen(PS_DOT, 1, colorRef)
                SelectObject(hdc, dashPen)
                Rectangle(hdc, left + 1, top + 2, right - 1, bottom - 2)
                SelectObject(hdc, pen)
                DeleteObject(dashPen)
            }
            key == "ellipse-select" || key.contains("ellipse-select") -> {
                val dashPen = CreatePen(PS_DOT, 1, colorRef)
                SelectObject(hdc, dashPen)
                Ellipse(hdc, left + 1, top + 2, right - 1, bottom - 2)
                SelectObject(hdc, pen)
                DeleteObject(dashPen)
            }
            key == "lasso-select" || key.contains("lasso") -> {
                Ellipse(hdc, left + 2, top + 2, right - 2, bottom - 5)
                MoveToEx(hdc, cx + 2, bottom - 6, null)
                LineTo(hdc, left + 3, bottom - 1)
            }
            key == "magic-wand" || key.contains("wand") -> {
                MoveToEx(hdc, left + 2, bottom - 2, null); LineTo(hdc, right - 4, top + 4)
                MoveToEx(hdc, right - 4, top + 1, null); LineTo(hdc, right - 4, top + 7)
                MoveToEx(hdc, right - 7, top + 4, null); LineTo(hdc, right - 1, top + 4)
            }
            key == "crop" || key.contains("crop") -> {
                MoveToEx(hdc, left + 4, top + 1, null); LineTo(hdc, left + 4, bottom - 4); LineTo(hdc, right - 1, bottom - 4)
                MoveToEx(hdc, left + 1, top + 4, null); LineTo(hdc, right - 4, top + 4); LineTo(hdc, right - 4, bottom - 1)
            }
            key == "brush" || key.contains("brush") -> {
                MoveToEx(hdc, right - 2, top + 2, null); LineTo(hdc, cx - 1, cy + 1)
                Ellipse(hdc, left + 2, cy, cx + 2, bottom - 1)
            }
            key == "pencil" || key.contains("pencil") -> {
                MoveToEx(hdc, left + 2, bottom - 2, null)
                LineTo(hdc, right - 3, top + 3)
                MoveToEx(hdc, left + 2, bottom - 2, null)
                LineTo(hdc, left + 6, bottom - 2)
            }
            key == "airbrush" || key.contains("airbrush") -> {
                Ellipse(hdc, cx - 2, cy - 2, cx + 3, cy + 3)
                SetPixel(hdc, left + 3, top + 4, colorRef)
                SetPixel(hdc, left + 6, top + 2, colorRef)
                SetPixel(hdc, left + 3, cy, colorRef)
                MoveToEx(hdc, cx + 2, cy + 2, null); LineTo(hdc, right - 2, bottom - 2)
            }
            key == "eraser" || key.contains("eraser") -> {
                Rectangle(hdc, left + 2, top + 4, right - 2, bottom - 3)
                MoveToEx(hdc, left + 2, bottom - 1, null); LineTo(hdc, right - 1, bottom - 1)
            }
            key == "bucket-fill" || key.contains("bucket") || key.contains("fill") -> {
                Rectangle(hdc, left + 3, top + 4, right - 4, bottom - 3)
                MoveToEx(hdc, right - 4, cy, null); LineTo(hdc, right - 1, bottom - 2)
            }
            key == "gradient" || key.contains("gradient") -> {
                Rectangle(hdc, left + 1, top + 2, right - 1, bottom - 2)
                MoveToEx(hdc, left + 3, bottom - 4, null); LineTo(hdc, right - 3, top + 4)
            }
            key == "eyedropper" || key.contains("pipette") || key.contains("picker") -> {
                MoveToEx(hdc, left + 2, bottom - 2, null); LineTo(hdc, right - 3, top + 3)
                Ellipse(hdc, right - 6, top + 1, right - 1, top + 6)
            }
            key == "text" || key == "type" || key == "vector-text" -> {
                MoveToEx(hdc, left + 3, top + 3, null); LineTo(hdc, right - 3, top + 3)
                MoveToEx(hdc, cx, top + 3, null); LineTo(hdc, cx, bottom - 2)
                MoveToEx(hdc, cx - 3, bottom - 2, null); LineTo(hdc, cx + 4, bottom - 2)
            }
            key == "vector-rect" || key == "rect-shape" || key == "▭" -> {
                Rectangle(hdc, left + 2, top + 3, right - 2, bottom - 3)
            }
            key == "vector-circle" || key == "circle-shape" || key == "⚪" -> {
                Ellipse(hdc, left + 2, top + 2, right - 2, bottom - 2)
            }
            key == "vector-ellipse" || key == "ellipse-shape" || key == "⬭" -> {
                Ellipse(hdc, left + 1, top + 4, right - 1, bottom - 4)
            }
            key == "vector-polygon" || key == "polygon-shape" || key == "⬡" -> {
                MoveToEx(hdc, cx, top + 1, null)
                LineTo(hdc, right - 2, top + 5)
                LineTo(hdc, right - 2, bottom - 5)
                LineTo(hdc, cx, bottom - 1)
                LineTo(hdc, left + 2, bottom - 5)
                LineTo(hdc, left + 2, top + 5)
                LineTo(hdc, cx, top + 1)
            }
            key == "vector-star" || key == "star-shape" || key == "⭐" -> {
                MoveToEx(hdc, cx, top + 1, null)
                LineTo(hdc, cx + 3, cy - 2)
                LineTo(hdc, right - 1, cy - 2)
                LineTo(hdc, cx + 4, cy + 2)
                LineTo(hdc, right - 3, bottom - 1)
                LineTo(hdc, cx, cy + 5)
                LineTo(hdc, left + 3, bottom - 1)
                LineTo(hdc, cx - 4, cy + 2)
                LineTo(hdc, left + 1, cy - 2)
                LineTo(hdc, cx - 3, cy - 2)
                LineTo(hdc, cx, top + 1)
            }
            key == "vector-line" || key == "line-shape" || key == "╱" -> {
                MoveToEx(hdc, left + 3, bottom - 3, null)
                LineTo(hdc, right - 3, top + 3)
                Rectangle(hdc, left + 1, bottom - 5, left + 5, bottom - 1)
                Rectangle(hdc, right - 5, top + 1, right - 1, top + 5)
            }
            key == "shape" || key.contains("shape") || key.contains("polygon") -> {
                Rectangle(hdc, left + 2, top + 2, cx + 2, cy + 2)
                Ellipse(hdc, cx - 2, cy - 2, right - 1, bottom - 1)
            }
            key == "bezier-pen" || key == "vector-path" || key.contains("bezier") || key.contains("pen") -> {
                MoveToEx(hdc, left + 2, bottom - 2, null)
                LineTo(hdc, cx, top + 2)
                LineTo(hdc, right - 2, bottom - 2)
                Ellipse(hdc, cx - 2, cy - 1, cx + 2, cy + 3)
            }
            key == "node-edit" || key == "vector-deform" || key.contains("node") || key.contains("vertex") || key.contains("deform") -> {
                MoveToEx(hdc, left + 2, bottom - 3, null)
                LineTo(hdc, cx, top + 4)
                LineTo(hdc, right - 2, bottom - 3)
                Rectangle(hdc, left + 1, bottom - 5, left + 5, bottom - 1)
                Rectangle(hdc, cx - 2, top + 2, cx + 2, top + 6)
                Rectangle(hdc, right - 5, bottom - 5, right - 1, bottom - 1)
            }
            key == "swap" || key.contains("swap") -> {
                MoveToEx(hdc, left + 2, top + 4, null); LineTo(hdc, right - 2, top + 4)
                MoveToEx(hdc, right - 5, top + 1, null); LineTo(hdc, right - 2, top + 4); LineTo(hdc, right - 5, top + 7)
                MoveToEx(hdc, right - 2, bottom - 4, null); LineTo(hdc, left + 2, bottom - 4)
                MoveToEx(hdc, left + 5, bottom - 7, null); LineTo(hdc, left + 2, bottom - 4); LineTo(hdc, left + 5, bottom - 1)
            }
            key == "play" -> {
                MoveToEx(hdc, left + 3, top + 2, null)
                LineTo(hdc, right - 2, cy)
                LineTo(hdc, left + 3, bottom - 2)
                LineTo(hdc, left + 3, top + 2)
            }
            key == "pause" -> {
                MoveToEx(hdc, cx - 3, top + 2, null); LineTo(hdc, cx - 3, bottom - 2)
                MoveToEx(hdc, cx + 3, top + 2, null); LineTo(hdc, cx + 3, bottom - 2)
            }
            key == "stop" -> {
                Rectangle(hdc, left + 3, top + 3, right - 3, bottom - 3)
            }
            key == "plus" || key == "add" -> {
                MoveToEx(hdc, cx, top + 2, null); LineTo(hdc, cx, bottom - 2)
                MoveToEx(hdc, left + 2, cy, null); LineTo(hdc, right - 2, cy)
            }
            key == "trash" || key == "delete" || key == "remove" -> {
                MoveToEx(hdc, left + 2, top + 4, null); LineTo(hdc, right - 2, top + 4)
                Rectangle(hdc, left + 4, top + 4, right - 4, bottom - 2)
            }
            else -> {
                val glyph = if (iconSpec.length <= 3) iconSpec else "◈"
                drawTextInRect(hdc, glyph, left - 2, top - 2, right + 2, bottom + 2, colorRef, center = true)
            }
        }

        SelectObject(hdc, oldBrush)
        SelectObject(hdc, oldPen)
        DeleteObject(pen)
    }

    /**
     * Lightweight SVG `<path d="...">` command renderer (`M`, `L`, `H`, `V`, `C`, `Q`, `Z`) using Win32 GDI.
     */
    private fun renderSvgPathGdi(
        hdc: HDC?,
        pathD: String,
        originX: Int,
        originY: Int,
        scaleX: Float,
        scaleY: Float
    ) {
        val tokens = Regex("([MmLlHhVvCcQqZz])|(-?\\d+(?:\\.\\d+)?)")
            .findAll(pathD)
            .map { it.value }
            .toList()
        var idx = 0
        var cmd = 'M'
        var curX = 0f
        var curY = 0f
        var startX = 0f
        var startY = 0f

        fun toWinX(x: Float): Int = originX + (x * scaleX).roundToInt()
        fun toWinY(y: Float): Int = originY + (y * scaleY).roundToInt()

        while (idx < tokens.size) {
            val t = tokens[idx]
            val firstChar = t[0]
            if (firstChar.isLetter()) {
                cmd = firstChar
                idx++
                if (cmd == 'Z' || cmd == 'z') {
                    LineTo(hdc, toWinX(startX), toWinY(startY))
                    curX = startX
                    curY = startY
                    continue
                }
                if (idx >= tokens.size) break
            }
            fun nextFloat(): Float = if (idx < tokens.size) tokens[idx++].toFloatOrNull() ?: 0f else 0f

            when (cmd) {
                'M' -> {
                    curX = nextFloat()
                    curY = nextFloat()
                    startX = curX
                    startY = curY
                    MoveToEx(hdc, toWinX(curX), toWinY(curY), null)
                    cmd = 'L'
                }
                'm' -> {
                    curX += nextFloat()
                    curY += nextFloat()
                    startX = curX
                    startY = curY
                    MoveToEx(hdc, toWinX(curX), toWinY(curY), null)
                    cmd = 'l'
                }
                'L' -> {
                    curX = nextFloat()
                    curY = nextFloat()
                    LineTo(hdc, toWinX(curX), toWinY(curY))
                }
                'l' -> {
                    curX += nextFloat()
                    curY += nextFloat()
                    LineTo(hdc, toWinX(curX), toWinY(curY))
                }
                'H' -> {
                    curX = nextFloat()
                    LineTo(hdc, toWinX(curX), toWinY(curY))
                }
                'h' -> {
                    curX += nextFloat()
                    LineTo(hdc, toWinX(curX), toWinY(curY))
                }
                'V' -> {
                    curY = nextFloat()
                    LineTo(hdc, toWinX(curX), toWinY(curY))
                }
                'v' -> {
                    curY += nextFloat()
                    LineTo(hdc, toWinX(curX), toWinY(curY))
                }
                'C' -> {
                    val x1 = nextFloat(); val y1 = nextFloat()
                    val x2 = nextFloat(); val y2 = nextFloat()
                    val x = nextFloat(); val y = nextFloat()
                    val midX = (curX + 3f * x1 + 3f * x2 + x) / 8f
                    val midY = (curY + 3f * y1 + 3f * y2 + y) / 8f
                    LineTo(hdc, toWinX(midX), toWinY(midY))
                    curX = x; curY = y
                    LineTo(hdc, toWinX(curX), toWinY(curY))
                }
                'c' -> {
                    val x1 = curX + nextFloat(); val y1 = curY + nextFloat()
                    val x2 = curX + nextFloat(); val y2 = curY + nextFloat()
                    val x = curX + nextFloat(); val y = curY + nextFloat()
                    val midX = (curX + 3f * x1 + 3f * x2 + x) / 8f
                    val midY = (curY + 3f * y1 + 3f * y2 + y) / 8f
                    LineTo(hdc, toWinX(midX), toWinY(midY))
                    curX = x; curY = y
                    LineTo(hdc, toWinX(curX), toWinY(curY))
                }
                'Q' -> {
                    val x1 = nextFloat(); val y1 = nextFloat()
                    val x = nextFloat(); val y = nextFloat()
                    val midX = (curX + 2f * x1 + x) / 4f
                    val midY = (curY + 2f * y1 + y) / 4f
                    LineTo(hdc, toWinX(midX), toWinY(midY))
                    curX = x; curY = y
                    LineTo(hdc, toWinX(curX), toWinY(curY))
                }
                'q' -> {
                    val x1 = curX + nextFloat(); val y1 = curY + nextFloat()
                    val x = curX + nextFloat(); val y = curY + nextFloat()
                    val midX = (curX + 2f * x1 + x) / 4f
                    val midY = (curY + 2f * y1 + y) / 4f
                    LineTo(hdc, toWinX(midX), toWinY(midY))
                    curX = x; curY = y
                    LineTo(hdc, toWinX(curX), toWinY(curY))
                }
                else -> idx++
            }
        }
    }

    private fun drawCheckboxControl(
        hdc: HDC?,
        theme: SystemThemePalette,
        label: String,
        checked: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        widget: DesktopNativeWidget? = null,
        hwndForHover: HWND? = null
    ) {
        val boxSize = 14
        val boxTop = top + ((bottom - top) - boxSize) / 2
        val impl = widget?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>
        val tintColor = impl?.getEffectiveTintColor()?.let { rgbaToColorRef(it) }
        val hovered = (widget != null && hoveredWidget == widget) || isRectHovered(hwndForHover, left, top, right, bottom)
        val focused = widget != null && focusedWidget == widget
        val boxBg = when {
            checked && tintColor != null -> tintColor
            checked -> theme.accentBlue
            hovered -> theme.bgElevated
            else -> theme.bgCard
        }
        val boxBorder = when {
            focused -> theme.accentBlueBorder
            hovered -> theme.borderStrong
            checked -> theme.accentBlueBorder
            else -> theme.borderSubtle
        }
        fillRectColor(hdc, left, boxTop, left + boxSize, boxTop + boxSize, boxBg)
        drawRectBorder(hdc, left, boxTop, left + boxSize, boxTop + boxSize, boxBorder)
        if (checked) {
            drawTextInRect(hdc, "✓", left, boxTop - 1, left + boxSize, boxTop + boxSize, rgb(255, 255, 255), center = true)
        }
        drawTextInRect(hdc, label, left + boxSize + 6, top, right, bottom, theme.textPrimary)
    }

    private fun drawSelectControl(
        hdc: HDC?,
        theme: SystemThemePalette,
        label: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        widget: DesktopNativeWidget? = null,
        hwndForHover: HWND? = null
    ) {
        val impl = widget?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>
        val tintColor = impl?.getEffectiveTintColor()?.let { rgbaToColorRef(it) }
        val hovered = (widget != null && hoveredWidget == widget) || isRectHovered(hwndForHover, left, top, right, bottom)
        val focused = widget != null && focusedWidget == widget
        val bg = when {
            tintColor != null -> tintColor
            hovered -> theme.bgElevated
            else -> theme.bgCard
        }
        val border = when {
            focused -> theme.accentBlueBorder
            hovered -> theme.borderStrong
            else -> theme.borderSubtle
        }
        drawRoundedRect(hdc, left, top, right, bottom, 4, bg, border)
        drawTextInRect(hdc, label, left + 8, top + 2, right - 22, bottom - 2, theme.textPrimary)
        drawTextInRect(hdc, "▼", right - 20, top + 2, right - 4, bottom - 2, theme.textSecondary, center = true)
    }

    private fun drawSliderControl(
        hdc: HDC?,
        theme: SystemThemePalette,
        slider: DesktopRangeSliderNativeImpl<*>,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        val midY = (top + bottom) / 2
        val tintColor = slider.getEffectiveTintColor()?.let { rgbaToColorRef(it) }
        val hovered = hoveredWidget == slider.nativeWidget
        val activeColor = tintColor ?: theme.accentBlue
        val thumbColor = if (hovered) lightenColorRef(activeColor, 26) else theme.accentBlueHover
        fillRectColor(hdc, left, midY - 3, right, midY + 3, theme.bgElevated)
        val span = (slider.getMax() - slider.getMin()).coerceAtLeast(0.0001)
        val frac = ((slider.getValue() - slider.getMin()) / span).coerceIn(0.0, 1.0)
        val fillX = left + ((right - left) * frac).roundToInt()
        fillRectColor(hdc, left, midY - 3, fillX, midY + 3, activeColor)
        fillRectColor(hdc, fillX - 5, top + 1, fillX + 5, bottom - 1, thumbColor)
    }

    private fun fillRectColor(hdc: HDC?, left: Int, top: Int, right: Int, bottom: Int, colorRef: UInt) = memScoped {
        val r = alloc<RECT>()
        r.left = left
        r.top = top
        r.right = right
        r.bottom = bottom
        val brush = CreateSolidBrush(colorRef)
        FillRect(hdc, r.ptr, brush)
        DeleteObject(brush)
    }

    private fun drawRectBorder(hdc: HDC?, left: Int, top: Int, right: Int, bottom: Int, colorRef: UInt) = memScoped {
        val r = alloc<RECT>()
        r.left = left
        r.top = top
        r.right = right
        r.bottom = bottom
        val brush = CreateSolidBrush(colorRef)
        FrameRect(hdc, r.ptr, brush)
        DeleteObject(brush)
    }

    private fun drawLineColor(hdc: HDC?, x0: Int, y0: Int, x1: Int, y1: Int, colorRef: UInt) {
        val pen = CreatePen(PS_SOLID, 1, colorRef)
        val oldPen = SelectObject(hdc, pen)
        MoveToEx(hdc, x0, y0, null)
        LineTo(hdc, x1, y1)
        SelectObject(hdc, oldPen)
        DeleteObject(pen)
    }

    private fun drawTextInRect(
        hdc: HDC?,
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        colorRef: UInt,
        center: Boolean = false,
        alignRight: Boolean = false
    ) = memScoped {
        val r = alloc<RECT>()
        r.left = left
        r.top = top
        r.right = right
        r.bottom = bottom
        SetTextColor(hdc, colorRef)
        val horizFlag = when {
            center -> DT_CENTER
            alignRight -> DT_RIGHT
            else -> DT_LEFT
        }
        val flags = (DT_SINGLELINE or DT_VCENTER or horizFlag or DT_END_ELLIPSIS or DT_NOPREFIX).toUInt()
        DrawTextW(hdc, text, -1, r.ptr, flags)
    }

    private fun rgb(r: Int, g: Int, b: Int): UInt =
        ((r and 0xFF) or ((g and 0xFF) shl 8) or ((b and 0xFF) shl 16)).toUInt()

    fun isMainHostWindow(hwnd: HWND?): Boolean = hwnd != null && hwnd == activeHwnd

    fun clearActiveWindow() {
        Win32NativeTitleBarRenderer.clearWindowState(null)
        releaseMainBackBufferDc()
        activeHwnd = null
        panelHwnds.clear()
        hwndToPanel.clear()
        hitRegionsByHwnd.clear()
        isDraggingCardScrollbarThumb = false
        isDraggingLazyGridScrollbarThumb = false
    }
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun win32HostWndProc(hwnd: HWND?, msg: UINT, wParam: WPARAM, lParam: LPARAM): LRESULT {
    val isPanelTool = Win32NativeWindowHost.isPanelToolWindow(hwnd)
    val isMainCustom = Win32NativeWindowHost.isMainHostWindow(hwnd) &&
        Win32NativeWindowHost.hasCustomMainWindowTitleBar()
    val hasCustomTitleBarFrame = isPanelTool || isMainCustom

    if (hasCustomTitleBarFrame && hwnd != null) {
        Win32DwmUxThemeBridge.dwmDefWindowProc(hwnd, msg, wParam, lParam)?.let { dwmRes ->
            return dwmRes
        }
    }

    when (msg.toInt()) {
        WM_NCCALCSIZE -> {
            if (wParam != 0uL && hasCustomTitleBarFrame && hwnd != null) {
                return Win32NativeTitleBarRenderer.handleNcCalcSize(hwnd, wParam, lParam)
            }
        }
        WM_NCHITTEST -> {
            if (hwnd != null && hasCustomTitleBarFrame) {
                val screenX = (lParam and 0xFFFF).toShort().toInt()
                val screenY = ((lParam ushr 16) and 0xFFFF).toShort().toInt()
                val hit = if (isPanelTool) {
                    Win32NativeWindowHost.hitTestPanelWindow(hwnd, screenX, screenY)
                } else {
                    Win32NativeWindowHost.hitTestMainWindow(hwnd, screenX, screenY)
                }
                Win32NativeTitleBarRenderer.updateHoveredNcButton(hwnd, hit.toInt())
                return hit
            }
        }
        WM_NCMOUSEMOVE -> {
            if (hwnd != null && hasCustomTitleBarFrame) {
                if (Win32NativeTitleBarRenderer.handleNcMouseMove(hwnd, wParam.toInt())) {
                    return 0L
                }
            }
        }
        0x02A2 /* WM_NCMOUSELEAVE */ -> {
            if (hwnd != null && hasCustomTitleBarFrame) {
                Win32NativeTitleBarRenderer.handleNcMouseLeave(hwnd)
                return 0L
            }
        }
        WM_NCLBUTTONDOWN -> {
            if (hwnd != null && hasCustomTitleBarFrame) {
                val screenX = (lParam and 0xFFFF).toShort().toInt()
                val screenY = ((lParam ushr 16) and 0xFFFF).toShort().toInt()
                if (Win32NativeTitleBarRenderer.handleNcLButtonDown(hwnd, wParam.toInt(), screenX, screenY)) {
                    return 0L
                }
            }
        }
        WM_NCLBUTTONUP -> {
            if (hwnd != null && hasCustomTitleBarFrame) {
                if (Win32NativeTitleBarRenderer.handleNcLButtonUp(
                        hwnd = hwnd,
                        hitCode = wParam.toInt(),
                        isMainWindow = Win32NativeWindowHost.isMainHostWindow(hwnd) && !isPanelTool
                    )
                ) {
                    return 0L
                }
            }
        }
        WM_NCRBUTTONUP -> {
            if (hwnd != null && hasCustomTitleBarFrame) {
                val hitCode = wParam.toInt()
                if (hitCode == HTCAPTION || hitCode == HTSYSMENU) {
                    val screenX = (lParam and 0xFFFF).toShort().toInt()
                    val screenY = ((lParam ushr 16) and 0xFFFF).toShort().toInt()
                    Win32NativeTitleBarRenderer.showNativeSystemMenu(hwnd, screenX, screenY)
                    return 0L
                }
            }
        }
        WM_ACTIVATE -> {
            if (hwnd != null) {
                val active = (wParam.toInt() and 0xFFFF) != WA_INACTIVE
                Win32NativeTitleBarRenderer.setWindowActive(hwnd, active)
                InvalidateRect(hwnd, null, 0)
            }
        }
        WM_NCACTIVATE -> {
            if (hwnd != null) {
                Win32NativeTitleBarRenderer.setWindowActive(hwnd, wParam != 0uL)
                InvalidateRect(hwnd, null, 0)
                if (hasCustomTitleBarFrame) {
                    return 1L
                }
            }
        }
        WM_SETTINGCHANGE,
        0x031A /* WM_THEMECHANGED */,
        0x0320 /* WM_DWMCOLORIZATIONCOLORCHANGED */ -> {
            if (hwnd != null) {
                Win32NativeWindowHost.invalidateSystemThemeCache()
                Win32DwmUxThemeBridge.clearCachedHwndState(hwnd)
                Win32NativeWindowHost.invalidateAllWindows()
            }
        }
        WM_ERASEBKGND -> return 1L
        WM_SETCURSOR -> {
            val hitTest = (lParam.toLong() and 0xFFFF).toInt()
            if (hwnd != null && hitTest == HTCLIENT) {
                runCatching { Win32NativeWindowHost.updateCursorForWindow(hwnd) }
                return 1L
            }
        }
        WM_PAINT -> {
            if (hwnd != null) {
                runCatching { Win32NativeWindowHost.paintWindow(hwnd) }
            }
            return 0L
        }
        WM_SIZE -> {
            if (hwnd != null) {
                runCatching { Win32NativeWindowHost.handleWindowResize(hwnd) }
                InvalidateRect(hwnd, null, 0)
            }
            return 0L
        }
        WM_COMMAND -> {
            val cmdId = (wParam.toLong() and 0xFFFF).toInt()
            runCatching { Win32NativeWindowHost.handleCommand(cmdId) }
            return 0L
        }
        WM_KEYDOWN -> {
            if (hwnd != null) {
                runCatching { Win32NativeWindowHost.handleKeyDown(hwnd, wParam.toInt()) }
            }
            return 0L
        }
        WM_KEYUP -> {
            if (hwnd != null) {
                runCatching { Win32NativeWindowHost.handleKeyUp(hwnd, wParam.toInt()) }
            }
            return 0L
        }
        WM_CHAR -> {
            if (hwnd != null) {
                runCatching { Win32NativeWindowHost.handleCharInput(hwnd, wParam.toInt()) }
            }
            return 0L
        }
        WM_LBUTTONDOWN -> {
            if (hwnd != null) {
                val x = (lParam.toLong() and 0xFFFF).toShort().toInt()
                val y = ((lParam.toLong() ushr 16) and 0xFFFF).toShort().toInt()
                runCatching { Win32NativeWindowHost.handleMouseDown(hwnd, x, y) }
            }
            return 0L
        }
        WM_MOUSEMOVE -> {
            if (hwnd != null) {
                Win32NativeTitleBarRenderer.updateHoveredNcButton(hwnd, 0)
                val x = (lParam.toLong() and 0xFFFF).toShort().toInt()
                val y = ((lParam.toLong() ushr 16) and 0xFFFF).toShort().toInt()
                runCatching { Win32NativeWindowHost.handleMouseMove(hwnd, x, y) }
            }
            return 0L
        }
        WM_LBUTTONUP -> {
            if (hwnd != null) {
                val x = (lParam.toLong() and 0xFFFF).toShort().toInt()
                val y = ((lParam.toLong() ushr 16) and 0xFFFF).toShort().toInt()
                ReleaseCapture()
                runCatching { Win32NativeWindowHost.handleMouseUp(hwnd, x, y) }
            }
            return 0L
        }
        WM_MOUSEWHEEL -> {
            if (hwnd != null) {
                val wheelDelta = ((wParam.toLong() ushr 16) and 0xFFFF).toShort().toInt()
                runCatching { Win32NativeWindowHost.handleMouseWheel(hwnd, wheelDelta) }
            }
            return 0L
        }
        WM_CLOSE -> {
            if (hwnd != null && !Win32NativeWindowHost.isMainHostWindow(hwnd)) {
                ShowWindow(hwnd, SW_HIDE)
                return 0L
            }
        }
        WM_DESTROY -> {
            Win32NativeTitleBarRenderer.clearWindowState(hwnd)
            if (Win32NativeWindowHost.isMainHostWindow(hwnd)) {
                Win32NativeWindowHost.clearActiveWindow()
                PostQuitMessage(0)
            }
            return 0L
        }
    }
    return DefWindowProcW(hwnd, msg, wParam, lParam)
}
