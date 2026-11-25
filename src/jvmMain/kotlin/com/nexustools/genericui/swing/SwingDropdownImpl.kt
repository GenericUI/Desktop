package com.nexustools.genericui.swing

import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.nativeimpl.action.DropdownNativeImpl
import com.nexustools.genericui.style.color.BootstrapColorPalette
import java.awt.Dialog
import java.awt.GraphicsEnvironment
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities

/**
 * Java Swing `JButton` + `JPopupMenu` implementation of [DropdownNativeImpl] with screen-bound and
 * dialog-safe popup anchoring.
 */
class SwingDropdownImpl : SwingBaseContainerNativeImpl(
    SwingControlTintRenderUtil.createNativeOrTintedButton(),
    "dropdown"
), DropdownNativeImpl<JComponent> {
    override val nativeWidget: JButton get() = super.nativeWidget as JButton
    private val popupMenu = JPopupMenu()
    private var onItemSelectedListener: ((String) -> Unit)? = null
    private var onPrimaryActionListener: (() -> Unit)? = null
    private var lastMousePressX: Int = -1
    private var lastPressWasMouse: Boolean = false

    init {
        nativeWidget.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                lastMousePressX = e.x
                lastPressWasMouse = true
            }
        })
        nativeWidget.addActionListener {
            val primary = onPrimaryActionListener
            val isSplitCaretClick = primary != null &&
                lastPressWasMouse &&
                popupMenu.componentCount > 0 &&
                lastMousePressX >= (nativeWidget.width - 24).coerceAtLeast(nativeWidget.width * 2 / 3)
            lastPressWasMouse = false
            when {
                isSplitCaretClick -> showAnchoredPopup()
                primary != null -> primary.invoke()
                popupMenu.componentCount > 0 -> showAnchoredPopup()
            }
        }
        syncSwingAppearance()
    }

    private fun showAnchoredPopup() {
        val win = SwingUtilities.getWindowAncestor(nativeWidget)
        popupMenu.isLightWeightPopupEnabled = win !is Dialog
        val pref = popupMenu.preferredSize
        var showX = 0
        var showY = nativeWidget.height
        if (nativeWidget.isShowing && !GraphicsEnvironment.isHeadless()) {
            runCatching {
                val loc = nativeWidget.locationOnScreen
                val screenBounds = nativeWidget.graphicsConfiguration?.bounds
                    ?: GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                if (loc.y + nativeWidget.height + pref.height > screenBounds.y + screenBounds.height &&
                    loc.y - pref.height >= screenBounds.y
                ) {
                    showY = -pref.height
                }
                if (loc.x + pref.width > screenBounds.x + screenBounds.width) {
                    showX = (nativeWidget.width - pref.width).coerceAtMost(0)
                }
            }
        }
        popupMenu.show(nativeWidget, showX, showY)
    }

    override fun syncSwingAppearance() {
        super.syncSwingAppearance()
        nativeWidget.isContentAreaFilled = true
        nativeWidget.isBorderPainted = true
        nativeWidget.putClientProperty("bootstrapType", BootstrapType.NONE)
        nativeWidget.putClientProperty("bootstrapOutline", false)
        nativeWidget.putClientProperty("activeThemeConfig", BootstrapColorPalette.activeThemeConfig)
        nativeWidget.repaint()
    }

    fun renderOffscreenButtonSprite(
        width: Int = nativeWidget.width.takeIf { it > 0 } ?: 120,
        height: Int = nativeWidget.height.takeIf { it > 0 } ?: 36,
        includeTextOverlay: Boolean = true
    ): BufferedImage = SwingControlTintRenderUtil.renderButtonOffscreenSprite(
        button = nativeWidget,
        width = width,
        height = height,
        type = BootstrapType.NONE,
        outline = false,
        allowTinted = false,
        includeTextOverlay = includeTextOverlay
    )

    override fun setLabel(label: String) {
        nativeWidget.text = label
    }

    override fun setItems(items: List<Pair<String, String>>) {
        popupMenu.removeAll()
        for ((id, label) in items) {
            val item = JMenuItem(label)
            item.addActionListener {
                onItemSelectedListener?.invoke(id)
            }
            popupMenu.add(item)
        }
    }

    override fun setOnItemSelected(listener: ((String) -> Unit)?) {
        onItemSelectedListener = listener
    }

    override fun setOnPrimaryAction(listener: (() -> Unit)?) {
        onPrimaryActionListener = listener
        nativeWidget.putClientProperty("splitDropdown", listener != null)
        nativeWidget.repaint()
    }
}
