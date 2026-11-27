@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.component.action.Button
import com.nexustools.genericui.component.action.DropdownButton
import com.nexustools.genericui.component.action.SplitDropdownButton
import com.nexustools.genericui.component.action.ToggleButton
import com.nexustools.genericui.component.content.ImageView
import com.nexustools.genericui.component.feedback.ProgressBar
import com.nexustools.genericui.component.form.CheckBox
import com.nexustools.genericui.component.form.RangeSlider
import com.nexustools.genericui.component.form.Select
import com.nexustools.genericui.component.form.SelectOption
import com.nexustools.genericui.component.layout.HStack
import com.nexustools.genericui.component.layout.ScrollView
import com.nexustools.genericui.component.layout.Spacer
import com.nexustools.genericui.component.layout.StackConstraint
import com.nexustools.genericui.component.layout.styled.StyledContainer
import com.nexustools.genericui.component.layout.VStack
import com.nexustools.genericui.component.lazy.LazyGridTree
import com.nexustools.genericui.component.lazy.LazyGridTreeNode
import com.nexustools.genericui.component.lazy.LazyList
import com.nexustools.genericui.component.shell.Window
import com.nexustools.genericui.component.tabs.Tab
import com.nexustools.genericui.component.tabs.TabContainer
import com.nexustools.genericui.component.typography.Badge
import com.nexustools.genericui.component.typography.Heading
import com.nexustools.genericui.component.typography.Text
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopScrollViewNativeImpl
import com.nexustools.genericui.windows.impl.WindowsLazyListNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.bootstrap.FlexWrap
import com.nexustools.genericui.enums.bootstrap.OverflowBehavior
import com.nexustools.genericui.geometry.EdgeInsets
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssLength
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.windows.GWL_STYLE
import platform.windows.GetClientRect
import platform.windows.GetWindowLongW
import platform.windows.HWND
import platform.windows.IsWindow
import platform.windows.IsWindowVisible
import platform.windows.MapWindowPoints
import platform.windows.POINT
import platform.windows.RECT
import platform.windows.WS_VSCROLL
import kotlin.math.abs
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end GDI pixel-readback and layout/scrolling tests verifying general Win32 layout,
 * persistent backbuffer invalidation (`RC1`), bounded flex + virtualized `LazyList` viewports
 * (`RC3`, `RC4`), `HStack` flex/wrap/padding (`RC5`, `RC6`), `Heading`/`ProgressBar` semantics
 * (`RC7`), `OverflowBehavior.HIDDEN` (`RC8`), `ScrollView` clipping & wheel routing (`RC9`),
 * `LazyGridTree` general flow rendering, and Objective A multi-window close behavior.
 */
class Win32GeneralLayoutAndLazyListRenderingTest {

    private inline fun withVisualWindowHost(block: (WindowsNativeFactory) -> Unit) {
        val prevInteractive = WindowsApplicationNativeImpl.interactiveWin32GuiEnabled
        val prevVisualTest = Win32NativeWindowHost.visualTestMode
        WindowsApplicationNativeImpl.interactiveWin32GuiEnabled = true
        Win32NativeWindowHost.visualTestMode = true
        try {
            val factory = WindowsNativeFactory()
            GenericUI.initialize(factory)
            block(factory)
        } finally {
            Win32NativeWindowHost.closeAllVisualWindows()
            Win32NativeWindowHost.visualTestMode = prevVisualTest
            WindowsApplicationNativeImpl.interactiveWin32GuiEnabled = prevInteractive
        }
    }

    private fun extractRectPixels(
        pixels: IntArray,
        stride: Int,
        totalH: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): IntArray {
        val l = left.coerceIn(0, stride)
        val r = right.coerceIn(l, stride)
        val t = top.coerceIn(0, totalH)
        val b = bottom.coerceIn(t, totalH)
        val w = r - l
        val h = b - t
        if (w <= 0 || h <= 0) return IntArray(0)
        val out = IntArray(w * h)
        for (row in 0 until h) {
            val srcOff = (t + row) * stride + l
            val dstOff = row * w
            for (col in 0 until w) {
                out[dstOff + col] = pixels[srcOff + col]
            }
        }
        return out
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

    private fun hwndRectInHost(childHwnd: HWND, hostHwnd: HWND): IntArray = memScoped {
        val rc = alloc<RECT>()
        GetClientRect(childHwnd, rc.ptr)
        val pt = alloc<POINT>()
        pt.x = 0
        pt.y = 0
        MapWindowPoints(childHwnd, hostHwnd, pt.ptr, 1u)
        intArrayOf(pt.x, pt.y, pt.x + (rc.right - rc.left), pt.y + (rc.bottom - rc.top))
    }

    private fun countDifferentPixels(a: IntArray, b: IntArray): Int {
        val n = min(a.size, b.size)
        var diff = 0
        for (i in 0 until n) {
            if (a[i] != b[i]) diff++
        }
        return diff
    }

    @Test
    fun testDynamicTextAndBadgeUpdateUpdatesWin32BackbufferPixels() = withVisualWindowHost { factory ->
        val root = VStack(gapPx = 8).apply {
            setOverflow(OverflowBehavior.HIDDEN)
        }
        val card = StyledContainer().apply {
            setPadding(EdgeInsets.all(14))
            setBackground(RgbaColor(42, 46, 54, 255))
            setBorder(1f, RgbaColor(72, 78, 90, 255), radiusPx = 8f)
        }
        val percentText = Text("0.0%")
        val jobsText = Text("0 / 0 Jobs")
        val statusBadge = Badge("Active: 0/4", BootstrapType.SECONDARY)
        val cardRow = HStack(gapPx = 10)
        cardRow.addChild(percentText)
        cardRow.addChild(jobsText)
        cardRow.addChild(statusBadge)
        card.addChild(cardRow)
        root.addChild(card)

        val window = Window(title = "Dynamic Text & Badge Pixel Test", initialSize = Size(640, 320))
        window.setContent(root)
        window.show(factory)
        Win32NativeWindowHost.pumpVisualWindowEvents(16L)

        val hwnd = Win32NativeWindowHost.activeHwnd
        assertNotNull(hwnd, "Expected active Win32 HWND")

        val (frame1Pixels, dims1) = captureClientArgb(window)
        val bw = dims1.first
        val bh = dims1.second
        assertTrue(bw >= 300 && bh >= 200, "Unexpected backbuffer dimensions ${bw}x$bh")

        // Frame 2: Mutate to wider strings & resized semantic badge
        percentText.text = "98.8%"
        jobsText.text = "88 / 117 Jobs"
        statusBadge.text = "Active: 4/4"
        statusBadge.type = BootstrapType.WARNING
        Win32NativeWindowHost.pumpVisualWindowEvents(16L)

        val (frame2Pixels, _) = captureClientArgb(window)
        assertFalse(
            frame1Pixels.contentEquals(frame2Pixels),
            "PrintWindow pixels must change when Text and Badge state change"
        )
        val changedCount = countDifferentPixels(frame1Pixels, frame2Pixels)
        assertTrue(changedCount > 25, "Expected substantial pixel delta on Text+Badge change, got $changedCount")

        // Frame 3: Mutate back to initial strings/badge and verify ZERO ghosting vs Frame 1
        percentText.text = "0.0%"
        jobsText.text = "0 / 0 Jobs"
        statusBadge.text = "Active: 0/4"
        statusBadge.type = BootstrapType.SECONDARY
        Win32NativeWindowHost.pumpVisualWindowEvents(16L)

        val (frame3Pixels, _) = captureClientArgb(window)
        val ghostDiff = countDifferentPixels(frame1Pixels, frame3Pixels)
        assertTrue(
            frame1Pixels.contentEquals(frame3Pixels),
            "Frame 3 after reverting text/badge must match Frame 1 pixel-for-pixel (zero smearing), diffPixels=$ghostDiff"
        )
    }

    @Test
    fun testRepeatedLazyListSetItemsAndSemanticProgressBarColors() = withVisualWindowHost { factory ->
        val root = VStack(gapPx = 8).apply {
            setOverflow(OverflowBehavior.HIDDEN)
        }
        val lazyList = LazyList(estimatedItemExtentPx = 56f)
        root.addChild(lazyList, StackConstraint.weight(1f))

        fun buildRow(label: String, progress: Float, barType: BootstrapType): StyledContainer {
            val row = StyledContainer().apply {
                setPadding(EdgeInsets(8, 12, 8, 12))
                setBackground(RgbaColor(40, 44, 52, 255))
                setBorder(1f, RgbaColor(70, 76, 88, 255))
            }
            val inner = VStack(gapPx = 4)
            inner.addChild(Text(label))
            inner.addChild(ProgressBar(progress = progress).apply { setType(barType) })
            row.addChild(inner)
            return row
        }

        lazyList.setItems(12, onLoadItem = { idx ->
            buildRow("encode-job-$idx.mp4", 0.65f, BootstrapType.WARNING)
        })

        val window = Window(title = "LazyList & ProgressBar Test", initialSize = Size(700, 420))
        window.setContent(root)
        window.show(factory)
        Win32NativeWindowHost.pumpVisualWindowEvents(16L)

        val hwnd = Win32NativeWindowHost.activeHwnd
        assertNotNull(hwnd)

        val (warningPixels, wDims) = captureClientArgb(window)
        val bw = wDims.first
        val bh = wDims.second
        val row0TopBandWarning = extractRectPixels(warningPixels, bw, bh, 0, 0, bw, 120)

        // Setting identical job items with BootstrapType.NONE must produce identical native
        // ComCtl32 ProgressBar output (non-button controls ignore BootstrapType on Windows).
        lazyList.setItems(12, onLoadItem = { idx ->
            buildRow("encode-job-$idx.mp4", 0.65f, BootstrapType.NONE)
        })
        Win32NativeWindowHost.pumpVisualWindowEvents(16L)

        val (nonePixels, _) = captureClientArgb(window)
        val row0TopBandNone = extractRectPixels(nonePixels, bw, bh, 0, 0, bw, 120)
        assertTrue(
            row0TopBandWarning.contentEquals(row0TopBandNone),
            "Native ProgressBar must ignore BootstrapType and render identically for WARNING vs NONE"
        )

        // Update items via setItems with finished progress (1.0f) + new text
        lazyList.setItems(12, onLoadItem = { idx ->
            buildRow("finished-job-$idx.mkv", 1.0f, BootstrapType.SUCCESS)
        })
        Win32NativeWindowHost.pumpVisualWindowEvents(16L)

        val (successPixels, _) = captureClientArgb(window)
        val row0TopBandSuccess = extractRectPixels(successPixels, bw, bh, 0, 0, bw, 120)

        assertFalse(
            row0TopBandWarning.contentEquals(row0TopBandSuccess),
            "Repeated LazyList.setItems must re-render row contents and ProgressBar progress fill"
        )
    }

    @Test
    fun testButtonOnlyBootstrapTintingAndGenuineNativeNonButtonControls() = withVisualWindowHost { factory ->
        // 1. Non-button standard controls must render identical genuine uxtheme.dll output
        //    regardless of BootstrapType (NONE vs DANGER vs SUCCESS).
        fun buildNonButtonRoot(type: BootstrapType): VStack {
            val vstack = VStack(gapPx = 6).apply {
                setOverflow(OverflowBehavior.HIDDEN)
            }
            vstack.addChild(ProgressBar(progress = 0.65f).apply { setType(type) })
            vstack.addChild(CheckBox("Native CheckBox", checked = true).apply { setType(type) })
            vstack.addChild(
                RangeSlider(minValue = 55.0, maxValue = 55.0, min = 0.0, max = 100.0).apply {
                    setType(type)
                }
            )
            vstack.addChild(
                Select(
                    options = listOf(SelectOption("opt1", "Option One"), SelectOption("opt2", "Option Two")),
                    selectedValue = "opt1"
                ).apply { setType(type) }
            )
            vstack.addChild(
                TabContainer().apply {
                    setType(type)
                    addTab(Tab(title = "Tab Alpha"), Text("Alpha Content"))
                    addTab(Tab(title = "Tab Beta"), Text("Beta Content"))
                }
            )
            val scrollView = ScrollView().apply {
                setType(type)
                val inner = VStack(gapPx = 4)
                for (i in 1..16) {
                    inner.addChild(Text("Scrollable item line $i"))
                }
                addChild(inner)
            }
            vstack.addChild(scrollView, StackConstraint.weight(1f))
            return vstack
        }

        val nativeWindow = Window(title = "Native Controls Parity", initialSize = Size(560, 480))
        nativeWindow.setContent(buildNonButtonRoot(BootstrapType.NONE))
        nativeWindow.show(factory)
        Win32NativeWindowHost.pumpVisualWindowEvents(20L)

        val hwnd = Win32NativeWindowHost.activeHwnd
        assertNotNull(hwnd)

        val (noneCapture, capDims) = captureClientArgb(nativeWindow)
        val bw = capDims.first
        val bh = capDims.second
        val noneClient = extractRectPixels(noneCapture, bw, bh, 4, 4, bw - 4, bh - 4)
        assertTrue(noneClient.toSet().size > 4, "Expected native controls to render non-uniform pixels")

        nativeWindow.setContent(buildNonButtonRoot(BootstrapType.DANGER))
        Win32NativeWindowHost.pumpVisualWindowEvents(20L)
        val (dangerCapture, _) = captureClientArgb(nativeWindow)
        val dangerClient = extractRectPixels(dangerCapture, bw, bh, 4, 4, bw - 4, bh - 4)
        assertTrue(
            noneClient.contentEquals(dangerClient),
            "Non-button controls (ProgressBar, CheckBox, RangeSlider, Select, TabContainer, ScrollBar) must render pixel-for-pixel identically between BootstrapType.NONE and BootstrapType.DANGER"
        )

        nativeWindow.setContent(buildNonButtonRoot(BootstrapType.SUCCESS))
        Win32NativeWindowHost.pumpVisualWindowEvents(20L)
        val (successCapture, _) = captureClientArgb(nativeWindow)
        val successClient = extractRectPixels(successCapture, bw, bh, 4, 4, bw - 4, bh - 4)
        assertTrue(
            noneClient.contentEquals(successClient),
            "Non-button controls must render pixel-for-pixel identically between BootstrapType.NONE and BootstrapType.SUCCESS"
        )

        // 2. Buttons ONLY support BootstrapType custom Bootstrap surface vs genuine Button class.
        fun captureSingleControl(component: com.nexustools.genericui.core.AbstractComponent): IntArray {
            nativeWindow.setContent(VStack(gapPx = 6).apply { addChild(component) })
            Win32NativeWindowHost.pumpVisualWindowEvents(16L)
            val (snap, _) = captureClientArgb(nativeWindow)
            return extractRectPixels(snap, bw, bh, 0, 0, bw - 8, 64)
        }

        val btnNone = captureSingleControl(Button("Action Label", type = BootstrapType.NONE))
        val btnDanger = captureSingleControl(Button("Action Label", type = BootstrapType.DANGER))
        val btnPrimary = captureSingleControl(Button("Action Label", type = BootstrapType.PRIMARY))
        val btnSuccess = captureSingleControl(Button("Action Label", type = BootstrapType.SUCCESS))

        assertFalse(btnNone.contentEquals(btnDanger), "DANGER Button must render pixel-tinted uxtheme.dll sprite")
        assertFalse(btnNone.contentEquals(btnPrimary), "PRIMARY Button must render pixel-tinted uxtheme.dll sprite")
        assertFalse(btnNone.contentEquals(btnSuccess), "SUCCESS Button must render pixel-tinted uxtheme.dll sprite")
        assertFalse(btnDanger.contentEquals(btnSuccess), "DANGER and SUCCESS Buttons must have distinct tinted chroma")

        assertTrue(
            btnDanger.any { argb ->
                val r = (argb ushr 16) and 0xFF
                val g = (argb ushr 8) and 0xFF
                val b = argb and 0xFF
                r > g + 40 && r > b + 40
            },
            "Expected DANGER Button sprite to contain red-tinted pixels"
        )
        assertTrue(
            btnPrimary.any { argb ->
                val r = (argb ushr 16) and 0xFF
                val b = argb and 0xFF
                b > r + 40
            },
            "Expected PRIMARY Button sprite to contain blue-tinted pixels"
        )
        assertTrue(
            btnSuccess.any { argb ->
                val r = (argb ushr 16) and 0xFF
                val g = (argb ushr 8) and 0xFF
                val b = argb and 0xFF
                g > r + 25 && g > b + 20
            },
            "Expected SUCCESS Button sprite to contain green-tinted pixels"
        )
        val stripW = (bw - 8).coerceAtLeast(1)
        val stripH = 64
        var redMinX = Int.MAX_VALUE
        var redMaxX = -1
        var redMinY = Int.MAX_VALUE
        var redMaxY = -1
        for (sy in 0 until stripH) {
            for (sx in 0 until stripW) {
                val argb = btnDanger[sy * stripW + sx]
                val r = (argb ushr 16) and 0xFF
                val g = (argb ushr 8) and 0xFF
                val b = argb and 0xFF
                if (r > g + 40 && r > b + 40) {
                    if (sx < redMinX) redMinX = sx
                    if (sx > redMaxX) redMaxX = sx
                    if (sy < redMinY) redMinY = sy
                    if (sy > redMaxY) redMaxY = sy
                }
            }
        }
        assertTrue(redMaxX > redMinX + 10 && redMaxY > redMinY + 10, "Expected bounded DANGER Button sprite")
        var hasBrightTextInsideRedButton = false
        for (sy in (redMinY + 3) until (redMaxY - 2)) {
            for (sx in (redMinX + 4) until (redMaxX - 3)) {
                val argb = btnDanger[sy * stripW + sx]
                val r = (argb ushr 16) and 0xFF
                val g = (argb ushr 8) and 0xFF
                val b = argb and 0xFF
                if (r > 210 && g > 210 && b > 210) {
                    hasBrightTextInsideRedButton = true
                    break
                }
            }
            if (hasBrightTextInsideRedButton) break
        }
        assertTrue(
            hasBrightTextInsideRedButton,
            "Expected DANGER Button to render bright contrasting text strictly inside the red tinted sprite"
        )

        val toggleNone = captureSingleControl(ToggleButton("Toggle", selected = true, type = BootstrapType.NONE))
        val toggleDanger = captureSingleControl(ToggleButton("Toggle", selected = true, type = BootstrapType.DANGER))
        assertFalse(toggleNone.contentEquals(toggleDanger), "ToggleButton must keep BootstrapType button tinting")

        // DropdownButton & SplitDropdownButton must ignore BootstrapType and use unmodified native OS controls
        val dropdownNone = captureSingleControl(DropdownButton("Actions").apply { setType(BootstrapType.NONE) })
        val dropdownDanger = captureSingleControl(DropdownButton("Actions").apply { setType(BootstrapType.DANGER) })
        assertTrue(dropdownNone.contentEquals(dropdownDanger), "DropdownButton must ignore BootstrapType and stay native OS styled")

        val splitDropdownNone = captureSingleControl(SplitDropdownButton("Deploy").apply { setType(BootstrapType.NONE) })
        val splitDropdownSuccess = captureSingleControl(SplitDropdownButton("Deploy").apply { setType(BootstrapType.SUCCESS) })
        assertTrue(splitDropdownNone.contentEquals(splitDropdownSuccess), "SplitDropdownButton must ignore BootstrapType and stay native OS styled")
    }

    @Test
    fun testMultiColumnWeightedMetricsAndSideBySideBoundedLazyListsWithIndependentWheelScroll() = withVisualWindowHost { factory ->
        val root = VStack(gapPx = 8).apply {
            setOverflow(OverflowBehavior.HIDDEN)
        }

        val headerRow = HStack(gapPx = 10)
        val iconView = ImageView(com.nexustools.genericui.res.Resources.LOADING_IMAGE, "Dashboard Icon").apply {
            getStyle().setWidth(CssLength.px(32f))
            getStyle().setHeight(CssLength.px(32f))
        }
        val titleHeading = Heading("Queue Telemetry", level = 2)
        val headerBadge = Badge("ONLINE", BootstrapType.SUCCESS)
        headerRow.addChild(iconView)
        headerRow.addChild(titleHeading, StackConstraint.weight(1f))
        headerRow.addChild(headerBadge)
        root.addChild(headerRow)

        val metricsWeights = floatArrayOf(1.4f, 1.0f, 1.1f)
        val metricCards = mutableListOf<StyledContainer>()
        val metricsRow = HStack(gapPx = 8)
        for ((colIdx, weight) in metricsWeights.withIndex()) {
            val metricCard = StyledContainer().apply {
                setPadding(EdgeInsets.all(10))
                setBackground(RgbaColor(45, 49, 58, 255))
                setBorder(1f, RgbaColor(72, 78, 90, 255))
            }
            val colStack = VStack(gapPx = 4)
            colStack.addChild(Text("Metric ${colIdx + 1}"))
            colStack.addChild(Heading("${(colIdx + 1) * 12}%", level = 3))
            metricCard.addChild(colStack)
            metricCards.add(metricCard)
            metricsRow.addChild(metricCard, StackConstraint.weight(weight))
        }
        root.addChild(metricsRow)

        fun buildLazyColumn(title: String): Pair<StyledContainer, LazyList> {
            val panel = StyledContainer().apply {
                setPadding(EdgeInsets.all(8))
                setBackground(RgbaColor(36, 40, 48, 255))
                setBorder(1f, RgbaColor(65, 72, 84, 255))
            }
            val stack = VStack(gapPx = 6).apply {
                setOverflow(OverflowBehavior.HIDDEN)
            }
            stack.addChild(Heading(title, level = 5))
            val lazy = LazyList(estimatedItemExtentPx = 44f)
            lazy.setItems(20, onLoadItem = { idx ->
                StyledContainer().apply {
                    setPadding(EdgeInsets(6, 10, 6, 10))
                    setBackground(RgbaColor(50, 55, 65, 255))
                    addChild(Text("$title Row #$idx"))
                }
            })
            stack.addChild(lazy, StackConstraint.weight(1f))
            panel.addChild(stack)
            return panel to lazy
        }

        val (leftPanel, leftLazy) = buildLazyColumn("Left Queue")
        val (rightPanel, rightLazy) = buildLazyColumn("Right Queue")

        val columnsRow = HStack(gapPx = 12)
        columnsRow.addChild(leftPanel, StackConstraint.weight(1f))
        columnsRow.addChild(rightPanel, StackConstraint.weight(1f))
        root.addChild(columnsRow, StackConstraint.weight(1f))

        val window = Window(title = "Bounded Flex & Dual LazyList Test", initialSize = Size(920, 540))
        window.setContent(root)
        window.show(factory)
        Win32NativeWindowHost.pumpVisualWindowEvents(24L)

        val imageBounds = (iconView.ensureNativeImpl() as DesktopBaseNativeImpl<*>).nativeWidget.bounds
        assertEquals(32, imageBounds.width, "ImageView CSS width=32px must override default width")
        assertEquals(32, imageBounds.height, "ImageView CSS height=32px must override default height")

        val col0Bounds = (metricCards[0].ensureNativeImpl() as DesktopBaseNativeImpl<*>).nativeWidget.bounds
        val col1Bounds = (metricCards[1].ensureNativeImpl() as DesktopBaseNativeImpl<*>).nativeWidget.bounds
        val col2Bounds = (metricCards[2].ensureNativeImpl() as DesktopBaseNativeImpl<*>).nativeWidget.bounds
        assertTrue(
            col0Bounds.width > col2Bounds.width && col2Bounds.width > col1Bounds.width,
            "Metric columns must follow 1.4 : 1.0 : 1.1 weights, got ${col0Bounds.width}, ${col1Bounds.width}, ${col2Bounds.width}"
        )
        val ratio01 = col0Bounds.width.toDouble() / col1Bounds.width.coerceAtLeast(1).toDouble()
        assertTrue(ratio01 in 1.32..1.48, "Expected 1.4:1.0 width ratio, got $ratio01")

        val leftLazyImpl = leftLazy.ensureNativeImpl() as WindowsLazyListNativeImpl
        val rightLazyImpl = rightLazy.ensureNativeImpl() as WindowsLazyListNativeImpl

        val hwnd = Win32NativeWindowHost.activeHwnd
        assertNotNull(hwnd)

        val (initialPixels, frameSize) = captureClientArgb(window)
        val bw = frameSize.first
        val bh = frameSize.second

        val leftHwnd = (leftLazyImpl.nativeWidget as Win32HwndHandle).hwnd
        val rightHwnd = (rightLazyImpl.nativeWidget as Win32HwndHandle).hwnd
        assertNotNull(leftHwnd)
        assertNotNull(rightHwnd)
        assertTrue((GetWindowLongW(leftHwnd, GWL_STYLE) and WS_VSCROLL) != 0, "Left LazyList HWND must have WS_VSCROLL")
        assertTrue((GetWindowLongW(rightHwnd, GWL_STYLE) and WS_VSCROLL) != 0, "Right LazyList HWND must have WS_VSCROLL")

        val leftRegion = hwndRectInHost(leftHwnd, hwnd)
        val rightRegion = hwndRectInHost(rightHwnd, hwnd)
        assertTrue(leftRegion[2] < rightRegion[0], "Expected left and right LazyList viewports side-by-side without overlap")
        assertTrue(leftRegion[3] - leftRegion[1] < bh, "Left LazyList viewport must be bounded within window height $bh")
        assertTrue(rightRegion[3] - rightRegion[1] < bh, "Right LazyList viewport must be bounded within window height $bh")

        val leftCenterX = (leftRegion[0] + leftRegion[2]) / 2
        val leftCenterY = (leftRegion[1] + leftRegion[3]) / 2
        val rightCenterX = (rightRegion[0] + rightRegion[2]) / 2
        val rightCenterY = (rightRegion[1] + rightRegion[3]) / 2

        val rightBandBefore = extractRectPixels(
            initialPixels, bw, bh,
            rightRegion[0], rightRegion[1], (rightRegion[2] - 20).coerceAtLeast(rightRegion[0] + 1), rightRegion[3]
        )

        // Wheel scroll strictly over the LEFT LazyList
        Win32NativeWindowHost.handleMouseWheelAt(hwnd, leftCenterX, leftCenterY, -120)
        Win32NativeWindowHost.pumpVisualWindowEvents(24L)

        val (afterLeftWheelPixels, _) = captureClientArgb(window)

        val leftBandBefore = extractRectPixels(
            initialPixels, bw, bh,
            leftRegion[0], leftRegion[1], (leftRegion[2] - 20).coerceAtLeast(leftRegion[0] + 1), leftRegion[3]
        )
        val leftBandAfter = extractRectPixels(
            afterLeftWheelPixels, bw, bh,
            leftRegion[0], leftRegion[1], (leftRegion[2] - 20).coerceAtLeast(leftRegion[0] + 1), leftRegion[3]
        )
        val rightBandAfter = extractRectPixels(
            afterLeftWheelPixels, bw, bh,
            rightRegion[0], rightRegion[1], (rightRegion[2] - 20).coerceAtLeast(rightRegion[0] + 1), rightRegion[3]
        )

        val leftOffsetAfterLeftWheel = leftLazyImpl.viewportEngine.scrollOffsetPx.toInt()
        val rightOffsetAfterLeftWheel = rightLazyImpl.viewportEngine.scrollOffsetPx.toInt()
        assertTrue(leftOffsetAfterLeftWheel > 0, "Expected wheel over left LazyList to increase left scrollOffsetPx, got $leftOffsetAfterLeftWheel")
        assertEquals(0, rightOffsetAfterLeftWheel, "Expected right LazyList scrollOffsetPx to remain 0")
        assertFalse(leftBandBefore.contentEquals(leftBandAfter), "Left LazyList pixels must change after scroll")
        assertTrue(rightBandBefore.contentEquals(rightBandAfter), "Right LazyList pixels must stay unchanged when scrolling Left LazyList")

        // Wheel scroll strictly over the RIGHT LazyList -> only Right scrolls while Left stays steady
        Win32NativeWindowHost.handleMouseWheelAt(hwnd, rightCenterX, rightCenterY, -120)
        Win32NativeWindowHost.pumpVisualWindowEvents(24L)

        val leftOffsetAfterRightWheel = leftLazyImpl.viewportEngine.scrollOffsetPx.toInt()
        val rightOffsetAfterRightWheel = rightLazyImpl.viewportEngine.scrollOffsetPx.toInt()
        assertEquals(leftOffsetAfterLeftWheel, leftOffsetAfterRightWheel, "Left LazyList offset must remain steady when wheeling Right LazyList")
        assertTrue(rightOffsetAfterRightWheel > 0, "Expected wheel over right LazyList to increase right scrollOffsetPx, got $rightOffsetAfterRightWheel")
    }

    @Test
    fun testWeightedHStackSingleRowWithPaddingAndHeadingNoWrap() = withVisualWindowHost { factory ->
        val root = VStack(gapPx = 8).apply {
            setOverflow(OverflowBehavior.HIDDEN)
        }
        val headerBox = StyledContainer().apply {
            setPadding(EdgeInsets(16, 20, 16, 20))
            setBackground(RgbaColor(42, 46, 54, 255))
            setBorder(1f, RgbaColor(80, 88, 102, 255), radiusPx = 6f)
        }
        val row = HStack(gapPx = 8).apply {
            setFlexWrap(FlexWrap.NOWRAP)
        }
        val heading = Heading("Comprehensive Batch Media Conversion Header Title", level = 2)
        val fillSpacer = Spacer.fill()
        val runBtn = Button("Run")
        val clearBtn = Button("Clear")
        row.addChild(heading, StackConstraint.weight(1f))
        row.addChild(fillSpacer)
        row.addChild(runBtn)
        row.addChild(clearBtn)
        headerBox.addChild(row)
        root.addChild(headerBox)

        val itemBox = StyledContainer().apply {
            setPadding(EdgeInsets.all(12))
            setBackground(RgbaColor(36, 40, 48, 255))
            setBorder(1f, RgbaColor(70, 78, 92, 255), radiusPx = 6f)
        }
        val indexBadge = Badge("#1", BootstrapType.SECONDARY)
        val longPathText = Text(
            "D:/Media/Incoming/Very/Long/Nested/Directory/Season_04/Episode_12_Remux_2160p_DV_HDR10_TrueHD_Atmos.mkv"
        ).apply {
            maxLines = 1
        }
        val durationBadge = Badge("22:17", BootstrapType.INFO)
        val itemRow = HStack(gapPx = 8).apply {
            addChild(indexBadge)
            addChild(longPathText, StackConstraint.weight(1f))
            addChild(durationBadge)
        }
        itemBox.addChild(itemRow)
        root.addChild(itemBox)

        val window = Window(title = "HStack Single Row & Padding Test", initialSize = Size(1520, 360))
        window.setContent(root)
        window.show(factory)
        Win32NativeWindowHost.pumpVisualWindowEvents(16L)

        val hwnd = Win32NativeWindowHost.activeHwnd
        assertNotNull(hwnd)
        val (pixels, _) = captureClientArgb(window)
        assertTrue(pixels.toSet().size > 2, "Expected rendered header row and buttons to produce distinct pixels")

        val boxBounds = (headerBox.ensureNativeImpl() as DesktopBaseNativeImpl<*>).getBounds()
        val headingBounds = (heading.ensureNativeImpl() as DesktopBaseNativeImpl<*>).getBounds()
        val runBounds = (runBtn.ensureNativeImpl() as DesktopBaseNativeImpl<*>).getBounds()
        val clearBounds = (clearBtn.ensureNativeImpl() as DesktopBaseNativeImpl<*>).getBounds()

        assertTrue(boxBounds.height < 96, "Single-row HStack with 32px vertical padding should not wrap onto extra rows (got height=${boxBounds.height})")
        assertTrue(headingBounds.x >= boxBounds.x + 20, "Heading left ${headingBounds.x} must respect 20px left padding of container ${boxBounds.x}")
        assertTrue(clearBounds.right <= boxBounds.right - 20, "Clear button right ${clearBounds.right} must respect 20px right padding of container ${boxBounds.right}")
        assertTrue(runBounds.y in boxBounds.y..(boxBounds.y + boxBounds.height - runBounds.height), "Run button must sit cleanly inside padded box vertically")
        assertTrue(
            abs((runBounds.y + runBounds.height / 2) - (headingBounds.y + headingBounds.height / 2)) <= 14,
            "Heading and action buttons must align on the same horizontal band"
        )

        val itemBoxBounds = (itemBox.ensureNativeImpl() as DesktopBaseNativeImpl<*>).getBounds()
        val badge1Bounds = (indexBadge.ensureNativeImpl() as DesktopBaseNativeImpl<*>).getBounds()
        val pathBounds = (longPathText.ensureNativeImpl() as DesktopBaseNativeImpl<*>).getBounds()
        val badge2Bounds = (durationBadge.ensureNativeImpl() as DesktopBaseNativeImpl<*>).getBounds()

        assertTrue(badge1Bounds.x >= itemBoxBounds.x + 12, "Left badge ${badge1Bounds.x} must respect 12px padding ${itemBoxBounds.x}")
        assertTrue(pathBounds.x >= badge1Bounds.right, "Weighted path ${pathBounds.x} must follow left badge ${badge1Bounds.right}")
        assertTrue(badge2Bounds.x >= pathBounds.right, "Right badge ${badge2Bounds.x} must sit after weighted path ${pathBounds.right}")
        assertTrue(badge2Bounds.right <= itemBoxBounds.right - 12, "Right badge ${badge2Bounds.right} must stay inside 12px right padding ${itemBoxBounds.right}")
        assertTrue(
            abs((badge1Bounds.y + badge1Bounds.height / 2) - (badge2Bounds.y + badge2Bounds.height / 2)) <= 4,
            "Badges [Badge, weighted Text, Badge] must render on the exact same horizontal row"
        )
    }

    @Test
    fun testOverflowHiddenRootWithBoundedScrollViewsClipAndIndependentWheelScroll() = withVisualWindowHost { factory ->
        val root = VStack(gapPx = 8).apply {
            setOverflow(OverflowBehavior.HIDDEN)
        }
        val header = StyledContainer().apply {
            setPadding(EdgeInsets(8, 12, 8, 12))
            setBackground(RgbaColor(48, 52, 62, 255))
            addChild(Text("Pinned Top Header"))
        }
        val leftSv = ScrollView()
        for (i in 0 until 15) {
            leftSv.addChild(
                StyledContainer().apply {
                    setPadding(EdgeInsets(6, 10, 6, 10))
                    setBackground(RgbaColor(54, 58, 68, 255))
                    addChild(Text("Left Item $i"))
                }
            )
        }
        val rightSv = ScrollView()
        for (i in 0 until 15) {
            rightSv.addChild(
                StyledContainer().apply {
                    setPadding(EdgeInsets(6, 10, 6, 10))
                    setBackground(RgbaColor(54, 58, 68, 255))
                    addChild(Text("Right Item $i"))
                }
            )
        }
        val bodyRow = HStack(gapPx = 12)
        bodyRow.addChild(leftSv, StackConstraint.weight(1f))
        bodyRow.addChild(rightSv, StackConstraint.weight(1f))

        val footer = StyledContainer().apply {
            setPadding(EdgeInsets(8, 12, 8, 12))
            setBackground(RgbaColor(28, 86, 140, 255))
            setBorder(1f, RgbaColor(90, 170, 240, 255))
            addChild(Text("Pinned Bottom Footer"))
        }

        root.addChild(header)
        root.addChild(bodyRow, StackConstraint.weight(1f))
        root.addChild(footer)

        val window = Window(title = "OverflowBehavior.HIDDEN & Dual ScrollView Test", initialSize = Size(820, 460))
        window.setContent(root)
        window.show(factory)
        Win32NativeWindowHost.pumpVisualWindowEvents(20L)

        val hwnd = Win32NativeWindowHost.activeHwnd
        assertNotNull(hwnd)
        val (pixelsBefore, frameSize) = captureClientArgb(window)
        val bw = frameSize.first
        val bh = frameSize.second

        val rootHwnd = ((root.ensureNativeImpl() as DesktopBaseNativeImpl<*>).nativeWidget as Win32HwndHandle).hwnd
        assertNotNull(rootHwnd)
        assertEquals(0, GetWindowLongW(rootHwnd, GWL_STYLE) and WS_VSCROLL, "OverflowBehavior.HIDDEN root VStack must NOT enable WS_VSCROLL")

        val leftImpl = leftSv.ensureNativeImpl() as DesktopScrollViewNativeImpl<*>
        val rightImpl = rightSv.ensureNativeImpl() as DesktopScrollViewNativeImpl<*>
        val leftHwnd = (leftImpl.nativeWidget as Win32HwndHandle).hwnd
        val rightHwnd = (rightImpl.nativeWidget as Win32HwndHandle).hwnd
        assertNotNull(leftHwnd)
        assertNotNull(rightHwnd)
        assertTrue((GetWindowLongW(leftHwnd, GWL_STYLE) and WS_VSCROLL) != 0, "Left ScrollView must have WS_VSCROLL")
        assertTrue((GetWindowLongW(rightHwnd, GWL_STYLE) and WS_VSCROLL) != 0, "Right ScrollView must have WS_VSCROLL")

        val leftBounds = leftImpl.nativeWidget.bounds
        val rightBounds = rightImpl.nativeWidget.bounds
        val footerBounds = (footer.ensureNativeImpl() as DesktopBaseNativeImpl<*>).nativeWidget.bounds

        assertTrue(leftBounds.bottom <= footerBounds.y, "Left ScrollView bottom ${leftBounds.bottom} must stay above footer top ${footerBounds.y}")
        assertTrue(rightBounds.bottom <= footerBounds.y, "Right ScrollView bottom ${rightBounds.bottom} must stay above footer top ${footerBounds.y}")

        val footerPixelsBefore = extractRectPixels(
            pixelsBefore, bw, bh,
            footerBounds.x, footerBounds.y, footerBounds.right, footerBounds.bottom
        )

        // Programmatically scroll Left ScrollView -> footer pixels must stay untouched (retained HWND WS_CLIPCHILDREN clipping)
        leftSv.scrollTo(0, 160)
        Win32NativeWindowHost.pumpVisualWindowEvents(20L)
        val (pixelsAfterLeftScrollTo, _) = captureClientArgb(window)
        val footerPixelsAfter = extractRectPixels(
            pixelsAfterLeftScrollTo, bw, bh,
            footerBounds.x, footerBounds.y, footerBounds.right, footerBounds.bottom
        )
        assertTrue(footerPixelsBefore.contentEquals(footerPixelsAfter), "Footer pixels must remain un-occluded when ScrollView scrolls")

        // Reset left to 0 and test independent wheel scrolling at left ScrollView center
        leftSv.scrollTo(0, 0)
        Win32NativeWindowHost.pumpVisualWindowEvents(16L)
        val leftRect = hwndRectInHost(leftHwnd, hwnd)
        val leftX = (leftRect[0] + leftRect[2]) / 2
        val leftY = (leftRect[1] + leftRect[3]) / 2
        Win32NativeWindowHost.handleMouseWheelAt(hwnd, leftX, leftY, -120)
        Win32NativeWindowHost.pumpVisualWindowEvents(24L)

        assertTrue(leftImpl.currentScrollY() > 0, "Expected mouse wheel over left ScrollView to scroll left ScrollView")
        assertEquals(0, rightImpl.currentScrollY(), "Expected right ScrollView to remain at y=0 when wheel targets left ScrollView")
    }

    @Test
    fun testMultiWindowCloseDoesNotCloseRemainingSecondaryWindowAndLazyGridTreeRendersGenerally() = withVisualWindowHost { factory ->
        val primaryRoot = VStack(gapPx = 8).apply {
            setOverflow(OverflowBehavior.HIDDEN)
        }
        val rootNode = LazyGridTreeNode<String>(
            id = "root",
            keyText = "config",
            isBranch = true,
            isExpanded = true
        ).apply {
            setTextCell(1, "{2 keys}")
            setTextCell(2, "OBJECT")
            setTextCell(3, "+")
            children.add(
                LazyGridTreeNode<String>(id = "child-1", keyText = "bitrate").apply {
                    setTextCell(1, "4500")
                    setTextCell(2, "INT")
                }
            )
            children.add(
                LazyGridTreeNode<String>(id = "child-2", keyText = "codec").apply {
                    setTextCell(1, "h264")
                    setTextCell(2, "STRING")
                }
            )
        }
        val tree = LazyGridTree<String>().apply {
            setRootNodes(listOf(rootNode))
        }
        primaryRoot.addChild(Heading("Tree Container", level = 4))
        primaryRoot.addChild(tree, StackConstraint.weight(1f))

        val primaryWin = Window(title = "Primary Tree Window", initialSize = Size(640, 400))
        primaryWin.setContent(primaryRoot)
        primaryWin.show(factory)

        val secondaryRoot = VStack(gapPx = 8).apply {
            addChild(Text("Secondary Window Content"))
        }
        val secondaryWin = Window(title = "Secondary Window", initialSize = Size(480, 320))
        secondaryWin.setContent(secondaryRoot)
        secondaryWin.show(factory)
        Win32NativeWindowHost.pumpVisualWindowEvents(24L)

        val primaryHwnd = Win32NativeWindowHost.activeHwnd
        assertNotNull(primaryHwnd)
        val (treePixels, _) = captureClientArgb(primaryWin)
        assertTrue(treePixels.toSet().size > 2, "Expected LazyGridTree inside VStack to render visible non-uniform pixels")

        val secImpl = secondaryWin.ensureNativeImpl() as WindowsWindowNativeImpl
        val secHwnd = Win32NativeWindowHost.resolveHwndForWindow(secImpl)
        assertNotNull(secHwnd)
        assertTrue(IsWindow(secHwnd) != 0 && IsWindowVisible(secHwnd) != 0)

        // Closing main window while secondary window is still visible & non-disposed must keep secondary window alive!
        primaryWin.close()
        Win32NativeWindowHost.pumpVisualWindowEvents(16L)

        assertTrue(IsWindow(secHwnd) != 0, "Secondary HWND must NOT be destroyed when main window closes")
        assertTrue(IsWindowVisible(secHwnd) != 0, "Secondary window must remain visible")
        assertFalse(secImpl.isDisposed, "Secondary WindowsWindowNativeImpl must NOT be disposed")
    }
}
