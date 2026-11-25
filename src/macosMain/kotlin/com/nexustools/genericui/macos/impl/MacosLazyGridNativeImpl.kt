@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos.impl

import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.lazy.DesktopLazyGridNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.macos.MacosAppKitDispatch
import com.nexustools.genericui.macos.MacosViewHandle
import com.nexustools.genericui.macos.usesNativeControlAlignmentExpansion
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.useContents
import platform.AppKit.NSScrollView
import platform.AppKit.NSView
import platform.AppKit.NSViewBoundsDidChangeNotification
import platform.Foundation.NSMakePoint
import platform.Foundation.NSMakeRect
import platform.Foundation.NSNotificationCenter
import kotlin.math.roundToInt

/**
 * macOS AppKit virtualized [NSScrollView] implementation of [DesktopLazyGridNativeImpl].
 */
class MacosLazyGridNativeImpl(
    nativeWidget: MacosViewHandle
) : DesktopLazyGridNativeImpl<MacosViewHandle>(nativeWidget) {

    var minColumnWidthPx: Int = 0
        private set
    private var scrollView: NSScrollView? = null
    private var documentView: MacosScrollDocumentView? = null
    private var boundsObserver: MacosClipBoundsObserver? = null

    init {
        nativeWidget.viewMaterializer = { ensureScrollView() }
        if (MacosAppKitDispatch.isMainThread()) {
            ensureScrollView()
        }
    }

    private var isLayingOutGridCells: Boolean = false

    private fun ensureScrollView(): NSScrollView {
        scrollView?.let { return it }
        val sv = NSScrollView(frame = NSMakeRect(0.0, 0.0, 360.0, 360.0)).apply {
            setAutomaticallyAdjustsContentInsets(false)
            setHasVerticalScroller(true)
            setHasHorizontalScroller(false)
            setDrawsBackground(false)
            setAutohidesScrollers(true)
        }
        val doc = MacosScrollDocumentView(NSMakeRect(0.0, 0.0, 360.0, 360.0))
        sv.setDocumentView(doc)
        val clipView = sv.contentView()
        clipView.setPostsBoundsChangedNotifications(true)
        val obs = MacosClipBoundsObserver {
            if (isLayingOutGridCells) return@MacosClipBoundsObserver
            val (oy, ch) = clipView.bounds.useContents { origin.y to size.height }
            viewportEngine.scrollOffsetPx = oy.toFloat().coerceAtLeast(0f)
            viewportEngine.viewportExtentPx = ch.toFloat().coerceAtLeast(1f)
            rematerializeVisibleWindow()
        }
        NSNotificationCenter.defaultCenter.addObserver(
            observer = obs,
            selector = obs.boundsSelector,
            name = NSViewBoundsDidChangeNotification,
            `object` = clipView
        )
        scrollView = sv
        documentView = doc
        boundsObserver = obs
        nativeWidget.nsView = sv
        sv.setHidden(!nativeWidget.visible)
        sv.setAlphaValue(nativeWidget.opacity.coerceIn(0f, 1f).toDouble())
        sv.setToolTip(nativeWidget.tooltipText)
        return sv
    }

    override fun rematerializeVisibleWindow() {
        super.rematerializeVisibleWindow()
        if (MacosAppKitDispatch.isMainThread()) {
            ensureScrollView()
            layoutGridCells()
        } else {
            MacosAppKitDispatch.onMainOrDefer {
                if (!isDisposed) {
                    ensureScrollView()
                    layoutGridCells()
                }
            }
        }
    }

    override fun layoutChildrenInBounds(width: Float, height: Float) {
        val newExtent = height.coerceAtLeast(1f)
        if (kotlin.math.abs(newExtent - viewportEngine.viewportExtentPx) > 0.5f ||
            (viewportEngine.totalItemCount > 0 && materializedItems.isEmpty())
        ) {
            viewportEngine.viewportExtentPx = newExtent
            rematerializeVisibleWindow()
        }
        if (MacosAppKitDispatch.isMainThread()) {
            ensureScrollView()
            layoutGridCells(width, height)
        }
    }

    override fun setMinColumnWidthPx(minWidthPx: Int) {
        minColumnWidthPx = minWidthPx.coerceAtLeast(0)
        super.setMinColumnWidthPx(minWidthPx)
        nativeWidget.attributes["minColumnWidthPx"] = minColumnWidthPx
        if (MacosAppKitDispatch.isMainThread()) {
            layoutGridCells()
        }
    }

    fun getMinColumnWidthPx(): Int = minColumnWidthPx

    fun resolveEffectiveColumns(availableWidthPx: Int): Int =
        if (minColumnWidthPx > 0 && availableWidthPx > 0) {
            kotlin.math.max(1, availableWidthPx / minColumnWidthPx)
        } else {
            getColumnCount()
        }

    private fun layoutGridCells(
        viewW: Float = nativeWidget.bounds.width.coerceAtLeast(1).toFloat(),
        viewH: Float = nativeWidget.bounds.height.coerceAtLeast(1).toFloat()
    ) {
        val doc = documentView ?: return
        isLayingOutGridCells = true
        try {
            val cols = resolveEffectiveColumns(viewW.roundToInt()).coerceAtLeast(1)
            viewportEngine.columns = cols
            val cellW = (viewW / cols.toFloat()).roundToInt().coerceAtLeast(24)
            val cellH = viewportEngine.estimatedItemExtentPx.roundToInt().coerceAtLeast(28)
            val orderedPairs = materializedItems.entries.map { it.key to it.value }.sortedBy { it.first }
            val mounted = ArrayList<NSView>(orderedPairs.size)

            for ((idx, itemImpl) in orderedPairs) {
                val base = itemImpl as? DesktopBaseNativeImpl<*> ?: continue
                val row = idx / cols
                val col = idx % cols
                val cx = col * cellW
                val cy = row * cellH
                val childHandle = base.nativeWidget as? MacosViewHandle
                val v = childHandle?.ensureNsViewOnMain()
                base.setBounds(Rect(cx, cy, cellW, cellH))
                if (base is DesktopBaseContainerNativeImpl<*>) {
                    base.layoutChildrenInBounds(cellW.toFloat(), cellH.toFloat())
                }
                if (v != null) {
                    mounted.add(v)
                    MacosAppKitDispatch.applyFrameAndTransform(
                        view = v,
                        bounds = Rect(cx, cy, cellW, cellH),
                        transform = childHandle.cssTransform,
                        useAlignmentRectExpansion = base.usesNativeControlAlignmentExpansion()
                    )
                }
            }
            val totalH = maxOf(viewH.toDouble(), (getTotalRowCount() * cellH).toDouble())
            doc.setFrame(NSMakeRect(0.0, 0.0, viewW.toDouble(), totalH))
            MacosAppKitDispatch.syncChildViewHierarchy(doc, mounted)
            scrollView?.let { sv ->
                sv.tile()
                sv.reflectScrolledClipView(sv.contentView())
            }
        } finally {
            isLayingOutGridCells = false
        }
    }

    override fun scrollToIndex(index: Int) {
        super.scrollToIndex(index)
        val sv = scrollView ?: return
        val clip = sv.contentView()
        clip.scrollToPoint(NSMakePoint(0.0, viewportEngine.scrollOffsetPx.toDouble().coerceAtLeast(0.0)))
        sv.reflectScrolledClipView(clip)
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size =
        Size((maxWidth?.toInt() ?: 360).coerceAtLeast(160), (maxHeight?.toInt() ?: 360).coerceAtLeast(120))

    override fun onNativeBoundsChanged(rect: Rect) {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer {
                if (!isDisposed) onNativeBoundsChanged(nativeWidget.bounds)
            }
            return
        }
        val sv = ensureScrollView()
        MacosAppKitDispatch.applyFrameAndTransform(sv, rect, nativeWidget.cssTransform, useAlignmentRectExpansion = false)
        val newExtent = rect.height.coerceAtLeast(1).toFloat()
        if (kotlin.math.abs(newExtent - viewportEngine.viewportExtentPx) > 0.5f ||
            (viewportEngine.totalItemCount > 0 && materializedItems.isEmpty())
        ) {
            viewportEngine.viewportExtentPx = newExtent
            rematerializeVisibleWindow()
        }
        layoutGridCells(rect.width.toFloat(), rect.height.toFloat())
    }

    override fun onNativeVisibilityChanged(visible: Boolean) {
        scrollView?.setHidden(!visible)
    }

    override fun onNativeOpacityChanged(opacity: Float) {
        scrollView?.setAlphaValue(opacity.coerceIn(0f, 1f).toDouble())
    }

    override fun onNativeTooltipChanged(tooltip: String?) {
        scrollView?.setToolTip(tooltip)
    }

    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        scrollView?.let { v -> v.window?.invalidateCursorRectsForView(v) }
    }

    override fun onNativeTransformChanged(transform: CssTransform?) {
        nativeWidget.cssTransform = transform
        val sv = scrollView ?: return
        MacosAppKitDispatch.applyFrameAndTransform(sv, nativeWidget.bounds, transform, useAlignmentRectExpansion = false)
    }

    override fun dispose() {
        val obs = boundsObserver
        val sv = scrollView
        boundsObserver = null
        scrollView = null
        documentView = null
        MacosAppKitDispatch.onMainOrDefer {
            obs?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
            sv?.removeFromSuperview()
        }
        super.dispose()
    }
}
