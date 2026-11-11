package com.longdev.xiaoling.storage

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DatabaseRestoreFilesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun stagingLeavesLiveDatabaseIntactAndActivationSwapsCompleteFiles() {
        val current = folder.newFile("current").apply { writeText("old") }
        val candidate = folder.newFile("candidate").apply { writeText("new") }
        val restore = DatabaseRestoreFiles(current)
        restore.stage(candidate)
        assertEquals("old", current.readText())
        assertTrue(restore.activate({}, { assertEquals("new", it.readText()) }, { assertEquals("new", current.readText()) }))
        assertEquals("new", current.readText())
        assertFalse(restore.hasUnfinishedRestore)
        assertEquals("old", File(current.path + ".pre-restore").readText())
    }

    @Test fun failedValidationCannotReplaceLiveDatabase() {
        val current = folder.newFile("current").apply { writeText("old") }
        val candidate = folder.newFile("candidate").apply { writeText("bad") }
        val restore = DatabaseRestoreFiles(current)
        restore.stage(candidate)
        assertTrue(runCatching { restore.activate({}, { error("bad schema") }, { fail("must not open") }) }.isFailure)
        assertEquals("old", current.readText())
    }

    @Test fun failedAtomicMoveAndFailedFirstOpenBothRestoreOriginal() {
        for (failMove in listOf(true, false)) {
            val current = folder.newFile("current-$failMove").apply { writeText("old") }
            val candidate = folder.newFile("candidate-$failMove").apply { writeText("new") }
            val restore = DatabaseRestoreFiles(current, move = { source, target ->
                if (failMove && source.name.endsWith(".restore-ready")) throw java.io.IOException("disk failure")
                Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                Unit
            })
            restore.stage(candidate)
            assertTrue(runCatching { restore.activate({}, {}, { error("first open failed") }) }.isFailure)
            assertEquals("old", current.readText())
            assertFalse(restore.hasUnfinishedRestore)
        }
    }

    @Test fun interruptedActivationRollsBackBeforeAnotherOpen() {
        val current = folder.newFile("current").apply { writeText("new") }
        File(current.path + ".pre-restore").writeText("old")
        File(current.path + ".restore-installing").writeText("pending")
        File(current.path + "-wal").writeText("new wal")
        val restore = DatabaseRestoreFiles(current)
        assertFalse(restore.activate({ fail("must rollback first") }, {}, { fail("must not open new") }))
        assertEquals("old", current.readText())
        assertFalse(File(current.path + "-wal").exists())
        assertFalse(restore.hasUnfinishedRestore)
    }

    @Test fun failedStagingSyncCannotSilentlyActivateOnNextOpen() {
        val current = folder.newFile("current").apply { writeText("old") }
        val candidate = folder.newFile("candidate").apply { writeText("new") }
        var syncs = 0
        val restore = DatabaseRestoreFiles(current, syncDirectory = { if (++syncs == 1) error("directory sync failed") })
        assertTrue(runCatching { restore.stage(candidate) }.isFailure)
        assertFalse(restore.activate({}, {}, { fail("failed staging cannot install") }))
        assertEquals("old", current.readText())
    }
}
