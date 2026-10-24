package com.nexustools.genericui.swing

import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.nativeimpl.shell.ApplicationNativeImpl
import javax.swing.JPanel

/**
 * Java Swing (`javax.swing.SwingUtilities` EDT) implementation of [ApplicationNativeImpl].
 */
class SwingApplicationImpl : ApplicationNativeImpl<JPanel> {
    override val nativeApplication: JPanel = JPanel()

    private var themeMode: ThemeMode = ThemeMode.SYSTEM
    private var running: Boolean = false

    override fun setThemeMode(mode: ThemeMode) {
        themeMode = mode
    }

    override fun getThemeMode(): ThemeMode = themeMode

    override fun runEventLoop(onStarted: () -> Unit) {
        running = true
        onStarted()
    }

    override fun exitApplication(exitCode: Int) {
        running = false
    }

    override fun dispose() {
        running = false
    }
}
