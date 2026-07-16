package com.opencray.filesystem

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

  @Test
  fun executeBatchSerializesSharedWorkspaceAcrossServiceInstances() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-process-lock").toPath()
    val lockDirectory = temporaryFolder.newFolder("workspace-process-locks").toPath()
    val stateFile = workspaceRoot.resolve("state.txt")
    Files.write(stateFile, "before".toByteArray(StandardCharsets.UTF_8))
    val firstEnteredCheckpoint = CountDownLatch(1)
    val releaseFirstCheckpoint = CountDownLatch(1)
    val secondEnteredCheckpoint = CountDownLatch(1)
    val firstJournal = blockingRollbackJournal(
      enteredCheckpoint = firstEnteredCheckpoint,
      releaseCheckpoint = releaseFirstCheckpoint,
    )
    val secondJournal = blockingRollbackJournal(
      enteredCheckpoint = secondEnteredCheckpoint,
    )
    val firstService = FileOpsService(
      approvedRoots = setOf(workspaceRoot),
      rollbackJournal = firstJournal,
      mutationLockDirectory = lockDirectory,
    )
    val secondService = FileOpsService(
      approvedRoots = setOf(workspaceRoot),
      rollbackJournal = secondJournal,
      mutationLockDirectory = lockDirectory,
    )
    val executor = Executors.newFixedThreadPool(2)
    try {
      val firstWrite = executor.submit {
        firstService.executeBatch(
          listOf(FileMutationOperation.Write(Paths.get("state.txt"), "first")),
        )
      }
      assertTrue(firstEnteredCheckpoint.await(5, TimeUnit.SECONDS))
      val secondWrite = executor.submit {
        secondService.executeBatch(
          listOf(FileMutationOperation.Write(Paths.get("state.txt"), "second")),
        )
      }

      assertFalse(secondEnteredCheckpoint.await(200, TimeUnit.MILLISECONDS))
      releaseFirstCheckpoint.countDown()
      firstWrite.get(5, TimeUnit.SECONDS)
      secondWrite.get(5, TimeUnit.SECONDS)

      assertTrue(secondEnteredCheckpoint.await(5, TimeUnit.SECONDS))
      assertEquals("second", readUtf8(stateFile))
    } finally {
      releaseFirstCheckpoint.countDown()
      executor.shutdownNow()
    }
  }

  private fun readUtf8(path: Path): String =
    String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private fun sha256Hex(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest
      .digest(Files.readAllBytes(path))
      .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
  }

  private fun blockingRollbackJournal(
    enteredCheckpoint: CountDownLatch,
    releaseCheckpoint: CountDownLatch? = null,
  ): RollbackJournal {
    val delegate = LocalRollbackJournal()
    return object : RollbackJournal {
      override fun checkpoint(paths: List<Path>): RollbackCheckpoint {
        enteredCheckpoint.countDown()
        if (releaseCheckpoint != null) {
          check(releaseCheckpoint.await(5, TimeUnit.SECONDS)) {
            "Timed out waiting to release the blocking rollback checkpoint."
          }
        }
        return delegate.checkpoint(paths)
      }

      override fun commit(checkpointId: String) = delegate.commit(checkpointId)

      override fun restore(checkpoint: RollbackCheckpoint) = delegate.restore(checkpoint)

      override fun activeCheckpointIds(): Set<String> = delegate.activeCheckpointIds()
    }
  }
}
