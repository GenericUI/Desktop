@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk

import com.nexustools.genericui.component.action.Button
import com.nexustools.genericui.component.fragment.Fragment
import com.nexustools.genericui.component.layout.Container
import com.nexustools.genericui.component.layout.HStack
import com.nexustools.genericui.component.layout.ScrollView
import com.nexustools.genericui.component.layout.VStack
import com.nexustools.genericui.component.shell.MdiContainer
import com.nexustools.genericui.component.shell.MdiWindow
import com.nexustools.genericui.component.shell.MdiWindowState
import com.nexustools.genericui.component.shell.Menu
import com.nexustools.genericui.component.shell.MenuBar
import com.nexustools.genericui.component.shell.MenuItem
import com.nexustools.genericui.component.shell.Window
import com.nexustools.genericui.component.tabs.Tab
import com.nexustools.genericui.component.tabs.TabContainer
import com.nexustools.genericui.component.tabs.TabIconPlacement
import com.nexustools.genericui.component.tabs.TabPosition
import com.nexustools.genericui.component.typography.Text
import com.nexustools.genericui.core.AbstractComponent
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.core.DesktopStageOverlaySpec
import com.nexustools.genericui.desktop.widget.DesktopContainerNativeImpl
import com.nexustools.genericui.enums.window.TitleBarControlsPlacement
import com.nexustools.genericui.enums.window.WindowState
import com.nexustools.genericui.event.PointerEvent
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.gtk.impl.GtkScrollViewNativeImpl
import com.nexustools.genericui.gtk.impl.GtkTabNativeImpl
import com.nexustools.genericui.gtk.titlebar.GtkWindowTitleBarController
import com.nexustools.genericui.gtk.widget.GtkSemanticContainerWidget
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.image.ImageColorSpace
import com.nexustools.genericui.image.PixelFormat
import com.nexustools.genericui.linux.LinuxDynamicLibraryLoader
import com.nexustools.genericui.linux.LinuxTabIconHelper
import com.nexustools.genericui.linux.titlebar.LinuxTitleBarProfileManager
import com.nexustools.genericui.linux.titlebar.LinuxTitleBarThemeFamily
import com.nexustools.genericui.linux.titlebar.LinuxTitleButtonKind
import com.nexustools.genericui.linux.titlebar.LinuxWmDetector
import com.nexustools.genericui.linux.titlebar.LinuxWmEnvironmentSnapshot
import com.nexustools.genericui.linux.titlebar.LinuxWmType
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.window.TitleBar
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.plus
import kotlinx.cinterop.reinterpret
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GtkLinuxTitleBarAndMdiTest {

    private class OffscreenCairoSurface(
        val width: Int,
        val height: Int
    ) {
        val surface: COpaquePointer
        val cr: COpaquePointer

        init {
            val cairoLib = LinuxDynamicLibraryLoader.openFirst("libcairo.so.2", "libcairo.so")
                ?: error("libcairo.so.2 is required for GTK4 Cairo offscreen titlebar verification")
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
    fun resetOverrides() {
        LinuxTitleBarProfileManager.setTestProfileOverride(null)
    }

    private fun regionHash(pixels: IntArray, width: Int, x: Int, y: Int, w: Int, h: Int): Long {
        var acc = 1469598103934665603L
        val xEnd = (x + w).coerceAtMost(width)
        val totalRows = pixels.size / width
        val yEnd = (y + h).coerceAtMost(totalRows)
        for (py in y until yEnd) {
            val rowOff = py * width
            for (px in x until xEnd) {
                acc = (acc xor pixels[rowOff + px].toLong()) * 1099511628211L
            }
        }
        return acc
    }

    private fun screenshotRegionHash(shot: ImageBuffer, x: Int, y: Int, w: Int, h: Int): Long {
        var acc = 1469598103934665603L
        val xEnd = (x + w).coerceAtMost(shot.width)
        val yEnd = (y + h).coerceAtMost(shot.height)
        for (py in y until yEnd) {
            for (px in x until xEnd) {
                val i = (py * shot.width + px) * 4
                val word = ((shot.bytes[i].toInt() and 0xFF) shl 24) or
                    ((shot.bytes[i + 1].toInt() and 0xFF) shl 16) or
                    ((shot.bytes[i + 2].toInt() and 0xFF) shl 8) or
                    (shot.bytes[i + 3].toInt() and 0xFF)
                acc = (acc xor word.toLong()) * 1099511628211L
            }
        }
        return acc
    }

    private fun widgetOf(component: AbstractComponent): DesktopNativeWidget =
        component.ensureNativeImpl()!!.nativeWidget as DesktopNativeWidget

    private fun xRelativeToAncestor(widget: DesktopNativeWidget, ancestor: DesktopNativeWidget): Int {
        var acc = 0
        var cur: DesktopNativeWidget? = widget
        while (cur != null && cur !== ancestor) {
            acc += cur.bounds.x
            cur = cur.parentWidget
        }
        return acc
    }

    private fun findWidgetByClass(root: DesktopNativeWidget, token: String): DesktopNativeWidget? {
        if (token in root.cachedAllClassTokens) return root
        for (child in root.children) {
            findWidgetByClass(child, token)?.let { return it }
        }
        return null
    }

    private inline fun <reified T> findWidgetNativeImpl(root: DesktopNativeWidget): T? {
        val queue = ArrayDeque<DesktopNativeWidget>()
        queue.addLast(root)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            val impl = cur.attributes["nativeImpl"]
            if (impl is T) return impl
            for (child in cur.children) {
                queue.addLast(child)
            }
        }
        return null
    }

    @Test
    fun yaruCustomTitleBarCapabilitiesSlotsEmbeddedMenuBarAndActiveVsBackdropCairoPixels() {
        assertTrue(Gtk4DynamicBindings.ensureLibrariesLoaded())
        val prevInteractive = GtkApplicationNativeImpl.interactiveGtkGuiEnabled
        val prevFactory = GenericUI.activeFactory
        val yaruProfile = LinuxWmDetector.detectFromSnapshot(
            LinuxWmEnvironmentSnapshot(
                xdgCurrentDesktop = "ubuntu:GNOME",
                ewmhWmName = "GNOME Shell",
                gnomeGtkTheme = "Yaru",
                gnomeWmButtonLayout = ":minimize,maximize,close"
            )
        )
        assertEquals(LinuxTitleBarThemeFamily.YARU, yaruProfile.themeFamily)
        assertTrue(yaruProfile.isReplicable)

        try {
            GtkApplicationNativeImpl.interactiveGtkGuiEnabled = false
            LinuxTitleBarProfileManager.setTestProfileOverride(yaruProfile)
            val factory = GtkNativeFactory()
            GenericUI.initialize(factory)

            val defaultCaps = factory.defaultTitleCapabilities
            assertTrue(defaultCaps.supportsCustomTitleBar)
            assertTrue(defaultCaps.canEmbedComponentsInTitleBar)
            assertTrue(defaultCaps.supportsEmbeddedMenuBar)
            assertEquals(TitleBarControlsPlacement.TRAILING, defaultCaps.defaultControlsPlacement)

            val leadingBtn = Button("Tabs").apply { id = "slot-leading-btn" }
            val centerLabel = Text("Workspace Title").apply { id = "slot-center-label" }
            val trailingBtn = Button("Prefs").apply { id = "slot-trailing-btn" }
            val titleBar = TitleBar(embedMenuBar = true)
                .addLeading(leadingBtn)
                .addCenter(centerLabel)
                .addTrailing(trailingBtn)

            val menuBar = MenuBar().addMenu(
                Menu("File").apply {
                    addItem(MenuItem("New"))
                    addItem(MenuItem("Exit"))
                }
            )

            val bodyLabel = Text("Main Body").apply { id = "main-body-label" }
            val root = VStack().apply {
                addChild(leadingBtn)
                addChild(centerLabel)
                addChild(trailingBtn)
                addChild(bodyLabel)
            }

            val window = Window(title = "Yaru Window").apply {
                setWindowSize(Size(760, 420))
                setTitleBar(titleBar)
                setMenuBar(menuBar)
                setContent(root)
            }
            window.show(factory)

            val windowImpl = GtkWindowNativeImpl.activeWindows.lastOrNull()
            assertNotNull(windowImpl)
            assertTrue(window.titleCapabilities.supportsCustomTitleBar)
            assertTrue(window.titleCapabilities.canEmbedComponentsInTitleBar)
            assertTrue(windowImpl.gtkCustomTitleBarActive)
            assertEquals(true, windowImpl.nativeWidget.attributes["gtkCustomTitleBarActive"])
            assertEquals(true, windowImpl.nativeWidget.attributes["embedMenuBarInTitleBar"])
            assertEquals(true, leadingBtn.mountedInTitleBar)
            assertEquals(true, centerLabel.mountedInTitleBar)
            assertEquals(true, trailingBtn.mountedInTitleBar)
            assertTrue(GtkWindowTitleBarController.isCustomTitleBarActive(windowImpl))

            val leadingWidget = widgetOf(leadingBtn)
            val trailingWidget = widgetOf(trailingBtn)

            val barH = yaruProfile.metrics.heightPx
            val activeSurf = OffscreenCairoSurface(760, barH)
            val inactiveSurf = OffscreenCairoSurface(760, barH)
            try {
                windowImpl.notifyHostFocusChanged(true)
                val activeShot = GtkNativeWindowHost.captureWindowScreenshot(windowImpl, 760, 420)
                assertNotNull(activeShot)
                assertEquals(760, activeShot.width)
                assertEquals(420, activeShot.height)
                assertEquals(PixelFormat.RGBA_8888, activeShot.pixelFormat)

                GtkWindowTitleBarController.beginFrame(windowImpl.nativeWidget.nativePointer)
                GtkWindowTitleBarController.renderTopLevelTitleBarToCairo(activeSurf.cr, windowImpl, 760, barH)
                val activePixels = activeSurf.readPixelsArgb()
                assertTrue(activePixels.any { it != 0 })

                // Mounted slot components laid out cleanly inside header strip
                assertTrue(leadingWidget.bounds.height > 0)
                assertTrue(leadingWidget.bounds.bottom <= barH)
                assertTrue(trailingWidget.bounds.bottom <= barH)

                // Hovering over the trailing close button updates GtkWindowTitleBarController state
                val closeX = 760 - yaruProfile.metrics.horizontalPaddingPx - 6
                assertTrue(GtkWindowTitleBarController.updateTopLevelHoverAt(windowImpl.nativeWidget.nativePointer, closeX, barH / 2))
                assertEquals(
                    LinuxTitleButtonKind.CLOSE,
                    GtkWindowTitleBarController.getTopLevelHoveredButton(windowImpl.nativeWidget.nativePointer)
                )

                // Switching window to backdrop produces distinct top-level header Cairo pixels
                windowImpl.notifyHostFocusChanged(false)
                GtkWindowTitleBarController.beginFrame(windowImpl.nativeWidget.nativePointer)
                GtkWindowTitleBarController.renderTopLevelTitleBarToCairo(inactiveSurf.cr, windowImpl, 760, barH)
                val inactivePixels = inactiveSurf.readPixelsArgb()
                assertNotEquals(
                    regionHash(activePixels, 760, 0, 0, 760, barH),
                    regionHash(inactivePixels, 760, 0, 0, 760, barH)
                )
            } finally {
                GtkWindowTitleBarController.clearWindowState(windowImpl.nativeWidget.nativePointer)
                activeSurf.close()
                inactiveSurf.close()
                window.close()
            }
        } finally {
            LinuxTitleBarProfileManager.setTestProfileOverride(null)
            GtkApplicationNativeImpl.interactiveGtkGuiEnabled = prevInteractive
            GenericUI.activeFactory = prevFactory
        }
    }

    @Test
    fun breezeCustomTitleBarAndLeadingControlsPlacementRenderCleanlyInCairo() {
        assertTrue(Gtk4DynamicBindings.ensureLibrariesLoaded())
        val prevInteractive = GtkApplicationNativeImpl.interactiveGtkGuiEnabled
        val prevFactory = GenericUI.activeFactory
        val breezeProfile = LinuxWmDetector.detectFromSnapshot(
            LinuxWmEnvironmentSnapshot(
                xdgCurrentDesktop = "KDE",
                ewmhWmName = "KWin"
            )
        )
        assertEquals(LinuxWmType.KWIN_BREEZE, breezeProfile.wmType)
        assertEquals(LinuxTitleBarThemeFamily.BREEZE, breezeProfile.themeFamily)
        assertTrue(breezeProfile.isReplicable)

        try {
            GtkApplicationNativeImpl.interactiveGtkGuiEnabled = false
            LinuxTitleBarProfileManager.setTestProfileOverride(breezeProfile)
            val factory = GtkNativeFactory()
            GenericUI.initialize(factory)

            val titleBar = TitleBar(controlsPlacement = TitleBarControlsPlacement.LEADING)
            val window = Window(title = "KDE Breeze Window").apply {
                setWindowSize(Size(680, 360))
                setTitleBar(titleBar)
                setContent(Container().apply { addChild(Text("Breeze Body")) })
            }
            window.show(factory)
            val windowImpl = GtkWindowNativeImpl.activeWindows.lastOrNull()
            assertNotNull(windowImpl)
            assertTrue(windowImpl.gtkCustomTitleBarActive)

            val barH = breezeProfile.metrics.heightPx
            val surf = OffscreenCairoSurface(680, barH)
            try {
                GtkWindowTitleBarController.beginFrame(windowImpl.nativeWidget.nativePointer)
                GtkWindowTitleBarController.renderTopLevelTitleBarToCairo(surf.cr, windowImpl, 680, barH)
                val pixels = surf.readPixelsArgb()
                val headerHash = regionHash(pixels, 680, 0, 0, 680, barH)
                assertNotEquals(0L, headerHash)

                val leftCloseX = breezeProfile.metrics.horizontalPaddingPx + 8
                assertTrue(GtkWindowTitleBarController.updateTopLevelHoverAt(windowImpl.nativeWidget.nativePointer, leftCloseX, barH / 2))
                assertEquals(
                    LinuxTitleButtonKind.CLOSE,
                    GtkWindowTitleBarController.getTopLevelHoveredButton(windowImpl.nativeWidget.nativePointer)
                )
            } finally {
                GtkWindowTitleBarController.clearWindowState(windowImpl.nativeWidget.nativePointer)
                surf.close()
                window.close()
            }
        } finally {
            LinuxTitleBarProfileManager.setTestProfileOverride(null)
            GtkApplicationNativeImpl.interactiveGtkGuiEnabled = prevInteractive
            GenericUI.activeFactory = prevFactory
        }
    }

    @Test
    fun nonReplicableWmsAndGtkCssFallbackDisableCustomTitleBarAndCollapseOverlayTitleBar() {
        assertTrue(Gtk4DynamicBindings.ensureLibrariesLoaded())
        val prevInteractive = GtkApplicationNativeImpl.interactiveGtkGuiEnabled
        val prevFactory = GenericUI.activeFactory

        val nonReplicableProfiles = listOf(
            LinuxWmDetector.detectFromSnapshot(LinuxWmEnvironmentSnapshot(swaySock = "/tmp/sway.sock")),
            LinuxWmDetector.detectFromSnapshot(
                LinuxWmEnvironmentSnapshot(
                    xdgCurrentDesktop = "KDE",
                    ewmhWmName = "KWin",
                    kwinRcContent = "[org.kde.kdecoration2]\nlibrary=org.kde.kwin.aurorae\n"
                )
            ),
            LinuxWmDetector.detectFromSnapshot(
                LinuxWmEnvironmentSnapshot(
                    xdgCurrentDesktop = "ubuntu:GNOME",
                    ewmhWmName = "GNOME Shell",
                    gnomeGtkTheme = "Yaru",
                    gtk4UserCssContent = "headerbar { min-height: 24px; background: #123456; }"
                )
            )
        )

        try {
            GtkApplicationNativeImpl.interactiveGtkGuiEnabled = false
            for (profile in nonReplicableProfiles) {
                assertFalse(profile.isReplicable, "Expected non-replicable profile for ${profile.wmType}")
                LinuxTitleBarProfileManager.setTestProfileOverride(profile)

                val factory = GtkNativeFactory()
                GenericUI.initialize(factory)
                val caps = factory.defaultTitleCapabilities
                assertFalse(caps.supportsCustomTitleBar)
                assertFalse(caps.canEmbedComponentsInTitleBar)
                assertFalse(caps.supportsEmbeddedMenuBar)

                val slotBtn = Button("Title Action")
                val tb = TitleBar(embedMenuBar = true).addTrailing(slotBtn)
                val overlayBar = Container(
                    tagName = "genericui:overlay-window-title-bar",
                    classPrefix = "gui-overlay-window-title-bar"
                ).apply {
                    id = "fallback-overlay-titlebar"
                    setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_WINDOW_TITLE_BAR)
                    setAttribute("data-height-px", "32")
                    addChild(Text("Video Overlay"))
                }
                val body = Text("Body Content").apply { id = "fallback-body-label" }
                val root = VStack().apply {
                    addChild(overlayBar)
                    addChild(body)
                    addChild(slotBtn)
                }

                val window = Window(title = "Fallback Window").apply {
                    setWindowSize(Size(640, 360))
                    setTitleBar(tb)
                    setContent(root)
                }
                window.show(factory)

                val windowImpl = GtkWindowNativeImpl.activeWindows.lastOrNull()
                assertNotNull(windowImpl)
                assertFalse(window.titleCapabilities.supportsCustomTitleBar)
                assertFalse(windowImpl.gtkCustomTitleBarActive)
                assertFalse(slotBtn.mountedInTitleBar)
                assertFalse(GtkWindowTitleBarController.isCustomTitleBarActive(windowImpl))

                val overlayWidget = widgetOf(overlayBar)
                val bodyWidget = widgetOf(body)

                try {
                    val shot = GtkNativeWindowHost.captureWindowScreenshot(windowImpl, 640, 360)
                    assertNotNull(shot)
                    assertEquals(0, overlayWidget.bounds.height)
                    assertTrue(bodyWidget.bounds.y <= 20)
                } finally {
                    GtkWindowTitleBarController.clearWindowState(windowImpl.nativeWidget.nativePointer)
                    window.close()
                }
            }
        } finally {
            LinuxTitleBarProfileManager.setTestProfileOverride(null)
            GtkApplicationNativeImpl.interactiveGtkGuiEnabled = prevInteractive
            GenericUI.activeFactory = prevFactory
        }
    }

    @Test
    fun overlayWindowTitleBarAndPipAccessoryRenderViaRetainedSemanticSurfaceUnderYaru() {
        assertTrue(Gtk4DynamicBindings.ensureLibrariesLoaded())
        val prevInteractive = GtkApplicationNativeImpl.interactiveGtkGuiEnabled
        val prevFactory = GenericUI.activeFactory
        val yaruProfile = LinuxWmDetector.detectFromSnapshot(
            LinuxWmEnvironmentSnapshot(
                xdgCurrentDesktop = "ubuntu:GNOME",
                ewmhWmName = "GNOME Shell",
                gnomeGtkTheme = "Yaru",
                gnomeWmButtonLayout = ":minimize,maximize,close"
            )
        )

        try {
            GtkApplicationNativeImpl.interactiveGtkGuiEnabled = false
            LinuxTitleBarProfileManager.setTestProfileOverride(yaruProfile)
            val factory = GtkNativeFactory()
            GenericUI.initialize(factory)

            var closeClicked = 0
            var restoreClicked = 0
            val restoreBtn = Button("Restore Window").apply {
                id = "pip-restore-btn"
                setOnClick { restoreClicked++ }
            }
            val trailingAcc = HStack().apply {
                id = "overlay-window-trailing-accessories"
                addClass("gui-overlay-window-trailing-accessories")
                addChild(restoreBtn)
            }
            val minBtn = Button("—").apply {
                id = "overlay-window-minimize-btn"
                setAttribute("data-window-control", "minimize")
            }
            val maxBtn = Button("□").apply {
                id = "overlay-window-maximize-btn"
                setAttribute("data-window-control", "maximize")
            }
            val closeBtn = Button("×").apply {
                id = "overlay-window-close-btn"
                setAttribute("data-window-control", "close")
                setOnClick { closeClicked++ }
            }
            val controlsContainer = HStack().apply {
                id = "overlay-window-controls"
                addChild(minBtn)
                addChild(maxBtn)
                addChild(closeBtn)
            }
            val overlayTitleBar = Container(
                tagName = "genericui:overlay-window-title-bar",
                classPrefix = "gui-overlay-window-title-bar"
            ).apply {
                id = "pip-overlay-title-bar"
                setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_WINDOW_TITLE_BAR)
                setAttribute("data-titlebar-active", "true")
                setAttribute("windowTitle", "PiP Player")
                setAttribute("data-controls-placement", TitleBarControlsPlacement.PLATFORM_DEFAULT.name)
                addChild(trailingAcc)
                addChild(controlsContainer)
            }
            val controlsLayer = Container().apply {
                setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_CONTROLS_LAYER)
                addChild(Text("Playback Controls"))
            }
            val overlayStage = VStack().apply {
                addChild(overlayTitleBar)
                addChild(controlsLayer)
            }

            val window = Window(title = "Video").apply {
                setUndecorated(true)
                setWindowSize(Size(560, 320))
                setContent(overlayStage)
            }
            window.show(factory)
            val windowImpl = GtkWindowNativeImpl.activeWindows.lastOrNull()
            assertNotNull(windowImpl)

            val titleBarImpl = overlayTitleBar.ensureNativeImpl() as DesktopContainerNativeImpl<GtkWidgetHandle>
            val titleBarWidget = widgetOf(overlayTitleBar)
            val restoreWidget = widgetOf(restoreBtn)
            val minWidget = widgetOf(minBtn)
            val closeWidget = widgetOf(closeBtn)

            val surf = OffscreenCairoSurface(560, 48)
            try {
                val shot = GtkNativeWindowHost.captureWindowScreenshot(windowImpl, 560, 320)
                assertNotNull(shot)
                val expectedH = yaruProfile.metrics.compactHeightPx
                assertEquals(expectedH, titleBarWidget.bounds.height)

                GtkSemanticContainerWidget.paintIfSemanticOrInteractive(titleBarImpl, surf.cr, 560, expectedH)
                val pixels = surf.readPixelsArgb()
                assertNotEquals(0L, regionHash(pixels, 560, 0, 0, 560, expectedH))

                assertTrue(restoreWidget.bounds.width > 0)
                assertTrue(restoreWidget.bounds.bottom <= expectedH)
                assertTrue(closeWidget.bounds.width > 0)
                val restoreRightX = xRelativeToAncestor(restoreWidget, titleBarWidget) + restoreWidget.bounds.width
                val minLeftX = xRelativeToAncestor(minWidget, titleBarWidget)
                assertTrue(restoreRightX <= minLeftX, "Expected trailing restore ($restoreRightX) <= min ($minLeftX)")

                (closeWidget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>)?.dispatchClick()
                assertEquals(1, closeClicked)

                (restoreWidget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>)?.dispatchClick()
                assertEquals(1, restoreClicked)

                // Fullscreen collapses overlay titlebar cleanly to 0px
                windowImpl.setFullscreen(true)
                windowImpl.layoutChildrenInBounds(560f, 320f)
                assertEquals(0, titleBarWidget.bounds.height)
            } finally {
                GtkWindowTitleBarController.clearWindowState(windowImpl.nativeWidget.nativePointer)
                surf.close()
                window.close()
            }
        } finally {
            LinuxTitleBarProfileManager.setTestProfileOverride(null)
            GtkApplicationNativeImpl.interactiveGtkGuiEnabled = prevInteractive
            GenericUI.activeFactory = prevFactory
        }
    }

    @Test
    fun mdiContainerZOrderActiveVsBackdropRenderingAndCaptionActionsInCairo() {
        assertTrue(Gtk4DynamicBindings.ensureLibrariesLoaded())
        val prevInteractive = GtkApplicationNativeImpl.interactiveGtkGuiEnabled
        val prevFactory = GenericUI.activeFactory
        val yaruProfile = LinuxWmDetector.detectFromSnapshot(
            LinuxWmEnvironmentSnapshot(
                xdgCurrentDesktop = "ubuntu:GNOME",
                ewmhWmName = "GNOME Shell",
                gnomeGtkTheme = "Yaru"
            )
        )

        try {
            GtkApplicationNativeImpl.interactiveGtkGuiEnabled = false
            LinuxTitleBarProfileManager.setTestProfileOverride(yaruProfile)
            val factory = GtkNativeFactory()
            GenericUI.initialize(factory)

            val mdi = MdiContainer()
            val winA = MdiWindow(
                windowId = "mdi-a",
                title = "Back Window",
                initialBounds = Rect(20, 20, 320, 200)
            ).apply {
                setTitleBar(TitleBar().addLeading(Button("A-Tool")))
                setContent(Container().apply { addChild(Text("Body A")) })
            }
            val winB = MdiWindow(
                windowId = "mdi-b",
                title = "Front Window",
                initialBounds = Rect(120, 20, 320, 200)
            ).apply {
                setContent(Container().apply { addChild(Text("Body B")) })
            }
            mdi.addWindow(winA)
            mdi.addWindow(winB)
            mdi.activateWindow(winB)

            assertFalse(winA.isActiveWindow)
            assertTrue(winB.isActiveWindow)
            assertTrue((winB.zIndex ?: 0) > (winA.zIndex ?: 0))

            val hostWin = Window(title = "MDI Host").apply {
                setWindowSize(Size(760, 480))
                setContent(mdi)
            }
            hostWin.show(factory)
            val winImpl = GtkWindowNativeImpl.activeWindows.lastOrNull()
            assertNotNull(winImpl)

            val desktopSurfaceWidget = findWidgetByClass(winImpl.nativeWidget, "mdi-desktop-surface")
            val winAWidget = widgetOf(winA)
            val winBWidget = widgetOf(winB)
            assertNotNull(desktopSurfaceWidget)
            val winAHdrWidget = findWidgetByClass(winAWidget, "mdi-window-header")
            val winBHdrWidget = findWidgetByClass(winBWidget, "mdi-window-header")
            val winBCloseWidget = findWidgetByClass(winBWidget, "mdi-btn-close")
            assertNotNull(winAHdrWidget)
            assertNotNull(winBHdrWidget)
            assertNotNull(winBCloseWidget)

            try {
                val shot = GtkNativeWindowHost.captureWindowScreenshot(winImpl, 760, 480)
                assertNotNull(shot)
                val hdrH = yaruProfile.metrics.compactHeightPx

                val winAHdr = winAHdrWidget.bounds
                val winBHdr = winBHdrWidget.bounds
                assertTrue(winAHdr.height >= 24)
                assertEquals(winAHdr.height, winBHdr.height)

                val backSliceHash = screenshotRegionHash(shot, winAHdr.x + 8, winAHdr.y + 4, 60, (hdrH - 8).coerceAtLeast(8))
                val frontSliceHash = screenshotRegionHash(shot, winBHdr.right - 140, winBHdr.y + 4, 60, (hdrH - 8).coerceAtLeast(8))
                assertNotEquals(0L, backSliceHash)
                assertNotEquals(0L, frontSliceHash)

                // Retained layout controller Z-order places front winB after back winA
                val surfaceImpl = desktopSurfaceWidget.attributes["nativeImpl"] as? DesktopBaseContainerNativeImpl<*>
                val sortedWidgets = surfaceImpl?.getLogicalChildren()
                    ?.mapNotNull { it.nativeWidget as? DesktopNativeWidget }
                    ?.sortedBy { it.effectiveZIndex() }
                assertNotNull(sortedWidgets)
                val idxA = sortedWidgets.indexOfFirst { it === winAWidget }
                val idxB = sortedWidgets.indexOfFirst { it === winBWidget }
                assertTrue(idxA >= 0 && idxB > idxA, "Front winB must stack above back winA")

                // winB close button click closes winB cleanly
                (winBCloseWidget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>)?.dispatchClick()
                assertFalse(winB in mdi.windows)

                // Maximizing remaining winA expands it to fill desktopSurface bounds
                winA.maximize()
                assertEquals(MdiWindowState.MAXIMIZED, winA.windowState)
                winImpl.layoutChildrenInBounds(760f, 480f)
                assertEquals(
                    Rect(0, 0, desktopSurfaceWidget.bounds.width, desktopSurfaceWidget.bounds.height),
                    winAWidget.bounds
                )
            } finally {
                GtkWindowTitleBarController.clearWindowState(winImpl.nativeWidget.nativePointer)
                hostWin.close()
            }
        } finally {
            LinuxTitleBarProfileManager.setTestProfileOverride(null)
            GtkApplicationNativeImpl.interactiveGtkGuiEnabled = prevInteractive
            GenericUI.activeFactory = prevFactory
        }
    }

    @Test
    fun nativeTabBarTopBottomAndScrollViewScrollbarRenderCleanlyInCairo() {
        assertTrue(Gtk4DynamicBindings.ensureLibrariesLoaded())
        val prevInteractive = GtkApplicationNativeImpl.interactiveGtkGuiEnabled
        val prevFactory = GenericUI.activeFactory

        try {
            GtkApplicationNativeImpl.interactiveGtkGuiEnabled = false
            val factory = GtkNativeFactory()
            GenericUI.initialize(factory)

            val scroll = ScrollView().apply {
                for (i in 1..20) {
                    addChild(Text("Scroll Row $i"))
                }
            }
            val tabs = TabContainer(tabPosition = TabPosition.BOTTOM).apply {
                addTab(Tab("Overview"), scroll)
                addTab(Tab("Logs"), Text("Log View"))
            }
            val win = Window(title = "Controls").apply {
                setWindowSize(Size(480, 320))
                setContent(Container().apply { addChild(tabs) })
            }
            win.show(factory)
            val winImpl = GtkWindowNativeImpl.activeWindows.lastOrNull()
            assertNotNull(winImpl)

            val tabContainerImpl = findWidgetNativeImpl<GtkTabContainerNativeImpl>(winImpl.nativeWidget)
            assertNotNull(tabContainerImpl)
            val scrollImpl = findWidgetNativeImpl<GtkScrollViewNativeImpl>(winImpl.nativeWidget)
            assertNotNull(scrollImpl)

            try {
                val shot = GtkNativeWindowHost.captureWindowScreenshot(winImpl, 480, 320)
                assertNotNull(shot)
                assertEquals(480, shot.width)
                assertEquals(320, shot.height)

                val tab1Impl = tabContainerImpl.getTabImpls().getOrNull(1) as? DesktopBaseNativeImpl<*>
                val tab1Widget = tab1Impl?.nativeWidget
                assertNotNull(tab1Widget)
                assertTrue(tab1Widget.bounds.y >= 320 - 44)
                tab1Impl.dispatchClick()
                assertEquals(1, tabs.selectedIndex)

                scrollImpl.scrollTo(0, 48)
                assertEquals(48, scrollImpl.currentScrollY())
            } finally {
                GtkWindowTitleBarController.clearWindowState(winImpl.nativeWidget.nativePointer)
                win.close()
            }
        } finally {
            GtkApplicationNativeImpl.interactiveGtkGuiEnabled = prevInteractive
            GenericUI.activeFactory = prevFactory
        }
    }

    @Test
    fun nonReplicableUnknownWmDisablesOverlayTitleBarAndPreventsFragmentUndecoratedOnGtk() {
        assertTrue(Gtk4DynamicBindings.ensureLibrariesLoaded())
        val prevInteractive = GtkApplicationNativeImpl.interactiveGtkGuiEnabled
        val prevFactory = GenericUI.activeFactory
        val unknownProfile = LinuxWmDetector.detectFromSnapshot(
            LinuxWmEnvironmentSnapshot(ewmhWmName = "UnknownCustomWM")
        )
        assertFalse(unknownProfile.isReplicable)

        try {
            GtkApplicationNativeImpl.interactiveGtkGuiEnabled = false
            LinuxTitleBarProfileManager.setTestProfileOverride(unknownProfile)
            val factory = GtkNativeFactory()
            GenericUI.initialize(factory)

            val defaultCaps = factory.defaultTitleCapabilities
            assertFalse(defaultCaps.supportsCustomTitleBar)
            assertFalse(defaultCaps.canEmbedComponentsInTitleBar)
            assertFalse(defaultCaps.prefersBlurredTranslucentOverlayTitleBar)

            val overlayBar = Container(
                tagName = "genericui:overlay-window-title-bar",
                classPrefix = "gui-overlay-window-title-bar"
            ).apply {
                setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_WINDOW_TITLE_BAR)
                setAttribute("data-titlebar-active", "false")
                setAttribute("data-blurred-vibrancy", "false")
                addChild(Text("Title"))
            }
            val fragment = object : Fragment() {
                override val prefersUndecoratedWindow: Boolean = true
                init {
                    addChild(overlayBar)
                }
            }
            val window = Window(title = "Unknown WM GTK Window").apply {
                setWindowSize(Size(640, 360))
                setFragment(fragment)
            }
            window.show(factory)

            val windowImpl = window.nativeImpl as? GtkWindowNativeImpl
            assertNotNull(windowImpl)
            val winCaps = windowImpl.getTitleCapabilities()
            assertFalse(winCaps.supportsCustomTitleBar)
            assertFalse(winCaps.canEmbedComponentsInTitleBar)
            assertFalse(winCaps.prefersBlurredTranslucentOverlayTitleBar)
            assertFalse(fragment.isOverlayWindowTitleBarSupported)
            assertFalse(window.isUndecorated)
            assertFalse(windowImpl.isUndecorated())

            try {
                val shot = GtkNativeWindowHost.captureWindowScreenshot(windowImpl, 640, 360)
                assertNotNull(shot)
                val barImpl = overlayBar.ensureNativeImpl() as DesktopContainerNativeImpl<*>
                assertEquals(0, barImpl.measureIntrinsicSize(640f, 360f).height)
                assertEquals(0, widgetOf(overlayBar).bounds.height)
            } finally {
                GtkWindowTitleBarController.clearWindowState(windowImpl.nativeWidget.nativePointer)
                window.close()
            }
        } finally {
            LinuxTitleBarProfileManager.setTestProfileOverride(null)
            GtkApplicationNativeImpl.interactiveGtkGuiEnabled = prevInteractive
            GenericUI.activeFactory = prevFactory
        }
    }

    @Test
    fun replicableGtkLeadingButtonsFocusForwardingAndDoubleClickToggleWork() {
        assertTrue(Gtk4DynamicBindings.ensureLibrariesLoaded())
        val prevInteractive = GtkApplicationNativeImpl.interactiveGtkGuiEnabled
        val prevFactory = GenericUI.activeFactory
        val leadingProfile = LinuxWmDetector.detectFromSnapshot(
            LinuxWmEnvironmentSnapshot(
                xdgCurrentDesktop = "ubuntu:GNOME",
                ewmhWmName = "GNOME Shell",
                gnomeGtkTheme = "Yaru",
                gnomeWmButtonLayout = "close,minimize,maximize:"
            )
        )
        assertTrue(leadingProfile.isReplicable)

        try {
            GtkApplicationNativeImpl.interactiveGtkGuiEnabled = false
            LinuxTitleBarProfileManager.setTestProfileOverride(leadingProfile)
            val factory = GtkNativeFactory()
            GenericUI.initialize(factory)

            val caps = factory.defaultTitleCapabilities
            assertTrue(caps.supportsCustomTitleBar)
            assertTrue(caps.canEmbedComponentsInTitleBar)
            assertFalse(caps.prefersBlurredTranslucentOverlayTitleBar)
            assertEquals(TitleBarControlsPlacement.LEADING, caps.defaultControlsPlacement)

            val focusEvents = mutableListOf<Boolean>()
            val closeBtn = Button("×").apply { setAttribute("data-window-control", "close") }
            val minBtn = Button("—").apply { setAttribute("data-window-control", "minimize") }
            val maxBtn = Button("□").apply { setAttribute("data-window-control", "maximize") }
            val controls = HStack().apply {
                id = "overlay-window-controls"
                addChild(closeBtn)
                addChild(minBtn)
                addChild(maxBtn)
            }
            val titleText = Text("Leading Controls").apply { id = "overlay-window-title-text" }
            val overlayBar = Container(
                tagName = "genericui:overlay-window-title-bar",
                classPrefix = "gui-overlay-window-title-bar"
            ).apply {
                setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_WINDOW_TITLE_BAR)
                setAttribute("data-titlebar-active", "true")
                setAttribute("data-blurred-vibrancy", "false")
                setAttribute("data-controls-placement", TitleBarControlsPlacement.PLATFORM_DEFAULT.name)
                addChild(controls)
                addChild(titleText)
            }
            val fragment = object : Fragment() {
                override val prefersUndecoratedWindow: Boolean = true
                init {
                    addChild(overlayBar)
                }
                override fun onWindowFocusChanged(hasFocus: Boolean) {
                    focusEvents.add(hasFocus)
                }
            }
            val window = Window(title = "Leading GTK Window").apply {
                setWindowSize(Size(640, 360))
                setFragment(fragment)
            }
            window.show(factory)

            val windowImpl = window.nativeImpl as? GtkWindowNativeImpl
            assertNotNull(windowImpl)
            assertTrue(fragment.isOverlayWindowTitleBarSupported)
            assertTrue(window.isUndecorated)
            assertTrue(windowImpl.isUndecorated())

            try {
                val shot = GtkNativeWindowHost.captureWindowScreenshot(windowImpl, 640, 360)
                assertNotNull(shot)
                val controlsWidget = widgetOf(controls)
                val titleWidget = widgetOf(titleText)
                assertTrue(controlsWidget.bounds.x <= 24, "Leading controls must align near left edge (x=${controlsWidget.bounds.x})")
                assertTrue(titleWidget.bounds.x >= controlsWidget.bounds.right)

                windowImpl.notifyHostFocusChanged(false)
                assertFalse(window.isWindowFocused)
                windowImpl.notifyHostFocusChanged(true)
                assertTrue(window.isWindowFocused)
                assertTrue(false in focusEvents && true in focusEvents)

                val barImpl = overlayBar.ensureNativeImpl() as GtkContainerNativeImpl
                assertEquals(WindowState.NORMAL, window.getWindowState())
                assertTrue(
                    barImpl.handleOverlayTitleBarPressed(
                        nPress = 2,
                        buttonNum = Gtk4DynamicBindings.GDK_BUTTON_PRIMARY,
                        ev = PointerEvent(x = 320f, y = 12f),
                        gdkEvent = null
                    )
                )
                assertEquals(WindowState.MAXIMIZED, window.getWindowState())
                assertTrue(
                    barImpl.handleOverlayTitleBarPressed(
                        nPress = 2,
                        buttonNum = Gtk4DynamicBindings.GDK_BUTTON_PRIMARY,
                        ev = PointerEvent(x = 320f, y = 12f),
                        gdkEvent = null
                    )
                )
                assertEquals(WindowState.NORMAL, window.getWindowState())
            } finally {
                GtkWindowTitleBarController.clearWindowState(windowImpl.nativeWidget.nativePointer)
                window.close()
            }
        } finally {
            LinuxTitleBarProfileManager.setTestProfileOverride(null)
            GtkApplicationNativeImpl.interactiveGtkGuiEnabled = prevInteractive
            GenericUI.activeFactory = prevFactory
        }
    }

    @Test
    fun gtkTabTracksFullColorIconTintSizeAndDynamicClosableAndKeepsWindowIconState() {
        val tabImpl = GtkTabNativeImpl(closable = false)
        assertFalse(tabImpl.closableVal)
        val baseWidth = tabImpl.measureIntrinsicSize(null, null).width

        tabImpl.setIconNative("https://example.com/favicon.ico")
        tabImpl.setIconSizePxNative(18)
        tabImpl.setIconPlacementNative(TabIconPlacement.TOP)
        tabImpl.setTintImageToThemeNative(false)
        tabImpl.setClosableNative(true)

        assertTrue(tabImpl.closableVal)
        assertFalse(tabImpl.tintImageToThemeVal)
        assertEquals(18, tabImpl.iconSizePxVal)
        assertEquals(TabIconPlacement.TOP, tabImpl.iconPlacementVal)
        assertTrue(tabImpl.measureIntrinsicSize(null, null).width > baseWidth)

        // Verify LinuxTabIconHelper preserves full colors when tintToTheme=false and tints when true
        val raw = ImageBuffer(2, 2, PixelFormat.RGBA_8888, ImageColorSpace.SRGB)
        raw.setPixel(0, 0, RgbaColor(240, 20, 20, 1f))
        raw.setPixel(1, 0, RgbaColor(20, 220, 40, 1f))
        raw.setPixel(0, 1, RgbaColor(20, 60, 240, 1f))
        raw.setPixel(1, 1, RgbaColor(240, 220, 20, 1f))

        val untinted = LinuxTabIconHelper.prepareTabIconBuffer(raw, sizePx = 2, tintToTheme = false, isDark = true)
        assertEquals(RgbaColor(240, 20, 20, 1f), untinted.getPixel(0, 0))
        assertEquals(RgbaColor(20, 220, 40, 1f), untinted.getPixel(1, 0))

        val customTint = RgbaColor(200, 210, 220, 1f)
        val tinted = LinuxTabIconHelper.prepareTabIconBuffer(raw, sizePx = 2, tintToTheme = true, isDark = true, customTint = customTint)
        assertEquals(customTint, tinted.getPixel(0, 0))
        assertEquals(customTint, tinted.getPixel(1, 0))
        tabImpl.dispose()
    }
}
