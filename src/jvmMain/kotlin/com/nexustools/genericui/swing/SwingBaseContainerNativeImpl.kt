package com.nexustools.genericui.swing

import com.nexustools.genericui.core.AbstractContainerNativeImpl
import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.style.transition.ContainerAnimationFrameState
import java.awt.Component
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Base [AbstractContainerNativeImpl] for the `:swing` backend.
 */
open class SwingBaseContainerNativeImpl(
    component: JComponent = JPanel(),
    componentClassPrefix: String = ""
) : SwingBaseNativeImpl(component, componentClassPrefix), AbstractContainerNativeImpl<JComponent> {

    private val logicalChildrenList = ArrayList<Component>()

    var animatedBuffer: ImageBuffer? = null
        private set
    var animatedFrameState: ContainerAnimationFrameState? = null
        private set

    internal fun getLogicalChildComponents(): List<Component> =
        if (logicalChildrenList.isNotEmpty() || nativeWidget.componentCount == 0) {
            logicalChildrenList.toList()
        } else {
            nativeWidget.components.toList()
        }

    override fun addChildNative(childImpl: NativeImpl<*>, index: Int) {
        val childComp = childImpl.nativeWidget as? JComponent ?: return
        logicalChildrenList.remove(childComp)
        val insertIdx = if (index < 0 || index >= logicalChildrenList.size) {
            logicalChildrenList.size
        } else {
            index
        }
        logicalChildrenList.add(insertIdx, childComp)

        if (childComp.parent === nativeWidget) {
            nativeWidget.remove(childComp)
        }
        if (index < 0 || index >= nativeWidget.componentCount) {
            nativeWidget.add(childComp)
        } else {
            nativeWidget.add(childComp, index)
        }
        (childImpl as? SwingBaseNativeImpl)?.refreshThemeAppearance()
        invalidateLayoutHierarchy()
        nativeWidget.repaint()
    }

    override fun removeChildNative(childImpl: NativeImpl<*>) {
        val childComp = childImpl.nativeWidget as? JComponent ?: return
        logicalChildrenList.remove(childComp)
        nativeWidget.remove(childComp)
        invalidateLayoutHierarchy()
        nativeWidget.repaint()
    }

    override fun clearChildrenNative() {
        logicalChildrenList.clear()
        nativeWidget.removeAll()
        invalidateLayoutHierarchy()
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
