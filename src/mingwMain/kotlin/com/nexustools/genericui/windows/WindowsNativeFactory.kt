@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.component.layout.LayoutFactory
import com.nexustools.genericui.core.NativeFactory
import com.nexustools.genericui.desktop.core.DesktopCanvasWidgetHooks
import com.nexustools.genericui.desktop.image.DesktopImageCodecHooks
import com.nexustools.genericui.desktop.layout.DesktopLayoutFactory
import com.nexustools.genericui.desktop.system.DesktopPermissionManagerNativeImpl
import com.nexustools.genericui.enums.system.BackendTarget
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.image.ImageLoader
import com.nexustools.genericui.nativeimpl.action.ButtonNativeImpl
import com.nexustools.genericui.nativeimpl.action.DropdownNativeImpl
import com.nexustools.genericui.nativeimpl.action.StyledButtonNativeImpl
import com.nexustools.genericui.nativeimpl.content.ImageNativeImpl
import com.nexustools.genericui.nativeimpl.content.TableNativeImpl
import com.nexustools.genericui.nativeimpl.dialog.DialogWindowNativeImpl
import com.nexustools.genericui.nativeimpl.dialog.FileBrowserDialogNativeImpl
import com.nexustools.genericui.nativeimpl.dialog.StandardDialogsNativeImpl
import com.nexustools.genericui.nativeimpl.dock.DockAreaNativeImpl
import com.nexustools.genericui.nativeimpl.dock.DockGroupNativeImpl
import com.nexustools.genericui.nativeimpl.dock.DockPanelNativeImpl
import com.nexustools.genericui.nativeimpl.dock.DockWindowNativeImpl
import com.nexustools.genericui.nativeimpl.dock.DockWorkspaceNativeImpl
import com.nexustools.genericui.nativeimpl.dock.ToolPaletteNativeImpl
import com.nexustools.genericui.nativeimpl.document.BookContainerNativeImpl
import com.nexustools.genericui.nativeimpl.feedback.ProgressBarNativeImpl
import com.nexustools.genericui.nativeimpl.feedback.SpinnerNativeImpl
import com.nexustools.genericui.nativeimpl.form.CheckboxNativeImpl
import com.nexustools.genericui.nativeimpl.form.RangeSliderNativeImpl
import com.nexustools.genericui.nativeimpl.form.SelectNativeImpl
import com.nexustools.genericui.nativeimpl.form.StyledTextInputNativeImpl
import com.nexustools.genericui.nativeimpl.form.TextInputNativeImpl
import com.nexustools.genericui.nativeimpl.io.FileSystemNativeImpl
import com.nexustools.genericui.nativeimpl.layout.ContainerNativeImpl
import com.nexustools.genericui.nativeimpl.layout.DividerNativeImpl
import com.nexustools.genericui.nativeimpl.layout.ScrollViewNativeImpl
import com.nexustools.genericui.nativeimpl.layout.StyledContainerNativeImpl
import com.nexustools.genericui.nativeimpl.lazy.LazyGridNativeImpl
import com.nexustools.genericui.nativeimpl.lazy.LazyListNativeImpl
import com.nexustools.genericui.nativeimpl.lazy.TreeViewNativeImpl
import com.nexustools.genericui.nativeimpl.navigation.BottomNavigationBarNativeImpl
import com.nexustools.genericui.nativeimpl.navigation.NavbarNativeImpl
import com.nexustools.genericui.nativeimpl.net.HttpClientNativeImpl
import com.nexustools.genericui.nativeimpl.net.TcpSocketNativeImpl
import com.nexustools.genericui.nativeimpl.net.UdpSocketNativeImpl
import com.nexustools.genericui.nativeimpl.net.WebSocketNativeImpl
import com.nexustools.genericui.nativeimpl.overlay.PopoverNativeImpl
import com.nexustools.genericui.nativeimpl.shell.ApplicationNativeImpl
import com.nexustools.genericui.nativeimpl.shell.ContextMenuNativeImpl
import com.nexustools.genericui.nativeimpl.shell.MenuBarNativeImpl
import com.nexustools.genericui.nativeimpl.shell.MenuItemNativeImpl
import com.nexustools.genericui.nativeimpl.shell.MenuNativeImpl
import com.nexustools.genericui.nativeimpl.system.SystemTrayNativeImpl
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl
import com.nexustools.genericui.nativeimpl.surface.StyledComponentNativeImpl
import com.nexustools.genericui.nativeimpl.system.AnimationNativeImpl
import com.nexustools.genericui.nativeimpl.system.DatabaseNativeImpl
import com.nexustools.genericui.nativeimpl.system.NetworkMonitorNativeImpl
import com.nexustools.genericui.nativeimpl.system.NotificationManagerNativeImpl
import com.nexustools.genericui.nativeimpl.system.PermissionManagerNativeImpl
import com.nexustools.genericui.nativeimpl.system.PreferenceStoreNativeImpl
import com.nexustools.genericui.nativeimpl.system.UiTimerNativeImpl
import com.nexustools.genericui.nativeimpl.system.UserDirectoryNativeImpl
import com.nexustools.genericui.nativeimpl.tabs.TabContainerNativeImpl
import com.nexustools.genericui.nativeimpl.tabs.TabNativeImpl
import com.nexustools.genericui.nativeimpl.typography.MarkdownViewNativeImpl
import com.nexustools.genericui.nativeimpl.typography.TextNativeImpl
import com.nexustools.genericui.resources.ResourceResolverNativeImpl
import com.nexustools.genericui.threading.UiThreadDispatcherNativeImpl
import com.nexustools.genericui.windows.impl.*

/**
 * Windows (`:windows`) implementation of [NativeFactory] backed by genuine retained Win32
 * ComCtl32 v6 child `HWND` controls (`comctl32.dll`), Direct2D/WIC (`Win32Direct2dBackdropBlurEngine`),
 * layered stage surfaces (`Win32LayeredStageHostHwnd`), and `libjpeg-turbo` ([WindowsWicImageCodecNativeImpl]).
 */
class WindowsNativeFactory : NativeFactory {

    override val backendTarget: BackendTarget = BackendTarget.Windows

    override val backendName: String = "Windows (Win32 / ComCtl32 v6 / Direct2D / ANGLE / Vulkan / VLC)"

    override val layoutFactory: LayoutFactory = DesktopLayoutFactory

    override fun supportsTcp(): Boolean = true

    override fun supportsUdp(): Boolean = true

    override val supportsWindowStatePersistence: Boolean = true

    override fun getPrimaryMonitorBounds(): Rect? = Win32NativeWindowHost.getPrimaryMonitorBounds()

    override fun isWindowRectOnScreen(rect: Rect): Boolean = Win32NativeWindowHost.isWindowRectOnScreen(rect)

    val imageCodec: WindowsWicImageCodecNativeImpl = WindowsWicImageCodecNativeImpl()

    init {
        Win32WinMain.ensureWindowsControlsInitialized()
        DesktopImageCodecHooks.install(backendName)
        ImageLoader.activeNativeCodec = imageCodec
        DesktopCanvasWidgetHooks.createInteractiveSurfaceWidget = { role ->
            Win32HwndHandle("GenericUIInteractiveSurfaceW").apply {
                attributes["interactiveSurfaceRole"] = role
            }
        }
    }

    override fun createApplication(): ApplicationNativeImpl<*> {
        DesktopCanvasWidgetHooks.createInteractiveSurfaceWidget = { role ->
            Win32HwndHandle("GenericUIInteractiveSurfaceW").apply {
                attributes["interactiveSurfaceRole"] = role
            }
        }
        return WindowsApplicationNativeImpl()
    }

    override fun createWindow(): WindowNativeImpl<*> = WindowsWindowNativeImpl()

    override fun createMenuBar(): MenuBarNativeImpl<*> = WindowsMenuBarNativeImpl()

    override fun createMenu(): MenuNativeImpl<*> = WindowsMenuNativeImpl()

    override fun createMenuItem(checkable: Boolean, radio: Boolean): MenuItemNativeImpl<*> =
        WindowsMenuItemNativeImpl(checkable = checkable, radio = radio)

    override fun createContextMenu(): ContextMenuNativeImpl<*> = WindowsContextMenuNativeImpl()

    override fun createDockWorkspace(): DockWorkspaceNativeImpl<*> = WindowsDockWorkspaceNativeImpl()

    override fun createDockArea(): DockAreaNativeImpl<*> = WindowsDockAreaNativeImpl()

    override fun createDockGroup(): DockGroupNativeImpl<*> = WindowsDockGroupNativeImpl()

    override fun createDockPanel(): DockPanelNativeImpl<*> = WindowsDockPanelNativeImpl()

    override fun createDockWindow(): DockWindowNativeImpl<*> = WindowsDockWindowNativeImpl()

    override fun createToolPalette(): ToolPaletteNativeImpl<*> = WindowsToolPaletteNativeImpl()

    override fun createContainer(classPrefix: String): ContainerNativeImpl<*> =
        createContainer(classPrefix, "div")

    override fun createContainer(classPrefix: String, tagName: String): ContainerNativeImpl<*> {
        val prefixLower = classPrefix.lowercase()
        val tagLower = tagName.lowercase()
        val impl = when {
            prefixLower.contains("split-pane") || tagLower.contains("splitpane") ->
                createSplitPane(classPrefix)
            prefixLower.contains("mdi-container") || prefixLower.contains("mdi-window") || tagLower.contains("mdi") ->
                createMdiContainer(classPrefix)
            prefixLower.contains("accordion") || prefixLower.contains("collapse") || tagLower == "details" ->
                createCollapsibleSection(classPrefix)
            prefixLower.contains("file-drop-zone") || prefixLower.contains("drop-zone") ->
                createFilePickerDropZone(classPrefix)
            prefixLower.contains("offcanvas") ||
                prefixLower.contains("side-nav") ||
                prefixLower.contains("drawer") ||
                prefixLower.contains("navigation-rail") ->
                createDrawer(classPrefix)
            prefixLower.contains("status-bar") || tagLower.contains("statusbar") ->
                createStatusBar(classPrefix)
            else -> WindowsContainerNativeImpl(
                widget = Win32HwndHandle("GenericUIContainerW"),
                classPrefix = classPrefix,
                tagName = tagName
            )
        }
        impl.nativeWidget.attributes["nativeImpl"] = impl
        return impl
    }

    fun createCollapsibleSection(classPrefix: String = "accordion"): WindowsCollapsibleSectionNativeImpl =
        WindowsCollapsibleSectionNativeImpl(Win32HwndHandle("GenericUICollapsibleSectionW"), classPrefix)

    fun createSplitPane(classPrefix: String = "split-pane"): WindowsSplitPaneNativeImpl =
        WindowsSplitPaneNativeImpl(Win32HwndHandle("GenericUISplitPaneW"), classPrefix)

    fun createMdiContainer(classPrefix: String = "mdi-container"): WindowsMdiContainerNativeImpl =
        WindowsMdiContainerNativeImpl(Win32HwndHandle("GenericUIMdiContainerW"), classPrefix)

    fun createDrawer(classPrefix: String = "offcanvas"): WindowsDrawerNativeImpl =
        WindowsDrawerNativeImpl(Win32HwndHandle("GenericUIDrawerW"), classPrefix)

    fun createStatusBar(classPrefix: String = "status-bar"): WindowsStatusBarNativeImpl =
        WindowsStatusBarNativeImpl(Win32HwndHandle("GenericUIStatusBarW"), classPrefix)

    fun createToolBar(): WindowsToolBarNativeImpl = WindowsToolBarNativeImpl()

    fun createRichTextEditor(): WindowsRichTextEditorNativeImpl = WindowsRichTextEditorNativeImpl()

    fun createCodeEditor(): WindowsCodeEditorNativeImpl = WindowsCodeEditorNativeImpl()

    fun createColorPicker(): WindowsColorPickerNativeImpl = WindowsColorPickerNativeImpl()

    fun createDateTimePicker(): WindowsDateTimePickerNativeImpl = WindowsDateTimePickerNativeImpl()

    fun createFilePickerDropZone(classPrefix: String = "file-drop-zone"): WindowsFilePickerDropZoneNativeImpl =
        WindowsFilePickerDropZoneNativeImpl(Win32HwndHandle("GenericUIFileDropZoneW"), classPrefix)

    override fun createStyledContainer(classPrefix: String, tagName: String): StyledContainerNativeImpl<*> =
        WindowsStyledContainerNativeImpl(Win32HwndHandle("GenericUIStyledContainerW"), classPrefix, tagName)

    override fun createStyledComponent(classPrefix: String, tagName: String): StyledComponentNativeImpl<*> =
        WindowsStyledComponentNativeImpl(Win32HwndHandle("GenericUIStyledComponentW"), classPrefix, tagName)

    override fun createBookContainer(): BookContainerNativeImpl<*> = WindowsBookContainerNativeImpl()

    override fun createScrollView(): ScrollViewNativeImpl<*> = WindowsScrollViewNativeImpl()

    override fun createScrollView(tagName: String): ScrollViewNativeImpl<*> =
        WindowsScrollViewNativeImpl().also {
            it.nativeWidget.attributes["tagName"] = tagName
        }

    override fun createDivider(): DividerNativeImpl<*> = WindowsDividerNativeImpl()

    override fun createText(tagName: String, classPrefix: String): TextNativeImpl<*> =
        WindowsTextNativeImpl(Win32HwndHandle("Static"), tagName, classPrefix)

    override fun createMarkdownView(): MarkdownViewNativeImpl<*> = WindowsMarkdownViewNativeImpl()

    override fun createImage(): ImageNativeImpl<*> = WindowsImageNativeImpl()

    override fun createTable(): TableNativeImpl<*> = WindowsTableNativeImpl()

    override fun createButton(classPrefix: String): ButtonNativeImpl<*> =
        WindowsButtonNativeImpl(Win32HwndHandle("Button"), classPrefix)

    override fun createStyledButton(classPrefix: String): StyledButtonNativeImpl<*> =
        WindowsStyledButtonNativeImpl(Win32HwndHandle("GenericUIStyledButtonW"), classPrefix)

    override fun createDropdown(): DropdownNativeImpl<*> = WindowsDropdownNativeImpl()

    override fun createTextInput(multiline: Boolean): TextInputNativeImpl<*> =
        WindowsTextInputNativeImpl(Win32HwndHandle("Edit"), multiline)

    override fun createStyledTextInput(multiline: Boolean, classPrefix: String): StyledTextInputNativeImpl<*> =
        WindowsStyledTextInputNativeImpl(
            widget = Win32HwndHandle("GenericUIStyledTextInputW"),
            multiline = multiline,
            classPrefix = classPrefix
        )

    override fun createSelect(): SelectNativeImpl<*> = WindowsSelectNativeImpl()

    override fun createCheckbox(switchStyle: Boolean, radioStyle: Boolean): CheckboxNativeImpl<*> =
        WindowsCheckboxNativeImpl(
            widget = Win32HwndHandle(if (switchStyle) "GenericUISwitchW" else "Button"),
            switchStyle = switchStyle,
            radioStyle = radioStyle
        )

    override fun createRangeSlider(): RangeSliderNativeImpl<*> = WindowsRangeSliderNativeImpl()

    override fun createTab(closable: Boolean): TabNativeImpl<*> =
        WindowsTabNativeImpl(closable = closable)

    override fun createTabContainer(): TabContainerNativeImpl<*> = WindowsTabContainerNativeImpl()

    override fun createNavbar(): NavbarNativeImpl<*> = WindowsToolBarNativeImpl()

    override fun createBottomNavigationBar(): BottomNavigationBarNativeImpl<*> =
        WindowsBottomNavigationBarNativeImpl()

    override fun createProgressBar(): ProgressBarNativeImpl<*> = WindowsProgressBarNativeImpl()

    override fun createSpinner(): SpinnerNativeImpl<*> = WindowsSpinnerNativeImpl()

    override fun createPopover(): PopoverNativeImpl<*> = WindowsPopoverNativeImpl()

    override fun createDialogWindow(): DialogWindowNativeImpl<*> = WindowsDialogWindowNativeImpl()

    override fun createFileBrowserDialog(): FileBrowserDialogNativeImpl =
        WindowsFileBrowserDialogNativeImpl()

    override fun createStandardDialogs(): StandardDialogsNativeImpl =
        WindowsStandardDialogsNativeImpl()

    override fun createLazyList(): LazyListNativeImpl<*> = WindowsLazyListNativeImpl()

    override fun createLazyGrid(): LazyGridNativeImpl<*> = WindowsLazyGridNativeImpl()

    override fun createTreeView(): TreeViewNativeImpl<*> = WindowsTreeViewNativeImpl()

    override fun createUiThreadDispatcher(): UiThreadDispatcherNativeImpl =
        WindowsUiThreadDispatcherNativeImpl()

    override fun createUiTimerScheduler(): UiTimerNativeImpl =
        WindowsUiTimerNativeImpl()

    override fun createAnimationScheduler(): AnimationNativeImpl =
        WindowsAnimationNativeImpl()

    override fun createPermissionManager(): PermissionManagerNativeImpl =
        DesktopPermissionManagerNativeImpl("Windows AppCapability")

    override fun createSystemTray(): SystemTrayNativeImpl = WindowsSystemTrayNativeImpl()

    override fun createNotificationManager(): NotificationManagerNativeImpl =
        WindowsNotificationManagerNativeImpl()

    override fun createNetworkMonitor(): NetworkMonitorNativeImpl = WindowsNetworkMonitorNativeImpl()

    override fun createPreferenceStore(appName: String): PreferenceStoreNativeImpl =
        WindowsPreferenceStoreImpl(appName)

    override fun createConfigDirectory(appName: String, subdirectory: String): UserDirectoryNativeImpl =
        WindowsConfigDirectoryImpl(appName, subdirectory)

    override fun createCacheDirectory(appName: String, subdirectory: String): UserDirectoryNativeImpl =
        WindowsCacheDirectoryImpl(appName, subdirectory)

    override fun createFileSystem(): FileSystemNativeImpl =
        WindowsFileSystemNativeImpl()

    override fun createDatabase(databaseName: String, filePath: String?, version: Int): DatabaseNativeImpl =
        WindowsDatabaseNativeImpl(
            databaseName = databaseName,
            filePath = filePath ?: "$databaseName.db",
            version = version
        )

    override fun createResourceResolver(): ResourceResolverNativeImpl =
        WindowsResourceResolverImpl()

    override fun createTcpSocket(): TcpSocketNativeImpl =
        WindowsTcpSocketNativeImpl()

    override fun createUdpSocket(): UdpSocketNativeImpl =
        WindowsUdpSocketNativeImpl()

    override fun createHttpClient(): HttpClientNativeImpl =
        WindowsHttpClientNativeImpl()

    override fun createWebSocket(): WebSocketNativeImpl =
        WindowsWebSocketNativeImpl()
}
