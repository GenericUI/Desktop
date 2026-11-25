@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.widget

import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.layout.DesktopContainerLayoutController
import com.nexustools.genericui.enums.bootstrap.FlexDirection
import com.nexustools.genericui.enums.bootstrap.OverflowBehavior
import com.nexustools.genericui.enums.css.CssDisplay
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.geometry.EdgeInsets
import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.gtk.GtkApplicationNativeImpl
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.style.theme.SystemThemeDetector
import kotlinx.cinterop.COpaquePointer

/**
 * Shared retained GTK4 container materializer & child lifecycle helper supporting plain transparent
 * containers (`GtkBox + GtkCustomLayout`), `LayeredContainer` (`GtkOverlay + GtkCustomLayout`),
 * styled/semantic overlay surfaces, and overflow clipping without duplicating container plumbing.
 */
object GtkRetainedContainerSupport {

    fun isDarkTheme(): Boolean =
        (Application.current?.getResolvedTheme() ?: GtkApplicationNativeImpl.detectGtkSystemTheme()) == ThemeMode.DARK

    fun resolveDarkTheme(): Boolean =
        (Application.current?.getResolvedTheme() ?: SystemThemeDetector.detectSystemTheme()) == ThemeMode.DARK

    fun isLayeredHostTagOrClass(classPrefix: String, tagName: String, classes: Set<String>): Boolean {
        val tagLower = tagName.lowercase()
        val prefixLower = classPrefix.lowercase()
        if (tagLower == "layeredcontainer" ||
            tagLower == "layered-container" ||
            tagLower == "genericui:layered-container" ||
            tagLower == "gui-layered-container" ||
            prefixLower.contains("layered-container")
        ) {
            return true
        }
        return "gui-layered-container" in classes || "layered-container" in classes
    }

    fun initializeContainer(
        host: DesktopBaseContainerNativeImpl<GtkWidgetHandle>,
        defaultDisplay: CssDisplay? = null,
        defaultFlexDirection: FlexDirection? = null,
        defaultGapPx: Int = 0,
        defaultPadding: EdgeInsets = EdgeInsets.ZERO,
        isLayeredHost: Boolean = false,
        useSurfaceOverlay: Boolean = false,
        surfacePainter: ((cr: COpaquePointer, width: Int, height: Int) -> Unit)? = null
    ): DesktopContainerLayoutController {
        val handle = host.nativeWidget
        handle.ownerImpl = host
        val controller = DesktopContainerLayoutController(
            host = host,
            defaultDisplay = defaultDisplay,
            defaultFlexDirection = defaultFlexDirection,
            defaultGapPx = defaultGapPx,
            defaultPadding = defaultPadding,
            isLayeredHost = isLayeredHost,
            applyScaleToBounds = false
        )
        handle.customLayoutController = controller
        if (surfacePainter != null) {
            handle.onDrawSurfaceCairo = surfacePainter
        }
        handle.widgetMaterializer = {
            materializeContainer(
                host = host,
                controller = controller,
                isLayeredHost = isLayeredHost,
                useSurfaceOverlay = useSurfaceOverlay,
                surfacePainter = surfacePainter
            )
        }
        return controller
    }

    fun materializeContainer(
        host: DesktopBaseContainerNativeImpl<GtkWidgetHandle>,
        controller: DesktopContainerLayoutController,
        isLayeredHost: Boolean,
        useSurfaceOverlay: Boolean,
        surfacePainter: ((cr: COpaquePointer, width: Int, height: Int) -> Unit)?
    ): COpaquePointer? {
        val handle = host.nativeWidget
        handle.realGtkWidgetPtr?.let { return it }
        if (!Gtk4DynamicBindings.ensureGtkInitialized()) return null

        val rootPtr: COpaquePointer
        val layoutChildHostPtr: COpaquePointer
        var fixedBgPtr: COpaquePointer? = null

        if (isLayeredHost) {
            val overlay = Gtk4DynamicBindings.gtkOverlayNew() ?: return null
            if (useSurfaceOverlay && surfacePainter != null) {
                val bgDa = GtkWidgetLifecycleBridge.createDrawingSurfaceForPainter(handle, surfacePainter)
                if (bgDa != null) {
                    Gtk4DynamicBindings.gtkWidgetSetCanTarget(bgDa, false)
                    Gtk4DynamicBindings.gtkOverlaySetChild(overlay, bgDa)
                    handle.backgroundSurfacePtr = bgDa
                    fixedBgPtr = bgDa
                }
            }
            GtkCustomLayoutManager.attachToContainer(host, overlay, controller, fixedBgPtr)
            rootPtr = overlay
            layoutChildHostPtr = overlay
            handle.updateActiveGtkClassName("GtkOverlay")
        } else if (useSurfaceOverlay && surfacePainter != null) {
            val overlay = Gtk4DynamicBindings.gtkOverlayNew() ?: return null
            val bgDa = GtkWidgetLifecycleBridge.createDrawingSurfaceForPainter(handle, surfacePainter)
            if (bgDa != null) {
                Gtk4DynamicBindings.gtkWidgetSetCanTarget(bgDa, false)
                Gtk4DynamicBindings.gtkOverlaySetChild(overlay, bgDa)
                handle.backgroundSurfacePtr = bgDa
            }
            val contentBox = Gtk4DynamicBindings.gtkBoxNew(Gtk4DynamicBindings.GTK_ORIENTATION_VERTICAL, 0)
            if (contentBox == null) {
                GtkCustomLayoutManager.attachToContainer(host, overlay, controller, bgDa)
                rootPtr = overlay
                layoutChildHostPtr = overlay
            } else {
                Gtk4DynamicBindings.gtkOverlayAddOverlay(overlay, contentBox)
                GtkCustomLayoutManager.attachToContainer(host, contentBox, controller, null)
                rootPtr = overlay
                layoutChildHostPtr = contentBox
            }
        } else {
            val box = Gtk4DynamicBindings.gtkBoxNew(Gtk4DynamicBindings.GTK_ORIENTATION_VERTICAL, 0) ?: return null
            GtkCustomLayoutManager.attachToContainer(host, box, controller, null)
            rootPtr = box
            layoutChildHostPtr = box
        }

        handle.containerLayoutBoxPtr = layoutChildHostPtr
        GtkWidgetLifecycleBridge.attachControllersAndState(
            impl = host,
            rootPtr = rootPtr,
            focusTargetPtr = rootPtr,
            isTextInput = false,
            dispatchClickOnPrimaryRelease = true,
            attachClickGesture = true
        )
        syncOverflowPolicy(host)

        for (child in host.getLogicalChildren()) {
            attachChildWidgetIfRealized(host, child)
        }
        GtkCustomLayoutManager.syncChildrenZOrder(
            parentPtr = layoutChildHostPtr,
            orderedChildren = host.getLogicalChildren().filterIsInstance<DesktopBaseNativeImpl<*>>(),
            fixedFirstChildPtr = fixedBgPtr
        )
        return rootPtr
    }

    fun attachChildWidgetIfRealized(host: DesktopBaseContainerNativeImpl<GtkWidgetHandle>, childImpl: NativeImpl<*>) {
        val childHandle = childImpl.nativeWidget as? GtkWidgetHandle ?: return
        if (childHandle.attributes["mountedInTitleBar"] == true &&
            host !is com.nexustools.genericui.gtk.GtkTabContainerNativeImpl
        ) {
            return
        }
        val parentPtr = host.nativeWidget.containerLayoutBoxPtr ?: host.nativeWidget.realGtkWidgetPtr ?: return
        val childPtr = childHandle.ensureLiveWidgetPtr() ?: childHandle.realGtkWidgetPtr ?: return
        GtkCustomLayoutManager.attachChildWidget(parentPtr, childPtr, null)
        GtkCustomLayoutManager.syncChildrenZOrder(
            parentPtr = parentPtr,
            orderedChildren = host.getLogicalChildren().filterIsInstance<DesktopBaseNativeImpl<*>>(),
            fixedFirstChildPtr = if (parentPtr == host.nativeWidget.realGtkWidgetPtr) host.nativeWidget.backgroundSurfacePtr else null
        )
        GtkCustomLayoutManager.requestRelayout(parentPtr)
    }

    fun detachChildWidget(host: DesktopBaseContainerNativeImpl<GtkWidgetHandle>, childImpl: NativeImpl<*>) {
        val childHandle = childImpl.nativeWidget as? GtkWidgetHandle ?: return
        val childPtr = childHandle.realGtkWidgetPtr ?: return
        GtkCustomLayoutManager.detachChildWidget(childPtr)
        val parentPtr = host.nativeWidget.containerLayoutBoxPtr ?: host.nativeWidget.realGtkWidgetPtr
        GtkCustomLayoutManager.requestRelayout(parentPtr)
    }

    fun clearChildWidgets(host: DesktopBaseContainerNativeImpl<GtkWidgetHandle>) {
        val parentPtr = host.nativeWidget.containerLayoutBoxPtr ?: host.nativeWidget.realGtkWidgetPtr ?: return
        var cur = Gtk4DynamicBindings.gtkWidgetGetFirstChild(parentPtr)
        val bgPtr = host.nativeWidget.backgroundSurfacePtr
        while (cur != null) {
            val next = Gtk4DynamicBindings.gtkWidgetGetNextSibling(cur)
            if (cur != bgPtr) {
                Gtk4DynamicBindings.gtkWidgetUnparent(cur)
            }
            cur = next
        }
        GtkCustomLayoutManager.requestRelayout(parentPtr)
    }

    fun syncOverflowPolicy(host: DesktopBaseContainerNativeImpl<GtkWidgetHandle>) {
        val ptr = host.nativeWidget.realGtkWidgetPtr ?: return
        val overflowAttr = (host.nativeWidget.attributes["overflow"] as? String)?.lowercase()
        val overflowYAttr = host.nativeWidget.attributes["overflowY"]
        val overflowXAttr = host.nativeWidget.attributes["overflowX"]
        val shouldClip = host.getOverflow() == OverflowBehavior.HIDDEN ||
            overflowAttr == "hidden" ||
            overflowYAttr == OverflowBehavior.HIDDEN ||
            overflowXAttr == OverflowBehavior.HIDDEN ||
            overflowYAttr?.toString()?.equals("hidden", ignoreCase = true) == true ||
            overflowXAttr?.toString()?.equals("hidden", ignoreCase = true) == true ||
            "overflow-hidden" in host.nativeWidget.cachedAllClassTokens
        Gtk4DynamicBindings.gtkWidgetSetOverflow(
            ptr,
            if (shouldClip) Gtk4DynamicBindings.GTK_OVERFLOW_HIDDEN else Gtk4DynamicBindings.GTK_OVERFLOW_VISIBLE
        )
    }
}
