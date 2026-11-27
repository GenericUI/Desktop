@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.component.layout.LayoutBackendTarget
import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.dialog.DesktopDialogWindowNativeImpl
import com.nexustools.genericui.desktop.dock.DesktopDockWindowNativeImpl
import com.nexustools.genericui.desktop.layout.DesktopContainerLayoutController
import com.nexustools.genericui.enums.css.CssDisplay
import com.nexustools.genericui.enums.bootstrap.FlexDirection
import com.nexustools.genericui.geometry.Rect
import kotlinx.cinterop.CValue
import kotlinx.cinterop.useContents
import platform.AppKit.NSView
import platform.AppKit.NSWindow
import platform.Foundation.NSRect

internal class MacosWindowSession(
    val windowKey: Long,
    val nsWindow: NSWindow,
    val rootView: MacosWindowRootView,
    val delegate: MacosWindowDelegate,
    val isDialog: Boolean,
    var windowImpl: MacosWindowNativeImpl? = null,
    var dialogImpl: DesktopDialogWindowNativeImpl<*>? = null,
    var dockWindowImpl: DesktopDockWindowNativeImpl<*>? = null,
    var ownerWindowKey: Long? = null,
    var needsRelayout: Boolean = true,
    var needsRedisplay: Boolean = true,
    var needsStageOnlyRedisplay: Boolean = false,
    var isShown: Boolean = false,
    var isClosing: Boolean = false,
    var activeResizeEdge: MacosNativeWindowHost.UndecoratedResizeEdge = MacosNativeWindowHost.UndecoratedResizeEdge.NONE,
    var resizeStartScreenX: Double = 0.0,
    var resizeStartScreenY: Double = 0.0,
    var resizeStartFrame: CValue<NSRect>? = null,
    var activeFullscreen: Boolean = false,
    var preFullscreenWasUndecorated: Boolean = false,
    var lastLaidOutWidth: Int = -1,
    var lastLaidOutHeight: Int = -1,
    var lastMutationVersion: Long = -1L,
    var isSyncingSize: Boolean = false,
    var lastHoverTargetView: NSView? = null,
    var lastDispatchedFocus: Boolean? = null,
    var lastLeftMouseDownEvent: platform.AppKit.NSEvent? = null
) {
    private var rootLayoutController: DesktopContainerLayoutController? = null
    private var layoutHostRef: DesktopBaseContainerNativeImpl<*>? = null
    private var lastStageTopologyVersion: Long = -1L
    var lastChromeSyncMutationVersion: Long = -1L
    var lastChromeFullscreenElement: Any? = null
    var lastChromeUndecorated: Boolean = false
    var lastChromeActiveFullscreen: Boolean = false
    var lastChromeNativeOverlayTitleBar: Boolean = false
    var lastChromeWindowStyleMask: ULong = 0uL
    var nativeTitleBarHoverPinned: Boolean = false
    var lastSyncedNativeTitleBarVisible: Boolean? = null
    var lastSyncedNativeTitleBarAlpha: Double = -1.0
    var activeWindowResizeDragSession: com.nexustools.genericui.event.PointerDragSession? = null
    var lastRootOrderedViews: List<NSView> = emptyList()
    var lastRootPinnedBack: List<NSView> = emptyList()
    val stageViews = mutableListOf<MacosInteractiveSurfaceView>()
    var titleBarView: MacosTitleBarView? = null
    internal var titleBarInsetTopPx: Int = 28
    internal var attachedUnifiedNavbar: com.nexustools.genericui.macos.impl.MacosNavbarNativeImpl? = null

    fun hostContainer(): DesktopBaseContainerNativeImpl<*>? =
        windowImpl ?: dialogImpl ?: dockWindowImpl

    fun currentClientSize(): Pair<Int, Int> =
        rootView.bounds.useContents {
            size.width.toInt().coerceAtLeast(1) to size.height.toInt().coerceAtLeast(1)
        }

    fun layoutWindowTree(force: Boolean = false) {
        val host = hostContainer() ?: return
        MacosNativeWindowHost.syncUnifiedTitleBarChrome(this)
        val (w, h) = currentClientSize()
        val mutVer = DesktopNativeWidget.globalLayoutMutationVersion
        if (!force &&
            !needsRelayout &&
            lastLaidOutWidth == w &&
            lastLaidOutHeight == h &&
            lastMutationVersion == mutVer
        ) {
            return
        }

        host.nativeWidget.attributes["layoutBackendTarget"] = LayoutBackendTarget.MACOS
        (host.nativeWidget as? MacosViewHandle)?.let { handle ->
            handle.nsWindow = nsWindow
            handle.nsView = rootView
        }

        val fsElement = windowImpl?.getFullscreenElement() as? DesktopBaseNativeImpl<*>
        val pinnedBack = mutableListOf<NSView>()

        if (fsElement != null) {
            host.nativeWidget.attributes["contentOffsetY"] = 0
            rootView.unifiedHeaderView.setHidden(true)
            rootView.unifiedHeaderHeightPx = 0.0
            val fsView = MacosAppKitDispatch.resolveOrCreateChildNsView(fsElement)
            val orderedViews = if (fsView != null) listOf(fsView) else emptyList()
            if (needsRootHierarchySync(orderedViews, pinnedBack)) {
                MacosAppKitDispatch.syncChildViewHierarchy(rootView, orderedViews, pinnedBack)
                lastRootOrderedViews = orderedViews
                lastRootPinnedBack = pinnedBack
            }
            fsElement.setBounds(Rect(0, 0, w, h))
            if (fsElement is DesktopBaseContainerNativeImpl<*>) {
                fsElement.layoutChildrenInBounds(w.toFloat(), h.toFloat())
            }
            if (fsView != null) {
                MacosAppKitDispatch.applyFrameAndTransform(
                    view = fsView,
                    bounds = Rect(0, 0, w, h),
                    transform = (fsElement.nativeWidget as? MacosViewHandle)?.cssTransform,
                    useAlignmentRectExpansion = false,
                    knownHandle = fsElement.nativeWidget as? MacosViewHandle
                )
            }
            rootView.contentRootView = fsView
        } else {
            val topInset = titleBarInsetTopPx.coerceAtLeast(0)
            host.nativeWidget.attributes["contentOffsetY"] = topInset
            val contentH = (h - topInset).coerceAtLeast(1)
            val controller = ensureRootLayoutController(host)
            host.setBounds(Rect(0, 0, w, h))
            controller.layoutInBounds(w.toFloat(), contentH.toFloat())

            val orderedImpls = controller.resolveChildrenSortedByZIndex()
            val orderedViews = ArrayList<NSView>(orderedImpls.size + 1)
            for (childImpl in orderedImpls) {
                val childView = MacosAppKitDispatch.resolveOrCreateChildNsView(childImpl)
                if (childView != null) {
                    orderedViews.add(childView)
                    val b = childImpl.nativeWidget.bounds
                    val framed = if (topInset > 0) {
                        Rect(b.x, b.y + topInset, b.width, b.height)
                    } else {
                        b
                    }
                    MacosAppKitDispatch.applyFrameAndTransform(
                        view = childView,
                        bounds = framed,
                        transform = (childImpl.nativeWidget as? MacosViewHandle)?.cssTransform,
                        useAlignmentRectExpansion = childImpl.usesNativeControlAlignmentExpansion(),
                        knownHandle = childImpl.nativeWidget as? MacosViewHandle
                    )
                }
            }
            val unifiedNav = attachedUnifiedNavbar
            if (unifiedNav != null && topInset > 0) {
                val navBounds = unifiedNav.nativeWidget.bounds
                val navH = (navBounds.y + navBounds.height).coerceAtLeast(44)
                val headerH = topInset + navH
                val navDarkTheme = MacosAppKitAppearance.isDarkTheme(unifiedNav.nativeWidget)
                rootView.unifiedHeaderView.setHidden(false)
                rootView.unifiedHeaderView.updateChromeAppearance(navDarkTheme, w, headerH)
                rootView.unifiedHeaderHeightPx = headerH.toDouble()
                pinnedBack.add(0, rootView.unifiedHeaderView)
            } else {
                rootView.unifiedHeaderView.setHidden(true)
                rootView.unifiedHeaderHeightPx = 0.0
            }
            titleBarView?.let { tb ->
                val win = windowImpl
                if (win != null) {
                    val tbInstructions = win.getTitleBarInstructions()
                    val barH = (tbInstructions?.heightPx ?: 28).coerceAtLeast(28)
                    tb.setFrame(platform.Foundation.NSMakeRect(0.0, 0.0, w.toDouble(), barH.toDouble()))
                    tb.syncAndLayout(win, nsWindow, w, barH, activeFullscreen || win.isFullscreen())
                }
                orderedViews.add(tb)
            }
            if (needsRootHierarchySync(orderedViews, pinnedBack)) {
                MacosAppKitDispatch.syncChildViewHierarchy(rootView, orderedViews, pinnedBack)
                lastRootOrderedViews = orderedViews
                lastRootPinnedBack = pinnedBack
            }
            rootView.contentRootView = orderedViews.firstOrNull()
        }

        if (lastStageTopologyVersion != MacosNativeWindowHost.stageTopologyVersion) {
            collectStageViews(rootView)
        }
        lastLaidOutWidth = w
        lastLaidOutHeight = h
        lastMutationVersion = mutVer
        needsRelayout = false
        if ((nsWindow as? MacosHostWindow)?.isNativeOverlayTitleBarHostWindow == true) {
            MacosNativeWindowHost.syncNativeOverlayTitleBarForSession(this)
        }
    }

    private fun needsRootHierarchySync(orderedViews: List<NSView>, pinnedBack: List<NSView>): Boolean {
        val isPinnedSame = pinnedBack.size == lastRootPinnedBack.size &&
            pinnedBack.indices.all { pinnedBack[it] === lastRootPinnedBack[it] }
        return !isPinnedSame ||
            orderedViews.size != lastRootOrderedViews.size ||
            orderedViews.indices.any { orderedViews[it] !== lastRootOrderedViews[it] }
    }

    private fun ensureRootLayoutController(host: DesktopBaseContainerNativeImpl<*>): DesktopContainerLayoutController {
        val existing = rootLayoutController
        if (existing != null && layoutHostRef === host) {
            val rawChildren = host.rawChildImplsSnapshot()
            val logicalChildren = existing.getLogicalChildren()
            if (logicalChildren.size != rawChildren.size ||
                logicalChildren.indices.any { logicalChildren[it] !== rawChildren[it] }
            ) {
                existing.clearLogicalChildren()
                for (child in rawChildren) {
                    existing.addLogicalChild(child)
                }
            }
            return existing
        }
        val created = DesktopContainerLayoutController(
            host = host,
            defaultDisplay = CssDisplay.FLEX,
            defaultFlexDirection = FlexDirection.COLUMN
        )
        layoutHostRef = host
        rootLayoutController = created
        return created
    }

    private fun collectStageViews(parent: NSView) {
        stageViews.clear()
        fun walk(v: NSView) {
            if (v is MacosInteractiveSurfaceView) {
                stageViews.add(v)
            }
            for (sub in v.subviews) {
                if (sub is NSView) walk(sub)
            }
        }
        walk(parent)
        lastStageTopologyVersion = MacosNativeWindowHost.stageTopologyVersion
        MacosNativeWindowHost.syncHostVBlankWatcher()
    }

    fun presentStageSurfaces(forceRefresh: Boolean = false): Boolean {
        if (stageViews.isEmpty()) {
            collectStageViews(rootView)
        }
        var updated = false
        for (stage in stageViews) {
            if (stage.presentLatestFrame(forceRefresh = forceRefresh)) {
                updated = true
            }
        }
        return updated
    }
}

internal fun DesktopBaseNativeImpl<*>.usesNativeControlAlignmentExpansion(): Boolean =
    (nativeWidget.attributes["usesAppKitAlignmentRect"] as? Boolean) == true
