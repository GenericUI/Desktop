package com.nexustools.genericui.desktop.dialog

import com.nexustools.genericui.component.dialog.FileBrowserCallback
import com.nexustools.genericui.component.dialog.FileBrowserRequest
import com.nexustools.genericui.nativeimpl.dialog.FileBrowserDialogNativeImpl
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl

/**
 * Shared desktop base implementation of [FileBrowserDialogNativeImpl] powered by [DesktopFileBrowserHelper]
 * for Win32 `IFileOpenDialog`/`IFileSaveDialog` (`FOS_PICKFOLDERS`), macOS `NSOpenPanel`/`NSSavePanel`
 * (`canChooseDirectories`), GTK4 `GtkFileDialog`, and Qt6 `QFileDialog`.
 */
open class DesktopFileBrowserDialogNativeImpl(
    val nativeDialogType: String = "DesktopNativeFileDialog"
) : FileBrowserDialogNativeImpl {

    override var isDisposed: Boolean = false
        protected set

    var lastRequest: FileBrowserRequest? = null
        protected set

    var simulatedSelectedPaths: List<String>? = null

    override fun showFileBrowser(
        parentWindow: WindowNativeImpl<*>?,
        request: FileBrowserRequest,
        callback: FileBrowserCallback
    ) {
        lastRequest = request
        val paths = simulatedSelectedPaths
        if (paths != null) {
            DesktopFileBrowserHelper.dispatchSelectionResult(request = request, selectedPaths = paths, callback = callback)
        } else {
            callback.onCancelled()
        }
    }

    override fun dispose() {
        isDisposed = true
    }
}
