package com.nexustools.genericui.swing

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.enums.bootstrap.FlexDirection
import com.nexustools.genericui.nativeimpl.layout.ContainerNativeImpl
import com.nexustools.genericui.style.gradient.Gradient.Gradient
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Java Swing `JPanel` implementation of [ContainerNativeImpl] backed by [SwingFlexLayoutManager].
 *
 * Non-styled [SwingContainerImpl] instances keep intermediate panels non-opaque (`isOpaque = false`)
 * so parent imagery/video and `backdrop-filter` blur composite cleanly, while explicitly styled
 * containers (`Card`, `Alert`, `MdiWindow`, `MdiDesktop`) paint native-theme-compatible surface chrome.
 */
open class SwingContainerImpl : SwingBaseContainerNativeImpl, ContainerNativeImpl<JComponent> {

    private val explicitTagName: String

    constructor(
        classPrefix: String = "container",
        panel: JPanel = SwingLayoutPanel()
    ) : super(panel, classPrefix) {
        this.explicitTagName = ""
        initializeContainer()
    }

    constructor(
        classPrefix: String,
        tagName: String
    ) : super(SwingLayoutPanel(), classPrefix) {
        this.explicitTagName = tagName
        initializeContainer()
    }

    protected constructor(
        classPrefix: String,
        tagName: String,
        panel: JPanel
    ) : super(panel, classPrefix) {
        this.explicitTagName = tagName
        initializeContainer()
    }

    override val componentTagName: String
        get() = explicitTagName

    private fun initializeContainer() {
        nativeWidget.putClientProperty("tagName", explicitTagName)
        nativeWidget.layout = SwingFlexLayoutManager(hostNativeImpl = this)
        nativeWidget.putClientProperty(SwingFlexLayoutManager.MANAGED_LAYOUT_INSETS_KEY, true)
        if (this !is SwingStyledContainerImpl) {
            nativeWidget.isOpaque = false
        }
        syncSwingAppearance()
    }

    override fun syncSwingAppearance() {
        super.syncSwingAppearance()
        if (this !is SwingStyledContainerImpl) {
            nativeWidget.putClientProperty("backdropBlurPx", null)
            nativeWidget.putClientProperty("backdrop-filter", null)
            nativeWidget.putClientProperty("-webkit-backdrop-filter", null)
            nativeWidget.putClientProperty("backgroundGradient", null)
        }
        if (nativeWidget.layout !is SwingFlexLayoutManager) {
            nativeWidget.layout = SwingFlexLayoutManager(hostNativeImpl = this)
            nativeWidget.putClientProperty(SwingFlexLayoutManager.MANAGED_LAYOUT_INSETS_KEY, true)
        }
    }

    override fun addChildNative(childImpl: NativeImpl<*>, index: Int) {
        super.addChildNative(childImpl, index)
        // Propagate inherited contrast/surface appearance (e.g. Alert text color or LiveScss subtrees)
        (childImpl as? SwingBaseNativeImpl)?.let { swingChild ->
            swingChild.refreshThemeAppearance()
            swingChild.refreshDescendantThemeAppearance()
        }
    }

    override fun setBackgroundGradient(gradient: Gradient?) {
        if (this is SwingStyledContainerImpl) {
            super<SwingBaseContainerNativeImpl>.setBackgroundGradient(gradient)
        } else {
            super<SwingBaseContainerNativeImpl>.setBackgroundGradient(null)
            nativeWidget.putClientProperty("backgroundGradient", null)
        }
    }

    override fun getBackgroundGradient(): Gradient? =
        if (this is SwingStyledContainerImpl) super<SwingBaseContainerNativeImpl>.getBackgroundGradient() else null

    internal open class SwingLayoutPanel : JPanel() {
        private val panelLogicalChildren = ArrayList<Component>()
        internal var isReorderingZOrder: Boolean = false

        init {
            isOpaque = false
        }

        internal fun getPanelLogicalChildren(): List<Component> =
            if (componentCount == 0) {
                panelLogicalChildren.clear()
                emptyList()
            } else if (panelLogicalChildren.isNotEmpty()) {
                panelLogicalChildren.filter { it.parent === this }
            } else {
                components.toList()
            }

        override fun addImpl(comp: Component, constraints: Any?, index: Int) {
            if (!isReorderingZOrder) {
                panelLogicalChildren.remove(comp)
                val targetIdx = if (index < 0 || index >= panelLogicalChildren.size) {
                    panelLogicalChildren.size
                } else {
                    index
                }
                panelLogicalChildren.add(targetIdx, comp)
            }
            super.addImpl(comp, constraints, index)
        }

        override fun remove(index: Int) {
            if (!isReorderingZOrder && index in 0 until componentCount) {
                panelLogicalChildren.remove(getComponent(index))
            }
            super.remove(index)
        }

        override fun removeAll() {
            if (!isReorderingZOrder) {
                panelLogicalChildren.clear()
            }
            super.removeAll()
        }

        override fun isOptimizedDrawingEnabled(): Boolean {
            val impl = getClientProperty(CLIENT_PROPERTY_KEY) as? SwingBaseNativeImpl ?: return true
            val tag = SwingSurfacePaintUtil.normalizeTag(impl.componentTagName)
            if (tag == "layeredcontainer" || tag == "layered-container" ||
                tag == "mdidesktop" || tag == "mdi-desktop" ||
                SwingSurfacePaintUtil.hasClassToken(impl.componentClassPrefix, impl.getClasses(), "gui-layered-container") ||
                SwingSurfacePaintUtil.hasClassToken(impl.componentClassPrefix, impl.getClasses(), "mdi-desktop-surface")
            ) {
                return false
            }
            return componentCount <= 1
        }

        override fun contains(x: Int, y: Int): Boolean {
            val impl = getClientProperty(CLIENT_PROPERTY_KEY) as? SwingBaseNativeImpl ?: return super.contains(x, y)
            return impl.hitTestContains(this, x, y, super.contains(x, y))
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val impl = getClientProperty(CLIENT_PROPERTY_KEY) as? SwingBaseNativeImpl ?: return
            if (impl is SwingStyledContainerImpl) return
            if (!SwingSurfacePaintUtil.shouldPaintSystemSurfaceChrome(
                    nativeImpl = impl,
                    tagName = impl.componentTagName,
                    classPrefix = impl.componentClassPrefix,
                    classes = impl.getClasses()
                )
            ) {
                return
            }
            val isDark = SwingSurfacePaintUtil.isDarkThemeForComponent(this)
            val spec = SwingSurfacePaintUtil.resolveEffectiveSurfaceSpec(
                nativeImpl = impl,
                tagName = impl.componentTagName,
                classPrefix = impl.componentClassPrefix,
                classes = impl.getClasses(),
                explicitSpec = null,
                isDark = isDark
            ) ?: return
            val rawOpacity = getClientProperty("opacity") as? Float
            val op = (rawOpacity ?: spec.opacity ?: 1f).coerceIn(0f, 1f)
            SwingSurfacePaintUtil.withComponentTransform(this, g) { tg ->
                SwingSurfacePaintUtil.paintStyledSurface(
                    g = tg,
                    width = width,
                    height = height,
                    spec = spec.copy(opacity = null),
                    isFocused = hasFocus(),
                    componentOpacity = op
                )
            }
        }

        override fun paintChildren(g: Graphics) {
            SwingSurfacePaintUtil.withComponentTransform(this, g) { tg ->
                val componentOpacity = ((getClientProperty("opacity") as? Float) ?: 1f).coerceIn(0f, 1f)
                if (componentOpacity <= SwingSurfacePaintUtil.OPACITY_SKIP_THRESHOLD) return@withComponentTransform
                if (componentOpacity >= 0.999f || componentCount == 0) {
                    super.paintChildren(tg)
                    return@withComponentTransform
                }
                SwingSurfacePaintUtil.paintChildrenWithOpacity(this, tg, componentOpacity) { childG ->
                    super.paintChildren(childG)
                }
            }
        }
    }
}
