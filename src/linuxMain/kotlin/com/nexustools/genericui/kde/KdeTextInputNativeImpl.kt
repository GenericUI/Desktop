@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.desktop.widget.DesktopTextInputNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.COpaquePointer

/**
 * Retained KDE form input control selecting authentic KF6 & Qt6 native widgets by variant:
 * - `"date"`: KF6 `KDateComboBox` (fallback Qt6 `QDateEdit`)
 * - `"time"`: KF6 `KTimeComboBox` (fallback Qt6 `QTimeEdit`)
 * - `"color"`: KF6 `KColorButton` (fallback `QPushButton` + `QColorDialog`)
 * - `"number"`: Qt6 `QDoubleSpinBox`
 * - `"password"` or `passwordMode`: KF6 `KPasswordLineEdit` (fallback `QLineEdit(EchoMode.Password)`)
 * - `"textarea"` or `multiline`: Qt6 `QPlainTextEdit`
 * - `"search"` / default: Qt6 `QLineEdit`
 */
open class KdeTextInputNativeImpl(
    nativeWidget: KdeWidgetHandle = KdeWidgetHandle("QLineEdit"),
    multiline: Boolean = false
) : DesktopTextInputNativeImpl<KdeWidgetHandle>(nativeWidget, multiline) {

    private var activeVariantKey: String = ""
    private var innerPasswordLineEditPtr: COpaquePointer? = null
    private var suppressSignalSync = false

    init {
        KdeDynamicBindings.ensureQApplication()
        nativeWidget.attributes["isTextInputComponent"] = true
        rebuildControlForCurrentVariant(forceCreate = true)
    }

    private fun resolveVariantKey(): String {
        val v = resolveControlVariant()?.trim()?.lowercase()
        return when {
            v == "date" -> "date"
            v == "time" -> "time"
            v == "color" -> "color"
            v == "number" -> "number"
            v == "textarea" || multilineState -> "textarea"
            v == "password" || passwordModeState -> "password"
            v == "search" -> "search"
            else -> "text"
        }
    }

    private fun rebuildControlForCurrentVariant(forceCreate: Boolean = false) {
        val targetVariant = resolveVariantKey()
        if (!forceCreate && targetVariant == activeVariantKey && nativeWidget.qWidgetPtr != null) {
            syncValueAndPlaceholderToLiveWidget()
            return
        }
        activeVariantKey = targetVariant
        innerPasswordLineEditPtr = null
        val parentPtr = nativeWidget.desiredParentPtr

        when (targetVariant) {
            "date" -> {
                val kf = KdeDynamicBindings.createKDateComboBox(parentPtr)
                val ptr = kf ?: KdeDynamicBindings.createQDateEdit(parentPtr)
                val typeName = if (kf != null) "KDateComboBox" else "QDateEdit"
                KdeVTableBridge.connectFirstSignal(ptr, "dateChanged(QDate)", "dateEdited(QDate)") { args ->
                    if (!suppressSignalSync) {
                        val ymd = KdeDynamicBindings.readQDateFields(KdeVTableBridge.readSignalArgPtr(args, 1))
                        if (ymd != null) {
                            val iso = "${ymd.first.toString().padStart(4, '0')}-${ymd.second.toString().padStart(2, '0')}-${ymd.third.toString().padStart(2, '0')}"
                            emitUserValueChange(iso)
                        }
                    }
                }
                KdePainterSupport.attachStandardInputHooks(ptr, this, KdeVTableBridge.HOOK_RESIZE, dispatchLeftClickOnRelease = false)
                nativeWidget.swapQWidget(ptr, typeName, customPainted = false)
            }
            "time" -> {
                val kf = KdeDynamicBindings.createKTimeComboBox(parentPtr)
                val ptr = kf ?: KdeDynamicBindings.createQTimeEdit(parentPtr)
                val typeName = if (kf != null) "KTimeComboBox" else "QTimeEdit"
                KdeVTableBridge.connectFirstSignal(ptr, "timeChanged(QTime)", "timeEdited(QTime)") { args ->
                    if (!suppressSignalSync) {
                        val hms = KdeDynamicBindings.readQTimeFields(KdeVTableBridge.readSignalArgPtr(args, 1))
                        if (hms != null) {
                            val iso = "${hms.first.toString().padStart(2, '0')}:${hms.second.toString().padStart(2, '0')}"
                            emitUserValueChange(iso)
                        }
                    }
                }
                KdePainterSupport.attachStandardInputHooks(ptr, this, KdeVTableBridge.HOOK_RESIZE, dispatchLeftClickOnRelease = false)
                nativeWidget.swapQWidget(ptr, typeName, customPainted = false)
            }
            "color" -> {
                val kf = KdeDynamicBindings.createKColorButton(parentPtr)
                if (kf != null) {
                    KdeVTableBridge.connectSignal(kf, "changed(QColor)") { args ->
                        if (!suppressSignalSync) {
                            KdeDynamicBindings.readQColorRgba(KdeVTableBridge.readSignalArgPtr(args, 1))?.let { col ->
                                emitUserValueChange(col.toHex())
                            }
                        }
                    }
                    KdePainterSupport.attachStandardInputHooks(kf, this, KdeVTableBridge.HOOK_RESIZE, dispatchLeftClickOnRelease = false)
                    nativeWidget.swapQWidget(kf, "KColorButton", customPainted = false)
                } else {
                    val btn = KdeDynamicBindings.createQPushButton(parentPtr)
                    KdeVTableBridge.connectSignal(btn, "clicked(bool)") {
                        val cur = parseRgbaHex(textValue) ?: RgbaColor.PRIMARY_BLUE
                        val dlg = KdeDynamicBindings.createQColorDialog(btn, cur)
                        var picked: RgbaColor? = null
                        KdeVTableBridge.connectSignal(dlg, "colorSelected(QColor)") { args ->
                            picked = KdeDynamicBindings.readQColorRgba(KdeVTableBridge.readSignalArgPtr(args, 1))
                        }
                        val ok = KdeDynamicBindings.execDialog(dlg) == 1
                        KdeVTableBridge.destroyObject(dlg)
                        if (ok && picked != null) {
                            emitUserValueChange(picked!!.toHex())
                        }
                    }
                    KdePainterSupport.attachStandardInputHooks(btn, this, KdeVTableBridge.HOOK_RESIZE, dispatchLeftClickOnRelease = false)
                    nativeWidget.swapQWidget(btn, "QPushButton_ColorPicker", customPainted = false)
                }
            }
            "number" -> {
                val ptr = KdeDynamicBindings.createQDoubleSpinBox(parentPtr)
                KdeVTableBridge.connectSignal(ptr, "valueChanged(double)") { args ->
                    if (!suppressSignalSync) {
                        val num = KdeVTableBridge.readSignalArgDouble(args, 1)
                            ?: KdeDynamicBindings.getDoubleSpinBoxValue(ptr)
                        emitUserValueChange(num.toString())
                    }
                }
                KdePainterSupport.attachStandardInputHooks(ptr, this, KdeVTableBridge.HOOK_RESIZE, dispatchLeftClickOnRelease = false)
                nativeWidget.swapQWidget(ptr, "QDoubleSpinBox", customPainted = false)
            }
            "password" -> {
                val kf = KdeDynamicBindings.createKPasswordLineEdit(parentPtr)
                if (kf != null) {
                    val inner = KdeDynamicBindings.kPasswordInnerLineEdit(kf)
                    innerPasswordLineEditPtr = inner
                    KdeVTableBridge.connectSignal(kf, "passwordChanged(QString)") { args ->
                        if (!suppressSignalSync) {
                            val s = KdeDynamicBindings.readBorrowedQString(KdeVTableBridge.readSignalArgPtr(args, 1))
                            emitUserValueChange(s)
                        }
                    }
                    if (inner != null) {
                        KdeVTableBridge.connectSignal(inner, "returnPressed()") {
                            GenericUiThreading.runOnLogic { onSubmitListener?.invoke(textValue) }
                        }
                    }
                    KdePainterSupport.attachStandardInputHooks(kf, this, KdeVTableBridge.HOOK_RESIZE, dispatchLeftClickOnRelease = false)
                    nativeWidget.swapQWidget(kf, "KPasswordLineEdit", customPainted = false)
                } else {
                    val ptr = KdeDynamicBindings.createQLineEdit(parentPtr)
                    connectLineEditSignals(ptr)
                    KdePainterSupport.attachStandardInputHooks(ptr, this, KdeVTableBridge.HOOK_RESIZE, dispatchLeftClickOnRelease = false)
                    nativeWidget.swapQWidget(ptr, "QLineEdit", customPainted = false)
                }
            }
            "textarea" -> {
                val ptr = KdeDynamicBindings.createQPlainTextEdit(parentPtr)
                KdeVTableBridge.connectSignal(ptr, "textChanged()") {
                    if (!suppressSignalSync) {
                        emitUserValueChange(KdeDynamicBindings.readPlainTextEditText(ptr))
                    }
                }
                KdePainterSupport.attachStandardInputHooks(ptr, this, KdeVTableBridge.HOOK_RESIZE, dispatchLeftClickOnRelease = false)
                nativeWidget.swapQWidget(ptr, "QPlainTextEdit", customPainted = false)
            }
            else -> {
                val ptr = KdeDynamicBindings.createQLineEdit(parentPtr)
                connectLineEditSignals(ptr)
                KdePainterSupport.attachStandardInputHooks(ptr, this, KdeVTableBridge.HOOK_RESIZE, dispatchLeftClickOnRelease = false)
                nativeWidget.swapQWidget(ptr, "QLineEdit", customPainted = false)
            }
        }

        syncValueAndPlaceholderToLiveWidget()
    }

    private fun connectLineEditSignals(ptr: COpaquePointer) {
        KdeVTableBridge.connectSignal(ptr, "textEdited(QString)") { args ->
            if (!suppressSignalSync) {
                val s = KdeDynamicBindings.readBorrowedQString(KdeVTableBridge.readSignalArgPtr(args, 1))
                emitUserValueChange(s)
            }
        }
        KdeVTableBridge.connectSignal(ptr, "returnPressed()") {
            GenericUiThreading.runOnLogic { onSubmitListener?.invoke(textValue) }
        }
    }

    private fun emitUserValueChange(newText: String) {
        textValue = newText
        nativeWidget.attributes["value"] = newText
        GenericUiThreading.runOnLogic {
            onValueChangedListener?.invoke(newText)
        }
    }

    private fun syncValueAndPlaceholderToLiveWidget() {
        val ptr = nativeWidget.qWidgetPtr ?: return
        if (nativeWidget.kdeDestroyed) return
        suppressSignalSync = true
        try {
            when (activeVariantKey) {
                "date" -> {
                    val parts = textValue.split('-').mapNotNull { it.trim().toIntOrNull() }
                    val y = parts.getOrNull(0) ?: 2026
                    val m = (parts.getOrNull(1) ?: 1).coerceIn(1, 12)
                    val d = (parts.getOrNull(2) ?: 1).coerceIn(1, 31)
                    if (nativeWidget.activeKdeWidgetType == "KDateComboBox") {
                        KdeDynamicBindings.setKDateComboDate(ptr, y, m, d)
                    } else {
                        KdeDynamicBindings.setDateEditDate(ptr, y, m, d)
                    }
                }
                "time" -> {
                    val parts = textValue.split(':').mapNotNull { it.trim().toIntOrNull() }
                    val hh = (parts.getOrNull(0) ?: 12).coerceIn(0, 23)
                    val mm = (parts.getOrNull(1) ?: 0).coerceIn(0, 59)
                    val ss = (parts.getOrNull(2) ?: 0).coerceIn(0, 59)
                    if (nativeWidget.activeKdeWidgetType == "KTimeComboBox") {
                        KdeDynamicBindings.setKTimeComboTime(ptr, hh, mm, ss)
                    } else {
                        KdeDynamicBindings.setTimeEditTime(ptr, hh, mm, ss)
                    }
                }
                "color" -> {
                    val col = parseRgbaHex(textValue) ?: RgbaColor.PRIMARY_BLUE
                    if (nativeWidget.activeKdeWidgetType == "KColorButton") {
                        KdeDynamicBindings.setKColorButtonColor(ptr, col)
                    } else {
                        KdeDynamicBindings.setAbstractButtonText(ptr, textValue.ifBlank { col.toHex() })
                    }
                }
                "number" -> {
                    val minV = (nativeWidget.attributes["minValue"] as? Number)?.toDouble() ?: -1e9
                    val maxV = (nativeWidget.attributes["maxValue"] as? Number)?.toDouble() ?: 1e9
                    val stepV = (nativeWidget.attributes["step"] as? Number)?.toDouble() ?: 1.0
                    val num = textValue.toDoubleOrNull() ?: 0.0
                    KdeDynamicBindings.setDoubleSpinBoxState(ptr, minV, maxV, stepV, num)
                }
                "password" -> {
                    val targetLe = innerPasswordLineEditPtr ?: ptr
                    KdeDynamicBindings.setLineEditState(
                        targetLe,
                        textValue,
                        placeholderText,
                        readOnlyState,
                        password = (innerPasswordLineEditPtr == null)
                    )
                }
                "textarea" -> {
                    KdeDynamicBindings.setPlainTextEditState(ptr, textValue, placeholderText, readOnlyState)
                }
                else -> {
                    KdeDynamicBindings.setLineEditState(ptr, textValue, placeholderText, readOnlyState, password = passwordModeState)
                }
            }
        } finally {
            suppressSignalSync = false
        }
    }

    private fun parseRgbaHex(hex: String): RgbaColor? {
        val clean = hex.trim().removePrefix("#")
        if (clean.length != 6 && clean.length != 8) return null
        val r = clean.substring(0, 2).toIntOrNull(16) ?: return null
        val g = clean.substring(2, 4).toIntOrNull(16) ?: return null
        val b = clean.substring(4, 6).toIntOrNull(16) ?: return null
        val a = if (clean.length == 8) clean.substring(6, 8).toIntOrNull(16) ?: 255 else 255
        return RgbaColor(r, g, b, a)
    }

    override fun onVariantChanged(variant: String?) { rebuildControlForCurrentVariant(forceCreate = false) }
    override fun setValue(value: String) { super.setValue(value); syncValueAndPlaceholderToLiveWidget() }
    override fun setPlaceholder(placeholder: String) { super.setPlaceholder(placeholder); syncValueAndPlaceholderToLiveWidget() }
    override fun setReadOnly(readOnly: Boolean) { super.setReadOnly(readOnly); syncValueAndPlaceholderToLiveWidget() }
    override fun setPasswordMode(password: Boolean) { super.setPasswordMode(password); rebuildControlForCurrentVariant(forceCreate = false) }
    override fun setMultiline(multiline: Boolean) { super.setMultiline(multiline); rebuildControlForCurrentVariant(forceCreate = false) }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val h = if (activeVariantKey == "textarea") 96f else 34f
        val w = if (maxWidth != null) minOf(220f, maxWidth) else 220f
        return Size(w, h)
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
    override fun onNativeStyleSynced() { syncValueAndPlaceholderToLiveWidget() }

    override fun dispose() {
        nativeWidget.destroyQWidget()
        super.dispose()
    }
}
