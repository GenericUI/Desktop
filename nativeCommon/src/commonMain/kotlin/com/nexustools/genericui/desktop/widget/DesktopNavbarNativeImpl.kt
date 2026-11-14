package com.nexustools.genericui.desktop.widget

import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.nativeimpl.navigation.NavbarNativeImpl

/**
 * Shared desktop base implementation of [NavbarNativeImpl].
 */
open class DesktopNavbarNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W
) : DesktopBaseContainerNativeImpl<W>(nativeWidget, "navbar"), NavbarNativeImpl<W> {

    protected var brandTitleText: String = ""
    protected var brandIconSpec: String? = null
    protected var showBrandOverride: Boolean? = null

    init {
        nativeWidget.attributes["showBrand"] = false
    }

    override fun setBrandTitle(title: String) {
        if (brandTitleText == title && nativeWidget.attributes["brandTitle"] == title) return
        brandTitleText = title
        nativeWidget.attributes["brandTitle"] = title
        notifyTreeInvalidated()
    }

    override fun getBrandTitle(): String = brandTitleText

    override fun setBrandIcon(icon: String?) {
        if (brandIconSpec == icon) return
        brandIconSpec = icon
        if (icon != null) {
            nativeWidget.attributes["brandIcon"] = icon
        } else {
            nativeWidget.attributes.remove("brandIcon")
        }
        notifyTreeInvalidated()
    }

    override fun getBrandIcon(): String? = brandIconSpec

    override fun setShowBrand(show: Boolean?) {
        if (showBrandOverride == show) return
        showBrandOverride = show
        nativeWidget.attributes["showBrand"] = isBrandVisible()
        notifyTreeInvalidated()
    }

    override fun getShowBrand(): Boolean? = showBrandOverride

    /**
     * Desktop backends (`Windows`, `Linux` GTK/Qt, `macOS`) hide the navbar brand title and icon
     * by default (`false`) because they already appear in the OS window titlebar directly above.
     */
    override fun isBrandVisible(): Boolean = showBrandOverride ?: false
}
