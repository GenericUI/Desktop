package com.nexustools.genericui.gtk.impl

import com.nexustools.genericui.gtk.GtkWidgetHandle

/**
 * GTK4 / Libadwaita per-component native implementation for `TabContainer` (`AdwViewStack`),
 * delegating rendering to [com.nexustools.genericui.gtk.render.GtkControlRenderUtil].
 */
class GtkTabContainerNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("AdwViewStack")
) : com.nexustools.genericui.gtk.GtkTabContainerNativeImpl(nativeWidget)
