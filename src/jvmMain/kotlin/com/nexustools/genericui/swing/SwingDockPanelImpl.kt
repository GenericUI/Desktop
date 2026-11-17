package com.nexustools.genericui.swing

import com.nexustools.genericui.component.dock.DockHeaderAction
import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.nativeimpl.dock.DockPanelNativeImpl
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import kotlinx.coroutines.Job

/**
 * Java Swing implementation of [DockPanelNativeImpl] featuring a header title/icon bar, clickable
 * [DockHeaderAction] toolbar buttons, collapsible content container, and floating state tracking.
 */
class SwingDockPanelImpl : SwingBaseContainerNativeImpl(
    JPanel(BorderLayout()),
    "dock-panel"
), DockPanelNativeImpl<JComponent> {

    private val rootPanel: JPanel = nativeWidget as JPanel
    val headerBar: JPanel = JPanel(BorderLayout(6, 0)).apply {
        border = BorderFactory.createEmptyBorder(4, 6, 4, 6)
    }
    private val headerLeft = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { isOpaque = false }
    private val iconLabel = JLabel().apply { isVisible = false }
    val titleLabel: JLabel = JLabel("")
    val actionsPanel: JPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply { isOpaque = false }
    val contentPanel: JPanel = JPanel(BorderLayout()).apply { isOpaque = false }

    private val actionButtons = linkedMapOf<String, JButton>()
    private var titleText: String = ""
    private var iconSpec: String? = null
    private var collapsedFlag: Boolean = false
    private var floatingFlag: Boolean = false
    private var iconLoadJob: Job? = null

    init {
        headerLeft.add(iconLabel)
        headerLeft.add(titleLabel)
        headerBar.add(headerLeft, BorderLayout.CENTER)
        headerBar.add(actionsPanel, BorderLayout.EAST)
        rootPanel.add(headerBar, BorderLayout.NORTH)
        rootPanel.add(contentPanel, BorderLayout.CENTER)
    }

    override fun addChildNative(childImpl: NativeImpl<*>, index: Int) {
        val childComp = childImpl.nativeWidget as? JComponent ?: return
        if (index < 0 || index >= contentPanel.componentCount) {
            contentPanel.add(childComp, BorderLayout.CENTER)
        } else {
            contentPanel.add(childComp, index)
        }
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    override fun removeChildNative(childImpl: NativeImpl<*>) {
        val childComp = childImpl.nativeWidget as? JComponent ?: return
        contentPanel.remove(childComp)
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    override fun clearChildrenNative() {
        contentPanel.removeAll()
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    override fun setTitle(title: String) {
        titleText = title
        titleLabel.text = title
        nativeWidget.putClientProperty("title", title)
    }

    override fun getTitle(): String = titleText

    override fun setIcon(icon: String?) {
        iconSpec = icon
        nativeWidget.putClientProperty("icon", icon)
        iconLoadJob?.cancel()
        iconLoadJob = null
        val trimmed = icon?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            iconLabel.icon = null
            iconLabel.text = ""
            iconLabel.isVisible = false
            return
        }
        iconLabel.isVisible = true
        if (!trimmed.contains("://") && !trimmed.contains("/") && !trimmed.endsWith(".png", true) &&
            !trimmed.endsWith(".jpg", true) && !trimmed.endsWith(".svg", true)
        ) {
            iconLabel.icon = null
            iconLabel.text = trimmed
            return
        }
        iconLoadJob = SwingControlTintRenderUtil.loadScaledIconAsync(trimmed, 16) { loaded ->
            iconLabel.text = ""
            iconLabel.icon = loaded
            headerBar.revalidate()
            headerBar.repaint()
        }
    }

    override fun setCollapsed(collapsed: Boolean) {
        collapsedFlag = collapsed
        contentPanel.isVisible = !collapsed
        rootPanel.revalidate()
        rootPanel.repaint()
    }

    override fun isCollapsed(): Boolean = collapsedFlag

    override fun setFloating(floating: Boolean) {
        floatingFlag = floating
        nativeWidget.putClientProperty("floating", floating)
    }

    override fun isFloating(): Boolean = floatingFlag

    override fun setHeaderActions(actions: List<DockHeaderAction>) {
        actionsPanel.removeAll()
        actionButtons.clear()
        nativeWidget.putClientProperty("headerActionCount", actions.size)
        for (action in actions) {
            val btn = JButton(action.label.ifEmpty { action.id }).apply {
                margin = Insets(1, 5, 1, 5)
                isFocusable = false
                action.tooltip?.takeIf { it.isNotEmpty() }?.let { toolTipText = it }
                addActionListener { action.onClick() }
            }
            action.iconSvgOrPath?.let { iconPath ->
                if (iconPath.contains("://") || iconPath.contains("/")) {
                    SwingControlTintRenderUtil.loadScaledIconAsync(iconPath, 14) { loaded ->
                        btn.icon = loaded
                    }
                }
            }
            actionButtons[action.id] = btn
            actionsPanel.add(btn)
        }
        actionsPanel.revalidate()
        actionsPanel.repaint()
    }

    fun headerActionButton(actionId: String): JButton? = actionButtons[actionId]

    override fun dispose() {
        iconLoadJob?.cancel()
        iconLoadJob = null
        super.dispose()
    }
}
