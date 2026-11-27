@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.component.tabs.TabBarStyle
import com.nexustools.genericui.component.tabs.TabIconPlacement
import com.nexustools.genericui.component.tabs.TabPosition
import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.layout.DesktopContainerLayoutController
import com.nexustools.genericui.desktop.widget.DesktopTabContainerNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.nativeimpl.tabs.TabNativeImpl
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.windows.Win32ControlMessageRouter
import com.nexustools.genericui.windows.Win32DwmUxThemeBridge
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.Win32NativeWindowHost
import com.nexustools.genericui.windows.Win32RetainedControlSupport
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toLong
import kotlinx.cinterop.wcstr
import platform.windows.CreateSolidBrush
import platform.windows.FillRect
import platform.windows.HBRUSH
import platform.windows.HWND
import platform.windows.InvalidateRect
import platform.windows.IsWindow
import platform.windows.NM_RCLICK
import platform.windows.RECT
import platform.windows.ReleaseCapture
import platform.windows.SWP_NOACTIVATE
import platform.windows.SWP_NOZORDER
import platform.windows.SW_HIDE
import platform.windows.SW_SHOWNOACTIVATE
import platform.windows.SendMessageW
import platform.windows.SetCapture
import platform.windows.SetWindowPos
import platform.windows.ShowWindow
import platform.windows.TCIF_TEXT
import platform.windows.TCITEMW
import platform.windows.TCM_ADJUSTRECT
import platform.windows.TCM_DELETEALLITEMS
import platform.windows.TCM_GETCURSEL
import platform.windows.TCM_INSERTITEMW
import platform.windows.TCM_SETCURSEL
import platform.windows.TCM_SETITEMW
import platform.windows.TCN_SELCHANGE
import platform.windows.TCS_BOTTOM
import platform.windows.TCS_TABS
import platform.windows.TCS_VERTICAL
import platform.windows.WM_LBUTTONDOWN
import platform.windows.WM_LBUTTONUP
import platform.windows.WM_MOUSELEAVE
import platform.windows.WM_MOUSEMOVE
import platform.windows.WS_CHILD
import platform.windows.WS_CLIPCHILDREN
import platform.windows.WS_CLIPSIBLINGS
import platform.windows.WS_TABSTOP
import platform.windows.WS_VISIBLE

/**
 * Retained Win32 ComCtl32 `"SysTabControl32"` (`WC_TABCONTROLW`) implementation for `TabContainer`.
 *
 * Uses `TCS_OWNERDRAWFIXED` + parent-reflected `WM_DRAWITEM` to render tab favicons, ellipsized
 * titles, active selection accents, and hoverable/clickable `×` close buttons directly onto
 * native ComCtl32 tab items, while tab content pages are parented inside `TCM_ADJUSTRECT`.
 */
class WindowsTabContainerNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("SysTabControl32")
) : DesktopTabContainerNativeImpl<Win32HwndHandle>(widget) {

    companion object {
        private const val TCS_OWNERDRAWFIXED_FLAG: Int = 0x2000
        private const val TCM_GETITEMRECT_MSG: Int = 0x130A
        private const val TCM_SETPADDING_MSG: Int = 0x132B
        private const val ODS_SELECTED_FLAG: Int = 0x0001
        private const val ODT_TAB_TYPE: Int = 101
        private const val WM_CAPTURECHANGED_MSG: Int = 0x0215

        private val cachedBrushes = HashMap<UInt, HBRUSH>()

        private fun obtainBrush(colorRef: UInt): HBRUSH? =
            cachedBrushes[colorRef] ?: CreateSolidBrush(colorRef)?.also { cachedBrushes[colorRef] = it }

        internal fun closeButtonRectForTabBounds(
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            hasIcon: Boolean,
            iconSizePx: Int,
            closable: Boolean
        ): Rect? = WindowsTabNativeImpl.computeTabHeaderMetrics(
            rectLeft = left,
            rectTop = top,
            rectRight = right,
            rectBottom = bottom,
            hasIcon = hasIcon,
            iconSizePx = iconSizePx,
            iconPlacement = TabIconPlacement.START,
            closable = closable
        ).closeRect
    }

    private var syncingSelection: Boolean = false
    private var hoveredTabIndex: Int = -1
    private var hoveredCloseIndex: Int = -1
    private var pressedCloseIndex: Int = -1
    private var lastAppliedPadX: Int = -1

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
        var style = WS_CHILD or WS_VISIBLE or WS_TABSTOP or WS_CLIPCHILDREN or WS_CLIPSIBLINGS or
            TCS_TABS or TCS_OWNERDRAWFIXED_FLAG
        when (tabPositionVal) {
            TabPosition.BOTTOM -> style = style or TCS_BOTTOM
            TabPosition.LEFT -> style = style or TCS_VERTICAL
            TabPosition.RIGHT -> style = style or TCS_VERTICAL or TCS_BOTTOM
            TabPosition.TOP -> Unit
        }
        return style
    }

    private fun computeDesiredPadX(): Int {
        val hasAnyIcon = tabImpls.any {
            val t = it as? WindowsTabNativeImpl
            t != null && (t.decodedIconBuffer != null || !t.iconVal.isNullOrBlank())
        }
        val hasAnyClose = tabImpls.any { (it as? WindowsTabNativeImpl)?.closableVal == true }
        return when {
            hasAnyIcon && hasAnyClose -> 24
            hasAnyIcon || hasAnyClose -> 18
            else -> 10
        }
    }

    private fun hitTestTabAt(hwnd: HWND, mx: Int, my: Int): Int {
        return memScoped {
            val rc = alloc<RECT>()
            for (i in tabTitles.indices) {
                if (SendMessageW(hwnd, TCM_GETITEMRECT_MSG.toUInt(), i.toULong(), rc.ptr.toLong()) != 0L) {
                    if (mx in rc.left until rc.right && my in rc.top until rc.bottom) {
                        return@memScoped i
                    }
                }
            }
            -1
        }
    }

    internal fun hitTestTabCloseButtonAt(hwnd: HWND, mx: Int, my: Int): Int {
        return memScoped {
            val rc = alloc<RECT>()
            for (i in tabTitles.indices) {
                val tab = tabImpls.getOrNull(i) as? WindowsTabNativeImpl ?: continue
                if (!tab.closableVal) continue
                if (SendMessageW(hwnd, TCM_GETITEMRECT_MSG.toUInt(), i.toULong(), rc.ptr.toLong()) != 0L) {
                    val closeRect = closeButtonRectForTabBounds(
                        left = rc.left,
                        top = rc.top,
                        right = rc.right,
                        bottom = rc.bottom,
                        hasIcon = tab.decodedIconBuffer != null,
                        iconSizePx = tab.iconSizePxVal,
                        closable = true
                    )
                    if (closeRect != null &&
                        mx in closeRect.x until (closeRect.x + closeRect.width) &&
                        my in closeRect.y until (closeRect.y + closeRect.height)
                    ) {
                        return@memScoped i
                    }
                }
            }
            -1
        }
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

        nativeWidget.onDrawItemCallback = draw@{ disPtr ->
            val dis = disPtr.pointed
            if (dis.CtlType.toInt() != ODT_TAB_TYPE) return@draw null
            val hdc = dis.hDC ?: return@draw null
            val idx = dis.itemID.toInt()
            if (idx !in tabTitles.indices) return@draw null

            val rc = dis.rcItem
            val isSelected = ((dis.itemState.toInt() and ODS_SELECTED_FLAG) != 0) || idx == selectedIndexVal
            val isHovered = idx == hoveredTabIndex
            val dark = Win32NativeWindowHost.isEffectivelyDarkTheme()

            val bgRef = when {
                isSelected -> if (dark) Win32DwmUxThemeBridge.colorRefRgb(50, 54, 62) else Win32DwmUxThemeBridge.colorRefRgb(255, 255, 255)
                isHovered -> if (dark) Win32DwmUxThemeBridge.colorRefRgb(43, 46, 53) else Win32DwmUxThemeBridge.colorRefRgb(236, 239, 244)
                else -> if (dark) Win32DwmUxThemeBridge.colorRefRgb(32, 34, 39) else Win32DwmUxThemeBridge.colorRefRgb(243, 244, 246)
            }
            val fgColor = when {
                isSelected -> if (dark) RgbaColor(242, 245, 250) else RgbaColor(17, 24, 39)
                isHovered -> if (dark) RgbaColor(225, 229, 236) else RgbaColor(31, 41, 55)
                else -> if (dark) RgbaColor(180, 186, 196) else RgbaColor(75, 85, 99)
            }
            memScoped {
                val fillRc = alloc<RECT>()
                fillRc.left = rc.left
                fillRc.top = rc.top
                fillRc.right = rc.right
                fillRc.bottom = rc.bottom
                obtainBrush(bgRef)?.let { FillRect(hdc, fillRc.ptr, it) }

                if (isSelected) {
                    val accentRef = if (dark) Win32DwmUxThemeBridge.colorRefRgb(59, 130, 246) else Win32DwmUxThemeBridge.colorRefRgb(13, 110, 253)
                    val accentRc = alloc<RECT>()
                    accentRc.left = rc.left
                    accentRc.right = rc.right
                    if (tabPositionVal == TabPosition.BOTTOM) {
                        accentRc.top = (rc.bottom - 2).coerceAtLeast(rc.top)
                        accentRc.bottom = rc.bottom
                    } else {
                        accentRc.top = rc.top
                        accentRc.bottom = (rc.top + 2).coerceAtMost(rc.bottom)
                    }
                    obtainBrush(accentRef)?.let { FillRect(hdc, accentRc.ptr, it) }
                } else {
                    val divRef = if (dark) Win32DwmUxThemeBridge.colorRefRgb(56, 60, 68) else Win32DwmUxThemeBridge.colorRefRgb(210, 214, 222)
                    val divRc = alloc<RECT>()
                    divRc.left = (rc.right - 1).coerceAtLeast(rc.left)
                    divRc.top = rc.top + 4
                    divRc.right = rc.right
                    divRc.bottom = (rc.bottom - 4).coerceAtLeast(divRc.top + 1)
                    obtainBrush(divRef)?.let { FillRect(hdc, divRc.ptr, it) }
                }
            }

            val tabImpl = tabImpls.getOrNull(idx) as? WindowsTabNativeImpl
            WindowsTabNativeImpl.paintTabHeaderContent(
                hdc = hdc,
                rectLeft = rc.left,
                rectTop = rc.top,
                rectRight = rc.right,
                rectBottom = rc.bottom,
                label = formatTabHeaderText(idx),
                fontHandle = nativeWidget.fontHandle,
                iconBuffer = tabImpl?.decodedIconBuffer,
                iconSizePx = tabImpl?.iconSizePxVal ?: 16,
                iconPlacement = TabIconPlacement.START,
                tintToTheme = tabImpl?.tintImageToThemeVal ?: false,
                fgColor = fgColor,
                closable = tabImpl?.closableVal == true,
                closeHovered = idx == hoveredCloseIndex,
                closePressed = idx == pressedCloseIndex,
                isDark = dark
            )
            1L
        }

        nativeWidget.customMessageCallback = msg@{ hwnd, msg, _, lParam ->
            val mx = (lParam and 0xFFFFL).toShort().toInt()
            val my = ((lParam shr 16) and 0xFFFFL).toShort().toInt()
            when (msg.toInt()) {
                WM_MOUSEMOVE -> {
                    val nextTab = hitTestTabAt(hwnd, mx, my)
                    val nextClose = hitTestTabCloseButtonAt(hwnd, mx, my)
                    if (nextTab != hoveredTabIndex || nextClose != hoveredCloseIndex) {
                        hoveredTabIndex = nextTab
                        hoveredCloseIndex = nextClose
                        InvalidateRect(hwnd, null, 0)
                    }
                    null
                }
                WM_MOUSELEAVE, WM_CAPTURECHANGED_MSG -> {
                    if (hoveredTabIndex != -1 || hoveredCloseIndex != -1 || pressedCloseIndex != -1) {
                        hoveredTabIndex = -1
                        hoveredCloseIndex = -1
                        pressedCloseIndex = -1
                        InvalidateRect(hwnd, null, 0)
                    }
                    null
                }
                WM_LBUTTONDOWN -> {
                    val closeIdx = hitTestTabCloseButtonAt(hwnd, mx, my)
                    if (closeIdx >= 0) {
                        pressedCloseIndex = closeIdx
                        hoveredCloseIndex = closeIdx
                        SetCapture(hwnd)
                        InvalidateRect(hwnd, null, 0)
                        return@msg 0L
                    }
                    null
                }
                WM_LBUTTONUP -> {
                    if (pressedCloseIndex >= 0) {
                        val target = pressedCloseIndex
                        pressedCloseIndex = -1
                        ReleaseCapture()
                        val stillCloseIdx = hitTestTabCloseButtonAt(hwnd, mx, my)
                        hoveredCloseIndex = stillCloseIdx
                        InvalidateRect(hwnd, null, 0)
                        if (stillCloseIdx == target && target in tabTitles.indices) {
                            if (onTabCloseRequestedListener != null) {
                                requestCloseTab(target)
                            } else {
                                (tabImpls.getOrNull(target) as? WindowsTabNativeImpl)?.onCloseClickedListener?.invoke()
                            }
                        }
                        return@msg 0L
                    }
                    null
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
        if (IsWindow(hwnd) == 0) return
        if (computeDesiredPadX() != lastAppliedPadX) {
            syncTabControlItems()
            return
        }
        val label = formatTabHeaderText(idx)
        memScoped {
            val item = alloc<TCITEMW>()
            item.mask = TCIF_TEXT.toUInt()
            item.pszText = label.wcstr.ptr
            SendMessageW(hwnd, TCM_SETITEMW.toUInt(), idx.toULong(), item.ptr.toLong())
        }
        InvalidateRect(hwnd, null, 0)
    }

    private fun syncTabControlItems() {
        val hwnd = nativeWidget.hwnd ?: return
        syncingSelection = true
        try {
            val desiredPadX = computeDesiredPadX()
            lastAppliedPadX = desiredPadX
            SendMessageW(hwnd, TCM_SETPADDING_MSG.toUInt(), 0uL, ((6 shl 16) or (desiredPadX and 0xFFFF)).toLong())
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
        val insideBatch = Win32ControlMessageRouter.batchingHostLayoutDepth > 0
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
                val boundsChanged = content.getBounds() != displayRect
                if (boundsChanged) {
                    content.setBounds(displayRect)
                }
                if (insideBatch || !boundsChanged) {
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

    override fun onChildrenClearedNative(clearedChildren: List<NativeImpl<*>>) {
        for (child in clearedChildren) {
            if (child is TabNativeImpl<*>) continue
            val childHandle = child.nativeWidget as? Win32HwndHandle ?: continue
            Win32ControlMessageRouter.detachChildHwnd(childHandle)
        }
        syncTabControlItems()
        layoutPagesInDisplayRect()
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
