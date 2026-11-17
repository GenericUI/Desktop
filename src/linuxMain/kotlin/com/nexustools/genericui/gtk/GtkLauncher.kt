package com.nexustools.genericui.gtk

import com.nexustools.genericui.component.shell.IApplication
import com.nexustools.genericui.core.GenericUiAppDescriptor
import com.nexustools.genericui.core.GenericUiLaunchSequence

/**
 * Platform launcher for the Linux GTK 4 + Libadwaita backend target in `linuxMain`.
 */
object GtkLauncher {

    /**
     * Prepares the [IApplication] for the GTK 4 backend without starting the interactive native event loop.
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
     * Prepares and launches the [IApplication] on the interactive GTK 4 backend.
     */
    fun launch(
        args: Array<String> = emptyArray(),
        descriptor: GenericUiAppDescriptor
    ): IApplication {
        GtkApplicationNativeImpl.installGtkThemeDetector()
        val prevInteractive = GtkApplicationNativeImpl.interactiveGtkGuiEnabled
        GtkApplicationNativeImpl.interactiveGtkGuiEnabled = true
        return try {
            GenericUiLaunchSequence.launch(
                factory = GtkNativeFactory(),
                args = args,
                descriptor = descriptor,
                isSystemTarget = true
            )
        } finally {
            GtkApplicationNativeImpl.interactiveGtkGuiEnabled = prevInteractive
        }
    }
}
