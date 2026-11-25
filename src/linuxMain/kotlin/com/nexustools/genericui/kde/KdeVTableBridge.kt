@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.linux.LinuxReentrantLock
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.value
import kotlinx.cinterop.pointed
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction

/**
 * Dynamic C++ ABI vtable & `QMetaObject::connect` bridge for Qt 6 / KDE Frameworks 6 objects.
 *
 * At startup, [KdeVTableBridge] creates probe `QObject` and `QWidget` instances (`256` bytes each),
 * dynamically resolves live vtable slot indices via ELF `dlsym` symbol matching, verifies the
 * `QPaintDevice` subobject offset (`self + 16`, `devType() == 1`), and provides safe per-instance
 * virtual method interception and signal delivery without mutating read-only Qt `.rodata` vtables.
 */
internal object KdeVTableBridge {

    const val HOOK_NONE: Int = 0
    const val HOOK_LIFECYCLE: Int = 1 shl 0
    const val HOOK_PAINT: Int = 1 shl 1
    const val HOOK_RESIZE: Int = 1 shl 2
    const val HOOK_SIZE_HINT: Int = 1 shl 3
    const val HOOK_INPUT: Int = 1 shl 4
    const val HOOK_CLOSE: Int = 1 shl 5
    const val HOOK_WINDOW_EVENT: Int = 1 shl 6

    private const val VTABLE_COPY_SLOTS: Int = 192
    private const val FIRST_CUSTOM_SLOT_ID: Int = 100

    data class MouseEventInfo(
        val localX: Float,
        val localY: Float,
        val globalX: Float,
        val globalY: Float,
        val button: Int,
        val buttonsMask: Int,
        val modifiers: Int
    )

    data class WheelEventInfo(
        val localX: Float,
        val localY: Float,
        val deltaX: Float,
        val deltaY: Float,
        val modifiers: Int
    )

    data class KeyEventInfo(
        val qtKey: Int,
        val text: String,
        val modifiers: Int
    )

    class PeerHooks {
        var onPaint: ((widget: COpaquePointer, painter: COpaquePointer, width: Int, height: Int) -> Unit)? = null
        var onResize: ((widget: COpaquePointer, width: Int, height: Int) -> Unit)? = null
        var onSizeHint: ((widget: COpaquePointer) -> Pair<Int, Int>?)? = null
        var onMinimumSizeHint: ((widget: COpaquePointer) -> Pair<Int, Int>?)? = null
        var onMousePress: ((widget: COpaquePointer, info: MouseEventInfo) -> Boolean)? = null
        var onMouseRelease: ((widget: COpaquePointer, info: MouseEventInfo) -> Boolean)? = null
        var onMouseDoubleClick: ((widget: COpaquePointer, info: MouseEventInfo) -> Boolean)? = null
        var onMouseMove: ((widget: COpaquePointer, info: MouseEventInfo) -> Boolean)? = null
        var onWheel: ((widget: COpaquePointer, info: WheelEventInfo) -> Boolean)? = null
        var onEnter: ((widget: COpaquePointer) -> Unit)? = null
        var onLeave: ((widget: COpaquePointer) -> Unit)? = null
        var onKeyPress: ((widget: COpaquePointer, info: KeyEventInfo) -> Boolean)? = null
        var onKeyRelease: ((widget: COpaquePointer, info: KeyEventInfo) -> Boolean)? = null
        var onFocusIn: ((widget: COpaquePointer) -> Unit)? = null
        var onFocusOut: ((widget: COpaquePointer) -> Unit)? = null
        var onWindowActivateChanged: ((widget: COpaquePointer, active: Boolean) -> Unit)? = null
        var onClose: ((widget: COpaquePointer) -> Boolean)? = null
        var onUserEvent: (() -> Unit)? = null
        var onNativeDestroyed: (() -> Unit)? = null
        val signalHandlers: HashMap<Int, (COpaquePointer?) -> Unit> = HashMap()
    }

    private class PeerEntry(
        val ptr: COpaquePointer,
        val originalVtable: Long,
        val isWidget: Boolean,
        val hooks: PeerHooks
    ) {
        var nativeDestroyed: Boolean = false
        val origSlots: CPointer<LongVar>
            get() = originalVtable.toCPointer<LongVar>()!!
    }

    private data class VTableCacheKey(
        val originalVtable: Long,
        val isWidget: Boolean,
        val hookMask: Int
    )

    private val lock = LinuxReentrantLock()
    private val peersByAddr = HashMap<Long, PeerEntry>(256)
    private val focusableChecksByAddr = HashMap<Long, () -> Boolean>(256)
    private val destroyedAddrs = HashSet<Long>(256)
    private val patchedVtables = HashMap<VTableCacheKey, Long>(64)

    fun registerWidgetFocusableCheck(widget: COpaquePointer?, check: () -> Boolean) {
        val addr = widget?.rawValue?.toLong() ?: return
        lock.withLock {
            focusableChecksByAddr[addr] = check
        }
    }

    fun unregisterWidgetFocusableCheck(widget: COpaquePointer?) {
        val addr = widget?.rawValue?.toLong() ?: return
        lock.withLock {
            focusableChecksByAddr.remove(addr)
        }
    }

    fun markObjectAllocated(ptr: COpaquePointer?) {
        val addr = ptr?.rawValue?.toLong() ?: return
        lock.withLock {
            destroyedAddrs.remove(addr)
        }
    }

    private var initialized = false
    private var nextSlotId = FIRST_CUSTOM_SLOT_ID
    private var sharedSignalProxyPtr: COpaquePointer? = null

    // Discovered Qt6 QObject slot indices
    private var objSlotQtMetacall: Int = 2
    private var objSlotD1: Int = 3
    private var objSlotD0: Int = 4
    private var objSlotEvent: Int = 5

    // Discovered Qt6 QWidget primary vtable slot indices
    private var wSlotQtMetacall: Int = 2
    private var wSlotD1: Int = 3
    private var wSlotD0: Int = 4
    private var wSlotEvent: Int = 5
    private var wSlotDevType: Int = 12
    private var wSlotSizeHint: Int = 14
    private var wSlotMinSizeHint: Int = 15
    private var wSlotMousePress: Int = 19
    private var wSlotMouseRelease: Int = 20
    private var wSlotMouseDblClick: Int = 21
    private var wSlotMouseMove: Int = 22
    private var wSlotWheel: Int = 23
    private var wSlotKeyPress: Int = 24
    private var wSlotKeyRelease: Int = 25
    private var wSlotFocusIn: Int = 26
    private var wSlotFocusOut: Int = 27
    private var wSlotEnter: Int = 28
    private var wSlotLeave: Int = 29
    private var wSlotPaint: Int = 30
    private var wSlotResize: Int = 32
    private var wSlotClose: Int = 33

    var verifiedPaintDeviceOffset: Long = 16L
        private set

    fun ensureInitialized(): Boolean = lock.withLock {
        if (initialized) return true
        KdeDynamicBindings.ensureQApplication()

        val probeObj = KdeDynamicBindings.createQObject(null)
        val probeWidget = KdeDynamicBindings.createQWidget(null, 0)
        try {
            val objVptr = probeObj.reinterpret<LongVar>()[0]
            val objTable = objVptr.toCPointer<LongVar>()!!
            objSlotQtMetacall = findSlot(objTable, 12, KdeDynamicBindings.symQObjectQtMetacall, 2)
            objSlotD1 = findSlot(objTable, 12, KdeDynamicBindings.symQObjectD1, 3)
            objSlotD0 = findSlot(objTable, 12, KdeDynamicBindings.symQObjectD0, 4)
            objSlotEvent = findSlot(objTable, 12, KdeDynamicBindings.symQObjectEvent, 5)

            val wVptr = probeWidget.reinterpret<LongVar>()[0]
            val wTable = wVptr.toCPointer<LongVar>()!!
            wSlotQtMetacall = findSlot(wTable, 55, KdeDynamicBindings.symQWidgetQtMetacall, 2)
            wSlotD1 = findSlot(wTable, 55, KdeDynamicBindings.symQWidgetD1, 3)
            wSlotD0 = findSlot(wTable, 55, KdeDynamicBindings.symQWidgetD0, 4)
            wSlotEvent = findSlot(wTable, 55, KdeDynamicBindings.symQWidgetEvent, 5)
            wSlotDevType = findSlot(wTable, 55, KdeDynamicBindings.symQWidgetDevType, 12)
            wSlotSizeHint = findSlot(wTable, 55, KdeDynamicBindings.symQWidgetSizeHint, 14)
            wSlotMinSizeHint = findSlot(wTable, 55, KdeDynamicBindings.symQWidgetMinSizeHint, 15)
            wSlotMousePress = findSlot(wTable, 55, KdeDynamicBindings.symQWidgetMousePress, 19)
            wSlotMouseRelease = findSlot(wTable, 55, KdeDynamicBindings.symQWidgetMouseRelease, 20)
            wSlotMouseDblClick = findSlot(wTable, 55, KdeDynamicBindings.symQWidgetMouseDoubleClick, 21)
            wSlotMouseMove = findSlot(wTable, 55, KdeDynamicBindings.symQWidgetMouseMove, 22)
            wSlotWheel = findSlot(wTable, 55, KdeDynamicBindings.symQWidgetWheel, 23)
            wSlotKeyPress = findSlot(wTable, 55, KdeDynamicBindings.symQWidgetKeyPress, 24)
            wSlotKeyRelease = findSlot(wTable, 55, KdeDynamicBindings.symQWidgetKeyRelease, 25)
            wSlotFocusIn = findSlot(wTable, 55, KdeDynamicBindings.symQWidgetFocusIn, 26)
            wSlotFocusOut = findSlot(wTable, 55, KdeDynamicBindings.symQWidgetFocusOut, 27)
            wSlotEnter = findSlot(wTable, 55, KdeDynamicBindings.symQWidgetEnter, 28)
            wSlotLeave = findSlot(wTable, 55, KdeDynamicBindings.symQWidgetLeave, 29)
            wSlotPaint = findSlot(wTable, 55, KdeDynamicBindings.symQWidgetPaintEvent, 30)
            wSlotResize = findSlot(wTable, 55, KdeDynamicBindings.symQWidgetResizeEvent, 32)
            wSlotClose = findSlot(wTable, 55, KdeDynamicBindings.symQWidgetClose, 33)

            // Verify QPaintDevice subobject offset (+16 on LP64) using devType() == 1
            val pd = KdeDynamicBindings.paintDevicePtr(probeWidget)
            val pdVptr = pd.reinterpret<LongVar>()[0]
            check(pdVptr != 0L) { "Probe QWidget QPaintDevice subobject vptr missing at self + 16." }
            val pdDevType = KdeDynamicBindings.paintDeviceDevType(pd)
            check(pdDevType == 1) { "Expected QPaintDevice::devType() == 1 at self + 16, got $pdDevType" }
            val devTypePrimaryFn = wTable[wSlotDevType].toCPointer<CFunction<(COpaquePointer) -> Int>>()
            val primaryDevType = devTypePrimaryFn?.invoke(probeWidget) ?: -1
            check(primaryDevType == 1) { "Expected QWidget::devType() == 1, got $primaryDevType" }
            verifiedPaintDeviceOffset = 16L
        } finally {
            // Destroy probes using raw original D1 + free
            destroyRawUntracked(probeWidget, isWidget = true)
            destroyRawUntracked(probeObj, isWidget = false)
        }

        initialized = true
        true
    }

    private fun destroyRawUntracked(ptr: COpaquePointer, isWidget: Boolean) {
        val vptr = ptr.reinterpret<LongVar>()[0]
        if (vptr != 0L) {
            val table = vptr.toCPointer<LongVar>()!!
            val d1Slot = if (isWidget) wSlotD1 else objSlotD1
            val d1Fn = table[d1Slot].toCPointer<CFunction<(COpaquePointer) -> Unit>>()
            d1Fn?.invoke(ptr)
        }
        KdeDynamicBindings.freeRawObject(ptr)
    }

    private fun findSlot(table: CPointer<LongVar>, maxSlot: Int, targetSym: COpaquePointer?, fallbackSlot: Int): Int {
        if (targetSym != null) {
            val targetAddr = targetSym.rawValue.toLong()
            for (i in 0 until maxSlot) {
                if (table[i] == targetAddr) return i
            }
        }
        return fallbackSlot
    }

    fun attachWidgetHooks(widget: COpaquePointer, hookMask: Int, configure: PeerHooks.() -> Unit): PeerHooks {
        ensureInitialized()
        return attachInternal(widget, isWidget = true, hookMask = hookMask or HOOK_LIFECYCLE, configure = configure)
    }

    fun attachObjectHooks(qObj: COpaquePointer, configure: PeerHooks.() -> Unit): PeerHooks {
        ensureInitialized()
        return attachInternal(qObj, isWidget = false, hookMask = HOOK_LIFECYCLE, configure = configure)
    }

    private fun attachInternal(
        ptr: COpaquePointer,
        isWidget: Boolean,
        hookMask: Int,
        configure: PeerHooks.() -> Unit
    ): PeerHooks = lock.withLock {
        val addr = ptr.rawValue.toLong()
        val existing = peersByAddr[addr]
        val entry = if (existing != null && !existing.nativeDestroyed) {
            existing.hooks.configure()
            existing
        } else {
            val origVptr = ptr.reinterpret<LongVar>()[0]
            val hooks = PeerHooks().apply(configure)
            val created = PeerEntry(ptr, origVptr, isWidget, hooks)
            peersByAddr[addr] = created
            created
        }

        val patchedPtr = getOrBuildPatchedVtable(entry.originalVtable, isWidget, hookMask)
        ptr.reinterpret<LongVar>()[0] = patchedPtr
        entry.hooks
    }

    private fun getOrBuildPatchedVtable(originalVtable: Long, isWidget: Boolean, hookMask: Int): Long {
        val key = VTableCacheKey(originalVtable, isWidget, hookMask)
        patchedVtables[key]?.let { return it }

        // Itanium C++ ABI stores [offsetToTop, typeinfo*, virtualSlots...] and vptr points to slots[0] (+2 Longs)
        val rawSrc = (originalVtable - 16L).toCPointer<LongVar>()!!
        val copy = nativeHeap.allocArray<LongVar>(VTABLE_COPY_SLOTS + 2)
        for (i in 0 until (VTABLE_COPY_SLOTS + 2)) {
            copy[i] = rawSrc[i]
        }
        val slots = (copy.rawValue.toLong() + 16L).toCPointer<LongVar>()!!

        if (isWidget) {
            slots[wSlotQtMetacall] = fnTrampolineQtMetacall.rawValue.toLong()
            slots[wSlotD1] = fnTrampolineD1.rawValue.toLong()
            slots[wSlotD0] = fnTrampolineD0.rawValue.toLong()
            if ((hookMask and HOOK_PAINT) != 0) {
                slots[wSlotPaint] = fnTrampolinePaint.rawValue.toLong()
            }
            if ((hookMask and HOOK_RESIZE) != 0) {
                slots[wSlotResize] = fnTrampolineResize.rawValue.toLong()
            }
            if ((hookMask and HOOK_SIZE_HINT) != 0) {
                slots[wSlotSizeHint] = fnTrampolineSizeHint.rawValue.toLong()
                slots[wSlotMinSizeHint] = fnTrampolineMinSizeHint.rawValue.toLong()
            }
            if ((hookMask and HOOK_INPUT) != 0) {
                slots[wSlotMousePress] = fnTrampolineMousePress.rawValue.toLong()
                slots[wSlotMouseRelease] = fnTrampolineMouseRelease.rawValue.toLong()
                slots[wSlotMouseDblClick] = fnTrampolineMouseDblClick.rawValue.toLong()
                slots[wSlotMouseMove] = fnTrampolineMouseMove.rawValue.toLong()
                slots[wSlotWheel] = fnTrampolineWheel.rawValue.toLong()
                slots[wSlotKeyPress] = fnTrampolineKeyPress.rawValue.toLong()
                slots[wSlotKeyRelease] = fnTrampolineKeyRelease.rawValue.toLong()
                slots[wSlotFocusIn] = fnTrampolineFocusIn.rawValue.toLong()
                slots[wSlotFocusOut] = fnTrampolineFocusOut.rawValue.toLong()
                slots[wSlotEnter] = fnTrampolineEnter.rawValue.toLong()
                slots[wSlotLeave] = fnTrampolineLeave.rawValue.toLong()
            }
            if ((hookMask and HOOK_CLOSE) != 0) {
                slots[wSlotClose] = fnTrampolineClose.rawValue.toLong()
            }
            if ((hookMask and HOOK_WINDOW_EVENT) != 0) {
                slots[wSlotEvent] = fnTrampolineWidgetEvent.rawValue.toLong()
            }
        } else {
            slots[objSlotQtMetacall] = fnTrampolineQtMetacall.rawValue.toLong()
            slots[objSlotD1] = fnTrampolineD1.rawValue.toLong()
            slots[objSlotD0] = fnTrampolineD0.rawValue.toLong()
            slots[objSlotEvent] = fnTrampolineObjEvent.rawValue.toLong()
        }

        val patchedAddr = slots.rawValue.toLong()
        patchedVtables[key] = patchedAddr
        return patchedAddr
    }

    /**
     * Connects a Qt/KF6 meta-object signal on [senderPtr] using `QMetaObject::connect` into an
     * internal `QObject` receiver proxy routed by unique `slotId >= 100`.
     */
    fun connectSignal(
        senderPtr: COpaquePointer,
        signalSignature: String,
        handler: (args: COpaquePointer?) -> Unit
    ): Boolean {
        ensureInitialized()
        val sigIdx = KdeDynamicBindings.indexOfSignal(senderPtr, signalSignature)
        if (sigIdx < 0) return false

        val receiverAndSlot = lock.withLock {
            val proxy = ensureSharedSignalProxy()
            val slotId = nextSlotId++
            val entry = peersByAddr[proxy.rawValue.toLong()] ?: return false
            entry.hooks.signalHandlers[slotId] = handler
            proxy to slotId
        }
        return KdeDynamicBindings.connectSignal(senderPtr, sigIdx, receiverAndSlot.first, receiverAndSlot.second)
    }

    /** Connects the first available signal name from [candidateSignatures]. */
    fun connectFirstSignal(
        senderPtr: COpaquePointer,
        vararg candidateSignatures: String,
        handler: (args: COpaquePointer?) -> Unit
    ): Boolean {
        for (sig in candidateSignatures) {
            if (connectSignal(senderPtr, sig, handler)) return true
        }
        return false
    }

    private fun ensureSharedSignalProxy(): COpaquePointer {
        val current = sharedSignalProxyPtr
        if (current != null && peersByAddr[current.rawValue.toLong()]?.nativeDestroyed == false) {
            return current
        }
        val proxy = KdeDynamicBindings.createQObject(null)
        attachObjectHooks(proxy) {}
        sharedSignalProxyPtr = proxy
        return proxy
    }

    /** Safely destroys a tracked Qt/KF6 object if Qt has not already deleted it via a parent. */
    fun destroyObject(ptr: COpaquePointer?) {
        if (ptr == null) return
        val addr = ptr.rawValue.toLong()
        val entry = lock.withLock {
            if (addr in destroyedAddrs) return
            destroyedAddrs.add(addr)
            peersByAddr[addr]
        }
        if (entry != null && entry.nativeDestroyed) return

        if (entry != null) {
            val d1Slot = if (entry.isWidget) wSlotD1 else objSlotD1
            val d1Fn = entry.origSlots[d1Slot].toCPointer<CFunction<(COpaquePointer) -> Unit>>()
            lock.withLock {
                entry.nativeDestroyed = true
                peersByAddr.remove(addr)
            }
            ptr.reinterpret<LongVar>()[0] = entry.originalVtable
            entry.hooks.onNativeDestroyed?.invoke()
            d1Fn?.invoke(ptr)
            KdeDynamicBindings.freeRawObject(ptr)
        } else {
            // Untracked raw object (e.g. direct child already unhooked)
            val vptr = ptr.reinterpret<LongVar>()[0]
            if (vptr != 0L) {
                val table = vptr.toCPointer<LongVar>()!!
                val d1Fn = table[objSlotD1].toCPointer<CFunction<(COpaquePointer) -> Unit>>()
                d1Fn?.invoke(ptr)
            }
            KdeDynamicBindings.freeRawObject(ptr)
        }
    }

    fun isPeerAlive(ptr: COpaquePointer?): Boolean {
        if (ptr == null) return false
        val addr = ptr.rawValue.toLong()
        return lock.withLock {
            if (addr in destroyedAddrs) {
                false
            } else {
                val e = peersByAddr[addr]
                e == null || !e.nativeDestroyed
            }
        }
    }

    // --- Trampoline Handlers ---
    private fun findEntry(self: COpaquePointer): PeerEntry? = lock.withLock {
        peersByAddr[self.rawValue.toLong()]?.takeIf { !it.nativeDestroyed }
    }

    fun readSignalArgPtr(args: COpaquePointer?, argIndex: Int): COpaquePointer? {
        if (args == null) return null
        val arr = args.reinterpret<LongVar>()
        val ptrLong = arr[argIndex]
        if (ptrLong == 0L) return null
        return ptrLong.toCPointer<kotlinx.cinterop.ByteVar>()
    }

    fun readSignalArgInt(args: COpaquePointer?, argIndex: Int): Int? {
        val p = readSignalArgPtr(args, argIndex) ?: return null
        return p.reinterpret<IntVar>().pointed.value
    }

    fun readSignalArgLong(args: COpaquePointer?, argIndex: Int): Long? {
        val p = readSignalArgPtr(args, argIndex) ?: return null
        return p.reinterpret<LongVar>().pointed.value
    }

    fun readSignalArgDouble(args: COpaquePointer?, argIndex: Int): Double? {
        val p = readSignalArgPtr(args, argIndex) ?: return null
        return p.reinterpret<DoubleVar>().pointed.value
    }

    fun readSignalArgBool(args: COpaquePointer?, argIndex: Int): Boolean? {
        val p = readSignalArgPtr(args, argIndex) ?: return null
        return p.reinterpret<kotlinx.cinterop.ByteVar>().pointed.value != 0.toByte()
    }

    private fun extractMouseEvent(widget: COpaquePointer, eventPtr: COpaquePointer): MouseEventInfo {
        val ints = eventPtr.reinterpret<IntVar>()
        val longs = eventPtr.reinterpret<LongVar>()
        val mods = ints[8] // offset +32
        val button = ints[16] // offset +64
        val buttonsMask = ints[17] // offset +68

        var localX = 0f
        var localY = 0f
        var globalX = 0f
        var globalY = 0f
        var resolvedFromEvent = false

        val pointsPtrLong = longs[6] // offset +48: m_points.ptr
        val pointsSize = longs[7].toInt() // offset +56: m_points.size
        if (pointsPtrLong != 0L && pointsSize in 1..16) {
            val epPrivLong = pointsPtrLong.toCPointer<LongVar>()?.get(0) ?: 0L
            if (epPrivLong != 0L) {
                val epDoubles = epPrivLong.toCPointer<DoubleVar>()
                if (epDoubles != null) {
                    val lx = epDoubles[6] // +48
                    val ly = epDoubles[7] // +56
                    val gx = epDoubles[10] // +80
                    val gy = epDoubles[11] // +88
                    if (lx.isFinite() && ly.isFinite() && lx in -1e6..1e6 && ly in -1e6..1e6) {
                        localX = lx.toFloat()
                        localY = ly.toFloat()
                        globalX = if (gx.isFinite() && gx in -1e6..1e6) gx.toFloat() else lx.toFloat()
                        globalY = if (gy.isFinite() && gy in -1e6..1e6) gy.toFloat() else ly.toFloat()
                        resolvedFromEvent = true
                    }
                }
            }
        }
        if (!resolvedFromEvent) {
            val gPos = KdeDynamicBindings.globalCursorPos()
            if (gPos != null) {
                globalX = gPos.first.toFloat()
                globalY = gPos.second.toFloat()
                val lPos = KdeDynamicBindings.mapFromGlobal(widget, gPos.first, gPos.second)
                localX = lPos.first.toFloat()
                localY = lPos.second.toFloat()
            }
        }
        return MouseEventInfo(localX, localY, globalX, globalY, button, buttonsMask, mods)
    }

    private fun extractWheelEvent(widget: COpaquePointer, eventPtr: COpaquePointer): WheelEventInfo {
        val m = extractMouseEvent(widget, eventPtr)
        val ints = eventPtr.reinterpret<IntVar>()
        val pixelDx = ints[20] // +80
        val pixelDy = ints[21] // +84
        val angleDx = ints[22] // +88
        val angleDy = ints[23] // +92
        val dx = if (pixelDx != 0) pixelDx.toFloat() else (angleDx / 8f)
        val dy = if (pixelDy != 0) pixelDy.toFloat() else (angleDy / 8f)
        return WheelEventInfo(m.localX, m.localY, dx, dy, m.modifiers)
    }

    private fun extractKeyEvent(eventPtr: COpaquePointer): KeyEventInfo {
        val ints = eventPtr.reinterpret<IntVar>()
        val mods = ints[8] // +32
        val qtKey = ints[16] // +64
        val qStrPtr = (eventPtr.rawValue.toLong() + 40L).toCPointer<kotlinx.cinterop.ByteVar>()
        val text = KdeDynamicBindings.readBorrowedQString(qStrPtr)
        return KeyEventInfo(qtKey, text, mods)
    }

    private val fnTrampolineQtMetacall = staticCFunction { self: COpaquePointer, call: Int, id: Int, args: COpaquePointer? ->
        val entry = findEntry(self) ?: return@staticCFunction id
        if (call == 0 /*InvokeMetaMethod*/ && id >= FIRST_CUSTOM_SLOT_ID) {
            val handler = lock.withLock { entry.hooks.signalHandlers[id] }
            handler?.invoke(args)
            return@staticCFunction -1
        }
        val slot = if (entry.isWidget) wSlotQtMetacall else objSlotQtMetacall
        val orig = entry.origSlots[slot].toCPointer<CFunction<(COpaquePointer, Int, Int, COpaquePointer?) -> Int>>()
        orig?.invoke(self, call, id, args) ?: -1
    }

    private val fnTrampolineD1 = staticCFunction { self: COpaquePointer ->
        val addr = self.rawValue.toLong()
        val entry = lock.withLock {
            destroyedAddrs.add(addr)
            focusableChecksByAddr.remove(addr)
            val e = peersByAddr.remove(addr)
            e?.nativeDestroyed = true
            e
        } ?: return@staticCFunction
        val d1Slot = if (entry.isWidget) wSlotD1 else objSlotD1
        val orig = entry.origSlots[d1Slot].toCPointer<CFunction<(COpaquePointer) -> Unit>>()
        self.reinterpret<LongVar>()[0] = entry.originalVtable
        entry.hooks.onNativeDestroyed?.invoke()
        orig?.invoke(self)
        Unit
    }

    private val fnTrampolineD0 = staticCFunction { self: COpaquePointer ->
        val addr = self.rawValue.toLong()
        val entry = lock.withLock {
            destroyedAddrs.add(addr)
            focusableChecksByAddr.remove(addr)
            val e = peersByAddr.remove(addr)
            e?.nativeDestroyed = true
            e
        } ?: return@staticCFunction
        val d0Slot = if (entry.isWidget) wSlotD0 else objSlotD0
        val orig = entry.origSlots[d0Slot].toCPointer<CFunction<(COpaquePointer) -> Unit>>()
        self.reinterpret<LongVar>()[0] = entry.originalVtable
        entry.hooks.onNativeDestroyed?.invoke()
        orig?.invoke(self)
        Unit
    }

    private val fnTrampolinePaint = staticCFunction { self: COpaquePointer, eventPtr: COpaquePointer ->
        val entry = findEntry(self) ?: return@staticCFunction
        val paintCb = entry.hooks.onPaint
        if (paintCb == null) {
            val orig = entry.origSlots[wSlotPaint].toCPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>()
            orig?.invoke(self, eventPtr)
            return@staticCFunction
        }
        val w = KdeDynamicBindings.getWidgetWidth(self).coerceAtLeast(1)
        val h = KdeDynamicBindings.getWidgetHeight(self).coerceAtLeast(1)
        val pd = KdeDynamicBindings.paintDevicePtr(self)
        KdeDynamicBindings.withQPainter(pd) { painter ->
            paintCb.invoke(self, painter, w, h)
        }
    }

    private val fnTrampolineResize = staticCFunction { self: COpaquePointer, eventPtr: COpaquePointer ->
        val entry = findEntry(self) ?: return@staticCFunction
        val orig = entry.origSlots[wSlotResize].toCPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>()
        orig?.invoke(self, eventPtr)
        val ints = eventPtr.reinterpret<IntVar>()
        val w = ints[4] // offset +16
        val h = ints[5] // offset +20
        entry.hooks.onResize?.invoke(self, w, h)
    }

    private val fnTrampolineSizeHint = staticCFunction { self: COpaquePointer ->
        val entry = findEntry(self)
        val hinted = entry?.hooks?.onSizeHint?.invoke(self)
        if (hinted != null) {
            return@staticCFunction (hinted.first.toLong() and 0xFFFFFFFFL) or (hinted.second.toLong() shl 32)
        }
        val orig = entry?.let { it.origSlots[wSlotSizeHint].toCPointer<CFunction<(COpaquePointer) -> Long>>() }
        orig?.invoke(self) ?: ((100L) or (32L shl 32))
    }

    private val fnTrampolineMinSizeHint = staticCFunction { self: COpaquePointer ->
        val entry = findEntry(self)
        val hinted = entry?.hooks?.onMinimumSizeHint?.invoke(self)
        if (hinted != null) {
            return@staticCFunction (hinted.first.toLong() and 0xFFFFFFFFL) or (hinted.second.toLong() shl 32)
        }
        val orig = entry?.let { it.origSlots[wSlotMinSizeHint].toCPointer<CFunction<(COpaquePointer) -> Long>>() }
        orig?.invoke(self) ?: 0L
    }

    private val fnTrampolineMousePress = staticCFunction { self: COpaquePointer, eventPtr: COpaquePointer ->
        val entry = findEntry(self) ?: return@staticCFunction
        val consumed = entry.hooks.onMousePress?.invoke(self, extractMouseEvent(self, eventPtr)) == true
        if (!consumed) {
            val orig = entry.origSlots[wSlotMousePress].toCPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>()
            orig?.invoke(self, eventPtr)
        }
    }

    private val fnTrampolineMouseRelease = staticCFunction { self: COpaquePointer, eventPtr: COpaquePointer ->
        val entry = findEntry(self) ?: return@staticCFunction
        val consumed = entry.hooks.onMouseRelease?.invoke(self, extractMouseEvent(self, eventPtr)) == true
        if (!consumed) {
            val orig = entry.origSlots[wSlotMouseRelease].toCPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>()
            orig?.invoke(self, eventPtr)
        }
    }

    private val fnTrampolineMouseDblClick = staticCFunction { self: COpaquePointer, eventPtr: COpaquePointer ->
        val entry = findEntry(self) ?: return@staticCFunction
        val consumed = entry.hooks.onMouseDoubleClick?.invoke(self, extractMouseEvent(self, eventPtr)) == true
        if (!consumed) {
            val orig = entry.origSlots[wSlotMouseDblClick].toCPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>()
            orig?.invoke(self, eventPtr)
        }
    }

    private val fnTrampolineMouseMove = staticCFunction { self: COpaquePointer, eventPtr: COpaquePointer ->
        val entry = findEntry(self) ?: return@staticCFunction
        val consumed = entry.hooks.onMouseMove?.invoke(self, extractMouseEvent(self, eventPtr)) == true
        if (!consumed) {
            val orig = entry.origSlots[wSlotMouseMove].toCPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>()
            orig?.invoke(self, eventPtr)
        }
    }

    private val fnTrampolineWheel = staticCFunction { self: COpaquePointer, eventPtr: COpaquePointer ->
        val entry = findEntry(self) ?: return@staticCFunction
        val consumed = entry.hooks.onWheel?.invoke(self, extractWheelEvent(self, eventPtr)) == true
        if (!consumed) {
            val orig = entry.origSlots[wSlotWheel].toCPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>()
            orig?.invoke(self, eventPtr)
        }
    }

    private val fnTrampolineKeyPress = staticCFunction { self: COpaquePointer, eventPtr: COpaquePointer ->
        val entry = findEntry(self) ?: return@staticCFunction
        val consumed = entry.hooks.onKeyPress?.invoke(self, extractKeyEvent(eventPtr)) == true
        if (!consumed) {
            val orig = entry.origSlots[wSlotKeyPress].toCPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>()
            orig?.invoke(self, eventPtr)
        }
    }

    private val fnTrampolineKeyRelease = staticCFunction { self: COpaquePointer, eventPtr: COpaquePointer ->
        val entry = findEntry(self) ?: return@staticCFunction
        val consumed = entry.hooks.onKeyRelease?.invoke(self, extractKeyEvent(eventPtr)) == true
        if (!consumed) {
            val orig = entry.origSlots[wSlotKeyRelease].toCPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>()
            orig?.invoke(self, eventPtr)
        }
    }

    private val fnTrampolineFocusIn = staticCFunction { self: COpaquePointer, eventPtr: COpaquePointer ->
        val entry = findEntry(self) ?: return@staticCFunction
        val check = lock.withLock { focusableChecksByAddr[self.rawValue.toLong()] }
        if (check != null && !check()) {
            KdeDynamicBindings.setWidgetFocus(self, false)
            return@staticCFunction
        }
        val orig = entry.origSlots[wSlotFocusIn].toCPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>()
        orig?.invoke(self, eventPtr)
        entry.hooks.onFocusIn?.invoke(self)
    }

    private val fnTrampolineFocusOut = staticCFunction { self: COpaquePointer, eventPtr: COpaquePointer ->
        val entry = findEntry(self) ?: return@staticCFunction
        val orig = entry.origSlots[wSlotFocusOut].toCPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>()
        orig?.invoke(self, eventPtr)
        entry.hooks.onFocusOut?.invoke(self)
    }

    private val fnTrampolineEnter = staticCFunction { self: COpaquePointer, eventPtr: COpaquePointer ->
        val entry = findEntry(self) ?: return@staticCFunction
        val orig = entry.origSlots[wSlotEnter].toCPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>()
        orig?.invoke(self, eventPtr)
        entry.hooks.onEnter?.invoke(self)
    }

    private val fnTrampolineLeave = staticCFunction { self: COpaquePointer, eventPtr: COpaquePointer ->
        val entry = findEntry(self) ?: return@staticCFunction
        val orig = entry.origSlots[wSlotLeave].toCPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>()
        orig?.invoke(self, eventPtr)
        entry.hooks.onLeave?.invoke(self)
    }

    private val fnTrampolineWidgetEvent = staticCFunction { self: COpaquePointer, eventPtr: COpaquePointer ->
        val entry = findEntry(self) ?: return@staticCFunction 0.toByte()
        val evType = eventPtr.reinterpret<UShortVar>()[4].toInt() // offset +8
        if (evType == 24 /* QEvent::WindowActivate */) {
            entry.hooks.onWindowActivateChanged?.invoke(self, true)
        } else if (evType == 25 /* QEvent::WindowDeactivate */) {
            entry.hooks.onWindowActivateChanged?.invoke(self, false)
        }
        val orig = entry.origSlots[wSlotEvent].toCPointer<CFunction<(COpaquePointer, COpaquePointer) -> Byte>>()
        orig?.invoke(self, eventPtr) ?: 0.toByte()
    }

    private val fnTrampolineObjEvent = staticCFunction { self: COpaquePointer, eventPtr: COpaquePointer ->
        val entry = findEntry(self) ?: return@staticCFunction 0.toByte()
        val evType = eventPtr.reinterpret<UShortVar>()[4].toInt() // offset +8
        if (evType >= 1000) {
            entry.hooks.onUserEvent?.invoke()
            return@staticCFunction 1.toByte()
        }
        val orig = entry.origSlots[objSlotEvent].toCPointer<CFunction<(COpaquePointer, COpaquePointer) -> Byte>>()
        orig?.invoke(self, eventPtr) ?: 0.toByte()
    }

    private val fnTrampolineClose = staticCFunction { self: COpaquePointer, eventPtr: COpaquePointer ->
        val entry = findEntry(self) ?: return@staticCFunction
        val allowClose = entry.hooks.onClose?.invoke(self) ?: true
        if (allowClose) {
            val orig = entry.origSlots[wSlotClose].toCPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>()
            orig?.invoke(self, eventPtr)
        } else {
            // QEvent::m_accept bitfield flag in Qt6: clear accept byte at offset +12 (uint16 flags: m_accept bit 0)
            val shorts = eventPtr.reinterpret<kotlinx.cinterop.UShortVar>()
            val flags = shorts[6].toInt() // offset +12
            shorts[6] = (flags and 0xFFFE).toUShort()
        }
    }
}
