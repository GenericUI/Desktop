@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.impl

import com.nexustools.genericui.desktop.widget.DesktopStyledTextInputNativeImpl
import com.nexustools.genericui.geometry.EdgeInsets
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.gtk.widget.GtkCustomLayoutManager
import com.nexustools.genericui.gtk.widget.GtkRetainedContainerSupport
import com.nexustools.genericui.gtk.widget.GtkSurfaceRenderHelper
import com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge
import com.nexustools.genericui.linux.LinuxGlibGioBindings
import com.nexustools.genericui.linux.style.LinuxBackdropFilterController
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.style.css.StyledVisualSpec
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Retained GTK4 custom-styled text input (`StyledTextField` / `StyledTextArea`) combining a
 * Cairo/GSK-painted `GtkDrawingArea` surface (pill radii, gradients, borders, box shadows,
 * focus rings, and `backdrop-filter`) beneath a transparent, borderless `GtkEntry` or
 * `GtkScrolledWindow + GtkTextView` inside a `GtkOverlay`.
 */
open class GtkStyledTextInputNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkOverlay_StyledTextInput"),
    multiline: Boolean = false,
    classPrefix: String = if (multiline) "styled-textarea" else "styled-textfield"
) : DesktopStyledTextInputNativeImpl<GtkWidgetHandle>(nativeWidget, multiline, classPrefix) {

    private var signalRef: StableRef<GtkStyledTextInputNativeImpl>? = null
    private var syncingFromModel = false
    private var captionLabelPtr: COpaquePointer? = null
    private var bgDrawingAreaPtr: COpaquePointer? = null
    private var editorBoxPtr: COpaquePointer? = null
    private var editableLeafPtr: COpaquePointer? = null
    private var textBufferPtr: COpaquePointer? = null
    private var cssProviderPtr: COpaquePointer? = null
    private var cssProviderDisplayPtr: COpaquePointer? = null
    private val perWidgetCssClass: String = "gui-styled-input-${nativeWidget.nativePointer}"
    private var lastVisuals: StyledVisualSpec = StyledVisualSpec()
    private var builtMultiline: Boolean? = null
    private var builtHasLabel: Boolean = false

    init {
        nativeWidget.ownerImpl = this
        nativeWidget.hasCustomSurfaceBackground = true
        nativeWidget.onDrawSurfaceCairo = { cr, w, h -> paintStyledSurface(cr, w, h) }
        nativeWidget.widgetMaterializer = { materializeStyledInput() }
        nativeWidget.updateActiveGtkClassName(if (multiline) "GtkOverlay_StyledTextView" else "GtkOverlay_StyledEntry")
    }

    private fun materializeStyledInput(): COpaquePointer? {
        nativeWidget.realGtkWidgetPtr?.let { return it }
        if (!Gtk4DynamicBindings.ensureGtkInitialized()) return null

        signalRef?.dispose()
        val ref = StableRef.create(this)
        signalRef = ref

        val isMulti = isMultiline()
        val label = getLabel()
        val wantLabel = !label.isNullOrBlank()
        builtMultiline = isMulti
        builtHasLabel = wantLabel
        textBufferPtr = null
        captionLabelPtr = null

        val overlay = Gtk4DynamicBindings.gtkOverlayNew() ?: return null
        val bgDa = GtkWidgetLifecycleBridge.createDrawingSurfaceForPainter(nativeWidget) { cr, w, h ->
            paintStyledSurface(cr, w, h)
        }
        if (bgDa != null) {
            Gtk4DynamicBindings.gtkWidgetSetCanTarget(bgDa, false)
            Gtk4DynamicBindings.gtkOverlaySetChild(overlay, bgDa)
            bgDrawingAreaPtr = bgDa
            nativeWidget.backgroundSurfacePtr = bgDa
        }

        val editorContainer: COpaquePointer
        val focusLeaf: COpaquePointer
        if (isMulti) {
            val sw = Gtk4DynamicBindings.gtkScrolledWindowNew() ?: return null
            val tv = Gtk4DynamicBindings.gtkTextViewNew() ?: return null
            Gtk4DynamicBindings.gtkScrolledWindowSetPolicy(
                sw,
                Gtk4DynamicBindings.GTK_POLICY_AUTOMATIC,
                Gtk4DynamicBindings.GTK_POLICY_AUTOMATIC
            )
            Gtk4DynamicBindings.gtkScrolledWindowSetChild(sw, tv)
            Gtk4DynamicBindings.gtkTextViewSetWrapMode(tv, Gtk4DynamicBindings.GTK_WRAP_WORD_CHAR)
            Gtk4DynamicBindings.gtkTextViewSetEditable(tv, !isReadOnly())
            Gtk4DynamicBindings.gtkWidgetAddCssClass(sw, perWidgetCssClass)
            Gtk4DynamicBindings.gtkWidgetAddCssClass(tv, perWidgetCssClass)
            val buf = Gtk4DynamicBindings.gtkTextViewGetBuffer(tv)
            textBufferPtr = buf
            if (buf != null) {
                syncingFromModel = true
                try {
                    Gtk4DynamicBindings.gtkTextBufferSetText(buf, textValue)
                } finally {
                    syncingFromModel = false
                }
                LinuxGlibGioBindings.signalConnect(
                    instance = buf,
                    signalName = "changed",
                    handler = textBufferChangedCb.reinterpret(),
                    userData = ref.asCPointer()
                )
            }
            nativeWidget.scrolledWindowPtr = sw
            nativeWidget.primaryLeafPtr = tv
            editorContainer = sw
            focusLeaf = tv
        } else {
            val entry = Gtk4DynamicBindings.gtkEntryNew() ?: return null
            Gtk4DynamicBindings.gtkEntrySetHasFrame(entry, false)
            Gtk4DynamicBindings.gtkEntrySetVisibility(entry, !isPasswordMode())
            Gtk4DynamicBindings.gtkWidgetAddCssClass(entry, perWidgetCssClass)
            syncingFromModel = true
            try {
                Gtk4DynamicBindings.gtkEditableSetText(entry, textValue)
                Gtk4DynamicBindings.gtkEntrySetPlaceholderText(entry, placeholderText.ifEmpty { null })
                Gtk4DynamicBindings.gtkEditableSetEditable(entry, !isReadOnly())
            } finally {
                syncingFromModel = false
            }
            LinuxGlibGioBindings.signalConnect(
                instance = entry,
                signalName = "changed",
                handler = editableChangedCb.reinterpret(),
                userData = ref.asCPointer()
            )
            LinuxGlibGioBindings.signalConnect(
                instance = entry,
                signalName = "activate",
                handler = editableActivateCb.reinterpret(),
                userData = ref.asCPointer()
            )
            nativeWidget.primaryLeafPtr = entry
            editorContainer = entry
            focusLeaf = entry
        }

        editorBoxPtr = editorContainer
        editableLeafPtr = focusLeaf
        Gtk4DynamicBindings.gtkWidgetSetExpand(editorContainer, hexpand = true, vexpand = true)
        Gtk4DynamicBindings.gtkWidgetSetHalign(editorContainer, Gtk4DynamicBindings.GTK_ALIGN_FILL)
        Gtk4DynamicBindings.gtkWidgetSetValign(
            editorContainer,
            if (isMulti) Gtk4DynamicBindings.GTK_ALIGN_FILL else Gtk4DynamicBindings.GTK_ALIGN_CENTER
        )
        Gtk4DynamicBindings.gtkOverlayAddOverlay(overlay, editorContainer)

        val rootPtr = if (wantLabel && label != null) {
            val box = Gtk4DynamicBindings.gtkBoxNew(Gtk4DynamicBindings.GTK_ORIENTATION_VERTICAL, 4) ?: overlay
            if (box !== overlay) {
                val cap = Gtk4DynamicBindings.gtkLabelNew(label)
                if (cap != null) {
                    Gtk4DynamicBindings.gtkLabelSetXalign(cap, 0f)
                    Gtk4DynamicBindings.gtkBoxAppend(box, cap)
                    captionLabelPtr = cap
                }
                Gtk4DynamicBindings.gtkWidgetSetExpand(overlay, hexpand = true, vexpand = true)
                Gtk4DynamicBindings.gtkBoxAppend(box, overlay)
            }
            box
        } else {
            overlay
        }

        GtkWidgetLifecycleBridge.attachControllersAndState(
            impl = this,
            rootPtr = rootPtr,
            focusTargetPtr = focusLeaf,
            isTextInput = true,
            dispatchClickOnPrimaryRelease = false,
            attachClickGesture = true
        )
        applyStyledVisuals(resolveCurrentVisuals())
        return rootPtr
    }

    private fun checkRebuildStructure() {
        val wantMulti = isMultiline()
        val wantLabel = !getLabel().isNullOrBlank()
        if (builtMultiline == wantMulti && builtHasLabel == wantLabel) {
            captionLabelPtr?.let { cap -> Gtk4DynamicBindings.gtkLabelSetText(cap, getLabel() ?: "") }
            return
        }
        val oldPtr = nativeWidget.realGtkWidgetPtr ?: return
        nativeWidget.realGtkWidgetPtr = null
        val newPtr = materializeStyledInput()
        if (newPtr != null) {
            GtkWidgetLifecycleBridge.swapLiveWidgetInParent(this, oldPtr, newPtr)
        }
    }

    override fun applyStyledVisuals(visuals: StyledVisualSpec) {
        lastVisuals = visuals
        val leaf = editableLeafPtr
        val container = editorBoxPtr
        if (leaf != null && container != null) {
            val pad = visuals.padding ?: lastComputedStyle?.padding?.toEdgeInsets() ?: DEFAULT_INPUT_PADDING
            Gtk4DynamicBindings.gtkWidgetSetMargins(
                container,
                top = pad.top.coerceAtLeast(0),
                bottom = pad.bottom.coerceAtLeast(0),
                start = pad.left.coerceAtLeast(0),
                end = pad.right.coerceAtLeast(0)
            )
            installOrUpdateCssProvider(visuals)
        }
        bgDrawingAreaPtr?.let { Gtk4DynamicBindings.gtkWidgetQueueDraw(it) }
        nativeWidget.realGtkWidgetPtr?.let { Gtk4DynamicBindings.gtkWidgetQueueDraw(it) }
    }

    private fun installOrUpdateCssProvider(visuals: StyledVisualSpec) {
        val display = Gtk4DynamicBindings.gdkDisplayGetDefault() ?: return
        val provider = cssProviderPtr ?: Gtk4DynamicBindings.gtkCssProviderNew()?.also { p ->
            cssProviderPtr = p
            cssProviderDisplayPtr = display
            Gtk4DynamicBindings.gtkStyleContextAddProviderForDisplay(
                display = display,
                provider = p,
                priority = Gtk4DynamicBindings.GTK_STYLE_PROVIDER_PRIORITY_USER + 2u
            )
        } ?: return

        val dark = GtkRetainedContainerSupport.resolveDarkTheme()
        var textColor = visuals.textColor ?: lastComputedStyle?.textColor ?: defaultTextColor(dark)
        if (!nativeWidget.enabled) {
            textColor = textColor.copy(a = (textColor.a * 0.55f).roundToInt().coerceIn(0, 255))
        }
        val placeholderColor = visuals.placeholderColor ?: textColor.copy(a = (textColor.a * 0.52f).roundToInt().coerceIn(0, 255))
        val fontSizePx = visuals.fontSizePx?.takeIf { it > 0f } ?: lastComputedStyle?.fontSizePx ?: 14f
        val fontWeight = visuals.fontWeightNumeric ?: lastComputedStyle?.fontWeightNumeric ?: 400
        val fontFamily = (visuals.fontFamily ?: lastComputedStyle?.fontFamily)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        val textCss = rgbaCss(textColor)
        val placeholderCss = rgbaCss(placeholderColor)
        val fontProps = buildString {
            append("font-size: ").append(fontSizePx).append("px; ")
            append("font-weight: ").append(fontWeight).append("; ")
            if (fontFamily != null) {
                append("font-family: ").append(fontFamily).append("; ")
            }
        }
        val cls = perWidgetCssClass
        val css = buildString {
            append("entry.").append(cls).append(", ")
            append("entry.").append(cls).append(" > text, ")
            append("scrolledwindow.").append(cls).append(", ")
            append("textview.").append(cls).append(", ")
            append("textview.").append(cls).append(" > text { ")
            append("background-color: transparent; background-image: none; ")
            append("border: none; outline: none; box-shadow: none; ")
            append("min-height: 0px; min-width: 0px; padding: 0px; margin: 0px; ")
            append("color: ").append(textCss).append("; ")
            append("caret-color: ").append(textCss).append("; ")
            append(fontProps)
            append("}\n")
            append("entry.").append(cls).append(" > text > placeholder { ")
            append("color: ").append(placeholderCss).append("; opacity: 1; }\n")
        }
        Gtk4DynamicBindings.gtkCssProviderLoadCss(provider, css)
    }

    private fun paintStyledSurface(cr: COpaquePointer, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val dark = GtkRetainedContainerSupport.resolveDarkTheme()
        val visuals = resolveCurrentVisuals()
        val effectiveSpec = if (isFocusedState()) {
            visuals
        } else {
            visuals.copy(focusRingColor = null, focusRingWidthPx = null)
        }
        val computed = getComputedStyle()
        val paintSpec = GtkSurfaceRenderHelper.fromStyledVisualSpec(
            spec = effectiveSpec,
            cornerRadii = computed?.borderRadius,
            backdropFilter = (nativeWidget.attributes["backdropFilter"] as? CssFilter) ?: effectiveSpec.backdropFilter,
            foregroundFilter = (nativeWidget.attributes["cssFilter"] as? CssFilter) ?: effectiveSpec.filter,
            opacity = effectiveSpec.opacity ?: getOpacity(),
            darkTheme = dark
        )
        val hasBackdrop = GtkSurfaceRenderHelper.effectiveBackdropFilter(paintSpec)?.let {
            LinuxBackdropFilterController.shared.hasActiveEffects(it)
        } == true
        val behindCapture: (() -> IntArray?)? = if (hasBackdrop) {
            { GtkWidgetLifecycleBridge.captureBehindWidgetPremulBgra(this, width, height) }
        } else {
            null
        }
        GtkSurfaceRenderHelper.paintSurface(
            cr = cr,
            width = width,
            height = height,
            spec = paintSpec,
            behindBgraCapture = behindCapture
        )
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val visuals = resolveCurrentVisuals()
        val pad = visuals.padding ?: lastComputedStyle?.padding?.toEdgeInsets() ?: DEFAULT_INPUT_PADDING
        val ptr = nativeWidget.realGtkWidgetPtr
        val measured = if (ptr != null) {
            GtkCustomLayoutManager.measureGtkWidgetIntrinsic(ptr, maxWidth, maxHeight)
        } else {
            0 to 0
        }
        val defaultW = if (isMultiline()) 240 else 180
        val defaultH = if (isMultiline()) 96 else 36
        val captionExtra = if (!getLabel().isNullOrBlank()) 22 else 0
        val prefW = visuals.widthPx ?: max(visuals.minWidthPx ?: defaultW, measured.first + pad.left + pad.right)
        val prefH = visuals.heightPx ?: max(visuals.minHeightPx ?: (defaultH + captionExtra), measured.second + pad.top + pad.bottom + captionExtra)
        return Size(prefW, prefH)
    }

    override fun setValue(value: String) {
        super.setValue(value)
        val leaf = editableLeafPtr ?: return
        syncingFromModel = true
        try {
            if (isMultiline()) {
                val buf = textBufferPtr ?: return
                if (Gtk4DynamicBindings.gtkTextBufferGetText(buf) != value) {
                    Gtk4DynamicBindings.gtkTextBufferSetText(buf, value)
                }
            } else if (Gtk4DynamicBindings.gtkEditableGetText(leaf) != value) {
                Gtk4DynamicBindings.gtkEditableSetText(leaf, value)
            }
        } finally {
            syncingFromModel = false
        }
    }

    override fun setPlaceholder(placeholder: String) {
        super.setPlaceholder(placeholder)
        val leaf = editableLeafPtr ?: return
        if (!isMultiline()) {
            Gtk4DynamicBindings.gtkEntrySetPlaceholderText(leaf, placeholder.ifEmpty { null })
        }
    }

    override fun setLabel(label: String?) {
        super.setLabel(label)
        checkRebuildStructure()
    }

    override fun setReadOnly(readOnly: Boolean) {
        super.setReadOnly(readOnly)
        val leaf = editableLeafPtr ?: return
        if (isMultiline()) {
            Gtk4DynamicBindings.gtkTextViewSetEditable(leaf, !readOnly)
        } else {
            Gtk4DynamicBindings.gtkEditableSetEditable(leaf, !readOnly)
        }
    }

    override fun setPasswordMode(password: Boolean) {
        super.setPasswordMode(password)
        val leaf = editableLeafPtr ?: return
        if (!isMultiline()) {
            Gtk4DynamicBindings.gtkEntrySetVisibility(leaf, !password)
        }
    }

    override fun setMultiline(multiline: Boolean) {
        super.setMultiline(multiline)
        nativeWidget.updateActiveGtkClassName(if (multiline) "GtkOverlay_StyledTextView" else "GtkOverlay_StyledEntry")
        checkRebuildStructure()
    }

    override fun setFocusedState(focused: Boolean) {
        super.setFocusedState(focused)
        GtkWidgetLifecycleBridge.syncNativeFocus(this, isFocusedState())
        bgDrawingAreaPtr?.let { Gtk4DynamicBindings.gtkWidgetQueueDraw(it) }
    }

    private fun pushUserTextChange(newText: String) {
        if (syncingFromModel) return
        textValue = newText
        nativeWidget.attributes["value"] = newText
        onValueChangedListener?.invoke(newText)
    }

    override fun onNativeBoundsChanged(rect: Rect) = GtkWidgetLifecycleBridge.onBoundsChanged(this, rect)
    override fun onNativeVisibilityChanged(visible: Boolean) = GtkWidgetLifecycleBridge.onVisibilityChanged(this, visible)
    override fun onNativeEnabledChanged(enabled: Boolean) {
        GtkWidgetLifecycleBridge.onEnabledChanged(this, enabled)
        applyStyledVisuals(resolveCurrentVisuals())
    }
    override fun onNativeOpacityChanged(opacity: Float) = GtkWidgetLifecycleBridge.onOpacityChanged(this, opacity)
    override fun onNativeTooltipChanged(tooltip: String?) = GtkWidgetLifecycleBridge.onTooltipChanged(this, tooltip)
    override fun onNativeCursorChanged(cursor: CssCursorValue?) = GtkWidgetLifecycleBridge.onCursorChanged(this, cursor)
    override fun onNativePointerEventsChanged(pointerEventsNone: Boolean) = GtkWidgetLifecycleBridge.onPointerEventsChanged(this, pointerEventsNone)
    override fun onNativeZIndexChanged(zIndex: Int) = GtkWidgetLifecycleBridge.onZIndexChanged(this, zIndex)
    override fun onNativeTransformChanged(transform: CssTransform?) = GtkWidgetLifecycleBridge.onTransformChanged(this, transform)
    override fun onNativeStyleSynced() {
        GtkWidgetLifecycleBridge.onStyleSynced(this)
        applyStyledVisuals(resolveCurrentVisuals())
    }

    override fun dispose() {
        val provider = cssProviderPtr
        val display = cssProviderDisplayPtr
        cssProviderPtr = null
        cssProviderDisplayPtr = null
        if (provider != null) {
            if (display != null) {
                Gtk4DynamicBindings.gtkStyleContextRemoveProviderForDisplay(display, provider)
            }
            LinuxGlibGioBindings.objectUnref(provider)
        }
        signalRef?.dispose()
        signalRef = null
        captionLabelPtr = null
        bgDrawingAreaPtr = null
        editorBoxPtr = null
        editableLeafPtr = null
        textBufferPtr = null
        super.dispose()
    }

    companion object {
        private val DEFAULT_INPUT_PADDING = EdgeInsets(6, 12, 6, 12)

        private fun defaultTextColor(dark: Boolean): RgbaColor =
            if (dark) RgbaColor(240, 242, 245, 255) else RgbaColor(20, 22, 26, 255)

        private fun rgbaCss(c: RgbaColor): String =
            "rgba(${c.r.coerceIn(0, 255)}, ${c.g.coerceIn(0, 255)}, ${c.b.coerceIn(0, 255)}, ${(c.a / 255.0).coerceIn(0.0, 1.0)})"

        private val editableChangedCb = staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { editable, userData ->
            val impl = userData?.asStableRef<GtkStyledTextInputNativeImpl>()?.get() ?: return@staticCFunction
            if (impl.syncingFromModel || editable == null) return@staticCFunction
            impl.pushUserTextChange(Gtk4DynamicBindings.gtkEditableGetText(editable))
        }

        private val editableActivateCb = staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { _, userData ->
            val impl = userData?.asStableRef<GtkStyledTextInputNativeImpl>()?.get() ?: return@staticCFunction
            impl.submitValue()
        }

        private val textBufferChangedCb = staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { buf, userData ->
            val impl = userData?.asStableRef<GtkStyledTextInputNativeImpl>()?.get() ?: return@staticCFunction
            if (impl.syncingFromModel || buf == null) return@staticCFunction
            impl.pushUserTextChange(Gtk4DynamicBindings.gtkTextBufferGetText(buf))
        }
    }
}
