@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.dock.DesktopDockAreaNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.enums.dock.DockPosition
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.css.CssTransform

/**
 * Retained KDE dock area (`QWidget_DockArea`) arranging attached `DockGroup`s horizontally
 * (top/bottom edges) or vertically (left/right edges) with collapsible visibility.
 */
open class KdeDockAreaNativeImpl(
    nativeWidget: KdeWidgetHandle = KdeWidgetHandle("QWidget_DockArea")
) : DesktopDockAreaNativeImpl<KdeWidgetHandle>(nativeWidget) {

    init {
        KdeDynamicBindings.ensureQApplication()
        val ptr = KdeDynamicBindings.createQWidget(null, 0)
        nativeWidget.attachToQWidget(ptr, "QWidget_DockArea", customPainted = false)
        KdeVTableBridge.attachWidgetHooks(ptr, KdeVTableBridge.HOOK_RESIZE) {
            onResize = { _, w, h -> layoutAreaChildren(w, h) }
        }
    }

    private fun layoutAreaChildren(w: Int, h: Int) {
        if (collapsedState || w <= 0 || h <= 0 || childImpls.isEmpty()) return
        val isHorizontal = dockPosition == DockPosition.TOP || dockPosition == DockPosition.BOTTOM
        val count = childImpls.size
        if (isHorizontal) {
            val sliceW = (w / count).coerceAtLeast(1)
            for (i in childImpls.indices) {
                val child = childImpls[i] as? DesktopBaseNativeImpl<*> ?: continue
                val x = i * sliceW
                val cw = if (i == count - 1) (w - x).coerceAtLeast(1) else sliceW
                child.setBounds(Rect(x, 0, cw, h))
            }
        } else {
            val sliceH = (h / count).coerceAtLeast(1)
            for (i in childImpls.indices) {
                val child = childImpls[i] as? DesktopBaseNativeImpl<*> ?: continue
                val y = i * sliceH
                val ch = if (i == count - 1) (h - y).coerceAtLeast(1) else sliceH
                child.setBounds(Rect(0, y, w, ch))
            }
        }
    }

    override fun setPosition(position: DockPosition) {
        super.setPosition(position)
        val b = nativeWidget.bounds
        if (b.width > 0 && b.height > 0) layoutAreaChildren(b.width, b.height)
    }

    override fun setCollapsed(collapsed: Boolean) {
        super.setCollapsed(collapsed)
        nativeWidget.syncVisibilityToNative(!collapsed && isVisible())
    }

    override fun onChildAddedNative(childImpl: NativeImpl<*>, index: Int) {
        super.onChildAddedNative(childImpl, index)
        (childImpl.nativeWidget as? KdeWidgetHandle)?.syncChildParentAndVisibility(nativeWidget)
        val b = nativeWidget.bounds
        if (b.width > 0 && b.height > 0) layoutAreaChildren(b.width, b.height)
    }

    override fun onChildRemovedNative(childImpl: NativeImpl<*>) {
        (childImpl.nativeWidget as? KdeWidgetHandle)?.syncChildParentAndVisibility(null)
        super.onChildRemovedNative(childImpl)
    }

    override fun onChildrenClearedNative() {
        for (c in childImpls) (c.nativeWidget as? KdeWidgetHandle)?.syncChildParentAndVisibility(null)
        super.onChildrenClearedNative()
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size =
        if (collapsedState) Size(0, 0) else Size(240, 220)

    override fun onNativeBoundsChanged(rect: Rect) { nativeWidget.applyBoundsToNative(rect); layoutAreaChildren(rect.width, rect.height) }
    override fun onNativeVisibilityChanged(visible: Boolean) { nativeWidget.syncVisibilityToNative(visible && !collapsedState) }
    override fun onNativeEnabledChanged(enabled: Boolean) { nativeWidget.qWidgetPtr?.let { KdeDynamicBindings.setWidgetEnabled(it, enabled) } }
    override fun onNativeOpacityChanged(opacity: Float) {}
    override fun onNativeTooltipChanged(tooltip: String?) { KdePainterSupport.applyCommonWidgetProperties(this, nativeWidget) }
    override fun onNativeCursorChanged(cursor: CssCursorValue?) { KdePainterSupport.applyCommonWidgetProperties(this, nativeWidget) }
    override fun onNativePointerEventsChanged(pointerEventsNone: Boolean) { KdePainterSupport.applyCommonWidgetProperties(this, nativeWidget) }
    override fun onNativeZIndexChanged(zIndex: Int) { nativeWidget.qWidgetPtr?.let { KdeDynamicBindings.raiseWidget(it) } }
    override fun onNativeTransformChanged(transform: CssTransform?) {}
    override fun onNativeStyleSynced() { KdePainterSupport.applyCommonWidgetProperties(this, nativeWidget) }

    override fun dispose() {
        super.dispose()
        nativeWidget.destroyQWidget()
    }
}
