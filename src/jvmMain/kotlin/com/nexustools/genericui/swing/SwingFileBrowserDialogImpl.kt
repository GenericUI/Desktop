package com.nexustools.genericui.swing

import com.nexustools.genericui.component.dialog.FileBrowserCallback
import com.nexustools.genericui.component.dialog.FileBrowserRequest
import com.nexustools.genericui.component.dialog.FileTypeFilter
import com.nexustools.genericui.component.dialog.SelectedFile
import com.nexustools.genericui.enums.dialog.FileBrowserMode
import com.nexustools.genericui.nativeimpl.dialog.FileBrowserDialogNativeImpl
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Java Swing `javax.swing.JFileChooser` implementation of [FileBrowserDialogNativeImpl].
 *
 * Supports `FILES_ONLY` and `DIRECTORIES_ONLY` folder mode ([FileBrowserMode.SELECT_FOLDER]),
 * multi-selection ([FileBrowserMode.OPEN_MULTIPLE_FILES]), [FileNameExtensionFilter] populated
 * from [FileTypeFilter], initial directory/file name, and hidden files visibility.
 */
class SwingFileBrowserDialogImpl : FileBrowserDialogNativeImpl {

    val fileChooser: JFileChooser = JFileChooser()
    private var lastCallback: FileBrowserCallback? = null

    override fun showFileBrowser(
        parentWindow: WindowNativeImpl<*>?,
        request: FileBrowserRequest,
        callback: FileBrowserCallback
    ) {
        lastCallback = callback
        fileChooser.dialogTitle = request.title
        fileChooser.isMultiSelectionEnabled = request.mode == FileBrowserMode.OPEN_MULTIPLE_FILES
        fileChooser.fileSelectionMode = if (request.mode == FileBrowserMode.SELECT_FOLDER) {
            JFileChooser.DIRECTORIES_ONLY
        } else {
            JFileChooser.FILES_ONLY
        }
        fileChooser.dialogType = if (request.mode == FileBrowserMode.SAVE_FILE) {
            JFileChooser.SAVE_DIALOG
        } else {
            JFileChooser.OPEN_DIALOG
        }
        fileChooser.isFileHidingEnabled = !request.showHiddenFiles

        if (!request.initialDirectory.isNullOrBlank()) {
            fileChooser.currentDirectory = File(request.initialDirectory)
        }
        val initialName = request.defaultFileName ?: request.suggestedFileName
        if (!initialName.isNullOrBlank()) {
            fileChooser.selectedFile = File(fileChooser.currentDirectory, initialName)
        }

        fileChooser.resetChoosableFileFilters()
        val swingFilters = mutableListOf<Pair<FileNameExtensionFilter, FileTypeFilter>>()
        for (filter in request.filters) {
            val cleanExts = filter.extensions
                .map { it.trim().removePrefix("*.").removePrefix(".") }
                .filter { it.isNotEmpty() && it != "*" }
            if (cleanExts.isNotEmpty()) {
                val swingFilter = FileNameExtensionFilter(filter.description, *cleanExts.toTypedArray())
                fileChooser.addChoosableFileFilter(swingFilter)
                swingFilters.add(swingFilter to filter)
            }
        }
        if (request.selectedFilterIndex in swingFilters.indices) {
            fileChooser.fileFilter = swingFilters[request.selectedFilterIndex].first
        }

        val parentFrame = (parentWindow as? SwingWindowImpl)?.jFrame
        if (parentFrame != null && parentFrame.isShowing) {
            val result = if (request.mode == FileBrowserMode.SAVE_FILE) {
                fileChooser.showSaveDialog(parentFrame)
            } else {
                fileChooser.showOpenDialog(parentFrame)
            }
            if (result == JFileChooser.APPROVE_OPTION) {
                val files = if (fileChooser.isMultiSelectionEnabled && fileChooser.selectedFiles.isNotEmpty()) {
                    fileChooser.selectedFiles.toList()
                } else {
                    listOfNotNull(fileChooser.selectedFile)
                }
                if (files.isNotEmpty()) {
                    val activeFilter = swingFilters.firstOrNull { it.first == fileChooser.fileFilter }?.second
                    callback.onFilesSelected(files.map { toSelectedFile(it) }, activeFilter)
                } else {
                    callback.onCancelled()
                }
            } else {
                callback.onCancelled()
            }
        }
    }

    fun approveSelection(selectedPaths: List<String>) {
        val cb = lastCallback ?: return
        if (selectedPaths.isNotEmpty()) {
            cb.onFilesSelected(selectedPaths.map { toSelectedFile(File(it)) }, null)
        } else {
            cb.onCancelled()
        }
    }

    fun cancelSelection() {
        lastCallback?.onCancelled()
    }

    override fun dispose() {
        lastCallback = null
    }

    private fun toSelectedFile(file: File): SelectedFile =
        SelectedFile(
            name = file.name,
            pathOrUri = file.absolutePath,
            sizeBytes = if (file.isFile) file.length() else 0L,
            isDirectory = file.isDirectory,
            bytesReader = { if (file.isFile && file.exists()) file.readBytes() else ByteArray(0) },
            bytesWriter = { bytes -> file.writeBytes(bytes) },
            childrenLister = {
                file.listFiles()?.map { toSelectedFile(it) } ?: emptyList()
            }
        )
}
