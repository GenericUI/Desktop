@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk

import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.desktop.system.DesktopSystemTrayNativeImpl
import com.nexustools.genericui.linux.LinuxGlibGioBindings
import com.nexustools.genericui.linux.LinuxReentrantLock
import com.nexustools.genericui.nativeimpl.shell.ContextMenuNativeImpl
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import platform.posix.getpid

/**
 * GTK / Linux system tray implementation using the freedesktop / KDE
 * `org.kde.StatusNotifierItem` D-Bus protocol over `libgio-2.0.so.0`.
 */
class GtkSystemTrayNativeImpl : DesktopSystemTrayNativeImpl(
    isSupported = true
) {
    private val lock = LinuxReentrantLock()
    private var dbusConnection: COpaquePointer? = null
    private var nodeInfo: COpaquePointer? = null
    private var registrationId: UInt = 0u
    private var busOwnerId: UInt = 0u
    private var vtablePtr: COpaquePointer? = null
    private var selfRef: StableRef<GtkSystemTrayNativeImpl>? = null
    private var registeredBusName: String? = null

    override fun setIcon(iconPathOrUrl: String?) {
        iconPath = iconPathOrUrl
        scheduleOrEmitDbusSignal("NewIcon")
    }

    override fun setTooltip(tooltip: String?) {
        tooltipText = tooltip
        scheduleOrEmitDbusSignal("NewToolTip")
    }

    override fun setBadgeText(badge: String?) {
        badgeText = badge
        scheduleOrEmitDbusSignal("NewTitle")
        scheduleOrEmitDbusSignal("NewStatus")
    }

    override fun setContextMenu(menuImpl: ContextMenuNativeImpl<*>?) {
        contextMenu = menuImpl
        ensureDbusRegisteredIfInteractive()
    }

    override fun setOnTrayIconClicked(listener: (() -> Unit)?) {
        onClickedListener = listener
        ensureDbusRegisteredIfInteractive()
    }

    override fun dispose() {
        super.dispose()
        var conn: COpaquePointer? = null
        var regId: UInt = 0u
        var ownerId: UInt = 0u
        var info: COpaquePointer? = null
        var vtable: COpaquePointer? = null
        var ref: StableRef<GtkSystemTrayNativeImpl>? = null
        lock.withLock {
            conn = dbusConnection
            regId = registrationId
            ownerId = busOwnerId
            info = nodeInfo
            vtable = vtablePtr
            ref = selfRef
            dbusConnection = null
            registrationId = 0u
            busOwnerId = 0u
            nodeInfo = null
            vtablePtr = null
            selfRef = null
            registeredBusName = null
        }
        if (conn != null && regId != 0u) {
            LinuxGlibGioBindings.dbusUnregisterObject(conn, regId)
        }
        if (ownerId != 0u) {
            LinuxGlibGioBindings.busUnownName(ownerId)
        }
        if (info != null) {
            LinuxGlibGioBindings.dbusNodeInfoUnref(info)
        }
        if (vtable != null) {
            LinuxGlibGioBindings.free(vtable)
        }
        ref?.dispose()
    }

    private fun scheduleOrEmitDbusSignal(signalName: String) {
        if (!GtkApplicationNativeImpl.interactiveGtkGuiEnabled || isDisposed) return
        ensureDbusRegisteredIfInteractive()
        val conn = lock.withLock { dbusConnection } ?: return
        val param = if (signalName == "NewStatus") {
            val statusV = LinuxGlibGioBindings.variantNewString(currentStatus()) ?: return
            LinuxGlibGioBindings.variantNewTuple(listOf(statusV))
        } else {
            null
        }
        LinuxGlibGioBindings.dbusEmitSignal(
            connection = conn,
            objectPath = SNI_OBJECT_PATH,
            interfaceName = SNI_INTERFACE,
            signalName = signalName,
            parameters = param
        )
    }

    private fun ensureDbusRegisteredIfInteractive() {
        if (!GtkApplicationNativeImpl.interactiveGtkGuiEnabled || isDisposed) return
        if (!LinuxGlibGioBindings.ensureLoaded()) return
        val shouldRegister = lock.withLock { registrationId == 0u }
        if (!shouldRegister) return

        val conn = LinuxGlibGioBindings.busGetSessionSync() ?: return
        val info = LinuxGlibGioBindings.dbusNodeInfoNewForXml(SNI_INTROSPECTION_XML) ?: return
        val ifaceInfo = LinuxGlibGioBindings.dbusNodeInfoInterfacesFirst(info) ?: run {
            LinuxGlibGioBindings.dbusNodeInfoUnref(info)
            return
        }

        // GDBusInterfaceVTable on LP64 is 88 bytes: { method_call (0), get_property (8), set_property (16), padding... }
        val vtable = LinuxGlibGioBindings.malloc0(88uL) ?: run {
            LinuxGlibGioBindings.dbusNodeInfoUnref(info)
            return
        }
        val fnSlots = vtable.reinterpret<COpaquePointerVar>()
        val methodCb = staticCFunction { _: COpaquePointer?, _: CPointer<ByteVar>?, _: CPointer<ByteVar>?, _: CPointer<ByteVar>?, methodName: CPointer<ByteVar>?, parameters: COpaquePointer?, invocation: COpaquePointer?, userData: COpaquePointer? ->
            val tray = userData?.asStableRef<GtkSystemTrayNativeImpl>()?.get()
            if (tray != null && invocation != null) {
                tray.handleMethodCall(methodName?.toKString().orEmpty(), parameters, invocation)
            }
        }
        val getPropCb = staticCFunction { _: COpaquePointer?, _: CPointer<ByteVar>?, _: CPointer<ByteVar>?, _: CPointer<ByteVar>?, propertyName: CPointer<ByteVar>?, _: CPointer<COpaquePointerVar>?, userData: COpaquePointer? ->
            val tray = userData?.asStableRef<GtkSystemTrayNativeImpl>()?.get()
            tray?.handleGetProperty(propertyName?.toKString().orEmpty())
        }
        fnSlots[0] = methodCb
        fnSlots[1] = getPropCb
        fnSlots[2] = null

        val ref = StableRef.create(this)
        val regId = LinuxGlibGioBindings.dbusRegisterObject(
            connection = conn,
            objectPath = SNI_OBJECT_PATH,
            interfaceInfo = ifaceInfo,
            vtable = vtable,
            userData = ref.asCPointer()
        )
        if (regId == 0u) {
            ref.dispose()
            LinuxGlibGioBindings.free(vtable)
            LinuxGlibGioBindings.dbusNodeInfoUnref(info)
            return
        }

        val serviceName = "org.kde.StatusNotifierItem-${getpid()}-${(++trayCounter)}"
        val ownerId = LinuxGlibGioBindings.busOwnName(serviceName)
        lock.withLock {
            dbusConnection = conn
            nodeInfo = info
            registrationId = regId
            busOwnerId = ownerId
            vtablePtr = vtable
            selfRef = ref
            registeredBusName = serviceName
        }

        val arg = LinuxGlibGioBindings.variantNewString(serviceName)
        val params = if (arg != null) LinuxGlibGioBindings.variantNewTuple(listOf(arg)) else null
        val reply = LinuxGlibGioBindings.dbusCallSync(
            connection = conn,
            busName = WATCHER_BUS_NAME,
            objectPath = WATCHER_OBJECT_PATH,
            interfaceName = WATCHER_INTERFACE,
            methodName = "RegisterStatusNotifierItem",
            parameters = params
        )
        if (reply != null) {
            LinuxGlibGioBindings.variantUnref(reply)
        }
    }

    private fun handleMethodCall(
        methodName: String,
        parameters: COpaquePointer?,
        invocation: COpaquePointer
    ) {
        when (methodName) {
            "Activate" -> {
                LinuxGlibGioBindings.dbusMethodReturnValue(invocation, null)
                val listener = onClickedListener
                if (listener != null) {
                    GenericUiThreading.runOnLogic { listener.invoke() }
                }
            }
            "SecondaryActivate", "ContextMenu" -> {
                LinuxGlibGioBindings.dbusMethodReturnValue(invocation, null)
                val menu = contextMenu
                val listener = onClickedListener
                if (menu == null && listener != null) {
                    GenericUiThreading.runOnLogic { listener.invoke() }
                }
            }
            "Scroll" -> {
                LinuxGlibGioBindings.dbusMethodReturnValue(invocation, null)
            }
            else -> {
                LinuxGlibGioBindings.dbusMethodReturnError(
                    invocation,
                    "org.freedesktop.DBus.Error.UnknownMethod",
                    "Unknown method $methodName"
                )
            }
        }
    }

    private fun handleGetProperty(propertyName: String): COpaquePointer? {
        val appTitle = Application.current?.mainWindow?.getTitle()?.ifBlank { "GenericUI" } ?: "GenericUI"
        val titleWithBadge = badgeText?.trim()?.takeIf { it.isNotEmpty() }?.let { "$appTitle ($it)" } ?: appTitle
        return when (propertyName) {
            "Category" -> LinuxGlibGioBindings.variantNewString("ApplicationStatus")
            "Id" -> LinuxGlibGioBindings.variantNewString(registeredBusName ?: appTitle.lowercase().replace(' ', '-'))
            "Title" -> LinuxGlibGioBindings.variantNewString(titleWithBadge)
            "Status" -> LinuxGlibGioBindings.variantNewString(currentStatus())
            "WindowId" -> LinuxGlibGioBindings.variantNewUint32(0u)
            "IconName" -> LinuxGlibGioBindings.variantNewString(iconPath?.trim()?.takeIf { it.isNotEmpty() } ?: defaultIconPath)
            "IconThemePath" -> LinuxGlibGioBindings.variantNewString("")
            "OverlayIconName" -> LinuxGlibGioBindings.variantNewString("")
            "AttentionIconName" -> LinuxGlibGioBindings.variantNewString("")
            "AttentionMovieName" -> LinuxGlibGioBindings.variantNewString("")
            "ItemIsMenu" -> LinuxGlibGioBindings.variantNewBoolean(contextMenu != null && onClickedListener == null)
            "ToolTip" -> buildToolTipVariant(titleWithBadge, tooltipText.orEmpty())
            else -> null
        }
    }

    private fun buildToolTipVariant(title: String, description: String): COpaquePointer? {
        val pixmapType = LinuxGlibGioBindings.variantTypeNew("(iiay)") ?: return null
        return try {
            val iconNameV = LinuxGlibGioBindings.variantNewString(iconPath.orEmpty()) ?: return null
            val emptyPixmaps = LinuxGlibGioBindings.variantNewArray(pixmapType, emptyList()) ?: return null
            val titleV = LinuxGlibGioBindings.variantNewString(title) ?: return null
            val descV = LinuxGlibGioBindings.variantNewString(description) ?: return null
            LinuxGlibGioBindings.variantNewTuple(listOf(iconNameV, emptyPixmaps, titleV, descV))
        } finally {
            LinuxGlibGioBindings.variantTypeFree(pixmapType)
        }
    }

    private fun currentStatus(): String =
        if (!badgeText.isNullOrBlank()) "NeedsAttention" else "Active"

    companion object {
        private const val SNI_OBJECT_PATH = "/StatusNotifierItem"
        private const val SNI_INTERFACE = "org.kde.StatusNotifierItem"
        private const val WATCHER_BUS_NAME = "org.kde.StatusNotifierWatcher"
        private const val WATCHER_OBJECT_PATH = "/StatusNotifierWatcher"
        private const val WATCHER_INTERFACE = "org.kde.StatusNotifierWatcher"
        private var trayCounter: Int = 0

        private const val SNI_INTROSPECTION_XML = """
<node>
  <interface name="org.kde.StatusNotifierItem">
    <method name="ContextMenu">
      <arg name="x" type="i" direction="in"/>
      <arg name="y" type="i" direction="in"/>
    </method>
    <method name="Activate">
      <arg name="x" type="i" direction="in"/>
      <arg name="y" type="i" direction="in"/>
    </method>
    <method name="SecondaryActivate">
      <arg name="x" type="i" direction="in"/>
      <arg name="y" type="i" direction="in"/>
    </method>
    <method name="Scroll">
      <arg name="delta" type="i" direction="in"/>
      <arg name="orientation" type="s" direction="in"/>
    </method>
    <signal name="NewTitle"/>
    <signal name="NewIcon"/>
    <signal name="NewAttentionIcon"/>
    <signal name="NewOverlayIcon"/>
    <signal name="NewToolTip"/>
    <signal name="NewStatus">
      <arg name="status" type="s"/>
    </signal>
    <property name="Category" type="s" access="read"/>
    <property name="Id" type="s" access="read"/>
    <property name="Title" type="s" access="read"/>
    <property name="Status" type="s" access="read"/>
    <property name="WindowId" type="u" access="read"/>
    <property name="IconName" type="s" access="read"/>
    <property name="IconThemePath" type="s" access="read"/>
    <property name="OverlayIconName" type="s" access="read"/>
    <property name="AttentionIconName" type="s" access="read"/>
    <property name="AttentionMovieName" type="s" access="read"/>
    <property name="ToolTip" type="(sa(iiay)ss)" access="read"/>
    <property name="ItemIsMenu" type="b" access="read"/>
  </interface>
</node>
"""
    }
}
