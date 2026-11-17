package com.nexustools.genericui.swing

import com.nexustools.genericui.core.AbstractContainerNativeImpl
import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.style.transition.ContainerAnimationFrameState
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Base [AbstractContainerNativeImpl] for the `:swing` backend.
 */
open class SwingBaseContainerNativeImpl(
    component: JComponent = JPanel(),
    componentClassPrefix: String = ""
) : SwingBaseNativeImpl(component, componentClassPrefix), AbstractContainerNativeImpl<JComponent> {

    var animatedBuffer: ImageBuffer? = null
        private set
    var animatedFrameState: ContainerAnimationFrameState? = null
        private set

    override fun addChildNative(childImpl: NativeImpl<*>, index: Int) {
        val childComp = childImpl.nativeWidget as? JComponent ?: return
        if (index < 0 || index >= nativeWidget.componentCount) {
            nativeWidget.add(childComp)
        } else {
            nativeWidget.add(childComp, index)
        }
        nativeWidget.revalidate()
        nativeWidget.repaint()
    }

    override fun removeChildNative(childImpl: NativeImpl<*>) {
        val childComp = childImpl.nativeWidget as? JComponent ?: return
        nativeWidget.remove(childComp)
        nativeWidget.revalidate()
        nativeWidget.repaint()
    }

    override fun clearChildrenNative() {
        nativeWidget.removeAll()
        nativeWidget.revalidate()
        nativeWidget.repaint()
    }

    override fun enableHardwareLayerCache(enabled: Boolean) {
        nativeWidget.isDoubleBuffered = true
    }

    override fun presentAnimatedBuffer(buffer: ImageBuffer, frameState: ContainerAnimationFrameState) {
        animatedBuffer = buffer
        animatedFrameState = frameState
        setOpacity(frameState.opacity)
        nativeWidget.repaint()
    }

    override fun releaseAnimatedBuffer() {
        animatedBuffer = null
        animatedFrameState = null
        nativeWidget.repaint()
    }
}
