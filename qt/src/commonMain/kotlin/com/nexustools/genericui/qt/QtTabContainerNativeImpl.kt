package com.nexustools.genericui.qt

import com.nexustools.genericui.desktop.widget.DesktopTabContainerNativeImpl

/**
 * Qt 6 `QTabWidget` implementation of [com.nexustools.genericui.nativeimpl.navigation.TabContainerNativeImpl]
 * with `QGraphicsOpacityEffect` + `QPropertyAnimation` fade transitions.
 */
class QtTabContainerNativeImpl : DesktopTabContainerNativeImpl<QtWidgetHandle>(
    nativeWidget = QtWidgetHandle("QTabWidget"),
    supportsNativeFadeTransition = true
)
