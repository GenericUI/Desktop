@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.io.ByteBuffer
import com.nexustools.genericui.io.ByteOrder
import com.nexustools.genericui.io.Readable
import com.nexustools.genericui.io.Writable
import com.nexustools.genericui.io.WritableByteBuffer
import com.nexustools.genericui.nativeimpl.io.DatabaseReadableNativeImpl
import com.nexustools.genericui.nativeimpl.io.DatabaseWritableNativeImpl
import com.nexustools.genericui.nativeimpl.system.DatabaseNativeImpl
import com.nexustools.genericui.util.db.DatabaseColumnSpec
import com.nexustools.genericui.util.db.DatabaseColumnType
import com.nexustools.genericui.util.db.DatabaseFilter
import com.nexustools.genericui.util.db.DatabasePreparedSql
import com.nexustools.genericui.util.db.DatabaseQuery
import com.nexustools.genericui.util.db.DatabaseQuerySqlTranslator
import com.nexustools.genericui.util.db.DatabaseRecord
import com.nexustools.genericui.util.db.DatabaseTableSchema
import com.nexustools.genericui.util.db.DatabaseValue
import kotlinx.cinterop.COpaquePointer
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext

/**
 * Windows Win32 SQLite (`winsqlite3.dll` / `sqlite3.dll`) implementation of [DatabaseNativeImpl]
 * using [DatabaseQuerySqlTranslator] parameterized queries on a dedicated SQLite thread.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class WindowsDatabaseNativeImpl(
    override val databaseName: String = "GenericUI",
    override val filePath: String = "$databaseName.db",
    override val version: Int = 1
) : DatabaseNativeImpl {

    val nativeEngine: String = "Windows_winsqlite3_sqlite3_open_v2_sqlite3_prepare_v2"

    private val lock = WindowsSpinLock()
    private val dbDispatcher = newSingleThreadContext("WinSqlite-$databaseName")
    private val registeredSchemas = LinkedHashMap<String, DatabaseTableSchema>()
    private val preparedStatementsLog = mutableListOf<DatabasePreparedSql>()
    private var dbHandle: COpaquePointer? = null
    private var disposedState: Boolean = false

    override val isDisposed: Boolean
        get() = lock.withLock { disposedState }

    val lastPreparedSql: DatabasePreparedSql?
        get() = lock.withLock { preparedStatementsLog.lastOrNull() }

    fun preparedSqlHistory(): List<DatabasePreparedSql> =
        lock.withLock { preparedStatementsLog.toList() }

    fun buildCreateTableSql(schema: DatabaseTableSchema): List<DatabasePreparedSql> =
        DatabaseQuerySqlTranslator.buildCreateTableSql(schema)

    fun buildUpsertRecordSql(
        table: String,
        record: DatabaseRecord,
        primaryKeyColumn: String = "key"
    ): DatabasePreparedSql =
        DatabaseQuerySqlTranslator.buildUpsertRecordSql(table, record, primaryKeyColumn)

    fun buildSelectByKeySql(
        table: String,
        key: String,
        primaryKeyColumn: String = "key"
    ): DatabasePreparedSql =
        DatabaseQuerySqlTranslator.buildSelectSql(
            DatabaseQuery.from(table).where(DatabaseFilter.eq(primaryKeyColumn, key)).limit(1)
        )

    fun buildSelectQuerySql(query: DatabaseQuery): DatabasePreparedSql =
        DatabaseQuerySqlTranslator.buildSelectSql(query)

    fun buildCountQuerySql(query: DatabaseQuery): DatabasePreparedSql =
        DatabaseQuerySqlTranslator.buildCountSql(query)

    fun buildDeleteWhereSql(query: DatabaseQuery): DatabasePreparedSql =
        DatabaseQuerySqlTranslator.buildDeleteSql(query)

    override suspend fun defineTable(schema: DatabaseTableSchema): Boolean {
        if (isDisposed) return false
        val normalizedSchema = normalizeSchemaWithPrimaryKey(schema)
        val tableName = normalizedSchema.tableName
        val statements = buildCreateTableSql(normalizedSchema)
        recordSql(statements)

        return executeOnDbQueue { db ->
            inTransaction(db) {
                for (prep in statements) {
                    executePreparedUpdate(db, prep)
                }
                val existingCols = queryTableColumnNames(db, tableName)
                for (col in normalizedSchema.columns) {
                    val colName = DatabaseQuerySqlTranslator.sanitizeIdentifier(col.name)
                    if (!existingCols.contains(colName)) {
                        val alterSql = "ALTER TABLE ${DatabaseQuerySqlTranslator.quoteIdentifier(tableName)} " +
                            "ADD COLUMN ${DatabaseQuerySqlTranslator.quoteIdentifier(colName)} ${col.type.sqlAffinity}"
                        executeRawUpdate(db, alterSql)
                    }
                }
            }
            lock.withLock { registeredSchemas[tableName] = normalizedSchema }
            true
        }
    }

    override suspend fun putRecord(table: String, record: DatabaseRecord): Boolean {
        if (isDisposed) return false
        val tableName = DatabaseQuerySqlTranslator.sanitizeIdentifier(table)
        return executeOnDbQueue { db ->
            var upsertSql: DatabasePreparedSql? = null
            inTransaction(db) {
                val schema = ensureTableForRecord(db, tableName, record)
                val existingCols = queryTableColumnNames(db, tableName)
                for ((rawCol, value) in record.values) {
                    val col = DatabaseQuerySqlTranslator.sanitizeIdentifier(rawCol)
                    if (!existingCols.contains(col)) {
                        val affinity = (value.type ?: DatabaseColumnType.STRING).sqlAffinity
                        val alterSql = "ALTER TABLE ${DatabaseQuerySqlTranslator.quoteIdentifier(tableName)} " +
                            "ADD COLUMN ${DatabaseQuerySqlTranslator.quoteIdentifier(col)} $affinity"
                        executeRawUpdate(db, alterSql)
                    }
                }
                val prepared = buildUpsertRecordSql(
                    table = tableName,
                    record = record,
                    primaryKeyColumn = schema.primaryKeyColumn
                )
                upsertSql = prepared
                executePreparedUpdate(db, prepared)
            }
            upsertSql?.let { recordSql(listOf(it)) }
            true
        }
    }

    override suspend fun getRecord(table: String, key: String): DatabaseRecord? {
        if (isDisposed) return null
        val tableName = DatabaseQuerySqlTranslator.sanitizeIdentifier(table)
        return executeOnDbQueue { db ->
            if (!tableExistsSync(db, tableName)) return@executeOnDbQueue null
            val schema = resolveOrLoadSchema(db, tableName)
            val prepared = buildSelectByKeySql(tableName, key, schema.primaryKeyColumn)
            recordSql(listOf(prepared))
            executePreparedSelect(db, prepared, schema).firstOrNull()
        }
    }

    override suspend fun query(query: DatabaseQuery): List<DatabaseRecord> {
        if (isDisposed) return emptyList()
        val tableName = DatabaseQuerySqlTranslator.sanitizeIdentifier(query.table)
        val normalizedQuery = query.copy(table = tableName)
        val prepared = buildSelectQuerySql(normalizedQuery)
        recordSql(listOf(prepared))
        return executeOnDbQueue { db ->
            if (!tableExistsSync(db, tableName)) return@executeOnDbQueue emptyList()
            val schema = resolveOrLoadSchema(db, tableName)
            executePreparedSelect(db, prepared, schema)
        }
    }

    override suspend fun count(query: DatabaseQuery): Long {
        if (isDisposed) return 0L
        val tableName = DatabaseQuerySqlTranslator.sanitizeIdentifier(query.table)
        val normalizedQuery = query.copy(table = tableName)
        val prepared = buildCountQuerySql(normalizedQuery)
        recordSql(listOf(prepared))
        return executeOnDbQueue { db ->
            if (!tableExistsSync(db, tableName)) return@executeOnDbQueue 0L
            val stmt = WindowsSqliteBindings.prepareV2(db, prepared.sql)
            try {
                bindParameters(stmt, prepared.bindValues)
                if (WindowsSqliteBindings.step(stmt) == WindowsSqliteBindings.SQLITE_ROW) {
                    WindowsSqliteBindings.columnInt64(stmt, 0)
                } else {
                    0L
                }
            } finally {
                WindowsSqliteBindings.finalize(stmt)
            }
        }
    }

    override suspend fun deleteWhere(query: DatabaseQuery): Int {
        if (isDisposed) return 0
        val tableName = DatabaseQuerySqlTranslator.sanitizeIdentifier(query.table)
        val normalizedQuery = query.copy(table = tableName)
        val prepared = buildDeleteWhereSql(normalizedQuery)
        recordSql(listOf(prepared))
        return executeOnDbQueue { db ->
            if (!tableExistsSync(db, tableName)) return@executeOnDbQueue 0
            executePreparedUpdate(db, prepared)
            WindowsSqliteBindings.changes(db)
        }
    }

    override suspend fun read(table: String, key: String): ByteBuffer? {
        if (isDisposed) return null
        val tableName = DatabaseQuerySqlTranslator.sanitizeIdentifier(table)
        return executeOnDbQueue { db ->
            val blob = readBlobBytesSync(db, tableName, key) ?: return@executeOnDbQueue null
            ByteBuffer.wrap(blob)
        }
    }

    override suspend fun write(table: String, key: String, buffer: ByteBuffer, append: Boolean): Boolean {
        if (isDisposed) return false
        val tableName = DatabaseQuerySqlTranslator.sanitizeIdentifier(table)
        val payload = buffer.remainingByteArray()
        return executeOnDbQueue { db ->
            writeBlobBytesSync(db, tableName, key, payload, append)
            true
        }
    }

    override suspend fun openReadable(table: String, key: String): DatabaseReadableNativeImpl? {
        if (isDisposed || !exists(table, key)) return null
        val tableName = DatabaseQuerySqlTranslator.sanitizeIdentifier(table)
        return WinSqliteBlobReadableStream(
            table = tableName,
            key = key,
            readSlice = { offset, maxBytes ->
                executeOnDbQueue { db ->
                    readBlobSliceSync(db, tableName, key, offset, maxBytes)
                }
            }
        )
    }

    override suspend fun openWritable(table: String, key: String, append: Boolean): DatabaseWritableNativeImpl {
        val tableName = DatabaseQuerySqlTranslator.sanitizeIdentifier(table)
        return WinSqliteBlobWritableStream(
            table = tableName,
            key = key,
            appendMode = append,
            commitBytes = { bytes, shouldAppend ->
                executeOnDbQueue { db ->
                    writeBlobBytesSync(db, tableName, key, bytes, shouldAppend)
                }
            }
        )
    }

    override suspend fun exists(table: String, key: String): Boolean {
        if (isDisposed) return false
        val tableName = DatabaseQuerySqlTranslator.sanitizeIdentifier(table)
        return executeOnDbQueue { db ->
            if (tableExistsSync(db, BLOB_KV_TABLE) && existsInKvBlobTable(db, tableName, key)) {
                return@executeOnDbQueue true
            }
            if (!tableExistsSync(db, tableName)) return@executeOnDbQueue false
            val schema = resolveOrLoadSchema(db, tableName)
            val qTable = DatabaseQuerySqlTranslator.quoteIdentifier(tableName)
            val pkCol = DatabaseQuerySqlTranslator.quoteIdentifier(schema.primaryKeyColumn)
            val stmt = WindowsSqliteBindings.prepareV2(db, "SELECT 1 FROM $qTable WHERE $pkCol = ? LIMIT 1")
            try {
                WindowsSqliteBindings.bindText(stmt, 1, key)
                WindowsSqliteBindings.step(stmt) == WindowsSqliteBindings.SQLITE_ROW
            } finally {
                WindowsSqliteBindings.finalize(stmt)
            }
        }
    }

    override suspend fun byteSize(table: String, key: String): Long {
        if (isDisposed) return -1L
        val tableName = DatabaseQuerySqlTranslator.sanitizeIdentifier(table)
        return executeOnDbQueue { db ->
            if (tableExistsSync(db, BLOB_KV_TABLE)) {
                val stmt = WindowsSqliteBindings.prepareV2(
                    db,
                    "SELECT length(payload) FROM $BLOB_KV_TABLE WHERE table_name = ? AND key_id = ? LIMIT 1"
                )
                try {
                    WindowsSqliteBindings.bindText(stmt, 1, tableName)
                    WindowsSqliteBindings.bindText(stmt, 2, key)
                    if (WindowsSqliteBindings.step(stmt) == WindowsSqliteBindings.SQLITE_ROW) {
                        return@executeOnDbQueue WindowsSqliteBindings.columnInt64(stmt, 0)
                    }
                } finally {
                    WindowsSqliteBindings.finalize(stmt)
                }
            }
            if (!tableExistsSync(db, tableName)) return@executeOnDbQueue -1L
            val blobCol = findBlobColumnName(db, tableName)
            if (blobCol != null) {
                val schema = resolveOrLoadSchema(db, tableName)
                val qTable = DatabaseQuerySqlTranslator.quoteIdentifier(tableName)
                val pkCol = DatabaseQuerySqlTranslator.quoteIdentifier(schema.primaryKeyColumn)
                val qBlob = DatabaseQuerySqlTranslator.quoteIdentifier(blobCol)
                val stmt = WindowsSqliteBindings.prepareV2(db, "SELECT length($qBlob) FROM $qTable WHERE $pkCol = ? LIMIT 1")
                try {
                    WindowsSqliteBindings.bindText(stmt, 1, key)
                    if (WindowsSqliteBindings.step(stmt) == WindowsSqliteBindings.SQLITE_ROW &&
                        WindowsSqliteBindings.columnType(stmt, 0) != WindowsSqliteBindings.SQLITE_NULL
                    ) {
                        return@executeOnDbQueue WindowsSqliteBindings.columnInt64(stmt, 0)
                    }
                } finally {
                    WindowsSqliteBindings.finalize(stmt)
                }
            }
            -1L
        }
    }

    override suspend fun delete(table: String, key: String): Boolean {
        if (isDisposed) return false
        val tableName = DatabaseQuerySqlTranslator.sanitizeIdentifier(table)
        return executeOnDbQueue { db ->
            var deletedAny = false
            inTransaction(db) {
                if (tableExistsSync(db, BLOB_KV_TABLE)) {
                    executePreparedUpdate(
                        db,
                        DatabasePreparedSql(
                            "DELETE FROM $BLOB_KV_TABLE WHERE table_name = ? AND key_id = ?",
                            listOf(DatabaseValue.of(tableName), DatabaseValue.of(key))
                        )
                    )
                    if (WindowsSqliteBindings.changes(db) > 0) deletedAny = true
                }
                if (tableExistsSync(db, tableName)) {
                    val schema = resolveOrLoadSchema(db, tableName)
                    val qTable = DatabaseQuerySqlTranslator.quoteIdentifier(tableName)
                    val pkCol = DatabaseQuerySqlTranslator.quoteIdentifier(schema.primaryKeyColumn)
                    val delStmt = DatabasePreparedSql("DELETE FROM $qTable WHERE $pkCol = ?", listOf(DatabaseValue.of(key)))
                    recordSql(listOf(delStmt))
                    executePreparedUpdate(db, delStmt)
                    if (WindowsSqliteBindings.changes(db) > 0) deletedAny = true
                }
            }
            deletedAny
        }
    }

    override suspend fun keys(table: String, prefix: String): List<String> {
        if (isDisposed) return emptyList()
        val tableName = DatabaseQuerySqlTranslator.sanitizeIdentifier(table)
        return executeOnDbQueue { db ->
            val result = LinkedHashSet<String>()
            if (tableExistsSync(db, tableName)) {
                val schema = resolveOrLoadSchema(db, tableName)
                val qTable = DatabaseQuerySqlTranslator.quoteIdentifier(tableName)
                val pkCol = DatabaseQuerySqlTranslator.quoteIdentifier(schema.primaryKeyColumn)
                val sql = if (prefix.isEmpty()) {
                    "SELECT $pkCol FROM $qTable ORDER BY $pkCol ASC"
                } else {
                    "SELECT $pkCol FROM $qTable WHERE $pkCol LIKE ? ESCAPE '\\' ORDER BY $pkCol ASC"
                }
                val stmt = WindowsSqliteBindings.prepareV2(db, sql)
                try {
                    if (prefix.isNotEmpty()) {
                        WindowsSqliteBindings.bindText(stmt, 1, "${escapeLike(prefix)}%")
                    }
                    while (WindowsSqliteBindings.step(stmt) == WindowsSqliteBindings.SQLITE_ROW) {
                        result.add(WindowsSqliteBindings.columnText(stmt, 0))
                    }
                } finally {
                    WindowsSqliteBindings.finalize(stmt)
                }
            }
            if (tableExistsSync(db, BLOB_KV_TABLE)) {
                val sql = if (prefix.isEmpty()) {
                    "SELECT key_id FROM $BLOB_KV_TABLE WHERE table_name = ? ORDER BY key_id ASC"
                } else {
                    "SELECT key_id FROM $BLOB_KV_TABLE WHERE table_name = ? AND key_id LIKE ? ESCAPE '\\' ORDER BY key_id ASC"
                }
                val stmt = WindowsSqliteBindings.prepareV2(db, sql)
                try {
                    WindowsSqliteBindings.bindText(stmt, 1, tableName)
                    if (prefix.isNotEmpty()) {
                        WindowsSqliteBindings.bindText(stmt, 2, "${escapeLike(prefix)}%")
                    }
                    while (WindowsSqliteBindings.step(stmt) == WindowsSqliteBindings.SQLITE_ROW) {
                        result.add(WindowsSqliteBindings.columnText(stmt, 0))
                    }
                } finally {
                    WindowsSqliteBindings.finalize(stmt)
                }
            }
            result.toList()
        }
    }

    override suspend fun clearTable(table: String): Int {
        if (isDisposed) return 0
        val tableName = DatabaseQuerySqlTranslator.sanitizeIdentifier(table)
        return executeOnDbQueue { db ->
            var removed = 0
            inTransaction(db) {
                if (tableExistsSync(db, tableName)) {
                    val qTable = DatabaseQuerySqlTranslator.quoteIdentifier(tableName)
                    executeRawUpdate(db, "DELETE FROM $qTable")
                    removed += WindowsSqliteBindings.changes(db)
                }
                if (tableExistsSync(db, BLOB_KV_TABLE)) {
                    executePreparedUpdate(
                        db,
                        DatabasePreparedSql("DELETE FROM $BLOB_KV_TABLE WHERE table_name = ?", listOf(DatabaseValue.of(tableName)))
                    )
                    removed += WindowsSqliteBindings.changes(db)
                }
            }
            removed
        }
    }

    override suspend fun dropTable(table: String): Boolean {
        if (isDisposed) return false
        val tableName = DatabaseQuerySqlTranslator.sanitizeIdentifier(table)
        return executeOnDbQueue { db ->
            var dropped = false
            inTransaction(db) {
                if (tableExistsSync(db, tableName)) {
                    val qTable = DatabaseQuerySqlTranslator.quoteIdentifier(tableName)
                    executeRawUpdate(db, "DROP TABLE IF EXISTS $qTable")
                    dropped = true
                }
                if (tableExistsSync(db, BLOB_KV_TABLE)) {
                    executePreparedUpdate(
                        db,
                        DatabasePreparedSql("DELETE FROM $BLOB_KV_TABLE WHERE table_name = ?", listOf(DatabaseValue.of(tableName)))
                    )
                }
            }
            lock.withLock { registeredSchemas.remove(tableName) }
            dropped
        }
    }

    override suspend fun tables(): List<String> {
        if (isDisposed) return emptyList()
        return executeOnDbQueue { db ->
            val out = LinkedHashSet<String>()
            val stmt = WindowsSqliteBindings.prepareV2(
                db,
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name ASC"
            )
            try {
                while (WindowsSqliteBindings.step(stmt) == WindowsSqliteBindings.SQLITE_ROW) {
                    val name = WindowsSqliteBindings.columnText(stmt, 0)
                    if (name != BLOB_KV_TABLE) out.add(name)
                }
            } finally {
                WindowsSqliteBindings.finalize(stmt)
            }
            if (tableExistsSync(db, BLOB_KV_TABLE)) {
                val kvStmt = WindowsSqliteBindings.prepareV2(db, "SELECT DISTINCT table_name FROM $BLOB_KV_TABLE ORDER BY table_name ASC")
                try {
                    while (WindowsSqliteBindings.step(kvStmt) == WindowsSqliteBindings.SQLITE_ROW) {
                        out.add(WindowsSqliteBindings.columnText(kvStmt, 0))
                    }
                } finally {
                    WindowsSqliteBindings.finalize(kvStmt)
                }
            }
            out.toList()
        }
    }

    override suspend fun totalByteSize(table: String): Long {
        if (isDisposed) return 0L
        val tableName = DatabaseQuerySqlTranslator.sanitizeIdentifier(table)
        return executeOnDbQueue { db ->
            var total = 0L
            if (tableExistsSync(db, BLOB_KV_TABLE)) {
                val stmt = WindowsSqliteBindings.prepareV2(
                    db,
                    "SELECT COALESCE(SUM(length(payload)), 0) FROM $BLOB_KV_TABLE WHERE table_name = ?"
                )
                try {
                    WindowsSqliteBindings.bindText(stmt, 1, tableName)
                    if (WindowsSqliteBindings.step(stmt) == WindowsSqliteBindings.SQLITE_ROW) {
                        total += WindowsSqliteBindings.columnInt64(stmt, 0)
                    }
                } finally {
                    WindowsSqliteBindings.finalize(stmt)
                }
            }
            if (total == 0L && tableExistsSync(db, tableName)) {
                val blobCol = findBlobColumnName(db, tableName)
                if (blobCol != null) {
                    val qTable = DatabaseQuerySqlTranslator.quoteIdentifier(tableName)
                    val qBlob = DatabaseQuerySqlTranslator.quoteIdentifier(blobCol)
                    val stmt = WindowsSqliteBindings.prepareV2(db, "SELECT COALESCE(SUM(length($qBlob)), 0) FROM $qTable")
                    try {
                        if (WindowsSqliteBindings.step(stmt) == WindowsSqliteBindings.SQLITE_ROW) {
                            total += WindowsSqliteBindings.columnInt64(stmt, 0)
                        }
                    } finally {
                        WindowsSqliteBindings.finalize(stmt)
                    }
                }
            }
            total
        }
    }

    override fun dispose() {
        val handleToClose = lock.withLock {
            if (disposedState) return
            disposedState = true
            preparedStatementsLog.clear()
            registeredSchemas.clear()
            val h = dbHandle
            dbHandle = null
            h
        }
        if (handleToClose != null) {
            WindowsSqliteBindings.closeV2(handleToClose)
        }
        runCatching { dbDispatcher.close() }
    }

    private fun recordSql(statements: List<DatabasePreparedSql>) {
        lock.withLock {
            preparedStatementsLog.addAll(statements)
            while (preparedStatementsLog.size > 256) {
                preparedStatementsLog.removeAt(0)
            }
        }
    }

    private suspend fun <T> executeOnDbQueue(block: (COpaquePointer) -> T): T {
        return WindowsIoHelper.runIo(dbDispatcher) {
            if (isDisposed) error("Database '$databaseName' is disposed")
            val db = ensureOpenOnDbThread()
            block(db)
        }
    }

    private fun ensureOpenOnDbThread(): COpaquePointer {
        lock.withLock { dbHandle }?.let { return it }
        val normalizedPath = if (filePath == ":memory:" || filePath.startsWith("file:", ignoreCase = true)) {
            filePath
        } else {
            WindowsIoHelper.normalizeWin32Path(filePath).also { p ->
                WindowsIoHelper.ensureParentDirectories(p)
            }
        }
        val db = WindowsSqliteBindings.openV2(normalizedPath)
        if (normalizedPath != ":memory:") {
            executeRawUpdate(db, "PRAGMA journal_mode=WAL")
        }
        executeRawUpdate(db, "PRAGMA foreign_keys=ON")
        executeRawUpdate(db, "PRAGMA busy_timeout=5000")
        executeRawUpdate(db, "PRAGMA user_version=${version.coerceAtLeast(0)}")
        lock.withLock { dbHandle = db }
        return db
    }

    private inline fun <T> inTransaction(db: COpaquePointer, block: () -> T): T {
        executeRawUpdate(db, "BEGIN IMMEDIATE TRANSACTION")
        try {
            val result = block()
            executeRawUpdate(db, "COMMIT")
            return result
        } catch (t: Throwable) {
            runCatching { executeRawUpdate(db, "ROLLBACK") }
            throw t
        }
    }

    private fun executeRawUpdate(db: COpaquePointer, sql: String) {
        val stmt = WindowsSqliteBindings.prepareV2(db, sql)
        try {
            var rc = WindowsSqliteBindings.step(stmt)
            while (rc == WindowsSqliteBindings.SQLITE_ROW) {
                rc = WindowsSqliteBindings.step(stmt)
            }
            if (rc != WindowsSqliteBindings.SQLITE_DONE) {
                error("sqlite3_step failed ($rc) for SQL [$sql]: ${WindowsSqliteBindings.errmsg(db)}")
            }
        } finally {
            WindowsSqliteBindings.finalize(stmt)
        }
    }

    private fun executePreparedUpdate(db: COpaquePointer, prepared: DatabasePreparedSql) {
        val stmt = WindowsSqliteBindings.prepareV2(db, prepared.sql)
        try {
            bindParameters(stmt, prepared.bindValues)
            val rc = WindowsSqliteBindings.step(stmt)
            if (rc != WindowsSqliteBindings.SQLITE_DONE && rc != WindowsSqliteBindings.SQLITE_ROW) {
                error("sqlite3_step failed ($rc) for SQL [${prepared.sql}]: ${WindowsSqliteBindings.errmsg(db)}")
            }
        } finally {
            WindowsSqliteBindings.finalize(stmt)
        }
    }

    private fun executePreparedSelect(
        db: COpaquePointer,
        prepared: DatabasePreparedSql,
        schema: DatabaseTableSchema
    ): List<DatabaseRecord> {
        val colTypesByName = LinkedHashMap<String, DatabaseColumnType>()
        for (col in schema.columns) {
            colTypesByName[DatabaseQuerySqlTranslator.sanitizeIdentifier(col.name)] = col.type
        }
        val pkName = DatabaseQuerySqlTranslator.sanitizeIdentifier(schema.primaryKeyColumn)

        val stmt = WindowsSqliteBindings.prepareV2(db, prepared.sql)
        try {
            bindParameters(stmt, prepared.bindValues)
            val colCount = WindowsSqliteBindings.columnCount(stmt)
            val colNames = Array(colCount) { idx -> WindowsSqliteBindings.columnName(stmt, idx) }
            val pkColIdx = colNames.indexOfFirst { it == pkName }.takeIf { it >= 0 }
                ?: colNames.indexOfFirst { it == "key_id" || it == "id" || it == "key" }

            val results = ArrayList<DatabaseRecord>()
            var rc = WindowsSqliteBindings.step(stmt)
            while (rc == WindowsSqliteBindings.SQLITE_ROW) {
                val valuesMap = LinkedHashMap<String, DatabaseValue>()
                var recordKey = ""
                for (i in 0 until colCount) {
                    val colName = colNames[i]
                    val dbValue = readColumnValue(stmt, i, colTypesByName[colName])
                    if (i == pkColIdx || colName == pkName) {
                        recordKey = dbValue.asString() ?: ""
                    } else {
                        valuesMap[colName] = dbValue
                    }
                }
                results.add(DatabaseRecord(key = recordKey, values = valuesMap))
                rc = WindowsSqliteBindings.step(stmt)
            }
            if (rc != WindowsSqliteBindings.SQLITE_DONE) {
                error("sqlite3_step SELECT failed ($rc) for [${prepared.sql}]: ${WindowsSqliteBindings.errmsg(db)}")
            }
            return results
        } finally {
            WindowsSqliteBindings.finalize(stmt)
        }
    }

    private fun readColumnValue(
        stmt: COpaquePointer,
        colIdx: Int,
        declaredType: DatabaseColumnType?
    ): DatabaseValue {
        return when (WindowsSqliteBindings.columnType(stmt, colIdx)) {
            WindowsSqliteBindings.SQLITE_NULL -> DatabaseValue.Null
            WindowsSqliteBindings.SQLITE_INTEGER -> {
                val rawLong = WindowsSqliteBindings.columnInt64(stmt, colIdx)
                if (declaredType == DatabaseColumnType.BOOLEAN) {
                    DatabaseValue.of(rawLong != 0L)
                } else {
                    DatabaseValue.of(rawLong)
                }
            }
            WindowsSqliteBindings.SQLITE_FLOAT -> DatabaseValue.of(WindowsSqliteBindings.columnDouble(stmt, colIdx))
            WindowsSqliteBindings.SQLITE_TEXT -> DatabaseValue.of(WindowsSqliteBindings.columnText(stmt, colIdx))
            WindowsSqliteBindings.SQLITE_BLOB -> DatabaseValue.of(WindowsSqliteBindings.columnBlob(stmt, colIdx))
            else -> DatabaseValue.Null
        }
    }

    private fun bindParameters(stmt: COpaquePointer, bindValues: List<DatabaseValue>) {
        for ((zeroIdx, value) in bindValues.withIndex()) {
            val idx = zeroIdx + 1
            val rc = when {
                value.isNull || value.type == null -> WindowsSqliteBindings.bindNull(stmt, idx)
                value.type == DatabaseColumnType.STRING -> WindowsSqliteBindings.bindText(stmt, idx, value.asString() ?: "")
                value.type == DatabaseColumnType.INT64 -> WindowsSqliteBindings.bindInt64(stmt, idx, value.asLong() ?: 0L)
                value.type == DatabaseColumnType.FLOAT64 -> WindowsSqliteBindings.bindDouble(stmt, idx, value.asDouble() ?: 0.0)
                value.type == DatabaseColumnType.BOOLEAN -> WindowsSqliteBindings.bindInt64(stmt, idx, if (value.asBoolean() == true) 1L else 0L)
                value.type == DatabaseColumnType.BLOB -> WindowsSqliteBindings.bindBlob(stmt, idx, value.asBytes() ?: ByteArray(0))
                else -> WindowsSqliteBindings.bindNull(stmt, idx)
            }
            if (rc != WindowsSqliteBindings.SQLITE_OK) {
                error("sqlite3_bind failed ($rc) at param $idx")
            }
        }
    }

    private fun normalizeSchemaWithPrimaryKey(schema: DatabaseTableSchema): DatabaseTableSchema {
        val tableName = DatabaseQuerySqlTranslator.sanitizeIdentifier(schema.tableName)
        val pkName = DatabaseQuerySqlTranslator.sanitizeIdentifier(schema.primaryKeyColumn.ifBlank { "key" })
        val sanitizedCols = schema.columns.map { col ->
            col.copy(name = DatabaseQuerySqlTranslator.sanitizeIdentifier(col.name))
        }
        val withPkCols = if (sanitizedCols.any { it.name == pkName }) {
            sanitizedCols
        } else {
            listOf(
                DatabaseColumnSpec(
                    name = pkName,
                    type = DatabaseColumnType.STRING,
                    primaryKey = true,
                    nullable = false
                )
            ) + sanitizedCols
        }
        return schema.copy(tableName = tableName, primaryKeyColumn = pkName, columns = withPkCols)
    }

    private fun ensureTableForRecord(db: COpaquePointer, tableName: String, record: DatabaseRecord): DatabaseTableSchema {
        lock.withLock { registeredSchemas[tableName] }?.let { return it }
        if (tableExistsSync(db, tableName)) {
            return resolveOrLoadSchema(db, tableName)
        }
        val defaultSchema = normalizeSchemaWithPrimaryKey(
            DatabaseTableSchema(
                tableName = tableName,
                columns = record.values.map { (colName, dbVal) ->
                    DatabaseColumnSpec(
                        name = DatabaseQuerySqlTranslator.sanitizeIdentifier(colName),
                        type = dbVal.type ?: DatabaseColumnType.STRING,
                        nullable = true
                    )
                }
            )
        )
        for (prep in buildCreateTableSql(defaultSchema)) {
            executePreparedUpdate(db, prep)
        }
        lock.withLock { registeredSchemas[tableName] = defaultSchema }
        return defaultSchema
    }

    private fun resolveOrLoadSchema(db: COpaquePointer, tableName: String): DatabaseTableSchema {
        lock.withLock { registeredSchemas[tableName] }?.let { return it }
        val qTable = DatabaseQuerySqlTranslator.quoteIdentifier(tableName)
        val stmt = WindowsSqliteBindings.prepareV2(db, "PRAGMA table_info($qTable)")
        var pkCol = "key"
        val cols = ArrayList<DatabaseColumnSpec>()
        try {
            while (WindowsSqliteBindings.step(stmt) == WindowsSqliteBindings.SQLITE_ROW) {
                val colName = WindowsSqliteBindings.columnText(stmt, 1)
                val colTypeStr = WindowsSqliteBindings.columnText(stmt, 2).uppercase()
                val notNull = WindowsSqliteBindings.columnInt64(stmt, 3) != 0L
                val isPk = WindowsSqliteBindings.columnInt64(stmt, 5) > 0L
                if (isPk) pkCol = colName
                val type = when {
                    colTypeStr.contains("INT") -> DatabaseColumnType.INT64
                    colTypeStr.contains("REAL") || colTypeStr.contains("FLOA") || colTypeStr.contains("DOUB") -> DatabaseColumnType.FLOAT64
                    colTypeStr.contains("BLOB") -> DatabaseColumnType.BLOB
                    else -> DatabaseColumnType.STRING
                }
                cols.add(
                    DatabaseColumnSpec(
                        name = colName,
                        type = type,
                        primaryKey = isPk,
                        nullable = !notNull
                    )
                )
            }
        } finally {
            WindowsSqliteBindings.finalize(stmt)
        }
        val loaded = DatabaseTableSchema(tableName = tableName, primaryKeyColumn = pkCol, columns = cols)
        lock.withLock { registeredSchemas[tableName] = loaded }
        return loaded
    }

    private fun tableExistsSync(db: COpaquePointer, tableName: String): Boolean {
        val stmt = WindowsSqliteBindings.prepareV2(
            db,
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1"
        )
        try {
            WindowsSqliteBindings.bindText(stmt, 1, tableName)
            return WindowsSqliteBindings.step(stmt) == WindowsSqliteBindings.SQLITE_ROW
        } finally {
            WindowsSqliteBindings.finalize(stmt)
        }
    }

    private fun queryTableColumnNames(db: COpaquePointer, tableName: String): Set<String> {
        val qTable = DatabaseQuerySqlTranslator.quoteIdentifier(tableName)
        val stmt = WindowsSqliteBindings.prepareV2(db, "PRAGMA table_info($qTable)")
        val cols = LinkedHashSet<String>()
        try {
            while (WindowsSqliteBindings.step(stmt) == WindowsSqliteBindings.SQLITE_ROW) {
                cols.add(WindowsSqliteBindings.columnText(stmt, 1))
            }
        } finally {
            WindowsSqliteBindings.finalize(stmt)
        }
        return cols
    }

    private fun findBlobColumnName(db: COpaquePointer, tableName: String): String? {
        val schema = resolveOrLoadSchema(db, tableName)
        return schema.columns.firstOrNull { it.type == DatabaseColumnType.BLOB }?.name
    }

    private fun ensureKvBlobTableSync(db: COpaquePointer) {
        executeRawUpdate(
            db,
            "CREATE TABLE IF NOT EXISTS $BLOB_KV_TABLE (" +
                "table_name TEXT NOT NULL, " +
                "key_id TEXT NOT NULL, " +
                "payload BLOB NOT NULL, " +
                "PRIMARY KEY (table_name, key_id))"
        )
    }

    private fun existsInKvBlobTable(db: COpaquePointer, tableName: String, key: String): Boolean {
        val stmt = WindowsSqliteBindings.prepareV2(
            db,
            "SELECT 1 FROM $BLOB_KV_TABLE WHERE table_name = ? AND key_id = ? LIMIT 1"
        )
        try {
            WindowsSqliteBindings.bindText(stmt, 1, tableName)
            WindowsSqliteBindings.bindText(stmt, 2, key)
            return WindowsSqliteBindings.step(stmt) == WindowsSqliteBindings.SQLITE_ROW
        } finally {
            WindowsSqliteBindings.finalize(stmt)
        }
    }

    private fun readBlobBytesSync(db: COpaquePointer, tableName: String, key: String): ByteArray? {
        if (tableExistsSync(db, BLOB_KV_TABLE)) {
            val stmt = WindowsSqliteBindings.prepareV2(
                db,
                "SELECT payload FROM $BLOB_KV_TABLE WHERE table_name = ? AND key_id = ? LIMIT 1"
            )
            try {
                WindowsSqliteBindings.bindText(stmt, 1, tableName)
                WindowsSqliteBindings.bindText(stmt, 2, key)
                if (WindowsSqliteBindings.step(stmt) == WindowsSqliteBindings.SQLITE_ROW) {
                    return WindowsSqliteBindings.columnBlob(stmt, 0)
                }
            } finally {
                WindowsSqliteBindings.finalize(stmt)
            }
        }
        if (tableExistsSync(db, tableName)) {
            val blobCol = findBlobColumnName(db, tableName)
            if (blobCol != null) {
                val schema = resolveOrLoadSchema(db, tableName)
                val qTable = DatabaseQuerySqlTranslator.quoteIdentifier(tableName)
                val pkCol = DatabaseQuerySqlTranslator.quoteIdentifier(schema.primaryKeyColumn)
                val qBlob = DatabaseQuerySqlTranslator.quoteIdentifier(blobCol)
                val stmt = WindowsSqliteBindings.prepareV2(db, "SELECT $qBlob FROM $qTable WHERE $pkCol = ? LIMIT 1")
                try {
                    WindowsSqliteBindings.bindText(stmt, 1, key)
                    if (WindowsSqliteBindings.step(stmt) == WindowsSqliteBindings.SQLITE_ROW &&
                        WindowsSqliteBindings.columnType(stmt, 0) != WindowsSqliteBindings.SQLITE_NULL
                    ) {
                        return WindowsSqliteBindings.columnBlob(stmt, 0)
                    }
                } finally {
                    WindowsSqliteBindings.finalize(stmt)
                }
            }
        }
        return null
    }

    private fun readBlobSliceSync(
        db: COpaquePointer,
        tableName: String,
        key: String,
        offset: Long,
        maxBytes: Int
    ): ByteArray? {
        if (maxBytes <= 0) return ByteArray(0)
        val sqlOffset = offset + 1L
        if (tableExistsSync(db, BLOB_KV_TABLE)) {
            val stmt = WindowsSqliteBindings.prepareV2(
                db,
                "SELECT substr(payload, ?, ?) FROM $BLOB_KV_TABLE WHERE table_name = ? AND key_id = ? LIMIT 1"
            )
            try {
                WindowsSqliteBindings.bindInt64(stmt, 1, sqlOffset)
                WindowsSqliteBindings.bindInt64(stmt, 2, maxBytes.toLong())
                WindowsSqliteBindings.bindText(stmt, 3, tableName)
                WindowsSqliteBindings.bindText(stmt, 4, key)
                if (WindowsSqliteBindings.step(stmt) == WindowsSqliteBindings.SQLITE_ROW) {
                    return WindowsSqliteBindings.columnBlob(stmt, 0)
                }
            } finally {
                WindowsSqliteBindings.finalize(stmt)
            }
        }
        if (tableExistsSync(db, tableName)) {
            val blobCol = findBlobColumnName(db, tableName)
            if (blobCol != null) {
                val schema = resolveOrLoadSchema(db, tableName)
                val qTable = DatabaseQuerySqlTranslator.quoteIdentifier(tableName)
                val pkCol = DatabaseQuerySqlTranslator.quoteIdentifier(schema.primaryKeyColumn)
                val qBlob = DatabaseQuerySqlTranslator.quoteIdentifier(blobCol)
                val stmt = WindowsSqliteBindings.prepareV2(
                    db,
                    "SELECT substr($qBlob, ?, ?) FROM $qTable WHERE $pkCol = ? LIMIT 1"
                )
                try {
                    WindowsSqliteBindings.bindInt64(stmt, 1, sqlOffset)
                    WindowsSqliteBindings.bindInt64(stmt, 2, maxBytes.toLong())
                    WindowsSqliteBindings.bindText(stmt, 3, key)
                    if (WindowsSqliteBindings.step(stmt) == WindowsSqliteBindings.SQLITE_ROW &&
                        WindowsSqliteBindings.columnType(stmt, 0) != WindowsSqliteBindings.SQLITE_NULL
                    ) {
                        return WindowsSqliteBindings.columnBlob(stmt, 0)
                    }
                } finally {
                    WindowsSqliteBindings.finalize(stmt)
                }
            }
        }
        return null
    }

    private fun writeBlobBytesSync(
        db: COpaquePointer,
        tableName: String,
        key: String,
        bytes: ByteArray,
        append: Boolean
    ) {
        inTransaction(db) {
            ensureKvBlobTableSync(db)
            val finalBytes = if (append) {
                val existing = readBlobBytesSync(db, tableName, key) ?: ByteArray(0)
                if (existing.isEmpty()) bytes else existing + bytes
            } else {
                bytes
            }
            val stmt = WindowsSqliteBindings.prepareV2(
                db,
                "INSERT INTO $BLOB_KV_TABLE (table_name, key_id, payload) VALUES (?, ?, ?) " +
                    "ON CONFLICT(table_name, key_id) DO UPDATE SET payload = excluded.payload"
            )
            try {
                WindowsSqliteBindings.bindText(stmt, 1, tableName)
                WindowsSqliteBindings.bindText(stmt, 2, key)
                WindowsSqliteBindings.bindBlob(stmt, 3, finalBytes)
                val rc = WindowsSqliteBindings.step(stmt)
                if (rc != WindowsSqliteBindings.SQLITE_DONE) {
                    error("Failed to upsert SQLite BLOB for $tableName/$key ($rc): ${WindowsSqliteBindings.errmsg(db)}")
                }
            } finally {
                WindowsSqliteBindings.finalize(stmt)
            }
        }
    }

    private fun escapeLike(prefix: String): String {
        return buildString(prefix.length) {
            for (ch in prefix) {
                when (ch) {
                    '%', '_', '\\' -> {
                        append('\\')
                        append(ch)
                    }
                    else -> append(ch)
                }
            }
        }
    }

    private class WinSqliteBlobReadableStream(
        override val table: String,
        override val key: String,
        private val readSlice: suspend (offset: Long, maxBytes: Int) -> ByteArray?,
        initialByteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN
    ) : DatabaseReadableNativeImpl {
        private val state = WindowsStreamState(initialByteOrder = initialByteOrder)
        private var readOffset: Long = 0L

        override val isReadable: Boolean
            get() = state.lock.withLock { !state.isDisposed && !state.isEnded }

        override val isPaused: Boolean
            get() = state.lock.withLock { state.isPaused }

        override val isEnded: Boolean
            get() = state.lock.withLock { state.isEnded }

        override val bytesRead: Long
            get() = state.lock.withLock { state.bytesRead }

        override var byteOrder: ByteOrder
            get() = state.lock.withLock { state.byteOrder }
            set(value) {
                state.lock.withLock { state.byteOrder = value }
            }

        override val isDisposed: Boolean
            get() = state.lock.withLock { state.isDisposed }

        override suspend fun read(maxBytes: Int): ByteBuffer? {
            if (maxBytes <= 0 || isPaused || isDisposed || isEnded) return null
            state.pollPushback(maxBytes)?.let { return it }

            val offset = state.lock.withLock { readOffset }
            val chunkBytes = readSlice(offset, maxBytes)
            if (chunkBytes == null || chunkBytes.isEmpty()) {
                state.emitEnd()
                return null
            }

            state.lock.withLock { readOffset += chunkBytes.size }
            state.recordRead(chunkBytes.size)
            val chunk = ByteBuffer.wrap(chunkBytes, byteOrder = byteOrder)
            state.emitData(chunk)
            return chunk
        }

        override fun onData(listener: suspend (ByteBuffer) -> Unit): Readable {
            state.addDataListener(listener)
            return this
        }

        override fun onEnd(listener: suspend () -> Unit): Readable {
            state.addEndListener(listener)
            return this
        }

        override fun onError(listener: (Throwable) -> Unit): Readable {
            state.addErrorListener(listener)
            return this
        }

        override fun onClose(listener: () -> Unit): Readable {
            state.addCloseListener(listener)
            return this
        }

        override fun pause(): Readable {
            state.pause()
            return this
        }

        override fun resume(): Readable {
            state.resume()
            return this
        }

        override fun unshift(buffer: ByteBuffer) {
            state.unshift(buffer)
        }

        override suspend fun close() {
            state.emitEnd()
            state.markDisposedAndNotifyClose()
        }

        override fun dispose() {
            state.markDisposedAndNotifyClose()
        }
    }

    private class WinSqliteBlobWritableStream(
        override val table: String,
        override val key: String,
        override val appendMode: Boolean = false,
        private val commitBytes: suspend (bytes: ByteArray, append: Boolean) -> Unit,
        initialHighWaterMark: Int = 64 * 1024,
        initialByteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN
    ) : DatabaseWritableNativeImpl {
        private val state = WindowsStreamState(
            initialByteOrder = initialByteOrder,
            initialHighWaterMark = initialHighWaterMark
        )
        private val accumulator = WritableByteBuffer(byteOrder = initialByteOrder)
        private var flushedOnce: Boolean = false

        override val isWritable: Boolean
            get() = state.lock.withLock { !state.isDisposed && !state.isFinished }

        override val isFinished: Boolean
            get() = state.lock.withLock { state.isFinished }

        override val bytesWritten: Long
            get() = state.lock.withLock { state.bytesWritten }

        override var highWaterMark: Int
            get() = state.lock.withLock { state.highWaterMark }
            set(value) {
                state.lock.withLock { state.highWaterMark = value }
            }

        override var byteOrder: ByteOrder
            get() = state.lock.withLock { state.byteOrder }
            set(value) {
                state.lock.withLock {
                    state.byteOrder = value
                    accumulator.byteOrder = value
                }
            }

        override val isDisposed: Boolean
            get() = state.lock.withLock { state.isDisposed }

        override fun write(buffer: ByteBuffer): Boolean {
            val slice = buffer.remainingByteArray()
            return write(slice, 0, slice.size)
        }

        override fun write(chunk: ByteArray, offset: Int, length: Int): Boolean {
            require(offset >= 0 && length >= 0 && offset + length <= chunk.size) {
                "Invalid slice offset=$offset length=$length size=${chunk.size}"
            }
            val slice = if (offset == 0 && length == chunk.size) chunk.copyOf() else chunk.copyOfRange(offset, offset + length)
            val ok = state.lock.withLock {
                if (state.isDisposed || state.isFinished) return false
                accumulator.writeBytes(slice, 0, slice.size)
            }
            if (!ok) return false
            state.recordWrite(slice.size)
            state.emitWrite(ByteBuffer.wrap(slice, byteOrder = byteOrder))
            return true
        }

        override fun onWrite(listener: (ByteBuffer) -> Unit): Writable {
            state.addWriteListener(listener)
            return this
        }

        override fun onDrain(listener: suspend () -> Unit): Writable {
            state.addDrainListener(listener)
            return this
        }

        override fun onFinish(listener: suspend () -> Unit): Writable {
            state.addFinishListener(listener)
            return this
        }

        override fun onError(listener: (Throwable) -> Unit): Writable {
            state.addErrorListener(listener)
            return this
        }

        override fun onClose(listener: () -> Unit): Writable {
            state.addCloseListener(listener)
            return this
        }

        override suspend fun flush() {
            val (bytes, appendForCommit) = state.lock.withLock {
                val data = accumulator.toByteArray()
                val shouldAppend = appendMode || flushedOnce
                accumulator.clear()
                data to shouldAppend
            }
            if (bytes.isEmpty() && flushedOnce) return
            commitBytes(bytes, appendForCommit)
            state.lock.withLock { flushedOnce = true }
            state.emitDrain()
        }

        override suspend fun end(finalBuffer: ByteBuffer?) {
            if (finalBuffer != null && finalBuffer.hasRemaining) {
                write(finalBuffer)
            }
            flush()
            state.emitFinish()
            state.markDisposedAndNotifyClose()
        }

        override suspend fun close() {
            state.markDisposedAndNotifyClose()
        }

        override fun dispose() {
            state.markDisposedAndNotifyClose()
        }
    }

    private companion object {
        const val BLOB_KV_TABLE = "__genericui_kv_blobs"
    }
}
