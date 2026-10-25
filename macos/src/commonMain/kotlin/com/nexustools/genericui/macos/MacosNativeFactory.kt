package com.nexustools.genericui.macos

import com.nexustools.genericui.core.NativeFactory
import com.nexustools.genericui.desktop.dialog.DesktopDialogWindowNativeImpl
import com.nexustools.genericui.desktop.dock.DesktopDockAreaNativeImpl
import com.nexustools.genericui.desktop.dock.DesktopDockGroupNativeImpl
import com.nexustools.genericui.desktop.dock.DesktopDockPanelNativeImpl
import com.nexustools.genericui.desktop.dock.DesktopDockWorkspaceNativeImpl
import com.nexustools.genericui.desktop.dock.DesktopToolPaletteNativeImpl
import com.nexustools.genericui.desktop.image.DesktopImageCodecHooks
import com.nexustools.genericui.desktop.lazy.DesktopLazyGridNativeImpl
import com.nexustools.genericui.desktop.lazy.DesktopLazyListNativeImpl
import com.nexustools.genericui.desktop.lazy.DesktopTreeViewNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopContextMenuNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopMenuBarNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopMenuItemNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopMenuNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopSplitPaneNativeImpl
import com.nexustools.genericui.desktop.system.DesktopPermissionManagerNativeImpl
import com.nexustools.genericui.desktop.widget.*
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
 * macOS (`:macos`) implementation of [NativeFactory] backed by AppKit (`NSWindow`, `NSPanel`, `NSView`,
 * `NSMenu` on `NSApplication.mainMenu`), CoreGraphics (`Canvas2D`), Metal-backed OpenGL ES (`GLCanvas`),
 * MoltenVK (`VK_EXT_metal_surface` for `VKCanvas`), VLC (`libvlc.dylib` with Vulkan `#version 450` SPIR-V
 * primary and OpenGL ES `#version 300 es` fallback for `VideoPlayer` / `AudioPlayer`), `WKWebView`,
 * and Apple `ImageIO` + `libjpeg-turbo` ([MacosImageIoCodecNativeImpl]).
 */
class MacosNativeFactory : NativeFactory {

    override val backendName: String = "macOS (AppKit / CoreGraphics / MoltenVK / VLC)"

    val imageCodec: MacosImageIoCodecNativeImpl = MacosImageIoCodecNativeImpl()

    init {
        DesktopImageCodecHooks.install(backendName)
        ImageLoader.activeNativeCodec = imageCodec
    }

    override fun createApplication(): ApplicationNativeImpl<*> = MacosApplicationNativeImpl()

    override fun createWindow(): WindowNativeImpl<*> = MacosWindowNativeImpl()

    override fun createMenuBar(): MenuBarNativeImpl<*> =
        DesktopMenuBarNativeImpl(MacosViewHandle("NSMenu_MainMenu"))

    override fun createMenu(): MenuNativeImpl<*> =
        DesktopMenuNativeImpl(MacosViewHandle("NSMenu"))

    override fun createMenuItem(checkable: Boolean, radio: Boolean): MenuItemNativeImpl<*> =
        DesktopMenuItemNativeImpl(MacosViewHandle("NSMenuItem"), checkable, radio)

    override fun createContextMenu(): ContextMenuNativeImpl<*> =
        DesktopContextMenuNativeImpl(MacosViewHandle("NSMenu_Contextual"))

    override fun createSplitPane(): SplitPaneNativeImpl<*> =
        DesktopSplitPaneNativeImpl(MacosViewHandle("NSSplitView"))

    override fun createDockWorkspace(): DockWorkspaceNativeImpl<*> =
        DesktopDockWorkspaceNativeImpl(MacosViewHandle("NSView_DockWorkspace"))

    override fun createDockArea(): DockAreaNativeImpl<*> =
        DesktopDockAreaNativeImpl(MacosViewHandle("NSView_DockArea"))

    override fun createDockGroup(): DockGroupNativeImpl<*> =
        DesktopDockGroupNativeImpl(MacosViewHandle("NSView_DockGroup"))

    override fun createDockPanel(): DockPanelNativeImpl<*> =
        DesktopDockPanelNativeImpl(MacosViewHandle("NSView_DockPanel"))

    override fun createDockWindow(): DockWindowNativeImpl<*> = MacosDockWindowNativeImpl()

    override fun createToolPalette(): ToolPaletteNativeImpl<*> =
        DesktopToolPaletteNativeImpl(MacosViewHandle("NSView_ToolPalette"))

    override fun createContainer(classPrefix: String): ContainerNativeImpl<*> =
        DesktopContainerNativeImpl(MacosViewHandle("NSStackView"), classPrefix)

    override fun createBookContainer(): BookContainerNativeImpl<*> =
        DesktopBookContainerNativeImpl(MacosViewHandle("NSView_BookPrintPreview"))

    override fun createScrollView(): ScrollViewNativeImpl<*> =
        DesktopScrollViewNativeImpl(MacosViewHandle("NSScrollView"))

    override fun createDivider(): DividerNativeImpl<*> =
        DesktopDividerNativeImpl(MacosViewHandle("NSBox_Separator"))

    override fun createText(tagName: String, classPrefix: String): TextNativeImpl<*> =
        DesktopTextNativeImpl(MacosViewHandle("NSTextField_Label"), tagName, classPrefix)

    override fun createMarkdownView(): MarkdownViewNativeImpl<*> =
        DesktopMarkdownViewNativeImpl(MacosViewHandle("NSTextView_Attributed"))

    override fun createImage(): ImageNativeImpl<*> =
        DesktopImageNativeImpl(MacosViewHandle("NSImageView"))

    override fun createTable(): TableNativeImpl<*> =
        DesktopTableNativeImpl(MacosViewHandle("NSTableView"))

    override fun createButton(classPrefix: String): ButtonNativeImpl<*> =
        DesktopButtonNativeImpl(MacosViewHandle("NSButton"), classPrefix)

    override fun createDropdown(): DropdownNativeImpl<*> =
        DesktopDropdownNativeImpl(MacosViewHandle("NSPopUpButton_PullDown"))

    override fun createTextInput(multiline: Boolean): TextInputNativeImpl<*> =
        DesktopTextInputNativeImpl(MacosViewHandle(if (multiline) "NSTextView" else "NSTextField"), multiline)

    override fun createSelect(): SelectNativeImpl<*> =
        DesktopSelectNativeImpl(MacosViewHandle("NSPopUpButton"))

    override fun createCheckbox(switchStyle: Boolean, radioStyle: Boolean): CheckboxNativeImpl<*> =
        DesktopCheckboxNativeImpl(
            MacosViewHandle(if (switchStyle) "NSSwitch" else "NSButton_Switch"),
            switchStyle,
            radioStyle
        )

    override fun createRangeSlider(): RangeSliderNativeImpl<*> =
        DesktopRangeSliderNativeImpl(MacosViewHandle("NSSlider"))

    override fun createCollapse(): CollapseNativeImpl<*> =
        DesktopCollapseNativeImpl(MacosViewHandle("NSDisclosureView"))

    override fun createTab(closable: Boolean): TabNativeImpl<*> =
        DesktopTabNativeImpl(MacosViewHandle(if (closable) "NSTabViewItem_Closable" else "NSTabViewItem"), closable)

    override fun createTabContainer(): TabContainerNativeImpl<*> = MacosTabContainerNativeImpl()

    override fun createNavbar(): NavbarNativeImpl<*> =
        DesktopNavbarNativeImpl(MacosViewHandle("NSToolbar"))

    override fun createBottomNavigationBar(): BottomNavigationBarNativeImpl<*> =
        DesktopBottomNavigationBarNativeImpl(MacosViewHandle("NSSegmentedControl"))

    override fun createProgressBar(): ProgressBarNativeImpl<*> =
        DesktopProgressBarNativeImpl(MacosViewHandle("NSProgressIndicator_Bar"))

    override fun createSpinner(): SpinnerNativeImpl<*> =
        DesktopSpinnerNativeImpl(MacosViewHandle("NSProgressIndicator_Spinning"))

    override fun createPopover(): PopoverNativeImpl<*> =
        DesktopPopoverNativeImpl(MacosViewHandle("NSPopover"))

    override fun createDialogWindow(): DialogWindowNativeImpl<*> =
        DesktopDialogWindowNativeImpl(MacosViewHandle("NSPanel_SheetOrModal"))

    override fun createFileBrowserDialog(): FileBrowserDialogNativeImpl =
        MacosFileBrowserDialogNativeImpl()

    override fun createStandardDialogs(): StandardDialogsNativeImpl =
        MacosStandardDialogsNativeImpl()

    override fun createLazyList(): LazyListNativeImpl<*> =
        DesktopLazyListNativeImpl(MacosViewHandle("NSTableView_Virtualized"))

    override fun createLazyGrid(): LazyGridNativeImpl<*> =
        DesktopLazyGridNativeImpl(MacosViewHandle("NSCollectionView"))

    override fun createTreeView(): TreeViewNativeImpl<*> =
        DesktopTreeViewNativeImpl(MacosViewHandle("NSOutlineView"))

    override fun createPermissionManager(): PermissionManagerNativeImpl =
        DesktopPermissionManagerNativeImpl("macOS TCC /AVCaptureDevice")

    override fun createSystemTray(): SystemTrayNativeImpl = MacosSystemTrayNativeImpl()

    override fun createNotificationManager(): NotificationManagerNativeImpl =
        MacosNotificationManagerNativeImpl()

    override fun createNetworkMonitor(): NetworkMonitorNativeImpl = MacosNetworkMonitorNativeImpl()

    override fun createPreferenceStore(appName: String): PreferenceStoreNativeImpl =
        MacosPreferenceStoreImpl(appName)

    override fun createConfigDirectory(appName: String, subdirectory: String): UserDirectoryNativeImpl =
        MacosConfigDirectoryImpl(appName, subdirectory)

    override fun createCacheDirectory(appName: String, subdirectory: String): UserDirectoryNativeImpl =
        MacosCacheDirectoryImpl(appName, subdirectory)
}
