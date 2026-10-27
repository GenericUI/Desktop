package com.nexustools.genericui.gtk

import com.nexustools.genericui.component.shell.IApplication
import com.nexustools.genericui.core.GenericUiAppDescriptor
import com.nexustools.genericui.core.GenericUiLaunchSequence

/**
 * Platform launcher for the Linux GTK 4 + Libadwaita (`:desktop:gtk`) backend target.
 */
object GtkLauncher {

    /**
     * Prepares the [IApplication] for the GTK 4 backend without starting the event loop.
     */
    fun prepare(
        args: Array<String> = emptyArray(),
        descriptor: GenericUiAppDescriptor
    ): IApplication {
        GtkApplicationNativeImpl.installGtkThemeDetector()
        return GenericUiLaunchSequence.prepareApplication(
            factory = GtkNativeFactory(),
            args = args,
            descriptor = descriptor,
            isSystemTarget = true
        )
    }

    /**
     * Prepares and launches the [IApplication] on the GTK 4 backend.
     */
    fun launch(
        args: Array<String> = emptyArray(),
        descriptor: GenericUiAppDescriptor
    ): IApplication {
        GtkApplicationNativeImpl.installGtkThemeDetector()
        return GenericUiLaunchSequence.launch(
            factory = GtkNativeFactory(),
            args = args,
            descriptor = descriptor,
            isSystemTarget = true
        )
    }
}
