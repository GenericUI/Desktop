@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.enums.css.CssBorderStyle
import com.nexustools.genericui.enums.css.CssCursor
import com.nexustools.genericui.event.InputFocusManager
import com.nexustools.genericui.event.KeyEvent
import com.nexustools.genericui.event.PointerDragManager
import com.nexustools.genericui.event.PointerEvent
import com.nexustools.genericui.event.PopupMouseCaptureManager
import com.nexustools.genericui.event.WheelEvent
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.linux.style.LinuxBackdropFilterController
import com.nexustools.genericui.linux.style.LinuxStyledSurfaceHelper
import com.nexustools.genericui.linux.titlebar.LinuxTitleBarCanvas
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssCornerRadii
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssCustomCursor
import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.css.StyledVisualSpec
import com.nexustools.genericui.style.gradient.Gradient.Gradient
import com.nexustools.genericui.style.gradient.Gradient.LinearGradient
import com.nexustools.genericui.style.gradient.Gradient.RadialGradient
import com.nexustools.genericui.system.style.SemanticSurfacePaintSpec
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Shared KDE `QPainter` surface rendering, preceding-sibling `QWidget::render` backdrop capture for
 * all 10 CSS `backdrop-filter` ops, `LinuxTitleBarCanvas` implementation, and Qt-to-GenericUI input
 * event translation.
 */
internal object KdePainterSupport {

    fun paintStyledSurface(
        widgetPtr: COpaquePointer,
        painterPtr: COpaquePointer,
        widthPx: Int,
        heightPx: Int,
        spec: StyledVisualSpec?,
        radii: CssCornerRadii?,
        gradientOverride: Gradient? = null,
        backdropFilterOverride: CssFilter? = null,
        focused: Boolean = false,
        opacity: Float = 1f,
        isPressed: Boolean = false,
        isHovered: Boolean = false,
        defaultBgFallback: RgbaColor? = null
    ) {
        val w = widthPx.toDouble()
        val h = heightPx.toDouble()
        if (w <= 0.0 || h <= 0.0 || opacity <= 0f) return

        val cornerRadii = LinuxStyledSurfaceHelper.resolveClampedCornerRadii(
            radii = radii,
            fallbackRadiusPx = spec?.borderRadiusPx,
            widthPx = widthPx.toFloat(),
            heightPx = heightPx.toFloat()
        )
        val tl = cornerRadii[0].toDouble()
        val tr = cornerRadii[1].toDouble()
        val br = cornerRadii[2].toDouble()
        val bl = cornerRadii[3].toDouble()
        val uniformRadius = (cornerRadii[0] + cornerRadii[1] + cornerRadii[2] + cornerRadii[3]) / 4f

        KdeDynamicBindings.painterSave(painterPtr)
        if (opacity < 1f) {
            KdeDynamicBindings.painterSetOpacity(painterPtr, opacity)
        }

        try {
            // 1. Real CSS backdrop-filter (capture behind widget -> apply 10 ops + blur -> draw clipped)
            val effectiveBackdrop = CssFilter.backdropBlurOrFilter(
                spec?.backdropBlurPx,
                backdropFilterOverride ?: spec?.backdropFilter
            )
            if (LinuxBackdropFilterController.shared.hasActiveEffects(effectiveBackdrop)) {
                renderFilteredBackdrop(
                    widgetPtr = widgetPtr,
                    painterPtr = painterPtr,
                    widthPx = widthPx,
                    heightPx = heightPx,
                    tl = tl, tr = tr, br = br, bl = bl,
                    uniformRadius = uniformRadius,
                    filter = effectiveBackdrop!!,
                    opacity = opacity
                )
            }

            // 2. Box shadow accent glow inside surface margin/perimeter
            val shadowColor = spec?.boxShadowColor
            val shadowBlur = spec?.boxShadowBlurPx ?: 0f
            if (shadowColor != null && shadowColor.a > 0 && shadowBlur > 0f) {
                KdeDynamicBindings.withQPainterPath { shadowPath ->
                    KdeDynamicBindings.pathAddRoundedRect(shadowPath, 1.0, 1.0, (w - 2.0).coerceAtLeast(1.0), (h - 2.0).coerceAtLeast(1.0), tl, tr, br, bl)
                    KdeDynamicBindings.painterStrokePath(
                        painterPtr,
                        shadowPath,
                        shadowColor.copy(a = (shadowColor.a * 0.45f).roundToInt().coerceIn(0, 255)),
                        minOf(shadowBlur, 6f).toDouble(),
                        KdeDynamicBindings.PEN_SOLID_LINE
                    )
                }
            }

            // 3. Background fill (gradient or solid color, respecting hover/active state if subtle tint)
            val grad = gradientOverride ?: spec?.backgroundGradient
            val bgColor = spec?.backgroundColor ?: defaultBgFallback
            KdeDynamicBindings.withQPainterPath { fillPath ->
                KdeDynamicBindings.pathAddRoundedRect(fillPath, 0.0, 0.0, w, h, tl, tr, br, bl)
                when (grad) {
                    is LinearGradient -> {
                        val norm = LinuxStyledSurfaceHelper.normalizeLinearGradient(grad, widthPx.toFloat(), heightPx.toFloat())
                        val stops = norm.positions.indices.map { i ->
                            norm.positions[i].toDouble() to tintInteractiveColor(norm.colors[i], isHovered, isPressed)
                        }
                        KdeDynamicBindings.painterFillLinearGradientPath(
                            painterPtr,
                            fillPath,
                            norm.x1.toDouble(),
                            norm.y1.toDouble(),
                            norm.x2.toDouble(),
                            norm.y2.toDouble(),
                            stops
                        )
                    }
                    is RadialGradient -> {
                        val norm = LinuxStyledSurfaceHelper.normalizeRadialGradient(grad, widthPx.toFloat(), heightPx.toFloat())
                        val stops = norm.positions.indices.map { i ->
                            norm.positions[i].toDouble() to tintInteractiveColor(norm.colors[i], isHovered, isPressed)
                        }
                        val rad = max(norm.radiusX, norm.radiusY).coerceAtLeast(1f).toDouble()
                        KdeDynamicBindings.painterFillRadialGradientPath(
                            painterPtr,
                            fillPath,
                            norm.cx.toDouble(),
                            norm.cy.toDouble(),
                            rad,
                            stops
                        )
                    }
                    else -> {
                        if (bgColor != null && bgColor.a > 0) {
                            KdeDynamicBindings.painterFillPath(
                                painterPtr,
                                fillPath,
                                tintInteractiveColor(bgColor, isHovered, isPressed)
                            )
                        }
                    }
                }
            }

            // 4. Border (solid, dashed, dotted, or double)
            val bw = spec?.borderWidthPx ?: 0f
            val borderStyle = spec?.borderStyle
            val borderColor = spec?.borderColor
            if (borderColor != null && borderColor.a > 0 && LinuxStyledSurfaceHelper.isVisibleBorder(borderStyle, bw)) {
                if (LinuxStyledSurfaceHelper.isDoubleBorder(borderStyle, bw)) {
                    val metrics = LinuxStyledSurfaceHelper.resolveDoubleBorderMetrics(bw)
                    val lineW = metrics[0].toDouble()
                    val outerInset = metrics[1].toDouble()
                    val innerInset = metrics[2].toDouble()
                    paintBorderLoop(painterPtr, w, h, outerInset, tl, tr, br, bl, borderColor, lineW, KdeDynamicBindings.PEN_SOLID_LINE)
                    paintBorderLoop(painterPtr, w, h, innerInset, tl, tr, br, bl, borderColor, lineW, KdeDynamicBindings.PEN_SOLID_LINE)
                } else {
                    val penStyle = when (borderStyle) {
                        CssBorderStyle.DASHED -> KdeDynamicBindings.PEN_DASH_LINE
                        CssBorderStyle.DOTTED -> KdeDynamicBindings.PEN_DOT_LINE
                        else -> KdeDynamicBindings.PEN_SOLID_LINE
                    }
                    val inset = bw.toDouble() / 2.0
                    paintBorderLoop(painterPtr, w, h, inset, tl, tr, br, bl, borderColor, bw.toDouble(), penStyle)
                }
            }

            // 5. Focus ring
            val focusCol = spec?.focusRingColor
            val focusW = spec?.focusRingWidthPx ?: 0f
            if (focused && focusCol != null && focusCol.a > 0 && focusW > 0f) {
                val inset = (focusW / 2f).toDouble()
                paintBorderLoop(painterPtr, w, h, inset, tl, tr, br, bl, focusCol, focusW.toDouble(), KdeDynamicBindings.PEN_SOLID_LINE)
            }
        } finally {
            KdeDynamicBindings.painterRestore(painterPtr)
        }
    }

    fun paintSemanticSurface(
        painterPtr: COpaquePointer,
        widthPx: Int,
        heightPx: Int,
        spec: SemanticSurfacePaintSpec,
        opacity: Float = 1f
    ) {
        val w = widthPx.toDouble()
        val h = heightPx.toDouble()
        if (w <= 0.0 || h <= 0.0 || opacity <= 0f) return
        val r = LinuxStyledSurfaceHelper.clampCornerRadius(
            spec.borderRadiusPx ?: 6f,
            widthPx.toFloat(),
            heightPx.toFloat()
        ).toDouble()

        KdeDynamicBindings.painterSave(painterPtr)
        if (opacity < 1f) KdeDynamicBindings.painterSetOpacity(painterPtr, opacity)
        try {
            KdeDynamicBindings.withQPainterPath { fillPath ->
                KdeDynamicBindings.pathAddRoundedRect(fillPath, 0.0, 0.0, w, h, r, r, r, r)
                val bg = spec.backgroundColor
                if (bg != null && bg.a > 0) {
                    KdeDynamicBindings.painterFillPath(painterPtr, fillPath, bg)
                }
            }
            val bw = spec.borderWidthPx ?: 1f
            val bc = spec.borderColor
            if (bc != null && bc.a > 0 && bw > 0f) {
                paintBorderLoop(painterPtr, w, h, bw.toDouble() / 2.0, r, r, r, r, bc, bw.toDouble(), KdeDynamicBindings.PEN_SOLID_LINE)
            }
        } finally {
            KdeDynamicBindings.painterRestore(painterPtr)
        }
    }

    private fun paintBorderLoop(
        painterPtr: COpaquePointer,
        w: Double,
        h: Double,
        inset: Double,
        tl: Double,
        tr: Double,
        br: Double,
        bl: Double,
        color: RgbaColor,
        strokeWidth: Double,
        penStyle: Int
    ) {
        val bw = (w - inset * 2.0).coerceAtLeast(0.5)
        val bh = (h - inset * 2.0).coerceAtLeast(0.5)
        KdeDynamicBindings.withQPainterPath { bPath ->
            KdeDynamicBindings.pathAddRoundedRect(
                bPath,
                inset,
                inset,
                bw,
                bh,
                (tl - inset).coerceAtLeast(0.0),
                (tr - inset).coerceAtLeast(0.0),
                (br - inset).coerceAtLeast(0.0),
                (bl - inset).coerceAtLeast(0.0)
            )
            KdeDynamicBindings.painterStrokePath(painterPtr, bPath, color, strokeWidth, penStyle)
        }
    }

    private fun tintInteractiveColor(c: RgbaColor, hovered: Boolean, pressed: Boolean): RgbaColor {
        if (!hovered && !pressed) return c
        val delta = when {
            pressed -> -18
            hovered -> -8
            else -> 0
        }
        return RgbaColor(
            r = (c.r + delta).coerceIn(0, 255),
            g = (c.g + delta).coerceIn(0, 255),
            b = (c.b + delta).coerceIn(0, 255),
            a = c.a
        )
    }

    private fun renderFilteredBackdrop(
        widgetPtr: COpaquePointer,
        painterPtr: COpaquePointer,
        widthPx: Int,
        heightPx: Int,
        tl: Double,
        tr: Double,
        br: Double,
        bl: Double,
        uniformRadius: Float,
        filter: CssFilter,
        opacity: Float
    ) {
        if (widthPx <= 0 || heightPx <= 0) return

        // Walk ancestor chain from target up to root window
        val path = ArrayList<COpaquePointer>(8)
        var cur: COpaquePointer? = widgetPtr
        while (cur != null) {
            path.add(cur)
            cur = KdeDynamicBindings.getQObjectParentPtr(cur)
        }
        if (path.size < 2) return
        path.reverse() // [rootWindow, ..., parent, widgetPtr]

        // Compute target origin relative to rootWindow
        var offsetX = 0
        var offsetY = 0
        for (i in 1 until path.size) {
            offsetX += KdeDynamicBindings.getWidgetX(path[i])
            offsetY += KdeDynamicBindings.getWidgetY(path[i])
        }

        val pads = LinuxBackdropFilterController.computeHaloPadding(filter)
        val padLeft = pads[0]
        val padTop = pads[1]
        val padRight = pads[2]
        val padBottom = pads[3]
        val rootW = KdeDynamicBindings.getWidgetWidth(path[0]).coerceAtLeast(offsetX + widthPx)
        val rootH = KdeDynamicBindings.getWidgetHeight(path[0]).coerceAtLeast(offsetY + heightPx)

        val capX0 = (offsetX - padLeft).coerceIn(0, rootW)
        val capY0 = (offsetY - padTop).coerceIn(0, rootH)
        val capX1 = (offsetX + widthPx + padRight).coerceIn(capX0 + 1, max(capX0 + 1, rootW))
        val capY1 = (offsetY + heightPx + padBottom).coerceIn(capY0 + 1, max(capY0 + 1, rootH))
        val capW = (capX1 - capX0).coerceAtLeast(1)
        val capH = (capY1 - capY0).coerceAtLeast(1)

        val capImage = KdeDynamicBindings.createQImage(capW, capH, KdeDynamicBindings.IMAGE_FORMAT_ARGB32_PREMULTIPLIED)
        try {
            KdeDynamicBindings.fillQImage(capImage, 0x00000000u)
            KdeDynamicBindings.withQPainter(capImage) { capPainter ->
                fun renderAncestorsAndPrecedingSiblings(depth: Int, nodeGlobalX: Int, nodeGlobalY: Int) {
                    val node = path[depth]
                    val nodeW = KdeDynamicBindings.getWidgetWidth(node)
                    val nodeH = KdeDynamicBindings.getWidgetHeight(node)
                    if (nodeW <= 0 || nodeH <= 0) return

                    // Intersection of capture rect [capX0..capX1, capY0..capY1] with node bounds
                    val ix0 = max(capX0, nodeGlobalX)
                    val iy0 = max(capY0, nodeGlobalY)
                    val ix1 = min(capX1, nodeGlobalX + nodeW)
                    val iy1 = min(capY1, nodeGlobalY + nodeH)
                    if (ix1 > ix0 && iy1 > iy0) {
                        KdeDynamicBindings.renderWidgetIntoPainter(
                            widget = node,
                            painter = capPainter,
                            targetOffsetX = ix0 - capX0,
                            targetOffsetY = iy0 - capY0,
                            srcX = ix0 - nodeGlobalX,
                            srcY = iy0 - nodeGlobalY,
                            srcW = ix1 - ix0,
                            srcH = iy1 - iy0,
                            renderFlags = KdeDynamicBindings.RENDER_DRAW_WINDOW_BACKGROUND
                        )
                    }

                    if (depth + 1 >= path.size) return
                    val nextOnPath = path[depth + 1]
                    val children = KdeDynamicBindings.getQObjectChildPointers(node)
                    for (child in children) {
                        if (child.rawValue == nextOnPath.rawValue) break
                        if (!KdeDynamicBindings.isWidgetVisible(child)) continue
                        val cw = KdeDynamicBindings.getWidgetWidth(child)
                        val ch = KdeDynamicBindings.getWidgetHeight(child)
                        if (cw <= 0 || ch <= 0) continue
                        val cgx = nodeGlobalX + KdeDynamicBindings.getWidgetX(child)
                        val cgy = nodeGlobalY + KdeDynamicBindings.getWidgetY(child)
                        val sx0 = max(capX0, cgx)
                        val sy0 = max(capY0, cgy)
                        val sx1 = min(capX1, cgx + cw)
                        val sy1 = min(capY1, cgy + ch)
                        if (sx1 > sx0 && sy1 > sy0) {
                            KdeDynamicBindings.renderWidgetIntoPainter(
                                widget = child,
                                painter = capPainter,
                                targetOffsetX = sx0 - capX0,
                                targetOffsetY = sy0 - capY0,
                                srcX = sx0 - cgx,
                                srcY = sy0 - cgy,
                                srcW = sx1 - sx0,
                                srcH = sy1 - sy0,
                                renderFlags = KdeDynamicBindings.RENDER_DRAW_WINDOW_BACKGROUND or KdeDynamicBindings.RENDER_DRAW_CHILDREN
                            )
                        }
                    }

                    val nextGx = nodeGlobalX + KdeDynamicBindings.getWidgetX(nextOnPath)
                    val nextGy = nodeGlobalY + KdeDynamicBindings.getWidgetY(nextOnPath)
                    renderAncestorsAndPrecedingSiblings(depth + 1, nextGx, nextGy)
                }

                renderAncestorsAndPrecedingSiblings(0, 0, 0)
            }

            val bitsPtr = KdeDynamicBindings.qImageBits(capImage)?.reinterpret<ByteVar>() ?: return
            val stride = KdeDynamicBindings.qImageBytesPerLine(capImage).takeIf { it > 0 } ?: (capW * 4)
            val regionX = (offsetX - capX0).coerceIn(0, capW)
            val regionY = (offsetY - capY0).coerceIn(0, capH)
            val regionW = min(widthPx, capW - regionX).coerceAtLeast(1)
            val regionH = min(heightPx, capH - regionY).coerceAtLeast(1)

            val applied = LinuxBackdropFilterController.shared.applyToPremulBgraPointer(
                filter = filter,
                dataPtr = bitsPtr,
                strideBytes = stride,
                bufferWidth = capW,
                bufferHeight = capH,
                regionX = regionX,
                regionY = regionY,
                regionWidth = regionW,
                regionHeight = regionH,
                cornerRadiusPx = uniformRadius,
                componentOpacity = opacity
            )

            if (applied) {
                KdeDynamicBindings.painterSave(painterPtr)
                KdeDynamicBindings.withQPainterPath { clipPath ->
                    KdeDynamicBindings.pathAddRoundedRect(clipPath, 0.0, 0.0, widthPx.toDouble(), heightPx.toDouble(), tl, tr, br, bl)
                    KdeDynamicBindings.painterSetClipPath(painterPtr, clipPath, KdeDynamicBindings.CLIP_INTERSECT)
                }
                KdeDynamicBindings.painterDrawImageRect(
                    painterPtr,
                    0.0,
                    0.0,
                    regionW.toDouble(),
                    regionH.toDouble(),
                    capImage,
                    regionX.toDouble(),
                    regionY.toDouble(),
                    regionW.toDouble(),
                    regionH.toDouble()
                )
                KdeDynamicBindings.painterRestore(painterPtr)
            }
        } finally {
            KdeDynamicBindings.destroyQImage(capImage)
        }
    }

    fun handleStyledBackdropAttribute(
        impl: DesktopBaseNativeImpl<KdeWidgetHandle>,
        name: String,
        value: String?
    ) {
        val attrs = impl.nativeWidget.attributes
        when {
            name.equals("backdrop-filter", ignoreCase = true) ||
                name.equals("-webkit-backdrop-filter", ignoreCase = true) ||
                name.equals("backdropFilter", ignoreCase = true) -> {
                val parsed = value?.let { CssFilter.parse(it) }
                if (parsed != null) {
                    attrs["attrBackdropFilter"] = parsed
                } else {
                    attrs.remove("attrBackdropFilter")
                }
                impl.nativeWidget.requestRepaint()
            }
            name.equals("backdropBlurPx", ignoreCase = true) -> {
                val parsed = value?.toFloatOrNull()
                if (parsed != null) {
                    attrs["attrBackdropBlurPx"] = parsed
                } else {
                    attrs.remove("attrBackdropBlurPx")
                }
                impl.nativeWidget.requestRepaint()
            }
        }
    }

    fun resolveWidgetBackdropFilter(
        handle: KdeWidgetHandle,
        spec: StyledVisualSpec
    ): CssFilter? {
        val attrFilter = (handle.attributes["attrBackdropFilter"] as? CssFilter)
            ?: (handle.attributes["backdropFilter"] as? CssFilter)
            ?: spec.backdropFilter
        val attrBlurPx = (handle.attributes["attrBackdropBlurPx"] as? Float)
            ?: spec.backdropBlurPx
        return CssFilter.backdropBlurOrFilter(attrBlurPx, attrFilter)
    }

    // --- Input Event Wiring ---
    fun attachStandardInputHooks(
        widgetPtr: COpaquePointer,
        impl: DesktopBaseNativeImpl<KdeWidgetHandle>,
        hookMask: Int,
        dispatchLeftClickOnRelease: Boolean = true,
        onPaint: ((COpaquePointer, COpaquePointer, Int, Int) -> Unit)? = null,
        onSizeHint: ((COpaquePointer) -> Pair<Int, Int>?)? = null,
        onHoverChanged: ((Boolean) -> Unit)? = null,
        onPressedChanged: ((Boolean) -> Unit)? = null,
        onClose: ((COpaquePointer) -> Boolean)? = null
    ) {
        var pressStartedInside = false
        KdeVTableBridge.attachWidgetHooks(widgetPtr, hookMask or KdeVTableBridge.HOOK_INPUT) {
            if (onPaint != null) {
                this.onPaint = onPaint
            }
            if (onSizeHint != null) {
                this.onSizeHint = onSizeHint
            }
            if (onClose != null) {
                this.onClose = onClose
            }
            this.onMousePress = { wPtr, info ->
                val pEv = toPointerEvent(info)
                val popupConsumed = PopupMouseCaptureManager.interceptPointerDown(pEv.screenX, pEv.screenY, impl.nativeWidget)
                if (popupConsumed) {
                    pressStartedInside = false
                    true
                } else {
                    if (info.button == 1) {
                        pressStartedInside = true
                        onPressedChanged?.invoke(true)
                    }
                    impl.dispatchPointerDown(pEv)
                    if (info.button == 2 /*Qt::RightButton*/) {
                        impl.dispatchContextMenu(pEv)
                    }
                    false
                }
            }
            this.onMouseMove = { _, info ->
                val pEv = toPointerEvent(info)
                PointerDragManager.notifyGlobalPointerMove(pEv)
                impl.dispatchPointerMove(pEv)
                false
            }
            this.onMouseRelease = { wPtr, info ->
                val pEv = toPointerEvent(info)
                PointerDragManager.notifyGlobalPointerUp(pEv)
                val popupConsumed = PopupMouseCaptureManager.interceptPointerUp(pEv.screenX, pEv.screenY, impl.nativeWidget)
                val wasPressed = pressStartedInside
                pressStartedInside = false
                if (info.button == 1) onPressedChanged?.invoke(false)
                if (popupConsumed) {
                    true
                } else {
                    impl.dispatchPointerUp(pEv)
                    if (dispatchLeftClickOnRelease && wasPressed && info.button == 1) {
                        val w = KdeDynamicBindings.getWidgetWidth(wPtr)
                        val h = KdeDynamicBindings.getWidgetHeight(wPtr)
                        if (info.localX in 0f..w.toFloat() && info.localY in 0f..h.toFloat()) {
                            val clickSwallowed = PopupMouseCaptureManager.interceptClick(pEv.screenX, pEv.screenY, impl.nativeWidget)
                            if (!clickSwallowed) {
                                impl.dispatchClick()
                            }
                        }
                    }
                    false
                }
            }
            this.onMouseDoubleClick = { _, info ->
                val pEv = toPointerEvent(info)
                impl.dispatchPointerDown(pEv)
                if (info.button == 1) impl.dispatchDoubleClick()
                false
            }
            this.onWheel = { _, info ->
                val wEv = WheelEvent(
                    x = info.localX,
                    y = info.localY,
                    deltaX = info.deltaX,
                    deltaY = info.deltaY,
                    shiftKey = (info.modifiers and 0x02000000) != 0,
                    ctrlKey = (info.modifiers and 0x04000000) != 0,
                    altKey = (info.modifiers and 0x08000000) != 0,
                    metaKey = (info.modifiers and 0x10000000) != 0
                )
                impl.dispatchWheel(wEv)
                false
            }
            this.onEnter = { _ ->
                onHoverChanged?.invoke(true)
                impl.dispatchPointerEnter(PointerEvent())
            }
            this.onLeave = { _ ->
                onHoverChanged?.invoke(false)
                impl.dispatchPointerLeave(PointerEvent())
            }
            this.onKeyPress = { _, info ->
                impl.dispatchKeyDown(toKeyEvent(info, impl))
                false
            }
            this.onKeyRelease = { _, info ->
                impl.dispatchKeyUp(toKeyEvent(info, impl))
                false
            }
            this.onFocusIn = { _ ->
                if (impl.nativeWidget.attributes["isTextInputComponent"] == true) {
                    InputFocusManager.setEditableInputFocused(true)
                }
                if (impl.nativeWidget.isEffectivelyFocusable()) {
                    impl.setFocusedState(true)
                }
            }
            this.onFocusOut = { _ ->
                if (impl.nativeWidget.attributes["isTextInputComponent"] == true) {
                    InputFocusManager.setEditableInputFocused(false)
                }
                impl.setFocusedState(false)
            }
        }
    }

    fun syncWidgetFocus(
        impl: DesktopBaseNativeImpl<KdeWidgetHandle>,
        focused: Boolean,
        targetPtr: COpaquePointer? = impl.nativeWidget.qWidgetPtr
    ) {
        val ptr = targetPtr ?: return
        val effective = focused && impl.nativeWidget.enabled && impl.isFocusable() && impl.nativeWidget.isEffectivelyFocusable()
        if (effective) {
            if (!KdeDynamicBindings.hasWidgetFocus(ptr)) {
                KdeDynamicBindings.setWidgetFocus(ptr, true)
            }
        } else if (KdeDynamicBindings.hasWidgetFocus(ptr)) {
            KdeDynamicBindings.setWidgetFocus(ptr, false)
        }
    }

    fun toPointerEvent(info: KdeVTableBridge.MouseEventInfo): PointerEvent {
        val button = when (info.button) {
            1 -> 0
            2 -> 2
            4 -> 1
            else -> 0
        }
        return PointerEvent(
            x = info.localX,
            y = info.localY,
            screenX = info.globalX,
            screenY = info.globalY,
            button = button,
            buttonsMask = info.buttonsMask,
            shiftKey = (info.modifiers and 0x02000000) != 0,
            ctrlKey = (info.modifiers and 0x04000000) != 0,
            altKey = (info.modifiers and 0x08000000) != 0,
            metaKey = (info.modifiers and 0x10000000) != 0
        )
    }

    fun toKeyEvent(info: KdeVTableBridge.KeyEventInfo, impl: DesktopBaseNativeImpl<*>?): KeyEvent {
        val keyStr = mapQtKeyToName(info.qtKey, info.text)
        return KeyEvent(
            key = keyStr,
            code = keyStr,
            keyCode = info.qtKey,
            shiftKey = (info.modifiers and 0x02000000) != 0,
            ctrlKey = (info.modifiers and 0x04000000) != 0,
            altKey = (info.modifiers and 0x08000000) != 0,
            metaKey = (info.modifiers and 0x10000000) != 0,
            isTargetTextInput = impl?.nativeWidget?.attributes?.get("isTextInputComponent") == true
        )
    }

    private fun mapQtKeyToName(qtKey: Int, text: String): String = when (qtKey) {
        0x01000000 -> "Escape"
        0x01000001 -> "Tab"
        0x01000003 -> "Backspace"
        0x01000004, 0x01000005 -> "Enter"
        0x01000007 -> "Delete"
        0x01000010 -> "Home"
        0x01000011 -> "End"
        0x01000012 -> "ArrowLeft"
        0x01000013 -> "ArrowUp"
        0x01000014 -> "ArrowRight"
        0x01000015 -> "ArrowDown"
        0x01000016 -> "PageUp"
        0x01000017 -> "PageDown"
        else -> text.ifEmpty { if (qtKey in 32..126) qtKey.toChar().toString() else "Key_$qtKey" }
    }

    fun applyCommonWidgetProperties(impl: DesktopBaseNativeImpl<KdeWidgetHandle>, handle: KdeWidgetHandle) {
        applyCommonWidgetProperties(
            handle = handle,
            cursor = impl.getCustomCursor(),
            pointerEventsNone = handle.isSelfPointerEventsNone(),
            tooltip = impl.getTooltipText(),
            opacity = impl.getOpacity()
        )
    }

    fun applyCommonWidgetProperties(handle: KdeWidgetHandle, cursor: CssCursorValue?, pointerEventsNone: Boolean, tooltip: String?, opacity: Float) {
        val ptr = handle.qWidgetPtr ?: return
        if (handle.kdeDestroyed || !KdeVTableBridge.isPeerAlive(ptr)) return
        KdeDynamicBindings.setWidgetAttribute(ptr, KdeDynamicBindings.WA_TRANSPARENT_FOR_MOUSE_EVENTS, pointerEventsNone)
        if (!tooltip.isNullOrBlank()) {
            KdeDynamicBindings.setWidgetTooltip(ptr, tooltip)
        } else {
            KdeDynamicBindings.setWidgetTooltip(ptr, "")
        }
        applyWidgetCursor(handle, cursor)
        if (handle.isTopLevelWindow) {
            KdeDynamicBindings.setWindowOpacity(ptr, opacity)
        }
    }

    internal fun applyWidgetCursor(handle: KdeWidgetHandle, explicitCursor: CssCursorValue? = null) {
        val ptr = handle.qWidgetPtr ?: return
        if (handle.kdeDestroyed || !KdeVTableBridge.isPeerAlive(ptr)) return
        if (PointerDragManager.isCursorLocked) {
            val locked = PointerDragManager.lockedCursor ?: return
            val lockedCss = cursorValueToCss(locked).trim().lowercase()
            val lockedShape = if (lockedCss == "default") KdeDynamicBindings.CURSOR_ARROW else mapCssCursorNameToQtShape(lockedCss)
            KdeDynamicBindings.setWidgetCursorShape(ptr, lockedShape)
            return
        }
        val qtCursor = resolveEffectiveQtCursorShape(handle, explicitCursor)
        KdeDynamicBindings.setWidgetCursorShape(ptr, qtCursor)
    }

    internal fun refreshWidgetTreeCursors(root: DesktopNativeWidget) {
        if (root.disposed) return
        if (PointerDragManager.isCursorLocked && PointerDragManager.lockedCursor == null) {
            return
        }
        if (root is KdeWidgetHandle) {
            applyWidgetCursor(root)
        }
        for (child in root.children) {
            refreshWidgetTreeCursors(child)
        }
    }

    private fun resolveEffectiveQtCursorShape(
        handle: DesktopNativeWidget,
        explicitCursor: CssCursorValue?
    ): Int? {
        var cur: DesktopNativeWidget? = handle
        var isFirst = true
        while (cur != null) {
            val cleaned = resolveNodeCursorCss(cur, if (isFirst) explicitCursor else null)?.trim()?.lowercase()
            isFirst = false
            if (!cleaned.isNullOrEmpty() && cleaned != "auto" && cleaned != "inherit") {
                if (cleaned == "default") {
                    return if (hasAncestorNonDefaultCursor(cur.parentWidget)) KdeDynamicBindings.CURSOR_ARROW else null
                }
                return mapCssCursorNameToQtShape(cleaned)
            }
            cur = cur.parentWidget
        }
        return null
    }

    private fun hasAncestorNonDefaultCursor(start: DesktopNativeWidget?): Boolean {
        var cur: DesktopNativeWidget? = start
        while (cur != null) {
            val cleaned = resolveNodeCursorCss(cur, null)?.trim()?.lowercase()
            if (!cleaned.isNullOrEmpty() && cleaned != "auto" && cleaned != "inherit") {
                return cleaned != "default"
            }
            cur = cur.parentWidget
        }
        return false
    }

    private fun resolveNodeCursorCss(
        node: DesktopNativeWidget,
        explicitCursor: CssCursorValue?
    ): String? {
        if (explicitCursor != null) {
            return cursorValueToCss(explicitCursor)
        }
        val customAttr = node.attributes["customCursor"] as? CssCustomCursor
        if (customAttr != null) {
            return customAttr.fallback.cssValue
        }
        val strAttr = node.attributes["cursor"] as? String
        if (!strAttr.isNullOrBlank()) {
            return strAttr
        }
        val implCursor = (node.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>)?.getCustomCursor()
        return implCursor?.let(::cursorValueToCss)
    }

    private fun cursorValueToCss(cursor: CssCursorValue): String = when (cursor) {
        is CssCustomCursor -> cursor.fallback.cssValue
        is CssCursor -> cursor.cssValue
        else -> cursor.toCssString()
    }

    private fun mapCssCursorNameToQtShape(cssCursor: String): Int? = when (cssCursor) {
        "none" -> 10 // Qt::BlankCursor
        "pointer", "hand" -> 13 // Qt::PointingHandCursor
        "text", "ibeam" -> 4 // Qt::IBeamCursor
        "wait" -> 3 // Qt::WaitCursor
        "progress" -> 16 // Qt::BusyCursor
        "help" -> 15 // Qt::WhatsThisCursor
        "crosshair" -> 2 // Qt::CrossCursor
        "move", "all-scroll" -> 9 // Qt::SizeAllCursor
        "grab" -> 17 // Qt::OpenHandCursor
        "grabbing" -> 18 // Qt::ClosedHandCursor
        "ew-resize", "col-resize", "e-resize", "w-resize" -> KdeDynamicBindings.CURSOR_SIZE_HOR
        "ns-resize", "row-resize", "n-resize", "s-resize" -> KdeDynamicBindings.CURSOR_SIZE_VER
        "nesw-resize", "ne-resize", "sw-resize" -> KdeDynamicBindings.CURSOR_SIZE_BDIAG
        "nwse-resize", "nw-resize", "se-resize" -> KdeDynamicBindings.CURSOR_SIZE_FDIAG
        "not-allowed", "no-drop" -> 14 // Qt::ForbiddenCursor
        else -> null
    }

    // --- Titlebar Canvas Adapter over live QPainter* ---
    class KdeTitleBarPainterCanvas(
        private val painterPtr: COpaquePointer
    ) : LinuxTitleBarCanvas {
        override fun save() { KdeDynamicBindings.painterSave(painterPtr) }
        override fun restore() { KdeDynamicBindings.painterRestore(painterPtr) }
        override fun clipRect(rect: Rect) {
            KdeDynamicBindings.painterSetClipRect(
                painterPtr,
                rect.x.toDouble(),
                rect.y.toDouble(),
                rect.width.toDouble(),
                rect.height.toDouble(),
                KdeDynamicBindings.CLIP_INTERSECT
            )
        }

        override fun fillRect(rect: Rect, color: RgbaColor) {
            KdeDynamicBindings.painterFillRect(painterPtr, rect.x.toDouble(), rect.y.toDouble(), rect.width.toDouble(), rect.height.toDouble(), color)
        }

        override fun fillTopRoundedRect(rect: Rect, topRadiusPx: Float, color: RgbaColor) {
            val r = topRadiusPx.toDouble()
            KdeDynamicBindings.withQPainterPath { p ->
                KdeDynamicBindings.pathAddRoundedRect(p, rect.x.toDouble(), rect.y.toDouble(), rect.width.toDouble(), rect.height.toDouble(), r, r, 0.0, 0.0)
                KdeDynamicBindings.painterFillPath(painterPtr, p, color)
            }
        }

        override fun fillLinearGradientTopRounded(rect: Rect, topRadiusPx: Float, topColor: RgbaColor, bottomColor: RgbaColor) {
            val r = topRadiusPx.toDouble()
            KdeDynamicBindings.withQPainterPath { p ->
                KdeDynamicBindings.pathAddRoundedRect(p, rect.x.toDouble(), rect.y.toDouble(), rect.width.toDouble(), rect.height.toDouble(), r, r, 0.0, 0.0)
                KdeDynamicBindings.painterFillLinearGradientPath(
                    painterPtr,
                    p,
                    rect.x.toDouble(),
                    rect.y.toDouble(),
                    rect.x.toDouble(),
                    (rect.y + rect.height).toDouble(),
                    listOf(0.0 to topColor, 1.0 to bottomColor)
                )
            }
        }

        override fun fillRoundedRect(rect: Rect, radiusPx: Float, color: RgbaColor) {
            KdeDynamicBindings.painterFillRoundedRect(
                painterPtr, rect.x.toDouble(), rect.y.toDouble(), rect.width.toDouble(), rect.height.toDouble(), radiusPx.toDouble(), color
            )
        }

        override fun strokeRoundedRect(rect: Rect, radiusPx: Float, color: RgbaColor, strokeWidthPx: Float) {
            KdeDynamicBindings.painterStrokeRoundedRect(
                painterPtr, rect.x.toDouble(), rect.y.toDouble(), rect.width.toDouble(), rect.height.toDouble(), radiusPx.toDouble(), color, strokeWidthPx.toDouble()
            )
        }

        override fun fillCircle(cx: Float, cy: Float, radius: Float, color: RgbaColor) {
            KdeDynamicBindings.painterDrawEllipse(
                painterPtr,
                (cx - radius).toDouble(),
                (cy - radius).toDouble(),
                (radius * 2f).toDouble(),
                (radius * 2f).toDouble(),
                fill = color,
                stroke = null
            )
        }

        override fun strokeCircle(cx: Float, cy: Float, radius: Float, color: RgbaColor, strokeWidthPx: Float) {
            KdeDynamicBindings.painterDrawEllipse(
                painterPtr,
                (cx - radius).toDouble(),
                (cy - radius).toDouble(),
                (radius * 2f).toDouble(),
                (radius * 2f).toDouble(),
                fill = null,
                stroke = color,
                strokeWidthPx = strokeWidthPx.toDouble()
            )
        }

        override fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float, color: RgbaColor, strokeWidthPx: Float) {
            KdeDynamicBindings.painterDrawLine(painterPtr, x1.toDouble(), y1.toDouble(), x2.toDouble(), y2.toDouble(), color, strokeWidthPx.toDouble())
        }

        override fun drawText(text: String, bounds: Rect, color: RgbaColor, fontSizePx: Float, bold: Boolean, centered: Boolean) {
            val align = (if (centered) KdeDynamicBindings.ALIGN_HCENTER else KdeDynamicBindings.ALIGN_LEFT) or KdeDynamicBindings.ALIGN_VCENTER
            KdeDynamicBindings.painterDrawText(
                painterPtr,
                text,
                bounds.x.toDouble(),
                bounds.y.toDouble(),
                bounds.width.toDouble(),
                bounds.height.toDouble(),
                color = color,
                fontSizePx = fontSizePx,
                fontWeight = if (bold) 700 else 400,
                alignmentFlags = align
            )
        }

        override fun measureTextWidth(text: String, fontSizePx: Float, bold: Boolean): Int {
            return KdeDynamicBindings.measureTextSize(text, fontSizePx, if (bold) 700 else 400).first.roundToInt()
        }

        override fun drawArgbPixels(pixels: IntArray, srcWidth: Int, srcHeight: Int, dstRect: Rect, opacity: Float) {
            if (pixels.isEmpty() || srcWidth <= 0 || srcHeight <= 0) return
            val img = KdeDynamicBindings.createQImage(srcWidth, srcHeight, KdeDynamicBindings.IMAGE_FORMAT_ARGB32_PREMULTIPLIED)
            try {
                val dstPtr = KdeDynamicBindings.qImageBits(img)?.reinterpret<kotlinx.cinterop.IntVar>() ?: return
                for (i in 0 until minOf(pixels.size, srcWidth * srcHeight)) {
                    val px = pixels[i]
                    val a = ((px ushr 24) and 0xFF)
                    if (a == 255) {
                        dstPtr[i] = px
                    } else if (a == 0) {
                        dstPtr[i] = 0
                    } else {
                        val r = (((px ushr 16) and 0xFF) * a + 127) / 255
                        val g = (((px ushr 8) and 0xFF) * a + 127) / 255
                        val b = ((px and 0xFF) * a + 127) / 255
                        dstPtr[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
                KdeDynamicBindings.painterSave(painterPtr)
                if (opacity < 1f) KdeDynamicBindings.painterSetOpacity(painterPtr, opacity)
                KdeDynamicBindings.painterDrawImageRect(
                    painterPtr,
                    dstRect.x.toDouble(),
                    dstRect.y.toDouble(),
                    dstRect.width.toDouble(),
                    dstRect.height.toDouble(),
                    img,
                    0.0,
                    0.0,
                    srcWidth.toDouble(),
                    srcHeight.toDouble()
                )
                KdeDynamicBindings.painterRestore(painterPtr)
            } finally {
                KdeDynamicBindings.destroyQImage(img)
            }
        }
    }

    fun restackChildrenByZOrder(childImpls: List<com.nexustools.genericui.core.NativeImpl<*>>) {
        if (childImpls.size <= 1) return
        val ordered = childImpls.filterIsInstance<DesktopBaseNativeImpl<*>>()
            .sortedWith(compareBy<DesktopBaseNativeImpl<*>> { it.nativeWidget.effectiveZIndex() }.thenBy { childImpls.indexOf(it) })
        for (child in ordered) {
            val cPtr = (child.nativeWidget as? KdeWidgetHandle)?.qWidgetPtr
            if (cPtr != null && KdeVTableBridge.isPeerAlive(cPtr)) {
                KdeDynamicBindings.raiseWidget(cPtr)
            }
        }
    }

    fun restackParentByZOrder(nativeWidget: KdeWidgetHandle) {
        val parentImpl = nativeWidget.parentWidget?.attributes?.get("nativeImpl") as? com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl<*>
            ?: return
        restackChildrenByZOrder(parentImpl.rawChildImplsSnapshot())
    }
}
