package com.nexustools.genericui.linux.titlebar

import com.nexustools.genericui.enums.window.TitleBarControlsPlacement
import com.nexustools.genericui.geometry.Rect

/**
 * Pure-Kotlin geometry layout, hit-testing, draggable-background calculation, and action
 * dispatch engine for replicated Linux titlebars.
 */
object LinuxTitleBarLayoutEngine {

    fun computeLayout(
        profile: LinuxTitleBarProfile,
        barBounds: Rect,
        isCompact: Boolean = false,
        showMinimize: Boolean = true,
        showMaximize: Boolean = true,
        showClose: Boolean = true,
        leadingWidthPx: Int = 0,
        centerWidthPx: Int = 0,
        trailingWidthPx: Int = 0
    ): LinuxTitleBarLayoutResult {
        val metrics = profile.metrics
        val effectiveHeight = if (barBounds.height > 0) {
            barBounds.height
        } else {
            if (isCompact) metrics.compactHeightPx else metrics.heightPx
        }.coerceAtLeast(1)
        val effectiveBar = Rect(
            x = barBounds.x,
            y = barBounds.y,
            width = barBounds.width.coerceAtLeast(0),
            height = effectiveHeight
        )

        val visibleLeft = filterVisibleButtons(profile.leftButtons, showMinimize, showMaximize, showClose)
        val visibleRight = filterVisibleButtons(profile.rightButtons, showMinimize, showMaximize, showClose)

        val btnW = metrics.buttonWidthPx.coerceAtLeast(12)
        val btnH = metrics.buttonHeightPx.coerceAtLeast(12).coerceAtMost(effectiveHeight)
        val btnY = effectiveBar.y + (effectiveHeight - btnH) / 2
        val padX = metrics.horizontalPaddingPx.coerceAtLeast(0)
        val spacing = metrics.buttonSpacingPx.coerceAtLeast(0)
        val spacerW = (btnW / 2).coerceAtLeast(6)

        val leftHits = ArrayList<LinuxTitleBarButtonHit>(visibleLeft.size)
        var curLeftX = effectiveBar.x + padX
        for (kind in visibleLeft) {
            if (kind == LinuxTitleButtonKind.SPACER) {
                curLeftX += spacerW + spacing
                continue
            }
            if (curLeftX + btnW <= effectiveBar.right) {
                leftHits.add(
                    LinuxTitleBarButtonHit(
                        kind = kind,
                        side = LinuxTitleButtonSide.LEFT,
                        bounds = Rect(curLeftX, btnY, btnW, btnH)
                    )
                )
            }
            curLeftX += btnW + spacing
        }
        val leftEnd = if (leftHits.isNotEmpty()) {
            (leftHits.last().bounds.right + padX).coerceAtMost(effectiveBar.right)
        } else {
            (effectiveBar.x + padX).coerceAtMost(effectiveBar.right)
        }

        val totalRightSpan = computeButtonGroupSpan(visibleRight, btnW, spacerW, spacing)
        val rightHits = ArrayList<LinuxTitleBarButtonHit>(visibleRight.size)
        var curRightX = (effectiveBar.right - padX - totalRightSpan).coerceAtLeast(leftEnd)
        for (kind in visibleRight) {
            if (kind == LinuxTitleButtonKind.SPACER) {
                curRightX += spacerW + spacing
                continue
            }
            val clampedW = (effectiveBar.right - padX - curRightX).coerceIn(0, btnW)
            if (clampedW > 0) {
                rightHits.add(
                    LinuxTitleBarButtonHit(
                        kind = kind,
                        side = LinuxTitleButtonSide.RIGHT,
                        bounds = Rect(curRightX, btnY, clampedW, btnH)
                    )
                )
            }
            curRightX += btnW + spacing
        }
        val rightStart = if (rightHits.isNotEmpty()) {
            (rightHits.first().bounds.x - padX).coerceAtLeast(leftEnd)
        } else {
            (effectiveBar.right - padX).coerceAtLeast(leftEnd)
        }

        val contentX = leftEnd
        val contentW = (rightStart - leftEnd).coerceAtLeast(0)
        val contentSlotBounds = Rect(contentX, effectiveBar.y, contentW, effectiveHeight)

        val clampedLeadingW = leadingWidthPx.coerceIn(0, contentW)
        val leadingSlotBounds = Rect(contentX, effectiveBar.y, clampedLeadingW, effectiveHeight)

        val clampedTrailingW = trailingWidthPx.coerceIn(0, (contentW - clampedLeadingW).coerceAtLeast(0))
        val trailingSlotBounds = Rect(
            x = contentX + contentW - clampedTrailingW,
            y = effectiveBar.y,
            width = clampedTrailingW,
            height = effectiveHeight
        )

        val remX = leadingSlotBounds.right
        val remW = (trailingSlotBounds.x - remX).coerceAtLeast(0)
        val clampedCenterW = centerWidthPx.coerceIn(0, remW)
        val centerSlotBounds = if (clampedCenterW > 0) {
            val idealCenterX = effectiveBar.x + (effectiveBar.width - clampedCenterW) / 2
            val minCenterX = remX
            val maxCenterX = (remX + remW - clampedCenterW).coerceAtLeast(minCenterX)
            Rect(idealCenterX.coerceIn(minCenterX, maxCenterX), effectiveBar.y, clampedCenterW, effectiveHeight)
        } else {
            Rect(remX + remW / 2, effectiveBar.y, 0, effectiveHeight)
        }

        val titleTextBounds = if (clampedCenterW <= 0) {
            Rect(remX, effectiveBar.y, remW, effectiveHeight)
        } else {
            val leftSpace = (centerSlotBounds.x - remX).coerceAtLeast(0)
            val rightSpace = ((remX + remW) - centerSlotBounds.right).coerceAtLeast(0)
            if (leftSpace >= rightSpace) {
                Rect(remX, effectiveBar.y, leftSpace, effectiveHeight)
            } else {
                Rect(centerSlotBounds.right, effectiveBar.y, rightSpace, effectiveHeight)
            }
        }

        return LinuxTitleBarLayoutResult(
            barBounds = effectiveBar,
            leftButtons = leftHits,
            rightButtons = rightHits,
            contentSlotBounds = contentSlotBounds,
            leadingSlotBounds = leadingSlotBounds,
            centerSlotBounds = centerSlotBounds,
            trailingSlotBounds = trailingSlotBounds,
            titleTextBounds = titleTextBounds
        )
    }

    fun hitTestButton(layout: LinuxTitleBarLayoutResult, x: Int, y: Int): LinuxTitleBarButtonHit? {
        for (hit in layout.leftButtons) {
            if (hit.kind != LinuxTitleButtonKind.SPACER && hit.bounds.contains(x, y)) {
                return hit
            }
        }
        for (hit in layout.rightButtons) {
            if (hit.kind != LinuxTitleButtonKind.SPACER && hit.bounds.contains(x, y)) {
                return hit
            }
        }
        return null
    }

    fun isDraggableBackground(
        layout: LinuxTitleBarLayoutResult,
        x: Int,
        y: Int,
        occupiedWidgetRects: List<Rect> = emptyList()
    ): Boolean {
        if (!layout.barBounds.contains(x, y)) return false
        if (hitTestButton(layout, x, y) != null) return false
        for (rect in occupiedWidgetRects) {
            if (rect.contains(x, y)) return false
        }
        return true
    }

    fun dispatchButtonAction(
        kind: LinuxTitleButtonKind,
        actions: LinuxTitleBarWindowActions,
        screenX: Int = 0,
        screenY: Int = 0,
        onContextHelp: (() -> Unit)? = null
    ): Boolean = when (kind) {
        LinuxTitleButtonKind.CLOSE -> {
            actions.onClose()
            true
        }
        LinuxTitleButtonKind.MINIMIZE -> {
            actions.onMinimize()
            true
        }
        LinuxTitleButtonKind.MAXIMIZE_OR_RESTORE -> {
            actions.onMaximizeOrRestore()
            true
        }
        LinuxTitleButtonKind.MENU -> {
            actions.onShowWindowMenu(screenX, screenY)
            true
        }
        LinuxTitleButtonKind.KEEP_ABOVE -> {
            actions.onToggleKeepAbove()
            true
        }
        LinuxTitleButtonKind.KEEP_BELOW -> {
            actions.onToggleKeepBelow()
            true
        }
        LinuxTitleButtonKind.ON_ALL_DESKTOPS -> {
            actions.onToggleAllDesktops()
            true
        }
        LinuxTitleButtonKind.SHADE -> {
            actions.onToggleShade()
            true
        }
        LinuxTitleButtonKind.CONTEXT_HELP -> {
            if (onContextHelp != null) {
                onContextHelp()
                true
            } else {
                false
            }
        }
        LinuxTitleButtonKind.SPACER -> false
    }

    private fun filterVisibleButtons(
        buttons: List<LinuxTitleButtonKind>,
        showMinimize: Boolean,
        showMaximize: Boolean,
        showClose: Boolean
    ): List<LinuxTitleButtonKind> {
        val out = ArrayList<LinuxTitleButtonKind>(buttons.size)
        for (kind in buttons) {
            val include = when (kind) {
                LinuxTitleButtonKind.MINIMIZE -> showMinimize
                LinuxTitleButtonKind.MAXIMIZE_OR_RESTORE -> showMaximize
                LinuxTitleButtonKind.CLOSE -> showClose
                else -> true
            }
            if (include) out.add(kind)
        }
        return out
    }

    private fun computeButtonGroupSpan(
        buttons: List<LinuxTitleButtonKind>,
        btnW: Int,
        spacerW: Int,
        spacing: Int
    ): Int {
        if (buttons.isEmpty()) return 0
        var total = 0
        for (i in buttons.indices) {
            if (i > 0) total += spacing
            total += if (buttons[i] == LinuxTitleButtonKind.SPACER) spacerW else btnW
        }
        return total
    }

    fun applyControlsPlacement(
        profile: LinuxTitleBarProfile,
        placement: TitleBarControlsPlacement
    ): LinuxTitleBarProfile {
        if (placement == TitleBarControlsPlacement.PLATFORM_DEFAULT) return profile
        val captionKinds = setOf(
            LinuxTitleButtonKind.MINIMIZE,
            LinuxTitleButtonKind.MAXIMIZE_OR_RESTORE,
            LinuxTitleButtonKind.CLOSE
        )
        val leftCaptions = profile.leftButtons.filter { it in captionKinds }
        val rightCaptions = profile.rightButtons.filter { it in captionKinds }
        val leftOthers = profile.leftButtons.filter { it !in captionKinds }
        val rightOthers = profile.rightButtons.filter { it !in captionKinds }

        return when (placement) {
            TitleBarControlsPlacement.LEADING -> {
                if (leftCaptions.isNotEmpty() && rightCaptions.isEmpty()) {
                    profile
                } else {
                    val present = (leftCaptions + rightCaptions).toSet()
                    val moved = listOf(
                        LinuxTitleButtonKind.CLOSE,
                        LinuxTitleButtonKind.MINIMIZE,
                        LinuxTitleButtonKind.MAXIMIZE_OR_RESTORE
                    ).filter { it in present }.ifEmpty {
                        listOf(
                            LinuxTitleButtonKind.CLOSE,
                            LinuxTitleButtonKind.MINIMIZE,
                            LinuxTitleButtonKind.MAXIMIZE_OR_RESTORE
                        )
                    }
                    profile.copy(leftButtons = moved + leftOthers, rightButtons = rightOthers)
                }
            }
            TitleBarControlsPlacement.TRAILING -> {
                if (rightCaptions.isNotEmpty() && leftCaptions.isEmpty()) {
                    profile
                } else {
                    val present = (leftCaptions + rightCaptions).toSet()
                    val moved = listOf(
                        LinuxTitleButtonKind.MINIMIZE,
                        LinuxTitleButtonKind.MAXIMIZE_OR_RESTORE,
                        LinuxTitleButtonKind.CLOSE
                    ).filter { it in present }.ifEmpty {
                        listOf(
                            LinuxTitleButtonKind.MINIMIZE,
                            LinuxTitleButtonKind.MAXIMIZE_OR_RESTORE,
                            LinuxTitleButtonKind.CLOSE
                        )
                    }
                    profile.copy(leftButtons = leftOthers, rightButtons = rightOthers + moved)
                }
            }
            TitleBarControlsPlacement.PLATFORM_DEFAULT -> profile
        }
    }
}
