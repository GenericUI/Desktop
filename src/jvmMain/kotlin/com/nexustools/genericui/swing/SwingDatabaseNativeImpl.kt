package com.nexustools.genericui.swing

import com.nexustools.genericui.jvm.JvmDatabaseBase
import com.nexustools.genericui.util.db.DatabaseColumnType
import com.nexustools.genericui.util.db.DatabasePreparedSql
import com.nexustools.genericui.util.db.DatabaseQuery
import com.nexustools.genericui.util.db.DatabaseQuerySqlTranslator
import com.nexustools.genericui.util.db.DatabaseRecord
import com.nexustools.genericui.util.db.DatabaseTableSchema
import com.nexustools.genericui.util.db.DatabaseValue
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import javax.swing.SwingUtilities

/**
 * Java Swing / JVM SQLite JDBC (`org.xerial:sqlite-jdbc`) implementation of
 * [com.nexustools.genericui.nativeimpl.system.DatabaseNativeImpl], extending [JvmDatabaseBase].
 */
class SwingDatabaseNativeImpl(
    databaseName: String = "GenericUI",
    filePath: String = "GenericUI.db",
    version: Int = 1
) : JvmDatabaseBase(
    databaseName = databaseName,
    filePath = filePath,
    version = version,
    isOnMainThread = { SwingUtilities.isEventDispatchThread() }
) {

    val resolvedJdbcPath: String = resolveJdbcDatabasePath(databaseName, filePath)

    private var connection: Connection? = null

    init {
        runCatching { Class.forName("org.sqlite.JDBC") }
    }

    override suspend fun defineTable(schema: DatabaseTableSchema): Boolean {
        if (isDisposed) return false
        super.defineTable(schema)
        val norm = normalizeTableName(schema.tableName)
        return runOnDatabaseIo {
            synchronized(this) {
                val conn = getOrOpenConnection() ?: return@runOnDatabaseIo true
                val statements = DatabaseQuerySqlTranslator.buildCreateTableSql(schema)
                for (stmt in statements) {
                    executePreparedUpdate(conn, stmt)
                }
                val colMap = registerSchemaColumns(norm, schema)
                ensureRecordColumnsExist(conn, norm, colMap)
                true
            }
        }
    }

    override suspend fun putRecord(table: String, record: DatabaseRecord): Boolean {
        if (isDisposed) return false
        super.putRecord(table, record)
        val norm = normalizeTableName(table)
        return runOnDatabaseIo {
            synchronized(this) {
                val conn = getOrOpenConnection() ?: return@runOnDatabaseIo true
                val (pkCol, enrichedRecord) = enrichRecordForUpsert(norm, record)
                ensureTableAndColumnsForRecord(conn, norm, pkCol, enrichedRecord)

                val upsertSql = DatabaseQuerySqlTranslator.buildUpsertRecordSql(
                    table = norm,
                    primaryKeyColumn = pkCol,
                    record = enrichedRecord
                )
                executePreparedUpdate(conn, upsertSql)
                true
            }
        }
    }

    override suspend fun query(query: DatabaseQuery): List<DatabaseRecord> {
        if (isDisposed) return emptyList()
        val norm = normalizeTableName(query.table)
        return runOnDatabaseIo {
            synchronized(this) {
                val conn = getOrOpenConnection() ?: return@runOnDatabaseIo queryInMemoryFallback(query)
                if (!tableExistsInSqlite(conn, norm)) {
                    return@runOnDatabaseIo emptyList()
                }
                val normalizedQuery = query.copy(table = norm)
                val prepared = DatabaseQuerySqlTranslator.buildSelectSql(normalizedQuery)
                conn.prepareStatement(prepared.sql).use { stmt ->
                    bindAll(stmt, prepared.bindValues)
                    stmt.executeQuery().use { rs ->
                        readResultSetRecords(rs, norm, query.selectedColumns)
                    }
                }
            }
        }
    }

    override suspend fun count(query: DatabaseQuery): Long {
        if (isDisposed) return 0L
        val norm = normalizeTableName(query.table)
        return runOnDatabaseIo {
            synchronized(this) {
                val conn = getOrOpenConnection() ?: return@runOnDatabaseIo countInMemoryFallback(query)
                if (!tableExistsInSqlite(conn, norm)) {
                    return@runOnDatabaseIo 0L
                }
                val normalizedQuery = query.copy(table = norm)
                val prepared = DatabaseQuerySqlTranslator.buildCountSql(normalizedQuery)
                conn.prepareStatement(prepared.sql).use { stmt ->
                    bindAll(stmt, prepared.bindValues)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) rs.getLong(1) else 0L
                    }
                }
            }
        }
    }

    override suspend fun deleteWhere(query: DatabaseQuery): Int {
        if (isDisposed) return 0
        super.deleteWhere(query)
        val norm = normalizeTableName(query.table)
        return runOnDatabaseIo {
            synchronized(this) {
                val conn = getOrOpenConnection() ?: return@runOnDatabaseIo 0
                if (!tableExistsInSqlite(conn, norm)) {
                    return@runOnDatabaseIo 0
                }
                val normalizedQuery = query.copy(table = norm)
                val prepared = DatabaseQuerySqlTranslator.buildDeleteSql(normalizedQuery)
                executePreparedUpdate(conn, prepared)
            }
        }
    }

    override suspend fun dropTable(table: String): Boolean {
        if (isDisposed) return false
        super.dropTable(table)
        val norm = normalizeTableName(table)
        return runOnDatabaseIo {
            synchronized(this) {
                val conn = getOrOpenConnection() ?: return@runOnDatabaseIo false
                val existed = tableExistsInSqlite(conn, norm)
                val tableIdent = DatabaseQuerySqlTranslator.quoteIdentifier(norm)
                conn.prepareStatement("DROP TABLE IF EXISTS $tableIdent").use { it.executeUpdate() }
                knownTableColumns.remove(norm)
                existed
            }
        }
    }

    override suspend fun tables(): List<String> {
        if (isDisposed) return emptyList()
        return runOnDatabaseIo {
            synchronized(this) {
                val all = LinkedHashSet<String>()
                all.addAll(schemas.keys)
                all.addAll(knownTableColumns.keys)
                val conn = getOrOpenConnection()
                if (conn != null) {
                    conn.prepareStatement(
                        "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'"
                    ).use { stmt ->
                        stmt.executeQuery().use { rs ->
                            while (rs.next()) {
                                val name = rs.getString(1)
                                if (!name.isNullOrEmpty()) {
                                    all.add(name)
                                }
                            }
                        }
                    }
                }
                all.toList()
            }
        }
    }

    override fun dispose() {
        synchronized(this) {
            super.dispose()
            val conn = connection
            connection = null
            if (conn != null) {
                runCatching { conn.close() }
            }
        }
    }

    private fun getOrOpenConnection(): Connection? {
        val existing = connection
        if (existing != null && runCatching { !existing.isClosed }.getOrDefault(false)) {
            return existing
        }
        return try {
            val opened = DriverManager.getConnection("jdbc:sqlite:$resolvedJdbcPath")
            opened.createStatement().use { stmt ->
                stmt.execute("PRAGMA user_version = ${version.coerceAtLeast(1)}")
            }
            connection = opened
            opened
        } catch (_: Throwable) {
            null
        }
    }

    private fun ensureTableAndColumnsForRecord(
        conn: Connection,
        normTable: String,
        pkCol: String,
        record: DatabaseRecord
    ) {
        val existingCols = knownTableColumns[normTable]
        if (existingCols == null && !tableExistsInSqlite(conn, normTable)) {
            val resolvedSchema = buildOrLookupSchemaForRecord(normTable, pkCol, record)
            for (ddl in DatabaseQuerySqlTranslator.buildCreateTableSql(resolvedSchema)) {
                executePreparedUpdate(conn, ddl)
            }
        }
        val newCols = collectMissingColumnsForRecord(normTable, record) {
            loadExistingSqliteColumns(conn, normTable)
        }
        if (newCols.isNotEmpty()) {
            ensureRecordColumnsExist(conn, normTable, newCols)
        }
    }

    private fun ensureRecordColumnsExist(
        conn: Connection,
        normTable: String,
        columnsToAdd: Map<String, DatabaseColumnType>
    ) {
        val currentSqliteCols = loadExistingSqliteColumns(conn, normTable)
        val tableIdent = DatabaseQuerySqlTranslator.quoteIdentifier(normTable)
        for ((colName, colType) in columnsToAdd) {
            val sanitized = DatabaseQuerySqlTranslator.sanitizeIdentifier(colName)
            val exists = currentSqliteCols.keys.any {
                DatabaseQuerySqlTranslator.sanitizeIdentifier(it).equals(sanitized, ignoreCase = true)
            }
            if (!exists) {
                val colIdent = DatabaseQuerySqlTranslator.quoteIdentifier(colName)
                val alterSql = "ALTER TABLE $tableIdent ADD COLUMN $colIdent ${colType.sqlAffinity}"
                runCatching {
                    conn.prepareStatement(alterSql).use { it.executeUpdate() }
                }
            }
        }
    }

    private fun tableExistsInSqlite(conn: Connection, normTable: String): Boolean {
        val sanitized = DatabaseQuerySqlTranslator.sanitizeIdentifier(normTable)
        conn.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1"
        ).use { stmt ->
            stmt.setString(1, sanitized)
            stmt.executeQuery().use { rs ->
                return rs.next()
            }
        }
    }

    private fun loadExistingSqliteColumns(
        conn: Connection,
        normTable: String
    ): LinkedHashMap<String, DatabaseColumnType> {
        val result = LinkedHashMap<String, DatabaseColumnType>()
        val schemaCols = schemas[normTable]?.columns?.associateBy {
            DatabaseQuerySqlTranslator.sanitizeIdentifier(it.name)
        } ?: emptyMap()
        val tableIdent = DatabaseQuerySqlTranslator.quoteIdentifier(normTable)
        runCatching {
            conn.prepareStatement("PRAGMA table_info($tableIdent)").use { stmt ->
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        val name = rs.getString("name") ?: continue
                        val typeStr = rs.getString("type") ?: ""
                        val schemaMatch = schemaCols[DatabaseQuerySqlTranslator.sanitizeIdentifier(name)]?.type
                        result[name] = schemaMatch ?: mapSqliteAffinityToColumnType(typeStr)
                    }
                }
            }
        }
        return result
    }

    private fun executePreparedUpdate(conn: Connection, prepared: DatabasePreparedSql): Int {
        conn.prepareStatement(prepared.sql).use { stmt ->
            bindAll(stmt, prepared.bindValues)
            return stmt.executeUpdate()
        }
    }

    private fun bindAll(stmt: PreparedStatement, bindValues: List<DatabaseValue>) {
        for (i in bindValues.indices) {
            val paramIdx = i + 1
            val v = bindValues[i]
            if (v.isNull) {
                stmt.setNull(paramIdx, Types.NULL)
                continue
            }
            when (v.type) {
                DatabaseColumnType.STRING -> stmt.setString(paramIdx, v.stringValue)
                DatabaseColumnType.INT64 -> stmt.setLong(paramIdx, v.longValue ?: 0L)
                DatabaseColumnType.FLOAT64 -> stmt.setDouble(paramIdx, v.doubleValue ?: 0.0)
                DatabaseColumnType.BOOLEAN -> stmt.setInt(paramIdx, if (v.booleanValue == true) 1 else 0)
                DatabaseColumnType.BLOB -> stmt.setBytes(paramIdx, v.blobValue?.toByteArray() ?: ByteArray(0))
                null -> stmt.setNull(paramIdx, Types.NULL)
            }
        }
    }

    private fun readResultSetRecords(
        rs: ResultSet,
        normTable: String,
        selectedColumns: List<String>
    ): List<DatabaseRecord> {
        val out = ArrayList<DatabaseRecord>()
        val meta = rs.metaData
        val colCount = meta.columnCount
        val pkCol = schemas[normTable]?.primaryKeyColumn ?: "key"
        val pkSanitized = DatabaseQuerySqlTranslator.sanitizeIdentifier(pkCol)
        val extraCols = if (knownTableColumns.containsKey(normTable)) {
            emptyMap()
        } else {
            loadExistingSqliteColumns(rs.statement.connection, normTable)
        }
        val schemaColBySanitized = buildSchemaColumnLookup(normTable, extraCols)

        while (rs.next()) {
            val values = LinkedHashMap<String, DatabaseValue>()
            var resolvedKey = ""
            for (i in 1..colCount) {
                val rawLabel = meta.getColumnLabel(i).ifEmpty { meta.getColumnName(i) }
                val sanitized = DatabaseQuerySqlTranslator.sanitizeIdentifier(rawLabel)
                val mapped = schemaColBySanitized[sanitized]
                val logicalColName = mapped?.first ?: rawLabel
                val expectedType = mapped?.second

                val rawObj = rs.getObject(i)
                val dbValue = if (rawObj == null || rs.wasNull()) {
                    DatabaseValue.Null
                } else {
                    when (expectedType) {
                        DatabaseColumnType.BOOLEAN -> DatabaseValue.of(rs.getInt(i) != 0)
                        DatabaseColumnType.INT64 -> DatabaseValue.of(rs.getLong(i))
                        DatabaseColumnType.FLOAT64 -> DatabaseValue.of(rs.getDouble(i))
                        DatabaseColumnType.BLOB -> {
                            val bytes = when (rawObj) {
                                is ByteArray -> rawObj
                                is String -> rawObj.encodeToByteArray()
                                else -> rs.getBytes(i) ?: ByteArray(0)
                            }
                            DatabaseValue.of(bytes)
                        }
                        DatabaseColumnType.STRING -> DatabaseValue.of(rs.getString(i))
                        null -> when (rawObj) {
                            is ByteArray -> DatabaseValue.of(rawObj)
                            is Long -> DatabaseValue.of(rawObj)
                            is Int -> DatabaseValue.of(rawObj.toLong())
                            is Double -> DatabaseValue.of(rawObj)
                            is Float -> DatabaseValue.of(rawObj.toDouble())
                            is Boolean -> DatabaseValue.of(rawObj)
                            is String -> DatabaseValue.of(rawObj)
                            else -> DatabaseValue.fromAny(rawObj)
                        }
                    }
                }
                values[logicalColName] = dbValue
                if (sanitized == pkSanitized || logicalColName == pkCol) {
                    resolvedKey = dbValue.asString() ?: ""
                }
            }
            val record = DatabaseRecord(key = resolvedKey, values = values)
            out.add(if (selectedColumns.isEmpty()) record else record.project(selectedColumns))
        }
        return out
    }

    companion object {
        fun resolveJdbcDatabasePath(databaseName: String, rawFilePath: String): String =
            JvmDatabaseBase.resolveDatabaseFilePath(databaseName, rawFilePath, "genericui_swing_db")
    }
}
