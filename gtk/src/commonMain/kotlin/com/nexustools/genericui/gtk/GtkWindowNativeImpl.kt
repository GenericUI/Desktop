package com.nexustools.genericui.gtk

import com.nexustools.genericui.desktop.shell.DesktopWindowNativeImpl
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl
import com.nexustools.genericui.style.transition.CssTransitionSpec
import com.nexustools.genericui.window.TitleBar
import com.nexustools.genericui.window.TitleCapabilities

/**
 * GTK 4 `GtkApplicationWindow` / `AdwApplicationWindow` implementation of
 * [com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl].
 *
 * Uses `GtkHeaderBar` (`gtk_window_set_titlebar`) for client-side decorations (`supportsCustomTitleBar = true`)
 * and supports packing `GtkPopoverMenuBar` directly inside the header bar (`supportsEmbeddedMenuBarInTitleBar = true`).
 */
class GtkWindowNativeImpl : DesktopWindowNativeImpl<GtkWidgetHandle>(
    nativeWidget = GtkWidgetHandle("AdwApplicationWindow"),
    supportsCustomTitleBar = true,
    supportsEmbeddedMenuBarInTitleBar = true,
    supportsMultiWindow = true
) {
    override fun configureAsToolModalWindow(
        ownerWindow: WindowNativeImpl<*>?,
        showInTaskbar: Boolean,
        minimizable: Boolean,
        alwaysOnTopOfOwner: Boolean
    ) {
        super.configureAsToolModalWindow(ownerWindow, showInTaskbar, minimizable, alwaysOnTopOfOwner)
        nativeWidget.attributes["skip-taskbar"] = !showInTaskbar
        nativeWidget.attributes["minimizable"] = minimizable
        nativeWidget.attributes["transient-for-owner"] = alwaysOnTopOfOwner
        nativeWidget.attributes["gtkButtonsLayout"] = if (minimizable) ":minimize,maximize,close" else ":close"
    }

    override fun getTitleCapabilities(): TitleCapabilities = TitleCapabilities(
        isSupported = true,
        isEmulated = false,
        supportsCustomTitleBar = true,
        supportsCustomComponents = true,
        supportsLeadingComponents = true,
        supportsCenterComponents = true,
        supportsTrailingComponents = true,
        supportsEmbeddedMenuBar = true,
        supportsNavbarMenuBar = false,
        supportsWindowIcon = true,
        supportsWindowTitle = true,
        supportsMinimizeButton = true,
        supportsMaximizeButton = true,
        supportsCloseButton = true,
        supportsCollapseToggle = false,
        supportsControlsPlacement = true,
        supportsCustomHeight = true,
        supportsSystemAeroOrBackdrop = false,
        isToolWindowTitleBar = false,
        isTitleBarRequired = true,
        titleBarRendersAsNavbar = false,
        menuBarRendersAsNavbar = false,
        menuBarMergesIntoTitleNavbar = false
    )

    override fun applyTitleBarInstructions(titleBar: TitleBar?) {
        super.applyTitleBarInstructions(titleBar)
        nativeWidget.attributes["gtkHeaderBar"] = titleBar?.customTitleBar ?: true
        nativeWidget.attributes["decorationLayout"] = titleBar?.controlsPlacement?.name ?: "END"
        val allowMinimize = if (isToolModalWindow) minimizable else (titleBar?.showMinimizeButton ?: true)
        nativeWidget.attributes["gtkButtonsLayout"] = if (allowMinimize) ":minimize,maximize,close" else ":close"
    }

    override fun runNativeTransition(
        property: String,
        fromValue: String?,
        toValue: String,
        spec: CssTransitionSpec,
        onComplete: () -> Unit
    ): Boolean {
        val handled = super.runNativeTransition(property, fromValue, toValue, spec, onComplete)
        if (property.equals("opacity", ignoreCase = true)) {
            val fromAlpha = (fromValue?.toFloatOrNull() ?: getOpacity()).coerceIn(0f, 1f)
            val targetAlpha = (toValue.toFloatOrNull() ?: getOpacity()).coerceIn(0f, 1f)
            nativeWidget.attributes["transitionFromOpacity"] = fromAlpha
            nativeWidget.attributes["transitionToOpacity"] = targetAlpha
            setOpacity(targetAlpha)
            if (spec.durationMs <= 0f) {
                onComplete()
            }
        }
        return handled
    }
}
