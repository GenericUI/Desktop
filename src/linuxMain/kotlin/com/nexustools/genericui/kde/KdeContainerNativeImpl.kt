@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.core.DesktopStageOverlaySpec
import com.nexustools.genericui.desktop.layout.DesktopContainerLayoutController
import com.nexustools.genericui.desktop.shell.DesktopWindowNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopContainerNativeImpl
import com.nexustools.genericui.enums.bootstrap.FlexDirection
import com.nexustools.genericui.enums.css.CssBorderStyle
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.enums.css.CssDisplay
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.enums.window.WindowState
import com.nexustools.genericui.event.PopupMouseCaptureManager
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.linux.style.LinuxStyledSurfaceHelper
import com.nexustools.genericui.linux.titlebar.LinuxOverlayTitleBarResolver
import com.nexustools.genericui.linux.titlebar.LinuxTitleBarProfileManager
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.style.css.StyledVisualSpec
import com.nexustools.genericui.style.gradient.Gradient.GradientStop
import com.nexustools.genericui.style.gradient.Gradient.LinearGradient
import com.nexustools.genericui.style.theme.SystemThemeDetector
import kotlinx.cinterop.COpaquePointer
import kotlin.math.roundToInt

/**
 * Retained KDE container implementation keeping plain containers as transparent child `QWidget`s
 * managed by [DesktopContainerLayoutController], while activating semantic container painting
 * (`KdeSemanticContainerWidget`) or zero-copy interactive surface drawing (`KdeInteractiveSurfaceWidget`)
 * when semantic surface styles or [DesktopInteractiveSurfaceSpec] apply.
 */
open class KdeContainerNativeImpl(
    nativeWidget: KdeWidgetHandle = KdeWidgetHandle("QWidget_Container"),
    classPrefix: String = "container",
    tagName: String = "div"
) : DesktopContainerNativeImpl<KdeWidgetHandle>(nativeWidget, classPrefix, tagName) {

    private var customSurfaceHooked = false

    init {
        KdeDynamicBindings.ensureQApplication()
        val isLayered = isLayeredHostTagOrClass(classPrefix, tagName, nativeWidget.cachedAllClassTokens)
        val ptr = KdeDynamicBindings.createQWidget(null, 0)
        val initRole = if (isLayered) "QWidget_LayeredContainer" else "QWidget_Container"
        nativeWidget.attachToQWidget(ptr, initRole, customPainted = false)

        layoutController = DesktopContainerLayoutController(
            host = this,
            defaultDisplay = if (isLayered) CssDisplay.BLOCK else null,
            defaultFlexDirection = if (isLayered) null else FlexDirection.COLUMN,
            isLayeredHost = isLayered,
            applyScaleToBounds = false
        )
        syncSurfaceModeAndHooks()
    }

    private fun isLayeredHostTagOrClass(classPrefix: String, tagName: String, classes: Set<String>): Boolean =
        tagName.equals("genericui:layered", ignoreCase = true) ||
            tagName.equals("genericui:layered-container", ignoreCase = true) ||
            classPrefix.contains("layered", ignoreCase = true) ||
            classes.any {
                it.equals("layered", ignoreCase = true) ||
                    it.equals("layered-container", ignoreCase = true) ||
                    it.equals("gui-layered-container", ignoreCase = true)
            }

    private fun isOverlayWindowTitleBarRole(): Boolean =
        (nativeWidget.attributes[DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE] as? String)
            ?.trim()
            ?.lowercase() == DesktopStageOverlaySpec.ROLE_WINDOW_TITLE_BAR

    private fun findAncestorWindowImpl(): DesktopWindowNativeImpl<*>? {
        var cur: DesktopNativeWidget? = nativeWidget.parentWidget
        while (cur != null) {
            val impl = cur.attributes["nativeImpl"]
            if (impl is DesktopWindowNativeImpl<*>) return impl
            cur = cur.parentWidget
        }
        return null
    }

    private fun shouldCollapseOverlayTitleBar(): Boolean {
        if (!isOverlayWindowTitleBarRole()) return false
        if (nativeWidget.attributes["data-titlebar-active"]?.toString()?.equals("false", ignoreCase = true) == true) {
            return true
        }
        val profile = LinuxTitleBarProfileManager.currentProfile()
        if (!profile.isReplicable) return true
        return findAncestorWindowImpl()?.isFullscreen() == true
    }

    private fun toggleMaximizeFromTitleBar(winImpl: DesktopWindowNativeImpl<*>) {
        if (winImpl.isToolModalWindow) return
        if (winImpl.nativeWidget.attributes["maximizable"]?.toString() == "false") return
        val next = if (winImpl.getWindowState() == WindowState.MAXIMIZED) {
            WindowState.NORMAL
        } else {
            WindowState.MAXIMIZED
        }
        winImpl.setWindowState(next)
    }

    private fun syncSurfaceModeAndHooks() {
        if (isDisposed || nativeWidget.kdeDestroyed) return
        val ptr = nativeWidget.qWidgetPtr?.takeIf { KdeVTableBridge.isPeerAlive(it) } ?: return
        val dark = SystemThemeDetector.resolveTheme(ThemeMode.AUTO) == ThemeMode.DARK
        val semanticSpec = resolveSemanticSurfaceSpec(dark)
        val isOverlayTitleBar = isOverlayWindowTitleBarRole()
        val hasSurface = isOverlayTitleBar || (semanticSpec != null) || (nativeWidget.interactiveSurfaceSpec != null)
        val hasListeners = onClickListener != null || onPointerDownListener != null || onPointerMoveListener != null ||
            onPointerUpListener != null || onWheelListener != null || onKeyDownListener != null

        val targetType = when {
            nativeWidget.interactiveSurfaceSpec != null -> "KdeInteractiveSurfaceWidget"
            isOverlayTitleBar || semanticSpec != null -> "KdeSemanticContainerWidget"
            else -> if (layoutController != null && nativeWidget.activeKdeWidgetType == "QWidget_LayeredContainer") {
                "QWidget_LayeredContainer"
            } else {
                "QWidget_Container"
            }
        }
        nativeWidget.activeKdeWidgetType = targetType
        nativeWidget.usesCustomPainting = hasSurface

        if (hasSurface || hasListeners || !customSurfaceHooked) {
            customSurfaceHooked = true
            val mask = if (hasSurface) KdeVTableBridge.HOOK_PAINT or KdeVTableBridge.HOOK_RESIZE else KdeVTableBridge.HOOK_RESIZE
            KdePainterSupport.attachStandardInputHooks(
                widgetPtr = ptr,
                impl = this,
                hookMask = mask,
                dispatchLeftClickOnRelease = true,
                onPaint = if (hasSurface) { wPtr, painter, w, h -> paintContainerSurface(wPtr, painter, w, h) } else null
            )
            if (isOverlayTitleBar) {
                KdeVTableBridge.attachWidgetHooks(ptr, KdeVTableBridge.HOOK_INPUT) {
                    val origPress = this.onMousePress
                    this.onMousePress = { wPtr, info ->
                        val pEv = KdePainterSupport.toPointerEvent(info)
                        if (PopupMouseCaptureManager.interceptPointerDown(pEv.screenX, pEv.screenY, nativeWidget)) {
                            true
                        } else if (!shouldCollapseOverlayTitleBar() &&
                            !LinuxOverlayTitleBarResolver.isPointOverTitleBarInteractive(nativeWidget, info.localX, info.localY)
                        ) {
                            val winImpl = findAncestorWindowImpl()
                            when (info.button) {
                                2 -> {
                                    if (onContextMenuListener != null) {
                                        dispatchContextMenu(pEv)
                                    } else if (winImpl is KdeWindowNativeImpl) {
                                        KdeNativeWindowHost.showWindowMenu(winImpl, info.globalX.roundToInt(), info.globalY.roundToInt())
                                    }
                                    true
                                }
                                1 -> {
                                    if (onPointerDownListener != null) {
                                        dispatchPointerDown(pEv)
                                    } else {
                                        winImpl?.startSystemMove()
                                    }
                                    true
                                }
                                else -> origPress?.invoke(wPtr, info) ?: false
                            }
                        } else {
                            origPress?.invoke(wPtr, info) ?: false
                        }
                    }
                    val origDblClick = this.onMouseDoubleClick
                    this.onMouseDoubleClick = { wPtr, info ->
                        if (info.button == 1 && !shouldCollapseOverlayTitleBar() &&
                            !LinuxOverlayTitleBarResolver.isPointOverTitleBarInteractive(nativeWidget, info.localX, info.localY)
                        ) {
                            val winImpl = findAncestorWindowImpl()
                            if (onDoubleClickListener != null) {
                                dispatchDoubleClick()
                            } else if (winImpl != null) {
                                toggleMaximizeFromTitleBar(winImpl)
                            }
                            true
                        } else {
                            origDblClick?.invoke(wPtr, info) ?: false
                        }
                    }
                }
            }
        }
    }

    private fun paintContainerSurface(widgetPtr: COpaquePointer, painter: COpaquePointer, widthPx: Int, heightPx: Int) {
        if (nativeWidget.interactiveSurfaceSpec != null) {
            if (KdeInteractiveSurfaceWidget.paintInteractiveSurface(nativeWidget, painter, widthPx, heightPx)) {
                return
            }
        }
        val dark = SystemThemeDetector.resolveTheme(ThemeMode.AUTO) == ThemeMode.DARK
        if (isOverlayWindowTitleBarRole()) {
            val profile = LinuxTitleBarProfileManager.currentProfile()
            val ownerWin = findAncestorWindowImpl()
            if (!profile.isReplicable || ownerWin?.isFullscreen() == true) return
            val appearance = LinuxStyledSurfaceHelper.resolveOverlayTitleBarAppearance(
                windowTitleBar = nativeWidget,
                titleLabelWidget = null,
                effectiveProfile = profile,
                contextIsDark = dark,
                isUndecoratedWindow = ownerWin?.isUndecorated() == true,
                isMaximized = false
            )
            val palette = appearance.profile.palette
            val gradient = LinearGradient(
                angleDegrees = 180f,
                stops = listOf(
                    GradientStop(0f, palette.activeBgTop),
                    GradientStop(1f, palette.activeBgBottom)
                )
            )
            val spec = StyledVisualSpec(
                borderColor = palette.activeBorderBottom,
                borderWidthPx = 1f,
                borderStyle = CssBorderStyle.SOLID,
                borderRadiusPx = appearance.topCornerRadiusPx
            )
            KdePainterSupport.paintStyledSurface(
                widgetPtr = widgetPtr,
                painterPtr = painter,
                widthPx = widthPx,
                heightPx = heightPx,
                spec = spec,
                radii = null,
                gradientOverride = gradient,
                backdropFilterOverride = appearance.backdropFilter,
                opacity = appearance.opacity
            )
            return
        }
        val sem = resolveSemanticSurfaceSpec(dark) ?: return
        KdePainterSupport.paintSemanticSurface(painter, widthPx, heightPx, sem, getOpacity())
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        if (isOverlayWindowTitleBarRole()) {
            if (shouldCollapseOverlayTitleBar()) return Size(0, 0)
            val compactH = LinuxTitleBarProfileManager.currentProfile().metrics.compactHeightPx
            return Size((maxWidth?.toInt() ?: 480).coerceAtLeast(1), compactH)
        }
        return layoutController?.measurePreferredSize(maxWidth, maxHeight) ?: super.measureIntrinsicSize(maxWidth, maxHeight)
    }

    override fun setBounds(rect: Rect) {
        val resolved = if (shouldCollapseOverlayTitleBar()) {
            Rect(rect.x, rect.y, rect.width, 0)
        } else {
            rect
        }
        super.setBounds(resolved)
    }

    override fun layoutChildrenInBounds(width: Float, height: Float) {
        if (shouldCollapseOverlayTitleBar()) {
            for (child in getLogicalChildren()) {
                (child as? DesktopBaseNativeImpl<*>)?.setBounds(Rect(0, 0, 0, 0))
            }
            return
        }
        if (isOverlayWindowTitleBarRole()) {
            layoutOverlayTitleBarChildren(width.toInt().coerceAtLeast(1), height.toInt().coerceAtLeast(1))
            return
        }
        super.layoutChildrenInBounds(width, height)
        restackChildrenByZOrder()
    }

    private fun layoutOverlayTitleBarChildren(totalW: Int, totalH: Int) {
        val visibleChildren = getLogicalChildren().filterIsInstance<DesktopBaseNativeImpl<*>>().filter {
            it.nativeWidget.visible && !it.nativeWidget.disposed
        }
        LinuxOverlayTitleBarResolver.layoutOverlayTitleBarChildren(
            visibleChildren = visibleChildren,
            windowTitleBar = nativeWidget,
            totalW = totalW,
            totalH = totalH
        )
    }

    override fun setAttribute(name: String, value: String?) {
        super.setAttribute(name, value)
        if (!isDisposed && !nativeWidget.kdeDestroyed) {
            syncSurfaceModeAndHooks()
        }
    }

    protected fun restackChildrenByZOrder() {
        KdePainterSupport.restackChildrenByZOrder(childImpls)
    }

    override fun onChildAddedNative(childImpl: NativeImpl<*>, index: Int) {
        super.onChildAddedNative(childImpl, index)
        (childImpl.nativeWidget as? KdeWidgetHandle)?.syncChildParentAndVisibility(nativeWidget)
        val b = nativeWidget.bounds
        if (b.width > 0 && b.height > 0) {
            layoutChildrenInBounds(b.width.toFloat(), b.height.toFloat())
        } else {
            restackChildrenByZOrder()
        }
    }

    override fun onChildRemovedNative(childImpl: NativeImpl<*>) {
        (childImpl.nativeWidget as? KdeWidgetHandle)?.syncChildParentAndVisibility(null)
        super.onChildRemovedNative(childImpl)
    }

    override fun onChildrenClearedNative() {
        for (c in childImpls) {
            (c.nativeWidget as? KdeWidgetHandle)?.syncChildParentAndVisibility(null)
        }
        super.onChildrenClearedNative()
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        nativeWidget.applyBoundsToNative(rect)
        layoutChildrenInBounds(rect.width.toFloat(), rect.height.toFloat())
    }

    override fun onNativeVisibilityChanged(visible: Boolean) {
        nativeWidget.syncVisibilityToNative(visible)
    }

    override fun onNativeEnabledChanged(enabled: Boolean) {
        nativeWidget.qWidgetPtr?.let { KdeDynamicBindings.setWidgetEnabled(it, enabled) }
    }

    override fun onNativeOpacityChanged(opacity: Float) {
        nativeWidget.requestRepaint()
    }

    override fun onNativeTooltipChanged(tooltip: String?) {
        KdePainterSupport.applyCommonWidgetProperties(
            nativeWidget, customCursorState, nativeWidget.isSelfPointerEventsNone(), tooltip, getOpacity()
        )
    }

    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        KdePainterSupport.applyCommonWidgetProperties(
            nativeWidget, cursor, nativeWidget.isSelfPointerEventsNone(), nativeWidget.tooltipText, getOpacity()
        )
    }

    override fun onNativePointerEventsChanged(pointerEventsNone: Boolean) {
        KdePainterSupport.applyCommonWidgetProperties(
            nativeWidget, customCursorState, pointerEventsNone, nativeWidget.tooltipText, getOpacity()
        )
    }

    override fun onNativeZIndexChanged(zIndex: Int) {
        KdePainterSupport.restackParentByZOrder(nativeWidget)
    }

    override fun onNativeTransformChanged(transform: CssTransform?) {
        nativeWidget.requestRepaint()
    }

    override fun onNativeStyleSynced() {
        syncSurfaceModeAndHooks()
        nativeWidget.requestRepaint()
    }

    override fun requestRepaintNative() {
        nativeWidget.requestRepaint()
    }

    override fun dispose() {
        nativeWidget.destroyQWidget()
        super.dispose()
    }
}
