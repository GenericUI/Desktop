@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.desktop.widget.DesktopTextNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.css.CssBorderStyle
import com.nexustools.genericui.enums.css.CssFontStyle
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.style.css.StyledVisualSpec
import com.nexustools.genericui.style.theme.SystemThemeDetector
import kotlinx.cinterop.COpaquePointer
import kotlin.math.roundToInt

/**
 * Retained KDE text/typography node hosting:
 * - KF6 `KUrlLabel` (fallback `QLabel`) when `tagName == "a"`,
 * - custom pill/rounded `KdeBadgeWidget` when [isBadgeRole] holds,
 * - genuine Qt6 `QLabel` otherwise.
 */
class KdeTextNativeImpl(
    nativeWidget: KdeWidgetHandle = KdeWidgetHandle("QLabel"),
    tagName: String = "span",
    classPrefix: String = ""
) : DesktopTextNativeImpl<KdeWidgetHandle>(nativeWidget, tagName, classPrefix) {

    private val isAnchorTag: Boolean = tagName.equals("a", ignoreCase = true)
    private var activeBadgeMode: Boolean = false
    private var usesKUrlLabel: Boolean = false

    init {
        KdeDynamicBindings.ensureQApplication()
        nativeWidget.attributes["tagName"] = tagName
        nativeWidget.attributes["componentRole"] = if (classPrefix.isNotBlank()) classPrefix else "text"
        rebuildOrSyncWidget(forceCreate = true)
    }

    private fun rebuildOrSyncWidget(forceCreate: Boolean = false) {
        if (isDisposed || nativeWidget.kdeDestroyed) return
        val existingPtr = nativeWidget.qWidgetPtr
        if (!forceCreate && existingPtr != null && !KdeVTableBridge.isPeerAlive(existingPtr)) {
            nativeWidget.qWidgetPtr = null
            nativeWidget.kdeDestroyed = true
            return
        }
        val wantBadge = isBadgeRole()
        if (!forceCreate && wantBadge == activeBadgeMode && existingPtr != null) {
            syncTextToLiveWidget()
            return
        }
        activeBadgeMode = wantBadge
        val parentPtr = nativeWidget.desiredParentPtr

        if (wantBadge) {
            val ptr = KdeDynamicBindings.createQWidget(parentPtr, 0)
            KdePainterSupport.attachStandardInputHooks(
                widgetPtr = ptr,
                impl = this,
                hookMask = KdeVTableBridge.HOOK_PAINT or KdeVTableBridge.HOOK_RESIZE or KdeVTableBridge.HOOK_SIZE_HINT,
                dispatchLeftClickOnRelease = true,
                onPaint = { wPtr, painter, w, h -> paintBadge(wPtr, painter, w, h) },
                onSizeHint = { _ ->
                    val s = measureIntrinsicSize(null, null)
                    s.width.coerceAtLeast(24) to s.height.coerceAtLeast(20)
                }
            )
            usesKUrlLabel = false
            nativeWidget.swapQWidget(ptr, "KdeBadgeWidget", customPainted = true)
        } else {
            val kUrlPtr = if (isAnchorTag) KdeDynamicBindings.createKUrlLabel(parentPtr) else null
            usesKUrlLabel = kUrlPtr != null
            val ptr = kUrlPtr ?: KdeDynamicBindings.createQLabel(parentPtr)
            val roleName = if (usesKUrlLabel) "KUrlLabel" else "QLabel"
            KdePainterSupport.attachStandardInputHooks(
                widgetPtr = ptr,
                impl = this,
                hookMask = KdeVTableBridge.HOOK_RESIZE,
                dispatchLeftClickOnRelease = true
            )
            nativeWidget.swapQWidget(ptr, roleName, customPainted = false)
        }
        syncTextToLiveWidget()
    }

    private fun paintBadge(widgetPtr: COpaquePointer, painter: COpaquePointer, w: Int, h: Int) {
        val dark = SystemThemeDetector.resolveTheme(ThemeMode.AUTO) == ThemeMode.DARK
        val computed = getComputedStyle()
        val bType = getBackgroundType()?.takeIf { it != BootstrapType.NONE }
            ?: getType()?.takeIf { it != BootstrapType.NONE }
            ?: BootstrapType.SECONDARY
        val bg = getEffectiveBackgroundColor() ?: BootstrapColorPalette.resolveColor(bType, dark)
        val fg = getEffectiveTextColor() ?: if (bType == BootstrapType.LIGHT || bType == BootstrapType.WARNING) {
            RgbaColor(33, 37, 41)
        } else {
            RgbaColor.WHITE
        }
        val pill = "rounded-pill" in nativeWidget.cachedAllClassTokens
        val radius = if (pill) (h / 2f) else (computed?.borderRadiusPx ?: 6f)
        val spec = StyledVisualSpec(
            backgroundColor = bg,
            textColor = fg,
            borderColor = computed?.borderColor,
            borderWidthPx = if (computed?.borderColor != null) 1f else 0f,
            borderStyle = CssBorderStyle.SOLID,
            borderRadiusPx = radius
        )
        KdePainterSupport.paintStyledSurface(
            widgetPtr = widgetPtr,
            painterPtr = painter,
            widthPx = w,
            heightPx = h,
            spec = spec,
            radii = computed?.borderRadius,
            opacity = getOpacity()
        )
        if (textContent.isNotEmpty()) {
            val fontSize = computed?.fontSizePx ?: 12f
            KdeDynamicBindings.painterDrawText(
                p = painter,
                text = textContent,
                x = 0.0,
                y = 0.0,
                w = w.toDouble(),
                h = h.toDouble(),
                color = fg,
                fontSizePx = fontSize,
                fontWeight = computed?.fontWeightNumeric ?: 700,
                italic = computed?.fontStyle == CssFontStyle.ITALIC,
                fontFamily = computed?.fontFamily,
                alignmentFlags = KdeDynamicBindings.ALIGN_CENTER
            )
        }
    }

    private fun syncTextToLiveWidget() {
        if (isDisposed || nativeWidget.kdeDestroyed) return
        val ptr = nativeWidget.qWidgetPtr?.takeIf { KdeVTableBridge.isPeerAlive(it) } ?: return
        if (activeBadgeMode) {
            KdeDynamicBindings.updateWidget(ptr)
            return
        }
        val computed = lastComputedStyle
        val align = when (computed?.textAlign?.trim()?.lowercase()) {
            "center" -> KdeDynamicBindings.ALIGN_HCENTER or KdeDynamicBindings.ALIGN_VCENTER
            "right", "end" -> KdeDynamicBindings.ALIGN_RIGHT or KdeDynamicBindings.ALIGN_VCENTER
            else -> KdeDynamicBindings.ALIGN_LEFT or KdeDynamicBindings.ALIGN_VCENTER
        }
        val wrap = (maxLinesLimit == null || (maxLinesLimit ?: 0) > 1)
        KdeDynamicBindings.setLabelState(ptr, textContent, wrap = wrap, alignFlags = align)
        KdeDynamicBindings.setWidgetFontStyle(
            ptr,
            computed?.fontFamily,
            computed?.fontSizePx,
            bold = (computed?.fontWeightNumeric ?: 400) >= 600,
            italic = computed?.fontStyle == CssFontStyle.ITALIC
        )
        if (usesKUrlLabel) {
            val href = (nativeWidget.attributes["href"] as? String).orEmpty()
            if (href.isNotEmpty()) KdeDynamicBindings.setKUrlLabelUrl(ptr, href)
        }
    }

    override fun setText(text: String) {
        super.setText(text)
        syncTextToLiveWidget()
    }

    override fun setMaxLines(maxLines: Int?) {
        super.setMaxLines(maxLines)
        syncTextToLiveWidget()
    }

    override fun setAttribute(name: String, value: String?) {
        super.setAttribute(name, value)
        if (name == "href" && usesKUrlLabel) syncTextToLiveWidget()
        if (name == "class" || name == "role") rebuildOrSyncWidget(forceCreate = false)
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val fontSize = lastComputedStyle?.fontSizePx ?: if (activeBadgeMode) 12f else 14f
        val weight = lastComputedStyle?.fontWeightNumeric ?: if (activeBadgeMode) 700 else 400
        val italic = lastComputedStyle?.fontStyle == CssFontStyle.ITALIC
        val measured = KdeDynamicBindings.measureTextSize(
            textContent.ifEmpty { " " },
            fontSize,
            fontWeight = weight,
            italic = italic,
            fontFamily = lastComputedStyle?.fontFamily
        )
        val padW = if (activeBadgeMode) 16.0 else 4.0
        val padH = if (activeBadgeMode) 8.0 else 4.0
        val w = (measured.first + padW).toFloat().let { if (maxWidth != null) minOf(it, maxWidth) else it }
        val h = (measured.second + padH).toFloat()
        return Size(w.coerceAtLeast(if (activeBadgeMode) 24f else 1f), h.coerceAtLeast(if (activeBadgeMode) 18f else 14f))
    }

    override fun onNativeBoundsChanged(rect: Rect) { nativeWidget.applyBoundsToNative(rect) }
    override fun onNativeVisibilityChanged(visible: Boolean) { nativeWidget.syncVisibilityToNative(visible) }
    override fun onNativeEnabledChanged(enabled: Boolean) { nativeWidget.qWidgetPtr?.let { KdeDynamicBindings.setWidgetEnabled(it, enabled) } }
    override fun onNativeOpacityChanged(opacity: Float) { nativeWidget.requestRepaint() }
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
    override fun onNativeStyleSynced() { rebuildOrSyncWidget(forceCreate = false) }
    override fun requestRepaintNative() { nativeWidget.requestRepaint() }

    override fun dispose() {
        nativeWidget.destroyQWidget()
        super.dispose()
    }
}
