package com.longdev.xiaoling.storage

import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaoLingBackupArchiveTest {
    @Test
    fun archiveRoundTripKeepsDatabaseBytesAndKeystoreBoundaryMetadata() {
        val database = File.createTempFile("xiaoling", ".db").apply {
            writeText("database-bytes-v10", StandardCharsets.UTF_8)
            deleteOnExit()
        }
        val archive = File.createTempFile("xiaoling", ".backup").apply { deleteOnExit() }
        val restored = File.createTempFile("xiaoling", ".restored.db").apply { deleteOnExit() }

        XiaoLingBackupArchive.create(
            output = archive,
            database = database,
            schemaVersion = 10,
            appVersion = "0.1.9",
            createdAt = 123L,
        )

        val manifest = XiaoLingBackupArchive.readManifest(archive)
        XiaoLingBackupArchive.extractDatabase(archive, restored, expectedSchemaVersion = 10)

        assertEquals(10, manifest.schemaVersion)
        assertEquals("0.1.9", manifest.appVersion)
        assertEquals(123L, manifest.createdAt)
        assertTrue(manifest.providerCiphertextRequiresKeystore)
        assertArrayEquals(database.readBytes(), restored.readBytes())
    }

    @Test(expected = XiaoLingBackupException::class)
    fun futureSchemaIsRejectedBeforeDatabaseExtraction() {
        val database = File.createTempFile("xiaoling", ".db").apply {
            writeText("future")
            deleteOnExit()
        }
        val archive = File.createTempFile("xiaoling", ".backup").apply { deleteOnExit() }
        val restored = File.createTempFile("xiaoling", ".restored.db").apply { deleteOnExit() }

        XiaoLingBackupArchive.create(archive, database, schemaVersion = 11, appVersion = "future", createdAt = 1L)
        XiaoLingBackupArchive.extractDatabase(archive, restored, expectedSchemaVersion = 10)
    }
}
