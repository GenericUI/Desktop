@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

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
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.image.ImageColorSpace
import com.nexustools.genericui.image.PixelFormat
import com.nexustools.genericui.linux.LinuxTabIconHelper
import com.nexustools.genericui.linux.titlebar.LinuxTitleBarProfileManager
import com.nexustools.genericui.linux.titlebar.LinuxTitleBarThemeFamily
import com.nexustools.genericui.linux.titlebar.LinuxTitleButtonKind
import com.nexustools.genericui.linux.titlebar.LinuxWmDetector
import com.nexustools.genericui.linux.titlebar.LinuxWmEnvironmentSnapshot
import com.nexustools.genericui.linux.titlebar.LinuxWmType
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.window.TitleBar
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KdeLinuxTitleBarAndMdiTest {

    @AfterTest
    fun resetOverrides() {
        LinuxTitleBarProfileManager.setTestProfileOverride(null)
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
    fun breezeCustomTitleBarCapabilitiesSlotsEmbeddedMenuBarAndActiveVsBackdropCapture() {
        assertTrue(KdeDynamicBindings.ensureLoaded())
        val prevInteractive = KdeApplicationNativeImpl.interactiveKdeGuiEnabled
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
            KdeApplicationNativeImpl.interactiveKdeGuiEnabled = false
            LinuxTitleBarProfileManager.setTestProfileOverride(breezeProfile)
            val factory = KdeNativeFactory()
            GenericUI.initialize(factory)

            val defaultCaps = factory.defaultTitleCapabilities
            assertTrue(defaultCaps.supportsCustomTitleBar)
            assertTrue(defaultCaps.canEmbedComponentsInTitleBar)
            assertTrue(defaultCaps.supportsEmbeddedMenuBar)

            val leadingBtn = Button("Tabs").apply { id = "kde-slot-leading-btn" }
            val centerLabel = Text("Workspace Title").apply { id = "kde-slot-center-label" }
            val trailingBtn = Button("Prefs").apply { id = "kde-slot-trailing-btn" }
            val titleBar = TitleBar(embedMenuBar = true, controlsPlacement = TitleBarControlsPlacement.LEADING)
                .addLeading(leadingBtn)
                .addCenter(centerLabel)
                .addTrailing(trailingBtn)

            val menuBar = MenuBar().addMenu(
                Menu("File").apply {
                    addItem(MenuItem("New"))
                    addItem(MenuItem("Exit"))
                }
            )

            val bodyLabel = Text("Main Body").apply { id = "kde-main-body-label" }
            val root = VStack().apply {
                addChild(leadingBtn)
                addChild(centerLabel)
                addChild(trailingBtn)
                addChild(bodyLabel)
            }

            val window = Window(title = "KDE Breeze Window").apply {
                setWindowSize(Size(760, 420))
                setTitleBar(titleBar)
                setMenuBar(menuBar)
                setContent(root)
            }
            window.show(factory)

            val windowImpl = KdeWindowNativeImpl.activeWindows.lastOrNull()
            assertNotNull(windowImpl)
            assertTrue(window.titleCapabilities.supportsCustomTitleBar)
            assertTrue(window.titleCapabilities.canEmbedComponentsInTitleBar)
            assertTrue(windowImpl.kdeCustomTitleBarActive)
            assertEquals(true, windowImpl.nativeWidget.attributes["kdeCustomTitleBarActive"])
            assertEquals(true, windowImpl.nativeWidget.attributes["embedMenuBarInTitleBar"])
            assertEquals(true, leadingBtn.mountedInTitleBar)
            assertEquals(true, centerLabel.mountedInTitleBar)
            assertEquals(true, trailingBtn.mountedInTitleBar)

            val titleBarWidget = assertNotNull(windowImpl.customTitleBarWidget)
            val barH = breezeProfile.metrics.heightPx
            try {
                windowImpl.notifyHostFocusChanged(true)
                val activeShot = KdeNativeWindowHost.captureWindowScreenshot(windowImpl, 760, 420)
                assertNotNull(activeShot)
                assertEquals(760, activeShot.width)
                assertEquals(420, activeShot.height)
                assertEquals(PixelFormat.RGBA_8888, activeShot.pixelFormat)

                windowImpl.notifyHostFocusChanged(false)
                val inactiveShot = KdeNativeWindowHost.captureWindowScreenshot(windowImpl, 760, 420)
                assertNotNull(inactiveShot)

                val activeHdrHash = screenshotRegionHash(activeShot, 0, 0, 760, barH)
                val inactiveHdrHash = screenshotRegionHash(inactiveShot, 0, 0, 760, barH)
                assertNotEquals(0L, activeHdrHash)
                assertNotEquals(activeHdrHash, inactiveHdrHash)

                val leadingWidget = widgetOf(leadingBtn)
                val trailingWidget = widgetOf(trailingBtn)
                assertTrue(leadingWidget.bounds.height > 0)
                assertTrue(leadingWidget.bounds.bottom <= barH)
                assertTrue(trailingWidget.bounds.bottom <= barH)

                val leftCloseX = breezeProfile.metrics.horizontalPaddingPx + 8
                val hovered = titleBarWidget.updateHoverAt(leftCloseX, barH / 2)
                assertEquals(LinuxTitleButtonKind.CLOSE, hovered)
                assertEquals(LinuxTitleButtonKind.CLOSE, titleBarWidget.hoveredButtonKind())
            } finally {
                window.close()
            }
        } finally {
            LinuxTitleBarProfileManager.setTestProfileOverride(null)
            KdeApplicationNativeImpl.interactiveKdeGuiEnabled = prevInteractive
            GenericUI.activeFactory = prevFactory
        }
    }

    @Test
    fun nonReplicableWmsAndGtkCssFallbackDisableCustomTitleBarInKde() {
        assertTrue(KdeDynamicBindings.ensureLoaded())
        val prevInteractive = KdeApplicationNativeImpl.interactiveKdeGuiEnabled
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
            KdeApplicationNativeImpl.interactiveKdeGuiEnabled = false
            for (profile in nonReplicableProfiles) {
                assertFalse(profile.isReplicable, "Expected non-replicable profile for ${profile.wmType}")
                LinuxTitleBarProfileManager.setTestProfileOverride(profile)

                val factory = KdeNativeFactory()
                GenericUI.initialize(factory)
                val caps = factory.defaultTitleCapabilities
                assertFalse(caps.supportsCustomTitleBar)

                val embedded = Button("ShouldStayInContent").apply { id = "fallback-embedded-btn" }
                val window = Window(title = "Fallback").apply {
                    setWindowSize(Size(600, 320))
                    setTitleBar(TitleBar(embedMenuBar = true).addLeading(embedded))
                    setContent(Container().apply { addChild(embedded) })
                }
                window.show(factory)
                val windowImpl = KdeWindowNativeImpl.activeWindows.lastOrNull()
                assertNotNull(windowImpl)
                assertFalse(windowImpl.kdeCustomTitleBarActive)
                assertEquals(false, embedded.mountedInTitleBar)
                assertEquals(0, windowImpl.customTitleBarWidget?.syncAndLayout(600))
                window.close()
            }
        } finally {
            LinuxTitleBarProfileManager.setTestProfileOverride(null)
            KdeApplicationNativeImpl.interactiveKdeGuiEnabled = prevInteractive
            GenericUI.activeFactory = prevFactory
        }
    }

    @Test
    fun overlayWindowTitleBarAndPipAccessoryRenderViaRetainedSemanticSurfaceInKde() {
        assertTrue(KdeDynamicBindings.ensureLoaded())
        val prevInteractive = KdeApplicationNativeImpl.interactiveKdeGuiEnabled
        val prevFactory = GenericUI.activeFactory
        val breezeProfile = LinuxWmDetector.detectFromSnapshot(
            LinuxWmEnvironmentSnapshot(xdgCurrentDesktop = "KDE", ewmhWmName = "KWin")
        )

        try {
            KdeApplicationNativeImpl.interactiveKdeGuiEnabled = false
            LinuxTitleBarProfileManager.setTestProfileOverride(breezeProfile)
            val factory = KdeNativeFactory()
            GenericUI.initialize(factory)

            var closeClicked = 0
            val restoreBtn = Button("Restore Window").apply { id = "pip-restore-btn" }
            val trailingAcc = HStack().apply {
                id = "overlay-window-trailing-accessories"
                addClass("gui-overlay-window-trailing-accessories")
                addChild(restoreBtn)
            }
            val minBtn = Button("—").apply { setAttribute("data-window-control", "minimize") }
            val maxBtn = Button("□").apply { setAttribute("data-window-control", "maximize") }
            val closeBtn = Button("×").apply {
                setAttribute("data-window-control", "close")
                setOnClick { closeClicked++ }
            }
            val controlsContainer = HStack().apply {
                addChild(minBtn)
                addChild(maxBtn)
                addChild(closeBtn)
            }
            val overlayTitleBar = Container(
                tagName = "genericui:overlay-window-title-bar",
                classPrefix = "gui-overlay-window-title-bar"
            ).apply {
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
            val windowImpl = KdeWindowNativeImpl.activeWindows.lastOrNull()
            assertNotNull(windowImpl)

            val titleBarImpl = overlayTitleBar.ensureNativeImpl() as DesktopContainerNativeImpl<KdeWidgetHandle>
            val titleBarWidget = widgetOf(overlayTitleBar)
            val restoreWidget = widgetOf(restoreBtn)
            val closeWidget = widgetOf(closeBtn)

            try {
                val shot = KdeNativeWindowHost.captureWindowScreenshot(windowImpl, 560, 320)
                assertNotNull(shot)
                val expectedH = breezeProfile.metrics.compactHeightPx
                assertEquals(expectedH, titleBarWidget.bounds.height)
                assertEquals("KdeSemanticContainerWidget", titleBarImpl.nativeWidget.activeKdeWidgetType)
                assertNotEquals(0L, screenshotRegionHash(shot, 0, 0, 560, expectedH))

                assertTrue(restoreWidget.bounds.width > 0)
                assertTrue(restoreWidget.bounds.bottom <= expectedH)
                assertTrue(closeWidget.bounds.width > 0)

                (closeWidget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>)?.dispatchClick()
                assertEquals(1, closeClicked)
            } finally {
                window.close()
            }
        } finally {
            LinuxTitleBarProfileManager.setTestProfileOverride(null)
            KdeApplicationNativeImpl.interactiveKdeGuiEnabled = prevInteractive
            GenericUI.activeFactory = prevFactory
        }
    }

    @Test
    fun mdiContainerZOrderActiveVsBackdropRenderingAndCaptionActionsInKde() {
        assertTrue(KdeDynamicBindings.ensureLoaded())
        val prevInteractive = KdeApplicationNativeImpl.interactiveKdeGuiEnabled
        val prevFactory = GenericUI.activeFactory
        val breezeProfile = LinuxWmDetector.detectFromSnapshot(
            LinuxWmEnvironmentSnapshot(xdgCurrentDesktop = "KDE", ewmhWmName = "KWin")
        )

        try {
            KdeApplicationNativeImpl.interactiveKdeGuiEnabled = false
            LinuxTitleBarProfileManager.setTestProfileOverride(breezeProfile)
            val factory = KdeNativeFactory()
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
            val winImpl = KdeWindowNativeImpl.activeWindows.lastOrNull()
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
                val shot = KdeNativeWindowHost.captureWindowScreenshot(winImpl, 760, 480)
                assertNotNull(shot)
                val hdrH = breezeProfile.metrics.compactHeightPx

                val winAHdr = winAHdrWidget.bounds
                val winBHdr = winBHdrWidget.bounds
                assertTrue(winAHdr.height >= 24)
                assertEquals(winAHdr.height, winBHdr.height)

                val backSliceHash = screenshotRegionHash(shot, winAHdr.x + 8, winAHdr.y + 4, 60, (hdrH - 8).coerceAtLeast(8))
                val frontSliceHash = screenshotRegionHash(shot, winBHdr.right - 140, winBHdr.y + 4, 60, (hdrH - 8).coerceAtLeast(8))
                assertNotEquals(0L, backSliceHash)
                assertNotEquals(0L, frontSliceHash)

                val surfaceImpl = desktopSurfaceWidget.attributes["nativeImpl"] as? DesktopBaseContainerNativeImpl<*>
                val sortedWidgets = surfaceImpl?.getLogicalChildren()
                    ?.mapNotNull { it.nativeWidget as? DesktopNativeWidget }
                    ?.sortedBy { it.effectiveZIndex() }
                assertNotNull(sortedWidgets)
                val idxA = sortedWidgets.indexOfFirst { it === winAWidget }
                val idxB = sortedWidgets.indexOfFirst { it === winBWidget }
                assertTrue(idxA >= 0 && idxB > idxA, "Front winB must stack above back winA")

                (winBCloseWidget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>)?.dispatchClick()
                assertFalse(winB in mdi.windows)

                winA.maximize()
                assertEquals(MdiWindowState.MAXIMIZED, winA.windowState)
                winImpl.layoutWindowChromeAndChildren(760, 480)
                assertEquals(
                    Rect(0, 0, desktopSurfaceWidget.bounds.width, desktopSurfaceWidget.bounds.height),
                    winAWidget.bounds
                )
            } finally {
                hostWin.close()
            }
        } finally {
            LinuxTitleBarProfileManager.setTestProfileOverride(null)
            KdeApplicationNativeImpl.interactiveKdeGuiEnabled = prevInteractive
            GenericUI.activeFactory = prevFactory
        }
    }

    @Test
    fun nativeTabBarTopBottomAndScrollViewScrollbarRenderCleanlyInKde() {
        assertTrue(KdeDynamicBindings.ensureLoaded())
        val prevInteractive = KdeApplicationNativeImpl.interactiveKdeGuiEnabled
        val prevFactory = GenericUI.activeFactory

        try {
            KdeApplicationNativeImpl.interactiveKdeGuiEnabled = false
            val factory = KdeNativeFactory()
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
            val winImpl = KdeWindowNativeImpl.activeWindows.lastOrNull()
            assertNotNull(winImpl)

            val tabContainerImpl = findWidgetNativeImpl<KdeTabContainerNativeImpl>(winImpl.nativeWidget)
            assertNotNull(tabContainerImpl)
            val scrollImpl = findWidgetNativeImpl<KdeScrollViewNativeImpl>(winImpl.nativeWidget)
            assertNotNull(scrollImpl)

            try {
                val shot = KdeNativeWindowHost.captureWindowScreenshot(winImpl, 480, 320)
                assertNotNull(shot)
                assertEquals(480, shot.width)
                assertEquals(320, shot.height)

                tabContainerImpl.requestSelectTab(1)
                assertEquals(1, tabs.selectedIndex)

                scrollImpl.scrollTo(0, 48)
                assertEquals(48, scrollImpl.currentScrollY())
            } finally {
                win.close()
            }
        } finally {
            KdeApplicationNativeImpl.interactiveKdeGuiEnabled = prevInteractive
            GenericUI.activeFactory = prevFactory
        }
    }

    @Test
    fun nonReplicableUnknownWmDisablesOverlayTitleBarAndPreventsFragmentUndecoratedOnKde() {
        assertTrue(KdeDynamicBindings.ensureLoaded())
        val prevInteractive = KdeApplicationNativeImpl.interactiveKdeGuiEnabled
        val prevFactory = GenericUI.activeFactory
        val unknownProfile = LinuxWmDetector.detectFromSnapshot(
            LinuxWmEnvironmentSnapshot(ewmhWmName = "UnknownCustomWM")
        )
        assertFalse(unknownProfile.isReplicable)

        try {
            KdeApplicationNativeImpl.interactiveKdeGuiEnabled = false
            LinuxTitleBarProfileManager.setTestProfileOverride(unknownProfile)
            val factory = KdeNativeFactory()
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
            val window = Window(title = "Unknown WM KDE Window").apply {
                setWindowSize(Size(640, 360))
                setFragment(fragment)
            }
            window.show(factory)

            val windowImpl = window.nativeImpl as? KdeWindowNativeImpl
            assertNotNull(windowImpl)
            val winCaps = windowImpl.getTitleCapabilities()
            assertFalse(winCaps.supportsCustomTitleBar)
            assertFalse(winCaps.canEmbedComponentsInTitleBar)
            assertFalse(winCaps.prefersBlurredTranslucentOverlayTitleBar)
            assertFalse(fragment.isOverlayWindowTitleBarSupported)
            assertFalse(window.isUndecorated)
            assertFalse(windowImpl.isUndecorated())

            try {
                val shot = KdeNativeWindowHost.captureWindowScreenshot(windowImpl, 640, 360)
                assertNotNull(shot)
                val barImpl = overlayBar.ensureNativeImpl() as DesktopContainerNativeImpl<*>
                assertEquals(0, barImpl.measureIntrinsicSize(640f, 360f).height)
                assertEquals(0, widgetOf(overlayBar).bounds.height)
            } finally {
                window.close()
            }
        } finally {
            LinuxTitleBarProfileManager.setTestProfileOverride(null)
            KdeApplicationNativeImpl.interactiveKdeGuiEnabled = prevInteractive
            GenericUI.activeFactory = prevFactory
        }
    }

    @Test
    fun replicableKdeLeadingButtonsAndFocusForwardingWorkCleanly() {
        assertTrue(KdeDynamicBindings.ensureLoaded())
        val prevInteractive = KdeApplicationNativeImpl.interactiveKdeGuiEnabled
        val prevFactory = GenericUI.activeFactory
        val leadingKdeProfile = LinuxWmDetector.detectFromSnapshot(
            LinuxWmEnvironmentSnapshot(
                xdgCurrentDesktop = "KDE",
                ewmhWmName = "KWin",
                kwinRcContent = "[org.kde.kdecoration2]\nlibrary=org.kde.breeze\nButtonsOnLeft=XIA\nButtonsOnRight=\n"
            )
        )
        assertTrue(leadingKdeProfile.isReplicable)

        try {
            KdeApplicationNativeImpl.interactiveKdeGuiEnabled = false
            LinuxTitleBarProfileManager.setTestProfileOverride(leadingKdeProfile)
            val factory = KdeNativeFactory()
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
            val titleText = Text("KDE Leading Controls").apply { id = "overlay-window-title-text" }
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
            val window = Window(title = "Leading KDE Window").apply {
                setWindowSize(Size(640, 360))
                setFragment(fragment)
            }
            window.show(factory)

            val windowImpl = window.nativeImpl as? KdeWindowNativeImpl
            assertNotNull(windowImpl)
            assertTrue(fragment.isOverlayWindowTitleBarSupported)
            assertTrue(window.isUndecorated)
            assertTrue(windowImpl.isUndecorated())

            try {
                val shot = KdeNativeWindowHost.captureWindowScreenshot(windowImpl, 640, 360)
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
            } finally {
                window.close()
            }
        } finally {
            LinuxTitleBarProfileManager.setTestProfileOverride(null)
            KdeApplicationNativeImpl.interactiveKdeGuiEnabled = prevInteractive
            GenericUI.activeFactory = prevFactory
        }
    }

    @Test
    fun kdeTabNativeImplTracksIconTintClosableAndPinnedMetadata() {
        val tab = KdeTabNativeImpl(closable = false)
        assertFalse(tab.closableVal)
        tab.setTitleNative("Docs")
        tab.setIconNative("https://kotlinlang.org/favicon.ico")
        tab.setIconSizePxNative(18)
        tab.setIconPlacementNative(TabIconPlacement.START)
        tab.setTintImageToThemeNative(false)
        tab.setClosableNative(true)

        assertTrue(tab.closableVal)
        assertFalse(tab.tintImageToThemeVal)
        assertEquals(18, tab.iconSizePxVal)
        assertTrue(LinuxTabIconHelper.looksLikeImageOrVectorSpec(tab.iconVal!!))

        tab.setPinnedNative(true)
        assertTrue(tab.pinnedVal)
        tab.dispose()
    }
}
