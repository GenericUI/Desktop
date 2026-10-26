package com.nexustools.genericui.qt

import com.nexustools.genericui.desktop.shell.DesktopWindowNativeImpl
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl
import com.nexustools.genericui.window.TitleBar
import com.nexustools.genericui.window.TitleCapabilities

/**
 * Qt 6 `QMainWindow` implementation of [com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl].
 *
 * Supports custom borderless title bars via `Qt::FramelessWindowHint` + `QWindow::startSystemMove()` /
 * `startSystemResize()` (`supportsCustomTitleBar = true`) and embedding `QMenuBar` directly inside
 * the custom title bar (`supportsEmbeddedMenuBarInTitleBar = true`).
 */
class QtWindowNativeImpl : DesktopWindowNativeImpl<QtWidgetHandle>(
    nativeWidget = QtWidgetHandle("QMainWindow", QtWidgetHandle.Qt_Window),
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
        var flags = nativeWidget.qtWindowFlags
        if (!showInTaskbar) {
            flags = flags or QtWidgetHandle.Qt_Tool
        }
        if (alwaysOnTopOfOwner) {
            flags = flags or QtWidgetHandle.Qt_WindowStaysOnTopHint
        }
        if (!minimizable) {
            flags = flags and QtWidgetHandle.Qt_WindowMinimizeButtonHint.inv()
        }
        nativeWidget.qtWindowFlags = flags
        nativeWidget.attributes["skip-taskbar"] = !showInTaskbar
        nativeWidget.attributes["minimizable"] = minimizable
        nativeWidget.attributes["transient-for-owner"] = alwaysOnTopOfOwner
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
        val custom = titleBar?.customTitleBar == true
        if (custom) {
            nativeWidget.qtWindowFlags = nativeWidget.qtWindowFlags or QtWidgetHandle.Qt_FramelessWindowHint
        } else {
            nativeWidget.qtWindowFlags = nativeWidget.qtWindowFlags and QtWidgetHandle.Qt_FramelessWindowHint.inv()
        }
        val allowMinimize = if (isToolModalWindow) minimizable else (titleBar?.showMinimizeButton ?: true)
        if (!allowMinimize) {
            nativeWidget.qtWindowFlags = nativeWidget.qtWindowFlags and QtWidgetHandle.Qt_WindowMinimizeButtonHint.inv()
        } else {
            nativeWidget.qtWindowFlags = nativeWidget.qtWindowFlags or QtWidgetHandle.Qt_WindowMinimizeButtonHint
        }
    }
}
