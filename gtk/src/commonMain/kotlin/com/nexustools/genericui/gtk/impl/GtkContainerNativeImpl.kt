package com.nexustools.genericui.gtk.impl

import com.nexustools.genericui.gtk.GtkWidgetHandle

/**
 * GTK4 / Libadwaita per-component native implementation for `Container` / `Card` (`GtkBox`),
 * delegating rendering and [com.nexustools.genericui.enums.bootstrap.BootstrapType] tinting to
 * [com.nexustools.genericui.gtk.render.GtkControlRenderUtil].
 */
class GtkContainerNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkBox"),
    classPrefix: String = "container"
) : com.nexustools.genericui.gtk.GtkContainerNativeImpl(nativeWidget, classPrefix)
