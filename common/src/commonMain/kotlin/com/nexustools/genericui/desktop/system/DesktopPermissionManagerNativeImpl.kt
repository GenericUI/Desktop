package com.nexustools.genericui.desktop.system

import com.nexustools.genericui.enums.system.PermissionStatus
import com.nexustools.genericui.enums.system.PermissionType
import com.nexustools.genericui.nativeimpl.system.PermissionManagerNativeImpl

/**
 * Shared desktop base implementation of [PermissionManagerNativeImpl].
 */
open class DesktopPermissionManagerNativeImpl(
    val backendToolkit: String = "Desktop"
) : PermissionManagerNativeImpl {

    override var isDisposed: Boolean = false
        protected set

    protected val permissionStates: MutableMap<PermissionType, PermissionStatus> = mutableMapOf()

    override fun checkPermission(permission: PermissionType): PermissionStatus =
        permissionStates[permission] ?: PermissionStatus.GRANTED

    override fun requestPermission(permission: PermissionType, onResult: (PermissionStatus) -> Unit) {
        val status = permissionStates.getOrPut(permission) { PermissionStatus.GRANTED }
        onResult(status)
    }

    override fun dispose() {
        isDisposed = true
    }
}
