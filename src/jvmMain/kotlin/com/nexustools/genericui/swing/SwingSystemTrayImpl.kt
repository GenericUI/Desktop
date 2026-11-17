package com.nexustools.genericui.swing

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.jvm.JvmImageSourceResolver
import com.nexustools.genericui.nativeimpl.layout.DividerNativeImpl
import com.nexustools.genericui.nativeimpl.shell.ContextMenuNativeImpl
import com.nexustools.genericui.nativeimpl.system.SystemTrayNativeImpl
import java.awt.CheckboxMenuItem
import java.awt.Color
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.Menu
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * Java AWT `java.awt.SystemTray` + `java.awt.TrayIcon` implementation of [SystemTrayNativeImpl].
 *
 * When headless or when `SystemTray.isSupported()` is `false`, [isSupported] returns `false` and
 * tray mutation methods throw [UnsupportedOperationException] rather than no-oping silently.
 */
class SwingSystemTrayImpl : SystemTrayNativeImpl {

    override val isSupported: Boolean
        get() = checkSystemTraySupported()

    override var isDisposed: Boolean = false
        private set

    private var trayIcon: TrayIcon? = null
    private var baseImage: Image = createDefaultTrayImage()
    private var iconPath: String? = null
    private var tooltipText: String? = null
    private var badgeTextVal: String? = null
    private var contextMenuImpl: ContextMenuNativeImpl<*>? = null
    private var clickedListener: (() -> Unit)? = null

    private fun requireTray(): TrayIcon {
        check(!isDisposed) { "SwingSystemTrayImpl has been disposed." }
        if (!isSupported) {
            throw UnsupportedOperationException(
                "java.awt.SystemTray is not supported on this platform or headless environment."
            )
        }
        trayIcon?.let { return it }
        val icon = TrayIcon(renderImageWithBadge(baseImage, badgeTextVal), tooltipText).apply {
            isImageAutoSize = true
            addActionListener { clickedListener?.invoke() }
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON1 && e.clickCount == 1) {
                        clickedListener?.invoke()
                    }
                }
            })
        }
        SystemTray.getSystemTray().add(icon)
        trayIcon = icon
        activeTrayIcons.add(icon)
        contextMenuImpl?.let { icon.popupMenu = buildAwtPopupMenu(it) }
        return icon
    }

    override fun setIcon(iconPathOrUrl: String?) {
        iconPath = iconPathOrUrl
        val icon = requireTray()
        baseImage = loadTrayImage(iconPathOrUrl) ?: createDefaultTrayImage()
        icon.image = renderImageWithBadge(baseImage, badgeTextVal)
    }

    override fun setTooltip(tooltip: String?) {
        tooltipText = tooltip
        val icon = requireTray()
        icon.toolTip = tooltip
    }

    override fun setBadgeText(badge: String?) {
        badgeTextVal = badge
        val icon = requireTray()
        icon.image = renderImageWithBadge(baseImage, badge)
    }

    override fun setContextMenu(menuImpl: ContextMenuNativeImpl<*>?) {
        contextMenuImpl = menuImpl
        val icon = requireTray()
        icon.popupMenu = buildAwtPopupMenu(menuImpl)
    }

    override fun setOnTrayIconClicked(listener: (() -> Unit)?) {
        clickedListener = listener
        requireTray()
    }

    override fun dispose() {
        isDisposed = true
        val icon = trayIcon
        trayIcon = null
        contextMenuImpl = null
        clickedListener = null
        if (icon != null) {
            activeTrayIcons.remove(icon)
            runCatching { SystemTray.getSystemTray().remove(icon) }
        }
    }

    companion object {
        private val activeTrayIcons = mutableListOf<TrayIcon>()

        internal fun checkSystemTraySupported(): Boolean {
            if (GraphicsEnvironment.isHeadless()) return false
            return runCatching { SystemTray.isSupported() }.getOrDefault(false)
        }

        internal fun currentPrimaryTrayIcon(): TrayIcon? =
            activeTrayIcons.lastOrNull()

        internal fun buildAwtPopupMenu(menuImpl: ContextMenuNativeImpl<*>?): PopupMenu? {
            if (menuImpl == null) return null
            val swingContext = menuImpl as? SwingContextMenuImpl ?: return PopupMenu()
            val popup = PopupMenu()
            populateAwtMenu(popup, swingContext.items)
            return popup
        }

        private fun populateAwtMenu(target: Menu, children: List<NativeImpl<*>>) {
            for (child in children) {
                when (child) {
                    is SwingDividerImpl, is DividerNativeImpl<*> -> target.addSeparator()
                    is SwingMenuImpl -> {
                        val sub = Menu(child.getLabel())
                        sub.isEnabled = child.isEnabled()
                        populateAwtMenu(sub, child.items)
                        target.add(sub)
                    }
                    is SwingMenuItemImpl -> {
                        if (child.checkable || child.radio) {
                            val item = CheckboxMenuItem(child.getLabel(), child.isChecked())
                            item.isEnabled = child.isEnabled()
                            item.addItemListener {
                                child.setChecked(item.state)
                                child.trigger()
                            }
                            target.add(item)
                        } else {
                            val item = MenuItem(child.getLabel())
                            item.isEnabled = child.isEnabled()
                            item.addActionListener { child.trigger() }
                            target.add(item)
                        }
                    }
                }
            }
        }

        internal fun loadTrayImage(iconPathOrUrl: String?): Image? {
            val trimmed = iconPathOrUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val bytes = JvmImageSourceResolver.fetchUrlOrLocalBytes(trimmed)
            if (bytes != null && bytes.isNotEmpty()) {
                runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()?.let { return it }
            }
            val directFile = File(trimmed)
            if (directFile.isFile && directFile.exists()) {
                runCatching { ImageIO.read(directFile) }.getOrNull()?.let { return it }
                if (!GraphicsEnvironment.isHeadless()) {
                    return runCatching { Toolkit.getDefaultToolkit().getImage(directFile.absolutePath) }.getOrNull()
                }
            }
            val resourceUrl = SwingSystemTrayImpl::class.java.classLoader?.getResource(trimmed.trimStart('/'))
            if (resourceUrl != null) {
                runCatching { ImageIO.read(resourceUrl) }.getOrNull()?.let { return it }
                if (!GraphicsEnvironment.isHeadless()) {
                    return runCatching { Toolkit.getDefaultToolkit().getImage(resourceUrl) }.getOrNull()
                }
            }
            return null
        }

        internal fun createDefaultTrayImage(): BufferedImage {
            loadTrayImage("icon.png")?.let { loaded ->
                val img = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
                val g = img.createGraphics()
                try {
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                    g.drawImage(loaded, 0, 0, 16, 16, null)
                } finally {
                    g.dispose()
                }
                return img
            }
            val img = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.color = Color(13, 110, 253)
                g.fillRoundRect(1, 1, 14, 14, 4, 4)
            } finally {
                g.dispose()
            }
            return img
        }

        internal fun renderImageWithBadge(base: Image, badge: String?): Image {
            val trimmed = badge?.trim()?.takeIf { it.isNotEmpty() } ?: return base
            val w = base.getWidth(null).takeIf { it > 0 } ?: 16
            val h = base.getHeight(null).takeIf { it > 0 } ?: 16
            val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val g = out.createGraphics()
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.drawImage(base, 0, 0, w, h, null)
                val label = trimmed.take(3)
                val badgeD = (minOf(w, h) * 0.56f).toInt().coerceAtLeast(8)
                val bx = w - badgeD
                val by = h - badgeD
                g.color = Color(220, 53, 69)
                g.fillOval(bx, by, badgeD, badgeD)
                g.color = Color.WHITE
                g.font = Font(Font.SANS_SERIF, Font.BOLD, (badgeD * 0.65f).toInt().coerceAtLeast(7))
                val fm = g.fontMetrics
                val tx = bx + ((badgeD - fm.stringWidth(label)) / 2)
                val ty = by + ((badgeD - fm.height) / 2) + fm.ascent
                g.drawString(label, tx, ty)
            } finally {
                g.dispose()
            }
            return out
        }
    }
}
