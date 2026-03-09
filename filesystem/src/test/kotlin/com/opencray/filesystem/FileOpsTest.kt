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

class FileOpsTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun testBatchWriteRollbackRestoresPreOperationContentAfterInjectedFailure() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-batch-rollback").toPath()
    val stateFile = workspaceRoot.resolve("state.txt")
    Files.write(stateFile, "before".toByteArray(StandardCharsets.UTF_8))

    val originalHash = sha256Hex(stateFile)
    val originalSize = Files.size(stateFile)

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
    assertEquals(originalHash, sha256Hex(stateFile))
    assertEquals(originalSize, Files.size(stateFile))
    assertFalse(Files.exists(workspaceRoot.resolve("created.txt")))
    assertTrue(service.activeCheckpointIds().isEmpty())
  }

  @Test
  fun testProtectedRenameDeniedLeavesAgentMdContentAndHashUnchanged() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-protected-rename").toPath()
    val protectedFile = workspaceRoot.resolve("agent.md")
    Files.write(protectedFile, "protected".toByteArray(StandardCharsets.UTF_8))

    val originalHash = sha256Hex(protectedFile)
    val originalSize = Files.size(protectedFile)

    val service = FileOpsService(
      approvedRoots = setOf(workspaceRoot),
      rollbackJournal = LocalRollbackJournal(),
    )

    val error = assertThrows(FileOpsException::class.java) {
      service.executeBatch(
        operations = listOf(
          FileMutationOperation.Move(
            sourcePath = Paths.get("agent.md"),
            destinationPath = Paths.get("agent-renamed.md"),
          ),
        ),
      )
    }

    assertEquals(FileOpsReasonCode.DENY_PROTECTED_FILE, error.reasonCode)
    assertFalse(error.rollbackRestored)
    assertEquals("protected", readUtf8(protectedFile))
    assertEquals(originalHash, sha256Hex(protectedFile))
    assertEquals(originalSize, Files.size(protectedFile))
    assertFalse(Files.exists(workspaceRoot.resolve("agent-renamed.md")))
    assertTrue(service.activeCheckpointIds().isEmpty())
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
