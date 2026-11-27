@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.impl

import com.nexustools.genericui.desktop.widget.DesktopSelectNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.gtk.widget.GtkCustomLayoutManager
import com.nexustools.genericui.gtk.widget.GtkSurfaceRenderHelper
import com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge
import com.nexustools.genericui.linux.LinuxGlibGioBindings
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlin.math.ceil
import kotlin.math.max

/**
 * Retained GTK4 selector implementation mounting `GtkDropDown` for standard `Select` and an
 * editable linked `[GtkEntry + GtkMenuButton(GtkPopover + GtkListBox)]` for `ComboBox`.
 */
class GtkSelectNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkDropDown"),
    classPrefix: String = "select"
) : DesktopSelectNativeImpl<GtkWidgetHandle>(nativeWidget) {

    private var syncingFromModel = false
    private var signalRef: StableRef<GtkSelectNativeImpl>? = null
    private var comboListBoxPtr: COpaquePointer? = null
    private var comboMenuBtnPtr: COpaquePointer? = null

    init {
        nativeWidget.ownerImpl = this
        syncClassMetadata()
        nativeWidget.widgetMaterializer = { materializeSelect() }
    }

    private fun syncClassMetadata() {
        nativeWidget.updateActiveGtkClassName(
            if (isComboBoxVariant()) "GtkBox_ComboBox" else "GtkDropDown"
        )
    }

    private fun materializeSelect(): COpaquePointer? {
        nativeWidget.realGtkWidgetPtr?.let { return it }
        if (!Gtk4DynamicBindings.ensureGtkInitialized()) return null
        syncClassMetadata()
        signalRef?.dispose()
        val ref = StableRef.create(this)
        signalRef = ref

        return if (isComboBoxVariant()) {
            materializeEditableComboBox(ref)
        } else {
            materializeDropDown(ref)
        }
    }

    private fun materializeDropDown(ref: StableRef<GtkSelectNativeImpl>): COpaquePointer? {
        val options = getOptions()
        val labels = options.map { it.second }
        val dd = Gtk4DynamicBindings.gtkDropDownNewFromStrings(labels) ?: return null
        comboListBoxPtr = null
        comboMenuBtnPtr = null
        nativeWidget.primaryLeafPtr = dd
        nativeWidget.secondaryLeafPtr = null

        val selIdx = options.indexOfFirst { it.first == getSelectedValue() }
        if (selIdx >= 0) {
            syncingFromModel = true
            try {
                Gtk4DynamicBindings.gtkDropDownSetSelected(dd, selIdx)
            } finally {
                syncingFromModel = false
            }
        }
        LinuxGlibGioBindings.signalConnect(
            instance = dd,
            signalName = "notify::selected",
            handler = dropDownNotifySelectedCb.reinterpret(),
            userData = ref.asCPointer()
        )
        GtkWidgetLifecycleBridge.attachControllersAndState(
            impl = this,
            rootPtr = dd,
            focusTargetPtr = dd,
            isTextInput = false,
            dispatchClickOnPrimaryRelease = false,
            attachClickGesture = true
        )
        return dd
    }

    private fun materializeEditableComboBox(ref: StableRef<GtkSelectNativeImpl>): COpaquePointer? {
        val box = Gtk4DynamicBindings.gtkBoxNew(Gtk4DynamicBindings.GTK_ORIENTATION_HORIZONTAL, 0) ?: return null
        Gtk4DynamicBindings.gtkWidgetAddCssClass(box, "linked")
        val entry = Gtk4DynamicBindings.gtkEntryNew() ?: return null
        val menuBtn = Gtk4DynamicBindings.gtkMenuButtonNew() ?: return null
        val popover = Gtk4DynamicBindings.gtkPopoverNew()
        val listBox = Gtk4DynamicBindings.gtkListBoxNew()

        if (listBox != null && popover != null) {
            Gtk4DynamicBindings.gtkListBoxSetSelectionMode(listBox, Gtk4DynamicBindings.GTK_SELECTION_SINGLE)
            populateComboListBox(listBox)
            LinuxGlibGioBindings.signalConnect(
                instance = listBox,
                signalName = "row-selected",
                handler = comboListRowSelectedCb.reinterpret(),
                userData = ref.asCPointer()
            )
            val sw = Gtk4DynamicBindings.gtkScrolledWindowNew()
            if (sw != null) {
                Gtk4DynamicBindings.gtkScrolledWindowSetPolicy(
                    sw,
                    Gtk4DynamicBindings.GTK_POLICY_NEVER,
                    Gtk4DynamicBindings.GTK_POLICY_AUTOMATIC
                )
                Gtk4DynamicBindings.gtkWidgetSetSizeRequest(sw, 180, 180)
                Gtk4DynamicBindings.gtkScrolledWindowSetChild(sw, listBox)
                Gtk4DynamicBindings.gtkPopoverSetChild(popover, sw)
            } else {
                Gtk4DynamicBindings.gtkPopoverSetChild(popover, listBox)
            }
            Gtk4DynamicBindings.gtkMenuButtonSetPopover(menuBtn, popover)
            nativeWidget.popoverPtr = popover
        }
        comboListBoxPtr = listBox
        comboMenuBtnPtr = menuBtn

        val selectedLabel = getOptions().firstOrNull { it.first == getSelectedValue() }?.second
            ?: getSelectedValue().orEmpty()
        syncingFromModel = true
        try {
            Gtk4DynamicBindings.gtkEditableSetText(entry, selectedLabel)
        } finally {
            syncingFromModel = false
        }
        LinuxGlibGioBindings.signalConnect(
            instance = entry,
            signalName = "changed",
            handler = comboEntryChangedCb.reinterpret(),
            userData = ref.asCPointer()
        )
        LinuxGlibGioBindings.signalConnect(
            instance = entry,
            signalName = "activate",
            handler = comboEntryActivateCb.reinterpret(),
            userData = ref.asCPointer()
        )

        Gtk4DynamicBindings.gtkBoxAppend(box, entry)
        Gtk4DynamicBindings.gtkBoxAppend(box, menuBtn)
        nativeWidget.primaryLeafPtr = entry
        nativeWidget.secondaryLeafPtr = menuBtn

        GtkWidgetLifecycleBridge.attachControllersAndState(
            impl = this,
            rootPtr = box,
            focusTargetPtr = entry,
            isTextInput = true,
            dispatchClickOnPrimaryRelease = false,
            attachClickGesture = true
        )
        return box
    }

    private fun populateComboListBox(listBox: COpaquePointer) {
        var cur = Gtk4DynamicBindings.gtkWidgetGetFirstChild(listBox)
        while (cur != null) {
            val next = Gtk4DynamicBindings.gtkWidgetGetNextSibling(cur)
            Gtk4DynamicBindings.gtkListBoxRemove(listBox, cur)
            cur = next
        }
        for ((_, label) in getOptions()) {
            val lbl = Gtk4DynamicBindings.gtkLabelNew(label) ?: continue
            Gtk4DynamicBindings.gtkLabelSetXalign(lbl, 0f)
            Gtk4DynamicBindings.gtkWidgetSetMargins(lbl, 4, 4, 8, 8)
            Gtk4DynamicBindings.gtkListBoxAppend(listBox, lbl)
        }
    }

    override fun onVariantChanged(variant: String?) {
        syncClassMetadata()
        val oldPtr = nativeWidget.realGtkWidgetPtr ?: return
        nativeWidget.realGtkWidgetPtr = null
        val newPtr = materializeSelect()
        if (newPtr != null) {
            GtkWidgetLifecycleBridge.swapLiveWidgetInParent(this, oldPtr, newPtr)
        }
    }

    override fun setOptions(options: List<Pair<String, String>>) {
        super.setOptions(options)
        val ptr = nativeWidget.realGtkWidgetPtr ?: return
        if (isComboBoxVariant()) {
            comboListBoxPtr?.let { populateComboListBox(it) }
        } else {
            val strList = Gtk4DynamicBindings.gtkStringListNew(options.map { it.second }) ?: return
            syncingFromModel = true
            try {
                Gtk4DynamicBindings.gtkDropDownSetModel(ptr, strList)
                val selIdx = options.indexOfFirst { it.first == getSelectedValue() }
                if (selIdx >= 0) Gtk4DynamicBindings.gtkDropDownSetSelected(ptr, selIdx)
            } finally {
                syncingFromModel = false
                LinuxGlibGioBindings.objectUnref(strList)
            }
        }
    }

    override fun setSelectedValue(value: String?) {
        super.setSelectedValue(value)
        val ptr = nativeWidget.realGtkWidgetPtr ?: return
        val options = getOptions()
        syncingFromModel = true
        try {
            if (isComboBoxVariant()) {
                val entry = nativeWidget.primaryLeafPtr ?: return
                val text = options.firstOrNull { it.first == value }?.second ?: value.orEmpty()
                Gtk4DynamicBindings.gtkEditableSetText(entry, text)
            } else {
                val idx = options.indexOfFirst { it.first == value }
                if (idx >= 0) {
                    Gtk4DynamicBindings.gtkDropDownSetSelected(ptr, idx)
                }
            }
        } finally {
            syncingFromModel = false
        }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val ptr = nativeWidget.realGtkWidgetPtr
        if (ptr != null) {
            val (w, h) = GtkCustomLayoutManager.measureGtkWidgetIntrinsic(ptr, maxWidth, maxHeight)
            if (w > 0 && h > 0) return Size(w, h)
        }
        val longest = getOptions().maxByOrNull { it.second.length }?.second ?: "Select"
        val (tw, th) = GtkSurfaceRenderHelper.measureTextSize(longest, 14f)
        return Size(max(120, tw + 44), max(34, th + 14))
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

    override fun setFocusedState(focused: Boolean) {
        super.setFocusedState(focused)
        GtkWidgetLifecycleBridge.syncNativeFocus(this, isFocusedState())
    }

    override fun dispose() {
        signalRef?.dispose()
        signalRef = null
        super.dispose()
    }

    companion object {
        private val dropDownNotifySelectedCb = staticCFunction<COpaquePointer?, COpaquePointer?, COpaquePointer?, Unit> { dd, _, userData ->
            val impl = userData?.asStableRef<GtkSelectNativeImpl>()?.get() ?: return@staticCFunction
            if (impl.syncingFromModel || dd == null) return@staticCFunction
            val idx = Gtk4DynamicBindings.gtkDropDownGetSelected(dd)
            val option = impl.getOptions().getOrNull(idx) ?: return@staticCFunction
            impl.notifyUserSelectionChanged(option.first)
        }

        private val comboEntryChangedCb = staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { entry, userData ->
            val impl = userData?.asStableRef<GtkSelectNativeImpl>()?.get() ?: return@staticCFunction
            if (impl.syncingFromModel || entry == null) return@staticCFunction
            val txt = Gtk4DynamicBindings.gtkEditableGetText(entry)
            val match = impl.getOptions().firstOrNull { it.second == txt || it.first == txt }
            impl.notifyUserSelectionChanged(match?.first ?: txt)
        }

        private val comboEntryActivateCb = staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { entry, userData ->
            val impl = userData?.asStableRef<GtkSelectNativeImpl>()?.get() ?: return@staticCFunction
            if (entry == null) return@staticCFunction
            val txt = Gtk4DynamicBindings.gtkEditableGetText(entry)
            impl.notifyUserSelectionChanged(txt)
        }

        private val comboListRowSelectedCb = staticCFunction<COpaquePointer?, COpaquePointer?, COpaquePointer?, Unit> { listBox, row, userData ->
            val impl = userData?.asStableRef<GtkSelectNativeImpl>()?.get() ?: return@staticCFunction
            if (impl.syncingFromModel || listBox == null || row == null) return@staticCFunction
            val opts = impl.getOptions()
            var idx = -1
            for (i in opts.indices) {
                if (Gtk4DynamicBindings.gtkListBoxGetRowAtIndex(listBox, i) == row) {
                    idx = i
                    break
                }
            }
            val chosen = opts.getOrNull(idx) ?: return@staticCFunction
            impl.syncingFromModel = true
            try {
                impl.nativeWidget.primaryLeafPtr?.let { entry ->
                    Gtk4DynamicBindings.gtkEditableSetText(entry, chosen.second)
                }
            } finally {
                impl.syncingFromModel = false
            }
            impl.nativeWidget.popoverPtr?.let { Gtk4DynamicBindings.gtkPopoverPopdown(it) }
            impl.notifyUserSelectionChanged(chosen.first)
        }
    }
}
