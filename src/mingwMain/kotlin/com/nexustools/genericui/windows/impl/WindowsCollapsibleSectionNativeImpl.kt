@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopContainerNativeImpl
import com.nexustools.genericui.enums.bootstrap.FlexDirection
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.enums.css.CssDisplay
import com.nexustools.genericui.geometry.EdgeInsets
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.StyledVisualSpec
import com.nexustools.genericui.windows.Win32ControlMessageRouter
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.Win32NativeWindowHost
import com.nexustools.genericui.windows.Win32RetainedControlSupport
import com.nexustools.genericui.windows.Win32StyledWidgetHelper
import platform.windows.InvalidateRect
import platform.windows.SW_HIDE
import platform.windows.SW_SHOWNOACTIVATE
import platform.windows.ShowWindow
import platform.windows.WM_LBUTTONUP

/**
 * Retained Win32 container for `Collapse`, `Accordion`, and `Details` collapsible sections
 * (`"GenericUICollapsibleSectionW"`), featuring an interactive header bar and animated/toggled body child layout.
 */
class WindowsCollapsibleSectionNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("GenericUICollapsibleSectionW"),
    classPrefix: String = "accordion"
) : DesktopContainerNativeImpl<Win32HwndHandle>(widget, classPrefix) {

    private val headerHeightPx: Int = 34

    init {
        layoutController = Win32RetainedControlSupport.initContainer(
            container = this,
            handle = nativeWidget,
            win32ClassName = "GenericUICollapsibleSectionW",
            defaultDisplay = CssDisplay.FLEX,
            defaultFlexDirection = FlexDirection.COLUMN,
            defaultGapPx = 4,
            defaultPadding = EdgeInsets(top = 6, right = 10, bottom = 8, left = 10)
        )
        nativeWidget.customPaintCallback = { hwnd, hdc, w, h ->
            val dark = Win32NativeWindowHost.isEffectivelyDarkTheme()
            val bg = if (dark) RgbaColor(37, 40, 48) else RgbaColor(248, 249, 250)
            val border = if (dark) RgbaColor(65, 69, 78) else RgbaColor(222, 226, 230)
            val fg = if (dark) RgbaColor(236, 239, 244) else RgbaColor(33, 37, 41)
            val expanded = isSectionExpanded()
            val glyph = if (expanded) "\u25BE" else "\u25B8"
            val title = (nativeWidget.attributes["title"] as? String)
                ?: (nativeWidget.attributes["summary"] as? String)
                ?: "Section"
            Win32StyledWidgetHelper.renderStyledSpecToHdc(
                hwnd = hwnd,
                hdc = hdc,
                width = w,
                height = h,
                spec = StyledVisualSpec(
                    backgroundColor = bg,
                    textColor = fg,
                    borderColor = border,
                    borderWidthPx = 1f,
                    borderRadiusPx = 6f
                ),
                isDark = dark,
                fontHandle = nativeWidget.fontHandle,
                text = if (childImpls.isEmpty()) "$glyph  $title" else null,
                textColorOverride = fg,
                alignLeft = true
            )
        }
        nativeWidget.customMessageCallback = { hwnd, msg, _, lParam ->
            if (msg.toInt() == WM_LBUTTONUP) {
                val y = ((lParam shr 16) and 0xFFFFL).toShort().toInt()
                val headerLimit = if (childImpls.isEmpty()) headerHeightPx else 0
                if (y <= headerLimit) {
                    toggleExpandedState()
                    InvalidateRect(hwnd, null, 0)
                }
            }
            null
        }
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = false)
    }

    private fun isSectionExpanded(): Boolean {
        val attr = nativeWidget.attributes["expanded"] as? Boolean
            ?: nativeWidget.attributes["open"] as? Boolean
        return attr ?: true
    }

    private fun toggleExpandedState() {
        val next = !isSectionExpanded()
        nativeWidget.attributes["expanded"] = next
        nativeWidget.attributes["open"] = next
        syncChildExpansionVisibility()
        val w = nativeWidget.bounds.width.takeIf { it > 0 } ?: nativeWidget.lastAppliedW
        val h = nativeWidget.bounds.height.takeIf { it > 0 } ?: nativeWidget.lastAppliedH
        if (w > 0 && h > 0) {
            layoutChildrenInBounds(w.toFloat(), h.toFloat())
        }
        notifyTreeInvalidated()
    }

    private fun syncChildExpansionVisibility() {
        val expanded = isSectionExpanded()
        for ((idx, child) in childImpls.withIndex()) {
            val h = (child.nativeWidget as? Win32HwndHandle)?.hwnd ?: continue
            val visible = (idx == 0 || expanded) && (child as? DesktopBaseNativeImpl<*>)?.isVisible() != false
            ShowWindow(h, if (visible) SW_SHOWNOACTIVATE else SW_HIDE)
        }
    }

    override fun layoutChildrenInBounds(containerWidth: Float, containerHeight: Float) {
        super.layoutChildrenInBounds(containerWidth, containerHeight)
        syncChildExpansionVisibility()
        nativeWidget.hwnd?.let { host ->
            val children = getLogicalChildren().filterIsInstance<DesktopBaseNativeImpl<*>>()
            Win32ControlMessageRouter.layoutChildrenBatch(host, children)
        }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val expanded = isSectionExpanded()
        if (!expanded && childImpls.isEmpty()) {
            return Size(240, headerHeightPx)
        }
        val pref = layoutController?.measurePreferredSize(maxWidth, maxHeight) ?: Size(240, headerHeightPx)
        return Size(pref.width.coerceAtLeast(160), pref.height.coerceAtLeast(headerHeightPx))
    }

    override fun onChildAddedNative(childImpl: NativeImpl<*>, index: Int) {
        Win32RetainedControlSupport.onContainerChildAdded(this, nativeWidget, childImpl)
        syncChildExpansionVisibility()
    }

    override fun onChildRemovedNative(childImpl: NativeImpl<*>) {
        Win32RetainedControlSupport.onContainerChildRemoved(this, nativeWidget, childImpl)
    }

    override fun onChildrenClearedNative(clearedChildren: List<NativeImpl<*>>) {
        Win32RetainedControlSupport.onContainerChildrenCleared(nativeWidget, clearedChildren)
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        Win32RetainedControlSupport.onContainerBoundsChanged(this, nativeWidget, rect)
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
