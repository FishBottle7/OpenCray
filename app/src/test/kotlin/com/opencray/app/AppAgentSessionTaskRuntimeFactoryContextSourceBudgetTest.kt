package com.opencray.app

import com.opencray.core.contracts.AgentTaskType
import com.opencray.persistence.model.MemoryRecord
import com.opencray.runtime.memory.MemoryKind
import com.opencray.runtime.memory.MemoryScope
import com.opencray.runtime.memory.MemoryStatus
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppAgentSessionTaskRuntimeFactoryContextSourceBudgetTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun prepareSessionContextUsesBudgetProfileForMemoryRecallAndBootstrapCaps() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-source-budget"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-source-budget").toPath()
    writeFile(workspaceRoot.resolve("AGENTS.md"), "A".repeat(2_200))
    writeFile(workspaceRoot.resolve("PROJECT.md"), "B".repeat(2_200))
    val workspaceId = "workspace-source-budget"
    val records = listOf(
      memoryRecord(
        id = "pref-1",
        content = "sharedtoken prefer concise replies for coding work.",
        kind = MemoryKind.USER_PREFERENCE,
        scope = MemoryScope.USER,
      ),
      memoryRecord(
        id = "pref-2",
        content = "sharedtoken prefer direct explanations when debugging.",
        kind = MemoryKind.USER_PREFERENCE,
        scope = MemoryScope.USER,
      ),
      memoryRecord(
        id = "pref-3",
        content = "sharedtoken prefer Chinese summaries after technical work.",
        kind = MemoryKind.USER_PREFERENCE,
        scope = MemoryScope.USER,
      ),
      memoryRecord(
        id = "fact-1",
        content = "sharedtoken project uses Gradle from the repository root.",
        kind = MemoryKind.PROJECT_FACT,
        scope = MemoryScope.WORKSPACE,
        workspaceId = workspaceId,
      ),
      memoryRecord(
        id = "fact-2",
        content = "sharedtoken runtime module owns prompt assembly behavior.",
        kind = MemoryKind.PROJECT_FACT,
        scope = MemoryScope.WORKSPACE,
        workspaceId = workspaceId,
      ),
      memoryRecord(
        id = "fact-3",
        content = "sharedtoken docs describe context budget presets for operators.",
        kind = MemoryKind.PROJECT_FACT,
        scope = MemoryScope.WORKSPACE,
        workspaceId = workspaceId,
      ),
      memoryRecord(
        id = "rule-1",
        content = "sharedtoken do not skip focused verification before reporting done.",
        kind = MemoryKind.DURABLE_INSTRUCTION,
        scope = MemoryScope.USER,
      ),
      memoryRecord(
        id = "rule-2",
        content = "sharedtoken keep fixes conservative when stabilizing runtime behavior.",
        kind = MemoryKind.DURABLE_INSTRUCTION,
        scope = MemoryScope.USER,
      ),
    )
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
      memoryRecordsProvider = { records },
    )

    val compactPrepared = factory.prepareSessionContext(
      sessionId = "session-compact",
      workspaceId = workspaceId,
      visibleThroughMessageId = null,
      excludedMessageIds = emptySet(),
      soulProfile = null,
      taskType = AgentTaskType.PROMPT,
      taskId = "task-compact",
      taskInput = "sharedtoken",
      transcriptStore = factory.transcriptStoreForSession("session-compact"),
      memoryRecords = records,
      llmMetadata = mapOf("context_budget_preset" to "compact"),
    )
    val expandedPrepared = factory.prepareSessionContext(
      sessionId = "session-expanded",
      workspaceId = workspaceId,
      visibleThroughMessageId = null,
      excludedMessageIds = emptySet(),
      soulProfile = null,
      taskType = AgentTaskType.PROMPT,
      taskId = "task-expanded",
      taskInput = "sharedtoken",
      transcriptStore = factory.transcriptStoreForSession("session-expanded"),
      memoryRecords = records,
      llmMetadata = mapOf("context_budget_preset" to "expanded"),
    )

    assertEquals(5, compactPrepared.sessionContext.recalledMemory.memories.size)
    assertEquals(8, expandedPrepared.sessionContext.recalledMemory.memories.size)
    assertTrue(
      expandedPrepared.sessionContext.bootstrapContext.files.sumOf { file -> file.content.length } >
        compactPrepared.sessionContext.bootstrapContext.files.sumOf { file -> file.content.length },
    )
    assertEquals(2_800, compactPrepared.sessionContext.bootstrapContext.files.sumOf { file -> file.content.length })
    assertEquals(4_400, expandedPrepared.sessionContext.bootstrapContext.files.sumOf { file -> file.content.length })
  }

  private fun writeFile(path: java.nio.file.Path, content: String) {
    Files.write(path, content.toByteArray(StandardCharsets.UTF_8))
  }

  private fun memoryRecord(
    id: String,
    content: String,
    kind: MemoryKind,
    scope: MemoryScope,
    workspaceId: String? = null,
  ): MemoryRecord = MemoryRecord(
    id = id,
    content = content,
    createdAtEpochMs = 1_000L,
    updatedAtEpochMs = 1_100L,
    tags = listOf(
      "kind:${kind.name.lowercase()}",
      "scope:${scope.name.lowercase()}",
      "status:${MemoryStatus.ACTIVE.name.lowercase()}",
    ),
    extensions = buildMap {
      put("kind", kind.name.lowercase())
      put("scope", scope.name.lowercase())
      put("status", MemoryStatus.ACTIVE.name.lowercase())
      put("source_session_id", "source-session")
      workspaceId?.let { put("workspace_id", it) }
    },
  )
}
