package com.opencray.policy

import com.opencray.core.contracts.PolicyDecisionOutcome
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProtectedFileInvariantTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  private val policy = ModePolicy()

  @Test
  fun minimumProtectedFilesAreAlwaysRegistered() {
    val registry = ProtectedRegistry(protectedFileNames = setOf("notes.md"))

    assertTrue(
      registry.registeredProtectedFiles().containsAll(ProtectedRegistry.MINIMUM_PROTECTED_FILE_NAMES),
    )
  }

  @Test
  fun deletingProtectedFilesIsAlwaysDenied() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-delete-protected").toPath()

    for (mode in ExecutionMode.values()) {
      for (protectedFile in ProtectedRegistry.MINIMUM_PROTECTED_FILE_NAMES) {
        val decision = policy.decide(
          PolicyRequest(
            mode = mode,
            toolClass = PolicyToolClass.DELETE_FILE,
            workspaceRoot = workspaceRoot,
            targetPath = Paths.get(protectedFile),
          ),
        )

        assertEquals(PolicyDecisionOutcome.DENY, decision.outcome)
        assertEquals(PolicyReasonCode.DENY_PROTECTED_FILE, decision.reasonCode)
      }
    }
  }

  @Test
  fun renamingProtectedSourceIsDenied() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-rename-protected").toPath()

    val decision = policy.decide(
      PolicyRequest(
        mode = ExecutionMode.DEVELOPER,
        toolClass = PolicyToolClass.RENAME_FILE,
        workspaceRoot = workspaceRoot,
        targetPath = Paths.get("agent.md"),
        destinationPath = Paths.get("agent-renamed.md"),
      ),
    )

    assertEquals(PolicyDecisionOutcome.DENY, decision.outcome)
    assertEquals(PolicyReasonCode.DENY_PROTECTED_FILE, decision.reasonCode)
  }

  @Test
  fun movingIntoProtectedDestinationIsDenied() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-move-protected").toPath()

    val decision = policy.decide(
      PolicyRequest(
        mode = ExecutionMode.DEVELOPER,
        toolClass = PolicyToolClass.MOVE_FILE,
        workspaceRoot = workspaceRoot,
        targetPath = Paths.get("notes.txt"),
        destinationPath = Paths.get("memory.md"),
      ),
    )

    assertEquals(PolicyDecisionOutcome.DENY, decision.outcome)
    assertEquals(PolicyReasonCode.DENY_PROTECTED_FILE, decision.reasonCode)
  }

  @Test
  fun deletingSymlinkToProtectedFileIsDenied() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-symlink-protected").toPath()
    val protectedTarget = workspaceRoot.resolve("agent.md")
    Files.write(protectedTarget, "protected".toByteArray())
    val aliasPath = workspaceRoot.resolve("alias-not-protected.md")

    val symlinkCreated = runCatching {
      Files.createSymbolicLink(aliasPath, protectedTarget)
    }.isSuccess
    assumeTrue("Symbolic links are unavailable in this environment.", symlinkCreated)

    val decision = policy.decide(
      PolicyRequest(
        mode = ExecutionMode.DEVELOPER,
        toolClass = PolicyToolClass.DELETE_FILE,
        workspaceRoot = workspaceRoot,
        targetPath = aliasPath,
      ),
    )

    assertEquals(PolicyDecisionOutcome.DENY, decision.outcome)
    assertEquals(PolicyReasonCode.DENY_PROTECTED_FILE, decision.reasonCode)
  }
}
