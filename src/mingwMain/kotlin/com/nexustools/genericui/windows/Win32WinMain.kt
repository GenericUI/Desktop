@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.nexustools.genericui.windows

import kotlinx.cinterop.*
import platform.windows.*

/**
 * Native Win32 `WinMain` bootstrapper and process startup state manager for `:desktop:windows`.
 *
 * Captures `hInstance`, `hPrevInstance`, `lpCmdLine`, and `nCmdShow` from either the explicit
 * `@CName("WinMain")` entry point or Win32 `GetModuleHandleW` / `GetCommandLineW` / `GetStartupInfoW`,
 * initializes Per-Monitor DPI awareness and OLE/COM apartment state, and ensures the main Win32
 * window is shown with a valid `nCmdShow` even when launched from a background shell.
 */
object Win32WinMain {

    var hInstance: HINSTANCE? = null
        private set

    var hPrevInstance: HINSTANCE? = null
        private set

    var lpCmdLine: String = ""
        private set

    var nCmdShow: Int = SW_SHOWNORMAL
        private set

    var commandLineArgs: Array<String> = emptyArray()
        private set

    var isInitialized: Boolean = false
        private set

    var registeredEntryPoint: ((Array<String>) -> Int)? = null

    private var comCtlInitialized: Boolean = false
    private var comCtlActCtxHandle: HANDLE? = null
    private var comCtlActCtxCookie: ULong = 0uL
    private var msftEditModule: HMODULE? = null
    private var preferredAppModeFn: CPointer<CFunction<(Int) -> Int>>? = null
    private var allowDarkModeForWindowFn: CPointer<CFunction<(HWND?, Int) -> Int>>? = null
    private var flushMenuThemesFn: CPointer<CFunction<() -> Unit>>? = null
    private var lastPreferredAppMode: Int = -1

    /**
     * Ensures Per-Monitor DPI v2, ComCtl32 v6 activation context (before `InitCommonControlsEx`),
     * common control class registration, RichEdit (`RICHEDIT50W`), UxTheme dark mode ordinals, and OLE
     * are initialized cleanly once per process. Idempotent and safe before any `Window.show()`.
     */
    fun ensureWindowsControlsInitialized() {
        if (comCtlInitialized) return
        comCtlInitialized = true
        memScoped {
            val user32 = GetModuleHandleW("user32.dll") ?: LoadLibraryW("user32.dll")
            val setDpiContextFn = user32?.let {
                GetProcAddress(it, "SetProcessDpiAwarenessContext")
                    ?.reinterpret<CFunction<(COpaquePointer?) -> Int>>()
            }
            val dpiV2Applied = setDpiContextFn != null && setDpiContextFn((-4L).toCPointer()) != 0
            if (!dpiV2Applied) {
                SetProcessDPIAware()
            }

            // Activate the ComCtl32 v6 manifest bundled in shell32.dll resource #124
            // strictly BEFORE calling InitCommonControlsEx so every standard HWND gets v6 theming.
            val shell32 = GetModuleHandleW("shell32.dll") ?: LoadLibraryW("shell32.dll")
            if (shell32 != null) {
                val actCtx = alloc<ACTCTXW>()
                actCtx.cbSize = sizeOf<ACTCTXW>().toUInt()
                actCtx.dwFlags = ACTCTX_FLAG_RESOURCE_NAME_VALID.toUInt() or ACTCTX_FLAG_HMODULE_VALID.toUInt()
                actCtx.lpResourceName = 124L.toCPointer()
                actCtx.hModule = shell32
                val hActCtx = CreateActCtxW(actCtx.ptr)
                if (hActCtx != null && hActCtx != INVALID_HANDLE_VALUE) {
                    val cookieVar = alloc<ULongVar>()
                    if (ActivateActCtx(hActCtx, cookieVar.ptr) != 0) {
                        comCtlActCtxHandle = hActCtx
                        comCtlActCtxCookie = cookieVar.value
                    }
                }
            }

            val icc = alloc<tagINITCOMMONCONTROLSEX>()
            icc.dwSize = sizeOf<tagINITCOMMONCONTROLSEX>().toUInt()
            icc.dwICC = ICC_STANDARD_CLASSES.toUInt() or
                ICC_WIN95_CLASSES.toUInt() or
                ICC_BAR_CLASSES.toUInt() or
                ICC_PROGRESS_CLASS.toUInt() or
                ICC_TAB_CLASSES.toUInt() or
                ICC_LISTVIEW_CLASSES.toUInt() or
                ICC_TREEVIEW_CLASSES.toUInt() or
                ICC_DATE_CLASSES.toUInt() or
                ICC_UPDOWN_CLASS.toUInt() or
                ICC_LINK_CLASS.toUInt()
            InitCommonControlsEx(icc.ptr)

            msftEditModule = GetModuleHandleW("Msftedit.dll") ?: LoadLibraryW("Msftedit.dll")

            val uxtheme = GetModuleHandleW("uxtheme.dll") ?: LoadLibraryW("uxtheme.dll")
            if (uxtheme != null) {
                preferredAppModeFn = GetProcAddress(uxtheme, 135L.toCPointer<ByteVar>())
                    ?.reinterpret<CFunction<(Int) -> Int>>()
                allowDarkModeForWindowFn = GetProcAddress(uxtheme, 133L.toCPointer<ByteVar>())
                    ?.reinterpret<CFunction<(HWND?, Int) -> Int>>()
                flushMenuThemesFn = GetProcAddress(uxtheme, 136L.toCPointer<ByteVar>())
                    ?.reinterpret<CFunction<() -> Unit>>()
                preferredAppModeFn?.invoke(1) // AllowDark
                lastPreferredAppMode = 1
            }

            OleInitialize(null)
        }
    }

    /**
     * Allows or disables UxTheme dark mode chrome for an individual top-level or child [hwnd] via
     * `uxtheme.dll` ordinal #133 (`AllowDarkModeForWindow`).
     */
    fun allowDarkModeForWindow(hwnd: HWND?, allow: Boolean): Boolean {
        if (hwnd == null) return false
        ensureWindowsControlsInitialized()
        val fn = allowDarkModeForWindowFn ?: return false
        return fn(hwnd, if (allow) 1 else 0) != 0
    }

    /**
     * Synchronizes process-wide Win32 `HMENU` popup dark/light theming via `uxtheme.dll`
     * ordinal #135 (`SetPreferredAppMode`: `2 = ForceDark`, `3 = ForceLight`) and ordinal #136
     * (`FlushMenuThemes`) so Win32 popup context menus follow the app's active dark/light mode.
     */
    fun syncUxThemeMenuDarkMode(isDark: Boolean) {
        ensureWindowsControlsInitialized()
        val desiredMode = if (isDark) 2 else 3
        if (lastPreferredAppMode != desiredMode) {
            lastPreferredAppMode = desiredMode
            preferredAppModeFn?.invoke(desiredMode)
            flushMenuThemesFn?.invoke()
        }
    }

    fun initializeFromProcessStartup(
        explicitHInstance: HINSTANCE? = null,
        explicitPrevInstance: HINSTANCE? = null,
        explicitCmdLine: String? = null,
        explicitCmdShow: Int? = null,
        explicitArgs: Array<String>? = null
    ) = memScoped {
        if (!isInitialized) {
            val defaultDesktop = OpenDesktopW("Default", 0u, 0, GENERIC_ALL.toUInt())
            if (defaultDesktop != null) {
                SetThreadDesktop(defaultDesktop)
            }
            ensureWindowsControlsInitialized()
            isInitialized = true
        }

        hInstance = explicitHInstance ?: GetModuleHandleW(null)
        hPrevInstance = explicitPrevInstance

        val rawCmdLine = explicitCmdLine ?: GetCommandLineW()?.toKString() ?: ""
        lpCmdLine = rawCmdLine

        if (explicitArgs != null && explicitArgs.isNotEmpty()) {
            commandLineArgs = explicitArgs
        } else if (commandLineArgs.isEmpty() && rawCmdLine.isNotBlank()) {
            commandLineArgs = parseCommandLineToArgs(rawCmdLine)
        }

        val resolvedShow = if (explicitCmdShow != null && explicitCmdShow != SW_HIDE) {
            explicitCmdShow
        } else {
            val startupInfo = alloc<STARTUPINFOW>()
            startupInfo.cb = sizeOf<STARTUPINFOW>().toUInt()
            GetStartupInfoW(startupInfo.ptr)
            val hasUseShowWindow = (startupInfo.dwFlags and STARTF_USESHOWWINDOW.toUInt()) != 0u
            val showVal = startupInfo.wShowWindow.toInt()
            if (hasUseShowWindow && showVal != SW_HIDE) {
                showVal
            } else {
                SW_SHOWNORMAL
            }
        }
        nCmdShow = resolvedShow

        if (commandLineArgs.contains("--headless-verify")) {
            WindowsApplicationNativeImpl.interactiveWin32GuiEnabled = false
        }
    }

    /**
     * Executes a Windows GUI application entry point with full `WinMain` initialization.
     */
    fun launch(args: Array<String> = emptyArray(), block: () -> Unit): Int {
        initializeFromProcessStartup(explicitArgs = args)
        if (!args.contains("--headless-verify") && !commandLineArgs.contains("--headless-verify")) {
            WindowsApplicationNativeImpl.interactiveWin32GuiEnabled = true
        }
        block()
        return 0
    }

    private fun parseCommandLineToArgs(rawCmdLine: String): Array<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        for (ch in rawCmdLine.trim()) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                (ch == ' ' || ch == '\t') && !inQuotes -> {
                    if (current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current.clear()
                    }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) {
            tokens.add(current.toString())
        }
        return if (tokens.size > 1) tokens.drop(1).toTypedArray() else emptyArray()
    }
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)
@CName("Win32GenericUiWinMain")
fun win32GenericUiWinMain(
    hInstance: HINSTANCE?,
    hPrevInstance: HINSTANCE?,
    lpCmdLine: LPSTR?,
    nCmdShow: Int
): Int {
    Win32WinMain.initializeFromProcessStartup(
        explicitHInstance = hInstance,
        explicitPrevInstance = hPrevInstance,
        explicitCmdLine = lpCmdLine?.toKString(),
        explicitCmdShow = nCmdShow
    )
    WindowsApplicationNativeImpl.interactiveWin32GuiEnabled =
        !Win32WinMain.commandLineArgs.contains("--headless-verify")
    return Win32WinMain.registeredEntryPoint?.invoke(Win32WinMain.commandLineArgs) ?: 0
}
