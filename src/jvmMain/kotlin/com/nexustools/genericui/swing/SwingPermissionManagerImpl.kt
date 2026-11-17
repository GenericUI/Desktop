package com.nexustools.genericui.swing

import com.nexustools.genericui.enums.system.PermissionStatus
import com.nexustools.genericui.enums.system.PermissionType
import com.nexustools.genericui.nativeimpl.system.PermissionManagerNativeImpl
import java.awt.GraphicsEnvironment
import java.io.File
import java.nio.file.Files
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

/**
 * Java Swing / JVM desktop implementation of [PermissionManagerNativeImpl].
 *
 * Queries real desktop/JVM capability state (system tray / notification availability and user storage
 * readability/writability) and delegates runtime approval requests (camera, microphone, location)
 * through [runtimePermissionHandler] or an interactive Swing confirmation dialog rather than blindly
 * approving all permissions.
 */
class SwingPermissionManagerImpl : PermissionManagerNativeImpl {

    override var isDisposed: Boolean = false
        private set

    private val decidedRuntimeStatuses = mutableMapOf<PermissionType, PermissionStatus>()

    var runtimePermissionHandler: ((PermissionType, (PermissionStatus) -> Unit) -> Unit)? = null

    override fun checkPermission(permission: PermissionType): PermissionStatus {
        check(!isDisposed) { "SwingPermissionManagerImpl has been disposed." }
        decidedRuntimeStatuses[permission]?.let { return it }
        return when (permission) {
            PermissionType.NOTIFICATIONS,
            PermissionType.SYSTEM_TRAY ->
                if (SwingSystemTrayImpl.checkSystemTraySupported()) {
                    PermissionStatus.GRANTED
                } else {
                    PermissionStatus.NOT_APPLICABLE
                }

            PermissionType.READ_MEDIA_STORAGE ->
                if (Files.isReadable(resolveStorageRoot().toPath())) {
                    PermissionStatus.GRANTED
                } else {
                    PermissionStatus.DENIED
                }

            PermissionType.WRITE_MEDIA_STORAGE ->
                if (Files.isWritable(resolveStorageRoot().toPath())) {
                    PermissionStatus.GRANTED
                } else {
                    PermissionStatus.DENIED
                }

            PermissionType.CAMERA,
            PermissionType.MICROPHONE,
            PermissionType.LOCATION ->
                if (GraphicsEnvironment.isHeadless() && runtimePermissionHandler == null) {
                    PermissionStatus.NOT_APPLICABLE
                } else {
                    PermissionStatus.PROMPT_REQUIRED
                }
        }
    }

    override fun requestPermission(permission: PermissionType, onResult: (PermissionStatus) -> Unit) {
        check(!isDisposed) { "SwingPermissionManagerImpl has been disposed." }
        val current = checkPermission(permission)
        if (current == PermissionStatus.GRANTED ||
            current == PermissionStatus.PERMANENTLY_DENIED ||
            current == PermissionStatus.NOT_APPLICABLE
        ) {
            onResult(current)
            return
        }

        val handler = runtimePermissionHandler
        if (handler != null) {
            handler(permission) { resolved ->
                decidedRuntimeStatuses[permission] = resolved
                onResult(resolved)
            }
            return
        }

        if (GraphicsEnvironment.isHeadless()) {
            decidedRuntimeStatuses[permission] = PermissionStatus.DENIED
            onResult(PermissionStatus.DENIED)
            return
        }

        val prompt = {
            val answer = JOptionPane.showConfirmDialog(
                null,
                "Allow desktop access for ${permission.name.lowercase().replace('_', ' ')}?",
                "Permission Request",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE
            )
            val resolved = if (answer == JOptionPane.YES_OPTION) {
                PermissionStatus.GRANTED
            } else {
                PermissionStatus.DENIED
            }
            decidedRuntimeStatuses[permission] = resolved
            onResult(resolved)
        }
        if (SwingUtilities.isEventDispatchThread()) {
            prompt()
        } else {
            SwingUtilities.invokeLater { prompt() }
        }
    }

    override fun dispose() {
        isDisposed = true
        decidedRuntimeStatuses.clear()
        runtimePermissionHandler = null
    }

    private fun resolveStorageRoot(): File {
        val home = System.getProperty("user.home", ".").trim().ifEmpty { "." }
        return File(home)
    }
}
