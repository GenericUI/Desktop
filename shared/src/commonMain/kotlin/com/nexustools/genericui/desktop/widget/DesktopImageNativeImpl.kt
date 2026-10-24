package com.nexustools.genericui.desktop.widget

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.enums.ui.ObjectFit
import com.nexustools.genericui.nativeimpl.content.ImageNativeImpl

/**
 * Shared desktop base implementation of [ImageNativeImpl].
 */
open class DesktopImageNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W
) : DesktopBaseNativeImpl<W>(nativeWidget, "img"), ImageNativeImpl<W> {

    protected var sourceUrlState: String? = null
    protected var fallbackTextState: String? = null
    protected var objectFitState: ObjectFit = ObjectFit.CONTAIN

    override fun setSourceUrl(url: String?) {
        sourceUrlState = url
        nativeWidget.attributes["sourceUrl"] = url
    }

    override fun getSourceUrl(): String? = sourceUrlState

    override fun setFallbackText(fallback: String?) {
        fallbackTextState = fallback
        nativeWidget.attributes["fallbackText"] = fallback
    }

    override fun setObjectFit(fit: ObjectFit) {
        objectFitState = fit
        nativeWidget.attributes["objectFit"] = fit
    }

    override fun getObjectFit(): ObjectFit = objectFitState
}
