@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.desktop.widget.DesktopButtonNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapSize
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.style.theme.SystemThemeDetector
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.COpaquePointer

/**
 * Retained KDE button always wrapping a genuine **`QPushButton`** rendered natively by the active
 * KDE `Breeze` / `Fusion` `QStyle`:
 * - Default mode (`BootstrapType.NONE` and `!outline`) keeps flat `false` and the application's
 *   default Breeze `QPalette`.
 * - Semantic modes apply native `QPushButton::setFlat` (for outline/link buttons) and native
 *   `QPalette` (`Button`, `ButtonText`) resolved via [BootstrapColorPalette].
 */
open class KdeButtonNativeImpl(
    nativeWidget: KdeWidgetHandle = KdeWidgetHandle("QPushButton"),
    classPrefix: String = "btn"
) : DesktopButtonNativeImpl<KdeWidgetHandle>(nativeWidget, classPrefix) {

    val isUsingNativeQPushButton: Boolean
        get() = nativeWidget.activeKdeWidgetType == "QPushButton"

    init {
        KdeDynamicBindings.ensureQApplication()
        val ptr = KdeDynamicBindings.createQPushButton(nativeWidget.desiredParentPtr)
        nativeWidget.attachToQWidget(ptr, "QPushButton", customPainted = false)
        KdeVTableBridge.connectSignal(ptr, "clicked(bool)") {
            GenericUiThreading.runOnLogic {
                if (isEnabled()) dispatchClick()
            }
        }
        KdePainterSupport.attachStandardInputHooks(
            widgetPtr = ptr,
            impl = this,
            hookMask = KdeVTableBridge.HOOK_RESIZE,
            dispatchLeftClickOnRelease = false
        )
        syncLabelAndStyleToLiveWidget()
    }

    private fun buildDisplayLabel(): String {
        val icon = getIcon()?.takeIf { it.isNotBlank() }
        val text = getText()
        return if (icon != null && text.isNotEmpty()) "$icon $text" else (icon ?: text)
    }

    private fun syncLabelAndStyleToLiveWidget() {
        if (isDisposed || nativeWidget.kdeDestroyed) return
        val nativeDefault = isNativeDefaultButtonMode()
        nativeWidget.attributes["useSystemControlAppearance"] = nativeDefault.toString()
        val ptr = nativeWidget.qWidgetPtr?.takeIf { KdeVTableBridge.isPeerAlive(it) } ?: return
        KdeDynamicBindings.setAbstractButtonText(ptr, buildDisplayLabel())

        val bType = getType()?.takeIf { it != BootstrapType.NONE }
            ?: getBackgroundType()?.takeIf { it != BootstrapType.NONE }
        val outline = isOutline()
        if (nativeDefault || (bType == null && !outline)) {
            KdeDynamicBindings.setPushButtonFlat(ptr, false)
            KdeDynamicBindings.applyWidgetButtonPalette(ptr, buttonBg = null, buttonFg = null)
        } else {
            val isDark = SystemThemeDetector.resolveTheme(ThemeMode.AUTO) == ThemeMode.DARK
            val effective = bType ?: BootstrapType.SECONDARY
            val accent = BootstrapColorPalette.resolveColor(effective, isDark)
            val onAccent = BootstrapColorPalette.resolveForegroundOn(effective, isDark)
            if (effective == BootstrapType.LINK || outline) {
                val fg = if (effective == BootstrapType.LINK) {
                    BootstrapColorPalette.resolveColor(BootstrapType.PRIMARY, isDark)
                } else {
                    accent
                }
                KdeDynamicBindings.setPushButtonFlat(ptr, true)
                KdeDynamicBindings.applyWidgetButtonPalette(ptr, buttonBg = null, buttonFg = fg)
            } else {
                KdeDynamicBindings.setPushButtonFlat(ptr, false)
                KdeDynamicBindings.applyWidgetButtonPalette(ptr, buttonBg = accent, buttonFg = onAccent)
            }
        }
        KdeDynamicBindings.updateWidget(ptr)
    }

    override fun onButtonModeChanged() {
        syncLabelAndStyleToLiveWidget()
    }

    override fun setText(text: String) {
        super.setText(text)
        syncLabelAndStyleToLiveWidget()
    }

    override fun setIcon(icon: String?) {
        super.setIcon(icon)
        syncLabelAndStyleToLiveWidget()
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val base = super.measureIntrinsicSize(maxWidth, maxHeight)
        val label = buildDisplayLabel()
        if (label.isEmpty()) return base
        val fontSize = lastComputedStyle?.fontSizePx ?: 14f
        val measured = KdeDynamicBindings.measureTextSize(label, fontSize)
        val padX = when (styleSnapshot.size) {
            BootstrapSize.SMALL -> 18.0
            BootstrapSize.LARGE -> 30.0
            else -> 24.0
        }
        val padY = when (styleSnapshot.size) {
            BootstrapSize.SMALL -> 10.0
            BootstrapSize.LARGE -> 18.0
            else -> 14.0
        }
        return Size(
            maxOf(base.width.toFloat(), (measured.first + padX).toFloat()),
            maxOf(base.height.toFloat(), (measured.second + padY).toFloat())
        )
    }

    override fun onNativeBoundsChanged(rect: Rect) { nativeWidget.applyBoundsToNative(rect) }
    override fun onNativeVisibilityChanged(visible: Boolean) { nativeWidget.syncVisibilityToNative(visible) }
    override fun onNativeEnabledChanged(enabled: Boolean) { nativeWidget.qWidgetPtr?.let { KdeDynamicBindings.setWidgetEnabled(it, enabled) } }
    override fun onNativeOpacityChanged(opacity: Float) { nativeWidget.requestRepaint() }
    override fun onNativeTooltipChanged(tooltip: String?) {
        KdePainterSupport.applyCommonWidgetProperties(nativeWidget, customCursorState, nativeWidget.isSelfPointerEventsNone(), tooltip, getOpacity())
    }
    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        KdePainterSupport.applyCommonWidgetProperties(nativeWidget, cursor, nativeWidget.isSelfPointerEventsNone(), nativeWidget.tooltipText, getOpacity())
    }
    override fun onNativePointerEventsChanged(pointerEventsNone: Boolean) {
        KdePainterSupport.applyCommonWidgetProperties(nativeWidget, customCursorState, pointerEventsNone, nativeWidget.tooltipText, getOpacity())
    }
    override fun onNativeTransformChanged(transform: CssTransform?) { nativeWidget.requestRepaint() }
    override fun onNativeStyleSynced() { syncLabelAndStyleToLiveWidget() }
    override fun requestRepaintNative() { nativeWidget.requestRepaint() }

    override fun setFocusedState(focused: Boolean) {
        super.setFocusedState(focused)
        KdePainterSupport.syncWidgetFocus(this, isFocusedState())
    }

    override fun dispose() {
        nativeWidget.destroyQWidget()
        super.dispose()
    }
}
