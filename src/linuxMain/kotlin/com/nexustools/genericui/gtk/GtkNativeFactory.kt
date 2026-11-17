package com.nexustools.genericui.gtk

import com.nexustools.genericui.component.layout.LayoutFactory
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.core.NativeFactory
import com.nexustools.genericui.desktop.dock.DesktopDockAreaNativeImpl
import com.nexustools.genericui.desktop.dock.DesktopDockGroupNativeImpl
import com.nexustools.genericui.desktop.dock.DesktopDockPanelNativeImpl
import com.nexustools.genericui.desktop.dock.DesktopDockWorkspaceNativeImpl
import com.nexustools.genericui.desktop.dock.DesktopToolPaletteNativeImpl
import com.nexustools.genericui.desktop.image.DesktopImageCodecHooks
import com.nexustools.genericui.desktop.layout.DesktopLayoutFactory
import com.nexustools.genericui.desktop.lazy.DesktopLazyListNativeImpl
import com.nexustools.genericui.desktop.lazy.DesktopTreeViewNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopContextMenuNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopMenuBarNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopMenuItemNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopMenuNativeImpl
import com.nexustools.genericui.desktop.system.DesktopPermissionManagerNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopBookContainerNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopBottomNavigationBarNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopDividerNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopImageNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopNavbarNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopPopoverNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopScrollViewNativeImpl
import com.nexustools.genericui.enums.system.BackendTarget
import com.nexustools.genericui.gtk.impl.GtkButtonNativeImpl
import com.nexustools.genericui.gtk.impl.GtkCheckboxNativeImpl
import com.nexustools.genericui.gtk.impl.GtkContainerNativeImpl
import com.nexustools.genericui.gtk.impl.GtkDropdownNativeImpl
import com.nexustools.genericui.gtk.impl.GtkLazyGridNativeImpl
import com.nexustools.genericui.gtk.impl.GtkMarkdownViewNativeImpl
import com.nexustools.genericui.gtk.impl.GtkProgressBarNativeImpl
import com.nexustools.genericui.gtk.impl.GtkRangeSliderNativeImpl
import com.nexustools.genericui.gtk.impl.GtkSelectNativeImpl
import com.nexustools.genericui.gtk.impl.GtkSpinnerNativeImpl
import com.nexustools.genericui.gtk.impl.GtkTabContainerNativeImpl
import com.nexustools.genericui.gtk.impl.GtkTabNativeImpl
import com.nexustools.genericui.gtk.impl.GtkTableNativeImpl
import com.nexustools.genericui.gtk.impl.GtkTextInputNativeImpl
import com.nexustools.genericui.gtk.impl.GtkTextNativeImpl
import com.nexustools.genericui.image.ImageLoader
import com.nexustools.genericui.linux.LinuxCurlHttpClientNativeImpl
import com.nexustools.genericui.linux.LinuxDbusNotificationManagerNativeImpl
import com.nexustools.genericui.linux.LinuxGdkPixbufImageCodecNativeImpl
import com.nexustools.genericui.linux.LinuxGioNetworkMonitorNativeImpl
import com.nexustools.genericui.linux.LinuxPosixFileSystemNativeImpl
import com.nexustools.genericui.linux.LinuxPosixTcpSocketNativeImpl
import com.nexustools.genericui.linux.LinuxPosixUdpSocketNativeImpl
import com.nexustools.genericui.linux.LinuxResourceResolverImpl
import com.nexustools.genericui.linux.LinuxRfc6455WebSocketNativeImpl
import com.nexustools.genericui.linux.LinuxSqliteDatabaseNativeImpl
import com.nexustools.genericui.linux.LinuxXdgCacheDirectoryImpl
import com.nexustools.genericui.linux.LinuxXdgConfigDirectoryImpl
import com.nexustools.genericui.linux.LinuxXdgPreferenceStoreImpl
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
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl
import com.nexustools.genericui.nativeimpl.system.DatabaseNativeImpl
import com.nexustools.genericui.nativeimpl.system.NetworkMonitorNativeImpl
import com.nexustools.genericui.nativeimpl.system.NotificationManagerNativeImpl
import com.nexustools.genericui.nativeimpl.system.PermissionManagerNativeImpl
import com.nexustools.genericui.nativeimpl.system.PreferenceStoreNativeImpl
import com.nexustools.genericui.nativeimpl.system.SystemTrayNativeImpl
import com.nexustools.genericui.nativeimpl.system.UiTimerNativeImpl
import com.nexustools.genericui.nativeimpl.system.UserDirectoryNativeImpl
import com.nexustools.genericui.nativeimpl.tabs.TabContainerNativeImpl
import com.nexustools.genericui.nativeimpl.tabs.TabNativeImpl
import com.nexustools.genericui.nativeimpl.typography.MarkdownViewNativeImpl
import com.nexustools.genericui.nativeimpl.typography.TextNativeImpl
import com.nexustools.genericui.resources.ResourceResolverNativeImpl
import com.nexustools.genericui.threading.UiThreadDispatcherNativeImpl

/**
 * GTK 4 + Libadwaita (`linuxMain`) implementation of [NativeFactory] backed by real shared libraries:
 * `libgtk-4.so.1`, `libadwaita-1.so.0`, `libcairo.so.2`, `libpangocairo-1.0.so.0`, `libglib-2.0.so.0`,
 * `libgio-2.0.so.0`, `libgdk_pixbuf-2.0.so.0`, `libwebp.so.7`, `libsqlite3.so.0`, `libcurl.so.4`,
 * and OpenSSL `libssl.so.3` / POSIX sockets.
 */
class GtkNativeFactory : NativeFactory {

    override val backendTarget: BackendTarget = BackendTarget.Gtk

    override val backendName: String = "GTK4 (Libadwaita / Cairo / PangoCairo / GIO / libcurl / sqlite3)"

    override val layoutFactory: LayoutFactory = DesktopLayoutFactory

    override fun supportsTcp(): Boolean = true

    override fun supportsUdp(): Boolean = true

    override fun supportsMulticastUdp(): Boolean = true

    val imageCodec: LinuxGdkPixbufImageCodecNativeImpl = LinuxGdkPixbufImageCodecNativeImpl()

    init {
        DesktopImageCodecHooks.install(backendName)
        ImageLoader.activeNativeCodec = imageCodec
    }

    override fun createApplication(): ApplicationNativeImpl<*> = GtkApplicationNativeImpl()

    override fun createWindow(): WindowNativeImpl<*> = GtkWindowNativeImpl()

    override fun createMenuBar(): MenuBarNativeImpl<*> =
        DesktopMenuBarNativeImpl(GtkWidgetHandle("GtkPopoverMenuBar"))

    override fun createMenu(): MenuNativeImpl<*> =
        DesktopMenuNativeImpl(GtkWidgetHandle("GMenu"))

    override fun createMenuItem(checkable: Boolean, radio: Boolean): MenuItemNativeImpl<*> =
        DesktopMenuItemNativeImpl(GtkWidgetHandle("GMenuItem"), checkable, radio)

    override fun createContextMenu(): ContextMenuNativeImpl<*> =
        DesktopContextMenuNativeImpl(GtkWidgetHandle("GtkPopoverMenu"))

    override fun createDockWorkspace(): DockWorkspaceNativeImpl<*> =
        DesktopDockWorkspaceNativeImpl(GtkWidgetHandle("AdwOverlaySplitView"))

    override fun createDockArea(): DockAreaNativeImpl<*> =
        DesktopDockAreaNativeImpl(GtkWidgetHandle("GtkBox_DockArea"))

    override fun createDockGroup(): DockGroupNativeImpl<*> =
        DesktopDockGroupNativeImpl(GtkWidgetHandle("AdwTabView_DockGroup"))

    override fun createDockPanel(): DockPanelNativeImpl<*> =
        DesktopDockPanelNativeImpl(GtkWidgetHandle("AdwBin_DockPanel"))

    override fun createDockWindow(): DockWindowNativeImpl<*> = GtkDockWindowNativeImpl()

    override fun createToolPalette(): ToolPaletteNativeImpl<*> =
        DesktopToolPaletteNativeImpl(GtkWidgetHandle("GtkFlowBox_ToolPalette"))

    override fun createContainer(classPrefix: String): ContainerNativeImpl<*> =
        GtkContainerNativeImpl(GtkWidgetHandle("GtkBox"), classPrefix)

    override fun createStyledContainer(classPrefix: String, tagName: String): StyledContainerNativeImpl<*> =
        GtkStyledContainerNativeImpl(GtkWidgetHandle("GtkBox_Styled"), classPrefix, tagName)

    override fun createBookContainer(): BookContainerNativeImpl<*> =
        DesktopBookContainerNativeImpl(GtkWidgetHandle("GtkBox_BookPrintPreview"))

    override fun createScrollView(): ScrollViewNativeImpl<*> =
        DesktopScrollViewNativeImpl(GtkWidgetHandle("GtkScrolledWindow"))

    override fun createDivider(): DividerNativeImpl<*> =
        DesktopDividerNativeImpl(GtkWidgetHandle("GtkSeparator"))

    override fun createText(tagName: String, classPrefix: String): TextNativeImpl<*> =
        GtkTextNativeImpl(GtkWidgetHandle("GtkLabel"), tagName, classPrefix)

    override fun createMarkdownView(): MarkdownViewNativeImpl<*> =
        GtkMarkdownViewNativeImpl(GtkWidgetHandle("GtkTextView_Markdown"))

    override fun createImage(): ImageNativeImpl<*> =
        DesktopImageNativeImpl(GtkWidgetHandle("GtkPicture"))

    override fun createTable(): TableNativeImpl<*> =
        GtkTableNativeImpl(GtkWidgetHandle("GtkColumnView"))

    override fun createButton(classPrefix: String): ButtonNativeImpl<*> =
        GtkButtonNativeImpl(GtkWidgetHandle("GtkButton"), classPrefix)

    override fun createStyledButton(classPrefix: String): StyledButtonNativeImpl<*> =
        GtkStyledButtonNativeImpl(GtkWidgetHandle("GtkButton_Styled"), classPrefix)

    override fun createDropdown(): DropdownNativeImpl<*> =
        GtkDropdownNativeImpl(GtkWidgetHandle("GtkMenuButton"))

    override fun createTextInput(multiline: Boolean): TextInputNativeImpl<*> =
        GtkTextInputNativeImpl(GtkWidgetHandle(if (multiline) "GtkTextView" else "GtkEntry"), multiline)

    override fun createSelect(): SelectNativeImpl<*> =
        GtkSelectNativeImpl(GtkWidgetHandle("GtkDropDown"))

    override fun createCheckbox(switchStyle: Boolean, radioStyle: Boolean): CheckboxNativeImpl<*> =
        GtkCheckboxNativeImpl(
            GtkWidgetHandle(if (switchStyle) "GtkSwitch" else "GtkCheckButton"),
            switchStyle,
            radioStyle
        )

    override fun createRangeSlider(): RangeSliderNativeImpl<*> =
        GtkRangeSliderNativeImpl(GtkWidgetHandle("GtkScale"))

    override fun createTab(closable: Boolean): TabNativeImpl<*> =
        GtkTabNativeImpl(GtkWidgetHandle(if (closable) "AdwTabPage_Closable" else "AdwTabPage"), closable)

    override fun createTabContainer(): TabContainerNativeImpl<*> =
        GtkTabContainerNativeImpl(GtkWidgetHandle("AdwViewStack"))

    override fun createNavbar(): NavbarNativeImpl<*> =
        DesktopNavbarNativeImpl(GtkWidgetHandle("AdwHeaderBar"))

    override fun createBottomNavigationBar(): BottomNavigationBarNativeImpl<*> =
        DesktopBottomNavigationBarNativeImpl(GtkWidgetHandle("AdwViewSwitcherBar"))

    override fun createProgressBar(): ProgressBarNativeImpl<*> =
        GtkProgressBarNativeImpl(GtkWidgetHandle("GtkProgressBar"))

    override fun createSpinner(): SpinnerNativeImpl<*> =
        GtkSpinnerNativeImpl(GtkWidgetHandle("AdwSpinner"))

    override fun createPopover(): PopoverNativeImpl<*> =
        DesktopPopoverNativeImpl(GtkWidgetHandle("GtkPopover"))

    override fun createDialogWindow(): DialogWindowNativeImpl<*> =
        GtkDialogWindowNativeImpl()

    override fun createFileBrowserDialog(): FileBrowserDialogNativeImpl =
        GtkFileBrowserDialogNativeImpl()

    override fun createStandardDialogs(): StandardDialogsNativeImpl =
        GtkStandardDialogsNativeImpl()

    override fun createLazyList(): LazyListNativeImpl<*> =
        DesktopLazyListNativeImpl(GtkWidgetHandle("GtkListView"))

    override fun createLazyGrid(): LazyGridNativeImpl<*> =
        GtkLazyGridNativeImpl(GtkWidgetHandle("GtkGridView"))

    override fun createTreeView(): TreeViewNativeImpl<*> =
        DesktopTreeViewNativeImpl(GtkWidgetHandle("GtkListView_GtkTreeListModel"))

    override fun createUiThreadDispatcher(): UiThreadDispatcherNativeImpl =
        GtkUiThreadDispatcherNativeImpl(
            isSplitDispatchActive = { GtkApplicationNativeImpl.interactiveGtkGuiEnabled },
            onUiDrainComplete = { GtkNativeWindowHost.requestRedraw() }
        )

    override fun createUiTimerScheduler(): UiTimerNativeImpl =
        GtkUiTimerNativeImpl()

    override fun createPermissionManager(): PermissionManagerNativeImpl =
        DesktopPermissionManagerNativeImpl("XDG Desktop Portal PermissionStore")

    override fun createSystemTray(): SystemTrayNativeImpl = GtkSystemTrayNativeImpl()

    override fun createNotificationManager(): NotificationManagerNativeImpl =
        LinuxDbusNotificationManagerNativeImpl(appName = GenericUI.applicationName)

    override fun createNetworkMonitor(): NetworkMonitorNativeImpl = LinuxGioNetworkMonitorNativeImpl()

    override fun createPreferenceStore(appName: String): PreferenceStoreNativeImpl =
        LinuxXdgPreferenceStoreImpl(appName)

    override fun createConfigDirectory(appName: String, subdirectory: String): UserDirectoryNativeImpl =
        LinuxXdgConfigDirectoryImpl(appName, subdirectory)

    override fun createCacheDirectory(appName: String, subdirectory: String): UserDirectoryNativeImpl =
        LinuxXdgCacheDirectoryImpl(appName, subdirectory)

    override fun createFileSystem(): FileSystemNativeImpl =
        LinuxPosixFileSystemNativeImpl { GtkFileBrowserDialogNativeImpl() }

    override fun createDatabase(
        databaseName: String,
        filePath: String?,
        version: Int
    ): DatabaseNativeImpl = LinuxSqliteDatabaseNativeImpl(
        databaseName = databaseName,
        filePath = filePath ?: "$databaseName.db",
        version = version
    )

    override fun createResourceResolver(): ResourceResolverNativeImpl =
        LinuxResourceResolverImpl()

    override fun createTcpSocket(): TcpSocketNativeImpl =
        LinuxPosixTcpSocketNativeImpl()

    override fun createUdpSocket(): UdpSocketNativeImpl =
        LinuxPosixUdpSocketNativeImpl()

    override fun createHttpClient(): HttpClientNativeImpl =
        LinuxCurlHttpClientNativeImpl()

    override fun createWebSocket(): WebSocketNativeImpl =
        LinuxRfc6455WebSocketNativeImpl()
}
