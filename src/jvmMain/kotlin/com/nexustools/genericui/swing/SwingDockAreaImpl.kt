package com.nexustools.genericui.swing

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.enums.bootstrap.FlexDirection
import com.nexustools.genericui.enums.dock.DockPosition
import com.nexustools.genericui.nativeimpl.dock.DockAreaNativeImpl
import com.nexustools.genericui.enums.css.CssDisplay
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Java Swing [DockAreaNativeImpl] backed by [SwingFlexLayoutManager].
 */
class SwingDockAreaImpl : SwingBaseContainerNativeImpl(
    SwingContainerImpl.SwingLayoutPanel(),
    "dock-area"
), DockAreaNativeImpl<JComponent> {

    private var positionVal: DockPosition = DockPosition.LEFT
    private var collapsedFlag: Boolean = false
    internal var ownerWorkspace: SwingDockWorkspaceImpl? = null

    init {
        nativeWidget.isOpaque = false
        applyOrientationLayout(positionVal)
        syncSwingAppearance()
    }

    override fun addChildNative(childImpl: NativeImpl<*>, index: Int) {
        if (childImpl is SwingDockGroupImpl || childImpl is SwingDockPanelImpl) {
            childImpl.setAttribute("data-stack-weight", "1")
        }
        super.addChildNative(childImpl, index)
        notifyOwnerWorkspace()
    }

    override fun removeChildNative(childImpl: NativeImpl<*>) {
        super.removeChildNative(childImpl)
        notifyOwnerWorkspace()
    }

    override fun clearChildrenNative() {
        super.clearChildrenNative()
        notifyOwnerWorkspace()
    }

    override fun setPosition(position: DockPosition) {
        positionVal = position
        nativeWidget.putClientProperty("dockPosition", position.name)
        applyOrientationLayout(position)
    }

    fun getDockPosition(): DockPosition = positionVal

    override fun setCollapsed(collapsed: Boolean) {
        collapsedFlag = collapsed
        nativeWidget.putClientProperty("collapsed", collapsed)
        nativeWidget.isVisible = !collapsed
        invalidateLayoutHierarchy()
        nativeWidget.repaint()
        notifyOwnerWorkspace()
    }

    private fun notifyOwnerWorkspace() {
        ownerWorkspace?.let {
            it.notifyAreaChanged()
            return
        }
        var cur: java.awt.Container? = nativeWidget.parent
        while (cur != null) {
            val ws = (cur as? JComponent)?.getClientProperty(CLIENT_PROPERTY_KEY) as? SwingDockWorkspaceImpl
            if (ws != null) {
                ownerWorkspace = ws
                ws.notifyAreaChanged()
                return
            }
            cur = cur.parent
        }
    }

    override fun isCollapsed(): Boolean = collapsedFlag

    private fun applyOrientationLayout(position: DockPosition) {
        val panel = nativeWidget as JPanel
        val vertical = position == DockPosition.LEFT || position == DockPosition.RIGHT
        panel.layout = SwingFlexLayoutManager(
            hostNativeImpl = this,
            defaultDisplay = CssDisplay.FLEX,
            defaultFlexDirection = if (vertical) FlexDirection.COLUMN else FlexDirection.ROW,
            defaultGapPx = 4
        )
        panel.putClientProperty(SwingFlexLayoutManager.MANAGED_LAYOUT_INSETS_KEY, true)
        if (!panel.isPreferredSizeSet) {
            panel.preferredSize = if (vertical) Dimension(240, 420) else Dimension(420, 180)
        }
        panel.revalidate()
        panel.repaint()
    }
}
