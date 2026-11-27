@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.css.CssPointerEvents
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.windows.impl.WindowsButtonNativeImpl
import com.nexustools.genericui.windows.impl.WindowsContainerNativeImpl
import com.nexustools.genericui.windows.impl.WindowsDropdownNativeImpl
import com.nexustools.genericui.windows.impl.WindowsSelectNativeImpl
import com.nexustools.genericui.windows.impl.WindowsTextInputNativeImpl
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.toLong
import platform.windows.BN_CLICKED
import platform.windows.CB_GETITEMHEIGHT
import platform.windows.GetClassNameW
import platform.windows.GetParent
import platform.windows.GetWindowRect
import platform.windows.HTTRANSPARENT
import platform.windows.HWND
import platform.windows.RECT
import platform.windows.SendMessageW
import platform.windows.WM_COMMAND
import platform.windows.WM_NCHITTEST
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WindowsRetainedHwndControlsTest {

    private fun readWin32ClassName(hwnd: HWND): String = memScoped {
        val buf = allocArray<UShortVar>(128)
        GetClassNameW(hwnd, buf, 128)
        buf.toKString()
    }

    @Test
    fun buttonUsesNativeSystemButtonClassAndSwapsDynamicallyWithBootstrapMode() {
        val prevVisual = Win32NativeWindowHost.visualTestMode
        Win32NativeWindowHost.visualTestMode = true
        val container = WindowsContainerNativeImpl()
        val btn = WindowsButtonNativeImpl()
        try {
            assertTrue(btn.isNativeDefaultButtonMode())
            assertEquals("Button", btn.nativeWidget.actualWin32ClassName)
            val defaultHwnd = assertNotNull(btn.nativeWidget.hwnd)
            assertEquals("Button", readWin32ClassName(defaultHwnd))
            assertEquals(Win32ControlMessageRouter.getOrCreateParkingHwnd(), GetParent(defaultHwnd))

            container.addChildNative(btn)
            val containerHwnd = assertNotNull(container.nativeWidget.hwnd)
            assertEquals(containerHwnd, GetParent(defaultHwnd))

            btn.setType(BootstrapType.PRIMARY)
            assertFalse(btn.isNativeDefaultButtonMode())
            assertEquals("Button", btn.nativeWidget.actualWin32ClassName)
            val styledHwnd = assertNotNull(btn.nativeWidget.hwnd)
            assertEquals("Button", readWin32ClassName(styledHwnd))
            assertEquals(containerHwnd, GetParent(styledHwnd))

            btn.setOutline(true)
            assertEquals("Button", btn.nativeWidget.actualWin32ClassName)

            btn.setOutline(false)
            btn.setType(BootstrapType.NONE)
            assertTrue(btn.isNativeDefaultButtonMode())
            val restoredHwnd = assertNotNull(btn.nativeWidget.hwnd)
            assertEquals("Button", readWin32ClassName(restoredHwnd))
            assertEquals(containerHwnd, GetParent(restoredHwnd))
        } finally {
            btn.dispose()
            container.dispose()
            Win32NativeWindowHost.closeAllVisualWindows()
            Win32NativeWindowHost.visualTestMode = prevVisual
        }
    }

    @Test
    fun selectComboBoxAppliesDropdownHeightRuleAndItemHeight() = memScoped {
        val select = WindowsSelectNativeImpl()
        try {
            select.setOptions(listOf("alpha" to "Alpha", "beta" to "Beta", "gamma" to "Gamma"))
            select.setSelectedValue("beta")
            select.setBounds(Rect(12, 14, 180, 32))

            val hwnd = assertNotNull(select.nativeWidget.hwnd)
            assertEquals("ComboBox", readWin32ClassName(hwnd))
            val r = alloc<RECT>()
            assertTrue(
                SendMessageW(hwnd, 0x0152u /* CB_GETDROPPEDCONTROLRECT */, 0uL, r.ptr.toLong()) != 0L,
                "CB_GETDROPPEDCONTROLRECT should succeed on ComboBox HWND"
            )
            assertTrue((r.bottom - r.top) >= 260, "ComboBox dropdown rect height must accommodate dropdown list (got ${r.bottom - r.top})")

            val itemH = SendMessageW(hwnd, CB_GETITEMHEIGHT.toUInt(), (-1L).toULong(), 0L).toInt()
            assertEquals(26, itemH)
        } finally {
            select.dispose()
        }
    }

    @Test
    fun preMountAttachHitTestTransparentOverlayAndSyntheticClickForwardingWork() {
        val prevVisual = Win32NativeWindowHost.visualTestMode
        Win32NativeWindowHost.visualTestMode = true
        val win = WindowsWindowNativeImpl()
        val root = WindowsContainerNativeImpl()
        val btn = WindowsButtonNativeImpl()
        val passThroughOverlay = WindowsContainerNativeImpl()
        try {
            var clickedCount = 0
            btn.setText("Save")
            btn.setTooltipText("Save document")
            btn.setEnabled(true)
            btn.setOpacity(0.95f)
            btn.setOnClick { clickedCount++ }

            val overlayStyle = ComponentStyle().apply {
                pointerEvents = CssPointerEvents.NONE
            }
            passThroughOverlay.applyComponentStyle(overlayStyle)

            root.addChildNative(btn)
            root.addChildNative(passThroughOverlay)
            win.addChildNative(root)

            win.setWindowSize(Size(400, 300))
            Win32NativeWindowHost.createAndShowWin32Window(win, startHidden = true)
            Win32NativeWindowHost.pumpVisualWindowEvents(16L)

            val hostHwnd = assertNotNull(Win32NativeWindowHost.resolveHwndForWindow(win))
            val rootHwnd = assertNotNull(root.nativeWidget.hwnd)
            val btnHwnd = assertNotNull(btn.nativeWidget.hwnd)
            val overlayHwnd = assertNotNull(passThroughOverlay.nativeWidget.hwnd)

            assertEquals(hostHwnd, GetParent(rootHwnd))
            assertEquals(rootHwnd, GetParent(btnHwnd))
            assertEquals(rootHwnd, GetParent(overlayHwnd))

            val topOffset = Win32NativeWindowHost.getMainWindowTitleBarHeight(win)
            root.setBounds(Rect(0, topOffset, 400, (300 - topOffset).coerceAtLeast(100)))
            Win32ControlMessageRouter.applyBounds(root.nativeWidget, root.getBounds())

            val btnRect = Rect(20, 20, 120, 36)
            btn.setBounds(btnRect)
            passThroughOverlay.setBounds(Rect(0, 0, 400, 300))
            Win32ControlMessageRouter.layoutChildrenBatch(rootHwnd, listOf(btn, passThroughOverlay))

            val hitOverlay = SendMessageW(overlayHwnd, WM_NCHITTEST.toUInt(), 0uL, 0L)
            assertEquals(HTTRANSPARENT.toLong(), hitOverlay)

            Win32NativeWindowHost.simulateMouseClick(
                x = 60,
                y = topOffset + 38,
                targetHwnd = hostHwnd
            )
            Win32NativeWindowHost.pumpVisualWindowEvents(16L)
            assertEquals(1, clickedCount)
        } finally {
            Win32NativeWindowHost.closeAllVisualWindows()
            Win32NativeWindowHost.visualTestMode = prevVisual
        }
    }

    @Test
    fun splitDropdownAndNumericUpDownBuddyWireNatively() {
        val container = WindowsContainerNativeImpl()
        val dropdown = WindowsDropdownNativeImpl()
        val numeric = WindowsTextInputNativeImpl()
        try {
            var primaryFired = 0
            dropdown.setLabel("Actions")
            dropdown.setItems(listOf("open" to "Open", "export" to "Export"))
            dropdown.setAttribute("data-control-variant", "split")
            dropdown.setOnPrimaryAction { primaryFired++ }

            numeric.setAttribute("data-control-variant", "number")
            numeric.setValue("42")

            container.addChildNative(dropdown)
            container.addChildNative(numeric)

            val containerHwnd = assertNotNull(container.nativeWidget.hwnd)
            val ddHwnd = assertNotNull(dropdown.nativeWidget.hwnd)
            assertEquals("Button", readWin32ClassName(ddHwnd))
            val buddyHwnd = assertNotNull(numeric.nativeWidget.buddyHwnd)
            assertEquals("msctls_updown32", readWin32ClassName(buddyHwnd))

            val wParam = (BN_CLICKED.toLong() shl 16).toULong()
            SendMessageW(containerHwnd, WM_COMMAND.toUInt(), wParam, ddHwnd.toLong())
            assertEquals(1, primaryFired)
        } finally {
            dropdown.dispose()
            numeric.dispose()
            container.dispose()
        }
    }
}
