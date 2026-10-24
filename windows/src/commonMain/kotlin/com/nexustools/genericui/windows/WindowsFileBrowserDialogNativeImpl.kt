package com.nexustools.genericui.windows

import com.nexustools.genericui.component.dialog.FileBrowserCallback
import com.nexustools.genericui.component.dialog.FileBrowserRequest
import com.nexustools.genericui.desktop.dialog.DesktopFileBrowserDialogNativeImpl
import com.nexustools.genericui.desktop.dialog.DesktopFileBrowserHelper
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl

/**
 * Windows Common Item Dialog (`IFileOpenDialog` & `IFileSaveDialog` from `Shobjidl.h`) implementation
 * of [com.nexustools.genericui.nativeimpl.dialog.FileBrowserDialogNativeImpl].
 * Configures `FOS_PICKFOLDERS` for folder selection, `FOS_ALLOWMULTISELECT` for multi-file selection,
 * and `COMDLG_FILTERSPEC` via [DesktopFileBrowserHelper.buildWin32FilterSpecs].
 */
class WindowsFileBrowserDialogNativeImpl : DesktopFileBrowserDialogNativeImpl(
    nativeDialogType = "Win32_IFileDialog_COM"
) {
    var lastWin32FilterSpecs: List<Pair<String, String>> = emptyList()
        private set

    var lastFosFlags: Int = 0
        private set

    override fun showFileBrowser(
        parentWindow: WindowNativeImpl<*>?,
        request: FileBrowserRequest,
        callback: FileBrowserCallback
    ) {
        lastWin32FilterSpecs = DesktopFileBrowserHelper.buildWin32FilterSpecs(request.filters)
        var flags = FOS_FORCEFILESYSTEM or FOS_PATHMUSTEXIST
        if (DesktopFileBrowserHelper.isFolderSelection(request)) {
            flags = flags or FOS_PICKFOLDERS
        }
        if (DesktopFileBrowserHelper.isMultipleSelection(request)) {
            flags = flags or FOS_ALLOWMULTISELECT
        }
        if (DesktopFileBrowserHelper.isSaveDialog(request)) {
            flags = flags or FOS_OVERWRITEPROMPT
        }
        lastFosFlags = flags
        super.showFileBrowser(parentWindow, request, callback)
    }

    companion object {
        const val FOS_OVERWRITEPROMPT: Int = 0x00000002
        const val FOS_PICKFOLDERS: Int = 0x00000020
        const val FOS_FORCEFILESYSTEM: Int = 0x00000040
        const val FOS_ALLOWMULTISELECT: Int = 0x00000200
        const val FOS_PATHMUSTEXIST: Int = 0x00000800
    }
}
