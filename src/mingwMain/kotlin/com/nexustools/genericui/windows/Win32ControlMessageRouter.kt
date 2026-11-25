@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.widget.DesktopButtonNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopCheckboxNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopSelectNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTabNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTextInputNativeImpl
import com.nexustools.genericui.event.InputFocusManager
import com.nexustools.genericui.event.KeyEvent
import com.nexustools.genericui.event.PointerDragManager
import com.nexustools.genericui.event.PointerEvent
import com.nexustools.genericui.event.WheelEvent
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.enums.css.CssCursor
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssCustomCursor
import com.nexustools.genericui.windows.impl.WindowsButtonNativeImpl
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cValue
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.toLong
import kotlinx.cinterop.wcstr
import platform.windows.BeginDeferWindowPos
import platform.windows.BeginPaint
import platform.windows.CB_SETITEMHEIGHT
import platform.windows.CDDS_PREPAINT
import platform.windows.CDRF_DODEFAULT
import platform.windows.CDRF_SKIPDEFAULT
import platform.windows.ChildWindowFromPointEx
import platform.windows.CS_DBLCLKS
import platform.windows.CWP_SKIPINVISIBLE
import platform.windows.CWP_SKIPTRANSPARENT
import platform.windows.ClientToScreen
import platform.windows.CreateFontW
import platform.windows.CreateSolidBrush
import platform.windows.CreateWindowExW
import platform.windows.DEFAULT_CHARSET
import platform.windows.DEFAULT_GUI_FONT
import platform.windows.DefSubclassProc
import platform.windows.DefWindowProcW
import platform.windows.DeferWindowPos
import platform.windows.DeleteObject
import platform.windows.DestroyWindow
import platform.windows.EnableWindow
import platform.windows.EndDeferWindowPos
import platform.windows.EndPaint
import platform.windows.FW_BOLD
import platform.windows.FW_NORMAL
import platform.windows.FW_SEMIBOLD
import platform.windows.GA_ROOT
import platform.windows.GetAncestor
import platform.windows.GetCapture
import platform.windows.GetClientRect
import platform.windows.GetFocus
import platform.windows.GetModuleHandleW
import platform.windows.GetParent
import platform.windows.GetStockObject
import platform.windows.GetWindowLongPtrW
import platform.windows.GetWindowRect
import platform.windows.GWL_EXSTYLE
import platform.windows.GWL_STYLE
import platform.windows.HBRUSH
import platform.windows.HDC
import platform.windows.HDC__
import platform.windows.HFONT
import platform.windows.HMENU__
import platform.windows.HOVER_DEFAULT
import platform.windows.HTTRANSPARENT
import platform.windows.HWND
import platform.windows.HWND_TOP
import platform.windows.HWND__
import platform.windows.IDC_APPSTARTING
import platform.windows.IDC_ARROW
import platform.windows.IDC_CROSS
import platform.windows.IDC_HAND
import platform.windows.IDC_HELP
import platform.windows.IDC_IBEAM
import platform.windows.IDC_NO
import platform.windows.IDC_SIZEALL
import platform.windows.IDC_SIZENESW
import platform.windows.IDC_SIZENS
import platform.windows.IDC_SIZENWSE
import platform.windows.IDC_SIZEWE
import platform.windows.IDC_WAIT
import platform.windows.InvalidateRect
import platform.windows.IsChild
import platform.windows.IsWindow
import platform.windows.LoadCursorW
import platform.windows.LPARAM
import platform.windows.LRESULT
import platform.windows.LWA_ALPHA
import platform.windows.MK_CONTROL
import platform.windows.MK_LBUTTON
import platform.windows.MK_MBUTTON
import platform.windows.MK_RBUTTON
import platform.windows.MK_SHIFT
import platform.windows.MapWindowPoints
import platform.windows.NMCUSTOMDRAW
import platform.windows.NMHDR
import platform.windows.NM_CUSTOMDRAW
import platform.windows.NULL_BRUSH
import platform.windows.OPAQUE
import platform.windows.PAINTSTRUCT
import platform.windows.POINT
import platform.windows.RECT
import platform.windows.RegisterClassExW
import platform.windows.ReleaseCapture
import platform.windows.RemoveWindowSubclass
import platform.windows.SWP_FRAMECHANGED
import platform.windows.SWP_NOACTIVATE
import platform.windows.SWP_NOMOVE
import platform.windows.SWP_NOSIZE
import platform.windows.SWP_NOZORDER
import platform.windows.SW_HIDE
import platform.windows.SW_SHOWNOACTIVATE
import platform.windows.ScreenToClient
import platform.windows.SendMessageW
import platform.windows.SetBkColor
import platform.windows.SetBkMode
import platform.windows.SetCapture
import platform.windows.SetCursor
import platform.windows.SetFocus
import platform.windows.SetLayeredWindowAttributes
import platform.windows.SetParent
import platform.windows.SetTextColor
import platform.windows.SetWindowLongPtrW
import platform.windows.SetWindowPos
import platform.windows.SetWindowSubclass
import platform.windows.ShowWindow
import platform.windows.TME_LEAVE
import platform.windows.TTTOOLINFOW
import platform.windows.TRANSPARENT
import platform.windows.TTF_IDISHWND
import platform.windows.TTF_SUBCLASS
import platform.windows.TTM_ACTIVATE
import platform.windows.TTM_ADDTOOLW
import platform.windows.TTM_DELTOOLW
import platform.windows.TTM_UPDATETIPTEXTW
import platform.windows.TrackMouseEvent
import platform.windows.UINT
import platform.windows.WNDCLASSEXW
import platform.windows.WM_CHAR
import platform.windows.WM_COMMAND
import platform.windows.WM_CTLCOLORBTN
import platform.windows.WM_CTLCOLOREDIT
import platform.windows.WM_CTLCOLORLISTBOX
import platform.windows.WM_CTLCOLORSTATIC
import platform.windows.WM_DPICHANGED
import platform.windows.WM_ERASEBKGND
import platform.windows.WM_GETDLGCODE
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
import platform.windows.WM_NCDESTROY
import platform.windows.WM_NCHITTEST
import platform.windows.WM_NOTIFY
import platform.windows.WM_PAINT
import platform.windows.WM_RBUTTONDOWN
import platform.windows.WM_RBUTTONUP
import platform.windows.WM_SETCURSOR
import platform.windows.WM_SETFOCUS
import platform.windows.WM_SETFONT
import platform.windows.WM_SIZE
import platform.windows.WM_VSCROLL
import platform.windows.WPARAM
import platform.windows.WS_CHILD
import platform.windows.WS_CLIPCHILDREN
import platform.windows.WS_CLIPSIBLINGS
import platform.windows.WS_EX_LAYERED
import platform.windows.WS_POPUP
import platform.windows.WS_VISIBLE
import platform.windows.tagPOINT
import platform.windows.tagTRACKMOUSEEVENT
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Central retained Win32 child `HWND` registry, pre-mount parking host, `SetWindowSubclass`
 * pointer/focus/hit-test router, batched `DeferWindowPos` layout engine, and parent-reflected
 * `WM_COMMAND` / `WM_NOTIFY` / `WM_CTLCOLOR*` dispatcher.
 */
object Win32ControlMessageRouter {

    private const val PARKING_WINDOW_CLASS = "GenericUIParkingWindowW"
    private const val SUBCLASS_ID: ULong = 1uL
    private const val CLICK_DRAG_THRESHOLD_PX = 5

    private val entriesByHwnd = HashMap<Long, Win32HwndHandle>()
    private val auxHadTabStopByHwnd = HashMap<Long, Boolean>()
    private val registeredCustomClasses = HashSet<String>()
    private val cachedUiFonts = HashMap<String, HFONT>()
    private var sharedTooltipHwnd: HWND? = null
    private var parkingHwnd: HWND? = null
    private var nextControlId: Int = 0x1000

    private var hoveredEntry: Win32HwndHandle? = null
    private var activePressEntry: Win32HwndHandle? = null
    private var pressStartClientX: Int = 0
    private var pressStartClientY: Int = 0
    private var suppressNextLeftClickForEntry: Win32HwndHandle? = null
    private var lastCursorIdKey: Long = 0L

    /**
     * Returns the shared hidden `WS_POPUP` parking `HWND` used to host newly created child controls
     * prior to container attachment (`Window.show()`).
     */
    fun getOrCreateParkingHwnd(): HWND {
        parkingHwnd?.let { existing ->
            if (IsWindow(existing) != 0) return existing
        }
        Win32WinMain.ensureWindowsControlsInitialized()
        registerCustomClass(PARKING_WINDOW_CLASS)
        val hInstance = Win32WinMain.hInstance ?: GetModuleHandleW(null)
        val created = CreateWindowExW(
            0u,
            PARKING_WINDOW_CLASS,
            "GenericUIParking",
            WS_POPUP.toUInt(),
            0,
            0,
            0,
            0,
            null,
            null,
            hInstance,
            null
        ) ?: error("Failed to create Win32 hidden WS_POPUP parking window")
        parkingHwnd = created
        return created
    }

    /**
     * Registers a custom retained Win32 control/container `WNDCLASSEXW` wired to
     * [win32RetainedCustomChildWndProc]. Idempotent per [className].
     */
    fun registerCustomClass(className: String) {
        if (!registeredCustomClasses.add(className)) return
        Win32WinMain.ensureWindowsControlsInitialized()
        memScoped {
            val wc = alloc<WNDCLASSEXW>()
            wc.cbSize = sizeOf<WNDCLASSEXW>().toUInt()
            wc.style = CS_DBLCLKS.toUInt()
            wc.lpfnWndProc = staticCFunction(::win32RetainedCustomChildWndProc)
            wc.hInstance = Win32WinMain.hInstance ?: GetModuleHandleW(null)
            wc.hCursor = LoadCursorW(null, IDC_ARROW)
            wc.hbrBackground = null
            wc.lpszClassName = className.wcstr.ptr
            RegisterClassExW(wc.ptr)
        }
    }

    /**
     * Allocates a genuine child `HWND` pre-mounted inside [getOrCreateParkingHwnd], wires its
     * [Win32HwndHandle] & [DesktopBaseNativeImpl], registers `SetWindowSubclass` when subclassing
     * standard Win32/ComCtl32 controls, and applies theme + default GUI font.
     */
    fun createChildHwnd(
        handle: Win32HwndHandle,
        owner: DesktopBaseNativeImpl<*>,
        win32ClassName: String,
        dwStyle: Int,
        dwExStyle: Int = 0,
        initialText: String = "",
        initialWidth: Int = 120,
        initialHeight: Int = 28,
        installSubclass: Boolean = true,
        isNativeEditTarget: Boolean = false
    ): HWND {
        Win32WinMain.ensureWindowsControlsInitialized()
        if (win32ClassName.startsWith("GenericUI")) {
            registerCustomClass(win32ClassName)
        }
        val parking = getOrCreateParkingHwnd()
        val ctrlId = allocateControlId()
        val effectiveStyle = (dwStyle and WS_POPUP.toInt().inv()) or WS_CHILD or WS_CLIPSIBLINGS
        val hInstance = Win32WinMain.hInstance ?: GetModuleHandleW(null)
        val created = CreateWindowExW(
            dwExStyle.toUInt(),
            win32ClassName,
            initialText,
            effectiveStyle.toUInt(),
            0,
            0,
            initialWidth.coerceAtLeast(1),
            initialHeight.coerceAtLeast(1),
            parking,
            ctrlId.toLong().toCPointer<HMENU__>(),
            hInstance,
            null
        ) ?: error("Failed to create child HWND of class '$win32ClassName'")

        handle.hwnd = created
        handle.actualWin32ClassName = win32ClassName
        handle.dwStyle = effectiveStyle.toLong()
        handle.dwExStyle = dwExStyle.toLong()
        handle.controlId = ctrlId
        handle.parentHwnd = null
        handle.ownerImpl = owner
        handle.isNativeEditTarget = isNativeEditTarget
        handle.attributes["nativeImpl"] = owner
        entriesByHwnd[created.toLong()] = handle

        if (installSubclass) {
            SetWindowSubclass(created, staticCFunction(::win32RetainedSubclassProc), SUBCLASS_ID, 0uL)
        }

        val dark = Win32NativeWindowHost.isEffectivelyDarkTheme()
        Win32DwmUxThemeBridge.setControlWindowTheme(created, dark)
        val defFont = obtainUiFont(fontFamily = "Segoe UI", fontSizePx = 13, fontWeight = 400)
        if (defFont != null) {
            handle.fontHandle = defFont
            handle.ownsFontHandle = false
            SendMessageW(created, WM_SETFONT.toUInt(), defFont.toLong().toULong(), 0L)
        }
        handle.hadTabStop = (effectiveStyle.toLong() and Win32HwndHandle.WS_TABSTOP) != 0L && !handle.isEffectivelyFocusable()
        if (!handle.isEffectivelyFocusable()) {
            syncFocusable(handle)
        }
        return created
    }

    /**
     * Replaces [handle]'s underlying Win32 window class & style in place (for example when a
     * `Select` / `TextInput` swaps variant or a container upgrades to `"GenericUILayeredStageHostW"`,
     * while `Button` stays a genuine `"Button"` (`WC_BUTTON`) painted via `NM_CUSTOMDRAW` in
     * semantic modes). Preserves parent, child HWNDs, bounds, visibility, enabled state, opacity,
     * tooltip, Z-order, and focus.
     */
    fun replaceChildHwndInPlace(
        handle: Win32HwndHandle,
        owner: DesktopBaseNativeImpl<*>,
        newWin32ClassName: String,
        newDwStyle: Int,
        newDwExStyle: Int = 0,
        initialText: String = "",
        installSubclass: Boolean = true,
        isNativeEditTarget: Boolean = false
    ): HWND {
        val oldHwnd = handle.hwnd
        val oldParent = handle.parentHwnd
        val hadFocus = oldHwnd != null && GetFocus() == oldHwnd
        val prevW = handle.lastAppliedW.takeIf { it > 0 } ?: handle.bounds.width.coerceAtLeast(1)
        val prevH = handle.lastAppliedH.takeIf { it > 0 } ?: handle.bounds.height.coerceAtLeast(1)
        val prevX = if (handle.lastAppliedX != Int.MIN_VALUE) handle.lastAppliedX else handle.bounds.x
        val prevY = if (handle.lastAppliedY != Int.MIN_VALUE) handle.lastAppliedY else handle.bounds.y

        // Create new child HWND first (initially parked) so we can reparent children cleanly before destroying oldHwnd
        val savedChildren = handle.children.filterIsInstance<Win32HwndHandle>()
        if (oldHwnd != null) {
            entriesByHwnd.remove(oldHwnd.toLong())
            if (hoveredEntry === handle) hoveredEntry = null
            if (activePressEntry === handle) activePressEntry = null
            if (handle.tooltipHwnd != null) {
                removeTooltipTool(oldHwnd)
            }
        }

        val newHwnd = createChildHwnd(
            handle = handle,
            owner = owner,
            win32ClassName = newWin32ClassName,
            dwStyle = newDwStyle,
            dwExStyle = newDwExStyle,
            initialText = initialText,
            initialWidth = prevW,
            initialHeight = prevH,
            installSubclass = installSubclass,
            isNativeEditTarget = isNativeEditTarget
        )

        for (childWidget in savedChildren) {
            attachChildHwnd(childWidget, newHwnd)
        }

        if (oldHwnd != null && IsWindow(oldHwnd) != 0) {
            DestroyWindow(oldHwnd)
        }

        if (oldParent != null && IsWindow(oldParent) != 0) {
            attachChildHwnd(handle, oldParent)
            val setH = if (newWin32ClassName.equals("ComboBox", ignoreCase = true)) maxOf(prevH, 260) else prevH
            SetWindowPos(newHwnd, null, prevX, prevY, prevW, setH, SWP_NOZORDER.toUInt() or SWP_NOACTIVATE.toUInt())
            if (newWin32ClassName.equals("ComboBox", ignoreCase = true)) {
                SendMessageW(newHwnd, CB_SETITEMHEIGHT.toUInt(), (-1L).toULong(), (prevH - 6).coerceAtLeast(16).toLong())
            }
            if (hadFocus && handle.isEffectivelyFocusable()) {
                SetFocus(newHwnd)
            }
        }

        val tip = handle.tooltipText
        if (!tip.isNullOrBlank()) {
            applyTooltip(handle, tip)
        }
        applyOpacity(handle, handle.effectiveOpacity)
        return newHwnd
    }

    /**
     * Attaches [child] onto [parentHwnd] using `SetParent`, updating `WS_CHILD` / `WS_CLIPSIBLINGS`
     * styles, visibility, enabled state, opacity, tooltip, and dark-mode theme cleanly.
     */
    fun attachChildHwnd(child: Win32HwndHandle, parentHwnd: HWND) {
        val hwnd = child.hwnd ?: return
        if (IsWindow(hwnd) == 0 || IsWindow(parentHwnd) == 0) return
        val currentParent = GetParent(hwnd)
        if (currentParent != parentHwnd || child.parentHwnd != parentHwnd) {
            var style = GetWindowLongPtrW(hwnd, GWL_STYLE).toInt()
            style = (style and WS_POPUP.toInt().inv()) or WS_CHILD or WS_CLIPSIBLINGS
            SetWindowLongPtrW(hwnd, GWL_STYLE, style.toLong())
            SetParent(hwnd, parentHwnd)
            child.parentHwnd = parentHwnd
            child.dwStyle = style.toLong()
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
        val buddy = child.buddyHwnd
        if (buddy != null && IsWindow(buddy) != 0 && GetParent(buddy) != parentHwnd) {
            SetParent(buddy, parentHwnd)
            ShowWindow(buddy, if (child.effectiveVisible) SW_SHOWNOACTIVATE else SW_HIDE)
        }
        ShowWindow(hwnd, if (child.effectiveVisible) SW_SHOWNOACTIVATE else SW_HIDE)
        EnableWindow(hwnd, if (child.effectiveEnabled) 1 else 0)
        applyOpacity(child, child.effectiveOpacity)
        Win32DwmUxThemeBridge.setControlWindowTheme(hwnd, Win32NativeWindowHost.isEffectivelyDarkTheme())
        val tip = child.tooltipText
        if (!tip.isNullOrBlank()) {
            applyTooltip(child, tip)
        }
        syncFocusable(child)
    }

    private fun syncAuxHwndTabStop(hwnd: HWND?, focusable: Boolean) {
        if (hwnd == null || IsWindow(hwnd) == 0) return
        val key = hwnd.toLong()
        val curStyle = GetWindowLongPtrW(hwnd, GWL_STYLE).toLong()
        if (!focusable) {
            if ((curStyle and Win32HwndHandle.WS_TABSTOP) != 0L) {
                if (!auxHadTabStopByHwnd.containsKey(key)) {
                    auxHadTabStopByHwnd[key] = true
                }
                SetWindowLongPtrW(hwnd, GWL_STYLE, curStyle and Win32HwndHandle.WS_TABSTOP.inv())
            }
        } else if (auxHadTabStopByHwnd.remove(key) == true) {
            SetWindowLongPtrW(hwnd, GWL_STYLE, curStyle or Win32HwndHandle.WS_TABSTOP)
        }
    }

    fun syncFocusable(handle: Win32HwndHandle) {
        val effective = handle.isEffectivelyFocusable()
        val primary = handle.hwnd
        if (primary != null && IsWindow(primary) != 0) {
            val curStyle = GetWindowLongPtrW(primary, GWL_STYLE).toLong()
            if (!effective) {
                if ((curStyle and Win32HwndHandle.WS_TABSTOP) != 0L) {
                    handle.hadTabStop = true
                    val nextStyle = curStyle and Win32HwndHandle.WS_TABSTOP.inv()
                    SetWindowLongPtrW(primary, GWL_STYLE, nextStyle)
                    handle.dwStyle = nextStyle
                }
            } else if (handle.hadTabStop) {
                handle.hadTabStop = false
                val nextStyle = curStyle or Win32HwndHandle.WS_TABSTOP
                SetWindowLongPtrW(primary, GWL_STYLE, nextStyle)
                handle.dwStyle = nextStyle
            }
        }
        syncAuxHwndTabStop(handle.buddyHwnd, effective)
        syncAuxHwndTabStop(handle.secondaryHwnd, effective)
        syncAuxHwndTabStop(handle.secondaryHwnd2, effective)

        for (child in handle.children) {
            if (child is Win32HwndHandle) {
                syncFocusable(child)
            }
        }

        if (!effective && primary != null && IsWindow(primary) != 0) {
            val focused = GetFocus()
            val focusInSubtree = focused != null && (
                focused == primary ||
                    IsChild(primary, focused) != 0 ||
                    (handle.buddyHwnd != null && (focused == handle.buddyHwnd || IsChild(handle.buddyHwnd, focused) != 0)) ||
                    (handle.secondaryHwnd != null && (focused == handle.secondaryHwnd || IsChild(handle.secondaryHwnd, focused) != 0)) ||
                    (handle.secondaryHwnd2 != null && (focused == handle.secondaryHwnd2 || IsChild(handle.secondaryHwnd2, focused) != 0))
                )
            if (focusInSubtree) {
                val root = GetAncestor(primary, GA_ROOT.toUInt())
                if (root != null && root != primary && IsWindow(root) != 0) {
                    SetFocus(root)
                } else {
                    SetFocus(null)
                }
            }
        }
    }

    /**
     * Parks [child] back inside the hidden [getOrCreateParkingHwnd] when removed from a container.
     */
    fun detachChildHwnd(child: Win32HwndHandle) {
        val hwnd = child.hwnd ?: return
        if (IsWindow(hwnd) == 0) return
        ShowWindow(hwnd, SW_HIDE)
        val parking = getOrCreateParkingHwnd()
        if (GetParent(hwnd) != parking) {
            SetParent(hwnd, parking)
        }
        child.parentHwnd = null
        val buddy = child.buddyHwnd
        if (buddy != null && IsWindow(buddy) != 0) {
            ShowWindow(buddy, SW_HIDE)
            if (GetParent(buddy) != parking) {
                SetParent(buddy, parking)
            }
        }
    }

    /**
     * Destroys the child `HWND`(s) owned by [handle] and frees GDI font/brush handles.
     */
    fun destroyHandle(handle: Win32HwndHandle) {
        handle.secondaryHwnd?.let { sec ->
            entriesByHwnd.remove(sec.toLong())
            if (IsWindow(sec) != 0) DestroyWindow(sec)
            handle.secondaryHwnd = null
        }
        handle.secondaryHwnd2?.let { sec2 ->
            entriesByHwnd.remove(sec2.toLong())
            if (IsWindow(sec2) != 0) DestroyWindow(sec2)
            handle.secondaryHwnd2 = null
        }
        handle.buddyHwnd?.let { buddy ->
            entriesByHwnd.remove(buddy.toLong())
            if (IsWindow(buddy) != 0) DestroyWindow(buddy)
            handle.buddyHwnd = null
        }
        handle.hwnd?.let { hwnd ->
            entriesByHwnd.remove(hwnd.toLong())
            removeTooltipTool(hwnd)
            if (hoveredEntry === handle) hoveredEntry = null
            if (activePressEntry === handle) activePressEntry = null
            if (IsWindow(hwnd) != 0) {
                DestroyWindow(hwnd)
            }
            handle.hwnd = null
        }
        handle.parentHwnd = null
        handle.releaseWin32GdiResources()
    }

    fun resolveEntry(hwnd: HWND?): Win32HwndHandle? =
        if (hwnd == null) null else entriesByHwnd[hwnd.toLong()]

    fun registerEntryHwnd(hwnd: HWND, handle: Win32HwndHandle) {
        entriesByHwnd[hwnd.toLong()] = handle
    }

    fun unregisterEntryHwnd(hwnd: HWND?) {
        if (hwnd != null) {
            entriesByHwnd.remove(hwnd.toLong())
        }
    }

    /**
     * Batches position, size, and Z-order updates for [children] attached to [parentHwnd] via
     * `BeginDeferWindowPos` / `DeferWindowPos` / `EndDeferWindowPos` (`SWP_NOACTIVATE`).
     */
    fun layoutChildrenBatch(
        parentHwnd: HWND,
        children: List<DesktopBaseNativeImpl<*>>,
        offsetX: Int = 0,
        offsetY: Int = 0
    ) {
        if (IsWindow(parentHwnd) == 0 || children.isEmpty()) return

        var effectiveParentHwnd = parentHwnd
        val parentEntry = entriesByHwnd[parentHwnd.toLong()]
        val parentOwner = parentEntry?.ownerImpl
        if (parentEntry != null &&
            parentOwner is com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl<*> &&
            parentEntry.actualWin32ClassName != "GenericUILayeredStageHostW" &&
            Win32LayeredStageHostHwnd.isLayeredStageContainer(parentOwner)
        ) {
            effectiveParentHwnd = replaceChildHwndInPlace(
                handle = parentEntry,
                owner = parentOwner,
                newWin32ClassName = "GenericUILayeredStageHostW",
                newDwStyle = WS_CHILD or WS_VISIBLE or WS_CLIPCHILDREN or WS_CLIPSIBLINGS,
                installSubclass = false
            )
            Win32LayeredStageHostHwnd.registerStageHost(parentEntry, parentOwner)
        }

        class ChildPos(
            val impl: DesktopBaseNativeImpl<*>,
            val handle: Win32HwndHandle,
            val hwnd: HWND,
            val x: Int,
            val y: Int,
            val w: Int,
            val h: Int,
            val zIndex: Int,
            val order: Int
        )

        val entries = ArrayList<ChildPos>(children.size)
        for ((idx, impl) in children.withIndex()) {
            val handle = impl.nativeWidget as? Win32HwndHandle ?: continue
            Win32InteractiveSurfaceHwnd.ensureSurfaceChildHwnd(handle, impl)
            val hwnd = handle.hwnd ?: continue
            if (IsWindow(hwnd) == 0) continue
            attachChildHwnd(handle, effectiveParentHwnd)
            val rect = impl.nativeWidget.bounds
            val x = rect.x + offsetX
            val y = rect.y + offsetY
            val w = rect.width.coerceAtLeast(0)
            val h = rect.height.coerceAtLeast(0)
            entries.add(ChildPos(impl, handle, hwnd, x, y, w, h, handle.desiredZIndex, idx))
        }
        if (entries.isEmpty()) return

        // Sort ascending by (zIndex, order); iterate descending when chaining HWND_TOP -> prevHwnd
        // so higher zIndex siblings sit in front (Win32 Z-order first = topmost).
        entries.sortWith(compareBy<ChildPos> { it.zIndex }.thenBy { it.order })
        val deferCount = entries.sumOf { if (it.handle.buddyHwnd != null) 2L else 1L }.toInt()
        var hdwp = BeginDeferWindowPos(deferCount)
        var prevHwnd: HWND? = HWND_TOP
        val comboListToAdjust = ArrayList<Pair<HWND, Int>>()
        val repaintsNeeded = ArrayList<HWND>()

        for (i in entries.indices.reversed()) {
            val item = entries[i]
            val isCombo = item.handle.actualWin32ClassName.equals("ComboBox", ignoreCase = true)
            val buddy = item.handle.buddyHwnd
            val spinnerW = if (buddy != null && IsWindow(buddy) != 0 && item.w > 24) 18 else 0
            val mainW = (item.w - spinnerW).coerceAtLeast(0)
            val deferH = if (isCombo) maxOf(item.h, 260) else item.h

            val sizeChanged = item.handle.lastAppliedW != mainW || item.handle.lastAppliedH != item.h
            item.handle.lastAppliedX = item.x
            item.handle.lastAppliedY = item.y
            item.handle.lastAppliedW = mainW
            item.handle.lastAppliedH = item.h

            if (hdwp != null) {
                hdwp = DeferWindowPos(
                    hdwp,
                    item.hwnd,
                    prevHwnd,
                    item.x,
                    item.y,
                    mainW,
                    deferH,
                    SWP_NOACTIVATE.toUInt()
                )
            } else {
                SetWindowPos(
                    item.hwnd,
                    prevHwnd,
                    item.x,
                    item.y,
                    mainW,
                    deferH,
                    SWP_NOACTIVATE.toUInt()
                )
            }
            prevHwnd = item.hwnd

            if (buddy != null && IsWindow(buddy) != 0 && spinnerW > 0) {
                if (hdwp != null) {
                    hdwp = DeferWindowPos(
                        hdwp,
                        buddy,
                        prevHwnd,
                        item.x + mainW,
                        item.y,
                        spinnerW,
                        item.h,
                        SWP_NOACTIVATE.toUInt()
                    )
                } else {
                    SetWindowPos(
                        buddy,
                        prevHwnd,
                        item.x + mainW,
                        item.y,
                        spinnerW,
                        item.h,
                        SWP_NOACTIVATE.toUInt()
                    )
                }
                prevHwnd = buddy
            }

            if (isCombo && item.h > 6) {
                comboListToAdjust.add(item.hwnd to item.h)
            }
            if (sizeChanged && item.handle.actualWin32ClassName.startsWith("GenericUI")) {
                repaintsNeeded.add(item.hwnd)
            }
        }

        if (hdwp != null) {
            EndDeferWindowPos(hdwp)
        }
        for ((comboHwnd, layoutH) in comboListToAdjust) {
            SendMessageW(
                comboHwnd,
                CB_SETITEMHEIGHT.toUInt(),
                (-1L).toULong(),
                (layoutH - 6).coerceAtLeast(16).toLong()
            )
        }
        for (h in repaintsNeeded) {
            InvalidateRect(h, null, 0)
        }
    }

    /**
     * Applies single-widget bounds directly to [handle]'s `HWND` (preserving `ComboBox` `cy >= 260`
     * and `CB_SETITEMHEIGHT`, and syncing any numeric `msctls_updown32` buddy).
     */
    fun applyBounds(handle: Win32HwndHandle, rect: Rect) {
        val hwnd = handle.hwnd ?: return
        if (IsWindow(hwnd) == 0) return
        val x = rect.x
        val y = rect.y
        val w = rect.width.coerceAtLeast(0)
        val h = rect.height.coerceAtLeast(0)
        val isCombo = handle.actualWin32ClassName.equals("ComboBox", ignoreCase = true)
        val buddy = handle.buddyHwnd
        val spinnerW = if (buddy != null && IsWindow(buddy) != 0 && w > 24) 18 else 0
        val mainW = (w - spinnerW).coerceAtLeast(0)
        val deferH = if (isCombo) maxOf(h, 260) else h

        val changed = handle.lastAppliedX != x || handle.lastAppliedY != y ||
            handle.lastAppliedW != mainW || handle.lastAppliedH != h
        handle.lastAppliedX = x
        handle.lastAppliedY = y
        handle.lastAppliedW = mainW
        handle.lastAppliedH = h

        if (changed) {
            SetWindowPos(hwnd, null, x, y, mainW, deferH, SWP_NOZORDER.toUInt() or SWP_NOACTIVATE.toUInt())
            if (buddy != null && IsWindow(buddy) != 0 && spinnerW > 0) {
                SetWindowPos(
                    buddy,
                    null,
                    x + mainW,
                    y,
                    spinnerW,
                    h,
                    SWP_NOZORDER.toUInt() or SWP_NOACTIVATE.toUInt()
                )
            }
            if (isCombo && h > 6) {
                SendMessageW(hwnd, CB_SETITEMHEIGHT.toUInt(), (-1L).toULong(), (h - 6).coerceAtLeast(16).toLong())
            }
            if (handle.actualWin32ClassName.startsWith("GenericUI")) {
                InvalidateRect(hwnd, null, 0)
            }
        }
    }

    fun applyVisibility(handle: Win32HwndHandle, visible: Boolean) {
        handle.effectiveVisible = visible
        val hwnd = handle.hwnd ?: return
        if (IsWindow(hwnd) == 0) return
        if (handle.parentHwnd != null) {
            ShowWindow(hwnd, if (visible) SW_SHOWNOACTIVATE else SW_HIDE)
            handle.buddyHwnd?.let { buddy ->
                if (IsWindow(buddy) != 0) ShowWindow(buddy, if (visible) SW_SHOWNOACTIVATE else SW_HIDE)
            }
        }
    }

    fun applyEnabled(handle: Win32HwndHandle, enabled: Boolean) {
        handle.effectiveEnabled = enabled
        val hwnd = handle.hwnd ?: return
        if (IsWindow(hwnd) == 0) return
        EnableWindow(hwnd, if (enabled) 1 else 0)
        handle.buddyHwnd?.let { buddy ->
            if (IsWindow(buddy) != 0) EnableWindow(buddy, if (enabled) 1 else 0)
        }
        InvalidateRect(hwnd, null, 0)
    }

    fun applyOpacity(handle: Win32HwndHandle, opacity: Float) {
        val clamped = opacity.coerceIn(0f, 1f)
        handle.effectiveOpacity = clamped
        val hwnd = handle.hwnd ?: return
        if (IsWindow(hwnd) == 0) return
        var ex = GetWindowLongPtrW(hwnd, GWL_EXSTYLE).toInt()
        if (clamped < 0.996f) {
            if ((ex and WS_EX_LAYERED) == 0) {
                ex = ex or WS_EX_LAYERED
                SetWindowLongPtrW(hwnd, GWL_EXSTYLE, ex.toLong())
                handle.dwExStyle = ex.toLong()
            }
            val alpha = (clamped * 255f).roundToInt().coerceIn(0, 255)
            SetLayeredWindowAttributes(hwnd, 0u, alpha.toUByte(), LWA_ALPHA.toUInt())
        } else if ((ex and WS_EX_LAYERED) != 0) {
            ex = ex and WS_EX_LAYERED.inv()
            SetWindowLongPtrW(hwnd, GWL_EXSTYLE, ex.toLong())
            handle.dwExStyle = ex.toLong()
        }
        if (handle.parentHwnd != null) {
            val shouldShow = handle.effectiveVisible && clamped > 0.001f
            ShowWindow(hwnd, if (shouldShow) SW_SHOWNOACTIVATE else SW_HIDE)
        }
    }

    fun applyPointerEventsNone(handle: Win32HwndHandle, pointerEventsNone: Boolean) {
        handle.pointerEventsNone = pointerEventsNone
    }

    fun applyZIndex(handle: Win32HwndHandle, zIndex: Int) {
        handle.desiredZIndex = zIndex
    }

    fun applyCursor(handle: Win32HwndHandle, cursor: CssCursorValue?) {
        handle.activeCursorValue = cursor
        if (PointerDragManager.isCursorLocked) return
        if (hoveredEntry === handle || hoveredEntry == null) {
            handle.hwnd?.let { updateCursorForHwnd(it, forceSet = true) }
        }
    }

    fun applyTooltip(handle: Win32HwndHandle, tooltip: String?) {
        handle.tooltipText = tooltip
        val hwnd = handle.hwnd ?: return
        if (IsWindow(hwnd) == 0) return
        val tt = ensureSharedTooltipHwnd() ?: return
        if (tooltip.isNullOrBlank()) {
            removeTooltipTool(hwnd)
            handle.tooltipHwnd = null
            return
        }
        memScoped {
            val ti = alloc<TTTOOLINFOW>()
            ti.cbSize = sizeOf<TTTOOLINFOW>().toUInt()
            ti.uFlags = TTF_IDISHWND.toUInt() or TTF_SUBCLASS.toUInt()
            ti.hwnd = handle.parentHwnd ?: hwnd
            ti.uId = hwnd.toLong().toULong()
            ti.lpszText = tooltip.wcstr.ptr
            if (handle.tooltipHwnd == tt) {
                SendMessageW(tt, TTM_UPDATETIPTEXTW.toUInt(), 0uL, ti.ptr.toLong())
            } else {
                SendMessageW(tt, TTM_ADDTOOLW.toUInt(), 0uL, ti.ptr.toLong())
                handle.tooltipHwnd = tt
            }
        }
    }

    /**
     * Syncs font, text color, and background brush on [handle] from its resolved style.
     */
    fun syncTypographyAndColors(
        handle: Win32HwndHandle,
        impl: DesktopBaseNativeImpl<*>,
        defaultTransparentBg: Boolean = true
    ) {
        val style = impl.resolveEffectiveStyle()
        val fontSize = style?.fontSizePx?.roundToInt()?.coerceIn(9, 96) ?: 13
        val fontFamily = style?.fontFamily?.takeIf { it.isNotBlank() } ?: "Segoe UI"
        val weight = style?.fontWeightNumeric ?: 400
        val italic = style?.fontStyle?.name?.equals("ITALIC", ignoreCase = true) == true
        val font = obtainUiFont(fontFamily, fontSize, weight, italic)
        val hwnd = handle.hwnd
        if (font != null && handle.fontHandle != font) {
            handle.fontHandle = font
            handle.ownsFontHandle = false
            if (hwnd != null && IsWindow(hwnd) != 0) {
                SendMessageW(hwnd, WM_SETFONT.toUInt(), font.toLong().toULong(), 1L)
            }
        }

        val dark = Win32NativeWindowHost.isEffectivelyDarkTheme()
        val fg: RgbaColor? = impl.getEffectiveTextColor()
        val bg: RgbaColor? = impl.getEffectiveBackgroundColor()
        val resolvedFgRef = if (fg != null) {
            colorRefRgb(fg.r, fg.g, fg.b)
        } else if (dark) {
            colorRefRgb(242, 242, 245)
        } else {
            colorRefRgb(20, 20, 24)
        }
        val hasSolidBg = bg != null && bg.a > 0
        val resolvedBgRef = if (hasSolidBg && bg != null) {
            colorRefRgb(bg.r, bg.g, bg.b)
        } else if (dark) {
            colorRefRgb(32, 32, 36)
        } else {
            colorRefRgb(248, 248, 250)
        }

        handle.fgColorRef = resolvedFgRef
        handle.bgColorRef = resolvedBgRef
        handle.hasCustomColors = fg != null || hasSolidBg || dark
        handle.isTransparentCtlColor = defaultTransparentBg && !hasSolidBg

        val prevBrush = handle.bgBrushHandle
        if (prevBrush != null) {
            DeleteObject(prevBrush)
            handle.bgBrushHandle = null
        }
        if (!handle.isTransparentCtlColor) {
            handle.bgBrushHandle = CreateSolidBrush(resolvedBgRef)
        }

        if (hwnd != null && IsWindow(hwnd) != 0) {
            Win32DwmUxThemeBridge.setControlWindowTheme(hwnd, dark)
            InvalidateRect(hwnd, null, 0)
        }
    }

    fun obtainUiFont(
        fontFamily: String = "Segoe UI",
        fontSizePx: Int = 13,
        fontWeight: Int = 400,
        italic: Boolean = false
    ): HFONT? {
        val key = "$fontFamily|$fontSizePx|$fontWeight|$italic"
        cachedUiFonts[key]?.let { return it }
        val winWeight = when {
            fontWeight >= 700 -> FW_BOLD
            fontWeight >= 600 -> FW_SEMIBOLD
            else -> FW_NORMAL
        }
        val created = CreateFontW(
            -fontSizePx.coerceIn(8, 120),
            0,
            0,
            0,
            winWeight,
            if (italic) 1u else 0u,
            0u,
            0u,
            DEFAULT_CHARSET.toUInt(),
            0u,
            0u,
            5u, // CLEARTYPE_QUALITY
            0u,
            fontFamily
        ) ?: GetStockObject(DEFAULT_GUI_FONT)?.reinterpret()
        if (created != null) {
            cachedUiFonts[key] = created
        }
        return created
    }

    /**
     * Handles reflected parent notifications (`WM_COMMAND`, `WM_NOTIFY`, `WM_HSCROLL`,
     * `WM_VSCROLL`, `WM_CTLCOLOR*`) from child `HWND`s in both top-level host windows and nested
     * child containers.
     */
    fun handleReflectedParentMessage(
        parentHwnd: HWND,
        msg: UINT,
        wParam: WPARAM,
        lParam: LPARAM
    ): LRESULT? {
        return when (msg.toInt()) {
            WM_COMMAND -> {
                val childHwnd = lParam.toCPointer<HWND__>() ?: return null
                val entry = entriesByHwnd[childHwnd.toLong()] ?: return null
                val notifyCode = ((wParam.toLong() ushr 16) and 0xFFFFL).toInt()
                entry.onCommandCallback?.invoke(notifyCode, wParam, lParam)
                0L
            }

            WM_NOTIFY -> {
                val nmhdr = lParam.toCPointer<NMHDR>() ?: return null
                val fromHwnd = nmhdr.pointed.hwndFrom ?: return null
                val entry = entriesByHwnd[fromHwnd.toLong()] ?: return null
                if (nmhdr.pointed.code.toInt() == NM_CUSTOMDRAW.toInt()) {
                    val ownerBtn = entry.ownerImpl as? WindowsButtonNativeImpl
                    if (ownerBtn != null) {
                        if (ownerBtn.isNativeDefaultButtonMode()) {
                            return CDRF_DODEFAULT.toLong()
                        }
                        val nmcd = lParam.toCPointer<NMCUSTOMDRAW>() ?: return CDRF_DODEFAULT.toLong()
                        if (nmcd.pointed.dwDrawStage.toInt() == CDDS_PREPAINT.toInt()) {
                            val customDrawHdc = nmcd.pointed.hdc ?: return CDRF_DODEFAULT.toLong()
                            val w = (nmcd.pointed.rc.right - nmcd.pointed.rc.left).coerceAtLeast(1)
                            val h = (nmcd.pointed.rc.bottom - nmcd.pointed.rc.top).coerceAtLeast(1)
                            Win32StyledWidgetHelper.paintCustomBootstrapButtonToHdc(entry, fromHwnd, customDrawHdc, w, h, ownerBtn)
                            return CDRF_SKIPDEFAULT.toLong()
                        }
                        return CDRF_DODEFAULT.toLong()
                    }
                }
                entry.onNotifyCallback?.invoke(nmhdr.pointed.code, nmhdr)
            }

            WM_HSCROLL, WM_VSCROLL -> {
                val childHwnd = lParam.toCPointer<HWND__>()
                if (childHwnd != null) {
                    val entry = entriesByHwnd[childHwnd.toLong()]
                    if (entry != null) {
                        entry.onScrollCallback?.invoke(msg, wParam, lParam)
                        return 0L
                    }
                }
                val parentEntry = entriesByHwnd[parentHwnd.toLong()]
                if (parentEntry?.onScrollCallback != null) {
                    parentEntry.onScrollCallback?.invoke(msg, wParam, lParam)
                    return 0L
                }
                null
            }

            WM_CTLCOLORBTN, WM_CTLCOLORSTATIC, WM_CTLCOLOREDIT, WM_CTLCOLORLISTBOX -> {
                val hdc = wParam.toLong().toCPointer<HDC__>() ?: return null
                val childHwnd = lParam.toCPointer<HWND__>() ?: return null
                val entry = entriesByHwnd[childHwnd.toLong()] ?: return null
                val msgInt = msg.toInt()
                val dark = Win32NativeWindowHost.isEffectivelyDarkTheme()
                val fgRef = if (entry.hasCustomColors) {
                    entry.fgColorRef
                } else if (dark) {
                    colorRefRgb(242, 242, 245)
                } else {
                    colorRefRgb(20, 20, 24)
                }
                SetTextColor(hdc, fgRef)

                if (msgInt == WM_CTLCOLOREDIT || msgInt == WM_CTLCOLORLISTBOX) {
                    val editBg = if (entry.hasCustomColors && !entry.isTransparentCtlColor) {
                        entry.bgColorRef
                    } else if (dark) {
                        colorRefRgb(34, 34, 40)
                    } else {
                        colorRefRgb(255, 255, 255)
                    }
                    SetBkMode(hdc, OPAQUE)
                    SetBkColor(hdc, editBg)
                    val brush = entry.bgBrushHandle ?: CreateSolidBrush(editBg).also { entry.bgBrushHandle = it }
                    return brush?.toLong()
                }

                if (entry.isTransparentCtlColor) {
                    SetBkMode(hdc, TRANSPARENT)
                    return GetStockObject(NULL_BRUSH)?.toLong()
                }
                SetBkMode(hdc, OPAQUE)
                SetBkColor(hdc, entry.bgColorRef)
                val brush = entry.bgBrushHandle ?: CreateSolidBrush(entry.bgColorRef).also { entry.bgBrushHandle = it }
                brush?.toLong()
            }

            else -> null
        }
    }

    /**
     * Forwards synthetic top-level `WM_MOUSE*` messages sent to [rootHwnd] cleanly into the
     * deepest visible, non-`pointer-events: none` child `HWND` via `ChildWindowFromPointEx`.
     */
    fun forwardMouseMessageToChild(
        rootHwnd: HWND,
        msg: UINT,
        wParam: WPARAM,
        lParam: LPARAM
    ): LRESULT? {
        val msgInt = msg.toInt()
        val captured = GetCapture()
        if (captured != null && captured != rootHwnd && IsWindow(captured) != 0) {
            val forwardedLParam = if (msgInt == WM_MOUSEWHEEL) {
                lParam
            } else {
                translateClientLParam(rootHwnd, captured, lParam)
            }
            return SendMessageW(captured, msg, wParam, forwardedLParam)
        }

        val clientPtX: Int
        val clientPtY: Int
        if (msgInt == WM_MOUSEWHEEL) {
            val screenX = (lParam and 0xFFFFL).toShort().toInt()
            val screenY = ((lParam shr 16) and 0xFFFFL).toShort().toInt()
            memScoped {
                val pt = alloc<POINT>()
                pt.x = screenX
                pt.y = screenY
                ScreenToClient(rootHwnd, pt.ptr)
                clientPtX = pt.x
                clientPtY = pt.y
            }
        } else {
            clientPtX = (lParam and 0xFFFFL).toShort().toInt()
            clientPtY = ((lParam shr 16) and 0xFFFFL).toShort().toInt()
        }

        val targetChild = findDeepestHitChildHwnd(rootHwnd, clientPtX, clientPtY)
        if (targetChild == null || targetChild == rootHwnd) return null

        val forwardedLParam = if (msgInt == WM_MOUSEWHEEL) {
            lParam
        } else {
            translateClientLParam(rootHwnd, targetChild, lParam)
        }
        return SendMessageW(targetChild, msg, wParam, forwardedLParam)
    }

    fun findDeepestHitChildHwnd(rootHwnd: HWND, clientX: Int, clientY: Int): HWND {
        var current = rootHwnd
        var curX = clientX
        var curY = clientY
        val flags = CWP_SKIPINVISIBLE.toUInt() or CWP_SKIPTRANSPARENT.toUInt()
        for (depth in 0 until 32) {
            val next = ChildWindowFromPointEx(
                current,
                cValue<POINT> {
                    x = curX
                    y = curY
                },
                flags
            )
            if (next == null || next == current) break
            memScoped {
                val pt = alloc<POINT>()
                pt.x = curX
                pt.y = curY
                MapWindowPoints(current, next, pt.ptr, 1u)
                curX = pt.x
                curY = pt.y
            }
            current = next
        }
        return current
    }

    internal fun handleCustomOrSubclassedMessage(
        hwnd: HWND,
        msg: UINT,
        wParam: WPARAM,
        lParam: LPARAM,
        isCustomClass: Boolean
    ): LRESULT? {
        val entry = entriesByHwnd[hwnd.toLong()]

        if (isCustomClass) {
            handleReflectedParentMessage(hwnd, msg, wParam, lParam)?.let { return it }
        }

        entry?.customMessageCallback?.invoke(hwnd, msg, wParam, lParam)?.let { return it }

        val msgInt = msg.toInt()
        when (msgInt) {
            WM_NCDESTROY -> {
                entriesByHwnd.remove(hwnd.toLong())
                auxHadTabStopByHwnd.remove(hwnd.toLong())
                if (hoveredEntry === entry) hoveredEntry = null
                if (activePressEntry === entry) activePressEntry = null
                if (entry?.hwnd == hwnd) {
                    entry.hwnd = null
                }
                if (!isCustomClass) {
                    RemoveWindowSubclass(hwnd, staticCFunction(::win32RetainedSubclassProc), SUBCLASS_ID)
                }
                return null
            }

            WM_NCHITTEST -> {
                if (entry != null &&
                    (entry.pointerEventsNone || !entry.effectiveVisible || entry.effectiveOpacity <= 0.001f)
                ) {
                    return HTTRANSPARENT.toLong()
                }
                val screenX = (lParam and 0xFFFFL).toShort().toInt()
                val screenY = ((lParam shr 16) and 0xFFFFL).toShort().toInt()
                Win32NativeWindowHost.childHwndNcHitTest(hwnd, screenX, screenY)?.let { return it }
                if (isCustomClass && entry?.ownerImpl != null) {
                    val owner = entry.ownerImpl
                    // Transparent layout containers with no paint/border/background or click/hover
                    // interaction still allow ChildWindowFromPointEx to look through empty regions
                    // unless they own click/pointer listeners or opaque visual surface.
                    if (entry.actualWin32ClassName == "GenericUIContainerW" &&
                        owner?.pointerEventsNone() == true
                    ) {
                        return HTTRANSPARENT.toLong()
                    }
                }
                return null
            }

            WM_ERASEBKGND -> {
                if (isCustomClass) return 1L
                return null
            }

            WM_PAINT -> {
                if (!isCustomClass) return null
                memScoped {
                    val ps = alloc<PAINTSTRUCT>()
                    val hdc = BeginPaint(hwnd, ps.ptr)
                    try {
                        val rc = alloc<RECT>()
                        GetClientRect(hwnd, rc.ptr)
                        val w = (rc.right - rc.left).coerceAtLeast(0)
                        val h = (rc.bottom - rc.top).coerceAtLeast(0)
                        if (hdc != null && w > 0 && h > 0) {
                            val cb = entry?.customPaintCallback
                            if (cb != null) {
                                cb(hwnd, hdc, w, h)
                            } else {
                                Win32StyledWidgetHelper.paintCustomEntry(entry, hwnd, hdc, w, h)
                            }
                        }
                    } finally {
                        EndPaint(hwnd, ps.ptr)
                    }
                }
                return 0L
            }

            WM_SIZE -> {
                if (isCustomClass) {
                    val newW = (lParam and 0xFFFFL).toInt()
                    val newH = ((lParam shr 16) and 0xFFFFL).toInt()
                    val owner = entry?.ownerImpl
                    if (owner is com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl<*> &&
                        newW > 0 && newH > 0
                    ) {
                        owner.layoutChildrenInBounds(newW.toFloat(), newH.toFloat())
                    }
                    InvalidateRect(hwnd, null, 0)
                    return 0L
                }
                return null
            }

            WM_DPICHANGED -> {
                val dpi = (wParam.toLong() and 0xFFFFL).toInt().coerceAtLeast(96)
                entry?.onDpiChangedCallback?.invoke(dpi)
                return null
            }

            WM_SETCURSOR -> {
                if (updateCursorForHwnd(hwnd, forceSet = false)) {
                    return 1L
                }
                return null
            }

            WM_GETDLGCODE -> {
                if (entry != null && !entry.isEffectivelyFocusable()) {
                    return 0L
                }
                return null
            }

            WM_SETFOCUS -> {
                if (entry != null && !entry.isEffectivelyFocusable()) {
                    val prevPtr = if (wParam != 0uL) wParam.toLong().toCPointer<HWND__>() else null
                    val prevEntry = prevPtr?.let { entriesByHwnd[it.toLong()] }
                    val target = when {
                        prevPtr != null &&
                            prevPtr != hwnd &&
                            IsWindow(prevPtr) != 0 &&
                            IsChild(hwnd, prevPtr) == 0 &&
                            (prevEntry == null || prevEntry.isEffectivelyFocusable()) -> prevPtr
                        else -> GetAncestor(hwnd, GA_ROOT.toUInt())?.takeIf { it != hwnd && IsWindow(it) != 0 }
                    }
                    SetFocus(target)
                    return 0L
                }
                val owner = entry?.ownerImpl
                owner?.setFocusedState(true)
                if (entry?.isNativeEditTarget == true) {
                    InputFocusManager.setEditableInputFocused(true)
                }
                return null
            }

            WM_KILLFOCUS -> {
                val owner = entry?.ownerImpl
                owner?.setFocusedState(false)
                if (entry?.isNativeEditTarget == true) {
                    InputFocusManager.setEditableInputFocused(false)
                }
                return null
            }

            WM_KEYDOWN -> {
                val owner = entry?.ownerImpl
                if (owner != null) {
                    val vk = (wParam and 0xFFFFuL).toInt()
                    val isRepeat = (lParam and (1L shl 30)) != 0L
                    owner.dispatchKeyDown(
                        KeyEvent(
                            key = virtualKeyToName(vk),
                            keyCode = vk,
                            isRepeat = isRepeat,
                            isTargetTextInput = entry?.isNativeEditTarget == true
                        )
                    )
                }
                return null
            }

            WM_KEYUP -> {
                val owner = entry?.ownerImpl
                if (owner != null) {
                    val vk = (wParam and 0xFFFFuL).toInt()
                    owner.dispatchKeyUp(
                        KeyEvent(
                            key = virtualKeyToName(vk),
                            keyCode = vk,
                            isTargetTextInput = entry?.isNativeEditTarget == true
                        )
                    )
                }
                return null
            }

            WM_MOUSEMOVE -> {
                if (entry != null && !entry.isMouseTracking) {
                    entry.isMouseTracking = true
                    memScoped {
                        val tme = alloc<tagTRACKMOUSEEVENT>()
                        tme.cbSize = sizeOf<tagTRACKMOUSEEVENT>().toUInt()
                        tme.dwFlags = TME_LEAVE.toUInt()
                        tme.hwndTrack = hwnd
                        tme.dwHoverTime = HOVER_DEFAULT.toUInt()
                        TrackMouseEvent(tme.ptr)
                    }
                }
                val prevHovered = hoveredEntry
                if (prevHovered !== entry) {
                    fun implChainToRoot(leaf: Win32HwndHandle?): List<com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl<*>> {
                        if (leaf == null) return emptyList()
                        val chain = ArrayList<com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl<*>>(12)
                        var cur: com.nexustools.genericui.desktop.core.DesktopNativeWidget? = leaf
                        while (cur != null) {
                            val impl = (cur.attributes["nativeImpl"] as? com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl<*>)
                                ?: (cur as? Win32HwndHandle)?.ownerImpl
                            if (impl != null) {
                                chain.add(impl)
                            }
                            cur = cur.parentWidget
                        }
                        return chain
                    }
                    val prevChain = implChainToRoot(prevHovered)
                    val nextChain = implChainToRoot(entry)
                    val nextSet = nextChain.toHashSet()
                    var lca: com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl<*>? = null
                    for (impl in prevChain) {
                        if (impl in nextSet) {
                            lca = impl
                            break
                        }
                    }
                    val pointerEv = buildPointerEvent(hwnd, wParam, lParam, 0)
                    for (impl in prevChain) {
                        if (impl === lca) break
                        impl.dispatchPointerLeave(pointerEv)
                    }
                    hoveredEntry = entry
                    val enterList = ArrayList<com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl<*>>(nextChain.size)
                    for (impl in nextChain) {
                        if (impl === lca) break
                        enterList.add(impl)
                    }
                    for (i in enterList.indices.reversed()) {
                        enterList[i].dispatchPointerEnter(pointerEv)
                    }
                    if (prevHovered?.actualWin32ClassName?.startsWith("GenericUI") == true) {
                        prevHovered.hwnd?.let { InvalidateRect(it, null, 0) }
                    }
                    if (entry?.actualWin32ClassName?.startsWith("GenericUI") == true) {
                        InvalidateRect(hwnd, null, 0)
                    }
                }
                val ev = buildPointerEvent(hwnd, wParam, lParam, 0)
                if (PointerDragManager.isDragging) {
                    val b = entry?.ownerImpl?.getBounds()
                    val dragEv = if (b != null) {
                        val x = (lParam and 0xFFFFL).toShort().toInt()
                        val y = ((lParam shr 16) and 0xFFFFL).toShort().toInt()
                        ev.copy(screenX = b.x.toFloat() + x.toFloat(), screenY = b.y.toFloat() + y.toFloat())
                    } else {
                        ev
                    }
                    PointerDragManager.notifyGlobalPointerMove(dragEv, coordinatesAreTargetLocal = false)
                } else {
                    entry?.ownerImpl?.dispatchPointerMove(ev)
                }
                return if (isCustomClass) 0L else null
            }

            WM_MOUSELEAVE -> {
                if (entry != null) {
                    entry.isMouseTracking = false
                    if (hoveredEntry === entry) {
                        hoveredEntry = null
                        val leaveEv = PointerEvent()
                        var cur: com.nexustools.genericui.desktop.core.DesktopNativeWidget? = entry
                        while (cur != null) {
                            val impl = (cur.attributes["nativeImpl"] as? com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl<*>)
                                ?: (cur as? Win32HwndHandle)?.ownerImpl
                            impl?.dispatchPointerLeave(leaveEv)
                            cur = cur.parentWidget
                        }
                    }
                    if (entry.actualWin32ClassName.startsWith("GenericUI")) {
                        InvalidateRect(hwnd, null, 0)
                    }
                }
                return if (isCustomClass) 0L else null
            }

            WM_LBUTTONDOWN -> {
                val x = (lParam and 0xFFFFL).toShort().toInt()
                val y = ((lParam shr 16) and 0xFFFFL).toShort().toInt()
                if (entry != null) {
                    activePressEntry = entry
                    pressStartClientX = x
                    pressStartClientY = y
                    if (entry.isNativeEditTarget && entry.isEffectivelyFocusable()) {
                        SetFocus(hwnd)
                        InputFocusManager.setEditableInputFocused(true)
                    }
                    if (isCustomClass) {
                        SetCapture(hwnd)
                    }
                    val ev = buildPointerEvent(hwnd, wParam, lParam, button = 0)
                    val owner = entry.ownerImpl
                    owner?.setActivePressedState(true)
                    owner?.dispatchPointerDown(ev)
                    if (isCustomClass && entry.actualWin32ClassName.startsWith("GenericUI")) {
                        InvalidateRect(hwnd, null, 0)
                    }
                }
                return if (isCustomClass) 0L else null
            }

            WM_LBUTTONUP -> {
                val x = (lParam and 0xFFFFL).toShort().toInt()
                val y = ((lParam shr 16) and 0xFFFFL).toShort().toInt()
                if (isCustomClass && GetCapture() == hwnd) {
                    ReleaseCapture()
                }
                val owner = entry?.ownerImpl
                val wasPressedHere = activePressEntry === entry
                val withinClickDistance = wasPressedHere &&
                    abs(x - pressStartClientX) <= CLICK_DRAG_THRESHOLD_PX &&
                    abs(y - pressStartClientY) <= CLICK_DRAG_THRESHOLD_PX
                if (wasPressedHere) {
                    activePressEntry = null
                }
                if (entry != null && owner != null) {
                    owner.setActivePressedState(false)
                    val ev = buildPointerEvent(hwnd, wParam, lParam, button = 0)
                    val wasDragging = PointerDragManager.isDragging
                    if (wasDragging) {
                        val b = owner.getBounds()
                        val dragEv = ev.copy(screenX = b.x.toFloat() + x.toFloat(), screenY = b.y.toFloat() + y.toFloat())
                        PointerDragManager.notifyGlobalPointerUp(dragEv, coordinatesAreTargetLocal = false)
                    }
                    owner.dispatchPointerUp(ev)
                    val suppressed = suppressNextLeftClickForEntry === entry
                    if (suppressed) {
                        suppressNextLeftClickForEntry = null
                    }
                    // For custom retained child HWNDs, fire click strictly on WM_LBUTTONUP within drag threshold.
                    // Standard Win32 controls (like Button) fire BN_CLICKED via WM_COMMAND -> dispatchClick().
                    if (isCustomClass && !wasDragging && !suppressed && withinClickDistance &&
                        !DesktopNativeWidget.interceptPopupClick(ev.x, ev.y, entry)
                    ) {
                        owner.dispatchClick()
                    }
                    if (isCustomClass && entry.actualWin32ClassName.startsWith("GenericUI")) {
                        InvalidateRect(hwnd, null, 0)
                    }
                } else if (PointerDragManager.isDragging) {
                    PointerDragManager.notifyGlobalPointerUp()
                }
                return if (isCustomClass) 0L else null
            }

            WM_LBUTTONDBLCLK -> {
                if (entry != null) {
                    val owner = entry.ownerImpl
                    if (owner != null) {
                        owner.dispatchDoubleClick()
                    }
                    suppressNextLeftClickForEntry = entry
                }
                return if (isCustomClass) 0L else null
            }

            WM_RBUTTONDOWN, WM_MBUTTONDOWN -> {
                val button = if (msgInt == WM_RBUTTONDOWN) 2 else 1
                entry?.ownerImpl?.dispatchPointerDown(buildPointerEvent(hwnd, wParam, lParam, button))
                return if (isCustomClass) 0L else null
            }

            WM_RBUTTONUP, WM_MBUTTONUP -> {
                val button = if (msgInt == WM_RBUTTONUP) 2 else 1
                val ev = buildPointerEvent(hwnd, wParam, lParam, button)
                entry?.ownerImpl?.dispatchPointerUp(ev)
                if (msgInt == WM_RBUTTONUP) {
                    entry?.ownerImpl?.dispatchContextMenu(ev)
                }
                return if (isCustomClass) 0L else null
            }

            WM_MOUSEWHEEL -> {
                val delta = ((wParam.toLong() ushr 16).toShort().toInt()) / 120f
                val screenX = (lParam and 0xFFFFL).toShort().toInt()
                val screenY = ((lParam shr 16) and 0xFFFFL).toShort().toInt()
                val clientPt = memScoped {
                    val pt = alloc<POINT>()
                    pt.x = screenX
                    pt.y = screenY
                    ScreenToClient(hwnd, pt.ptr)
                    pt.x to pt.y
                }
                val keys = (wParam and 0xFFFFuL).toInt()
                val ev = WheelEvent(
                    x = clientPt.first.toFloat(),
                    y = clientPt.second.toFloat(),
                    deltaX = 0f,
                    deltaY = -delta * 48f,
                    shiftKey = (keys and MK_SHIFT) != 0,
                    ctrlKey = (keys and MK_CONTROL) != 0
                )
                entry?.ownerImpl?.dispatchWheel(ev)
                val parent = GetParent(hwnd)
                if (parent != null && IsWindow(parent) != 0 && entriesByHwnd.containsKey(parent.toLong())) {
                    return SendMessageW(parent, msg, wParam, lParam)
                }
                return if (isCustomClass) 0L else null
            }

            else -> return null
        }
    }

    internal fun applyLockedCursorIfAny(forceSet: Boolean = false): Boolean {
        if (!PointerDragManager.isCursorLocked) return false
        val locked = PointerDragManager.lockedCursor ?: return true
        val custom = locked as? CssCustomCursor
        val cssStr = when (locked) {
            is CssCustomCursor -> locked.fallback.cssValue
            is CssCursor -> locked.cssValue
            else -> locked.toCssString()
        }
        return applyResolvedWin32Cursor(custom, cssStr, forceSet = forceSet)
    }

    internal fun updateCursorForHwnd(hwnd: HWND, forceSet: Boolean = false): Boolean {
        if (PointerDragManager.isCursorLocked) {
            return applyLockedCursorIfAny(forceSet = forceSet)
        }
        val entry = entriesByHwnd[hwnd.toLong()]
        val owner = entry?.ownerImpl

        var customCursor: CssCustomCursor? = null
        var cssCursor: String? = null
        var current: DesktopNativeWidget? = entry
        while (current != null) {
            val handle = current as? Win32HwndHandle
            val activeCursor = handle?.activeCursorValue
            val resolvedCustom = (activeCursor as? CssCustomCursor)
                ?: (current.attributes["customCursor"] as? CssCustomCursor)
                ?: (handle?.ownerImpl?.getCustomCursor() as? CssCustomCursor)
            if (resolvedCustom != null) {
                customCursor = resolvedCustom
                cssCursor = resolvedCustom.fallback.cssValue.trim().lowercase()
                break
            }
            val activeCss = (activeCursor as? CssCursor)?.cssValue?.trim()?.lowercase()
            val attrCss = (current.attributes["cursor"] as? String)?.trim()?.lowercase()
            val candidate = when {
                !activeCss.isNullOrBlank() && activeCss != "auto" && activeCss != "inherit" -> activeCss
                !attrCss.isNullOrBlank() && attrCss != "auto" && attrCss != "inherit" -> attrCss
                else -> null
            }
            if (candidate != null) {
                cssCursor = candidate
                break
            }
            current = current.parentWidget
        }

        return applyResolvedWin32Cursor(
            customCursor = customCursor,
            cssCursor = cssCursor,
            forceSet = forceSet,
            entry = entry,
            owner = owner
        )
    }

    private fun applyResolvedWin32Cursor(
        customCursor: CssCustomCursor?,
        cssCursor: String?,
        forceSet: Boolean,
        entry: Win32HwndHandle? = null,
        owner: DesktopBaseNativeImpl<*>? = null
    ): Boolean {
        if (customCursor != null) {
            val hCustom = WindowsCurCodec.createWin32Cursor(customCursor)
            if (hCustom != null) {
                lastCursorIdKey = 0L
                SetCursor(hCustom)
                return true
            }
        }
        val normalized = cssCursor?.trim()?.lowercase()
        if (normalized == "none") {
            lastCursorIdKey = 0L
            SetCursor(null)
            return true
        }
        val cursorId = when {
            entry != null && (!entry.effectiveEnabled || owner?.isEnabled() == false) -> IDC_NO
            normalized == "text" -> IDC_IBEAM
            normalized == "crosshair" -> IDC_CROSS
            normalized == "pointer" || normalized == "grab" || normalized == "grabbing" -> IDC_HAND
            normalized == "wait" -> IDC_WAIT
            normalized == "progress" -> IDC_APPSTARTING
            normalized == "help" -> IDC_HELP
            normalized == "move" || normalized == "all-scroll" -> IDC_SIZEALL
            normalized == "not-allowed" || normalized == "no-drop" -> IDC_NO
            normalized == "ew-resize" || normalized == "col-resize" || normalized == "e-resize" || normalized == "w-resize" -> IDC_SIZEWE
            normalized == "ns-resize" || normalized == "row-resize" || normalized == "n-resize" || normalized == "s-resize" -> IDC_SIZENS
            normalized == "nwse-resize" || normalized == "nw-resize" || normalized == "se-resize" -> IDC_SIZENWSE
            normalized == "nesw-resize" || normalized == "ne-resize" || normalized == "sw-resize" -> IDC_SIZENESW
            normalized == "default" -> IDC_ARROW
            owner is DesktopButtonNativeImpl<*> ||
                owner is DesktopSelectNativeImpl<*> ||
                owner is DesktopCheckboxNativeImpl<*> ||
                owner is DesktopTabNativeImpl<*> -> IDC_HAND
            owner is DesktopTextInputNativeImpl<*> -> IDC_IBEAM
            else -> return false
        }
        val key = cursorId.toLong()
        if (forceSet || key != lastCursorIdKey) {
            lastCursorIdKey = key
            SetCursor(LoadCursorW(null, cursorId))
        }
        return true
    }

    private fun buildPointerEvent(hwnd: HWND, wParam: WPARAM, lParam: LPARAM, button: Int): PointerEvent {
        val x = (lParam and 0xFFFFL).toShort().toInt()
        val y = ((lParam shr 16) and 0xFFFFL).toShort().toInt()
        val keys = (wParam and 0xFFFFuL).toInt()
        var mask = 0
        if ((keys and MK_LBUTTON) != 0) mask = mask or 1
        if ((keys and MK_RBUTTON) != 0) mask = mask or 2
        if ((keys and MK_MBUTTON) != 0) mask = mask or 4
        val screenCoords = memScoped {
            val pt = alloc<POINT>()
            pt.x = x
            pt.y = y
            ClientToScreen(hwnd, pt.ptr)
            pt.x.toFloat() to pt.y.toFloat()
        }
        return PointerEvent(
            x = x.toFloat(),
            y = y.toFloat(),
            screenX = screenCoords.first,
            screenY = screenCoords.second,
            button = button,
            buttonsMask = mask,
            shiftKey = (keys and MK_SHIFT) != 0,
            ctrlKey = (keys and MK_CONTROL) != 0
        )
    }

    private fun translateClientLParam(fromHwnd: HWND, toHwnd: HWND, lParam: LPARAM): LPARAM = memScoped {
        val pt = alloc<POINT>()
        pt.x = (lParam and 0xFFFFL).toShort().toInt()
        pt.y = ((lParam shr 16) and 0xFFFFL).toShort().toInt()
        MapWindowPoints(fromHwnd, toHwnd, pt.ptr, 1u)
        ((pt.y and 0xFFFF).toLong() shl 16) or (pt.x and 0xFFFF).toLong()
    }

    private fun ensureSharedTooltipHwnd(): HWND? {
        sharedTooltipHwnd?.let { existing ->
            if (IsWindow(existing) != 0) return existing
        }
        Win32WinMain.ensureWindowsControlsInitialized()
        val hInst = Win32WinMain.hInstance ?: GetModuleHandleW(null)
        val tt = CreateWindowExW(
            0u,
            "tooltips_class32",
            null,
            WS_POPUP.toUInt(),
            0,
            0,
            0,
            0,
            getOrCreateParkingHwnd(),
            null,
            hInst,
            null
        ) ?: return null
        SendMessageW(tt, TTM_ACTIVATE.toUInt(), 1uL, 0L)
        sharedTooltipHwnd = tt
        return tt
    }

    private fun removeTooltipTool(targetHwnd: HWND) {
        val tt = sharedTooltipHwnd ?: return
        if (IsWindow(tt) == 0) return
        memScoped {
            val ti = alloc<TTTOOLINFOW>()
            ti.cbSize = sizeOf<TTTOOLINFOW>().toUInt()
            ti.hwnd = GetParent(targetHwnd) ?: targetHwnd
            ti.uId = targetHwnd.toLong().toULong()
            SendMessageW(tt, TTM_DELTOOLW.toUInt(), 0uL, ti.ptr.toLong())
        }
    }

    private fun allocateControlId(): Int {
        val id = nextControlId++
        if (nextControlId > 0x7EFF) {
            nextControlId = 0x1000
        }
        return id
    }

    private fun colorRefRgb(r: Int, g: Int, b: Int): UInt =
        (r.coerceIn(0, 255) or (g.coerceIn(0, 255) shl 8) or (b.coerceIn(0, 255) shl 16)).toUInt()

    private fun DesktopBaseNativeImpl<*>.pointerEventsNone(): Boolean =
        (nativeWidget.attributes["pointerEvents"] as? String)?.equals("none", ignoreCase = true) == true

    private fun virtualKeyToName(vk: Int): String = when (vk) {
        0x08 -> "Backspace"
        0x09 -> "Tab"
        0x0D -> "Enter"
        0x1B -> "Escape"
        0x20 -> " "
        0x25 -> "ArrowLeft"
        0x26 -> "ArrowUp"
        0x27 -> "ArrowRight"
        0x28 -> "ArrowDown"
        0x2E -> "Delete"
        in 0x30..0x39 -> vk.toChar().toString()
        in 0x41..0x5A -> vk.toChar().lowercase()
        in 0x70..0x7B -> "F${vk - 0x6F}"
        else -> "Key$vk"
    }
}

private fun win32RetainedCustomChildWndProc(
    hwnd: HWND?,
    msg: UINT,
    wParam: WPARAM,
    lParam: LPARAM
): LRESULT {
    if (hwnd != null) {
        Win32ControlMessageRouter.handleCustomOrSubclassedMessage(hwnd, msg, wParam, lParam, isCustomClass = true)
            ?.let { return it }
    }
    return DefWindowProcW(hwnd, msg, wParam, lParam)
}

private fun win32RetainedSubclassProc(
    hwnd: HWND?,
    msg: UINT,
    wParam: WPARAM,
    lParam: LPARAM,
    uIdSubclass: ULong,
    dwRefData: ULong
): LRESULT {
    if (hwnd != null) {
        Win32ControlMessageRouter.handleCustomOrSubclassedMessage(hwnd, msg, wParam, lParam, isCustomClass = false)
            ?.let { return it }
    }
    return DefSubclassProc(hwnd, msg, wParam, lParam)
}
