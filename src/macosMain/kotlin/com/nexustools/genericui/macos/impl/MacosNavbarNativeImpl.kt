@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos.impl

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.layout.DesktopContainerLayoutController
import com.nexustools.genericui.desktop.widget.DesktopNavbarNativeImpl
import com.nexustools.genericui.enums.bootstrap.FlexDirection
import com.nexustools.genericui.enums.css.CssBorderStyle
import com.nexustools.genericui.geometry.EdgeInsets
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.macos.MacosAppKitAppearance
import com.nexustools.genericui.macos.MacosAppKitDispatch
import com.nexustools.genericui.macos.MacosSurfaceLayerSupport
import com.nexustools.genericui.macos.MacosViewHandle
import com.nexustools.genericui.macos.usesNativeControlAlignmentExpansion
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.style.css.StyledVisualSpec
import kotlinx.cinterop.CValue
import platform.AppKit.NSImageView
import platform.AppKit.NSTextField
import platform.AppKit.NSView
import platform.AppKit.labelWithString
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSMakeRect
import platform.Foundation.NSRect

internal class MacosNavbarView(
    frameRect: CValue<NSRect> = NSMakeRect(0.0, 0.0, 800.0, 52.0)
) : NSView(frameRect) {

    val surfaceSupport = MacosSurfaceLayerSupport(this)
    val brandIconView = NSImageView(frame = NSMakeRect(0.0, 0.0, 22.0, 22.0)).apply {
        setHidden(true)
    }
    val brandLabel = NSTextField.labelWithString("").apply {
        setHidden(true)
    }

    init {
        setWantsLayer(true)
        addSubview(brandIconView)
        addSubview(brandLabel)
    }

    override fun isFlipped(): Boolean = true

    override fun acceptsFirstMouse(event: platform.AppKit.NSEvent?): Boolean = true

    override fun viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        surfaceSupport.refreshBackdropUnderlayIfNeeded(force = true)
    }

    override fun layout() {
        super.layout()
        surfaceSupport.refreshBackdropUnderlayIfNeeded(force = false)
    }

    var isUnifiedHeaderMode: Boolean = false

    override fun mouseDown(event: platform.AppKit.NSEvent) {
        if (isUnifiedHeaderMode) {
            val localPt = convertPoint(event.locationInWindow, fromView = null)
            val hit = hitTest(superview?.convertPoint(localPt, fromView = this) ?: localPt)
            if (hit == null || hit == this) {
                if (event.clickCount >= 2L) {
                    (window as? com.nexustools.genericui.macos.MacosHostWindow)?.handleTitleBarDoubleClick()
                        ?: window?.performZoom(null)
                    return
                }
                val win = window
                if (win != null) {
                    com.nexustools.genericui.macos.MacosNativeWindowHost.performWindowDragWithCursorLock(win, event)
                    return
                }
            }
        }
        super.mouseDown(event)
    }
}

/**
 * macOS AppKit retained [NSView] bar implementation of [DesktopNavbarNativeImpl].
 */
class MacosNavbarNativeImpl(
    nativeWidget: MacosViewHandle
) : DesktopNavbarNativeImpl<MacosViewHandle>(nativeWidget) {

    private var navbarView: MacosNavbarView? = null
    internal var isUnifiedWithMacosTitlebar: Boolean = false
        private set

    init {
        val controller = DesktopContainerLayoutController(
            host = this,
            defaultFlexDirection = FlexDirection.ROW,
            defaultGapPx = 10,
            defaultPadding = EdgeInsets.symmetric(horizontal = 16, vertical = 8)
        )
        controller.onZOrderUpdated = { ordered ->
            syncSubviews(ordered)
        }
        layoutController = controller
        nativeWidget.viewMaterializer = {
            ensureNavbarView().also { syncNavbarVisuals() }
        }
        if (MacosAppKitDispatch.isMainThread()) {
            ensureNavbarView()
            syncNavbarVisuals()
        }
    }

    internal fun setUnifiedWithMacosTitlebar(unified: Boolean) {
        if (isUnifiedWithMacosTitlebar == unified &&
            nativeWidget.attributes["unifiedWithMacosTitlebar"] == unified
        ) {
            return
        }
        isUnifiedWithMacosTitlebar = unified
        nativeWidget.attributes["unifiedWithMacosTitlebar"] = unified
        navbarView?.isUnifiedHeaderMode = unified
        if (MacosAppKitDispatch.isMainThread()) {
            syncNavbarVisuals()
        } else {
            MacosAppKitDispatch.onMainOrDefer { syncNavbarVisuals() }
        }
    }

    private fun ensureNavbarView(): MacosNavbarView {
        navbarView?.let { return it }
        val v = MacosNavbarView()
        navbarView = v
        nativeWidget.nsView = v
        v.setHidden(!nativeWidget.visible)
        v.setAlphaValue(nativeWidget.opacity.coerceIn(0f, 1f).toDouble())
        v.setToolTip(nativeWidget.tooltipText)
        return v
    }

    private fun syncNavbarVisuals() {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { syncNavbarVisuals() }
            return
        }
        val v = ensureNavbarView()
        v.isUnifiedHeaderMode = isUnifiedWithMacosTitlebar
        val darkTheme = MacosAppKitAppearance.isDarkTheme(nativeWidget)
        if (isUnifiedWithMacosTitlebar) {
            v.surfaceSupport.applyStyledVisuals(
                spec = StyledVisualSpec(
                    backgroundColor = null,
                    borderColor = null,
                    borderWidthPx = 0f,
                    borderStyle = CssBorderStyle.NONE
                ),
                cornerRadii = null,
                boxShadow = null,
                isFocused = false,
                clipOverflow = false,
                darkTheme = darkTheme,
                effectiveBackdropFilter = null,
                effectiveForegroundFilter = null
            )
            v.layer?.backgroundColor = null
            v.layer?.borderWidth = 0.0
        } else {
            val bg = lastComputedStyle?.backgroundColor
                ?: if (darkTheme) RgbaColor(32, 34, 40, 235) else RgbaColor(246, 247, 250, 235)
            val border = if (darkTheme) RgbaColor(60, 64, 72, 255) else RgbaColor(220, 224, 230, 255)
            v.surfaceSupport.applyStyledVisuals(
                spec = StyledVisualSpec(
                    backgroundColor = bg,
                    borderColor = border,
                    borderWidthPx = 1f,
                    borderStyle = CssBorderStyle.SOLID
                ),
                cornerRadii = lastComputedStyle?.borderRadius,
                boxShadow = null,
                isFocused = false,
                clipOverflow = false,
                darkTheme = darkTheme,
                effectiveBackdropFilter = lastComputedStyle?.backdropFilter,
                effectiveForegroundFilter = lastComputedStyle?.filter
            )
        }

        val showBrand = isBrandVisible()
        if (showBrand && brandTitleText.isNotEmpty()) {
            v.brandLabel.setHidden(false)
            val font = MacosAppKitAppearance.resolveFont(lastComputedStyle, styleSnapshot, 14.0, defaultBold = true)
            v.brandLabel.setAttributedStringValue(
                MacosAppKitAppearance.buildAttributedString(
                    text = brandTitleText,
                    style = lastComputedStyle,
                    textColor = if (darkTheme) RgbaColor.WHITE else RgbaColor(20, 20, 24, 255),
                    defaultFont = font
                )
            )
            val iconImg = brandIconSpec?.let { MacosAppKitAppearance.resolveNsImage(it, desiredPx = 22) }
            if (iconImg != null) {
                v.brandIconView.setImage(iconImg)
                v.brandIconView.setHidden(false)
            } else {
                v.brandIconView.setHidden(true)
            }
            v.brandIconView.setFrame(CGRectMake(12.0, 15.0, 22.0, 22.0))
            v.brandLabel.setFrame(CGRectMake(if (iconImg != null) 40.0 else 12.0, 16.0, 180.0, 20.0))
        } else {
            v.brandLabel.setHidden(true)
            v.brandIconView.setHidden(true)
        }
        syncSubviews(
            layoutController?.resolveChildrenSortedByZIndex()
                ?: getLogicalChildren().filterIsInstance<DesktopBaseNativeImpl<*>>()
        )
    }

    private fun syncSubviews(ordered: List<DesktopBaseNativeImpl<*>>) {
        val v = ensureNavbarView()
        val backdropPinned = v.surfaceSupport.pinnedBackdropSubviews()
        val pinned = ArrayList<NSView>(backdropPinned.size + 2).apply {
            addAll(backdropPinned)
            add(v.brandIconView)
            add(v.brandLabel)
        }
        val children = ordered.mapNotNull { MacosAppKitDispatch.resolveOrCreateChildNsView(it) }
        MacosAppKitDispatch.syncChildViewHierarchy(v, children, pinned)
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
        layoutController?.measurePreferredSize(maxWidth, maxHeight) ?: Size((maxWidth?.toInt() ?: 600), 52)

    override fun setBrandTitle(title: String) {
        super.setBrandTitle(title)
        syncNavbarVisuals()
    }

    override fun setBrandIcon(icon: String?) {
        super.setBrandIcon(icon)
        syncNavbarVisuals()
    }

    override fun setShowBrand(show: Boolean?) {
        super.setShowBrand(show)
        syncNavbarVisuals()
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { onNativeBoundsChanged(nativeWidget.bounds) }
            return
        }
        val v = (nativeWidget.ensureNsViewOnMain() as? MacosNavbarView) ?: navbarView ?: return
        val parentOffX = (nativeWidget.parentWidget?.attributes?.get("contentOffsetX") as? Int) ?: 0
        val parentOffY = (nativeWidget.parentWidget?.attributes?.get("contentOffsetY") as? Int) ?: 0
        val adjusted = if (parentOffX != 0 || parentOffY != 0) {
            Rect(rect.x + parentOffX, rect.y + parentOffY, rect.width, rect.height)
        } else {
            rect
        }
        MacosAppKitDispatch.applyFrameAndTransform(v, adjusted, nativeWidget.cssTransform, useAlignmentRectExpansion = false)
        syncNavbarVisuals()
    }

    override fun onNativeVisibilityChanged(visible: Boolean) {
        navbarView?.setHidden(!visible)
        nativeWidget.requestRetainedRelayout()
    }

    override fun onNativeOpacityChanged(opacity: Float) {
        navbarView?.setAlphaValue(opacity.coerceIn(0f, 1f).toDouble())
    }

    override fun onNativeTooltipChanged(tooltip: String?) {
        navbarView?.setToolTip(tooltip)
    }

    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        navbarView?.let { v -> v.window?.invalidateCursorRectsForView(v) }
    }

    override fun onNativeTransformChanged(transform: CssTransform?) {
        nativeWidget.cssTransform = transform
        val v = navbarView ?: return
        MacosAppKitDispatch.applyFrameAndTransform(v, nativeWidget.bounds, transform, useAlignmentRectExpansion = false)
    }

    override fun onNativeStyleSynced() {
        syncNavbarVisuals()
    }

    override fun dispose() {
        val view = navbarView
        navbarView = null
        MacosAppKitDispatch.detachViewOnMain(view)
        super.dispose()
    }
}
