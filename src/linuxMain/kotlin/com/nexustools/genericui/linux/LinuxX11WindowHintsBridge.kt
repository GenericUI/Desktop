@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.linux

import com.nexustools.genericui.geometry.Rect
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.getenv
import kotlin.math.roundToInt

/**
 * Dynamic `libX11.so.6` bridge for Linux X11 window manager hints:
 * - EWMH `_NET_WM_STATE` client messages (`_NET_WM_STATE_ADD` / `_NET_WM_STATE_REMOVE`) for `_NET_WM_STATE_ABOVE`
 * - ICCCM `XSetWMNormalHints` `PAspect` (`min_aspect` == `max_aspect`) for windowed client aspect-ratio constraints
 */
internal object LinuxX11WindowHintsBridge {

    const val NET_WM_STATE_REMOVE: Long = 0L
    const val NET_WM_STATE_ADD: Long = 1L
    const val CLIENT_MESSAGE: Int = 33
    const val SUBSTRUCTURE_NOTIFY_MASK: Long = 1L shl 19
    const val SUBSTRUCTURE_REDIRECT_MASK: Long = 1L shl 20
    const val P_ASPECT: Long = 1L shl 7
    const val DEFAULT_ASPECT_SCALE: Int = 10000

    private val lock = LinuxReentrantLock()
    private var librariesLoaded = false
    private var x11Available = false

    private var fnXOpenDisplay: CPointer<CFunction<(CPointer<ByteVar>?) -> COpaquePointer?>>? = null
    private var fnXCloseDisplay: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnXInternAtom: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?, Int) -> ULong>>? = null
    private var fnXDefaultRootWindow: CPointer<CFunction<(COpaquePointer?) -> ULong>>? = null
    private var fnXSendEvent: CPointer<CFunction<(COpaquePointer?, ULong, Int, Long, COpaquePointer?) -> Int>>? = null
    private var fnXAllocSizeHints: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnXGetWMNormalHints: CPointer<CFunction<(COpaquePointer?, ULong, COpaquePointer?, CPointer<LongVar>?) -> Int>>? = null
    private var fnXSetWMNormalHints: CPointer<CFunction<(COpaquePointer?, ULong, COpaquePointer?) -> Unit>>? = null
    private var fnXFree: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnXFlush: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnXDefaultScreen: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnXDisplayWidth: CPointer<CFunction<(COpaquePointer?, Int) -> Int>>? = null
    private var fnXDisplayHeight: CPointer<CFunction<(COpaquePointer?, Int) -> Int>>? = null
    private var fnXMoveWindow: CPointer<CFunction<(COpaquePointer?, ULong, Int, Int) -> Int>>? = null
    private var fnXTranslateCoordinates: CPointer<CFunction<(COpaquePointer?, ULong, ULong, Int, Int, CPointer<IntVar>?, CPointer<IntVar>?, CPointer<ULongVar>?) -> Int>>? = null
    private var fnXQueryTree: CPointer<CFunction<(COpaquePointer?, ULong, CPointer<ULongVar>?, CPointer<ULongVar>?, CPointer<CPointerVar<ULongVar>>?, CPointer<UIntVar>?) -> Int>>? = null

    fun ensureLoaded(): Boolean = lock.withLock {
        if (librariesLoaded) return x11Available
        librariesLoaded = true

        val x11 = LinuxDynamicLibraryLoader.openFirst("libX11.so.6", "libX11.so", global = true)
        if (x11 != null) {
            fnXOpenDisplay = LinuxDynamicLibraryLoader.symbol(x11, "XOpenDisplay")?.reinterpret()
            fnXCloseDisplay = LinuxDynamicLibraryLoader.symbol(x11, "XCloseDisplay")?.reinterpret()
            fnXInternAtom = LinuxDynamicLibraryLoader.symbol(x11, "XInternAtom")?.reinterpret()
            fnXDefaultRootWindow = LinuxDynamicLibraryLoader.symbol(x11, "XDefaultRootWindow")?.reinterpret()
            fnXSendEvent = LinuxDynamicLibraryLoader.symbol(x11, "XSendEvent")?.reinterpret()
            fnXAllocSizeHints = LinuxDynamicLibraryLoader.symbol(x11, "XAllocSizeHints")?.reinterpret()
            fnXGetWMNormalHints = LinuxDynamicLibraryLoader.symbol(x11, "XGetWMNormalHints")?.reinterpret()
            fnXSetWMNormalHints = LinuxDynamicLibraryLoader.symbol(x11, "XSetWMNormalHints")?.reinterpret()
            fnXFree = LinuxDynamicLibraryLoader.symbol(x11, "XFree")?.reinterpret()
            fnXFlush = LinuxDynamicLibraryLoader.symbol(x11, "XFlush")?.reinterpret()
            fnXDefaultScreen = LinuxDynamicLibraryLoader.symbol(x11, "XDefaultScreen")?.reinterpret()
            fnXDisplayWidth = LinuxDynamicLibraryLoader.symbol(x11, "XDisplayWidth")?.reinterpret()
            fnXDisplayHeight = LinuxDynamicLibraryLoader.symbol(x11, "XDisplayHeight")?.reinterpret()
            fnXMoveWindow = LinuxDynamicLibraryLoader.symbol(x11, "XMoveWindow")?.reinterpret()
            fnXTranslateCoordinates = LinuxDynamicLibraryLoader.symbol(x11, "XTranslateCoordinates")?.reinterpret()
            fnXQueryTree = LinuxDynamicLibraryLoader.symbol(x11, "XQueryTree")?.reinterpret()
        }

        x11Available = fnXOpenDisplay != null &&
            fnXCloseDisplay != null &&
            fnXInternAtom != null &&
            fnXDefaultRootWindow != null &&
            fnXSendEvent != null &&
            fnXAllocSizeHints != null &&
            fnXSetWMNormalHints != null &&
            fnXFree != null &&
            fnXFlush != null
        x11Available
    }

    fun isDisplayEnvironmentPresent(): Boolean =
        getenv("DISPLAY")?.toKString()?.isNotBlank() == true

    fun allocSizeHints(): COpaquePointer? =
        if (ensureLoaded()) fnXAllocSizeHints?.invoke() else null

    fun freeX11Object(ptr: COpaquePointer?) {
        if (ptr != null && ensureLoaded()) {
            fnXFree?.invoke(ptr)
        }
    }

    fun aspectFraction(aspectRatio: Double, scale: Int = DEFAULT_ASPECT_SCALE): Pair<Int, Int> {
        require(aspectRatio.isFinite() && aspectRatio > 0.0) {
            "Aspect ratio must be finite and positive, got $aspectRatio"
        }
        val den = scale.coerceAtLeast(1)
        val num = (aspectRatio * den.toDouble()).roundToInt().coerceAtLeast(1)
        return num to den
    }

    fun applySizeHintsAspectRatioBuffer(
        hintsPtr: COpaquePointer,
        aspectRatio: Double?,
        scale: Int = DEFAULT_ASPECT_SCALE
    ) {
        val longPtr = hintsPtr.reinterpret<LongVar>()
        val intPtr = hintsPtr.reinterpret<IntVar>()
        if (aspectRatio != null && aspectRatio.isFinite() && aspectRatio > 0.0) {
            val (num, den) = aspectFraction(aspectRatio, scale)
            longPtr[0] = longPtr[0] or P_ASPECT
            intPtr[12] = num // min_aspect.x
            intPtr[13] = den // min_aspect.y
            intPtr[14] = num // max_aspect.x
            intPtr[15] = den // max_aspect.y
        } else {
            longPtr[0] = longPtr[0] and P_ASPECT.inv()
            intPtr[12] = 0
            intPtr[13] = 0
            intPtr[14] = 0
            intPtr[15] = 0
        }
    }

    fun populateNetWmStateEventBuffer(
        eventBuf: CPointer<LongVar>,
        display: COpaquePointer?,
        windowXid: ULong,
        netWmStateAtom: ULong,
        stateAtom: ULong,
        add: Boolean
    ) {
        for (i in 0 until 24) {
            eventBuf[i] = 0L
        }
        val intPtr = eventBuf.reinterpret<IntVar>()
        intPtr[0] = CLIENT_MESSAGE
        intPtr[4] = 1 // send_event = True
        intPtr[12] = 32 // format = 32
        eventBuf[1] = 0L
        eventBuf[3] = display?.rawValue?.toLong() ?: 0L
        eventBuf[4] = windowXid.toLong()
        eventBuf[5] = netWmStateAtom.toLong()
        eventBuf[7] = if (add) NET_WM_STATE_ADD else NET_WM_STATE_REMOVE
        eventBuf[8] = stateAtom.toLong()
        eventBuf[9] = 0L
        eventBuf[10] = 1L // Source indication: regular application
        eventBuf[11] = 0L
    }

    fun setAlwaysOnTop(windowXid: ULong, alwaysOnTop: Boolean): Boolean {
        if (windowXid == 0uL || !isDisplayEnvironmentPresent() || !ensureLoaded()) return false
        val dpy = fnXOpenDisplay?.invoke(null) ?: return false
        return try {
            sendNetWmStateAbove(dpy, windowXid, alwaysOnTop)
            fnXFlush?.invoke(dpy)
            true
        } finally {
            fnXCloseDisplay?.invoke(dpy)
        }
    }

    fun setAspectRatioHint(windowXid: ULong, aspectRatio: Double?): Boolean {
        if (windowXid == 0uL || !isDisplayEnvironmentPresent() || !ensureLoaded()) return false
        val dpy = fnXOpenDisplay?.invoke(null) ?: return false
        return try {
            val ok = applyNormalAspectHints(dpy, windowXid, aspectRatio)
            if (ok) {
                fnXFlush?.invoke(dpy)
            }
            ok
        } finally {
            fnXCloseDisplay?.invoke(dpy)
        }
    }

    fun getPrimaryScreenBounds(): Rect? {
        if (!isDisplayEnvironmentPresent() || !ensureLoaded()) return null
        val dpy = fnXOpenDisplay?.invoke(null) ?: return null
        return try {
            val scr = fnXDefaultScreen?.invoke(dpy) ?: 0
            val w = fnXDisplayWidth?.invoke(dpy, scr) ?: 0
            val h = fnXDisplayHeight?.invoke(dpy, scr) ?: 0
            if (w > 0 && h > 0) Rect(0, 0, w, h) else null
        } finally {
            fnXCloseDisplay?.invoke(dpy)
        }
    }

    fun moveWindow(windowXid: ULong, x: Int, y: Int): Boolean {
        if (windowXid == 0uL || !isDisplayEnvironmentPresent() || !ensureLoaded()) return false
        val dpy = fnXOpenDisplay?.invoke(null) ?: return false
        return try {
            fnXMoveWindow?.invoke(dpy, windowXid, x, y)
            fnXFlush?.invoke(dpy)
            true
        } finally {
            fnXCloseDisplay?.invoke(dpy)
        }
    }

    fun queryWindowPosition(windowXid: ULong): Pair<Int, Int>? = memScoped {
        if (windowXid == 0uL || !isDisplayEnvironmentPresent() || !ensureLoaded()) return@memScoped null
        val dpy = fnXOpenDisplay?.invoke(null) ?: return@memScoped null
        try {
            val rootWin = fnXDefaultRootWindow?.invoke(dpy) ?: 0uL
            if (rootWin == 0uL) return@memScoped null
            var targetWin = windowXid
            val qTree = fnXQueryTree
            if (qTree != null) {
                var current = windowXid
                for (depth in 0 until 8) {
                    val outRoot = alloc<ULongVar>()
                    val outParent = alloc<ULongVar>()
                    val outChildren = alloc<CPointerVar<ULongVar>>()
                    val outCount = alloc<UIntVar>()
                    outRoot.value = 0uL
                    outParent.value = 0uL
                    outChildren.value = null
                    outCount.value = 0u
                    val ok = qTree.invoke(dpy, current, outRoot.ptr, outParent.ptr, outChildren.ptr, outCount.ptr)
                    outChildren.value?.let { fnXFree?.invoke(it) }
                    if (ok == 0) break
                    val parent = outParent.value
                    if (parent == 0uL || parent == outRoot.value) {
                        targetWin = current
                        break
                    }
                    current = parent
                    targetWin = parent
                }
            }
            val dstX = alloc<IntVar>()
            val dstY = alloc<IntVar>()
            val childRet = alloc<ULongVar>()
            dstX.value = 0
            dstY.value = 0
            childRet.value = 0uL
            val translated = fnXTranslateCoordinates?.invoke(dpy, targetWin, rootWin, 0, 0, dstX.ptr, dstY.ptr, childRet.ptr) ?: 0
            if (translated != 0) dstX.value to dstY.value else null
        } finally {
            fnXCloseDisplay?.invoke(dpy)
        }
    }

    fun syncWindowHints(windowXid: ULong, alwaysOnTop: Boolean, aspectRatio: Double?): Boolean {
        if (windowXid == 0uL || !isDisplayEnvironmentPresent() || !ensureLoaded()) return false
        val dpy = fnXOpenDisplay?.invoke(null) ?: return false
        return try {
            sendNetWmStateAbove(dpy, windowXid, alwaysOnTop)
            applyNormalAspectHints(dpy, windowXid, aspectRatio)
            fnXFlush?.invoke(dpy)
            true
        } finally {
            fnXCloseDisplay?.invoke(dpy)
        }
    }

    private fun sendNetWmStateAbove(
        display: COpaquePointer,
        windowXid: ULong,
        alwaysOnTop: Boolean
    ): Boolean = memScoped {
        val netWmState = fnXInternAtom?.invoke(display, "_NET_WM_STATE".cstr.ptr, 0) ?: 0uL
        val netWmStateAbove = fnXInternAtom?.invoke(display, "_NET_WM_STATE_ABOVE".cstr.ptr, 0) ?: 0uL
        if (netWmState == 0uL || netWmStateAbove == 0uL) return@memScoped false
        val rootWin = fnXDefaultRootWindow?.invoke(display) ?: 0uL
        if (rootWin == 0uL) return@memScoped false

        val eventBuf = allocArray<LongVar>(24)
        populateNetWmStateEventBuffer(
            eventBuf = eventBuf,
            display = display,
            windowXid = windowXid,
            netWmStateAtom = netWmState,
            stateAtom = netWmStateAbove,
            add = alwaysOnTop
        )
        val mask = SUBSTRUCTURE_REDIRECT_MASK or SUBSTRUCTURE_NOTIFY_MASK
        (fnXSendEvent?.invoke(display, rootWin, 0, mask, eventBuf) ?: 0) != 0
    }

    private fun applyNormalAspectHints(
        display: COpaquePointer,
        windowXid: ULong,
        aspectRatio: Double?
    ): Boolean {
        val hints = fnXAllocSizeHints?.invoke() ?: return false
        return try {
            memScoped {
                val supplied = alloc<LongVar>()
                supplied.value = 0L
                fnXGetWMNormalHints?.invoke(display, windowXid, hints, supplied.ptr)
            }
            applySizeHintsAspectRatioBuffer(hints, aspectRatio)
            fnXSetWMNormalHints?.invoke(display, windowXid, hints)
            true
        } finally {
            fnXFree?.invoke(hints)
        }
    }
}
