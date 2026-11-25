@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk

import com.nexustools.genericui.animation.AnimationSpec
import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.linux.LinuxGlibGioBindings
import com.nexustools.genericui.linux.LinuxReentrantLock
import com.nexustools.genericui.nativeimpl.system.DefaultAnimationNativeImpl
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import kotlin.math.roundToInt
import kotlin.time.TimeSource

/**
 * GTK4 / Libadwaita implementation of [DefaultAnimationNativeImpl]:
 * - Uses Libadwaita `AdwTimedAnimation` + `AdwCallbackAnimationTarget` when Libadwaita is available
 *   and called on the GTK UI thread with a realized GTK widget, synced directly to GTK4's `GdkFrameClock`.
 * - Drives `requestAnimationFrame`, `Animator.watchVBlank`, and general `Animator` animations via
 *   `gtk_widget_add_tick_callback` (`GdkFrameClock` VBlank ticks calling [onNativeVBlankTick]) with
 *   a 0%-CPU idle state when no animations or VBlank watchers are active.
 * - Queries monitor refresh rate dynamically via `gdk_display_get_monitor_at_surface` +
 *   `gdk_monitor_get_refresh_rate` (`mHz / 1000.0`).
 */
class GtkAnimationNativeImpl : DefaultAnimationNativeImpl() {

    private class AdwAnimState(
        val owner: GtkAnimationNativeImpl,
        val spec: AnimationSpec,
        val onProgress: (Float) -> Unit,
        val onFinished: (Boolean) -> Unit
    ) {
        val lock = LinuxReentrantLock()
        var animationPtr: COpaquePointer? = null
        var doneHandlerId: ULong = 0uL
        var settled: Boolean = false
        var cleanedUp: Boolean = false
    }

    private class TickBinding(val owner: GtkAnimationNativeImpl)

    private val animLock = LinuxReentrantLock()
    private val clockOrigin = TimeSource.Monotonic.markNow()
    private val activeAdwAnimations = LinkedHashSet<AdwAnimState>()

    private var animatorWorkFlag: Boolean = false
    private var hostSurfaceDemandActive: Boolean = false
    private var hostSurfacePreferredRateHz: Double? = null
    private var tickCallbackWidget: COpaquePointer? = null
    private var tickCallbackId: UInt = 0u
    private var fallbackTimerId: UInt = 0u
    private var activeFallbackIntervalMs: UInt = 0u
    private var tickScheduledOnUi: Boolean = false
    private var cachedRefreshRateHz: Double = 60.0

    init {
        activeInstance = this
    }

    private fun monotonicNowMs(): Double = clockOrigin.elapsedNow().inWholeNanoseconds / 1_000_000.0

    override fun getRefreshRateHz(): Double {
        val widget = GtkNativeWindowHost.activeTickWidgetPtr() ?: GtkNativeWindowHost.activeGtkWindowPtr()
        val queried = Gtk4DynamicBindings.queryMonitorRefreshRateHz(widget)
        if (queried != null && queried > 0.0) {
            animLock.withLock { cachedRefreshRateHz = queried }
            return queried
        }
        return animLock.withLock { cachedRefreshRateHz }
    }

    override fun startNativeTimedAnimation(
        id: Int,
        spec: AnimationSpec,
        target: NativeImpl<*>?,
        onProgress: (easedProgress: Float) -> Unit,
        onFinished: (cancelled: Boolean) -> Unit
    ): (() -> Unit)? {
        if (isDisposed || !GtkApplicationNativeImpl.interactiveGtkGuiEnabled) return null
        if (spec.durationMs <= 0 || spec.delayMs > 0) return null
        if (!GenericUiThreading.isOnUiThread()) return null
        if (!Gtk4DynamicBindings.ensureGtkInitialized() || !Gtk4DynamicBindings.hasAdwTimedAnimation()) return null

        val hostWidget = GtkNativeWindowHost.activeTickWidgetPtr() ?: return null
        if (!Gtk4DynamicBindings.gtkWidgetGetRealized(hostWidget)) return null

        val state = AdwAnimState(
            owner = this,
            spec = spec,
            onProgress = onProgress,
            onFinished = onFinished
        )
        val stateRef = StableRef.create(state)
        val statePtr = stateRef.asCPointer()

        val adwTarget = Gtk4DynamicBindings.adwCallbackAnimationTargetNew(
            callback = adwTargetCallback,
            userData = statePtr,
            destroyNotify = adwTargetDestroyNotify
        )
        if (adwTarget == null) {
            stateRef.dispose()
            return null
        }

        val anim = Gtk4DynamicBindings.adwTimedAnimationNew(
            widget = hostWidget,
            from = 0.0,
            to = 1.0,
            durationMs = spec.durationMs.coerceAtLeast(1).toUInt(),
            target = adwTarget
        )
        if (anim == null) {
            // `adw_timed_animation_new` takes transfer-full on adwTarget, but if creation itself failed
            // before taking ownership we unref adwTarget (whose finalize invokes adwTargetDestroyNotify).
            LinuxGlibGioBindings.objectUnref(adwTarget)
            return null
        }

        Gtk4DynamicBindings.adwTimedAnimationSetEasing(anim, Gtk4DynamicBindings.ADW_EASING_LINEAR)

        val doneHandlerId = LinuxGlibGioBindings.signalConnect(
            instance = anim,
            signalName = "done",
            handler = adwDoneCallback,
            userData = statePtr,
            destroyNotify = null
        )

        state.lock.withLock {
            state.animationPtr = anim
            state.doneHandlerId = doneHandlerId
        }
        animLock.withLock {
            if (isDisposed) {
                state.settled = true
            } else {
                activeAdwAnimations.add(state)
            }
        }
        if (state.lock.withLock { state.settled }) {
            cleanupAdwAnimationOnUi(state, skipToEnd = false)
            return null
        }

        Gtk4DynamicBindings.adwAnimationPlay(anim)

        return {
            cancelAdwAnimation(state)
        }
    }

    private fun cancelAdwAnimation(state: AdwAnimState) {
        val alreadySettled = state.lock.withLock {
            if (state.settled) true else {
                state.settled = true
                false
            }
        }
        if (alreadySettled) return
        if (GenericUiThreading.isOnUiThread()) {
            cleanupAdwAnimationOnUi(state, skipToEnd = false)
        } else {
            GenericUiThreading.runOnUi {
                cleanupAdwAnimationOnUi(state, skipToEnd = false)
            }
        }
    }

    private fun handleAdwTargetTickOnUi(state: AdwAnimState, rawValue: Double) {
        val active = state.lock.withLock { !state.settled && !state.cleanedUp } && !isDisposed
        if (!active) return
        val clampedRaw = rawValue.toFloat().coerceIn(0f, 1f)
        val eased = state.spec.timingFunction.evaluate(clampedRaw)
        state.onProgress(eased)
        GtkNativeWindowHost.requestRedrawOnUiThread()
    }

    private fun handleAdwDoneOnUi(state: AdwAnimState) {
        val shouldFinish = state.lock.withLock {
            if (state.settled) false else {
                state.settled = true
                true
            }
        }
        cleanupAdwAnimationOnUi(state, skipToEnd = false)
        if (shouldFinish && !isDisposed) {
            state.onFinished(false)
            GtkNativeWindowHost.requestRedrawOnUiThread()
        }
    }

    private fun cleanupAdwAnimationOnUi(state: AdwAnimState, skipToEnd: Boolean) {
        val (anim, handlerId) = state.lock.withLock {
            if (state.cleanedUp) return
            state.cleanedUp = true
            val ptr = state.animationPtr
            val hid = state.doneHandlerId
            state.animationPtr = null
            state.doneHandlerId = 0uL
            ptr to hid
        }
        animLock.withLock { activeAdwAnimations.remove(state) }
        if (anim != null) {
            if (handlerId != 0uL) {
                LinuxGlibGioBindings.signalHandlerDisconnect(anim, handlerId)
            }
            if (skipToEnd) {
                Gtk4DynamicBindings.adwAnimationSkip(anim)
            } else {
                Gtk4DynamicBindings.adwAnimationPause(anim)
            }
            LinuxGlibGioBindings.objectUnref(anim)
        }
    }

    private fun isEffectivelyActiveLocked(): Boolean =
        !isDisposed && (animatorWorkFlag || hostSurfaceDemandActive)

    override fun onActiveVBlankWorkChanged(hasActiveWork: Boolean) {
        val effective = animLock.withLock {
            animatorWorkFlag = hasActiveWork
            isEffectivelyActiveLocked()
        }
        scheduleEnsureTickSource(wakeIfNeeded = effective)
    }

    internal fun setHostSurfaceDemand(active: Boolean, preferredRateHz: Double?) {
        animLock.withLock {
            hostSurfaceDemandActive = active
            hostSurfacePreferredRateHz = preferredRateHz
        }
        scheduleEnsureTickSource(wakeIfNeeded = active || preferredRateHz != null)
    }

    override fun onRequestNativeFrame() {
        scheduleEnsureTickSource(wakeIfNeeded = true)
    }

    internal fun onHostSessionMappedChanged() {
        val active = animLock.withLock { isEffectivelyActiveLocked() }
        if (active) {
            scheduleEnsureTickSource(wakeIfNeeded = true)
        }
    }

    internal fun onHostWidgetDestroyed(destroyedWidget: COpaquePointer?) {
        if (destroyedWidget == null) return
        val shouldReattach = animLock.withLock {
            val matched = tickCallbackWidget === destroyedWidget
            if (matched) {
                tickCallbackWidget = null
                tickCallbackId = 0u
            }
            matched && isEffectivelyActiveLocked()
        }
        if (shouldReattach) {
            scheduleEnsureTickSource(wakeIfNeeded = true)
        }
    }

    internal fun pumpIfFallbackNeeded(elapsedFallbackMs: Double) {
        val needsManualStep = animLock.withLock {
            isEffectivelyActiveLocked() && tickCallbackId == 0u && fallbackTimerId == 0u
        }
        if (needsManualStep) {
            val ts = monotonicNowMs() + elapsedFallbackMs.coerceAtLeast(0.0)
            onNativeVBlankTick(ts)
            GtkNativeWindowHost.onAnimatorVBlankTick(ts)
        }
    }

    private fun scheduleEnsureTickSource(wakeIfNeeded: Boolean) {
        if (GenericUiThreading.isOnUiThread()) {
            ensureTickSourceOnUi()
            if (wakeIfNeeded) {
                LinuxGlibGioBindings.mainContextWakeup(LinuxGlibGioBindings.mainContextDefault())
            }
            return
        }
        val shouldPost = animLock.withLock {
            if (tickScheduledOnUi) false else {
                tickScheduledOnUi = true
                true
            }
        }
        if (shouldPost) {
            GenericUiThreading.runOnUi {
                animLock.withLock { tickScheduledOnUi = false }
                ensureTickSourceOnUi()
            }
        }
        if (wakeIfNeeded) {
            LinuxGlibGioBindings.mainContextWakeup(LinuxGlibGioBindings.mainContextDefault())
        }
    }

    private fun ensureTickSourceOnUi() {
        val active = animLock.withLock { isEffectivelyActiveLocked() }
        if (!active) {
            removeSourcesOnUi()
            return
        }
        if (!Gtk4DynamicBindings.ensureGtkInitialized()) {
            ensureFallbackTimerOnUi()
            return
        }
        val widget = GtkNativeWindowHost.activeTickWidgetPtr()
        if (widget != null && Gtk4DynamicBindings.gtkWidgetGetRealized(widget)) {
            removeFallbackTimerLocked()
            val alreadyAttached = animLock.withLock { tickCallbackId != 0u && tickCallbackWidget === widget }
            if (!alreadyAttached) {
                removeTickCallbackOnUi()
                val bindingRef = StableRef.create(TickBinding(this))
                val newId = Gtk4DynamicBindings.gtkWidgetAddTickCallback(
                    widget = widget,
                    callback = frameTickCallback,
                    userData = bindingRef.asCPointer(),
                    destroyNotify = tickDestroyNotify
                )
                if (newId != 0u) {
                    animLock.withLock {
                        tickCallbackWidget = widget
                        tickCallbackId = newId
                    }
                    Gtk4DynamicBindings.gtkWidgetQueueDraw(widget)
                    return
                } else {
                    bindingRef.dispose()
                }
            } else {
                Gtk4DynamicBindings.gtkWidgetQueueDraw(widget)
                return
            }
        }
        ensureFallbackTimerOnUi()
    }

    private fun ensureFallbackTimerOnUi() {
        val activeState = animLock.withLock {
            if (!isEffectivelyActiveLocked()) null else (animatorWorkFlag to hostSurfacePreferredRateHz)
        } ?: return

        val displayHz = getRefreshRateHz().coerceAtLeast(1.0)
        val targetHz = if (!activeState.first) {
            activeState.second?.takeIf { it > 0.0 }?.let { minOf(it, displayHz) } ?: displayHz
        } else {
            displayHz
        }
        val intervalMs = (1000.0 / targetHz.coerceAtLeast(1.0)).roundToInt().coerceAtLeast(1).toUInt()

        val oldTimerToRemove = animLock.withLock {
            if (!isEffectivelyActiveLocked()) return
            if (fallbackTimerId != 0u && activeFallbackIntervalMs == intervalMs) {
                return
            }
            val oldId = fallbackTimerId
            fallbackTimerId = 0u
            activeFallbackIntervalMs = 0u
            oldId
        }
        if (oldTimerToRemove != 0u) {
            LinuxGlibGioBindings.sourceRemove(oldTimerToRemove)
        }

        val bindingRef = StableRef.create(TickBinding(this))
        val newId = LinuxGlibGioBindings.timeoutAddFull(
            intervalMs = intervalMs,
            callback = fallbackTimeoutCallback,
            userData = bindingRef.asCPointer(),
            destroyNotify = tickDestroyNotify
        )
        if (newId != 0u) {
            val shouldRemoveNew = animLock.withLock {
                if (isEffectivelyActiveLocked() && fallbackTimerId == 0u) {
                    fallbackTimerId = newId
                    activeFallbackIntervalMs = intervalMs
                    false
                } else {
                    true
                }
            }
            if (shouldRemoveNew) {
                LinuxGlibGioBindings.sourceRemove(newId)
            }
        } else {
            bindingRef.dispose()
        }
    }

    private fun removeFallbackTimerLocked() {
        val timerId = animLock.withLock {
            val id = fallbackTimerId
            fallbackTimerId = 0u
            activeFallbackIntervalMs = 0u
            id
        }
        if (timerId != 0u) {
            LinuxGlibGioBindings.sourceRemove(timerId)
        }
    }

    private fun removeTickCallbackOnUi() {
        val (widget, id) = animLock.withLock {
            val w = tickCallbackWidget
            val tid = tickCallbackId
            tickCallbackWidget = null
            tickCallbackId = 0u
            w to tid
        }
        if (widget != null && id != 0u && GtkNativeWindowHost.isLiveTickWidget(widget)) {
            Gtk4DynamicBindings.gtkWidgetRemoveTickCallback(widget, id)
        }
    }

    private fun removeSourcesOnUi() {
        removeFallbackTimerLocked()
        removeTickCallbackOnUi()
    }

    private fun handleFrameTickOnUi(widget: COpaquePointer, frameClock: COpaquePointer?): UInt {
        if (!animLock.withLock { isEffectivelyActiveLocked() }) {
            animLock.withLock {
                if (tickCallbackWidget === widget) {
                    tickCallbackWidget = null
                    tickCallbackId = 0u
                }
            }
            return 0u
        }
        val frameUs = if (frameClock != null) Gtk4DynamicBindings.gdkFrameClockGetFrameTime(frameClock) else 0L
        val timestampMs = if (frameUs > 0L) frameUs / 1000.0 else monotonicNowMs()
        val dispatched = onNativeVBlankTick(timestampMs)
        GtkNativeWindowHost.onAnimatorVBlankTick(timestampMs)
        if (dispatched > 0) {
            GtkNativeWindowHost.requestRedrawOnUiThread()
        }
        val keepAlive = animLock.withLock { isEffectivelyActiveLocked() && tickCallbackWidget === widget }
        if (!keepAlive) {
            animLock.withLock {
                if (tickCallbackWidget === widget) {
                    tickCallbackWidget = null
                    tickCallbackId = 0u
                }
            }
            return 0u
        }
        return 1u
    }

    private fun handleFallbackTimeoutOnUi(): Int {
        if (!animLock.withLock { isEffectivelyActiveLocked() }) {
            animLock.withLock {
                fallbackTimerId = 0u
                activeFallbackIntervalMs = 0u
            }
            return 0
        }
        // Switch immediately to GdkFrameClock tick callback once a widget realizes.
        val widget = GtkNativeWindowHost.activeTickWidgetPtr()
        if (widget != null && Gtk4DynamicBindings.gtkWidgetGetRealized(widget)) {
            animLock.withLock {
                fallbackTimerId = 0u
                activeFallbackIntervalMs = 0u
            }
            ensureTickSourceOnUi()
            val nowMs = monotonicNowMs()
            onNativeVBlankTick(nowMs)
            GtkNativeWindowHost.onAnimatorVBlankTick(nowMs)
            GtkNativeWindowHost.requestRedrawOnUiThread()
            return 0
        }
        val nowMs = monotonicNowMs()
        val dispatched = onNativeVBlankTick(nowMs)
        GtkNativeWindowHost.onAnimatorVBlankTick(nowMs)
        if (dispatched > 0) {
            GtkNativeWindowHost.requestRedrawOnUiThread()
        }
        val keepAlive = animLock.withLock { isEffectivelyActiveLocked() && fallbackTimerId != 0u }
        if (!keepAlive) {
            animLock.withLock {
                fallbackTimerId = 0u
                activeFallbackIntervalMs = 0u
            }
            return 0
        }
        return 1
    }

    override fun dispose() {
        val adwStates = animLock.withLock {
            animatorWorkFlag = false
            hostSurfaceDemandActive = false
            hostSurfacePreferredRateHz = null
            activeAdwAnimations.toList()
        }
        if (GenericUiThreading.isOnUiThread()) {
            removeSourcesOnUi()
            for (st in adwStates) {
                st.lock.withLock { st.settled = true }
                cleanupAdwAnimationOnUi(st, skipToEnd = false)
            }
        } else {
            val fId = animLock.withLock {
                val id = fallbackTimerId
                fallbackTimerId = 0u
                id
            }
            if (fId != 0u) {
                LinuxGlibGioBindings.sourceRemove(fId)
            }
            GenericUiThreading.runOnUi {
                removeSourcesOnUi()
                for (st in adwStates) {
                    st.lock.withLock { st.settled = true }
                    cleanupAdwAnimationOnUi(st, skipToEnd = false)
                }
            }
        }
        super.dispose()
        if (activeInstance === this) {
            activeInstance = null
        }
    }

    companion object {
        internal var activeInstance: GtkAnimationNativeImpl? = null

        private val adwTargetCallback = staticCFunction { value: Double, userData: COpaquePointer? ->
            if (userData != null) {
                val state = userData.asStableRef<AdwAnimState>().get()
                state.owner.handleAdwTargetTickOnUi(state, value)
            }
        }

        private val adwTargetDestroyNotify = staticCFunction { userData: COpaquePointer? ->
            if (userData != null) {
                userData.asStableRef<AdwAnimState>().dispose()
            }
        }

        private val adwDoneCallback = staticCFunction { _: COpaquePointer?, userData: COpaquePointer? ->
            if (userData != null) {
                val state = userData.asStableRef<AdwAnimState>().get()
                state.owner.handleAdwDoneOnUi(state)
            }
        }

        private val frameTickCallback = staticCFunction { widget: COpaquePointer?, frameClock: COpaquePointer?, userData: COpaquePointer? ->
            if (widget == null || userData == null) return@staticCFunction 0u
            val binding = userData.asStableRef<TickBinding>().get()
            binding.owner.handleFrameTickOnUi(widget, frameClock)
        }

        private val fallbackTimeoutCallback = staticCFunction { userData: COpaquePointer? ->
            if (userData == null) return@staticCFunction 0
            val binding = userData.asStableRef<TickBinding>().get()
            binding.owner.handleFallbackTimeoutOnUi()
        }

        private val tickDestroyNotify = staticCFunction { userData: COpaquePointer? ->
            if (userData != null) {
                userData.asStableRef<TickBinding>().dispose()
            }
        }
    }
}
