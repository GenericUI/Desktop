package com.nexustools.genericui.desktop.core

/**
 * Generic pixel/hardware surface descriptor attached to a [DesktopNativeWidget] by surface-providing
 * extensions (`:extensions:media`, `:extensions:canvas2d`, `:extensions:canvas3d`) so desktop backends
 * can composite and scale live surfaces without referencing extension-specific types or tag names.
 */
class DesktopInteractiveSurfaceSpec(
    val surfaceWidth: () -> Int,
    val surfaceHeight: () -> Int,
    val swapCount: () -> Long,
    val acquireBgraPtr: (() -> Pair<Any?, Int>)? = null,
    val captureBgra: (() -> ByteArray)? = null,
    val captureRgba: (() -> ByteArray)? = null,
    val resizeSurface: ((Int, Int) -> Unit)? = null,
    val preferHardwareStretchBlit: Boolean = false,
    val suppressDefaultBorder: Boolean = false,
    val defaultAspectRatio: Float? = null,
    val isActiveStream: () -> Boolean = { false },
    val activeCaptionText: () -> String? = { null },
    val onBeforePresentFrame: ((frameSwapCount: Long, timestampMs: Double?) -> Unit)? = null
) {
    private val beforePresentHooks = mutableListOf<(Long, Double?) -> Unit>()
    private var lastDispatchedBeforePresentSwap: Long = -1L

    fun registerBeforePresentHook(hook: (frameSwapCount: Long, timestampMs: Double?) -> Unit) {
        if (hook !in beforePresentHooks) {
            beforePresentHooks.add(hook)
        }
    }

    fun unregisterBeforePresentHook(hook: (frameSwapCount: Long, timestampMs: Double?) -> Unit) {
        beforePresentHooks.remove(hook)
    }

    fun dispatchBeforePresentFrame(frameSwapCount: Long, timestampMs: Double? = null) {
        if (frameSwapCount < 0L || frameSwapCount == lastDispatchedBeforePresentSwap) return
        lastDispatchedBeforePresentSwap = frameSwapCount
        onBeforePresentFrame?.invoke(frameSwapCount, timestampMs)
        if (beforePresentHooks.isNotEmpty()) {
            for (hook in beforePresentHooks.toList()) {
                hook(frameSwapCount, timestampMs)
            }
        }
    }

    fun attachTo(widget: DesktopNativeWidget) {
        widget.interactiveSurfaceSpec = this
        widget.isIsolatedStageSubtreeRoot = true
        widget.attributes["surfaceWidth"] = surfaceWidth
        widget.attributes["surfaceHeight"] = surfaceHeight
        widget.attributes["swapCount"] = swapCount
        widget.attributes["preferHardwareStretchBlit"] = preferHardwareStretchBlit
        widget.attributes["suppressDefaultBorder"] = suppressDefaultBorder
        widget.attributes["onBeforePresentFrame"] = { sc: Long, ts: Double? -> dispatchBeforePresentFrame(sc, ts) }
        if (defaultAspectRatio != null) {
            widget.attributes["defaultAspectRatio"] = defaultAspectRatio
        }
        if (acquireBgraPtr != null) {
            widget.attributes["captureBgraPtr"] = acquireBgraPtr
        }
        if (captureBgra != null) {
            widget.attributes["captureBgra"] = captureBgra
        }
        if (captureRgba != null) {
            widget.attributes["captureRgba"] = captureRgba
        }
        if (resizeSurface != null) {
            widget.attributes["resizeSurface"] = resizeSurface
        }
    }
}
