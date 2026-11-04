package com.nexustools.genericui.desktop.layout

import com.nexustools.genericui.component.layout.DefaultResponsiveBentoGridLayoutImpl
import com.nexustools.genericui.component.layout.ResponsiveBentoGridLayout
import com.nexustools.genericui.geometry.Size

/**
 * Desktop [DefaultResponsiveBentoGridLayoutImpl] that always renders a multi-column bento grid
 * and enforces minimum desktop window dimensions.
 */
class DesktopResponsiveBentoGridLayoutImpl(
    layout: ResponsiveBentoGridLayout
) : DefaultResponsiveBentoGridLayoutImpl(layout) {

    override val supportsDynamicViewportSwitching: Boolean = false

    override val minRequiredWindowSize: Size = Size(840, 560)

    override fun syncGrid(viewportWidthDp: Float, viewportHeightDp: Float) {
        super.syncGrid(
            viewportWidthDp = maxOf(viewportWidthDp, layout.breakpointSpec.mediumBreakpointDp.toFloat()),
            viewportHeightDp = viewportHeightDp
        )
    }
}
