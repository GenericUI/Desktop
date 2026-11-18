package com.nexustools.genericui.gtk

import com.nexustools.genericui.desktop.shell.DesktopWindowNativeImpl
import com.nexustools.genericui.enums.window.DisplayCutoutMode
import com.nexustools.genericui.enums.window.WindowState
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
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
        nativeWidget.attributes["gtkButtonsLayout"] = if (minimizable) ":minimize,maximize,close" else ":close"
        if (GtkApplicationNativeImpl.interactiveGtkGuiEnabled) {
            GtkNativeWindowHost.syncWindowProperties(this)
        }
    }

    override fun getTitleCapabilities(): TitleCapabilities = TitleCapabilities(
        isSupported = true,
        isEmulated = false,
        supportsCustomTitleBar = true,
        supportsCustomComponents = true,
        supportsLeadingComponents = true,
        supportsCenterComponents = true,
        supportsTrailingComponents = true,
        supportsEmbeddedMenuBar = true,
        supportsNavbarMenuBar = false,
        supportsWindowIcon = true,
        supportsWindowTitle = true,
        supportsMinimizeButton = true,
        supportsMaximizeButton = true,
        supportsCloseButton = true,
        supportsCollapseToggle = false,
        supportsControlsPlacement = true,
        supportsCustomHeight = true,
        supportsSystemAeroOrBackdrop = false,
        isToolWindowTitleBar = false,
        isTitleBarRequired = true,
        titleBarRendersAsNavbar = false,
        menuBarRendersAsNavbar = false,
        menuBarMergesIntoTitleNavbar = false
    )

    override fun applyTitleBarInstructions(titleBar: TitleBar?) {
        super.applyTitleBarInstructions(titleBar)
        nativeWidget.attributes["gtkHeaderBar"] = titleBar?.customTitleBar ?: true
        nativeWidget.attributes["decorationLayout"] = titleBar?.controlsPlacement?.name ?: "END"
        val allowMinimize = if (isToolModalWindow) minimizable else (titleBar?.showMinimizeButton ?: true)
        nativeWidget.attributes["gtkButtonsLayout"] = if (allowMinimize) ":minimize,maximize,close" else ":close"
        if (GtkApplicationNativeImpl.interactiveGtkGuiEnabled) {
            GtkNativeWindowHost.syncWindowProperties(this)
        }
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

    override fun setFullscreen(fullscreen: Boolean, cutoutMode: DisplayCutoutMode) {
        super.setFullscreen(fullscreen, cutoutMode)
        if (GtkApplicationNativeImpl.interactiveGtkGuiEnabled && !suppressHostSync) {
            GtkNativeWindowHost.syncFullscreen(this, fullscreen)
        }
    }

    override fun showWindow() {
        super.showWindow()
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

    override fun closeWindow() {
        super.closeWindow()
        activeWindows.remove(this)
        if (GtkApplicationNativeImpl.interactiveGtkGuiEnabled && isDisposed) {
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
                minSize = getMinWindowSize()
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
            onWindowResizeListener?.invoke(
                WindowConfiguration(
                    widthPx = resolvedSize.width,
                    heightPx = resolvedSize.height,
                    windowState = currentWindowState,
                    isFullscreen = fullscreenFlag,
                    isInPictureInPicture = inPipMode
                )
            )
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
    }
}
