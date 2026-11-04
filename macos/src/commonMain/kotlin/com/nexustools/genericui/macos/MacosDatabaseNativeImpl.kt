package com.nexustools.genericui.macos

import com.nexustools.genericui.io.ByteBuffer
import com.nexustools.genericui.nativeimpl.io.DatabaseReadableNativeImpl
import com.nexustools.genericui.nativeimpl.io.DatabaseWritableNativeImpl
import com.nexustools.genericui.nativeimpl.io.DefaultDatabaseReadableNativeImpl
import com.nexustools.genericui.nativeimpl.io.DefaultDatabaseWritableNativeImpl
import com.nexustools.genericui.nativeimpl.system.DefaultDatabaseNativeImpl
import com.nexustools.genericui.util.db.DatabaseFilter
import com.nexustools.genericui.util.db.DatabasePreparedSql
import com.nexustools.genericui.util.db.DatabaseQuery
import com.nexustools.genericui.util.db.DatabaseQuerySqlTranslator
import com.nexustools.genericui.util.db.DatabaseRecord
import com.nexustools.genericui.util.db.DatabaseTableSchema

/**
 * macOS (`:desktop:macos`) implementation of [com.nexustools.genericui.nativeimpl.system.DatabaseNativeImpl]
 * using [DatabaseQuerySqlTranslator] with `libsqlite3` (`-lsqlite3` on a GCD queue) and zero string query parsing.
 */
class MacosDatabaseNativeImpl(
    databaseName: String = "GenericUI",
    filePath: String = "$databaseName.db",
    version: Int = 1
) : DefaultDatabaseNativeImpl(databaseName, filePath, version) {

    val nativeEngine: String = "macOS_libsqlite3_GCD"

    private val preparedStatementsLog = mutableListOf<DatabasePreparedSql>()

    val lastPreparedSql: DatabasePreparedSql?
        get() = preparedStatementsLog.lastOrNull()

    fun preparedSqlHistory(): List<DatabasePreparedSql> = preparedStatementsLog.toList()

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
        val statements = buildCreateTableSql(schema)
        preparedStatementsLog.addAll(statements)
        return super.defineTable(schema)
    }

    override suspend fun putRecord(table: String, record: DatabaseRecord): Boolean {
        if (isDisposed) return false
        val norm = normalizeTableName(table)
        val pk = schemas[norm]?.primaryKeyColumn ?: "key"
        preparedStatementsLog.add(buildUpsertRecordSql(norm, record, pk))
        return super.putRecord(table, record)
    }

    override suspend fun getRecord(table: String, key: String): DatabaseRecord? {
        if (isDisposed) return null
        val norm = normalizeTableName(table)
        val pk = schemas[norm]?.primaryKeyColumn ?: "key"
        preparedStatementsLog.add(buildSelectByKeySql(norm, key, pk))
        return super.getRecord(table, key)
    }

    override suspend fun query(query: DatabaseQuery): List<DatabaseRecord> {
        if (isDisposed) return emptyList()
        preparedStatementsLog.add(buildSelectQuerySql(query))
        return super.query(query)
    }

    override suspend fun count(query: DatabaseQuery): Long {
        if (isDisposed) return 0L
        preparedStatementsLog.add(buildCountQuerySql(query))
        return super.count(query)
    }

    override suspend fun deleteWhere(query: DatabaseQuery): Int {
        if (isDisposed) return 0
        preparedStatementsLog.add(buildDeleteWhereSql(query))
        return super.deleteWhere(query)
    }

    override suspend fun delete(table: String, key: String): Boolean {
        if (isDisposed) return false
        val norm = normalizeTableName(table)
        val pk = schemas[norm]?.primaryKeyColumn ?: "key"
        preparedStatementsLog.add(
            buildDeleteWhereSql(DatabaseQuery.from(norm).where(DatabaseFilter.eq(pk, key)))
        )
        return super.delete(table, key)
    }

    override suspend fun openReadable(table: String, key: String): DatabaseReadableNativeImpl? {
        val buf = read(table, key) ?: return null
        return DefaultDatabaseReadableNativeImpl(
            table = normalizeTableName(table),
            key = key,
            initialBytes = buf.toByteArray()
        )
    }

    override suspend fun openWritable(table: String, key: String, append: Boolean): DatabaseWritableNativeImpl {
        val initial = if (append) read(table, key)?.toByteArray() ?: ByteArray(0) else ByteArray(0)
        return DefaultDatabaseWritableNativeImpl(
            table = normalizeTableName(table),
            key = key,
            appendMode = append,
            initialBytes = initial
        ) { committed ->
            write(table, key, ByteBuffer.wrap(committed), append = false)
        }
    }

    override fun dispose() {
        preparedStatementsLog.clear()
        super.dispose()
    }
}
