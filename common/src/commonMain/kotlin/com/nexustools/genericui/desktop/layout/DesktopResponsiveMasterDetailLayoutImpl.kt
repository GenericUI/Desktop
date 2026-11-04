package com.nexustools.genericui.desktop.layout

import com.nexustools.genericui.component.layout.DefaultResponsiveMasterDetailLayoutImpl
import com.nexustools.genericui.component.layout.ResponsiveMasterDetailLayout
import com.nexustools.genericui.geometry.Size

/**
 * Desktop [DefaultResponsiveMasterDetailLayoutImpl] that always renders side-by-side master+detail panes
 * and enforces minimum desktop window dimensions.
 */
class DesktopResponsiveMasterDetailLayoutImpl(
    layout: ResponsiveMasterDetailLayout
) : DefaultResponsiveMasterDetailLayoutImpl(layout) {

    override val supportsDynamicViewportSwitching: Boolean = false

    override val minRequiredWindowSize: Size = Size(840, 560)

    override fun syncPanes(viewportWidthDp: Float, viewportHeightDp: Float) {
        super.syncPanes(
            viewportWidthDp = maxOf(viewportWidthDp, layout.breakpointSpec.mediumBreakpointDp.toFloat()),
            viewportHeightDp = viewportHeightDp
        )
    }
}
