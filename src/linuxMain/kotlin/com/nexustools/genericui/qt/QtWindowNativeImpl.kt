package com.nexustools.genericui.qt

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

/**
 * Qt 6 (`QWidget` + `QImage`/`QPainter`/`QPixmap`/`QLabel`) implementation of [WindowNativeImpl].
 */
class QtWindowNativeImpl : DesktopWindowNativeImpl<QtWidgetHandle>(
    nativeWidget = QtWidgetHandle("QMainWindow"),
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
        val baseFlags = QtWidgetHandle.Qt_Dialog or QtWidgetHandle.Qt_WindowCloseButtonHint or QtWidgetHandle.Qt_WindowTitleHint
        nativeWidget.qtWindowFlags = if (alwaysOnTopFlag || alwaysOnTopOfOwner) {
            baseFlags or QtWidgetHandle.Qt_WindowStaysOnTopHint
        } else {
            baseFlags
        }
        nativeWidget.attributes["qtWindowFlags"] = nativeWidget.qtWindowFlags
        nativeWidget.attributes["qtAttributeShowWithoutTaskbar"] = !showInTaskbar
        nativeWidget.attributes["minimizable"] = minimizable
        nativeWidget.attributes["qtWindowModality"] = "Qt::WindowModal"
        if (QtApplicationNativeImpl.interactiveQtGuiEnabled) {
            QtNativeWindowHost.syncWindowProperties(this)
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
        nativeWidget.attributes["framelessWindowHint"] = titleBar?.customTitleBar ?: false
        nativeWidget.attributes["controlsPlacement"] = titleBar?.controlsPlacement?.name ?: "END"
        val allowMinimize = if (isToolModalWindow) minimizable else (titleBar?.showMinimizeButton ?: true)
        nativeWidget.attributes["windowMinimizeButtonHint"] = allowMinimize
        if (QtApplicationNativeImpl.interactiveQtGuiEnabled) {
            QtNativeWindowHost.syncWindowProperties(this)
        }
    }

    override fun setTitle(title: String) {
        super.setTitle(title)
        if (QtApplicationNativeImpl.interactiveQtGuiEnabled) {
            QtNativeWindowHost.syncWindowTitle(this, title)
        }
    }

    override fun setAlwaysOnTop(alwaysOnTop: Boolean) {
        super.setAlwaysOnTop(alwaysOnTop)
        val effectiveTop = alwaysOnTop || (isToolModalWindow && alwaysOnTopOfOwner)
        if (effectiveTop) {
            nativeWidget.qtWindowFlags = nativeWidget.qtWindowFlags or QtWidgetHandle.Qt_WindowStaysOnTopHint
        } else {
            nativeWidget.qtWindowFlags = nativeWidget.qtWindowFlags and QtWidgetHandle.Qt_WindowStaysOnTopHint.inv()
        }
        nativeWidget.attributes["qtWindowFlags"] = nativeWidget.qtWindowFlags
        if (QtApplicationNativeImpl.interactiveQtGuiEnabled && !suppressHostSync) {
            QtNativeWindowHost.syncWindowAlwaysOnTop(this)
        }
    }

    override fun setAspectRatio(aspectRatio: Double?) {
        super.setAspectRatio(aspectRatio)
        if (QtApplicationNativeImpl.interactiveQtGuiEnabled && !suppressHostSync) {
            QtNativeWindowHost.syncWindowAspectRatio(this)
        }
    }

    override fun setMinWindowSize(minSize: Size?) {
        super.setMinWindowSize(minSize)
        if (QtApplicationNativeImpl.interactiveQtGuiEnabled && !suppressHostSync) {
            QtNativeWindowHost.syncWindowProperties(this)
        }
    }

    override fun setWindowSize(size: Size) {
        super.setWindowSize(size)
        if (QtApplicationNativeImpl.interactiveQtGuiEnabled && !suppressHostSync) {
            QtNativeWindowHost.syncWindowSize(this)
        }
    }

    override fun setWindowPosition(x: Int, y: Int) {
        super.setWindowPosition(x, y)
        if (QtApplicationNativeImpl.interactiveQtGuiEnabled && !suppressHostSync) {
            QtNativeWindowHost.syncWindowPosition(this)
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
        if (QtApplicationNativeImpl.interactiveQtGuiEnabled && !suppressHostSync) {
            QtNativeWindowHost.syncWindowState(this)
        }
    }

    override fun isUndecorated(): Boolean = super.isUndecorated()

    override fun setUndecorated(undecorated: Boolean) {
        val changed = super.isUndecorated() != undecorated
        super.setUndecorated(undecorated)
        if (undecorated) {
            nativeWidget.qtWindowFlags = nativeWidget.qtWindowFlags or QtWidgetHandle.Qt_FramelessWindowHint
        } else {
            nativeWidget.qtWindowFlags = nativeWidget.qtWindowFlags and QtWidgetHandle.Qt_FramelessWindowHint.inv()
        }
        if (changed && QtApplicationNativeImpl.interactiveQtGuiEnabled && !suppressHostSync) {
            QtNativeWindowHost.syncWindowProperties(this)
        }
    }

    override fun setOnWindowStateChanged(listener: ((WindowState) -> Unit)?) {
        super.setOnWindowStateChanged(listener)
    }

    override fun startSystemMove() {
        if (QtApplicationNativeImpl.interactiveQtGuiEnabled && !isDisposed) {
            QtNativeWindowHost.startSystemMove(this)
        }
    }

    override fun setFullscreen(fullscreen: Boolean, cutoutMode: DisplayCutoutMode) {
        super.setFullscreen(fullscreen, cutoutMode)
        if (QtApplicationNativeImpl.interactiveQtGuiEnabled && !suppressHostSync) {
            QtNativeWindowHost.syncFullscreen(this, fullscreen)
        }
    }

    override fun showWindow() {
        super.showWindow()
        if (!activeWindows.contains(this)) {
            activeWindows.add(this)
        }
        if (QtApplicationNativeImpl.interactiveQtGuiEnabled && isVisible()) {
            QtNativeWindowHost.presentWindow(this)
        }
    }

    override fun setVisible(visible: Boolean) {
        super.setVisible(visible)
        if (QtApplicationNativeImpl.interactiveQtGuiEnabled) {
            QtNativeWindowHost.syncWindowVisibility(this, visible)
        }
    }

    override fun bringToFront() {
        super.bringToFront()
        if (QtApplicationNativeImpl.interactiveQtGuiEnabled && !suppressHostSync) {
            QtNativeWindowHost.bringWindowToFront(this)
        }
    }

    override fun closeWindow() {
        super.closeWindow()
        activeWindows.remove(this)
        if (QtApplicationNativeImpl.interactiveQtGuiEnabled && isDisposed) {
            QtNativeWindowHost.onWindowClosed(this)
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

        if (QtApplicationNativeImpl.interactiveQtGuiEnabled) {
            if (prevState != nextState || prevFs != fullscreenNow) {
                QtNativeWindowHost.syncWindowAspectRatio(this)
            }
            if (needsHostClamp) {
                QtNativeWindowHost.requestAspectClampResize(this, requestedSize, resolvedSize)
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
        internal val activeWindows = mutableListOf<QtWindowNativeImpl>()
    }
}
