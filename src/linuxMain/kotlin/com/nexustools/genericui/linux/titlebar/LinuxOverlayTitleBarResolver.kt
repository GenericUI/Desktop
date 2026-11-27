package com.nexustools.genericui.linux.titlebar

import com.nexustools.genericui.core.AbstractComponent
import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.core.DesktopStageOverlaySpec
import com.nexustools.genericui.desktop.widget.DesktopButtonNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopCheckboxNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopDropdownNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopSelectNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopStyledContainerNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTextInputNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTextNativeImpl
import com.nexustools.genericui.enums.css.CssUnit
import com.nexustools.genericui.enums.window.TitleBarControlsPlacement
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.css.CssLength
import com.nexustools.genericui.style.css.StyledVisualSpec

/**
 * Shared Linux (GTK4 + KDE) resolver for [OverlayWindowTitleBar] theming, title text colors,
 * backdrop blur, rectangular undecorated corner radii, caption control lookup, and `OverlayHostBox`
 * stage overlay traversal.
 */
object LinuxOverlayTitleBarResolver {

    data class ResolvedOverlayTitleBarAppearance(
        val profile: LinuxTitleBarProfile,
        val backdropFilter: CssFilter?,
        val topCornerRadiusPx: Float,
        val opacity: Float,
        val isDark: Boolean
    )

    fun resolveThemeIsDark(
        windowTitleBar: DesktopNativeWidget,
        currentProfileIsDark: Boolean,
        contextIsDark: Boolean
    ): Boolean = when ((windowTitleBar.attributes["data-titlebar-theme"] as? String)?.trim()?.lowercase()) {
        "dark" -> true
        "light" -> false
        "auto" -> contextIsDark
        else -> currentProfileIsDark
    }

    fun resolveWidgetTextColorRgba(widget: DesktopNativeWidget?): RgbaColor? {
        if (widget == null) return null
        (widget.attributes["textColor"] as? RgbaColor)?.takeIf { it.a > 0 }?.let { return it }
        (widget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>)?.getEffectiveTextColor()
            ?.takeIf { it.a > 0 }?.let { return it }
        (widget.attributes["color"] as? RgbaColor)?.takeIf { it.a > 0 }?.let { return it }
        (widget.attributes["colorCss"] as? String)?.let { css ->
            RgbaColor.fromCss(css)?.takeIf { it.a > 0 }?.let { return it }
        }
        return null
    }

    fun hasExplicitZeroBackdropFilter(
        widget: DesktopNativeWidget,
        styledVisuals: StyledVisualSpec? = widget.attributes["styledVisuals"] as? StyledVisualSpec
    ): Boolean {
        if (widget.attributes["data-backdrop-blur"]?.toString()?.equals("false", ignoreCase = true) == true) {
            return true
        }
        val blurPx = styledVisuals?.backdropBlurPx
            ?: (widget.attributes["backdropBlurPx"] as? Number)?.toFloat()
            ?: (widget.attributes["data-backdrop-blur-px"] as? String)?.removeSuffix("px")?.trim()?.toFloatOrNull()
        if (blurPx != null && blurPx <= 0f) return true
        val rawFilter = styledVisuals?.backdropFilter
            ?: (widget.attributes["backdropFilter"] as? CssFilter)
        if (rawFilter != null && !rawFilter.hasActiveEffects) return true
        val rawFilterStr = widget.attributes["data-backdrop-filter"]?.toString()?.trim()
        if (rawFilterStr != null && (rawFilterStr.equals("none", ignoreCase = true) || rawFilterStr == "blur(0)" || rawFilterStr == "blur(0px)")) {
            return true
        }
        return false
    }

    fun resolveBackdropFilter(
        widget: DesktopNativeWidget,
        styledVisuals: StyledVisualSpec? = widget.attributes["styledVisuals"] as? StyledVisualSpec
    ): CssFilter? {
        if (hasExplicitZeroBackdropFilter(widget, styledVisuals)) return null
        val blurPx = styledVisuals?.backdropBlurPx
            ?: (widget.attributes["backdropBlurPx"] as? Number)?.toFloat()
            ?: (widget.attributes["data-backdrop-blur-px"] as? String)?.removeSuffix("px")?.trim()?.toFloatOrNull()
        val rawFilter = styledVisuals?.backdropFilter
            ?: (widget.attributes["backdropFilter"] as? CssFilter)
        return CssFilter.backdropBlurOrFilter(blurPx, rawFilter)
    }

    fun switchProfileTheme(
        profile: LinuxTitleBarProfile,
        targetIsDark: Boolean
    ): LinuxTitleBarProfile {
        if (profile.palette.isDark == targetIsDark) return profile
        val switched = when (profile.themeFamily) {
            LinuxTitleBarThemeFamily.YARU -> {
                val accentKey = LinuxThemePaletteCatalog.parseYaruVariant(profile.themeName)?.first ?: "default"
                LinuxThemePaletteCatalog.resolveYaruProfile(
                    themeName = profile.themeName,
                    accentKey = accentKey,
                    isDark = targetIsDark,
                    buttonLayout = null
                )
            }
            LinuxTitleBarThemeFamily.LIBADWAITA -> {
                LinuxThemePaletteCatalog.resolveLibadwaitaProfile(
                    isDark = targetIsDark,
                    accentKey = null,
                    portalAccent = profile.palette.accentColor,
                    buttonLayout = null
                )
            }
            LinuxTitleBarThemeFamily.BREEZE -> {
                LinuxThemePaletteCatalog.resolveBreezeProfile(
                    themeName = if (targetIsDark) "BreezeDark" else "Breeze",
                    leftButtons = profile.leftButtons,
                    rightButtons = profile.rightButtons,
                    kdeGlobalsSections = emptyMap(),
                    breezeRcSections = emptyMap(),
                    portalColorScheme = if (targetIsDark) 1 else 2,
                    portalAccent = profile.palette.accentColor,
                    isDarkHint = targetIsDark
                )
            }
            LinuxTitleBarThemeFamily.MINT_Y -> {
                LinuxThemePaletteCatalog.resolveMintYProfile(
                    themeName = profile.themeName,
                    accentKey = "default",
                    isDark = targetIsDark,
                    buttonLayout = null
                )
            }
            else -> {
                LinuxThemePaletteCatalog.resolveLibadwaitaProfile(
                    isDark = targetIsDark,
                    accentKey = null,
                    portalAccent = profile.palette.accentColor,
                    buttonLayout = null
                )
            }
        }
        return profile.copy(
            palette = switched.palette,
            xfwm4Assets = if (profile.palette.isDark == targetIsDark) profile.xfwm4Assets else null
        )
    }

    fun resolveAppearance(
        windowTitleBar: DesktopNativeWidget,
        titleLabelWidget: DesktopNativeWidget?,
        effectiveProfile: LinuxTitleBarProfile,
        contextIsDark: Boolean,
        isUndecoratedWindow: Boolean,
        isMaximized: Boolean
    ): ResolvedOverlayTitleBarAppearance {
        val targetIsDark = resolveThemeIsDark(
            windowTitleBar = windowTitleBar,
            currentProfileIsDark = effectiveProfile.palette.isDark,
            contextIsDark = contextIsDark
        )
        val themedProfile = switchProfileTheme(effectiveProfile, targetIsDark)

        val implVisuals = (windowTitleBar.attributes["nativeImpl"] as? DesktopStyledContainerNativeImpl<*>)?.getStyledVisuals()
        val styledVisuals = implVisuals ?: (windowTitleBar.attributes["styledVisuals"] as? StyledVisualSpec)
        val explicitBg = styledVisuals?.backgroundColor?.takeIf { it.a > 0 }
            ?: (windowTitleBar.attributes["backgroundColor"] as? RgbaColor)?.takeIf { it.a > 0 }

        var palette = themedProfile.palette
        if (explicitBg != null) {
            val inactiveAlpha = (explicitBg.a * 0.88f).toInt().coerceIn(1, 255)
            val inactiveBg = explicitBg.copy(a = inactiveAlpha)
            palette = palette.copy(
                activeBgTop = explicitBg,
                activeBgBottom = explicitBg,
                inactiveBgTop = inactiveBg,
                inactiveBgBottom = inactiveBg,
                activeBorderBottom = if (targetIsDark) RgbaColor(255, 255, 255, 34) else RgbaColor(0, 0, 0, 28),
                inactiveBorderBottom = if (targetIsDark) RgbaColor(255, 255, 255, 22) else RgbaColor(0, 0, 0, 18),
                topHighlight = if (targetIsDark) RgbaColor(255, 255, 255, 18) else RgbaColor(255, 255, 255, 96)
            )
        }

        val explicitTitleColor = resolveWidgetTextColorRgba(titleLabelWidget)
            ?: resolveWidgetTextColorRgba(windowTitleBar)
        if (explicitTitleColor != null) {
            val inactiveTitleAlpha = (explicitTitleColor.a * 0.72f).toInt().coerceIn(96, 255)
            palette = palette.copy(
                activeTitleText = explicitTitleColor,
                inactiveTitleText = explicitTitleColor.copy(a = inactiveTitleAlpha)
            )
        }

        val explicitRadius = styledVisuals?.borderRadiusPx
            ?: (windowTitleBar.attributes["borderRadiusPx"] as? Number)?.toFloat()
            ?: (windowTitleBar.attributes["data-corner-radius-px"] as? String)?.toFloatOrNull()
        val resolvedCornerRadius = when {
            isMaximized -> 0f
            explicitRadius != null -> explicitRadius.coerceAtLeast(0f)
            isUndecoratedWindow -> 0f
            else -> themedProfile.metrics.topCornerRadiusPx
        }

        val blurredFlag = windowTitleBar.attributes["data-blurred-vibrancy"]
            ?.toString()?.equals("false", ignoreCase = true) != true
        val explicitlyDisabledBackdrop = hasExplicitZeroBackdropFilter(windowTitleBar, styledVisuals)
        val explicitBackdropFilter = resolveBackdropFilter(windowTitleBar, styledVisuals)
        val effectiveBackdropFilter = when {
            !blurredFlag || explicitlyDisabledBackdrop -> null
            explicitBackdropFilter != null -> explicitBackdropFilter
            explicitBg == null || explicitBg.a < 255 -> CssFilter.blur(CssLength.px(12f))
            else -> null
        }

        val renderOpacity = when {
            explicitBg != null -> 1f
            effectiveBackdropFilter != null -> 0.86f
            else -> 0.94f
        }

        val resolvedProfile = themedProfile.copy(
            palette = palette,
            metrics = themedProfile.metrics.copy(topCornerRadiusPx = resolvedCornerRadius)
        )
        return ResolvedOverlayTitleBarAppearance(
            profile = resolvedProfile,
            backdropFilter = effectiveBackdropFilter,
            topCornerRadiusPx = resolvedCornerRadius,
            opacity = renderOpacity,
            isDark = targetIsDark
        )
    }

    fun isWindowControlWidget(widget: DesktopNativeWidget?): Boolean {
        if (widget == null) return false
        if (widget.attributes["data-window-control"] != null) return true
        if (widget.attributes["linuxTitleButtonKind"] != null) return true
        val role = (widget.attributes[DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE] as? String)?.lowercase()
        if (role != null && role.startsWith("window-") && role != DesktopStageOverlaySpec.ROLE_WINDOW_TITLE_BAR) {
            return true
        }
        val tokens = widget.cachedAllClassTokens
        return "gui-overlay-titlebar-btn" in tokens ||
            "gui-overlay-titlebar-close" in tokens ||
            "gui-overlay-titlebar-minimize" in tokens ||
            "gui-overlay-titlebar-maximize" in tokens
    }

    fun isInteractiveTitleBarControlOrAccessory(
        widget: DesktopNativeWidget?,
        titleBarWidget: DesktopNativeWidget
    ): Boolean {
        if (widget == null || widget === titleBarWidget) return false
        if (isWindowControlWidget(widget)) return true
        val impl = widget.attributes["nativeImpl"]
        if (impl is DesktopTextNativeImpl<*>) return false
        if (impl is DesktopButtonNativeImpl<*> ||
            impl is DesktopTextInputNativeImpl<*> ||
            impl is DesktopSelectNativeImpl<*> ||
            impl is DesktopCheckboxNativeImpl<*> ||
            impl is DesktopDropdownNativeImpl<*>
        ) {
            return true
        }
        val cursor = (widget.attributes["cursor"] as? String)?.trim()?.lowercase()
        return cursor == "pointer"
    }

    fun isPointOverTitleBarInteractive(
        titleBarWidget: DesktopNativeWidget,
        localX: Float,
        localY: Float
    ): Boolean {
        fun hitInteractive(node: DesktopNativeWidget, x: Float, y: Float): Boolean {
            if (!node.visible || node.disposed || node.isSelfPointerEventsNone()) return false
            for (child in node.children.asReversed()) {
                if (!child.visible || child.disposed || child.isSelfPointerEventsNone()) continue
                val b = child.bounds
                val lx = x - b.x
                val ly = y - b.y
                if (lx >= 0f && ly >= 0f && lx < b.width && ly < b.height) {
                    if (hitInteractive(child, lx, ly)) return true
                }
            }
            return isInteractiveTitleBarControlOrAccessory(node, titleBarWidget)
        }
        return hitInteractive(titleBarWidget, localX, localY)
    }

    fun isExpandingTitleBarComponent(widget: DesktopNativeWidget, comp: AbstractComponent? = null): Boolean {
        val attrWidth = (widget.attributes["styleWidth"] as? String)?.trim()?.lowercase()
        if (attrWidth == "100%") return true
        val impl = widget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
        val computed = impl?.getComputedStyle() ?: comp?.computedStyle
        if (computed?.width?.trim()?.lowercase() == "100%") return true
        if ((computed?.flexGrow ?: 0f) > 0f) return true
        val style = comp?.style ?: impl?.getComponentStyle()
        val wLen = style?.getWidth()
        if (wLen != null && wLen.unit == CssUnit.PERCENT && wLen.value >= 99.9f) return true
        if ((style?.getFlexGrow() ?: 0f) > 0f) return true
        val tokens = widget.cachedAllClassTokens
        return "w-100" in tokens || "flex-grow-1" in tokens || "flex-fill" in tokens
    }

    fun resolveOverlayControlsPlacement(windowTitleBar: DesktopNativeWidget): TitleBarControlsPlacement {
        val raw = (windowTitleBar.attributes["data-controls-placement"] as? String)?.trim()?.uppercase()
        return when (raw) {
            TitleBarControlsPlacement.LEADING.name -> TitleBarControlsPlacement.LEADING
            TitleBarControlsPlacement.TRAILING.name -> TitleBarControlsPlacement.TRAILING
            else -> LinuxTitleBarProfileManager.resolveTitleCapabilities().defaultControlsPlacement
        }
    }

    fun layoutOverlayTitleBarChildren(
        visibleChildren: List<DesktopBaseNativeImpl<*>>,
        windowTitleBar: DesktopNativeWidget,
        totalW: Int,
        totalH: Int
    ) {
        if (visibleChildren.isEmpty()) return
        val leadingSlot = visibleChildren.firstOrNull {
            (it.nativeWidget.attributes["id"] as? String) == "overlay-window-titlebar-leading" ||
                "gui-overlay-titlebar-leading" in it.nativeWidget.cachedAllClassTokens
        }
        val centerSlot = visibleChildren.firstOrNull {
            (it.nativeWidget.attributes["id"] as? String) == "overlay-window-titlebar-center" ||
                "gui-overlay-titlebar-center" in it.nativeWidget.cachedAllClassTokens
        }
        val trailingSlot = visibleChildren.firstOrNull {
            (it.nativeWidget.attributes["id"] as? String) == "overlay-window-titlebar-trailing" ||
                "gui-overlay-titlebar-trailing" in it.nativeWidget.cachedAllClassTokens
        }
        if (leadingSlot != null || trailingSlot != null) {
            val ch = totalH.coerceAtLeast(24)
            val padX = 8
            val trailW = if (trailingSlot != null) {
                trailingSlot.measureIntrinsicSize(totalW.toFloat(), ch.toFloat()).width.coerceIn(0, (totalW - padX * 2).coerceAtLeast(0))
            } else {
                0
            }
            val trailX = (totalW - padX - trailW).coerceAtLeast(padX)
            if (trailingSlot != null) {
                trailingSlot.setBounds(Rect(trailX, 0, trailW, ch))
                (trailingSlot as? DesktopBaseContainerNativeImpl<*>)?.layoutChildrenInBounds(trailW.toFloat(), ch.toFloat())
            }

            if (centerSlot != null) {
                val maxRightBeforeTrail = (trailX - if (trailW > 0) 8 else 0).coerceAtLeast(padX)
                val centerPrefW = centerSlot.measureIntrinsicSize(
                    (maxRightBeforeTrail - padX).coerceAtLeast(0).toFloat(),
                    ch.toFloat()
                ).width.coerceIn(0, (maxRightBeforeTrail - padX).coerceAtLeast(0))
                val leadMaxAvail = (maxRightBeforeTrail - centerPrefW - if (centerPrefW > 0) 8 else 0).coerceAtLeast(0)
                val leadPrefW = if (leadingSlot != null) {
                    if (isExpandingTitleBarComponent(leadingSlot.nativeWidget)) {
                        leadMaxAvail
                    } else {
                        leadingSlot.measureIntrinsicSize(totalW.toFloat(), ch.toFloat()).width
                            .coerceIn(0, (trailX - padX - 8).coerceAtLeast(0))
                    }
                } else {
                    0
                }
                if (leadingSlot != null) {
                    leadingSlot.setBounds(Rect(padX, 0, leadPrefW, ch))
                    (leadingSlot as? DesktopBaseContainerNativeImpl<*>)?.layoutChildrenInBounds(leadPrefW.toFloat(), ch.toFloat())
                }
                val minCenterX = padX + leadPrefW + if (leadPrefW > 0) 8 else 0
                val maxCenterRight = maxRightBeforeTrail.coerceAtLeast(minCenterX)
                val availCenterW = (maxCenterRight - minCenterX).coerceAtLeast(0)
                val clampedCenterW = centerPrefW.coerceIn(0, availCenterW)
                val centeredX = ((totalW - clampedCenterW) / 2).coerceIn(minCenterX, (maxCenterRight - clampedCenterW).coerceAtLeast(minCenterX))
                centerSlot.setBounds(Rect(centeredX, 0, clampedCenterW, ch))
                (centerSlot as? DesktopBaseContainerNativeImpl<*>)?.layoutChildrenInBounds(clampedCenterW.toFloat(), ch.toFloat())
            } else if (leadingSlot != null) {
                val leadW = (trailX - padX - if (trailW > 0) 8 else 0).coerceAtLeast(0)
                leadingSlot.setBounds(Rect(padX, 0, leadW, ch))
                (leadingSlot as? DesktopBaseContainerNativeImpl<*>)?.layoutChildrenInBounds(leadW.toFloat(), ch.toFloat())
            }
            return
        }

        val controlsGroup = visibleChildren.firstOrNull { child ->
            val w = child.nativeWidget
            (w.attributes["id"] as? String) == "overlay-window-controls" ||
                "gui-overlay-titlebar-controls" in w.cachedAllClassTokens ||
                (child is DesktopBaseContainerNativeImpl<*> && child.getLogicalChildren().any { sub ->
                    (sub.nativeWidget as? DesktopNativeWidget)?.attributes?.containsKey("data-window-control") == true
                })
        }
        val otherChildren = visibleChildren.filter { it !== controlsGroup }
        val placement = resolveOverlayControlsPlacement(windowTitleBar)
        val ch = totalH.coerceAtLeast(24)
        if (placement == TitleBarControlsPlacement.LEADING) {
            var leftCursor = 8
            if (controlsGroup != null) {
                val pref = controlsGroup.measureIntrinsicSize(totalW.toFloat(), totalH.toFloat())
                val cw = pref.width.coerceIn(48, (totalW / 2).coerceAtLeast(48))
                controlsGroup.setBounds(Rect(leftCursor, 0, cw, ch))
                (controlsGroup as? DesktopBaseContainerNativeImpl<*>)?.layoutChildrenInBounds(cw.toFloat(), ch.toFloat())
                leftCursor += cw + 8
            }
            var rightCursor = totalW - 8
            for (child in otherChildren.asReversed()) {
                val avail = (rightCursor - leftCursor).coerceAtLeast(24)
                val pref = child.measureIntrinsicSize(avail.toFloat(), totalH.toFloat())
                val cw = pref.width.coerceIn(24, avail)
                val cx = (rightCursor - cw).coerceAtLeast(leftCursor)
                child.setBounds(Rect(cx, 0, cw, ch))
                (child as? DesktopBaseContainerNativeImpl<*>)?.layoutChildrenInBounds(cw.toFloat(), ch.toFloat())
                rightCursor = (cx - 8).coerceAtLeast(leftCursor)
            }
        } else {
            var rightCursor = totalW - 8
            if (controlsGroup != null) {
                val pref = controlsGroup.measureIntrinsicSize(totalW.toFloat(), totalH.toFloat())
                val cw = pref.width.coerceIn(72, (totalW / 2).coerceAtLeast(72))
                val cx = (rightCursor - cw).coerceAtLeast(0)
                controlsGroup.setBounds(Rect(cx, 0, cw, ch))
                (controlsGroup as? DesktopBaseContainerNativeImpl<*>)?.layoutChildrenInBounds(cw.toFloat(), ch.toFloat())
                rightCursor = (cx - 8).coerceAtLeast(0)
            }
            for (child in otherChildren.asReversed()) {
                val pref = child.measureIntrinsicSize(rightCursor.toFloat(), totalH.toFloat())
                val cw = pref.width.coerceIn(24, rightCursor.coerceAtLeast(24))
                val cx = (rightCursor - cw).coerceAtLeast(0)
                child.setBounds(Rect(cx, 0, cw, ch))
                (child as? DesktopBaseContainerNativeImpl<*>)?.layoutChildrenInBounds(cw.toFloat(), ch.toFloat())
                rightCursor = (cx - 8).coerceAtLeast(0)
            }
        }
    }

    fun findStageOverlayInSubtree(node: DesktopNativeWidget): DesktopNativeWidget? {
        if (node.disposed || !node.visible) return null
        val isHostWrapper = node.attributes["data-overlay-host-root"] == "true"
        if (!isHostWrapper &&
            (node.stageOverlaySpec != null ||
                node.attributes["data-overlay-root"] == "true" ||
                "gui-overlay" in node.cachedAllClassTokens)
        ) {
            return node
        }
        for (child in node.children) {
            findStageOverlayInSubtree(child)?.let { return it }
        }
        return null
    }

    fun findStageClickWidget(surfaceWidget: DesktopNativeWidget): DesktopNativeWidget {
        val parent = surfaceWidget.parentWidget ?: return surfaceWidget
        var overlay: DesktopNativeWidget? = null
        for (sibling in parent.children) {
            if (sibling === surfaceWidget) continue
            overlay = findStageOverlayInSubtree(sibling)
            if (overlay != null) break
        }
        if (overlay == null) return surfaceWidget
        if (overlay.attributes["nativeImpl"] != null) {
            return overlay
        }
        val centerZone = findDescendantByRole(overlay, DesktopStageOverlaySpec.ROLE_CENTER_ZONE)
        val centerImpl = centerZone?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>
        if (centerZone != null && centerImpl != null && centerImpl.hasClickListener()) {
            return centerZone
        }
        return overlay
    }

    fun findDescendantByRole(root: DesktopNativeWidget, role: String): DesktopNativeWidget? {
        if (!root.visible || root.disposed) return null
        if (root.attributes[DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE] == role) return root
        for (child in root.children) {
            findDescendantByRole(child, role)?.let { return it }
        }
        return null
    }

    fun findWindowControlChild(root: DesktopNativeWidget, kind: String): DesktopNativeWidget? {
        val target = kind.lowercase()
        for (child in root.children) {
            if (child.disposed) continue
            val ctrlAttr = (child.attributes["data-window-control"] as? String)?.trim()?.lowercase()
            val roleAttr = (child.attributes[DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE] as? String)?.trim()?.lowercase()
            val idStr = (child.attributes["id"] as? String)?.trim()?.lowercase().orEmpty()
            val tokens = child.cachedAllClassTokens
            if (ctrlAttr == target ||
                roleAttr == "window-$target" ||
                "mdi-btn-$target" in tokens ||
                "gui-overlay-titlebar-$target" in tokens ||
                idStr == "overlay-window-$target-btn" ||
                idStr.contains("window-$target") ||
                idStr.contains("mdi-win-$target") ||
                idStr == target
            ) {
                return child
            }
            findWindowControlChild(child, target)?.let { return it }
        }
        return null
    }

    fun findTitleLabelWidget(windowTitleBar: DesktopNativeWidget): DesktopNativeWidget? {
        fun visitByIdOrClass(node: DesktopNativeWidget): DesktopNativeWidget? {
            for (child in node.children) {
                if (child.disposed) continue
                val idStr = child.attributes["id"] as? String
                if (idStr == "overlay-window-title-text" ||
                    "gui-overlay-window-title-text" in child.cachedAllClassTokens
                ) {
                    return child
                }
                visitByIdOrClass(child)?.let { return it }
            }
            return null
        }
        return visitByIdOrClass(windowTitleBar)
    }

    const val EDGE_LEFT: Int = 1
    const val EDGE_RIGHT: Int = 2
    const val EDGE_TOP: Int = 4
    const val EDGE_BOTTOM: Int = 8

    /**
     * Resolves an 8-direction undecorated resize edge bitmask (`LEFT/RIGHT/TOP/BOTTOM`) while
     * letting interactive child controls (`pointer` / `text` / window caption buttons) inside
     * the interior edge strip keep click priority, reserving the outermost [perimeterPx] border
     * exclusively for window resize.
     */
    fun computeResizeEdgeBitmask(
        width: Int,
        height: Int,
        x: Float,
        y: Float,
        borderPx: Float = 6f,
        hitOverInteractiveControl: Boolean = false,
        perimeterPx: Float = 2f
    ): Int {
        if (width <= 12 || height <= 12) return 0
        val onOuterPerimeter =
            x < perimeterPx || x >= (width - perimeterPx) || y < perimeterPx || y >= (height - perimeterPx)
        if (hitOverInteractiveControl && !onOuterPerimeter) return 0
        var mask = 0
        if (x >= 0f && x < borderPx) mask = mask or EDGE_LEFT
        else if (x <= width.toFloat() && x >= (width - borderPx)) mask = mask or EDGE_RIGHT
        if (y >= 0f && y < borderPx) mask = mask or EDGE_TOP
        else if (y <= height.toFloat() && y >= (height - borderPx)) mask = mask or EDGE_BOTTOM
        return mask
    }

    /**
     * Anchors a `Select` / `Dropdown` popup menu directly below [anchorBounds] within [viewport],
     * flipping above the anchor when there is insufficient room below.
     */
    fun computeAnchoredPopupRect(
        anchorBounds: Rect,
        popupWidthPx: Int,
        popupHeightPx: Int,
        viewport: Rect,
        gapPx: Int = 2,
        marginPx: Int = 2
    ): Rect {
        val clampedW = popupWidthPx.coerceAtMost((viewport.width - marginPx * 2).coerceAtLeast(40))
        val clampedH = popupHeightPx.coerceAtMost((viewport.height - marginPx * 2).coerceAtLeast(32))
        val minX = viewport.x + marginPx
        val maxX = (viewport.right - clampedW - marginPx).coerceAtLeast(minX)
        val px = anchorBounds.x.coerceIn(minX, maxX)
        val minY = viewport.y + marginPx
        val maxY = (viewport.bottom - clampedH - marginPx).coerceAtLeast(minY)
        val belowY = anchorBounds.bottom + gapPx
        val aboveY = anchorBounds.y - clampedH - gapPx
        val py = if (belowY + clampedH <= viewport.bottom - marginPx || aboveY < minY) {
            belowY.coerceIn(minY, maxY)
        } else {
            aboveY.coerceIn(minY, maxY)
        }
        return Rect(px, py, clampedW, clampedH)
    }
}
