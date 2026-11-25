@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.desktop.widget.DesktopSelectNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.threading.GenericUiThreading

/**
 * Retained KDE dropdown / combo-box implementation backed by Qt 6 **`QComboBox`**, switching
 * `setEditable(true)` cleanly when `isComboBoxVariant()` holds.
 */
open class KdeSelectNativeImpl(
    nativeWidget: KdeWidgetHandle = KdeWidgetHandle("QComboBox")
) : DesktopSelectNativeImpl<KdeWidgetHandle>(nativeWidget) {

    private var suppressSignalSync = false

    init {
        KdeDynamicBindings.ensureQApplication()
        val ptr = KdeDynamicBindings.createQComboBox(null)
        nativeWidget.attachToQWidget(ptr, "QComboBox", customPainted = false)
        KdeVTableBridge.connectSignal(ptr, "currentIndexChanged(int)") { args ->
            if (!suppressSignalSync) {
                val idx = KdeVTableBridge.readSignalArgInt(args, 1) ?: -1
                val optVal = optionsList.getOrNull(idx)?.first ?: return@connectSignal
                selectedVal = optVal
                nativeWidget.attributes["selectedValue"] = optVal
                GenericUiThreading.runOnLogic {
                    onSelectionChangedListener?.invoke(optVal)
                }
            }
        }
        KdeVTableBridge.connectSignal(ptr, "editTextChanged(QString)") { args ->
            if (!suppressSignalSync && isComboBoxVariant()) {
                val text = KdeDynamicBindings.readBorrowedQString(KdeVTableBridge.readSignalArgPtr(args, 1))
                selectedVal = text
                nativeWidget.attributes["selectedValue"] = text
                GenericUiThreading.runOnLogic {
                    onSelectionChangedListener?.invoke(text)
                }
            }
        }
        KdePainterSupport.attachStandardInputHooks(ptr, this, KdeVTableBridge.HOOK_RESIZE, dispatchLeftClickOnRelease = false)
        syncComboState()
    }

    private fun syncComboState() {
        val ptr = nativeWidget.qWidgetPtr ?: return
        if (nativeWidget.kdeDestroyed) return
        suppressSignalSync = true
        try {
            val editable = isComboBoxVariant()
            val selectedIdx = optionsList.indexOfFirst { it.first == selectedVal }
            KdeDynamicBindings.setComboBoxOptions(ptr, optionsList, editable, selectedIdx)
        } finally {
            suppressSignalSync = false
        }
    }

    override fun onVariantChanged(variant: String?) { syncComboState() }
    override fun setOptions(options: List<Pair<String, String>>) { super.setOptions(options); syncComboState() }
    override fun setSelectedValue(value: String?) { super.setSelectedValue(value); syncComboState() }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val longest = optionsList.maxOfOrNull { it.second.length } ?: 8
        val estW = (longest * 8 + 44).toFloat().coerceAtLeast(120f)
        val w = if (maxWidth != null) minOf(estW, maxWidth) else estW
        return Size(w, 34f)
    }

    override fun onNativeBoundsChanged(rect: Rect) { nativeWidget.applyBoundsToNative(rect) }
    override fun onNativeVisibilityChanged(visible: Boolean) { nativeWidget.syncVisibilityToNative(visible) }
    override fun onNativeEnabledChanged(enabled: Boolean) { nativeWidget.qWidgetPtr?.let { KdeDynamicBindings.setWidgetEnabled(it, enabled) } }
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

    override fun dispose() {
        menuCleanupAndDestroy()
        super.dispose()
    }

    private fun menuCleanupAndDestroy() {
        nativeWidget.destroyQWidget()
    }
}
