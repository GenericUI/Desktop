package com.nexustools.genericui.linux.titlebar

import com.nexustools.genericui.enums.window.TitleBarControlsPlacement
import com.nexustools.genericui.linux.LinuxReentrantLock
import com.nexustools.genericui.window.TitleCapabilities

/**
 * Thread-safe singleton manager caching the active [LinuxTitleBarProfile], deriving toolkit-level
 * [TitleCapabilities], and resolving fallback profiles for emulated MDI sub-windows.
 */
object LinuxTitleBarProfileManager {

    private val lock = LinuxReentrantLock()
    private var cachedProfile: LinuxTitleBarProfile? = null
    private var testOverride: LinuxTitleBarProfile? = null

    fun currentProfile(): LinuxTitleBarProfile = lock.withLock {
        testOverride ?: cachedProfile ?: refreshProfileLocked()
    }

    fun refreshProfile(): LinuxTitleBarProfile = lock.withLock {
        refreshProfileLocked()
    }

    fun setTestProfileOverride(profile: LinuxTitleBarProfile?) = lock.withLock {
        testOverride = profile
    }

    fun resolveTitleCapabilities(
        isToolModalWindow: Boolean = false,
        minimizable: Boolean = true
    ): TitleCapabilities {
        val profile = currentProfile()
        val replicable = profile.isReplicable

        val hasLeftControls = profile.leftButtons.any { isCaptionButton(it) }
        val hasRightControls = profile.rightButtons.any { isCaptionButton(it) }
        val defaultPlacement = if (replicable && hasLeftControls && !hasRightControls) {
            TitleBarControlsPlacement.LEADING
        } else {
            TitleBarControlsPlacement.TRAILING
        }

        return TitleCapabilities(
            isSupported = true,
            isEmulated = false,
            supportsCustomTitleBar = replicable,
            supportsCustomComponents = replicable,
            supportsLeadingComponents = replicable,
            supportsCenterComponents = replicable,
            supportsTrailingComponents = replicable,
            supportsEmbeddedMenuBar = replicable,
            supportsNavbarMenuBar = false,
            supportsWindowIcon = !isToolModalWindow,
            supportsWindowTitle = true,
            supportsMinimizeButton = if (isToolModalWindow) minimizable else true,
            supportsMaximizeButton = !isToolModalWindow,
            supportsCloseButton = true,
            supportsCollapseToggle = false,
            supportsControlsPlacement = replicable,
            supportsCustomHeight = replicable,
            supportsSystemAeroOrBackdrop = false,
            isToolWindowTitleBar = isToolModalWindow,
            isTitleBarRequired = true,
            titleBarRendersAsNavbar = false,
            menuBarRendersAsNavbar = false,
            menuBarMergesIntoTitleNavbar = false,
            defaultControlsPlacement = defaultPlacement,
            prefersBlurredTranslucentOverlayTitleBar = false
        )
    }

    fun resolveEmulatedMdiProfile(
        preferKdeStyle: Boolean,
        isDarkFallback: Boolean
    ): LinuxTitleBarProfile {
        val current = currentProfile()
        if (current.isReplicable) {
            return current
        }
        return if (preferKdeStyle) {
            LinuxThemePaletteCatalog.resolveBreezeProfile(
                themeName = if (isDarkFallback) "BreezeDark" else "Breeze",
                leftButtons = listOf(LinuxTitleButtonKind.MENU),
                rightButtons = listOf(
                    LinuxTitleButtonKind.MINIMIZE,
                    LinuxTitleButtonKind.MAXIMIZE_OR_RESTORE,
                    LinuxTitleButtonKind.CLOSE
                ),
                kdeGlobalsSections = emptyMap(),
                breezeRcSections = emptyMap(),
                portalColorScheme = if (isDarkFallback) 1 else 2,
                portalAccent = null,
                isDarkHint = isDarkFallback
            )
        } else {
            LinuxThemePaletteCatalog.resolveLibadwaitaProfile(
                isDark = isDarkFallback,
                accentKey = "blue",
                portalAccent = null,
                buttonLayout = ":minimize,maximize,close"
            )
        }
    }

    private fun refreshProfileLocked(): LinuxTitleBarProfile {
        val probed = LinuxWmDetector.detectCurrent()
        cachedProfile = probed
        return testOverride ?: probed
    }

    private fun isCaptionButton(kind: LinuxTitleButtonKind): Boolean = when (kind) {
        LinuxTitleButtonKind.MINIMIZE,
        LinuxTitleButtonKind.MAXIMIZE_OR_RESTORE,
        LinuxTitleButtonKind.CLOSE -> true
        else -> false
    }
}
