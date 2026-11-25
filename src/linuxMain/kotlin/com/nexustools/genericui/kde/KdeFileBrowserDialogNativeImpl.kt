@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.component.dialog.FileBrowserCallback
import com.nexustools.genericui.component.dialog.FileBrowserRequest
import com.nexustools.genericui.desktop.dialog.DesktopFileBrowserDialogNativeImpl
import com.nexustools.genericui.desktop.dialog.DesktopFileBrowserHelper
import com.nexustools.genericui.linux.LinuxSelectedFileHelper
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl
import com.nexustools.genericui.threading.GenericUiThreading

/**
 * KDE (`QFileDialog` integrating Plasma's native `KFileWidget` portal/platform theme) implementation of
 * [com.nexustools.genericui.nativeimpl.dialog.FileBrowserDialogNativeImpl].
 */
class KdeFileBrowserDialogNativeImpl : DesktopFileBrowserDialogNativeImpl(
    nativeDialogType = "KDE_QFileDialog"
) {
    override fun showFileBrowser(
        parentWindow: WindowNativeImpl<*>?,
        request: FileBrowserRequest,
        callback: FileBrowserCallback
    ) {
        if (!KdeApplicationNativeImpl.interactiveKdeGuiEnabled || simulatedSelectedPaths != null) {
            super.showFileBrowser(parentWindow, request, callback)
            return
        }

        lastRequest = request
        val ownerPtr = (parentWindow as? KdeWindowNativeImpl)?.nativeWidget?.qWidgetPtr
            ?: KdeNativeWindowHost.activeQWidgetPtr()

        GenericUiThreading.runOnUi {
            val isFolder = DesktopFileBrowserHelper.isFolderSelection(request)
            val isSave = DesktopFileBrowserHelper.isSaveDialog(request)
            val isMulti = DesktopFileBrowserHelper.isMultipleSelection(request)
            val dlg = KdeDynamicBindings.createQFileDialog(ownerPtr)
            KdeDynamicBindings.configureFileDialog(
                dlg = dlg,
                title = request.title.ifBlank { if (isFolder) "Select Folder" else "Select File" },
                directoryOnly = isFolder,
                saveMode = isSave,
                allowMultiple = isMulti,
                initialDir = request.initialDirectory
            )

            var selectedSingle: String? = null
            var selectedMulti: List<String> = emptyList()
            KdeVTableBridge.connectSignal(dlg, "fileSelected(QString)") { args ->
                val path = KdeDynamicBindings.readBorrowedQString(KdeVTableBridge.readSignalArgPtr(args, 1))
                if (path.isNotBlank()) selectedSingle = path
            }
            KdeVTableBridge.connectSignal(dlg, "filesSelected(QStringList)") { args ->
                selectedMulti = KdeDynamicBindings.readQStringList(KdeVTableBridge.readSignalArgPtr(args, 1))
            }

            val accepted = KdeDynamicBindings.execDialog(dlg) == 1
            KdeVTableBridge.destroyObject(dlg)

            val paths = when {
                !accepted -> emptyList()
                selectedMulti.isNotEmpty() -> selectedMulti
                selectedSingle != null -> listOf(selectedSingle!!)
                else -> emptyList()
            }

            val chosenFilter = DesktopFileBrowserHelper.effectiveFilters(request).getOrNull(request.activeFilterIndex)
            GenericUiThreading.runOnLogic {
                if (paths.isEmpty()) {
                    callback.onCancelled()
                } else {
                    callback.onFilesSelected(
                        paths.map { LinuxSelectedFileHelper.buildSelectedFile(it, request.showHiddenFiles) },
                        chosenFilter
                    )
                }
            }
        }
    }
}
