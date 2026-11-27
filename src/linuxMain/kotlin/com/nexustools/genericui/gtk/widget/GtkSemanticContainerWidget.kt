@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.widget

import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.core.DesktopStageOverlaySpec
import com.nexustools.genericui.desktop.shell.DesktopWindowNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopContainerNativeImpl
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.linux.style.LinuxBackdropFilterController
import com.nexustools.genericui.linux.style.LinuxStyledSurfaceHelper
import com.nexustools.genericui.linux.titlebar.LinuxTitleBarProfileManager
import com.nexustools.genericui.style.gradient.Gradient.LinearGradient
import com.nexustools.genericui.style.gradient.Gradient.GradientStop
import kotlinx.cinterop.COpaquePointer

/**
 * Retained surface painter for semantic Bootstrap containers (`Card`, `Alert`, `Banner`, `Toast`,
 * `ListGroup`, `Jumbotron`, `Well`, `MdiWindow`) powered by [DesktopContainerNativeImpl.resolveSemanticSurfaceSpec],
 * as well as Linux overlay window titlebars ([DesktopStageOverlaySpec.ROLE_WINDOW_TITLE_BAR]).
 * Plain unstyled containers stay transparent.
 */
object GtkSemanticContainerWidget {

    fun paintIfSemanticOrInteractive(
        host: DesktopContainerNativeImpl<GtkWidgetHandle>,
        cr: COpaquePointer,
        width: Int,
        height: Int
    ) {
        if (width <= 0 || height <= 0) return
        if (host.nativeWidget.interactiveSurfaceSpec != null) {
            if (GtkInteractiveSurfaceWidget.paintInteractiveSurface(host.nativeWidget, cr, width, height)) {
                return
            }
        }
        val role = (host.nativeWidget.attributes[DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE] as? String)
            ?.trim()
            ?.lowercase()
        if (role == DesktopStageOverlaySpec.ROLE_WINDOW_TITLE_BAR) {
            paintOverlayWindowTitleBar(host, cr, width, height)
            return
        }

        val dark = GtkRetainedContainerSupport.resolveDarkTheme()
        val semanticSpec = host.resolveSemanticSurfaceSpec(dark) ?: return
        val paintSpec = GtkSurfaceRenderHelper.fromSemanticSpec(
            spec = semanticSpec,
            opacity = host.getOpacity(),
            darkTheme = dark
        )
        GtkSurfaceRenderHelper.paintSurface(
            cr = cr,
            width = width,
            height = height,
            spec = paintSpec,
            behindBgraCapture = null
        )
    }

    private fun paintOverlayWindowTitleBar(
        host: DesktopContainerNativeImpl<GtkWidgetHandle>,
        cr: COpaquePointer,
        width: Int,
        height: Int
    ) {
        val profile = LinuxTitleBarProfileManager.currentProfile()
        if (!profile.isReplicable) return
        var ownerWin: DesktopWindowNativeImpl<*>? = null
        var cur: DesktopNativeWidget? = host.nativeWidget.parentWidget
        while (cur != null) {
            val impl = cur.attributes["nativeImpl"]
            if (impl is DesktopWindowNativeImpl<*>) {
                ownerWin = impl
                break
            }
            cur = cur.parentWidget
        }
        if (ownerWin?.isFullscreen() == true) return

        val appearance = LinuxStyledSurfaceHelper.resolveOverlayTitleBarAppearance(
            windowTitleBar = host.nativeWidget,
            titleLabelWidget = null,
            effectiveProfile = profile,
            contextIsDark = GtkRetainedContainerSupport.resolveDarkTheme(),
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
        val hasBackdrop = appearance.backdropFilter?.let {
            LinuxBackdropFilterController.shared.hasActiveEffects(it)
        } == true
        val behindCapture: ((Int, Int, Int, Int) -> GtkSurfaceRenderHelper.PaddedBgraCapture?)? = if (hasBackdrop) {
            { padL, padT, padR, padB ->
                GtkWidgetLifecycleBridge.captureBehindWidgetPremulBgra(host, width, height, padL, padT, padR, padB)
            }
        } else {
            null
        }
        GtkSurfaceRenderHelper.paintSurface(
            cr = cr,
            width = width,
            height = height,
            spec = GtkSurfacePaintSpec(
                backgroundGradient = gradient,
                borderColor = palette.activeBorderBottom,
                borderWidthPx = 1f,
                fallbackRadiusPx = appearance.topCornerRadiusPx,
                backdropFilter = appearance.backdropFilter,
                opacity = appearance.opacity,
                darkTheme = appearance.isDark
            ),
            behindBgraCapture = behindCapture
        )
    }
}
