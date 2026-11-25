@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.widget.DesktopStyledContainerNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.gtk.widget.GtkInteractiveSurfaceWidget
import com.nexustools.genericui.gtk.widget.GtkRetainedContainerSupport
import com.nexustools.genericui.gtk.widget.GtkSurfaceRenderHelper
import com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge
import com.nexustools.genericui.linux.style.LinuxBackdropFilterController
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.style.css.StyledVisualSpec
import kotlinx.cinterop.COpaquePointer

/**
 * Retained GTK4 custom-styled container rendering gradients, CSS borders (solid/dashed/dotted/double),
 * box shadows, all 10 `backdrop-filter` ops via [LinuxBackdropFilterController], and GSK/Cairo
 * foreground filters behind a `GtkCustomLayout` child container (`GtkOverlay`).
 */
open class GtkStyledContainerNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkOverlay_StyledContainer"),
    classPrefix: String = "styled-container",
    tagName: String = "div"
) : DesktopStyledContainerNativeImpl<GtkWidgetHandle>(nativeWidget, classPrefix, tagName) {

    init {
        nativeWidget.attributes["tagName"] = tagName
        nativeWidget.attributes["useSystemControlAppearance"] = "false"
        nativeWidget.hasCustomSurfaceBackground = true
        val isLayered = GtkRetainedContainerSupport.isLayeredHostTagOrClass(
            classPrefix = classPrefix,
            tagName = tagName,
            classes = nativeWidget.cachedAllClassTokens
        )
        layoutController = GtkRetainedContainerSupport.initializeContainer(
            host = this,
            isLayeredHost = isLayered,
            useSurfaceOverlay = true,
            surfacePainter = { cr, width, height -> paintStyledContainerSurface(cr, width, height) }
        )
    }

    private fun paintStyledContainerSurface(cr: COpaquePointer, width: Int, height: Int) {
        if (nativeWidget.interactiveSurfaceSpec != null) {
            if (GtkInteractiveSurfaceWidget.paintInteractiveSurface(nativeWidget, cr, width, height)) {
                return
            }
        }
        val dark = GtkRetainedContainerSupport.resolveDarkTheme()
        val visuals = getStyledVisuals()
        val computed = getComputedStyle()
        val paintSpec = GtkSurfaceRenderHelper.fromStyledVisualSpec(
            spec = visuals,
            cornerRadii = computed?.borderRadius,
            backdropFilter = nativeWidget.attributes["backdropFilter"] as? com.nexustools.genericui.style.css.CssFilter ?: visuals.backdropFilter,
            foregroundFilter = nativeWidget.attributes["cssFilter"] as? com.nexustools.genericui.style.css.CssFilter ?: visuals.filter,
            opacity = getOpacity(),
            darkTheme = dark
        )
        val hasBackdrop = GtkSurfaceRenderHelper.effectiveBackdropFilter(paintSpec)?.let {
            LinuxBackdropFilterController.shared.hasActiveEffects(it)
        } == true
        val behindCapture: (() -> IntArray?)? = if (hasBackdrop) {
            { GtkWidgetLifecycleBridge.captureBehindWidgetPremulBgra(this, width, height) }
        } else {
            null
        }
        GtkSurfaceRenderHelper.paintSurface(
            cr = cr,
            width = width,
            height = height,
            spec = paintSpec,
            behindBgraCapture = behindCapture
        )
    }

    override fun onChildAddedNative(childImpl: NativeImpl<*>, index: Int) {
        GtkRetainedContainerSupport.attachChildWidgetIfRealized(this, childImpl)
    }

    override fun onChildRemovedNative(childImpl: NativeImpl<*>) {
        GtkRetainedContainerSupport.detachChildWidget(this, childImpl)
    }

    override fun onChildrenClearedNative() {
        GtkRetainedContainerSupport.clearChildWidgets(this)
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

    override fun onNativeStyleSynced() {
        GtkRetainedContainerSupport.syncOverflowPolicy(this)
        GtkWidgetLifecycleBridge.onStyleSynced(this)
    }
}
