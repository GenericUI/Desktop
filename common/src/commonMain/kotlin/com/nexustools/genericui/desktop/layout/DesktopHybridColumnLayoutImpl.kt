package com.nexustools.genericui.desktop.layout

import com.nexustools.genericui.component.layout.HybridColumnLayout
import com.nexustools.genericui.component.layout.HybridLayoutMode
import com.nexustools.genericui.component.layout.ResponsiveHybridColumnLayoutImpl
import com.nexustools.genericui.geometry.Size

/**
 * Desktop implementation of [com.nexustools.genericui.component.layout.HybridColumnLayoutImpl].
 *
 * Desktop implements only the large-screen multi-column layout ([HybridLayoutMode.COLUMNS], or
 * [HybridLayoutMode.FULLSCREEN] / [HybridLayoutMode.PIP] when explicitly active) and never collapses
 * into small-screen [HybridLayoutMode.SINGLE], while enforcing `minRequiredWindowSize`.
 */
open class DesktopHybridColumnLayoutImpl(
    layout: HybridColumnLayout
) : ResponsiveHybridColumnLayoutImpl(layout) {

    override val supportsDynamicViewportSwitching: Boolean = false

    override val minRequiredWindowSize: Size =
        Size(width = maxOf(800, layout.breakpointSpec.mediumBreakpointDp), height = 560)

    override fun evaluateLayoutMode(
        viewportWidthDp: Float,
        viewportHeightDp: Float,
        smallestScreenWidthDp: Int
    ): HybridLayoutMode = when {
        layout.isInPictureInPictureMode -> HybridLayoutMode.PIP
        layout.isFullscreen -> HybridLayoutMode.FULLSCREEN
        else -> HybridLayoutMode.COLUMNS
    }
}
