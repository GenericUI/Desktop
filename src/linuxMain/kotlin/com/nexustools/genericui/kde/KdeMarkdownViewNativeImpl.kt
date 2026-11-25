@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.desktop.widget.DesktopMarkdownViewNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.css.CssTransform

/**
 * Retained KDE rich Markdown document view backed by Qt 6 **`QTextBrowser`** (`setMarkdown` + clickable
 * external links).
 */
class KdeMarkdownViewNativeImpl(
    nativeWidget: KdeWidgetHandle = KdeWidgetHandle("QTextBrowser")
) : DesktopMarkdownViewNativeImpl<KdeWidgetHandle>(nativeWidget) {

    init {
        KdeDynamicBindings.ensureQApplication()
        val ptr = KdeDynamicBindings.createQTextBrowser(null)
        nativeWidget.attachToQWidget(ptr, "QTextBrowser", customPainted = false)
        KdePainterSupport.attachStandardInputHooks(ptr, this, KdeVTableBridge.HOOK_RESIZE, dispatchLeftClickOnRelease = false)
        KdeDynamicBindings.setTextBrowserContent(ptr, markdownSource, markdown = true)
    }

    override fun setMarkdown(markdown: String) {
        super.setMarkdown(markdown)
        nativeWidget.qWidgetPtr?.let { ptr ->
            if (!nativeWidget.kdeDestroyed) {
                KdeDynamicBindings.setTextBrowserContent(ptr, markdown, markdown = true)
            }
        }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val w = maxWidth ?: 360f
        val lineCount = markdownSource.lines().size.coerceAtLeast(1)
        val estH = (lineCount * 22f + 16f).coerceIn(48f, maxHeight ?: 600f)
        return Size(w, estH)
    }

    override fun onNativeBoundsChanged(rect: Rect) { nativeWidget.applyBoundsToNative(rect) }
    override fun onNativeVisibilityChanged(visible: Boolean) { nativeWidget.syncVisibilityToNative(visible) }
    override fun onNativeEnabledChanged(enabled: Boolean) { nativeWidget.qWidgetPtr?.let { KdeDynamicBindings.setWidgetEnabled(it, enabled) } }
    override fun onNativeTooltipChanged(tooltip: String?) {
        KdePainterSupport.applyCommonWidgetProperties(nativeWidget, customCursorState, nativeWidget.isSelfPointerEventsNone(), tooltip, getOpacity())
    }
    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        KdePainterSupport.applyCommonWidgetProperties(nativeWidget, cursor, nativeWidget.isSelfPointerEventsNone(), nativeWidget.tooltipText, getOpacity())
    }
    override fun onNativePointerEventsChanged(pointerEventsNone: Boolean) {
        KdePainterSupport.applyCommonWidgetProperties(nativeWidget, customCursorState, pointerEventsNone, nativeWidget.tooltipText, getOpacity())
    }
    override fun onNativeTransformChanged(transform: CssTransform?) { nativeWidget.requestRepaint() }

    override fun dispose() {
        nativeWidget.destroyQWidget()
        super.dispose()
    }
}
