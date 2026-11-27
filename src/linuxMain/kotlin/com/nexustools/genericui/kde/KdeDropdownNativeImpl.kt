@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.desktop.widget.DesktopDropdownNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.COpaquePointer

/**
 * Retained KDE dropdown / split-dropdown implementation backed by Qt 6 **`QToolButton`** + KF6
 * **`KActionMenu`** (with `POPUP_MENU_BUTTON` split mode dispatching [dispatchPrimaryAction] on
 * primary button click, and `POPUP_INSTANT` menu popup for standard dropdowns).
 */
open class KdeDropdownNativeImpl(
    nativeWidget: KdeWidgetHandle = KdeWidgetHandle("QToolButton")
) : DesktopDropdownNativeImpl<KdeWidgetHandle>(nativeWidget) {

    private var qMenuPtr: COpaquePointer? = null
    private var kActionMenuPtr: COpaquePointer? = null

    init {
        KdeDynamicBindings.ensureQApplication()
        val ptr = KdeDynamicBindings.createQToolButton(null)
        nativeWidget.attachToQWidget(ptr, "QToolButton", customPainted = false)
        KdeVTableBridge.connectSignal(ptr, "clicked(bool)") {
            if (isSplitDropdownVariant()) {
                GenericUiThreading.runOnLogic {
                    dispatchPrimaryAction()
                }
            }
        }
        KdePainterSupport.attachStandardInputHooks(ptr, this, KdeVTableBridge.HOOK_RESIZE, dispatchLeftClickOnRelease = false)
        syncDropdownToNative()
    }

    private fun syncDropdownToNative() {
        val ptr = nativeWidget.qWidgetPtr ?: return
        if (nativeWidget.kdeDestroyed) return
        val split = isSplitDropdownVariant()
        KdeDynamicBindings.setAbstractButtonText(ptr, labelText)
        KdeDynamicBindings.setToolButtonPopupMode(
            ptr,
            if (split) KdeDynamicBindings.POPUP_MENU_BUTTON else KdeDynamicBindings.POPUP_INSTANT
        )

        if (kActionMenuPtr == null && !split) {
            val kAm = KdeDynamicBindings.createKActionMenu(labelText, ptr)
            if (kAm != null) {
                kActionMenuPtr = kAm
                qMenuPtr = KdeDynamicBindings.kActionMenuMenu(kAm)
                nativeWidget.activeKdeWidgetType = "KActionMenu_QToolButton"
            }
        }
        val menu = qMenuPtr ?: KdeDynamicBindings.createQMenu(ptr).also { qMenuPtr = it }
        KdeDynamicBindings.clearMenu(menu)
        for ((itemId, itemLabel) in itemsList) {
            val action = KdeDynamicBindings.addMenuAction(menu, itemLabel)
            if (action != null) {
                KdeVTableBridge.connectSignal(action, "triggered(bool)") {
                    GenericUiThreading.runOnLogic {
                        dispatchItemSelected(itemId)
                    }
                }
            }
        }
        KdeDynamicBindings.setToolButtonMenu(ptr, menu)
    }

    override fun onDropdownVariantChanged() { syncDropdownToNative() }
    override fun setLabel(label: String) { super.setLabel(label); syncDropdownToNative() }
    override fun setItems(items: List<Pair<String, String>>) { super.setItems(items); syncDropdownToNative() }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val measured = KdeDynamicBindings.measureTextSize(labelText.ifEmpty { "Options" }, 14f)
        val extra = if (isSplitDropdownVariant()) 44.0 else 32.0
        return Size((measured.first + extra).toFloat().coerceAtLeast(80f), 34f)
    }

    override fun onNativeBoundsChanged(rect: Rect) { nativeWidget.applyBoundsToNative(rect) }
    override fun onNativeVisibilityChanged(visible: Boolean) { nativeWidget.syncVisibilityToNative(visible) }
    override fun onNativeEnabledChanged(enabled: Boolean) { nativeWidget.qWidgetPtr?.let { KdeDynamicBindings.setWidgetEnabled(it, enabled) } }
    override fun onNativeTooltipChanged(tooltip: String?) {
        KdePainterSupport.applyCommonWidgetProperties(nativeWidget, customCursorState, nativeWidget.isSelfPointerEventsNone(), tooltip, getOpacity())
    }
    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        KdePainterSupport.applyCommonWidgetProperties(nativeWidget, cursor, nativeWidget.isSelfPointerEventsNone(), nativeWidget.tooltipText, getOpacity())
    }
    override fun onNativePointerEventsChanged(pointerEventsNone: Boolean) {
        KdePainterSupport.applyCommonWidgetProperties(nativeWidget, customCursorState, pointerEventsNone, nativeWidget.tooltipText, getOpacity())
    }
    override fun onNativeTransformChanged(transform: CssTransform?) { nativeWidget.requestRepaint() }

    override fun setFocusedState(focused: Boolean) {
        super.setFocusedState(focused)
        KdePainterSupport.syncWidgetFocus(this, isFocusedState())
    }

    override fun dispose() {
        qMenuPtr = null
        kActionMenuPtr = null
        nativeWidget.destroyQWidget()
        super.dispose()
    }
}
