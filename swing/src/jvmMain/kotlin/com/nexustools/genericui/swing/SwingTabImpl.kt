package com.nexustools.genericui.swing

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.nativeimpl.tabs.TabNativeImpl
import java.awt.FlowLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Java Swing implementation of [TabNativeImpl], translating `Tab`, `CloseableTab`, `BadgeTab`,
 * `PinnedTab`, `EditableTab`, and custom `AbstractComponent` tab headers into a native Swing
 * `JPanel` tab component mounted via `JTabbedPane.setTabComponentAt(index, component)`.
 */
class SwingTabImpl(
    initialClosable: Boolean = false
) : SwingBaseContainerNativeImpl(
    JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { isOpaque = false },
    "nav-item"
), TabNativeImpl<JComponent> {

    private val headerPanel: JPanel = nativeWidget as JPanel
    private val pinLabel: JLabel = JLabel("📌").apply { isVisible = false }
    private val titleLabel: JLabel = JLabel("")
    private val badgeLabel: JLabel = JLabel("").apply {
        isVisible = false
        border = BorderFactory.createEmptyBorder(1, 5, 1, 5)
    }
    private val closeButton: JButton = JButton("×").apply {
        margin = Insets(0, 4, 0, 4)
        border = BorderFactory.createEmptyBorder(0, 4, 0, 2)
        isContentAreaFilled = false
        isFocusable = false
        isVisible = initialClosable
    }

    var tabIdVal: String = ""
        private set
    var titleVal: String = ""
        private set
    var tooltipVal: String? = null
        private set
    var closableVal: Boolean = initialClosable
        private set
    var customHeaderComponent: JComponent? = null
        private set

    private var onCloseClickedListener: (() -> Unit)? = null
    private var onTitleEditedListener: ((String) -> Unit)? = null

    init {
        headerPanel.add(pinLabel)
        headerPanel.add(titleLabel)
        headerPanel.add(badgeLabel)
        headerPanel.add(closeButton)
        closeButton.addActionListener {
            onCloseClickedListener?.invoke()
        }
    }

    override fun setTabIdNative(id: String) {
        tabIdVal = id
        headerPanel.putClientProperty("tabId", id)
    }

    override fun setTitleNative(title: String) {
        titleVal = title
        titleLabel.text = title
    }

    override fun setIconNative(icon: String?) {
        headerPanel.putClientProperty("icon", icon)
    }

    override fun setTooltipNative(tooltip: String?) {
        tooltipVal = tooltip
        headerPanel.toolTipText = tooltip
        titleLabel.toolTipText = tooltip
    }

    override fun setClosableNative(closable: Boolean) {
        closableVal = closable
        closeButton.isVisible = closable
        headerPanel.revalidate()
    }

    override fun setDirtyNative(dirty: Boolean) {
        headerPanel.putClientProperty("dirty", dirty)
    }

    override fun setPinnedNative(pinned: Boolean) {
        pinLabel.isVisible = pinned
        headerPanel.revalidate()
    }

    override fun setEditableNative(editable: Boolean) {
        headerPanel.putClientProperty("editable", editable)
    }

    override fun setBadgeNative(badgeText: String?, variantCss: String?) {
        if (!badgeText.isNullOrEmpty()) {
            badgeLabel.text = badgeText
            badgeLabel.isVisible = true
        } else {
            badgeLabel.text = ""
            badgeLabel.isVisible = false
        }
        headerPanel.revalidate()
    }

    override fun setSelectedNative(selected: Boolean) {
        headerPanel.putClientProperty("selected", selected)
    }

    override fun setCustomHeaderComponentNative(customImpl: NativeImpl<*>?) {
        customHeaderComponent?.let { headerPanel.remove(it) }
        val customComp = customImpl?.nativeWidget as? JComponent
        customHeaderComponent = customComp
        if (customComp != null) {
            titleLabel.isVisible = titleVal.isNotEmpty()
            headerPanel.add(customComp, 1)
        } else {
            titleLabel.isVisible = true
        }
        headerPanel.revalidate()
        headerPanel.repaint()
    }

    override fun setOnCloseClicked(listener: (() -> Unit)?) {
        onCloseClickedListener = listener
    }

    override fun setOnTitleEdited(listener: ((String) -> Unit)?) {
        onTitleEditedListener = listener
    }
}
