@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos.impl

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.layout.DesktopContainerLayoutController
import com.nexustools.genericui.desktop.widget.DesktopBookContainerNativeImpl
import com.nexustools.genericui.enums.bootstrap.FlexDirection
import com.nexustools.genericui.enums.css.CssBorderStyle
import com.nexustools.genericui.enums.document.PrintPageOrientation
import com.nexustools.genericui.enums.document.PrintPageSize
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.macos.MacosAppKitAppearance
import com.nexustools.genericui.macos.MacosAppKitDispatch
import com.nexustools.genericui.macos.MacosApplicationNativeImpl
import com.nexustools.genericui.macos.MacosSurfaceLayerSupport
import com.nexustools.genericui.macos.MacosViewHandle
import com.nexustools.genericui.macos.usesNativeControlAlignmentExpansion
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.style.css.StyledVisualSpec
import kotlinx.cinterop.CValue
import platform.AppKit.NSPrintOperation
import platform.AppKit.NSView
import platform.Foundation.NSMakeRect
import platform.Foundation.NSRect

internal class MacosBookRootView(
    frameRect: CValue<NSRect> = NSMakeRect(0.0, 0.0, 794.0, 1123.0)
) : NSView(frameRect) {

    val surfaceSupport = MacosSurfaceLayerSupport(this)

    init {
        setWantsLayer(true)
    }

    override fun isFlipped(): Boolean = true
}

/**
 * macOS AppKit retained document [NSView] and `NSPrintOperation` implementation of
 * [DesktopBookContainerNativeImpl].
 */
class MacosBookContainerNativeImpl(
    nativeWidget: MacosViewHandle
) : DesktopBookContainerNativeImpl<MacosViewHandle>(nativeWidget) {

    private var bookView: MacosBookRootView? = null

    init {
        val controller = DesktopContainerLayoutController(
            host = this,
            defaultFlexDirection = FlexDirection.COLUMN
        )
        controller.onZOrderUpdated = { ordered ->
            syncSubviews(ordered)
        }
        layoutController = controller
        nativeWidget.viewMaterializer = {
            ensureBookView().also { syncBookSurface() }
        }
        if (MacosAppKitDispatch.isMainThread()) {
            ensureBookView()
            syncBookSurface()
        }
    }

    private fun ensureBookView(): MacosBookRootView {
        bookView?.let { return it }
        val v = MacosBookRootView()
        bookView = v
        nativeWidget.nsView = v
        v.setHidden(!nativeWidget.visible)
        v.setAlphaValue(nativeWidget.opacity.coerceIn(0f, 1f).toDouble())
        v.setToolTip(nativeWidget.tooltipText)
        return v
    }

    private fun syncBookSurface() {
        val v = bookView ?: return
        val darkTheme = MacosAppKitAppearance.isDarkTheme(nativeWidget)
        val bg = lastComputedStyle?.backgroundColor
            ?: if (darkTheme) RgbaColor(42, 44, 50, 255) else RgbaColor.WHITE
        val border = if (darkTheme) RgbaColor(68, 72, 82, 255) else RgbaColor(216, 220, 228, 255)
        v.surfaceSupport.applyStyledVisuals(
            spec = StyledVisualSpec(
                backgroundColor = bg,
                borderColor = border,
                borderWidthPx = 1f,
                borderStyle = CssBorderStyle.SOLID
            ),
            cornerRadii = lastComputedStyle?.borderRadius,
            boxShadow = lastComputedStyle?.boxShadowList?.shadows?.firstOrNull(),
            isFocused = false,
            clipOverflow = false,
            darkTheme = darkTheme,
            effectiveBackdropFilter = null,
            effectiveForegroundFilter = null
        )
    }

    private fun syncSubviews(ordered: List<DesktopBaseNativeImpl<*>>) {
        val v = ensureBookView()
        val views = ordered.mapNotNull { (it.nativeWidget as? MacosViewHandle)?.ensureNsViewOnMain() }
        MacosAppKitDispatch.syncChildViewHierarchy(v, views)
    }

    override fun onChildAddedNative(childImpl: NativeImpl<*>, index: Int) {
        super.onChildAddedNative(childImpl, index)
        if (MacosAppKitDispatch.isMainThread()) {
            syncSubviews(getLogicalChildren().filterIsInstance<DesktopBaseNativeImpl<*>>())
        }
        nativeWidget.requestRetainedRelayout()
    }

    override fun onChildRemovedNative(childImpl: NativeImpl<*>) {
        super.onChildRemovedNative(childImpl)
        if (MacosAppKitDispatch.isMainThread()) {
            syncSubviews(getLogicalChildren().filterIsInstance<DesktopBaseNativeImpl<*>>())
        }
        nativeWidget.requestRetainedRelayout()
    }

    override fun onChildrenClearedNative() {
        super.onChildrenClearedNative()
        if (MacosAppKitDispatch.isMainThread()) {
            syncSubviews(emptyList())
        }
        nativeWidget.requestRetainedRelayout()
    }

    override fun layoutChildrenInBounds(width: Float, height: Float) {
        layoutController?.layoutInBounds(width, height)
        if (!MacosAppKitDispatch.isMainThread()) return
        val ordered = layoutController?.resolveChildrenSortedByZIndex()
            ?: getLogicalChildren().filterIsInstance<DesktopBaseNativeImpl<*>>()
        for (child in ordered) {
            val childHandle = child.nativeWidget as? MacosViewHandle ?: continue
            val childView = childHandle.ensureNsViewOnMain() ?: continue
            MacosAppKitDispatch.applyFrameAndTransform(
                view = childView,
                bounds = child.nativeWidget.bounds,
                transform = childHandle.cssTransform,
                useAlignmentRectExpansion = child.usesNativeControlAlignmentExpansion()
            )
        }
        syncSubviews(ordered)
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size =
        layoutController?.measurePreferredSize(maxWidth, maxHeight) ?: super.measureIntrinsicSize(maxWidth, maxHeight)

    override fun printBook(
        documentTitle: String,
        pageSize: PrintPageSize,
        orientation: PrintPageOrientation,
        marginMm: Float
    ): Boolean {
        val ok = super.printBook(documentTitle, pageSize, orientation, marginMm)
        val view = bookView
        if (ok && MacosApplicationNativeImpl.interactiveMacosGuiEnabled && view != null && view.window != null) {
            val op = NSPrintOperation.printOperationWithView(view)
            op.setJobTitle(documentTitle)
            op.runOperation()
        }
        return ok
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { onNativeBoundsChanged(nativeWidget.bounds) }
            return
        }
        val v = (nativeWidget.ensureNsViewOnMain() as? MacosBookRootView) ?: bookView ?: return
        MacosAppKitDispatch.applyFrameAndTransform(v, rect, nativeWidget.cssTransform, useAlignmentRectExpansion = false)
        syncBookSurface()
    }

    override fun onNativeVisibilityChanged(visible: Boolean) {
        bookView?.setHidden(!visible)
    }

    override fun onNativeOpacityChanged(opacity: Float) {
        bookView?.setAlphaValue(opacity.coerceIn(0f, 1f).toDouble())
    }

    override fun onNativeTooltipChanged(tooltip: String?) {
        bookView?.setToolTip(tooltip)
    }

    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        bookView?.let { v -> v.window?.invalidateCursorRectsForView(v) }
    }

    override fun onNativeTransformChanged(transform: CssTransform?) {
        nativeWidget.cssTransform = transform
        val v = bookView ?: return
        MacosAppKitDispatch.applyFrameAndTransform(v, nativeWidget.bounds, transform, useAlignmentRectExpansion = false)
    }

    override fun onNativeStyleSynced() {
        syncBookSurface()
    }

    override fun dispose() {
        val view = bookView
        bookView = null
        MacosAppKitDispatch.detachViewOnMain(view)
        super.dispose()
    }
}
