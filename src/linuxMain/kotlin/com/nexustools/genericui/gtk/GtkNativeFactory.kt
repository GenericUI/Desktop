@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk

import com.nexustools.genericui.component.layout.LayoutFactory
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.core.NativeFactory
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.linux.LinuxX11WindowHintsBridge
import com.nexustools.genericui.desktop.core.DesktopCanvasWidgetHooks
import com.nexustools.genericui.desktop.image.DesktopImageCodecHooks
import com.nexustools.genericui.desktop.layout.DesktopLayoutFactory
import com.nexustools.genericui.desktop.system.DesktopPermissionManagerNativeImpl
import com.nexustools.genericui.enums.system.BackendTarget
import com.nexustools.genericui.gtk.impl.GtkBookContainerNativeImpl
import com.nexustools.genericui.gtk.impl.GtkBottomNavigationBarNativeImpl
import com.nexustools.genericui.gtk.impl.GtkCheckboxNativeImpl
import com.nexustools.genericui.gtk.impl.GtkContextMenuNativeImpl
import com.nexustools.genericui.gtk.impl.GtkDividerNativeImpl
import com.nexustools.genericui.gtk.impl.GtkDockAreaNativeImpl
import com.nexustools.genericui.gtk.impl.GtkDockGroupNativeImpl
import com.nexustools.genericui.gtk.impl.GtkDockPanelNativeImpl
import com.nexustools.genericui.gtk.impl.GtkDockWorkspaceNativeImpl
import com.nexustools.genericui.gtk.impl.GtkDropdownNativeImpl
import com.nexustools.genericui.gtk.impl.GtkImageNativeImpl
import com.nexustools.genericui.gtk.impl.GtkLazyGridNativeImpl
import com.nexustools.genericui.gtk.impl.GtkLazyListNativeImpl
import com.nexustools.genericui.gtk.impl.GtkMarkdownViewNativeImpl
import com.nexustools.genericui.gtk.impl.GtkMenuBarNativeImpl
import com.nexustools.genericui.gtk.impl.GtkMenuItemNativeImpl
import com.nexustools.genericui.gtk.impl.GtkMenuNativeImpl
import com.nexustools.genericui.gtk.impl.GtkNavbarNativeImpl
import com.nexustools.genericui.gtk.impl.GtkPopoverNativeImpl
import com.nexustools.genericui.gtk.impl.GtkProgressBarNativeImpl
import com.nexustools.genericui.gtk.impl.GtkRangeSliderNativeImpl
import com.nexustools.genericui.gtk.impl.GtkScrollViewNativeImpl
import com.nexustools.genericui.gtk.impl.GtkSelectNativeImpl
import com.nexustools.genericui.gtk.impl.GtkSpinnerNativeImpl
import com.nexustools.genericui.gtk.impl.GtkTabNativeImpl
import com.nexustools.genericui.gtk.impl.GtkTableNativeImpl
import com.nexustools.genericui.gtk.impl.GtkTextInputNativeImpl
import com.nexustools.genericui.gtk.impl.GtkTextNativeImpl
import com.nexustools.genericui.gtk.impl.GtkToolPaletteNativeImpl
import com.nexustools.genericui.gtk.impl.GtkTreeViewNativeImpl
import com.nexustools.genericui.gtk.widget.GtkRetainedContainerSupport
import com.nexustools.genericui.image.ImageLoader
import com.nexustools.genericui.linux.titlebar.LinuxTitleBarProfileManager
import com.nexustools.genericui.window.TitleCapabilities
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

/**
 * GTK 4 + Libadwaita (`linuxMain`) implementation of [NativeFactory] backed by real shared libraries:
 * `libgtk-4.so.1`, `libadwaita-1.so.0`, `libcairo.so.2`, `libpangocairo-1.0.so.0`, `libglib-2.0.so.0`,
 * `libgio-2.0.so.0`, `libgdk_pixbuf-2.0.so.0`, `libwebp.so.7`, `libsqlite3.so.0`, `libcurl.so.4`,
 * and OpenSSL `libssl.so.3` / POSIX sockets.
 */
class GtkNativeFactory : NativeFactory {

    override val backendTarget: BackendTarget = BackendTarget.Gtk

    override val backendName: String = "GTK4 (Libadwaita / Cairo / PangoCairo / GIO / libcurl / sqlite3)"

    override val defaultTitleCapabilities: TitleCapabilities
        get() = LinuxTitleBarProfileManager.resolveTitleCapabilities()

    override val layoutFactory: LayoutFactory = DesktopLayoutFactory

    override fun supportsTcp(): Boolean = true

    override fun supportsUdp(): Boolean = true

    override fun supportsMulticastUdp(): Boolean = true

    override val supportsWindowStatePersistence: Boolean = true

    override fun getPrimaryMonitorBounds(): Rect? =
        Gtk4DynamicBindings.getPrimaryMonitorBounds() ?: LinuxX11WindowHintsBridge.getPrimaryScreenBounds()

    val imageCodec: LinuxGdkPixbufImageCodecNativeImpl = LinuxGdkPixbufImageCodecNativeImpl()

    private fun createCanvas2DWidgetAndApiName(): Pair<GtkWidgetHandle, String> {
        val handle = GtkWidgetHandle("GtkDrawingArea")
        handle.onDrawSurfaceCairo = { cr, w, h ->
            com.nexustools.genericui.gtk.widget.GtkInteractiveSurfaceWidget.paintInteractiveSurface(handle, cr, w, h)
        }
        handle.widgetMaterializer = {
            val da = com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge.createDrawingSurfaceForPainter(handle) { cr, w, h ->
                com.nexustools.genericui.gtk.widget.GtkInteractiveSurfaceWidget.paintInteractiveSurface(handle, cr, w, h)
            }
            val impl = (handle.ownerImpl ?: handle.attributes["nativeImpl"]) as? com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl<GtkWidgetHandle>
            if (da != null && impl != null) {
                com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge.attachControllersAndState(
                    impl = impl,
                    rootPtr = da,
                    focusTargetPtr = da,
                    isTextInput = false,
                    dispatchClickOnPrimaryRelease = true,
                    attachClickGesture = true
                )
            }
            da
        }
        return handle to "Cairo 2D (cairo_t) + Pango"
    }

    init {
        DesktopImageCodecHooks.install(backendName)
        ImageLoader.activeNativeCodec = imageCodec
        DesktopCanvasWidgetHooks.createCanvas2DWidgetAndApiName = {
            createCanvas2DWidgetAndApiName()
        }
        DesktopCanvasWidgetHooks.createInteractiveSurfaceWidget = { _ ->
            createCanvas2DWidgetAndApiName().first
        }
    }

    override fun createApplication(): ApplicationNativeImpl<*> {
        DesktopCanvasWidgetHooks.createCanvas2DWidgetAndApiName = {
            createCanvas2DWidgetAndApiName()
        }
        DesktopCanvasWidgetHooks.createInteractiveSurfaceWidget = { _ ->
            createCanvas2DWidgetAndApiName().first
        }
        return GtkApplicationNativeImpl()
    }

    override fun createWindow(): WindowNativeImpl<*> = GtkWindowNativeImpl()

    override fun createMenuBar(): MenuBarNativeImpl<*> =
        GtkMenuBarNativeImpl(GtkWidgetHandle("GtkPopoverMenuBar"))

    override fun createMenu(): MenuNativeImpl<*> =
        GtkMenuNativeImpl(GtkWidgetHandle("GMenu"))

    override fun createMenuItem(checkable: Boolean, radio: Boolean): MenuItemNativeImpl<*> =
        GtkMenuItemNativeImpl(GtkWidgetHandle("GMenuItem"), checkable, radio)

    override fun createContextMenu(): ContextMenuNativeImpl<*> =
        GtkContextMenuNativeImpl(GtkWidgetHandle("GtkPopoverMenu"))

    override fun createDockWorkspace(): DockWorkspaceNativeImpl<*> =
        GtkDockWorkspaceNativeImpl(GtkWidgetHandle("GtkPaned_DockWorkspace"))

    override fun createDockArea(): DockAreaNativeImpl<*> =
        GtkDockAreaNativeImpl(GtkWidgetHandle("GtkBox_DockArea"))

    override fun createDockGroup(): DockGroupNativeImpl<*> =
        GtkDockGroupNativeImpl(GtkWidgetHandle("GtkNotebook_DockGroup"))

    override fun createDockPanel(): DockPanelNativeImpl<*> =
        GtkDockPanelNativeImpl(GtkWidgetHandle("GtkBox_DockPanel"))

    override fun createDockWindow(): DockWindowNativeImpl<*> = GtkDockWindowNativeImpl()

    override fun createToolPalette(): ToolPaletteNativeImpl<*> =
        GtkToolPaletteNativeImpl(GtkWidgetHandle("GtkFlowBox_ToolPalette"))

    override fun createContainer(classPrefix: String): ContainerNativeImpl<*> =
        createContainer(classPrefix, "div")

    override fun createContainer(classPrefix: String, tagName: String): ContainerNativeImpl<*> {
        val isLayered = GtkRetainedContainerSupport.isLayeredHostTagOrClass(classPrefix, tagName, emptySet())
        return GtkContainerNativeImpl(
            nativeWidget = GtkWidgetHandle(if (isLayered) "GtkOverlay" else "GtkBox"),
            classPrefix = classPrefix,
            tagName = tagName
        )
    }

    override fun createStyledContainer(classPrefix: String, tagName: String): StyledContainerNativeImpl<*> =
        GtkStyledContainerNativeImpl(GtkWidgetHandle("GtkOverlay_StyledContainer"), classPrefix, tagName)

    override fun createStyledComponent(
        classPrefix: String,
        tagName: String
    ): com.nexustools.genericui.nativeimpl.surface.StyledComponentNativeImpl<*> =
        GtkStyledComponentNativeImpl(GtkWidgetHandle("GtkDrawingArea_StyledComponent"), classPrefix, tagName)

    override fun createBookContainer(): BookContainerNativeImpl<*> =
        GtkBookContainerNativeImpl(GtkWidgetHandle("GtkBox_BookPrintPreview"))

    override fun createScrollView(): ScrollViewNativeImpl<*> =
        GtkScrollViewNativeImpl(GtkWidgetHandle("GtkScrolledWindow"))

    override fun createDivider(): DividerNativeImpl<*> =
        GtkDividerNativeImpl(GtkWidgetHandle("GtkSeparator"))

    override fun createText(tagName: String, classPrefix: String): TextNativeImpl<*> =
        GtkTextNativeImpl(GtkWidgetHandle("GtkLabel"), tagName, classPrefix)

    override fun createMarkdownView(): MarkdownViewNativeImpl<*> =
        GtkMarkdownViewNativeImpl(GtkWidgetHandle("GtkTextView_Markdown"))

    override fun createImage(): ImageNativeImpl<*> =
        GtkImageNativeImpl(GtkWidgetHandle("GtkPicture"))

    override fun createTable(): TableNativeImpl<*> =
        GtkTableNativeImpl(GtkWidgetHandle("GtkColumnView"))

    override fun createButton(classPrefix: String): ButtonNativeImpl<*> =
        GtkButtonNativeImpl(GtkWidgetHandle("GtkButton"), classPrefix)

    override fun createStyledButton(classPrefix: String): StyledButtonNativeImpl<*> =
        GtkStyledButtonNativeImpl(GtkWidgetHandle("GtkDrawingArea_StyledButton"), classPrefix)

    override fun createDropdown(): DropdownNativeImpl<*> =
        GtkDropdownNativeImpl(GtkWidgetHandle("GtkMenuButton"))

    override fun createTextInput(multiline: Boolean): TextInputNativeImpl<*> =
        GtkTextInputNativeImpl(GtkWidgetHandle(if (multiline) "GtkScrolledWindow_GtkTextView" else "GtkEntry"), multiline)

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
        GtkTabContainerNativeImpl(GtkWidgetHandle("GtkStack_Tabs"))

    override fun createNavbar(): NavbarNativeImpl<*> =
        GtkNavbarNativeImpl(GtkWidgetHandle("AdwHeaderBar"))

    override fun createBottomNavigationBar(): BottomNavigationBarNativeImpl<*> =
        GtkBottomNavigationBarNativeImpl(GtkWidgetHandle("AdwViewSwitcherBar"))

    override fun createProgressBar(): ProgressBarNativeImpl<*> =
        GtkProgressBarNativeImpl(GtkWidgetHandle("GtkProgressBar"))

    override fun createSpinner(): SpinnerNativeImpl<*> =
        GtkSpinnerNativeImpl(GtkWidgetHandle("AdwSpinner"))

    override fun createPopover(): PopoverNativeImpl<*> =
        GtkPopoverNativeImpl(GtkWidgetHandle("GtkPopover"))

    override fun createDialogWindow(): DialogWindowNativeImpl<*> {
        if (GtkApplicationNativeImpl.interactiveGtkGuiEnabled) {
            GtkNativeWindowHost.prepareEventLoop()
        }
        return GtkDialogWindowNativeImpl()
    }

    override fun createFileBrowserDialog(): FileBrowserDialogNativeImpl =
        GtkFileBrowserDialogNativeImpl()

    override fun createStandardDialogs(): StandardDialogsNativeImpl =
        GtkStandardDialogsNativeImpl()

    override fun createLazyList(): LazyListNativeImpl<*> =
        GtkLazyListNativeImpl(GtkWidgetHandle("GtkListView"))

    override fun createLazyGrid(): LazyGridNativeImpl<*> =
        GtkLazyGridNativeImpl(GtkWidgetHandle("GtkGridView"))

    override fun createTreeView(): TreeViewNativeImpl<*> =
        GtkTreeViewNativeImpl(GtkWidgetHandle("GtkListView_GtkTreeListModel"))

    override fun createUiThreadDispatcher(): UiThreadDispatcherNativeImpl =
        GtkUiThreadDispatcherNativeImpl(
            isSplitDispatchActive = { GtkApplicationNativeImpl.interactiveGtkGuiEnabled },
            onUiDrainComplete = { GtkNativeWindowHost.requestRedraw() }
        )

    override fun createUiTimerScheduler(): UiTimerNativeImpl =
        GtkUiTimerNativeImpl()

    override fun createAnimationScheduler(): AnimationNativeImpl =
        GtkAnimationNativeImpl()

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
