@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.impl

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.widget.DesktopNavbarNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.enums.css.CssDisplay
import com.nexustools.genericui.enums.bootstrap.FlexDirection
import com.nexustools.genericui.geometry.EdgeInsets
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.gtk.widget.GtkRetainedContainerSupport
import com.nexustools.genericui.gtk.widget.GtkSurfacePaintSpec
import com.nexustools.genericui.gtk.widget.GtkSurfaceRenderHelper
import com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge
import com.nexustools.genericui.linux.titlebar.LinuxOverlayTitleBarResolver
import com.nexustools.genericui.linux.titlebar.LinuxTitleBarProfileManager
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.COpaquePointer

/**
 * Retained GTK4 top navigation bar mounting `GtkOverlay(headerSurface + GtkCustomLayout row)`
 * with optional brand title/icon and child nav items.
 */
class GtkNavbarNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("AdwHeaderBar")
) : DesktopNavbarNativeImpl<GtkWidgetHandle>(nativeWidget) {

    init {
        layoutController = GtkRetainedContainerSupport.initializeContainer(
            host = this,
            defaultDisplay = CssDisplay.FLEX,
            defaultFlexDirection = FlexDirection.ROW,
            defaultGapPx = 10,
            defaultPadding = EdgeInsets.symmetric(vertical = 8, horizontal = 12),
            isLayeredHost = false,
            useSurfaceOverlay = true,
            surfacePainter = { cr, w, h -> paintNavbarSurface(cr, w, h) }
        )
    }

    private fun paintNavbarSurface(cr: COpaquePointer, width: Int, height: Int) {
        val dark = GtkRetainedContainerSupport.resolveDarkTheme()
        val palette = LinuxOverlayTitleBarResolver.switchProfileTheme(
            LinuxTitleBarProfileManager.currentProfile(),
            dark
        ).palette
        val spec = GtkSurfacePaintSpec(
            backgroundColor = palette.activeBgBottom,
            borderColor = palette.activeBorderBottom,
            borderWidthPx = 1f,
            opacity = getOpacity(),
            darkTheme = dark
        )
        GtkSurfaceRenderHelper.paintSurface(cr, width, height, spec)
        if (isBrandVisible() && brandTitleText.isNotEmpty()) {
            val display = if (!brandIconSpec.isNullOrBlank()) "$brandIconSpec $brandTitleText" else brandTitleText
            GtkSurfaceRenderHelper.drawPangoText(
                cr = cr,
                text = display,
                x = 12.0,
                y = 0.0,
                width = (width - 24.0).coerceAtLeast(1.0),
                height = height.toDouble(),
                color = palette.activeTitleText,
                fontSizePx = 15f,
                fontWeight = Gtk4DynamicBindings.PANGO_WEIGHT_SEMIBOLD,
                singleLine = true,
                opacity = getOpacity()
            )
        }
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

    override fun setBrandTitle(title: String) {
        super.setBrandTitle(title)
        nativeWidget.backgroundSurfacePtr?.let { Gtk4DynamicBindings.gtkWidgetQueueDraw(it) }
    }

    override fun setBrandIcon(icon: String?) {
        super.setBrandIcon(icon)
        nativeWidget.backgroundSurfacePtr?.let { Gtk4DynamicBindings.gtkWidgetQueueDraw(it) }
    }

    override fun setShowBrand(show: Boolean?) {
        super.setShowBrand(show)
        nativeWidget.backgroundSurfacePtr?.let { Gtk4DynamicBindings.gtkWidgetQueueDraw(it) }
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
    override fun onNativeStyleSynced() = GtkWidgetLifecycleBridge.onStyleSynced(this)
}
