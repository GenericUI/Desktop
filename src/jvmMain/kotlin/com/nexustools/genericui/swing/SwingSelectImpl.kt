package com.nexustools.genericui.swing

import com.nexustools.genericui.nativeimpl.form.SelectNativeImpl
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.color.RgbaColor
import java.awt.Color
import java.awt.Component
import java.awt.Dialog
import javax.swing.BorderFactory
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.SwingUtilities
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

/**
 * Java Swing `JComboBox<String>` implementation of [SelectNativeImpl] with theme-contrast cell
 * rendering, scrollable popup bounds, and clean popup anchoring inside [Dialog] windows.
 */
class SwingSelectImpl : SwingBaseNativeImpl(JComboBox<String>(), "form-select"), SelectNativeImpl<JComponent> {
    @Suppress("UNCHECKED_CAST")
    override val nativeWidget: JComboBox<String> get() = super.nativeWidget as JComboBox<String>

    private var optionsList: List<Pair<String, String>> = emptyList()
    private var selectedVal: String? = null
    private var onSelectionChangedListener: ((String) -> Unit)? = null
    private var isProgrammaticUpdate: Boolean = false

    init {
        nativeWidget.maximumRowCount = 10
        nativeWidget.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): Component {
                val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                if (c is JComponent) {
                    c.border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
                }
                if (!isSelected) {
                    val isDark = SwingSurfacePaintUtil.isDarkThemeForComponent(nativeWidget)
                    val colors = BootstrapColorPalette.activeThemeConfig.resolveEffectiveColors(isDark)
                    val surface = colors.surface ?: if (isDark) RgbaColor(42, 46, 54) else RgbaColor(255, 255, 255)
                    val fg = colors.onSurface ?: if (isDark) RgbaColor(235, 238, 245) else RgbaColor(28, 30, 36)
                    c.background = Color(surface.r, surface.g, surface.b)
                    c.foreground = Color(fg.r, fg.g, fg.b)
                }
                return c
            }
        }
        nativeWidget.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent?) {
                val win = SwingUtilities.getWindowAncestor(nativeWidget)
                // Inside DialogWindow or small popups, ensure popup floats above dialog borders cleanly
                nativeWidget.isLightWeightPopupEnabled = win !is Dialog
            }

            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent?) {}

            override fun popupMenuCanceled(e: PopupMenuEvent?) {}
        })
        nativeWidget.addActionListener {
            if (!isProgrammaticUpdate) {
                val idx = nativeWidget.selectedIndex
                val newVal = optionsList.getOrNull(idx)?.first
                if (newVal != null && selectedVal != newVal) {
                    selectedVal = newVal
                    onSelectionChangedListener?.invoke(newVal)
                }
            }
        }
    }

    override fun setOptions(options: List<Pair<String, String>>) {
        isProgrammaticUpdate = true
        try {
            optionsList = options.toList()
            nativeWidget.model = DefaultComboBoxModel(optionsList.map { it.second }.toTypedArray())
            if (selectedVal == null && optionsList.isNotEmpty()) {
                selectedVal = optionsList.first().first
            }
            val idx = optionsList.indexOfFirst { it.first == selectedVal }
            if (idx >= 0) {
                nativeWidget.selectedIndex = idx
            }
        } finally {
            isProgrammaticUpdate = false
        }
    }

    override fun setSelectedValue(value: String?) {
        isProgrammaticUpdate = true
        try {
            selectedVal = value
            val idx = optionsList.indexOfFirst { it.first == value }
            if (idx >= 0) {
                nativeWidget.selectedIndex = idx
            }
        } finally {
            isProgrammaticUpdate = false
        }
    }

    override fun getSelectedValue(): String? = selectedVal

    override fun setOnSelectionChanged(listener: ((String) -> Unit)?) {
        onSelectionChangedListener = listener
    }
}
