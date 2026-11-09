@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.render

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.lazy.DesktopLazyGridNativeImpl
import com.nexustools.genericui.desktop.lazy.DesktopLazyListNativeImpl
import com.nexustools.genericui.desktop.lazy.DesktopTreeViewNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopImageNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopPopoverNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTableNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.windows.Win32DwmUxThemeBridge
import com.nexustools.genericui.windows.Win32NativeWindowHost
import kotlin.math.max
import kotlin.math.min

/**
 * Renders data display, list/tree/table, and media badge/image components on Windows (`Table`,
 * `DataGrid`, `TreeView`, `VirtualList`, `LazyColumn`, `LazyRow`, `LazyGrid`, `Timeline`,
 * `Calendar`, `PropertyGrid`, `ImageView`, `Avatar`, `Carousel`, `Chip`, `TooltipPopover`).
 *
 * Uses native `uxtheme.dll` `HEADER` (`HP_HEADERITEM`) and `TREEVIEW` (`TVP_TREEITEM` /
 * `TVP_GLYPH`) control parts with `BootstrapType` tinting via [Win32ControlTintRenderUtil].
 */
object Win32DataDisplayRenderUtil {

    fun canRender(widget: DesktopNativeWidget): Boolean {
        val impl = widget.attributes["nativeImpl"]
        if (impl is DesktopTableNativeImpl<*> ||
            impl is DesktopTreeViewNativeImpl<*> ||
            impl is DesktopLazyListNativeImpl<*> ||
            impl is DesktopLazyGridNativeImpl<*> ||
            impl is DesktopImageNativeImpl<*> ||
            impl is DesktopPopoverNativeImpl<*>
        ) {
            return true
        }
        val cls = widget.nativeClassName.lowercase()
        val role = (widget.attributes["componentRole"] as? String ?: "").lowercase()
        return cls in DATA_DISPLAY_CLASSES || role in DATA_DISPLAY_ROLES
    }

    fun render(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        left: Int,
        top: Int,
        right: Int
    ): Int {
        val impl = widget.attributes["nativeImpl"]
        val cls = widget.nativeClassName.lowercase()
        val role = (widget.attributes["componentRole"] as? String ?: "").lowercase()

        return when {
            impl is DesktopTableNativeImpl<*> || cls in TABLE_CLASSES || role in TABLE_ROLES ->
                renderTableOrDataGrid(ctx, widget, impl as? DesktopTableNativeImpl<*>, left, top, right)
            impl is DesktopTreeViewNativeImpl<*> || cls == "treeview" || role == "tree-view" ->
                renderTreeView(ctx, widget, impl as? DesktopTreeViewNativeImpl<*>, left, top, right)
            impl is DesktopLazyListNativeImpl<*> || cls in LAZY_LIST_CLASSES || role in LAZY_LIST_ROLES ->
                renderLazyList(ctx, widget, impl as? DesktopLazyListNativeImpl<*>, left, top, right)
            impl is DesktopLazyGridNativeImpl<*> || cls == "lazygrid" || role == "lazy-grid" ->
                renderLazyGrid(ctx, widget, impl as? DesktopLazyGridNativeImpl<*>, left, top, right)
            impl is DesktopImageNativeImpl<*> || cls == "imageview" || cls == "avatar" || role == "image-view" || role == "avatar" ->
                renderImageOrAvatar(ctx, widget, impl as? DesktopImageNativeImpl<*>, left, top, right)
            cls == "chip" || role == "chip" ->
                renderChip(ctx, widget, left, top, right)
            cls == "timeline" || role == "timeline" ->
                renderTimeline(ctx, widget, left, top, right)
            cls == "calendar" || role == "calendar" ->
                renderCalendar(ctx, widget, left, top, right)
            impl is DesktopPopoverNativeImpl<*> || cls == "tooltippopover" || role == "tooltip-popover" ->
                renderTooltipPopover(ctx, widget, impl as? DesktopPopoverNativeImpl<*>, left, top, right)
            else ->
                Win32ContainerChromeRenderUtil.render(ctx, widget, left, top, right)
        }
    }

    /**
     * Renders `Table`, `DataGrid`, or `PropertyGrid` using native `uxtheme.dll` `HEADER`
     * (`HP_HEADERITEM`) column headers and striped/hoverable data rows.
     */
    fun renderTableOrDataGrid(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        tableImpl: DesktopTableNativeImpl<*>?,
        left: Int,
        top: Int,
        right: Int
    ): Int {
        val attrs = tableImpl?.nativeWidget?.attributes ?: widget.attributes
        val headers = (attrs["headers"] as? List<*>)?.map { it?.toString().orEmpty() }.orEmpty()
        val rows = (attrs["rows"] as? List<*>)?.map { row ->
            (row as? List<*>)?.map { it?.toString().orEmpty() }.orEmpty()
        }.orEmpty()
        val headerH = 30
        val rowH = 28
        val totalH = headerH + (rows.size.coerceAtLeast(1) * rowH) + 2
        val bottom = top + totalH
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), totalH)

        Win32NativeWindowHost.drawRoundedRect(
            hdc = ctx.hdc,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            radius = 4,
            fillColor = ctx.bgCardColorRef,
            borderColor = ctx.borderSubtleColorRef
        )

        val colCount = max(1, headers.size.takeIf { it > 0 } ?: (rows.firstOrNull()?.size ?: 1))
        val totalTableW = (right - left).coerceAtLeast(colCount * 32)
        val rawWeights = attrs["columnWeights"] as? FloatArray
        val colBounds = IntArray(colCount + 1)
        colBounds[0] = left
        if (rawWeights != null && rawWeights.isNotEmpty()) {
            var weightSum = 0f
            for (c in 0 until colCount) {
                weightSum += (rawWeights.getOrNull(c) ?: 1f).coerceAtLeast(0.05f)
            }
            var accX = left
            for (c in 0 until colCount) {
                val w = ((rawWeights.getOrNull(c) ?: 1f).coerceAtLeast(0.05f) / weightSum * totalTableW).toInt().coerceAtLeast(24)
                accX = if (c == colCount - 1) right else min(right, accX + w)
                colBounds[c + 1] = accX
            }
        } else {
            val colW = (totalTableW / colCount).coerceAtLeast(32)
            for (c in 0 until colCount) {
                colBounds[c + 1] = if (c == colCount - 1) right else (left + (c + 1) * colW)
            }
        }
        val headerClassList = Win32DwmUxThemeBridge.resolveExplorerClassList("HEADER", ctx.darkMode)
        val hwnd = ctx.hwnd

        for (c in 0 until colCount) {
            val cL = colBounds[c]
            val cR = colBounds[c + 1]
            val hovered = hwnd != null && Win32NativeWindowHost.isRectHovered(hwnd, cL, top, cR, top + headerH)
            Win32DwmUxThemeBridge.drawUxThemeControlBackground(
                hwnd = ctx.hwnd,
                hdc = ctx.hdc,
                classList = headerClassList,
                partId = Win32DwmUxThemeBridge.HP_HEADERITEM,
                stateId = if (hovered) Win32DwmUxThemeBridge.HIS_HOT else Win32DwmUxThemeBridge.HIS_NORMAL,
                left = cL,
                top = top,
                right = cR,
                bottom = top + headerH
            )
            val hdrText = headers.getOrNull(c).orEmpty()
            ctx.selectFont(ctx.fontBold ?: ctx.fontNormal)
            Win32NativeWindowHost.drawTextInRect(
                hdc = ctx.hdc,
                text = hdrText,
                left = cL + 8,
                top = top,
                right = cR - 8,
                bottom = top + headerH,
                colorRef = ctx.textPrimaryColorRef
            )
        }

        val isStriped = attrs["striped"] == true
        ctx.selectFont(ctx.fontNormal)
        for ((rIdx, row) in rows.withIndex()) {
            val rT = top + headerH + rIdx * rowH
            val rB = rT + rowH
            if (rIdx % 2 == 1 && isStriped) {
                Win32NativeWindowHost.fillRectColor(ctx.hdc, left + 1, rT, right - 1, rB, ctx.bgElevatedColorRef)
            }
            for (c in 0 until colCount) {
                val cL = colBounds[c]
                val cR = colBounds[c + 1]
                val cellText = row.getOrNull(c).orEmpty()
                Win32NativeWindowHost.drawTextInRect(
                    hdc = ctx.hdc,
                    text = cellText,
                    left = cL + 8,
                    top = rT,
                    right = cR - 8,
                    bottom = rB,
                    colorRef = ctx.textPrimaryColorRef
                )
            }
            ctx.registerHitRegion(
                Win32NativeWindowHost.HitRegion(
                    left = left,
                    top = rT,
                    right = right,
                    bottom = rB,
                    widget = widget,
                    onClick = { _, _ ->
                        attrs["selectedRowIndex"] = rIdx
                        tableImpl?.dispatchClick()
                    }
                )
            )
        }
        return bottom
    }

    /**
     * Renders a `TreeView` using `uxtheme.dll` `TREEVIEW` (`TVP_GLYPH` + `TVP_TREEITEM`) parts.
     */
    fun renderTreeView(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        treeImpl: DesktopTreeViewNativeImpl<*>?,
        left: Int,
        top: Int,
        right: Int
    ): Int {
        val attrs = treeImpl?.nativeWidget?.attributes ?: widget.attributes
        val rootNodes = (attrs["rootNodes"] as? List<*>)
            ?.mapNotNull { it as? com.nexustools.genericui.component.lazy.TreeNode<*> }
            .orEmpty()
        val flatNodes = mutableListOf<Pair<com.nexustools.genericui.component.lazy.TreeNode<*>, Int>>()
        fun flatten(nodes: List<com.nexustools.genericui.component.lazy.TreeNode<*>>, depth: Int) {
            for (n in nodes) {
                flatNodes.add(n to depth)
                if (n.isExpanded && n.children.isNotEmpty()) {
                    flatten(n.children, depth + 1)
                }
            }
        }
        flatten(rootNodes, 0)

        val rowH = 26
        val totalH = (flatNodes.size.coerceAtLeast(1) * rowH + 8).coerceIn(36, 320)
        val bottom = top + totalH
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), totalH)

        Win32NativeWindowHost.drawRoundedRect(
            hdc = ctx.hdc,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            radius = 4,
            fillColor = ctx.bgCardColorRef,
            borderColor = ctx.borderSubtleColorRef
        )

        val treeClassList = Win32DwmUxThemeBridge.resolveExplorerClassList("TREEVIEW", ctx.darkMode)
        val selectedId = attrs["selectedNodeId"] as? String
        val hwnd = ctx.hwnd
        ctx.selectFont(ctx.fontNormal)

        for ((idx, pair) in flatNodes.withIndex()) {
            val (node, depth) = pair
            val rT = top + 4 + idx * rowH
            val rB = rT + rowH
            if (rB > bottom - 2) break
            val isSelected = node.id == selectedId
            val isHovered = hwnd != null && Win32NativeWindowHost.isRectHovered(hwnd, left + 2, rT, right - 2, rB)
            if (isSelected || isHovered) {
                Win32DwmUxThemeBridge.drawUxThemeControlBackground(
                    hwnd = ctx.hwnd,
                    hdc = ctx.hdc,
                    classList = treeClassList,
                    partId = Win32DwmUxThemeBridge.TVP_TREEITEM,
                    stateId = if (isSelected) Win32DwmUxThemeBridge.TREIS_SELECTED else Win32DwmUxThemeBridge.TREIS_HOT,
                    left = left + 2,
                    top = rT,
                    right = right - 2,
                    bottom = rB
                )
            }
            val indentX = left + 8 + depth * 18
            val hasChildren = node.children.isNotEmpty()
            if (hasChildren) {
                Win32DwmUxThemeBridge.drawUxThemeControlBackground(
                    hwnd = ctx.hwnd,
                    hdc = ctx.hdc,
                    classList = treeClassList,
                    partId = Win32DwmUxThemeBridge.TVP_GLYPH,
                    stateId = if (node.isExpanded) Win32DwmUxThemeBridge.GLPS_OPENED else Win32DwmUxThemeBridge.GLPS_CLOSED,
                    left = indentX,
                    top = rT + 5,
                    right = indentX + 16,
                    bottom = rB - 5
                )
            }
            val labelText = if (!node.iconSvgOrPath.isNullOrBlank()) {
                "${node.iconSvgOrPath}  ${node.label}"
            } else {
                node.label
            }
            Win32NativeWindowHost.drawTextInRect(
                hdc = ctx.hdc,
                text = labelText,
                left = indentX + 20,
                top = rT,
                right = right - 8,
                bottom = rB,
                colorRef = ctx.textPrimaryColorRef
            )
            ctx.registerHitRegion(
                Win32NativeWindowHost.HitRegion(
                    left = left + 2,
                    top = rT,
                    right = right - 2,
                    bottom = rB,
                    widget = widget,
                    onClick = { _, _ ->
                        treeImpl?.setSelectedNodeId(node.id)
                    }
                )
            )
        }
        return bottom
    }

    /**
     * Renders `VirtualList`, `LazyColumn`, or `LazyRow`.
     */
    fun renderLazyList(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        lazyListImpl: DesktopLazyListNativeImpl<*>?,
        left: Int,
        top: Int,
        right: Int
    ): Int {
        val children = ctx.visibleChildrenOf(widget)
        val attrs = lazyListImpl?.nativeWidget?.attributes ?: widget.attributes
        val isHorizontal = attrs["orientation"] == com.nexustools.genericui.enums.ui.Orientation.HORIZONTAL ||
            widget.nativeClassName.equals("LazyRow", ignoreCase = true) ||
            widget.attributes["componentRole"] == "lazy-row"
        var curY = top
        if (isHorizontal && children.isNotEmpty()) {
            var curX = left
            val rowH = 36
            for (child in children) {
                val itemW = 120
                val itemR = min(right, curX + itemW)
                if (curX >= itemR) break
                ctx.renderChild(child, curX, top, itemR)
                curX = itemR + 6
            }
            curY = top + rowH
        } else {
            for (child in children) {
                curY = ctx.renderChild(child, left, curY, right) + 4
            }
        }
        val bottom = max(top + 32, curY)
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), bottom - top)
        return bottom
    }

    /**
     * Renders a `LazyGrid`.
     */
    fun renderLazyGrid(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        lazyGridImpl: DesktopLazyGridNativeImpl<*>?,
        left: Int,
        top: Int,
        right: Int
    ): Int {
        val gap = 8
        val rawAvailW = (right - left).coerceAtLeast(48)
        val minColW = ((lazyGridImpl?.nativeWidget?.attributes?.get("minColumnWidthPx") as? Number)?.toInt()
            ?: (widget.attributes["minColumnWidthPx"] as? Number)?.toInt()
            ?: 0).coerceAtLeast(0)
        val cols = if (minColW > 0) {
            ((rawAvailW + gap) / (minColW + gap)).coerceAtLeast(1)
        } else {
            (lazyGridImpl?.getColumnCount() ?: 3).coerceAtLeast(1)
        }
        val children = ctx.visibleChildrenOf(widget)
        val availW = rawAvailW.coerceAtLeast(cols * 24)
        val cellW = (availW - gap * (cols - 1)) / cols
        var curY = top
        var rowMaxBottom = top
        for ((idx, child) in children.withIndex()) {
            val col = idx % cols
            if (col == 0 && idx > 0) {
                curY = rowMaxBottom + gap
                rowMaxBottom = curY
            }
            val cL = left + col * (cellW + gap)
            val cR = min(right, cL + cellW)
            val b = ctx.renderChild(child, cL, curY, cR)
            if (b > rowMaxBottom) rowMaxBottom = b
        }
        val bottom = max(top + 32, rowMaxBottom)
        widget.bounds = Rect(left, top, availW, bottom - top)
        return bottom
    }

    /**
     * Renders an `ImageView` or `Avatar`.
     */
    fun renderImageOrAvatar(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        imageImpl: DesktopImageNativeImpl<*>?,
        left: Int,
        top: Int,
        right: Int
    ): Int {
        val isAvatar = widget.nativeClassName.equals("Avatar", ignoreCase = true) ||
            widget.attributes["componentRole"] == "avatar"
        val defaultSize = if (isAvatar) 40 else 48
        val w = ((widget.attributes["width"] as? Number)?.toInt()
            ?: widget.preferredSize?.width
            ?: defaultSize).coerceIn(16, (right - left).coerceAtLeast(16))
        val h = ((widget.attributes["height"] as? Number)?.toInt()
            ?: widget.preferredSize?.height
            ?: defaultSize).coerceIn(16, 240)
        val imgRight = left + w
        val bottom = top + h
        widget.bounds = Rect(left, top, w, h)

        if (!isAvatar && ctx.drawImageBoxCallback != null) {
            val url = imageImpl?.getSourceUrl()?.trim().orEmpty()
            val placeholderUrl = imageImpl?.getPlaceholderUrl()?.trim().takeUnless { it.isNullOrEmpty() }
                ?: com.nexustools.genericui.image.ImageLoader.DEFAULT_PLACEHOLDER_URI
            val antialiasing = imageImpl?.isAntialiasing() ?: true
            val fallbackTxt = (widget.attributes["fallbackText"] as? String)?.trim().orEmpty()
            ctx.drawImageBoxCallback?.invoke(
                ctx,
                widget,
                url,
                placeholderUrl,
                antialiasing,
                fallbackTxt,
                left,
                top,
                imgRight,
                bottom,
                ctx.bgElevatedColorRef
            )
            return bottom
        }

        val radius = if (isAvatar || "rounded-circle" in widget.cachedAllClassTokens) min(w, h) / 2 else 6
        Win32NativeWindowHost.drawRoundedRect(
            hdc = ctx.hdc,
            left = left,
            top = top,
            right = imgRight,
            bottom = bottom,
            radius = radius,
            fillColor = ctx.bgElevatedColorRef,
            borderColor = ctx.borderSubtleColorRef
        )
        val altText = (imageImpl?.nativeWidget?.attributes?.get("alt") as? String)?.takeIf { it.isNotBlank() }
            ?: (widget.attributes["initials"] as? String)
            ?: (widget.attributes["alt"] as? String)
            ?: "IMG"
        ctx.selectFont(ctx.fontSmallBold ?: ctx.fontNormal)
        Win32NativeWindowHost.drawTextInRect(
            hdc = ctx.hdc,
            text = altText.take(4),
            left = left + 4,
            top = top + 2,
            right = imgRight - 4,
            bottom = bottom - 2,
            colorRef = ctx.textSecondaryColorRef,
            center = true
        )
        return bottom
    }

    /**
     * Renders a `Chip` using native `uxtheme.dll` `BUTTON` + `BootstrapType` tinting.
     */
    fun renderChip(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        left: Int,
        top: Int,
        right: Int,
        height: Int = 26
    ): Int = Win32ButtonRenderUtil.render(ctx, widget, left, top, right, fixedHeight = height)

    /**
     * Renders a `Timeline` component.
     */
    fun renderTimeline(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        left: Int,
        top: Int,
        right: Int
    ): Int {
        val children = ctx.visibleChildrenOf(widget)
        var curY = top + 4
        val lineX = left + 12
        for (child in children) {
            val itemBottom = ctx.renderChild(child, left + 28, curY, right)
            Win32NativeWindowHost.drawRoundedRect(
                hdc = ctx.hdc,
                left = lineX - 5,
                top = curY + 4,
                right = lineX + 5,
                bottom = curY + 14,
                radius = 5,
                fillColor = ctx.accentBlueColorRef,
                borderColor = ctx.accentBlueColorRef
            )
            curY = itemBottom + 8
        }
        if (curY > top + 12) {
            Win32NativeWindowHost.fillRectColor(ctx.hdc, lineX, top + 8, lineX + 2, curY - 8, ctx.borderSubtleColorRef)
        }
        val bottom = max(top + 32, curY)
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), bottom - top)
        return bottom
    }

    /**
     * Renders a `Calendar` month view grid.
     */
    fun renderCalendar(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        left: Int,
        top: Int,
        right: Int
    ): Int {
        val height = 180
        val bottom = top + height
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), height)
        Win32NativeWindowHost.drawRoundedRect(
            hdc = ctx.hdc,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            radius = 6,
            fillColor = ctx.bgCardColorRef,
            borderColor = ctx.borderSubtleColorRef
        )
        val monthTitle = (widget.attributes["monthTitle"] as? String) ?: "Calendar"
        Win32ControlTintRenderUtil.drawNativeOrTintedControlWithTextOverlay(
            ctx = ctx,
            widget = widget,
            baseClass = "HEADER",
            partId = Win32DwmUxThemeBridge.HP_HEADERITEM,
            stateId = Win32DwmUxThemeBridge.HIS_NORMAL,
            left = left + 1,
            top = top + 1,
            right = right - 1,
            bottom = top + 28,
            text = monthTitle,
            font = ctx.fontBold ?: ctx.fontNormal,
            centerText = true
        )
        val days = listOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa")
        val colW = ((right - left - 16) / 7).coerceAtLeast(20)
        ctx.selectFont(ctx.fontSmallBold ?: ctx.fontSmall)
        for ((idx, d) in days.withIndex()) {
            val cL = left + 8 + idx * colW
            Win32NativeWindowHost.drawTextInRect(ctx.hdc, d, cL, top + 30, cL + colW, top + 48, ctx.textSecondaryColorRef, center = true)
        }
        ctx.selectFont(ctx.fontSmall ?: ctx.fontNormal)
        for (day in 1..28) {
            val row = (day - 1) / 7
            val col = (day - 1) % 7
            val cL = left + 8 + col * colW
            val rT = top + 50 + row * 28
            Win32NativeWindowHost.drawTextInRect(
                hdc = ctx.hdc,
                text = day.toString(),
                left = cL,
                top = rT,
                right = cL + colW,
                bottom = rT + 24,
                colorRef = ctx.textPrimaryColorRef,
                center = true
            )
        }
        return bottom
    }

    /**
     * Renders a `TooltipPopover`.
     */
    fun renderTooltipPopover(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        popoverImpl: DesktopPopoverNativeImpl<*>?,
        left: Int,
        top: Int,
        right: Int
    ): Int {
        if (popoverImpl != null && !popoverImpl.isVisible()) return top
        val title = ((popoverImpl?.nativeWidget?.attributes?.get("title") as? String)
            ?: (widget.attributes["title"] as? String))?.trim().orEmpty()
        val body = Win32NativeWindowHost.extractWidgetText(widget).trim()
        val h = if (title.isNotEmpty() && body.isNotEmpty()) 54 else 32
        val bottom = top + h
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), h)
        Win32NativeWindowHost.drawRoundedRect(
            hdc = ctx.hdc,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            radius = 6,
            fillColor = ctx.bgElevatedColorRef,
            borderColor = ctx.borderSubtleColorRef
        )
        if (title.isNotEmpty()) {
            ctx.selectFont(ctx.fontBold ?: ctx.fontNormal)
            Win32NativeWindowHost.drawTextInRect(ctx.hdc, title, left + 10, top + 4, right - 10, top + 24, ctx.textPrimaryColorRef)
        }
        if (body.isNotEmpty()) {
            ctx.selectFont(ctx.fontNormal)
            Win32NativeWindowHost.drawTextInRect(
                hdc = ctx.hdc,
                text = body,
                left = left + 10,
                top = if (title.isNotEmpty()) top + 24 else top + 4,
                right = right - 10,
                bottom = bottom - 4,
                colorRef = ctx.textSecondaryColorRef
            )
        }
        return bottom
    }

    private val TABLE_CLASSES = setOf("table", "datagrid", "propertygrid")
    private val TABLE_ROLES = setOf("table", "data-grid", "property-grid")
    private val LAZY_LIST_CLASSES = setOf("virtuallist", "lazycolumn", "lazyrow", "carousel")
    private val LAZY_LIST_ROLES = setOf("virtual-list", "lazy-column", "lazy-row", "carousel")
    private val DATA_DISPLAY_CLASSES = setOf(
        "table",
        "datagrid",
        "treeview",
        "virtuallist",
        "lazycolumn",
        "lazyrow",
        "lazygrid",
        "timeline",
        "calendar",
        "propertygrid",
        "imageview",
        "avatar",
        "carousel",
        "chip",
        "tooltippopover"
    )
    private val DATA_DISPLAY_ROLES = setOf(
        "table",
        "data-grid",
        "tree-view",
        "virtual-list",
        "lazy-column",
        "lazy-row",
        "lazy-grid",
        "timeline",
        "calendar",
        "property-grid",
        "image-view",
        "avatar",
        "carousel",
        "chip",
        "tooltip-popover"
    )
}
