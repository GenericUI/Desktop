package com.nexustools.genericui.desktop.layout

import com.nexustools.genericui.component.layout.CollapsibleBottomDockLayout
import com.nexustools.genericui.component.layout.ResponsiveCollapsibleBottomDockLayoutImpl
import com.nexustools.genericui.geometry.Size

/**
 * Desktop [ResponsiveCollapsibleBottomDockLayoutImpl] that enforces minimum desktop window dimensions.
 */
class DesktopCollapsibleBottomDockLayoutImpl(
    layout: CollapsibleBottomDockLayout
) : ResponsiveCollapsibleBottomDockLayoutImpl(layout) {

    override val supportsDynamicViewportSwitching: Boolean = false

    override val minRequiredWindowSize: Size = Size(800, 560)
}
