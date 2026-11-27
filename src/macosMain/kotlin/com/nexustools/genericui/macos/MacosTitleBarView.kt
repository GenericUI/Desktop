@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.core.AbstractComponent
import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.enums.css.CssUnit
import com.nexustools.genericui.enums.window.TitleBarControlsPlacement
import com.nexustools.genericui.geometry.Rect
import kotlinx.cinterop.CValue
import kotlinx.cinterop.useContents
import platform.AppKit.NSButton
import platform.AppKit.NSControl
import platform.AppKit.NSEvent
import platform.AppKit.NSTextField
import platform.AppKit.NSTextView
import platform.AppKit.NSView
import platform.AppKit.NSWindow
import platform.AppKit.NSWindowCloseButton
import platform.AppKit.NSWindowDocumentIconButton
import platform.AppKit.NSWindowMiniaturizeButton
import platform.AppKit.NSWindowZoomButton
import platform.Foundation.NSMakeRect
import platform.Foundation.NSPoint
import platform.Foundation.NSRect
import kotlin.math.roundToInt

internal class MacosTitleBarView(
    private val owner: DesktopBaseContainerNativeImpl<*>?,
    frameRect: CValue<NSRect> = NSMakeRect(0.0, 0.0, 0.0, 0.0)
) : NSView(frameRect) {

    private var mountedSlotViews: Set<NSView> = emptySet()

    init {
        setWantsLayer(true)
    }

    override fun isFlipped(): Boolean = true

    override fun acceptsFirstMouse(event: NSEvent?): Boolean = true

    override fun hitTest(point: CValue<NSPoint>): NSView? {
        if (isHidden() || alphaValue <= 0.004) return null
        val hit = super.hitTest(point)
        if (owner?.nativeWidget?.isSelfPointerEventsNone() == true && hit == this) {
            return null
        }
        return hit
    }

    internal fun syncAndLayout(
        windowImpl: MacosWindowNativeImpl,
        nsWindow: NSWindow,
        barWidthPx: Int,
        barHeightPx: Int,
        inFullscreen: Boolean
    ) {
        val tb = windowImpl.getTitleBarInstructions()
        val showWindowIcon = tb?.showWindowIcon != false
        (nsWindow as? MacosHostWindow)?.syncWindowDocumentIconVisibility(showWindowIcon)
            ?: nsWindow.standardWindowButton(NSWindowDocumentIconButton)?.let {
                it.hidden = !showWindowIcon
                it.alphaValue = if (showWindowIcon) 1.0 else 0.0
                it.enabled = showWindowIcon
            }
        if (tb == null || barWidthPx <= 0 || barHeightPx <= 0) {
            clearMountedSlots()
            return
        }
        val caps = windowImpl.getTitleCapabilities()
        val leadingImpls = resolveVisibleImpls(tb.leadingComponents)
        val centerImpls = resolveVisibleImpls(tb.centerComponents)
        val trailingImpls = resolveVisibleImpls(tb.trailingComponents)
        if (leadingImpls.isEmpty() && centerImpls.isEmpty() && trailingImpls.isEmpty()) {
            clearMountedSlots()
            return
        }

        val placement = when (tb.controlsPlacement) {
            TitleBarControlsPlacement.PLATFORM_DEFAULT -> caps.defaultControlsPlacement
            else -> tb.controlsPlacement
        }
        val showControls = !inFullscreen && !windowImpl.isUndecorated() &&
            (tb.showCloseButton || tb.showMinimizeButton || tb.showMaximizeButton)
        val trafficReservation = if (showControls) {
            measureTrafficLightWidth(nsWindow)
        } else {
            10
        }
        val leftStart = if (showControls && placement != TitleBarControlsPlacement.TRAILING) {
            trafficReservation
        } else {
            10
        }
        val rightLimit = if (showControls && placement == TitleBarControlsPlacement.TRAILING) {
            (barWidthPx - trafficReservation).coerceAtLeast(leftStart)
        } else {
            (barWidthPx - 10).coerceAtLeast(leftStart)
        }

        val nextViews = LinkedHashSet<NSView>()
        val trailStart = layoutTrailingSlot(trailingImpls, leftStart, rightLimit, barHeightPx, nextViews)
        val centerLeft = if (centerImpls.isNotEmpty()) {
            val maxRight = (trailStart - 8).coerceAtLeast(leftStart)
            layoutCenterSlot(centerImpls, barWidthPx, leftStart, maxRight, barHeightPx, nextViews)
        } else {
            (trailStart - if (trailingImpls.isNotEmpty()) 8 else 0).coerceAtLeast(leftStart)
        }
        layoutLeadingSlot(leadingImpls, leftStart, centerLeft, barHeightPx, nextViews)

        for (prev in mountedSlotViews) {
            if (prev !in nextViews && prev.superview === this) {
                prev.removeFromSuperview()
            }
        }
        mountedSlotViews = nextViews
    }

    private fun clearMountedSlots() {
        for (prev in mountedSlotViews) {
            if (prev.superview === this) {
                prev.removeFromSuperview()
            }
        }
        mountedSlotViews = emptySet()
    }

    private fun measureTrafficLightWidth(nsWindow: NSWindow): Int {
        var maxRight = 0
        for (btnType in listOf(NSWindowCloseButton, NSWindowMiniaturizeButton, NSWindowZoomButton)) {
            val btn = nsWindow.standardWindowButton(btnType) ?: continue
            if (btn.isHidden()) continue
            val right = btn.frame.useContents { origin.x + size.width }.roundToInt()
            if (right > maxRight) maxRight = right
        }
        return if (maxRight > 0) (maxRight + 14).coerceAtLeast(78) else 78
    }

    private fun resolveVisibleImpls(components: List<AbstractComponent>): List<Pair<AbstractComponent, DesktopBaseNativeImpl<*>>> {
        if (components.isEmpty()) return emptyList()
        val out = ArrayList<Pair<AbstractComponent, DesktopBaseNativeImpl<*>>>(components.size)
        for (comp in components) {
            if (!comp.isVisible) continue
            val impl = comp.ensureNativeImpl() as? DesktopBaseNativeImpl<*> ?: continue
            if (impl.isDisposed) continue
            out.add(comp to impl)
        }
        return out
    }

    private fun layoutTrailingSlot(
        items: List<Pair<AbstractComponent, DesktopBaseNativeImpl<*>>>,
        minLeft: Int,
        maxRight: Int,
        barH: Int,
        mountedOut: MutableSet<NSView>
    ): Int {
        if (items.isEmpty()) return maxRight
        var rightCursor = maxRight
        for (i in items.indices.reversed()) {
            val (_, impl) = items[i]
            val avail = (rightCursor - minLeft).coerceAtLeast(0)
            if (avail <= 0) break
            val pref = impl.measureIntrinsicSize(avail.toFloat(), barH.toFloat())
            val cw = pref.width.coerceIn(16.coerceAtMost(avail), avail)
            val ch = resolveSlotItemHeight(impl, pref.height, barH)
            val cx = (rightCursor - cw).coerceAtLeast(minLeft)
            val cy = ((barH - ch) / 2).coerceAtLeast(0)
            placeMountedSlotItem(impl, cx, cy, cw, ch, mountedOut)
            rightCursor = (cx - SLOT_GAP_PX).coerceAtLeast(minLeft)
        }
        return rightCursor
    }

    private fun layoutCenterSlot(
        items: List<Pair<AbstractComponent, DesktopBaseNativeImpl<*>>>,
        barW: Int,
        minLeft: Int,
        maxRight: Int,
        barH: Int,
        mountedOut: MutableSet<NSView>
    ): Int {
        val availSpan = (maxRight - minLeft).coerceAtLeast(0)
        if (items.isEmpty() || availSpan <= 0) return maxRight
        val widths = IntArray(items.size)
        val heights = IntArray(items.size)
        var totalW = 0
        for ((idx, pair) in items.withIndex()) {
            val pref = pair.second.measureIntrinsicSize(availSpan.toFloat(), barH.toFloat())
            widths[idx] = pref.width.coerceIn(16, availSpan)
            heights[idx] = resolveSlotItemHeight(pair.second, pref.height, barH)
            totalW += widths[idx]
            if (idx > 0) totalW += SLOT_GAP_PX
        }
        val clampedTotal = totalW.coerceAtMost(availSpan)
        var cursorX = ((barW - clampedTotal) / 2).coerceIn(minLeft, (maxRight - clampedTotal).coerceAtLeast(minLeft))
        val groupLeft = cursorX
        for ((idx, pair) in items.withIndex()) {
            val rem = (maxRight - cursorX).coerceAtLeast(0)
            if (rem <= 0) break
            val cw = widths[idx].coerceAtMost(rem)
            val ch = heights[idx]
            val cy = ((barH - ch) / 2).coerceAtLeast(0)
            placeMountedSlotItem(pair.second, cursorX, cy, cw, ch, mountedOut)
            cursorX += cw + SLOT_GAP_PX
        }
        return (groupLeft - SLOT_GAP_PX).coerceAtLeast(minLeft)
    }

    private fun layoutLeadingSlot(
        items: List<Pair<AbstractComponent, DesktopBaseNativeImpl<*>>>,
        leftStart: Int,
        maxRight: Int,
        barH: Int,
        mountedOut: MutableSet<NSView>
    ) {
        if (items.isEmpty()) return
        val availSpan = (maxRight - leftStart).coerceAtLeast(0)
        if (availSpan <= 0) return

        val expanding = BooleanArray(items.size)
        val measuredWidths = IntArray(items.size)
        val heights = IntArray(items.size)
        var expandingCount = 0
        var fixedWidthSum = 0
        for ((idx, pair) in items.withIndex()) {
            val (comp, impl) = pair
            val pref = impl.measureIntrinsicSize(availSpan.toFloat(), barH.toFloat())
            heights[idx] = resolveSlotItemHeight(impl, pref.height, barH)
            if (isExpandingSlotComponent(comp, impl)) {
                expanding[idx] = true
                expandingCount++
            } else {
                val cw = pref.width.coerceIn(16.coerceAtMost(availSpan), availSpan)
                measuredWidths[idx] = cw
                fixedWidthSum += cw
            }
        }
        val gapTotal = SLOT_GAP_PX * (items.size - 1).coerceAtLeast(0)
        val expandBudget = (availSpan - fixedWidthSum - gapTotal).coerceAtLeast(0)
        var remainingExpand = expandBudget
        var expandOrdinal = 0
        var cursorX = leftStart
        for ((idx, pair) in items.withIndex()) {
            val rem = (maxRight - cursorX).coerceAtLeast(0)
            if (rem <= 0) break
            val cw = if (expanding[idx]) {
                expandOrdinal++
                val share = if (expandOrdinal == expandingCount) {
                    remainingExpand
                } else {
                    (expandBudget / expandingCount.coerceAtLeast(1)).also { remainingExpand -= it }
                }
                share.coerceIn(0, rem)
            } else {
                measuredWidths[idx].coerceAtMost(rem)
            }
            val ch = heights[idx]
            val cy = ((barH - ch) / 2).coerceAtLeast(0)
            placeMountedSlotItem(pair.second, cursorX, cy, cw, ch, mountedOut)
            cursorX += cw + SLOT_GAP_PX
        }
    }

    private fun resolveSlotItemHeight(impl: DesktopBaseNativeImpl<*>, prefHeight: Int, barH: Int): Int {
        if (impl is DesktopBaseContainerNativeImpl<*>) {
            return (barH - 4).coerceAtLeast(24).coerceAtMost(barH)
        }
        return prefHeight.coerceIn(20.coerceAtMost(barH), barH)
    }

    private fun isExpandingSlotComponent(comp: AbstractComponent, impl: DesktopBaseNativeImpl<*>): Boolean {
        val computed = comp.computedStyle
        val style = comp.style
        val widthStr = computed?.width?.trim()?.lowercase()
            ?: (impl.nativeWidget.attributes["styleWidth"] as? String)?.trim()?.lowercase()
        if (widthStr == "100%") return true
        val compWidth = style?.getWidth()
        if (compWidth != null && compWidth.unit == CssUnit.PERCENT && compWidth.value >= 99.9f) return true
        if ((computed?.flexGrow ?: 0f) > 0f || (style?.getFlexGrow() ?: 0f) > 0f) return true
        val tokens = impl.nativeWidget.cachedAllClassTokens
        return "w-100" in tokens || "flex-grow-1" in tokens || "flex-fill" in tokens
    }

    private fun placeMountedSlotItem(
        impl: DesktopBaseNativeImpl<*>,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        mountedOut: MutableSet<NSView>
    ) {
        val rect = Rect(x, y, w, h)
        impl.setBounds(rect)
        if (impl is DesktopBaseContainerNativeImpl<*>) {
            impl.layoutChildrenInBounds(w.toFloat(), h.toFloat())
        }
        val childView = MacosAppKitDispatch.resolveOrCreateChildNsView(impl) ?: return
        if (childView.superview !== this) {
            addSubview(childView)
        }
        MacosAppKitDispatch.applyFrameAndTransform(
            view = childView,
            bounds = rect,
            transform = (impl.nativeWidget as? MacosViewHandle)?.cssTransform,
            useAlignmentRectExpansion = impl.usesNativeControlAlignmentExpansion(),
            knownHandle = impl.nativeWidget as? MacosViewHandle
        )
        mountedOut.add(childView)
    }

    override fun mouseDown(event: NSEvent) {
        val localPt = convertPoint(event.locationInWindow, fromView = null)
        val hit = hitTest(superview?.convertPoint(localPt, fromView = this) ?: localPt)
        if (hit != null && hit != this && isInteractiveControlView(hit)) {
            super.mouseDown(event)
            return
        }
        if (event.clickCount >= 2L) {
            (window as? MacosHostWindow)?.handleTitleBarDoubleClick() ?: window?.performZoom(null)
            return
        }
        val win = window
        if (win != null) {
            MacosNativeWindowHost.performWindowDragWithCursorLock(win, event)
        } else {
            super.mouseDown(event)
        }
    }

    private fun isInteractiveControlView(view: NSView): Boolean {
        var cur: NSView? = view
        while (cur != null && cur != this) {
            if (cur is NSButton || cur is MacosStyledButtonControl || cur is NSControl) {
                if (cur is NSTextField && !cur.isEditable()) {
                    val handle = MacosFocusSupport.directHandleForView(cur)
                    val impl = handle?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>
                    if (impl != null && impl.hasClickListener()) return true
                    cur = cur.superview
                    continue
                }
                return true
            }
            if (cur is NSTextView && cur.isEditable()) {
                return true
            }
            val handle = MacosFocusSupport.directHandleForView(cur)
            val impl = handle?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>
            if (impl != null && impl.hasClickListener()) {
                return true
            }
            cur = cur.superview
        }
        return false
    }

    private companion object {
        const val SLOT_GAP_PX: Int = 6
    }
}
