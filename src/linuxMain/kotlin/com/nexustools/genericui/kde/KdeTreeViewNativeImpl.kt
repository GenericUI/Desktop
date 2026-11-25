@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.component.lazy.TreeNode
import com.nexustools.genericui.desktop.lazy.DesktopTreeViewNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.COpaquePointer

/**
 * Retained KDE hierarchical tree widget backed by Qt 6 **`QTreeWidget`** and native
 * `QTreeWidgetItem` subtrees, supporting lazy child loading, selection, and double-click signals.
 */
open class KdeTreeViewNativeImpl(
    nativeWidget: KdeWidgetHandle = KdeWidgetHandle("QTreeWidget")
) : DesktopTreeViewNativeImpl<KdeWidgetHandle>(nativeWidget) {

    private val itemAddressToNode = hashMapOf<Long, TreeNode<*>>()
    private val nodeIdToItemPtr = hashMapOf<String, COpaquePointer>()
    private var lastContextMenuPos: Pair<Float, Float> = 0f to 0f

    init {
        KdeDynamicBindings.ensureQApplication()
        val ptr = KdeDynamicBindings.createQTreeWidget(null)
        nativeWidget.attachToQWidget(ptr, "QTreeWidget", customPainted = false)

        KdeVTableBridge.connectSignal(ptr, "itemClicked(QTreeWidgetItem*,int)") { args ->
            val itemPtr = KdeVTableBridge.readSignalArgPtr(args, 1) ?: return@connectSignal
            val node = itemAddressToNode[itemPtr.rawValue.toLong()] ?: return@connectSignal
            GenericUiThreading.runOnLogic {
                setSelectedNodeId(node.id)
                onNodeSelectedListener?.invoke(node)
            }
        }
        KdeVTableBridge.connectSignal(ptr, "itemDoubleClicked(QTreeWidgetItem*,int)") { args ->
            val itemPtr = KdeVTableBridge.readSignalArgPtr(args, 1) ?: return@connectSignal
            val node = itemAddressToNode[itemPtr.rawValue.toLong()] ?: return@connectSignal
            GenericUiThreading.runOnLogic {
                onNodeDoubleClickedListener?.invoke(node)
            }
        }
        KdeVTableBridge.connectSignal(ptr, "itemExpanded(QTreeWidgetItem*)") { args ->
            val itemPtr = KdeVTableBridge.readSignalArgPtr(args, 1) ?: return@connectSignal
            val node = itemAddressToNode[itemPtr.rawValue.toLong()] ?: return@connectSignal
            node.isExpanded = true
            if (node.lazyChildrenLoader != null && node.children.isEmpty()) {
                GenericUiThreading.runOnLogic {
                    node.ensureChildrenLoaded()
                    rebuildTreeItems()
                }
            }
        }
        KdeVTableBridge.attachWidgetHooks(ptr, KdeVTableBridge.HOOK_RESIZE or KdeVTableBridge.HOOK_INPUT) {
            onMouseRelease = { _, info ->
                if (info.button == 2 && isEnabled()) {
                    lastContextMenuPos = info.localX to info.localY
                    val selectedNode = selectedNodeIdVal?.let { id -> findNodeById(rootNodesList, id) }
                    if (selectedNode != null) {
                        GenericUiThreading.runOnLogic {
                            onNodeContextMenuListener?.invoke(selectedNode, lastContextMenuPos.first, lastContextMenuPos.second)
                        }
                    }
                }
                false
            }
        }
    }

    private fun findNodeById(nodes: List<TreeNode<*>>, id: String): TreeNode<*>? {
        for (n in nodes) {
            if (n.id == id) return n
            findNodeById(n.children, id)?.let { return it }
        }
        return null
    }

    private fun rebuildTreeItems() {
        val treePtr = nativeWidget.qWidgetPtr ?: return
        if (!KdeVTableBridge.isPeerAlive(treePtr)) return

        itemAddressToNode.clear()
        nodeIdToItemPtr.clear()
        KdeDynamicBindings.clearQTreeWidget(treePtr)

        fun buildItem(node: TreeNode<*>, parentItem: COpaquePointer?) {
            val item = KdeDynamicBindings.createQTreeItem(node.label)
            itemAddressToNode[item.rawValue.toLong()] = node
            nodeIdToItemPtr[node.id] = item
            if (parentItem == null) {
                KdeDynamicBindings.addQTreeTopItem(treePtr, item)
            } else {
                KdeDynamicBindings.addQTreeChildItem(parentItem, item)
            }
            if (node.isExpanded) {
                node.ensureChildrenLoaded()
            }
            for (child in node.children) {
                buildItem(child, item)
            }
            if (node.children.isEmpty() && node.lazyChildrenLoader != null) {
                // Placeholder dummy item so Qt displays expand arrow for unloaded lazy folders
                val placeholder = KdeDynamicBindings.createQTreeItem("...")
                KdeDynamicBindings.addQTreeChildItem(item, placeholder)
            }
            if (node.isExpanded) {
                KdeDynamicBindings.setQTreeItemExpanded(item, true)
            }
        }

        for (root in rootNodesList) {
            buildItem(root, null)
        }
        selectedNodeIdVal?.let { selId ->
            nodeIdToItemPtr[selId]?.let { KdeDynamicBindings.setQTreeCurrentItem(treePtr, it) }
        }
    }

    override fun setRootNodes(roots: List<TreeNode<*>>) {
        super.setRootNodes(roots)
        rebuildTreeItems()
    }

    override fun setSelectedNodeId(nodeId: String?) {
        super.setSelectedNodeId(nodeId)
        val treePtr = nativeWidget.qWidgetPtr ?: return
        val itemPtr = nodeId?.let { nodeIdToItemPtr[it] }
        KdeDynamicBindings.setQTreeCurrentItem(treePtr, itemPtr)
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size =
        Size(maxWidth?.toInt()?.coerceAtMost(320) ?: 260, maxHeight?.toInt()?.coerceAtMost(420) ?: 280)

    override fun onNativeBoundsChanged(rect: Rect) { nativeWidget.applyBoundsToNative(rect) }
    override fun onNativeVisibilityChanged(visible: Boolean) { nativeWidget.syncVisibilityToNative(visible) }
    override fun onNativeEnabledChanged(enabled: Boolean) { nativeWidget.qWidgetPtr?.let { KdeDynamicBindings.setWidgetEnabled(it, enabled) } }
    override fun onNativeOpacityChanged(opacity: Float) {}
    override fun onNativeTooltipChanged(tooltip: String?) { KdePainterSupport.applyCommonWidgetProperties(this, nativeWidget) }
    override fun onNativeCursorChanged(cursor: CssCursorValue?) { KdePainterSupport.applyCommonWidgetProperties(this, nativeWidget) }
    override fun onNativePointerEventsChanged(pointerEventsNone: Boolean) { KdePainterSupport.applyCommonWidgetProperties(this, nativeWidget) }
    override fun onNativeZIndexChanged(zIndex: Int) { nativeWidget.qWidgetPtr?.let { KdeDynamicBindings.raiseWidget(it) } }
    override fun onNativeTransformChanged(transform: CssTransform?) {}
    override fun onNativeStyleSynced() { KdePainterSupport.applyCommonWidgetProperties(this, nativeWidget) }

    override fun dispose() {
        itemAddressToNode.clear()
        nodeIdToItemPtr.clear()
        super.dispose()
        nativeWidget.destroyQWidget()
    }
}
