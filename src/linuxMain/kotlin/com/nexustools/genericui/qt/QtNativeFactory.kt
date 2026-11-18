package com.nexustools.genericui.qt

import com.nexustools.genericui.component.layout.LayoutFactory
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.core.NativeFactory
import com.nexustools.genericui.desktop.core.DesktopCanvasWidgetHooks
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.linux.LinuxX11WindowHintsBridge
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
import com.nexustools.genericui.qt.impl.QtButtonNativeImpl
import com.nexustools.genericui.qt.impl.QtCheckboxNativeImpl
import com.nexustools.genericui.qt.impl.QtContainerNativeImpl
import com.nexustools.genericui.qt.impl.QtDropdownNativeImpl
import com.nexustools.genericui.qt.impl.QtLazyGridNativeImpl
import com.nexustools.genericui.qt.impl.QtMarkdownViewNativeImpl
import com.nexustools.genericui.qt.impl.QtProgressBarNativeImpl
import com.nexustools.genericui.qt.impl.QtRangeSliderNativeImpl
import com.nexustools.genericui.qt.impl.QtSelectNativeImpl
import com.nexustools.genericui.qt.impl.QtSpinnerNativeImpl
import com.nexustools.genericui.qt.impl.QtTabContainerNativeImpl
import com.nexustools.genericui.qt.impl.QtTabNativeImpl
import com.nexustools.genericui.qt.impl.QtTableNativeImpl
import com.nexustools.genericui.qt.impl.QtTextInputNativeImpl
import com.nexustools.genericui.qt.impl.QtTextNativeImpl
import com.nexustools.genericui.resources.ResourceResolverNativeImpl
import com.nexustools.genericui.threading.UiThreadDispatcherNativeImpl

/**
 * Qt 6 (`linuxMain`) implementation of [NativeFactory] pairing Qt 6 (`QApplication`, `QWidget`,
 * `QImage`/`QPainter`/`QPixmap`/`QLabel`, `QDialog`, `QSystemTrayIcon`) with the shared Linux
 * OS layer (`com.nexustools.genericui.linux.*`).
 */
class QtNativeFactory : NativeFactory {

    override val backendTarget: BackendTarget = BackendTarget.Qt

    override val backendName: String = "Qt6 (QtWidgets / QPainter / GIO / libcurl / sqlite3)"

    override val layoutFactory: LayoutFactory = DesktopLayoutFactory

    override fun supportsTcp(): Boolean = true

    override fun supportsUdp(): Boolean = true

    override fun supportsMulticastUdp(): Boolean = true

    override val supportsWindowStatePersistence: Boolean = true

    override fun getPrimaryMonitorBounds(): Rect? =
        Qt6DynamicBindings.getPrimaryScreenBounds() ?: LinuxX11WindowHintsBridge.getPrimaryScreenBounds()

    val imageCodec: LinuxGdkPixbufImageCodecNativeImpl = LinuxGdkPixbufImageCodecNativeImpl()

    init {
        DesktopImageCodecHooks.install(backendName)
        ImageLoader.activeNativeCodec = imageCodec
        DesktopCanvasWidgetHooks.createCanvas2DWidgetAndApiName = {
            QtWidgetHandle("QWidget_QPainterCanvas") to "Qt6 QPainter 2D"
        }
    }

    override fun createApplication(): ApplicationNativeImpl<*> {
        DesktopCanvasWidgetHooks.createCanvas2DWidgetAndApiName = {
            QtWidgetHandle("QWidget_QPainterCanvas") to "Qt6 QPainter 2D"
        }
        return QtApplicationNativeImpl()
    }

    override fun createWindow(): WindowNativeImpl<*> = QtWindowNativeImpl()

    override fun createMenuBar(): MenuBarNativeImpl<*> =
        DesktopMenuBarNativeImpl(QtWidgetHandle("QMenuBar"))

    override fun createMenu(): MenuNativeImpl<*> =
        DesktopMenuNativeImpl(QtWidgetHandle("QMenu"))

    override fun createMenuItem(checkable: Boolean, radio: Boolean): MenuItemNativeImpl<*> =
        DesktopMenuItemNativeImpl(QtWidgetHandle("QAction"), checkable, radio)

    override fun createContextMenu(): ContextMenuNativeImpl<*> =
        DesktopContextMenuNativeImpl(QtWidgetHandle("QMenu_Context"))

    override fun createDockWorkspace(): DockWorkspaceNativeImpl<*> =
        DesktopDockWorkspaceNativeImpl(QtWidgetHandle("QMainWindow_DockWorkspace"))

    override fun createDockArea(): DockAreaNativeImpl<*> =
        DesktopDockAreaNativeImpl(QtWidgetHandle("QSplitter_DockArea"))

    override fun createDockGroup(): DockGroupNativeImpl<*> =
        DesktopDockGroupNativeImpl(QtWidgetHandle("QTabWidget_DockGroup"))

    override fun createDockPanel(): DockPanelNativeImpl<*> =
        DesktopDockPanelNativeImpl(QtWidgetHandle("QDockWidget"))

    override fun createDockWindow(): DockWindowNativeImpl<*> = QtDockWindowNativeImpl()

    override fun createToolPalette(): ToolPaletteNativeImpl<*> =
        DesktopToolPaletteNativeImpl(QtWidgetHandle("QToolBar"))

    override fun createContainer(classPrefix: String): ContainerNativeImpl<*> =
        QtContainerNativeImpl(QtWidgetHandle("QWidget"), classPrefix)

    override fun createStyledContainer(classPrefix: String, tagName: String): StyledContainerNativeImpl<*> =
        QtStyledContainerNativeImpl(QtWidgetHandle("QWidget_Styled"), classPrefix, tagName)

    override fun createBookContainer(): BookContainerNativeImpl<*> =
        DesktopBookContainerNativeImpl(QtWidgetHandle("QStackedWidget_Book"))

    override fun createScrollView(): ScrollViewNativeImpl<*> =
        DesktopScrollViewNativeImpl(QtWidgetHandle("QScrollArea"))

    override fun createDivider(): DividerNativeImpl<*> =
        DesktopDividerNativeImpl(QtWidgetHandle("QFrame_HLine"))

    override fun createText(tagName: String, classPrefix: String): TextNativeImpl<*> =
        QtTextNativeImpl(QtWidgetHandle("QLabel"), tagName, classPrefix)

    override fun createMarkdownView(): MarkdownViewNativeImpl<*> =
        QtMarkdownViewNativeImpl(QtWidgetHandle("QTextBrowser_Markdown"))

    override fun createImage(): ImageNativeImpl<*> =
        DesktopImageNativeImpl(QtWidgetHandle("QLabel_Pixmap"))

    override fun createTable(): TableNativeImpl<*> =
        QtTableNativeImpl(QtWidgetHandle("QTableView"))

    override fun createButton(classPrefix: String): ButtonNativeImpl<*> =
        QtButtonNativeImpl(QtWidgetHandle("QPushButton"), classPrefix)

    override fun createStyledButton(classPrefix: String): StyledButtonNativeImpl<*> =
        QtStyledButtonNativeImpl(QtWidgetHandle("QPushButton_Styled"), classPrefix)

    override fun createDropdown(): DropdownNativeImpl<*> =
        QtDropdownNativeImpl(QtWidgetHandle("QToolButton_Menu"))

    override fun createTextInput(multiline: Boolean): TextInputNativeImpl<*> =
        QtTextInputNativeImpl(QtWidgetHandle(if (multiline) "QTextEdit" else "QLineEdit"), multiline)

    override fun createSelect(): SelectNativeImpl<*> =
        QtSelectNativeImpl(QtWidgetHandle("QComboBox"))

    override fun createCheckbox(switchStyle: Boolean, radioStyle: Boolean): CheckboxNativeImpl<*> =
        QtCheckboxNativeImpl(
            QtWidgetHandle(
                when {
                    switchStyle -> "QCheckBox_Switch"
                    radioStyle -> "QRadioButton"
                    else -> "QCheckBox"
                }
            ),
            switchStyle,
            radioStyle
        )

    override fun createRangeSlider(): RangeSliderNativeImpl<*> =
        QtRangeSliderNativeImpl(QtWidgetHandle("QSlider"))

    override fun createTab(closable: Boolean): TabNativeImpl<*> =
        QtTabNativeImpl(QtWidgetHandle(if (closable) "QTabWidget_PageClosable" else "QTabWidget_Page"), closable)

    override fun createTabContainer(): TabContainerNativeImpl<*> =
        QtTabContainerNativeImpl(QtWidgetHandle("QTabWidget"))

    override fun createNavbar(): NavbarNativeImpl<*> =
        DesktopNavbarNativeImpl(QtWidgetHandle("QToolBar_Navbar"))

    override fun createBottomNavigationBar(): BottomNavigationBarNativeImpl<*> =
        DesktopBottomNavigationBarNativeImpl(QtWidgetHandle("QTabBar_Bottom"))

    override fun createProgressBar(): ProgressBarNativeImpl<*> =
        QtProgressBarNativeImpl(QtWidgetHandle("QProgressBar"))

    override fun createSpinner(): SpinnerNativeImpl<*> =
        QtSpinnerNativeImpl(QtWidgetHandle("QProgressIndicator"))

    override fun createPopover(): PopoverNativeImpl<*> =
        DesktopPopoverNativeImpl(QtWidgetHandle("QMenu_Popover"))

    override fun createDialogWindow(): DialogWindowNativeImpl<*> =
        QtDialogWindowNativeImpl()

    override fun createFileBrowserDialog(): FileBrowserDialogNativeImpl =
        QtFileBrowserDialogNativeImpl()

    override fun createStandardDialogs(): StandardDialogsNativeImpl =
        QtStandardDialogsNativeImpl()

    override fun createLazyList(): LazyListNativeImpl<*> =
        DesktopLazyListNativeImpl(QtWidgetHandle("QListView"))

    override fun createLazyGrid(): LazyGridNativeImpl<*> =
        QtLazyGridNativeImpl(QtWidgetHandle("QListView_IconMode"))

    override fun createTreeView(): TreeViewNativeImpl<*> =
        DesktopTreeViewNativeImpl(QtWidgetHandle("QTreeView"))

    override fun createUiThreadDispatcher(): UiThreadDispatcherNativeImpl =
        QtUiThreadDispatcherNativeImpl(
            isSplitDispatchActive = { QtApplicationNativeImpl.interactiveQtGuiEnabled },
            onUiDrainComplete = { QtNativeWindowHost.requestRedraw() }
        )

    override fun createUiTimerScheduler(): UiTimerNativeImpl =
        QtUiTimerNativeImpl()

    override fun createPermissionManager(): PermissionManagerNativeImpl =
        DesktopPermissionManagerNativeImpl("XDG Desktop Portal PermissionStore")

    override fun createSystemTray(): SystemTrayNativeImpl = QtSystemTrayNativeImpl()

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
        LinuxPosixFileSystemNativeImpl { QtFileBrowserDialogNativeImpl() }

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
