@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.component.dock.DockHeaderAction
import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.dock.DesktopDockPanelNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.style.theme.SystemThemeDetector
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.COpaquePointer

/**
 * Retained KDE dock panel backed by a Breeze-styled header strip (collapse toggle, title,
 * and header actions) paired with a collapsible retained child content area.
 */
open class KdeDockPanelNativeImpl(
    nativeWidget: KdeWidgetHandle = KdeWidgetHandle("QWidget_DockPanel")
) : DesktopDockPanelNativeImpl<KdeWidgetHandle>(nativeWidget) {

    private val headerHeightPx = 28

    init {
        KdeDynamicBindings.ensureQApplication()
        val ptr = KdeDynamicBindings.createQWidget(null, 0)
        val kind = if (KdeDynamicBindings.kf6WidgetsAvailable) "KCollapsibleGroupBox_DockPanel" else "QWidget_DockPanel"
        nativeWidget.attachToQWidget(ptr, kind, customPainted = true)
        KdePainterSupport.attachStandardInputHooks(
            widgetPtr = ptr,
            impl = this,
            hookMask = KdeVTableBridge.HOOK_PAINT or KdeVTableBridge.HOOK_RESIZE or KdeVTableBridge.HOOK_SIZE_HINT,
            dispatchLeftClickOnRelease = false,
            onPaint = { _, painter, w, h -> paintPanelChrome(painter, w, h) },
            onSizeHint = { _ ->
                val s = measureIntrinsicSize(null, null)
                s.width to s.height
            }
        )
        KdeVTableBridge.attachWidgetHooks(ptr, KdeVTableBridge.HOOK_INPUT) {
            onMouseRelease = { _, info ->
                if (info.button == 1 && info.localY <= headerHeightPx.toFloat()) {
                    val xi = info.localX.toInt()
                    val totalW = nativeWidget.bounds.width.coerceAtLeast(120)
                    val actionIdx = hitTestHeaderAction(xi, totalW)
                    if (actionIdx in headerActionsList.indices) {
                        val action = headerActionsList[actionIdx]
                        GenericUiThreading.runOnLogic { action.onClick() }
                        true
                    } else if (xi in 0..headerHeightPx) {
                        GenericUiThreading.runOnLogic {
                            toggleCollapsed()
                            layoutPanelChildren()
                            nativeWidget.requestRepaint()
                        }
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
            }
        }
    }

    private fun hitTestHeaderAction(x: Int, totalW: Int): Int {
        if (headerActionsList.isEmpty()) return -1
        val btnW = 24
        val count = headerActionsList.size
        val startX = (totalW - count * btnW - 4).coerceAtLeast(headerHeightPx)
        if (x < startX) return -1
        val idx = (x - startX) / btnW
        return if (idx in 0 until count) idx else -1
    }

    private fun paintPanelChrome(painter: COpaquePointer, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val isDark = SystemThemeDetector.resolveTheme(ThemeMode.AUTO) == ThemeMode.DARK
        val panelBg = getEffectiveBackgroundColor() ?: if (isDark) RgbaColor(42, 46, 50) else RgbaColor(250, 250, 250)
        val headerBg = if (isDark) RgbaColor(50, 54, 59) else RgbaColor(232, 234, 236)
        val border = if (isDark) RgbaColor(74, 80, 86) else RgbaColor(200, 204, 208)
        val textCol = if (isDark) RgbaColor(239, 240, 241) else RgbaColor(35, 38, 41)

        KdeDynamicBindings.painterFillRect(painter, 0.0, 0.0, w.toDouble(), h.toDouble(), panelBg)
        KdeDynamicBindings.painterFillRect(painter, 0.0, 0.0, w.toDouble(), headerHeightPx.toDouble(), headerBg)
        KdeDynamicBindings.painterFillRect(painter, 0.0, (headerHeightPx - 1).toDouble(), w.toDouble(), 1.0, border)
        KdeDynamicBindings.painterStrokeRoundedRect(painter, 0.5, 0.5, (w - 1).toDouble(), (h - 1).toDouble(), 2.0, border, 1.0)

        val chevron = if (collapsedState) "▸" else "▾"
        KdeDynamicBindings.painterDrawText(
            p = painter,
            text = chevron,
            x = 6.0,
            y = 0.0,
            w = 18.0,
            h = headerHeightPx.toDouble(),
            color = textCol,
            fontSizePx = 12f,
            fontWeight = 400,
            italic = false,
            fontFamily = null,
            alignmentFlags = KdeDynamicBindings.ALIGN_HCENTER or KdeDynamicBindings.ALIGN_VCENTER
        )
        if (titleText.isNotEmpty()) {
            val actionsReserve = headerActionsList.size * 24 + 8
            KdeDynamicBindings.painterDrawText(
                p = painter,
                text = titleText,
                x = 24.0,
                y = 0.0,
                w = (w - 28 - actionsReserve).coerceAtLeast(32).toDouble(),
                h = headerHeightPx.toDouble(),
                color = textCol,
                fontSizePx = 12f,
                fontWeight = 700,
                italic = false,
                fontFamily = null,
                alignmentFlags = KdeDynamicBindings.ALIGN_LEFT or KdeDynamicBindings.ALIGN_VCENTER
            )
        }
        val btnW = 24
        val startX = (w - headerActionsList.size * btnW - 4).coerceAtLeast(headerHeightPx)
        for (i in headerActionsList.indices) {
            val act = headerActionsList[i]
            KdeDynamicBindings.painterDrawText(
                p = painter,
                text = act.label.take(2),
                x = (startX + i * btnW).toDouble(),
                y = 2.0,
                w = btnW.toDouble(),
                h = (headerHeightPx - 4).toDouble(),
                color = textCol,
                fontSizePx = 11f,
                fontWeight = 400,
                italic = false,
                fontFamily = null,
                alignmentFlags = KdeDynamicBindings.ALIGN_HCENTER or KdeDynamicBindings.ALIGN_VCENTER
            )
        }
    }

    private fun layoutPanelChildren() {
        val b = nativeWidget.bounds
        val w = b.width.coerceAtLeast(1)
        val contentH = if (collapsedState) 0 else (b.height - headerHeightPx).coerceAtLeast(0)
        for (child in childImpls) {
            val ch = child.nativeWidget as? KdeWidgetHandle
            ch?.syncVisibilityToNative(!collapsedState && child.isVisible())
            if (!collapsedState && contentH > 0) {
                (child as? com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl<*>)?.setBounds(
                    Rect(0, headerHeightPx, w, contentH)
                )
            }
        }
    }

    override fun layoutChildrenInBounds(width: Float, height: Float) {
        layoutPanelChildren()
    }

    override fun setTitle(title: String) {
        super.setTitle(title)
        nativeWidget.requestRepaint()
    }

    override fun setCollapsed(collapsed: Boolean) {
        super.setCollapsed(collapsed)
        layoutPanelChildren()
        nativeWidget.requestRepaint()
    }

    override fun setHeaderActions(actions: List<DockHeaderAction>) {
        super.setHeaderActions(actions)
        nativeWidget.requestRepaint()
    }

    override fun onChildAddedNative(childImpl: NativeImpl<*>, index: Int) {
        super.onChildAddedNative(childImpl, index)
        (childImpl.nativeWidget as? KdeWidgetHandle)?.syncChildParentAndVisibility(nativeWidget)
        layoutPanelChildren()
    }

    override fun onChildRemovedNative(childImpl: NativeImpl<*>) {
        (childImpl.nativeWidget as? KdeWidgetHandle)?.syncChildParentAndVisibility(null)
        super.onChildRemovedNative(childImpl)
    }

    override fun onChildrenClearedNative() {
        for (c in childImpls) (c.nativeWidget as? KdeWidgetHandle)?.syncChildParentAndVisibility(null)
        super.onChildrenClearedNative()
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        if (collapsedState) return Size(maxWidth?.toInt() ?: 220, headerHeightPx)
        val childSize = layoutController?.measurePreferredSize(maxWidth, maxHeight) ?: Size(220, 140)
        return Size(childSize.width.coerceAtLeast(180), childSize.height + headerHeightPx)
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        nativeWidget.applyBoundsToNative(rect)
        layoutPanelChildren()
        nativeWidget.requestRepaint()
    }

    override fun onNativeVisibilityChanged(visible: Boolean) { nativeWidget.syncVisibilityToNative(visible) }
    override fun onNativeEnabledChanged(enabled: Boolean) { nativeWidget.qWidgetPtr?.let { KdeDynamicBindings.setWidgetEnabled(it, enabled) } }
    override fun onNativeOpacityChanged(opacity: Float) { nativeWidget.requestRepaint() }
    override fun onNativeTooltipChanged(tooltip: String?) { KdePainterSupport.applyCommonWidgetProperties(this, nativeWidget) }
    override fun onNativeCursorChanged(cursor: CssCursorValue?) { KdePainterSupport.applyCommonWidgetProperties(this, nativeWidget) }
    override fun onNativePointerEventsChanged(pointerEventsNone: Boolean) { KdePainterSupport.applyCommonWidgetProperties(this, nativeWidget) }
    override fun onNativeZIndexChanged(zIndex: Int) { nativeWidget.qWidgetPtr?.let { KdeDynamicBindings.raiseWidget(it) } }
    override fun onNativeTransformChanged(transform: CssTransform?) { nativeWidget.requestRepaint() }
    override fun onNativeStyleSynced() { KdePainterSupport.applyCommonWidgetProperties(this, nativeWidget); nativeWidget.requestRepaint() }

    override fun dispose() {
        super.dispose()
        nativeWidget.destroyQWidget()
    }
}
