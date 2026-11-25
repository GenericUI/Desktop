@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.dock.DesktopDockWorkspaceNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.enums.dock.DockPosition
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.nativeimpl.dock.DockAreaNativeImpl
import com.nexustools.genericui.style.css.CssTransform

/**
 * Retained KDE studio dock workspace (`QWidget_DockWorkspace`) coordinating left/right/top/bottom
 * dock sidebars, central document viewport, and multi-window dock transitions.
 */
open class KdeDockWorkspaceNativeImpl(
    nativeWidget: KdeWidgetHandle = KdeWidgetHandle("QWidget_DockWorkspace")
) : DesktopDockWorkspaceNativeImpl<KdeWidgetHandle>(nativeWidget) {

    init {
        KdeDynamicBindings.ensureQApplication()
        val ptr = KdeDynamicBindings.createQWidget(null, 0)
        nativeWidget.attachToQWidget(ptr, "QWidget_DockWorkspace", customPainted = false)
        KdeVTableBridge.attachWidgetHooks(ptr, KdeVTableBridge.HOOK_RESIZE) {
            onResize = { _, w, h -> layoutWorkspace(w, h) }
        }
    }

    private fun layoutWorkspace(totalW: Int, totalH: Int) {
        if (totalW <= 0 || totalH <= 0) return

        val topArea = dockAreas[DockPosition.TOP] as? DesktopBaseNativeImpl<*>
        val bottomArea = dockAreas[DockPosition.BOTTOM] as? DesktopBaseNativeImpl<*>
        val leftArea = dockAreas[DockPosition.LEFT] as? DesktopBaseNativeImpl<*>
        val rightArea = dockAreas[DockPosition.RIGHT] as? DesktopBaseNativeImpl<*>

        val topH = if (topArea != null && dockAreas[DockPosition.TOP]?.isCollapsed() != true) 160.coerceAtMost(totalH / 3) else 0
        val bottomH = if (bottomArea != null && dockAreas[DockPosition.BOTTOM]?.isCollapsed() != true) 180.coerceAtMost(totalH / 3) else 0
        val middleY = topH
        val middleH = (totalH - topH - bottomH).coerceAtLeast(0)

        topArea?.setBounds(Rect(0, 0, totalW, topH))
        bottomArea?.setBounds(Rect(0, (totalH - bottomH).coerceAtLeast(middleY), totalW, bottomH))

        val leftW = if (leftArea != null && dockAreas[DockPosition.LEFT]?.isCollapsed() != true) 240.coerceAtMost(totalW / 3) else 0
        val rightW = if (rightArea != null && dockAreas[DockPosition.RIGHT]?.isCollapsed() != true) 260.coerceAtMost(totalW / 3) else 0
        val centerW = (totalW - leftW - rightW).coerceAtLeast(0)

        leftArea?.setBounds(Rect(0, middleY, leftW, middleH))
        rightArea?.setBounds(Rect((totalW - rightW).coerceAtLeast(leftW), middleY, rightW, middleH))

        (centralDocument as? DesktopBaseNativeImpl<*>)?.let { center ->
            (center.nativeWidget as? KdeWidgetHandle)?.syncChildParentAndVisibility(nativeWidget)
            center.setBounds(Rect(leftW, middleY, centerW, middleH))
        }
    }

    override fun setCentralDocumentNative(centralImpl: NativeImpl<*>?) {
        val prev = centralDocument
        if (prev != null && prev !== centralImpl) {
            (prev.nativeWidget as? KdeWidgetHandle)?.syncChildParentAndVisibility(null)
        }
        super.setCentralDocumentNative(centralImpl)
        (centralImpl?.nativeWidget as? KdeWidgetHandle)?.syncChildParentAndVisibility(nativeWidget)
        val b = nativeWidget.bounds
        if (b.width > 0 && b.height > 0) layoutWorkspace(b.width, b.height)
    }

    override fun attachDockAreaNative(position: DockPosition, areaImpl: DockAreaNativeImpl<*>) {
        super.attachDockAreaNative(position, areaImpl)
        (areaImpl.nativeWidget as? KdeWidgetHandle)?.syncChildParentAndVisibility(nativeWidget)
        val b = nativeWidget.bounds
        if (b.width > 0 && b.height > 0) layoutWorkspace(b.width, b.height)
    }

    override fun onChildAddedNative(childImpl: NativeImpl<*>, index: Int) {
        super.onChildAddedNative(childImpl, index)
        (childImpl.nativeWidget as? KdeWidgetHandle)?.syncChildParentAndVisibility(nativeWidget)
        val b = nativeWidget.bounds
        if (b.width > 0 && b.height > 0) layoutWorkspace(b.width, b.height)
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
        Size(maxWidth?.toInt() ?: 800, maxHeight?.toInt() ?: 560)

    override fun onNativeBoundsChanged(rect: Rect) { nativeWidget.applyBoundsToNative(rect); layoutWorkspace(rect.width, rect.height) }
    override fun onNativeVisibilityChanged(visible: Boolean) { nativeWidget.syncVisibilityToNative(visible) }
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
