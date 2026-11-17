package com.nexustools.genericui.qt

import com.nexustools.genericui.component.shell.IApplication
import com.nexustools.genericui.core.GenericUiAppDescriptor
import com.nexustools.genericui.core.GenericUiLaunchSequence

/**
 * Platform launcher for the Linux Qt 6 backend target in `linuxMain`.
 */
object QtLauncher {

    /**
     * Prepares the [IApplication] for the Qt 6 backend without starting the interactive native event loop.
     */
    fun prepare(
        args: Array<String> = emptyArray(),
        descriptor: GenericUiAppDescriptor
    ): IApplication {
        QtApplicationNativeImpl.installQtThemeDetector()
        return GenericUiLaunchSequence.prepareApplication(
            factory = QtNativeFactory(),
            args = args,
            descriptor = descriptor,
            isSystemTarget = true
        )
    }

    /**
     * Prepares and launches the [IApplication] on the interactive Qt 6 backend.
     */
    fun launch(
        args: Array<String> = emptyArray(),
        descriptor: GenericUiAppDescriptor
    ): IApplication {
        QtApplicationNativeImpl.installQtThemeDetector()
        val prevInteractive = QtApplicationNativeImpl.interactiveQtGuiEnabled
        QtApplicationNativeImpl.interactiveQtGuiEnabled = true
        return try {
            GenericUiLaunchSequence.launch(
                factory = QtNativeFactory(),
                args = args,
                descriptor = descriptor,
                isSystemTarget = true
            )
        } finally {
            QtApplicationNativeImpl.interactiveQtGuiEnabled = prevInteractive
        }
    }
}
