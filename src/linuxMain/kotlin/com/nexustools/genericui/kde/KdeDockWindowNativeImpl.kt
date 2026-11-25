@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.dock.DesktopDockWindowNativeImpl
import com.nexustools.genericui.geometry.Rect
import kotlin.math.roundToInt

/**
 * KDE (`QWidget(Qt::Tool)`) floating dock utility window implementation of
 * [DesktopDockWindowNativeImpl].
 */
class KdeDockWindowNativeImpl : DesktopDockWindowNativeImpl<KdeWidgetHandle>(
    nativeWidget = KdeWidgetHandle("QWidget_ToolWindow").apply {
        isTopLevelWindow = true
        attributes["floatingUtilityWindow"] = true
        attributes["skipTaskbarHint"] = true
    }
) {
    init {
        KdeDynamicBindings.ensureQApplication()
        val ptr = KdeDynamicBindings.createQWidget(null, KdeDynamicBindings.WINDOW_TOOL)
        nativeWidget.attachToQWidget(ptr, "QWidget_ToolWindow", customPainted = false)
        KdeVTableBridge.attachWidgetHooks(ptr, KdeVTableBridge.HOOK_RESIZE or KdeVTableBridge.HOOK_CLOSE) {
            onResize = { _, w, h ->
                layoutChildrenInBounds(w.toFloat(), h.toFloat())
            }
            onClose = { _ ->
                onCloseRequestedListener?.invoke()
                hideDockWindow()
                false
            }
        }
    }

    override fun onChildAddedNative(childImpl: NativeImpl<*>, index: Int) {
        super.onChildAddedNative(childImpl, index)
        (childImpl.nativeWidget as? KdeWidgetHandle)?.syncChildParentAndVisibility(nativeWidget)
        layoutChildrenInBounds(nativeWidget.bounds.width.coerceAtLeast(320).toFloat(), nativeWidget.bounds.height.coerceAtLeast(240).toFloat())
    }

    override fun onChildRemovedNative(childImpl: NativeImpl<*>) {
        (childImpl.nativeWidget as? KdeWidgetHandle)?.syncChildParentAndVisibility(null)
        super.onChildRemovedNative(childImpl)
    }

    override fun setTitle(title: String) {
        super.setTitle(title)
        nativeWidget.qWidgetPtr?.let { KdeDynamicBindings.setWindowTitle(it, title) }
    }

    override fun setWindowBounds(bounds: Rect) {
        super.setWindowBounds(bounds)
        nativeWidget.applyBoundsToNative(bounds)
        layoutChildrenInBounds(bounds.width.toFloat(), bounds.height.toFloat())
    }

    override fun showDockWindow() {
        super.showDockWindow()
        nativeWidget.qWidgetPtr?.let { ptr ->
            val w = nativeWidget.bounds.width.takeIf { it > 0 } ?: 320
            val h = nativeWidget.bounds.height.takeIf { it > 0 } ?: 240
            layoutChildrenInBounds(w.toFloat(), h.toFloat())
            KdeDynamicBindings.resizeWidget(ptr, w, h)
            if (KdeApplicationNativeImpl.interactiveKdeGuiEnabled) {
                KdeDynamicBindings.setWidgetVisible(ptr, true)
                KdeDynamicBindings.raiseWidget(ptr)
            }
        }
    }

    override fun hideDockWindow() {
        super.hideDockWindow()
        nativeWidget.qWidgetPtr?.let { KdeDynamicBindings.setWidgetVisible(it, false) }
    }

    override fun bringToFront() {
        super.bringToFront()
        nativeWidget.qWidgetPtr?.let {
            KdeDynamicBindings.raiseWidget(it)
            KdeDynamicBindings.activateWindow(it)
        }
    }

    override fun dispose() {
        nativeWidget.destroyQWidget()
        super.dispose()
    }
}
