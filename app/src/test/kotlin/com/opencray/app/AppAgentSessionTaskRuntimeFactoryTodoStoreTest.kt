package com.opencray.app

import com.opencray.persistence.model.MemoryRecord
import com.opencray.persistence.store.MemoryStore
import com.opencray.core.contracts.AgentTaskType
import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.OpenCrayToolResultEvent
import com.opencray.runtime.bootstrap.BootstrapMode
import com.opencray.runtime.memory.MemoryFlushOutcome
import com.opencray.runtime.process.InMemoryAgentProcessRegistry
import com.opencray.runtime.context.RuntimeConversationRole
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
  fun compactionStoreForSessionReusesStoreForSameSessionIdAndSeparatesDifferentSessions() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-compaction"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-compaction").toPath()
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )

    val first = factory.compactionStoreForSession("session-1")
    val second = factory.compactionStoreForSession("session-1")
    val third = factory.compactionStoreForSession("session-2")

    assertSame(first, second)
    assertNotSame(first, third)
  }

  @Test
  fun processRegistryForSessionReusesRegistryForSameSessionIdAndSeparatesDifferentSessions() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-process-registry"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-process-registry").toPath()
    val createdRegistries = mutableListOf<InMemoryAgentProcessRegistry>()
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
      processRegistryProvider = {
        InMemoryAgentProcessRegistry().also(createdRegistries::add)
      },
    )

    val first = factory.processRegistryForSession("session-1")
    val second = factory.processRegistryForSession("session-1")
    val third = factory.processRegistryForSession("session-2")

    assertSame(first, second)
    assertNotSame(first, third)
    assertEquals(2, createdRegistries.size)
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
  fun recordRunCancellationAppendsReplayToolObservation() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-run-cancelled"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-run-cancelled").toPath()
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )

    factory.recordRunCancellation(
      sessionId = "session-1",
      taskId = "task-1",
      runId = "run-1",
      toolName = "Write",
    )

    val snapshot = factory.transcriptStoreForSession("session-1").snapshot()

    assertEquals(1, snapshot.size)
    assertEquals(RuntimeConversationRole.TOOL, snapshot.single().role)
    assertTrue(snapshot.single().content.contains("run_cancelled"))
    assertTrue(snapshot.single().content.contains("task_id=task-1"))
    assertTrue(snapshot.single().content.contains("run_id=run-1"))
    assertTrue(snapshot.single().content.contains("tool_name=Write"))
    assertTrue(snapshot.single().content.contains("outcome=user_cancelled"))
  }

  @Test
  fun repairTerminalReplayFromRunSnapshotsBackfillsInterruptedAndRetryAbandoned() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-terminal-repair"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-terminal-repair").toPath()
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )

    factory.repairTerminalReplayFromRunSnapshots(
      sessionId = "session-1",
      runs = listOf(
        AgentRunSnapshot(
          sessionId = "session-1",
          runId = "run-failed-1",
          taskId = "task-failed-1",
          acceptedAtEpochMs = 1_000L,
          updatedAtEpochMs = 1_100L,
          lifecycleState = com.opencray.core.orchestrator.QueueTaskLifecycleState.FAILED,
          taskState = com.opencray.core.contracts.AgentTaskState.FAILED,
          attempt = 1,
          executionStatus = com.opencray.core.contracts.ExecutionStatus.FAILED,
          errorCode = "RUNTIME_EXCEPTION",
        ),
        AgentRunSnapshot(
          sessionId = "session-1",
          runId = "run-failed-2",
          taskId = "task-failed-2",
          acceptedAtEpochMs = 2_000L,
          updatedAtEpochMs = 2_100L,
          lifecycleState = com.opencray.core.orchestrator.QueueTaskLifecycleState.FAILED,
          taskState = com.opencray.core.contracts.AgentTaskState.FAILED,
          attempt = 2,
          executionStatus = com.opencray.core.contracts.ExecutionStatus.FAILED,
          errorCode = "TOOL_EXECUTION_FAILED",
        ),
      ),
    )
    factory.repairTerminalReplayFromRunSnapshots(
      sessionId = "session-1",
      runs = listOf(
        AgentRunSnapshot(
          sessionId = "session-1",
          runId = "run-failed-1",
          taskId = "task-failed-1",
          acceptedAtEpochMs = 1_000L,
          updatedAtEpochMs = 1_100L,
          lifecycleState = com.opencray.core.orchestrator.QueueTaskLifecycleState.FAILED,
          taskState = com.opencray.core.contracts.AgentTaskState.FAILED,
          attempt = 1,
          executionStatus = com.opencray.core.contracts.ExecutionStatus.FAILED,
          errorCode = "RUNTIME_EXCEPTION",
        ),
        AgentRunSnapshot(
          sessionId = "session-1",
          runId = "run-failed-2",
          taskId = "task-failed-2",
          acceptedAtEpochMs = 2_000L,
          updatedAtEpochMs = 2_100L,
          lifecycleState = com.opencray.core.orchestrator.QueueTaskLifecycleState.FAILED,
          taskState = com.opencray.core.contracts.AgentTaskState.FAILED,
          attempt = 2,
          executionStatus = com.opencray.core.contracts.ExecutionStatus.FAILED,
          errorCode = "TOOL_EXECUTION_FAILED",
        ),
      ),
    )

    val snapshot = factory.transcriptStoreForSession("session-1").snapshot()

    assertEquals(2, snapshot.size)
    assertTrue(snapshot[0].content.startsWith("run_interrupted"))
    assertTrue(snapshot[0].content.contains("task_id=task-failed-1"))
    assertTrue(snapshot[1].content.startsWith("retry_abandoned"))
    assertTrue(snapshot[1].content.contains("attempt=2"))
  }

  @Test
  fun repairTerminalReplayFromRunSnapshotsMarksRestoredInterruptedProcessRuns() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-process-restore-repair"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-process-restore-repair").toPath()
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )

    factory.repairTerminalReplayFromRunSnapshots(
      sessionId = "session-1",
      runs = listOf(
        AgentRunSnapshot(
          sessionId = "session-1",
          runId = "run-restored-process",
          taskId = "task-restored-process",
          acceptedAtEpochMs = 1_000L,
          updatedAtEpochMs = 1_100L,
          lifecycleState = com.opencray.core.orchestrator.QueueTaskLifecycleState.FAILED,
          taskState = com.opencray.core.contracts.AgentTaskState.FAILED,
          attempt = 1,
          executionStatus = com.opencray.core.contracts.ExecutionStatus.FAILED,
          errorCode = ERROR_MANAGED_PROCESS_INTERRUPTED_ON_RESTORE,
          resultMetadata = mapOf(
            METADATA_RESTORED_TERMINAL_STATE to RESTORED_TERMINAL_STATE_INTERRUPTED,
          ),
        ),
      ),
    )

    val snapshot = factory.transcriptStoreForSession("session-1").snapshot()

    assertEquals(1, snapshot.size)
    assertTrue(snapshot.single().content.startsWith("run_interrupted"))
    assertTrue(snapshot.single().content.contains("outcome=restored_process_interrupted"))
    assertTrue(snapshot.single().content.contains("error_code=$ERROR_MANAGED_PROCESS_INTERRUPTED_ON_RESTORE"))
  }

  @Test
  fun recordSuccessfulToolInteractionAppendsToolCallAndResultReplaySummaries() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-tool-replay"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-tool-replay").toPath()
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )

    factory.recordSuccessfulToolInteraction(
      sessionId = "session-1",
      event = OpenCrayToolResultEvent(
        runId = "run-1",
        taskId = "task-1",
        turn = 2,
        call = AgentToolCall(
          toolName = "Read",
          arguments = buildJsonObject {
            put("file_path", "README.md")
            put("offset", 5)
            put("limit", 2)
          },
          reason = "Inspect the install section before answering.",
        ),
        result = AgentToolResult(
          toolName = "Read",
          status = AgentToolResultStatus.SUCCESS,
          content = "Line five\nLine six\n",
          metadata = mapOf(
            "filePath" to "README.md",
            "offset" to "5",
            "returnedLineCount" to "2",
          ),
        ),
        emittedAtEpochMs = 1_000L,
      ),
    )

    val snapshot = factory.transcriptStoreForSession("session-1").snapshot()

    assertEquals(2, snapshot.size)
    assertEquals(RuntimeConversationRole.TOOL, snapshot[0].role)
    assertTrue(snapshot[0].content.startsWith("tool_call "))
    assertTrue(snapshot[0].content.contains("\"run_id\":\"run-1\""))
    assertTrue(snapshot[0].content.contains("\"turn\":2"))
    assertTrue(snapshot[0].content.contains("\"tool_name\":\"Read\""))
    assertTrue(snapshot[0].content.contains("\"file_path\":\"README.md\""))
    assertTrue(snapshot[1].content.startsWith("tool_result "))
    assertTrue(snapshot[1].content.contains("\"status\":\"success\""))
    assertTrue(snapshot[1].content.contains("\"content_preview\":\"Line five Line six\""))
    assertTrue(snapshot[1].content.contains("\"filePath\":\"README.md\""))
  }

  @Test
  fun recordSuccessfulToolInteractionSkipsDuplicateReplayEntriesForSameRunTurn() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-tool-replay-dedupe"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-tool-replay-dedupe").toPath()
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )
    val event = OpenCrayToolResultEvent(
      runId = "run-1",
      taskId = "task-1",
      turn = 1,
      call = AgentToolCall(
        toolName = "Grep",
        arguments = buildJsonObject {
          put("pattern", "TODO")
          put("path", ".")
        },
      ),
      result = AgentToolResult(
        toolName = "Grep",
        status = AgentToolResultStatus.SUCCESS,
        content = "src/Main.kt:10:// TODO",
        metadata = mapOf(
          "path" to ".",
          "pattern" to "TODO",
          "matchCount" to "1",
        ),
      ),
      emittedAtEpochMs = 2_000L,
    )

    factory.recordSuccessfulToolInteraction(sessionId = "session-1", event = event)
    factory.recordSuccessfulToolInteraction(sessionId = "session-1", event = event)

    val snapshot = factory.transcriptStoreForSession("session-1").snapshot()

    assertEquals(2, snapshot.size)
    assertTrue(snapshot[0].content.contains("\"run_id\":\"run-1\""))
    assertTrue(snapshot[1].content.contains("\"matchCount\":\"1\""))
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

  @Test
  fun effectiveSoulProfileForOverlaysUserIdentityPreferenceAndSessionStylePreference() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-soul-memory"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-soul-memory").toPath()
    val sessionId = chatStore.loadState().activeSession.sessionId
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )

    val effective = factory.effectiveSoulProfileFor(
      sessionId = sessionId,
      soulProfile = PersonalizationLocalStore.SoulProfile(
        presetName = "BUILDER",
        customLabel = "",
        customGuidance = "Stay direct.",
      ),
      memoryRecords = listOf(
        memoryRecord(
          id = "agent-name",
          content = "Agent display name is Xiao Bai",
          kind = "user_preference",
          scope = "user",
          sourceSessionId = "session-source",
          preferenceKey = "agent_display_name",
          preferenceValue = "Xiao Bai",
        ),
        memoryRecord(
          id = "session-style",
          content = "Agent style profile should be warm",
          kind = "user_preference",
          scope = "session",
          sourceSessionId = sessionId,
          preferenceKey = "agent_style_profile",
          preferenceValue = "warm",
        ),
      ),
    )

    assertEquals("Xiao Bai", effective?.displayName)
    assertEquals("BUILDER", effective?.presetName)
    assertEquals("Stay direct.", effective?.customGuidance)
    assertEquals("warm and gentle", effective?.voice)
    assertEquals("warm", effective?.extensions?.get("tone"))
  }

  @Test
  fun visibleSkillInventoryForLoadsVisibleSkillsAndTracksInvalidEntries() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-skill-inventory"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-skill-inventory").toPath()
    val skillsRoot = temporaryFolder.newFolder("skills-root-skill-inventory")
    writeSkill(
      root = skillsRoot,
      relativeDirectory = "ui-ux-pro-max",
      frontMatter = """
        name: ui-ux-pro-max
        description: High-end UI review workflow.
        invocation-control: explicit-only
        user-invocable: true
      """.trimIndent(),
      body = "# UI UX Pro Max",
    )
    writeSkill(
      root = skillsRoot,
      relativeDirectory = "broken-skill",
      frontMatter = """
        name: broken-skill
        description: Invalid because it is unreachable.
        invocation-control: explicit-only
        user-invocable: false
      """.trimIndent(),
      body = "# Broken",
    )
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { listOf(skillsRoot) },
      mcpReportProvider = { null },
    )

    val inventory = factory.visibleSkillInventoryFor()
    val skillCatalog = factory.skillCatalogFor()

    assertEquals(1, inventory.visibleSkillCount)
    assertEquals(1, inventory.invalidSkillCount)
    assertEquals(listOf("ui-ux-pro-max"), inventory.skills.map { skill -> skill.name })
    assertEquals(listOf("ui-ux-pro-max"), inventory.trace.visible.map { trace -> trace.name })
    assertEquals(0, inventory.trace.implicitSkillCount)
    assertEquals(listOf("ui-ux-pro-max"), skillCatalog.skillsByName.keys.toList())
    assertEquals("# UI UX Pro Max", skillCatalog.skillsByName["ui-ux-pro-max"]?.markdownBody?.trim())
  }

  @Test
  fun prepareSessionContextFlushesOmittedHistoryAndReloadsFreshMemoryRecords() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-memory-flush"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-memory-flush").toPath()
    val workspaceId = "workspace-main"
    val sessionId = chatStore.loadState().activeSession.sessionId
    listOf(
      "Please default to Simplified Chinese for explanations.",
      "Do not use git reset --hard in this repo.",
      "Project uses the Gradle wrapper from the repo root.",
      "以后都用 PowerShell 命令。",
    ).forEach { text ->
      chatStore.appendMessage(
        sessionId = sessionId,
        role = com.opencray.persistence.model.ChatTranscriptRole.USER,
        text = text,
      )
    }
    (1..11).forEach { index ->
      chatStore.appendMessage(
        sessionId = sessionId,
        role = com.opencray.persistence.model.ChatTranscriptRole.USER,
        text = "Padding user message $index to keep the active transcript window bounded.",
      )
    }
    val memoryStore = InMemoryMemoryStore()
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
      memoryRecordsProvider = memoryStore::list,
      memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(
        memoryStore = memoryStore,
        workspaceIdProvider = { workspaceId },
      ),
    )

    val prepared = factory.prepareSessionContext(
      sessionId = sessionId,
      workspaceId = workspaceId,
      visibleThroughMessageId = null,
      excludedMessageIds = emptySet(),
      soulProfile = null,
      taskType = AgentTaskType.PROMPT,
      taskId = "task-memory-flush",
      taskInput = "Please keep using Chinese while continuing.",
      transcriptStore = factory.transcriptStoreForSession(sessionId),
      memoryRecords = memoryStore.list(),
    )

    assertEquals(MemoryFlushOutcome.WRITTEN, prepared.sessionContext.memoryFlushTrace.outcome)
    assertTrue(prepared.effectiveMemoryRecords.isNotEmpty())
    assertTrue(prepared.effectiveMemoryRecords.any { record ->
      record.content.contains("Chinese") || record.content.contains("PowerShell")
    })
    assertTrue(prepared.sessionContext.recalledMemory.memories.any { memory ->
      memory.content.contains("Chinese")
    })
  }

  @Test
  fun bootstrapContextForLoadsWorkspaceBootstrapFilesAndSupportsLightweightMode() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-bootstrap"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-bootstrap")
    Files.write(
      workspaceRoot.toPath().resolve("AGENTS.md"),
      "# Agents\nFollow the repo instructions.".toByteArray(StandardCharsets.UTF_8),
    )
    Files.write(
      workspaceRoot.toPath().resolve("SOUL.md"),
      "# Soul\nStay terse.".toByteArray(StandardCharsets.UTF_8),
    )
    Files.write(
      workspaceRoot.toPath().resolve("PROJECT.md"),
      "# Project\nThis repo uses Gradle.".toByteArray(StandardCharsets.UTF_8),
    )
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot.toPath()) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )

    val fullContext = factory.bootstrapContextFor(BootstrapMode.FULL)
    val lightweightContext = factory.bootstrapContextFor(BootstrapMode.LIGHTWEIGHT)

    assertEquals(listOf("AGENTS.md", "SOUL.md", "PROJECT.md"), fullContext.files.map { file -> file.name })
    assertEquals(listOf("AGENTS.md", "PROJECT.md"), lightweightContext.files.map { file -> file.name })
    assertEquals("full", fullContext.trace.mode)
    assertEquals("lightweight", lightweightContext.trace.mode)
  }

  @Test
  fun prepareSessionContextInjectsBootstrapContextFromWorkspaceRoots() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-bootstrap-session"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-bootstrap-session")
    val sessionId = chatStore.loadState().activeSession.sessionId
    Files.write(
      workspaceRoot.toPath().resolve("AGENTS.md"),
      "# Agents\nFollow the repo instructions.".toByteArray(StandardCharsets.UTF_8),
    )
    Files.write(
      workspaceRoot.toPath().resolve("PROJECT.md"),
      "# Project\nThis repo uses Gradle.".toByteArray(StandardCharsets.UTF_8),
    )
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot.toPath()) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )

    val prepared = factory.prepareSessionContext(
      sessionId = sessionId,
      workspaceId = "workspace-bootstrap",
      visibleThroughMessageId = null,
      excludedMessageIds = emptySet(),
      soulProfile = null,
      taskType = AgentTaskType.PROMPT,
      taskId = "task-bootstrap",
      taskInput = "Continue with workspace guidance.",
      transcriptStore = factory.transcriptStoreForSession(sessionId),
      memoryRecords = emptyList(),
    )

    assertEquals("full", prepared.sessionContext.bootstrapContext.trace.mode)
    assertEquals(listOf("AGENTS.md", "PROJECT.md"), prepared.sessionContext.bootstrapContext.files.map { file -> file.name })
    assertTrue(prepared.sessionContext.bootstrapContext.files.first().content.contains("Follow the repo instructions."))
  }

  @Test
  fun prepareSessionContextCompactsOlderTranscriptIntoDurableSummaries() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-durable-compaction"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-durable-compaction").toPath()
    val sessionId = chatStore.loadState().activeSession.sessionId
    (1..15).forEach { index ->
      chatStore.appendMessage(
        sessionId = sessionId,
        role = com.opencray.persistence.model.ChatTranscriptRole.USER,
        text = "Conversation message $index that should eventually move into durable compaction.",
      )
    }
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )

    val prepared = factory.prepareSessionContext(
      sessionId = sessionId,
      workspaceId = "workspace-durable",
      visibleThroughMessageId = null,
      excludedMessageIds = emptySet(),
      soulProfile = null,
      taskType = AgentTaskType.PROMPT,
      taskId = "task-durable-compaction",
      taskInput = "Continue after durable compaction.",
      transcriptStore = factory.transcriptStoreForSession(sessionId),
      memoryRecords = emptyList(),
    )

    assertTrue(prepared.sessionContext.durableCompaction.included)
    assertTrue(prepared.sessionContext.durableCompaction.text.contains("Older session history has been durably compacted into summaries."))
    assertTrue(prepared.sessionContext.durableCompaction.trace.compactedThisRun)
    assertEquals(16, prepared.sessionContext.durableCompaction.trace.sourceTranscriptMessageCount)
    assertEquals(12, prepared.sessionContext.durableCompaction.trace.retainedTranscriptMessageCount)
    assertEquals(4, prepared.sessionContext.durableCompaction.trace.latestCompactedMessageCount)
    assertEquals(1, prepared.sessionContext.durableCompaction.trace.includedSummaryCount)
    assertEquals(4, prepared.sessionContext.durableCompaction.trace.totalCompactedMessageCount)
    assertEquals(12, prepared.sessionContext.conversation.size)
    assertEquals(12, factory.transcriptStoreForSession(sessionId).snapshot().size)
    assertFalse(prepared.sessionContext.conversation.first().content.contains("Conversation message 1"))
  }

  private fun memoryRecord(
    id: String,
    content: String,
    kind: String,
    scope: String,
    sourceSessionId: String,
    status: String = "active",
    workspaceId: String? = null,
    preferenceKey: String? = null,
    preferenceValue: String? = null,
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
      preferenceKey?.let { "preference_key" to it },
      preferenceValue?.let { "preference_value" to it },
    ).toMap(),
  )

  private fun writeSkill(
    root: File,
    relativeDirectory: String,
    frontMatter: String,
    body: String,
  ): File {
    val skillDirectory = root.resolve(relativeDirectory)
    Files.createDirectories(skillDirectory.toPath())
    val skillFile = skillDirectory.resolve("SKILL.md")
    val content = buildString {
      appendLine("---")
      appendLine(frontMatter)
      appendLine("---")
      appendLine(body)
    }
    Files.write(skillFile.toPath(), content.toByteArray(StandardCharsets.UTF_8))
    return skillFile
  }

  private class InMemoryMemoryStore : MemoryStore {
    private val records = linkedMapOf<String, MemoryRecord>()

    override fun list(): List<MemoryRecord> = records.values.toList()

    override fun upsert(record: MemoryRecord) {
      records[record.id] = record
    }

    override fun delete(id: String): Boolean = records.remove(id) != null

    override fun clear(): Boolean {
      val hadRecords = records.isNotEmpty()
      records.clear()
      return hadRecords
    }
  }
}
