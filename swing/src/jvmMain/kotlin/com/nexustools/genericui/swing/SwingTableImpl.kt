package com.nexustools.genericui.swing

import com.nexustools.genericui.nativeimpl.content.TableNativeImpl
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.table.DefaultTableModel

/**
 * Java Swing `JTable` + `DefaultTableModel` implementation of [TableNativeImpl].
 */
class SwingTableImpl : SwingBaseContainerNativeImpl(JTable(DefaultTableModel()), "table"), TableNativeImpl<JComponent> {
    override val nativeWidget: JTable = super.nativeWidget as JTable
    private val model: DefaultTableModel = nativeWidget.model as DefaultTableModel

    override fun setHeaders(headers: List<String>) {
        model.setColumnIdentifiers(headers.toTypedArray())
    }

    override fun setRows(rows: List<List<String>>) {
        model.rowCount = 0
        for (row in rows) {
            model.addRow(row.toTypedArray())
        }
    }

    override fun setStriped(striped: Boolean) {
        nativeWidget.putClientProperty("striped", striped)
    }

    override fun setHoverable(hoverable: Boolean) {
        nativeWidget.putClientProperty("hoverable", hoverable)
    }
}
