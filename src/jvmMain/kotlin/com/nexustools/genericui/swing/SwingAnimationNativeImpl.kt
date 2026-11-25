package com.nexustools.genericui.swing

import com.nexustools.genericui.nativeimpl.system.DefaultAnimationNativeImpl
import java.awt.Component
import java.awt.DisplayMode
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import javax.swing.SwingUtilities
import kotlin.math.roundToLong

/**
 * Monitor-synchronized Java Swing implementation of
 * [com.nexustools.genericui.nativeimpl.system.AnimationNativeImpl] extending
 * [DefaultAnimationNativeImpl].
 *
 * - Tracks active / most-recent AWT [Window] / [Component] `GraphicsConfiguration` instances via
 *   [WeakReference]s and `PropertyChangeListener("graphicsConfiguration")` so [getRefreshRateHz]
 *   automatically adapts across `60Hz`, `120Hz` ProMotion, `144Hz`, and `240Hz` monitors (falling
 *   back to default [GraphicsEnvironment] screen device refresh rate or `60.0` when unknown/headless).
 * - Drives active animations, `requestAnimationFrame`, and `watchVBlank` consumers on a dedicated
 *   daemon VBlank pacer thread (`GenericUI-Swing-VBlank`) using drift-free `LockSupport.parkNanos`
 *   intervals (`1_000_000_000L / hz`), parking completely at 0% CPU when idle (`hasActiveWork == false`).
 * - Coalesces single-flight `SwingUtilities.invokeLater` ticks (skipping missed VBlanks while a
 *   previous EDT frame is still running), flushes via `Toolkit.getDefaultToolkit().sync()`, and
 *   dispatches [onNativeVBlankTick] cleanly on the EDT.
 */
class SwingAnimationNativeImpl : DefaultAnimationNativeImpl() {

    private class TrackedEntry(
        val componentRef: WeakReference<Component>,
        var lastObservedCompGcRef: WeakReference<GraphicsConfiguration>? = null,
        var lastEventGcRef: WeakReference<GraphicsConfiguration>? = null,
        var gcListener: PropertyChangeListener? = null,
        var windowListener: WindowAdapter? = null,
        var windowFocusListener: WindowFocusListener? = null
    )

    private val windowsLock = Any()
    private val instanceEntries = ArrayList<TrackedEntry>()
    private val globalEntries = ArrayList<TrackedEntry>()

    private val disposed = AtomicBoolean(false)
    private val activeWork = AtomicBoolean(false)
    private val frameTickPending = AtomicBoolean(false)
    private val pacerLock = Any()

    @Volatile
    private var pacerThread: Thread? = null

    @Volatile
    private var cachedRefreshRateHz: Double = DEFAULT_REFRESH_RATE_HZ

    internal val isPacerThreadAlive: Boolean
        get() = pacerThread?.isAlive == true

    internal val pacerThreadState: Thread.State?
        get() = pacerThread?.state

    internal val hasActiveVBlankWork: Boolean
        get() = activeWork.get()

    internal val isFrameTickInFlight: Boolean
        get() = frameTickPending.get()

    internal fun getTrackedWindowCount(): Int = synchronized(windowsLock) {
        purgeDeadEntriesLocked(instanceEntries)
        purgeDeadEntriesLocked(globalEntries)
        instanceEntries.size + globalEntries.size
    }

    init {
        synchronized(globalLock) {
            liveInstances.removeIf { it.get() == null }
            liveInstances.add(WeakReference(this))
            purgeDeadComponentRefsLocked(globalWindowRefs)
            synchronized(windowsLock) {
                for (ref in globalWindowRefs) {
                    val comp = ref.get() ?: continue
                    addOrPromoteEntryLocked(globalEntries, comp, isGlobal = true)
                }
            }
        }
        refreshCachedRefreshRateHz()
    }

    /**
     * Registers an AWT [Window] or [Component] with this scheduler instance and marks it as the
     * most-recently active component for live monitor refresh-rate resolution.
     */
    fun registerWindow(component: Component) {
        if (disposed.get()) return
        synchronized(windowsLock) {
            addOrPromoteEntryLocked(instanceEntries, component, isGlobal = false)
        }
        refreshCachedRefreshRateHz()
        if (activeWork.get()) {
            pacerThread?.let { LockSupport.unpark(it) }
        }
    }

    /**
     * Unregisters an AWT [Window] or [Component] from this scheduler instance and removes listeners.
     */
    fun unregisterWindow(component: Component) {
        synchronized(windowsLock) {
            removeEntryLocked(instanceEntries, component)
        }
        refreshCachedRefreshRateHz()
    }

    private fun onGlobalWindowRegistered(component: Component) {
        if (disposed.get()) return
        synchronized(windowsLock) {
            addOrPromoteEntryLocked(globalEntries, component, isGlobal = true)
        }
        refreshCachedRefreshRateHz()
        if (activeWork.get()) {
            pacerThread?.let { LockSupport.unpark(it) }
        }
    }

    private fun onGlobalWindowUnregistered(component: Component) {
        synchronized(windowsLock) {
            removeEntryLocked(globalEntries, component)
        }
        refreshCachedRefreshRateHz()
    }

    private fun addOrPromoteEntryLocked(
        list: MutableList<TrackedEntry>,
        component: Component,
        isGlobal: Boolean
    ) {
        purgeDeadEntriesLocked(list)
        val existingIdx = list.indexOfFirst { it.componentRef.get() === component }
        if (existingIdx >= 0) {
            val existing = list.removeAt(existingIdx)
            val liveGc = runCatching { component.graphicsConfiguration }.getOrNull()
            if (liveGc != null && liveGc !== existing.lastObservedCompGcRef?.get()) {
                existing.lastObservedCompGcRef = WeakReference(liveGc)
                existing.lastEventGcRef = null
            }
            list.add(existing)
            return
        }
        val liveGc = runCatching { component.graphicsConfiguration }.getOrNull()
        val entry = TrackedEntry(
            componentRef = WeakReference(component),
            lastObservedCompGcRef = liveGc?.let { WeakReference(it) }
        )
        attachListeners(entry, component, isGlobal)
        list.add(entry)
    }

    private fun removeEntryLocked(
        list: MutableList<TrackedEntry>,
        component: Component
    ) {
        purgeDeadEntriesLocked(list)
        val iterator = list.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val target = entry.componentRef.get()
            if (target == null || target === component) {
                iterator.remove()
                if (target === component) {
                    detachListeners(entry, component)
                }
            }
        }
    }

    private fun attachListeners(entry: TrackedEntry, component: Component, isGlobal: Boolean) {
        val selfRef = WeakReference(this)
        val compRef = WeakReference(component)
        val gcListener = object : PropertyChangeListener {
            override fun propertyChange(evt: PropertyChangeEvent) {
                val impl = selfRef.get()
                val target = compRef.get()
                if (impl == null || target == null || impl.disposed.get()) {
                    (evt.source as? Component)?.removePropertyChangeListener(GRAPHICS_CONFIG_PROPERTY, this)
                    return
                }
                impl.onComponentGraphicsConfigurationChanged(
                    component = target,
                    newGc = evt.newValue as? GraphicsConfiguration,
                    isGlobal = isGlobal
                )
            }
        }
        entry.gcListener = gcListener
        runCatching { component.addPropertyChangeListener(GRAPHICS_CONFIG_PROPERTY, gcListener) }

        if (component is Window) {
            val winListener = object : WindowAdapter() {
                override fun windowActivated(e: WindowEvent?) {
                    val impl = selfRef.get()
                    val target = compRef.get()
                    if (impl == null || target == null || impl.disposed.get()) {
                        (e?.window ?: (e?.source as? Window))?.removeWindowListener(this)
                        return
                    }
                    impl.promoteComponent(target, isGlobal)
                }

                override fun windowDeiconified(e: WindowEvent?) {
                    val impl = selfRef.get()
                    val target = compRef.get()
                    if (impl == null || target == null || impl.disposed.get()) {
                        (e?.window ?: (e?.source as? Window))?.removeWindowListener(this)
                        return
                    }
                    impl.promoteComponent(target, isGlobal)
                }
            }
            val focusListener = object : WindowFocusListener {
                override fun windowGainedFocus(e: WindowEvent?) {
                    val impl = selfRef.get()
                    val target = compRef.get()
                    if (impl == null || target == null || impl.disposed.get()) {
                        (e?.window ?: (e?.source as? Window))?.removeWindowFocusListener(this)
                        return
                    }
                    impl.promoteComponent(target, isGlobal)
                }

                override fun windowLostFocus(e: WindowEvent?) {}
            }
            entry.windowListener = winListener
            entry.windowFocusListener = focusListener
            runCatching { component.addWindowListener(winListener) }
            runCatching { component.addWindowFocusListener(focusListener) }
        }
    }

    private fun detachListeners(entry: TrackedEntry, component: Component) {
        entry.gcListener?.let { listener ->
            entry.gcListener = null
            runCatching { component.removePropertyChangeListener(GRAPHICS_CONFIG_PROPERTY, listener) }
        }
        if (component is Window) {
            entry.windowListener?.let { listener ->
                entry.windowListener = null
                runCatching { component.removeWindowListener(listener) }
            }
            entry.windowFocusListener?.let { listener ->
                entry.windowFocusListener = null
                runCatching { component.removeWindowFocusListener(listener) }
            }
        }
    }

    private fun purgeDeadEntriesLocked(list: MutableList<TrackedEntry>) {
        list.removeIf { it.componentRef.get() == null }
    }

    private fun onComponentGraphicsConfigurationChanged(
        component: Component,
        newGc: GraphicsConfiguration?,
        isGlobal: Boolean
    ) {
        if (disposed.get()) return
        synchronized(windowsLock) {
            val targetList = if (isGlobal) globalEntries else instanceEntries
            purgeDeadEntriesLocked(targetList)
            val liveGc = runCatching { component.graphicsConfiguration }.getOrNull()
            for (entry in targetList) {
                if (entry.componentRef.get() === component) {
                    entry.lastObservedCompGcRef = liveGc?.let { WeakReference(it) }
                    entry.lastEventGcRef = newGc?.let { WeakReference(it) }
                }
            }
        }
        refreshCachedRefreshRateHz()
        if (activeWork.get()) {
            pacerThread?.let { LockSupport.unpark(it) }
        }
    }

    private fun promoteComponent(component: Component, isGlobal: Boolean) {
        if (disposed.get()) return
        synchronized(windowsLock) {
            val targetList = if (isGlobal) globalEntries else instanceEntries
            purgeDeadEntriesLocked(targetList)
            val idx = targetList.indexOfFirst { it.componentRef.get() === component }
            if (idx >= 0) {
                val entry = targetList.removeAt(idx)
                targetList.add(entry)
            }
        }
        refreshCachedRefreshRateHz()
        if (activeWork.get()) {
            pacerThread?.let { LockSupport.unpark(it) }
        }
    }

    private fun extractRefreshRateHz(entry: TrackedEntry): Double? {
        val comp = entry.componentRef.get() ?: return null
        val liveGc = runCatching { comp.graphicsConfiguration }.getOrNull()
        val lastCompGc = entry.lastObservedCompGcRef?.get()
        val gc = if (liveGc != null && liveGc !== lastCompGc) {
            entry.lastObservedCompGcRef = WeakReference(liveGc)
            entry.lastEventGcRef = null
            liveGc
        } else {
            entry.lastEventGcRef?.get() ?: liveGc
        } ?: return null
        val rate = runCatching { gc.device?.displayMode?.refreshRate }.getOrNull() ?: return null
        if (rate == DisplayMode.REFRESH_RATE_UNKNOWN || rate <= 0) return null
        return rate.toDouble()
    }

    private fun findBestRefreshRateInListLocked(list: MutableList<TrackedEntry>): Double? {
        purgeDeadEntriesLocked(list)
        if (list.isEmpty()) return null
        // 1. Prefer showing components (most-recently active first)
        for (i in list.indices.reversed()) {
            val entry = list[i]
            val comp = entry.componentRef.get() ?: continue
            if (comp.isShowing) {
                extractRefreshRateHz(entry)?.let { return it }
            }
        }
        // 2. Fall back to visible components (covers headless/offscreen test components)
        for (i in list.indices.reversed()) {
            val entry = list[i]
            val comp = entry.componentRef.get() ?: continue
            if (comp.isVisible) {
                extractRefreshRateHz(entry)?.let { return it }
            }
        }
        // 3. Any remaining tracked component with valid monitor display mode
        for (i in list.indices.reversed()) {
            extractRefreshRateHz(list[i])?.let { return it }
        }
        return null
    }

    private fun refreshCachedRefreshRateHz(): Double {
        val resolved = resolveRefreshRateHzLive()
        cachedRefreshRateHz = resolved
        return resolved
    }

    private fun resolveRefreshRateHzLive(): Double {
        synchronized(windowsLock) {
            findBestRefreshRateInListLocked(instanceEntries)?.let { return it }
            findBestRefreshRateInListLocked(globalEntries)?.let { return it }
        }
        if (!GraphicsEnvironment.isHeadless()) {
            val envRate = runCatching {
                GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .defaultScreenDevice
                    ?.displayMode
                    ?.refreshRate
            }.getOrNull()
            if (envRate != null && envRate != DisplayMode.REFRESH_RATE_UNKNOWN && envRate > 0) {
                return envRate.toDouble()
            }
        }
        return DEFAULT_REFRESH_RATE_HZ
    }

    override fun getRefreshRateHz(): Double = refreshCachedRefreshRateHz()

    override fun onActiveVBlankWorkChanged(hasActiveWork: Boolean) {
        if (disposed.get()) {
            activeWork.set(false)
            return
        }
        if (hasActiveWork) {
            refreshCachedRefreshRateHz()
            activeWork.set(true)
            val thread = ensurePacerThreadStarted()
            LockSupport.unpark(thread)
        } else {
            activeWork.set(false)
            pacerThread?.let { LockSupport.unpark(it) }
        }
    }

    private fun ensurePacerThreadStarted(): Thread {
        pacerThread?.takeIf { it.isAlive }?.let { return it }
        synchronized(pacerLock) {
            pacerThread?.takeIf { it.isAlive }?.let { return it }
            val created = Thread({ runVBlankPacerLoop() }, PACER_THREAD_NAME).apply {
                isDaemon = true
                start()
            }
            pacerThread = created
            return created
        }
    }

    private fun runVBlankPacerLoop() {
        var nextDeadlineNs = 0L
        while (!disposed.get() && !Thread.currentThread().isInterrupted) {
            if (!activeWork.get()) {
                nextDeadlineNs = 0L
                LockSupport.park(this)
                continue
            }
            val hz = cachedRefreshRateHz.let {
                if (it.isFinite() && it > 0.0) it else DEFAULT_REFRESH_RATE_HZ
            }
            val intervalNs = (1_000_000_000.0 / hz).roundToLong().coerceAtLeast(MIN_FRAME_INTERVAL_NS)
            val nowNs = System.nanoTime()
            if (nextDeadlineNs == 0L) {
                nextDeadlineNs = nowNs + intervalNs
            }
            val remainingNs = nextDeadlineNs - nowNs
            if (remainingNs > 0L) {
                LockSupport.parkNanos(this, remainingNs)
                continue
            }
            nextDeadlineNs += intervalNs
            val afterNs = System.nanoTime()
            if (nextDeadlineNs < afterNs - intervalNs * 2L) {
                nextDeadlineNs = afterNs + intervalNs
            }
            if (disposed.get() || !activeWork.get()) {
                continue
            }
            dispatchSingleFlightEdtTick()
        }
    }

    private fun dispatchSingleFlightEdtTick() {
        if (!frameTickPending.compareAndSet(false, true)) {
            return
        }
        try {
            SwingUtilities.invokeLater {
                try {
                    if (disposed.get() || !activeWork.get()) {
                        return@invokeLater
                    }
                    refreshCachedRefreshRateHz()
                    if (!GraphicsEnvironment.isHeadless()) {
                        runCatching { Toolkit.getDefaultToolkit().sync() }
                    }
                    onNativeVBlankTick(System.nanoTime() / 1_000_000.0)
                } finally {
                    frameTickPending.set(false)
                }
            }
        } catch (_: Throwable) {
            frameTickPending.set(false)
        }
    }

    private fun clearTrackedWindows() {
        synchronized(windowsLock) {
            for (entry in instanceEntries) {
                entry.componentRef.get()?.let { detachListeners(entry, it) }
            }
            instanceEntries.clear()
            for (entry in globalEntries) {
                entry.componentRef.get()?.let { detachListeners(entry, it) }
            }
            globalEntries.clear()
        }
    }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) {
            super.dispose()
            return
        }
        activeWork.set(false)
        super.dispose()
        val threadToStop = synchronized(pacerLock) {
            val current = pacerThread
            pacerThread = null
            current
        }
        if (threadToStop != null) {
            threadToStop.interrupt()
            LockSupport.unpark(threadToStop)
            if (Thread.currentThread() !== threadToStop) {
                runCatching { threadToStop.join(250L) }
            }
        }
        clearTrackedWindows()
        synchronized(globalLock) {
            liveInstances.removeIf { it.get() == null || it.get() === this }
        }
    }

    companion object {
        private const val PACER_THREAD_NAME: String = "GenericUI-Swing-VBlank"
        private const val GRAPHICS_CONFIG_PROPERTY: String = "graphicsConfiguration"
        private const val DEFAULT_REFRESH_RATE_HZ: Double = 60.0
        private const val MIN_FRAME_INTERVAL_NS: Long = 1_000_000L

        private val globalLock = Any()
        private val globalWindowRefs = ArrayList<WeakReference<Component>>()
        private val liveInstances = ArrayList<WeakReference<SwingAnimationNativeImpl>>()

        private fun purgeDeadComponentRefsLocked(refs: MutableList<WeakReference<Component>>) {
            refs.removeIf { it.get() == null }
        }

        /**
         * Registers an active AWT [Window] or [Component] globally so any active or future
         * [SwingAnimationNativeImpl] picks up its display refresh rate and monitor switches.
         */
        fun registerWindow(component: Component) {
            val instancesSnapshot: List<SwingAnimationNativeImpl>
            synchronized(globalLock) {
                purgeDeadComponentRefsLocked(globalWindowRefs)
                globalWindowRefs.removeIf { it.get() === component }
                globalWindowRefs.add(WeakReference(component))
                liveInstances.removeIf { it.get() == null }
                instancesSnapshot = liveInstances.mapNotNull { it.get() }
            }
            for (instance in instancesSnapshot) {
                instance.onGlobalWindowRegistered(component)
            }
        }

        /**
         * Unregisters a closed or disposed AWT [Window] or [Component] from global tracking.
         */
        fun unregisterWindow(component: Component) {
            val instancesSnapshot: List<SwingAnimationNativeImpl>
            synchronized(globalLock) {
                purgeDeadComponentRefsLocked(globalWindowRefs)
                globalWindowRefs.removeIf { it.get() === component }
                liveInstances.removeIf { it.get() == null }
                instancesSnapshot = liveInstances.mapNotNull { it.get() }
            }
            for (instance in instancesSnapshot) {
                instance.onGlobalWindowUnregistered(component)
            }
        }
    }
}
