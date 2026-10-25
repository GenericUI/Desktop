@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.component.dialog.FileBrowserCallback
import com.nexustools.genericui.component.dialog.FileBrowserRequest
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
                val chosenFile = showNativeWin32FileDialog(
                    hwndOwner = Win32NativeWindowHost.activeHwnd,
                    title = request.title.ifBlank { if (isSave) "Save File" else "Open File" },
                    suggestedFileName = request.suggestedFileName,
                    isSave = isSave
                )
                if (!chosenFile.isNullOrBlank()) {
                    val fileName = chosenFile.substringAfterLast('\\').substringAfterLast('/').ifEmpty { chosenFile }
                    callback.onFilesSelected(
                        listOf(
                            SelectedFile(
                                pathOrUri = chosenFile,
                                name = fileName,
                                sizeBytes = 0L,
                                isDirectory = false
                            )
                        )
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

    private fun showNativeWin32FileDialog(
        hwndOwner: HWND?,
        title: String,
        suggestedFileName: String?,
        isSave: Boolean
    ): String? = memScoped {
        val fileBuf = allocArray<WCHARVar>(MAX_PATH)
        val initial = suggestedFileName?.trim().orEmpty()
        for (i in 0 until minOf(initial.length, MAX_PATH - 1)) {
            fileBuf[i] = initial[i].code.toUShort()
        }
        val ofn = alloc<OPENFILENAMEW>()
        ofn.lStructSize = sizeOf<OPENFILENAMEW>().toUInt()
        ofn.hwndOwner = hwndOwner
        ofn.lpstrFile = fileBuf
        ofn.nMaxFile = MAX_PATH.toUInt()
        ofn.lpstrTitle = title.wcstr.ptr
        ofn.Flags = (if (isSave) {
            OFN_OVERWRITEPROMPT or OFN_PATHMUSTEXIST or OFN_NOCHANGEDIR
        } else {
            OFN_FILEMUSTEXIST or OFN_PATHMUSTEXIST or OFN_NOCHANGEDIR
        }).toUInt()

        val ok = if (isSave) GetSaveFileNameW(ofn.ptr) else GetOpenFileNameW(ofn.ptr)
        if (ok != 0) {
            fileBuf.toKString().trim().takeIf { it.isNotEmpty() }
        } else {
            null
        }
    }

    private fun showNativeWin32FolderPicker(hwndOwner: HWND?, title: String): String? = memScoped {
        CoInitializeEx(null, COINIT_APARTMENTTHREADED.toUInt())
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

    companion object {
        const val FOS_OVERWRITEPROMPT: Int = 0x00000002
        const val FOS_PICKFOLDERS: Int = 0x00000020
        const val FOS_FORCEFILESYSTEM: Int = 0x00000040
        const val FOS_ALLOWMULTISELECT: Int = 0x00000200
        const val FOS_PATHMUSTEXIST: Int = 0x00000800
    }
}
