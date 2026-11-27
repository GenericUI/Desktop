@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos.impl

import com.nexustools.genericui.component.tabs.TabIconPlacement
import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTabNativeImpl
import com.nexustools.genericui.macos.MacosViewHandle

/**
 * macOS AppKit retained tab header item implementation of [DesktopTabNativeImpl].
 */
class MacosTabNativeImpl(
    nativeWidget: MacosViewHandle,
    initialClosable: Boolean = false
) : DesktopTabNativeImpl<MacosViewHandle>(nativeWidget, initialClosable) {

    internal var onTabUpdatedHook: (() -> Unit)? = null
    internal var onTabSelectedChangedHook: ((Boolean) -> Unit)? = null

    override fun setTitleNative(title: String) {
        super.setTitleNative(title)
        onTabUpdatedHook?.invoke()
    }

    override fun setIconNative(icon: String?) {
        super.setIconNative(icon)
        onTabUpdatedHook?.invoke()
    }

    override fun setIconSizePxNative(sizePx: Int) {
        super.setIconSizePxNative(sizePx)
        onTabUpdatedHook?.invoke()
    }

    override fun setIconPlacementNative(placement: TabIconPlacement) {
        super.setIconPlacementNative(placement)
        onTabUpdatedHook?.invoke()
    }

    override fun setTintImageToThemeNative(tintToTheme: Boolean) {
        super.setTintImageToThemeNative(tintToTheme)
        onTabUpdatedHook?.invoke()
    }

    override fun setClosableNative(closable: Boolean) {
        super.setClosableNative(closable)
        onTabUpdatedHook?.invoke()
    }

    override fun setDirtyNative(dirty: Boolean) {
        super.setDirtyNative(dirty)
        onTabUpdatedHook?.invoke()
    }

    override fun setPinnedNative(pinned: Boolean) {
        super.setPinnedNative(pinned)
        onTabUpdatedHook?.invoke()
    }

    override fun setEditableNative(editable: Boolean) {
        super.setEditableNative(editable)
        onTabUpdatedHook?.invoke()
    }

    override fun setBadgeNative(badgeText: String?, variantCss: String?) {
        super.setBadgeNative(badgeText, variantCss)
        onTabUpdatedHook?.invoke()
    }

    override fun setSelectedNative(selected: Boolean) {
        super.setSelectedNative(selected)
        val hook = onTabSelectedChangedHook
        if (hook != null) {
            hook.invoke(selected)
        } else {
            onTabUpdatedHook?.invoke()
        }
    }

    override fun setCustomHeaderComponentNative(customImpl: NativeImpl<*>?) {
        super.setCustomHeaderComponentNative(customImpl)
        onTabUpdatedHook?.invoke()
    }
}
