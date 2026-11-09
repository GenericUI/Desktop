package com.nexustools.genericui.windows

import com.nexustools.genericui.component.layout.LayoutFactory
import com.nexustools.genericui.core.NativeFactory
import com.nexustools.genericui.desktop.dialog.DesktopDialogWindowNativeImpl
import com.nexustools.genericui.desktop.dock.DesktopDockAreaNativeImpl
import com.nexustools.genericui.desktop.dock.DesktopDockGroupNativeImpl
import com.nexustools.genericui.desktop.dock.DesktopDockPanelNativeImpl
import com.nexustools.genericui.desktop.dock.DesktopToolPaletteNativeImpl
import com.nexustools.genericui.desktop.image.DesktopImageCodecHooks
import com.nexustools.genericui.desktop.layout.DesktopLayoutFactory
import com.nexustools.genericui.desktop.shell.DesktopContextMenuNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopMenuItemNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopMenuNativeImpl
import com.nexustools.genericui.desktop.system.DesktopPermissionManagerNativeImpl
import com.nexustools.genericui.desktop.system.DesktopUiTimerNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopPopoverNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopScrollViewNativeImpl
import com.nexustools.genericui.image.ImageLoader
import com.nexustools.genericui.nativeimpl.action.ButtonNativeImpl
import com.nexustools.genericui.nativeimpl.action.DropdownNativeImpl
import com.nexustools.genericui.nativeimpl.action.StyledButtonNativeImpl
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
import com.nexustools.genericui.nativeimpl.shell.*
import com.nexustools.genericui.nativeimpl.system.*
import com.nexustools.genericui.nativeimpl.tabs.TabContainerNativeImpl
import com.nexustools.genericui.nativeimpl.tabs.TabNativeImpl
import com.nexustools.genericui.nativeimpl.typography.MarkdownViewNativeImpl
import com.nexustools.genericui.nativeimpl.typography.TextNativeImpl
import com.nexustools.genericui.threading.UiThreadDispatcherNativeImpl
import com.nexustools.genericui.windows.impl.*

/**
 * Windows (`:windows`) implementation of [NativeFactory] backed by Win32 `HWND` controls (`comctl32.dll`),
 * Direct2D/DirectWrite, and WIC + `libjpeg-turbo` ([WindowsWicImageCodecNativeImpl]).
 */
class WindowsNativeFactory : NativeFactory {

    override val backendTarget: com.nexustools.genericui.enums.system.BackendTarget =
        com.nexustools.genericui.enums.system.BackendTarget.Windows

    override val backendName: String = "Windows (Win32 / Direct2D / ANGLE / Vulkan / VLC)"

    override val layoutFactory: LayoutFactory = DesktopLayoutFactory

    override fun supportsTcp(): Boolean = true

    override fun supportsUdp(): Boolean = true

    val imageCodec: WindowsWicImageCodecNativeImpl = WindowsWicImageCodecNativeImpl()

    init {
        DesktopImageCodecHooks.install(backendName)
        ImageLoader.activeNativeCodec = imageCodec
    }

    override fun createApplication(): ApplicationNativeImpl<*> = WindowsApplicationNativeImpl()

    override fun createWindow(): WindowNativeImpl<*> = WindowsWindowNativeImpl()

    override fun createMenuBar(): MenuBarNativeImpl<*> =
        WindowsMenuBarNativeImpl(Win32HwndHandle("Win32HMenuBar")).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    override fun createMenu(): MenuNativeImpl<*> =
        DesktopMenuNativeImpl(Win32HwndHandle("Win32HMenu")).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    override fun createMenuItem(checkable: Boolean, radio: Boolean): MenuItemNativeImpl<*> =
        DesktopMenuItemNativeImpl(Win32HwndHandle("Win32MenuItem"), checkable, radio).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    override fun createContextMenu(): ContextMenuNativeImpl<*> =
        DesktopContextMenuNativeImpl(Win32HwndHandle("Win32PopupMenu")).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    override fun createDockWorkspace(): DockWorkspaceNativeImpl<*> =
        WindowsDockWorkspaceNativeImpl(Win32HwndHandle("Win32DockWorkspaceW")).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    override fun createDockArea(): DockAreaNativeImpl<*> =
        DesktopDockAreaNativeImpl(Win32HwndHandle("Win32DockAreaW")).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    override fun createDockGroup(): DockGroupNativeImpl<*> =
        DesktopDockGroupNativeImpl(Win32HwndHandle("Win32DockGroupW")).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    override fun createDockPanel(): DockPanelNativeImpl<*> =
        DesktopDockPanelNativeImpl(Win32HwndHandle("Win32DockPanelW")).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    override fun createDockWindow(): DockWindowNativeImpl<*> = WindowsDockWindowNativeImpl()

    override fun createToolPalette(): ToolPaletteNativeImpl<*> =
        DesktopToolPaletteNativeImpl(Win32HwndHandle("Win32ToolPaletteW")).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

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
            else -> WindowsContainerNativeImpl(Win32HwndHandle("GenericUIContainerW"), classPrefix)
        }
        impl.nativeWidget.attributes["nativeImpl"] = impl
        return impl
    }

    fun createCollapsibleSection(classPrefix: String = "accordion"): WindowsCollapsibleSectionNativeImpl =
        WindowsCollapsibleSectionNativeImpl(Win32HwndHandle("GenericUICollapsibleSectionClass"), classPrefix).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    fun createSplitPane(classPrefix: String = "split-pane"): WindowsSplitPaneNativeImpl =
        WindowsSplitPaneNativeImpl(Win32HwndHandle("GenericUISplitPaneClass"), classPrefix).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    fun createMdiContainer(classPrefix: String = "mdi-container"): WindowsMdiContainerNativeImpl =
        WindowsMdiContainerNativeImpl(Win32HwndHandle("MDICLIENT"), classPrefix).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    fun createDrawer(classPrefix: String = "offcanvas"): WindowsDrawerNativeImpl =
        WindowsDrawerNativeImpl(Win32HwndHandle("GenericUIDrawerClass"), classPrefix).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    fun createStatusBar(classPrefix: String = "status-bar"): WindowsStatusBarNativeImpl =
        WindowsStatusBarNativeImpl(Win32HwndHandle("msctls_statusbar32"), classPrefix).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    fun createToolBar(): WindowsToolBarNativeImpl =
        WindowsToolBarNativeImpl(Win32HwndHandle("ToolbarWindow32")).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    fun createRichTextEditor(): WindowsRichTextEditorNativeImpl =
        WindowsRichTextEditorNativeImpl(Win32HwndHandle("RICHEDIT50W")).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    fun createCodeEditor(): WindowsCodeEditorNativeImpl =
        WindowsCodeEditorNativeImpl(Win32HwndHandle("GenericUICodeEditorClass")).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    fun createColorPicker(): WindowsColorPickerNativeImpl =
        WindowsColorPickerNativeImpl(Win32HwndHandle("GenericUIColorPickerClass")).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    fun createDateTimePicker(): WindowsDateTimePickerNativeImpl =
        WindowsDateTimePickerNativeImpl(Win32HwndHandle("SysDateTimePick32")).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    fun createFilePickerDropZone(classPrefix: String = "file-drop-zone"): WindowsFilePickerDropZoneNativeImpl =
        WindowsFilePickerDropZoneNativeImpl(Win32HwndHandle("GenericUIFileDropZoneClass"), classPrefix).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    override fun createStyledContainer(classPrefix: String, tagName: String): StyledContainerNativeImpl<*> =
        WindowsStyledContainerNativeImpl(Win32HwndHandle("GenericUIStyledContainerW"), classPrefix, tagName).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    override fun createBookContainer(): BookContainerNativeImpl<*> =
        WindowsBookContainerNativeImpl(Win32HwndHandle("GenericUIBookContainerW")).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    override fun createScrollView(): ScrollViewNativeImpl<*> =
        DesktopScrollViewNativeImpl(Win32HwndHandle("GenericUIScrollViewW")).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    override fun createDivider(): DividerNativeImpl<*> =
        WindowsDividerNativeImpl(Win32HwndHandle("STATIC_SS_ETCHEDHORZ")).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    override fun createText(tagName: String, classPrefix: String): TextNativeImpl<*> =
        WindowsTextNativeImpl(Win32HwndHandle("STATIC"), tagName, classPrefix).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    override fun createMarkdownView(): MarkdownViewNativeImpl<*> =
        WindowsMarkdownViewNativeImpl(Win32HwndHandle("RichEdit50W")).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    override fun createImage(): ImageNativeImpl<*> =
        WindowsImageNativeImpl(Win32HwndHandle("STATIC_SS_BITMAP")).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    override fun createTable(): TableNativeImpl<*> =
        WindowsTableNativeImpl(Win32HwndHandle("SysListView32")).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    override fun createButton(classPrefix: String): ButtonNativeImpl<*> =
        WindowsButtonNativeImpl(Win32HwndHandle("BUTTON"), classPrefix).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    override fun createStyledButton(classPrefix: String): StyledButtonNativeImpl<*> =
        WindowsStyledButtonNativeImpl(Win32HwndHandle("BUTTON"), classPrefix).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    override fun createDropdown(): DropdownNativeImpl<*> =
        WindowsDropdownNativeImpl(Win32HwndHandle("BUTTON_BS_SPLITBUTTON")).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    override fun createTextInput(multiline: Boolean): TextInputNativeImpl<*> =
        WindowsTextInputNativeImpl(Win32HwndHandle(if (multiline) "EDIT_MULTILINE" else "EDIT"), multiline).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    override fun createSelect(): SelectNativeImpl<*> =
        WindowsSelectNativeImpl(Win32HwndHandle("COMBOBOX")).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    override fun createCheckbox(switchStyle: Boolean, radioStyle: Boolean): CheckboxNativeImpl<*> =
        WindowsCheckboxNativeImpl(Win32HwndHandle("BUTTON_BS_AUTOCHECKBOX"), switchStyle, radioStyle).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    override fun createRangeSlider(): RangeSliderNativeImpl<*> =
        WindowsRangeSliderNativeImpl(Win32HwndHandle("msctls_trackbar32")).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    override fun createTab(closable: Boolean): TabNativeImpl<*> =
        WindowsTabNativeImpl(Win32HwndHandle(if (closable) "SysTabItem32_Closable" else "SysTabItem32"), closable).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    override fun createTabContainer(): TabContainerNativeImpl<*> =
        WindowsTabContainerNativeImpl(Win32HwndHandle("SysTabControl32")).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    override fun createNavbar(): NavbarNativeImpl<*> =
        WindowsToolBarNativeImpl(Win32HwndHandle("ReBarWindow32")).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    override fun createBottomNavigationBar(): BottomNavigationBarNativeImpl<*> =
        WindowsBottomNavigationBarNativeImpl(Win32HwndHandle("ToolbarWindow32")).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    override fun createProgressBar(): ProgressBarNativeImpl<*> =
        WindowsProgressBarNativeImpl(Win32HwndHandle("msctls_progress32")).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    override fun createSpinner(): SpinnerNativeImpl<*> =
        WindowsSpinnerNativeImpl(Win32HwndHandle("Win32ActivityRingW")).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    override fun createPopover(): PopoverNativeImpl<*> =
        DesktopPopoverNativeImpl(Win32HwndHandle("tooltips_class32")).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    override fun createDialogWindow(): DialogWindowNativeImpl<*> =
        DesktopDialogWindowNativeImpl(
            Win32HwndHandle(
                win32ClassName = "GenericUIWindowClass",
                dwStyle = Win32HwndHandle.WS_POPUP or Win32HwndHandle.WS_CAPTION or Win32HwndHandle.WS_SYSMENU or Win32HwndHandle.WS_THICKFRAME,
                dwExStyle = Win32HwndHandle.WS_EX_DLGMODALFRAME
            ).apply {
                attributes["isSeparateNativeWindow"] = true
                attributes["inWindowOverlay"] = false
                attributes["attachedToMainWindow"] = true
            }
        ).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    override fun createFileBrowserDialog(): FileBrowserDialogNativeImpl =
        WindowsFileBrowserDialogNativeImpl()

    override fun createStandardDialogs(): StandardDialogsNativeImpl =
        WindowsStandardDialogsNativeImpl()

    override fun createLazyList(): LazyListNativeImpl<*> =
        WindowsLazyListNativeImpl(Win32HwndHandle("SysListView32_LVS_OWNERDATA")).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    override fun createLazyGrid(): LazyGridNativeImpl<*> =
        WindowsLazyGridNativeImpl(Win32HwndHandle("SysListView32_ICON_OWNERDATA")).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    override fun createTreeView(): TreeViewNativeImpl<*> =
        WindowsTreeViewNativeImpl(Win32HwndHandle("SysTreeView32")).also {
            it.nativeWidget.attributes["nativeImpl"] = it
        }

    override fun createUiThreadDispatcher(): UiThreadDispatcherNativeImpl =
        WindowsUiThreadDispatcherNativeImpl()

    override fun createUiTimerScheduler(): UiTimerNativeImpl =
        DesktopUiTimerNativeImpl("Win32 SetTimer")

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

    override fun createResourceResolver(): com.nexustools.genericui.resources.ResourceResolverNativeImpl =
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
