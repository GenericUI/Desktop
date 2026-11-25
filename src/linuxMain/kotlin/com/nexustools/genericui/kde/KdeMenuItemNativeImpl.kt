@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.desktop.shell.DesktopMenuItemNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.event.KeyShortcut
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.COpaquePointer

/**
 * Retained KDE menu item descriptor syncing text, checkable/checked state, keyboard shortcuts,
 * and enabled state onto its live Qt 6 **`QAction`** inside an enclosing **`QMenu`**.
 */
open class KdeMenuItemNativeImpl(
    nativeWidget: KdeWidgetHandle = KdeWidgetHandle("QAction"),
    checkable: Boolean = false,
    radio: Boolean = false
) : DesktopMenuItemNativeImpl<KdeWidgetHandle>(nativeWidget, checkable, radio) {

    internal var attachedQActionPtr: COpaquePointer? = null

    internal fun syncToAttachedQAction() {
        val actPtr = attachedQActionPtr ?: return
        if (!KdeVTableBridge.isPeerAlive(actPtr)) return
        KdeDynamicBindings.setActionState(
            action = actPtr,
            text = labelText,
            checkable = checkable || radio,
            checked = checkedState,
            enabled = isEnabled(),
            visible = isVisible(),
            shortcutText = getShortcutText()
        )
    }

    override fun setLabel(label: String) {
        super.setLabel(label)
        syncToAttachedQAction()
    }

    override fun setShortcut(shortcut: KeyShortcut?) {
        super.setShortcut(shortcut)
        syncToAttachedQAction()
    }

    override fun setChecked(checked: Boolean) {
        super.setChecked(checked)
        syncToAttachedQAction()
    }

    override fun onNativeBoundsChanged(rect: Rect) {}
    override fun onNativeVisibilityChanged(visible: Boolean) { syncToAttachedQAction() }
    override fun onNativeEnabledChanged(enabled: Boolean) { syncToAttachedQAction() }
    override fun onNativeOpacityChanged(opacity: Float) {}
    override fun onNativeTooltipChanged(tooltip: String?) {}
    override fun onNativeCursorChanged(cursor: CssCursorValue?) {}
    override fun onNativePointerEventsChanged(pointerEventsNone: Boolean) {}
    override fun onNativeZIndexChanged(zIndex: Int) {}
    override fun onNativeTransformChanged(transform: CssTransform?) {}
    override fun onNativeStyleSynced() { syncToAttachedQAction() }
}
