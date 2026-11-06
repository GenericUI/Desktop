@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.component.action.Button
import com.nexustools.genericui.component.dialog.DialogWindow
import com.nexustools.genericui.component.form.TextField
import com.nexustools.genericui.component.layout.Container
import com.nexustools.genericui.component.layout.Spacer
import com.nexustools.genericui.component.layout.VStack
import com.nexustools.genericui.component.overlay.Modal
import com.nexustools.genericui.component.shell.Window
import com.nexustools.genericui.component.typography.Text
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.dialog.DesktopDialogWindowNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapSize
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.bootstrap.SpacingScale
import com.nexustools.genericui.geometry.Size
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.windows.GetClientRect
import platform.windows.GetWindowRect
import platform.windows.InvalidateRect
import platform.windows.RECT
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Reproduces "dialogs are not sizing to their contents properly" on the Windows backend.
 *
 * Expected semantics (matching the Web backend `WebDialogWindowImpl`: `.modal-dialog-centered` with
 * `width: fit-content; max-width: calc(100vw - 2rem); height: auto; max-height: calc(100dvh - 2rem)`
 * and a scrollable `.modal-body`):
 * - The dialog's CLIENT area fits header-less body content + footer + paddings exactly (no clipping,
 *   no unnecessary scrollbar, no large empty gap), independent of the native caption/frame size (DPI).
 * - Wrapped text is measured at the same width it is painted at.
 * - Footer buttons are never clipped or wrapped out of the footer strip.
 * - The dialog re-measures (and re-centers) when content is added, revealed, changed, or removed after open.
 * - Tall content clamps to the owner window (minus margins) and the body scrolls.
 * - Explicit sizes are clamped to the owner window bounds.
 *
 * Observables: the real dialog HWND client/window rects, plus `scrollContentHeightPx` /
 * `scrollViewportHeightPx` written by `renderScrollableVerticalStack` onto the dialog widget while the
 * dialog HWND is painted via `Win32NativeWindowHost.paintWindow`.
 */
class WindowsDialogSizingTest {

    private class DialogMetrics(
        val clientW: Int,
        val clientH: Int,
        val windowLeft: Int,
        val windowTop: Int,
        val windowW: Int,
        val windowH: Int,
        val ownerLeft: Int,
        val ownerTop: Int,
        val ownerW: Int,
        val ownerH: Int,
        val contentH: Int,
        val viewportH: Int
    ) {
        val overflowPx: Int get() = contentH - viewportH
        val emptyGapPx: Int get() = viewportH - contentH
        val nonClientH: Int get() = windowH - clientH
        val nonClientW: Int get() = windowW - clientW
        val centerOffsetX: Int get() = abs((windowLeft + windowW / 2) - (ownerLeft + ownerW / 2))
        val centerOffsetY: Int get() = abs((windowTop + windowH / 2) - (ownerTop + ownerH / 2))

        override fun toString(): String =
            "client=${clientW}x$clientH window=${windowW}x$windowH@($windowLeft,$windowTop) " +
                "nonClient=${nonClientW}x$nonClientH owner=${ownerW}x$ownerH@($ownerLeft,$ownerTop) " +
                "bodyContentH=$contentH bodyViewportH=$viewportH"
    }

    private fun withDialogHost(block: (Window) -> Unit) {
        val prevInteractive = WindowsApplicationNativeImpl.interactiveWin32GuiEnabled
        val prevVisualTest = Win32NativeWindowHost.visualTestMode
        WindowsApplicationNativeImpl.interactiveWin32GuiEnabled = true
        Win32NativeWindowHost.visualTestMode = true
        try {
            val factory = WindowsNativeFactory()
            GenericUI.initialize(factory)
            GenericUI.activeFactory = factory
            val mainWindow = Window(title = "Dialog Sizing Owner")
            mainWindow.setWindowSize(Size(900, 640))
            mainWindow.setContent(Container("div", "w-100 h-100"))
            mainWindow.show(factory)
            Win32NativeWindowHost.pumpVisualWindowEvents(24L)
            assertNotNull(Win32NativeWindowHost.activeHwnd, "Expected owner Win32 HWND")
            block(mainWindow)
        } finally {
            for (dialog in DesktopDialogWindowNativeImpl.activeDialogs.toList()) {
                dialog.closeDialog()
            }
            Win32NativeWindowHost.closeAllVisualWindows()
            Win32NativeWindowHost.visualTestMode = prevVisualTest
            WindowsApplicationNativeImpl.interactiveWin32GuiEnabled = prevInteractive
        }
    }

    private fun openAndSettle(dialog: DialogWindow, owner: Window) {
        dialog.open(owner)
        Win32NativeWindowHost.pumpVisualWindowEvents(24L)
    }

    private fun settle() {
        Win32NativeWindowHost.pumpVisualWindowEvents(24L)
    }

    private fun dialogImpl(dialog: DialogWindow): DesktopDialogWindowNativeImpl<*> =
        dialog.ensureNativeImpl() as DesktopDialogWindowNativeImpl<*>

    private fun measure(dialog: DialogWindow): DialogMetrics = memScoped {
        val impl = dialogImpl(dialog)
        val hwnd = assertNotNull(Win32NativeWindowHost.dialogHwnds[impl], "Expected a native dialog HWND")
        val ownerHwnd = assertNotNull(Win32NativeWindowHost.activeHwnd, "Expected owner HWND")
        val client = alloc<RECT>()
        val win = alloc<RECT>()
        val owner = alloc<RECT>()
        GetClientRect(hwnd, client.ptr)
        GetWindowRect(hwnd, win.ptr)
        GetWindowRect(ownerHwnd, owner.ptr)
        InvalidateRect(hwnd, null, 0)
        Win32NativeWindowHost.paintWindow(hwnd)
        val attrs = impl.nativeWidget.attributes
        DialogMetrics(
            clientW = client.right - client.left,
            clientH = client.bottom - client.top,
            windowLeft = win.left,
            windowTop = win.top,
            windowW = win.right - win.left,
            windowH = win.bottom - win.top,
            ownerLeft = owner.left,
            ownerTop = owner.top,
            ownerW = owner.right - owner.left,
            ownerH = owner.bottom - owner.top,
            contentH = (attrs["scrollContentHeightPx"] as? Number)?.toInt() ?: -1,
            viewportH = (attrs["scrollViewportHeightPx"] as? Number)?.toInt() ?: -1
        )
    }

    private fun buildCreatePlaylistLikeModal(errorText: Text): Modal = Modal(title = "Create Playlist").apply {
        val root = VStack().apply {
            setPaddingAll(SpacingScale.S3)
            addChild(Text("Playlist Name:"))
            addChild(Spacer.vertical(4))
            addChild(TextField(placeholder = "Enter playlist name (e.g. Christmas)", value = ""))
            addChild(Spacer.vertical(8))
            addChild(errorText)
        }
        addChild(root)
        addLeftButton("Builtins", BootstrapType.INFO, BootstrapSize.SM, outline = true) {}
        addCloseButton("Cancel", outline = true)
        addRightButton("Create", BootstrapType.PRIMARY, BootstrapSize.SM) {}
    }

    private fun assertFitsContent(m: DialogMetrics, context: String, maxGapPx: Int = 32) {
        assertTrue(m.contentH > 0 && m.viewportH > 0, "$context: body was not laid out ($m)")
        assertTrue(
            m.overflowPx <= 0,
            "$context: dialog body content is clipped/scrolls by ${m.overflowPx}px even though it should " +
                "size to fit its content (client height ignores the ${m.nonClientH}px native caption/frame) — $m"
        )
        assertTrue(
            m.emptyGapPx <= maxGapPx,
            "$context: dialog has ${m.emptyGapPx}px of empty space below its content (expected <= ${maxGapPx}px) — $m"
        )
    }

    private fun assertCentered(m: DialogMetrics, context: String) {
        assertTrue(
            m.centerOffsetX <= 2 && m.centerOffsetY <= 2,
            "$context: dialog is not centered over its owner (dx=${m.centerOffsetX}, dy=${m.centerOffsetY}) — $m"
        )
    }

    @Test
    fun testSmallFormDialogClientAreaFitsContentWithoutScrolling() = withDialogHost { owner ->
        val errorText = Text("")
        val modal = buildCreatePlaylistLikeModal(errorText)
        openAndSettle(modal, owner)
        val m = measure(modal)
        println("[WindowsDialogSizingTest] small form: $m")
        assertFitsContent(m, "Small form dialog")
        assertTrue(m.windowH < m.ownerH / 2, "Small form dialog should not be sized near the owner height — $m")
        assertCentered(m, "Small form dialog")
    }

    @Test
    fun testWrappedParagraphIsMeasuredAtPaintedWidthAndFullyVisible() = withDialogHost { owner ->
        val paragraph = buildString {
            repeat(14) { i ->
                append("Sentence number $i explains a setting that wraps across multiple lines in the dialog body. ")
            }
        }
        val modal = Modal(title = "About Offline Downloads").apply {
            val root = VStack().apply {
                setPaddingAll(SpacingScale.S3)
                addChild(Text(paragraph))
            }
            addChild(root)
            addCloseButton("Close", outline = true)
        }
        openAndSettle(modal, owner)
        val m = measure(modal)
        println("[WindowsDialogSizingTest] wrapped paragraph: $m")
        assertTrue(m.windowW <= m.ownerW, "Wrapped-text dialog must not exceed owner width — $m")
        assertFitsContent(m, "Wrapped paragraph dialog")
    }

    @Test
    fun testFooterButtonsAreNotClippedWhenFooterIsTheWidestPart() = withDialogHost { owner ->
        val buttons = mutableListOf<Button>()
        val modal = Modal(title = "Item").apply {
            addChild(VStack().apply { addChild(Text("Choose an action.")) })
            buttons += addLeftButton("Builtins", BootstrapType.INFO, BootstrapSize.SM, outline = true) {}
            buttons += addLeftButton("Smart Select", BootstrapType.SECONDARY, BootstrapSize.SM, outline = true) {}
            buttons += addCloseButton("Cancel", outline = true)
            buttons += addRightButton("Delete Everything", BootstrapType.DANGER, BootstrapSize.SM) {}
            buttons += addRightButton("Create Playlist", BootstrapType.PRIMARY, BootstrapSize.SM) {}
        }
        openAndSettle(modal, owner)
        val m = measure(modal)
        println("[WindowsDialogSizingTest] wide footer: $m")

        val regions = (0 until Win32NativeWindowHost.getHitRegionsCount()).map { Win32NativeWindowHost.getHitRegionAt(it) }
        val footerTopLimit = m.clientH - 12 - 44
        val buttonRects = buttons.map { btn ->
            val widget = (btn.ensureNativeImpl() as DesktopBaseNativeImpl<*>).nativeWidget
            val r = regions.firstOrNull { it.widget === widget }
            assertNotNull(r, "Footer button '${btn.text}' was not laid out/hit-testable at all (clipped away) — $m")
            Triple(btn.text, r.left to r.top, r.right to r.bottom)
        }
        for ((label, lt, rb) in buttonRects) {
            assertTrue(
                lt.first >= 0 && rb.first <= m.clientW,
                "Footer button '$label' is horizontally clipped: x=[${lt.first}, ${rb.first}] client width=${m.clientW} — $m"
            )
            assertTrue(
                lt.second >= footerTopLimit - 2 && rb.second <= m.clientH,
                "Footer button '$label' wrapped/clipped out of the footer strip: y=[${lt.second}, ${rb.second}] " +
                    "footer=[$footerTopLimit, ${m.clientH}] — $m"
            )
        }
        for (i in buttonRects.indices) {
            for (j in i + 1 until buttonRects.size) {
                val a = buttonRects[i]
                val b = buttonRects[j]
                val overlapX = a.second.first < b.third.first && b.second.first < a.third.first
                val overlapY = a.second.second < b.third.second && b.second.second < a.third.second
                assertTrue(!(overlapX && overlapY), "Footer buttons '${a.first}' and '${b.first}' overlap — $m")
            }
        }
    }

    @Test
    fun testDialogGrowsAndRecentersWhenContentIsChangedOrRevealedAfterOpen() = withDialogHost { owner ->
        val errorText = Text("")
        val lateSection = VStack().apply {
            isVisible = false
            for (i in 1..6) {
                addChild(Text("Late-loaded setting row $i"))
            }
        }
        val modal = buildCreatePlaylistLikeModal(errorText).apply {
            addChild(VStack().apply { addChild(lateSection) })
        }
        openAndSettle(modal, owner)
        val before = measure(modal)
        println("[WindowsDialogSizingTest] before reveal: $before")

        errorText.text = "Please enter a playlist name."
        lateSection.isVisible = true
        settle()
        val after = measure(modal)
        println("[WindowsDialogSizingTest] after reveal: $after")

        assertTrue(
            after.clientH > before.clientH,
            "Dialog did not re-measure after content was revealed/changed post-open " +
                "(client height stayed ${before.clientH} -> ${after.clientH}) — before=$before after=$after"
        )
        assertFitsContent(after, "Dialog after revealing content")
        assertCentered(after, "Dialog after revealing content")
    }

    @Test
    fun testDialogShrinksWhenContentIsRemovedAfterOpen() = withDialogHost { owner ->
        val rows = (1..12).map { Text("Episode row $it") }
        val listHost = VStack().apply { rows.forEach { addChild(it) } }
        val modal = Modal(title = "Season 1").apply {
            addChild(VStack().apply {
                setPaddingAll(SpacingScale.S3)
                addChild(listHost)
            })
            addCloseButton("Close", outline = true)
        }
        openAndSettle(modal, owner)
        val before = measure(modal)
        println("[WindowsDialogSizingTest] before remove: $before")

        rows.drop(2).forEach { listHost.removeChild(it) }
        settle()
        val after = measure(modal)
        println("[WindowsDialogSizingTest] after remove: $after")

        assertTrue(
            after.clientH < before.clientH,
            "Dialog did not shrink after content was removed post-open " +
                "(client height ${before.clientH} -> ${after.clientH}) — before=$before after=$after"
        )
        assertFitsContent(after, "Dialog after removing content")
        assertCentered(after, "Dialog after removing content")
    }

    @Test
    fun testTallDialogClampsToOwnerAndScrollsBody() = withDialogHost { owner ->
        val modal = Modal(title = "All Episodes").apply {
            addChild(VStack().apply {
                for (i in 1..90) {
                    addChild(Text("Episode $i - A reasonably long episode title"))
                }
            })
            addCloseButton("Close", outline = true)
        }
        openAndSettle(modal, owner)
        val m = measure(modal)
        println("[WindowsDialogSizingTest] tall: $m")
        assertTrue(m.windowH <= m.ownerH, "Tall dialog must clamp to owner height — $m")
        assertTrue(m.windowW <= m.ownerW, "Tall dialog must clamp to owner width — $m")
        assertTrue(m.overflowPx > 0, "Tall dialog body should scroll (content taller than viewport) — $m")
        assertTrue(m.viewportH >= 120, "Tall dialog body viewport should remain usable — $m")
        assertCentered(m, "Tall dialog")
    }

    @Test
    fun testExplicitlySizedDialogIsClampedToOwnerBounds() = withDialogHost { owner ->
        val ownerSize = memScoped {
            val r = alloc<RECT>()
            GetWindowRect(Win32NativeWindowHost.activeHwnd, r.ptr)
            (r.right - r.left) to (r.bottom - r.top)
        }
        val modal = Modal(title = "Huge").apply {
            addChild(VStack().apply { addChild(Text("Explicitly oversized dialog")) })
            addCloseButton("Close", outline = true)
            setWindowSize(Size(ownerSize.first + 400, ownerSize.second + 300))
        }
        openAndSettle(modal, owner)
        val m = measure(modal)
        println("[WindowsDialogSizingTest] explicit oversized: $m")
        assertTrue(
            m.windowW <= m.ownerW && m.windowH <= m.ownerH,
            "Explicit dialog size must be clamped to the owner window (Web: min(W, 100vw - 2rem)) — $m"
        )
        assertTrue(
            m.windowW >= m.ownerW - 96,
            "Oversized explicit dialog width should be clamped to (not ignored in favour of) the owner width — $m"
        )
        assertCentered(m, "Explicitly sized dialog")
    }

    @Test
    fun testExplicitDialogSizeIsHonoredWhenItFitsTheOwner() = withDialogHost { owner ->
        val modal = Modal(title = "Preferences").apply {
            addChild(VStack().apply { addChild(Text("Explicitly sized dialog")) })
            addCloseButton("Close", outline = true)
            setWindowSize(Size(480, 320))
        }
        openAndSettle(modal, owner)
        val m = measure(modal)
        println("[WindowsDialogSizingTest] explicit 480x320: $m")
        assertTrue(
            abs(m.windowW - 480) <= 2 && abs(m.windowH - 320) <= 2,
            "Explicit dialog size 480x320 was not applied to the native dialog window — $m"
        )
        assertCentered(m, "Explicit 480x320 dialog")
    }
}
