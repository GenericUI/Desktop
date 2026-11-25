package com.nexustools.genericui.swing

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.enums.dock.DockPosition
import com.nexustools.genericui.nativeimpl.dock.DockAreaNativeImpl
import com.nexustools.genericui.nativeimpl.dock.DockWorkspaceNativeImpl
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSplitPane

/**
 * Java Swing nested [JSplitPane] workspace implementation of [DockWorkspaceNativeImpl].
 *
 * Maintains proportional initial/resize divider locations, minimal child minimums so split panes
 * never crush or lock, and clean non-opaque theme compositing.
 */
class SwingDockWorkspaceImpl : SwingBaseContainerNativeImpl(
    JPanel(BorderLayout()).apply { isOpaque = false },
    "dock-workspace"
), DockWorkspaceNativeImpl<JComponent> {

    private var centralComponent: JComponent? = null
    private var centralTabsComponent: JComponent? = null
    private var backgroundComponent: JComponent? = null
    private val workspaceChildren = mutableListOf<NativeImpl<*>>()
    private val dockAreas = linkedMapOf<DockPosition, JComponent>()
    private var windowModeName: String = "SINGLE_WINDOW"

    init {
        syncSwingAppearance()
    }

    override fun syncSwingAppearance() {
        super.syncSwingAppearance()
        nativeWidget.isOpaque = false
    }

    override fun addChildNative(childImpl: NativeImpl<*>, index: Int) {
        workspaceChildren.removeAll { it === childImpl }
        val insertIdx = if (index < 0 || index > workspaceChildren.size) workspaceChildren.size else index
        workspaceChildren.add(insertIdx, childImpl)
        val comp = childImpl.nativeWidget as? JComponent
        if (childImpl is SwingTabContainerImpl) {
            centralTabsComponent = comp
        } else if ("dock-workspace-background" in childImpl.getClasses()) {
            backgroundComponent = comp
        }
        rebuildWorkspaceSplits()
    }

    override fun removeChildNative(childImpl: NativeImpl<*>) {
        workspaceChildren.removeAll { it === childImpl }
        val comp = childImpl.nativeWidget as? JComponent
        if (comp != null && comp === centralTabsComponent) {
            centralTabsComponent = null
        }
        if (comp != null && comp === backgroundComponent) {
            backgroundComponent = null
        }
        rebuildWorkspaceSplits()
    }

    override fun clearChildrenNative() {
        workspaceChildren.clear()
        centralTabsComponent = null
        backgroundComponent = null
        rebuildWorkspaceSplits()
    }

    override fun setCentralDocumentNative(centralImpl: NativeImpl<*>?) {
        centralComponent = centralImpl?.nativeWidget as? JComponent
        rebuildWorkspaceSplits()
    }

    override fun attachDockAreaNative(position: DockPosition, areaImpl: DockAreaNativeImpl<*>) {
        (areaImpl as? SwingDockAreaImpl)?.ownerWorkspace = this
        val comp = areaImpl.nativeWidget as? JComponent ?: return
        dockAreas[position] = comp
        rebuildWorkspaceSplits()
    }

    internal fun notifyAreaChanged() {
        rebuildWorkspaceSplits()
    }

    override fun setWindowModeNative(modeName: String) {
        windowModeName = modeName
        nativeWidget.putClientProperty("windowMode", modeName)
    }

    fun getDockAreaComponent(position: DockPosition): JComponent? = dockAreas[position]

    private fun createProportionalSplitPane(
        orientation: Int,
        first: JComponent,
        second: JComponent,
        resizeWeight: Double,
        initialRatio: Double
    ): JSplitPane {
        first.minimumSize = Dimension(0, 0)
        second.minimumSize = Dimension(0, 0)
        return object : JSplitPane(orientation, true, first, second) {
            private var userAdjusted = false

            init {
                this.resizeWeight = resizeWeight
                dividerSize = 5
                isOpaque = false
                border = BorderFactory.createEmptyBorder()
                setDividerLocation(initialRatio)
                addPropertyChangeListener(DIVIDER_LOCATION_PROPERTY) { evt ->
                    if (width > 0 && height > 0 && isShowing && evt.newValue != null) {
                        val loc = evt.newValue as? Int ?: return@addPropertyChangeListener
                        val total = if (orientation == HORIZONTAL_SPLIT) width else height
                        if (loc > 0 && loc < total - dividerSize) {
                            userAdjusted = true
                        }
                    }
                }
                addComponentListener(object : ComponentAdapter() {
                    override fun componentResized(e: ComponentEvent?) {
                        if (!userAdjusted) {
                            setDividerLocation(initialRatio)
                        }
                    }
                })
            }

            override fun doLayout() {
                if (!userAdjusted && width > 0 && height > 0) {
                    val span = (if (orientation == HORIZONTAL_SPLIT) width else height) - dividerSize
                    if (span > 0) {
                        super.setDividerLocation((span * initialRatio).toInt())
                    }
                }
                super.doLayout()
            }
        }
    }

    private fun rebuildWorkspaceSplits() {
        val root = nativeWidget as JPanel
        root.removeAll()

        val centerSlot = JPanel(BorderLayout()).apply { isOpaque = false }
        val tabs = centralTabsComponent
        val bg = backgroundComponent
        val primaryCenter: JComponent = when {
            bg != null && bg.isVisible && ((tabs as? javax.swing.JTabbedPane)?.tabCount ?: 0) == 0 -> bg
            tabs != null && tabs.isVisible -> tabs
            centralComponent != null -> centralComponent!!
            dockAreas[DockPosition.CENTER] != null -> dockAreas[DockPosition.CENTER]!!
            else -> JPanel(BorderLayout()).apply { isOpaque = false }
        }
        centerSlot.add(primaryCenter, BorderLayout.CENTER)

        val allAreasEmpty = dockAreas.values.all { (it as? java.awt.Container)?.componentCount == 0 }
        fun isAreaActive(comp: JComponent?): Boolean =
            comp != null && comp.isVisible && (allAreasEmpty || (
                (comp as? java.awt.Container)?.let { c ->
                    c.componentCount > 0 && c.components.any { it.isVisible }
                } ?: true
                ))

        var verticalCore: JComponent = centerSlot
        val topArea = dockAreas[DockPosition.TOP]?.takeIf(::isAreaActive)
        val bottomArea = dockAreas[DockPosition.BOTTOM]?.takeIf(::isAreaActive)
        if (topArea != null) {
            verticalCore = createProportionalSplitPane(
                orientation = JSplitPane.VERTICAL_SPLIT,
                first = topArea,
                second = verticalCore,
                resizeWeight = 0.22,
                initialRatio = 0.22
            )
        }
        if (bottomArea != null) {
            verticalCore = createProportionalSplitPane(
                orientation = JSplitPane.VERTICAL_SPLIT,
                first = verticalCore,
                second = bottomArea,
                resizeWeight = 0.78,
                initialRatio = 0.78
            )
        }

        var horizontalCore: JComponent = verticalCore
        val leftArea = dockAreas[DockPosition.LEFT]?.takeIf(::isAreaActive)
        val rightArea = dockAreas[DockPosition.RIGHT]?.takeIf(::isAreaActive)
        if (leftArea != null) {
            horizontalCore = createProportionalSplitPane(
                orientation = JSplitPane.HORIZONTAL_SPLIT,
                first = leftArea,
                second = horizontalCore,
                resizeWeight = 0.22,
                initialRatio = 0.22
            )
        }
        if (rightArea != null) {
            horizontalCore = createProportionalSplitPane(
                orientation = JSplitPane.HORIZONTAL_SPLIT,
                first = horizontalCore,
                second = rightArea,
                resizeWeight = 0.78,
                initialRatio = 0.78
            )
        }

        root.add(horizontalCore, BorderLayout.CENTER)
        SwingFlexLayoutManager.layoutTreeNow(root)
        root.revalidate()
        root.repaint()
    }
}
