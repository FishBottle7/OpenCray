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
