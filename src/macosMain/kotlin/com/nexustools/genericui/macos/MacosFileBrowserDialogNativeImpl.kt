@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.apple.AppleUniformTypeHelper
import com.nexustools.genericui.component.dialog.FileBrowserCallback
import com.nexustools.genericui.component.dialog.FileBrowserRequest
import com.nexustools.genericui.component.dialog.FileTypeFilter
import com.nexustools.genericui.component.dialog.SelectedFile
import com.nexustools.genericui.desktop.dialog.DesktopFileBrowserDialogNativeImpl
import com.nexustools.genericui.desktop.dialog.DesktopFileBrowserHelper
import com.nexustools.genericui.desktop.system.DesktopConfigDirectoryHelper
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.AppKit.NSModalResponseOK
import platform.AppKit.NSOpenPanel
import platform.AppKit.NSSavePanel
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSURL

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

        if (!MacosApplicationNativeImpl.interactiveMacosGuiEnabled || simulatedSelectedPaths != null) {
            super.showFileBrowser(parentWindow, request, callback)
            return
        }

        lastRequest = request
        GenericUiThreading.runOnUi {
            val selectedPaths = presentNativePanel(request)
            val filters = DesktopFileBrowserHelper.effectiveFilters(request)
            val chosenFilter = filters.getOrNull(request.activeFilterIndex)
            if (selectedPaths.isEmpty()) {
                GenericUiThreading.runOnLogic {
                    callback.onCancelled()
                }
            } else {
                val files = selectedPaths.map { buildMacosSelectedFile(it, request.showHiddenFiles) }
                GenericUiThreading.runOnLogic {
                    callback.onFilesSelected(files, chosenFilter)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun presentNativePanel(request: FileBrowserRequest): List<String> {
        val isSave = DesktopFileBrowserHelper.isSaveDialog(request)
        if (isSave) {
            val panel = NSSavePanel.savePanel()
            panel.title = request.title
            panel.canCreateDirectories = request.createDirectoriesAllowed
            panel.showsHiddenFiles = request.showHiddenFiles
            request.initialDirectory?.takeIf { it.isNotBlank() }?.let { dir ->
                panel.directoryURL = NSURL.fileURLWithPath(dir)
            }
            request.suggestedFileName?.takeIf { it.isNotBlank() }?.let { suggested ->
                panel.nameFieldStringValue = suggested
            }
            val utTypes = AppleUniformTypeHelper.utTypesForFilters(request.filters)
            if (utTypes.isNotEmpty()) {
                panel.allowedContentTypes = utTypes
            }
            val result = panel.runModal()
            if (result != NSModalResponseOK) return emptyList()
            val path = panel.URL?.path ?: return emptyList()
            return listOf(path)
        } else {
            val panel = NSOpenPanel.openPanel()
            panel.title = request.title
            panel.canChooseDirectories = canChooseDirectories
            panel.canChooseFiles = canChooseFiles
            panel.allowsMultipleSelection = allowsMultipleSelection
            panel.canCreateDirectories = request.createDirectoriesAllowed
            panel.showsHiddenFiles = request.showHiddenFiles
            request.initialDirectory?.takeIf { it.isNotBlank() }?.let { dir ->
                panel.directoryURL = NSURL.fileURLWithPath(dir)
            }
            if (!canChooseDirectories) {
                val utTypes = AppleUniformTypeHelper.utTypesForFilters(request.filters)
                if (utTypes.isNotEmpty()) {
                    panel.allowedContentTypes = utTypes
                }
            }
            val result = panel.runModal()
            if (result != NSModalResponseOK) return emptyList()
            val urls = panel.URLs
            val out = ArrayList<String>(urls.size)
            for (u in urls) {
                val nsUrl = u as? NSURL ?: continue
                val p = nsUrl.path ?: continue
                if (p.isNotEmpty()) out.add(p)
            }
            return out
        }
    }

    companion object {
        internal fun buildMacosSelectedFile(fullPath: String, includeHidden: Boolean = false): SelectedFile {
            val fm = NSFileManager.defaultManager
            val isDir = memScoped {
                val isDirVar = alloc<BooleanVar>()
                val exists = fm.fileExistsAtPath(fullPath, isDirectory = isDirVar.ptr)
                exists && isDirVar.value
            }
            val sizeBytes = if (!isDir) {
                val attrs = fm.attributesOfItemAtPath(fullPath, error = null)
                (attrs?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L
            } else {
                0L
            }
            val normalized = fullPath.replace('\\', '/').trimEnd('/').ifEmpty { "/" }
            val name = normalized.substringAfterLast('/', normalized)
            val ext = name.substringAfterLast('.', "")
            return SelectedFile(
                name = name,
                pathOrUri = fullPath,
                sizeBytes = sizeBytes,
                mimeType = if (isDir) "inode/directory" else FileTypeFilter.inferMimeTypeFromExtension(ext),
                isDirectory = isDir,
                bytesReader = if (!isDir) {
                    suspend { DesktopConfigDirectoryHelper.readBytesFromFile(fullPath) ?: ByteArray(0) }
                } else {
                    null
                },
                bytesWriter = if (!isDir) {
                    suspend { data -> DesktopConfigDirectoryHelper.writeBytesToFile(fullPath, data) }
                } else {
                    null
                },
                childrenLister = if (isDir) {
                    suspend {
                        val rawNames = fm.contentsOfDirectoryAtPath(fullPath, error = null) ?: emptyList<Any?>()
                        rawNames
                            .mapNotNull { it as? String }
                            .filter { entry ->
                                entry.isNotEmpty() &&
                                    entry != "." &&
                                    entry != ".." &&
                                    entry != ".DS_Store" &&
                                    (includeHidden || !entry.startsWith("."))
                            }
                            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
                            .map { childName ->
                                val childPath = if (fullPath.endsWith("/")) "$fullPath$childName" else "$fullPath/$childName"
                                buildMacosSelectedFile(childPath, includeHidden)
                            }
                    }
                } else {
                    null
                }
            )
        }
    }
}
