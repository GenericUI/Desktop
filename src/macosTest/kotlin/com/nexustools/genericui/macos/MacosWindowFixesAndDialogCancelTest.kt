@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.animation.Animator
import com.nexustools.genericui.component.action.Button
import com.nexustools.genericui.component.dialog.DialogWindow
import com.nexustools.genericui.component.form.Select
import com.nexustools.genericui.component.form.SelectOption
import com.nexustools.genericui.component.fragment.Fragment
import com.nexustools.genericui.component.fragment.FragmentContainer
import com.nexustools.genericui.component.layout.Container
import com.nexustools.genericui.component.layout.HStack
import com.nexustools.genericui.component.layout.ScrollView
import com.nexustools.genericui.component.layout.VStack
import com.nexustools.genericui.component.layout.styled.StyledContainer
import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.component.shell.Window
import com.nexustools.genericui.component.typography.Badge
import com.nexustools.genericui.component.typography.Text
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.desktop.core.DesktopInteractiveSurfaceSpec
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.core.DesktopStageOverlaySpec
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.css.CssBorderStyle
import com.nexustools.genericui.enums.css.CssCursor
import com.nexustools.genericui.enums.ui.ObjectFit
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.enums.window.WindowState
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.macos.impl.MacosBadgeView
import com.nexustools.genericui.macos.impl.MacosTextNativeImpl
import com.nexustools.genericui.nativeimpl.system.DefaultAnimationNativeImpl
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.css.CssLength
import com.nexustools.genericui.style.css.CssStyleSheet
import com.nexustools.genericui.system.style.BlinkStyleEngine
import com.nexustools.genericui.system.style.SystemCssAnimationController
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.set
import kotlinx.cinterop.useContents
import platform.AppKit.NSButton
import platform.AppKit.NSCursor
import platform.AppKit.NSEvent
import platform.AppKit.NSEventTypeLeftMouseDown
import platform.AppKit.NSEventTypeLeftMouseUp
import platform.AppKit.NSEventTypeMouseMoved
import platform.AppKit.NSKernAttributeName
import platform.AppKit.NSParagraphStyle
import platform.AppKit.NSParagraphStyleAttributeName
import platform.AppKit.NSPanel
import platform.AppKit.NSPopUpButton
import platform.AppKit.NSScreen
import platform.AppKit.NSScrollView
import platform.AppKit.NSView
import platform.AppKit.NSVisualEffectView
import platform.AppKit.NSWindowCloseButton
import platform.AppKit.NSWindowMiniaturizeButton
import platform.AppKit.NSWindowStyleMaskFullScreen
import platform.AppKit.NSWindowZoomButton
import platform.AppKit.NSWorkspace
import platform.AppKit.accessibilityDisplayShouldReduceMotion
import platform.Foundation.NSMakePoint
import platform.Foundation.NSMakeRect
import platform.Foundation.NSNotification
import platform.Foundation.NSNumber
import platform.Foundation.NSThread
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_sync
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class MacosWindowFixesAndDialogCancelTest {

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

    private fun pumpUntil(timeoutMs: Long = 2000L, predicate: () -> Boolean): Boolean {
        val mark = TimeSource.Monotonic.markNow()
        while (!predicate() && mark.elapsedNow().inWholeMilliseconds < timeoutMs) {
            MacosNativeWindowHost.pumpVisualWindowEvents(16L)
        }
        return predicate()
    }

    @Test
    fun smoothMaximizeAnimationDefersAspectRatioUntilCompletionAndSurvivesRapidToggle() = withInteractiveMacosHost { factory ->
        val window = Window(title = "Smooth Maximize", initialSize = Size(800, 450)).apply {
            setUndecorated(true)
            setAspectRatio(16, 9)
            setContent(Container())
        }
        window.show(factory)
        MacosNativeWindowHost.pumpVisualWindowEvents(32L)

        val hostWin = assertNotNull(MacosNativeWindowHost.activeNsWindow as? MacosHostWindow)
        val reduceMotion = NSWorkspace.sharedWorkspace.accessibilityDisplayShouldReduceMotion

        // 1. Maximize
        hostWin.toggleMaximizeOrRestore()
        if (!reduceMotion) {
            assertTrue(hostWin.isAnimatingMaximize, "Maximize must animate when Reduce Motion is off")
        }
        assertTrue(
            pumpUntil(timeoutMs = 1500L) { !hostWin.isAnimatingMaximize && window.getWindowState() == WindowState.MAXIMIZED },
            "Maximize animation must settle cleanly"
        )
        assertTrue(hostWin.isUndecoratedMaximized)
        val targetScreen = hostWin.screen ?: NSScreen.mainScreen ?: (NSScreen.screens.firstOrNull() as? NSScreen)
        if (targetScreen != null) {
            val vis = targetScreen.visibleFrame.useContents { size.width to size.height }
            val maxSize = hostWin.frame.useContents { size.width to size.height }
            assertEquals(vis.first, maxSize.first, 2.0)
            assertEquals(vis.second, maxSize.second, 2.0)
        }

        // 2. Restore: contentAspectRatio must remain cleared until completion handler fires.
        hostWin.toggleMaximizeOrRestore()
        if (!reduceMotion) {
            assertTrue(hostWin.isAnimatingMaximize)
            val midAspect = hostWin.contentAspectRatio.useContents { width to height }
            assertEquals(0.0, midAspect.first, 1e-6, "contentAspectRatio must stay suspended during restore animation")
            assertEquals(0.0, midAspect.second, 1e-6)
        }
        assertTrue(
            pumpUntil(timeoutMs = 1500L) { !hostWin.isAnimatingMaximize && window.getWindowState() == WindowState.NORMAL },
            "Restore animation must settle cleanly"
        )
        val restoredSize = hostWin.frame.useContents { size.width to size.height }
        assertEquals(800.0, restoredSize.first, 2.0)
        assertEquals(450.0, restoredSize.second, 2.0)
        val restoredAspect = hostWin.contentAspectRatio.useContents {
            if (height > 0.0) width / height else 0.0
        }
        assertTrue(abs(restoredAspect - (16.0 / 9.0)) < 0.02, "16:9 contentAspectRatio must be restored after animation")

        // 3. Rapid re-toggle mid-animation preserves preZoomFrame and preZoomAspectRatio.
        hostWin.toggleMaximizeOrRestore()
        MacosNativeWindowHost.pumpVisualWindowEvents(40L)
        hostWin.toggleMaximizeOrRestore()
        MacosNativeWindowHost.pumpVisualWindowEvents(40L)
        hostWin.toggleMaximizeOrRestore()
        assertTrue(
            pumpUntil(timeoutMs = 1500L) { !hostWin.isAnimatingMaximize && window.getWindowState() == WindowState.MAXIMIZED },
            "Rapid re-toggle ending maximized must settle"
        )
        hostWin.toggleMaximizeOrRestore()
        assertTrue(
            pumpUntil(timeoutMs = 1500L) { !hostWin.isAnimatingMaximize && window.getWindowState() == WindowState.NORMAL },
            "Final restore after rapid toggle must settle"
        )
        val finalSize = hostWin.frame.useContents { size.width to size.height }
        assertEquals(800.0, finalSize.first, 2.0)
        assertEquals(450.0, finalSize.second, 2.0)
        val finalAspect = hostWin.contentAspectRatio.useContents {
            if (height > 0.0) width / height else 0.0
        }
        assertTrue(abs(finalAspect - (16.0 / 9.0)) < 0.02, "Original 16:9 aspect ratio must survive rapid mid-animation toggle")

        window.close()
    }

    @Test
    fun badgeHonorsCssColorsAppliesKernWithoutTrailingAdvanceAndCentersTextOptically() = withInteractiveMacosHost { factory ->
        val font = MacosAppKitAppearance.resolveFont(null, defaultSizePt = 11.0, defaultBold = true)
        val styledWithKernAndLineHeight = ComputedStyle(
            fontSizePx = 11f,
            lineHeight = CssLength.px(1f),
            letterSpacing = CssLength.px(1.5f)
        )

        // applyLineHeight = false must not clamp paragraph line height to 1px, and NSKernAttributeName
        // must only cover 0 until (length - 1) so the final character has zero trailing advance.
        val attrStr = MacosAppKitAppearance.buildAttributedString(
            text = "5.1",
            style = styledWithKernAndLineHeight,
            textColor = RgbaColor.WHITE,
            defaultFont = font,
            applyLineHeight = false
        )
        val pStyle = attrStr.attributesAtIndex(0uL, effectiveRange = null)[NSParagraphStyleAttributeName] as? NSParagraphStyle
        assertNotNull(pStyle)
        assertEquals(0.0, pStyle.minimumLineHeight, 1e-6)
        assertEquals(0.0, pStyle.maximumLineHeight, 1e-6)

        val kernFirst = attrStr.attributesAtIndex(0uL, effectiveRange = null)[NSKernAttributeName] as? NSNumber
        val kernLast = attrStr.attributesAtIndex(2uL, effectiveRange = null)[NSKernAttributeName] as? NSNumber
        assertNotNull(kernFirst, "Leading characters must receive NSKernAttributeName")
        assertEquals(1.5, kernFirst.doubleValue, 1e-4)
        assertNull(kernLast, "Trailing character must not receive NSKernAttributeName")

        // Single-character string must never carry NSKernAttributeName.
        val singleAttr = MacosAppKitAppearance.buildAttributedString(
            text = "5",
            style = styledWithKernAndLineHeight,
            textColor = RgbaColor.WHITE,
            defaultFont = font,
            applyLineHeight = false
        )
        assertNull(singleAttr.attributesAtIndex(0uL, effectiveRange = null)[NSKernAttributeName])

        // Live Badge("5.1") honoring explicit CSS background, border, textColor, and optical cap-height centering.
        val badgeBg = RgbaColor(28, 32, 42, 230)
        val badgeBorder = RgbaColor(255, 255, 255, 160)
        val badge = Badge("5.1", type = BootstrapType.SECONDARY, pill = true).apply {
            style.setWidth(CssLength.px(44f))
            style.setHeight(CssLength.px(22f))
        }
        val host = Container().apply { addChild(badge) }
        val window = Window(title = "Badge Visuals", initialSize = Size(160, 80)).apply {
            setContent(host)
        }
        window.show(factory)
        MacosNativeWindowHost.pumpVisualWindowEvents(48L)

        val badgeImpl = assertNotNull(badge.ensureNativeImpl() as? MacosTextNativeImpl)
        badgeImpl.applyComputedStyle(
            ComputedStyle(
                backgroundColor = badgeBg,
                borderColor = badgeBorder,
                borderWidthPx = 1f,
                borderStyle = "solid",
                borderRadiusPx = 11f,
                textColor = RgbaColor.WHITE,
                fontSizePx = 11f,
                fontWeightNumeric = 700,
                lineHeight = CssLength.px(1f),
                letterSpacing = CssLength.px(0.5f)
            )
        )
        MacosNativeWindowHost.pumpVisualWindowEvents(48L)

        val badgeView = assertNotNull((badgeImpl.nativeWidget as? MacosViewHandle)?.nsView as? MacosBadgeView)
        val activeSpec = badgeView.surfaceSupport.activeSurfaceSpec
        assertEquals(badgeBg, activeSpec.backgroundColor, "Badge must honor explicit CSS backgroundColor")
        assertEquals(badgeBorder, activeSpec.borderColor, "Badge must honor explicit CSS borderColor")
        assertEquals(1f, activeSpec.borderWidthPx, "Badge must honor explicit CSS borderWidthPx")
        assertEquals(CssBorderStyle.SOLID, activeSpec.borderStyle, "Badge must honor explicit CSS borderStyle")
        assertEquals(RgbaColor.WHITE, activeSpec.textColor, "Badge must honor explicit CSS textColor")

        val shot = assertNotNull(
            MacosNativeWindowHost.captureWindowScreenshot(
                window.ensureNativeImpl() as MacosWindowNativeImpl,
                160,
                80
            )
        )
        val b = badge.getBounds()
        var topBandWhite = 0
        var centerBandWhite = 0
        var bottomBandWhite = 0
        for (py in b.y until (b.y + b.height)) {
            for (px in (b.x + 6) until (b.x + b.width - 6)) {
                val off = (py * shot.width + px) * 4
                val r = shot.bytes[off].toInt() and 0xFF
                val g = shot.bytes[off + 1].toInt() and 0xFF
                val bl = shot.bytes[off + 2].toInt() and 0xFF
                if (r > 210 && g > 210 && bl > 210) {
                    val relY = py - b.y
                    when {
                        relY < 4 -> topBandWhite++
                        relY in 6..15 -> centerBandWhite++
                        relY > b.height - 4 -> bottomBandWhite++
                    }
                }
            }
        }
        assertTrue(centerBandWhite > 0, "5.1 glyph pixels must appear centered in the badge middle band")
        assertTrue(centerBandWhite > topBandWhite && centerBandWhite > bottomBandWhite, "5.1 glyphs must be centered rather than pinned to top/bottom")

        window.close()
    }

    @Test
    fun undecoratedEightEdgeResizeHitTestAspectMathAndCursorLockWork() = withInteractiveMacosHost { factory ->
        // 1. Edge & corner band detection math (8pt edges, 14pt corners, 2pt over controls).
        assertEquals(
            MacosNativeWindowHost.UndecoratedResizeEdge.W,
            MacosNativeWindowHost.detectUndecoratedResizeEdge(6.0, 200.0, 800.0, 450.0, isWindowControlHit = false)
        )
        assertEquals(
            MacosNativeWindowHost.UndecoratedResizeEdge.E,
            MacosNativeWindowHost.detectUndecoratedResizeEdge(794.0, 200.0, 800.0, 450.0, isWindowControlHit = false)
        )
        assertEquals(
            MacosNativeWindowHost.UndecoratedResizeEdge.NW,
            MacosNativeWindowHost.detectUndecoratedResizeEdge(12.0, 4.0, 800.0, 450.0, isWindowControlHit = false)
        )
        assertEquals(
            MacosNativeWindowHost.UndecoratedResizeEdge.SE,
            MacosNativeWindowHost.detectUndecoratedResizeEdge(788.0, 445.0, 800.0, 450.0, isWindowControlHit = false)
        )
        assertEquals(
            MacosNativeWindowHost.UndecoratedResizeEdge.NONE,
            MacosNativeWindowHost.detectUndecoratedResizeEdge(5.0, 5.0, 800.0, 450.0, isWindowControlHit = true)
        )
        assertEquals(
            MacosNativeWindowHost.UndecoratedResizeEdge.NW,
            MacosNativeWindowHost.detectUndecoratedResizeEdge(1.5, 1.5, 800.0, 450.0, isWindowControlHit = true)
        )

        // 2. All 8 edges under 16:9 aspect ratio preserve ratio and anchor opposite edge/corner.
        val start = NSMakeRect(100.0, 100.0, 800.0, 450.0)
        val ratio = 16.0 / 9.0
        val nFrame = MacosNativeWindowHost.computeResizedWindowFrame(
            start, MacosNativeWindowHost.UndecoratedResizeEdge.N, deltaScreenX = 0.0, deltaScreenY = 90.0, aspectRatio = ratio
        )
        nFrame.useContents {
            assertEquals(960.0, size.width, 0.5)
            assertEquals(540.0, size.height, 0.5)
            assertEquals(100.0, origin.y, 0.5)
            assertEquals(20.0, origin.x, 0.5)
        }
        val sFrame = MacosNativeWindowHost.computeResizedWindowFrame(
            start, MacosNativeWindowHost.UndecoratedResizeEdge.S, deltaScreenX = 0.0, deltaScreenY = -90.0, aspectRatio = ratio
        )
        sFrame.useContents {
            assertEquals(960.0, size.width, 0.5)
            assertEquals(540.0, size.height, 0.5)
            assertEquals(550.0, origin.y + size.height, 0.5)
        }
        val eFrame = MacosNativeWindowHost.computeResizedWindowFrame(
            start, MacosNativeWindowHost.UndecoratedResizeEdge.E, deltaScreenX = 160.0, deltaScreenY = 0.0, aspectRatio = ratio
        )
        eFrame.useContents {
            assertEquals(960.0, size.width, 0.5)
            assertEquals(540.0, size.height, 0.5)
            assertEquals(100.0, origin.x, 0.5)
            assertEquals(550.0, origin.y + size.height, 0.5)
        }
        val wFrame = MacosNativeWindowHost.computeResizedWindowFrame(
            start, MacosNativeWindowHost.UndecoratedResizeEdge.W, deltaScreenX = -160.0, deltaScreenY = 0.0, aspectRatio = ratio
        )
        wFrame.useContents {
            assertEquals(960.0, size.width, 0.5)
            assertEquals(540.0, size.height, 0.5)
            assertEquals(900.0, origin.x + size.width, 0.5)
            assertEquals(550.0, origin.y + size.height, 0.5)
        }
        for (cornerEdge in listOf(
            MacosNativeWindowHost.UndecoratedResizeEdge.NE,
            MacosNativeWindowHost.UndecoratedResizeEdge.NW,
            MacosNativeWindowHost.UndecoratedResizeEdge.SE,
            MacosNativeWindowHost.UndecoratedResizeEdge.SW
        )) {
            val dx = if (cornerEdge.right) 160.0 else -160.0
            val dy = if (cornerEdge.top) 45.0 else -45.0
            val cFrame = MacosNativeWindowHost.computeResizedWindowFrame(
                start, cornerEdge, deltaScreenX = dx, deltaScreenY = dy, aspectRatio = ratio
            )
            cFrame.useContents {
                assertEquals(size.width / size.height, ratio, 0.01, "Corner $cornerEdge must preserve aspect ratio")
                if (cornerEdge.left) assertEquals(900.0, origin.x + size.width, 0.5) else assertEquals(100.0, origin.x, 0.5)
                if (cornerEdge.bottom) assertEquals(550.0, origin.y + size.height, 0.5) else assertEquals(100.0, origin.y, 0.5)
            }
        }

        // 3. Full-bleed subviews & titlebar yield edge hitTest and preserve locked cursor during resize.
        val titleBar = StyledContainer().apply {
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_WINDOW_TITLE_BAR)
            setAttribute("data-titlebar-active", "true")
            style.setWidth(CssLength.percent(100f))
            style.setHeight(CssLength.px(44f))
            style.setCursor(CssCursor.POINTER)
        }
        val body = StyledContainer().apply {
            style.setWidth(CssLength.percent(100f))
            style.setHeight(CssLength.percent(100f))
            style.setCursor(CssCursor.POINTER)
        }
        val window = Window(title = "Edge HitTest & Cursor Lock", initialSize = Size(640, 360)).apply {
            setUndecorated(true)
            setContent(Container().apply {
                addChild(titleBar)
                addChild(body)
            })
        }
        window.show(factory)
        MacosNativeWindowHost.pumpVisualWindowEvents(48L)

        val hostWin = assertNotNull(MacosNativeWindowHost.activeNsWindow as? MacosHostWindow)
        val rootView = assertNotNull(hostWin.contentView as? MacosWindowRootView)
        val delegate = assertNotNull(hostWin.delegate as? MacosWindowDelegate)
        val bodyView = assertNotNull((body.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView)

        // Root view captures edge/corner hits directly over full-bleed subviews.
        assertEquals(rootView, rootView.hitTestClientPoint(4.0, 4.0))
        assertEquals(rootView, rootView.hitTestClientPoint(320.0, 4.0))
        assertEquals(rootView, rootView.hitTestClientPoint(636.0, 180.0))

        // Edge mouseDown locks activeCustomResizeEdge and lockedResizeCursor.
        val winLocEdge = rootView.convertClientPointToWindow(636.0, 180.0)
        val downEv = assertNotNull(
            NSEvent.mouseEventWithType(
                type = NSEventTypeLeftMouseDown,
                location = winLocEdge,
                modifierFlags = 0uL,
                timestamp = 0.0,
                windowNumber = hostWin.windowNumber,
                context = null,
                eventNumber = 0L,
                clickCount = 1L,
                pressure = 1f
            )
        )
        rootView.mouseDown(downEv)
        val expectedEastCursor = MacosNativeWindowHost.resolveCursorForResizeEdge(MacosNativeWindowHost.UndecoratedResizeEdge.E).second
        assertEquals(MacosNativeWindowHost.UndecoratedResizeEdge.E, rootView.activeCustomResizeEdge)
        assertEquals(expectedEastCursor, rootView.lockedResizeCursor)

        // Subview cursorUpdate and root mouseMoved over POINTER body must NOT overwrite lockedResizeCursor.
        val innerMoveEv = assertNotNull(
            NSEvent.mouseEventWithType(
                type = NSEventTypeMouseMoved,
                location = rootView.convertClientPointToWindow(320.0, 200.0),
                modifierFlags = 0uL,
                timestamp = 1.0,
                windowNumber = hostWin.windowNumber,
                context = null,
                eventNumber = 0L,
                clickCount = 0L,
                pressure = 0f
            )
        )
        bodyView.cursorUpdate(innerMoveEv)
        assertEquals(expectedEastCursor, NSCursor.currentCursor)
        rootView.mouseMoved(innerMoveEv)
        assertEquals(expectedEastCursor, NSCursor.currentCursor)

        val upEv = assertNotNull(
            NSEvent.mouseEventWithType(
                type = NSEventTypeLeftMouseUp,
                location = winLocEdge,
                modifierFlags = 0uL,
                timestamp = 2.0,
                windowNumber = hostWin.windowNumber,
                context = null,
                eventNumber = 0L,
                clickCount = 1L,
                pressure = 0f
            )
        )
        rootView.mouseUp(upEv)
        assertEquals(MacosNativeWindowHost.UndecoratedResizeEdge.NONE, rootView.activeCustomResizeEdge)
        assertNull(rootView.lockedResizeCursor)

        // Native live-resize delegate hooks toggle isInNativeLiveResize cleanly.
        val notif = NSNotification(name = "LiveResize", `object` = hostWin, userInfo = null)
        delegate.windowWillStartLiveResize(notif)
        assertTrue(rootView.isInNativeLiveResize)
        delegate.windowDidEndLiveResize(notif)
        assertFalse(rootView.isInNativeLiveResize)

        window.close()
    }

    @Test
    fun dialogWindowCancelButtonClosesNativePanelAndClearsParentAndSession() = withInteractiveMacosHost { factory ->
        val ownerWindow = Window(title = "Owner", initialSize = Size(640, 400)).apply {
            setContent(Container())
        }
        ownerWindow.show(factory)

        val cancelBtn = Button("Cancel")
        val dialog = DialogWindow(title = "Settings").apply {
            setWindowSize(Size(420, 260))
            setContent(
                HStack(gapPx = 8).apply {
                    addChild(cancelBtn)
                }
            )
        }
        cancelBtn.onClick = {
            dialog.close()
        }

        dialog.open(ownerWindow)
        MacosNativeWindowHost.pumpVisualWindowEvents(48L)

        val dialogImpl = dialog.ensureNativeImpl() as MacosDialogWindowNativeImpl
        val panel = assertNotNull(MacosNativeWindowHost.dialogSessionNsWindow(dialogImpl))
        assertTrue(panel.isVisible(), "Dialog NSPanel must be visible after open()")
        assertTrue(dialog.isWindowShown)
        assertNotNull(panel.parentWindow, "Owned dialog NSPanel must attach to owner NSWindow")

        val btnView = assertNotNull((cancelBtn.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView as? NSButton)
        btnView.performClick(null)

        assertTrue(
            pumpUntil(timeoutMs = 1500L) {
                !panel.isVisible() && !dialog.isWindowShown && MacosNativeWindowHost.dialogSessionNsWindow(dialogImpl) == null
            },
            "Clicking Cancel must close the native NSPanel and clear dialog session"
        )
        assertFalse(panel.isVisible())
        assertFalse(dialog.isWindowShown)
        assertFalse(dialog.isVisible)
        assertNull(MacosNativeWindowHost.dialogSessionNsWindow(dialogImpl))
        assertNull(panel.parentWindow)
        assertNull((dialogImpl.nativeWidget as MacosViewHandle).nsWindow)
        assertNull((dialogImpl.nativeWidget as MacosViewHandle).nsView)

        ownerWindow.close()
    }

    @Test
    fun topLeftDesktopCoordinatesRoundTripAndFullscreenDoesNotShiftWindowDownward() = withInteractiveMacosHost { factory ->
        // Pure coordinate conversion round-trip anchored to primary screen top.
        val cocoaRect = MacosNativeWindowHost.desktopTopLeftRectToCocoaRect(120, 80, 800.0, 450.0)
        val roundTrip = MacosNativeWindowHost.cocoaFrameToDesktopTopLeft(cocoaRect)
        assertEquals(120, roundTrip.first)
        assertEquals(80, roundTrip.second)

        val window = Window(title = "TopLeft RoundTrip", initialSize = Size(640, 360)).apply {
            setUndecorated(true)
            setWindowPosition(140, 90)
            setContent(Container())
        }
        window.show(factory)
        MacosNativeWindowHost.pumpVisualWindowEvents(48L)

        val hostWin = assertNotNull(MacosNativeWindowHost.activeNsWindow as? MacosHostWindow)
        val winImpl = assertNotNull(window.ensureNativeImpl() as? MacosWindowNativeImpl)
        val delegate = assertNotNull(hostWin.delegate as? MacosWindowDelegate)
        val initialPos = MacosNativeWindowHost.cocoaFrameToDesktopTopLeft(hostWin.frame)
        assertEquals(140, initialPos.first)
        assertEquals(90, initialPos.second)
        assertEquals(140, winImpl.getWindowPosition().x.toInt())
        assertEquals(90, winImpl.getWindowPosition().y.toInt())

        // Simulated windowDidMove during fullscreen transition must NOT clobber saved top-left position.
        hostWin.markFullscreenTransitionWillChange()
        hostWin.setFrameTopLeftPoint(MacosNativeWindowHost.desktopTopLeftToCocoaTopLeft(0, 0))
        val notif = NSNotification(name = "DidMove", `object` = hostWin, userInfo = null)
        delegate.windowDidMove(notif)
        assertEquals(90, winImpl.getWindowPosition().y.toInt())
        hostWin.onFullscreenTransitionFinished(succeeded = false, inFullscreenNow = false)

        // Restore explicit position, then exercise real fullscreen enter & exit if available.
        window.setWindowPosition(140, 90)
        hostWin.toggleFullScreen(null)
        assertTrue(pumpUntil(timeoutMs = 2500L) { !hostWin.isTransitioningFullscreen })
        if ((hostWin.styleMask and NSWindowStyleMaskFullScreen) != 0UL) {
            hostWin.toggleFullScreen(null)
            assertTrue(
                pumpUntil(timeoutMs = 2500L) {
                    !hostWin.isTransitioningFullscreen && (hostWin.styleMask and NSWindowStyleMaskFullScreen) == 0UL
                }
            )
            MacosNativeWindowHost.pumpVisualWindowEvents(48L)
        }

        val afterPos = MacosNativeWindowHost.cocoaFrameToDesktopTopLeft(hostWin.frame)
        assertTrue(abs(afterPos.first - 140) <= 2, "X position must remain 140 after fullscreen exit, got ${afterPos.first}")
        assertTrue(abs(afterPos.second - 90) <= 2, "Y position must remain 90 (zero downward jump), got ${afterPos.second}")

        window.close()
    }

    @Test
    fun translucentOverlayTitlebarScalesVisualEffectAlphaWhileOpaqueStaysUnchanged() = withInteractiveMacosHost { factory ->
        val blurFilter = CssFilter(blur = CssLength.px(18f))
        val translucentTitleBar = StyledContainer().apply {
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_WINDOW_TITLE_BAR)
            style.setWidth(CssLength.px(240f))
            style.setHeight(CssLength.px(32f))
            backgroundColor = RgbaColor(20, 20, 24, 94)
            backdropFilter = blurFilter
        }
        val higherOpacityTitleBar = StyledContainer().apply {
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_WINDOW_TITLE_BAR)
            style.setWidth(CssLength.px(240f))
            style.setHeight(CssLength.px(32f))
            backgroundColor = RgbaColor(16, 18, 24, 173)
            backdropFilter = blurFilter
        }
        val uncoloredTitleBar = StyledContainer().apply {
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_WINDOW_TITLE_BAR)
            style.setWidth(CssLength.px(240f))
            style.setHeight(CssLength.px(32f))
            backdropFilter = blurFilter
        }
        val window = Window(title = "Vibrancy Alpha", initialSize = Size(320, 140)).apply {
            setContent(Container().apply {
                addChild(translucentTitleBar)
                addChild(higherOpacityTitleBar)
                addChild(uncoloredTitleBar)
            })
        }
        window.show(factory)
        MacosNativeWindowHost.syncThemeAppearance(ThemeMode.DARK)
        MacosNativeWindowHost.pumpVisualWindowEvents(48L)

        val transView = assertNotNull((translucentTitleBar.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView as? MacosStyledContainerView)
        val higherView = assertNotNull((higherOpacityTitleBar.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView as? MacosStyledContainerView)
        val uncoloredView = assertNotNull((uncoloredTitleBar.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView as? MacosStyledContainerView)

        assertTrue(transView.subviews.none { it is NSVisualEffectView })
        assertTrue(higherView.subviews.none { it is NSVisualEffectView })
        assertTrue(uncoloredView.subviews.none { it is NSVisualEffectView })

        assertNotNull(transView.surfaceSupport.backdropUnderlayView)
        assertNotNull(higherView.surfaceSupport.backdropUnderlayView)
        assertNotNull(uncoloredView.surfaceSupport.backdropUnderlayView)

        assertNotNull(transView.surfaceSupport.tintOverlayView)
        assertNotNull(higherView.surfaceSupport.tintOverlayView)
        assertNotNull(uncoloredView.surfaceSupport.tintOverlayView)

        assertEquals(RgbaColor(20, 20, 24, 94), transView.surfaceSupport.activeSurfaceSpec.backgroundColor)
        assertEquals(RgbaColor(16, 18, 24, 173), higherView.surfaceSupport.activeSurfaceSpec.backgroundColor)
        assertNull(uncoloredView.surfaceSupport.activeSurfaceSpec.backgroundColor)

        window.close()
    }

    @Test
    fun presentationRectComputesContainPillarboxLetterboxCoverFillAndLegacyDefaults() {
        // 16:9 in 16:10 (1512x945) under CONTAIN -> top/bottom letterbox bars
        val letterbox = DesktopInteractiveSurfaceSpec.computePresentationRect(
            containerW = 1512.0,
            containerH = 945.0,
            srcW = 1920,
            srcH = 1080,
            aspectRatio = 16f / 9f,
            fitMode = ObjectFit.CONTAIN
        )
        assertEquals(0.0, letterbox.dstX, 1e-4)
        assertEquals(47.25, letterbox.dstY, 0.05)
        assertEquals(1512.0, letterbox.dstW, 1e-4)
        assertEquals(850.5, letterbox.dstH, 0.05)
        assertEquals(0, letterbox.srcX)
        assertEquals(0, letterbox.srcY)
        assertEquals(1920, letterbox.srcW)
        assertEquals(1080, letterbox.srcH)
        assertEquals(0, letterbox.clippedDstX)
        assertEquals(47, letterbox.clippedDstY)
        assertEquals(1512, letterbox.clippedDstW)
        assertEquals(851, letterbox.clippedDstH)

        // 16:9 in 21:9 (2560x1080) under CONTAIN -> left/right pillarbox bars
        val pillarbox = DesktopInteractiveSurfaceSpec.computePresentationRect(
            containerW = 2560.0,
            containerH = 1080.0,
            srcW = 1920,
            srcH = 1080,
            aspectRatio = 16f / 9f,
            fitMode = ObjectFit.CONTAIN
        )
        assertEquals(320.0, pillarbox.dstX, 0.05)
        assertEquals(0.0, pillarbox.dstY, 1e-4)
        assertEquals(1920.0, pillarbox.dstW, 0.05)
        assertEquals(1080.0, pillarbox.dstH, 1e-4)
        assertEquals(320, pillarbox.clippedDstX)
        assertEquals(0, pillarbox.clippedDstY)
        assertEquals(1920, pillarbox.clippedDstW)
        assertEquals(1080, pillarbox.clippedDstH)

        // 4:3 contentAspectRatio override on 1920x1080 buffer in 1512x945 under CONTAIN -> pillarbox
        val anamorphicSpec = DesktopInteractiveSurfaceSpec(
            surfaceWidth = { 1920 },
            surfaceHeight = { 1080 },
            swapCount = { 1L },
            contentFitMode = { ObjectFit.CONTAIN },
            contentAspectRatio = { 4f / 3f }
        )
        val anamorphic = DesktopInteractiveSurfaceSpec.computePresentationRect(
            containerW = 1512.0,
            containerH = 945.0,
            srcW = 1920,
            srcH = 1080,
            spec = anamorphicSpec
        )
        assertEquals(126.0, anamorphic.dstX, 0.05)
        assertEquals(0.0, anamorphic.dstY, 1e-4)
        assertEquals(1260.0, anamorphic.dstW, 0.05)
        assertEquals(945.0, anamorphic.dstH, 1e-4)

        // COVER overflow in 1512x945 for 16:9 -> horizontal overflow + cropped srcX/srcW
        val cover = DesktopInteractiveSurfaceSpec.computePresentationRect(
            containerW = 1512.0,
            containerH = 945.0,
            srcW = 1920,
            srcH = 1080,
            aspectRatio = 16f / 9f,
            fitMode = ObjectFit.COVER
        )
        assertEquals(-84.0, cover.dstX, 0.05)
        assertEquals(0.0, cover.dstY, 1e-4)
        assertEquals(1680.0, cover.dstW, 0.05)
        assertEquals(945.0, cover.dstH, 1e-4)
        assertEquals(0, cover.clippedDstX)
        assertEquals(0, cover.clippedDstY)
        assertEquals(1512, cover.clippedDstW)
        assertEquals(945, cover.clippedDstH)
        assertEquals(96, cover.srcX)
        assertEquals(0, cover.srcY)
        assertEquals(1728, cover.srcW)
        assertEquals(1080, cover.srcH)

        // FILL stretches to full bounds
        val fill = DesktopInteractiveSurfaceSpec.computePresentationRect(
            containerW = 1512.0,
            containerH = 945.0,
            srcW = 1920,
            srcH = 1080,
            aspectRatio = 16f / 9f,
            fitMode = ObjectFit.FILL
        )
        assertEquals(0.0, fill.dstX, 1e-4)
        assertEquals(0.0, fill.dstY, 1e-4)
        assertEquals(1512.0, fill.dstW, 1e-4)
        assertEquals(945.0, fill.dstH, 1e-4)
        assertEquals(0, fill.srcX)
        assertEquals(0, fill.srcY)
        assertEquals(1920, fill.srcW)
        assertEquals(1080, fill.srcH)

        // Legacy Canvas3D spec (preferHardwareStretchBlit = true, defaultAspectRatio = null) -> FILL
        val canvas3dSpec = DesktopInteractiveSurfaceSpec(
            surfaceWidth = { 800 },
            surfaceHeight = { 600 },
            swapCount = { 1L },
            preferHardwareStretchBlit = true,
            defaultAspectRatio = null
        )
        assertEquals(ObjectFit.FILL, canvas3dSpec.resolveContentFitMode())
        assertFalse(canvas3dSpec.shouldPaintBlackBackdrop())

        // Legacy WindowsOverlayFlickerTest spec (preferHardwareStretchBlit = true, defaultAspectRatio = 16/9) -> CONTAIN
        val legacyVideoSpec = DesktopInteractiveSurfaceSpec(
            surfaceWidth = { 1920 },
            surfaceHeight = { 1080 },
            swapCount = { 1L },
            preferHardwareStretchBlit = true,
            suppressDefaultBorder = true,
            defaultAspectRatio = 16f / 9f
        )
        assertEquals(ObjectFit.CONTAIN, legacyVideoSpec.resolveContentFitMode())
        assertTrue(legacyVideoSpec.shouldPaintBlackBackdrop())
    }

    @Test
    fun macosInteractiveSurfacePaintsLetterboxPillarboxAndUpdatesFitModeWhilePaused() = withInteractiveMacosHost { factory ->
        val srcW = 320
        val srcH = 180
        // Solid bright blue in BGRA (B=255, G=140, R=20, A=255)
        val frameBgra = ByteArray(srcW * srcH * 4)
        for (i in 0 until (srcW * srcH)) {
            val off = i * 4
            frameBgra[off] = 255.toByte()
            frameBgra[off + 1] = 140.toByte()
            frameBgra[off + 2] = 20.toByte()
            frameBgra[off + 3] = 255.toByte()
        }

        var fitMode = ObjectFit.CONTAIN
        var aspectOverride: Float? = 16f / 9f
        val swap = 1L

        val stageContainer = Container().apply {
            getStyle().apply {
                setWidth(CssLength.PERCENT_100)
                setHeight(CssLength.PERCENT_100)
            }
        }
        val window = Window(title = "Video Presentation", initialSize = Size(800, 500)).apply {
            setUndecorated(true)
            setContent(stageContainer)
        }
        window.show(factory)
        try {
            MacosNativeWindowHost.pumpVisualWindowEvents(32L)

            val winImpl = window.ensureNativeImpl() as MacosWindowNativeImpl
            val stageNative = stageContainer.ensureNativeImpl()!!.nativeWidget as DesktopNativeWidget
            DesktopInteractiveSurfaceSpec(
                surfaceWidth = { srcW },
                surfaceHeight = { srcH },
                swapCount = { swap },
                captureBgra = { frameBgra },
                suppressDefaultBorder = true,
                contentFitMode = { fitMode },
                contentAspectRatio = { aspectOverride }
            ).attachTo(stageNative)

            val shotContain = assertNotNull(MacosNativeWindowHost.captureWindowScreenshot(winImpl, 800, 500))
        val surfaceView = assertNotNull(
            (stageNative as MacosViewHandle).nsView
                ?.subviews
                ?.filterIsInstance<MacosInteractiveSurfaceView>()
                ?.firstOrNull(),
            "Expected retained MacosInteractiveSurfaceView mounted on stage container"
        )

        surfaceView.currentVideoContentLayerFrame().useContents {
            assertEquals(0.0, origin.x, 0.1)
            assertEquals(25.0, origin.y, 0.1)
            assertEquals(800.0, size.width, 0.1)
            assertEquals(450.0, size.height, 0.1)
        }

        fun pixelRgb(bytes: ByteArray, width: Int, x: Int, y: Int): Triple<Int, Int, Int> {
            val off = (y * width + x) * 4
            return Triple(
                bytes[off].toInt() and 0xFF,
                bytes[off + 1].toInt() and 0xFF,
                bytes[off + 2].toInt() and 0xFF
            )
        }

        val topBar = pixelRgb(shotContain.bytes, shotContain.width, 400, 5)
        val bottomBar = pixelRgb(shotContain.bytes, shotContain.width, 400, 495)
        val centerContain = pixelRgb(shotContain.bytes, shotContain.width, 400, 250)
        assertEquals(Triple(0, 0, 0), topBar, "Top letterbox bar must be pure black")
        assertEquals(Triple(0, 0, 0), bottomBar, "Bottom letterbox bar must be pure black")
        assertTrue(centerContain.first in 0..40 && centerContain.second in 120..160 && centerContain.third >= 235,
            "Center pixel must match rendered blue frame, got $centerContain")

        // Resize to ultrawide 1000x400 -> left/right pillarbox black bars
        window.setWindowSize(Size(1000, 400))
        MacosNativeWindowHost.pumpVisualWindowEvents(48L)
        val shotPillar = assertNotNull(MacosNativeWindowHost.captureWindowScreenshot(winImpl, 1000, 400))
        surfaceView.currentVideoContentLayerFrame().useContents {
            assertEquals(144.44, origin.x, 0.2)
            assertEquals(0.0, origin.y, 0.1)
            assertEquals(711.11, size.width, 0.2)
            assertEquals(400.0, size.height, 0.1)
        }
        val leftBar = pixelRgb(shotPillar.bytes, shotPillar.width, 5, 200)
        val rightBar = pixelRgb(shotPillar.bytes, shotPillar.width, 995, 200)
        val centerPillar = pixelRgb(shotPillar.bytes, shotPillar.width, 500, 200)
        assertEquals(Triple(0, 0, 0), leftBar, "Left pillarbox bar must be pure black")
        assertEquals(Triple(0, 0, 0), rightBar, "Right pillarbox bar must be pure black")
        assertTrue(centerPillar.first in 0..40 && centerPillar.second in 120..160 && centerPillar.third >= 235,
            "Center pixel must match rendered blue frame in ultrawide window, got $centerPillar")

        // Toggle fitMode to COVER and FILL while paused (swapCount unchanged) and verify immediate layer frame updates
        fitMode = ObjectFit.COVER
        assertFalse(surfaceView.presentLatestFrame(forceRefresh = false))
        surfaceView.currentVideoContentLayerFrame().useContents {
            assertEquals(0.0, origin.x, 0.1)
            assertEquals(-81.25, origin.y, 0.2)
            assertEquals(1000.0, size.width, 0.1)
            assertEquals(562.5, size.height, 0.2)
        }
        val shotCover = assertNotNull(MacosNativeWindowHost.captureWindowScreenshot(winImpl, 1000, 400))
        val coverCorner = pixelRgb(shotCover.bytes, shotCover.width, 5, 5)
        assertTrue(coverCorner.first in 0..40 && coverCorner.second in 120..160 && coverCorner.third >= 235,
            "COVER mode must fill ultrawide viewport corners, got $coverCorner")

        fitMode = ObjectFit.FILL
        assertFalse(surfaceView.presentLatestFrame(forceRefresh = false))
        surfaceView.currentVideoContentLayerFrame().useContents {
            assertEquals(0.0, origin.x, 0.1)
            assertEquals(0.0, origin.y, 0.1)
            assertEquals(1000.0, size.width, 0.1)
            assertEquals(400.0, size.height, 0.1)
        }

        // Update contentAspectRatio while paused -> immediate CONTAIN re-layout
        fitMode = ObjectFit.CONTAIN
        aspectOverride = 4f / 3f
        assertFalse(surfaceView.presentLatestFrame(forceRefresh = false))
        surfaceView.currentVideoContentLayerFrame().useContents {
            assertEquals(233.33, origin.x, 0.2)
            assertEquals(0.0, origin.y, 0.1)
            assertEquals(533.33, size.width, 0.2)
            assertEquals(400.0, size.height, 0.1)
        }

        } finally {
            window.close()
        }
    }

    @Test
    fun macosInteractiveSurfaceUsesIoSurfaceWithoutPerFrameCgImageAndSkipsRedundantStageRedrawCapture() = withInteractiveMacosHost { factory ->
        val srcW = 64
        val srcH = 36
        val byteSize = srcW * srcH * 4
        val rawPtr = nativeHeap.allocArray<ByteVar>(byteSize)
        var swap = 0L
        var acquireCalls = 0

        val stageContainer = Container().apply {
            getStyle().apply {
                setWidth(CssLength.PERCENT_100)
                setHeight(CssLength.PERCENT_100)
            }
        }
        val window = Window(title = "IOSurface Video Path", initialSize = Size(320, 180)).apply {
            setUndecorated(true)
            setContent(stageContainer)
        }
        try {
            // Fill BGRA with 0 alpha to verify ignoreAlpha forces opaque alpha=0xFF in IOSurface
            for (i in 0 until (srcW * srcH)) {
                val off = i * 4
                rawPtr[off] = 240.toByte()     // B
                rawPtr[off + 1] = 100.toByte() // G
                rawPtr[off + 2] = 30.toByte()  // R
                rawPtr[off + 3] = 0.toByte()   // A
            }

            window.show(factory)
            MacosNativeWindowHost.pumpVisualWindowEvents(24L)

            val winImpl = window.ensureNativeImpl() as MacosWindowNativeImpl
            val stageNative = stageContainer.ensureNativeImpl()!!.nativeWidget as DesktopNativeWidget
            DesktopInteractiveSurfaceSpec(
                surfaceWidth = { srcW },
                surfaceHeight = { srcH },
                swapCount = { swap },
                acquireBgraPtr = {
                    if (swap <= 0L) {
                        Pair(null, 0)
                    } else {
                        acquireCalls++
                        rawPtr to byteSize
                    }
                },
                suppressDefaultBorder = true,
                contentFitMode = { ObjectFit.CONTAIN },
                contentAspectRatio = { 16f / 9f }
            ).attachTo(stageNative)
            DesktopNativeWidget.globalLayoutMutationVersion++
            MacosNativeWindowHost.invalidateAllWindows(stageOnly = false)
            MacosNativeWindowHost.forceSynchronousPaint(stageOnly = false)
            MacosNativeWindowHost.pumpVisualWindowEvents(24L)

            val surfaceView = assertNotNull(
                (stageNative as MacosViewHandle).nsView
                    ?.subviews
                    ?.filterIsInstance<MacosInteractiveSurfaceView>()
                    ?.firstOrNull(),
                "Expected retained MacosInteractiveSurfaceView mounted on stage container"
            )

            // Initial upload + advance across multiple swaps to exercise ring reuse
            swap = 1L
            assertTrue(surfaceView.presentLatestFrame(forceRefresh = false))
            assertTrue(surfaceView.activeSlotUsesIoSurface, "Live frame presentation must use IOSurface backing")
            assertEquals(0, surfaceView.cgImageBuildCount, "Live IOSurface upload must not build a CGImage")

            for (nextSwap in 2L..7L) {
                swap = nextSwap
                assertTrue(surfaceView.presentLatestFrame(forceRefresh = false))
            }
            assertEquals(7, acquireCalls)
            assertEquals(3, surfaceView.ringAllocationCount, "3-slot IOSurface ring must allocate at most 3 surfaces")
            assertTrue(surfaceView.ringSlotReuseCount >= 4, "3-slot IOSurface ring must reuse surfaces after warmup")
            assertEquals(0, surfaceView.cgImageBuildCount, "Presenting 7 frames via IOSurface must build 0 CGImages")

            // Steady-state calls at unchanged swapCount and bounds must skip both capture and CATransaction bounds sync
            val commitsBeforeSteady = surfaceView.layerBoundsSyncCommitCount
            repeat(5) {
                assertFalse(surfaceView.presentLatestFrame(forceRefresh = false))
            }
            assertEquals(7, acquireCalls, "Unchanged swapCount must skip duplicate acquireBgraPtr calls")
            assertEquals(commitsBeforeSteady, surfaceView.layerBoundsSyncCommitCount, "Unchanged geometry must skip layer bounds CATransaction")

            // requestStageRedraw() (triggered by HUD progress ticks) must NOT re-capture unchanged swapCount
            MacosNativeWindowHost.requestStageRedraw()
            MacosNativeWindowHost.pumpVisualWindowEvents(32L)
            assertEquals(7, acquireCalls, "Stage-only overlay redraws must not force re-capturing unchanged video frames")

            // Lazy CGImage snapshot path (used by screenshots) must render opaque blue pixels from the active IOSurface
            val lazyCg = assertNotNull(surfaceView.currentCgImage(), "Expected lazy CGImage generation from active IOSurface")
            assertEquals(srcW.toULong(), platform.CoreGraphics.CGImageGetWidth(lazyCg))
            assertEquals(1, surfaceView.cgImageBuildCount)

            val shot = assertNotNull(MacosNativeWindowHost.captureWindowScreenshot(winImpl, 320, 180))
            val off = (90 * shot.width + 160) * 4
            val r = shot.bytes[off].toInt() and 0xFF
            val g = shot.bytes[off + 1].toInt() and 0xFF
            val b = shot.bytes[off + 2].toInt() and 0xFF
            val a = shot.bytes[off + 3].toInt() and 0xFF
            assertTrue(r in 15..55 && g in 80..120 && b >= 220 && a == 255, "Screenshot from IOSurface must preserve RGB and opaque alpha, got ($r, $g, $b, $a)")
        } finally {
            window.close()
            nativeHeap.free(rawPtr)
        }
    }

    @Test
    fun overlayFadeOutWithFollowUpCursorClassNeverFlickersAlphaOrNativeTitlebarToZero() = withInteractiveMacosHost { factory ->
        val scheduler = object : DefaultAnimationNativeImpl() {}
        val prevScheduler = Animator.customScheduler
        SystemCssAnimationController.clear()
        Animator.customScheduler = scheduler
        BlinkStyleEngine.ensureInstalled()
        SystemCssAnimationController.ensureInstalled()

        val sheet = CssStyleSheet.parse(
            """
            @keyframes overlayFadeOut {
                0% { opacity: 1; }
                100% { opacity: 0; }
            }
            @keyframes overlayFadeIn {
                0% { opacity: 0; }
                100% { opacity: 1; }
            }
            .overlay-fading-root {
                opacity: 1;
                animation: overlayFadeIn 200ms ease-out forwards;
            }
            .overlay-fading-root.hidden {
                opacity: 0;
                pointer-events: none;
                animation: overlayFadeOut 200ms ease-in forwards;
            }
            .overlay-fading-root.hidden.hud-cursor-hidden {
                cursor: none;
            }
            """.trimIndent()
        )

        val titleBar = StyledContainer().apply {
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_WINDOW_TITLE_BAR)
            setAttribute("data-native-titlebar-controls", "true")
            setAttribute("data-titlebar-enabled", "true")
            setAttribute("data-titlebar-active", "true")
            style.setWidth(CssLength.px(480f))
            style.setHeight(CssLength.px(32f))
        }
        val overlayRoot = StyledContainer("overlay-fading-root").apply {
            addClass("overlay-fading-root")
            setAttribute("data-overlay-root", "true")
            addChild(titleBar)
        }
        val window = Window(title = "Overlay Fade Stability", initialSize = Size(480, 270)).apply {
            setUndecorated(true)
            setContent(Container().apply { addChild(overlayRoot) })
        }

        try {
            window.show(factory)
            MacosNativeWindowHost.pumpVisualWindowEvents(48L)

            val hostWin = assertNotNull(MacosNativeWindowHost.activeNsWindow as? MacosHostWindow)
            val overlayView = assertNotNull((overlayRoot.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView)
            val closeButton = assertNotNull(hostWin.standardWindowButton(NSWindowCloseButton))
            val titlebarContainer = assertNotNull(hostWin.titlebarContainerView())

            sheet.applyToTree(overlayRoot)
            Animator.advanceTimeBy(250.0)
            MacosNativeWindowHost.pumpVisualWindowEvents(32L)

            assertEquals(1.0, overlayView.alphaValue, 1e-3, "Overlay NSView must start visible at alpha=1.0")
            assertTrue(closeButton.alphaValue > 0.9 && !closeButton.isHidden(), "Native traffic light must be visible before fade-out")
            assertTrue(titlebarContainer.alphaValue > 0.9 && !titlebarContainer.isHidden(), "Native titlebar container must be visible before fade-out")

            // Trigger fade-out + immediate follow-up cursor-hidden class mutation at t=0
            overlayRoot.addClass("hidden")
            assertEquals(1f, overlayRoot.opacity, 0.001f)
            MacosNativeWindowHost.pumpVisualWindowEvents(24L)
            assertEquals(1.0, overlayView.alphaValue, 1e-3, "NSView alpha must stay 1.0 immediately on .hidden before advancing time")
            assertTrue(closeButton.alphaValue > 0.9 && !closeButton.isHidden(), "Traffic lights must stay visible at t=0 of overlayFadeOut")
            assertTrue(titlebarContainer.alphaValue > 0.9 && !titlebarContainer.isHidden(), "Titlebar container must stay visible at t=0 of overlayFadeOut")

            overlayRoot.addClass("hud-cursor-hidden")
            assertEquals(1f, overlayRoot.opacity, 0.001f)
            MacosNativeWindowHost.pumpVisualWindowEvents(24L)
            assertEquals(1.0, overlayView.alphaValue, 1e-3, "Adding .hud-cursor-hidden at t=0 must NOT drop NSView alpha to 0")
            assertTrue(closeButton.alphaValue > 0.9 && !closeButton.isHidden(), "Adding .hud-cursor-hidden at t=0 must NOT hide traffic lights")
            assertTrue(titlebarContainer.alphaValue > 0.9 && !titlebarContainer.isHidden(), "Adding .hud-cursor-hidden at t=0 must NOT hide titlebar container")

            // Advance mid-fade and toggle cursor class again
            Animator.advanceTimeBy(100.0)
            MacosNativeWindowHost.pumpVisualWindowEvents(24L)
            val midAlpha = overlayView.alphaValue
            assertTrue(midAlpha in 0.1..0.9, "Overlay NSView alpha must fade smoothly at midpoint, got $midAlpha")
            assertFalse(titlebarContainer.isHidden())
            assertEquals(midAlpha, titlebarContainer.alphaValue, 0.05)

            overlayRoot.removeClass("hud-cursor-hidden")
            MacosNativeWindowHost.pumpVisualWindowEvents(24L)
            assertEquals(midAlpha, overlayView.alphaValue, 0.02, "Mid-fade cursor class removal must preserve current NSView alpha")

            // Complete fade-out
            Animator.advanceTimeBy(150.0)
            MacosNativeWindowHost.pumpVisualWindowEvents(32L)
            assertEquals(0.0, overlayView.alphaValue, 1e-3, "Overlay NSView alpha must reach 0.0 when fade-out completes")
            assertTrue(titlebarContainer.isHidden() && titlebarContainer.alphaValue <= 0.01, "Native titlebar container must hide once fade-out completes")
            assertTrue(closeButton.isHiddenOrHasHiddenAncestor() || titlebarContainer.isHidden(), "Native traffic lights must hide once fade-out completes")
        } finally {
            window.close()
            SystemCssAnimationController.clear()
            Animator.cancelAllAnimations()
            Animator.customScheduler = prevScheduler
            scheduler.dispose()
        }
    }

    @Test
    fun ownedFragmentDialogRendersInOwnPanelWithoutStealingRootViewOrVBlankLag() = withInteractiveMacosHost { factory ->
        val frameBytes = ByteArray(320 * 180 * 4)
        val stageContainer = Container("stage-surface", "w-100 h-100")
        val ownerTitleBar = StyledContainer().apply {
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_WINDOW_TITLE_BAR)
            setAttribute("data-native-titlebar-controls", "true")
            setAttribute("data-titlebar-enabled", "true")
            setAttribute("data-titlebar-active", "true")
            style.setWidth(CssLength.px(960f))
            style.setHeight(CssLength.px(32f))
        }
        val ownerOverlayRoot = StyledContainer("owner-overlay").apply {
            setAttribute("data-overlay-root", "true")
            addChild(ownerTitleBar)
        }
        val ownerRoot = Container("owner-root", "w-100 h-100").apply {
            addChild(stageContainer)
            addChild(ownerOverlayRoot)
        }
        val ownerWindow = Window(title = "Owner Stage Window", initialSize = Size(960, 600)).apply {
            setUndecorated(true)
            setContent(ownerRoot)
        }

        val scrollSelect = Select(
            options = listOf(
                SelectOption("auto", "Automatic"),
                SelectOption("manual", "Manual")
            ),
            selectedValue = "auto"
        )
        val infoText = Text("Receiver configuration details")
        val scrollInner = VStack(gapPx = 8).apply {
            addClass("w-100")
            addChild(scrollSelect)
            addChild(infoText)
        }
        val scrollView = ScrollView().apply {
            addClass("w-100")
            addChild(scrollInner)
        }
        val cancelBtn = Button("Cancel")
        val saveBtn = Button("Save")
        val footer = HStack(gapPx = 8).apply {
            addClass("w-100")
            addChild(cancelBtn)
            addChild(saveBtn)
        }
        val fragmentRootStack = VStack(gapPx = 12).apply {
            addClass("w-100")
            addClass("h-100")
            addChild(scrollView, weight = 1f)
            addChild(footer)
        }
        val settingsFragment = Fragment().apply {
            addClass("w-100")
            addClass("h-100")
            addChild(fragmentRootStack)
        }
        val fragmentContainer = FragmentContainer().apply {
            addClass("w-100")
            addClass("h-100")
        }
        val dialog = DialogWindow(title = "Receiver Settings").apply {
            setWindowSize(Size(640, 560))
            setMinWindowSize(Size(480, 420))
            setContent(fragmentContainer)
        }
        fragmentContainer.setFragment(settingsFragment)

        fun containsViewRecursively(root: NSView, target: NSView): Boolean {
            if (root === target) return true
            for (sub in root.subviews) {
                val child = sub as? NSView ?: continue
                if (containsViewRecursively(child, target)) return true
            }
            return false
        }

        try {
            ownerWindow.show(factory)
            val stageNative = stageContainer.ensureNativeImpl()!!.nativeWidget as DesktopNativeWidget
            DesktopInteractiveSurfaceSpec(
                surfaceWidth = { 320 },
                surfaceHeight = { 180 },
                swapCount = { 1L },
                captureBgra = { frameBytes },
                suppressDefaultBorder = true
            ).attachTo(stageNative)
            DesktopNativeWidget.globalLayoutMutationVersion++
            MacosNativeWindowHost.invalidateAllWindows(stageOnly = false)
            MacosNativeWindowHost.pumpVisualWindowEvents(48L)

            dialog.open(ownerWindow)

            val ownerWinImpl = ownerWindow.ensureNativeImpl() as MacosWindowNativeImpl
            val dialogImpl = dialog.ensureNativeImpl() as MacosDialogWindowNativeImpl
            val ownerSession = assertNotNull(MacosNativeWindowHost.windowSessionForTest(ownerWinImpl))
            val dialogSession = assertNotNull(MacosNativeWindowHost.dialogSessionForTest(dialogImpl))
            val panel = assertNotNull(dialogSession.nsWindow as? NSPanel)
            val ownerNsWin = ownerSession.nsWindow

            fun verifyDialogPanelHierarchyAndBounds(phase: String) {
                assertTrue(
                    ownerWinImpl.rawChildImplsSnapshot().none { it === dialogImpl },
                    "$phase: owner rawChildImpls must not contain owned dialogImpl"
                )
                assertTrue(
                    panel.contentView == dialogSession.rootView,
                    "$phase: panel.contentView must be dialogSession.rootView, was ${panel.contentView}"
                )
                assertTrue(
                    dialogSession.rootView.window == panel,
                    "$phase: dialogSession.rootView.window must be panel ($panel), was ${dialogSession.rootView.window}"
                )
                assertEquals(0, dialogSession.titleBarInsetTopPx, "$phase: dialogSession.titleBarInsetTopPx must be 0")
                assertTrue(dialogSession.rootView.subviews.isNotEmpty(), "$phase: dialogSession.rootView must have realized subviews")

                val scrollNsView = assertNotNull((scrollView.ensureNativeImpl()?.nativeWidget as? MacosViewHandle)?.nsView as? NSScrollView)
                val selectNsView = assertNotNull((scrollSelect.ensureNativeImpl()?.nativeWidget as? MacosViewHandle)?.nsView as? NSPopUpButton)
                val cancelNsView = assertNotNull((cancelBtn.ensureNativeImpl()?.nativeWidget as? MacosViewHandle)?.nsView as? NSButton)
                val saveNsView = assertNotNull((saveBtn.ensureNativeImpl()?.nativeWidget as? MacosViewHandle)?.nsView as? NSButton)

                for (descView in listOf<NSView>(scrollNsView, selectNsView, cancelNsView, saveNsView)) {
                    assertTrue(
                        descView.window == panel,
                        "$phase: dialog descendant $descView must be mounted in panel ($panel), was ${descView.window}"
                    )
                    assertFalse(
                        descView.window == ownerNsWin,
                        "$phase: dialog descendant $descView must never attach to ownerNsWin ($ownerNsWin)"
                    )
                    descView.bounds.useContents {
                        assertTrue(
                            size.width > 0.0 && size.height > 0.0,
                            "$phase: dialog descendant $descView must have positive bounds, got ${size.width}x${size.height}"
                        )
                    }
                }

                assertFalse(
                    containsViewRecursively(ownerSession.rootView, dialogSession.rootView),
                    "$phase: ownerSession.rootView must not contain dialogSession.rootView"
                )
                for (descView in listOf<NSView>(scrollNsView, selectNsView, cancelNsView, saveNsView)) {
                    assertFalse(
                        containsViewRecursively(ownerSession.rootView, descView),
                        "$phase: ownerSession.rootView must not contain dialog descendant $descView"
                    )
                }

                panel.contentMinSize.useContents {
                    assertTrue(width >= 480.0, "$phase: panel.contentMinSize.width must be >= 480.0, got $width")
                    assertTrue(height >= 420.0, "$phase: panel.contentMinSize.height must be >= 420.0, got $height")
                }
            }

            verifyDialogPanelHierarchyAndBounds("immediate")
            MacosNativeWindowHost.pumpVisualWindowEvents(48L)
            verifyDialogPanelHierarchyAndBounds("afterPump")

            MacosNativeWindowHost.resetFrameMetrics()
            val collectsBefore = dialogSession.stageViewCollectCountForTest
            repeat(10) {
                MacosNativeWindowHost.onAnimatorVBlankTick()
            }
            MacosNativeWindowHost.pumpVisualWindowEvents(48L)
            assertEquals(collectsBefore, dialogSession.stageViewCollectCountForTest)
            assertTrue(panel.contentView == dialogSession.rootView)
            assertTrue(dialogSession.rootView.window == panel)
            assertTrue(
                MacosNativeWindowHost.getFrameMetricsSnapshot().fullWindowPaints <= 2L,
                "Expected <= 2 fullWindowPaints across 10 VBlank ticks, got ${MacosNativeWindowHost.getFrameMetricsSnapshot().fullWindowPaints}"
            )

            dialog.setWindowSize(Size(720, 600))
            MacosNativeWindowHost.pumpVisualWindowEvents(48L)
            assertEquals(720 to 600, dialogSession.currentClientSize())
        } finally {
            if (dialog.isWindowShown) {
                dialog.close()
            }
            if (ownerWindow.isWindowShown) {
                ownerWindow.close()
            }
        }
    }
}
