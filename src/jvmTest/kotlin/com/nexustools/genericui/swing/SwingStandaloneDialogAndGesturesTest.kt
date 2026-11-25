package com.nexustools.genericui.swing

import com.nexustools.genericui.component.dialog.DialogWindow
import com.nexustools.genericui.component.layout.Container
import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.component.shell.Window
import com.nexustools.genericui.component.typography.Text
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.event.PointerEvent
import com.nexustools.genericui.geometry.Size
import java.awt.GraphicsEnvironment
import java.awt.event.MouseEvent
import java.awt.event.WindowEvent
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwingStandaloneDialogAndGesturesTest {

    private fun runEdtAware(block: () -> Unit) {
        if (GraphicsEnvironment.isHeadless() || SwingUtilities.isEventDispatchThread()) {
            block()
        } else {
            SwingUtilities.invokeAndWait { block() }
        }
    }

    @Test
    fun standaloneDialogOpensWithoutOwnerAndPropagatesCloseCallback() {
        runEdtAware {
            val factory = SwingNativeFactory()
            val prevFactory = GenericUI.activeFactory
            val prevApp = Application.current
            Application.current = null
            GenericUI.initialize(factory)
            val dialog = DialogWindow(title = "Standalone Settings")
            try {
                dialog.setContent(Container().apply { addChild(Text("Standalone Body")) })
                dialog.setWindowSize(Size(360, 220))
                var closeCount = 0
                dialog.onCloseHandler = { closeCount++ }

                dialog.open()

                assertTrue(dialog.isWindowShown)
                val dialogImpl = dialog.ensureNativeImpl() as SwingDialogWindowImpl
                assertNull(dialogImpl.ownerWindow)
                assertEquals(false, dialogImpl.nativeWidget.getClientProperty("attachedToMainWindow"))
                assertEquals(false, dialogImpl.nativeWidget.rootPane.getClientProperty("attachedToMainWindow"))
                assertEquals(true, dialogImpl.nativeWidget.rootPane.getClientProperty("isSeparateNativeWindow"))

                dialogImpl.setTitle("Renamed Standalone")
                assertEquals("Renamed Standalone", dialogImpl.getTitle())
                dialogImpl.setPreferredSize(Size(400, 260))
                dialogImpl.setBounds(40, 50, 400, 260)
                dialogImpl.bringToFront()

                val jDialog = dialogImpl.jDialog
                if (jDialog != null) {
                    assertNull(jDialog.owner, "Standalone JDialog must not attach to any owner frame")
                    assertEquals(WindowConstants.DO_NOTHING_ON_CLOSE, jDialog.defaultCloseOperation)
                    jDialog.dispatchEvent(WindowEvent(jDialog, WindowEvent.WINDOW_CLOSING))
                } else {
                    dialog.close()
                }

                assertFalse(dialog.isWindowShown)
                assertFalse(dialogImpl.isVisible())
                assertNull(dialogImpl.jDialog)
                assertEquals(1, closeCount)

                dialogImpl.closeDialog()
                assertEquals(1, closeCount, "Closing twice must not double-fire close callback")
            } finally {
                if (dialog.isWindowShown) {
                    dialog.close()
                }
                Application.current = prevApp
                GenericUI.activeFactory = prevFactory
            }
        }
    }

    @Test
    fun hiddenOwnerDialogDoesNotAttachToInvisibleOwnerAndPropagatesCloseError() {
        runEdtAware {
            val factory = SwingNativeFactory()
            val prevFactory = GenericUI.activeFactory
            val prevApp = Application.current
            Application.current = null
            GenericUI.initialize(factory)
            val hiddenOwner = Window(title = "Hidden Owner").apply {
                setContent(Container())
                hide()
            }
            val dialog = DialogWindow(title = "Owned By Hidden")
            try {
                hiddenOwner.show(factory)
                assertFalse(hiddenOwner.isVisible)

                dialog.setContent(Container())
                dialog.open(hiddenOwner)

                val dialogImpl = dialog.ensureNativeImpl() as SwingDialogWindowImpl
                val jDialog = dialogImpl.jDialog
                if (jDialog != null) {
                    assertNull(jDialog.owner, "Invisible owner frame must not own JDialog")
                }

                dialogImpl.setOnDialogClosed {
                    throw IllegalStateException("Deterministic close error propagation")
                }
                assertFailsWith<IllegalStateException> {
                    dialogImpl.closeDialog()
                }
                assertFalse(dialogImpl.isVisible())
            } finally {
                val impl = dialog.nativeImpl as? SwingDialogWindowImpl
                impl?.setOnDialogClosed(null)
                if (dialog.isWindowShown) {
                    dialog.close()
                }
                hiddenOwner.close()
                Application.current = prevApp
                GenericUI.activeFactory = prevFactory
            }
        }
    }

    @Test
    fun swingContainerDispatchesDoubleClickAndDraggedPointerMove() {
        runEdtAware {
            val factory = SwingNativeFactory()
            val prevFactory = GenericUI.activeFactory
            GenericUI.initialize(factory)
            val host = Window("Swing Gesture Host")
            try {
                val container = Container()
                host.setContent(container)
                host.show(factory)
                var clicks = 0
                var doubleClicks = 0
                val movedEvents = mutableListOf<PointerEvent>()
                container.onClick = { clicks++ }
                container.onDoubleClick = { doubleClicks++ }
                container.onPointerMove = { movedEvents.add(it) }

                val impl = container.ensureNativeImpl() as SwingBaseNativeImpl
                val comp = impl.nativeWidget
                comp.setBounds(0, 0, 400, 300)

                val now = System.currentTimeMillis()
                comp.dispatchEvent(MouseEvent(comp, MouseEvent.MOUSE_PRESSED, now, MouseEvent.BUTTON1_DOWN_MASK, 100, 80, 1, false, MouseEvent.BUTTON1))
                comp.dispatchEvent(MouseEvent(comp, MouseEvent.MOUSE_DRAGGED, now + 10, MouseEvent.BUTTON1_DOWN_MASK, 124, 96, 1, false, MouseEvent.BUTTON1))
                comp.dispatchEvent(MouseEvent(comp, MouseEvent.MOUSE_RELEASED, now + 20, 0, 124, 96, 1, false, MouseEvent.BUTTON1))
                comp.dispatchEvent(MouseEvent(comp, MouseEvent.MOUSE_CLICKED, now + 30, 0, 124, 96, 2, false, MouseEvent.BUTTON1))

                assertEquals(0, clicks, "Double click count=2 must dispatch double click instead of single click")
                assertEquals(1, doubleClicks)
                assertTrue(movedEvents.isNotEmpty(), "Dragged pointer motion must reach onPointerMove")
                assertEquals(124f, movedEvents.last().x)
                assertEquals(96f, movedEvents.last().y)
            } finally {
                if (host.isWindowShown) {
                    host.close()
                }
                GenericUI.activeFactory = prevFactory
            }
        }
    }
}
