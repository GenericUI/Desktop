@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.impl

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.dock.DesktopDockGroupNativeImpl
import com.nexustools.genericui.desktop.dock.DesktopDockPanelNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.enums.dock.DockGroupMode
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.gtk.widget.GtkCustomLayoutManager
import com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge
import com.nexustools.genericui.linux.LinuxGlibGioBindings
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction

/**
 * Retained GTK4 dock group mounting a `GtkNotebook` for tabbed panels and switching pages cleanly.
 */
class GtkDockGroupNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkNotebook_DockGroup")
) : DesktopDockGroupNativeImpl<GtkWidgetHandle>(nativeWidget) {

    private var notebookPtr: COpaquePointer? = null
    private var switchRef: StableRef<GtkDockGroupNativeImpl>? = null
    private var syncing = false

    init {
        nativeWidget.ownerImpl = this
        nativeWidget.widgetMaterializer = { materializeDockGroup() }
    }

    private fun materializeDockGroup(): COpaquePointer? {
        nativeWidget.realGtkWidgetPtr?.let { return it }
        if (!Gtk4DynamicBindings.ensureGtkInitialized()) return null

        val nb = Gtk4DynamicBindings.gtkNotebookNew() ?: return null
        Gtk4DynamicBindings.gtkNotebookSetScrollable(nb, true)
        notebookPtr = nb
        nativeWidget.primaryLeafPtr = nb

        switchRef?.dispose()
        val ref = StableRef.create(this)
        switchRef = ref
        LinuxGlibGioBindings.signalConnect(nb, "switch-page", pageSwitchedCb.reinterpret(), ref.asCPointer())

        GtkWidgetLifecycleBridge.attachControllersAndState(
            impl = this,
            rootPtr = nb,
            focusTargetPtr = nb,
            isTextInput = false,
            dispatchClickOnPrimaryRelease = false,
            attachClickGesture = false
        )
        rebuildPages()
        return nb
    }

    private fun rebuildPages() {
        val nb = notebookPtr ?: return
        syncing = true
        try {
            while (Gtk4DynamicBindings.gtkNotebookGetNPages(nb) > 0) {
                Gtk4DynamicBindings.gtkNotebookRemovePage(nb, 0)
            }
            for ((idx, child) in getLogicalChildren().withIndex()) {
                val handle = child.nativeWidget as? GtkWidgetHandle ?: continue
                val childPtr = handle.ensureLiveWidgetPtr() ?: handle.realGtkWidgetPtr ?: continue
                if (Gtk4DynamicBindings.gtkWidgetGetParent(childPtr) != null) {
                    Gtk4DynamicBindings.gtkWidgetUnparent(childPtr)
                }
                val title = (child as? DesktopDockPanelNativeImpl<*>)?.getTitle()?.takeIf { it.isNotEmpty() }
                    ?: "Panel ${idx + 1}"
                val tabLabel = Gtk4DynamicBindings.gtkLabelNew(title)
                Gtk4DynamicBindings.gtkNotebookAppendPage(nb, childPtr, tabLabel)
            }
            Gtk4DynamicBindings.gtkNotebookSetShowTabs(nb, groupModeState == DockGroupMode.TABBED)
            if (activePanelIdx in 0 until Gtk4DynamicBindings.gtkNotebookGetNPages(nb)) {
                Gtk4DynamicBindings.gtkNotebookSetCurrentPage(nb, activePanelIdx)
            }
        } finally {
            syncing = false
        }
    }

    override fun setGroupMode(mode: DockGroupMode) {
        super.setGroupMode(mode)
        notebookPtr?.let { Gtk4DynamicBindings.gtkNotebookSetShowTabs(it, mode == DockGroupMode.TABBED) }
    }

    override fun setActivePanelIndex(index: Int) {
        super.setActivePanelIndex(index)
        val nb = notebookPtr ?: return
        if (!syncing && index in 0 until Gtk4DynamicBindings.gtkNotebookGetNPages(nb)) {
            syncing = true
            try {
                Gtk4DynamicBindings.gtkNotebookSetCurrentPage(nb, index)
            } finally {
                syncing = false
            }
        }
    }

    override fun onChildAddedNative(childImpl: NativeImpl<*>, index: Int) {
        rebuildPages()
    }

    override fun onChildRemovedNative(childImpl: NativeImpl<*>) {
        rebuildPages()
    }

    override fun onChildrenClearedNative() {
        rebuildPages()
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
        switchRef?.dispose()
        switchRef = null
        super.dispose()
    }

    companion object {
        private val pageSwitchedCb = staticCFunction<COpaquePointer?, COpaquePointer?, UInt, COpaquePointer?, Unit> { _, _, pageNum, userData ->
            val impl = userData?.asStableRef<GtkDockGroupNativeImpl>()?.get() ?: return@staticCFunction
            if (!impl.syncing) {
                impl.setActivePanelIndex(pageNum.toInt())
            }
        }
    }
}
