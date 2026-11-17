@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.desktop.dialog.DesktopStandardDialogsNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.toLong
import kotlinx.cinterop.wcstr
import platform.windows.BN_CLICKED
import platform.windows.BS_DEFPUSHBUTTON
import platform.windows.BS_PUSHBUTTON
import platform.windows.CC_FULLOPEN
import platform.windows.CC_RGBINIT
import platform.windows.CHOOSECOLORW
import platform.windows.COLOR_BTNFACE
import platform.windows.ChooseColorW
import platform.windows.CreateSolidBrush
import platform.windows.CreateWindowExW
import platform.windows.DEFAULT_GUI_FONT
import platform.windows.DefWindowProcW
import platform.windows.DestroyWindow
import platform.windows.DispatchMessageW
import platform.windows.ES_AUTOHSCROLL
import platform.windows.EnableWindow
import platform.windows.GetMessageW
import platform.windows.GetModuleHandleW
import platform.windows.GetStockObject
import platform.windows.GetSystemMetrics
import platform.windows.GetWindowTextLengthW
import platform.windows.GetWindowTextW
import platform.windows.HBRUSH
import platform.windows.HMENU
import platform.windows.HWND
import platform.windows.IDCANCEL
import platform.windows.IDC_ARROW
import platform.windows.IDOK
import platform.windows.IDYES
import platform.windows.LBN_DBLCLK
import platform.windows.LBS_NOTIFY
import platform.windows.LB_ADDSTRING
import platform.windows.LB_GETCURSEL
import platform.windows.LB_SETCURSEL
import platform.windows.LPARAM
import platform.windows.LRESULT
import platform.windows.LoadCursorW
import platform.windows.MB_ICONERROR
import platform.windows.MB_ICONINFORMATION
import platform.windows.MB_ICONWARNING
import platform.windows.MB_OK
import platform.windows.MB_YESNO
import platform.windows.MSG
import platform.windows.MessageBoxW
import platform.windows.RegisterClassExW
import platform.windows.SM_CXSCREEN
import platform.windows.SM_CYSCREEN
import platform.windows.SendMessageW
import platform.windows.SetFocus
import platform.windows.ShowWindow
import platform.windows.SW_SHOWNORMAL
import platform.windows.TranslateMessage
import platform.windows.UINT
import platform.windows.UpdateWindow
import platform.windows.VK_ESCAPE
import platform.windows.VK_RETURN
import platform.windows.WCHARVar
import platform.windows.WM_CLOSE
import platform.windows.WM_COMMAND
import platform.windows.WM_KEYDOWN
import platform.windows.WM_SETFONT
import platform.windows.WNDCLASSEXW
import platform.windows.WPARAM
import platform.windows.WS_BORDER
import platform.windows.WS_CAPTION
import platform.windows.WS_CHILD
import platform.windows.WS_EX_DLGMODALFRAME
import platform.windows.WS_POPUP
import platform.windows.WS_SYSMENU
import platform.windows.WS_TABSTOP
import platform.windows.WS_VISIBLE
import platform.windows.WS_VSCROLL

/**
 * Windows native standard dialogs (`MessageBoxW`, `ChooseColorW`, and Win32 modal prompt/choice `HWND`)
 * implementation of [com.nexustools.genericui.nativeimpl.dialog.StandardDialogsNativeImpl].
 */
class WindowsStandardDialogsNativeImpl : DesktopStandardDialogsNativeImpl(
    backendToolkit = "Win32 (MessageBoxW / ChooseColorW / Win32 Modal)"
) {
    private val customPalette = IntArray(16) { 0x00FFFFFF }

    override fun showAlert(
        owner: WindowNativeImpl<*>?,
        title: String,
        message: String,
        type: BootstrapType,
        okButtonText: String,
        onDismissed: () -> Unit
    ) {
        if (!WindowsApplicationNativeImpl.interactiveWin32GuiEnabled) {
            super.showAlert(owner, title, message, type, okButtonText, onDismissed)
            return
        }
        GenericUiThreading.runOnUi {
            MessageBoxW(
                Win32NativeWindowHost.activeHwnd,
                message,
                title.ifBlank { "Notice" },
                (MB_OK or iconFlagFor(type)).toUInt()
            )
            GenericUiThreading.runOnLogic {
                onDismissed()
            }
        }
    }

    override fun showConfirm(
        owner: WindowNativeImpl<*>?,
        title: String,
        message: String,
        confirmText: String,
        cancelText: String,
        type: BootstrapType,
        onResult: (Boolean) -> Unit
    ) {
        if (!WindowsApplicationNativeImpl.interactiveWin32GuiEnabled) {
            super.showConfirm(owner, title, message, confirmText, cancelText, type, onResult)
            return
        }
        GenericUiThreading.runOnUi {
            val result = MessageBoxW(
                Win32NativeWindowHost.activeHwnd,
                message,
                title.ifBlank { "Confirm" },
                (MB_YESNO or iconFlagFor(type)).toUInt()
            )
            val confirmed = result == IDYES
            GenericUiThreading.runOnLogic {
                onResult(confirmed)
            }
        }
    }

    override fun showPrompt(
        owner: WindowNativeImpl<*>?,
        title: String,
        message: String,
        defaultValue: String,
        placeholder: String,
        confirmText: String,
        cancelText: String,
        onResult: (String?) -> Unit
    ) {
        if (!WindowsApplicationNativeImpl.interactiveWin32GuiEnabled) {
            super.showPrompt(owner, title, message, defaultValue, placeholder, confirmText, cancelText, onResult)
            return
        }
        GenericUiThreading.runOnUi {
            val chosen = runWin32PromptModal(
                ownerHwnd = Win32NativeWindowHost.activeHwnd,
                title = title.ifBlank { "Input" },
                message = message,
                defaultValue = defaultValue,
                confirmText = confirmText.ifBlank { "OK" },
                cancelText = cancelText.ifBlank { "Cancel" }
            )
            GenericUiThreading.runOnLogic {
                onResult(chosen)
            }
        }
    }

    override fun showChoice(
        owner: WindowNativeImpl<*>?,
        title: String,
        message: String,
        options: List<String>,
        selectedIndex: Int,
        onResult: (Int?) -> Unit
    ) {
        if (!WindowsApplicationNativeImpl.interactiveWin32GuiEnabled) {
            super.showChoice(owner, title, message, options, selectedIndex, onResult)
            return
        }
        GenericUiThreading.runOnUi {
            val chosen = if (options.isEmpty()) {
                null
            } else {
                runWin32ChoiceModal(
                    ownerHwnd = Win32NativeWindowHost.activeHwnd,
                    title = title.ifBlank { "Choose Option" },
                    message = message,
                    options = options,
                    selectedIndex = selectedIndex
                )
            }
            GenericUiThreading.runOnLogic {
                onResult(chosen)
            }
        }
    }

    override fun showColorChooser(
        owner: WindowNativeImpl<*>?,
        title: String,
        initialColor: RgbaColor,
        onResult: (RgbaColor?) -> Unit
    ) {
        if (!WindowsApplicationNativeImpl.interactiveWin32GuiEnabled) {
            super.showColorChooser(owner, title, initialColor, onResult)
            return
        }
        GenericUiThreading.runOnUi {
            val chosen = memScoped {
                val custColors = allocArray<UIntVar>(16)
                for (i in 0 until 16) {
                    custColors[i] = customPalette[i].toUInt()
                }
                val initialColorRef = ((initialColor.b.coerceIn(0, 255) shl 16) or
                    (initialColor.g.coerceIn(0, 255) shl 8) or
                    initialColor.r.coerceIn(0, 255)).toUInt()

                val cc = alloc<CHOOSECOLORW>()
                cc.lStructSize = sizeOf<CHOOSECOLORW>().toUInt()
                cc.hwndOwner = Win32NativeWindowHost.activeHwnd
                cc.lpCustColors = custColors
                cc.rgbResult = initialColorRef
                cc.Flags = (CC_RGBINIT or CC_FULLOPEN).toUInt()

                val accepted = ChooseColorW(cc.ptr) != 0
                if (!accepted) {
                    null
                } else {
                    for (i in 0 until 16) {
                        customPalette[i] = custColors[i].toInt()
                    }
                    val colorRef = cc.rgbResult.toInt()
                    RgbaColor(
                        r = colorRef and 0xFF,
                        g = (colorRef ushr 8) and 0xFF,
                        b = (colorRef ushr 16) and 0xFF,
                        a = initialColor.a
                    )
                }
            }
            GenericUiThreading.runOnLogic {
                onResult(chosen)
            }
        }
    }

    private fun runWin32PromptModal(
        ownerHwnd: HWND?,
        title: String,
        message: String,
        defaultValue: String,
        confirmText: String,
        cancelText: String
    ): String? {
        ensureModalClassRegistered()
        val width = 420
        val height = 172
        val dlgHwnd = createCenteredModalHwnd(ownerHwnd, title, width, height) ?: return null
        val hFont = GetStockObject(DEFAULT_GUI_FONT)
        val hInst = Win32WinMain.hInstance ?: GetModuleHandleW(null)

        val labelHwnd = CreateWindowExW(
            0u, "STATIC", message,
            (WS_CHILD or WS_VISIBLE).toUInt(),
            16, 14, width - 48, 36, dlgHwnd, null, hInst, null
        )
        val editHwnd = CreateWindowExW(
            0u, "EDIT", defaultValue,
            (WS_CHILD or WS_VISIBLE or WS_BORDER or WS_TABSTOP or ES_AUTOHSCROLL).toUInt(),
            16, 54, width - 48, 26, dlgHwnd, IDC_EDIT.toLong().toCPointer<platform.windows.HMENU__>(), hInst, null
        )
        val okBtn = CreateWindowExW(
            0u, "BUTTON", confirmText,
            (WS_CHILD or WS_VISIBLE or WS_TABSTOP or BS_DEFPUSHBUTTON).toUInt(),
            width - 196, height - 74, 82, 28, dlgHwnd, IDOK.toLong().toCPointer<platform.windows.HMENU__>(), hInst, null
        )
        val cancelBtn = CreateWindowExW(
            0u, "BUTTON", cancelText,
            (WS_CHILD or WS_VISIBLE or WS_TABSTOP or BS_PUSHBUTTON).toUInt(),
            width - 106, height - 74, 82, 28, dlgHwnd, IDCANCEL.toLong().toCPointer<platform.windows.HMENU__>(), hInst, null
        )
        for (ctrl in listOfNotNull(labelHwnd, editHwnd, okBtn, cancelBtn)) {
            hFont?.let { f -> SendMessageW(ctrl, WM_SETFONT.toUInt(), f.toLong().toULong(), 1L) }
        }
        editHwnd?.let { SetFocus(it) }

        val confirmed = runModalMessageLoop(dlgHwnd, ownerHwnd)
        val text = if (confirmed && editHwnd != null) readWindowTextW(editHwnd) else null
        DestroyWindow(dlgHwnd)
        return text
    }

    private fun runWin32ChoiceModal(
        ownerHwnd: HWND?,
        title: String,
        message: String,
        options: List<String>,
        selectedIndex: Int
    ): Int? {
        ensureModalClassRegistered()
        val width = 420
        val height = 276
        val dlgHwnd = createCenteredModalHwnd(ownerHwnd, title, width, height) ?: return null
        val hFont = GetStockObject(DEFAULT_GUI_FONT)
        val hInst = Win32WinMain.hInstance ?: GetModuleHandleW(null)

        val labelHwnd = CreateWindowExW(
            0u, "STATIC", message,
            (WS_CHILD or WS_VISIBLE).toUInt(),
            16, 14, width - 48, 28, dlgHwnd, null, hInst, null
        )
        val listHwnd = CreateWindowExW(
            0u, "LISTBOX", "",
            (WS_CHILD or WS_VISIBLE or WS_BORDER or WS_TABSTOP or WS_VSCROLL or LBS_NOTIFY).toUInt(),
            16, 46, width - 48, 134, dlgHwnd, IDC_LIST.toLong().toCPointer<platform.windows.HMENU__>(), hInst, null
        )
        val okBtn = CreateWindowExW(
            0u, "BUTTON", "OK",
            (WS_CHILD or WS_VISIBLE or WS_TABSTOP or BS_DEFPUSHBUTTON).toUInt(),
            width - 196, height - 74, 82, 28, dlgHwnd, IDOK.toLong().toCPointer<platform.windows.HMENU__>(), hInst, null
        )
        val cancelBtn = CreateWindowExW(
            0u, "BUTTON", "Cancel",
            (WS_CHILD or WS_VISIBLE or WS_TABSTOP or BS_PUSHBUTTON).toUInt(),
            width - 106, height - 74, 82, 28, dlgHwnd, IDCANCEL.toLong().toCPointer<platform.windows.HMENU__>(), hInst, null
        )
        for (ctrl in listOfNotNull(labelHwnd, listHwnd, okBtn, cancelBtn)) {
            hFont?.let { f -> SendMessageW(ctrl, WM_SETFONT.toUInt(), f.toLong().toULong(), 1L) }
        }
        if (listHwnd != null) {
            memScoped {
                for (opt in options) {
                    val ptr = opt.wcstr.ptr
                    SendMessageW(listHwnd, LB_ADDSTRING.toUInt(), 0uL, ptr.toLong())
                }
            }
            if (selectedIndex in options.indices) {
                SendMessageW(listHwnd, LB_SETCURSEL.toUInt(), selectedIndex.toULong(), 0L)
            }
            SetFocus(listHwnd)
        }

        val confirmed = runModalMessageLoop(dlgHwnd, ownerHwnd)
        val chosenIndex = if (confirmed && listHwnd != null) {
            val idx = SendMessageW(listHwnd, LB_GETCURSEL.toUInt(), 0uL, 0L).toInt()
            idx.takeIf { it in options.indices }
        } else {
            null
        }
        DestroyWindow(dlgHwnd)
        return chosenIndex
    }

    private fun createCenteredModalHwnd(ownerHwnd: HWND?, title: String, width: Int, height: Int): HWND? {
        val screenW = GetSystemMetrics(SM_CXSCREEN).coerceAtLeast(800)
        val screenH = GetSystemMetrics(SM_CYSCREEN).coerceAtLeast(600)
        val x = ((screenW - width) / 2).coerceAtLeast(0)
        val y = ((screenH - height) / 2).coerceAtLeast(0)
        val hInst = Win32WinMain.hInstance ?: GetModuleHandleW(null)
        return CreateWindowExW(
            dwExStyle = WS_EX_DLGMODALFRAME.toUInt(),
            lpClassName = MODAL_CLASS_NAME,
            lpWindowName = title,
            dwStyle = WS_POPUP or WS_CAPTION.toUInt() or WS_SYSMENU.toUInt(),
            X = x,
            Y = y,
            nWidth = width,
            nHeight = height,
            hWndParent = ownerHwnd,
            hMenu = null,
            hInstance = hInst,
            lpParam = null
        )
    }

    private fun runModalMessageLoop(dlgHwnd: HWND, ownerHwnd: HWND?): Boolean = memScoped {
        val key = dlgHwnd.toLong()
        val state = ModalSessionState()
        modalStatesByHwnd[key] = state
        if (ownerHwnd != null) {
            EnableWindow(ownerHwnd, 0)
        }
        ShowWindow(dlgHwnd, SW_SHOWNORMAL)
        UpdateWindow(dlgHwnd)

        try {
            val msg = alloc<MSG>()
            while (!state.done && GetMessageW(msg.ptr, null, 0u, 0u) > 0) {
                if (msg.message == WM_KEYDOWN.toUInt()) {
                    when (msg.wParam.toInt()) {
                        VK_RETURN -> {
                            state.accepted = true
                            state.done = true
                            break
                        }
                        VK_ESCAPE -> {
                            state.accepted = false
                            state.done = true
                            break
                        }
                    }
                }
                TranslateMessage(msg.ptr)
                DispatchMessageW(msg.ptr)
            }
            state.accepted
        } finally {
            modalStatesByHwnd.remove(key)
            if (ownerHwnd != null) {
                EnableWindow(ownerHwnd, 1)
            }
        }
    }

    private fun readWindowTextW(hwnd: HWND): String = memScoped {
        val len = GetWindowTextLengthW(hwnd).coerceAtLeast(0)
        val buf = allocArray<WCHARVar>(len + 2)
        GetWindowTextW(hwnd, buf, len + 1)
        buf.toKString()
    }

    private fun ensureModalClassRegistered() {
        if (modalClassRegistered) return
        memScoped {
            val hInst = Win32WinMain.hInstance ?: GetModuleHandleW(null)
            val wc = alloc<WNDCLASSEXW>()
            wc.cbSize = sizeOf<WNDCLASSEXW>().toUInt()
            wc.lpfnWndProc = staticCFunction(::win32ModalDialogWndProc)
            wc.hInstance = hInst
            wc.hCursor = LoadCursorW(null, IDC_ARROW)
            wc.hbrBackground = (COLOR_BTNFACE + 1).toLong().toCPointer<platform.windows.HBRUSH__>()
                ?: CreateSolidBrush(0x00F0F0F0u)
            wc.lpszClassName = MODAL_CLASS_NAME.wcstr.ptr
            RegisterClassExW(wc.ptr)
            modalClassRegistered = true
        }
    }

    private fun iconFlagFor(type: BootstrapType): Int = when (type) {
        BootstrapType.DANGER -> MB_ICONERROR
        BootstrapType.WARNING -> MB_ICONWARNING
        else -> MB_ICONINFORMATION
    }

    internal class ModalSessionState {
        var done: Boolean = false
        var accepted: Boolean = false
    }

    internal companion object {
        private const val MODAL_CLASS_NAME = "GenericUiWin32ModalDialogW"
        const val IDC_EDIT = 2001
        const val IDC_LIST = 2002

        private var modalClassRegistered: Boolean = false
        val modalStatesByHwnd = LinkedHashMap<Long, ModalSessionState>()
    }
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun win32ModalDialogWndProc(hwnd: HWND?, msg: UINT, wParam: WPARAM, lParam: LPARAM): LRESULT {
    val state = hwnd?.let { WindowsStandardDialogsNativeImpl.modalStatesByHwnd[it.toLong()] }
    if (state != null) {
        when (msg.toInt()) {
            WM_COMMAND -> {
                val cmdId = (wParam.toLong() and 0xFFFF).toInt()
                val notify = ((wParam.toLong() ushr 16) and 0xFFFF).toInt()
                when {
                    cmdId == IDOK && notify == BN_CLICKED -> {
                        state.accepted = true
                        state.done = true
                        return 0L
                    }
                    cmdId == IDCANCEL && notify == BN_CLICKED -> {
                        state.accepted = false
                        state.done = true
                        return 0L
                    }
                    cmdId == WindowsStandardDialogsNativeImpl.IDC_LIST && notify == LBN_DBLCLK -> {
                        state.accepted = true
                        state.done = true
                        return 0L
                    }
                }
            }
            WM_CLOSE -> {
                state.accepted = false
                state.done = true
                return 0L
            }
        }
    }
    return DefWindowProcW(hwnd, msg, wParam, lParam)
}
