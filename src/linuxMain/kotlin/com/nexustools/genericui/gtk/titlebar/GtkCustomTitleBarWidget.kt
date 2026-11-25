@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.titlebar

import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.shell.DesktopWindowNativeImpl
import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.gtk.widget.GtkCustomLayoutManager
import com.nexustools.genericui.linux.LinuxGlibGioBindings
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.pointed
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value

/**
 * Retained GTK4 custom titlebar widget mounting a `GtkOverlay` with a `GtkDrawingArea` header
 * surface and a `GtkCustomLayout` overlay container holding live retained `TitleBar` child
 * `GtkWidget*`s (`leading`/`center`/`trailing`/`MenuBar`), collapsing cleanly to `0px` when
 * the host WM is non-replicable or the window is fullscreen.
 */
class GtkCustomTitleBarWidget(
    val windowImpl: DesktopWindowNativeImpl<*>
) {
    private val windowKey: Long get() = windowImpl.nativeWidget.nativePointer

    var rootWidgetPtr: COpaquePointer? = null
        private set
    private var bgDrawingAreaPtr: COpaquePointer? = null
    private var slotsOverlayBoxPtr: COpaquePointer? = null
    private var selfRef: StableRef<GtkCustomTitleBarWidget>? = null

    fun ensureWidget(): COpaquePointer? {
        rootWidgetPtr?.let { return it }
        if (!Gtk4DynamicBindings.ensureGtkInitialized()) return null

        val overlay = Gtk4DynamicBindings.gtkOverlayNew() ?: return null
        val da = Gtk4DynamicBindings.gtkDrawingAreaNew() ?: return null
        val slotsBox = Gtk4DynamicBindings.gtkBoxNew(Gtk4DynamicBindings.GTK_ORIENTATION_HORIZONTAL, 0) ?: return null

        val ref = StableRef.create(this)
        selfRef = ref
        val userData = ref.asCPointer()

        Gtk4DynamicBindings.gtkDrawingAreaSetDrawFunc(
            da = da,
            drawFunc = titleBarDrawCb.reinterpret(),
            userData = userData,
            destroy = null
        )

        Gtk4DynamicBindings.gObjectSetQdataFull(slotsBox, quarkTitleBarSlotsHost, userData, null)
        val slotsMgr = Gtk4DynamicBindings.gtkCustomLayoutNew(
            requestMode = slotsRequestModeCb.reinterpret(),
            measure = slotsMeasureCb.reinterpret(),
            allocate = slotsAllocateCb.reinterpret()
        )
        if (slotsMgr != null) {
            Gtk4DynamicBindings.gtkWidgetSetLayoutManager(slotsBox, slotsMgr)
        }

        Gtk4DynamicBindings.gtkOverlaySetChild(overlay, da)
        Gtk4DynamicBindings.gtkOverlayAddOverlay(overlay, slotsBox)

        Gtk4DynamicBindings.gtkEventControllerMotionNew()?.let { motion ->
            LinuxGlibGioBindings.signalConnect(motion, "motion", motionMoveCb.reinterpret(), userData)
            LinuxGlibGioBindings.signalConnect(motion, "leave", motionLeaveCb.reinterpret(), userData)
            Gtk4DynamicBindings.gtkWidgetAddController(overlay, motion)
        }
        Gtk4DynamicBindings.gtkGestureClickNew()?.let { click ->
            Gtk4DynamicBindings.gtkGestureSingleSetButton(click, 0u)
            LinuxGlibGioBindings.signalConnect(click, "pressed", clickPressedCb.reinterpret(), userData)
            LinuxGlibGioBindings.signalConnect(click, "released", clickReleasedCb.reinterpret(), userData)
            Gtk4DynamicBindings.gtkWidgetAddController(overlay, click)
        }

        rootWidgetPtr = overlay
        bgDrawingAreaPtr = da
        slotsOverlayBoxPtr = slotsBox
        syncVisibilityAndHeight(windowWidth = windowImpl.getWindowSize().width)
        return overlay
    }

    fun syncVisibilityAndHeight(windowWidth: Int) {
        val active = GtkWindowTitleBarController.isCustomTitleBarActive(windowImpl)
        val layout = if (active) {
            GtkWindowTitleBarController.computeAndApplySlotLayout(windowImpl, windowWidth)
        } else {
            GtkWindowTitleBarController.clearWindowState(windowKey)
            null
        }
        val root = rootWidgetPtr ?: return
        if (!active || layout == null || layout.barBounds.height <= 0) {
            Gtk4DynamicBindings.gtkWidgetSetVisible(root, false)
            Gtk4DynamicBindings.gtkWidgetSetSizeRequest(root, -1, 0)
            return
        }
        Gtk4DynamicBindings.gtkWidgetSetVisible(root, true)
        Gtk4DynamicBindings.gtkWidgetSetSizeRequest(root, -1, layout.barBounds.height)
        syncSlotChildren()
        bgDrawingAreaPtr?.let { Gtk4DynamicBindings.gtkWidgetQueueDraw(it) }
    }

    fun syncSlotChildren() {
        val box = slotsOverlayBoxPtr ?: return
        val positioned = GtkWindowTitleBarController.getLatestSlotWidgets(windowKey)
        val desiredPtrs = ArrayList<COpaquePointer>(positioned.size)
        for ((widget, _) in positioned) {
            val h = widget as? GtkWidgetHandle ?: continue
            val ptr = h.ensureLiveWidgetPtr() ?: h.realGtkWidgetPtr ?: continue
            desiredPtrs.add(ptr)
        }

        var cur = Gtk4DynamicBindings.gtkWidgetGetFirstChild(box)
        while (cur != null) {
            val next = Gtk4DynamicBindings.gtkWidgetGetNextSibling(cur)
            if (cur !in desiredPtrs) {
                Gtk4DynamicBindings.gtkWidgetUnparent(cur)
            }
            cur = next
        }
        for (ptr in desiredPtrs) {
            if (Gtk4DynamicBindings.gtkWidgetGetParent(ptr) != box) {
                GtkCustomLayoutManager.attachChildWidget(box, ptr, null)
            }
        }
        GtkCustomLayoutManager.requestRelayout(box)
    }

    private fun allocateSlots(width: Int) {
        val layout = GtkWindowTitleBarController.computeAndApplySlotLayout(windowImpl, width) ?: return
        if (layout.barBounds.height <= 0) return
        val positioned = GtkWindowTitleBarController.getLatestSlotWidgets(windowKey)
        val box = slotsOverlayBoxPtr ?: return
        for ((widget, rect) in positioned) {
            val h = widget as? GtkWidgetHandle ?: continue
            val ptr = h.ensureLiveWidgetPtr() ?: h.realGtkWidgetPtr ?: continue
            if (Gtk4DynamicBindings.gtkWidgetGetParent(ptr) != box) {
                GtkCustomLayoutManager.attachChildWidget(box, ptr, null)
            }
            Gtk4DynamicBindings.gtkWidgetSizeAllocate(ptr, rect.x, rect.y, rect.width, rect.height, -1)
        }
    }

    fun queueDraw() {
        bgDrawingAreaPtr?.let { Gtk4DynamicBindings.gtkWidgetQueueDraw(it) }
    }

    fun dispose() {
        selfRef?.dispose()
        selfRef = null
        rootWidgetPtr = null
        bgDrawingAreaPtr = null
        slotsOverlayBoxPtr = null
    }

    companion object {
        private val quarkTitleBarSlotsHost by lazy {
            Gtk4DynamicBindings.gQuarkFromString("genericui-titlebar-slots-host")
        }

        private val titleBarDrawCb = staticCFunction<COpaquePointer?, COpaquePointer?, Int, Int, COpaquePointer?, Unit> { _, cr, width, height, userData ->
            if (cr == null || userData == null || width <= 0 || height <= 0) return@staticCFunction
            val owner = userData.asStableRef<GtkCustomTitleBarWidget>().get()
            GtkWindowTitleBarController.renderTopLevelTitleBarToCairo(cr, owner.windowImpl, width, height)
            owner.syncSlotChildren()
        }

        private val motionMoveCb = staticCFunction<COpaquePointer?, Double, Double, COpaquePointer?, Unit> { _, x, y, userData ->
            val owner = userData?.asStableRef<GtkCustomTitleBarWidget>()?.get() ?: return@staticCFunction
            val key = owner.windowKey
            if (GtkWindowTitleBarController.updateTopLevelHoverAt(key, x.toInt(), y.toInt())) {
                owner.queueDraw()
            }
            val hovered = GtkWindowTitleBarController.getTopLevelHoveredButton(key)
            owner.rootWidgetPtr?.let { ptr ->
                Gtk4DynamicBindings.gtkWidgetSetCursorFromName(ptr, if (hovered != null) "pointer" else "default")
            }
        }

        private val motionLeaveCb = staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { _, userData ->
            val owner = userData?.asStableRef<GtkCustomTitleBarWidget>()?.get() ?: return@staticCFunction
            GtkWindowTitleBarController.clearHoverAndPress(owner.windowKey)
            owner.queueDraw()
        }

        private val clickPressedCb = staticCFunction<COpaquePointer?, Int, Double, Double, COpaquePointer?, Unit> { gesture, nPress, x, y, userData ->
            val owner = userData?.asStableRef<GtkCustomTitleBarWidget>()?.get() ?: return@staticCFunction
            val button = gesture?.let { Gtk4DynamicBindings.gtkGestureSingleGetCurrentButton(it) } ?: Gtk4DynamicBindings.GDK_BUTTON_PRIMARY
            val gdkEvent = Gtk4DynamicBindings.gtkEventControllerGetCurrentEvent(gesture)
            if (GtkWindowTitleBarController.handlePointerPress(owner.windowImpl, x.toInt(), y.toInt(), nPress, button, gdkEvent)) {
                owner.queueDraw()
            }
        }

        private val clickReleasedCb = staticCFunction<COpaquePointer?, Int, Double, Double, COpaquePointer?, Unit> { gesture, _, x, y, userData ->
            val owner = userData?.asStableRef<GtkCustomTitleBarWidget>()?.get() ?: return@staticCFunction
            val button = gesture?.let { Gtk4DynamicBindings.gtkGestureSingleGetCurrentButton(it) } ?: Gtk4DynamicBindings.GDK_BUTTON_PRIMARY
            if (button == Gtk4DynamicBindings.GDK_BUTTON_PRIMARY &&
                GtkWindowTitleBarController.handlePointerRelease(owner.windowImpl, x.toInt(), y.toInt())
            ) {
                owner.queueDraw()
            }
        }

        private val slotsRequestModeCb = staticCFunction<COpaquePointer?, Int> { _ ->
            Gtk4DynamicBindings.GTK_SIZE_REQUEST_HEIGHT_FOR_WIDTH
        }

        private val slotsMeasureCb = staticCFunction<
            COpaquePointer?, Int, Int,
            kotlinx.cinterop.CPointer<IntVar>?,
            kotlinx.cinterop.CPointer<IntVar>?,
            kotlinx.cinterop.CPointer<IntVar>?,
            kotlinx.cinterop.CPointer<IntVar>?,
            Unit
        > { _, orientation, _, minPtr, natPtr, minBasePtr, natBasePtr ->
            val v = if (orientation == Gtk4DynamicBindings.GTK_ORIENTATION_HORIZONTAL) 120 else 36
            minPtr?.pointed?.value = v
            natPtr?.pointed?.value = v
            minBasePtr?.pointed?.value = -1
            natBasePtr?.pointed?.value = -1
        }

        private val slotsAllocateCb = staticCFunction<COpaquePointer?, Int, Int, Int, Unit> { box, width, _, _ ->
            box ?: return@staticCFunction
            val owner = Gtk4DynamicBindings.gObjectGetQdata(box, quarkTitleBarSlotsHost)
                ?.asStableRef<GtkCustomTitleBarWidget>()?.get() ?: return@staticCFunction
            owner.allocateSlots(width)
        }
    }
}
