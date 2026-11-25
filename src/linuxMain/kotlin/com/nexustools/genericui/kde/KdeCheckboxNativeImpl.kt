@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.desktop.widget.DesktopCheckboxNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.style.theme.SystemThemeDetector
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.COpaquePointer
import kotlin.math.roundToInt

/**
 * Retained KDE toggle control hosting:
 * - custom-painted **`KdeSwitchWidget`** when `switchStyle == true`,
 * - Qt6 **`QRadioButton`** when `radioStyle == true`,
 * - Qt6 **`QCheckBox`** otherwise.
 */
class KdeCheckboxNativeImpl(
    nativeWidget: KdeWidgetHandle = KdeWidgetHandle("QCheckBox"),
    switchStyle: Boolean = false,
    radioStyle: Boolean = false
) : DesktopCheckboxNativeImpl<KdeWidgetHandle>(nativeWidget, switchStyle, radioStyle) {

    private var suppressSignalSync = false
    private var hovered = false

    init {
        KdeDynamicBindings.ensureQApplication()
        when {
            switchStyle -> {
                val ptr = KdeDynamicBindings.createQCheckBox(null)
                nativeWidget.attachToQWidget(ptr, "KdeSwitchWidget", customPainted = true)
                KdeVTableBridge.connectSignal(ptr, "toggled(bool)") { args ->
                    if (!suppressSignalSync) {
                        val next = KdeVTableBridge.readSignalArgBool(args, 1) ?: KdeDynamicBindings.isAbstractButtonChecked(ptr)
                        notifyToggleFromUser(next)
                    }
                }
                KdePainterSupport.attachStandardInputHooks(
                    widgetPtr = ptr,
                    impl = this,
                    hookMask = KdeVTableBridge.HOOK_PAINT or KdeVTableBridge.HOOK_RESIZE or KdeVTableBridge.HOOK_SIZE_HINT,
                    dispatchLeftClickOnRelease = true,
                    onPaint = { _, painter, w, h -> paintSwitch(painter, w, h) },
                    onSizeHint = { _ ->
                        val s = measureIntrinsicSize(null, null)
                        s.width to s.height
                    },
                    onHoverChanged = { h -> hovered = h; nativeWidget.requestRepaint() }
                )
            }
            radioStyle -> {
                val ptr = KdeDynamicBindings.createQRadioButton(null)
                nativeWidget.attachToQWidget(ptr, "QRadioButton", customPainted = false)
                KdeVTableBridge.connectSignal(ptr, "toggled(bool)") { args ->
                    if (!suppressSignalSync) {
                        val next = KdeVTableBridge.readSignalArgBool(args, 1) ?: KdeDynamicBindings.isAbstractButtonChecked(ptr)
                        notifyToggleFromUser(next)
                    }
                }
                KdePainterSupport.attachStandardInputHooks(ptr, this, KdeVTableBridge.HOOK_RESIZE, dispatchLeftClickOnRelease = false)
            }
            else -> {
                val ptr = KdeDynamicBindings.createQCheckBox(null)
                nativeWidget.attachToQWidget(ptr, "QCheckBox", customPainted = false)
                KdeVTableBridge.connectSignal(ptr, "toggled(bool)") { args ->
                    if (!suppressSignalSync) {
                        val next = KdeVTableBridge.readSignalArgBool(args, 1) ?: KdeDynamicBindings.isAbstractButtonChecked(ptr)
                        notifyToggleFromUser(next)
                    }
                }
                KdePainterSupport.attachStandardInputHooks(ptr, this, KdeVTableBridge.HOOK_RESIZE, dispatchLeftClickOnRelease = false)
            }
        }
        syncToggleToWidget()
    }

    private fun notifyToggleFromUser(nextChecked: Boolean) {
        if (switchStyle && suppressSignalSync) return
        checkedState = nextChecked
        nativeWidget.attributes["checked"] = nextChecked
        if (switchStyle) {
            suppressSignalSync = true
            try {
                nativeWidget.qWidgetPtr?.let { KdeDynamicBindings.setAbstractButtonChecked(it, nextChecked) }
            } finally {
                suppressSignalSync = false
            }
            nativeWidget.requestRepaint()
        }
        GenericUiThreading.runOnLogic {
            onCheckedChangedListener?.invoke(nextChecked)
        }
    }

    private fun paintSwitch(painter: COpaquePointer, w: Int, h: Int) {
        val isDark = SystemThemeDetector.resolveTheme(ThemeMode.AUTO) == ThemeMode.DARK
        val trackW = 42.0
        val trackH = 22.0
        val trackY = ((h - trackH) / 2.0).coerceAtLeast(0.0)
        val radius = trackH / 2.0

        val bType = getType()?.takeIf { it != BootstrapType.NONE } ?: BootstrapType.PRIMARY
        val activeColor = BootstrapColorPalette.resolveColor(bType, isDark)
        val inactiveTrack = if (isDark) RgbaColor(70, 76, 82) else RgbaColor(190, 196, 202)
        val trackCol = if (checkedState) activeColor else inactiveTrack

        KdeDynamicBindings.painterSave(painter)
        if (getOpacity() < 1f) KdeDynamicBindings.painterSetOpacity(painter, getOpacity())
        try {
            KdeDynamicBindings.withQPainterPath { trackPath ->
                KdeDynamicBindings.pathAddRoundedRect(trackPath, 0.0, trackY, trackW, trackH, radius, radius, radius, radius)
                KdeDynamicBindings.painterFillPath(painter, trackPath, trackCol)
                val borderCol = if (hovered) activeColor else if (isDark) RgbaColor(100, 108, 116) else RgbaColor(150, 156, 162)
                KdeDynamicBindings.painterStrokePath(painter, trackPath, borderCol, 1.0)
            }

            val thumbSize = 16.0
            val thumbY = trackY + (trackH - thumbSize) / 2.0
            val thumbX = if (checkedState) trackW - thumbSize - 3.0 else 3.0
            KdeDynamicBindings.painterDrawEllipse(
                painter,
                thumbX,
                thumbY,
                thumbSize,
                thumbSize,
                fill = RgbaColor.WHITE,
                stroke = RgbaColor(0, 0, 0, 40),
                strokeWidthPx = 1.0
            )

            if (labelText.isNotEmpty()) {
                val fg = getEffectiveTextColor() ?: if (isDark) RgbaColor(0xFC, 0xFC, 0xFC) else RgbaColor(0x23, 0x26, 0x29)
                KdeDynamicBindings.painterDrawText(
                    painter,
                    labelText,
                    trackW + 8.0,
                    0.0,
                    (w - trackW - 8.0).coerceAtLeast(0.0),
                    h.toDouble(),
                    color = fg,
                    fontSizePx = lastComputedStyle?.fontSizePx ?: 14f,
                    alignmentFlags = KdeDynamicBindings.ALIGN_LEFT or KdeDynamicBindings.ALIGN_VCENTER
                )
            }
        } finally {
            KdeDynamicBindings.painterRestore(painter)
        }
    }

    private fun syncToggleToWidget() {
        val ptr = nativeWidget.qWidgetPtr ?: return
        if (nativeWidget.kdeDestroyed) return
        suppressSignalSync = true
        try {
            if (!switchStyle) {
                KdeDynamicBindings.setAbstractButtonText(ptr, labelText)
            }
            if (!switchStyle && !radioStyle) {
                KdeDynamicBindings.setCheckBoxState(ptr, checkedState)
            } else {
                KdeDynamicBindings.setAbstractButtonChecked(ptr, checkedState)
            }
            KdeDynamicBindings.updateWidget(ptr)
        } finally {
            suppressSignalSync = false
        }
    }

    override fun setLabel(label: String) { super.setLabel(label); syncToggleToWidget() }
    override fun setChecked(checked: Boolean) { super.setChecked(checked); syncToggleToWidget() }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val baseW = if (switchStyle) 48.0 else 28.0
        val textW = if (labelText.isEmpty()) 0.0 else KdeDynamicBindings.measureTextSize(labelText, 14f).first + 8.0
        val w = (baseW + textW).toFloat().let { if (maxWidth != null) minOf(it, maxWidth) else it }
        return Size(w.coerceAtLeast(32f), 28f)
    }

    override fun onNativeBoundsChanged(rect: Rect) { nativeWidget.applyBoundsToNative(rect) }
    override fun onNativeVisibilityChanged(visible: Boolean) { nativeWidget.syncVisibilityToNative(visible) }
    override fun onNativeEnabledChanged(enabled: Boolean) { nativeWidget.qWidgetPtr?.let { KdeDynamicBindings.setWidgetEnabled(it, enabled) } }
    override fun onNativeOpacityChanged(opacity: Float) { nativeWidget.requestRepaint() }
    override fun onNativeTooltipChanged(tooltip: String?) {
        KdePainterSupport.applyCommonWidgetProperties(nativeWidget, customCursorState, nativeWidget.isSelfPointerEventsNone(), tooltip, getOpacity())
    }
    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        KdePainterSupport.applyCommonWidgetProperties(nativeWidget, cursor, nativeWidget.isSelfPointerEventsNone(), nativeWidget.tooltipText, getOpacity())
    }
    override fun onNativePointerEventsChanged(pointerEventsNone: Boolean) {
        KdePainterSupport.applyCommonWidgetProperties(nativeWidget, customCursorState, pointerEventsNone, nativeWidget.tooltipText, getOpacity())
    }
    override fun onNativeTransformChanged(transform: CssTransform?) { nativeWidget.requestRepaint() }
    override fun onNativeStyleSynced() { syncToggleToWidget() }
    override fun requestRepaintNative() { nativeWidget.requestRepaint() }

    override fun dispose() {
        nativeWidget.destroyQWidget()
        super.dispose()
    }
}
