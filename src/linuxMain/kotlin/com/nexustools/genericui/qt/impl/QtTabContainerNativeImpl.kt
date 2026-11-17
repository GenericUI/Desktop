package com.nexustools.genericui.qt.impl

import com.nexustools.genericui.qt.QtWidgetHandle

/**
 * Qt 6 per-component native implementation for `TabContainer` (`QTabWidget`).
 */
class QtTabContainerNativeImpl(
    nativeWidget: QtWidgetHandle = QtWidgetHandle("QTabWidget")
) : com.nexustools.genericui.qt.QtTabContainerNativeImpl(nativeWidget)
