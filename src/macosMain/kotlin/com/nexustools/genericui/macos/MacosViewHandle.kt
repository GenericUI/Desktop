@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.style.css.CssTransform
import platform.AppKit.NSView
import platform.AppKit.NSWindow

/**
 * macOS AppKit widget handle carrying the retained [NSView] and/or [NSWindow] along with window
 * style-mask flags and layout bounds tracked by [DesktopNativeWidget].
 */
class MacosViewHandle(
    objcClassName: String,
    var styleMask: Long = 0L,
    objcPointer: Long = nextPointerId(),
    nsView: NSView? = null,
    var nsWindow: NSWindow? = null,
    var cssTransform: CssTransform? = null
) : DesktopNativeWidget("macOS_AppKit", objcClassName, if (objcPointer != 0L) objcPointer else nextPointerId()) {

    var nsView: NSView? = nsView
        set(value) {
            val prev = field
            if (prev === value) return
            if (prev != null) {
                MacosFocusSupport.unregisterView(prev, this)
            }
            field = value
            lastAppliedFrameView = null
            lastAppliedFrameBounds = null
            lastAppliedFrameTransform = null
            hasNonIdentityLayerTransform = false
            lastAppliedFocusableView = null
            lastAppliedFocusableValue = true
            if (value != null && !disposed) {
                MacosFocusSupport.registerView(value, this)
            }
        }

    init {
        if (nsView != null) {
            MacosFocusSupport.registerView(nsView, this)
        }
    }

    internal var hostInvalidateHook: ((stageOnly: Boolean) -> Unit)? = null
    internal var hostRelayoutHook: (() -> Unit)? = null
    internal var viewMaterializer: (() -> NSView?)? = null
    internal var lastAppliedFrameView: NSView? = null
    internal var lastAppliedFrameBounds: Rect? = null
    internal var lastAppliedFrameTransform: CssTransform? = null
    internal var lastAppliedFrameAlignExpand: Boolean = false
    internal var hasNonIdentityLayerTransform: Boolean = false
    private var lastAppliedFocusableView: NSView? = null
    private var lastAppliedFocusableValue: Boolean = true

    private fun syncFocusableIfNeeded(view: NSView, effective: Boolean) {
        if (lastAppliedFocusableView === view && lastAppliedFocusableValue == effective) return
        lastAppliedFocusableView = view
        lastAppliedFocusableValue = effective
        MacosFocusSupport.applyFocusable(view, effective)
    }

    internal fun ensureNsViewOnMain(): NSView? {
        val existing = nsView
        if (existing != null) {
            if (existing is MacosInteractiveSurfaceView) {
                existing.setHidden(!visible)
                existing.setAlphaValue(opacity.coerceIn(0f, 1f).toDouble())
            }
            if (!isEffectivelyFocusable()) {
                syncFocusableIfNeeded(existing, false)
            }
            return existing
        }
        if (!MacosAppKitDispatch.isMainThread()) return null
        val created = viewMaterializer?.invoke() ?: if (interactiveSurfaceSpec != null) {
            MacosInteractiveSurfaceView(surfaceWidget = this)
        } else {
            null
        }
        if (created != null && nsView == null) {
            nsView = created
        }
        nsView?.let { v ->
            if (v !is MacosWindowRootView) {
                v.setHidden(!visible)
                v.setAlphaValue(opacity.coerceIn(0f, 1f).toDouble())
                v.setToolTip(tooltipText)
            }
            if (!isEffectivelyFocusable()) {
                syncFocusableIfNeeded(v, false)
            }
        }
        return nsView
    }

    override fun onEffectiveFocusableChanged(focusable: Boolean) {
        val v = nsView ?: return
        val effective = isEffectivelyFocusable()
        MacosAppKitDispatch.onMainOrDefer {
            if (!disposed) {
                syncFocusableIfNeeded(v, effective)
            }
        }
    }

    override fun onDisposed() {
        lastAppliedFrameView = null
        lastAppliedFrameBounds = null
        lastAppliedFrameTransform = null
        hasNonIdentityLayerTransform = false
        lastAppliedFocusableView = null
        lastAppliedFocusableValue = true
        nsView?.let { MacosFocusSupport.unregisterView(it, this) }
    }

    fun notifyRetainedTreeInvalidated(stageOnly: Boolean = false) {
        hostInvalidateHook?.invoke(stageOnly)
        notifyTreeInvalidated(visualOnly = stageOnly)
    }

    fun requestRetainedRelayout() {
        hostRelayoutHook?.invoke()
    }

    companion object {
        const val NSWindowStyleMaskBorderless: Long = 0L
        const val NSWindowStyleMaskTitled: Long = 1L shl 0
        const val NSWindowStyleMaskClosable: Long = 1L shl 1
        const val NSWindowStyleMaskMiniaturizable: Long = 1L shl 2
        const val NSWindowStyleMaskResizable: Long = 1L shl 3
        const val NSWindowStyleMaskUtilityWindow: Long = 1L shl 4
        const val NSWindowStyleMaskFullSizeContentView: Long = 1L shl 15
        const val NSWindowStyleMaskUnifiedTitleAndToolbar: Long = 1L shl 12
    }
}
