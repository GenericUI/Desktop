@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.dialog.DesktopDialogWindowNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.macos.render.MacosHitRegion
import com.nexustools.genericui.macos.render.MacosScrollRegion
import platform.AppKit.NSWindow

/**
 * Live per-window AppKit rendering, hit-testing, focus, and stage-HUD session managed by [MacosNativeWindowHost].
 */
internal class MacosWindowSession(
    val windowKey: Long,
    val nsWindow: NSWindow,
    val canvasView: MacosCanvasView,
    val delegate: MacosWindowDelegate,
    val windowImpl: MacosWindowNativeImpl? = null,
    val dialogImpl: DesktopDialogWindowNativeImpl<*>? = null,
    val ownerWindowKey: Long? = null
) {
    var hitRegions: List<MacosHitRegion> = emptyList()
    var scrollRegions: List<MacosScrollRegion> = emptyList()
    var renderedStageWidget: DesktopNativeWidget? = null
    var renderedStageBounds: Rect? = null
    var stageHitRegionStartIdx: Int = -1
    var stageHitRegionEndIdx: Int = -1
    var stageScrollRegionStartIdx: Int = -1
    var stageScrollRegionEndIdx: Int = -1
    var lastRenderedNonOverlayVersion: Long = -1L
    var pendingFullPaint: Boolean = true

    var hoveredRegion: MacosHitRegion? = null
    var hoveredWidget: DesktopNativeWidget? = null
    var pressedRegion: MacosHitRegion? = null
    var pressedWidget: DesktopNativeWidget? = null
    var focusedWidget: DesktopNativeWidget? = null
    var selectAllPendingReplace: Boolean = false

    var lastMouseX: Float = -1f
    var lastMouseY: Float = -1f
    var isPointerInside: Boolean = false

    var lastStageActivityTimeMs: Double = 0.0
    var lastStageActivitySeq: String = ""
    var overlayHudAlpha: Float = 1f
    var isStageCursorHidden: Boolean = false
    var lastAppliedCursorKey: String = ""
    var lastRenderedSurfaceSwapSum: Long = -1L
    var lastRenderedTreeVersion: Long = -1L
    var appliedTitleBarChromeMode: Int = -1
    var titleBarInsetTopPx: Int = 28
    var isApplyingChromeGeometryTransition: Boolean = false
    var isFullscreenTransitionInFlight: Boolean = false
    var fullscreenTransitionStartedMs: Double = 0.0
    var targetFullscreen: Boolean = false
    var pendingTargetFullscreen: Boolean? = null
    var programmaticMaximized: Boolean = false
    var lastKnownZoomed: Boolean = false
}
