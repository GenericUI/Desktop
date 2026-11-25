@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos.impl

import com.nexustools.genericui.desktop.widget.DesktopDropdownNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapSize
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.macos.MacosAppKitDispatch
import com.nexustools.genericui.macos.MacosControlActionTarget
import com.nexustools.genericui.macos.MacosViewHandle
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.CValue
import kotlinx.cinterop.useContents
import platform.AppKit.NSBezelStylePush
import platform.AppKit.NSButton
import platform.AppKit.NSControlSizeLarge
import platform.AppKit.NSControlSizeRegular
import platform.AppKit.NSControlSizeSmall
import platform.AppKit.NSPopUpButton
import platform.AppKit.NSView
import platform.AppKit.alignmentRectForFrame
import platform.AppKit.fittingSize
import platform.AppKit.frameForAlignmentRect
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSMakeRect
import platform.Foundation.NSRect
import kotlin.math.ceil

internal class MacosSplitDropdownView(
    val primaryButton: NSButton,
    val menuButton: NSPopUpButton,
    frameRect: CValue<NSRect> = NSMakeRect(0.0, 0.0, 140.0, 32.0)
) : NSView(frameRect) {

    init {
        addSubview(primaryButton)
        addSubview(menuButton)
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
        val dropW = 28.0.coerceAtMost((w * 0.35).coerceAtLeast(22.0))
        val btnW = (w - dropW - 2.0).coerceAtLeast(0.0)
        primaryButton.setFrame(primaryButton.frameForAlignmentRect(CGRectMake(0.0, 0.0, btnW, h)))
        menuButton.setFrame(menuButton.frameForAlignmentRect(CGRectMake(w - dropW, 0.0, dropW, h)))
    }
}

/**
 * macOS AppKit retained [NSPopUpButton] / split `[NSButton + NSPopUpButton]` implementation
 * of [DesktopDropdownNativeImpl].
 */
class MacosDropdownNativeImpl(
    nativeWidget: MacosViewHandle
) : DesktopDropdownNativeImpl<MacosViewHandle>(nativeWidget) {

    private val popupActionTarget = MacosControlActionTarget { sender ->
        val pop = sender as? NSPopUpButton ?: return@MacosControlActionTarget
        val selectedIdx = pop.indexOfSelectedItem().toInt()
        // For pullsDown = true, index 0 is the anchor header title and itemsList starts at index 1.
        val itemIdx = selectedIdx - 1
        if (itemIdx in itemsList.indices) {
            onItemSelectedListener?.invoke(itemsList[itemIdx].first)
        }
    }

    private val primaryActionTarget = MacosControlActionTarget {
        dispatchPrimaryAction()
    }

    private var popUpButton: NSPopUpButton? = null
    private var splitContainer: MacosSplitDropdownView? = null

    init {
        nativeWidget.viewMaterializer = {
            mountActiveView()
            popUpButton ?: splitContainer
        }
        if (MacosAppKitDispatch.isMainThread()) {
            mountActiveView()
        }
    }

    private fun resolveControlSize(): ULong = when (styleSnapshot.size) {
        BootstrapSize.SM -> NSControlSizeSmall
        BootstrapSize.LG -> NSControlSizeLarge
        else -> NSControlSizeRegular
    }

    private fun mountActiveView() {
        val split = isSplitDropdownVariant()
        nativeWidget.attributes["usesAppKitAlignmentRect"] = !split
        if (split) {
            val primaryBtn = NSButton.buttonWithTitle(
                title = labelText,
                target = primaryActionTarget,
                action = primaryActionTarget.actionSelector
            ).apply {
                setBezelStyle(NSBezelStylePush)
            }
            val menuBtn = NSPopUpButton(frame = NSMakeRect(0.0, 0.0, 28.0, 32.0), pullsDown = true).apply {
                setTarget(popupActionTarget)
                setAction(popupActionTarget.actionSelector)
            }
            val splitView = MacosSplitDropdownView(primaryBtn, menuBtn)
            splitContainer = splitView
            popUpButton = null
            nativeWidget.nsView = splitView
            syncSplitState(splitView)
        } else {
            val pop = NSPopUpButton(frame = NSMakeRect(0.0, 0.0, 140.0, 32.0), pullsDown = true).apply {
                setTarget(popupActionTarget)
                setAction(popupActionTarget.actionSelector)
            }
            popUpButton = pop
            splitContainer = null
            nativeWidget.nsView = pop
            syncSinglePopupState(pop)
        }
    }

    private fun syncSinglePopupState(pop: NSPopUpButton) {
        pop.removeAllItems()
        pop.addItemWithTitle(labelText)
        for ((_, displayTitle) in itemsList) {
            pop.addItemWithTitle(displayTitle)
        }
        pop.setControlSize(resolveControlSize())
        pop.setEnabled(nativeWidget.enabled)
        pop.setHidden(!nativeWidget.visible)
        pop.setAlphaValue(nativeWidget.opacity.coerceIn(0f, 1f).toDouble())
        pop.setToolTip(nativeWidget.tooltipText)
    }

    private fun syncSplitState(splitView: MacosSplitDropdownView) {
        val cs = resolveControlSize()
        splitView.primaryButton.setTitle(labelText)
        splitView.primaryButton.setControlSize(cs)
        splitView.primaryButton.setEnabled(nativeWidget.enabled)

        val pop = splitView.menuButton
        pop.removeAllItems()
        pop.addItemWithTitle("")
        for ((_, displayTitle) in itemsList) {
            pop.addItemWithTitle(displayTitle)
        }
        pop.setControlSize(cs)
        pop.setEnabled(nativeWidget.enabled)

        splitView.setHidden(!nativeWidget.visible)
        splitView.setAlphaValue(nativeWidget.opacity.coerceIn(0f, 1f).toDouble())
        splitView.setToolTip(nativeWidget.tooltipText)
    }

    private fun syncAll() {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { syncAll() }
            return
        }
        if (popUpButton == null && splitContainer == null) {
            mountActiveView()
            return
        }
        popUpButton?.let { syncSinglePopupState(it) }
        splitContainer?.let { syncSplitState(it) }
    }

    override fun onDropdownVariantChanged() {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { onDropdownVariantChanged() }
            return
        }
        val wantSplit = isSplitDropdownVariant()
        nativeWidget.attributes["usesAppKitAlignmentRect"] = !wantSplit
        if (wantSplit && splitContainer == null) {
            val oldView = popUpButton
            val primaryBtn = NSButton.buttonWithTitle(
                title = labelText,
                target = primaryActionTarget,
                action = primaryActionTarget.actionSelector
            ).apply { setBezelStyle(NSBezelStylePush) }
            val menuBtn = NSPopUpButton(frame = NSMakeRect(0.0, 0.0, 28.0, 32.0), pullsDown = true).apply {
                setTarget(popupActionTarget)
                setAction(popupActionTarget.actionSelector)
            }
            val nextView = MacosSplitDropdownView(primaryBtn, menuBtn)
            splitContainer = nextView
            popUpButton = null
            MacosAppKitDispatch.swapMountedView(nativeWidget, oldView, nextView, this)
            syncSplitState(nextView)
        } else if (!wantSplit && popUpButton == null) {
            val oldView = splitContainer
            val nextPop = NSPopUpButton(frame = NSMakeRect(0.0, 0.0, 140.0, 32.0), pullsDown = true).apply {
                setTarget(popupActionTarget)
                setAction(popupActionTarget.actionSelector)
            }
            popUpButton = nextPop
            splitContainer = null
            MacosAppKitDispatch.swapMountedView(nativeWidget, oldView, nextPop, this)
            syncSinglePopupState(nextPop)
        }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val pop = popUpButton
        if (pop != null) {
            syncSinglePopupState(pop)
            val fit = pop.fittingSize()
            val aligned = pop.alignmentRectForFrame(fit.useContents { NSMakeRect(0.0, 0.0, width, height) })
            return aligned.useContents {
                Size(ceil(size.width).toInt().coerceAtLeast(80), ceil(size.height).toInt().coerceAtLeast(26))
            }
        }
        val split = splitContainer
        if (split != null) {
            syncSplitState(split)
            val fit = split.primaryButton.fittingSize()
            val aligned = split.primaryButton.alignmentRectForFrame(fit.useContents { NSMakeRect(0.0, 0.0, width, height) })
            return aligned.useContents {
                Size((ceil(size.width).toInt() + 30).coerceAtLeast(96), ceil(size.height).toInt().coerceAtLeast(26))
            }
        }
        return Size(120, 32)
    }

    override fun setLabel(label: String) {
        super.setLabel(label)
        syncAll()
    }

    override fun setItems(items: List<Pair<String, String>>) {
        super.setItems(items)
        syncAll()
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { onNativeBoundsChanged(nativeWidget.bounds) }
            return
        }
        val view = nativeWidget.ensureNsViewOnMain() ?: (popUpButton ?: splitContainer) ?: return
        MacosAppKitDispatch.applyFrameAndTransform(
            view = view,
            bounds = rect,
            transform = nativeWidget.cssTransform,
            useAlignmentRectExpansion = !isSplitDropdownVariant()
        )
    }

    override fun onNativeVisibilityChanged(visible: Boolean) {
        (popUpButton ?: splitContainer)?.setHidden(!visible)
    }

    override fun onNativeEnabledChanged(enabled: Boolean) {
        syncAll()
    }

    override fun onNativeOpacityChanged(opacity: Float) {
        (popUpButton ?: splitContainer)?.setAlphaValue(opacity.coerceIn(0f, 1f).toDouble())
    }

    override fun onNativeTooltipChanged(tooltip: String?) {
        (popUpButton ?: splitContainer)?.setToolTip(tooltip)
    }

    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        (popUpButton ?: splitContainer)?.let { v -> v.window?.invalidateCursorRectsForView(v) }
    }

    override fun onNativeTransformChanged(transform: CssTransform?) {
        nativeWidget.cssTransform = transform
        val view = (popUpButton ?: splitContainer) ?: return
        MacosAppKitDispatch.applyFrameAndTransform(
            view = view,
            bounds = nativeWidget.bounds,
            transform = transform,
            useAlignmentRectExpansion = !isSplitDropdownVariant()
        )
    }

    override fun onNativeStyleSynced() {
        syncAll()
    }

    override fun dispose() {
        val pop = popUpButton
        val split = splitContainer
        popUpButton = null
        splitContainer = null
        MacosAppKitDispatch.onMainOrDefer {
            pop?.removeFromSuperview()
            split?.removeFromSuperview()
        }
        super.dispose()
    }
}
