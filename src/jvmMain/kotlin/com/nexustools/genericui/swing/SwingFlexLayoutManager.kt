package com.nexustools.genericui.swing

import com.nexustools.genericui.geometry.EdgeInsets
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.enums.css.CssDisplay
import com.nexustools.genericui.enums.css.CssPosition
import com.nexustools.genericui.enums.css.CssUnit
import com.nexustools.genericui.style.layout.BrowserLayoutEngine
import com.nexustools.genericui.style.layout.ComputedLayoutBox
import com.nexustools.genericui.style.layout.IntrinsicSizeMeasurer
import com.nexustools.genericui.style.layout.LayoutNodeAdapter
import com.nexustools.genericui.style.layout.LayoutStyleSnapshot
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.Insets
import java.awt.LayoutManager2
import javax.swing.AbstractButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextField
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Full CSS Flexbox, Bootstrap 12-column grid, Block/Inline flow, Absolute/Fixed positioning,
 * and `LayeredContainer` Z-order stacking [LayoutManager2] for Swing containers.
 *
 * Delegates box-model & flexbox math to [BrowserLayoutEngine] while measuring Swing leaf controls
 * accurately (including multi-line wrapped typography/markdown and width-constrained nested layouts)
 * and preventing single-axis `0px` collapse when only one dimension is specified.
 */
internal class SwingFlexLayoutManager(
    private val hostNativeImpl: SwingBaseNativeImpl? = null,
    private val defaultDisplay: CssDisplay? = null,
    private val defaultFlexDirection: com.nexustools.genericui.enums.bootstrap.FlexDirection? = null,
    private val defaultGapPx: Int = 0,
    private val defaultPadding: EdgeInsets = EdgeInsets.ZERO,
    private val isLayeredHost: Boolean = false
) : LayoutManager2 {

    private var activeRootContainer: Container? = null
    private var reorderedDuringPass: Boolean = false
    private var measureIndefiniteColumnHeight: Boolean = false
    private val activeMeasuringNodes = HashSet<Component>()

    override fun addLayoutComponent(name: String?, comp: Component?) {}

    override fun addLayoutComponent(comp: Component?, constraints: Any?) {}

    override fun removeLayoutComponent(comp: Component?) {}

    override fun invalidateLayout(target: Container?) {}

    override fun getLayoutAlignmentX(target: Container?): Float = 0.5f

    override fun getLayoutAlignmentY(target: Container?): Float = 0.0f

    override fun minimumLayoutSize(parent: Container): Dimension {
        val style = buildSnapshotFor(parent, viewportW = 800, viewportH = 600, isLayoutRoot = true)
        val insets = resolveEffectiveInsets(parent, style)
        val minW = style.minWidth?.resolvePx(viewportWidthPx = 800f, viewportHeightPx = 600f)?.roundToInt() ?: 0
        val minH = style.minHeight?.resolvePx(viewportWidthPx = 800f, viewportHeightPx = 600f)?.roundToInt() ?: 0
        return Dimension(max(minW, insets.left + insets.right), max(minH, insets.top + insets.bottom))
    }

    override fun maximumLayoutSize(target: Container): Dimension = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)

    override fun preferredLayoutSize(parent: Container): Dimension {
        val availW = resolveReferenceWidth(parent)
        val availH = resolveReferenceHeight(parent)
        return measurePreferredSize(parent, availW, availH, shrinkWrapWidth = true)
    }

    fun measurePreferredSize(
        parent: Container,
        availableWidthPx: Int,
        availableHeightPx: Int,
        shrinkWrapWidth: Boolean = false
    ): Dimension {
        if (!activeMeasuringNodes.add(parent)) {
            val expW = resolveExplicitWidth(parent) ?: parent.width.takeIf { it > 0 } ?: 1
            val expH = resolveExplicitHeight(parent) ?: parent.height.takeIf { it > 0 } ?: 1
            return Dimension(expW.coerceAtLeast(1), expH.coerceAtLeast(1))
        }
        return try {
            val safeAvailW = availableWidthPx.coerceAtLeast(1)
            val safeAvailH = availableHeightPx.coerceAtLeast(0)
            val engine = createEngine(parent, measureMode = true)
            // Use viewport height 0 when container height is auto so BrowserLayoutEngine computes tight maxBottom
            val rootSnapshot = buildSnapshotFor(parent, safeAvailW, safeAvailH.coerceAtLeast(1), isLayoutRoot = true, measureMode = true)
            val explicitRootW = resolveRootExplicitWidth(parent, rootSnapshot, safeAvailW)
            val explicitRootH = resolveRootExplicitHeight(parent, rootSnapshot, safeAvailH)

            val computeW = explicitRootW ?: safeAvailW
            val computeH = explicitRootH ?: 0

            val isColumnFlexRoot = (rootSnapshot.display == CssDisplay.FLEX || rootSnapshot.display == CssDisplay.INLINE_FLEX) &&
                (rootSnapshot.flexDirection == com.nexustools.genericui.enums.bootstrap.FlexDirection.COLUMN ||
                    rootSnapshot.flexDirection == com.nexustools.genericui.enums.bootstrap.FlexDirection.COLUMN_REVERSE)
            val prevIndefiniteCol = measureIndefiniteColumnHeight
            measureIndefiniteColumnHeight = (explicitRootH == null && isColumnFlexRoot)
            val layoutBox = try {
                engine.computeLayout(parent, Rect(0, 0, computeW, computeH))
            } finally {
                measureIndefiniteColumnHeight = prevIndefiniteCol
            }
            val children = logicalChildrenOf(parent)
            val childResults = layoutBox.children

            var maxChildRight = layoutBox.contentBox.x
            var maxChildBottom = layoutBox.contentBox.y
            var visibleCount = 0
            for (i in children.indices) {
                val box = childResults.getOrNull(i) ?: continue
                if (!box.isVisibleInLayout) continue
                visibleCount++
                maxChildRight = max(maxChildRight, box.borderBox.right + box.margin.right)
                maxChildBottom = max(maxChildBottom, box.borderBox.bottom + box.margin.bottom)
            }

            val insets = resolveEffectiveInsets(parent, rootSnapshot)
            val contentUsedW = if (visibleCount > 0) (maxChildRight - layoutBox.contentBox.x).coerceAtLeast(0) else 0
            val contentUsedH = if (visibleCount > 0) (maxChildBottom - layoutBox.contentBox.y).coerceAtLeast(0) else 0

            val outW = when {
                explicitRootW != null -> explicitRootW
                !shrinkWrapWidth -> computeW
                else -> {
                    // For vertical/block containers inside shrink-wrap contexts, ask each visible flow child its natural width
                    val isColumnOrBlock = rootSnapshot.display == CssDisplay.BLOCK ||
                        rootSnapshot.flexDirection == com.nexustools.genericui.enums.bootstrap.FlexDirection.COLUMN ||
                        rootSnapshot.flexDirection == com.nexustools.genericui.enums.bootstrap.FlexDirection.COLUMN_REVERSE
                    if (isColumnOrBlock && !isEffectivelyLayered(parent, rootSnapshot)) {
                        var widest = contentUsedW
                        val innerAvailW = (computeW - insets.left - insets.right).coerceAtLeast(1)
                        for (child in children) {
                            if (!child.isVisible) continue
                            val cSnap = buildSnapshotFor(child, innerAvailW, safeAvailH.coerceAtLeast(1), isLayoutRoot = false)
                            if (cSnap.display == CssDisplay.NONE) continue
                            val intrinsic = SwingIntrinsicSizeMeasurer.measureIntrinsicSize(child, innerAvailW, safeAvailH.coerceAtLeast(1))
                            val wSpec = cSnap.width?.resolvePx(referencePx = innerAvailW.toFloat(), viewportWidthPx = computeW.toFloat(), viewportHeightPx = safeAvailH.coerceAtLeast(1).toFloat())?.roundToInt()
                            val mL = cSnap.marginLeft?.resolvePx(referencePx = innerAvailW.toFloat())?.roundToInt() ?: 0
                            val mR = cSnap.marginRight?.resolvePx(referencePx = innerAvailW.toFloat())?.roundToInt() ?: 0
                            val cW = max(wSpec ?: intrinsic.width, 0) + mL + mR
                            if (cW > widest) widest = cW
                        }
                        widest + insets.left + insets.right
                    } else {
                        max(contentUsedW + insets.left + insets.right, insets.left + insets.right)
                    }
                }
            }.coerceAtLeast(1)

            val outH = (explicitRootH ?: (contentUsedH + insets.top + insets.bottom)).coerceAtLeast(1)
            Dimension(outW, outH)
        } finally {
            activeMeasuringNodes.remove(parent)
        }
    }

    override fun layoutContainer(parent: Container) {
        val w = parent.width.takeIf { it > 0 } ?: resolveExplicitWidth(parent) ?: parent.preferredSize?.width ?: 0
        val h = parent.height.takeIf { it > 0 } ?: resolveExplicitHeight(parent) ?: parent.preferredSize?.height ?: 0
        if (w <= 0 && h <= 0) return
        val safeW = w.coerceAtLeast(1)
        val safeH = h.coerceAtLeast(1)

        val engine = createEngine(parent, measureMode = false)
        val result = engine.computeLayout(parent, Rect(0, 0, safeW, safeH))
        val children = logicalChildrenOf(parent)
        val childBoxes = result.children

        // Map computed boxes onto Swing child components
        val rootSnapshot = buildSnapshotFor(parent, safeW, safeH, isLayoutRoot = true)
        val zEntries = ArrayList<Triple<Component, Int, Int>>(children.size)
        for (i in children.indices) {
            val child = children[i]
            val snap = buildSnapshotFor(child, safeW, safeH, isLayoutRoot = false)
            val rawBox = childBoxes.getOrNull(i)
            var targetRect = rawBox?.borderBox
            var effectiveZ = rawBox?.effectiveZIndex ?: snap.zIndex
            if (snap.display != CssDisplay.NONE &&
                child.isVisible &&
                (snap.position == CssPosition.ABSOLUTE || snap.position == CssPosition.FIXED)
            ) {
                val cb = resolveContainingBlockForPositionedChild(parent, rootSnapshot, snap.position)
                if (cb != null) {
                    val (anc, offset) = cb
                    val ancBox = computePositionedChildBox(anc, child, anc.width, anc.height)
                    if (ancBox != null) {
                        targetRect = Rect(
                            ancBox.borderBox.x - offset.x,
                            ancBox.borderBox.y - offset.y,
                            ancBox.borderBox.width,
                            ancBox.borderBox.height
                        )
                        effectiveZ = ancBox.effectiveZIndex
                    }
                }
            }
            if (targetRect != null && snap.display != CssDisplay.NONE && child.isVisible) {
                val targetX = targetRect.x
                val targetY = targetRect.y
                val targetW = targetRect.width.coerceAtLeast(0)
                val targetH = targetRect.height.coerceAtLeast(0)
                val boundsChanged =
                    child.x != targetX || child.y != targetY || child.width != targetW || child.height != targetH
                if (boundsChanged) {
                    child.setBounds(targetX, targetY, targetW, targetH)
                }
                if (child is Container && (boundsChanged || !child.isValid)) {
                    child.invalidate()
                    child.doLayout()
                }
                (child as? JComponent)?.let { jc ->
                    (jc.getClientProperty(SwingBaseNativeImpl.CLIENT_PROPERTY_KEY) as? SwingBaseNativeImpl)
                        ?.updateLogicalBoundsFromAwt(targetX, targetY, targetW, targetH)
                }
            }
            zEntries.add(Triple(child, effectiveZ, i))
        }

        applySwingZOrderIfNeeded(parent, zEntries)
    }

    private fun isWindowViewportHost(comp: Component): Boolean =
        (comp as? JComponent)?.getClientProperty("genericuiWindowRootHost") == true

    private fun resolveRawCssPosition(comp: Component): CssPosition {
        val impl = (comp as? JComponent)?.getClientProperty(SwingBaseNativeImpl.CLIENT_PROPERTY_KEY) as? SwingBaseNativeImpl
            ?: return CssPosition.STATIC
        val pos = impl.buildLayoutStyleInput().styleProps["position"]?.trim()?.lowercase()
        return when (pos) {
            "relative" -> CssPosition.RELATIVE
            "absolute" -> CssPosition.ABSOLUTE
            "fixed" -> CssPosition.FIXED
            "sticky" -> CssPosition.STICKY
            else -> CssPosition.STATIC
        }
    }

    private fun isPositionedOrLayeredContainingBlock(container: Container): Boolean {
        if (isWindowViewportHost(container)) return true
        val lm = container.layout as? SwingFlexLayoutManager
        val snap = lm?.let { buildSnapshotFor(container, container.width.coerceAtLeast(1), container.height.coerceAtLeast(1), isLayoutRoot = true) }
        if (lm != null && snap != null && lm.isEffectivelyLayered(container, snap)) return true
        return resolveRawCssPosition(container) != CssPosition.STATIC
    }

    private fun resolveContainingBlockForPositionedChild(
        parent: Container,
        parentRootSnapshot: LayoutStyleSnapshot,
        childPosition: CssPosition
    ): Pair<Container, java.awt.Point>? {
        if (childPosition == CssPosition.FIXED) {
            if (isWindowViewportHost(parent)) return null
        } else {
            if (isWindowViewportHost(parent) || isEffectivelyLayered(parent, parentRootSnapshot)) return null
            if (resolveRawCssPosition(parent) != CssPosition.STATIC) return null
        }

        var offsetX = parent.x
        var offsetY = parent.y
        var anc: Container? = parent.parent as? Container
        while (anc != null) {
            if (anc is javax.swing.JRootPane || anc is javax.swing.JLayeredPane) break
            if (isWindowViewportHost(anc) ||
                (childPosition != CssPosition.FIXED && isPositionedOrLayeredContainingBlock(anc))
            ) {
                return anc to java.awt.Point(offsetX, offsetY)
            }
            val ancParent = anc.parent as? Container
            if (ancParent == null || ancParent is javax.swing.JRootPane || ancParent is javax.swing.JLayeredPane) {
                return anc to java.awt.Point(offsetX, offsetY)
            }
            offsetX += anc.x
            offsetY += anc.y
            anc = ancParent
        }
        return null
    }

    private fun applySwingZOrderIfNeeded(parent: Container, entries: List<Triple<Component, Int, Int>>) {
        if (entries.size <= 1) return
        // In Swing Container, index 0 is painted LAST (on TOP) and hit-tested FIRST.
        // Higher zIndex comes first; for equal zIndex, later logical sibling comes first (on top).
        val desiredOrder = entries.sortedWith(
            Comparator { a, b ->
                val zCmp = b.second.compareTo(a.second)
                if (zCmp != 0) zCmp else b.third.compareTo(a.third)
            }
        ).map { it.first }

        val currentCount = parent.componentCount
        var alreadySorted = desiredOrder.size == currentCount
        if (alreadySorted) {
            for (idx in desiredOrder.indices) {
                if (parent.getComponent(idx) !== desiredOrder[idx]) {
                    alreadySorted = false
                    break
                }
            }
        }
        if (alreadySorted) return

        reorderedDuringPass = true
        (parent as? SwingContainerImpl.SwingLayoutPanel)?.isReorderingZOrder = true
        try {
            for (idx in desiredOrder.indices) {
                val comp = desiredOrder[idx]
                if (comp.parent === parent && idx < parent.componentCount && parent.getComponent(idx) !== comp) {
                    parent.setComponentZOrder(comp, idx)
                }
            }
        } finally {
            (parent as? SwingContainerImpl.SwingLayoutPanel)?.isReorderingZOrder = false
            reorderedDuringPass = false
        }
    }

    private fun createEngine(root: Container, measureMode: Boolean, childSubset: List<Component>? = null): BrowserLayoutEngine<Component> {
        activeRootContainer = root
        val adapter = object : LayoutNodeAdapter<Component> {
            override fun childrenOf(node: Component): List<Component> =
                if (node === root) (childSubset ?: logicalChildrenOf(root)) else emptyList()

            override fun layoutStyleOf(
                node: Component,
                viewportWidthPx: Int,
                viewportHeightPx: Int
            ): LayoutStyleSnapshot = buildSnapshotFor(
                comp = node,
                viewportW = viewportWidthPx,
                viewportH = viewportHeightPx,
                isLayoutRoot = (node === root),
                measureMode = measureMode
            )

            override fun tagNameOf(node: Component): String {
                val impl = (node as? JComponent)?.getClientProperty(SwingBaseNativeImpl.CLIENT_PROPERTY_KEY) as? SwingBaseNativeImpl
                return impl?.componentTagName?.takeIf { it.isNotBlank() }
                    ?: impl?.componentClassPrefix
                    ?: "div"
            }
        }
        return BrowserLayoutEngine(adapter, SwingIntrinsicSizeMeasurer)
    }

    private fun logicalChildrenOf(parent: Container): List<Component> {
        val baseContainer = (parent as? JComponent)
            ?.getClientProperty(SwingBaseNativeImpl.CLIENT_PROPERTY_KEY) as? SwingBaseContainerNativeImpl
        if (baseContainer != null) {
            return baseContainer.getLogicalChildComponents()
        }
        if (parent is SwingContainerImpl.SwingLayoutPanel) {
            return parent.getPanelLogicalChildren()
        }
        return parent.components.toList()
    }

    private fun isEffectivelyLayered(parent: Container, snapshot: LayoutStyleSnapshot): Boolean {
        if (isLayeredHost) return true
        val impl = (parent as? JComponent)?.getClientProperty(SwingBaseNativeImpl.CLIENT_PROPERTY_KEY) as? SwingBaseNativeImpl
        val tag = impl?.componentTagName?.trim()?.lowercase()?.removePrefix("genericui:").orEmpty()
        if (tag == "layeredcontainer" || tag == "layered-container") return true
        val classes = impl?.getClasses().orEmpty()
        return SwingSurfacePaintUtil.hasClassToken(impl?.componentClassPrefix.orEmpty(), classes, "gui-layered-container")
    }

    private fun buildSnapshotFor(
        comp: Component,
        viewportW: Int,
        viewportH: Int,
        isLayoutRoot: Boolean,
        measureMode: Boolean = false
    ): LayoutStyleSnapshot {
        val jc = comp as? JComponent
        val impl = jc?.getClientProperty(SwingBaseNativeImpl.CLIENT_PROPERTY_KEY) as? SwingBaseNativeImpl
        if (!comp.isVisible) {
            return LayoutStyleSnapshot(display = CssDisplay.NONE)
        }
        if (impl == null) {
            val pref = if (!isLayoutRoot && comp !in activeMeasuringNodes) {
                runCatching { comp.preferredSize }.getOrNull()
            } else null
            val w = if (!isLayoutRoot) (pref?.width?.takeIf { it > 0 } ?: comp.width.takeIf { it > 0 } ?: 0) else 0
            val h = if (!isLayoutRoot) (pref?.height?.takeIf { it > 0 } ?: comp.height.takeIf { it > 0 } ?: 0) else 0
            val styleProps = linkedMapOf<String, String>()
            if (isLayoutRoot) {
                if (defaultDisplay != null) {
                    styleProps["display"] = defaultDisplay.name.lowercase().replace('_', '-')
                }
                if (defaultFlexDirection != null) {
                    styleProps["flex-direction"] = defaultFlexDirection.name.lowercase().replace('_', '-')
                }
                if (defaultGapPx > 0) {
                    styleProps["gap"] = "${defaultGapPx}px"
                }
                if (!measureMode) {
                    styleProps["width"] = "${viewportW}px"
                    styleProps["height"] = "${viewportH}px"
                }
            }
            val insets = (comp as? Container)?.insets
            val pad = if (isLayoutRoot && defaultPadding != EdgeInsets.ZERO) {
                defaultPadding
            } else if (insets != null) {
                EdgeInsets(insets.top, insets.right, insets.bottom, insets.left)
            } else {
                EdgeInsets.ZERO
            }
            return LayoutStyleSnapshot.fromAttributesAndStyles(
                tagName = "div",
                classes = emptySet(),
                attributes = emptyMap(),
                styleProps = styleProps,
                explicitBounds = Rect(0, 0, w, h),
                padding = pad,
                visible = comp.isVisible,
                viewportWidthPx = viewportW,
                viewportHeightPx = viewportH
            )
        }

        val input = impl.buildLayoutStyleInput()
        val styleProps = LinkedHashMap(input.styleProps)
        val classes = LinkedHashSet(input.classes)
        val attrs = input.attributes

        // Apply layout-manager container defaults if parent uses defaults (ScrollView, Dialog body, Stack)
        if (isLayoutRoot) {
            if (defaultDisplay != null && "display" !in styleProps && classes.none { it.startsWith("d-") }) {
                styleProps["display"] = defaultDisplay.name.lowercase().replace('_', '-')
            }
            if (defaultFlexDirection != null && "flex-direction" !in styleProps &&
                classes.none { it.startsWith("flex-row") || it.startsWith("flex-column") }
            ) {
                styleProps["flex-direction"] = defaultFlexDirection.name.lowercase().replace('_', '-')
            }
            if (defaultGapPx > 0 && "gap" !in styleProps && "row-gap" !in styleProps && "column-gap" !in styleProps) {
                styleProps["gap"] = "${defaultGapPx}px"
            }
        }

        val explicitHeightPx = input.explicitHeightPx
            ?: styleProps["height"]?.trim()?.takeIf { it.endsWith("px", ignoreCase = true) }?.dropLast(2)?.trim()?.toFloatOrNull()?.toInt()

        // Card / Alert default internal spacing when not overridden
        if (SwingSurfacePaintUtil.isCardComponent(input.tagName, input.classPrefix, classes)) {
            if ("display" !in styleProps && classes.none { it.startsWith("d-") }) {
                styleProps["display"] = "flex"
                styleProps.putIfAbsent("flex-direction", "column")
            }
            if ("gap" !in styleProps && "row-gap" !in styleProps) {
                styleProps["row-gap"] = if (explicitHeightPx != null && explicitHeightPx < 130) "4px" else "8px"
            }
        } else if (SwingSurfacePaintUtil.isAlertComponent(input.tagName, input.classPrefix, classes)) {
            if ("display" !in styleProps && classes.none { it.startsWith("d-") }) {
                styleProps["display"] = "flex"
                styleProps.putIfAbsent("flex-direction", "row")
                styleProps.putIfAbsent("align-items", "center")
            }
        } else if (SwingSurfacePaintUtil.isMdiWindowComponent(input.tagName, input.classPrefix, classes)) {
            if ("display" !in styleProps && classes.none { it.startsWith("d-") }) {
                styleProps["display"] = "flex"
                styleProps.putIfAbsent("flex-direction", "column")
            }
        }

        val normalizedTag = SwingSurfacePaintUtil.normalizeTag(input.tagName)
        val isFragmentContainer = normalizedTag == "fragment-container" ||
            normalizedTag == "fragmentcontainer" ||
            SwingSurfacePaintUtil.hasClassToken(input.classPrefix, classes, "fragment-container")
        val isFragment = normalizedTag == "fragment" ||
            SwingSurfacePaintUtil.hasClassToken(input.classPrefix, classes, "fragment")
        val isDockWorkspace = impl is SwingDockWorkspaceImpl ||
            SwingSurfacePaintUtil.hasClassToken(input.classPrefix, classes, "dock-workspace")
        if (isFragmentContainer || isFragment) {
            if ("display" !in styleProps && classes.none { it.startsWith("d-") }) {
                styleProps["display"] = "flex"
                styleProps.putIfAbsent("flex-direction", "column")
            }
            if (!isLayoutRoot) {
                styleProps.putIfAbsent("width", "100%")
                styleProps.putIfAbsent("flex-grow", "1")
                styleProps.putIfAbsent("min-height", "0px")
                if (!measureMode) {
                    styleProps.putIfAbsent("height", "100%")
                }
            }
        } else if (!isLayoutRoot && isDockWorkspace) {
            styleProps.putIfAbsent("width", "100%")
            styleProps.putIfAbsent("flex-grow", "1")
            styleProps.putIfAbsent("min-height", "240px")
        }

        // Map HStack/VStack/Spacer attributes cleanly into CSS flex properties
        val spacerFill = attrs["spacerFill"]?.equals("true", ignoreCase = true) == true
        val spacerWeight = attrs["spacerWeight"]?.toFloatOrNull()
        if (spacerFill || (spacerWeight != null && spacerWeight > 0f)) {
            styleProps.putIfAbsent("flex-grow", (spacerWeight ?: 1f).toString())
            styleProps.putIfAbsent("flex-basis", "0px")
        }
        attrs["spacerWidthPx"]?.toIntOrNull()?.takeIf { it > 0 }?.let {
            styleProps.putIfAbsent("width", "${it}px")
        }
        attrs["spacerHeightPx"]?.toIntOrNull()?.takeIf { it > 0 }?.let {
            styleProps.putIfAbsent("height", "${it}px")
        }
        attrs["data-stack-weight"]?.toFloatOrNull()?.takeIf { it > 0f }?.let { weight ->
            styleProps.putIfAbsent("flex-grow", weight.toString())
            styleProps.putIfAbsent("flex-basis", "0px")
        }
        attrs["data-stack-aspect-ratio"]?.toFloatOrNull()?.takeIf { it > 0f }?.let { ar ->
            styleProps.putIfAbsent("aspect-ratio", ar.toString())
        }
        if (attrs["data-stack-shrink-cross"]?.equals("true", ignoreCase = true) == true) {
            styleProps.putIfAbsent("flex-shrink", "1")
        }

        // Single-axis explicit size preservation: only apply explicit width/height > 0, never collapse auto axis to 0
        val expW = input.explicitWidthPx?.takeIf { it > 0 }
        val expH = input.explicitHeightPx?.takeIf { it > 0 }
        if (expW != null && "width" !in styleProps && !hasWidthUtilityClass(classes)) {
            styleProps["width"] = "${expW}px"
        }
        if (expH != null && "height" !in styleProps && !hasHeightUtilityClass(classes)) {
            styleProps["height"] = "${expH}px"
        }

        if (measureMode && !isLayoutRoot) {
            if (measureIndefiniteColumnHeight) {
                styleProps["flex-shrink"] = "0"
                styleProps["flex-grow"] = "0"
            }
            if (viewportH <= 1) {
                stripPercentConstraintsAndClasses(
                    styleProps = styleProps,
                    classes = classes,
                    props = INDEFINITE_HEIGHT_PERCENT_PROPS,
                    classNames = INDEFINITE_HEIGHT_UTILITY_CLASSES
                )
            }
        }

        // LayeredContainer children without explicit float/position default to full-bounds fill
        if (!isLayoutRoot && activeRootContainer != null) {
            val rootLayered = isLayeredHost || isRootTagLayered(activeRootContainer)
            if (rootLayered) {
                val hasLayerClass = "gui-layer-fill" in classes || "gui-layer-float" in classes
                val hasLayerSizing = !attrs["layerSizingMode"].isNullOrBlank() || !attrs["layerRole"].isNullOrBlank()
                val explicitPos = styleProps["position"]?.lowercase()
                if (!hasLayerClass && !hasLayerSizing && explicitPos != "absolute" && explicitPos != "fixed") {
                    classes.add("gui-layer-fill")
                }
            }
        }

        // Root inside layoutContainer / measureMode already has its outer box assigned; do not offset by own margin/pos
        var margin = input.margin
        if (isLayoutRoot) {
            styleProps.remove("position")
            styleProps.remove("top")
            styleProps.remove("right")
            styleProps.remove("bottom")
            styleProps.remove("left")
            styleProps.remove("transform")
            styleProps.remove("margin")
            styleProps.remove("margin-top")
            styleProps.remove("margin-right")
            styleProps.remove("margin-bottom")
            styleProps.remove("margin-left")
            margin = EdgeInsets.ZERO
            if (!measureMode) {
                // In concrete layoutContainer, root fills allocated viewportW x viewportH exactly
                styleProps["width"] = "${viewportW}px"
                styleProps["height"] = "${viewportH}px"
                for (prop in ROOT_CONSTRAINT_PROPS) {
                    styleProps.remove(prop)
                }
                classes.removeAll { it.lowercase() in ROOT_CONSTRAINT_UTILITY_CLASSES }
            } else {
                stripPercentConstraintsAndClasses(
                    styleProps = styleProps,
                    classes = classes,
                    props = ROOT_CONSTRAINT_PROPS,
                    classNames = ROOT_CONSTRAINT_UTILITY_CLASSES
                )
            }
        }

        // Combine default padding / card/alert default padding when none explicitly configured
        var padding = input.padding
        if (padding == EdgeInsets.ZERO && defaultPadding != EdgeInsets.ZERO && "padding" !in styleProps) {
            padding = defaultPadding
        }
        if (padding == EdgeInsets.ZERO && "padding" !in styleProps &&
            "padding-top" !in styleProps && "padding-left" !in styleProps &&
            classes.none { it.startsWith("p-") || it.startsWith("px-") || it.startsWith("py-") || it.startsWith("pt-") || it.startsWith("pl-") }
        ) {
            padding = when {
                SwingSurfacePaintUtil.isCardComponent(input.tagName, input.classPrefix, classes) -> {
                    val p = if (explicitHeightPx != null && explicitHeightPx < 130) 6 else 12
                    EdgeInsets(p, p, p, p)
                }
                SwingSurfacePaintUtil.isAlertComponent(input.tagName, input.classPrefix, classes) -> {
                    val vp = if (explicitHeightPx != null && explicitHeightPx < 40) ((explicitHeightPx - 20).coerceAtLeast(4) / 2) else 10
                    EdgeInsets(vp, 14, vp, 14)
                }
                else -> EdgeInsets.ZERO
            }
        }

        // Incorporate external Swing border insets only if they aren't suppressed and border width wasn't provided by CSS
        val swingInsets = (comp as? JComponent)?.let {
            if (it.getClientProperty(MANAGED_LAYOUT_INSETS_KEY) == true) Insets(0, 0, 0, 0) else it.insets
        }
        if (isLayoutRoot && swingInsets != null && (swingInsets.top > 0 || swingInsets.left > 0 || swingInsets.right > 0 || swingInsets.bottom > 0)) {
            if ("border-width" !in styleProps && input.cssBorderWidthPx <= 0) {
                styleProps["border-top-width"] = "${swingInsets.top}px"
                styleProps["border-right-width"] = "${swingInsets.right}px"
                styleProps["border-bottom-width"] = "${swingInsets.bottom}px"
                styleProps["border-left-width"] = "${swingInsets.left}px"
            }
        } else if (input.cssBorderWidthPx > 0 && "border-width" !in styleProps && "border" !in styleProps) {
            styleProps["border-width"] = "${input.cssBorderWidthPx}px"
        }

        return LayoutStyleSnapshot.fromAttributesAndStyles(
            tagName = input.tagName,
            classes = classes,
            attributes = attrs,
            styleProps = styleProps,
            explicitBounds = Rect(0, 0, expW ?: 0, expH ?: 0),
            padding = padding,
            margin = margin,
            visible = comp.isVisible && input.visible,
            viewportWidthPx = viewportW,
            viewportHeightPx = viewportH
        )
    }

    private fun isRootTagLayered(root: Container?): Boolean {
        val impl = (root as? JComponent)?.getClientProperty(SwingBaseNativeImpl.CLIENT_PROPERTY_KEY) as? SwingBaseNativeImpl
            ?: return false
        val tag = impl.componentTagName.trim().lowercase().removePrefix("genericui:")
        return tag == "layeredcontainer" ||
            tag == "layered-container" ||
            SwingSurfacePaintUtil.hasClassToken(impl.componentClassPrefix, impl.getClasses(), "gui-layered-container")
    }

    private fun resolveEffectiveInsets(parent: Container, snapshot: LayoutStyleSnapshot): Insets = Insets(
        snapshot.padding.top + snapshot.border.top,
        snapshot.padding.left + snapshot.border.left,
        snapshot.padding.bottom + snapshot.border.bottom,
        snapshot.padding.right + snapshot.border.right
    )

    private fun resolveReferenceWidth(parent: Container): Int {
        if (parent.width > 0) return parent.width
        val explicit = resolveExplicitWidth(parent)
        if (explicit != null && explicit > 0) return explicit
        var cur: Container? = parent.parent
        while (cur != null) {
            if (cur.width > 0) return cur.width
            cur = cur.parent
        }
        return 800
    }

    private fun resolveReferenceHeight(parent: Container): Int {
        if (parent.height > 0) return parent.height
        val explicit = resolveExplicitHeight(parent)
        if (explicit != null && explicit > 0) return explicit
        return 0
    }

    private fun resolveExplicitWidth(comp: Component): Int? {
        val impl = (comp as? JComponent)?.getClientProperty(SwingBaseNativeImpl.CLIENT_PROPERTY_KEY) as? SwingBaseNativeImpl
            ?: return null
        return impl.getExplicitWidthPx()
    }

    private fun resolveExplicitHeight(comp: Component): Int? {
        val impl = (comp as? JComponent)?.getClientProperty(SwingBaseNativeImpl.CLIENT_PROPERTY_KEY) as? SwingBaseNativeImpl
            ?: return null
        return impl.getExplicitHeightPx()
    }

    private fun resolveRootExplicitWidth(parent: Container, snap: LayoutStyleSnapshot, availW: Int): Int? =
        snap.width?.resolvePx(referencePx = availW.toFloat(), viewportWidthPx = availW.toFloat(), viewportHeightPx = availW.toFloat())?.roundToInt()
            ?: resolveExplicitWidth(parent)

    private fun resolveRootExplicitHeight(parent: Container, snap: LayoutStyleSnapshot, availH: Int): Int? =
        if (availH > 0) {
            snap.height?.resolvePx(referencePx = availH.toFloat(), viewportWidthPx = availH.toFloat(), viewportHeightPx = availH.toFloat())?.roundToInt()
                ?: resolveExplicitHeight(parent)
        } else {
            snap.height?.takeIf { it.unit != CssUnit.PERCENT }?.resolvePx(referencePx = 0f, viewportWidthPx = 800f, viewportHeightPx = 600f)?.roundToInt()
                ?: resolveExplicitHeight(parent)
        }

    private fun hasWidthUtilityClass(classes: Collection<String>): Boolean =
        classes.any { cls ->
            val lower = cls.lowercase()
            lower == "w-100" || lower == "w-75" || lower == "w-50" || lower == "w-25" || lower == "w-auto"
        }

    private fun hasHeightUtilityClass(classes: Collection<String>): Boolean =
        classes.any { cls ->
            val lower = cls.lowercase()
            lower == "h-100" || lower == "h-75" || lower == "h-50" || lower == "h-25" || lower == "h-auto"
        }

    private fun stripPercentConstraintsAndClasses(
        styleProps: MutableMap<String, String>,
        classes: MutableSet<String>,
        props: List<String>,
        classNames: Set<String>
    ) {
        for (prop in props) {
            if (styleProps[prop]?.trim()?.endsWith("%") == true) {
                styleProps.remove(prop)
            }
        }
        classes.removeAll { it.lowercase() in classNames }
    }

    companion object {
        const val MANAGED_LAYOUT_INSETS_KEY: String = "genericui.managedLayoutInsets"

        private val INDEFINITE_HEIGHT_PERCENT_PROPS = listOf("height", "min-height", "max-height")
        private val INDEFINITE_HEIGHT_UTILITY_CLASSES = setOf("h-100", "h-75", "h-50", "h-25", "mh-100")
        private val ROOT_CONSTRAINT_PROPS = listOf("min-width", "max-width", "min-height", "max-height")
        private val ROOT_CONSTRAINT_UTILITY_CLASSES = setOf("mw-100", "mh-100")

        /**
         * Synchronously lays out [root] and all nested containers/widgets, even when [root]
         * is non-displayable (e.g. headless mode where `jFrame == null` or offscreen renders).
         */
        fun layoutTreeNow(root: Component, forceRootLayout: Boolean = true) {
            if (!forceRootLayout && !root.isVisible) return
            if (root is Container) {
                root.invalidate()
                root.doLayout()
                val snapshot = root.components.copyOf()
                for (child in snapshot) {
                    layoutTreeNow(child, forceRootLayout = false)
                }
                root.invalidate()
                root.doLayout()
            }
        }

        internal fun computePositionedChildBox(
            containingBlock: Container,
            child: Component,
            viewportWidthPx: Int,
            viewportHeightPx: Int
        ): ComputedLayoutBox? {
            val safeW = viewportWidthPx.coerceAtLeast(1)
            val safeH = viewportHeightPx.coerceAtLeast(1)
            val helper = (containingBlock.layout as? SwingFlexLayoutManager) ?: SwingFlexLayoutManager()
            val engine = helper.createEngine(containingBlock, measureMode = false, childSubset = listOf(child))
            return engine.computeLayout(containingBlock, Rect(0, 0, safeW, safeH)).children.firstOrNull()
        }
    }
}

/**
 * Width-constrained Swing widget measurer used by [SwingFlexLayoutManager].
 */
internal object SwingIntrinsicSizeMeasurer : IntrinsicSizeMeasurer<Component> {

    override fun measureIntrinsicSize(node: Component, availableWidthPx: Int, availableHeightPx: Int): Size {
        val availW = availableWidthPx.coerceAtLeast(1)
        val availH = availableHeightPx.coerceAtLeast(1)
        val jc = node as? JComponent
        val impl = jc?.getClientProperty(SwingBaseNativeImpl.CLIENT_PROPERTY_KEY) as? SwingBaseNativeImpl

        val expW = impl?.getExplicitWidthPx()?.takeIf { it > 0 }
        val expH = impl?.getExplicitHeightPx()?.takeIf { it > 0 }

        val measured: Size = when {
            node is Container && node.layout is SwingFlexLayoutManager -> {
                val lm = node.layout as SwingFlexLayoutManager
                val targetW = expW ?: availW
                val dim = lm.measurePreferredSize(
                    parent = node,
                    availableWidthPx = targetW,
                    availableHeightPx = availableHeightPx.coerceAtLeast(0),
                    shrinkWrapWidth = (expW == null)
                )
                Size(dim.width.coerceAtLeast(1), dim.height.coerceAtLeast(1))
            }
            impl is SwingTextImpl -> impl.measureWrappedSize(expW ?: availW)
            impl is SwingMarkdownViewImpl -> impl.measureWrappedSize(expW ?: availW)
            impl is SwingTableImpl -> impl.measurePreferredTableSize(expW ?: availW, expH ?: availH)
            impl is SwingScrollViewImpl -> impl.measurePreferredScrollSize(expW ?: availW, expH ?: availH)
            impl is SwingImageImpl -> impl.measureNaturalOrScaledSize(expW ?: availW, expH)
            node is JScrollPane -> {
                val view = node.viewport?.view
                val viewSize = if (view != null) {
                    measureIntrinsicSize(view, expW ?: availW, availH)
                } else {
                    val p = node.preferredSize
                    Size(p?.width?.takeIf { it > 0 } ?: 120, p?.height?.takeIf { it > 0 } ?: 80)
                }
                Size(viewSize.width.coerceAtLeast(1), viewSize.height.coerceAtLeast(1))
            }
            node is JEditorPane -> measureEditorPane(node, expW ?: availW)
            node is JLabel -> measureLabelFallback(node, expW ?: availW)
            node is AbstractButton || node is JComboBox<*> || node is JTextField -> {
                val pref = node.uiPreferredSizeFallback()
                Size(pref.width.coerceAtLeast(1), pref.height.coerceAtLeast(1))
            }
            else -> {
                val pref = node.uiPreferredSizeFallback()
                Size(pref.width.coerceAtLeast(1), pref.height.coerceAtLeast(1))
            }
        }

        val finalW = expW ?: measured.width.coerceAtLeast(1)
        val finalH = expH ?: measured.height.coerceAtLeast(1)
        return Size(finalW, finalH)
    }

    private fun Component.uiPreferredSizeFallback(): Dimension {
        val prevPref = if (this is JComponent && isPreferredSizeSet) preferredSize else null
        if (prevPref != null && prevPref.width > 0 && prevPref.height > 0) {
            return prevPref
        }
        val d = preferredSize
        val w = d?.width?.takeIf { it > 0 } ?: prevPref?.width?.takeIf { it > 0 } ?: width.takeIf { it > 0 } ?: 1
        val h = d?.height?.takeIf { it > 0 } ?: prevPref?.height?.takeIf { it > 0 } ?: height.takeIf { it > 0 } ?: 1
        return Dimension(w, h)
    }

    private fun measureEditorPane(pane: JEditorPane, maxWidthPx: Int): Size {
        val safeW = maxWidthPx.coerceAtLeast(24)
        val prevSize = pane.size
        pane.setSize(safeW, Short.MAX_VALUE.toInt())
        val pref = pane.preferredSize
        if (prevSize.width > 0 || prevSize.height > 0) {
            pane.size = prevSize
        }
        return Size(
            width = pref.width.coerceAtMost(safeW).coerceAtLeast(1),
            height = pref.height.coerceAtLeast(20)
        )
    }

    private fun measureLabelFallback(label: JLabel, maxWidthPx: Int): Size {
        val pref = label.preferredSize
        val text = label.text.orEmpty()
        if (text.isEmpty() && label.icon == null) {
            return Size(pref.width.coerceAtLeast(1), pref.height.coerceAtLeast(16))
        }
        val fm = label.getFontMetrics(label.font)
        val lineHeight = (fm?.height ?: 16).coerceAtLeast(14)
        val insets = label.insets
        val padW = insets.left + insets.right
        val padH = insets.top + insets.bottom
        if (pref.width <= maxWidthPx && !text.contains('\n')) {
            return Size(pref.width.coerceAtLeast(1), max(pref.height, lineHeight + padH))
        }
        val textAvail = (maxWidthPx - padW - (label.icon?.let { it.iconWidth + label.iconTextGap } ?: 0)).coerceAtLeast(24)
        val wrappedLines = countWrappedLines(text, fm, textAvail)
        return Size(
            width = pref.width.coerceAtMost(maxWidthPx).coerceAtLeast(1),
            height = max(pref.height, wrappedLines * lineHeight + padH)
        )
    }

    internal fun countWrappedLines(text: String, fm: java.awt.FontMetrics?, maxLineWidthPx: Int): Int {
        if (text.isEmpty()) return 1
        if (fm == null || maxLineWidthPx <= 0) return text.lines().size.coerceAtLeast(1)
        var totalLines = 0
        for (rawLine in text.split('\n')) {
            if (rawLine.isEmpty()) {
                totalLines++
                continue
            }
            if (fm.stringWidth(rawLine) <= maxLineWidthPx) {
                totalLines++
                continue
            }
            var curLineWidth = 0
            var linesForPara = 1
            val words = rawLine.split(' ')
            val spaceW = fm.charWidth(' ')
            for (wIdx in words.indices) {
                val word = words[wIdx]
                val wordW = fm.stringWidth(word)
                if (curLineWidth == 0) {
                    if (wordW > maxLineWidthPx) {
                        linesForPara += ((wordW - 1) / maxLineWidthPx)
                        curLineWidth = wordW % maxLineWidthPx
                        if (curLineWidth == 0) curLineWidth = maxLineWidthPx
                    } else {
                        curLineWidth = wordW
                    }
                } else if (curLineWidth + spaceW + wordW <= maxLineWidthPx) {
                    curLineWidth += spaceW + wordW
                } else {
                    linesForPara++
                    curLineWidth = wordW.coerceAtMost(maxLineWidthPx)
                }
            }
            totalLines += linesForPara
        }
        return totalLines.coerceAtLeast(1)
    }
}
