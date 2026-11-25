@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.dock.DesktopDockWorkspaceNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.enums.dock.DockPosition
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
import kotlin.math.roundToInt

/**
 * Retained Win32 multi-pane dock workspace (`"GenericUIDockWorkspaceW"`), arranging peripheral
 * `LEFT`/`RIGHT`/`TOP`/`BOTTOM` dock areas cleanly around the central document viewport.
 */
class WindowsDockWorkspaceNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("GenericUIDockWorkspaceW")
) : DesktopDockWorkspaceNativeImpl<Win32HwndHandle>(widget) {

    init {
        layoutController = Win32RetainedControlSupport.initContainer(
            container = this,
            handle = nativeWidget,
            win32ClassName = "GenericUIDockWorkspaceW"
        )
        nativeWidget.customPaintCallback = { hwnd, hdc, w, h ->
            val dark = Win32NativeWindowHost.isEffectivelyDarkTheme()
            val fallbackBg = if (dark) RgbaColor(24, 26, 30) else RgbaColor(235, 238, 242)
            Win32StyledWidgetHelper.renderStyledSpecToHdc(
                hwnd = hwnd,
                hdc = hdc,
                width = w,
                height = h,
                spec = StyledVisualSpec(backgroundColor = fallbackBg),
                isDark = dark
            )
        }
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = false)
    }

    override fun setCentralDocumentNative(centralImpl: NativeImpl<*>?) {
        super.setCentralDocumentNative(centralImpl)
        if (centralImpl != null && childImpls.none { it === centralImpl }) {
            addChildNative(centralImpl)
        }
        relayoutCurrentBounds()
    }

    override fun setWindowModeNative(modeName: String) {
        super.setWindowModeNative(modeName)
        relayoutCurrentBounds()
    }

    private fun relayoutCurrentBounds() {
        val w = nativeWidget.bounds.width.takeIf { it > 0 } ?: nativeWidget.lastAppliedW
        val h = nativeWidget.bounds.height.takeIf { it > 0 } ?: nativeWidget.lastAppliedH
        if (w > 0 && h > 0) {
            layoutChildrenInBounds(w.toFloat(), h.toFloat())
        }
    }

    override fun layoutChildrenInBounds(containerWidth: Float, containerHeight: Float) {
        val w = containerWidth.roundToInt().coerceAtLeast(1)
        val h = containerHeight.roundToInt().coerceAtLeast(1)
        if (dockAreas.isEmpty() && centralDocument == null) {
            super.layoutChildrenInBounds(containerWidth, containerHeight)
            return
        }

        var left = 0
        var top = 0
        var right = w
        var bottom = h

        val orderedBatch = ArrayList<DesktopBaseNativeImpl<*>>()

        fun placeArea(pos: DockPosition) {
            val area = dockAreas[pos] as? DesktopBaseNativeImpl<*> ?: return
            if (!area.isVisible() || area.isDisposed) return
            val collapsed = (dockAreas[pos]?.isCollapsed() == true)
            val pref = area.measureIntrinsicSize(w.toFloat(), h.toFloat())
            val rect = when (pos) {
                DockPosition.TOP -> {
                    val ah = if (collapsed) 28 else pref.height.coerceIn(48, (bottom - top) / 2)
                    val r = Rect(left, top, (right - left).coerceAtLeast(1), ah)
                    top += ah
                    r
                }
                DockPosition.BOTTOM -> {
                    val ah = if (collapsed) 28 else pref.height.coerceIn(48, (bottom - top) / 2)
                    val y = (bottom - ah).coerceAtLeast(top)
                    val r = Rect(left, y, (right - left).coerceAtLeast(1), (bottom - y).coerceAtLeast(1))
                    bottom = y
                    r
                }
                DockPosition.LEFT -> {
                    val aw = if (collapsed) 28 else pref.width.coerceIn(140, ((right - left) * 0.45f).roundToInt().coerceAtLeast(140))
                    val r = Rect(left, top, aw, (bottom - top).coerceAtLeast(1))
                    left += aw
                    r
                }
                DockPosition.RIGHT -> {
                    val aw = if (collapsed) 28 else pref.width.coerceIn(140, ((right - left) * 0.45f).roundToInt().coerceAtLeast(140))
                    val x = (right - aw).coerceAtLeast(left)
                    val r = Rect(x, top, (right - x).coerceAtLeast(1), (bottom - top).coerceAtLeast(1))
                    right = x
                    r
                }
                else -> Rect(left, top, (right - left).coerceAtLeast(1), (bottom - top).coerceAtLeast(1))
            }
            area.setBounds(rect)
            if (area is DesktopBaseContainerNativeImpl<*>) {
                area.layoutChildrenInBounds(rect.width.toFloat(), rect.height.toFloat())
            }
            orderedBatch.add(area)
        }

        placeArea(DockPosition.TOP)
        placeArea(DockPosition.BOTTOM)
        placeArea(DockPosition.LEFT)
        placeArea(DockPosition.RIGHT)

        val central = centralDocument as? DesktopBaseNativeImpl<*>
        if (central != null && !central.isDisposed) {
            val rect = Rect(left, top, (right - left).coerceAtLeast(1), (bottom - top).coerceAtLeast(1))
            central.setBounds(rect)
            if (central is DesktopBaseContainerNativeImpl<*>) {
                central.layoutChildrenInBounds(rect.width.toFloat(), rect.height.toFloat())
            }
            orderedBatch.add(central)
        }

        nativeWidget.hwnd?.let { Win32ControlMessageRouter.layoutChildrenBatch(it, orderedBatch) }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size =
        Size((maxWidth?.toInt() ?: 640).coerceAtLeast(240), (maxHeight?.toInt() ?: 400).coerceAtLeast(200))

    override fun onChildAddedNative(childImpl: NativeImpl<*>, index: Int) {
        Win32RetainedControlSupport.onContainerChildAdded(this, nativeWidget, childImpl)
    }

    override fun onChildRemovedNative(childImpl: NativeImpl<*>) {
        Win32RetainedControlSupport.onContainerChildRemoved(this, nativeWidget, childImpl)
    }

    override fun onChildrenClearedNative() {
        Win32RetainedControlSupport.onContainerChildrenCleared(nativeWidget, childImpls.toList())
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
