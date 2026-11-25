@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopMenuNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.nativeimpl.layout.DividerNativeImpl
import com.nexustools.genericui.nativeimpl.shell.MenuItemNativeImpl
import com.nexustools.genericui.nativeimpl.shell.MenuNativeImpl
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.COpaquePointer

/**
 * Retained KDE menu backed by Qt 6 **`QMenu`** supporting actions, checkable/radio toggles,
 * keyboard shortcuts, separators, and nested submenus.
 */
open class KdeMenuNativeImpl(
    nativeWidget: KdeWidgetHandle = KdeWidgetHandle("QMenu")
) : DesktopMenuNativeImpl<KdeWidgetHandle>(nativeWidget) {

    init {
        KdeDynamicBindings.ensureQApplication()
        val ptr = KdeDynamicBindings.createQMenu(null)
        nativeWidget.attachToQWidget(ptr, "QMenu", customPainted = false)
        rebuildNativeMenu()
    }

    internal fun rebuildNativeMenu() {
        val ptr = nativeWidget.qWidgetPtr ?: return
        if (!KdeVTableBridge.isPeerAlive(ptr)) return
        KdeDynamicBindings.setMenuTitle(ptr, labelText)
        populateQtMenu(ptr, childImpls)
    }

    override fun setLabel(label: String) {
        super.setLabel(label)
        nativeWidget.qWidgetPtr?.let { KdeDynamicBindings.setMenuTitle(it, label) }
    }

    override fun addChildNative(childImpl: NativeImpl<*>, index: Int) {
        super.addChildNative(childImpl, index)
        rebuildNativeMenu()
    }

    override fun removeChildNative(childImpl: NativeImpl<*>) {
        super.removeChildNative(childImpl)
        rebuildNativeMenu()
    }

    override fun clearChildrenNative() {
        super.clearChildrenNative()
        rebuildNativeMenu()
    }

    override fun onNativeBoundsChanged(rect: Rect) {}
    override fun onNativeVisibilityChanged(visible: Boolean) {}
    override fun onNativeEnabledChanged(enabled: Boolean) { nativeWidget.qWidgetPtr?.let { KdeDynamicBindings.setWidgetEnabled(it, enabled) } }
    override fun onNativeOpacityChanged(opacity: Float) {}
    override fun onNativeTooltipChanged(tooltip: String?) {}
    override fun onNativeCursorChanged(cursor: CssCursorValue?) {}
    override fun onNativePointerEventsChanged(pointerEventsNone: Boolean) {}
    override fun onNativeZIndexChanged(zIndex: Int) {}
    override fun onNativeTransformChanged(transform: CssTransform?) {}
    override fun onNativeStyleSynced() { rebuildNativeMenu() }

    override fun dispose() {
        super.dispose()
        nativeWidget.destroyQWidget()
    }

    companion object {
        internal fun populateQtMenu(
            menuPtr: COpaquePointer,
            children: List<NativeImpl<*>>,
            onAfterItemTriggered: (() -> Unit)? = null
        ) {
            KdeDynamicBindings.clearMenu(menuPtr)
            for (child in children) {
                when (child) {
                    is DividerNativeImpl<*> -> KdeDynamicBindings.addMenuSeparator(menuPtr)
                    is KdeMenuNativeImpl -> {
                        child.rebuildNativeMenu()
                        child.nativeWidget.qWidgetPtr?.let { subPtr ->
                            KdeDynamicBindings.addSubmenu(menuPtr, subPtr)
                        }
                    }
                    is MenuNativeImpl<*> -> {
                        (child.nativeWidget as? KdeWidgetHandle)?.qWidgetPtr?.let { subPtr ->
                            KdeDynamicBindings.setMenuTitle(subPtr, child.getLabel())
                            KdeDynamicBindings.addSubmenu(menuPtr, subPtr)
                        }
                    }
                    is KdeMenuItemNativeImpl -> {
                        val action = KdeDynamicBindings.addMenuAction(
                            menu = menuPtr,
                            text = child.getLabel(),
                            checkable = child.checkable || child.radio,
                            checked = child.isChecked(),
                            enabled = child.isEnabled(),
                            shortcutText = child.getShortcutText()
                        )
                        child.attachedQActionPtr = action
                        if (action != null) {
                            KdeVTableBridge.connectSignal(action, "triggered(bool)") {
                                GenericUiThreading.runOnLogic {
                                    if (!child.isEnabled()) return@runOnLogic
                                    if (child.checkable) {
                                        child.setChecked(!child.isChecked())
                                    } else if (child.radio) {
                                        child.setChecked(true)
                                    }
                                    child.dispatchClick()
                                    onAfterItemTriggered?.invoke()
                                }
                            }
                        }
                    }
                    is MenuItemNativeImpl<*> -> {
                        val action = KdeDynamicBindings.addMenuAction(
                            menu = menuPtr,
                            text = child.getLabel(),
                            checkable = false,
                            checked = child.isChecked(),
                            enabled = (child as? DesktopBaseNativeImpl<*>)?.isEnabled() ?: true
                        )
                        if (action != null && child is DesktopBaseNativeImpl<*>) {
                            KdeVTableBridge.connectSignal(action, "triggered(bool)") {
                                GenericUiThreading.runOnLogic {
                                    if (child.isEnabled()) child.dispatchClick()
                                    onAfterItemTriggered?.invoke()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
