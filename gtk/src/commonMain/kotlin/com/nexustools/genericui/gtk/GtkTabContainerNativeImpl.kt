package com.nexustools.genericui.gtk

import com.nexustools.genericui.desktop.widget.DesktopTabContainerNativeImpl

/**
 * GTK 4 / Libadwaita `AdwViewStack` + `GtkStack` (`GTK_STACK_TRANSITION_TYPE_CROSSFADE`)
 * implementation of [com.nexustools.genericui.nativeimpl.navigation.TabContainerNativeImpl].
 */
class GtkTabContainerNativeImpl : DesktopTabContainerNativeImpl<GtkWidgetHandle>(
    nativeWidget = GtkWidgetHandle("AdwViewStack"),
    supportsNativeFadeTransition = true
)
