@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.impl

import com.nexustools.genericui.desktop.widget.DesktopTextInputNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.gtk.widget.GtkCustomLayoutManager
import com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge
import com.nexustools.genericui.linux.LinuxGlibGioBindings
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Retained GTK4 text/form input implementation switching dynamically across `GtkEntry`,
 * `GtkPasswordEntry`, `GtkSearchEntry`, `GtkSpinButton` (`number`), `GtkMenuButton+GtkCalendar`
 * (`date`), `GtkMenuButton+GtkSpinButton` (`time`), `GtkColorDialogButton` (`color`), and
 * `GtkScrolledWindow+GtkTextView` (multiline).
 */
class GtkTextInputNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkEntry"),
    multiline: Boolean = false,
    classPrefix: String = if (multiline) "textarea" else "input"
) : DesktopTextInputNativeImpl<GtkWidgetHandle>(nativeWidget, multiline) {

    private enum class InputKind {
        ENTRY, PASSWORD, SEARCH, NUMBER, DATE, TIME, COLOR, MULTILINE
    }

    private var activeKind: InputKind = resolveDesiredKind()
    private var syncingFromModel = false
    private var signalRef: StableRef<GtkTextInputNativeImpl>? = null
    private var textBufferPtr: COpaquePointer? = null
    private var calendarPtr: COpaquePointer? = null
    private var timeHourSpinPtr: COpaquePointer? = null
    private var timeMinuteSpinPtr: COpaquePointer? = null

    init {
        nativeWidget.ownerImpl = this
        syncClassMetadata(activeKind)
        nativeWidget.widgetMaterializer = { materializeInput() }
    }

    private fun resolveDesiredKind(): InputKind {
        if (isMultiline()) return InputKind.MULTILINE
        if (isPasswordMode()) return InputKind.PASSWORD
        return when (resolveControlVariant()) {
            "password" -> InputKind.PASSWORD
            "search" -> InputKind.SEARCH
            "number" -> InputKind.NUMBER
            "date" -> InputKind.DATE
            "time" -> InputKind.TIME
            "color" -> InputKind.COLOR
            else -> InputKind.ENTRY
        }
    }

    private fun syncClassMetadata(kind: InputKind) {
        val name = when (kind) {
            InputKind.MULTILINE -> "GtkScrolledWindow_GtkTextView"
            InputKind.PASSWORD -> "GtkPasswordEntry"
            InputKind.SEARCH -> "GtkSearchEntry"
            InputKind.NUMBER -> "GtkSpinButton"
            InputKind.DATE -> "GtkMenuButton_GtkCalendar"
            InputKind.TIME -> "GtkMenuButton_GtkSpinButton"
            InputKind.COLOR -> "GtkColorDialogButton"
            InputKind.ENTRY -> "GtkEntry"
        }
        nativeWidget.updateActiveGtkClassName(name)
    }

    private fun materializeInput(): COpaquePointer? {
        nativeWidget.realGtkWidgetPtr?.let { return it }
        if (!Gtk4DynamicBindings.ensureGtkInitialized()) return null

        val kind = resolveDesiredKind()
        activeKind = kind
        syncClassMetadata(kind)
        signalRef?.dispose()
        val ref = StableRef.create(this)
        signalRef = ref
        textBufferPtr = null
        calendarPtr = null
        timeHourSpinPtr = null
        timeMinuteSpinPtr = null

        var rootPtr: COpaquePointer? = null
        var focusPtr: COpaquePointer? = null

        when (kind) {
            InputKind.MULTILINE -> {
                val sw = Gtk4DynamicBindings.gtkScrolledWindowNew() ?: return null
                val tv = Gtk4DynamicBindings.gtkTextViewNew() ?: return null
                Gtk4DynamicBindings.gtkScrolledWindowSetPolicy(
                    sw,
                    Gtk4DynamicBindings.GTK_POLICY_AUTOMATIC,
                    Gtk4DynamicBindings.GTK_POLICY_AUTOMATIC
                )
                Gtk4DynamicBindings.gtkScrolledWindowSetChild(sw, tv)
                Gtk4DynamicBindings.gtkTextViewSetWrapMode(tv, Gtk4DynamicBindings.GTK_WRAP_WORD_CHAR)
                Gtk4DynamicBindings.gtkTextViewSetEditable(tv, !isReadOnly())
                val buf = Gtk4DynamicBindings.gtkTextViewGetBuffer(tv)
                textBufferPtr = buf
                if (buf != null) {
                    syncingFromModel = true
                    try {
                        Gtk4DynamicBindings.gtkTextBufferSetText(buf, textValue)
                    } finally {
                        syncingFromModel = false
                    }
                    LinuxGlibGioBindings.signalConnect(
                        instance = buf,
                        signalName = "changed",
                        handler = textBufferChangedCb.reinterpret(),
                        userData = ref.asCPointer()
                    )
                }
                nativeWidget.scrolledWindowPtr = sw
                nativeWidget.primaryLeafPtr = tv
                rootPtr = sw
                focusPtr = tv
            }

            InputKind.PASSWORD -> {
                val pe = Gtk4DynamicBindings.gtkPasswordEntryNew() ?: Gtk4DynamicBindings.gtkEntryNew()?.also {
                    Gtk4DynamicBindings.gtkEntrySetVisibility(it, false)
                } ?: return null
                Gtk4DynamicBindings.gtkPasswordEntrySetShowPeekIcon(pe, true)
                wireEditableCommon(pe, ref)
                rootPtr = pe
                focusPtr = pe
            }

            InputKind.SEARCH -> {
                val se = Gtk4DynamicBindings.gtkSearchEntryNew() ?: Gtk4DynamicBindings.gtkEntryNew() ?: return null
                wireEditableCommon(se, ref, searchSignal = true)
                rootPtr = se
                focusPtr = se
            }

            InputKind.NUMBER -> {
                val min = (nativeWidget.attributes["min"] as? String)?.toDoubleOrNull() ?: -1_000_000.0
                val max = (nativeWidget.attributes["max"] as? String)?.toDoubleOrNull() ?: 1_000_000.0
                val step = (nativeWidget.attributes["step"] as? String)?.toDoubleOrNull() ?: 1.0
                val sb = Gtk4DynamicBindings.gtkSpinButtonNewWithRange(min, max, step) ?: Gtk4DynamicBindings.gtkEntryNew() ?: return null
                syncingFromModel = true
                try {
                    Gtk4DynamicBindings.gtkSpinButtonSetValue(sb, textValue.toDoubleOrNull() ?: 0.0)
                    Gtk4DynamicBindings.gtkEditableSetEditable(sb, !isReadOnly())
                } finally {
                    syncingFromModel = false
                }
                LinuxGlibGioBindings.signalConnect(
                    instance = sb,
                    signalName = "value-changed",
                    handler = spinValueChangedCb.reinterpret(),
                    userData = ref.asCPointer()
                )
                nativeWidget.primaryLeafPtr = sb
                rootPtr = sb
                focusPtr = sb
            }

            InputKind.DATE -> {
                val mb = Gtk4DynamicBindings.gtkMenuButtonNew() ?: return null
                Gtk4DynamicBindings.gtkMenuButtonSetLabel(
                    mb,
                    textValue.ifEmpty { placeholderText.ifEmpty { "Select Date" } }
                )
                val pop = Gtk4DynamicBindings.gtkPopoverNew()
                val cal = Gtk4DynamicBindings.gtkCalendarNew()
                if (pop != null && cal != null) {
                    applyYmdToCalendar(cal, textValue)
                    LinuxGlibGioBindings.signalConnect(
                        instance = cal,
                        signalName = "day-selected",
                        handler = calendarDaySelectedCb.reinterpret(),
                        userData = ref.asCPointer()
                    )
                    Gtk4DynamicBindings.gtkPopoverSetChild(pop, cal)
                    Gtk4DynamicBindings.gtkMenuButtonSetPopover(mb, pop)
                    calendarPtr = cal
                    nativeWidget.popoverPtr = pop
                }
                nativeWidget.primaryLeafPtr = mb
                rootPtr = mb
                focusPtr = mb
            }

            InputKind.TIME -> {
                val mb = Gtk4DynamicBindings.gtkMenuButtonNew() ?: return null
                Gtk4DynamicBindings.gtkMenuButtonSetLabel(
                    mb,
                    textValue.ifEmpty { placeholderText.ifEmpty { "00:00" } }
                )
                val pop = Gtk4DynamicBindings.gtkPopoverNew()
                val hBox = Gtk4DynamicBindings.gtkBoxNew(Gtk4DynamicBindings.GTK_ORIENTATION_HORIZONTAL, 6)
                val hourSpin = Gtk4DynamicBindings.gtkSpinButtonNewWithRange(0.0, 23.0, 1.0)
                val minSpin = Gtk4DynamicBindings.gtkSpinButtonNewWithRange(0.0, 59.0, 1.0)
                if (pop != null && hBox != null && hourSpin != null && minSpin != null) {
                    Gtk4DynamicBindings.gtkWidgetSetMargins(hBox, 8, 8, 8, 8)
                    val (hVal, mVal) = parseTimeHhMm(textValue)
                    syncingFromModel = true
                    try {
                        Gtk4DynamicBindings.gtkSpinButtonSetValue(hourSpin, hVal.toDouble())
                        Gtk4DynamicBindings.gtkSpinButtonSetValue(minSpin, mVal.toDouble())
                    } finally {
                        syncingFromModel = false
                    }
                    LinuxGlibGioBindings.signalConnect(hourSpin, "value-changed", timeSpinChangedCb.reinterpret(), ref.asCPointer())
                    LinuxGlibGioBindings.signalConnect(minSpin, "value-changed", timeSpinChangedCb.reinterpret(), ref.asCPointer())
                    Gtk4DynamicBindings.gtkBoxAppend(hBox, hourSpin)
                    Gtk4DynamicBindings.gtkLabelNew(":")?.let { Gtk4DynamicBindings.gtkBoxAppend(hBox, it) }
                    Gtk4DynamicBindings.gtkBoxAppend(hBox, minSpin)
                    Gtk4DynamicBindings.gtkPopoverSetChild(pop, hBox)
                    Gtk4DynamicBindings.gtkMenuButtonSetPopover(mb, pop)
                    timeHourSpinPtr = hourSpin
                    timeMinuteSpinPtr = minSpin
                    nativeWidget.popoverPtr = pop
                }
                nativeWidget.primaryLeafPtr = mb
                rootPtr = mb
                focusPtr = mb
            }

            InputKind.COLOR -> {
                val dlg = Gtk4DynamicBindings.gtkColorDialogNew()
                val colorBtn = Gtk4DynamicBindings.gtkColorDialogButtonNew(dlg)
                if (colorBtn != null) {
                    parseRgbaColor(textValue)?.let { c ->
                        val argb = (c.a shl 24) or (c.r shl 16) or (c.g shl 8) or c.b
                        syncingFromModel = true
                        try {
                            Gtk4DynamicBindings.gtkColorDialogButtonSetArgb(colorBtn, argb)
                        } finally {
                            syncingFromModel = false
                        }
                    }
                    LinuxGlibGioBindings.signalConnect(
                        instance = colorBtn,
                        signalName = "notify::rgba",
                        handler = colorChangedCb.reinterpret(),
                        userData = ref.asCPointer()
                    )
                    nativeWidget.primaryLeafPtr = colorBtn
                    rootPtr = colorBtn
                    focusPtr = colorBtn
                } else {
                    val fallbackEntry = Gtk4DynamicBindings.gtkEntryNew() ?: return null
                    wireEditableCommon(fallbackEntry, ref)
                    rootPtr = fallbackEntry
                    focusPtr = fallbackEntry
                }
            }

            InputKind.ENTRY -> {
                val entry = Gtk4DynamicBindings.gtkEntryNew() ?: return null
                wireEditableCommon(entry, ref)
                rootPtr = entry
                focusPtr = entry
            }
        }

        val resolvedRoot = rootPtr ?: return null
        GtkWidgetLifecycleBridge.attachControllersAndState(
            impl = this,
            rootPtr = resolvedRoot,
            focusTargetPtr = focusPtr ?: resolvedRoot,
            isTextInput = true,
            dispatchClickOnPrimaryRelease = false,
            attachClickGesture = true
        )
        return resolvedRoot
    }

    private fun wireEditableCommon(
        editablePtr: COpaquePointer,
        ref: StableRef<GtkTextInputNativeImpl>,
        searchSignal: Boolean = false
    ) {
        syncingFromModel = true
        try {
            Gtk4DynamicBindings.gtkEditableSetText(editablePtr, textValue)
            Gtk4DynamicBindings.gtkEntrySetPlaceholderText(editablePtr, placeholderText.ifEmpty { null })
            Gtk4DynamicBindings.gtkEditableSetEditable(editablePtr, !isReadOnly())
        } finally {
            syncingFromModel = false
        }
        LinuxGlibGioBindings.signalConnect(
            instance = editablePtr,
            signalName = if (searchSignal) "search-changed" else "changed",
            handler = editableChangedCb.reinterpret(),
            userData = ref.asCPointer()
        )
        LinuxGlibGioBindings.signalConnect(
            instance = editablePtr,
            signalName = "activate",
            handler = editableActivateCb.reinterpret(),
            userData = ref.asCPointer()
        )
        nativeWidget.primaryLeafPtr = editablePtr
    }

    private fun checkRebuildForVariantChange() {
        val desired = resolveDesiredKind()
        if (desired == activeKind) return
        activeKind = desired
        syncClassMetadata(desired)
        val oldPtr = nativeWidget.realGtkWidgetPtr ?: return
        nativeWidget.realGtkWidgetPtr = null
        val newPtr = materializeInput()
        if (newPtr != null) {
            GtkWidgetLifecycleBridge.swapLiveWidgetInParent(this, oldPtr, newPtr)
        }
    }

    override fun onVariantChanged(variant: String?) = checkRebuildForVariantChange()

    override fun setPasswordMode(password: Boolean) {
        super.setPasswordMode(password)
        checkRebuildForVariantChange()
    }

    override fun setMultiline(multiline: Boolean) {
        super.setMultiline(multiline)
        checkRebuildForVariantChange()
    }

    override fun setValue(value: String) {
        super.setValue(value)
        val root = nativeWidget.realGtkWidgetPtr ?: return
        syncingFromModel = true
        try {
            when (activeKind) {
                InputKind.MULTILINE -> textBufferPtr?.let { Gtk4DynamicBindings.gtkTextBufferSetText(it, value) }
                InputKind.NUMBER -> Gtk4DynamicBindings.gtkSpinButtonSetValue(root, value.toDoubleOrNull() ?: 0.0)
                InputKind.DATE -> {
                    Gtk4DynamicBindings.gtkMenuButtonSetLabel(
                        root,
                        value.ifEmpty { placeholderText.ifEmpty { "Select Date" } }
                    )
                    calendarPtr?.let { applyYmdToCalendar(it, value) }
                }
                InputKind.TIME -> {
                    Gtk4DynamicBindings.gtkMenuButtonSetLabel(
                        root,
                        value.ifEmpty { placeholderText.ifEmpty { "00:00" } }
                    )
                    val (hh, mm) = parseTimeHhMm(value)
                    timeHourSpinPtr?.let { Gtk4DynamicBindings.gtkSpinButtonSetValue(it, hh.toDouble()) }
                    timeMinuteSpinPtr?.let { Gtk4DynamicBindings.gtkSpinButtonSetValue(it, mm.toDouble()) }
                }
                InputKind.COLOR -> {
                    parseRgbaColor(value)?.let { c ->
                        val argb = (c.a shl 24) or (c.r shl 16) or (c.g shl 8) or c.b
                        Gtk4DynamicBindings.gtkColorDialogButtonSetArgb(root, argb)
                    }
                }
                else -> {
                    val leaf = nativeWidget.primaryLeafPtr ?: root
                    if (Gtk4DynamicBindings.gtkEditableGetText(leaf) != value) {
                        Gtk4DynamicBindings.gtkEditableSetText(leaf, value)
                    }
                }
            }
        } finally {
            syncingFromModel = false
        }
    }

    override fun setPlaceholder(placeholder: String) {
        super.setPlaceholder(placeholder)
        val ptr = nativeWidget.realGtkWidgetPtr ?: return
        if (activeKind == InputKind.ENTRY || activeKind == InputKind.PASSWORD || activeKind == InputKind.SEARCH) {
            Gtk4DynamicBindings.gtkEntrySetPlaceholderText(nativeWidget.primaryLeafPtr ?: ptr, placeholder.ifEmpty { null })
        }
    }

    override fun setReadOnly(readOnly: Boolean) {
        super.setReadOnly(readOnly)
        val ptr = nativeWidget.realGtkWidgetPtr ?: return
        when (activeKind) {
            InputKind.MULTILINE -> nativeWidget.primaryLeafPtr?.let { Gtk4DynamicBindings.gtkTextViewSetEditable(it, !readOnly) }
            InputKind.ENTRY, InputKind.PASSWORD, InputKind.SEARCH, InputKind.NUMBER ->
                Gtk4DynamicBindings.gtkEditableSetEditable(nativeWidget.primaryLeafPtr ?: ptr, !readOnly)
            else -> {}
        }
    }

    override fun setAttribute(name: String, value: String?) {
        super.setAttribute(name, value)
        checkRebuildForVariantChange()
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val ptr = nativeWidget.realGtkWidgetPtr
        if (ptr != null) {
            val (w, h) = GtkCustomLayoutManager.measureGtkWidgetIntrinsic(ptr, maxWidth, maxHeight)
            val fallbackH = if (isMultiline()) max(h, 88) else max(h, 34)
            if (w > 0) return Size(max(w, 120), fallbackH)
        }
        return if (isMultiline()) Size(240, 96) else Size(180, 36)
    }

    override fun onNativeBoundsChanged(rect: Rect) = GtkWidgetLifecycleBridge.onBoundsChanged(this, rect)
    override fun onNativeVisibilityChanged(visible: Boolean) = GtkWidgetLifecycleBridge.onVisibilityChanged(this, visible)
    override fun onNativeEnabledChanged(enabled: Boolean) = GtkWidgetLifecycleBridge.onEnabledChanged(this, enabled)
    override fun onNativeOpacityChanged(opacity: Float) = GtkWidgetLifecycleBridge.onOpacityChanged(this, opacity)
    override fun onNativeTooltipChanged(tooltip: String?) = GtkWidgetLifecycleBridge.onTooltipChanged(this, tooltip)
    override fun onNativeCursorChanged(cursor: CssCursorValue?) = GtkWidgetLifecycleBridge.onCursorChanged(this, cursor)
    override fun onNativePointerEventsChanged(pointerEventsNone: Boolean) = GtkWidgetLifecycleBridge.onPointerEventsChanged(this, pointerEventsNone)
    override fun onNativeZIndexChanged(zIndex: Int) = GtkWidgetLifecycleBridge.onZIndexChanged(this, zIndex)
    override fun onNativeTransformChanged(transform: CssTransform?) = GtkWidgetLifecycleBridge.onTransformChanged(this, transform)
    override fun onNativeStyleSynced() = GtkWidgetLifecycleBridge.onStyleSynced(this)

    override fun setFocusedState(focused: Boolean) {
        super.setFocusedState(focused)
        GtkWidgetLifecycleBridge.syncNativeFocus(this, isFocusedState())
    }

    override fun dispose() {
        signalRef?.dispose()
        signalRef = null
        super.dispose()
    }

    private fun applyYmdToCalendar(cal: COpaquePointer, raw: String) {
        val parts = raw.trim().split('-')
        if (parts.size == 3) {
            val y = parts[0].toIntOrNull()
            val m = parts[1].toIntOrNull()
            val d = parts[2].toIntOrNull()
            if (y != null && m != null && d != null) {
                Gtk4DynamicBindings.gtkCalendarSelectYmd(cal, y, m, d)
            }
        }
    }

    private fun parseTimeHhMm(raw: String): Pair<Int, Int> {
        val parts = raw.trim().split(':')
        val h = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 0
        val m = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        return h to m
    }

    private fun parseRgbaColor(raw: String): RgbaColor? = RgbaColor.fromCss(raw)

    private fun pushUserTextChange(newText: String) {
        if (syncingFromModel) return
        textValue = newText
        nativeWidget.attributes["value"] = newText
        onValueChangedListener?.invoke(newText)
    }

    companion object {
        private val editableChangedCb = staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { editable, userData ->
            val impl = userData?.asStableRef<GtkTextInputNativeImpl>()?.get() ?: return@staticCFunction
            if (impl.syncingFromModel || editable == null) return@staticCFunction
            impl.pushUserTextChange(Gtk4DynamicBindings.gtkEditableGetText(editable))
        }

        private val editableActivateCb = staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { _, userData ->
            val impl = userData?.asStableRef<GtkTextInputNativeImpl>()?.get() ?: return@staticCFunction
            impl.submitValue()
        }

        private val textBufferChangedCb = staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { buf, userData ->
            val impl = userData?.asStableRef<GtkTextInputNativeImpl>()?.get() ?: return@staticCFunction
            if (impl.syncingFromModel || buf == null) return@staticCFunction
            impl.pushUserTextChange(Gtk4DynamicBindings.gtkTextBufferGetText(buf))
        }

        private val spinValueChangedCb = staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { sb, userData ->
            val impl = userData?.asStableRef<GtkTextInputNativeImpl>()?.get() ?: return@staticCFunction
            if (impl.syncingFromModel || sb == null) return@staticCFunction
            val v = Gtk4DynamicBindings.gtkSpinButtonGetValue(sb)
            val str = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
            impl.pushUserTextChange(str)
        }

        private val calendarDaySelectedCb = staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { cal, userData ->
            val impl = userData?.asStableRef<GtkTextInputNativeImpl>()?.get() ?: return@staticCFunction
            if (impl.syncingFromModel || cal == null) return@staticCFunction
            val (y, m, d) = Gtk4DynamicBindings.gtkCalendarGetYmd(cal) ?: return@staticCFunction
            val mm = m.toString().padStart(2, '0')
            val dd = d.toString().padStart(2, '0')
            val formatted = "$y-$mm-$dd"
            impl.nativeWidget.realGtkWidgetPtr?.let { mb -> Gtk4DynamicBindings.gtkMenuButtonSetLabel(mb, formatted) }
            impl.nativeWidget.popoverPtr?.let { Gtk4DynamicBindings.gtkPopoverPopdown(it) }
            impl.pushUserTextChange(formatted)
        }

        private val timeSpinChangedCb = staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { _, userData ->
            val impl = userData?.asStableRef<GtkTextInputNativeImpl>()?.get() ?: return@staticCFunction
            if (impl.syncingFromModel) return@staticCFunction
            val h = impl.timeHourSpinPtr?.let { Gtk4DynamicBindings.gtkSpinButtonGetValue(it).toInt().coerceIn(0, 23) } ?: 0
            val m = impl.timeMinuteSpinPtr?.let { Gtk4DynamicBindings.gtkSpinButtonGetValue(it).toInt().coerceIn(0, 59) } ?: 0
            val formatted = "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"
            impl.nativeWidget.realGtkWidgetPtr?.let { mb -> Gtk4DynamicBindings.gtkMenuButtonSetLabel(mb, formatted) }
            impl.pushUserTextChange(formatted)
        }

        private val colorChangedCb = staticCFunction<COpaquePointer?, COpaquePointer?, COpaquePointer?, Unit> { btn, _, userData ->
            val impl = userData?.asStableRef<GtkTextInputNativeImpl>()?.get() ?: return@staticCFunction
            if (impl.syncingFromModel || btn == null) return@staticCFunction
            val argb = Gtk4DynamicBindings.gtkColorDialogButtonGetArgb(btn) ?: return@staticCFunction
            val r = ((argb ushr 16) and 0xFF).toString(16).padStart(2, '0')
            val g = ((argb ushr 8) and 0xFF).toString(16).padStart(2, '0')
            val b = (argb and 0xFF).toString(16).padStart(2, '0')
            impl.pushUserTextChange("#$r$g$b")
        }
    }
}
