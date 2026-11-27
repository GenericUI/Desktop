@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.component.layout.LayoutFactory
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.core.NativeFactory
import com.nexustools.genericui.desktop.core.DesktopCanvasWidgetHooks
import com.nexustools.genericui.desktop.image.DesktopImageCodecHooks
import com.nexustools.genericui.desktop.layout.DesktopLayoutFactory
import com.nexustools.genericui.desktop.system.DesktopPermissionManagerNativeImpl
import com.nexustools.genericui.enums.system.BackendTarget
import com.nexustools.genericui.geometry.Rect
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
import com.nexustools.genericui.linux.LinuxX11WindowHintsBridge
import com.nexustools.genericui.linux.LinuxXdgCacheDirectoryImpl
import com.nexustools.genericui.linux.LinuxXdgConfigDirectoryImpl
import com.nexustools.genericui.linux.LinuxXdgPreferenceStoreImpl
import com.nexustools.genericui.linux.titlebar.LinuxTitleBarProfileManager
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
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl
import com.nexustools.genericui.nativeimpl.surface.StyledComponentNativeImpl
import com.nexustools.genericui.nativeimpl.system.AnimationNativeImpl
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
import com.nexustools.genericui.window.TitleCapabilities

/**
 * KDE Frameworks 6 + Breeze + Qt 6 retained `QWidget` (`linuxMain`) implementation of
 * [NativeFactory] backed by genuine Qt 6 / KF6 widgets, `QPainter`, `KStatusNotifierItem`,
 * `KWindowEffects`, GIO, libcurl, SQLite 3, and POSIX sockets.
 */
class KdeNativeFactory : NativeFactory {

    override val backendTarget: BackendTarget = BackendTarget.Kde

    override val backendName: String =
        "KDE KF6 / Breeze (Qt6 QWidget / QPainter / KStatusNotifierItem / GIO / libcurl / sqlite3)"

    override val defaultTitleCapabilities: TitleCapabilities
        get() = LinuxTitleBarProfileManager.resolveTitleCapabilities()

    override val layoutFactory: LayoutFactory = DesktopLayoutFactory

    override fun supportsTcp(): Boolean = true

    override fun supportsUdp(): Boolean = true

    override fun supportsMulticastUdp(): Boolean = true

    override val supportsWindowStatePersistence: Boolean = true

    override fun getPrimaryMonitorBounds(): Rect? = LinuxX11WindowHintsBridge.getPrimaryScreenBounds()

    val imageCodec: LinuxGdkPixbufImageCodecNativeImpl = LinuxGdkPixbufImageCodecNativeImpl()

    private fun createCanvas2DWidgetAndApiName(): Pair<KdeWidgetHandle, String> =
        KdeInteractiveSurfaceWidget.createCanvas2DWidgetHandle() to "Qt6 QPainter (ARGB32_Premultiplied)"

    init {
        DesktopImageCodecHooks.install(backendName)
        ImageLoader.activeNativeCodec = imageCodec
        DesktopCanvasWidgetHooks.createCanvas2DWidgetAndApiName = {
            createCanvas2DWidgetAndApiName()
        }
        DesktopCanvasWidgetHooks.createInteractiveSurfaceWidget = { _ ->
            KdeInteractiveSurfaceWidget.createCanvas2DWidgetHandle()
        }
    }

    override fun createApplication(): ApplicationNativeImpl<*> {
        DesktopCanvasWidgetHooks.createCanvas2DWidgetAndApiName = {
            createCanvas2DWidgetAndApiName()
        }
        DesktopCanvasWidgetHooks.createInteractiveSurfaceWidget = { _ ->
            KdeInteractiveSurfaceWidget.createCanvas2DWidgetHandle()
        }
        return KdeApplicationNativeImpl()
    }

    override fun createWindow(): WindowNativeImpl<*> = KdeWindowNativeImpl()

    override fun createMenuBar(): MenuBarNativeImpl<*> = KdeMenuBarNativeImpl()

    override fun createMenu(): MenuNativeImpl<*> = KdeMenuNativeImpl()

    override fun createMenuItem(checkable: Boolean, radio: Boolean): MenuItemNativeImpl<*> =
        KdeMenuItemNativeImpl(checkable = checkable, radio = radio)

    override fun createContextMenu(): ContextMenuNativeImpl<*> = KdeContextMenuNativeImpl()

    override fun createDockWorkspace(): DockWorkspaceNativeImpl<*> = KdeDockWorkspaceNativeImpl()

    override fun createDockArea(): DockAreaNativeImpl<*> = KdeDockAreaNativeImpl()

    override fun createDockGroup(): DockGroupNativeImpl<*> = KdeDockGroupNativeImpl()

    override fun createDockPanel(): DockPanelNativeImpl<*> = KdeDockPanelNativeImpl()

    override fun createDockWindow(): DockWindowNativeImpl<*> = KdeDockWindowNativeImpl()

    override fun createToolPalette(): ToolPaletteNativeImpl<*> = KdeToolPaletteNativeImpl()

    override fun createContainer(classPrefix: String): ContainerNativeImpl<*> =
        createContainer(classPrefix, "div")

    override fun createContainer(classPrefix: String, tagName: String): ContainerNativeImpl<*> =
        KdeContainerNativeImpl(
            nativeWidget = KdeWidgetHandle("QWidget_Container"),
            classPrefix = classPrefix,
            tagName = tagName
        )

    override fun createStyledContainer(classPrefix: String, tagName: String): StyledContainerNativeImpl<*> =
        KdeStyledContainerNativeImpl(
            nativeWidget = KdeWidgetHandle("KdeStyledContainerWidget"),
            classPrefix = classPrefix,
            tagName = tagName
        )

    override fun createStyledComponent(classPrefix: String, tagName: String): StyledComponentNativeImpl<*> =
        KdeStyledComponentNativeImpl(
            nativeWidget = KdeWidgetHandle("KdeStyledComponentWidget"),
            classPrefix = classPrefix,
            tagName = tagName
        )

    override fun createBookContainer(): BookContainerNativeImpl<*> = KdeBookContainerNativeImpl()

    override fun createScrollView(): ScrollViewNativeImpl<*> = KdeScrollViewNativeImpl()

    override fun createDivider(): DividerNativeImpl<*> = KdeDividerNativeImpl()

    override fun createText(tagName: String, classPrefix: String): TextNativeImpl<*> =
        KdeTextNativeImpl(tagName = tagName, classPrefix = classPrefix)

    override fun createMarkdownView(): MarkdownViewNativeImpl<*> = KdeMarkdownViewNativeImpl()

    override fun createImage(): ImageNativeImpl<*> = KdeImageNativeImpl()

    override fun createTable(): TableNativeImpl<*> = KdeTableNativeImpl()

    override fun createButton(classPrefix: String): ButtonNativeImpl<*> =
        KdeButtonNativeImpl(classPrefix = classPrefix)

    override fun createStyledButton(classPrefix: String): StyledButtonNativeImpl<*> =
        KdeStyledButtonNativeImpl(classPrefix = classPrefix)

    override fun createDropdown(): DropdownNativeImpl<*> = KdeDropdownNativeImpl()

    override fun createTextInput(multiline: Boolean): TextInputNativeImpl<*> =
        KdeTextInputNativeImpl(
            nativeWidget = KdeWidgetHandle(if (multiline) "QPlainTextEdit" else "QLineEdit"),
            multiline = multiline
        )

    override fun createStyledTextInput(multiline: Boolean, classPrefix: String): StyledTextInputNativeImpl<*> =
        KdeStyledTextInputNativeImpl(
            nativeWidget = KdeWidgetHandle(if (multiline) "KdeStyledTextArea" else "KdeStyledTextField"),
            multiline = multiline,
            classPrefix = classPrefix
        )

    override fun createSelect(): SelectNativeImpl<*> = KdeSelectNativeImpl()

    override fun createCheckbox(switchStyle: Boolean, radioStyle: Boolean): CheckboxNativeImpl<*> =
        KdeCheckboxNativeImpl(switchStyle = switchStyle, radioStyle = radioStyle)

    override fun createRangeSlider(): RangeSliderNativeImpl<*> = KdeRangeSliderNativeImpl()

    override fun createTab(closable: Boolean): TabNativeImpl<*> = KdeTabNativeImpl(closable = closable)

    override fun createTabContainer(): TabContainerNativeImpl<*> = KdeTabContainerNativeImpl()

    override fun createNavbar(): NavbarNativeImpl<*> = KdeNavbarNativeImpl()

    override fun createBottomNavigationBar(): BottomNavigationBarNativeImpl<*> =
        KdeBottomNavigationBarNativeImpl()

    override fun createProgressBar(): ProgressBarNativeImpl<*> = KdeProgressBarNativeImpl()

    override fun createSpinner(): SpinnerNativeImpl<*> = KdeSpinnerNativeImpl()

    override fun createPopover(): PopoverNativeImpl<*> = KdePopoverNativeImpl()

    override fun createDialogWindow(): DialogWindowNativeImpl<*> {
        if (KdeApplicationNativeImpl.interactiveKdeGuiEnabled) {
            KdeNativeWindowHost.prepareEventLoop()
        }
        return KdeDialogWindowNativeImpl()
    }

    override fun createFileBrowserDialog(): FileBrowserDialogNativeImpl = KdeFileBrowserDialogNativeImpl()

    override fun createStandardDialogs(): StandardDialogsNativeImpl = KdeStandardDialogsNativeImpl()

    override fun createLazyList(): LazyListNativeImpl<*> = KdeLazyListNativeImpl()

    override fun createLazyGrid(): LazyGridNativeImpl<*> = KdeLazyGridNativeImpl()

    override fun createTreeView(): TreeViewNativeImpl<*> = KdeTreeViewNativeImpl()

    override fun createUiThreadDispatcher(): UiThreadDispatcherNativeImpl =
        KdeUiThreadDispatcherNativeImpl(
            isSplitDispatchActive = { KdeApplicationNativeImpl.interactiveKdeGuiEnabled },
            onUiDrainComplete = { KdeNativeWindowHost.requestRedraw() }
        )

    override fun createUiTimerScheduler(): UiTimerNativeImpl = KdeUiTimerNativeImpl()

    override fun createAnimationScheduler(): AnimationNativeImpl = KdeAnimationNativeImpl()

    override fun createPermissionManager(): PermissionManagerNativeImpl =
        DesktopPermissionManagerNativeImpl("XDG Desktop Portal PermissionStore")

    override fun createSystemTray(): SystemTrayNativeImpl = KdeSystemTrayNativeImpl()

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
        LinuxPosixFileSystemNativeImpl { KdeFileBrowserDialogNativeImpl() }

    override fun createDatabase(
        databaseName: String,
        filePath: String?,
        version: Int
    ): DatabaseNativeImpl = LinuxSqliteDatabaseNativeImpl(
        databaseName = databaseName,
        filePath = filePath ?: "$databaseName.db",
        version = version
    )

    override fun createResourceResolver(): ResourceResolverNativeImpl = LinuxResourceResolverImpl()

    override fun createTcpSocket(): TcpSocketNativeImpl = LinuxPosixTcpSocketNativeImpl()

    override fun createUdpSocket(): UdpSocketNativeImpl = LinuxPosixUdpSocketNativeImpl()

    override fun createHttpClient(): HttpClientNativeImpl = LinuxCurlHttpClientNativeImpl()

    override fun createWebSocket(): WebSocketNativeImpl = LinuxRfc6455WebSocketNativeImpl()
}
