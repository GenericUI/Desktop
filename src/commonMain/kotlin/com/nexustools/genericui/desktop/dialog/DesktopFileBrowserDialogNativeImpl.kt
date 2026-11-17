package com.nexustools.genericui.desktop.dialog

import com.nexustools.genericui.component.dialog.FileBrowserCallback
import com.nexustools.genericui.component.dialog.FileBrowserRequest
import com.nexustools.genericui.nativeimpl.dialog.FileBrowserDialogNativeImpl
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl

/**
 * Shared desktop base implementation of [FileBrowserDialogNativeImpl].
 *
 * Stores [lastRequest] and dispatches explicit test selections when [simulatedSelectedPaths] is set;
 * un-overridden calls without simulated paths fail fast rather than silently faking cancellation.
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
        check(!isDisposed) { "$nativeDialogType has been disposed." }
        lastRequest = request
        val paths = simulatedSelectedPaths
            ?: throw UnsupportedOperationException(
                "$nativeDialogType requires a platform OS file browser dialog implementation when no simulatedSelectedPaths are configured."
            )
        DesktopFileBrowserHelper.dispatchSelectionResult(request, paths, callback = callback)
    }

    override fun dispose() {
        isDisposed = true
        lastRequest = null
        simulatedSelectedPaths = null
    }
}
