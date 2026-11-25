package com.nexustools.genericui.desktop.core

/**
 * Backend registration hooks allowing native desktop toolkits (GTK4, KDE, macOS, Win32)
 * to supply toolkit-specific [DesktopNativeWidget] handles and API names to `:extensions:graphics:canvas2d`
 * without coupling extension shared libraries directly to a single Linux toolkit's classes.
 */
object DesktopCanvasWidgetHooks {
    var createCanvas2DWidgetAndApiName: (() -> Pair<DesktopNativeWidget, String>)? = null
    var createInteractiveSurfaceWidget: ((role: String) -> DesktopNativeWidget)? = null
}
