@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk

import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.layout.DesktopContainerLayoutController
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.COpaquePointer

/**
 * Retained GTK4 widget handle backing every GenericUI component on `:gtk`.
 *
 * Pre-mount calls are pure and store state cleanly until [realGtkWidgetPtr] is materialized.
 */
class GtkWidgetHandle(
    gtkTypeName: String,
    gtkPointer: Long = nextPointerId()
) : DesktopNativeWidget(
    backendToolkit = "GTK4_Libadwaita",
    nativeClassName = gtkTypeName,
    nativePointer = gtkPointer
) {
    val gtkCssClasses: MutableSet<String> = linkedSetOf()
    var activeGtkClassName: String = gtkTypeName
    var realGtkWidgetPtr: COpaquePointer? = null
    var focusTargetPtr: COpaquePointer? = null
    var containerLayoutBoxPtr: COpaquePointer? = null
    var backgroundSurfacePtr: COpaquePointer? = null
    var primaryLeafPtr: COpaquePointer? = null
    var secondaryLeafPtr: COpaquePointer? = null
    var scrolledWindowPtr: COpaquePointer? = null
    var gtkLayoutManagerPtr: COpaquePointer? = null
    var popoverPtr: COpaquePointer? = null

    var pointerEventsNone: Boolean = false
    var resolvedZIndex: Int = 0
    var cssTransform: CssTransform? = null
    var hasCustomSurfaceBackground: Boolean = false
    var customLayoutController: DesktopContainerLayoutController? = null
    var ownerImpl: Any? = null
    var widgetMaterializer: (() -> COpaquePointer?)? = null
    var onDrawSurfaceCairo: ((cr: COpaquePointer, width: Int, height: Int) -> Unit)? = null
    var onPaintChildrenHeadlessCairo: ((cr: COpaquePointer, width: Int, height: Int) -> Unit)? = null
    var onWidgetSwapped: ((oldPtr: COpaquePointer?, newPtr: COpaquePointer?) -> Unit)? = null
    var onBeforeLayoutAllocate: ((width: Int, height: Int) -> Unit)? = null

    fun ensureLiveWidgetPtr(): COpaquePointer? {
        realGtkWidgetPtr?.let { return it }
        val materializer = widgetMaterializer ?: return null
        if (!Gtk4DynamicBindings.ensureGtkInitialized()) return null
        return materializer.invoke()
    }

    fun addGtkCssClass(cssClass: String) {
        if (cssClass.isBlank()) return
        gtkCssClasses.add(cssClass)
        realGtkWidgetPtr?.let { Gtk4DynamicBindings.gtkWidgetAddCssClass(it, cssClass) }
    }

    fun removeGtkCssClass(cssClass: String) {
        if (cssClass.isBlank()) return
        gtkCssClasses.remove(cssClass)
        realGtkWidgetPtr?.let { Gtk4DynamicBindings.gtkWidgetRemoveCssClass(it, cssClass) }
    }

    fun updateActiveGtkClassName(name: String) {
        activeGtkClassName = name
        attributes["gtkClassName"] = name
    }

    override fun onEffectiveFocusableChanged(focusable: Boolean) {
        com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge.onFocusableChanged(this)
    }
}
