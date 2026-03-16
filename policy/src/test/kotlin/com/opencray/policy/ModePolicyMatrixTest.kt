package com.opencray.policy

import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.contracts.PolicyApprovalRisk
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModePolicyMatrixTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  private val policy = ModePolicy()

  @Test
  fun modeMatrixDecisionsAreDeterministic() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-matrix").toPath()

    val cases = listOf(
      MatrixExpectation(
        mode = ExecutionMode.SAFE,
        toolClass = PolicyToolClass.READ_FILE,
        expectedOutcome = PolicyDecisionOutcome.ALLOW,
        expectedReasonCode = PolicyReasonCode.ALLOW_SAFE_READ,
      ),
      MatrixExpectation(
        mode = ExecutionMode.SAFE,
        toolClass = PolicyToolClass.WRITE_FILE,
        expectedOutcome = PolicyDecisionOutcome.ASK,
        expectedReasonCode = PolicyReasonCode.ASK_SAFE_WRITE,
      ),
      MatrixExpectation(
        mode = ExecutionMode.SAFE,
        toolClass = PolicyToolClass.DELETE_FILE,
        expectedOutcome = PolicyDecisionOutcome.ASK,
        expectedReasonCode = PolicyReasonCode.ASK_SAFE_DESTRUCTIVE_HIGH_RISK,
        expectedApprovalRisk = PolicyApprovalRisk.HIGH_RISK,
      ),
      MatrixExpectation(
        mode = ExecutionMode.SAFE,
        toolClass = PolicyToolClass.MOVE_FILE,
        expectedOutcome = PolicyDecisionOutcome.ASK,
        expectedReasonCode = PolicyReasonCode.ASK_SAFE_DESTRUCTIVE_HIGH_RISK,
        expectedApprovalRisk = PolicyApprovalRisk.HIGH_RISK,
        destinationRelativePath = "moved.txt",
      ),
      MatrixExpectation(
        mode = ExecutionMode.SAFE,
        toolClass = PolicyToolClass.RENAME_FILE,
        expectedOutcome = PolicyDecisionOutcome.ASK,
        expectedReasonCode = PolicyReasonCode.ASK_SAFE_DESTRUCTIVE_HIGH_RISK,
        expectedApprovalRisk = PolicyApprovalRisk.HIGH_RISK,
        destinationRelativePath = "renamed.txt",
      ),
      MatrixExpectation(
        mode = ExecutionMode.SAFE,
        toolClass = PolicyToolClass.EXECUTE_COMMAND,
        expectedOutcome = PolicyDecisionOutcome.ASK,
        expectedReasonCode = PolicyReasonCode.ASK_SAFE_COMMAND_HIGH_RISK,
        expectedApprovalRisk = PolicyApprovalRisk.HIGH_RISK,
        targetRelativePath = null,
      ),
      MatrixExpectation(
        mode = ExecutionMode.SAFE,
        toolClass = PolicyToolClass.NETWORK_ACCESS,
        expectedOutcome = PolicyDecisionOutcome.ASK,
        expectedReasonCode = PolicyReasonCode.ASK_SAFE_NETWORK_HIGH_RISK,
        expectedApprovalRisk = PolicyApprovalRisk.HIGH_RISK,
        targetRelativePath = null,
      ),

      MatrixExpectation(
        mode = ExecutionMode.AUTO,
        toolClass = PolicyToolClass.READ_FILE,
        expectedOutcome = PolicyDecisionOutcome.ALLOW,
        expectedReasonCode = PolicyReasonCode.ALLOW_AUTO_STANDARD,
      ),
      MatrixExpectation(
        mode = ExecutionMode.AUTO,
        toolClass = PolicyToolClass.WRITE_FILE,
        expectedOutcome = PolicyDecisionOutcome.ALLOW,
        expectedReasonCode = PolicyReasonCode.ALLOW_AUTO_STANDARD,
      ),
      MatrixExpectation(
        mode = ExecutionMode.AUTO,
        toolClass = PolicyToolClass.DELETE_FILE,
        expectedOutcome = PolicyDecisionOutcome.ASK,
        expectedReasonCode = PolicyReasonCode.ASK_AUTO_DESTRUCTIVE,
      ),
      MatrixExpectation(
        mode = ExecutionMode.AUTO,
        toolClass = PolicyToolClass.MOVE_FILE,
        expectedOutcome = PolicyDecisionOutcome.ASK,
        expectedReasonCode = PolicyReasonCode.ASK_AUTO_DESTRUCTIVE,
        destinationRelativePath = "moved.txt",
      ),
      MatrixExpectation(
        mode = ExecutionMode.AUTO,
        toolClass = PolicyToolClass.RENAME_FILE,
        expectedOutcome = PolicyDecisionOutcome.ASK,
        expectedReasonCode = PolicyReasonCode.ASK_AUTO_DESTRUCTIVE,
        destinationRelativePath = "renamed.txt",
      ),
      MatrixExpectation(
        mode = ExecutionMode.AUTO,
        toolClass = PolicyToolClass.EXECUTE_COMMAND,
        expectedOutcome = PolicyDecisionOutcome.ASK,
        expectedReasonCode = PolicyReasonCode.ASK_AUTO_COMMAND,
        targetRelativePath = null,
      ),
      MatrixExpectation(
        mode = ExecutionMode.AUTO,
        toolClass = PolicyToolClass.NETWORK_ACCESS,
        expectedOutcome = PolicyDecisionOutcome.ASK,
        expectedReasonCode = PolicyReasonCode.ASK_AUTO_NETWORK,
        targetRelativePath = null,
      ),

      MatrixExpectation(
        mode = ExecutionMode.DEVELOPER,
        toolClass = PolicyToolClass.READ_FILE,
        expectedOutcome = PolicyDecisionOutcome.ALLOW,
        expectedReasonCode = PolicyReasonCode.ALLOW_DEVELOPER_OVERRIDE,
      ),
      MatrixExpectation(
        mode = ExecutionMode.DEVELOPER,
        toolClass = PolicyToolClass.WRITE_FILE,
        expectedOutcome = PolicyDecisionOutcome.ALLOW,
        expectedReasonCode = PolicyReasonCode.ALLOW_DEVELOPER_OVERRIDE,
      ),
      MatrixExpectation(
        mode = ExecutionMode.DEVELOPER,
        toolClass = PolicyToolClass.DELETE_FILE,
        expectedOutcome = PolicyDecisionOutcome.ALLOW,
        expectedReasonCode = PolicyReasonCode.ALLOW_DEVELOPER_OVERRIDE,
      ),
      MatrixExpectation(
        mode = ExecutionMode.DEVELOPER,
        toolClass = PolicyToolClass.MOVE_FILE,
        expectedOutcome = PolicyDecisionOutcome.ALLOW,
        expectedReasonCode = PolicyReasonCode.ALLOW_DEVELOPER_OVERRIDE,
        destinationRelativePath = "moved.txt",
      ),
      MatrixExpectation(
        mode = ExecutionMode.DEVELOPER,
        toolClass = PolicyToolClass.RENAME_FILE,
        expectedOutcome = PolicyDecisionOutcome.ALLOW,
        expectedReasonCode = PolicyReasonCode.ALLOW_DEVELOPER_OVERRIDE,
        destinationRelativePath = "renamed.txt",
      ),
      MatrixExpectation(
        mode = ExecutionMode.DEVELOPER,
        toolClass = PolicyToolClass.EXECUTE_COMMAND,
        expectedOutcome = PolicyDecisionOutcome.ALLOW,
        expectedReasonCode = PolicyReasonCode.ALLOW_DEVELOPER_OVERRIDE,
        targetRelativePath = null,
      ),
      MatrixExpectation(
        mode = ExecutionMode.DEVELOPER,
        toolClass = PolicyToolClass.NETWORK_ACCESS,
        expectedOutcome = PolicyDecisionOutcome.ALLOW,
        expectedReasonCode = PolicyReasonCode.ALLOW_DEVELOPER_OVERRIDE,
        targetRelativePath = null,
      ),
    )

    for (matrixCase in cases) {
      val decision = policy.decide(
        PolicyRequest(
          mode = matrixCase.mode,
          toolClass = matrixCase.toolClass,
          workspaceRoot = workspaceRoot,
          targetPath = matrixCase.targetRelativePath?.let { Paths.get(it) },
          destinationPath = matrixCase.destinationRelativePath?.let { Paths.get(it) },
        ),
      )
      assertEquals(matrixCase.expectedOutcome, decision.outcome)
      assertEquals(matrixCase.expectedReasonCode, decision.reasonCode)
      assertEquals(matrixCase.expectedApprovalRisk, decision.approvalRisk)
    }
  }

  @Test
  fun traversalAttemptIsDeniedDeterministically() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-traversal").toPath()

    val decision = policy.decide(
      PolicyRequest(
        mode = ExecutionMode.AUTO,
        toolClass = PolicyToolClass.WRITE_FILE,
        workspaceRoot = workspaceRoot,
        targetPath = Paths.get("..", "escape.txt"),
      ),
    )

    assertEquals(PolicyDecisionOutcome.DENY, decision.outcome)
    assertEquals(PolicyReasonCode.DENY_PATH_TRAVERSAL, decision.reasonCode)
  }

  @Test
  fun workspaceEscapeAttemptIsDeniedDeterministically() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-escape").toPath()
    val outsidePath = workspaceRoot.resolveSibling("outside.txt")

    val decision = policy.decide(
      PolicyRequest(
        mode = ExecutionMode.DEVELOPER,
        toolClass = PolicyToolClass.WRITE_FILE,
        workspaceRoot = workspaceRoot,
        targetPath = outsidePath,
      ),
    )

    assertEquals(PolicyDecisionOutcome.DENY, decision.outcome)
    assertEquals(PolicyReasonCode.DENY_PATH_ESCAPE, decision.reasonCode)
  }

  @Test
  fun symlinkEscapeAttemptIsDeniedDeterministically() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-symlink-escape").toPath()
    val outsideRoot = temporaryFolder.newFolder("outside-root").toPath()
    val symlinkPath = workspaceRoot.resolve("linked-out")

    val symlinkCreated = runCatching {
      Files.createSymbolicLink(symlinkPath, outsideRoot)
    }.isSuccess
    assumeTrue("Symbolic links are unavailable in this environment.", symlinkCreated)

    val decision = policy.decide(
      PolicyRequest(
        mode = ExecutionMode.DEVELOPER,
        toolClass = PolicyToolClass.WRITE_FILE,
        workspaceRoot = workspaceRoot,
        targetPath = symlinkPath.resolve("escaped-through-link.txt"),
      ),
    )

    assertEquals(PolicyDecisionOutcome.DENY, decision.outcome)
    assertEquals(PolicyReasonCode.DENY_PATH_ESCAPE, decision.reasonCode)
  }

  private data class MatrixExpectation(
    val mode: ExecutionMode,
    val toolClass: PolicyToolClass,
    val expectedOutcome: PolicyDecisionOutcome,
    val expectedReasonCode: String,
    val expectedApprovalRisk: PolicyApprovalRisk = PolicyApprovalRisk.STANDARD,
    val targetRelativePath: String? = "target.txt",
    val destinationRelativePath: String? = null,
  )
}
