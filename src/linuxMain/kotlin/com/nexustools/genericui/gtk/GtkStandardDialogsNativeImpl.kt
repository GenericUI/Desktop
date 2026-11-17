@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk

import com.nexustools.genericui.desktop.dialog.DesktopStandardDialogsNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.linux.LinuxGlibGioBindings
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import kotlin.math.roundToInt

/**
 * GTK4 (`GtkAlertDialog`, `GtkColorDialog`, and modal `GtkWindow` with `GtkEntry` / `GtkDropDown`)
 * implementation of [com.nexustools.genericui.nativeimpl.dialog.StandardDialogsNativeImpl].
 */
class GtkStandardDialogsNativeImpl : DesktopStandardDialogsNativeImpl(
    backendToolkit = "GTK4 (GtkAlertDialog / GtkColorDialog)"
) {
    override fun showAlert(
        owner: WindowNativeImpl<*>?,
        title: String,
        message: String,
        type: BootstrapType,
        okButtonText: String,
        onDismissed: () -> Unit
    ) {
        if (!GtkApplicationNativeImpl.interactiveGtkGuiEnabled || !Gtk4DynamicBindings.ensureGtkInitialized()) {
            super.showAlert(owner, title, message, type, okButtonText, onDismissed)
            return
        }
        val parentPtr = resolveParentGtkWindow(owner)
        GenericUiThreading.runOnUi {
            val dlg = Gtk4DynamicBindings.gtkAlertDialogNew(title.ifBlank { "Notice" })
            if (dlg != null) {
                if (message.isNotEmpty()) {
                    Gtk4DynamicBindings.gtkAlertDialogSetDetail(dlg, message)
                }
                Gtk4DynamicBindings.gtkAlertDialogSetButtons(dlg, listOf(okButtonText.ifBlank { "OK" }))
                Gtk4DynamicBindings.gtkAlertDialogSetDefaultButton(dlg, 0)
                Gtk4DynamicBindings.gtkAlertDialogSetCancelButton(dlg, 0)
                val state = AlertModalState()
                val ref = StableRef.create(state)
                val cb = staticCFunction { source: COpaquePointer?, res: COpaquePointer?, userData: COpaquePointer? ->
                    if (userData != null) {
                        val s = userData.asStableRef<AlertModalState>().get()
                        if (source != null && res != null) {
                            s.buttonIndex = Gtk4DynamicBindings.gtkAlertDialogChooseFinish(source, res)
                        }
                        s.completed = true
                    }
                }
                Gtk4DynamicBindings.gtkAlertDialogChoose(dlg, parentPtr, cb, ref.asCPointer())
                pumpUntil { state.completed }
                ref.dispose()
                LinuxGlibGioBindings.objectUnref(dlg)
            }
            GenericUiThreading.runOnLogic {
                onDismissed()
            }
        }
    }

    override fun showConfirm(
        owner: WindowNativeImpl<*>?,
        title: String,
        message: String,
        confirmText: String,
        cancelText: String,
        type: BootstrapType,
        onResult: (Boolean) -> Unit
    ) {
        if (!GtkApplicationNativeImpl.interactiveGtkGuiEnabled || !Gtk4DynamicBindings.ensureGtkInitialized()) {
            super.showConfirm(owner, title, message, confirmText, cancelText, type, onResult)
            return
        }
        val parentPtr = resolveParentGtkWindow(owner)
        GenericUiThreading.runOnUi {
            val dlg = Gtk4DynamicBindings.gtkAlertDialogNew(title.ifBlank { "Confirm" })
            var confirmed = false
            if (dlg != null) {
                if (message.isNotEmpty()) {
                    Gtk4DynamicBindings.gtkAlertDialogSetDetail(dlg, message)
                }
                Gtk4DynamicBindings.gtkAlertDialogSetButtons(
                    dlg,
                    listOf(cancelText.ifBlank { "Cancel" }, confirmText.ifBlank { "OK" })
                )
                Gtk4DynamicBindings.gtkAlertDialogSetCancelButton(dlg, 0)
                Gtk4DynamicBindings.gtkAlertDialogSetDefaultButton(dlg, 1)
                val state = AlertModalState()
                val ref = StableRef.create(state)
                val cb = staticCFunction { source: COpaquePointer?, res: COpaquePointer?, userData: COpaquePointer? ->
                    if (userData != null) {
                        val s = userData.asStableRef<AlertModalState>().get()
                        if (source != null && res != null) {
                            s.buttonIndex = Gtk4DynamicBindings.gtkAlertDialogChooseFinish(source, res)
                        }
                        s.completed = true
                    }
                }
                Gtk4DynamicBindings.gtkAlertDialogChoose(dlg, parentPtr, cb, ref.asCPointer())
                pumpUntil { state.completed }
                ref.dispose()
                LinuxGlibGioBindings.objectUnref(dlg)
                confirmed = state.buttonIndex == 1
            }
            GenericUiThreading.runOnLogic {
                onResult(confirmed)
            }
        }
    }

    override fun showPrompt(
        owner: WindowNativeImpl<*>?,
        title: String,
        message: String,
        defaultValue: String,
        placeholder: String,
        confirmText: String,
        cancelText: String,
        onResult: (String?) -> Unit
    ) {
        if (!GtkApplicationNativeImpl.interactiveGtkGuiEnabled || !Gtk4DynamicBindings.ensureGtkInitialized()) {
            super.showPrompt(owner, title, message, defaultValue, placeholder, confirmText, cancelText, onResult)
            return
        }
        val parentPtr = resolveParentGtkWindow(owner)
        GenericUiThreading.runOnUi {
            val win = Gtk4DynamicBindings.gtkWindowNew()
            val rootBox = Gtk4DynamicBindings.gtkBoxNew(Gtk4DynamicBindings.GTK_ORIENTATION_VERTICAL, 12)
            val entry = Gtk4DynamicBindings.gtkEntryNew()
            if (win == null || rootBox == null || entry == null) {
                if (win != null) Gtk4DynamicBindings.gtkWindowDestroy(win)
                GenericUiThreading.runOnLogic { onResult(null) }
                return@runOnUi
            }

            Gtk4DynamicBindings.gtkWindowSetTitle(win, title.ifBlank { "Input" })
            Gtk4DynamicBindings.gtkWindowSetModal(win, true)
            Gtk4DynamicBindings.gtkWindowSetResizable(win, false)
            Gtk4DynamicBindings.gtkWindowSetDefaultSize(win, 380, -1)
            if (parentPtr != null) Gtk4DynamicBindings.gtkWindowSetTransientFor(win, parentPtr)
            Gtk4DynamicBindings.gtkWidgetSetMargins(rootBox, 16, 16, 16, 16)

            if (message.isNotEmpty()) {
                Gtk4DynamicBindings.gtkLabelNew(message)?.let { lbl ->
                    Gtk4DynamicBindings.gtkLabelSetWrap(lbl, true)
                    Gtk4DynamicBindings.gtkBoxAppend(rootBox, lbl)
                }
            }
            if (defaultValue.isNotEmpty()) {
                Gtk4DynamicBindings.gtkEditableSetText(entry, defaultValue)
            }
            Gtk4DynamicBindings.gtkBoxAppend(rootBox, entry)

            val btnBox = Gtk4DynamicBindings.gtkBoxNew(Gtk4DynamicBindings.GTK_ORIENTATION_HORIZONTAL, 8)
            val cancelBtn = Gtk4DynamicBindings.gtkButtonNewWithLabel(cancelText.ifBlank { "Cancel" })
            val confirmBtn = Gtk4DynamicBindings.gtkButtonNewWithLabel(confirmText.ifBlank { "OK" })
            if (btnBox != null) {
                if (cancelBtn != null) Gtk4DynamicBindings.gtkBoxAppend(btnBox, cancelBtn)
                if (confirmBtn != null) Gtk4DynamicBindings.gtkBoxAppend(btnBox, confirmBtn)
                Gtk4DynamicBindings.gtkBoxAppend(rootBox, btnBox)
            }

            val modalState = CustomModalState()
            val stateRef = StableRef.create(modalState)
            val refPtr = stateRef.asCPointer()

            val confirmCb = staticCFunction { _: COpaquePointer?, userData: COpaquePointer? ->
                userData?.asStableRef<CustomModalState>()?.get()?.finish(accepted = true)
                Unit
            }
            val cancelCb = staticCFunction { _: COpaquePointer?, userData: COpaquePointer? ->
                userData?.asStableRef<CustomModalState>()?.get()?.finish(accepted = false)
                Unit
            }
            val closeCb = staticCFunction { _: COpaquePointer?, userData: COpaquePointer? ->
                userData?.asStableRef<CustomModalState>()?.get()?.finish(accepted = false)
                1
            }

            if (confirmBtn != null) LinuxGlibGioBindings.signalConnect(confirmBtn, "clicked", confirmCb, refPtr)
            if (cancelBtn != null) LinuxGlibGioBindings.signalConnect(cancelBtn, "clicked", cancelCb, refPtr)
            LinuxGlibGioBindings.signalConnect(entry, "activate", confirmCb, refPtr)
            LinuxGlibGioBindings.signalConnect(win, "close-request", closeCb, refPtr)

            Gtk4DynamicBindings.gtkWindowSetChild(win, rootBox)
            Gtk4DynamicBindings.gtkWindowPresent(win)
            Gtk4DynamicBindings.gtkWidgetGrabFocus(entry)

            pumpUntil { modalState.completed }
            val resultText = if (modalState.accepted) Gtk4DynamicBindings.gtkEditableGetText(entry) else null
            Gtk4DynamicBindings.gtkWindowDestroy(win)
            stateRef.dispose()

            GenericUiThreading.runOnLogic {
                onResult(resultText)
            }
        }
    }

    override fun showChoice(
        owner: WindowNativeImpl<*>?,
        title: String,
        message: String,
        options: List<String>,
        selectedIndex: Int,
        onResult: (Int?) -> Unit
    ) {
        if (!GtkApplicationNativeImpl.interactiveGtkGuiEnabled || !Gtk4DynamicBindings.ensureGtkInitialized()) {
            super.showChoice(owner, title, message, options, selectedIndex, onResult)
            return
        }
        val parentPtr = resolveParentGtkWindow(owner)
        GenericUiThreading.runOnUi {
            if (options.isEmpty()) {
                GenericUiThreading.runOnLogic { onResult(null) }
                return@runOnUi
            }
            val win = Gtk4DynamicBindings.gtkWindowNew()
            val rootBox = Gtk4DynamicBindings.gtkBoxNew(Gtk4DynamicBindings.GTK_ORIENTATION_VERTICAL, 12)
            val dropdown = Gtk4DynamicBindings.gtkDropDownNewFromStrings(options)
            if (win == null || rootBox == null || dropdown == null) {
                if (win != null) Gtk4DynamicBindings.gtkWindowDestroy(win)
                GenericUiThreading.runOnLogic { onResult(null) }
                return@runOnUi
            }

            Gtk4DynamicBindings.gtkWindowSetTitle(win, title.ifBlank { "Choose Option" })
            Gtk4DynamicBindings.gtkWindowSetModal(win, true)
            Gtk4DynamicBindings.gtkWindowSetResizable(win, false)
            Gtk4DynamicBindings.gtkWindowSetDefaultSize(win, 360, -1)
            if (parentPtr != null) Gtk4DynamicBindings.gtkWindowSetTransientFor(win, parentPtr)
            Gtk4DynamicBindings.gtkWidgetSetMargins(rootBox, 16, 16, 16, 16)

            if (message.isNotEmpty()) {
                Gtk4DynamicBindings.gtkLabelNew(message)?.let { lbl ->
                    Gtk4DynamicBindings.gtkLabelSetWrap(lbl, true)
                    Gtk4DynamicBindings.gtkBoxAppend(rootBox, lbl)
                }
            }
            if (selectedIndex in options.indices) {
                Gtk4DynamicBindings.gtkDropDownSetSelected(dropdown, selectedIndex)
            }
            Gtk4DynamicBindings.gtkBoxAppend(rootBox, dropdown)

            val btnBox = Gtk4DynamicBindings.gtkBoxNew(Gtk4DynamicBindings.GTK_ORIENTATION_HORIZONTAL, 8)
            val cancelBtn = Gtk4DynamicBindings.gtkButtonNewWithLabel("Cancel")
            val okBtn = Gtk4DynamicBindings.gtkButtonNewWithLabel("OK")
            if (btnBox != null) {
                if (cancelBtn != null) Gtk4DynamicBindings.gtkBoxAppend(btnBox, cancelBtn)
                if (okBtn != null) Gtk4DynamicBindings.gtkBoxAppend(btnBox, okBtn)
                Gtk4DynamicBindings.gtkBoxAppend(rootBox, btnBox)
            }

            val modalState = CustomModalState()
            val stateRef = StableRef.create(modalState)
            val refPtr = stateRef.asCPointer()

            val confirmCb = staticCFunction { _: COpaquePointer?, userData: COpaquePointer? ->
                userData?.asStableRef<CustomModalState>()?.get()?.finish(accepted = true)
                Unit
            }
            val cancelCb = staticCFunction { _: COpaquePointer?, userData: COpaquePointer? ->
                userData?.asStableRef<CustomModalState>()?.get()?.finish(accepted = false)
                Unit
            }
            val closeCb = staticCFunction { _: COpaquePointer?, userData: COpaquePointer? ->
                userData?.asStableRef<CustomModalState>()?.get()?.finish(accepted = false)
                1
            }

            if (okBtn != null) LinuxGlibGioBindings.signalConnect(okBtn, "clicked", confirmCb, refPtr)
            if (cancelBtn != null) LinuxGlibGioBindings.signalConnect(cancelBtn, "clicked", cancelCb, refPtr)
            LinuxGlibGioBindings.signalConnect(win, "close-request", closeCb, refPtr)

            Gtk4DynamicBindings.gtkWindowSetChild(win, rootBox)
            Gtk4DynamicBindings.gtkWindowPresent(win)

            pumpUntil { modalState.completed }
            val chosen = if (modalState.accepted) {
                Gtk4DynamicBindings.gtkDropDownGetSelected(dropdown).takeIf { it in options.indices }
            } else {
                null
            }
            Gtk4DynamicBindings.gtkWindowDestroy(win)
            stateRef.dispose()

            GenericUiThreading.runOnLogic {
                onResult(chosen)
            }
        }
    }

    override fun showColorChooser(
        owner: WindowNativeImpl<*>?,
        title: String,
        initialColor: RgbaColor,
        onResult: (RgbaColor?) -> Unit
    ) {
        if (!GtkApplicationNativeImpl.interactiveGtkGuiEnabled || !Gtk4DynamicBindings.ensureGtkInitialized()) {
            super.showColorChooser(owner, title, initialColor, onResult)
            return
        }
        val parentPtr = resolveParentGtkWindow(owner)
        GenericUiThreading.runOnUi {
            val dlg = Gtk4DynamicBindings.gtkColorDialogNew()
            var chosen: RgbaColor? = null
            if (dlg != null) {
                Gtk4DynamicBindings.gtkColorDialogSetTitle(dlg, title.ifBlank { "Choose Color" })
                Gtk4DynamicBindings.gtkColorDialogSetWithAlpha(dlg, true)
                val state = ColorModalState()
                val ref = StableRef.create(state)
                val cb = staticCFunction { source: COpaquePointer?, res: COpaquePointer?, userData: COpaquePointer? ->
                    if (userData != null) {
                        val s = userData.asStableRef<ColorModalState>().get()
                        if (source != null && res != null) {
                            Gtk4DynamicBindings.gtkColorDialogChooseRgbaFinish(source, res)?.let { rgba ->
                                s.color = RgbaColor(
                                    r = (rgba[0] * 255f).roundToInt().coerceIn(0, 255),
                                    g = (rgba[1] * 255f).roundToInt().coerceIn(0, 255),
                                    b = (rgba[2] * 255f).roundToInt().coerceIn(0, 255),
                                    a = (rgba[3] * 255f).roundToInt().coerceIn(0, 255)
                                )
                            }
                        }
                        s.completed = true
                    }
                }
                Gtk4DynamicBindings.gtkColorDialogChooseRgba(
                    dlg = dlg,
                    parent = parentPtr,
                    r = initialColor.r / 255f,
                    g = initialColor.g / 255f,
                    b = initialColor.b / 255f,
                    a = initialColor.a / 255f,
                    callback = cb,
                    userData = ref.asCPointer()
                )
                pumpUntil { state.completed }
                ref.dispose()
                LinuxGlibGioBindings.objectUnref(dlg)
                chosen = state.color
            }
            GenericUiThreading.runOnLogic {
                onResult(chosen)
            }
        }
    }

    private fun resolveParentGtkWindow(owner: WindowNativeImpl<*>?): COpaquePointer? =
        (owner as? GtkWindowNativeImpl)?.let { GtkNativeWindowHost.sessionForWindow(it)?.gtkWindow }
            ?: GtkNativeWindowHost.activeGtkWindowPtr()

    private fun pumpUntil(done: () -> Boolean) {
        val ctx = LinuxGlibGioBindings.mainContextDefault()
        while (!done()) {
            LinuxGlibGioBindings.mainContextIteration(ctx, mayBlock = true)
        }
    }

    private class AlertModalState {
        var completed: Boolean = false
        var buttonIndex: Int = -1
    }

    private class ColorModalState {
        var completed: Boolean = false
        var color: RgbaColor? = null
    }

    private class CustomModalState {
        var completed: Boolean = false
        var accepted: Boolean = false

        fun finish(accepted: Boolean) {
            if (!completed) {
                this.accepted = accepted
                completed = true
            }
        }
    }
}
