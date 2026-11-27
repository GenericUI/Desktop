@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.component.tabs.TabBarStyle
import com.nexustools.genericui.component.tabs.TabPosition
import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTabContainerNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.linux.LinuxTabIconHelper
import com.nexustools.genericui.nativeimpl.tabs.TabNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.COpaquePointer
import kotlin.math.roundToInt

/**
 * Retained KDE tab container backed by Qt 6 **`QTabBar`** + **`QStackedWidget`**, supporting
 * [TabPosition.TOP], [TabPosition.BOTTOM], [TabPosition.LEFT], and [TabPosition.RIGHT],
 * closable tabs, badges, dirty indicators, and immediate non-fading page switching.
 */
open class KdeTabContainerNativeImpl(
    nativeWidget: KdeWidgetHandle = KdeWidgetHandle("QTabWidget_StackedContainer")
) : DesktopTabContainerNativeImpl<KdeWidgetHandle>(nativeWidget) {

    override val supportsNativeFadeTransition: Boolean = false

    private val tabBarHandle: KdeWidgetHandle
    private val stackedHandle: KdeWidgetHandle
    private var suppressTabSignal = false
    private var barTabCount = 0
    private val stackedPages = linkedMapOf<Int, COpaquePointer>()

    init {
        KdeDynamicBindings.ensureQApplication()
        val rootPtr = KdeDynamicBindings.createQWidget(null, 0)
        val barPtr = KdeDynamicBindings.createQTabBar(rootPtr)
        val stackPtr = KdeDynamicBindings.createQStackedWidget(rootPtr)
        KdeDynamicBindings.setWidgetVisible(barPtr, true)
        KdeDynamicBindings.setWidgetVisible(stackPtr, true)

        nativeWidget.attachToQWidget(rootPtr, "QTabWidget_StackedContainer", customPainted = false)
        tabBarHandle = KdeWidgetHandle("QTabBar", barPtr).apply { desiredParentPtr = rootPtr }
        stackedHandle = KdeWidgetHandle("QStackedWidget", stackPtr).apply { desiredParentPtr = rootPtr }

        KdeVTableBridge.connectSignal(barPtr, "currentChanged(int)") { args ->
            if (!suppressTabSignal) {
                val idx = KdeVTableBridge.readSignalArgInt(args, 1) ?: return@connectSignal
                GenericUiThreading.runOnLogic {
                    requestSelectTab(idx)
                }
            }
        }
        KdeVTableBridge.connectSignal(barPtr, "tabCloseRequested(int)") { args ->
            val idx = KdeVTableBridge.readSignalArgInt(args, 1) ?: return@connectSignal
            GenericUiThreading.runOnLogic {
                requestCloseTab(idx)
            }
        }
        KdeVTableBridge.attachWidgetHooks(rootPtr, KdeVTableBridge.HOOK_RESIZE) {
            onResize = { _, w, h -> layoutTabsAndContent(w, h) }
        }
        KdePainterSupport.attachStandardInputHooks(rootPtr, this, KdeVTableBridge.HOOK_RESIZE, dispatchLeftClickOnRelease = false)
        syncTabBarFromModel()
    }

    internal fun syncTabBarFromModel() {
        val barPtr = tabBarHandle.qWidgetPtr ?: return
        val stackPtr = stackedHandle.qWidgetPtr ?: return
        if (!KdeVTableBridge.isPeerAlive(barPtr) || !KdeVTableBridge.isPeerAlive(stackPtr)) return

        suppressTabSignal = true
        try {
            val qtShape = when (tabPositionVal) {
                TabPosition.BOTTOM -> KdeDynamicBindings.TAB_SOUTH
                TabPosition.LEFT -> KdeDynamicBindings.TAB_WEST
                TabPosition.RIGHT -> KdeDynamicBindings.TAB_EAST
                TabPosition.TOP -> KdeDynamicBindings.TAB_NORTH
            }
            KdeDynamicBindings.tabBarSetShape(barPtr, qtShape)
            KdeDynamicBindings.tabBarSetExpanding(barPtr, fullWidthTabsVal || tabBarStyleVal == TabBarStyle.PILLS)

            val anyClosable = tabImpls.indices.any { idx ->
                val kdeTab = tabImpls.getOrNull(idx) as? KdeTabNativeImpl
                kdeTab != null && !isButtonTab(idx) && kdeTab.closableVal && !kdeTab.pinnedVal
            }
            KdeDynamicBindings.tabBarSetClosable(barPtr, anyClosable)

            val iconSizePx = tabImpls.filterIsInstance<KdeTabNativeImpl>()
                .filter { !it.iconVal.isNullOrBlank() && LinuxTabIconHelper.looksLikeImageOrVectorSpec(it.iconVal.orEmpty()) }
                .maxOfOrNull { it.iconSizePxVal.coerceIn(12, 32) }
                ?: 16
            KdeDynamicBindings.tabBarSetIconSize(barPtr, iconSizePx, iconSizePx)

            // Sync page stack widgets & tab count
            val activeContentPtrs = contentImpls.mapIndexedNotNull { idx, cImpl ->
                if (!isButtonTab(idx)) (cImpl?.nativeWidget as? KdeWidgetHandle)?.qWidgetPtr else null
            }.toHashSet()
            for (oldPage in stackedPages.values) {
                KdeDynamicBindings.stackedRemoveWidget(stackPtr, oldPage)
                if (oldPage !in activeContentPtrs) {
                    KdeDynamicBindings.setWidgetVisible(oldPage, false)
                    KdeDynamicBindings.setWidgetParent(oldPage, null)
                }
            }
            stackedPages.clear()
            for (i in contentImpls.indices) {
                val cImpl = contentImpls[i]
                val cHandle = cImpl?.nativeWidget as? KdeWidgetHandle
                val cPtr = cHandle?.qWidgetPtr
                if (cPtr != null && !isButtonTab(i)) {
                    cHandle.desiredParentPtr = stackPtr
                    KdeDynamicBindings.stackedAddWidget(stackPtr, cPtr)
                    stackedPages[i] = cPtr
                }
            }

            // Rebuild QTabBar labels synchronously
            repeat(barTabCount) {
                KdeDynamicBindings.tabBarRemoveTab(barPtr, 0)
            }
            barTabCount = 0
            for (i in tabTitles.indices) {
                val tab = tabImpls.getOrNull(i) as? KdeTabNativeImpl
                tab?.ownerContainer = this
                val label = formatTabTitle(tabTitles[i], tab)
                KdeDynamicBindings.tabBarAddTab(barPtr, label)
                barTabCount++
                if (anyClosable && (isButtonTab(i) || tab == null || !tab.closableVal || tab.pinnedVal)) {
                    KdeDynamicBindings.tabBarHideTabCloseButton(barPtr, i)
                }
                if (tab != null) {
                    applyTabIconAtIndex(i, tab, barPtr)
                }
            }
            val safeSel = selectedIndexVal.coerceIn(0, (tabTitles.size - 1).coerceAtLeast(0))
            if (tabTitles.isNotEmpty()) {
                KdeDynamicBindings.tabBarSetCurrentIndex(barPtr, safeSel)
                stackedPages[safeSel]?.let { p ->
                    KdeDynamicBindings.stackedSetCurrentIndex(stackPtr, safeSel.coerceIn(0, (stackedPages.size - 1).coerceAtLeast(0)))
                }
            }
        } finally {
            suppressTabSignal = false
        }

        val b = nativeWidget.bounds
        if (b.width > 0 && b.height > 0) {
            layoutTabsAndContent(b.width, b.height)
        }
    }

    internal fun applySingleTabIcon(tab: KdeTabNativeImpl) {
        val barPtr = tabBarHandle.qWidgetPtr ?: return
        if (!KdeVTableBridge.isPeerAlive(barPtr)) return
        val idx = tabImpls.indexOfFirst { it === tab }
        if (idx !in 0 until barTabCount) return
        applyTabIconAtIndex(idx, tab, barPtr)
    }

    private fun applyTabIconAtIndex(index: Int, tab: KdeTabNativeImpl, barPtr: COpaquePointer) {
        val iconSpec = tab.iconVal?.trim().orEmpty()
        if (iconSpec.isEmpty() || !LinuxTabIconHelper.looksLikeImageOrVectorSpec(iconSpec)) {
            KdeDynamicBindings.tabBarClearTabIcon(barPtr, index)
            return
        }
        val rawBuf = tab.decodedIconBuffer
        if (rawBuf != null) {
            val prepared = LinuxTabIconHelper.prepareTabIconBuffer(
                raw = rawBuf,
                sizePx = tab.iconSizePxVal.coerceIn(12, 32),
                tintToTheme = tab.tintImageToThemeVal,
                isDark = LinuxTabIconHelper.isDarkTheme()
            )
            KdeDynamicBindings.tabBarSetTabIconFromBuffer(barPtr, index, prepared)
        } else {
            tab.ensureIconLoadStarted { applySingleTabIcon(tab) }
        }
    }

    private fun formatTabTitle(raw: String, tab: KdeTabNativeImpl?): String {
        val dirty = tab?.nativeWidget?.attributes?.get("dirty") == true
        val badge = tab?.nativeWidget?.attributes?.get("badgeText") as? String
        val rawIcon = tab?.iconVal?.trim()?.takeIf { it.isNotEmpty() && !LinuxTabIconHelper.looksLikeImageOrVectorSpec(it) }
        return buildString {
            if (rawIcon != null) append(rawIcon).append(' ')
            append(raw)
            if (dirty) append(" •")
            if (!badge.isNullOrBlank()) append(" [$badge]")
        }
    }

    private fun layoutTabsAndContent(totalW: Int, totalH: Int) {
        val barPtr = tabBarHandle.qWidgetPtr ?: return
        val stackPtr = stackedHandle.qWidgetPtr ?: return
        if (totalW <= 0 || totalH <= 0) return

        val stripThickness = if (tabPositionVal == TabPosition.LEFT || tabPositionVal == TabPosition.RIGHT) 140 else 36
        when (tabPositionVal) {
            TabPosition.TOP -> {
                KdeDynamicBindings.setWidgetGeometry(barPtr, 0, 0, totalW, stripThickness)
                KdeDynamicBindings.setWidgetGeometry(stackPtr, 0, stripThickness, totalW, (totalH - stripThickness).coerceAtLeast(0))
                layoutSelectedContent(totalW, (totalH - stripThickness).coerceAtLeast(0))
            }
            TabPosition.BOTTOM -> {
                val contentH = (totalH - stripThickness).coerceAtLeast(0)
                KdeDynamicBindings.setWidgetGeometry(stackPtr, 0, 0, totalW, contentH)
                KdeDynamicBindings.setWidgetGeometry(barPtr, 0, contentH, totalW, stripThickness)
                layoutSelectedContent(totalW, contentH)
            }
            TabPosition.LEFT -> {
                val contentW = (totalW - stripThickness).coerceAtLeast(0)
                KdeDynamicBindings.setWidgetGeometry(barPtr, 0, 0, stripThickness, totalH)
                KdeDynamicBindings.setWidgetGeometry(stackPtr, stripThickness, 0, contentW, totalH)
                layoutSelectedContent(contentW, totalH)
            }
            TabPosition.RIGHT -> {
                val contentW = (totalW - stripThickness).coerceAtLeast(0)
                KdeDynamicBindings.setWidgetGeometry(stackPtr, 0, 0, contentW, totalH)
                KdeDynamicBindings.setWidgetGeometry(barPtr, contentW, 0, stripThickness, totalH)
                layoutSelectedContent(contentW, totalH)
            }
        }
    }

    private fun layoutSelectedContent(cw: Int, ch: Int) {
        for (i in contentImpls.indices) {
            val child = contentImpls[i] as? DesktopBaseNativeImpl<*> ?: continue
            child.setBounds(Rect(0f, 0f, cw.toFloat(), ch.toFloat()))
        }
    }

    override fun addTabNative(tabImpl: TabNativeImpl<*>, contentImpl: NativeImpl<*>, index: Int) {
        (tabImpl as? KdeTabNativeImpl)?.ownerContainer = this
        super.addTabNative(tabImpl, contentImpl, index)
        syncTabBarFromModel()
    }

    override fun addButtonTabNative(tabImpl: TabNativeImpl<*>, index: Int) {
        (tabImpl as? KdeTabNativeImpl)?.ownerContainer = this
        super.addButtonTabNative(tabImpl, index)
        syncTabBarFromModel()
    }

    override fun addTabNative(title: String, contentImpl: NativeImpl<*>, index: Int) {
        super.addTabNative(title, contentImpl, index)
        syncTabBarFromModel()
    }

    override fun removeTabNative(index: Int) {
        super.removeTabNative(index)
        syncTabBarFromModel()
    }

    override fun setTabTitleNative(index: Int, title: String) {
        super.setTabTitleNative(index, title)
        syncTabBarFromModel()
    }

    override fun setTabPositionNative(position: TabPosition) {
        super.setTabPositionNative(position)
        syncTabBarFromModel()
    }

    override fun setTabBarStyleNative(style: TabBarStyle) {
        super.setTabBarStyleNative(style)
        syncTabBarFromModel()
    }

    override fun setFullWidthTabsNative(fullWidth: Boolean) {
        super.setFullWidthTabsNative(fullWidth)
        syncTabBarFromModel()
    }

    override fun setSelectedTabIndex(index: Int, fadeTransition: Boolean) {
        super.setSelectedTabIndex(index, fadeTransition)
        val barPtr = tabBarHandle.qWidgetPtr
        val stackPtr = stackedHandle.qWidgetPtr
        suppressTabSignal = true
        try {
            if (barPtr != null && index in tabTitles.indices) {
                KdeDynamicBindings.tabBarSetCurrentIndex(barPtr, index)
            }
            if (stackPtr != null && index in contentImpls.indices) {
                val cPtr = (contentImpls[index]?.nativeWidget as? KdeWidgetHandle)?.qWidgetPtr
                if (cPtr != null) {
                    val stackIdx = stackedPages.keys.indexOf(index)
                    if (stackIdx >= 0) KdeDynamicBindings.stackedSetCurrentIndex(stackPtr, stackIdx)
                }
            }
        } finally {
            suppressTabSignal = false
        }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size =
        measureTabContainerIntrinsicSize(
            stripThicknessPx = 36,
            verticalStripWidthPx = 140,
            maxWidth = maxWidth,
            maxHeight = maxHeight
        )

    override fun onNativeBoundsChanged(rect: Rect) {
        nativeWidget.applyBoundsToNative(rect)
        layoutTabsAndContent(rect.width, rect.height)
    }

    override fun onNativeVisibilityChanged(visible: Boolean) { nativeWidget.syncVisibilityToNative(visible) }
    override fun onNativeEnabledChanged(enabled: Boolean) { nativeWidget.qWidgetPtr?.let { KdeDynamicBindings.setWidgetEnabled(it, enabled) } }
    override fun onNativeTooltipChanged(tooltip: String?) {
        KdePainterSupport.applyCommonWidgetProperties(nativeWidget, customCursorState, nativeWidget.isSelfPointerEventsNone(), tooltip, getOpacity())
    }
    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        KdePainterSupport.applyCommonWidgetProperties(nativeWidget, cursor, nativeWidget.isSelfPointerEventsNone(), nativeWidget.tooltipText, getOpacity())
    }
    override fun onNativePointerEventsChanged(pointerEventsNone: Boolean) {
        KdePainterSupport.applyCommonWidgetProperties(nativeWidget, customCursorState, pointerEventsNone, nativeWidget.tooltipText, getOpacity())
    }
    override fun onNativeTransformChanged(transform: CssTransform?) { nativeWidget.requestRepaint() }

    override fun dispose() {
        nativeWidget.destroyQWidget()
        super.dispose()
    }
}
