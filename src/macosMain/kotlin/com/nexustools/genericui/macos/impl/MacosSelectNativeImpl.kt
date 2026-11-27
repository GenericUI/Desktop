@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos.impl

import com.nexustools.genericui.desktop.widget.DesktopSelectNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapSize
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.macos.MacosAppKitDispatch
import com.nexustools.genericui.macos.MacosFocusSupport
import com.nexustools.genericui.macos.MacosControlActionTarget
import com.nexustools.genericui.macos.MacosViewHandle
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.style.mapper.BootstrapStyleSnapshot
import kotlinx.cinterop.useContents
import platform.AppKit.NSComboBox
import platform.AppKit.NSComboBoxDelegateProtocol
import platform.AppKit.NSControlSizeLarge
import platform.AppKit.NSControlSizeRegular
import platform.AppKit.NSControlSizeSmall
import platform.AppKit.NSPopUpButton
import platform.AppKit.NSView
import platform.AppKit.alignmentRectForFrame
import platform.AppKit.fittingSize
import platform.Foundation.NSMakeRect
import platform.Foundation.NSNotification
import platform.darwin.NSObject
import kotlin.math.ceil

internal class MacosComboBoxDelegate(
    private val onCommitText: (String) -> Unit,
    private val onIndexSelected: (Int) -> Unit
) : NSObject(), NSComboBoxDelegateProtocol {

    override fun comboBoxSelectionDidChange(notification: NSNotification) {
        val cb = notification.`object` as? NSComboBox ?: return
        val idx = cb.indexOfSelectedItem().toInt()
        if (idx >= 0) {
            onIndexSelected(idx)
        }
    }

    override fun controlTextDidChange(obj: NSNotification) {
        val cb = obj.`object` as? NSComboBox ?: return
        onCommitText(cb.stringValue)
    }

    override fun controlTextDidEndEditing(obj: NSNotification) {
        val cb = obj.`object` as? NSComboBox ?: return
        onCommitText(cb.stringValue)
    }
}

/**
 * macOS AppKit retained implementation of [DesktopSelectNativeImpl] supporting both persistent
 * `NSPopUpButton` dropdown selects and editable `NSComboBox` controls (`data-control-variant="combobox"`).
 */
class MacosSelectNativeImpl(
    nativeWidget: MacosViewHandle
) : DesktopSelectNativeImpl<MacosViewHandle>(nativeWidget) {

    private var suppressCallback: Boolean = false

    private val popupActionTarget = MacosControlActionTarget { sender ->
        if (suppressCallback) return@MacosControlActionTarget
        val pop = sender as? NSPopUpButton ?: return@MacosControlActionTarget
        val idx = pop.indexOfSelectedItem().toInt()
        if (idx in optionsList.indices) {
            notifyUserSelectionChanged(optionsList[idx].first)
        }
    }

    private val comboDelegate = MacosComboBoxDelegate(
        onCommitText = { text ->
            if (!suppressCallback) {
                val matched = optionsList.firstOrNull { it.second == text || it.first == text }
                notifyUserSelectionChanged(matched?.first ?: text)
            }
        },
        onIndexSelected = { idx ->
            if (!suppressCallback && idx in optionsList.indices) {
                notifyUserSelectionChanged(optionsList[idx].first)
            }
        }
    )

    private var popUpButton: NSPopUpButton? = null
    private var comboBox: NSComboBox? = null
    private var cachedMeasureView: NSView? = null
    private var cachedMeasureOptions: List<Pair<String, String>>? = null
    private var cachedMeasureSelectedVal: String? = null
    private var cachedMeasureComboVariant: Boolean = false
    private var cachedMeasureComputed: ComputedStyle? = null
    private var cachedMeasureSnapshot: BootstrapStyleSnapshot? = null
    private var cachedMeasureMaxW: Float? = null
    private var cachedMeasureMaxH: Float? = null
    private var cachedMeasureSize: Size? = null

    init {
        nativeWidget.viewMaterializer = {
            mountActiveSelectView()
            popUpButton ?: comboBox
        }
        if (MacosAppKitDispatch.isMainThread()) {
            mountActiveSelectView()
        }
    }

    private fun resolveControlSize(): ULong = when (styleSnapshot.size) {
        BootstrapSize.SM -> NSControlSizeSmall
        BootstrapSize.LG -> NSControlSizeLarge
        else -> NSControlSizeRegular
    }

    private fun mountActiveSelectView() {
        val wantCombo = isComboBoxVariant()
        cachedMeasureSize = null
        nativeWidget.attributes["usesAppKitAlignmentRect"] = true
        if (wantCombo) {
            val cb = NSComboBox(frame = NSMakeRect(0.0, 0.0, 160.0, 28.0)).apply {
                setDelegate(comboDelegate)
            }
            comboBox = cb
            popUpButton = null
            nativeWidget.nsView = cb
            syncComboState(cb)
        } else {
            val pop = NSPopUpButton(frame = NSMakeRect(0.0, 0.0, 160.0, 28.0), pullsDown = false).apply {
                setTarget(popupActionTarget)
                setAction(popupActionTarget.actionSelector)
            }
            popUpButton = pop
            comboBox = null
            nativeWidget.nsView = pop
            syncPopupState(pop)
        }
    }

    private fun syncPopupState(pop: NSPopUpButton) {
        suppressCallback = true
        try {
            pop.removeAllItems()
            for ((_, label) in optionsList) {
                pop.addItemWithTitle(label)
            }
            val selIdx = optionsList.indexOfFirst { it.first == selectedVal }
            if (selIdx >= 0) {
                pop.selectItemAtIndex(selIdx.toLong())
            } else if (optionsList.isNotEmpty()) {
                pop.selectItemAtIndex(0L)
            }
            pop.setControlSize(resolveControlSize())
            pop.setEnabled(nativeWidget.enabled)
            pop.setHidden(!nativeWidget.visible)
            pop.setAlphaValue(nativeWidget.opacity.coerceIn(0f, 1f).toDouble())
            pop.setToolTip(nativeWidget.tooltipText)
        } finally {
            suppressCallback = false
        }
    }

    private fun syncComboState(cb: NSComboBox) {
        suppressCallback = true
        try {
            cb.removeAllItems()
            for ((_, label) in optionsList) {
                cb.addItemWithObjectValue(label)
            }
            val selIdx = optionsList.indexOfFirst { it.first == selectedVal }
            if (selIdx >= 0) {
                cb.selectItemAtIndex(selIdx.toLong())
                cb.setStringValue(optionsList[selIdx].second)
            } else {
                cb.setStringValue(selectedVal ?: "")
            }
            cb.setControlSize(resolveControlSize())
            cb.setEnabled(nativeWidget.enabled)
            cb.setHidden(!nativeWidget.visible)
            cb.setAlphaValue(nativeWidget.opacity.coerceIn(0f, 1f).toDouble())
            cb.setToolTip(nativeWidget.tooltipText)
        } finally {
            suppressCallback = false
        }
    }

    private fun syncAll() {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { syncAll() }
            return
        }
        if (popUpButton == null && comboBox == null) {
            mountActiveSelectView()
            return
        }
        popUpButton?.let { syncPopupState(it) }
        comboBox?.let { syncComboState(it) }
    }

    override fun onVariantChanged(variant: String?) {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { onVariantChanged(variant) }
            return
        }
        val wantCombo = isComboBoxVariant()
        cachedMeasureSize = null
        nativeWidget.attributes["usesAppKitAlignmentRect"] = true
        if (wantCombo && comboBox == null) {
            val oldView = popUpButton
            val nextCombo = NSComboBox(frame = NSMakeRect(0.0, 0.0, 160.0, 28.0)).apply {
                setDelegate(comboDelegate)
            }
            comboBox = nextCombo
            popUpButton = null
            MacosAppKitDispatch.swapMountedView(nativeWidget, oldView, nextCombo, this)
            syncComboState(nextCombo)
        } else if (!wantCombo && popUpButton == null) {
            val oldView = comboBox
            val nextPop = NSPopUpButton(frame = NSMakeRect(0.0, 0.0, 160.0, 28.0), pullsDown = false).apply {
                setTarget(popupActionTarget)
                setAction(popupActionTarget.actionSelector)
            }
            popUpButton = nextPop
            comboBox = null
            MacosAppKitDispatch.swapMountedView(nativeWidget, oldView, nextPop, this)
            syncPopupState(nextPop)
        } else {
            syncAll()
        }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val view = (popUpButton ?: comboBox)
        val isCombo = isComboBoxVariant()
        val cached = cachedMeasureSize
        if (cached != null &&
            cachedMeasureView === view &&
            cachedMeasureComboVariant == isCombo &&
            cachedMeasureOptions == optionsList &&
            cachedMeasureSelectedVal == selectedVal &&
            cachedMeasureComputed === lastComputedStyle &&
            cachedMeasureSnapshot == styleSnapshot &&
            cachedMeasureMaxW == maxWidth &&
            cachedMeasureMaxH == maxHeight
        ) {
            return cached
        }
        val measured = if (view != null) {
            syncAll()
            val fit = view.fittingSize()
            val aligned = view.alignmentRectForFrame(fit.useContents { NSMakeRect(0.0, 0.0, width, height) })
            aligned.useContents {
                Size(ceil(size.width).toInt().coerceAtLeast(110), ceil(size.height).toInt().coerceAtLeast(26))
            }
        } else {
            Size(140, 28)
        }
        cachedMeasureView = view
        cachedMeasureOptions = optionsList.toList()
        cachedMeasureSelectedVal = selectedVal
        cachedMeasureComboVariant = isCombo
        cachedMeasureComputed = lastComputedStyle
        cachedMeasureSnapshot = styleSnapshot
        cachedMeasureMaxW = maxWidth
        cachedMeasureMaxH = maxHeight
        cachedMeasureSize = measured
        return measured
    }

    override fun setOptions(options: List<Pair<String, String>>) {
        cachedMeasureSize = null
        super.setOptions(options)
        syncAll()
    }

    override fun setSelectedValue(value: String?) {
        cachedMeasureSize = null
        super.setSelectedValue(value)
        syncAll()
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { onNativeBoundsChanged(nativeWidget.bounds) }
            return
        }
        val view = nativeWidget.ensureNsViewOnMain() ?: (popUpButton ?: comboBox) ?: return
        MacosAppKitDispatch.applyFrameAndTransform(
            view = view,
            bounds = rect,
            transform = nativeWidget.cssTransform,
            useAlignmentRectExpansion = true
        )
    }

    override fun onNativeVisibilityChanged(visible: Boolean) {
        (popUpButton ?: comboBox)?.setHidden(!visible)
    }

    override fun onNativeEnabledChanged(enabled: Boolean) {
        syncAll()
    }

    override fun onNativeOpacityChanged(opacity: Float) {
        (popUpButton ?: comboBox)?.setAlphaValue(opacity.coerceIn(0f, 1f).toDouble())
    }

    override fun onNativeTooltipChanged(tooltip: String?) {
        (popUpButton ?: comboBox)?.setToolTip(tooltip)
    }

    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        (popUpButton ?: comboBox)?.let { v -> v.window?.invalidateCursorRectsForView(v) }
    }

    override fun onNativeTransformChanged(transform: CssTransform?) {
        nativeWidget.cssTransform = transform
        val view = (popUpButton ?: comboBox) ?: return
        MacosAppKitDispatch.applyFrameAndTransform(
            view = view,
            bounds = nativeWidget.bounds,
            transform = transform,
            useAlignmentRectExpansion = true
        )
    }

    override fun onNativeStyleSynced() {
        cachedMeasureSize = null
        syncAll()
    }

    override fun setFocusedState(focused: Boolean) {
        super.setFocusedState(focused)
        MacosFocusSupport.syncViewFirstResponder(
            targetView = comboBox ?: popUpButton,
            focused = isFocusedState(),
            effectivelyFocusable = nativeWidget.isEffectivelyFocusable(),
            enabled = nativeWidget.enabled
        )
    }

    override fun dispose() {
        val pop = popUpButton
        val combo = comboBox
        popUpButton = null
        comboBox = null
        MacosAppKitDispatch.onMainOrDefer {
            pop?.removeFromSuperview()
            combo?.setDelegate(null)
            combo?.removeFromSuperview()
        }
        super.dispose()
    }
}
