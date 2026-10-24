package com.nexustools.genericui.qt

import com.nexustools.genericui.component.dialog.FileBrowserCallback
import com.nexustools.genericui.component.dialog.FileBrowserRequest
import com.nexustools.genericui.desktop.dialog.DesktopFileBrowserDialogNativeImpl
import com.nexustools.genericui.desktop.dialog.DesktopFileBrowserHelper
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl

/**
 * Qt 6 `QFileDialog` (`getOpenFileName`, `getOpenFileNames`, `getExistingDirectory`, `getSaveFileName`)
 * implementation of [com.nexustools.genericui.nativeimpl.dialog.FileBrowserDialogNativeImpl].
 */
class QtFileBrowserDialogNativeImpl : DesktopFileBrowserDialogNativeImpl(
    nativeDialogType = "Qt6_QFileDialog"
) {
    var qtFileMode: String = "QFileDialog::ExistingFile"
        private set
    var qtFilterString: String = "All Files (*)"
        private set

    override fun showFileBrowser(
        parentWindow: WindowNativeImpl<*>?,
        request: FileBrowserRequest,
        callback: FileBrowserCallback
    ) {
        qtFileMode = when {
            DesktopFileBrowserHelper.isFolderSelection(request) -> "QFileDialog::Directory"
            DesktopFileBrowserHelper.isSaveDialog(request) -> "QFileDialog::AnyFile"
            DesktopFileBrowserHelper.isMultipleSelection(request) -> "QFileDialog::ExistingFiles"
            else -> "QFileDialog::ExistingFile"
        }
        qtFilterString = DesktopFileBrowserHelper.buildQtFilterString(request.filters)
        super.showFileBrowser(parentWindow, request, callback)
    }
}
