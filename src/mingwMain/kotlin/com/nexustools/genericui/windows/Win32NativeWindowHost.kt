@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.animation.Animator
import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.threading.GenericUiThreading
import com.nexustools.genericui.core.AbstractComponent
import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.dialog.DesktopDialogWindowNativeImpl
import com.nexustools.genericui.desktop.dock.DesktopDockPanelNativeImpl
import com.nexustools.genericui.desktop.layout.DesktopContainerLayoutController
import com.nexustools.genericui.desktop.lazy.DesktopLazyGridNativeImpl
import com.nexustools.genericui.desktop.lazy.DesktopLazyListNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopMenuItemNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopMenuNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopWindowNativeImpl
import com.nexustools.genericui.desktop.system.DesktopConfigDirectoryHelper
import com.nexustools.genericui.desktop.widget.DesktopButtonNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopCheckboxNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopContainerNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopDropdownNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopPopoverNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopScrollViewNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopSelectNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTextInputNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTextNativeImpl
import com.nexustools.genericui.enums.css.CssCursor
import com.nexustools.genericui.enums.css.CssUnit
import com.nexustools.genericui.enums.dialog.DialogPresentationStyle
import com.nexustools.genericui.enums.ui.Placement
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.enums.window.WindowState
import com.nexustools.genericui.event.InputFocusManager
import com.nexustools.genericui.event.KeyEvent
import com.nexustools.genericui.event.PointerDragKind
import com.nexustools.genericui.event.PointerDragManager
import com.nexustools.genericui.event.PointerDragSession
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.image.ImageColorSpace
import com.nexustools.genericui.image.ImageDecodeOptions
import com.nexustools.genericui.image.ImageFormat
import com.nexustools.genericui.image.ImageLoader
import com.nexustools.genericui.image.ImageSource
import com.nexustools.genericui.image.PixelFormat
import com.nexustools.genericui.nativeimpl.layout.DividerNativeImpl
import com.nexustools.genericui.res.DrawableResource
import com.nexustools.genericui.res.Resources
import com.nexustools.genericui.resources.ResourceID
import com.nexustools.genericui.resources.ResourceType
import com.nexustools.genericui.style.css.CssLength
import com.nexustools.genericui.style.theme.SystemThemeDetector
import com.nexustools.genericui.window.TitleBar
import com.nexustools.genericui.window.WindowAspectRatioHelper
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cValue
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toLong
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import platform.windows.AdjustWindowRectEx
import platform.windows.AppendMenuW
import platform.windows.BITMAPINFO
import platform.windows.BITMAPINFOHEADER
import platform.windows.BeginPaint
import platform.windows.CreateCompatibleDC
import platform.windows.CreateDIBSection
import platform.windows.CreateMenu
import platform.windows.CreatePopupMenu
import platform.windows.CreateSolidBrush
import platform.windows.CreateWindowExW
import platform.windows.CW_USEDEFAULT
import platform.windows.DIB_RGB_COLORS
import platform.windows.DefWindowProcW
import platform.windows.DeleteDC
import platform.windows.DeleteObject
import platform.windows.DestroyIcon
import platform.windows.DestroyMenu
import platform.windows.DestroyWindow
import platform.windows.DispatchMessageW
import platform.windows.EndPaint
import platform.windows.FillRect
import platform.windows.GWLP_HWNDPARENT
import platform.windows.GWL_EXSTYLE
import platform.windows.GWL_STYLE
import platform.windows.ClientToScreen
import platform.windows.GA_ROOT
import platform.windows.GetAncestor
import platform.windows.GetClientRect
import platform.windows.GetCurrentThreadId
import platform.windows.GetCursorPos
import platform.windows.GetMessageW
import platform.windows.GetModuleHandleW
import platform.windows.GetMonitorInfoW
import platform.windows.GetParent
import platform.windows.GetSystemMetrics
import platform.windows.GetWindowLongPtrW
import platform.windows.GetWindowPlacement
import platform.windows.GetWindowRect
import platform.windows.HBRUSH
import platform.windows.HDC
import platform.windows.HICON
import platform.windows.HMENU
import platform.windows.HTBOTTOM
import platform.windows.HTBOTTOMLEFT
import platform.windows.HTBOTTOMRIGHT
import platform.windows.HTCAPTION
import platform.windows.HTCLIENT
import platform.windows.HTCLOSE
import platform.windows.HTLEFT
import platform.windows.HTMAXBUTTON
import platform.windows.HTMINBUTTON
import platform.windows.HTRIGHT
import platform.windows.HTSYSMENU
import platform.windows.HTTOP
import platform.windows.HTTOPLEFT
import platform.windows.HTTOPRIGHT
import platform.windows.HTTRANSPARENT
import platform.windows.HWND
import platform.windows.HWND_NOTOPMOST
import platform.windows.HWND_TOP
import platform.windows.HWND_TOPMOST
import platform.windows.HWND__
import platform.windows.ICON_BIG
import platform.windows.ICON_SMALL
import platform.windows.IDC_ARROW
import platform.windows.IDC_SIZENESW
import platform.windows.IDC_SIZENS
import platform.windows.IDC_SIZENWSE
import platform.windows.IDC_SIZEWE
import platform.windows.IDI_APPLICATION
import platform.windows.IMAGE_ICON
import platform.windows.InvalidateRect
import platform.windows.IsChild
import platform.windows.IsIconic
import platform.windows.IsWindow
import platform.windows.IsWindowVisible
import platform.windows.IsZoomed
import platform.windows.KillTimer
import platform.windows.LARGE_INTEGER
import platform.windows.LR_DEFAULTCOLOR
import platform.windows.LR_LOADFROMFILE
import platform.windows.LoadCursorW
import platform.windows.LoadIconW
import platform.windows.LoadImageW
import platform.windows.LPARAM
import platform.windows.LRESULT
import platform.windows.MF_CHECKED
import platform.windows.MF_GRAYED
import platform.windows.MF_POPUP
import platform.windows.MF_SEPARATOR
import platform.windows.MF_STRING
import platform.windows.MINMAXINFO
import platform.windows.MONITORINFO
import platform.windows.MONITOR_DEFAULTTOPRIMARY
import platform.windows.MSG
import platform.windows.MonitorFromWindow
import platform.windows.PAINTSTRUCT
import platform.windows.PM_REMOVE
import platform.windows.POINT
import platform.windows.PeekMessageW
import platform.windows.PostMessageW
import platform.windows.PostQuitMessage
import platform.windows.PostThreadMessageW
import platform.windows.PrintWindow
import platform.windows.QueryPerformanceCounter
import platform.windows.QueryPerformanceFrequency
import platform.windows.RECT
import platform.windows.RedrawWindow
import platform.windows.RDW_ALLCHILDREN
import platform.windows.RDW_INVALIDATE
import platform.windows.RDW_UPDATENOW
import platform.windows.RegisterClassExW
import platform.windows.RegisterWindowMessageW
import platform.windows.ReleaseCapture
import platform.windows.SC_CLOSE
import platform.windows.SC_MAXIMIZE
import platform.windows.SC_MINIMIZE
import platform.windows.SC_MOVE
import platform.windows.SC_RESTORE
import platform.windows.SM_CXICON
import platform.windows.SM_CXSCREEN
import platform.windows.SM_CXSMICON
import platform.windows.SM_CYICON
import platform.windows.SM_CYSCREEN
import platform.windows.SM_CYSMICON
import platform.windows.SPI_GETWORKAREA
import platform.windows.SWP_FRAMECHANGED
import platform.windows.SWP_NOACTIVATE
import platform.windows.SWP_NOMOVE
import platform.windows.SWP_NOSIZE
import platform.windows.SWP_NOZORDER
import platform.windows.SW_HIDE
import platform.windows.SW_MAXIMIZE
import platform.windows.SW_MINIMIZE
import platform.windows.SW_RESTORE
import platform.windows.SW_SHOW
import platform.windows.SW_SHOWMAXIMIZED
import platform.windows.SW_SHOWNA
import platform.windows.SW_SHOWNOACTIVATE
import platform.windows.SW_SHOWNORMAL
import platform.windows.ScreenToClient
import platform.windows.SendMessageW
import platform.windows.SetForegroundWindow
import platform.windows.SetMenu
import platform.windows.SetTimer
import platform.windows.SetWindowLongPtrW
import platform.windows.SetWindowPlacement
import platform.windows.SetWindowPos
import platform.windows.SetWindowTextW
import platform.windows.ShowWindow
import platform.windows.Sleep
import platform.windows.SystemParametersInfoW
import platform.windows.TranslateMessage
import platform.windows.UINT
import platform.windows.UpdateWindow
import platform.windows.WINDOWPLACEMENT
import platform.windows.VK_ESCAPE
import platform.windows.VK_F11
import platform.windows.WindowFromPoint
import platform.windows.WMSZ_BOTTOM
import platform.windows.WMSZ_BOTTOMLEFT
import platform.windows.WMSZ_BOTTOMRIGHT
import platform.windows.WMSZ_LEFT
import platform.windows.WMSZ_RIGHT
import platform.windows.WMSZ_TOP
import platform.windows.WMSZ_TOPLEFT
import platform.windows.WMSZ_TOPRIGHT
import platform.windows.WM_ACTIVATE
import platform.windows.WM_CHAR
import platform.windows.WM_CLOSE
import platform.windows.WM_COMMAND
import platform.windows.WM_CTLCOLORBTN
import platform.windows.WM_CTLCOLOREDIT
import platform.windows.WM_CTLCOLORLISTBOX
import platform.windows.WM_CTLCOLORSTATIC
import platform.windows.WM_DESTROY
import platform.windows.WM_DPICHANGED
import platform.windows.WM_ERASEBKGND
import platform.windows.WM_GETMINMAXINFO
import platform.windows.WM_HSCROLL
import platform.windows.WM_KEYDOWN
import platform.windows.WM_KEYUP
import platform.windows.WM_KILLFOCUS
import platform.windows.WM_LBUTTONDBLCLK
import platform.windows.WM_LBUTTONDOWN
import platform.windows.WM_LBUTTONUP
import platform.windows.WM_MBUTTONDOWN
import platform.windows.WM_MBUTTONUP
import platform.windows.WM_MOUSELEAVE
import platform.windows.WM_MOUSEMOVE
import platform.windows.WM_MOUSEWHEEL
import platform.windows.WM_MOVE
import platform.windows.WM_NCACTIVATE
import platform.windows.WM_NCCALCSIZE
import platform.windows.WM_NCHITTEST
import platform.windows.WM_NCLBUTTONDBLCLK
import platform.windows.WM_NCLBUTTONDOWN
import platform.windows.WM_NCLBUTTONUP
import platform.windows.WM_NCMOUSELEAVE
import platform.windows.WM_NCMOUSEMOVE
import platform.windows.WM_NCRBUTTONUP
import platform.windows.WM_NOTIFY
import platform.windows.WM_PAINT
import platform.windows.WM_QUIT
import platform.windows.WM_RBUTTONDOWN
import platform.windows.WM_RBUTTONUP
import platform.windows.WM_SETCURSOR
import platform.windows.WM_SETFOCUS
import platform.windows.WM_SETICON
import platform.windows.WM_SETTINGCHANGE
import platform.windows.WM_SIZE
import platform.windows.WM_SIZING
import platform.windows.WM_SYSCOMMAND
import platform.windows.WM_THEMECHANGED
import platform.windows.WM_TIMER
import platform.windows.WM_VSCROLL
import platform.windows.WNDCLASSEXW
import platform.windows.WPARAM
import platform.windows.WS_CAPTION
import platform.windows.WS_CLIPCHILDREN
import platform.windows.WS_EX_APPWINDOW
import platform.windows.WS_EX_TOOLWINDOW
import platform.windows.WS_EX_TOPMOST
import platform.windows.WS_MAXIMIZEBOX
import platform.windows.WS_MINIMIZEBOX
import platform.windows.WS_OVERLAPPEDWINDOW
import platform.windows.WS_POPUP
import platform.windows.WS_SYSMENU
import platform.windows.WS_THICKFRAME
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure retained Win32 window host managing top-level `GenericUIWin32HostWindowW` windows, secondary
 * tool/panel windows, standalone dialogs, popovers, system menus, VBlank pacing, message-loop dispatch,
 * `BeginDeferWindowPos` child `HWND` tree synchronization, and `PrintWindow(..., PW_RENDERFULLCONTENT)`
 * window screenshot capture.
 */
object Win32NativeWindowHost {

    const val HOST_WINDOW_CLASS: String = "GenericUIWin32HostWindowW"
    private const val POPOVER_WINDOW_CLASS: String = "GenericUIPopoverHostWindowW"

    const val WM_APP_TRAY_ICON: UInt = 0x8101u
    const val WM_APP_UI_DISPATCH: UInt = 0x8102u
    const val WM_APP_NOTIFY_BALLOON: UInt = 0x8103u
    const val WM_APP_VBLANK: UInt = 0x8104u
    const val WM_APP_LAYOUT_SYNC: UInt = 0x8105u

    internal val WM_TASKBARCREATED: UInt by lazy { RegisterWindowMessageW("TaskbarCreated") }

    private const val PW_RENDERFULLCONTENT_FLAG: UInt = 2u
    private const val HUD_AUTOHIDE_TIMER_ID: ULong = 0x7A01uL
    private const val HUD_AUTOHIDE_DELAY_MS: UInt = 2500u
    private const val WM_ENTERSIZEMOVE_MSG: Int = 0x0231
    private const val WM_EXITSIZEMOVE_MSG: Int = 0x0232

    var visualTestMode: Boolean = false
    var visualTestBannerText: String? = null
    var forcedThemeMode: ThemeMode = ThemeMode.SYSTEM

    var activeWindowImpl: WindowsWindowNativeImpl? = null
    var activeHwnd: HWND? = null

    val secondaryWindowHwnds: MutableMap<WindowsWindowNativeImpl, HWND> = mutableMapOf()
    val panelHwnds: MutableMap<DesktopDockPanelNativeImpl<*>, HWND> = mutableMapOf()
    val dialogHwnds: MutableMap<DesktopDialogWindowNativeImpl<*>, HWND> = mutableMapOf()
    private val popoverHwnds: MutableMap<DesktopPopoverNativeImpl<*>, HWND> = mutableMapOf()

    var activeFullscreenElementWidget: DesktopNativeWidget? = null
    var lastRenderedVideoSwapCount: Long = -1L
    var lastRenderedVideoStageRect: IntArray = intArrayOf(0, 0, 0, 0)
    var monitorRefreshRateHz: Int = 60

    var appIconBig: HICON? = null
    var appIconSmall: HICON? = null

    private class SavedFullscreenState(
        val style: Long,
        val exStyle: Long,
        val wasZoomed: Boolean,
        val normalLeft: Int,
        val normalTop: Int,
        val normalRight: Int,
        val normalBottom: Int,
        val wpFlags: UInt,
        val wpShowCmd: UInt,
        val minPosX: Int,
        val minPosY: Int,
        val maxPosX: Int,
        val maxPosY: Int
    )

    private class SavedFullscreenElementOrigin(
        val fsImpl: DesktopBaseNativeImpl<*>,
        val fsHandle: Win32HwndHandle,
        val originalParentHwnd: HWND?,
        val originalBounds: Rect
    )

    private val savedFullscreenStateByHwnd = HashMap<Long, SavedFullscreenState>()
    private val savedFullscreenElementOriginByHwnd = HashMap<Long, SavedFullscreenElementOrigin>()
    private val dialogScrollYByDialog = HashMap<DesktopDialogWindowNativeImpl<*>, Int>()
    private val dialogBodyMaxScrollYByDialog = HashMap<DesktopDialogWindowNativeImpl<*>, Int>()
    private val menuCmdHandlersByHwnd = HashMap<Long, MutableMap<Int, () -> Unit>>()
    private val undecoratedNcMaxPressedHwnds = HashSet<Long>()
    private val undecoratedNcMaxHoveredByHwnd = HashMap<Long, Win32HwndHandle>()
    private val pendingSizeMoveCursorByHwnd = HashMap<Long, CssCursor>()
    private val ownedWindowIconsByHwnd = HashMap<Long, Pair<HICON?, HICON?>>()
    private val activeHostWmSizeHwnds = HashSet<Long>()
    private val sizeMoveSessionsByHwnd = HashMap<Long, PointerDragSession>()
    private val cachedHostSolidBrushes = HashMap<UInt, HBRUSH>()
    private val fallbackWicCodec: WindowsWicImageCodecNativeImpl by lazy { WindowsWicImageCodecNativeImpl() }
    private var messageLoopThreadId: UInt = 0u
    private val layoutSyncPosted = kotlin.concurrent.AtomicInt(0)
    private var lastSyncedNonOverlayVer: Long = 0L
    private var isSyncingTreeLayout: Boolean = false
    private var lastSystemDarkTheme: Boolean? = null
    private var hostClassRegistered: Boolean = false
    private var overlayInvalidationListenerInstalled: Boolean = false
    private var vblankWidgetsListenerInstalled: Boolean = false
    private var dialogHooksInstalled: Boolean = false
    private var qpcFrequencyHz: Long = 0L
    private val hostFallbackWatcherId = kotlin.concurrent.AtomicInt(-1)
    private val surfaceFrameWakePosted = kotlin.concurrent.AtomicInt(0)

    private var metricsTotalPaints: Int = 0
    private var metricsVideoStageOnlyPaints: Int = 0
    private var metricsFullWindowPaints: Int = 0
    private var metricsLastPaintUs: Long = 0L
    private val metricsSamplesUs = ArrayList<Long>()
    private var metricsLastSlowBreakdown: String? = null

    fun getSecondaryWindowForHwnd(hwnd: HWND?): WindowsWindowNativeImpl? =
        if (hwnd == null) null else secondaryWindowHwnds.entries.firstOrNull { it.value == hwnd }?.key

    fun hasWindow(windowImpl: WindowsWindowNativeImpl): Boolean =
        windowImpl === activeWindowImpl && activeHwnd != null || secondaryWindowHwnds.containsKey(windowImpl)

    fun resolveHwndForWindow(windowImpl: WindowsWindowNativeImpl): HWND? = when {
        windowImpl === activeWindowImpl -> activeHwnd
        else -> secondaryWindowHwnds[windowImpl]
    }

    fun resolveWindowForHwnd(hwnd: HWND?): WindowsWindowNativeImpl? = when {
        hwnd == null -> null
        hwnd == activeHwnd -> activeWindowImpl
        else -> getSecondaryWindowForHwnd(hwnd)
    }

    fun isDialogWindow(hwnd: HWND?): Boolean =
        hwnd != null && dialogHwnds.values.any { it == hwnd }

    fun getDialogForHwnd(hwnd: HWND?): DesktopDialogWindowNativeImpl<*>? =
        if (hwnd == null) null else dialogHwnds.entries.firstOrNull { it.value == hwnd }?.key

    fun isPanelToolWindow(hwnd: HWND?): Boolean =
        hwnd != null && panelHwnds.values.any { it == hwnd }

    fun isMainHostWindow(hwnd: HWND?): Boolean =
        hwnd != null && hwnd == activeHwnd

    fun hasCustomTitleBarForWindow(winImpl: WindowsWindowNativeImpl?, hwnd: HWND? = null): Boolean {
        val w = winImpl ?: return false
        if (w.isUndecorated()) return false
        if (hwnd != null && isFullscreenForWindow(hwnd, w)) return false
        val tb = w.getTitleBarInstructions() ?: return false
        return tb.customTitleBar ||
            tb.leadingComponents.isNotEmpty() ||
            tb.centerComponents.isNotEmpty() ||
            tb.trailingComponents.isNotEmpty()
    }

    fun hasCustomMainWindowTitleBar(): Boolean =
        hasCustomTitleBarForWindow(activeWindowImpl, activeHwnd)

    fun getMainWindowTitleBarHeight(winImpl: WindowsWindowNativeImpl? = activeWindowImpl): Int {
        val w = winImpl ?: return 0
        if (w.isUndecorated()) return 0
        val hwnd = resolveHwndForWindow(w)
        if (hwnd != null && isFullscreenForWindow(hwnd, w)) return 0
        val tb = w.getTitleBarInstructions() ?: return 0
        if (!hasCustomTitleBarForWindow(w, hwnd)) return 0
        return Win32NativeTitleBarRenderer.getDefaultTitleBarHeightPx(
            isToolWindow = w.isToolModalWindow,
            titleBar = tb,
            kind = w.titleBarKind
        )
    }

    fun getPanelWindowTitleBarHeight(panel: DesktopDockPanelNativeImpl<*>? = null): Int {
        val tb = panel?.let {
            (it.nativeWidget.attributes["titleBar"] as? TitleBar)
        }
        return Win32NativeTitleBarRenderer.getDefaultTitleBarHeightPx(
            isToolWindow = true,
            titleBar = tb,
            kind = Win32TitleBarKind.TOOL_WINDOW
        )
    }

    fun invalidateSystemThemeCache() {
        lastSystemDarkTheme = null
    }

    fun isWindowsSystemDarkTheme(): Boolean {
        SystemThemeDetector.overrideDetectedTheme?.let { override ->
            if (!override.isAuto) return override == ThemeMode.DARK
        }
        lastSystemDarkTheme?.let { return it }
        val resolved = Win32DwmUxThemeBridge.isSystemDarkTheme()
        lastSystemDarkTheme = resolved
        return resolved
    }

    fun isEffectivelyDarkTheme(): Boolean = when (forcedThemeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM, ThemeMode.AUTO -> isWindowsSystemDarkTheme()
    }

    fun applyWindowsImmersiveTitleBarTheme(hwnd: HWND?, dark: Boolean) {
        if (hwnd == null || IsWindow(hwnd) == 0) return
        Win32WinMain.allowDarkModeForWindow(hwnd, dark)
        Win32WinMain.syncUxThemeMenuDarkMode(dark)
        val winImpl = resolveWindowForHwnd(hwnd)
        if (winImpl != null) {
            Win32DwmUxThemeBridge.applyWindowDwmAttributes(
                hwnd = hwnd,
                isToolWindow = winImpl.isToolModalWindow,
                extendTopPx = getMainWindowTitleBarHeight(winImpl),
                isDark = dark,
                kind = winImpl.titleBarKind
            )
            return
        }
        val panel = panelHwnds.entries.firstOrNull { it.value == hwnd }?.key
        if (panel != null) {
            Win32DwmUxThemeBridge.applyWindowDwmAttributes(
                hwnd = hwnd,
                isToolWindow = true,
                extendTopPx = getPanelWindowTitleBarHeight(panel),
                isDark = dark,
                kind = Win32TitleBarKind.TOOL_WINDOW
            )
            return
        }
        Win32DwmUxThemeBridge.applyWindowDwmAttributes(
            hwnd = hwnd,
            isToolWindow = false,
            extendTopPx = 0,
            isDark = dark,
            kind = Win32TitleBarKind.STANDARD_WINDOW
        )
    }

    internal fun isInsideHostWmSize(hwnd: HWND?): Boolean =
        hwnd != null && activeHostWmSizeHwnds.contains(hwnd.toLong())

    fun queryMonitorRefreshRateHz(): Int {
        val hwnd = activeHwnd
        if (hwnd != null && Win32DwmUxThemeBridge.isDwmCompositionEnabled()) {
            Win32DwmUxThemeBridge.queryDwmRefreshRateHz(hwnd)?.let { hz ->
                monitorRefreshRateHz = hz.roundToInt().coerceIn(24, 360)
                return monitorRefreshRateHz
            }
        }
        return monitorRefreshRateHz
    }

    fun ensureOverlayInvalidationListenerInstalled() {
        installDialogAndPopoverHooks()
        ensureVBlankWidgetsListenerInstalled()
        if (overlayInvalidationListenerInstalled) return
        overlayInvalidationListenerInstalled = true
        DesktopNativeWidget.onNativeTreeInvalidated = {
            requestCoalescedNonOverlayLayoutSync()
        }
        DesktopNativeWidget.overlayInvalidationListener = { widget ->
            val handle = widget as? Win32HwndHandle
            val owner = handle?.ownerImpl
            if (handle != null && owner != null && handle.interactiveSurfaceSpec != null) {
                Win32InteractiveSurfaceHwnd.ensureSurfaceChildHwnd(handle, owner)
            }
            activeHwnd?.let { top ->
                if (IsWindow(top) != 0) layoutAndSyncWindowChildren(top)
            }
            for (sec in secondaryWindowHwnds.values) {
                if (IsWindow(sec) != 0) layoutAndSyncWindowChildren(sec)
            }
            syncActiveDialogWindows()
            Win32LayeredStageHostHwnd.refreshAllStageBackdropsSynchronously()
            val hwnd = handle?.hwnd
            if (hwnd != null && IsWindow(hwnd) != 0) {
                InvalidateRect(hwnd, null, 0)
            }
        }
        DesktopNativeWidget.onCursorUpdated = {
            if (GenericUiThreading.isOnUiThread()) {
                refreshActiveCursorAtPointerOnUiThread()
            } else {
                GenericUiThreading.runOnUi {
                    refreshActiveCursorAtPointerOnUiThread()
                }
            }
        }
        PointerDragManager.addCursorLockListener { _, _, _ ->
            if (GenericUiThreading.isOnUiThread()) {
                refreshActiveCursorAtPointerOnUiThread()
            } else {
                GenericUiThreading.runOnUi {
                    refreshActiveCursorAtPointerOnUiThread()
                }
            }
        }
    }

    private fun isOwnedSessionHwnd(hwnd: HWND): Boolean {
        if (IsWindow(hwnd) == 0) return false
        fun matchesOrChild(host: HWND?): Boolean =
            host != null && IsWindow(host) != 0 && (hwnd == host || IsChild(host, hwnd) != 0)
        if (matchesOrChild(activeHwnd)) return true
        if (secondaryWindowHwnds.values.any { matchesOrChild(it) }) return true
        if (dialogHwnds.values.any { matchesOrChild(it) }) return true
        if (panelHwnds.values.any { matchesOrChild(it) }) return true
        if (popoverHwnds.values.any { matchesOrChild(it) }) return true
        return false
    }

    private fun refreshActiveCursorAtPointerOnUiThread() {
        if (PointerDragManager.isCursorLocked) {
            Win32ControlMessageRouter.applyLockedCursorIfAny(forceSet = true)
            return
        }
        memScoped {
            val pt = alloc<POINT>()
            if (GetCursorPos(pt.ptr) == 0) return
            var targetHwnd: HWND? = WindowFromPoint(
                cValue<POINT> {
                    x = pt.x
                    y = pt.y
                }
            )
            val screenLParam = ((pt.y and 0xFFFF).toLong() shl 16) or (pt.x and 0xFFFF).toLong()
            while (targetHwnd != null && isOwnedSessionHwnd(targetHwnd)) {
                val hitTest = SendMessageW(targetHwnd, WM_NCHITTEST.toUInt(), 0uL, screenLParam).toInt()
                if (hitTest == HTCLIENT) {
                    if (!Win32ControlMessageRouter.updateCursorForHwnd(targetHwnd, forceSet = true)) {
                        SendMessageW(
                            targetHwnd,
                            WM_SETCURSOR.toUInt(),
                            targetHwnd.toLong().toULong(),
                            ((WM_MOUSEMOVE.toLong() shl 16) or HTCLIENT.toLong())
                        )
                    }
                    return
                }
                if (hitTest != HTTRANSPARENT) {
                    return
                }
                targetHwnd = GetParent(targetHwnd)
            }
        }
    }

    fun nowMicros(): Long = memScoped {
        if (qpcFrequencyHz <= 0L) {
            val freq = alloc<LARGE_INTEGER>()
            if (QueryPerformanceFrequency(freq.ptr) != 0 && freq.QuadPart > 0L) {
                qpcFrequencyHz = freq.QuadPart
            } else {
                qpcFrequencyHz = 1_000_000L
            }
        }
        val counter = alloc<LARGE_INTEGER>()
        QueryPerformanceCounter(counter.ptr)
        (counter.QuadPart * 1_000_000L) / qpcFrequencyHz
    }

    fun recordFramePaintDurationUs(elapsedUs: Long, stageOnly: Boolean) {
        recordPaintMetrics(elapsedUs, stageOnly)
    }

    internal fun recordPaintMetrics(durationUs: Long, isVideoStageOnly: Boolean) {
        val safeUs = durationUs.coerceAtLeast(0L)
        metricsTotalPaints++
        if (isVideoStageOnly) {
            metricsVideoStageOnlyPaints++
        } else {
            metricsFullWindowPaints++
        }
        metricsLastPaintUs = safeUs
        metricsSamplesUs.add(safeUs)
        if (safeUs > 8000L) {
            metricsLastSlowBreakdown = if (isVideoStageOnly) "stage=${safeUs}us" else "window=${safeUs}us"
        }
    }

    internal fun resolveTimerHostHwnd(): HWND? {
        activeHwnd?.takeIf { IsWindow(it) != 0 }?.let { return it }
        dialogHwnds.values.firstOrNull { IsWindow(it) != 0 }?.let { return it }
        secondaryWindowHwnds.values.firstOrNull { IsWindow(it) != 0 }?.let { return it }
        return WindowsHeadlessTrayLoop.currentTrayHwnd()?.takeIf { IsWindow(it) != 0 }
    }

    fun requestQuitMessageLoop() {
        val tid = messageLoopThreadId
        if (tid != 0u) {
            if (GetCurrentThreadId() == tid) {
                PostQuitMessage(0)
            } else {
                PostThreadMessageW(tid, WM_QUIT.toUInt(), 0uL, 0L)
            }
        } else {
            resolveTimerHostHwnd()?.let { h ->
                PostMessageW(h, WM_QUIT.toUInt(), 0uL, 0L)
            }
        }
    }

    internal fun requestCoalescedNonOverlayLayoutSync() {
        if (isSyncingTreeLayout || DesktopNativeWidget.activeViewportScrollScopeCount.value > 0) return
        if (!GenericUiThreading.isOnUiThread()) {
            GenericUiThreading.runOnUi { requestCoalescedNonOverlayLayoutSync() }
            return
        }
        if (!layoutSyncPosted.compareAndSet(0, 1)) return
        val target = resolveTimerHostHwnd()
        if (target != null && IsWindow(target) != 0) {
            if (PostMessageW(target, WM_APP_LAYOUT_SYNC, 0uL, 0L) == 0) {
                layoutSyncPosted.value = 0
            }
        } else if (messageLoopThreadId != 0u) {
            if (PostThreadMessageW(messageLoopThreadId, WM_APP_LAYOUT_SYNC, 0uL, 0L) == 0) {
                layoutSyncPosted.value = 0
            }
        }
    }

    internal fun flushPendingNonOverlayLayoutSyncIfNeeded() {
        if (isSyncingTreeLayout || DesktopNativeWidget.activeViewportScrollScopeCount.value > 0) return
        val currentVer = DesktopNativeWidget.globalNonOverlayMutationVersion
        val posted = layoutSyncPosted.compareAndSet(1, 0)
        if (!posted && currentVer == lastSyncedNonOverlayVer) return
        performCoalescedNonOverlayLayoutSync()
    }

    private fun performCoalescedNonOverlayLayoutSync() {
        if (isSyncingTreeLayout || DesktopNativeWidget.activeViewportScrollScopeCount.value > 0) return
        layoutSyncPosted.value = 0
        isSyncingTreeLayout = true
        try {
            lastSyncedNonOverlayVer = DesktopNativeWidget.globalNonOverlayMutationVersion
            activeHwnd?.let { top ->
                if (IsWindow(top) != 0) layoutAndSyncWindowChildren(top)
            }
            for (sec in secondaryWindowHwnds.values.toList()) {
                if (IsWindow(sec) != 0) layoutAndSyncWindowChildren(sec)
            }
            syncActiveDialogWindows()
            syncMultiWindowDocks()
            Win32LayeredStageHostHwnd.refreshAllStageBackdropsSynchronously()
        } finally {
            isSyncingTreeLayout = false
        }
    }

    internal fun isActiveFullscreenElement(handle: Win32HwndHandle): Boolean {
        val active = activeFullscreenElementWidget ?: return false
        if (active === handle) return true
        return savedFullscreenElementOriginByHwnd.values.any { it.fsHandle === handle }
    }

    internal fun updateVideoStageRect(x: Int, y: Int, width: Int, height: Int) {
        lastRenderedVideoStageRect[0] = x
        lastRenderedVideoStageRect[1] = y
        lastRenderedVideoStageRect[2] = x + width.coerceAtLeast(0)
        lastRenderedVideoStageRect[3] = y + height.coerceAtLeast(0)
    }

    internal fun recordVideoStageSwap(
        swapCount: Long,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        lastRenderedVideoSwapCount = swapCount
        lastRenderedVideoStageRect[0] = left
        lastRenderedVideoStageRect[1] = top
        lastRenderedVideoStageRect[2] = right
        lastRenderedVideoStageRect[3] = bottom
    }

    fun resetFrameMetrics() {
        metricsTotalPaints = 0
        metricsVideoStageOnlyPaints = 0
        metricsFullWindowPaints = 0
        metricsLastPaintUs = 0L
        metricsSamplesUs.clear()
        metricsLastSlowBreakdown = null
    }

    fun getFrameMetricsSnapshot(): Win32FrameMetricsSnapshot {
        if (metricsSamplesUs.isEmpty()) {
            return Win32FrameMetricsSnapshot(
                totalPaints = metricsTotalPaints,
                videoStageOnlyPaints = metricsVideoStageOnlyPaints,
                fullWindowPaints = metricsFullWindowPaints,
                lastPaintUs = metricsLastPaintUs,
                p50PaintUs = 0L,
                p95PaintUs = 0L,
                maxPaintUs = 0L,
                avgPaintUs = 0L,
                lastSlowPaintBreakdown = metricsLastSlowBreakdown ?: ""
            )
        }
        val sorted = metricsSamplesUs.sorted()
        val p50 = sorted[(sorted.size * 50 / 100).coerceIn(0, sorted.lastIndex)]
        val p95 = sorted[(sorted.size * 95 / 100).coerceIn(0, sorted.lastIndex)]
        val maxUs = sorted.last()
        val avgUs = sorted.sum() / sorted.size
        return Win32FrameMetricsSnapshot(
            totalPaints = metricsTotalPaints,
            videoStageOnlyPaints = metricsVideoStageOnlyPaints,
            fullWindowPaints = metricsFullWindowPaints,
            lastPaintUs = metricsLastPaintUs,
            p50PaintUs = p50,
            p95PaintUs = p95,
            maxPaintUs = maxUs,
            avgPaintUs = avgUs,
            lastSlowPaintBreakdown = metricsLastSlowBreakdown ?: ""
        )
    }

    /**
     * Advances animations and live active video/interactive stage surfaces on one VBlank step.
     */
    fun stepLiveVBlankFrame(nowMsOverride: Double? = null): Boolean {
        val nowMs = nowMsOverride ?: (nowMicros().toDouble() / 1000.0)
        val stageStepped = Win32InteractiveSurfaceHwnd.stepVBlankSurfaces(nowMs)
        val mainHwnd = activeHwnd
        if (stageStepped && mainHwnd != null && IsWindow(mainHwnd) != 0) {
            pumpVisualWindowEvents(0L)
        }
        return stageStepped
    }

    fun createAndShowWin32Window(windowImpl: WindowsWindowNativeImpl, startHidden: Boolean = false): HWND? {
        WindowsUiThreadDispatcherNativeImpl.attachCurrentThreadAsUiThreadIfUnset()
        Win32WinMain.ensureWindowsControlsInitialized()
        ensureDefaultAppIconsLoaded(windowImpl.defaultWindowIconPath)
        ensureHostClassRegistered()
        installDialogAndPopoverHooks()
        ensureOverlayInvalidationListenerInstalled()

        val requestedIconPath = windowImpl.getWindowIconPath()?.takeIf { it.isNotBlank() }
            ?: windowImpl.getTitleBarInstructions()?.iconPath?.takeIf { it.isNotBlank() }

        val existing = resolveHwndForWindow(windowImpl)
        if (existing != null && IsWindow(existing) != 0) {
            if (requestedIconPath != null) {
                applyWindowIcons(existing, requestedIconPath)
            }
            layoutAndSyncWindowChildren(existing)
            if (!startHidden) {
                ShowWindow(existing, if (windowImpl.getWindowState() == WindowState.MAXIMIZED) SW_MAXIMIZE else SW_SHOW)
                UpdateWindow(existing)
            }
            return existing
        }

        val (style, exStyle) = computeDesiredWindowStyle(windowImpl)
        val createStyle = if (startHidden) (style and Win32HwndHandle.WS_VISIBLE.inv()) else style
        val clientSize = windowImpl.getWindowSize()
        val clientW = clientSize.width.coerceAtLeast(160)
        val clientH = clientSize.height.coerceAtLeast(120)

        val winRect = memScoped {
            val r = alloc<RECT>()
            r.left = 0
            r.top = 0
            r.right = clientW
            r.bottom = clientH
            AdjustWindowRectEx(r.ptr, createStyle.toUInt(), 0, exStyle.toUInt())
            intArrayOf(r.right - r.left, r.bottom - r.top)
        }
        val winW = winRect[0]
        val winH = winRect[1]
        val winPos = windowImpl.getWindowPosition()
        val posX = winPos.x.roundToInt()
        val posY = winPos.y.roundToInt()
        val hasExplicitPos = windowImpl.hasExplicitWindowPosition() ||
            windowImpl.nativeWidget.attributes["hasExplicitPosition"] == true ||
            (posX != 0 || posY != 0)
        val xPos = if (startHidden) -32000 else if (hasExplicitPos) posX else CW_USEDEFAULT
        val yPos = if (startHidden) -32000 else if (hasExplicitPos) posY else CW_USEDEFAULT

        val title = resolveEffectiveWindowTitle(windowImpl)
        val ownerWin = windowImpl.ownerWindow as? WindowsWindowNativeImpl
        val ownerHwnd = if (ownerWin != null) resolveHwndForWindow(ownerWin) else null
        val hInst = Win32WinMain.hInstance ?: GetModuleHandleW(null)
        val hwnd = CreateWindowExW(
            exStyle.toUInt(),
            HOST_WINDOW_CLASS,
            title,
            createStyle.toUInt(),
            xPos,
            yPos,
            winW,
            winH,
            ownerHwnd,
            null,
            hInst,
            null
        ) ?: error("Failed to create Win32 host HWND for window '$title'")

        if (activeWindowImpl == null || activeWindowImpl === windowImpl) {
            activeWindowImpl = windowImpl
            activeHwnd = hwnd
        } else {
            secondaryWindowHwnds[windowImpl] = hwnd
        }
        WindowsUiTimerNativeImpl.attachPendingTimersToHwnd(hwnd)
        applyWindowIcons(hwnd, requestedIconPath)

        val winHandle = windowImpl.nativeWidget
        winHandle.hwnd = hwnd
        winHandle.actualWin32ClassName = HOST_WINDOW_CLASS
        winHandle.ownerImpl = windowImpl
        if (startHidden) {
            winHandle.dwStyle = winHandle.dwStyle and Win32HwndHandle.WS_VISIBLE.inv()
        } else {
            winHandle.dwStyle = winHandle.dwStyle or Win32HwndHandle.WS_VISIBLE
        }
        Win32ControlMessageRouter.applyVisibility(winHandle, !startHidden)
        Win32ControlMessageRouter.registerEntryHwnd(hwnd, winHandle)

        val dark = isEffectivelyDarkTheme()
        Win32NativeTitleBarRenderer.syncWindowCaptionStyleFlags(
            hwnd = hwnd,
            titleBar = windowImpl.getTitleBarInstructions(),
            isToolWindow = windowImpl.isToolModalWindow,
            minimizableOverride = windowImpl.minimizable,
            kind = windowImpl.titleBarKind
        )
        applyWindowsImmersiveTitleBarTheme(hwnd, dark)
        if (hasCustomTitleBarForWindow(windowImpl, hwnd)) {
            SetWindowPos(
                hwnd,
                null,
                0,
                0,
                0,
                0,
                (SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED).toUInt()
            )
        }
        syncNativeMenuBarToHostWindow(hwnd, windowImpl)
        layoutAndSyncWindowChildren(hwnd)

        if (!startHidden) {
            if (windowImpl.isFullscreen()) {
                val fsWidget = windowImpl.getFullscreenElement()?.nativeWidget as? DesktopNativeWidget
                setWindowFullscreen(windowImpl, true, fsWidget)
                ShowWindow(hwnd, SW_SHOW)
                UpdateWindow(hwnd)
            } else {
                val showCmd = when (windowImpl.getWindowState()) {
                    WindowState.MAXIMIZED -> SW_MAXIMIZE
                    WindowState.MINIMIZED -> SW_MINIMIZE
                    else -> SW_SHOW
                }
                ShowWindow(hwnd, showCmd)
                UpdateWindow(hwnd)
            }
        }
        syncHostVBlankDemand()
        return hwnd
    }

    fun closeSecondaryOrPrimaryWindow(windowImpl: WindowsWindowNativeImpl) {
        val sec = secondaryWindowHwnds.remove(windowImpl)
        if (sec != null) {
            Win32ControlMessageRouter.unregisterEntryHwnd(sec)
            Win32NativeTitleBarRenderer.clearWindowState(sec)
            savedFullscreenStateByHwnd.remove(sec.toLong())
            savedFullscreenElementOriginByHwnd.remove(sec.toLong())
            menuCmdHandlersByHwnd.remove(sec.toLong())
            undecoratedNcMaxPressedHwnds.remove(sec.toLong())
            undecoratedNcMaxHoveredByHwnd.remove(sec.toLong())
            if (IsWindow(sec) != 0) DestroyWindow(sec)
            syncHostVBlankDemand()
            return
        }
        if (windowImpl === activeWindowImpl) {
            val h = activeHwnd
            activeHwnd = null
            activeWindowImpl = null
            activeFullscreenElementWidget = null
            if (h != null) {
                Win32ControlMessageRouter.unregisterEntryHwnd(h)
                Win32NativeTitleBarRenderer.clearWindowState(h)
                savedFullscreenStateByHwnd.remove(h.toLong())
                savedFullscreenElementOriginByHwnd.remove(h.toLong())
                menuCmdHandlersByHwnd.remove(h.toLong())
                if (IsWindow(h) != 0) DestroyWindow(h)
            }
            syncHostVBlankDemand()
        }
    }

    fun closeAllVisualWindows() {
        surfaceFrameWakePosted.value = 0
        layoutSyncPosted.value = 0
        isSyncingTreeLayout = false
        lastSyncedNonOverlayVer = DesktopNativeWidget.globalNonOverlayMutationVersion
        val prevWatcher = hostFallbackWatcherId.value
        if (prevWatcher > 0 && hostFallbackWatcherId.compareAndSet(prevWatcher, -1)) {
            Animator.unwatchVBlank(prevWatcher)
        }
        (Animator.activeScheduler as? WindowsAnimationNativeImpl)?.setHostSurfaceDemand(false, null)
        for ((_, hwnd) in dialogHwnds.toList()) {
            Win32ControlMessageRouter.unregisterEntryHwnd(hwnd)
            if (IsWindow(hwnd) != 0) DestroyWindow(hwnd)
        }
        dialogHwnds.clear()
        dialogScrollYByDialog.clear()
        dialogBodyMaxScrollYByDialog.clear()

        for ((_, hwnd) in panelHwnds.toList()) {
            Win32ControlMessageRouter.unregisterEntryHwnd(hwnd)
            Win32NativeTitleBarRenderer.clearWindowState(hwnd)
            if (IsWindow(hwnd) != 0) DestroyWindow(hwnd)
        }
        panelHwnds.clear()

        for ((_, hwnd) in popoverHwnds.toList()) {
            Win32ControlMessageRouter.unregisterEntryHwnd(hwnd)
            if (IsWindow(hwnd) != 0) DestroyWindow(hwnd)
        }
        popoverHwnds.clear()

        for ((_, hwnd) in secondaryWindowHwnds.toList()) {
            Win32ControlMessageRouter.unregisterEntryHwnd(hwnd)
            Win32NativeTitleBarRenderer.clearWindowState(hwnd)
            if (IsWindow(hwnd) != 0) DestroyWindow(hwnd)
        }
        secondaryWindowHwnds.clear()

        activeHwnd?.let { main ->
            Win32ControlMessageRouter.unregisterEntryHwnd(main)
            Win32NativeTitleBarRenderer.clearWindowState(main)
            if (IsWindow(main) != 0) DestroyWindow(main)
        }
        activeHwnd = null
        activeWindowImpl = null
        for ((_, icons) in ownedWindowIconsByHwnd.toList()) {
            destroyOwnedIconPair(icons)
        }
        ownedWindowIconsByHwnd.clear()
        activeFullscreenElementWidget = null
        lastRenderedVideoSwapCount = -1L
        lastRenderedVideoStageRect = intArrayOf(0, 0, 0, 0)
        Win32ScrollViewCompositor.releaseBackbuffer()
        savedFullscreenStateByHwnd.clear()
        savedFullscreenElementOriginByHwnd.clear()
        menuCmdHandlersByHwnd.clear()
        undecoratedNcMaxPressedHwnds.clear()
        undecoratedNcMaxHoveredByHwnd.clear()
        resetFrameMetrics()
    }

    fun invalidateAllWindows() {
        val dark = isEffectivelyDarkTheme()
        activeHwnd?.let { hwnd ->
            if (IsWindow(hwnd) != 0) {
                applyWindowsImmersiveTitleBarTheme(hwnd, dark)
                activeWindowImpl?.let { updateMainWindowTitle(it, it.getTitle()) }
                layoutAndSyncWindowChildren(hwnd)
                RedrawWindow(hwnd, null, null, RDW_INVALIDATE.toUInt() or RDW_ALLCHILDREN.toUInt())
            }
        }
        for ((winImpl, hwnd) in secondaryWindowHwnds) {
            if (IsWindow(hwnd) != 0) {
                applyWindowsImmersiveTitleBarTheme(hwnd, dark)
                layoutAndSyncWindowChildren(hwnd)
                RedrawWindow(hwnd, null, null, RDW_INVALIDATE.toUInt() or RDW_ALLCHILDREN.toUInt())
            }
        }
        for (hwnd in dialogHwnds.values) {
            if (IsWindow(hwnd) != 0) {
                applyWindowsImmersiveTitleBarTheme(hwnd, dark)
                RedrawWindow(hwnd, null, null, RDW_INVALIDATE.toUInt() or RDW_ALLCHILDREN.toUInt())
            }
        }
        for (hwnd in panelHwnds.values) {
            if (IsWindow(hwnd) != 0) {
                applyWindowsImmersiveTitleBarTheme(hwnd, dark)
                RedrawWindow(hwnd, null, null, RDW_INVALIDATE.toUInt() or RDW_ALLCHILDREN.toUInt())
            }
        }
    }

    fun updateMainWindowTitle(windowImpl: WindowsWindowNativeImpl, title: String) {
        val hwnd = resolveHwndForWindow(windowImpl) ?: return
        if (IsWindow(hwnd) == 0) return
        SetWindowTextW(hwnd, resolveEffectiveWindowTitle(windowImpl, title))
        if (hasCustomTitleBarForWindow(windowImpl, hwnd)) {
            InvalidateRect(hwnd, null, 0)
        }
    }

    fun updateWindowIcon(windowImpl: WindowsWindowNativeImpl, iconPath: String?) {
        val hwnd = resolveHwndForWindow(windowImpl) ?: return
        if (IsWindow(hwnd) == 0) return
        val effectiveIconPath = iconPath?.takeIf { it.isNotBlank() }
            ?: windowImpl.getTitleBarInstructions()?.iconPath?.takeIf { it.isNotBlank() }
        applyWindowIcons(hwnd, effectiveIconPath)
        if (hasCustomTitleBarForWindow(windowImpl, hwnd)) {
            InvalidateRect(hwnd, null, 0)
        } else {
            SetWindowPos(
                hwnd,
                null,
                0,
                0,
                0,
                0,
                (SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED).toUInt()
            )
        }
    }

    fun onMainWindowTitleBarChanged(windowImpl: WindowsWindowNativeImpl) {
        val hwnd = resolveHwndForWindow(windowImpl) ?: return
        if (IsWindow(hwnd) == 0) return
        Win32NativeTitleBarRenderer.syncWindowCaptionStyleFlags(
            hwnd = hwnd,
            titleBar = windowImpl.getTitleBarInstructions(),
            isToolWindow = windowImpl.isToolModalWindow,
            minimizableOverride = windowImpl.minimizable,
            kind = windowImpl.titleBarKind
        )
        val explicitTbIcon = windowImpl.getTitleBarInstructions()?.iconPath?.takeIf { it.isNotBlank() }
        if (explicitTbIcon != null) {
            applyWindowIcons(hwnd, explicitTbIcon)
        } else if (ownedWindowIconsByHwnd[hwnd.toLong()] == null) {
            applyWindowIcons(hwnd, null)
        }
        applyWindowsImmersiveTitleBarTheme(hwnd, isEffectivelyDarkTheme())
        SetWindowPos(hwnd, null, 0, 0, 0, 0, (SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED).toUInt())
        layoutAndSyncWindowChildren(hwnd)
        InvalidateRect(hwnd, null, 0)
    }

    fun syncWindowUndecorated(windowImpl: WindowsWindowNativeImpl, undecorated: Boolean) {
        val hwnd = resolveHwndForWindow(windowImpl) ?: return
        if (IsWindow(hwnd) == 0) return
        val (desiredStyle, desiredEx) = computeDesiredWindowStyle(windowImpl)
        SetWindowLongPtrW(hwnd, GWL_STYLE, desiredStyle)
        SetWindowLongPtrW(hwnd, GWL_EXSTYLE, desiredEx)
        SetWindowPos(hwnd, null, 0, 0, 0, 0, (SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED).toUInt())
        layoutAndSyncWindowChildren(hwnd)
        InvalidateRect(hwnd, null, 0)
    }

    fun startSystemMove(windowImpl: WindowsWindowNativeImpl) {
        val hwnd = resolveHwndForWindow(windowImpl) ?: return
        if (IsWindow(hwnd) == 0) return
        ReleaseCapture()
        val dragSession = PointerDragManager.startWindowMoveDrag(lockedCursor = CssCursor.DEFAULT)
        Win32ControlMessageRouter.applyLockedCursorIfAny(forceSet = true)
        try {
            SendMessageW(hwnd, WM_SYSCOMMAND.toUInt(), (SC_MOVE + 2).toULong(), 0L)
        } finally {
            pendingSizeMoveCursorByHwnd.remove(hwnd.toLong())
            sizeMoveSessionsByHwnd.remove(hwnd.toLong())?.dispose()
            dragSession.dispose()
        }
    }

    fun setWindowAlwaysOnTop(windowImpl: WindowsWindowNativeImpl, alwaysOnTop: Boolean) {
        val hwnd = resolveHwndForWindow(windowImpl) ?: return
        if (IsWindow(hwnd) == 0) return
        val after = if (alwaysOnTop) HWND_TOPMOST else HWND_NOTOPMOST
        SetWindowPos(hwnd, after, 0, 0, 0, 0, (SWP_NOMOVE or SWP_NOSIZE or SWP_NOACTIVATE).toUInt())
    }

    fun setWindowAspectRatio(windowImpl: WindowsWindowNativeImpl, aspectRatio: Double?) {
        val hwnd = resolveHwndForWindow(windowImpl) ?: return
        if (IsWindow(hwnd) == 0 || aspectRatio == null) return
        val cur = windowImpl.getWindowSize()
        val adjustedH = max(120, (cur.width / aspectRatio).roundToInt())
        if (adjustedH != cur.height) {
            setWindowClientSize(windowImpl, Size(cur.width, adjustedH))
        }
    }

    fun setWindowClientSize(windowImpl: WindowsWindowNativeImpl, size: Size) {
        val hwnd = resolveHwndForWindow(windowImpl) ?: return
        if (IsWindow(hwnd) == 0) return
        val style = GetWindowLongPtrW(hwnd, GWL_STYLE).toUInt()
        val ex = GetWindowLongPtrW(hwnd, GWL_EXSTYLE).toUInt()
        val hasMenu = windowImpl.attachedMenuBarWidget() != null && !hasCustomTitleBarForWindow(windowImpl, hwnd)
        val winSize = memScoped {
            val r = alloc<RECT>()
            r.left = 0
            r.top = 0
            r.right = size.width.coerceAtLeast(1)
            r.bottom = size.height.coerceAtLeast(1)
            AdjustWindowRectEx(r.ptr, style, if (hasMenu) 1 else 0, ex)
            (r.right - r.left) to (r.bottom - r.top)
        }
        SetWindowPos(hwnd, null, 0, 0, winSize.first, winSize.second, (SWP_NOMOVE or SWP_NOZORDER or SWP_NOACTIVATE).toUInt())
        layoutAndSyncWindowChildren(hwnd)
    }

    fun setWindowPosition(windowImpl: WindowsWindowNativeImpl, x: Int, y: Int) {
        val hwnd = resolveHwndForWindow(windowImpl) ?: return
        if (IsWindow(hwnd) == 0) return
        SetWindowPos(hwnd, null, x, y, 0, 0, (SWP_NOSIZE or SWP_NOZORDER or SWP_NOACTIVATE).toUInt())
    }

    fun setWindowVisible(windowImpl: WindowsWindowNativeImpl, visible: Boolean) {
        if (!visible) {
            windowImpl.nativeWidget.dwStyle = windowImpl.nativeWidget.dwStyle and Win32HwndHandle.WS_VISIBLE.inv()
            Win32ControlMessageRouter.applyVisibility(windowImpl.nativeWidget, false)
            val hwnd = resolveHwndForWindow(windowImpl) ?: return
            if (IsWindow(hwnd) == 0) return
            ShowWindow(hwnd, SW_HIDE)
            return
        }
        windowImpl.nativeWidget.dwStyle = windowImpl.nativeWidget.dwStyle or Win32HwndHandle.WS_VISIBLE
        Win32ControlMessageRouter.applyVisibility(windowImpl.nativeWidget, true)
        var hwnd = resolveHwndForWindow(windowImpl)
        if (hwnd == null || IsWindow(hwnd) == 0) {
            createAndShowWin32Window(windowImpl, startHidden = false)
            return
        }
        val winRect = memScoped {
            val r = alloc<RECT>()
            if (GetWindowRect(hwnd, r.ptr) != 0) {
                intArrayOf(r.left, r.top, (r.right - r.left).coerceAtLeast(160), (r.bottom - r.top).coerceAtLeast(120))
            } else null
        }
        if (winRect != null && (winRect[0] <= -16000 || winRect[1] <= -16000)) {
            val winPos = windowImpl.getWindowPosition()
            val posX = winPos.x.roundToInt()
            val posY = winPos.y.roundToInt()
            val hasExplicitPos = windowImpl.hasExplicitWindowPosition() ||
                windowImpl.nativeWidget.attributes["hasExplicitPosition"] == true ||
                (posX != 0 || posY != 0)
            val ownerWin = windowImpl.ownerWindow as? WindowsWindowNativeImpl
            val ownerHwnd = ownerWin?.let { resolveHwndForWindow(it) }
            val origin = if (hasExplicitPos) {
                posX to posY
            } else {
                computeCenteredOrigin(ownerHwnd, winRect[2], winRect[3])
            }
            SetWindowPos(
                hwnd,
                null,
                origin.first,
                origin.second,
                0,
                0,
                (SWP_NOSIZE or SWP_NOZORDER or SWP_NOACTIVATE).toUInt()
            )
        }
        val showCmd = when {
            windowImpl.getWindowState() == WindowState.MAXIMIZED -> SW_MAXIMIZE
            windowImpl.getWindowState() == WindowState.MINIMIZED -> SW_MINIMIZE
            visualTestMode -> SW_SHOWNOACTIVATE
            else -> SW_SHOW
        }
        if (windowImpl.isFullscreen()) {
            val fsWidget = windowImpl.getFullscreenElement()?.nativeWidget as? DesktopNativeWidget
            setWindowFullscreen(windowImpl, true, fsWidget)
            ShowWindow(hwnd, if (visualTestMode) SW_SHOWNOACTIVATE else SW_SHOW)
            if (!visualTestMode) {
                SetForegroundWindow(hwnd)
            }
            UpdateWindow(hwnd)
        } else {
            ShowWindow(hwnd, showCmd)
            if (!visualTestMode && showCmd != SW_MINIMIZE) {
                SetForegroundWindow(hwnd)
            }
            layoutAndSyncWindowChildren(hwnd)
            UpdateWindow(hwnd)
        }
    }

    fun setWindowState(windowImpl: WindowsWindowNativeImpl, state: WindowState) {
        val hwnd = resolveHwndForWindow(windowImpl) ?: return
        if (IsWindow(hwnd) == 0) return
        when (state) {
            WindowState.MINIMIZED -> ShowWindow(hwnd, SW_MINIMIZE)
            WindowState.MAXIMIZED -> ShowWindow(hwnd, SW_MAXIMIZE)
            WindowState.FULLSCREEN -> setWindowFullscreen(windowImpl, true, windowImpl.getFullscreenElement()?.nativeWidget as? DesktopNativeWidget)
            WindowState.NORMAL -> {
                if (isFullscreenForWindow(hwnd, windowImpl)) {
                    setWindowFullscreen(windowImpl, false, null)
                } else {
                    ShowWindow(hwnd, SW_RESTORE)
                }
            }
        }
    }

    fun setWindowFullscreen(
        windowImpl: WindowsWindowNativeImpl,
        fullscreen: Boolean,
        fullscreenWidget: DesktopNativeWidget? = null
    ) {
        val hwnd = resolveHwndForWindow(windowImpl) ?: return
        if (IsWindow(hwnd) == 0) return
        activeFullscreenElementWidget = if (fullscreen) fullscreenWidget else null

        val key = hwnd.toLong()
        if (fullscreen) {
            if (!savedFullscreenStateByHwnd.containsKey(key)) {
                memScoped {
                    val wr = alloc<RECT>()
                    GetWindowRect(hwnd, wr.ptr)
                    val curStyle = GetWindowLongPtrW(hwnd, GWL_STYLE).toLong()
                    val curEx = GetWindowLongPtrW(hwnd, GWL_EXSTYLE).toLong()
                    val wasZoomed = IsZoomed(hwnd) != 0
                    val wp = alloc<WINDOWPLACEMENT>()
                    wp.length = sizeOf<WINDOWPLACEMENT>().toUInt()
                    val hasWp = GetWindowPlacement(hwnd, wp.ptr) != 0
                    val normRect = if (wasZoomed && hasWp) wp.rcNormalPosition else wr
                    savedFullscreenStateByHwnd[key] = SavedFullscreenState(
                        style = curStyle,
                        exStyle = curEx,
                        wasZoomed = wasZoomed,
                        normalLeft = normRect.left,
                        normalTop = normRect.top,
                        normalRight = normRect.right,
                        normalBottom = normRect.bottom,
                        wpFlags = if (hasWp) wp.flags else 0u,
                        wpShowCmd = if (wasZoomed) SW_SHOWMAXIMIZED.toUInt() else SW_SHOWNORMAL.toUInt(),
                        minPosX = if (hasWp) wp.ptMinPosition.x else -1,
                        minPosY = if (hasWp) wp.ptMinPosition.y else -1,
                        maxPosX = if (hasWp) wp.ptMaxPosition.x else -1,
                        maxPosY = if (hasWp) wp.ptMaxPosition.y else -1
                    )
                }
            }
            val monRect = memScoped {
                val mi = alloc<MONITORINFO>()
                mi.cbSize = sizeOf<MONITORINFO>().toUInt()
                val hMon = MonitorFromWindow(hwnd, MONITOR_DEFAULTTOPRIMARY.toUInt())
                if (GetMonitorInfoW(hMon, mi.ptr) != 0) {
                    intArrayOf(
                        mi.rcMonitor.left,
                        mi.rcMonitor.top,
                        mi.rcMonitor.right - mi.rcMonitor.left,
                        mi.rcMonitor.bottom - mi.rcMonitor.top
                    )
                } else {
                    intArrayOf(0, 0, GetSystemMetrics(SM_CXSCREEN), GetSystemMetrics(SM_CYSCREEN))
                }
            }
            val curStyle = GetWindowLongPtrW(hwnd, GWL_STYLE).toLong()
            val fullStyle = (curStyle and WS_OVERLAPPEDWINDOW.toLong().inv()) or WS_POPUP.toLong() or WS_CLIPCHILDREN.toLong()
            SetWindowLongPtrW(hwnd, GWL_STYLE, fullStyle)
            SetWindowPos(
                hwnd,
                HWND_TOP,
                monRect[0],
                monRect[1],
                monRect[2],
                monRect[3],
                (SWP_NOACTIVATE or SWP_FRAMECHANGED).toUInt()
            )
            Win32DwmUxThemeBridge.extendFrameIntoClientArea(hwnd, top = 0)
            layoutAndSyncWindowChildren(hwnd)
            InvalidateRect(hwnd, null, 0)
        } else {
            val savedOrigin = savedFullscreenElementOriginByHwnd.remove(key)
            if (savedOrigin != null) {
                val origParent = savedOrigin.originalParentHwnd
                if (origParent != null && IsWindow(origParent) != 0) {
                    Win32ControlMessageRouter.attachChildHwnd(savedOrigin.fsHandle, origParent)
                } else {
                    Win32ControlMessageRouter.detachChildHwnd(savedOrigin.fsHandle)
                }
                savedOrigin.fsImpl.setBounds(savedOrigin.originalBounds)
            }
            val saved = savedFullscreenStateByHwnd.remove(key)
            if (saved != null) {
                SetWindowLongPtrW(hwnd, GWL_STYLE, saved.style)
                SetWindowLongPtrW(hwnd, GWL_EXSTYLE, saved.exStyle)
                memScoped {
                    val wp = alloc<WINDOWPLACEMENT>()
                    wp.length = sizeOf<WINDOWPLACEMENT>().toUInt()
                    wp.flags = saved.wpFlags
                    wp.showCmd = if (saved.wasZoomed) SW_SHOWMAXIMIZED.toUInt() else SW_SHOWNORMAL.toUInt()
                    wp.ptMinPosition.x = saved.minPosX
                    wp.ptMinPosition.y = saved.minPosY
                    wp.ptMaxPosition.x = saved.maxPosX
                    wp.ptMaxPosition.y = saved.maxPosY
                    wp.rcNormalPosition.left = saved.normalLeft
                    wp.rcNormalPosition.top = saved.normalTop
                    wp.rcNormalPosition.right = saved.normalRight
                    wp.rcNormalPosition.bottom = saved.normalBottom
                    SetWindowPlacement(hwnd, wp.ptr)
                }
                if (saved.wasZoomed) {
                    SetWindowPos(
                        hwnd,
                        null,
                        0,
                        0,
                        0,
                        0,
                        (SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED).toUInt()
                    )
                    ShowWindow(hwnd, SW_SHOWMAXIMIZED)
                } else {
                    val nw = (saved.normalRight - saved.normalLeft).coerceAtLeast(160)
                    val nh = (saved.normalBottom - saved.normalTop).coerceAtLeast(120)
                    SetWindowPos(
                        hwnd,
                        null,
                        saved.normalLeft,
                        saved.normalTop,
                        nw,
                        nh,
                        (SWP_NOZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED).toUInt()
                    )
                }
            } else {
                val (style, ex) = computeDesiredWindowStyle(windowImpl)
                SetWindowLongPtrW(hwnd, GWL_STYLE, style)
                SetWindowLongPtrW(hwnd, GWL_EXSTYLE, ex)
                SetWindowPos(hwnd, null, 0, 0, 0, 0, (SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED).toUInt())
            }
            applyWindowsImmersiveTitleBarTheme(hwnd, isEffectivelyDarkTheme())
            layoutAndSyncWindowChildren(hwnd)
            RedrawWindow(
                hwnd,
                null,
                null,
                (RDW_INVALIDATE or RDW_UPDATENOW or RDW_ALLCHILDREN).toUInt()
            )
            windowImpl.notifyHostFullscreenExited()
        }
    }

    fun getPrimaryMonitorBounds(): Rect? = memScoped {
        val mi = alloc<MONITORINFO>()
        mi.cbSize = sizeOf<MONITORINFO>().toUInt()
        val refHwnd = activeHwnd ?: Win32ControlMessageRouter.getOrCreateParkingHwnd()
        val hMon = MonitorFromWindow(refHwnd, MONITOR_DEFAULTTOPRIMARY.toUInt())
        if (GetMonitorInfoW(hMon, mi.ptr) != 0) {
            val r = mi.rcWork
            Rect(r.left, r.top, (r.right - r.left).coerceAtLeast(1), (r.bottom - r.top).coerceAtLeast(1))
        } else {
            Rect(0, 0, GetSystemMetrics(SM_CXSCREEN).coerceAtLeast(800), GetSystemMetrics(SM_CYSCREEN).coerceAtLeast(600))
        }
    }

    fun isWindowRectOnScreen(rect: Rect): Boolean = memScoped {
        val primary = getPrimaryMonitorBounds() ?: return@memScoped true
        val rRight = rect.x + rect.width
        val rBottom = rect.y + rect.height
        val ix0 = max(rect.x, primary.x)
        val iy0 = max(rect.y, primary.y)
        val ix1 = min(rRight, primary.x + primary.width)
        val iy1 = min(rBottom, primary.y + primary.height)
        (ix1 - ix0) >= 64 && (iy1 - iy0) >= 48
    }

    fun computeDesiredWindowStyle(windowImpl: WindowsWindowNativeImpl): Pair<Long, Long> {
        val hwnd = resolveHwndForWindow(windowImpl)
        if (hwnd != null && isFullscreenForWindow(hwnd, windowImpl)) {
            return (WS_POPUP.toLong() or WS_CLIPCHILDREN.toLong()) to 0L
        }
        val baseStyle = windowImpl.computeDesiredWindowStyle(windowImpl.isUndecorated())
        val baseEx = windowImpl.nativeWidget.dwExStyle
        return (baseStyle or WS_CLIPCHILDREN.toLong()) to baseEx
    }

    fun rgb(r: Int, g: Int, b: Int): UInt = Win32DwmUxThemeBridge.colorRefRgb(r, g, b)

    fun hasOtherVisibleNonDisposedWindow(): Boolean =
        dialogHwnds.entries.any { (dlg, h) -> !dlg.isDisposed && IsWindow(h) != 0 && IsWindowVisible(h) != 0 } ||
            panelHwnds.entries.any { (panel, h) -> !panel.isDisposed && IsWindow(h) != 0 && IsWindowVisible(h) != 0 } ||
            secondaryWindowHwnds.entries.any { (win, h) -> !win.isDisposed && IsWindow(h) != 0 && IsWindowVisible(h) != 0 }

    fun resolveMinWindowTrackSize(winImpl: WindowsWindowNativeImpl): Pair<Int, Int> {
        val min = winImpl.getMinWindowSize()
        return (min?.width ?: 200) to (min?.height ?: 140)
    }

    fun resolveSelectPopupClientAnchor(bounds: Rect, clickX: Int, clickY: Int): Pair<Int, Int> =
        bounds.x to (bounds.y + bounds.height)

    fun hitTestUndecoratedRect(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        screenX: Int,
        screenY: Int,
        resizable: Boolean,
        isFullscreenOrMaximized: Boolean,
        borderPx: Int = 6,
        topBorderPx: Int = borderPx,
        rightBorderPx: Int = borderPx
    ): Int {
        if (!resizable || isFullscreenOrMaximized) return HTCLIENT
        val onL = screenX in left until (left + borderPx)
        val onR = screenX in (right - rightBorderPx)..right
        val onT = screenY in top until (top + topBorderPx)
        val onB = screenY in (bottom - borderPx)..bottom
        return when {
            onT && onL -> HTTOPLEFT
            onT && onR -> HTTOPRIGHT
            onB && onL -> HTBOTTOMLEFT
            onB && onR -> HTBOTTOMRIGHT
            onL -> HTLEFT
            onR -> HTRIGHT
            onT -> HTTOP
            onB -> HTBOTTOM
            else -> HTCLIENT
        }
    }

    internal fun findActiveOverlayTitleBarWidget(root: DesktopNativeWidget): DesktopNativeWidget? {
        if (root.disposed || !root.visible) return null
        if (root is Win32HwndHandle && (!root.effectiveVisible || root.effectiveOpacity <= 0.001f)) {
            return null
        }
        val role = root.attributes["data-overlay-role"]?.toString()?.trim()?.lowercase()
        val active = root.attributes["data-titlebar-active"]?.toString()?.trim()?.lowercase()
        if (role == "window-title-bar" && active == "true") {
            return root
        }
        for (child in root.children) {
            findActiveOverlayTitleBarWidget(child)?.let { return it }
        }
        return null
    }

    private fun resolveWidgetScreenRect(
        hostHwnd: HWND,
        rootWidget: DesktopNativeWidget,
        target: DesktopNativeWidget
    ): IntArray? = memScoped {
        val childHwnd = (target as? Win32HwndHandle)?.hwnd
        if (childHwnd != null && IsWindow(childHwnd) != 0 && IsChild(hostHwnd, childHwnd) != 0) {
            val r = alloc<RECT>()
            if (GetWindowRect(childHwnd, r.ptr) != 0 && r.right > r.left && r.bottom > r.top) {
                return@memScoped intArrayOf(r.left, r.top, r.right, r.bottom)
            }
        }
        val b = target.bounds
        if (b.width <= 0 || b.height <= 0) return@memScoped null
        var accX = 0
        var accY = 0
        var cur: DesktopNativeWidget? = target
        var reachedRoot = false
        while (cur != null) {
            if (cur === rootWidget) {
                reachedRoot = true
                break
            }
            accX += cur.bounds.x
            accY += cur.bounds.y
            cur = cur.parentWidget
        }
        if (!reachedRoot) return@memScoped null
        val origin = alloc<POINT>()
        origin.x = 0
        origin.y = 0
        ClientToScreen(hostHwnd, origin.ptr)
        val left = origin.x + accX
        val top = origin.y + accY
        intArrayOf(left, top, left + b.width, top + b.height)
    }

    private fun isInteractiveOverlayAccessory(widget: DesktopNativeWidget, titleBarWidget: DesktopNativeWidget): Boolean {
        if (widget === titleBarWidget) return false
        val impl = widget.attributes["nativeImpl"] ?: (widget as? Win32HwndHandle)?.ownerImpl
        if (impl is DesktopTextNativeImpl<*>) return false
        if (impl is DesktopButtonNativeImpl<*> ||
            impl is DesktopTextInputNativeImpl<*> ||
            impl is DesktopSelectNativeImpl<*> ||
            impl is DesktopCheckboxNativeImpl<*> ||
            impl is DesktopDropdownNativeImpl<*>
        ) {
            return true
        }
        val cursor = widget.attributes["cursor"]?.toString()?.trim()?.lowercase()
        if (cursor == "pointer") return true
        return false
    }

    private fun findOverlayTitleBarControlAt(
        hostHwnd: HWND,
        rootWidget: DesktopNativeWidget,
        titleBarWidget: DesktopNativeWidget,
        screenX: Int,
        screenY: Int
    ): Pair<String?, DesktopNativeWidget?> {
        fun visit(node: DesktopNativeWidget): Pair<String?, DesktopNativeWidget?>? {
            if (node.disposed || !node.visible) return null
            if (node is Win32HwndHandle && (!node.effectiveVisible || node.effectiveOpacity <= 0.001f || node.pointerEventsNone)) {
                return null
            }
            for (child in node.children.asReversed()) {
                visit(child)?.let { return it }
            }
            if (node === titleBarWidget) return null
            val rect = resolveWidgetScreenRect(hostHwnd, rootWidget, node) ?: return null
            if (screenX !in rect[0] until rect[2] || screenY !in rect[1] until rect[3]) {
                return null
            }
            val controlAttr = node.attributes["data-window-control"]?.toString()?.trim()?.lowercase()
            if (controlAttr == "close" || controlAttr == "minimize" || controlAttr == "maximize") {
                return controlAttr to node
            }
            if (isInteractiveOverlayAccessory(node, titleBarWidget)) {
                return "accessory" to node
            }
            return null
        }
        return visit(titleBarWidget) ?: (null to null)
    }

    private fun findOverlayWindowControl(
        root: DesktopNativeWidget,
        controlRole: String
    ): DesktopNativeWidget? {
        if (root.disposed) return null
        val ctrl = root.attributes["data-window-control"]?.toString()?.trim()?.lowercase()
        if (ctrl == controlRole) return root
        for (child in root.children) {
            findOverlayWindowControl(child, controlRole)?.let { return it }
        }
        return null
    }

    private fun clearUndecoratedNcMaxHover(hostHwnd: HWND) {
        val prev = undecoratedNcMaxHoveredByHwnd.remove(hostHwnd.toLong()) ?: return
        prev.ownerImpl?.setHoveredState(false)
        prev.hwnd?.let { if (IsWindow(it) != 0) InvalidateRect(it, null, 0) }
    }

    internal fun hitTestUndecoratedWindowWithOverlayTitleBar(
        hwnd: HWND,
        winImpl: WindowsWindowNativeImpl,
        screenX: Int,
        screenY: Int
    ): Long {
        val resizable = (winImpl.nativeWidget.attributes["resizable"] as? Boolean) != false
        val isMaximized = IsZoomed(hwnd) != 0
        val wr = memScoped {
            val r = alloc<RECT>()
            GetWindowRect(hwnd, r.ptr)
            intArrayOf(r.left, r.top, r.right, r.bottom)
        }
        val activeTitleBar = findActiveOverlayTitleBarWidget(winImpl.nativeWidget)
        val tbRect = activeTitleBar?.let { resolveWidgetScreenRect(hwnd, winImpl.nativeWidget, it) }
        val inTitleBar = tbRect != null &&
            screenX in tbRect[0] until tbRect[2] &&
            screenY in tbRect[1] until tbRect[3]
        val controlHit = if (inTitleBar && activeTitleBar != null) {
            findOverlayTitleBarControlAt(hwnd, winImpl.nativeWidget, activeTitleBar, screenX, screenY).first
        } else {
            null
        }
        val overControl = controlHit != null
        val borderHit = hitTestUndecoratedRect(
            left = wr[0],
            top = wr[1],
            right = wr[2],
            bottom = wr[3],
            screenX = screenX,
            screenY = screenY,
            resizable = resizable,
            isFullscreenOrMaximized = isMaximized,
            borderPx = 6,
            topBorderPx = if (overControl) 2 else 6,
            rightBorderPx = if (overControl) 2 else 6
        )
        if (borderHit != HTCLIENT) {
            return borderHit.toLong()
        }
        if (inTitleBar) {
            return when (controlHit) {
                "maximize" -> HTMAXBUTTON.toLong()
                "close", "minimize", "accessory" -> HTCLIENT.toLong()
                else -> HTCAPTION.toLong()
            }
        }
        return HTCLIENT.toLong()
    }

    internal fun isInteractiveTitleBarChildWidget(widget: DesktopNativeWidget?): Boolean {
        if (widget == null || widget.disposed) return false
        if (widget is Win32HwndHandle && (!widget.effectiveVisible || widget.effectiveOpacity <= 0.001f || widget.pointerEventsNone)) {
            return false
        }
        val selfDragAttr = widget.attributes["titlebarDragRegion"] == true ||
            widget.attributes["data-window-drag"]?.toString()?.equals("true", ignoreCase = true) == true ||
            widget.attributes["appRegion"]?.toString()?.equals("drag", ignoreCase = true) == true
        val selfTokens = widget.cachedAllClassTokens
        val selfTag = (widget.attributes["tagName"] as? String)?.trim()?.lowercase()
        val selfImpl = (widget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>)
            ?: (widget as? Win32HwndHandle)?.ownerImpl
        val selfIsExplicitSpacer = selfDragAttr ||
            selfTag == "spacer" ||
            selfTag == "genericui:spacer" ||
            "spacer" in selfTokens ||
            "gui-tab-strip-spacer" in selfTokens ||
            "titlebar-spacer" in selfTokens ||
            "titlebar-drag-spacer" in selfTokens
        if (selfIsExplicitSpacer && selfImpl?.hasClickListener() != true) {
            return false
        }
        if (isTitleBarStripTokens(widget) && selfImpl?.hasClickListener() != true) {
            return false
        }

        var cur: DesktopNativeWidget? = widget
        while (cur != null) {
            if (cur !== widget && isTitleBarStripTokens(cur)) {
                return false
            }
            val impl = (cur.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>)
                ?: (cur as? Win32HwndHandle)?.ownerImpl
            val tokens = cur.cachedAllClassTokens
            val cursor = cur.attributes["cursor"]?.toString()?.trim()?.lowercase()
            val role = cur.attributes["role"]?.toString()?.trim()?.lowercase()
            val isInteractive = impl is DesktopButtonNativeImpl<*> ||
                impl is DesktopTextInputNativeImpl<*> ||
                impl is DesktopSelectNativeImpl<*> ||
                impl is DesktopCheckboxNativeImpl<*> ||
                impl is DesktopDropdownNativeImpl<*> ||
                impl?.hasClickListener() == true ||
                cursor == "pointer" ||
                "gui-tab-item" in tokens ||
                "genericui-tabbed-chip" in tokens ||
                "nav-link" in tokens ||
                role == "tab" ||
                cur.attributes.containsKey("interactiveSurfaceRole")
            if (isInteractive) {
                return true
            }
            if (cur.attributes["mountedInTitleBar"] == true) {
                break
            }
            cur = cur.parentWidget
        }
        return false
    }

    internal fun childHwndNcHitTest(childHwnd: HWND, screenX: Int, screenY: Int): Long? {
        val rootHwnd = GetAncestor(childHwnd, GA_ROOT.toUInt()) ?: return null
        if (rootHwnd == childHwnd) return null
        val winImpl = resolveWindowForHwnd(rootHwnd) ?: return null
        if (isFullscreenForWindow(rootHwnd, winImpl)) return null

        if (winImpl.isUndecorated()) {
            findActiveOverlayTitleBarWidget(winImpl.nativeWidget) ?: return null
            val hostHit = hitTestUndecoratedWindowWithOverlayTitleBar(rootHwnd, winImpl, screenX, screenY).toInt()
            return if (hostHit != HTCLIENT) HTTRANSPARENT.toLong() else null
        }

        if (!hasCustomTitleBarForWindow(winImpl, rootHwnd)) return null
        val pt = memScoped {
            val p = alloc<POINT>()
            p.x = screenX
            p.y = screenY
            ScreenToClient(rootHwnd, p.ptr)
            p.x to p.y
        }
        val titleBarH = getMainWindowTitleBarHeight(winImpl)
        if (pt.second !in 0 until titleBarH) return null

        val entry = Win32ControlMessageRouter.resolveEntry(childHwnd)
        val interactive = isInteractiveTitleBarChildWidget(entry)
        val inCaptionControl = Win32NativeTitleBarRenderer.isPointInCaptionButtonOrIcon(rootHwnd, pt.first, pt.second)
        val isMaximized = IsZoomed(rootHwnd) != 0
        if (!isMaximized) {
            val resizable = (winImpl.nativeWidget.attributes["resizable"] as? Boolean) != false
            if (resizable) {
                val wr = memScoped {
                    val r = alloc<RECT>()
                    GetWindowRect(rootHwnd, r.ptr)
                    intArrayOf(r.left, r.top, r.right, r.bottom)
                }
                val topBorder = if (interactive || inCaptionControl) 2 else 6
                val rightBorder = if (interactive || inCaptionControl) 2 else 6
                val onBorder = screenX in wr[0] until (wr[0] + 6) ||
                    screenX in (wr[2] - rightBorder)..wr[2] ||
                    screenY in wr[1] until (wr[1] + topBorder)
                if (onBorder) {
                    return HTTRANSPARENT.toLong()
                }
            }
        }
        return if (interactive) null else HTTRANSPARENT.toLong()
    }

    private fun resizeHitTestToCssCursor(hitCode: Int): CssCursor? = when (hitCode) {
        HTLEFT -> CssCursor.W_RESIZE
        HTRIGHT -> CssCursor.E_RESIZE
        HTTOP -> CssCursor.N_RESIZE
        HTBOTTOM -> CssCursor.S_RESIZE
        HTTOPLEFT -> CssCursor.NW_RESIZE
        HTTOPRIGHT -> CssCursor.NE_RESIZE
        HTBOTTOMLEFT -> CssCursor.SW_RESIZE
        HTBOTTOMRIGHT -> CssCursor.SE_RESIZE
        else -> null
    }

    fun resolveResizeCursorForNcHitTest(hitCode: Int): CPointer<UShortVar>? = when (hitCode) {
        HTLEFT, HTRIGHT -> IDC_SIZEWE
        HTTOP, HTBOTTOM -> IDC_SIZENS
        HTTOPLEFT, HTBOTTOMRIGHT -> IDC_SIZENWSE
        HTTOPRIGHT, HTBOTTOMLEFT -> IDC_SIZENESW
        else -> null
    }

    fun adjustRectForMinSize(
        rect: RECT,
        edge: Int,
        minTrackW: Int,
        minTrackH: Int
    ) {
        if ((rect.right - rect.left) < minTrackW) {
            if (edge == WMSZ_LEFT || edge == WMSZ_TOPLEFT || edge == WMSZ_BOTTOMLEFT) {
                rect.left = rect.right - minTrackW
            } else {
                rect.right = rect.left + minTrackW
            }
        }
        if ((rect.bottom - rect.top) < minTrackH) {
            if (edge == WMSZ_TOP || edge == WMSZ_TOPLEFT || edge == WMSZ_TOPRIGHT) {
                rect.top = rect.bottom - minTrackH
            } else {
                rect.bottom = rect.top + minTrackH
            }
        }
    }

    fun adjustRectForAspectRatio(
        rect: RECT,
        edge: Int,
        frameW: Int,
        frameH: Int,
        currentClientW: Int,
        currentClientH: Int,
        minTrackW: Int,
        minTrackH: Int,
        aspectRatio: Double
    ) {
        if (!aspectRatio.isFinite() || aspectRatio <= 0.0) {
            adjustRectForMinSize(rect, edge, minTrackW, minTrackH)
            return
        }
        val rawW = (rect.right - rect.left - frameW).coerceAtLeast(1)
        val rawH = (rect.bottom - rect.top - frameH).coerceAtLeast(1)
        val minClientW = (minTrackW - frameW).coerceAtLeast(1)
        val minClientH = (minTrackH - frameH).coerceAtLeast(1)

        var targetClientW: Int
        var targetClientH: Int
        when (edge) {
            WMSZ_LEFT, WMSZ_RIGHT -> {
                targetClientW = rawW.coerceAtLeast(minClientW)
                targetClientH = (targetClientW.toDouble() / aspectRatio).roundToInt().coerceAtLeast(minClientH)
                if (targetClientH * aspectRatio > targetClientW) {
                    targetClientW = (targetClientH * aspectRatio).roundToInt().coerceAtLeast(minClientW)
                }
            }
            WMSZ_TOP, WMSZ_BOTTOM -> {
                targetClientH = rawH.coerceAtLeast(minClientH)
                targetClientW = (targetClientH.toDouble() * aspectRatio).roundToInt().coerceAtLeast(minClientW)
                if (targetClientW.toDouble() / targetClientH < aspectRatio) {
                    targetClientH = (targetClientW / aspectRatio).roundToInt().coerceAtLeast(minClientH)
                }
            }
            else -> {
                val dw = abs(rawW - currentClientW)
                val dh = abs(rawH - currentClientH)
                if (dw >= dh) {
                    targetClientW = rawW.coerceAtLeast(minClientW)
                    targetClientH = (targetClientW / aspectRatio).roundToInt().coerceAtLeast(minClientH)
                } else {
                    targetClientH = rawH.coerceAtLeast(minClientH)
                    targetClientW = (targetClientH * aspectRatio).roundToInt().coerceAtLeast(minClientW)
                }
                if (targetClientW < minClientW) {
                    targetClientW = minClientW
                    targetClientH = (targetClientW / aspectRatio).roundToInt().coerceAtLeast(minClientH)
                }
                if (targetClientH < minClientH) {
                    targetClientH = minClientH
                    targetClientW = (targetClientH * aspectRatio).roundToInt().coerceAtLeast(minClientW)
                }
            }
        }

        val totalW = targetClientW + frameW
        val totalH = targetClientH + frameH
        if (edge == WMSZ_LEFT || edge == WMSZ_TOPLEFT || edge == WMSZ_BOTTOMLEFT) {
            rect.left = rect.right - totalW
        } else {
            rect.right = rect.left + totalW
        }
        if (edge == WMSZ_TOP || edge == WMSZ_TOPLEFT || edge == WMSZ_TOPRIGHT) {
            rect.top = rect.bottom - totalH
        } else {
            rect.bottom = rect.top + totalH
        }
    }

    /**
     * Synchronizes all retained child `HWND`s owned by [hwnd] (`WindowsWindowNativeImpl`,
     * `DesktopDialogWindowNativeImpl`, `DesktopDockPanelNativeImpl`, or `DesktopPopoverNativeImpl`)
     * via `SetParent`, `DesktopContainerLayoutController`, and batched `BeginDeferWindowPos`.
     */
    fun layoutAndSyncWindowChildren(hwnd: HWND) {
        if (IsWindow(hwnd) == 0) return
        val clientRect = memScoped {
            val r = alloc<RECT>()
            GetClientRect(hwnd, r.ptr)
            (r.right - r.left).coerceAtLeast(0) to (r.bottom - r.top).coerceAtLeast(0)
        }
        val clientW = clientRect.first
        val clientH = clientRect.second
        if (clientW <= 0 || clientH <= 0) return

        Win32ControlMessageRouter.batchingHostLayoutDepth++
        try {
            val dialog = dialogHwnds.entries.firstOrNull { it.value == hwnd }?.key
            if (dialog != null) {
                layoutDialogChildren(hwnd, dialog, clientW, clientH)
                return
            }

            val panel = panelHwnds.entries.firstOrNull { it.value == hwnd }?.key
            if (panel != null) {
                val headerH = getPanelWindowTitleBarHeight(panel)
                layoutContainerChildrenIntoHostHwnd(hwnd, panel, 0, headerH, clientW, (clientH - headerH).coerceAtLeast(1))
                return
            }

            val popover = popoverHwnds.entries.firstOrNull { it.value == hwnd }?.key
            if (popover != null) {
                layoutContainerChildrenIntoHostHwnd(hwnd, popover, 0, 0, clientW, clientH)
                return
            }

            val winImpl = resolveWindowForHwnd(hwnd) ?: return
            val topTitleH = getMainWindowTitleBarHeight(winImpl)

            val fsWidget = if (isFullscreenForWindow(hwnd, winImpl)) {
                activeFullscreenElementWidget ?: (winImpl.getFullscreenElement()?.nativeWidget as? DesktopNativeWidget)
            } else {
                null
            }
            if (fsWidget != null) {
                syncFullscreenElementChildren(hwnd, winImpl, fsWidget, clientW, clientH)
                return
            }

            // Mount optional TitleBar embedded control child HWNDs inside [0..topTitleH)
            if (topTitleH > 0) {
                syncTitleBarComponentChildHwnds(hwnd, winImpl, clientW, topTitleH)
            } else {
                winImpl.getTitleBarInstructions()?.let { tb ->
                    for (comp in tb.leadingComponents + tb.centerComponents + tb.trailingComponents) {
                        val ch = comp.nativeImpl?.nativeWidget as? Win32HwndHandle ?: continue
                        Win32ControlMessageRouter.detachChildHwnd(ch)
                    }
                }
            }

            val contentTop = topTitleH.coerceAtLeast(0)
            val contentH = (clientH - contentTop).coerceAtLeast(1)
            layoutContainerChildrenIntoHostHwnd(hwnd, winImpl, 0, contentTop, clientW, contentH)
            syncInteractiveSurfaceRectsForHost(hwnd, winImpl)
        } finally {
            Win32ControlMessageRouter.batchingHostLayoutDepth--
        }
    }

    private fun obtainHostSolidBrush(colorRef: UInt): HBRUSH? {
        cachedHostSolidBrushes[colorRef]?.let { return it }
        return CreateSolidBrush(colorRef)?.also { cachedHostSolidBrushes[colorRef] = it }
    }

    fun paintWindow(hwnd: HWND) {
        if (IsWindow(hwnd) == 0) return
        val startUs = nowMicros()
        memScoped {
            val ps = alloc<PAINTSTRUCT>()
            val hdc = BeginPaint(hwnd, ps.ptr)
            try {
                if (hdc != null &&
                    ps.rcPaint.right > ps.rcPaint.left &&
                    ps.rcPaint.bottom > ps.rcPaint.top
                ) {
                    val rc = alloc<RECT>()
                    GetClientRect(hwnd, rc.ptr)
                    val w = (rc.right - rc.left).coerceAtLeast(0)
                    val h = (rc.bottom - rc.top).coerceAtLeast(0)
                    if (w > 0 && h > 0) {
                        val isDark = isEffectivelyDarkTheme()
                        val winImpl = resolveWindowForHwnd(hwnd)
                        val customBg = winImpl?.getEffectiveBackgroundColor()
                        val bgRef = when {
                            customBg != null -> Win32DwmUxThemeBridge.colorRefRgb(customBg.r, customBg.g, customBg.b)
                            isDark -> Win32DwmUxThemeBridge.colorRefRgb(24, 24, 28)
                            else -> Win32DwmUxThemeBridge.colorRefRgb(245, 246, 248)
                        }
                        val fillLeft = ps.rcPaint.left.coerceIn(0, w)
                        val fillTop = ps.rcPaint.top.coerceIn(0, h)
                        val fillRight = ps.rcPaint.right.coerceIn(fillLeft, w)
                        val fillBottom = ps.rcPaint.bottom.coerceIn(fillTop, h)
                        if (fillRight > fillLeft && fillBottom > fillTop) {
                            val fillRect = alloc<RECT>()
                            fillRect.left = fillLeft
                            fillRect.top = fillTop
                            fillRect.right = fillRight
                            fillRect.bottom = fillBottom
                            val brush = obtainHostSolidBrush(bgRef)
                            if (brush != null) {
                                FillRect(hdc, fillRect.ptr, brush)
                            }
                        }

                        if (winImpl != null && hasCustomTitleBarForWindow(winImpl, hwnd)) {
                            val tbH = getMainWindowTitleBarHeight(winImpl)
                            if (ps.rcPaint.top < tbH) {
                                val titleBar = winImpl.getTitleBarInstructions()
                                Win32NativeTitleBarRenderer.renderWindowTitleBar(
                                    hwnd = hwnd,
                                    targetHdc = hdc,
                                    left = 0,
                                    top = 0,
                                    right = w,
                                    titleBarHeight = tbH,
                                    windowTitle = resolveEffectiveWindowTitle(winImpl),
                                    titleBar = toChromeOnlyTitleBar(titleBar),
                                    isToolWindow = winImpl.isToolModalWindow,
                                    kind = winImpl.titleBarKind,
                                    appIcon = resolveTitleBarIcon(hwnd),
                                    fontSmall = Win32ControlMessageRouter.obtainUiFont("Segoe UI", 12, 400),
                                    fontCaption = Win32ControlMessageRouter.obtainUiFont("Segoe UI", 13, 400),
                                    hoveredWidget = null,
                                    pressedWidget = null,
                                    isRectHoveredFn = { _, _, _, _ -> false },
                                    registerHitRegionFn = { _, _, _, _, _, _, _ -> },
                                    openSelectPopupFn = { _, _, _ -> },
                                    onCloseClicked = { SendMessageW(hwnd, WM_CLOSE.toUInt(), 0uL, 0L) }
                                )
                            }
                        } else if (isPanelToolWindow(hwnd)) {
                            val panel = panelHwnds.entries.firstOrNull { it.value == hwnd }?.key
                            if (panel != null) {
                                val tbH = getPanelWindowTitleBarHeight(panel)
                                if (ps.rcPaint.top < tbH) {
                                    Win32NativeTitleBarRenderer.renderWindowTitleBar(
                                        hwnd = hwnd,
                                        targetHdc = hdc,
                                        left = 0,
                                        top = 0,
                                        right = w,
                                        titleBarHeight = tbH,
                                        windowTitle = panel.getTitle(),
                                        titleBar = toChromeOnlyTitleBar(panel.nativeWidget.attributes["titleBar"] as? TitleBar),
                                        isToolWindow = true,
                                        kind = Win32TitleBarKind.TOOL_WINDOW,
                                        appIcon = resolveTitleBarIcon(hwnd),
                                        fontSmall = Win32ControlMessageRouter.obtainUiFont("Segoe UI", 11, 400),
                                        fontCaption = Win32ControlMessageRouter.obtainUiFont("Segoe UI", 12, 400),
                                        hoveredWidget = null,
                                        pressedWidget = null,
                                        isRectHoveredFn = { _, _, _, _ -> false },
                                        registerHitRegionFn = { _, _, _, _, _, _, _ -> },
                                        openSelectPopupFn = { _, _, _ -> },
                                        onCloseClicked = { closeDockPanelWindow(panel) }
                                    )
                                }
                            }
                        }
                    }
                }
            } finally {
                EndPaint(hwnd, ps.ptr)
            }
        }
        recordPaintMetrics(nowMicros() - startUs, isVideoStageOnly = false)
    }

    /**
     * Runs the main blocking Win32 message loop (`GetMessageW` -> `TranslateMessage` -> `DispatchMessageW`)
     * until `WM_QUIT` or all host windows close.
     */
    fun runMessageLoop() {
        Win32WinMain.ensureWindowsControlsInitialized()
        ensureOverlayInvalidationListenerInstalled()
        ensureVBlankWidgetsListenerInstalled()
        WindowsUiThreadDispatcherNativeImpl.attachCurrentThreadAsUiThread()
        val tid = GetCurrentThreadId()
        messageLoopThreadId = tid
        WindowsHeadlessTrayLoop.attachActiveLoopThread(tid)
        syncHostVBlankDemand()
        try {
            WindowsUiThreadDispatcherNativeImpl.drainPendingUiQueue()
            flushPendingNonOverlayLayoutSyncIfNeeded()
            if (WindowsSystemTrayNativeImpl.hasActiveTray() && !WindowsSystemTrayNativeImpl.isNativeTrayInstalled()) {
                WindowsSystemTrayNativeImpl.syncActiveTrayIcon()
            }
            memScoped {
                val msg = alloc<MSG>()
                while (true) {
                    val res = GetMessageW(msg.ptr, null, 0u, 0u)
                    if (res <= 0) break
                    if (msg.hwnd == null) {
                        if (msg.message == WM_APP_UI_DISPATCH) {
                            WindowsUiThreadDispatcherNativeImpl.drainPendingUiQueue()
                            continue
                        }
                        if (msg.message == WM_APP_LAYOUT_SYNC) {
                            flushPendingNonOverlayLayoutSyncIfNeeded()
                            continue
                        }
                    }
                    TranslateMessage(msg.ptr)
                    DispatchMessageW(msg.ptr)
                }
            }
        } finally {
            WindowsHeadlessTrayLoop.detachActiveLoopThread()
            if (messageLoopThreadId == tid) {
                messageLoopThreadId = 0u
            }
        }
    }

    private fun ensureVBlankWidgetsListenerInstalled() {
        if (vblankWidgetsListenerInstalled) return
        vblankWidgetsListenerInstalled = true
        DesktopNativeWidget.onVBlankWidgetsChanged = {
            syncHostVBlankDemand()
        }
        DesktopNativeWidget.onSurfaceFrameReadyGlobal = { _ ->
            coalescePostSurfaceFrameWake()
        }
    }

    private fun resolveHostTargetHwnd(): HWND? = resolveTimerHostHwnd()

    internal fun syncHostVBlankDemand() {
        val hasActive = DesktopNativeWidget.activeVBlankWidgetsSnapshot().isNotEmpty()
        val preferredRateHz = if (hasActive) DesktopNativeWidget.resolvePreferredVBlankRateHz() else null
        val winScheduler = Animator.activeScheduler as? WindowsAnimationNativeImpl
        if (winScheduler != null) {
            val prevId = hostFallbackWatcherId.value
            if (prevId > 0 && hostFallbackWatcherId.compareAndSet(prevId, -1)) {
                Animator.unwatchVBlank(prevId)
            }
            winScheduler.setHostSurfaceDemand(hasActive, preferredRateHz)
        } else if (hasActive) {
            if (hostFallbackWatcherId.value <= 0) {
                val id = Animator.watchVBlank { _, _, _ ->
                    dispatchActiveVBlankWidgets()
                }
                if (!hostFallbackWatcherId.compareAndSet(-1, id) && id > 0) {
                    Animator.unwatchVBlank(id)
                }
            }
        } else {
            val prevId = hostFallbackWatcherId.value
            if (prevId > 0 && hostFallbackWatcherId.compareAndSet(prevId, -1)) {
                Animator.unwatchVBlank(prevId)
            }
        }
    }

    private fun coalescePostSurfaceFrameWake() {
        if (!surfaceFrameWakePosted.compareAndSet(0, 1)) return
        val targetHwnd = resolveHostTargetHwnd()
        if (targetHwnd != null) {
            if (PostMessageW(targetHwnd, WM_APP_VBLANK, 0uL, 0L) == 0) {
                surfaceFrameWakePosted.value = 0
            }
        } else {
            GenericUiThreading.runOnUi {
                surfaceFrameWakePosted.value = 0
                dispatchActiveVBlankWidgets()
            }
        }
    }

    private fun dispatchActiveVBlankWidgets() {
        val nowMs = (nowMicros() / 1000L).toDouble()
        for (w in DesktopNativeWidget.activeVBlankWidgetsSnapshot()) {
            if (!w.disposed) {
                w.vblankDispatch?.invoke(nowMs)
            }
        }
        Win32InteractiveSurfaceHwnd.stepVBlankSurfaces(nowMs)
    }

    /**
     * Pumps pending Win32 messages for up to [durationMs] milliseconds cleanly during tests and animations.
     */
    fun pumpVisualWindowEvents(durationMs: Long = 32L) {
        WindowsUiThreadDispatcherNativeImpl.attachCurrentThreadAsUiThreadIfUnset()
        WindowsUiThreadDispatcherNativeImpl.drainPendingUiQueue()
        flushPendingNonOverlayLayoutSyncIfNeeded()
        val startUs = nowMicros()
        val endUs = startUs + durationMs.coerceAtLeast(0L) * 1000L
        val intervalUs = (1_000_000.0 / Animator.getRefreshRateHz().coerceAtLeast(1.0)).toLong().coerceAtLeast(1_000L)
        var lastVBlankUs = startUs - intervalUs
        memScoped {
            val msg = alloc<MSG>()
            while (true) {
                while (PeekMessageW(msg.ptr, null, 0u, 0u, PM_REMOVE.toUInt()) != 0) {
                    if (msg.message == 0x0012u /* WM_QUIT */) return
                    if (msg.hwnd == null) {
                        if (msg.message == WM_APP_UI_DISPATCH) {
                            WindowsUiThreadDispatcherNativeImpl.drainPendingUiQueue()
                            continue
                        }
                        if (msg.message == WM_APP_LAYOUT_SYNC) {
                            flushPendingNonOverlayLayoutSyncIfNeeded()
                            continue
                        }
                    }
                    TranslateMessage(msg.ptr)
                    DispatchMessageW(msg.ptr)
                    if (nowMicros() >= endUs) break
                }
                WindowsUiThreadDispatcherNativeImpl.drainPendingUiQueue()
                flushPendingNonOverlayLayoutSyncIfNeeded()
                val nowUs = nowMicros()
                if (nowUs - lastVBlankUs >= intervalUs) {
                    lastVBlankUs = nowUs
                    dispatchActiveVBlankWidgets()
                }
                if (nowUs >= endUs) break
                Sleep(1u)
            }
        }
    }

    fun simulateMouseMove(x: Int, y: Int, targetHwnd: HWND? = activeHwnd) {
        val h = targetHwnd ?: return
        if (IsWindow(h) == 0) return
        val lParam = ((y and 0xFFFF).toLong() shl 16) or (x and 0xFFFF).toLong()
        SendMessageW(h, WM_MOUSEMOVE.toUInt(), 0uL, lParam)
    }

    fun simulateMouseWheel(x: Int, y: Int, deltaY: Int, targetHwnd: HWND? = activeHwnd) {
        val h = targetHwnd ?: return
        if (IsWindow(h) == 0) return
        val screenPt = memScoped {
            val pt = alloc<POINT>()
            pt.x = x
            pt.y = y
            platform.windows.ClientToScreen(h, pt.ptr)
            ((pt.y and 0xFFFF).toLong() shl 16) or (pt.x and 0xFFFF).toLong()
        }
        val wParam = ((deltaY and 0xFFFF).toLong() shl 16).toULong()
        SendMessageW(h, WM_MOUSEWHEEL.toUInt(), wParam, screenPt)
    }

    fun handleMouseWheelAt(hwnd: HWND, clientX: Int, clientY: Int, deltaY: Int) {
        simulateMouseWheel(clientX, clientY, deltaY, hwnd)
    }

    fun handleKeyDown(hwnd: HWND, vk: Int): Boolean {
        val winImpl = resolveWindowForHwnd(hwnd)
        if (vk == VK_ESCAPE) {
            dialogHwnds.entries.lastOrNull()?.key?.let { dlg ->
                dlg.closeDialog()
                return true
            }
            if (winImpl != null && isFullscreenForWindow(hwnd, winImpl)) {
                winImpl.setFullscreen(false)
                winImpl.setFullscreenElement(null)
                winImpl.notifyHostFullscreenExited()
                return true
            }
        } else if (vk == VK_F11 && !InputFocusManager.isTypingInTextInput && winImpl != null) {
            winImpl.setFullscreen(!winImpl.isFullscreen())
            return true
        }
        winImpl?.dispatchKeyDown(KeyEvent(key = "Key$vk", keyCode = vk))
        return false
    }

    fun simulateMouseClick(x: Int, y: Int, targetHwnd: HWND? = activeHwnd) {
        val h = targetHwnd ?: return
        if (IsWindow(h) == 0) return
        val lParam = ((y and 0xFFFF).toLong() shl 16) or (x and 0xFFFF).toLong()
        SendMessageW(h, WM_MOUSEMOVE.toUInt(), 0uL, lParam)
        SendMessageW(h, WM_LBUTTONDOWN.toUInt(), 1uL, lParam)
        SendMessageW(h, WM_LBUTTONUP.toUInt(), 0uL, lParam)
    }

    fun simulateMouseDoubleClick(x: Int, y: Int, targetHwnd: HWND? = activeHwnd) {
        val h = targetHwnd ?: return
        if (IsWindow(h) == 0) return
        val lParam = ((y and 0xFFFF).toLong() shl 16) or (x and 0xFFFF).toLong()
        SendMessageW(h, WM_LBUTTONDBLCLK.toUInt(), 1uL, lParam)
    }

    /**
     * Captures a genuine rendered pixel screenshot of the active main host window using
     * `PrintWindow(hwnd, memDc, PW_RENDERFULLCONTENT)`.
     */
    fun captureWindowScreenshot(width: Int = 1280, height: Int = 800): ImageBuffer? {
        val win = activeWindowImpl
        if (win != null) {
            return captureWindowScreenshot(win, width, height)
        }
        val hwnd = activeHwnd ?: return null
        return captureHwndPixelsViaPrintWindow(hwnd, width, height)
    }

    /**
     * Mounts (if needed), lays out all retained ComCtl32/Win32 & custom child `HWND`s of [winImpl]
     * at client dimensions `[width] x [height]`, and captures the resulting framebuffer pixels via
     * `PrintWindow(hwnd, memDc, PW_RENDERFULLCONTENT)`.
     */
    fun captureWindowScreenshot(
        winImpl: WindowsWindowNativeImpl,
        width: Int = 1280,
        height: Int = 800
    ): ImageBuffer? {
        Win32WinMain.ensureWindowsControlsInitialized()
        WindowsUiThreadDispatcherNativeImpl.attachCurrentThreadAsUiThreadIfUnset()
        WindowsUiThreadDispatcherNativeImpl.drainPendingUiQueue()
        flushPendingNonOverlayLayoutSyncIfNeeded()
        val reqW = width.coerceAtLeast(16)
        val reqH = height.coerceAtLeast(16)
        val existing = resolveHwndForWindow(winImpl)
        val hwnd = existing ?: createAndShowWin32Window(winImpl, startHidden = true) ?: return null
        if (IsWindow(hwnd) == 0) return null

        val wasVisible = IsWindowVisible(hwnd) != 0
        val savedRect = memScoped {
            val r = alloc<RECT>()
            GetWindowRect(hwnd, r.ptr)
            intArrayOf(r.left, r.top, r.right - r.left, r.bottom - r.top)
        }

        if (!wasVisible) {
            SetWindowPos(hwnd, null, -32000, -32000, 0, 0, (SWP_NOSIZE or SWP_NOZORDER or SWP_NOACTIVATE).toUInt())
            ShowWindow(hwnd, SW_SHOWNA)
        }
        setWindowClientSize(winImpl, Size(reqW, reqH))
        flushPendingNonOverlayLayoutSyncIfNeeded()
        layoutAndSyncWindowChildren(hwnd)
        Win32LayeredStageHostHwnd.refreshAllStageBackdropsSynchronously()
        RedrawWindow(hwnd, null, null, RDW_INVALIDATE.toUInt() or RDW_UPDATENOW.toUInt() or RDW_ALLCHILDREN.toUInt())
        pumpVisualWindowEvents(16L)

        val result = captureHwndPixelsViaPrintWindow(hwnd, reqW, reqH)

        if (!wasVisible) {
            ShowWindow(hwnd, SW_HIDE)
            SetWindowPos(
                hwnd,
                null,
                savedRect[0],
                savedRect[1],
                savedRect[2],
                savedRect[3],
                (SWP_NOZORDER or SWP_NOACTIVATE).toUInt()
            )
        }
        return result
    }

    private fun captureHwndPixelsViaPrintWindow(hwnd: HWND, targetClientW: Int, targetClientH: Int): ImageBuffer? = memScoped {
        if (IsWindow(hwnd) == 0) return@memScoped null
        val wr = alloc<RECT>()
        val cr = alloc<RECT>()
        if (GetWindowRect(hwnd, wr.ptr) == 0 || GetClientRect(hwnd, cr.ptr) == 0) return@memScoped null
        val winW = (wr.right - wr.left).coerceAtLeast(1)
        val winH = (wr.bottom - wr.top).coerceAtLeast(1)
        val clientOriginScreen = alloc<POINT>()
        clientOriginScreen.x = 0
        clientOriginScreen.y = 0
        platform.windows.ClientToScreen(hwnd, clientOriginScreen.ptr)
        val offX = (clientOriginScreen.x - wr.left).coerceIn(0, winW - 1)
        val offY = (clientOriginScreen.y - wr.top).coerceIn(0, winH - 1)
        val cw = (cr.right - cr.left).coerceAtLeast(1).coerceAtMost(winW - offX)
        val ch = (cr.bottom - cr.top).coerceAtLeast(1).coerceAtMost(winH - offY)

        val memDc = CreateCompatibleDC(null) ?: return@memScoped null
        val bmi = alloc<BITMAPINFO>()
        bmi.bmiHeader.biSize = sizeOf<BITMAPINFOHEADER>().toUInt()
        bmi.bmiHeader.biWidth = winW
        bmi.bmiHeader.biHeight = -winH
        bmi.bmiHeader.biPlanes = 1u
        bmi.bmiHeader.biBitCount = 32u
        bmi.bmiHeader.biCompression = platform.windows.BI_RGB.toUInt()
        val bitsVar = alloc<COpaquePointerVar>()
        val dibBmp = CreateDIBSection(memDc, bmi.ptr, DIB_RGB_COLORS.toUInt(), bitsVar.ptr, null, 0u)
        val pixelsPtr = bitsVar.value?.reinterpret<UIntVar>()
        if (dibBmp == null || pixelsPtr == null) {
            if (dibBmp != null) DeleteObject(dibBmp)
            DeleteDC(memDc)
            return@memScoped null
        }
        val oldBmp = platform.windows.SelectObject(memDc, dibBmp)
        try {
            val total = winW * winH
            val sentinel: UInt = 0x01020304u
            fun fillSentinel() {
                for (i in 0 until total) {
                    pixelsPtr[i] = sentinel
                }
            }
            val outW = targetClientW.coerceAtMost(cw)
            val outH = targetClientH.coerceAtMost(ch)
            fun isClientUntouchedOrBlank(): Boolean {
                var allSentinel = true
                var allZeroRgb = true
                for (py in 0 until outH) {
                    val srcRow = (offY + py) * winW + offX
                    for (px in 0 until outW) {
                        val w = pixelsPtr[srcRow + px]
                        if (w != sentinel) allSentinel = false
                        if ((w and 0x00FFFFFFu) != 0u) allZeroRgb = false
                        if (!allSentinel && !allZeroRgb) return false
                    }
                }
                return allSentinel || allZeroRgb
            }
            fillSentinel()
            var ok = PrintWindow(hwnd, memDc, PW_RENDERFULLCONTENT_FLAG) != 0
            platform.windows.GdiFlush()
            if (!ok || isClientUntouchedOrBlank()) {
                fillSentinel()
                RedrawWindow(
                    hwnd,
                    null,
                    null,
                    (RDW_INVALIDATE or RDW_UPDATENOW or RDW_ALLCHILDREN).toUInt()
                )
                platform.windows.GdiFlush()
                Sleep(16u)
                ok = PrintWindow(hwnd, memDc, PW_RENDERFULLCONTENT_FLAG) != 0
                platform.windows.GdiFlush()
                if (!ok || isClientUntouchedOrBlank()) {
                    fillSentinel()
                    PrintWindow(hwnd, memDc, 0u)
                    platform.windows.GdiFlush()
                }
            }

            val rgba = ByteArray(outW * outH * 4)
            var dst = 0
            for (py in 0 until outH) {
                val srcRow = (offY + py) * winW + offX
                for (px in 0 until outW) {
                    val rawWord = pixelsPtr[srcRow + px]
                    val bgra = if (rawWord == sentinel) 0xFF000000.toInt() else rawWord.toInt()
                    rgba[dst++] = ((bgra ushr 16) and 0xFF).toByte()
                    rgba[dst++] = ((bgra ushr 8) and 0xFF).toByte()
                    rgba[dst++] = (bgra and 0xFF).toByte()
                    rgba[dst++] = 0xFF.toByte()
                }
            }
            ImageBuffer(
                width = outW,
                height = outH,
                pixelFormat = PixelFormat.RGBA_8888,
                colorSpace = ImageColorSpace.SRGB,
                bytes = rgba
            )
        } finally {
            platform.windows.SelectObject(memDc, oldBmp)
            DeleteObject(dibBmp)
            DeleteDC(memDc)
        }
    }

    /**
     * Populates a Win32 [HMENU] recursively from [menuWidget] (`DesktopMenuNativeImpl`), registering
     * executable command callbacks into [cmdHandlers].
     */
    internal fun populateWin32PopupMenu(
        hMenu: HMENU,
        menuWidget: DesktopNativeWidget,
        cmdHandlers: MutableMap<Int, () -> Unit>
    ) {
        var nextId = 0x4200
        fun populateRecursive(targetMenu: HMENU, widget: DesktopNativeWidget) {
            for (childWidget in widget.children) {
                if (!childWidget.visible) continue
                val childImpl = childWidget.attributes["nativeImpl"]
                when (childImpl) {
                    is DividerNativeImpl<*> -> {
                        AppendMenuW(targetMenu, MF_SEPARATOR.toUInt(), 0uL, null as String?)
                    }
                    is DesktopMenuNativeImpl<*> -> {
                        val sub = CreatePopupMenu() ?: continue
                        populateRecursive(sub, childWidget)
                        AppendMenuW(targetMenu, MF_POPUP.toUInt(), sub.toLong().toULong(), childImpl.getLabel())
                    }
                    is DesktopMenuItemNativeImpl<*> -> {
                        val id = nextId++
                        var flags = MF_STRING
                        if (!childWidget.enabled) flags = flags or MF_GRAYED
                        if (childImpl.checkable && childImpl.isChecked()) flags = flags or MF_CHECKED
                        val shortcut = childImpl.getShortcutText()?.takeIf { it.isNotBlank() }
                        val text = if (shortcut != null) "${childImpl.getLabel()}\t$shortcut" else childImpl.getLabel()
                        AppendMenuW(targetMenu, flags.toUInt(), id.toULong(), text)
                        cmdHandlers[id] = { childImpl.dispatchClick() }
                    }
                    else -> {
                        if (childWidget.children.isNotEmpty()) {
                            populateRecursive(targetMenu, childWidget)
                        }
                    }
                }
            }
        }
        populateRecursive(hMenu, menuWidget)
    }

    fun showNativeDialogWindow(dialog: DesktopDialogWindowNativeImpl<*>, ownerWindow: DesktopWindowNativeImpl<*>? = null) {
        WindowsUiThreadDispatcherNativeImpl.attachCurrentThreadAsUiThreadIfUnset()
        Win32WinMain.ensureWindowsControlsInitialized()
        ensureDefaultAppIconsLoaded("icon.ico")
        ensureHostClassRegistered()
        val ownerHwnd = resolveDialogOwnerHwnd(dialog, ownerWindow)
        val existing = dialogHwnds[dialog]
        if (existing != null && IsWindow(existing) != 0) {
            SetWindowTextW(existing, dialog.getTitle())
            applyWindowIcons(existing, null)
            resizeAndCenterDialogWindow(dialog)
            ShowWindow(existing, SW_SHOW)
            return
        }

        val style = (WS_POPUP.toInt() or WS_CAPTION or WS_SYSMENU or WS_THICKFRAME or WS_CLIPCHILDREN).toUInt()
        val exStyle = if (ownerHwnd == null) WS_EX_APPWINDOW.toUInt() else 0u
        val winDims = computeDialogOuterDimensions(dialog, style, exStyle, ownerHwnd)
        val centerPos = computeCenteredOrigin(ownerHwnd, winDims.first, winDims.second)
        val hInst = Win32WinMain.hInstance ?: GetModuleHandleW(null)
        val hwnd = CreateWindowExW(
            exStyle,
            HOST_WINDOW_CLASS,
            dialog.getTitle(),
            style,
            centerPos.first,
            centerPos.second,
            winDims.first,
            winDims.second,
            ownerHwnd,
            null,
            hInst,
            null
        ) ?: return

        dialogHwnds[dialog] = hwnd
        val handle = dialog.nativeWidget as? Win32HwndHandle
        if (handle != null) {
            handle.hwnd = hwnd
            handle.ownerImpl = dialog
            Win32ControlMessageRouter.registerEntryHwnd(hwnd, handle)
        }
        WindowsUiTimerNativeImpl.attachPendingTimersToHwnd(hwnd)
        applyWindowIcons(hwnd, null)
        Win32WinMain.allowDarkModeForWindow(hwnd, isEffectivelyDarkTheme())
        Win32DwmUxThemeBridge.applyWindowDwmAttributes(
            hwnd = hwnd,
            isToolWindow = false,
            extendTopPx = 0,
            isDark = isEffectivelyDarkTheme(),
            kind = Win32TitleBarKind.DIALOG_WINDOW
        )
        layoutAndSyncWindowChildren(hwnd)
        ShowWindow(hwnd, SW_SHOW)
        UpdateWindow(hwnd)
    }

    internal fun closeNativeDialogWindow(dialog: DesktopDialogWindowNativeImpl<*>) {
        dialogScrollYByDialog.remove(dialog)
        dialogBodyMaxScrollYByDialog.remove(dialog)
        val hwnd = dialogHwnds.remove(dialog) ?: return
        Win32ControlMessageRouter.unregisterEntryHwnd(hwnd)
        menuCmdHandlersByHwnd.remove(hwnd.toLong())
        if (IsWindow(hwnd) != 0) {
            DestroyWindow(hwnd)
        }
    }

    internal fun resizeAndCenterDialogWindow(dialog: DesktopDialogWindowNativeImpl<*>) {
        val hwnd = dialogHwnds[dialog] ?: return
        if (IsWindow(hwnd) == 0) return
        val ownerHwnd = resolveDialogOwnerHwnd(dialog, null)
        val style = GetWindowLongPtrW(hwnd, GWL_STYLE).toUInt()
        val exStyle = GetWindowLongPtrW(hwnd, GWL_EXSTYLE).toUInt()
        val targetDims = computeDialogOuterDimensions(dialog, style, exStyle, ownerHwnd)
        val centerPos = computeCenteredOrigin(ownerHwnd, targetDims.first, targetDims.second)
        val currentRect = memScoped {
            val r = alloc<RECT>()
            if (GetWindowRect(hwnd, r.ptr) != 0) {
                intArrayOf(r.left, r.top, r.right - r.left, r.bottom - r.top)
            } else {
                null
            }
        }
        if (currentRect == null ||
            currentRect[0] != centerPos.first ||
            currentRect[1] != centerPos.second ||
            currentRect[2] != targetDims.first ||
            currentRect[3] != targetDims.second
        ) {
            SetWindowPos(
                hwnd,
                null,
                centerPos.first,
                centerPos.second,
                targetDims.first,
                targetDims.second,
                (SWP_NOZORDER or SWP_NOACTIVATE).toUInt()
            )
        }
        layoutAndSyncWindowChildren(hwnd)
        InvalidateRect(hwnd, null, 0)
    }

    fun syncActiveDialogWindows() {
        for ((dialog, hwnd) in dialogHwnds.toList()) {
            if (!dialog.isVisible() || dialog.isDisposed) {
                closeNativeDialogWindow(dialog)
            } else if (IsWindow(hwnd) != 0) {
                resizeAndCenterDialogWindow(dialog)
            }
        }
    }

    fun showDockPanelWindow(panel: DesktopDockPanelNativeImpl<*>) {
        Win32WinMain.ensureWindowsControlsInitialized()
        ensureHostClassRegistered()
        val existing = panelHwnds[panel]
        if (existing != null && IsWindow(existing) != 0) {
            SetWindowTextW(existing, panel.getTitle())
            layoutAndSyncWindowChildren(existing)
            ShowWindow(existing, SW_SHOWNOACTIVATE)
            return
        }
        val style = (WS_POPUP.toInt() or WS_THICKFRAME or WS_SYSMENU or WS_CLIPCHILDREN).toUInt()
        val exStyle = WS_EX_TOOLWINDOW.toUInt()
        val b = panel.nativeWidget.bounds
        val w = b.width.takeIf { it > 40 } ?: 320
        val h = b.height.takeIf { it > 40 } ?: 420
        val hInst = Win32WinMain.hInstance ?: GetModuleHandleW(null)
        val hwnd = CreateWindowExW(
            exStyle,
            HOST_WINDOW_CLASS,
            panel.getTitle(),
            style,
            b.x.takeIf { it != 0 } ?: 120,
            b.y.takeIf { it != 0 } ?: 120,
            w,
            h,
            activeHwnd,
            null,
            hInst,
            null
        ) ?: return

        panelHwnds[panel] = hwnd
        val handle = panel.nativeWidget as? Win32HwndHandle
        if (handle != null) {
            handle.hwnd = hwnd
            handle.ownerImpl = panel
            Win32ControlMessageRouter.registerEntryHwnd(hwnd, handle)
        }
        applyWindowsImmersiveTitleBarTheme(hwnd, isEffectivelyDarkTheme())
        layoutAndSyncWindowChildren(hwnd)
        ShowWindow(hwnd, SW_SHOWNOACTIVATE)
        UpdateWindow(hwnd)
    }

    fun closeDockPanelWindow(panel: DesktopDockPanelNativeImpl<*>) {
        val hwnd = panelHwnds.remove(panel) ?: return
        Win32ControlMessageRouter.unregisterEntryHwnd(hwnd)
        Win32NativeTitleBarRenderer.clearWindowState(hwnd)
        if (IsWindow(hwnd) != 0) DestroyWindow(hwnd)
    }

    fun syncMultiWindowDocks() {
        for ((panel, hwnd) in panelHwnds.toList()) {
            if (!panel.isFloating() || panel.isDisposed) {
                closeDockPanelWindow(panel)
            } else if (IsWindow(hwnd) != 0) {
                layoutAndSyncWindowChildren(hwnd)
            }
        }
    }

    fun showPopoverWindow(popover: DesktopPopoverNativeImpl<*>, anchorImpl: NativeImpl<*>?, x: Int, y: Int) {
        Win32WinMain.ensureWindowsControlsInitialized()
        ensureHostClassRegistered()
        val pref = popover.measureIntrinsicSize(360f, 480f)
        val pw = pref.width.coerceIn(120, 600)
        val ph = pref.height.coerceIn(60, 640)
        val anchorHandle = anchorImpl?.nativeWidget as? Win32HwndHandle
        val anchorHwnd = anchorHandle?.hwnd
        val owner = activeHwnd

        val pos = memScoped {
            if (anchorHwnd != null && IsWindow(anchorHwnd) != 0) {
                val r = alloc<RECT>()
                GetWindowRect(anchorHwnd, r.ptr)
                val placement = popover.nativeWidget.attributes["placement"] as? Placement ?: Placement.BOTTOM
                when (placement) {
                    Placement.TOP, Placement.TOP_START, Placement.TOP_END -> r.left to (r.top - ph - 4)
                    Placement.START -> (r.left - pw - 4) to r.top
                    Placement.END -> (r.right + 4) to r.top
                    Placement.BOTTOM, Placement.BOTTOM_START, Placement.BOTTOM_END -> r.left to (r.bottom + 4)
                }
            } else if (owner != null && IsWindow(owner) != 0) {
                val pt = alloc<POINT>()
                pt.x = x
                pt.y = y
                platform.windows.ClientToScreen(owner, pt.ptr)
                pt.x to pt.y
            } else {
                x to y
            }
        }

        val existing = popoverHwnds[popover]
        val hwnd = if (existing != null && IsWindow(existing) != 0) {
            SetWindowPos(existing, HWND_TOPMOST, pos.first, pos.second, pw, ph, SWP_NOACTIVATE.toUInt())
            existing
        } else {
            val hInst = Win32WinMain.hInstance ?: GetModuleHandleW(null)
            val created = CreateWindowExW(
                (WS_EX_TOOLWINDOW or WS_EX_TOPMOST).toUInt(),
                POPOVER_WINDOW_CLASS,
                "",
                (WS_POPUP.toInt() or WS_CLIPCHILDREN).toUInt(),
                pos.first,
                pos.second,
                pw,
                ph,
                owner,
                null,
                hInst,
                null
            ) ?: return
            popoverHwnds[popover] = created
            (popover.nativeWidget as? Win32HwndHandle)?.let { h ->
                h.hwnd = created
                h.ownerImpl = popover
                Win32ControlMessageRouter.registerEntryHwnd(created, h)
            }
            created
        }
        applyWindowsImmersiveTitleBarTheme(hwnd, isEffectivelyDarkTheme())
        layoutContainerChildrenIntoHostHwnd(hwnd, popover, 0, 0, pw, ph)
        ShowWindow(hwnd, SW_SHOWNOACTIVATE)
    }

    fun closePopoverWindow(popover: DesktopPopoverNativeImpl<*>) {
        val hwnd = popoverHwnds.remove(popover) ?: return
        Win32ControlMessageRouter.unregisterEntryHwnd(hwnd)
        if (IsWindow(hwnd) != 0) DestroyWindow(hwnd)
    }

    internal fun onSystemThemeChanged(hwnd: HWND?) {
        invalidateSystemThemeCache()
        Win32WinMain.syncUxThemeMenuDarkMode(isEffectivelyDarkTheme())
        Win32RetainedControlPaintCache.invalidateAll()
        invalidateAllWindows()
    }

    internal fun handleHostWndProc(
        hwnd: HWND,
        msg: UINT,
        wParam: WPARAM,
        lParam: LPARAM
    ): LRESULT? {
        Win32ControlMessageRouter.handleReflectedParentMessage(hwnd, msg, wParam, lParam)?.let { return it }

        val winImpl = resolveWindowForHwnd(hwnd)
        val msgInt = msg.toInt()
        when (msgInt) {
            WM_COMMAND -> {
                if (lParam == 0L) {
                    val cmdId = (wParam and 0xFFFFuL).toInt()
                    val handler = menuCmdHandlersByHwnd[hwnd.toLong()]?.get(cmdId)
                    if (handler != null) {
                        handler()
                        return 0L
                    }
                }
                return null
            }

            WM_NCCALCSIZE -> {
                if (wParam != 0uL && winImpl != null && hasCustomTitleBarForWindow(winImpl, hwnd)) {
                    return Win32NativeTitleBarRenderer.handleNcCalcSize(hwnd, wParam, lParam)
                }
                if (wParam != 0uL && isPanelToolWindow(hwnd)) {
                    return Win32NativeTitleBarRenderer.handleNcCalcSize(hwnd, wParam, lParam)
                }
                return null
            }

            WM_NCHITTEST -> {
                val screenX = (lParam and 0xFFFFL).toShort().toInt()
                val screenY = ((lParam shr 16) and 0xFFFFL).toShort().toInt()
                if (winImpl != null && hasCustomTitleBarForWindow(winImpl, hwnd)) {
                    if (Win32NativeTitleBarRenderer.doesDwmDrawCaptionButtons(hwnd)) {
                        Win32DwmUxThemeBridge.dwmDefWindowProc(hwnd, msg, wParam, lParam)?.let { dwmRes ->
                            return dwmRes.toLong()
                        }
                    }
                    val pt = memScoped {
                        val p = alloc<POINT>()
                        p.x = screenX
                        p.y = screenY
                        ScreenToClient(hwnd, p.ptr)
                        p.x to p.y
                    }
                    val deepChild = Win32ControlMessageRouter.findDeepestHitChildHwnd(hwnd, pt.first, pt.second)
                    val deepEntry = deepChild?.let { Win32ControlMessageRouter.resolveEntry(it) }
                    val overInteractiveChild = deepEntry != null && isInteractiveTitleBarChildWidget(deepEntry)
                    return Win32NativeTitleBarRenderer.hitTestWindow(
                        hwnd = hwnd,
                        screenX = screenX,
                        screenY = screenY,
                        isToolWindow = winImpl.isToolModalWindow,
                        hasInteractiveClientRegionAt = { _, _ -> overInteractiveChild },
                        isWindowControlAt = { cx, cy ->
                            overInteractiveChild || Win32NativeTitleBarRenderer.isPointInCaptionButtonOrIcon(hwnd, cx, cy)
                        }
                    )
                }
                if (winImpl != null && winImpl.isUndecorated() && !isFullscreenForWindow(hwnd, winImpl)) {
                    return hitTestUndecoratedWindowWithOverlayTitleBar(hwnd, winImpl, screenX, screenY)
                }
                return null
            }

            WM_NCMOUSEMOVE -> {
                if (winImpl != null && hasCustomTitleBarForWindow(winImpl, hwnd)) {
                    val hit = wParam.toInt()
                    Win32NativeTitleBarRenderer.handleNcMouseMove(hwnd, hit)
                    if (!Win32NativeTitleBarRenderer.doesDwmDrawCaptionButtons(hwnd) &&
                        (hit == HTMINBUTTON || hit == HTMAXBUTTON || hit == HTCLOSE)
                    ) {
                        return 0L
                    }
                } else if (winImpl != null && winImpl.isUndecorated() && !isFullscreenForWindow(hwnd, winImpl) &&
                    findActiveOverlayTitleBarWidget(winImpl.nativeWidget) != null
                ) {
                    if (wParam.toInt() == HTMAXBUTTON) {
                        val maxHandle = findOverlayWindowControl(winImpl.nativeWidget, "maximize") as? Win32HwndHandle
                        val prev = undecoratedNcMaxHoveredByHwnd[hwnd.toLong()]
                        if (prev !== maxHandle) {
                            clearUndecoratedNcMaxHover(hwnd)
                            if (maxHandle != null) {
                                maxHandle.ownerImpl?.setHoveredState(true)
                                maxHandle.hwnd?.let { if (IsWindow(it) != 0) InvalidateRect(it, null, 0) }
                                undecoratedNcMaxHoveredByHwnd[hwnd.toLong()] = maxHandle
                            }
                        }
                    } else {
                        clearUndecoratedNcMaxHover(hwnd)
                    }
                }
                return null
            }

            WM_NCMOUSELEAVE -> {
                Win32NativeTitleBarRenderer.handleNcMouseLeave(hwnd)
                clearUndecoratedNcMaxHover(hwnd)
                if (winImpl != null &&
                    hasCustomTitleBarForWindow(winImpl, hwnd) &&
                    !Win32NativeTitleBarRenderer.doesDwmDrawCaptionButtons(hwnd)
                ) {
                    return 0L
                }
                return null
            }

            WM_NCLBUTTONDOWN -> {
                val hit = wParam.toInt()
                resizeHitTestToCssCursor(hit)?.let { cursor ->
                    pendingSizeMoveCursorByHwnd[hwnd.toLong()] = cursor
                } ?: if (hit == HTCAPTION) {
                    pendingSizeMoveCursorByHwnd[hwnd.toLong()] = CssCursor.DEFAULT
                } else {
                    pendingSizeMoveCursorByHwnd.remove(hwnd.toLong())
                }
                if (winImpl != null && hasCustomTitleBarForWindow(winImpl, hwnd)) {
                    val sx = (lParam and 0xFFFFL).toShort().toInt()
                    val sy = ((lParam shr 16) and 0xFFFFL).toShort().toInt()
                    if (Win32NativeTitleBarRenderer.handleNcLButtonDown(hwnd, hit, sx, sy)) {
                        pendingSizeMoveCursorByHwnd.remove(hwnd.toLong())
                        InvalidateRect(hwnd, null, 0)
                        return 0L
                    }
                } else if (winImpl != null && winImpl.isUndecorated() && !isFullscreenForWindow(hwnd, winImpl) &&
                    hit == HTMAXBUTTON && findActiveOverlayTitleBarWidget(winImpl.nativeWidget) != null
                ) {
                    undecoratedNcMaxPressedHwnds.add(hwnd.toLong())
                    val maxHandle = findOverlayWindowControl(winImpl.nativeWidget, "maximize") as? Win32HwndHandle
                    maxHandle?.ownerImpl?.setActivePressedState(true)
                    maxHandle?.hwnd?.let { if (IsWindow(it) != 0) InvalidateRect(it, null, 0) }
                    return 0L
                }
                return null
            }

            WM_NCLBUTTONUP -> {
                pendingSizeMoveCursorByHwnd.remove(hwnd.toLong())
                if (winImpl != null && hasCustomTitleBarForWindow(winImpl, hwnd)) {
                    val hit = wParam.toInt()
                    val handled = Win32NativeTitleBarRenderer.handleNcLButtonUp(hwnd, hit, isMainHostWindow(hwnd))
                    if (handled || (!Win32NativeTitleBarRenderer.doesDwmDrawCaptionButtons(hwnd) &&
                            (hit == HTMINBUTTON || hit == HTMAXBUTTON || hit == HTCLOSE))
                    ) {
                        return 0L
                    }
                } else if (winImpl != null && winImpl.isUndecorated() && !isFullscreenForWindow(hwnd, winImpl) &&
                    findActiveOverlayTitleBarWidget(winImpl.nativeWidget) != null
                ) {
                    val wasPressed = undecoratedNcMaxPressedHwnds.remove(hwnd.toLong())
                    val hit = wParam.toInt()
                    if (hit == HTMAXBUTTON || wasPressed) {
                        val maxHandle = findOverlayWindowControl(winImpl.nativeWidget, "maximize") as? Win32HwndHandle
                        maxHandle?.ownerImpl?.setActivePressedState(false)
                        maxHandle?.hwnd?.let { if (IsWindow(it) != 0) InvalidateRect(it, null, 0) }
                        if (hit == HTMAXBUTTON) {
                            val maxOwner = maxHandle?.ownerImpl
                            if (maxOwner != null && maxOwner.hasClickListener()) {
                                maxOwner.dispatchClick()
                            } else {
                                val cmd = if (IsZoomed(hwnd) != 0) SC_RESTORE else SC_MAXIMIZE
                                PostMessageW(hwnd, WM_SYSCOMMAND.toUInt(), cmd.toULong(), 0L)
                            }
                        }
                        return 0L
                    }
                }
                return null
            }

            WM_NCLBUTTONDBLCLK -> {
                if (winImpl != null && winImpl.isUndecorated() && !isFullscreenForWindow(hwnd, winImpl) &&
                    wParam.toInt() == HTCAPTION && findActiveOverlayTitleBarWidget(winImpl.nativeWidget) != null
                ) {
                    val resizable = (winImpl.nativeWidget.attributes["resizable"] as? Boolean) != false
                    val allowMax = !winImpl.isToolModalWindow && resizable &&
                        (winImpl.getTitleBarInstructions()?.showMaximizeButton ?: true)
                    if (allowMax) {
                        val cmd = if (IsZoomed(hwnd) != 0) SC_RESTORE else SC_MAXIMIZE
                        PostMessageW(hwnd, WM_SYSCOMMAND.toUInt(), cmd.toULong(), 0L)
                    }
                    return 0L
                }
                return null
            }

            WM_NCRBUTTONUP -> {
                if (winImpl != null && winImpl.isUndecorated() && !isFullscreenForWindow(hwnd, winImpl) &&
                    wParam.toInt() == HTCAPTION && findActiveOverlayTitleBarWidget(winImpl.nativeWidget) != null
                ) {
                    val sx = (lParam and 0xFFFFL).toShort().toInt()
                    val sy = ((lParam shr 16) and 0xFFFFL).toShort().toInt()
                    Win32NativeTitleBarRenderer.showNativeSystemMenu(hwnd, sx, sy)
                    return 0L
                }
                return null
            }

            WM_ERASEBKGND -> return 1L

            WM_SETCURSOR -> {
                if (PointerDragManager.isCursorLocked &&
                    Win32ControlMessageRouter.applyLockedCursorIfAny(forceSet = false)
                ) {
                    return 1L
                }
                if ((lParam and 0xFFFFL).toInt() == HTCLIENT &&
                    Win32ControlMessageRouter.updateCursorForHwnd(hwnd, forceSet = false)
                ) {
                    return 1L
                }
                return null
            }

            WM_ENTERSIZEMOVE_MSG -> {
                val pendingCursor = pendingSizeMoveCursorByHwnd.remove(hwnd.toLong())
                if (PointerDragManager.activeKind != PointerDragKind.WINDOW_MOVE &&
                    PointerDragManager.activeKind != PointerDragKind.WINDOW_RESIZE
                ) {
                    val session = if (pendingCursor != null && pendingCursor != CssCursor.DEFAULT) {
                        PointerDragManager.startWindowResizeDrag(lockedCursor = pendingCursor)
                    } else {
                        PointerDragManager.startWindowMoveDrag(lockedCursor = pendingCursor ?: CssCursor.DEFAULT)
                    }
                    sizeMoveSessionsByHwnd[hwnd.toLong()] = session
                }
                Win32ControlMessageRouter.applyLockedCursorIfAny(forceSet = true)
                return null
            }

            WM_EXITSIZEMOVE_MSG -> {
                pendingSizeMoveCursorByHwnd.remove(hwnd.toLong())
                sizeMoveSessionsByHwnd.remove(hwnd.toLong())?.dispose()
                if (PointerDragManager.activeKind == PointerDragKind.WINDOW_MOVE ||
                    PointerDragManager.activeKind == PointerDragKind.WINDOW_RESIZE
                ) {
                    PointerDragManager.cancelActiveDrag()
                }
                refreshActiveCursorAtPointerOnUiThread()
                return null
            }

            WM_PAINT -> {
                paintWindow(hwnd)
                return 0L
            }

            WM_SIZE -> {
                val cw = (lParam and 0xFFFFL).toInt()
                val ch = ((lParam shr 16) and 0xFFFFL).toInt()
                val hwndKey = hwnd.toLong()
                activeHostWmSizeHwnds.add(hwndKey)
                try {
                    if (winImpl != null && cw > 0 && ch > 0) {
                        winImpl.syncHostWindowStateAndSize(
                            widthPx = cw,
                            heightPx = ch,
                            fullscreenNow = isFullscreenForWindow(hwnd, winImpl),
                            zoomedNow = IsZoomed(hwnd) != 0,
                            iconicNow = IsIconic(hwnd) != 0
                        )
                    }
                    layoutAndSyncWindowChildren(hwnd)
                    if (winImpl != null && hasCustomTitleBarForWindow(winImpl, hwnd) && cw > 0 && ch > 0) {
                        val tbH = getMainWindowTitleBarHeight(winImpl)
                        if (tbH > 0) {
                            memScoped {
                                val band = alloc<RECT>()
                                band.left = 0
                                band.top = 0
                                band.right = cw
                                band.bottom = tbH.coerceAtMost(ch)
                                InvalidateRect(hwnd, band.ptr, 0)
                            }
                        }
                    }
                } finally {
                    activeHostWmSizeHwnds.remove(hwndKey)
                }
                return 0L
            }

            WM_MOVE -> {
                if (winImpl != null && IsIconic(hwnd) == 0 && !isFullscreenForWindow(hwnd, winImpl)) {
                    memScoped {
                        val wr = alloc<RECT>()
                        if (GetWindowRect(hwnd, wr.ptr) != 0 && wr.left > -16000 && wr.top > -16000) {
                            winImpl.notifyHostPositionChanged(wr.left, wr.top)
                        }
                    }
                }
                return 0L
            }

            WM_SIZING -> {
                if (winImpl != null) {
                    if (isFullscreenForWindow(hwnd, winImpl) || IsZoomed(hwnd) != 0) {
                        return null
                    }
                    val rectPtr = lParam.toCPointer<RECT>() ?: return null
                    val minSize = winImpl.getMinWindowSize()
                    val minClientW = minSize?.width ?: 200
                    val minClientH = minSize?.height ?: 140
                    val style = GetWindowLongPtrW(hwnd, GWL_STYLE).toUInt()
                    val ex = GetWindowLongPtrW(hwnd, GWL_EXSTYLE).toUInt()
                    val frameMargins = memScoped {
                        val r = alloc<RECT>()
                        AdjustWindowRectEx(r.ptr, style, 0, ex)
                        (r.right - r.left) to (r.bottom - r.top)
                    }
                    val minTrackW = minClientW + frameMargins.first
                    val minTrackH = minClientH + frameMargins.second
                    val ratio = winImpl.getAspectRatio()
                    val cur = winImpl.getWindowSize()
                    if (ratio != null) {
                        adjustRectForAspectRatio(
                            rect = rectPtr.pointed,
                            edge = wParam.toInt(),
                            frameW = frameMargins.first,
                            frameH = frameMargins.second,
                            currentClientW = cur.width,
                            currentClientH = cur.height,
                            minTrackW = minTrackW,
                            minTrackH = minTrackH,
                            aspectRatio = ratio
                        )
                        return 1L
                    }
                    adjustRectForMinSize(rectPtr.pointed, wParam.toInt(), minTrackW, minTrackH)
                    return 1L
                }
                return null
            }

            WM_GETMINMAXINFO -> {
                if (winImpl != null) {
                    val minSize = winImpl.getMinWindowSize() ?: return null
                    val mmi = lParam.toCPointer<MINMAXINFO>() ?: return null
                    val style = GetWindowLongPtrW(hwnd, GWL_STYLE).toUInt()
                    val ex = GetWindowLongPtrW(hwnd, GWL_EXSTYLE).toUInt()
                    memScoped {
                        val r = alloc<RECT>()
                        r.left = 0
                        r.top = 0
                        r.right = minSize.width.coerceAtLeast(1)
                        r.bottom = minSize.height.coerceAtLeast(1)
                        AdjustWindowRectEx(r.ptr, style, 0, ex)
                        mmi.pointed.ptMinTrackSize.x = r.right - r.left
                        mmi.pointed.ptMinTrackSize.y = r.bottom - r.top
                    }
                    return 0L
                }
                return null
            }

            WM_NCACTIVATE -> {
                val active = wParam != 0uL
                Win32NativeTitleBarRenderer.setWindowActive(hwnd, active)
                if (!active) {
                    Win32NativeTitleBarRenderer.clearPressedNcButton(hwnd)
                }
                winImpl?.notifyHostFocusChanged(active)
                if (hasCustomTitleBarForWindow(winImpl, hwnd)) {
                    Win32StyledWidgetHelper.invalidateAncestorBackgroundCache()
                    invalidateTitleBarAndChildren(hwnd, winImpl)
                    return DefWindowProcW(hwnd, WM_NCACTIVATE.toUInt(), wParam, -1L)
                }
                return null
            }

            WM_ACTIVATE -> {
                val active = (wParam and 0xFFFFuL).toInt() != 0 && ((wParam shr 16) and 0xFFFFuL).toInt() == 0
                Win32NativeTitleBarRenderer.setWindowActive(hwnd, active)
                if (!active) {
                    Win32NativeTitleBarRenderer.clearPressedNcButton(hwnd)
                }
                winImpl?.notifyHostFocusChanged(active)
                if (hasCustomTitleBarForWindow(winImpl, hwnd)) {
                    invalidateTitleBarAndChildren(hwnd, winImpl)
                }
                return null
            }

            WM_DPICHANGED -> {
                val rectPtr = lParam.toCPointer<RECT>()
                if (rectPtr != null) {
                    val r = rectPtr.pointed
                    SetWindowPos(
                        hwnd,
                        null,
                        r.left,
                        r.top,
                        r.right - r.left,
                        r.bottom - r.top,
                        (SWP_NOZORDER or SWP_NOACTIVATE).toUInt()
                    )
                }
                layoutAndSyncWindowChildren(hwnd)
                InvalidateRect(hwnd, null, 0)
                return 0L
            }

            WM_SETTINGCHANGE, WM_THEMECHANGED -> {
                onSystemThemeChanged(hwnd)
                return 0L
            }

            0x0215 /* WM_CAPTURECHANGED */ -> {
                Win32NativeTitleBarRenderer.clearPressedNcButton(hwnd)
                return null
            }

            WM_MOUSEMOVE,
            WM_LBUTTONDOWN, WM_LBUTTONUP, WM_LBUTTONDBLCLK,
            WM_RBUTTONDOWN, WM_RBUTTONUP,
            WM_MBUTTONDOWN, WM_MBUTTONUP,
            WM_MOUSEWHEEL -> {
                if (msgInt == WM_MOUSEMOVE || msgInt == WM_LBUTTONDOWN) {
                    Win32LayeredStageHostHwnd.setHudAutoHiddenOnAllStages(false)
                    SetTimer(hwnd, HUD_AUTOHIDE_TIMER_ID, HUD_AUTOHIDE_DELAY_MS, null)
                }
                if (msgInt == WM_LBUTTONUP) {
                    Win32NativeTitleBarRenderer.clearPressedNcButton(hwnd)
                }
                Win32ControlMessageRouter.forwardMouseMessageToChild(hwnd, msg, wParam, lParam)?.let { return it }
                if (msgInt == WM_MOUSEWHEEL) {
                    getDialogForHwnd(hwnd)?.let { dlg ->
                        val maxScroll = dialogBodyMaxScrollYByDialog[dlg] ?: 0
                        if (maxScroll > 0) {
                            val delta = ((wParam.toLong() shr 16) and 0xFFFFL).toShort().toInt()
                            if (delta != 0) {
                                val step = (-delta / 120f * 48f).roundToInt()
                                val cur = dialogScrollYByDialog[dlg] ?: 0
                                val next = (cur + step).coerceIn(0, maxScroll)
                                if (next != cur) {
                                    dialogScrollYByDialog[dlg] = next
                                    layoutAndSyncWindowChildren(hwnd)
                                    InvalidateRect(hwnd, null, 0)
                                    return 0L
                                }
                            }
                        }
                    }
                }
                return null
            }

            WM_KEYDOWN -> {
                val vk = (wParam and 0xFFFFuL).toInt()
                return if (handleKeyDown(hwnd, vk)) 0L else null
            }

            WM_KEYUP -> {
                val vk = (wParam and 0xFFFFuL).toInt()
                winImpl?.dispatchKeyUp(KeyEvent(key = "Key$vk", keyCode = vk))
                return null
            }

            WM_TIMER -> {
                if (wParam == HUD_AUTOHIDE_TIMER_ID) {
                    KillTimer(hwnd, HUD_AUTOHIDE_TIMER_ID)
                    Win32LayeredStageHostHwnd.setHudAutoHiddenOnAllStages(true)
                    return 0L
                }
                if (WindowsSystemTrayNativeImpl.handleRetryTimer(hwnd, wParam.toInt())) {
                    return 0L
                }
                if (WindowsUiTimerNativeImpl.dispatchWin32Timer(wParam.toInt())) {
                    return 0L
                }
                return null
            }

            WM_APP_VBLANK.toInt() -> {
                surfaceFrameWakePosted.value = 0
                WindowsAnimationNativeImpl.activeInstance?.handleWmAppVBlank()
                dispatchActiveVBlankWidgets()
                return 0L
            }

            WM_APP_UI_DISPATCH.toInt() -> {
                WindowsUiThreadDispatcherNativeImpl.drainPendingUiQueue()
                return 0L
            }

            WM_APP_LAYOUT_SYNC.toInt() -> {
                flushPendingNonOverlayLayoutSyncIfNeeded()
                return 0L
            }

            WM_APP_TRAY_ICON.toInt() -> {
                WindowsSystemTrayNativeImpl.handleTrayCallback(hwnd, lParam.toInt())
                return 0L
            }

            WM_APP_NOTIFY_BALLOON.toInt() -> {
                WindowsNotificationManagerNativeImpl.handleNotificationCallback(lParam.toInt())
                return 0L
            }

            WM_CLOSE -> {
                getDialogForHwnd(hwnd)?.let { dlg ->
                    dlg.closeDialog()
                    return 0L
                }
                panelHwnds.entries.firstOrNull { it.value == hwnd }?.key?.let { panel ->
                    panel.setFloating(false)
                    closeDockPanelWindow(panel)
                    return 0L
                }
                if (winImpl != null) {
                    winImpl.closeWindow()
                    return 0L
                }
                DestroyWindow(hwnd)
                return 0L
            }

            WM_DESTROY -> {
                WindowsUiTimerNativeImpl.rehomeTimersFromDestroyedHwnd(hwnd)
                Win32ControlMessageRouter.unregisterEntryHwnd(hwnd)
                Win32NativeTitleBarRenderer.clearWindowState(hwnd)
                activeHostWmSizeHwnds.remove(hwnd.toLong())
                destroyOwnedIconPair(ownedWindowIconsByHwnd.remove(hwnd.toLong()))
                savedFullscreenStateByHwnd.remove(hwnd.toLong())
                savedFullscreenElementOriginByHwnd.remove(hwnd.toLong())
                menuCmdHandlersByHwnd.remove(hwnd.toLong())
                undecoratedNcMaxPressedHwnds.remove(hwnd.toLong())
                undecoratedNcMaxHoveredByHwnd.remove(hwnd.toLong())
                pendingSizeMoveCursorByHwnd.remove(hwnd.toLong())
                sizeMoveSessionsByHwnd.remove(hwnd.toLong())?.dispose()
                val destroyedDialogs = dialogHwnds.entries.filter { it.value == hwnd }.map { it.key }
                for (dlg in destroyedDialogs) {
                    dialogHwnds.remove(dlg)
                    dialogScrollYByDialog.remove(dlg)
                    dialogBodyMaxScrollYByDialog.remove(dlg)
                }
                panelHwnds.entries.removeAll { it.value == hwnd }
                popoverHwnds.entries.removeAll { it.value == hwnd }
                secondaryWindowHwnds.entries.removeAll { it.value == hwnd }
                if (hwnd == activeHwnd) {
                    activeHwnd = null
                    activeWindowImpl = null
                }
                val hasLiveTopLevel =
                    (activeHwnd != null && IsWindow(activeHwnd) != 0) ||
                    dialogHwnds.values.any { IsWindow(it) != 0 } ||
                    secondaryWindowHwnds.values.any { IsWindow(it) != 0 } ||
                    panelHwnds.values.any { IsWindow(it) != 0 }
                if (!hasLiveTopLevel && !WindowsHeadlessTrayLoop.isTrayActive() && !visualTestMode) {
                    PostQuitMessage(0)
                } else {
                    syncHostVBlankDemand()
                }
                return 0L
            }

            else -> {
                val tbMsg = WM_TASKBARCREATED
                if (tbMsg != 0u && msg == tbMsg) {
                    WindowsSystemTrayNativeImpl.reRegisterAfterTaskbarCreated(hwnd)
                    return 0L
                }
                return null
            }
        }
    }

    private fun invalidateTitleBarAndChildren(hwnd: HWND, winImpl: WindowsWindowNativeImpl?) {
        if (IsWindow(hwnd) == 0) return
        val tb = winImpl?.getTitleBarInstructions()
        val tbH = if (winImpl != null) getMainWindowTitleBarHeight(winImpl) else 0
        if (tbH > 0) {
            memScoped {
                val r = alloc<RECT>()
                GetClientRect(hwnd, r.ptr)
                val cw = (r.right - r.left).coerceAtLeast(1)
                val band = alloc<RECT>()
                band.left = 0
                band.top = 0
                band.right = cw
                band.bottom = tbH.coerceAtMost((r.bottom - r.top).coerceAtLeast(1))
                InvalidateRect(hwnd, band.ptr, 0)
            }
        } else {
            InvalidateRect(hwnd, null, 0)
        }
        if (tb != null) {
            for (comp in tb.leadingComponents + tb.centerComponents + tb.trailingComponents) {
                val h = (comp.nativeImpl?.nativeWidget as? Win32HwndHandle)?.hwnd
                if (h != null && IsWindow(h) != 0) {
                    RedrawWindow(h, null, null, (RDW_INVALIDATE or RDW_ALLCHILDREN).toUInt())
                }
            }
        }
    }

    private fun layoutContainerChildrenIntoHostHwnd(
        hostHwnd: HWND,
        container: DesktopBaseContainerNativeImpl<*>,
        originX: Int,
        originY: Int,
        width: Int,
        height: Int
    ) {
        val children = container.getLogicalChildren().filterIsInstance<DesktopBaseNativeImpl<*>>()
        if (children.isEmpty()) return

        for (child in children) {
            val h = child.nativeWidget as? Win32HwndHandle
            if (h != null) {
                Win32InteractiveSurfaceHwnd.ensureSurfaceChildHwnd(h, child)
                h.hwnd?.let { Win32ControlMessageRouter.attachChildHwnd(h, hostHwnd) }
            }
        }
        container.layoutChildrenInBounds(width.toFloat(), height.toFloat())
        Win32ControlMessageRouter.layoutChildrenBatch(hostHwnd, children, originX, originY)
    }

    private fun relayoutSubtreeBounds(container: DesktopBaseContainerNativeImpl<*>) {
        for (child in container.getLogicalChildren()) {
            val base = child as? DesktopBaseNativeImpl<*> ?: continue
            if (base is DesktopBaseContainerNativeImpl<*>) {
                val bw = base.getBounds().width
                val bh = base.getBounds().height
                if (bw > 0 && bh > 0) {
                    base.layoutChildrenInBounds(bw.toFloat(), bh.toFloat())
                }
                relayoutSubtreeBounds(base)
            }
        }
    }

    private fun syncInteractiveSurfaceRectsForHost(
        hostHwnd: HWND,
        container: DesktopBaseContainerNativeImpl<*>
    ) {
        for (child in container.getLogicalChildren()) {
            val base = child as? DesktopBaseNativeImpl<*> ?: continue
            val h = base.nativeWidget as? Win32HwndHandle
            if (h != null && h.interactiveSurfaceSpec != null) {
                Win32InteractiveSurfaceHwnd.ensureSurfaceChildHwnd(h, base)
                val surfHwnd = h.hwnd
                if (surfHwnd != null && IsWindow(surfHwnd) != 0 && h.effectiveVisible && IsWindowVisible(surfHwnd) != 0) {
                    Win32InteractiveSurfaceHwnd.syncSurfaceStageRectForHost(h, hostHwnd)
                }
            }
            if (base is DesktopBaseContainerNativeImpl<*>) {
                if (h == null || (h.effectiveVisible && h.effectiveOpacity > 0.001f)) {
                    syncInteractiveSurfaceRectsForHost(hostHwnd, base)
                }
            }
        }
    }

    private fun syncFullscreenElementChildren(
        hostHwnd: HWND,
        winImpl: WindowsWindowNativeImpl,
        fsWidget: DesktopNativeWidget,
        clientW: Int,
        clientH: Int
    ) {
        val fsImpl = fsWidget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
        val fsHandle = fsImpl?.nativeWidget as? Win32HwndHandle
        val key = hostHwnd.toLong()
        if (fsImpl != null && fsHandle != null && savedFullscreenElementOriginByHwnd[key]?.fsHandle !== fsHandle) {
            savedFullscreenElementOriginByHwnd[key] = SavedFullscreenElementOrigin(
                fsImpl = fsImpl,
                fsHandle = fsHandle,
                originalParentHwnd = fsHandle.parentHwnd,
                originalBounds = fsImpl.getBounds()
            )
        }
        val allTop = winImpl.getLogicalChildren().filterIsInstance<DesktopBaseNativeImpl<*>>()
        for (child in allTop) {
            if (child !== fsImpl) {
                val h = child.nativeWidget as? Win32HwndHandle ?: continue
                Win32ControlMessageRouter.detachChildHwnd(h)
            }
        }
        if (fsImpl != null && fsHandle != null) {
            Win32InteractiveSurfaceHwnd.ensureSurfaceChildHwnd(fsHandle, fsImpl)
            Win32ControlMessageRouter.attachChildHwnd(fsHandle, hostHwnd)
            fsImpl.setBounds(Rect(0, 0, clientW, clientH))
            if (fsImpl is DesktopBaseContainerNativeImpl<*>) {
                fsImpl.layoutChildrenInBounds(clientW.toFloat(), clientH.toFloat())
                relayoutSubtreeBounds(fsImpl)
            }
            Win32ControlMessageRouter.applyBounds(fsHandle, Rect(0, 0, clientW, clientH))
            if (fsImpl is DesktopBaseContainerNativeImpl<*>) {
                val fsHwnd = fsHandle.hwnd
                if (fsHwnd != null && IsWindow(fsHwnd) != 0) {
                    Win32ControlMessageRouter.layoutChildrenBatch(
                        fsHwnd,
                        fsImpl.getLogicalChildren().filterIsInstance<DesktopBaseNativeImpl<*>>()
                    )
                }
                syncInteractiveSurfaceRectsForHost(hostHwnd, fsImpl)
            } else if (fsHandle.interactiveSurfaceSpec != null) {
                val surfHwnd = fsHandle.hwnd
                if (surfHwnd != null && IsWindow(surfHwnd) != 0) {
                    Win32InteractiveSurfaceHwnd.syncSurfaceStageRectForHost(fsHandle, hostHwnd)
                }
            }
        }
    }

    private fun isTitleBarStripTokens(widget: DesktopNativeWidget): Boolean {
        val tokens = widget.cachedAllClassTokens
        if ("gui-tab-strip" in tokens ||
            "gui-tab-strip--titlebar" in tokens ||
            "genericui-tabbed-desktop-strip" in tokens
        ) {
            return true
        }
        val attrs = widget.attributes
        if (attrs["titlebarTabStrip"] == true ||
            (attrs["data-titlebar-tab-strip"] as? String)?.equals("true", ignoreCase = true) == true
        ) {
            return true
        }
        val rawCls = attrs["class"]?.toString() ?: return false
        return rawCls.split(' ', '\t', '\n', '\r').any {
            it == "gui-tab-strip" || it == "gui-tab-strip--titlebar" || it == "genericui-tabbed-desktop-strip"
        }
    }

    internal fun isExpandingTitleBarStripComponent(
        comp: AbstractComponent,
        impl: DesktopBaseNativeImpl<*>
    ): Boolean {
        val w = impl.nativeWidget
        if (isTitleBarStripTokens(w) || w.attributes["expandInTitleBar"] == true) return true
        val cs = comp.getStyle()
        if (cs.width?.let { it.unit == CssUnit.PERCENT && it.value >= 99f } == true) return true
        val eff = impl.resolveEffectiveStyle()
        val effWidth = eff?.width?.let { CssLength.parse(it) }
        if (effWidth != null && effWidth.unit == CssUnit.PERCENT && effWidth.value >= 99f) return true
        val fg = cs.getFlexGrow() ?: eff?.flexGrow ?: 0f
        return fg > 0f
    }

    private fun hasExpandingTitleBarStrip(tb: TitleBar): Boolean {
        for (comp in tb.leadingComponents + tb.centerComponents) {
            val impl = comp.nativeImpl as? DesktopBaseNativeImpl<*> ?: continue
            if (comp.isVisible && isExpandingTitleBarStripComponent(comp, impl)) return true
        }
        return false
    }

    internal fun computeTitleBarStripComponentBounds(
        availableLeft: Int,
        availableRight: Int,
        titleBarH: Int,
        impl: DesktopBaseNativeImpl<*>
    ): Rect {
        val availW = (availableRight - availableLeft).coerceAtLeast(28)
        val effStyle = impl.resolveEffectiveStyle()
        val attrHeight = impl.nativeWidget.attributes["styleHeight"] as? CssLength
        val explicitH = attrHeight?.resolvePx(titleBarH.toFloat())
            ?: effStyle?.height?.let { CssLength.parse(it)?.resolvePx(titleBarH.toFloat()) }
        val pref = impl.measureIntrinsicSize(availW.toFloat(), titleBarH.toFloat())
        val minStripH = kotlin.math.min(28, titleBarH)
        val desiredH = when {
            explicitH != null && explicitH > 0f -> kotlin.math.round(explicitH).toInt().coerceIn(minStripH, titleBarH)
            pref.height in 24..titleBarH -> pref.height.coerceIn(minStripH, titleBarH)
            else -> titleBarH
        }
        val stripY = (titleBarH - desiredH).coerceAtLeast(0)
        return Rect(availableLeft, stripY, availW, desiredH)
    }

    private fun syncTitleBarComponentChildHwnds(
        hostHwnd: HWND,
        winImpl: WindowsWindowNativeImpl,
        clientW: Int,
        titleBarH: Int
    ) {
        val tb = winImpl.getTitleBarInstructions() ?: return
        val palette = Win32DwmUxThemeBridge.resolveTitleBarPalette(
            hwnd = hostHwnd,
            isToolWindow = winImpl.isToolModalWindow,
            kind = winImpl.titleBarKind,
            isDark = isEffectivelyDarkTheme()
        )
        val showMax = if (winImpl.titleBarKind.allowMinimizeMaximizeButtons) tb.showMaximizeButton else false
        val showMin = if (winImpl.titleBarKind.allowMinimizeMaximizeButtons) tb.showMinimizeButton else false
        val captionCount = (if (tb.showCloseButton) 1 else 0) + (if (showMax) 1 else 0) + (if (showMin) 1 else 0)
        var rightEdge = (clientW - palette.captionButtonWidthPx * captionCount - 8).coerceAtLeast(64)
        val hasAppIcon = tb.showWindowIcon && resolveTitleBarIcon(hostHwnd) != null
        var leftEdge = if (hasAppIcon) 32 else 8
        val compH = (titleBarH - 6).coerceIn(18, 28)
        val compY = ((titleBarH - compH) / 2).coerceAtLeast(2)

        val visibleLeading = ArrayList<Triple<AbstractComponent, DesktopBaseNativeImpl<*>, Win32HwndHandle>>(tb.leadingComponents.size)
        var nonExpandingLeadingW = 0
        var hasExpandingStrip = false
        for (comp in tb.leadingComponents) {
            val impl = comp.nativeImpl as? DesktopBaseNativeImpl<*> ?: continue
            val h = impl.nativeWidget as? Win32HwndHandle ?: continue
            if (!comp.isVisible || h.hwnd == null) continue
            visibleLeading.add(Triple(comp, impl, h))
            if (isExpandingTitleBarStripComponent(comp, impl)) {
                hasExpandingStrip = true
            } else {
                val cw = impl.measureIntrinsicSize(180f, compH.toFloat()).width.coerceIn(28, 180)
                nonExpandingLeadingW += cw + 4
            }
        }
        val minStripReserve = if (hasExpandingStrip) 80 else 16
        val minTrailingX = leftEdge + nonExpandingLeadingW + minStripReserve

        val syncedTitleBarImpls = ArrayList<DesktopBaseNativeImpl<*>>()

        fun syncTitleBarComponentSubtree(
            impl: DesktopBaseNativeImpl<*>,
            handle: Win32HwndHandle,
            bounds: Rect
        ) {
            impl.setBounds(bounds)
            if (Win32ControlMessageRouter.batchingHostLayoutDepth <= 0) {
                Win32ControlMessageRouter.applyBounds(handle, bounds)
            }
            if (impl is DesktopBaseContainerNativeImpl<*>) {
                impl.layoutChildrenInBounds(bounds.width.toFloat(), bounds.height.toFloat())
            }
            syncedTitleBarImpls.add(impl)
        }

        for (comp in tb.trailingComponents.asReversed()) {
            val impl = comp.nativeImpl as? DesktopBaseNativeImpl<*> ?: continue
            val h = impl.nativeWidget as? Win32HwndHandle ?: continue
            if (!comp.isVisible || h.hwnd == null) continue
            val pref = impl.measureIntrinsicSize(180f, compH.toFloat())
            val cw = pref.width.coerceIn(28, 180)
            val bx = (rightEdge - cw).coerceAtLeast(minTrailingX.coerceAtMost(rightEdge))
            Win32ControlMessageRouter.attachChildHwnd(h, hostHwnd)
            val r = Rect(bx, compY, cw, compH)
            syncTitleBarComponentSubtree(impl, h, r)
            rightEdge = bx - 4
        }

        val postCompactLeadingW = nonExpandingLeadingW
        var remainingFixedW = postCompactLeadingW
        for ((comp, impl, h) in visibleLeading) {
            Win32ControlMessageRouter.attachChildHwnd(h, hostHwnd)
            if (isExpandingTitleBarStripComponent(comp, impl)) {
                val stripRight = (rightEdge - remainingFixedW).coerceAtLeast(leftEdge + 28)
                val r = computeTitleBarStripComponentBounds(leftEdge, stripRight, titleBarH, impl)
                syncTitleBarComponentSubtree(impl, h, r)
                leftEdge = r.x + r.width + 4
            } else {
                val pref = impl.measureIntrinsicSize(180f, compH.toFloat())
                val cw = pref.width.coerceIn(28, 180)
                remainingFixedW = (remainingFixedW - (cw + 4)).coerceAtLeast(0)
                val r = Rect(leftEdge, compY, cw, compH)
                syncTitleBarComponentSubtree(impl, h, r)
                leftEdge += cw + 4
            }
        }

        if (syncedTitleBarImpls.isNotEmpty()) {
            Win32ControlMessageRouter.layoutChildrenBatch(hostHwnd, syncedTitleBarImpls, 0, 0)
        }
    }

    private fun syncNativeMenuBarToHostWindow(hwnd: HWND, winImpl: WindowsWindowNativeImpl) {
        val menuBarWidget = winImpl.attachedMenuBarWidget() ?: return
        if (hasCustomTitleBarForWindow(winImpl, hwnd)) return
        val hMenu = CreateMenu() ?: return
        val handlers = menuCmdHandlersByHwnd.getOrPut(hwnd.toLong()) { HashMap() }
        handlers.clear()
        populateWin32PopupMenu(hMenu, menuBarWidget, handlers)
        SetMenu(hwnd, hMenu)
    }

    private fun resolveDialogOwnerHwnd(
        dialog: DesktopDialogWindowNativeImpl<*>,
        explicitOwner: DesktopWindowNativeImpl<*>?
    ): HWND? {
        val ownerImpl = (explicitOwner ?: dialog.ownerWindow) as? WindowsWindowNativeImpl
        return ownerImpl
            ?.takeIf { it.isVisible() }
            ?.let { resolveHwndForWindow(it)?.takeIf { h -> IsWindow(h) != 0 && IsWindowVisible(h) != 0 } }
    }

    private fun isDialogFooterWidget(impl: DesktopBaseNativeImpl<*>): Boolean {
        val w = impl.nativeWidget
        if ("modal-footer" in w.cachedAllClassTokens) return true
        val cls = w.attributes["class"]?.toString() ?: return false
        return cls.split(' ', '\t', '\n', '\r').any { it == "modal-footer" }
    }

    private fun isDialogChildVisible(impl: DesktopBaseNativeImpl<*>): Boolean {
        val w = impl.nativeWidget
        if (!impl.isVisible() || !w.visible) return false
        val disp = w.attributes["display"]?.toString()?.trim()?.lowercase()
        return disp != "none"
    }

    private fun resolveDialogMaxOuterSize(ownerHwnd: HWND?): Pair<Int, Int> {
        if (ownerHwnd != null && IsWindow(ownerHwnd) != 0) {
            val ownerDims = memScoped {
                val r = alloc<RECT>()
                if (GetWindowRect(ownerHwnd, r.ptr) != 0) {
                    (r.right - r.left) to (r.bottom - r.top)
                } else null
            }
            if (ownerDims != null && ownerDims.first > 0 && ownerDims.second > 0) {
                val maxW = (ownerDims.first - 32).coerceAtLeast(240)
                val maxH = (ownerDims.second - 32).coerceAtLeast(180)
                return maxW to maxH
            }
        }
        val work = getPrimaryMonitorBounds()
        if (work != null && work.width > 0 && work.height > 0) {
            return (work.width - 48).coerceAtLeast(240) to (work.height - 48).coerceAtLeast(180)
        }
        return (GetSystemMetrics(SM_CXSCREEN).coerceAtLeast(640) - 48) to
            (GetSystemMetrics(SM_CYSCREEN).coerceAtLeast(480) - 48)
    }

    private fun measureDialogFrameMargins(style: UInt, exStyle: UInt): Pair<Int, Int> = memScoped {
        val r = alloc<RECT>()
        r.left = 0
        r.top = 0
        r.right = 0
        r.bottom = 0
        AdjustWindowRectEx(r.ptr, style, 0, exStyle)
        (r.right - r.left).coerceAtLeast(0) to (r.bottom - r.top).coerceAtLeast(0)
    }

    private fun computeDialogOuterDimensions(
        dialog: DesktopDialogWindowNativeImpl<*>,
        style: UInt,
        exStyle: UInt,
        ownerHwnd: HWND?
    ): Pair<Int, Int> {
        val (maxOuterW, maxOuterH) = resolveDialogMaxOuterSize(ownerHwnd)
        val hasExplicitSize = dialog.nativeWidget.attributes["hasExplicitBounds"] == true
        if (hasExplicitSize) {
            val pref = dialog.nativeWidget.preferredSize
            val reqOuterW = (pref?.width?.takeIf { it > 0 } ?: dialog.boundsWidth).coerceAtLeast(160)
            val reqOuterH = (pref?.height?.takeIf { it > 0 } ?: dialog.boundsHeight).coerceAtLeast(120)
            return reqOuterW.coerceIn(minOf(200, maxOuterW), maxOuterW) to
                reqOuterH.coerceIn(minOf(120, maxOuterH), maxOuterH)
        }

        val (ncW, ncH) = measureDialogFrameMargins(style, exStyle)
        val maxClientW = (maxOuterW - ncW).coerceAtLeast(200)
        val maxClientH = (maxOuterH - ncH).coerceAtLeast(140)

        val children = dialog.getLogicalChildren().filterIsInstance<DesktopBaseNativeImpl<*>>()
        val visibleBodies = children.filter { isDialogChildVisible(it) && !isDialogFooterWidget(it) }
        val visibleFooters = children.filter { isDialogChildVisible(it) && isDialogFooterWidget(it) }

        val maxFooterNatW = visibleFooters.maxOfOrNull { it.measureIntrinsicSize(null, null).width } ?: 0
        val neededFooterClientW = if (maxFooterNatW > 0) maxFooterNatW + 24 else 0

        val maxBodyAvailW = (maxClientW - 24).coerceAtLeast(160)
        val maxBodyNatW = visibleBodies.maxOfOrNull { it.measureIntrinsicSize(maxBodyAvailW.toFloat(), null).width } ?: 0
        val neededBodyClientW = if (maxBodyNatW > 0) maxBodyNatW + 24 else minOf(280, maxClientW)

        val minClientW = minOf(280, maxClientW)
        val targetClientW = maxOf(neededBodyClientW, neededFooterClientW, minClientW).coerceIn(minClientW, maxClientW)
        val availW = (targetClientW - 24).coerceAtLeast(120)

        val footerHeights = visibleFooters.map {
            it.measureIntrinsicSize(availW.toFloat(), null).height.coerceAtLeast(34)
        }
        val footerStripH = if (footerHeights.isEmpty()) {
            0
        } else {
            val fhSum = footerHeights.sum()
            val bottomMargin = if (fhSum >= 48) 0 else 10
            val topGap = if (fhSum >= 48) 0 else 6
            fhSum + bottomMargin + topGap
        }

        val bodyPadTop = if (visibleBodies.isEmpty()) 0 else 8
        val bodyPadBottom = if (visibleBodies.isEmpty()) 0 else 8
        val bodyGap = 8
        val bodyMeasuredHeights = visibleBodies.map {
            it.measureIntrinsicSize(availW.toFloat(), null).height.coerceAtLeast(1)
        }
        val totalBodyContentH = if (bodyMeasuredHeights.isEmpty()) {
            0
        } else {
            bodyPadTop + bodyPadBottom + bodyMeasuredHeights.sum() + bodyGap * (bodyMeasuredHeights.size - 1)
        }

        val desiredClientH = (totalBodyContentH + footerStripH).coerceAtLeast(if (visibleBodies.isEmpty() && visibleFooters.isEmpty()) 140 else 80)
        val targetClientH = desiredClientH.coerceIn(minOf(100, maxClientH), maxClientH)
        return (targetClientW + ncW).coerceAtMost(maxOuterW) to
            (targetClientH + ncH).coerceAtMost(maxOuterH)
    }

    private fun containsScrollableViewport(impl: DesktopBaseNativeImpl<*>): Boolean {
        if (!isDialogChildVisible(impl)) return false
        if (impl is DesktopScrollViewNativeImpl<*> ||
            impl is DesktopLazyListNativeImpl<*> ||
            impl is DesktopLazyGridNativeImpl<*>
        ) {
            return true
        }
        if (impl is DesktopBaseContainerNativeImpl<*>) {
            for (child in impl.getLogicalChildren()) {
                if (child is DesktopBaseNativeImpl<*> && containsScrollableViewport(child)) {
                    return true
                }
            }
        }
        return false
    }

    private fun layoutDialogChildren(
        hwnd: HWND,
        dialog: DesktopDialogWindowNativeImpl<*>,
        clientW: Int,
        clientH: Int
    ) {
        val allChildren = dialog.getLogicalChildren().filterIsInstance<DesktopBaseNativeImpl<*>>()
        if (allChildren.isEmpty()) {
            dialog.nativeWidget.attributes["scrollContentHeightPx"] = 0
            dialog.nativeWidget.attributes["scrollViewportHeightPx"] = clientH
            dialogBodyMaxScrollYByDialog[dialog] = 0
            return
        }

        val hiddenChildren = allChildren.filter { !isDialogChildVisible(it) }
        for (hidden in hiddenChildren) {
            val h = hidden.nativeWidget as? Win32HwndHandle ?: continue
            Win32ControlMessageRouter.detachChildHwnd(h)
        }

        val visibleBodies = allChildren.filter { isDialogChildVisible(it) && !isDialogFooterWidget(it) }
        val visibleFooters = allChildren.filter { isDialogChildVisible(it) && isDialogFooterWidget(it) }
        val availW = (clientW - 24).coerceAtLeast(80)

        val footerHeights = visibleFooters.map {
            it.measureIntrinsicSize(availW.toFloat(), null).height.coerceAtLeast(34).coerceAtMost((clientH / 2).coerceAtLeast(36))
        }
        val footerSum = footerHeights.sum()
        val footerBottomMargin = if (footerHeights.isEmpty() || footerSum >= 48) 0 else 10
        val footerTopGap = if (footerHeights.isEmpty() || footerSum >= 48) 0 else 6
        val footerStripH = if (footerHeights.isEmpty()) 0 else footerSum + footerBottomMargin + footerTopGap

        val bodyViewportH = (clientH - footerStripH).coerceAtLeast(1)
        val hasExplicitSize = dialog.nativeWidget.attributes["hasExplicitBounds"] == true
        val bodyPadTop = if (visibleBodies.isEmpty()) 0 else 8
        val bodyPadBottom = if (visibleBodies.isEmpty()) 0 else 8
        val bodyGap = 8
        val availBodyH = (bodyViewportH - bodyPadTop - bodyPadBottom).coerceAtLeast(1)
        val singleScrollableExplicitBody = hasExplicitSize &&
            visibleBodies.size == 1 &&
            containsScrollableViewport(visibleBodies[0])
        val bodyMeasuredHeights = visibleBodies.map {
            it.measureIntrinsicSize(availW.toFloat(), null).height.coerceAtLeast(1)
        }
        val bodyTargetHeights = visibleBodies.indices.map { i ->
            val measuredH = bodyMeasuredHeights[i]
            if (singleScrollableExplicitBody) {
                availBodyH
            } else if (hasExplicitSize && visibleBodies.size == 1) {
                maxOf(measuredH, availBodyH)
            } else {
                measuredH
            }
        }
        val totalBodyContentH = if (bodyMeasuredHeights.isEmpty()) {
            0
        } else if (singleScrollableExplicitBody) {
            bodyPadTop + bodyPadBottom + availBodyH
        } else {
            bodyPadTop + bodyPadBottom + bodyMeasuredHeights.sum() + bodyGap * (bodyMeasuredHeights.size - 1)
        }

        val maxScrollY = (totalBodyContentH - bodyViewportH).coerceAtLeast(0)
        dialogBodyMaxScrollYByDialog[dialog] = maxScrollY
        val scrollY = (dialogScrollYByDialog[dialog] ?: 0).coerceIn(0, maxScrollY)
        dialogScrollYByDialog[dialog] = scrollY

        val layoutList = ArrayList<DesktopBaseNativeImpl<*>>(visibleBodies.size + visibleFooters.size)
        var curY = bodyPadTop - scrollY
        for (i in visibleBodies.indices) {
            val body = visibleBodies[i]
            val hndl = body.nativeWidget as? Win32HwndHandle
            if (hndl != null) {
                Win32InteractiveSurfaceHwnd.ensureSurfaceChildHwnd(hndl, body)
                hndl.hwnd?.let { Win32ControlMessageRouter.attachChildHwnd(hndl, hwnd) }
            }
            val targetH = bodyTargetHeights[i]
            val rect = Rect(12, curY, availW, targetH)
            body.setBounds(rect)
            if (body is DesktopBaseContainerNativeImpl<*>) {
                body.layoutChildrenInBounds(availW.toFloat(), targetH.toFloat())
                relayoutSubtreeBounds(body)
            }
            layoutList.add(body)
            curY += targetH + bodyGap
        }

        var footerBottomY = clientH - footerBottomMargin
        for (i in visibleFooters.indices.reversed()) {
            val footer = visibleFooters[i]
            val hndl = footer.nativeWidget as? Win32HwndHandle
            if (hndl != null) {
                Win32InteractiveSurfaceHwnd.ensureSurfaceChildHwnd(hndl, footer)
                hndl.hwnd?.let { Win32ControlMessageRouter.attachChildHwnd(hndl, hwnd) }
            }
            val fh = footerHeights[i]
            val fy = (footerBottomY - fh).coerceAtLeast(0)
            val rect = Rect(12, fy, availW, fh)
            footer.setBounds(rect)
            if (footer is DesktopBaseContainerNativeImpl<*>) {
                footer.layoutChildrenInBounds(availW.toFloat(), fh.toFloat())
                relayoutSubtreeBounds(footer)
            }
            layoutList.add(footer)
            footerBottomY = fy
        }

        Win32ControlMessageRouter.layoutChildrenBatch(hwnd, layoutList)
        dialog.nativeWidget.attributes["scrollContentHeightPx"] = totalBodyContentH
        dialog.nativeWidget.attributes["scrollViewportHeightPx"] = bodyViewportH
    }

    private fun computeCenteredOrigin(ownerHwnd: HWND?, w: Int, h: Int): Pair<Int, Int> = memScoped {
        if (ownerHwnd != null && IsWindow(ownerHwnd) != 0) {
            val r = alloc<RECT>()
            if (GetWindowRect(ownerHwnd, r.ptr) != 0) {
                val ox = r.left + ((r.right - r.left - w) / 2)
                val oy = r.top + ((r.bottom - r.top - h) / 2)
                return@memScoped ox to oy
            }
        }
        val work = getPrimaryMonitorBounds()
        if (work != null && work.width > 0 && work.height > 0) {
            val ox = work.x + ((work.width - w) / 2)
            val oy = work.y + ((work.height - h) / 2)
            return@memScoped ox to oy
        }
        val workRc = alloc<RECT>()
        if (SystemParametersInfoW(SPI_GETWORKAREA.toUInt(), 0u, workRc.ptr, 0u) != 0) {
            val workW = (workRc.right - workRc.left).coerceAtLeast(1)
            val workH = (workRc.bottom - workRc.top).coerceAtLeast(1)
            return@memScoped (workRc.left + (workW - w) / 2) to (workRc.top + (workH - h) / 2)
        }
        val screenW = GetSystemMetrics(SM_CXSCREEN).coerceAtLeast(w)
        val screenH = GetSystemMetrics(SM_CYSCREEN).coerceAtLeast(h)
        ((screenW - w) / 2) to ((screenH - h) / 2)
    }

    private fun toChromeOnlyTitleBar(tb: TitleBar?): TitleBar? {
        if (tb == null) return null
        return TitleBar(
            embedMenuBar = tb.embedMenuBar,
            showWindowTitle = tb.showWindowTitle && !hasExpandingTitleBarStrip(tb),
            showWindowIcon = tb.showWindowIcon,
            heightPx = tb.heightPx,
            controlsPlacement = tb.controlsPlacement,
            customTitleBar = tb.customTitleBar,
            showMinimizeButton = tb.showMinimizeButton,
            showMaximizeButton = tb.showMaximizeButton,
            showCloseButton = tb.showCloseButton,
            iconPath = tb.iconPath
        )
    }

    private fun isFullscreenForWindow(hwnd: HWND?, winImpl: WindowsWindowNativeImpl?): Boolean =
        winImpl?.isFullscreen() == true || (hwnd != null && savedFullscreenStateByHwnd.containsKey(hwnd.toLong()))

    private fun resolveEffectiveWindowTitle(windowImpl: WindowsWindowNativeImpl, baseTitle: String = windowImpl.getTitle()): String {
        val banner = if (visualTestMode) visualTestBannerText?.takeIf { it.isNotBlank() } else null
        return if (banner != null) "$baseTitle — $banner" else baseTitle
    }

    internal fun loadEmbeddedExeIcon(widthPx: Int, heightPx: Int): HICON? =
        LoadImageW(
            GetModuleHandleW(null),
            1L.toCPointer<UShortVar>(),
            IMAGE_ICON.toUInt(),
            widthPx.coerceAtLeast(16),
            heightPx.coerceAtLeast(16),
            LR_DEFAULTCOLOR.toUInt()
        )?.reinterpret()

    internal fun resolveGenericUiIconBytes(iconPath: String): ByteArray? {
        val clean = iconPath.trim().replace('\\', '/')
        if (clean.isEmpty()) return null
        if (clean.startsWith("http://", ignoreCase = true) ||
            clean.startsWith("https://", ignoreCase = true) ||
            clean.startsWith("file://", ignoreCase = true) ||
            clean.startsWith("data:", ignoreCase = true) ||
            clean.startsWith("blob:", ignoreCase = true) ||
            clean.startsWith("memory:", ignoreCase = true)
        ) {
            return null
        }

        val noLeadingSlash = clean.removePrefix("/")
        val withoutScheme = when {
            clean.startsWith("res://", ignoreCase = true) -> clean.substring(6).trimStart('/')
            clean.startsWith("@drawable/", ignoreCase = true) -> clean.substring(10).trimStart('/')
            else -> noLeadingSlash
        }
        val lastSlash = withoutScheme.lastIndexOf('/')
        val lastDot = withoutScheme.lastIndexOf('.')
        val withoutSchemeStem = if (lastDot > lastSlash) withoutScheme.substring(0, lastDot) else withoutScheme
        val leafStem = withoutSchemeStem.substringAfterLast('/')
        val resolver = WindowsResourceResolverImpl()

        fun bytesForResource(resId: ResourceID, drawable: DrawableResource?): ByteArray? {
            drawable?.svgSource?.takeIf { it.isNotBlank() }?.let { return it.encodeToByteArray() }
            resolver.resolveBytes(resId)?.takeIf { it.isNotEmpty() }?.let { return it }
            Resources.activeTable.getRawBytes(resId)?.takeIf { it.isNotEmpty() }?.let { return it }
            if (drawable != null && drawable.svgPathDataList.isNotEmpty()) {
                val vw = drawable.viewBoxWidth.takeIf { it > 0f } ?: 24f
                val vh = drawable.viewBoxHeight.takeIf { it > 0f } ?: 24f
                val syntheticSvg = buildString {
                    append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $vw $vh\">")
                    for (d in drawable.svgPathDataList) {
                        append("<path d=\"").append(d).append("\" fill=\"#FFFFFF\"/>")
                    }
                    append("</svg>")
                }
                return syntheticSvg.encodeToByteArray()
            }
            return null
        }

        Resources.getDrawable(clean)?.let { drawable ->
            bytesForResource(drawable.resId, drawable)?.let { return it }
        }
        val matchedId = Resources.activeTable.findResourceId(clean)
            ?: Resources.activeTable.findResourceId(ResourceType.DRAWABLE, clean)
        if (matchedId != null) {
            bytesForResource(matchedId, Resources.activeTable.getDrawable(matchedId))?.let { return it }
        }
        val allowBareNameMatch = !withoutScheme.contains('/')
        for (drawable in Resources.activeTable.allDrawables()) {
            val id = drawable.resId
            val matches = id.toResourceUri().equals(clean, ignoreCase = true) ||
                id.legacyResourceUri.equals(clean, ignoreCase = true) ||
                id.canonicalRelativePath.equals(withoutScheme, ignoreCase = true) ||
                id.canonicalStem.equals(withoutSchemeStem, ignoreCase = true) ||
                (allowBareNameMatch && leafStem.isNotEmpty() && id.name.equals(leafStem, ignoreCase = true))
            if (matches) {
                bytesForResource(id, drawable)?.let { return it }
            }
        }

        return resolver.resolveBytes(
            ResourceID(
                id = 0,
                type = ResourceType.DRAWABLE,
                name = leafStem,
                relativePath = withoutScheme
            )
        )?.takeIf { it.isNotEmpty() }
    }

    internal fun decodeImageBytesToHIcon(bytes: ByteArray, targetW: Int, targetH: Int): HICON? {
        if (bytes.isEmpty()) return null
        val fmt = ImageFormat.detectFromHeader(bytes)
        val source = ImageSource.fromBytes(bytes, fmt)
        val options = ImageDecodeOptions(
            targetWidth = targetW,
            targetHeight = targetH,
            antialiasing = true
        )
        val active = ImageLoader.activeNativeCodec
        val decoded = active?.decode(source, options)
            ?: if (active !is WindowsWicImageCodecNativeImpl) {
                fallbackWicCodec.decode(source, options)
            } else {
                null
            }
            ?: return null
        val (fitW, fitH) = ImageLoader.resolveTargetDimensions(decoded.width, decoded.height, options)
        val finalW = fitW.coerceIn(1, 256)
        val finalH = fitH.coerceIn(1, 256)
        val ready = if (decoded.width != finalW || decoded.height != finalH) {
            ImageLoader.resize(decoded, finalW, finalH, options.antialiasing)
        } else {
            decoded
        }
        return WindowsCurCodec.createHIconFromImage(ready)
    }

    private fun isDefaultIconReference(raw: String): Boolean {
        if (raw.isEmpty()) return true
        val norm = raw.replace('\\', '/').trimStart('/').lowercase()
        return norm == "icon.ico" ||
            norm == "icon.svg" ||
            norm == "icon.png" ||
            norm == "src/commonmain/resources/icon.svg" ||
            norm == "src/commonmain/resources/icon.png" ||
            norm == "res://app/icon" ||
            norm == "app/icon" ||
            norm == "app/icon.svg"
    }

    private fun resolveLocalHIconFromPath(diskPath: String, targetW: Int, targetH: Int): HICON? {
        val normalizedPath = WindowsIoHelper.normalizeWin32Path(diskPath)
        if (diskPath.endsWith(".ico", ignoreCase = true)) {
            val hIcon: HICON? = memScoped {
                LoadImageW(
                    null,
                    normalizedPath.wcstr.ptr,
                    IMAGE_ICON.toUInt(),
                    targetW,
                    targetH,
                    (LR_LOADFROMFILE or LR_DEFAULTCOLOR).toUInt()
                )?.reinterpret()
            }
            if (hIcon != null) {
                return hIcon
            }
        }
        val bytes = DesktopConfigDirectoryHelper.readBytesFromFile(diskPath)
            ?: DesktopConfigDirectoryHelper.readBytesFromFile(normalizedPath)
            ?: return null
        return decodeImageBytesToHIcon(bytes, targetW, targetH)
    }

    /**
     * Resolves an [HICON] for a window or system tray icon path/URI, returning `(hIcon, callerOwnsHandle)`.
     */
    internal fun resolveHIconForPath(
        iconPath: String?,
        widthPx: Int,
        heightPx: Int,
        allowEmbeddedExeFallback: Boolean = true
    ): Pair<HICON?, Boolean> {
        val targetW = widthPx.coerceAtLeast(16)
        val targetH = heightPx.coerceAtLeast(16)
        val raw = iconPath?.trim().orEmpty()
        val isDefaultRef = isDefaultIconReference(raw)

        // (a) Default references prefer PE embedded RT_GROUP_ICON(#1) first
        if (isDefaultRef) {
            loadEmbeddedExeIcon(targetW, targetH)?.let { return it to true }
        }

        if (raw.isNotEmpty()) {
            val isRemoteOrDataUri = raw.startsWith("http://", ignoreCase = true) ||
                raw.startsWith("https://", ignoreCase = true) ||
                raw.startsWith("data:", ignoreCase = true) ||
                raw.startsWith("memory://", ignoreCase = true)

            // (b) Local filesystem paths (direct, exe-dir, KMP resources) and GenericUI resources
            if (!isRemoteOrDataUri) {
                DesktopConfigDirectoryHelper.resolveLocalAssetFilePath(raw)?.let { diskPath ->
                    resolveLocalHIconFromPath(diskPath, targetW, targetH)?.let { return it to true }
                }
                resolveGenericUiIconBytes(raw)?.let { resourceBytes ->
                    decodeImageBytesToHIcon(resourceBytes, targetW, targetH)?.let { return it to true }
                }
            }

            // (c) http(s)://, file://, or data: decoded via active/WIC codec
            if (raw.startsWith("http://", ignoreCase = true) ||
                raw.startsWith("https://", ignoreCase = true) ||
                raw.startsWith("file://", ignoreCase = true) ||
                raw.startsWith("data:", ignoreCase = true)
            ) {
                val source = ImageSource.fromUrl(raw)
                val options = ImageDecodeOptions(
                    targetWidth = targetW,
                    targetHeight = targetH,
                    antialiasing = true
                )
                val active = ImageLoader.activeNativeCodec
                val codec = (active as? WindowsWicImageCodecNativeImpl) ?: fallbackWicCodec
                val buffer = active?.decode(source, options)
                    ?: if (active !is WindowsWicImageCodecNativeImpl) {
                        codec.decode(source, options)
                    } else {
                        null
                    }
                if (buffer != null) {
                    val (fitW, fitH) = ImageLoader.resolveTargetDimensions(buffer.width, buffer.height, options)
                    val ready = if (buffer.width != fitW || buffer.height != fitH) {
                        ImageLoader.resize(buffer, fitW.coerceIn(1, 256), fitH.coerceIn(1, 256), options.antialiasing)
                    } else {
                        buffer
                    }
                    WindowsCurCodec.createHIconFromImage(ready)?.let { return it to true }
                }
            }
        }

        // (d) Default refs probe well-known disk/resource app icon candidates
        if (isDefaultRef) {
            for (candidate in listOf("icon.ico", "icon.svg", "icon.png", "src/commonMain/resources/icon.svg", "res://app/icon")) {
                DesktopConfigDirectoryHelper.resolveLocalAssetFilePath(candidate)?.let { diskPath ->
                    resolveLocalHIconFromPath(diskPath, targetW, targetH)?.let { return it to true }
                }
                resolveGenericUiIconBytes(candidate)?.let { resBytes ->
                    decodeImageBytesToHIcon(resBytes, targetW, targetH)?.let { return it to true }
                }
            }
        }

        // (e) Fallback to PE embedded icon when allowed for non-default paths
        if (allowEmbeddedExeFallback && !isDefaultRef) {
            loadEmbeddedExeIcon(targetW, targetH)?.let { return it to true }
        }

        return null to false
    }

    private fun resolveTitleBarIcon(hwnd: HWND): HICON? {
        val owned = ownedWindowIconsByHwnd[hwnd.toLong()]
        return owned?.second ?: owned?.first ?: appIconSmall ?: appIconBig
    }

    private fun destroyOwnedIconPair(
        pair: Pair<HICON?, HICON?>?,
        keepA: HICON? = null,
        keepB: HICON? = null
    ) {
        if (pair == null) return
        val defaultSysIcon = LoadIconW(null, IDI_APPLICATION)
        val first = pair.first
        val second = pair.second
        if (first != null &&
            first !== keepA &&
            first !== keepB &&
            first !== appIconBig &&
            first !== appIconSmall &&
            first !== defaultSysIcon
        ) {
            DestroyIcon(first)
        }
        if (second != null &&
            second !== first &&
            second !== keepA &&
            second !== keepB &&
            second !== appIconBig &&
            second !== appIconSmall &&
            second !== defaultSysIcon
        ) {
            DestroyIcon(second)
        }
    }

    private fun applyWindowIcons(hwnd: HWND, iconPath: String?) {
        if (IsWindow(hwnd) == 0) return
        ensureDefaultAppIconsLoaded("icon.ico")
        val bigW = GetSystemMetrics(SM_CXICON).coerceAtLeast(32)
        val bigH = GetSystemMetrics(SM_CYICON).coerceAtLeast(32)
        val smallW = GetSystemMetrics(SM_CXSMICON).coerceAtLeast(16)
        val smallH = GetSystemMetrics(SM_CYSMICON).coerceAtLeast(16)

        val trimmed = iconPath?.trim().orEmpty()
        val resolvedBigPair: Pair<HICON?, Boolean>
        val resolvedSmallPair: Pair<HICON?, Boolean>
        if (trimmed.isNotEmpty()) {
            resolvedBigPair = resolveHIconForPath(trimmed, bigW, bigH, allowEmbeddedExeFallback = true)
            resolvedSmallPair = resolveHIconForPath(trimmed, smallW, smallH, allowEmbeddedExeFallback = true)
        } else {
            resolvedBigPair = appIconBig to false
            resolvedSmallPair = appIconSmall to false
        }

        val defaultSysIcon = LoadIconW(null, IDI_APPLICATION)
        val sendBig = resolvedBigPair.first ?: appIconBig ?: defaultSysIcon
        val sendSmall = resolvedSmallPair.first ?: appIconSmall ?: appIconBig ?: defaultSysIcon

        val nextOwnedBig = if (resolvedBigPair.second &&
            resolvedBigPair.first != null &&
            resolvedBigPair.first !== appIconBig &&
            resolvedBigPair.first !== defaultSysIcon
        ) {
            resolvedBigPair.first
        } else {
            null
        }
        val nextOwnedSmall = if (resolvedSmallPair.second &&
            resolvedSmallPair.first != null &&
            resolvedSmallPair.first !== appIconSmall &&
            resolvedSmallPair.first !== defaultSysIcon
        ) {
            resolvedSmallPair.first
        } else {
            null
        }

        val hwndKey = hwnd.toLong()
        val prevOwned = ownedWindowIconsByHwnd.remove(hwndKey)
        sendBig?.let { SendMessageW(hwnd, WM_SETICON.toUInt(), ICON_BIG.toULong(), it.toLong()) }
        sendSmall?.let { SendMessageW(hwnd, WM_SETICON.toUInt(), ICON_SMALL.toULong(), it.toLong()) }
        if (nextOwnedBig != null || nextOwnedSmall != null) {
            ownedWindowIconsByHwnd[hwndKey] = nextOwnedBig to nextOwnedSmall
        }
        destroyOwnedIconPair(prevOwned, keepA = sendBig, keepB = sendSmall)
    }

    private fun ensureDefaultAppIconsLoaded(iconPath: String) {
        if (appIconBig != null && appIconSmall != null) return

        val bigW = GetSystemMetrics(SM_CXICON).coerceAtLeast(32)
        val bigH = GetSystemMetrics(SM_CYICON).coerceAtLeast(32)
        val smallW = GetSystemMetrics(SM_CXSMICON).coerceAtLeast(16)
        val smallH = GetSystemMetrics(SM_CYSMICON).coerceAtLeast(16)

        if (appIconBig == null) {
            val (resolvedBig, _) = resolveHIconForPath(iconPath, bigW, bigH, allowEmbeddedExeFallback = true)
            if (resolvedBig != null) {
                appIconBig = resolvedBig
            }
        }
        if (appIconSmall == null) {
            val (resolvedSmall, _) = resolveHIconForPath(iconPath, smallW, smallH, allowEmbeddedExeFallback = true)
            if (resolvedSmall != null) {
                appIconSmall = resolvedSmall
            }
        }
    }

    private fun installDialogAndPopoverHooks() {
        if (dialogHooksInstalled) return
        dialogHooksInstalled = true
        DesktopDialogWindowNativeImpl.onShowDialogHook = { dialog, owner ->
            showNativeDialogWindow(dialog, owner)
        }
        DesktopDialogWindowNativeImpl.onCloseDialogHook = { dialog ->
            closeNativeDialogWindow(dialog)
        }
        DesktopDialogWindowNativeImpl.onTitleChangedHook = { dialog, title ->
            dialogHwnds[dialog]?.let { h ->
                if (IsWindow(h) != 0) SetWindowTextW(h, title)
            }
        }
    }

    private fun ensureHostClassRegistered() {
        if (hostClassRegistered) return
        hostClassRegistered = true
        val hInst = Win32WinMain.hInstance ?: GetModuleHandleW(null)
        memScoped {
            val wc = alloc<WNDCLASSEXW>()
            wc.cbSize = sizeOf<WNDCLASSEXW>().toUInt()
            wc.style = platform.windows.CS_DBLCLKS.toUInt()
            wc.lpfnWndProc = staticCFunction(::win32HostWndProc)
            wc.hInstance = hInst
            wc.hCursor = LoadCursorW(null, IDC_ARROW)
            wc.hIcon = appIconBig ?: LoadIconW(null, IDI_APPLICATION)
            wc.hIconSm = appIconSmall ?: appIconBig ?: LoadIconW(null, IDI_APPLICATION)
            wc.hbrBackground = null
            wc.lpszClassName = HOST_WINDOW_CLASS.wcstr.ptr
            RegisterClassExW(wc.ptr)

            val popWc = alloc<WNDCLASSEXW>()
            popWc.cbSize = sizeOf<WNDCLASSEXW>().toUInt()
            popWc.style = platform.windows.CS_DBLCLKS.toUInt()
            popWc.lpfnWndProc = staticCFunction(::win32HostWndProc)
            popWc.hInstance = hInst
            popWc.hCursor = LoadCursorW(null, IDC_ARROW)
            popWc.hbrBackground = null
            popWc.lpszClassName = POPOVER_WINDOW_CLASS.wcstr.ptr
            RegisterClassExW(popWc.ptr)
        }
    }
}

private fun win32HostWndProc(
    hwnd: HWND?,
    msg: UINT,
    wParam: WPARAM,
    lParam: LPARAM
): LRESULT {
    if (hwnd != null) {
        Win32NativeWindowHost.handleHostWndProc(hwnd, msg, wParam, lParam)?.let { return it }
    }
    return DefWindowProcW(hwnd, msg, wParam, lParam)
}
