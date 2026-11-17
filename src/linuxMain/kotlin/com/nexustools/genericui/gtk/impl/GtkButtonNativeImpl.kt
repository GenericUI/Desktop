package com.nexustools.genericui.gtk.impl

import com.nexustools.genericui.gtk.GtkWidgetHandle

/**
 * GTK4 / Libadwaita per-component native implementation for `Button` (`GtkButton`),
 * delegating rendering and [com.nexustools.genericui.enums.bootstrap.BootstrapType] tinting to
 * [com.nexustools.genericui.gtk.render.GtkControlRenderUtil].
 */
class GtkButtonNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkButton"),
    classPrefix: String = "btn"
) : com.nexustools.genericui.gtk.GtkButtonNativeImpl(nativeWidget, classPrefix)
