package com.nexustools.genericui.desktop.layout

import com.nexustools.genericui.component.layout.AdaptiveNavigationScaffoldLayout
import com.nexustools.genericui.component.layout.DefaultAdaptiveNavigationScaffoldLayoutImpl
import com.nexustools.genericui.geometry.Size

/**
 * Desktop [DefaultAdaptiveNavigationScaffoldLayoutImpl] that always renders large-screen navigation
 * (navigation rail or permanent drawer) instead of compact bottom navigation and enforces minimum window dimensions.
 */
class DesktopAdaptiveNavigationScaffoldLayoutImpl(
    layout: AdaptiveNavigationScaffoldLayout
) : DefaultAdaptiveNavigationScaffoldLayoutImpl(layout) {

    override val supportsDynamicViewportSwitching: Boolean = false

    override val minRequiredWindowSize: Size = Size(840, 560)

    override fun syncScaffold(viewportWidthDp: Float, viewportHeightDp: Float) {
        super.syncScaffold(
            viewportWidthDp = maxOf(viewportWidthDp, layout.breakpointSpec.mediumBreakpointDp.toFloat()),
            viewportHeightDp = viewportHeightDp
        )
    }
}
