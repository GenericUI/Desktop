@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.impl

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTabNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.component.tabs.TabIconPlacement
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.gtk.widget.GtkSurfaceRenderHelper
import com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge
import com.nexustools.genericui.linux.LinuxGlibGioBindings
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlin.math.ceil
import kotlin.math.max

/**
 * Retained GTK4 tab chip widget backing `AdwTabPage` / custom `Tab` headers inside [GtkTabContainerNativeImpl].
 */
open class GtkTabNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("AdwTabPage"),
    closable: Boolean = false
) : DesktopTabNativeImpl<GtkWidgetHandle>(nativeWidget, closable) {

    var onHeaderChanged: (() -> Unit)? = null
    var onSelectRequested: (() -> Unit)? = null
    var onCloseActionRequested: (() -> Unit)? = null

    private var labelPtr: COpaquePointer? = null
    private var badgePtr: COpaquePointer? = null
    private var closeBtnPtr: COpaquePointer? = null
    private var clickRef: StableRef<GtkTabNativeImpl>? = null

    init {
        nativeWidget.ownerImpl = this
        nativeWidget.widgetMaterializer = { materializeTabChip() }
    }

    private fun materializeTabChip(): COpaquePointer? {
        nativeWidget.realGtkWidgetPtr?.let { return it }
        if (!Gtk4DynamicBindings.ensureGtkInitialized()) return null

        val row = Gtk4DynamicBindings.gtkBoxNew(Gtk4DynamicBindings.GTK_ORIENTATION_HORIZONTAL, 6) ?: return null
        Gtk4DynamicBindings.gtkWidgetAddCssClass(row, "tab-chip")
        Gtk4DynamicBindings.gtkWidgetSetMargins(row, 6, 6, 10, 10)

        clickRef?.dispose()
        val ref = StableRef.create(this)
        clickRef = ref

        val customPtr = (customHeaderImpl?.nativeWidget as? GtkWidgetHandle)?.ensureLiveWidgetPtr()
        if (customPtr != null) {
            Gtk4DynamicBindings.gtkBoxAppend(row, customPtr)
            labelPtr = null
            badgePtr = null
        } else {
            val btn = Gtk4DynamicBindings.gtkButtonNew()
            if (btn != null) {
                Gtk4DynamicBindings.gtkWidgetAddCssClass(btn, "flat")
                Gtk4DynamicBindings.gtkButtonSetLabel(btn, formattedTitle())
                LinuxGlibGioBindings.signalConnect(btn, "clicked", tabBtnClickedCb.reinterpret(), ref.asCPointer())
                Gtk4DynamicBindings.gtkBoxAppend(row, btn)
                labelPtr = btn
            }
            val badgeText = nativeWidget.attributes["badgeText"] as? String
            if (!badgeText.isNullOrBlank()) {
                val bLbl = Gtk4DynamicBindings.gtkLabelNew(badgeText)
                if (bLbl != null) {
                    Gtk4DynamicBindings.gtkWidgetAddCssClass(bLbl, "badge")
                    Gtk4DynamicBindings.gtkBoxAppend(row, bLbl)
                    badgePtr = bLbl
                }
            }
        }

        val isClosable = nativeWidget.attributes["closable"] as? Boolean ?: false
        if (isClosable) {
            val closeBtn = Gtk4DynamicBindings.gtkButtonNewWithLabel("×")
            if (closeBtn != null) {
                Gtk4DynamicBindings.gtkWidgetAddCssClass(closeBtn, "flat")
                Gtk4DynamicBindings.gtkWidgetAddCssClass(closeBtn, "circular")
                LinuxGlibGioBindings.signalConnect(closeBtn, "clicked", tabCloseClickedCb.reinterpret(), ref.asCPointer())
                Gtk4DynamicBindings.gtkBoxAppend(row, closeBtn)
                closeBtnPtr = closeBtn
            }
        }

        GtkWidgetLifecycleBridge.attachControllersAndState(
            impl = this,
            rootPtr = row,
            focusTargetPtr = labelPtr ?: row,
            isTextInput = false,
            dispatchClickOnPrimaryRelease = true,
            attachClickGesture = true
        )
        syncSelectedAppearance(row)
        return row
    }

    private fun formattedTitle(): String {
        val rawTitle = (nativeWidget.attributes["title"] as? String).orEmpty()
        val dirty = nativeWidget.attributes["dirty"] as? Boolean == true
        val pinned = nativeWidget.attributes["pinned"] as? Boolean == true
        val icon = (nativeWidget.attributes["icon"] as? String)?.takeIf { it.isNotBlank() }
        val prefix = buildString {
            if (pinned) append("📌 ")
            if (icon != null) append("$icon ")
        }
        val suffix = if (dirty) " •" else ""
        return "$prefix$rawTitle$suffix"
    }

    private fun syncSelectedAppearance(ptr: COpaquePointer? = nativeWidget.realGtkWidgetPtr) {
        ptr ?: return
        val selected = nativeWidget.attributes["selected"] as? Boolean == true
        if (selected) {
            Gtk4DynamicBindings.gtkWidgetAddCssClass(ptr, "active")
            Gtk4DynamicBindings.gtkWidgetAddCssClass(ptr, "suggested-action")
        } else {
            Gtk4DynamicBindings.gtkWidgetRemoveCssClass(ptr, "active")
            Gtk4DynamicBindings.gtkWidgetRemoveCssClass(ptr, "suggested-action")
        }
        labelPtr?.let { btn ->
            Gtk4DynamicBindings.gtkButtonSetLabel(btn, formattedTitle())
        }
        Gtk4DynamicBindings.gtkWidgetQueueDraw(ptr)
    }

    private fun rebuildChipIfLive() {
        val oldPtr = nativeWidget.realGtkWidgetPtr ?: run {
            onHeaderChanged?.invoke()
            return
        }
        nativeWidget.realGtkWidgetPtr = null
        val newPtr = materializeTabChip()
        if (newPtr != null) {
            GtkWidgetLifecycleBridge.swapLiveWidgetInParent(this, oldPtr, newPtr)
        }
        onHeaderChanged?.invoke()
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val customBase = customHeaderImpl as? DesktopBaseNativeImpl<*>
        if (customBase != null) {
            val cs = customBase.measureIntrinsicSize(maxWidth, maxHeight)
            val closeW = if (nativeWidget.attributes["closable"] as? Boolean == true) 26 else 0
            return Size(max(44, cs.width + 20 + closeW), max(34, cs.height + 10))
        }
        val (tw, th) = GtkSurfaceRenderHelper.measureTextSize(formattedTitle().ifEmpty { "Tab" }, 13.5f)
        val badgeW = (nativeWidget.attributes["badgeText"] as? String)?.let { it.length * 7 + 16 } ?: 0
        val closeW = if (nativeWidget.attributes["closable"] as? Boolean == true) 26 else 0
        return Size(max(64, tw + 28 + badgeW + closeW), max(34, th + 14))
    }

    override fun setTitleNative(title: String) {
        super.setTitleNative(title)
        syncSelectedAppearance()
        onHeaderChanged?.invoke()
    }

    override fun setIconNative(icon: String?) {
        super.setIconNative(icon)
        syncSelectedAppearance()
        onHeaderChanged?.invoke()
    }

    override fun setClosableNative(closable: Boolean) {
        super.setClosableNative(closable)
        rebuildChipIfLive()
    }

    override fun setDirtyNative(dirty: Boolean) {
        super.setDirtyNative(dirty)
        syncSelectedAppearance()
        onHeaderChanged?.invoke()
    }

    override fun setPinnedNative(pinned: Boolean) {
        super.setPinnedNative(pinned)
        syncSelectedAppearance()
        onHeaderChanged?.invoke()
    }

    override fun setBadgeNative(badgeText: String?, variantCss: String?) {
        super.setBadgeNative(badgeText, variantCss)
        rebuildChipIfLive()
    }

    override fun setSelectedNative(selected: Boolean) {
        super.setSelectedNative(selected)
        syncSelectedAppearance()
    }

    override fun setCustomHeaderComponentNative(customImpl: NativeImpl<*>?) {
        super.setCustomHeaderComponentNative(customImpl)
        rebuildChipIfLive()
    }

    override fun setType(type: BootstrapType?) {
        super.setType(type)
        onHeaderChanged?.invoke()
    }

    override fun setBackgroundType(type: BootstrapType?) {
        super.setBackgroundType(type)
        onHeaderChanged?.invoke()
    }

    override fun applyComponentBootstrap(bootstrap: ComponentBootstrap) {
        super.applyComponentBootstrap(bootstrap)
        onHeaderChanged?.invoke()
    }

    override fun applyComponentStyle(style: ComponentStyle) {
        super.applyComponentStyle(style)
        onHeaderChanged?.invoke()
    }

    override fun applyComputedStyle(style: ComputedStyle) {
        super.applyComputedStyle(style)
        onHeaderChanged?.invoke()
    }

    override fun onNativeBoundsChanged(rect: Rect) = GtkWidgetLifecycleBridge.onBoundsChanged(this, rect)
    override fun onNativeVisibilityChanged(visible: Boolean) = GtkWidgetLifecycleBridge.onVisibilityChanged(this, visible)
    override fun onNativeEnabledChanged(enabled: Boolean) = GtkWidgetLifecycleBridge.onEnabledChanged(this, enabled)
    override fun onNativeOpacityChanged(opacity: Float) = GtkWidgetLifecycleBridge.onOpacityChanged(this, opacity)
    override fun onNativeTooltipChanged(tooltip: String?) = GtkWidgetLifecycleBridge.onTooltipChanged(this, tooltip)
    override fun onNativeCursorChanged(cursor: CssCursorValue?) = GtkWidgetLifecycleBridge.onCursorChanged(this, cursor)
    override fun onNativePointerEventsChanged(pointerEventsNone: Boolean) = GtkWidgetLifecycleBridge.onPointerEventsChanged(this, pointerEventsNone)
    override fun onNativeZIndexChanged(zIndex: Int) = GtkWidgetLifecycleBridge.onZIndexChanged(this, zIndex)
    override fun onNativeTransformChanged(transform: CssTransform?) = GtkWidgetLifecycleBridge.onTransformChanged(this, transform)
    override fun onNativeStyleSynced() = GtkWidgetLifecycleBridge.onStyleSynced(this)

    override fun dispatchClick() {
        onSelectRequested?.invoke()
        super.dispatchClick()
    }

    override fun dispose() {
        clickRef?.dispose()
        clickRef = null
        super.dispose()
    }

    companion object {
        private val tabBtnClickedCb = staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { _, userData ->
            val impl = userData?.asStableRef<GtkTabNativeImpl>()?.get() ?: return@staticCFunction
            impl.dispatchClick()
        }

        private val tabCloseClickedCb = staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { _, userData ->
            val impl = userData?.asStableRef<GtkTabNativeImpl>()?.get() ?: return@staticCFunction
            impl.onCloseActionRequested?.invoke()
        }
    }
}
