package com.nexustools.genericui.desktop.core

import com.nexustools.genericui.geometry.EdgeInsets
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size

/**
 * Base desktop native widget handle model encapsulating a platform-specific native pointer/handle
 * (`HWND` on Win32, `NSView*`/`NSWindow*` on macOS AppKit, `GtkWidget*` on GTK4, `QWidget*` on Qt6),
 * native toolkit widget class name, property map, and child widget hierarchy.
 */
open class DesktopNativeWidget(
    val backendToolkit: String,
    val nativeClassName: String,
    val nativePointer: Long = nextPointerId()
) {
    var visible: Boolean = true
    var enabled: Boolean = true
    var opacity: Float = 1.0f
    var tooltipText: String? = null
    var preferredSize: Size? = null
    var bounds: Rect = Rect(0f, 0f, 0f, 0f)
    var padding: EdgeInsets = EdgeInsets.ZERO
    var margin: EdgeInsets = EdgeInsets.ZERO
    var disposed: Boolean = false
        set(value) {
            field = value
            if (value) {
                removeActiveVBlankWidget(this)
            }
        }

    private val attrSpinLock = kotlin.concurrent.AtomicInt(0)

    private inline fun <T> withAttrLock(block: () -> T): T {
        while (!attrSpinLock.compareAndSet(0, 1)) {
            // brief spin across Logic/Coroutine vs UI thread
        }
        return try {
            block()
        } finally {
            attrSpinLock.value = 0
        }
    }

    private val backingAttributes: LinkedHashMap<String, Any?> = linkedMapOf("antialiasing" to true)

    val attributes: MutableMap<String, Any?> = object : MutableMap<String, Any?> {
        override val size: Int
            get() = withAttrLock { backingAttributes.size }

        override fun isEmpty(): Boolean =
            withAttrLock { backingAttributes.isEmpty() }

        override fun containsKey(key: String): Boolean =
            withAttrLock { backingAttributes.containsKey(key) }

        override fun containsValue(value: Any?): Boolean =
            withAttrLock { backingAttributes.containsValue(value) }

        override fun get(key: String): Any? =
            withAttrLock { backingAttributes[key] }

        override val keys: MutableSet<String>
            get() = withAttrLock { LinkedHashSet(backingAttributes.keys) }

        override val values: MutableCollection<Any?>
            get() = withAttrLock { ArrayList(backingAttributes.values) }

        override val entries: MutableSet<MutableMap.MutableEntry<String, Any?>>
            get() = withAttrLock { LinkedHashMap(backingAttributes).entries }

        override fun putAll(from: Map<out String, Any?>) {
            for ((k, v) in from) {
                put(k, v)
            }
        }

        override fun put(key: String, value: Any?): Any? {
            val prev = withAttrLock { backingAttributes.put(key, value) }
            if (key == "vblankDispatch") {
                syncVBlankRegistration(value)
            }
            if (
                key == "id" ||
                key == "class" ||
                key == "bootstrapClass" ||
                key == "enumClasses" ||
                key == "hudVisible" ||
                key == "data-overlay-host" ||
                key == "data-overlay-root" ||
                key == "data-overlay-layer" ||
                key == "data-overlay-role"
            ) {
                resetOverlaySubtreeFlagRecursive()
            }
            return prev
        }

        override fun remove(key: String): Any? {
            val prev = withAttrLock { backingAttributes.remove(key) }
            if (key == "vblankDispatch") {
                syncVBlankRegistration(null)
            }
            if (
                key == "id" ||
                key == "class" ||
                key == "bootstrapClass" ||
                key == "enumClasses" ||
                key == "hudVisible" ||
                key == "data-overlay-host" ||
                key == "data-overlay-root" ||
                key == "data-overlay-layer" ||
                key == "data-overlay-role"
            ) {
                resetOverlaySubtreeFlagRecursive()
            }
            return prev
        }

        override fun clear() {
            withAttrLock { backingAttributes.clear() }
            syncVBlankRegistration(null)
            resetOverlaySubtreeFlagRecursive()
        }
    }

    @Suppress("UNCHECKED_CAST")
    var vblankDispatch: ((Double?) -> Unit)?
        get() = withAttrLock { backingAttributes["vblankDispatch"] } as? ((Double?) -> Unit)
        set(value) {
            if (value != null) {
                attributes["vblankDispatch"] = value
            } else {
                attributes.remove("vblankDispatch")
            }
        }

    var cachedAllClassTokens: Set<String> = emptySet()
    var subtreeMutationVersion: Long = 1L
    internal var cachedOverlaySubtreeFlag: Int = -1
    private var cachedOverlayClassifierGen: Int = -1
    var interactiveSurfaceSpec: DesktopInteractiveSurfaceSpec? = null
        set(value) {
            field = value
            if (value != null) {
                isIsolatedStageSubtreeRoot = true
            }
            resetOverlaySubtreeFlagRecursive()
            val ver = ++globalTreeMutationVersion
            ++globalNonOverlayMutationVersion
            var cur: DesktopNativeWidget? = this
            while (cur != null) {
                cur.subtreeMutationVersion = ver
                cur = cur.parentWidget
            }
        }
    var stageOverlaySpec: DesktopStageOverlaySpec? = null
        set(value) {
            field = value
            if (value != null) {
                isIsolatedStageSubtreeRoot = true
            }
            resetOverlaySubtreeFlagRecursive()
            val ver = ++globalTreeMutationVersion
            ++globalNonOverlayMutationVersion
            var cur: DesktopNativeWidget? = this
            while (cur != null) {
                cur.subtreeMutationVersion = ver
                cur = cur.parentWidget
            }
        }
    var isIsolatedStageSubtreeRoot: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                resetOverlaySubtreeFlagRecursive()
            }
        }
    var parentWidget: DesktopNativeWidget? = null
        private set

    @kotlin.concurrent.Volatile
    var children: List<DesktopNativeWidget> = emptyList()
        private set

    private fun resetOverlaySubtreeFlagRecursive() {
        cachedOverlaySubtreeFlag = -1
        cachedOverlayClassifierGen = -1
        for (child in children) {
            child.resetOverlaySubtreeFlagRecursive()
        }
    }

    private fun syncVBlankRegistration(value: Any?) {
        if (value != null && !disposed) {
            addActiveVBlankWidget(this)
        } else {
            removeActiveVBlankWidget(this)
        }
    }

    fun rebuildClassTokensCache(classPrefix: String = "") {
        resetOverlaySubtreeFlagRecursive()
        val out = LinkedHashSet<String>()
        if (classPrefix.isNotEmpty()) {
            for (tok in classPrefix.split(WHITESPACE_REGEX)) {
                if (tok.isNotEmpty()) out.add(tok)
            }
        }
        val implTokens = (attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>)?.getClassTokensSet()
        if (implTokens != null) {
            out.addAll(implTokens)
        }
        val enumSet = attributes["enumClasses"] as? Iterable<*>
        if (enumSet != null) {
            for (item in enumSet) {
                val s = item as? String
                if (!s.isNullOrEmpty()) out.add(s)
            }
        }
        val bootstrapCls = attributes["bootstrapClass"] as? String
        if (!bootstrapCls.isNullOrEmpty()) {
            for (tok in bootstrapCls.split(WHITESPACE_REGEX)) {
                if (tok.isNotEmpty()) out.add(tok)
            }
        }
        val attrCls = attributes["class"] as? String
        if (!attrCls.isNullOrEmpty()) {
            for (tok in attrCls.split(WHITESPACE_REGEX)) {
                if (tok.isNotEmpty()) out.add(tok)
            }
        }
        cachedAllClassTokens = if (out.isEmpty()) emptySet() else out
    }

    fun isInsideIsolatedStageSubtree(): Boolean {
        val currentGen = classifierGeneration
        val cached = cachedOverlaySubtreeFlag
        if (cached != -1 && cachedOverlayClassifierGen == currentGen) return cached == 1
        var cur: DesktopNativeWidget? = this
        var topRoot: DesktopNativeWidget = this
        var found = false
        while (cur != null) {
            topRoot = cur
            if (cur.isIsolatedStageSubtreeRoot ||
                cur.interactiveSurfaceSpec != null ||
                cur.stageOverlaySpec != null ||
                cur.nativeClassName.contains("overlay", ignoreCase = true) ||
                cur.attributes["data-overlay-host"] == "true" ||
                cur.attributes["data-overlay-root"] == "true" ||
                cur.attributes["data-overlay-layer"] == "true" ||
                cur.attributes.containsKey(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE)
            ) {
                found = true
                break
            }
            if (cur.cachedAllClassTokens.isEmpty() && (
                    cur.attributes.containsKey("class") ||
                        cur.attributes.containsKey("bootstrapClass") ||
                        cur.attributes.containsKey("enumClasses") ||
                        cur.attributes.containsKey("nativeImpl")
                )
            ) {
                cur.rebuildClassTokensCache(
                    (cur.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>)?.componentClassPrefix ?: ""
                )
            }
            val tokens = cur.cachedAllClassTokens
            if (
                "gui-overlay" in tokens ||
                "gui-overlay-host" in tokens ||
                "gui-text-bubble" in tokens ||
                "gui-text-bubble-label" in tokens
            ) {
                found = true
                break
            }
            val htmlTag = cur.attributes["htmlTag"] as? String
            if (htmlTag != null && htmlTag.contains("overlay", ignoreCase = true)) {
                found = true
                break
            }
            val classifiers = stageSubtreeClassifiers
            if (classifiers.isNotEmpty()) {
                val candidate = cur
                var matchedClassifier = false
                for (classifier in classifiers) {
                    if (classifier.isIsolatedStageWidget(candidate)) {
                        matchedClassifier = true
                        break
                    }
                }
                if (matchedClassifier) {
                    found = true
                    break
                }
            }
            cur = cur.parentWidget
        }
        if (found) {
            cachedOverlaySubtreeFlag = 1
            cachedOverlayClassifierGen = currentGen
        } else if (topRoot.nativeClassName.contains("window", ignoreCase = true)) {
            cachedOverlaySubtreeFlag = 0
            cachedOverlayClassifierGen = currentGen
        }
        return found
    }

    private fun canFastAssignNonOverlayChild(child: DesktopNativeWidget): Boolean =
        this.attributes["isLazyList"] == true &&
            !child.isIsolatedStageSubtreeRoot &&
            child.interactiveSurfaceSpec == null &&
            child.stageOverlaySpec == null &&
            child.children.isEmpty()

    fun addChildWidget(child: DesktopNativeWidget, index: Int = -1) {
        val oldParent = child.parentWidget
        if (oldParent != null && oldParent !== this) {
            oldParent.removeChildWidget(child)
        }
        val current = children
        if (oldParent === this) {
            val targetIdx = if (index < 0 || index >= current.size) current.size - 1 else index
            if (targetIdx in current.indices && current[targetIdx] === child) {
                return
            }
        }
        val next = ArrayList<DesktopNativeWidget>(current.size + 1)
        for (item in current) {
            if (item !== child) {
                next.add(item)
            }
        }
        if (index < 0 || index >= next.size) {
            next.add(child)
        } else {
            next.add(index, child)
        }
        child.parentWidget = this
        if (canFastAssignNonOverlayChild(child)) {
            child.cachedOverlaySubtreeFlag = 0
            child.cachedOverlayClassifierGen = classifierGeneration
        } else {
            child.resetOverlaySubtreeFlagRecursive()
        }
        children = next
        notifyTreeInvalidated()
    }

    fun removeChildWidget(child: DesktopNativeWidget) {
        val current = children
        if (!current.contains(child)) return
        val next = ArrayList<DesktopNativeWidget>(current.size)
        for (item in current) {
            if (item !== child) {
                next.add(item)
            }
        }
        if (child.parentWidget === this) {
            child.parentWidget = null
            if (canFastAssignNonOverlayChild(child)) {
                child.cachedOverlaySubtreeFlag = 0
                child.cachedOverlayClassifierGen = classifierGeneration
            } else {
                child.resetOverlaySubtreeFlagRecursive()
            }
        }
        children = next
        notifyTreeInvalidated()
    }

    fun clearChildWidgets() {
        for (item in children) {
            if (item.parentWidget === this) {
                item.parentWidget = null
                if (canFastAssignNonOverlayChild(item)) {
                    item.cachedOverlaySubtreeFlag = 0
                    item.cachedOverlayClassifierGen = classifierGeneration
                } else {
                    item.resetOverlaySubtreeFlagRecursive()
                }
            }
        }
        children = emptyList()
        notifyTreeInvalidated()
    }

    fun containsNativeWidget(candidate: Any?): Boolean {
        val w = when (candidate) {
            is DesktopNativeWidget -> candidate
            is DesktopBaseNativeImpl<*> -> candidate.nativeWidget
            is com.nexustools.genericui.core.NativeImpl<*> -> candidate.nativeWidget as? DesktopNativeWidget
            is com.nexustools.genericui.core.AbstractComponent -> (candidate.nativeImpl as? DesktopBaseNativeImpl<*>)?.nativeWidget
            else -> null
        } ?: return false
        var cur: DesktopNativeWidget? = w
        while (cur != null) {
            if (cur === this) return true
            cur = cur.parentWidget
        }
        return false
    }

    fun notifyTreeInvalidated(visualOnly: Boolean = false) {
        var ancestorCheck: DesktopNativeWidget? = this.parentWidget
        while (ancestorCheck != null) {
            if (!ancestorCheck.visible) {
                return
            }
            ancestorCheck = ancestorCheck.parentWidget
        }
        val ver = ++globalTreeMutationVersion
        val inOverlay = isInsideIsolatedStageSubtree()
        if (!visualOnly) {
            var cur: DesktopNativeWidget? = this
            while (cur != null) {
                if (inOverlay && !cur.isInsideIsolatedStageSubtree()) {
                    break
                }
                cur.subtreeMutationVersion = ver
                if (cur.attributes["isLazyList"] == true) {
                    break
                }
                if ("gui-hybrid-split-navigation" in cur.cachedAllClassTokens) {
                    cur.parentWidget?.subtreeMutationVersion = ver
                    break
                }
                cur = cur.parentWidget
            }
        }
        if (inOverlay && overlayInvalidationListener != null) {
            overlayInvalidationListener?.invoke(this)
        } else {
            if (!visualOnly) {
                ++globalNonOverlayMutationVersion
            }
            onNativeTreeInvalidated?.invoke()
        }
    }

    companion object {
        private val WHITESPACE_REGEX = Regex("\\s+")
        private var pointerCounter: Long = 0x1000_0000L
        private val stageSubtreeClassifiers: MutableList<DesktopStageSubtreeClassifier> = mutableListOf()
        private var classifierGeneration: Int = 0
        var globalTreeMutationVersion: Long = 1L
        var globalNonOverlayMutationVersion: Long = 1L
        var onNativeTreeInvalidated: (() -> Unit)? = null
        var onCursorUpdated: (() -> Unit)? = null
        var overlayInvalidationListener: ((DesktopNativeWidget) -> Unit)? = null
        val activeVBlankWidgets: MutableSet<DesktopNativeWidget> = linkedSetOf()
        private val vblankSpinLock = kotlin.concurrent.AtomicInt(0)
        private val pointerIdSpinLock = kotlin.concurrent.AtomicInt(0)
        internal var activePopupHitTestWidget: DesktopNativeWidget? = null
        internal var activePopupHitTestX: Int = 0
        internal var activePopupHitTestY: Int = 0

        private inline fun <T> withVBlankLock(block: () -> T): T {
            while (!vblankSpinLock.compareAndSet(0, 1)) {}
            return try {
                block()
            } finally {
                vblankSpinLock.value = 0
            }
        }

        internal fun addActiveVBlankWidget(widget: DesktopNativeWidget) {
            withVBlankLock { activeVBlankWidgets.add(widget) }
        }

        internal fun removeActiveVBlankWidget(widget: DesktopNativeWidget) {
            withVBlankLock { activeVBlankWidgets.remove(widget) }
        }

        fun registerStageSubtreeClassifier(classifier: DesktopStageSubtreeClassifier) {
            if (!stageSubtreeClassifiers.contains(classifier)) {
                stageSubtreeClassifiers.add(classifier)
                classifierGeneration++
            }
        }

        private fun resolveOwnerOrSelf(targetWidget: DesktopNativeWidget?): Any? {
            var cur: DesktopNativeWidget? = targetWidget
            while (cur != null) {
                val owner = cur.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
                if (owner != null) return owner
                cur = cur.parentWidget
            }
            return targetWidget
        }

        fun interceptPopupPointerDown(x: Float, y: Float, targetWidget: DesktopNativeWidget?): Boolean {
            val checkX = if (x.isNaN()) 0f else x
            val checkY = if (y.isNaN()) 0f else y
            activePopupHitTestWidget = targetWidget
            activePopupHitTestX = checkX.toInt()
            activePopupHitTestY = checkY.toInt()
            val targetObj = resolveOwnerOrSelf(targetWidget)
            return try {
                com.nexustools.genericui.event.PopupMouseCaptureManager.interceptPointerDown(x, y, targetObj)
            } finally {
                activePopupHitTestWidget = null
            }
        }

        fun interceptPopupPointerUp(x: Float, y: Float, targetWidget: DesktopNativeWidget?): Boolean {
            val checkX = if (x.isNaN()) 0f else x
            val checkY = if (y.isNaN()) 0f else y
            activePopupHitTestWidget = targetWidget
            activePopupHitTestX = checkX.toInt()
            activePopupHitTestY = checkY.toInt()
            val targetObj = resolveOwnerOrSelf(targetWidget)
            return try {
                com.nexustools.genericui.event.PopupMouseCaptureManager.interceptPointerUp(x, y, targetObj)
            } finally {
                activePopupHitTestWidget = null
            }
        }

        fun interceptPopupClick(x: Float, y: Float, targetWidget: DesktopNativeWidget?): Boolean {
            val checkX = if (x.isNaN()) 0f else x
            val checkY = if (y.isNaN()) 0f else y
            activePopupHitTestWidget = targetWidget
            activePopupHitTestX = checkX.toInt()
            activePopupHitTestY = checkY.toInt()
            val targetObj = resolveOwnerOrSelf(targetWidget)
            return try {
                com.nexustools.genericui.event.PopupMouseCaptureManager.interceptClick(x, y, targetObj)
            } finally {
                activePopupHitTestWidget = null
            }
        }

        fun activeVBlankWidgetsSnapshot(): List<DesktopNativeWidget> =
            withVBlankLock {
                if (activeVBlankWidgets.isEmpty()) emptyList() else activeVBlankWidgets.toList()
            }

        fun nextPointerId(): Long {
            while (!pointerIdSpinLock.compareAndSet(0, 1)) {}
            return try {
                pointerCounter += 0x10L
                pointerCounter
            } finally {
                pointerIdSpinLock.value = 0
            }
        }
    }
}
