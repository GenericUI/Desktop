@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.component.tabs.TabIconPlacement
import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTabNativeImpl
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.linux.LinuxTabIconHelper
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.coroutines.Job

/**
 * Retained KDE tab item descriptor syncing title, icon, closable, dirty, pinned, badge, selection,
 * and custom tab header components with its owning [KdeTabContainerNativeImpl].
 */
open class KdeTabNativeImpl(
    nativeWidget: KdeWidgetHandle = KdeWidgetHandle("QTabBar_Tab"),
    closable: Boolean = false
) : DesktopTabNativeImpl<KdeWidgetHandle>(nativeWidget, closable) {

    internal var ownerContainer: KdeTabContainerNativeImpl? = null
    internal var decodedIconBuffer: ImageBuffer? = null
        private set
    private var activeIconLoadJob: Job? = null
    private var iconLoadGeneration: Int = 0
    private var lastLoadedKey: String? = null

    private fun notifyOwnerChanged() {
        ownerContainer?.syncTabBarFromModel()
    }

    internal fun ensureIconLoadStarted(onReadyOnUi: () -> Unit) {
        val raw = iconVal?.trim().orEmpty()
        if (raw.isEmpty() || !LinuxTabIconHelper.looksLikeImageOrVectorSpec(raw)) {
            activeIconLoadJob?.cancel()
            activeIconLoadJob = null
            decodedIconBuffer = null
            lastLoadedKey = null
            return
        }
        val sz = iconSizePxVal.coerceIn(12, 32)
        val key = "$raw@$sz"
        if (lastLoadedKey == key && decodedIconBuffer != null) {
            return
        }
        activeIconLoadJob?.cancel()
        activeIconLoadJob = null
        val gen = ++iconLoadGeneration
        decodedIconBuffer = null
        lastLoadedKey = key
        activeIconLoadJob = LinuxTabIconHelper.launchLoadTabIcon(
            iconSpec = raw,
            targetSizePx = sz,
            onLoaded = { buf ->
                if (gen != iconLoadGeneration) return@launchLoadTabIcon
                decodedIconBuffer = buf
                GenericUiThreading.runOnUi {
                    if (gen == iconLoadGeneration) {
                        onReadyOnUi()
                    }
                }
            }
        )
    }

    override fun setTitleNative(title: String) {
        super.setTitleNative(title)
        notifyOwnerChanged()
    }

    override fun setIconNative(icon: String?) {
        super.setIconNative(icon)
        decodedIconBuffer = null
        lastLoadedKey = null
        ensureIconLoadStarted { ownerContainer?.applySingleTabIcon(this) }
        notifyOwnerChanged()
    }

    override fun setIconSizePxNative(sizePx: Int) {
        super.setIconSizePxNative(sizePx)
        decodedIconBuffer = null
        lastLoadedKey = null
        ensureIconLoadStarted { ownerContainer?.applySingleTabIcon(this) }
        notifyOwnerChanged()
    }

    override fun setIconPlacementNative(placement: TabIconPlacement) {
        super.setIconPlacementNative(placement)
        notifyOwnerChanged()
    }

    override fun setTintImageToThemeNative(tintToTheme: Boolean) {
        super.setTintImageToThemeNative(tintToTheme)
        ownerContainer?.applySingleTabIcon(this)
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

    override fun dispose() {
        activeIconLoadJob?.cancel()
        activeIconLoadJob = null
        decodedIconBuffer = null
        super.dispose()
    }
}
