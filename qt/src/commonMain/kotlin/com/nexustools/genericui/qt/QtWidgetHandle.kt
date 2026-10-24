package com.nexustools.genericui.qt

import com.nexustools.genericui.desktop.core.DesktopNativeWidget

/**
 * Qt 6 native C++ pointer handle (`QWidget*`, `QMainWindow*`, `QDockWidget*`, `QApplication*`)
 * descriptor wrapping a Qt meta-object class name, `Qt::WindowFlags`, and QSS stylesheet string.
 */
class QtWidgetHandle(
    qtClassName: String,
    var qtWindowFlags: Int = Qt_Widget,
    qtPointer: Long = nextPointerId()
) : DesktopNativeWidget(
    backendToolkit = "Qt6_Widgets",
    nativeClassName = qtClassName,
    nativePointer = qtPointer
) {
    var qssStyleSheet: String = ""

    companion object {
        const val Qt_Widget: Int = 0x00000000
        const val Qt_Window: Int = 0x00000001
        const val Qt_Dialog: Int = 0x00000003
        const val Qt_Tool: Int = 0x0000000B
        const val Qt_FramelessWindowHint: Int = 0x00000800
        const val Qt_WindowStaysOnTopHint: Int = 0x00040000
    }
}
