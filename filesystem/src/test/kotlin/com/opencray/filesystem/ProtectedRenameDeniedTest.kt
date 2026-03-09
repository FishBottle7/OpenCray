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

class ProtectedRenameDeniedTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun protectedRenameIsDeniedWithDeterministicReasonCode() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-protected-rename").toPath()
    val protectedFile = workspaceRoot.resolve("agent.md")
    Files.write(protectedFile, "protected".toByteArray(StandardCharsets.UTF_8))
    val hashBefore = sha256Hex(protectedFile)

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
    assertEquals("protected", readUtf8(protectedFile))
    assertFalse(Files.exists(workspaceRoot.resolve("agent-renamed.md")))
    assertTrue(service.activeCheckpointIds().isEmpty())

    val hashAfter = sha256Hex(protectedFile)
    println("protected hash before=${hashBefore}")
    println("protected hash after=${hashAfter}")
    println("protected hash unchanged=${hashBefore == hashAfter}")
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
