@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.impl

import com.nexustools.genericui.desktop.widget.DesktopCheckboxNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.gtk.widget.GtkCustomLayoutManager
import com.nexustools.genericui.gtk.widget.GtkSurfaceRenderHelper
import com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge
import com.nexustools.genericui.linux.LinuxGlibGioBindings
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlin.math.ceil
import kotlin.math.max

/**
 * Retained GTK4 toggle control mounting `GtkSwitch`, `GtkCheckButton`, or grouped radio `GtkCheckButton`.
 */
class GtkCheckboxNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkCheckButton"),
    switchStyle: Boolean = false,
    radioStyle: Boolean = false,
    classPrefix: String = when {
        switchStyle -> "switch"
        radioStyle -> "radio"
        else -> "checkbox"
    }
) : DesktopCheckboxNativeImpl<GtkWidgetHandle>(nativeWidget, switchStyle, radioStyle) {

    private var syncingFromModel = false
    private var signalRef: StableRef<GtkCheckboxNativeImpl>? = null
    private var registeredGroupKey: String? = null

    init {
        nativeWidget.ownerImpl = this
        nativeWidget.updateActiveGtkClassName(
            when {
                switchStyle -> "GtkSwitch"
                radioStyle -> "GtkCheckButton_Radio"
                else -> "GtkCheckButton"
            }
        )
        nativeWidget.widgetMaterializer = { materializeToggle() }
    }

    private fun materializeToggle(): COpaquePointer? {
        nativeWidget.realGtkWidgetPtr?.let { return it }
        if (!Gtk4DynamicBindings.ensureGtkInitialized()) return null

        signalRef?.dispose()
        val ref = StableRef.create(this)
        signalRef = ref

        val widgetPtr: COpaquePointer
        if (switchStyle) {
            val sw = Gtk4DynamicBindings.gtkSwitchNew() ?: return null
            syncingFromModel = true
            try {
                Gtk4DynamicBindings.gtkSwitchSetActive(sw, checkedState)
            } finally {
                syncingFromModel = false
            }
            LinuxGlibGioBindings.signalConnect(
                instance = sw,
                signalName = "notify::active",
                handler = switchActiveChangedCb.reinterpret(),
                userData = ref.asCPointer()
            )
            nativeWidget.primaryLeafPtr = sw
            widgetPtr = sw
        } else {
            val cb = Gtk4DynamicBindings.gtkCheckButtonNew(labelText.ifEmpty { null }) ?: return null
            syncingFromModel = true
            try {
                Gtk4DynamicBindings.gtkCheckButtonSetActive(cb, checkedState)
            } finally {
                syncingFromModel = false
            }
            if (radioStyle) {
                attachRadioGroup(cb)
            }
            LinuxGlibGioBindings.signalConnect(
                instance = cb,
                signalName = "toggled",
                handler = checkButtonToggledCb.reinterpret(),
                userData = ref.asCPointer()
            )
            nativeWidget.primaryLeafPtr = cb
            widgetPtr = cb
        }

        GtkWidgetLifecycleBridge.attachControllersAndState(
            impl = this,
            rootPtr = widgetPtr,
            focusTargetPtr = widgetPtr,
            isTextInput = false,
            dispatchClickOnPrimaryRelease = false,
            attachClickGesture = true
        )
        return widgetPtr
    }

    private fun attachRadioGroup(cb: COpaquePointer) {
        val groupKey = (nativeWidget.attributes["name"] as? String)?.takeIf { it.isNotBlank() } ?: "__default__"
        val members = radioGroups.getOrPut(groupKey) { mutableListOf() }
        registeredGroupKey = groupKey
        val leader = members.firstOrNull { it !== this }?.nativeWidget?.realGtkWidgetPtr
        if (!members.contains(this)) {
            members.add(this)
        }
        if (leader != null) {
            Gtk4DynamicBindings.gtkCheckButtonSetGroup(cb, leader)
        }
    }

    override fun setLabel(label: String) {
        super.setLabel(label)
        val ptr = nativeWidget.realGtkWidgetPtr ?: return
        if (!switchStyle) {
            Gtk4DynamicBindings.gtkCheckButtonSetLabel(ptr, label)
            GtkCustomLayoutManager.requestRelayout(ptr)
        }
    }

    override fun setChecked(checked: Boolean) {
        super.setChecked(checked)
        val ptr = nativeWidget.realGtkWidgetPtr ?: return
        syncingFromModel = true
        try {
            if (switchStyle) {
                Gtk4DynamicBindings.gtkSwitchSetActive(ptr, checked)
            } else {
                Gtk4DynamicBindings.gtkCheckButtonSetActive(ptr, checked)
            }
        } finally {
            syncingFromModel = false
        }
    }

    override fun setAttribute(name: String, value: String?) {
        super.setAttribute(name, value)
        if (radioStyle && name == "name") {
            val ptr = nativeWidget.realGtkWidgetPtr ?: return
            registeredGroupKey?.let { radioGroups[it]?.remove(this) }
            attachRadioGroup(ptr)
        }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val ptr = nativeWidget.realGtkWidgetPtr
        if (ptr != null) {
            val (w, h) = GtkCustomLayoutManager.measureGtkWidgetIntrinsic(ptr, maxWidth, maxHeight)
            if (w > 0 && h > 0) return Size(w, h)
        }
        if (switchStyle) return Size(48, 26)
        val (tw, th) = GtkSurfaceRenderHelper.measureTextSize(labelText.ifEmpty { "" }, 14f)
        val textW = if (labelText.isNotEmpty()) tw + 28 else 22
        return Size(textW, max(24, th + 6))
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

    override fun setFocusedState(focused: Boolean) {
        super.setFocusedState(focused)
        GtkWidgetLifecycleBridge.syncNativeFocus(this, isFocusedState())
    }

    override fun dispose() {
        registeredGroupKey?.let { key ->
            radioGroups[key]?.remove(this)
            if (radioGroups[key]?.isEmpty() == true) radioGroups.remove(key)
        }
        signalRef?.dispose()
        signalRef = null
        super.dispose()
    }

    companion object {
        private val radioGroups = mutableMapOf<String, MutableList<GtkCheckboxNativeImpl>>()

        private val checkButtonToggledCb = staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { cb, userData ->
            val impl = userData?.asStableRef<GtkCheckboxNativeImpl>()?.get() ?: return@staticCFunction
            if (impl.syncingFromModel || cb == null) return@staticCFunction
            val active = Gtk4DynamicBindings.gtkCheckButtonGetActive(cb)
            impl.dispatchToggle(active)
        }

        private val switchActiveChangedCb = staticCFunction<COpaquePointer?, COpaquePointer?, COpaquePointer?, Unit> { sw, _, userData ->
            val impl = userData?.asStableRef<GtkCheckboxNativeImpl>()?.get() ?: return@staticCFunction
            if (impl.syncingFromModel || sw == null) return@staticCFunction
            val active = Gtk4DynamicBindings.gtkSwitchGetActive(sw)
            impl.dispatchToggle(active)
        }
    }
}
