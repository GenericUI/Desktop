package com.nexustools.genericui.gtk

import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
class GtkWidgetHandle(
    gtkTypeName: String,
    gtkPointer: Long = nextPointerId()
) : DesktopNativeWidget(
    backendToolkit = "GTK4_Libadwaita",
    nativeClassName = gtkTypeName,
    nativePointer = gtkPointer
) {
    val gtkCssClasses: MutableSet<String> = linkedSetOf()
    var realGtkWidgetPtr: COpaquePointer? = null
}
