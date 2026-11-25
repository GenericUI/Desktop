@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.widget.DesktopPopoverNativeImpl
import com.nexustools.genericui.enums.css.CssBorderStyle
import com.nexustools.genericui.enums.ui.Placement
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.linux.style.LinuxStyledSurfaceHelper
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.style.css.StyledVisualSpec
import com.nexustools.genericui.style.theme.SystemThemeDetector
import kotlinx.cinterop.COpaquePointer

/**
 * Retained KDE floating popover backed by a popup `QWidget` (`Qt::Popup`) with custom Breeze
 * surface painting, anchored positioning relative to its owner widget, and popup dismissal hooks.
 */
open class KdePopoverNativeImpl(
    nativeWidget: KdeWidgetHandle = KdeWidgetHandle("QFrame_Popup")
) : DesktopPopoverNativeImpl<KdeWidgetHandle>(nativeWidget) {

    init {
        KdeDynamicBindings.ensureQApplication()
        val ptr = KdeDynamicBindings.createQWidget(null, KdeDynamicBindings.WINDOW_POPUP or KdeDynamicBindings.WINDOW_FRAMELESS_HINT)
        KdeDynamicBindings.setWidgetAttribute(ptr, KdeDynamicBindings.WA_TRANSLUCENT_BACKGROUND, true)
        KdeDynamicBindings.setWidgetVisible(ptr, false)
        nativeWidget.isTopLevelWindow = true
        nativeWidget.attachToQWidget(ptr, "QFrame_Popup", customPainted = true)
        KdePainterSupport.attachStandardInputHooks(
            widgetPtr = ptr,
            impl = this,
            hookMask = KdeVTableBridge.HOOK_PAINT or KdeVTableBridge.HOOK_RESIZE or KdeVTableBridge.HOOK_CLOSE,
            dispatchLeftClickOnRelease = false,
            onPaint = { wPtr, painter, w, h -> paintPopover(wPtr, painter, w, h) },
            onClose = { _ ->
                hide()
                true
            }
        )
    }

    private fun paintPopover(wPtr: COpaquePointer, painter: COpaquePointer, w: Int, h: Int) {
        val isDark = SystemThemeDetector.resolveTheme(ThemeMode.AUTO) == ThemeMode.DARK
        val computed = getComputedStyle()
        val visual = (nativeWidget.attributes["styledVisuals"] as? StyledVisualSpec) ?: StyledVisualSpec(
            backgroundColor = getEffectiveBackgroundColor() ?: computed?.backgroundColor
                ?: if (isDark) RgbaColor(42, 46, 50, 248) else RgbaColor(252, 252, 252, 248),
            borderColor = computed?.borderColor ?: if (isDark) RgbaColor(82, 88, 94) else RgbaColor(198, 202, 206),
            borderWidthPx = 1f,
            borderStyle = CssBorderStyle.SOLID,
            borderRadiusPx = 8f
        )
        KdePainterSupport.paintStyledSurface(
            widgetPtr = wPtr,
            painterPtr = painter,
            widthPx = w,
            heightPx = h,
            spec = visual,
            radii = computed?.borderRadius,
            focused = false,
            opacity = getOpacity(),
            isPressed = false,
            isHovered = false
        )
    }

    override fun showAnchored(anchor: NativeImpl<*>?, x: Int, y: Int) {
        super.showAnchored(anchor, x, y)
        val ptr = nativeWidget.qWidgetPtr ?: return
        val anchorHandle = anchor?.nativeWidget as? KdeWidgetHandle
        val pref = measureIntrinsicSize(null, null)
        val popW = nativeWidget.bounds.width.takeIf { it > 0 } ?: pref.width.coerceAtLeast(220)
        val popH = nativeWidget.bounds.height.takeIf { it > 0 } ?: pref.height.coerceAtLeast(120)

        val anchorRect = if (anchorHandle != null) {
            val anchorPtr = anchorHandle.qWidgetPtr
            val (gx, gy) = if (anchorPtr != null) KdeDynamicBindings.mapToGlobal(anchorPtr, 0, 0) else (anchorHandle.bounds.x to anchorHandle.bounds.y)
            Rect(gx + x, gy + y, anchorHandle.bounds.width.coerceAtLeast(1), anchorHandle.bounds.height.coerceAtLeast(1))
        } else {
            Rect(x, y, 1, 1)
        }
        val placed = LinuxStyledSurfaceHelper.computeAnchoredPopupRect(
            anchorBounds = anchorRect,
            popupWidthPx = popW,
            popupHeightPx = popH,
            viewport = Rect(0, 0, 1920, 1080)
        )
        val adjY = when (placementState) {
            Placement.TOP -> (anchorRect.y - popH - 4).coerceAtLeast(4)
            else -> placed.y
        }
        val adjX = placed.x
        nativeWidget.bounds = Rect(adjX, adjY, popW, popH)
        KdeDynamicBindings.setWidgetGeometry(ptr, adjX, adjY, popW, popH)
        layoutChildrenInBounds(popW.toFloat(), popH.toFloat())
        KdeDynamicBindings.setWidgetVisible(ptr, true)
        KdeDynamicBindings.raiseWidget(ptr)
    }

    override fun hide() {
        super.hide()
        nativeWidget.qWidgetPtr?.let { KdeDynamicBindings.setWidgetVisible(it, false) }
    }

    override fun onChildAddedNative(childImpl: NativeImpl<*>, index: Int) {
        super.onChildAddedNative(childImpl, index)
        (childImpl.nativeWidget as? KdeWidgetHandle)?.syncChildParentAndVisibility(nativeWidget)
        val b = nativeWidget.bounds
        if (b.width > 0 && b.height > 0) layoutChildrenInBounds(b.width.toFloat(), b.height.toFloat())
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
        val base = layoutController?.measurePreferredSize(maxWidth, maxHeight) ?: super.measureIntrinsicSize(maxWidth, maxHeight)
        return Size(base.width.coerceAtLeast(180), base.height.coerceAtLeast(80))
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        nativeWidget.applyBoundsToNative(rect)
        layoutChildrenInBounds(rect.width.toFloat(), rect.height.toFloat())
    }

    override fun onNativeVisibilityChanged(visible: Boolean) { nativeWidget.qWidgetPtr?.let { KdeDynamicBindings.setWidgetVisible(it, visible) } }
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
