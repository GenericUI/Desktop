@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopTextNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.windows.Win32ControlMessageRouter
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.Win32RetainedControlSupport
import platform.windows.InvalidateRect
import platform.windows.NM_CLICK
import platform.windows.NM_RETURN
import platform.windows.SS_CENTER
import platform.windows.SS_LEFT
import platform.windows.SS_NOTIFY
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

    private fun resolveStyle(targetClass: String): Int = when (targetClass) {
        "GenericUIBadgeW" -> WS_CHILD or WS_VISIBLE or WS_CLIPSIBLINGS
        "SysLink" -> WS_CHILD or WS_VISIBLE or WS_TABSTOP or WS_CLIPSIBLINGS
        else -> WS_CHILD or WS_VISIBLE or WS_CLIPSIBLINGS or SS_NOTIFY or SS_LEFT
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
        if (targetClass == "SysLink") {
            nativeWidget.onNotifyCallback = { code, _ ->
                if (code == NM_CLICK.toUInt() || code == NM_RETURN.toUInt()) {
                    dispatchClick()
                    0L
                } else {
                    null
                }
            }
        } else {
            nativeWidget.onNotifyCallback = null
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

    override fun setText(text: String) {
        super.setText(text)
        syncControlClassIfNeeded()
        Win32RetainedControlSupport.setWindowTextIfChanged(
            nativeWidget.hwnd,
            formatTextForClass(nativeWidget.actualWin32ClassName, text)
        )
        nativeWidget.hwnd?.let { InvalidateRect(it, null, 0) }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val isBadge = resolveWin32ClassName() == "GenericUIBadgeW"
        return Win32RetainedControlSupport.measureTextSize(
            text = textContent,
            fontHandle = nativeWidget.fontHandle,
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
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = true)
    }

    override fun dispose() {
        Win32RetainedControlSupport.onDispose(nativeWidget)
        super.dispose()
    }
}
