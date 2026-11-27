@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos.impl

import com.nexustools.genericui.desktop.widget.DesktopCheckboxNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.macos.MacosAppKitAppearance
import com.nexustools.genericui.macos.MacosAppKitDispatch
import com.nexustools.genericui.macos.MacosFocusSupport
import com.nexustools.genericui.macos.MacosControlActionTarget
import com.nexustools.genericui.macos.MacosViewHandle
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.CValue
import kotlinx.cinterop.useContents
import platform.AppKit.NSButton
import platform.AppKit.NSControlStateValueOff
import platform.AppKit.NSControlStateValueOn
import platform.AppKit.NSEvent
import platform.AppKit.NSSwitch
import platform.AppKit.NSTextField
import platform.AppKit.NSView
import platform.AppKit.alignmentRectForFrame
import platform.AppKit.fittingSize
import platform.AppKit.frameForAlignmentRect
import platform.AppKit.labelWithString
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSMakeRect
import platform.Foundation.NSRect
import kotlin.math.ceil

internal class MacosSwitchLabeledView(
    val switchControl: NSSwitch,
    val labelField: NSTextField,
    private val onLabelClick: () -> Unit,
    frameRect: CValue<NSRect> = NSMakeRect(0.0, 0.0, 140.0, 26.0)
) : NSView(frameRect) {

    init {
        addSubview(labelField)
    }

    fun ensureSwitchAttached() {
        if (switchControl.superview != this) {
            addSubview(switchControl)
            layoutChildren()
        }
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

    override fun mouseUp(event: NSEvent) {
        onLabelClick()
    }

    private fun layoutChildren() {
        val (w, h) = bounds.useContents { size.width to size.height }
        val swFit = switchControl.fittingSize().useContents { width to height }
        val swW = swFit.first.coerceAtLeast(38.0)
        val swH = swFit.second.coerceAtLeast(22.0)
        val swY = ((h - swH) * 0.5).coerceAtLeast(0.0)
        switchControl.setFrame(switchControl.frameForAlignmentRect(CGRectMake(0.0, swY, swW, swH)))
        val lblX = swW + 8.0
        val lblW = (w - lblX).coerceAtLeast(0.0)
        val lblH = 18.0
        labelField.setFrame(CGRectMake(lblX, ((h - lblH) * 0.5).coerceAtLeast(0.0), lblW, lblH))
    }
}

/**
 * macOS AppKit retained `NSButton` (checkbox/radio) and `NSSwitch` implementation of
 * [DesktopCheckboxNativeImpl].
 */
class MacosCheckboxNativeImpl(
    nativeWidget: MacosViewHandle,
    switchStyle: Boolean = false,
    radioStyle: Boolean = false
) : DesktopCheckboxNativeImpl<MacosViewHandle>(nativeWidget, switchStyle, radioStyle) {

    private var suppressCallback: Boolean = false

    private val toggleTarget = MacosControlActionTarget { sender ->
        if (suppressCallback || !nativeWidget.enabled || isPointerInteractionDisabled()) return@MacosControlActionTarget
        val nextChecked = when (sender) {
            is NSSwitch -> sender.state == NSControlStateValueOn
            is NSButton -> sender.state == NSControlStateValueOn
            else -> !checkedState
        }
        setChecked(nextChecked)
    }

    private var buttonControl: NSButton? = null
    private var switchControl: NSSwitch? = null
    private var switchLabeledView: MacosSwitchLabeledView? = null
    private var rootMountedView: NSView? = null

    init {
        nativeWidget.viewMaterializer = {
            mountControl(isInitial = true)
            rootMountedView
        }
        if (MacosAppKitDispatch.isMainThread()) {
            mountControl(isInitial = true)
        }
    }

    private fun mountControl(isInitial: Boolean) {
        val oldRoot = rootMountedView
        val nextRoot: NSView
        if (switchStyle) {
            val sw = switchControl ?: NSSwitch(frame = NSMakeRect(0.0, 0.0, 40.0, 24.0)).apply {
                setTarget(toggleTarget)
                setAction(toggleTarget.actionSelector)
                switchControl = this
            }
            if (labelText.isNotEmpty()) {
                val wrapper = switchLabeledView ?: MacosSwitchLabeledView(
                    switchControl = sw,
                    labelField = NSTextField.labelWithString(labelText),
                    onLabelClick = {
                        if (nativeWidget.enabled && !isPointerInteractionDisabled()) {
                            setChecked(!checkedState)
                        }
                    }
                ).also { switchLabeledView = it }
                nativeWidget.attributes["usesAppKitAlignmentRect"] = false
                nextRoot = wrapper
            } else {
                switchLabeledView = null
                nativeWidget.attributes["usesAppKitAlignmentRect"] = true
                nextRoot = sw
            }
        } else {
            val btn = buttonControl ?: if (radioStyle) {
                NSButton.radioButtonWithTitle(
                    title = labelText,
                    target = toggleTarget,
                    action = toggleTarget.actionSelector
                )
            } else {
                NSButton.checkboxWithTitle(
                    title = labelText,
                    target = toggleTarget,
                    action = toggleTarget.actionSelector
                )
            }.also { buttonControl = it }
            nativeWidget.attributes["usesAppKitAlignmentRect"] = true
            nextRoot = btn
        }

        rootMountedView = nextRoot
        if (isInitial || oldRoot == null) {
            nativeWidget.nsView = nextRoot
        } else if (oldRoot != nextRoot) {
            MacosAppKitDispatch.swapMountedView(nativeWidget, oldRoot, nextRoot, this)
        }
        (nextRoot as? MacosSwitchLabeledView)?.ensureSwitchAttached()
        syncViewState()
    }

    private fun syncViewState() {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { syncViewState() }
            return
        }
        if (rootMountedView == null) {
            mountControl(isInitial = true)
            return
        }
        suppressCallbacksState(true)
        try {
            val stateVal = if (checkedState) NSControlStateValueOn else NSControlStateValueOff
            buttonControl?.let { btn ->
                btn.setTitle(labelText)
                btn.setState(stateVal)
                btn.setEnabled(nativeWidget.enabled)
                btn.setFont(MacosAppKitAppearance.resolveFont(lastComputedStyle, styleSnapshot, 13.0, false))
            }
            switchControl?.let { sw ->
                sw.setState(stateVal)
                sw.setEnabled(nativeWidget.enabled)
            }
            switchLabeledView?.let { w ->
                w.labelField.setStringValue(labelText)
                w.labelField.setFont(MacosAppKitAppearance.resolveFont(lastComputedStyle, styleSnapshot, 13.0, false))
            }
            rootMountedView?.let { v ->
                v.setHidden(!nativeWidget.visible)
                v.setAlphaValue(nativeWidget.opacity.coerceIn(0f, 1f).toDouble())
                v.setToolTip(nativeWidget.tooltipText)
            }
        } finally {
            suppressCallbacksState(false)
        }
    }

    private fun suppressCallbacksState(suppress: Boolean) {
        suppressCallback = suppress
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val btn = buttonControl
        if (btn != null) {
            syncViewState()
            val fit = btn.fittingSize()
            val aligned = btn.alignmentRectForFrame(fit.useContents { NSMakeRect(0.0, 0.0, width, height) })
            return aligned.useContents {
                Size(ceil(size.width).toInt().coerceAtLeast(24), ceil(size.height).toInt().coerceAtLeast(20))
            }
        }
        val sw = switchControl
        if (sw != null) {
            val swFit = sw.fittingSize().useContents { ceil(width).toInt() to ceil(height).toInt() }
            val extra = if (labelText.isNotEmpty()) (labelText.length * 7 + 16) else 0
            return Size((swFit.first + extra).coerceAtLeast(38), swFit.second.coerceAtLeast(22))
        }
        return Size(120, 24)
    }

    override fun setLabel(label: String) {
        val wasEmpty = labelText.isEmpty()
        super.setLabel(label)
        val isEmpty = labelText.isEmpty()
        if (MacosAppKitDispatch.isMainThread() && switchStyle && wasEmpty != isEmpty) {
            mountControl(isInitial = false)
        } else {
            syncViewState()
        }
    }

    override fun setChecked(checked: Boolean) {
        super.setChecked(checked)
        syncViewState()
    }

    private fun applyRootFrame(view: NSView, rect: Rect, transform: CssTransform?) {
        val useAlign = (nativeWidget.attributes["usesAppKitAlignmentRect"] as? Boolean) == true
        val effectiveRect = if (switchStyle && labelText.isEmpty()) {
            val swFit = switchControl?.fittingSize()?.useContents { ceil(width).toInt() } ?: 38
            val targetW = if (rect.width <= 0) 0 else minOf(rect.width, maxOf(swFit, 38)).coerceAtLeast(1)
            Rect(rect.x, rect.y, targetW, rect.height)
        } else {
            rect
        }
        MacosAppKitDispatch.applyFrameAndTransform(view, effectiveRect, transform, useAlignmentRectExpansion = useAlign)
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { onNativeBoundsChanged(nativeWidget.bounds) }
            return
        }
        val v = nativeWidget.ensureNsViewOnMain() ?: rootMountedView ?: return
        applyRootFrame(v, rect, nativeWidget.cssTransform)
    }

    override fun onNativeVisibilityChanged(visible: Boolean) {
        rootMountedView?.setHidden(!visible)
    }

    override fun onNativeEnabledChanged(enabled: Boolean) {
        syncViewState()
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
        applyRootFrame(v, nativeWidget.bounds, transform)
    }

    override fun onNativeStyleSynced() {
        syncViewState()
    }

    override fun setFocusedState(focused: Boolean) {
        super.setFocusedState(focused)
        MacosFocusSupport.syncViewFirstResponder(
            targetView = buttonControl ?: switchControl,
            focused = isFocusedState(),
            effectivelyFocusable = nativeWidget.isEffectivelyFocusable(),
            enabled = nativeWidget.enabled
        )
    }

    override fun dispose() {
        val view = rootMountedView
        buttonControl = null
        switchControl = null
        switchLabeledView = null
        rootMountedView = null
        MacosAppKitDispatch.detachViewOnMain(view)
        super.dispose()
    }
}
