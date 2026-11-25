@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.component.tabs.TabBarStyle
import com.nexustools.genericui.component.tabs.TabPosition
import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.layout.DesktopContainerLayoutController
import com.nexustools.genericui.desktop.widget.DesktopTabContainerNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.nativeimpl.tabs.TabNativeImpl
import com.nexustools.genericui.windows.Win32ControlMessageRouter
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.Win32RetainedControlSupport
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toLong
import kotlinx.cinterop.wcstr
import platform.windows.InvalidateRect
import platform.windows.NM_RCLICK
import platform.windows.RECT
import platform.windows.SWP_NOACTIVATE
import platform.windows.SWP_NOZORDER
import platform.windows.SW_HIDE
import platform.windows.SW_SHOWNOACTIVATE
import platform.windows.SendMessageW
import platform.windows.SetWindowPos
import platform.windows.ShowWindow
import platform.windows.TCIF_TEXT
import platform.windows.TCITEMW
import platform.windows.TCM_ADJUSTRECT
import platform.windows.TCM_DELETEALLITEMS
import platform.windows.TCM_GETCURSEL
import platform.windows.TCM_INSERTITEMW
import platform.windows.TCM_SETITEMW
import platform.windows.TCM_SETCURSEL
import platform.windows.TCN_SELCHANGE
import platform.windows.TCS_BOTTOM
import platform.windows.TCS_TABS
import platform.windows.TCS_VERTICAL
import platform.windows.WS_CHILD
import platform.windows.WS_CLIPCHILDREN
import platform.windows.WS_CLIPSIBLINGS
import platform.windows.WS_TABSTOP
import platform.windows.WS_VISIBLE

/**
 * Retained Win32 ComCtl32 `"SysTabControl32"` (`WC_TABCONTROLW`) implementation for `TabContainer`.
 *
 * Tab headers live natively inside `SysTabControl32` via `TCM_INSERTITEMW` / `TCM_SETITEMW` /
 * `TCM_SETCURSEL` / `TCN_SELCHANGE`, while tab content pages are parented inside the control's
 * `TCM_ADJUSTRECT` client rect and toggled with `SW_SHOWNOACTIVATE` / `SW_HIDE`.
 */
class WindowsTabContainerNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("SysTabControl32")
) : DesktopTabContainerNativeImpl<Win32HwndHandle>(widget) {

    private var syncingSelection: Boolean = false

    init {
        nativeWidget.attributes["nativeImpl"] = this
        Win32ControlMessageRouter.createChildHwnd(
            handle = nativeWidget,
            owner = this,
            win32ClassName = "SysTabControl32",
            dwStyle = resolveTabControlStyle(),
            initialWidth = 360,
            initialHeight = 240,
            installSubclass = true
        )
        layoutController = DesktopContainerLayoutController(this)
        wireCallbacks()
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = false)
    }

    private fun resolveTabControlStyle(): Int {
        var style = WS_CHILD or WS_VISIBLE or WS_TABSTOP or WS_CLIPCHILDREN or WS_CLIPSIBLINGS or TCS_TABS
        when (tabPositionVal) {
            TabPosition.BOTTOM -> style = style or TCS_BOTTOM
            TabPosition.LEFT -> style = style or TCS_VERTICAL
            TabPosition.RIGHT -> style = style or TCS_VERTICAL or TCS_BOTTOM
            TabPosition.TOP -> Unit
        }
        return style
    }

    private fun wireCallbacks() {
        nativeWidget.onNotifyCallback = { code, _ ->
            when (code) {
                TCN_SELCHANGE.toUInt() -> {
                    val hwnd = nativeWidget.hwnd
                    if (!syncingSelection && hwnd != null) {
                        val sel = SendMessageW(hwnd, TCM_GETCURSEL.toUInt(), 0uL, 0L).toInt()
                        if (sel in tabTitles.indices) {
                            setSelectedTabIndex(sel, fadeTransition = false)
                        }
                    }
                    0L
                }
                NM_RCLICK.toUInt() -> {
                    val hwnd = nativeWidget.hwnd
                    if (hwnd != null) {
                        val sel = SendMessageW(hwnd, TCM_GETCURSEL.toUInt(), 0uL, 0L).toInt()
                        if (sel in tabTitles.indices && (tabImpls.getOrNull(sel) as? WindowsTabNativeImpl)?.closableVal == true) {
                            requestCloseTab(sel)
                        }
                    }
                    0L
                }
                else -> null
            }
        }
    }

    private fun formatTabHeaderText(index: Int): String {
        val tabImpl = tabImpls.getOrNull(index) as? WindowsTabNativeImpl
        if (tabImpl != null) {
            return tabImpl.buildDisplayTitle().ifBlank { tabTitles.getOrNull(index).orEmpty() }
        }
        return tabTitles.getOrNull(index).orEmpty()
    }

    internal fun syncTabItemFromChild(tabImpl: WindowsTabNativeImpl) {
        val idx = tabImpls.indexOfFirst { it === tabImpl }
        if (idx < 0) return
        val hwnd = nativeWidget.hwnd ?: return
        val label = formatTabHeaderText(idx)
        memScoped {
            val item = alloc<TCITEMW>()
            item.mask = TCIF_TEXT.toUInt()
            item.pszText = label.wcstr.ptr
            SendMessageW(hwnd, TCM_SETITEMW.toUInt(), idx.toULong(), item.ptr.toLong())
        }
        layoutPagesInDisplayRect()
    }

    private fun syncTabControlItems() {
        val hwnd = nativeWidget.hwnd ?: return
        syncingSelection = true
        try {
            SendMessageW(hwnd, TCM_DELETEALLITEMS.toUInt(), 0uL, 0L)
            memScoped {
                val item = alloc<TCITEMW>()
                item.mask = TCIF_TEXT.toUInt()
                for (i in tabTitles.indices) {
                    (tabImpls.getOrNull(i) as? WindowsTabNativeImpl)?.parentTabContainer = this@WindowsTabContainerNativeImpl
                    val label = formatTabHeaderText(i)
                    item.pszText = label.wcstr.ptr
                    SendMessageW(hwnd, TCM_INSERTITEMW.toUInt(), i.toULong(), item.ptr.toLong())
                }
            }
            if (selectedIndexVal in tabTitles.indices) {
                SendMessageW(hwnd, TCM_SETCURSEL.toUInt(), selectedIndexVal.toULong(), 0L)
            }
        } finally {
            syncingSelection = false
        }
        layoutPagesInDisplayRect()
    }

    private fun computeContentDisplayRect(): Rect {
        val w = nativeWidget.bounds.width.takeIf { it > 0 } ?: nativeWidget.lastAppliedW.coerceAtLeast(1)
        val h = nativeWidget.bounds.height.takeIf { it > 0 } ?: nativeWidget.lastAppliedH.coerceAtLeast(1)
        val hwnd = nativeWidget.hwnd ?: return Rect(4, 30, (w - 8).coerceAtLeast(1), (h - 34).coerceAtLeast(1))
        return memScoped {
            val rc = alloc<RECT>()
            rc.left = 0
            rc.top = 0
            rc.right = w
            rc.bottom = h
            SendMessageW(hwnd, TCM_ADJUSTRECT.toUInt(), 0uL, rc.ptr.toLong())
            val cw = (rc.right - rc.left).coerceAtLeast(1)
            val ch = (rc.bottom - rc.top).coerceAtLeast(1)
            Rect(rc.left.coerceAtLeast(0), rc.top.coerceAtLeast(0), cw, ch)
        }
    }

    private fun layoutPagesInDisplayRect() {
        val hwnd = nativeWidget.hwnd ?: return
        val displayRect = computeContentDisplayRect()
        for (i in contentImpls.indices) {
            val content = contentImpls[i] as? DesktopBaseNativeImpl<*> ?: continue
            val contentHandle = content.nativeWidget as? Win32HwndHandle ?: continue
            val contentHwnd = contentHandle.hwnd ?: continue
            if (contentHandle.parentHwnd != hwnd) {
                Win32ControlMessageRouter.attachChildHwnd(contentHandle, hwnd)
            }
            val isActive = i == selectedIndexVal && !isButtonTabIndex(i)
            ShowWindow(contentHwnd, if (isActive) SW_SHOWNOACTIVATE else SW_HIDE)
            if (isActive) {
                content.setBounds(displayRect)
                SetWindowPos(
                    contentHwnd,
                    null,
                    displayRect.x,
                    displayRect.y,
                    displayRect.width,
                    displayRect.height,
                    (SWP_NOZORDER or SWP_NOACTIVATE).toUInt()
                )
                contentHandle.lastAppliedX = displayRect.x
                contentHandle.lastAppliedY = displayRect.y
                contentHandle.lastAppliedW = displayRect.width
                contentHandle.lastAppliedH = displayRect.height
                if (content is com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl<*>) {
                    content.layoutChildrenInBounds(displayRect.width.toFloat(), displayRect.height.toFloat())
                }
            }
        }
        InvalidateRect(hwnd, null, 0)
    }

    private fun isButtonTabIndex(index: Int): Boolean =
        index in buttonTabFlags.indices && buttonTabFlags[index]

    override fun addTabNative(tabImpl: TabNativeImpl<*>, contentImpl: NativeImpl<*>, index: Int) {
        (tabImpl as? WindowsTabNativeImpl)?.parentTabContainer = this
        super.addTabNative(tabImpl, contentImpl, index)
        syncTabControlItems()
    }

    override fun addButtonTabNative(tabImpl: TabNativeImpl<*>, index: Int) {
        (tabImpl as? WindowsTabNativeImpl)?.parentTabContainer = this
        super.addButtonTabNative(tabImpl, index)
        syncTabControlItems()
    }

    override fun addTabNative(title: String, contentImpl: NativeImpl<*>, index: Int) {
        super.addTabNative(title, contentImpl, index)
        syncTabControlItems()
    }

    override fun updateTabNative(index: Int, tabImpl: TabNativeImpl<*>) {
        (tabImpl as? WindowsTabNativeImpl)?.parentTabContainer = this
        super.updateTabNative(index, tabImpl)
        syncTabControlItems()
    }

    override fun removeTabNative(index: Int) {
        val oldContent = contentImpls.getOrNull(index)?.nativeWidget as? Win32HwndHandle
        super.removeTabNative(index)
        if (oldContent != null) {
            Win32ControlMessageRouter.detachChildHwnd(oldContent)
        }
        syncTabControlItems()
    }

    override fun setTabTitleNative(index: Int, title: String) {
        super.setTabTitleNative(index, title)
        val hwnd = nativeWidget.hwnd ?: return
        if (index in tabTitles.indices) {
            val label = formatTabHeaderText(index)
            memScoped {
                val item = alloc<TCITEMW>()
                item.mask = TCIF_TEXT.toUInt()
                item.pszText = label.wcstr.ptr
                SendMessageW(hwnd, TCM_SETITEMW.toUInt(), index.toULong(), item.ptr.toLong())
            }
        }
    }

    override fun setTabPositionNative(position: TabPosition) {
        super.setTabPositionNative(position)
        if (nativeWidget.hwnd != null) {
            Win32ControlMessageRouter.replaceChildHwndInPlace(
                handle = nativeWidget,
                owner = this,
                newWin32ClassName = "SysTabControl32",
                newDwStyle = resolveTabControlStyle(),
                installSubclass = true
            )
            wireCallbacks()
            syncTabControlItems()
        }
    }

    override fun setTabBarStyleNative(style: TabBarStyle) {
        super.setTabBarStyleNative(style)
        nativeWidget.hwnd?.let { InvalidateRect(it, null, 0) }
    }

    override fun setFullWidthTabsNative(fullWidth: Boolean) {
        super.setFullWidthTabsNative(fullWidth)
        layoutPagesInDisplayRect()
    }

    override fun setSelectedTabIndex(index: Int, fadeTransition: Boolean) {
        super.setSelectedTabIndex(index, fadeTransition)
        val hwnd = nativeWidget.hwnd
        if (hwnd != null && selectedIndexVal in tabTitles.indices) {
            syncingSelection = true
            try {
                SendMessageW(hwnd, TCM_SETCURSEL.toUInt(), selectedIndexVal.toULong(), 0L)
            } finally {
                syncingSelection = false
            }
        }
        layoutPagesInDisplayRect()
    }

    override fun onChildAddedNative(childImpl: NativeImpl<*>, index: Int) {
        if (childImpl is TabNativeImpl<*>) return
        val hwnd = nativeWidget.hwnd ?: return
        val base = childImpl as? DesktopBaseNativeImpl<*> ?: return
        val childHandle = base.nativeWidget as? Win32HwndHandle ?: return
        Win32ControlMessageRouter.attachChildHwnd(childHandle, hwnd)
        layoutPagesInDisplayRect()
    }

    override fun onChildRemovedNative(childImpl: NativeImpl<*>) {
        if (childImpl is TabNativeImpl<*>) return
        val childHandle = childImpl.nativeWidget as? Win32HwndHandle
        if (childHandle != null) {
            Win32ControlMessageRouter.detachChildHwnd(childHandle)
        }
        layoutPagesInDisplayRect()
    }

    override fun onChildrenClearedNative() {
        for (child in childImpls.toList()) {
            val childHandle = child.nativeWidget as? Win32HwndHandle ?: continue
            Win32ControlMessageRouter.detachChildHwnd(childHandle)
        }
        syncTabControlItems()
    }

    override fun layoutChildrenInBounds(containerWidth: Float, containerHeight: Float) {
        layoutPagesInDisplayRect()
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val tabBarW = tabTitles.sumOf { (it.length * 8 + 28).coerceAtLeast(64) }.coerceAtLeast(180)
        return Size(tabBarW, 200)
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        Win32RetainedControlSupport.onLeafBoundsChanged(nativeWidget, rect)
        if (rect.width > 0 && rect.height > 0) {
            layoutPagesInDisplayRect()
        }
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
        nativeWidget.hwnd?.let { InvalidateRect(it, null, 0) }
    }

    override fun dispose() {
        Win32RetainedControlSupport.onDispose(nativeWidget)
        super.dispose()
    }
}
