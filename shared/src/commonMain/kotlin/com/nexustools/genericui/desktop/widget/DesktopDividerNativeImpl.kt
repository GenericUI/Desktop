package com.nexustools.genericui.desktop.widget

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.nativeimpl.layout.DividerNativeImpl

/**
 * Shared desktop base implementation of [DividerNativeImpl].
 */
open class DesktopDividerNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W
) : DesktopBaseNativeImpl<W>(nativeWidget, "divider"), DividerNativeImpl<W>
