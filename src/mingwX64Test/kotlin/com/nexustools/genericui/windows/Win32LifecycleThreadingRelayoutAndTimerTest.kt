@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.component.action.Button
import com.nexustools.genericui.component.dialog.DialogWindow
import com.nexustools.genericui.component.form.NumberField
import com.nexustools.genericui.component.form.RangeSlider
import com.nexustools.genericui.component.fragment.Fragment
import com.nexustools.genericui.component.layout.Container
import com.nexustools.genericui.component.layout.ScrollView
import com.nexustools.genericui.component.layout.styled.StyledContainer
import com.nexustools.genericui.component.layout.VStack
import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.component.shell.Window
import com.nexustools.genericui.component.typography.Text
import com.nexustools.genericui.core.AbstractComponent
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.core.UiTimerScheduler
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.dialog.DesktopDialogWindowNativeImpl
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.geometry.EdgeInsets
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.theme.SystemThemeDetector
import com.nexustools.genericui.threading.GenericUiThreading
import com.nexustools.genericui.windows.impl.WindowsScrollViewNativeImpl
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import platform.windows.GetCurrentThreadId
import platform.windows.GetParent
import platform.windows.GetScrollInfo
import platform.windows.HWND
import platform.windows.IsWindow
import platform.windows.IsWindowVisible
import platform.windows.MapWindowPoints
import platform.windows.POINT
import platform.windows.SB_VERT
import platform.windows.SCROLLINFO
import platform.windows.SIF_PAGE
import platform.windows.SIF_POS
import platform.windows.SIF_RANGE
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Automated Win32 regression tests covering retained child HWND detachment on `clearChildren()`,
 * cross-thread `Dispatchers.Default -> GenericUiThreading.runOnUi` marshaling, coalesced
 * non-overlay dynamic relayouts, `ScrollView` + `VStack` growth & scroll-offset preservation,
 * dark-mode `"Static"` text repainting without glyph smearing, and `Fragment.onStart()` timers
 * arming once `Window.show()` realizes a live `HWND`.
 */
class Win32LifecycleThreadingRelayoutAndTimerTest {

    private fun withVisualWindowHost(block: (WindowsNativeFactory) -> Unit) {
        val prevVisual = Win32NativeWindowHost.visualTestMode
        val prevInteractive = WindowsApplicationNativeImpl.interactiveWin32GuiEnabled
        val prevFactory = GenericUI.activeFactory
        val prevApp = Application.current
        val prevThemeOverride = SystemThemeDetector.overrideDetectedTheme

        Win32NativeWindowHost.visualTestMode = true
        WindowsApplicationNativeImpl.interactiveWin32GuiEnabled = true
        Application.current = null
        GenericUI.resetForTests()
        val factory = WindowsNativeFactory()
        GenericUI.initialize(factory)

        try {
            block(factory)
        } finally {
            for (dialog in DesktopDialogWindowNativeImpl.activeDialogs.toList()) {
                dialog.closeDialog()
            }
            Win32NativeWindowHost.closeAllVisualWindows()
            UiTimerScheduler.clearAllTimers()
            GenericUiThreading.flushAllQueues()
            SystemThemeDetector.overrideDetectedTheme = prevThemeOverride
            Win32NativeWindowHost.visualTestMode = prevVisual
            WindowsApplicationNativeImpl.interactiveWin32GuiEnabled = prevInteractive
            Application.current = prevApp
            GenericUI.resetForTests()
            if (prevFactory != null) {
                GenericUI.initialize(prevFactory)
            }
        }
    }

    private fun collectSubtreeHwnds(component: AbstractComponent): List<HWND> {
        val out = ArrayList<HWND>()
        fun visitHandle(handle: Win32HwndHandle) {
            handle.hwnd?.let { if (IsWindow(it) != 0) out.add(it) }
            handle.buddyHwnd?.let { if (IsWindow(it) != 0) out.add(it) }
            handle.secondaryHwnd?.let { if (IsWindow(it) != 0) out.add(it) }
            handle.secondaryHwnd2?.let { if (IsWindow(it) != 0) out.add(it) }
            for (child in handle.children) {
                if (child is Win32HwndHandle) {
                    visitHandle(child)
                }
            }
        }
        val base = component.ensureNativeImpl() as? DesktopBaseNativeImpl<*> ?: return out
        val handle = base.nativeWidget as? Win32HwndHandle ?: return out
        visitHandle(handle)
        return out
    }

    private fun mapClientTopLeftInAncestor(childHwnd: HWND, ancestorHwnd: HWND): Pair<Int, Int> = memScoped {
        val pt = alloc<POINT>()
        pt.x = 0
        pt.y = 0
        MapWindowPoints(childHwnd, ancestorHwnd, pt.ptr, 1u)
        pt.x to pt.y
    }

    private fun queryVertScrollInfo(svHwnd: HWND): Triple<Int, Int, Int> = memScoped {
        val si = alloc<SCROLLINFO>()
        si.cbSize = sizeOf<SCROLLINFO>().toUInt()
        si.fMask = (SIF_RANGE or SIF_PAGE or SIF_POS).toUInt()
        GetScrollInfo(svHwnd, SB_VERT, si.ptr)
        Triple(si.nMax, si.nPage.toInt(), si.nPos)
    }

    private fun captureClientArgb(window: Window): Pair<IntArray, Pair<Int, Int>> {
        val winImpl = window.ensureNativeImpl() as WindowsWindowNativeImpl
        val sz = winImpl.getWindowSize()
        val shot = assertNotNull(
            Win32NativeWindowHost.captureWindowScreenshot(winImpl, sz.width.coerceAtLeast(240), sz.height.coerceAtLeast(180)),
            "Expected PrintWindow screenshot"
        )
        val out = IntArray(shot.width * shot.height)
        for (i in out.indices) {
            val off = i * 4
            val r = shot.bytes[off].toInt() and 0xFF
            val g = shot.bytes[off + 1].toInt() and 0xFF
            val b = shot.bytes[off + 2].toInt() and 0xFF
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out to (shot.width to shot.height)
    }

    @Test
    fun testClearChildrenDetachesAndHidesWin32ChildHwnds() = withVisualWindowHost { factory ->
        val stack = VStack(gapPx = 8)
        val oldText = Text("Initial stale label")
        val oldButton = Button("Initial stale button")
        val oldNumberField = NumberField(numericValue = 42.0)
        val oldSlider = RangeSlider(minValue = 15.0, maxValue = 85.0, min = 0.0, max = 100.0)
        stack.addChild(oldText)
        stack.addChild(oldButton)
        stack.addChild(oldNumberField)
        stack.addChild(oldSlider)

        val win = Window(title = "ClearChildren Detach Test", initialSize = Size(480, 360))
        win.setContent(Container("div", "w-100 h-100").apply { addChild(stack) })
        win.show(factory)
        Win32NativeWindowHost.pumpVisualWindowEvents(24L)

        val stackHandle = (stack.ensureNativeImpl() as DesktopBaseNativeImpl<*>).nativeWidget as Win32HwndHandle
        val parentHwnd = assertNotNull(stackHandle.hwnd, "VStack must own a live Win32 container HWND")
        val numHandle = (oldNumberField.ensureNativeImpl() as DesktopBaseNativeImpl<*>).nativeWidget as Win32HwndHandle
        assertNotNull(numHandle.buddyHwnd, "NumberField must realize an msctls_updown32 buddy HWND")

        val oldHwnds = listOf(oldText, oldButton, oldNumberField, oldSlider)
            .flatMap { collectSubtreeHwnds(it) }
            .distinct()
        assertTrue(oldHwnds.size >= 5, "Expected at least 5 HWNDs (including buddy HWND), got ${oldHwnds.size}")
        for (h in oldHwnds) {
            assertEquals(parentHwnd, GetParent(h), "Precondition: old HWND must be parented under VStack HWND")
            assertTrue(IsWindowVisible(h) != 0, "Precondition: old HWND must be visible before clearChildren()")
        }

        stack.clearChildren()
        val newText = Text("Replaced active row")
        val newButton = Button("Replaced action")
        stack.addChild(newText)
        stack.addChild(newButton)
        Win32NativeWindowHost.pumpVisualWindowEvents(24L)

        for (oldHwnd in oldHwnds) {
            assertNotEquals(parentHwnd, GetParent(oldHwnd), "Cleared child or buddy HWND must be detached from VStack parent")
            assertEquals(0, IsWindowVisible(oldHwnd), "Cleared child or buddy HWND must be hidden after clearChildren()")
        }

        val newHwnds = listOf(newText, newButton).flatMap { collectSubtreeHwnds(it) }.distinct()
        assertTrue(newHwnds.size >= 2, "Expected new children to realize Win32 HWNDs")
        assertTrue(newHwnds.intersect(oldHwnds.toSet()).isEmpty(), "New HWNDs must not reuse un-detached old entries")
        for (h in newHwnds) {
            assertEquals(parentHwnd, GetParent(h), "Replaced child HWND must be mounted under VStack parent")
            assertTrue(IsWindowVisible(h) != 0, "Replaced child HWND must be visible")
        }

        val newTextBounds = (newText.ensureNativeImpl() as DesktopBaseNativeImpl<*>).getBounds()
        val newButtonBounds = (newButton.ensureNativeImpl() as DesktopBaseNativeImpl<*>).getBounds()
        assertTrue(
            newButtonBounds.y >= newTextBounds.y + newTextBounds.height,
            "Replacement children must lay out cleanly without overlapping (text=$newTextBounds, button=$newButtonBounds)"
        )

        win.close()
    }

    @Test
    fun testRunOnUiFromDispatchersDefaultMarshalsToWin32UiThreadAndJestPumpFlushes() = withVisualWindowHost { factory ->
        WindowsUiThreadDispatcherNativeImpl.clearUiThreadBinding()
        WindowsUiThreadDispatcherNativeImpl.attachCurrentThreadAsUiThreadIfUnset()

        val hostWin = Window(title = "Threading Host", initialSize = Size(440, 260))
        hostWin.setContent(VStack(gapPx = 8).apply { addChild(Text("Win32 UI Thread Anchor")) })
        hostWin.show(factory)
        Win32NativeWindowHost.pumpVisualWindowEvents(24L)

        val registeredUiThreadId = WindowsUiThreadDispatcherNativeImpl.getRegisteredUiThreadId()
        assertNotEquals(0, registeredUiThreadId, "Win32 UI thread ID must be registered")
        assertEquals(GetCurrentThreadId().toInt(), registeredUiThreadId, "Test thread must own registered Win32 UI thread")

        var workerThreadId = 0
        var marshaledThreadId = 0
        var dialogShown = false
        var openedDialog: DialogWindow? = null

        try {
            runBlocking {
                val bgTask = async(Dispatchers.Default) {
                    workerThreadId = GetCurrentThreadId().toInt()
                    GenericUiThreading.runOnUi {
                        marshaledThreadId = GetCurrentThreadId().toInt()
                        val dlg = DialogWindow(title = "Marshaled UI Dialog").apply {
                            setWindowSize(Size(320, 180))
                            setContent(Container("div", "p-3").apply {
                                addChild(Text("Dialog opened from Dispatchers.Default via runOnUi"))
                            })
                        }
                        dlg.open()
                        openedDialog = dlg
                        dialogShown = true
                    }
                }

                var attempts = 0
                while ((!bgTask.isCompleted || !dialogShown) && attempts < 300) {
                    Win32NativeWindowHost.pumpVisualWindowEvents(10L)
                    GenericUiThreading.flushAllQueues()
                    attempts++
                }
                bgTask.await()
            }

            assertTrue(dialogShown, "GenericUiThreading.runOnUi callback must execute without deadlock")
            assertNotEquals(0, workerThreadId, "Dispatchers.Default worker thread ID must be captured")
            assertNotEquals(
                registeredUiThreadId,
                workerThreadId,
                "Dispatchers.Default must execute on a non-UI background thread (worker=$workerThreadId, ui=$registeredUiThreadId)"
            )
            assertEquals(
                registeredUiThreadId,
                marshaledThreadId,
                "runOnUi callback must execute strictly on the registered Win32 UI thread"
            )

            Win32NativeWindowHost.pumpVisualWindowEvents(24L)
            val dlgImpl = assertNotNull(openedDialog).ensureNativeImpl() as DesktopDialogWindowNativeImpl<*>
            val dlgHwnd = assertNotNull(Win32NativeWindowHost.dialogHwnds[dlgImpl], "DialogWindow must realize a live Win32 HWND")
            assertTrue(IsWindow(dlgHwnd) != 0 && IsWindowVisible(dlgHwnd) != 0, "Marshaled DialogWindow HWND must be live and visible")
        } finally {
            openedDialog?.close()
            hostWin.close()
        }
    }

    @Test
    fun testNonOverlayDynamicMutationsTriggerCoalescedTopDownRelayout() = withVisualWindowHost { factory ->
        val dynamicText = Text("Short label")
        val rowsStack = VStack(gapPx = 6).apply {
            addChild(dynamicText)
            addChild(Text("Static baseline row 1"))
        }
        val card = StyledContainer().apply {
            setPadding(EdgeInsets.all(12))
            setBackground(RgbaColor(36, 40, 48, 255))
            setBorder(1f, RgbaColor(72, 78, 90, 255), radiusPx = 6f)
            addChild(rowsStack)
        }
        val siblingBelow = Button("Below Dynamic Card")
        val rootStack = VStack(gapPx = 10).apply {
            addChild(card)
            addChild(siblingBelow)
        }

        val win = Window(title = "Dynamic Relayout Test", initialSize = Size(560, 480))
        win.setContent(Container("div", "p-3").apply { addChild(rootStack) })
        win.show(factory)
        Win32NativeWindowHost.pumpVisualWindowEvents(32L)

        val rootHwnd = assertNotNull(Win32NativeWindowHost.activeHwnd)
        val siblingHwnd = assertNotNull(((siblingBelow.ensureNativeImpl() as DesktopBaseNativeImpl<*>).nativeWidget as Win32HwndHandle).hwnd)

        val cardInit = (card.ensureNativeImpl() as DesktopBaseNativeImpl<*>).getBounds()
        val siblingInit = (siblingBelow.ensureNativeImpl() as DesktopBaseNativeImpl<*>).getBounds()
        val (_, siblingHwndYInit) = mapClientTopLeftInAncestor(siblingHwnd, rootHwnd)

        // Mutate text + append rows WITHOUT resizing the host window
        dynamicText.text = "Expanded multi-line status line 1\nExpanded status line 2\nExpanded status line 3"
        val addedRows = (2..5).map { idx -> Text("Dynamically appended row #$idx") }
        for (row in addedRows) {
            rowsStack.addChild(row)
        }
        Win32NativeWindowHost.pumpVisualWindowEvents(40L)

        val cardGrow = (card.ensureNativeImpl() as DesktopBaseNativeImpl<*>).getBounds()
        val siblingGrow = (siblingBelow.ensureNativeImpl() as DesktopBaseNativeImpl<*>).getBounds()
        val (_, siblingHwndYGrow) = mapClientTopLeftInAncestor(siblingHwnd, rootHwnd)

        assertTrue(
            cardGrow.height > cardInit.height,
            "Card container height must grow after non-overlay mutations (${cardInit.height} -> ${cardGrow.height})"
        )
        assertTrue(
            siblingGrow.y > siblingInit.y,
            "Sibling below card must reflow downward (${siblingInit.y} -> ${siblingGrow.y})"
        )
        assertTrue(
            siblingHwndYGrow > siblingHwndYInit,
            "Live Win32 sibling HWND Y must advance on screen ($siblingHwndYInit -> $siblingHwndYGrow)"
        )

        // Shrink back WITHOUT resizing the host window
        for (row in addedRows) {
            rowsStack.removeChild(row)
        }
        dynamicText.text = "Short label"
        Win32NativeWindowHost.pumpVisualWindowEvents(40L)

        val cardShrink = (card.ensureNativeImpl() as DesktopBaseNativeImpl<*>).getBounds()
        val siblingShrink = (siblingBelow.ensureNativeImpl() as DesktopBaseNativeImpl<*>).getBounds()
        val (_, siblingHwndYShrink) = mapClientTopLeftInAncestor(siblingHwnd, rootHwnd)

        assertTrue(
            cardShrink.height < cardGrow.height,
            "Card container height must shrink back after row removal (${cardGrow.height} -> ${cardShrink.height})"
        )
        assertTrue(
            siblingShrink.y < siblingGrow.y,
            "Sibling below card must reflow back upward (${siblingGrow.y} -> ${siblingShrink.y})"
        )
        assertTrue(
            siblingHwndYShrink < siblingHwndYGrow,
            "Live Win32 sibling HWND Y must move back up ($siblingHwndYGrow -> $siblingHwndYShrink)"
        )

        win.close()
    }

    @Test
    fun testScrollViewInnerVStackGrowthUpdatesScrollbarsAndPreservesScrollOffset() = withVisualWindowHost { factory ->
        val innerStack = VStack(gapPx = 6)
        val anchorText = Text("Anchor row #0")
        val initialRows = ArrayList<StyledContainer>()
        for (i in 0 until 8) {
            val row = StyledContainer().apply {
                setPadding(EdgeInsets(6, 10, 6, 10))
                setBackground(RgbaColor(42, 46, 56, 255))
                addChild(if (i == 0) anchorText else Text("Scroll item #$i"))
            }
            initialRows.add(row)
            innerStack.addChild(row)
        }

        val scrollView = ScrollView().apply {
            style.setWidthPx(360)
            style.setHeightPx(160)
            addChild(innerStack)
        }

        val win = Window(title = "ScrollView VStack Growth & Offset Test", initialSize = Size(460, 300))
        win.setContent(Container("div", "p-3").apply { addChild(scrollView) })
        win.show(factory)
        Win32NativeWindowHost.pumpVisualWindowEvents(32L)

        val svImpl = scrollView.ensureNativeImpl() as WindowsScrollViewNativeImpl
        val svHwnd = assertNotNull(svImpl.nativeWidget.hwnd)
        val anchorHwnd = assertNotNull(((initialRows.first().ensureNativeImpl() as DesktopBaseNativeImpl<*>).nativeWidget as Win32HwndHandle).hwnd)

        val (nMaxBefore, _, _) = queryVertScrollInfo(svHwnd)
        assertTrue(nMaxBefore > 160, "Precondition: initial 8 items should exceed 160px ScrollView height (nMax=$nMaxBefore)")

        // Grow inner VStack -> SB_VERT nMax must grow
        val extraRows = (8 until 18).map { idx ->
            StyledContainer().apply {
                setPadding(EdgeInsets(6, 10, 6, 10))
                setBackground(RgbaColor(42, 46, 56, 255))
                addChild(Text("Appended scroll item #$idx"))
            }
        }
        for (row in extraRows) {
            innerStack.addChild(row)
        }
        Win32NativeWindowHost.pumpVisualWindowEvents(32L)

        val (nMaxAfterGrow, _, _) = queryVertScrollInfo(svHwnd)
        assertTrue(
            nMaxAfterGrow > nMaxBefore,
            "Adding children to inner VStack must grow SB_VERT nMax ($nMaxBefore -> $nMaxAfterGrow)"
        )

        // Scroll down to non-zero scrollY and capture shifted child HWND Y
        scrollView.scrollTo(0, 110)
        Win32NativeWindowHost.pumpVisualWindowEvents(24L)
        val scrollYBeforeMut = svImpl.currentScrollY()
        assertTrue(scrollYBeforeMut > 0, "ScrollView must scroll to positive Y offset (got $scrollYBeforeMut)")
        val (_, anchorYBeforeMut) = mapClientTopLeftInAncestor(anchorHwnd, svHwnd)
        assertTrue(anchorYBeforeMut < 0, "Scrolled top child HWND must sit at negative Y inside ScrollView (got $anchorYBeforeMut)")

        // Trigger a non-overlay relayout inside the inner VStack -> scrollY & child HWND offset must be preserved
        anchorText.text = "Mutated anchor text without resetting scroll"
        val trailingRow = StyledContainer().apply {
            setPadding(EdgeInsets(6, 10, 6, 10))
            setBackground(RgbaColor(50, 56, 68, 255))
            addChild(Text("Trailing dynamic row"))
        }
        innerStack.addChild(trailingRow)
        Win32NativeWindowHost.pumpVisualWindowEvents(32L)

        val scrollYAfterMut = svImpl.currentScrollY()
        val (_, anchorYAfterMut) = mapClientTopLeftInAncestor(anchorHwnd, svHwnd)
        assertEquals(
            scrollYBeforeMut,
            scrollYAfterMut,
            "Non-overlay relayout inside VStack must preserve ScrollView scrollY ($scrollYBeforeMut vs $scrollYAfterMut)"
        )
        assertTrue(
            anchorYAfterMut < 0 && abs(anchorYAfterMut - anchorYBeforeMut) <= 2,
            "Scrolled child HWND Y inside ScrollView must stay shifted after relayout (before=$anchorYBeforeMut, after=$anchorYAfterMut)"
        )

        // Remove appended rows -> SB_VERT nMax must shrink back
        innerStack.removeChild(trailingRow)
        for (row in extraRows) {
            innerStack.removeChild(row)
        }
        Win32NativeWindowHost.pumpVisualWindowEvents(32L)

        val (nMaxAfterShrink, _, _) = queryVertScrollInfo(svHwnd)
        assertTrue(
            nMaxAfterShrink < nMaxAfterGrow,
            "Removing children from inner VStack must shrink SB_VERT nMax ($nMaxAfterGrow -> $nMaxAfterShrink)"
        )

        win.close()
    }

    @Test
    fun testStaticTextUpdateErasesPreviousGlyphsWithoutSmearing() = withVisualWindowHost { factory ->
        SystemThemeDetector.overrideDetectedTheme = ThemeMode.DARK

        val statusText = Text("Ready").apply {
            style.setWidthPx(340)
            style.setColor(RgbaColor(238, 238, 244, 255))
        }
        val darkCard = StyledContainer().apply {
            setPadding(EdgeInsets.all(16))
            setBackground(RgbaColor(24, 24, 30, 255))
            addChild(statusText)
        }

        val win = Window(title = "Static Text Repaint Test", initialSize = Size(440, 180))
        win.setContent(Container("div", "w-100 h-100").apply { addChild(darkCard) })
        win.show(factory)
        Win32NativeWindowHost.pumpVisualWindowEvents(40L)

        val (baselinePixels, sizeInit) = captureClientArgb(win)
        val textImpl = statusText.ensureNativeImpl() as DesktopBaseNativeImpl<*>
        val cardImpl = darkCard.ensureNativeImpl() as DesktopBaseNativeImpl<*>
        val textBounds = textImpl.getBounds()
        val cardBoundsBefore = cardImpl.getBounds()
        val rightSampleX = (textBounds.x + textBounds.width - 16).coerceIn(0, sizeInit.first - 1)
        val rightSampleY = (textBounds.y + textBounds.height / 2).coerceIn(0, sizeInit.second - 1)
        val rightBgPixel = baselinePixels[rightSampleY * sizeInit.first + rightSampleX]
        val bgR = (rightBgPixel ushr 16) and 0xFF
        val bgG = (rightBgPixel ushr 8) and 0xFF
        val bgB = rightBgPixel and 0xFF
        assertTrue(
            bgR < 60 && bgG < 60 && bgB < 60,
            "Dark-mode Static text background must match dark surface, not bright white box (rgb=($bgR,$bgG,$bgB))"
        )

        // Render much wider string, then reset back to exact initial string
        statusText.text = "Wide Status Message 0123456789 ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        Win32NativeWindowHost.pumpVisualWindowEvents(40L)

        statusText.text = "Ready"
        Win32NativeWindowHost.pumpVisualWindowEvents(40L)

        val (afterResetPixels, sizeAfter) = captureClientArgb(win)
        assertEquals(sizeInit, sizeAfter)
        val textBoundsAfter = textImpl.getBounds()
        val cardBoundsAfter = cardImpl.getBounds()

        var diffPixels = 0
        var firstDiffIdx = -1
        for (idx in baselinePixels.indices) {
            if (baselinePixels[idx] != afterResetPixels[idx]) {
                if (firstDiffIdx < 0) firstDiffIdx = idx
                diffPixels++
            }
        }
        val diag = if (firstDiffIdx >= 0) {
            val fx = firstDiffIdx % sizeInit.first
            val fy = firstDiffIdx / sizeInit.first
            val bHex = "0x" + baselinePixels[firstDiffIdx].toUInt().toString(16).padStart(8, '0')
            val aHex = "0x" + afterResetPixels[firstDiffIdx].toUInt().toString(16).padStart(8, '0')
            " firstMismatch=($fx,$fy) baseline=$bHex after=$aHex textBefore=$textBounds textAfter=$textBoundsAfter cardBefore=$cardBoundsBefore cardAfter=$cardBoundsAfter"
        } else ""
        assertEquals(
            0,
            diffPixels,
            "Reverting Static text from wide string back to initial string must restore exact dark-mode pixels with zero smeared ghost glyphs.$diag"
        )

        win.close()
    }

    @Test
    fun testFragmentOnStartTimersFireOnceWindowShowsAndStopOnClear() = withVisualWindowHost { factory ->
        assertNull(Win32NativeWindowHost.activeHwnd, "Precondition: no live HWND before Window.show()")

        var onStartCalled = false
        var activeHwndDuringOnStart: HWND? = null
        var timeoutFires = 0
        var intervalFires = 0
        var timeoutId = -1
        var intervalId = -1

        val fragment = object : Fragment() {
            override fun onStart() {
                super.onStart()
                activeHwndDuringOnStart = Win32NativeWindowHost.activeHwnd
                onStartCalled = true
                timeoutId = setTimeout(25) { timeoutFires++ }
                intervalId = setInterval(30) { intervalFires++ }
            }
        }

        val win = Window(title = "Fragment Timer Lifecycle Host", initialSize = Size(420, 240))
        win.setFragment(fragment)

        assertNull(Win32NativeWindowHost.activeHwnd, "activeHwnd must still be null right after setFragment()")

        win.show(factory)
        assertTrue(onStartCalled, "Fragment.onStart() must execute when Window.show() starts the active fragment")
        assertNull(activeHwndDuringOnStart, "Fragment.onStart() must execute before Window.show() creates activeHwnd")
        assertTrue(timeoutId > 0 && intervalId > 0, "Timers registered in onStart() must return valid IDs")
        assertEquals(0, timeoutFires)
        assertEquals(0, intervalFires)
        assertNotNull(Win32NativeWindowHost.activeHwnd, "Window.show() must realize live Win32 HWND")

        // Pump real Win32 WM_TIMER events across >120ms
        Win32NativeWindowHost.pumpVisualWindowEvents(140L)
        GenericUiThreading.flushAllQueues()

        assertEquals(1, timeoutFires, "onStart setTimeout must fire exactly once after Window.show()")
        assertTrue(intervalFires >= 2, "onStart setInterval must fire repeatedly after Window.show(), got $intervalFires")

        fragment.clearInterval(intervalId)
        val countAtClear = intervalFires
        Win32NativeWindowHost.pumpVisualWindowEvents(90L)
        GenericUiThreading.flushAllQueues()
        assertEquals(countAtClear, intervalFires, "Fragment interval timer must stop cleanly after clearInterval()")

        // Verify window close / fragment destroy also halts any active timer cleanly
        var closeStopCount = 0
        fragment.setInterval(25) { closeStopCount++ }
        Win32NativeWindowHost.pumpVisualWindowEvents(85L)
        GenericUiThreading.flushAllQueues()
        assertTrue(closeStopCount >= 1, "Second interval should fire while window is shown")

        win.close()
        val countAtClose = closeStopCount
        Win32NativeWindowHost.pumpVisualWindowEvents(90L)
        GenericUiThreading.flushAllQueues()
        assertEquals(countAtClose, closeStopCount, "Fragment timers must stop cleanly on Window.close()")
    }
}
