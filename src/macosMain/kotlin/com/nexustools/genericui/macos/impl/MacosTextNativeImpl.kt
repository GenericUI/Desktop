@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos.impl

import com.nexustools.genericui.desktop.widget.DesktopTextNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.css.CssBorderStyle
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.macos.MacosAppKitAppearance
import com.nexustools.genericui.macos.MacosAppKitDispatch
import com.nexustools.genericui.macos.MacosForegroundDrawView
import com.nexustools.genericui.macos.MacosSurfaceLayerSupport
import com.nexustools.genericui.macos.MacosViewHandle
import com.nexustools.genericui.macos.MacosViewInteractionController
import com.nexustools.genericui.macos.handleOverlayTitleBarMouseDown
import com.nexustools.genericui.macos.isWithinOverlayTitleBarNonControlArea
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.style.css.StyledVisualSpec
import com.nexustools.genericui.style.mapper.BootstrapStyleSnapshot
import com.nexustools.genericui.system.style.SemanticSurfaceStyleResolver
import kotlinx.cinterop.CValue
import kotlinx.cinterop.useContents
import platform.AppKit.NSEvent
import platform.AppKit.NSLineBreakByTruncatingTail
import platform.AppKit.NSLineBreakByWordWrapping
import platform.AppKit.NSStringDrawingUsesFontLeading
import platform.AppKit.NSStringDrawingUsesLineFragmentOrigin
import platform.AppKit.NSTextAlignmentCenter
import platform.AppKit.NSTextField
import platform.AppKit.NSView
import platform.AppKit.boundingRectWithSize
import platform.AppKit.drawInRect
import platform.AppKit.size
import platform.CoreGraphics.CGPoint
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSMakeRect
import platform.Foundation.NSRect
import kotlin.math.ceil

internal class MacosBadgeView(
    private val owner: MacosTextNativeImpl,
    frameRect: CValue<NSRect> = NSMakeRect(0.0, 0.0, 44.0, 20.0)
) : NSView(frameRect) {

    val surfaceSupport = MacosSurfaceLayerSupport(this)
    private val interactionController = MacosViewInteractionController(owner, this)
    private var badgeForegroundColor: RgbaColor = RgbaColor.WHITE
    private val foregroundView = MacosForegroundDrawView({ drawForeground() })

    init {
        setWantsLayer(true)
        setPostsFrameChangedNotifications(false)
        addSubview(foregroundView)
    }

    override fun isFlipped(): Boolean = true

    override fun acceptsFirstMouse(event: NSEvent?): Boolean = true

    override fun mouseDownCanMoveWindow(): Boolean = isWithinOverlayTitleBarNonControlArea(this)

    override fun viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        surfaceSupport.refreshBackdropUnderlayIfNeeded(force = true)
    }

    override fun hitTest(point: CValue<CGPoint>): NSView? {
        if (isHidden() || alphaValue <= 0.004 || owner.nativeWidget.isSelfPointerEventsNone()) return null
        val ptInSelf = superview?.let { convertPoint(point, fromView = it) } ?: point
        val inside = ptInSelf.useContents {
            bounds.useContents {
                x >= origin.x && y >= origin.y && x <= origin.x + size.width && y <= origin.y + size.height
            }
        }
        return if (inside && owner.hasClickListener()) this else null
    }

    override fun setFrame(frame: CValue<NSRect>) {
        super.setFrame(frame)
        foregroundView.syncToHost(this)
    }

    override fun layout() {
        super.layout()
        surfaceSupport.refreshBackdropUnderlayIfNeeded(force = false)
        foregroundView.syncToHost(this)
    }

    override fun mouseDown(event: NSEvent) {
        if (handleOverlayTitleBarMouseDown(this, event)) return
        if (owner.hasClickListener()) interactionController.handleMouseDown(event)
    }

    override fun mouseUp(event: NSEvent) {
        if (owner.hasClickListener()) interactionController.handleMouseUp(event)
    }

    override fun cursorUpdate(event: NSEvent) {
        interactionController.handleCursorUpdate(event)
    }

    private fun drawForeground() {
        val text = owner.getText()
        if (text.isEmpty()) return
        val (bw, bh) = bounds.useContents { size.width to size.height }
        if (bw <= 0.0 || bh <= 0.0) return
        val effectiveStyle = owner.resolveEffectiveStyle() ?: owner.lastComputedStyleSnapshot()
        val font = MacosAppKitAppearance.resolveFont(
            effectiveStyle,
            owner.currentStyleSnapshot,
            11.0,
            defaultBold = true
        )
        val attrStr = MacosAppKitAppearance.buildAttributedString(
            text = text,
            style = effectiveStyle,
            textColor = badgeForegroundColor,
            defaultFont = font,
            alignment = NSTextAlignmentCenter,
            applyLineHeight = false
        )
        val textSize = attrStr.size().useContents { width to height }
        val ascender = font.ascender
        val capHeight = font.capHeight
        val textY = if (ascender > 0.0 && capHeight > 0.0) {
            (bh * 0.5) - (ascender - capHeight * 0.5)
        } else {
            (bh - textSize.second) * 0.5
        }
        val drawH = maxOf(textSize.second + 2.0, bh - textY)
        attrStr.drawInRect(NSMakeRect(0.0, textY, bw, drawH))
    }

    fun syncBadge() {
        val darkTheme = MacosAppKitAppearance.isDarkTheme(owner.nativeWidget)
        val tokens = LinkedHashSet<String>().apply {
            addAll(owner.nativeWidget.cachedAllClassTokens)
            add("badge")
        }
        val effectiveStyle = owner.resolveEffectiveStyle() ?: owner.lastComputedStyleSnapshot()
        val cssSpec = StyledVisualSpec.fromComponentAndComputed(
            componentStyle = owner.getComponentStyle(),
            computedStyle = effectiveStyle,
            snapshot = null,
            darkTheme = darkTheme
        )
        val spec = SemanticSurfaceStyleResolver.resolveForTagAndClasses(
            tagName = "span",
            classTokens = tokens,
            snapshot = owner.currentStyleSnapshot,
            darkTheme = darkTheme
        )
        val (bw, bh) = bounds.useContents { size.width to size.height }
        val pill = "rounded-pill" in tokens || "badge-pill" in tokens
        val radiusPx = if (pill && bh > 0.0) {
            (bh * 0.5).toFloat()
        } else {
            cssSpec.borderRadiusPx ?: spec?.borderRadiusPx ?: 5f
        }
        val fallbackType = owner.currentStyleSnapshot.backgroundType
            ?: owner.currentStyleSnapshot.type
            ?: BootstrapType.SECONDARY
        val bg = cssSpec.backgroundColor
            ?: spec?.backgroundColor
            ?: BootstrapColorPalette.resolveColor(fallbackType, darkTheme)
        val fg = cssSpec.textColor
            ?: spec?.textColor
            ?: BootstrapColorPalette.resolveForegroundOn(fallbackType, darkTheme)
        val borderColor = cssSpec.borderColor ?: spec?.borderColor
        val borderWidthPx = cssSpec.borderWidthPx ?: spec?.borderWidthPx ?: 0f
        val borderStyle = cssSpec.borderStyle
            ?: spec?.borderStyle
            ?: if (borderWidthPx > 0f && borderColor != null) CssBorderStyle.SOLID else CssBorderStyle.NONE

        surfaceSupport.applyStyledVisuals(
            spec = StyledVisualSpec(
                backgroundColor = bg,
                borderColor = borderColor,
                borderWidthPx = borderWidthPx,
                borderStyle = borderStyle,
                borderRadiusPx = radiusPx,
                textColor = fg
            ),
            cornerRadii = if (pill) null else effectiveStyle?.borderRadius,
            boxShadow = null,
            isFocused = false,
            clipOverflow = true,
            darkTheme = darkTheme,
            effectiveBackdropFilter = null,
            effectiveForegroundFilter = null
        )

        badgeForegroundColor = fg
        MacosAppKitDispatch.syncChildViewHierarchy(
            parentView = this,
            orderedChildViews = listOf(foregroundView),
            pinnedBackViews = surfaceSupport.pinnedBackdropSubviews()
        )
        foregroundView.syncToHost(this, forceRedraw = true)
    }
}

internal class MacosInteractiveLabelField(
    private val owner: MacosTextNativeImpl,
    frameRect: CValue<NSRect> = NSMakeRect(0.0, 0.0, 120.0, 20.0)
) : NSTextField(frameRect) {

    private val interactionController = MacosViewInteractionController(owner, this)

    init {
        setEditable(false)
        setSelectable(false)
        setBezeled(false)
        setDrawsBackground(false)
        setPostsFrameChangedNotifications(false)
    }

    override fun hitTest(point: CValue<CGPoint>): NSView? {
        if (isHidden() || alphaValue <= 0.004 || owner.nativeWidget.isSelfPointerEventsNone()) return null
        val ptInSelf = superview?.let { convertPoint(point, fromView = it) } ?: point
        val inside = ptInSelf.useContents {
            bounds.useContents {
                x >= origin.x && y >= origin.y && x <= origin.x + size.width && y <= origin.y + size.height
            }
        }
        return if (inside) this else null
    }

    override fun acceptsFirstMouse(event: NSEvent?): Boolean = true

    override fun mouseDownCanMoveWindow(): Boolean = isWithinOverlayTitleBarNonControlArea(this)

    override fun updateTrackingAreas() {
        super.updateTrackingAreas()
        if (owner.hasClickListener() || owner.isLinkTag()) {
            interactionController.refreshTrackingAreas()
        }
    }

    override fun resetCursorRects() {
        val customCursor = interactionController.resolveCssCursor()
        if (customCursor != null) {
            interactionController.applyCursorRects()
        } else if (owner.hasClickListener() || owner.isLinkTag()) {
            addCursorRect(bounds, cursor = platform.AppKit.NSCursor.pointingHandCursor)
        }
    }

    override fun cursorUpdate(event: NSEvent) {
        interactionController.handleCursorUpdate(event)
    }

    override fun mouseEntered(event: NSEvent) {
        if (owner.hasClickListener() || owner.isLinkTag()) {
            interactionController.handleMouseEntered(event)
        } else {
            super.mouseEntered(event)
        }
    }

    override fun mouseExited(event: NSEvent) {
        if (owner.hasClickListener() || owner.isLinkTag()) {
            interactionController.handleMouseExited(event)
        } else {
            super.mouseExited(event)
        }
    }

    override fun mouseMoved(event: NSEvent) {
        if (owner.hasClickListener() || owner.isLinkTag()) {
            interactionController.handleMouseMoved(event)
        } else {
            super.mouseMoved(event)
        }
    }

    override fun mouseDown(event: NSEvent) {
        if (handleOverlayTitleBarMouseDown(this, event)) {
            return
        }
        if (owner.hasClickListener() || owner.isLinkTag()) {
            interactionController.handleMouseDown(event)
        } else {
            super.mouseDown(event)
        }
    }

    override fun mouseUp(event: NSEvent) {
        if (owner.hasClickListener() || owner.isLinkTag()) {
            interactionController.handleMouseUp(event)
        } else {
            super.mouseUp(event)
        }
    }
}

/**
 * macOS AppKit retained [NSTextField] / [MacosBadgeView] implementation of [DesktopTextNativeImpl].
 */
class MacosTextNativeImpl(
    nativeWidget: MacosViewHandle,
    tagName: String = "span",
    classPrefix: String = ""
) : DesktopTextNativeImpl<MacosViewHandle>(nativeWidget, tagName, classPrefix) {

    private var labelView: MacosInteractiveLabelField? = null
    private var badgeView: MacosBadgeView? = null
    private var cachedMeasureText: String? = null
    private var cachedMeasureIsBadge: Boolean = false
    private var cachedMeasureMaxLines: Int? = null
    private var cachedMeasureComputed: ComputedStyle? = null
    private var cachedMeasureSnapshot: BootstrapStyleSnapshot? = null
    private var cachedSingleLineSize: Size? = null
    private var cachedWrappedAvailW: Int = -1
    private var cachedWrappedSize: Size? = null
    private var lastPreferredMaxLayoutWidth: Double = Double.NaN
    private var lastSyncedLabelText: String? = null
    private var lastSyncedLabelSingleLine: Boolean = false
    private var lastSyncedLabelMaxLines: Long = -1L
    private var lastSyncedLabelColor: RgbaColor? = null
    private var lastSyncedLabelDarkTheme: Boolean = false
    private var lastSyncedLabelComputedStyle: ComputedStyle? = null
    private var lastSyncedLabelSnapshot: BootstrapStyleSnapshot? = null
    private var lastSyncedLabelComponentColor: RgbaColor? = null
    private var lastSyncedLabelIsLink: Boolean = false

    private companion object {
        val BOLD_HEADING_TAGS: Set<String> = setOf("h1", "h2", "h3", "h4", "strong", "b")
    }

    init {
        nativeWidget.viewMaterializer = {
            if (labelView == null && badgeView == null) {
                mountActiveView(isInitial = true)
            }
            labelView ?: badgeView
        }
        if (MacosAppKitDispatch.isMainThread()) {
            mountActiveView(isInitial = true)
        }
    }

    internal val currentStyleSnapshot: BootstrapStyleSnapshot
        get() = styleSnapshot

    internal fun lastComputedStyleSnapshot() = lastComputedStyle

    internal fun isLinkTag(): Boolean =
        tagName.equals("a", ignoreCase = true) ||
            (nativeWidget.attributes["tagName"] as? String)?.equals("a", ignoreCase = true) == true

    private fun mountActiveView(isInitial: Boolean) {
        val wantBadge = isBadgeRole()
        val oldView = labelView ?: badgeView
        if (!isInitial && ((wantBadge && badgeView != null) || (!wantBadge && labelView != null))) {
            syncActiveView()
            return
        }

        val nextView: NSView = if (wantBadge) {
            val bv = MacosBadgeView(this)
            badgeView = bv
            labelView = null
            bv
        } else {
            val lf = MacosInteractiveLabelField(this)
            labelView = lf
            badgeView = null
            lastPreferredMaxLayoutWidth = Double.NaN
            lastSyncedLabelText = null
            lastSyncedLabelColor = null
            lastSyncedLabelComputedStyle = null
            lastSyncedLabelSnapshot = null
            lastSyncedLabelComponentColor = null
            lf
        }

        if (isInitial || oldView == null) {
            nativeWidget.nsView = nextView
        } else {
            MacosAppKitDispatch.swapMountedView(nativeWidget, oldView, nextView, this)
        }
        syncActiveView()
    }

    private fun typographyEquals(a: ComputedStyle?, b: ComputedStyle?): Boolean {
        if (a === b) return true
        if (a == null || b == null) return false
        return a.fontSizePx == b.fontSizePx &&
            a.fontWeightNumeric == b.fontWeightNumeric &&
            a.fontFamily == b.fontFamily &&
            a.fontStyle == b.fontStyle &&
            a.lineHeight == b.lineHeight &&
            a.whiteSpace == b.whiteSpace &&
            a.textAlign == b.textAlign &&
            a.textDecoration == b.textDecoration &&
            a.padding == b.padding &&
            a.paddingTop == b.paddingTop &&
            a.paddingRight == b.paddingRight &&
            a.paddingBottom == b.paddingBottom &&
            a.paddingLeft == b.paddingLeft
    }

    private fun invalidateMeasureCache() {
        cachedSingleLineSize = null
        cachedWrappedSize = null
        cachedWrappedAvailW = -1
    }

    private fun syncActiveView() {
        val wantBadge = isBadgeRole()
        if (cachedMeasureText != textContent ||
            cachedMeasureIsBadge != wantBadge ||
            cachedMeasureMaxLines != maxLinesLimit ||
            !typographyEquals(lastComputedStyle, cachedMeasureComputed) ||
            styleSnapshot != cachedMeasureSnapshot
        ) {
            invalidateMeasureCache()
        }
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { syncActiveView() }
            return
        }
        if ((labelView == null && badgeView == null) ||
            (wantBadge && badgeView == null) ||
            (!wantBadge && labelView == null)
        ) {
            mountActiveView(isInitial = (labelView == null && badgeView == null))
            return
        }

        badgeView?.let { bv ->
            bv.setHidden(!nativeWidget.visible)
            bv.setAlphaValue(nativeWidget.opacity.coerceIn(0f, 1f).toDouble())
            bv.setToolTip(nativeWidget.tooltipText)
            bv.syncBadge()
        }

        labelView?.let { lf ->
            val singleLine = isSingleLineMode()
            val desiredPrefMaxW = if (!singleLine && nativeWidget.bounds.width > 0) {
                nativeWidget.bounds.width.toDouble()
            } else {
                0.0
            }
            if (lastPreferredMaxLayoutWidth != desiredPrefMaxW) {
                lf.setPreferredMaxLayoutWidth(desiredPrefMaxW)
                lastPreferredMaxLayoutWidth = desiredPrefMaxW
            }

            val darkTheme = MacosAppKitAppearance.isDarkTheme(nativeWidget)
            val isLink = isLinkTag()
            val effectiveStyle = resolveEffectiveStyle()
            val compColor = lastComponentStyle?.color
            val explicitColor = compColor
                ?: effectiveStyle?.textColor
                ?: lastComputedStyle?.textColor
                ?: styleSnapshot.textColorType?.takeIf { it != BootstrapType.NONE }?.let {
                    BootstrapColorPalette.resolveColor(it, darkTheme)
                }
                ?: resolveBootstrapTextUtilityColor(darkTheme)
            val textColor = explicitColor ?: when {
                isLink -> RgbaColor(10, 132, 255, 255)
                darkTheme -> RgbaColor(235, 235, 240, 255)
                else -> RgbaColor(24, 24, 27, 255)
            }
            val desiredMaxLines = (maxLinesLimit ?: if (singleLine) 1 else 0).toLong()

            val needsAttributedSync = lastSyncedLabelText != textContent ||
                lastSyncedLabelSingleLine != singleLine ||
                lastSyncedLabelMaxLines != desiredMaxLines ||
                lastSyncedLabelColor != textColor ||
                lastSyncedLabelDarkTheme != darkTheme ||
                !typographyEquals(lastSyncedLabelComputedStyle, lastComputedStyle) ||
                lastSyncedLabelSnapshot != styleSnapshot ||
                lastSyncedLabelComponentColor != compColor ||
                lastSyncedLabelIsLink != isLink

            if (needsAttributedSync) {
                val defaultSize = when (tagName.lowercase()) {
                    "h1" -> 28.0
                    "h2" -> 22.0
                    "h3" -> 18.0
                    "h4" -> 16.0
                    "small" -> 11.0
                    else -> 13.0
                }
                val defaultBold = tagName.lowercase() in BOLD_HEADING_TAGS
                val font = MacosAppKitAppearance.resolveFont(lastComputedStyle, styleSnapshot, defaultSize, defaultBold)
                lf.setUsesSingleLineMode(singleLine)
                lf.setLineBreakMode(if (singleLine) NSLineBreakByTruncatingTail else NSLineBreakByWordWrapping)
                lf.setMaximumNumberOfLines(desiredMaxLines)
                lf.setAttributedStringValue(
                    MacosAppKitAppearance.buildAttributedString(
                        text = textContent,
                        style = lastComputedStyle,
                        textColor = textColor,
                        defaultFont = font,
                        alignment = MacosAppKitAppearance.resolveAlignment(lastComputedStyle),
                        underline = isLink
                    )
                )
                lf.setNeedsDisplay(true)
                lastSyncedLabelText = textContent
                lastSyncedLabelSingleLine = singleLine
                lastSyncedLabelMaxLines = desiredMaxLines
                lastSyncedLabelColor = textColor
                lastSyncedLabelDarkTheme = darkTheme
                lastSyncedLabelComputedStyle = lastComputedStyle
                lastSyncedLabelSnapshot = styleSnapshot
                lastSyncedLabelComponentColor = compColor
                lastSyncedLabelIsLink = isLink
            }
            lf.setHidden(!nativeWidget.visible)
            lf.setAlphaValue(nativeWidget.opacity.coerceIn(0f, 1f).toDouble())
            lf.setToolTip(nativeWidget.tooltipText)
        }
    }

    private fun isNoWrap(): Boolean {
        val ws = lastComponentStyle?.whiteSpace?.cssValue ?: resolveEffectiveStyle()?.whiteSpace ?: lastComputedStyle?.whiteSpace
        if (ws.equals("nowrap", ignoreCase = true)) return true
        val tokens = nativeWidget.cachedAllClassTokens
        return "text-nowrap" in tokens || "text-truncate" in tokens
    }

    internal fun isSingleLineMode(): Boolean = (maxLinesLimit == 1) || isNoWrap()

    internal fun isHeightIndependentAtWidth(availWidthPx: Int): Boolean {
        if (isBadgeRole() || isSingleLineMode()) return true
        val single = cachedSingleLineSize ?: return false
        return availWidthPx > 0 &&
            textContent == cachedMeasureText &&
            typographyEquals(lastComputedStyle, cachedMeasureComputed) &&
            styleSnapshot == cachedMeasureSnapshot &&
            single.width <= availWidthPx
    }

    private fun resolveBootstrapTextUtilityColor(darkTheme: Boolean): RgbaColor? {
        val tokens = nativeWidget.cachedAllClassTokens
        return when {
            "text-white" in tokens -> RgbaColor.WHITE
            "text-white-50" in tokens -> RgbaColor(255, 255, 255, 128)
            "text-black-50" in tokens -> RgbaColor(0, 0, 0, 128)
            "text-light" in tokens -> BootstrapColorPalette.resolveColor(BootstrapType.LIGHT, darkTheme)
            "text-dark" in tokens -> BootstrapColorPalette.resolveColor(BootstrapType.DARK, darkTheme)
            "text-info" in tokens -> BootstrapColorPalette.resolveColor(BootstrapType.INFO, darkTheme)
            "text-primary" in tokens -> BootstrapColorPalette.resolveColor(BootstrapType.PRIMARY, darkTheme)
            "text-secondary" in tokens -> BootstrapColorPalette.resolveColor(BootstrapType.SECONDARY, darkTheme)
            "text-success" in tokens -> BootstrapColorPalette.resolveColor(BootstrapType.SUCCESS, darkTheme)
            "text-warning" in tokens -> BootstrapColorPalette.resolveColor(BootstrapType.WARNING, darkTheme)
            "text-danger" in tokens -> BootstrapColorPalette.resolveColor(BootstrapType.DANGER, darkTheme)
            "text-muted" in tokens -> if (darkTheme) RgbaColor(173, 181, 189, 255) else RgbaColor(108, 117, 125, 255)
            else -> null
        }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val isBadge = isBadgeRole()
        val allowWrap = !isBadge && !isNoWrap() && (maxLinesLimit == null || (maxLinesLimit ?: 0) > 1)
        val availW = if (allowWrap) maxWidth?.toDouble()?.takeIf { it > 0.0 } else null
        val availWInt = availW?.toInt()
        val keyMatches = textContent == cachedMeasureText &&
            isBadge == cachedMeasureIsBadge &&
            maxLinesLimit == cachedMeasureMaxLines &&
            typographyEquals(lastComputedStyle, cachedMeasureComputed) &&
            styleSnapshot == cachedMeasureSnapshot
        if (keyMatches) {
            val singleCached = cachedSingleLineSize
            if (singleCached != null && (isBadge || !allowWrap || availWInt == null || singleCached.width <= availWInt)) {
                return singleCached
            }
            val wrappedCached = cachedWrappedSize
            if (wrappedCached != null && availWInt != null && cachedWrappedAvailW == availWInt) {
                return wrappedCached
            }
        } else {
            cachedMeasureText = textContent
            cachedMeasureIsBadge = isBadge
            cachedMeasureMaxLines = maxLinesLimit
            cachedMeasureComputed = lastComputedStyle
            cachedMeasureSnapshot = styleSnapshot
            cachedSingleLineSize = null
            cachedWrappedSize = null
            cachedWrappedAvailW = -1
        }

        if (isBadge) {
            val font = MacosAppKitAppearance.resolveFont(lastComputedStyle, styleSnapshot, 11.0, defaultBold = true)
            val attr = MacosAppKitAppearance.buildAttributedString(
                text = textContent,
                style = lastComputedStyle,
                textColor = RgbaColor.WHITE,
                defaultFont = font,
                applyLineHeight = false
            )
            val (tw, th) = attr.size().useContents { ceil(width).toInt() to ceil(height).toInt() }
            val pad = getEffectivePaddingInsets(
                referenceWidthPx = maxWidth ?: nativeWidget.bounds.width.toFloat(),
                referenceHeightPx = maxHeight ?: nativeWidget.bounds.height.toFloat()
            )
            val padX = maxOf(18, pad.left + pad.right + 6)
            val padY = maxOf(6, pad.top + pad.bottom)
            return Size((tw + padX).coerceAtLeast(24), (th + padY).coerceAtLeast(18)).also {
                cachedSingleLineSize = it
            }
        }
        val defaultSize = when (tagName.lowercase()) {
            "h1" -> 28.0
            "h2" -> 22.0
            "h3" -> 18.0
            "h4" -> 16.0
            "small" -> 11.0
            else -> 13.0
        }
        val defaultBold = tagName.lowercase() in BOLD_HEADING_TAGS
        val font = MacosAppKitAppearance.resolveFont(lastComputedStyle, styleSnapshot, defaultSize, defaultBold)
        val attr = MacosAppKitAppearance.buildAttributedString(
            text = if (textContent.isEmpty()) " " else textContent,
            style = lastComputedStyle,
            textColor = RgbaColor.WHITE,
            defaultFont = font,
            alignment = MacosAppKitAppearance.resolveAlignment(lastComputedStyle),
            underline = isLinkTag()
        )
        val (rawW, rawH) = attr.size().useContents { width to height }
        val singleW = if (textContent.isEmpty()) 0 else (ceil(rawW).toInt() + 6)
        val singleH = ceil(rawH).toInt().coerceAtLeast(16)
        val singleSize = Size(singleW, singleH)
        cachedSingleLineSize = singleSize
        if (allowWrap && availW != null && textContent.isNotEmpty() && singleW > availW) {
            val wrapped = attr.boundingRectWithSize(
                CGSizeMake(availW, 10000.0),
                options = NSStringDrawingUsesLineFragmentOrigin or NSStringDrawingUsesFontLeading,
                context = null
            )
            var (ww, wh) = wrapped.useContents {
                (ceil(size.width).toInt() + 2).coerceAtMost(availW.toInt().coerceAtLeast(1)) to
                    (ceil(size.height).toInt() + 2).coerceAtLeast(singleH)
            }
            val limit = maxLinesLimit
            if (limit != null && limit > 1) {
                wh = wh.coerceAtMost(singleH * limit)
            }
            return Size(ww.coerceAtLeast(0), wh).also {
                cachedWrappedAvailW = availWInt!!
                cachedWrappedSize = it
            }
        }
        return singleSize
    }

    override fun setText(text: String) {
        invalidateMeasureCache()
        super.setText(text)
        syncActiveView()
    }

    override fun setMaxLines(maxLines: Int?) {
        invalidateMeasureCache()
        super.setMaxLines(maxLines)
        syncActiveView()
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer {
                if (!isDisposed) onNativeBoundsChanged(nativeWidget.bounds)
            }
            return
        }
        if (labelView == null && badgeView == null) {
            mountActiveView(isInitial = true)
        }
        val v = (labelView ?: badgeView) ?: return
        labelView?.let { lf ->
            val desiredPrefMaxW = if (!isSingleLineMode() && rect.width > 0) {
                rect.width.toDouble()
            } else {
                0.0
            }
            if (lastPreferredMaxLayoutWidth != desiredPrefMaxW) {
                lf.setPreferredMaxLayoutWidth(desiredPrefMaxW)
                lastPreferredMaxLayoutWidth = desiredPrefMaxW
            }
        }
        MacosAppKitDispatch.applyFrameAndTransform(v, rect, nativeWidget.cssTransform, useAlignmentRectExpansion = false)
        badgeView?.syncBadge()
    }

    override fun onNativeVisibilityChanged(visible: Boolean) {
        (labelView ?: badgeView)?.setHidden(!visible)
    }

    override fun onNativeOpacityChanged(opacity: Float) {
        (labelView ?: badgeView)?.setAlphaValue(opacity.coerceIn(0f, 1f).toDouble())
    }

    override fun onNativeTooltipChanged(tooltip: String?) {
        (labelView ?: badgeView)?.setToolTip(tooltip)
    }

    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        (labelView ?: badgeView)?.let { v -> v.window?.invalidateCursorRectsForView(v) }
    }

    override fun onNativeTransformChanged(transform: CssTransform?) {
        nativeWidget.cssTransform = transform
        val v = (labelView ?: badgeView) ?: return
        MacosAppKitDispatch.applyFrameAndTransform(v, nativeWidget.bounds, transform, useAlignmentRectExpansion = false)
    }

    override fun onNativeStyleSynced() {
        syncActiveView()
    }

    override fun dispose() {
        val lv = labelView
        val bv = badgeView
        labelView = null
        badgeView = null
        MacosAppKitDispatch.onMainOrDefer {
            lv?.removeFromSuperview()
            bv?.removeFromSuperview()
        }
        super.dispose()
    }
}
