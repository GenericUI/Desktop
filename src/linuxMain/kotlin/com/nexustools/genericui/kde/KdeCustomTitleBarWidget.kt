@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.core.AbstractComponent
import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.enums.window.TitleBarControlsPlacement
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.linux.titlebar.LinuxOverlayTitleBarResolver
import com.nexustools.genericui.linux.titlebar.LinuxTitleBarButtonHit
import com.nexustools.genericui.linux.titlebar.LinuxTitleBarLayoutEngine
import com.nexustools.genericui.linux.titlebar.LinuxTitleBarLayoutResult
import com.nexustools.genericui.linux.titlebar.LinuxTitleBarProfile
import com.nexustools.genericui.linux.titlebar.LinuxTitleBarProfileManager
import com.nexustools.genericui.linux.titlebar.LinuxTitleBarRenderer
import com.nexustools.genericui.linux.titlebar.LinuxTitleBarWindowActions
import com.nexustools.genericui.linux.titlebar.LinuxTitleButtonKind
import com.nexustools.genericui.window.TitleBar
import kotlinx.cinterop.COpaquePointer
import kotlin.math.roundToInt

/**
 * Retained KDE custom titlebar `QWidget` painting active desktop titlebar chrome (Breeze / Yaru /
 * Libadwaita / Mint-Y / Xfwm4 / Marco via [LinuxTitleBarRenderer]) and hosting embedded [TitleBar]
 * components (`leadingComponents`, `centerComponents`, `trailingComponents`) as real child `QWidget`s.
 *
 * Collapses cleanly to `0px` height when `customTitleBar == false`, fullscreen, undecorated, or when the
 * active window manager does not support client-side decorations.
 */
open class KdeCustomTitleBarWidget(
    private val windowImpl: KdeWindowNativeImpl,
    parentWindowPtr: COpaquePointer? = null
) {
    val handle: KdeWidgetHandle = KdeWidgetHandle("KdeCustomTitleBarWidget").also { h ->
        val ptr = KdeDynamicBindings.createQWidget(parentWindowPtr, 0)
        h.desiredParentPtr = parentWindowPtr
        h.attachToQWidget(ptr, "KdeCustomTitleBarWidget", customPainted = true)
        KdeDynamicBindings.setWidgetAttribute(ptr, KdeDynamicBindings.WA_HOVER, true)
    }

    private var hoveredButton: LinuxTitleButtonKind? = null
    private var pressedButton: LinuxTitleButtonKind? = null
    private var lastLayout: LinuxTitleBarLayoutResult? = null
    private var lastWidthPx: Int = 0
    private var lastHeightPx: Int = 0
    private val mountedEmbeddedChildren = LinkedHashSet<DesktopBaseNativeImpl<*>>()

    init {
        val ptr = handle.qWidgetPtr
        if (ptr != null) {
            KdeVTableBridge.attachWidgetHooks(
                ptr,
                KdeVTableBridge.HOOK_PAINT or KdeVTableBridge.HOOK_RESIZE or KdeVTableBridge.HOOK_INPUT
            ) {
                onPaint = { _, painter, w, h ->
                    paintInto(painter, w, h)
                }
                onMouseMove = { wPtr, info ->
                    val layout = lastLayout
                    val nextHover = if (layout != null) {
                        LinuxTitleBarLayoutEngine.hitTestButton(layout, info.localX.roundToInt(), info.localY.roundToInt())?.kind
                    } else null
                    if (nextHover != hoveredButton) {
                        hoveredButton = nextHover
                        KdeDynamicBindings.updateWidget(wPtr)
                    }
                    false
                }
                onMousePress = { wPtr, info ->
                    val layout = lastLayout
                    val hit = if (layout != null) {
                        LinuxTitleBarLayoutEngine.hitTestButton(layout, info.localX.roundToInt(), info.localY.roundToInt())
                    } else null
                    if (info.button == 2) {
                        if (hit == null && layout != null && LinuxTitleBarLayoutEngine.isDraggableBackground(
                                layout = layout,
                                x = info.localX.roundToInt(),
                                y = info.localY.roundToInt(),
                                occupiedWidgetRects = collectEmbeddedBounds()
                            )
                        ) {
                            buildWindowActions().onShowWindowMenu(info.globalX.roundToInt(), info.globalY.roundToInt())
                            true
                        } else {
                            false
                        }
                    } else if (info.button == 1) {
                        if (hit != null) {
                            pressedButton = hit.kind
                            KdeDynamicBindings.updateWidget(wPtr)
                            true
                        } else if (layout != null && LinuxTitleBarLayoutEngine.isDraggableBackground(
                                layout = layout,
                                x = info.localX.roundToInt(),
                                y = info.localY.roundToInt(),
                                occupiedWidgetRects = collectEmbeddedBounds()
                            )
                        ) {
                            windowImpl.startSystemMove()
                            true
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                }
                onMouseRelease = { wPtr, info ->
                    val prevPressed = pressedButton
                    pressedButton = null
                    if (info.button == 1 && prevPressed != null) {
                        val layout = lastLayout
                        val hit = if (layout != null) {
                            LinuxTitleBarLayoutEngine.hitTestButton(layout, info.localX.roundToInt(), info.localY.roundToInt())
                        } else null
                        if (hit != null && hit.kind == prevPressed) {
                            LinuxTitleBarLayoutEngine.dispatchButtonAction(prevPressed, buildWindowActions(), info.globalX.roundToInt(), info.globalY.roundToInt())
                        }
                        KdeDynamicBindings.updateWidget(wPtr)
                        true
                    } else {
                        false
                    }
                }
                onMouseDoubleClick = { _, info ->
                    val layout = lastLayout
                    if (info.button == 1 && layout != null && LinuxTitleBarLayoutEngine.isDraggableBackground(
                            layout = layout,
                            x = info.localX.roundToInt(),
                            y = info.localY.roundToInt(),
                            occupiedWidgetRects = collectEmbeddedBounds()
                        )
                    ) {
                        buildWindowActions().onDoubleClickMaximize()
                        true
                    } else {
                        false
                    }
                }
                onLeave = { wPtr ->
                    if (hoveredButton != null || pressedButton != null) {
                        hoveredButton = null
                        pressedButton = null
                        KdeDynamicBindings.updateWidget(wPtr)
                    }
                }
            }
        }
    }

    fun resolveVisibleHeightPx(): Int {
        val profile = LinuxTitleBarProfileManager.currentProfile()
        val caps = windowImpl.getTitleCapabilities()
        val tb = windowImpl.getTitleBarInstructions()
        val active = !windowImpl.isUndecorated() &&
            !windowImpl.isFullscreen() &&
            profile.isReplicable &&
            caps.supportsCustomTitleBar &&
            (tb?.customTitleBar == true)
        if (!active) return 0
        val requested = tb?.heightPx
        return (requested ?: profile.metrics.heightPx).coerceAtLeast(24)
    }

    fun syncAndLayout(hostWidthPx: Int): Int {
        val barH = resolveVisibleHeightPx()
        val ptr = handle.qWidgetPtr ?: return 0
        lastWidthPx = hostWidthPx.coerceAtLeast(0)
        lastHeightPx = barH
        if (barH <= 0 || hostWidthPx <= 0) {
            handle.syncVisibilityToNative(false)
            handle.applyBoundsToNative(Rect(0f, 0f, 0f, 0f))
            lastLayout = null
            unmountRemovedEmbeddedChildren(emptySet())
            return 0
        }

        handle.syncVisibilityToNative(true)
        handle.applyBoundsToNative(Rect(0f, 0f, hostWidthPx.toFloat(), barH.toFloat()))

        val tb = windowImpl.getTitleBarInstructions()
        val profile = LinuxTitleBarLayoutEngine.applyControlsPlacement(
            LinuxTitleBarProfileManager.currentProfile(),
            tb?.controlsPlacement ?: TitleBarControlsPlacement.PLATFORM_DEFAULT
        )
        val leadingComps = tb?.leadingComponents.orEmpty()
        val centerComps = tb?.centerComponents.orEmpty()
        val trailingComps = tb?.trailingComponents.orEmpty()

        val leadW = measureSlotWidth(leadingComps, barH)
        val centerW = measureSlotWidth(centerComps, barH)
        val trailW = measureSlotWidth(trailingComps, barH)
        val expandLeading = leadingComps.any { comp ->
            val impl = comp.nativeImpl as? DesktopBaseNativeImpl<*> ?: return@any false
            LinuxOverlayTitleBarResolver.isExpandingTitleBarComponent(impl.nativeWidget, comp)
        }

        val layout = LinuxTitleBarLayoutEngine.computeLayout(
            profile = profile,
            barBounds = Rect(0f, 0f, hostWidthPx.toFloat(), barH.toFloat()),
            isCompact = tb?.embedMenuBar == false && tb?.heightPx != null && (tb.heightPx ?: barH) < profile.metrics.heightPx,
            showMinimize = tb?.showMinimizeButton ?: true,
            showMaximize = tb?.showMaximizeButton ?: true,
            showClose = tb?.showCloseButton ?: true,
            leadingWidthPx = leadW,
            centerWidthPx = centerW,
            trailingWidthPx = trailW,
            expandLeadingToFill = expandLeading,
            showWindowIcon = tb?.showWindowIcon ?: true
        )
        lastLayout = layout

        val nextMounted = LinkedHashSet<DesktopBaseNativeImpl<*>>()
        layoutEmbeddedGroup(leadingComps, layout.leadingSlotBounds, nextMounted, allowExpand = true)
        layoutEmbeddedGroup(centerComps, layout.centerSlotBounds, nextMounted, allowExpand = false)
        layoutEmbeddedGroup(trailingComps, layout.trailingSlotBounds, nextMounted, allowExpand = false)
        unmountRemovedEmbeddedChildren(nextMounted)
        KdeDynamicBindings.updateWidget(ptr)
        return barH
    }

    private fun measureSlotWidth(components: List<AbstractComponent>, barH: Int): Int {
        if (components.isEmpty()) return 0
        var total = 0
        for ((idx, comp) in components.withIndex()) {
            val impl = comp.nativeImpl as? DesktopBaseNativeImpl<*> ?: continue
            val pref = impl.measureIntrinsicSize(null, barH.toFloat())
            total += pref.width.coerceAtLeast(24)
            if (idx > 0) total += 6
        }
        return total
    }

    private fun layoutEmbeddedGroup(
        components: List<AbstractComponent>,
        slotBounds: Rect,
        mountedOut: MutableSet<DesktopBaseNativeImpl<*>>,
        allowExpand: Boolean = false
    ) {
        if (components.isEmpty() || slotBounds.width <= 0 || slotBounds.height <= 0) return
        val resolved = ArrayList<Triple<AbstractComponent, DesktopBaseNativeImpl<*>, Boolean>>(components.size)
        for (comp in components) {
            if (!comp.isVisible) continue
            val impl = comp.nativeImpl as? DesktopBaseNativeImpl<*> ?: continue
            val expanding = allowExpand && LinuxOverlayTitleBarResolver.isExpandingTitleBarComponent(impl.nativeWidget, comp)
            resolved.add(Triple(comp, impl, expanding))
        }
        if (resolved.isEmpty()) return

        val slotH = slotBounds.height.coerceAtLeast(18)
        val fixedWidths = IntArray(resolved.size)
        val prefHeights = IntArray(resolved.size)
        var fixedSum = 0
        var expandingCount = 0
        for ((idx, triple) in resolved.withIndex()) {
            val impl = triple.second
            val pref = impl.measureIntrinsicSize(slotBounds.width.toFloat(), slotH.toFloat())
            prefHeights[idx] = pref.height
            if (triple.third) {
                expandingCount++
            } else {
                val fw = pref.width.coerceIn(16, slotBounds.width.coerceAtLeast(16))
                fixedWidths[idx] = fw
                fixedSum += fw
            }
        }
        val gapTotal = (resolved.size - 1).coerceAtLeast(0) * 6
        val expandBudget = (slotBounds.width - fixedSum - gapTotal).coerceAtLeast(0)
        var remainingExpand = expandBudget
        var expandIdx = 0

        var cursorX = slotBounds.x
        val maxRight = slotBounds.x + slotBounds.width
        for ((idx, triple) in resolved.withIndex()) {
            val impl = triple.second
            mountedOut.add(impl)
            mountedEmbeddedChildren.add(impl)
            val kh = impl.nativeWidget as? KdeWidgetHandle
            if (kh != null && kh.desiredParentPtr !== handle.qWidgetPtr) {
                kh.syncChildParentAndVisibility(handle)
            }
            val availRight = (maxRight - cursorX).coerceAtLeast(16)
            val cw = if (triple.third) {
                expandIdx++
                val share = if (expandIdx == expandingCount) {
                    remainingExpand
                } else {
                    (expandBudget / expandingCount.coerceAtLeast(1)).also { remainingExpand -= it }
                }
                share.coerceIn(16.coerceAtMost(availRight), availRight)
            } else {
                fixedWidths[idx].coerceIn(16.coerceAtMost(availRight), availRight)
            }
            val ch = if (impl is DesktopBaseContainerNativeImpl<*>) {
                (slotH - 4).coerceAtLeast(24).coerceAtMost(slotH)
            } else {
                prefHeights[idx].coerceIn(16.coerceAtMost(slotH), slotH)
            }
            val cy = slotBounds.y + (slotH - ch) / 2
            impl.setBounds(Rect(cursorX, cy, cw, ch))
            if (impl is DesktopBaseContainerNativeImpl<*>) {
                impl.layoutChildrenInBounds(cw.toFloat(), ch.toFloat())
            }
            cursorX += cw + 6
        }
    }

    private fun unmountRemovedEmbeddedChildren(keep: Set<DesktopBaseNativeImpl<*>>) {
        val iter = mountedEmbeddedChildren.iterator()
        while (iter.hasNext()) {
            val prev = iter.next()
            if (prev !in keep) {
                (prev.nativeWidget as? KdeWidgetHandle)?.syncChildParentAndVisibility(null)
                iter.remove()
            }
        }
    }

    private fun collectEmbeddedBounds(): List<Rect> =
        mountedEmbeddedChildren.mapNotNull { impl ->
            (impl.nativeWidget as? KdeWidgetHandle)?.bounds?.takeIf { it.width > 0 && it.height > 0 }
        }

    private fun paintInto(painter: COpaquePointer, widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        val tb: TitleBar? = windowImpl.getTitleBarInstructions()
        val profile: LinuxTitleBarProfile = LinuxTitleBarLayoutEngine.applyControlsPlacement(
            LinuxTitleBarProfileManager.currentProfile(),
            tb?.controlsPlacement ?: TitleBarControlsPlacement.PLATFORM_DEFAULT
        )
        val layout = lastLayout ?: LinuxTitleBarLayoutEngine.computeLayout(
            profile = profile,
            barBounds = Rect(0f, 0f, widthPx.toFloat(), heightPx.toFloat()),
            showMinimize = tb?.showMinimizeButton ?: true,
            showMaximize = tb?.showMaximizeButton ?: true,
            showClose = tb?.showCloseButton ?: true,
            showWindowIcon = tb?.showWindowIcon ?: true
        )
        val titleText = if (tb?.showWindowTitle != false) windowImpl.getTitle() else ""
        val activeStates = linkedSetOf<LinuxTitleButtonKind>()
        if (windowImpl.isAlwaysOnTop()) activeStates.add(LinuxTitleButtonKind.KEEP_ABOVE)

        LinuxTitleBarRenderer.render(
            canvas = KdePainterSupport.KdeTitleBarPainterCanvas(painter),
            profile = profile,
            layout = layout,
            title = titleText,
            isActiveWindow = windowImpl.isFocusedState(),
            isMaximized = windowImpl.getWindowState() == com.nexustools.genericui.enums.window.WindowState.MAXIMIZED,
            hoveredButton = hoveredButton,
            pressedButton = pressedButton,
            buttonStates = activeStates,
            opacity = windowImpl.getOpacity()
        )
    }

    private fun buildWindowActions(): LinuxTitleBarWindowActions = object : LinuxTitleBarWindowActions {
        override fun onClose() { windowImpl.closeWindow() }
        override fun onMinimize() { windowImpl.setWindowState(com.nexustools.genericui.enums.window.WindowState.MINIMIZED) }
        override fun onMaximizeOrRestore() {
            val next = if (windowImpl.getWindowState() == com.nexustools.genericui.enums.window.WindowState.MAXIMIZED) {
                com.nexustools.genericui.enums.window.WindowState.NORMAL
            } else {
                com.nexustools.genericui.enums.window.WindowState.MAXIMIZED
            }
            windowImpl.setWindowState(next)
        }
        override fun onShowWindowMenu(screenX: Int, screenY: Int) {
            KdeNativeWindowHost.showWindowMenu(windowImpl, screenX, screenY)
        }
        override fun onToggleKeepAbove() { windowImpl.setAlwaysOnTop(!windowImpl.isAlwaysOnTop()) }
        override fun onToggleKeepBelow() {}
        override fun onToggleAllDesktops() {}
        override fun onToggleShade() {}
        override fun onStartSystemMove() { windowImpl.startSystemMove() }
        override fun onDoubleClickMaximize() { onMaximizeOrRestore() }
    }

    internal fun hoveredButtonKind(): LinuxTitleButtonKind? = hoveredButton

    internal fun updateHoverAt(x: Int, y: Int): LinuxTitleButtonKind? {
        val layout = lastLayout ?: return null
        val next = LinuxTitleBarLayoutEngine.hitTestButton(layout, x, y)?.kind
        hoveredButton = next
        handle.requestRepaint()
        return next
    }

    fun dispose() {
        unmountRemovedEmbeddedChildren(emptySet())
        handle.destroyQWidget()
    }
}
