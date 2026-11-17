package com.nexustools.genericui.swing

import com.nexustools.genericui.component.lazy.TreeNode
import com.nexustools.genericui.nativeimpl.lazy.TreeViewNativeImpl
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel
import kotlin.math.roundToInt

/**
 * Java Swing `JTree` + `JScrollPane` implementation of [TreeViewNativeImpl].
 *
 * Uses a real [JTree] backed by [DefaultTreeModel], lazy [TreeWillExpandListener] child loading,
 * and a [MouseAdapter] for click, double-click, and popup context menu callbacks.
 */
class SwingTreeViewImpl private constructor(
    val tree: JTree,
    private val hiddenRoot: DefaultMutableTreeNode,
    private val treeModel: DefaultTreeModel
) : SwingBaseNativeImpl(JScrollPane(tree), "tree-view overflow-auto"), TreeViewNativeImpl<JComponent> {

    constructor() : this(
        hiddenRoot = DefaultMutableTreeNode("__root__"),
        treeModel = DefaultTreeModel(DefaultMutableTreeNode("__root__"))
    )

    private constructor(
        hiddenRoot: DefaultMutableTreeNode,
        treeModel: DefaultTreeModel
    ) : this(
        tree = JTree(treeModel).apply {
            isRootVisible = false
            showsRootHandles = true
            rowHeight = 28
            selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        },
        hiddenRoot = hiddenRoot.also { treeModel.setRoot(it) },
        treeModel = treeModel
    )

    override val nativeWidget: JScrollPane get() = super.nativeWidget as JScrollPane

    private var roots: List<TreeNode<*>> = emptyList()
    private val nodeMap = linkedMapOf<String, DefaultMutableTreeNode>()
    private var selectedId: String? = null
    private var onSelectListener: ((TreeNode<*>) -> Unit)? = null
    private var onDoubleClickListenerNode: ((TreeNode<*>) -> Unit)? = null
    private var onContextMenuListenerNode: ((TreeNode<*>, Float, Float) -> Unit)? = null
    private var onViewportScrollListener: ((Float, Float) -> Unit)? = null

    init {
        tree.cellRenderer = object : DefaultTreeCellRenderer() {
            override fun getTreeCellRendererComponent(
                tree: JTree,
                value: Any?,
                sel: Boolean,
                expanded: Boolean,
                leaf: Boolean,
                row: Int,
                hasFocus: Boolean
            ): Component {
                val c = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
                val treeNode = (value as? DefaultMutableTreeNode)?.userObject as? TreeNode<*>
                if (treeNode != null) {
                    text = treeNode.label
                }
                return c
            }
        }

        tree.addTreeWillExpandListener(object : TreeWillExpandListener {
            override fun treeWillExpand(event: TreeExpansionEvent) {
                val swingNode = event.path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val modelNode = swingNode.userObject as? TreeNode<*> ?: return
                val hadPlaceholder = swingNode.childCount == 1 &&
                    (swingNode.getChildAt(0) as? DefaultMutableTreeNode)?.userObject == LAZY_PLACEHOLDER
                modelNode.ensureChildrenLoaded()
                modelNode.isExpanded = true
                if (hadPlaceholder || swingNode.childCount != modelNode.children.size) {
                    swingNode.removeAllChildren()
                    for (child in modelNode.children) {
                        swingNode.add(buildSwingTreeNode(child))
                    }
                    treeModel.reload(swingNode)
                }
            }

            override fun treeWillCollapse(event: TreeExpansionEvent) {
                val swingNode = event.path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val modelNode = swingNode.userObject as? TreeNode<*> ?: return
                modelNode.isExpanded = false
            }
        })

        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger || SwingUtilities.isRightMouseButton(e)) {
                    handleContextMenuEvent(e)
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    handleContextMenuEvent(e)
                }
            }

            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isRightMouseButton(e) || e.isPopupTrigger) return
                val path = tree.getPathForLocation(e.x, e.y) ?: return
                tree.selectionPath = path
                val swingNode = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val modelNode = swingNode.userObject as? TreeNode<*> ?: return
                selectedId = modelNode.id
                if (SwingUtilities.isLeftMouseButton(e)) {
                    if (e.clickCount >= 2) {
                        onDoubleClickListenerNode?.invoke(modelNode)
                    } else if (e.clickCount == 1) {
                        onSelectListener?.invoke(modelNode)
                    }
                }
            }

            private var lastPopupTimeMs: Long = -1L

            private fun handleContextMenuEvent(e: MouseEvent) {
                if (e.`when` == lastPopupTimeMs) return
                lastPopupTimeMs = e.`when`
                val path = tree.getPathForLocation(e.x, e.y)
                    ?: tree.getClosestPathForLocation(e.x, e.y)
                    ?: tree.selectionPath
                    ?: return
                tree.selectionPath = path
                val swingNode = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val modelNode = swingNode.userObject as? TreeNode<*> ?: return
                selectedId = modelNode.id
                onContextMenuListenerNode?.invoke(modelNode, e.x.toFloat(), e.y.toFloat())
            }
        })

        nativeWidget.viewport.addChangeListener {
            val viewPosY = nativeWidget.viewport.viewPosition.y.toFloat()
            val viewH = nativeWidget.viewport.extentSize.height.toFloat()
            onViewportScrollListener?.invoke(viewPosY, viewH)
        }
    }

    override fun setRootNodes(roots: List<TreeNode<*>>) {
        this.roots = roots.toList()
        nodeMap.clear()
        hiddenRoot.removeAllChildren()
        val pathsToExpand = mutableListOf<TreePath>()
        for (root in this.roots) {
            val childNode = buildSwingTreeNode(root)
            hiddenRoot.add(childNode)
            collectExpandedPaths(childNode, pathsToExpand)
        }
        treeModel.reload(hiddenRoot)
        for (path in pathsToExpand) {
            tree.expandPath(path)
        }
        selectedId?.let { id ->
            nodeMap[id]?.let { target ->
                tree.selectionPath = TreePath(target.path)
            }
        }
    }

    override fun setSelectedNodeId(nodeId: String?) {
        selectedId = nodeId
        if (nodeId == null) {
            tree.clearSelection()
            return
        }
        val target = nodeMap[nodeId] ?: return
        val path = TreePath(target.path)
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
        val modelNode = target.userObject as? TreeNode<*> ?: return
        onSelectListener?.invoke(modelNode)
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

    override fun setEstimatedRowHeight(rowHeightPx: Float, overscanRows: Int) {
        tree.rowHeight = rowHeightPx.roundToInt().coerceAtLeast(16)
    }

    override fun updateViewportMetrics(
        scrollOffsetPx: Float,
        viewportHeightPx: Float,
        firstActiveIndex: Int,
        lastActiveIndex: Int,
        leadingSpacerPx: Float,
        trailingSpacerPx: Float,
        totalContentExtentPx: Float
    ) {
        nativeWidget.putClientProperty("scrollOffsetPx", scrollOffsetPx)
        nativeWidget.putClientProperty("viewportHeightPx", viewportHeightPx)
        nativeWidget.putClientProperty("firstActiveIndex", firstActiveIndex)
        nativeWidget.putClientProperty("lastActiveIndex", lastActiveIndex)
        nativeWidget.putClientProperty("leadingSpacerPx", leadingSpacerPx)
        nativeWidget.putClientProperty("trailingSpacerPx", trailingSpacerPx)
        nativeWidget.putClientProperty("totalContentExtentPx", totalContentExtentPx)
    }

    override fun setOnViewportScrollChanged(listener: ((scrollOffsetPx: Float, viewportHeightPx: Float) -> Unit)?) {
        onViewportScrollListener = listener
    }

    private fun buildSwingTreeNode(modelNode: TreeNode<*>): DefaultMutableTreeNode {
        val swingNode = DefaultMutableTreeNode(modelNode)
        nodeMap[modelNode.id] = swingNode
        if (modelNode.isExpanded) {
            modelNode.ensureChildrenLoaded()
        }
        if (modelNode.children.isNotEmpty()) {
            for (child in modelNode.children) {
                swingNode.add(buildSwingTreeNode(child))
            }
        } else if (modelNode.lazyChildrenLoader != null) {
            swingNode.add(DefaultMutableTreeNode(LAZY_PLACEHOLDER))
        }
        return swingNode
    }

    private fun collectExpandedPaths(node: DefaultMutableTreeNode, out: MutableList<TreePath>) {
        val model = node.userObject as? TreeNode<*>
        if (model?.isExpanded == true) {
            out.add(TreePath(node.path))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as? DefaultMutableTreeNode ?: continue
            collectExpandedPaths(child, out)
        }
    }

    private companion object {
        val LAZY_PLACEHOLDER = Any()
    }
}
