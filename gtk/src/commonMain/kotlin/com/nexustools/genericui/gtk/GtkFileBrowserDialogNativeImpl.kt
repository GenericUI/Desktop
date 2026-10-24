package com.nexustools.genericui.gtk

import com.nexustools.genericui.component.dialog.FileBrowserCallback
import com.nexustools.genericui.component.dialog.FileBrowserRequest
import com.nexustools.genericui.desktop.dialog.DesktopFileBrowserDialogNativeImpl
import com.nexustools.genericui.desktop.dialog.DesktopFileBrowserHelper
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl

/**
 * GTK 4.10+ `GtkFileDialog` (`gtk_file_dialog_open`, `gtk_file_dialog_open_multiple`,
 * `gtk_file_dialog_select_folder`, `gtk_file_dialog_save` + `GtkFileFilter`) implementation of
 * [com.nexustools.genericui.nativeimpl.dialog.FileBrowserDialogNativeImpl].
 */
class GtkFileBrowserDialogNativeImpl : DesktopFileBrowserDialogNativeImpl(
    nativeDialogType = "GTK4_GtkFileDialog"
) {
    var gtkEntryPoint: String = "gtk_file_dialog_open"
        private set
    var gtkFilterPatterns: List<Pair<String, List<String>>> = emptyList()
        private set

    override fun showFileBrowser(
        parentWindow: WindowNativeImpl<*>?,
        request: FileBrowserRequest,
        callback: FileBrowserCallback
    ) {
        gtkEntryPoint = when {
            DesktopFileBrowserHelper.isFolderSelection(request) -> "gtk_file_dialog_select_folder"
            DesktopFileBrowserHelper.isSaveDialog(request) -> "gtk_file_dialog_save"
            DesktopFileBrowserHelper.isMultipleSelection(request) -> "gtk_file_dialog_open_multiple"
            else -> "gtk_file_dialog_open"
        }
        gtkFilterPatterns = DesktopFileBrowserHelper.buildGtkFilterPatterns(request.filters)
        super.showFileBrowser(parentWindow, request, callback)
    }
}
