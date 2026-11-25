package com.nexustools.genericui.linux.titlebar

import com.nexustools.genericui.component.shell.MdiWindow
import com.nexustools.genericui.component.shell.MdiWindowState
import com.nexustools.genericui.enums.window.TitleBarControlsPlacement
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.window.TitleBar

/**
 * Orchestrates Linux native-style headerbar layout, rendering, and pointer/button action dispatch
 * for in-viewport [MdiWindow] sub-windows using [LinuxTitleBarLayoutEngine] and [LinuxTitleBarRenderer].
 */
object LinuxMdiTitleBarHelper {

    data class DispatchOutcome(
        val handled: Boolean,
        val clickedButton: LinuxTitleButtonKind? = null,
        val startedDrag: Boolean = false,
        val toggledMaximizeByDoubleClick: Boolean = false
    )

    fun resolveEffectiveMdiProfile(
        profile: LinuxTitleBarProfile? = null,
        titleBar: TitleBar? = null,
        preferKdeStyle: Boolean = false,
        isDarkFallback: Boolean = false
    ): LinuxTitleBarProfile {
        val base = if (profile != null && profile.isReplicable) {
            profile
        } else {
            LinuxTitleBarProfileManager.resolveEmulatedMdiProfile(preferKdeStyle, isDarkFallback)
        }
        return applyPlacementOverride(base, titleBar)
    }

    fun computeMdiLayout(
        mdiWindow: MdiWindow,
        headerBarBounds: Rect,
        profile: LinuxTitleBarProfile? = null,
        preferKdeStyle: Boolean = false,
        isDarkFallback: Boolean = false,
        leadingWidthPx: Int? = null,
        centerWidthPx: Int? = null,
        trailingWidthPx: Int? = null
    ): LinuxTitleBarLayoutResult {
        val tb = mdiWindow.getTitleBar()
        val effectiveProfile = resolveEffectiveMdiProfile(profile, tb, preferKdeStyle, isDarkFallback)

        val resolvedHeight = when {
            headerBarBounds.height > 0 -> headerBarBounds.height
            tb?.heightPx != null -> tb.heightPx!!
            else -> effectiveProfile.metrics.compactHeightPx
        }
        val effectiveBounds = Rect(headerBarBounds.x, headerBarBounds.y, headerBarBounds.width, resolvedHeight)

        val showMin = mdiWindow.isMinimizable && (tb?.showMinimizeButton ?: true)
        val showMax = mdiWindow.isMaximizable && (tb?.showMaximizeButton ?: true)
        val showClose = mdiWindow.isClosable && (tb?.showCloseButton ?: true)

        val resolvedLeadingW = leadingWidthPx ?: estimateSlotWidth(tb?.leadingComponents)
        val resolvedCenterW = centerWidthPx ?: estimateSlotWidth(tb?.centerComponents)
        val resolvedTrailingW = trailingWidthPx ?: estimateSlotWidth(tb?.trailingComponents)

        return LinuxTitleBarLayoutEngine.computeLayout(
            profile = effectiveProfile,
            barBounds = effectiveBounds,
            isCompact = true,
            showMinimize = showMin,
            showMaximize = showMax,
            showClose = showClose,
            leadingWidthPx = resolvedLeadingW,
            centerWidthPx = resolvedCenterW,
            trailingWidthPx = resolvedTrailingW
        )
    }

    fun renderMdiHeaderBar(
        canvas: LinuxTitleBarCanvas,
        mdiWindow: MdiWindow,
        layout: LinuxTitleBarLayoutResult,
        profile: LinuxTitleBarProfile? = null,
        preferKdeStyle: Boolean = false,
        isDarkFallback: Boolean = false,
        hoveredButton: LinuxTitleButtonKind? = null,
        pressedButton: LinuxTitleButtonKind? = null,
        buttonStates: Set<LinuxTitleButtonKind> = emptySet(),
        opacity: Float = 1f
    ) {
        val tb = mdiWindow.getTitleBar()
        val effectiveProfile = resolveEffectiveMdiProfile(profile, tb, preferKdeStyle, isDarkFallback)
        val showTitle = tb?.showWindowTitle ?: true
        val titleText = if (showTitle) mdiWindow.title else ""
        val isMaximized = (mdiWindow.windowState == MdiWindowState.MAXIMIZED)

        LinuxTitleBarRenderer.render(
            canvas = canvas,
            profile = effectiveProfile,
            layout = layout,
            title = titleText,
            isActiveWindow = mdiWindow.isActiveWindow,
            isMaximized = isMaximized,
            hoveredButton = hoveredButton,
            pressedButton = pressedButton,
            buttonStates = buttonStates,
            opacity = opacity
        )
    }

    fun dispatchMdiHeaderAction(
        mdiWindow: MdiWindow,
        layout: LinuxTitleBarLayoutResult,
        x: Int,
        y: Int,
        isDoubleClick: Boolean = false,
        occupiedWidgetRects: List<Rect> = emptyList(),
        onShowWindowMenu: ((Int, Int) -> Unit)? = null
    ): DispatchOutcome {
        if (!layout.barBounds.contains(x, y)) {
            return DispatchOutcome(handled = false)
        }

        mdiWindow.activate()

        var startedDrag = false
        var toggledMaximizeByDoubleClick = false

        val actions = object : LinuxTitleBarWindowActions {
            override fun onClose() {
                mdiWindow.close()
            }

            override fun onMinimize() {
                mdiWindow.minimize()
            }

            override fun onMaximizeOrRestore() {
                if (!mdiWindow.isMaximizable) return
                if (mdiWindow.windowState == MdiWindowState.MAXIMIZED) {
                    mdiWindow.restore()
                } else {
                    mdiWindow.maximize()
                }
            }

            override fun onShowWindowMenu(screenX: Int, screenY: Int) {
                onShowWindowMenu?.invoke(screenX, screenY)
            }

            override fun onToggleKeepAbove() {}
            override fun onToggleKeepBelow() {}
            override fun onToggleAllDesktops() {}
            override fun onToggleShade() {}

            override fun onStartSystemMove() {
                if (mdiWindow.isDraggable && mdiWindow.windowState == MdiWindowState.NORMAL) {
                    startedDrag = true
                }
            }

            override fun onDoubleClickMaximize() {
                if (mdiWindow.isMaximizable) {
                    onMaximizeOrRestore()
                    toggledMaximizeByDoubleClick = true
                }
            }
        }

        val hit = LinuxTitleBarLayoutEngine.hitTestButton(layout, x, y)
        if (hit != null) {
            val handled = if (!isDoubleClick) {
                LinuxTitleBarLayoutEngine.dispatchButtonAction(hit.kind, actions, x, y)
            } else {
                false
            }
            return DispatchOutcome(
                handled = handled,
                clickedButton = if (handled) hit.kind else null
            )
        }

        if (LinuxTitleBarLayoutEngine.isDraggableBackground(layout, x, y, occupiedWidgetRects)) {
            if (isDoubleClick) {
                actions.onDoubleClickMaximize()
            } else {
                actions.onStartSystemMove()
            }
            return DispatchOutcome(
                handled = startedDrag || toggledMaximizeByDoubleClick,
                startedDrag = startedDrag,
                toggledMaximizeByDoubleClick = toggledMaximizeByDoubleClick
            )
        }

        return DispatchOutcome(handled = false)
    }

    private fun applyPlacementOverride(
        profile: LinuxTitleBarProfile,
        titleBar: TitleBar?
    ): LinuxTitleBarProfile {
        val placement = titleBar?.controlsPlacement ?: TitleBarControlsPlacement.PLATFORM_DEFAULT
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
                    val moved = (leftCaptions + rightCaptions).ifEmpty {
                        listOf(LinuxTitleButtonKind.CLOSE, LinuxTitleButtonKind.MINIMIZE, LinuxTitleButtonKind.MAXIMIZE_OR_RESTORE)
                    }
                    profile.copy(
                        leftButtons = moved + leftOthers,
                        rightButtons = rightOthers
                    )
                }
            }
            TitleBarControlsPlacement.TRAILING -> {
                if (rightCaptions.isNotEmpty() && leftCaptions.isEmpty()) {
                    profile
                } else {
                    val moved = (leftCaptions + rightCaptions).ifEmpty {
                        listOf(LinuxTitleButtonKind.MINIMIZE, LinuxTitleButtonKind.MAXIMIZE_OR_RESTORE, LinuxTitleButtonKind.CLOSE)
                    }
                    profile.copy(
                        leftButtons = leftOthers,
                        rightButtons = rightOthers + moved
                    )
                }
            }
            TitleBarControlsPlacement.PLATFORM_DEFAULT -> profile
        }
    }

    private fun estimateSlotWidth(components: List<com.nexustools.genericui.core.AbstractComponent>?): Int {
        if (components.isNullOrEmpty()) return 0
        var total = 0
        var visibleCount = 0
        for (c in components) {
            if (!c.isVisible) continue
            if (visibleCount > 0) total += 4
            total += (c.widthPx ?: 28).coerceAtLeast(0)
            visibleCount++
        }
        return total
    }
}
