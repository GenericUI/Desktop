package com.nexustools.genericui.swing

import com.nexustools.genericui.enums.window.WindowState
import com.nexustools.genericui.geometry.Size
import java.awt.GraphicsEnvironment
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwingWindowUndecoratedAndStateTest {

    private fun runEdtAware(block: () -> Unit) {
        if (GraphicsEnvironment.isHeadless() || SwingUtilities.isEventDispatchThread()) {
            block()
        } else {
            SwingUtilities.invokeAndWait { block() }
        }
    }

    @Test
    fun undecoratedTogglesSafelyInHeadlessAndDisplayableModes() {
        runEdtAware {
            val impl = SwingWindowImpl()
            try {
                assertFalse(impl.isUndecorated())

                impl.setUndecorated(true)
                assertTrue(impl.isUndecorated())
                assertEquals(true, impl.nativeWidget.getClientProperty("undecorated"))

                val frame = impl.jFrame
                if (frame != null) {
                    assertTrue(frame.isUndecorated)
                    frame.pack()
                    assertTrue(frame.isDisplayable)

                    impl.setUndecorated(false)
                    assertFalse(impl.isUndecorated())
                    assertFalse(frame.isUndecorated)

                    impl.setUndecorated(true)
                    assertTrue(impl.isUndecorated())
                    assertTrue(frame.isUndecorated)
                }

                impl.setUndecorated(false)
                assertFalse(impl.isUndecorated())
                assertEquals(false, impl.nativeWidget.getClientProperty("undecorated"))
            } finally {
                impl.closeWindow()
            }
        }
    }

    @Test
    fun setOnWindowStateChangedFiresAndDeduplicatesStateTransitions() {
        runEdtAware {
            val impl = SwingWindowImpl()
            try {
                val observed = mutableListOf<WindowState>()
                impl.setOnWindowStateChanged { observed.add(it) }

                impl.setWindowState(WindowState.MINIMIZED)
                impl.setWindowState(WindowState.MINIMIZED)
                assertEquals(WindowState.MINIMIZED, impl.getWindowState())

                impl.setWindowState(WindowState.MAXIMIZED)
                assertEquals(WindowState.MAXIMIZED, impl.getWindowState())

                impl.setWindowState(WindowState.NORMAL)
                assertEquals(WindowState.NORMAL, impl.getWindowState())

                impl.handleHostFrameResized(
                    outerWidth = 400,
                    outerHeight = 225,
                    insetsLeft = 0,
                    insetsTop = 0,
                    insetsRight = 0,
                    insetsBottom = 0,
                    extendedState = JFrame.ICONIFIED
                )
                impl.handleHostFrameResized(
                    outerWidth = 400,
                    outerHeight = 225,
                    insetsLeft = 0,
                    insetsTop = 0,
                    insetsRight = 0,
                    insetsBottom = 0,
                    extendedState = JFrame.ICONIFIED
                )
                impl.handleHostFrameResized(
                    outerWidth = 800,
                    outerHeight = 600,
                    insetsLeft = 0,
                    insetsTop = 0,
                    insetsRight = 0,
                    insetsBottom = 0,
                    extendedState = JFrame.MAXIMIZED_BOTH
                )
                impl.handleHostFrameResized(
                    outerWidth = 640,
                    outerHeight = 360,
                    insetsLeft = 0,
                    insetsTop = 0,
                    insetsRight = 0,
                    insetsBottom = 0,
                    extendedState = JFrame.NORMAL
                )

                assertEquals(
                    listOf(
                        WindowState.MINIMIZED,
                        WindowState.MAXIMIZED,
                        WindowState.NORMAL,
                        WindowState.MINIMIZED,
                        WindowState.MAXIMIZED,
                        WindowState.NORMAL
                    ),
                    observed
                )
            } finally {
                impl.closeWindow()
            }
        }
    }

    @Test
    fun startSystemMoveIsSafeWithoutThrowingWhenUndecoratedOrHeadless() {
        runEdtAware {
            val impl = SwingWindowImpl()
            try {
                impl.setWindowSize(Size(320, 180))
                impl.setUndecorated(true)
                impl.startSystemMove()
                assertTrue(impl.isUndecorated())
            } finally {
                impl.closeWindow()
            }
        }
    }
}
