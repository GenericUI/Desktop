package com.nexustools.genericui.desktop.core

import com.nexustools.genericui.geometry.EdgeInsets
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size

/**
 * Base desktop native widget handle model encapsulating a platform-specific native pointer/handle
 * (`HWND` on Win32, `NSView*`/`NSWindow*` on macOS AppKit, `GtkWidget*` on GTK4, `QWidget*` on KDE),
 * native toolkit widget class name, property map, and child widget hierarchy.
 */
open class DesktopNativeWidget(
    val backendToolkit: String,
    val nativeClassName: String,
    val nativePointer: Long = nextPointerId()
) {
    var visible: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                bumpAttrMutationVersion()
            }
        }
    var enabled: Boolean = true
    var focusable: Boolean = true
    var opacity: Float = 1.0f
    var tooltipText: String? = null
    var preferredSize: Size? = null
        set(value) {
            if (field != value) {
                field = value
                bumpAttrMutationVersion()
            }
        }
    var bounds: Rect = Rect(0f, 0f, 0f, 0f)
    var padding: EdgeInsets = EdgeInsets.ZERO
        set(value) {
            if (field != value) {
                field = value
                bumpAttrMutationVersion()
            }
        }
    var margin: EdgeInsets = EdgeInsets.ZERO
        set(value) {
            if (field != value) {
                field = value
                bumpAttrMutationVersion()
            }
        }
    var disposed: Boolean = false
        set(value) {
            field = value
            if (value) {
                removeActiveVBlankWidget(this)
                onDisposed()
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
    private var rawAttrWriteVersion: Long = 0L
    private var cachedAttrSnapshotVer: Long = -1L
    private var cachedAttrSnapshot: Map<String, Any?> = emptyMap()
    private var cachedStringAttrVer: Long = -1L
    private var cachedStringAttrs: Map<String, String> = emptyMap()
    internal var cachedNormalizedTagName: String? = null
    internal var cachedNormalizedTagVer: Long = -1L

    fun resolveNormalizedTagName(computeRaw: () -> String): String {
        val ver = rawAttrWriteVersion
        val cached = cachedNormalizedTagName
        if (cached != null && cachedNormalizedTagVer == ver) {
            return cached
        }
        val normalized = computeRaw().trim().lowercase().removePrefix("genericui:")
        cachedNormalizedTagName = normalized
        cachedNormalizedTagVer = ver
        return normalized
    }

    fun snapshotAttributesMap(): Map<String, Any?> = withAttrLock {
        if (cachedAttrSnapshotVer == rawAttrWriteVersion) {
            cachedAttrSnapshot
        } else {
            val copy = LinkedHashMap(backingAttributes)
            cachedAttrSnapshot = copy
            cachedAttrSnapshotVer = rawAttrWriteVersion
            copy
        }
    }

    fun snapshotStringAttributesMap(): Map<String, String> = withAttrLock {
        if (cachedStringAttrVer == rawAttrWriteVersion) {
            cachedStringAttrs
        } else {
            val out = LinkedHashMap<String, String>()
            for ((k, v) in backingAttributes.entries) {
                when (v) {
                    is String -> out[k] = v
                    is Number -> out[k] = v.toString()
                    is Boolean -> out[k] = v.toString()
                    is com.nexustools.genericui.style.css.CssLength -> out[k] = v.toCssString()
                    is com.nexustools.genericui.style.css.CssAspectRatio -> out[k] = v.numericRatio.toString()
                }
            }
            cachedStringAttrs = out
            cachedStringAttrVer = rawAttrWriteVersion
            out
        }
    }

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
            var changed = false
            val isTransformKey = key == "transform" || key == "transform2D"
            val prev = withAttrLock {
                val existed = backingAttributes.containsKey(key)
                val old = backingAttributes.put(key, value)
                if (!existed || old != value) {
                    rawAttrWriteVersion++
                    changed = true
                }
                if (isTransformKey) {
                    hasTransformAttribute = backingAttributes["transform"] != null || backingAttributes["transform2D"] != null
                }
                old
            }
            if (changed && !isLayoutOutputOnlyKey(key)) {
                bumpAttrMutationVersion()
            }
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
            var hadKey = false
            val isTransformKey = key == "transform" || key == "transform2D"
            val prev = withAttrLock {
                hadKey = backingAttributes.containsKey(key)
                val old = backingAttributes.remove(key)
                if (hadKey) {
                    rawAttrWriteVersion++
                }
                if (isTransformKey) {
                    hasTransformAttribute = backingAttributes["transform"] != null || backingAttributes["transform2D"] != null
                }
                old
            }
            if (prev != null && !isLayoutOutputOnlyKey(key)) {
                bumpAttrMutationVersion()
            }
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
            withAttrLock {
                if (backingAttributes.isNotEmpty()) {
                    rawAttrWriteVersion++
                }
                hasTransformAttribute = false
                backingAttributes.clear()
            }
            bumpAttrMutationVersion()
            syncVBlankRegistration(null)
            resetOverlaySubtreeFlagRecursive()
        }
    }

    private fun isLayoutOutputOnlyKey(key: String): Boolean =
        key == "vblankDispatch" ||
            key == "contentOffsetX" ||
            key == "contentOffsetY" ||
            key == "tabContentOffsetX" ||
            key == "tabContentOffsetY" ||
            key == "resolvedTransform" ||
            key == "layoutBackendTarget" ||
            key == "firstActiveIndex" ||
            key == "lastActiveIndex" ||
            key == "leadingSpacerPx" ||
            key == "trailingSpacerPx" ||
            key == "totalContentExtentPx" ||
            key == "hostSyncedScrollOffsetPx" ||
            key == "hostSyncedScrollVer"

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
    var hasResponsiveLayoutClass: Boolean = false
    var hasFragmentClassToken: Boolean = false
    var hasHeightUtilityClass: Boolean = false
    var hasWidthUtilityClass: Boolean = false
    var hasRootConstraintUtilityClass: Boolean = false
    var hasTransformAttribute: Boolean = false
    var subtreeMutationVersion: Long = 1L
    var selfAttrMutationVersion: Long = 0L
    var subtreeAttrMutationVersion: Long = 0L
    internal var cachedOverlaySubtreeFlag: Int = -1
    var interactiveSurfaceSpec: DesktopInteractiveSurfaceSpec? = null
        set(value) {
            field = value
            if (value != null) {
                isIsolatedStageSubtreeRoot = true
            }
            resetOverlaySubtreeFlagRecursive()
            val ver = ++globalTreeMutationVersion
            ++globalLayoutMutationVersion
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
            ++globalLayoutMutationVersion
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
        internal set

    var onViewportRedisplayRequested: (() -> Unit)? = null
    var onSurfaceFrameReady: (() -> Unit)? = null

    @kotlin.concurrent.Volatile
    var children: List<DesktopNativeWidget> = emptyList()
        private set

    private val childrenSpinLock = kotlin.concurrent.AtomicInt(0)

    private inline fun <T> withChildrenLock(block: () -> T): T {
        while (!childrenSpinLock.compareAndSet(0, 1)) {
        }
        return try {
            block()
        } finally {
            childrenSpinLock.value = 0
        }
    }

    private fun resetOverlaySubtreeFlagRecursive() {
        cachedOverlaySubtreeFlag = -1
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

    private fun addSpaceTokens(raw: String, out: MutableSet<String>) {
        val len = raw.length
        if (len == 0) return
        var start = -1
        for (i in 0..len) {
            val ch = if (i < len) raw[i] else ' '
            if (ch.isWhitespace()) {
                if (start >= 0) {
                    out.add(if (start == 0 && i == len) raw else raw.substring(start, i))
                    start = -1
                }
            } else if (start < 0) {
                start = i
            }
        }
    }

    fun rebuildClassTokensCache(classPrefix: String = "") {
        resetOverlaySubtreeFlagRecursive()
        val out = LinkedHashSet<String>()
        if (classPrefix.isNotEmpty()) {
            addSpaceTokens(classPrefix, out)
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
            addSpaceTokens(bootstrapCls, out)
        }
        val attrCls = attributes["class"] as? String
        if (!attrCls.isNullOrEmpty()) {
            addSpaceTokens(attrCls, out)
        }
        cachedAllClassTokens = if (out.isEmpty()) emptySet() else out
        var hasResponsive = false
        var hasFragment = false
        var hasHeightUtil = false
        var hasWidthUtil = false
        var hasRootConstraint = false
        for (tok in out) {
            if (!hasResponsive) {
                val isResponsiveLayoutPrefix = tok.startsWith("d-", ignoreCase = true) ||
                    tok.startsWith("col-", ignoreCase = true) ||
                    tok.startsWith("flex-", ignoreCase = true) ||
                    tok.startsWith("order-", ignoreCase = true) ||
                    tok.startsWith("justify-content-", ignoreCase = true) ||
                    tok.startsWith("align-items-", ignoreCase = true) ||
                    tok.startsWith("align-self-", ignoreCase = true) ||
                    tok.startsWith("g-", ignoreCase = true) ||
                    tok.startsWith("gx-", ignoreCase = true) ||
                    tok.startsWith("gy-", ignoreCase = true) ||
                    tok.startsWith("gap-", ignoreCase = true) ||
                    tok.startsWith("row-cols-", ignoreCase = true)
                if (isResponsiveLayoutPrefix && (
                        tok.contains("-sm-", ignoreCase = true) ||
                            tok.contains("-md-", ignoreCase = true) ||
                            tok.contains("-lg-", ignoreCase = true) ||
                            tok.contains("-xl-", ignoreCase = true) ||
                            tok.contains("-xxl-", ignoreCase = true) ||
                            (tok.startsWith("col-", ignoreCase = true) && (
                                tok.endsWith("-sm", ignoreCase = true) ||
                                    tok.endsWith("-md", ignoreCase = true) ||
                                    tok.endsWith("-lg", ignoreCase = true) ||
                                    tok.endsWith("-xl", ignoreCase = true) ||
                                    tok.endsWith("-xxl", ignoreCase = true)
                                ))
                        )
                ) {
                    hasResponsive = true
                }
            }
            if (!hasFragment && tok.contains("fragment", ignoreCase = true)) {
                hasFragment = true
            }
            if (!hasHeightUtil && (
                    tok.equals("h-100", ignoreCase = true) ||
                        tok.equals("h-75", ignoreCase = true) ||
                        tok.equals("h-50", ignoreCase = true) ||
                        tok.equals("h-25", ignoreCase = true) ||
                        tok.equals("h-auto", ignoreCase = true)
                    )
            ) {
                hasHeightUtil = true
            }
            if (!hasWidthUtil && (
                    tok.equals("w-100", ignoreCase = true) ||
                        tok.equals("w-75", ignoreCase = true) ||
                        tok.equals("w-50", ignoreCase = true) ||
                        tok.equals("w-25", ignoreCase = true) ||
                        tok.equals("w-auto", ignoreCase = true)
                    )
            ) {
                hasWidthUtil = true
            }
            if (!hasRootConstraint && (
                    tok.equals("mw-100", ignoreCase = true) ||
                        tok.equals("mh-100", ignoreCase = true)
                    )
            ) {
                hasRootConstraint = true
            }
        }
        hasResponsiveLayoutClass = hasResponsive
        hasFragmentClassToken = hasFragment
        hasHeightUtilityClass = hasHeightUtil
        hasWidthUtilityClass = hasWidthUtil
        hasRootConstraintUtilityClass = hasRootConstraint
        selfAttrMutationVersion++
    }

    fun isSelfPointerEventsNone(): Boolean {
        val attr = attributes["pointerEvents"] as? String
        if (attr == "auto") return false
        if (attr == "none") return true
        return "pe-none" in cachedAllClassTokens
    }

    fun isSubtreeHitTestBlocked(): Boolean =
        !visible || disposed || (isSelfPointerEventsNone() && opacity <= 0.001f)

    fun effectiveZIndex(): Int =
        (attributes["zIndex"] as? Number)?.toInt()
            ?: (attributes["zIndex"] as? String)?.toIntOrNull()
            ?: 0

    fun effectivePosition(): String = when (val raw = attributes["position"] as? String) {
        "absolute", "relative", "fixed", "sticky" -> raw
        "static" -> "static"
        else -> when {
            "position-absolute" in cachedAllClassTokens -> "absolute"
            "position-relative" in cachedAllClassTokens -> "relative"
            "position-fixed" in cachedAllClassTokens -> "fixed"
            "position-sticky" in cachedAllClassTokens -> "sticky"
            else -> "static"
        }
    }

    fun isInsideIsolatedStageSubtree(): Boolean {
        val cached = cachedOverlaySubtreeFlag
        if (cached != -1) return cached == 1
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
                "gui-overlay-host" in tokens
            ) {
                found = true
                break
            }
            val htmlTag = cur.attributes["htmlTag"] as? String
            if (htmlTag != null && htmlTag.contains("overlay", ignoreCase = true)) {
                found = true
                break
            }
            cur = cur.parentWidget
        }
        if (found) {
            cachedOverlaySubtreeFlag = 1
        } else if (topRoot.nativeClassName.contains("window", ignoreCase = true)) {
            cachedOverlaySubtreeFlag = 0
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
        val alreadyAtTarget = withChildrenLock {
            val current = children
            if (child.parentWidget === this) {
                val targetIdx = if (index < 0 || index >= current.size) current.size - 1 else index
                if (targetIdx in current.indices && current[targetIdx] === child) {
                    return@withChildrenLock true
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
            children = next
            false
        }
        if (alreadyAtTarget) return
        if (canFastAssignNonOverlayChild(child)) {
            child.cachedOverlaySubtreeFlag = 0
        } else {
            child.resetOverlaySubtreeFlagRecursive()
        }
        notifyTreeInvalidated()
    }

    fun removeChildWidget(child: DesktopNativeWidget) {
        val (wasPresent, detachedFromSelf) = withChildrenLock {
            val current = children
            if (!current.contains(child)) {
                return@withChildrenLock false to false
            }
            val next = ArrayList<DesktopNativeWidget>(current.size)
            for (item in current) {
                if (item !== child) {
                    next.add(item)
                }
            }
            var detached = false
            if (child.parentWidget === this) {
                child.parentWidget = null
                detached = true
            }
            children = next
            true to detached
        }
        if (!wasPresent) return
        if (detachedFromSelf) {
            if (canFastAssignNonOverlayChild(child)) {
                child.cachedOverlaySubtreeFlag = 0
            } else {
                child.resetOverlaySubtreeFlagRecursive()
            }
        }
        notifyTreeInvalidated()
    }

    fun clearChildWidgets() {
        val previous = withChildrenLock {
            val prev = children
            for (item in prev) {
                if (item.parentWidget === this) {
                    item.parentWidget = null
                }
            }
            children = emptyList()
            prev
        }
        for (item in previous) {
            if (canFastAssignNonOverlayChild(item)) {
                item.cachedOverlaySubtreeFlag = 0
            } else {
                item.resetOverlaySubtreeFlagRecursive()
            }
        }
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

    fun isEffectivelyFocusable(): Boolean {
        var cur: DesktopNativeWidget? = this
        while (cur != null) {
            if (!cur.focusable) return false
            cur = cur.parentWidget
        }
        return true
    }

    internal open fun onEffectiveFocusableChanged(focusable: Boolean) {}

    internal open fun onDisposed() {}

    fun bumpAttrMutationVersion(): Long {
        val ver = ++globalAttrMutationVersion
        selfAttrMutationVersion = ver
        val inOverlay = isInsideIsolatedStageSubtree()
        var cur: DesktopNativeWidget? = this
        while (cur != null) {
            if (cur !== this && inOverlay && !cur.isInsideIsolatedStageSubtree()) {
                break
            }
            cur.subtreeAttrMutationVersion = ver
            cur = cur.parentWidget
        }
        return ver
    }

    fun bumpSubtreeMutationVersion(): Long {
        if (suppressTreeVersionBumpCount.value > 0) {
            return subtreeMutationVersion
        }
        val ver = ++globalTreeMutationVersion
        val inOverlay = isInsideIsolatedStageSubtree()
        if (activeViewportScrollScopeCount.value <= 0 && !inOverlay) {
            ++globalLayoutMutationVersion
        }
        var cur: DesktopNativeWidget? = this
        while (cur != null) {
            if (inOverlay && !cur.isInsideIsolatedStageSubtree()) {
                break
            }
            cur.subtreeMutationVersion = ver
            cur = cur.parentWidget
        }
        return ver
    }

    fun notifyTreeInvalidated(visualOnly: Boolean = false) {
        if (activeViewportScrollScopeCount.value > 0) {
            if (!visualOnly && suppressTreeVersionBumpCount.value <= 0) {
                bumpSubtreeMutationVersion()
            }
            var ancestorCheck: DesktopNativeWidget? = this.parentWidget
            while (ancestorCheck != null) {
                if (!ancestorCheck.visible) {
                    return
                }
                ancestorCheck = ancestorCheck.parentWidget
            }
            val directHook = activeViewportScrollHost?.onViewportRedisplayRequested
            if (directHook != null) {
                directHook.invoke()
            } else {
                var cur: DesktopNativeWidget? = this
                while (cur != null) {
                    val hook = cur.onViewportRedisplayRequested
                    if (hook != null) {
                        hook.invoke()
                        break
                    }
                    cur = cur.parentWidget
                }
            }
            return
        }
        val inOverlay = isInsideIsolatedStageSubtree()
        if (!visualOnly) {
            bumpSubtreeMutationVersion()
        }
        var ancestorCheck: DesktopNativeWidget? = this.parentWidget
        while (ancestorCheck != null) {
            if (!ancestorCheck.visible) {
                return
            }
            ancestorCheck = ancestorCheck.parentWidget
        }
        if (inOverlay && (overlayLayoutInvalidationListener != null || overlayInvalidationListener != null)) {
            if (!visualOnly && overlayLayoutInvalidationListener != null) {
                overlayLayoutInvalidationListener?.invoke(this)
            } else {
                overlayInvalidationListener?.invoke(this)
            }
        } else {
            if (!visualOnly) {
                ++globalNonOverlayMutationVersion
            }
            onNativeTreeInvalidated?.invoke()
            onNativeTreeInvalidatedWidget?.invoke(this)
        }
    }

    companion object {
        private val WHITESPACE_REGEX = Regex("\\s+")
        private var pointerCounter: Long = 0x1000_0000L
        var globalTreeMutationVersion: Long = 1L
        var globalAttrMutationVersion: Long = 1L
        var globalLayoutMutationVersion: Long = 1L
        var globalNonOverlayMutationVersion: Long = 1L
        var globalViewportMutationVersion: Long = 1_000_000_000L
        val activeViewportScrollScopeCount: kotlin.concurrent.AtomicInt = kotlin.concurrent.AtomicInt(0)
        val suppressTreeVersionBumpCount: kotlin.concurrent.AtomicInt = kotlin.concurrent.AtomicInt(0)
        var activeViewportScrollHost: DesktopNativeWidget? = null
        var onNativeTreeInvalidated: (() -> Unit)? = null
        var onNativeTreeInvalidatedWidget: ((DesktopNativeWidget) -> Unit)? = null
        var onCursorUpdated: (() -> Unit)? = null
        var overlayInvalidationListener: ((DesktopNativeWidget) -> Unit)? = null
        var overlayLayoutInvalidationListener: ((DesktopNativeWidget) -> Unit)? = null
        @kotlin.concurrent.Volatile
        var onVBlankWidgetsChanged: (() -> Unit)? = null
        @kotlin.concurrent.Volatile
        var onSurfaceFrameReadyGlobal: ((DesktopNativeWidget) -> Unit)? = null
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
            onVBlankWidgetsChanged?.invoke()
        }

        internal fun removeActiveVBlankWidget(widget: DesktopNativeWidget) {
            withVBlankLock { activeVBlankWidgets.remove(widget) }
            onVBlankWidgetsChanged?.invoke()
        }

        fun notifyVBlankWidgetsChanged() {
            onVBlankWidgetsChanged?.invoke()
        }

        fun notifySurfaceFrameReady(widget: DesktopNativeWidget) {
            widget.onSurfaceFrameReady?.invoke()
            onSurfaceFrameReadyGlobal?.invoke(widget)
        }

        fun resolvePreferredVBlankRateHz(): Double? {
            val snapshot = withVBlankLock {
                if (activeVBlankWidgets.isEmpty()) emptyList() else activeVBlankWidgets.toList()
            }
            if (snapshot.isEmpty()) return null
            var maxPositiveRate = 0.0
            var sawZeroOrNegative = false
            for (widget in snapshot) {
                val provider = widget.interactiveSurfaceSpec?.preferredFrameRateHz ?: return null
                val rate = provider.invoke() ?: return null
                if (!rate.isFinite()) return null
                if (rate > 0.0) {
                    if (rate > maxPositiveRate) {
                        maxPositiveRate = rate
                    }
                } else {
                    sawZeroOrNegative = true
                }
            }
            return when {
                maxPositiveRate > 0.0 -> maxPositiveRate
                sawZeroOrNegative -> 0.0
                else -> null
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
