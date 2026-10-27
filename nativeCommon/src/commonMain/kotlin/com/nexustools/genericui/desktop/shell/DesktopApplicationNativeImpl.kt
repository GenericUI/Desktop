package com.nexustools.genericui.desktop.shell

import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.nativeimpl.shell.ApplicationNativeImpl

/**
 * Shared desktop base implementation of [ApplicationNativeImpl] for Win32 message loops (`GetMessageW` /
 * `DispatchMessageW`), macOS `NSApplication`, GTK4 `GtkApplication` (`GMainLoop`), and Qt6 `QApplication`.
 */
open class DesktopApplicationNativeImpl<out W : DesktopNativeWidget>(
    override val nativeApplication: W
) : ApplicationNativeImpl<W> {

    val nativeWidget: W
        get() = nativeApplication

    protected var currentThemeMode: ThemeMode = ThemeMode.AUTO
    protected var running: Boolean = false
    protected var lastExitCode: Int = 0

    override val isDisposed: Boolean
        get() = nativeApplication.disposed

    override fun setThemeMode(mode: ThemeMode) {
        currentThemeMode = mode
        nativeApplication.attributes["themeMode"] = mode
    }

    override fun getThemeMode(): ThemeMode = currentThemeMode

    protected open fun onNativeRun() {}

    protected open fun onNativeExit(exitCode: Int) {}

    override fun runEventLoop(onStarted: () -> Unit) {
        running = true
        nativeApplication.attributes["running"] = true
        onNativeRun()
        onStarted()
    }

    override fun exitApplication(exitCode: Int) {
        lastExitCode = exitCode
        running = false
        nativeApplication.attributes["running"] = false
        nativeApplication.attributes["exitCode"] = exitCode
        onNativeExit(exitCode)
    }

    override fun dispose() {
        running = false
        nativeApplication.disposed = true
    }
}
