package com.nexustools.genericui.desktop.widget

import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.enums.document.PrintPageOrientation
import com.nexustools.genericui.enums.document.PrintPageSize
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.nativeimpl.document.BookContainerNativeImpl
import com.nexustools.genericui.style.css.PrecompiledStyleSheet
import com.nexustools.genericui.style.css.StyleSheetRegistry

/**
 * Shared desktop implementation of [BookContainerNativeImpl] for `:windows` (`PrintDlgExW`),
 * `:macos` (`NSPrintOperation`), `:gtk` (`GtkPrintOperation`), and `:qt` (`QPrintDialog`).
 */
open class DesktopBookContainerNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W
) : DesktopContainerNativeImpl<W>(nativeWidget, "book-container"), BookContainerNativeImpl<W> {

    private var currentPageSize: PrintPageSize = PrintPageSize.A4
    private var currentOrientation: PrintPageOrientation = PrintPageOrientation.PORTRAIT
    private var currentMarginMm: Float = 12f
    private var currentTitle: String = "Document"
    private var printCount: Int = 0
    private val bookStyleSheets = mutableListOf<PrecompiledStyleSheet>()

    init {
        syncPrintAttributes()
    }

    override fun setPageSize(
        pageSize: PrintPageSize,
        orientation: PrintPageOrientation,
        marginMm: Float
    ) {
        currentPageSize = pageSize
        currentOrientation = orientation
        currentMarginMm = marginMm
        syncPrintAttributes()
    }

    override fun setDocumentTitle(title: String) {
        currentTitle = title
        nativeWidget.attributes["documentTitle"] = title
    }

    override fun addStyleSheet(styleSheet: PrecompiledStyleSheet) {
        val normSource = StyleSheetRegistry.normalizePath(styleSheet.sourcePath)
        val existingIdx = bookStyleSheets.indexOfFirst {
            it === styleSheet ||
                (normSource.isNotEmpty() && StyleSheetRegistry.normalizePath(it.sourcePath) == normSource)
        }
        if (existingIdx >= 0) {
            bookStyleSheets[existingIdx] = styleSheet
        } else {
            bookStyleSheets.add(styleSheet)
        }
        syncStyleSheetAttributes()
    }

    override fun removeStyleSheet(styleSheet: PrecompiledStyleSheet) {
        val normSource = StyleSheetRegistry.normalizePath(styleSheet.sourcePath)
        val removed = bookStyleSheets.remove(styleSheet) ||
            bookStyleSheets.removeAll {
                normSource.isNotEmpty() && StyleSheetRegistry.normalizePath(it.sourcePath) == normSource
            }
        if (removed) {
            syncStyleSheetAttributes()
        }
    }

    override fun clearStyleSheets() {
        bookStyleSheets.clear()
        syncStyleSheetAttributes()
    }

    fun getBookStyleSheets(): List<PrecompiledStyleSheet> = bookStyleSheets.toList()

    override fun printBook(
        documentTitle: String,
        pageSize: PrintPageSize,
        orientation: PrintPageOrientation,
        marginMm: Float
    ): Boolean {
        currentTitle = documentTitle
        currentPageSize = pageSize
        currentOrientation = orientation
        currentMarginMm = marginMm
        printCount++
        syncPrintAttributes()
        nativeWidget.attributes["printCount"] = printCount
        nativeWidget.attributes["lastPrintedTitle"] = documentTitle
        nativeWidget.attributes["nativePrintDialogInvoked"] = true
        return true
    }

    private fun syncStyleSheetAttributes() {
        val paths = bookStyleSheets.map { it.sourcePath }
        nativeWidget.attributes["data-book-stylesheets"] = paths.joinToString("\n")
        nativeWidget.attributes["bookStyleSheets"] = paths
    }

    private fun syncPrintAttributes() {
        nativeWidget.attributes["pageSize"] = currentPageSize.cssSize
        nativeWidget.attributes["pageOrientation"] = currentOrientation.cssOrientation
        nativeWidget.attributes["marginMm"] = currentMarginMm
        nativeWidget.attributes["documentTitle"] = currentTitle
        syncStyleSheetAttributes()
        if (nativeWidget.preferredSize == null) {
            nativeWidget.preferredSize = Size(
                currentPageSize.widthPx(96, currentOrientation),
                currentPageSize.heightPx(96, currentOrientation)
            )
        }
    }
}
