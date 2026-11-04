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

    val attributes: MutableMap<String, Any?> = linkedMapOf("antialiasing" to true)
    val styleProperties: MutableMap<String, String> = linkedMapOf()
    var cachedAllClassTokens: Set<String> = emptySet()
    var subtreeMutationVersion: Long = 1L
    var parentWidget: DesktopNativeWidget? = null
        private set
    var children: List<DesktopNativeWidget> = emptyList()
        private set

    fun rebuildClassTokensCache(classPrefix: String = "") {
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
        val styleCls = styleProperties["class"]
        if (!styleCls.isNullOrEmpty()) {
            for (tok in styleCls.split(WHITESPACE_REGEX)) {
                if (tok.isNotEmpty()) out.add(tok)
            }
        }
        cachedAllClassTokens = if (out.isEmpty()) emptySet() else out
    }

    private fun isInsideVideoOverlaySubtree(): Boolean {
        var cur: DesktopNativeWidget? = this
        while (cur != null) {
            if (cur.nativeClassName.contains("overlay", ignoreCase = true)) return true
            val tokens = cur.cachedAllClassTokens
            if (
                "episodecaster-overlay-hud" in tokens ||
                "gui-overlay" in tokens ||
                "gui-overlay-host" in tokens ||
                "player-overlay-top" in tokens ||
                "player-overlay-bottom" in tokens ||
                "player-overlay-center" in tokens ||
                "player-controls-pill" in tokens ||
                "player-time-pill" in tokens ||
                "expanding-volume-pill" in tokens ||
                "player-popup-menu" in tokens
            ) {
                return true
            }
            val htmlTag = cur.attributes["htmlTag"] as? String
            if (htmlTag != null && htmlTag.contains("overlay", ignoreCase = true)) {
                return true
            }
            cur = cur.parentWidget
        }
        return false
    }

    fun addChildWidget(child: DesktopNativeWidget, index: Int = -1) {
        val current = children
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
        }
        children = next
        notifyTreeInvalidated()
    }

    fun clearChildWidgets() {
        for (item in children) {
            if (item.parentWidget === this) {
                item.parentWidget = null
            }
        }
        children = emptyList()
        notifyTreeInvalidated()
    }

    fun notifyTreeInvalidated() {
        val ver = ++globalTreeMutationVersion
        var cur: DesktopNativeWidget? = this
        while (cur != null) {
            cur.subtreeMutationVersion = ver
            cur = cur.parentWidget
        }
        if (isInsideVideoOverlaySubtree() && overlayInvalidationListener != null) {
            overlayInvalidationListener?.invoke(this)
        } else {
            ++globalNonOverlayMutationVersion
            onNativeTreeInvalidated?.invoke()
        }
    }

    companion object {
        private val WHITESPACE_REGEX = Regex("\\s+")
        private var pointerCounter: Long = 0x1000_0000L
        var globalTreeMutationVersion: Long = 1L
        var globalNonOverlayMutationVersion: Long = 1L
        var onNativeTreeInvalidated: (() -> Unit)? = null
        var overlayInvalidationListener: ((DesktopNativeWidget) -> Unit)? = null

        fun nextPointerId(): Long {
            pointerCounter += 0x10L
            return pointerCounter
        }
    }
}
