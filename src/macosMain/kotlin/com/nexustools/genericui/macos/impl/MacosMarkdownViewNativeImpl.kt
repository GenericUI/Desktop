@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos.impl

import com.nexustools.genericui.desktop.widget.DesktopMarkdownViewNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.macos.MacosAppKitAppearance
import com.nexustools.genericui.macos.MacosAppKitDispatch
import com.nexustools.genericui.macos.MacosViewHandle
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.useContents
import platform.AppKit.NSColor
import platform.AppKit.NSFont
import platform.AppKit.NSFontAttributeName
import platform.AppKit.NSFontWeightBold
import platform.AppKit.NSFontWeightRegular
import platform.AppKit.NSFontWeightSemibold
import platform.AppKit.NSForegroundColorAttributeName
import platform.AppKit.NSScrollView
import platform.AppKit.NSTextView
import platform.AppKit.NSUnderlineStyleAttributeName
import platform.AppKit.NSUnderlineStyleSingle
import platform.Foundation.NSMakeRange
import platform.Foundation.NSMakeRect
import platform.Foundation.NSMutableAttributedString
import platform.Foundation.NSNumber
import platform.Foundation.create
import platform.Foundation.numberWithInteger
import platform.Foundation.setAttributedString
import kotlin.math.ceil

/**
 * macOS AppKit read-only [NSScrollView] + [NSTextView] implementation of
 * [DesktopMarkdownViewNativeImpl].
 */
class MacosMarkdownViewNativeImpl(
    nativeWidget: MacosViewHandle
) : DesktopMarkdownViewNativeImpl<MacosViewHandle>(nativeWidget) {

    private var scrollView: NSScrollView? = null
    private var textView: NSTextView? = null

    init {
        nativeWidget.viewMaterializer = {
            ensureViews().also { syncMarkdownContent() }
        }
        if (MacosAppKitDispatch.isMainThread()) {
            ensureViews()
            syncMarkdownContent()
        }
    }

    private fun ensureViews(): NSScrollView {
        scrollView?.let { return it }
        val sv = NSScrollView(frame = NSMakeRect(0.0, 0.0, 300.0, 120.0)).apply {
            setHasVerticalScroller(true)
            setHasHorizontalScroller(false)
            setDrawsBackground(false)
            setAutohidesScrollers(true)
        }
        val tv = NSTextView(frame = NSMakeRect(0.0, 0.0, 300.0, 120.0)).apply {
            setEditable(false)
            setSelectable(true)
            setDrawsBackground(false)
            setVerticallyResizable(true)
            setHorizontallyResizable(false)
        }
        sv.setDocumentView(tv)
        scrollView = sv
        textView = tv
        nativeWidget.nsView = sv
        return sv
    }

    private fun syncMarkdownContent() {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { syncMarkdownContent() }
            return
        }
        val sv = ensureViews()
        val tv = textView ?: return
        val darkTheme = MacosAppKitAppearance.isDarkTheme(nativeWidget)
        val fgColor = lastComputedStyle?.textColor
            ?: if (darkTheme) RgbaColor(235, 235, 240, 255) else RgbaColor(24, 24, 27, 255)
        val baseFont = MacosAppKitAppearance.resolveFont(lastComputedStyle, styleSnapshot, 13.0, false)
        val built = renderSimpleMarkdownAttributedString(markdownSource, baseFont, MacosAppKitAppearance.toNsColor(fgColor), darkTheme)
        tv.textStorage?.setAttributedString(built)
        sv.setHidden(!nativeWidget.visible)
        sv.setAlphaValue(nativeWidget.opacity.coerceIn(0f, 1f).toDouble())
        sv.setToolTip(nativeWidget.tooltipText)
    }

    private fun renderSimpleMarkdownAttributedString(
        raw: String,
        baseFont: NSFont,
        textColor: NSColor,
        darkTheme: Boolean
    ): NSMutableAttributedString {
        val sb = StringBuilder(raw.length + 16)
        data class StyleRun(val start: Int, val length: Int, val attrs: Map<Any?, Any?>)
        val runs = mutableListOf<StyleRun>()
        val codeBgFg = if (darkTheme) NSColor.colorWithRed(0.55, 0.82, 1.0, 1.0) else NSColor.colorWithRed(0.15, 0.35, 0.72, 1.0)
        val lines = raw.lines()
        for ((idx, line) in lines.withIndex()) {
            val lineStart = sb.length
            val trimmed = line.trimStart()
            var headingLevel = 0
            var content = line
            when {
                trimmed.startsWith("### ") -> { headingLevel = 3; content = trimmed.removePrefix("### ") }
                trimmed.startsWith("## ") -> { headingLevel = 2; content = trimmed.removePrefix("## ") }
                trimmed.startsWith("# ") -> { headingLevel = 1; content = trimmed.removePrefix("# ") }
                trimmed.startsWith("- ") -> content = "• ${trimmed.removePrefix("- ")}"
                trimmed.startsWith("* ") -> content = "• ${trimmed.removePrefix("* ")}"
            }

            if (headingLevel > 0) {
                sb.append(content)
                val sizePt = when (headingLevel) {
                    1 -> 22.0
                    2 -> 18.0
                    else -> 15.0
                }
                val hFont = NSFont.systemFontOfSize(sizePt, weight = NSFontWeightBold)
                runs.add(
                    StyleRun(
                        lineStart,
                        content.length,
                        mapOf(NSFontAttributeName to hFont, NSForegroundColorAttributeName to textColor)
                    )
                )
            } else {
                var i = 0
                while (i < content.length) {
                    if (content.startsWith("**", i)) {
                        val end = content.indexOf("**", i + 2)
                        if (end > i + 2) {
                            val spanStart = sb.length
                            val span = content.substring(i + 2, end)
                            sb.append(span)
                            runs.add(
                                StyleRun(
                                    spanStart,
                                    span.length,
                                    mapOf(
                                        NSFontAttributeName to NSFont.systemFontOfSize(baseFont.pointSize, weight = NSFontWeightSemibold),
                                        NSForegroundColorAttributeName to textColor
                                    )
                                )
                            )
                            i = end + 2
                            continue
                        }
                    }
                    if (content[i] == '`') {
                        val end = content.indexOf('`', i + 1)
                        if (end > i + 1) {
                            val spanStart = sb.length
                            val span = content.substring(i + 1, end)
                            sb.append(span)
                            runs.add(
                                StyleRun(
                                    spanStart,
                                    span.length,
                                    mapOf(
                                        NSFontAttributeName to NSFont.monospacedSystemFontOfSize(baseFont.pointSize, weight = NSFontWeightRegular),
                                        NSForegroundColorAttributeName to codeBgFg
                                    )
                                )
                            )
                            i = end + 1
                            continue
                        }
                    }
                    if (content[i] == '[') {
                        val closeBracket = content.indexOf(']', i + 1)
                        if (closeBracket > i + 1 && closeBracket + 1 < content.length && content[closeBracket + 1] == '(') {
                            val closeParen = content.indexOf(')', closeBracket + 2)
                            if (closeParen > closeBracket + 2) {
                                val spanStart = sb.length
                                val span = content.substring(i + 1, closeBracket)
                                sb.append(span)
                                runs.add(
                                    StyleRun(
                                        spanStart,
                                        span.length,
                                        mapOf(
                                            NSFontAttributeName to baseFont,
                                            NSForegroundColorAttributeName to NSColor.linkColor,
                                            NSUnderlineStyleAttributeName to NSNumber.numberWithInteger(NSUnderlineStyleSingle.toLong())
                                        )
                                    )
                                )
                                i = closeParen + 1
                                continue
                            }
                        }
                    }
                    sb.append(content[i])
                    i++
                }
            }
            if (idx < lines.lastIndex) {
                sb.append('\n')
            }
        }

        val fullText = sb.toString()
        val result = NSMutableAttributedString.create(
            string = fullText,
            attributes = mapOf(
                NSFontAttributeName to baseFont,
                NSForegroundColorAttributeName to textColor
            )
        )
        for (run in runs) {
            if (run.length > 0 && run.start + run.length <= fullText.length) {
                result.setAttributes(run.attrs, range = NSMakeRange(run.start.toULong(), run.length.toULong()))
            }
        }
        return result
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val tv = textView
        if (tv != null) {
            tv.sizeToFit()
            return tv.bounds.useContents {
                Size(ceil(size.width).toInt().coerceAtLeast(160), ceil(size.height).toInt().coerceAtLeast(48))
            }
        }
        return Size(260, 120)
    }

    override fun setMarkdown(markdown: String) {
        super.setMarkdown(markdown)
        syncMarkdownContent()
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { onNativeBoundsChanged(nativeWidget.bounds) }
            return
        }
        val sv = (nativeWidget.ensureNsViewOnMain() as? NSScrollView) ?: scrollView ?: return
        MacosAppKitDispatch.applyFrameAndTransform(sv, rect, nativeWidget.cssTransform, useAlignmentRectExpansion = false)
        textView?.setFrame(NSMakeRect(0.0, 0.0, rect.width.coerceAtLeast(1).toDouble(), rect.height.coerceAtLeast(1).toDouble()))
    }

    override fun onNativeVisibilityChanged(visible: Boolean) {
        scrollView?.setHidden(!visible)
    }

    override fun onNativeOpacityChanged(opacity: Float) {
        scrollView?.setAlphaValue(opacity.coerceIn(0f, 1f).toDouble())
    }

    override fun onNativeTooltipChanged(tooltip: String?) {
        scrollView?.setToolTip(tooltip)
    }

    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        scrollView?.let { v -> v.window?.invalidateCursorRectsForView(v) }
    }

    override fun onNativeTransformChanged(transform: CssTransform?) {
        nativeWidget.cssTransform = transform
        val sv = scrollView ?: return
        MacosAppKitDispatch.applyFrameAndTransform(sv, nativeWidget.bounds, transform, useAlignmentRectExpansion = false)
    }

    override fun onNativeStyleSynced() {
        syncMarkdownContent()
    }

    override fun dispose() {
        val sv = scrollView
        scrollView = null
        textView = null
        MacosAppKitDispatch.detachViewOnMain(sv)
        super.dispose()
    }
}
