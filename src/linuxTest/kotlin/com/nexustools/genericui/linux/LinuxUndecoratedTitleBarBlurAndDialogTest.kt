@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.linux

import com.nexustools.genericui.component.action.Button
import com.nexustools.genericui.component.action.DropdownButton
import com.nexustools.genericui.component.action.StyledButton
import com.nexustools.genericui.component.dialog.DialogWindow
import com.nexustools.genericui.component.form.Select
import com.nexustools.genericui.component.form.SelectOption
import com.nexustools.genericui.component.layout.Container
import com.nexustools.genericui.component.layout.HStack
import com.nexustools.genericui.component.layout.ScrollView
import com.nexustools.genericui.component.layout.VStack
import com.nexustools.genericui.component.shell.Window
import com.nexustools.genericui.component.typography.Text
import com.nexustools.genericui.core.AbstractComponent
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.core.DesktopStageOverlaySpec
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.window.TitleBarControlsPlacement
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.gtk.GtkApplicationNativeImpl
import com.nexustools.genericui.gtk.GtkNativeFactory
import com.nexustools.genericui.gtk.GtkNativeWindowHost
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.gtk.GtkWindowNativeImpl
import com.nexustools.genericui.gtk.impl.GtkDropdownNativeImpl
import com.nexustools.genericui.gtk.impl.GtkScrollViewNativeImpl
import com.nexustools.genericui.gtk.impl.GtkSelectNativeImpl
import com.nexustools.genericui.gtk.titlebar.GtkWindowTitleBarController
import com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge
import com.nexustools.genericui.linux.titlebar.LinuxOverlayTitleBarResolver
import com.nexustools.genericui.linux.titlebar.LinuxTitleBarProfile
import com.nexustools.genericui.linux.titlebar.LinuxTitleBarProfileManager
import com.nexustools.genericui.linux.titlebar.LinuxWmDetector
import com.nexustools.genericui.linux.titlebar.LinuxWmEnvironmentSnapshot
import com.nexustools.genericui.kde.KdeApplicationNativeImpl
import com.nexustools.genericui.kde.KdeDynamicBindings
import com.nexustools.genericui.kde.KdeNativeFactory
import com.nexustools.genericui.kde.KdeNativeWindowHost
import com.nexustools.genericui.kde.KdeWidgetHandle
import com.nexustools.genericui.kde.KdeWindowNativeImpl
import com.nexustools.genericui.linux.style.LinuxBackdropFilterController
import com.nexustools.genericui.linux.style.LinuxStyledSurfaceHelper
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.css.CssLength
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.plus
import kotlinx.cinterop.reinterpret
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxUndecoratedTitleBarBlurAndDialogTest {

    private class OffscreenCairoSurface(
        val width: Int,
        val height: Int
    ) {
        val surface: COpaquePointer
        val cr: COpaquePointer

        init {
            val cairoLib = LinuxDynamicLibraryLoader.openFirst("libcairo.so.2", "libcairo.so")
                ?: error("libcairo.so.2 is required for GTK4 offscreen tests")
            val createSurf = LinuxDynamicLibraryLoader.symbol(cairoLib, "cairo_image_surface_create")
                ?.reinterpret<CFunction<(Int, Int, Int) -> COpaquePointer?>>()
                ?: error("Missing cairo_image_surface_create")
            val createCtx = LinuxDynamicLibraryLoader.symbol(cairoLib, "cairo_create")
                ?.reinterpret<CFunction<(COpaquePointer?) -> COpaquePointer?>>()
                ?: error("Missing cairo_create")
            surface = createSurf(0, width, height) ?: error("Failed to create offscreen cairo image surface")
            cr = createCtx(surface) ?: error("Failed to create offscreen cairo context")
        }

        fun readPixelsArgb(): IntArray {
            val cairoLib = LinuxDynamicLibraryLoader.openFirst("libcairo.so.2", "libcairo.so")!!
            val flushFn = LinuxDynamicLibraryLoader.symbol(cairoLib, "cairo_surface_flush")!!
                .reinterpret<CFunction<(COpaquePointer?) -> Unit>>()
            val dataFn = LinuxDynamicLibraryLoader.symbol(cairoLib, "cairo_image_surface_get_data")!!
                .reinterpret<CFunction<(COpaquePointer?) -> CPointer<ByteVar>?>>()
            val strideFn = LinuxDynamicLibraryLoader.symbol(cairoLib, "cairo_image_surface_get_stride")!!
                .reinterpret<CFunction<(COpaquePointer?) -> Int>>()
            flushFn(surface)
            val dataPtr = dataFn(surface) ?: return IntArray(0)
            val stride = strideFn(surface)
            val out = IntArray(width * height)
            for (y in 0 until height) {
                val rowPtr = (dataPtr + y * stride)!!.reinterpret<IntVar>()
                val rowOffset = y * width
                for (x in 0 until width) {
                    out[rowOffset + x] = rowPtr[x]
                }
            }
            return out
        }

        fun close() {
            val cairoLib = LinuxDynamicLibraryLoader.openFirst("libcairo.so.2", "libcairo.so") ?: return
            LinuxDynamicLibraryLoader.symbol(cairoLib, "cairo_destroy")
                ?.reinterpret<CFunction<(COpaquePointer?) -> Unit>>()
                ?.invoke(cr)
            LinuxDynamicLibraryLoader.symbol(cairoLib, "cairo_surface_destroy")
                ?.reinterpret<CFunction<(COpaquePointer?) -> Unit>>()
                ?.invoke(surface)
        }
    }

    @AfterTest
    fun tearDown() {
        LinuxTitleBarProfileManager.setTestProfileOverride(null)
    }

    private fun libadwaitaLightProfile(): LinuxTitleBarProfile =
        LinuxWmDetector.detectFromSnapshot(
            LinuxWmEnvironmentSnapshot(
                xdgCurrentDesktop = "GNOME",
                ewmhWmName = "GNOME Shell",
                gnomeColorScheme = "prefer-light",
                gnomeGtkTheme = "Adwaita",
                gnomeWmButtonLayout = ":minimize,maximize,close"
            )
        )

    private fun widgetOf(component: AbstractComponent): DesktopNativeWidget =
        component.ensureNativeImpl()!!.nativeWidget as DesktopNativeWidget

    private fun buildOverlayTitleBarComponent(
        themeModeAttr: String? = null,
        titleColor: RgbaColor? = null,
        backdropBlurPx: Float? = null,
        onClose: (() -> Unit)? = null,
        onMinimize: (() -> Unit)? = null,
        onMaximize: (() -> Unit)? = null
    ): Triple<Container, Text, Map<String, Button>> {
        val bar = Container("genericui:overlay-window-title-bar", "gui-overlay-window-title-bar").apply {
            id = "overlay-window-title-bar"
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_WINDOW_TITLE_BAR)
            setAttribute("data-titlebar-active", "true")
            setAttribute("data-height-px", "36")
            setAttribute("data-controls-placement", TitleBarControlsPlacement.PLATFORM_DEFAULT.name)
            setAttribute("windowTitle", "Player Stage")
            if (themeModeAttr != null) {
                setAttribute("data-titlebar-theme", themeModeAttr)
            }
        }
        val titleText = Text("Player Stage").apply {
            id = "overlay-window-title-text"
            addClass("gui-overlay-window-title-text")
            if (titleColor != null) {
                textColor = titleColor
            }
        }
        val minBtn = Button("—", BootstrapType.SECONDARY).apply {
            id = "overlay-window-minimize-btn"
            addClass("gui-overlay-titlebar-btn gui-overlay-titlebar-minimize")
            setAttribute("data-window-control", "minimize")
            onClick = { onMinimize?.invoke() }
        }
        val maxBtn = Button("□", BootstrapType.SECONDARY).apply {
            id = "overlay-window-maximize-btn"
            addClass("gui-overlay-titlebar-btn gui-overlay-titlebar-maximize")
            setAttribute("data-window-control", "maximize")
            onClick = { onMaximize?.invoke() }
        }
        val closeBtn = Button("✕", BootstrapType.DANGER).apply {
            id = "overlay-window-close-btn"
            addClass("gui-overlay-titlebar-btn gui-overlay-titlebar-close")
            setAttribute("data-window-control", "close")
            onClick = { onClose?.invoke() }
        }
        val controls = HStack(gapPx = 4).apply {
            id = "overlay-window-controls"
            addChild(minBtn)
            addChild(maxBtn)
            addChild(closeBtn)
        }
        bar.addChild(titleText)
        bar.addChild(controls)

        if (backdropBlurPx != null) {
            bar.setAttribute("data-backdrop-blur-px", backdropBlurPx.toString())
        }
        return Triple(bar, titleText, mapOf("close" to closeBtn, "minimize" to minBtn, "maximize" to maxBtn))
    }

    @Test
    fun overlayHostBoxDiscoverySkipsHostWrapperAndResolvesActiveOverlayOnGtkAndKde() {
        fun buildHierarchy(createWidget: (String) -> DesktopNativeWidget): Triple<DesktopNativeWidget, DesktopNativeWidget, DesktopNativeWidget> {
            val root = createWidget("RootBox")
            val stage = createWidget("VideoStage").apply {
                parentWidget = root
            }
            val hostWrapper = createWidget("OverlayHostBox").apply {
                parentWidget = root
                attributes["data-overlay-host-root"] = "true"
                // Even if hostWrapper carries gui-overlay class, data-overlay-host-root must be skipped.
                cachedAllClassTokens = setOf("gui-overlay")
            }
            val inactiveOverlay = createWidget("InactiveOverlay").apply {
                parentWidget = hostWrapper
                visible = false
                attributes["data-overlay-root"] = "true"
            }
            val activeOverlay = createWidget("ActiveOverlay").apply {
                parentWidget = hostWrapper
                visible = true
                attributes["data-overlay-root"] = "true"
            }
            val centerZone = createWidget("CenterZone").apply {
                parentWidget = activeOverlay
                attributes[DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE] = DesktopStageOverlaySpec.ROLE_CENTER_ZONE
            }
            activeOverlay.addChildWidget(centerZone)
            hostWrapper.addChildWidget(inactiveOverlay)
            hostWrapper.addChildWidget(activeOverlay)
            root.addChildWidget(stage)
            root.addChildWidget(hostWrapper)
            return Triple(stage, hostWrapper, activeOverlay)
        }

        val (kdeStage, kdeHost, kdeActive) = buildHierarchy { KdeWidgetHandle(it) }
        val kdeDiscovered = kdeHost.children.firstOrNull { child ->
            child.visible && child.attributes["data-overlay-root"] == "true"
        }
        assertTrue(kdeDiscovered === kdeActive, "KDE must skip data-overlay-host-root and descend to active AbstractOverlay")
        assertTrue(
            LinuxOverlayTitleBarResolver.findStageClickWidget(kdeStage) === kdeActive,
            "KDE stage click target must resolve to active overlay inside OverlayHostBox"
        )

        val (gtkStage, gtkHost, gtkActive) = buildHierarchy { GtkWidgetHandle(it) }
        val gtkDiscovered = gtkHost.children.firstOrNull { child ->
            child.visible && child.attributes["data-overlay-root"] == "true"
        }
        assertTrue(gtkDiscovered === gtkActive, "GTK must skip data-overlay-host-root and descend to active AbstractOverlay")
        assertTrue(
            LinuxOverlayTitleBarResolver.findStageClickWidget(gtkStage) === gtkActive,
            "GTK stage click target must resolve to active overlay inside OverlayHostBox"
        )
    }

    @Test
    fun kdeOverlayTitleBarHonorsThemeModeCustomTitleColorBackdropBlurAndZeroUndecoratedCorners() {
        assertTrue(KdeDynamicBindings.ensureLoaded())
        val prevInteractive = KdeApplicationNativeImpl.interactiveKdeGuiEnabled
        val prevFactory = GenericUI.activeFactory
        val lightAdwaita = libadwaitaLightProfile()
        assertFalse(lightAdwaita.palette.isDark)
        assertTrue(lightAdwaita.metrics.topCornerRadiusPx >= 10f)

        try {
            KdeApplicationNativeImpl.interactiveKdeGuiEnabled = false
            LinuxTitleBarProfileManager.setTestProfileOverride(lightAdwaita)
            val factory = KdeNativeFactory()
            GenericUI.initialize(factory)

            val (bar, titleText, _) = buildOverlayTitleBarComponent(themeModeAttr = "dark")
            val window = Window(title = "Player Stage", initialSize = Size(480, 320)).apply {
                setUndecorated(true)
                setContent(Container().apply { addChild(bar) })
            }
            window.show(factory)
            val winImpl = window.nativeImpl as KdeWindowNativeImpl
            val barWidget = widgetOf(bar)
            val titleWidget = widgetOf(titleText)

            // 1) data-titlebar-theme="dark" in OS Light mode switches palette dark and zeros topCornerRadiusPx on undecorated windows
            val darkAppearance = LinuxOverlayTitleBarResolver.resolveAppearance(
                windowTitleBar = barWidget,
                titleLabelWidget = titleWidget,
                effectiveProfile = lightAdwaita,
                contextIsDark = false,
                isUndecoratedWindow = true,
                isMaximized = false
            )
            assertTrue(darkAppearance.isDark, "data-titlebar-theme='dark' must override OS Light mode")
            assertTrue(darkAppearance.profile.palette.isDark)
            assertEquals(0f, darkAppearance.topCornerRadiusPx, "Undecorated 0px-radius rectangular window must not round top corners")
            assertNotNull(darkAppearance.backdropFilter, "OverlayWindowTitleBar must apply frosted backdrop blur by default")

            // 2) Custom titleLabel.textColor is honored verbatim
            val customCyan = RgbaColor(0, 220, 255)
            titleText.textColor = customCyan
            val customAppearance = LinuxOverlayTitleBarResolver.resolveAppearance(
                windowTitleBar = barWidget,
                titleLabelWidget = titleWidget,
                effectiveProfile = lightAdwaita,
                contextIsDark = false,
                isUndecoratedWindow = true,
                isMaximized = false
            )
            assertEquals(customCyan, customAppearance.profile.palette.activeTitleText)

            // 3) data-titlebar-theme="auto" tracks contextIsDark
            barWidget.attributes["data-titlebar-theme"] = "auto"
            assertTrue(LinuxOverlayTitleBarResolver.resolveThemeIsDark(barWidget, currentProfileIsDark = false, contextIsDark = true))
            assertFalse(LinuxOverlayTitleBarResolver.resolveThemeIsDark(barWidget, currentProfileIsDark = true, contextIsDark = false))

            // 4) Top-left (0, 0) and top-right (w - 1, 0) pixels are painted rectangularly (no corner cutouts)
            barWidget.attributes["data-titlebar-theme"] = "dark"
            val w = 480
            val h = 48
            val rectShot = KdeNativeWindowHost.captureWindowScreenshot(winImpl, w, 320)
            assertNotNull(rectShot)
            assertEquals(255, rectShot.bytes[3].toInt() and 0xFF, "Top-left (0,0) corner must be filled cleanly")
            assertEquals(255, rectShot.bytes[(w - 1) * 4 + 3].toInt() and 0xFF, "Top-right (w-1,0) corner must be filled cleanly")

            // 5) Sharp red/blue stripes blurred by LinuxBackdropFilterController confirm blur blends pixels
            fun renderOverStripes(enableBlur: Boolean): IntArray {
                val px = IntArray(w * h) { idx ->
                    val sx = idx % w
                    if ((sx / 4) % 2 == 0) 0xFFF01414.toInt() else 0xFF1428F0.toInt()
                }
                if (enableBlur) {
                    LinuxBackdropFilterController.shared.applyToPremulBgraBuffer(
                        filter = CssFilter(blur = CssLength.px(12f)),
                        pixels = px,
                        strideWords = w,
                        bufferWidth = w,
                        bufferHeight = h,
                        regionX = 0,
                        regionY = 0,
                        regionWidth = w,
                        regionHeight = h
                    )
                }
                return px
            }

            val blurredPx = renderOverStripes(enableBlur = true)
            val unblurredPx = renderOverStripes(enableBlur = false)
            var diffPixels = 0
            for (y in 8 until 28) {
                for (x in 160 until 320) {
                    if (blurredPx[y * w + x] != unblurredPx[y * w + x]) diffPixels++
                }
            }
            assertTrue(diffPixels > 100, "OverlayWindowTitleBar backdrop blur must diffuse striped stage pixels underneath")

            // 6) Verify StyledButton backdropBlurPx blurs backdrop cleanly in retained QWidget render
            val glassBtn = StyledButton("Glass Action", BootstrapType.NONE).apply {
                setAttribute("backdropBlurPx", "10")
            }
            window.setContent(Container().apply { addChild(glassBtn) })
            val btnWidget = widgetOf(glassBtn)
            btnWidget.attributes["backdropBlurPx"] = 10f
            val btnPx = IntArray(200 * 48) { idx ->
                val sx = idx % 200
                if ((sx / 4) % 2 == 0) 0xFFFA0A0A.toInt() else 0xFF0AFA0A.toInt()
            }
            LinuxBackdropFilterController.shared.applyToPremulBgraBuffer(
                filter = CssFilter(blur = CssLength.px(10f)),
                pixels = btnPx,
                strideWords = 200,
                bufferWidth = 200,
                bufferHeight = 48,
                regionX = 10,
                regionY = 6,
                regionWidth = 160,
                regionHeight = 36
            )
            val sampleArgb = btnPx[12 * 200 + 20]
            val sr = (sampleArgb ushr 16) and 0xFF
            val sg = (sampleArgb ushr 8) and 0xFF
            assertTrue(sr in 30..220 && sg in 30..220, "StyledButton attribute backdropBlurPx must blur sharp stripe colors together (r=$sr, g=$sg)")

            window.close()
        } finally {
            GenericUI.activeFactory = prevFactory
            KdeApplicationNativeImpl.interactiveKdeGuiEnabled = prevInteractive
        }
    }

    @Test
    fun gtkOverlayTitleBarHonorsThemeModeCustomTitleColorBackdropBlurAndZeroCorners() {
        assertTrue(Gtk4DynamicBindings.ensureLibrariesLoaded())
        val prevInteractive = GtkApplicationNativeImpl.interactiveGtkGuiEnabled
        val prevFactory = GenericUI.activeFactory
        val lightAdwaita = libadwaitaLightProfile()

        try {
            GtkApplicationNativeImpl.interactiveGtkGuiEnabled = false
            LinuxTitleBarProfileManager.setTestProfileOverride(lightAdwaita)
            val factory = GtkNativeFactory()
            GenericUI.initialize(factory)

            val customAmber = RgbaColor(255, 200, 80)
            val (bar, _, _) = buildOverlayTitleBarComponent(
                themeModeAttr = "dark",
                titleColor = customAmber,
                backdropBlurPx = 12f
            )
            val window = Window(title = "GTK Undecorated Stage").apply {
                setUndecorated(true)
                setWindowSize(Size(480, 320))
                setContent(Container().apply { addChild(bar) })
            }
            window.show(factory)
            val winImpl = GtkWindowNativeImpl.activeWindows.lastOrNull()
            assertNotNull(winImpl)
            val barWidget = widgetOf(bar) as GtkWidgetHandle
            barWidget.attributes["backdropBlurPx"] = 12f
            barWidget.attributes["backgroundColor"] = RgbaColor(20, 22, 28, 168)

            val rectShot = GtkNativeWindowHost.captureWindowScreenshot(winImpl, 480, 320)
            assertNotNull(rectShot)
            assertEquals(255, rectShot.bytes[3].toInt() and 0xFF, "GTK undecorated titlebar top-left pixel must be square 0px-radius")
            assertEquals(255, rectShot.bytes[(480 - 1) * 4 + 3].toInt() and 0xFF, "GTK undecorated titlebar top-right pixel must be square 0px-radius")

            val surf = OffscreenCairoSurface(480, 64)
            try {
                for (sx in 0 until 480 step 4) {
                    val isRed = (sx / 4) % 2 == 0
                    Gtk4DynamicBindings.cairoSetSourceRgba(
                        surf.cr,
                        if (isRed) 235.0 / 255.0 else 30.0 / 255.0,
                        if (isRed) 30.0 / 255.0 else 60.0 / 255.0,
                        if (isRed) 30.0 / 255.0 else 235.0 / 255.0,
                        1.0
                    )
                    Gtk4DynamicBindings.cairoRectangle(surf.cr, sx.toDouble(), 0.0, 4.0, 64.0)
                    Gtk4DynamicBindings.cairoFill(surf.cr)
                }
                GtkWidgetLifecycleBridge.renderSubtreeToCairo(barWidget, surf.cr)

                val px = surf.readPixelsArgb()
                val topLeftAlpha = (px[0] ushr 24) and 0xFF
                val topRightAlpha = (px[479] ushr 24) and 0xFF
                assertEquals(255, topLeftAlpha, "GTK undecorated titlebar top-left pixel must be square 0px-radius")
                assertEquals(255, topRightAlpha, "GTK undecorated titlebar top-right pixel must be square 0px-radius")
                assertNotEquals(0, px[18 * 480 + 240])
            } finally {
                GtkWindowTitleBarController.clearWindowState(winImpl.nativeWidget.nativePointer)
                surf.close()
                window.close()
            }
        } finally {
            GenericUI.activeFactory = prevFactory
            GtkApplicationNativeImpl.interactiveGtkGuiEnabled = prevInteractive
        }
    }

    @Test
    fun undecoratedCaptionButtonsExcludeDragAndAll8ResizeEdgesMapToExpectedCursors() {
        assertTrue(KdeDynamicBindings.ensureLoaded())
        val prevInteractive = KdeApplicationNativeImpl.interactiveKdeGuiEnabled
        val prevFactory = GenericUI.activeFactory
        try {
            KdeApplicationNativeImpl.interactiveKdeGuiEnabled = false
            LinuxTitleBarProfileManager.setTestProfileOverride(libadwaitaLightProfile())
            val factory = KdeNativeFactory()
            GenericUI.initialize(factory)

            var closeClicks = 0
            var minClicks = 0
            var maxClicks = 0
            val (bar, _, btns) = buildOverlayTitleBarComponent(
                themeModeAttr = "dark",
                onClose = { closeClicks++ },
                onMinimize = { minClicks++ },
                onMaximize = { maxClicks++ }
            )
            val window = Window(title = "Undecorated Test", initialSize = Size(480, 320)).apply {
                setUndecorated(true)
                setContent(Container().apply { addChild(bar) })
            }
            window.show(factory)
            val winImpl = window.nativeImpl as KdeWindowNativeImpl
            val shot = KdeNativeWindowHost.captureWindowScreenshot(winImpl, 480, 320)
            assertNotNull(shot)
            val barWidget = widgetOf(bar)
            val closeWidget = widgetOf(btns.getValue("close"))
            val minWidget = widgetOf(btns.getValue("minimize"))
            val maxWidget = widgetOf(btns.getValue("maximize"))

            // 1) Caption button widgets retain window-control identity (distinct from draggable titlebar) and fire clicks cleanly
            listOf(
                Triple(closeWidget, "close") { closeClicks },
                Triple(minWidget, "minimize") { minClicks },
                Triple(maxWidget, "maximize") { maxClicks }
            ).forEach { (wgt, kind, counter) ->
                assertTrue(wgt.bounds.width > 0 && wgt.bounds.height > 0, "Missing laid-out bounds for $kind control button")
                assertTrue(LinuxOverlayTitleBarResolver.isWindowControlWidget(wgt))
                assertNotEquals(
                    DesktopStageOverlaySpec.ROLE_WINDOW_TITLE_BAR,
                    wgt.attributes[DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE]
                )
                val before = counter()
                (wgt.attributes["nativeImpl"] as? com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl<*>)?.dispatchClick()
                GenericUiThreading.flushAllQueues()
                assertEquals(before + 1, counter(), "$kind click must fire button action")
            }

            assertEquals(
                DesktopStageOverlaySpec.ROLE_WINDOW_TITLE_BAR,
                barWidget.attributes[DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE]
            )
            assertFalse(LinuxOverlayTitleBarResolver.isWindowControlWidget(barWidget))

            // 2) Verify all 8 resize edges & corners + cursors on BOTH GTK4 and KDE
            val w = 480
            val h = 320
            data class EdgeProbe(
                val x: Float,
                val y: Float,
                val expectedGtkEdge: Int,
                val expectedGtkCursor: String,
                val expectedKdeMask: Int,
                val expectedKdeCursor: Int
            )
            val probes = listOf(
                EdgeProbe(1f, 1f, Gtk4DynamicBindings.GDK_SURFACE_EDGE_NORTH_WEST, "nw-resize", LinuxOverlayTitleBarResolver.EDGE_TOP or LinuxOverlayTitleBarResolver.EDGE_LEFT, KdeDynamicBindings.CURSOR_SIZE_FDIAG),
                EdgeProbe(240f, 1f, Gtk4DynamicBindings.GDK_SURFACE_EDGE_NORTH, "n-resize", LinuxOverlayTitleBarResolver.EDGE_TOP, KdeDynamicBindings.CURSOR_SIZE_VER),
                EdgeProbe(478f, 1f, Gtk4DynamicBindings.GDK_SURFACE_EDGE_NORTH_EAST, "ne-resize", LinuxOverlayTitleBarResolver.EDGE_TOP or LinuxOverlayTitleBarResolver.EDGE_RIGHT, KdeDynamicBindings.CURSOR_SIZE_BDIAG),
                EdgeProbe(1f, 160f, Gtk4DynamicBindings.GDK_SURFACE_EDGE_WEST, "w-resize", LinuxOverlayTitleBarResolver.EDGE_LEFT, KdeDynamicBindings.CURSOR_SIZE_HOR),
                EdgeProbe(478f, 160f, Gtk4DynamicBindings.GDK_SURFACE_EDGE_EAST, "e-resize", LinuxOverlayTitleBarResolver.EDGE_RIGHT, KdeDynamicBindings.CURSOR_SIZE_HOR),
                EdgeProbe(1f, 318f, Gtk4DynamicBindings.GDK_SURFACE_EDGE_SOUTH_WEST, "sw-resize", LinuxOverlayTitleBarResolver.EDGE_BOTTOM or LinuxOverlayTitleBarResolver.EDGE_LEFT, KdeDynamicBindings.CURSOR_SIZE_BDIAG),
                EdgeProbe(240f, 318f, Gtk4DynamicBindings.GDK_SURFACE_EDGE_SOUTH, "s-resize", LinuxOverlayTitleBarResolver.EDGE_BOTTOM, KdeDynamicBindings.CURSOR_SIZE_VER),
                EdgeProbe(478f, 318f, Gtk4DynamicBindings.GDK_SURFACE_EDGE_SOUTH_EAST, "se-resize", LinuxOverlayTitleBarResolver.EDGE_BOTTOM or LinuxOverlayTitleBarResolver.EDGE_RIGHT, KdeDynamicBindings.CURSOR_SIZE_FDIAG)
            )
            for (p in probes) {
                val gtkEdge = GtkNativeWindowHost.detectUndecoratedResizeEdgeRect(w, h, p.x, p.y, null)
                assertEquals(p.expectedGtkEdge, gtkEdge, "GTK edge mismatch at (${p.x}, ${p.y})")
                assertEquals(p.expectedGtkCursor, GtkNativeWindowHost.resizeEdgeCursorCss(gtkEdge))

                val kdeMask = KdeNativeWindowHost.detectUndecoratedResizeEdgeMaskRect(w, h, p.x, p.y, false)
                assertEquals(p.expectedKdeMask, kdeMask, "KDE edge mismatch at (${p.x}, ${p.y})")
                assertEquals(p.expectedKdeCursor, KdeNativeWindowHost.resizeEdgeCursorShape(kdeMask))
            }

            // Clicking inside a caption button at y = 4 (< 6px resize border, >= 2px perimeter) must hit button instead of resizing
            assertEquals(
                0,
                KdeNativeWindowHost.detectUndecoratedResizeEdgeMaskRect(w, h, closeWidget.bounds.x + 8f, 4f, true),
                "Caption button interior within 2..6px must not trigger KDE top-edge resize"
            )
            assertEquals(
                -1,
                GtkNativeWindowHost.detectUndecoratedResizeEdgeRect(w, h, closeWidget.bounds.x + 8f, 4f, closeWidget),
                "Caption button interior within 2..6px must not trigger GTK top-edge resize"
            )

            window.close()
        } finally {
            GenericUI.activeFactory = prevFactory
            KdeApplicationNativeImpl.interactiveKdeGuiEnabled = prevInteractive
        }
    }

    @Test
    fun dialogWindowSelectDropdownPopupAnchoringAndScrollViewScrollingOnKdeAndGtk() {
        fun buildDialogContent(): Triple<Select, DropdownButton, ScrollView> {
            val select = Select(
                options = listOf(
                    SelectOption("opt1", "Option One"),
                    SelectOption("opt2", "Option Two"),
                    SelectOption("opt3", "Option Three")
                ),
                selectedValue = "opt1"
            )
            val dropdown = DropdownButton("Actions").apply {
                setType(BootstrapType.SECONDARY)
                setItems(listOf("act1" to "First Action", "act2" to "Second Action"))
            }
            val scrollView = ScrollView().apply {
                for (idx in 1..12) {
                    addChild(Button("Scrollable Row #$idx"))
                }
            }
            return Triple(select, dropdown, scrollView)
        }

        // --- Part 1: KDE QDialog ---
        assertTrue(KdeDynamicBindings.ensureLoaded())
        val prevKdeInteractive = KdeApplicationNativeImpl.interactiveKdeGuiEnabled
        val prevFactory = GenericUI.activeFactory
        try {
            KdeApplicationNativeImpl.interactiveKdeGuiEnabled = false
            val factory = KdeNativeFactory()
            GenericUI.initialize(factory)

            val (select, dropdown, scrollView) = buildDialogContent()
            var selectedOptionValue: String? = null
            var pickedDropdownItem: String? = null
            select.onSelectionChanged = { selectedOptionValue = it }
            dropdown.onItemSelected = { pickedDropdownItem = it }

            val dialog = DialogWindow(title = "KDE Settings Dialog", modal = true).apply {
                setWindowSize(Size(420, 320))
                setContent(VStack(gapPx = 10).apply {
                    addChild(select)
                    addChild(dropdown)
                    addChild(scrollView)
                })
            }
            dialog.show(factory)
            val dlgImpl = dialog.ensureNativeImpl() as com.nexustools.genericui.kde.KdeDialogWindowNativeImpl
            dlgImpl.setBounds(0, 0, 420, 320)
            val selectWidget = widgetOf(select)
            val dropdownWidget = widgetOf(dropdown)
            val selectImpl = select.ensureNativeImpl() as com.nexustools.genericui.kde.KdeSelectNativeImpl
            val dropdownImpl = dropdown.ensureNativeImpl() as com.nexustools.genericui.kde.KdeDropdownNativeImpl
            val scrollImpl = scrollView.ensureNativeImpl() as com.nexustools.genericui.kde.KdeScrollViewNativeImpl

            val selBounds = selectWidget.bounds
            val ddBounds = dropdownWidget.bounds
            assertTrue(selBounds.width > 0 && selBounds.height > 0)
            assertTrue(ddBounds.y > selBounds.bottom)

            // Verify anchored popup geometry below Select & Dropdown inside DialogWindow bounds
            val selPopupBounds = LinuxStyledSurfaceHelper.computeAnchoredPopupRect(
                anchorBounds = selBounds,
                popupWidthPx = selBounds.width,
                popupHeightPx = 96,
                viewport = Rect(0, 0, 420, 320)
            )
            assertTrue(abs(selPopupBounds.x - selBounds.x) <= 2)
            assertTrue(selPopupBounds.y in selBounds.bottom..(selBounds.bottom + 4))

            selectImpl.notifyUserSelectionChanged("opt2")
            GenericUiThreading.flushAllQueues()
            assertEquals("opt2", selectedOptionValue)

            val ddPopupBounds = LinuxStyledSurfaceHelper.computeAnchoredPopupRect(
                anchorBounds = ddBounds,
                popupWidthPx = 180,
                popupHeightPx = 72,
                viewport = Rect(0, 0, 420, 320)
            )
            assertTrue(abs(ddPopupBounds.x - ddBounds.x) <= 2)
            assertTrue(ddPopupBounds.y in ddBounds.bottom..(ddBounds.bottom + 4))

            dropdownImpl.dispatchItemSelected("act2")
            GenericUiThreading.flushAllQueues()
            assertEquals("act2", pickedDropdownItem)

            // QScrollArea scrolling inside DialogWindow
            assertEquals(0, scrollImpl.currentScrollY())
            scrollImpl.scrollTo(0, 64)
            assertEquals(64, scrollImpl.currentScrollY())

            // Verify near-bottom flip behavior cleanly keeps popup inside dialog viewport
            val flipped = LinuxStyledSurfaceHelper.computeAnchoredPopupRect(
                anchorBounds = Rect(20, 320 - 38, 160, 32),
                popupWidthPx = 180,
                popupHeightPx = 100,
                viewport = Rect(0, 0, 420, 320)
            )
            assertTrue(flipped.bottom <= 320 - 2 && flipped.y < 320 - 38, "Near-bottom popup must flip cleanly above anchor")

            dialog.close()
        } finally {
            GenericUI.activeFactory = prevFactory
            KdeApplicationNativeImpl.interactiveKdeGuiEnabled = prevKdeInteractive
        }

        // --- Part 2: GTK4 DialogWindow (when Cairo/GTK libs are present on Linux) ---
        if (Gtk4DynamicBindings.ensureLibrariesLoaded()) {
            val prevGtkInteractive = GtkApplicationNativeImpl.interactiveGtkGuiEnabled
            try {
                GtkApplicationNativeImpl.interactiveGtkGuiEnabled = false
                val gtkFactory = GtkNativeFactory()
                GenericUI.initialize(gtkFactory)

                val (select, dropdown, scrollView) = buildDialogContent()
                var selectedValue: String? = null
                var pickedDropdown: String? = null
                select.onSelectionChanged = { selectedValue = it }
                dropdown.onItemSelected = { pickedDropdown = it }

                val dialog = DialogWindow(title = "GTK Settings Dialog", modal = true).apply {
                    setWindowSize(Size(420, 320))
                    setContent(VStack(gapPx = 10).apply {
                        addChild(select)
                        addChild(dropdown)
                        addChild(scrollView)
                    })
                }
                dialog.show(gtkFactory)
                val dlgImpl = dialog.ensureNativeImpl() as com.nexustools.genericui.gtk.GtkDialogWindowNativeImpl
                dlgImpl.setBounds(0, 0, 420, 320)
                dlgImpl.layoutChildrenInBounds(420f, 320f)
                val selectWidget = widgetOf(select)
                val dropdownWidget = widgetOf(dropdown)
                val selectImpl = select.ensureNativeImpl() as GtkSelectNativeImpl
                val dropdownImpl = dropdown.ensureNativeImpl() as GtkDropdownNativeImpl
                val scrollImpl = scrollView.ensureNativeImpl() as GtkScrollViewNativeImpl

                try {
                    val selBounds = selectWidget.bounds
                    val ddBounds = dropdownWidget.bounds
                    assertTrue(selBounds.width > 0 && selBounds.height > 0)
                    assertTrue(ddBounds.y > selBounds.bottom)

                    val selPopupBounds = LinuxStyledSurfaceHelper.computeAnchoredPopupRect(
                        anchorBounds = selBounds,
                        popupWidthPx = selBounds.width,
                        popupHeightPx = 96,
                        viewport = Rect(0, 0, 420, 320)
                    )
                    assertTrue(abs(selPopupBounds.x - selBounds.x) <= 2)
                    assertTrue(selPopupBounds.y in selBounds.bottom..(selBounds.bottom + 4))

                    selectImpl.notifyUserSelectionChanged("opt2")
                    GenericUiThreading.flushAllQueues()
                    assertEquals("opt2", selectedValue)

                    val ddPopupBounds = LinuxStyledSurfaceHelper.computeAnchoredPopupRect(
                        anchorBounds = ddBounds,
                        popupWidthPx = 180,
                        popupHeightPx = 72,
                        viewport = Rect(0, 0, 420, 320)
                    )
                    assertTrue(abs(ddPopupBounds.x - ddBounds.x) <= 2)
                    assertTrue(ddPopupBounds.y in ddBounds.bottom..(ddBounds.bottom + 4))

                    dropdownImpl.dispatchItemSelected("act2")
                    GenericUiThreading.flushAllQueues()
                    assertEquals("act2", pickedDropdown)

                    assertEquals(0, scrollImpl.currentScrollY())
                    scrollImpl.scrollTo(0, 80)
                    assertEquals(80, scrollImpl.currentScrollY())
                } finally {
                    dialog.close()
                }
            } finally {
                GenericUI.activeFactory = prevFactory
                GtkApplicationNativeImpl.interactiveGtkGuiEnabled = prevGtkInteractive
            }
        }
    }

    @Test
    fun linuxOverlayTitleBarWithDisabledVibrancyRendersSemiTransparentWithoutBlur() {
        val profile = libadwaitaLightProfile()
        assertTrue(profile.isReplicable)
        LinuxTitleBarProfileManager.setTestProfileOverride(profile)

        val caps = LinuxTitleBarProfileManager.resolveTitleCapabilities()
        assertFalse(caps.prefersBlurredTranslucentOverlayTitleBar)

        val prevKdeInteractive = KdeApplicationNativeImpl.interactiveKdeGuiEnabled
        val prevFactory = GenericUI.activeFactory
        try {
            if (KdeDynamicBindings.ensureLoaded()) {
                KdeApplicationNativeImpl.interactiveKdeGuiEnabled = false
                val kdeFactory = KdeNativeFactory()
                GenericUI.initialize(kdeFactory)
                assertFalse(kdeFactory.defaultTitleCapabilities.prefersBlurredTranslucentOverlayTitleBar)

                val (bar, titleText, _) = buildOverlayTitleBarComponent(themeModeAttr = "dark")
                bar.setAttribute("data-blurred-vibrancy", "false")
                val window = Window(title = "Non-Blurred Stage", initialSize = Size(480, 320)).apply {
                    setUndecorated(true)
                    setContent(Container().apply { addChild(bar) })
                }
                window.show(kdeFactory)
                try {
                    val barWidget = widgetOf(bar)
                    val titleWidget = widgetOf(titleText)

                    val appearanceDefaultBg = LinuxOverlayTitleBarResolver.resolveAppearance(
                        windowTitleBar = barWidget,
                        titleLabelWidget = titleWidget,
                        effectiveProfile = profile,
                        contextIsDark = true,
                        isUndecoratedWindow = true,
                        isMaximized = false
                    )
                    assertNull(appearanceDefaultBg.backdropFilter, "data-blurred-vibrancy='false' must not apply blur")
                    assertTrue(appearanceDefaultBg.opacity < 1f, "Default non-blurred Linux overlay titlebar must stay semi-transparent")
                    assertEquals(0f, appearanceDefaultBg.topCornerRadiusPx)

                    barWidget.attributes["backgroundColor"] = RgbaColor(24, 24, 28, 219)
                    val appearanceSemiTransparentBg = LinuxOverlayTitleBarResolver.resolveAppearance(
                        windowTitleBar = barWidget,
                        titleLabelWidget = titleWidget,
                        effectiveProfile = profile,
                        contextIsDark = true,
                        isUndecoratedWindow = true,
                        isMaximized = false
                    )
                    assertNull(appearanceSemiTransparentBg.backdropFilter, "Semi-transparent overlay titlebar with data-blurred-vibrancy='false' must not blur")
                    assertTrue(appearanceSemiTransparentBg.profile.palette.activeBgTop.a < 255)
                } finally {
                    window.close()
                }
            }
        } finally {
            GenericUI.activeFactory = prevFactory
            KdeApplicationNativeImpl.interactiveKdeGuiEnabled = prevKdeInteractive
            LinuxTitleBarProfileManager.setTestProfileOverride(null)
        }
    }
}
