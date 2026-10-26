@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.component.dock.DockHeaderAction
import com.nexustools.genericui.core.AbstractComponent
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.widget.DesktopButtonNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopCheckboxNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopSelectNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTextNativeImpl
import com.nexustools.genericui.window.TitleBar
import kotlinx.cinterop.*
import platform.windows.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Unified native Windows titlebar renderer and non-client hit-test coordinator shared across:
 * - Main top-level [`Window`](file:///D:/IdeaProjects/GenericUI/common/src/commonMain/kotlin/com/nexustools/genericui/component/shell/Window.kt) (`WindowsWindowNativeImpl`)
 * - Tool-modal [`PanelWindow`](file:///D:/IdeaProjects/GenericUI/common/src/commonMain/kotlin/com/nexustools/genericui/component/dock/PanelWindow.kt) (`DesktopDockPanelNativeImpl` & tool-modal `WindowsWindowNativeImpl`)
 * - Floating [`WindowsDockWindowNativeImpl`](file:///D:/IdeaProjects/GenericUI/desktop/windows/src/commonMain/kotlin/com/nexustools/genericui/windows/WindowsDockWindowNativeImpl.kt)
 * - In-window [`DialogWindow`](file:///D:/IdeaProjects/GenericUI/common/src/commonMain/kotlin/com/nexustools/genericui/component/dialog/DialogWindow.kt) (`DesktopDialogWindowNativeImpl`)
 *
 * Renders into a 32-bit premultiplied ARGB DIB section (`CreateDIBSection`) so transparent background
 * pixels (`0x00000000`) allow DWM Aero Glass (Vista/7) and Windows 11 Mica backdrops to shine through,
 * while emulating the exact native Windows titlebar theme (Windows 11, Windows 10, Windows 8/8.1,
 * Windows Vista/7 Aero Glass, UxTheme Basic, and Win32 Classic) and placing [TitleBar] components
 * (`leadingComponents`, `centerComponents`, `trailingComponents`) directly overtop of the native titlebar.
 */
object Win32NativeTitleBarRenderer {

    private class IntBox(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom
    }

    private class WindowTitleBarHitMap(
        val titleBarHeight: Int,
        val kind: Win32TitleBarKind,
        val dwmDrawsCaptionButtons: Boolean,
        val iconRect: IntBox?,
        val minimizeButtonRect: IntBox?,
        val maximizeButtonRect: IntBox?,
        val closeButtonRect: IntBox?
    )

    private val hitMapsByHwnd = mutableMapOf<Long, WindowTitleBarHitMap>()
    private val activeStateByHwnd = mutableMapOf<Long, Boolean>()
    private val hoveredNcButtonByHwnd = mutableMapOf<Long, Int>()
    private val pressedNcButtonByHwnd = mutableMapOf<Long, Int>()

    private var cachedFluentIconsFont: HFONT? = null
    private var fluentIconsFontResolved: Boolean = false

    fun isWindowActive(hwnd: HWND?): Boolean {
        if (hwnd == null) return true
        val key = hwnd.toLong()
        activeStateByHwnd[key]?.let { return it }
        val fg = GetForegroundWindow()
        return fg == null || fg == hwnd
    }

    fun setWindowActive(hwnd: HWND?, active: Boolean) {
        if (hwnd == null) return
        activeStateByHwnd[hwnd.toLong()] = active
    }

    fun getHoveredNcButton(hwnd: HWND?): Int =
        if (hwnd != null) hoveredNcButtonByHwnd[hwnd.toLong()] ?: 0 else 0

    fun getPressedNcButton(hwnd: HWND?): Int =
        if (hwnd != null) pressedNcButtonByHwnd[hwnd.toLong()] ?: 0 else 0

    fun clearWindowState(hwnd: HWND?) {
        if (hwnd == null) {
            hitMapsByHwnd.clear()
            activeStateByHwnd.clear()
            hoveredNcButtonByHwnd.clear()
            pressedNcButtonByHwnd.clear()
            Win32DwmUxThemeBridge.clearCachedHwndState(null)
        } else {
            val key = hwnd.toLong()
            hitMapsByHwnd.remove(key)
            activeStateByHwnd.remove(key)
            hoveredNcButtonByHwnd.remove(key)
            pressedNcButtonByHwnd.remove(key)
            Win32DwmUxThemeBridge.clearCachedHwndState(hwnd)
        }
    }

    /**
     * Computes the standard native Windows caption height in pixels for the given [kind]
     * (`STANDARD_WINDOW` uses `SM_CYCAPTION`, `TOOL_WINDOW` uses `SM_CYSMCAPTION`).
     */
    fun getDefaultTitleBarHeightPx(
        isToolWindow: Boolean = false,
        titleBar: TitleBar? = null,
        kind: Win32TitleBarKind = if (isToolWindow) Win32TitleBarKind.TOOL_WINDOW else Win32TitleBarKind.STANDARD_WINDOW
    ): Int {
        titleBar?.heightPx?.let { explicitH ->
            return explicitH.coerceIn(20, 64)
        }
        if (!kind.isTopLevelNonClientFrame) {
            return kind.defaultFallbackHeightPx
        }
        val sysCaption = GetSystemMetrics(if (kind.usesSmallCaptionMetrics) SM_CYSMCAPTION else SM_CYCAPTION)
        if (sysCaption <= 0) {
            return kind.defaultFallbackHeightPx
        }
        val total = if (kind.usesSmallCaptionMetrics) {
            sysCaption + 4
        } else {
            val frameY = GetSystemMetrics(SM_CYSIZEFRAME) + GetSystemMetrics(92 /* SM_CXPADDEDBORDER */)
            sysCaption + frameY.coerceIn(6, 10)
        }
        return total.coerceIn(kind.defaultMinHeightPx, kind.defaultMaxHeightPx)
    }

    /**
     * Synchronizes Win32 window style flags (`WS_MINIMIZEBOX`, `WS_MAXIMIZEBOX`, `WS_EX_TOOLWINDOW`)
     * and system menu `SC_CLOSE` state on [hwnd] to match [titleBar] and [kind].
     */
    fun syncWindowCaptionStyleFlags(
        hwnd: HWND?,
        titleBar: TitleBar?,
        isToolWindow: Boolean = false,
        minimizableOverride: Boolean? = null,
        kind: Win32TitleBarKind = if (isToolWindow) Win32TitleBarKind.TOOL_WINDOW else Win32TitleBarKind.STANDARD_WINDOW
    ) {
        if (hwnd == null || IsWindow(hwnd) == 0) return
        val allowMin = if (!kind.allowMinimizeMaximizeButtons) {
            false
        } else {
            minimizableOverride ?: (titleBar?.showMinimizeButton ?: kind.defaultShowMinimizeButton)
        }
        val allowMax = if (!kind.allowMinimizeMaximizeButtons) {
            false
        } else {
            titleBar?.showMaximizeButton ?: kind.defaultShowMaximizeButton
        }
        val allowClose = titleBar?.showCloseButton ?: kind.defaultShowCloseButton

        var style = GetWindowLongPtrW(hwnd, GWL_STYLE).toInt()
        style = if (allowMin) style or WS_MINIMIZEBOX else style and WS_MINIMIZEBOX.inv()
        style = if (allowMax) style or WS_MAXIMIZEBOX else style and WS_MAXIMIZEBOX.inv()
        SetWindowLongPtrW(hwnd, GWL_STYLE, style.toLong())

        if (kind == Win32TitleBarKind.TOOL_WINDOW) {
            var exStyle = GetWindowLongPtrW(hwnd, GWL_EXSTYLE).toInt()
            if ((exStyle and WS_EX_TOOLWINDOW) == 0 || (exStyle and WS_EX_APPWINDOW) != 0) {
                exStyle = (exStyle or WS_EX_TOOLWINDOW) and WS_EX_APPWINDOW.inv()
                SetWindowLongPtrW(hwnd, GWL_EXSTYLE, exStyle.toLong())
            }
        }

        val hSysMenu = GetSystemMenu(hwnd, 0)
        if (hSysMenu != null) {
            val closeFlags = if (allowClose) {
                (MF_BYCOMMAND or MF_ENABLED).toUInt()
            } else {
                (MF_BYCOMMAND or MF_DISABLED or MF_GRAYED).toUInt()
            }
            EnableMenuItem(hSysMenu, SC_CLOSE.toUInt(), closeFlags)
        }
    }

    /**
     * Handles `WM_NCCALCSIZE` when `wParam != 0`:
     * Delegates to `DefWindowProcW` first so Windows computes the exact native left, right, and bottom
     * resize borders (preserving the native DWM drop shadow, 1px window border, rounded corners, and
     * Aero Snap), then restores `rgrc[0].top` to the top of the window so the custom/extended titlebar
     * occupies `y = 0..titleBarH`.
     */
    fun handleNcCalcSize(hwnd: HWND, wParam: WPARAM, lParam: LPARAM): LRESULT {
        if (wParam == 0uL || lParam == 0L) {
            return DefWindowProcW(hwnd, WM_NCCALCSIZE.toUInt(), wParam, lParam)
        }
        val rcPtr = lParam.toCPointer<RECT>() ?: return 0L
        val origLeft = rcPtr.pointed.left
        val origTop = rcPtr.pointed.top
        val origRight = rcPtr.pointed.right
        val origBottom = rcPtr.pointed.bottom

        val defResult = DefWindowProcW(hwnd, WM_NCCALCSIZE.toUInt(), wParam, lParam)

        if (rcPtr.pointed.right <= rcPtr.pointed.left || rcPtr.pointed.bottom <= rcPtr.pointed.top) {
            rcPtr.pointed.left = origLeft
            rcPtr.pointed.top = origTop
            rcPtr.pointed.right = origRight
            rcPtr.pointed.bottom = origBottom
            return 0L
        }

        if (IsZoomed(hwnd) != 0) {
            val frameY = GetSystemMetrics(SM_CYSIZEFRAME) + GetSystemMetrics(92 /* SM_CXPADDEDBORDER */)
            rcPtr.pointed.top = origTop + frameY.coerceAtLeast(0)
        } else {
            rcPtr.pointed.top = origTop
        }
        return defResult
    }

    /**
     * Performs complete non-client and titlebar hit-testing for `WM_NCHITTEST`.
     *
     * Returns:
     * - `HTCLIENT` (`1`) when the pointer is over an interactive [TitleBar] component (`leadingComponents`,
     *   `centerComponents`, `trailingComponents`) or inside the client body below the titlebar.
     * - `HTSYSMENU` (`3`) when over the top-left window icon.
     * - `HTMINBUTTON` (`8`), `HTMAXBUTTON` (`9`), or `HTCLOSE` (`20`) when over the native caption buttons
     *   (enabling Windows 11's native Snap Layouts flyout on `HTMAXBUTTON`!).
     * - `HTTOP`, `HTTOPLEFT`, `HTTOPRIGHT`, `HTLEFT`, `HTRIGHT`, `HTBOTTOM`, `HTBOTTOMLEFT`, `HTBOTTOMRIGHT`
     *   when on the window's resize edges.
     * - `HTCAPTION` (`2`) when on the draggable titlebar area.
     */
    fun hitTestWindow(
        hwnd: HWND,
        screenX: Int,
        screenY: Int,
        isToolWindow: Boolean,
        hasInteractiveClientRegionAt: (cx: Int, cy: Int) -> Boolean
    ): LRESULT = memScoped {
        val winRect = alloc<RECT>()
        GetWindowRect(hwnd, winRect.ptr)
        val isMaximized = IsZoomed(hwnd) != 0
        val border = 6

        if (!isMaximized) {
            val onLeft = screenX in winRect.left until (winRect.left + border)
            val onRight = screenX in (winRect.right - border)..winRect.right
            val onTop = screenY in winRect.top until (winRect.top + border)
            val onBottom = screenY in (winRect.bottom - border)..winRect.bottom
            when {
                onTop && onLeft -> return@memScoped HTTOPLEFT.toLong()
                onTop && onRight -> return@memScoped HTTOPRIGHT.toLong()
                onBottom && onLeft -> return@memScoped HTBOTTOMLEFT.toLong()
                onBottom && onRight -> return@memScoped HTBOTTOMRIGHT.toLong()
                onLeft -> return@memScoped HTLEFT.toLong()
                onRight -> return@memScoped HTRIGHT.toLong()
                onTop -> return@memScoped HTTOP.toLong()
                onBottom -> return@memScoped HTBOTTOM.toLong()
            }
        }

        val pt = alloc<POINT>()
        pt.x = screenX
        pt.y = screenY
        ScreenToClient(hwnd, pt.ptr)
        val cx = pt.x
        val cy = pt.y

        val hitMap = hitMapsByHwnd[hwnd.toLong()]
        val titleBarH = hitMap?.titleBarHeight ?: getDefaultTitleBarHeightPx(isToolWindow)

        if (cy in 0 until titleBarH) {
            if (hasInteractiveClientRegionAt(cx, cy)) {
                updateHoveredNcButton(hwnd, 0)
                return@memScoped HTCLIENT.toLong()
            }
            if (hitMap != null) {
                if (hitMap.iconRect?.contains(cx, cy) == true) {
                    return@memScoped HTSYSMENU.toLong()
                }
                if (!hitMap.dwmDrawsCaptionButtons) {
                    if (hitMap.closeButtonRect?.contains(cx, cy) == true) {
                        return@memScoped HTCLOSE.toLong()
                    }
                    if (hitMap.maximizeButtonRect?.contains(cx, cy) == true) {
                        return@memScoped HTMAXBUTTON.toLong()
                    }
                    if (hitMap.minimizeButtonRect?.contains(cx, cy) == true) {
                        return@memScoped HTMINBUTTON.toLong()
                    }
                }
            }
            return@memScoped HTCAPTION.toLong()
        }

        return@memScoped HTCLIENT.toLong()
    }

    /**
     * Pure coordinate hit-test helper for unit testing caption button, icon, component, and caption hit zones
     * across [Win32TitleBarKind] variants (`STANDARD_WINDOW`, `TOOL_WINDOW`, `DIALOG_WINDOW`, `DOCKED_PANEL_HEADER`).
     */
    fun hitTestLocalCoordinates(
        cx: Int,
        cy: Int,
        windowWidth: Int,
        titleBarHeight: Int,
        kind: Win32TitleBarKind = Win32TitleBarKind.STANDARD_WINDOW,
        showWindowIcon: Boolean = kind.defaultShowWindowIcon,
        showMinimizeButton: Boolean = kind.defaultShowMinimizeButton,
        showMaximizeButton: Boolean = kind.defaultShowMaximizeButton,
        showCloseButton: Boolean = kind.defaultShowCloseButton,
        captionButtonWidthPx: Int = kind.defaultCaptionButtonWidthPx,
        hasInteractiveComponentAtPoint: Boolean = false
    ): Int {
        if (cy !in 0 until titleBarHeight) return HTCLIENT
        if (hasInteractiveComponentAtPoint) return HTCLIENT
        if (!kind.isTopLevelNonClientFrame) return HTCLIENT
        if (showWindowIcon && cx in 8 until 28 && cy in 4 until (titleBarHeight - 4)) {
            return HTSYSMENU
        }
        var rightCursor = windowWidth
        if (showCloseButton) {
            val left = rightCursor - captionButtonWidthPx
            if (cx in left until rightCursor) return HTCLOSE
            rightCursor = left
        }
        if (kind.allowMinimizeMaximizeButtons && showMaximizeButton) {
            val left = rightCursor - captionButtonWidthPx
            if (cx in left until rightCursor) return HTMAXBUTTON
            rightCursor = left
        }
        if (kind.allowMinimizeMaximizeButtons && showMinimizeButton) {
            val left = rightCursor - captionButtonWidthPx
            if (cx in left until rightCursor) return HTMINBUTTON
        }
        return HTCAPTION
    }

    fun updateHoveredNcButton(hwnd: HWND?, hitCode: Int) {
        if (hwnd == null) return
        val key = hwnd.toLong()
        val norm = if (hitCode == HTMINBUTTON || hitCode == HTMAXBUTTON || hitCode == HTCLOSE) hitCode else 0
        val prev = hoveredNcButtonByHwnd[key] ?: 0
        if (prev != norm) {
            hoveredNcButtonByHwnd[key] = norm
            InvalidateRect(hwnd, null, 0)
        }
    }

    /**
     * Handles `WM_NCMOUSEMOVE` to highlight hovered caption buttons (`HTMINBUTTON`, `HTMAXBUTTON`, `HTCLOSE`).
     */
    fun handleNcMouseMove(hwnd: HWND, hitCode: Int): Boolean {
        updateHoveredNcButton(hwnd, hitCode)
        return hitCode == HTMINBUTTON || hitCode == HTMAXBUTTON || hitCode == HTCLOSE
    }

    /**
     * Handles `WM_NCMOUSELEAVE` to clear caption button hover states.
     */
    fun handleNcMouseLeave(hwnd: HWND) {
        val key = hwnd.toLong()
        val prevHover = hoveredNcButtonByHwnd.remove(key) ?: 0
        val prevPress = pressedNcButtonByHwnd.remove(key) ?: 0
        if (prevHover != 0 || prevPress != 0) {
            InvalidateRect(hwnd, null, 0)
        }
    }

    /**
     * Handles `WM_NCLBUTTONDOWN`:
     * - For `HTMINBUTTON`, `HTMAXBUTTON`, and `HTCLOSE`, records the pressed button and returns `true`
     *   (preventing `DefWindowProcW` from drawing a legacy Win95 button over the titlebar).
     * - For `HTSYSMENU`, opens the native Win32 System Menu.
     */
    fun handleNcLButtonDown(hwnd: HWND, hitCode: Int, screenX: Int, screenY: Int): Boolean {
        val key = hwnd.toLong()
        if (hitCode == HTSYSMENU) {
            showNativeSystemMenu(hwnd, screenX, screenY + 18)
            return true
        }
        if (hitCode == HTMINBUTTON || hitCode == HTMAXBUTTON || hitCode == HTCLOSE) {
            pressedNcButtonByHwnd[key] = hitCode
            hoveredNcButtonByHwnd[key] = hitCode
            InvalidateRect(hwnd, null, 0)
            return true
        }
        return false
    }

    /**
     * Handles `WM_NCLBUTTONUP`:
     * Executes `SC_MINIMIZE`, `SC_MAXIMIZE` / `SC_RESTORE`, or `SC_CLOSE` when releasing over the
     * pressed caption button.
     */
    fun handleNcLButtonUp(hwnd: HWND, hitCode: Int, isMainWindow: Boolean): Boolean {
        val key = hwnd.toLong()
        val pressed = pressedNcButtonByHwnd.remove(key) ?: 0
        if (pressed != 0) {
            InvalidateRect(hwnd, null, 0)
        }
        val effectiveHit = if (hitCode == HTMINBUTTON || hitCode == HTMAXBUTTON || hitCode == HTCLOSE) {
            hitCode
        } else {
            pressed
        }
        when (effectiveHit) {
            HTMINBUTTON -> {
                PostMessageW(hwnd, WM_SYSCOMMAND.toUInt(), SC_MINIMIZE.toULong(), 0L)
                return true
            }
            HTMAXBUTTON -> {
                val cmd = if (IsZoomed(hwnd) != 0) SC_RESTORE else SC_MAXIMIZE
                PostMessageW(hwnd, WM_SYSCOMMAND.toUInt(), cmd.toULong(), 0L)
                return true
            }
            HTCLOSE -> {
                if (isMainWindow) {
                    PostMessageW(hwnd, WM_CLOSE.toUInt(), 0u, 0L)
                } else {
                    ShowWindow(hwnd, SW_HIDE)
                }
                return true
            }
        }
        return false
    }

    /**
     * Opens the native Win32 System Menu (`GetSystemMenu(hwnd, FALSE)` with Restore, Move, Size,
     * Minimize, Maximize, Close) when right-clicking on `HTCAPTION` or clicking `HTSYSMENU`.
     */
    fun showNativeSystemMenu(hwnd: HWND, screenX: Int, screenY: Int) {
        val hSysMenu = GetSystemMenu(hwnd, 0) ?: return
        val isMaximized = IsZoomed(hwnd) != 0
        val style = GetWindowLongPtrW(hwnd, GWL_STYLE).toInt()
        val canMin = (style and WS_MINIMIZEBOX) != 0
        val canMax = (style and WS_MAXIMIZEBOX) != 0

        EnableMenuItem(
            hSysMenu,
            SC_RESTORE.toUInt(),
            (MF_BYCOMMAND or if (isMaximized) MF_ENABLED else (MF_DISABLED or MF_GRAYED)).toUInt()
        )
        EnableMenuItem(
            hSysMenu,
            SC_MOVE.toUInt(),
            (MF_BYCOMMAND or if (!isMaximized) MF_ENABLED else (MF_DISABLED or MF_GRAYED)).toUInt()
        )
        EnableMenuItem(
            hSysMenu,
            SC_SIZE.toUInt(),
            (MF_BYCOMMAND or if (!isMaximized && canMax) MF_ENABLED else (MF_DISABLED or MF_GRAYED)).toUInt()
        )
        EnableMenuItem(
            hSysMenu,
            SC_MINIMIZE.toUInt(),
            (MF_BYCOMMAND or if (canMin) MF_ENABLED else (MF_DISABLED or MF_GRAYED)).toUInt()
        )
        EnableMenuItem(
            hSysMenu,
            SC_MAXIMIZE.toUInt(),
            (MF_BYCOMMAND or if (!isMaximized && canMax) MF_ENABLED else (MF_DISABLED or MF_GRAYED)).toUInt()
        )

        val cmd = TrackPopupMenu(
            hSysMenu,
            (TPM_RETURNCMD or TPM_LEFTALIGN or TPM_TOPALIGN).toUInt(),
            screenX,
            screenY,
            0,
            hwnd,
            null
        )
        if (cmd != 0) {
            PostMessageW(hwnd, WM_SYSCOMMAND.toUInt(), cmd.toULong(), 0L)
        }
    }

    /**
     * Unified native Windows titlebar renderer shared across:
     * - `Window` ([Win32TitleBarKind.STANDARD_WINDOW])
     * - `PanelWindow` / `DockWindow` tool windows ([Win32TitleBarKind.TOOL_WINDOW])
     * - `DialogWindow` / in-window modals ([Win32TitleBarKind.DIALOG_WINDOW])
     * - In-window collapsible panel headers ([Win32TitleBarKind.DOCKED_PANEL_HEADER])
     *
     * Renders into a 32-bit premultiplied ARGB DIB section (`CreateDIBSection`) at `(left, top, right, top + height)`,
     * embeds [TitleBar] components (`leadingComponents`, `centerComponents`, `trailingComponents`) and [headerActions]
     * via the shared [renderEmbeddedTitleBarComponents] pipeline, and blits onto [targetHdc].
     */
    fun renderWindowTitleBar(
        hwnd: HWND,
        targetHdc: HDC?,
        left: Int = 0,
        top: Int = 0,
        right: Int,
        titleBarHeight: Int,
        windowTitle: String,
        titleBar: TitleBar?,
        isToolWindow: Boolean = false,
        isInWindowModalHeader: Boolean = false,
        kind: Win32TitleBarKind = when {
            isInWindowModalHeader -> Win32TitleBarKind.DIALOG_WINDOW
            isToolWindow -> Win32TitleBarKind.TOOL_WINDOW
            else -> Win32TitleBarKind.STANDARD_WINDOW
        },
        appIcon: HICON? = null,
        leadingPrefixGlyph: String? = null,
        headerActions: List<DockHeaderAction> = emptyList(),
        fontSmall: HFONT?,
        fontCaption: HFONT?,
        hoveredWidget: DesktopNativeWidget?,
        pressedWidget: DesktopNativeWidget?,
        isRectHoveredFn: (left: Int, top: Int, right: Int, bottom: Int) -> Boolean,
        registerHitRegionFn: (
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            tooltip: String?,
            widget: DesktopNativeWidget?,
            onClick: (mx: Int, my: Int) -> Unit
        ) -> Unit,
        openSelectPopupFn: (selectImpl: DesktopSelectNativeImpl<*>, mx: Int, my: Int) -> Unit,
        onHeaderBackgroundClicked: (() -> Unit)? = null,
        onCloseClicked: () -> Unit = {
            if (kind == Win32TitleBarKind.TOOL_WINDOW || isToolWindow) {
                ShowWindow(hwnd, SW_HIDE)
            } else {
                PostMessageW(hwnd, WM_CLOSE.toUInt(), 0u, 0L)
            }
        },
        onMaximizeRestoreClicked: () -> Unit = {
            ShowWindow(hwnd, if (IsZoomed(hwnd) != 0) SW_RESTORE else SW_MAXIMIZE)
        },
        onMinimizeClicked: () -> Unit = {
            ShowWindow(hwnd, SW_MINIMIZE)
        }
    ): Int = memScoped {
        val width = (right - left).coerceAtLeast(1)
        val height = titleBarHeight.coerceAtLeast(1)
        if (targetHdc == null || width <= 0 || height <= 0) return@memScoped height

        val effectiveTool = kind.usesSmallCaptionMetrics || isToolWindow
        val isTopLevelFrame = kind.isTopLevelNonClientFrame && !isInWindowModalHeader
        val windowActive = if (!isTopLevelFrame) true else isWindowActive(hwnd)
        val palette = Win32DwmUxThemeBridge.resolveTitleBarPalette(
            hwnd = if (isTopLevelFrame) hwnd else null,
            isWindowActive = windowActive,
            isToolWindow = effectiveTool,
            kind = kind
        )

        if (isTopLevelFrame) {
            syncWindowCaptionStyleFlags(
                hwnd = hwnd,
                titleBar = titleBar,
                isToolWindow = effectiveTool,
                kind = kind
            )
            Win32DwmUxThemeBridge.applyWindowDwmAttributes(
                hwnd = hwnd,
                isToolWindow = effectiveTool,
                extendTopPx = if (top == 0) height else 0,
                isDark = palette.isDark,
                kind = kind
            )
        }

        // Create a 32-bit top-down ARGB DIB section for `[0..width, 0..height]`
        val bmi = alloc<BITMAPINFO>()
        bmi.bmiHeader.biSize = sizeOf<BITMAPINFOHEADER>().toUInt()
        bmi.bmiHeader.biWidth = width
        bmi.bmiHeader.biHeight = -height // top-down
        bmi.bmiHeader.biPlanes = 1u
        bmi.bmiHeader.biBitCount = 32u
        bmi.bmiHeader.biCompression = BI_RGB.toUInt()

        val bitsPtrVar = alloc<COpaquePointerVar>()
        val dibDc = CreateCompatibleDC(targetHdc) ?: return@memScoped height
        val dibBmp = CreateDIBSection(targetHdc, bmi.ptr, DIB_RGB_COLORS.toUInt(), bitsPtrVar.ptr, null, 0u)
        val pixelsPtr = bitsPtrVar.value?.reinterpret<UIntVar>()
        if (dibBmp == null || pixelsPtr == null) {
            if (dibBmp != null) DeleteObject(dibBmp)
            DeleteDC(dibDc)
            return@memScoped height
        }

        val effectiveCaptionFont = if (kind.usesSmallCaptionMetrics) {
            fontSmall ?: fontCaption
        } else {
            fontCaption ?: fontSmall
        }
        val oldBmp = SelectObject(dibDc, dibBmp)
        val oldFont = SelectObject(dibDc, effectiveCaptionFont)
        SetBkMode(dibDc, TRANSPARENT)

        val totalPixels = width * height
        val transparentDwm = palette.useTransparentDwmBackdrop && isTopLevelFrame && top == 0
        val dwmButtons = palette.dwmDrawsCaptionButtons && isTopLevelFrame && top == 0

        // 1. Paint Native Titlebar Backdrop into the 32-bit ARGB DIB
        when {
            transparentDwm -> {
                for (i in 0 until totalPixels) {
                    pixelsPtr[i] = 0x00000000u
                }
            }
            palette.generation == Win32VisualStyleGeneration.UXTHEME_BASIC && isTopLevelFrame -> {
                preTagDibRect(pixelsPtr, width, height, 0, 0, width, height)
                val paintedByUxTheme = Win32DwmUxThemeBridge.drawUxThemeCaptionBackground(
                    hwnd = hwnd,
                    hdc = dibDc,
                    width = width,
                    height = height,
                    isWindowActive = windowActive,
                    isToolWindow = effectiveTool,
                    kind = kind
                )
                if (paintedByUxTheme) {
                    finalizeGdiDrawnRectAlpha(pixelsPtr, width, height, 0, 0, width, height, false)
                } else {
                    fillDibSolidColorRef(pixelsPtr, width, height, 0, 0, width, height, palette.captionBgColor, 255)
                }
            }
            palette.generation == Win32VisualStyleGeneration.WIN32_CLASSIC && palette.captionBgGradientEndColor != null -> {
                fillDibHorizontalGradientColorRef(
                    pixelsPtr = pixelsPtr,
                    width = width,
                    height = height,
                    startColorRef = palette.captionBgColor,
                    endColorRef = palette.captionBgGradientEndColor
                )
            }
            else -> {
                val fillRight = if (dwmButtons) {
                    (width - palette.nativeCaptionButtonsRightInsetPx).coerceAtLeast(0)
                } else {
                    width
                }
                fillDibSolidColorRef(pixelsPtr, width, height, 0, 0, fillRight, height, palette.captionBgColor, 255)
                if (fillRight < width) {
                    for (py in 0 until height) {
                        val rowOff = py * width
                        for (px in fillRight until width) {
                            pixelsPtr[rowOff + px] = 0x00000000u
                        }
                    }
                }
            }
        }

        if (palette.bottomBorderColor != null) {
            fillDibSolidColorRef(pixelsPtr, width, height, 0, height - 1, width, height, palette.bottomBorderColor, 255)
        } else if (kind == Win32TitleBarKind.TOOL_WINDOW || kind == Win32TitleBarKind.DOCKED_PANEL_HEADER) {
            // Subtle 1px bottom separator on tool windows and docked panel headers
            blendDibRectColorRef(
                pixelsPtr = pixelsPtr,
                surfW = width,
                surfH = height,
                left = 0,
                top = height - 1,
                right = width,
                bottom = height,
                colorRef = palette.componentBorderColor,
                alpha = 32
            )
        }

        // 2. Render Native Caption Buttons on the Right
        // Note: `TOOL_WINDOW` (`PanelWindow` / `DockWindow`) only renders the compact tool Close button
        // (`WP_SMALLCLOSEBUTTON`, 28px wide, 8x8 glyph) and never renders Minimize or Maximize buttons.
        val showClose = titleBar?.showCloseButton ?: kind.defaultShowCloseButton
        val showMax = if (!kind.allowMinimizeMaximizeButtons) {
            false
        } else {
            titleBar?.showMaximizeButton ?: kind.defaultShowMaximizeButton
        }
        val showMin = if (!kind.allowMinimizeMaximizeButtons) {
            false
        } else {
            titleBar?.showMinimizeButton ?: kind.defaultShowMinimizeButton
        }

        var rightCursor = width
        var closeBox: IntBox? = null
        var maxBox: IntBox? = null
        var minBox: IntBox? = null

        val hoveredNc = if (!isTopLevelFrame) 0 else getHoveredNcButton(hwnd)
        val pressedNc = if (!isTopLevelFrame) 0 else getPressedNcButton(hwnd)

        if (dwmButtons) {
            rightCursor = (width - palette.nativeCaptionButtonsRightInsetPx).coerceAtLeast(48)
        } else {
            val btnW = palette.captionButtonWidthPx
            val btnTop = if (palette.generation == Win32VisualStyleGeneration.WIN32_CLASSIC) 3 else 0
            val btnBottom = if (palette.generation == Win32VisualStyleGeneration.WIN32_CLASSIC) height - 3 else height

            if (showClose) {
                val bRight = rightCursor
                val bLeft = (bRight - btnW).coerceAtLeast(0)
                val absLeft = left + bLeft
                val absRight = left + bRight
                val absTop = top + btnTop
                val absBottom = top + btnBottom
                closeBox = IntBox(absLeft, absTop, absRight, absBottom)
                val isHovered = hoveredNc == HTCLOSE || isRectHoveredFn(absLeft, absTop, absRight, absBottom)
                val isPressed = pressedNc == HTCLOSE
                renderNativeCaptionButton(
                    hwnd = hwnd,
                    dibDc = dibDc,
                    pixelsPtr = pixelsPtr,
                    surfW = width,
                    surfH = height,
                    palette = palette,
                    kind = kind,
                    hitType = HTCLOSE,
                    isMaximized = false,
                    isHovered = isHovered,
                    isPressed = isPressed,
                    left = bLeft,
                    top = btnTop,
                    right = bRight,
                    bottom = btnBottom
                )
                registerHitRegionFn(absLeft, absTop, absRight, absBottom, "Close", null) { _, _ ->
                    onCloseClicked()
                }
                rightCursor = bLeft
            }

            if (showMax) {
                val bRight = rightCursor
                val bLeft = (bRight - btnW).coerceAtLeast(0)
                val absLeft = left + bLeft
                val absRight = left + bRight
                val absTop = top + btnTop
                val absBottom = top + btnBottom
                maxBox = IntBox(absLeft, absTop, absRight, absBottom)
                val isZoomed = IsZoomed(hwnd) != 0
                val isHovered = hoveredNc == HTMAXBUTTON || isRectHoveredFn(absLeft, absTop, absRight, absBottom)
                val isPressed = pressedNc == HTMAXBUTTON
                renderNativeCaptionButton(
                    hwnd = hwnd,
                    dibDc = dibDc,
                    pixelsPtr = pixelsPtr,
                    surfW = width,
                    surfH = height,
                    palette = palette,
                    kind = kind,
                    hitType = HTMAXBUTTON,
                    isMaximized = isZoomed,
                    isHovered = isHovered,
                    isPressed = isPressed,
                    left = bLeft,
                    top = btnTop,
                    right = bRight,
                    bottom = btnBottom
                )
                registerHitRegionFn(
                    absLeft,
                    absTop,
                    absRight,
                    absBottom,
                    if (isZoomed) "Restore" else "Maximize",
                    null
                ) { _, _ ->
                    onMaximizeRestoreClicked()
                }
                rightCursor = bLeft
            }

            if (showMin) {
                val bRight = rightCursor
                val bLeft = (bRight - btnW).coerceAtLeast(0)
                val absLeft = left + bLeft
                val absRight = left + bRight
                val absTop = top + btnTop
                val absBottom = top + btnBottom
                minBox = IntBox(absLeft, absTop, absRight, absBottom)
                val isHovered = hoveredNc == HTMINBUTTON || isRectHoveredFn(absLeft, absTop, absRight, absBottom)
                val isPressed = pressedNc == HTMINBUTTON
                renderNativeCaptionButton(
                    hwnd = hwnd,
                    dibDc = dibDc,
                    pixelsPtr = pixelsPtr,
                    surfW = width,
                    surfH = height,
                    palette = palette,
                    kind = kind,
                    hitType = HTMINBUTTON,
                    isMaximized = false,
                    isHovered = isHovered,
                    isPressed = isPressed,
                    left = bLeft,
                    top = btnTop,
                    right = bRight,
                    bottom = btnBottom
                )
                registerHitRegionFn(absLeft, absTop, absRight, absBottom, "Minimize", null) { _, _ ->
                    onMinimizeClicked()
                }
                rightCursor = bLeft
            }
        }

        rightCursor -= if (kind.usesSmallCaptionMetrics) 4 else 6

        // 3. Render Window Icon (`HTSYSMENU`) or Leading Prefix Glyph (e.g. Docked Panel Collapse Chevron)
        var leftCursor = if (kind.usesSmallCaptionMetrics) 8 else 10
        var iconBox: IntBox? = null
        val showIcon = titleBar?.showWindowIcon ?: kind.defaultShowWindowIcon

        if (showIcon && appIcon != null) {
            val iconSize = if (kind.usesSmallCaptionMetrics) 14 else 16
            val iconY = max(2, (height - iconSize) / 2)
            preTagDibRect(pixelsPtr, width, height, leftCursor, iconY, leftCursor + iconSize, iconY + iconSize)
            DrawIconEx(dibDc, leftCursor, iconY, appIcon, iconSize, iconSize, 0u, null, DI_NORMAL.toUInt())
            finalizeGdiDrawnRectAlpha(pixelsPtr, width, height, leftCursor, iconY, leftCursor + iconSize, iconY + iconSize, transparentDwm)
            val absIconLeft = left + leftCursor
            iconBox = IntBox(absIconLeft - 2, top, absIconLeft + iconSize + 4, top + height)
            leftCursor += iconSize + 6
        } else if (!leadingPrefixGlyph.isNullOrBlank()) {
            SelectObject(dibDc, fontSmall ?: effectiveCaptionFont)
            val glyphW = 14
            drawAlphaPreservedText(
                dibDc = dibDc,
                pixelsPtr = pixelsPtr,
                surfW = width,
                surfH = height,
                text = leadingPrefixGlyph,
                left = leftCursor,
                top = 0,
                right = leftCursor + glyphW,
                bottom = height,
                colorRef = palette.captionSecondaryTextColor,
                center = false,
                transparentDwm = transparentDwm,
                aeroGlow = palette.drawAeroTextGlow
            )
            leftCursor += glyphW + 2
        }

        // 4. Shared Component Embedding Pipeline (`leadingComponents`, `trailingComponents`, `headerActions`, `centerComponents`)
        val (updatedLeftCursor, updatedRightCursor) = renderEmbeddedTitleBarComponents(
            dibDc = dibDc,
            pixelsPtr = pixelsPtr,
            surfW = width,
            surfH = height,
            originLeft = left,
            originTop = top,
            initialLeftCursor = leftCursor,
            initialRightCursor = rightCursor,
            kind = kind,
            palette = palette,
            transparentDwm = transparentDwm,
            fontSmall = fontSmall ?: effectiveCaptionFont,
            titleBar = titleBar,
            headerActions = headerActions,
            hoveredWidget = hoveredWidget,
            pressedWidget = pressedWidget,
            isRectHoveredFn = isRectHoveredFn,
            registerHitRegionFn = registerHitRegionFn,
            openSelectPopupFn = openSelectPopupFn
        )
        leftCursor = updatedLeftCursor
        rightCursor = updatedRightCursor

        // 5. Render Window / Tool / Panel Title Text in the Remaining Span `[leftCursor .. rightCursor]`
        val showTitle = titleBar?.showWindowTitle ?: true
        if (showTitle && windowTitle.isNotBlank() && rightCursor > leftCursor + 16) {
            SelectObject(dibDc, effectiveCaptionFont)
            val titleLeft = leftCursor + 2
            val titleRight = rightCursor - 2
            drawAlphaPreservedText(
                dibDc = dibDc,
                pixelsPtr = pixelsPtr,
                surfW = width,
                surfH = height,
                text = windowTitle,
                left = titleLeft,
                top = 0,
                right = titleRight,
                bottom = height,
                colorRef = palette.captionTextColor,
                center = palette.centerWindowTitle,
                transparentDwm = transparentDwm,
                aeroGlow = palette.drawAeroTextGlow
            )
        }

        // Optional background click handler (e.g. for toggling collapsible docked panel headers)
        if (onHeaderBackgroundClicked != null && rightCursor > left) {
            registerHitRegionFn(left, top, left + rightCursor, top + height, windowTitle, null) { _, _ ->
                onHeaderBackgroundClicked()
            }
        }

        // Record hit map for `WM_NCHITTEST`
        if (isTopLevelFrame) {
            hitMapsByHwnd[hwnd.toLong()] = WindowTitleBarHitMap(
                titleBarHeight = height,
                kind = kind,
                dwmDrawsCaptionButtons = dwmButtons,
                iconRect = iconBox,
                minimizeButtonRect = minBox,
                maximizeButtonRect = maxBox,
                closeButtonRect = closeBox
            )
        }

        // 6. Blit the 32-bit ARGB titlebar DIB to targetHdc
        BitBlt(targetHdc, left, top, width, height, dibDc, 0, 0, SRCCOPY)

        SelectObject(dibDc, oldFont)
        SelectObject(dibDc, oldBmp)
        DeleteObject(dibBmp)
        DeleteDC(dibDc)

        return@memScoped height
    }

    /**
     * Abstract, reusable component-embedding pipeline shared across all [Win32TitleBarKind] types
     * (`STANDARD_WINDOW`, `TOOL_WINDOW`, `DIALOG_WINDOW`, and `DOCKED_PANEL_HEADER`).
     *
     * Lays out and renders:
     * 1. [TitleBar.leadingComponents] from `initialLeftCursor` rightward.
     * 2. [TitleBar.trailingComponents] and [headerActions] from `initialRightCursor` leftward.
     * 3. [TitleBar.centerComponents] inside the remaining titlebar span.
     *
     * @return Updated `(leftCursor, rightCursor)` pair bounding the remaining title text region.
     */
    fun renderEmbeddedTitleBarComponents(
        dibDc: HDC,
        pixelsPtr: CPointer<UIntVar>,
        surfW: Int,
        surfH: Int,
        originLeft: Int,
        originTop: Int,
        initialLeftCursor: Int,
        initialRightCursor: Int,
        kind: Win32TitleBarKind,
        palette: Win32TitleBarThemePalette,
        transparentDwm: Boolean,
        fontSmall: HFONT?,
        titleBar: TitleBar?,
        headerActions: List<DockHeaderAction>,
        hoveredWidget: DesktopNativeWidget?,
        pressedWidget: DesktopNativeWidget?,
        isRectHoveredFn: (left: Int, top: Int, right: Int, bottom: Int) -> Boolean,
        registerHitRegionFn: (
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            tooltip: String?,
            widget: DesktopNativeWidget?,
            onClick: (mx: Int, my: Int) -> Unit
        ) -> Unit,
        openSelectPopupFn: (selectImpl: DesktopSelectNativeImpl<*>, mx: Int, my: Int) -> Unit
    ): Pair<Int, Int> {
        var leftCursor = initialLeftCursor
        var rightCursor = initialRightCursor
        val targetCompH = min(kind.defaultComponentHeightPx, max(16, surfH - 4))
        val compTop = max(2, (surfH - targetCompH) / 2)
        val compBottom = min(surfH - 2, compTop + targetCompH)
        val spacing = if (kind.usesSmallCaptionMetrics) 3 else 4

        // 1. Leading components (`TitleBar.leadingComponents`, e.g. "← Back" navigation button)
        if (titleBar != null) {
            for (comp in titleBar.leadingComponents) {
                if (!comp.isVisible) continue
                val usedW = renderNativeTitleBarComponent(
                    dibDc = dibDc,
                    pixelsPtr = pixelsPtr,
                    surfW = surfW,
                    surfH = surfH,
                    originLeft = originLeft,
                    originTop = originTop,
                    palette = palette,
                    transparentDwm = transparentDwm,
                    fontSmall = fontSmall,
                    comp = comp,
                    x = leftCursor,
                    compTop = compTop,
                    compBottom = compBottom,
                    alignFromRight = false,
                    hoveredWidget = hoveredWidget,
                    pressedWidget = pressedWidget,
                    isRectHoveredFn = isRectHoveredFn,
                    registerHitRegionFn = registerHitRegionFn,
                    openSelectPopupFn = openSelectPopupFn
                )
                if (usedW > 0) {
                    leftCursor += usedW + spacing
                }
            }
        }

        // 2. Trailing components (`TitleBar.trailingComponents`)
        if (titleBar != null) {
            for (comp in titleBar.trailingComponents.asReversed()) {
                if (!comp.isVisible) continue
                val usedW = renderNativeTitleBarComponent(
                    dibDc = dibDc,
                    pixelsPtr = pixelsPtr,
                    surfW = surfW,
                    surfH = surfH,
                    originLeft = originLeft,
                    originTop = originTop,
                    palette = palette,
                    transparentDwm = transparentDwm,
                    fontSmall = fontSmall,
                    comp = comp,
                    x = rightCursor,
                    compTop = compTop,
                    compBottom = compBottom,
                    alignFromRight = true,
                    hoveredWidget = hoveredWidget,
                    pressedWidget = pressedWidget,
                    isRectHoveredFn = isRectHoveredFn,
                    registerHitRegionFn = registerHitRegionFn,
                    openSelectPopupFn = openSelectPopupFn
                )
                if (usedW > 0) {
                    rightCursor -= (usedW + spacing)
                }
            }
        }

        // 3. Panel / tool header actions (`DockHeaderAction`)
        for (action in headerActions.asReversed()) {
            val label = action.label
            val btnW = max(if (kind.usesSmallCaptionMetrics) 20 else 24, label.length * 6 + 10)
            val bLeft = rightCursor - btnW
            if (bLeft > leftCursor + 36) {
                val absLeft = originLeft + bLeft
                val absRight = originLeft + rightCursor
                val absTop = originTop + compTop
                val absBottom = originTop + compBottom
                val isHovered = isRectHoveredFn(absLeft, absTop, absRight, absBottom)
                drawNativeTitleBarButtonSurface(
                    dibDc = dibDc,
                    pixelsPtr = pixelsPtr,
                    surfW = surfW,
                    surfH = surfH,
                    palette = palette,
                    transparentDwm = transparentDwm,
                    font = fontSmall,
                    label = label,
                    left = bLeft,
                    top = compTop,
                    right = rightCursor,
                    bottom = compBottom,
                    isHovered = isHovered,
                    isPressed = false,
                    customBgColorRef = null
                )
                registerHitRegionFn(
                    absLeft,
                    absTop,
                    absRight,
                    absBottom,
                    action.tooltip?.takeIf { it.isNotBlank() && it != label },
                    null
                ) { _, _ ->
                    action.onClick()
                }
                rightCursor = bLeft - spacing
            }
        }

        // 4. Center components (`TitleBar.centerComponents`)
        if (titleBar != null && titleBar.centerComponents.isNotEmpty()) {
            for (comp in titleBar.centerComponents.asReversed()) {
                if (!comp.isVisible) continue
                val usedW = renderNativeTitleBarComponent(
                    dibDc = dibDc,
                    pixelsPtr = pixelsPtr,
                    surfW = surfW,
                    surfH = surfH,
                    originLeft = originLeft,
                    originTop = originTop,
                    palette = palette,
                    transparentDwm = transparentDwm,
                    fontSmall = fontSmall,
                    comp = comp,
                    x = rightCursor,
                    compTop = compTop,
                    compBottom = compBottom,
                    alignFromRight = true,
                    hoveredWidget = hoveredWidget,
                    pressedWidget = pressedWidget,
                    isRectHoveredFn = isRectHoveredFn,
                    registerHitRegionFn = registerHitRegionFn,
                    openSelectPopupFn = openSelectPopupFn
                )
                if (usedW > 0) {
                    rightCursor -= (usedW + spacing)
                }
            }
        }

        return leftCursor to rightCursor
    }

    /**
     * Renders a single [AbstractComponent] (`Button`, `Select`, `CheckBox`, `Text`, or nested container)
     * inside the native Windows titlebar using native caption colors and subtle translucent hover/press states.
     */
    private fun renderNativeTitleBarComponent(
        dibDc: HDC,
        pixelsPtr: CPointer<UIntVar>,
        surfW: Int,
        surfH: Int,
        originLeft: Int,
        originTop: Int,
        palette: Win32TitleBarThemePalette,
        transparentDwm: Boolean,
        fontSmall: HFONT?,
        comp: AbstractComponent,
        x: Int,
        compTop: Int,
        compBottom: Int,
        alignFromRight: Boolean,
        hoveredWidget: DesktopNativeWidget?,
        pressedWidget: DesktopNativeWidget?,
        isRectHoveredFn: (left: Int, top: Int, right: Int, bottom: Int) -> Boolean,
        registerHitRegionFn: (
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            tooltip: String?,
            widget: DesktopNativeWidget?,
            onClick: (mx: Int, my: Int) -> Unit
        ) -> Unit,
        openSelectPopupFn: (selectImpl: DesktopSelectNativeImpl<*>, mx: Int, my: Int) -> Unit
    ): Int {
        val impl = comp.nativeImpl ?: comp.ensureNativeImpl()
        val compH = max(18, compBottom - compTop)

        when (impl) {
            is DesktopButtonNativeImpl<*> -> {
                val label = impl.getText()
                val icon = impl.getIcon()
                val isIconOnly = !icon.isNullOrBlank() && label.isBlank()
                val textDisplay = if (isIconOnly) {
                    icon.takeLast(2)
                } else if (!icon.isNullOrBlank() && icon.length <= 2) {
                    "$icon $label"
                } else {
                    label
                }
                val w = when {
                    isIconOnly -> compH + 6
                    else -> max(44, textDisplay.length * 6 + 16)
                }
                val bLeft = if (alignFromRight) x - w else x
                val bRight = bLeft + w
                if (bLeft < 0 || bRight > surfW) return 0

                val absLeft = originLeft + bLeft
                val absRight = originLeft + bRight
                val absTop = originTop + compTop
                val absBottom = originTop + compBottom

                val isHovered = hoveredWidget == impl.nativeWidget || isRectHoveredFn(absLeft, absTop, absRight, absBottom)
                val isPressed = pressedWidget == impl.nativeWidget
                val customTint = (impl.getEffectiveTintColor() ?: impl.getEffectiveBackgroundColor())?.let {
                    Win32DwmUxThemeBridge.colorRefRgb(it.r, it.g, it.b)
                }

                drawNativeTitleBarButtonSurface(
                    dibDc = dibDc,
                    pixelsPtr = pixelsPtr,
                    surfW = surfW,
                    surfH = surfH,
                    palette = palette,
                    transparentDwm = transparentDwm,
                    font = fontSmall,
                    label = textDisplay,
                    left = bLeft,
                    top = compTop,
                    right = bRight,
                    bottom = compBottom,
                    isHovered = isHovered,
                    isPressed = isPressed,
                    customBgColorRef = customTint
                )

                registerHitRegionFn(
                    absLeft,
                    absTop,
                    absRight,
                    absBottom,
                    impl.getTooltipText()?.takeIf { it.isNotBlank() && (isIconOnly || it != label) },
                    impl.nativeWidget
                ) { _, _ ->
                    impl.dispatchClick()
                }
                return w
            }

            is DesktopSelectNativeImpl<*> -> {
                val curVal = impl.getSelectedValue() ?: ""
                val curLabel = impl.getOptions().firstOrNull { it.first == curVal }?.second ?: curVal
                val w = max(68, curLabel.length * 6 + 24)
                val bLeft = if (alignFromRight) x - w else x
                val bRight = bLeft + w
                if (bLeft < 0 || bRight > surfW) return 0

                val absLeft = originLeft + bLeft
                val absRight = originLeft + bRight
                val absTop = originTop + compTop
                val absBottom = originTop + compBottom
                val isHovered = hoveredWidget == impl.nativeWidget || isRectHoveredFn(absLeft, absTop, absRight, absBottom)

                drawNativeTitleBarButtonSurface(
                    dibDc = dibDc,
                    pixelsPtr = pixelsPtr,
                    surfW = surfW,
                    surfH = surfH,
                    palette = palette,
                    transparentDwm = transparentDwm,
                    font = fontSmall,
                    label = "$curLabel ▾",
                    left = bLeft,
                    top = compTop,
                    right = bRight,
                    bottom = compBottom,
                    isHovered = isHovered,
                    isPressed = false,
                    customBgColorRef = null,
                    forceSubtleBorder = true
                )

                registerHitRegionFn(
                    absLeft,
                    absTop,
                    absRight,
                    absBottom,
                    impl.getTooltipText(),
                    impl.nativeWidget
                ) { mx, my ->
                    openSelectPopupFn(impl, mx, my)
                }
                return w
            }

            is DesktopCheckboxNativeImpl<*> -> {
                val label = impl.getLabel()
                val mark = if (impl.isChecked()) "☑" else "☐"
                val display = if (label.isNotBlank()) "$mark $label" else mark
                val w = max(42, display.length * 6 + 14)
                val bLeft = if (alignFromRight) x - w else x
                val bRight = bLeft + w
                if (bLeft < 0 || bRight > surfW) return 0

                val absLeft = originLeft + bLeft
                val absRight = originLeft + bRight
                val absTop = originTop + compTop
                val absBottom = originTop + compBottom
                val isHovered = hoveredWidget == impl.nativeWidget || isRectHoveredFn(absLeft, absTop, absRight, absBottom)

                drawNativeTitleBarButtonSurface(
                    dibDc = dibDc,
                    pixelsPtr = pixelsPtr,
                    surfW = surfW,
                    surfH = surfH,
                    palette = palette,
                    transparentDwm = transparentDwm,
                    font = fontSmall,
                    label = display,
                    left = bLeft,
                    top = compTop,
                    right = bRight,
                    bottom = compBottom,
                    isHovered = isHovered,
                    isPressed = false,
                    customBgColorRef = null
                )

                registerHitRegionFn(
                    absLeft,
                    absTop,
                    absRight,
                    absBottom,
                    impl.getTooltipText(),
                    impl.nativeWidget
                ) { _, _ ->
                    impl.setChecked(!impl.isChecked())
                }
                return w
            }

            is DesktopTextNativeImpl<*> -> {
                val text = impl.getText()
                if (text.isBlank()) return 0
                val w = max(28, text.length * 6 + 8)
                val bLeft = if (alignFromRight) x - w else x
                val bRight = bLeft + w
                if (bLeft < 0 || bRight > surfW) return 0

                SelectObject(dibDc, fontSmall)
                drawAlphaPreservedText(
                    dibDc = dibDc,
                    pixelsPtr = pixelsPtr,
                    surfW = surfW,
                    surfH = surfH,
                    text = text,
                    left = bLeft,
                    top = compTop,
                    right = bRight,
                    bottom = compBottom,
                    colorRef = palette.captionSecondaryTextColor,
                    center = true,
                    transparentDwm = transparentDwm,
                    aeroGlow = palette.drawAeroTextGlow
                )
                return w
            }

            else -> return 0
        }
    }

    private fun drawNativeTitleBarButtonSurface(
        dibDc: HDC,
        pixelsPtr: CPointer<UIntVar>,
        surfW: Int,
        surfH: Int,
        palette: Win32TitleBarThemePalette,
        transparentDwm: Boolean,
        font: HFONT?,
        label: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        isHovered: Boolean,
        isPressed: Boolean,
        customBgColorRef: UInt?,
        forceSubtleBorder: Boolean = false
    ) {
        val fillCol: UInt
        val fillAlpha: Int
        when {
            customBgColorRef != null -> {
                fillCol = customBgColorRef
                fillAlpha = when {
                    isPressed -> 230
                    isHovered -> 210
                    else -> 175
                }
            }
            isPressed -> {
                fillCol = palette.componentPressBgColor
                fillAlpha = palette.componentPressBgAlpha
            }
            isHovered -> {
                fillCol = palette.componentHoverBgColor
                fillAlpha = palette.componentHoverBgAlpha
            }
            else -> {
                fillCol = palette.componentRestBgColor
                fillAlpha = palette.componentRestBgAlpha
            }
        }

        if (fillAlpha > 0) {
            blendDibRoundedRectColorRef(
                pixelsPtr = pixelsPtr,
                surfW = surfW,
                surfH = surfH,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                radius = 4,
                colorRef = fillCol,
                alpha = fillAlpha
            )
        }

        if (isHovered || isPressed || forceSubtleBorder || palette.drawAeroTextGlow) {
            blendDibRoundedRectBorderColorRef(
                pixelsPtr = pixelsPtr,
                surfW = surfW,
                surfH = surfH,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                radius = 4,
                colorRef = palette.componentBorderColor,
                alpha = if (isHovered || isPressed) max(64, palette.componentBorderAlpha) else palette.componentBorderAlpha
            )
        }

        val textCol = if (customBgColorRef != null && Win32DwmUxThemeBridge.computeColorRefLuminance(customBgColorRef) < 0.52f) {
            Win32DwmUxThemeBridge.colorRefRgb(255, 255, 255)
        } else {
            palette.captionTextColor
        }

        SelectObject(dibDc, font)
        drawAlphaPreservedText(
            dibDc = dibDc,
            pixelsPtr = pixelsPtr,
            surfW = surfW,
            surfH = surfH,
            text = label,
            left = left + 4,
            top = top + (if (isPressed) 1 else 0),
            right = right - 4,
            bottom = bottom,
            colorRef = textCol,
            center = true,
            transparentDwm = transparentDwm,
            aeroGlow = palette.drawAeroTextGlow && customBgColorRef == null
        )
    }

    /**
     * Renders a native Minimize, Maximize/Restore, or Close caption button in `[left..right, top..bottom]`.
     */
    private fun renderNativeCaptionButton(
        hwnd: HWND,
        dibDc: HDC,
        pixelsPtr: CPointer<UIntVar>,
        surfW: Int,
        surfH: Int,
        palette: Win32TitleBarThemePalette,
        kind: Win32TitleBarKind,
        hitType: Int,
        isMaximized: Boolean,
        isHovered: Boolean,
        isPressed: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) = memScoped {
        when (palette.generation) {
            Win32VisualStyleGeneration.UXTHEME_BASIC -> {
                val partId = when (hitType) {
                    HTCLOSE -> kind.uxThemeCloseButtonPartId
                    HTMAXBUTTON -> if (isMaximized) Win32DwmUxThemeBridge.WP_RESTOREBUTTON else Win32DwmUxThemeBridge.WP_MAXBUTTON
                    else -> Win32DwmUxThemeBridge.WP_MINBUTTON
                }
                val stateId = when {
                    isPressed -> Win32DwmUxThemeBridge.CBS_PUSHED
                    isHovered -> Win32DwmUxThemeBridge.CBS_HOT
                    else -> Win32DwmUxThemeBridge.CBS_NORMAL
                }
                preTagDibRect(pixelsPtr, surfW, surfH, left, top, right, bottom)
                val ok = Win32DwmUxThemeBridge.drawUxThemeCaptionButton(
                    hwnd = hwnd,
                    hdc = dibDc,
                    partId = partId,
                    stateId = stateId,
                    left = left,
                    top = top + 2,
                    right = right - 2,
                    bottom = bottom - 2
                )
                if (ok) {
                    finalizeGdiDrawnRectAlpha(pixelsPtr, surfW, surfH, left, top, right, bottom, false)
                    return@memScoped
                }
            }

            Win32VisualStyleGeneration.WIN32_CLASSIC -> {
                val rc = alloc<RECT>()
                rc.left = left
                rc.top = top
                rc.right = right
                rc.bottom = bottom
                var state = when (hitType) {
                    HTCLOSE -> DFCS_CAPTIONCLOSE
                    HTMAXBUTTON -> if (isMaximized) DFCS_CAPTIONRESTORE else DFCS_CAPTIONMAX
                    else -> DFCS_CAPTIONMIN
                }
                if (isPressed) {
                    state = state or DFCS_PUSHED
                }
                preTagDibRect(pixelsPtr, surfW, surfH, left, top, right, bottom)
                DrawFrameControl(dibDc, rc.ptr, DFC_CAPTION.toUInt(), state.toUInt())
                finalizeGdiDrawnRectAlpha(pixelsPtr, surfW, surfH, left, top, right, bottom, false)
                return@memScoped
            }

            else -> {}
        }

        // Fluent / Aero Caption Button Background (Hover / Press)
        val glyphColorRef = if (hitType == HTCLOSE && (isHovered || isPressed)) {
            val bgCol = if (isPressed) palette.closeButtonPressBgColor else palette.closeButtonHoverBgColor
            fillDibSolidColorRef(pixelsPtr, surfW, surfH, left, top, right, bottom, bgCol, 255)
            palette.closeButtonHoverTextColor
        } else {
            if (isPressed) {
                blendDibRectColorRef(
                    pixelsPtr = pixelsPtr,
                    surfW = surfW,
                    surfH = surfH,
                    left = left,
                    top = top,
                    right = right,
                    bottom = bottom,
                    colorRef = palette.buttonPressBgColor,
                    alpha = palette.buttonPressAlpha
                )
            } else if (isHovered) {
                blendDibRectColorRef(
                    pixelsPtr = pixelsPtr,
                    surfW = surfW,
                    surfH = surfH,
                    left = left,
                    top = top,
                    right = right,
                    bottom = bottom,
                    colorRef = palette.buttonHoverBgColor,
                    alpha = palette.buttonHoverAlpha
                )
            }
            palette.captionTextColor
        }

        // Render pixel-accurate Windows 10/11 caption glyph (`10x10` for normal windows, `8x8` for tool windows)
        val cx = (left + right) / 2
        val cy = (top + bottom) / 2
        drawCaptionButtonGlyphPixels(
            pixelsPtr = pixelsPtr,
            surfW = surfW,
            surfH = surfH,
            cx = cx,
            cy = cy,
            hitType = hitType,
            isMaximized = isMaximized,
            glyphSizePx = kind.defaultGlyphSizePx,
            isWin11 = palette.generation == Win32VisualStyleGeneration.WIN11_MICA_FLUENT,
            colorRef = glyphColorRef
        )
    }

    /**
     * Draws pixel-accurate Windows 10/11 caption glyphs (`Minimize`, `Maximize`, `Restore`, `Close`)
     * directly into the 32-bit ARGB DIB buffer so they are 100% crisp at any DPI (`10x10` on standard
     * windows, `8x8` on `TOOL_WINDOW` captions).
     */
    private fun drawCaptionButtonGlyphPixels(
        pixelsPtr: CPointer<UIntVar>,
        surfW: Int,
        surfH: Int,
        cx: Int,
        cy: Int,
        hitType: Int,
        isMaximized: Boolean,
        glyphSizePx: Int,
        isWin11: Boolean,
        colorRef: UInt
    ) {
        val span = glyphSizePx.coerceIn(6, 12)
        val half = span / 2
        when (hitType) {
            HTMINBUTTON -> {
                for (px in (cx - half) until (cx - half + span)) {
                    blendPixelColorRef(pixelsPtr, surfW, surfH, px, cy, colorRef, 255)
                }
            }

            HTMAXBUTTON -> {
                if (!isMaximized) {
                    val x0 = cx - half
                    val y0 = cy - half
                    val x1 = x0 + span - 1
                    val y1 = y0 + span - 1
                    for (px in x0..x1) {
                        val isCorner = isWin11 && (px == x0 || px == x1)
                        blendPixelColorRef(pixelsPtr, surfW, surfH, px, y0, colorRef, if (isCorner) 140 else 255)
                        blendPixelColorRef(pixelsPtr, surfW, surfH, px, y1, colorRef, if (isCorner) 140 else 255)
                    }
                    for (py in (y0 + 1) until y1) {
                        blendPixelColorRef(pixelsPtr, surfW, surfH, x0, py, colorRef, 255)
                        blendPixelColorRef(pixelsPtr, surfW, surfH, x1, py, colorRef, 255)
                    }
                } else {
                    val fx0 = cx - half
                    val fy0 = cy - half + 2
                    val fx1 = fx0 + span - 3
                    val fy1 = fy0 + span - 3
                    for (px in fx0..fx1) {
                        blendPixelColorRef(pixelsPtr, surfW, surfH, px, fy0, colorRef, 255)
                        blendPixelColorRef(pixelsPtr, surfW, surfH, px, fy1, colorRef, 255)
                    }
                    for (py in (fy0 + 1) until fy1) {
                        blendPixelColorRef(pixelsPtr, surfW, surfH, fx0, py, colorRef, 255)
                        blendPixelColorRef(pixelsPtr, surfW, surfH, fx1, py, colorRef, 255)
                    }
                    val bx0 = cx - half + 2
                    val by0 = cy - half
                    val bx1 = bx0 + span - 3
                    val by1 = by0 + span - 3
                    for (px in bx0..bx1) {
                        blendPixelColorRef(pixelsPtr, surfW, surfH, px, by0, colorRef, 255)
                    }
                    for (py in by0..by1) {
                        blendPixelColorRef(pixelsPtr, surfW, surfH, bx1, py, colorRef, 255)
                    }
                    blendPixelColorRef(pixelsPtr, surfW, surfH, bx0, by0 + 1, colorRef, 255)
                    blendPixelColorRef(pixelsPtr, surfW, surfH, bx1 - 1, by1, colorRef, 255)
                }
            }

            HTCLOSE -> {
                val x0 = cx - half
                val y0 = cy - half
                for (i in 0 until span) {
                    val ax = x0 + i
                    val ay = y0 + i
                    val bx = x0 + (span - 1 - i)
                    val by = y0 + i
                    blendPixelColorRef(pixelsPtr, surfW, surfH, ax, ay, colorRef, 255)
                    blendPixelColorRef(pixelsPtr, surfW, surfH, bx, by, colorRef, 255)
                    // Sub-pixel anti-aliasing fringe
                    blendPixelColorRef(pixelsPtr, surfW, surfH, ax + 1, ay, colorRef, 72)
                    blendPixelColorRef(pixelsPtr, surfW, surfH, ax - 1, ay, colorRef, 72)
                    blendPixelColorRef(pixelsPtr, surfW, surfH, bx + 1, by, colorRef, 72)
                    blendPixelColorRef(pixelsPtr, surfW, surfH, bx - 1, by, colorRef, 72)
                }
            }
        }
    }

    /**
     * Draws text with GDI `DrawTextW` while preserving full 32-bit premultiplied ARGB alpha
     * (and optional Vista/7 Aero Glass halo glow behind the glyphs).
     */
    private fun drawAlphaPreservedText(
        dibDc: HDC,
        pixelsPtr: CPointer<UIntVar>,
        surfW: Int,
        surfH: Int,
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        colorRef: UInt,
        center: Boolean,
        transparentDwm: Boolean,
        aeroGlow: Boolean
    ) = memScoped {
        val x0 = left.coerceIn(0, surfW)
        val y0 = top.coerceIn(0, surfH)
        val x1 = right.coerceIn(0, surfW)
        val y1 = bottom.coerceIn(0, surfH)
        if (x1 <= x0 || y1 <= y0) return@memScoped

        val rc = alloc<RECT>()
        rc.left = x0
        rc.top = y0
        rc.right = x1
        rc.bottom = y1

        val flags = (DT_SINGLELINE or DT_VCENTER or (if (center) DT_CENTER else DT_LEFT) or DT_END_ELLIPSIS or DT_NOPREFIX).toUInt()

        if (aeroGlow) {
            // Render soft white Aero Glass halo glow behind the caption text
            val glowWhite = Win32DwmUxThemeBridge.colorRefRgb(255, 255, 255)
            val offsets = arrayOf(-1 to -1, 1 to -1, -1 to 1, 1 to 1, -2 to 0, 2 to 0, 0 to -2, 0 to 2)
            for ((dx, dy) in offsets) {
                blendDibRectColorRef(
                    pixelsPtr = pixelsPtr,
                    surfW = surfW,
                    surfH = surfH,
                    left = (x0 + dx).coerceIn(0, surfW),
                    top = (y0 + dy).coerceIn(0, surfH),
                    right = min(x1, x0 + text.length * 8 + 16).coerceIn(0, surfW),
                    bottom = (y1 + dy).coerceIn(0, surfH),
                    colorRef = glowWhite,
                    alpha = 18
                )
            }
        }

        preTagDibRect(pixelsPtr, surfW, surfH, x0, y0, x1, y1)
        SetTextColor(dibDc, colorRef)
        DrawTextW(dibDc, text, -1, rc.ptr, flags)
        finalizeGdiDrawnRectAlpha(pixelsPtr, surfW, surfH, x0, y0, x1, y1, transparentDwm)
    }

    /**
     * Pre-tags pixels in `[left..right, top..bottom]` whose alpha is `0x00` with `0x01000000u`
     * before a GDI draw call. Since GDI writes `0x00` into the high byte of every pixel it modifies,
     * [finalizeGdiDrawnRectAlpha] can distinguish GDI-written pixels (`alpha == 0x00` -> `0xFF`)
     * from untouched transparent DWM pixels (`alpha == 0x01` -> `0x00000000u`).
     */
    private fun preTagDibRect(
        pixelsPtr: CPointer<UIntVar>,
        surfW: Int,
        surfH: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        val x0 = left.coerceIn(0, surfW)
        val y0 = top.coerceIn(0, surfH)
        val x1 = right.coerceIn(0, surfW)
        val y1 = bottom.coerceIn(0, surfH)
        for (py in y0 until y1) {
            val rowOff = py * surfW
            for (px in x0 until x1) {
                val p = pixelsPtr[rowOff + px]
                if ((p shr 24) == 0u) {
                    pixelsPtr[rowOff + px] = p or 0x01000000u
                }
            }
        }
    }

    private fun finalizeGdiDrawnRectAlpha(
        pixelsPtr: CPointer<UIntVar>,
        surfW: Int,
        surfH: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        restoreUntouchedToTransparent: Boolean
    ) {
        val x0 = left.coerceIn(0, surfW)
        val y0 = top.coerceIn(0, surfH)
        val x1 = right.coerceIn(0, surfW)
        val y1 = bottom.coerceIn(0, surfH)
        for (py in y0 until y1) {
            val rowOff = py * surfW
            for (px in x0 until x1) {
                val p = pixelsPtr[rowOff + px]
                val a = (p shr 24).toInt()
                if (a == 0) {
                    // Written by GDI -> set alpha to 0xFF
                    pixelsPtr[rowOff + px] = p or 0xFF000000u
                } else if (a == 1 && restoreUntouchedToTransparent) {
                    pixelsPtr[rowOff + px] = 0x00000000u
                }
            }
        }
    }

    private fun colorRefToBgra(colorRef: UInt, alpha: Int = 255): UInt {
        val a = alpha.coerceIn(0, 255)
        val r = ((colorRef and 0xFFu).toInt() * a + 127) / 255
        val g = (((colorRef shr 8) and 0xFFu).toInt() * a + 127) / 255
        val b = (((colorRef shr 16) and 0xFFu).toInt() * a + 127) / 255
        return ((a shl 24) or (r shl 16) or (g shl 8) or b).toUInt()
    }

    private fun fillDibSolidColorRef(
        pixelsPtr: CPointer<UIntVar>,
        surfW: Int,
        surfH: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        colorRef: UInt,
        alpha: Int = 255
    ) {
        val x0 = left.coerceIn(0, surfW)
        val y0 = top.coerceIn(0, surfH)
        val x1 = right.coerceIn(0, surfW)
        val y1 = bottom.coerceIn(0, surfH)
        if (x1 <= x0 || y1 <= y0) return
        val packed = colorRefToBgra(colorRef, alpha)
        for (py in y0 until y1) {
            val rowOff = py * surfW
            for (px in x0 until x1) {
                pixelsPtr[rowOff + px] = packed
            }
        }
    }

    private fun fillDibHorizontalGradientColorRef(
        pixelsPtr: CPointer<UIntVar>,
        width: Int,
        height: Int,
        startColorRef: UInt,
        endColorRef: UInt
    ) {
        val r0 = (startColorRef and 0xFFu).toInt()
        val g0 = ((startColorRef shr 8) and 0xFFu).toInt()
        val b0 = ((startColorRef shr 16) and 0xFFu).toInt()
        val r1 = (endColorRef and 0xFFu).toInt()
        val g1 = ((endColorRef shr 8) and 0xFFu).toInt()
        val b1 = ((endColorRef shr 16) and 0xFFu).toInt()
        val span = (width - 1).coerceAtLeast(1)

        for (px in 0 until width) {
            val r = r0 + ((r1 - r0) * px) / span
            val g = g0 + ((g1 - g0) * px) / span
            val b = b0 + ((b1 - b0) * px) / span
            val packed = ((0xFF shl 24) or (r shl 16) or (g shl 8) or b).toUInt()
            for (py in 0 until height) {
                pixelsPtr[py * width + px] = packed
            }
        }
    }

    private fun blendPixelColorRef(
        pixelsPtr: CPointer<UIntVar>,
        surfW: Int,
        surfH: Int,
        px: Int,
        py: Int,
        colorRef: UInt,
        alpha: Int
    ) {
        if (px !in 0 until surfW || py !in 0 until surfH || alpha <= 0) return
        val a = alpha.coerceIn(0, 255)
        val idx = py * surfW + px
        if (a == 255) {
            pixelsPtr[idx] = colorRefToBgra(colorRef, 255)
            return
        }
        val sr = ((colorRef and 0xFFu).toInt() * a + 127) / 255
        val sg = (((colorRef shr 8) and 0xFFu).toInt() * a + 127) / 255
        val sb = (((colorRef shr 16) and 0xFFu).toInt() * a + 127) / 255
        val dst = pixelsPtr[idx].toInt()
        val da = (dst ushr 24) and 0xFF
        val dr = (dst ushr 16) and 0xFF
        val dg = (dst ushr 8) and 0xFF
        val db = dst and 0xFF
        val invA = 255 - a
        val outA = (a + (da * invA + 127) / 255).coerceIn(0, 255)
        val outR = (sr + (dr * invA + 127) / 255).coerceIn(0, 255)
        val outG = (sg + (dg * invA + 127) / 255).coerceIn(0, 255)
        val outB = (sb + (db * invA + 127) / 255).coerceIn(0, 255)
        pixelsPtr[idx] = ((outA shl 24) or (outR shl 16) or (outG shl 8) or outB).toUInt()
    }

    private fun blendDibRectColorRef(
        pixelsPtr: CPointer<UIntVar>,
        surfW: Int,
        surfH: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        colorRef: UInt,
        alpha: Int
    ) {
        if (alpha <= 0) return
        val x0 = left.coerceIn(0, surfW)
        val y0 = top.coerceIn(0, surfH)
        val x1 = right.coerceIn(0, surfW)
        val y1 = bottom.coerceIn(0, surfH)
        for (py in y0 until y1) {
            for (px in x0 until x1) {
                blendPixelColorRef(pixelsPtr, surfW, surfH, px, py, colorRef, alpha)
            }
        }
    }

    private fun blendDibRoundedRectColorRef(
        pixelsPtr: CPointer<UIntVar>,
        surfW: Int,
        surfH: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        radius: Int,
        colorRef: UInt,
        alpha: Int
    ) {
        if (alpha <= 0) return
        val x0 = left.coerceIn(0, surfW)
        val y0 = top.coerceIn(0, surfH)
        val x1 = right.coerceIn(0, surfW)
        val y1 = bottom.coerceIn(0, surfH)
        val r = radius.coerceAtLeast(0)
        for (py in y0 until y1) {
            for (px in x0 until x1) {
                val dx = when {
                    px < left + r -> (left + r) - px
                    px >= right - r -> px - (right - r - 1)
                    else -> 0
                }
                val dy = when {
                    py < top + r -> (top + r) - py
                    py >= bottom - r -> py - (bottom - r - 1)
                    else -> 0
                }
                if (dx * dx + dy * dy <= r * r + 1) {
                    blendPixelColorRef(pixelsPtr, surfW, surfH, px, py, colorRef, alpha)
                }
            }
        }
    }

    private fun blendDibRoundedRectBorderColorRef(
        pixelsPtr: CPointer<UIntVar>,
        surfW: Int,
        surfH: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        radius: Int,
        colorRef: UInt,
        alpha: Int
    ) {
        if (alpha <= 0 || right - left < 4 || bottom - top < 4) return
        val r = radius.coerceIn(0, min(right - left, bottom - top) / 2)
        for (px in (left + r) until (right - r)) {
            blendPixelColorRef(pixelsPtr, surfW, surfH, px, top, colorRef, alpha)
            blendPixelColorRef(pixelsPtr, surfW, surfH, px, bottom - 1, colorRef, alpha)
        }
        for (py in (top + r) until (bottom - r)) {
            blendPixelColorRef(pixelsPtr, surfW, surfH, left, py, colorRef, alpha)
            blendPixelColorRef(pixelsPtr, surfW, surfH, right - 1, py, colorRef, alpha)
        }
        if (r >= 2) {
            blendPixelColorRef(pixelsPtr, surfW, surfH, left + 1, top + 1, colorRef, alpha)
            blendPixelColorRef(pixelsPtr, surfW, surfH, right - 2, top + 1, colorRef, alpha)
            blendPixelColorRef(pixelsPtr, surfW, surfH, left + 1, bottom - 2, colorRef, alpha)
            blendPixelColorRef(pixelsPtr, surfW, surfH, right - 2, bottom - 2, colorRef, alpha)
        }
    }
}
