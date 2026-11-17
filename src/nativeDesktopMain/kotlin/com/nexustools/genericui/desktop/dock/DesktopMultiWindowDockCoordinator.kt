package com.nexustools.genericui.desktop.dock

import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.enums.dock.DockPosition
import com.nexustools.genericui.enums.dock.StudioWindowMode
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.nativeimpl.dock.DockAreaNativeImpl
import com.nexustools.genericui.nativeimpl.dock.DockPanelNativeImpl
import com.nexustools.genericui.nativeimpl.dock.DockWindowNativeImpl
import com.nexustools.genericui.nativeimpl.dock.DockWorkspaceNativeImpl
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl

/**
 * Shared Desktop coordinator for GIMP / Photoshop Single-Window Mode (`StudioWindowMode.SINGLE_WINDOW_MODE`)
 * and Multi-Window Mode (`StudioWindowMode.MULTI_WINDOW_MODE`) across `:windows`, `:macos`, `:gtk`, and `:qt`.
 *
 * Manages detaching `DockPanel`s and `DockArea`s into floating utility `DockWindow`s and re-docking them back
 * into the primary `DockWorkspace`. Detached `DockWindow`s are attached to [ownerWindow] as tool-modal utility
 * windows without taskbar entries or minimize buttons, and stay on top of [ownerWindow].
 */
class DesktopMultiWindowDockCoordinator(
    private val dockWindowFactory: () -> DockWindowNativeImpl<*> = {
        DesktopDockWindowNativeImpl(DesktopNativeWidget("Desktop", "DockWindow"))
    }
) {
    var windowMode: StudioWindowMode = StudioWindowMode.SINGLE_WINDOW_MODE
        private set

    var ownerWindow: WindowNativeImpl<*>? = null
        set(value) {
            field = value
            for (dockWindow in floatingWindowsByPanel.values) {
                dockWindow.attachToOwnerWindow(value)
            }
        }

    private val floatingWindowsByPanel = linkedMapOf<DockPanelNativeImpl<*>, DockWindowNativeImpl<*>>()
    private val dockAreaByPosition = linkedMapOf<DockPosition, DockAreaNativeImpl<*>>()

    val activeFloatingWindows: Collection<DockWindowNativeImpl<*>>
        get() = floatingWindowsByPanel.values

    fun registerDockArea(position: DockPosition, area: DockAreaNativeImpl<*>) {
        dockAreaByPosition[position] = area
    }

    fun detachPanelToFloatingWindow(
        panel: DockPanelNativeImpl<*>,
        bounds: Rect = Rect(120f, 120f, 320f, 480f)
    ): DockWindowNativeImpl<*> {
        val existing = floatingWindowsByPanel[panel]
        if (existing != null) {
            existing.attachToOwnerWindow(ownerWindow)
            existing.setWindowBounds(bounds)
            existing.showDockWindow()
            existing.bringToFront()
            return existing
        }

        dockAreaByPosition.values.forEach { it.removeChildNative(panel) }
        val dockWindow = dockWindowFactory()
        dockWindow.setTitle(panel.getTitle())
        dockWindow.setWindowBounds(bounds)
        dockWindow.attachToOwnerWindow(ownerWindow)
        dockWindow.addChildNative(panel)
        panel.setFloating(true)
        dockWindow.setOnDockWindowCloseRequested {
            reattachPanelToWorkspace(panel, DockPosition.RIGHT)
        }
        dockWindow.showDockWindow()
        dockWindow.bringToFront()
        floatingWindowsByPanel[panel] = dockWindow
        return dockWindow
    }

    fun bringAllPanelsToFront() {
        for (dockWindow in floatingWindowsByPanel.values) {
            dockWindow.bringToFront()
        }
    }

    fun reattachPanelToWorkspace(
        panel: DockPanelNativeImpl<*>,
        targetPosition: DockPosition = DockPosition.RIGHT
    ) {
        val floatingWindow = floatingWindowsByPanel.remove(panel)
        if (floatingWindow != null) {
            floatingWindow.removeChildNative(panel)
            floatingWindow.hideDockWindow()
            floatingWindow.dispose()
        }
        panel.setFloating(false)
        dockAreaByPosition[targetPosition]?.addChildNative(panel)
    }

    fun setStudioWindowMode(
        mode: StudioWindowMode,
        @Suppress("UNUSED_PARAMETER") workspace: DockWorkspaceNativeImpl<*>?,
        panels: List<DockPanelNativeImpl<*>>
    ) {
        if (windowMode == mode) return
        windowMode = mode
        if (mode == StudioWindowMode.MULTI_WINDOW_MODE) {
            var offset = 80f
            for (panel in panels) {
                detachPanelToFloatingWindow(panel, Rect(offset, offset, 320f, 520f))
                offset += 36f
            }
            bringAllPanelsToFront()
        } else {
            for (panel in panels.toList()) {
                reattachPanelToWorkspace(panel, DockPosition.RIGHT)
            }
        }
    }

    fun disposeAll() {
        for (window in floatingWindowsByPanel.values) {
            window.hideDockWindow()
            window.dispose()
        }
        floatingWindowsByPanel.clear()
        dockAreaByPosition.clear()
    }
}
