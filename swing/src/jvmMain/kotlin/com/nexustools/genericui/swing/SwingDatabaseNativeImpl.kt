package com.nexustools.genericui.swing

import com.nexustools.genericui.io.ByteBuffer
import com.nexustools.genericui.nativeimpl.io.DatabaseReadableNativeImpl
import com.nexustools.genericui.nativeimpl.io.DatabaseWritableNativeImpl
import com.nexustools.genericui.nativeimpl.io.DefaultDatabaseReadableNativeImpl
import com.nexustools.genericui.nativeimpl.io.DefaultDatabaseWritableNativeImpl
import com.nexustools.genericui.nativeimpl.system.DefaultDatabaseNativeImpl
import com.nexustools.genericui.util.db.DatabaseColumnSpec
import com.nexustools.genericui.util.db.DatabaseColumnType
import com.nexustools.genericui.util.db.DatabaseFilter
import com.nexustools.genericui.util.db.DatabasePreparedSql
import com.nexustools.genericui.util.db.DatabaseQuery
import com.nexustools.genericui.util.db.DatabaseQuerySqlTranslator
import com.nexustools.genericui.util.db.DatabaseRecord
import com.nexustools.genericui.util.db.DatabaseTableSchema
import com.nexustools.genericui.util.db.DatabaseValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import javax.swing.SwingUtilities
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext

/**
 * Java Swing / JVM (`:desktop:swing`) SQLite JDBC (`org.xerial:sqlite-jdbc`) implementation of
 * [com.nexustools.genericui.nativeimpl.system.DatabaseNativeImpl].
 *
 * Translates structured [DatabaseTableSchema], [DatabaseRecord], [DatabaseQuery], and [DatabaseFilter]
 * ASTs via [DatabaseQuerySqlTranslator] into parameterized JDBC [PreparedStatement]s executed on
 * [Dispatchers.IO] with zero string query parsing.
 */
class SwingDatabaseNativeImpl(
    databaseName: String = "GenericUI",
    filePath: String = "GenericUI.db",
    version: Int = 1
) : DefaultDatabaseNativeImpl(databaseName, filePath, version) {

    val resolvedJdbcPath: String = resolveJdbcDatabasePath(databaseName, filePath)

    private var connection: Connection? = null
    private val knownTableColumns = LinkedHashMap<String, LinkedHashMap<String, DatabaseColumnType>>()
    private var tickSequence: Long = 1700000000000L

    init {
        runCatching { Class.forName("org.sqlite.JDBC") }
    }

    override suspend fun defineTable(schema: DatabaseTableSchema): Boolean {
        if (isDisposed) return false
        super.defineTable(schema)
        val norm = normalizeTableName(schema.tableName)
        return runOnSwingIo {
            synchronized(this) {
                val conn = getOrOpenConnection() ?: return@runOnSwingIo true
                val statements = DatabaseQuerySqlTranslator.buildCreateTableSql(schema)
                for (stmt in statements) {
                    executePreparedUpdate(conn, stmt)
                }
                val colMap = knownTableColumns.getOrPut(norm) { LinkedHashMap() }
                val effectiveCols = if (schema.columns.isEmpty()) {
                    DatabaseTableSchema.defaultKeyValueColumns(schema.primaryKeyColumn)
                } else {
                    schema.columns
                }
                for (col in effectiveCols) {
                    colMap[col.name] = col.type
                }
                ensureRecordColumnsExist(conn, norm, colMap)
                true
            }
        }
    }

    override suspend fun putRecord(table: String, record: DatabaseRecord): Boolean {
        if (isDisposed) return false
        super.putRecord(table, record)
        val norm = normalizeTableName(table)
        return runOnSwingIo {
            synchronized(this) {
                val conn = getOrOpenConnection() ?: return@runOnSwingIo true
                val pkCol = schemas[norm]?.primaryKeyColumn ?: "key"
                val enrichedValues = LinkedHashMap(record.values)
                if (!enrichedValues.containsKey(pkCol)) {
                    enrichedValues[pkCol] = DatabaseValue.of(record.key)
                }
                if (!enrichedValues.containsKey("updatedAt")) {
                    enrichedValues["updatedAt"] = DatabaseValue.of(++tickSequence)
                }
                val enrichedRecord = DatabaseRecord(key = record.key, values = enrichedValues)
                ensureTableAndColumnsForRecord(conn, norm, pkCol, enrichedRecord)

                val upsertSql = DatabaseQuerySqlTranslator.buildUpsertRecordSql(
                    table = norm,
                    record = enrichedRecord,
                    primaryKeyColumn = pkCol
                )
                executePreparedUpdate(conn, upsertSql) >= 0
            }
        }
    }

    override suspend fun getRecord(table: String, key: String): DatabaseRecord? {
        if (isDisposed) return null
        val norm = normalizeTableName(table)
        val pkCol = schemas[norm]?.primaryKeyColumn ?: "key"
        val selectByKeyQuery = DatabaseQuery.from(norm)
            .where(DatabaseFilter.eq(pkCol, key))
            .limit(1)
        return query(selectByKeyQuery).firstOrNull()
    }

    override suspend fun query(query: DatabaseQuery): List<DatabaseRecord> {
        if (isDisposed) return emptyList()
        val norm = normalizeTableName(query.table)
        return runOnSwingIo {
            synchronized(this) {
                val conn = getOrOpenConnection() ?: return@runOnSwingIo emptyList()
                if (!tableExistsInSqlite(conn, norm)) {
                    return@runOnSwingIo emptyList()
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
        return runOnSwingIo {
            synchronized(this) {
                val conn = getOrOpenConnection() ?: return@runOnSwingIo 0L
                if (!tableExistsInSqlite(conn, norm)) {
                    return@runOnSwingIo 0L
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
        return runOnSwingIo {
            synchronized(this) {
                val conn = getOrOpenConnection() ?: return@runOnSwingIo 0
                if (!tableExistsInSqlite(conn, norm)) {
                    return@runOnSwingIo 0
                }
                val normalizedQuery = query.copy(table = norm)
                val prepared = DatabaseQuerySqlTranslator.buildDeleteSql(normalizedQuery)
                executePreparedUpdate(conn, prepared)
            }
        }
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

    override suspend fun exists(table: String, key: String): Boolean =
        getRecord(table, key) != null

    override suspend fun delete(table: String, key: String): Boolean {
        if (isDisposed) return false
        val norm = normalizeTableName(table)
        val pkCol = schemas[norm]?.primaryKeyColumn ?: "key"
        val deleted = deleteWhere(DatabaseQuery.from(norm).where(DatabaseFilter.eq(pkCol, key)))
        return deleted > 0
    }

    override suspend fun keys(table: String, prefix: String): List<String> {
        if (isDisposed) return emptyList()
        val norm = normalizeTableName(table)
        val pkCol = schemas[norm]?.primaryKeyColumn ?: "key"
        val q = if (prefix.isEmpty()) {
            DatabaseQuery.from(norm).select(pkCol)
        } else {
            DatabaseQuery.from(norm).select(pkCol).where(DatabaseFilter.startsWith(pkCol, prefix))
        }
        return query(q).map { it.key }
    }

    override suspend fun clearTable(table: String): Int =
        deleteWhere(DatabaseQuery.from(table))

    override suspend fun dropTable(table: String): Boolean {
        if (isDisposed) return false
        super.dropTable(table)
        val norm = normalizeTableName(table)
        return runOnSwingIo {
            synchronized(this) {
                val conn = getOrOpenConnection() ?: return@runOnSwingIo false
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
        return runOnSwingIo {
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

    override suspend fun totalByteSize(table: String): Long {
        if (isDisposed) return 0L
        val records = query(DatabaseQuery.from(table))
        var total = 0L
        for (rec in records) {
            total += rec.getBuffer("value")?.size?.toLong() ?: 0L
        }
        return total
    }

    override fun dispose() {
        synchronized(this) {
            super.dispose()
            knownTableColumns.clear()
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
            val inferredSpecs = ArrayList<DatabaseColumnSpec>()
            inferredSpecs.add(
                DatabaseColumnSpec(
                    name = pkCol,
                    type = DatabaseColumnType.STRING,
                    primaryKey = true,
                    nullable = false
                )
            )
            for ((colName, value) in record.values) {
                if (colName == pkCol) continue
                inferredSpecs.add(
                    DatabaseColumnSpec(
                        name = colName,
                        type = value.type ?: DatabaseColumnType.BLOB,
                        primaryKey = false,
                        nullable = true,
                        indexed = colName == "updatedAt"
                    )
                )
            }
            val inferredSchema = schemas[normTable] ?: DatabaseTableSchema(
                tableName = normTable,
                primaryKeyColumn = pkCol,
                columns = inferredSpecs
            )
            schemas.putIfAbsent(normTable, inferredSchema)
            for (ddl in DatabaseQuerySqlTranslator.buildCreateTableSql(inferredSchema)) {
                executePreparedUpdate(conn, ddl)
            }
            val map = knownTableColumns.getOrPut(normTable) { LinkedHashMap() }
            for (spec in inferredSchema.columns) {
                map[spec.name] = spec.type
            }
        }

        val colMap = knownTableColumns.getOrPut(normTable) { loadExistingSqliteColumns(conn, normTable) }
        val newCols = LinkedHashMap<String, DatabaseColumnType>()
        for ((colName, value) in record.values) {
            val sanitized = DatabaseQuerySqlTranslator.sanitizeIdentifier(colName)
            val alreadyPresent = colMap.keys.any {
                DatabaseQuerySqlTranslator.sanitizeIdentifier(it).equals(sanitized, ignoreCase = true)
            }
            val valueType = value.type
            val resolvedType = valueType ?: colMap[colName] ?: DatabaseColumnType.BLOB
            if (!alreadyPresent) {
                newCols[colName] = resolvedType
            }
            if (valueType != null) {
                colMap[colName] = valueType
            }
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
                        val typeStr = (rs.getString("type") ?: "").uppercase()
                        val schemaMatch = schemaCols[DatabaseQuerySqlTranslator.sanitizeIdentifier(name)]?.type
                        val resolvedType = schemaMatch ?: when {
                            typeStr.contains("INT") -> DatabaseColumnType.INT64
                            typeStr.contains("REAL") || typeStr.contains("FLOA") || typeStr.contains("DOUB") ->
                                DatabaseColumnType.FLOAT64
                            typeStr.contains("BLOB") -> DatabaseColumnType.BLOB
                            else -> DatabaseColumnType.STRING
                        }
                        result[name] = resolvedType
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
        val knownCols = knownTableColumns[normTable] ?: loadExistingSqliteColumns(rs.statement.connection, normTable)
        val schemaColBySanitized = LinkedHashMap<String, Pair<String, DatabaseColumnType>>()
        schemas[normTable]?.columns?.forEach { spec ->
            schemaColBySanitized[DatabaseQuerySqlTranslator.sanitizeIdentifier(spec.name)] = spec.name to spec.type
        }
        for ((k, t) in knownCols) {
            val s = DatabaseQuerySqlTranslator.sanitizeIdentifier(k)
            if (!schemaColBySanitized.containsKey(s)) {
                schemaColBySanitized[s] = k to t
            }
        }

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

    private suspend fun <T> runOnSwingIo(block: () -> T): T {
        val hasInterceptor = coroutineContext[ContinuationInterceptor] != null
        return if (hasInterceptor || SwingUtilities.isEventDispatchThread()) {
            withContext(Dispatchers.IO) { block() }
        } else {
            block()
        }
    }

    companion object {
        fun resolveJdbcDatabasePath(databaseName: String, rawFilePath: String): String {
            val trimmed = rawFilePath.trim()
            if (trimmed == ":memory:") return ":memory:"
            if (trimmed.startsWith("jdbc:sqlite:")) return trimmed.removePrefix("jdbc:sqlite:")

            val candidate = if (trimmed.contains("://")) {
                val safe = trimmed.replace(Regex("[^A-Za-z0-9._-]"), "_")
                File(File(System.getProperty("java.io.tmpdir", "."), "genericui_swing_db"), safe)
            } else if (File.separatorChar == '\\' && trimmed.startsWith("/") && !(trimmed.length >= 3 && trimmed[2] == ':')) {
                File(File(System.getProperty("java.io.tmpdir", "."), "genericui_swing_db"), trimmed.trimStart('/'))
            } else {
                val direct = File(trimmed)
                if (direct.isAbsolute) {
                    direct
                } else {
                    File(File(System.getProperty("java.io.tmpdir", "."), "genericui_swing_db"), trimmed)
                }
            }

            val ensured = runCatching {
                candidate.parentFile?.mkdirs()
                candidate.absolutePath
            }.getOrNull()

            if (ensured != null) {
                return ensured
            }
            val fallbackName = databaseName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "GenericUI" }
            val tempFallback = File(File(System.getProperty("java.io.tmpdir", "."), "genericui_swing_db"), "$fallbackName.db")
            runCatching { tempFallback.parentFile?.mkdirs() }
            return tempFallback.absolutePath
        }
    }
}
