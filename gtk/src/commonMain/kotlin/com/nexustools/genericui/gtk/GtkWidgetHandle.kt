package com.nexustools.genericui.gtk

import com.nexustools.genericui.desktop.core.DesktopNativeWidget

/**
 * GTK 4 / Libadwaita native pointer handle (`GtkWidget*`, `GtkWindow*`, `GMenu*`, `GtkApplication*`)
 * descriptor wrapping a GType widget class name and GTK CSS class list.
 */
class GtkWidgetHandle(
    gtkTypeName: String,
    gtkPointer: Long = nextPointerId()
) : DesktopNativeWidget(
    backendToolkit = "GTK4_Libadwaita",
    nativeClassName = gtkTypeName,
    nativePointer = gtkPointer
) {
    val gtkCssClasses: MutableSet<String> = linkedSetOf()
}
