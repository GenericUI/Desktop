package com.nexustools.genericui.gtk

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
 * GTK 4 + Libadwaita (`:gtk`) implementation of [NativeFactory] backed by native `GtkWidget` controls,
 * `GtkHeaderBar` custom title bars, Cairo (`Canvas2D`), `GtkGLArea` (`GLCanvas` using EGL/GLES3 or Google ANGLE),
 * `GdkVulkanContext` (`VKCanvas`), VLC (`libvlc` with Vulkan `#version 450` SPIR-V primary and OpenGL ES
 * `#version 300 es` fallback for `VideoPlayer` / `AudioPlayer`), `WebKitWebView`, and `GdkPixbuf` +
 * `libjpeg-turbo` ([GtkPixbufImageCodecNativeImpl]).
 */
class GtkNativeFactory : NativeFactory {

    override val backendName: String = "GTK4 (Libadwaita / Cairo / GtkGLArea / Vulkan / VLC)"

    val imageCodec: GtkPixbufImageCodecNativeImpl = GtkPixbufImageCodecNativeImpl()

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

    override fun createSplitPane(): SplitPaneNativeImpl<*> =
        DesktopSplitPaneNativeImpl(GtkWidgetHandle("GtkPaned"))

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
        DesktopContainerNativeImpl(GtkWidgetHandle("GtkBox"), classPrefix)

    override fun createBookContainer(): BookContainerNativeImpl<*> =
        DesktopBookContainerNativeImpl(GtkWidgetHandle("GtkBox_BookPrintPreview"))

    override fun createScrollView(): ScrollViewNativeImpl<*> =
        DesktopScrollViewNativeImpl(GtkWidgetHandle("GtkScrolledWindow"))

    override fun createDivider(): DividerNativeImpl<*> =
        DesktopDividerNativeImpl(GtkWidgetHandle("GtkSeparator"))

    override fun createText(tagName: String, classPrefix: String): TextNativeImpl<*> =
        DesktopTextNativeImpl(GtkWidgetHandle("GtkLabel"), tagName, classPrefix)

    override fun createMarkdownView(): MarkdownViewNativeImpl<*> =
        DesktopMarkdownViewNativeImpl(GtkWidgetHandle("GtkTextView_Markdown"))

    override fun createImage(): ImageNativeImpl<*> =
        DesktopImageNativeImpl(GtkWidgetHandle("GtkPicture"))

    override fun createTable(): TableNativeImpl<*> =
        DesktopTableNativeImpl(GtkWidgetHandle("GtkColumnView"))

    override fun createButton(classPrefix: String): ButtonNativeImpl<*> =
        DesktopButtonNativeImpl(GtkWidgetHandle("GtkButton"), classPrefix)

    override fun createDropdown(): DropdownNativeImpl<*> =
        DesktopDropdownNativeImpl(GtkWidgetHandle("GtkMenuButton"))

    override fun createTextInput(multiline: Boolean): TextInputNativeImpl<*> =
        DesktopTextInputNativeImpl(GtkWidgetHandle(if (multiline) "GtkTextView" else "GtkEntry"), multiline)

    override fun createSelect(): SelectNativeImpl<*> =
        DesktopSelectNativeImpl(GtkWidgetHandle("GtkDropDown"))

    override fun createCheckbox(switchStyle: Boolean, radioStyle: Boolean): CheckboxNativeImpl<*> =
        DesktopCheckboxNativeImpl(
            GtkWidgetHandle(if (switchStyle) "GtkSwitch" else "GtkCheckButton"),
            switchStyle,
            radioStyle
        )

    override fun createRangeSlider(): RangeSliderNativeImpl<*> =
        DesktopRangeSliderNativeImpl(GtkWidgetHandle("GtkScale"))

    override fun createCollapse(): CollapseNativeImpl<*> =
        DesktopCollapseNativeImpl(GtkWidgetHandle("GtkExpander"))

    override fun createTab(closable: Boolean): TabNativeImpl<*> =
        DesktopTabNativeImpl(GtkWidgetHandle(if (closable) "AdwTabPage_Closable" else "AdwTabPage"), closable)

    override fun createTabContainer(): TabContainerNativeImpl<*> = GtkTabContainerNativeImpl()

    override fun createNavbar(): NavbarNativeImpl<*> =
        DesktopNavbarNativeImpl(GtkWidgetHandle("AdwHeaderBar"))

    override fun createBottomNavigationBar(): BottomNavigationBarNativeImpl<*> =
        DesktopBottomNavigationBarNativeImpl(GtkWidgetHandle("AdwViewSwitcherBar"))

    override fun createProgressBar(): ProgressBarNativeImpl<*> =
        DesktopProgressBarNativeImpl(GtkWidgetHandle("GtkProgressBar"))

    override fun createSpinner(): SpinnerNativeImpl<*> =
        DesktopSpinnerNativeImpl(GtkWidgetHandle("AdwSpinner"))

    override fun createPopover(): PopoverNativeImpl<*> =
        DesktopPopoverNativeImpl(GtkWidgetHandle("GtkPopover"))

    override fun createDialogWindow(): DialogWindowNativeImpl<*> =
        DesktopDialogWindowNativeImpl(GtkWidgetHandle("AdwDialog"))

    override fun createFileBrowserDialog(): FileBrowserDialogNativeImpl =
        GtkFileBrowserDialogNativeImpl()

    override fun createStandardDialogs(): StandardDialogsNativeImpl =
        GtkStandardDialogsNativeImpl()

    override fun createLazyList(): LazyListNativeImpl<*> =
        DesktopLazyListNativeImpl(GtkWidgetHandle("GtkListView"))

    override fun createLazyGrid(): LazyGridNativeImpl<*> =
        DesktopLazyGridNativeImpl(GtkWidgetHandle("GtkGridView"))

    override fun createTreeView(): TreeViewNativeImpl<*> =
        DesktopTreeViewNativeImpl(GtkWidgetHandle("GtkListView_GtkTreeListModel"))

    override fun createPermissionManager(): PermissionManagerNativeImpl =
        DesktopPermissionManagerNativeImpl("XDG Desktop Portal PermissionStore")

    override fun createSystemTray(): SystemTrayNativeImpl = GtkSystemTrayNativeImpl()

    override fun createNotificationManager(): NotificationManagerNativeImpl =
        GtkNotificationManagerNativeImpl()

    override fun createNetworkMonitor(): NetworkMonitorNativeImpl = GtkNetworkMonitorNativeImpl()

    override fun createPreferenceStore(appName: String): PreferenceStoreNativeImpl =
        GtkPreferenceStoreImpl(appName)

    override fun createConfigDirectory(appName: String, subdirectory: String): UserDirectoryNativeImpl =
        GtkConfigDirectoryImpl(appName, subdirectory)

    override fun createCacheDirectory(appName: String, subdirectory: String): UserDirectoryNativeImpl =
        GtkCacheDirectoryImpl(appName, subdirectory)
}
