@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTabNativeImpl

/**
 * Retained KDE tab item descriptor syncing title, icon, closable, dirty, pinned, badge, selection,
 * and custom tab header components with its owning [KdeTabContainerNativeImpl].
 */
open class KdeTabNativeImpl(
    nativeWidget: KdeWidgetHandle = KdeWidgetHandle("QTabBar_Tab"),
    closable: Boolean = false
) : DesktopTabNativeImpl<KdeWidgetHandle>(nativeWidget, closable) {

    internal var ownerContainer: KdeTabContainerNativeImpl? = null

    private fun notifyOwnerChanged() {
        ownerContainer?.syncTabBarFromModel()
    }

    override fun setTitleNative(title: String) {
        super.setTitleNative(title)
        notifyOwnerChanged()
    }

    override fun setIconNative(icon: String?) {
        super.setIconNative(icon)
        notifyOwnerChanged()
    }

    override fun setClosableNative(closable: Boolean) {
        super.setClosableNative(closable)
        notifyOwnerChanged()
    }

    override fun setDirtyNative(dirty: Boolean) {
        super.setDirtyNative(dirty)
        notifyOwnerChanged()
    }

    override fun setPinnedNative(pinned: Boolean) {
        super.setPinnedNative(pinned)
        notifyOwnerChanged()
    }

    override fun setBadgeNative(badgeText: String?, variantCss: String?) {
        super.setBadgeNative(badgeText, variantCss)
        notifyOwnerChanged()
    }

    override fun setSelectedNative(selected: Boolean) {
        super.setSelectedNative(selected)
        notifyOwnerChanged()
    }

    override fun setCustomHeaderComponentNative(customImpl: NativeImpl<*>?) {
        super.setCustomHeaderComponentNative(customImpl)
        notifyOwnerChanged()
    }
}
