package com.nexustools.genericui.macos.render

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopInteractiveSurfaceSpec
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.core.DesktopStageOverlaySpec
import com.nexustools.genericui.desktop.widget.DesktopButtonNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopImageNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopRangeSliderNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopSpinnerNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopStyledButtonNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopStyledContainerNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTextNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.bootstrap.TextAlignment
import com.nexustools.genericui.enums.css.CssBorderStyle
import com.nexustools.genericui.event.PointerEvent
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.color.RgbaColor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Type-grouped macOS renderer for interactive media/3D stages ([DesktopInteractiveSurfaceSpec])
 * and HUD stage overlays ([DesktopStageOverlaySpec]) with zero extension-module coupling.
 */
object MacosStageOverlayRenderUtil {

    private const val HUD_BAR_ITEM_HEIGHT: Int = 40
    private val DEFAULT_HUD_PILL_BG = RgbaColor(18, 18, 22, 188)
    private val DEFAULT_HUD_PILL_BORDER = RgbaColor(255, 255, 255, 36)

    fun findInteractiveSurfaceInSubtree(node: DesktopNativeWidget, maxDepth: Int = 4): DesktopNativeWidget? {
        if (!node.visible || node.disposed) return null
        if (node.interactiveSurfaceSpec != null) return node
        if (maxDepth <= 0) return null
        for (child in node.children) {
            findInteractiveSurfaceInSubtree(child, maxDepth - 1)?.let { return it }
        }
        return null
    }

    fun findStageOverlayInSubtree(node: DesktopNativeWidget, maxDepth: Int = 4): DesktopNativeWidget? {
        if (!node.visible || node.disposed) return null
        if (node.stageOverlaySpec != null ||
            node.attributes["data-overlay-root"] == "true" ||
            "gui-overlay" in node.cachedAllClassTokens
        ) {
            return node
        }
        if (maxDepth <= 0) return null
        for (child in node.children) {
            findStageOverlayInSubtree(child, maxDepth - 1)?.let { return it }
        }
        return null
    }

    private fun isStageOverlayOrAbsoluteSibling(child: DesktopNativeWidget): Boolean {
        if (child.stageOverlaySpec != null || child.attributes["data-overlay-root"] == "true") return true
        val tokens = child.cachedAllClassTokens
        if ("gui-overlay" in tokens || "position-absolute" in tokens) return true
        if (child.attributes["position"] == "absolute") return true
        return false
    }

    fun isStageContainer(node: DesktopNativeWidget): Boolean {
        if (!node.visible || node.disposed) return false
        if (node.interactiveSurfaceSpec != null) return true
        val tokens = node.cachedAllClassTokens
        if ("gui-overlay-host" in tokens ||
            "genericui-video-overlay-host" in tokens ||
            node.attributes["data-overlay-host"] == "true"
        ) {
            return true
        }
        // Match direct stage wrappers (e.g. AspectRatioContainer / LayeredContainer wrapping [Surface, Overlay]),
        // never outer vertical flow columns or cards that have sibling rows/spacers below the stage.
        var hasDirectSurface = false
        for (child in node.children) {
            if (!child.visible || child.disposed || child.attributes["visibilityHidden"] == true) continue
            if (child.interactiveSurfaceSpec != null) {
                hasDirectSurface = true
            } else if (!isStageOverlayOrAbsoluteSibling(child)) {
                return false
            }
        }
        return hasDirectSurface
    }

    fun renderStageContainer(
        ctx: MacosRenderContext,
        container: DesktopNativeWidget,
        bounds: Rect,
        renderGenericSubtree: (MacosRenderContext, DesktopNativeWidget, Rect) -> Unit
    ) {
        if (bounds.width <= 0 || bounds.height <= 0) return
        container.isIsolatedStageSubtreeRoot = true
        container.bounds = bounds
        val startHitIdx = ctx.hitRegions.size
        val startScrollIdx = ctx.scrollRegions.size
        ctx.renderedStageWidget = container
        ctx.renderedStageBounds = bounds

        val stagePopovers = ArrayList<DesktopNativeWidget>(4)
        val radius = (container.attributes["borderRadiusPx"] as? Number)?.toFloat()
            ?: (container.attributes["borderRadiusPx"] as? String)?.toFloatOrNull()
            ?: 8f
        MacosGraphicsDrawUtil.withClip(ctx, bounds.x, bounds.y, bounds.width, bounds.height) {
            MacosGraphicsDrawUtil.fillRoundedRect(ctx, bounds.x, bounds.y, bounds.width, bounds.height, radius, RgbaColor.BLACK)

            val surfaceWidget = if (container.interactiveSurfaceSpec != null) {
                container
            } else {
                findInteractiveSurfaceInSubtree(container, 4)
            }
            val overlayWidget = findStageOverlayInSubtree(container, 4)

            if (surfaceWidget != null) {
                surfaceWidget.bounds = bounds
                val spec = surfaceWidget.interactiveSurfaceSpec
                if (spec != null) {
                    MacosGraphicsDrawUtil.drawInteractiveSurface(ctx, spec, bounds.x, bounds.y, bounds.width, bounds.height)
                }

                // Register base stage pointer region (click to play/pause, double-click fullscreen)
                val centerZoneWidget = if (overlayWidget != null && overlayWidget.visible && !overlayWidget.disposed) {
                    findOverlayRole(overlayWidget, DesktopStageOverlaySpec.ROLE_CENTER_ZONE)
                } else {
                    null
                }
                val overlayRootImpl = overlayWidget?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>
                val centerZoneImpl = (centerZoneWidget?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>)
                    ?.takeIf { it.hasClickListener() }
                val targetClickImpl = centerZoneImpl
                    ?: overlayRootImpl
                    ?: (surfaceWidget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>)
                    ?: (container.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>)
                if (targetClickImpl != null) {
                    ctx.registerHitRegion(
                        MacosHitRegion(
                            left = bounds.x,
                            top = bounds.y,
                            right = bounds.right,
                            bottom = bounds.bottom,
                            widget = overlayWidget ?: surfaceWidget,
                            onPress = { lx, ly, wx, wy, btn, cc ->
                                targetClickImpl.dispatchPointerDown(
                                    PointerEvent(x = lx, y = ly, screenX = wx, screenY = wy, button = btn, clickCount = cc)
                                )
                                false
                            },
                            onClick = {
                                if (overlayRootImpl != null && overlayRootImpl !== targetClickImpl) {
                                    overlayRootImpl.dispatchClick()
                                }
                                targetClickImpl.dispatchClick()
                            },
                            onDoubleClick = { targetClickImpl.dispatchDoubleClick() }
                        )
                    )
                }
            }

            // Render caption text if active on surface or overlay
            val captionText = overlayWidget?.stageOverlaySpec?.activeCaptionText?.invoke(overlayWidget)
                ?: surfaceWidget?.interactiveSurfaceSpec?.activeCaptionText?.invoke()
            if (!captionText.isNullOrBlank()) {
                renderCaptionBannerText(ctx, captionText, bounds)
            }

            if (overlayWidget != null && overlayWidget.visible && !overlayWidget.disposed) {
                renderOverlayHud(ctx, overlayWidget, bounds, stagePopovers)
            }

            // Render any non-surface, non-overlay sibling overlays (e.g. absolute status spinner / placeholder)
            for (child in container.children) {
                if (!child.visible || child.disposed || child.attributes["visibilityHidden"] == true) continue
                if (child === surfaceWidget || child === overlayWidget) continue
                if (findInteractiveSurfaceInSubtree(child, 3) != null) continue
                if (findStageOverlayInSubtree(child, 3) != null) continue
                renderGenericSubtree(ctx, child, bounds)
            }

            // Draw open stage popovers above HUD bars and sibling overlays within stage bounds
            for (popover in stagePopovers) {
                renderOverlayPopoverPanel(ctx, popover, bounds, renderGenericSubtree)
            }
        }

        ctx.stageHitRegionStartIdx = startHitIdx
        ctx.stageHitRegionEndIdx = ctx.hitRegions.size
        ctx.stageScrollRegionStartIdx = startScrollIdx
        ctx.stageScrollRegionEndIdx = ctx.scrollRegions.size
    }

    private fun renderCaptionBannerText(ctx: MacosRenderContext, text: String, stageBounds: Rect) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val fontSize = (stageBounds.height * 0.036f).coerceIn(13f, 24f)
        val maxW = (stageBounds.width * 0.82f).roundToInt().coerceAtLeast(120)
        val measured = MacosGraphicsDrawUtil.measureText(clean, fontSize, 600, maxWidth = maxW, singleLine = false)
        val padX = 14
        val padY = 8
        val boxW = (measured.width + padX * 2).coerceAtMost(maxW)
        val boxH = (measured.height + padY * 2).coerceAtMost(stageBounds.height / 3)
        val bx = stageBounds.x + (stageBounds.width - boxW) / 2
        val by = (stageBounds.bottom - 82 - boxH).coerceAtLeast(stageBounds.y + 16)

        MacosGraphicsDrawUtil.fillRoundedRect(ctx, bx, by, boxW, boxH, 8f, RgbaColor(0, 0, 0, 196))
        MacosGraphicsDrawUtil.drawText(
            ctx = ctx,
            text = clean,
            x = bx + padX,
            y = by + padY,
            w = (boxW - padX * 2).coerceAtLeast(1),
            h = (boxH - padY * 2).coerceAtLeast(1),
            color = RgbaColor.WHITE,
            fontSizePx = fontSize,
            weightNumeric = 600,
            alignment = TextAlignment.CENTER,
            singleLine = false
        )
    }

    private fun renderOverlayHud(
        ctx: MacosRenderContext,
        overlayRoot: DesktopNativeWidget,
        stageBounds: Rect,
        stagePopoversOut: MutableList<DesktopNativeWidget>
    ) {
        overlayRoot.bounds = stageBounds
        val spec = overlayRoot.stageOverlaySpec
        val specVisible = spec?.isOverlayVisible?.invoke(overlayRoot) ?: (overlayRoot.attributes["hudVisible"] as? Boolean ?: true)
        val hudAlpha = if (!specVisible) 0f else (ctx.overlayHudAlpha * overlayRoot.opacity).coerceIn(0f, 1f)

        val topBar = findOverlayRole(overlayRoot, DesktopStageOverlaySpec.ROLE_TOP_BAR)
        val centerZone = findOverlayRole(overlayRoot, DesktopStageOverlaySpec.ROLE_CENTER_ZONE)
        val bottomBar = findOverlayRole(overlayRoot, DesktopStageOverlaySpec.ROLE_BOTTOM_BAR)
        val captionNode = findOverlayRole(overlayRoot, DesktopStageOverlaySpec.ROLE_CAPTION_BANNER)

        // Collect active popovers anywhere inside overlayRoot
        collectVisiblePopovers(overlayRoot, stagePopoversOut)

        if (captionNode != null && captionNode.visible && captionNode.attributes["visibilityHidden"] != true) {
            val capText = extractFirstNonEmptyText(captionNode)
            if (!capText.isNullOrBlank()) {
                renderCaptionBannerText(ctx, capText, stageBounds)
            }
        }

        if (hudAlpha > 0.02f) {
            val savedOpacity = ctx.currentOpacity
            ctx.currentOpacity = savedOpacity * hudAlpha
            try {
                if (topBar != null && topBar.visible && topBar.attributes["visibilityHidden"] != true) {
                    val stackedLineCount = countVisibleTopHudStackedLines(topBar)
                    val barH = when {
                        stackedLineCount >= 3 -> 64
                        stackedLineCount == 2 -> 46
                        else -> HUD_BAR_ITEM_HEIGHT
                    }
                    MacosGraphicsDrawUtil.fillVerticalGradient(
                        ctx,
                        stageBounds.x,
                        stageBounds.y,
                        stageBounds.width,
                        barH + 34,
                        RgbaColor(0, 0, 0, 188),
                        RgbaColor(0, 0, 0, 0)
                    )
                    renderOverlayTopBar(
                        ctx,
                        topBar,
                        Rect(stageBounds.x + 14, stageBounds.y + 10, (stageBounds.width - 28).coerceAtLeast(40), barH)
                    )
                }

                if (centerZone != null && centerZone.visible && centerZone.attributes["visibilityHidden"] != true) {
                    renderOverlayCenterZone(ctx, centerZone, stageBounds)
                }

                if (bottomBar != null && bottomBar.visible && bottomBar.attributes["visibilityHidden"] != true) {
                    val botH = 78
                    MacosGraphicsDrawUtil.fillVerticalGradient(
                        ctx,
                        stageBounds.x,
                        stageBounds.bottom - botH - 28,
                        stageBounds.width,
                        botH + 28,
                        RgbaColor(0, 0, 0, 0),
                        RgbaColor(0, 0, 0, 215)
                    )
                    renderOverlayBottomBar(
                        ctx,
                        bottomBar,
                        Rect(stageBounds.x + 14, stageBounds.bottom - botH, (stageBounds.width - 28).coerceAtLeast(40), botH - 10)
                    )
                }
            } finally {
                ctx.currentOpacity = savedOpacity
            }
        }
    }

    private fun countVisibleTopHudStackedLines(topBar: DesktopNativeWidget): Int {
        val leaves = ArrayList<DesktopNativeWidget>(8)
        collectHudLeafControls(topBar, leaves)
        var count = 0
        for (leaf in leaves) {
            val impl = leaf.attributes["nativeImpl"]
            if (impl is DesktopTextNativeImpl<*>) {
                val isBadge = "badge" in leaf.cachedAllClassTokens ||
                    leaf.attributes[DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE] == DesktopStageOverlaySpec.ROLE_OUTLINED_FEATURE_BADGE
                if (!isBadge && impl.getText().isNotBlank()) {
                    count += 1
                }
            }
        }
        return count
    }

    private fun renderOverlayTopBar(ctx: MacosRenderContext, topBar: DesktopNativeWidget, bounds: Rect) {
        topBar.bounds = bounds
        val leaves = ArrayList<DesktopNativeWidget>(8)
        collectHudLeafControls(topBar, leaves)
        if (leaves.isEmpty()) return

        var leftX = bounds.x
        var rightX = bounds.right
        val buttons = leaves.filter {
            val impl = it.attributes["nativeImpl"]
            impl is DesktopButtonNativeImpl<*> || impl is DesktopStyledButtonNativeImpl<*>
        }
        val labels = leaves.filter { it !in buttons }

        for (btn in buttons.asReversed()) {
            val bw = measureHudControlWidth(btn)
            val bh = HUD_BAR_ITEM_HEIGHT.coerceAtMost(bounds.height)
            val bx = rightX - bw
            val rect = Rect(bx, bounds.y, bw, bh)
            btn.bounds = rect
            renderHudControl(ctx, btn, rect)
            rightX = bx - 8
        }

        // First lay out any inline badge pills on the left if present
        val stackedTextLabels = ArrayList<Pair<DesktopNativeWidget, DesktopTextNativeImpl<*>>>(4)
        for (lbl in labels) {
            val impl = lbl.attributes["nativeImpl"]
            if (impl is DesktopTextNativeImpl<*>) {
                val isBadge = "badge" in lbl.cachedAllClassTokens ||
                    lbl.attributes[DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE] == DesktopStageOverlaySpec.ROLE_OUTLINED_FEATURE_BADGE
                if (isBadge) {
                    val txt = impl.getText()
                    if (txt.isNotBlank() && leftX < rightX - 20) {
                        val m = MacosGraphicsDrawUtil.measureText(txt, 11.5f, 600)
                        val bw = m.width + 16
                        val rect = Rect(leftX, bounds.y + (HUD_BAR_ITEM_HEIGHT.coerceAtMost(bounds.height) - 22) / 2, bw, 22)
                        lbl.bounds = rect
                        MacosGraphicsDrawUtil.fillRoundedRect(ctx, rect.x, rect.y, rect.width, rect.height, 11f, RgbaColor(255, 255, 255, 36))
                        MacosGraphicsDrawUtil.strokeRoundedRect(ctx, rect.x, rect.y, rect.width, rect.height, 11f, RgbaColor(255, 255, 255, 90), 1f)
                        MacosGraphicsDrawUtil.drawText(ctx, txt, rect.x + 6, rect.y, rect.width - 12, rect.height, RgbaColor.WHITE, 11.5f, 600, TextAlignment.CENTER)
                        leftX += bw + 8
                    }
                } else {
                    stackedTextLabels.add(lbl to impl)
                }
            }
        }

        // Stack top-left title, subtitle, and loading message labels vertically
        val availWidth = (rightX - leftX - 8).coerceAtLeast(24)
        var textY = bounds.y
        var visibleLineIndex = 0
        for ((lbl, impl) in stackedTextLabels) {
            val txt = impl.getText()
            if (txt.isBlank()) {
                lbl.bounds = Rect(leftX, textY, 0, 0)
                continue
            }
            val isPrimary = visibleLineIndex == 0
            val isInfoLine = "text-info" in lbl.cachedAllClassTokens
            val fontSize = when {
                isPrimary -> 15f
                visibleLineIndex == 1 -> 12.5f
                else -> 12f
            }
            val fontWeight = if (isPrimary) 600 else 500
            val lineHeight = when {
                isPrimary -> 21
                visibleLineIndex == 1 -> 18
                else -> 17
            }
            val textColor = when {
                isPrimary -> RgbaColor.WHITE
                isInfoLine -> RgbaColor(125, 211, 252, 238)
                else -> RgbaColor(226, 232, 240, 220)
            }
            val m = MacosGraphicsDrawUtil.measureText(txt, fontSize, fontWeight)
            val tw = min(availWidth, m.width + 8)
            val rect = Rect(leftX, textY, tw, lineHeight)
            lbl.bounds = rect
            MacosGraphicsDrawUtil.drawText(
                ctx = ctx,
                text = txt,
                x = rect.x,
                y = rect.y,
                w = rect.width,
                h = rect.height,
                color = textColor,
                fontSizePx = fontSize,
                weightNumeric = fontWeight
            )
            textY += lineHeight + 2
            visibleLineIndex += 1
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun renderOverlayCenterZone(ctx: MacosRenderContext, centerZone: DesktopNativeWidget, stageBounds: Rect) {
        val spinners = ArrayList<DesktopNativeWidget>(2)
        val buttons = ArrayList<DesktopNativeWidget>(4)
        collectCenterZoneItems(centerZone, spinners, buttons)

        for (sp in spinners) {
            val spImpl = sp.attributes["nativeImpl"] as? DesktopSpinnerNativeImpl<DesktopNativeWidget> ?: continue
            val spRect = Rect(
                stageBounds.x + (stageBounds.width - 36) / 2,
                stageBounds.y + (stageBounds.height - 36) / 2,
                36,
                36
            )
            sp.bounds = spRect
            MacosControlRenderUtil.renderSpinner(spImpl, ctx, spRect)
        }

        if (buttons.size >= 2) {
            val gap = 16
            val sizes = IntArray(buttons.size) { idx ->
                val isPrimary = buttons[idx].attributes[DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE] == DesktopStageOverlaySpec.ROLE_PRIMARY_CENTER_ACTION
                if (isPrimary) 64 else 54
            }
            val totalW = sizes.sum() + gap * (buttons.size - 1)
            var curX = stageBounds.x + (stageBounds.width - totalW) / 2
            for ((idx, btn) in buttons.withIndex()) {
                val sz = sizes[idx]
                val rect = Rect(curX, stageBounds.y + (stageBounds.height - sz) / 2, sz, sz)
                btn.bounds = rect
                renderHudControl(ctx, btn, rect)
                curX += sz + gap
            }
            return
        }

        val actionNode = findOverlayRole(centerZone, DesktopStageOverlaySpec.ROLE_PRIMARY_CENTER_ACTION)
            ?: buttons.firstOrNull()
            ?: return
        if (!actionNode.visible || actionNode.attributes["visibilityHidden"] == true) return
        val size = 62
        val rect = Rect(
            stageBounds.x + (stageBounds.width - size) / 2,
            stageBounds.y + (stageBounds.height - size) / 2,
            size,
            size
        )
        actionNode.bounds = rect
        renderHudControl(ctx, actionNode, rect)
    }

    private fun collectCenterZoneItems(
        node: DesktopNativeWidget,
        spinners: MutableList<DesktopNativeWidget>,
        buttons: MutableList<DesktopNativeWidget>
    ) {
        if (!node.visible || node.disposed || node.attributes["visibilityHidden"] == true) return
        val impl = node.attributes["nativeImpl"]
        if (impl is DesktopSpinnerNativeImpl<*>) {
            spinners.add(node)
            return
        }
        if (impl is DesktopButtonNativeImpl<*> || impl is DesktopStyledButtonNativeImpl<*>) {
            buttons.add(node)
            return
        }
        for (child in node.children) {
            collectCenterZoneItems(child, spinners, buttons)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun renderOverlayBottomBar(ctx: MacosRenderContext, bottomBar: DesktopNativeWidget, bounds: Rect) {
        bottomBar.bounds = bounds
        val seekContainer = findOverlayRole(bottomBar, DesktopStageOverlaySpec.ROLE_SEEK_CONTAINER)
        val controlsRow = findOverlayRole(bottomBar, DesktopStageOverlaySpec.ROLE_CONTROLS_ROW) ?: bottomBar
        val seekVisible = seekContainer != null && seekContainer.visible && seekContainer.attributes["visibilityHidden"] != true

        val controlsBeforeSeek = if (seekVisible && controlsRow !== bottomBar) {
            val idxControls = bottomBar.children.indexOfFirst { it === controlsRow || containsDescendant(it, controlsRow) }
            val idxSeek = bottomBar.children.indexOfFirst { it === seekContainer || containsDescendant(it, seekContainer!!) }
            idxControls in 0 until idxSeek
        } else {
            false
        }

        val seekH = 22
        val gapY = 6
        val rowH = if (seekVisible) {
            (bounds.height - seekH - gapY).coerceIn(34, HUD_BAR_ITEM_HEIGHT)
        } else {
            bounds.height.coerceIn(34, HUD_BAR_ITEM_HEIGHT)
        }

        val seekRect: Rect?
        val rowRect: Rect
        if (seekVisible) {
            if (controlsBeforeSeek) {
                rowRect = Rect(bounds.x, bounds.y, bounds.width, rowH)
                seekRect = Rect(bounds.x, rowRect.bottom + gapY, bounds.width, seekH)
            } else {
                seekRect = Rect(bounds.x, bounds.y, bounds.width, seekH)
                rowRect = Rect(bounds.x, seekRect.bottom + gapY, bounds.width, rowH)
            }
        } else {
            seekRect = null
            rowRect = Rect(bounds.x, bounds.bottom - rowH, bounds.width, rowH)
        }

        if (seekContainer != null && seekRect != null) {
            seekContainer.bounds = seekRect
            val sliderNode = findFirstSlider(seekContainer)
            if (sliderNode != null) {
                sliderNode.bounds = seekRect
                val sliderImpl = sliderNode.attributes["nativeImpl"] as? DesktopRangeSliderNativeImpl<*>
                if (sliderImpl != null) {
                    MacosControlRenderUtil.renderRangeSlider(sliderImpl, ctx, seekRect)
                }
            } else {
                renderSeekContainerCustom(ctx, seekContainer, seekRect)
            }

            val hoverBubble = findOverlayRole(bottomBar, DesktopStageOverlaySpec.ROLE_HOVER_BUBBLE)
            if (hoverBubble != null && hoverBubble.visible && hoverBubble.attributes["visibilityHidden"] != true) {
                val bubbleText = extractFirstNonEmptyText(hoverBubble)
                if (!bubbleText.isNullOrBlank()) {
                    val m = MacosGraphicsDrawUtil.measureText(bubbleText, 11.5f, 600)
                    val bw = m.width + 14
                    val bh = 22
                    val progressFrac = (sliderNode?.attributes?.get("nativeImpl") as? DesktopRangeSliderNativeImpl<*>)?.let { s ->
                        val span = (s.getMax() - s.getMin()).takeIf { it > 0.0 } ?: 100.0
                        ((s.getValue() - s.getMin()) / span).toFloat().coerceIn(0f, 1f)
                    } ?: 0.5f
                    val anchorX = seekRect.x + (seekRect.width * progressFrac).roundToInt()
                    val bx = (anchorX - bw / 2).coerceIn(seekRect.x, (seekRect.right - bw).coerceAtLeast(seekRect.x))
                    val by = seekRect.y - bh - 6
                    MacosGraphicsDrawUtil.fillRoundedRect(ctx, bx, by, bw, bh, 6f, RgbaColor(20, 22, 28, 232))
                    MacosGraphicsDrawUtil.strokeRoundedRect(ctx, bx, by, bw, bh, 6f, DEFAULT_HUD_PILL_BORDER, 1f)
                    MacosGraphicsDrawUtil.drawText(ctx, bubbleText, bx + 6, by, bw - 12, bh, RgbaColor.WHITE, 11.5f, 600, TextAlignment.CENTER)
                }
            }
        }

        controlsRow.bounds = rowRect
        val leftUnits = ArrayList<DesktopNativeWidget>(8)
        val rightUnits = ArrayList<DesktopNativeWidget>(8)
        partitionControlsRow(controlsRow, leftUnits, rightUnits)

        val itemH = min(HUD_BAR_ITEM_HEIGHT, rowRect.height)
        val itemY = rowRect.y + (rowRect.height - itemH) / 2
        val gapX = 8

        var rightX = rowRect.right
        for (item in rightUnits.asReversed()) {
            val w = measureHudControlWidth(item)
            val ix = rightX - w
            val rect = Rect(ix, itemY, w, itemH)
            item.bounds = rect
            renderHudControl(ctx, item, rect)
            rightX = ix - gapX
        }

        var leftX = rowRect.x
        for (item in leftUnits) {
            if (leftX >= rightX - 16) break
            val w = min(measureHudControlWidth(item), (rightX - leftX).coerceAtLeast(24))
            val rect = Rect(leftX, itemY, w, itemH)
            item.bounds = rect
            renderHudControl(ctx, item, rect)
            leftX += w + gapX
        }
    }

    private fun partitionControlsRow(
        controlsRow: DesktopNativeWidget,
        leftUnits: MutableList<DesktopNativeWidget>,
        rightUnits: MutableList<DesktopNativeWidget>
    ) {
        var afterFlexSpacer = false
        val hasExplicitRightRole = controlsRow.children.any {
            it.visible && !it.disposed && it.attributes["visibilityHidden"] != true &&
                it.attributes[DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE] == DesktopStageOverlaySpec.ROLE_CONTROLS_RIGHT
        }

        for (child in controlsRow.children) {
            if (!child.visible || child.disposed || child.attributes["visibilityHidden"] == true) continue
            val role = child.attributes[DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE] as? String
            if (isPopoverRole(role)) continue

            if (isHudSpacer(child)) {
                if (isFlexHudSpacer(child)) {
                    afterFlexSpacer = true
                }
                continue
            }

            when (role) {
                DesktopStageOverlaySpec.ROLE_CONTROLS_LEFT -> {
                    for (sub in child.children) {
                        collectHudRowUnits(sub, leftUnits)
                    }
                }
                DesktopStageOverlaySpec.ROLE_CONTROLS_RIGHT -> {
                    if (isHudPillContainer(child)) {
                        rightUnits.add(child)
                    } else {
                        for (sub in child.children) {
                            collectHudRowUnits(sub, rightUnits)
                        }
                    }
                }
                else -> {
                    val target = if (afterFlexSpacer && !hasExplicitRightRole) rightUnits
                    else if (afterFlexSpacer) rightUnits
                    else leftUnits
                    collectHudRowUnits(child, target)
                }
            }
        }
    }

    private fun collectHudRowUnits(node: DesktopNativeWidget, out: MutableList<DesktopNativeWidget>) {
        if (!node.visible || node.disposed || node.attributes["visibilityHidden"] == true) return
        val role = node.attributes[DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE] as? String
        if (isPopoverRole(role) || isHudSpacer(node)) return
        if (role == DesktopStageOverlaySpec.ROLE_SEEK_CONTAINER || role == DesktopStageOverlaySpec.ROLE_HOVER_BUBBLE) return

        if (role == DesktopStageOverlaySpec.ROLE_EXPANDING_SLIDER_PILL ||
            role == DesktopStageOverlaySpec.ROLE_TIME_READOUT ||
            isHudPillContainer(node)
        ) {
            out.add(node)
            return
        }

        val impl = node.attributes["nativeImpl"]
        if (impl is DesktopButtonNativeImpl<*> ||
            impl is DesktopStyledButtonNativeImpl<*> ||
            impl is DesktopRangeSliderNativeImpl<*> ||
            impl is DesktopTextNativeImpl<*> ||
            impl is DesktopImageNativeImpl<*>
        ) {
            out.add(node)
            return
        }

        for (child in node.children) {
            collectHudRowUnits(child, out)
        }
    }

    private fun isHudPillContainer(widget: DesktopNativeWidget): Boolean {
        val impl = widget.attributes["nativeImpl"] as? DesktopStyledContainerNativeImpl<*> ?: return false
        val visuals = impl.getStyledVisuals()
        val bg = visuals.backgroundColor ?: (widget.attributes["backgroundColor"] as? RgbaColor)
        val border = visuals.borderColor ?: (widget.attributes["borderColor"] as? RgbaColor)
        return (bg != null && bg.a > 0) || (border != null && border.a > 0)
    }

    private fun isPopoverRole(role: String?): Boolean =
        role == DesktopStageOverlaySpec.ROLE_POPOVER_PANEL ||
            role == DesktopStageOverlaySpec.ROLE_COMPACT_POPOVER_MENU ||
            role == DesktopStageOverlaySpec.ROLE_INLINE_SLIDER_POPOVER

    private fun isHudSpacer(widget: DesktopNativeWidget): Boolean {
        if (widget.attributes["htmlTag"] == "genericui:spacer") return true
        if ("spacer" in widget.cachedAllClassTokens) return true
        return widget.attributes["spacerFill"] != null ||
            widget.attributes["spacerWidthPx"] != null ||
            widget.attributes["spacerHeightPx"] != null ||
            widget.attributes["spacerWeight"] != null
    }

    private fun isFlexHudSpacer(widget: DesktopNativeWidget): Boolean {
        if ("ms-auto" in widget.cachedAllClassTokens || "flex-grow-1" in widget.cachedAllClassTokens) return true
        if (widget.attributes["spacerFill"]?.toString().equals("true", ignoreCase = true)) return true
        if (((widget.attributes["spacerWeight"] as? Number)?.toFloat() ?: 0f) > 0f) return true
        if (((widget.attributes["flexGrow"] as? Number)?.toFloat() ?: 0f) > 0f) return true
        return false
    }

    private fun containsDescendant(root: DesktopNativeWidget, target: DesktopNativeWidget): Boolean {
        for (c in root.children) {
            if (c === target || containsDescendant(c, target)) return true
        }
        return false
    }

    private fun renderSeekContainerCustom(ctx: MacosRenderContext, seekNode: DesktopNativeWidget, bounds: Rect) {
        val trackH = 5
        val ty = bounds.y + (bounds.height - trackH) / 2
        MacosGraphicsDrawUtil.fillRoundedRect(ctx, bounds.x, ty, bounds.width, trackH, 2.5f, RgbaColor(255, 255, 255, 72))
        val progress = (seekNode.attributes["progressFraction"] as? Number)?.toFloat() ?: 0f
        val fillW = (bounds.width * progress.coerceIn(0f, 1f)).roundToInt()
        if (fillW > 0) {
            MacosGraphicsDrawUtil.fillRoundedRect(ctx, bounds.x, ty, fillW, trackH, 2.5f, ctx.palette.accentPrimary)
        }
        val impl = seekNode.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
        if (impl != null) {
            ctx.registerHitRegion(
                MacosHitRegion(
                    left = bounds.x,
                    top = bounds.y,
                    right = bounds.right,
                    bottom = bounds.bottom,
                    widget = seekNode,
                    cursorCss = "pointer",
                    onPress = { lx, ly, wx, wy, btn, cc ->
                        impl.dispatchPointerDown(PointerEvent(x = lx, y = ly, screenX = wx, screenY = wy, button = btn, clickCount = cc))
                        true
                    },
                    onDrag = { lx, ly, wx, wy ->
                        impl.dispatchPointerMove(PointerEvent(x = lx, y = ly, screenX = wx, screenY = wy, buttonsMask = 1))
                    },
                    onRelease = { lx, ly, wx, wy, _ ->
                        impl.dispatchPointerUp(PointerEvent(x = lx, y = ly, screenX = wx, screenY = wy))
                    },
                    onClick = { impl.dispatchClick() }
                )
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun renderHudControl(ctx: MacosRenderContext, widget: DesktopNativeWidget, bounds: Rect) {
        if (bounds.width <= 0 || bounds.height <= 0) return
        val localOpacity = widget.opacity.coerceIn(0f, 1f)
        if (localOpacity <= 0.001f) return
        val savedOpacity = ctx.currentOpacity
        ctx.currentOpacity = savedOpacity * localOpacity
        try {
            val role = widget.attributes[DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE] as? String
            if (role == DesktopStageOverlaySpec.ROLE_EXPANDING_SLIDER_PILL) {
                renderExpandingSliderPill(ctx, widget, bounds)
                return
            }

            val impl = widget.attributes["nativeImpl"]
            if (impl is DesktopStyledContainerNativeImpl<*>) {
                renderHudPillContainer(ctx, widget, impl, bounds)
                return
            }

            when (impl) {
                is DesktopStyledButtonNativeImpl<*> -> {
                    renderHudStyledButton(ctx, widget, impl as DesktopStyledButtonNativeImpl<DesktopNativeWidget>, bounds)
                }
                is DesktopButtonNativeImpl<*> -> {
                    if (impl.isHoveredState()) {
                        MacosGraphicsDrawUtil.fillRoundedRect(ctx, bounds.x, bounds.y, bounds.width, bounds.height, bounds.height / 2f, RgbaColor(255, 255, 255, 36))
                    }
                    val icon = MacosControlRenderUtil.resolveButtonIconSpec(impl)
                    val text = impl.getText().trim()
                    if (!icon.isNullOrEmpty() && text.isEmpty()) {
                        val side = min(19, (bounds.height - 10).coerceAtLeast(14))
                        drawHudIconOrGlyph(ctx, icon, bounds, side, RgbaColor.WHITE)
                    } else {
                        MacosGraphicsDrawUtil.drawText(
                            ctx,
                            text,
                            bounds.x + 6,
                            bounds.y,
                            (bounds.width - 12).coerceAtLeast(1),
                            bounds.height,
                            RgbaColor.WHITE,
                            12.5f,
                            600,
                            TextAlignment.CENTER
                        )
                    }
                    registerSimpleClick(ctx, impl as DesktopBaseNativeImpl<DesktopNativeWidget>, bounds)
                }
                is DesktopImageNativeImpl<*> -> {
                    val side = min(19, min(bounds.width, bounds.height))
                    val src = impl.getSourceUrl()
                    if (!src.isNullOrBlank()) {
                        MacosGraphicsDrawUtil.drawIconOrUri(
                            ctx,
                            src,
                            bounds.x + (bounds.width - side) / 2,
                            bounds.y + (bounds.height - side) / 2,
                            side,
                            side,
                            tintColor = impl.getTintColor() ?: RgbaColor.WHITE,
                            imageImpl = impl
                        )
                    }
                    if (impl.hasClickListener()) {
                        registerSimpleClick(ctx, impl as DesktopBaseNativeImpl<DesktopNativeWidget>, bounds)
                    }
                }
                is DesktopRangeSliderNativeImpl<*> -> {
                    MacosControlRenderUtil.renderRangeSlider(impl, ctx, bounds)
                }
                is DesktopTextNativeImpl<*> -> {
                    renderHudTextOrBadge(ctx, widget, impl as DesktopTextNativeImpl<DesktopNativeWidget>, bounds)
                }
            }
        } finally {
            ctx.currentOpacity = savedOpacity
        }
    }

    private fun renderExpandingSliderPill(ctx: MacosRenderContext, widget: DesktopNativeWidget, bounds: Rect) {
        val styledImpl = widget.attributes["nativeImpl"] as? DesktopStyledContainerNativeImpl<*>
        val visuals = styledImpl?.getStyledVisuals()
        val bg = visuals?.backgroundColor?.takeIf { it.a > 0 }
            ?: (widget.attributes["backgroundColor"] as? RgbaColor)?.takeIf { it.a > 0 }
            ?: DEFAULT_HUD_PILL_BG
        val border = visuals?.borderColor?.takeIf { it.a > 0 }
            ?: (widget.attributes["borderColor"] as? RgbaColor)?.takeIf { it.a > 0 }
            ?: DEFAULT_HUD_PILL_BORDER
        val radius = visuals?.borderRadiusPx ?: (bounds.height / 2f)

        if (visuals?.hasBackdropFilter == true) {
            MacosGraphicsDrawUtil.applyBackdropFilterRoundedRect(
                ctx = ctx,
                x = bounds.x,
                y = bounds.y,
                w = bounds.width,
                h = bounds.height,
                radiusPx = radius,
                backdropBlurPx = visuals.backdropBlurPx,
                backdropFilter = visuals.backdropFilter
            )
        }
        MacosGraphicsDrawUtil.fillRoundedRect(ctx, bounds.x, bounds.y, bounds.width, bounds.height, radius, bg)
        MacosGraphicsDrawUtil.strokeRoundedRect(ctx, bounds.x, bounds.y, bounds.width, bounds.height, radius, border, 1f)

        // Base hover region on the pill container so pointer-enter/leave animations hold steady across gaps
        ctx.registerHitRegion(
            MacosHitRegion(
                left = bounds.x,
                top = bounds.y,
                right = bounds.right,
                bottom = bounds.bottom,
                widget = widget,
                cursorCss = "pointer"
            )
        )

        val muteBtn = widget.children.firstOrNull {
            it.visible && !it.disposed &&
                (it.attributes["nativeImpl"] is DesktopButtonNativeImpl<*> || it.attributes["nativeImpl"] is DesktopStyledButtonNativeImpl<*>)
        }
        val sliderNode = findFirstSlider(widget)
        val animSliderW = resolveExpandingSliderWidthPx(widget, sliderNode)

        var xCur = bounds.x + 4
        if (muteBtn != null) {
            val btnSide = (bounds.height - 8).coerceAtLeast(26)
            val bRect = Rect(
                if (animSliderW <= 2) bounds.x + (bounds.width - btnSide) / 2 else xCur,
                bounds.y + (bounds.height - btnSide) / 2,
                btnSide,
                btnSide
            )
            muteBtn.bounds = bRect
            renderHudControl(ctx, muteBtn, bRect)
            if (widget.attributes["data-custom-volume-icons"] != "true") {
                renderExpandingVolumeIconStack(ctx, widget, bRect)
            }
            xCur = bRect.right + 4
        }

        if (animSliderW > 4 && sliderNode != null) {
            val sliderImpl = sliderNode.attributes["nativeImpl"] as? DesktopRangeSliderNativeImpl<*>
            if (sliderImpl != null) {
                val availW = (bounds.right - 8 - xCur).coerceAtLeast(12)
                val sw = min(animSliderW, availW)
                val sRect = Rect(xCur, bounds.y + 4, sw, (bounds.height - 8).coerceAtLeast(16))
                sliderNode.bounds = sRect
                MacosGraphicsDrawUtil.withClip(ctx, sRect.x - 4, bounds.y, sw + 8, bounds.height) {
                    MacosControlRenderUtil.renderRangeSlider(sliderImpl, ctx, sRect)
                }
            }
        }
    }

    private fun resolveVolumeLayerAlpha(pillWidget: DesktopNativeWidget, layerWidget: DesktopNativeWidget): Float {
        val attrKey = when (layerWidget.attributes["data-volume-layer"] as? String) {
            "speaker" -> return 1f
            "wave-1" -> "data-wave-1-opacity"
            "wave-2" -> "data-wave-2-opacity"
            "wave-3" -> "data-wave-3-opacity"
            "mute-cross" -> "data-mute-cross-opacity"
            else -> null
        }
        val fromAttr = attrKey?.let { (pillWidget.attributes[it] as? String)?.toFloatOrNull() }
        return (fromAttr ?: layerWidget.opacity).coerceIn(0f, 1f)
    }

    private fun renderExpandingVolumeIconStack(
        ctx: MacosRenderContext,
        pillWidget: DesktopNativeWidget,
        buttonRect: Rect
    ) {
        val stackWidget = pillWidget.children.getOrNull(2)?.takeIf { it.visible && !it.disposed } ?: return
        val iconSide = 18
        val savedOpacity = ctx.currentOpacity
        for (layerWidget in stackWidget.children) {
            if (!layerWidget.visible || layerWidget.disposed) continue
            val imgImpl = layerWidget.attributes["nativeImpl"] as? DesktopImageNativeImpl<*> ?: continue
            val url = imgImpl.getSourceUrl()?.trim().orEmpty()
            if (url.isEmpty()) continue
            val layerAlpha = resolveVolumeLayerAlpha(pillWidget, layerWidget)
            if (layerAlpha <= 0.005f) continue
            ctx.currentOpacity = (savedOpacity * layerAlpha).coerceIn(0f, 1f)
            try {
                drawHudIconOrGlyph(
                    ctx = ctx,
                    iconSpec = url,
                    bounds = buttonRect,
                    iconSide = iconSide,
                    fg = RgbaColor(242, 245, 250, 255)
                )
            } finally {
                ctx.currentOpacity = savedOpacity
            }
        }
    }

    private fun renderHudPillContainer(
        ctx: MacosRenderContext,
        widget: DesktopNativeWidget,
        styledImpl: DesktopStyledContainerNativeImpl<*>,
        bounds: Rect
    ) {
        val visuals = styledImpl.getStyledVisuals()
        val bg = visuals.backgroundColor?.takeIf { it.a > 0 }
            ?: (widget.attributes["backgroundColor"] as? RgbaColor)?.takeIf { it.a > 0 }
            ?: DEFAULT_HUD_PILL_BG
        val border = visuals.borderColor?.takeIf { it.a > 0 }
            ?: (widget.attributes["borderColor"] as? RgbaColor)?.takeIf { it.a > 0 }
            ?: DEFAULT_HUD_PILL_BORDER
        val radius = visuals?.borderRadiusPx ?: (bounds.height / 2f)

        if (visuals?.hasBackdropFilter == true) {
            MacosGraphicsDrawUtil.applyBackdropFilterRoundedRect(
                ctx = ctx,
                x = bounds.x,
                y = bounds.y,
                w = bounds.width,
                h = bounds.height,
                radiusPx = radius,
                backdropBlurPx = visuals.backdropBlurPx,
                backdropFilter = visuals.backdropFilter
            )
        }
        MacosGraphicsDrawUtil.fillRoundedRect(ctx, bounds.x, bounds.y, bounds.width, bounds.height, radius, bg)
        if (visuals.borderStyle != CssBorderStyle.NONE) {
            MacosGraphicsDrawUtil.strokeRoundedRect(ctx, bounds.x, bounds.y, bounds.width, bounds.height, radius, border, 1f)
        }

        val innerLeaves = ArrayList<DesktopNativeWidget>(6)
        collectHudLeafControls(widget, innerLeaves)
        if (innerLeaves.isEmpty()) return

        val isTimePill = widget.attributes[DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE] == DesktopStageOverlaySpec.ROLE_TIME_READOUT
        val padX = if (isTimePill) 13 else 4
        val gap = if (isTimePill) 8 else 4
        var curX = bounds.x + padX
        for (child in innerLeaves) {
            if (curX >= bounds.right - padX) break
            val cw = min(measureHudPillInnerItemWidth(child), (bounds.right - padX - curX).coerceAtLeast(16))
            val isBadge = "badge" in child.cachedAllClassTokens
            val ch = when {
                isBadge -> 22
                child.attributes["nativeImpl"] is DesktopButtonNativeImpl<*> -> (bounds.height - 6).coerceAtLeast(26)
                else -> bounds.height
            }
            val cy = bounds.y + (bounds.height - ch) / 2
            val cRect = Rect(curX, cy, cw, ch)
            child.bounds = cRect
            renderHudControl(ctx, child, cRect)
            curX += cw + gap
        }
    }

    private fun renderHudStyledButton(
        ctx: MacosRenderContext,
        widget: DesktopNativeWidget,
        impl: DesktopStyledButtonNativeImpl<DesktopNativeWidget>,
        bounds: Rect
    ) {
        val visuals = impl.resolveCurrentVisuals()
        val style = impl.getComponentStyle()
        val snap = impl.getStyleSnapshot()
        val bsType = snap.type?.takeIf { it != BootstrapType.NONE && it != BootstrapType.LINK }

        val radius = visuals.borderRadiusPx
            ?: (widget.attributes["borderRadiusPx"] as? Float)
            ?: style?.borderRadius?.topLeft?.resolvePx()
            ?: (min(bounds.width, bounds.height) / 2f)

        val explicitBg = visuals.backgroundColor
            ?: style?.backgroundColor
            ?: (widget.attributes["backgroundColor"] as? RgbaColor)

        val bg: RgbaColor = when {
            explicitBg != null && explicitBg.a > 0 -> {
                when {
                    !widget.enabled -> explicitBg.withAlpha(explicitBg.alphaFloat * 0.5f)
                    impl.isActivePressedState() && impl.getActiveVisuals()?.backgroundColor == null ->
                        explicitBg.adjustBrightness(-16)
                    impl.isHoveredState() && impl.getHoverVisuals()?.backgroundColor == null ->
                        RgbaColor(
                            (explicitBg.r + 28).coerceAtMost(255),
                            (explicitBg.g + 28).coerceAtMost(255),
                            (explicitBg.b + 32).coerceAtMost(255),
                            (explicitBg.a + 28).coerceAtMost(245)
                        )
                    else -> explicitBg
                }
            }
            explicitBg != null -> {
                // Explicitly transparent button (e.g. inner button inside a pill container)
                if (widget.enabled && (impl.isActivePressedState() || impl.isHoveredState())) {
                    impl.getHoverVisuals()?.backgroundColor?.takeIf { it.a > 0 } ?: RgbaColor(255, 255, 255, 38)
                } else {
                    RgbaColor.TRANSPARENT
                }
            }
            bsType != null -> {
                val bsCol = BootstrapColorPalette.resolveColor(bsType, darkMode = true)
                if (snap.outline) {
                    if (impl.isHoveredState() || impl.isActivePressedState()) bsCol.withAlpha(0.22f) else RgbaColor.TRANSPARENT
                } else {
                    bsCol
                }
            }
            else -> {
                // Standalone HUD icon/circle button without explicit bg falls back to dark translucent HUD circle
                if (impl.isHoveredState()) RgbaColor(42, 44, 52, 210) else DEFAULT_HUD_PILL_BG
            }
        }

        if (visuals.hasBackdropFilter) {
            MacosGraphicsDrawUtil.applyBackdropFilterRoundedRect(
                ctx = ctx,
                x = bounds.x,
                y = bounds.y,
                w = bounds.width,
                h = bounds.height,
                radiusPx = radius,
                backdropBlurPx = visuals.backdropBlurPx,
                backdropFilter = visuals.backdropFilter
            )
        }
        if (bg.a > 0) {
            MacosGraphicsDrawUtil.fillRoundedRect(ctx, bounds.x, bounds.y, bounds.width, bounds.height, radius, bg)
        }

        val borderCol = visuals.borderColor
            ?: style?.borderColor
            ?: (widget.attributes["borderColor"] as? RgbaColor)
            ?: if (explicitBg == null && bsType == null) DEFAULT_HUD_PILL_BORDER else null

        if (borderCol != null && borderCol.a > 0 && visuals.borderStyle != CssBorderStyle.NONE) {
            val bw = (visuals.borderWidthPx ?: 1f).coerceAtLeast(1f)
            MacosGraphicsDrawUtil.strokeRoundedRect(ctx, bounds.x, bounds.y, bounds.width, bounds.height, radius, borderCol, bw)
        }

        // Video stage HUD always sits over a dark media surface, so default unstyled foreground is crisp white
        val fg = visuals.textColor
            ?: style?.color
            ?: (widget.attributes["textColor"] as? RgbaColor)
            ?: if (bsType != null && !snap.outline) {
                BootstrapColorPalette.resolveForegroundOn(bsType, darkMode = true)
            } else if (bsType != null) {
                BootstrapColorPalette.resolveColor(bsType, darkMode = true)
            } else {
                RgbaColor.WHITE
            }
        val effectiveFg = if (!widget.enabled) fg.withAlpha(0.5f) else fg

        val iconSpec = MacosControlRenderUtil.resolveButtonIconSpec(impl)
        val text = impl.getText().trim()
        if (!iconSpec.isNullOrEmpty() && text.isEmpty()) {
            val iconSide = when {
                bounds.height >= 54 -> 24
                bounds.height <= 34 -> 17
                else -> 19
            }
            drawHudIconOrGlyph(ctx, iconSpec, bounds, iconSide, effectiveFg)
        } else if (text.isNotEmpty()) {
            MacosGraphicsDrawUtil.drawText(
                ctx = ctx,
                text = text,
                x = bounds.x + 6,
                y = bounds.y,
                w = (bounds.width - 12).coerceAtLeast(1),
                h = bounds.height,
                color = effectiveFg,
                fontSizePx = 12.5f,
                weightNumeric = 600,
                alignment = TextAlignment.CENTER
            )
        }
        registerSimpleClick(ctx, impl, bounds)
    }

    private fun renderHudTextOrBadge(
        ctx: MacosRenderContext,
        widget: DesktopNativeWidget,
        impl: DesktopTextNativeImpl<DesktopNativeWidget>,
        bounds: Rect
    ) {
        val txt = impl.getText()
        if (txt.isEmpty()) return
        val tokens = widget.cachedAllClassTokens
        if ("badge" in tokens || impl.componentClassPrefix.contains("badge")) {
            val snap = impl.getStyleSnapshot()
            val badgeType = snap.backgroundType?.takeIf { it != BootstrapType.NONE }
                ?: snap.type?.takeIf { it != BootstrapType.NONE }
                ?: BootstrapType.INFO
            val bgCol = BootstrapColorPalette.resolveColor(badgeType, darkMode = true)
            val fgCol = BootstrapColorPalette.resolveForegroundOn(badgeType, darkMode = true)
            MacosGraphicsDrawUtil.fillRoundedRect(ctx, bounds.x, bounds.y, bounds.width, bounds.height, bounds.height / 2f, bgCol)
            MacosGraphicsDrawUtil.drawText(
                ctx,
                txt,
                bounds.x + 6,
                bounds.y,
                (bounds.width - 12).coerceAtLeast(1),
                bounds.height,
                fgCol,
                11.5f,
                600,
                TextAlignment.CENTER
            )
            if (impl.hasClickListener()) {
                registerSimpleClick(ctx, impl, bounds)
            }
            return
        }
        MacosGraphicsDrawUtil.drawText(
            ctx,
            txt,
            bounds.x,
            bounds.y,
            bounds.width.coerceAtLeast(1),
            bounds.height,
            RgbaColor(238, 241, 248),
            12.5f,
            500,
            alignment = TextAlignment.CENTER,
            monospace = true
        )
        if (impl.hasClickListener()) {
            registerSimpleClick(ctx, impl, bounds)
        }
    }

    private fun drawHudIconOrGlyph(
        ctx: MacosRenderContext,
        iconSpec: String,
        bounds: Rect,
        iconSide: Int,
        fg: RgbaColor
    ) {
        if (!MacosControlRenderUtil.isDrawableUri(iconSpec)) {
            MacosGraphicsDrawUtil.drawText(ctx, iconSpec, bounds.x, bounds.y, bounds.width, bounds.height, fg, 15f, 600, TextAlignment.CENTER)
        } else {
            MacosGraphicsDrawUtil.drawIconOrUri(
                ctx = ctx,
                uriOrIcon = iconSpec,
                dstX = bounds.x + (bounds.width - iconSide) / 2,
                dstY = bounds.y + (bounds.height - iconSide) / 2,
                dstW = iconSide,
                dstH = iconSide,
                tintColor = fg
            )
        }
    }

    private fun registerSimpleClick(
        ctx: MacosRenderContext,
        impl: DesktopBaseNativeImpl<DesktopNativeWidget>,
        bounds: Rect
    ) {
        ctx.registerHitRegion(
            MacosHitRegion(
                left = bounds.x,
                top = bounds.y,
                right = bounds.right,
                bottom = bounds.bottom,
                widget = impl.nativeWidget,
                tooltip = impl.nativeWidget.tooltipText,
                cursorCss = "pointer",
                onPress = { lx, ly, wx, wy, btn, cc ->
                    impl.dispatchPointerDown(PointerEvent(x = lx, y = ly, screenX = wx, screenY = wy, button = btn, clickCount = cc))
                    false
                },
                onRelease = { lx, ly, wx, wy, _ ->
                    impl.dispatchPointerUp(PointerEvent(x = lx, y = ly, screenX = wx, screenY = wy))
                },
                onClick = { impl.dispatchClick() }
            )
        )
    }

    private fun resolveExpandingSliderWidthPx(widget: DesktopNativeWidget, sliderNode: DesktopNativeWidget?): Int {
        if (sliderNode == null || !sliderNode.visible || sliderNode.disposed) return 0
        widget.attributes["data-animated-slider-width-px"]?.toString()?.toIntOrNull()?.let { return it.coerceAtLeast(0) }
        val expandedAttr = widget.attributes["data-expanded"]?.toString()
        if (expandedAttr != null) {
            return if (expandedAttr.equals("true", ignoreCase = true)) 92 else 0
        }
        return 92
    }

    private fun measureHudControlWidth(widget: DesktopNativeWidget): Int {
        val role = widget.attributes[DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE] as? String
        if (role == DesktopStageOverlaySpec.ROLE_EXPANDING_SLIDER_PILL) {
            val sliderNode = findFirstSlider(widget)
            val animW = resolveExpandingSliderWidthPx(widget, sliderNode)
            return if (animW > 4) (40 + animW + 10) else HUD_BAR_ITEM_HEIGHT
        }

        val impl = widget.attributes["nativeImpl"]
        if (impl is DesktopStyledContainerNativeImpl<*>) {
            val innerLeaves = ArrayList<DesktopNativeWidget>(6)
            collectHudLeafControls(widget, innerLeaves)
            if (innerLeaves.isEmpty()) return HUD_BAR_ITEM_HEIGHT
            val isTimePill = role == DesktopStageOverlaySpec.ROLE_TIME_READOUT
            val padX = if (isTimePill) 13 else 4
            val gap = if (isTimePill) 8 else 4
            val contentW = innerLeaves.sumOf { measureHudPillInnerItemWidth(it) } + gap * (innerLeaves.size - 1).coerceAtLeast(0)
            return (contentW + padX * 2).coerceAtLeast(HUD_BAR_ITEM_HEIGHT)
        }

        return when (impl) {
            is DesktopButtonNativeImpl<*> -> {
                val txt = impl.getText().trim()
                if (txt.isEmpty()) {
                    HUD_BAR_ITEM_HEIGHT
                } else {
                    (MacosGraphicsDrawUtil.measureText(txt, 12.5f, 600).width + 24).coerceAtLeast(44)
                }
            }
            is DesktopRangeSliderNativeImpl<*> -> 96
            is DesktopTextNativeImpl<*> -> {
                val txt = impl.getText()
                if ("badge" in widget.cachedAllClassTokens) {
                    (MacosGraphicsDrawUtil.measureText(txt, 11.5f, 600).width + 18).coerceAtLeast(40)
                } else {
                    (MacosGraphicsDrawUtil.measureText(txt, 12.5f, 500, monospace = true).width + 12).coerceAtLeast(48)
                }
            }
            else -> HUD_BAR_ITEM_HEIGHT
        }
    }

    private fun measureHudPillInnerItemWidth(widget: DesktopNativeWidget): Int {
        val impl = widget.attributes["nativeImpl"]
        return when (impl) {
            is DesktopButtonNativeImpl<*> -> {
                val txt = impl.getText().trim()
                if (txt.isEmpty()) 34 else (MacosGraphicsDrawUtil.measureText(txt, 12f, 600).width + 18).coerceAtLeast(36)
            }
            is DesktopTextNativeImpl<*> -> {
                val txt = impl.getText()
                if ("badge" in widget.cachedAllClassTokens) {
                    (MacosGraphicsDrawUtil.measureText(txt, 11.5f, 600).width + 16).coerceAtLeast(38)
                } else {
                    max(44, MacosGraphicsDrawUtil.measureText(txt, 12.5f, 500, monospace = true).width + 4)
                }
            }
            is DesktopRangeSliderNativeImpl<*> -> 88
            else -> 34
        }
    }

    private fun collectHudLeafControls(node: DesktopNativeWidget, out: MutableList<DesktopNativeWidget>) {
        for (child in node.children) {
            if (!child.visible || child.disposed || child.attributes["visibilityHidden"] == true) continue
            val role = child.attributes[DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE] as? String
            if (isPopoverRole(role) || isHudSpacer(child)) continue
            if (role == DesktopStageOverlaySpec.ROLE_EXPANDING_SLIDER_PILL) {
                out.add(child)
                continue
            }
            val impl = child.attributes["nativeImpl"]
            if (impl is DesktopButtonNativeImpl<*> ||
                impl is DesktopStyledButtonNativeImpl<*> ||
                impl is DesktopRangeSliderNativeImpl<*> ||
                impl is DesktopTextNativeImpl<*> ||
                impl is DesktopImageNativeImpl<*>
            ) {
                out.add(child)
            } else {
                collectHudLeafControls(child, out)
            }
        }
    }

    private fun renderOverlayPopoverPanel(
        ctx: MacosRenderContext,
        popover: DesktopNativeWidget,
        stageBounds: Rect,
        renderGenericSubtree: (MacosRenderContext, DesktopNativeWidget, Rect) -> Unit
    ) {
        val role = popover.attributes[DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE] as? String
        val styledImpl = popover.attributes["nativeImpl"] as? DesktopStyledContainerNativeImpl<*>
        val visuals = styledImpl?.getStyledVisuals()
        val popW = when (role) {
            DesktopStageOverlaySpec.ROLE_INLINE_SLIDER_POPOVER -> 220
            DesktopStageOverlaySpec.ROLE_COMPACT_POPOVER_MENU -> 230
            else -> 260
        }.coerceAtMost((stageBounds.width - 24).coerceAtLeast(160))
        val popH = when (role) {
            DesktopStageOverlaySpec.ROLE_INLINE_SLIDER_POPOVER -> 52
            else -> min(280, (stageBounds.height - 96).coerceAtLeast(120))
        }
        val px = (stageBounds.right - popW - 16).coerceAtLeast(stageBounds.x + 12)
        val py = (stageBounds.bottom - 82 - popH).coerceAtLeast(stageBounds.y + 16)
        val rect = Rect(px, py, popW, popH)
        popover.bounds = rect

        val bg = visuals?.backgroundColor?.takeIf { it.a > 0 } ?: RgbaColor(24, 27, 34, 244)
        val border = visuals?.borderColor?.takeIf { it.a > 0 } ?: RgbaColor(255, 255, 255, 48)
        val radius = visuals?.borderRadiusPx ?: 10f
        MacosGraphicsDrawUtil.fillRoundedRect(ctx, rect.x, rect.y, rect.width, rect.height, radius, bg)
        MacosGraphicsDrawUtil.strokeRoundedRect(ctx, rect.x, rect.y, rect.width, rect.height, radius, border, 1f)

        val inner = Rect(rect.x + 10, rect.y + 10, (rect.width - 20).coerceAtLeast(20), (rect.height - 20).coerceAtLeast(20))
        renderGenericSubtree(ctx, popover, inner)
    }

    private fun findOverlayRole(root: DesktopNativeWidget, targetRole: String): DesktopNativeWidget? {
        if (!root.visible || root.disposed || root.attributes["visibilityHidden"] == true) return null
        if (root.attributes[DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE] == targetRole) return root
        for (child in root.children) {
            if (!child.visible || child.disposed || child.attributes["visibilityHidden"] == true) continue
            findOverlayRole(child, targetRole)?.let { return it }
        }
        return null
    }

    private fun collectVisiblePopovers(node: DesktopNativeWidget, out: MutableList<DesktopNativeWidget>) {
        if (!node.visible || node.disposed || node.attributes["visibilityHidden"] == true) return
        val role = node.attributes[DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE] as? String
        if (isPopoverRole(role)) {
            out.add(node)
            return
        }
        for (child in node.children) {
            collectVisiblePopovers(child, out)
        }
    }

    private fun findFirstSlider(node: DesktopNativeWidget): DesktopNativeWidget? {
        if (!node.visible || node.disposed || node.attributes["visibilityHidden"] == true) return null
        if (node.attributes["nativeImpl"] is DesktopRangeSliderNativeImpl<*>) return node
        for (child in node.children) {
            findFirstSlider(child)?.let { return it }
        }
        return null
    }

    private fun extractFirstNonEmptyText(node: DesktopNativeWidget): String? {
        val impl = node.attributes["nativeImpl"]
        if (impl is DesktopTextNativeImpl<*>) {
            val t = impl.getText().trim()
            if (t.isNotEmpty()) return t
        }
        for (child in node.children) {
            if (!child.visible || child.disposed) continue
            extractFirstNonEmptyText(child)?.let { return it }
        }
        return null
    }
}
