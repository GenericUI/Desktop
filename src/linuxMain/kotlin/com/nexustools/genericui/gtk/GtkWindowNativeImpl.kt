package com.nexustools.genericui.gtk

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.layout.DesktopContainerLayoutController
import com.nexustools.genericui.desktop.shell.DesktopWindowNativeImpl
import com.nexustools.genericui.enums.bootstrap.FlexDirection
import com.nexustools.genericui.enums.css.CssDisplay
import com.nexustools.genericui.enums.window.DisplayCutoutMode
import com.nexustools.genericui.enums.window.WindowState
import com.nexustools.genericui.event.KeyEvent
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.gtk.widget.GtkRetainedContainerSupport
import com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge
import com.nexustools.genericui.linux.titlebar.LinuxTitleBarProfileManager
import com.nexustools.genericui.nativeimpl.shell.MenuBarNativeImpl
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl
import com.nexustools.genericui.style.transition.CssTransitionSpec
import com.nexustools.genericui.window.TitleBar
import com.nexustools.genericui.window.TitleCapabilities
import com.nexustools.genericui.window.WindowAspectRatioHelper
import com.nexustools.genericui.window.WindowConfiguration

class GtkWindowNativeImpl : DesktopWindowNativeImpl<GtkWidgetHandle>(
    nativeWidget = GtkWidgetHandle("AdwApplicationWindow"),
    supportsCustomTitleBar = true,
    supportsEmbeddedMenuBarInTitleBar = true,
    supportsMultiWindow = true
) {
    private var suppressHostSync = false
    private val mediaKeyHooks = mutableListOf<(KeyEvent) -> Boolean>()
    var gtkCustomTitleBarActive: Boolean = false
        private set

    init {
        nativeWidget.ownerImpl = this
        layoutController = DesktopContainerLayoutController(
            host = this,
            defaultDisplay = CssDisplay.FLEX,
            defaultFlexDirection = FlexDirection.COLUMN,
            applyScaleToBounds = false
        )
        nativeWidget.customLayoutController = layoutController
    }

    override fun layoutChildrenInBounds(width: Float, height: Float) {
        val contentChildren = childImpls.filterIsInstance<DesktopBaseNativeImpl<*>>().filter {
            it.isVisible() &&
                it.nativeWidget.visible &&
                !it.nativeWidget.disposed &&
                it.nativeWidget.attributes["mountedInTitleBar"] != true
        }
        if (contentChildren.size == 1) {
            val child = contentChildren[0]
            val cw = width.coerceAtLeast(1f)
            val ch = height.coerceAtLeast(1f)
            child.setBounds(Rect(0f, 0f, cw, ch))
            if (child is DesktopBaseContainerNativeImpl<*>) {
                child.layoutChildrenInBounds(cw, ch)
            }
            return
        }
        super.layoutChildrenInBounds(width, height)
    }

    override fun onChildAddedNative(childImpl: NativeImpl<*>, index: Int) {
        GtkRetainedContainerSupport.attachChildWidgetIfRealized(this, childImpl)
    }

    override fun onChildRemovedNative(childImpl: NativeImpl<*>) {
        GtkRetainedContainerSupport.detachChildWidget(this, childImpl)
    }

    override fun onChildrenClearedNative() {
        GtkRetainedContainerSupport.clearChildWidgets(this)
    }

    override fun onNativeStyleSynced() {
        GtkWidgetLifecycleBridge.onStyleSynced(this)
        if (GtkApplicationNativeImpl.interactiveGtkGuiEnabled) {
            GtkNativeWindowHost.requestRedraw()
        }
    }

    override val supportsCustomTitleBar: Boolean
        get() = getTitleCapabilities().supportsCustomTitleBar

    override val supportsEmbeddedMenuBarInTitleBar: Boolean
        get() = getTitleCapabilities().supportsEmbeddedMenuBar

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
        if (GtkApplicationNativeImpl.interactiveGtkGuiEnabled) {
            GtkNativeWindowHost.syncWindowProperties(this)
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
                (comp.nativeImpl?.nativeWidget as? DesktopNativeWidget)?.attributes?.remove("mountedInTitleBar")
            }
        }
        syncTitleBarDerivedAttributes()
        if (GtkApplicationNativeImpl.interactiveGtkGuiEnabled) {
            GtkNativeWindowHost.syncWindowProperties(this)
        }
    }

    override fun setMenuBarNative(menuBarImpl: MenuBarNativeImpl<*>?) {
        super.setMenuBarNative(menuBarImpl)
        syncTitleBarDerivedAttributes()
        if (GtkApplicationNativeImpl.interactiveGtkGuiEnabled) {
            GtkNativeWindowHost.syncWindowChromeBars(this)
            GtkNativeWindowHost.requestRedraw()
        }
    }

    override fun setNavBarNative(navBarImpl: NativeImpl<*>?) {
        super.setNavBarNative(navBarImpl)
        if (GtkApplicationNativeImpl.interactiveGtkGuiEnabled) {
            GtkNativeWindowHost.syncWindowChromeBars(this)
            GtkNativeWindowHost.requestRedraw()
        }
    }

    override fun setBottomNavigationBarNative(bottomNavImpl: NativeImpl<*>?) {
        super.setBottomNavigationBarNative(bottomNavImpl)
        if (GtkApplicationNativeImpl.interactiveGtkGuiEnabled) {
            GtkNativeWindowHost.syncWindowChromeBars(this)
            GtkNativeWindowHost.requestRedraw()
        }
    }

    internal fun syncTitleBarDerivedAttributes() {
        val profile = LinuxTitleBarProfileManager.currentProfile()
        val caps = getTitleCapabilities()
        val titleBar = activeTitleBar
        val customActive = !isUndecorated() && !isFullscreen() &&
            profile.isReplicable && caps.supportsCustomTitleBar &&
            (titleBar?.customTitleBar == true)
        gtkCustomTitleBarActive = customActive
        nativeWidget.attributes["gtkCustomTitleBarActive"] = customActive
        nativeWidget.attributes["gtkTitleBarProfile"] = profile.themeName
        nativeWidget.attributes["gtkHeaderBar"] = customActive
        nativeWidget.attributes["decorationLayout"] = titleBar?.controlsPlacement?.name ?: caps.defaultControlsPlacement.name
        val allowMinimize = if (isToolModalWindow) minimizable else (titleBar?.showMinimizeButton ?: true)
        nativeWidget.attributes["gtkButtonsLayout"] = if (allowMinimize) ":minimize,maximize,close" else ":close"
        nativeWidget.attributes["embedMenuBarInTitleBar"] =
            (attachedMenuBar != null) && customActive && caps.supportsEmbeddedMenuBar && (titleBar?.embedMenuBar == true)
    }

    override fun setTitle(title: String) {
        super.setTitle(title)
        if (GtkApplicationNativeImpl.interactiveGtkGuiEnabled) {
            GtkNativeWindowHost.syncWindowTitle(this, title)
        }
    }

    override fun setAlwaysOnTop(alwaysOnTop: Boolean) {
        super.setAlwaysOnTop(alwaysOnTop)
        if (GtkApplicationNativeImpl.interactiveGtkGuiEnabled && !suppressHostSync) {
            GtkNativeWindowHost.syncWindowAlwaysOnTop(this)
        }
    }

    override fun setAspectRatio(aspectRatio: Double?) {
        super.setAspectRatio(aspectRatio)
        if (GtkApplicationNativeImpl.interactiveGtkGuiEnabled && !suppressHostSync) {
            GtkNativeWindowHost.syncWindowAspectRatio(this)
        }
    }

    override fun setMinWindowSize(minSize: Size?) {
        super.setMinWindowSize(minSize)
        if (GtkApplicationNativeImpl.interactiveGtkGuiEnabled && !suppressHostSync) {
            GtkNativeWindowHost.syncWindowProperties(this)
        }
    }

    override fun setWindowSize(size: Size) {
        super.setWindowSize(size)
        if (GtkApplicationNativeImpl.interactiveGtkGuiEnabled && !suppressHostSync) {
            GtkNativeWindowHost.syncWindowSize(this)
        }
    }

    override fun setWindowPosition(x: Int, y: Int) {
        super.setWindowPosition(x, y)
        if (GtkApplicationNativeImpl.interactiveGtkGuiEnabled && !suppressHostSync) {
            GtkNativeWindowHost.syncWindowPosition(this)
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
        if (GtkApplicationNativeImpl.interactiveGtkGuiEnabled && !suppressHostSync) {
            GtkNativeWindowHost.syncWindowState(this)
        }
    }

    override fun isUndecorated(): Boolean = super.isUndecorated()

    override fun setUndecorated(undecorated: Boolean) {
        val changed = super.isUndecorated() != undecorated
        super.setUndecorated(undecorated)
        if (changed) {
            syncTitleBarDerivedAttributes()
        }
        if (changed && GtkApplicationNativeImpl.interactiveGtkGuiEnabled && !suppressHostSync) {
            GtkNativeWindowHost.syncWindowProperties(this)
        }
    }

    override fun setOnWindowStateChanged(listener: ((WindowState) -> Unit)?) {
        super.setOnWindowStateChanged(listener)
    }

    override fun startSystemMove() {
        if (GtkApplicationNativeImpl.interactiveGtkGuiEnabled && !isDisposed) {
            GtkNativeWindowHost.startSystemMove(this)
        }
    }

    override fun setFullscreen(fullscreen: Boolean, cutoutMode: DisplayCutoutMode) {
        super.setFullscreen(fullscreen, cutoutMode)
        syncTitleBarDerivedAttributes()
        if (GtkApplicationNativeImpl.interactiveGtkGuiEnabled && !suppressHostSync) {
            GtkNativeWindowHost.syncFullscreen(this, fullscreen)
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
        if (GtkApplicationNativeImpl.interactiveGtkGuiEnabled && isVisible()) {
            GtkNativeWindowHost.presentWindow(this)
        }
    }

    override fun setVisible(visible: Boolean) {
        super.setVisible(visible)
        if (GtkApplicationNativeImpl.interactiveGtkGuiEnabled) {
            GtkNativeWindowHost.syncWindowVisibility(this, visible)
        }
    }

    override fun bringToFront() {
        super.bringToFront()
        if (GtkApplicationNativeImpl.interactiveGtkGuiEnabled && !suppressHostSync) {
            GtkNativeWindowHost.bringWindowToFront(this)
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
            if (!wasDisposed && GtkApplicationNativeImpl.interactiveGtkGuiEnabled) {
                GtkNativeWindowHost.onWindowClosed(this)
            }
        }
    }

    override fun dispose() {
        val alreadyDisposed = isDisposed
        mediaKeyHooks.clear()
        super.dispose()
        activeWindows.remove(this)
        if (!alreadyDisposed && GtkApplicationNativeImpl.interactiveGtkGuiEnabled) {
            GtkNativeWindowHost.onWindowClosed(this)
        }
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

        if (GtkApplicationNativeImpl.interactiveGtkGuiEnabled) {
            if (prevState != nextState || prevFs != fullscreenNow) {
                GtkNativeWindowHost.syncWindowAspectRatio(this)
            }
            if (needsHostClamp) {
                GtkNativeWindowHost.requestAspectClampResize(this, requestedSize, resolvedSize)
            }
        }
        if (changed) {
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
            val fromAlpha = (fromValue?.toFloatOrNull() ?: getOpacity()).coerceIn(0f, 1f)
            val targetAlpha = (toValue.toFloatOrNull() ?: getOpacity()).coerceIn(0f, 1f)
            nativeWidget.attributes["transitionFromOpacity"] = fromAlpha
            nativeWidget.attributes["transitionToOpacity"] = targetAlpha
            setOpacity(targetAlpha)
            if (spec.durationMs <= 0f) {
                onComplete()
            }
        }
        return handled
    }

    companion object {
        internal val activeWindows = mutableListOf<GtkWindowNativeImpl>()
        private val globalMediaKeyHooks = mutableListOf<(GtkWindowNativeImpl, KeyEvent) -> Boolean>()

        fun addGlobalMediaKeyHook(hook: (GtkWindowNativeImpl, KeyEvent) -> Boolean) {
            if (hook !in globalMediaKeyHooks) {
                globalMediaKeyHooks.add(hook)
            }
        }

        fun removeGlobalMediaKeyHook(hook: (GtkWindowNativeImpl, KeyEvent) -> Boolean) {
            globalMediaKeyHooks.remove(hook)
        }
    }
}
