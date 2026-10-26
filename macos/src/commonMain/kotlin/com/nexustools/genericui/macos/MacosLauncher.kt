package com.nexustools.genericui.macos

import com.nexustools.genericui.component.shell.IApplication
import com.nexustools.genericui.core.GenericUiAppDescriptor
import com.nexustools.genericui.core.GenericUiLaunchSequence

/**
 * Platform launcher for the macOS (`:desktop:macos`) backend target.
 */
object MacosLauncher {

    /**
     * Prepares the [IApplication] for the macOS AppKit backend without starting the event loop.
     */
    fun prepare(
        args: Array<String> = emptyArray(),
        descriptor: GenericUiAppDescriptor
    ): IApplication = GenericUiLaunchSequence.prepareApplication(
        factory = MacosNativeFactory(),
        args = args,
        descriptor = descriptor,
        isSystemTarget = true
    )

    /**
     * Prepares and launches the [IApplication] on the macOS AppKit backend.
     */
    fun launch(
        args: Array<String> = emptyArray(),
        descriptor: GenericUiAppDescriptor
    ): IApplication = GenericUiLaunchSequence.launch(
        factory = MacosNativeFactory(),
        args = args,
        descriptor = descriptor,
        isSystemTarget = true
    )
}
