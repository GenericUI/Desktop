@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk

import com.nexustools.genericui.desktop.widget.DesktopButtonNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapSize
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.gtk.widget.GtkCustomLayoutManager
import com.nexustools.genericui.gtk.widget.GtkRetainedContainerSupport
import com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge
import com.nexustools.genericui.linux.LinuxGlibGioBindings
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.style.gradient.Gradient.Gradient
import com.nexustools.genericui.style.theme.ThemeConfig
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction

/**
 * Retained GTK4 button implementation always wrapping a genuine `GtkButton`.
 *
 * - Default mode (`BootstrapType.NONE` and `!outline`) leaves the native `GtkButton` unmodified.
 * - Semantic modes style the genuine `GtkButton` via Libadwaita/GTK4 CSS classes (`suggested-action`,
 *   `destructive-action`, `flat`, `pill`, `gui-btn-*`) and a theme-derived `GtkCssProvider`.
 */
open class GtkButtonNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkButton"),
    classPrefix: String = "btn"
) : DesktopButtonNativeImpl<GtkWidgetHandle>(nativeWidget, classPrefix) {

    private var clickRef: StableRef<GtkButtonNativeImpl>? = null

    init {
        nativeWidget.ownerImpl = this
        syncButtonModeMetadata()
        nativeWidget.widgetMaterializer = { materializeButtonWidget() }
    }

    protected fun syncButtonModeMetadata() {
        val nativeDefault = isNativeDefaultButtonMode()
        nativeWidget.updateActiveGtkClassName("GtkButton")
        nativeWidget.attributes["useSystemControlAppearance"] = nativeDefault.toString()
        nativeWidget.onDrawSurfaceCairo = null
    }

    private fun materializeButtonWidget(): COpaquePointer? {
        nativeWidget.realGtkWidgetPtr?.let { return it }
        if (!Gtk4DynamicBindings.ensureGtkInitialized()) return null
        val btn = Gtk4DynamicBindings.gtkButtonNew() ?: return null
        Gtk4DynamicBindings.gtkWidgetSetFocusable(btn, true)
        clickRef?.dispose()
        val ref = StableRef.create(this)
        clickRef = ref
        LinuxGlibGioBindings.signalConnect(
            instance = btn,
            signalName = "clicked",
            handler = gtkButtonClickedCb.reinterpret(),
            userData = ref.asCPointer()
        )
        GtkWidgetLifecycleBridge.attachControllersAndState(
            impl = this,
            rootPtr = btn,
            focusTargetPtr = btn,
            isTextInput = false,
            dispatchClickOnPrimaryRelease = false,
            attachClickGesture = true
        )
        syncButtonModeMetadata()
        syncLabelAndCssClassesToLiveWidget(btn)
        return btn
    }

    private fun resolveSemanticCssClasses(): Set<String> {
        val out = LinkedHashSet<String>()
        val bType = getType()?.takeIf { it != BootstrapType.NONE }
            ?: getBackgroundType()?.takeIf { it != BootstrapType.NONE }
        val outline = isOutline()
        if (bType == null && !outline) {
            return out
        }
        if ("rounded-pill" in nativeWidget.gtkCssClasses || (nativeWidget.attributes["roundedPill"] as? Boolean == true)) {
            out.add("pill")
        }
        when {
            bType == BootstrapType.LINK -> {
                out.add("flat")
                out.add("gui-btn-link")
            }
            outline -> {
                out.add("gui-btn-outline")
                val effective = bType ?: BootstrapType.SECONDARY
                out.add("gui-btn-${effective.cssSuffix}")
            }
            bType != null -> {
                out.add("gui-btn-${bType.cssSuffix}")
                when (bType) {
                    BootstrapType.PRIMARY -> out.add("suggested-action")
                    BootstrapType.DANGER -> out.add("destructive-action")
                    else -> Unit
                }
            }
        }
        return out
    }

    private fun syncLabelAndCssClassesToLiveWidget(
        targetPtr: COpaquePointer? = nativeWidget.realGtkWidgetPtr
    ) {
        val ptr = targetPtr ?: return
        val icon = getIcon()?.takeIf { it.isNotBlank() }
        val text = getText()
        val labelText = if (icon != null && text.isNotEmpty()) "$icon $text" else (icon ?: text)
        Gtk4DynamicBindings.gtkButtonSetLabel(ptr, labelText)

        ensureThemeButtonCssProvider()
        for (cls in MANAGED_SEMANTIC_CLASSES) {
            Gtk4DynamicBindings.gtkWidgetRemoveCssClass(ptr, cls)
        }
        for (cls in resolveSemanticCssClasses()) {
            Gtk4DynamicBindings.gtkWidgetAddCssClass(ptr, cls)
        }
        Gtk4DynamicBindings.gtkWidgetQueueDraw(ptr)
    }

    override fun onButtonModeChanged() {
        syncButtonModeMetadata()
        syncLabelAndCssClassesToLiveWidget()
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val ptr = nativeWidget.realGtkWidgetPtr
        if (ptr != null) {
            val (w, h) = GtkCustomLayoutManager.measureGtkWidgetIntrinsic(ptr, maxWidth, maxHeight)
            return Size(w.coerceAtLeast(36), h.coerceAtLeast(32))
        }
        val base = super.measureIntrinsicSize(maxWidth, maxHeight)
        val icon = getIcon()?.takeIf { it.isNotBlank() }
        val label = getText()
        val display = if (icon != null && label.isNotEmpty()) "$icon $label" else (icon ?: label)
        val estW = (display.length * 8 + 24).coerceAtLeast(base.width).coerceAtLeast(36)
        val estH = when (styleSnapshot.size) {
            BootstrapSize.SMALL -> 28
            BootstrapSize.LARGE -> 42
            else -> 34
        }.coerceAtLeast(base.height)
        return Size(estW, estH)
    }

    override fun setText(text: String) {
        super.setText(text)
        syncLabelAndCssClassesToLiveWidget()
    }

    override fun setIcon(icon: String?) {
        super.setIcon(icon)
        syncLabelAndCssClassesToLiveWidget()
    }

    override fun setSize(size: BootstrapSize?) {
        super.setSize(size)
        onButtonModeChanged()
    }

    override fun setType(type: BootstrapType?) {
        super.setType(type)
        onButtonModeChanged()
    }

    override fun setBackgroundType(type: BootstrapType?) {
        super.setBackgroundType(type)
        onButtonModeChanged()
    }

    override fun setTextColorType(type: BootstrapType?) {
        super.setTextColorType(type)
        onButtonModeChanged()
    }

    override fun setOutline(outline: Boolean) {
        super.setOutline(outline)
        onButtonModeChanged()
    }

    override fun setAttribute(name: String, value: String?) {
        super.setAttribute(name, value)
        onButtonModeChanged()
    }

    override fun setBackgroundGradient(gradient: Gradient?) {
        super.setBackgroundGradient(gradient)
        onButtonModeChanged()
    }

    override fun applyComponentBootstrap(bootstrap: ComponentBootstrap) {
        super.applyComponentBootstrap(bootstrap)
        onButtonModeChanged()
    }

    override fun applyComponentStyle(style: ComponentStyle) {
        super.applyComponentStyle(style)
        onButtonModeChanged()
    }

    override fun applyComputedStyle(style: ComputedStyle) {
        super.applyComputedStyle(style)
        onButtonModeChanged()
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
    override fun onNativeStyleSynced() {
        syncButtonModeMetadata()
        syncLabelAndCssClassesToLiveWidget()
        GtkWidgetLifecycleBridge.onStyleSynced(this)
    }

    override fun setFocusedState(focused: Boolean) {
        super.setFocusedState(focused)
        GtkWidgetLifecycleBridge.syncNativeFocus(this, isFocusedState())
    }

    override fun dispose() {
        clickRef?.dispose()
        clickRef = null
        super.dispose()
    }

    companion object {
        private val MANAGED_SEMANTIC_CLASSES = listOf(
            "suggested-action",
            "destructive-action",
            "flat",
            "pill",
            "gui-btn-outline",
            "gui-btn-primary",
            "gui-btn-secondary",
            "gui-btn-success",
            "gui-btn-danger",
            "gui-btn-warning",
            "gui-btn-info",
            "gui-btn-light",
            "gui-btn-dark",
            "gui-btn-link"
        )

        private var buttonCssProvider: COpaquePointer? = null
        private var lastThemeKey: String? = null

        private fun ensureThemeButtonCssProvider() {
            val dark = GtkRetainedContainerSupport.resolveDarkTheme()
            val cfg = BootstrapColorPalette.activeThemeConfig
            val key = "$dark|${cfg.hashCode()}"
            if (lastThemeKey == key && buttonCssProvider != null) return
            val display = Gtk4DynamicBindings.gdkDisplayGetDefault() ?: return
            val provider = buttonCssProvider ?: Gtk4DynamicBindings.gtkCssProviderNew()?.also { p ->
                buttonCssProvider = p
                Gtk4DynamicBindings.gtkStyleContextAddProviderForDisplay(display, p)
            } ?: return
            val css = buildButtonThemeCss(dark, cfg)
            Gtk4DynamicBindings.gtkCssProviderLoadCss(provider, css)
            lastThemeKey = key
        }

        private fun rgbaCss(c: RgbaColor): String =
            "rgba(${c.r}, ${c.g}, ${c.b}, ${(c.a / 255.0).coerceIn(0.0, 1.0)})"

        private fun buildButtonThemeCss(dark: Boolean, cfg: ThemeConfig): String {
            val sb = StringBuilder(1024)
            for (t in listOf(
                BootstrapType.PRIMARY,
                BootstrapType.SECONDARY,
                BootstrapType.SUCCESS,
                BootstrapType.DANGER,
                BootstrapType.WARNING,
                BootstrapType.INFO,
                BootstrapType.LIGHT,
                BootstrapType.DARK
            )) {
                val bg = BootstrapColorPalette.resolveColor(t, dark, cfg)
                val fg = BootstrapColorPalette.resolveForegroundOn(t, dark, cfg)
                val bgCss = rgbaCss(bg)
                val fgCss = rgbaCss(fg)
                sb.append("button.gui-btn-${t.cssSuffix}:not(.gui-btn-outline) { ")
                    .append("background-color: ").append(bgCss).append("; ")
                    .append("background-image: none; ")
                    .append("color: ").append(fgCss).append("; ")
                    .append("border-color: ").append(bgCss).append("; }\n")
                sb.append("button.gui-btn-outline.gui-btn-${t.cssSuffix} { ")
                    .append("background-color: transparent; ")
                    .append("background-image: none; ")
                    .append("color: ").append(bgCss).append("; ")
                    .append("border: 1.5px solid ").append(bgCss).append("; }\n")
                sb.append("button.gui-btn-outline.gui-btn-${t.cssSuffix}:hover { ")
                    .append("background-color: ").append(bgCss).append("; ")
                    .append("color: ").append(fgCss).append("; }\n")
            }
            val linkColor = rgbaCss(BootstrapColorPalette.resolveColor(BootstrapType.PRIMARY, dark, cfg))
            sb.append("button.gui-btn-link { background-color: transparent; background-image: none; color: ")
                .append(linkColor).append("; }\n")
            return sb.toString()
        }

        private val gtkButtonClickedCb = staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { _, userData ->
            val impl = userData?.asStableRef<GtkButtonNativeImpl>()?.get() ?: return@staticCFunction
            if (!impl.nativeWidget.pointerEventsNone && impl.nativeWidget.enabled) {
                impl.dispatchClick()
            }
        }
    }
}
