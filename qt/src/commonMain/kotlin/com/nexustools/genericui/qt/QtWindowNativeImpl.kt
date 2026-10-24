package com.nexustools.genericui.qt

import com.nexustools.genericui.desktop.shell.DesktopWindowNativeImpl
import com.nexustools.genericui.window.TitleBar

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
    override fun applyTitleBarInstructions(titleBar: TitleBar?) {
        super.applyTitleBarInstructions(titleBar)
        val custom = titleBar?.customTitleBar == true
        if (custom) {
            nativeWidget.qtWindowFlags = nativeWidget.qtWindowFlags or QtWidgetHandle.Qt_FramelessWindowHint
        } else {
            nativeWidget.qtWindowFlags = nativeWidget.qtWindowFlags and QtWidgetHandle.Qt_FramelessWindowHint.inv()
        }
    }
}
