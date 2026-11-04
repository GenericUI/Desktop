package com.nexustools.genericui.desktop.layout

import com.nexustools.genericui.component.layout.AdaptiveCardLayout
import com.nexustools.genericui.component.layout.AdaptiveCardMode
import com.nexustools.genericui.component.layout.ResponsiveAdaptiveCardLayoutImpl
import com.nexustools.genericui.geometry.Size

/**
 * Desktop implementation of [com.nexustools.genericui.component.layout.AdaptiveCardLayoutImpl].
 *
 * Desktop always evaluates as a large screen (`supportsDynamicViewportSwitching = false`) and
 * enforces a minimum window size (`minRequiredWindowSize`) on the hosting window.
 */
open class DesktopAdaptiveCardLayoutImpl(
    layout: AdaptiveCardLayout
) : ResponsiveAdaptiveCardLayoutImpl(layout) {

    override val supportsDynamicViewportSwitching: Boolean = false

    override val minRequiredWindowSize: Size =
        Size(width = maxOf(720, layout.cardMaxWidthPx + 120), height = 520)

    override fun evaluateIsCardActive(
        viewportWidthDp: Float,
        viewportHeightDp: Float,
        smallestScreenWidthDp: Int
    ): Boolean = when (layout.cardMode) {
        AdaptiveCardMode.CARD_ON_LARGE_SCREENS -> true
        AdaptiveCardMode.ALWAYS_CARD -> true
        AdaptiveCardMode.CARD_ON_SMALL_SCREENS -> false
    }
}
