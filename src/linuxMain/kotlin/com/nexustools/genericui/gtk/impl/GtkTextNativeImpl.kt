@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.impl

import com.nexustools.genericui.desktop.widget.DesktopTextNativeImpl
import com.nexustools.genericui.enums.bootstrap.TextAlignment
import com.nexustools.genericui.enums.bootstrap.TextOverflow
import com.nexustools.genericui.enums.css.CssFontStyle
import com.nexustools.genericui.enums.css.CssTextDecoration
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.gtk.widget.GtkBadgeWidget
import com.nexustools.genericui.gtk.widget.GtkCustomLayoutManager
import com.nexustools.genericui.gtk.widget.GtkSurfaceRenderHelper
import com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge
import com.nexustools.genericui.linux.style.LinuxStyledSurfaceHelper
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.COpaquePointer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Retained GTK4 text/typography node mounting a `GtkLabel` with Pango typography/ellipsize/wrap,
 * or swapping seamlessly to a custom-painted pill/rounded [GtkBadgeWidget] when [isBadgeRole] holds.
 */
class GtkTextNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkLabel"),
    tagName: String = "span",
    classPrefix: String = ""
) : DesktopTextNativeImpl<GtkWidgetHandle>(nativeWidget, tagName, classPrefix) {

    private var activeBadgeMode: Boolean = isBadgeRole()

    init {
        nativeWidget.ownerImpl = this
        nativeWidget.attributes["tagName"] = tagName
        nativeWidget.attributes["componentRole"] = if (classPrefix.isNotBlank()) classPrefix else "text"
        syncTextClassMetadata()
        nativeWidget.widgetMaterializer = { materializeText() }
    }

    private fun syncTextClassMetadata() {
        val badge = isBadgeRole()
        activeBadgeMode = badge
        nativeWidget.updateActiveGtkClassName(if (badge) "GtkBadgeWidget" else "GtkLabel")
        nativeWidget.hasCustomSurfaceBackground = badge
    }

    private fun materializeText(): COpaquePointer? {
        nativeWidget.realGtkWidgetPtr?.let { return it }
        if (!Gtk4DynamicBindings.ensureGtkInitialized()) return null
        val badge = isBadgeRole()
        syncTextClassMetadata()
        val ptr = if (badge) {
            nativeWidget.onDrawSurfaceCairo = { cr, w, h -> GtkBadgeWidget.paintBadge(this, cr, w, h) }
            GtkWidgetLifecycleBridge.createDrawingSurfaceForPainter(nativeWidget) { cr, w, h ->
                GtkBadgeWidget.paintBadge(this, cr, w, h)
            }
        } else {
            nativeWidget.onDrawSurfaceCairo = null
            Gtk4DynamicBindings.gtkLabelNew("")
        } ?: return null
        nativeWidget.primaryLeafPtr = ptr
        GtkWidgetLifecycleBridge.attachControllersAndState(
            impl = this,
            rootPtr = ptr,
            focusTargetPtr = ptr,
            isTextInput = false,
            dispatchClickOnPrimaryRelease = true,
            attachClickGesture = true
        )
        syncContentAndTypographyToLiveWidget(ptr, badge)
        return ptr
    }

    private fun checkRoleSwapAndRefresh() {
        val wantBadge = isBadgeRole()
        val oldPtr = nativeWidget.realGtkWidgetPtr
        if (oldPtr == null) {
            syncTextClassMetadata()
            return
        }
        if (wantBadge != activeBadgeMode) {
            syncTextClassMetadata()
            nativeWidget.realGtkWidgetPtr = null
            val newPtr = materializeText()
            if (newPtr != null) {
                GtkWidgetLifecycleBridge.swapLiveWidgetInParent(this, oldPtr, newPtr)
            }
        } else {
            syncContentAndTypographyToLiveWidget(oldPtr, wantBadge)
        }
    }

    private fun syncContentAndTypographyToLiveWidget(ptr: COpaquePointer, badge: Boolean) {
        if (badge) {
            Gtk4DynamicBindings.gtkWidgetQueueDraw(ptr)
            return
        }
        val computed = getComputedStyle()
        val maxLines = getMaxLines()
        val overflow = getTextOverflow() ?: computed?.textOverflow
        val singleLine = maxLines == 1 || overflow == TextOverflow.ELLIPSIS
        Gtk4DynamicBindings.gtkLabelSetWrap(ptr, !singleLine)
        Gtk4DynamicBindings.gtkLabelSetWrapMode(ptr, Gtk4DynamicBindings.PANGO_WRAP_WORD_CHAR)
        Gtk4DynamicBindings.gtkLabelSetEllipsize(
            ptr,
            if (singleLine && overflow == TextOverflow.ELLIPSIS) Gtk4DynamicBindings.PANGO_ELLIPSIZE_END
            else Gtk4DynamicBindings.PANGO_ELLIPSIZE_NONE
        )

        val alignToken = computed?.textAlign?.trim()?.lowercase()
        val align = getTextAlignment()
        val xAlign = when {
            align == TextAlignment.CENTER || alignToken == "center" -> 0.5f
            align == TextAlignment.END || alignToken == "right" || alignToken == "end" -> 1f
            else -> 0f
        }
        Gtk4DynamicBindings.gtkLabelSetXalign(ptr, xAlign)
        Gtk4DynamicBindings.gtkLabelSetYalign(ptr, 0.5f)

        val rawText = getText()
        val color = LinuxStyledSurfaceHelper.resolveWidgetTextColor(nativeWidget) ?: computed?.textColor
        val fontSizePx = resolvedFontSizePx()
        val isBold = resolvedFontWeight() >= 600
        val italic = computed?.fontStyle == CssFontStyle.ITALIC || (nativeWidget.attributes["italic"] as? Boolean == true)
        val underline = computed?.textDecoration == CssTextDecoration.UNDERLINE || (nativeWidget.attributes["underline"] as? Boolean == true)
        val strike = computed?.textDecoration == CssTextDecoration.LINE_THROUGH || (nativeWidget.attributes["strikethrough"] as? Boolean == true)

        val escaped = escapePangoXml(rawText)
        val attrs = StringBuilder()
        if (fontSizePx != null) {
            val pangoSizeUnits = (fontSizePx * 0.75f * Gtk4DynamicBindings.PANGO_SCALE).roundToInt().coerceAtLeast(1024)
            attrs.append(" size=\"$pangoSizeUnits\"")
        }
        if (isBold) attrs.append(" weight=\"bold\"")
        if (italic) attrs.append(" style=\"italic\"")
        if (underline) attrs.append(" underline=\"single\"")
        if (strike) attrs.append(" strikethrough=\"true\"")
        if (color != null && color.a > 0) {
            val hex = "#${hex2(color.r)}${hex2(color.g)}${hex2(color.b)}"
            attrs.append(" foreground=\"$hex\"")
        }

        if (attrs.isNotEmpty()) {
            Gtk4DynamicBindings.gtkLabelSetMarkup(ptr, "<span$attrs>$escaped</span>")
        } else {
            Gtk4DynamicBindings.gtkLabelSetText(ptr, rawText)
        }
    }

    private fun resolvedFontSizePx(): Float? {
        val attrSize = nativeWidget.attributes["fontSizePx"] as? Float
        if (attrSize != null && attrSize > 0f) return attrSize
        val tag = (nativeWidget.attributes["tagName"] as? String)?.lowercase()
        return when (tag) {
            "h1" -> 32f
            "h2" -> 28f
            "h3" -> 24f
            "h4" -> 20f
            "h5" -> 16f
            "h6" -> 14f
            "small" -> 12f
            else -> null
        }
    }

    private fun resolvedFontWeight(): Int {
        (nativeWidget.attributes["fontWeight"] as? Int)?.let { return it }
        getComputedStyle()?.fontWeightNumeric?.let { return it }
        val strW = (nativeWidget.attributes["fontWeight"] as? String)?.trim()?.uppercase()
        if (strW == "BOLD" || strW == "700") return 700
        if (strW == "SEMIBOLD" || strW == "SEMI_BOLD" || strW == "600") return 600
        val tag = (nativeWidget.attributes["tagName"] as? String)?.lowercase()
        return when (tag) {
            "h1", "h2", "h3", "h4", "h5", "h6", "strong", "b" -> 700
            else -> 400
        }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        if (isBadgeRole()) {
            val (w, h) = GtkBadgeWidget.measureBadgeSize(getText(), resolvedFontSizePx() ?: 11.5f)
            return Size(w.roundToInt(), h.roundToInt())
        }
        val ptr = nativeWidget.realGtkWidgetPtr
        if (ptr != null) {
            val (w, h) = GtkCustomLayoutManager.measureGtkWidgetIntrinsic(ptr, maxWidth, maxHeight)
            if (w > 0 && h > 0) return Size(w, h)
        }
        val fontSize = resolvedFontSizePx() ?: 14f
        val weight = resolvedFontWeight()
        val maxWInt = maxWidth?.roundToInt()?.takeIf { it > 0 }
        val (tw, th) = GtkSurfaceRenderHelper.measureTextSize(
            text = getText(),
            fontSizePx = fontSize,
            fontWeight = weight,
            maxWidthPx = maxWInt
        )
        return Size(max(1, tw), max(ceil(fontSize * 1.3f).roundToInt(), th))
    }

    override fun setText(text: String) {
        super.setText(text)
        checkRoleSwapAndRefresh()
        GtkWidgetLifecycleBridge.requestParentRelayout(this)
    }

    override fun setMaxLines(maxLines: Int?) {
        super.setMaxLines(maxLines)
        checkRoleSwapAndRefresh()
    }

    override fun onNativeBoundsChanged(rect: Rect) = GtkWidgetLifecycleBridge.onBoundsChanged(this, rect)
    override fun onNativeVisibilityChanged(visible: Boolean) = GtkWidgetLifecycleBridge.onVisibilityChanged(this, visible)
    override fun onNativeEnabledChanged(enabled: Boolean) = GtkWidgetLifecycleBridge.onEnabledChanged(this, enabled)
    override fun onNativeOpacityChanged(opacity: Float) = GtkWidgetLifecycleBridge.onOpacityChanged(this, opacity)
    override fun onNativeTooltipChanged(tooltip: String?) = GtkWidgetLifecycleBridge.onTooltipChanged(this, tooltip)
    override fun onNativeCursorChanged(cursor: CssCursorValue?) = GtkWidgetLifecycleBridge.onCursorChanged(this, cursor)
    override fun onNativePointerEventsChanged(pointerEventsNone: Boolean) = GtkWidgetLifecycleBridge.onPointerEventsChanged(this, pointerEventsNone)
    override fun onNativeZIndexChanged(zIndex: Int) = GtkWidgetLifecycleBridge.onZIndexChanged(this, zIndex)
    override fun onNativeTransformChanged(transform: CssTransform?) = GtkWidgetLifecycleBridge.onTransformChanged(this, transform)
    override fun onNativeStyleSynced() {
        checkRoleSwapAndRefresh()
        GtkWidgetLifecycleBridge.onStyleSynced(this)
    }

    private fun escapePangoXml(text: String): String = buildString(text.length + 16) {
        for (ch in text) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                else -> append(ch)
            }
        }
    }

    private fun hex2(v: Int): String = v.coerceIn(0, 255).toString(16).padStart(2, '0')
}
