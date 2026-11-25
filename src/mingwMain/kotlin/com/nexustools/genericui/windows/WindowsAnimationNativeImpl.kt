@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.animation.AnimationSpec
import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.nativeimpl.system.DefaultAnimationNativeImpl
import com.nexustools.genericui.style.transition.CssTimingFunction
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.*
import platform.posix.GUID
import platform.windows.*
import kotlin.math.roundToInt

/**
 * Windows VBlank- and `UIAnimation.dll`-backed [DefaultAnimationNativeImpl].
 *
 * Uses:
 * - Lazy background DWM VBlank pacer calling `DwmFlush()` (or monitor-paced sleep when DWM
 *   composition is disabled) and parking on an auto-reset Win32 Event (`WaitForSingleObject`
 *   with `INFINITE` at 0% CPU) whenever there is no active animation / `watchVBlank` work.
 * - Single-flight `PostMessageW(hwnd, WM_APP_VBLANK, 0, 0)` dispatch to an active HWND so `WndProc`
 *   steps frames at exact VBlank cadence, with a clean UI-thread fallback when no HWND is mapped.
 * - `IUIAnimationManager2` + `IUIAnimationTransitionLibrary2` (`UIAnimation.dll` COM) curve evaluation
 *   when invoked on an initialized UI apartment, falling back transparently to
 *   [DefaultAnimationNativeImpl] eased stepping otherwise.
 * - `DwmGetCompositionTimingInfo` and `MonitorFromWindow` + `EnumDisplaySettingsW` refresh-rate queries.
 */
class WindowsAnimationNativeImpl : DefaultAnimationNativeImpl() {

    private class UiAnimationEntry(
        val id: Int,
        val variablePtr: COpaquePointer,
        val spec: AnimationSpec,
        val startMs: Double,
        val useSoftwareEasing: Boolean,
        val onProgress: (Float) -> Unit,
        val onFinished: (Boolean) -> Unit,
        var settled: Boolean = false
    )

    private data class WorkerLoopState(
        val shouldContinue: Boolean,
        val isActive: Boolean,
        val wakeEvent: HANDLE?,
        val surfaceOnlyDemand: Boolean,
        val preferredRateHz: Double?
    )

    private val stateLock = WindowsSpinLock()
    private val comLock = WindowsSpinLock()

    private var hasDefaultVBlankWork: Boolean = false
    private var hostSurfaceDemandActive: Boolean = false
    private var hostSurfacePreferredRateHz: Double? = null
    private var workerShouldRun: Boolean = true
    private var workerStarted: Boolean = false
    private var workerThreadHandle: HANDLE? = null
    private var wakeEventHandle: HANDLE? = null
    private var workerSelfRef: StableRef<WindowsAnimationNativeImpl>? = null

    private var vblankPostedInFlight: Boolean = false
    private var vblankPostedHwndKey: Long = 0L
    private var noHwndTickInFlight: Boolean = false

    private var comAttempted: Boolean = false
    private var comAvailable: Boolean = false
    private var uiAnimManagerPtr: COpaquePointer? = null
    private var uiTransitionLibPtr: COpaquePointer? = null
    private val uiAnimationEntries = LinkedHashMap<Int, UiAnimationEntry>()
    private var virtualClockMs: Double = 0.0

    init {
        stateLock.withLock {
            activeInstance = this
        }
        queryHighResTimestampMs()
    }

    override fun getRefreshRateHz(): Double {
        val hwnd = Win32NativeWindowHost.activeHwnd
            ?: Win32NativeWindowHost.secondaryWindowHwnds.values.firstOrNull { IsWindow(it) != 0 }

        if (Win32DwmUxThemeBridge.isDwmCompositionEnabled()) {
            Win32DwmUxThemeBridge.queryDwmRefreshRateHz(hwnd)?.let { return it }
        }

        queryDisplayFrequencyForHwnd(hwnd)?.let { return it }
        return 60.0
    }

    private fun queryDisplayFrequencyForHwnd(hwnd: HWND?): Double? = memScoped {
        val devMode = alloc<DEVMODEW>()
        devMode.dmSize = sizeOf<DEVMODEW>().toUShort()

        if (hwnd != null && IsWindow(hwnd) != 0) {
            val hMon = MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST.toUInt())
            if (hMon != null) {
                val mi = alloc<MONITORINFOEXW>()
                mi.cbSize = sizeOf<MONITORINFOEXW>().toUInt()
                if (GetMonitorInfoW(hMon, mi.ptr.reinterpret()) != 0) {
                    if (EnumDisplaySettingsW(mi.szDevice, ENUM_CURRENT_SETTINGS, devMode.ptr) != 0) {
                        val hz = devMode.dmDisplayFrequency.toInt()
                        if (hz > 0) return@memScoped hz.toDouble()
                    }
                }
            }
        }

        if (EnumDisplaySettingsW(null, ENUM_CURRENT_SETTINGS, devMode.ptr) != 0) {
            val hz = devMode.dmDisplayFrequency.toInt()
            if (hz > 0) return@memScoped hz.toDouble()
        }
        null
    }

    override fun onActiveVBlankWorkChanged(hasActiveWork: Boolean) {
        val needStartOrWake = stateLock.withLock {
            hasDefaultVBlankWork = hasActiveWork
            val activeNow = hasAnyActiveWorkLocked()
            if (vblankPostedInFlight && vblankPostedHwndKey != 0L) {
                val h = vblankPostedHwndKey.toCPointer<HWND__>()
                if (h == null || IsWindow(h) == 0) {
                    vblankPostedInFlight = false
                    vblankPostedHwndKey = 0L
                }
            }
            activeNow
        }
        if (needStartOrWake) {
            ensureWorkerStartedAndWake()
        }
    }

    internal fun setHostSurfaceDemand(active: Boolean, preferredRateHz: Double?) {
        val shouldWake = stateLock.withLock {
            val rateChanged = hostSurfacePreferredRateHz != preferredRateHz
            hostSurfaceDemandActive = active
            hostSurfacePreferredRateHz = preferredRateHz
            if (vblankPostedInFlight && vblankPostedHwndKey != 0L) {
                val h = vblankPostedHwndKey.toCPointer<HWND__>()
                if (h == null || IsWindow(h) == 0) {
                    vblankPostedInFlight = false
                    vblankPostedHwndKey = 0L
                }
            }
            workerShouldRun && !isDisposed && (active || (hasAnyActiveWorkLocked() && rateChanged))
        }
        if (shouldWake) {
            ensureWorkerStartedAndWake()
        }
    }

    override fun onRequestNativeFrame() {
        val activeNow = stateLock.withLock { hasAnyActiveWorkLocked() }
        if (activeNow) {
            ensureWorkerStartedAndWake()
        }
    }

    private fun hasAnyActiveWorkLocked(): Boolean =
        workerShouldRun && !isDisposed && (hasDefaultVBlankWork || uiAnimationEntries.isNotEmpty() || hostSurfaceDemandActive)

    private fun ensureWorkerStartedAndWake() {
        var eventToSignal: HANDLE? = null
        stateLock.withLock {
            if (isDisposed || !workerShouldRun) return
            if (wakeEventHandle == null) {
                wakeEventHandle = CreateEventW(null, 0, 0, null)
            }
            eventToSignal = wakeEventHandle
            if (!workerStarted && wakeEventHandle != null) {
                workerStarted = true
                val ref = StableRef.create(this)
                workerSelfRef = ref
                val proc = staticCFunction { param: COpaquePointer? ->
                    val inst = param?.asStableRef<WindowsAnimationNativeImpl>()?.get()
                    inst?.runVBlankThreadLoop()
                    0u
                }
                val hThread = CreateThread(null, 0.convert(), proc, ref.asCPointer(), 0u, null)
                if (hThread != null) {
                    workerThreadHandle = hThread
                } else {
                    workerStarted = false
                    workerSelfRef = null
                    ref.dispose()
                }
            }
        }
        eventToSignal?.let { SetEvent(it) }
    }

    private fun runVBlankThreadLoop() {
        while (true) {
            val state = stateLock.withLock {
                WorkerLoopState(
                    shouldContinue = workerShouldRun && !isDisposed,
                    isActive = hasAnyActiveWorkLocked(),
                    wakeEvent = wakeEventHandle,
                    surfaceOnlyDemand = !hasDefaultVBlankWork && uiAnimationEntries.isEmpty() && hostSurfaceDemandActive,
                    preferredRateHz = hostSurfacePreferredRateHz
                )
            }
            if (!state.shouldContinue) break
            val ev = state.wakeEvent
            if (!state.isActive) {
                stateLock.withLock {
                    vblankPostedInFlight = false
                    vblankPostedHwndKey = 0L
                }
                if (ev != null) {
                    WaitForSingleObject(ev, INFINITE)
                } else {
                    Sleep(16u)
                }
                continue
            }

            val displayHz = getRefreshRateHz().let { if (it.isFinite() && it > 0.0) it else 60.0 }
            val useDwmFlush = Win32DwmUxThemeBridge.isDwmCompositionEnabled() &&
                Win32DwmUxThemeBridge.hasDwmFlush()
            val pref = state.preferredRateHz
            if (state.surfaceOnlyDemand && pref != null && pref > 0.0) {
                val targetHz = minOf(pref, displayHz).coerceAtLeast(1.0)
                if (targetHz >= displayHz - 0.5 && useDwmFlush) {
                    val hr = Win32DwmUxThemeBridge.dwmFlush()
                    if (hr < 0) {
                        val waitMs = (1000.0 / displayHz).roundToInt().coerceAtLeast(1)
                        if (ev != null) WaitForSingleObject(ev, waitMs.toUInt()) else Sleep(waitMs.toUInt())
                    }
                } else {
                    val waitMs = (1000.0 / targetHz).roundToInt().coerceAtLeast(1)
                    if (ev != null) WaitForSingleObject(ev, waitMs.toUInt()) else Sleep(waitMs.toUInt())
                }
            } else {
                val intervalMs = (1000.0 / displayHz).roundToInt().coerceAtLeast(1)
                if (useDwmFlush) {
                    val hr = Win32DwmUxThemeBridge.dwmFlush()
                    if (hr < 0) {
                        if (ev != null) WaitForSingleObject(ev, intervalMs.toUInt()) else Sleep(intervalMs.toUInt())
                    }
                } else {
                    if (ev != null) WaitForSingleObject(ev, intervalMs.toUInt()) else Sleep(intervalMs.toUInt())
                }
            }

            val stillActive = stateLock.withLock { workerShouldRun && !isDisposed && hasAnyActiveWorkLocked() }
            if (!stillActive) continue

            val targetHwnd = resolveActiveTargetHwnd()
            if (targetHwnd != null) {
                val hwndKey = targetHwnd.toLong()
                val shouldPost = stateLock.withLock {
                    if (!workerShouldRun || isDisposed || !hasAnyActiveWorkLocked()) {
                        false
                    } else if (vblankPostedInFlight && vblankPostedHwndKey == hwndKey) {
                        false
                    } else {
                        vblankPostedInFlight = true
                        vblankPostedHwndKey = hwndKey
                        true
                    }
                }
                if (shouldPost) {
                    if (PostMessageW(targetHwnd, Win32NativeWindowHost.WM_APP_VBLANK, 0uL, 0L) == 0) {
                        stateLock.withLock {
                            vblankPostedInFlight = false
                            vblankPostedHwndKey = 0L
                        }
                    }
                }
            } else {
                val shouldDispatchFallback = stateLock.withLock {
                    if (!workerShouldRun || isDisposed || !hasAnyActiveWorkLocked() || noHwndTickInFlight) {
                        false
                    } else {
                        noHwndTickInFlight = true
                        true
                    }
                }
                if (shouldDispatchFallback) {
                    val nowMs = queryHighResTimestampMs()
                    GenericUiThreading.runOnUi {
                        try {
                            if (!isDisposed) {
                                dispatchFrameAtTimestamp(nowMs)
                            }
                        } finally {
                            stateLock.withLock { noHwndTickInFlight = false }
                        }
                    }
                }
            }
        }
    }

    private fun resolveActiveTargetHwnd(): HWND? {
        val main = Win32NativeWindowHost.activeHwnd
        if (main != null && IsWindow(main) != 0) return main
        for (sec in Win32NativeWindowHost.secondaryWindowHwnds.values) {
            if (IsWindow(sec) != 0) return sec
        }
        return null
    }

    /**
     * Called by `Win32NativeWindowHost` `WndProc` on `WM_APP_VBLANK`.
     */
    internal fun handleWmAppVBlank() {
        stateLock.withLock {
            vblankPostedInFlight = false
            vblankPostedHwndKey = 0L
        }
        if (isDisposed) return
        val nowMs = queryHighResTimestampMs()
        dispatchFrameAtTimestamp(nowMs)
        Win32NativeWindowHost.stepLiveVBlankFrame(nowMsOverride = nowMs)
    }

    /**
     * Advances both `UIAnimation.dll` curves and [DefaultAnimationNativeImpl] ticks at [timestampMs].
     */
    internal fun tickAtTimestampMs(timestampMs: Double): Int =
        dispatchFrameAtTimestamp(timestampMs)

    private fun dispatchFrameAtTimestamp(timestampMs: Double): Int {
        if (isDisposed) return 0
        val effectiveMs = if (timestampMs > virtualClockMs) timestampMs else virtualClockMs
        virtualClockMs = effectiveMs
        val nativeCount = stepUiAnimationEntriesAtMs(effectiveMs)
        val defaultCount = onNativeVBlankTick(effectiveMs)
        return nativeCount + defaultCount
    }

    override fun stepFrame(deltaMs: Double): Int {
        if (isDisposed) return 0
        val step = deltaMs.coerceAtLeast(0.0)
        if (virtualClockMs <= 0.0) {
            virtualClockMs = queryHighResTimestampMs()
        }
        virtualClockMs += step
        val nativeCount = stepUiAnimationEntriesAtMs(virtualClockMs)
        val defaultCount = super.stepFrame(step)
        return nativeCount + defaultCount
    }

    override fun startNativeTimedAnimation(
        id: Int,
        spec: AnimationSpec,
        target: NativeImpl<*>?,
        onProgress: (easedProgress: Float) -> Unit,
        onFinished: (cancelled: Boolean) -> Unit
    ): (() -> Unit)? {
        if (isDisposed || spec.durationMs <= 0 || spec.delayMs > 0) {
            return null
        }
        if (Win32NativeWindowHost.visualTestMode || !GenericUiThreading.isOnUiThread()) {
            return null
        }
        if (!ensureUiAnimationComAvailable()) {
            return null
        }

        val entry = createUiAnimationEntry(id, spec, onProgress, onFinished) ?: return null
        stateLock.withLock {
            uiAnimationEntries[id] = entry
        }
        ensureWorkerStartedAndWake()

        return {
            val removed = stateLock.withLock {
                val e = uiAnimationEntries.remove(id)
                if (e != null && !e.settled) {
                    e.settled = true
                    e
                } else {
                    null
                }
            }
            if (removed != null) {
                comLock.withLock {
                    comRelease(removed.variablePtr)
                }
                removed.onFinished(true)
            }
        }
    }

    internal fun ensureUiAnimationComAvailable(): Boolean = comLock.withLock {
        if (comAttempted) return@withLock comAvailable
        comAttempted = true
        CoInitializeEx(null, COINIT_APARTMENTTHREADED)

        val mgr = memScoped {
            val clsid = alloc<GUID>()
            val iid = alloc<GUID>()
            fillGuid(clsid, 0xd25d8842u, 0x8884u, 0x4a4au, 0xb3u, 0x21u, 0x09u, 0x13u, 0x14u, 0x37u, 0x9bu, 0xddu)
            fillGuid(iid, 0xd8b6f7d4u, 0x4109u, 0x4d3fu, 0xacu, 0xeeu, 0x87u, 0x99u, 0x26u, 0x96u, 0x8cu, 0xb1u)
            val out = alloc<COpaquePointerVar>()
            val hr = CoCreateInstance(clsid.ptr, null, CLSCTX_INPROC_SERVER.toUInt(), iid.ptr, out.ptr)
            if (hr >= 0) out.value else null
        }
        val lib = memScoped {
            val clsid = alloc<GUID>()
            val iid = alloc<GUID>()
            fillGuid(clsid, 0x812f944au, 0xc5c8u, 0x4cd9u, 0xb0u, 0xa6u, 0xb3u, 0xdau, 0x80u, 0x2fu, 0x22u, 0x8du)
            fillGuid(iid, 0x03cfae53u, 0x9580u, 0x4ee3u, 0xb3u, 0x63u, 0x2eu, 0xceu, 0x51u, 0xb4u, 0xafu, 0x6au)
            val out = alloc<COpaquePointerVar>()
            val hr = CoCreateInstance(clsid.ptr, null, CLSCTX_INPROC_SERVER.toUInt(), iid.ptr, out.ptr)
            if (hr >= 0) out.value else null
        }
        if (mgr != null && lib != null) {
            uiAnimManagerPtr = mgr
            uiTransitionLibPtr = lib
            comAvailable = true
        } else {
            comRelease(mgr)
            comRelease(lib)
            comAvailable = false
        }
        comAvailable
    }

    private fun createUiAnimationEntry(
        id: Int,
        spec: AnimationSpec,
        onProgress: (Float) -> Unit,
        onFinished: (Boolean) -> Unit
    ): UiAnimationEntry? = comLock.withLock {
        val mgr = uiAnimManagerPtr ?: return@withLock null
        val lib = uiTransitionLibPtr ?: return@withLock null
        val wallMs = queryHighResTimestampMs()
        val nowMs = if (wallMs > virtualClockMs) wallMs else virtualClockMs
        virtualClockMs = nowMs
        val nowSec = nowMs / 1000.0
        val durSec = spec.durationMs.toDouble() / 1000.0

        memScoped {
            val varOut = alloc<COpaquePointerVar>()
            val createVarFn = comMethod<CFunction<(COpaquePointer, Double, CPointer<COpaquePointerVar>) -> Int>>(
                mgr,
                SLOT_MGR2_CREATE_ANIMATION_VARIABLE
            )
            if (createVarFn(mgr, 0.0, varOut.ptr) < 0) return@withLock null
            val variablePtr = varOut.value ?: return@withLock null

            val transOut = alloc<COpaquePointerVar>()
            var useSoftEasing = false
            val timing = spec.timingFunction
            val transHr = if (timing.isLinear) {
                val fn = comMethod<CFunction<(COpaquePointer, Double, Double, CPointer<COpaquePointerVar>) -> Int>>(
                    lib,
                    SLOT_LIB2_CREATE_LINEAR_TRANSITION
                )
                fn(lib, durSec, 1.0, transOut.ptr)
            } else {
                val fn = comMethod<CFunction<(COpaquePointer, Double, Double, Double, Double, Double, Double, CPointer<COpaquePointerVar>) -> Int>>(
                    lib,
                    SLOT_LIB2_CREATE_CUBIC_BEZIER_LINEAR_TRANSITION
                )
                val hr = fn(
                    lib,
                    durSec,
                    1.0,
                    timing.x1.toDouble(),
                    timing.y1.toDouble(),
                    timing.x2.toDouble(),
                    timing.y2.toDouble(),
                    transOut.ptr
                )
                if (hr < 0) {
                    useSoftEasing = true
                    val linFn = comMethod<CFunction<(COpaquePointer, Double, Double, CPointer<COpaquePointerVar>) -> Int>>(
                        lib,
                        SLOT_LIB2_CREATE_LINEAR_TRANSITION
                    )
                    linFn(lib, durSec, 1.0, transOut.ptr)
                } else {
                    hr
                }
            }
            val transPtr = transOut.value
            if (transHr < 0 || transPtr == null) {
                comRelease(variablePtr)
                return@withLock null
            }

            val scheduleFn = comMethod<CFunction<(COpaquePointer, COpaquePointer, COpaquePointer, Double) -> Int>>(
                mgr,
                SLOT_MGR2_SCHEDULE_TRANSITION
            )
            val schedHr = scheduleFn(mgr, variablePtr, transPtr, nowSec)
            comRelease(transPtr)
            if (schedHr < 0) {
                comRelease(variablePtr)
                return@withLock null
            }

            UiAnimationEntry(
                id = id,
                variablePtr = variablePtr,
                spec = spec,
                startMs = nowMs,
                useSoftwareEasing = useSoftEasing,
                onProgress = onProgress,
                onFinished = onFinished
            )
        }
    }

    private fun stepUiAnimationEntriesAtMs(nowMs: Double): Int {
        val snapshot = stateLock.withLock {
            if (uiAnimationEntries.isEmpty()) return 0
            uiAnimationEntries.values.toList()
        }
        val mgr = comLock.withLock { uiAnimManagerPtr } ?: return 0
        val completed = ArrayList<UiAnimationEntry>()
        var updatedCount = 0

        comLock.withLock {
            memScoped {
                val updateFn = comMethod<CFunction<(COpaquePointer, Double, CPointer<IntVar>?) -> Int>>(
                    mgr,
                    SLOT_MGR2_UPDATE
                )
                val resVar = alloc<IntVar>()
                updateFn(mgr, nowMs / 1000.0, resVar.ptr)

                val valVar = alloc<DoubleVar>()
                for (entry in snapshot) {
                    if (entry.settled) continue
                    val getValFn = comMethod<CFunction<(COpaquePointer, CPointer<DoubleVar>) -> Int>>(
                        entry.variablePtr,
                        SLOT_VAR2_GET_VALUE
                    )
                    val elapsedMs = nowMs - entry.startMs
                    val rawLinear = if (getValFn(entry.variablePtr, valVar.ptr) >= 0) {
                        valVar.value.toFloat().coerceIn(0f, 1f)
                    } else {
                        (elapsedMs / entry.spec.durationMs.toDouble()).toFloat().coerceIn(0f, 1f)
                    }
                    val eased = if (entry.useSoftwareEasing) {
                        entry.spec.timingFunction.evaluate(rawLinear)
                    } else {
                        rawLinear
                    }
                    entry.onProgress(eased)
                    updatedCount++
                    if (elapsedMs >= entry.spec.durationMs.toDouble() - 0.5) {
                        entry.settled = true
                        completed.add(entry)
                    }
                }
            }
        }

        if (completed.isNotEmpty()) {
            stateLock.withLock {
                for (c in completed) {
                    uiAnimationEntries.remove(c.id)
                }
            }
            for (c in completed) {
                comLock.withLock { comRelease(c.variablePtr) }
                c.onProgress(1f)
                c.onFinished(false)
            }
        }
        return updatedCount
    }

    private data class DisposeSnapshot(
        val entriesToCancel: List<UiAnimationEntry>,
        val threadToJoin: HANDLE?,
        val evToWake: HANDLE?
    )

    override fun dispose() {
        val snapshot = stateLock.withLock {
            workerShouldRun = false
            val entries = uiAnimationEntries.values.toList()
            uiAnimationEntries.clear()
            val thread = workerThreadHandle
            val ev = wakeEventHandle
            if (activeInstance === this) {
                activeInstance = null
            }
            DisposeSnapshot(entriesToCancel = entries, threadToJoin = thread, evToWake = ev)
        }
        super.dispose()
        snapshot.evToWake?.let { SetEvent(it) }
        val threadToJoin = snapshot.threadToJoin
        if (threadToJoin != null) {
            WaitForSingleObject(threadToJoin, 2000u)
            CloseHandle(threadToJoin)
        }
        stateLock.withLock {
            workerThreadHandle = null
            val ref = workerSelfRef
            workerSelfRef = null
            ref?.dispose()
            wakeEventHandle?.let { CloseHandle(it) }
            wakeEventHandle = null
        }
        comLock.withLock {
            for (entry in snapshot.entriesToCancel) {
                if (!entry.settled) {
                    entry.settled = true
                    comRelease(entry.variablePtr)
                }
            }
            comRelease(uiTransitionLibPtr)
            uiTransitionLibPtr = null
            comRelease(uiAnimManagerPtr)
            uiAnimManagerPtr = null
            comAvailable = false
        }
    }

    private fun queryHighResTimestampMs(): Double = memScoped {
        val freq = alloc<LARGE_INTEGER>()
        val counter = alloc<LARGE_INTEGER>()
        QueryPerformanceFrequency(freq.ptr)
        QueryPerformanceCounter(counter.ptr)
        val ticksPerMs = (freq.QuadPart.toDouble() / 1000.0).coerceAtLeast(1.0)
        counter.QuadPart.toDouble() / ticksPerMs
    }

    private inline fun <reified T : CFunction<*>> comMethod(obj: COpaquePointer, slot: Int): CPointer<T> {
        val vtblPtr = obj.reinterpret<CPointerVar<COpaquePointerVar>>().pointed.value
            ?: error("Null COM vtable pointer")
        return vtblPtr[slot]!!.reinterpret()
    }

    private fun comRelease(obj: COpaquePointer?) {
        if (obj == null) return
        val fnRelease = comMethod<CFunction<(COpaquePointer) -> UInt>>(obj, SLOT_UNKNOWN_RELEASE)
        fnRelease(obj)
    }

    private fun fillGuid(
        guid: GUID,
        d1: UInt,
        d2: UShort,
        d3: UShort,
        b0: UByte,
        b1: UByte,
        b2: UByte,
        b3: UByte,
        b4: UByte,
        b5: UByte,
        b6: UByte,
        b7: UByte
    ) {
        guid.Data1 = d1
        guid.Data2 = d2
        guid.Data3 = d3
        guid.Data4[0] = b0
        guid.Data4[1] = b1
        guid.Data4[2] = b2
        guid.Data4[3] = b3
        guid.Data4[4] = b4
        guid.Data4[5] = b5
        guid.Data4[6] = b6
        guid.Data4[7] = b7
    }

    companion object {
        private const val SLOT_UNKNOWN_RELEASE = 2
        private const val SLOT_MGR2_CREATE_ANIMATION_VARIABLE = 4
        private const val SLOT_MGR2_SCHEDULE_TRANSITION = 5
        private const val SLOT_MGR2_UPDATE = 9
        private const val SLOT_VAR2_GET_VALUE = 4
        private const val SLOT_LIB2_CREATE_LINEAR_TRANSITION = 8
        private const val SLOT_LIB2_CREATE_CUBIC_BEZIER_LINEAR_TRANSITION = 20

        internal var activeInstance: WindowsAnimationNativeImpl? = null
    }
}
