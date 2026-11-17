package com.nexustools.genericui.qt.impl

import com.nexustools.genericui.qt.QtWidgetHandle

/**
 * Qt 6 per-component native implementation for `Button` (`QPushButton`).
 */
class QtButtonNativeImpl(
    nativeWidget: QtWidgetHandle = QtWidgetHandle("QPushButton"),
    classPrefix: String = "btn"
) : com.nexustools.genericui.qt.QtButtonNativeImpl(nativeWidget, classPrefix)
