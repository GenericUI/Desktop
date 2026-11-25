package com.nexustools.genericui.swing

import com.nexustools.genericui.component.dock.DockHeaderAction
import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.enums.bootstrap.FlexDirection
import com.nexustools.genericui.nativeimpl.dock.DockPanelNativeImpl
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.enums.css.CssDisplay
import com.nexustools.genericui.window.TitleBar
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import kotlinx.coroutines.Job

/**
 * Java Swing [DockPanelNativeImpl] with theme-aware header styling, action buttons, collapse
 * support, and flexbox multi-child [contentPanel].
 */
class SwingDockPanelImpl : SwingBaseContainerNativeImpl(
    JPanel(BorderLayout()).apply { isOpaque = false },
    "dock-panel"
), DockPanelNativeImpl<JComponent> {

    private val rootPanel: JPanel = nativeWidget as JPanel
    val headerBar: JPanel = JPanel(BorderLayout(6, 0)).apply {
        border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
    }
    private val headerLeft = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { isOpaque = false }
    private val iconLabel = JLabel().apply { isVisible = false }
    val titleLabel: JLabel = JLabel("")
    val actionsPanel: JPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply { isOpaque = false }
    val contentPanel: JPanel = SwingContainerImpl.SwingLayoutPanel()

    private val actionButtons = linkedMapOf<String, JButton>()
    private var titleText: String = ""
    private var iconSpec: String? = null
    private var collapsedFlag: Boolean = false
    private var floatingFlag: Boolean = false
    private var iconLoadJob: Job? = null
    private var onCollapsedListener: ((Boolean) -> Unit)? = null
    private var titleBarInstructionsVal: TitleBar? = null

    init {
        contentPanel.isOpaque = false
        contentPanel.layout = SwingFlexLayoutManager(
            defaultDisplay = CssDisplay.FLEX,
            defaultFlexDirection = FlexDirection.COLUMN
        )
        headerLeft.add(iconLabel)
        headerLeft.add(titleLabel)
        headerBar.add(headerLeft, BorderLayout.CENTER)
        headerBar.add(actionsPanel, BorderLayout.EAST)
        rootPanel.add(headerBar, BorderLayout.NORTH)
        rootPanel.add(contentPanel, BorderLayout.CENTER)
        syncSwingAppearance()
    }

    override fun syncSwingAppearance() {
        super.syncSwingAppearance()
        contentPanel.isOpaque = false
        val isDark = SwingSurfacePaintUtil.isDarkThemeForComponent(rootPanel)
        val colors = BootstrapColorPalette.activeThemeConfig.resolveEffectiveColors(isDark)
        val bgBase = colors.background ?: if (isDark) RgbaColor(24, 26, 32) else RgbaColor(248, 249, 252)
        val surface = colors.surface ?: if (isDark) RgbaColor(42, 46, 56) else RgbaColor(240, 242, 246)
        val panelBg = SwingSurfacePaintUtil.blendRgb(bgBase, surface, 0.55f)
        rootPanel.isOpaque = true
        rootPanel.background = Color(panelBg.r, panelBg.g, panelBg.b)
        val fgRgba = colors.onSurface ?: if (isDark) RgbaColor(235, 238, 244) else RgbaColor(28, 30, 36)
        val borderRgba = SwingSurfacePaintUtil.blendRgb(surface, fgRgba, if (isDark) 0.18f else 0.14f)
        headerBar.isOpaque = true
        headerBar.background = Color(surface.r, surface.g, surface.b)
        headerBar.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, Color(borderRgba.r, borderRgba.g, borderRgba.b)),
            BorderFactory.createEmptyBorder(4, 8, 4, 8)
        )
        titleLabel.foreground = Color(fgRgba.r, fgRgba.g, fgRgba.b)
    }

    override fun addChildNative(childImpl: NativeImpl<*>, index: Int) {
        val childComp = childImpl.nativeWidget as? JComponent ?: return
        if (childComp.parent === contentPanel) {
            contentPanel.remove(childComp)
        }
        if (index < 0 || index >= contentPanel.componentCount) {
            contentPanel.add(childComp)
        } else {
            contentPanel.add(childComp, index)
        }
        (childImpl as? SwingBaseNativeImpl)?.refreshThemeAppearance()
        SwingFlexLayoutManager.layoutTreeNow(contentPanel)
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    override fun removeChildNative(childImpl: NativeImpl<*>) {
        val childComp = childImpl.nativeWidget as? JComponent ?: return
        contentPanel.remove(childComp)
        SwingFlexLayoutManager.layoutTreeNow(contentPanel)
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
        headerBar.revalidate()
        headerBar.repaint()
    }

    override fun getTitle(): String = titleText

    override fun setIcon(icon: String?) {
        iconSpec = icon
        iconLoadJob?.cancel()
        iconLoadJob = null
        val trimmed = icon?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            iconLabel.icon = null
            iconLabel.text = ""
            iconLabel.isVisible = false
            return
        }
        if (!trimmed.contains("://") && !trimmed.contains("/") &&
            !trimmed.endsWith(".png", true) && !trimmed.endsWith(".jpg", true) && !trimmed.endsWith(".svg", true)
        ) {
            iconLabel.icon = null
            iconLabel.text = trimmed
            iconLabel.isVisible = true
            return
        }
        iconLabel.isVisible = true
        iconLoadJob = SwingControlTintRenderUtil.loadScaledIconAsync(trimmed, 16) { loaded ->
            iconLabel.icon = loaded
            headerBar.revalidate()
            headerBar.repaint()
        }
    }

    override fun setCollapsed(collapsed: Boolean) {
        collapsedFlag = collapsed
        contentPanel.isVisible = !collapsed
        onCollapsedListener?.invoke(collapsed)
        invalidateLayoutHierarchy()
        rootPanel.revalidate()
        rootPanel.repaint()
    }

    override fun isCollapsed(): Boolean = collapsedFlag

    override fun setOnCollapsedChanged(listener: ((Boolean) -> Unit)?) {
        onCollapsedListener = listener
    }

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

    override fun applyTitleBarInstructions(titleBar: TitleBar?) {
        titleBarInstructionsVal = titleBar
        titleBar?.heightPx?.takeIf { it > 0 }?.let { h ->
            headerBar.preferredSize = java.awt.Dimension(headerBar.preferredSize?.width ?: 120, h)
        }
    }

    override fun getTitleBarInstructions(): TitleBar? = titleBarInstructionsVal

    fun headerActionButton(actionId: String): JButton? = actionButtons[actionId]

    override fun dispose() {
        iconLoadJob?.cancel()
        iconLoadJob = null
        super.dispose()
    }
}
