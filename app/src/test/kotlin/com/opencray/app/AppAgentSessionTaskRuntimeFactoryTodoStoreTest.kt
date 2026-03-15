package com.opencray.app

import com.opencray.persistence.model.MemoryRecord
import com.opencray.runtime.context.RuntimeConversationRole
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
  fun transcriptStoreForSessionReusesStoreForSameSessionIdAndSeparatesDifferentSessions() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-transcript"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-transcript").toPath()
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )

    val first = factory.transcriptStoreForSession("session-1")
    val second = factory.transcriptStoreForSession("session-1")
    val third = factory.transcriptStoreForSession("session-2")

    assertSame(first, second)
    assertNotSame(first, third)
  }

  @Test
  fun recordApprovalRejectionAppendsReplayToolObservation() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-approval-rejection"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-approval-rejection").toPath()
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )

    factory.recordApprovalRejection(
      sessionId = "session-1",
      toolName = "Write",
      isHighRisk = true,
    )

    val snapshot = factory.transcriptStoreForSession("session-1").snapshot()

    assertEquals(1, snapshot.size)
    assertEquals(RuntimeConversationRole.TOOL, snapshot.single().role)
    assertTrue(snapshot.single().content.contains("approval_rejected"))
    assertTrue(snapshot.single().content.contains("tool_name=Write"))
    assertTrue(snapshot.single().content.contains("executed=false"))
    assertTrue(snapshot.single().content.contains("risk=high_risk"))
  }

  @Test
  fun recalledMemoryForUsesPersistedRecordsAndSessionAndWorkspaceFiltering() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-memory"))
    val personalizationStore = PersonalizationLocalStore(temporaryFolder.newFolder("personalization-memory"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root").toPath()
    val workspaceId = requireNotNull(AppWorkspaceIdentity.fromRoots(setOf(workspaceRoot)))
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
        workspaceId = workspaceId,
      ),
    )
    personalizationStore.upsertMemoryRecord(
      memoryRecord(
        id = "fact-other-workspace",
        content = "Project uses pnpm workspaces for package management.",
        kind = "project_fact",
        scope = "workspace",
        sourceSessionId = "session-source",
        workspaceId = "workspace-other",
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
    assertTrue(recalled.memories.none { memory -> memory.id == "fact-other-workspace" })
    assertTrue(recalled.memories.none { memory -> memory.id == "commitment-other-session" })
  }

  private fun memoryRecord(
    id: String,
    content: String,
    kind: String,
    scope: String,
    sourceSessionId: String,
    status: String = "active",
    workspaceId: String? = null,
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
    ) + listOfNotNull(
      workspaceId?.let { "workspace_id" to it },
    ).toMap(),
  )
}
