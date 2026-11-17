package com.nexustools.genericui.desktop.core

import com.nexustools.genericui.core.AbstractContainerNativeImpl
import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.style.transition.ContainerAnimationFrameState

/**
 * Shared desktop base implementation of [AbstractContainerNativeImpl] managing native child widget
 * attachment, insertion, removal, and clearing.
 */
open class DesktopBaseContainerNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W,
    componentClassPrefix: String = ""
) : DesktopBaseNativeImpl<W>(nativeWidget, componentClassPrefix), AbstractContainerNativeImpl<W> {

    protected val childImpls: MutableList<NativeImpl<*>> = mutableListOf()

    override fun addChildNative(childImpl: NativeImpl<*>, index: Int) {
        childImpls.remove(childImpl)
        if (index < 0 || index >= childImpls.size) {
            childImpls.add(childImpl)
        } else {
            childImpls.add(index, childImpl)
        }
        val childWidget = childImpl.nativeWidget
        if (childWidget is DesktopNativeWidget) {
            nativeWidget.addChildWidget(childWidget, index)
        }
    }

    override fun removeChildNative(childImpl: NativeImpl<*>) {
        childImpls.remove(childImpl)
        val childWidget = childImpl.nativeWidget
        if (childWidget is DesktopNativeWidget) {
            nativeWidget.removeChildWidget(childWidget)
        }
    }

    override fun clearChildrenNative() {
        childImpls.clear()
        nativeWidget.clearChildWidgets()
    }

    override fun enableHardwareLayerCache(enabled: Boolean) {
        nativeWidget.attributes["hardwareLayerCached"] = enabled
    }

    override fun presentAnimatedBuffer(buffer: ImageBuffer, frameState: ContainerAnimationFrameState) {
        nativeWidget.attributes["animatedBuffer"] = buffer
        nativeWidget.attributes["animatedFrameState"] = frameState
        nativeWidget.opacity = frameState.opacity
    }

    override fun releaseAnimatedBuffer() {
        nativeWidget.attributes.remove("animatedBuffer")
        nativeWidget.attributes.remove("animatedFrameState")
    }
}
