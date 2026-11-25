@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.dock.DesktopDockGroupNativeImpl
import com.nexustools.genericui.enums.dock.DockGroupMode
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.nativeimpl.dock.DockPanelNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.threading.GenericUiThreading

/**
 * Retained KDE dock panel group supporting tabbed (`QTabBar` + stacked content) and vertical/horizontal
 * split (`DockGroupMode`) arrangements of child [DockPanelNativeImpl]s.
 */
open class KdeDockGroupNativeImpl(
    nativeWidget: KdeWidgetHandle = KdeWidgetHandle("QTabWidget_DockGroup")
) : DesktopDockGroupNativeImpl<KdeWidgetHandle>(nativeWidget) {

    private val tabBarHandle: KdeWidgetHandle
    private var tabCount: Int = 0
    private var suppressTabSignal = false
    private val tabStripHeightPx = 30

    init {
        KdeDynamicBindings.ensureQApplication()
        val rootPtr = KdeDynamicBindings.createQWidget(null, 0)
        val barPtr = KdeDynamicBindings.createQTabBar(rootPtr)
        KdeDynamicBindings.setWidgetVisible(barPtr, true)
        nativeWidget.attachToQWidget(rootPtr, "QTabWidget_DockGroup", customPainted = false)
        tabBarHandle = KdeWidgetHandle("QTabBar_DockGroup", barPtr).apply { desiredParentPtr = rootPtr }

        KdeVTableBridge.connectSignal(barPtr, "currentChanged(int)") { args ->
            if (!suppressTabSignal) {
                val idx = KdeVTableBridge.readSignalArgInt(args, 1) ?: return@connectSignal
                GenericUiThreading.runOnLogic { setActivePanelIndex(idx) }
            }
        }
        KdeVTableBridge.attachWidgetHooks(rootPtr, KdeVTableBridge.HOOK_RESIZE) {
            onResize = { _, w, h -> layoutGroupChildren(w, h) }
        }
    }

    private fun syncTabBarLabels() {
        val barPtr = tabBarHandle.qWidgetPtr ?: return
        if (!KdeVTableBridge.isPeerAlive(barPtr)) return
        val isTabbed = groupModeState == DockGroupMode.TABBED
        KdeDynamicBindings.setWidgetVisible(barPtr, isTabbed && childImpls.isNotEmpty())
        if (!isTabbed) return

        suppressTabSignal = true
        try {
            repeat(tabCount) { KdeDynamicBindings.tabBarRemoveTab(barPtr, 0) }
            tabCount = 0
            for (i in childImpls.indices) {
                val title = (childImpls[i] as? DockPanelNativeImpl<*>)?.getTitle()?.takeIf { it.isNotEmpty() } ?: "Panel ${i + 1}"
                KdeDynamicBindings.tabBarAddTab(barPtr, title)
                tabCount++
            }
            if (tabCount > 0) {
                KdeDynamicBindings.tabBarSetCurrentIndex(barPtr, activePanelIdx.coerceIn(0, tabCount - 1))
            }
        } finally {
            suppressTabSignal = false
        }
    }

    private fun layoutGroupChildren(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val barPtr = tabBarHandle.qWidgetPtr
        val count = childImpls.size
        if (count == 0) {
            barPtr?.let { KdeDynamicBindings.setWidgetVisible(it, false) }
            return
        }
        when (groupModeState) {
            DockGroupMode.TABBED -> {
                barPtr?.let {
                    KdeDynamicBindings.setWidgetVisible(it, true)
                    KdeDynamicBindings.setWidgetGeometry(it, 0, 0, w, tabStripHeightPx)
                }
                val contentH = (h - tabStripHeightPx).coerceAtLeast(1)
                val safeActive = activePanelIdx.coerceIn(0, count - 1)
                for (i in childImpls.indices) {
                    val child = childImpls[i] as? DesktopBaseNativeImpl<*> ?: continue
                    val ch = child.nativeWidget as? KdeWidgetHandle
                    val active = (i == safeActive)
                    ch?.syncVisibilityToNative(active)
                    if (active) {
                        child.setBounds(Rect(0, tabStripHeightPx, w, contentH))
                    }
                }
            }
            DockGroupMode.VERTICAL_SPLIT -> {
                barPtr?.let { KdeDynamicBindings.setWidgetVisible(it, false) }
                val sliceH = (h / count).coerceAtLeast(1)
                for (i in childImpls.indices) {
                    val child = childImpls[i] as? DesktopBaseNativeImpl<*> ?: continue
                    val y = i * sliceH
                    val ch = if (i == count - 1) (h - y).coerceAtLeast(1) else sliceH
                    (child.nativeWidget as? KdeWidgetHandle)?.syncVisibilityToNative(true)
                    child.setBounds(Rect(0, y, w, ch))
                }
            }
            DockGroupMode.HORIZONTAL_SPLIT -> {
                barPtr?.let { KdeDynamicBindings.setWidgetVisible(it, false) }
                val sliceW = (w / count).coerceAtLeast(1)
                for (i in childImpls.indices) {
                    val child = childImpls[i] as? DesktopBaseNativeImpl<*> ?: continue
                    val x = i * sliceW
                    val cw = if (i == count - 1) (w - x).coerceAtLeast(1) else sliceW
                    (child.nativeWidget as? KdeWidgetHandle)?.syncVisibilityToNative(true)
                    child.setBounds(Rect(x, 0, cw, h))
                }
            }
        }
    }

    override fun setGroupMode(mode: DockGroupMode) {
        super.setGroupMode(mode)
        syncTabBarLabels()
        val b = nativeWidget.bounds
        if (b.width > 0 && b.height > 0) layoutGroupChildren(b.width, b.height)
    }

    override fun setActivePanelIndex(index: Int) {
        super.setActivePanelIndex(index)
        syncTabBarLabels()
        val b = nativeWidget.bounds
        if (b.width > 0 && b.height > 0) layoutGroupChildren(b.width, b.height)
    }

    override fun onChildAddedNative(childImpl: NativeImpl<*>, index: Int) {
        super.onChildAddedNative(childImpl, index)
        (childImpl.nativeWidget as? KdeWidgetHandle)?.syncChildParentAndVisibility(nativeWidget)
        syncTabBarLabels()
        val b = nativeWidget.bounds
        if (b.width > 0 && b.height > 0) layoutGroupChildren(b.width, b.height)
    }

    override fun onChildRemovedNative(childImpl: NativeImpl<*>) {
        (childImpl.nativeWidget as? KdeWidgetHandle)?.syncChildParentAndVisibility(null)
        super.onChildRemovedNative(childImpl)
        syncTabBarLabels()
    }

    override fun onChildrenClearedNative() {
        for (c in childImpls) (c.nativeWidget as? KdeWidgetHandle)?.syncChildParentAndVisibility(null)
        super.onChildrenClearedNative()
        syncTabBarLabels()
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size = Size(240, 220)
    override fun onNativeBoundsChanged(rect: Rect) { nativeWidget.applyBoundsToNative(rect); layoutGroupChildren(rect.width, rect.height) }
    override fun onNativeVisibilityChanged(visible: Boolean) { nativeWidget.syncVisibilityToNative(visible) }
    override fun onNativeEnabledChanged(enabled: Boolean) { nativeWidget.qWidgetPtr?.let { KdeDynamicBindings.setWidgetEnabled(it, enabled) } }
    override fun onNativeOpacityChanged(opacity: Float) {}
    override fun onNativeTooltipChanged(tooltip: String?) { KdePainterSupport.applyCommonWidgetProperties(this, nativeWidget) }
    override fun onNativeCursorChanged(cursor: CssCursorValue?) { KdePainterSupport.applyCommonWidgetProperties(this, nativeWidget) }
    override fun onNativePointerEventsChanged(pointerEventsNone: Boolean) { KdePainterSupport.applyCommonWidgetProperties(this, nativeWidget) }
    override fun onNativeZIndexChanged(zIndex: Int) { nativeWidget.qWidgetPtr?.let { KdeDynamicBindings.raiseWidget(it) } }
    override fun onNativeTransformChanged(transform: CssTransform?) {}
    override fun onNativeStyleSynced() { KdePainterSupport.applyCommonWidgetProperties(this, nativeWidget); syncTabBarLabels() }

    override fun dispose() {
        super.dispose()
        nativeWidget.destroyQWidget()
    }
}
