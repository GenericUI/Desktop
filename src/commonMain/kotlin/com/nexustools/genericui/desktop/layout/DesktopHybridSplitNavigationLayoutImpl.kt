package com.nexustools.genericui.desktop.layout

import com.nexustools.genericui.component.layout.HybridSplitNavigationLayout
import com.nexustools.genericui.component.layout.ResponsiveHybridSplitNavigationLayoutImpl
import com.nexustools.genericui.geometry.Size

/**
 * Desktop [ResponsiveHybridSplitNavigationLayoutImpl] that enforces the large-screen top tab bar
 * navigation mode without compact bottom navigation bar switching.
 */
class DesktopHybridSplitNavigationLayoutImpl(
    layout: HybridSplitNavigationLayout
) : ResponsiveHybridSplitNavigationLayoutImpl(layout) {

    override val supportsDynamicViewportSwitching: Boolean = false

    override val minRequiredWindowSize: Size = Size(800, 560)

    override val inColumnMode: Boolean
        get() = true

    override fun syncNavigationChrome(inColumnMode: Boolean) {
        super.syncNavigationChrome(true)
    }
}
