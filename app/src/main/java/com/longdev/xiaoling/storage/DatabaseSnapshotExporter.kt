package com.longdev.xiaoling.storage

import android.database.sqlite.SQLiteDatabase
import com.longdev.xiaoling.data.XiaoLingDatabase
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal object DatabaseSnapshotExporter {
    suspend fun write(database: XiaoLingDatabase, destination: File) {
        val source = database.openHelper.writableDatabase
        val version = source.query("SELECT sqlite_version()").use { cursor ->
            check(cursor.moveToFirst()) { "无法读取 SQLite 版本" }
            cursor.getString(0).split('.').map { it.toIntOrNull() ?: 0 }
        }
        if ((version.firstOrNull() ?: 0) > 3 || (version.firstOrNull() == 3 && (version.getOrNull(1) ?: 0) >= 27)) {
            source.execSQL("VACUUM main INTO ?", arrayOf(destination.absolutePath))
        } else {
            writeCompatible(database, destination)
        }
    }

    internal suspend fun writeCompatible(database: XiaoLingDatabase, destination: File) {
        require(!destination.exists() || destination.length() == 0L) { "快照目标必须为空" }
        val sourcePath = requireNotNull(database.openHelper.writableDatabase.path) { "备份源路径不存在" }
        require(sourcePath != ":memory:" && File(sourcePath).isFile) { "备份源必须是已打开的数据库文件" }
        // long: 不在 Room 的连接池上 ATTACH，避免影响在线 WAL；独立连接只写目标库，事务同时冻结源库的结构和业务行。
        SQLiteDatabase.openDatabase(destination.path, null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { output ->
            output.execSQL("ATTACH DATABASE ? AS backup_source", arrayOf(sourcePath))
            output.beginTransactionNonExclusive()
            try {
                val schema = output.rawQuery("SELECT type, name, sql FROM backup_source.sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%'", null).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(Schema(cursor.getString(0), cursor.getString(1), cursor.getString(2))) }
                }
                val virtualTables = schema.filter { it.type == "table" && it.sql.trimStart().startsWith("CREATE VIRTUAL TABLE", ignoreCase = true) }
                // long: FTS4 的 shadow 表由虚拟表创建并维护；复制可检索的业务行重建索引，不能直接搬运内部索引页。
                val shadows = virtualTables.flatMap { table ->
                    listOf("content", "segments", "segdir", "docsize", "stat").map { "${table.name}_$it" }
                }.toSet()
                val tables = schema.filter { it.type == "table" && it.name !in shadows }
                tables.forEach { output.execSQL(it.sql) }
                tables.forEach { copyRows(output, it) }
                output.rawQuery("SELECT name FROM backup_source.sqlite_master WHERE name = 'sqlite_sequence'", null).use { cursor ->
                    if (cursor.moveToFirst()) {
                        output.execSQL("DELETE FROM main.sqlite_sequence")
                        copyRows(output, Schema("table", "sqlite_sequence", ""))
                    }
                }
                // long: 先恢复数据，再创建用户索引、视图和触发器，避免复制行时触发业务写入或重复维护 FTS。
                schema.filter { it.type != "table" && it.name !in shadows }.forEach { output.execSQL(it.sql) }
                output.rawQuery("PRAGMA backup_source.user_version", null).use { cursor ->
                    check(cursor.moveToFirst())
                    output.version = cursor.getInt(0)
                }
                output.rawQuery("PRAGMA backup_source.application_id", null).use { cursor ->
                    check(cursor.moveToFirst())
                    output.execSQL("PRAGMA application_id = ${cursor.getInt(0)}")
                }
                output.setTransactionSuccessful()
            } finally { output.endTransaction() }
        }
    }

    private suspend fun copyRows(output: SQLiteDatabase, table: Schema) {
        val name = quote(table.name)
        val columns = output.rawQuery("PRAGMA backup_source.table_info($name)", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getString(1))
                }
            }
        }
        // long: 事件同毫秒时按隐式 rowid 排序；保留原编号及空洞，恢复后才能保持原审计顺序。
        val selected = if (table.sql.contains("WITHOUT ROWID", ignoreCase = true) || columns.any { it.equals("rowid", ignoreCase = true) }) columns else listOf("rowid") + columns
        val quoted = selected.joinToString(",", transform = ::quote)
        currentCoroutineContext().ensureActive()
        // long: 文档正文可超过 CursorWindow；复制留在 SQLite 内部，不能经 Cursor 逐行取回大 TEXT/BLOB。
        output.execSQL("INSERT INTO main.$name ($quoted) SELECT $quoted FROM backup_source.$name")
    }

    private fun quote(name: String) = "\"${name.replace("\"", "\"\"")}\""
    private data class Schema(val type: String, val name: String, val sql: String)
}
