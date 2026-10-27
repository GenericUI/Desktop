package com.nexustools.genericui.swing

import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.nativeimpl.shell.ApplicationNativeImpl
import com.nexustools.genericui.style.theme.SystemThemeDetector
import com.nexustools.genericui.window.IWindow
import java.awt.Color
import java.awt.Window
import javax.swing.SwingUtilities
import javax.swing.UIManager

/**
 * Java Swing [ApplicationNativeImpl] (`:desktop:swing`).
 */
class SwingApplicationImpl : ApplicationNativeImpl<String> {
    override val nativeApplication: String = "SwingApplication"
    private var themeMode: ThemeMode = ThemeMode.AUTO
    private var themeColorHex: String? = null
    private val windows = mutableListOf<IWindow>()
    private var running: Boolean = false
    private var disposed: Boolean = false

    init {
        installSwingThemeDetector()
    }

    override val isDisposed: Boolean
        get() = disposed

    override fun setThemeMode(mode: ThemeMode) {
        themeMode = mode
        installSwingThemeDetector()
        val effective = SystemThemeDetector.resolveTheme(mode)
        val isDark = effective == ThemeMode.DARK
        applySwingThemeDefaults(isDark)
    }

    override fun getThemeMode(): ThemeMode = themeMode

    override fun setThemeColor(colorHex: String?) {
        themeColorHex = colorHex
    }

    override fun getThemeColor(): String? = themeColorHex

    fun registerWindow(window: IWindow) {
        if (!windows.contains(window)) windows.add(window)
    }

    fun unregisterWindow(window: IWindow) {
        windows.remove(window)
    }

    fun getWindows(): List<IWindow> = windows.toList()

    override fun runEventLoop(onStarted: () -> Unit) {
        running = true
        onStarted()
    }

    override fun exitApplication(exitCode: Int) {
        running = false
        windows.toList().forEach { it.close() }
        windows.clear()
    }

    fun isRunning(): Boolean = running

    override fun dispose() {
        running = false
        disposed = true
        windows.clear()
    }

    companion object {
        fun detectJvmSystemTheme(): ThemeMode {
            val osName = System.getProperty("os.name").orEmpty().lowercase()
            return runCatching {
                when {
                    osName.contains("win") -> {
                        val proc = ProcessBuilder(
                            "reg",
                            "query",
                            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                            "/v",
                            "AppsUseLightTheme"
                        ).redirectErrorStream(true).start()
                        val output = proc.inputStream.bufferedReader().readText()
                        proc.waitFor()
                        if (output.contains("0x0", ignoreCase = true)) ThemeMode.DARK else ThemeMode.LIGHT
                    }
                    osName.contains("mac") || osName.contains("darwin") -> {
                        val proc = ProcessBuilder("defaults", "read", "-g", "AppleInterfaceStyle")
                            .redirectErrorStream(true)
                            .start()
                        val output = proc.inputStream.bufferedReader().readText().trim()
                        proc.waitFor()
                        if (output.equals("Dark", ignoreCase = true)) ThemeMode.DARK else ThemeMode.LIGHT
                    }
                    else -> {
                        val gtkTheme = System.getenv("GTK_THEME").orEmpty()
                        if (gtkTheme.contains("dark", ignoreCase = true)) ThemeMode.DARK else ThemeMode.LIGHT
                    }
                }
            }.getOrDefault(ThemeMode.LIGHT)
        }

        fun installSwingThemeDetector() {
            SystemThemeDetector.backendDetector = { detectJvmSystemTheme() }
        }

        fun applySwingThemeDefaults(isDark: Boolean) {
            if (isDark) {
                UIManager.put("Panel.background", Color(30, 30, 34))
                UIManager.put("Panel.foreground", Color(235, 235, 240))
                UIManager.put("Button.background", Color(45, 45, 50))
                UIManager.put("Button.foreground", Color(240, 240, 245))
                UIManager.put("Label.foreground", Color(235, 235, 240))
            } else {
                UIManager.put("Panel.background", Color(245, 245, 247))
                UIManager.put("Panel.foreground", Color(28, 28, 30))
                UIManager.put("Button.background", Color(250, 250, 252))
                UIManager.put("Button.foreground", Color(28, 28, 30))
                UIManager.put("Label.foreground", Color(28, 28, 30))
            }
            runCatching {
                for (win in Window.getWindows()) {
                    SwingUtilities.updateComponentTreeUI(win)
                }
            }
        }
    }
}
