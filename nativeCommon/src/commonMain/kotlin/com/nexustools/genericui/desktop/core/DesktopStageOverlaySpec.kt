package com.nexustools.genericui.desktop.core

/**
 * Generic stage-overlay layout & state contract attached to an overlay root [DesktopNativeWidget]
 * by `:extensions:overlay`, `:extensions:media`, `:extensions:canvas2d`, or consumer overlays.
 * Allows desktop backends to identify overlay zones (`top-bar`, `center-zone`, `bottom-bar`,
 * `controls-row`, `seek-container`, `hover-bubble`, `time-readout`, `expanding-slider-pill`,
 * `caption-banner`, `popover-panel`, etc.) without referencing extension or consumer-app class names.
 */
class DesktopStageOverlaySpec(
    val isTargetActive: ((DesktopNativeWidget) -> Boolean)? = null,
    val isOverlayVisible: ((DesktopNativeWidget) -> Boolean)? = null,
    val activeCaptionText: ((DesktopNativeWidget) -> String?)? = null,
    var onBeforeTargetFramePresent: ((frameSwapCount: Long, timestampMs: Double?) -> Unit)? = null
) {
    fun attachTo(widget: DesktopNativeWidget) {
        @Suppress("UNCHECKED_CAST")
        val existingHook = widget.attributes["onBeforeTargetFramePresent"] as? ((Long, Double?) -> Unit)
        if (onBeforeTargetFramePresent == null && existingHook != null) {
            onBeforeTargetFramePresent = existingHook
        }
        widget.stageOverlaySpec = this
        widget.isIsolatedStageSubtreeRoot = true
        widget.attributes["data-overlay-root"] = "true"
        widget.attributes["onBeforeTargetFramePresent"] = { sc: Long, ts: Double? ->
            onBeforeTargetFramePresent?.invoke(sc, ts)
        }
    }

    companion object {
        const val ATTR_OVERLAY_ROLE = "data-overlay-role"
        const val ROLE_CONTROLS_LAYER = "controls-layer"
        const val ROLE_TOP_BAR = "top-bar"
        const val ROLE_CENTER_ZONE = "center-zone"
        const val ROLE_BOTTOM_BAR = "bottom-bar"
        const val ROLE_CONTROLS_ROW = "controls-row"
        const val ROLE_CONTROLS_LEFT = "controls-left"
        const val ROLE_CONTROLS_RIGHT = "controls-right"
        const val ROLE_SEEK_CONTAINER = "seek-container"
        const val ROLE_HOVER_BUBBLE = "hover-bubble"
        const val ROLE_TIME_READOUT = "time-readout"
        const val ROLE_EXPANDING_SLIDER_PILL = "expanding-slider-pill"
        const val ROLE_CAPTION_BANNER = "caption-banner"
        const val ROLE_POPOVER_PANEL = "popover-panel"
        const val ROLE_COMPACT_POPOVER_MENU = "compact-popover-menu"
        const val ROLE_INLINE_SLIDER_POPOVER = "inline-slider-popover"
        const val ROLE_POPOVER_SCROLL_LIST = "popover-scroll-list"
        const val ROLE_POPOVER_OPTION = "popover-option"
        const val ROLE_OUTLINED_FEATURE_BADGE = "outlined-feature-badge"
        const val ROLE_PRIMARY_CENTER_ACTION = "primary-center-action"
    }
}
