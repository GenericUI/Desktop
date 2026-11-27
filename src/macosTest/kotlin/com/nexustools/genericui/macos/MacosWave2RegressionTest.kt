@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.component.action.styled.StyledButton
import com.nexustools.genericui.component.composite.Composite
import com.nexustools.genericui.component.dialog.DialogWindow
import com.nexustools.genericui.component.form.CheckBox
import com.nexustools.genericui.component.form.Select
import com.nexustools.genericui.component.form.SelectOption
import com.nexustools.genericui.component.layout.Container
import com.nexustools.genericui.component.layout.LayerConstraint
import com.nexustools.genericui.component.layout.LayeredContainer
import com.nexustools.genericui.component.layout.styled.StyledContainer
import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.component.shell.CheckMenuItem
import com.nexustools.genericui.component.shell.MacApplicationMenu
import com.nexustools.genericui.component.shell.Menu
import com.nexustools.genericui.component.shell.MenuBar
import com.nexustools.genericui.component.shell.MenuItem
import com.nexustools.genericui.component.shell.Window
import com.nexustools.genericui.component.surface.styled.StyledComponent
import com.nexustools.genericui.component.typography.Text
import com.nexustools.genericui.core.AbstractComponent
import com.nexustools.genericui.core.AbstractContainer
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.desktop.core.DesktopInteractiveSurfaceSpec
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.core.DesktopStageOverlaySpec
import com.nexustools.genericui.enums.css.CssDisplay
import com.nexustools.genericui.enums.css.CssPointerEvents
import com.nexustools.genericui.enums.css.CssPosition
import com.nexustools.genericui.enums.ui.ObjectFit
import com.nexustools.genericui.event.KeyShortcut
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.macos.impl.MacosCheckboxNativeImpl
import com.nexustools.genericui.macos.impl.MacosSelectNativeImpl
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.css.CssLength
import com.nexustools.genericui.style.css.CssShadow
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.useContents
import platform.AppKit.NSApplication
import platform.AppKit.NSApplicationActivationPolicy
import platform.AppKit.NSControlStateValueOff
import platform.AppKit.NSControlStateValueOn
import platform.AppKit.NSEventModifierFlagCommand
import platform.AppKit.NSMenuItem
import platform.AppKit.NSView
import platform.AppKit.NSVisualEffectView
import platform.AppKit.NSWindowDidEndLiveResizeNotification
import platform.AppKit.NSWindowWillMoveNotification
import platform.AppKit.NSWindowWillStartLiveResizeNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSThread
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_sync
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MacosWave2RegressionTest {

    private var savedFactory = GenericUI.activeFactory
    private var savedApp: Application? = Application.current
    private var savedInteractive = MacosApplicationNativeImpl.interactiveMacosGuiEnabled
    private var savedVisual = MacosNativeWindowHost.visualTestMode

    @AfterTest
    fun cleanupMacosTestHost() {
        runOnMainThreadSync {
            MacosNativeWindowHost.closeAllVisualWindows()
            GenericUiThreading.flushAllQueues()
        }
        MacosNativeWindowHost.visualTestMode = savedVisual
        MacosApplicationNativeImpl.interactiveMacosGuiEnabled = savedInteractive
        Application.current = savedApp
        GenericUI.activeFactory = savedFactory
    }

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
        savedFactory = GenericUI.activeFactory
        savedApp = Application.current
        savedInteractive = MacosApplicationNativeImpl.interactiveMacosGuiEnabled
        savedVisual = MacosNativeWindowHost.visualTestMode

        val factory = MacosNativeFactory()
        Application.current = null
        MacosApplicationNativeImpl.interactiveMacosGuiEnabled = true
        MacosNativeWindowHost.visualTestMode = true
        GenericUI.initialize(factory)
        runOnMainThreadSync {
            MacosNativeWindowHost.prepareEventLoop()
            assertEquals(
                NSApplicationActivationPolicy.NSApplicationActivationPolicyAccessory,
                NSApplication.sharedApplication.activationPolicy()
            )
            block(factory)
        }
    }

    // -------------------------------------------------------------------------
    // Part 1: Settings Window Drag Lag, NSWindow Identity, Per-Window Isolation, Intrinsic Cache
    // -------------------------------------------------------------------------

    @Test
    fun dialogPanelDetachesDuringDragAndLiveResizeReattachesAtRestAndSuppressesHoverPin() = withInteractiveMacosHost { factory ->
        val overlayTitleBar = StyledContainer().apply {
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_WINDOW_TITLE_BAR)
            setAttribute("data-native-titlebar-controls", "true")
            setAttribute("data-titlebar-active", "true")
            style.setWidth(CssLength.px(520))
            style.setHeight(CssLength.px(28))
        }
        val mainWindow = Window(title = "Main Window", initialSize = Size(520, 320)).apply {
            setUndecorated(true)
            setContent(Container().apply { addChild(overlayTitleBar) })
        }
        mainWindow.show(factory)
        MacosNativeWindowHost.pumpVisualWindowEvents(32L)

        val mainSession = assertNotNull(MacosNativeWindowHost.windowSessionForTest(mainWindow.ensureNativeImpl() as MacosWindowNativeImpl))
        val dialog = DialogWindow(title = "Settings").apply {
            setWindowSize(Size(360, 260))
            setContent(Container().apply { addChild(Text("Preferences")) })
        }
        dialog.open(mainWindow)
        MacosNativeWindowHost.pumpVisualWindowEvents(32L)

        val dlgImpl = dialog.ensureNativeImpl() as MacosDialogWindowNativeImpl
        val dlgSession = assertNotNull(MacosNativeWindowHost.dialogSessionForTest(dlgImpl))
        val panel = assertNotNull(dlgSession.nsWindow as? MacosDialogPanel, "Dialog must use MacosDialogPanel")

        assertTrue(isSameNsWindow(panel.parentWindow, mainSession.nsWindow))
        assertTrue(isSameNsWindow(panel, panel))
        assertFalse(isSameNsWindow(panel, mainSession.nsWindow))
        assertFalse(isSameNsWindow(null, panel))
        assertFalse(MacosNativeWindowHost.hasActiveDialogDragOrResize())

        // Simulate windowWillMove notification -> panel detaches from owner immediately
        NSNotificationCenter.defaultCenter.postNotificationName(NSWindowWillMoveNotification, panel)
        assertTrue(panel.isActivelyDraggingOrResizing)
        assertNull(panel.parentWindow, "Dialog panel must detach from owner while dragging")
        assertTrue(MacosNativeWindowHost.hasActiveDialogDragOrResize())

        // While dragging dialog, overlay titlebar hover-pin on main window must be suppressed
        MacosNativeWindowHost.onNativeWindowDidMove(dlgSession.windowKey, isDialog = true)
        mainSession.nativeTitleBarHoverPinned = false
        mainSession.rootView.onWindowMouseMoved?.invoke(120.0, 10.0)
        assertFalse(
            mainSession.nativeTitleBarHoverPinned,
            "syncNativeOverlayTitleBarHoverPin must be suppressed while dialog drag is active"
        )

        // End drag immediately -> re-attaches cleanly
        panel.endDragOrResizeNow()
        assertFalse(panel.isActivelyDraggingOrResizing)
        assertTrue(isSameNsWindow(panel.parentWindow, mainSession.nsWindow))
        assertFalse(MacosNativeWindowHost.hasActiveDialogDragOrResize())

        // Live resize notifications detach and re-attach cleanly too
        NSNotificationCenter.defaultCenter.postNotificationName(NSWindowWillStartLiveResizeNotification, panel)
        assertTrue(panel.isActivelyDraggingOrResizing)
        assertNull(panel.parentWindow)
        assertTrue(MacosNativeWindowHost.hasActiveDialogDragOrResize())

        NSNotificationCenter.defaultCenter.postNotificationName(NSWindowDidEndLiveResizeNotification, panel)
        assertFalse(panel.isActivelyDraggingOrResizing)
        assertTrue(isSameNsWindow(panel.parentWindow, mainSession.nsWindow))
        assertFalse(MacosNativeWindowHost.hasActiveDialogDragOrResize())

        dialog.close()
        mainWindow.close()
    }

    @Test
    fun perWindowInvalidationIsolationDoesNotDirtyUnrelatedWindowAndCachesIntrinsicSizes() = withInteractiveMacosHost { factory ->
        val textA = Text("Window A")
        val textB = Text("Window B")
        val winA = Window(title = "Window A", initialSize = Size(320, 200)).apply {
            setContent(Container().apply { addChild(textA) })
        }
        val winB = Window(title = "Window B", initialSize = Size(320, 200)).apply {
            setContent(Container().apply { addChild(textB) })
        }
        winA.show(factory)
        winB.show(factory)
        MacosNativeWindowHost.flushPendingDisplay()

        val sessA = assertNotNull(MacosNativeWindowHost.windowSessionForTest(winA.ensureNativeImpl() as MacosWindowNativeImpl))
        val sessB = assertNotNull(MacosNativeWindowHost.windowSessionForTest(winB.ensureNativeImpl() as MacosWindowNativeImpl))
        sessA.needsRelayout = false
        sessA.needsRedisplay = false
        sessB.needsRelayout = false
        sessB.needsRedisplay = false

        // Invalidate only textA inside winA
        (textA.ensureNativeImpl()!!.nativeWidget as DesktopNativeWidget).notifyTreeInvalidated()
        assertTrue(sessA.needsRelayout && sessA.needsRedisplay, "Owning window A must be invalidated")
        assertFalse(sessB.needsRelayout, "Unrelated window B must NOT have needsRelayout set")
        assertFalse(sessB.needsRedisplay, "Unrelated window B must NOT have needsRedisplay set")

        // Select & Checkbox measureIntrinsicSize cache hit & invalidation on change
        val select = Select().apply {
            setOptions(
                listOf(
                    SelectOption("s", "Short"),
                    SelectOption("m", "Medium Option")
                )
            )
            selectedValue = "s"
        }
        val checkbox = CheckBox("Enable Hardware Decode")
        val controlsRoot = Container().apply {
            addChild(select)
            addChild(checkbox)
        }
        winA.setContent(controlsRoot)
        MacosNativeWindowHost.flushPendingDisplay()

        val selectImpl = assertNotNull(select.ensureNativeImpl() as? MacosSelectNativeImpl)
        val checkboxImpl = assertNotNull(checkbox.ensureNativeImpl() as? MacosCheckboxNativeImpl)

        val s1 = selectImpl.measureIntrinsicSize(null, null)
        val s2 = selectImpl.measureIntrinsicSize(null, null)
        assertEquals(s1, s2)
        selectImpl.setOptions(listOf("long" to "Very Long Audio Output Device Name Example"))
        val s3 = selectImpl.measureIntrinsicSize(null, null)
        assertTrue(s3.width > s1.width, "Select intrinsic cache must invalidate when options change ($s1 -> $s3)")

        val c1 = checkboxImpl.measureIntrinsicSize(null, null)
        val c2 = checkboxImpl.measureIntrinsicSize(null, null)
        assertEquals(c1, c2)
        checkboxImpl.setLabel("Enable Hardware Accelerated 10-Bit HDR Video Decoding Pipeline")
        val c3 = checkboxImpl.measureIntrinsicSize(null, null)
        assertTrue(c3.width > c1.width, "Checkbox intrinsic cache must invalidate when label changes ($c1 -> $c3)")

        winA.close()
        winB.close()
    }

    // -------------------------------------------------------------------------
    // Part 2: macOS App Menu Bridge & NSMenuItemValidationProtocol
    // -------------------------------------------------------------------------

    @Test
    fun applicationMenuBridgePlacesCustomItemsPreservesPerWindowMenusAndLiveValidates() = withInteractiveMacosHost { factory ->
        var prefsClicks = 0
        var autoUpdateChecked = true
        lateinit var checkItem: CheckMenuItem
        lateinit var dynamicItem: MenuItem

        val app = object : Application(title = "ReceiverApp") {
            override fun onInstallMacApplicationMenu(menu: MacApplicationMenu) {
                menu.addItem(
                    MenuItem(
                        label = "Preferences...",
                        shortcut = KeyShortcut(",", ctrlOrCmd = true)
                    ).apply {
                        setOnClick { prefsClicks++ }
                    }
                )
                menu.addSeparator()
                checkItem = menu.addCheckItem(
                    label = "Auto-Update",
                    checked = true,
                    onToggled = { checked -> autoUpdateChecked = checked }
                )
                dynamicItem = MenuItem("Sync Now").apply {
                    isEnabled = false
                }
                menu.addItem(dynamicItem)
                menu.addSubmenu(
                    Menu("Diagnostics").apply {
                        addItem(MenuItem("Export Logs"))
                    }
                )
            }
        }
        app.suppressInitialMainWindowShow = true

        val dlg = DialogWindow(title = "Hidden-Main Dialog").apply {
            setWindowSize(Size(320, 200))
            setContent(Container().apply { addChild(Text("Ready")) })
        }

        // Run application lifecycle (suppressInitialMainWindowShow=true + visualTestMode=true exits runEventLoop immediately)
        app.run(factory)

        // Present a dialog while mainWindow stays hidden -> app menu persists
        dlg.open()
        MacosNativeWindowHost.pumpVisualWindowEvents(24L)

        val mainMenu = assertNotNull(NSApplication.sharedApplication.mainMenu)
        val appSubmenu = assertNotNull(mainMenu.itemAtIndex(0L)?.submenu)
        assertTrue(appSubmenu.autoenablesItems, "App menu must keep autoenablesItems = true for Cocoa standard items")

        val titles = (0 until appSubmenu.numberOfItems.toInt()).map { idx ->
            val item = appSubmenu.itemAtIndex(idx.toLong())!!
            if (item.separatorItem) "---" else item.title
        }
        // Expected order: About, ---, Preferences..., ---, Auto-Update, Sync Now, Diagnostics, ---, Hide, Hide Others, Show All, ---, Quit
        assertEquals("About ReceiverApp", titles[0])
        assertEquals("---", titles[1])
        assertEquals("Preferences...", titles[2])
        assertEquals("---", titles[3])
        assertEquals("Auto-Update", titles[4])
        assertEquals("Sync Now", titles[5])
        assertEquals("Diagnostics", titles[6])
        assertEquals("---", titles[7])
        assertEquals("Hide ReceiverApp", titles[8])
        assertEquals("Hide Others", titles[9])
        assertEquals("Show All", titles[10])
        assertEquals("---", titles[11])
        assertEquals("Quit ReceiverApp", titles[12])

        val nsPrefs = appSubmenu.itemAtIndex(2L)!!
        assertEquals(",", nsPrefs.keyEquivalent)
        assertTrue((nsPrefs.keyEquivalentModifierMask and NSEventModifierFlagCommand) != 0uL)

        val nsCheck = appSubmenu.itemAtIndex(4L)!!
        assertEquals(NSControlStateValueOn, nsCheck.state)

        val nsDynamic = appSubmenu.itemAtIndex(5L)!!
        val target = assertNotNull(nsDynamic.target as? MacosMenuActionTarget)
        assertFalse(target.validateMenuItem(nsDynamic), "Disabled item must return false from validateMenuItem:")

        // Live-validate isEnabled change on existing NSMenuItem without full menu rebuild
        dynamicItem.isEnabled = true
        assertTrue(target.validateMenuItem(nsDynamic))

        // Mutate label and checked state -> triggers MacApplicationMenu re-sync of mainMenu
        dynamicItem.setLabel("Sync Completed")
        checkItem.setChecked(false)
        MacosNativeWindowHost.pumpVisualWindowEvents(16L)
        val refreshedSubmenu = assertNotNull(NSApplication.sharedApplication.mainMenu?.itemAtIndex(0L)?.submenu)
        val refreshedCheck = refreshedSubmenu.itemAtIndex(4L)!!
        val refreshedDynamic = refreshedSubmenu.itemAtIndex(5L)!!
        val refreshedTarget = assertNotNull(refreshedDynamic.target as? MacosMenuActionTarget)
        assertTrue(refreshedTarget.validateMenuItem(refreshedDynamic))
        assertEquals("Sync Completed", refreshedDynamic.title)
        assertTrue(refreshedTarget.validateMenuItem(refreshedCheck))
        assertEquals(NSControlStateValueOff, refreshedCheck.state)

        // Submenu preserved
        val nsSub = refreshedSubmenu.itemAtIndex(6L)?.submenu
        assertNotNull(nsSub)
        assertEquals("Export Logs", nsSub.itemAtIndex(0L)?.title)

        // Per-window MenuBars display for their focused window while NEVER mutating the mac-specific app menu
        val windowA = Window(title = "Window Alpha", initialSize = Size(400, 260)).apply {
            setMenuBar(
                MenuBar()
                    .addMenu(Menu("File").apply { addItem(MenuItem("Open Alpha")) })
                    .addMenu(Menu("AlphaTools").apply { addItem(MenuItem("Inspect")) })
            )
            setContent(Container())
        }
        val windowB = Window(title = "Window Beta", initialSize = Size(400, 260)).apply {
            setMenuBar(
                MenuBar()
                    .addMenu(Menu("File").apply { addItem(MenuItem("Open Beta")) })
                    .addMenu(Menu("BetaView").apply { addItem(MenuItem("Zoom")) })
            )
            setContent(Container())
        }
        windowA.show(factory)
        MacosNativeWindowHost.pumpVisualWindowEvents(24L)
        val menuAfterA = assertNotNull(NSApplication.sharedApplication.mainMenu)
        val topTitlesA = (0 until menuAfterA.numberOfItems.toInt()).map { menuAfterA.itemAtIndex(it.toLong())?.title }
        assertEquals("ReceiverApp", topTitlesA[0], "Window title must never overwrite mac application menu title")
        assertTrue("AlphaTools" in topTitlesA, "Focused Window A's MenuBar must appear in macOS menu bar: $topTitlesA")
        assertEquals("Preferences...", menuAfterA.itemAtIndex(0L)?.submenu?.itemAtIndex(2L)?.title)

        windowB.show(factory)
        MacosNativeWindowHost.pumpVisualWindowEvents(24L)
        val menuAfterB = assertNotNull(NSApplication.sharedApplication.mainMenu)
        val topTitlesB = (0 until menuAfterB.numberOfItems.toInt()).map { menuAfterB.itemAtIndex(it.toLong())?.title }
        assertTrue("BetaView" in topTitlesB && "AlphaTools" !in topTitlesB, "Focused Window B's MenuBar must replace A's window menus: $topTitlesB")
        assertEquals("ReceiverApp", topTitlesB[0])
        assertEquals("Preferences...", menuAfterB.itemAtIndex(0L)?.submenu?.itemAtIndex(2L)?.title)

        // Refocusing Window A restores Window A's MenuBar while keeping MacApplicationMenu intact
        val sessA = assertNotNull(MacosNativeWindowHost.windowSessionForTest(windowA.ensureNativeImpl() as MacosWindowNativeImpl))
        MacosNativeWindowHost.onNativeWindowFocusChanged(sessA.windowKey, isDialog = false, focused = true)
        MacosNativeWindowHost.pumpVisualWindowEvents(16L)
        val menuRefocusedA = assertNotNull(NSApplication.sharedApplication.mainMenu)
        val topTitlesRefocusedA = (0 until menuRefocusedA.numberOfItems.toInt()).map { menuRefocusedA.itemAtIndex(it.toLong())?.title }
        assertTrue("AlphaTools" in topTitlesRefocusedA && "BetaView" !in topTitlesRefocusedA)
        assertEquals("Preferences...", menuRefocusedA.itemAtIndex(0L)?.submenu?.itemAtIndex(2L)?.title)

        // Trigger Cmd+, target callback
        val rebuiltPrefs = menuRefocusedA.itemAtIndex(0L)?.submenu?.itemAtIndex(2L)!!
        (rebuiltPrefs.target as MacosMenuActionTarget).onMenuItemTriggered(rebuiltPrefs)
        MacosNativeWindowHost.pumpVisualWindowEvents(32L)
        assertEquals(1, prefsClicks)
        assertTrue(autoUpdateChecked)
        assertEquals(
            NSApplicationActivationPolicy.NSApplicationActivationPolicyAccessory,
            NSApplication.sharedApplication.activationPolicy()
        )

        dlg.close()
        windowA.close()
        windowB.close()
        app.dispose()
    }

    @Test
    fun clickingAppMenuSettingsItemOpensDialogFromLogicThreadWithoutCrashing() = withInteractiveMacosHost { factory ->
        var settingsClickCount = 0
        var lastClickWasOnMainThread = true
        val openedDialogs = mutableListOf<DialogWindow>()

        val app = object : Application(title = "ReceiverApp") {
            override fun onInstallMacApplicationMenu(menu: MacApplicationMenu) {
                menu.addItem(
                    label = "Settings\u2026",
                    shortcut = KeyShortcut(",", ctrlOrCmd = true),
                    onClick = {
                        settingsClickCount++
                        lastClickWasOnMainThread = NSThread.isMainThread
                        val dlg = DialogWindow(title = "Receiver Media Settings").apply {
                            setWindowSize(Size(420, 300))
                            setContent(Container().apply { addChild(Text("Audio Output")) })
                        }
                        dlg.open(mainWindow)
                        openedDialogs.add(dlg)
                    }
                )
            }
        }
        app.mainWindow.setContent(Container().apply { addChild(Text("Player Stage")) })
        app.run(factory)
        MacosNativeWindowHost.pumpVisualWindowEvents(24L)

        // First trigger: simulates clicking "Settings…" from macOS Application Menu with visible mainWindow
        val menuBar = assertNotNull(NSApplication.sharedApplication.mainMenu)
        val appSubmenu = assertNotNull(menuBar.itemAtIndex(0L)?.submenu)
        val settingsNsItem = assertNotNull(appSubmenu.itemAtIndex(2L))
        assertEquals("Settings\u2026", settingsNsItem.title)

        val installsBeforeClick = MacosNativeWindowHost.mainMenuInstallCountForTest
        (settingsNsItem.target as MacosMenuActionTarget).onMenuItemTriggered(settingsNsItem)
        MacosNativeWindowHost.pumpVisualWindowEvents(64L)

        assertEquals(1, settingsClickCount)
        assertFalse(lastClickWasOnMainThread, "Menu item onClick must run on Logic thread (the exact crash precondition)")
        assertEquals(
            installsBeforeClick,
            MacosNativeWindowHost.mainMenuInstallCountForTest,
            "Opening a dialog from the app menu must not spuriously tear down and rebuild NSApplication.mainMenu"
        )
        val firstDlg = assertNotNull(openedDialogs.singleOrNull())
        assertTrue(firstDlg.isWindowShown)
        val firstDlgImpl = firstDlg.ensureNativeImpl() as MacosDialogWindowNativeImpl
        val firstSession = assertNotNull(MacosNativeWindowHost.dialogSessionForTest(firstDlgImpl))
        assertNotNull(firstSession.nsWindow as? MacosDialogPanel)
        val mainSession = assertNotNull(MacosNativeWindowHost.windowSessionForTest(app.mainWindow.ensureNativeImpl() as MacosWindowNativeImpl))
        assertTrue(isSameNsWindow(firstSession.nsWindow.parentWindow, mainSession.nsWindow))
        firstDlg.close()
        MacosNativeWindowHost.pumpVisualWindowEvents(24L)

        // Second trigger: menu item action binding must still work reliably
        (settingsNsItem.target as MacosMenuActionTarget).onMenuItemTriggered(settingsNsItem)
        MacosNativeWindowHost.pumpVisualWindowEvents(64L)
        assertEquals(2, settingsClickCount)
        assertFalse(lastClickWasOnMainThread)
        assertTrue(openedDialogs[1].isWindowShown)
        openedDialogs[1].close()

        // Also test standalone DialogWindow open from Logic thread when mainWindow is hidden,
        // plus background-thread calls to prepareEventLoop, NSScreen bounds queries, and activation policy sync
        app.mainWindow.hide()
        MacosNativeWindowHost.pumpVisualWindowEvents(24L)
        var bgMonitorOk = false
        var bgRectOk = false
        var standaloneDlg: DialogWindow? = null
        val sem = dispatch_semaphore_create(0)
        val bgQueue = dispatch_queue_create("com.nexustools.genericui.test.bg_crash_check", null)
        dispatch_async(bgQueue) {
            MacosNativeWindowHost.prepareEventLoop()
            MacosNativeWindowHost.syncActivationPolicyForCurrentState()
            val bounds = MacosNativeWindowHost.getPrimaryMonitorBounds()
            bgMonitorOk = bounds != null && bounds.width > 0 && bounds.height > 0
            bgRectOk = MacosNativeWindowHost.isWindowRectOnScreen(com.nexustools.genericui.geometry.Rect(50, 50, 200, 150))
            val dlg = DialogWindow(title = "Standalone Settings").apply {
                setWindowSize(Size(360, 240))
                setContent(Container().apply { addChild(Text("Standalone")) })
            }
            dlg.open()
            standaloneDlg = dlg
            sem?.let { dispatch_semaphore_signal(it) }
        }
        // Pump main queue so runOnMainSync inside getPrimaryMonitorBounds/presentDialogWindow never stalls
        val deadlineMark = kotlin.time.TimeSource.Monotonic.markNow()
        while (standaloneDlg == null && deadlineMark.elapsedNow().inWholeMilliseconds < 5000L) {
            MacosNativeWindowHost.pumpVisualWindowEvents(16L)
        }
        if (sem != null) {
            dispatch_semaphore_wait(sem, DISPATCH_TIME_FOREVER)
        }
        assertTrue(bgMonitorOk, "Background getPrimaryMonitorBounds() must marshal cleanly to main thread")
        assertTrue(bgRectOk, "Background isWindowRectOnScreen() must marshal cleanly to main thread")
        assertTrue(assertNotNull(standaloneDlg).isWindowShown)
        assertEquals(
            NSApplicationActivationPolicy.NSApplicationActivationPolicyAccessory,
            NSApplication.sharedApplication.activationPolicy()
        )

        standaloneDlg?.close()
        app.mainWindow.close()
        app.dispose()
    }

    // -------------------------------------------------------------------------
    // Part 3: SeekBar Initial Z-Order Split-Threading (Zero Hover)
    // -------------------------------------------------------------------------

    private class TestSeekBarComposite : Composite(classPrefix = "seek-bar", tagName = "genericui:seek-bar") {
        val trackBtn = StyledButton("").apply {
            style.apply {
                setPosition(CssPosition.ABSOLUTE)
                setLeft(CssLength.px(0))
                setTop(CssLength.px(8))
                setWidth(CssLength.px(300))
                setHeight(CssLength.px(10))
                setZIndex(1)
                setBackgroundColor(RgbaColor(40, 40, 40, 255))
            }
        }
        val progressSegment = StyledComponent().apply {
            style.apply {
                setPosition(CssPosition.ABSOLUTE)
                setLeft(CssLength.px(0))
                setTop(CssLength.px(8))
                setWidth(CssLength.px(120))
                setHeight(CssLength.px(10))
                setZIndex(3)
                setPointerEvents(CssPointerEvents.NONE)
                setBackgroundColor(RgbaColor(240, 20, 20, 255)) // Bright red (z=3)
            }
        }
        val knobBtn = StyledButton("").apply {
            style.apply {
                setPosition(CssPosition.ABSOLUTE)
                setLeft(CssLength.px(114))
                setTop(CssLength.px(4))
                setWidth(CssLength.px(18))
                setHeight(CssLength.px(18))
                setZIndex(4)
                setBackgroundColor(RgbaColor(20, 235, 40, 255)) // Bright green (z=4)
            }
        }
        // Appended LAST in DOM (after progressSegment & knobBtn), just like SeekBar.preloadSegment!
        val preloadSegment = StyledComponent().apply {
            style.apply {
                setPosition(CssPosition.ABSOLUTE)
                setLeft(CssLength.px(0))
                setTop(CssLength.px(8))
                setWidth(CssLength.px(220))
                setHeight(CssLength.px(10))
                setZIndex(2)
                setPointerEvents(CssPointerEvents.NONE)
                setBackgroundColor(RgbaColor(30, 80, 240, 255)) // Bright blue (z=2)
            }
        }

        init {
            style.apply {
                setPosition(CssPosition.RELATIVE)
                setWidth(CssLength.px(300))
                setHeight(CssLength.px(26))
            }
            addCompositeChild(trackBtn)
            addCompositeChild(progressSegment)
            addCompositeChild(knobBtn)
            addCompositeChild(preloadSegment)
        }
    }

    @Test
    fun seekBarInitialZOrderRendersProgressAbovePreloadAndBelowKnobWithZeroHover() = withInteractiveMacosHost { factory ->
        val seekBar = TestSeekBarComposite()
        val overlayRoot = Container().apply {
            setAttribute("data-overlay-root", "true")
            style.apply {
                setDisplay(CssDisplay.FLEX)
                setWidth(CssLength.px(360))
                setHeight(CssLength.px(120))
            }
            addChild(seekBar)
        }
        val stageRoot = LayeredContainer().apply {
            setAttribute("data-overlay-host", "true")
            style.apply {
                setWidth(CssLength.px(360))
                setHeight(CssLength.px(120))
            }
            addFillLayer(overlayRoot, zIndex = 10)
        }

        // Hidden at launch, update value on Logic thread BEFORE showing and WITHOUT any hover!
        val window = Window(title = "SeekBar Initial Z-Order", initialSize = Size(360, 120)).apply {
            isVisible = false
            setContent(stageRoot)
        }
        GenericUiThreading.runOnLogic {
            seekBar.progressSegment.style.setWidth(CssLength.px(120))
            seekBar.preloadSegment.style.setWidth(CssLength.px(220))
        }
        GenericUiThreading.flushAllQueues()

        window.show(factory)
        window.isVisible = true
        MacosNativeWindowHost.pumpVisualWindowEvents(48L)

        val seekImpl = seekBar.ensureNativeImpl() as MacosStyledContainerNativeImpl
        val seekView = assertNotNull((seekImpl.nativeWidget as MacosViewHandle).nsView)
        val trackView = assertNotNull((seekBar.trackBtn.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView)
        val preloadView = assertNotNull((seekBar.preloadSegment.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView)
        val progressView = assertNotNull((seekBar.progressSegment.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView)
        val knobView = assertNotNull((seekBar.knobBtn.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView)

        val subviews = seekView.subviews.filterIsInstance<NSView>()
        val idxTrack = subviews.indexOf(trackView)
        val idxPreload = subviews.indexOf(preloadView)
        val idxProgress = subviews.indexOf(progressView)
        val idxKnob = subviews.indexOf(knobView)
        assertTrue(idxTrack in 0 until idxPreload, "track must precede preload ($idxTrack < $idxPreload)")
        assertTrue(idxPreload < idxProgress, "preload must precede progress ($idxPreload < $idxProgress)")
        assertTrue(idxProgress < idxKnob, "progress must precede knob ($idxProgress < $idxKnob)")

        val zTrack = trackView.layer?.zPosition ?: -99.0
        val zPreload = preloadView.layer?.zPosition ?: -99.0
        val zProgress = progressView.layer?.zPosition ?: -99.0
        val zKnob = knobView.layer?.zPosition ?: -99.0
        assertTrue(zTrack < zPreload && zPreload < zProgress && zProgress < zKnob,
            "CALayer.zPosition must ascend: track=$zTrack, preload=$zPreload, progress=$zProgress, knob=$zKnob"
        )

        val shot = assertNotNull(
            MacosNativeWindowHost.captureWindowScreenshot(
                window.ensureNativeImpl() as MacosWindowNativeImpl,
                360,
                120
            )
        )
        fun samplePixel(x: Int, y: Int): Triple<Int, Int, Int> {
            val o = (y * shot.width + x) * 4
            return Triple(
                shot.bytes[o].toInt() and 0xFF,
                shot.bytes[o + 1].toInt() and 0xFF,
                shot.bytes[o + 2].toInt() and 0xFF
            )
        }
        // Inside progressSegment (x=60, y=13) -> RED (above blue preload)
        val (pr, pg, pb) = samplePixel(60, 13)
        assertTrue(pr > 200 && pb < 60 && pg < 60, "progressSegment must paint red above preloadSegment without hover (got r=$pr g=$pg b=$pb)")

        // Inside preloadSegment beyond progress (x=170, y=13) -> BLUE
        val (br, bg, bb) = samplePixel(170, 13)
        assertTrue(bb > 200 && br < 60, "preloadSegment must paint blue between progress and preload end (got r=$br g=$bg b=$bb)")

        // Inside knobButton (x=123, y=13) -> GREEN (above progressSegment)
        val (kr, kg, kb) = samplePixel(123, 13)
        assertTrue(kg > 200 && kr < 60 && kb < 60, "knobButton must paint green above progressSegment (got r=$kr g=$kg b=$kb)")

        window.close()
    }

    // -------------------------------------------------------------------------
    // Part 4: Live CoreImage 10-Op backdrop-filter, No NSVisualEffectView, Live IOSurface Stage
    // -------------------------------------------------------------------------

    @Test
    fun liveBackdropFilter10OpsNoVisualEffectViewHigherZIsolationAndStageFrameRefresh() = withInteractiveMacosHost { factory ->
        val stageW = 320
        val stageH = 200
        var frameSwap = 1L
        var stageFrameMode = 0 // 0 = high-contrast colored vertical stripes + saturated cyan/red halves; 1 = solid green

        fun buildStagePixels(): ByteArray {
            val bytes = ByteArray(stageW * stageH * 4)
            for (y in 0 until stageH) {
                for (x in 0 until stageW) {
                    val o = (y * stageW + x) * 4
                    if (stageFrameMode == 0) {
                        when {
                            x < 80 -> { // White
                                bytes[o] = 255.toByte(); bytes[o + 1] = 255.toByte(); bytes[o + 2] = 255.toByte(); bytes[o + 3] = 255.toByte()
                            }
                            x < 160 -> { // Black (crisp edge at x=80)
                                bytes[o] = 0; bytes[o + 1] = 0; bytes[o + 2] = 0; bytes[o + 3] = 255.toByte()
                            }
                            x < 240 -> { // Saturated Orange-Red (BGRA)
                                bytes[o] = 20; bytes[o + 1] = 90; bytes[o + 2] = 240.toByte(); bytes[o + 3] = 255.toByte()
                            }
                            else -> { // Vivid Teal (BGRA)
                                bytes[o] = 220.toByte(); bytes[o + 1] = 180.toByte(); bytes[o + 2] = 30; bytes[o + 3] = 255.toByte()
                            }
                        }
                    } else {
                        // Frame 2: Bright Green (BGRA)
                        bytes[o] = 20; bytes[o + 1] = 220.toByte(); bytes[o + 2] = 20; bytes[o + 3] = 255.toByte()
                    }
                }
            }
            return bytes
        }

        val stageSurface = Container().apply {
            style.setWidth(CssLength.px(stageW))
            style.setHeight(CssLength.px(stageH))
        }
        val blurEdgeComp = StyledComponent().apply {
            backdropFilter = CssFilter(blur = CssLength.px(14f))
            style.apply {
                setPosition(CssPosition.ABSOLUTE)
                setLeft(CssLength.px(40))
                setTop(CssLength.px(40))
                setWidth(CssLength.px(80))
                setHeight(CssLength.px(60))
                setZIndex(10)
            }
        }
        val satHighContainer = StyledContainer().apply {
            backdropFilter = CssFilter(saturate = 1.4f)
            style.apply {
                setPosition(CssPosition.ABSOLUTE)
                setLeft(CssLength.px(170))
                setTop(CssLength.px(40))
                setWidth(CssLength.px(50))
                setHeight(CssLength.px(50))
                setZIndex(10)
            }
        }
        val satZeroContainer = StyledContainer().apply {
            backdropFilter = CssFilter(saturate = 0f)
            style.apply {
                setPosition(CssPosition.ABSOLUTE)
                setLeft(CssLength.px(170))
                setTop(CssLength.px(100))
                setWidth(CssLength.px(50))
                setHeight(CssLength.px(50))
                setZIndex(10)
            }
        }
        val overlayTitleBar = StyledContainer().apply {
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_WINDOW_TITLE_BAR)
            backdropFilter = CssFilter(blur = CssLength.px(22f), saturate = 1.4f)
            style.apply {
                setPosition(CssPosition.ABSOLUTE)
                setLeft(CssLength.px(0))
                setTop(CssLength.px(0))
                setWidth(CssLength.px(320))
                setHeight(CssLength.px(28))
                setZIndex(10)
            }
        }
        val backdropBtn = StyledButton("").apply {
            backdropFilter = CssFilter(blur = CssLength.px(8f), brightness = 1.2f)
            style.apply {
                setPosition(CssPosition.ABSOLUTE)
                setLeft(CssLength.px(250))
                setTop(CssLength.px(100))
                setWidth(CssLength.px(50))
                setHeight(CssLength.px(40))
                setZIndex(10)
            }
        }
        // Higher-Z sibling directly overlapping blurEdgeComp -> must NOT leak into blurEdgeComp's backdrop!
        val higherZBadge = StyledComponent().apply {
            backgroundColor = RgbaColor(255, 0, 255, 255)
            style.apply {
                setPosition(CssPosition.ABSOLUTE)
                setLeft(CssLength.px(75))
                setTop(CssLength.px(65))
                setWidth(CssLength.px(10))
                setHeight(CssLength.px(10))
                setZIndex(50)
            }
        }

        val root = LayeredContainer().apply {
            style.setWidth(CssLength.px(stageW))
            style.setHeight(CssLength.px(stageH))
            addFillLayer(stageSurface, zIndex = 0)
            addLayer(overlayTitleBar, LayerConstraint.float(offsetXPx = 0, offsetYPx = 0, zIndex = 10))
            addLayer(blurEdgeComp, LayerConstraint.float(offsetXPx = 40, offsetYPx = 40, zIndex = 10))
            addLayer(satHighContainer, LayerConstraint.float(offsetXPx = 170, offsetYPx = 40, zIndex = 10))
            addLayer(satZeroContainer, LayerConstraint.float(offsetXPx = 170, offsetYPx = 100, zIndex = 10))
            addLayer(backdropBtn, LayerConstraint.float(offsetXPx = 250, offsetYPx = 100, zIndex = 10))
            addLayer(higherZBadge, LayerConstraint.float(offsetXPx = 75, offsetYPx = 65, zIndex = 50))
        }

        val win = Window(title = "Backdrop 10-Op Stage", initialSize = Size(stageW, stageH)).apply {
            setContent(root)
        }
        win.show(factory)
        val stageWidget = stageSurface.ensureNativeImpl()!!.nativeWidget as DesktopNativeWidget
        DesktopInteractiveSurfaceSpec(
            surfaceWidth = { stageW },
            surfaceHeight = { stageH },
            swapCount = { frameSwap },
            captureBgra = { buildStagePixels() },
            contentFitMode = { ObjectFit.FILL }
        ).attachTo(stageWidget)

        MacosNativeWindowHost.pumpVisualWindowEvents(48L)
        val winImpl = win.ensureNativeImpl() as MacosWindowNativeImpl
        val rootView = assertNotNull((winImpl.nativeWidget as MacosViewHandle).nsView as? MacosWindowRootView)

        // 1. Assert NO NSVisualEffectView exists anywhere in view hierarchy
        fun containsVisualEffectView(v: NSView): Boolean {
            if (v is NSVisualEffectView) return true
            for (sub in v.subviews) {
                if (sub is NSView && containsVisualEffectView(sub)) return true
            }
            return false
        }
        assertFalse(containsVisualEffectView(rootView), "Window hierarchy must contain ZERO NSVisualEffectViews")

        val shot1 = assertNotNull(MacosNativeWindowHost.captureWindowScreenshot(winImpl, stageW, stageH))
        fun rgbAt(buf: com.nexustools.genericui.image.ImageBuffer, x: Int, y: Int): Triple<Int, Int, Int> {
            val o = (y * buf.width + x) * 4
            return Triple(
                buf.bytes[o].toInt() and 0xFF,
                buf.bytes[o + 1].toInt() and 0xFF,
                buf.bytes[o + 2].toInt() and 0xFF
            )
        }

        // Real Gaussian blur softens crisp white/black edge at x=80 inside blurEdgeComp (y=50)
        val (ebr, ebg, ebb) = rgbAt(shot1, 80, 50)
        assertTrue(ebr in 60..200 && ebg in 60..200 && ebb in 60..200,
            "Gaussian blur(14px) must soften white/black step edge (got r=$ebr g=$ebg b=$ebb)")

        // Higher-Z magenta badge (x=75..85, y=65..75) must NOT bleed into blurEdgeComp's underlay at (65, 70)
        val (adjR, adjG, adjB) = rgbAt(shot1, 65, 70)
        assertTrue(abs(adjR - adjG) < 30 && abs(adjG - adjB) < 30,
            "Higher-Z magenta sibling must not leak into lower-Z backdrop (got r=$adjR g=$adjG b=$adjB)")

        // saturate(1.4) vs saturate(0) chroma comparison over orange-red stripe (x=195)
        val (sr, sg, sb) = rgbAt(shot1, 195, 65)
        val (gr, gg, gb) = rgbAt(shot1, 195, 125)
        val satChroma = max(sr, max(sg, sb)) - min(sr, min(sg, sb))
        val grayChroma = max(gr, max(gg, gb)) - min(gr, min(gg, gb))
        assertTrue(satChroma > 120, "saturate(1.4) must have strong chroma (got $satChroma)")
        assertTrue(grayChroma <= 6, "saturate(0) must be grayscale (got chroma=$grayChroma: r=$gr g=$gg b=$gb)")

        // OverlayTitleBar has CoreImage backdropUnderlayView
        val barView = assertNotNull((overlayTitleBar.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView as? MacosStyledContainerView)
        assertNotNull(barView.surfaceSupport.backdropUnderlayView, "OverlayWindowTitleBar must mount backdropUnderlayView")

        // 2. Live IOSurface stage frame update refreshes backdropUnderlayView contents live
        val blurView = assertNotNull((blurEdgeComp.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView as? MacosStyledComponentView)
        val blurUnderlay = assertNotNull(blurView.surfaceSupport.backdropUnderlayView)
        stageFrameMode = 1
        frameSwap = 2L
        val session = assertNotNull(MacosNativeWindowHost.windowSessionForTest(winImpl))
        assertTrue(session.presentStageSurfaces(forceRefresh = false))
        assertNotNull(blurUnderlay.layer?.contents, "Stage frame update must refresh backdropUnderlayView contents")

        val shot2 = assertNotNull(MacosNativeWindowHost.captureWindowScreenshot(winImpl, stageW, stageH))
        val (fr, fg, fb) = rgbAt(shot2, 80, 50)
        assertTrue(fg > 180 && fr < 80 && fb < 80,
            "Live stage swap to green frame must update backdrop pixels to green (got r=$fr g=$fg b=$fb)")

        // 3. All 10 CSS filter ops on AppleBackdropFilterController produce expected distinct pixel results
        fun test10OpPixels(filter: CssFilter): Triple<Int, Int, Int> {
            val buf = IntArray(4 * 4) { (0xFF shl 24) or (200 shl 16) or (100 shl 8) or 50 }
            assertTrue(
                com.nexustools.genericui.apple.style.AppleBackdropFilterController.applyBackdropToPremulBgraRegion(
                    filter = filter,
                    pixels = buf,
                    strideWords = 4,
                    bufferWidth = 4,
                    bufferHeight = 4,
                    regionX = 0,
                    regionY = 0,
                    regionWidth = 4,
                    regionHeight = 4,
                    cornerRadiusPx = 0f,
                    componentOpacity = 1.0f
                )
            )
            val px = buf[5]
            return Triple((px ushr 16) and 0xFF, (px ushr 8) and 0xFF, px and 0xFF)
        }
        val (invR, invG, invB) = test10OpPixels(CssFilter(invert = 1f))
        assertTrue(invR < 80 && invB > 180, "invert(1) must invert RGB (got $invR, $invG, $invB)")
        val (briR, _, _) = test10OpPixels(CssFilter(brightness = 0.5f))
        assertTrue(briR in 80..120, "brightness(0.5) must darken (got $briR)")
        val (conR, conG, conB) = test10OpPixels(CssFilter(contrast = 0f))
        assertTrue(abs(conR - conG) <= 4 && abs(conG - conB) <= 4, "contrast(0) must converge toward neutral gray")
        val (gR, gG, gB) = test10OpPixels(CssFilter(grayscale = 1f))
        assertTrue(abs(gR - gG) <= 4 && abs(gG - gB) <= 4, "grayscale(1) must desaturate")
        val (hR, hG, hB) = test10OpPixels(CssFilter(hueRotateDeg = 120f))
        assertTrue(hG > hR && hG > hB, "hue-rotate(120deg) must shift red-dominant toward green ($hR,$hG,$hB)")
        val (_, _, sepB) = test10OpPixels(CssFilter(sepia = 1f))
        val (sepR, _, _) = test10OpPixels(CssFilter(sepia = 1f))
        assertTrue(sepR > sepB, "sepia(1) must stay warm (R > B)")
        val (opR, _, _) = test10OpPixels(CssFilter(opacity = 0.5f, brightness = 0.5f))
        assertTrue(opR < 180, "opacity(0.5) must blend toward base")
        val (dsR, _, _) = test10OpPixels(
            CssFilter(blur = CssLength.px(2f), saturate = 1.2f, dropShadow = CssShadow.px(1f, 1f, 2f, color = RgbaColor.BLACK))
        )
        assertTrue(dsR in 0..255)

        win.close()
    }
}
