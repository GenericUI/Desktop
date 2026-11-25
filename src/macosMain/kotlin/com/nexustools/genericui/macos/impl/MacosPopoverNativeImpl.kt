@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos.impl

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.layout.DesktopContainerLayoutController
import com.nexustools.genericui.desktop.widget.DesktopPopoverNativeImpl
import com.nexustools.genericui.enums.bootstrap.FlexDirection
import com.nexustools.genericui.enums.ui.Placement
import com.nexustools.genericui.geometry.EdgeInsets
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.macos.MacosAppKitDispatch
import com.nexustools.genericui.macos.MacosViewHandle
import com.nexustools.genericui.macos.usesNativeControlAlignmentExpansion
import kotlinx.cinterop.CValue
import kotlinx.cinterop.useContents
import platform.AppKit.NSFont
import platform.AppKit.NSFontWeightSemibold
import platform.AppKit.NSPopover
import platform.AppKit.NSPopoverBehaviorTransient
import platform.AppKit.NSPopoverDelegateProtocol
import platform.AppKit.NSTextField
import platform.AppKit.NSView
import platform.AppKit.NSViewController
import platform.AppKit.labelWithString
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSMakeRect
import platform.Foundation.NSMakeSize
import platform.Foundation.NSMaxXEdge
import platform.Foundation.NSMaxYEdge
import platform.Foundation.NSMinXEdge
import platform.Foundation.NSMinYEdge
import platform.Foundation.NSNotification
import platform.Foundation.NSRect
import platform.darwin.NSObject

internal class MacosPopoverContentView(
    frameRect: CValue<NSRect> = NSMakeRect(0.0, 0.0, 260.0, 140.0)
) : NSView(frameRect) {

    val titleLabel = NSTextField.labelWithString("").apply {
        setFont(NSFont.systemFontOfSize(13.0, weight = NSFontWeightSemibold))
        setHidden(true)
    }

    init {
        addSubview(titleLabel)
    }

    override fun isFlipped(): Boolean = true
}

internal class MacosPopoverDelegateBridge(
    private val onDidClose: () -> Unit
) : NSObject(), NSPopoverDelegateProtocol {
    override fun popoverDidClose(notification: NSNotification) {
        onDidClose()
    }
}

/**
 * macOS AppKit retained [NSPopover] implementation of [DesktopPopoverNativeImpl].
 */
class MacosPopoverNativeImpl(
    nativeWidget: MacosViewHandle
) : DesktopPopoverNativeImpl<MacosViewHandle>(nativeWidget) {

    private var popover: NSPopover? = null
    private var viewController: NSViewController? = null
    private var contentView: MacosPopoverContentView? = null
    private var popoverDelegate: MacosPopoverDelegateBridge? = null

    init {
        val controller = DesktopContainerLayoutController(
            host = this,
            defaultFlexDirection = FlexDirection.COLUMN,
            defaultPadding = EdgeInsets.all(10)
        )
        controller.onZOrderUpdated = { ordered ->
            syncPopoverChildren(ordered)
        }
        layoutController = controller
        nativeWidget.viewMaterializer = {
            ensurePopover()
            contentView
        }
        if (MacosAppKitDispatch.isMainThread()) {
            ensurePopover()
        }
    }

    private fun ensurePopover(): NSPopover {
        popover?.let { return it }
        val cv = MacosPopoverContentView(NSMakeRect(0.0, 0.0, 260.0, 140.0))
        val vc = NSViewController()
        vc.setView(cv)

        val del = MacosPopoverDelegateBridge {
            if (isVisible()) {
                hide()
            }
        }
        val pop = NSPopover().apply {
            setBehavior(NSPopoverBehaviorTransient)
            setContentViewController(vc)
            setDelegate(del)
        }
        contentView = cv
        viewController = vc
        popoverDelegate = del
        popover = pop
        nativeWidget.nsView = cv
        return pop
    }

    private fun syncPopoverChildren(ordered: List<DesktopBaseNativeImpl<*>>) {
        val cv = contentView ?: run {
            ensurePopover()
            contentView ?: return
        }
        val pinned: List<NSView> = listOf(cv.titleLabel)
        val views = ordered.mapNotNull { (it.nativeWidget as? MacosViewHandle)?.ensureNsViewOnMain() }
        MacosAppKitDispatch.syncChildViewHierarchy(cv, views, pinned)
    }

    override fun onChildAddedNative(childImpl: NativeImpl<*>, index: Int) {
        super.onChildAddedNative(childImpl, index)
        if (MacosAppKitDispatch.isMainThread()) {
            syncPopoverChildren(getLogicalChildren().filterIsInstance<DesktopBaseNativeImpl<*>>())
        }
    }

    override fun onChildRemovedNative(childImpl: NativeImpl<*>) {
        super.onChildRemovedNative(childImpl)
        if (MacosAppKitDispatch.isMainThread()) {
            syncPopoverChildren(getLogicalChildren().filterIsInstance<DesktopBaseNativeImpl<*>>())
        }
    }

    override fun layoutChildrenInBounds(width: Float, height: Float) {
        val popW = width.coerceAtLeast(160f)
        val popH = height.coerceAtLeast(64f)
        val cv = contentView
        val titleOffset = if (!titleText.isNullOrBlank()) 26f else 0f
        if (cv != null) {
            if (titleOffset > 0f) {
                cv.titleLabel.setHidden(false)
                cv.titleLabel.setStringValue(titleText ?: "")
                cv.titleLabel.setFrame(CGRectMake(10.0, 8.0, (popW - 20f).toDouble(), 18.0))
            } else {
                cv.titleLabel.setHidden(true)
            }
        }
        layoutController?.layoutInBounds(popW, (popH - titleOffset).coerceAtLeast(32f))
        if (MacosAppKitDispatch.isMainThread()) {
            val ordered = layoutController?.resolveChildrenSortedByZIndex()
                ?: getLogicalChildren().filterIsInstance<DesktopBaseNativeImpl<*>>()
            for (child in ordered) {
                val b = child.nativeWidget.bounds
                val shifted = Rect(b.x, b.y + titleOffset.toInt(), b.width, b.height)
                val childHandle = child.nativeWidget as? MacosViewHandle ?: continue
                val v = childHandle.ensureNsViewOnMain() ?: continue
                MacosAppKitDispatch.applyFrameAndTransform(
                    view = v,
                    bounds = shifted,
                    transform = childHandle.cssTransform,
                    useAlignmentRectExpansion = child.usesNativeControlAlignmentExpansion()
                )
            }
            syncPopoverChildren(ordered)
        }
    }

    override fun setTitle(title: String?) {
        super.setTitle(title)
        contentView?.let { cv ->
            if (!title.isNullOrBlank()) {
                cv.titleLabel.setStringValue(title)
                cv.titleLabel.setHidden(false)
            } else {
                cv.titleLabel.setHidden(true)
            }
        }
    }

    override fun showAnchored(anchor: NativeImpl<*>?, x: Int, y: Int) {
        super.showAnchored(anchor, x, y)
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { showAnchored(anchor, x, y) }
            return
        }
        val pop = ensurePopover()
        val measured = layoutController?.measurePreferredSize(360f, 480f) ?: Size(260, 140)
        val w = measured.width.coerceIn(160, 480)
        val h = (measured.height + if (!titleText.isNullOrBlank()) 26 else 0).coerceIn(72, 520)
        pop.setContentSize(NSMakeSize(w.toDouble(), h.toDouble()))
        layoutChildrenInBounds(w.toFloat(), h.toFloat())

        val anchorView = (anchor?.nativeWidget as? MacosViewHandle)?.nsView
        if (anchorView != null && anchorView.window != null) {
            val rect = anchorView.bounds.useContents {
                if (size.width > 0.0 && size.height > 0.0) {
                    CGRectMake(0.0, 0.0, size.width, size.height)
                } else {
                    CGRectMake(x.toDouble(), y.toDouble(), 1.0, 1.0)
                }
            }
            val preferredEdge = when (placementState) {
                Placement.TOP, Placement.TOP_START, Placement.TOP_END -> NSMinYEdge
                Placement.BOTTOM, Placement.BOTTOM_START, Placement.BOTTOM_END -> NSMaxYEdge
                Placement.START -> NSMinXEdge
                Placement.END -> NSMaxXEdge
            }
            pop.showRelativeToRect(rect, ofView = anchorView, preferredEdge = preferredEdge)
        }
    }

    override fun hide() {
        popover?.let { pop ->
            if (pop.isShown()) {
                pop.performClose(null)
            }
        }
        super.hide()
    }

    override fun dispose() {
        val pop = popover
        popover = null
        viewController = null
        contentView = null
        popoverDelegate = null
        MacosAppKitDispatch.onMainOrDefer {
            pop?.setDelegate(null)
            pop?.close()
        }
        super.dispose()
    }
}
