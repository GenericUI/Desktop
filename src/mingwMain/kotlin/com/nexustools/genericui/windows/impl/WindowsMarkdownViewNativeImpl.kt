@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopMarkdownViewNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.windows.Win32ControlMessageRouter
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.Win32NativeWindowHost
import com.nexustools.genericui.windows.Win32RetainedControlSupport
import platform.windows.ES_AUTOVSCROLL
import platform.windows.ES_MULTILINE
import platform.windows.ES_READONLY
import platform.windows.SendMessageW
import platform.windows.WS_CHILD
import platform.windows.WS_CLIPSIBLINGS
import platform.windows.WS_VISIBLE
import platform.windows.WS_VSCROLL

/**
 * Retained Win32 `"RICHEDIT50W"` (`Msftedit.dll`) read-only viewer for `MarkdownView`.
 */
class WindowsMarkdownViewNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("RICHEDIT50W")
) : DesktopMarkdownViewNativeImpl<Win32HwndHandle>(widget) {

    init {
        nativeWidget.attributes["nativeImpl"] = this
        Win32ControlMessageRouter.createChildHwnd(
            handle = nativeWidget,
            owner = this,
            win32ClassName = "RICHEDIT50W",
            dwStyle = WS_CHILD or WS_VISIBLE or WS_VSCROLL or WS_CLIPSIBLINGS or
                ES_MULTILINE or ES_READONLY or ES_AUTOVSCROLL,
            initialText = formatMarkdownReadableText(markdownSource),
            initialWidth = 320,
            initialHeight = 160,
            installSubclass = true,
            isNativeEditTarget = false
        )
        syncRichEditColors()
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = false)
    }

    private fun syncRichEditColors() {
        val hwnd = nativeWidget.hwnd ?: return
        val isDark = Win32NativeWindowHost.isEffectivelyDarkTheme()
        val bg = getEffectiveBackgroundColor()
        val bgRef = if (bg != null && bg.a > 0) {
            (bg.r or (bg.g shl 8) or (bg.b shl 16)).toLong()
        } else if (isDark) {
            (30 or (30 shl 8) or (34 shl 16)).toLong()
        } else {
            (255 or (255 shl 8) or (255 shl 16)).toLong()
        }
        // EM_SETBKGNDCOLOR = WM_USER + 67 = 0x0443
        SendMessageW(hwnd, 0x0443u, 0uL, bgRef)
    }

    private fun formatMarkdownReadableText(src: String): String {
        if (src.isBlank()) return ""
        return src.lines().joinToString("\r\n") { raw ->
            val trimmed = raw.trimStart()
            when {
                trimmed.startsWith("### ") -> trimmed.removePrefix("### ")
                trimmed.startsWith("## ") -> trimmed.removePrefix("## ")
                trimmed.startsWith("# ") -> trimmed.removePrefix("# ")
                trimmed.startsWith("- ") -> "• ${trimmed.removePrefix("- ")}"
                trimmed.startsWith("* ") -> "• ${trimmed.removePrefix("* ")}"
                else -> raw
            }
        }
    }

    override fun setMarkdown(markdown: String) {
        super.setMarkdown(markdown)
        Win32RetainedControlSupport.setWindowTextIfChanged(
            nativeWidget.hwnd,
            formatMarkdownReadableText(markdown)
        )
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size =
        Win32RetainedControlSupport.measureTextSize(
            text = formatMarkdownReadableText(markdownSource),
            fontHandle = nativeWidget.fontHandle,
            maxWidthPx = maxWidth ?: 480f,
            extraW = 16,
            extraH = 16,
            minW = 120,
            minH = 48
        )

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
        syncRichEditColors()
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = false)
    }

    override fun dispose() {
        Win32RetainedControlSupport.onDispose(nativeWidget)
        super.dispose()
    }
}
