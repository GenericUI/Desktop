@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.nexustools.genericui.linux

import com.nexustools.genericui.io.ByteBuffer
import com.nexustools.genericui.io.ByteOrder
import com.nexustools.genericui.nativeimpl.io.DatabaseReadableNativeImpl
import com.nexustools.genericui.nativeimpl.io.DatabaseWritableNativeImpl
import com.nexustools.genericui.nativeimpl.system.DatabaseNativeImpl
import com.nexustools.genericui.util.db.DatabaseColumnSpec
import com.nexustools.genericui.util.db.DatabaseColumnType
import com.nexustools.genericui.util.db.DatabasePreparedSql
import com.nexustools.genericui.util.db.DatabaseQuery
import com.nexustools.genericui.util.db.DatabaseQuerySqlTranslator
import com.nexustools.genericui.util.db.DatabaseRecord
import com.nexustools.genericui.util.db.DatabaseTableSchema
import com.nexustools.genericui.util.db.DatabaseValue
import kotlinx.cinterop.COpaquePointer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

class LinuxSqliteDatabaseNativeImpl(
    override val databaseName: String = "genericui.db",
    override val filePath: String = ":memory:",
    override val version: Int = 1
) : DatabaseNativeImpl {
    private val lock = LinuxReentrantLock()
    private val dbLock = LinuxReentrantLock()
    private val dbDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val registeredSchemas = LinkedHashMap<String, DatabaseTableSchema>()
    private var dbHandle: COpaquePointer? = null
    private var disposedState = false

    override val isDisposed: Boolean
        get() = lock.withLock { disposedState }

    override suspend fun defineTable(schema: DatabaseTableSchema): Boolean {
        if (isDisposed) return false
        val normalizedSchema = normalizeSchemaWithPrimaryKey(schema)
        val tableName = normalizedSchema.tableName
        val statements = DatabaseQuerySqlTranslator.buildCreateTableSql(normalizedSchema)

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
            inTransaction(db) {
                val schema = ensureTableForRecord(db, tableName, record)
                for ((rawCol, value) in record.values) {
                    val col = DatabaseQuerySqlTranslator.sanitizeIdentifier(rawCol)
                    val existingCols = queryTableColumnNames(db, tableName)
                    if (!existingCols.contains(col)) {
                        val affinity = (value.type ?: DatabaseColumnType.STRING).sqlAffinity
                        val alterSql = "ALTER TABLE ${DatabaseQuerySqlTranslator.quoteIdentifier(tableName)} " +
                            "ADD COLUMN ${DatabaseQuerySqlTranslator.quoteIdentifier(col)} $affinity"
                        executeRawUpdate(db, alterSql)
                    }
                }
                val prepared = DatabaseQuerySqlTranslator.buildUpsertRecordSql(
                    table = tableName,
                    record = record,
                    primaryKeyColumn = schema.primaryKeyColumn
                )
                executePreparedUpdate(db, prepared)
            }
            true
        }
    }

    override suspend fun getRecord(table: String, key: String): DatabaseRecord? {
        if (isDisposed) return null
        val tableName = DatabaseQuerySqlTranslator.sanitizeIdentifier(table)
        return executeOnDbQueue { db ->
            if (!tableExistsSync(db, tableName)) return@executeOnDbQueue null
            val schema = resolveOrLoadSchema(db, tableName)
            val pkCol = DatabaseQuerySqlTranslator.quoteIdentifier(schema.primaryKeyColumn)
            val qTable = DatabaseQuerySqlTranslator.quoteIdentifier(tableName)
            executePreparedSelect(
                db,
                DatabasePreparedSql("SELECT * FROM $qTable WHERE $pkCol = ? LIMIT 1", listOf(DatabaseValue.of(key))),
                schema
            ).firstOrNull()
        }
    }

    override suspend fun query(query: DatabaseQuery): List<DatabaseRecord> {
        if (isDisposed) return emptyList()
        val tableName = DatabaseQuerySqlTranslator.sanitizeIdentifier(query.table)
        val normalizedQuery = query.copy(table = tableName)
        return executeOnDbQueue { db ->
            if (!tableExistsSync(db, tableName)) return@executeOnDbQueue emptyList()
            val schema = resolveOrLoadSchema(db, tableName)
            val prepared = DatabaseQuerySqlTranslator.buildSelectSql(normalizedQuery)
            executePreparedSelect(db, prepared, schema)
        }
    }

    override suspend fun count(query: DatabaseQuery): Long {
        if (isDisposed) return 0L
        val tableName = DatabaseQuerySqlTranslator.sanitizeIdentifier(query.table)
        val normalizedQuery = query.copy(table = tableName)
        return executeOnDbQueue { db ->
            if (!tableExistsSync(db, tableName)) return@executeOnDbQueue 0L
            val prepared = DatabaseQuerySqlTranslator.buildCountSql(normalizedQuery)
            val stmt = LinuxSqliteBindings.prepareV2(db, prepared.sql)
            try {
                bindParameters(stmt, prepared.bindValues)
                if (LinuxSqliteBindings.step(stmt) == LinuxSqliteBindings.SQLITE_ROW) {
                    LinuxSqliteBindings.columnInt64(stmt, 0)
                } else {
                    0L
                }
            } finally {
                LinuxSqliteBindings.finalize(stmt)
            }
        }
    }

    override suspend fun deleteWhere(query: DatabaseQuery): Int {
        if (isDisposed) return 0
        val tableName = DatabaseQuerySqlTranslator.sanitizeIdentifier(query.table)
        val normalizedQuery = query.copy(table = tableName)
        return executeOnDbQueue { db ->
            if (!tableExistsSync(db, tableName)) return@executeOnDbQueue 0
            val prepared = DatabaseQuerySqlTranslator.buildDeleteSql(normalizedQuery)
            executePreparedUpdate(db, prepared)
            LinuxSqliteBindings.changes(db)
        }
    }

    override suspend fun read(table: String, key: String): ByteBuffer? {
        if (isDisposed) return null
        val tableName = DatabaseQuerySqlTranslator.sanitizeIdentifier(table)
        return executeOnDbQueue { db ->
            val blob = readBlobBytesSync(db, tableName, key) ?: return@executeOnDbQueue null
            ByteBuffer.wrap(blob, byteOrder = ByteOrder.LITTLE_ENDIAN)
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
        return LinuxDatabaseReadableNativeImpl(
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
        return LinuxDatabaseWritableNativeImpl(
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
            val stmt = LinuxSqliteBindings.prepareV2(db, "SELECT 1 FROM $qTable WHERE $pkCol = ? LIMIT 1")
            try {
                LinuxSqliteBindings.bindText(stmt, 1, key)
                LinuxSqliteBindings.step(stmt) == LinuxSqliteBindings.SQLITE_ROW
            } finally {
                LinuxSqliteBindings.finalize(stmt)
            }
        }
    }

    override suspend fun byteSize(table: String, key: String): Long {
        if (isDisposed) return -1L
        val tableName = DatabaseQuerySqlTranslator.sanitizeIdentifier(table)
        return executeOnDbQueue { db ->
            if (tableExistsSync(db, BLOB_KV_TABLE)) {
                val stmt = LinuxSqliteBindings.prepareV2(
                    db,
                    "SELECT length(payload) FROM $BLOB_KV_TABLE WHERE table_name = ? AND key_id = ? LIMIT 1"
                )
                try {
                    LinuxSqliteBindings.bindText(stmt, 1, tableName)
                    LinuxSqliteBindings.bindText(stmt, 2, key)
                    if (LinuxSqliteBindings.step(stmt) == LinuxSqliteBindings.SQLITE_ROW) {
                        return@executeOnDbQueue LinuxSqliteBindings.columnInt64(stmt, 0)
                    }
                } finally {
                    LinuxSqliteBindings.finalize(stmt)
                }
            }
            if (!tableExistsSync(db, tableName)) return@executeOnDbQueue -1L
            val blobCol = findBlobColumnName(db, tableName)
            if (blobCol != null) {
                val schema = resolveOrLoadSchema(db, tableName)
                val qTable = DatabaseQuerySqlTranslator.quoteIdentifier(tableName)
                val pkCol = DatabaseQuerySqlTranslator.quoteIdentifier(schema.primaryKeyColumn)
                val qBlob = DatabaseQuerySqlTranslator.quoteIdentifier(blobCol)
                val stmt = LinuxSqliteBindings.prepareV2(db, "SELECT length($qBlob) FROM $qTable WHERE $pkCol = ? LIMIT 1")
                try {
                    LinuxSqliteBindings.bindText(stmt, 1, key)
                    if (LinuxSqliteBindings.step(stmt) == LinuxSqliteBindings.SQLITE_ROW &&
                        LinuxSqliteBindings.columnType(stmt, 0) != LinuxSqliteBindings.SQLITE_NULL
                    ) {
                        return@executeOnDbQueue LinuxSqliteBindings.columnInt64(stmt, 0)
                    }
                } finally {
                    LinuxSqliteBindings.finalize(stmt)
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
                    if (LinuxSqliteBindings.changes(db) > 0) deletedAny = true
                }
                if (tableExistsSync(db, tableName)) {
                    val schema = resolveOrLoadSchema(db, tableName)
                    val qTable = DatabaseQuerySqlTranslator.quoteIdentifier(tableName)
                    val pkCol = DatabaseQuerySqlTranslator.quoteIdentifier(schema.primaryKeyColumn)
                    executePreparedUpdate(
                        db,
                        DatabasePreparedSql("DELETE FROM $qTable WHERE $pkCol = ?", listOf(DatabaseValue.of(key)))
                    )
                    if (LinuxSqliteBindings.changes(db) > 0) deletedAny = true
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
                val stmt = LinuxSqliteBindings.prepareV2(db, sql)
                try {
                    if (prefix.isNotEmpty()) {
                        LinuxSqliteBindings.bindText(stmt, 1, "${escapeLike(prefix)}%")
                    }
                    while (LinuxSqliteBindings.step(stmt) == LinuxSqliteBindings.SQLITE_ROW) {
                        result.add(LinuxSqliteBindings.columnText(stmt, 0))
                    }
                } finally {
                    LinuxSqliteBindings.finalize(stmt)
                }
            }
            if (tableExistsSync(db, BLOB_KV_TABLE)) {
                val sql = if (prefix.isEmpty()) {
                    "SELECT key_id FROM $BLOB_KV_TABLE WHERE table_name = ? ORDER BY key_id ASC"
                } else {
                    "SELECT key_id FROM $BLOB_KV_TABLE WHERE table_name = ? AND key_id LIKE ? ESCAPE '\\' ORDER BY key_id ASC"
                }
                val stmt = LinuxSqliteBindings.prepareV2(db, sql)
                try {
                    LinuxSqliteBindings.bindText(stmt, 1, tableName)
                    if (prefix.isNotEmpty()) {
                        LinuxSqliteBindings.bindText(stmt, 2, "${escapeLike(prefix)}%")
                    }
                    while (LinuxSqliteBindings.step(stmt) == LinuxSqliteBindings.SQLITE_ROW) {
                        result.add(LinuxSqliteBindings.columnText(stmt, 0))
                    }
                } finally {
                    LinuxSqliteBindings.finalize(stmt)
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
                    removed += LinuxSqliteBindings.changes(db)
                }
                if (tableExistsSync(db, BLOB_KV_TABLE)) {
                    executePreparedUpdate(
                        db,
                        DatabasePreparedSql("DELETE FROM $BLOB_KV_TABLE WHERE table_name = ?", listOf(DatabaseValue.of(tableName)))
                    )
                    removed += LinuxSqliteBindings.changes(db)
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
            val stmt = LinuxSqliteBindings.prepareV2(
                db,
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name ASC"
            )
            try {
                while (LinuxSqliteBindings.step(stmt) == LinuxSqliteBindings.SQLITE_ROW) {
                    val name = LinuxSqliteBindings.columnText(stmt, 0)
                    if (name != BLOB_KV_TABLE) {
                        out.add(name)
                    }
                }
            } finally {
                LinuxSqliteBindings.finalize(stmt)
            }
            if (tableExistsSync(db, BLOB_KV_TABLE)) {
                val kvStmt = LinuxSqliteBindings.prepareV2(db, "SELECT DISTINCT table_name FROM $BLOB_KV_TABLE ORDER BY table_name ASC")
                try {
                    while (LinuxSqliteBindings.step(kvStmt) == LinuxSqliteBindings.SQLITE_ROW) {
                        out.add(LinuxSqliteBindings.columnText(kvStmt, 0))
                    }
                } finally {
                    LinuxSqliteBindings.finalize(kvStmt)
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
                val stmt = LinuxSqliteBindings.prepareV2(
                    db,
                    "SELECT COALESCE(SUM(length(payload)), 0) FROM $BLOB_KV_TABLE WHERE table_name = ?"
                )
                try {
                    LinuxSqliteBindings.bindText(stmt, 1, tableName)
                    if (LinuxSqliteBindings.step(stmt) == LinuxSqliteBindings.SQLITE_ROW) {
                        total += LinuxSqliteBindings.columnInt64(stmt, 0)
                    }
                } finally {
                    LinuxSqliteBindings.finalize(stmt)
                }
            }
            if (total == 0L && tableExistsSync(db, tableName)) {
                val blobCol = findBlobColumnName(db, tableName)
                if (blobCol != null) {
                    val qTable = DatabaseQuerySqlTranslator.quoteIdentifier(tableName)
                    val qBlob = DatabaseQuerySqlTranslator.quoteIdentifier(blobCol)
                    val stmt = LinuxSqliteBindings.prepareV2(db, "SELECT COALESCE(SUM(length($qBlob)), 0) FROM $qTable")
                    try {
                        if (LinuxSqliteBindings.step(stmt) == LinuxSqliteBindings.SQLITE_ROW) {
                            total += LinuxSqliteBindings.columnInt64(stmt, 0)
                        }
                    } finally {
                        LinuxSqliteBindings.finalize(stmt)
                    }
                }
            }
            total
        }
    }

    suspend fun <T> withTransaction(block: () -> T): T {
        return executeOnDbQueue { db ->
            inTransaction(db) {
                block()
            }
        }
    }

    override fun dispose() {
        val alreadyDisposed = lock.withLock {
            if (disposedState) true else {
                disposedState = true
                false
            }
        }
        if (alreadyDisposed) return

        dbLock.withLock {
            val handle = dbHandle
            dbHandle = null
            if (handle != null) {
                LinuxSqliteBindings.closeV2(handle)
            }
        }
    }

    private suspend fun <T> executeOnDbQueue(block: (COpaquePointer) -> T): T {
        return withContext(dbDispatcher) {
            dbLock.withLock {
                if (isDisposed) {
                    error("Database '$databaseName' is disposed")
                }
                val db = ensureOpenLocked()
                block(db)
            }
        }
    }

    private fun ensureOpenLocked(): COpaquePointer {
        dbHandle?.let { return it }
        if (filePath != ":memory:" && !filePath.startsWith("file:", ignoreCase = true)) {
            val parent = LinuxPosixPaths.parentOf(filePath)
            if (parent.isNotEmpty()) {
                LinuxPosixPaths.ensureDirectoryHierarchy(parent)
            }
        }
        val db = LinuxSqliteBindings.openV2(filePath)
        executeRawUpdate(db, "PRAGMA journal_mode=WAL")
        executeRawUpdate(db, "PRAGMA foreign_keys=ON")
        executeRawUpdate(db, "PRAGMA busy_timeout=5000")
        executeRawUpdate(db, "PRAGMA user_version=${version.coerceAtLeast(0)}")
        dbHandle = db
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
        val stmt = LinuxSqliteBindings.prepareV2(db, sql)
        try {
            var rc = LinuxSqliteBindings.step(stmt)
            while (rc == LinuxSqliteBindings.SQLITE_ROW) {
                rc = LinuxSqliteBindings.step(stmt)
            }
            if (rc != LinuxSqliteBindings.SQLITE_DONE) {
                error("sqlite3_step failed ($rc) for SQL [$sql]: ${LinuxSqliteBindings.errmsg(db)}")
            }
        } finally {
            LinuxSqliteBindings.finalize(stmt)
        }
    }

    private fun executePreparedUpdate(db: COpaquePointer, prepared: DatabasePreparedSql) {
        val stmt = LinuxSqliteBindings.prepareV2(db, prepared.sql)
        try {
            bindParameters(stmt, prepared.bindValues)
            val rc = LinuxSqliteBindings.step(stmt)
            if (rc != LinuxSqliteBindings.SQLITE_DONE && rc != LinuxSqliteBindings.SQLITE_ROW) {
                error("sqlite3_step failed ($rc) for SQL [${prepared.sql}]: ${LinuxSqliteBindings.errmsg(db)}")
            }
        } finally {
            LinuxSqliteBindings.finalize(stmt)
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

        val stmt = LinuxSqliteBindings.prepareV2(db, prepared.sql)
        try {
            bindParameters(stmt, prepared.bindValues)
            val colCount = LinuxSqliteBindings.columnCount(stmt)
            val colNames = Array(colCount) { idx -> LinuxSqliteBindings.columnName(stmt, idx) }
            val pkColIdx = colNames.indexOfFirst { it == pkName }.takeIf { it >= 0 }
                ?: colNames.indexOfFirst { it == "key_id" || it == "id" || it == "key" }

            val results = ArrayList<DatabaseRecord>()
            var rc = LinuxSqliteBindings.step(stmt)
            while (rc == LinuxSqliteBindings.SQLITE_ROW) {
                val valuesMap = LinkedHashMap<String, DatabaseValue>()
                var recordKey = ""
                for (i in 0 until colCount) {
                    val colName = colNames[i]
                    val declaredType = colTypesByName[colName]
                    val dbValue = readColumnValue(stmt, i, declaredType)
                    if (i == pkColIdx || colName == pkName) {
                        recordKey = dbValue.asString() ?: ""
                    } else {
                        valuesMap[colName] = dbValue
                    }
                }
                results.add(DatabaseRecord(key = recordKey, values = valuesMap))
                rc = LinuxSqliteBindings.step(stmt)
            }
            if (rc != LinuxSqliteBindings.SQLITE_DONE) {
                error("sqlite3_step SELECT failed ($rc) for [${prepared.sql}]: ${LinuxSqliteBindings.errmsg(db)}")
            }
            return results
        } finally {
            LinuxSqliteBindings.finalize(stmt)
        }
    }

    private fun readColumnValue(
        stmt: COpaquePointer,
        colIdx: Int,
        declaredType: DatabaseColumnType?
    ): DatabaseValue {
        return when (LinuxSqliteBindings.columnType(stmt, colIdx)) {
            LinuxSqliteBindings.SQLITE_NULL -> DatabaseValue.Null
            LinuxSqliteBindings.SQLITE_INTEGER -> {
                val rawLong = LinuxSqliteBindings.columnInt64(stmt, colIdx)
                if (declaredType == DatabaseColumnType.BOOLEAN) {
                    DatabaseValue.of(rawLong != 0L)
                } else {
                    DatabaseValue.of(rawLong)
                }
            }
            LinuxSqliteBindings.SQLITE_FLOAT -> DatabaseValue.of(LinuxSqliteBindings.columnDouble(stmt, colIdx))
            LinuxSqliteBindings.SQLITE_TEXT -> DatabaseValue.of(LinuxSqliteBindings.columnText(stmt, colIdx))
            LinuxSqliteBindings.SQLITE_BLOB -> DatabaseValue.of(LinuxSqliteBindings.columnBlob(stmt, colIdx))
            else -> DatabaseValue.Null
        }
    }

    private fun bindParameters(stmt: COpaquePointer, bindValues: List<DatabaseValue>) {
        for ((zeroIdx, value) in bindValues.withIndex()) {
            val idx = zeroIdx + 1
            val rc = when {
                value.isNull || value.type == null -> LinuxSqliteBindings.bindNull(stmt, idx)
                value.type == DatabaseColumnType.STRING -> LinuxSqliteBindings.bindText(stmt, idx, value.asString() ?: "")
                value.type == DatabaseColumnType.INT64 -> LinuxSqliteBindings.bindInt64(stmt, idx, value.asLong() ?: 0L)
                value.type == DatabaseColumnType.FLOAT64 -> LinuxSqliteBindings.bindDouble(stmt, idx, value.asDouble() ?: 0.0)
                value.type == DatabaseColumnType.BOOLEAN -> LinuxSqliteBindings.bindInt64(stmt, idx, if (value.asBoolean() == true) 1L else 0L)
                value.type == DatabaseColumnType.BLOB -> LinuxSqliteBindings.bindBlob(stmt, idx, value.asBytes() ?: ByteArray(0))
                else -> LinuxSqliteBindings.bindNull(stmt, idx)
            }
            if (rc != LinuxSqliteBindings.SQLITE_OK) {
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
        for (prep in DatabaseQuerySqlTranslator.buildCreateTableSql(defaultSchema)) {
            executePreparedUpdate(db, prep)
        }
        lock.withLock { registeredSchemas[tableName] = defaultSchema }
        return defaultSchema
    }

    private fun resolveOrLoadSchema(db: COpaquePointer, tableName: String): DatabaseTableSchema {
        lock.withLock { registeredSchemas[tableName] }?.let { return it }
        val qTable = DatabaseQuerySqlTranslator.quoteIdentifier(tableName)
        val stmt = LinuxSqliteBindings.prepareV2(db, "PRAGMA table_info($qTable)")
        var pkCol = "key"
        val cols = ArrayList<DatabaseColumnSpec>()
        try {
            while (LinuxSqliteBindings.step(stmt) == LinuxSqliteBindings.SQLITE_ROW) {
                val colName = LinuxSqliteBindings.columnText(stmt, 1)
                val colTypeStr = LinuxSqliteBindings.columnText(stmt, 2).uppercase()
                val notNull = LinuxSqliteBindings.columnInt64(stmt, 3) != 0L
                val isPk = LinuxSqliteBindings.columnInt64(stmt, 5) > 0L
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
            LinuxSqliteBindings.finalize(stmt)
        }
        val loaded = DatabaseTableSchema(tableName = tableName, primaryKeyColumn = pkCol, columns = cols)
        lock.withLock { registeredSchemas[tableName] = loaded }
        return loaded
    }

    private fun tableExistsSync(db: COpaquePointer, tableName: String): Boolean {
        val stmt = LinuxSqliteBindings.prepareV2(
            db,
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1"
        )
        try {
            LinuxSqliteBindings.bindText(stmt, 1, tableName)
            return LinuxSqliteBindings.step(stmt) == LinuxSqliteBindings.SQLITE_ROW
        } finally {
            LinuxSqliteBindings.finalize(stmt)
        }
    }

    private fun queryTableColumnNames(db: COpaquePointer, tableName: String): Set<String> {
        val qTable = DatabaseQuerySqlTranslator.quoteIdentifier(tableName)
        val stmt = LinuxSqliteBindings.prepareV2(db, "PRAGMA table_info($qTable)")
        val cols = LinkedHashSet<String>()
        try {
            while (LinuxSqliteBindings.step(stmt) == LinuxSqliteBindings.SQLITE_ROW) {
                cols.add(LinuxSqliteBindings.columnText(stmt, 1))
            }
        } finally {
            LinuxSqliteBindings.finalize(stmt)
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
        val stmt = LinuxSqliteBindings.prepareV2(
            db,
            "SELECT 1 FROM $BLOB_KV_TABLE WHERE table_name = ? AND key_id = ? LIMIT 1"
        )
        try {
            LinuxSqliteBindings.bindText(stmt, 1, tableName)
            LinuxSqliteBindings.bindText(stmt, 2, key)
            return LinuxSqliteBindings.step(stmt) == LinuxSqliteBindings.SQLITE_ROW
        } finally {
            LinuxSqliteBindings.finalize(stmt)
        }
    }

    private fun readBlobBytesSync(db: COpaquePointer, tableName: String, key: String): ByteArray? {
        if (tableExistsSync(db, BLOB_KV_TABLE)) {
            val stmt = LinuxSqliteBindings.prepareV2(
                db,
                "SELECT payload FROM $BLOB_KV_TABLE WHERE table_name = ? AND key_id = ? LIMIT 1"
            )
            try {
                LinuxSqliteBindings.bindText(stmt, 1, tableName)
                LinuxSqliteBindings.bindText(stmt, 2, key)
                if (LinuxSqliteBindings.step(stmt) == LinuxSqliteBindings.SQLITE_ROW) {
                    return LinuxSqliteBindings.columnBlob(stmt, 0)
                }
            } finally {
                LinuxSqliteBindings.finalize(stmt)
            }
        }
        if (tableExistsSync(db, tableName)) {
            val blobCol = findBlobColumnName(db, tableName)
            if (blobCol != null) {
                val schema = resolveOrLoadSchema(db, tableName)
                val qTable = DatabaseQuerySqlTranslator.quoteIdentifier(tableName)
                val pkCol = DatabaseQuerySqlTranslator.quoteIdentifier(schema.primaryKeyColumn)
                val qBlob = DatabaseQuerySqlTranslator.quoteIdentifier(blobCol)
                val stmt = LinuxSqliteBindings.prepareV2(db, "SELECT $qBlob FROM $qTable WHERE $pkCol = ? LIMIT 1")
                try {
                    LinuxSqliteBindings.bindText(stmt, 1, key)
                    if (LinuxSqliteBindings.step(stmt) == LinuxSqliteBindings.SQLITE_ROW &&
                        LinuxSqliteBindings.columnType(stmt, 0) != LinuxSqliteBindings.SQLITE_NULL
                    ) {
                        return LinuxSqliteBindings.columnBlob(stmt, 0)
                    }
                } finally {
                    LinuxSqliteBindings.finalize(stmt)
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
            val stmt = LinuxSqliteBindings.prepareV2(
                db,
                "SELECT substr(payload, ?, ?) FROM $BLOB_KV_TABLE WHERE table_name = ? AND key_id = ? LIMIT 1"
            )
            try {
                LinuxSqliteBindings.bindInt64(stmt, 1, sqlOffset)
                LinuxSqliteBindings.bindInt64(stmt, 2, maxBytes.toLong())
                LinuxSqliteBindings.bindText(stmt, 3, tableName)
                LinuxSqliteBindings.bindText(stmt, 4, key)
                if (LinuxSqliteBindings.step(stmt) == LinuxSqliteBindings.SQLITE_ROW) {
                    return LinuxSqliteBindings.columnBlob(stmt, 0)
                }
            } finally {
                LinuxSqliteBindings.finalize(stmt)
            }
        }
        if (tableExistsSync(db, tableName)) {
            val blobCol = findBlobColumnName(db, tableName)
            if (blobCol != null) {
                val schema = resolveOrLoadSchema(db, tableName)
                val qTable = DatabaseQuerySqlTranslator.quoteIdentifier(tableName)
                val pkCol = DatabaseQuerySqlTranslator.quoteIdentifier(schema.primaryKeyColumn)
                val qBlob = DatabaseQuerySqlTranslator.quoteIdentifier(blobCol)
                val stmt = LinuxSqliteBindings.prepareV2(
                    db,
                    "SELECT substr($qBlob, ?, ?) FROM $qTable WHERE $pkCol = ? LIMIT 1"
                )
                try {
                    LinuxSqliteBindings.bindInt64(stmt, 1, sqlOffset)
                    LinuxSqliteBindings.bindInt64(stmt, 2, maxBytes.toLong())
                    LinuxSqliteBindings.bindText(stmt, 3, key)
                    if (LinuxSqliteBindings.step(stmt) == LinuxSqliteBindings.SQLITE_ROW &&
                        LinuxSqliteBindings.columnType(stmt, 0) != LinuxSqliteBindings.SQLITE_NULL
                    ) {
                        return LinuxSqliteBindings.columnBlob(stmt, 0)
                    }
                } finally {
                    LinuxSqliteBindings.finalize(stmt)
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
            val stmt = LinuxSqliteBindings.prepareV2(
                db,
                "INSERT INTO $BLOB_KV_TABLE (table_name, key_id, payload) VALUES (?, ?, ?) " +
                    "ON CONFLICT(table_name, key_id) DO UPDATE SET payload = excluded.payload"
            )
            try {
                LinuxSqliteBindings.bindText(stmt, 1, tableName)
                LinuxSqliteBindings.bindText(stmt, 2, key)
                LinuxSqliteBindings.bindBlob(stmt, 3, finalBytes)
                val rc = LinuxSqliteBindings.step(stmt)
                if (rc != LinuxSqliteBindings.SQLITE_DONE) {
                    error("Failed to upsert SQLite BLOB for $tableName/$key ($rc): ${LinuxSqliteBindings.errmsg(db)}")
                }
            } finally {
                LinuxSqliteBindings.finalize(stmt)
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

    private companion object {
        const val BLOB_KV_TABLE = "__genericui_kv_blobs"
    }
}
