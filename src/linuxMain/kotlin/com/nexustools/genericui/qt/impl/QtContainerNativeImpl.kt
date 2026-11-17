package com.nexustools.genericui.qt.impl

import com.nexustools.genericui.qt.QtWidgetHandle

/**
 * Qt 6 per-component native implementation for `Container` / `Card` (`QWidget`).
 */
class QtContainerNativeImpl(
    nativeWidget: QtWidgetHandle = QtWidgetHandle("QWidget"),
    classPrefix: String = "container"
) : com.nexustools.genericui.qt.QtContainerNativeImpl(nativeWidget, classPrefix)
