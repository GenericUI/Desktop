package com.nexustools.genericui.swing

import com.nexustools.genericui.component.lazy.TreeNode
import com.nexustools.genericui.nativeimpl.lazy.TreeViewNativeImpl
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane

/**
 * Java Swing `JScrollPane` viewport-windowed implementation of [TreeViewNativeImpl].
 */
class SwingTreeViewImpl : SwingBaseNativeImpl(JScrollPane(JPanel(null)), "tree-view overflow-auto"), TreeViewNativeImpl<JComponent> {
    override val nativeWidget: JScrollPane = super.nativeWidget as JScrollPane
    private val canvasPanel: JPanel = nativeWidget.viewport.view as JPanel

    private var roots: List<TreeNode<*>> = emptyList()
    private var flatRows: List<Pair<TreeNode<*>, Int>> = emptyList()
    private val rowHeightPx: Int = 28
    private val indentPx: Int = 18
    private var selectedId: String? = null
    private var scrollOffsetYPx: Int = 0
    private var onSelectListener: ((TreeNode<*>) -> Unit)? = null
    private var onDoubleClickListenerNode: ((TreeNode<*>) -> Unit)? = null
    private var onContextMenuListenerNode: ((TreeNode<*>, Float, Float) -> Unit)? = null
    private val mountedRows = linkedMapOf<Int, JComponent>()

    init {
        nativeWidget.viewport.addChangeListener {
            scrollOffsetYPx = nativeWidget.viewport.viewPosition.y
            recomputeVisibleViewport()
        }
    }

    override fun setRootNodes(roots: List<TreeNode<*>>) {
        this.roots = roots.toList()
        rebuildFlatRows()
    }

    override fun setSelectedNodeId(nodeId: String?) {
        selectedId = nodeId
        if (nodeId != null) {
            flatRows.firstOrNull { it.first.id == nodeId }?.first?.let { node ->
                onSelectListener?.invoke(node)
            }
        }
    }

    override fun setOnNodeSelected(listener: ((TreeNode<*>) -> Unit)?) {
        onSelectListener = listener
    }

    override fun setOnNodeDoubleClicked(listener: ((TreeNode<*>) -> Unit)?) {
        onDoubleClickListenerNode = listener
    }

    override fun setOnNodeContextMenu(listener: ((TreeNode<*>, Float, Float) -> Unit)?) {
        onContextMenuListenerNode = listener
    }

    private fun rebuildFlatRows() {
        for ((_, comp) in mountedRows) {
            canvasPanel.remove(comp)
        }
        mountedRows.clear()

        val list = mutableListOf<Pair<TreeNode<*>, Int>>()
        fun walk(nodes: List<TreeNode<*>>, depth: Int) {
            for (n in nodes) {
                list.add(n to depth)
                if (n.isExpanded) {
                    n.ensureChildrenLoaded()
                    walk(n.children, depth + 1)
                }
            }
        }
        walk(roots, 0)
        flatRows = list
        recomputeVisibleViewport()
    }

    private fun recomputeVisibleViewport() {
        canvasPanel.preferredSize = Dimension(400, flatRows.size * rowHeightPx)
        if (flatRows.isEmpty()) return

        val viewportHeight = getBounds().height.coerceAtLeast(320)
        val firstIdx = ((scrollOffsetYPx / rowHeightPx) - 3).coerceAtLeast(0)
        val lastIdx = (((scrollOffsetYPx + viewportHeight) / rowHeightPx) + 3).coerceAtMost(flatRows.size - 1)

        val toEvict = mountedRows.keys.filter { it < firstIdx || it > lastIdx }
        for (idx in toEvict) {
            mountedRows.remove(idx)?.let { canvasPanel.remove(it) }
        }

        for (idx in firstIdx..lastIdx) {
            if (!mountedRows.containsKey(idx)) {
                val (node, depth) = flatRows[idx]
                val label = JLabel(node.label)
                val xOffset = depth * indentPx
                label.setBounds(xOffset, idx * rowHeightPx, (getBounds().width - xOffset).coerceAtLeast(200), rowHeightPx)
                canvasPanel.add(label)
                mountedRows[idx] = label
            }
        }
    }
}
