package com.nexustools.genericui.windows

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
 * Windows (`:windows`) implementation of [NativeFactory] backed by Win32 `HWND` controls (`comctl32.dll`),
 * Direct2D/DirectWrite (`Canvas2D`), Google ANGLE (`libEGL.dll` + `libGLESv2.dll` for `GLCanvas`),
 * Vulkan (`VK_KHR_win32_surface` for `VKCanvas`), VLC (`libvlc` with Vulkan `#version 450` SPIR-V primary
 * and OpenGL ES `#version 300 es` fallback for `VideoPlayer` / `AudioPlayer`), WebView2 (`ICoreWebView2`),
 * and WIC + `libjpeg-turbo` ([WindowsWicImageCodecNativeImpl]).
 */
class WindowsNativeFactory : NativeFactory {

    override val backendName: String = "Windows (Win32 / Direct2D / ANGLE / Vulkan / VLC)"

    val imageCodec: WindowsWicImageCodecNativeImpl = WindowsWicImageCodecNativeImpl()

    init {
        DesktopImageCodecHooks.install(backendName)
        ImageLoader.activeNativeCodec = imageCodec
    }

    override fun createApplication(): ApplicationNativeImpl<*> = WindowsApplicationNativeImpl()

    override fun createWindow(): WindowNativeImpl<*> = WindowsWindowNativeImpl()

    override fun createMenuBar(): MenuBarNativeImpl<*> =
        DesktopMenuBarNativeImpl(Win32HwndHandle("Win32HMenuBar"))

    override fun createMenu(): MenuNativeImpl<*> =
        DesktopMenuNativeImpl(Win32HwndHandle("Win32HMenu"))

    override fun createMenuItem(checkable: Boolean, radio: Boolean): MenuItemNativeImpl<*> =
        DesktopMenuItemNativeImpl(Win32HwndHandle("Win32MenuItem"), checkable, radio)

    override fun createContextMenu(): ContextMenuNativeImpl<*> =
        DesktopContextMenuNativeImpl(Win32HwndHandle("Win32PopupMenu"))

    override fun createSplitPane(): SplitPaneNativeImpl<*> =
        DesktopSplitPaneNativeImpl(Win32HwndHandle("Win32SplitPaneW"))

    override fun createDockWorkspace(): DockWorkspaceNativeImpl<*> =
        DesktopDockWorkspaceNativeImpl(Win32HwndHandle("Win32DockWorkspaceW"))

    override fun createDockArea(): DockAreaNativeImpl<*> =
        DesktopDockAreaNativeImpl(Win32HwndHandle("Win32DockAreaW"))

    override fun createDockGroup(): DockGroupNativeImpl<*> =
        DesktopDockGroupNativeImpl(Win32HwndHandle("Win32DockGroupW"))

    override fun createDockPanel(): DockPanelNativeImpl<*> =
        DesktopDockPanelNativeImpl(Win32HwndHandle("Win32DockPanelW"))

    override fun createDockWindow(): DockWindowNativeImpl<*> = WindowsDockWindowNativeImpl()

    override fun createToolPalette(): ToolPaletteNativeImpl<*> =
        DesktopToolPaletteNativeImpl(Win32HwndHandle("Win32ToolPaletteW"))

    override fun createContainer(classPrefix: String): ContainerNativeImpl<*> =
        DesktopContainerNativeImpl(Win32HwndHandle("GenericUIContainerW"), classPrefix)

    override fun createBookContainer(): BookContainerNativeImpl<*> =
        DesktopBookContainerNativeImpl(Win32HwndHandle("GenericUIBookContainerW"))

    override fun createScrollView(): ScrollViewNativeImpl<*> =
        DesktopScrollViewNativeImpl(Win32HwndHandle("GenericUIScrollViewW"))

    override fun createDivider(): DividerNativeImpl<*> =
        DesktopDividerNativeImpl(Win32HwndHandle("STATIC_SS_ETCHEDHORZ"))

    override fun createText(tagName: String, classPrefix: String): TextNativeImpl<*> =
        DesktopTextNativeImpl(Win32HwndHandle("STATIC"), tagName, classPrefix)

    override fun createMarkdownView(): MarkdownViewNativeImpl<*> =
        DesktopMarkdownViewNativeImpl(Win32HwndHandle("RichEdit50W"))

    override fun createImage(): ImageNativeImpl<*> =
        DesktopImageNativeImpl(Win32HwndHandle("STATIC_SS_BITMAP"))

    override fun createTable(): TableNativeImpl<*> =
        DesktopTableNativeImpl(Win32HwndHandle("SysListView32"))

    override fun createButton(classPrefix: String): ButtonNativeImpl<*> =
        DesktopButtonNativeImpl(Win32HwndHandle("BUTTON"), classPrefix)

    override fun createDropdown(): DropdownNativeImpl<*> =
        DesktopDropdownNativeImpl(Win32HwndHandle("BUTTON_BS_SPLITBUTTON"))

    override fun createTextInput(multiline: Boolean): TextInputNativeImpl<*> =
        DesktopTextInputNativeImpl(Win32HwndHandle("EDIT"), multiline)

    override fun createSelect(): SelectNativeImpl<*> =
        DesktopSelectNativeImpl(Win32HwndHandle("COMBOBOX"))

    override fun createCheckbox(switchStyle: Boolean, radioStyle: Boolean): CheckboxNativeImpl<*> =
        DesktopCheckboxNativeImpl(Win32HwndHandle("BUTTON_BS_AUTOCHECKBOX"), switchStyle, radioStyle)

    override fun createRangeSlider(): RangeSliderNativeImpl<*> =
        DesktopRangeSliderNativeImpl(Win32HwndHandle("msctls_trackbar32"))

    override fun createCollapse(): CollapseNativeImpl<*> =
        DesktopCollapseNativeImpl(Win32HwndHandle("Win32CollapsePaneW"))

    override fun createTab(closable: Boolean): TabNativeImpl<*> =
        DesktopTabNativeImpl(Win32HwndHandle(if (closable) "SysTabItem32_Closable" else "SysTabItem32"), closable)

    override fun createTabContainer(): TabContainerNativeImpl<*> = WindowsTabContainerNativeImpl()

    override fun createNavbar(): NavbarNativeImpl<*> =
        DesktopNavbarNativeImpl(Win32HwndHandle("ReBarWindow32"))

    override fun createBottomNavigationBar(): BottomNavigationBarNativeImpl<*> =
        DesktopBottomNavigationBarNativeImpl(Win32HwndHandle("ToolbarWindow32"))

    override fun createProgressBar(): ProgressBarNativeImpl<*> =
        DesktopProgressBarNativeImpl(Win32HwndHandle("msctls_progress32"))

    override fun createSpinner(): SpinnerNativeImpl<*> =
        DesktopSpinnerNativeImpl(Win32HwndHandle("Win32ActivityRingW"))

    override fun createPopover(): PopoverNativeImpl<*> =
        DesktopPopoverNativeImpl(Win32HwndHandle("tooltips_class32"))

    override fun createDialogWindow(): DialogWindowNativeImpl<*> =
        DesktopDialogWindowNativeImpl(
            Win32HwndHandle(
                win32ClassName = "#32770",
                dwStyle = Win32HwndHandle.WS_POPUP or Win32HwndHandle.WS_THICKFRAME
            )
        )

    override fun createFileBrowserDialog(): FileBrowserDialogNativeImpl =
        WindowsFileBrowserDialogNativeImpl()

    override fun createStandardDialogs(): StandardDialogsNativeImpl =
        WindowsStandardDialogsNativeImpl()

    override fun createLazyList(): LazyListNativeImpl<*> =
        DesktopLazyListNativeImpl(Win32HwndHandle("SysListView32_LVS_OWNERDATA"))

    override fun createLazyGrid(): LazyGridNativeImpl<*> =
        DesktopLazyGridNativeImpl(Win32HwndHandle("SysListView32_ICON_OWNERDATA"))

    override fun createTreeView(): TreeViewNativeImpl<*> =
        DesktopTreeViewNativeImpl(Win32HwndHandle("SysTreeView32"))

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
}
