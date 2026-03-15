package com.opencray.app

import com.opencray.persistence.model.MemoryRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppAgentSessionTaskRuntimeFactoryTodoStoreTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun todoStoreForSessionReusesStoreForSameSessionIdAndSeparatesDifferentSessions() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root").toPath()
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )

    val first = factory.todoStoreForSession("session-1")
    val second = factory.todoStoreForSession("session-1")
    val third = factory.todoStoreForSession("session-2")

    assertSame(first, second)
    assertNotSame(first, third)
  }

  @Test
  fun recalledMemoryForUsesPersistedRecordsAndSessionScopeFiltering() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-memory"))
    val personalizationStore = PersonalizationLocalStore(temporaryFolder.newFolder("personalization-memory"))
    personalizationStore.upsertMemoryRecord(
      memoryRecord(
        id = "pref-user",
        content = "Default to concise Chinese replies.",
        kind = "user_preference",
        scope = "user",
        sourceSessionId = "session-source",
      ),
    )
    personalizationStore.upsertMemoryRecord(
      memoryRecord(
        id = "fact-workspace",
        content = "Project uses the Gradle wrapper from the repo root.",
        kind = "project_fact",
        scope = "workspace",
        sourceSessionId = "session-source",
      ),
    )
    personalizationStore.upsertMemoryRecord(
      memoryRecord(
        id = "commitment-other-session",
        content = "Finish the docs pass after the queue repair.",
        kind = "task_commitment",
        scope = "session",
        status = "open",
        sourceSessionId = "session-other",
      ),
    )
    val workspaceRoot = temporaryFolder.newFolder("workspace-root").toPath()
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
      memoryRecordsProvider = personalizationStore::listMemoryRecords,
    )

    val recalled = factory.recalledMemoryFor(
      sessionId = "session-main",
      taskInput = "Please keep using Chinese and verify the Gradle setup.",
    )

    assertEquals(2, recalled.memories.size)
    assertTrue(recalled.memories.any { memory -> memory.id == "pref-user" })
    assertTrue(recalled.memories.any { memory -> memory.id == "fact-workspace" })
    assertTrue(recalled.memories.none { memory -> memory.id == "commitment-other-session" })
  }

  private fun memoryRecord(
    id: String,
    content: String,
    kind: String,
    scope: String,
    sourceSessionId: String,
    status: String = "active",
  ): MemoryRecord = MemoryRecord(
    id = id,
    content = content,
    createdAtEpochMs = 1_000L,
    updatedAtEpochMs = 1_001L,
    tags = listOf(
      "kind:$kind",
      "scope:$scope",
      "status:$status",
    ),
    extensions = mapOf(
      "kind" to kind,
      "scope" to scope,
      "status" to status,
      "source_session_id" to sourceSessionId,
    ),
  )
}
