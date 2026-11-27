@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ObjCClass
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.interpretObjCPointerOrNull
import kotlinx.cinterop.invoke
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents
import platform.AppKit.NSAnimationContext
import platform.AppKit.NSBackingStoreType
import platform.AppKit.NSFloatingWindowLevel
import platform.AppKit.NSNormalWindowLevel
import platform.AppKit.NSResponder
import platform.AppKit.NSScreen
import platform.AppKit.NSTitlebarSeparatorStyle
import platform.AppKit.NSView
import platform.AppKit.NSWindow
import platform.AppKit.NSWindowCloseButton
import platform.AppKit.NSWindowCollectionBehaviorFullScreenAuxiliary
import platform.AppKit.NSWindowCollectionBehaviorFullScreenNone
import platform.AppKit.NSWindowCollectionBehaviorFullScreenPrimary
import platform.AppKit.NSWindowDocumentIconButton
import platform.AppKit.NSWindowMiniaturizeButton
import platform.AppKit.NSWindowOrderingMode
import platform.AppKit.NSWindowOut
import platform.AppKit.NSWindowStyleMask
import platform.AppKit.NSWindowStyleMaskBorderless
import platform.AppKit.NSWindowStyleMaskClosable
import platform.AppKit.NSWindowStyleMaskFullScreen
import platform.AppKit.NSWindowStyleMaskFullSizeContentView
import platform.AppKit.NSWindowStyleMaskMiniaturizable
import platform.AppKit.NSWindowStyleMaskResizable
import platform.AppKit.NSWindowStyleMaskTitled
import platform.AppKit.NSWindowTitleHidden
import platform.AppKit.NSWindowTitleVisible
import platform.AppKit.NSWindowZoomButton
import platform.AppKit.NSWorkspace
import platform.AppKit.accessibilityDisplayShouldReduceMotion
import platform.Foundation.NSMakePoint
import platform.Foundation.NSMakeRect
import platform.Foundation.NSMakeSize
import platform.Foundation.NSRect
import platform.Foundation.NSSize
import platform.Foundation.NSUserDefaults
import platform.QuartzCore.CAMediaTimingFunction
import platform.QuartzCore.kCAMediaTimingFunctionEaseInEaseOut
import platform.darwin.IMP
import platform.darwin.NSInteger
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.objc.class_addMethod
import platform.objc.class_getInstanceMethod
import platform.objc.class_getMethodImplementation
import platform.objc.class_getSuperclass
import platform.objc.method_getImplementation
import platform.objc.method_getTypeEncoding
import platform.objc.object_getClass
import platform.objc.sel_registerName

internal val UNDECORATED_WINDOWED_STYLE_MASK: NSWindowStyleMask =
    NSWindowStyleMaskBorderless or
        NSWindowStyleMaskClosable or
        NSWindowStyleMaskMiniaturizable or
        NSWindowStyleMaskResizable

internal val UNDECORATED_TRANSITION_STYLE_MASK: NSWindowStyleMask =
    NSWindowStyleMaskTitled or
        NSWindowStyleMaskClosable or
        NSWindowStyleMaskMiniaturizable or
        NSWindowStyleMaskResizable or
        NSWindowStyleMaskFullSizeContentView

/**
 * Custom AppKit [NSWindow] subclass enabling key/main activation, native 8-edge resize, miniaturize,
 * and smooth macOS Spaces fullscreen transitions for undecorated full-size-content-view windows
 * with hidden standard traffic-light buttons.
 */
internal class MacosHostWindow(
    contentRect: CValue<NSRect>,
    styleMask: NSWindowStyleMask,
    backing: NSBackingStoreType,
    defer: Boolean,
    var isToolModal: Boolean = false,
    var isUndecoratedHostWindow: Boolean = false
) : NSWindow(
    contentRect = contentRect,
    styleMask = styleMask,
    backing = backing,
    defer = defer
) {
    private var preFullscreenFrame: CValue<NSRect>? = null
    private var preFullscreenAlwaysOnTop: Boolean = false
    private var preFullscreenWindowLevel: Long = NSNormalWindowLevel
    private var preFullscreenAspectRatio: CValue<NSSize>? = null
    internal var isTransitioningFullscreen: Boolean = false
        private set
    internal var isClosingWindow: Boolean = false
    private var didReceiveWillFullscreenCallback: Boolean = false
    internal var isUndecoratedMaximized: Boolean = false
        private set
    internal var isAnimatingMaximize: Boolean = false
        private set
    private var maximizeAnimationGeneration: Long = 0L
    private var preZoomFrame: CValue<NSRect>? = null
    private var preZoomAspectRatio: CValue<NSSize>? = null
    internal var onMaximizedStateChanged: ((Boolean) -> Unit)? = null
    internal var forceActiveKeyAppearanceForCapture: Boolean = false
    internal var isPerformingNativeZoom: Boolean = false
    var isNativeOverlayTitleBarHostWindow: Boolean = false
    internal var usesSquareWindowCorners: Boolean = false
    private var hasSyncedOverlayTitleBarChrome: Boolean = false
    private var lastChromeFullscreen: Boolean = false
    private var lastChromeVisible: Boolean = false
    private var lastChromeAlphaQuantized: Int = -1
    private var lastChromeShowClose: Boolean = true
    private var lastChromeShowMinimize: Boolean = true
    private var lastChromeShowMaximize: Boolean = true
    private var lastChromeShowTitle: Boolean = true
    private var lastChromeShowWindowIcon: Boolean = true
    private var lastChromeTitleText: String = ""

    internal fun invalidateNativeOverlayTitleBarChromeCache() {
        hasSyncedOverlayTitleBarChrome = false
    }

    private fun isPureBorderlessHostWindow(): Boolean =
        isUndecoratedHostWindow && !isNativeOverlayTitleBarHostWindow && !isToolModal

    init {
        MacosHostWindowCornerHooks.installOverridesIfNeeded(this)
        if (isPureBorderlessHostWindow()) {
            applyUndecoratedChromeAttributes()
            hideStandardWindowButtons()
        }
    }

    override fun isKeyWindow(): Boolean =
        if (forceActiveKeyAppearanceForCapture) true else super.isKeyWindow()

    override fun isMainWindow(): Boolean =
        if (forceActiveKeyAppearanceForCapture) true else super.isMainWindow()

    override fun canBecomeKeyWindow(): Boolean = true

    override fun canBecomeMainWindow(): Boolean = !isToolModal

    override fun isZoomed(): Boolean =
        if (isPureBorderlessHostWindow()) isUndecoratedMaximized else super.isZoomed()

    internal fun suspendAspectRatioForNativeZoom() {
        val curAspect = contentAspectRatio
        val nonZero = curAspect.useContents {
            if (width > 0.0 && height > 0.0) curAspect else null
        }
        if (nonZero != null) {
            preZoomAspectRatio = nonZero
        }
        setContentAspectRatio(NSMakeSize(0.0, 0.0))
        setResizeIncrements(NSMakeSize(1.0, 1.0))
    }

    private fun restorePreZoomAspectRatioAfterNativeZoom() {
        val restoreAspect = preZoomAspectRatio
        preZoomAspectRatio = null
        if (restoreAspect != null &&
            !isClosingWindow &&
            (styleMask and NSWindowStyleMaskFullScreen) == 0UL &&
            !super.isZoomed()
        ) {
            contentAspectRatio = restoreAspect
        }
    }

    override fun zoom(sender: Any?) {
        if (isNativeOverlayTitleBarHostWindow && !isToolModal) {
            val wasZoomed = super.isZoomed()
            isPerformingNativeZoom = true
            var zoomedAfter = wasZoomed
            try {
                if (!wasZoomed) {
                    suspendAspectRatioForNativeZoom()
                    super.zoom(sender)
                } else {
                    super.zoom(sender)
                    restorePreZoomAspectRatioAfterNativeZoom()
                }
                zoomedAfter = super.isZoomed()
            } finally {
                isPerformingNativeZoom = false
            }
            onMaximizedStateChanged?.invoke(zoomedAfter)
            return
        }
        super.zoom(sender)
    }

    override fun performZoom(sender: Any?) {
        if (isNativeOverlayTitleBarHostWindow && !isToolModal) {
            zoom(sender)
            return
        }
        if (isPureBorderlessHostWindow()) {
            toggleMaximizeOrRestore()
            return
        }
        super.performZoom(sender)
    }

    override fun close() {
        isClosingWindow = true
        isTransitioningFullscreen = false
        isAnimatingMaximize = false
        maximizeAnimationGeneration++
        MacosHostWindowCornerHooks.setSquareCorners(this, false)
        super.close()
    }

    override fun makeFirstResponder(aResponder: NSResponder?): Boolean {
        if (aResponder != null && MacosFocusSupport.isResponderInNonFocusableSubtree(aResponder)) {
            return false
        }
        return super.makeFirstResponder(aResponder)
    }

    override fun miniaturize(sender: Any?) {
        if (isClosingWindow) return
        isAnimatingMaximize = false
        maximizeAnimationGeneration++
        if (isPureBorderlessHostWindow()) {
            val desiredMask = UNDECORATED_TRANSITION_STYLE_MASK or (styleMask and NSWindowStyleMaskFullScreen)
            if (styleMask != desiredMask) {
                val savedFrame = frame
                styleMask = desiredMask
                setFrame(savedFrame, display = false)
                syncContentViewFillsWindowFrame()
            }
            applyUndecoratedChromeAttributes()
            hideStandardWindowButtons()
        }
        if (level > NSNormalWindowLevel && level <= NSFloatingWindowLevel) {
            level = NSNormalWindowLevel
        }
        super.miniaturize(sender)
    }

    override fun deminiaturize(sender: Any?) {
        super.deminiaturize(sender)
        restoreUndecoratedStyleMaskIfNeeded()
    }

    override fun toggleFullScreen(sender: Any?) {
        if (isClosingWindow || isTransitioningFullscreen || miniaturized) return
        val entering = (styleMask and NSWindowStyleMaskFullScreen) == 0UL
        if (!visible) {
            // Never call super.toggleFullScreen on an un-ordered / hidden window;
            // SkyLight SLSWindowServerClientSpaceAddWindowsAndRemoveFromSpaces crashes on invalid windows.
            return
        }
        isAnimatingMaximize = false
        maximizeAnimationGeneration++
        if (entering) {
            isTransitioningFullscreen = true
            preFullscreenFrame = frame
            preFullscreenAlwaysOnTop = level > NSNormalWindowLevel
            preFullscreenWindowLevel = level
            val curAspect = contentAspectRatio
            preFullscreenAspectRatio = curAspect.useContents {
                if (width > 0.0 && height > 0.0) curAspect else null
            }

            if (level > NSNormalWindowLevel) {
                level = NSNormalWindowLevel
            }
            setContentAspectRatio(NSMakeSize(0.0, 0.0))
            setResizeIncrements(NSMakeSize(1.0, 1.0))

            var behavior = collectionBehavior
            behavior = behavior and NSWindowCollectionBehaviorFullScreenAuxiliary.inv()
            behavior = behavior and NSWindowCollectionBehaviorFullScreenNone.inv()
            behavior = behavior or NSWindowCollectionBehaviorFullScreenPrimary
            collectionBehavior = behavior

            val isBorderless = (styleMask and NSWindowStyleMaskTitled) == 0UL
            if (isPureBorderlessHostWindow() && (isBorderless || styleMask != UNDECORATED_TRANSITION_STYLE_MASK)) {
                val savedFrame = frame
                styleMask = UNDECORATED_TRANSITION_STYLE_MASK
                setFrame(savedFrame, display = false)
                applyUndecoratedChromeAttributes()
                hideStandardWindowButtons()
                syncContentViewFillsWindowFrame()
                dispatch_async(dispatch_get_main_queue()) {
                    if (isClosingWindow || miniaturized || !visible) {
                        onFullscreenTransitionFinished(
                            succeeded = false,
                            inFullscreenNow = (styleMask and NSWindowStyleMaskFullScreen) != 0UL
                        )
                        return@dispatch_async
                    }
                    if ((styleMask and NSWindowStyleMaskFullScreen) != 0UL) {
                        isTransitioningFullscreen = false
                        return@dispatch_async
                    }
                    didReceiveWillFullscreenCallback = false
                    super.toggleFullScreen(sender)
                    if (isTransitioningFullscreen && !didReceiveWillFullscreenCallback && (styleMask and NSWindowStyleMaskFullScreen) == 0UL) {
                        onFullscreenTransitionFinished(succeeded = false, inFullscreenNow = false)
                    }
                }
                return
            }

            if (isPureBorderlessHostWindow()) {
                applyUndecoratedChromeAttributes()
                hideStandardWindowButtons()
                syncContentViewFillsWindowFrame()
            } else if (isNativeOverlayTitleBarHostWindow) {
                syncContentViewFillsWindowFrame()
            }
            didReceiveWillFullscreenCallback = false
            super.toggleFullScreen(sender)
            if (isTransitioningFullscreen && !didReceiveWillFullscreenCallback && (styleMask and NSWindowStyleMaskFullScreen) == 0UL) {
                onFullscreenTransitionFinished(succeeded = false, inFullscreenNow = false)
            }
        } else {
            isTransitioningFullscreen = true
            setContentAspectRatio(NSMakeSize(0.0, 0.0))
            setResizeIncrements(NSMakeSize(1.0, 1.0))
            if (isPureBorderlessHostWindow()) {
                applyUndecoratedChromeAttributes()
                hideStandardWindowButtons()
                syncContentViewFillsWindowFrame()
            } else if (isNativeOverlayTitleBarHostWindow) {
                syncContentViewFillsWindowFrame()
            }
            didReceiveWillFullscreenCallback = false
            super.toggleFullScreen(sender)
            if (isTransitioningFullscreen && !didReceiveWillFullscreenCallback && (styleMask and NSWindowStyleMaskFullScreen) != 0UL) {
                isTransitioningFullscreen = false
            }
        }
    }

    fun markFullscreenTransitionWillChange() {
        didReceiveWillFullscreenCallback = true
        isTransitioningFullscreen = true
    }

    fun onFullscreenTransitionFinished(succeeded: Boolean, inFullscreenNow: Boolean) {
        isTransitioningFullscreen = false
        invalidateNativeOverlayTitleBarChromeCache()
        if (!inFullscreenNow && !miniaturized) {
            val savedAlwaysOnTop = preFullscreenAlwaysOnTop
            val savedLevel = preFullscreenWindowLevel
            val savedAspect = preFullscreenAspectRatio
            preFullscreenAlwaysOnTop = false
            preFullscreenWindowLevel = NSNormalWindowLevel
            preFullscreenAspectRatio = null

            restoreUndecoratedStyleMaskIfNeeded()
            if (savedAlwaysOnTop && savedLevel > NSNormalWindowLevel) {
                level = savedLevel
            }
            if (savedAspect != null && !isZoomed()) {
                contentAspectRatio = savedAspect
            }
        }
    }

    fun toggleMaximizeOrRestore() {
        setMaximized(!isZoomed())
    }

    fun setMaximized(maximized: Boolean) {
        if (isClosingWindow || isTransitioningFullscreen || miniaturized) return
        if ((styleMask and NSWindowStyleMaskFullScreen) != 0UL) return
        if (isNativeOverlayTitleBarHostWindow && !isToolModal) {
            if (maximized != super.isZoomed()) {
                zoom(null)
            }
            return
        }
        if (!isPureBorderlessHostWindow()) {
            if (maximized != super.isZoomed()) {
                super.zoom(null)
            }
            return
        }
        if (maximized == isUndecoratedMaximized) return
        val generation = ++maximizeAnimationGeneration
        if (maximized) {
            if (!isAnimatingMaximize) {
                preZoomFrame = frame
                val curAspect = contentAspectRatio
                preZoomAspectRatio = curAspect.useContents {
                    if (width > 0.0 && height > 0.0) curAspect else null
                }
            }
            setContentAspectRatio(NSMakeSize(0.0, 0.0))
            setResizeIncrements(NSMakeSize(1.0, 1.0))
            val targetScreen = screen ?: NSScreen.mainScreen ?: (NSScreen.screens.firstOrNull() as? NSScreen)
            isUndecoratedMaximized = true
            val targetFrame = targetScreen?.visibleFrame
            if (targetFrame == null) {
                isAnimatingMaximize = false
                syncContentViewFillsWindowFrame()
                onMaximizedStateChanged?.invoke(true)
                return
            }
            if (NSWorkspace.sharedWorkspace.accessibilityDisplayShouldReduceMotion) {
                isAnimatingMaximize = false
                setFrame(targetFrame, display = true)
                syncContentViewFillsWindowFrame()
                onMaximizedStateChanged?.invoke(true)
                return
            }
            val duration = animationResizeTime(targetFrame).coerceIn(0.20, 0.30)
            isAnimatingMaximize = true
            NSAnimationContext.runAnimationGroup(
                changes = { context ->
                    context?.duration = duration
                    context?.allowsImplicitAnimation = true
                    context?.timingFunction =
                        CAMediaTimingFunction.functionWithName(kCAMediaTimingFunctionEaseInEaseOut)
                            ?.let { interpretObjCPointerOrNull(it.objcPtr()) }
                    animator().setFrame(targetFrame, display = true)
                },
                completionHandler = {
                    if (generation != maximizeAnimationGeneration) return@runAnimationGroup
                    isAnimatingMaximize = false
                    if (!isClosingWindow && isUndecoratedMaximized) {
                        val settledFrame = (screen ?: NSScreen.mainScreen ?: (NSScreen.screens.firstOrNull() as? NSScreen))?.visibleFrame ?: targetFrame
                        setFrame(settledFrame, display = true)
                        syncContentViewFillsWindowFrame()
                        onMaximizedStateChanged?.invoke(true)
                    }
                }
            )
        } else {
            val restoreFrame = preZoomFrame
            val restoreAspect = preZoomAspectRatio
            isUndecoratedMaximized = false
            if (restoreFrame == null) {
                preZoomFrame = null
                preZoomAspectRatio = null
                isAnimatingMaximize = false
                if (restoreAspect != null && !isClosingWindow && !isUndecoratedMaximized &&
                    (styleMask and NSWindowStyleMaskFullScreen) == 0UL
                ) {
                    contentAspectRatio = restoreAspect
                }
                syncContentViewFillsWindowFrame()
                onMaximizedStateChanged?.invoke(false)
                return
            }
            if (NSWorkspace.sharedWorkspace.accessibilityDisplayShouldReduceMotion) {
                preZoomFrame = null
                preZoomAspectRatio = null
                isAnimatingMaximize = false
                setFrame(restoreFrame, display = true)
                if (restoreAspect != null && !isClosingWindow && !isUndecoratedMaximized &&
                    (styleMask and NSWindowStyleMaskFullScreen) == 0UL
                ) {
                    contentAspectRatio = restoreAspect
                }
                syncContentViewFillsWindowFrame()
                onMaximizedStateChanged?.invoke(false)
                return
            }
            val duration = animationResizeTime(restoreFrame).coerceIn(0.20, 0.30)
            isAnimatingMaximize = true
            NSAnimationContext.runAnimationGroup(
                changes = { context ->
                    context?.duration = duration
                    context?.allowsImplicitAnimation = true
                    context?.timingFunction =
                        CAMediaTimingFunction.functionWithName(kCAMediaTimingFunctionEaseInEaseOut)
                            ?.let { interpretObjCPointerOrNull(it.objcPtr()) }
                    animator().setFrame(restoreFrame, display = true)
                },
                completionHandler = {
                    if (generation != maximizeAnimationGeneration) return@runAnimationGroup
                    isAnimatingMaximize = false
                    preZoomFrame = null
                    preZoomAspectRatio = null
                    if (!isClosingWindow && !isUndecoratedMaximized &&
                        (styleMask and NSWindowStyleMaskFullScreen) == 0UL
                    ) {
                        setFrame(restoreFrame, display = true)
                        if (restoreAspect != null) {
                            contentAspectRatio = restoreAspect
                        }
                        syncContentViewFillsWindowFrame()
                        onMaximizedStateChanged?.invoke(false)
                    }
                }
            )
        }
    }

    fun clearMaximizedStateOnManualResize() {
        isAnimatingMaximize = false
        maximizeAnimationGeneration++
        if (isUndecoratedMaximized) {
            isUndecoratedMaximized = false
            preZoomFrame = null
            preZoomAspectRatio = null
        }
    }

    fun handleTitleBarDoubleClick() {
        if (isClosingWindow) return
        val action = NSUserDefaults.standardUserDefaults
            .stringForKey("AppleActionOnDoubleClick")
            ?.trim()
        when {
            action.equals("Minimize", ignoreCase = true) -> {
                if ((styleMask and NSWindowStyleMaskMiniaturizable) != 0UL || isUndecoratedHostWindow) {
                    miniaturize(null)
                }
            }
            action.equals("None", ignoreCase = true) -> Unit
            else -> if (isNativeOverlayTitleBarHostWindow && !isToolModal) performZoom(null) else toggleMaximizeOrRestore()
        }
    }

    fun applyUndecoratedChromeAttributes() {
        invalidateNativeOverlayTitleBarChromeCache()
        titlebarAppearsTransparent = true
        titleVisibility = NSWindowTitleHidden
        titlebarSeparatorStyle = NSTitlebarSeparatorStyle.NSTitlebarSeparatorStyleNone
        movableByWindowBackground = false
        hasShadow = true
    }

    fun titlebarContainerView(): NSView? {
        val themeFrame = contentView?.superview
        val btnContainer = standardWindowButton(NSWindowCloseButton)?.superview?.superview
        if (btnContainer != null && btnContainer !== themeFrame) {
            return btnContainer
        }
        if (themeFrame != null) {
            for (sub in themeFrame.subviews) {
                val view = sub as? NSView ?: continue
                if (view.toString().contains("NSTitlebarContainerView")) {
                    return view
                }
            }
        }
        return null
    }

    fun syncNativeOverlayTitleBarChrome(
        visible: Boolean,
        alpha: Double,
        showClose: Boolean,
        showMinimize: Boolean,
        showMaximize: Boolean,
        showTitle: Boolean,
        titleText: String,
        showWindowIcon: Boolean = true
    ): Boolean {
        val inFullscreen = (styleMask and NSWindowStyleMaskFullScreen) != 0UL
        val clampedAlpha = alpha.coerceIn(0.0, 1.0)
        val effectiveVisible = inFullscreen || (visible && clampedAlpha > 0.004)
        val effectiveAlpha = if (inFullscreen) 1.0 else if (effectiveVisible) clampedAlpha else 0.0
        val quantizedAlpha = if (effectiveVisible) (effectiveAlpha * 1000.0).toInt() else 0
        val container = titlebarContainerView()
        val containerWasVisible = container?.isHidden() == false

        if (hasSyncedOverlayTitleBarChrome &&
            lastChromeFullscreen == inFullscreen &&
            lastChromeVisible == effectiveVisible &&
            lastChromeAlphaQuantized == quantizedAlpha &&
            lastChromeShowClose == showClose &&
            lastChromeShowMinimize == showMinimize &&
            lastChromeShowMaximize == showMaximize &&
            lastChromeShowTitle == showTitle &&
            lastChromeShowWindowIcon == showWindowIcon &&
            lastChromeTitleText == titleText &&
            (container == null ||
                (container.isHidden() == !effectiveVisible &&
                    kotlin.math.abs(container.alphaValue - effectiveAlpha) < 0.002))
        ) {
            return false
        }

        if (inFullscreen) {
            container?.let {
                if (it.isHidden()) it.hidden = false
                if (kotlin.math.abs(it.alphaValue - 1.0) >= 0.002) it.alphaValue = 1.0
            }
            applyFullscreenMenuBarHoverChrome(titleText, showWindowIcon)
            hasSyncedOverlayTitleBarChrome = true
            lastChromeFullscreen = true
            lastChromeVisible = true
            lastChromeAlphaQuantized = 1000
            lastChromeShowClose = showClose
            lastChromeShowMinimize = showMinimize
            lastChromeShowMaximize = showMaximize
            lastChromeShowTitle = showTitle
            lastChromeShowWindowIcon = showWindowIcon
            lastChromeTitleText = titleText
            return false
        }

        if (!hasSyncedOverlayTitleBarChrome || lastChromeFullscreen || lastChromeTitleText != titleText) {
            if (this.title != titleText) {
                this.title = titleText
            }
        }
        if (!hasSyncedOverlayTitleBarChrome || lastChromeFullscreen) {
            titlebarAppearsTransparent = true
            titlebarSeparatorStyle = NSTitlebarSeparatorStyle.NSTitlebarSeparatorStyleNone
            movableByWindowBackground = false
            hasShadow = true
        }
        val targetTitleVisibility = if (showTitle) NSWindowTitleVisible else NSWindowTitleHidden
        if (titleVisibility != targetTitleVisibility) {
            titleVisibility = targetTitleVisibility
        }

        standardWindowButton(NSWindowCloseButton)?.let {
            if (it.isHidden() != !showClose) it.hidden = !showClose
            if (it.alphaValue != 1.0) it.alphaValue = 1.0
            if (it.isEnabled() != showClose) it.enabled = showClose
        }
        standardWindowButton(NSWindowMiniaturizeButton)?.let {
            if (it.isHidden() != !showMinimize) it.hidden = !showMinimize
            if (it.alphaValue != 1.0) it.alphaValue = 1.0
            if (it.isEnabled() != showMinimize) it.enabled = showMinimize
        }
        standardWindowButton(NSWindowZoomButton)?.let {
            if (it.isHidden() != !showMaximize) it.hidden = !showMaximize
            if (it.alphaValue != 1.0) it.alphaValue = 1.0
            if (it.isEnabled() != showMaximize) it.enabled = showMaximize
        }
        syncWindowDocumentIconVisibility(showWindowIcon)

        container?.let {
            if (!effectiveVisible) {
                if (it.alphaValue != 0.0) it.alphaValue = 0.0
                if (!it.isHidden()) it.hidden = true
            } else {
                if (it.isHidden()) it.hidden = false
                if (kotlin.math.abs(it.alphaValue - effectiveAlpha) >= 0.002) {
                    it.alphaValue = effectiveAlpha
                }
            }
        }

        hasSyncedOverlayTitleBarChrome = true
        lastChromeFullscreen = false
        lastChromeVisible = effectiveVisible
        lastChromeAlphaQuantized = quantizedAlpha
        lastChromeShowClose = showClose
        lastChromeShowMinimize = showMinimize
        lastChromeShowMaximize = showMaximize
        lastChromeShowTitle = showTitle
        lastChromeShowWindowIcon = showWindowIcon
        lastChromeTitleText = titleText
        return containerWasVisible && !effectiveVisible
    }

    fun applyFullscreenMenuBarHoverChrome(title: String, showWindowIcon: Boolean = true) {
        invalidateNativeOverlayTitleBarChromeCache()
        if (this.title != title) {
            this.title = title
        }
        titlebarAppearsTransparent = false
        titleVisibility = NSWindowTitleVisible
        titlebarSeparatorStyle = NSTitlebarSeparatorStyle.NSTitlebarSeparatorStyleAutomatic
        movableByWindowBackground = false
        titlebarContainerView()?.let { container ->
            container.hidden = false
            container.alphaValue = 1.0
        }
        showStandardWindowButtons(showMinimize = true, enableMinimize = false, showWindowIcon = showWindowIcon)
    }

    fun hideStandardWindowButtons() {
        invalidateNativeOverlayTitleBarChromeCache()
        standardWindowButton(NSWindowCloseButton)?.let {
            it.hidden = true
            it.alphaValue = 1.0
            it.enabled = true
        }
        standardWindowButton(NSWindowMiniaturizeButton)?.let {
            it.hidden = true
            it.alphaValue = 1.0
            it.enabled = true
        }
        standardWindowButton(NSWindowZoomButton)?.let {
            it.hidden = true
            it.alphaValue = 1.0
            it.enabled = true
        }
        syncWindowDocumentIconVisibility(showWindowIcon = false)
    }

    fun showStandardWindowButtons(
        showMinimize: Boolean = true,
        enableMinimize: Boolean = true,
        showWindowIcon: Boolean = true
    ) {
        invalidateNativeOverlayTitleBarChromeCache()
        titlebarContainerView()?.let { container ->
            container.hidden = false
            container.alphaValue = 1.0
        }
        standardWindowButton(NSWindowCloseButton)?.let {
            it.hidden = false
            it.alphaValue = 1.0
            it.enabled = true
        }
        standardWindowButton(NSWindowMiniaturizeButton)?.let {
            it.hidden = !showMinimize
            it.alphaValue = 1.0
            it.enabled = showMinimize && enableMinimize
        }
        standardWindowButton(NSWindowZoomButton)?.let {
            it.hidden = false
            it.alphaValue = 1.0
            it.enabled = true
        }
        syncWindowDocumentIconVisibility(showWindowIcon = showWindowIcon)
    }

    /**
     * Shows or hides the NSWindow titlebar document icon (`NSWindowDocumentIconButton`) without
     * affecting the application's Dock icon (`NSApplication.sharedApplication.applicationIconImage`).
     */
    fun syncWindowDocumentIconVisibility(showWindowIcon: Boolean) {
        standardWindowButton(NSWindowDocumentIconButton)?.let { docBtn ->
            val targetHidden = !showWindowIcon
            if (docBtn.isHidden() != targetHidden) docBtn.hidden = targetHidden
            val targetAlpha = if (showWindowIcon) 1.0 else 0.0
            if (kotlin.math.abs(docBtn.alphaValue - targetAlpha) >= 0.002) {
                docBtn.alphaValue = targetAlpha
            }
            if (docBtn.isEnabled() != showWindowIcon) docBtn.enabled = showWindowIcon
        }
    }

    fun syncContentViewFillsWindowFrame() {
        val cv = contentView ?: return
        var fullW = 0.0
        var fullH = 0.0
        contentRectForFrameRect(frame).useContents {
            fullW = size.width
            fullH = size.height
        }
        if (!fullW.isFinite() || !fullH.isFinite() || fullW <= 0.0 || fullH <= 0.0) return
        var needsFrameSync = false
        cv.frame.useContents {
            if (kotlin.math.abs(origin.x) > 0.5 ||
                kotlin.math.abs(origin.y) > 0.5 ||
                kotlin.math.abs(size.width - fullW) > 0.5 ||
                kotlin.math.abs(size.height - fullH) > 0.5
            ) {
                needsFrameSync = true
            }
        }
        if (needsFrameSync) {
            cv.setFrame(NSMakeRect(0.0, 0.0, fullW, fullH))
        }
        cv.bounds.useContents {
            if (origin.x != 0.0 || origin.y != 0.0) {
                cv.setBoundsOrigin(NSMakePoint(0.0, 0.0))
            }
        }
    }

    fun restoreUndecoratedStyleMaskIfNeeded() {
        invalidateNativeOverlayTitleBarChromeCache()
        val inFullscreen = (styleMask and NSWindowStyleMaskFullScreen) != 0UL
        if (inFullscreen || miniaturized) return
        if (!isPureBorderlessHostWindow()) {
            preFullscreenFrame = null
            return
        }
        val targetMask = UNDECORATED_WINDOWED_STYLE_MASK
        val savedFrame = preFullscreenFrame
        preFullscreenFrame = null
        val frameBeforeStrip = frame
        if (styleMask != targetMask) {
            styleMask = targetMask
            setFrame(savedFrame ?: frameBeforeStrip, display = true)
        } else if (savedFrame != null) {
            setFrame(savedFrame, display = true)
        }
        applyUndecoratedChromeAttributes()
        hideStandardWindowButtons()
        syncContentViewFillsWindowFrame()
        contentView?.layer?.cornerRadius = 0.0
    }

    override fun orderWindow(place: NSWindowOrderingMode, relativeTo: NSInteger) {
        super.orderWindow(place, relativeTo)
        if (place != NSWindowOut && usesSquareWindowCorners && (styleMask and NSWindowStyleMaskTitled) != 0UL) {
            syncSquareWindowCorners(enabled = true, forceRefresh = true)
        }
    }

    override fun invalidateCursorRectsForView(view: NSView) {
        if (MacosNativeWindowHost.visualTestMode) return
        super.invalidateCursorRectsForView(view)
    }

    internal fun syncSquareWindowCorners(enabled: Boolean, forceRefresh: Boolean = false) {
        MacosHostWindowCornerHooks.installOverridesIfNeeded(this)
        MacosHostWindowCornerHooks.setSquareCorners(this, enabled)
        val changed = usesSquareWindowCorners != enabled
        usesSquareWindowCorners = enabled
        if (enabled) {
            contentView?.layer?.cornerRadius = 0.0
        }
        val isTitledWindow = (styleMask and NSWindowStyleMaskTitled) != 0UL
        if (isTitledWindow && (changed || forceRefresh)) {
            invokeVoidSelectorIfSupported("_cornerMaskChanged")
            invokeVoidSelectorIfSupported("_updateWindowCornerMask")
            invalidateShadow()
            if (visible) {
                contentView?.superview?.setNeedsDisplay(true)
                display()
            }
        }
    }

    private fun invokeVoidSelectorIfSupported(selectorName: String) {
        val sel = sel_registerName(selectorName) ?: return
        if (!respondsToSelector(sel)) return
        val cls = object_getClass(this) ?: return
        val imp = class_getMethodImplementation(cls, sel) ?: return
        val fn = imp.reinterpret<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>()
        val winPtr = interpretCPointer<CPointed>(objcPtr()) ?: return
        fn(winPtr, sel.reinterpret())
    }
}

private object MacosHostWindowCornerHooks {
    private var squareCornerHooksInstalled: Boolean = false
    private val squareWindowPtrs = HashSet<Long>()
    private var origCornerRadiusImp: IMP? = null
    private var origTopCornerRadiusImp: IMP? = null
    private var origBottomCornerRadiusImp: IMP? = null
    private var origShouldRoundCornersImp: IMP? = null
    private var origCornerMaskImp: IMP? = null

    fun setSquareCorners(host: MacosHostWindow, enabled: Boolean) {
        val ptr = host.objcPtr().toLong()
        if (ptr == 0L) return
        if (enabled) {
            squareWindowPtrs.add(ptr)
        } else {
            squareWindowPtrs.remove(ptr)
        }
    }

    private fun isSquareWindow(self: COpaquePointer?): Boolean =
        self != null && squareWindowPtrs.contains(self.rawValue.toLong())

    private fun installHostMethodOverride(
        hostCls: ObjCClass,
        superCls: ObjCClass,
        selectorName: String,
        replacementImp: IMP
    ): IMP? {
        val sel = sel_registerName(selectorName) ?: return null
        val method = class_getInstanceMethod(superCls, sel) ?: return null
        val origImp = method_getImplementation(method) ?: return null
        val encoding = method_getTypeEncoding(method)?.toKString() ?: return null
        class_addMethod(hostCls, sel, replacementImp, encoding)
        return origImp
    }

    private val squareCornerRadiusImp: IMP = staticCFunction { self: COpaquePointer?, cmd: COpaquePointer? ->
        if (isSquareWindow(self)) {
            0.0
        } else {
            origCornerRadiusImp?.reinterpret<CFunction<(COpaquePointer?, COpaquePointer?) -> Double>>()
                ?.invoke(self, cmd)
                ?: 16.0
        }
    }.reinterpret()

    private val squareTopCornerRadiusImp: IMP = staticCFunction { self: COpaquePointer?, cmd: COpaquePointer? ->
        if (isSquareWindow(self)) {
            0.0
        } else {
            origTopCornerRadiusImp?.reinterpret<CFunction<(COpaquePointer?, COpaquePointer?) -> Double>>()
                ?.invoke(self, cmd)
                ?: 16.0
        }
    }.reinterpret()

    private val squareBottomCornerRadiusImp: IMP = staticCFunction { self: COpaquePointer?, cmd: COpaquePointer? ->
        if (isSquareWindow(self)) {
            0.0
        } else {
            origBottomCornerRadiusImp?.reinterpret<CFunction<(COpaquePointer?, COpaquePointer?) -> Double>>()
                ?.invoke(self, cmd)
                ?: 16.0
        }
    }.reinterpret()

    private val squareShouldRoundCornersImp: IMP = staticCFunction { self: COpaquePointer?, cmd: COpaquePointer? ->
        if (isSquareWindow(self)) {
            false
        } else {
            origShouldRoundCornersImp?.reinterpret<CFunction<(COpaquePointer?, COpaquePointer?) -> Boolean>>()
                ?.invoke(self, cmd)
                ?: true
        }
    }.reinterpret()

    private val squareCornerMaskImp: IMP = staticCFunction { self: COpaquePointer?, cmd: COpaquePointer? ->
        if (isSquareWindow(self)) {
            null
        } else {
            origCornerMaskImp?.reinterpret<CFunction<(COpaquePointer?, COpaquePointer?) -> COpaquePointer?>>()
                ?.invoke(self, cmd)
        }
    }.reinterpret()

    fun installOverridesIfNeeded(hostInstance: MacosHostWindow) {
        if (squareCornerHooksInstalled) return
        val hostCls = object_getClass(hostInstance) ?: return
        val superCls = class_getSuperclass(hostCls) ?: return
        if (hostCls == superCls) return
        squareCornerHooksInstalled = true
        origCornerRadiusImp = installHostMethodOverride(hostCls, superCls, "_cornerRadius", squareCornerRadiusImp)
        origTopCornerRadiusImp = installHostMethodOverride(hostCls, superCls, "_topCornerRadius", squareTopCornerRadiusImp)
        origBottomCornerRadiusImp = installHostMethodOverride(hostCls, superCls, "_bottomCornerRadius", squareBottomCornerRadiusImp)
        origShouldRoundCornersImp = installHostMethodOverride(hostCls, superCls, "_shouldRoundCornersForSurface", squareShouldRoundCornersImp)
        origCornerMaskImp = installHostMethodOverride(hostCls, superCls, "_cornerMask", squareCornerMaskImp)
    }
}
