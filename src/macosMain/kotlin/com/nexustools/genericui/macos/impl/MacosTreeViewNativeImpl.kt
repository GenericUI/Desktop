@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos.impl

import com.nexustools.genericui.component.lazy.TreeNode
import com.nexustools.genericui.desktop.lazy.DesktopTreeViewNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.macos.MacosAppKitDispatch
import com.nexustools.genericui.macos.MacosControlActionTarget
import com.nexustools.genericui.macos.MacosViewHandle
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssTransform
import platform.AppKit.NSOutlineView
import platform.AppKit.NSOutlineViewDataSourceProtocol
import platform.AppKit.NSOutlineViewDelegateProtocol
import platform.AppKit.NSScrollView
import platform.AppKit.NSTableColumn
import platform.AppKit.NSTableColumnAutoresizingMask
import platform.AppKit.NSTextField
import platform.AppKit.NSView
import platform.AppKit.labelWithString
import platform.Foundation.NSMakeRect
import platform.Foundation.NSNotification
import platform.darwin.NSObject

internal class MacosTreeNodeHandle(
    val node: TreeNode<*>
) : NSObject()

internal class MacosOutlineDataBridge(
    private val rootsProvider: () -> List<TreeNode<*>>,
    private val wrapperLookup: (TreeNode<*>) -> MacosTreeNodeHandle,
    private val onNodeSelected: (TreeNode<*>) -> Unit
) : NSObject(), NSOutlineViewDataSourceProtocol, NSOutlineViewDelegateProtocol {

    override fun outlineView(outlineView: NSOutlineView, numberOfChildrenOfItem: Any?): Long {
        val handle = numberOfChildrenOfItem as? MacosTreeNodeHandle ?: return rootsProvider().size.toLong()
        handle.node.ensureChildrenLoaded()
        return handle.node.children.size.toLong()
    }

    override fun outlineView(outlineView: NSOutlineView, child: Long, ofItem: Any?): Any {
        val list = if (ofItem is MacosTreeNodeHandle) {
            ofItem.node.ensureChildrenLoaded()
            ofItem.node.children
        } else {
            rootsProvider()
        }
        val target = list.getOrNull(child.toInt())
        return if (target != null) wrapperLookup(target) else NSObject()
    }

    override fun outlineView(outlineView: NSOutlineView, isItemExpandable: Any): Boolean {
        val handle = isItemExpandable as? MacosTreeNodeHandle ?: return false
        return handle.node.children.isNotEmpty() || handle.node.lazyChildrenLoader != null
    }

    override fun outlineView(outlineView: NSOutlineView, viewForTableColumn: NSTableColumn?, item: Any): NSView? {
        val handle = item as? MacosTreeNodeHandle ?: return null
        return NSTextField.labelWithString(handle.node.label)
    }

    override fun outlineViewSelectionDidChange(notification: NSNotification) {
        val ov = notification.`object` as? NSOutlineView ?: return
        val row = ov.selectedRow
        if (row >= 0L) {
            val handle = ov.itemAtRow(row) as? MacosTreeNodeHandle
            handle?.let { onNodeSelected(it.node) }
        }
    }

    override fun outlineViewItemDidExpand(notification: NSNotification) {
        val handle = notification.userInfo?.get("NSObject") as? MacosTreeNodeHandle ?: return
        handle.node.isExpanded = true
        handle.node.ensureChildrenLoaded()
    }

    override fun outlineViewItemDidCollapse(notification: NSNotification) {
        val handle = notification.userInfo?.get("NSObject") as? MacosTreeNodeHandle ?: return
        handle.node.isExpanded = false
    }
}

/**
 * macOS AppKit retained [NSScrollView] + [NSOutlineView] implementation of
 * [DesktopTreeViewNativeImpl].
 */
class MacosTreeViewNativeImpl(
    nativeWidget: MacosViewHandle
) : DesktopTreeViewNativeImpl<MacosViewHandle>(nativeWidget) {

    private val nodeHandles = LinkedHashMap<String, MacosTreeNodeHandle>()
    private var scrollView: NSScrollView? = null
    private var outlineView: NSOutlineView? = null
    private var dataBridge: MacosOutlineDataBridge? = null

    private val doubleClickTarget = MacosControlActionTarget { sender ->
        val ov = sender as? NSOutlineView ?: return@MacosControlActionTarget
        val row = ov.clickedRow
        if (row >= 0L) {
            val handle = ov.itemAtRow(row) as? MacosTreeNodeHandle
            handle?.let { onNodeDoubleClickedListener?.invoke(it.node) }
        }
    }

    init {
        nativeWidget.viewMaterializer = {
            ensureOutlineView().also { reloadTree() }
        }
        if (MacosAppKitDispatch.isMainThread()) {
            ensureOutlineView()
            reloadTree()
        }
    }

    private fun wrapperFor(node: TreeNode<*>): MacosTreeNodeHandle =
        nodeHandles.getOrPut(node.id) { MacosTreeNodeHandle(node) }

    private fun ensureOutlineView(): NSScrollView {
        scrollView?.let { return it }
        val bridge = MacosOutlineDataBridge(
            rootsProvider = { rootNodesList },
            wrapperLookup = { wrapperFor(it) },
            onNodeSelected = { node ->
                selectedNodeIdVal = node.id
                nativeWidget.attributes["selectedNodeId"] = node.id
                onNodeSelectedListener?.invoke(node)
            }
        )
        val col = NSTableColumn(identifier = "tree_col").apply {
            setTitle("")
            setResizingMask(NSTableColumnAutoresizingMask)
            setWidth(240.0)
        }
        val ov = NSOutlineView(frame = NSMakeRect(0.0, 0.0, 260.0, 320.0)).apply {
            setHeaderView(null)
            setRowHeight(26.0)
            addTableColumn(col)
            setOutlineTableColumn(col)
            setDataSource(bridge)
            setDelegate(bridge)
            setTarget(doubleClickTarget)
            setDoubleAction(doubleClickTarget.actionSelector)
        }
        val sv = NSScrollView(frame = NSMakeRect(0.0, 0.0, 260.0, 320.0)).apply {
            setHasVerticalScroller(true)
            setHasHorizontalScroller(true)
            setDrawsBackground(false)
            setAutohidesScrollers(true)
            setDocumentView(ov)
        }
        dataBridge = bridge
        outlineView = ov
        scrollView = sv
        nativeWidget.nsView = sv
        sv.setHidden(!nativeWidget.visible)
        sv.setAlphaValue(nativeWidget.opacity.coerceIn(0f, 1f).toDouble())
        sv.setToolTip(nativeWidget.tooltipText)
        return sv
    }

    private fun reloadTree() {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { reloadTree() }
            return
        }
        ensureOutlineView()
        nodeHandles.clear()
        val ov = outlineView ?: return
        ov.reloadData()

        fun applyExpansion(nodes: List<TreeNode<*>>) {
            for (n in nodes) {
                if (n.isExpanded) {
                    val h = wrapperFor(n)
                    ov.expandItem(h)
                    applyExpansion(n.children)
                }
            }
        }
        applyExpansion(rootNodesList)
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size =
        Size((maxWidth?.toInt() ?: 260).coerceAtLeast(160), (maxHeight?.toInt() ?: 320).coerceAtLeast(120))

    override fun setRootNodes(roots: List<TreeNode<*>>) {
        super.setRootNodes(roots)
        reloadTree()
    }

    override fun setSelectedNodeId(nodeId: String?) {
        super.setSelectedNodeId(nodeId)
        val ov = outlineView ?: return
        if (nodeId == null) {
            ov.deselectAll(null)
        } else {
            val handle = nodeHandles[nodeId]
            if (handle != null) {
                val row = ov.rowForItem(handle)
                if (row >= 0L) {
                    ov.selectRowIndexes(platform.Foundation.NSIndexSet.indexSetWithIndex(row.toULong()), byExtendingSelection = false)
                }
            }
        }
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { onNativeBoundsChanged(nativeWidget.bounds) }
            return
        }
        val sv = (nativeWidget.ensureNsViewOnMain() as? NSScrollView) ?: scrollView ?: return
        MacosAppKitDispatch.applyFrameAndTransform(sv, rect, nativeWidget.cssTransform, useAlignmentRectExpansion = false)
        outlineView?.tableColumns?.firstOrNull()?.let { col ->
            (col as? NSTableColumn)?.setWidth(rect.width.coerceAtLeast(120).toDouble())
        }
    }

    override fun onNativeVisibilityChanged(visible: Boolean) {
        scrollView?.setHidden(!visible)
    }

    override fun onNativeOpacityChanged(opacity: Float) {
        scrollView?.setAlphaValue(opacity.coerceIn(0f, 1f).toDouble())
    }

    override fun onNativeTooltipChanged(tooltip: String?) {
        scrollView?.setToolTip(tooltip)
    }

    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        scrollView?.let { v -> v.window?.invalidateCursorRectsForView(v) }
    }

    override fun onNativeTransformChanged(transform: CssTransform?) {
        nativeWidget.cssTransform = transform
        val sv = scrollView ?: return
        MacosAppKitDispatch.applyFrameAndTransform(sv, nativeWidget.bounds, transform, useAlignmentRectExpansion = false)
    }

    override fun dispose() {
        val ov = outlineView
        val sv = scrollView
        nodeHandles.clear()
        outlineView = null
        scrollView = null
        dataBridge = null
        MacosAppKitDispatch.onMainOrDefer {
            ov?.setDataSource(null)
            ov?.setDelegate(null)
            sv?.removeFromSuperview()
        }
        super.dispose()
    }
}
