package com.opencray.filesystem

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BatchWriteRollbackTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun batchWriteRollbackCommitsAndEmitsCheckpointMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-batch-commit").toPath()
    val journal = LocalRollbackJournal()
    val service = FileOpsService(
      approvedRoots = setOf(workspaceRoot),
      rollbackJournal = journal,
    )

    val result = service.executeBatch(
      operations = listOf(
        FileMutationOperation.Create(
          path = Paths.get("notes.txt"),
          content = "hello",
        ),
        FileMutationOperation.Write(
          path = Paths.get("notes.txt"),
          content = "updated",
        ),
        FileMutationOperation.Move(
          sourcePath = Paths.get("notes.txt"),
          destinationPath = Paths.get("archive/notes.txt"),
        ),
      ),
    )

    assertTrue(result.checkpointId.startsWith("local-"))
    assertEquals(3, result.operationCount)
    assertEquals(2, result.checkpointEntryCount)
    assertEquals(2, result.committedPaths.size)
    assertEquals("updated", readUtf8(workspaceRoot.resolve("archive/notes.txt")))
    assertFalse(Files.exists(workspaceRoot.resolve("notes.txt")))
    assertTrue(service.activeCheckpointIds().isEmpty())

    println("checkpoint id=${result.checkpointId}")
    println("checkpoint entry count=${result.checkpointEntryCount}")
    println("committed path count=${result.committedPaths.size}")
  }

  @Test
  fun batchWriteRollbackRestoresPriorStateWhenLaterOperationFails() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-batch-rollback").toPath()
    val stateFile = workspaceRoot.resolve("state.txt")
    Files.write(stateFile, "before".toByteArray(StandardCharsets.UTF_8))
    val hashBefore = sha256Hex(stateFile)

    val service = FileOpsService(
      approvedRoots = setOf(workspaceRoot),
      rollbackJournal = LocalRollbackJournal(),
    )

    val error = assertThrows(FileOpsException::class.java) {
      service.executeBatch(
        operations = listOf(
          FileMutationOperation.Write(
            path = Paths.get("state.txt"),
            content = "after",
          ),
          FileMutationOperation.Create(
            path = Paths.get("created.txt"),
            content = "new-file",
          ),
          FileMutationOperation.Move(
            sourcePath = Paths.get("missing.txt"),
            destinationPath = Paths.get("archive/missing.txt"),
          ),
        ),
      )
    }

    assertEquals(FileOpsReasonCode.FILE_NOT_FOUND, error.reasonCode)
    assertTrue(error.rollbackRestored)
    assertEquals("before", readUtf8(stateFile))
    assertFalse(Files.exists(workspaceRoot.resolve("created.txt")))
    assertTrue(service.activeCheckpointIds().isEmpty())

    val hashAfter = sha256Hex(stateFile)
    println("rollback hash before=${hashBefore}")
    println("rollback hash after=${hashAfter}")
  }

  private fun readUtf8(path: Path): String =
    String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private fun sha256Hex(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest
      .digest(Files.readAllBytes(path))
      .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
  }
}
