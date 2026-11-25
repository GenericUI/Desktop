@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.linux.LinuxReentrantLock
import com.nexustools.genericui.nativeimpl.system.DefaultAnimationNativeImpl
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.COpaquePointer
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.time.TimeSource

/**
 * KDE (`Qt 6`) `Animator` scheduler extending [DefaultAnimationNativeImpl].
 *
 * Drives live animations & VBlank watchers using a Qt6 `QTimer` synced to `QScreen::refreshRate()` when
 * active, parking cleanly at 0% CPU whenever idle, while preserving deterministic `stepFrame`/`advanceTimeBy`
 * stepping for unit tests.
 */
class KdeAnimationNativeImpl : DefaultAnimationNativeImpl() {

    private val animLock = LinuxReentrantLock()
    private val clockOrigin = TimeSource.Monotonic.markNow()
    private var tickTimer: COpaquePointer? = null
    private var tickActive = false
    private var animatorWorkFlag: Boolean = false
    private var hostSurfaceDemandActive: Boolean = false
    private var hostSurfacePreferredRateHz: Double? = null
    private var activeTimerIntervalMs: Int = -1
    private var cachedRefreshRateHz: Double = 60.0

    private fun monotonicNowMs(): Double = clockOrigin.elapsedNow().inWholeNanoseconds / 1_000_000.0

    override fun getRefreshRateHz(): Double {
        if (KdeApplicationNativeImpl.interactiveKdeGuiEnabled) {
            val hz = KdeDynamicBindings.primaryRefreshRateHz()
            if (hz > 0.0) {
                animLock.withLock { cachedRefreshRateHz = hz }
                return hz
            }
        }
        return animLock.withLock { cachedRefreshRateHz }
    }

    override fun onActiveVBlankWorkChanged(hasActiveWork: Boolean) {
        animLock.withLock { animatorWorkFlag = hasActiveWork }
        syncTimerState()
    }

    internal fun setHostSurfaceDemand(active: Boolean, preferredRateHz: Double?) {
        animLock.withLock {
            hostSurfaceDemandActive = active
            hostSurfacePreferredRateHz = preferredRateHz
        }
        syncTimerState()
    }

    private fun syncTimerState() {
        if (!KdeApplicationNativeImpl.interactiveKdeGuiEnabled || isDisposed) return
        val updateTimer = {
            val effectiveActive = animLock.withLock { !isDisposed && (animatorWorkFlag || hostSurfaceDemandActive) }
            if (effectiveActive) {
                startVBlankTimerOnUi()
            } else {
                stopVBlankTimerOnUi()
            }
        }
        if (GenericUiThreading.isOnUiThread()) {
            updateTimer()
        } else {
            GenericUiThreading.runOnUi(updateTimer)
        }
    }

    private fun startVBlankTimerOnUi() {
        val (effectiveActive, hasAnimatorWork, preferredRate) = animLock.withLock {
            Triple(!isDisposed && (animatorWorkFlag || hostSurfaceDemandActive), animatorWorkFlag, hostSurfacePreferredRateHz)
        }
        if (!effectiveActive) {
            stopVBlankTimerOnUi()
            return
        }
        KdeDynamicBindings.ensureQApplication()
        var timer = animLock.withLock { tickTimer }
        if (timer == null || !KdeVTableBridge.isPeerAlive(timer)) {
            timer = KdeDynamicBindings.createQTimer(null)
            KdeVTableBridge.connectSignal(timer, "timeout()") {
                val active = animLock.withLock { !isDisposed && (animatorWorkFlag || hostSurfaceDemandActive) && tickActive }
                if (!active) {
                    stopVBlankTimerOnUi()
                    return@connectSignal
                }
                val nowMs = monotonicNowMs()
                onNativeVBlankTick(nowMs)
                KdeNativeWindowHost.onAnimatorVBlankTick(nowMs)
                KdeNativeWindowHost.requestRedraw()
            }
            animLock.withLock {
                tickTimer = timer
                activeTimerIntervalMs = -1
            }
        }
        val displayHz = getRefreshRateHz().coerceAtLeast(1.0)
        val targetHz = if (!hasAnimatorWork) {
            preferredRate?.takeIf { it > 0.0 }?.let { minOf(it, displayHz) } ?: displayHz
        } else {
            displayHz
        }
        val intervalMs = max(1, (1000.0 / targetHz.coerceAtLeast(1.0)).roundToInt())
        val shouldStart = animLock.withLock {
            val changed = !tickActive || activeTimerIntervalMs != intervalMs
            tickActive = true
            activeTimerIntervalMs = intervalMs
            changed
        }
        if (shouldStart) {
            KdeDynamicBindings.startQTimer(timer, intervalMs)
        }
    }

    private fun stopVBlankTimerOnUi() {
        val timer = animLock.withLock {
            tickActive = false
            activeTimerIntervalMs = -1
            tickTimer
        } ?: return
        if (KdeVTableBridge.isPeerAlive(timer)) {
            KdeDynamicBindings.stopQTimer(timer)
        }
    }

    override fun dispose() {
        val timer = animLock.withLock {
            tickActive = false
            animatorWorkFlag = false
            hostSurfaceDemandActive = false
            hostSurfacePreferredRateHz = null
            activeTimerIntervalMs = -1
            val t = tickTimer
            tickTimer = null
            t
        }
        if (timer != null && KdeVTableBridge.isPeerAlive(timer)) {
            if (GenericUiThreading.isOnUiThread()) {
                KdeDynamicBindings.stopQTimer(timer)
                KdeVTableBridge.destroyObject(timer)
            } else {
                GenericUiThreading.runOnUi {
                    if (KdeVTableBridge.isPeerAlive(timer)) {
                        KdeDynamicBindings.stopQTimer(timer)
                        KdeVTableBridge.destroyObject(timer)
                    }
                }
            }
        }
        super.dispose()
    }
}
