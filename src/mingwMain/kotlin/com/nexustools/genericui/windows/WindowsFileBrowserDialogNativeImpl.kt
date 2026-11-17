@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.component.dialog.FileBrowserCallback
import com.nexustools.genericui.component.dialog.FileBrowserRequest
import com.nexustools.genericui.component.dialog.FileTypeFilter
import com.nexustools.genericui.component.dialog.SelectedFile
import com.nexustools.genericui.desktop.dialog.DesktopFileBrowserDialogNativeImpl
import com.nexustools.genericui.desktop.dialog.DesktopFileBrowserHelper
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl
import kotlinx.cinterop.*
import platform.windows.*

/**
 * Windows native file & folder browser implementation of [com.nexustools.genericui.nativeimpl.dialog.FileBrowserDialogNativeImpl].
 *
 * Uses Win32 `SHBrowseForFolderW` + `SHGetPathFromIDListW` (`BIF_RETURNONLYFSDIRS | BIF_NEWDIALOGSTYLE`)
 * for interactive folder selection and `GetOpenFileNameW` / `GetSaveFileNameW` for file selection when
 * [WindowsApplicationNativeImpl.interactiveWin32GuiEnabled] is active, with automatic fallback for headless unit tests.
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

        if (WindowsApplicationNativeImpl.interactiveWin32GuiEnabled && Win32NativeWindowHost.activeHwnd != null) {
            if (DesktopFileBrowserHelper.isFolderSelection(request)) {
                val chosenFolder = showNativeWin32FolderPicker(
                    hwndOwner = Win32NativeWindowHost.activeHwnd,
                    title = request.title.ifBlank { "Select Folder" }
                )
                if (!chosenFolder.isNullOrBlank()) {
                    val folderName = chosenFolder.substringAfterLast('\\').substringAfterLast('/').ifEmpty { chosenFolder }
                    callback.onFilesSelected(
                        listOf(
                            SelectedFile(
                                pathOrUri = chosenFolder,
                                name = folderName,
                                sizeBytes = -1L,
                                isDirectory = true
                            )
                        )
                    )
                } else {
                    callback.onCancelled()
                }
                Win32NativeWindowHost.activeHwnd?.let { InvalidateRect(it, null, 0) }
                return
            } else {
                val isSave = DesktopFileBrowserHelper.isSaveDialog(request)
                val chosenFiles = showNativeWin32FileDialog(
                    hwndOwner = Win32NativeWindowHost.activeHwnd,
                    title = request.title.ifBlank { if (isSave) "Save File" else "Open File" },
                    suggestedFileName = request.suggestedFileName,
                    initialDirectory = request.initialDirectory,
                    isSave = isSave,
                    allowMultiple = DesktopFileBrowserHelper.isMultipleSelection(request),
                    filters = request.filters
                )
                if (chosenFiles.isNotEmpty()) {
                    callback.onFilesSelected(
                        chosenFiles.map { chosenFile ->
                            val fileName = chosenFile.substringAfterLast('\\').substringAfterLast('/').ifEmpty { chosenFile }
                            SelectedFile(
                                pathOrUri = chosenFile,
                                name = fileName,
                                sizeBytes = 0L,
                                isDirectory = false
                            )
                        }
                    )
                } else {
                    callback.onCancelled()
                }
                Win32NativeWindowHost.activeHwnd?.let { InvalidateRect(it, null, 0) }
                return
            }
        }

        super.showFileBrowser(parentWindow, request, callback)
    }

    companion object {
        const val FOS_OVERWRITEPROMPT: Int = 0x00000002
        const val FOS_PICKFOLDERS: Int = 0x00000020
        const val FOS_FORCEFILESYSTEM: Int = 0x00000040
        const val FOS_ALLOWMULTISELECT: Int = 0x00000200
        const val FOS_PATHMUSTEXIST: Int = 0x00000800

        internal fun showNativeWin32FileDialog(
            hwndOwner: HWND?,
            title: String,
            suggestedFileName: String? = null,
            initialDirectory: String? = null,
            isSave: Boolean,
            allowMultiple: Boolean = false,
            filters: List<FileTypeFilter> = emptyList()
        ): List<String> = memScoped {
            val bufSize = if (allowMultiple && !isSave) 32_768 else MAX_PATH
            val fileBuf = allocArray<WCHARVar>(bufSize)
            val initial = suggestedFileName?.trim().orEmpty()
            for (i in 0 until minOf(initial.length, bufSize - 1)) {
                fileBuf[i] = initial[i].code.toUShort()
            }
            val filterSpecs = DesktopFileBrowserHelper.buildWin32FilterSpecs(filters)
                .ifEmpty { listOf("All Files (*.*)" to "*.*") }
            val filterStr = buildString {
                for ((desc, pattern) in filterSpecs) {
                    append(desc)
                    append('\u0000')
                    append(pattern)
                    append('\u0000')
                }
                append('\u0000')
            }
            val filterWChars = allocArray<WCHARVar>(filterStr.length + 1)
            for (i in filterStr.indices) {
                filterWChars[i] = filterStr[i].code.toUShort()
            }
            val defaultExt = filters.firstOrNull()?.extensions?.firstOrNull()
                ?.trim()
                ?.removePrefix("*.")
                ?.removePrefix(".")
                ?.takeIf { it.isNotEmpty() && it != "*" }

            val ofn = alloc<OPENFILENAMEW>()
            ofn.lStructSize = sizeOf<OPENFILENAMEW>().toUInt()
            ofn.hwndOwner = hwndOwner
            ofn.lpstrFile = fileBuf
            ofn.nMaxFile = bufSize.toUInt()
            ofn.lpstrFilter = filterWChars
            ofn.nFilterIndex = 1u
            initialDirectory?.trim()?.takeIf { it.isNotEmpty() }?.let { dir ->
                ofn.lpstrInitialDir = WindowsIoHelper.normalizeWin32Path(dir).wcstr.ptr
            }
            if (defaultExt != null) {
                ofn.lpstrDefExt = defaultExt.wcstr.ptr
            }
            ofn.lpstrTitle = title.wcstr.ptr
            val baseFlags = if (isSave) {
                OFN_OVERWRITEPROMPT or OFN_PATHMUSTEXIST or OFN_NOCHANGEDIR
            } else {
                var f = OFN_FILEMUSTEXIST or OFN_PATHMUSTEXIST or OFN_NOCHANGEDIR
                if (allowMultiple) {
                    f = f or OFN_ALLOWMULTISELECT or OFN_EXPLORER
                }
                f
            }
            ofn.Flags = baseFlags.toUInt()

            val ok = if (isSave) GetSaveFileNameW(ofn.ptr) else GetOpenFileNameW(ofn.ptr)
            if (ok == 0) return@memScoped emptyList()

            if (!allowMultiple || isSave) {
                val single = fileBuf.toKString().trim()
                return@memScoped if (single.isNotEmpty()) listOf(single) else emptyList()
            }

            val segments = ArrayList<String>()
            var pos = 0
            while (pos < bufSize) {
                val sb = StringBuilder()
                while (pos < bufSize && fileBuf[pos] != 0.toUShort()) {
                    sb.append(fileBuf[pos].toInt().toChar())
                    pos++
                }
                if (sb.isEmpty()) break
                segments.add(sb.toString())
                pos++
            }
            when (segments.size) {
                0 -> emptyList()
                1 -> listOf(segments[0])
                else -> {
                    val dir = segments[0].trimEnd('\\', '/')
                    segments.drop(1).map { fileName -> "$dir\\$fileName" }
                }
            }
        }

        internal fun showNativeWin32FolderPicker(hwndOwner: HWND?, title: String): String? = memScoped {
            CoInitializeEx(null, COINIT_APARTMENTTHREADED)
            val displayNameBuf = allocArray<WCHARVar>(MAX_PATH)
            val bi = alloc<BROWSEINFOW>()
            bi.hwndOwner = hwndOwner
            bi.pszDisplayName = displayNameBuf
            bi.lpszTitle = title.wcstr.ptr
            bi.ulFlags = (BIF_RETURNONLYFSDIRS or BIF_NEWDIALOGSTYLE or BIF_USENEWUI).toUInt()

            val pidl = SHBrowseForFolderW(bi.ptr) ?: return@memScoped null
            val pathBuf = allocArray<WCHARVar>(MAX_PATH)
            val ok = SHGetPathFromIDListW(pidl, pathBuf)
            CoTaskMemFree(pidl)
            if (ok != 0) {
                pathBuf.toKString().trim().takeIf { it.isNotEmpty() }
            } else {
                null
            }
        }
    }
}
