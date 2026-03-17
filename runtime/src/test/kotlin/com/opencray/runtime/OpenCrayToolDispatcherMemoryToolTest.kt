package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.persistence.model.MemoryRecord
import com.opencray.runtime.memory.MemoryToolContext
import com.opencray.runtime.memory.formatMemoryDateStamp
import java.nio.file.Files
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenCrayToolDispatcherMemoryToolTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun definitionsIncludeMemoryToolsWhenContextIsConfigured() {
    val dispatcher = dispatcher()

    val toolNames = dispatcher.definitions().map { definition -> definition.name }

    assertTrue("memory_search" in toolNames)
    assertTrue("memory_get" in toolNames)
  }

  @Test
  fun dispatchMemorySearchAndGetReadProjectedCorpusInsteadOfRawStore() {
    val dispatcher = dispatcher()
    val expectedPath = "memory/${formatMemoryDateStamp(DAY_2_EPOCH_MS)}.md"

    val searchResult = dispatcher.dispatch(
      task = task(),
      call = AgentToolCall(
        toolName = "memory_search",
        arguments = buildJsonObject {
          put("query", "gradle wrapper repo root")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, searchResult.status)
    assertEquals("read_memory", searchResult.metadata["capabilityKind"])
    assertEquals("none", searchResult.metadata["workspaceRelation"])
    assertEquals("1", searchResult.metadata["resultCount"])
    assertTrue(searchResult.content.contains(expectedPath))
    assertTrue(searchResult.content.contains("kind=project_fact"))

    val getResult = dispatcher.dispatch(
      task = task(),
      call = AgentToolCall(
        toolName = "memory_get",
        arguments = buildJsonObject {
          put("path", expectedPath)
          put("from", 5)
          put("lines", 4)
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, getResult.status)
    assertEquals("read_memory", getResult.metadata["capabilityKind"])
    assertEquals("file", getResult.metadata["targetKind"])
    assertEquals("none", getResult.metadata["workspaceRelation"])
    assertEquals(expectedPath, getResult.metadata["primaryTargetPath"])
    assertEquals(expectedPath, getResult.metadata["path"])
    assertTrue(getResult.content.startsWith("$expectedPath#L5"))
    assertTrue(getResult.content.contains("## mem-workspace"))
  }

  private fun dispatcher(): OpenCrayToolDispatcher {
    val workspaceRoot = temporaryFolder.newFolder("dispatcher-memory-workspace").toPath()
    Files.createDirectories(workspaceRoot)
    return OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        memoryToolContext = MemoryToolContext(
          sessionId = "session-main",
          workspaceId = "workspace-main",
          records = listOf(
            memoryRecord(
              id = "mem-workspace",
              content = "Project uses the Gradle wrapper from the repo root.",
              kind = "project_fact",
              scope = "workspace",
              sourceSessionId = "session-source",
              workspaceId = "workspace-main",
              confirmedAtEpochMs = DAY_2_EPOCH_MS,
              updatedAtEpochMs = DAY_2_EPOCH_MS,
            ),
          ),
        ),
      ),
    )
  }

  private fun task(): AgentTask = AgentTask(
    id = "task-memory-tool",
    type = AgentTaskType.PROMPT,
    input = "Search projected memory.",
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "TEST_ALLOW",
    ),
    createdAtEpochMs = 1L,
  )

  private fun runtimeHooks(): RuntimeExecutionHooks = RuntimeExecutionHooks(
    isCancellationRequested = { false },
    requestRetry = { _ -> Unit },
  )

  private fun memoryRecord(
    id: String,
    content: String,
    kind: String,
    scope: String,
    sourceSessionId: String,
    workspaceId: String? = null,
    confirmedAtEpochMs: Long,
    updatedAtEpochMs: Long,
  ): MemoryRecord = MemoryRecord(
    id = id,
    content = content,
    createdAtEpochMs = updatedAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    tags = listOf(
      "kind:$kind",
      "scope:$scope",
      "status:active",
    ),
    extensions = mapOf(
      "kind" to kind,
      "scope" to scope,
      "status" to "active",
      "source_session_id" to sourceSessionId,
      "last_confirmed_at_epoch_ms" to confirmedAtEpochMs.toString(),
    ) + listOfNotNull(
      workspaceId?.let { "workspace_id" to it },
    ).toMap(),
  )

  private companion object {
    const val DAY_2_EPOCH_MS: Long = 1_710_086_400_000L
  }
}
