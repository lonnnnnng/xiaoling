package com.longdev.xiaoling.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import com.longdev.xiaoling.data.XiaoLingDatabase
import java.io.File

internal object BackupDatabaseValidator {
    fun checkIntegrityAndVersion(file: File, expectedVersion: Int) {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            require(db.version == expectedVersion && expectedVersion in 1..XiaoLingDatabase.CURRENT_VERSION) {
                "备份数据库版本与清单不一致或不受支持"
            }
            db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                require(cursor.moveToFirst() && cursor.getString(0) == "ok" && !cursor.moveToNext()) { "备份数据库完整性检查失败" }
            }
            db.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                require(!cursor.moveToFirst()) { "备份数据库存在失效的关联记录" }
            }
        }
    }

    fun prepare(context: Context, file: File, manifestVersion: Int) {
        checkIntegrityAndVersion(file, manifestVersion)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            // long: 清单和 identity hash 都可能来自损坏备份；只在临时副本移除 Room 身份缓存，强制执行真实表结构校验。
            db.execSQL("DROP TABLE IF EXISTS room_master_table")
        }
        val room = Room.databaseBuilder(context, XiaoLingDatabase::class.java, file.absolutePath)
            .addMigrations(*XiaoLingDatabase.migrations())
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .build()
        try {
            val db = room.openHelper.writableDatabase
            // long: WorkManager 数据库不属于备份；恢复后的待执行任务必须重新取得本机实际工作项，旧 UUID 不能继续当作已入队证据。
            db.execSQL("UPDATE scheduled_tasks SET workRequestId = NULL WHERE status = 'SCHEDULED'")
        } finally { room.close() }
        checkIntegrityAndVersion(file, XiaoLingDatabase.CURRENT_VERSION)
    }

    fun checkpoint(file: File) {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                require(cursor.moveToFirst() && cursor.getInt(0) == 0) { "数据库仍有活动连接，请重新打开应用后恢复" }
            }
        }
    }
}
