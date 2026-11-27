@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.titlebar

import com.nexustools.genericui.core.AbstractComponent
import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.shell.DesktopMenuBarNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopWindowNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopButtonNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTextNativeImpl
import com.nexustools.genericui.enums.window.WindowState
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.gtk.GtkNativeWindowHost
import com.nexustools.genericui.gtk.GtkWindowNativeImpl
import com.nexustools.genericui.gtk.widget.GtkRetainedContainerSupport
import com.nexustools.genericui.gtk.widget.GtkSurfaceRenderHelper
import com.nexustools.genericui.linux.LinuxX11WindowHintsBridge
import com.nexustools.genericui.linux.titlebar.LinuxOverlayTitleBarResolver
import com.nexustools.genericui.linux.titlebar.LinuxTitleBarButtonHit
import com.nexustools.genericui.linux.titlebar.LinuxTitleBarLayoutEngine
import com.nexustools.genericui.linux.titlebar.LinuxTitleBarLayoutResult
import com.nexustools.genericui.linux.titlebar.LinuxTitleBarProfile
import com.nexustools.genericui.linux.titlebar.LinuxTitleBarProfileManager
import com.nexustools.genericui.linux.titlebar.LinuxTitleBarRenderer
import com.nexustools.genericui.linux.titlebar.LinuxTitleBarWindowActions
import com.nexustools.genericui.linux.titlebar.LinuxTitleButtonKind
import com.nexustools.genericui.threading.GenericUiThreading
import com.nexustools.genericui.window.TitleBar
import com.nexustools.genericui.enums.window.TitleBarControlsPlacement
import kotlinx.cinterop.COpaquePointer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * GTK4 retained titlebar state, layout, hover/press/click dispatch, and X11/GDK window action controller.
 */
object GtkWindowTitleBarController {

    private class WindowTitleState {
        var hoveredButton: LinuxTitleButtonKind? = null
        var pressedButton: LinuxTitleButtonKind? = null
        val activeStates: MutableSet<LinuxTitleButtonKind> = linkedSetOf()
        var latestLayout: LinuxTitleBarLayoutResult? = null
        var lastEffectiveTitleBarHeight: Int = 0
        var activeSlotWidgets: List<Pair<DesktopNativeWidget, Rect>> = emptyList()
    }

    private val stateByWindow = LinkedHashMap<Long, WindowTitleState>(8)
    private val surfaceHoverByKey = LinkedHashMap<Long, LinuxTitleButtonKind>(16)
    private val surfacePressByKey = LinkedHashMap<Long, LinuxTitleButtonKind>(16)
    private val surfaceButtonsByKey = LinkedHashMap<Long, List<LinuxTitleBarButtonHit>>(16)

    private fun stateFor(windowKey: Long): WindowTitleState =
        stateByWindow.getOrPut(windowKey) { WindowTitleState() }

    fun beginFrame(windowKey: Long) {
        val st = stateFor(windowKey)
        st.activeSlotWidgets = emptyList()
    }

    fun clearWindowState(windowKey: Long) {
        stateByWindow.remove(windowKey)
    }

    fun registerSurfaceButtons(surfaceKey: Long, buttons: List<LinuxTitleBarButtonHit>) {
        surfaceButtonsByKey[surfaceKey] = buttons
    }

    fun getSurfaceHoveredButton(surfaceKey: Long): LinuxTitleButtonKind? = surfaceHoverByKey[surfaceKey]

    fun getSurfacePressedButton(surfaceKey: Long): LinuxTitleButtonKind? = surfacePressByKey[surfaceKey]

    fun setSurfaceHoveredButton(surfaceKey: Long, kind: LinuxTitleButtonKind?) {
        if (kind == null) surfaceHoverByKey.remove(surfaceKey) else surfaceHoverByKey[surfaceKey] = kind
    }

    fun setSurfacePressedButton(surfaceKey: Long, kind: LinuxTitleButtonKind?) {
        if (kind == null) surfacePressByKey.remove(surfaceKey) else surfacePressByKey[surfaceKey] = kind
    }

    fun updateSurfaceHoverAt(surfaceKey: Long, x: Int, y: Int): Boolean {
        val prev = surfaceHoverByKey[surfaceKey]
        val hit = surfaceButtonsByKey[surfaceKey]?.firstOrNull { b ->
            x >= b.bounds.x && x < b.bounds.right && y >= b.bounds.y && y < b.bounds.bottom
        }?.kind
        setSurfaceHoveredButton(surfaceKey, hit)
        return prev != hit
    }

    fun getTopLevelHoveredButton(windowKey: Long): LinuxTitleButtonKind? = stateByWindow[windowKey]?.hoveredButton

    fun getTopLevelPressedButton(windowKey: Long): LinuxTitleButtonKind? = stateByWindow[windowKey]?.pressedButton

    fun getLatestLayout(windowKey: Long): LinuxTitleBarLayoutResult? = stateByWindow[windowKey]?.latestLayout

    fun getEffectiveTitleBarHeight(windowKey: Long): Int = stateByWindow[windowKey]?.lastEffectiveTitleBarHeight ?: 0

    fun getLatestSlotWidgets(windowKey: Long): List<Pair<DesktopNativeWidget, Rect>> =
        stateByWindow[windowKey]?.activeSlotWidgets ?: emptyList()

    fun updateTopLevelHoverAt(windowKey: Long, x: Int, y: Int): Boolean {
        val st = stateFor(windowKey)
        val layout = st.latestLayout
        val next = layout?.let { LinuxTitleBarLayoutEngine.hitTestButton(it, x, y)?.kind }
        val changed = st.hoveredButton != next
        st.hoveredButton = next
        return changed
    }

    fun clearHoverAndPress(windowKey: Long) {
        val st = stateByWindow[windowKey] ?: return
        st.hoveredButton = null
        st.pressedButton = null
    }

    fun isCustomTitleBarActive(windowImpl: DesktopWindowNativeImpl<*>): Boolean {
        if (windowImpl.isUndecorated() || windowImpl.isFullscreen()) return false
        val profile = LinuxTitleBarProfileManager.currentProfile()
        if (!profile.isReplicable) return false
        val titleBar = windowImpl.getTitleBarInstructions() ?: return false
        return titleBar.customTitleBar
    }

    fun resolveProfileForWindow(windowImpl: DesktopWindowNativeImpl<*>): LinuxTitleBarProfile {
        val dark = GtkRetainedContainerSupport.resolveDarkTheme()
        val base = LinuxOverlayTitleBarResolver.switchProfileTheme(
            LinuxTitleBarProfileManager.currentProfile(),
            dark
        )
        val placement = windowImpl.getTitleBarInstructions()?.controlsPlacement ?: TitleBarControlsPlacement.PLATFORM_DEFAULT
        return applyControlsPlacement(base, placement)
    }

    fun computeAndApplySlotLayout(
        windowImpl: DesktopWindowNativeImpl<*>,
        widthPx: Int
    ): LinuxTitleBarLayoutResult? {
        val windowKey = windowImpl.nativeWidget.nativePointer
        val st = stateFor(windowKey)
        if (!isCustomTitleBarActive(windowImpl)) {
            st.latestLayout = null
            st.lastEffectiveTitleBarHeight = 0
            st.activeSlotWidgets = emptyList()
            return null
        }
        val titleBar = windowImpl.getTitleBarInstructions() ?: return null
        val profile = resolveProfileForWindow(windowImpl)
        val barH = titleBar.heightPx ?: profile.metrics.heightPx

        val leadingWidgets = resolveVisibleSlotWidgets(titleBar.leadingComponents)
        val centerWidgets = resolveVisibleSlotWidgets(titleBar.centerComponents)
        val trailingWidgets = resolveVisibleSlotWidgets(titleBar.trailingComponents)
        val menuBarImpl = if (titleBar.embedMenuBar) {
            windowImpl.nativeWidget.attributes["menuBar"] as? DesktopMenuBarNativeImpl<*>
        } else {
            null
        }

        val leadingW = computeSlotGroupWidth(leadingWidgets, menuBarImpl)
        val centerW = computeSlotGroupWidth(centerWidgets, null)
        val trailingW = computeSlotGroupWidth(trailingWidgets, null)
        val expandLeading = leadingWidgets.any { LinuxOverlayTitleBarResolver.isExpandingTitleBarComponent(it) }

        val allowMinimize = windowImpl.nativeWidget.attributes["minimizable"]?.toString() != "false"
        val allowMaximize = windowImpl.nativeWidget.attributes["maximizable"]?.toString() != "false"
        val allowClose = windowImpl.nativeWidget.attributes["closable"]?.toString() != "false"

        val layout = LinuxTitleBarLayoutEngine.computeLayout(
            profile = profile,
            barBounds = Rect(0, 0, widthPx.coerceAtLeast(1), barH),
            isCompact = false,
            showMinimize = titleBar.showMinimizeButton && allowMinimize,
            showMaximize = titleBar.showMaximizeButton && allowMaximize,
            showClose = titleBar.showCloseButton && allowClose,
            leadingWidthPx = leadingW,
            centerWidthPx = centerW,
            trailingWidthPx = trailingW,
            expandLeadingToFill = expandLeading,
            showWindowIcon = titleBar.showWindowIcon
        )

        val positioned = mutableListOf<Pair<DesktopNativeWidget, Rect>>()
        placeSlotGroup(leadingWidgets, menuBarImpl, layout.leadingSlotBounds, alignRight = false, positioned)
        placeSlotGroup(centerWidgets, null, layout.centerSlotBounds, alignRight = false, positioned)
        placeSlotGroup(trailingWidgets, null, layout.trailingSlotBounds, alignRight = true, positioned)

        st.latestLayout = layout
        st.lastEffectiveTitleBarHeight = layout.barBounds.height
        st.activeSlotWidgets = positioned
        return layout
    }

    fun renderTopLevelTitleBarToCairo(
        cr: COpaquePointer,
        windowImpl: DesktopWindowNativeImpl<*>,
        widthPx: Int,
        heightPx: Int
    ): LinuxTitleBarLayoutResult? {
        val layout = computeAndApplySlotLayout(windowImpl, widthPx) ?: return null
        val windowKey = windowImpl.nativeWidget.nativePointer
        val st = stateFor(windowKey)
        val profile = resolveProfileForWindow(windowImpl)
        val titleBar = windowImpl.getTitleBarInstructions()
        val isActive = resolveActiveWindowFlag(windowImpl)
        val isMax = windowImpl.getWindowState() == WindowState.MAXIMIZED
        val displayTitle = if (titleBar?.showWindowTitle != false) windowImpl.getTitle() else ""

        if (windowImpl.getWindowState() == WindowState.MAXIMIZED) {
            st.activeStates.add(LinuxTitleButtonKind.MAXIMIZE_OR_RESTORE)
        } else {
            st.activeStates.remove(LinuxTitleButtonKind.MAXIMIZE_OR_RESTORE)
        }

        val canvas = GtkTitleBarCanvas(cr)
        LinuxTitleBarRenderer.render(
            canvas = canvas,
            profile = profile,
            layout = layout,
            title = displayTitle,
            isActiveWindow = isActive,
            isMaximized = isMax,
            hoveredButton = st.hoveredButton,
            pressedButton = st.pressedButton,
            buttonStates = st.activeStates
        )
        return layout
    }

    fun handlePointerPress(
        windowImpl: DesktopWindowNativeImpl<*>,
        x: Int,
        y: Int,
        nPress: Int,
        button: UInt = Gtk4DynamicBindings.GDK_BUTTON_PRIMARY,
        gdkEvent: COpaquePointer? = null
    ): Boolean {
        val key = windowImpl.nativeWidget.nativePointer
        val st = stateFor(key)
        val layout = st.latestLayout ?: return false
        val hit = LinuxTitleBarLayoutEngine.hitTestButton(layout, x, y)
        if (button == Gtk4DynamicBindings.GDK_BUTTON_SECONDARY) {
            if (hit != null) return false
            val slotHit = st.activeSlotWidgets.any { (_, r) ->
                x >= r.x && x < r.right && y >= r.y && y < r.bottom
            }
            if (slotHit) return false
            if (LinuxTitleBarLayoutEngine.isDraggableBackground(layout, x, y)) {
                val gtkWin = windowImpl as? GtkWindowNativeImpl
                if (gtkWin != null && GtkNativeWindowHost.showWindowMenu(gtkWin, gdkEvent, x, y)) {
                    return true
                }
                buildWindowActions(key, windowImpl).onShowWindowMenu(x, y)
                return true
            }
            return false
        }
        if (hit != null) {
            st.pressedButton = hit.kind
            return true
        }
        val slotHit = st.activeSlotWidgets.any { (_, r) ->
            x >= r.x && x < r.right && y >= r.y && y < r.bottom
        }
        if (slotHit) return false
        val allowMax = windowImpl.nativeWidget.attributes["maximizable"]?.toString() != "false"
        if (nPress >= 2) {
            buildWindowActions(key, windowImpl, allowMax).onDoubleClickMaximize()
            return true
        }
        if (LinuxTitleBarLayoutEngine.isDraggableBackground(layout, x, y)) {
            (windowImpl as? GtkWindowNativeImpl)?.let { GtkNativeWindowHost.startSystemMove(it) }
            return true
        }
        return false
    }

    fun handlePointerRelease(windowImpl: DesktopWindowNativeImpl<*>, x: Int, y: Int): Boolean {
        val key = windowImpl.nativeWidget.nativePointer
        val st = stateFor(key)
        val pressed = st.pressedButton
        st.pressedButton = null
        val layout = st.latestLayout ?: return false
        if (pressed != null) {
            val hit = LinuxTitleBarLayoutEngine.hitTestButton(layout, x, y)
            if (hit != null && hit.kind == pressed) {
                val allowMax = windowImpl.nativeWidget.attributes["maximizable"]?.toString() != "false"
                val actions = buildWindowActions(key, windowImpl, allowMax)
                LinuxTitleBarLayoutEngine.dispatchButtonAction(hit.kind, actions)
            }
            return true
        }
        return false
    }

    fun applyControlsPlacement(
        profile: LinuxTitleBarProfile,
        placement: TitleBarControlsPlacement
    ): LinuxTitleBarProfile =
        LinuxTitleBarLayoutEngine.applyControlsPlacement(profile, placement)

    fun resolveActiveWindowFlag(windowImpl: DesktopWindowNativeImpl<*>?): Boolean {
        if (windowImpl == null) return true
        return if (windowImpl.nativeWidget.attributes.containsKey("focused")) {
            windowImpl.isFocusedState()
        } else {
            true
        }
    }

    fun buildWindowActions(
        windowKey: Long,
        windowImpl: DesktopWindowNativeImpl<*>?,
        allowMaximize: Boolean = true
    ): LinuxTitleBarWindowActions {
        val state = stateFor(windowKey)
        return object : LinuxTitleBarWindowActions {
            override fun onClose() {
                windowImpl ?: return
                GenericUiThreading.runOnLogic {
                    windowImpl.closeWindow()
                }
            }

            override fun onMinimize() {
                windowImpl ?: return
                GenericUiThreading.runOnLogic {
                    windowImpl.setWindowState(WindowState.MINIMIZED)
                }
            }

            override fun onMaximizeOrRestore() {
                if (!allowMaximize || windowImpl == null) return
                val next = if (windowImpl.getWindowState() == WindowState.MAXIMIZED) {
                    WindowState.NORMAL
                } else {
                    WindowState.MAXIMIZED
                }
                GenericUiThreading.runOnLogic {
                    windowImpl.setWindowState(next)
                }
            }

            override fun onShowWindowMenu(screenX: Int, screenY: Int) {
                val xid = GtkNativeWindowHost.windowX11Xid(windowKey)
                if (xid != 0uL) {
                    LinuxX11WindowHintsBridge.showWindowMenu(xid, screenX, screenY)
                }
            }

            override fun onToggleKeepAbove() {
                val nowActive = !state.activeStates.contains(LinuxTitleButtonKind.KEEP_ABOVE)
                if (nowActive) {
                    state.activeStates.add(LinuxTitleButtonKind.KEEP_ABOVE)
                    state.activeStates.remove(LinuxTitleButtonKind.KEEP_BELOW)
                } else {
                    state.activeStates.remove(LinuxTitleButtonKind.KEEP_ABOVE)
                }
                windowImpl?.let { win -> GenericUiThreading.runOnLogic { win.setAlwaysOnTop(nowActive) } }
                val xid = GtkNativeWindowHost.windowX11Xid(windowKey)
                if (xid != 0uL) {
                    LinuxX11WindowHintsBridge.sendNetWmState(
                        xid,
                        "_NET_WM_STATE_ABOVE",
                        action = if (nowActive) LinuxX11WindowHintsBridge.NET_WM_STATE_ADD else LinuxX11WindowHintsBridge.NET_WM_STATE_REMOVE
                    )
                }
            }

            override fun onToggleKeepBelow() {
                val nowActive = !state.activeStates.contains(LinuxTitleButtonKind.KEEP_BELOW)
                if (nowActive) {
                    state.activeStates.add(LinuxTitleButtonKind.KEEP_BELOW)
                    state.activeStates.remove(LinuxTitleButtonKind.KEEP_ABOVE)
                    windowImpl?.let { win -> GenericUiThreading.runOnLogic { win.setAlwaysOnTop(false) } }
                } else {
                    state.activeStates.remove(LinuxTitleButtonKind.KEEP_BELOW)
                }
                val xid = GtkNativeWindowHost.windowX11Xid(windowKey)
                if (xid != 0uL) {
                    LinuxX11WindowHintsBridge.sendNetWmState(
                        xid,
                        "_NET_WM_STATE_BELOW",
                        action = if (nowActive) LinuxX11WindowHintsBridge.NET_WM_STATE_ADD else LinuxX11WindowHintsBridge.NET_WM_STATE_REMOVE
                    )
                }
            }

            override fun onToggleAllDesktops() {
                val nowActive = !state.activeStates.contains(LinuxTitleButtonKind.ON_ALL_DESKTOPS)
                if (nowActive) state.activeStates.add(LinuxTitleButtonKind.ON_ALL_DESKTOPS)
                else state.activeStates.remove(LinuxTitleButtonKind.ON_ALL_DESKTOPS)
                val xid = GtkNativeWindowHost.windowX11Xid(windowKey)
                if (xid != 0uL) {
                    LinuxX11WindowHintsBridge.sendNetWmState(
                        xid,
                        "_NET_WM_STATE_STICKY",
                        action = if (nowActive) LinuxX11WindowHintsBridge.NET_WM_STATE_ADD else LinuxX11WindowHintsBridge.NET_WM_STATE_REMOVE
                    )
                }
            }

            override fun onToggleShade() {
                val nowActive = !state.activeStates.contains(LinuxTitleButtonKind.SHADE)
                if (nowActive) state.activeStates.add(LinuxTitleButtonKind.SHADE)
                else state.activeStates.remove(LinuxTitleButtonKind.SHADE)
                val xid = GtkNativeWindowHost.windowX11Xid(windowKey)
                if (xid != 0uL) {
                    LinuxX11WindowHintsBridge.sendNetWmState(
                        xid,
                        "_NET_WM_STATE_SHADED",
                        action = if (nowActive) LinuxX11WindowHintsBridge.NET_WM_STATE_ADD else LinuxX11WindowHintsBridge.NET_WM_STATE_REMOVE
                    )
                }
            }

            override fun onStartSystemMove() {
                val gtkWin = windowImpl as? GtkWindowNativeImpl ?: return
                GtkNativeWindowHost.startSystemMove(gtkWin)
            }

            override fun onDoubleClickMaximize() {
                if (allowMaximize) {
                    onMaximizeOrRestore()
                }
            }
        }
    }

    private fun resolveVisibleSlotWidgets(components: List<AbstractComponent>): List<DesktopNativeWidget> {
        if (components.isEmpty()) return emptyList()
        val out = ArrayList<DesktopNativeWidget>(components.size)
        for (c in components) {
            if (!c.isVisible) continue
            val w = (c.nativeImpl as? DesktopBaseNativeImpl<*>)?.nativeWidget ?: continue
            if (!w.visible || w.disposed) continue
            out.add(w)
        }
        return out
    }

    private fun measureWidgetSlotSize(widget: DesktopNativeWidget): Size {
        val impl = widget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
        return when (impl) {
            is DesktopButtonNativeImpl<*> -> {
                val pref = impl.measureIntrinsicSize(null, null)
                Size(pref.width.coerceAtLeast(32), pref.height.coerceIn(22, 34))
            }
            is DesktopTextNativeImpl<*> -> {
                val pref = impl.measureIntrinsicSize(null, null)
                Size(pref.width.coerceAtLeast(16), pref.height.coerceIn(18, 30))
            }
            is DesktopBaseContainerNativeImpl<*> -> {
                val pref = impl.measureIntrinsicSize(null, null)
                Size(pref.width.coerceAtLeast(28), pref.height.coerceIn(24, 48))
            }
            is DesktopBaseNativeImpl<*> -> {
                val pref = impl.measureIntrinsicSize(null, null)
                Size(pref.width.coerceAtLeast(28), pref.height.coerceIn(22, 34))
            }
            else -> Size(48, 26)
        }
    }

    private fun computeSlotGroupWidth(
        widgets: List<DesktopNativeWidget>,
        menuBarImpl: DesktopMenuBarNativeImpl<*>?
    ): Int {
        var total = 0
        for (w in widgets) {
            val sz = measureWidgetSlotSize(w)
            if (total > 0) total += 6
            total += sz.width
        }
        if (menuBarImpl != null) {
            val mbSize = menuBarImpl.measureIntrinsicSize(null, null)
            if (total > 0) total += 8
            total += mbSize.width
        }
        return total
    }

    private fun placeSlotGroup(
        widgets: List<DesktopNativeWidget>,
        menuBarImpl: DesktopMenuBarNativeImpl<*>?,
        slotBounds: Rect,
        alignRight: Boolean,
        out: MutableList<Pair<DesktopNativeWidget, Rect>>
    ) {
        if (slotBounds.width <= 0 || slotBounds.height <= 0) return
        val items = ArrayList<Triple<DesktopNativeWidget, Int, Boolean>>(widgets.size + 1)
        if (menuBarImpl != null) {
            val mbW = menuBarImpl.measureIntrinsicSize(null, null).width.coerceAtLeast(48)
            items.add(Triple(menuBarImpl.nativeWidget, mbW, false))
        }
        for (w in widgets) {
            val sz = measureWidgetSlotSize(w)
            val expanding = !alignRight && LinuxOverlayTitleBarResolver.isExpandingTitleBarComponent(w)
            items.add(Triple(w, sz.width, expanding))
        }
        if (items.isEmpty()) return

        val totalGap = (items.size - 1).coerceAtLeast(0) * 6
        val expandingCount = items.count { it.third }
        val fixedSum = items.filter { !it.third }.sumOf { it.second }
        val expandBudget = (slotBounds.width - fixedSum - totalGap).coerceAtLeast(0)
        var remainingExpand = expandBudget
        var expandIndex = 0

        var curX = if (alignRight && expandingCount == 0) {
            val totalW = fixedSum + totalGap
            max(slotBounds.x, slotBounds.right - totalW)
        } else {
            slotBounds.x
        }

        for ((w, fixedW, isExpanding) in items) {
            val rem = (slotBounds.right - curX).coerceAtLeast(1)
            val itemW = if (isExpanding) {
                expandIndex++
                val share = if (expandIndex == expandingCount) {
                    remainingExpand
                } else {
                    (expandBudget / expandingCount.coerceAtLeast(1)).also { remainingExpand -= it }
                }
                share.coerceIn(1, rem)
            } else {
                fixedW.coerceAtMost(rem)
            }
            val impl = w.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
            val h = if (impl is DesktopBaseContainerNativeImpl<*>) {
                (slotBounds.height - 4).coerceAtLeast(24).coerceAtMost(slotBounds.height)
            } else {
                minOf(slotBounds.height - 4, 30).coerceAtLeast(20)
            }
            val y = slotBounds.y + ((slotBounds.height - h) / 2).coerceAtLeast(0)
            val rect = Rect(curX, y, itemW, h)
            impl?.setBounds(rect) ?: run { w.bounds = rect }
            if (impl is DesktopBaseContainerNativeImpl<*>) {
                impl.layoutChildrenInBounds(rect.width.toFloat(), rect.height.toFloat())
            }
            out.add(w to rect)
            curX += rect.width + 6
        }
    }
}
