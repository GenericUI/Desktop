package com.nexustools.genericui.swing

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.enums.bootstrap.OverflowBehavior
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.nativeimpl.layout.ScrollViewNativeImpl
import com.nexustools.genericui.enums.css.CssDisplay
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.SwingConstants

/**
 * Java Swing `JScrollPane` implementation of [ScrollViewNativeImpl].
 *
 * Uses a custom [Scrollable] content panel backed by [SwingFlexLayoutManager] with viewport-width
 * tracking, smooth mouse-wheel increments, and transparent compositing so child cards/layouts and
 * wheel scrolling (including inside dialogs) work cleanly.
 */
class SwingScrollViewImpl : SwingBaseContainerNativeImpl(
    JScrollPane(),
    "overflow-auto"
), ScrollViewNativeImpl<JComponent> {

    private val scrollPane: JScrollPane = nativeWidget as JScrollPane
    private val contentPanel: ScrollContentPanel = ScrollContentPanel()
    private val contentLayout = SwingFlexLayoutManager(defaultDisplay = CssDisplay.BLOCK)

    init {
        contentPanel.layout = contentLayout
        contentPanel.isOpaque = false
        scrollPane.setViewportView(contentPanel)
        scrollPane.isOpaque = false
        scrollPane.viewport.isOpaque = false
        scrollPane.border = BorderFactory.createEmptyBorder()
        scrollPane.viewportBorder = null
        scrollPane.verticalScrollBar.unitIncrement = 16
        scrollPane.horizontalScrollBar.unitIncrement = 16
        scrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        scrollPane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        syncSwingAppearance()
    }

    override fun addChildNative(childImpl: NativeImpl<*>, index: Int) {
        val comp = childImpl.nativeWidget as? JComponent ?: return
        if (comp.parent === contentPanel) {
            contentPanel.remove(comp)
        }
        if (index < 0 || index >= contentPanel.componentCount) {
            contentPanel.add(comp)
        } else {
            contentPanel.add(comp, index)
        }
        (childImpl as? SwingBaseNativeImpl)?.refreshThemeAppearance()
        contentPanel.invalidate()
        scrollPane.invalidate()
        scrollPane.revalidate()
        scrollPane.repaint()
    }

    override fun removeChildNative(childImpl: NativeImpl<*>) {
        val comp = childImpl.nativeWidget as? JComponent ?: return
        contentPanel.remove(comp)
        contentPanel.invalidate()
        scrollPane.invalidate()
        scrollPane.revalidate()
        scrollPane.repaint()
    }

    override fun clearChildrenNative() {
        contentPanel.removeAll()
        contentPanel.invalidate()
        scrollPane.invalidate()
        scrollPane.revalidate()
        scrollPane.repaint()
    }

    override fun syncSwingAppearance() {
        super.syncSwingAppearance()
        scrollPane.isOpaque = false
        scrollPane.viewport?.isOpaque = false
        val horizontalAllowed = getOverflow() == OverflowBehavior.SCROLL ||
            styleSnapshot.flexDirection == com.nexustools.genericui.enums.bootstrap.FlexDirection.ROW
        scrollPane.horizontalScrollBarPolicy = if (getOverflow() == OverflowBehavior.HIDDEN) {
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        } else {
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        }
        scrollPane.verticalScrollBarPolicy = if (getOverflow() == OverflowBehavior.HIDDEN) {
            ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
        } else {
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }
        contentPanel.trackViewportWidth = !horizontalAllowed
    }

    internal fun measurePreferredScrollSize(availableWidthPx: Int, availableHeightPx: Int): Size {
        val explicitW = getExplicitWidthPx()?.takeIf { it > 0 }
        val explicitH = getExplicitHeightPx()?.takeIf { it > 0 }
        val w = explicitW ?: availableWidthPx.coerceAtLeast(1)
        val pref = contentLayout.measurePreferredSize(
            parent = contentPanel,
            availableWidthPx = w,
            availableHeightPx = availableHeightPx,
            shrinkWrapWidth = (explicitW == null && !contentPanel.trackViewportWidth)
        )
        return Size(
            width = explicitW ?: pref.width.coerceAtLeast(1),
            height = explicitH ?: pref.height.coerceAtLeast(1)
        )
    }

    override fun scrollTo(x: Int, y: Int) {
        scrollPane.viewport.viewPosition = Point(x.coerceAtLeast(0), y.coerceAtLeast(0))
    }

    override fun scrollToBottom() {
        val maxH = (contentPanel.height - scrollPane.viewport.height).coerceAtLeast(0)
        scrollPane.viewport.viewPosition = Point(0, maxH)
    }

    private inner class ScrollContentPanel : SwingContainerImpl.SwingLayoutPanel(), Scrollable {
        var trackViewportWidth: Boolean = true

        override fun getPreferredSize(): Dimension {
            val viewW = scrollPane.viewport?.width?.takeIf { it > 0 }
                ?: scrollPane.width.takeIf { it > 0 }
                ?: getExplicitWidthPx()
                ?: 640
            return contentLayout.measurePreferredSize(
                parent = this,
                availableWidthPx = viewW,
                availableHeightPx = scrollPane.viewport?.height ?: 0,
                shrinkWrapWidth = !trackViewportWidth
            )
        }

        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

        override fun getScrollableUnitIncrement(visibleRect: Rectangle?, orientation: Int, direction: Int): Int = 16

        override fun getScrollableBlockIncrement(visibleRect: Rectangle?, orientation: Int, direction: Int): Int =
            if (orientation == SwingConstants.VERTICAL) {
                ((visibleRect?.height ?: 120) - 16).coerceAtLeast(32)
            } else {
                ((visibleRect?.width ?: 120) - 16).coerceAtLeast(32)
            }

        override fun getScrollableTracksViewportWidth(): Boolean = trackViewportWidth

        override fun getScrollableTracksViewportHeight(): Boolean = false
    }
}
