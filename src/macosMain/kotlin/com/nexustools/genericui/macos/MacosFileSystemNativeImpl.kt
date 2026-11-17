@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.apple.AppleFileSystemBase
import com.nexustools.genericui.apple.AppleUniformTypeHelper
import com.nexustools.genericui.component.dialog.FileTypeFilter
import com.nexustools.genericui.nativeimpl.io.FileReadableNativeImpl
import com.nexustools.genericui.nativeimpl.io.FileWritableNativeImpl
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AppKit.NSModalResponseOK
import platform.AppKit.NSOpenPanel
import platform.AppKit.NSSavePanel
import platform.Foundation.NSURL
import kotlin.coroutines.resume

/**
 * macOS AppKit implementation of [com.nexustools.genericui.nativeimpl.io.FileSystemNativeImpl]
 * extending [AppleFileSystemBase] with `NSOpenPanel` and `NSSavePanel` file/directory pickers.
 */
class MacosFileSystemNativeImpl : AppleFileSystemBase() {

    val nativeApi: String = "AppleFileSystemBase_NSOpenPanel_NSSavePanel"

    @Suppress("DEPRECATION")
    override suspend fun openReadableFromFileDialog(
        title: String,
        initialDirectory: String?,
        filters: List<FileTypeFilter>
    ): Pair<String, FileReadableNativeImpl>? {
        if (!MacosApplicationNativeImpl.interactiveMacosGuiEnabled) return null
        val selectedUrl = suspendCancellableCoroutine<NSURL?> { cont ->
            GenericUiThreading.runOnUi {
                val panel = NSOpenPanel.openPanel()
                panel.title = title
                panel.canChooseFiles = true
                panel.canChooseDirectories = false
                panel.allowsMultipleSelection = false
                initialDirectory?.takeIf { it.isNotBlank() }?.let { dir ->
                    panel.directoryURL = NSURL.fileURLWithPath(dir)
                }
                val utTypes = AppleUniformTypeHelper.utTypesForFilters(filters)
                if (utTypes.isNotEmpty()) {
                    panel.allowedContentTypes = utTypes
                }
                val ok = panel.runModal() == NSModalResponseOK
                if (cont.isActive) cont.resume(if (ok) panel.URL else null)
            }
        } ?: return null
        return createSecurityScopedReadable(selectedUrl)
    }

    @Suppress("DEPRECATION")
    override suspend fun openReadablesFromFileDialog(
        title: String,
        initialDirectory: String?,
        filters: List<FileTypeFilter>
    ): List<Pair<String, FileReadableNativeImpl>> {
        if (!MacosApplicationNativeImpl.interactiveMacosGuiEnabled) return emptyList()
        val selectedUrls = suspendCancellableCoroutine<List<NSURL>> { cont ->
            GenericUiThreading.runOnUi {
                val panel = NSOpenPanel.openPanel()
                panel.title = title
                panel.canChooseFiles = true
                panel.canChooseDirectories = false
                panel.allowsMultipleSelection = true
                initialDirectory?.takeIf { it.isNotBlank() }?.let { dir ->
                    panel.directoryURL = NSURL.fileURLWithPath(dir)
                }
                val utTypes = AppleUniformTypeHelper.utTypesForFilters(filters)
                if (utTypes.isNotEmpty()) {
                    panel.allowedContentTypes = utTypes
                }
                val ok = panel.runModal() == NSModalResponseOK
                val urls = if (ok) {
                    panel.URLs.mapNotNull { it as? NSURL }
                } else {
                    emptyList()
                }
                if (cont.isActive) cont.resume(urls)
            }
        }
        return selectedUrls.mapNotNull { createSecurityScopedReadable(it) }
    }

    @Suppress("DEPRECATION")
    override suspend fun openWritableFromFileDialog(
        title: String,
        initialDirectory: String?,
        suggestedFileName: String,
        filters: List<FileTypeFilter>
    ): Pair<String, FileWritableNativeImpl>? {
        if (!MacosApplicationNativeImpl.interactiveMacosGuiEnabled) return null
        val targetUrl = suspendCancellableCoroutine<NSURL?> { cont ->
            GenericUiThreading.runOnUi {
                val panel = NSSavePanel.savePanel()
                panel.title = title
                panel.canCreateDirectories = true
                initialDirectory?.takeIf { it.isNotBlank() }?.let { dir ->
                    panel.directoryURL = NSURL.fileURLWithPath(dir)
                }
                if (suggestedFileName.isNotBlank()) {
                    panel.nameFieldStringValue = suggestedFileName
                }
                val utTypes = AppleUniformTypeHelper.utTypesForFilters(filters)
                if (utTypes.isNotEmpty()) {
                    panel.allowedContentTypes = utTypes
                }
                val ok = panel.runModal() == NSModalResponseOK
                if (cont.isActive) cont.resume(if (ok) panel.URL else null)
            }
        } ?: return null
        return createSecurityScopedWritable(targetUrl, append = false)
    }

    override suspend fun requestDirectoryAccess(initialPath: String?): String? {
        if (!MacosApplicationNativeImpl.interactiveMacosGuiEnabled) return null
        val selectedUrl = suspendCancellableCoroutine<NSURL?> { cont ->
            GenericUiThreading.runOnUi {
                val panel = NSOpenPanel.openPanel()
                panel.canChooseFiles = false
                panel.canChooseDirectories = true
                panel.allowsMultipleSelection = false
                panel.canCreateDirectories = true
                initialPath?.takeIf { it.isNotBlank() }?.let { dir ->
                    panel.directoryURL = NSURL.fileURLWithPath(dir)
                }
                val ok = panel.runModal() == NSModalResponseOK
                if (cont.isActive) cont.resume(if (ok) panel.URL else null)
            }
        } ?: return null
        return registerSecurityScopedUrl(selectedUrl)
    }
}
