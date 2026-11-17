package com.nexustools.genericui.swing

import com.nexustools.genericui.component.dock.DockHeaderAction
import com.nexustools.genericui.component.dock.ToolPaletteItem
import com.nexustools.genericui.component.lazy.TreeNode
import com.nexustools.genericui.component.system.Notification
import com.nexustools.genericui.component.system.NotificationChannel
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.dock.DockGroupMode
import com.nexustools.genericui.enums.dock.DockPosition
import com.nexustools.genericui.enums.system.PermissionStatus
import com.nexustools.genericui.enums.system.PermissionType
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.image.ImageColorSpace
import com.nexustools.genericui.image.ImageDecodeOptions
import com.nexustools.genericui.image.ImageEncodeOptions
import com.nexustools.genericui.image.ImageFormat
import com.nexustools.genericui.image.ImageLoader
import com.nexustools.genericui.image.ImageSource
import com.nexustools.genericui.image.PixelFormat
import com.nexustools.genericui.style.color.ImageTintMode
import com.nexustools.genericui.style.color.RgbaColor
import java.awt.CheckboxMenuItem
import java.awt.GraphicsEnvironment
import java.awt.Menu
import java.awt.event.MouseEvent
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.JTextField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwingSystemServicesAndWidgetsTest {

    @Test
    fun standardDialogsUseTestRespondersAndFailFastInHeadlessMode() {
        val dialogs = SwingStandardDialogsImpl()
        if (GraphicsEnvironment.isHeadless()) {
            assertFailsWith<IllegalStateException> {
                dialogs.showAlert(null, "Warn", "Headless check", BootstrapType.WARNING, "OK") {}
            }
            assertFailsWith<IllegalStateException> {
                dialogs.showConfirm(null, "Confirm", "Proceed?", "Yes", "No", BootstrapType.PRIMARY) {}
            }
        }

        var alertCalled = false
        var confirmResult: Boolean? = null
        var promptResult: String? = null
        var choiceResult: Int? = null
        var colorResult: RgbaColor? = null

        dialogs.alertResponder = { _, _, _, _ -> alertCalled = true }
        dialogs.confirmResponder = { _, _, _, _, _ -> false }
        dialogs.promptResponder = { _, _, _, _, _, _ -> "typed-value" }
        dialogs.choiceResponder = { _, _, opts, _ -> opts.lastIndex }
        dialogs.colorResponder = { _, _ -> RgbaColor(12, 34, 56, 200) }

        dialogs.showAlert(null, "Hi", "Msg", BootstrapType.INFO, "OK") {}
        dialogs.showConfirm(null, "Q", "Body", "OK", "Cancel", BootstrapType.DANGER) { confirmResult = it }
        dialogs.showPrompt(null, "P", "Enter:", "def", "hint", "OK", "Cancel") { promptResult = it }
        dialogs.showChoice(null, "C", "Pick", listOf("A", "B", "C"), 0) { choiceResult = it }
        dialogs.showColorChooser(null, "Color", RgbaColor(0, 0, 0, 255)) { colorResult = it }

        assertTrue(alertCalled)
        assertEquals(false, confirmResult)
        assertEquals("typed-value", promptResult)
        assertEquals(2, choiceResult)
        assertEquals(RgbaColor(12, 34, 56, 200), colorResult)
        dialogs.dispose()
    }

    @Test
    fun systemTrayAndNotificationsValidateSupportBuildAwtMenuAndBadge() {
        val tray = SwingSystemTrayImpl()
        val notifications = SwingNotificationManagerImpl()
        notifications.registerChannel(NotificationChannel(id = "alerts", name = "Alerts", highImportance = true))

        assertEquals(SwingSystemTrayImpl.checkSystemTraySupported(), tray.isSupported)
        if (!tray.isSupported) {
            assertFailsWith<UnsupportedOperationException> { tray.setIcon("icon.png") }
            assertFailsWith<UnsupportedOperationException> { tray.setTooltip("GenericUI") }
            assertFailsWith<UnsupportedOperationException> { tray.setBadgeText("3") }
            assertFailsWith<UnsupportedOperationException> { tray.setContextMenu(SwingContextMenuImpl()) }
            assertFailsWith<UnsupportedOperationException> { tray.setOnTrayIconClicked {} }
            assertFailsWith<UnsupportedOperationException> {
                notifications.showNotification(Notification(id = "n1", channelId = "alerts", title = "Alert", body = "Hi"))
            }
        }

        val ctxMenu = SwingContextMenuImpl()
        var openClicked = 0
        val openItem = SwingMenuItemImpl().apply {
            setLabel("Open")
            setOnClick { openClicked++ }
        }
        val toggleItem = SwingMenuItemImpl(checkable = true).apply {
            setLabel("Auto Sync")
            setChecked(true)
        }
        val subMenu = SwingMenuImpl().apply { setLabel("More") }
        subMenu.addChildNative(SwingMenuItemImpl().apply { setLabel("About") }, -1)

        ctxMenu.addChildNative(openItem, -1)
        ctxMenu.addChildNative(SwingDividerImpl(), -1)
        ctxMenu.addChildNative(toggleItem, -1)
        ctxMenu.addChildNative(subMenu, -1)

        val awtMenu = assertNotNull(SwingSystemTrayImpl.buildAwtPopupMenu(ctxMenu))
        assertEquals(4, awtMenu.itemCount)
        assertEquals("Open", awtMenu.getItem(0).label)
        assertTrue(awtMenu.getItem(2) is CheckboxMenuItem)
        assertTrue((awtMenu.getItem(2) as CheckboxMenuItem).state)
        assertTrue(awtMenu.getItem(3) is Menu)
        assertEquals(1, (awtMenu.getItem(3) as Menu).itemCount)

        awtMenu.getItem(0).actionListeners.forEach {
            it.actionPerformed(java.awt.event.ActionEvent(awtMenu.getItem(0), 0, "Open"))
        }
        assertEquals(1, openClicked)

        val baseImg = SwingSystemTrayImpl.createDefaultTrayImage()
        val badged = SwingSystemTrayImpl.renderImageWithBadge(baseImg, "9") as BufferedImage
        val bottomRightPixel = badged.getRGB(badged.width - 2, badged.height - 2)
        assertTrue(((bottomRightPixel ushr 24) and 0xFF) > 0)

        notifications.dispose()
        tray.dispose()
    }

    @Test
    fun networkMonitorPollsBackgroundAndPermissionManagerChecksRealState() {
        val onlineState = AtomicBoolean(true)
        val transitions = mutableListOf<Boolean>()
        val changedLatch = CountDownLatch(2)
        val monitor = SwingNetworkMonitorImpl(pollIntervalMs = 20L) { onlineState.get() }
        assertTrue(monitor.isOnline())
        monitor.setOnConnectivityChanged { online ->
            transitions.add(online)
            changedLatch.countDown()
        }
        onlineState.set(false)
        Thread.sleep(60L)
        onlineState.set(true)
        assertTrue(changedLatch.await(2, TimeUnit.SECONDS))
        assertEquals(listOf(false, true), transitions)
        monitor.dispose()

        val perms = SwingPermissionManagerImpl()
        val expectedTrayPerm = if (SwingSystemTrayImpl.checkSystemTraySupported()) {
            PermissionStatus.GRANTED
        } else {
            PermissionStatus.NOT_APPLICABLE
        }
        assertEquals(expectedTrayPerm, perms.checkPermission(PermissionType.NOTIFICATIONS))
        assertEquals(expectedTrayPerm, perms.checkPermission(PermissionType.SYSTEM_TRAY))
        assertEquals(PermissionStatus.GRANTED, perms.checkPermission(PermissionType.READ_MEDIA_STORAGE))

        var requestedResult: PermissionStatus? = null
        perms.runtimePermissionHandler = { _, reply -> reply(PermissionStatus.DENIED) }
        assertEquals(PermissionStatus.PROMPT_REQUIRED, perms.checkPermission(PermissionType.CAMERA))
        perms.requestPermission(PermissionType.CAMERA) { requestedResult = it }
        assertEquals(PermissionStatus.DENIED, requestedResult)
        assertEquals(PermissionStatus.DENIED, perms.checkPermission(PermissionType.CAMERA))
        perms.dispose()
    }

    @Test
    fun rangeSliderAndTextInputWireRealSwingListenersAndModes() {
        val sliderImpl = SwingRangeSliderImpl()
        val sliderEvents = mutableListOf<Double>()
        sliderImpl.setRange(10.0, 50.0, 2.0)
        assertEquals(10, sliderImpl.nativeWidget.minimum)
        assertEquals(50, sliderImpl.nativeWidget.maximum)
        sliderImpl.setOnValueChanged { sliderEvents.add(it) }
        sliderImpl.nativeWidget.value = 26
        assertEquals(26.0, sliderImpl.getValue(), 0.001)
        assertEquals(listOf(26.0), sliderEvents)

        sliderImpl.setRange(0.0, 1.0, 0.05)
        sliderImpl.setValue(0.5)
        assertEquals(0.5, sliderImpl.getValue(), 0.01)

        val textImpl = SwingTextInputImpl()
        val textEvents = mutableListOf<String>()
        textImpl.setOnValueChanged { textEvents.add(it) }
        textImpl.setLabel("Username")
        textImpl.setPlaceholder("Enter handle")
        assertEquals("Username", textImpl.getLabel())
        assertEquals("Enter handle", textImpl.getPlaceholder())

        assertTrue(textImpl.activeEditor is JTextField)
        textImpl.activeEditor.document.insertString(0, "alice", null)
        assertEquals("alice", textImpl.getValue())
        assertEquals(listOf("alice"), textEvents)

        textImpl.setPasswordMode(true)
        assertTrue(textImpl.activeEditor is JPasswordField)
        assertEquals("alice", textImpl.getValue())

        textImpl.setMultiline(true)
        assertTrue(textImpl.activeEditor is JTextArea)
        assertEquals("alice", textImpl.getValue())
        textImpl.activeEditor.document.insertString(5, "\nbob", null)
        assertEquals("alice\nbob", textImpl.getValue())
        assertTrue(textEvents.last() == "alice\nbob")
        textImpl.setFocusedState(true)
        assertTrue(textImpl.isFocusedState())
        textImpl.setFocusedState(false)
    }

    @Test
    fun treeViewNavbarBottomNavToolPaletteDockingAndTabsWorkEndToEnd() {
        val childNode = TreeNode<String>(id = "child-1", label = "Child One")
        val rootNode = TreeNode<String>(
            id = "root-1",
            label = "Root One",
            isExpanded = true,
            children = mutableListOf(childNode)
        )
        val treeImpl = SwingTreeViewImpl()
        var selectedNodeId: String? = null
        var doubleClickedNodeId: String? = null
        var contextNodeId: String? = null
        treeImpl.setOnNodeSelected { selectedNodeId = it.id }
        treeImpl.setOnNodeDoubleClicked { doubleClickedNodeId = it.id }
        treeImpl.setOnNodeContextMenu { n, _, _ -> contextNodeId = n.id }
        treeImpl.setRootNodes(listOf(rootNode))
        treeImpl.tree.setBounds(0, 0, 320, 200)
        treeImpl.tree.doLayout()

        val rowBounds = assertNotNull(treeImpl.tree.getRowBounds(1))
        val clickX = rowBounds.x + 12
        val clickY = rowBounds.y + rowBounds.height / 2
        val singleClick = MouseEvent(treeImpl.tree, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, clickX, clickY, 1, false, MouseEvent.BUTTON1)
        val doubleClick = MouseEvent(treeImpl.tree, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, clickX, clickY, 2, false, MouseEvent.BUTTON1)
        val popupClick = MouseEvent(treeImpl.tree, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, clickX, clickY, 1, true, MouseEvent.BUTTON3)
        treeImpl.tree.mouseListeners.forEach {
            it.mouseClicked(singleClick)
            it.mouseClicked(doubleClick)
            it.mousePressed(popupClick)
        }
        assertEquals("child-1", selectedNodeId)
        assertEquals("child-1", doubleClickedNodeId)
        assertEquals("child-1", contextNodeId)
        treeImpl.updateViewportMetrics(
            scrollOffsetPx = 0f,
            viewportHeightPx = 200f,
            firstActiveIndex = 0,
            lastActiveIndex = 1,
            leadingSpacerPx = 0f,
            trailingSpacerPx = 0f,
            totalContentExtentPx = 56f
        )
        assertEquals(56f, treeImpl.nativeWidget.getClientProperty("totalContentExtentPx"))

        val navbar = SwingNavbarImpl()
        navbar.setBrandTitle("Studio")
        navbar.setShowBrand(true)
        assertTrue(navbar.isBrandVisible())
        val navBtn = SwingButtonImpl().apply { setText("File") }
        navbar.addChildNative(navBtn, -1)
        assertEquals(2, navbar.nativeWidget.componentCount)

        val bottomNav = SwingBottomNavigationBarImpl()
        var pickedNavId: String? = null
        bottomNav.setOnItemSelected { pickedNavId = it }
        bottomNav.setItems(listOf(Triple("home", "Home", null), Triple("settings", "Settings", null)))
        assertEquals("home", bottomNav.getSelectedItemId())
        assertNotNull(bottomNav.buttonForItem("settings")).doClick()
        assertEquals("settings", pickedNavId)

        val palette = SwingToolPaletteImpl()
        var selectedTool: String? = null
        var swappedPair: Pair<RgbaColor, RgbaColor>? = null
        palette.setColumns(2)
        palette.setTools(
            listOf(
                ToolPaletteItem(id = "brush", name = "Brush", iconSvgOrPath = ""),
                ToolPaletteItem(id = "pen", name = "Pen", iconSvgOrPath = "")
            ),
            activeToolId = "brush"
        )
        palette.setForegroundColor(RgbaColor(20, 40, 60, 255))
        palette.setBackgroundColor(RgbaColor(200, 210, 220, 255))
        palette.setOnToolSelected { selectedTool = it }
        palette.setOnColorsSwapped { fg, bg -> swappedPair = fg to bg }
        assertNotNull(palette.toolButtonForId("pen")).doClick()
        assertEquals("pen", selectedTool)
        palette.swapColorsButton.doClick()
        assertEquals(RgbaColor(200, 210, 220, 255) to RgbaColor(20, 40, 60, 255), swappedPair)

        val workspace = SwingDockWorkspaceImpl()
        val leftArea = SwingDockAreaImpl().apply { setPosition(DockPosition.LEFT) }
        assertEquals(DockPosition.LEFT, leftArea.getDockPosition())
        val dockGroup = SwingDockGroupImpl()
        val dockPanel = SwingDockPanelImpl()
        var actionClicks = 0
        dockPanel.setTitle("Layers")
        dockPanel.setHeaderActions(listOf(DockHeaderAction(id = "add", label = "+") { actionClicks++ }))
        dockPanel.addChildNative(SwingTextImpl(), -1)
        assertNotNull(dockPanel.headerActionButton("add")).doClick()
        assertEquals(1, actionClicks)
        dockPanel.setCollapsed(true)
        assertFalse(dockPanel.contentPanel.isVisible)

        dockGroup.addChildNative(dockPanel, -1)
        assertEquals("Layers", dockGroup.tabbedPane.getTitleAt(0))
        dockGroup.setGroupMode(DockGroupMode.VERTICAL_SPLIT)
        assertEquals(DockGroupMode.VERTICAL_SPLIT, dockGroup.getGroupMode())

        leftArea.addChildNative(dockGroup, -1)
        workspace.setCentralDocumentNative(SwingContainerImpl())
        workspace.attachDockAreaNative(DockPosition.LEFT, leftArea)
        val workspacePanel = workspace.nativeWidget as JPanel
        assertTrue(workspacePanel.getComponent(0) is JSplitPane)

        val dockWin = SwingDockWindowImpl()
        var dockCloseCalled = false
        dockWin.setTitle("Inspector")
        dockWin.setWindowBounds(Rect(20, 30, 300, 200))
        dockWin.setOnDockWindowCloseRequested { dockCloseCalled = true }
        val closeEvent = if (!GraphicsEnvironment.isHeadless()) {
            WindowEvent(javax.swing.JDialog(), WindowEvent.WINDOW_CLOSING)
        } else {
            null
        }
        dockWin.windowClosingListener.windowClosing(closeEvent)
        assertTrue(dockCloseCalled)
        dockWin.dispose()

        val tab = SwingTabImpl(initialClosable = true)
        var closeClicked = false
        var editedTitle: String? = null
        tab.setTitleNative("main.kt")
        tab.setDirtyNative(true)
        assertTrue(tab.dirtyLabel.isVisible)
        tab.setEditableNative(true)
        tab.setOnCloseClicked { closeClicked = true }
        tab.setOnTitleEdited { editedTitle = it }
        tab.beginTitleEdit()
        assertTrue(tab.titleEditor.isVisible)
        tab.titleEditor.text = "renamed.kt"
        tab.commitTitleEdit()
        assertEquals("renamed.kt", editedTitle)
        assertEquals("renamed.kt", tab.titleVal)
        tab.closeButton.doClick()
        assertTrue(closeClicked)
        tab.dispose()
    }

    @Test
    fun swingImageSupportsTintRecoloringPlaceholderAndAlphaCodecRoundTrip() {
        val imgImpl = SwingImageImpl()
        val buf = ImageBuffer(2, 2, PixelFormat.RGBA_8888, ImageColorSpace.SRGB)
        for (y in 0 until 2) {
            for (x in 0 until 2) {
                buf.setPixel(x, y, RgbaColor(200, 200, 200, 255))
            }
        }
        imgImpl.setImageBuffer(buf)
        imgImpl.setTintColor(RgbaColor(255, 0, 0, 255))
        imgImpl.setTintMode(ImageTintMode.SRC_IN)
        val tinted = assertNotNull(imgImpl.renderedBuffer)
        assertEquals(255, tinted.getPixel(0, 0).r)
        assertEquals(0, tinted.getPixel(0, 0).g)
        assertEquals(0, tinted.getPixel(0, 0).b)

        imgImpl.setSourceUrl("https://example.invalid/nonexistent.png")
        assertTrue(imgImpl.isLoading())
        assertNotNull((imgImpl.nativeWidget as JLabel).icon)
        assertNull(imgImpl.renderedBuffer)
        imgImpl.dispose()

        val codec = SwingImageIoCodecImpl()
        val pngInput = ImageBuffer(2, 1, PixelFormat.RGBA_8888, ImageColorSpace.SRGB)
        pngInput.setPixel(0, 0, RgbaColor(100, 150, 200, 128))
        pngInput.setPixel(1, 0, RgbaColor(10, 20, 30, 255))
        val encoded = assertNotNull(codec.encode(pngInput, ImageEncodeOptions(format = ImageFormat.PNG)))
        val decoded = assertNotNull(codec.decode(ImageSource.fromBytes(encoded), ImageDecodeOptions()))
        assertEquals(128, decoded.getPixel(0, 0).a)
        assertEquals(255, decoded.getPixel(1, 0).a)
        codec.dispose()
    }
}
