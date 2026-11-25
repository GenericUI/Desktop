@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTabContainerNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.component.tabs.TabBarStyle
import com.nexustools.genericui.component.tabs.TabPosition
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.gtk.impl.GtkTabNativeImpl
import com.nexustools.genericui.gtk.widget.GtkCustomLayoutManager
import com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge
import com.nexustools.genericui.nativeimpl.tabs.TabNativeImpl
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.pointed
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Retained GTK4 tab container mounting a `GtkBox` tab strip + `GtkStack` page container via
 * `GtkCustomLayout`, supporting all 4 [TabPosition] orientations, close actions, badges, and
 * custom tab headers.
 */
class GtkTabContainerNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkStack_Tabs")
) : DesktopTabContainerNativeImpl<GtkWidgetHandle>(nativeWidget) {

    private val quarkTabHost by lazy {
        Gtk4DynamicBindings.gQuarkFromString("genericui-tab-container-host")
    }

    private var rootBoxPtr: COpaquePointer? = null
    private var tabStripBoxPtr: COpaquePointer? = null
    private var contentStackPtr: COpaquePointer? = null
    private val stackedPages = linkedMapOf<Int, Pair<String, COpaquePointer>>()

    init {
        nativeWidget.ownerImpl = this
        nativeWidget.widgetMaterializer = { materializeTabContainer() }
    }

    private fun materializeTabContainer(): COpaquePointer? {
        nativeWidget.realGtkWidgetPtr?.let { return it }
        if (!Gtk4DynamicBindings.ensureGtkInitialized()) return null

        val rootBox = Gtk4DynamicBindings.gtkBoxNew(Gtk4DynamicBindings.GTK_ORIENTATION_VERTICAL, 0) ?: return null
        val stripOrientation = if (isVerticalTabBar()) {
            Gtk4DynamicBindings.GTK_ORIENTATION_VERTICAL
        } else {
            Gtk4DynamicBindings.GTK_ORIENTATION_HORIZONTAL
        }
        val tabStrip = Gtk4DynamicBindings.gtkBoxNew(stripOrientation, 4) ?: return null
        val stack = Gtk4DynamicBindings.gtkStackNew() ?: return null
        Gtk4DynamicBindings.gtkStackSetTransitionType(stack, Gtk4DynamicBindings.GTK_STACK_TRANSITION_TYPE_NONE)

        Gtk4DynamicBindings.gtkBoxAppend(rootBox, tabStrip)
        Gtk4DynamicBindings.gtkBoxAppend(rootBox, stack)

        val stable = StableRef.create(this)
        Gtk4DynamicBindings.gObjectSetQdataFull(
            obj = rootBox,
            quark = quarkTabHost,
            data = stable.asCPointer(),
            destroy = destroyTabHostCb.reinterpret()
        )
        val mgr = Gtk4DynamicBindings.gtkCustomLayoutNew(
            requestMode = requestModeCb.reinterpret(),
            measure = measureCb.reinterpret(),
            allocate = allocateCb.reinterpret()
        )
        if (mgr != null) {
            Gtk4DynamicBindings.gtkWidgetSetLayoutManager(rootBox, mgr)
            nativeWidget.gtkLayoutManagerPtr = mgr
        }

        rootBoxPtr = rootBox
        tabStripBoxPtr = tabStrip
        contentStackPtr = stack
        nativeWidget.containerLayoutBoxPtr = stack

        GtkWidgetLifecycleBridge.attachControllersAndState(
            impl = this,
            rootPtr = rootBox,
            focusTargetPtr = rootBox,
            isTextInput = false,
            dispatchClickOnPrimaryRelease = false,
            attachClickGesture = true
        )
        rebuildLiveTabsAndPages()
        return rootBox
    }

    private fun isVerticalTabBar(): Boolean =
        getTabPosition() == TabPosition.LEFT || getTabPosition() == TabPosition.RIGHT

    private fun wireTabCallbacks(tabImpl: TabNativeImpl<*>?, index: Int) {
        val gtkTab = tabImpl as? GtkTabNativeImpl ?: return
        gtkTab.onHeaderChanged = { GtkCustomLayoutManager.requestRelayout(rootBoxPtr) }
        gtkTab.onSelectRequested = { requestSelectTab(index) }
        gtkTab.onCloseActionRequested = { requestCloseTab(index) }
    }

    private fun rebuildLiveTabsAndPages() {
        for (i in tabImpls.indices) {
            wireTabCallbacks(tabImpls[i], i)
        }
        val strip = tabStripBoxPtr ?: return
        val stack = contentStackPtr ?: return

        var stripChild = Gtk4DynamicBindings.gtkWidgetGetFirstChild(strip)
        while (stripChild != null) {
            val next = Gtk4DynamicBindings.gtkWidgetGetNextSibling(stripChild)
            Gtk4DynamicBindings.gtkWidgetUnparent(stripChild)
            stripChild = next
        }

        for ((_, pagePtr) in stackedPages.values) {
            Gtk4DynamicBindings.gtkStackRemove(stack, pagePtr)
        }
        stackedPages.clear()

        val count = tabTitles.size
        for (i in 0 until count) {
            val tabImpl = tabImpls.getOrNull(i)
            wireTabCallbacks(tabImpl, i)
            val tabHandle = tabImpl?.nativeWidget as? GtkWidgetHandle
            val tabPtr = tabHandle?.ensureLiveWidgetPtr() ?: tabHandle?.realGtkWidgetPtr
            if (tabPtr != null) {
                GtkCustomLayoutManager.attachChildWidget(strip, tabPtr, null)
            } else {
                val fallbackBtn = Gtk4DynamicBindings.gtkButtonNewWithLabel(tabTitles[i])
                if (fallbackBtn != null) {
                    Gtk4DynamicBindings.gtkBoxAppend(strip, fallbackBtn)
                }
            }

            val contentImpl = contentImpls.getOrNull(i)
            val contentHandle = contentImpl?.nativeWidget as? GtkWidgetHandle
            val pagePtr = contentHandle?.ensureLiveWidgetPtr() ?: contentHandle?.realGtkWidgetPtr
            if (pagePtr != null) {
                if (Gtk4DynamicBindings.gtkWidgetGetParent(pagePtr) != null) {
                    Gtk4DynamicBindings.gtkWidgetUnparent(pagePtr)
                }
                val pageName = "tab_$i"
                Gtk4DynamicBindings.gtkStackAddNamed(stack, pagePtr, pageName)
                stackedPages[i] = pageName to pagePtr
            }
        }
        syncVisibleStackPage()
        GtkCustomLayoutManager.requestRelayout(rootBoxPtr)
    }

    private fun syncVisibleStackPage() {
        val stack = contentStackPtr ?: return
        val activeName = stackedPages[selectedIndexVal]?.first ?: return
        Gtk4DynamicBindings.gtkStackSetVisibleChildName(stack, activeName)
    }

    fun layoutTabsAndContent(totalW: Int, totalH: Int) {
        val w = totalW.coerceAtLeast(1)
        val h = totalH.coerceAtLeast(1)
        val pos = getTabPosition()
        val count = tabTitles.size
        val stripThick = if (isVerticalTabBar()) 160 else 40

        val stripRect = when (pos) {
            TabPosition.BOTTOM -> Rect(0, (h - stripThick).coerceAtLeast(0), w, stripThick.coerceAtMost(h))
            TabPosition.LEFT -> Rect(0, 0, stripThick.coerceAtMost(w), h)
            TabPosition.RIGHT -> Rect((w - stripThick).coerceAtLeast(0), 0, stripThick.coerceAtMost(w), h)
            TabPosition.TOP -> Rect(0, 0, w, stripThick.coerceAtMost(h))
        }
        val contentRect = when (pos) {
            TabPosition.BOTTOM -> Rect(0, 0, w, (h - stripRect.height).coerceAtLeast(1))
            TabPosition.LEFT -> Rect(stripRect.width, 0, (w - stripRect.width).coerceAtLeast(1), h)
            TabPosition.RIGHT -> Rect(0, 0, (w - stripRect.width).coerceAtLeast(1), h)
            TabPosition.TOP -> Rect(0, stripRect.height, w, (h - stripRect.height).coerceAtLeast(1))
        }

        tabStripBoxPtr?.let {
            Gtk4DynamicBindings.gtkWidgetSizeAllocate(it, stripRect.x, stripRect.y, stripRect.width, stripRect.height, -1)
        }
        contentStackPtr?.let {
            Gtk4DynamicBindings.gtkWidgetSizeAllocate(it, contentRect.x, contentRect.y, contentRect.width, contentRect.height, -1)
        }

        if (count > 0) {
            var curX = 0
            var curY = 0
            val equalW = if (isFullWidthTabs() && !isVerticalTabBar()) (stripRect.width / count).coerceAtLeast(36) else 0
            for (i in 0 until count) {
                val tabImpl = tabImpls.getOrNull(i) as? DesktopBaseNativeImpl<*>
                val pref = tabImpl?.measureIntrinsicSize(null, null) ?: Size(96, 34)
                val tabW = if (isVerticalTabBar()) stripRect.width else if (equalW > 0) equalW else pref.width.coerceAtLeast(48)
                val tabH = if (isVerticalTabBar()) pref.height.coerceAtLeast(34) else stripRect.height
                val globalRect = Rect(stripRect.x + curX, stripRect.y + curY, tabW, tabH)
                tabImpl?.setBounds(globalRect)
                if (isVerticalTabBar()) curY += tabH else curX += tabW
            }
        }

        val activeContent = contentImpls.getOrNull(selectedIndexVal) as? DesktopBaseNativeImpl<*>
        activeContent?.setBounds(Rect(0, 0, contentRect.width, contentRect.height))
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size =
        measureTabContainerIntrinsicSize(
            stripThicknessPx = 40,
            verticalStripWidthPx = 160,
            maxWidth = maxWidth,
            maxHeight = maxHeight
        )

    override fun addTabNative(tabImpl: TabNativeImpl<*>, contentImpl: NativeImpl<*>, index: Int) {
        super.addTabNative(tabImpl, contentImpl, index)
        rebuildLiveTabsAndPages()
    }

    override fun addButtonTabNative(tabImpl: TabNativeImpl<*>, index: Int) {
        super.addButtonTabNative(tabImpl, index)
        rebuildLiveTabsAndPages()
    }

    override fun addTabNative(title: String, contentImpl: NativeImpl<*>, index: Int) {
        super.addTabNative(title, contentImpl, index)
        rebuildLiveTabsAndPages()
    }

    override fun removeTabNative(index: Int) {
        super.removeTabNative(index)
        rebuildLiveTabsAndPages()
    }

    override fun setTabTitleNative(index: Int, title: String) {
        super.setTabTitleNative(index, title)
        rebuildLiveTabsAndPages()
    }

    override fun setTabPositionNative(position: TabPosition) {
        super.setTabPositionNative(position)
        val b = getBounds()
        if (b.width > 0 && b.height > 0) {
            layoutTabsAndContent(b.width, b.height)
        }
        GtkCustomLayoutManager.requestRelayout(rootBoxPtr)
    }

    override fun setTabBarStyleNative(style: TabBarStyle) {
        super.setTabBarStyleNative(style)
        GtkCustomLayoutManager.requestRelayout(rootBoxPtr)
    }

    override fun setFullWidthTabsNative(fullWidth: Boolean) {
        super.setFullWidthTabsNative(fullWidth)
        GtkCustomLayoutManager.requestRelayout(rootBoxPtr)
    }

    override fun setSelectedTabIndex(index: Int, fadeTransition: Boolean) {
        super.setSelectedTabIndex(index, fadeTransition)
        syncVisibleStackPage()
        val b = getBounds()
        if (b.width > 0 && b.height > 0) {
            layoutTabsAndContent(b.width, b.height)
        }
    }

    override fun layoutChildrenInBounds(width: Float, height: Float) {
        layoutTabsAndContent(width.roundToInt(), height.roundToInt())
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        layoutTabsAndContent(rect.width, rect.height)
        GtkWidgetLifecycleBridge.onBoundsChanged(this, rect)
    }

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
        private val destroyTabHostCb = staticCFunction<COpaquePointer?, Unit> { ptr ->
            ptr?.asStableRef<GtkTabContainerNativeImpl>()?.dispose()
        }

        private val requestModeCb = staticCFunction<COpaquePointer?, Int> { _ ->
            Gtk4DynamicBindings.GTK_SIZE_REQUEST_HEIGHT_FOR_WIDTH
        }

        private val measureCb = staticCFunction<
            COpaquePointer?, Int, Int,
            kotlinx.cinterop.CPointer<IntVar>?,
            kotlinx.cinterop.CPointer<IntVar>?,
            kotlinx.cinterop.CPointer<IntVar>?,
            kotlinx.cinterop.CPointer<IntVar>?,
            Unit
        > { widget, orientation, forSize, minPtr, natPtr, minBasePtr, natBasePtr ->
            val host = widget?.let {
                val quark = Gtk4DynamicBindings.gQuarkFromString("genericui-tab-container-host")
                Gtk4DynamicBindings.gObjectGetQdata(it, quark)?.asStableRef<GtkTabContainerNativeImpl>()?.get()
            }
            val size = host?.measureIntrinsicSize(
                if (orientation == Gtk4DynamicBindings.GTK_ORIENTATION_VERTICAL && forSize > 0) forSize.toFloat() else null,
                null
            ) ?: Size(240, 180)
            val v = if (orientation == Gtk4DynamicBindings.GTK_ORIENTATION_HORIZONTAL) {
                size.width
            } else {
                size.height
            }
            minPtr?.pointed?.value = v
            natPtr?.pointed?.value = v
            minBasePtr?.pointed?.value = -1
            natBasePtr?.pointed?.value = -1
        }

        private val allocateCb = staticCFunction<COpaquePointer?, Int, Int, Int, Unit> { widget, width, height, _ ->
            widget ?: return@staticCFunction
            val quark = Gtk4DynamicBindings.gQuarkFromString("genericui-tab-container-host")
            val host = Gtk4DynamicBindings.gObjectGetQdata(widget, quark)?.asStableRef<GtkTabContainerNativeImpl>()?.get()
                ?: return@staticCFunction
            host.layoutTabsAndContent(width, height)
        }
    }
}
