@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos.impl

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.dock.DesktopDockWorkspaceNativeImpl
import com.nexustools.genericui.desktop.layout.DesktopContainerLayoutController
import com.nexustools.genericui.enums.bootstrap.FlexDirection
import com.nexustools.genericui.enums.dock.DockPosition
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.macos.MacosAppKitDispatch
import com.nexustools.genericui.macos.MacosViewHandle
import com.nexustools.genericui.macos.usesNativeControlAlignmentExpansion
import com.nexustools.genericui.nativeimpl.dock.DockAreaNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.CValue
import platform.AppKit.NSView
import platform.Foundation.NSMakeRect
import platform.Foundation.NSRect

internal class MacosFlippedDockView(
    frameRect: CValue<NSRect> = NSMakeRect(0.0, 0.0, 0.0, 0.0)
) : NSView(frameRect) {
    init {
        setWantsLayer(true)
    }
    override fun isFlipped(): Boolean = true
}

/**
 * macOS AppKit retained [NSView] implementation of [DesktopDockWorkspaceNativeImpl].
 */
class MacosDockWorkspaceNativeImpl(
    nativeWidget: MacosViewHandle
) : DesktopDockWorkspaceNativeImpl<MacosViewHandle>(nativeWidget) {

    private var workspaceView: MacosFlippedDockView? = null

    init {
        val controller = DesktopContainerLayoutController(
            host = this,
            defaultFlexDirection = FlexDirection.ROW
        )
        controller.onZOrderUpdated = { ordered ->
            syncSubviews(ordered)
        }
        layoutController = controller
        nativeWidget.viewMaterializer = { ensureView() }
        if (MacosAppKitDispatch.isMainThread()) {
            ensureView()
        }
    }

    private fun ensureView(): MacosFlippedDockView {
        workspaceView?.let { return it }
        val v = MacosFlippedDockView()
        workspaceView = v
        nativeWidget.nsView = v
        v.setHidden(!nativeWidget.visible)
        v.setAlphaValue(nativeWidget.opacity.coerceIn(0f, 1f).toDouble())
        v.setToolTip(nativeWidget.tooltipText)
        return v
    }

    private fun syncSubviews(ordered: List<DesktopBaseNativeImpl<*>>) {
        val v = ensureView()
        val views = ordered.mapNotNull { (it.nativeWidget as? MacosViewHandle)?.ensureNsViewOnMain() }
        MacosAppKitDispatch.syncChildViewHierarchy(v, views)
    }

    override fun setCentralDocumentNative(centralImpl: NativeImpl<*>?) {
        val previous = centralDocument
        if (previous != null && previous !== centralImpl) {
            removeChildNative(previous)
        }
        super.setCentralDocumentNative(centralImpl)
        if (centralImpl != null && centralImpl !in getLogicalChildren()) {
            addChildNative(centralImpl)
        }
    }

    override fun attachDockAreaNative(position: DockPosition, areaImpl: DockAreaNativeImpl<*>) {
        super.attachDockAreaNative(position, areaImpl)
        if (MacosAppKitDispatch.isMainThread()) {
            syncSubviews(getLogicalChildren().filterIsInstance<DesktopBaseNativeImpl<*>>())
        }
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

    override fun layoutChildrenInBounds(width: Float, height: Float) {
        layoutController?.layoutInBounds(width, height)
        if (!MacosAppKitDispatch.isMainThread()) return
        val ordered = layoutController?.resolveChildrenSortedByZIndex()
            ?: getLogicalChildren().filterIsInstance<DesktopBaseNativeImpl<*>>()
        for (child in ordered) {
            val childHandle = child.nativeWidget as? MacosViewHandle ?: continue
            val cv = childHandle.ensureNsViewOnMain() ?: continue
            MacosAppKitDispatch.applyFrameAndTransform(
                view = cv,
                bounds = child.nativeWidget.bounds,
                transform = childHandle.cssTransform,
                useAlignmentRectExpansion = child.usesNativeControlAlignmentExpansion()
            )
        }
        syncSubviews(ordered)
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size =
        layoutController?.measurePreferredSize(maxWidth, maxHeight) ?: super.measureIntrinsicSize(maxWidth, maxHeight)

    override fun onNativeBoundsChanged(rect: Rect) {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { onNativeBoundsChanged(nativeWidget.bounds) }
            return
        }
        val v = (nativeWidget.ensureNsViewOnMain() as? MacosFlippedDockView) ?: workspaceView ?: return
        MacosAppKitDispatch.applyFrameAndTransform(v, rect, nativeWidget.cssTransform, useAlignmentRectExpansion = false)
    }

    override fun onNativeVisibilityChanged(visible: Boolean) {
        workspaceView?.setHidden(!visible)
    }

    override fun onNativeOpacityChanged(opacity: Float) {
        workspaceView?.setAlphaValue(opacity.coerceIn(0f, 1f).toDouble())
    }

    override fun onNativeTooltipChanged(tooltip: String?) {
        workspaceView?.setToolTip(tooltip)
    }

    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        workspaceView?.let { v -> v.window?.invalidateCursorRectsForView(v) }
    }

    override fun onNativeTransformChanged(transform: CssTransform?) {
        nativeWidget.cssTransform = transform
        val v = workspaceView ?: return
        MacosAppKitDispatch.applyFrameAndTransform(v, nativeWidget.bounds, transform, useAlignmentRectExpansion = false)
    }

    override fun dispose() {
        val view = workspaceView
        workspaceView = null
        MacosAppKitDispatch.detachViewOnMain(view)
        super.dispose()
    }
}
