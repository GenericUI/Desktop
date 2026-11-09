package com.nexustools.genericui.swing

import com.nexustools.genericui.nativeimpl.content.TableNativeImpl
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.table.DefaultTableModel

/**
 * Java Swing `JTable` + `DefaultTableModel` implementation of [TableNativeImpl].
 */
class SwingTableImpl : SwingBaseContainerNativeImpl(JTable(DefaultTableModel()), "table"), TableNativeImpl<JComponent> {
    override val nativeWidget: JTable get() = super.nativeWidget as JTable
    private val model: DefaultTableModel = nativeWidget.model as DefaultTableModel
    private var columnWeights: FloatArray = FloatArray(0)

    override fun setHeaders(headers: List<String>) {
        model.setColumnIdentifiers(headers.toTypedArray())
        applyColumnWeightsToModel()
    }

    override fun setRows(rows: List<List<String>>) {
        model.rowCount = 0
        for (row in rows) {
            model.addRow(row.toTypedArray())
        }
        applyColumnWeightsToModel()
    }

    override fun setStriped(striped: Boolean) {
        nativeWidget.putClientProperty("striped", striped)
    }

    override fun setHoverable(hoverable: Boolean) {
        nativeWidget.putClientProperty("hoverable", hoverable)
    }

    override fun setColumnWeights(weights: FloatArray) {
        columnWeights = weights.copyOf()
        nativeWidget.putClientProperty("columnWeights", columnWeights.toList())
        applyColumnWeightsToModel()
    }

    fun getColumnWeights(): FloatArray = columnWeights.copyOf()

    private fun applyColumnWeightsToModel() {
        val colCount = nativeWidget.columnModel.columnCount
        if (colCount <= 0 || columnWeights.isEmpty()) return
        val totalWeight = (0 until colCount).sumOf { idx ->
            (columnWeights.getOrNull(idx)?.takeIf { it > 0f } ?: 1f).toDouble()
        }.toFloat().coerceAtLeast(0.001f)
        val totalWidth = (nativeWidget.width.takeIf { it > 0 } ?: (colCount * 150)).coerceAtLeast(colCount * 40)
        for (idx in 0 until colCount) {
            val weight = columnWeights.getOrNull(idx)?.takeIf { it > 0f } ?: 1f
            val colWidth = ((weight / totalWeight) * totalWidth).toInt().coerceAtLeast(24)
            nativeWidget.columnModel.getColumn(idx).preferredWidth = colWidth
        }
    }
}
