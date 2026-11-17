package com.nexustools.genericui.desktop.layout

import com.nexustools.genericui.component.layout.AdaptiveCardLayout
import com.nexustools.genericui.component.layout.AdaptiveCardLayoutImpl
import com.nexustools.genericui.component.layout.AdaptiveNavigationScaffoldLayout
import com.nexustools.genericui.component.layout.AdaptiveNavigationScaffoldLayoutImpl
import com.nexustools.genericui.component.layout.CollapsibleBottomDockLayout
import com.nexustools.genericui.component.layout.CollapsibleBottomDockLayoutImpl
import com.nexustools.genericui.component.layout.HybridColumnLayout
import com.nexustools.genericui.component.layout.HybridColumnLayoutImpl
import com.nexustools.genericui.component.layout.HybridSplitNavigationLayout
import com.nexustools.genericui.component.layout.HybridSplitNavigationLayoutImpl
import com.nexustools.genericui.component.layout.LayoutFactory
import com.nexustools.genericui.component.layout.ResponsiveBentoGridLayout
import com.nexustools.genericui.component.layout.ResponsiveBentoGridLayoutImpl
import com.nexustools.genericui.component.layout.ResponsiveMasterDetailLayout
import com.nexustools.genericui.component.layout.ResponsiveMasterDetailLayoutImpl

/**
 * Shared desktop [LayoutFactory] used by `:desktop:windows`, `:desktop:macos`, `:desktop:gtk`,
 * `:desktop:qt`, and `:desktop:swing`.
 *
 * Desktop implements only large-screen layouts (`supportsDynamicViewportSwitching = false`) while
 * enforcing minimum window dimensions (`minRequiredWindowSize`) on the hosting window.
 */
object DesktopLayoutFactory : LayoutFactory {

    override fun createAdaptiveCardLayout(layout: AdaptiveCardLayout): AdaptiveCardLayoutImpl =
        DesktopAdaptiveCardLayoutImpl(layout)

    override fun createHybridColumnLayout(layout: HybridColumnLayout): HybridColumnLayoutImpl =
        DesktopHybridColumnLayoutImpl(layout)

    override fun createHybridSplitNavigationLayout(layout: HybridSplitNavigationLayout): HybridSplitNavigationLayoutImpl =
        DesktopHybridSplitNavigationLayoutImpl(layout)

    override fun createCollapsibleBottomDockLayout(layout: CollapsibleBottomDockLayout): CollapsibleBottomDockLayoutImpl =
        DesktopCollapsibleBottomDockLayoutImpl(layout)

    override fun createResponsiveMasterDetailLayout(layout: ResponsiveMasterDetailLayout): ResponsiveMasterDetailLayoutImpl =
        DesktopResponsiveMasterDetailLayoutImpl(layout)

    override fun createResponsiveBentoGridLayout(layout: ResponsiveBentoGridLayout): ResponsiveBentoGridLayoutImpl =
        DesktopResponsiveBentoGridLayoutImpl(layout)

    override fun createAdaptiveNavigationScaffoldLayout(layout: AdaptiveNavigationScaffoldLayout): AdaptiveNavigationScaffoldLayoutImpl =
        DesktopAdaptiveNavigationScaffoldLayoutImpl(layout)
}
