package com.nexustools.genericui.qt

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
 * Qt 6 (`:qt`) implementation of [NativeFactory] backed by native `QWidget` / `QMainWindow` / `QDockWidget`
 * controls, `QPainter` (`Canvas2D`), `QOpenGLWidget` (`GLCanvas` using OpenGL ES 3.0 / Google ANGLE on Windows),
 * `QVulkanWindow` (`VKCanvas`), VLC (`libvlc` with Vulkan `#version 450` SPIR-V primary and OpenGL ES
 * `#version 300 es` fallback for `VideoPlayer` / `AudioPlayer`), `QWebEngineView`, and `QImage` +
 * `libjpeg-turbo` ([QtQImageCodecNativeImpl]).
 */
class QtNativeFactory : NativeFactory {

    override val backendName: String = "Qt6 (QWidgets / QPainter / QOpenGLWidget / QVulkanWindow / VLC)"

    val imageCodec: QtQImageCodecNativeImpl = QtQImageCodecNativeImpl()

    init {
        DesktopImageCodecHooks.install(backendName)
        ImageLoader.activeNativeCodec = imageCodec
    }

    override fun createApplication(): ApplicationNativeImpl<*> = QtApplicationNativeImpl()

    override fun createWindow(): WindowNativeImpl<*> = QtWindowNativeImpl()

    override fun createMenuBar(): MenuBarNativeImpl<*> =
        DesktopMenuBarNativeImpl(QtWidgetHandle("QMenuBar"))

    override fun createMenu(): MenuNativeImpl<*> =
        DesktopMenuNativeImpl(QtWidgetHandle("QMenu"))

    override fun createMenuItem(checkable: Boolean, radio: Boolean): MenuItemNativeImpl<*> =
        DesktopMenuItemNativeImpl(QtWidgetHandle("QAction"), checkable, radio)

    override fun createContextMenu(): ContextMenuNativeImpl<*> =
        DesktopContextMenuNativeImpl(QtWidgetHandle("QMenu_Context"))

    override fun createSplitPane(): SplitPaneNativeImpl<*> =
        DesktopSplitPaneNativeImpl(QtWidgetHandle("QSplitter"))

    override fun createDockWorkspace(): DockWorkspaceNativeImpl<*> =
        DesktopDockWorkspaceNativeImpl(QtWidgetHandle("QMainWindow_DockWorkspace"))

    override fun createDockArea(): DockAreaNativeImpl<*> =
        DesktopDockAreaNativeImpl(QtWidgetHandle("QWidget_DockArea"))

    override fun createDockGroup(): DockGroupNativeImpl<*> =
        DesktopDockGroupNativeImpl(QtWidgetHandle("QTabWidget_DockGroup"))

    override fun createDockPanel(): DockPanelNativeImpl<*> =
        DesktopDockPanelNativeImpl(QtWidgetHandle("QDockWidget_Panel"))

    override fun createDockWindow(): DockWindowNativeImpl<*> = QtDockWindowNativeImpl()

    override fun createToolPalette(): ToolPaletteNativeImpl<*> =
        DesktopToolPaletteNativeImpl(QtWidgetHandle("QToolBar_ToolPalette"))

    override fun createContainer(classPrefix: String): ContainerNativeImpl<*> =
        DesktopContainerNativeImpl(QtWidgetHandle("QWidget_QBoxLayout"), classPrefix)

    override fun createBookContainer(): BookContainerNativeImpl<*> =
        DesktopBookContainerNativeImpl(QtWidgetHandle("QPrintPreviewWidget"))

    override fun createScrollView(): ScrollViewNativeImpl<*> =
        DesktopScrollViewNativeImpl(QtWidgetHandle("QScrollArea"))

    override fun createDivider(): DividerNativeImpl<*> =
        DesktopDividerNativeImpl(QtWidgetHandle("QFrame_HLine"))

    override fun createText(tagName: String, classPrefix: String): TextNativeImpl<*> =
        DesktopTextNativeImpl(QtWidgetHandle("QLabel"), tagName, classPrefix)

    override fun createMarkdownView(): MarkdownViewNativeImpl<*> =
        DesktopMarkdownViewNativeImpl(QtWidgetHandle("QTextBrowser_Markdown"))

    override fun createImage(): ImageNativeImpl<*> =
        DesktopImageNativeImpl(QtWidgetHandle("QLabel_QPixmap"))

    override fun createTable(): TableNativeImpl<*> =
        DesktopTableNativeImpl(QtWidgetHandle("QTableWidget"))

    override fun createButton(classPrefix: String): ButtonNativeImpl<*> =
        DesktopButtonNativeImpl(QtWidgetHandle("QPushButton"), classPrefix)

    override fun createDropdown(): DropdownNativeImpl<*> =
        DesktopDropdownNativeImpl(QtWidgetHandle("QToolButton_MenuButtonPopup"))

    override fun createTextInput(multiline: Boolean): TextInputNativeImpl<*> =
        DesktopTextInputNativeImpl(QtWidgetHandle(if (multiline) "QPlainTextEdit" else "QLineEdit"), multiline)

    override fun createSelect(): SelectNativeImpl<*> =
        DesktopSelectNativeImpl(QtWidgetHandle("QComboBox"))

    override fun createCheckbox(switchStyle: Boolean, radioStyle: Boolean): CheckboxNativeImpl<*> =
        DesktopCheckboxNativeImpl(
            QtWidgetHandle(if (radioStyle) "QRadioButton" else "QCheckBox"),
            switchStyle,
            radioStyle
        )

    override fun createRangeSlider(): RangeSliderNativeImpl<*> =
        DesktopRangeSliderNativeImpl(QtWidgetHandle("QSlider"))

    override fun createCollapse(): CollapseNativeImpl<*> =
        DesktopCollapseNativeImpl(QtWidgetHandle("QGroupBox_Checkable"))

    override fun createTab(closable: Boolean): TabNativeImpl<*> =
        DesktopTabNativeImpl(QtWidgetHandle(if (closable) "QTabBarItem_Closable" else "QTabBarItem"), closable)

    override fun createTabContainer(): TabContainerNativeImpl<*> = QtTabContainerNativeImpl()

    override fun createNavbar(): NavbarNativeImpl<*> =
        DesktopNavbarNativeImpl(QtWidgetHandle("QToolBar_Navbar"))

    override fun createBottomNavigationBar(): BottomNavigationBarNativeImpl<*> =
        DesktopBottomNavigationBarNativeImpl(QtWidgetHandle("QTabBar_BottomNav"))

    override fun createProgressBar(): ProgressBarNativeImpl<*> =
        DesktopProgressBarNativeImpl(QtWidgetHandle("QProgressBar"))

    override fun createSpinner(): SpinnerNativeImpl<*> =
        DesktopSpinnerNativeImpl(QtWidgetHandle("QWidget_QMovieSpinner"))

    override fun createPopover(): PopoverNativeImpl<*> =
        DesktopPopoverNativeImpl(QtWidgetHandle("QFrame_QtPopup"))

    override fun createDialogWindow(): DialogWindowNativeImpl<*> =
        DesktopDialogWindowNativeImpl(QtWidgetHandle("QDialog", QtWidgetHandle.Qt_Dialog))

    override fun createFileBrowserDialog(): FileBrowserDialogNativeImpl =
        QtFileBrowserDialogNativeImpl()

    override fun createStandardDialogs(): StandardDialogsNativeImpl =
        QtStandardDialogsNativeImpl()

    override fun createLazyList(): LazyListNativeImpl<*> =
        DesktopLazyListNativeImpl(QtWidgetHandle("QListView_Virtualized"))

    override fun createLazyGrid(): LazyGridNativeImpl<*> =
        DesktopLazyGridNativeImpl(QtWidgetHandle("QListView_IconMode_Virtualized"))

    override fun createTreeView(): TreeViewNativeImpl<*> =
        DesktopTreeViewNativeImpl(QtWidgetHandle("QTreeView"))

    override fun createPermissionManager(): PermissionManagerNativeImpl =
        DesktopPermissionManagerNativeImpl("Qt6 QPermissions")

    override fun createSystemTray(): SystemTrayNativeImpl = QtSystemTrayNativeImpl()

    override fun createNotificationManager(): NotificationManagerNativeImpl =
        QtNotificationManagerNativeImpl()

    override fun createNetworkMonitor(): NetworkMonitorNativeImpl = QtNetworkMonitorNativeImpl()

    override fun createPreferenceStore(appName: String): PreferenceStoreNativeImpl =
        QtPreferenceStoreImpl(appName)
}
