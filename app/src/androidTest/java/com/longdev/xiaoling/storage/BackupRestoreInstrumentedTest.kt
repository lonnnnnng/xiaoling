package com.longdev.xiaoling.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.longdev.xiaoling.automation.WorkManagerScheduledTaskScheduler
import com.longdev.xiaoling.data.XiaoLingDatabase
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupRestoreInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun bothSnapshotPathsPreserveSearchIndexesAndEventOrder() = runBlocking {
        val root = File(context.cacheDir, "snapshot-search-${UUID.randomUUID()}").apply { mkdirs() }
        val database = Room.databaseBuilder(context, XiaoLingDatabase::class.java, File(root, "source.db").absolutePath)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
        try {
            val agents = RoomAgentRunRepository(context, database)
            val run = agents.createRun("snapshot-search", "message", "索引与事件保真")
            val sql = database.openHelper.writableDatabase
            database.agentMemoryDao().insertMemory(com.longdev.xiaoling.data.AgentMemoryEntity(
                "memory-snapshot", "memoryneedle 内容", "标签", "FACT", null, null, "来源", 1.0, true, 1, 1, false, null, null,
            ))
            val largeText = "snapshot-start|" + "x".repeat(4 * 1024 * 1024) + "|snapshot-end"
            database.knowledgeDao().insertDocument(com.longdev.xiaoling.data.KnowledgeDocumentEntity(
                "document-snapshot", "文档", "text/plain", "hash", 1, 1, largeText.length.toLong(), largeText.length, largeText, true, 1, 1,
            ))
            database.knowledgeDao().insertChunks(listOf(com.longdev.xiaoling.data.KnowledgeChunkEntity(
                "chunk-snapshot", "document-snapshot", 1, 1, 0, 10, "knowledgeneedle 内容",
            )))
            sql.execSQL("INSERT INTO agent_memories_fts(rowid,memoryId,content,tags,type,sourceSummary) VALUES(41,'removed','old','','FACT','')")
            sql.execSQL("INSERT INTO agent_memories_fts(rowid,memoryId,content,tags,type,sourceSummary) VALUES(72,'memory-snapshot','memoryneedle 内容','标签','FACT','来源')")
            sql.execSQL("DELETE FROM agent_memories_fts WHERE rowid=41")
            sql.execSQL("INSERT INTO knowledge_chunks_fts(rowid,chunkId,documentId,text) VALUES(83,'chunk-snapshot','document-snapshot','knowledgeneedle 内容')")
            // long: 同一毫秒的事件必须按原 rowid 保序；编号留洞可发现复制时按 TEXT 主键或新自增序号重排。
            sql.execSQL("INSERT INTO run_events(rowid,id,runId,type,message,createdAt,metadataJson) VALUES(42,'event-z',?,'fixture','first',100,NULL)", arrayOf(run.id))
            sql.execSQL("INSERT INTO run_events(rowid,id,runId,type,message,createdAt,metadataJson) VALUES(70,'event-a',?,'fixture','second',100,NULL)", arrayOf(run.id))
            val expectedEvents = agents.snapshot(run.id).events.map { it.id }
            for (compatible in listOf(false, true)) {
                val snapshot = File(root, "snapshot-$compatible.db")
                if (compatible) DatabaseSnapshotExporter.writeCompatible(database, snapshot)
                else DatabaseSnapshotExporter.write(database, snapshot)
                BackupDatabaseValidator.prepare(context, snapshot, XiaoLingDatabase.CURRENT_VERSION)
                val restored = open(snapshot)
                try {
                    val output = restored.openHelper.readableDatabase
                    // long: 只回读标量，让断言本身不受 CursorWindow 大小影响；相等比较覆盖大正文中段与边界。
                    output.query("SELECT length(normalizedText), normalizedText = ? FROM knowledge_documents WHERE id = 'document-snapshot'", arrayOf(largeText)).use {
                        assertTrue(it.moveToFirst()); assertEquals(largeText.length, it.getInt(0)); assertEquals(1, it.getInt(1))
                    }
                    output.query("SELECT m.id FROM agent_memories m JOIN agent_memories_fts f ON f.memoryId=m.id WHERE agent_memories_fts MATCH 'memoryneedle'").use {
                        assertTrue(it.moveToFirst()); assertEquals("memory-snapshot", it.getString(0)); assertFalse(it.moveToNext())
                    }
                    output.query("SELECT c.id FROM knowledge_chunks c JOIN knowledge_chunks_fts f ON f.chunkId=c.id WHERE knowledge_chunks_fts MATCH 'knowledgeneedle'").use {
                        assertTrue(it.moveToFirst()); assertEquals("chunk-snapshot", it.getString(0)); assertFalse(it.moveToNext())
                    }
                    assertEquals(expectedEvents, RoomAgentRunRepository(context, restored).snapshot(run.id).events.map { it.id })
                    if (compatible) {
                        output.query("SELECT rowid FROM agent_memories_fts WHERE memoryId='memory-snapshot'").use {
                            assertTrue(it.moveToFirst()); assertEquals(72L, it.getLong(0))
                        }
                        output.query("SELECT rowid FROM run_events WHERE id='event-a'").use {
                            assertTrue(it.moveToFirst()); assertEquals(70L, it.getLong(0))
                        }
                    }
                } finally { restored.close() }
            }
            assertNotNull(agents.createRun("source-after-snapshot", "next-message", "两种导出后原库仍可写"))
        } finally { database.close(); root.deleteRecursively() }
    }

    @Test fun exportIncludesLatestWalRunStateAndKeepsLiveDatabaseUsable() = runBlocking {
        val root = File(context.cacheDir, "export-wal-fixture-${UUID.randomUUID()}").apply { mkdirs() }
        val live = File(root, "live.db")
        val database = Room.databaseBuilder(context, XiaoLingDatabase::class.java, live.absolutePath)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
        try {
            val repository = RoomAgentRunRepository(context, database)
            val run = repository.createRun("export-conversation", "export-message", "保留最新失败状态")
            val sql = database.openHelper.writableDatabase
            sql.query("PRAGMA wal_checkpoint(TRUNCATE)").use { assertTrue(it.moveToFirst() && it.getInt(0) == 0) }
            // long: 关闭自动 checkpoint 后追加终态，复现界面已失败而主文件仍是旧状态的真实导出缺陷。
            sql.query("PRAGMA wal_autocheckpoint=0").use { assertTrue(it.moveToFirst()) }
            repository.updateRunStatus(run.id, com.longdev.xiaoling.agent.AgentRunStatus.FAILED, errorMessage = "最新失败")
            assertTrue(File(live.path + "-wal").length() > 0)
            val archive = File(root, "export.zip")
            XiaoLingBackupManager(context, database, live.absolutePath).export(android.net.Uri.fromFile(archive))
            val exported = File(root, "snapshot.db")
            XiaoLingBackupArchive.extractDatabase(archive, exported, XiaoLingDatabase.CURRENT_VERSION)
            BackupDatabaseValidator.checkIntegrityAndVersion(exported, XiaoLingDatabase.CURRENT_VERSION)
            val restored = open(exported)
            try {
                val detail = RoomAgentRunRepository(context, restored).runDetail(run.id)!!
                assertEquals(com.longdev.xiaoling.agent.AgentRunStatus.FAILED, detail.snapshot.run.status)
                assertEquals("最新失败", detail.snapshot.run.errorMessage)
                assertTrue(detail.snapshot.events.any { it.type == "run.status" && it.message == "FAILED" })
            } finally { restored.close() }
            assertNotNull(repository.createRun("live-still-open", "message-next", "导出后继续运行"))
        } finally { database.close(); root.deleteRecursively() }
    }

    @Test fun malformedSchemaAndIntegrityAreRejectedBeforeReplacingOriginal() {
        val root = File(context.cacheDir, "restore-fixture-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val malformed = File(root, "malformed.db")
            SQLiteDatabase.openOrCreateDatabase(malformed, null).use { it.version = XiaoLingDatabase.CURRENT_VERSION }
            assertTrue(runCatching { BackupDatabaseValidator.prepare(context, malformed, XiaoLingDatabase.CURRENT_VERSION) }.isFailure)
            val broken = File(root, "broken.db").apply { writeText("not a database") }
            assertTrue(runCatching { BackupDatabaseValidator.prepare(context, broken, XiaoLingDatabase.CURRENT_VERSION) }.isFailure)
            val staleIdentity = File(root, "stale-identity.db")
            val good = open(staleIdentity)
            try { good.openHelper.writableDatabase } finally { good.close() }
            SQLiteDatabase.openDatabase(staleIdentity.path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("ALTER TABLE providers ADD COLUMN unexpected TEXT")
            }
            assertTrue("旧 identity hash 不能绕过真实表结构校验", runCatching {
                BackupDatabaseValidator.prepare(context, staleIdentity, XiaoLingDatabase.CURRENT_VERSION)
            }.isFailure)
        } finally { root.deleteRecursively() }
    }

    @Test fun preparedRestorePreservesRowsClearsOldBindingAndOpensAfterAtomicActivation() = runBlocking {
        val root = File(context.cacheDir, "restore-fixture-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val source = File(root, "source.db")
            val live = File(root, "live.db")
            val sourceDb = open(source)
            val taskId: String
            try {
                val repository = RoomWorkflowRepository(context, sourceDb)
                val workflow = repository.createWorkflow("备份里的提醒", "读取时间")
                val task = repository.createOneTimeScheduledTask(workflow.id, 30)
                taskId = task.id
                repository.attachWorkRequest(task.id, UUID.randomUUID().toString())
            } finally { sourceDb.close() }
            val liveDb = open(live)
            try { RoomWorkflowRepository(context, liveDb).createWorkflow("当前库", "旧任务") } finally { liveDb.close() }
            val archive = File(root, "backup.zip")
            XiaoLingBackupArchive.create(archive, source, XiaoLingDatabase.CURRENT_VERSION, "fixture")
            val restore = androidDatabaseRestoreFiles(live)
            val before = open(live)
            try {
                val result = XiaoLingBackupManager(context, before, live.absolutePath).restore(android.net.Uri.fromFile(archive))
                assertTrue(result.restartRequired)
                assertEquals("当前库", before.workflowDao().listWorkflows().single().name)
            } finally { before.close() }
            assertTrue(restore.activate(
                { BackupDatabaseValidator.checkpoint(live) },
                { BackupDatabaseValidator.checkIntegrityAndVersion(it, XiaoLingDatabase.CURRENT_VERSION) },
                { val installed = open(live); try { installed.openHelper.writableDatabase } finally { installed.close() } },
            ))
            val restored = open(live)
            try {
                val repository = RoomWorkflowRepository(context, restored)
                assertEquals("备份里的提醒", restored.workflowDao().listWorkflows().single().name)
                assertNull(repository.getScheduledTask(taskId)?.workRequestId)
            } finally { restored.close() }
        } finally { root.deleteRecursively() }
    }

    @Test fun staleScheduledBindingIsRepairedAgainstRealWorkManagerWithoutDuplicates() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, XiaoLingDatabase::class.java).build()
        val scheduler = WorkManagerScheduledTaskScheduler(context)
        var taskId: String? = null
        try {
            val repository = RoomWorkflowRepository(context, database)
            val workflow = repository.createWorkflow("恢复调度测试", "读取时间")
            val task = repository.createOneTimeScheduledTask(workflow.id, 1_440)
            taskId = task.id
            repository.attachWorkRequest(task.id, UUID.randomUUID().toString())
            val missing = repository.reconcileScheduledWorkBindings(scheduler::activeWorkRequestId).single()
            assertNull(missing.workRequestId)
            val actual = scheduler.enqueue(missing)
            repository.attachWorkRequest(task.id, actual)
            assertEquals(actual, scheduler.activeWorkRequestId(task.id))
            assertTrue(repository.reconcileScheduledWorkBindings(scheduler::activeWorkRequestId).isEmpty())
            assertEquals(actual, scheduler.enqueue(missing))
            assertEquals(actual, repository.getScheduledTask(task.id)?.workRequestId)
        } finally {
            taskId?.let { scheduler.cancel(it) }
            database.close()
        }
    }

    @Test fun recoveryBindingCannotOverwriteTaskClaimedDuringEnqueue() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, XiaoLingDatabase::class.java).build()
        try {
            val repository = RoomWorkflowRepository(context, database)
            val workflow = repository.createWorkflow("已领取任务", "读取时间")
            val task = repository.createOneTimeScheduledTask(workflow.id, 30)
            val claim = repository.claimScheduledRun(task.id)
            assertNotNull(claim)
            assertFalse(repository.attachRecoveredWorkRequest(task.id, UUID.randomUUID().toString()))
            assertEquals(com.longdev.xiaoling.automation.ScheduledTaskStatus.RUNNING, repository.getScheduledTask(task.id)?.status)
        } finally { database.close() }
    }

    private fun open(file: File): XiaoLingDatabase = Room.databaseBuilder(context, XiaoLingDatabase::class.java, file.absolutePath)
        .addMigrations(*XiaoLingDatabase.migrations()).setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
}
