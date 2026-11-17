package com.nexustools.genericui.desktop.widget

import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.nativeimpl.content.TableNativeImpl

/**
 * Shared desktop base implementation of [TableNativeImpl].
 */
open class DesktopTableNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W
) : DesktopBaseContainerNativeImpl<W>(nativeWidget, "table"), TableNativeImpl<W> {

    protected var headersList: List<String> = emptyList()
    protected var rowsList: List<List<String>> = emptyList()
    protected var stripedState: Boolean = false
    protected var hoverableState: Boolean = false

    override fun setHeaders(headers: List<String>) {
        headersList = headers
        nativeWidget.attributes["headers"] = headers
    }

    override fun setRows(rows: List<List<String>>) {
        rowsList = rows
        nativeWidget.attributes["rows"] = rows
    }

    override fun setStriped(striped: Boolean) {
        stripedState = striped
        nativeWidget.attributes["striped"] = striped
    }

    override fun setHoverable(hoverable: Boolean) {
        hoverableState = hoverable
        nativeWidget.attributes["hoverable"] = hoverable
    }
}
