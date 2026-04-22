package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.session.SessionSearchCompactionSummary
import com.opencray.runtime.session.SessionSearchSession
import com.opencray.runtime.session.SessionSearchToolContext
import java.nio.file.Files
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenCrayToolDispatcherSessionToolTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun definitionsIncludeSessionToolsWhenContextIsConfigured() {
    val dispatcher = dispatcher()

    val toolNames = dispatcher.definitions().map { definition -> definition.name }

    assertTrue("session_search" in toolNames)
    assertTrue("session_get" in toolNames)
    assertTrue("past_session_search" in toolNames)
    assertTrue("past_session_get" in toolNames)
  }

  @Test
  fun dispatchSessionSearchAndGetReadProjectedPriorSessionCorpus() {
    val dispatcher = dispatcher()
    val expectedPath = "sessions/session-archive.md"

    val searchResult = dispatcher.dispatch(
      task = task(),
      call = AgentToolCall(
        toolName = "session_search",
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
    assertTrue(searchResult.content.contains("session_id=session-archive"))
    assertFalse(searchResult.content.contains("session-main"))

    val getResult = dispatcher.dispatch(
      task = task(),
      call = AgentToolCall(
        toolName = "session_get",
        arguments = buildJsonObject {
          put("path", expectedPath)
          put("from", 8)
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
    assertEquals("session-archive", getResult.metadata["recordIds"])
    assertTrue(getResult.content.startsWith("$expectedPath#L8-L11"))
    assertTrue(getResult.content.contains("## Summary 1"))
    assertTrue(getResult.content.contains("We decided to keep the Gradle wrapper at the repo root."))
  }

  @Test
  fun dispatchPastSessionSearchAndGetExposeArchiveSurfaceAndReferences() {
    val dispatcher = dispatcher()
    val expectedPath = "sessions/session-archive.md"

    val searchResult = dispatcher.dispatch(
      task = task(),
      call = AgentToolCall(
        toolName = "past_session_search",
        arguments = buildJsonObject {
          put("query", "gradle wrapper repo root")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, searchResult.status)
    assertEquals("read_memory", searchResult.metadata["capabilityKind"])
    assertEquals("session_archive", searchResult.metadata["surface"])
    assertEquals("1", searchResult.metadata["resultCount"])
    assertTrue(searchResult.content.contains("Found 1 past-session archive match(es)."))
    assertTrue(searchResult.content.contains("session_id=session-archive"))
    assertTrue(searchResult.content.contains("summary=We decided to keep the Gradle wrapper at the repo root."))
    assertTrue(searchResult.content.contains("reference=$expectedPath#L8-L11"))
    assertFalse(searchResult.content.contains("session-main"))

    val getResult = dispatcher.dispatch(
      task = task(),
      call = AgentToolCall(
        toolName = "past_session_get",
        arguments = buildJsonObject {
          put("path", expectedPath)
          put("from", 8)
          put("lines", 4)
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, getResult.status)
    assertEquals("read_memory", getResult.metadata["capabilityKind"])
    assertEquals("session_archive", getResult.metadata["surface"])
    assertEquals("file", getResult.metadata["targetKind"])
    assertEquals(expectedPath, getResult.metadata["primaryTargetPath"])
    assertEquals(expectedPath, getResult.metadata["path"])
    assertEquals("session-archive", getResult.metadata["recordIds"])
    assertTrue(getResult.content.startsWith("$expectedPath#L8-L11"))
    assertTrue(getResult.content.contains("## Summary 1"))
  }

  private fun dispatcher(): OpenCrayToolDispatcher {
    val workspaceRoot = temporaryFolder.newFolder("dispatcher-session-workspace").toPath()
    Files.createDirectories(workspaceRoot)
    return OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        sessionSearchToolContext = SessionSearchToolContext(
          sessionId = "session-main",
          sessions = listOf(
            SessionSearchSession(
              sessionId = "session-main",
              title = "Current working session",
              createdAtEpochMs = CURRENT_SESSION_EPOCH_MS,
              updatedAtEpochMs = CURRENT_SESSION_EPOCH_MS,
              messages = listOf(
                RuntimeConversationMessage(
                  role = RuntimeConversationRole.ASSISTANT,
                  content = "Active session note about the Gradle wrapper at the repo root.",
                ),
              ),
              compactionSummaries = listOf(
                SessionSearchCompactionSummary(
                  text = "Current-session summary that should stay hidden from session_search.",
                  compactedAtEpochMs = CURRENT_SESSION_EPOCH_MS,
                ),
              ),
            ),
            SessionSearchSession(
              sessionId = "session-archive",
              title = "Build setup follow-up",
              createdAtEpochMs = ARCHIVE_SESSION_EPOCH_MS,
              updatedAtEpochMs = ARCHIVE_SESSION_EPOCH_MS,
              messages = listOf(
                RuntimeConversationMessage(
                  role = RuntimeConversationRole.USER,
                  content = "What did we decide about the build setup?",
                ),
                RuntimeConversationMessage(
                  role = RuntimeConversationRole.ASSISTANT,
                  content = "We use the Gradle wrapper from the repo root.",
                ),
              ),
              compactionSummaries = listOf(
                SessionSearchCompactionSummary(
                  text = "We decided to keep the Gradle wrapper at the repo root.",
                  compactedAtEpochMs = ARCHIVE_SESSION_EPOCH_MS,
                ),
              ),
            ),
          ),
        ),
      ),
    )
  }

  private fun task(): AgentTask = AgentTask(
    id = "task-session-tool",
    type = AgentTaskType.PROMPT,
    input = "Search prior session history.",
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

  private companion object {
    const val CURRENT_SESSION_EPOCH_MS: Long = 1_710_172_800_000L
    const val ARCHIVE_SESSION_EPOCH_MS: Long = 1_710_086_400_000L
  }
}
