package com.nexustools.genericui.swing

import com.nexustools.genericui.component.tabs.TabIconPlacement
import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.image.ImageDecodeOptions
import com.nexustools.genericui.image.ImageLoader
import com.nexustools.genericui.image.ImageSource
import com.nexustools.genericui.nativeimpl.tabs.TabNativeImpl
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Insets
import java.awt.image.BufferedImage
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlinx.coroutines.Job

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
    private val iconLabel: JLabel = JLabel().apply { isVisible = false }
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
    var customHeaderComponent: JComponent? = null
        private set

    private var activeIconLoadJob: Job? = null
    private var onCloseClickedListener: (() -> Unit)? = null
    private var onTitleEditedListener: ((String) -> Unit)? = null

    init {
        headerPanel.add(pinLabel)
        headerPanel.add(iconLabel)
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
        if (iconPlacementVal == TabIconPlacement.TOP && !iconVal.isNullOrBlank()) {
            headerPanel.layout = BoxLayout(headerPanel, BoxLayout.Y_AXIS)
            iconLabel.alignmentX = Component.CENTER_ALIGNMENT
            titleLabel.alignmentX = Component.CENTER_ALIGNMENT
        } else {
            headerPanel.layout = FlowLayout(FlowLayout.LEFT, 4, 0)
        }
        headerPanel.revalidate()
        headerPanel.repaint()
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
        iconLabel.isVisible = true
        syncHeaderLayout()
        val memBuf = if (rawIcon.startsWith("memory://", ignoreCase = true)) {
            ImageLoader.resolveMemoryBuffer(rawIcon)
        } else {
            null
        }
        if (memBuf != null) {
            applyIconBuffer(memBuf.width, memBuf.height) { x, y ->
                val px = memBuf.getPixel(x, y)
                ((px.a and 0xFF) shl 24) or ((px.r and 0xFF) shl 16) or ((px.g and 0xFF) shl 8) or (px.b and 0xFF)
            }
            return
        }
        activeIconLoadJob = ImageLoader.launchLoad(
            source = ImageSource.fromUrl(rawIcon),
            options = ImageDecodeOptions(
                targetWidth = iconSizePxVal,
                targetHeight = iconSizePxVal,
                antialiasing = true
            ),
            onLoaded = { buf ->
                val resized = if (buf.width != iconSizePxVal || buf.height != iconSizePxVal) {
                    ImageLoader.resize(buf, iconSizePxVal, iconSizePxVal, antialiasing = true)
                } else {
                    buf
                }
                SwingUtilities.invokeLater {
                    applyIconBuffer(resized.width, resized.height) { x, y ->
                        val px = resized.getPixel(x, y)
                        ((px.a and 0xFF) shl 24) or ((px.r and 0xFF) shl 16) or ((px.g and 0xFF) shl 8) or (px.b and 0xFF)
                    }
                }
            },
            onError = {}
        )
    }

    private inline fun applyIconBuffer(w: Int, h: Int, pixelProvider: (Int, Int) -> Int) {
        val safeW = w.coerceAtLeast(1)
        val safeH = h.coerceAtLeast(1)
        val bimg = BufferedImage(safeW, safeH, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until safeH) {
            for (x in 0 until safeW) {
                bimg.setRGB(x, y, pixelProvider(x, y))
            }
        }
        iconLabel.icon = ImageIcon(bimg)
        iconLabel.revalidate()
        headerPanel.revalidate()
        headerPanel.repaint()
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
