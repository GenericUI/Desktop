@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.animation.AnimationSpec
import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.nativeimpl.system.DefaultAnimationNativeImpl
import com.nexustools.genericui.style.transition.CssTimingFunction
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.interpretObjCPointerOrNull
import kotlinx.cinterop.objcPtr
import platform.AppKit.NSAnimation
import platform.AppKit.NSAnimationBlockingMode
import platform.AppKit.NSAnimationCurve
import platform.AppKit.NSAnimationDelegateProtocol
import platform.AppKit.NSAnimationProgress
import platform.AppKit.NSScreen
import platform.AppKit.NSWindow
import platform.AppKit.displayLinkWithTarget
import platform.AppKit.maximumFramesPerSecond
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSRunLoop
import platform.Foundation.NSRunLoopCommonModes
import platform.Foundation.NSSelectorFromString
import platform.Foundation.NSThread
import platform.Foundation.NSTimer
import platform.QuartzCore.CACurrentMediaTime
import platform.QuartzCore.CADisplayLink
import platform.QuartzCore.CAFrameRateRangeMake
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.concurrent.AtomicInt

/**
 * macOS AppKit `NSAnimation` + `NSWindow.displayLinkWithTarget:selector:` (`CADisplayLink`)
 * scheduler implementing [com.nexustools.genericui.nativeimpl.system.AnimationNativeImpl].
 */
class MacosAnimationNativeImpl(
    private val activeWindowProvider: (() -> NSWindow?)? = { MacosNativeWindowHost.activeHostWindowForDisplayLink() },
    private val onAfterNativeVBlankTick: ((Double) -> Unit)? = { tsMs -> MacosNativeWindowHost.onAnimatorVBlankTick(tsMs) }
) : DefaultAnimationNativeImpl() {

    private class ActiveNsAnimationHolder(
        val id: Int,
        val settledFlag: AtomicInt = AtomicInt(0)
    ) {
        var animation: MacosTimedNsAnimation? = null
        var delegate: MacosNsAnimationDelegate? = null
        var delayTimer: NSTimer? = null
        var registryRemove: (() -> Unit)? = null

        fun settleFromDelegate(cancelled: Boolean, onFinished: (Boolean) -> Unit) {
            if (!settledFlag.compareAndSet(0, 1)) return
            registryRemove?.invoke()
            animation?.delegate = null
            animation = null
            delegate = null
            delayTimer = null
            onFinished(cancelled)
        }
    }

    private class MacosTimedNsAnimation(
        durationSeconds: Double,
        curve: NSAnimationCurve,
        private val timingFunction: CssTimingFunction,
        private val isCustomBezier: Boolean,
        private val holder: ActiveNsAnimationHolder,
        private val onProgress: (Float) -> Unit
    ) : NSAnimation(duration = durationSeconds, animationCurve = curve) {

        override fun setCurrentProgress(progress: NSAnimationProgress) {
            super.setCurrentProgress(progress)
            if (holder.settledFlag.value != 0) return
            val clamped = progress.coerceIn(0f, 1f)
            val eased = if (isCustomBezier) {
                timingFunction.evaluate(clamped)
            } else {
                currentValue.coerceIn(0f, 1f)
            }
            onProgress(eased)
        }
    }

    private class MacosNsAnimationDelegate(
        private val timingFunction: CssTimingFunction,
        private val isCustomBezier: Boolean,
        private val holder: ActiveNsAnimationHolder,
        private val onFinished: (Boolean) -> Unit
    ) : NSObject(), NSAnimationDelegateProtocol {

        override fun animation(animation: NSAnimation, valueForProgress: NSAnimationProgress): Float {
            val clamped = valueForProgress.coerceIn(0f, 1f)
            return if (isCustomBezier) {
                timingFunction.evaluate(clamped)
            } else {
                clamped
            }
        }

        override fun animationDidEnd(animation: NSAnimation) {
            holder.settleFromDelegate(cancelled = false, onFinished = onFinished)
        }

        override fun animationDidStop(animation: NSAnimation) {
            holder.settleFromDelegate(cancelled = true, onFinished = onFinished)
        }
    }

    private class DisplayLinkTarget(
        private val onTick: (Double) -> Unit
    ) : NSObject() {
        @ObjCAction
        fun onDisplayLinkTick(sender: NSObject?) {
            val link = sender?.let { interpretObjCPointerOrNull<CADisplayLink>(it.objcPtr()) }
            val tsSec = link?.timestamp ?: CACurrentMediaTime()
            onTick(tsSec * 1000.0)
        }
    }

    private val nativeLock = NSRecursiveLock()
    private val activeNsAnimations = HashMap<Int, ActiveNsAnimationHolder>(16)

    private var activeDisplayLink: CADisplayLink? = null
    private var displayLinkTarget: DisplayLinkTarget? = null
    private var fallbackVBlankTimer: NSTimer? = null
    private var animatorDefaultWorkFlag: Boolean = false
    private var hostSurfaceDemandActive: Boolean = false
    private var hostSurfacePreferredRateHz: Double? = null
    private var lastAppliedPreferredRateHz: Float = -1f
    private var lastAppliedFallbackRateHz: Double = -1.0
    private var vblankGeneration: Long = 0L

    @kotlin.concurrent.Volatile
    internal var lastNativeVBlankUptimeMs: Double = 0.0

    val isDisplayLinkActive: Boolean
        get() = withNativeLock { activeDisplayLink != null || fallbackVBlankTimer != null }

    val activeNsAnimationCount: Int
        get() = withNativeLock { activeNsAnimations.size }

    private inline fun <T> withNativeLock(block: () -> T): T {
        nativeLock.lock()
        return try {
            block()
        } finally {
            nativeLock.unlock()
        }
    }

    override fun getRefreshRateHz(): Double {
        val screen = activeWindowProvider?.invoke()?.screen ?: NSScreen.mainScreen
        val sel = NSSelectorFromString("maximumFramesPerSecond")
        val fps = if (screen != null && (screen as NSObject).respondsToSelector(sel)) {
            screen.maximumFramesPerSecond.toDouble()
        } else {
            60.0
        }
        return if (fps.isFinite() && fps > 0.0) fps else 60.0
    }

    override fun startNativeTimedAnimation(
        id: Int,
        spec: AnimationSpec,
        target: NativeImpl<*>?,
        onProgress: (easedProgress: Float) -> Unit,
        onFinished: (cancelled: Boolean) -> Unit
    ): (() -> Unit)? {
        if (spec.durationMs <= 0) return null

        val curveTuple = when (spec.timingFunction) {
            CssTimingFunction.LINEAR -> NSAnimationCurve.NSAnimationLinear to false
            CssTimingFunction.EASE_IN -> NSAnimationCurve.NSAnimationEaseIn to false
            CssTimingFunction.EASE_OUT -> NSAnimationCurve.NSAnimationEaseOut to false
            CssTimingFunction.EASE_IN_OUT -> NSAnimationCurve.NSAnimationEaseInOut to false
            else -> NSAnimationCurve.NSAnimationLinear to true
        }
        val curve = curveTuple.first
        val isCustomBezier = curveTuple.second

        val holder = ActiveNsAnimationHolder(id = id)
        holder.registryRemove = {
            withNativeLock { activeNsAnimations.remove(id) }
        }
        withNativeLock {
            activeNsAnimations[id] = holder
        }

        val startOnMain = {
            if (!isDisposed && holder.settledFlag.value == 0) {
                val durationSec = (spec.durationMs.toDouble() / 1000.0).coerceAtLeast(0.001)
                val delegate = MacosNsAnimationDelegate(
                    timingFunction = spec.timingFunction,
                    isCustomBezier = isCustomBezier,
                    holder = holder,
                    onFinished = onFinished
                )
                val anim = MacosTimedNsAnimation(
                    durationSeconds = durationSec,
                    curve = curve,
                    timingFunction = spec.timingFunction,
                    isCustomBezier = isCustomBezier,
                    holder = holder,
                    onProgress = onProgress
                ).apply {
                    animationBlockingMode = NSAnimationBlockingMode.NSAnimationNonblocking
                    frameRate = 0.0f
                    this.delegate = delegate
                }
                holder.delegate = delegate
                holder.animation = anim

                if (spec.delayMs > 0) {
                    val delayTimer = NSTimer.scheduledTimerWithTimeInterval(
                        interval = spec.delayMs.toDouble() / 1000.0,
                        repeats = false
                    ) { _ ->
                        holder.delayTimer = null
                        if (!isDisposed && holder.settledFlag.value == 0) {
                            onProgress(0f)
                            anim.startAnimation()
                        }
                    }
                    holder.delayTimer = delayTimer
                    NSRunLoop.mainRunLoop.addTimer(delayTimer, forMode = NSRunLoopCommonModes)
                } else {
                    onProgress(0f)
                    anim.startAnimation()
                }
            }
        }

        if (NSThread.isMainThread) {
            startOnMain()
        } else {
            dispatch_async(dispatch_get_main_queue(), startOnMain)
        }

        return {
            if (holder.settledFlag.compareAndSet(0, 1)) {
                holder.registryRemove?.invoke()
                val stopOnMain = {
                    holder.delayTimer?.invalidate()
                    holder.delayTimer = null
                    val anim = holder.animation
                    anim?.delegate = null
                    if (anim != null && anim.animating) {
                        anim.stopAnimation()
                    }
                    holder.animation = null
                    holder.delegate = null
                }
                if (NSThread.isMainThread) {
                    stopOnMain()
                } else {
                    dispatch_async(dispatch_get_main_queue(), stopOnMain)
                }
            }
        }
    }

    override fun onActiveVBlankWorkChanged(hasActiveWork: Boolean) {
        val gen = withNativeLock {
            animatorDefaultWorkFlag = hasActiveWork
            ++vblankGeneration
        }
        scheduleVBlankSync(gen)
    }

    internal fun setHostSurfaceDemand(active: Boolean, preferredRateHz: Double?) {
        val gen: Long? = withNativeLock {
            val rateChanged = hostSurfacePreferredRateHz != preferredRateHz
            if (hostSurfaceDemandActive == active && !rateChanged) {
                null
            } else {
                hostSurfaceDemandActive = active
                hostSurfacePreferredRateHz = preferredRateHz
                ++vblankGeneration
            }
        }
        if (gen != null) {
            scheduleVBlankSync(gen)
        }
    }

    private fun scheduleVBlankSync(gen: Long) {
        val apply = {
            val (shouldRun, isCurrentGen) = withNativeLock {
                (!isDisposed && (animatorDefaultWorkFlag || hostSurfaceDemandActive)) to (vblankGeneration == gen)
            }
            if (isCurrentGen) {
                if (shouldRun) {
                    if (activeDisplayLink != null || fallbackVBlankTimer != null) {
                        updateDisplayLinkOrTimerRateOnMain()
                    } else {
                        startDisplayLinkOnMain()
                    }
                } else {
                    stopDisplayLinkOnMain()
                }
            }
        }
        if (NSThread.isMainThread) {
            apply()
        } else {
            dispatch_async(dispatch_get_main_queue(), apply)
        }
    }

    private fun resolveEffectiveTargetRateHz(displayMaxHz: Double): Pair<Boolean, Double> {
        val (animatorWork, pref) = withNativeLock { animatorDefaultWorkFlag to hostSurfacePreferredRateHz }
        if (animatorWork || pref == null) {
            return false to displayMaxHz
        }
        val bounded = if (pref > 0.0) minOf(pref, displayMaxHz).coerceAtLeast(1.0) else 1.0
        return true to bounded
    }

    private fun applyDisplayLinkFrameRate(link: CADisplayLink) {
        val displayMaxFps = getRefreshRateHz().toFloat().coerceAtLeast(1f)
        val (isBounded, targetHz) = resolveEffectiveTargetRateHz(displayMaxFps.toDouble())
        if (isBounded) {
            val targetFps = targetHz.toFloat().coerceIn(1f, displayMaxFps)
            link.preferredFrameRateRange = CAFrameRateRangeMake(minOf(targetFps, displayMaxFps), displayMaxFps, targetFps)
            lastAppliedPreferredRateHz = targetFps
        } else {
            val minFps = minOf(24f, displayMaxFps)
            link.preferredFrameRateRange = CAFrameRateRangeMake(minFps, displayMaxFps, displayMaxFps)
            lastAppliedPreferredRateHz = displayMaxFps
        }
    }

    private fun createFallbackVBlankTimer(rateHz: Double): NSTimer {
        val effectiveHz = rateHz.coerceAtLeast(1.0)
        val intervalSec = 1.0 / effectiveHz
        val timer = NSTimer.scheduledTimerWithTimeInterval(intervalSec, repeats = true) { _ ->
            if (!isDisposed) {
                val tsMs = CACurrentMediaTime() * 1000.0
                lastNativeVBlankUptimeMs = NSProcessInfo.processInfo.systemUptime * 1000.0
                onNativeVBlankTick(tsMs)
                onAfterNativeVBlankTick?.invoke(tsMs)
            }
        }
        NSRunLoop.mainRunLoop.addTimer(timer, forMode = NSRunLoopCommonModes)
        lastAppliedFallbackRateHz = effectiveHz
        return timer
    }

    private fun updateDisplayLinkOrTimerRateOnMain() {
        val link = activeDisplayLink
        if (link != null) {
            val displayMaxFps = getRefreshRateHz().toFloat().coerceAtLeast(1f)
            val (_, targetHz) = resolveEffectiveTargetRateHz(displayMaxFps.toDouble())
            val desiredFps = targetHz.toFloat().coerceIn(1f, displayMaxFps)
            if (kotlin.math.abs(desiredFps - lastAppliedPreferredRateHz) >= 0.25f) {
                applyDisplayLinkFrameRate(link)
            }
            return
        }
        val timer = fallbackVBlankTimer
        if (timer != null) {
            val displayMaxHz = getRefreshRateHz().coerceAtLeast(1.0)
            val (_, targetHz) = resolveEffectiveTargetRateHz(displayMaxHz)
            val desiredHz = targetHz.coerceAtLeast(1.0)
            if (kotlin.math.abs(desiredHz - lastAppliedFallbackRateHz) >= 0.25) {
                timer.invalidate()
                fallbackVBlankTimer = createFallbackVBlankTimer(desiredHz)
            }
        }
    }

    private fun startDisplayLinkOnMain() {
        if (activeDisplayLink != null || fallbackVBlankTimer != null) return
        val target = DisplayLinkTarget { timestampMs ->
            if (!isDisposed) {
                lastNativeVBlankUptimeMs = NSProcessInfo.processInfo.systemUptime * 1000.0
                onNativeVBlankTick(timestampMs)
                onAfterNativeVBlankTick?.invoke(timestampMs)
            }
        }
        val selTick = NSSelectorFromString("onDisplayLinkTick:")
        val selCreate = NSSelectorFromString("displayLinkWithTarget:selector:")
        val activeWin = activeWindowProvider?.invoke()
        val rawLink: Any? = when {
            activeWin != null && (activeWin as NSObject).respondsToSelector(selCreate) ->
                activeWin.displayLinkWithTarget(target, selTick)
            else -> {
                val mainScreen = NSScreen.mainScreen
                if (mainScreen != null && (mainScreen as NSObject).respondsToSelector(selCreate)) {
                    mainScreen.displayLinkWithTarget(target, selTick)
                } else {
                    null
                }
            }
        }
        val link = rawLink?.let { interpretObjCPointerOrNull<CADisplayLink>(it.objcPtr()) }
        if (link != null) {
            applyDisplayLinkFrameRate(link)
            link.paused = false
            link.addToRunLoop(NSRunLoop.mainRunLoop, forMode = NSRunLoopCommonModes)
            displayLinkTarget = target
            activeDisplayLink = link
            return
        }

        val displayMaxHz = getRefreshRateHz().coerceAtLeast(1.0)
        val (_, targetHz) = resolveEffectiveTargetRateHz(displayMaxHz)
        fallbackVBlankTimer = createFallbackVBlankTimer(targetHz)
    }

    private fun stopDisplayLinkOnMain() {
        activeDisplayLink?.let { link ->
            link.paused = true
            link.invalidate()
        }
        activeDisplayLink = null
        displayLinkTarget = null
        fallbackVBlankTimer?.invalidate()
        fallbackVBlankTimer = null
        lastAppliedPreferredRateHz = -1f
        lastAppliedFallbackRateHz = -1.0
    }

    override fun dispose() {
        super.dispose()
        val stopAll = {
            stopDisplayLinkOnMain()
            val holders = withNativeLock {
                animatorDefaultWorkFlag = false
                hostSurfaceDemandActive = false
                hostSurfacePreferredRateHz = null
                ++vblankGeneration
                val copy = activeNsAnimations.values.toList()
                activeNsAnimations.clear()
                copy
            }
            for (holder in holders) {
                if (holder.settledFlag.compareAndSet(0, 1)) {
                    holder.delayTimer?.invalidate()
                    holder.delayTimer = null
                    holder.animation?.delegate = null
                    if (holder.animation?.animating == true) {
                        holder.animation?.stopAnimation()
                    }
                    holder.animation = null
                    holder.delegate = null
                }
            }
        }
        if (NSThread.isMainThread) {
            stopAll()
        } else {
            dispatch_async(dispatch_get_main_queue(), stopAll)
        }
    }
}
