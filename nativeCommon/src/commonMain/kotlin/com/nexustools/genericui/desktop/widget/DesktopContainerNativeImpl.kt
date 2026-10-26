package com.nexustools.genericui.desktop.widget

import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.nativeimpl.layout.ContainerNativeImpl

/**
 * Shared desktop base implementation of [ContainerNativeImpl].
 */
open class DesktopContainerNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W,
    classPrefix: String = "container"
) : DesktopBaseContainerNativeImpl<W>(nativeWidget, classPrefix), ContainerNativeImpl<W>
