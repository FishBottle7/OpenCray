package com.opencray.core.contracts

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Test

class ContractSerializationRoundtripTest {
  @Test
  fun agentTaskAndExecutionResultRoundTrip() {
    val task = AgentTask(
      id = "task-1",
      type = AgentTaskType.SKILL_CALL,
      input = "Summarize the workspace state",
      state = AgentTaskState.RUNNING,
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ASK,
        reasonCode = "SAFE_MODE_HIGH_RISK_APPROVAL",
        approvalRisk = PolicyApprovalRisk.HIGH_RISK,
      ),
      skillName = "workspace-summary",
      createdAtEpochMs = 1_710_000_000_000,
      updatedAtEpochMs = 1_710_000_000_010,
      metadata = mapOf("source" to "queue"),
    )

    val taskEncoded = ContractJson.instance.encodeToString(task)
    val taskDecoded = ContractJson.instance.decodeFromString<AgentTask>(taskEncoded)
    assertEquals(task, taskDecoded)

    val result = ExecutionResult(
      taskId = task.id,
      status = ExecutionStatus.SUCCESS,
      exitCode = 0,
      stdout = "done",
      stderr = "",
      startedAtEpochMs = 1_710_000_000_100,
      finishedAtEpochMs = 1_710_000_000_200,
      policyDecision = task.policyDecision,
      metadata = mapOf("runtime" to "python"),
    )

    val resultEncoded = ContractJson.instance.encodeToString(result)
    val resultDecoded = ContractJson.instance.decodeFromString<ExecutionResult>(resultEncoded)
    assertEquals(result, resultDecoded)
  }

  @Test
  fun skillSpecRoundTrip() {
    val spec = SkillSpec(
      name = "android-build-summary",
      description = "Summarize Android build and test output for the active module.",
      license = "MIT",
      compatibility = listOf("agent-skills-core", "opencode"),
      metadata = mapOf("owner" to "core-team"),
      allowedTools = listOf("bash", "read"),
      extensions = mapOf("x-vendor-flag" to "disabled"),
    )

    val encoded = ContractJson.instance.encodeToString(spec)
    val decoded = ContractJson.instance.decodeFromString<SkillSpec>(encoded)
    assertEquals(spec, decoded)
  }

  @Test
  fun mcpServerSpecRoundTripForAllTransports() {
    val stdioSpec = McpServerSpec(
      id = "mcp-local",
      displayName = "Local MCP",
      transport = McpTransportDescriptor.LocalStdio(
        command = "python",
        args = listOf("server.py"),
        environment = mapOf("PYTHONUNBUFFERED" to "1"),
        workingDirectory = "/workspace/mcp",
      ),
      trustState = McpServerTrustState.ENABLED,
    )
    val stdioEncoded = ContractJson.instance.encodeToString(stdioSpec)
    val stdioDecoded = ContractJson.instance.decodeFromString<McpServerSpec>(stdioEncoded)
    assertEquals(stdioSpec, stdioDecoded)

    val httpSpec = McpServerSpec(
      id = "mcp-http",
      displayName = "Remote HTTP MCP",
      transport = McpTransportDescriptor.RemoteHttp(
        url = "https://mcp.example.com/stream",
        headers = mapOf("MCP-Protocol-Version" to "2025-11-05"),
      ),
      auth = McpAuthSpec(credentialRef = "secret://mcp-http-token"),
    )
    val httpEncoded = ContractJson.instance.encodeToString(httpSpec)
    val httpDecoded = ContractJson.instance.decodeFromString<McpServerSpec>(httpEncoded)
    assertEquals(httpSpec, httpDecoded)

    val sseSpec = McpServerSpec(
      id = "mcp-sse",
      displayName = "Remote SSE MCP",
      transport = McpTransportDescriptor.RemoteSse(
        eventsUrl = "https://mcp.example.com/events",
        postUrl = "https://mcp.example.com/messages",
      ),
    )
    val sseEncoded = ContractJson.instance.encodeToString(sseSpec)
    val sseDecoded = ContractJson.instance.decodeFromString<McpServerSpec>(sseEncoded)
    assertEquals(sseSpec, sseDecoded)
  }
}
