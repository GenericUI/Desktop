package com.nexustools.genericui.swing

import com.nexustools.genericui.component.tabs.TabIconPlacement
import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.nativeimpl.tabs.TabNativeImpl
import com.nexustools.genericui.res.Resources
import com.nexustools.genericui.style.color.RgbaColor
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Insets
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities
import kotlinx.coroutines.Job

/**
 * Java Swing implementation of [TabNativeImpl], translating `Tab`, `CloseableTab`, `BadgeTab`,
 * `PinnedTab`, `EditableTab`, dirty state, and custom `AbstractComponent` tab headers into a native
 * Swing `JPanel` tab component mounted via `JTabbedPane.setTabComponentAt(index, component)`.
 */
class SwingTabImpl(
    initialClosable: Boolean = false
) : SwingBaseContainerNativeImpl(
    JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { isOpaque = false },
    "nav-item"
), TabNativeImpl<JComponent> {

    private val headerPanel: JPanel = nativeWidget as JPanel
    private val pinLabel: JLabel = JLabel("📌").apply { isVisible = false }
    val iconLabel: JLabel = JLabel().apply { isVisible = false }
    val titleLabel: JLabel = JLabel("")
    val titleEditor: JTextField = JTextField(12).apply { isVisible = false }
    val dirtyLabel: JLabel = JLabel("•").apply { isVisible = false }
    private val badgeLabel: JLabel = JLabel("").apply {
        isVisible = false
        border = BorderFactory.createEmptyBorder(1, 5, 1, 5)
    }
    val closeButton: JButton = JButton("×").apply {
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
    var iconVal: String? = null
        private set
    var iconSizePxVal: Int = 20
        private set
    var iconPlacementVal: TabIconPlacement = TabIconPlacement.START
        private set
    var tintImageToThemeVal: Boolean = true
        private set
    var tooltipVal: String? = null
        private set
    var closableVal: Boolean = initialClosable
        private set
    var editableVal: Boolean = false
        private set
    var dirtyVal: Boolean = false
        private set
    var pinnedVal: Boolean = false
        private set
    var customHeaderComponent: JComponent? = null
        private set

    private var isEditingTitle: Boolean = false
    private var activeIconLoadJob: Job? = null
    private var onCloseClickedListener: (() -> Unit)? = null
    private var onTitleEditedListener: ((String) -> Unit)? = null

    init {
        headerPanel.add(pinLabel)
        headerPanel.add(iconLabel)
        headerPanel.add(titleLabel)
        headerPanel.add(titleEditor)
        headerPanel.add(dirtyLabel)
        headerPanel.add(badgeLabel)
        headerPanel.add(closeButton)

        closeButton.addActionListener {
            onCloseClickedListener?.invoke()
        }

        val doubleClickEditAdapter = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (editableVal && e.clickCount >= 2 && SwingUtilities.isLeftMouseButton(e)) {
                    beginTitleEdit()
                }
            }
        }
        titleLabel.addMouseListener(doubleClickEditAdapter)
        headerPanel.addMouseListener(doubleClickEditAdapter)

        titleEditor.addActionListener {
            commitTitleEdit()
        }
        titleEditor.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent?) {
                if (isEditingTitle) {
                    commitTitleEdit()
                }
            }
        })
        titleEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ESCAPE && isEditingTitle) {
                    cancelTitleEdit()
                }
            }
        })
        syncSwingAppearance()
    }

    override fun syncSwingAppearance() {
        super.syncSwingAppearance()
        val tabFg = if (customTextColor == null &&
            lastComponentStyle?.color == null &&
            lastComputedStyle?.textColor == null
        ) {
            javax.swing.UIManager.getColor("TabbedPane.foreground") ?: titleLabel.foreground
        } else {
            titleLabel.foreground
        }
        titleLabel.foreground = tabFg
        pinLabel.foreground = tabFg
        iconLabel.foreground = tabFg
        dirtyLabel.foreground = tabFg
        closeButton.foreground = tabFg
        if (!iconVal.isNullOrBlank() && tintImageToThemeVal) {
            syncSwingTabIcon()
        }
    }

    fun beginTitleEdit() {
        if (!editableVal || isEditingTitle) return
        isEditingTitle = true
        titleEditor.text = titleVal
        titleLabel.isVisible = false
        titleEditor.isVisible = true
        headerPanel.revalidate()
        headerPanel.repaint()
        titleEditor.requestFocusInWindow()
        titleEditor.selectAll()
    }

    fun commitTitleEdit() {
        if (!isEditingTitle) return
        isEditingTitle = false
        val candidate = titleEditor.text.trim()
        titleEditor.isVisible = false
        titleLabel.isVisible = customHeaderComponent == null || titleVal.isNotEmpty() || candidate.isNotEmpty()
        if (candidate.isNotEmpty() && candidate != titleVal) {
            titleVal = candidate
            titleLabel.text = candidate
            onTitleEditedListener?.invoke(candidate)
        }
        headerPanel.revalidate()
        headerPanel.repaint()
    }

    fun cancelTitleEdit() {
        if (!isEditingTitle) return
        isEditingTitle = false
        titleEditor.isVisible = false
        titleLabel.isVisible = customHeaderComponent == null || titleVal.isNotEmpty()
        headerPanel.revalidate()
        headerPanel.repaint()
    }

    override fun setTabIdNative(id: String) {
        tabIdVal = id
        headerPanel.putClientProperty("tabId", id)
    }

    override fun setTitleNative(title: String) {
        titleVal = title
        titleLabel.text = title
        if (!isEditingTitle && customHeaderComponent != null) {
            titleLabel.isVisible = title.isNotEmpty()
        }
    }

    override fun setIconNative(icon: String?) {
        iconVal = icon
        headerPanel.putClientProperty("icon", icon)
        syncSwingTabIcon()
    }

    override fun setIconSizePxNative(sizePx: Int) {
        iconSizePxVal = sizePx.coerceAtLeast(1)
        headerPanel.putClientProperty("iconSizePx", iconSizePxVal)
        syncSwingTabIcon()
    }

    override fun setIconPlacementNative(placement: TabIconPlacement) {
        iconPlacementVal = placement
        headerPanel.putClientProperty("iconPlacement", placement.name)
        syncHeaderLayout()
    }

    override fun setTintImageToThemeNative(tintToTheme: Boolean) {
        tintImageToThemeVal = tintToTheme
        headerPanel.putClientProperty("tintImageToTheme", tintToTheme)
        syncSwingTabIcon()
    }

    private fun syncHeaderLayout() {
        val hasIcon = !iconVal.isNullOrBlank()
        if (hasIcon && iconPlacementVal == TabIconPlacement.TOP) {
            headerPanel.layout = BoxLayout(headerPanel, BoxLayout.Y_AXIS)
            iconLabel.alignmentX = Component.CENTER_ALIGNMENT
            titleLabel.alignmentX = Component.CENTER_ALIGNMENT
        } else {
            headerPanel.layout = FlowLayout(FlowLayout.LEFT, 4, 0)
        }
        val iconIdx = headerPanel.components.indexOf(iconLabel)
        val titleIdx = headerPanel.components.indexOf(titleLabel)
        if (iconIdx >= 0 && titleIdx >= 0 && iconIdx > titleIdx) {
            headerPanel.setComponentZOrder(iconLabel, titleIdx)
        }
        headerPanel.revalidate()
        headerPanel.repaint()
        headerPanel.parent?.revalidate()
        headerPanel.parent?.repaint()
    }

    private fun looksLikeImageOrVectorSpec(raw: String): Boolean {
        if (raw.contains("://") || raw.contains('/') || raw.contains('\\')) return true
        if (raw.startsWith("<svg", ignoreCase = true) || raw.startsWith("data:", ignoreCase = true)) return true
        val lower = raw.substringBefore('?').substringBefore('#').lowercase()
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp") ||
            lower.endsWith(".ico") || lower.endsWith(".svg")
        ) {
            return true
        }
        if (Resources.getDrawable(raw) != null) return true
        return File(raw).isFile
    }

    private fun syncSwingTabIcon() {
        activeIconLoadJob?.cancel()
        activeIconLoadJob = null
        val rawIcon = iconVal?.trim().orEmpty()
        if (rawIcon.isEmpty()) {
            iconLabel.icon = null
            iconLabel.text = ""
            iconLabel.isVisible = false
            syncHeaderLayout()
            return
        }
        if (!looksLikeImageOrVectorSpec(rawIcon)) {
            iconLabel.icon = null
            iconLabel.text = rawIcon
            iconLabel.isVisible = true
            syncHeaderLayout()
            return
        }
        iconLabel.text = ""
        iconLabel.isVisible = true
        syncHeaderLayout()
        val fg = titleLabel.foreground
        val tintRgba = RgbaColor(fg.red, fg.green, fg.blue, fg.alpha)
        val isDark = SwingControlTintRenderUtil.resolveIsDark(closeButton)
        activeIconLoadJob = SwingControlTintRenderUtil.loadScaledIconAsync(
            iconSpec = rawIcon,
            targetSizePx = iconSizePxVal,
            tintToTheme = tintImageToThemeVal,
            tintColor = tintRgba,
            isDarkTheme = isDark
        ) { icon ->
            iconLabel.icon = icon
            iconLabel.revalidate()
            headerPanel.revalidate()
            headerPanel.repaint()
            headerPanel.parent?.revalidate()
            headerPanel.parent?.repaint()
        }
    }

    override fun setTooltipNative(tooltip: String?) {
        tooltipVal = tooltip
        headerPanel.toolTipText = tooltip
        titleLabel.toolTipText = tooltip
    }

    override fun setClosableNative(closable: Boolean) {
        closableVal = closable
        closeButton.isVisible = closable && !pinnedVal
        headerPanel.revalidate()
        headerPanel.repaint()
        headerPanel.parent?.revalidate()
        headerPanel.parent?.repaint()
    }

    override fun setDirtyNative(dirty: Boolean) {
        dirtyVal = dirty
        dirtyLabel.isVisible = dirty
        headerPanel.putClientProperty("dirty", dirty)
        headerPanel.revalidate()
        headerPanel.repaint()
    }

    override fun setPinnedNative(pinned: Boolean) {
        pinnedVal = pinned
        pinLabel.isVisible = pinned
        closeButton.isVisible = closableVal && !pinned
        headerPanel.revalidate()
        headerPanel.repaint()
        headerPanel.parent?.revalidate()
        headerPanel.parent?.repaint()
    }

    override fun setEditableNative(editable: Boolean) {
        editableVal = editable
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

    override fun dispose() {
        activeIconLoadJob?.cancel()
        activeIconLoadJob = null
        super.dispose()
    }
}
