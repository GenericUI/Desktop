@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.shell.DesktopWindowNativeImpl
import com.nexustools.genericui.enums.window.DisplayCutoutMode
import com.nexustools.genericui.enums.window.WindowState
import com.nexustools.genericui.event.KeyEvent
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.linux.titlebar.LinuxTitleBarProfileManager
import com.nexustools.genericui.nativeimpl.shell.MenuBarNativeImpl
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl
import com.nexustools.genericui.style.transition.CssTransitionSpec
import com.nexustools.genericui.window.TitleBar
import com.nexustools.genericui.window.TitleCapabilities
import com.nexustools.genericui.window.WindowAspectRatioHelper
import com.nexustools.genericui.window.WindowConfiguration
import kotlin.math.roundToInt

/**
 * KDE Frameworks 6 + Breeze (`Qt 6`) implementation of [DesktopWindowNativeImpl].
 */
open class KdeWindowNativeImpl : DesktopWindowNativeImpl<KdeWidgetHandle>(
    nativeWidget = KdeWidgetHandle("QWidget_KDEWindow").apply {
        isTopLevelWindow = true
    },
    supportsCustomTitleBar = true,
    supportsEmbeddedMenuBarInTitleBar = true,
    supportsMultiWindow = true
) {
    private var suppressHostSync = false
    private val mediaKeyHooks = mutableListOf<(KeyEvent) -> Boolean>()
    internal var customTitleBarWidget: KdeCustomTitleBarWidget? = null

    var kdeCustomTitleBarActive: Boolean = false
        private set

    override val supportsCustomTitleBar: Boolean
        get() = getTitleCapabilities().supportsCustomTitleBar

    override val supportsEmbeddedMenuBarInTitleBar: Boolean
        get() = getTitleCapabilities().supportsEmbeddedMenuBar

    init {
        KdeDynamicBindings.ensureQApplication()
        val qWin = KdeDynamicBindings.createQWidget(null, KdeDynamicBindings.WINDOW_WINDOW)
        nativeWidget.attachToQWidget(qWin, "QWidget_KDEWindow", customPainted = false)
        customTitleBarWidget = KdeCustomTitleBarWidget(this, qWin)
        KdeVTableBridge.attachWidgetHooks(
            qWin,
            KdeVTableBridge.HOOK_RESIZE or KdeVTableBridge.HOOK_INPUT or KdeVTableBridge.HOOK_CLOSE or KdeVTableBridge.HOOK_WINDOW_EVENT
        ) {
            onResize = { _, w, h ->
                syncHostClientSize(w, h, fullscreenFlag, currentWindowState == WindowState.MAXIMIZED)
                layoutWindowChromeAndChildren(w.toFloat(), h.toFloat())
            }
            onWindowActivateChanged = { _, active -> notifyHostFocusChanged(active) }
            onFocusIn = { _ -> notifyHostFocusChanged(true) }
            onFocusOut = { wPtr ->
                if (!KdeDynamicBindings.hasIsWindowActiveSymbol() || !KdeDynamicBindings.isWindowActive(wPtr)) {
                    notifyHostFocusChanged(false)
                }
            }
            onKeyPress = { _, info ->
                dispatchWindowKeyDown(KdePainterSupport.toKeyEvent(info, null))
            }
            onKeyRelease = { _, info ->
                dispatchKeyUp(KdePainterSupport.toKeyEvent(info, null))
                false
            }
            onClose = { _ ->
                val allow = onWindowCloseRequestedListener?.invoke() ?: true
                if (allow) {
                    activeWindows.remove(this@KdeWindowNativeImpl)
                    KdeNativeWindowHost.onWindowClosed(this@KdeWindowNativeImpl)
                }
                allow
            }
        }
    }

    override fun configureAsToolModalWindow(
        ownerWindow: WindowNativeImpl<*>?,
        showInTaskbar: Boolean,
        minimizable: Boolean,
        alwaysOnTopOfOwner: Boolean
    ) {
        super.configureAsToolModalWindow(ownerWindow, showInTaskbar, minimizable, alwaysOnTopOfOwner)
        nativeWidget.attributes["skip-taskbar"] = !showInTaskbar
        nativeWidget.attributes["minimizable"] = minimizable
        nativeWidget.attributes["transient-for-owner"] = alwaysOnTopOfOwner
        syncTitleBarDerivedAttributes()
        if (KdeApplicationNativeImpl.interactiveKdeGuiEnabled) {
            KdeNativeWindowHost.syncWindowProperties(this)
        }
    }

    override fun getTitleCapabilities(): TitleCapabilities =
        LinuxTitleBarProfileManager.resolveTitleCapabilities(
            isToolModalWindow = isToolModalWindow,
            minimizable = minimizable
        )

    override fun applyTitleBarInstructions(titleBar: TitleBar?) {
        super.applyTitleBarInstructions(titleBar)
        val caps = getTitleCapabilities()
        val profile = LinuxTitleBarProfileManager.currentProfile()
        val customActive = !isUndecorated() && !isFullscreen() &&
            profile.isReplicable && caps.supportsCustomTitleBar &&
            (titleBar?.customTitleBar == true)
        if (titleBar != null && !customActive) {
            titleBar.setHostEmbedsComponents(false)
            for (comp in titleBar.leadingComponents + titleBar.centerComponents + titleBar.trailingComponents) {
                (comp.nativeImpl?.nativeWidget as? KdeWidgetHandle)?.attributes?.remove("mountedInTitleBar")
            }
        }
        syncTitleBarDerivedAttributes()
        val sz = currentWindowSize
        layoutWindowChromeAndChildren(sz.width, sz.height)
        if (KdeApplicationNativeImpl.interactiveKdeGuiEnabled) {
            KdeNativeWindowHost.syncWindowProperties(this)
        }
    }

    override fun setMenuBarNative(menuBarImpl: MenuBarNativeImpl<*>?) {
        super.setMenuBarNative(menuBarImpl)
        (menuBarImpl?.nativeWidget as? KdeWidgetHandle)?.syncChildParentAndVisibility(nativeWidget)
        syncTitleBarDerivedAttributes()
        layoutWindowChromeAndChildren(currentWindowSize.width, currentWindowSize.height)
        if (KdeApplicationNativeImpl.interactiveKdeGuiEnabled) {
            KdeNativeWindowHost.requestRedraw()
        }
    }

    override fun setNavBarNative(navBarImpl: NativeImpl<*>?) {
        super.setNavBarNative(navBarImpl)
        (navBarImpl?.nativeWidget as? KdeWidgetHandle)?.syncChildParentAndVisibility(nativeWidget)
        layoutWindowChromeAndChildren(currentWindowSize.width, currentWindowSize.height)
    }

    override fun setBottomNavigationBarNative(bottomNavImpl: NativeImpl<*>?) {
        super.setBottomNavigationBarNative(bottomNavImpl)
        (bottomNavImpl?.nativeWidget as? KdeWidgetHandle)?.syncChildParentAndVisibility(nativeWidget)
        layoutWindowChromeAndChildren(currentWindowSize.width, currentWindowSize.height)
    }

    override fun onChildAddedNative(childImpl: NativeImpl<*>, index: Int) {
        super.onChildAddedNative(childImpl, index)
        (childImpl.nativeWidget as? KdeWidgetHandle)?.syncChildParentAndVisibility(nativeWidget)
        layoutWindowChromeAndChildren(currentWindowSize.width, currentWindowSize.height)
    }

    override fun onChildRemovedNative(childImpl: NativeImpl<*>) {
        (childImpl.nativeWidget as? KdeWidgetHandle)?.syncChildParentAndVisibility(null)
        super.onChildRemovedNative(childImpl)
        layoutWindowChromeAndChildren(currentWindowSize.width, currentWindowSize.height)
    }

    override fun onChildrenClearedNative() {
        for (c in childImpls) {
            (c.nativeWidget as? KdeWidgetHandle)?.syncChildParentAndVisibility(null)
        }
        super.onChildrenClearedNative()
    }

    internal fun layoutWindowChromeAndChildren(widthPx: Int, heightPx: Int) {
        layoutWindowChromeAndChildren(widthPx.toFloat(), heightPx.toFloat())
    }

    internal fun layoutWindowChromeAndChildren(widthPx: Float, heightPx: Float) {
        val w = widthPx.coerceAtLeast(1f)
        val h = heightPx.coerceAtLeast(1f)
        val fsElem = fullscreenElementImpl as? DesktopBaseNativeImpl<*>
        if (fullscreenFlag && fsElem != null) {
            customTitleBarWidget?.syncAndLayout(0)
            (attachedMenuBar as? DesktopBaseNativeImpl<*>)?.setBounds(Rect(0f, 0f, 0f, 0f))
            (attachedNavBar as? DesktopBaseNativeImpl<*>)?.setBounds(Rect(0f, 0f, 0f, 0f))
            (attachedBottomNav as? DesktopBaseNativeImpl<*>)?.setBounds(Rect(0f, 0f, 0f, 0f))
            fsElem.setBounds(Rect(0f, 0f, w, h))
            (fsElem.nativeWidget as? KdeWidgetHandle)?.qWidgetPtr?.let { ptr ->
                KdeDynamicBindings.raiseWidget(ptr)
            }
            return
        }

        val titleBarH = customTitleBarWidget?.syncAndLayout(w.roundToInt())?.toFloat() ?: 0f

        var topY = titleBarH
        val embedMenu = nativeWidget.attributes["embedMenuBarInTitleBar"] == true
        val mb = attachedMenuBar as? DesktopBaseNativeImpl<*>
        if (mb != null && !embedMenu) {
            val mbH = 28f
            mb.setBounds(Rect(0f, topY, w, mbH))
            topY += mbH
        } else if (mb != null) {
            mb.setBounds(Rect(0f, 0f, 0f, 0f))
        }

        val nb = attachedNavBar as? DesktopBaseNativeImpl<*>
        if (nb != null) {
            val nbH = nb.measureIntrinsicSize(w, h - topY).height.toFloat().coerceAtLeast(48f)
            nb.setBounds(Rect(0f, topY, w, nbH))
            topY += nbH
        }

        var bottomH = 0f
        val bnb = attachedBottomNav as? DesktopBaseNativeImpl<*>
        if (bnb != null) {
            bottomH = bnb.measureIntrinsicSize(w, h - topY).height.toFloat().coerceAtLeast(52f)
            bnb.setBounds(Rect(0f, (h - bottomH).coerceAtLeast(topY), w, bottomH))
        }

        val contentH = (h - topY - bottomH).coerceAtLeast(0f)
        for (child in childImpls) {
            if (child is DesktopBaseNativeImpl<*>) {
                child.setBounds(Rect(0f, topY, w, contentH))
                if (child is DesktopBaseContainerNativeImpl<*>) {
                    child.layoutChildrenInBounds(w, contentH)
                }
            }
        }
    }

    internal fun syncTitleBarDerivedAttributes() {
        var flags = if (isToolModalWindow) KdeDynamicBindings.WINDOW_TOOL else KdeDynamicBindings.WINDOW_WINDOW
        if (isUndecorated()) flags = flags or KdeDynamicBindings.WINDOW_FRAMELESS_HINT
        if (alwaysOnTopFlag || alwaysOnTopOfOwner) flags = flags or KdeDynamicBindings.WINDOW_STAYS_ON_TOP_HINT
        nativeWidget.kdeWindowFlags = flags
        nativeWidget.attributes["kdeWindowFlags"] = flags
        nativeWidget.attributes["alwaysOnTop"] = alwaysOnTopFlag

        val profile = LinuxTitleBarProfileManager.currentProfile()
        val caps = getTitleCapabilities()
        val titleBar = activeTitleBar
        val customActive = !isUndecorated() && !isFullscreen() &&
            profile.isReplicable && caps.supportsCustomTitleBar &&
            (titleBar?.customTitleBar == true)
        kdeCustomTitleBarActive = customActive
        nativeWidget.attributes["kdeCustomTitleBarActive"] = customActive
        nativeWidget.attributes["kdeTitleBarProfile"] = profile.themeName
        nativeWidget.attributes["kdeHeaderBar"] = customActive
        nativeWidget.attributes["decorationLayout"] = titleBar?.controlsPlacement?.name ?: caps.defaultControlsPlacement.name
        val allowMinimize = if (isToolModalWindow) minimizable else (titleBar?.showMinimizeButton ?: true)
        nativeWidget.attributes["kdeButtonsLayout"] = if (allowMinimize) ":minimize,maximize,close" else ":close"
        nativeWidget.attributes["embedMenuBarInTitleBar"] =
            (attachedMenuBar != null) && customActive && caps.supportsEmbeddedMenuBar && (titleBar?.embedMenuBar == true)
    }

    override fun setTitle(title: String) {
        super.setTitle(title)
        if (KdeApplicationNativeImpl.interactiveKdeGuiEnabled) {
            KdeNativeWindowHost.syncWindowTitle(this, title)
        } else {
            nativeWidget.qWidgetPtr?.let { KdeDynamicBindings.setWindowTitle(it, title) }
        }
    }

    override fun setAlwaysOnTop(alwaysOnTop: Boolean) {
        super.setAlwaysOnTop(alwaysOnTop)
        syncTitleBarDerivedAttributes()
        if (KdeApplicationNativeImpl.interactiveKdeGuiEnabled && !suppressHostSync) {
            KdeNativeWindowHost.syncWindowAlwaysOnTop(this)
        }
    }

    override fun setAspectRatio(aspectRatio: Double?) {
        super.setAspectRatio(aspectRatio)
        if (KdeApplicationNativeImpl.interactiveKdeGuiEnabled && !suppressHostSync) {
            KdeNativeWindowHost.syncWindowAspectRatio(this)
        }
    }

    override fun setMinWindowSize(minSize: Size?) {
        super.setMinWindowSize(minSize)
        if (KdeApplicationNativeImpl.interactiveKdeGuiEnabled && !suppressHostSync) {
            KdeNativeWindowHost.syncWindowProperties(this)
        }
    }

    override fun setWindowSize(size: Size) {
        super.setWindowSize(size)
        layoutWindowChromeAndChildren(currentWindowSize.width, currentWindowSize.height)
        if (KdeApplicationNativeImpl.interactiveKdeGuiEnabled && !suppressHostSync) {
            KdeNativeWindowHost.syncWindowSize(this)
        }
    }

    override fun setWindowPosition(x: Int, y: Int) {
        super.setWindowPosition(x, y)
        if (KdeApplicationNativeImpl.interactiveKdeGuiEnabled && !suppressHostSync) {
            KdeNativeWindowHost.syncWindowPosition(this)
        }
    }

    internal fun notifyHostPositionChanged(x: Int, y: Int) {
        suppressHostSync = true
        try {
            updatePositionFromHost(x, y)
        } finally {
            suppressHostSync = false
        }
    }

    override fun setWindowState(state: WindowState) {
        super.setWindowState(state)
        if (KdeApplicationNativeImpl.interactiveKdeGuiEnabled && !suppressHostSync) {
            KdeNativeWindowHost.syncWindowState(this)
        }
    }

    override fun isUndecorated(): Boolean = super.isUndecorated()

    override fun setUndecorated(undecorated: Boolean) {
        val changed = super.isUndecorated() != undecorated
        super.setUndecorated(undecorated)
        if (changed) {
            syncTitleBarDerivedAttributes()
            layoutWindowChromeAndChildren(currentWindowSize.width, currentWindowSize.height)
        }
        if (changed && KdeApplicationNativeImpl.interactiveKdeGuiEnabled && !suppressHostSync) {
            KdeNativeWindowHost.syncWindowProperties(this)
        }
    }

    override fun startSystemMove() {
        if (KdeApplicationNativeImpl.interactiveKdeGuiEnabled && !isDisposed) {
            KdeNativeWindowHost.startSystemMove(this)
        }
    }

    override fun setFullscreen(fullscreen: Boolean, cutoutMode: DisplayCutoutMode) {
        super.setFullscreen(fullscreen, cutoutMode)
        syncTitleBarDerivedAttributes()
        layoutWindowChromeAndChildren(currentWindowSize.width, currentWindowSize.height)
        if (KdeApplicationNativeImpl.interactiveKdeGuiEnabled && !suppressHostSync) {
            KdeNativeWindowHost.syncFullscreen(this, fullscreen)
        }
    }

    override fun showWindow() {
        super.showWindow()
        if (isVisible() && !nativeWidget.attributes.containsKey("focused")) {
            setFocusedState(true)
        }
        if (!activeWindows.contains(this)) {
            activeWindows.add(this)
        }
        layoutWindowChromeAndChildren(currentWindowSize.width, currentWindowSize.height)
        if (KdeApplicationNativeImpl.interactiveKdeGuiEnabled && isVisible()) {
            KdeNativeWindowHost.presentWindow(this)
        }
    }

    override fun setVisible(visible: Boolean) {
        super.setVisible(visible)
        if (KdeApplicationNativeImpl.interactiveKdeGuiEnabled) {
            KdeNativeWindowHost.syncWindowVisibility(this, visible)
        }
    }

    override fun bringToFront() {
        super.bringToFront()
        if (KdeApplicationNativeImpl.interactiveKdeGuiEnabled && !suppressHostSync) {
            KdeNativeWindowHost.bringWindowToFront(this)
        }
    }

    fun addMediaKeyHook(hook: (KeyEvent) -> Boolean) {
        if (hook !in mediaKeyHooks) {
            mediaKeyHooks.add(hook)
        }
    }

    fun removeMediaKeyHook(hook: (KeyEvent) -> Boolean) {
        mediaKeyHooks.remove(hook)
    }

    internal fun dispatchWindowKeyDown(event: KeyEvent): Boolean {
        if (nativeWidget.enabled && !event.isRepeat) {
            for (hook in mediaKeyHooks.toList()) {
                if (hook(event)) return true
            }
            for (hook in globalMediaKeyHooks.toList()) {
                if (hook(this, event)) return true
            }
        }
        super.dispatchKeyDown(event)
        return false
    }

    override fun dispatchKeyDown(event: KeyEvent) {
        dispatchWindowKeyDown(event)
    }

    override fun closeWindow() {
        val wasDisposed = isDisposed
        super.closeWindow()
        if (isDisposed) {
            mediaKeyHooks.clear()
            activeWindows.remove(this)
            if (!wasDisposed && KdeApplicationNativeImpl.interactiveKdeGuiEnabled) {
                KdeNativeWindowHost.onWindowClosed(this)
            }
        }
    }

    private fun markSubtreeDestroyed(widget: DesktopNativeWidget) {
        (widget as? KdeWidgetHandle)?.kdeDestroyed = true
        for (child in widget.children) {
            markSubtreeDestroyed(child)
        }
    }

    override fun dispose() {
        val alreadyDisposed = isDisposed
        mediaKeyHooks.clear()
        markSubtreeDestroyed(nativeWidget)
        super.dispose()
        activeWindows.remove(this)
        if (!alreadyDisposed && KdeApplicationNativeImpl.interactiveKdeGuiEnabled) {
            KdeNativeWindowHost.onWindowClosed(this)
        }
        customTitleBarWidget?.dispose()
        customTitleBarWidget = null
        nativeWidget.destroyQWidget()
    }

    internal fun syncHostClientSize(
        widthPx: Int,
        heightPx: Int,
        fullscreenNow: Boolean = fullscreenFlag,
        maximizedNow: Boolean = currentWindowState == WindowState.MAXIMIZED
    ) {
        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        val requestedSize = Size(w, h)
        val prevState = currentWindowState
        val prevFs = fullscreenFlag
        val nextState = when {
            fullscreenNow -> WindowState.FULLSCREEN
            maximizedNow -> WindowState.MAXIMIZED
            currentWindowState == WindowState.FULLSCREEN || currentWindowState == WindowState.MAXIMIZED -> WindowState.NORMAL
            else -> currentWindowState
        }
        val ratio = aspectRatioValue
        val minSize = getMinWindowSize()
        val isWindowedNormal = !fullscreenNow && nextState == WindowState.NORMAL
        val resolvedSize = if (
            isWindowedNormal &&
            ratio != null &&
            !WindowAspectRatioHelper.matchesAspectRatio(requestedSize, ratio, tolerancePx = 2)
        ) {
            WindowAspectRatioHelper.enforceResizeDelta(
                previousSize = currentWindowSize,
                requestedSize = requestedSize,
                aspectRatio = ratio,
                minSize = minSize
            )
        } else if (isWindowedNormal && minSize != null) {
            Size(
                width = requestedSize.width.coerceAtLeast(minSize.width),
                height = requestedSize.height.coerceAtLeast(minSize.height)
            )
        } else {
            requestedSize
        }
        val needsHostClamp = resolvedSize != requestedSize
        val changed = currentWindowSize != resolvedSize ||
            prevState != nextState ||
            prevFs != fullscreenNow ||
            nativeWidget.bounds.width != resolvedSize.width ||
            nativeWidget.bounds.height != resolvedSize.height

        suppressHostSync = true
        try {
            fullscreenFlag = fullscreenNow
            currentWindowState = nextState
            nativeWidget.attributes["fullscreen"] = fullscreenNow
            nativeWidget.attributes["windowState"] = currentWindowState
            currentWindowSize = resolvedSize
            nativeWidget.bounds = Rect(0, 0, resolvedSize.width, resolvedSize.height)
            if (prevFs != fullscreenNow) {
                syncTitleBarDerivedAttributes()
            }
        } finally {
            suppressHostSync = false
        }

        if (KdeApplicationNativeImpl.interactiveKdeGuiEnabled) {
            if (prevState != nextState || prevFs != fullscreenNow) {
                KdeNativeWindowHost.syncWindowAspectRatio(this)
            }
            if (needsHostClamp) {
                KdeNativeWindowHost.requestAspectClampResize(this, requestedSize, resolvedSize)
            }
        }
        if (changed) {
            layoutWindowChromeAndChildren(resolvedSize.width, resolvedSize.height)
            if (prevState != currentWindowState) {
                onWindowStateChangedListener?.invoke(currentWindowState)
            }
            onWindowResizeListener?.invoke(
                WindowConfiguration(
                    widthPx = resolvedSize.width,
                    heightPx = resolvedSize.height,
                    windowState = currentWindowState,
                    isFullscreen = fullscreenFlag
                )
            )
        }
    }

    internal fun notifyHostMinimizedFromOs(
        minimized: Boolean,
        maximizedNow: Boolean,
        fullscreenNow: Boolean
    ) {
        val prevState = currentWindowState
        val nextState = when {
            minimized -> WindowState.MINIMIZED
            fullscreenNow -> WindowState.FULLSCREEN
            maximizedNow -> WindowState.MAXIMIZED
            else -> WindowState.NORMAL
        }
        suppressHostSync = true
        try {
            fullscreenFlag = fullscreenNow
            currentWindowState = nextState
            nativeWidget.attributes["fullscreen"] = fullscreenNow
            nativeWidget.attributes["windowState"] = currentWindowState
        } finally {
            suppressHostSync = false
        }
        if (prevState != currentWindowState) {
            onWindowStateChangedListener?.invoke(currentWindowState)
            onWindowResizeListener?.invoke(
                WindowConfiguration(
                    widthPx = currentWindowSize.width,
                    heightPx = currentWindowSize.height,
                    windowState = currentWindowState,
                    isFullscreen = fullscreenFlag
                )
            )
        }
    }

    internal fun notifyHostFocusChanged(focused: Boolean) {
        if (isFocusedState() == focused && nativeWidget.attributes.containsKey("focused")) return
        suppressHostSync = true
        try {
            dispatchFocusChanged(focused)
        } finally {
            suppressHostSync = false
        }
    }

    override fun runNativeTransition(
        property: String,
        fromValue: String?,
        toValue: String,
        spec: CssTransitionSpec,
        onComplete: () -> Unit
    ): Boolean {
        val handled = super.runNativeTransition(property, fromValue, toValue, spec, onComplete)
        if (property.equals("opacity", ignoreCase = true)) {
            val targetAlpha = (toValue.toFloatOrNull() ?: getOpacity()).coerceIn(0f, 1f)
            setOpacity(targetAlpha)
            if (spec.durationMs <= 0f) onComplete()
        }
        return handled
    }

    companion object {
        internal val activeWindows = mutableListOf<KdeWindowNativeImpl>()
        private val globalMediaKeyHooks = mutableListOf<(KdeWindowNativeImpl, KeyEvent) -> Boolean>()

        fun addGlobalMediaKeyHook(hook: (KdeWindowNativeImpl, KeyEvent) -> Boolean) {
            if (hook !in globalMediaKeyHooks) {
                globalMediaKeyHooks.add(hook)
            }
        }

        fun removeGlobalMediaKeyHook(hook: (KdeWindowNativeImpl, KeyEvent) -> Boolean) {
            globalMediaKeyHooks.remove(hook)
        }
    }
}
