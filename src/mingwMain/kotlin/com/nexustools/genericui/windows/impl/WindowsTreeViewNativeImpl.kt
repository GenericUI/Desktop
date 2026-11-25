@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.component.lazy.TreeNode
import com.nexustools.genericui.desktop.lazy.DesktopTreeViewNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.windows.Win32ControlMessageRouter
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.Win32RetainedControlSupport
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toLong
import kotlinx.cinterop.wcstr
import platform.windows.GetCursorPos
import platform.windows.HTREEITEM
import platform.windows.InvalidateRect
import platform.windows.NMTREEVIEWW
import platform.windows.NM_DBLCLK
import platform.windows.NM_RCLICK
import platform.windows.POINT
import platform.windows.ScreenToClient
import platform.windows.SendMessageW
import platform.windows.TVE_EXPAND
import platform.windows.TVGN_CARET
import platform.windows.TVIF_PARAM
import platform.windows.TVIF_TEXT
import platform.windows.TVINSERTSTRUCTW
import platform.windows.TVI_LAST
import platform.windows.TVI_ROOT
import platform.windows.TVM_DELETEITEM
import platform.windows.TVM_EXPAND
import platform.windows.TVM_GETNEXTITEM
import platform.windows.TVM_INSERTITEMW
import platform.windows.TVM_SELECTITEM
import platform.windows.TVN_ITEMEXPANDINGW
import platform.windows.TVN_SELCHANGEDW
import platform.windows.TVS_HASBUTTONS
import platform.windows.TVS_HASLINES
import platform.windows.TVS_LINESATROOT
import platform.windows.TVS_SHOWSELALWAYS
import platform.windows.WS_BORDER
import platform.windows.WS_CHILD
import platform.windows.WS_CLIPSIBLINGS
import platform.windows.WS_TABSTOP
import platform.windows.WS_VISIBLE

/**
 * Retained Win32 ComCtl32 `"SysTreeView32"` (`WC_TREEVIEWW`) implementation for `TreeView`,
 * supporting hierarchical expansion, lazy child loading, selection, double-click, and context menus.
 */
class WindowsTreeViewNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("SysTreeView32")
) : DesktopTreeViewNativeImpl<Win32HwndHandle>(widget) {

    private val itemHandleToNode = HashMap<Long, TreeNode<*>>()
    private val nodeIdToItemHandle = HashMap<String, HTREEITEM>()
    private val loadedLazyChildren = HashSet<Long>()
    private var syncingTree: Boolean = false

    init {
        nativeWidget.attributes["nativeImpl"] = this
        Win32ControlMessageRouter.createChildHwnd(
            handle = nativeWidget,
            owner = this,
            win32ClassName = "SysTreeView32",
            dwStyle = WS_CHILD or WS_VISIBLE or WS_TABSTOP or WS_BORDER or WS_CLIPSIBLINGS or
                TVS_HASBUTTONS or TVS_HASLINES or TVS_LINESATROOT or TVS_SHOWSELALWAYS,
            initialWidth = 260,
            initialHeight = 320,
            installSubclass = true
        )
        wireNotifications()
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = false)
    }

    private fun wireNotifications() {
        nativeWidget.onNotifyCallback = { code, nmhdrPtr ->
            val hwnd = nativeWidget.hwnd
            when (code) {
                TVN_SELCHANGEDW.toUInt() -> {
                    val tv = nmhdrPtr.reinterpret<NMTREEVIEWW>().pointed
                    val hItem = tv.itemNew.hItem
                    val node = hItem?.let { itemHandleToNode[it.toLong()] }
                    if (!syncingTree && node != null) {
                        selectedNodeIdVal = node.id
                        nativeWidget.attributes["selectedNodeId"] = node.id
                        onNodeSelectedListener?.invoke(node)
                    }
                    0L
                }
                TVN_ITEMEXPANDINGW.toUInt() -> {
                    val tv = nmhdrPtr.reinterpret<NMTREEVIEWW>().pointed
                    val hItem = tv.itemNew.hItem
                    if (hItem != null && tv.action == TVE_EXPAND.toUInt()) {
                        val key = hItem.toLong()
                        val node = itemHandleToNode[key]
                        if (node != null && loadedLazyChildren.add(key)) {
                            node.ensureChildrenLoaded()
                            val needsPopulate = node.children.any { it.id !in nodeIdToItemHandle }
                            if (needsPopulate && hwnd != null) {
                                for (child in node.children) {
                                    if (child.id !in nodeIdToItemHandle) {
                                        insertTreeNodeRecursive(hwnd, hItem, child)
                                    }
                                }
                            }
                        }
                    }
                    0L
                }
                NM_DBLCLK.toUInt() -> {
                    val selRes = if (hwnd != null) SendMessageW(hwnd, TVM_GETNEXTITEM.toUInt(), TVGN_CARET.toULong(), 0L) else 0L
                    val node = itemHandleToNode[selRes]
                    if (node != null) {
                        onNodeDoubleClickedListener?.invoke(node)
                    }
                    0L
                }
                NM_RCLICK.toUInt() -> {
                    val selRes = if (hwnd != null) SendMessageW(hwnd, TVM_GETNEXTITEM.toUInt(), TVGN_CARET.toULong(), 0L) else 0L
                    val node = itemHandleToNode[selRes]
                    if (node != null && hwnd != null) {
                        val clientPt = memScoped {
                            val pt = alloc<POINT>()
                            GetCursorPos(pt.ptr)
                            ScreenToClient(hwnd, pt.ptr)
                            pt.x.toFloat() to pt.y.toFloat()
                        }
                        onNodeContextMenuListener?.invoke(node, clientPt.first, clientPt.second)
                    }
                    0L
                }
                else -> null
            }
        }
    }

    override fun setRootNodes(roots: List<TreeNode<*>>) {
        super.setRootNodes(roots)
        rebuildTreeItems()
    }

    override fun setSelectedNodeId(nodeId: String?) {
        super.setSelectedNodeId(nodeId)
        val hwnd = nativeWidget.hwnd ?: return
        syncingTree = true
        try {
            val hItem = nodeId?.let { nodeIdToItemHandle[it] }
            SendMessageW(hwnd, TVM_SELECTITEM.toUInt(), TVGN_CARET.toULong(), hItem?.toLong() ?: 0L)
        } finally {
            syncingTree = false
        }
    }

    private fun rebuildTreeItems() {
        val hwnd = nativeWidget.hwnd ?: return
        syncingTree = true
        try {
            SendMessageW(hwnd, TVM_DELETEITEM.toUInt(), 0uL, TVI_ROOT.toLong())
            itemHandleToNode.clear()
            nodeIdToItemHandle.clear()
            loadedLazyChildren.clear()
            for (root in rootNodesList) {
                insertTreeNodeRecursive(hwnd, TVI_ROOT, root)
            }
            val selId = selectedNodeIdVal
            if (selId != null) {
                nodeIdToItemHandle[selId]?.let { hItem ->
                    SendMessageW(hwnd, TVM_SELECTITEM.toUInt(), TVGN_CARET.toULong(), hItem.toLong())
                }
            }
        } finally {
            syncingTree = false
        }
    }

    private fun insertTreeNodeRecursive(hwnd: platform.windows.HWND, parentItem: HTREEITEM?, node: TreeNode<*>) {
        val inserted: HTREEITEM? = memScoped {
            val tvis = alloc<TVINSERTSTRUCTW>()
            tvis.hParent = parentItem
            tvis.hInsertAfter = TVI_LAST
            tvis.item.mask = (TVIF_TEXT or TVIF_PARAM).toUInt()
            tvis.item.pszText = node.label.wcstr.ptr
            tvis.item.lParam = node.id.hashCode().toLong()
            val res = SendMessageW(hwnd, TVM_INSERTITEMW.toUInt(), 0uL, tvis.ptr.toLong())
            if (res != 0L) res.toCPointer() else null
        }
        val hItem = inserted ?: return
        val key = hItem.toLong()
        itemHandleToNode[key] = node
        nodeIdToItemHandle[node.id] = hItem
        if (node.isExpanded) {
            loadedLazyChildren.add(key)
            node.ensureChildrenLoaded()
        }
        for (child in node.children) {
            insertTreeNodeRecursive(hwnd, hItem, child)
        }
        if (node.isExpanded && node.children.isNotEmpty()) {
            SendMessageW(hwnd, TVM_EXPAND.toUInt(), TVE_EXPAND.toULong(), key)
        }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size =
        Size(220, (rootNodesList.size * 26 + 32).coerceIn(120, 480))

    override fun onNativeBoundsChanged(rect: Rect) {
        Win32RetainedControlSupport.onLeafBoundsChanged(nativeWidget, rect)
    }

    override fun onNativeVisibilityChanged(visible: Boolean) {
        Win32RetainedControlSupport.onVisibilityChanged(nativeWidget, visible)
    }

    override fun onNativeEnabledChanged(enabled: Boolean) {
        Win32RetainedControlSupport.onEnabledChanged(nativeWidget, enabled)
    }

    override fun onNativeOpacityChanged(opacity: Float) {
        Win32RetainedControlSupport.onOpacityChanged(nativeWidget, opacity)
    }

    override fun onNativeTooltipChanged(tooltip: String?) {
        Win32RetainedControlSupport.onTooltipChanged(nativeWidget, tooltip)
    }

    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        Win32RetainedControlSupport.onCursorChanged(nativeWidget, cursor)
    }

    override fun onNativePointerEventsChanged(pointerEventsNone: Boolean) {
        Win32RetainedControlSupport.onPointerEventsChanged(nativeWidget, pointerEventsNone)
    }

    override fun onNativeZIndexChanged(zIndex: Int) {
        Win32RetainedControlSupport.onZIndexChanged(nativeWidget, zIndex)
    }

    override fun onNativeStyleSynced() {
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = false)
        nativeWidget.hwnd?.let { InvalidateRect(it, null, 0) }
    }

    override fun dispose() {
        itemHandleToNode.clear()
        nodeIdToItemHandle.clear()
        Win32RetainedControlSupport.onDispose(nativeWidget)
        super.dispose()
    }
}
