package com.nexustools.genericui.qt.impl

import com.nexustools.genericui.qt.QtWidgetHandle

/**
 * Qt6 per-component native implementation for `TabContainer` (`QTabWidget`),
 * delegating rendering to [com.nexustools.genericui.qt.render.QtControlRenderUtil].
 */
class QtTabContainerNativeImpl(
    nativeWidget: QtWidgetHandle = QtWidgetHandle("QTabWidget")
) : com.nexustools.genericui.qt.QtTabContainerNativeImpl(nativeWidget)
