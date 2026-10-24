package com.nexustools.genericui.macos

import com.nexustools.genericui.component.dialog.FileBrowserCallback
import com.nexustools.genericui.component.dialog.FileBrowserRequest
import com.nexustools.genericui.desktop.dialog.DesktopFileBrowserDialogNativeImpl
import com.nexustools.genericui.desktop.dialog.DesktopFileBrowserHelper
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl

/**
 * macOS AppKit `NSOpenPanel` & `NSSavePanel` implementation of
 * [com.nexustools.genericui.nativeimpl.dialog.FileBrowserDialogNativeImpl].
 * Configures `canChooseDirectories`, `canChooseFiles`, `allowsMultipleSelection`, and
 * `allowedContentTypes` via [DesktopFileBrowserHelper.extractMacosAllowedExtensions].
 */
class MacosFileBrowserDialogNativeImpl : DesktopFileBrowserDialogNativeImpl(
    nativeDialogType = "macOS_NSOpenPanel_NSSavePanel"
) {
    var canChooseDirectories: Boolean = false
        private set
    var canChooseFiles: Boolean = true
        private set
    var allowsMultipleSelection: Boolean = false
        private set
    var allowedExtensions: List<String> = emptyList()
        private set

    override fun showFileBrowser(
        parentWindow: WindowNativeImpl<*>?,
        request: FileBrowserRequest,
        callback: FileBrowserCallback
    ) {
        val isFolder = DesktopFileBrowserHelper.isFolderSelection(request)
        canChooseDirectories = isFolder
        canChooseFiles = !isFolder
        allowsMultipleSelection = DesktopFileBrowserHelper.isMultipleSelection(request)
        allowedExtensions = DesktopFileBrowserHelper.extractMacosAllowedExtensions(request.filters)
        super.showFileBrowser(parentWindow, request, callback)
    }
}
