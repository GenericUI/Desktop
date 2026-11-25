@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos.impl

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.layout.DesktopContainerLayoutController
import com.nexustools.genericui.desktop.widget.DesktopScrollViewNativeImpl
import com.nexustools.genericui.enums.bootstrap.FlexDirection
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.macos.MacosAppKitDispatch
import com.nexustools.genericui.macos.MacosViewHandle
import com.nexustools.genericui.macos.usesNativeControlAlignmentExpansion
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.useContents
import platform.AppKit.NSScrollView
import platform.AppKit.NSView
import platform.AppKit.NSViewBoundsDidChangeNotification
import platform.Foundation.NSMakePoint
import platform.Foundation.NSMakeRect
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSRect
import platform.Foundation.NSSelectorFromString
import platform.darwin.NSObject
import kotlin.math.abs
import kotlin.math.roundToInt

internal class MacosScrollDocumentView(
    frameRect: CValue<NSRect> = NSMakeRect(0.0, 0.0, 200.0, 200.0)
) : NSView(frameRect) {
    init {
        setAutoresizesSubviews(false)
    }

    override fun isFlipped(): Boolean = true
}

internal class MacosClipBoundsObserver(
    private val onBoundsChanged: () -> Unit
) : NSObject() {

    val boundsSelector = NSSelectorFromString("onClipViewBoundsChanged:")

    @ObjCAction
    fun onClipViewBoundsChanged(notification: NSNotification) {
        onBoundsChanged()
    }
}

/**
 * macOS AppKit retained [NSScrollView] with a flipped [MacosScrollDocumentView] implementation of
 * [DesktopScrollViewNativeImpl].
 */
class MacosScrollViewNativeImpl(
    nativeWidget: MacosViewHandle
) : DesktopScrollViewNativeImpl<MacosViewHandle>(nativeWidget) {

    private var scrollView: NSScrollView? = null
    private var documentView: MacosScrollDocumentView? = null
    private var boundsObserver: MacosClipBoundsObserver? = null
    private var hadVerticalOverflow: Boolean = false

    init {
        val controller = DesktopContainerLayoutController(
            host = this,
            defaultFlexDirection = FlexDirection.COLUMN
        )
        controller.onZOrderUpdated = { ordered ->
            syncDocumentSubviews(ordered)
        }
        layoutController = controller
        nativeWidget.viewMaterializer = { ensureScrollView() }
        if (MacosAppKitDispatch.isMainThread()) {
            ensureScrollView()
        }
    }

    private fun ensureScrollView(): NSScrollView {
        scrollView?.let { return it }
        val sv = NSScrollView(frame = NSMakeRect(0.0, 0.0, 240.0, 240.0)).apply {
            setAutomaticallyAdjustsContentInsets(false)
            setHasVerticalScroller(true)
            setHasHorizontalScroller(false)
            setDrawsBackground(false)
            setAutohidesScrollers(true)
        }
        val doc = MacosScrollDocumentView(NSMakeRect(0.0, 0.0, 240.0, 240.0))
        sv.setDocumentView(doc)
        val clipView = sv.contentView()
        clipView.setPostsBoundsChangedNotifications(true)

        val obs = MacosClipBoundsObserver {
            val (cx, cy) = clipView.bounds.useContents {
                origin.x.roundToInt() to origin.y.roundToInt()
            }
            updateScrollPositionFromHost(cx, cy)
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

    private fun syncDocumentSubviews(orderedChildren: List<DesktopBaseNativeImpl<*>>) {
        val doc = documentView ?: run {
            ensureScrollView()
            documentView ?: return
        }
        val childViews = ArrayList<NSView>(orderedChildren.size)
        for (child in orderedChildren) {
            val v = (child.nativeWidget as? MacosViewHandle)?.ensureNsViewOnMain()
            if (v != null) childViews.add(v)
        }
        MacosAppKitDispatch.syncChildViewHierarchy(doc, childViews)
    }

    override fun onChildAddedNative(childImpl: NativeImpl<*>, index: Int) {
        super.onChildAddedNative(childImpl, index)
        if (MacosAppKitDispatch.isMainThread()) {
            syncDocumentSubviews(getLogicalChildren().filterIsInstance<DesktopBaseNativeImpl<*>>())
        }
        nativeWidget.requestRetainedRelayout()
    }

    override fun onChildRemovedNative(childImpl: NativeImpl<*>) {
        super.onChildRemovedNative(childImpl)
        if (MacosAppKitDispatch.isMainThread()) {
            syncDocumentSubviews(getLogicalChildren().filterIsInstance<DesktopBaseNativeImpl<*>>())
        }
        nativeWidget.requestRetainedRelayout()
    }

    override fun onChildrenClearedNative() {
        super.onChildrenClearedNative()
        if (MacosAppKitDispatch.isMainThread()) {
            syncDocumentSubviews(emptyList())
        }
        nativeWidget.requestRetainedRelayout()
    }

    private fun resolveRealizedContentWidth(sv: NSScrollView?, fallbackWidth: Float): Float {
        if (sv != null) {
            val frameW = sv.frame.useContents { size.width }
            val contentSizeW = sv.contentSize.useContents { this.width }
            if (contentSizeW > 0.0 && abs(frameW - fallbackWidth.toDouble()) <= 1.0) {
                return contentSizeW.toFloat().coerceAtLeast(1f)
            }
        }
        return fallbackWidth.coerceAtLeast(1f)
    }

    private fun layoutDocumentChildren(contentW: Float, viewportHeight: Float): Pair<Double, Double> {
        val pref = layoutController?.measureScrollableContentSize(contentW)
            ?: layoutController?.measurePreferredSize(contentW, null)
        val layoutH = maxOf(viewportHeight, pref?.height?.toFloat() ?: viewportHeight)
        layoutController?.layoutInBounds(contentW, layoutH)
        val ordered = layoutController?.resolveChildrenSortedByZIndex()
            ?: getLogicalChildren().filterIsInstance<DesktopBaseNativeImpl<*>>()
        val scrollPad = getEffectivePaddingInsets(contentW, layoutH)
        var maxRight = contentW.toDouble().coerceAtLeast(1.0)
        var maxBottom = viewportHeight.toDouble().coerceAtLeast(1.0)
        for (child in ordered) {
            if (child.isVisible() && child.nativeWidget.visible) {
                val b = child.nativeWidget.bounds
                val m = child.nativeWidget.margin
                maxRight = maxOf(maxRight, (b.right + m.right + scrollPad.right).toDouble())
                maxBottom = maxOf(maxBottom, (b.bottom + m.bottom + scrollPad.bottom).toDouble())
            }
            val childHandle = child.nativeWidget as? MacosViewHandle ?: continue
            val v = childHandle.ensureNsViewOnMain() ?: continue
            MacosAppKitDispatch.applyFrameAndTransform(
                view = v,
                bounds = child.nativeWidget.bounds,
                transform = childHandle.cssTransform,
                useAlignmentRectExpansion = child.usesNativeControlAlignmentExpansion()
            )
        }
        syncDocumentSubviews(ordered)
        return maxRight to maxBottom
    }

    private fun applyScrollerAndDocumentState(
        sv: NSScrollView,
        contentW: Float,
        viewportHeight: Float,
        maxRight: Double,
        maxBottom: Double
    ): Pair<Boolean, Boolean> {
        val overflow = (nativeWidget.attributes["overflow"] as? String)?.trim()?.lowercase()
        val overflowX = (nativeWidget.attributes["overflowX"] as? String)?.trim()?.lowercase() ?: overflow
        val overflowY = (nativeWidget.attributes["overflowY"] as? String)?.trim()?.lowercase() ?: overflow
        val allowVerticalScroll = overflowY != "hidden" && overflowY != "visible"
        val allowHorizontalScroll = overflowX != "hidden" && overflowX != "visible"

        val vertOverflow = maxBottom > viewportHeight.toDouble() + 0.5
        val horzOverflow = maxRight > contentW.toDouble() + 0.5
        val hasVertical = when (overflowY) {
            "scroll" -> true
            "hidden", "visible" -> false
            else -> vertOverflow
        }
        val hasHorizontal = when (overflowX) {
            "scroll" -> true
            "hidden", "visible" -> false
            else -> horzOverflow
        }

        sv.setHasVerticalScroller(hasVertical)
        sv.setHasHorizontalScroller(hasHorizontal)
        documentView?.setFrame(
            NSMakeRect(
                0.0,
                0.0,
                if (allowHorizontalScroll) maxRight else contentW.toDouble().coerceAtLeast(1.0),
                if (allowVerticalScroll) maxBottom else viewportHeight.toDouble().coerceAtLeast(1.0)
            )
        )
        sv.tile()
        return hasVertical to vertOverflow
    }

    override fun layoutChildrenInBounds(width: Float, height: Float) {
        if (!MacosAppKitDispatch.isMainThread()) {
            val fallbackW = width.coerceAtLeast(1f)
            val pref = layoutController?.measureScrollableContentSize(fallbackW)
                ?: layoutController?.measurePreferredSize(fallbackW, null)
            val layoutH = maxOf(height, pref?.height?.toFloat() ?: height)
            layoutController?.layoutInBounds(fallbackW, layoutH)
            return
        }
        val sv = ensureScrollView()
        var contentW = resolveRealizedContentWidth(sv, width)
        var (maxRight, maxBottom) = layoutDocumentChildren(contentW, height)
        var (hasVertical, vertOverflow) = applyScrollerAndDocumentState(sv, contentW, height, maxRight, maxBottom)

        // If toggling scrollers changed the realized content width (e.g., legacy scroller mode),
        // re-layout once at the updated content width so horizontal overflow stays accurate.
        val postTileW = sv.contentSize.useContents { this.width }.toFloat()
        if (postTileW > 0f && abs(postTileW - contentW) > 0.5f && abs(sv.frame.useContents { size.width } - width.toDouble()) <= 1.0) {
            contentW = postTileW.coerceAtLeast(1f)
            val updated = layoutDocumentChildren(contentW, height)
            maxRight = updated.first
            maxBottom = updated.second
            val applied = applyScrollerAndDocumentState(sv, contentW, height, maxRight, maxBottom)
            hasVertical = applied.first
            vertOverflow = applied.second
        }

        sv.reflectScrolledClipView(sv.contentView())
        val activeVertOverflow = hasVertical && vertOverflow
        if (activeVertOverflow && !hadVerticalOverflow) {
            sv.flashScrollers()
        }
        hadVerticalOverflow = activeVertOverflow
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        return layoutController?.measurePreferredSize(maxWidth, maxHeight)
            ?: super.measureIntrinsicSize(maxWidth, maxHeight)
    }

    override fun scrollTo(x: Int, y: Int) {
        super.scrollTo(x, y)
        val sv = scrollView ?: return
        val doc = documentView ?: return
        val (dw, dh) = doc.frame.useContents { size.width to size.height }
        val clip = sv.contentView()
        val (cw, ch) = clip.bounds.useContents { size.width to size.height }
        val maxX = (dw - cw).coerceAtLeast(0.0)
        val maxY = (dh - ch).coerceAtLeast(0.0)
        clip.scrollToPoint(
            NSMakePoint(x.toDouble().coerceIn(0.0, maxX), y.toDouble().coerceIn(0.0, maxY))
        )
        sv.reflectScrolledClipView(clip)
    }

    override fun scrollToBottom() {
        super.scrollToBottom()
        val sv = scrollView ?: return
        val doc = documentView ?: return
        val dh = doc.frame.useContents { size.height }
        val clip = sv.contentView()
        val ch = clip.bounds.useContents { size.height }
        val targetY = (dh - ch).coerceAtLeast(0.0)
        clip.scrollToPoint(NSMakePoint(scrollX.toDouble().coerceAtLeast(0.0), targetY))
        sv.reflectScrolledClipView(clip)
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer {
                if (!isDisposed) onNativeBoundsChanged(nativeWidget.bounds)
            }
            return
        }
        val sv = ensureScrollView()
        MacosAppKitDispatch.applyFrameAndTransform(sv, rect, nativeWidget.cssTransform, useAlignmentRectExpansion = false)
    }

    override fun onNativeVisibilityChanged(visible: Boolean) {
        scrollView?.setHidden(!visible)
        nativeWidget.requestRetainedRelayout()
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
