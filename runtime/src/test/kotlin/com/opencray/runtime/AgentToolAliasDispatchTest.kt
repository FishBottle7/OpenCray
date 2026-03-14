package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RetryRequest
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentToolAliasDispatchTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun definitionsExposeClaudeStyleToolsAndCompatibleAliases() {
    val dispatcher = dispatcher()

    val definitionNames = dispatcher.definitions().map { definition -> definition.name }.toSet()
    val readDefinition = requireNotNull(dispatcher.definitions().firstOrNull { definition -> definition.name == "read" })
    val pythonDefinition = requireNotNull(dispatcher.definitions().firstOrNull { definition -> definition.name == "python_exec" })

    assertTrue("LS" in definitionNames)
    assertTrue("Read" in definitionNames)
    assertTrue("Write" in definitionNames)
    assertTrue("Grep" in definitionNames)
    assertTrue("Glob" in definitionNames)
    assertTrue("Edit" in definitionNames)
    assertTrue("MultiEdit" in definitionNames)
    assertTrue("TodoWrite" in definitionNames)
    assertTrue("read" in definitionNames)
    assertTrue("write" in definitionNames)
    assertTrue("list" in definitionNames)
    assertTrue("ls" in definitionNames)
    assertTrue("grep" in definitionNames)
    assertTrue("glob" in definitionNames)
    assertTrue("edit" in definitionNames)
    assertTrue("multiedit" in definitionNames)
    assertTrue("todowrite" in definitionNames)
    assertTrue("bash" !in definitionNames)
    assertTrue(readDefinition.description.contains("Compatibility alias for Read"))
    assertTrue(pythonDefinition.description.contains("follows execute-command policy gates"))
  }

  @Test
  fun readAliasDispatchesToClaudeReadAndPreservesCanonicalMapping() {
    val workspaceRoot = temporaryFolder.newFolder("tool-alias-read").toPath()
    Files.write(
      workspaceRoot.resolve("README.md"),
      "alias content".toByteArray(StandardCharsets.UTF_8),
    )
    val dispatcher = dispatcher(workspaceRoot = workspaceRoot)

    val result = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "read",
        arguments = JsonObject(
          mapOf("path" to JsonPrimitive("README.md")),
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("read", result.toolName)
    assertEquals("Read", result.metadata["canonicalToolName"])
    assertEquals("alias content", result.content)
  }

  @Test
  fun grepAliasDispatchesToClaudeGrepAndPreservesCanonicalMapping() {
    val workspaceRoot = temporaryFolder.newFolder("tool-alias-grep").toPath()
    Files.write(
      workspaceRoot.resolve("notes.txt"),
      "first line\nmatch here".toByteArray(StandardCharsets.UTF_8),
    )
    val dispatcher = dispatcher(workspaceRoot = workspaceRoot)

    val result = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "grep",
        arguments = JsonObject(
          mapOf(
            "pattern" to JsonPrimitive("match"),
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("grep", result.toolName)
    assertEquals("Grep", result.metadata["canonicalToolName"])
    assertTrue(result.content.contains("notes.txt:2:match here"))
  }

  private fun dispatcher(
    workspaceRoot: java.nio.file.Path = temporaryFolder.newFolder("tool-alias-workspace").toPath(),
  ): OpenCrayToolDispatcher = OpenCrayToolDispatcher(
    OpenCrayToolDispatcherConfig(
      workspaceRoots = setOf(workspaceRoot),
    ),
  )

  private fun agentTask(
    policyDecision: PolicyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "HOST_ALLOW",
    ),
    metadata: Map<String, String> = emptyMap(),
  ): AgentTask = AgentTask(
    id = "task-${System.nanoTime()}",
    type = AgentTaskType.TOOL_CALL,
    input = """{"type":"tool_call"}""",
    policyDecision = policyDecision,
    metadata = metadata,
    createdAtEpochMs = 1_000L,
  )

  private fun runtimeHooks(): RuntimeExecutionHooks = RuntimeExecutionHooks(
    isCancellationRequested = { false },
    requestRetry = { _: RetryRequest -> error("Retry not expected in AgentToolAliasDispatchTest.") },
  )
}
