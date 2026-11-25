package com.nexustools.genericui.linux.titlebar

/**
 * Window action callbacks invoked when a replicated Linux titlebar button or background gesture is triggered.
 */
interface LinuxTitleBarWindowActions {
    fun onClose()
    fun onMinimize()
    fun onMaximizeOrRestore()
    fun onShowWindowMenu(screenX: Int, screenY: Int)
    fun onToggleKeepAbove()
    fun onToggleKeepBelow()
    fun onToggleAllDesktops()
    fun onToggleShade()
    fun onStartSystemMove()
    fun onDoubleClickMaximize()
}
