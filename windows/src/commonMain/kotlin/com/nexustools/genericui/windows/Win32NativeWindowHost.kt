@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.dock.DesktopDockPanelNativeImpl
import com.nexustools.genericui.desktop.dock.DesktopDockWorkspaceNativeImpl
import com.nexustools.genericui.desktop.dock.DesktopToolPaletteNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopMenuItemNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopMenuNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopButtonNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopCheckboxNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopProgressBarNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopRangeSliderNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopSelectNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTabContainerNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTextInputNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTextNativeImpl
import com.nexustools.genericui.event.PointerEvent
import com.nexustools.genericui.res.Resources
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.gradient.Gradient
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

    var toolboxHwnd: HWND? = null
        private set

    var timelineHwnd: HWND? = null
        private set

    var rightPanelsHwnd: HWND? = null
        private set

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
    private var hoveredTooltip: String? = null
    private var hoverMouseX: Int = 0
    private var hoverMouseY: Int = 0
    private var hoverHwndKey: Long = 0L
    private var canvasViewportRect: IntArray = intArrayOf(230, 48, 1220, 780)
    private var activeCanvasImpl: DesktopBaseNativeImpl<*>? = null
    private var activeCanvasSurfaceSize: Pair<Int, Int> = 320 to 240
    private var isDraggingCanvas: Boolean = false

    fun queryMonitorRefreshRateHz(): Int = memScoped {
        val devMode = alloc<DEVMODEW>()
        devMode.dmSize = sizeOf<DEVMODEW>().toUShort()
        val ok = EnumDisplaySettingsW(null, ENUM_CURRENT_SETTINGS, devMode.ptr)
        val freq = if (ok != 0) devMode.dmDisplayFrequency.toInt() else 60
        if (freq in 30..500) freq else 60
    }

    fun isWindowsSystemDarkTheme(): Boolean = memScoped {
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
            dwExStyle = WS_EX_APPWINDOW.toUInt(),
            lpClassName = className,
            lpWindowName = title,
            dwStyle = (WS_OVERLAPPEDWINDOW or WS_CLIPCHILDREN).toUInt(),
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
            applyWindowsImmersiveTitleBarTheme(hwnd, isWindowsSystemDarkTheme())
            appIconBig?.let { icon ->
                SendMessageW(hwnd, WM_SETICON.toUInt(), ICON_BIG.toULong(), icon.toLong())
            }
            appIconSmall?.let { icon ->
                SendMessageW(hwnd, WM_SETICON.toUInt(), ICON_SMALL.toULong(), icon.toLong())
            }
        }
        return hwnd
    }

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
        if (lastMultiWindowVisibilityState == multiWindow) return
        lastMultiWindowVisibilityState = multiWindow

        val hInstance = Win32WinMain.hInstance ?: GetModuleHandleW(null)
        val className = "GenericUIWin32HostWindowW"

        if (multiWindow) {
            if (toolboxHwnd == null) {
                toolboxHwnd = createAuxiliaryDockWindow(
                    hInstance = hInstance,
                    className = className,
                    title = "Toolbox - Tool Options",
                    x = 24,
                    y = 48,
                    width = 252,
                    height = 800,
                    ownerHwnd = null
                )
            }
            if (timelineHwnd == null) {
                timelineHwnd = createAuxiliaryDockWindow(
                    hInstance = hInstance,
                    className = className,
                    title = "Animation Timeline & Keyframe Curves",
                    x = 284,
                    y = 32,
                    width = 860,
                    height = 158,
                    ownerHwnd = null
                )
            }
            if (rightPanelsHwnd == null) {
                rightPanelsHwnd = createAuxiliaryDockWindow(
                    hInstance = hInstance,
                    className = className,
                    title = "AI Studio, Layers & GIMP Filters",
                    x = 1152,
                    y = 32,
                    width = 420,
                    height = 816,
                    ownerHwnd = null
                )
            }
            SetWindowPos(
                mainHwnd,
                HWND_TOP,
                284,
                198,
                860,
                650,
                (SWP_SHOWWINDOW or SWP_FRAMECHANGED).toUInt()
            )
            toolboxHwnd?.let {
                ShowWindow(it, SW_SHOWNOACTIVATE)
                InvalidateRect(it, null, 1)
                UpdateWindow(it)
            }
            timelineHwnd?.let {
                ShowWindow(it, SW_SHOWNOACTIVATE)
                InvalidateRect(it, null, 1)
                UpdateWindow(it)
            }
            rightPanelsHwnd?.let {
                ShowWindow(it, SW_SHOWNOACTIVATE)
                InvalidateRect(it, null, 1)
                UpdateWindow(it)
            }
            BringWindowToTop(mainHwnd)
            SetForegroundWindow(mainHwnd)
            InvalidateRect(mainHwnd, null, 1)
            UpdateWindow(mainHwnd)
        } else {
            toolboxHwnd?.let { ShowWindow(it, SW_HIDE) }
            timelineHwnd?.let { ShowWindow(it, SW_HIDE) }
            rightPanelsHwnd?.let { ShowWindow(it, SW_HIDE) }
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
        toolboxHwnd?.let { if (IsWindowVisible(it) != 0) InvalidateRect(it, null, 0) }
        timelineHwnd?.let { if (IsWindowVisible(it) != 0) InvalidateRect(it, null, 0) }
        rightPanelsHwnd?.let { if (IsWindowVisible(it) != 0) InvalidateRect(it, null, 0) }
    }

    fun createAndShowWin32Window(windowImpl: WindowsWindowNativeImpl) {
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
            val title = windowImpl.getTitle().ifBlank { "HAEditor" }

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
            toolboxHwnd?.let { if (IsWindowVisible(it) != 0) UpdateWindow(it) }
            timelineHwnd?.let { if (IsWindowVisible(it) != 0) UpdateWindow(it) }
            rightPanelsHwnd?.let { if (IsWindowVisible(it) != 0) UpdateWindow(it) }
            if (nowMs - startMs < durationMs) {
                Sleep(4u)
            }
        } while (nowMs - startMs < durationMs && activeHwnd != null)
    }

    fun closeAllVisualWindows() {
        toolboxHwnd?.let { DestroyWindow(it) }
        timelineHwnd?.let { DestroyWindow(it) }
        rightPanelsHwnd?.let { DestroyWindow(it) }
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
                if (remainingMs > 2.0) {
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
            runCatching { vblankFn.invoke(timestampMs) }
            dispatched = true
        }
        val childrenSnapshot = widget.children.toList()
        for (child in childrenSnapshot) {
            if (dispatchVBlankTree(child, timestampMs)) {
                dispatched = true
            }
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
        } ?: return false

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
        return false
    }

    fun handleCharInput(hwnd: HWND, charCode: Int): Boolean {
        val textInput = focusedTextInput ?: return false
        if (charCode < 32 || charCode == 127) return false
        val ctrlDown = (GetKeyState(VK_CONTROL).toInt() and 0x8000) != 0
        if (ctrlDown) return false
        val ch = charCode.toChar()
        textInput.setValue(textInput.getValue() + ch)
        invalidateAllWindows()
        return true
    }

    fun updateCursorForWindow(hwnd: HWND): Boolean {
        val hit = hoveredHitRegion
        val cursorId = when {
            hit?.isTextInput == true -> IDC_IBEAM
            hit != null -> IDC_HAND
            hwnd == activeHwnd &&
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
            (prevFocused?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>)?.setFocusedState(false)
            (newFocused?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>)?.setFocusedState(true)
            focusedWidget = newFocused
            focusedTextInput = newFocused?.attributes?.get("nativeImpl") as? DesktopTextInputNativeImpl<*>
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
                return
            }
        }
        val drag = activeDragRegion
        if (drag != null && drag.onDrag != null) {
            runCatching { drag.onDrag.invoke(x, y) }
            InvalidateRect(hwnd, null, 0)
            return
        }
        val regions = hitRegionsByHwnd[hwnd.toLong()].orEmpty()
        val hit = regions.lastOrNull { it.contains(x, y) }
        val prevHit = hoveredHitRegion
        val prevWidget = hoveredWidget
        val newWidget = hit?.widget
        hoveredHitRegion = hit
        if (prevWidget !== newWidget) {
            (prevWidget?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>)?.setHoveredState(false)
            (newWidget?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>)?.setHoveredState(true)
            hoveredWidget = newWidget
        }
        updateCursorForWindow(hwnd)

        val hoveredTipHit = regions.lastOrNull { it.contains(x, y) && !it.tooltip.isNullOrBlank() }
        val newTooltip = hoveredTipHit?.tooltip
        if (newTooltip != hoveredTooltip || prevHit !== hit || prevWidget !== newWidget) {
            hoveredTooltip = newTooltip
            InvalidateRect(hwnd, null, 0)
        }
    }

    fun handleMouseUp(hwnd: HWND, x: Int, y: Int) {
        if (pressedWidget != null) {
            (pressedWidget?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>)?.setActivePressedState(false)
            pressedWidget = null
            InvalidateRect(hwnd, null, 0)
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

    fun paintWindow(hwnd: HWND) = memScoped {
        val ps = alloc<PAINTSTRUCT>()
        val hdc = BeginPaint(hwnd, ps.ptr) ?: return@memScoped
        val clientRect = alloc<RECT>()
        GetClientRect(hwnd, clientRect.ptr)
        val clientW = max(180, clientRect.right - clientRect.left)
        val clientH = max(90, clientRect.bottom - clientRect.top)

        val memDc = CreateCompatibleDC(hdc)
        val memBmp = CreateCompatibleBitmap(hdc, clientW, clientH)
        val oldBmp = SelectObject(memDc, memBmp)

        val fontSmall = createSegoeFont(13, FW_NORMAL)
        val fontSmallBold = createSegoeFont(13, FW_SEMIBOLD)
        val fontNormal = createSegoeFont(15, FW_NORMAL)
        val fontBold = createSegoeFont(15, FW_SEMIBOLD)
        val fontTitle = createSegoeFont(19, FW_BOLD)
        val fontMetricBig = createSegoeFont(26, FW_BOLD)

        val oldFont = SelectObject(memDc, fontNormal)
        SetBkMode(memDc, TRANSPARENT)

        currentPaintHwndKey = hwnd.toLong()
        hitRegions.clear()

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

        // Auxiliary Window 1: GIMP Multi-Window "Toolbox - Tool Options"
        if (hwnd == toolboxHwnd) {
            fillRectColor(memDc, 0, 0, clientW, clientH, theme.bgPanel)
            renderLeftToolbox(
                hwnd = hwnd,
                hdc = memDc,
                theme = theme,
                fontSmall = fontSmall,
                fontNormal = fontNormal,
                fontBold = fontBold,
                toolPalette = toolPalettes.firstOrNull(),
                toolboxDockPanel = toolboxDockPanel,
                width = clientW,
                top = 6,
                bottom = clientH - 6
            )
            if (hoverHwndKey == hwnd.toLong()) {
                renderHoveredTooltipOverlay(memDc, fontSmall, theme, clientW, clientH)
            }
            BitBlt(hdc, 0, 0, clientW, clientH, memDc, 0, 0, SRCCOPY)
            SelectObject(memDc, oldFont)
            DeleteObject(fontSmall)
            DeleteObject(fontSmallBold)
            DeleteObject(fontNormal)
            DeleteObject(fontBold)
            DeleteObject(fontTitle)
            DeleteObject(fontMetricBig)
            SelectObject(memDc, oldBmp)
            DeleteObject(memBmp)
            DeleteDC(memDc)
            EndPaint(hwnd, ps.ptr)
            return@memScoped
        }

        // Auxiliary Window 2: GIMP Multi-Window "Animation Timeline & Keyframe Curves"
        if (hwnd == timelineHwnd) {
            fillRectColor(memDc, 0, 0, clientW, clientH, theme.bgPanel)
            renderTopTimelineDock(
                hwnd = hwnd,
                hdc = memDc,
                theme = theme,
                fontSmall = fontSmall,
                fontNormal = fontNormal,
                fontBold = fontBold,
                timelinePanel = timelinePanel,
                left = 4,
                top = 4,
                right = clientW - 4,
                bottom = clientH - 4
            )
            if (hoverHwndKey == hwnd.toLong()) {
                renderHoveredTooltipOverlay(memDc, fontSmall, theme, clientW, clientH)
            }
            BitBlt(hdc, 0, 0, clientW, clientH, memDc, 0, 0, SRCCOPY)
            SelectObject(memDc, oldFont)
            DeleteObject(fontSmall)
            DeleteObject(fontSmallBold)
            DeleteObject(fontNormal)
            DeleteObject(fontBold)
            DeleteObject(fontTitle)
            DeleteObject(fontMetricBig)
            SelectObject(memDc, oldBmp)
            DeleteObject(memBmp)
            DeleteDC(memDc)
            EndPaint(hwnd, ps.ptr)
            return@memScoped
        }

        // Auxiliary Window 3: GIMP Multi-Window "AI Studio, Layers & GIMP Filters"
        if (hwnd == rightPanelsHwnd) {
            fillRectColor(memDc, 0, 0, clientW, clientH, theme.bgPanel)
            renderRightInspectorDock(
                hwnd = hwnd,
                hdc = memDc,
                theme = theme,
                fontSmall = fontSmall,
                fontNormal = fontNormal,
                fontBold = fontBold,
                panels = rightPanels,
                left = 6,
                top = 6,
                right = clientW - 6,
                bottom = clientH - 6
            )
            if (hoverHwndKey == hwnd.toLong()) {
                renderHoveredTooltipOverlay(memDc, fontSmall, theme, clientW, clientH)
            }
            BitBlt(hdc, 0, 0, clientW, clientH, memDc, 0, 0, SRCCOPY)
            SelectObject(memDc, oldFont)
            DeleteObject(fontSmall)
            DeleteObject(fontSmallBold)
            DeleteObject(fontNormal)
            DeleteObject(fontBold)
            DeleteObject(fontTitle)
            DeleteObject(fontMetricBig)
            SelectObject(memDc, oldBmp)
            DeleteObject(memBmp)
            DeleteDC(memDc)
            EndPaint(hwnd, ps.ptr)
            return@memScoped
        }

        if (toolPalettes.isEmpty() && dockPanels.isEmpty()) {
            fillRectColor(memDc, 0, 0, clientW, clientH, theme.bgBase)
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
                clientH = clientH
            )
            if (hoverHwndKey == hwnd.toLong()) {
                renderHoveredTooltipOverlay(memDc, fontSmall, theme, clientW, clientH)
            }
            BitBlt(hdc, 0, 0, clientW, clientH, memDc, 0, 0, SRCCOPY)
            SelectObject(memDc, oldFont)
            DeleteObject(fontSmall)
            DeleteObject(fontSmallBold)
            DeleteObject(fontNormal)
            DeleteObject(fontBold)
            DeleteObject(fontTitle)
            DeleteObject(fontMetricBig)
            SelectObject(memDc, oldBmp)
            DeleteObject(memBmp)
            DeleteDC(memDc)
            EndPaint(hwnd, ps.ptr)
            return@memScoped
        }

        val multiWindowMode = isStudioMultiWindowMode(rootWidget)
        val canvasWidget = canvases.firstOrNull()
        activeCanvasImpl = canvasWidget?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>

        if (multiWindowMode) {
            // GIMP Multi-Window Mode Main Canvas Window:
            // MenuBar is attached directly to this window; displays Document Tab / Viewport Toolbar strip,
            // full-bleed Hybrid Vector + Raster Canvas Viewport, and bottom Status Bar.
            fillRectColor(memDc, 0, 0, clientW, clientH, theme.bgBase)
            val topBarH = 38
            val statusBarH = 26
            val bodyBottom = max(topBarH + 120, clientH - statusBarH)

            fillRectColor(memDc, 0, 0, clientW, topBarH, theme.bgPanel)
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

            if (hoverHwndKey == hwnd.toLong()) {
                renderHoveredTooltipOverlay(memDc, fontSmall, theme, clientW, clientH)
            }
            BitBlt(hdc, 0, 0, clientW, clientH, memDc, 0, 0, SRCCOPY)

            SelectObject(memDc, oldFont)
            DeleteObject(fontSmall)
            DeleteObject(fontSmallBold)
            DeleteObject(fontNormal)
            DeleteObject(fontBold)
            DeleteObject(fontTitle)
            DeleteObject(fontMetricBig)
            SelectObject(memDc, oldBmp)
            DeleteObject(memBmp)
            DeleteDC(memDc)
            EndPaint(hwnd, ps.ptr)
            return@memScoped
        }

        // Studio Single-Window Dock Layout (HAEditor) — GenericUI Base Native OS System Theme
        fillRectColor(memDc, 0, 0, clientW, clientH, theme.bgBase)
        val topBarH = 40
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
        fillRectColor(memDc, 0, 0, clientW, topBarH, theme.bgPanel)
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

        // 7. Native Hover Tooltip Popup Overlay
        if (hoverHwndKey == hwnd.toLong()) {
            renderHoveredTooltipOverlay(memDc, fontSmall, theme, clientW, clientH)
        }

        BitBlt(hdc, 0, 0, clientW, clientH, memDc, 0, 0, SRCCOPY)

        SelectObject(memDc, oldFont)
        DeleteObject(fontSmall)
        DeleteObject(fontSmallBold)
        DeleteObject(fontNormal)
        DeleteObject(fontBold)
        DeleteObject(fontTitle)
        DeleteObject(fontMetricBig)
        SelectObject(memDc, oldBmp)
        DeleteObject(memBmp)
        DeleteDC(memDc)
        EndPaint(hwnd, ps.ptr)
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
        if (impl != null && impl.hasClass(className)) return true
        val clsStr = (widget.attributes["bootstrapClass"] as? String).orEmpty()
        return clsStr.split(Regex("\\s+")).contains(className)
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

    /**
     * Renders applications built with `:extensions:dsl` (such as `NXTConverter`'s `app-header`,
     * `metrics-strip`, and `queues-container`) matching the exact original design in a native
     * Windows system theme, or falls back to a clean card layout for generic apps.
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
        clientH: Int
    ) {
        val appHeader = findWidgetByClass(rootWidget, "app-header")
        val metricsStrip = findWidgetByClass(rootWidget, "metrics-strip")
        val queuesContainer = findWidgetByClass(rootWidget, "queues-container")

        if (appHeader != null && metricsStrip != null && queuesContainer != null) {
            renderDslConverterLayout(
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

    /**
     * Renders the `NXTConverter` DSL structure (`<header class="app-header">`,
     * `<section class="metrics-strip">`, `<main class="queues-container">`) using native OS theming
     * without duplicating the app icon or app name inside the client layout.
     */
    private fun renderDslConverterLayout(
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
        if (widget == null || !widget.visible || widget.disposed) return
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
        val buttons = mutableListOf<DesktopButtonNativeImpl<*>>()
        val checkboxes = mutableListOf<DesktopCheckboxNativeImpl<*>>()
        val selects = mutableListOf<DesktopSelectNativeImpl<*>>()
        val sliders = mutableListOf<DesktopRangeSliderNativeImpl<*>>()
        val texts = mutableListOf<DesktopTextNativeImpl<*>>()

        fun collectOutsideDockPanels(w: DesktopNativeWidget?) {
            if (w == null || !w.visible || w.disposed || w in dockPanelWidgets) return
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
        bottom: Int
    ) {
        var y = top
        val collapsed = toolboxDockPanel?.isCollapsed() == true
        val headerTitle = toolboxDockPanel?.getTitle()?.ifBlank { "Toolbox - Tool Options" } ?: "Toolbox - Tool Options"
        val chevron = if (collapsed) "▶" else "▼"
        val headerHovered = isRectHovered(hwnd, 6, y, width - 6, y + 24)
        fillRectColor(hdc, 6, y, width - 6, y + 24, if (headerHovered) theme.accentBlueSoftBg else theme.bgElevated)
        drawRectBorder(hdc, 6, y, width - 6, y + 24, if (headerHovered) theme.accentBlueBorder else theme.borderSubtle)
        SelectObject(hdc, fontBold)
        drawTextInRect(hdc, "$chevron  $headerTitle", 12, y + 2, width - 10, y + 22, theme.textPrimary)
        if (toolboxDockPanel != null) {
            val hy = y
            hitRegions.add(
                HitRegion(
                    left = 6,
                    top = hy,
                    right = width - 6,
                    bottom = hy + 24,
                    tooltip = "Click to ${if (collapsed) "expand" else "collapse"} $headerTitle",
                    widget = toolboxDockPanel.nativeWidget,
                    onClick = { _, _ -> toolboxDockPanel.toggleCollapsed() }
                )
            )
        }
        y += 28

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
        val hasOpenCanvasFn = canvasWidget?.attributes?.get("hasOpenCanvas") as? (() -> Boolean)
        val hasOpenCanvas = hasOpenCanvasFn?.invoke()
            ?: (canvasWidget != null && !canvasWidget.attributes.containsKey("hasOpenCanvas"))

        if (!hasOpenCanvas || canvasWidget == null) {
            canvasViewportRect = intArrayOf(-1, -1, -1, -1)
            renderHaEditorSplashScreen(
                hwnd = hwnd,
                hdc = hdc,
                theme = theme,
                fontSmall = fontSmall,
                fontSmallBold = fontSmallBold,
                fontNormal = fontNormal,
                fontBold = fontBold,
                fontTitle = fontTitle,
                canvasWidget = canvasWidget,
                left = left,
                top = top,
                right = right,
                bottom = bottom
            )
            return
        }

        // Document Tabs Bar at the top of the canvas viewport (matches NW.js #ha-viewport-tabs-bar)
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
     * Renders the document tab strip (`#ha-viewport-tabs-bar`) above the open canvas stage,
     * allowing users to switch between open `.hacanvas` documents, close individual tabs (`×`),
     * or create a new tab (`+`). Closing the last tab returns to the HAEditor Splash Screen.
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
        val titles = tabContainer?.getTabTitles().orEmpty().ifEmpty { listOf("🎨 Main Canvas.hacanvas (320×240)") }
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

    /**
     * Renders the HAEditor Welcome Splash Screen inside the canvas viewport when no `.hacanvas`
     * document is open (`hasOpenCanvas == false`), matching the original NW.js `#ha-splash-screen`
     * with information about HAEditor, the `✨ New` and `📂 Open` action buttons, and the 4 feature cards.
     */
    @Suppress("UNCHECKED_CAST")
    private fun renderHaEditorSplashScreen(
        hwnd: HWND,
        hdc: HDC?,
        theme: SystemThemePalette,
        fontSmall: HFONT?,
        fontSmallBold: HFONT?,
        fontNormal: HFONT?,
        fontBold: HFONT?,
        fontTitle: HFONT?,
        canvasWidget: DesktopNativeWidget?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        fillRectColor(hdc, left, top, right, bottom, theme.bgBase)
        val availW = max(240, right - left)
        val availH = max(200, bottom - top)

        val cardW = min(680, availW - 24).coerceAtLeast(240)
        val cardH = min(380, availH - 20).coerceAtLeast(180)
        val cardLeft = left + (availW - cardW) / 2
        val cardTop = top + max(8, (availH - cardH) / 2)
        val cardRight = cardLeft + cardW
        val cardBottom = min(bottom - 6, cardTop + cardH)

        drawRoundedRect(hdc, cardLeft, cardTop, cardRight, cardBottom, 10, theme.bgPanel, theme.borderSubtle)
        // Top highlight bevel on splash card
        drawLineColor(hdc, cardLeft + 8, cardTop + 1, cardRight - 8, cardTop + 1, theme.bgElevated)

        var y = cardTop + 14

        // 1. Pill Badge: "OPEN PACKAGING ZIP + XML • GIMP RASTER + INKSCAPE VECTOR"
        val badgeText = "OPEN PACKAGING ZIP + XML  •  GIMP RASTER + INKSCAPE VECTOR"
        val badgeW = min(cardW - 32, 390)
        val badgeLeft = cardLeft + (cardW - badgeW) / 2
        drawRoundedRect(hdc, badgeLeft, y, badgeLeft + badgeW, y + 22, 11, theme.accentBlueSoftBg, theme.accentBlueBorder)
        SelectObject(hdc, fontSmallBold)
        drawTextInRect(hdc, badgeText, badgeLeft + 8, y + 1, badgeLeft + badgeW - 8, y + 21, theme.accentBlueText, center = true)
        y += 28

        // 2. Title: "🎨 HACanvas & HAEditor Studio"
        SelectObject(hdc, fontTitle)
        drawTextInRect(
            hdc,
            "🎨 HACanvas & HAEditor Studio",
            cardLeft + 16,
            y,
            cardRight - 16,
            y + 28,
            theme.textPrimary,
            center = true
        )
        y += 32

        // 3. Description lines explaining HACanvas (.hacanvas) & HAEditor
        SelectObject(hdc, fontSmall)
        val descLine1 = "HACanvas (.hacanvas) is a hybrid 2D raster & vector document engine and Open Packaging ZIP+XML"
        val descLine2 = "archive format unifying GIMP pixel layers, Inkscape SVG vectors, Reusable Shared Objects (<ObjectRef>),"
        val descLine3 = "multi-format codecs (.psd, .xcf, .ai, .svg, .ora, .png, .gif), keyframe animations, and AI authoring."
        drawTextInRect(hdc, descLine1, cardLeft + 18, y, cardRight - 18, y + 16, theme.textSecondary, center = true)
        y += 16
        drawTextInRect(hdc, descLine2, cardLeft + 18, y, cardRight - 18, y + 16, theme.textSecondary, center = true)
        y += 16
        drawTextInRect(hdc, descLine3, cardLeft + 18, y, cardRight - 18, y + 16, theme.textSecondary, center = true)
        y += 24

        // 4. Primary Action Buttons: "✨ New" (tinted primary button) and "📂 Open" (standard native button)
        val btnW = 148
        val btnH = 34
        val btnGap = 14
        val totalBtnsW = btnW * 2 + btnGap
        val newBtnLeft = cardLeft + (cardW - totalBtnsW) / 2
        val openBtnLeft = newBtnLeft + btnW + btnGap

        val onNewFn = canvasWidget?.attributes?.get("onSplashNewCanvas") as? (() -> Unit)
        val onOpenFn = canvasWidget?.attributes?.get("onSplashOpenFile") as? (() -> Unit)

        SelectObject(hdc, fontBold)
        drawButtonControl(
            hdc = hdc,
            theme = theme,
            label = "✨ New",
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
                tooltip = "Create a New Hybrid Canvas (Ctrl+N)",
                onClick = { _, _ -> onNewFn?.invoke() }
            )
        )

        drawButtonControl(
            hdc = hdc,
            theme = theme,
            label = "📂 Open",
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
                tooltip = "Open an Existing Document or Image (Ctrl+O)",
                onClick = { _, _ -> onOpenFn?.invoke() }
            )
        )
        y += btnH + 16

        // 5. 2x2 Feature Cards Grid (matching the 4 cards in NW.js #ha-splash-screen)
        val gridPadX = 18
        val gridGapX = 12
        val gridGapY = 10
        val featW = (cardW - gridPadX * 2 - gridGapX) / 2
        val availGridH = cardBottom - y - 12
        val featH = max(54, min(76, (availGridH - gridGapY) / 2))

        if (availGridH >= 110) {
            val features = listOf(
                Triple(
                    "📦 .hacanvas ZIP + XML Architecture",
                    "Structured like .docx (document.xml, objects/<id>.xml,",
                    "media/, fonts/) with non-destructive GIMP filter stacks."
                ),
                Triple(
                    "🖌️ GIMP Raster + Inkscape Vector Tools",
                    "Seamless pixel brush/airbrush/clone/heal painting alongside",
                    "Inkscape Bezier paths, shapes, boolean ops & node editing."
                ),
                Triple(
                    "🧩 Reusable Shared Objects & Library",
                    "Author reusable vector/raster component canvases and",
                    "instantiate linked <ObjectRef> nodes across scenes."
                ),
                Triple(
                    "🤖 LM Studio AI Assistant (localhost:1234)",
                    "Built-in multimodal AI agent with 29 direct canvas tools",
                    "to inspect scenes, draw vectors, apply filters & animate."
                )
            )

            features.forEachIndexed { idx, (fTitle, lineA, lineB) ->
                val col = idx % 2
                val row = idx / 2
                val fx = cardLeft + gridPadX + col * (featW + gridGapX)
                val fy = y + row * (featH + gridGapY)
                if (fy + featH <= cardBottom - 6) {
                    drawRoundedRect(hdc, fx, fy, fx + featW, fy + featH, 6, theme.bgCard, theme.borderSubtle)
                    SelectObject(hdc, fontSmallBold)
                    drawTextInRect(hdc, fTitle, fx + 10, fy + 5, fx + featW - 8, fy + 21, theme.textPrimary)
                    SelectObject(hdc, fontSmall)
                    drawTextInRect(hdc, lineA, fx + 10, fy + 22, fx + featW - 8, fy + 37, theme.textSecondary)
                    drawTextInRect(hdc, lineB, fx + 10, fy + 36, fx + featW - 8, fy + 51, theme.textSecondary)
                }
            }
        }
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
        val width = right - left
        val expandedPanels = panels.filter { !it.isCollapsed() }
        val expandedCount = expandedPanels.size.coerceAtLeast(1)
        val totalHeaderSpace = panels.size * 27
        val availBodySpace = max(120, (bottom - top) - totalHeaderSpace)
        val hasExpandedAiPanel = expandedPanels.any {
            it.getTitle().contains("AI", ignoreCase = true) || findWidgetByClass(it.nativeWidget, "ai-chat-container") != null
        }

        for ((panelIdx, panel) in panels.withIndex()) {
            if (y + 24 > bottom) break
            val collapsed = panel.isCollapsed()
            val chevron = if (collapsed) "▶" else "▼"
            val headerHovered = isRectHovered(hwnd, left, y, right, y + 24)
            fillRectColor(hdc, left, y, right, y + 24, if (headerHovered) theme.accentBlueSoftBg else theme.bgElevated)
            drawRectBorder(hdc, left, y, right, y + 24, if (headerHovered) theme.accentBlueBorder else theme.borderSubtle)
            SelectObject(hdc, fontBold)
            drawTextInRect(hdc, "$chevron  ${panel.getTitle()}", left + 8, y + 2, right - 8, y + 22, theme.textPrimary)
            val headerTop = y
            hitRegions.add(
                HitRegion(
                    left = left,
                    top = headerTop,
                    right = right,
                    bottom = headerTop + 24,
                    tooltip = "Click to ${if (collapsed) "expand" else "collapse"} ${panel.getTitle()}",
                    widget = panel.nativeWidget,
                    onClick = { _, _ -> panel.toggleCollapsed() }
                )
            )
            y += 27

            if (collapsed) {
                continue
            }

            val isAiPanel = panel.getTitle().contains("AI", ignoreCase = true) ||
                findWidgetByClass(panel.nativeWidget, "ai-chat-container") != null
            val remainingPanelsBelow = (panels.size - 1 - panelIdx).coerceAtLeast(0)
            val reservedForHeadersBelow = remainingPanelsBelow * 27
            val panelBodyQuota = if (isAiPanel) {
                max(230, availBodySpace / expandedCount + 60)
            } else if (hasExpandedAiPanel && expandedCount > 1) {
                max(80, (availBodySpace - 230) / max(1, expandedCount - 1))
            } else {
                max(84, availBodySpace / expandedCount)
            }
            val panelBottomLimit = min(bottom - reservedForHeadersBelow, y + panelBodyQuota)

            if (isAiPanel) {
                y = renderAiChatPanelBody(
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
                continue
            }

            SelectObject(hdc, fontSmall)
            val buttons = mutableListOf<DesktopButtonNativeImpl<*>>()
            val checkboxes = mutableListOf<DesktopCheckboxNativeImpl<*>>()
            val selects = mutableListOf<DesktopSelectNativeImpl<*>>()
            val sliders = mutableListOf<DesktopRangeSliderNativeImpl<*>>()
            val texts = mutableListOf<DesktopTextNativeImpl<*>>()
            collectPanelControls(panel.nativeWidget, buttons, checkboxes, selects, sliders, texts)

            if (buttons.isNotEmpty()) {
                val cols = if (buttons.all { it.isIconOnly() }) 6 else 2
                val colW = (width - (cols - 1) * 4) / cols
                buttons.take(cols * 3).forEachIndexed { idx, btn ->
                    val col = idx % cols
                    val bx = left + col * (colW + 4)
                    if (y + 22 <= panelBottomLimit) {
                        drawButtonControl(
                            hdc = hdc,
                            theme = theme,
                            label = if (btn.isIconOnly()) "" else btn.getText().take(20),
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
                                tooltip = btn.getTooltipText() ?: btn.getText(),
                                widget = btn.nativeWidget,
                                onClick = { _, _ -> btn.dispatchClick() }
                            )
                        )
                    }
                    if (col == cols - 1 || idx == min(cols * 3 - 1, buttons.lastIndex)) {
                        if (y + 22 <= panelBottomLimit) {
                            y += 25
                        }
                    }
                }
            }

            for (sel in selects.take(2)) {
                if (y + 22 <= panelBottomLimit) {
                    val curVal = sel.getSelectedValue() ?: ""
                    val curLabel = sel.getOptions().firstOrNull { it.first == curVal }?.second ?: curVal
                    drawSelectControl(hdc, theme, curLabel.ifBlank { "Select..." }, left, y, right, y + 22, sel.nativeWidget, hwnd)
                    val sy = y
                    hitRegions.add(
                        HitRegion(
                            left = left,
                            top = sy,
                            right = right,
                            bottom = sy + 22,
                            tooltip = sel.getTooltipText(),
                            widget = sel.nativeWidget,
                            onClick = { mx, my -> openSelectPopupMenu(hwnd, sel, mx, my) }
                        )
                    )
                    y += 25
                }
            }

            for (slider in sliders.take(2)) {
                if (y + 16 <= panelBottomLimit) {
                    drawSliderControl(hdc, theme, slider, left + 2, y, right - 2, y + 13)
                    val sTop = y - 2
                    val sBottom = y + 15
                    val sLeft = left + 2
                    val sRight = right - 2
                    hitRegions.add(
                        HitRegion(
                            left = sLeft,
                            top = sTop,
                            right = sRight,
                            bottom = sBottom,
                            tooltip = slider.getTooltipText(),
                            widget = slider.nativeWidget,
                            onClick = { mx, _ ->
                                slider.setNormalizedFraction((mx - sLeft).toDouble() / max(1, sRight - sLeft).toDouble())
                            },
                            onDrag = { mx, _ ->
                                slider.setNormalizedFraction((mx - sLeft).toDouble() / max(1, sRight - sLeft).toDouble())
                            }
                        )
                    )
                    y += 17
                }
            }

            if (checkboxes.isNotEmpty() && y + 20 <= panelBottomLimit) {
                var cx = left
                for (cb in checkboxes.take(2)) {
                    val cw = (width - 8) / 2
                    drawCheckboxControl(hdc, theme, cb.getLabel(), cb.isChecked(), cx, y, cx + cw, y + 18, cb.nativeWidget, hwnd)
                    val rLeft = cx
                    val rTop = y
                    hitRegions.add(
                        HitRegion(
                            left = rLeft,
                            top = rTop,
                            right = rLeft + cw,
                            bottom = rTop + 18,
                            tooltip = cb.getTooltipText(),
                            widget = cb.nativeWidget,
                            onClick = { _, _ -> cb.setChecked(!cb.isChecked()) }
                        )
                    )
                    cx += cw + 8
                }
                y += 21
            }

            val summaryTexts = texts.map { it.getText().trim() }.filter { it.isNotEmpty() }
            for (line in summaryTexts.takeLast(2)) {
                if (y + 16 <= panelBottomLimit) {
                    drawTextInRect(hdc, line, left + 2, y, right - 2, y + 16, theme.textSecondary)
                    y += 17
                }
            }
            y += 4
        }
    }

    /**
     * Renders the full `:extensions:aichat` (`AiChatContainer`) UI inside the `🤖 AI Studio Assistant`
     * dock panel, replicating the original NW.js `ai-chat-panel.ts`:
     * - Top action buttons (`⚙️ AI Provider`, `🧰 Tools`, `🗑️ Clear`)
     * - Connection & tool count badge (`🔌 LM Studio (http://localhost:1234/v1) • 29 Tools Ready`)
     * - 6 Quick-Action starter prompt chips (`🔍 Inspect Scene`, `⭐ Draw Cyan Star`, `🎨 Paint Gradient`,
     *   `✨ Blur & Shadow`, `🧩 New Shared Badge`, `🎞️ Animate Pulse`)
     * - Message history feed (`🤖 AI Studio Assistant` & `🧑 You` bubbles, reasoning accordions, tool call cards)
     * - Bottom Composer (`TextField` with keyboard focus/typing, Reasoning Effort selector, Fast `➡️` & Reasoning `🧠` send buttons)
     */
    private fun renderAiChatPanelBody(
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
        val width = right - left
        val buttons = mutableListOf<DesktopButtonNativeImpl<*>>()
        val checkboxes = mutableListOf<DesktopCheckboxNativeImpl<*>>()
        val selects = mutableListOf<DesktopSelectNativeImpl<*>>()
        val sliders = mutableListOf<DesktopRangeSliderNativeImpl<*>>()
        val texts = mutableListOf<DesktopTextNativeImpl<*>>()
        val textInputs = mutableListOf<DesktopTextInputNativeImpl<*>>()

        fun collectAiWidgets(w: DesktopNativeWidget?) {
            if (w == null || !w.visible || w.disposed) return
            when (val impl = w.attributes["nativeImpl"]) {
                is DesktopButtonNativeImpl<*> -> buttons.add(impl)
                is DesktopCheckboxNativeImpl<*> -> checkboxes.add(impl)
                is DesktopSelectNativeImpl<*> -> selects.add(impl)
                is DesktopRangeSliderNativeImpl<*> -> sliders.add(impl)
                is DesktopTextNativeImpl<*> -> texts.add(impl)
                is DesktopTextInputNativeImpl<*> -> textInputs.add(impl)
            }
            for (c in w.children) collectAiWidgets(c)
        }
        collectAiWidgets(panel.nativeWidget)

        SelectObject(hdc, fontSmall)

        // 1. Top Toolbar Buttons (⚙️ AI Provider, 🧰 Tools, 🗑️ Clear)
        val topButtons = buttons.filter {
            val t = it.getText()
            t.contains("Provider", ignoreCase = true) ||
                t.contains("Tools", ignoreCase = true) ||
                t.contains("Clear", ignoreCase = true)
        }
        if (topButtons.isNotEmpty() && y + 22 <= bottomLimit) {
            val cols = topButtons.size.coerceAtLeast(1)
            val colW = (width - (cols - 1) * 4) / cols
            topButtons.forEachIndexed { idx, btn ->
                val bx = left + idx * (colW + 4)
                drawButtonControl(
                    hdc = hdc,
                    theme = theme,
                    label = btn.getText(),
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
                        tooltip = btn.getTooltipText() ?: btn.getText(),
                        widget = btn.nativeWidget,
                        onClick = { _, _ -> btn.dispatchClick() }
                    )
                )
            }
            y += 25
        }

        // 2. Connection & Tool Status Bar
        val statusLabel = texts.firstOrNull {
            it.getText().contains("LM Studio", ignoreCase = true) ||
                it.getText().contains("Tools", ignoreCase = true) ||
                it.getText().contains("🔌")
        }?.getText() ?: "🔌 LM Studio (http://localhost:1234/v1) • 29 Tools Ready"
        if (y + 20 <= bottomLimit) {
            drawRoundedRect(hdc, left, y, right, y + 20, 4, theme.accentBlueSoftBg, theme.accentBlueBorder)
            drawTextInRect(hdc, statusLabel, left + 6, y + 1, right - 6, y + 19, theme.accentBlueText)
            y += 23
        }

        // 3. 6 Quick-Action Starter Prompt Chips (2 rows x 3 cols)
        val chipButtons = buttons.filter {
            val t = it.getText()
            t.contains("Inspect", ignoreCase = true) ||
                t.contains("Star", ignoreCase = true) ||
                t.contains("Gradient", ignoreCase = true) ||
                t.contains("Blur", ignoreCase = true) ||
                t.contains("Badge", ignoreCase = true) ||
                t.contains("Animate", ignoreCase = true)
        }
        if (chipButtons.isNotEmpty() && y + 46 <= bottomLimit - 60) {
            val cols = 3
            val colW = (width - (cols - 1) * 4) / cols
            chipButtons.take(6).forEachIndexed { idx, chip ->
                val col = idx % cols
                val row = idx / cols
                val cx = left + col * (colW + 4)
                val cy = y + row * 22
                drawButtonControl(
                    hdc = hdc,
                    theme = theme,
                    label = chip.getText(),
                    icon = chip.getIcon(),
                    left = cx,
                    top = cy,
                    right = cx + colW,
                    bottom = cy + 20,
                    primary = false,
                    widget = chip.nativeWidget,
                    hwndForHover = hwnd
                )
                hitRegions.add(
                    HitRegion(
                        left = cx,
                        top = cy,
                        right = cx + colW,
                        bottom = cy + 20,
                        tooltip = chip.getTooltipText() ?: chip.getText(),
                        widget = chip.nativeWidget,
                        onClick = { _, _ -> chip.dispatchClick() }
                    )
                )
            }
            val chipRows = (min(6, chipButtons.size) + 2) / 3
            y += chipRows * 22 + 3
        }

        // Reserve space at bottom for Composer bar (Prompt Input + Send / Brain buttons)
        val composerH = 54
        val msgBottom = max(y + 36, bottomLimit - composerH)

        // 4. Scrollable Chat Message Feed Box
        if (msgBottom > y + 24) {
            drawRoundedRect(hdc, left, y, right, msgBottom, 5, theme.bgBase, theme.borderSubtle)
            val feedTexts = texts.map { it.getText().trim() }
                .filter {
                    it.isNotEmpty() &&
                        it != statusLabel &&
                        !it.startsWith("AI Studio Assistant", ignoreCase = true) &&
                        !it.startsWith("Reasoning:", ignoreCase = true)
                }
            var msgY = y + 4
            val visibleLines = feedTexts.takeLast(max(2, (msgBottom - y - 8) / 18))
            for (line in visibleLines) {
                if (msgY + 17 > msgBottom - 2) break
                val isRoleHeader = line.startsWith("🤖") || line.startsWith("🧑") || line.startsWith("✓") || line.startsWith("⚠️")
                SelectObject(hdc, if (isRoleHeader) fontBold else fontSmall)
                val color = when {
                    line.startsWith("🧑") -> theme.accentBlueText
                    line.startsWith("✓") -> theme.successText
                    line.startsWith("⚠️") -> theme.warningText
                    isRoleHeader -> theme.textPrimary
                    else -> theme.textSecondary
                }
                val displayLine = line.replace('\n', ' ').take(64)
                drawTextInRect(hdc, displayLine, left + 6, msgY, right - 6, msgY + 16, color)
                msgY += 17
            }
            y = msgBottom + 4
        }

        // 5. Bottom Prompt Composer (Interactive TextField + Fast Send ➡️ + Reasoning Send 🧠 + Effort Select)
        val promptInput = textInputs.lastOrNull()
        val sendButtons = buttons.filter {
            val t = it.getText()
            t.contains("➡️") || t.contains("🧠") || t.contains("Send", ignoreCase = true)
        }
        if (y + 24 <= bottomLimit) {
            val sendBtnsW = if (sendButtons.size >= 2) 96 else 48
            val inputRight = max(left + 80, right - sendBtnsW - 4)
            val isFocused = promptInput != null && focusedWidget == promptInput.nativeWidget
            val inputHovered = isRectHovered(hwnd, left, y, inputRight, y + 24)
            val inputBorder = when {
                isFocused -> theme.accentBlueBorder
                inputHovered -> theme.borderStrong
                else -> theme.borderSubtle
            }
            fillRectColor(hdc, left, y, inputRight, y + 24, theme.bgCard)
            drawRectBorder(hdc, left, y, inputRight, y + 24, inputBorder)
            if (isFocused) {
                drawRectBorder(hdc, left + 1, y + 1, inputRight - 1, y + 23, theme.accentBlue)
            }
            SelectObject(hdc, fontSmall)
            val typed = promptInput?.getValue().orEmpty()
            val placeholder = promptInput?.getPlaceholder()?.ifBlank { "Ask AI to draw, paint, filter, or animate..." }
                ?: "Ask AI to draw, paint, filter, or animate..."
            val textToShow = if (typed.isEmpty()) {
                if (isFocused) "|" else placeholder
            } else {
                if (isFocused) "$typed|" else typed
            }
            val textColor = if (typed.isEmpty() && !isFocused) theme.textMuted else theme.textPrimary
            drawTextInRect(hdc, textToShow, left + 6, y + 2, inputRight - 6, y + 22, textColor)
            val iy = y
            if (promptInput != null) {
                hitRegions.add(
                    HitRegion(
                        left = left,
                        top = iy,
                        right = inputRight,
                        bottom = iy + 24,
                        tooltip = "Click to type an AI prompt (Enter to send)",
                        widget = promptInput.nativeWidget,
                        isTextInput = true,
                        onClick = { _, _ ->
                            setWidgetFocused(promptInput.nativeWidget)
                            InvalidateRect(hwnd, null, 0)
                        }
                    )
                )
            }

            // Fast Send (➡️) and Reasoning Send (🧠) buttons
            var bx = inputRight + 4
            for (sBtn in sendButtons.take(2)) {
                val bw = max(42, (right - bx) / max(1, sendButtons.size - sendButtons.indexOf(sBtn)))
                val isBrain = sBtn.getText().contains("🧠")
                val tintColor = if (isBrain) rgb(124, 58, 237) else null
                drawButtonControl(
                    hdc = hdc,
                    theme = theme,
                    label = sBtn.getText(),
                    icon = sBtn.getIcon(),
                    left = bx,
                    top = y,
                    right = min(right, bx + bw),
                    bottom = y + 24,
                    primary = !isBrain,
                    widget = sBtn.nativeWidget,
                    tintOverride = tintColor,
                    hwndForHover = hwnd
                )
                val bLeft = bx
                val bRight = min(right, bx + bw)
                hitRegions.add(
                    HitRegion(
                        left = bLeft,
                        top = iy,
                        right = bRight,
                        bottom = iy + 24,
                        tooltip = sBtn.getTooltipText() ?: sBtn.getText(),
                        widget = sBtn.nativeWidget,
                        onClick = { _, _ -> sBtn.dispatchClick() }
                    )
                )
                bx = bRight + 4
            }
            y += 27
        }

        // 6. Reasoning Effort Selector Row (if space remains)
        val effortSelect = selects.lastOrNull()
        if (effortSelect != null && y + 20 <= bottomLimit) {
            SelectObject(hdc, fontSmall)
            drawTextInRect(hdc, "Reasoning Effort:", left + 2, y + 1, left + 104, y + 19, theme.textSecondary)
            val curVal = effortSelect.getSelectedValue() ?: "MEDIUM"
            val curLabel = effortSelect.getOptions().firstOrNull { it.first == curVal }?.second ?: curVal
            drawSelectControl(hdc, theme, curLabel, left + 106, y, right, y + 20, effortSelect.nativeWidget, hwnd)
            val sy = y
            hitRegions.add(
                HitRegion(
                    left = left + 106,
                    top = sy,
                    right = right,
                    bottom = sy + 20,
                    tooltip = "Select Reasoning Effort (None / Low / Medium / High / Max)",
                    widget = effortSelect.nativeWidget,
                    onClick = { mx, my -> openSelectPopupMenu(hwnd, effortSelect, mx, my) }
                )
            )
            y += 22
        }

        return y
    }

    /**
     * Renders the Animation Timeline & Keyframe Curves Dock ABOVE the Canvas Viewport in the center column.
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
        bottom: Int
    ) {
        if (timelinePanel == null) return
        val collapsed = timelinePanel.isCollapsed()
        val chevron = if (collapsed) "▶" else "▼"
        val headerTitle = timelinePanel.getTitle().ifBlank { "Animation Timeline & Keyframe Curves" }
        val headerHovered = isRectHovered(hwnd, left, top, right, top + 24)
        fillRectColor(hdc, left + 1, top + 1, right - 1, top + 24, if (headerHovered) theme.accentBlueSoftBg else theme.bgElevated)
        drawLineColor(hdc, left, top + 24, right, top + 24, if (headerHovered) theme.accentBlueBorder else theme.borderSubtle)
        SelectObject(hdc, fontBold)
        drawTextInRect(hdc, "$chevron  $headerTitle", left + 8, top + 2, right - 8, top + 22, theme.textPrimary)
        hitRegions.add(
            HitRegion(
                left = left,
                top = top,
                right = right,
                bottom = top + 24,
                tooltip = "Click to ${if (collapsed) "expand" else "collapse"} $headerTitle",
                widget = timelinePanel.nativeWidget,
                onClick = { _, _ -> timelinePanel.toggleCollapsed() }
            )
        )
        if (collapsed) return

        SelectObject(hdc, fontSmall)
        val buttons = mutableListOf<DesktopButtonNativeImpl<*>>()
        val checkboxes = mutableListOf<DesktopCheckboxNativeImpl<*>>()
        val selects = mutableListOf<DesktopSelectNativeImpl<*>>()
        val sliders = mutableListOf<DesktopRangeSliderNativeImpl<*>>()
        val texts = mutableListOf<DesktopTextNativeImpl<*>>()
        collectPanelControls(timelinePanel.nativeWidget, buttons, checkboxes, selects, sliders, texts)

        var x = left + 8
        val rowY = top + 29
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
                    tooltip = btn.getTooltipText() ?: label,
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
        val leftStatus = texts.firstOrNull()?.getText()
            ?: "Ready — HAEditor Hybrid Vector + Raster Studio (NJS + GenericUI)"
        val rightStatus = texts.lastOrNull { it != texts.firstOrNull() }?.getText()
            ?: "NJS Filter & Transition Engine Ready"
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
        icon: String? = null
    ) {
        drawRoundedRect(hdc, left, top, right, bottom, radius, bg, border)
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
     * Renders crisp GDI vector/glyph icons inside `[left..right, top..bottom]` for all 17 HAEditor
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
            key == "text" || key == "type" -> {
                MoveToEx(hdc, left + 3, top + 3, null); LineTo(hdc, right - 3, top + 3)
                MoveToEx(hdc, cx, top + 3, null); LineTo(hdc, cx, bottom - 2)
                MoveToEx(hdc, cx - 3, bottom - 2, null); LineTo(hdc, cx + 4, bottom - 2)
            }
            key == "shape" || key.contains("shape") || key.contains("polygon") -> {
                Rectangle(hdc, left + 2, top + 2, cx + 2, cy + 2)
                Ellipse(hdc, cx - 2, cy - 2, right - 1, bottom - 1)
            }
            key == "bezier-pen" || key.contains("bezier") || key.contains("pen") -> {
                MoveToEx(hdc, left + 2, bottom - 2, null)
                LineTo(hdc, cx, top + 2)
                LineTo(hdc, right - 2, bottom - 2)
                Ellipse(hdc, cx - 2, cy - 1, cx + 2, cy + 3)
            }
            key == "node-edit" || key.contains("node") || key.contains("vertex") -> {
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
        activeHwnd = null
        toolboxHwnd = null
        timelineHwnd = null
        rightPanelsHwnd = null
        hitRegionsByHwnd.clear()
    }
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun win32HostWndProc(hwnd: HWND?, msg: UINT, wParam: WPARAM, lParam: LPARAM): LRESULT {
    when (msg.toInt()) {
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
        WM_CLOSE -> {
            if (hwnd != null && !Win32NativeWindowHost.isMainHostWindow(hwnd)) {
                ShowWindow(hwnd, SW_HIDE)
                return 0L
            }
        }
        WM_DESTROY -> {
            if (Win32NativeWindowHost.isMainHostWindow(hwnd)) {
                Win32NativeWindowHost.clearActiveWindow()
                PostQuitMessage(0)
            }
            return 0L
        }
    }
    return DefWindowProcW(hwnd, msg, wParam, lParam)
}
