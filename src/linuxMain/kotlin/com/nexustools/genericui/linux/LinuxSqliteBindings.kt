@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.linux

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.dlerror
import platform.posix.memcpy

internal object LinuxSqliteBindings {
    const val SQLITE_OK = 0
    const val SQLITE_BUSY = 5
    const val SQLITE_ROW = 100
    const val SQLITE_DONE = 101

    const val SQLITE_INTEGER = 1
    const val SQLITE_FLOAT = 2
    const val SQLITE_TEXT = 3
    const val SQLITE_BLOB = 4
    const val SQLITE_NULL = 5

    const val SQLITE_OPEN_READONLY = 0x00000001
    const val SQLITE_OPEN_READWRITE = 0x00000002
    const val SQLITE_OPEN_CREATE = 0x00000004
    const val SQLITE_OPEN_URI = 0x00000040
    const val SQLITE_OPEN_FULLMUTEX = 0x00010000

    private val SQLITE_TRANSIENT: COpaquePointer? = (-1L).toCPointer<CPointed>()
    private val EMPTY_PIN_ANCHOR = ByteArray(1)

    private val libraryHandle: COpaquePointer by lazy {
        LinuxDynamicLibraryLoader.openFirst("libsqlite3.so.0", "libsqlite3.so", global = false)
            ?: run {
                val err = dlerror()?.toKString() ?: "unknown dlerror"
                error("Unable to open libsqlite3.so.0: $err")
            }
    }

    private inline fun <reified T : CFunction<*>> resolveSymbol(name: String): CPointer<T> {
        val sym = LinuxDynamicLibraryLoader.symbol(libraryHandle, name)
            ?: error("Unable to resolve SQLite symbol '$name': ${dlerror()?.toKString() ?: "not found"}")
        return sym.reinterpret()
    }

    private val fnOpenV2 by lazy {
        resolveSymbol<CFunction<(CPointer<ByteVar>?, CPointer<COpaquePointerVar>?, Int, CPointer<ByteVar>?) -> Int>>("sqlite3_open_v2")
    }
    private val fnCloseV2 by lazy {
        resolveSymbol<CFunction<(COpaquePointer?) -> Int>>("sqlite3_close_v2")
    }
    private val fnPrepareV2 by lazy {
        resolveSymbol<CFunction<(COpaquePointer?, CPointer<ByteVar>?, Int, CPointer<COpaquePointerVar>?, CPointer<CPointerVar<ByteVar>>?) -> Int>>("sqlite3_prepare_v2")
    }
    private val fnStep by lazy {
        resolveSymbol<CFunction<(COpaquePointer?) -> Int>>("sqlite3_step")
    }
    private val fnReset by lazy {
        resolveSymbol<CFunction<(COpaquePointer?) -> Int>>("sqlite3_reset")
    }
    private val fnFinalize by lazy {
        resolveSymbol<CFunction<(COpaquePointer?) -> Int>>("sqlite3_finalize")
    }
    private val fnBindNull by lazy {
        resolveSymbol<CFunction<(COpaquePointer?, Int) -> Int>>("sqlite3_bind_null")
    }
    private val fnBindInt64 by lazy {
        resolveSymbol<CFunction<(COpaquePointer?, Int, Long) -> Int>>("sqlite3_bind_int64")
    }
    private val fnBindDouble by lazy {
        resolveSymbol<CFunction<(COpaquePointer?, Int, Double) -> Int>>("sqlite3_bind_double")
    }
    private val fnBindText by lazy {
        resolveSymbol<CFunction<(COpaquePointer?, Int, CPointer<ByteVar>?, Int, COpaquePointer?) -> Int>>("sqlite3_bind_text")
    }
    private val fnBindBlob by lazy {
        resolveSymbol<CFunction<(COpaquePointer?, Int, COpaquePointer?, Int, COpaquePointer?) -> Int>>("sqlite3_bind_blob")
    }
    private val fnColumnCount by lazy {
        resolveSymbol<CFunction<(COpaquePointer?) -> Int>>("sqlite3_column_count")
    }
    private val fnColumnName by lazy {
        resolveSymbol<CFunction<(COpaquePointer?, Int) -> CPointer<ByteVar>?>>("sqlite3_column_name")
    }
    private val fnColumnType by lazy {
        resolveSymbol<CFunction<(COpaquePointer?, Int) -> Int>>("sqlite3_column_type")
    }
    private val fnColumnInt64 by lazy {
        resolveSymbol<CFunction<(COpaquePointer?, Int) -> Long>>("sqlite3_column_int64")
    }
    private val fnColumnDouble by lazy {
        resolveSymbol<CFunction<(COpaquePointer?, Int) -> Double>>("sqlite3_column_double")
    }
    private val fnColumnText by lazy {
        resolveSymbol<CFunction<(COpaquePointer?, Int) -> CPointer<UByteVar>?>>("sqlite3_column_text")
    }
    private val fnColumnBlob by lazy {
        resolveSymbol<CFunction<(COpaquePointer?, Int) -> COpaquePointer?>>("sqlite3_column_blob")
    }
    private val fnColumnBytes by lazy {
        resolveSymbol<CFunction<(COpaquePointer?, Int) -> Int>>("sqlite3_column_bytes")
    }
    private val fnChanges by lazy {
        resolveSymbol<CFunction<(COpaquePointer?) -> Int>>("sqlite3_changes")
    }
    private val fnLastInsertRowId by lazy {
        resolveSymbol<CFunction<(COpaquePointer?) -> Long>>("sqlite3_last_insert_rowid")
    }
    private val fnErrmsg by lazy {
        resolveSymbol<CFunction<(COpaquePointer?) -> CPointer<ByteVar>?>>("sqlite3_errmsg")
    }

    fun openV2(path: String, flags: Int = SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE or SQLITE_OPEN_FULLMUTEX): COpaquePointer {
        return memScoped {
            val dbPtrVar = alloc<COpaquePointerVar>()
            val rc = fnOpenV2(path.cstr.ptr, dbPtrVar.ptr, flags, null)
            val db = dbPtrVar.value
            if (rc != SQLITE_OK || db == null) {
                val msg = if (db != null) errmsg(db) else "code=$rc"
                if (db != null) fnCloseV2(db)
                error("sqlite3_open_v2('$path') failed ($rc): $msg")
            }
            db
        }
    }

    fun closeV2(db: COpaquePointer): Int = fnCloseV2(db)

    fun prepareV2(db: COpaquePointer, sql: String): COpaquePointer {
        return memScoped {
            val stmtVar = alloc<COpaquePointerVar>()
            val utf8 = sql.encodeToByteArray()
            val rc = if (utf8.isEmpty()) {
                EMPTY_PIN_ANCHOR.usePinned { pinned ->
                    fnPrepareV2(db, pinned.addressOf(0), 0, stmtVar.ptr, null)
                }
            } else {
                utf8.usePinned { pinned ->
                    fnPrepareV2(db, pinned.addressOf(0), utf8.size, stmtVar.ptr, null)
                }
            }
            val stmt = stmtVar.value
            if (rc != SQLITE_OK || stmt == null) {
                error("sqlite3_prepare_v2 failed ($rc) for SQL [$sql]: ${errmsg(db)}")
            }
            stmt
        }
    }

    fun step(stmt: COpaquePointer): Int = fnStep(stmt)
    fun reset(stmt: COpaquePointer): Int = fnReset(stmt)
    fun finalize(stmt: COpaquePointer): Int = fnFinalize(stmt)
    fun bindNull(stmt: COpaquePointer, index1Based: Int): Int = fnBindNull(stmt, index1Based)
    fun bindInt64(stmt: COpaquePointer, index1Based: Int, value: Long): Int = fnBindInt64(stmt, index1Based, value)
    fun bindDouble(stmt: COpaquePointer, index1Based: Int, value: Double): Int = fnBindDouble(stmt, index1Based, value)

    fun bindText(stmt: COpaquePointer, index1Based: Int, value: String): Int {
        val utf8 = value.encodeToByteArray()
        if (utf8.isEmpty()) {
            return EMPTY_PIN_ANCHOR.usePinned { pinned ->
                fnBindText(stmt, index1Based, pinned.addressOf(0), 0, SQLITE_TRANSIENT)
            }
        }
        return utf8.usePinned { pinned ->
            fnBindText(stmt, index1Based, pinned.addressOf(0), utf8.size, SQLITE_TRANSIENT)
        }
    }

    fun bindBlob(stmt: COpaquePointer, index1Based: Int, bytes: ByteArray): Int {
        if (bytes.isEmpty()) {
            return EMPTY_PIN_ANCHOR.usePinned { pinned ->
                fnBindBlob(stmt, index1Based, pinned.addressOf(0), 0, SQLITE_TRANSIENT)
            }
        }
        return bytes.usePinned { pinned ->
            fnBindBlob(stmt, index1Based, pinned.addressOf(0), bytes.size, SQLITE_TRANSIENT)
        }
    }

    fun columnCount(stmt: COpaquePointer): Int = fnColumnCount(stmt)
    fun columnName(stmt: COpaquePointer, col: Int): String = fnColumnName(stmt, col)?.toKString() ?: "col_$col"
    fun columnType(stmt: COpaquePointer, col: Int): Int = fnColumnType(stmt, col)
    fun columnInt64(stmt: COpaquePointer, col: Int): Long = fnColumnInt64(stmt, col)
    fun columnDouble(stmt: COpaquePointer, col: Int): Double = fnColumnDouble(stmt, col)

    fun columnText(stmt: COpaquePointer, col: Int): String {
        val byteLen = fnColumnBytes(stmt, col)
        if (byteLen <= 0) return ""
        val ptr = fnColumnText(stmt, col) ?: return ""
        val bytes = ByteArray(byteLen)
        bytes.usePinned { pinned ->
            memcpy(pinned.addressOf(0), ptr, byteLen.convert())
        }
        return bytes.decodeToString()
    }

    fun columnBlob(stmt: COpaquePointer, col: Int): ByteArray {
        val byteLen = fnColumnBytes(stmt, col)
        if (byteLen <= 0) return ByteArray(0)
        val ptr = fnColumnBlob(stmt, col) ?: return ByteArray(0)
        val bytes = ByteArray(byteLen)
        bytes.usePinned { pinned ->
            memcpy(pinned.addressOf(0), ptr, byteLen.convert())
        }
        return bytes
    }

    fun changes(db: COpaquePointer): Int = fnChanges(db)
    fun lastInsertRowId(db: COpaquePointer): Long = fnLastInsertRowId(db)
    fun errmsg(db: COpaquePointer): String = fnErrmsg(db)?.toKString() ?: "unknown sqlite error"
}
