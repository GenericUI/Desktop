package com.nexustools.genericui.swing

import com.nexustools.genericui.enums.document.PrintPageOrientation
import com.nexustools.genericui.enums.document.PrintPageSize
import com.nexustools.genericui.nativeimpl.document.BookContainerNativeImpl
import java.awt.Color
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Graphics2D
import java.awt.print.PageFormat
import java.awt.print.Paper
import java.awt.print.Printable
import java.awt.print.PrinterJob
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Java Swing (`javax.swing.JPanel` + `java.awt.print.PrinterJob`) implementation of [BookContainerNativeImpl].
 *
 * Uses the system's native print dialog (`PrinterJob.printDialog()`), which supports printing to physical
 * printers or saving to PDF via the operating system's native print dialog.
 */
class SwingBookContainerImpl : SwingBaseContainerNativeImpl(
    JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = Color.WHITE
        isOpaque = true
    },
    "book-container"
), BookContainerNativeImpl<JComponent> {

    private var currentPageSize: PrintPageSize = PrintPageSize.A4
    private var currentOrientation: PrintPageOrientation = PrintPageOrientation.PORTRAIT
    private var currentMarginMm: Float = 12f
    private var currentTitle: String = "Document"
    private var printCount: Int = 0

    override fun setPageSize(
        pageSize: PrintPageSize,
        orientation: PrintPageOrientation,
        marginMm: Float
    ) {
        currentPageSize = pageSize
        currentOrientation = orientation
        currentMarginMm = marginMm
        nativeWidget.putClientProperty("genericui.pageSize", pageSize.cssSize)
        nativeWidget.putClientProperty("genericui.pageOrientation", orientation.cssOrientation)
        nativeWidget.putClientProperty("genericui.marginMm", marginMm)
        nativeWidget.preferredSize = Dimension(
            pageSize.widthPx(96, orientation),
            pageSize.heightPx(96, orientation)
        )
        nativeWidget.revalidate()
    }

    override fun setDocumentTitle(title: String) {
        currentTitle = title
        nativeWidget.putClientProperty("genericui.documentTitle", title)
    }

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
        nativeWidget.putClientProperty("genericui.printCount", printCount)
        nativeWidget.putClientProperty("genericui.lastPrintedTitle", documentTitle)

        if (GraphicsEnvironment.isHeadless() || !nativeWidget.isShowing) {
            return true
        }

        return runCatching {
            val job = PrinterJob.getPrinterJob()
            job.jobName = documentTitle.ifBlank { "Document" }
            val pageFormat = job.defaultPage()
            pageFormat.orientation = if (orientation == PrintPageOrientation.LANDSCAPE) {
                PageFormat.LANDSCAPE
            } else {
                PageFormat.PORTRAIT
            }
            val paper = Paper()
            val wPt = pageSize.widthPt(false).toDouble()
            val hPt = pageSize.heightPt(false).toDouble()
            val marginPt = ((marginMm / 25.4) * 72.0).coerceAtLeast(0.0)
            paper.setSize(wPt, hPt)
            paper.setImageableArea(
                marginPt,
                marginPt,
                (wPt - marginPt * 2.0).coerceAtLeast(1.0),
                (hPt - marginPt * 2.0).coerceAtLeast(1.0)
            )
            pageFormat.paper = paper

            job.setPrintable(
                Printable { graphics, pf, pageIndex ->
                    val pageComponents = nativeWidget.components
                    val totalPages = pageComponents.size.coerceAtLeast(1)
                    if (pageIndex >= totalPages) {
                        return@Printable Printable.NO_SUCH_PAGE
                    }
                    val g2d = graphics as Graphics2D
                    g2d.translate(pf.imageableX, pf.imageableY)
                    val targetComp = if (pageComponents.isNotEmpty()) pageComponents[pageIndex] else nativeWidget
                    val compW = targetComp.width.coerceAtLeast(1).toDouble()
                    val compH = targetComp.height.coerceAtLeast(1).toDouble()
                    val scaleX = pf.imageableWidth / compW
                    val scaleY = pf.imageableHeight / compH
                    val scale = minOf(scaleX, scaleY)
                    g2d.scale(scale, scale)
                    targetComp.printAll(g2d)
                    Printable.PAGE_EXISTS
                },
                pageFormat
            )

            if (job.printDialog()) {
                job.print()
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }
}
