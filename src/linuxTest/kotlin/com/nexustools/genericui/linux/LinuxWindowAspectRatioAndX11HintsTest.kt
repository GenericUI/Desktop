@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.linux

import com.nexustools.genericui.enums.window.WindowState
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.gtk.GtkWindowNativeImpl
import com.nexustools.genericui.kde.KdeDynamicBindings
import com.nexustools.genericui.kde.KdeWindowNativeImpl
import com.nexustools.genericui.window.WindowAspectRatioHelper
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxWindowAspectRatioAndX11HintsTest {

    @Test
    fun x11HintsBridgeSetsAndClearsPAspectAndPopulatesNetWmStateClientMessage() = memScoped {
        val (num, den) = LinuxX11WindowHintsBridge.aspectFraction(16.0 / 9.0)
        assertEquals(17778, num)
        assertEquals(10000, den)

        val hintsBuf = allocArray<LongVar>(18)
        for (i in 0 until 18) {
            hintsBuf[i] = 0L
        }
        val hintsInts = hintsBuf.reinterpret<IntVar>()

        LinuxX11WindowHintsBridge.applySizeHintsAspectRatioBuffer(hintsBuf, 16.0 / 9.0)
        assertTrue((hintsBuf[0] and LinuxX11WindowHintsBridge.P_ASPECT) != 0L)
        assertEquals(17778, hintsInts[12])
        assertEquals(10000, hintsInts[13])
        assertEquals(17778, hintsInts[14])
        assertEquals(10000, hintsInts[15])

        LinuxX11WindowHintsBridge.applySizeHintsAspectRatioBuffer(hintsBuf, null)
        assertEquals(0L, hintsBuf[0] and LinuxX11WindowHintsBridge.P_ASPECT)
        assertEquals(0, hintsInts[12])
        assertEquals(0, hintsInts[13])
        assertEquals(0, hintsInts[14])
        assertEquals(0, hintsInts[15])

        // If libX11.so.6 is present in this Linux host environment, also verify real XAllocSizeHints/XFree round-trip
        if (LinuxX11WindowHintsBridge.ensureLoaded()) {
            val nativeHints = LinuxX11WindowHintsBridge.allocSizeHints()
            if (nativeHints != null) {
                try {
                    LinuxX11WindowHintsBridge.applySizeHintsAspectRatioBuffer(nativeHints, 16.0 / 9.0)
                    assertTrue((nativeHints.reinterpret<LongVar>()[0] and LinuxX11WindowHintsBridge.P_ASPECT) != 0L)
                    LinuxX11WindowHintsBridge.applySizeHintsAspectRatioBuffer(nativeHints, null)
                    assertEquals(0L, nativeHints.reinterpret<LongVar>()[0] and LinuxX11WindowHintsBridge.P_ASPECT)
                } finally {
                    LinuxX11WindowHintsBridge.freeX11Object(nativeHints)
                }
            }
        }

        val eventBuf = allocArray<LongVar>(24)
        val eventInts = eventBuf.reinterpret<IntVar>()
        LinuxX11WindowHintsBridge.populateNetWmStateEventBuffer(
            eventBuf = eventBuf,
            display = null,
            windowXid = 0x123456uL,
            netWmStateAtom = 300uL,
            stateAtom = 400uL,
            add = true
        )
        assertEquals(LinuxX11WindowHintsBridge.CLIENT_MESSAGE, eventInts[0])
        assertEquals(1, eventInts[4])
        assertEquals(32, eventInts[12])
        assertEquals(0x123456L, eventBuf[4])
        assertEquals(300L, eventBuf[5])
        assertEquals(LinuxX11WindowHintsBridge.NET_WM_STATE_ADD, eventBuf[7])
        assertEquals(400L, eventBuf[8])
        assertEquals(1L, eventBuf[10])

        LinuxX11WindowHintsBridge.populateNetWmStateEventBuffer(
            eventBuf = eventBuf,
            display = null,
            windowXid = 0x123456uL,
            netWmStateAtom = 300uL,
            stateAtom = 400uL,
            add = false
        )
        assertEquals(LinuxX11WindowHintsBridge.NET_WM_STATE_REMOVE, eventBuf[7])
    }

    @Test
    fun gtkWindowAlwaysOnTopAndAspectRatioClampInNormalVsUnconstrainedStates() {
        val impl = GtkWindowNativeImpl()
        val sixteenByNine = 16.0 / 9.0

        assertFalse(impl.isAlwaysOnTop())
        impl.setAlwaysOnTop(true)
        assertTrue(impl.isAlwaysOnTop())
        assertEquals(true, impl.nativeWidget.attributes["alwaysOnTop"])
        impl.setAlwaysOnTop(false)
        assertFalse(impl.isAlwaysOnTop())
        assertEquals(false, impl.nativeWidget.attributes["alwaysOnTop"])

        assertNull(impl.getAspectRatio())
        impl.setAspectRatio(sixteenByNine)
        assertEquals(sixteenByNine, impl.getAspectRatio())

        impl.setWindowSize(Size(1600, 1000))
        assertEquals(Size(1600, 900), impl.getWindowSize())

        // Simulate interactive GTK Configure resize in NORMAL state
        impl.syncHostClientSize(widthPx = 1600, heightPx = 1100, fullscreenNow = false, maximizedNow = false)
        assertEquals(WindowState.NORMAL, impl.getWindowState())
        assertTrue(WindowAspectRatioHelper.matchesAspectRatio(impl.getWindowSize(), sixteenByNine, tolerancePx = 2))

        // Maximized fills work area unconstrained
        impl.syncHostClientSize(widthPx = 1920, heightPx = 1200, fullscreenNow = false, maximizedNow = true)
        assertEquals(WindowState.MAXIMIZED, impl.getWindowState())
        assertEquals(Size(1920, 1200), impl.getWindowSize())

        // Fullscreen fills display unconstrained
        impl.syncHostClientSize(widthPx = 2560, heightPx = 1600, fullscreenNow = true, maximizedNow = false)
        assertEquals(WindowState.FULLSCREEN, impl.getWindowState())
        assertTrue(impl.isFullscreen())
        assertEquals(Size(2560, 1600), impl.getWindowSize())

        // Restore to NORMAL re-enforces aspect ratio
        impl.syncHostClientSize(widthPx = 1280, heightPx = 850, fullscreenNow = false, maximizedNow = false)
        assertEquals(WindowState.NORMAL, impl.getWindowState())
        assertTrue(WindowAspectRatioHelper.matchesAspectRatio(impl.getWindowSize(), sixteenByNine, tolerancePx = 2))

        impl.setAspectRatio(null)
        assertFailsWith<IllegalArgumentException> { impl.setAspectRatio(0.0) }
    }

    @Test
    fun kdeWindowAlwaysOnTopFlagAndAspectRatioClampInNormalVsUnconstrainedStates() {
        val impl = KdeWindowNativeImpl()
        val sixteenByNine = 16.0 / 9.0

        assertFalse(impl.isAlwaysOnTop())
        assertEquals(0, impl.nativeWidget.kdeWindowFlags and KdeDynamicBindings.WINDOW_STAYS_ON_TOP_HINT)

        impl.setAlwaysOnTop(true)
        assertTrue(impl.isAlwaysOnTop())
        assertTrue((impl.nativeWidget.kdeWindowFlags and KdeDynamicBindings.WINDOW_STAYS_ON_TOP_HINT) != 0)
        assertEquals(impl.nativeWidget.kdeWindowFlags, impl.nativeWidget.attributes["kdeWindowFlags"])

        impl.setAlwaysOnTop(false)
        assertFalse(impl.isAlwaysOnTop())
        assertEquals(0, impl.nativeWidget.kdeWindowFlags and KdeDynamicBindings.WINDOW_STAYS_ON_TOP_HINT)

        // Tool-modal owned window with alwaysOnTopOfOwner retains WINDOW_STAYS_ON_TOP_HINT
        val owner = KdeWindowNativeImpl()
        impl.configureAsToolModalWindow(
            ownerWindow = owner,
            showInTaskbar = false,
            minimizable = false,
            alwaysOnTopOfOwner = true
        )
        assertTrue((impl.nativeWidget.kdeWindowFlags and KdeDynamicBindings.WINDOW_STAYS_ON_TOP_HINT) != 0)
        impl.setAlwaysOnTop(false)
        assertTrue((impl.nativeWidget.kdeWindowFlags and KdeDynamicBindings.WINDOW_STAYS_ON_TOP_HINT) != 0)

        val main = KdeWindowNativeImpl()
        main.setAspectRatio(sixteenByNine)
        main.setWindowSize(Size(1600, 1000))
        assertEquals(Size(1600, 900), main.getWindowSize())

        main.syncHostClientSize(widthPx = 1600, heightPx = 1100, fullscreenNow = false, maximizedNow = false)
        assertEquals(WindowState.NORMAL, main.getWindowState())
        assertTrue(WindowAspectRatioHelper.matchesAspectRatio(main.getWindowSize(), sixteenByNine, tolerancePx = 2))

        main.syncHostClientSize(widthPx = 1920, heightPx = 1200, fullscreenNow = false, maximizedNow = true)
        assertEquals(WindowState.MAXIMIZED, main.getWindowState())
        assertEquals(Size(1920, 1200), main.getWindowSize())

        main.syncHostClientSize(widthPx = 2560, heightPx = 1600, fullscreenNow = true, maximizedNow = false)
        assertEquals(WindowState.FULLSCREEN, main.getWindowState())
        assertTrue(main.isFullscreen())
        assertEquals(Size(2560, 1600), main.getWindowSize())

        main.syncHostClientSize(widthPx = 1280, heightPx = 850, fullscreenNow = false, maximizedNow = false)
        assertEquals(WindowState.NORMAL, main.getWindowState())
        assertTrue(WindowAspectRatioHelper.matchesAspectRatio(main.getWindowSize(), sixteenByNine, tolerancePx = 2))

        main.setAspectRatio(null)
        assertFailsWith<IllegalArgumentException> { main.setAspectRatio(-16.0 / 9.0) }
    }

    @Test
    fun x11HintsPMinSizeAndUndecoratedGtkKdeMinWindowSizeEnforcement() = memScoped {
        val hintsBuf = allocArray<LongVar>(18)
        for (i in 0 until 18) hintsBuf[i] = 0L
        val hintsInts = hintsBuf.reinterpret<IntVar>()

        LinuxX11WindowHintsBridge.applySizeHintsMinSizeBuffer(hintsBuf, 360, 220)
        assertTrue((hintsBuf[0] and LinuxX11WindowHintsBridge.P_MIN_SIZE) != 0L)
        assertEquals(360, hintsInts[6])
        assertEquals(220, hintsInts[7])

        LinuxX11WindowHintsBridge.applySizeHintsMinSizeBuffer(hintsBuf, null, null)
        assertEquals(0L, hintsBuf[0] and LinuxX11WindowHintsBridge.P_MIN_SIZE)
        assertEquals(0, hintsInts[6])
        assertEquals(0, hintsInts[7])

        // Verify GTK4 and KDE undecorated windows enforce minWindowSize on host resize events
        // both with and without an aspectRatio lock.
        val gtkWin = GtkWindowNativeImpl()
        gtkWin.setUndecorated(true)
        gtkWin.setMinWindowSize(Size(360, 220))
        gtkWin.syncHostClientSize(widthPx = 200, heightPx = 140, fullscreenNow = false, maximizedNow = false)
        assertEquals(Size(360, 220), gtkWin.getWindowSize())

        gtkWin.setAspectRatio(16.0 / 9.0)
        gtkWin.syncHostClientSize(widthPx = 240, heightPx = 150, fullscreenNow = false, maximizedNow = false)
        assertTrue(gtkWin.getWindowSize().width >= 360)
        assertTrue(gtkWin.getWindowSize().height >= 220)
        assertTrue(WindowAspectRatioHelper.matchesAspectRatio(gtkWin.getWindowSize(), 16.0 / 9.0, tolerancePx = 2))

        val kdeWin = KdeWindowNativeImpl()
        kdeWin.setUndecorated(true)
        kdeWin.setMinWindowSize(Size(360, 220))
        kdeWin.syncHostClientSize(widthPx = 200, heightPx = 140, fullscreenNow = false, maximizedNow = false)
        assertEquals(Size(360, 220), kdeWin.getWindowSize())

        kdeWin.setAspectRatio(16.0 / 9.0)
        kdeWin.syncHostClientSize(widthPx = 240, heightPx = 150, fullscreenNow = false, maximizedNow = false)
        assertTrue(kdeWin.getWindowSize().width >= 360)
        assertTrue(kdeWin.getWindowSize().height >= 220)
        assertTrue(WindowAspectRatioHelper.matchesAspectRatio(kdeWin.getWindowSize(), 16.0 / 9.0, tolerancePx = 2))
    }
}
