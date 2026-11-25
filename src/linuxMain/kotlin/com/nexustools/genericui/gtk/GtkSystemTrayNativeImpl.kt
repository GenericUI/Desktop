@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk

import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.shell.DesktopContextMenuNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopMenuItemNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopMenuNativeImpl
import com.nexustools.genericui.desktop.system.DesktopSystemTrayNativeImpl
import com.nexustools.genericui.image.ImageDecodeOptions
import com.nexustools.genericui.image.ImageLoader
import com.nexustools.genericui.image.ImageSource
import com.nexustools.genericui.linux.LinuxGlibGioBindings
import com.nexustools.genericui.linux.LinuxReentrantLock
import com.nexustools.genericui.nativeimpl.layout.DividerNativeImpl
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
    private var menuNodeInfo: COpaquePointer? = null
    private var registrationId: UInt = 0u
    private var menuRegistrationId: UInt = 0u
    private var busOwnerId: UInt = 0u
    private var vtablePtr: COpaquePointer? = null
    private var menuVtablePtr: COpaquePointer? = null
    private var selfRef: StableRef<GtkSystemTrayNativeImpl>? = null
    private var registeredBusName: String? = null
    private var menuRevision: UInt = 1u
    private var menuRootNode: DbusMenuNode = DbusMenuNode(id = 0, label = "", enabled = true, isSeparator = false, checkable = false, isRadio = false, checked = false, hasSubmenu = true, itemImpl = null, children = emptyList())
    private var menuNodesById: Map<Int, DbusMenuNode> = mapOf(0 to menuRootNode)

    init {
        activeTrayInstances.add(this)
    }

    override fun setIcon(iconPathOrUrl: String?) {
        super.setIcon(iconPathOrUrl)
        scheduleOrEmitDbusSignal("NewIcon")
    }

    override fun setTooltip(tooltip: String?) {
        super.setTooltip(tooltip)
        scheduleOrEmitDbusSignal("NewToolTip")
    }

    override fun setBadgeText(badge: String?) {
        super.setBadgeText(badge)
        scheduleOrEmitDbusSignal("NewTitle")
        scheduleOrEmitDbusSignal("NewStatus")
    }

    override fun setContextMenu(menuImpl: ContextMenuNativeImpl<*>?) {
        super.setContextMenu(menuImpl)
        rebuildMenuSnapshotLocked()
        ensureDbusRegisteredIfInteractive()
        emitMenuLayoutUpdated()
    }

    override fun setOnTrayIconClicked(listener: (() -> Unit)?) {
        super.setOnTrayIconClicked(listener)
        ensureDbusRegisteredIfInteractive()
    }

    override fun dispose() {
        activeTrayInstances.remove(this)
        super.dispose()
        var conn: COpaquePointer? = null
        var regId: UInt = 0u
        var mRegId: UInt = 0u
        var ownerId: UInt = 0u
        var info: COpaquePointer? = null
        var mInfo: COpaquePointer? = null
        var vtable: COpaquePointer? = null
        var mVtable: COpaquePointer? = null
        var ref: StableRef<GtkSystemTrayNativeImpl>? = null
        lock.withLock {
            conn = dbusConnection
            regId = registrationId
            mRegId = menuRegistrationId
            ownerId = busOwnerId
            info = nodeInfo
            mInfo = menuNodeInfo
            vtable = vtablePtr
            mVtable = menuVtablePtr
            ref = selfRef
            dbusConnection = null
            registrationId = 0u
            menuRegistrationId = 0u
            busOwnerId = 0u
            nodeInfo = null
            menuNodeInfo = null
            vtablePtr = null
            menuVtablePtr = null
            selfRef = null
            registeredBusName = null
            menuNodesById = mapOf(0 to menuRootNode)
        }
        if (conn != null && regId != 0u) {
            LinuxGlibGioBindings.dbusUnregisterObject(conn, regId)
        }
        if (conn != null && mRegId != 0u) {
            LinuxGlibGioBindings.dbusUnregisterObject(conn, mRegId)
        }
        if (ownerId != 0u) {
            LinuxGlibGioBindings.busUnownName(ownerId)
        }
        if (info != null) {
            LinuxGlibGioBindings.dbusNodeInfoUnref(info)
        }
        if (mInfo != null) {
            LinuxGlibGioBindings.dbusNodeInfoUnref(mInfo)
        }
        if (vtable != null) {
            LinuxGlibGioBindings.free(vtable)
        }
        if (mVtable != null) {
            LinuxGlibGioBindings.free(mVtable)
        }
        ref?.dispose()
        if (GtkApplicationNativeImpl.interactiveGtkGuiEnabled) {
            GtkNativeWindowHost.onTrayDisposed(hasRemainingActiveTrays = hasActiveTrayIconsInProcess())
            LinuxGlibGioBindings.mainContextWakeup(LinuxGlibGioBindings.mainContextDefault())
        }
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

        // Export com.canonical.dbusmenu on /MenuBar for StatusNotifierItem "Menu" property.
        var mInfo: COpaquePointer? = LinuxGlibGioBindings.dbusNodeInfoNewForXml(DBUSMENU_INTROSPECTION_XML)
        var mVtable: COpaquePointer? = null
        var mRegId: UInt = 0u
        val mIfaceInfo = mInfo?.let { LinuxGlibGioBindings.dbusNodeInfoInterfacesFirst(it) }
        if (mInfo != null && mIfaceInfo != null) {
            val menuVtable = LinuxGlibGioBindings.malloc0(88uL)
            if (menuVtable != null) {
                val mSlots = menuVtable.reinterpret<COpaquePointerVar>()
                val mMethodCb = staticCFunction { _: COpaquePointer?, _: CPointer<ByteVar>?, _: CPointer<ByteVar>?, _: CPointer<ByteVar>?, methodName: CPointer<ByteVar>?, parameters: COpaquePointer?, invocation: COpaquePointer?, userData: COpaquePointer? ->
                    val tray = userData?.asStableRef<GtkSystemTrayNativeImpl>()?.get()
                    if (tray != null && invocation != null) {
                        tray.handleDbusMenuMethodCall(methodName?.toKString().orEmpty(), parameters, invocation)
                    }
                }
                val mGetPropCb = staticCFunction { _: COpaquePointer?, _: CPointer<ByteVar>?, _: CPointer<ByteVar>?, _: CPointer<ByteVar>?, propertyName: CPointer<ByteVar>?, _: CPointer<COpaquePointerVar>?, userData: COpaquePointer? ->
                    val tray = userData?.asStableRef<GtkSystemTrayNativeImpl>()?.get()
                    tray?.handleDbusMenuGetProperty(propertyName?.toKString().orEmpty())
                }
                mSlots[0] = mMethodCb
                mSlots[1] = mGetPropCb
                mSlots[2] = null
                mRegId = LinuxGlibGioBindings.dbusRegisterObject(
                    connection = conn,
                    objectPath = DBUSMENU_OBJECT_PATH,
                    interfaceInfo = mIfaceInfo,
                    vtable = menuVtable,
                    userData = ref.asCPointer()
                )
                if (mRegId == 0u) {
                    LinuxGlibGioBindings.free(menuVtable)
                    LinuxGlibGioBindings.dbusNodeInfoUnref(mInfo)
                    mInfo = null
                } else {
                    mVtable = menuVtable
                }
            } else {
                LinuxGlibGioBindings.dbusNodeInfoUnref(mInfo)
                mInfo = null
            }
        } else if (mInfo != null) {
            LinuxGlibGioBindings.dbusNodeInfoUnref(mInfo)
            mInfo = null
        }

        rebuildMenuSnapshotLocked()

        val serviceName = "org.kde.StatusNotifierItem-${getpid()}-${(++trayCounter)}"
        val ownerId = LinuxGlibGioBindings.busOwnName(serviceName)
        lock.withLock {
            dbusConnection = conn
            nodeInfo = info
            menuNodeInfo = mInfo
            registrationId = regId
            menuRegistrationId = mRegId
            busOwnerId = ownerId
            vtablePtr = vtable
            menuVtablePtr = mVtable
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
            "IconName" -> LinuxGlibGioBindings.variantNewString(resolvedIconNameForSni())
            "IconPixmap" -> buildIconPixmapVariant()
            "IconThemePath" -> LinuxGlibGioBindings.variantNewString("")
            "OverlayIconName" -> LinuxGlibGioBindings.variantNewString("")
            "AttentionIconName" -> LinuxGlibGioBindings.variantNewString("")
            "AttentionMovieName" -> LinuxGlibGioBindings.variantNewString("")
            "Menu" -> LinuxGlibGioBindings.variantNewObjectPath(DBUSMENU_OBJECT_PATH)
            "ItemIsMenu" -> LinuxGlibGioBindings.variantNewBoolean(contextMenu != null && onClickedListener == null)
            "ToolTip" -> buildToolTipVariant(titleWithBadge, tooltipText.orEmpty())
            else -> null
        }
    }

    private fun resolvedIconNameForSni(): String {
        val raw = iconPath?.trim()?.takeIf { it.isNotEmpty() } ?: defaultIconPath
        return if (raw.contains('/') || raw.contains('\\')) {
            raw
        } else {
            raw.substringBeforeLast('.')
        }
    }

    private fun buildIconPixmapVariant(): COpaquePointer? {
        val pixmapType = LinuxGlibGioBindings.variantTypeNew("(iiay)") ?: return null
        return try {
            val raw = iconPath?.trim()?.takeIf { it.isNotEmpty() } ?: defaultIconPath
            val src = when {
                raw.startsWith("http://", ignoreCase = true) ||
                    raw.startsWith("https://", ignoreCase = true) ||
                    raw.startsWith("file://", ignoreCase = true) -> ImageSource.fromUrl(raw)
                raw.startsWith("/") -> ImageSource.fromFile(raw)
                else -> ImageSource.fromResource(raw)
            }
            val buffer = ImageLoader.activeNativeCodec?.decode(
                src,
                ImageDecodeOptions(targetWidth = 32, targetHeight = 32, antialiasing = true)
            )
            if (buffer == null) {
                LinuxGlibGioBindings.variantNewArray(pixmapType, emptyList())
            } else {
                val rgba = buffer.asRgba8888()
                val argbBytes = ByteArray(rgba.bytes.size)
                var i = 0
                val limit = rgba.bytes.size - 3
                while (i <= limit) {
                    argbBytes[i] = rgba.bytes[i + 3]
                    argbBytes[i + 1] = rgba.bytes[i]
                    argbBytes[i + 2] = rgba.bytes[i + 1]
                    argbBytes[i + 3] = rgba.bytes[i + 2]
                    i += 4
                }
                val wV = LinuxGlibGioBindings.variantNewInt32(rgba.width)
                val hV = LinuxGlibGioBindings.variantNewInt32(rgba.height)
                val ayV = LinuxGlibGioBindings.variantNewBytes(argbBytes)
                val tupleV = if (wV != null && hV != null && ayV != null) {
                    LinuxGlibGioBindings.variantNewTuple(listOf(wV, hV, ayV))
                } else null
                if (tupleV != null) {
                    LinuxGlibGioBindings.variantNewArray(pixmapType, listOf(tupleV))
                } else {
                    LinuxGlibGioBindings.variantNewArray(pixmapType, emptyList())
                }
            }
        } finally {
            LinuxGlibGioBindings.variantTypeFree(pixmapType)
        }
    }

    private fun emitMenuLayoutUpdated() {
        if (!GtkApplicationNativeImpl.interactiveGtkGuiEnabled || isDisposed) return
        val (conn, regId, rev) = lock.withLock {
            Triple(dbusConnection, menuRegistrationId, menuRevision)
        }
        if (conn == null || regId == 0u) return
        val revV = LinuxGlibGioBindings.variantNewUint32(rev) ?: return
        val parentV = LinuxGlibGioBindings.variantNewInt32(0) ?: return
        val params = LinuxGlibGioBindings.variantNewTuple(listOf(revV, parentV)) ?: return
        LinuxGlibGioBindings.dbusEmitSignal(
            connection = conn,
            objectPath = DBUSMENU_OBJECT_PATH,
            interfaceName = DBUSMENU_INTERFACE,
            signalName = "LayoutUpdated",
            parameters = params
        )
    }

    private fun rebuildMenuSnapshotLocked() {
        val desktopMenu = contextMenu as? DesktopContextMenuNativeImpl<*>
        val allNodes = linkedMapOf<Int, DbusMenuNode>()
        var nextId = 1
        fun buildChildren(parentWidget: DesktopNativeWidget): List<DbusMenuNode> {
            val result = mutableListOf<DbusMenuNode>()
            for (childWidget in parentWidget.children) {
                val impl = childWidget.attributes["nativeImpl"]
                when {
                    impl is DesktopMenuNativeImpl<*> -> {
                        val subId = nextId++
                        val subChildren = buildChildren(childWidget)
                        val node = DbusMenuNode(
                            id = subId,
                            label = impl.getLabel().ifBlank { "Submenu" },
                            enabled = true,
                            isSeparator = false,
                            checkable = false,
                            isRadio = false,
                            checked = false,
                            hasSubmenu = true,
                            itemImpl = null,
                            children = subChildren
                        )
                        allNodes[subId] = node
                        result.add(node)
                    }
                    impl is DesktopMenuItemNativeImpl<*> -> {
                        val itemId = nextId++
                        val node = DbusMenuNode(
                            id = itemId,
                            label = impl.getLabel(),
                            enabled = impl.isEnabled(),
                            isSeparator = false,
                            checkable = impl.checkable || impl.radio,
                            isRadio = impl.radio,
                            checked = impl.isChecked(),
                            hasSubmenu = false,
                            itemImpl = impl,
                            children = emptyList()
                        )
                        allNodes[itemId] = node
                        result.add(node)
                    }
                    impl is DividerNativeImpl<*> || childWidget.attributes["isMenuSeparator"] == true -> {
                        val sepId = nextId++
                        val node = DbusMenuNode(
                            id = sepId,
                            label = "",
                            enabled = true,
                            isSeparator = true,
                            checkable = false,
                            isRadio = false,
                            checked = false,
                            hasSubmenu = false,
                            itemImpl = null,
                            children = emptyList()
                        )
                        allNodes[sepId] = node
                        result.add(node)
                    }
                }
            }
            return result
        }
        val rootChildren = if (desktopMenu != null) buildChildren(desktopMenu.nativeWidget) else emptyList()
        val root = DbusMenuNode(
            id = 0,
            label = "",
            enabled = true,
            isSeparator = false,
            checkable = false,
            isRadio = false,
            checked = false,
            hasSubmenu = true,
            itemImpl = null,
            children = rootChildren
        )
        allNodes[0] = root
        lock.withLock {
            menuRevision++
            menuRootNode = root
            menuNodesById = allNodes
        }
    }

    private fun handleDbusMenuMethodCall(
        methodName: String,
        parameters: COpaquePointer?,
        invocation: COpaquePointer
    ) {
        when (methodName) {
            "GetLayout" -> {
                rebuildMenuSnapshotLocked()
                val parentIdV = LinuxGlibGioBindings.variantGetChildValue(parameters, 0)
                val depthV = LinuxGlibGioBindings.variantGetChildValue(parameters, 1)
                val propNamesV = LinuxGlibGioBindings.variantGetChildValue(parameters, 2)
                val parentId = LinuxGlibGioBindings.variantGetInt32(parentIdV)
                val depth = LinuxGlibGioBindings.variantGetInt32(depthV)
                val filter = extractStringArray(propNamesV)
                LinuxGlibGioBindings.variantUnref(parentIdV)
                LinuxGlibGioBindings.variantUnref(depthV)
                LinuxGlibGioBindings.variantUnref(propNamesV)

                val (rev, node) = lock.withLock {
                    menuRevision to (menuNodesById[parentId] ?: menuRootNode)
                }
                val revV = LinuxGlibGioBindings.variantNewUint32(rev)
                val layoutV = buildLayoutNode(node, depth, filter)
                val out = if (revV != null && layoutV != null) {
                    LinuxGlibGioBindings.variantNewTuple(listOf(revV, layoutV))
                } else null
                LinuxGlibGioBindings.dbusMethodReturnValue(invocation, out)
            }
            "GetGroupProperties" -> {
                rebuildMenuSnapshotLocked()
                val idsV = LinuxGlibGioBindings.variantGetChildValue(parameters, 0)
                val propNamesV = LinuxGlibGioBindings.variantGetChildValue(parameters, 1)
                val filter = extractStringArray(propNamesV)
                val entryType = LinuxGlibGioBindings.variantTypeNew("(ia{sv})")
                val entries = mutableListOf<COpaquePointer>()
                val nodesSnapshot = lock.withLock { menuNodesById }
                val count = LinuxGlibGioBindings.variantNChildren(idsV)
                for (i in 0 until count) {
                    val itemV = LinuxGlibGioBindings.variantGetChildValue(idsV, i)
                    val id = LinuxGlibGioBindings.variantGetInt32(itemV)
                    LinuxGlibGioBindings.variantUnref(itemV)
                    val node = nodesSnapshot[id] ?: continue
                    val idV = LinuxGlibGioBindings.variantNewInt32(id) ?: continue
                    val propsV = buildMenuPropertiesDict(node, filter) ?: continue
                    LinuxGlibGioBindings.variantNewTuple(listOf(idV, propsV))?.let { entries.add(it) }
                }
                LinuxGlibGioBindings.variantUnref(idsV)
                LinuxGlibGioBindings.variantUnref(propNamesV)
                val arrV = if (entryType != null) {
                    try {
                        LinuxGlibGioBindings.variantNewArray(entryType, entries)
                    } finally {
                        LinuxGlibGioBindings.variantTypeFree(entryType)
                    }
                } else null
                val out = arrV?.let { LinuxGlibGioBindings.variantNewTuple(listOf(it)) }
                LinuxGlibGioBindings.dbusMethodReturnValue(invocation, out)
            }
            "GetProperty" -> {
                val idV = LinuxGlibGioBindings.variantGetChildValue(parameters, 0)
                val nameV = LinuxGlibGioBindings.variantGetChildValue(parameters, 1)
                val id = LinuxGlibGioBindings.variantGetInt32(idV)
                val propName = LinuxGlibGioBindings.variantGetString(nameV).orEmpty()
                LinuxGlibGioBindings.variantUnref(idV)
                LinuxGlibGioBindings.variantUnref(nameV)
                val node = lock.withLock { menuNodesById[id] }
                val propVal = node?.let { buildSingleMenuPropertyValue(it, propName) }
                    ?: LinuxGlibGioBindings.variantNewString("")
                val boxed = propVal?.let { LinuxGlibGioBindings.variantNewVariant(it) }
                val out = boxed?.let { LinuxGlibGioBindings.variantNewTuple(listOf(it)) }
                LinuxGlibGioBindings.dbusMethodReturnValue(invocation, out)
            }
            "Event" -> {
                val idV = LinuxGlibGioBindings.variantGetChildValue(parameters, 0)
                val eventIdV = LinuxGlibGioBindings.variantGetChildValue(parameters, 1)
                val id = LinuxGlibGioBindings.variantGetInt32(idV)
                val eventId = LinuxGlibGioBindings.variantGetString(eventIdV).orEmpty()
                LinuxGlibGioBindings.variantUnref(idV)
                LinuxGlibGioBindings.variantUnref(eventIdV)
                LinuxGlibGioBindings.dbusMethodReturnValue(invocation, null)
                dispatchMenuEvent(id, eventId)
            }
            "EventGroup" -> {
                val eventsV = LinuxGlibGioBindings.variantGetChildValue(parameters, 0)
                val count = LinuxGlibGioBindings.variantNChildren(eventsV)
                for (i in 0 until count) {
                    val ev = LinuxGlibGioBindings.variantGetChildValue(eventsV, i)
                    val idV = LinuxGlibGioBindings.variantGetChildValue(ev, 0)
                    val eventIdV = LinuxGlibGioBindings.variantGetChildValue(ev, 1)
                    val id = LinuxGlibGioBindings.variantGetInt32(idV)
                    val eventId = LinuxGlibGioBindings.variantGetString(eventIdV).orEmpty()
                    LinuxGlibGioBindings.variantUnref(idV)
                    LinuxGlibGioBindings.variantUnref(eventIdV)
                    LinuxGlibGioBindings.variantUnref(ev)
                    dispatchMenuEvent(id, eventId)
                }
                LinuxGlibGioBindings.variantUnref(eventsV)
                val uType = LinuxGlibGioBindings.variantTypeNew("u")
                val emptyErrors = if (uType != null) {
                    try {
                        LinuxGlibGioBindings.variantNewArray(uType, emptyList())
                    } finally {
                        LinuxGlibGioBindings.variantTypeFree(uType)
                    }
                } else null
                val out = emptyErrors?.let { LinuxGlibGioBindings.variantNewTuple(listOf(it)) }
                LinuxGlibGioBindings.dbusMethodReturnValue(invocation, out)
            }
            "AboutToShow" -> {
                rebuildMenuSnapshotLocked()
                val bV = LinuxGlibGioBindings.variantNewBoolean(true)
                val out = bV?.let { LinuxGlibGioBindings.variantNewTuple(listOf(it)) }
                LinuxGlibGioBindings.dbusMethodReturnValue(invocation, out)
            }
            "AboutToShowGroup" -> {
                rebuildMenuSnapshotLocked()
                val iType = LinuxGlibGioBindings.variantTypeNew("i")
                val emptyA = if (iType != null) LinuxGlibGioBindings.variantNewArray(iType, emptyList()) else null
                val emptyB = if (iType != null) LinuxGlibGioBindings.variantNewArray(iType, emptyList()) else null
                if (iType != null) LinuxGlibGioBindings.variantTypeFree(iType)
                val out = if (emptyA != null && emptyB != null) {
                    LinuxGlibGioBindings.variantNewTuple(listOf(emptyA, emptyB))
                } else null
                LinuxGlibGioBindings.dbusMethodReturnValue(invocation, out)
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

    private fun handleDbusMenuGetProperty(propertyName: String): COpaquePointer? = when (propertyName) {
        "Version" -> LinuxGlibGioBindings.variantNewUint32(4u)
        "TextDirection" -> LinuxGlibGioBindings.variantNewString("ltr")
        "Status" -> LinuxGlibGioBindings.variantNewString("normal")
        "IconThemePath" -> {
            val sType = LinuxGlibGioBindings.variantTypeNew("s")
            try {
                if (sType != null) LinuxGlibGioBindings.variantNewArray(sType, emptyList()) else null
            } finally {
                if (sType != null) LinuxGlibGioBindings.variantTypeFree(sType)
            }
        }
        else -> null
    }

    private fun dispatchMenuEvent(id: Int, eventId: String) {
        if (eventId != "clicked") return
        val node = lock.withLock { menuNodesById[id] } ?: return
        val impl = node.itemImpl ?: return
        GenericUiThreading.runOnLogic {
            impl.dispatchClick()
            if (impl.checkable || impl.radio) {
                rebuildMenuSnapshotLocked()
                emitMenuLayoutUpdated()
            }
        }
    }

    private fun extractStringArray(variant: COpaquePointer?): Set<String> {
        val count = LinuxGlibGioBindings.variantNChildren(variant)
        if (count <= 0) return emptySet()
        val result = linkedSetOf<String>()
        for (i in 0 until count) {
            val child = LinuxGlibGioBindings.variantGetChildValue(variant, i)
            val s = LinuxGlibGioBindings.variantGetString(child)
            if (!s.isNullOrEmpty()) result.add(s)
            LinuxGlibGioBindings.variantUnref(child)
        }
        return result
    }

    private fun buildSingleMenuPropertyValue(node: DbusMenuNode, propName: String): COpaquePointer? = when (propName) {
        "label" -> LinuxGlibGioBindings.variantNewString(node.label)
        "enabled" -> LinuxGlibGioBindings.variantNewBoolean(node.enabled)
        "visible" -> LinuxGlibGioBindings.variantNewBoolean(true)
        "type" -> if (node.isSeparator) LinuxGlibGioBindings.variantNewString("separator") else null
        "children-display" -> if (node.hasSubmenu) LinuxGlibGioBindings.variantNewString("submenu") else null
        "toggle-type" -> if (node.checkable) {
            LinuxGlibGioBindings.variantNewString(if (node.isRadio) "radio" else "checkmark")
        } else null
        "toggle-state" -> if (node.checkable) {
            LinuxGlibGioBindings.variantNewInt32(if (node.checked) 1 else 0)
        } else null
        else -> null
    }

    private fun buildMenuPropertiesDict(node: DbusMenuNode, filter: Set<String>): COpaquePointer? {
        val svType = LinuxGlibGioBindings.variantTypeNew("{sv}") ?: return null
        return try {
            val entries = mutableListOf<COpaquePointer>()
            fun maybeAdd(name: String, rawValFactory: () -> COpaquePointer?) {
                if (filter.isNotEmpty() && name !in filter) return
                val raw = rawValFactory() ?: return
                val boxed = LinuxGlibGioBindings.variantNewVariant(raw) ?: return
                val k = LinuxGlibGioBindings.variantNewString(name) ?: return
                LinuxGlibGioBindings.variantNewDictEntry(k, boxed)?.let { entries.add(it) }
            }
            if (node.id != 0) {
                if (node.isSeparator) {
                    maybeAdd("type") { LinuxGlibGioBindings.variantNewString("separator") }
                    maybeAdd("visible") { LinuxGlibGioBindings.variantNewBoolean(true) }
                } else {
                    maybeAdd("label") { LinuxGlibGioBindings.variantNewString(node.label) }
                    maybeAdd("enabled") { LinuxGlibGioBindings.variantNewBoolean(node.enabled) }
                    maybeAdd("visible") { LinuxGlibGioBindings.variantNewBoolean(true) }
                    if (node.hasSubmenu) {
                        maybeAdd("children-display") { LinuxGlibGioBindings.variantNewString("submenu") }
                    }
                    if (node.checkable) {
                        maybeAdd("toggle-type") {
                            LinuxGlibGioBindings.variantNewString(if (node.isRadio) "radio" else "checkmark")
                        }
                        maybeAdd("toggle-state") {
                            LinuxGlibGioBindings.variantNewInt32(if (node.checked) 1 else 0)
                        }
                    }
                }
            } else {
                maybeAdd("children-display") { LinuxGlibGioBindings.variantNewString("submenu") }
            }
            LinuxGlibGioBindings.variantNewArray(svType, entries)
        } finally {
            LinuxGlibGioBindings.variantTypeFree(svType)
        }
    }

    private fun buildLayoutNode(node: DbusMenuNode, depth: Int, filter: Set<String>): COpaquePointer? {
        val idV = LinuxGlibGioBindings.variantNewInt32(node.id) ?: return null
        val propsV = buildMenuPropertiesDict(node, filter) ?: return null
        val vType = LinuxGlibGioBindings.variantTypeNew("v") ?: return null
        val childVariants = if (depth == 0) {
            emptyList()
        } else {
            val nextDepth = if (depth < 0) -1 else depth - 1
            node.children.mapNotNull { child ->
                buildLayoutNode(child, nextDepth, filter)?.let { LinuxGlibGioBindings.variantNewVariant(it) }
            }
        }
        val childrenArr = try {
            LinuxGlibGioBindings.variantNewArray(vType, childVariants)
        } finally {
            LinuxGlibGioBindings.variantTypeFree(vType)
        } ?: return null
        return LinuxGlibGioBindings.variantNewTuple(listOf(idV, propsV, childrenArr))
    }

    private class DbusMenuNode(
        val id: Int,
        val label: String,
        val enabled: Boolean,
        val isSeparator: Boolean,
        val checkable: Boolean,
        val isRadio: Boolean,
        val checked: Boolean,
        val hasSubmenu: Boolean,
        val itemImpl: DesktopMenuItemNativeImpl<*>?,
        val children: List<DbusMenuNode>
    )

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
        private const val DBUSMENU_OBJECT_PATH = "/MenuBar"
        private const val DBUSMENU_INTERFACE = "com.canonical.dbusmenu"
        private const val WATCHER_BUS_NAME = "org.kde.StatusNotifierWatcher"
        private const val WATCHER_OBJECT_PATH = "/StatusNotifierWatcher"
        private const val WATCHER_INTERFACE = "org.kde.StatusNotifierWatcher"
        private var trayCounter: Int = 0
        private val activeTrayInstances = mutableListOf<GtkSystemTrayNativeImpl>()

        internal fun hasActiveTrayIconsInProcess(): Boolean =
            activeTrayInstances.any { !it.isDisposed }

        internal fun hasActiveTray(): Boolean =
            hasActiveTrayIconsInProcess()

        internal fun ensureAllRegisteredIfInteractive() {
            for (tray in activeTrayInstances.toList()) {
                if (!tray.isDisposed) {
                    tray.ensureDbusRegisteredIfInteractive()
                }
            }
        }

        internal fun disposeAll() {
            for (tray in activeTrayInstances.toList()) {
                tray.dispose()
            }
        }

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
    <property name="IconPixmap" type="a(iiay)" access="read"/>
    <property name="IconThemePath" type="s" access="read"/>
    <property name="OverlayIconName" type="s" access="read"/>
    <property name="AttentionIconName" type="s" access="read"/>
    <property name="AttentionMovieName" type="s" access="read"/>
    <property name="ToolTip" type="(sa(iiay)ss)" access="read"/>
    <property name="Menu" type="o" access="read"/>
    <property name="ItemIsMenu" type="b" access="read"/>
  </interface>
</node>
"""

        private const val DBUSMENU_INTROSPECTION_XML = """
<node>
  <interface name="com.canonical.dbusmenu">
    <method name="GetLayout">
      <arg name="parentId" type="i" direction="in"/>
      <arg name="recursionDepth" type="i" direction="in"/>
      <arg name="propertyNames" type="as" direction="in"/>
      <arg name="revision" type="u" direction="out"/>
      <arg name="layout" type="(ia{sv}av)" direction="out"/>
    </method>
    <method name="GetGroupProperties">
      <arg name="ids" type="ai" direction="in"/>
      <arg name="propertyNames" type="as" direction="in"/>
      <arg name="properties" type="a(ia{sv})" direction="out"/>
    </method>
    <method name="GetProperty">
      <arg name="id" type="i" direction="in"/>
      <arg name="name" type="s" direction="in"/>
      <arg name="value" type="v" direction="out"/>
    </method>
    <method name="Event">
      <arg name="id" type="i" direction="in"/>
      <arg name="eventId" type="s" direction="in"/>
      <arg name="data" type="v" direction="in"/>
      <arg name="timestamp" type="u" direction="in"/>
    </method>
    <method name="EventGroup">
      <arg name="events" type="a(isvu)" direction="in"/>
      <arg name="idErrors" type="au" direction="out"/>
    </method>
    <method name="AboutToShow">
      <arg name="id" type="i" direction="in"/>
      <arg name="needUpdate" type="b" direction="out"/>
    </method>
    <method name="AboutToShowGroup">
      <arg name="ids" type="ai" direction="in"/>
      <arg name="updatesNeeded" type="ai" direction="out"/>
      <arg name="idErrors" type="ai" direction="out"/>
    </method>
    <signal name="LayoutUpdated">
      <arg name="revision" type="u"/>
      <arg name="parent" type="i"/>
    </signal>
    <property name="Version" type="u" access="read"/>
    <property name="TextDirection" type="s" access="read"/>
    <property name="Status" type="s" access="read"/>
    <property name="IconThemePath" type="as" access="read"/>
  </interface>
</node>
"""
    }
}
