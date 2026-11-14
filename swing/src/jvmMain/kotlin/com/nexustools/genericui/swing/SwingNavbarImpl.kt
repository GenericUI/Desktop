package com.nexustools.genericui.swing

import com.nexustools.genericui.nativeimpl.navigation.NavbarNativeImpl
import javax.swing.JComponent
import javax.swing.JToolBar

/**
 * Java Swing `JToolBar` implementation of [NavbarNativeImpl].
 */
class SwingNavbarImpl : SwingBaseContainerNativeImpl(JToolBar(), "navbar"), NavbarNativeImpl<JComponent> {
    private var brandTitleText: String = ""
    private var brandIconSpec: String? = null
    private var showBrandOverride: Boolean? = null

    override fun setBrandTitle(title: String) {
        brandTitleText = title
        nativeWidget.name = if (isBrandVisible()) title else ""
    }

    override fun getBrandTitle(): String = brandTitleText

    override fun setBrandIcon(icon: String?) {
        brandIconSpec = icon
    }

    override fun getBrandIcon(): String? = brandIconSpec

    override fun setShowBrand(show: Boolean?) {
        showBrandOverride = show
        nativeWidget.name = if (isBrandVisible()) brandTitleText else ""
    }

    override fun getShowBrand(): Boolean? = showBrandOverride

    override fun isBrandVisible(): Boolean = showBrandOverride ?: false
}
