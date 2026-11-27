@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.widget.DesktopTextNativeImpl
import com.nexustools.genericui.enums.bootstrap.TextAlignment
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.windows.Win32ControlMessageRouter
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.Win32RetainedControlPaintCache
import com.nexustools.genericui.windows.Win32RetainedControlSupport
import platform.windows.GWL_STYLE
import platform.windows.GetWindowLongPtrW
import platform.windows.InvalidateRect
import platform.windows.IsWindow
import platform.windows.NM_CLICK
import platform.windows.NM_RETURN
import platform.windows.SS_CENTER
import platform.windows.SS_LEFT
import platform.windows.SS_NOTIFY
import platform.windows.SS_RIGHT
import platform.windows.SS_TYPEMASK
import platform.windows.SWP_FRAMECHANGED
import platform.windows.SWP_NOACTIVATE
import platform.windows.SWP_NOMOVE
import platform.windows.SWP_NOSIZE
import platform.windows.SWP_NOZORDER
import platform.windows.SetWindowLongPtrW
import platform.windows.SetWindowPos
import platform.windows.UpdateWindow
import platform.windows.WM_LBUTTONDOWN
import platform.windows.WS_CHILD
import platform.windows.WS_CLIPSIBLINGS
import platform.windows.WS_TABSTOP
import platform.windows.WS_VISIBLE

/**
 * Retained Win32 implementation for typography (`Text`, `Label`, `H1`..`H6` -> `"Static"`),
 * hyperlinks (`A` -> `"SysLink"` with `NM_CLICK`), and badges (`Badge` -> `"GenericUIBadgeW"`).
 */
class WindowsTextNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("Static"),
    tagName: String = "span",
    classPrefix: String = ""
) : DesktopTextNativeImpl<Win32HwndHandle>(widget, tagName, classPrefix) {

    private var pressClickDispatched: Boolean = false

    init {
        nativeWidget.attributes["nativeImpl"] = this
        val targetClass = resolveWin32ClassName()
        Win32ControlMessageRouter.createChildHwnd(
            handle = nativeWidget,
            owner = this,
            win32ClassName = targetClass,
            dwStyle = resolveStyle(targetClass),
            initialText = formatTextForClass(targetClass, textContent),
            initialWidth = 80,
            initialHeight = 20,
            installSubclass = targetClass != "GenericUIBadgeW"
        )
        wireLinkNotifications(targetClass)
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = true)
    }

    private fun resolveWin32ClassName(): String = when {
        isBadgeRole() -> "GenericUIBadgeW"
        tagName.equals("a", ignoreCase = true) -> "SysLink"
        else -> "Static"
    }

    private fun resolveStaticAlignmentStyle(): Int {
        when (getComponentStyle()?.getTextAlign()) {
            TextAlignment.CENTER -> return SS_CENTER
            TextAlignment.END -> return SS_RIGHT
            TextAlignment.START, TextAlignment.JUSTIFY -> return SS_LEFT
            null -> {}
        }
        when (resolveEffectiveStyle()?.textAlign?.trim()?.lowercase()) {
            "center" -> return SS_CENTER
            "right", "end" -> return SS_RIGHT
            "left", "start", "justify" -> return SS_LEFT
        }
        when (getTextAlignment()) {
            TextAlignment.CENTER -> return SS_CENTER
            TextAlignment.END -> return SS_RIGHT
            TextAlignment.START, TextAlignment.JUSTIFY -> return SS_LEFT
            null -> {}
        }
        val tokens = nativeWidget.cachedAllClassTokens
        if ("text-center" in tokens) return SS_CENTER
        if ("text-end" in tokens || "text-right" in tokens) return SS_RIGHT
        if ("text-start" in tokens || "text-left" in tokens) return SS_LEFT
        return SS_LEFT
    }

    private fun resolveStyle(targetClass: String): Int = when (targetClass) {
        "GenericUIBadgeW" -> WS_CHILD or WS_VISIBLE or WS_CLIPSIBLINGS
        "SysLink" -> WS_CHILD or WS_VISIBLE or WS_TABSTOP or WS_CLIPSIBLINGS
        else -> WS_CHILD or WS_VISIBLE or WS_CLIPSIBLINGS or SS_NOTIFY or resolveStaticAlignmentStyle()
    }

    private fun formatTextForClass(targetClass: String, text: String): String {
        if (targetClass != "SysLink") return text
        val escaped = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        return "<a>$escaped</a>"
    }

    private fun wireLinkNotifications(targetClass: String) {
        when (targetClass) {
            "SysLink" -> {
                nativeWidget.onNotifyCallback = { code, _ ->
                    if (code == NM_CLICK.toUInt() || code == NM_RETURN.toUInt()) {
                        dispatchClick()
                        0L
                    } else {
                        null
                    }
                }
                nativeWidget.onCommandCallback = null
                nativeWidget.customMessageCallback = null
            }
            "Static" -> {
                nativeWidget.onNotifyCallback = null
                nativeWidget.onCommandCallback = { notifyCode, _, _ ->
                    if (notifyCode == STN_CLICKED_CODE && isEnabled() && !isPointerInteractionDisabled()) {
                        if (!isActivePressedState() || !pressClickDispatched) {
                            if (isActivePressedState()) {
                                pressClickDispatched = true
                            }
                            dispatchClick()
                        }
                    }
                }
                nativeWidget.customMessageCallback = { _, msg, _, _ ->
                    if (msg.toInt() == WM_LBUTTONDOWN) {
                        pressClickDispatched = false
                    }
                    null
                }
            }
            else -> {
                nativeWidget.onNotifyCallback = null
                nativeWidget.onCommandCallback = null
                nativeWidget.customMessageCallback = null
            }
        }
    }

    internal fun dispatchStaticClickFromPointerUp() {
        val alreadyDispatched = pressClickDispatched
        pressClickDispatched = false
        if (!alreadyDispatched && isEnabled() && !isPointerInteractionDisabled()) {
            dispatchClick()
        }
    }

    private fun syncControlClassIfNeeded() {
        if (nativeWidget.hwnd == null) return
        val desiredClass = resolveWin32ClassName()
        if (nativeWidget.actualWin32ClassName != desiredClass) {
            Win32ControlMessageRouter.replaceChildHwndInPlace(
                handle = nativeWidget,
                owner = this,
                newWin32ClassName = desiredClass,
                newDwStyle = resolveStyle(desiredClass),
                initialText = formatTextForClass(desiredClass, textContent),
                installSubclass = desiredClass != "GenericUIBadgeW"
            )
            wireLinkNotifications(desiredClass)
        }
    }

    private fun syncStaticTextAlignment() {
        val hwnd = nativeWidget.hwnd ?: return
        if (nativeWidget.actualWin32ClassName != "Static" || IsWindow(hwnd) == 0) return
        val currentStyle = GetWindowLongPtrW(hwnd, GWL_STYLE).toInt()
        val desiredAlign = resolveStaticAlignmentStyle()
        val nextStyle = (currentStyle and SS_TYPEMASK.inv()) or desiredAlign
        if (nextStyle != currentStyle) {
            SetWindowLongPtrW(hwnd, GWL_STYLE, nextStyle.toLong())
            nativeWidget.dwStyle = nextStyle.toLong()
            Win32RetainedControlPaintCache.invalidate(hwnd)
            SetWindowPos(
                hwnd,
                null,
                0,
                0,
                0,
                0,
                (SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED).toUInt()
            )
        }
    }

    private fun isInsideStageHost(): Boolean {
        var cur: DesktopNativeWidget? = nativeWidget.parentWidget
        while (cur != null) {
            val handle = cur as? Win32HwndHandle
            if (handle?.actualWin32ClassName == "GenericUILayeredStageHostW" ||
                cur.attributes["isLayeredStageHost"] == true ||
                cur.attributes["interactiveSurfaceHost"] == true ||
                cur.attributes["stageOverlayHost"] == true
            ) {
                return true
            }
            cur = cur.parentWidget
        }
        return false
    }

    override fun setText(text: String) {
        super.setText(text)
        syncControlClassIfNeeded()
        val hwnd = nativeWidget.hwnd ?: return
        Win32RetainedControlSupport.setWindowTextIfChanged(
            hwnd,
            formatTextForClass(nativeWidget.actualWin32ClassName, text)
        )
        if (IsWindow(hwnd) != 0) {
            if (isInsideStageHost()) {
                val parent = nativeWidget.parentHwnd
                if (parent != null && IsWindow(parent) != 0) {
                    InvalidateRect(parent, null, 0)
                }
                InvalidateRect(hwnd, null, 0)
            } else {
                InvalidateRect(hwnd, null, 1)
                UpdateWindow(hwnd)
            }
        }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val isBadge = resolveWin32ClassName() == "GenericUIBadgeW"
        val resolvedFont = nativeWidget.fontHandle ?: Win32ControlMessageRouter.resolveUiFontForImpl(this)
        return Win32RetainedControlSupport.measureTextSize(
            text = textContent,
            fontHandle = resolvedFont,
            maxWidthPx = if (isBadge) null else maxWidth,
            extraW = if (isBadge) 16 else 4,
            extraH = if (isBadge) 8 else 4,
            minW = if (isBadge) 28 else 8,
            minH = if (isBadge) 20 else 18
        )
    }

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
        syncControlClassIfNeeded()
        syncStaticTextAlignment()
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = true)
        nativeWidget.hwnd?.let { hwnd ->
            if (IsWindow(hwnd) != 0) {
                InvalidateRect(hwnd, null, if (isInsideStageHost()) 0 else 1)
            }
        }
    }

    override fun dispose() {
        Win32RetainedControlSupport.onDispose(nativeWidget)
        super.dispose()
    }

    private companion object {
        private const val STN_CLICKED_CODE: Int = 0
    }
}
