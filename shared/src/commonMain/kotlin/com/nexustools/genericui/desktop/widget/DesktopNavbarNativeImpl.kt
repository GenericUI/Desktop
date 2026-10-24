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

    override fun setBrandTitle(title: String) {
        brandTitleText = title
        nativeWidget.attributes["brandTitle"] = title
    }

    override fun getBrandTitle(): String = brandTitleText
}
