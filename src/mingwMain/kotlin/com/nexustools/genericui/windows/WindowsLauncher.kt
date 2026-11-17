package com.nexustools.genericui.windows

import com.nexustools.genericui.component.shell.IApplication
import com.nexustools.genericui.core.GenericUiAppDescriptor
import com.nexustools.genericui.core.GenericUiLaunchSequence

/**
 * Platform launcher for the Windows Win32 (`:desktop:windows`) backend target.
 */
object WindowsLauncher {

    /**
     * Prepares the [IApplication] for the Windows Win32 backend without starting the message loop.
     */
    fun prepare(
        args: Array<String> = emptyArray(),
        descriptor: GenericUiAppDescriptor
    ): IApplication {
        WindowsApplicationNativeImpl.installWindowsThemeDetector()
        return GenericUiLaunchSequence.prepareApplication(
            factory = WindowsNativeFactory(),
            args = args,
            descriptor = descriptor,
            isSystemTarget = true
        )
    }

    /**
     * Prepares and launches the [IApplication] on the Windows Win32 backend.
     */
    fun launch(
        args: Array<String> = emptyArray(),
        descriptor: GenericUiAppDescriptor
    ): IApplication {
        WindowsApplicationNativeImpl.installWindowsThemeDetector()
        val prevInteractive = WindowsApplicationNativeImpl.interactiveWin32GuiEnabled
        WindowsApplicationNativeImpl.interactiveWin32GuiEnabled = true
        return try {
            GenericUiLaunchSequence.launch(
                factory = WindowsNativeFactory(),
                args = args,
                descriptor = descriptor,
                isSystemTarget = true
            )
        } finally {
            WindowsApplicationNativeImpl.interactiveWin32GuiEnabled = prevInteractive
        }
    }
}
