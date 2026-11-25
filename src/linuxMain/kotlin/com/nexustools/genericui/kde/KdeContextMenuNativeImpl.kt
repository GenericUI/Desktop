@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.shell.DesktopContextMenuNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssTransform

/**
 * Retained KDE context popup menu backed by Qt 6 **`QMenu`**, popped up globally via
 * `QMenu::popup` relative to the anchor widget or cursor coordinates.
 */
open class KdeContextMenuNativeImpl(
    nativeWidget: KdeWidgetHandle = KdeWidgetHandle("QMenu_Context")
) : DesktopContextMenuNativeImpl<KdeWidgetHandle>(nativeWidget) {

    init {
        KdeDynamicBindings.ensureQApplication()
        val ptr = KdeDynamicBindings.createQMenu(null)
        KdeDynamicBindings.setWidgetVisible(ptr, false)
        nativeWidget.isTopLevelWindow = true
        nativeWidget.attachToQWidget(ptr, "QMenu_Context", customPainted = false)
    }

    private fun rebuildContextMenu() {
        val ptr = nativeWidget.qWidgetPtr ?: return
        if (!KdeVTableBridge.isPeerAlive(ptr)) return
        KdeMenuNativeImpl.populateQtMenu(ptr, childImpls) { hide() }
    }

    override fun addChildNative(childImpl: NativeImpl<*>, index: Int) {
        super.addChildNative(childImpl, index)
        rebuildContextMenu()
    }

    override fun removeChildNative(childImpl: NativeImpl<*>) {
        super.removeChildNative(childImpl)
        rebuildContextMenu()
    }

    override fun clearChildrenNative() {
        super.clearChildrenNative()
        rebuildContextMenu()
    }

    override fun showAt(anchor: NativeImpl<*>?, x: Int, y: Int) {
        super.showAt(anchor, x, y)
        val ptr = nativeWidget.qWidgetPtr ?: return
        rebuildContextMenu()
        val anchorPtr = (anchor?.nativeWidget as? KdeWidgetHandle)?.qWidgetPtr
        val (gx, gy) = if (anchorPtr != null && KdeVTableBridge.isPeerAlive(anchorPtr)) {
            KdeDynamicBindings.mapToGlobal(anchorPtr, x, y)
        } else {
            x to y
        }
        KdeDynamicBindings.popupMenuAt(ptr, gx, gy)
    }

    override fun hide() {
        super.hide()
        nativeWidget.qWidgetPtr?.let { KdeDynamicBindings.setWidgetVisible(it, false) }
    }

    override fun onNativeBoundsChanged(rect: Rect) {}
    override fun onNativeVisibilityChanged(visible: Boolean) { nativeWidget.qWidgetPtr?.let { KdeDynamicBindings.setWidgetVisible(it, visible) } }
    override fun onNativeEnabledChanged(enabled: Boolean) { nativeWidget.qWidgetPtr?.let { KdeDynamicBindings.setWidgetEnabled(it, enabled) } }
    override fun onNativeOpacityChanged(opacity: Float) {}
    override fun onNativeTooltipChanged(tooltip: String?) {}
    override fun onNativeCursorChanged(cursor: CssCursorValue?) {}
    override fun onNativePointerEventsChanged(pointerEventsNone: Boolean) {}
    override fun onNativeZIndexChanged(zIndex: Int) {}
    override fun onNativeTransformChanged(transform: CssTransform?) {}
    override fun onNativeStyleSynced() { rebuildContextMenu() }

    override fun dispose() {
        super.dispose()
        nativeWidget.destroyQWidget()
    }
}
