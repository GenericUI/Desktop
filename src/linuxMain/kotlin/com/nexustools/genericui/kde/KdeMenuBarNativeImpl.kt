@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.shell.DesktopMenuBarNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.css.CssTransform

/**
 * Retained KDE window menu bar backed by Qt 6 **`QMenuBar`**, hosting top-level **`QMenu`**
 * hierarchies inside `KdeWindowNativeImpl`.
 */
open class KdeMenuBarNativeImpl(
    nativeWidget: KdeWidgetHandle = KdeWidgetHandle("QMenuBar")
) : DesktopMenuBarNativeImpl<KdeWidgetHandle>(nativeWidget) {

    init {
        KdeDynamicBindings.ensureQApplication()
        val ptr = KdeDynamicBindings.createQMenuBar(null)
        nativeWidget.attachToQWidget(ptr, "QMenuBar", customPainted = false)
    }

    private fun rebuildMenuBar() {
        val ptr = nativeWidget.qWidgetPtr ?: return
        if (!KdeVTableBridge.isPeerAlive(ptr)) return
        KdeDynamicBindings.clearMenuBar(ptr)
        for (child in childImpls) {
            if (child is KdeMenuNativeImpl) {
                child.rebuildNativeMenu()
                child.nativeWidget.qWidgetPtr?.let { menuPtr ->
                    KdeDynamicBindings.addMenuToMenuBar(ptr, menuPtr)
                }
            }
        }
    }

    override fun addChildNative(childImpl: NativeImpl<*>, index: Int) {
        super.addChildNative(childImpl, index)
        rebuildMenuBar()
    }

    override fun removeChildNative(childImpl: NativeImpl<*>) {
        super.removeChildNative(childImpl)
        rebuildMenuBar()
    }

    override fun clearChildrenNative() {
        super.clearChildrenNative()
        rebuildMenuBar()
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size =
        Size(maxWidth?.toInt() ?: 480, 28)

    override fun onNativeBoundsChanged(rect: Rect) { nativeWidget.applyBoundsToNative(rect) }
    override fun onNativeVisibilityChanged(visible: Boolean) { nativeWidget.syncVisibilityToNative(visible) }
    override fun onNativeEnabledChanged(enabled: Boolean) { nativeWidget.qWidgetPtr?.let { KdeDynamicBindings.setWidgetEnabled(it, enabled) } }
    override fun onNativeOpacityChanged(opacity: Float) {}
    override fun onNativeTooltipChanged(tooltip: String?) { KdePainterSupport.applyCommonWidgetProperties(this, nativeWidget) }
    override fun onNativeCursorChanged(cursor: CssCursorValue?) { KdePainterSupport.applyCommonWidgetProperties(this, nativeWidget) }
    override fun onNativePointerEventsChanged(pointerEventsNone: Boolean) {}
    override fun onNativeZIndexChanged(zIndex: Int) { nativeWidget.qWidgetPtr?.let { KdeDynamicBindings.raiseWidget(it) } }
    override fun onNativeTransformChanged(transform: CssTransform?) {}
    override fun onNativeStyleSynced() { rebuildMenuBar() }

    override fun dispose() {
        super.dispose()
        nativeWidget.destroyQWidget()
    }
}
