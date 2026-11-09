package com.nexustools.genericui.qt.impl

import com.nexustools.genericui.qt.QtWidgetHandle

/**
 * Qt6 per-component native implementation for `Button` (`QPushButton` / `QStyle::CE_PushButton`),
 * delegating rendering and [com.nexustools.genericui.enums.bootstrap.BootstrapType] tinting to
 * [com.nexustools.genericui.qt.render.QtControlRenderUtil].
 */
class QtButtonNativeImpl(
    nativeWidget: QtWidgetHandle = QtWidgetHandle("QPushButton"),
    classPrefix: String = "btn"
) : com.nexustools.genericui.qt.QtButtonNativeImpl(nativeWidget, classPrefix)
