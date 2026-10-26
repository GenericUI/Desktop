package com.nexustools.genericui.windows

import com.nexustools.genericui.component.shell.IApplication
import com.nexustools.genericui.core.GenericUiAppDescriptor
import com.nexustools.genericui.core.GenericUiLaunchSequence

/**
 * Platform launcher for the Windows (`:desktop:windows`) backend target.
 */
object WindowsLauncher {

    /**
     * When `true`, suppresses interactive Win32 GUI window creation and message loop execution.
     */
    var headlessMode: Boolean = false

    /**
     * Prepares the [IApplication] for the Windows backend without starting the interactive Win32 message loop.
     */
    fun prepare(
        args: Array<String> = emptyArray(),
        descriptor: GenericUiAppDescriptor
    ): IApplication {
        val isHeadless = headlessMode ||
            args.contains("--headless-verify") ||
            args.contains("--headless")
        if (isHeadless) {
            WindowsApplicationNativeImpl.interactiveWin32GuiEnabled = false
        }
        return GenericUiLaunchSequence.prepareApplication(
            factory = WindowsNativeFactory(),
            args = args,
            descriptor = descriptor,
            isSystemTarget = true
        )
    }

    /**
     * Initializes the Win32 process startup state, prepares the [IApplication], and runs the
     * Win32 message loop unless `"--headless-verify"` or `"--headless"` is present in [args].
     */
    fun launch(
        args: Array<String> = emptyArray(),
        descriptor: GenericUiAppDescriptor
    ): IApplication {
        Win32WinMain.initializeFromProcessStartup(explicitArgs = args)
        val isHeadless = headlessMode ||
            args.contains("--headless-verify") ||
            args.contains("--headless") ||
            Win32WinMain.commandLineArgs.contains("--headless-verify") ||
            Win32WinMain.commandLineArgs.contains("--headless")
        WindowsApplicationNativeImpl.interactiveWin32GuiEnabled = !isHeadless
        return GenericUiLaunchSequence.launch(
            factory = WindowsNativeFactory(),
            args = args,
            descriptor = descriptor,
            isSystemTarget = true
        )
    }
}
