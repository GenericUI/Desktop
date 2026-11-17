@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk

import com.nexustools.genericui.component.dialog.FileBrowserCallback
import com.nexustools.genericui.component.dialog.FileBrowserRequest
import com.nexustools.genericui.desktop.dialog.DesktopFileBrowserDialogNativeImpl
import com.nexustools.genericui.desktop.dialog.DesktopFileBrowserHelper
import com.nexustools.genericui.linux.LinuxGlibGioBindings
import com.nexustools.genericui.linux.LinuxSelectedFileHelper
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction

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

        if (!GtkApplicationNativeImpl.interactiveGtkGuiEnabled ||
            simulatedSelectedPaths != null ||
            !Gtk4DynamicBindings.ensureGtkInitialized()
        ) {
            super.showFileBrowser(parentWindow, request, callback)
            return
        }

        lastRequest = request
        val parentPtr = (parentWindow as? GtkWindowNativeImpl)?.let {
            GtkNativeWindowHost.sessionForWindow(it)?.gtkWindow
        } ?: GtkNativeWindowHost.activeGtkWindowPtr()

        GenericUiThreading.runOnUi {
            val selectedPaths = presentNativeFileDialog(parentPtr, request)
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

    private fun presentNativeFileDialog(
        parentPtr: COpaquePointer?,
        request: FileBrowserRequest
    ): List<String> {
        val dlg = Gtk4DynamicBindings.gtkFileDialogNew() ?: return emptyList()
        if (request.title.isNotBlank()) {
            Gtk4DynamicBindings.gtkFileDialogSetTitle(dlg, request.title)
        }
        request.initialDirectory?.takeIf { it.isNotBlank() }?.let { dirPath ->
            val dirFile = LinuxGlibGioBindings.fileNewForPath(dirPath)
            if (dirFile != null) {
                Gtk4DynamicBindings.gtkFileDialogSetInitialFolder(dlg, dirFile)
                LinuxGlibGioBindings.objectUnref(dirFile)
            }
        }
        val isFolder = DesktopFileBrowserHelper.isFolderSelection(request)
        val isSave = DesktopFileBrowserHelper.isSaveDialog(request)
        val isMultiple = DesktopFileBrowserHelper.isMultipleSelection(request)

        if (isSave) {
            request.suggestedFileName?.takeIf { it.isNotBlank() }?.let { suggested ->
                Gtk4DynamicBindings.gtkFileDialogSetInitialName(dlg, suggested)
            }
        }

        var filterStore: COpaquePointer? = null
        if (!isFolder && gtkFilterPatterns.isNotEmpty()) {
            val filterType = Gtk4DynamicBindings.gtkFileFilterGetType()
            filterStore = LinuxGlibGioBindings.listStoreNew(filterType)
            if (filterStore != null) {
                for ((label, globs) in gtkFilterPatterns) {
                    val f = Gtk4DynamicBindings.gtkFileFilterNew() ?: continue
                    Gtk4DynamicBindings.gtkFileFilterSetName(f, label)
                    for (glob in globs) {
                        Gtk4DynamicBindings.gtkFileFilterAddPattern(f, glob)
                    }
                    LinuxGlibGioBindings.listStoreAppend(filterStore, f)
                    LinuxGlibGioBindings.objectUnref(f)
                }
                Gtk4DynamicBindings.gtkFileDialogSetFilters(dlg, filterStore)
            }
        }

        val state = FileDialogModalState(isMultiple = !isFolder && !isSave && isMultiple)
        val ref = StableRef.create(state)
        val cb = staticCFunction { source: COpaquePointer?, res: COpaquePointer?, userData: COpaquePointer? ->
            if (userData != null) {
                val s = userData.asStableRef<FileDialogModalState>().get()
                if (source != null && res != null) {
                    s.finish(source, res)
                } else {
                    s.completed = true
                }
            }
        }

        when {
            isFolder -> {
                state.mode = FileDialogCallMode.SELECT_FOLDER
                Gtk4DynamicBindings.gtkFileDialogSelectFolder(dlg, parentPtr, cb, ref.asCPointer())
            }
            isSave -> {
                state.mode = FileDialogCallMode.SAVE
                Gtk4DynamicBindings.gtkFileDialogSave(dlg, parentPtr, cb, ref.asCPointer())
            }
            isMultiple -> {
                state.mode = FileDialogCallMode.OPEN_MULTIPLE
                Gtk4DynamicBindings.gtkFileDialogOpenMultiple(dlg, parentPtr, cb, ref.asCPointer())
            }
            else -> {
                state.mode = FileDialogCallMode.OPEN
                Gtk4DynamicBindings.gtkFileDialogOpen(dlg, parentPtr, cb, ref.asCPointer())
            }
        }

        val ctx = LinuxGlibGioBindings.mainContextDefault()
        while (!state.completed) {
            LinuxGlibGioBindings.mainContextIteration(ctx, mayBlock = true)
        }
        ref.dispose()
        if (filterStore != null) LinuxGlibGioBindings.objectUnref(filterStore)
        LinuxGlibGioBindings.objectUnref(dlg)
        return state.selectedPaths
    }

    private enum class FileDialogCallMode {
        OPEN,
        OPEN_MULTIPLE,
        SELECT_FOLDER,
        SAVE
    }

    private class FileDialogModalState(val isMultiple: Boolean) {
        var mode: FileDialogCallMode = FileDialogCallMode.OPEN
        var completed: Boolean = false
        val selectedPaths: MutableList<String> = ArrayList()

        fun finish(dlg: COpaquePointer, res: COpaquePointer) {
            when (mode) {
                FileDialogCallMode.OPEN -> extractSingleFile(Gtk4DynamicBindings.gtkFileDialogOpenFinish(dlg, res))
                FileDialogCallMode.SELECT_FOLDER -> extractSingleFile(Gtk4DynamicBindings.gtkFileDialogSelectFolderFinish(dlg, res))
                FileDialogCallMode.SAVE -> extractSingleFile(Gtk4DynamicBindings.gtkFileDialogSaveFinish(dlg, res))
                FileDialogCallMode.OPEN_MULTIPLE -> {
                    val listModel = Gtk4DynamicBindings.gtkFileDialogOpenMultipleFinish(dlg, res)
                    if (listModel != null) {
                        val n = LinuxGlibGioBindings.listModelGetNItems(listModel)
                        for (i in 0 until n) {
                            extractSingleFile(LinuxGlibGioBindings.listModelGetItem(listModel, i))
                        }
                        LinuxGlibGioBindings.objectUnref(listModel)
                    }
                }
            }
            completed = true
        }

        private fun extractSingleFile(gfile: COpaquePointer?) {
            if (gfile == null) return
            val path = LinuxGlibGioBindings.fileGetPath(gfile)
            LinuxGlibGioBindings.objectUnref(gfile)
            if (!path.isNullOrBlank()) {
                selectedPaths.add(path)
            }
        }
    }
}
