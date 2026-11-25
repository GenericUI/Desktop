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
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.util.concurrent.locks.ReentrantLock
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.concurrent.withLock

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
        ensureSystemLookAndFeelAndThemeInitialized()
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
                runCatching { SwingUtilities.updateComponentTreeUI(rootComp) }
                repaintComponentHierarchy(rootComp)
            }
        }
    }

    override fun runEventLoop(onStarted: () -> Unit) {
        running = true
        onStarted()
        if (interactiveSwingGuiEnabled &&
            !GraphicsEnvironment.isHeadless() &&
            windows.isEmpty() &&
            SwingSystemTrayImpl.hasActiveTray()
        ) {
            headlessLoopLock.withLock {
                while (running && !disposed && windows.isEmpty() && SwingSystemTrayImpl.hasActiveTray()) {
                    headlessLoopCondition.await()
                }
            }
        }
    }

    override fun exitApplication(exitCode: Int) {
        running = false
        SwingSystemTrayImpl.disposeAll()
        windows.toList().forEach { it.close() }
        windows.clear()
        wakeHeadlessWaiters()
    }

    fun isRunning(): Boolean = running

    override fun dispose() {
        running = false
        disposed = true
        SwingSystemTrayImpl.disposeAll()
        windows.toList().forEach { runCatching { it.close() } }
        windows.clear()
        wakeHeadlessWaiters()
    }

    companion object {
        @Volatile
        var interactiveSwingGuiEnabled: Boolean = false

        internal val isInteractiveSwingDisplayEnabled: Boolean
            get() = interactiveSwingGuiEnabled && !GraphicsEnvironment.isHeadless()

        private val headlessLoopLock = ReentrantLock()
        private val headlessLoopCondition = headlessLoopLock.newCondition()

        internal fun wakeHeadlessWaiters() {
            headlessLoopLock.withLock {
                headlessLoopCondition.signalAll()
            }
        }
        private const val GTK_LAF_CLASS: String = "com.sun.java.swing.plaf.gtk.GTKLookAndFeel"

        private val NATIVE_CONTROL_PRESERVED_KEYS = listOf(
            "Button.background",
            "Button.foreground",
            "Button.border",
            "ToggleButton.background",
            "ToggleButton.foreground",
            "ToggleButton.border",
            "ComboBox.background",
            "ComboBox.foreground",
            "ComboBox.border",
            "ProgressBar.background",
            "ProgressBar.foreground",
            "ProgressBar.border",
            "ScrollBar.background",
            "ScrollBar.thumb",
            "ScrollBar.track",
            "ScrollBar.border"
        )

        private val lookAndFeelLock = Any()

        @Volatile
        private var lookAndFeelInstalled: Boolean = false

        @Volatile
        private var nativeDefaultsCaptured: Boolean = false

        private val pristineNativeControlDefaults = linkedMapOf<String, Any?>()

        @Volatile
        private var cachedSystemTheme: ThemeMode? = null

        @Volatile
        private var cachedSystemThemeTimestampMs: Long = 0L

        private const val SYSTEM_THEME_CACHE_TTL_MS: Long = 5_000L

        internal fun isMacOs(osName: String = System.getProperty("os.name").orEmpty()): Boolean {
            val lower = osName.lowercase()
            return "mac" in lower || "darwin" in lower
        }

        internal fun isWindowsOs(osName: String = System.getProperty("os.name").orEmpty()): Boolean =
            "win" in osName.lowercase()

        internal fun isLinuxOs(osName: String = System.getProperty("os.name").orEmpty()): Boolean {
            val lower = osName.lowercase()
            return "linux" in lower || "nix" in lower || "nux" in lower || "aix" in lower || "bsd" in lower
        }

        internal fun resolvePreferredSystemLafClassName(
            osName: String,
            systemLafClassName: String?,
            gtkAvailable: Boolean
        ): String? {
            val trimmed = systemLafClassName?.trim()?.takeIf { it.isNotEmpty() }
            if (isLinuxOs(osName) && gtkAvailable) {
                val isCrossPlatformMetal = trimmed == null ||
                    "MetalLookAndFeel" in trimmed ||
                    "CrossPlatform" in trimmed
                if (isCrossPlatformMetal) {
                    return GTK_LAF_CLASS
                }
            }
            return trimmed
        }

        internal fun parseMacOsInterfaceStyleOutput(output: String?): ThemeMode =
            if (output?.trim().orEmpty().contains("dark", ignoreCase = true)) ThemeMode.DARK else ThemeMode.LIGHT

        internal fun parseWindowsAppsUseLightThemeOutput(output: String?): ThemeMode? {
            val text = output?.trim().orEmpty()
            if (text.isEmpty()) return null
            val match = Regex("AppsUseLightTheme\\s+REG_DWORD\\s+(0x[0-9a-fA-F]+|\\d+)", RegexOption.IGNORE_CASE)
                .find(text) ?: return null
            val token = match.groupValues[1].lowercase()
            val value = if (token.startsWith("0x")) {
                token.removePrefix("0x").toIntOrNull(16)
            } else {
                token.toIntOrNull()
            } ?: return null
            return if (value == 0) ThemeMode.DARK else ThemeMode.LIGHT
        }

        internal fun parseGsettingsColorScheme(output: String?): ThemeMode? {
            val lower = output?.trim()?.trim('\'', '"')?.lowercase().orEmpty()
            return when {
                "prefer-dark" in lower || lower == "dark" -> ThemeMode.DARK
                "prefer-light" in lower || lower == "light" -> ThemeMode.LIGHT
                else -> null
            }
        }

        internal fun parseKdeGlobalsTheme(content: String?): ThemeMode? {
            if (content.isNullOrBlank()) return null
            for (rawLine in content.lineSequence()) {
                val line = rawLine.trim()
                if (line.startsWith("#") || line.startsWith("[")) continue
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                val key = line.substring(0, eq).trim()
                val value = line.substring(eq + 1).trim()
                if (key.equals("ColorScheme", ignoreCase = true) ||
                    key.equals("LookAndFeelPackage", ignoreCase = true)
                ) {
                    if (value.contains("dark", ignoreCase = true)) {
                        return ThemeMode.DARK
                    }
                    if (value.contains("light", ignoreCase = true) ||
                        value.equals("Breeze", ignoreCase = true)
                    ) {
                        return ThemeMode.LIGHT
                    }
                }
            }
            return null
        }

        internal fun resetSystemThemeCacheForTests() {
            cachedSystemTheme = null
            cachedSystemThemeTimestampMs = 0L
        }

        private fun isGtkLookAndFeelAvailable(): Boolean {
            if (UIManager.getInstalledLookAndFeels().any { it.className == GTK_LAF_CLASS }) {
                return true
            }
            return runCatching {
                Class.forName(GTK_LAF_CLASS, false, SwingApplicationImpl::class.java.classLoader)
                true
            }.getOrDefault(false)
        }

        private fun capturePristineNativeControlDefaults() {
            if (nativeDefaultsCaptured) return
            nativeDefaultsCaptured = true
            val defaults = UIManager.getLookAndFeelDefaults()
            for (key in NATIVE_CONTROL_PRESERVED_KEYS) {
                pristineNativeControlDefaults[key] = defaults[key] ?: UIManager.get(key)
            }
        }

        private fun restorePristineNativeControlDefaults() {
            if (!nativeDefaultsCaptured) return
            for ((key, value) in pristineNativeControlDefaults) {
                if (value != null) {
                    UIManager.put(key, value)
                }
            }
        }

        private fun runProcessStdout(command: List<String>, timeoutMs: Long = 400L): String? = runCatching {
            val proc = ProcessBuilder(command).redirectErrorStream(false).start()
            val finished = proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return null
            }
            if (proc.exitValue() != 0) {
                return null
            }
            proc.inputStream.bufferedReader().use { it.readText() }
        }.getOrNull()

        /**
         * Idempotently sets platform appearance properties (including macOS `apple.awt.application.appearance=system`),
         * installs Swing's native System Look-and-Feel (falling back to Linux `GTKLookAndFeel` even when
         * `sun.desktop != "gnome"`), registers the OS theme detector, and applies resolved `ThemeMode.AUTO`
         * defaults before any `JComponent` is constructed.
         */
        fun ensureSystemLookAndFeelAndThemeInitialized() {
            installSystemLookAndFeelOnce()
            installSwingThemeDetector()
            val resolved = SystemThemeDetector.resolveTheme(BootstrapColorPalette.activeThemeConfig.mode)
            applySwingThemeDefaults(
                isDark = resolved == ThemeMode.DARK,
                config = BootstrapColorPalette.activeThemeConfig
            )
        }

        fun installSystemLookAndFeelOnce() {
            synchronized(lookAndFeelLock) {
                if (lookAndFeelInstalled) return
                lookAndFeelInstalled = true
                val osName = System.getProperty("os.name").orEmpty()
                if (isMacOs(osName) && System.getProperty("apple.awt.application.appearance").isNullOrBlank()) {
                    System.setProperty("apple.awt.application.appearance", "system")
                }
                val systemLaf = runCatching { UIManager.getSystemLookAndFeelClassName() }.getOrNull()
                val preferredLaf = resolvePreferredSystemLafClassName(
                    osName = osName,
                    systemLafClassName = systemLaf,
                    gtkAvailable = isLinuxOs(osName) && isGtkLookAndFeelAvailable()
                )
                var installed = false
                if (!preferredLaf.isNullOrBlank()) {
                    installed = runCatching {
                        UIManager.setLookAndFeel(preferredLaf)
                        true
                    }.getOrDefault(false)
                }
                if (!installed && !systemLaf.isNullOrBlank() && systemLaf != preferredLaf) {
                    runCatching { UIManager.setLookAndFeel(systemLaf) }
                }
                capturePristineNativeControlDefaults()
            }
        }

        fun detectJvmSystemTheme(): ThemeMode {
            val now = System.currentTimeMillis()
            val cached = cachedSystemTheme
            if (cached != null && (now - cachedSystemThemeTimestampMs) < SYSTEM_THEME_CACHE_TTL_MS) {
                return cached
            }
            val osName = System.getProperty("os.name").orEmpty()
            val detected = runCatching {
                when {
                    isWindowsOs(osName) -> {
                        val output = runProcessStdout(
                            listOf(
                                "reg",
                                "query",
                                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                                "/v",
                                "AppsUseLightTheme"
                            )
                        )
                        parseWindowsAppsUseLightThemeOutput(output) ?: ThemeMode.LIGHT
                    }
                    isMacOs(osName) -> {
                        val output = runProcessStdout(listOf("defaults", "read", "-g", "AppleInterfaceStyle"))
                        parseMacOsInterfaceStyleOutput(output)
                    }
                    else -> {
                        val colorSchemeOut = runProcessStdout(
                            listOf("gsettings", "get", "org.gnome.desktop.interface", "color-scheme")
                        )
                        parseGsettingsColorScheme(colorSchemeOut)?.let { return@runCatching it }

                        val gsettingsGtkOut = runProcessStdout(
                            listOf("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme")
                        )
                        if (gsettingsGtkOut?.contains("dark", ignoreCase = true) == true) {
                            return@runCatching ThemeMode.DARK
                        }

                        val gtkTheme = System.getenv("GTK_THEME").orEmpty()
                        if (gtkTheme.contains("dark", ignoreCase = true)) {
                            return@runCatching ThemeMode.DARK
                        }

                        val userHome = System.getProperty("user.home").orEmpty()
                        if (userHome.isNotEmpty()) {
                            val kdeFile = java.io.File(userHome, ".config/kdeglobals")
                            if (kdeFile.isFile) {
                                parseKdeGlobalsTheme(runCatching { kdeFile.readText() }.getOrNull())?.let {
                                    return@runCatching it
                                }
                            }
                        }
                        ThemeMode.LIGHT
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
            installSystemLookAndFeelOnce()
            restorePristineNativeControlDefaults()
            val colors = config.resolveEffectiveColors(isDark)
            val textPrimary = colors.onSurface ?: colors.onBackground ?: if (isDark) RgbaColor(235, 235, 240) else RgbaColor(28, 28, 30)
            val background = colors.background ?: if (isDark) RgbaColor(30, 30, 34) else RgbaColor(245, 245, 247)
            UIManager.put("Panel.background", Color(background.r, background.g, background.b))
            UIManager.put("Panel.foreground", Color(textPrimary.r, textPrimary.g, textPrimary.b))
            UIManager.put("Label.foreground", Color(textPrimary.r, textPrimary.g, textPrimary.b))
            UIManager.put("TabbedPane.contentOpaque", java.lang.Boolean.FALSE)
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
