package com.nexustools.genericui.desktop.system

import com.nexustools.genericui.enums.system.PermissionStatus
import com.nexustools.genericui.enums.system.PermissionType
import com.nexustools.genericui.nativeimpl.system.PermissionManagerNativeImpl

/**
 * Shared desktop base implementation of [PermissionManagerNativeImpl].
 *
 * Subclasses query OS permission APIs; explicit [permissionStates] entries can override statuses
 * for tests without falling back to blind hardcoded approvals.
 */
open class DesktopPermissionManagerNativeImpl(
    val backendToolkit: String = "Desktop"
) : PermissionManagerNativeImpl {

    override var isDisposed: Boolean = false
        protected set

    protected val permissionStates: MutableMap<PermissionType, PermissionStatus> = mutableMapOf()

    fun setPermissionOverride(permission: PermissionType, status: PermissionStatus?) {
        if (status == null) {
            permissionStates.remove(permission)
        } else {
            permissionStates[permission] = status
        }
    }

    override fun checkPermission(permission: PermissionType): PermissionStatus {
        check(!isDisposed) { "$backendToolkit permission manager has been disposed." }
        return permissionStates[permission]
            ?: throw UnsupportedOperationException(
                "$backendToolkit permission checking for $permission requires a platform OS implementation."
            )
    }

    override fun requestPermission(permission: PermissionType, onResult: (PermissionStatus) -> Unit) {
        check(!isDisposed) { "$backendToolkit permission manager has been disposed." }
        val configured = permissionStates[permission]
            ?: throw UnsupportedOperationException(
                "$backendToolkit permission request for $permission requires a platform OS implementation."
            )
        onResult(configured)
    }

    override fun dispose() {
        isDisposed = true
        permissionStates.clear()
    }
}
