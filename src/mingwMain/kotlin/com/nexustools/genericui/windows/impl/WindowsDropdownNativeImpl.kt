@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopDropdownNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.windows.Win32ControlMessageRouter
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.Win32RetainedControlSupport
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.windows.AppendMenuW
import platform.windows.BCN_DROPDOWN
import platform.windows.BN_CLICKED
import platform.windows.BS_SPLITBUTTON
import platform.windows.CreatePopupMenu
import platform.windows.DestroyMenu
import platform.windows.GetWindowRect
import platform.windows.MF_STRING
import platform.windows.RECT
import platform.windows.TPM_LEFTALIGN
import platform.windows.TPM_NONOTIFY
import platform.windows.TPM_RETURNCMD
import platform.windows.TPM_TOPALIGN
import platform.windows.TrackPopupMenuEx
import platform.windows.WS_CHILD
import platform.windows.WS_CLIPSIBLINGS
import platform.windows.WS_TABSTOP
import platform.windows.WS_VISIBLE

/**
 * Retained Win32 `"Button"` (`BS_SPLITBUTTON`) implementation for both `DropdownButton` and
 * `SplitDropdownButton`:
 * - `BCN_DROPDOWN` opens a genuine Win32 `TrackPopupMenuEx` anchored to the bottom-left edge of `GetWindowRect(hwnd)`.
 * - Primary button click (`BN_CLICKED`) calls [dispatchPrimaryAction] in split mode, or opens the
 *   popup menu in plain dropdown mode.
 */
class WindowsDropdownNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("Button")
) : DesktopDropdownNativeImpl<Win32HwndHandle>(widget) {

    init {
        nativeWidget.attributes["nativeImpl"] = this
        Win32ControlMessageRouter.createChildHwnd(
            handle = nativeWidget,
            owner = this,
            win32ClassName = "Button",
            dwStyle = WS_CHILD or WS_VISIBLE or WS_TABSTOP or WS_CLIPSIBLINGS or BS_SPLITBUTTON,
            initialText = labelText,
            initialWidth = 132,
            initialHeight = 32,
            installSubclass = true
        )
        wireNotifications()
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = false)
    }

    internal fun getDropdownItems(): List<Pair<String, String>> = itemsList

    internal fun notifyDropdownItemSelected(id: String) {
        onItemSelectedListener?.invoke(id)
    }

    private fun wireNotifications() {
        nativeWidget.onCommandCallback = { notifyCode, _, _ ->
            if (notifyCode == BN_CLICKED) {
                if (isSplitDropdownVariant()) {
                    dispatchPrimaryAction()
                } else {
                    openDropdownMenuAtBottomEdge()
                }
            }
        }
        nativeWidget.onNotifyCallback = { code, _ ->
            if (code == BCN_DROPDOWN.toUInt()) {
                openDropdownMenuAtBottomEdge()
                0L
            } else {
                null
            }
        }
    }

    internal fun openDropdownMenuAtBottomEdge() {
        val hwnd = nativeWidget.hwnd ?: return
        val items = itemsList
        if (items.isEmpty()) return
        val popup = CreatePopupMenu() ?: return
        try {
            items.forEachIndexed { idx, (_, itemLabel) ->
                AppendMenuW(popup, MF_STRING.toUInt(), (idx + 1).toULong(), itemLabel)
            }
            val pos = memScoped {
                val r = alloc<RECT>()
                GetWindowRect(hwnd, r.ptr)
                r.left to r.bottom
            }
            val flags = TPM_LEFTALIGN or TPM_TOPALIGN or TPM_RETURNCMD or TPM_NONOTIFY
            val chosen = TrackPopupMenuEx(popup, flags.toUInt(), pos.first, pos.second, hwnd, null)
            if (chosen > 0 && chosen <= items.size) {
                notifyDropdownItemSelected(items[chosen - 1].first)
            }
        } finally {
            DestroyMenu(popup)
        }
    }

    override fun onDropdownVariantChanged() {
        if (nativeWidget.hwnd == null) return
        Win32ControlMessageRouter.replaceChildHwndInPlace(
            handle = nativeWidget,
            owner = this,
            newWin32ClassName = "Button",
            newDwStyle = WS_CHILD or WS_VISIBLE or WS_TABSTOP or WS_CLIPSIBLINGS or BS_SPLITBUTTON,
            initialText = labelText,
            installSubclass = true
        )
        wireNotifications()
    }

    override fun setLabel(label: String) {
        super.setLabel(label)
        Win32RetainedControlSupport.setWindowTextIfChanged(nativeWidget.hwnd, label)
    }

    override fun setFocusedState(focused: Boolean) {
        super.setFocusedState(focused)
        Win32RetainedControlSupport.syncOsFocusIfNeeded(this, nativeWidget.hwnd, focused)
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size =
        Win32RetainedControlSupport.measureTextSize(
            text = labelText,
            fontHandle = nativeWidget.fontHandle,
            maxWidthPx = maxWidth,
            extraW = 46,
            extraH = 14,
            minW = 96,
            minH = 30
        )

    override fun onNativeBoundsChanged(rect: Rect) {
        Win32RetainedControlSupport.onLeafBoundsChanged(nativeWidget, rect)
    }

    override fun onNativeVisibilityChanged(visible: Boolean) {
        Win32RetainedControlSupport.onVisibilityChanged(nativeWidget, visible)
    }

    override fun onNativeEnabledChanged(enabled: Boolean) {
        Win32RetainedControlSupport.onEnabledChanged(nativeWidget, enabled)
    }

    override fun onNativeOpacityChanged(opacity: Float) {
        Win32RetainedControlSupport.onOpacityChanged(nativeWidget, opacity)
    }

    override fun onNativeTooltipChanged(tooltip: String?) {
        Win32RetainedControlSupport.onTooltipChanged(nativeWidget, tooltip)
    }

    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        Win32RetainedControlSupport.onCursorChanged(nativeWidget, cursor)
    }

    override fun onNativePointerEventsChanged(pointerEventsNone: Boolean) {
        Win32RetainedControlSupport.onPointerEventsChanged(nativeWidget, pointerEventsNone)
    }

    override fun onNativeZIndexChanged(zIndex: Int) {
        Win32RetainedControlSupport.onZIndexChanged(nativeWidget, zIndex)
    }

    override fun onNativeStyleSynced() {
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = false)
    }

    override fun dispose() {
        Win32RetainedControlSupport.onDispose(nativeWidget)
        super.dispose()
    }
}
