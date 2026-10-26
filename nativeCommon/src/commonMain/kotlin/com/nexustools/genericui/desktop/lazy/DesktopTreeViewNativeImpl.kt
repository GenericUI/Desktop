package com.nexustools.genericui.desktop.lazy

import com.nexustools.genericui.component.lazy.TreeNode
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.nativeimpl.lazy.TreeViewNativeImpl

/**
 * Shared desktop base implementation of [TreeViewNativeImpl].
 */
open class DesktopTreeViewNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W
) : DesktopBaseNativeImpl<W>(nativeWidget, "tree-view"), TreeViewNativeImpl<W> {

    protected var rootNodesList: List<TreeNode<*>> = emptyList()
    protected var selectedNodeIdVal: String? = null
    protected var onNodeSelectedListener: ((TreeNode<*>) -> Unit)? = null
    protected var onNodeDoubleClickedListener: ((TreeNode<*>) -> Unit)? = null
    protected var onNodeContextMenuListener: ((TreeNode<*>, Float, Float) -> Unit)? = null

    override fun setRootNodes(roots: List<TreeNode<*>>) {
        rootNodesList = roots
        nativeWidget.attributes["rootNodes"] = roots
    }

    override fun setSelectedNodeId(nodeId: String?) {
        selectedNodeIdVal = nodeId
        nativeWidget.attributes["selectedNodeId"] = nodeId
    }

    override fun setOnNodeSelected(listener: ((TreeNode<*>) -> Unit)?) {
        onNodeSelectedListener = listener
    }

    override fun setOnNodeDoubleClicked(listener: ((TreeNode<*>) -> Unit)?) {
        onNodeDoubleClickedListener = listener
    }

    override fun setOnNodeContextMenu(listener: ((TreeNode<*>, Float, Float) -> Unit)?) {
        onNodeContextMenuListener = listener
    }
}
