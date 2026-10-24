package com.nexustools.genericui.swing

import com.nexustools.genericui.enums.ui.ObjectFit
import com.nexustools.genericui.nativeimpl.content.ImageNativeImpl
import javax.swing.JComponent
import javax.swing.JLabel

/**
 * Java Swing `JLabel` + `ImageIcon` / `BufferedImage` implementation of [ImageNativeImpl].
 */
class SwingImageImpl : SwingBaseNativeImpl(JLabel(), "img-fluid"), ImageNativeImpl<JComponent> {
    override val nativeWidget: JLabel = super.nativeWidget as JLabel

    private var sourceUrlVal: String? = null
    private var objectFitVal: ObjectFit = ObjectFit.CONTAIN

    override fun setSourceUrl(url: String?) {
        sourceUrlVal = url
        nativeWidget.putClientProperty("src", url)
    }

    override fun getSourceUrl(): String? = sourceUrlVal

    override fun setFallbackText(fallback: String?) {
        nativeWidget.toolTipText = fallback
    }

    override fun setObjectFit(fit: ObjectFit) {
        objectFitVal = fit
        nativeWidget.putClientProperty("objectFit", fit.name)
    }

    override fun getObjectFit(): ObjectFit = objectFitVal
}
