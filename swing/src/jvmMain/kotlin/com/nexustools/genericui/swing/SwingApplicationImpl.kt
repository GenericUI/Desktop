package com.nexustools.genericui.swing

import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.core.AbstractContainerNativeImpl
import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.nativeimpl.shell.ApplicationNativeImpl
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.theme.SystemThemeDetector
import com.nexustools.genericui.style.theme.ThemeConfig
import com.nexustools.genericui.window.IWindow
import java.awt.Color
import java.awt.Component
import java.awt.Container
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
    private var themeConfig: ThemeConfig = BootstrapColorPalette.activeThemeConfig
    private val windows = mutableListOf<IWindow>()
    private var running: Boolean = false
    private var disposed: Boolean = false

    init {
        installSystemLookAndFeelOnce()
        installSwingThemeDetector()
    }

    override val isDisposed: Boolean
        get() = disposed

    override fun detectSystemThemeMode(): ThemeMode =
        SystemThemeDetector.overrideDetectedTheme?.takeIf { !it.isAuto } ?: detectJvmSystemTheme()

    override fun setThemeMode(mode: ThemeMode) {
        themeMode = mode
        installSwingThemeDetector()
        val effective = SystemThemeDetector.resolveTheme(mode)
        val isDark = effective == ThemeMode.DARK
        applySwingThemeDefaults(isDark, themeConfig)
        refreshRegisteredWindows()
    }

    override fun getThemeMode(): ThemeMode = themeMode

    override fun setThemeColor(colorHex: String?) {
        themeColorHex = colorHex
    }

    override fun getThemeColor(): String? = themeColorHex

    override fun setThemeConfig(config: ThemeConfig) {
        themeConfig = config
        GenericUI.defaultThemeConfig = config
        if (config.mode != ThemeMode.AUTO) {
            themeMode = config.mode
        }
        val effective = SystemThemeDetector.resolveTheme(themeMode)
        val isDark = effective == ThemeMode.DARK
        applySwingThemeDefaults(isDark, config)
        refreshRegisteredWindows()
    }

    override fun getThemeConfig(): ThemeConfig = themeConfig

    fun registerWindow(window: IWindow) {
        if (!windows.contains(window)) windows.add(window)
    }

    fun unregisterWindow(window: IWindow) {
        windows.remove(window)
    }

    fun getWindows(): List<IWindow> = windows.toList()

    private fun refreshRegisteredWindows() {
        for (win in windows.toList()) {
            val rootComp = ((win as? com.nexustools.genericui.core.AbstractComponent)?.nativeImpl?.nativeWidget as? Component)
            if (rootComp != null) {
                repaintComponentHierarchy(rootComp)
            }
        }
    }

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
        @Volatile
        private var lookAndFeelInstalled: Boolean = false

        @Volatile
        private var cachedSystemTheme: ThemeMode? = null

        @Volatile
        private var cachedSystemThemeTimestampMs: Long = 0L

        private const val SYSTEM_THEME_CACHE_TTL_MS: Long = 5_000L

        fun installSystemLookAndFeelOnce() {
            if (lookAndFeelInstalled) return
            lookAndFeelInstalled = true
            runCatching {
                val systemLaf = UIManager.getSystemLookAndFeelClassName()
                if (!systemLaf.isNullOrBlank()) {
                    UIManager.setLookAndFeel(systemLaf)
                }
            }
        }

        fun detectJvmSystemTheme(): ThemeMode {
            val now = System.currentTimeMillis()
            val cached = cachedSystemTheme
            if (cached != null && (now - cachedSystemThemeTimestampMs) < SYSTEM_THEME_CACHE_TTL_MS) {
                return cached
            }
            val osName = System.getProperty("os.name").orEmpty().lowercase()
            val detected = runCatching {
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
            cachedSystemTheme = detected
            cachedSystemThemeTimestampMs = now
            return detected
        }

        fun installSwingThemeDetector() {
            SystemThemeDetector.backendDetector = { detectJvmSystemTheme() }
            SystemThemeDetector.onThemeAppliedHook = { _, effective ->
                applySwingThemeDefaults(effective == ThemeMode.DARK, BootstrapColorPalette.activeThemeConfig)
            }
        }

        fun applySwingThemeDefaults(
            isDark: Boolean,
            config: ThemeConfig = BootstrapColorPalette.activeThemeConfig
        ) {
            val colors = config.resolveEffectiveColors(isDark)
            val surface = colors.surface ?: if (isDark) RgbaColor(45, 45, 50) else RgbaColor(250, 250, 252)
            val textPrimary = colors.onSurface ?: colors.onBackground ?: if (isDark) RgbaColor(235, 235, 240) else RgbaColor(28, 28, 30)
            val background = colors.background ?: if (isDark) RgbaColor(30, 30, 34) else RgbaColor(245, 245, 247)
            UIManager.put("Panel.background", Color(background.r, background.g, background.b))
            UIManager.put("Panel.foreground", Color(textPrimary.r, textPrimary.g, textPrimary.b))
            UIManager.put("Button.background", Color(surface.r, surface.g, surface.b))
            UIManager.put("Button.foreground", Color(textPrimary.r, textPrimary.g, textPrimary.b))
            UIManager.put("Label.foreground", Color(textPrimary.r, textPrimary.g, textPrimary.b))
            runCatching {
                for (win in Window.getWindows()) {
                    SwingUtilities.updateComponentTreeUI(win)
                    repaintComponentHierarchy(win)
                }
            }
        }

        private fun repaintComponentHierarchy(component: Component) {
            if (component is javax.swing.JComponent) {
                component.putClientProperty("activeThemeConfig", BootstrapColorPalette.activeThemeConfig)
                (component.getClientProperty("genericUiNativeImpl") as? SwingBaseNativeImpl)?.refreshThemeAppearance()
            }
            component.repaint()
            if (component is Container) {
                for (child in component.components) {
                    repaintComponentHierarchy(child)
                }
            }
        }
    }
}
