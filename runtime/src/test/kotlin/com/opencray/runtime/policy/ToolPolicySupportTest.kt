package com.opencray.runtime.policy

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyApprovalRisk
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPolicySupportTest {
  private val toolCallNormalizer = ToolCallNormalizer()
  private val toolPolicySupport = ToolPolicySupport()

  @Test
  fun aliasNormalizationDecoratesResultsWithRequestedAndCanonicalNames() {
    val invocation = toolCallNormalizer.normalize(
      AgentToolCall(
        toolName = "read",
        arguments = JsonObject(emptyMap()),
      ),
    )

    val result = toolCallNormalizer.decorateResult(
      result = AgentToolResult(
        toolName = "Read",
        status = AgentToolResultStatus.SUCCESS,
        content = "README",
      ),
      invocation = invocation,
    )

    assertEquals("read", invocation.requestedToolName)
    assertEquals("Read", invocation.normalizedToolName)
    assertEquals("read", result.toolName)
    assertEquals("read", result.metadata["requestedToolName"])
    assertEquals("Read", result.metadata["normalizedToolName"])
    assertEquals("Read", result.metadata["canonicalToolName"])
  }

  @Test
  fun aliasDefinitionsMirrorCanonicalDefinitions() {
    val aliases = toolCallNormalizer.aliasDefinitions(
      listOf(
        com.opencray.runtime.AgentToolDefinition(
          name = "Read",
          description = "Read a file.",
        ),
        com.opencray.runtime.AgentToolDefinition(
          name = "Bash",
          description = "Run a shell command.",
        ),
      ),
    )

    val readAlias = requireNotNull(aliases.firstOrNull { it.name == "read" })
    val bashAlias = requireNotNull(aliases.firstOrNull { it.name == "bash" })
    assertTrue(readAlias.description.contains("Compatibility alias for Read"))
    assertTrue(bashAlias.description.contains("Compatibility alias for Bash"))
  }

  @Test
  fun gateResultReturnsNullForAllowedDecision() {
    val result = toolPolicySupport.gateResult(
      ToolGateRequest(
        task = task(),
        toolName = "Write",
        policyDecision = PolicyDecision(
          outcome = PolicyDecisionOutcome.ALLOW,
          reasonCode = "ALLOW_AUTO_STANDARD",
        ),
        askDetail = "unused",
        denyDetail = "unused",
      ),
    )

    assertNull(result)
  }

  @Test
  fun gateResultBuildsStandardApprovalDeniedResult() {
    val result = requireNotNull(
      toolPolicySupport.gateResult(
        ToolGateRequest(
          task = task(metadata = mapOf("chatMode" to "AUTO")),
          toolName = "Write",
          policyDecision = PolicyDecision(
            outcome = PolicyDecisionOutcome.ASK,
            reasonCode = "ASK_AUTO_DESTRUCTIVE",
            detail = "Approval is required before Write can run.",
            approvalRisk = PolicyApprovalRisk.STANDARD,
          ),
          affectedPaths = mapOf("path" to "notes.txt"),
          metadataContext = ToolMetadataContext(
            targetKind = ToolTargetKind.FILE,
            workspaceRelation = ToolWorkspaceRelation.INSIDE_WORKSPACE,
            primaryTargetPath = "notes.txt",
            targetSummary = "notes.txt",
          ),
          askDetail = "Approval is required before Write can run.",
          denyDetail = "Policy denied Write.",
        ),
      ),
    )

    assertEquals(AgentToolResultStatus.DENIED, result.status)
    assertEquals("APPROVAL_REQUIRED", result.errorCode)
    assertEquals("AUTO", result.metadata["executionMode"])
    assertEquals("ASK", result.metadata["policyOutcome"])
    assertEquals("ASK_AUTO_DESTRUCTIVE", result.metadata["policyReasonCode"])
    assertEquals("STANDARD", result.metadata["approvalRisk"])
    assertEquals("write_file", result.metadata["capabilityKind"])
    assertEquals("file", result.metadata["targetKind"])
    assertEquals("inside_workspace", result.metadata["workspaceRelation"])
    assertEquals("notes.txt", result.metadata["primaryTargetPath"])
    assertEquals("notes.txt", result.metadata["targetSummary"])
    assertEquals("notes.txt", result.metadata["path"])
  }

  @Test
  fun gateResultBuildsHighRiskApprovalDeniedResult() {
    val result = requireNotNull(
      toolPolicySupport.gateResult(
        ToolGateRequest(
          task = task(metadata = mapOf("chatMode" to "SAFE")),
          toolName = "Bash",
          policyDecision = PolicyDecision(
            outcome = PolicyDecisionOutcome.ASK,
            reasonCode = "ASK_SAFE_COMMAND_HIGH_RISK",
            approvalRisk = PolicyApprovalRisk.HIGH_RISK,
          ),
          metadataContext = ToolMetadataContext(
            targetKind = ToolTargetKind.WORKING_DIRECTORY,
            workspaceRelation = ToolWorkspaceRelation.INSIDE_WORKSPACE,
            primaryTargetPath = ".",
            targetSummary = "git status",
          ),
          askDetail = "Approval is required before Bash can run.",
          denyDetail = "Policy denied Bash.",
        ),
      ),
    )

    assertEquals(AgentToolResultStatus.DENIED, result.status)
    assertEquals("HIGH_RISK_APPROVAL_REQUIRED", result.errorCode)
    assertEquals("SAFE", result.metadata["executionMode"])
    assertEquals("HIGH_RISK", result.metadata["approvalRisk"])
    assertEquals("execute_command", result.metadata["capabilityKind"])
    assertEquals("working_directory", result.metadata["targetKind"])
    assertEquals("inside_workspace", result.metadata["workspaceRelation"])
    assertEquals(".", result.metadata["primaryTargetPath"])
    assertEquals("git status", result.metadata["targetSummary"])
    assertTrue(result.content.contains("High-risk approval required"))
  }

  private fun task(
    metadata: Map<String, String> = emptyMap(),
  ): AgentTask = AgentTask(
    id = "task-1",
    type = AgentTaskType.TOOL_CALL,
    input = """{"type":"tool_call"}""",
    createdAtEpochMs = 1_000L,
    metadata = metadata,
  )
}
