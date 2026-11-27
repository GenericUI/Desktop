@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopContainerNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.enums.css.CssDisplay
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.StyledVisualSpec
import com.nexustools.genericui.windows.Win32ControlMessageRouter
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.Win32NativeWindowHost
import com.nexustools.genericui.windows.Win32RetainedControlSupport
import com.nexustools.genericui.windows.Win32StyledWidgetHelper
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.windows.CreateSolidBrush
import platform.windows.DeleteObject
import platform.windows.FillRect
import platform.windows.IDC_SIZENS
import platform.windows.IDC_SIZEWE
import platform.windows.InvalidateRect
import platform.windows.LoadCursorW
import platform.windows.RECT
import platform.windows.ReleaseCapture
import platform.windows.SetCapture
import platform.windows.SetCursor
import platform.windows.WM_LBUTTONDOWN
import platform.windows.WM_LBUTTONUP
import platform.windows.WM_MOUSEMOVE
import platform.windows.WM_SETCURSOR
import kotlin.math.roundToInt

/**
 * Retained Win32 two-pane resizable split container (`"GenericUISplitPaneW"`), supporting both
 * horizontal and vertical split orientation, interactive divider dragging, and batched child layout.
 */
class WindowsSplitPaneNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("GenericUISplitPaneW"),
    classPrefix: String = "split-pane"
) : DesktopContainerNativeImpl<Win32HwndHandle>(widget, classPrefix) {

    private var splitRatio: Float = 0.5f
    private val dividerThicknessPx: Int = 6
    private var draggingDivider: Boolean = false

    init {
        layoutController = Win32RetainedControlSupport.initContainer(
            container = this,
            handle = nativeWidget,
            win32ClassName = "GenericUISplitPaneW",
            defaultDisplay = CssDisplay.FLEX
        )
        nativeWidget.customPaintCallback = { hwnd, hdc, w, h ->
            val dark = Win32NativeWindowHost.isEffectivelyDarkTheme()
            val surface = Win32StyledWidgetHelper.resolveEffectiveVisualSpec(
                nativeWidget,
                this,
                "GenericUISemanticSurfaceW",
                dark
            )
            Win32StyledWidgetHelper.renderStyledSpecToHdc(
                hwnd = hwnd,
                hdc = hdc,
                width = w,
                height = h,
                spec = StyledVisualSpec(
                    backgroundColor = surface.backgroundColor ?: if (dark) RgbaColor(28, 30, 35) else RgbaColor(246, 247, 249)
                ),
                isDark = dark
            )
            val divRect = computeDividerRect(w, h)
            val divBrush = CreateSolidBrush(if (dark) 0x0046423Cu else 0x00D8D4D0u)
            if (divBrush != null) {
                memScoped {
                    val rc = alloc<RECT>()
                    rc.left = divRect.x
                    rc.top = divRect.y
                    rc.right = divRect.x + divRect.width
                    rc.bottom = divRect.y + divRect.height
                    FillRect(hdc, rc.ptr, divBrush)
                }
                DeleteObject(divBrush)
            }
        }
        nativeWidget.customMessageCallback = { hwnd, msg, _, lParam ->
            handleSplitterMessage(hwnd, msg.toInt(), lParam)
        }
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = false)
    }

    private fun isVerticalSplit(): Boolean {
        val orient = (nativeWidget.attributes["orientation"] as? String)?.lowercase()
        return orient == "vertical" || "split-vertical" in nativeWidget.cachedAllClassTokens ||
            getClassTokensSet().any { it.contains("vertical", ignoreCase = true) }
    }

    private fun computeDividerRect(w: Int, h: Int): Rect {
        val vertical = isVerticalSplit()
        return if (vertical) {
            val divY = ((h - dividerThicknessPx) * splitRatio).roundToInt().coerceIn(24, (h - 24 - dividerThicknessPx).coerceAtLeast(24))
            Rect(0, divY, w, dividerThicknessPx)
        } else {
            val divX = ((w - dividerThicknessPx) * splitRatio).roundToInt().coerceIn(24, (w - 24 - dividerThicknessPx).coerceAtLeast(24))
            Rect(divX, 0, dividerThicknessPx, h)
        }
    }

    private fun handleSplitterMessage(hwnd: platform.windows.HWND, msgInt: Int, lParam: Long): Long? {
        val w = nativeWidget.bounds.width.takeIf { it > 0 } ?: nativeWidget.lastAppliedW
        val h = nativeWidget.bounds.height.takeIf { it > 0 } ?: nativeWidget.lastAppliedH
        if (w <= 0 || h <= 0) return null
        val vertical = isVerticalSplit()
        val x = (lParam and 0xFFFFL).toShort().toInt()
        val y = ((lParam shr 16) and 0xFFFFL).toShort().toInt()
        val divRect = computeDividerRect(w, h)
        val expandedHit = Rect(divRect.x - 3, divRect.y - 3, divRect.width + 6, divRect.height + 6)

        return when (msgInt) {
            WM_SETCURSOR -> {
                if (draggingDivider) {
                    SetCursor(LoadCursorW(null, if (vertical) IDC_SIZENS else IDC_SIZEWE))
                    1L
                } else {
                    null
                }
            }
            WM_LBUTTONDOWN -> {
                if (expandedHit.contains(x, y)) {
                    draggingDivider = true
                    SetCapture(hwnd)
                    0L
                } else {
                    null
                }
            }
            WM_MOUSEMOVE -> {
                if (draggingDivider) {
                    splitRatio = if (vertical) {
                        (y.toFloat() / h.coerceAtLeast(1)).coerceIn(0.1f, 0.9f)
                    } else {
                        (x.toFloat() / w.coerceAtLeast(1)).coerceIn(0.1f, 0.9f)
                    }
                    layoutChildrenInBounds(w.toFloat(), h.toFloat())
                    InvalidateRect(hwnd, null, 0)
                    0L
                } else {
                    null
                }
            }
            WM_LBUTTONUP -> {
                if (draggingDivider) {
                    draggingDivider = false
                    ReleaseCapture()
                    0L
                } else {
                    null
                }
            }
            else -> null
        }
    }

    override fun layoutChildrenInBounds(containerWidth: Float, containerHeight: Float) {
        val w = containerWidth.roundToInt().coerceAtLeast(1)
        val h = containerHeight.roundToInt().coerceAtLeast(1)
        val visibleChildren = getLogicalChildren().filterIsInstance<DesktopBaseNativeImpl<*>>()
            .filter { it.isVisible() && !it.isDisposed }
        if (visibleChildren.isEmpty()) return

        if (visibleChildren.size == 1) {
            val single = visibleChildren[0]
            val rect = Rect(0, 0, w, h)
            single.setBounds(rect)
            if (single is DesktopBaseContainerNativeImpl<*>) {
                single.layoutChildrenInBounds(w.toFloat(), h.toFloat())
            }
        } else {
            val divRect = computeDividerRect(w, h)
            val vertical = isVerticalSplit()
            val firstRect = if (vertical) Rect(0, 0, w, divRect.y.coerceAtLeast(1)) else Rect(0, 0, divRect.x.coerceAtLeast(1), h)
            val secondRect = if (vertical) {
                val top2 = (divRect.y + divRect.height).coerceAtMost(h)
                Rect(0, top2, w, (h - top2).coerceAtLeast(1))
            } else {
                val left2 = (divRect.x + divRect.width).coerceAtMost(w)
                Rect(left2, 0, (w - left2).coerceAtLeast(1), h)
            }
            visibleChildren[0].setBounds(firstRect)
            if (visibleChildren[0] is DesktopBaseContainerNativeImpl<*>) {
                (visibleChildren[0] as DesktopBaseContainerNativeImpl<*>).layoutChildrenInBounds(firstRect.width.toFloat(), firstRect.height.toFloat())
            }
            visibleChildren[1].setBounds(secondRect)
            if (visibleChildren[1] is DesktopBaseContainerNativeImpl<*>) {
                (visibleChildren[1] as DesktopBaseContainerNativeImpl<*>).layoutChildrenInBounds(secondRect.width.toFloat(), secondRect.height.toFloat())
            }
        }
        nativeWidget.hwnd?.let { Win32ControlMessageRouter.layoutChildrenBatch(it, visibleChildren) }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val w = (maxWidth?.toInt() ?: 480).coerceAtLeast(180)
        val h = (maxHeight?.toInt() ?: 260).coerceAtLeast(120)
        return Size(w, h)
    }

    override fun onChildAddedNative(childImpl: NativeImpl<*>, index: Int) {
        Win32RetainedControlSupport.onContainerChildAdded(this, nativeWidget, childImpl)
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
