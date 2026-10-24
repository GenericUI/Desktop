package com.nexustools.genericui.desktop.dialog

import com.nexustools.genericui.component.dialog.FileBrowserCallback
import com.nexustools.genericui.component.dialog.FileBrowserRequest
import com.nexustools.genericui.component.dialog.FileTypeFilter
import com.nexustools.genericui.component.dialog.SelectedFile
import com.nexustools.genericui.enums.dialog.FileBrowserMode

/**
 * Shared Desktop helper for formatting native file and folder browser filters across:
 * - `:windows` (`IFileOpenDialog` / `IFileSaveDialog` `COMDLG_FILTERSPEC` and `FOS_PICKFOLDERS`)
 * - `:macos` (`NSOpenPanel` / `NSSavePanel` `allowedContentTypes` / `UTType` and `canChooseDirectories`)
 * - `:gtk` (`GtkFileDialog` + `GtkFileFilter` pattern lists)
 * - `:qt` (`QFileDialog` filter strings `"Images (*.png *.jpg);;All Files (*)"`)
 */
object DesktopFileBrowserHelper {

    fun isFolderSelection(request: FileBrowserRequest): Boolean =
        request.mode == FileBrowserMode.SELECT_FOLDER

    fun isSaveDialog(request: FileBrowserRequest): Boolean =
        request.mode == FileBrowserMode.SAVE_FILE

    fun isMultipleSelection(request: FileBrowserRequest): Boolean =
        request.mode == FileBrowserMode.OPEN_MULTIPLE_FILES

    fun effectiveFilters(request: FileBrowserRequest): List<FileTypeFilter> {
        if (request.mode == FileBrowserMode.SELECT_FOLDER) return emptyList()
        return if (request.filters.isEmpty()) listOf(FileTypeFilter.ALL_FILES) else request.filters
    }

    fun buildWin32FilterSpecs(filters: List<FileTypeFilter>): List<Pair<String, String>> {
        val list = if (filters.isEmpty()) listOf(FileTypeFilter.ALL_FILES) else filters
        return list.map { filter -> filter.description to filter.toDesktopPatternString() }
    }

    fun buildQtFilterString(filters: List<FileTypeFilter>): String {
        val list = if (filters.isEmpty()) listOf(FileTypeFilter.ALL_FILES) else filters
        return list.joinToString(";;") { filter ->
            val exts = filter.normalizedExtensions
            val pattern = if (exts.isEmpty() || exts.contains("*")) "*" else exts.joinToString(" ") { "*.$it" }
            "${filter.description} ($pattern)"
        }
    }

    fun buildGtkFilterPatterns(filters: List<FileTypeFilter>): List<Pair<String, List<String>>> {
        val list = if (filters.isEmpty()) listOf(FileTypeFilter.ALL_FILES) else filters
        return list.map { filter ->
            val exts = filter.normalizedExtensions
            val globs = if (exts.isEmpty() || exts.contains("*")) listOf("*") else exts.map { "*.$it" }
            filter.description to globs
        }
    }

    fun extractMacosAllowedExtensions(filters: List<FileTypeFilter>): List<String> {
        val list = if (filters.isEmpty()) listOf(FileTypeFilter.ALL_FILES) else filters
        if (list.any { it.normalizedExtensions.contains("*") }) return emptyList()
        val types = linkedSetOf<String>()
        for (filter in list) {
            types.addAll(filter.macOsUniformTypeIdentifiers)
            types.addAll(filter.normalizedExtensions)
        }
        return types.toList()
    }

    /**
     * Formats filters for Win32 `IFileOpenDialog::SetFileTypes` (`COMDLG_FILTERSPEC` pairs of `pszName` to `pszSpec`).
     */
    fun toWin32FilterSpecs(request: FileBrowserRequest): List<Pair<String, String>> =
        effectiveFilters(request).map { filter ->
            filter.description to filter.toDesktopPatternString()
        }

    /**
     * Formats filters for Qt6 `QFileDialog` (`"Description (*.ext1 *.ext2);;..."`).
     */
    fun toQtFilterString(request: FileBrowserRequest): String =
        effectiveFilters(request).joinToString(";;") { filter ->
            val exts = filter.normalizedExtensions
            val pattern = if (exts.isEmpty() || exts.contains("*")) "*" else exts.joinToString(" ") { "*.$it" }
            "${filter.description} ($pattern)"
        }

    /**
     * Formats filters for GTK4 `GtkFileFilter` (`description` to list of glob patterns `*.ext`).
     */
    fun toGtkFilterPatterns(request: FileBrowserRequest): List<Pair<String, List<String>>> =
        effectiveFilters(request).map { filter ->
            val exts = filter.normalizedExtensions
            val globs = if (exts.isEmpty() || exts.contains("*")) listOf("*") else exts.map { "*.$it" }
            filter.description to globs
        }

    /**
     * Formats allowed extensions / Uniform Type Identifiers for macOS `NSOpenPanel` / `NSSavePanel`.
     */
    fun toMacOsAllowedTypes(request: FileBrowserRequest): List<String> {
        val filters = effectiveFilters(request)
        if (filters.any { it.normalizedExtensions.contains("*") }) return emptyList()
        val types = linkedSetOf<String>()
        for (filter in filters) {
            types.addAll(filter.macOsUniformTypeIdentifiers)
            types.addAll(filter.normalizedExtensions)
        }
        return types.toList()
    }

    /**
     * Wraps native filesystem paths selected by the user into [SelectedFile] instances and dispatches
     * [FileBrowserCallback.onFilesSelected] or [FileBrowserCallback.onCancelled].
     */
    fun dispatchSelectionResult(
        request: FileBrowserRequest,
        selectedPaths: List<String>,
        activeFilterIndex: Int = request.selectedFilterIndex,
        callback: FileBrowserCallback
    ) {
        if (selectedPaths.isEmpty()) {
            callback.onCancelled()
            return
        }
        val filters = effectiveFilters(request)
        val chosenFilter = filters.getOrNull(activeFilterIndex)
        val isDir = request.mode == FileBrowserMode.SELECT_FOLDER
        val files = selectedPaths.map { fullPath ->
            val normalized = fullPath.replace('\\', '/')
            val name = normalized.trimEnd('/').substringAfterLast('/', fullPath)
            val ext = name.substringAfterLast('.', "")
            SelectedFile(
                name = name,
                pathOrUri = fullPath,
                sizeBytes = 0L,
                mimeType = if (isDir) "inode/directory" else FileTypeFilter.inferMimeTypeFromExtension(ext),
                isDirectory = isDir
            )
        }
        callback.onFilesSelected(files, chosenFilter)
    }
}
