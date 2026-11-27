@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.component.action.styled.StyledButton
import com.nexustools.genericui.component.layout.Container
import com.nexustools.genericui.component.layout.ScrollView
import com.nexustools.genericui.component.layout.styled.StyledContainer
import com.nexustools.genericui.component.layout.VStack
import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.component.shell.Window
import com.nexustools.genericui.component.surface.styled.StyledComponent
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.enums.css.CssCursor
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.macos.impl.MacosScrollViewNativeImpl
import com.nexustools.genericui.style.css.CssLength
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.CValue
import kotlinx.cinterop.useContents
import platform.AppKit.NSCursor
import platform.AppKit.NSEvent
import platform.AppKit.NSEventTypeMouseMoved
import platform.AppKit.NSScrollView
import platform.AppKit.NSView
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGEventCreateScrollWheelEvent2
import platform.CoreGraphics.kCGScrollEventUnitPixel
import platform.Foundation.NSMakeRect
import platform.Foundation.NSRect
import platform.Foundation.NSThread
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_sync
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MacosScrollAndWheelBehaviorTest {

    private inline fun runOnMainThreadSync(crossinline block: () -> Unit) {
        if (NSThread.isMainThread) {
            block()
            return
        }
        var failure: Throwable? = null
        dispatch_sync(dispatch_get_main_queue()) {
            try {
                block()
            } catch (t: Throwable) {
                failure = t
            }
        }
        failure?.let { throw it }
    }

    private inline fun withInteractiveMacosHost(crossinline block: (MacosNativeFactory) -> Unit) {
        val factory = MacosNativeFactory()
        val prevFactory = GenericUI.activeFactory
        val prevApp = Application.current
        val prevInteractive = MacosApplicationNativeImpl.interactiveMacosGuiEnabled
        val prevVisual = MacosNativeWindowHost.visualTestMode
        Application.current = null
        MacosApplicationNativeImpl.interactiveMacosGuiEnabled = true
        MacosNativeWindowHost.visualTestMode = true
        GenericUI.initialize(factory)
        try {
            runOnMainThreadSync {
                block(factory)
            }
        } finally {
            runOnMainThreadSync {
                MacosNativeWindowHost.closeAllVisualWindows()
                GenericUiThreading.flushAllQueues()
            }
            MacosNativeWindowHost.visualTestMode = prevVisual
            MacosApplicationNativeImpl.interactiveMacosGuiEnabled = prevInteractive
            Application.current = prevApp
            GenericUI.activeFactory = prevFactory
        }
    }

    private fun createScrollWheelEvent(deltaY: Int): NSEvent {
        val cgEvent = assertNotNull(
            CGEventCreateScrollWheelEvent2(
                source = null,
                units = kCGScrollEventUnitPixel,
                wheelCount = 1u,
                wheel1 = deltaY,
                wheel2 = 0,
                wheel3 = 0
            )
        )
        return try {
            assertNotNull(NSEvent.eventWithCGEvent(cgEvent))
        } finally {
            CFRelease(cgEvent)
        }
    }

    private class RecordingCursorView(
        owner: DesktopBaseNativeImpl<*>,
        frameRect: CValue<NSRect>
    ) : NSView(frameRect) {
        val controller = MacosViewInteractionController(owner, this)
        val recordedRects = mutableListOf<Pair<List<Double>, NSCursor>>()

        override fun isFlipped(): Boolean = true

        override fun resetCursorRects() {
            recordedRects.clear()
            controller.applyCursorRects()
        }

        override fun addCursorRect(rect: CValue<NSRect>, cursor: NSCursor) {
            val coords = rect.useContents { listOf(origin.x, origin.y, size.width, size.height) }
            recordedRects.add(coords to cursor)
            super.addCursorRect(rect, cursor)
        }

        override fun cursorUpdate(event: NSEvent) {
            controller.handleCursorUpdate(event)
        }

        override fun mouseMoved(event: NSEvent) {
            controller.handleMouseMoved(event)
        }
    }

    @Test
    fun scrollViewWithTallNestedVStackExpandsDocumentViewAndConfiguresScrollers() = withInteractiveMacosHost { factory ->
        val nestedStack = VStack(gapPx = 8).apply {
            style.setWidth(CssLength.percent(100f))
            repeat(6) {
                addChild(Container().apply {
                    style.setWidth(CssLength.percent(100f))
                    style.setHeight(CssLength.px(44f))
                })
            }
        }
        val outerStack = VStack(gapPx = 8).apply {
            addClass("h-100")
            style.setWidth(CssLength.percent(100f))
            repeat(12) {
                addChild(Container().apply {
                    style.setWidth(CssLength.percent(100f))
                    style.setHeight(CssLength.px(44f))
                })
            }
            addChild(nestedStack)
        }
        val scrollView = ScrollView().apply {
            addClass("h-100")
            style.setWidth(CssLength.percent(100f))
            addChild(outerStack)
        }

        val window = Window(title = "ScrollView Overflow", initialSize = Size(420, 260)).apply {
            setContent(Container().apply {
                addClass("w-100", "h-100")
                addChild(scrollView)
            })
        }
        window.show(factory)
        MacosNativeWindowHost.pumpVisualWindowEvents(48L)

        val scrollImpl = assertNotNull(scrollView.ensureNativeImpl() as? MacosScrollViewNativeImpl)
        val sv = assertNotNull(scrollImpl.nativeWidget.nsView as? NSScrollView)
        val docView = assertNotNull(sv.documentView)

        val (docW, docH) = docView.frame.useContents { size.width to size.height }
        assertTrue(docH > 260.0, "Expected documentView height to exceed 260pt viewport, got $docH")
        assertTrue(docH >= 850.0, "Expected full unconstrained stack height >= 850pt, got $docH")
        assertTrue(docW <= 420.5, "Expected documentView width to fit viewport without horizontal overflow, got $docW")
        assertTrue(sv.hasVerticalScroller, "Expected vertical scroller enabled for overflowing content")
        assertFalse(sv.hasHorizontalScroller, "Expected horizontal scroller disabled when width fits")

        val nestedImpl = assertNotNull(nestedStack.ensureNativeImpl() as? DesktopBaseNativeImpl<*>)
        assertTrue(nestedImpl.nativeWidget.bounds.height >= 280, "Nested VStack must lay out at unconstrained height, got ${nestedImpl.nativeWidget.bounds.height}")

        val nestedPref = (nestedImpl as MacosContainerNativeImpl).measureIntrinsicSize(400f, null)
        assertTrue(nestedPref.height >= 280, "Unconstrained measurePreferredSize on nested VStack must return full height, got ${nestedPref.height}")

        scrollView.scrollTo(0, 140)
        GenericUiThreading.flushAllQueues()
        val clipOriginY = sv.contentView().bounds.useContents { origin.y }
        assertTrue(abs(clipOriginY - 140.0) <= 1.0, "Expected clipView origin.y near 140, got $clipOriginY")
        assertEquals(140, scrollImpl.currentScrollY())

        window.close()
    }

    @Test
    fun scrollWheelOnChildViewsBubblesToScrollViewUnlessOnWheelConsumed() = withInteractiveMacosHost { factory ->
        val plainContainer = Container().apply {
            style.setWidth(CssLength.percent(100f))
            style.setHeight(CssLength.px(120f))
        }
        val styledContainer = StyledContainer().apply {
            style.setWidth(CssLength.percent(100f))
            style.setHeight(CssLength.px(120f))
        }
        val styledComponent = StyledComponent().apply {
            style.setWidth(CssLength.percent(100f))
            style.setHeight(CssLength.px(120f))
        }
        val styledButton = StyledButton("Button").apply {
            style.setWidth(CssLength.percent(100f))
            style.setHeight(CssLength.px(120f))
        }
        val filler = Container().apply {
            style.setWidth(CssLength.percent(100f))
            style.setHeight(CssLength.px(420f))
        }
        val stack = VStack(gapPx = 8).apply {
            style.setWidth(CssLength.percent(100f))
            addChild(plainContainer)
            addChild(styledContainer)
            addChild(styledComponent)
            addChild(styledButton)
            addChild(filler)
        }
        val scrollView = ScrollView().apply {
            addClass("h-100")
            style.setWidth(CssLength.percent(100f))
            addChild(stack)
        }

        val window = Window(title = "Wheel Bubbling", initialSize = Size(420, 240)).apply {
            setContent(Container().apply {
                addClass("w-100", "h-100")
                addChild(scrollView)
            })
        }
        window.show(factory)
        MacosNativeWindowHost.pumpVisualWindowEvents(48L)

        val scrollImpl = assertNotNull(scrollView.ensureNativeImpl() as? MacosScrollViewNativeImpl)
        val sv = assertNotNull(scrollImpl.nativeWidget.nsView as? NSScrollView)
        val docView = assertNotNull(sv.documentView)
        val clipView = sv.contentView()

        val plainView = assertNotNull((plainContainer.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView)
        val styledContainerView = assertNotNull((styledContainer.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView)
        val styledComponentView = assertNotNull((styledComponent.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView)
        val styledButtonView = assertNotNull((styledButton.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView)

        val leafSurfaceHandle = createMacosInteractiveSurfaceHandle("wheel-test-surface")
        val leafSurfaceView = MacosInteractiveSurfaceView(
            surfaceWidget = leafSurfaceHandle,
            frameRect = NSMakeRect(0.0, 0.0, 100.0, 40.0)
        )
        docView.addSubview(leafSurfaceView)

        val wheelDownEvent = createScrollWheelEvent(deltaY = -40)
        val childViews = listOf(
            plainView,
            styledContainerView,
            styledComponentView,
            styledButtonView,
            leafSurfaceView
        )
        for (childView in childViews) {
            val prevY = clipView.bounds.useContents { origin.y }
            childView.scrollWheel(wheelDownEvent)
            val nextY = clipView.bounds.useContents { origin.y }
            assertTrue(
                nextY > prevY,
                "Expected scrollWheel on ${childView::class.simpleName} without onWheel to bubble to NSScrollView (prev=$prevY, next=$nextY)"
            )
        }

        var wheelCallbackCount = 0
        plainContainer.onWheel = { wheelCallbackCount++ }
        GenericUiThreading.flushAllQueues()

        val beforeConsumedY = clipView.bounds.useContents { origin.y }
        plainView.scrollWheel(wheelDownEvent)
        GenericUiThreading.flushAllQueues()
        val afterConsumedY = clipView.bounds.useContents { origin.y }

        assertEquals(1, wheelCallbackCount, "Expected child onWheel listener to receive wheel event")
        assertEquals(
            beforeConsumedY,
            afterConsumedY,
            1e-3,
            "Expected consumed onWheel event NOT to bubble to enclosing NSScrollView"
        )

        leafSurfaceView.removeFromSuperview()
        window.close()
    }

    @Test
    fun undecoratedWindowClipsSubviewCursorRectsAndSkipsUpdatesDuringResize() = withInteractiveMacosHost { factory ->
        val fullBleedContent = StyledContainer().apply {
            style.setWidth(CssLength.percent(100f))
            style.setHeight(CssLength.percent(100f))
            style.setCursor(CssCursor.POINTER)
        }
        val window = Window(title = "Cursor Rect Isolation", initialSize = Size(400, 300)).apply {
            setUndecorated(true)
            setContent(fullBleedContent)
        }
        window.show(factory)
        MacosNativeWindowHost.pumpVisualWindowEvents(48L)

        val winImpl = window.ensureNativeImpl() as MacosWindowNativeImpl
        val rootView = assertNotNull((winImpl.nativeWidget as MacosViewHandle).nsView as? MacosWindowRootView)
        assertTrue(rootView.isUndecoratedChrome)
        assertFalse(rootView.isWindowInFullscreen())

        val contentImpl = assertNotNull(fullBleedContent.ensureNativeImpl() as? DesktopBaseNativeImpl<*>)
        val probeView = RecordingCursorView(contentImpl, NSMakeRect(0.0, 0.0, 400.0, 300.0))
        rootView.addSubview(probeView)

        probeView.resetCursorRects()
        assertEquals(1, probeView.recordedRects.size, "Expected one clipped cursor rect for full-bleed subview")
        val (rectCoords, cursor) = probeView.recordedRects.first()
        val edge = MacosNativeWindowHost.UNDECORATED_RESIZE_EDGE_PT
        assertEquals(NSCursor.pointingHandCursor, cursor)
        assertEquals(edge, rectCoords[0], 1e-4)
        assertEquals(edge, rectCoords[1], 1e-4)
        assertEquals(400.0 - 2.0 * edge, rectCoords[2], 1e-4)
        assertEquals(300.0 - 2.0 * edge, rectCoords[3], 1e-4)

        // Lock cursor via native live resize: subview cursor rects and updates must be skipped.
        val resizeCursor = MacosNativeWindowHost.resolveCursorForResizeEdge(MacosNativeWindowHost.UndecoratedResizeEdge.E).second
        rootView.updateEffectiveCursorForClientPoint(398.0, 150.0, forceCheckCurrent = true)
        assertEquals(resizeCursor, rootView.lastAppliedCursor)

        val interiorWinLoc = rootView.convertClientPointToWindow(200.0, 150.0)
        val moveEv = assertNotNull(
            NSEvent.mouseEventWithType(
                type = NSEventTypeMouseMoved,
                location = interiorWinLoc,
                modifierFlags = 0uL,
                timestamp = 0.0,
                windowNumber = rootView.window?.windowNumber ?: 0L,
                context = null,
                eventNumber = 0L,
                clickCount = 0L,
                pressure = 0f
            )
        )

        rootView.isInNativeLiveResize = true
        probeView.resetCursorRects()
        assertTrue(probeView.recordedRects.isEmpty(), "Expected no subview cursor rects during native live resize")
        probeView.cursorUpdate(moveEv)
        probeView.mouseMoved(moveEv)
        assertEquals(resizeCursor, rootView.lastAppliedCursor, "Subview cursorUpdate/mouseMoved must not overwrite live-resize cursor")
        rootView.isInNativeLiveResize = false

        // Lock cursor via custom edge drag: subview cursor rects and updates must also be skipped.
        rootView.activeCustomResizeEdge = MacosNativeWindowHost.UndecoratedResizeEdge.E
        probeView.resetCursorRects()
        assertTrue(probeView.recordedRects.isEmpty(), "Expected no subview cursor rects during active custom resize")
        probeView.cursorUpdate(moveEv)
        probeView.mouseMoved(moveEv)
        assertEquals(resizeCursor, rootView.lastAppliedCursor, "Subview cursorUpdate/mouseMoved must not overwrite custom-resize cursor")
        rootView.activeCustomResizeEdge = MacosNativeWindowHost.UndecoratedResizeEdge.NONE

        // Outside undecorated chrome, subview receives the full unclipped bounds cursor rect.
        rootView.isUndecoratedChrome = false
        probeView.resetCursorRects()
        assertEquals(1, probeView.recordedRects.size)
        val (fullCoords, _) = probeView.recordedRects.first()
        assertEquals(listOf(0.0, 0.0, 400.0, 300.0), fullCoords)

        probeView.removeFromSuperview()
        window.close()
    }
}
