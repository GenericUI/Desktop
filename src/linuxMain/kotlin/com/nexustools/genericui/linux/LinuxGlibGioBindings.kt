@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.linux

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value

internal object LinuxGlibGioBindings {
    const val G_BUS_TYPE_SESSION = 2
    const val G_DBUS_CALL_FLAGS_NONE = 0
    const val G_CONNECT_AFTER = 1u
    const val G_SOURCE_REMOVE = 0
    const val G_SOURCE_CONTINUE = 1

    private val lock = LinuxReentrantLock()
    private var loaded = false
    private var glibAvailable = false
    private var gobjectAvailable = false
    private var gioAvailable = false

    private var fnFree: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnMalloc0: CPointer<CFunction<(ULong) -> COpaquePointer?>>? = null
    private var fnBytesNew: CPointer<CFunction<(COpaquePointer?, ULong) -> COpaquePointer?>>? = null
    private var fnBytesUnref: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnErrorFree: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnMainContextDefault: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnMainContextIteration: CPointer<CFunction<(COpaquePointer?, Int) -> Int>>? = null
    private var fnMainContextWakeup: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnIdleAdd: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> UInt>>? = null
    private var fnTimeoutAdd: CPointer<CFunction<(UInt, COpaquePointer?, COpaquePointer?) -> UInt>>? = null
    private var fnTimeoutAddFull: CPointer<CFunction<(Int, UInt, COpaquePointer?, COpaquePointer?, COpaquePointer?) -> UInt>>? = null
    private var fnSourceRemove: CPointer<CFunction<(UInt) -> Int>>? = null

    private var fnVariantTypeNew: CPointer<CFunction<(CPointer<ByteVar>?) -> COpaquePointer?>>? = null
    private var fnVariantTypeFree: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnVariantNewString: CPointer<CFunction<(CPointer<ByteVar>?) -> COpaquePointer?>>? = null
    private var fnVariantNewByte: CPointer<CFunction<(UByte) -> COpaquePointer?>>? = null
    private var fnVariantNewUint32: CPointer<CFunction<(UInt) -> COpaquePointer?>>? = null
    private var fnVariantNewInt32: CPointer<CFunction<(Int) -> COpaquePointer?>>? = null
    private var fnVariantNewInt16: CPointer<CFunction<(Short) -> COpaquePointer?>>? = null
    private var fnVariantNewBoolean: CPointer<CFunction<(Int) -> COpaquePointer?>>? = null
    private var fnVariantNewVariant: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnVariantNewArray: CPointer<CFunction<(COpaquePointer?, CPointer<COpaquePointerVar>?, ULong) -> COpaquePointer?>>? = null
    private var fnVariantNewTuple: CPointer<CFunction<(CPointer<COpaquePointerVar>?, ULong) -> COpaquePointer?>>? = null
    private var fnVariantNewDictEntry: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnVariantGetChildValue: CPointer<CFunction<(COpaquePointer?, ULong) -> COpaquePointer?>>? = null
    private var fnVariantGetUint32: CPointer<CFunction<(COpaquePointer?) -> UInt>>? = null
    private var fnVariantGetString: CPointer<CFunction<(COpaquePointer?, CPointer<ULongVar>?) -> CPointer<ByteVar>?>>? = null
    private var fnVariantUnref: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null

    private var fnObjectUnref: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnObjectRefSink: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnSignalConnectData: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?, COpaquePointer?, COpaquePointer?, COpaquePointer?, UInt) -> ULong>>? = null
    private var fnSignalHandlerDisconnect: CPointer<CFunction<(COpaquePointer?, ULong) -> Unit>>? = null
    private var fnTypeCheckInstanceIsA: CPointer<CFunction<(COpaquePointer?, ULong) -> Int>>? = null

    private var fnNetworkMonitorGetDefault: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnNetworkMonitorGetAvailable: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnBusGetSync: CPointer<CFunction<(Int, COpaquePointer?, CPointer<COpaquePointerVar>?) -> COpaquePointer?>>? = null
    private var fnBusOwnName: CPointer<CFunction<(Int, CPointer<ByteVar>?, UInt, COpaquePointer?, COpaquePointer?, COpaquePointer?, COpaquePointer?, COpaquePointer?) -> UInt>>? = null
    private var fnBusUnownName: CPointer<CFunction<(UInt) -> Unit>>? = null
    private var fnDbusCallSync: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?, CPointer<ByteVar>?, CPointer<ByteVar>?, CPointer<ByteVar>?, COpaquePointer?, COpaquePointer?, Int, Int, COpaquePointer?, CPointer<COpaquePointerVar>?) -> COpaquePointer?>>? = null
    private var fnDbusSignalSubscribe: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?, CPointer<ByteVar>?, CPointer<ByteVar>?, CPointer<ByteVar>?, CPointer<ByteVar>?, UInt, COpaquePointer?, COpaquePointer?, COpaquePointer?) -> UInt>>? = null
    private var fnDbusSignalUnsubscribe: CPointer<CFunction<(COpaquePointer?, UInt) -> Unit>>? = null
    private var fnDbusRegisterObject: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?, COpaquePointer?, COpaquePointer?, COpaquePointer?, COpaquePointer?, CPointer<COpaquePointerVar>?) -> UInt>>? = null
    private var fnDbusUnregisterObject: CPointer<CFunction<(COpaquePointer?, UInt) -> Int>>? = null
    private var fnDbusEmitSignal: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?, CPointer<ByteVar>?, CPointer<ByteVar>?, CPointer<ByteVar>?, COpaquePointer?, CPointer<COpaquePointerVar>?) -> Int>>? = null
    private var fnDbusNodeInfoNewForXml: CPointer<CFunction<(CPointer<ByteVar>?, CPointer<COpaquePointerVar>?) -> COpaquePointer?>>? = null
    private var fnDbusNodeInfoUnref: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnDbusMethodReturnValue: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnDbusMethodReturnError: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?, CPointer<ByteVar>?) -> Unit>>? = null

    private var fnListModelGetNItems: CPointer<CFunction<(COpaquePointer?) -> UInt>>? = null
    private var fnListModelGetItem: CPointer<CFunction<(COpaquePointer?, UInt) -> COpaquePointer?>>? = null
    private var fnListStoreNew: CPointer<CFunction<(ULong) -> COpaquePointer?>>? = null
    private var fnListStoreAppend: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnFileNewForPath: CPointer<CFunction<(CPointer<ByteVar>?) -> COpaquePointer?>>? = null
    private var fnFileGetPath: CPointer<CFunction<(COpaquePointer?) -> CPointer<ByteVar>?>>? = null

    fun ensureLoaded(): Boolean = lock.withLock {
        if (loaded) return glibAvailable && gobjectAvailable && gioAvailable
        loaded = true

        val glib = LinuxDynamicLibraryLoader.openFirst("libglib-2.0.so.0", "libglib-2.0.so", global = true)
        val gobject = LinuxDynamicLibraryLoader.openFirst("libgobject-2.0.so.0", "libgobject-2.0.so", global = true)
        val gio = LinuxDynamicLibraryLoader.openFirst("libgio-2.0.so.0", "libgio-2.0.so", global = true)

        if (glib != null) {
            fnFree = LinuxDynamicLibraryLoader.symbol(glib, "g_free")?.reinterpret()
            fnMalloc0 = LinuxDynamicLibraryLoader.symbol(glib, "g_malloc0")?.reinterpret()
            fnBytesNew = LinuxDynamicLibraryLoader.symbol(glib, "g_bytes_new")?.reinterpret()
            fnBytesUnref = LinuxDynamicLibraryLoader.symbol(glib, "g_bytes_unref")?.reinterpret()
            fnErrorFree = LinuxDynamicLibraryLoader.symbol(glib, "g_error_free")?.reinterpret()
            fnMainContextDefault = LinuxDynamicLibraryLoader.symbol(glib, "g_main_context_default")?.reinterpret()
            fnMainContextIteration = LinuxDynamicLibraryLoader.symbol(glib, "g_main_context_iteration")?.reinterpret()
            fnMainContextWakeup = LinuxDynamicLibraryLoader.symbol(glib, "g_main_context_wakeup")?.reinterpret()
            fnIdleAdd = LinuxDynamicLibraryLoader.symbol(glib, "g_idle_add")?.reinterpret()
            fnTimeoutAdd = LinuxDynamicLibraryLoader.symbol(glib, "g_timeout_add")?.reinterpret()
            fnTimeoutAddFull = LinuxDynamicLibraryLoader.symbol(glib, "g_timeout_add_full")?.reinterpret()
            fnSourceRemove = LinuxDynamicLibraryLoader.symbol(glib, "g_source_remove")?.reinterpret()
            fnVariantTypeNew = LinuxDynamicLibraryLoader.symbol(glib, "g_variant_type_new")?.reinterpret()
            fnVariantTypeFree = LinuxDynamicLibraryLoader.symbol(glib, "g_variant_type_free")?.reinterpret()
            fnVariantNewString = LinuxDynamicLibraryLoader.symbol(glib, "g_variant_new_string")?.reinterpret()
            fnVariantNewByte = LinuxDynamicLibraryLoader.symbol(glib, "g_variant_new_byte")?.reinterpret()
            fnVariantNewUint32 = LinuxDynamicLibraryLoader.symbol(glib, "g_variant_new_uint32")?.reinterpret()
            fnVariantNewInt32 = LinuxDynamicLibraryLoader.symbol(glib, "g_variant_new_int32")?.reinterpret()
            fnVariantNewInt16 = LinuxDynamicLibraryLoader.symbol(glib, "g_variant_new_int16")?.reinterpret()
            fnVariantNewBoolean = LinuxDynamicLibraryLoader.symbol(glib, "g_variant_new_boolean")?.reinterpret()
            fnVariantNewVariant = LinuxDynamicLibraryLoader.symbol(glib, "g_variant_new_variant")?.reinterpret()
            fnVariantNewArray = LinuxDynamicLibraryLoader.symbol(glib, "g_variant_new_array")?.reinterpret()
            fnVariantNewTuple = LinuxDynamicLibraryLoader.symbol(glib, "g_variant_new_tuple")?.reinterpret()
            fnVariantNewDictEntry = LinuxDynamicLibraryLoader.symbol(glib, "g_variant_new_dict_entry")?.reinterpret()
            fnVariantGetChildValue = LinuxDynamicLibraryLoader.symbol(glib, "g_variant_get_child_value")?.reinterpret()
            fnVariantGetUint32 = LinuxDynamicLibraryLoader.symbol(glib, "g_variant_get_uint32")?.reinterpret()
            fnVariantGetString = LinuxDynamicLibraryLoader.symbol(glib, "g_variant_get_string")?.reinterpret()
            fnVariantUnref = LinuxDynamicLibraryLoader.symbol(glib, "g_variant_unref")?.reinterpret()
            glibAvailable = fnFree != null && fnMainContextDefault != null && fnMainContextIteration != null
        }

        if (gobject != null) {
            fnObjectUnref = LinuxDynamicLibraryLoader.symbol(gobject, "g_object_unref")?.reinterpret()
            fnObjectRefSink = LinuxDynamicLibraryLoader.symbol(gobject, "g_object_ref_sink")?.reinterpret()
            fnSignalConnectData = LinuxDynamicLibraryLoader.symbol(gobject, "g_signal_connect_data")?.reinterpret()
            fnSignalHandlerDisconnect = LinuxDynamicLibraryLoader.symbol(gobject, "g_signal_handler_disconnect")?.reinterpret()
            fnTypeCheckInstanceIsA = LinuxDynamicLibraryLoader.symbol(gobject, "g_type_check_instance_is_a")?.reinterpret()
            gobjectAvailable = fnObjectUnref != null && fnSignalConnectData != null
        }

        if (gio != null) {
            fnNetworkMonitorGetDefault = LinuxDynamicLibraryLoader.symbol(gio, "g_network_monitor_get_default")?.reinterpret()
            fnNetworkMonitorGetAvailable = LinuxDynamicLibraryLoader.symbol(gio, "g_network_monitor_get_network_available")?.reinterpret()
            fnBusGetSync = LinuxDynamicLibraryLoader.symbol(gio, "g_bus_get_sync")?.reinterpret()
            fnBusOwnName = LinuxDynamicLibraryLoader.symbol(gio, "g_bus_own_name")?.reinterpret()
            fnBusUnownName = LinuxDynamicLibraryLoader.symbol(gio, "g_bus_unown_name")?.reinterpret()
            fnDbusCallSync = LinuxDynamicLibraryLoader.symbol(gio, "g_dbus_connection_call_sync")?.reinterpret()
            fnDbusSignalSubscribe = LinuxDynamicLibraryLoader.symbol(gio, "g_dbus_connection_signal_subscribe")?.reinterpret()
            fnDbusSignalUnsubscribe = LinuxDynamicLibraryLoader.symbol(gio, "g_dbus_connection_signal_unsubscribe")?.reinterpret()
            fnDbusRegisterObject = LinuxDynamicLibraryLoader.symbol(gio, "g_dbus_connection_register_object")?.reinterpret()
            fnDbusUnregisterObject = LinuxDynamicLibraryLoader.symbol(gio, "g_dbus_connection_unregister_object")?.reinterpret()
            fnDbusEmitSignal = LinuxDynamicLibraryLoader.symbol(gio, "g_dbus_connection_emit_signal")?.reinterpret()
            fnDbusNodeInfoNewForXml = LinuxDynamicLibraryLoader.symbol(gio, "g_dbus_node_info_new_for_xml")?.reinterpret()
            fnDbusNodeInfoUnref = LinuxDynamicLibraryLoader.symbol(gio, "g_dbus_node_info_unref")?.reinterpret()
            fnDbusMethodReturnValue = LinuxDynamicLibraryLoader.symbol(gio, "g_dbus_method_invocation_return_value")?.reinterpret()
            fnDbusMethodReturnError = LinuxDynamicLibraryLoader.symbol(gio, "g_dbus_method_invocation_return_dbus_error")?.reinterpret()
            fnListModelGetNItems = LinuxDynamicLibraryLoader.symbol(gio, "g_list_model_get_n_items")?.reinterpret()
            fnListModelGetItem = LinuxDynamicLibraryLoader.symbol(gio, "g_list_model_get_item")?.reinterpret()
            fnListStoreNew = LinuxDynamicLibraryLoader.symbol(gio, "g_list_store_new")?.reinterpret()
            fnListStoreAppend = LinuxDynamicLibraryLoader.symbol(gio, "g_list_store_append")?.reinterpret()
            fnFileNewForPath = LinuxDynamicLibraryLoader.symbol(gio, "g_file_new_for_path")?.reinterpret()
            fnFileGetPath = LinuxDynamicLibraryLoader.symbol(gio, "g_file_get_path")?.reinterpret()
            gioAvailable = fnNetworkMonitorGetDefault != null && fnBusGetSync != null
        }

        glibAvailable && gobjectAvailable && gioAvailable
    }

    fun free(ptr: COpaquePointer?) {
        if (ptr != null) fnFree?.invoke(ptr)
    }

    fun malloc0(bytes: ULong): COpaquePointer? = fnMalloc0?.invoke(bytes)

    fun bytesNew(bytes: ByteArray): COpaquePointer? {
        if (!ensureLoaded()) return null
        return bytes.usePinned { pinned ->
            val ptr = if (bytes.isNotEmpty()) pinned.addressOf(0) else null
            fnBytesNew?.invoke(ptr, bytes.size.convert())
        }
    }

    fun bytesUnref(bytesObj: COpaquePointer?) {
        if (bytesObj != null) fnBytesUnref?.invoke(bytesObj)
    }

    fun readAndFreeError(errVarPtr: CPointer<COpaquePointerVar>): String? {
        val errPtr = errVarPtr.pointed.value ?: return null
        val msgPtr = interpretCPointer<CPointerVar<ByteVar>>(errPtr.rawValue + 8L)?.pointed?.value
        val msg = msgPtr?.toKString() ?: "GLib error"
        fnErrorFree?.invoke(errPtr)
        errVarPtr.pointed.value = null
        return msg
    }

    fun mainContextDefault(): COpaquePointer? = if (ensureLoaded()) fnMainContextDefault?.invoke() else null
    fun mainContextIteration(ctx: COpaquePointer?, mayBlock: Boolean): Boolean =
        (fnMainContextIteration?.invoke(ctx, if (mayBlock) 1 else 0) ?: 0) != 0

    fun mainContextWakeup(ctx: COpaquePointer?) {
        fnMainContextWakeup?.invoke(ctx)
    }

    fun idleAdd(callback: COpaquePointer, userData: COpaquePointer?): UInt =
        if (ensureLoaded()) fnIdleAdd?.invoke(callback, userData) ?: 0u else 0u

    fun timeoutAdd(intervalMs: UInt, callback: COpaquePointer, userData: COpaquePointer?): UInt =
        if (ensureLoaded()) fnTimeoutAdd?.invoke(intervalMs, callback, userData) ?: 0u else 0u

    fun timeoutAddFull(
        intervalMs: UInt,
        callback: COpaquePointer,
        userData: COpaquePointer?,
        destroyNotify: COpaquePointer?
    ): UInt = if (ensureLoaded()) {
        fnTimeoutAddFull?.invoke(0, intervalMs, callback, userData, destroyNotify)
            ?: fnTimeoutAdd?.invoke(intervalMs, callback, userData)
            ?: 0u
    } else 0u

    fun sourceRemove(sourceId: UInt): Boolean =
        if (sourceId != 0u) (fnSourceRemove?.invoke(sourceId) ?: 0) != 0 else false

    fun objectUnref(obj: COpaquePointer?) {
        if (obj != null) fnObjectUnref?.invoke(obj)
    }

    fun objectRefSink(obj: COpaquePointer?): COpaquePointer? =
        if (obj != null) fnObjectRefSink?.invoke(obj) else null

    fun signalConnect(
        instance: COpaquePointer,
        signalName: String,
        handler: COpaquePointer,
        userData: COpaquePointer?,
        destroyNotify: COpaquePointer? = null,
        flags: UInt = 0u
    ): ULong = memScoped {
        fnSignalConnectData?.invoke(instance, signalName.cstr.ptr, handler, userData, destroyNotify, flags) ?: 0uL
    }

    fun signalHandlerDisconnect(instance: COpaquePointer?, handlerId: ULong) {
        if (instance != null && handlerId != 0uL) {
            fnSignalHandlerDisconnect?.invoke(instance, handlerId)
        }
    }

    fun typeCheckInstanceIsA(instance: COpaquePointer?, ifaceType: ULong): Boolean =
        instance != null && ifaceType != 0uL && (fnTypeCheckInstanceIsA?.invoke(instance, ifaceType) ?: 0) != 0

    fun networkMonitorGetDefault(): COpaquePointer? =
        if (ensureLoaded()) fnNetworkMonitorGetDefault?.invoke() else null

    fun networkMonitorGetAvailable(monitor: COpaquePointer?): Boolean =
        monitor != null && (fnNetworkMonitorGetAvailable?.invoke(monitor) ?: 0) != 0

    fun variantTypeNew(typeString: String): COpaquePointer? = memScoped {
        fnVariantTypeNew?.invoke(typeString.cstr.ptr)
    }

    fun variantTypeFree(vtype: COpaquePointer?) {
        if (vtype != null) fnVariantTypeFree?.invoke(vtype)
    }

    fun variantNewString(value: String): COpaquePointer? = memScoped {
        fnVariantNewString?.invoke(value.cstr.ptr)
    }

    fun variantNewByte(value: UByte): COpaquePointer? = fnVariantNewByte?.invoke(value)
    fun variantNewUint32(value: UInt): COpaquePointer? = fnVariantNewUint32?.invoke(value)
    fun variantNewInt32(value: Int): COpaquePointer? = fnVariantNewInt32?.invoke(value)
    fun variantNewInt16(value: Short): COpaquePointer? = fnVariantNewInt16?.invoke(value)
    fun variantNewBoolean(value: Boolean): COpaquePointer? = fnVariantNewBoolean?.invoke(if (value) 1 else 0)
    fun variantNewVariant(child: COpaquePointer?): COpaquePointer? = child?.let { fnVariantNewVariant?.invoke(it) }

    fun variantNewArray(childType: COpaquePointer?, children: List<COpaquePointer>): COpaquePointer? = memScoped {
        val fn = fnVariantNewArray ?: return@memScoped null
        if (children.isEmpty()) {
            fn.invoke(childType, null, 0.convert())
        } else {
            val arr = allocArray<COpaquePointerVar>(children.size)
            for (i in children.indices) {
                arr[i] = children[i]
            }
            fn.invoke(childType, arr, children.size.convert())
        }
    }

    fun variantNewTuple(children: List<COpaquePointer>): COpaquePointer? = memScoped {
        val fn = fnVariantNewTuple ?: return@memScoped null
        if (children.isEmpty()) {
            fn.invoke(null, 0.convert())
        } else {
            val arr = allocArray<COpaquePointerVar>(children.size)
            for (i in children.indices) {
                arr[i] = children[i]
            }
            fn.invoke(arr, children.size.convert())
        }
    }

    fun variantNewDictEntry(key: COpaquePointer?, value: COpaquePointer?): COpaquePointer? =
        if (key != null && value != null) fnVariantNewDictEntry?.invoke(key, value) else null

    fun variantGetChildValue(container: COpaquePointer?, index: Int): COpaquePointer? =
        if (container != null) fnVariantGetChildValue?.invoke(container, index.convert()) else null

    fun variantGetUint32(variant: COpaquePointer?): UInt =
        if (variant != null) fnVariantGetUint32?.invoke(variant) ?: 0u else 0u

    fun variantGetString(variant: COpaquePointer?): String? =
        variant?.let { fnVariantGetString?.invoke(it, null)?.toKString() }

    fun variantUnref(variant: COpaquePointer?) {
        if (variant != null) fnVariantUnref?.invoke(variant)
    }

    fun busGetSessionSync(): COpaquePointer? = memScoped {
        if (!ensureLoaded()) return@memScoped null
        val err = alloc<COpaquePointerVar>()
        err.value = null
        val bus = fnBusGetSync?.invoke(G_BUS_TYPE_SESSION, null, err.ptr)
        readAndFreeError(err.ptr)
        bus
    }

    fun busOwnName(
        name: String,
        onAcquired: COpaquePointer? = null,
        onLost: COpaquePointer? = null,
        userData: COpaquePointer? = null
    ): UInt = memScoped {
        if (!ensureLoaded()) return@memScoped 0u
        fnBusOwnName?.invoke(G_BUS_TYPE_SESSION, name.cstr.ptr, 0u, null, onAcquired, onLost, userData, null) ?: 0u
    }

    fun busUnownName(ownerId: UInt) {
        if (ownerId != 0u) fnBusUnownName?.invoke(ownerId)
    }

    fun dbusCallSync(
        connection: COpaquePointer,
        busName: String?,
        objectPath: String,
        interfaceName: String,
        methodName: String,
        parameters: COpaquePointer?,
        timeoutMs: Int = 5000
    ): COpaquePointer? = memScoped {
        val err = alloc<COpaquePointerVar>()
        err.value = null
        val res = fnDbusCallSync?.invoke(
            connection,
            busName?.cstr?.ptr,
            objectPath.cstr.ptr,
            interfaceName.cstr.ptr,
            methodName.cstr.ptr,
            parameters,
            null,
            G_DBUS_CALL_FLAGS_NONE,
            timeoutMs,
            null,
            err.ptr
        )
        readAndFreeError(err.ptr)
        res
    }

    fun dbusSignalSubscribe(
        connection: COpaquePointer,
        sender: String?,
        interfaceName: String?,
        member: String?,
        objectPath: String?,
        callback: COpaquePointer,
        userData: COpaquePointer?
    ): UInt = memScoped {
        fnDbusSignalSubscribe?.invoke(
            connection,
            sender?.cstr?.ptr,
            interfaceName?.cstr?.ptr,
            member?.cstr?.ptr,
            objectPath?.cstr?.ptr,
            null,
            0u,
            callback,
            userData,
            null
        ) ?: 0u
    }

    fun dbusSignalUnsubscribe(connection: COpaquePointer?, subscriptionId: UInt) {
        if (connection != null && subscriptionId != 0u) {
            fnDbusSignalUnsubscribe?.invoke(connection, subscriptionId)
        }
    }

    fun dbusNodeInfoNewForXml(xml: String): COpaquePointer? = memScoped {
        val err = alloc<COpaquePointerVar>()
        err.value = null
        val info = fnDbusNodeInfoNewForXml?.invoke(xml.cstr.ptr, err.ptr)
        readAndFreeError(err.ptr)
        info
    }

    fun dbusNodeInfoInterfacesFirst(nodeInfo: COpaquePointer): COpaquePointer? {
        // GDBusNodeInfo layout: { gint ref_count; GDBusInterfaceInfo **interfaces; ... }
        // interfaces** starts at offset 8 on LP64.
        val interfacesPtr = interpretCPointer<CPointerVar<COpaquePointerVar>>(nodeInfo.rawValue + 8L)?.pointed?.value
        return interfacesPtr?.pointed?.value
    }

    fun dbusNodeInfoUnref(nodeInfo: COpaquePointer?) {
        if (nodeInfo != null) fnDbusNodeInfoUnref?.invoke(nodeInfo)
    }

    fun dbusRegisterObject(
        connection: COpaquePointer,
        objectPath: String,
        interfaceInfo: COpaquePointer,
        vtable: COpaquePointer,
        userData: COpaquePointer?
    ): UInt = memScoped {
        val err = alloc<COpaquePointerVar>()
        err.value = null
        val regId = fnDbusRegisterObject?.invoke(
            connection,
            objectPath.cstr.ptr,
            interfaceInfo,
            vtable,
            userData,
            null,
            err.ptr
        ) ?: 0u
        readAndFreeError(err.ptr)
        regId
    }

    fun dbusUnregisterObject(connection: COpaquePointer?, registrationId: UInt) {
        if (connection != null && registrationId != 0u) {
            fnDbusUnregisterObject?.invoke(connection, registrationId)
        }
    }

    fun dbusEmitSignal(
        connection: COpaquePointer,
        objectPath: String,
        interfaceName: String,
        signalName: String,
        parameters: COpaquePointer? = null
    ) = memScoped {
        val err = alloc<COpaquePointerVar>()
        err.value = null
        fnDbusEmitSignal?.invoke(
            connection,
            null,
            objectPath.cstr.ptr,
            interfaceName.cstr.ptr,
            signalName.cstr.ptr,
            parameters,
            err.ptr
        )
        readAndFreeError(err.ptr)
    }

    fun dbusMethodReturnValue(invocation: COpaquePointer, value: COpaquePointer?) {
        fnDbusMethodReturnValue?.invoke(invocation, value)
    }

    fun dbusMethodReturnError(invocation: COpaquePointer, errorName: String, message: String) = memScoped {
        fnDbusMethodReturnError?.invoke(invocation, errorName.cstr.ptr, message.cstr.ptr)
    }

    fun listModelGetNItems(model: COpaquePointer?): Int =
        if (model != null) (fnListModelGetNItems?.invoke(model) ?: 0u).toInt() else 0

    fun listModelGetItem(model: COpaquePointer?, index: Int): COpaquePointer? =
        if (model != null && index >= 0) fnListModelGetItem?.invoke(model, index.toUInt()) else null

    fun listStoreNew(itemType: ULong): COpaquePointer? =
        if (itemType != 0uL) fnListStoreNew?.invoke(itemType) else null

    fun listStoreAppend(store: COpaquePointer?, item: COpaquePointer?) {
        if (store != null && item != null) fnListStoreAppend?.invoke(store, item)
    }

    fun fileNewForPath(path: String): COpaquePointer? = memScoped {
        fnFileNewForPath?.invoke(path.cstr.ptr)
    }

    fun fileGetPath(file: COpaquePointer?): String? {
        if (file == null) return null
        val pathPtr = fnFileGetPath?.invoke(file) ?: return null
        val str = pathPtr.toKString()
        free(pathPtr)
        return str
    }
}
