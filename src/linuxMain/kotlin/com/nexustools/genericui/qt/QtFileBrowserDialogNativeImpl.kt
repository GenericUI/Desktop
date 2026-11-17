@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.qt

import com.nexustools.genericui.component.dialog.FileBrowserCallback
import com.nexustools.genericui.component.dialog.FileBrowserRequest
import com.nexustools.genericui.desktop.dialog.DesktopFileBrowserDialogNativeImpl
import com.nexustools.genericui.desktop.dialog.DesktopFileBrowserHelper
import com.nexustools.genericui.linux.LinuxSelectedFileHelper
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl
import com.nexustools.genericui.threading.GenericUiThreading

/**
 * Qt 6 `QFileDialog` implementation of
 * [com.nexustools.genericui.nativeimpl.dialog.FileBrowserDialogNativeImpl].
 */
class QtFileBrowserDialogNativeImpl : DesktopFileBrowserDialogNativeImpl(
    nativeDialogType = "Qt6_QFileDialog"
) {
    var qtFileMode: String = "QFileDialog::ExistingFile"
        private set
    var qtFilterString: String = ""
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

        if (!QtApplicationNativeImpl.interactiveQtGuiEnabled ||
            simulatedSelectedPaths != null ||
            !Qt6DynamicBindings.ensureApplication()
        ) {
            super.showFileBrowser(parentWindow, request, callback)
            return
        }

        lastRequest = request
        val parentPtr = (parentWindow as? QtWindowNativeImpl)?.let {
            QtNativeWindowHost.sessionForWindow(it)?.qWidget
        } ?: QtNativeWindowHost.activeQWidgetPtr()

        val isFolder = DesktopFileBrowserHelper.isFolderSelection(request)
        val isSave = DesktopFileBrowserHelper.isSaveDialog(request)
        val isMultiple = DesktopFileBrowserHelper.isMultipleSelection(request)
        val fileMode = when {
            isFolder -> Qt6DynamicBindings.QFILE_MODE_DIRECTORY
            isSave -> Qt6DynamicBindings.QFILE_MODE_ANY_FILE
            isMultiple -> Qt6DynamicBindings.QFILE_MODE_EXISTING_FILES
            else -> Qt6DynamicBindings.QFILE_MODE_EXISTING_FILE
        }
        val acceptMode = if (isSave) Qt6DynamicBindings.QFILE_ACCEPT_SAVE else Qt6DynamicBindings.QFILE_ACCEPT_OPEN
        val title = request.title.ifBlank {
            when {
                isFolder -> "Select Folder"
                isSave -> "Save File"
                else -> "Select File"
            }
        }

        GenericUiThreading.runOnUi {
            val selectedPaths = Qt6DynamicBindings.execFileDialog(
                parent = parentPtr,
                title = title,
                initialDir = request.initialDirectory,
                filterString = qtFilterString.takeIf { !isFolder && it.isNotBlank() },
                fileMode = fileMode,
                acceptMode = acceptMode
            )
            val filters = DesktopFileBrowserHelper.effectiveFilters(request)
            val chosenFilter = filters.getOrNull(request.activeFilterIndex)
            if (selectedPaths.isEmpty()) {
                GenericUiThreading.runOnLogic {
                    callback.onCancelled()
                }
            } else {
                val files = selectedPaths.map { LinuxSelectedFileHelper.buildSelectedFile(it, request.showHiddenFiles) }
                GenericUiThreading.runOnLogic {
                    callback.onFilesSelected(files, chosenFilter)
                }
            }
        }
    }
}
