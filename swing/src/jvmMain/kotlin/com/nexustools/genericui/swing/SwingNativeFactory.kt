package com.nexustools.genericui.swing

import com.nexustools.genericui.core.NativeFactory
import com.nexustools.genericui.image.ImageLoader
import com.nexustools.genericui.nativeimpl.action.ButtonNativeImpl
import com.nexustools.genericui.nativeimpl.action.DropdownNativeImpl
import com.nexustools.genericui.nativeimpl.content.ImageNativeImpl
import com.nexustools.genericui.nativeimpl.content.TableNativeImpl
import com.nexustools.genericui.nativeimpl.dialog.DialogWindowNativeImpl
import com.nexustools.genericui.nativeimpl.dialog.FileBrowserDialogNativeImpl
import com.nexustools.genericui.nativeimpl.dialog.StandardDialogsNativeImpl
import com.nexustools.genericui.nativeimpl.dock.*
import com.nexustools.genericui.nativeimpl.document.BookContainerNativeImpl
import com.nexustools.genericui.nativeimpl.feedback.ProgressBarNativeImpl
import com.nexustools.genericui.nativeimpl.feedback.SpinnerNativeImpl
import com.nexustools.genericui.nativeimpl.form.CheckboxNativeImpl
import com.nexustools.genericui.nativeimpl.form.RangeSliderNativeImpl
import com.nexustools.genericui.nativeimpl.form.SelectNativeImpl
import com.nexustools.genericui.nativeimpl.form.TextInputNativeImpl
import com.nexustools.genericui.nativeimpl.layout.ContainerNativeImpl
import com.nexustools.genericui.nativeimpl.layout.DividerNativeImpl
import com.nexustools.genericui.nativeimpl.layout.ScrollViewNativeImpl
import com.nexustools.genericui.nativeimpl.lazy.LazyGridNativeImpl
import com.nexustools.genericui.nativeimpl.lazy.LazyListNativeImpl
import com.nexustools.genericui.nativeimpl.lazy.TreeViewNativeImpl
import com.nexustools.genericui.nativeimpl.navigation.BottomNavigationBarNativeImpl
import com.nexustools.genericui.nativeimpl.navigation.NavbarNativeImpl
import com.nexustools.genericui.nativeimpl.tabs.TabContainerNativeImpl
import com.nexustools.genericui.nativeimpl.tabs.TabNativeImpl
import com.nexustools.genericui.nativeimpl.overlay.PopoverNativeImpl
import com.nexustools.genericui.nativeimpl.shell.*
import com.nexustools.genericui.nativeimpl.surface.CollapseNativeImpl
import com.nexustools.genericui.nativeimpl.system.*
import com.nexustools.genericui.nativeimpl.typography.MarkdownViewNativeImpl
import com.nexustools.genericui.nativeimpl.typography.TextNativeImpl

/**
 * Java Swing (`javax.swing.*` + Java2D + `ImageIO` + optional LWJGL OpenGL ES / Vulkan) implementation of [NativeFactory].
 *
 * Uses `JFileChooser` with `FILES_ONLY` and `DIRECTORIES_ONLY` folder mode ([SwingFileBrowserDialogImpl]),
 * `JScrollPane` viewport listeners for true lazy loading/unloading ([SwingLazyListImpl], [SwingLazyGridImpl],
 * [SwingTreeViewImpl]), `BufferedImage` + `Graphics2D` for [SwingCanvas2DImpl], runtime LWJGL detection for
 * [SwingGLCanvasImpl] and [SwingVKCanvasImpl], and `javax.imageio.ImageIO` ([SwingImageIoCodecImpl]) for [ImageLoader].
 */
class SwingNativeFactory : NativeFactory {
    override val backendName: String = "swing"

    init {
        ImageLoader.activeNativeCodec = SwingImageIoCodecImpl()
    }

    // Shell & Window
    override fun createApplication(): ApplicationNativeImpl<*> = SwingApplicationImpl()
    override fun createWindow(): WindowNativeImpl<*> = SwingWindowImpl()
    override fun createMenuBar(): MenuBarNativeImpl<*> = SwingMenuBarImpl()
    override fun createMenu(): MenuNativeImpl<*> = SwingMenuImpl()
    override fun createMenuItem(checkable: Boolean, radio: Boolean): MenuItemNativeImpl<*> = SwingMenuItemImpl(checkable, radio)
    override fun createContextMenu(): ContextMenuNativeImpl<*> = SwingContextMenuImpl()
    override fun createSplitPane(): SplitPaneNativeImpl<*> = SwingSplitPaneImpl()

    // Dockable Panel System (GIMP / Photoshop Studio)
    override fun createDockWorkspace(): DockWorkspaceNativeImpl<*> = SwingDockWorkspaceImpl()
    override fun createDockArea(): DockAreaNativeImpl<*> = SwingDockAreaImpl()
    override fun createDockGroup(): DockGroupNativeImpl<*> = SwingDockGroupImpl()
    override fun createDockPanel(): DockPanelNativeImpl<*> = SwingDockPanelImpl()
    override fun createDockWindow(): DockWindowNativeImpl<*> = SwingDockWindowImpl()
    override fun createToolPalette(): ToolPaletteNativeImpl<*> = SwingToolPaletteImpl()

    // Layout & Printable Book Documents
    override fun createContainer(classPrefix: String): ContainerNativeImpl<*> = SwingContainerImpl(classPrefix)
    override fun createBookContainer(): BookContainerNativeImpl<*> = SwingBookContainerImpl()
    override fun createScrollView(): ScrollViewNativeImpl<*> = SwingScrollViewImpl()
    override fun createDivider(): DividerNativeImpl<*> = SwingDividerImpl()

    // Typography & Content
    override fun createText(tagName: String, classPrefix: String): TextNativeImpl<*> = SwingTextImpl(tagName, classPrefix)
    override fun createMarkdownView(): MarkdownViewNativeImpl<*> = SwingMarkdownViewImpl()
    override fun createImage(): ImageNativeImpl<*> = SwingImageImpl()
    override fun createTable(): TableNativeImpl<*> = SwingTableImpl()

    // Action & Form
    override fun createButton(classPrefix: String): ButtonNativeImpl<*> = SwingButtonImpl(classPrefix)
    override fun createDropdown(): DropdownNativeImpl<*> = SwingDropdownImpl()
    override fun createTextInput(multiline: Boolean): TextInputNativeImpl<*> = SwingTextInputImpl(multiline)
    override fun createSelect(): SelectNativeImpl<*> = SwingSelectImpl()
    override fun createCheckbox(switchStyle: Boolean, radioStyle: Boolean): CheckboxNativeImpl<*> = SwingCheckboxImpl(switchStyle, radioStyle)
    override fun createRangeSlider(): RangeSliderNativeImpl<*> = SwingRangeSliderImpl()

    // Surface, Tabs, Navigation, Feedback & Overlay
    override fun createCollapse(): CollapseNativeImpl<*> = SwingCollapseImpl()
    override fun createTab(closable: Boolean): TabNativeImpl<*> = SwingTabImpl(closable)
    override fun createTabContainer(): TabContainerNativeImpl<*> = SwingTabContainerImpl()
    override fun createNavbar(): NavbarNativeImpl<*> = SwingNavbarImpl()
    override fun createBottomNavigationBar(): BottomNavigationBarNativeImpl<*> = SwingBottomNavigationBarImpl()
    override fun createProgressBar(): ProgressBarNativeImpl<*> = SwingProgressBarImpl()
    override fun createSpinner(): SpinnerNativeImpl<*> = SwingSpinnerImpl()
    override fun createPopover(): PopoverNativeImpl<*> = SwingPopoverImpl()

    // Dialogs & Native File/Folder Browser
    override fun createDialogWindow(): DialogWindowNativeImpl<*> = SwingDialogWindowImpl()
    override fun createFileBrowserDialog(): FileBrowserDialogNativeImpl = SwingFileBrowserDialogImpl()
    override fun createStandardDialogs(): StandardDialogsNativeImpl = SwingStandardDialogsImpl()

    // Lazy Virtualized Lists, Grids & Trees
    override fun createLazyList(): LazyListNativeImpl<*> = SwingLazyListImpl()
    override fun createLazyGrid(): LazyGridNativeImpl<*> = SwingLazyGridImpl()
    override fun createTreeView(): TreeViewNativeImpl<*> = SwingTreeViewImpl()

    // System Services
    override fun createPermissionManager(): PermissionManagerNativeImpl = SwingPermissionManagerImpl()
    override fun createSystemTray(): SystemTrayNativeImpl = SwingSystemTrayImpl()
    override fun createNotificationManager(): NotificationManagerNativeImpl = SwingNotificationManagerImpl()
    override fun createNetworkMonitor(): NetworkMonitorNativeImpl = SwingNetworkMonitorImpl()
    override fun createPreferenceStore(appName: String): PreferenceStoreNativeImpl = SwingPreferenceStoreImpl(appName)
}
