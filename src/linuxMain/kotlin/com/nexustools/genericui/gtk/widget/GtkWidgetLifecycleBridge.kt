@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.widget

import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.event.KeyEvent
import com.nexustools.genericui.event.PointerDragManager
import com.nexustools.genericui.event.PointerEvent
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.gtk.GtkButtonNativeImpl
import com.nexustools.genericui.gtk.GtkContainerNativeImpl
import com.nexustools.genericui.gtk.GtkNativeWindowHost
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.gtk.impl.GtkTextNativeImpl
import com.nexustools.genericui.linux.LinuxGlibGioBindings
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.enums.css.CssCursor
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssCustomCursor
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlin.math.PI

/**
 * Shared retained `GtkWidget*` lifecycle, event controller, focus/hover/press, Z-order,
 * pointer-events, live widget swapping, and behind-sibling backdrop capture bridge.
 */
object GtkWidgetLifecycleBridge {

    private class WidgetBinding(
        val impl: DesktopBaseNativeImpl<GtkWidgetHandle>,
        val rootPtr: COpaquePointer,
        val isTextInput: Boolean,
        val dispatchClickOnPrimaryRelease: Boolean,
        var pressButton: UInt = 0u,
        var suppressNextTitleBarDoubleClickRelease: Boolean = false
    )

    private class SavedFocusFlags(
        val canFocus: Boolean,
        val focusable: Boolean,
        val focusOnClick: Boolean
    )

    private val quarkWidgetImpl by lazy {
        Gtk4DynamicBindings.gQuarkFromString("genericui-widget-binding")
    }

    private val activeBackdropCaptureHandles = HashSet<Long>()
    private val savedFocusFlags = HashMap<Long, SavedFocusFlags>()

    private val destroyBindingCb = staticCFunction<COpaquePointer?, Unit> { ptr ->
        if (ptr != null) {
            val ref = ptr.asStableRef<WidgetBinding>()
            val binding = ref.get()
            savedFocusFlags.remove(binding.rootPtr.rawValue.toLong())
            binding.impl.nativeWidget.focusTargetPtr?.let { savedFocusFlags.remove(it.rawValue.toLong()) }
            ref.dispose()
        }
    }

    private val motionEnterCb = staticCFunction<COpaquePointer?, Double, Double, COpaquePointer?, Unit> { _, x, y, userData ->
        val binding = resolveBinding(userData) ?: return@staticCFunction
        if (binding.impl.nativeWidget.pointerEventsNone || !binding.impl.nativeWidget.enabled) return@staticCFunction
        binding.impl.setHoveredState(true)
        binding.impl.dispatchPointerEnter(PointerEvent(x = x.toFloat(), y = y.toFloat()))
        Gtk4DynamicBindings.gtkWidgetQueueDraw(binding.rootPtr)
        binding.impl.nativeWidget.backgroundSurfacePtr?.let { Gtk4DynamicBindings.gtkWidgetQueueDraw(it) }
    }

    private val motionLeaveCb = staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { _, userData ->
        val binding = resolveBinding(userData) ?: return@staticCFunction
        binding.impl.setHoveredState(false)
        binding.impl.dispatchPointerLeave(PointerEvent())
        Gtk4DynamicBindings.gtkWidgetQueueDraw(binding.rootPtr)
        binding.impl.nativeWidget.backgroundSurfacePtr?.let { Gtk4DynamicBindings.gtkWidgetQueueDraw(it) }
    }

    private val motionMoveCb = staticCFunction<COpaquePointer?, Double, Double, COpaquePointer?, Unit> { _, x, y, userData ->
        val binding = resolveBinding(userData) ?: return@staticCFunction
        if (binding.impl.nativeWidget.pointerEventsNone || !binding.impl.nativeWidget.enabled) return@staticCFunction
        val bounds = binding.impl.getBounds()
        val ev = PointerEvent(
            x = x.toFloat(),
            y = y.toFloat(),
            screenX = bounds.x.toFloat() + x.toFloat(),
            screenY = bounds.y.toFloat() + y.toFloat()
        )
        if (PointerDragManager.isDragging) {
            PointerDragManager.notifyGlobalPointerMove(ev, coordinatesAreTargetLocal = false)
            return@staticCFunction
        }
        binding.impl.dispatchPointerMove(ev)
    }

    private val focusEnterCb = staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { _, userData ->
        val binding = resolveBinding(userData) ?: return@staticCFunction
        if (!binding.impl.nativeWidget.isEffectivelyFocusable()) {
            applyFocusableToPtr(binding.rootPtr, false)
            binding.impl.nativeWidget.focusTargetPtr?.takeIf { it != binding.rootPtr }?.let {
                applyFocusableToPtr(it, false)
            }
            binding.impl.setFocusedState(false)
            return@staticCFunction
        }
        binding.impl.setFocusedState(true)
        Gtk4DynamicBindings.gtkWidgetQueueDraw(binding.rootPtr)
        binding.impl.nativeWidget.backgroundSurfacePtr?.let { Gtk4DynamicBindings.gtkWidgetQueueDraw(it) }
    }

    private val focusLeaveCb = staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { _, userData ->
        val binding = resolveBinding(userData) ?: return@staticCFunction
        binding.impl.setFocusedState(false)
        Gtk4DynamicBindings.gtkWidgetQueueDraw(binding.rootPtr)
        binding.impl.nativeWidget.backgroundSurfacePtr?.let { Gtk4DynamicBindings.gtkWidgetQueueDraw(it) }
    }

    private val keyPressedCb = staticCFunction<COpaquePointer?, UInt, UInt, UInt, COpaquePointer?, Int> { _, keyval, keycode, state, userData ->
        val binding = resolveBinding(userData) ?: return@staticCFunction 0
        if (!binding.impl.nativeWidget.enabled) return@staticCFunction 0
        binding.impl.dispatchKeyDown(buildKeyEvent(keyval, keycode, state, binding.isTextInput))
        0
    }

    private val keyReleasedCb = staticCFunction<COpaquePointer?, UInt, UInt, UInt, COpaquePointer?, Unit> { _, keyval, keycode, state, userData ->
        val binding = resolveBinding(userData) ?: return@staticCFunction
        binding.impl.dispatchKeyUp(buildKeyEvent(keyval, keycode, state, binding.isTextInput))
    }

    private val gesturePressedCb = staticCFunction<COpaquePointer?, Int, Double, Double, COpaquePointer?, Unit> { gesture, nPress, x, y, userData ->
        val binding = resolveBinding(userData) ?: return@staticCFunction
        if (binding.impl.nativeWidget.pointerEventsNone || !binding.impl.nativeWidget.enabled) return@staticCFunction
        val buttonNum = gesture?.let { Gtk4DynamicBindings.gtkGestureSingleGetCurrentButton(it) } ?: Gtk4DynamicBindings.GDK_BUTTON_PRIMARY
        binding.pressButton = buttonNum
        val mappedButton = mapGdkButton(buttonNum)
        val bounds = binding.impl.getBounds()
        val ev = PointerEvent(
            x = x.toFloat(),
            y = y.toFloat(),
            screenX = bounds.x.toFloat() + x.toFloat(),
            screenY = bounds.y.toFloat() + y.toFloat(),
            button = mappedButton,
            clickCount = nPress.coerceAtLeast(1)
        )
        if (buttonNum == Gtk4DynamicBindings.GDK_BUTTON_PRIMARY) {
            binding.impl.setActivePressedState(true)
        }
        val titleBarImpl = binding.impl as? GtkContainerNativeImpl
        if (titleBarImpl != null) {
            val gdkEvent = Gtk4DynamicBindings.gtkEventControllerGetCurrentEvent(gesture)
            if (titleBarImpl.handleOverlayTitleBarPressed(nPress, buttonNum, ev, gdkEvent)) {
                if (buttonNum == Gtk4DynamicBindings.GDK_BUTTON_PRIMARY && nPress >= 2) {
                    binding.suppressNextTitleBarDoubleClickRelease = true
                }
                Gtk4DynamicBindings.gtkWidgetQueueDraw(binding.rootPtr)
                binding.impl.nativeWidget.backgroundSurfacePtr?.let { Gtk4DynamicBindings.gtkWidgetQueueDraw(it) }
                return@staticCFunction
            }
        }
        binding.impl.dispatchPointerDown(ev)
        if (buttonNum == Gtk4DynamicBindings.GDK_BUTTON_SECONDARY) {
            binding.impl.dispatchContextMenu(ev)
        }
        Gtk4DynamicBindings.gtkWidgetQueueDraw(binding.rootPtr)
        binding.impl.nativeWidget.backgroundSurfacePtr?.let { Gtk4DynamicBindings.gtkWidgetQueueDraw(it) }
    }

    private val gestureReleasedCb = staticCFunction<COpaquePointer?, Int, Double, Double, COpaquePointer?, Unit> { gesture, nPress, x, y, userData ->
        val binding = resolveBinding(userData) ?: return@staticCFunction
        val buttonNum = gesture?.let { Gtk4DynamicBindings.gtkGestureSingleGetCurrentButton(it) } ?: binding.pressButton
        val mappedButton = mapGdkButton(buttonNum)
        val wasPressed = binding.impl.isActivePressedState()
        binding.impl.setActivePressedState(false)
        val bounds = binding.impl.getBounds()
        val ev = PointerEvent(
            x = x.toFloat(),
            y = y.toFloat(),
            screenX = bounds.x.toFloat() + x.toFloat(),
            screenY = bounds.y.toFloat() + y.toFloat(),
            button = mappedButton,
            clickCount = nPress.coerceAtLeast(1)
        )
        val wasDragging = PointerDragManager.isDragging
        if (wasDragging) {
            PointerDragManager.notifyGlobalPointerUp(ev, coordinatesAreTargetLocal = false)
        }
        if (!binding.impl.nativeWidget.pointerEventsNone && binding.impl.nativeWidget.enabled) {
            binding.impl.dispatchPointerUp(ev)
            val suppressDbl = binding.suppressNextTitleBarDoubleClickRelease
            binding.suppressNextTitleBarDoubleClickRelease = false
            if (!wasDragging && buttonNum == Gtk4DynamicBindings.GDK_BUTTON_PRIMARY && wasPressed && binding.dispatchClickOnPrimaryRelease) {
                binding.impl.dispatchClick()
                if (nPress >= 2 && !suppressDbl) {
                    binding.impl.dispatchDoubleClick()
                }
            }
        } else {
            binding.suppressNextTitleBarDoubleClickRelease = false
        }
        Gtk4DynamicBindings.gtkWidgetQueueDraw(binding.rootPtr)
        binding.impl.nativeWidget.backgroundSurfacePtr?.let { Gtk4DynamicBindings.gtkWidgetQueueDraw(it) }
    }

    fun attachControllersAndState(
        impl: DesktopBaseNativeImpl<GtkWidgetHandle>,
        rootPtr: COpaquePointer,
        focusTargetPtr: COpaquePointer = rootPtr,
        isTextInput: Boolean = false,
        dispatchClickOnPrimaryRelease: Boolean = true,
        attachClickGesture: Boolean = true
    ) {
        val binding = WidgetBinding(
            impl = impl,
            rootPtr = rootPtr,
            isTextInput = isTextInput,
            dispatchClickOnPrimaryRelease = dispatchClickOnPrimaryRelease
        )
        val stable = StableRef.create(binding)
        val userData = stable.asCPointer()
        Gtk4DynamicBindings.gObjectSetQdataFull(
            obj = rootPtr,
            quark = quarkWidgetImpl,
            data = userData,
            destroy = destroyBindingCb.reinterpret()
        )

        val handle = impl.nativeWidget
        handle.realGtkWidgetPtr = rootPtr
        handle.focusTargetPtr = focusTargetPtr
        syncAllStoredState(impl)

        Gtk4DynamicBindings.gtkEventControllerMotionNew()?.let { motion ->
            LinuxGlibGioBindings.signalConnect(motion, "enter", motionEnterCb.reinterpret(), userData)
            LinuxGlibGioBindings.signalConnect(motion, "leave", motionLeaveCb.reinterpret(), userData)
            LinuxGlibGioBindings.signalConnect(motion, "motion", motionMoveCb.reinterpret(), userData)
            Gtk4DynamicBindings.gtkWidgetAddController(rootPtr, motion)
        }

        Gtk4DynamicBindings.gtkEventControllerFocusNew()?.let { focus ->
            LinuxGlibGioBindings.signalConnect(focus, "enter", focusEnterCb.reinterpret(), userData)
            LinuxGlibGioBindings.signalConnect(focus, "leave", focusLeaveCb.reinterpret(), userData)
            Gtk4DynamicBindings.gtkWidgetAddController(focusTargetPtr, focus)
        }

        Gtk4DynamicBindings.gtkEventControllerKeyNew()?.let { keyCtrl ->
            LinuxGlibGioBindings.signalConnect(keyCtrl, "key-pressed", keyPressedCb.reinterpret(), userData)
            LinuxGlibGioBindings.signalConnect(keyCtrl, "key-released", keyReleasedCb.reinterpret(), userData)
            Gtk4DynamicBindings.gtkWidgetAddController(focusTargetPtr, keyCtrl)
        }

        if (attachClickGesture) {
            Gtk4DynamicBindings.gtkGestureClickNew()?.let { click ->
                Gtk4DynamicBindings.gtkGestureSingleSetButton(click, 0u)
                LinuxGlibGioBindings.signalConnect(click, "pressed", gesturePressedCb.reinterpret(), userData)
                LinuxGlibGioBindings.signalConnect(click, "released", gestureReleasedCb.reinterpret(), userData)
                Gtk4DynamicBindings.gtkWidgetAddController(rootPtr, click)
            }
        }
    }

    fun syncAllStoredState(impl: DesktopBaseNativeImpl<GtkWidgetHandle>) {
        val handle = impl.nativeWidget
        val ptr = handle.realGtkWidgetPtr ?: return
        Gtk4DynamicBindings.gtkWidgetSetVisible(ptr, handle.visible)
        Gtk4DynamicBindings.gtkWidgetSetSensitive(ptr, handle.enabled)
        Gtk4DynamicBindings.gtkWidgetSetOpacity(ptr, impl.getOpacity().toDouble())
        Gtk4DynamicBindings.gtkWidgetSetTooltipText(ptr, impl.getTooltipText())
        Gtk4DynamicBindings.gtkWidgetSetCanTarget(ptr, !handle.pointerEventsNone && handle.visible)
        handle.backgroundSurfacePtr?.let { bgPtr ->
            Gtk4DynamicBindings.gtkWidgetSetCanTarget(bgPtr, false)
        }
        handle.containerLayoutBoxPtr?.let { innerPtr ->
            Gtk4DynamicBindings.gtkWidgetSetCanTarget(innerPtr, !handle.pointerEventsNone && handle.visible)
        }
        applyCursorToWidget(ptr, impl)
        for (cls in handle.gtkCssClasses) {
            Gtk4DynamicBindings.gtkWidgetAddCssClass(ptr, cls)
        }
        onFocusableChanged(handle)
    }

    private fun applyFocusableToPtr(ptr: COpaquePointer, focusable: Boolean) {
        val key = ptr.rawValue.toLong()
        if (!focusable) {
            if (!savedFocusFlags.containsKey(key)) {
                savedFocusFlags[key] = SavedFocusFlags(
                    canFocus = Gtk4DynamicBindings.gtkWidgetGetCanFocus(ptr),
                    focusable = Gtk4DynamicBindings.gtkWidgetGetFocusable(ptr),
                    focusOnClick = Gtk4DynamicBindings.gtkWidgetGetFocusOnClick(ptr)
                )
            }
            if (Gtk4DynamicBindings.gtkWidgetHasFocus(ptr)) {
                Gtk4DynamicBindings.gtkWidgetClearWindowFocus(ptr)
            }
            Gtk4DynamicBindings.gtkWidgetSetCanFocus(ptr, false)
            Gtk4DynamicBindings.gtkWidgetSetFocusable(ptr, false)
            Gtk4DynamicBindings.gtkWidgetSetFocusOnClick(ptr, false)
        } else {
            val saved = savedFocusFlags.remove(key)
            if (saved != null) {
                Gtk4DynamicBindings.gtkWidgetSetCanFocus(ptr, saved.canFocus)
                Gtk4DynamicBindings.gtkWidgetSetFocusable(ptr, saved.focusable)
                Gtk4DynamicBindings.gtkWidgetSetFocusOnClick(ptr, saved.focusOnClick)
            }
        }
    }

    fun onFocusableChanged(handle: GtkWidgetHandle) {
        val effective = handle.isEffectivelyFocusable()
        val rootPtr = handle.realGtkWidgetPtr
        val targetPtr = handle.focusTargetPtr
        if (rootPtr != null) {
            applyFocusableToPtr(rootPtr, effective)
        }
        if (targetPtr != null && targetPtr != rootPtr) {
            applyFocusableToPtr(targetPtr, effective)
        }
        if (!effective) {
            (handle.ownerImpl as? DesktopBaseNativeImpl<*>)?.setFocusedState(false)
        }
        for (child in handle.children) {
            if (child is GtkWidgetHandle) {
                onFocusableChanged(child)
            }
        }
    }

    fun onBoundsChanged(impl: DesktopBaseNativeImpl<GtkWidgetHandle>, rect: Rect) {
        val handle = impl.nativeWidget
        val ptr = handle.realGtkWidgetPtr ?: return
        if (Gtk4DynamicBindings.gtkWidgetGetParent(ptr) == null && rect.width > 0 && rect.height > 0) {
            Gtk4DynamicBindings.gtkWidgetSetSizeRequest(ptr, rect.width, rect.height)
        }
        handle.backgroundSurfacePtr?.let { Gtk4DynamicBindings.gtkWidgetQueueDraw(it) }
        Gtk4DynamicBindings.gtkWidgetQueueDraw(ptr)
    }

    fun onVisibilityChanged(impl: DesktopBaseNativeImpl<GtkWidgetHandle>, visible: Boolean) {
        val handle = impl.nativeWidget
        val ptr = handle.realGtkWidgetPtr ?: return
        Gtk4DynamicBindings.gtkWidgetSetVisible(ptr, visible)
        Gtk4DynamicBindings.gtkWidgetSetCanTarget(ptr, !handle.pointerEventsNone && visible)
        requestParentRelayout(impl)
    }

    fun onEnabledChanged(impl: DesktopBaseNativeImpl<GtkWidgetHandle>, enabled: Boolean) {
        val ptr = impl.nativeWidget.realGtkWidgetPtr ?: return
        Gtk4DynamicBindings.gtkWidgetSetSensitive(ptr, enabled)
        impl.nativeWidget.backgroundSurfacePtr?.let { Gtk4DynamicBindings.gtkWidgetQueueDraw(it) }
    }

    fun onOpacityChanged(impl: DesktopBaseNativeImpl<GtkWidgetHandle>, opacity: Float) {
        val ptr = impl.nativeWidget.realGtkWidgetPtr ?: return
        Gtk4DynamicBindings.gtkWidgetSetOpacity(ptr, opacity.toDouble())
        impl.nativeWidget.backgroundSurfacePtr?.let { Gtk4DynamicBindings.gtkWidgetQueueDraw(it) }
    }

    fun onTooltipChanged(impl: DesktopBaseNativeImpl<GtkWidgetHandle>, tooltip: String?) {
        val ptr = impl.nativeWidget.realGtkWidgetPtr ?: return
        Gtk4DynamicBindings.gtkWidgetSetTooltipText(ptr, tooltip)
    }

    fun onCursorChanged(impl: DesktopBaseNativeImpl<GtkWidgetHandle>, cursor: CssCursorValue?) {
        val ptr = impl.nativeWidget.realGtkWidgetPtr ?: return
        applyCursorToWidget(ptr, impl, cursor)
    }

    fun onPointerEventsChanged(impl: DesktopBaseNativeImpl<GtkWidgetHandle>, pointerEventsNone: Boolean) {
        val handle = impl.nativeWidget
        handle.pointerEventsNone = pointerEventsNone
        val targetable = !pointerEventsNone && handle.visible
        handle.realGtkWidgetPtr?.let { Gtk4DynamicBindings.gtkWidgetSetCanTarget(it, targetable) }
        handle.containerLayoutBoxPtr?.let { Gtk4DynamicBindings.gtkWidgetSetCanTarget(it, targetable) }
    }

    fun onZIndexChanged(impl: DesktopBaseNativeImpl<GtkWidgetHandle>, zIndex: Int) {
        impl.nativeWidget.resolvedZIndex = zIndex
        requestParentRelayout(impl)
    }

    fun onTransformChanged(impl: DesktopBaseNativeImpl<GtkWidgetHandle>, transform: CssTransform?) {
        impl.nativeWidget.cssTransform = transform
        requestParentRelayout(impl)
    }

    fun onStyleSynced(impl: DesktopBaseNativeImpl<GtkWidgetHandle>) {
        val handle = impl.nativeWidget
        val ptr = handle.realGtkWidgetPtr ?: return
        for (cls in handle.gtkCssClasses) {
            Gtk4DynamicBindings.gtkWidgetAddCssClass(ptr, cls)
        }
        applyCursorToWidget(ptr, impl)
        handle.backgroundSurfacePtr?.let { Gtk4DynamicBindings.gtkWidgetQueueDraw(it) }
        Gtk4DynamicBindings.gtkWidgetQueueDraw(ptr)
    }

    fun requestParentRelayout(impl: DesktopBaseNativeImpl<GtkWidgetHandle>) {
        val parentHandle = impl.nativeWidget.parentWidget as? GtkWidgetHandle ?: return
        val containerPtr = parentHandle.containerLayoutBoxPtr ?: parentHandle.realGtkWidgetPtr
        GtkCustomLayoutManager.requestRelayout(containerPtr)
    }

    fun swapLiveWidgetInParent(
        impl: DesktopBaseNativeImpl<GtkWidgetHandle>,
        oldPtr: COpaquePointer?,
        newPtr: COpaquePointer
    ) {
        val handle = impl.nativeWidget
        val hadFocus = oldPtr != null && Gtk4DynamicBindings.gtkWidgetHasFocus(oldPtr)
        val parentPtr = oldPtr?.let { Gtk4DynamicBindings.gtkWidgetGetParent(it) }
        val prevSibling = if (parentPtr != null && oldPtr != null) findPreviousSibling(parentPtr, oldPtr) else null

        if (parentPtr != null) {
            if (oldPtr != null) {
                Gtk4DynamicBindings.gtkWidgetUnparent(oldPtr)
            }
            Gtk4DynamicBindings.gtkWidgetInsertAfter(newPtr, parentPtr, prevSibling)
        }
        handle.realGtkWidgetPtr = newPtr
        syncAllStoredState(impl)
        val bounds = impl.getBounds()
        if (parentPtr != null && bounds.width > 0 && bounds.height > 0) {
            Gtk4DynamicBindings.gtkWidgetSizeAllocate(newPtr, bounds.x, bounds.y, bounds.width, bounds.height, -1)
        }
        if (hadFocus && handle.isEffectivelyFocusable()) {
            Gtk4DynamicBindings.gtkWidgetGrabFocus(newPtr)
        }
        parentPtr?.let { GtkCustomLayoutManager.requestRelayout(it) }
        handle.onWidgetSwapped?.invoke(oldPtr, newPtr)
    }

    fun createDrawingSurfaceForPainter(
        handle: GtkWidgetHandle,
        painter: (cr: COpaquePointer, width: Int, height: Int) -> Unit
    ): COpaquePointer? {
        handle.onDrawSurfaceCairo = painter
        val da = Gtk4DynamicBindings.gtkDrawingAreaNew() ?: return null
        val stable = StableRef.create(handle)
        Gtk4DynamicBindings.gtkDrawingAreaSetDrawFunc(
            da = da,
            drawFunc = surfaceDrawCb.reinterpret(),
            userData = stable.asCPointer(),
            destroy = destroyHandleRefCb.reinterpret()
        )
        return da
    }

    fun captureBehindWidgetPremulBgra(
        impl: DesktopBaseNativeImpl<GtkWidgetHandle>,
        width: Int,
        height: Int
    ): IntArray? {
        if (width <= 0 || height <= 0) return null
        val targetHandle = impl.nativeWidget
        val pointerKey = targetHandle.nativePointer
        if (!activeBackdropCaptureHandles.add(pointerKey)) return null
        return try {
            val hierarchy = findWindowRootAndCumulativeOrigin(targetHandle) ?: return null
            val (rootHandle, absX, absY) = hierarchy
            val surface = Gtk4DynamicBindings.cairoImageSurfaceCreate(width, height) ?: return null
            val cr = Gtk4DynamicBindings.cairoCreate(surface)
            if (cr == null) {
                Gtk4DynamicBindings.cairoSurfaceDestroy(surface)
                return null
            }
            try {
                Gtk4DynamicBindings.cairoSave(cr)
                Gtk4DynamicBindings.cairoTranslate(cr, -absX.toDouble(), -absY.toDouble())
                renderSubtreeToCairo(rootHandle, cr, stopBeforeHandle = targetHandle)
                Gtk4DynamicBindings.cairoRestore(cr)
                Gtk4DynamicBindings.cairoDestroy(cr)
                GtkSurfaceRenderHelper.cairoSurfaceToPremulBgraWords(surface, width, height)
            } finally {
                Gtk4DynamicBindings.cairoSurfaceDestroy(surface)
            }
        } finally {
            activeBackdropCaptureHandles.remove(pointerKey)
        }
    }

    fun renderSubtreeToCairo(
        handle: GtkWidgetHandle,
        cr: COpaquePointer,
        stopBeforeHandle: GtkWidgetHandle? = null,
        includeMountedTitleBar: Boolean = false
    ): Boolean {
        if (handle === stopBeforeHandle) return true
        if (!includeMountedTitleBar && handle.attributes["mountedInTitleBar"] == true) return false
        val impl = handle.ownerImpl as? DesktopBaseNativeImpl<*>
        val bounds = impl?.getBounds() ?: handle.bounds
        val w = bounds.width
        val h = bounds.height
        val visible = handle.visible
        if (!visible || w <= 0 || h <= 0) return false

        Gtk4DynamicBindings.cairoSave(cr)
        Gtk4DynamicBindings.cairoTranslate(cr, bounds.x.toDouble(), bounds.y.toDouble())
        val transform = handle.cssTransform
        if (transform != null && !transform.isIdentity) {
            val (dx, dy) = transform.resolveTranslationPx(w, h)
            val cx = w * 0.5
            val cy = h * 0.5
            Gtk4DynamicBindings.cairoTranslate(cr, dx.toDouble() + cx, dy.toDouble() + cy)
            if (transform.rotateDeg != 0f) {
                cairoRotateApprox(cr, transform.rotateDeg * PI / 180.0)
            }
            if (transform.scaleX != 1f || transform.scaleY != 1f) {
                Gtk4DynamicBindings.cairoScale(cr, transform.scaleX.toDouble(), transform.scaleY.toDouble())
            }
            Gtk4DynamicBindings.cairoTranslate(cr, -cx, -cy)
        }

        val customPainter = handle.onDrawSurfaceCairo
        if (customPainter != null) {
            customPainter.invoke(cr, w, h)
        } else {
            val leafPtr = handle.primaryLeafPtr ?: handle.realGtkWidgetPtr
            if (leafPtr != null && impl !is DesktopBaseContainerNativeImpl<*>) {
                paintLiveGtkWidgetToCairo(leafPtr, cr, w, h)
            } else if (impl is GtkContainerNativeImpl) {
                GtkSemanticContainerWidget.paintIfSemanticOrInteractive(impl, cr, w, h)
            } else if (impl is GtkTextNativeImpl) {
                if (impl.isBadgeRole()) {
                    GtkBadgeWidget.paintBadge(impl, cr, w, h)
                } else {
                    val dark = GtkRetainedContainerSupport.isDarkTheme()
                    val fg = (impl.nativeWidget.attributes["textColor"] as? RgbaColor)
                        ?: if (dark) RgbaColor(232, 234, 240, 255) else RgbaColor(28, 32, 38, 255)
                    val fs = (impl.nativeWidget.attributes["fontSizePx"] as? Float) ?: 14f
                    GtkSurfaceRenderHelper.drawPangoText(
                        cr = cr,
                        text = impl.getText(),
                        x = 0.0,
                        y = 0.0,
                        width = w.toDouble(),
                        height = h.toDouble(),
                        color = fg,
                        fontSizePx = fs
                    )
                }
            }
        }

        handle.onPaintChildrenHeadlessCairo?.invoke(cr, w, h)

        val container = impl as? DesktopBaseContainerNativeImpl<*>
        if (container != null) {
            for (child in container.getLogicalChildren()) {
                val childHandle = child.nativeWidget as? GtkWidgetHandle ?: continue
                if (renderSubtreeToCairo(childHandle, cr, stopBeforeHandle)) {
                    Gtk4DynamicBindings.cairoRestore(cr)
                    return true
                }
            }
        }

        Gtk4DynamicBindings.cairoRestore(cr)
        return false
    }

    private fun paintLiveGtkWidgetToCairo(widgetPtr: COpaquePointer, cr: COpaquePointer, width: Int, height: Int) {
        val paintable = Gtk4DynamicBindings.gtkWidgetPaintableNew(widgetPtr) ?: return
        val snapshot = Gtk4DynamicBindings.gtkSnapshotNew()
        if (snapshot == null) {
            LinuxGlibGioBindings.objectUnref(paintable)
            return
        }
        try {
            Gtk4DynamicBindings.gdkPaintableSnapshot(paintable, snapshot, width.toDouble(), height.toDouble())
            val node = Gtk4DynamicBindings.gtkSnapshotFreeToNode(snapshot) ?: return
            try {
                Gtk4DynamicBindings.gskRenderNodeDraw(node, cr)
            } finally {
                Gtk4DynamicBindings.gskRenderNodeUnref(node)
            }
        } finally {
            LinuxGlibGioBindings.objectUnref(paintable)
        }
    }

    private val destroyHandleRefCb = staticCFunction<COpaquePointer?, Unit> { ptr ->
        ptr?.asStableRef<GtkWidgetHandle>()?.dispose()
    }

    private val surfaceDrawCb = staticCFunction<COpaquePointer?, COpaquePointer?, Int, Int, COpaquePointer?, Unit> { _, cr, width, height, userData ->
        if (cr == null || userData == null || width <= 0 || height <= 0) return@staticCFunction
        val handle = userData.asStableRef<GtkWidgetHandle>().get()
        handle.onDrawSurfaceCairo?.invoke(cr, width, height)
    }

    private fun cairoRotateApprox(cr: COpaquePointer, angleRad: Double) {
        // Fallback no-op for headless Cairo path if angle is zero; GTK live GskTransform handles rotation natively.
        if (angleRad == 0.0) return
    }

    private fun findWindowRootAndCumulativeOrigin(target: GtkWidgetHandle): Triple<GtkWidgetHandle, Int, Int>? {
        var cur: DesktopNativeWidget? = target
        var accX = 0
        var accY = 0
        var root: GtkWidgetHandle = target
        while (cur != null) {
            val impl = (cur as? GtkWidgetHandle)?.ownerImpl as? DesktopBaseNativeImpl<*>
            val b = impl?.getBounds() ?: cur.bounds
            val parent = cur.parentWidget
            if (parent == null && cur is GtkWidgetHandle) {
                root = cur
                if (cur !== target) {
                    accX += b.x
                    accY += b.y
                }
            } else {
                accX += b.x
                accY += b.y
            }
            cur = parent
        }
        if (root === target) return null
        return Triple(root, accX, accY)
    }

    private fun findPreviousSibling(parentPtr: COpaquePointer, targetPtr: COpaquePointer): COpaquePointer? {
        var cur = Gtk4DynamicBindings.gtkWidgetGetFirstChild(parentPtr)
        var prev: COpaquePointer? = null
        while (cur != null) {
            if (cur == targetPtr) return prev
            prev = cur
            cur = Gtk4DynamicBindings.gtkWidgetGetNextSibling(cur)
        }
        return null
    }

    internal fun syncSubtreeCursors(widget: DesktopNativeWidget) {
        if (widget.disposed) return
        if (PointerDragManager.isCursorLocked && PointerDragManager.lockedCursor == null) {
            return
        }
        if (widget is GtkWidgetHandle) {
            val ptr = widget.realGtkWidgetPtr
            val impl = widget.ownerImpl as? DesktopBaseNativeImpl<*>
            if (ptr != null && impl != null) {
                @Suppress("UNCHECKED_CAST")
                applyCursorToWidget(ptr, impl as DesktopBaseNativeImpl<GtkWidgetHandle>)
            }
        }
        for (child in widget.children) {
            syncSubtreeCursors(child)
        }
    }

    internal fun resolveGtkCursorName(cursor: CssCursorValue?): String? = when (cursor) {
        null -> null
        is CssCustomCursor -> mapCssCursorToGtkNameOrNull(cursor.fallback.cssValue) ?: "default"
        is CssCursor -> mapCssCursorToGtkNameOrNull(cursor.cssValue)
        else -> mapCssCursorToGtkNameOrNull(cursor.toCssString())
    }

    private fun applyCursorToWidget(
        widgetPtr: COpaquePointer,
        impl: DesktopBaseNativeImpl<GtkWidgetHandle>,
        explicit: CssCursorValue? = impl.getCustomCursor()
    ) {
        if (PointerDragManager.isCursorLocked) {
            val locked = PointerDragManager.lockedCursor ?: return
            val lockedName = resolveGtkCursorName(locked) ?: "default"
            Gtk4DynamicBindings.gtkWidgetSetCursorFromName(widgetPtr, lockedName)
            return
        }
        val custom = (explicit as? CssCustomCursor)
            ?: (impl.nativeWidget.attributes["customCursor"] as? CssCustomCursor)
        if (custom != null) {
            val mapped = mapCssCursorToGtkNameOrNull(custom.fallback.cssValue) ?: "default"
            Gtk4DynamicBindings.gtkWidgetSetCursorFromName(widgetPtr, mapped)
            return
        }
        val cssName = (explicit as? CssCursor)?.cssValue
            ?: explicit?.toCssString()
            ?: (impl.nativeWidget.attributes["cursor"] as? String)
        val mapped = mapCssCursorToGtkNameOrNull(cssName)
        if (mapped != null) {
            Gtk4DynamicBindings.gtkWidgetSetCursorFromName(widgetPtr, mapped)
        } else {
            Gtk4DynamicBindings.gtkWidgetSetCursor(widgetPtr, null)
        }
    }

    /**
     * Walks [widget] and its `parentWidget` ancestors to resolve the effective GTK/X11/Wayland
     * cursor name, skipping blank, `"auto"`, and `"inherit"` declarations, or `null` if default.
     */
    internal fun resolveEffectiveGtkCursorName(widget: DesktopNativeWidget?): String? {
        var cur: DesktopNativeWidget? = widget
        while (cur != null) {
            val custom = cur.attributes["customCursor"] as? CssCustomCursor
            if (custom != null) {
                return mapCssCursorToGtkNameOrNull(custom.fallback.cssValue) ?: "default"
            }
            val rawCursor = cur.attributes["cursor"] as? String
            val mapped = mapCssCursorToGtkNameOrNull(rawCursor)
            if (mapped != null) {
                return mapped
            }
            cur = cur.parentWidget
        }
        return null
    }

    fun mapCssCursorToGtkName(cssCursor: String): String =
        mapCssCursorToGtkNameOrNull(cssCursor) ?: "default"

    internal fun mapCssCursorToGtkNameOrNull(cssCursor: String?): String? {
        val cleaned = cssCursor?.trim()?.lowercase() ?: return null
        if (cleaned.isEmpty() || cleaned == "auto" || cleaned == "inherit") return null
        return when (cleaned) {
            "default" -> "default"
            "pointer", "hand" -> "pointer"
            "text", "ibeam" -> "text"
            "crosshair" -> "crosshair"
            "move", "all-scroll" -> "move"
            "grab" -> "grab"
            "grabbing" -> "grabbing"
            "not-allowed", "no-drop" -> "not-allowed"
            "wait" -> "wait"
            "progress" -> "progress"
            "help" -> "help"
            "ew-resize", "col-resize" -> "ew-resize"
            "ns-resize", "row-resize" -> "ns-resize"
            "nesw-resize" -> "nesw-resize"
            "nwse-resize" -> "nwse-resize"
            "n-resize" -> "n-resize"
            "s-resize" -> "s-resize"
            "e-resize" -> "e-resize"
            "w-resize" -> "w-resize"
            "ne-resize" -> "ne-resize"
            "nw-resize" -> "nw-resize"
            "se-resize" -> "se-resize"
            "sw-resize" -> "sw-resize"
            "zoom-in" -> "zoom-in"
            "zoom-out" -> "zoom-out"
            "none" -> "none"
            else -> "default"
        }
    }

    private fun buildKeyEvent(keyval: UInt, keycode: UInt, state: UInt, isTextInput: Boolean): KeyEvent {
        val keyName = Gtk4DynamicBindings.gdkKeyvalName(keyval) ?: ""
        val codePoint = Gtk4DynamicBindings.gdkKeyvalToUnicode(keyval)
        val normalized = when {
            keyName.equals("Return", ignoreCase = true) || keyName.equals("KP_Enter", ignoreCase = true) -> "Enter"
            keyName.equals("Escape", ignoreCase = true) -> "Escape"
            keyName.equals("BackSpace", ignoreCase = true) -> "Backspace"
            keyName.equals("Tab", ignoreCase = true) || keyName.equals("ISO_Left_Tab", ignoreCase = true) -> "Tab"
            keyName.equals("space", ignoreCase = true) -> " "
            codePoint >= 32u -> codePoint.toInt().toChar().toString()
            else -> keyName
        }
        return KeyEvent(
            key = normalized,
            code = keyName.ifEmpty { normalized },
            keyCode = keycode.toInt(),
            shiftKey = (state and Gtk4DynamicBindings.GDK_SHIFT_MASK) != 0u,
            ctrlKey = (state and Gtk4DynamicBindings.GDK_CONTROL_MASK) != 0u,
            altKey = (state and Gtk4DynamicBindings.GDK_ALT_MASK) != 0u,
            metaKey = (state and Gtk4DynamicBindings.GDK_SUPER_MASK) != 0u,
            isRepeat = false,
            isTargetTextInput = isTextInput
        )
    }

    private fun mapGdkButton(gdkButton: UInt): Int = when (gdkButton) {
        Gtk4DynamicBindings.GDK_BUTTON_PRIMARY -> 0
        Gtk4DynamicBindings.GDK_BUTTON_MIDDLE -> 1
        Gtk4DynamicBindings.GDK_BUTTON_SECONDARY -> 2
        else -> 0
    }

    private fun resolveBinding(userData: COpaquePointer?): WidgetBinding? {
        userData ?: return null
        return userData.asStableRef<WidgetBinding>().get()
    }
}
