package com.nexustools.genericui.qt.impl

import com.nexustools.genericui.qt.QtWidgetHandle

/**
 * Qt6 per-component native implementation for `Container` / `Card` (`QWidget_QBoxLayout`),
 * delegating rendering and [com.nexustools.genericui.enums.bootstrap.BootstrapType] tinting to
 * [com.nexustools.genericui.qt.render.QtControlRenderUtil].
 */
class QtContainerNativeImpl(
    nativeWidget: QtWidgetHandle = QtWidgetHandle("QWidget_QBoxLayout"),
    classPrefix: String = "container"
) : com.nexustools.genericui.qt.QtContainerNativeImpl(nativeWidget, classPrefix)
