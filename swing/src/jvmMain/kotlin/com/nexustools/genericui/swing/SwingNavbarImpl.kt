package com.nexustools.genericui.swing

import com.nexustools.genericui.nativeimpl.navigation.NavbarNativeImpl
import javax.swing.JComponent
import javax.swing.JToolBar

/**
 * Java Swing `JToolBar` implementation of [NavbarNativeImpl].
 */
class SwingNavbarImpl : SwingBaseContainerNativeImpl(JToolBar(), "navbar"), NavbarNativeImpl<JComponent> {
    private var brandTitleText: String = ""

    override fun setBrandTitle(title: String) {
        brandTitleText = title
        nativeWidget.name = title
    }

    override fun getBrandTitle(): String = brandTitleText
}
