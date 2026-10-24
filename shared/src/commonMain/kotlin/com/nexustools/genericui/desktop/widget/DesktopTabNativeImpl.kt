package com.nexustools.genericui.desktop.widget

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.nativeimpl.tabs.TabNativeImpl

/**
 * Shared desktop native tab header item implementation ([TabNativeImpl]) for:
 * - `:windows`: Win32 `TCITEMW` inside `WC_TABCONTROLW` (`comctl32.dll`) with native close button / child `HWND`
 * - `:macos`: AppKit `NSTabViewItem` with native `NSStackView` header, `NSButton` close action, and custom `NSView`
 * - `:gtk`: GTK 4 / Libadwaita `AdwTabPage` / `GtkNotebookPage` with native `GtkBox` + `GtkButton("window-close-symbolic")`
 * - `:qt`: Qt 6 `QTabBar` item with `QTabBar::setTabText`, `setTabToolTip`, and `setTabButton(index, QTabBar::RightSide, widget)`
 */
open class DesktopTabNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W,
    initialClosable: Boolean = false
) : DesktopBaseContainerNativeImpl<W>(nativeWidget, "nav-item"), TabNativeImpl<W> {

    var tabIdVal: String = ""
        private set
    var titleVal: String = ""
        private set
    var iconVal: String? = null
        private set
    var tooltipVal: String? = null
        private set
    var closableVal: Boolean = initialClosable
        private set
    var dirtyVal: Boolean = false
        private set
    var pinnedVal: Boolean = false
        private set
    var editableVal: Boolean = false
        private set
    var badgeTextVal: String? = null
        private set
    var badgeVariantCssVal: String? = null
        private set
    var selectedVal: Boolean = false
        private set
    var customHeaderImpl: NativeImpl<*>? = null
        private set

    internal var onCloseClickedListener: (() -> Unit)? = null
    internal var onTitleEditedListener: ((String) -> Unit)? = null

    init {
        nativeWidget.attributes["closable"] = initialClosable
    }

    override fun setTabIdNative(id: String) {
        tabIdVal = id
        nativeWidget.attributes["tabId"] = id
    }

    override fun setTitleNative(title: String) {
        titleVal = title
        nativeWidget.attributes["title"] = title
    }

    override fun setIconNative(icon: String?) {
        iconVal = icon
        if (icon != null) {
            nativeWidget.attributes["icon"] = icon
        } else {
            nativeWidget.attributes.remove("icon")
        }
    }

    override fun setTooltipNative(tooltip: String?) {
        tooltipVal = tooltip
        nativeWidget.tooltipText = tooltip
    }

    override fun setClosableNative(closable: Boolean) {
        closableVal = closable
        nativeWidget.attributes["closable"] = closable
    }

    override fun setDirtyNative(dirty: Boolean) {
        dirtyVal = dirty
        nativeWidget.attributes["dirty"] = dirty
    }

    override fun setPinnedNative(pinned: Boolean) {
        pinnedVal = pinned
        nativeWidget.attributes["pinned"] = pinned
    }

    override fun setEditableNative(editable: Boolean) {
        editableVal = editable
        nativeWidget.attributes["editable"] = editable
    }

    override fun setBadgeNative(badgeText: String?, variantCss: String?) {
        badgeTextVal = badgeText
        badgeVariantCssVal = variantCss
        if (badgeText != null) {
            nativeWidget.attributes["badgeText"] = badgeText
            nativeWidget.attributes["badgeVariant"] = variantCss ?: "primary"
        } else {
            nativeWidget.attributes.remove("badgeText")
            nativeWidget.attributes.remove("badgeVariant")
        }
    }

    override fun setSelectedNative(selected: Boolean) {
        selectedVal = selected
        nativeWidget.attributes["selected"] = selected
    }

    override fun setCustomHeaderComponentNative(customImpl: NativeImpl<*>?) {
        customHeaderImpl?.let { removeChildNative(it) }
        customHeaderImpl = customImpl
        if (customImpl != null) {
            addChildNative(customImpl)
            nativeWidget.attributes["hasCustomHeader"] = true
        } else {
            nativeWidget.attributes["hasCustomHeader"] = false
        }
    }

    override fun setOnCloseClicked(listener: (() -> Unit)?) {
        onCloseClickedListener = listener
    }

    override fun setOnTitleEdited(listener: ((String) -> Unit)?) {
        onTitleEditedListener = listener
    }
}
