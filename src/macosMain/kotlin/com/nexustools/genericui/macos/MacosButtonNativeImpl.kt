@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.desktop.widget.DesktopButtonNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapSize
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.style.mapper.BootstrapStyleSnapshot
import com.nexustools.genericui.style.transition.CssTransitionSpec
import kotlinx.cinterop.useContents
import platform.AppKit.NSBezelStyleFlexiblePush
import platform.AppKit.NSBezelStylePush
import platform.AppKit.NSButton
import platform.AppKit.NSControlSizeLarge
import platform.AppKit.NSControlSizeRegular
import platform.AppKit.NSControlSizeSmall
import platform.AppKit.NSImageLeading
import platform.AppKit.NSImageOnly
import platform.AppKit.NSLineBreakByTruncatingTail
import platform.AppKit.NSNoImage
import platform.AppKit.alignmentRectForFrame
import platform.AppKit.fittingSize
import platform.Foundation.NSMakeRect
import platform.Foundation.NSNumber
import platform.Foundation.NSSelectorFromString
import platform.Foundation.numberWithInteger
import platform.Foundation.setValue
import kotlin.math.ceil

/**
 * macOS AppKit retained implementation of [DesktopButtonNativeImpl] wrapping a genuine [NSButton].
 *
 * - Default mode (`BootstrapType.NONE` and `!outline`) leaves the native `NSButton` unmodified.
 * - Semantic modes use native AppKit button styling (`bezelColor` and `hasDestructiveAction` for
 *   filled buttons, and borderless `showsBorderOnlyWhileMouseInside` + `contentTintColor` for
 *   outline and link variants) with template `NSImage` tinting.
 */
class MacosButtonNativeImpl(
    nativeWidget: MacosViewHandle,
    classPrefix: String = "btn"
) : DesktopButtonNativeImpl<MacosViewHandle>(nativeWidget, classPrefix) {

    private val actionTarget = MacosControlActionTarget {
        if (nativeWidget.enabled && !isPointerInteractionDisabled()) {
            dispatchClick()
        }
    }

    private var nativeButton: NSButton? = null
    private var syncedButtonRef: NSButton? = null
    private var syncedText: String? = null
    private var syncedIcon: String? = null
    private var syncedType: BootstrapType? = null
    private var syncedOutline: Boolean = false
    private var syncedSize: BootstrapSize? = null
    private var syncedEnabled: Boolean = true
    private var syncedVisible: Boolean = true
    private var syncedOpacity: Float = -1f
    private var syncedTooltip: String? = null
    private var syncedDarkTheme: Boolean = false
    private var syncedTallBezel: Boolean = false

    private var cachedIntrinsicButtonRef: NSButton? = null
    private var cachedIntrinsicText: String? = null
    private var cachedIntrinsicIcon: String? = null
    private var cachedIntrinsicType: BootstrapType? = null
    private var cachedIntrinsicOutline: Boolean = false
    private var cachedIntrinsicSizeEnum: BootstrapSize? = null
    private var cachedIntrinsicSnapshot: BootstrapStyleSnapshot? = null
    private var cachedIntrinsicTallBezel: Boolean = false
    private var cachedIntrinsicSize: Size? = null

    init {
        nativeWidget.viewMaterializer = { ensureNativeButton() }
        if (MacosAppKitDispatch.isMainThread()) {
            ensureNativeButton()
        }
    }

    private fun ensureNativeButton(): NSButton {
        nativeButton?.let { existing ->
            if (nativeWidget.nsView == null) nativeWidget.nsView = existing
            return existing
        }
        val btn = NSButton.buttonWithTitle(
            title = buttonText,
            target = actionTarget,
            action = actionTarget.actionSelector
        )
        btn.setBezelStyle(NSBezelStylePush)
        nativeButton = btn
        nativeWidget.nsView = btn
        syncNativeButtonState(btn)
        return btn
    }

    private fun syncNativeButtonState(btn: NSButton, force: Boolean = false) {
        val bType = buttonType?.takeIf { it != BootstrapType.NONE }
        val tallBezel = nativeWidget.bounds.height > 34
        val darkTheme = MacosAppKitAppearance.isDarkTheme(nativeWidget)
        val effOpacity = nativeWidget.opacity.coerceIn(0f, 1f)
        val sizeEnum = styleSnapshot.size
        val tooltip = nativeWidget.tooltipText
        if (!force &&
            syncedButtonRef === btn &&
            syncedText == buttonText &&
            syncedIcon == buttonIcon &&
            syncedType == bType &&
            syncedOutline == isOutline &&
            syncedSize == sizeEnum &&
            syncedEnabled == nativeWidget.enabled &&
            syncedVisible == nativeWidget.visible &&
            syncedOpacity == effOpacity &&
            syncedTooltip == tooltip &&
            syncedDarkTheme == darkTheme &&
            syncedTallBezel == tallBezel
        ) {
            return
        }
        syncedButtonRef = btn
        syncedText = buttonText
        syncedIcon = buttonIcon
        syncedType = bType
        syncedOutline = isOutline
        syncedSize = sizeEnum
        syncedEnabled = nativeWidget.enabled
        syncedVisible = nativeWidget.visible
        syncedOpacity = effOpacity
        syncedTooltip = tooltip
        syncedDarkTheme = darkTheme
        syncedTallBezel = tallBezel

        val borderlessMode = bType == BootstrapType.LINK || isOutline
        val useAlignmentRect = !borderlessMode
        nativeWidget.attributes["usesAppKitAlignmentRect"] = useAlignmentRect

        btn.setEnabled(nativeWidget.enabled)
        btn.setHidden(!nativeWidget.visible)
        btn.setAlphaValue(effOpacity.toDouble())
        btn.setToolTip(tooltip)

        val nsSizeEnum = when (sizeEnum) {
            BootstrapSize.SM -> NSControlSizeSmall
            BootstrapSize.LG -> NSControlSizeLarge
            else -> NSControlSizeRegular
        }
        btn.setControlSize(nsSizeEnum)

        val hasTintProminence = btn.respondsToSelector(SEL_SET_TINT_PROMINENCE)
        if (bType == null && !isOutline) {
            btn.setBordered(true)
            btn.setShowsBorderOnlyWhileMouseInside(false)
            btn.setBezelStyle(if (tallBezel) NSBezelStyleFlexiblePush else NSBezelStylePush)
            btn.setBezelColor(null)
            btn.setContentTintColor(null)
            btn.setHasDestructiveAction(false)
            if (hasTintProminence) {
                btn.setValue(NSNumber.numberWithInteger(0L), forKey = "tintProminence")
            }
        } else if (!borderlessMode && bType != null) {
            val accent = BootstrapColorPalette.resolveColor(bType, darkTheme)
            btn.setBordered(true)
            btn.setShowsBorderOnlyWhileMouseInside(false)
            btn.setBezelStyle(if (tallBezel) NSBezelStyleFlexiblePush else NSBezelStylePush)
            btn.setBezelColor(MacosAppKitAppearance.toNsColor(accent))
            btn.setContentTintColor(null)
            btn.setHasDestructiveAction(false)
            if (hasTintProminence) {
                btn.setValue(NSNumber.numberWithInteger(2L), forKey = "tintProminence")
            }
        } else {
            val tintType = when {
                bType == BootstrapType.LINK -> BootstrapType.PRIMARY
                bType != null -> bType
                else -> BootstrapType.SECONDARY
            }
            val tint = BootstrapColorPalette.resolveColor(tintType, darkTheme)
            btn.setBordered(false)
            btn.setShowsBorderOnlyWhileMouseInside(isOutline && bType != BootstrapType.LINK)
            btn.setBezelStyle(if (tallBezel) NSBezelStyleFlexiblePush else NSBezelStylePush)
            btn.setBezelColor(null)
            btn.setContentTintColor(MacosAppKitAppearance.toNsColor(tint))
            btn.setHasDestructiveAction(bType == BootstrapType.DANGER)
            if (hasTintProminence) {
                btn.setValue(NSNumber.numberWithInteger(0L), forKey = "tintProminence")
            }
        }

        val icon = buttonIcon?.takeIf { it.isNotBlank() }
        val img = if (icon != null) {
            MacosAppKitAppearance.resolveNsImage(icon, desiredPx = 16, templateIfMonochrome = true)
        } else {
            null
        }
        if (img != null) {
            btn.setTitle(buttonText)
            btn.setImage(img)
            btn.setImagePosition(if (buttonText.isNotEmpty()) NSImageLeading else NSImageOnly)
        } else {
            val displayTitle = when {
                icon != null && !icon.startsWith("res://") && !icon.startsWith("drawable://") && !icon.startsWith("http") ->
                    if (buttonText.isNotEmpty()) "$icon $buttonText" else icon
                else -> buttonText
            }
            btn.setTitle(displayTitle)
            btn.setImage(null)
            btn.setImagePosition(NSNoImage)
        }
        btn.setLineBreakMode(NSLineBreakByTruncatingTail)
    }

    private fun syncActiveViewState() {
        cachedIntrinsicSize = null
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { syncActiveViewState() }
            return
        }
        syncNativeButtonState(ensureNativeButton())
    }

    override fun onButtonModeChanged() {
        syncActiveViewState()
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val btn = nativeButton
        if (btn != null && MacosAppKitDispatch.isMainThread()) {
            val tallBezel = nativeWidget.bounds.height > 34
            val bType = buttonType?.takeIf { it != BootstrapType.NONE }
            val cached = cachedIntrinsicSize
            if (cached != null &&
                cachedIntrinsicButtonRef === btn &&
                cachedIntrinsicText == buttonText &&
                cachedIntrinsicIcon == buttonIcon &&
                cachedIntrinsicType == bType &&
                cachedIntrinsicOutline == isOutline &&
                cachedIntrinsicSizeEnum == styleSnapshot.size &&
                cachedIntrinsicSnapshot == styleSnapshot &&
                cachedIntrinsicTallBezel == tallBezel
            ) {
                return cached
            }
            syncNativeButtonState(btn)
            val fit = btn.fittingSize()
            val measured = if (btn.isBordered()) {
                val aligned = btn.alignmentRectForFrame(fit.useContents { NSMakeRect(0.0, 0.0, width, height) })
                aligned.useContents {
                    Size(ceil(size.width).toInt().coerceAtLeast(36), ceil(size.height).toInt().coerceAtLeast(24))
                }
            } else {
                fit.useContents {
                    Size((ceil(width).toInt() + 8).coerceAtLeast(28), (ceil(height).toInt() + 4).coerceAtLeast(24))
                }
            }
            cachedIntrinsicButtonRef = btn
            cachedIntrinsicText = buttonText
            cachedIntrinsicIcon = buttonIcon
            cachedIntrinsicType = bType
            cachedIntrinsicOutline = isOutline
            cachedIntrinsicSizeEnum = styleSnapshot.size
            cachedIntrinsicSnapshot = styleSnapshot
            cachedIntrinsicTallBezel = tallBezel
            cachedIntrinsicSize = measured
            return measured
        }
        val baseW = (buttonText.length * 8 + (if (!buttonIcon.isNullOrBlank()) 20 else 0) + 24).coerceAtLeast(48)
        val baseH = when (styleSnapshot.size) {
            BootstrapSize.SM -> 28
            BootstrapSize.LG -> 42
            else -> 34
        }
        return Size(baseW, baseH)
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { onNativeBoundsChanged(nativeWidget.bounds) }
            return
        }
        val btn = ensureNativeButton()
        val tallBezel = rect.height > 34
        if (tallBezel != syncedTallBezel) {
            syncedTallBezel = tallBezel
            btn.setBezelStyle(if (tallBezel) NSBezelStyleFlexiblePush else NSBezelStylePush)
        }
        MacosAppKitDispatch.applyFrameAndTransform(
            view = btn,
            bounds = rect,
            transform = nativeWidget.cssTransform,
            useAlignmentRectExpansion = btn.isBordered()
        )
    }

    override fun onNativeVisibilityChanged(visible: Boolean) {
        nativeButton?.setHidden(!visible)
    }

    override fun onNativeEnabledChanged(enabled: Boolean) {
        syncActiveViewState()
    }

    override fun onNativeOpacityChanged(opacity: Float) {
        nativeButton?.setAlphaValue(opacity.coerceIn(0f, 1f).toDouble())
    }

    override fun onNativeTooltipChanged(tooltip: String?) {
        nativeButton?.setToolTip(tooltip)
    }

    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        nativeButton?.let { v ->
            v.window?.invalidateCursorRectsForView(v)
        }
    }

    override fun onNativeTransformChanged(transform: CssTransform?) {
        nativeWidget.cssTransform = transform
        val btn = nativeButton ?: return
        MacosAppKitDispatch.applyFrameAndTransform(
            view = btn,
            bounds = nativeWidget.bounds,
            transform = transform,
            useAlignmentRectExpansion = btn.isBordered()
        )
    }

    override fun onNativeStyleSynced() {
        syncActiveViewState()
    }

    override fun setText(text: String) {
        super.setText(text)
        syncActiveViewState()
    }

    override fun setIcon(icon: String?) {
        super.setIcon(icon)
        syncActiveViewState()
    }

    override fun applyComponentBootstrap(bootstrap: ComponentBootstrap) {
        super.applyComponentBootstrap(bootstrap)
        syncActiveViewState()
    }

    override fun applyComponentStyle(style: ComponentStyle) {
        super.applyComponentStyle(style)
        syncActiveViewState()
    }

    override fun applyComputedStyle(style: ComputedStyle) {
        super.applyComputedStyle(style)
        syncActiveViewState()
    }

    override fun runNativeTransition(
        property: String,
        fromValue: String?,
        toValue: String,
        spec: CssTransitionSpec,
        onComplete: () -> Unit
    ): Boolean {
        val handled = super.runNativeTransition(property, fromValue, toValue, spec, onComplete)
        if (property.equals("opacity", ignoreCase = true)) {
            val fromAlpha = (fromValue?.toFloatOrNull() ?: getOpacity()).coerceIn(0f, 1f)
            val targetAlpha = (toValue.toFloatOrNull() ?: getOpacity()).coerceIn(0f, 1f)
            nativeWidget.attributes["transitionFromOpacity"] = fromAlpha
            nativeWidget.attributes["transitionToOpacity"] = targetAlpha
            setOpacity(targetAlpha)
            if (spec.durationMs <= 0f) {
                onComplete()
            }
            return true
        }
        return handled
    }

    override fun dispose() {
        val btn = nativeButton
        nativeButton = null
        MacosAppKitDispatch.detachViewOnMain(btn)
        super.dispose()
    }

    private companion object {
        val SEL_SET_TINT_PROMINENCE get() = NSSelectorFromString("setTintProminence:")
    }
}
