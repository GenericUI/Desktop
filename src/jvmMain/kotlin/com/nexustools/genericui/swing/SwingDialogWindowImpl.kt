package com.nexustools.genericui.swing

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.enums.bootstrap.FlexDirection
import com.nexustools.genericui.enums.dialog.DialogPresentationStyle
import com.nexustools.genericui.geometry.EdgeInsets
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.nativeimpl.dialog.DialogWindowNativeImpl
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl
import com.nexustools.genericui.enums.css.CssDisplay
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dialog
import java.awt.Dimension
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.WindowConstants

/**
 * Java Swing `JDialog` implementation of [DialogWindowNativeImpl].
 */
class SwingDialogWindowImpl : SwingBaseContainerNativeImpl(JPanel(BorderLayout()), "dialogwindow"), DialogWindowNativeImpl<JComponent> {
    private val bodyLayout = SwingFlexLayoutManager(
        defaultDisplay = CssDisplay.FLEX,
        defaultFlexDirection = FlexDirection.COLUMN,
        defaultGapPx = 10,
        defaultPadding = EdgeInsets(16, 18, 16, 18)
    )
    private val bodyPanel: BodyScrollPanel = BodyScrollPanel()
    private val bodyScrollPane = JScrollPane(bodyPanel).apply {
        isOpaque = false
        viewport?.isOpaque = false
        border = BorderFactory.createEmptyBorder()
        viewportBorder = null
        verticalScrollBar.unitIncrement = 16
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
    }
    private var footerComponent: JComponent? = null
    private var titleText: String = ""
    private var styleVal: DialogPresentationStyle = DialogPresentationStyle.MODAL_CENTERED
    private var modalFlag: Boolean = true
    private var fullscreenFlag: Boolean = false
    private var hasExplicitBounds: Boolean = false
    private var onCloseListener: (() -> Unit)? = null

    var ownerWindow: WindowNativeImpl<*>? = null
        private set

    var zOrderLayer: Int = 0
        private set

    var jDialog: JDialog? = null
        private set

    private val fallbackRootPane = javax.swing.JRootPane()

    private fun setClientPropOnWidgetAndRoot(key: String, value: Any?) {
        nativeWidget.putClientProperty(key, value)
        fallbackRootPane.putClientProperty(key, value)
        nativeWidget.rootPane?.putClientProperty(key, value)
        jDialog?.rootPane?.putClientProperty(key, value)
    }

    init {
        setVisible(false)
        bodyPanel.layout = bodyLayout
        bodyPanel.isOpaque = false
        nativeWidget.add(bodyScrollPane, BorderLayout.CENTER)
        fallbackRootPane.contentPane.add(nativeWidget, BorderLayout.CENTER)
        nativeWidget.setBounds(120, 120, 560, 400)
        setClientPropOnWidgetAndRoot("isSeparateNativeWindow", true)
        setClientPropOnWidgetAndRoot("inWindowOverlay", false)
        setClientPropOnWidgetAndRoot("minimizable", false)
        setClientPropOnWidgetAndRoot("maximizable", false)
        setClientPropOnWidgetAndRoot("closable", true)
        setClientPropOnWidgetAndRoot("modal", modalFlag)
        setClientPropOnWidgetAndRoot("presentationStyle", styleVal)
        syncSwingAppearance()
    }

    override fun syncSwingAppearance() {
        super.syncSwingAppearance()
        footerComponent?.let { fc ->
            val isDark = SwingSurfacePaintUtil.isDarkThemeForComponent(nativeWidget)
            val borderColor = if (isDark) Color(66, 72, 86) else Color(214, 220, 228)
            fc.border = BorderFactory.createMatteBorder(1, 0, 0, 0, borderColor)
        }
    }

    override fun addChildNative(childImpl: NativeImpl<*>, index: Int) {
        val childComp = childImpl.nativeWidget as? JComponent ?: return
        val clientClass = childComp.getClientProperty("class") as? String
        val isFooter = childImpl.hasClass("modal-footer") ||
            childImpl.getClasses().contains("modal-footer") ||
            (clientClass != null && clientClass.contains("modal-footer"))
        if (isFooter) {
            childComp.putClientProperty("class", childImpl.getClass().ifEmpty { "modal-footer" })
            childComp.putClientProperty("dialogSlot", "footer")
            footerComponent = childComp
            val isDark = SwingSurfacePaintUtil.isDarkThemeForComponent(nativeWidget)
            val borderColor = if (isDark) Color(66, 72, 86) else Color(214, 220, 228)
            childComp.border = BorderFactory.createMatteBorder(1, 0, 0, 0, borderColor)
            nativeWidget.putClientProperty("hasFooterButtonBar", true)
            nativeWidget.add(childComp, BorderLayout.SOUTH)
        } else {
            childComp.putClientProperty("dialogSlot", "body")
            if (childComp.parent === bodyPanel) {
                bodyPanel.remove(childComp)
            }
            if (index < 0 || index >= bodyPanel.componentCount) {
                bodyPanel.add(childComp)
            } else {
                bodyPanel.add(childComp, index)
            }
        }
        (childImpl as? SwingBaseNativeImpl)?.refreshThemeAppearance()
        SwingFlexLayoutManager.layoutTreeNow(nativeWidget)
        nativeWidget.revalidate()
        nativeWidget.repaint()
    }

    override fun removeChildNative(childImpl: NativeImpl<*>) {
        val childComp = childImpl.nativeWidget as? JComponent ?: return
        if (footerComponent === childComp) {
            footerComponent = null
            nativeWidget.putClientProperty("hasFooterButtonBar", false)
        }
        bodyPanel.remove(childComp)
        nativeWidget.remove(childComp)
        SwingFlexLayoutManager.layoutTreeNow(nativeWidget)
        nativeWidget.revalidate()
        nativeWidget.repaint()
    }

    override fun clearChildrenNative() {
        footerComponent = null
        bodyPanel.removeAll()
        nativeWidget.removeAll()
        nativeWidget.add(bodyScrollPane, BorderLayout.CENTER)
        nativeWidget.putClientProperty("hasFooterButtonBar", false)
        SwingFlexLayoutManager.layoutTreeNow(nativeWidget)
        nativeWidget.revalidate()
        nativeWidget.repaint()
    }

    override fun setTitle(title: String) {
        titleText = title
        nativeWidget.putClientProperty("title", title)
        jDialog?.title = title
    }

    override fun getTitle(): String = titleText

    override fun setPresentationStyle(style: DialogPresentationStyle) {
        styleVal = style
        nativeWidget.putClientProperty("presentationStyle", style)
    }

    override fun getPresentationStyle(): DialogPresentationStyle = styleVal

    override fun setModal(modal: Boolean) {
        modalFlag = modal
        nativeWidget.putClientProperty("modal", modal)
    }

    override fun setFullscreen(fullscreen: Boolean) {
        fullscreenFlag = fullscreen
        nativeWidget.putClientProperty("fullscreen", fullscreen)
    }

    override fun setPreferredSize(size: Size?) {
        super.setPreferredSize(size)
        if (size == null) {
            hasExplicitBounds = false
            nativeWidget.preferredSize = null
            setClientPropOnWidgetAndRoot("hasExplicitBounds", false)
            jDialog?.pack()
        } else {
            hasExplicitBounds = true
            setClientPropOnWidgetAndRoot("hasExplicitBounds", true)
            val w = size.width.takeIf { it > 0 } ?: nativeWidget.preferredSize?.width?.coerceAtLeast(220) ?: 420
            val h = size.height.takeIf { it > 0 } ?: nativeWidget.preferredSize?.height?.coerceAtLeast(140) ?: 300
            nativeWidget.setSize(w, h)
            SwingFlexLayoutManager.layoutTreeNow(nativeWidget)
            if (bodyPanel.width <= 0 || bodyPanel.height <= 0) {
                bodyPanel.setSize(w, h)
                SwingFlexLayoutManager.layoutTreeNow(bodyPanel)
            }
            jDialog?.setSize(w.coerceAtLeast(220), h.coerceAtLeast(140))
        }
    }

    override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
        hasExplicitBounds = true
        setClientPropOnWidgetAndRoot("hasExplicitBounds", true)
        super.setBounds(x, y, width, height)
        SwingFlexLayoutManager.layoutTreeNow(nativeWidget)
        if (bodyPanel.width <= 0 || bodyPanel.height <= 0) {
            bodyPanel.setSize(width.coerceAtLeast(1), height.coerceAtLeast(1))
            SwingFlexLayoutManager.layoutTreeNow(bodyPanel)
        }
        jDialog?.setBounds(x, y, width, height)
    }

    fun bringToFront() {
        zOrderLayer = SwingWindowImpl.nextZOrderSequence()
        nativeWidget.putClientProperty("zOrderLayer", zOrderLayer)
        nativeWidget.putClientProperty("broughtToFront", true)
        if (jDialog?.isVisible == true) {
            jDialog?.toFront()
        }
    }

    override fun showDialog(ownerWindow: WindowNativeImpl<*>?) {
        (this.ownerWindow as? SwingWindowImpl)?.unregisterOwnedDialogWindow(this)
        this.ownerWindow = ownerWindow
        val swingOwner = ownerWindow as? SwingWindowImpl
        swingOwner?.registerOwnedDialogWindow(this)
        setClientPropOnWidgetAndRoot("ownerWindow", ownerWindow)
        setClientPropOnWidgetAndRoot("attachedToMainWindow", ownerWindow != null)
        setClientPropOnWidgetAndRoot("transientFor", swingOwner?.nativeWidget)
        setVisible(true)
        if (!GraphicsEnvironment.isHeadless()) {
            val hasVisibleOwner = swingOwner != null && swingOwner.isVisible() && swingOwner.jFrame != null
            val visibleOwnerFrame: Frame? = if (hasVisibleOwner) swingOwner?.jFrame else null
            val existing = jDialog
            val dialog = if (existing != null && existing.owner === visibleOwnerFrame) {
                existing
            } else {
                existing?.dispose()
                JDialog(visibleOwnerFrame, titleText, Dialog.ModalityType.MODELESS).also { created ->
                    created.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
                    created.addWindowListener(object : WindowAdapter() {
                        override fun windowClosing(e: WindowEvent?) {
                            closeDialog()
                        }
                    })
                    created.contentPane.add(nativeWidget, BorderLayout.CENTER)
                    jDialog = created
                }
            }
            setClientPropOnWidgetAndRoot("isSeparateNativeWindow", true)
            setClientPropOnWidgetAndRoot("inWindowOverlay", false)
            setClientPropOnWidgetAndRoot("minimizable", false)
            setClientPropOnWidgetAndRoot("maximizable", false)
            setClientPropOnWidgetAndRoot("closable", true)
            setClientPropOnWidgetAndRoot("modal", modalFlag)
            setClientPropOnWidgetAndRoot("presentationStyle", styleVal)
            setClientPropOnWidgetAndRoot("ownerWindow", ownerWindow)
            setClientPropOnWidgetAndRoot("attachedToMainWindow", ownerWindow != null)
            setClientPropOnWidgetAndRoot("transientFor", swingOwner?.nativeWidget)
            dialog.title = titleText
            if (hasExplicitBounds) {
                val b = getBounds()
                dialog.setBounds(
                    b.x,
                    b.y,
                    b.width.coerceAtLeast(220),
                    b.height.coerceAtLeast(140)
                )
            } else if (SwingApplicationImpl.isInteractiveSwingDisplayEnabled) {
                nativeWidget.preferredSize = null
                dialog.pack()
                val screenBounds = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                val maxW = screenBounds.width - 48
                val maxH = screenBounds.height - 64
                val packedW = dialog.width.coerceIn(280, maxW.coerceAtLeast(320))
                val packedH = dialog.height.coerceIn(160, maxH.coerceAtLeast(240))
                dialog.setSize(packedW, packedH)
                dialog.setLocationRelativeTo(visibleOwnerFrame)
                nativeWidget.setBounds(0, 0, packedW, packedH)
            } else {
                val pref = bodyPanel.preferredSize
                val w = (pref.width + 24).coerceIn(280, 800)
                val h = (pref.height + 48).coerceIn(160, 600)
                dialog.setSize(w, h)
                nativeWidget.setBounds(0, 0, w, h)
            }
            SwingFlexLayoutManager.layoutTreeNow(nativeWidget)
            if (SwingApplicationImpl.isInteractiveSwingDisplayEnabled) {
                dialog.isVisible = true
            }
        } else {
            SwingFlexLayoutManager.layoutTreeNow(nativeWidget)
        }
        bringToFront()
    }

    override fun closeDialog() {
        val wasVisible = isVisible()
        setVisible(false)
        (ownerWindow as? SwingWindowImpl)?.unregisterOwnedDialogWindow(this)
        val current = jDialog
        if (current != null) {
            jDialog = null
            current.isVisible = false
            current.dispose()
        }
        if (wasVisible) {
            onCloseListener?.invoke()
        }
    }

    override fun setOnDialogClosed(listener: (() -> Unit)?) {
        onCloseListener = listener
    }

    override fun dispose() {
        (ownerWindow as? SwingWindowImpl)?.unregisterOwnedDialogWindow(this)
        val current = jDialog
        if (current != null) {
            jDialog = null
            current.isVisible = false
            current.dispose()
        }
        super.dispose()
    }

    private inner class BodyScrollPanel : SwingContainerImpl.SwingLayoutPanel(), Scrollable {
        override fun getPreferredSize(): Dimension {
            val viewW = bodyScrollPane.viewport?.width?.takeIf { it > 0 }
                ?: getBounds().width.takeIf { it > 0 }
                ?: 480
            return bodyLayout.measurePreferredSize(
                parent = this,
                availableWidthPx = viewW,
                availableHeightPx = 0,
                shrinkWrapWidth = false
            )
        }

        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

        override fun getScrollableUnitIncrement(visibleRect: Rectangle?, orientation: Int, direction: Int): Int = 16

        override fun getScrollableBlockIncrement(visibleRect: Rectangle?, orientation: Int, direction: Int): Int =
            if (orientation == SwingConstants.VERTICAL) {
                ((visibleRect?.height ?: 120) - 16).coerceAtLeast(32)
            } else {
                ((visibleRect?.width ?: 120) - 16).coerceAtLeast(32)
            }

        override fun getScrollableTracksViewportWidth(): Boolean = true

        override fun getScrollableTracksViewportHeight(): Boolean = false
    }
}
