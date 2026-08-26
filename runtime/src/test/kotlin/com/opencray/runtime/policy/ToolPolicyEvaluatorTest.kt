package com.opencray.runtime.policy

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.policy.ModePolicy
import com.opencray.policy.PolicyToolClass
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolPolicyEvaluatorTest {
  private val workspaceRoot = Paths.get("D:/workspace-root").toAbsolutePath().normalize()

  @Test
  fun autoModeWebFetchIsAllowedWithoutApproval() {
    val evaluator = ToolPolicyEvaluator(modePolicy = ModePolicy())

    val decision = evaluator.evaluate(
      ToolPolicyEvaluationRequest(
        task = task(
          metadata = mapOf("chatMode" to "AUTO"),
        ),
        toolName = "WebFetch",
        toolClass = PolicyToolClass.NETWORK_ACCESS,
        workspaceRoot = workspaceRoot,
      ),
    )

    assertEquals(PolicyDecisionOutcome.ALLOW, decision.outcome)
    assertEquals("ALLOW_AUTO_STANDARD", decision.reasonCode)
  }

  @Test
  fun autoModeWebSearchIsAllowedWithoutApproval() {
    val evaluator = ToolPolicyEvaluator(modePolicy = ModePolicy())

    val decision = evaluator.evaluate(
      ToolPolicyEvaluationRequest(
        task = task(
          metadata = mapOf("chatMode" to "AUTO"),
        ),
        toolName = "WebSearch",
        toolClass = PolicyToolClass.NETWORK_ACCESS,
        workspaceRoot = workspaceRoot,
      ),
    )

    assertEquals(PolicyDecisionOutcome.ALLOW, decision.outcome)
    assertEquals("ALLOW_AUTO_STANDARD", decision.reasonCode)
  }

  @Test
  fun hostDenyStillOverridesAutoModeWebSearchAllowance() {
    val evaluator = ToolPolicyEvaluator(modePolicy = ModePolicy())

    val decision = evaluator.evaluate(
      ToolPolicyEvaluationRequest(
        task = task(
          metadata = mapOf("chatMode" to "AUTO"),
          policyDecision = PolicyDecision(
            outcome = PolicyDecisionOutcome.DENY,
            reasonCode = "HOST_DENY",
            detail = "Host denied network access.",
          ),
        ),
        toolName = "WebSearch",
        toolClass = PolicyToolClass.NETWORK_ACCESS,
        workspaceRoot = workspaceRoot,
      ),
    )

    assertEquals(PolicyDecisionOutcome.DENY, decision.outcome)
    assertEquals("HOST_DENY", decision.reasonCode)
  }

  @Test
  fun settingsOverrideCanRequireApprovalForDeveloperWrite() {
    val evaluator = ToolPolicyEvaluator(modePolicy = ModePolicy())

    val decision = evaluator.evaluate(
      ToolPolicyEvaluationRequest(
        task = task(
          metadata = mapOf(
            "chatMode" to "DEVELOPER",
            "fileChangesPolicyId" to "ask",
          ),
        ),
        toolName = "Write",
        toolClass = PolicyToolClass.WRITE_FILE,
        workspaceRoot = workspaceRoot,
        targetPath = workspaceRoot.resolve("notes.txt"),
      ),
    )

    assertEquals(PolicyDecisionOutcome.ASK, decision.outcome)
    assertEquals("SETTINGS_OVERRIDE_ASK", decision.reasonCode)
  }

  @Test
  fun hostDenyStillOverridesDeveloperCommandAllowance() {
    val evaluator = ToolPolicyEvaluator(modePolicy = ModePolicy())

    val decision = evaluator.evaluate(
      ToolPolicyEvaluationRequest(
        task = task(
          metadata = mapOf("chatMode" to "DEVELOPER"),
          policyDecision = PolicyDecision(
            outcome = PolicyDecisionOutcome.DENY,
            reasonCode = "HOST_DENY",
            detail = "Host denied command execution.",
          ),
        ),
        toolName = "Bash",
        toolClass = PolicyToolClass.EXECUTE_COMMAND,
        workspaceRoot = workspaceRoot,
      ),
    )

    assertEquals(PolicyDecisionOutcome.DENY, decision.outcome)
    assertEquals("HOST_DENY", decision.reasonCode)
  }

  @Test
  fun approvedToolRetryOnlyAllowsMatchingTool() {
    val evaluator = ToolPolicyEvaluator(
      modePolicy = ModePolicy(),
      approvedTaskId = "task-approved",
      approvedToolName = "Write",
    )
    val writeDecision = evaluator.evaluate(
      ToolPolicyEvaluationRequest(
        task = task(
          id = "task-approved",
          metadata = mapOf("chatMode" to "SAFE"),
        ),
        toolName = "Write",
        toolClass = PolicyToolClass.WRITE_FILE,
        workspaceRoot = workspaceRoot,
        targetPath = workspaceRoot.resolve("notes.txt"),
      ),
    )
    val deleteDecision = evaluator.evaluate(
      ToolPolicyEvaluationRequest(
        task = task(
          id = "task-approved",
          metadata = mapOf("chatMode" to "SAFE"),
        ),
        toolName = "workspace_delete_file",
        toolClass = PolicyToolClass.DELETE_FILE,
        workspaceRoot = workspaceRoot,
        targetPath = workspaceRoot.resolve("notes.txt"),
      ),
    )

    assertEquals(PolicyDecisionOutcome.ALLOW, writeDecision.outcome)
    assertEquals("USER_APPROVED_RETRY", writeDecision.reasonCode)
    assertEquals(PolicyDecisionOutcome.ASK, deleteDecision.outcome)
    assertEquals("ASK_SAFE_DESTRUCTIVE_HIGH_RISK", deleteDecision.reasonCode)
  }

  @Test
  fun rejectedToolRetryOnlyBlocksMatchingTool() {
    val evaluator = ToolPolicyEvaluator(
      modePolicy = ModePolicy(),
      rejectedTaskId = "task-rejected",
      rejectedToolName = "Write",
    )
    val writeDecision = evaluator.evaluate(
      ToolPolicyEvaluationRequest(
        task = task(
          id = "task-rejected",
          metadata = mapOf("chatMode" to "SAFE"),
        ),
        toolName = "Write",
        toolClass = PolicyToolClass.WRITE_FILE,
        workspaceRoot = workspaceRoot,
        targetPath = workspaceRoot.resolve("notes.txt"),
      ),
    )
    val deleteDecision = evaluator.evaluate(
      ToolPolicyEvaluationRequest(
        task = task(
          id = "task-rejected",
          metadata = mapOf("chatMode" to "SAFE"),
        ),
        toolName = "workspace_delete_file",
        toolClass = PolicyToolClass.DELETE_FILE,
        workspaceRoot = workspaceRoot,
        targetPath = workspaceRoot.resolve("notes.txt"),
      ),
    )

    assertEquals(PolicyDecisionOutcome.DENY, writeDecision.outcome)
    assertEquals("USER_REJECTED_APPROVAL", writeDecision.reasonCode)
    assertEquals(PolicyDecisionOutcome.ASK, deleteDecision.outcome)
    assertEquals("ASK_SAFE_DESTRUCTIVE_HIGH_RISK", deleteDecision.reasonCode)
  }

  @Test
  fun approvedTaskOverrideBindsToFirstApprovedRequestFingerprint() {
    val evaluator = ToolPolicyEvaluator(modePolicy = ModePolicy(), approvedTaskId = "task-approved")

    val firstWrite = evaluator.evaluate(
      writeRequest(id = "task-approved", path = "notes.txt"),
    )
    val differentPathWrite = evaluator.evaluate(
      writeRequest(id = "task-approved", path = "other.txt"),
    )
    val deleteAfterGrant = evaluator.evaluate(
      deleteRequest(id = "task-approved", path = "notes.txt"),
    )
    val identicalReplay = evaluator.evaluate(
      writeRequest(id = "task-approved", path = "notes.txt"),
    )

    assertEquals(PolicyDecisionOutcome.ALLOW, firstWrite.outcome)
    assertEquals("USER_APPROVED_RETRY", firstWrite.reasonCode)
    assertEquals(PolicyDecisionOutcome.ASK, differentPathWrite.outcome)
    assertEquals(PolicyDecisionOutcome.ASK, deleteAfterGrant.outcome)
    assertEquals(PolicyDecisionOutcome.ALLOW, identicalReplay.outcome)
    assertEquals("USER_APPROVED_RETRY", identicalReplay.reasonCode)
  }

  @Test
  fun approvedTaskOverrideExpiresAfterValidityWindow() {
    var nowEpochMs = 1_000L
    val evaluator = ToolPolicyEvaluator(
      modePolicy = ModePolicy(),
      approvedTaskId = "task-expiring",
      clock = { nowEpochMs },
    )

    val grantedWrite = evaluator.evaluate(
      writeRequest(id = "task-expiring", path = "notes.txt"),
    )
    nowEpochMs += 10L * 60L * 1000L + 1L
    val replayAfterExpiry = evaluator.evaluate(
      writeRequest(id = "task-expiring", path = "notes.txt"),
    )

    assertEquals(PolicyDecisionOutcome.ALLOW, grantedWrite.outcome)
    assertEquals("USER_APPROVED_RETRY", grantedWrite.reasonCode)
    assertEquals(PolicyDecisionOutcome.ASK, replayAfterExpiry.outcome)
  }

  @Test
  fun approvedTaskOverrideDoesNotAffectOtherTasksOrConsumeTheirGrants() {
    val evaluator = ToolPolicyEvaluator(modePolicy = ModePolicy(), approvedTaskId = "task-a")

    val otherTaskWrite = evaluator.evaluate(
      writeRequest(id = "task-b", path = "notes.txt"),
    )
    val approvedTaskWrite = evaluator.evaluate(
      writeRequest(id = "task-a", path = "notes.txt"),
    )

    assertEquals(PolicyDecisionOutcome.ASK, otherTaskWrite.outcome)
    assertEquals(PolicyDecisionOutcome.ALLOW, approvedTaskWrite.outcome)
    assertEquals("USER_APPROVED_RETRY", approvedTaskWrite.reasonCode)
  }

  private fun writeRequest(
    id: String,
    path: String,
  ): ToolPolicyEvaluationRequest = ToolPolicyEvaluationRequest(
    task = task(
      id = id,
      metadata = mapOf("chatMode" to "SAFE"),
    ),
    toolName = "Write",
    toolClass = PolicyToolClass.WRITE_FILE,
    workspaceRoot = workspaceRoot,
    targetPath = workspaceRoot.resolve(path),
  )

  private fun deleteRequest(
    id: String,
    path: String,
  ): ToolPolicyEvaluationRequest = ToolPolicyEvaluationRequest(
    task = task(
      id = id,
      metadata = mapOf("chatMode" to "SAFE"),
    ),
    toolName = "workspace_delete_file",
    toolClass = PolicyToolClass.DELETE_FILE,
    workspaceRoot = workspaceRoot,
    targetPath = workspaceRoot.resolve(path),
  )

  private fun task(
    id: String = "task-1",
    metadata: Map<String, String> = emptyMap(),
    policyDecision: PolicyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "HOST_ALLOW",
    ),
  ): AgentTask = AgentTask(
    id = id,
    type = AgentTaskType.TOOL_CALL,
    input = """{"type":"tool_call"}""",
    policyDecision = policyDecision,
    metadata = metadata,
    createdAtEpochMs = 1_000L,
  )
}
