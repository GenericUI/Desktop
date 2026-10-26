package com.nexustools.genericui.desktop.widget

import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.nativeimpl.layout.ScrollViewNativeImpl

/**
 * Shared desktop base implementation of [ScrollViewNativeImpl].
 */
open class DesktopScrollViewNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W
) : DesktopBaseContainerNativeImpl<W>(nativeWidget, "scroll-view"), ScrollViewNativeImpl<W> {

    protected var scrollX: Int = 0
    protected var scrollY: Int = 0
    var scrollRequestVersion: Long = 0L
        private set

    fun currentScrollX(): Int = scrollX
    fun currentScrollY(): Int = scrollY

    fun updateScrollPositionFromHost(x: Int, y: Int) {
        scrollX = x.coerceAtLeast(0)
        scrollY = y.coerceAtLeast(0)
        nativeWidget.attributes["scrollX"] = scrollX
        nativeWidget.attributes["scrollY"] = scrollY
    }

    override fun scrollTo(x: Int, y: Int) {
        scrollX = x.coerceAtLeast(0)
        scrollY = y.coerceAtLeast(0)
        scrollRequestVersion++
        nativeWidget.attributes["scrollX"] = scrollX
        nativeWidget.attributes["scrollY"] = scrollY
        nativeWidget.attributes["scrollRequestVersion"] = scrollRequestVersion
    }

    override fun scrollToBottom() {
        scrollY = Int.MAX_VALUE / 2
        scrollRequestVersion++
        nativeWidget.attributes["scrollY"] = scrollY
        nativeWidget.attributes["scrollRequestVersion"] = scrollRequestVersion
    }
}
