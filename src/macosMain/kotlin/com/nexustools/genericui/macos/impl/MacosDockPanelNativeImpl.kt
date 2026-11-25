@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos.impl

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.dock.DesktopDockPanelNativeImpl
import com.nexustools.genericui.desktop.layout.DesktopContainerLayoutController
import com.nexustools.genericui.enums.bootstrap.FlexDirection
import com.nexustools.genericui.enums.css.CssBorderStyle
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.macos.MacosAppKitAppearance
import com.nexustools.genericui.macos.MacosAppKitDispatch
import com.nexustools.genericui.macos.MacosControlActionTarget
import com.nexustools.genericui.macos.MacosSurfaceLayerSupport
import com.nexustools.genericui.macos.MacosViewHandle
import com.nexustools.genericui.macos.usesNativeControlAlignmentExpansion
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.style.css.StyledVisualSpec
import kotlinx.cinterop.CValue
import kotlinx.cinterop.useContents
import platform.AppKit.NSButton
import platform.AppKit.NSFont
import platform.AppKit.NSFontWeightSemibold
import platform.AppKit.NSTextField
import platform.AppKit.NSView
import platform.AppKit.labelWithString
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSMakeRect
import platform.Foundation.NSRect

internal class MacosDockPanelView(
    onToggleCollapse: () -> Unit,
    frameRect: CValue<NSRect> = NSMakeRect(0.0, 0.0, 260.0, 220.0)
) : NSView(frameRect) {

    val surfaceSupport = MacosSurfaceLayerSupport(this)
    val headerBar = NSView(NSMakeRect(0.0, 0.0, 260.0, 28.0)).apply {
        setWantsLayer(true)
    }
    private val toggleTarget = MacosControlActionTarget { onToggleCollapse() }
    val collapseBtn = NSButton.buttonWithTitle("▾", target = toggleTarget, action = toggleTarget.actionSelector).apply {
        setBordered(false)
    }
    val titleField = NSTextField.labelWithString("").apply {
        setFont(NSFont.systemFontOfSize(12.0, weight = NSFontWeightSemibold))
    }
    val bodyHost = MacosFlippedDockView(NSMakeRect(0.0, 28.0, 260.0, 192.0))

    init {
        setWantsLayer(true)
        headerBar.addSubview(collapseBtn)
        headerBar.addSubview(titleField)
        addSubview(headerBar)
        addSubview(bodyHost)
    }

    override fun isFlipped(): Boolean = true
}

/**
 * macOS AppKit retained panel view implementation of [DesktopDockPanelNativeImpl].
 */
class MacosDockPanelNativeImpl(
    nativeWidget: MacosViewHandle
) : DesktopDockPanelNativeImpl<MacosViewHandle>(nativeWidget) {

    private var panelView: MacosDockPanelView? = null

    init {
        val controller = DesktopContainerLayoutController(
            host = this,
            defaultFlexDirection = FlexDirection.COLUMN
        )
        controller.onZOrderUpdated = { ordered -> syncBodyChildren(ordered) }
        layoutController = controller
        nativeWidget.viewMaterializer = {
            ensurePanelView().also { syncPanelAppearance() }
        }
        if (MacosAppKitDispatch.isMainThread()) {
            ensurePanelView()
            syncPanelAppearance()
        }
    }

    private fun ensurePanelView(): MacosDockPanelView {
        panelView?.let { return it }
        val pv = MacosDockPanelView(onToggleCollapse = { toggleCollapsed() })
        panelView = pv
        nativeWidget.nsView = pv
        pv.setHidden(!nativeWidget.visible)
        pv.setAlphaValue(nativeWidget.opacity.coerceIn(0f, 1f).toDouble())
        pv.setToolTip(nativeWidget.tooltipText)
        return pv
    }

    private fun syncPanelAppearance() {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { syncPanelAppearance() }
            return
        }
        val pv = ensurePanelView()
        val darkTheme = MacosAppKitAppearance.isDarkTheme(nativeWidget)
        val bg = if (darkTheme) RgbaColor(36, 38, 44, 255) else RgbaColor(248, 249, 252, 255)
        val border = if (darkTheme) RgbaColor(62, 66, 74, 255) else RgbaColor(214, 218, 226, 255)
        pv.surfaceSupport.applyStyledVisuals(
            spec = StyledVisualSpec(
                backgroundColor = bg,
                borderColor = border,
                borderWidthPx = 1f,
                borderStyle = CssBorderStyle.SOLID,
                borderRadiusPx = 6f
            ),
            cornerRadii = null,
            boxShadow = null,
            isFocused = false,
            clipOverflow = true,
            darkTheme = darkTheme,
            effectiveBackdropFilter = null,
            effectiveForegroundFilter = null
        )
        pv.titleField.setStringValue(titleText)
        pv.collapseBtn.setTitle(if (collapsedState) "▸" else "▾")
        pv.bodyHost.setHidden(collapsedState)
        layoutPanelParts()
    }

    private fun layoutPanelParts() {
        val pv = panelView ?: return
        val (w, h) = pv.bounds.useContents { size.width to size.height }
        val headerH = 28.0.coerceAtMost(h)
        pv.headerBar.setFrame(CGRectMake(0.0, 0.0, w, headerH))
        pv.collapseBtn.setFrame(CGRectMake(4.0, 4.0, 20.0, 20.0))
        pv.titleField.setFrame(CGRectMake(26.0, 5.0, (w - 34.0).coerceAtLeast(0.0), 18.0))
        pv.bodyHost.setFrame(CGRectMake(0.0, headerH, w, (h - headerH).coerceAtLeast(0.0)))
    }

    private fun syncBodyChildren(ordered: List<DesktopBaseNativeImpl<*>>) {
        val pv = ensurePanelView()
        val views = ordered.mapNotNull { (it.nativeWidget as? MacosViewHandle)?.ensureNsViewOnMain() }
        MacosAppKitDispatch.syncChildViewHierarchy(pv.bodyHost, views)
    }

    override fun setTitle(title: String) {
        super.setTitle(title)
        syncPanelAppearance()
    }

    override fun setCollapsed(collapsed: Boolean) {
        super.setCollapsed(collapsed)
        syncPanelAppearance()
        nativeWidget.requestRetainedRelayout()
    }

    override fun onChildAddedNative(childImpl: NativeImpl<*>, index: Int) {
        super.onChildAddedNative(childImpl, index)
        if (MacosAppKitDispatch.isMainThread()) {
            syncBodyChildren(getLogicalChildren().filterIsInstance<DesktopBaseNativeImpl<*>>())
        }
        nativeWidget.requestRetainedRelayout()
    }

    override fun onChildRemovedNative(childImpl: NativeImpl<*>) {
        super.onChildRemovedNative(childImpl)
        if (MacosAppKitDispatch.isMainThread()) {
            syncBodyChildren(getLogicalChildren().filterIsInstance<DesktopBaseNativeImpl<*>>())
        }
        nativeWidget.requestRetainedRelayout()
    }

    override fun layoutChildrenInBounds(width: Float, height: Float) {
        val headerH = 28f
        val bodyH = if (collapsedState) 0f else (height - headerH).coerceAtLeast(0f)
        layoutController?.layoutInBounds(width, bodyH)
        if (!MacosAppKitDispatch.isMainThread()) return
        layoutPanelParts()
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
        syncBodyChildren(ordered)
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        if (collapsedState) return Size((maxWidth?.toInt() ?: 240).coerceAtLeast(160), 28)
        val inner = layoutController?.measurePreferredSize(maxWidth, maxHeight) ?: Size(240, 180)
        return Size(inner.width.coerceAtLeast(160), inner.height + 28)
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { onNativeBoundsChanged(nativeWidget.bounds) }
            return
        }
        val pv = (nativeWidget.ensureNsViewOnMain() as? MacosDockPanelView) ?: panelView ?: return
        MacosAppKitDispatch.applyFrameAndTransform(pv, rect, nativeWidget.cssTransform, useAlignmentRectExpansion = false)
        syncPanelAppearance()
    }

    override fun onNativeVisibilityChanged(visible: Boolean) {
        panelView?.setHidden(!visible)
    }

    override fun onNativeOpacityChanged(opacity: Float) {
        panelView?.setAlphaValue(opacity.coerceIn(0f, 1f).toDouble())
    }

    override fun onNativeTooltipChanged(tooltip: String?) {
        panelView?.setToolTip(tooltip)
    }

    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        panelView?.let { v -> v.window?.invalidateCursorRectsForView(v) }
    }

    override fun onNativeTransformChanged(transform: CssTransform?) {
        nativeWidget.cssTransform = transform
        val pv = panelView ?: return
        MacosAppKitDispatch.applyFrameAndTransform(pv, nativeWidget.bounds, transform, useAlignmentRectExpansion = false)
    }

    override fun onNativeStyleSynced() {
        syncPanelAppearance()
    }

    override fun dispose() {
        val view = panelView
        panelView = null
        MacosAppKitDispatch.detachViewOnMain(view)
        super.dispose()
    }
}
