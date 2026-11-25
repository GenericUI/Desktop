@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.impl

import com.nexustools.genericui.component.lazy.TreeNode
import com.nexustools.genericui.desktop.lazy.DesktopTreeViewNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.gtk.widget.GtkCustomLayoutManager
import com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge
import com.nexustools.genericui.linux.LinuxGlibGioBindings
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction

/**
 * Retained GTK4 hierarchical tree mounting a `GtkScrolledWindow` over collapsible `GtkExpander`
 * branches and selectable `GtkButton` / `GtkListBox` nodes.
 */
class GtkTreeViewNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkListView_GtkTreeListModel")
) : DesktopTreeViewNativeImpl<GtkWidgetHandle>(nativeWidget) {

    private class NodeClickPayload(
        val owner: GtkTreeViewNativeImpl,
        val node: TreeNode<*>
    )

    private var treeContainerBoxPtr: COpaquePointer? = null
    private val nodeButtonMap = linkedMapOf<String, COpaquePointer>()

    init {
        nativeWidget.ownerImpl = this
        nativeWidget.widgetMaterializer = { materializeTreeView() }
    }

    private fun materializeTreeView(): COpaquePointer? {
        nativeWidget.realGtkWidgetPtr?.let { return it }
        if (!Gtk4DynamicBindings.ensureGtkInitialized()) return null

        val sw = Gtk4DynamicBindings.gtkScrolledWindowNew() ?: return null
        val treeBox = Gtk4DynamicBindings.gtkBoxNew(Gtk4DynamicBindings.GTK_ORIENTATION_VERTICAL, 2) ?: return null
        Gtk4DynamicBindings.gtkScrolledWindowSetPolicy(
            sw,
            Gtk4DynamicBindings.GTK_POLICY_AUTOMATIC,
            Gtk4DynamicBindings.GTK_POLICY_AUTOMATIC
        )
        Gtk4DynamicBindings.gtkScrolledWindowSetChild(sw, treeBox)

        treeContainerBoxPtr = treeBox
        nativeWidget.scrolledWindowPtr = sw
        nativeWidget.primaryLeafPtr = treeBox

        GtkWidgetLifecycleBridge.attachControllersAndState(
            impl = this,
            rootPtr = sw,
            focusTargetPtr = treeBox,
            isTextInput = false,
            dispatchClickOnPrimaryRelease = false,
            attachClickGesture = false
        )
        rebuildTreeNodes()
        return sw
    }

    private fun rebuildTreeNodes() {
        val box = treeContainerBoxPtr ?: return
        var cur = Gtk4DynamicBindings.gtkWidgetGetFirstChild(box)
        while (cur != null) {
            val next = Gtk4DynamicBindings.gtkWidgetGetNextSibling(cur)
            Gtk4DynamicBindings.gtkBoxRemove(box, cur)
            cur = next
        }
        nodeButtonMap.clear()
        for (rootNode in rootNodesList) {
            buildNodeWidget(rootNode, depth = 0)?.let { childPtr ->
                Gtk4DynamicBindings.gtkBoxAppend(box, childPtr)
            }
        }
    }

    private fun buildNodeWidget(node: TreeNode<*>, depth: Int): COpaquePointer? {
        val rowBtn = Gtk4DynamicBindings.gtkButtonNewWithLabel(node.label) ?: return null
        Gtk4DynamicBindings.gtkWidgetAddCssClass(rowBtn, "flat")
        if (node.id == selectedNodeIdVal) {
            Gtk4DynamicBindings.gtkWidgetAddCssClass(rowBtn, "suggested-action")
        }
        nodeButtonMap[node.id] = rowBtn

        val payload = NodeClickPayload(this, node)
        val payloadRef = StableRef.create(payload)
        LinuxGlibGioBindings.signalConnect(
            instance = rowBtn,
            signalName = "clicked",
            handler = nodeClickedCb.reinterpret(),
            userData = payloadRef.asCPointer(),
            destroyNotify = destroyNodePayloadCb.reinterpret()
        )
        Gtk4DynamicBindings.gtkGestureClickNew()?.let { click ->
            Gtk4DynamicBindings.gtkGestureSingleSetButton(click, 0u)
            val clickRef = StableRef.create(payload)
            LinuxGlibGioBindings.signalConnect(
                instance = click,
                signalName = "pressed",
                handler = nodeGesturePressedCb.reinterpret(),
                userData = clickRef.asCPointer(),
                destroyNotify = destroyNodePayloadCb.reinterpret()
            )
            Gtk4DynamicBindings.gtkWidgetAddController(rowBtn, click)
        }

        val hasExpandableChildren = node.children.isNotEmpty() || node.lazyChildrenLoader != null
        if (!hasExpandableChildren) {
            Gtk4DynamicBindings.gtkWidgetSetMargins(rowBtn, 1, 1, 8 + depth * 16, 4)
            return rowBtn
        }

        val expander = Gtk4DynamicBindings.gtkExpanderNew(null) ?: return rowBtn
        Gtk4DynamicBindings.gtkExpanderSetLabelWidget(expander, rowBtn)
        Gtk4DynamicBindings.gtkWidgetSetMargins(expander, 1, 1, depth * 16, 4)
        Gtk4DynamicBindings.gtkExpanderSetExpanded(expander, node.isExpanded)

        val childrenBox = Gtk4DynamicBindings.gtkBoxNew(Gtk4DynamicBindings.GTK_ORIENTATION_VERTICAL, 2)
        if (childrenBox != null) {
            if (node.isExpanded) {
                node.ensureChildrenLoaded()
                for (child in node.children) {
                    buildNodeWidget(child, depth + 1)?.let { Gtk4DynamicBindings.gtkBoxAppend(childrenBox, it) }
                }
            }
            Gtk4DynamicBindings.gtkExpanderSetChild(expander, childrenBox)
        }

        val expPayloadRef = StableRef.create(payload)
        LinuxGlibGioBindings.signalConnect(
            instance = expander,
            signalName = "notify::expanded",
            handler = expanderToggledCb.reinterpret(),
            userData = expPayloadRef.asCPointer(),
            destroyNotify = destroyNodePayloadCb.reinterpret()
        )
        return expander
    }

    private fun refreshExpandedSubtree(expander: COpaquePointer, node: TreeNode<*>) {
        val expanded = Gtk4DynamicBindings.gtkExpanderGetExpanded(expander)
        node.isExpanded = expanded
        if (!expanded) return
        node.ensureChildrenLoaded()
        val childrenBox = Gtk4DynamicBindings.gtkBoxNew(Gtk4DynamicBindings.GTK_ORIENTATION_VERTICAL, 2) ?: return
        for (child in node.children) {
            buildNodeWidget(child, depth = 1)?.let { Gtk4DynamicBindings.gtkBoxAppend(childrenBox, it) }
        }
        Gtk4DynamicBindings.gtkExpanderSetChild(expander, childrenBox)
    }

    override fun setRootNodes(roots: List<TreeNode<*>>) {
        super.setRootNodes(roots)
        rebuildTreeNodes()
        GtkWidgetLifecycleBridge.requestParentRelayout(this)
    }

    override fun setSelectedNodeId(nodeId: String?) {
        val prev = selectedNodeIdVal
        super.setSelectedNodeId(nodeId)
        prev?.let { nodeButtonMap[it]?.let { btn -> Gtk4DynamicBindings.gtkWidgetRemoveCssClass(btn, "suggested-action") } }
        nodeId?.let { nodeButtonMap[it]?.let { btn -> Gtk4DynamicBindings.gtkWidgetAddCssClass(btn, "suggested-action") } }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val ptr = nativeWidget.realGtkWidgetPtr
        if (ptr != null) {
            val (w, h) = GtkCustomLayoutManager.measureGtkWidgetIntrinsic(ptr, maxWidth, maxHeight)
            if (w > 0 && h > 0) return Size(w.coerceAtLeast(160), h.coerceAtLeast(120))
        }
        return Size(220, 180)
    }

    override fun onNativeBoundsChanged(rect: Rect) = GtkWidgetLifecycleBridge.onBoundsChanged(this, rect)
    override fun onNativeVisibilityChanged(visible: Boolean) = GtkWidgetLifecycleBridge.onVisibilityChanged(this, visible)
    override fun onNativeEnabledChanged(enabled: Boolean) = GtkWidgetLifecycleBridge.onEnabledChanged(this, enabled)
    override fun onNativeOpacityChanged(opacity: Float) = GtkWidgetLifecycleBridge.onOpacityChanged(this, opacity)
    override fun onNativeTooltipChanged(tooltip: String?) = GtkWidgetLifecycleBridge.onTooltipChanged(this, tooltip)
    override fun onNativeCursorChanged(cursor: CssCursorValue?) = GtkWidgetLifecycleBridge.onCursorChanged(this, cursor)
    override fun onNativePointerEventsChanged(pointerEventsNone: Boolean) = GtkWidgetLifecycleBridge.onPointerEventsChanged(this, pointerEventsNone)
    override fun onNativeZIndexChanged(zIndex: Int) = GtkWidgetLifecycleBridge.onZIndexChanged(this, zIndex)
    override fun onNativeTransformChanged(transform: CssTransform?) = GtkWidgetLifecycleBridge.onTransformChanged(this, transform)
    override fun onNativeStyleSynced() = GtkWidgetLifecycleBridge.onStyleSynced(this)

    companion object {
        private val nodeClickedCb = staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { _, userData ->
            val p = userData?.asStableRef<NodeClickPayload>()?.get() ?: return@staticCFunction
            p.owner.setSelectedNodeId(p.node.id)
            p.owner.onNodeSelectedListener?.invoke(p.node)
        }

        private val nodeGesturePressedCb = staticCFunction<COpaquePointer?, Int, Double, Double, COpaquePointer?, Unit> { gesture, nPress, x, y, userData ->
            val p = userData?.asStableRef<NodeClickPayload>()?.get() ?: return@staticCFunction
            val btn = gesture?.let { Gtk4DynamicBindings.gtkGestureSingleGetCurrentButton(it) } ?: Gtk4DynamicBindings.GDK_BUTTON_PRIMARY
            if (btn == Gtk4DynamicBindings.GDK_BUTTON_SECONDARY) {
                p.owner.onNodeContextMenuListener?.invoke(p.node, x.toFloat(), y.toFloat())
            } else if (nPress >= 2) {
                p.owner.onNodeDoubleClickedListener?.invoke(p.node)
            }
        }

        private val expanderToggledCb = staticCFunction<COpaquePointer?, COpaquePointer?, COpaquePointer?, Unit> { expander, _, userData ->
            val p = userData?.asStableRef<NodeClickPayload>()?.get() ?: return@staticCFunction
            if (expander != null) {
                p.owner.refreshExpandedSubtree(expander, p.node)
            }
        }

        private val destroyNodePayloadCb = staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { data, _ ->
            data?.asStableRef<NodeClickPayload>()?.dispose()
        }
    }
}
