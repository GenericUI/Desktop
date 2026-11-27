package com.nexustools.genericui.macos

import com.nexustools.genericui.apple.AppleDatabaseNativeImpl
import com.nexustools.genericui.apple.AppleHttpClientNativeImpl
import com.nexustools.genericui.apple.AppleImageIoCodecNativeImpl
import com.nexustools.genericui.apple.AppleNetworkMonitorNativeImpl
import com.nexustools.genericui.apple.AppleNotificationManagerNativeImpl
import com.nexustools.genericui.apple.ApplePreferenceStoreNativeImpl
import com.nexustools.genericui.apple.AppleTcpSocketNativeImpl
import com.nexustools.genericui.apple.AppleUdpSocketNativeImpl
import com.nexustools.genericui.apple.AppleUiThreadDispatcherNativeImpl
import com.nexustools.genericui.apple.AppleUiTimerNativeImpl
import com.nexustools.genericui.apple.AppleUserDirectoryNativeImpl
import com.nexustools.genericui.apple.AppleWebSocketNativeImpl
import com.nexustools.genericui.component.layout.LayoutFactory
import com.nexustools.genericui.core.NativeFactory
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.desktop.core.DesktopCanvasWidgetHooks
import com.nexustools.genericui.desktop.image.DesktopImageCodecHooks
import com.nexustools.genericui.desktop.layout.DesktopLayoutFactory
import com.nexustools.genericui.desktop.shell.DesktopContextMenuNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopMenuBarNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopMenuItemNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopMenuNativeImpl
import com.nexustools.genericui.desktop.system.DesktopPermissionManagerNativeImpl
import com.nexustools.genericui.image.ImageLoader
import com.nexustools.genericui.macos.impl.MacosBookContainerNativeImpl
import com.nexustools.genericui.macos.impl.MacosBottomNavigationBarNativeImpl
import com.nexustools.genericui.macos.impl.MacosCheckboxNativeImpl
import com.nexustools.genericui.macos.impl.MacosDividerNativeImpl
import com.nexustools.genericui.macos.impl.MacosDockAreaNativeImpl
import com.nexustools.genericui.macos.impl.MacosDockGroupNativeImpl
import com.nexustools.genericui.macos.impl.MacosDockPanelNativeImpl
import com.nexustools.genericui.macos.impl.MacosDockWorkspaceNativeImpl
import com.nexustools.genericui.macos.impl.MacosDropdownNativeImpl
import com.nexustools.genericui.macos.impl.MacosImageNativeImpl
import com.nexustools.genericui.macos.impl.MacosLazyGridNativeImpl
import com.nexustools.genericui.macos.impl.MacosLazyListNativeImpl
import com.nexustools.genericui.macos.impl.MacosMarkdownViewNativeImpl
import com.nexustools.genericui.macos.impl.MacosNavbarNativeImpl
import com.nexustools.genericui.macos.impl.MacosPopoverNativeImpl
import com.nexustools.genericui.macos.impl.MacosProgressBarNativeImpl
import com.nexustools.genericui.macos.impl.MacosRangeSliderNativeImpl
import com.nexustools.genericui.macos.impl.MacosScrollViewNativeImpl
import com.nexustools.genericui.macos.impl.MacosSelectNativeImpl
import com.nexustools.genericui.macos.impl.MacosSpinnerNativeImpl
import com.nexustools.genericui.macos.impl.MacosStyledTextInputNativeImpl
import com.nexustools.genericui.macos.impl.MacosTabNativeImpl
import com.nexustools.genericui.macos.impl.MacosTableNativeImpl
import com.nexustools.genericui.macos.impl.MacosTextInputNativeImpl
import com.nexustools.genericui.macos.impl.MacosTextNativeImpl
import com.nexustools.genericui.macos.impl.MacosToolPaletteNativeImpl
import com.nexustools.genericui.macos.impl.MacosTreeViewNativeImpl
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
import com.nexustools.genericui.nativeimpl.surface.StyledComponentNativeImpl
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
import com.nexustools.genericui.threading.UiThreadDispatcherNativeImpl

/**
 * macOS (`:macos`) implementation of [NativeFactory] backed by genuine retained AppKit views and
 * controls (`NSWindow`, `NSPanel`, `NSView`, `NSControl`, `NSMenu` on `NSApplication.mainMenu`)
 * and `:apple` Foundation / CoreGraphics / CoreImage / Security / Network implementations
 * ([AppleImageIoCodecNativeImpl]).
 */
class MacosNativeFactory : NativeFactory {

    override val backendTarget: com.nexustools.genericui.enums.system.BackendTarget =
        com.nexustools.genericui.enums.system.BackendTarget.MacOS

    override val backendName: String = "macOS (AppKit / CoreGraphics / MoltenVK / VLC)"

    override val layoutFactory: LayoutFactory = DesktopLayoutFactory

    override val supportsSystemMenuBar: Boolean = true

    override val supportsWindowStatePersistence: Boolean = true

    override fun getPrimaryMonitorBounds(): Rect? = MacosNativeWindowHost.getPrimaryMonitorBounds()

    override fun isWindowRectOnScreen(rect: Rect): Boolean = MacosNativeWindowHost.isWindowRectOnScreen(rect)

    override fun supportsTcp(): Boolean = true

    override fun supportsUdp(): Boolean = true

    val imageCodec: AppleImageIoCodecNativeImpl = AppleImageIoCodecNativeImpl()

    init {
        DesktopImageCodecHooks.install(backendName)
        ImageLoader.activeNativeCodec = imageCodec
        DesktopCanvasWidgetHooks.createInteractiveSurfaceWidget = { role ->
            createMacosInteractiveSurfaceHandle(role)
        }
    }

    override fun createApplication(): ApplicationNativeImpl<*> {
        DesktopCanvasWidgetHooks.createInteractiveSurfaceWidget = { role ->
            createMacosInteractiveSurfaceHandle(role)
        }
        return MacosApplicationNativeImpl()
    }

    override fun createWindow(): WindowNativeImpl<*> = MacosWindowNativeImpl()

    override fun createMenuBar(): MenuBarNativeImpl<*> =
        DesktopMenuBarNativeImpl(MacosViewHandle("NSMenu_MainMenu"))

    override fun createMenu(): MenuNativeImpl<*> =
        DesktopMenuNativeImpl(MacosViewHandle("NSMenu"))

    override fun createMenuItem(checkable: Boolean, radio: Boolean): MenuItemNativeImpl<*> =
        DesktopMenuItemNativeImpl(MacosViewHandle("NSMenuItem"), checkable, radio)

    override fun createContextMenu(): ContextMenuNativeImpl<*> =
        DesktopContextMenuNativeImpl(MacosViewHandle("NSMenu_Contextual"))

    override fun createDockWorkspace(): DockWorkspaceNativeImpl<*> =
        MacosDockWorkspaceNativeImpl(MacosViewHandle("NSView_DockWorkspace"))

    override fun createDockArea(): DockAreaNativeImpl<*> =
        MacosDockAreaNativeImpl(MacosViewHandle("NSView_DockArea"))

    override fun createDockGroup(): DockGroupNativeImpl<*> =
        MacosDockGroupNativeImpl(MacosViewHandle("NSView_DockGroup"))

    override fun createDockPanel(): DockPanelNativeImpl<*> =
        MacosDockPanelNativeImpl(MacosViewHandle("NSView_DockPanel"))

    override fun createDockWindow(): DockWindowNativeImpl<*> = MacosDockWindowNativeImpl()

    override fun createToolPalette(): ToolPaletteNativeImpl<*> =
        MacosToolPaletteNativeImpl(MacosViewHandle("NSView_ToolPalette"))

    override fun createContainer(classPrefix: String): ContainerNativeImpl<*> =
        MacosContainerNativeImpl(MacosViewHandle("NSView_Container"), classPrefix, "div")

    override fun createContainer(classPrefix: String, tagName: String): ContainerNativeImpl<*> =
        MacosContainerNativeImpl(MacosViewHandle("NSView_Container"), classPrefix, tagName)

    override fun createStyledContainer(classPrefix: String, tagName: String): StyledContainerNativeImpl<*> =
        MacosStyledContainerNativeImpl(MacosViewHandle("NSView_StyledContainer"), classPrefix, tagName)

    override fun createStyledComponent(classPrefix: String, tagName: String): StyledComponentNativeImpl<*> =
        MacosStyledComponentNativeImpl(MacosViewHandle("NSView_StyledComponent"), classPrefix, tagName)

    override fun createBookContainer(): BookContainerNativeImpl<*> =
        MacosBookContainerNativeImpl(MacosViewHandle("NSView_BookPrintPreview"))

    override fun createScrollView(): ScrollViewNativeImpl<*> =
        MacosScrollViewNativeImpl(MacosViewHandle("NSScrollView"))

    override fun createScrollView(tagName: String): ScrollViewNativeImpl<*> =
        MacosScrollViewNativeImpl(MacosViewHandle("NSScrollView"))

    override fun createDivider(): DividerNativeImpl<*> =
        MacosDividerNativeImpl(MacosViewHandle("NSBox_Separator"))

    override fun createText(tagName: String, classPrefix: String): TextNativeImpl<*> =
        MacosTextNativeImpl(MacosViewHandle("NSTextField_Label"), tagName, classPrefix)

    override fun createMarkdownView(): MarkdownViewNativeImpl<*> =
        MacosMarkdownViewNativeImpl(MacosViewHandle("NSTextView_Attributed"))

    override fun createImage(): ImageNativeImpl<*> =
        MacosImageNativeImpl(MacosViewHandle("NSView_Image"))

    override fun createTable(): TableNativeImpl<*> =
        MacosTableNativeImpl(MacosViewHandle("NSTableView"))

    override fun createButton(classPrefix: String): ButtonNativeImpl<*> =
        MacosButtonNativeImpl(MacosViewHandle("NSButton"), classPrefix)

    override fun createStyledButton(classPrefix: String): StyledButtonNativeImpl<*> =
        MacosStyledButtonNativeImpl(MacosViewHandle("NSButton_Styled"), classPrefix)

    override fun createDropdown(): DropdownNativeImpl<*> =
        MacosDropdownNativeImpl(MacosViewHandle("NSPopUpButton_PullDown"))

    override fun createTextInput(multiline: Boolean): TextInputNativeImpl<*> =
        MacosTextInputNativeImpl(MacosViewHandle(if (multiline) "NSTextView" else "NSTextField"), multiline)

    override fun createStyledTextInput(multiline: Boolean, classPrefix: String): StyledTextInputNativeImpl<*> =
        MacosStyledTextInputNativeImpl(
            nativeWidget = MacosViewHandle(if (multiline) "MacosStyledTextView" else "MacosStyledTextField"),
            multiline = multiline,
            classPrefix = classPrefix
        )

    override fun createSelect(): SelectNativeImpl<*> =
        MacosSelectNativeImpl(MacosViewHandle("NSPopUpButton"))

    override fun createCheckbox(switchStyle: Boolean, radioStyle: Boolean): CheckboxNativeImpl<*> =
        MacosCheckboxNativeImpl(
            MacosViewHandle(if (switchStyle) "NSSwitch" else "NSButton_Switch"),
            switchStyle,
            radioStyle
        )

    override fun createRangeSlider(): RangeSliderNativeImpl<*> =
        MacosRangeSliderNativeImpl(MacosViewHandle("NSSlider"))

    override fun createTab(closable: Boolean): TabNativeImpl<*> =
        MacosTabNativeImpl(MacosViewHandle(if (closable) "NSTabViewItem_Closable" else "NSTabViewItem"), closable)

    override fun createTabContainer(): TabContainerNativeImpl<*> =
        MacosTabContainerNativeImpl(MacosViewHandle("NSTabView"))

    override fun createNavbar(): NavbarNativeImpl<*> =
        MacosNavbarNativeImpl(MacosViewHandle("NSView_Navbar"))

    override fun createBottomNavigationBar(): BottomNavigationBarNativeImpl<*> =
        MacosBottomNavigationBarNativeImpl(MacosViewHandle("NSView_BottomNav"))

    override fun createProgressBar(): ProgressBarNativeImpl<*> =
        MacosProgressBarNativeImpl(MacosViewHandle("NSProgressIndicator_Bar"))

    override fun createSpinner(): SpinnerNativeImpl<*> =
        MacosSpinnerNativeImpl(MacosViewHandle("NSProgressIndicator_Spinning"))

    override fun createPopover(): PopoverNativeImpl<*> =
        MacosPopoverNativeImpl(MacosViewHandle("NSPopover"))

    override fun createDialogWindow(): DialogWindowNativeImpl<*> {
        if (MacosApplicationNativeImpl.interactiveMacosGuiEnabled) {
            MacosNativeWindowHost.prepareEventLoop()
        }
        return MacosDialogWindowNativeImpl()
    }

    override fun createFileBrowserDialog(): FileBrowserDialogNativeImpl =
        MacosFileBrowserDialogNativeImpl()

    override fun createStandardDialogs(): StandardDialogsNativeImpl =
        MacosStandardDialogsNativeImpl()

    override fun createLazyList(): LazyListNativeImpl<*> =
        MacosLazyListNativeImpl(MacosViewHandle("NSScrollView_VirtualizedList"))

    override fun createLazyGrid(): LazyGridNativeImpl<*> =
        MacosLazyGridNativeImpl(MacosViewHandle("NSScrollView_VirtualizedGrid"))

    override fun createTreeView(): TreeViewNativeImpl<*> =
        MacosTreeViewNativeImpl(MacosViewHandle("NSOutlineView"))

    override fun createUiThreadDispatcher(): UiThreadDispatcherNativeImpl =
        AppleUiThreadDispatcherNativeImpl(
            isSplitDispatchActive = { MacosApplicationNativeImpl.interactiveMacosGuiEnabled },
            onUiDrainComplete = { MacosNativeWindowHost.onUiQueueDrained() }
        )

    override fun createUiTimerScheduler(): UiTimerNativeImpl =
        AppleUiTimerNativeImpl()

    override fun createAnimationScheduler(): com.nexustools.genericui.nativeimpl.system.AnimationNativeImpl =
        MacosAnimationNativeImpl()

    override fun createPermissionManager(): PermissionManagerNativeImpl =
        DesktopPermissionManagerNativeImpl("macOS TCC /AVCaptureDevice")

    override fun createSystemTray(): SystemTrayNativeImpl = MacosSystemTrayNativeImpl()

    override fun createNotificationManager(): NotificationManagerNativeImpl =
        AppleNotificationManagerNativeImpl()

    override fun createNetworkMonitor(): NetworkMonitorNativeImpl = AppleNetworkMonitorNativeImpl()

    override fun createPreferenceStore(appName: String): PreferenceStoreNativeImpl =
        ApplePreferenceStoreNativeImpl(
            appName = appName,
            userDirectory = AppleUserDirectoryNativeImpl(
                appName = appName,
                subdirectory = "",
                useCachesDirectory = false,
                useXdgStyleConfigDirectory = true
            )
        )

    override fun createConfigDirectory(appName: String, subdirectory: String): UserDirectoryNativeImpl =
        AppleUserDirectoryNativeImpl(
            appName = appName,
            subdirectory = subdirectory,
            useCachesDirectory = false,
            useXdgStyleConfigDirectory = true
        )

    override fun createCacheDirectory(appName: String, subdirectory: String): UserDirectoryNativeImpl =
        AppleUserDirectoryNativeImpl(
            appName = appName,
            subdirectory = subdirectory,
            useCachesDirectory = true,
            useXdgStyleConfigDirectory = false
        )

    override fun createFileSystem(): FileSystemNativeImpl =
        MacosFileSystemNativeImpl()

    override fun createDatabase(databaseName: String, filePath: String?, version: Int): DatabaseNativeImpl =
        AppleDatabaseNativeImpl(
            databaseName = databaseName,
            filePath = filePath ?: "$databaseName.db",
            version = version
        )

    override fun createResourceResolver(): com.nexustools.genericui.resources.ResourceResolverNativeImpl =
        MacosResourceResolverImpl()

    override fun createTcpSocket(): TcpSocketNativeImpl =
        AppleTcpSocketNativeImpl()

    override fun createUdpSocket(): UdpSocketNativeImpl =
        AppleUdpSocketNativeImpl()

    override fun createHttpClient(): HttpClientNativeImpl =
        AppleHttpClientNativeImpl()

    override fun createWebSocket(): WebSocketNativeImpl =
        AppleWebSocketNativeImpl()
}
