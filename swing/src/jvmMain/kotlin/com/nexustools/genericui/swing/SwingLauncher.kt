package com.nexustools.genericui.swing

import com.nexustools.genericui.component.shell.IApplication
import com.nexustools.genericui.core.GenericUiAppDescriptor
import com.nexustools.genericui.core.GenericUiLaunchSequence

/**
 * Platform launcher for the JVM Java Swing (`:desktop:swing`) backend target.
 */
object SwingLauncher {

    /**
     * Prepares the [IApplication] for the Java Swing backend without starting the event loop.
     */
    fun prepare(
        args: Array<String> = emptyArray(),
        descriptor: GenericUiAppDescriptor
    ): IApplication {
        SwingApplicationImpl.installSwingThemeDetector()
        return GenericUiLaunchSequence.prepareApplication(
            factory = SwingNativeFactory(),
            args = args,
            descriptor = descriptor,
            isSystemTarget = true
        )
    }

    /**
     * Prepares and launches the [IApplication] on the Java Swing backend.
     */
    fun launch(
        args: Array<String> = emptyArray(),
        descriptor: GenericUiAppDescriptor
    ): IApplication {
        SwingApplicationImpl.installSwingThemeDetector()
        return GenericUiLaunchSequence.launch(
            factory = SwingNativeFactory(),
            args = args,
            descriptor = descriptor,
            isSystemTarget = true
        )
    }
}
