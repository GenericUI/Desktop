package com.nexustools.genericui.kde

import com.nexustools.genericui.component.shell.IApplication
import com.nexustools.genericui.core.GenericUiAppDescriptor
import com.nexustools.genericui.core.GenericUiLaunchSequence

/**
 * Platform launcher for the Linux KDE Frameworks 6 + Breeze (`Qt 6`) backend target in `linuxMain`.
 */
object KdeLauncher {

    /**
     * Prepares the [IApplication] for the KDE backend without starting the interactive native event loop.
     */
    fun prepare(
        args: Array<String> = emptyArray(),
        descriptor: GenericUiAppDescriptor
    ): IApplication {
        KdeApplicationNativeImpl.installKdeThemeDetector()
        return GenericUiLaunchSequence.prepareApplication(
            factory = KdeNativeFactory(),
            args = args,
            descriptor = descriptor,
            isSystemTarget = true
        )
    }

    /**
     * Prepares and launches the [IApplication] on the interactive KDE / Qt 6 backend.
     */
    fun launch(
        args: Array<String> = emptyArray(),
        descriptor: GenericUiAppDescriptor
    ): IApplication {
        KdeApplicationNativeImpl.installKdeThemeDetector()
        val prevInteractive = KdeApplicationNativeImpl.interactiveKdeGuiEnabled
        KdeApplicationNativeImpl.interactiveKdeGuiEnabled = true
        return try {
            GenericUiLaunchSequence.launch(
                factory = KdeNativeFactory(),
                args = args,
                descriptor = descriptor,
                isSystemTarget = true
            )
        } finally {
            KdeApplicationNativeImpl.interactiveKdeGuiEnabled = prevInteractive
        }
    }
}
