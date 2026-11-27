package com.nexustools.genericui.desktop.core

import com.nexustools.genericui.core.AbstractContainerNativeImpl
import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.layout.DesktopContainerLayoutController
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.style.transition.ContainerAnimationFrameState

/**
 * Shared desktop base implementation of [AbstractContainerNativeImpl] managing native child widget
 * attachment, insertion, removal, clearing, and retained layout controller delegation.
 */
open class DesktopBaseContainerNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W,
    componentClassPrefix: String = ""
) : DesktopBaseNativeImpl<W>(nativeWidget, componentClassPrefix), AbstractContainerNativeImpl<W> {

    @kotlin.concurrent.Volatile
    protected var childImpls: List<NativeImpl<*>> = emptyList()
        private set
    private val childImplsLock = kotlin.concurrent.AtomicInt(0)
    protected var layoutController: DesktopContainerLayoutController? = null

    private inline fun <T> withChildImplsLock(block: () -> T): T {
        while (!childImplsLock.compareAndSet(0, 1)) {
        }
        return try {
            block()
        } finally {
            childImplsLock.value = 0
        }
    }

    protected open fun onChildAddedNative(childImpl: NativeImpl<*>, index: Int) {}

    protected open fun onChildRemovedNative(childImpl: NativeImpl<*>) {}

    protected open fun onChildrenClearedNative() {}

    protected open fun onChildrenClearedNative(clearedChildren: List<NativeImpl<*>>) {
        onChildrenClearedNative()
    }

    internal fun rawChildImplsSnapshot(): List<NativeImpl<*>> = childImpls

    internal fun hasLayoutController(): Boolean = layoutController != null

    fun getLogicalChildren(): List<NativeImpl<*>> =
        layoutController?.getLogicalChildren() ?: childImpls

    open fun onChildZIndexInvalidated() {
        layoutController?.invalidateZOrder()
    }

    open fun layoutChildrenInBounds(width: Float, height: Float) {
        layoutController?.layoutInBounds(width, height)
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        layoutController?.let { controller ->
            return controller.measurePreferredSize(maxWidth, maxHeight)
        }
        return super.measureIntrinsicSize(maxWidth, maxHeight)
    }

    override fun addChildNative(childImpl: NativeImpl<*>, index: Int) {
        val insertIndex = withChildImplsLock {
            val current = childImpls
            val next = ArrayList<NativeImpl<*>>(current.size + 1)
            for (item in current) {
                if (item !== childImpl) {
                    next.add(item)
                }
            }
            val targetIdx = if (index < 0 || index >= next.size) next.size else index
            next.add(targetIdx, childImpl)
            childImpls = next
            targetIdx
        }
        val childWidget = childImpl.nativeWidget
        if (childWidget is DesktopNativeWidget) {
            nativeWidget.addChildWidget(childWidget, insertIndex)
        }
        layoutController?.addLogicalChild(childImpl, insertIndex)
        onChildAddedNative(childImpl, insertIndex)
    }

    override fun removeChildNative(childImpl: NativeImpl<*>) {
        withChildImplsLock {
            val current = childImpls
            if (current.any { it === childImpl }) {
                val next = ArrayList<NativeImpl<*>>(current.size)
                for (item in current) {
                    if (item !== childImpl) {
                        next.add(item)
                    }
                }
                childImpls = next
            }
        }
        val childWidget = childImpl.nativeWidget
        if (childWidget is DesktopNativeWidget) {
            nativeWidget.removeChildWidget(childWidget)
        }
        layoutController?.removeLogicalChild(childImpl)
        onChildRemovedNative(childImpl)
    }

    override fun clearChildrenNative() {
        val cleared = withChildImplsLock {
            val prev = childImpls.toList()
            childImpls = emptyList()
            prev
        }
        nativeWidget.clearChildWidgets()
        layoutController?.clearLogicalChildren()
        onChildrenClearedNative(cleared)
    }

    override fun enableHardwareLayerCache(enabled: Boolean) {
        nativeWidget.attributes["hardwareLayerCached"] = enabled
    }

    override fun presentAnimatedBuffer(buffer: ImageBuffer, frameState: ContainerAnimationFrameState) {
        nativeWidget.attributes["animatedBuffer"] = buffer
        nativeWidget.attributes["animatedFrameState"] = frameState
        applyResolvedOpacity(frameState.opacity, notifyTree = false)
    }

    override fun releaseAnimatedBuffer() {
        nativeWidget.attributes.remove("animatedBuffer")
        nativeWidget.attributes.remove("animatedFrameState")
    }
}
