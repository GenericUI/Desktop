package com.nexustools.genericui.gtk

import com.nexustools.genericui.desktop.shell.DesktopWindowNativeImpl
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
    override fun applyTitleBarInstructions(titleBar: TitleBar?) {
        super.applyTitleBarInstructions(titleBar)
        nativeWidget.attributes["gtkHeaderBar"] = titleBar?.customTitleBar ?: true
        nativeWidget.attributes["decorationLayout"] = titleBar?.controlsPlacement?.name ?: "END"
    }
}
