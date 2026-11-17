package com.nexustools.genericui.swing

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.nativeimpl.navigation.NavbarNativeImpl
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JToolBar
import kotlinx.coroutines.Job

/**
 * Java Swing `JToolBar` implementation of [NavbarNativeImpl] rendering a real brand icon and
 * title [JLabel] header when [isBrandVisible] is enabled alongside toolbar navigation children.
 */
class SwingNavbarImpl : SwingBaseContainerNativeImpl(
    JToolBar().apply { isFloatable = false },
    "navbar"
), NavbarNativeImpl<JComponent> {

    private val toolBar: JToolBar = nativeWidget as JToolBar
    private val brandPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
        isOpaque = false
        isVisible = false
        border = BorderFactory.createEmptyBorder(0, 4, 0, 8)
    }
    private val brandIconLabel = JLabel().apply { isVisible = false }
    private val brandTitleLabel = JLabel().apply { isVisible = false }

    private var brandTitleText: String = ""
    private var brandIconSpec: String? = null
    private var showBrandOverride: Boolean? = null
    private var brandIconJob: Job? = null

    init {
        brandPanel.add(brandIconLabel)
        brandPanel.add(brandTitleLabel)
        toolBar.add(brandPanel)
    }

    override fun setBrandTitle(title: String) {
        brandTitleText = title
        syncBrandHeader()
    }

    override fun getBrandTitle(): String = brandTitleText

    override fun setBrandIcon(icon: String?) {
        brandIconSpec = icon
        syncBrandHeader()
        loadBrandIconAsync(icon)
    }

    override fun getBrandIcon(): String? = brandIconSpec

    override fun setShowBrand(show: Boolean?) {
        showBrandOverride = show
        syncBrandHeader()
    }

    override fun getShowBrand(): Boolean? = showBrandOverride

    override fun isBrandVisible(): Boolean = showBrandOverride ?: false

    override fun addChildNative(childImpl: NativeImpl<*>, index: Int) {
        val childComp = childImpl.nativeWidget as? JComponent ?: return
        val adjustedIndex = if (index < 0) -1 else (index + 1).coerceAtMost(toolBar.componentCount)
        if (adjustedIndex < 0 || adjustedIndex >= toolBar.componentCount) {
            toolBar.add(childComp)
        } else {
            toolBar.add(childComp, adjustedIndex)
        }
        toolBar.revalidate()
        toolBar.repaint()
    }

    override fun clearChildrenNative() {
        toolBar.removeAll()
        toolBar.add(brandPanel)
        toolBar.revalidate()
        toolBar.repaint()
    }

    private fun syncBrandHeader() {
        val visible = isBrandVisible()
        nativeWidget.name = if (visible) brandTitleText else ""
        brandTitleLabel.text = brandTitleText
        brandTitleLabel.isVisible = visible && brandTitleText.isNotEmpty()
        brandIconLabel.isVisible = visible && !brandIconSpec.isNullOrBlank()
        brandPanel.isVisible = visible && (brandTitleLabel.isVisible || brandIconLabel.isVisible)
        toolBar.revalidate()
        toolBar.repaint()
    }

    private fun loadBrandIconAsync(icon: String?) {
        brandIconJob?.cancel()
        brandIconJob = null
        val trimmed = icon?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            brandIconLabel.icon = null
            brandIconLabel.text = ""
            syncBrandHeader()
            return
        }
        if (!trimmed.contains("://") && !trimmed.contains("/") && !trimmed.endsWith(".png", true) &&
            !trimmed.endsWith(".jpg", true) && !trimmed.endsWith(".svg", true)
        ) {
            brandIconLabel.icon = null
            brandIconLabel.text = trimmed
            syncBrandHeader()
            return
        }
        brandIconJob = SwingControlTintRenderUtil.loadScaledIconAsync(trimmed, 20) { loaded ->
            brandIconLabel.text = ""
            brandIconLabel.icon = loaded
            syncBrandHeader()
        }
    }

    override fun dispose() {
        brandIconJob?.cancel()
        brandIconJob = null
        super.dispose()
    }
}
