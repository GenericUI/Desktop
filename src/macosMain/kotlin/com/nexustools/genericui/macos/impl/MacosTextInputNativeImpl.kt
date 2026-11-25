@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos.impl

import com.nexustools.genericui.desktop.widget.DesktopTextInputNativeImpl
import com.nexustools.genericui.event.InputFocusManager
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.macos.MacosAppKitAppearance
import com.nexustools.genericui.macos.MacosAppKitDispatch
import com.nexustools.genericui.macos.MacosControlActionTarget
import com.nexustools.genericui.macos.MacosFocusSupport
import com.nexustools.genericui.macos.MacosViewHandle
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.CValue
import kotlinx.cinterop.useContents
import platform.AppKit.NSColorWell
import platform.AppKit.NSDatePicker
import platform.AppKit.NSDatePickerElementFlagHourMinuteSecond
import platform.AppKit.NSDatePickerElementFlagYearMonthDay
import platform.AppKit.NSDatePickerStyleTextFieldAndStepper
import platform.AppKit.NSFont
import platform.AppKit.NSLayoutManager
import platform.AppKit.NSScrollView
import platform.AppKit.NSSearchField
import platform.AppKit.NSSearchFieldDelegateProtocol
import platform.AppKit.NSSecureTextField
import platform.AppKit.NSStepper
import platform.AppKit.NSTextContainer
import platform.AppKit.NSTextField
import platform.AppKit.NSTextFieldDelegateProtocol
import platform.AppKit.NSTextStorage
import platform.AppKit.NSTextView
import platform.AppKit.NSTextViewDelegateProtocol
import platform.AppKit.NSView
import platform.AppKit.fittingSize
import platform.AppKit.frameForAlignmentRect
import platform.AppKit.labelWithString
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitMinute
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitSecond
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.NSDateComponents
import platform.Foundation.NSMakeRect
import platform.Foundation.NSMakeSize
import platform.Foundation.NSNotification
import platform.Foundation.NSRect
import platform.darwin.NSObject
import kotlin.math.ceil

internal class MacosTextFieldBridgeDelegate(
    private val onTextChanged: (String) -> Unit,
    private val onBeginEditing: () -> Unit,
    private val onEndEditing: (String) -> Unit
) : NSObject(), NSTextFieldDelegateProtocol, NSSearchFieldDelegateProtocol {

    override fun controlTextDidBeginEditing(obj: NSNotification) {
        onBeginEditing()
    }

    override fun controlTextDidChange(obj: NSNotification) {
        val tf = obj.`object` as? NSTextField ?: return
        onTextChanged(tf.stringValue)
    }

    override fun controlTextDidEndEditing(obj: NSNotification) {
        val tf = obj.`object` as? NSTextField ?: return
        onEndEditing(tf.stringValue)
    }
}

internal class MacosTextViewBridgeDelegate(
    private val onTextChanged: (String) -> Unit,
    private val onBeginEditing: () -> Unit,
    private val onEndEditing: (String) -> Unit
) : NSObject(), NSTextViewDelegateProtocol {

    override fun textDidBeginEditing(notification: NSNotification) {
        onBeginEditing()
    }

    override fun textDidChange(notification: NSNotification) {
        val tv = notification.`object` as? NSTextView ?: return
        onTextChanged(tv.string)
    }

    override fun textDidEndEditing(notification: NSNotification) {
        val tv = notification.`object` as? NSTextView ?: return
        onEndEditing(tv.string)
    }
}

internal class MacosLabeledWrapperView(
    val captionLabel: NSTextField,
    val contentView: NSView,
    frameRect: CValue<NSRect> = NSMakeRect(0.0, 0.0, 200.0, 52.0)
) : NSView(frameRect) {

    init {
        addSubview(captionLabel)
        addSubview(contentView)
    }

    override fun isFlipped(): Boolean = true

    override fun setFrame(frame: CValue<NSRect>) {
        super.setFrame(frame)
        layoutChildren()
    }

    override fun layout() {
        super.layout()
        layoutChildren()
    }

    private fun layoutChildren() {
        val (w, h) = bounds.useContents { size.width to size.height }
        val captionH = 18.0.coerceAtMost(h * 0.45)
        captionLabel.setFrame(CGRectMake(0.0, 0.0, w, captionH))
        val bodyH = (h - captionH - 2.0).coerceAtLeast(18.0)
        contentView.setFrame(CGRectMake(0.0, captionH + 2.0, w, bodyH))
    }
}

internal class MacosNumberInputView(
    val textField: NSTextField,
    val stepper: NSStepper,
    frameRect: CValue<NSRect> = NSMakeRect(0.0, 0.0, 140.0, 28.0)
) : NSView(frameRect) {

    init {
        addSubview(textField)
        addSubview(stepper)
    }

    override fun isFlipped(): Boolean = true

    override fun setFrame(frame: CValue<NSRect>) {
        super.setFrame(frame)
        layoutChildren()
    }

    override fun layout() {
        super.layout()
        layoutChildren()
    }

    private fun layoutChildren() {
        val (w, h) = bounds.useContents { size.width to size.height }
        val stepW = 19.0
        val fieldW = (w - stepW - 4.0).coerceAtLeast(24.0)
        textField.setFrame(textField.frameForAlignmentRect(CGRectMake(0.0, 0.0, fieldW, h)))
        stepper.setFrame(stepper.frameForAlignmentRect(CGRectMake(w - stepW, 0.0, stepW, h)))
    }
}

private class MacosFocusableTextView private constructor(
    private val ownerHandle: MacosViewHandle,
    frameRect: CValue<NSRect>,
    textContainer: NSTextContainer
) : NSTextView(frame = frameRect, textContainer = textContainer) {
    override fun acceptsFirstResponder(): Boolean =
        ownerHandle.isEffectivelyFocusable() && super.acceptsFirstResponder()

    override fun canBecomeKeyView(): Boolean =
        ownerHandle.isEffectivelyFocusable() && super.canBecomeKeyView()

    companion object {
        fun create(ownerHandle: MacosViewHandle, frameRect: CValue<NSRect>): MacosFocusableTextView {
            val storage = NSTextStorage()
            val layoutManager = NSLayoutManager()
            storage.addLayoutManager(layoutManager)
            val container = NSTextContainer(size = NSMakeSize(240.0, 1.0e7)).apply {
                setWidthTracksTextView(true)
            }
            layoutManager.addTextContainer(container)
            return MacosFocusableTextView(ownerHandle, frameRect, container)
        }
    }
}

/**
 * macOS AppKit retained implementation of [DesktopTextInputNativeImpl] covering `NSTextField`,
 * `NSSecureTextField`, `NSSearchField`, numeric `NSTextField + NSStepper`, multiline
 * `NSScrollView + NSTextView`, `NSDatePicker` (date/time), and `NSColorWell` variants.
 */
class MacosTextInputNativeImpl(
    nativeWidget: MacosViewHandle,
    multiline: Boolean = false
) : DesktopTextInputNativeImpl<MacosViewHandle>(nativeWidget, multiline) {

    private var suppressCallbacks: Boolean = false

    private val fieldDelegate = MacosTextFieldBridgeDelegate(
        onTextChanged = { text ->
            if (!suppressCallbacks) {
                super.setValue(text)
            }
        },
        onBeginEditing = {
            if (nativeWidget.isEffectivelyFocusable()) {
                InputFocusManager.isNativeTextInputFocused = true
                setFocusedState(true)
            } else {
                MacosFocusSupport.resignIfInside(rootMountedView)
            }
        },
        onEndEditing = { text ->
            if (!suppressCallbacks) {
                super.setValue(text)
                submitValue()
            }
            InputFocusManager.isNativeTextInputFocused = false
            setFocusedState(false)
        }
    )

    private val textViewDelegate = MacosTextViewBridgeDelegate(
        onTextChanged = { text ->
            if (!suppressCallbacks) {
                super.setValue(text)
            }
        },
        onBeginEditing = {
            if (nativeWidget.isEffectivelyFocusable()) {
                InputFocusManager.isNativeTextInputFocused = true
                setFocusedState(true)
            } else {
                MacosFocusSupport.resignIfInside(rootMountedView)
            }
        },
        onEndEditing = { text ->
            if (!suppressCallbacks) {
                super.setValue(text)
            }
            InputFocusManager.isNativeTextInputFocused = false
            setFocusedState(false)
        }
    )

    private val datePickerAction = MacosControlActionTarget { sender ->
        if (suppressCallbacks) return@MacosControlActionTarget
        val picker = sender as? NSDatePicker ?: return@MacosControlActionTarget
        val formatted = formatPickerDate(picker.dateValue, isTime = resolveVariantKey() == "time")
        super.setValue(formatted)
    }

    private val colorWellAction = MacosControlActionTarget { sender ->
        if (suppressCallbacks) return@MacosControlActionTarget
        val well = sender as? NSColorWell ?: return@MacosControlActionTarget
        val rgba = MacosAppKitAppearance.toRgbaColor(well.color)
        super.setValue(formatHexColor(rgba))
    }

    private val stepperAction = MacosControlActionTarget { sender ->
        if (suppressCallbacks) return@MacosControlActionTarget
        val step = sender as? NSStepper ?: return@MacosControlActionTarget
        val v = step.doubleValue
        val formatted = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
        numberView?.textField?.setStringValue(formatted)
        super.setValue(formatted)
    }

    private val fieldSubmitAction = MacosControlActionTarget {
        if (!suppressCallbacks) {
            submitValue()
        }
    }

    private var activeVariantKey: String = ""
    private var activeHasCaptionLabel: Boolean = false
    private var rootMountedView: NSView? = null
    private var labeledWrapper: MacosLabeledWrapperView? = null
    private var singleLineField: NSTextField? = null
    private var searchField: NSSearchField? = null
    private var secureField: NSSecureTextField? = null
    private var numberView: MacosNumberInputView? = null
    private var scrollView: NSScrollView? = null
    private var multilineTextView: NSTextView? = null
    private var datePicker: NSDatePicker? = null
    private var colorWell: NSColorWell? = null

    init {
        nativeWidget.viewMaterializer = {
            mountCurrentControl(isInitial = true)
            rootMountedView
        }
        if (MacosAppKitDispatch.isMainThread()) {
            mountCurrentControl(isInitial = true)
        }
    }

    private fun resolveVariantKey(): String = when {
        isMultiline() -> "multiline"
        isPasswordMode() -> "password"
        else -> when (resolveControlVariant()) {
            "date" -> "date"
            "time" -> "time"
            "color" -> "color"
            "search" -> "search"
            "number" -> "number"
            else -> "text"
        }
    }

    private fun clearSubReferences() {
        singleLineField?.setDelegate(null)
        searchField?.setDelegate(null)
        secureField?.setDelegate(null)
        numberView?.textField?.setDelegate(null)
        multilineTextView?.setDelegate(null)
        singleLineField = null
        searchField = null
        secureField = null
        numberView = null
        scrollView = null
        multilineTextView = null
        datePicker = null
        colorWell = null
        labeledWrapper = null
    }

    private fun buildVariantInnerView(variantKey: String): NSView {
        clearSubReferences()
        val font = MacosAppKitAppearance.resolveFont(lastComputedStyle, styleSnapshot, 13.0, false)
        return when (variantKey) {
            "date" -> NSDatePicker(frame = NSMakeRect(0.0, 0.0, 160.0, 28.0)).apply {
                setDatePickerStyle(NSDatePickerStyleTextFieldAndStepper)
                setDatePickerElements(NSDatePickerElementFlagYearMonthDay)
                setTarget(datePickerAction)
                setAction(datePickerAction.actionSelector)
                datePicker = this
            }
            "time" -> NSDatePicker(frame = NSMakeRect(0.0, 0.0, 140.0, 28.0)).apply {
                setDatePickerStyle(NSDatePickerStyleTextFieldAndStepper)
                setDatePickerElements(NSDatePickerElementFlagHourMinuteSecond)
                setTarget(datePickerAction)
                setAction(datePickerAction.actionSelector)
                datePicker = this
            }
            "color" -> NSColorWell(frame = NSMakeRect(0.0, 0.0, 48.0, 28.0)).apply {
                setTarget(colorWellAction)
                setAction(colorWellAction.actionSelector)
                colorWell = this
            }
            "search" -> NSSearchField(frame = NSMakeRect(0.0, 0.0, 200.0, 28.0)).apply {
                setFont(font)
                setDelegate(fieldDelegate)
                setTarget(fieldSubmitAction)
                setAction(fieldSubmitAction.actionSelector)
                searchField = this
            }
            "number" -> {
                val tf = NSTextField(frame = NSMakeRect(0.0, 0.0, 110.0, 28.0)).apply {
                    setFont(font)
                    setDelegate(fieldDelegate)
                    setTarget(fieldSubmitAction)
                    setAction(fieldSubmitAction.actionSelector)
                }
                val st = NSStepper(frame = NSMakeRect(0.0, 0.0, 19.0, 28.0)).apply {
                    setMinValue(-1_000_000_000.0)
                    setMaxValue(1_000_000_000.0)
                    setIncrement(1.0)
                    setValueWraps(false)
                    setTarget(stepperAction)
                    setAction(stepperAction.actionSelector)
                }
                MacosNumberInputView(tf, st).also { numberView = it }
            }
            "password" -> NSSecureTextField(frame = NSMakeRect(0.0, 0.0, 200.0, 28.0)).apply {
                setFont(font)
                setDelegate(fieldDelegate)
                setTarget(fieldSubmitAction)
                setAction(fieldSubmitAction.actionSelector)
                secureField = this
            }
            "multiline" -> {
                val sv = NSScrollView(frame = NSMakeRect(0.0, 0.0, 240.0, 96.0)).apply {
                    setHasVerticalScroller(true)
                    setHasHorizontalScroller(false)
                    setAutohidesScrollers(true)
                }
                sv.setBorderType(platform.AppKit.NSBezelBorder)
                val tv = MacosFocusableTextView.create(nativeWidget, NSMakeRect(0.0, 0.0, 240.0, 96.0)).apply {
                    setFont(font)
                    setVerticallyResizable(true)
                    setHorizontallyResizable(false)
                    setDelegate(textViewDelegate)
                }
                sv.setDocumentView(tv)
                scrollView = sv
                multilineTextView = tv
                sv
            }
            else -> NSTextField(frame = NSMakeRect(0.0, 0.0, 200.0, 28.0)).apply {
                setFont(font)
                setDelegate(fieldDelegate)
                setTarget(fieldSubmitAction)
                setAction(fieldSubmitAction.actionSelector)
                singleLineField = this
            }
        }
    }

    private fun mountCurrentControl(isInitial: Boolean) {
        val nextKey = resolveVariantKey()
        val label = getLabel()
        val wantLabel = !label.isNullOrBlank()
        if (!isInitial && rootMountedView != null && nextKey == activeVariantKey && wantLabel == activeHasCaptionLabel) {
            syncControlValues()
            return
        }

        val oldRoot = rootMountedView
        activeVariantKey = nextKey
        activeHasCaptionLabel = wantLabel

        val inner = buildVariantInnerView(nextKey)
        val mounted = if (wantLabel && label != null) {
            val caption = NSTextField.labelWithString(label).apply {
                setFont(NSFont.systemFontOfSize(11.0, weight = platform.AppKit.NSFontWeightMedium))
            }
            MacosLabeledWrapperView(caption, inner).also { labeledWrapper = it }
        } else {
            inner
        }

        val isPlainAppKitControl = !wantLabel && nextKey in setOf("text", "search", "password", "date", "time")
        nativeWidget.attributes["usesAppKitAlignmentRect"] = isPlainAppKitControl

        rootMountedView = mounted
        if (isInitial || oldRoot == null) {
            nativeWidget.nsView = mounted
        } else {
            MacosAppKitDispatch.swapMountedView(nativeWidget, oldRoot, mounted, this)
        }
        syncControlValues()
    }

    private fun syncControlValues() {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { syncControlValues() }
            return
        }
        if (rootMountedView == null) {
            mountCurrentControl(isInitial = true)
            return
        }
        suppressCallbacks = true
        try {
            val enabled = nativeWidget.enabled
            val editable = enabled && !isReadOnly()
            labeledWrapper?.captionLabel?.setStringValue(getLabel() ?: "")

            singleLineField?.let { tf ->
                if (tf.stringValue != textValue) tf.setStringValue(textValue)
                tf.setPlaceholderString(placeholderText)
                tf.setEditable(editable)
                tf.setEnabled(enabled)
            }
            searchField?.let { sf ->
                if (sf.stringValue != textValue) sf.setStringValue(textValue)
                sf.setPlaceholderString(placeholderText)
                sf.setEditable(editable)
                sf.setEnabled(enabled)
            }
            secureField?.let { sec ->
                if (sec.stringValue != textValue) sec.setStringValue(textValue)
                sec.setPlaceholderString(placeholderText)
                sec.setEditable(editable)
                sec.setEnabled(enabled)
            }
            numberView?.let { nv ->
                if (nv.textField.stringValue != textValue) nv.textField.setStringValue(textValue)
                nv.textField.setPlaceholderString(placeholderText)
                nv.textField.setEditable(editable)
                nv.textField.setEnabled(enabled)
                val num = textValue.trim().toDoubleOrNull() ?: 0.0
                nv.stepper.setDoubleValue(num)
                nv.stepper.setEnabled(editable)
            }
            multilineTextView?.let { tv ->
                if (tv.string != textValue) tv.setString(textValue)
                tv.setEditable(editable)
            }
            datePicker?.let { dp ->
                parsePickerDate(textValue, isTime = activeVariantKey == "time")?.let { d ->
                    dp.setDateValue(d)
                }
                dp.setEnabled(editable)
            }
            colorWell?.let { cw ->
                parseHexOrCssColor(textValue)?.let { rgba ->
                    cw.setColor(MacosAppKitAppearance.toNsColor(rgba))
                }
                cw.setEnabled(editable)
            }

            rootMountedView?.let { v ->
                v.setHidden(!nativeWidget.visible)
                v.setAlphaValue(nativeWidget.opacity.coerceIn(0f, 1f).toDouble())
                v.setToolTip(nativeWidget.tooltipText)
                MacosFocusSupport.applyFocusable(v, nativeWidget.isEffectivelyFocusable())
            }
        } finally {
            suppressCallbacks = false
        }
    }

    override fun onVariantChanged(variant: String?) {
        if (MacosAppKitDispatch.isMainThread()) {
            mountCurrentControl(isInitial = false)
        }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val v = rootMountedView
        if (v != null && activeVariantKey !in setOf("multiline", "color")) {
            val fit = v.fittingSize()
            val (fw, fh) = fit.useContents { ceil(width).toInt() to ceil(height).toInt() }
            if (activeVariantKey == "date" || activeVariantKey == "time") {
                return Size(fw.coerceAtLeast(110), (fh + if (activeHasCaptionLabel) 20 else 0).coerceAtLeast(26))
            }
            val preferredNaturalW = if (activeVariantKey == "number") 110 else 200
            val maxCapW = maxWidth?.roundToIntOrNull()?.coerceAtLeast(60)
            val targetNaturalW = if (maxCapW != null) minOf(preferredNaturalW, maxCapW) else preferredNaturalW
            val naturalW = maxOf(minOf(fw, preferredNaturalW), targetNaturalW)
            val clampedW = if (maxCapW != null) minOf(naturalW, maxCapW) else naturalW
            val minW = if (activeVariantKey == "number") 80 else 110
            val effectiveMinW = if (maxCapW != null) minOf(minW, maxCapW) else minW
            return Size(
                clampedW.coerceAtLeast(effectiveMinW),
                (fh + if (activeHasCaptionLabel) 20 else 0).coerceAtLeast(26)
            )
        }
        return when (activeVariantKey) {
            "multiline" -> Size(240, if (activeHasCaptionLabel) 116 else 96)
            "color" -> Size(48, if (activeHasCaptionLabel) 48 else 28)
            "number" -> Size(110, if (activeHasCaptionLabel) 48 else 28)
            else -> Size(200, if (activeHasCaptionLabel) 48 else 28)
        }
    }

    override fun setValue(value: String) {
        super.setValue(value)
        syncControlValues()
    }

    override fun setPlaceholder(placeholder: String) {
        super.setPlaceholder(placeholder)
        syncControlValues()
    }

    override fun setLabel(label: String?) {
        super.setLabel(label)
        if (MacosAppKitDispatch.isMainThread()) {
            mountCurrentControl(isInitial = false)
        }
    }

    override fun setReadOnly(readOnly: Boolean) {
        super.setReadOnly(readOnly)
        syncControlValues()
    }

    override fun setPasswordMode(password: Boolean) {
        super.setPasswordMode(password)
        if (MacosAppKitDispatch.isMainThread()) {
            mountCurrentControl(isInitial = false)
        }
    }

    override fun setMultiline(multiline: Boolean) {
        super.setMultiline(multiline)
        if (MacosAppKitDispatch.isMainThread()) {
            mountCurrentControl(isInitial = false)
        }
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { onNativeBoundsChanged(nativeWidget.bounds) }
            return
        }
        val v = nativeWidget.ensureNsViewOnMain() ?: rootMountedView ?: return
        val useAlign = (nativeWidget.attributes["usesAppKitAlignmentRect"] as? Boolean) == true
        MacosAppKitDispatch.applyFrameAndTransform(v, rect, nativeWidget.cssTransform, useAlignmentRectExpansion = useAlign)
    }

    override fun onNativeVisibilityChanged(visible: Boolean) {
        rootMountedView?.setHidden(!visible)
    }

    override fun onNativeEnabledChanged(enabled: Boolean) {
        syncControlValues()
    }

    override fun onNativeOpacityChanged(opacity: Float) {
        rootMountedView?.setAlphaValue(opacity.coerceIn(0f, 1f).toDouble())
    }

    override fun onNativeTooltipChanged(tooltip: String?) {
        rootMountedView?.setToolTip(tooltip)
    }

    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        rootMountedView?.let { v -> v.window?.invalidateCursorRectsForView(v) }
    }

    override fun onNativeTransformChanged(transform: CssTransform?) {
        nativeWidget.cssTransform = transform
        val v = rootMountedView ?: return
        val useAlign = (nativeWidget.attributes["usesAppKitAlignmentRect"] as? Boolean) == true
        MacosAppKitDispatch.applyFrameAndTransform(v, nativeWidget.bounds, transform, useAlignmentRectExpansion = useAlign)
    }

    override fun onNativeStyleSynced() {
        if (MacosAppKitDispatch.isMainThread()) {
            mountCurrentControl(isInitial = false)
        }
    }

    override fun dispose() {
        clearSubReferences()
        val view = rootMountedView
        rootMountedView = null
        MacosAppKitDispatch.detachViewOnMain(view)
        super.dispose()
    }

    private fun Float.roundToIntOrNull(): Int? = if (this.isFinite()) kotlin.math.round(this).toInt() else null

    private fun parsePickerDate(raw: String, isTime: Boolean): NSDate? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val comps = NSDateComponents()
        if (isTime) {
            val parts = trimmed.split(':')
            val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
            val m = parts.getOrNull(1)?.toIntOrNull() ?: return null
            val s = parts.getOrNull(2)?.toIntOrNull() ?: 0
            comps.setHour(h.coerceIn(0, 23).toLong())
            comps.setMinute(m.coerceIn(0, 59).toLong())
            comps.setSecond(s.coerceIn(0, 59).toLong())
        } else {
            val parts = trimmed.substringBefore('T').split('-')
            val y = parts.getOrNull(0)?.toIntOrNull() ?: return null
            val m = parts.getOrNull(1)?.toIntOrNull() ?: return null
            val d = parts.getOrNull(2)?.toIntOrNull() ?: return null
            comps.setYear(y.toLong())
            comps.setMonth(m.coerceIn(1, 12).toLong())
            comps.setDay(d.coerceIn(1, 31).toLong())
        }
        return NSCalendar.currentCalendar.dateFromComponents(comps)
    }

    private fun formatPickerDate(date: NSDate, isTime: Boolean): String {
        val cal = NSCalendar.currentCalendar
        val units = if (isTime) {
            NSCalendarUnitHour or NSCalendarUnitMinute or NSCalendarUnitSecond
        } else {
            NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay
        }
        val comps = cal.components(units, fromDate = date)
        return if (isTime) {
            val h = comps.hour.toString().padStart(2, '0')
            val m = comps.minute.toString().padStart(2, '0')
            val s = comps.second.toString().padStart(2, '0')
            "$h:$m:$s"
        } else {
            val y = comps.year.toString().padStart(4, '0')
            val m = comps.month.toString().padStart(2, '0')
            val d = comps.day.toString().padStart(2, '0')
            "$y-$m-$d"
        }
    }

    private fun parseHexOrCssColor(raw: String): RgbaColor? {
        val s = raw.trim().removePrefix("#")
        return when (s.length) {
            6 -> {
                val r = s.substring(0, 2).toIntOrNull(16) ?: return null
                val g = s.substring(2, 4).toIntOrNull(16) ?: return null
                val b = s.substring(4, 6).toIntOrNull(16) ?: return null
                RgbaColor(r, g, b, 255)
            }
            8 -> {
                val r = s.substring(0, 2).toIntOrNull(16) ?: return null
                val g = s.substring(2, 4).toIntOrNull(16) ?: return null
                val b = s.substring(4, 6).toIntOrNull(16) ?: return null
                val a = s.substring(6, 8).toIntOrNull(16) ?: return null
                RgbaColor(r, g, b, a)
            }
            else -> null
        }
    }

    private fun formatHexColor(c: RgbaColor): String {
        fun hex2(v: Int) = v.coerceIn(0, 255).toString(16).padStart(2, '0')
        return if (c.a >= 255) {
            "#${hex2(c.r)}${hex2(c.g)}${hex2(c.b)}"
        } else {
            "#${hex2(c.r)}${hex2(c.g)}${hex2(c.b)}${hex2(c.a)}"
        }
    }
}
