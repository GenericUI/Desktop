@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.impl

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.dock.DesktopDockPanelNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.enums.css.CssDisplay
import com.nexustools.genericui.enums.bootstrap.FlexDirection
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.gtk.widget.GtkCustomLayoutManager
import com.nexustools.genericui.gtk.widget.GtkRetainedContainerSupport
import com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge
import com.nexustools.genericui.linux.LinuxGlibGioBindings
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction

/**
 * Retained GTK4 dockable panel mounting a `GtkBox` with a collapsible header bar (`title`, collapse
 * toggle, and [DockHeaderAction] buttons) over a `GtkCustomLayout` content container.
 */
class GtkDockPanelNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkBox_DockPanel")
) : DesktopDockPanelNativeImpl<GtkWidgetHandle>(nativeWidget) {

    private class HeaderActionPayload(val onClick: () -> Unit)

    private var headerTitlePtr: COpaquePointer? = null
    private var actionsBoxPtr: COpaquePointer? = null
    private var bodyBoxPtr: COpaquePointer? = null
    private var collapseBtnRef: StableRef<GtkDockPanelNativeImpl>? = null

    init {
        layoutController = GtkRetainedContainerSupport.initializeContainer(
            host = this,
            defaultDisplay = CssDisplay.FLEX,
            defaultFlexDirection = FlexDirection.COLUMN
        )
        nativeWidget.widgetMaterializer = { materializePanel() }
    }

    private fun materializePanel(): COpaquePointer? {
        nativeWidget.realGtkWidgetPtr?.let { return it }
        if (!Gtk4DynamicBindings.ensureGtkInitialized()) return null

        val root = Gtk4DynamicBindings.gtkBoxNew(Gtk4DynamicBindings.GTK_ORIENTATION_VERTICAL, 0) ?: return null
        val header = Gtk4DynamicBindings.gtkBoxNew(Gtk4DynamicBindings.GTK_ORIENTATION_HORIZONTAL, 6) ?: return null
        Gtk4DynamicBindings.gtkWidgetSetMargins(header, 4, 4, 8, 8)

        val titleLbl = Gtk4DynamicBindings.gtkLabelNew(titleText) ?: return null
        Gtk4DynamicBindings.gtkLabelSetXalign(titleLbl, 0f)
        Gtk4DynamicBindings.gtkWidgetSetExpand(titleLbl, hexpand = true, vexpand = false)
        headerTitlePtr = titleLbl

        val actionsBox = Gtk4DynamicBindings.gtkBoxNew(Gtk4DynamicBindings.GTK_ORIENTATION_HORIZONTAL, 4)
        actionsBoxPtr = actionsBox

        val collapseBtn = Gtk4DynamicBindings.gtkButtonNewWithLabel(if (collapsedState) "▸" else "▾")
        if (collapseBtn != null) {
            Gtk4DynamicBindings.gtkWidgetAddCssClass(collapseBtn, "flat")
            collapseBtnRef?.dispose()
            val ref = StableRef.create(this)
            collapseBtnRef = ref
            LinuxGlibGioBindings.signalConnect(collapseBtn, "clicked", collapseToggleCb.reinterpret(), ref.asCPointer())
        }

        Gtk4DynamicBindings.gtkBoxAppend(header, titleLbl)
        actionsBox?.let { Gtk4DynamicBindings.gtkBoxAppend(header, it) }
        collapseBtn?.let { Gtk4DynamicBindings.gtkBoxAppend(header, it) }

        val bodyBox = Gtk4DynamicBindings.gtkBoxNew(Gtk4DynamicBindings.GTK_ORIENTATION_VERTICAL, 0) ?: return null
        val controller = layoutController ?: return null
        GtkCustomLayoutManager.attachToContainer(this, bodyBox, controller, null)
        Gtk4DynamicBindings.gtkWidgetSetExpand(bodyBox, hexpand = true, vexpand = true)
        Gtk4DynamicBindings.gtkWidgetSetVisible(bodyBox, !collapsedState)

        Gtk4DynamicBindings.gtkBoxAppend(root, header)
        Gtk4DynamicBindings.gtkBoxAppend(root, bodyBox)

        bodyBoxPtr = bodyBox
        nativeWidget.containerLayoutBoxPtr = bodyBox
        GtkWidgetLifecycleBridge.attachControllersAndState(
            impl = this,
            rootPtr = root,
            focusTargetPtr = bodyBox,
            isTextInput = false,
            dispatchClickOnPrimaryRelease = false,
            attachClickGesture = true
        )
        rebuildHeaderActions()
        for (child in getLogicalChildren()) {
            GtkRetainedContainerSupport.attachChildWidgetIfRealized(this, child)
        }
        return root
    }

    private fun rebuildHeaderActions() {
        val box = actionsBoxPtr ?: return
        var cur = Gtk4DynamicBindings.gtkWidgetGetFirstChild(box)
        while (cur != null) {
            val next = Gtk4DynamicBindings.gtkWidgetGetNextSibling(cur)
            Gtk4DynamicBindings.gtkBoxRemove(box, cur)
            cur = next
        }
        for (act in headerActionsList) {
            val btn = Gtk4DynamicBindings.gtkButtonNewWithLabel(act.label) ?: continue
            Gtk4DynamicBindings.gtkWidgetAddCssClass(btn, "flat")
            act.tooltip?.let { Gtk4DynamicBindings.gtkWidgetSetTooltipText(btn, it) }
            val ref = StableRef.create(HeaderActionPayload(act.onClick))
            LinuxGlibGioBindings.signalConnect(
                instance = btn,
                signalName = "clicked",
                handler = headerActionClickedCb.reinterpret(),
                userData = ref.asCPointer(),
                destroyNotify = destroyActionPayloadCb.reinterpret()
            )
            Gtk4DynamicBindings.gtkBoxAppend(box, btn)
        }
    }

    override fun setTitle(title: String) {
        super.setTitle(title)
        headerTitlePtr?.let { Gtk4DynamicBindings.gtkLabelSetText(it, title) }
    }

    override fun setCollapsed(collapsed: Boolean) {
        super.setCollapsed(collapsed)
        bodyBoxPtr?.let { Gtk4DynamicBindings.gtkWidgetSetVisible(it, !collapsed) }
    }

    override fun setHeaderActions(actions: List<com.nexustools.genericui.component.dock.DockHeaderAction>) {
        super.setHeaderActions(actions)
        rebuildHeaderActions()
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
    override fun onNativeStyleSynced() = GtkWidgetLifecycleBridge.onStyleSynced(this)

    override fun dispose() {
        collapseBtnRef?.dispose()
        collapseBtnRef = null
        super.dispose()
    }

    companion object {
        private val collapseToggleCb = staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { _, userData ->
            val impl = userData?.asStableRef<GtkDockPanelNativeImpl>()?.get() ?: return@staticCFunction
            impl.toggleCollapsed()
        }

        private val headerActionClickedCb = staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { _, userData ->
            userData?.asStableRef<HeaderActionPayload>()?.get()?.onClick?.invoke()
        }

        private val destroyActionPayloadCb = staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { data, _ ->
            data?.asStableRef<HeaderActionPayload>()?.dispose()
        }
    }
}
