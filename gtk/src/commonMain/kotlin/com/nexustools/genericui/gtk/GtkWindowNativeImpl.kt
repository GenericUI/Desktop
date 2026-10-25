package com.nexustools.genericui.gtk

import com.nexustools.genericui.desktop.shell.DesktopWindowNativeImpl
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl
import com.nexustools.genericui.window.TitleBar

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

    override fun applyTitleBarInstructions(titleBar: TitleBar?) {
        super.applyTitleBarInstructions(titleBar)
        nativeWidget.attributes["gtkHeaderBar"] = titleBar?.customTitleBar ?: true
        nativeWidget.attributes["decorationLayout"] = titleBar?.controlsPlacement?.name ?: "END"
        val allowMinimize = if (isToolModalWindow) minimizable else (titleBar?.showMinimizeButton ?: true)
        nativeWidget.attributes["gtkButtonsLayout"] = if (allowMinimize) ":minimize,maximize,close" else ":close"
    }
}
