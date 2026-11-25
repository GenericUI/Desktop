package com.nexustools.genericui.swing

import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.nativeimpl.content.TableNativeImpl
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.color.RgbaColor
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

/**
 * Java Swing `JScrollPane` + `JTable` implementation of [TableNativeImpl].
 *
 * Wraps the underlying [table] inside a [JScrollPane] so [JTable.getTableHeader] and vertical/horizontal
 * scrolling render cleanly alongside zebra striping, hover highlights, and proportional column weights.
 */
class SwingTableImpl private constructor(
    val table: JTable,
    private val model: DefaultTableModel
) : SwingBaseContainerNativeImpl(
    JScrollPane(table),
    "table"
), TableNativeImpl<JComponent> {

    constructor() : this(
        table = JTable(DefaultTableModel()),
        model = DefaultTableModel()
    )

    override val nativeWidget: JScrollPane get() = super.nativeWidget as JScrollPane

    private var columnWeights: FloatArray = FloatArray(0)
    private var stripedFlag: Boolean = false
    private var hoverableFlag: Boolean = false
    private var hoveredRow: Int = -1

    init {
        table.model = model
        table.rowHeight = 30
        table.fillsViewportHeight = true
        table.showVerticalLines = false
        table.intercellSpacing = Dimension(0, 1)
        table.autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
        table.tableHeader?.reorderingAllowed = false

        nativeWidget.verticalScrollBar.unitIncrement = 16
        nativeWidget.horizontalScrollBar.unitIncrement = 16
        nativeWidget.border = BorderFactory.createEmptyBorder()

        val hoverTracker = object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                if (!hoverableFlag) return
                val row = table.rowAtPoint(e.point)
                if (row != hoveredRow) {
                    hoveredRow = row
                    table.repaint()
                }
            }

            override fun mouseExited(e: MouseEvent) {
                if (hoveredRow != -1) {
                    hoveredRow = -1
                    table.repaint()
                }
            }
        }
        table.addMouseMotionListener(hoverTracker)
        table.addMouseListener(hoverTracker)

        table.setDefaultRenderer(Any::class.java, object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                tbl: JTable,
                value: Any?,
                isSelected: Boolean,
                hasFocus: Boolean,
                row: Int,
                column: Int
            ): Component {
                val c = super.getTableCellRendererComponent(tbl, value, isSelected, hasFocus, row, column)
                val isDark = SwingSurfacePaintUtil.isDarkThemeForComponent(tbl)
                val colors = BootstrapColorPalette.activeThemeConfig.resolveEffectiveColors(isDark)
                val baseBg = colors.surface ?: if (isDark) RgbaColor(36, 39, 46) else RgbaColor(255, 255, 255)
                val fg = colors.onSurface ?: if (isDark) RgbaColor(235, 237, 242) else RgbaColor(28, 30, 36)
                if (!isSelected) {
                    val rowBg = when {
                        hoverableFlag && row == hoveredRow ->
                            SwingSurfacePaintUtil.blendRgb(baseBg, if (isDark) RgbaColor.WHITE else RgbaColor.BLACK, if (isDark) 0.12f else 0.06f)
                        stripedFlag && (row % 2 == 1) ->
                            SwingSurfacePaintUtil.blendRgb(baseBg, if (isDark) RgbaColor.WHITE else RgbaColor.BLACK, if (isDark) 0.05f else 0.03f)
                        else -> baseBg
                    }
                    c.background = Color(rowBg.r, rowBg.g, rowBg.b)
                    c.foreground = Color(fg.r, fg.g, fg.b)
                }
                if (c is JComponent) {
                    c.border = BorderFactory.createEmptyBorder(4, 10, 4, 10)
                }
                return c
            }
        })

        nativeWidget.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                applyColumnWeightsToModel()
            }
        })
        syncSwingAppearance()
    }

    override fun syncSwingAppearance() {
        super.syncSwingAppearance()
        val isDark = SwingSurfacePaintUtil.isDarkThemeForComponent(nativeWidget)
        val colors = BootstrapColorPalette.activeThemeConfig.resolveEffectiveColors(isDark)
        val surface = colors.surface ?: if (isDark) RgbaColor(36, 39, 46) else RgbaColor(255, 255, 255)
        val headerBg = colors.background ?: if (isDark) RgbaColor(45, 49, 58) else RgbaColor(242, 244, 248)
        val fg = colors.onSurface ?: if (isDark) RgbaColor(235, 237, 242) else RgbaColor(28, 30, 36)
        val gridCol = SwingSurfacePaintUtil.blendRgb(surface, fg, if (isDark) 0.16f else 0.14f)

        table.background = Color(surface.r, surface.g, surface.b)
        table.foreground = Color(fg.r, fg.g, fg.b)
        table.gridColor = Color(gridCol.r, gridCol.g, gridCol.b)
        table.tableHeader?.let { hdr ->
            hdr.background = Color(headerBg.r, headerBg.g, headerBg.b)
            hdr.foreground = Color(fg.r, fg.g, fg.b)
        }
        nativeWidget.viewport?.background = table.background
        nativeWidget.border = BorderFactory.createLineBorder(Color(gridCol.r, gridCol.g, gridCol.b))
    }

    override fun setHeaders(headers: List<String>) {
        model.setColumnIdentifiers(headers.toTypedArray())
        applyColumnWeightsToModel()
        nativeWidget.revalidate()
        nativeWidget.repaint()
    }

    override fun setRows(rows: List<List<String>>) {
        model.rowCount = 0
        for (row in rows) {
            model.addRow(row.toTypedArray())
        }
        invalidateLayoutHierarchy()
        nativeWidget.repaint()
    }

    override fun setStriped(striped: Boolean) {
        stripedFlag = striped
        nativeWidget.putClientProperty("striped", striped)
        table.repaint()
    }

    override fun setHoverable(hoverable: Boolean) {
        hoverableFlag = hoverable
        nativeWidget.putClientProperty("hoverable", hoverable)
        if (!hoverable) hoveredRow = -1
        table.repaint()
    }

    override fun setColumnWeights(weights: FloatArray) {
        columnWeights = weights.copyOf()
        nativeWidget.putClientProperty("columnWeights", columnWeights.copyOf())
        applyColumnWeightsToModel()
    }

    fun getColumnWeights(): FloatArray = columnWeights.copyOf()

    internal fun measurePreferredTableSize(availableWidthPx: Int, availableHeightPx: Int): Size {
        val colCount = table.columnModel.columnCount.coerceAtLeast(1)
        val headerH = table.tableHeader?.preferredSize?.height?.coerceAtLeast(28) ?: 28
        val rowCount = model.rowCount
        val rowsH = (rowCount.coerceIn(1, 12) * table.rowHeight)
        val targetW = availableWidthPx.coerceAtLeast(colCount * 80)
        val targetH = (headerH + rowsH + 4).coerceAtMost(availableHeightPx.coerceAtLeast(headerH + rowsH + 4))
        return Size(targetW, targetH)
    }

    private fun applyColumnWeightsToModel() {
        val colCount = table.columnModel.columnCount
        if (colCount <= 0 || columnWeights.isEmpty()) return
        val totalWeight = (0 until colCount).sumOf { idx ->
            (columnWeights.getOrNull(idx)?.takeIf { it > 0f } ?: 1f).toDouble()
        }.toFloat().coerceAtLeast(1f)
        val viewportW = nativeWidget.viewport?.width?.takeIf { it > 0 }
            ?: table.width.takeIf { it > 0 }
            ?: (colCount * 150)
        val totalWidth = viewportW.coerceAtLeast(colCount * 40)
        for (idx in 0 until colCount) {
            val weight = columnWeights.getOrNull(idx)?.takeIf { it > 0f } ?: 1f
            val colWidth = ((weight / totalWeight) * totalWidth).toInt().coerceAtLeast(24)
            table.columnModel.getColumn(idx).preferredWidth = colWidth
        }
    }
}
