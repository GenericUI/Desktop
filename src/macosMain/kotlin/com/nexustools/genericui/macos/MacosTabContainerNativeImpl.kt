@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.component.tabs.TabBarStyle
import com.nexustools.genericui.component.tabs.TabIconPlacement
import com.nexustools.genericui.component.tabs.TabPosition
import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTabContainerNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTabNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.css.CssBorderStyle
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.macos.impl.MacosTabNativeImpl
import com.nexustools.genericui.nativeimpl.tabs.TabNativeImpl
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.style.css.StyledVisualSpec
import kotlinx.cinterop.CValue
import kotlinx.cinterop.useContents
import platform.AppKit.NSBezelStylePush
import platform.AppKit.NSButton
import platform.AppKit.NSCompositingOperation
import platform.AppKit.NSEvent
import platform.AppKit.NSFont
import platform.AppKit.NSFontWeightMedium
import platform.AppKit.NSFontWeightRegular
import platform.AppKit.NSTextAlignmentCenter
import platform.AppKit.NSView
import platform.AppKit.drawInRect
import platform.AppKit.size
import platform.CoreGraphics.CGPoint
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSMakeRect
import platform.Foundation.NSRect

internal class MacosTabHeaderCellView(
    private val index: Int,
    private val tabImpl: DesktopTabNativeImpl<*>?,
    private val title: String,
    private var isSelected: Boolean,
    private val style: TabBarStyle,
    private var darkTheme: Boolean,
    private val onSelect: (Int) -> Unit,
    private val onClose: (Int) -> Unit,
    frameRect: CValue<NSRect> = NSMakeRect(0.0, 0.0, 120.0, 34.0)
) : NSView(frameRect) {

    private val surface = MacosSurfaceLayerSupport(this)
    private val foregroundView = MacosForegroundDrawView({ drawForeground() })
    private var closeButton: NSButton? = null
    private val closeTarget = MacosControlActionTarget {
        tabImpl?.onCloseClickedListener?.invoke() ?: onClose(index)
    }

    init {
        setWantsLayer(true)
        addSubview(foregroundView)
        if (tabImpl?.closableVal == true) {
            val cb = NSButton.buttonWithTitle("×", target = closeTarget, action = closeTarget.actionSelector).apply {
                setBordered(false)
                setBezelStyle(NSBezelStylePush)
            }
            closeButton = cb
            addSubview(cb)
        }
        val customView = (tabImpl?.customHeaderImpl as? DesktopBaseNativeImpl<*>)?.let {
            MacosAppKitDispatch.resolveOrCreateChildNsView(it)
        } ?: (tabImpl?.customHeaderImpl?.nativeWidget as? MacosViewHandle)?.ensureNsViewOnMain()
        if (customView != null) {
            if (customView.superview != null) customView.removeFromSuperview()
            addSubview(customView)
        }
        updateSurface()
    }

    override fun isFlipped(): Boolean = true

    override fun setFrame(frame: CValue<NSRect>) {
        super.setFrame(frame)
        layoutParts()
        setNeedsDisplay(true)
    }

    override fun layout() {
        super.layout()
        layoutParts()
    }

    override fun mouseUp(event: NSEvent) {
        onSelect(index)
    }

    private fun layoutParts() {
        val (w, h) = bounds.useContents { size.width to size.height }
        val cb = closeButton
        if (cb != null) {
            val cSize = 18.0
            cb.setFrame(CGRectMake((w - cSize - 6.0).coerceAtLeast(0.0), ((h - cSize) * 0.5).coerceAtLeast(0.0), cSize, cSize))
        }
        val customView = (tabImpl?.customHeaderImpl as? DesktopBaseNativeImpl<*>)?.let {
            MacosAppKitDispatch.resolveOrCreateChildNsView(it)
        } ?: (tabImpl?.customHeaderImpl?.nativeWidget as? MacosViewHandle)?.ensureNsViewOnMain()
        if (customView != null) {
            val rightReserve = if (cb != null) 26.0 else 0.0
            customView.setFrame(CGRectMake(4.0, 2.0, (w - rightReserve - 8.0).coerceAtLeast(0.0), (h - 4.0).coerceAtLeast(0.0)))
        }
        foregroundView.setFrame(bounds)
    }

    fun updateSelectionAndTheme(selected: Boolean, dark: Boolean) {
        if (isSelected == selected && darkTheme == dark) return
        isSelected = selected
        darkTheme = dark
        updateSurface()
    }

    private fun updateSurface() {
        val accent = BootstrapColorPalette.resolveColor(BootstrapType.PRIMARY, darkTheme)
        val bg = when {
            isSelected && style == TabBarStyle.PILLS -> accent
            isSelected -> if (darkTheme) RgbaColor(54, 56, 66, 255) else RgbaColor(255, 255, 255, 255)
            else -> null
        }
        val border = if (isSelected && style != TabBarStyle.PILLS) {
            if (darkTheme) RgbaColor(82, 86, 98, 255) else RgbaColor(200, 204, 214, 255)
        } else null
        surface.applyStyledVisuals(
            spec = StyledVisualSpec(
                backgroundColor = bg,
                borderColor = border,
                borderWidthPx = if (border != null) 1f else 0f,
                borderStyle = if (border != null) CssBorderStyle.SOLID else CssBorderStyle.NONE,
                borderRadiusPx = if (style == TabBarStyle.PILLS) 8f else 6f
            ),
            cornerRadii = null,
            boxShadow = null,
            isFocused = false,
            clipOverflow = true,
            darkTheme = darkTheme,
            effectiveBackdropFilter = null,
            effectiveForegroundFilter = null
        )
        if (tabImpl?.customHeaderImpl == null) {
            foregroundView.syncToHost(this, forceRedraw = true)
        }
        closeButton?.let { cb -> addSubview(cb) }
        setNeedsDisplay(true)
    }

    private fun drawForeground() {
        if (tabImpl?.customHeaderImpl != null) return
        val (w, h) = bounds.useContents { size.width to size.height }
        val fg = when {
            isSelected && style == TabBarStyle.PILLS -> RgbaColor.WHITE
            isSelected && darkTheme -> RgbaColor.WHITE
            isSelected -> RgbaColor(20, 20, 24, 255)
            darkTheme -> RgbaColor(180, 184, 194, 255)
            else -> RgbaColor(80, 84, 94, 255)
        }
        val font = NSFont.systemFontOfSize(12.5, weight = if (isSelected) NSFontWeightMedium else NSFontWeightRegular)
        val displayTitle = if (tabImpl?.dirtyVal == true) "$title •" else title
        val attr = MacosAppKitAppearance.buildAttributedString(
            text = displayTitle,
            style = null,
            textColor = fg,
            defaultFont = font,
            alignment = NSTextAlignmentCenter
        )
        val iconSpec = tabImpl?.iconVal
        val iconPx = (tabImpl?.iconSizePxVal ?: 16).coerceIn(12, 20)
        val iconImg = if (!iconSpec.isNullOrBlank()) {
            MacosAppKitAppearance.resolveNsImage(iconSpec, iconPx, tintColor = fg)
        } else null
        val rightPad = if (closeButton != null) 24.0 else 8.0
        val usableW = (w - rightPad - 8.0).coerceAtLeast(16.0)
        val ts = attr.size().useContents { width to height }
        val textStartX = if (iconImg != null && tabImpl?.iconPlacementVal == TabIconPlacement.START) 26.0 else 8.0
        if (iconImg != null) {
            iconImg.drawInRect(
                dstSpacePortionRect = CGRectMake(8.0, ((h - iconPx) * 0.5).coerceAtLeast(2.0), iconPx.toDouble(), iconPx.toDouble()),
                fromRect = NSMakeRect(0.0, 0.0, 0.0, 0.0),
                operation = NSCompositingOperation.NSCompositingOperationSourceOver,
                fraction = 1.0,
                respectFlipped = true,
                hints = null
            )
        }
        attr.drawInRect(
            CGRectMake(textStartX, ((h - ts.second) * 0.5).coerceAtLeast(2.0), (usableW - (textStartX - 8.0)).coerceAtLeast(0.0), ts.second + 2.0)
        )
    }
}

internal class MacosTabContainerView(
    private val owner: MacosTabContainerNativeImpl,
    frameRect: CValue<NSRect> = NSMakeRect(0.0, 0.0, 400.0, 300.0)
) : NSView(frameRect) {

    private val surfaceSupport = MacosSurfaceLayerSupport(this)
    val stripView = NSView(NSMakeRect(0.0, 0.0, 400.0, 36.0))
    val contentHostView = NSView(NSMakeRect(0.0, 36.0, 400.0, 264.0))

    init {
        setWantsLayer(true)
        setAutoresizesSubviews(false)
        stripView.setWantsLayer(true)
        stripView.setAutoresizesSubviews(false)
        contentHostView.setWantsLayer(true)
        contentHostView.setAutoresizesSubviews(false)
        addSubview(stripView)
        addSubview(contentHostView)
    }

    override fun isFlipped(): Boolean = true

    override fun hitTest(point: CValue<CGPoint>): NSView? {
        if (isHidden() || alphaValue <= 0.004) return null
        val hit = super.hitTest(point)
        if (owner.nativeWidget.isSelfPointerEventsNone() && hit === this) return null
        return hit
    }

    fun applyContainerBackdropSurface(darkTheme: Boolean) {
        val spec = StyledVisualSpec(
            backgroundColor = if (darkTheme) RgbaColor(32, 34, 40, 255) else RgbaColor(246, 247, 250, 255),
            borderColor = if (darkTheme) RgbaColor(60, 64, 72, 255) else RgbaColor(218, 222, 230, 255),
            borderWidthPx = 1f,
            borderStyle = CssBorderStyle.SOLID,
            borderRadiusPx = 8f
        )
        surfaceSupport.applyStyledVisuals(
            spec = spec,
            cornerRadii = null,
            boxShadow = null,
            isFocused = false,
            clipOverflow = true,
            darkTheme = darkTheme,
            effectiveBackdropFilter = null,
            effectiveForegroundFilter = null
        )
    }
}

/**
 * macOS AppKit retained tab container implementation of [DesktopTabContainerNativeImpl].
 */
class MacosTabContainerNativeImpl(
    nativeWidget: MacosViewHandle
) : DesktopTabContainerNativeImpl<MacosViewHandle>(nativeWidget) {

    private var containerView: MacosTabContainerView? = null
    private var cellViews: List<MacosTabHeaderCellView> = emptyList()
    private var lastLaidOutTabW: Int = -1
    private var lastLaidOutTabH: Int = -1
    private var lastLaidOutTabSelectedIdx: Int = -1

    init {
        nativeWidget.viewMaterializer = {
            ensureContainerView().also { rebuildTabsView() }
        }
        if (MacosAppKitDispatch.isMainThread()) {
            ensureContainerView()
            rebuildTabsView()
        }
    }

    private fun ensureContainerView(): MacosTabContainerView {
        containerView?.let { return it }
        val v = MacosTabContainerView(this)
        containerView = v
        nativeWidget.nsView = v
        v.setHidden(!nativeWidget.visible)
        v.setAlphaValue(nativeWidget.opacity.coerceIn(0f, 1f).toDouble())
        v.setToolTip(nativeWidget.tooltipText)
        return v
    }

    private fun rebuildTabsView() {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { rebuildTabsView() }
            return
        }
        val root = ensureContainerView()
        val darkTheme = MacosAppKitAppearance.isDarkTheme(nativeWidget)
        root.applyContainerBackdropSurface(darkTheme)

        for (sub in root.stripView.subviews.filterIsInstance<NSView>()) {
            sub.removeFromSuperview()
        }
        val newCells = ArrayList<MacosTabHeaderCellView>(tabImpls.size)
        for (i in tabImpls.indices) {
            (tabImpls[i] as? MacosTabNativeImpl)?.let { tabImpl ->
                tabImpl.onTabUpdatedHook = { rebuildTabsView() }
                tabImpl.onTabSelectedChangedHook = { syncSelectionInPlace() }
            }
            val title = tabTitles.getOrElse(i) { "" }
            val cell = MacosTabHeaderCellView(
                index = i,
                tabImpl = tabImpls[i] as? DesktopTabNativeImpl<*>,
                title = title,
                isSelected = (i == selectedIndexVal && !isButtonTab(i)),
                style = tabBarStyleVal,
                darkTheme = darkTheme,
                onSelect = { idx -> requestSelectTab(idx) },
                onClose = { idx -> requestCloseTab(idx) }
            )
            newCells.add(cell)
            root.stripView.addSubview(cell)
        }
        cellViews = newCells

        val activeContentImpl = contentImpls.getOrNull(selectedIndexVal) as? DesktopBaseNativeImpl<*>
        val activeContent = activeContentImpl?.let { MacosAppKitDispatch.resolveOrCreateChildNsView(it) }
            ?: (getSelectedContentWidget() as? MacosViewHandle)?.ensureNsViewOnMain()
        val hostViews: List<NSView> = if (activeContent != null) listOf(activeContent) else emptyList()
        MacosAppKitDispatch.syncChildViewHierarchy(root.contentHostView, hostViews)
        lastLaidOutTabW = -1
        lastLaidOutTabH = -1
        layoutTabsInCurrentBounds()
    }

    private fun syncSelectionInPlace() {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { syncSelectionInPlace() }
            return
        }
        val root = containerView ?: run {
            rebuildTabsView()
            return
        }
        if (cellViews.size != tabImpls.size) {
            rebuildTabsView()
            return
        }
        val darkTheme = MacosAppKitAppearance.isDarkTheme(nativeWidget)
        for (i in cellViews.indices) {
            cellViews[i].updateSelectionAndTheme(
                selected = (i == selectedIndexVal && !isButtonTab(i)),
                dark = darkTheme
            )
        }
        val activeContentImpl = contentImpls.getOrNull(selectedIndexVal) as? DesktopBaseNativeImpl<*>
        val activeContent = activeContentImpl?.let { MacosAppKitDispatch.resolveOrCreateChildNsView(it) }
            ?: (getSelectedContentWidget() as? MacosViewHandle)?.ensureNsViewOnMain()
        val hostViews: List<NSView> = if (activeContent != null) listOf(activeContent) else emptyList()
        MacosAppKitDispatch.syncChildViewHierarchy(root.contentHostView, hostViews)
        layoutActiveContentInCurrentBounds()
    }

    private fun layoutActiveContentInCurrentBounds() {
        val root = containerView ?: return
        val (cw, ch) = root.contentHostView.bounds.useContents { size.width to size.height }
        val activeIdx = selectedIndexVal
        val activeContentImpl = contentImpls.getOrNull(activeIdx) as? DesktopBaseNativeImpl<*>
        if (activeContentImpl != null) {
            activeContentImpl.setBounds(Rect(0, 0, cw.toInt(), ch.toInt()))
            if (activeContentImpl is com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl<*>) {
                activeContentImpl.layoutChildrenInBounds(cw.toFloat(), ch.toFloat())
            }
            MacosAppKitDispatch.resolveOrCreateChildNsView(activeContentImpl)?.let { cv ->
                MacosAppKitDispatch.applyFrameAndTransform(
                    view = cv,
                    bounds = Rect(0, 0, cw.toInt(), ch.toInt()),
                    transform = (activeContentImpl.nativeWidget as? MacosViewHandle)?.cssTransform,
                    useAlignmentRectExpansion = false
                )
            }
        }
    }

    private fun layoutTabsInCurrentBounds() {
        val root = containerView ?: return
        val (w, h) = root.bounds.useContents { size.width to size.height }
        if (w <= 0.0 || h <= 0.0) return
        val intW = w.toInt()
        val intH = h.toInt()
        if (intW == lastLaidOutTabW && intH == lastLaidOutTabH && selectedIndexVal == lastLaidOutTabSelectedIdx) {
            return
        }
        lastLaidOutTabW = intW
        lastLaidOutTabH = intH
        lastLaidOutTabSelectedIdx = selectedIndexVal

        val stripThickness = if (tabPositionVal == TabPosition.LEFT || tabPositionVal == TabPosition.RIGHT) 140.0 else 38.0
        val stripInt = stripThickness.toInt()
        when (tabPositionVal) {
            TabPosition.TOP -> {
                root.stripView.setFrame(CGRectMake(0.0, 0.0, w, stripThickness.coerceAtMost(h)))
                root.contentHostView.setFrame(CGRectMake(0.0, stripThickness, w, (h - stripThickness).coerceAtLeast(0.0)))
                nativeWidget.attributes["tabContentOffsetX"] = 0
                nativeWidget.attributes["tabContentOffsetY"] = stripInt
            }
            TabPosition.BOTTOM -> {
                val cH = (h - stripThickness).coerceAtLeast(0.0)
                root.contentHostView.setFrame(CGRectMake(0.0, 0.0, w, cH))
                root.stripView.setFrame(CGRectMake(0.0, cH, w, stripThickness.coerceAtMost(h)))
                nativeWidget.attributes["tabContentOffsetX"] = 0
                nativeWidget.attributes["tabContentOffsetY"] = 0
            }
            TabPosition.LEFT -> {
                root.stripView.setFrame(CGRectMake(0.0, 0.0, stripThickness.coerceAtMost(w), h))
                root.contentHostView.setFrame(CGRectMake(stripThickness, 0.0, (w - stripThickness).coerceAtLeast(0.0), h))
                nativeWidget.attributes["tabContentOffsetX"] = stripInt
                nativeWidget.attributes["tabContentOffsetY"] = 0
            }
            TabPosition.RIGHT -> {
                val cW = (w - stripThickness).coerceAtLeast(0.0)
                root.contentHostView.setFrame(CGRectMake(0.0, 0.0, cW, h))
                root.stripView.setFrame(CGRectMake(cW, 0.0, stripThickness.coerceAtMost(w), h))
                nativeWidget.attributes["tabContentOffsetX"] = 0
                nativeWidget.attributes["tabContentOffsetY"] = 0
            }
        }

        val stripCells = root.stripView.subviews.filterIsInstance<NSView>()
        val count = stripCells.size.coerceAtLeast(1)
        val verticalTabs = tabPositionVal == TabPosition.LEFT || tabPositionVal == TabPosition.RIGHT
        if (verticalTabs) {
            val rowH = 34.0
            for ((idx, cell) in stripCells.withIndex()) {
                cell.setFrame(CGRectMake(4.0, 4.0 + idx * (rowH + 2.0), (stripThickness - 8.0).coerceAtLeast(0.0), rowH))
            }
        } else {
            val cellW = if (fullWidthTabsVal) {
                (w / count.toDouble()).coerceAtLeast(48.0)
            } else {
                124.0.coerceAtMost((w / count.toDouble()).coerceAtLeast(56.0))
            }
            var cx = 4.0
            for (cell in stripCells) {
                cell.setFrame(CGRectMake(cx, 3.0, (cellW - 4.0).coerceAtLeast(24.0), (stripThickness - 6.0).coerceAtLeast(20.0)))
                cx += cellW
            }
        }

        layoutActiveContentInCurrentBounds()
    }

    override fun layoutChildrenInBounds(width: Float, height: Float) {
        if (MacosAppKitDispatch.isMainThread()) {
            val root = ensureContainerView()
            MacosAppKitDispatch.applyFrameAndTransform(
                view = root,
                bounds = Rect(nativeWidget.bounds.x, nativeWidget.bounds.y, width.toInt(), height.toInt()),
                transform = nativeWidget.cssTransform,
                useAlignmentRectExpansion = false
            )
            layoutTabsInCurrentBounds()
        }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size =
        measureTabContainerIntrinsicSize(
            stripThicknessPx = 44,
            verticalStripWidthPx = 140,
            maxWidth = maxWidth,
            maxHeight = maxHeight
        )

    override fun addTabNative(tabImpl: TabNativeImpl<*>, contentImpl: NativeImpl<*>, index: Int) {
        super.addTabNative(tabImpl, contentImpl, index)
        rebuildTabsView()
    }

    override fun addButtonTabNative(tabImpl: TabNativeImpl<*>, index: Int) {
        super.addButtonTabNative(tabImpl, index)
        rebuildTabsView()
    }

    override fun addTabNative(title: String, contentImpl: NativeImpl<*>, index: Int) {
        super.addTabNative(title, contentImpl, index)
        rebuildTabsView()
    }

    override fun removeTabNative(index: Int) {
        super.removeTabNative(index)
        rebuildTabsView()
    }

    override fun setTabTitleNative(index: Int, title: String) {
        super.setTabTitleNative(index, title)
        rebuildTabsView()
    }

    override fun setTabPositionNative(position: TabPosition) {
        super.setTabPositionNative(position)
        rebuildTabsView()
    }

    override fun setTabBarStyleNative(style: TabBarStyle) {
        super.setTabBarStyleNative(style)
        rebuildTabsView()
    }

    override fun setFullWidthTabsNative(fullWidth: Boolean) {
        super.setFullWidthTabsNative(fullWidth)
        rebuildTabsView()
    }

    override fun setSelectedTabIndex(index: Int, fadeTransition: Boolean) {
        super.setSelectedTabIndex(index, fadeTransition)
        if (containerView != null && cellViews.size == tabImpls.size) {
            syncSelectionInPlace()
        } else {
            rebuildTabsView()
        }
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        val v = containerView ?: return
        MacosAppKitDispatch.applyFrameAndTransform(v, rect, nativeWidget.cssTransform, useAlignmentRectExpansion = false)
        layoutTabsInCurrentBounds()
    }

    override fun onNativeVisibilityChanged(visible: Boolean) {
        containerView?.setHidden(!visible)
    }

    override fun onNativeOpacityChanged(opacity: Float) {
        containerView?.setAlphaValue(opacity.coerceIn(0f, 1f).toDouble())
    }

    override fun onNativeTooltipChanged(tooltip: String?) {
        containerView?.setToolTip(tooltip)
    }

    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        containerView?.let { v -> v.window?.invalidateCursorRectsForView(v) }
    }

    override fun onNativeTransformChanged(transform: CssTransform?) {
        nativeWidget.cssTransform = transform
        val v = containerView ?: return
        MacosAppKitDispatch.applyFrameAndTransform(v, nativeWidget.bounds, transform, useAlignmentRectExpansion = false)
    }

    override fun onNativeStyleSynced() {
        rebuildTabsView()
    }

    override fun applyComponentBootstrap(bootstrap: ComponentBootstrap) {
        super.applyComponentBootstrap(bootstrap)
        rebuildTabsView()
    }

    override fun applyComponentStyle(style: ComponentStyle) {
        super.applyComponentStyle(style)
        rebuildTabsView()
    }

    override fun applyComputedStyle(style: ComputedStyle) {
        super.applyComputedStyle(style)
        rebuildTabsView()
    }

    override fun dispose() {
        val view = containerView
        containerView = null
        MacosAppKitDispatch.detachViewOnMain(view)
        super.dispose()
    }
}
