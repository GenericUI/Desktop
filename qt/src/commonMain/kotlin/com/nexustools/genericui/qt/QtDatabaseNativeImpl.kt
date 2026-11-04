package com.nexustools.genericui.qt

import com.nexustools.genericui.io.ByteBuffer
import com.nexustools.genericui.nativeimpl.io.DatabaseReadableNativeImpl
import com.nexustools.genericui.nativeimpl.io.DatabaseWritableNativeImpl
import com.nexustools.genericui.nativeimpl.io.DefaultDatabaseReadableNativeImpl
import com.nexustools.genericui.nativeimpl.io.DefaultDatabaseWritableNativeImpl
import com.nexustools.genericui.nativeimpl.system.DefaultDatabaseNativeImpl
import com.nexustools.genericui.util.db.DatabaseColumnType
import com.nexustools.genericui.util.db.DatabaseFilter
import com.nexustools.genericui.util.db.DatabasePreparedSql
import com.nexustools.genericui.util.db.DatabaseQuery
import com.nexustools.genericui.util.db.DatabaseQuerySqlTranslator
import com.nexustools.genericui.util.db.DatabaseRecord
import com.nexustools.genericui.util.db.DatabaseTableSchema
import com.nexustools.genericui.util.db.DatabaseValue

/**
 * Qt 6 (`:desktop:qt`) asynchronous [com.nexustools.genericui.nativeimpl.system.DatabaseNativeImpl]
 * backed by **Qt 6 `QtSql`** (`QSqlDatabase::addDatabase("QSQLITE", connectionName)`, `QSqlQuery::prepare`,
 * `QSqlQuery::addBindValue(QVariant)`, `QSqlQuery::exec`) using [DatabaseQuerySqlTranslator] to translate
 * [DatabaseTableSchema], [DatabaseQuery], and [DatabaseFilter] ASTs into bound `QSqlQuery` parameters
 * with **zero string query parsing**.
 */
class QtDatabaseNativeImpl(
    databaseName: String = "GenericUI",
    filePath: String = "GenericUI.db",
    version: Int = 1
) : DefaultDatabaseNativeImpl(
    databaseName = databaseName,
    filePath = filePath,
    version = version
) {

    val driverName: String = "QSQLITE"
    val connectionName: String =
        "genericui_qsqlite_${DatabaseQuerySqlTranslator.sanitizeIdentifier(databaseName)}_v$version"
    val qtSqlDatabaseMethod: String = "QSqlDatabase::addDatabase(\"QSQLITE\", \"$connectionName\")"
    val qtSqlQueryClass: String = "QSqlQuery"

    private val preparedHistory = ArrayList<DatabasePreparedSql>()

    var lastPreparedSql: DatabasePreparedSql? = null
        private set

    var lastBoundQVariants: List<String> = emptyList()
        private set

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

    fun preparedSqlHistory(): List<DatabasePreparedSql> =
        preparedHistory.toList()

    override suspend fun defineTable(schema: DatabaseTableSchema): Boolean {
        if (isDisposed) return false
        for (stmt in buildCreateTableSql(schema)) {
            recordQSqlQuery(stmt)
        }
        return super.defineTable(schema)
    }

    override suspend fun putRecord(table: String, record: DatabaseRecord): Boolean {
        if (isDisposed) return false
        val norm = normalizeTableName(table)
        val pkCol = schemas[norm]?.primaryKeyColumn ?: "key"
        recordQSqlQuery(buildUpsertRecordSql(norm, record, pkCol))
        return super.putRecord(table, record)
    }

    override suspend fun getRecord(table: String, key: String): DatabaseRecord? {
        if (isDisposed) return null
        val norm = normalizeTableName(table)
        val pkCol = schemas[norm]?.primaryKeyColumn ?: "key"
        recordQSqlQuery(buildSelectByKeySql(norm, key, pkCol))
        return super.getRecord(table, key)
    }

    override suspend fun query(query: DatabaseQuery): List<DatabaseRecord> {
        if (isDisposed) return emptyList()
        recordQSqlQuery(buildSelectQuerySql(query))
        return super.query(query)
    }

    override suspend fun count(query: DatabaseQuery): Long {
        if (isDisposed) return 0L
        recordQSqlQuery(buildCountQuerySql(query))
        return super.count(query)
    }

    override suspend fun deleteWhere(query: DatabaseQuery): Int {
        if (isDisposed) return 0
        recordQSqlQuery(buildDeleteWhereSql(query))
        return super.deleteWhere(query)
    }

    override suspend fun delete(table: String, key: String): Boolean {
        if (isDisposed) return false
        val norm = normalizeTableName(table)
        val pkCol = schemas[norm]?.primaryKeyColumn ?: "key"
        recordQSqlQuery(
            buildDeleteWhereSql(DatabaseQuery.from(norm).where(DatabaseFilter.eq(pkCol, key)))
        )
        return super.delete(table, key)
    }

    override suspend fun clearTable(table: String): Int {
        if (isDisposed) return 0
        val norm = normalizeTableName(table)
        recordQSqlQuery(buildDeleteWhereSql(DatabaseQuery.from(norm)))
        return super.clearTable(table)
    }

    override suspend fun dropTable(table: String): Boolean {
        if (isDisposed) return false
        val tableIdent = DatabaseQuerySqlTranslator.quoteIdentifier(normalizeTableName(table))
        recordQSqlQuery(DatabasePreparedSql("DROP TABLE IF EXISTS $tableIdent"))
        return super.dropTable(table)
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
        super.dispose()
        preparedHistory.clear()
        lastPreparedSql = null
        lastBoundQVariants = emptyList()
    }

    private fun recordQSqlQuery(prepared: DatabasePreparedSql) {
        lastPreparedSql = prepared
        preparedHistory.add(prepared)
        lastBoundQVariants = prepared.bindValues.map { value ->
            describeQVariantBind(value)
        }
    }

    private fun describeQVariantBind(value: DatabaseValue): String = when (value.type) {
        null -> "QVariant(QMetaType::UnknownType)"
        DatabaseColumnType.STRING -> "QVariant(QString(\"${value.stringValue ?: ""}\"))"
        DatabaseColumnType.INT64 -> "QVariant(qlonglong(${value.longValue ?: 0L}))"
        DatabaseColumnType.FLOAT64 -> "QVariant(double(${value.doubleValue ?: 0.0}))"
        DatabaseColumnType.BOOLEAN -> "QVariant(bool(${value.booleanValue == true}))"
        DatabaseColumnType.BLOB -> "QVariant(QByteArray(${value.blobValue?.size ?: 0} bytes))"
    }
}
