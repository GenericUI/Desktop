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
            SetProcessDPIAware()
            OleInitialize(null)
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
