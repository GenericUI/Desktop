@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.geometry.Rect
import kotlinx.cinterop.COpaquePointer

/**
 * Retained KDE (`QWidget*` / `KF6`) widget handle backing every node in the GenericUI KDE tree.
 *
 * Child widgets remain lightweight non-native alien `QWidget`s (never calling `winId()` or setting
 * `Qt::WA_NativeWindow`) so translucency, CSS `backdrop-filter`, z-index stacking, and pointer-event
 * transparency work without X11/Wayland native-child airspace artifacts.
 */
open class KdeWidgetHandle(
    kdeTypeName: String,
    var qWidgetPtr: COpaquePointer? = null,
    kdePointer: Long = nextPointerId()
) : DesktopNativeWidget(
    backendToolkit = "KDE_KF6_Breeze_Qt6",
    nativeClassName = kdeTypeName,
    nativePointer = kdePointer
) {
    val qtCssClasses: MutableSet<String> = linkedSetOf()
    var activeKdeWidgetType: String = kdeTypeName
    var isTopLevelWindow: Boolean = false
    var desiredVisible: Boolean = true
    var desiredParentPtr: COpaquePointer? = null
    var kdeDestroyed: Boolean = false
    var usesCustomPainting: Boolean = false
    var managesNativeClickSignal: Boolean = false
    var kdeWindowFlags: Int = KdeDynamicBindings.WINDOW_WINDOW
    private var savedFocusPolicy: Int? = null

    fun attachToQWidget(ptr: COpaquePointer?, widgetTypeName: String = activeKdeWidgetType, customPainted: Boolean = usesCustomPainting) {
        qWidgetPtr = ptr
        activeKdeWidgetType = widgetTypeName
        usesCustomPainting = customPainted
        kdeDestroyed = false
        savedFocusPolicy = null
        if (ptr != null) {
            KdeVTableBridge.registerWidgetFocusableCheck(ptr) { isEffectivelyFocusable() }
        }
        if (ptr != null && !isTopLevelWindow) {
            if (desiredParentPtr != null) {
                KdeDynamicBindings.setWidgetParent(ptr, desiredParentPtr)
                KdeDynamicBindings.setWidgetVisible(ptr, desiredVisible && visible)
            }
            applyBoundsToNative(bounds)
            KdeDynamicBindings.setWidgetEnabled(ptr, enabled)
            KdeDynamicBindings.setWidgetAttribute(
                ptr,
                KdeDynamicBindings.WA_TRANSPARENT_FOR_MOUSE_EVENTS,
                isSelfPointerEventsNone()
            )
            syncFocusableToNative()
        } else if (ptr != null) {
            syncFocusableToNative()
        }
    }

    fun swapQWidget(newPtr: COpaquePointer?, widgetTypeName: String, customPainted: Boolean): COpaquePointer? {
        val old = qWidgetPtr
        if (old === newPtr) {
            activeKdeWidgetType = widgetTypeName
            usesCustomPainting = customPainted
            return old
        }
        qWidgetPtr = null
        if (old != null) {
            KdeVTableBridge.unregisterWidgetFocusableCheck(old)
            if (!kdeDestroyed && KdeVTableBridge.isPeerAlive(old)) {
                KdeVTableBridge.destroyObject(old)
            }
        }
        attachToQWidget(newPtr, widgetTypeName, customPainted)
        return newPtr
    }

    fun syncChildParentAndVisibility(parentHandle: KdeWidgetHandle?) {
        val ptr = qWidgetPtr ?: return
        val parentPtr = parentHandle?.qWidgetPtr
        desiredParentPtr = parentPtr
        KdeDynamicBindings.setWidgetParent(ptr, parentPtr)
        if (parentPtr != null) {
            applyBoundsToNative(bounds)
            KdeDynamicBindings.setWidgetVisible(ptr, desiredVisible && visible)
        }
        syncFocusableToNative()
    }

    fun syncFocusableToNative() {
        val ptr = qWidgetPtr ?: return
        if (kdeDestroyed || !KdeVTableBridge.isPeerAlive(ptr)) return
        val effective = isEffectivelyFocusable()
        if (!effective) {
            if (savedFocusPolicy == null) {
                savedFocusPolicy = KdeDynamicBindings.getWidgetFocusPolicy(ptr)
            }
            KdeDynamicBindings.setWidgetFocusPolicy(ptr, KdeDynamicBindings.FOCUS_NO_FOCUS)
            if (KdeDynamicBindings.hasWidgetFocus(ptr)) {
                KdeDynamicBindings.setWidgetFocus(ptr, false)
            }
            (attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>)?.setFocusedState(false)
        } else {
            savedFocusPolicy?.let { prevPolicy ->
                savedFocusPolicy = null
                KdeDynamicBindings.setWidgetFocusPolicy(ptr, prevPolicy)
            }
        }
        for (child in children) {
            if (child is KdeWidgetHandle) {
                child.syncFocusableToNative()
            }
        }
    }

    override fun onEffectiveFocusableChanged(focusable: Boolean) {
        syncFocusableToNative()
    }

    fun applyBoundsToNative(rect: Rect) {
        val ptr = qWidgetPtr ?: return
        if (kdeDestroyed || !KdeVTableBridge.isPeerAlive(ptr)) return
        val x = rect.x
        val y = rect.y
        val w = rect.width.coerceAtLeast(0)
        val h = rect.height.coerceAtLeast(0)
        KdeDynamicBindings.setWidgetGeometry(ptr, x, y, w, h)
    }

    fun syncVisibilityToNative(nextVisible: Boolean) {
        desiredVisible = nextVisible
        val ptr = qWidgetPtr ?: return
        if (kdeDestroyed || !KdeVTableBridge.isPeerAlive(ptr)) return
        if (isTopLevelWindow || desiredParentPtr != null) {
            KdeDynamicBindings.setWidgetVisible(ptr, nextVisible)
        }
    }

    fun requestRepaint() {
        val ptr = qWidgetPtr ?: return
        if (!kdeDestroyed && KdeVTableBridge.isPeerAlive(ptr)) {
            KdeDynamicBindings.updateWidget(ptr)
        }
    }

    fun destroyQWidget() {
        val ptr = qWidgetPtr
        qWidgetPtr = null
        desiredParentPtr = null
        savedFocusPolicy = null
        kdeDestroyed = true
        if (ptr != null) {
            KdeVTableBridge.unregisterWidgetFocusableCheck(ptr)
            if (KdeVTableBridge.isPeerAlive(ptr)) {
                KdeVTableBridge.destroyObject(ptr)
            }
        }
    }
}
