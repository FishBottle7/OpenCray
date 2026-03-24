package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.persistence.model.MemoryRecord
import com.opencray.persistence.store.MemoryStore
import com.opencray.runtime.context.RuntimeSoulProfile
import com.opencray.runtime.memory.MemoryKind
import com.opencray.runtime.memory.MemoryCandidateExtractor
import com.opencray.runtime.memory.MemoryFlushOutcome
import com.opencray.runtime.memory.MemoryInteractionPreferenceExtensionKeys
import com.opencray.runtime.memory.MemoryPreferenceKeys
import com.opencray.runtime.memory.MemoryRecallRequest
import com.opencray.runtime.memory.MemoryRecordExtensionKeys
import com.opencray.runtime.memory.MemoryRetriever
import com.opencray.runtime.memory.MemoryScope
import com.opencray.runtime.memory.MemoryStewardshipAction
import com.opencray.runtime.memory.MemoryStewardshipDecision
import com.opencray.runtime.memory.MemoryStewardshipInterpretation
import com.opencray.runtime.memory.MemoryStewardshipInterpreter
import com.opencray.runtime.memory.MemoryStewardshipRequest
import com.opencray.runtime.memory.MemoryStewardshipResolutionReason
import com.opencray.runtime.memory.MemoryStewardshipService
import com.opencray.runtime.memory.SoulMemoryIntent
import com.opencray.runtime.memory.SoulMemoryIntentInterpretation
import com.opencray.runtime.memory.SoulMemoryIntentInterpreter
import com.opencray.runtime.memory.SoulMemoryIntentRequest
import com.opencray.runtime.memory.TaskCommitmentIntentAction
import com.opencray.runtime.memory.TaskCommitmentIntentDecision
import com.opencray.runtime.memory.TaskCommitmentIntentInterpretation
import com.opencray.runtime.memory.TaskCommitmentIntentInterpreter
import com.opencray.runtime.memory.TaskCommitmentIntentRequest
import com.opencray.runtime.memory.UserMemoryIntent
import com.opencray.runtime.memory.UserMemoryIntentInterpretation
import com.opencray.runtime.memory.UserMemoryIntentInterpreter
import com.opencray.runtime.memory.UserMemoryIntentRequest
import com.opencray.runtime.memory.MemoryWriter
import com.opencray.runtime.memory.TaskCommitmentResolver
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.soul.InteractionPreferenceState
import com.opencray.runtime.soul.RelationshipEvent
import com.opencray.runtime.soul.RelationshipEventConfidence
import com.opencray.runtime.soul.RelationshipEventInterpretation
import com.opencray.runtime.soul.RelationshipEventInterpreter
import com.opencray.runtime.soul.RelationshipEventRequest
import com.opencray.runtime.soul.RelationshipEventScope
import com.opencray.runtime.soul.RelationshipEventType
import com.opencray.runtime.soul.RelationshipEventValence
import com.opencray.runtime.soul.MemoryBackedSoulProfileResolver
import com.opencray.runtime.soul.SoulPlasticity
import com.opencray.runtime.soul.SoulMemoryExtensionKeys
import com.opencray.runtime.soul.SoulMemoryObjectTypes
import com.opencray.runtime.soul.SoulProfileExtensionKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSerializationApi::class)
class ChatMemoryIngestionCoordinatorTest {
  @Test
  fun flushBeforeCompactionWritesDurableCandidatesOnlyOncePerTranscriptSignature() {
    val memoryStore = InMemoryMemoryStore()
    val coordinator = ChatMemoryIngestionCoordinator(
      memoryStore = memoryStore,
      workspaceIdProvider = { "workspace-main" },
      candidateExtractor = semanticUserCandidateExtractor(),
    )
    val conversation = listOf(
      conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "Please default to Simplified Chinese for explanations.",
      ),
      conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "Do not use git reset --hard in this repo.",
      ),
      conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "Project uses the Gradle wrapper from the repo root.",
      ),
      conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "以后都用 PowerShell 命令。",
      ),
    ) + (1..12).map { index ->
      conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "Padding user message $index to keep the active transcript window bounded.",
      )
    }

    val first = coordinator.flushBeforeCompaction(
      sessionId = "session-flush",
      taskId = "task-flush-1",
      conversation = conversation,
    )
    val second = coordinator.flushBeforeCompaction(
      sessionId = "session-flush",
      taskId = "task-flush-2",
      conversation = conversation,
    )

    assertEquals(MemoryFlushOutcome.WRITTEN, first.trace.outcome)
    assertTrue(first.writtenRecords.isNotEmpty())
    assertEquals(first.writtenRecords.size, first.trace.writtenRecordCount)
    assertTrue(memoryStore.list().any { record -> record.extensions["kind"] == "user_preference" })
    assertTrue(memoryStore.list().any { record -> record.extensions["kind"] == "project_fact" })
    assertEquals(MemoryFlushOutcome.ALREADY_FLUSHED, second.trace.outcome)
    assertTrue(second.writtenRecords.isEmpty())
    assertEquals(first.writtenRecords.size, memoryStore.list().size)
  }

  @Test
  fun flushBeforeCompactionSkipsExpandedWindowWhenNoNewDurableCandidatesAppear() {
    val memoryStore = InMemoryMemoryStore()
    val coordinator = ChatMemoryIngestionCoordinator(
      memoryStore = memoryStore,
      workspaceIdProvider = { "workspace-main" },
      candidateExtractor = semanticUserCandidateExtractor(),
    )
    val baseConversation = listOf(
      conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "Please default to Simplified Chinese for explanations.",
      ),
      conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "Do not use git reset --hard in this repo.",
      ),
      conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "Project uses the Gradle wrapper from the repo root.",
      ),
      conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "以后都用 PowerShell 命令。",
      ),
    ) + (1..12).map { index ->
      conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "Padding user message $index to keep the active transcript window bounded.",
      )
    }

    val first = coordinator.flushBeforeCompaction(
      sessionId = "session-expanded-flush",
      taskId = "task-expanded-1",
      conversation = baseConversation,
    )
    val second = coordinator.flushBeforeCompaction(
      sessionId = "session-expanded-flush",
      taskId = "task-expanded-2",
      conversation = baseConversation + conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "Padding user message 13 to keep the active transcript window bounded.",
      ),
    )

    assertEquals(MemoryFlushOutcome.WRITTEN, first.trace.outcome)
    assertEquals(MemoryFlushOutcome.ALREADY_FLUSHED, second.trace.outcome)
    assertTrue(second.writtenRecords.isEmpty())
    assertEquals(first.writtenRecords.size, memoryStore.list().size)
  }

  @Test
  fun flushBeforeCompactionWritesOnlyDeltaWhenExpandedWindowAddsNewCandidate() {
    val memoryStore = InMemoryMemoryStore()
    val coordinator = ChatMemoryIngestionCoordinator(
      memoryStore = memoryStore,
      workspaceIdProvider = { "workspace-main" },
      candidateExtractor = semanticUserCandidateExtractor(),
    )
    val baseConversation = listOf(
      conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "Please default to Simplified Chinese for explanations.",
      ),
      conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "Do not use git reset --hard in this repo.",
      ),
      conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "Project uses the Gradle wrapper from the repo root.",
      ),
      conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "以后都用 PowerShell 命令。",
      ),
      conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "Project uses pnpm workspaces for package management.",
      ),
    ) + (1..11).map { index ->
      conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "Padding user message $index to keep the active transcript window bounded.",
      )
    }

    val first = coordinator.flushBeforeCompaction(
      sessionId = "session-expanded-delta",
      taskId = "task-delta-1",
      conversation = baseConversation,
    )
    val second = coordinator.flushBeforeCompaction(
      sessionId = "session-expanded-delta",
      taskId = "task-delta-2",
      conversation = baseConversation + conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "Padding user message 12 to keep the active transcript window bounded.",
      ),
    )

    assertEquals(MemoryFlushOutcome.WRITTEN, first.trace.outcome)
    assertEquals(MemoryFlushOutcome.WRITTEN, second.trace.outcome)
    assertEquals(1, second.writtenRecords.size)
    assertTrue(second.writtenRecords.single().content.contains("pnpm workspaces"))
    assertEquals(first.writtenRecords.size + 1, memoryStore.list().size)
  }

  @Test
  fun flushBeforeCompactionAllowsRewriteAfterBackingMemoryIsCleared() {
    val memoryStore = InMemoryMemoryStore()
    val coordinator = ChatMemoryIngestionCoordinator(
      memoryStore = memoryStore,
      workspaceIdProvider = { "workspace-main" },
      candidateExtractor = semanticUserCandidateExtractor(),
    )
    val conversation = listOf(
      conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "Please default to Simplified Chinese for explanations.",
      ),
      conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "Do not use git reset --hard in this repo.",
      ),
      conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "Project uses the Gradle wrapper from the repo root.",
      ),
      conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "以后都用 PowerShell 命令。",
      ),
    ) + (1..12).map { index ->
      conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "Padding user message $index to keep the active transcript window bounded.",
      )
    }

    val first = coordinator.flushBeforeCompaction(
      sessionId = "session-store-reset",
      taskId = "task-store-reset-1",
      conversation = conversation,
    )
    memoryStore.clear()
    val second = coordinator.flushBeforeCompaction(
      sessionId = "session-store-reset",
      taskId = "task-store-reset-2",
      conversation = conversation,
    )

    assertEquals(MemoryFlushOutcome.WRITTEN, first.trace.outcome)
    assertEquals(MemoryFlushOutcome.WRITTEN, second.trace.outcome)
    assertTrue(second.writtenRecords.isNotEmpty())
    assertEquals(second.writtenRecords.size, memoryStore.list().size)
  }

  @Test
  fun flushBeforeCompactionSkipsWhenTranscriptWindowDoesNotOmitEnoughHistory() {
    val memoryStore = InMemoryMemoryStore()
    val coordinator = ChatMemoryIngestionCoordinator(memoryStore = memoryStore)

    val summary = coordinator.flushBeforeCompaction(
      sessionId = "session-no-pressure",
      taskId = "task-no-pressure",
      conversation = listOf(
        conversationMessage(RuntimeConversationRole.USER, "Please keep responses short."),
        conversationMessage(RuntimeConversationRole.ASSISTANT, "I will keep them short."),
        conversationMessage(RuntimeConversationRole.USER, "Continue with the fix."),
      ),
    )

    assertEquals(MemoryFlushOutcome.NO_PRESSURE, summary.trace.outcome)
    assertTrue(summary.writtenRecords.isEmpty())
    assertTrue(memoryStore.list().isEmpty())
  }

  @Test
  fun ingestCompletedTurnWritesDeterministicCandidatesFromUserAssistantAndTools() {
    val memoryStore = InMemoryMemoryStore()
    val coordinator = ChatMemoryIngestionCoordinator(
      memoryStore = memoryStore,
      workspaceIdProvider = { "workspace-main" },
      candidateExtractor = semanticUserCandidateExtractor(),
    )

    val summary = coordinator.ingestCompletedTurn(
      sessionId = "session-1",
      task = promptTask(
        id = "task-1",
        input = """
          Please default to Simplified Chinese for explanations.
          Do not use git reset --hard in this repo.
        """.trimIndent(),
      ),
      result = successResult(taskId = "task-1"),
      assistantOutput = "Next I will run the targeted runtime tests.",
      toolObservations = listOf("Project uses the Gradle wrapper from the repo root."),
    )

    assertEquals(4, summary.writtenRecords.size)
    assertEquals(4, memoryStore.list().size)
    assertTrue(memoryStore.list().any { record -> record.extensions["kind"] == "user_preference" })
    assertTrue(memoryStore.list().any { record -> record.extensions["kind"] == "durable_instruction" })
    assertTrue(memoryStore.list().any { record -> record.extensions["kind"] == "project_fact" })
    assertTrue(memoryStore.list().any { record -> record.extensions["kind"] == "task_commitment" })
    assertTrue(memoryStore.list().any { record -> record.extensions["workspace_id"] == "workspace-main" })
  }

  @Test
  fun ingestCompletedTurnWritesDurableMemoryThatCanBeRecalledAcrossSessions() {
    val memoryStore = InMemoryMemoryStore()
    val coordinator = ChatMemoryIngestionCoordinator(
      memoryStore = memoryStore,
      workspaceIdProvider = { "workspace-main" },
      candidateExtractor = semanticUserCandidateExtractor(),
      memoryStewardshipService = MemoryStewardshipService(
        clock = { 2_000L },
        interpreter = FixedMemoryStewardshipInterpreter(
          interpretation = MemoryStewardshipInterpretation.Success(decisions = emptyList()),
        ),
        candidateOnlyReviewKinds = setOf(
          MemoryKind.PROJECT_FACT,
          MemoryKind.DURABLE_INSTRUCTION,
        ),
      ),
      writer = MemoryWriter(store = memoryStore, clock = { 2_000L }),
      taskCommitmentResolver = TaskCommitmentResolver(store = memoryStore, clock = { 2_000L }),
    )

    val summary = coordinator.ingestCompletedTurn(
      sessionId = "session-memory-write",
      task = promptTask(
        id = "task-memory-write",
        input = "以后这个项目不要用 git reset --hard，而且这个项目使用 Gradle wrapper。",
      ),
      result = successResult(taskId = "task-memory-write"),
      assistantOutput = "记住了。",
      toolObservations = emptyList(),
    )

    assertEquals(2, summary.writtenRecords.size)
    val recalled = MemoryRetriever(clock = { 2_000L }).retrieve(
      records = memoryStore.list(),
      request = MemoryRecallRequest(
        sessionId = "session-memory-recall",
        workspaceId = "workspace-main",
        userInput = "检查一下 Gradle wrapper，并且不要用 git reset --hard。",
      ),
    )

    assertTrue(
      recalled.memories.any { memory ->
        memory.kind == MemoryKind.DURABLE_INSTRUCTION &&
          memory.content.contains("git reset --hard", ignoreCase = true)
      },
    )
    assertTrue(
      recalled.memories.any { memory ->
        memory.kind == MemoryKind.PROJECT_FACT &&
          memory.content.contains("Gradle wrapper", ignoreCase = true)
      },
    )
  }

  @Test
  fun ingestCompletedTurnCarriesPreferredNameAcrossSessionsAndSupersedesOldValueWhenCorrected() {
    val memoryStore = InMemoryMemoryStore()
    val firstCoordinator = ChatMemoryIngestionCoordinator(
      memoryStore = memoryStore,
      workspaceIdProvider = { "workspace-main" },
      candidateExtractor = semanticUserCandidateExtractor(),
      memoryStewardshipService = MemoryStewardshipService(
        clock = { 2_000L },
        interpreter = FixedMemoryStewardshipInterpreter(
          interpretation = MemoryStewardshipInterpretation.Success(decisions = emptyList()),
        ),
        candidateOnlyReviewKinds = setOf(MemoryKind.USER_PREFERENCE),
      ),
      writer = MemoryWriter(store = memoryStore, clock = { 2_000L }),
      taskCommitmentResolver = TaskCommitmentResolver(store = memoryStore, clock = { 2_000L }),
    )

    val firstSummary = firstCoordinator.ingestCompletedTurn(
      sessionId = "session-name-a",
      task = promptTask(
        id = "task-name-a",
        input = "以后叫我阿澄。",
      ),
      result = successResult(taskId = "task-name-a"),
      assistantOutput = "知道了。",
      toolObservations = emptyList(),
    )

    val initialOverlay = MemoryBackedSoulProfileResolver().overlay(
      baseProfile = RuntimeSoulProfile(
        presetName = "BUILDER",
        voice = "decisive and direct",
      ),
      records = memoryStore.list(),
      sessionId = "session-name-b",
      workspaceId = "workspace-main",
    )
    assertEquals("阿澄", initialOverlay?.extensions?.get(SoulProfileExtensionKeys.PREFERRED_NAMING))

    val oldPreferredNameRecordId = firstSummary.writtenRecords.single { record ->
      record.extensions[MemoryRecordExtensionKeys.PREFERENCE_KEY] == MemoryPreferenceKeys.USER_PREFERRED_NAME
    }.id
    val secondCoordinator = ChatMemoryIngestionCoordinator(
      memoryStore = memoryStore,
      workspaceIdProvider = { "workspace-main" },
      candidateExtractor = semanticUserCandidateExtractor(),
      memoryStewardshipService = MemoryStewardshipService(
        clock = { 3_000L },
        interpreter = object : MemoryStewardshipInterpreter {
          override fun interpret(
            request: MemoryStewardshipRequest,
          ): MemoryStewardshipInterpretation = MemoryStewardshipInterpretation.Success(
            decisions = listOf(
              MemoryStewardshipDecision(
                action = MemoryStewardshipAction.SUPERSEDE_RECORD_WITH_CANDIDATE,
                recordId = oldPreferredNameRecordId,
                candidateIndex = 0,
              ),
            ),
          )
        },
        candidateOnlyReviewKinds = setOf(MemoryKind.USER_PREFERENCE),
        recordOnlyReviewKinds = setOf(MemoryKind.USER_PREFERENCE),
      ),
      writer = MemoryWriter(store = memoryStore, clock = { 3_000L }),
      taskCommitmentResolver = TaskCommitmentResolver(store = memoryStore, clock = { 3_000L }),
    )

    val secondSummary = secondCoordinator.ingestCompletedTurn(
      sessionId = "session-name-b",
      task = promptTask(
        id = "task-name-b",
        input = "别再叫我阿澄了，以后叫我阿青。",
      ),
      result = successResult(taskId = "task-name-b"),
      assistantOutput = "好，以后叫你阿青。",
      toolObservations = emptyList(),
    )

    val activePreferredNameRecords = memoryStore.list().filter { record ->
      record.extensions[MemoryRecordExtensionKeys.STATUS] == "active" &&
        record.extensions[MemoryRecordExtensionKeys.PREFERENCE_KEY] == MemoryPreferenceKeys.USER_PREFERRED_NAME
    }
    assertEquals(1, activePreferredNameRecords.size)
    assertEquals("阿青", activePreferredNameRecords.single().extensions[MemoryRecordExtensionKeys.PREFERENCE_VALUE])
    assertEquals(listOf(oldPreferredNameRecordId), secondSummary.resolvedRecords.map(MemoryRecord::id))
    assertEquals(
      "superseded",
      secondSummary.resolvedRecords.single().extensions[MemoryRecordExtensionKeys.RESOLUTION_REASON],
    )

    val correctedOverlay = MemoryBackedSoulProfileResolver().overlay(
      baseProfile = RuntimeSoulProfile(
        presetName = "BUILDER",
        voice = "decisive and direct",
      ),
      records = memoryStore.list(),
      sessionId = "session-name-c",
      workspaceId = "workspace-main",
    )
    assertEquals("阿青", correctedOverlay?.extensions?.get(SoulProfileExtensionKeys.PREFERRED_NAMING))
  }

  @Test
  fun ingestCompletedTurnSkipsApprovalRequiredDenials() {
    val memoryStore = InMemoryMemoryStore()
    val coordinator = ChatMemoryIngestionCoordinator(
      memoryStore = memoryStore,
      writer = MemoryWriter(store = memoryStore, clock = { 2_000L }),
      taskCommitmentResolver = TaskCommitmentResolver(store = memoryStore, clock = { 2_000L }),
      candidateExtractor = semanticUserCandidateExtractor(),
    )

    val summary = coordinator.ingestCompletedTurn(
      sessionId = "session-1",
      task = promptTask(
        id = "task-approval",
        input = "Please default to PowerShell commands.",
      ),
      result = ExecutionResult(
        taskId = "task-approval",
        status = ExecutionStatus.DENIED,
        errorCode = "APPROVAL_REQUIRED",
        errorMessage = "Approval is required before Write can run.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
      ),
      assistantOutput = "Approval is required before Write can run.",
      toolObservations = listOf("Project uses Gradle."),
    )

    assertTrue(summary.writtenRecords.isEmpty())
    assertTrue(memoryStore.list().isEmpty())
  }

  @Test
  fun ingestCompletedTurnKeepsUserSideWritesButSkipsAssistantCommitmentsOnFailure() {
    val memoryStore = InMemoryMemoryStore()
    val coordinator = ChatMemoryIngestionCoordinator(
      memoryStore = memoryStore,
      writer = MemoryWriter(store = memoryStore, clock = { 2_000L }),
      taskCommitmentResolver = TaskCommitmentResolver(store = memoryStore, clock = { 2_000L }),
      candidateExtractor = semanticUserCandidateExtractor(),
    )

    val summary = coordinator.ingestCompletedTurn(
      sessionId = "session-2",
      task = promptTask(
        id = "task-failure",
        input = "Please default to concise Chinese replies.",
      ),
      result = ExecutionResult(
        taskId = "task-failure",
        status = ExecutionStatus.FAILED,
        errorCode = "HTTP_500",
        errorMessage = "Gateway failed.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
      ),
      assistantOutput = "Next I will retry with the backup route.",
      toolObservations = emptyList(),
    )

    assertEquals(1, summary.writtenRecords.size)
    assertEquals(listOf("user_preference"), memoryStore.list().mapNotNull { record -> record.extensions["kind"] })
  }

  @Test
  fun ingestCompletedTurnResolvesOpenCommitmentWhenLaterEvidenceShowsCompletion() {
    val memoryStore = InMemoryMemoryStore().apply {
      upsert(
        MemoryRecord(
          id = "commitment-1",
          content = "run the targeted runtime tests",
          createdAtEpochMs = 1_000L,
          updatedAtEpochMs = 1_001L,
          tags = listOf("kind:task_commitment", "scope:session", "status:open"),
          extensions = mapOf(
            "kind" to "task_commitment",
            "scope" to "session",
            "status" to "open",
            "source_session_id" to "session-1",
            "ttl_ms" to (14L * 24L * 60L * 60L * 1000L).toString(),
            "last_confirmed_at_epoch_ms" to "1001",
          ),
        ),
      )
    }
    val coordinator = ChatMemoryIngestionCoordinator(
      memoryStore = memoryStore,
      writer = MemoryWriter(store = memoryStore, clock = { 2_000L }),
      taskCommitmentResolver = TaskCommitmentResolver(
        store = memoryStore,
        clock = { 2_000L },
        intentInterpreter = FixedTaskCommitmentIntentInterpreter(
          TaskCommitmentIntentInterpretation.Success(
            decisions = listOf(
              TaskCommitmentIntentDecision(
                commitmentId = "commitment-1",
                action = TaskCommitmentIntentAction.RESOLVE,
              ),
            ),
          ),
        ),
      ),
    )

    val summary = coordinator.ingestCompletedTurn(
      sessionId = "session-1",
      task = promptTask(
        id = "task-2",
        input = "Please continue.",
      ),
      result = successResult(taskId = "task-2"),
      assistantOutput = "I ran the targeted runtime tests and updated the docs.",
      toolObservations = emptyList(),
    )

    assertEquals(listOf("commitment-1"), summary.resolvedRecords.map { record -> record.id })
    assertEquals("resolved", memoryStore.list().single { record -> record.id == "commitment-1" }.extensions["status"])
  }

  @Test
  fun ingestCompletedTurnWritesReplacementCommitmentWhenExistingOneIsSuperseded() {
    val memoryStore = InMemoryMemoryStore().apply {
      upsert(
        MemoryRecord(
          id = "commitment-old",
          content = "run the targeted runtime tests",
          createdAtEpochMs = 1_000L,
          updatedAtEpochMs = 1_001L,
          tags = listOf("kind:task_commitment", "scope:session", "status:open"),
          extensions = mapOf(
            "kind" to "task_commitment",
            "scope" to "session",
            "status" to "open",
            "source_session_id" to "session-1",
            "ttl_ms" to (14L * 24L * 60L * 60L * 1000L).toString(),
            "last_confirmed_at_epoch_ms" to "1001",
          ),
        ),
      )
    }
    val coordinator = ChatMemoryIngestionCoordinator(
      memoryStore = memoryStore,
      writer = MemoryWriter(store = memoryStore, clock = { 2_000L }),
      taskCommitmentResolver = TaskCommitmentResolver(
        store = memoryStore,
        clock = { 2_000L },
        intentInterpreter = FixedTaskCommitmentIntentInterpreter(
          TaskCommitmentIntentInterpretation.Success(
            decisions = listOf(
              TaskCommitmentIntentDecision(
                commitmentId = "commitment-old",
                action = TaskCommitmentIntentAction.SUPERSEDE_WITH_PROPOSED,
                proposedCommitmentIndex = 0,
              ),
            ),
          ),
        ),
      ),
    )

    val summary = coordinator.ingestCompletedTurn(
      sessionId = "session-1",
      task = promptTask(
        id = "task-supersede",
        input = "Please continue.",
      ),
      result = successResult(taskId = "task-supersede"),
      assistantOutput = "Next I will verify the Android smoke tests.",
      toolObservations = emptyList(),
    )

    assertEquals(listOf("commitment-old"), summary.resolvedRecords.map { record -> record.id })
    assertEquals(1, summary.writtenRecords.count { record -> record.extensions["kind"] == "task_commitment" })
    assertTrue(memoryStore.list().any { record ->
      record.content == "verify the Android smoke tests" &&
        record.extensions["kind"] == "task_commitment" &&
        record.extensions["status"] == "open"
    })
    val resolvedRecord = memoryStore.list().single { record -> record.id == "commitment-old" }
    assertEquals("superseded", resolvedRecord.extensions["resolution_reason"])
    assertEquals(
      summary.writtenRecords.single { record -> record.extensions["kind"] == "task_commitment" }.id,
      resolvedRecord.extensions["superseded_by"],
    )
  }

  @Test
  fun ingestCompletedTurnReportsReaffirmedCommitmentWhenSemanticResolverKeepsItAlive() {
    val memoryStore = InMemoryMemoryStore().apply {
      upsert(
        MemoryRecord(
          id = "commitment-reaffirm",
          content = "stabilize the flaky runtime test",
          createdAtEpochMs = 1_000L,
          updatedAtEpochMs = 1_001L,
          tags = listOf("kind:task_commitment", "scope:session", "status:open"),
          extensions = mapOf(
            "kind" to "task_commitment",
            "scope" to "session",
            "status" to "open",
            "source_session_id" to "session-1",
            "ttl_ms" to (14L * 24L * 60L * 60L * 1000L).toString(),
            "last_confirmed_at_epoch_ms" to "1001",
          ),
        ),
      )
    }
    val coordinator = ChatMemoryIngestionCoordinator(
      memoryStore = memoryStore,
      writer = MemoryWriter(store = memoryStore, clock = { 2_000L }),
      taskCommitmentResolver = TaskCommitmentResolver(
        store = memoryStore,
        clock = { 2_000L },
        intentInterpreter = FixedTaskCommitmentIntentInterpreter(
          TaskCommitmentIntentInterpretation.Success(
            decisions = listOf(
              TaskCommitmentIntentDecision(
                commitmentId = "commitment-reaffirm",
                action = TaskCommitmentIntentAction.REAFFIRM,
              ),
            ),
          ),
        ),
      ),
    )

    val summary = coordinator.ingestCompletedTurn(
      sessionId = "session-1",
      task = promptTask(
        id = "task-3",
        input = "Please continue.",
      ),
      result = successResult(taskId = "task-3"),
      assistantOutput = "The flaky runtime test still needs work; I am continuing on it next.",
      toolObservations = emptyList(),
    )

    assertEquals(listOf("commitment-reaffirm"), summary.reaffirmedRecords.map { record -> record.id })
    assertEquals("open", memoryStore.list().single { record -> record.id == "commitment-reaffirm" }.extensions["status"])
  }

  @Test
  fun ingestCompletedTurnDropsDuplicateTaskCommitmentCandidateInsteadOfWritingAnotherRecord() {
    val memoryStore = InMemoryMemoryStore().apply {
      upsert(
        MemoryRecord(
          id = "commitment-existing",
          content = "stabilize the flaky runtime test",
          createdAtEpochMs = 1_000L,
          updatedAtEpochMs = 1_001L,
          tags = listOf("kind:task_commitment", "scope:session", "status:open"),
          extensions = mapOf(
            "kind" to "task_commitment",
            "scope" to "session",
            "status" to "open",
            "source_session_id" to "session-1",
            "ttl_ms" to (14L * 24L * 60L * 60L * 1000L).toString(),
            "last_confirmed_at_epoch_ms" to "1001",
          ),
        ),
      )
    }
    val coordinator = ChatMemoryIngestionCoordinator(
      memoryStore = memoryStore,
      writer = MemoryWriter(store = memoryStore, clock = { 2_000L }),
      taskCommitmentResolver = TaskCommitmentResolver(
        store = memoryStore,
        clock = { 2_000L },
        intentInterpreter = FixedTaskCommitmentIntentInterpreter(
          TaskCommitmentIntentInterpretation.Success(
            decisions = listOf(
              TaskCommitmentIntentDecision(
                proposedCommitmentIndex = 0,
                action = TaskCommitmentIntentAction.DROP_PROPOSED,
              ),
              TaskCommitmentIntentDecision(
                commitmentId = "commitment-existing",
                action = TaskCommitmentIntentAction.REAFFIRM,
              ),
            ),
          ),
        ),
      ),
    )

    val summary = coordinator.ingestCompletedTurn(
      sessionId = "session-1",
      task = promptTask(
        id = "task-drop-duplicate",
        input = "Please continue.",
      ),
      result = successResult(taskId = "task-drop-duplicate"),
      assistantOutput = "Next I will stabilize the flaky runtime test.",
      toolObservations = emptyList(),
    )

    assertTrue(summary.writtenRecords.none { record -> record.extensions["kind"] == "task_commitment" })
    assertEquals(listOf("commitment-existing"), summary.reaffirmedRecords.map { record -> record.id })
    assertEquals(1, memoryStore.list().count { record -> record.extensions["kind"] == "task_commitment" })
    assertEquals("commitment-existing", memoryStore.list().single { record -> record.extensions["kind"] == "task_commitment" }.id)
  }

  @Test
  fun ingestCompletedTurnUsesSemanticSoulInterpreterForMemoryWrites() {
    val memoryStore = InMemoryMemoryStore()
    val coordinator = ChatMemoryIngestionCoordinator(
      memoryStore = memoryStore,
      workspaceIdProvider = { "workspace-main" },
      candidateExtractor = MemoryCandidateExtractor(
        soulIntentInterpreter = FixedSoulIntentInterpreter(
          SoulMemoryIntentInterpretation.Success(
            intents = listOf(
              SoulMemoryIntent(
                preferenceKey = "agent_display_name",
                preferenceValue = "小白",
                scope = com.opencray.runtime.memory.MemoryScope.USER,
                soulExtensions = mapOf(
                  "soul_display_name" to "小白",
                ),
              ),
            ),
          ),
        ),
      ),
    )

    val summary = coordinator.ingestCompletedTurn(
      sessionId = "session-semantic",
      task = promptTask(
        id = "task-semantic",
        input = "以后叫你小白。",
      ),
      result = successResult(taskId = "task-semantic"),
      assistantOutput = "知道了。",
      toolObservations = emptyList(),
    )

    assertEquals(1, summary.writtenRecords.size)
    val record = memoryStore.list().single()
    assertEquals("agent_display_name", record.extensions["preference_key"])
    assertEquals("小白", record.extensions["preference_value"])
    assertEquals("小白", record.extensions["soul_display_name"])
  }

  @Test
  fun ingestCompletedTurnWritesInteractionPreferenceSnapshotFromDurableAdaptiveSignal() {
    val memoryStore = InMemoryMemoryStore()
    val coordinator = ChatMemoryIngestionCoordinator(
      memoryStore = memoryStore,
      workspaceIdProvider = { "workspace-main" },
      soulPlasticityProvider = { SoulPlasticity.HIGH },
      candidateExtractor = MemoryCandidateExtractor(
        soulIntentInterpreter = FixedSoulIntentInterpreter(
          SoulMemoryIntentInterpretation.Success(
            intents = listOf(
              SoulMemoryIntent(
                preferenceKey = "interaction_preference_signal",
                preferenceValue = "adaptive",
                scope = com.opencray.runtime.memory.MemoryScope.USER,
                preferenceExtensions = mapOf(
                  MemoryInteractionPreferenceExtensionKeys.WARMTH_DIRECTION to "higher",
                  MemoryInteractionPreferenceExtensionKeys.FORMALITY_DIRECTION to "lower",
                ),
              ),
            ),
          ),
        ),
      ),
    )

    val summary = coordinator.ingestCompletedTurn(
      sessionId = "session-relationship-style",
      task = promptTask(
        id = "task-relationship-style",
        input = "以后对我温柔一点。",
      ),
      result = successResult(taskId = "task-relationship-style"),
      assistantOutput = "我会注意语气。",
      toolObservations = emptyList(),
    )

    assertEquals(2, summary.writtenRecords.size)
    assertTrue(memoryStore.list().any { record ->
      record.extensions["preference_key"] == "interaction_preference_signal" &&
        record.extensions["preference_value"] == "warmth_higher__formality_lower" &&
        record.extensions[MemoryInteractionPreferenceExtensionKeys.WARMTH_DIRECTION] == "higher" &&
        record.extensions[MemoryInteractionPreferenceExtensionKeys.FORMALITY_DIRECTION] == "lower"
    })
    assertTrue(memoryStore.list().any { record ->
      record.extensions["soul_object_type"] == SoulMemoryObjectTypes.INTERACTION_PREFERENCE_STATE
    })
  }

  @Test
  fun ingestCompletedTurnCanonicalizesDurableStyleRequestsIntoInteractionPreferenceSignal() {
    val memoryStore = InMemoryMemoryStore()
    val coordinator = ChatMemoryIngestionCoordinator(
      memoryStore = memoryStore,
      workspaceIdProvider = { "workspace-main" },
      soulPlasticityProvider = { SoulPlasticity.HIGH },
      candidateExtractor = MemoryCandidateExtractor(
        soulIntentInterpreter = FixedSoulIntentInterpreter(
          SoulMemoryIntentInterpretation.Success(
            intents = listOf(
              SoulMemoryIntent(
                preferenceKey = "agent_style_profile",
                preferenceValue = "warm",
                scope = com.opencray.runtime.memory.MemoryScope.USER,
                soulExtensions = mapOf(
                  "soul_tone" to "warm",
                  "soul_voice" to "warm and gentle",
                  "soul_user_relationship_style" to "supportive",
                ),
                preferenceExtensions = mapOf(
                  MemoryInteractionPreferenceExtensionKeys.WARMTH_DIRECTION to "lower",
                  MemoryInteractionPreferenceExtensionKeys.FORMALITY_DIRECTION to "higher",
                ),
              ),
            ),
          ),
        ),
      ),
    )

    val summary = coordinator.ingestCompletedTurn(
      sessionId = "session-relationship-style-explicit",
      task = promptTask(
        id = "task-relationship-style-explicit",
        input = "以后对我温柔一点。",
      ),
      result = successResult(taskId = "task-relationship-style-explicit"),
      assistantOutput = "我会调整语气。",
      toolObservations = emptyList(),
    )

    assertEquals(2, summary.writtenRecords.size)
    assertTrue(memoryStore.list().any { record ->
      record.extensions["preference_key"] == "interaction_preference_signal" &&
        record.extensions["preference_value"] == "warmth_lower__formality_higher" &&
        record.extensions[MemoryInteractionPreferenceExtensionKeys.WARMTH_DIRECTION] == "lower" &&
        record.extensions[MemoryInteractionPreferenceExtensionKeys.FORMALITY_DIRECTION] == "higher"
    })

    val snapshotRecord = memoryStore.list().single { record ->
      record.extensions["soul_object_type"] == SoulMemoryObjectTypes.INTERACTION_PREFERENCE_STATE
    }
    val snapshotState = Json {
      ignoreUnknownKeys = true
      encodeDefaults = true
      explicitNulls = true
    }.decodeFromString(
      InteractionPreferenceState.serializer(),
      checkNotNull(snapshotRecord.extensions[SoulMemoryExtensionKeys.OBJECT_PAYLOAD_JSON]),
    )

    assertEquals(-1, snapshotState.warmth.offset)
    assertEquals(1, snapshotState.warmth.lowerSupport)
    assertEquals(1, snapshotState.formality.offset)
    assertEquals(1, snapshotState.formality.higherSupport)
  }

  @Test
  fun ingestCompletedTurnProjectsSnapshotFromExplicitInteractionPreferenceSignal() {
    val memoryStore = InMemoryMemoryStore()
    val coordinator = ChatMemoryIngestionCoordinator(
      memoryStore = memoryStore,
      workspaceIdProvider = { "workspace-main" },
      soulPlasticityProvider = { SoulPlasticity.HIGH },
      candidateExtractor = MemoryCandidateExtractor(
        soulIntentInterpreter = FixedSoulIntentInterpreter(
          SoulMemoryIntentInterpretation.Success(
            intents = listOf(
              SoulMemoryIntent(
                preferenceKey = "interaction_preference_signal",
                preferenceValue = "adaptive",
                scope = com.opencray.runtime.memory.MemoryScope.USER,
                preferenceExtensions = mapOf(
                  MemoryInteractionPreferenceExtensionKeys.WARMTH_DIRECTION to "higher",
                  MemoryInteractionPreferenceExtensionKeys.INITIATIVE_DIRECTION to "lower",
                ),
              ),
            ),
          ),
        ),
      ),
    )

    val summary = coordinator.ingestCompletedTurn(
      sessionId = "session-interaction-signal",
      task = promptTask(
        id = "task-interaction-signal",
        input = "以后主动一点，但也温柔一点。",
      ),
      result = successResult(taskId = "task-interaction-signal"),
      assistantOutput = "知道了。",
      toolObservations = emptyList(),
    )

    assertEquals(2, summary.writtenRecords.size)
    assertTrue(memoryStore.list().any { record ->
      record.extensions["preference_key"] == "interaction_preference_signal" &&
        record.extensions["preference_value"] == "warmth_higher__initiative_lower" &&
        record.extensions[MemoryInteractionPreferenceExtensionKeys.WARMTH_DIRECTION] == "higher" &&
        record.extensions[MemoryInteractionPreferenceExtensionKeys.INITIATIVE_DIRECTION] == "lower"
    })

    val snapshotRecord = memoryStore.list().single { record ->
      record.extensions["soul_object_type"] == SoulMemoryObjectTypes.INTERACTION_PREFERENCE_STATE
    }
    val snapshotState = Json {
      ignoreUnknownKeys = true
      encodeDefaults = true
      explicitNulls = true
    }.decodeFromString(
      InteractionPreferenceState.serializer(),
      checkNotNull(snapshotRecord.extensions[SoulMemoryExtensionKeys.OBJECT_PAYLOAD_JSON]),
    )

    assertEquals(1, snapshotState.warmth.offset)
    assertEquals(1, snapshotState.warmth.higherSupport)
    assertEquals(-1, snapshotState.initiative.offset)
    assertEquals(1, snapshotState.initiative.lowerSupport)
  }

  @Test
  fun ingestCompletedTurnWritesInteractionPreferenceSnapshotFromPreferredNamingAndAddressStyle() {
    val memoryStore = InMemoryMemoryStore()
    val coordinator = ChatMemoryIngestionCoordinator(
      memoryStore = memoryStore,
      workspaceIdProvider = { "workspace-main" },
      soulPlasticityProvider = { SoulPlasticity.HIGH },
      candidateExtractor = MemoryCandidateExtractor(
        soulIntentInterpreter = FixedSoulIntentInterpreter(
          SoulMemoryIntentInterpretation.Success(
            intents = listOf(
              SoulMemoryIntent(
                preferenceKey = "user_preferred_name",
                preferenceValue = "阿澄",
                scope = com.opencray.runtime.memory.MemoryScope.USER,
                soulExtensions = mapOf(
                  "soul_preferred_naming" to "阿澄",
                ),
              ),
              SoulMemoryIntent(
                preferenceKey = "user_address_style",
                preferenceValue = "friendly",
                scope = com.opencray.runtime.memory.MemoryScope.USER,
                soulExtensions = mapOf(
                  "soul_preferred_address_style" to "friendly",
                ),
              ),
            ),
          ),
        ),
      ),
    )

    val summary = coordinator.ingestCompletedTurn(
      sessionId = "session-addressing",
      task = promptTask(
        id = "task-addressing",
        input = "以后叫我阿澄，以后称呼我亲切一点。",
      ),
      result = successResult(taskId = "task-addressing"),
      assistantOutput = "记住了。",
      toolObservations = emptyList(),
    )

    assertEquals(3, summary.writtenRecords.size)
    assertTrue(memoryStore.list().any { record ->
      record.extensions["preference_key"] == "user_preferred_name" &&
        record.extensions["preference_value"] == "阿澄"
    })
    assertTrue(memoryStore.list().any { record ->
      record.extensions["preference_key"] == "user_address_style" &&
        record.extensions["preference_value"] == "friendly"
    })
    assertTrue(memoryStore.list().any { record ->
      record.extensions["soul_object_type"] == SoulMemoryObjectTypes.INTERACTION_PREFERENCE_STATE
    })
  }

  @Test
  fun ingestCompletedTurnUsesSemanticUserInterpreterForGeneralDurableMemories() {
    val memoryStore = InMemoryMemoryStore()
    val coordinator = ChatMemoryIngestionCoordinator(
      memoryStore = memoryStore,
      workspaceIdProvider = { "workspace-main" },
      candidateExtractor = MemoryCandidateExtractor(
        userIntentInterpreter = FixedUserIntentInterpreter(
          UserMemoryIntentInterpretation.Success(
            intents = listOf(
              UserMemoryIntent(
                kind = com.opencray.runtime.memory.MemoryKind.USER_PREFERENCE,
                scope = com.opencray.runtime.memory.MemoryScope.USER,
                content = "Default to PowerShell commands",
              ),
              UserMemoryIntent(
                kind = com.opencray.runtime.memory.MemoryKind.DURABLE_INSTRUCTION,
                scope = com.opencray.runtime.memory.MemoryScope.WORKSPACE,
                content = "Do not use git reset --hard in this repo",
              ),
            ),
          ),
        ),
      ),
    )

    val summary = coordinator.ingestCompletedTurn(
      sessionId = "session-user-semantic",
      task = promptTask(
        id = "task-user-semantic",
        input = "以后都用 PowerShell，这个仓库不要用 git reset --hard。",
      ),
      result = successResult(taskId = "task-user-semantic"),
      assistantOutput = "知道了。",
      toolObservations = emptyList(),
    )

    assertEquals(2, summary.writtenRecords.size)
    assertTrue(memoryStore.list().any { record ->
      record.content == "Default to PowerShell commands" &&
        record.extensions["kind"] == "user_preference"
    })
    assertTrue(memoryStore.list().any { record ->
      record.content == "Do not use git reset --hard in this repo" &&
        record.extensions["scope"] == "workspace"
    })
  }

  @Test
  fun ingestCompletedTurnCanSupersedeExistingProjectFactThroughBoundedStewardship() {
    val memoryStore = InMemoryMemoryStore().apply {
      upsert(
        MemoryRecord(
          id = "fact-old",
          content = "Project runs on port 3000",
          createdAtEpochMs = 1_000L,
          updatedAtEpochMs = 1_100L,
          tags = listOf("kind:project_fact", "scope:workspace", "status:active"),
          extensions = mapOf(
            "kind" to "project_fact",
            "scope" to "workspace",
            "status" to "active",
            "source" to "user_input",
            "source_session_id" to "session-old",
            "workspace_id" to "workspace-main",
            "ttl_ms" to (90L * 24L * 60L * 60L * 1000L).toString(),
            "last_confirmed_at_epoch_ms" to "1100",
          ),
        ),
      )
    }
    val coordinator = ChatMemoryIngestionCoordinator(
      memoryStore = memoryStore,
      workspaceIdProvider = { "workspace-main" },
      candidateExtractor = MemoryCandidateExtractor(
        userIntentInterpreter = FixedUserIntentInterpreter(
          interpretation = UserMemoryIntentInterpretation.Success(
            intents = listOf(
              UserMemoryIntent(
                kind = MemoryKind.PROJECT_FACT,
                scope = MemoryScope.WORKSPACE,
                content = "Project runs on port 8000",
              ),
            ),
          ),
        ),
      ),
      memoryStewardshipService = MemoryStewardshipService(
        clock = { 2_000L },
        interpreter = FixedMemoryStewardshipInterpreter(
          interpretation = MemoryStewardshipInterpretation.Success(
            decisions = listOf(
              MemoryStewardshipDecision(
                action = MemoryStewardshipAction.SUPERSEDE_RECORD_WITH_CANDIDATE,
                recordId = "fact-old",
                candidateIndex = 0,
              ),
            ),
          ),
        ),
      ),
      writer = MemoryWriter(store = memoryStore, clock = { 2_000L }),
      taskCommitmentResolver = TaskCommitmentResolver(store = memoryStore, clock = { 2_000L }),
    )

    val summary = coordinator.ingestCompletedTurn(
      sessionId = "session-project-fact",
      task = promptTask(
        id = "task-project-fact",
        input = "记住这个项目现在跑在 8000 端口。",
      ),
      result = successResult(taskId = "task-project-fact"),
      assistantOutput = "知道了。",
      toolObservations = emptyList(),
    )

    assertEquals(listOf("fact-old"), summary.resolvedRecords.map { record -> record.id })
    assertTrue(memoryStore.list().any { record ->
      record.content == "Project runs on port 8000" &&
        record.extensions["status"] == "active"
    })
    assertEquals(
      "superseded",
      memoryStore.list().single { record -> record.id == "fact-old" }.extensions["resolution_reason"],
    )
  }

  @Test
  fun ingestCompletedTurnCanMergeExistingProjectFactThroughBoundedStewardship() {
    val memoryStore = InMemoryMemoryStore().apply {
      upsert(
        MemoryRecord(
          id = "fact-merge-old",
          content = "Project uses Gradle",
          createdAtEpochMs = 1_000L,
          updatedAtEpochMs = 1_100L,
          tags = listOf("kind:project_fact", "scope:workspace", "status:active"),
          extensions = mapOf(
            "kind" to "project_fact",
            "scope" to "workspace",
            "status" to "active",
            "source" to "user_input",
            "source_session_id" to "session-old",
            "workspace_id" to "workspace-main",
            "ttl_ms" to (90L * 24L * 60L * 60L * 1000L).toString(),
            "last_confirmed_at_epoch_ms" to "1100",
          ),
        ),
      )
    }
    val coordinator = ChatMemoryIngestionCoordinator(
      memoryStore = memoryStore,
      workspaceIdProvider = { "workspace-main" },
      candidateExtractor = MemoryCandidateExtractor(
        userIntentInterpreter = FixedUserIntentInterpreter(
          interpretation = UserMemoryIntentInterpretation.Success(
            intents = listOf(
              UserMemoryIntent(
                kind = MemoryKind.PROJECT_FACT,
                scope = MemoryScope.WORKSPACE,
                content = "Use the Gradle wrapper from the repo root",
              ),
            ),
          ),
        ),
      ),
      memoryStewardshipService = MemoryStewardshipService(
        clock = { 2_000L },
        interpreter = FixedMemoryStewardshipInterpreter(
          interpretation = MemoryStewardshipInterpretation.Success(
            decisions = listOf(
              MemoryStewardshipDecision(
                action = MemoryStewardshipAction.MERGE_RECORD_WITH_CANDIDATE,
                recordId = "fact-merge-old",
                candidateIndex = 0,
              ),
            ),
          ),
        ),
      ),
      writer = MemoryWriter(store = memoryStore, clock = { 2_000L }),
      taskCommitmentResolver = TaskCommitmentResolver(store = memoryStore, clock = { 2_000L }),
    )

    val summary = coordinator.ingestCompletedTurn(
      sessionId = "session-project-fact-merge",
      task = promptTask(
        id = "task-project-fact-merge",
        input = "记住这个项目用 Gradle，而且要从仓库根目录走 wrapper。",
      ),
      result = successResult(taskId = "task-project-fact-merge"),
      assistantOutput = "知道了。",
      toolObservations = emptyList(),
    )

    assertEquals(listOf("fact-merge-old"), summary.resolvedRecords.map { record -> record.id })
    assertTrue(memoryStore.list().any { record ->
      record.content == "Project uses Gradle; Use the Gradle wrapper from the repo root" &&
        record.extensions["status"] == "active" &&
        record.extensions["merged_from_record_ids"] == "fact-merge-old"
    })
    assertEquals(
      "merged",
      memoryStore.list().single { record -> record.id == "fact-merge-old" }.extensions["resolution_reason"],
    )
  }

  @Test
  fun ingestCompletedTurnCanRefreshExistingProjectFactThroughBoundedStewardship() {
    val memoryStore = InMemoryMemoryStore().apply {
      upsert(
        MemoryRecord(
          id = "fact-existing",
          content = "Project runs on port 8000",
          createdAtEpochMs = 1_000L,
          updatedAtEpochMs = 1_100L,
          recordVersion = 2L,
          tags = listOf("kind:project_fact", "scope:workspace", "status:active"),
          extensions = mapOf(
            "kind" to "project_fact",
            "scope" to "workspace",
            "status" to "active",
            "source" to "tool_observation",
            "source_session_id" to "session-old",
            "workspace_id" to "workspace-main",
            "ttl_ms" to (90L * 24L * 60L * 60L * 1000L).toString(),
            "last_confirmed_at_epoch_ms" to "1100",
          ),
        ),
      )
    }
    val coordinator = ChatMemoryIngestionCoordinator(
      memoryStore = memoryStore,
      workspaceIdProvider = { "workspace-main" },
      candidateExtractor = MemoryCandidateExtractor(
        userIntentInterpreter = FixedUserIntentInterpreter(
          interpretation = UserMemoryIntentInterpretation.Success(
            intents = listOf(
              UserMemoryIntent(
                kind = MemoryKind.PROJECT_FACT,
                scope = MemoryScope.WORKSPACE,
                content = "Current project port is 8000",
              ),
            ),
          ),
        ),
      ),
      memoryStewardshipService = MemoryStewardshipService(
        clock = { 2_000L },
        interpreter = FixedMemoryStewardshipInterpreter(
          interpretation = MemoryStewardshipInterpretation.Success(
            decisions = listOf(
              MemoryStewardshipDecision(
                action = MemoryStewardshipAction.REFRESH_RECORD_WITH_CANDIDATE,
                recordId = "fact-existing",
                candidateIndex = 0,
              ),
            ),
          ),
        ),
      ),
      writer = MemoryWriter(store = memoryStore, clock = { 2_000L }),
      taskCommitmentResolver = TaskCommitmentResolver(store = memoryStore, clock = { 2_000L }),
    )

    val summary = coordinator.ingestCompletedTurn(
      sessionId = "session-project-fact-refresh",
      task = promptTask(
        id = "task-project-fact-refresh",
        input = "记住一下，现在项目端口还是 8000。",
      ),
      result = successResult(taskId = "task-project-fact-refresh"),
      assistantOutput = "知道了。",
      toolObservations = emptyList(),
    )

    assertTrue(summary.writtenRecords.isEmpty())
    assertEquals(listOf("fact-existing"), summary.reaffirmedRecords.map { record -> record.id })
    assertEquals(1, memoryStore.list().size)
    val stored = memoryStore.list().single()
    assertEquals("fact-existing", stored.id)
    assertEquals("Project runs on port 8000", stored.content)
    assertEquals(3L, stored.recordVersion)
    assertEquals("2000", stored.extensions["last_confirmed_at_epoch_ms"])
  }

  @Test
  fun ingestCompletedTurnCanDropConflictingProjectFactCandidateWithoutExistingRecord() {
    val memoryStore = InMemoryMemoryStore()
    val coordinator = ChatMemoryIngestionCoordinator(
      memoryStore = memoryStore,
      workspaceIdProvider = { "workspace-main" },
      candidateExtractor = MemoryCandidateExtractor(
        userIntentInterpreter = FixedUserIntentInterpreter(
          interpretation = UserMemoryIntentInterpretation.Success(
            intents = listOf(
              UserMemoryIntent(
                kind = MemoryKind.PROJECT_FACT,
                scope = MemoryScope.WORKSPACE,
                content = "Project runs on port 3000",
              ),
              UserMemoryIntent(
                kind = MemoryKind.PROJECT_FACT,
                scope = MemoryScope.WORKSPACE,
                content = "Project runs on port 8000",
              ),
            ),
          ),
        ),
      ),
      memoryStewardshipService = MemoryStewardshipService(
        clock = { 2_000L },
        interpreter = FixedMemoryStewardshipInterpreter(
          interpretation = MemoryStewardshipInterpretation.Success(
            decisions = listOf(
              MemoryStewardshipDecision(
                action = MemoryStewardshipAction.DROP_CANDIDATE,
                candidateIndex = 0,
              ),
            ),
          ),
        ),
      ),
      writer = MemoryWriter(store = memoryStore, clock = { 2_000L }),
      taskCommitmentResolver = TaskCommitmentResolver(store = memoryStore, clock = { 2_000L }),
    )

    val summary = coordinator.ingestCompletedTurn(
      sessionId = "session-project-fact-conflict",
      task = promptTask(
        id = "task-project-fact-conflict",
        input = "记住项目现在跑在 8000 端口，不是 3000。",
      ),
      result = successResult(taskId = "task-project-fact-conflict"),
      assistantOutput = "知道了。",
      toolObservations = emptyList(),
    )

    assertEquals(1, summary.writtenRecords.size)
    assertEquals(listOf("Project runs on port 8000"), memoryStore.list().map(MemoryRecord::content))
  }

  @Test
  fun ingestCompletedTurnCanFailClosedForStewardableCandidatesWhenConfigured() {
    val memoryStore = InMemoryMemoryStore()
    val coordinator = ChatMemoryIngestionCoordinator(
      memoryStore = memoryStore,
      workspaceIdProvider = { "workspace-main" },
      candidateExtractor = semanticUserCandidateExtractor(),
      memoryStewardshipService = MemoryStewardshipService(
        clock = { 2_000L },
        interpreter = FixedMemoryStewardshipInterpreter(
          interpretation = MemoryStewardshipInterpretation.Unavailable(
            reason = "offline",
          ),
        ),
        failClosedOnInterpreterUnavailable = true,
      ),
      writer = MemoryWriter(store = memoryStore, clock = { 2_000L }),
      taskCommitmentResolver = TaskCommitmentResolver(store = memoryStore, clock = { 2_000L }),
    )

    val summary = coordinator.ingestCompletedTurn(
      sessionId = "session-fail-closed-stewardship",
      task = promptTask(
        id = "task-fail-closed-stewardship",
        input = """
          Please default to Simplified Chinese for explanations.
          Do not use git reset --hard in this repo.
        """.trimIndent(),
      ),
      result = successResult(taskId = "task-fail-closed-stewardship"),
      assistantOutput = "Next I will run the targeted runtime tests.",
      toolObservations = listOf("Project uses the Gradle wrapper from the repo root."),
    )

    assertEquals(1, summary.writtenRecords.size)
    assertEquals(listOf("task_commitment"), memoryStore.list().mapNotNull { record -> record.extensions["kind"] })
    assertEquals("run the targeted runtime tests", memoryStore.list().single().content)
  }

  @Test
  fun ingestCompletedTurnCanFailClosedForSingleProjectFactCandidateWhenConfigured() {
    val memoryStore = InMemoryMemoryStore()
    val coordinator = ChatMemoryIngestionCoordinator(
      memoryStore = memoryStore,
      workspaceIdProvider = { "workspace-main" },
      candidateExtractor = MemoryCandidateExtractor(
        userIntentInterpreter = FixedUserIntentInterpreter(
          interpretation = UserMemoryIntentInterpretation.Success(
            intents = listOf(
              UserMemoryIntent(
                kind = MemoryKind.PROJECT_FACT,
                scope = MemoryScope.WORKSPACE,
                content = "Project runs on port 8000",
              ),
            ),
          ),
        ),
      ),
      memoryStewardshipService = MemoryStewardshipService(
        clock = { 2_000L },
        interpreter = FixedMemoryStewardshipInterpreter(
          interpretation = MemoryStewardshipInterpretation.Unavailable(
            reason = "offline",
          ),
        ),
        failClosedOnInterpreterUnavailable = true,
        candidateOnlyReviewKinds = setOf(MemoryKind.PROJECT_FACT),
      ),
      writer = MemoryWriter(store = memoryStore, clock = { 2_000L }),
      taskCommitmentResolver = TaskCommitmentResolver(store = memoryStore, clock = { 2_000L }),
    )

    val summary = coordinator.ingestCompletedTurn(
      sessionId = "session-single-project-fact-fail-closed",
      task = promptTask(
        id = "task-single-project-fact-fail-closed",
        input = "记住这个项目现在跑在 8000 端口。",
      ),
      result = successResult(taskId = "task-single-project-fact-fail-closed"),
      assistantOutput = "知道了。",
      toolObservations = emptyList(),
    )

    assertTrue(summary.writtenRecords.isEmpty())
    assertTrue(memoryStore.list().isEmpty())
  }

  @Test
  fun ingestCompletedTurnCanFailClosedForSingleDurableInstructionCandidateWhenConfigured() {
    val memoryStore = InMemoryMemoryStore()
    val coordinator = ChatMemoryIngestionCoordinator(
      memoryStore = memoryStore,
      workspaceIdProvider = { "workspace-main" },
      candidateExtractor = MemoryCandidateExtractor(
        userIntentInterpreter = FixedUserIntentInterpreter(
          interpretation = UserMemoryIntentInterpretation.Success(
            intents = listOf(
              UserMemoryIntent(
                kind = MemoryKind.DURABLE_INSTRUCTION,
                scope = MemoryScope.WORKSPACE,
                content = "Do not use git reset --hard in this repo",
              ),
            ),
          ),
        ),
      ),
      memoryStewardshipService = MemoryStewardshipService(
        clock = { 2_000L },
        interpreter = FixedMemoryStewardshipInterpreter(
          interpretation = MemoryStewardshipInterpretation.Unavailable(
            reason = "offline",
          ),
        ),
        failClosedOnInterpreterUnavailable = true,
        candidateOnlyReviewKinds = setOf(MemoryKind.DURABLE_INSTRUCTION),
      ),
      writer = MemoryWriter(store = memoryStore, clock = { 2_000L }),
      taskCommitmentResolver = TaskCommitmentResolver(store = memoryStore, clock = { 2_000L }),
    )

    val summary = coordinator.ingestCompletedTurn(
      sessionId = "session-single-durable-instruction-fail-closed",
      task = promptTask(
        id = "task-single-durable-instruction-fail-closed",
        input = "记住这个仓库不要用 git reset --hard。",
      ),
      result = successResult(taskId = "task-single-durable-instruction-fail-closed"),
      assistantOutput = "知道了。",
      toolObservations = emptyList(),
    )

    assertTrue(summary.writtenRecords.isEmpty())
    assertTrue(memoryStore.list().isEmpty())
  }

  @Test
  fun ingestCompletedTurnCanFailClosedForSingleUserPreferenceCandidateWhenConfigured() {
    val memoryStore = InMemoryMemoryStore()
    val coordinator = ChatMemoryIngestionCoordinator(
      memoryStore = memoryStore,
      workspaceIdProvider = { "workspace-main" },
      candidateExtractor = MemoryCandidateExtractor(
        userIntentInterpreter = FixedUserIntentInterpreter(
          interpretation = UserMemoryIntentInterpretation.Success(
            intents = listOf(
              UserMemoryIntent(
                kind = MemoryKind.USER_PREFERENCE,
                scope = MemoryScope.USER,
                preferenceKey = MemoryPreferenceKeys.USER_PREFERRED_NAME,
                preferenceValue = "阿澄",
              ),
            ),
          ),
        ),
      ),
      memoryStewardshipService = MemoryStewardshipService(
        clock = { 2_000L },
        interpreter = FixedMemoryStewardshipInterpreter(
          interpretation = MemoryStewardshipInterpretation.Unavailable(
            reason = "offline",
          ),
        ),
        failClosedOnInterpreterUnavailable = true,
        candidateOnlyReviewKinds = setOf(MemoryKind.USER_PREFERENCE),
      ),
      writer = MemoryWriter(store = memoryStore, clock = { 2_000L }),
      taskCommitmentResolver = TaskCommitmentResolver(store = memoryStore, clock = { 2_000L }),
    )

    val summary = coordinator.ingestCompletedTurn(
      sessionId = "session-single-user-preference-fail-closed",
      task = promptTask(
        id = "task-single-user-preference-fail-closed",
        input = "以后叫我阿澄。",
      ),
      result = successResult(taskId = "task-single-user-preference-fail-closed"),
      assistantOutput = "知道了。",
      toolObservations = emptyList(),
    )

    assertTrue(summary.writtenRecords.isEmpty())
    assertTrue(memoryStore.list().isEmpty())
  }

  @Test
  fun ingestCompletedTurnCanResolveRecordOnlyMemoryWhileKeepingTaskCommitmentWrite() {
    val memoryStore = InMemoryMemoryStore().apply {
      upsert(
        MemoryRecord(
          id = "pref-old",
          content = "Preferred user naming is 阿澄",
          createdAtEpochMs = 1_000L,
          updatedAtEpochMs = 1_100L,
          tags = listOf("kind:user_preference", "scope:user", "status:active"),
          extensions = mapOf(
            "kind" to "user_preference",
            "scope" to "user",
            "status" to "active",
            "source" to "user_input",
            "source_session_id" to "session-old",
            "preference_key" to MemoryPreferenceKeys.USER_PREFERRED_NAME,
            "preference_value" to "阿澄",
            "last_confirmed_at_epoch_ms" to "1100",
          ),
        ),
      )
    }
    val coordinator = ChatMemoryIngestionCoordinator(
      memoryStore = memoryStore,
      workspaceIdProvider = { "workspace-main" },
      candidateExtractor = MemoryCandidateExtractor(
        userIntentInterpreter = FixedUserIntentInterpreter(
          interpretation = UserMemoryIntentInterpretation.Success(intents = emptyList()),
        ),
      ),
      memoryStewardshipService = MemoryStewardshipService(
        clock = { 2_000L },
        interpreter = FixedMemoryStewardshipInterpreter(
          interpretation = MemoryStewardshipInterpretation.Success(
            decisions = listOf(
              MemoryStewardshipDecision(
                action = MemoryStewardshipAction.RESOLVE_RECORD,
                recordId = "pref-old",
                resolutionReason = MemoryStewardshipResolutionReason.INVALIDATED,
              ),
            ),
          ),
        ),
        recordOnlyReviewKinds = setOf(MemoryKind.USER_PREFERENCE),
      ),
      writer = MemoryWriter(store = memoryStore, clock = { 2_000L }),
      taskCommitmentResolver = TaskCommitmentResolver(store = memoryStore, clock = { 2_000L }),
    )

    val summary = coordinator.ingestCompletedTurn(
      sessionId = "session-record-only-resolve",
      task = promptTask(
        id = "task-record-only-resolve",
        input = "以后不要再叫我阿澄了。",
      ),
      result = successResult(taskId = "task-record-only-resolve"),
      assistantOutput = "Next I will run the targeted runtime tests.",
      toolObservations = emptyList(),
    )

    assertEquals(listOf("pref-old"), summary.resolvedRecords.map { record -> record.id })
    assertEquals(1, summary.writtenRecords.count { record -> record.extensions["kind"] == "task_commitment" })
    assertEquals("resolved", memoryStore.list().single { record -> record.id == "pref-old" }.extensions["status"])
    assertTrue(memoryStore.list().any { record ->
      record.extensions["kind"] == "task_commitment" &&
        record.content == "run the targeted runtime tests"
    })
  }

  @Test
  fun ingestCompletedTurnRewritesInteractionPreferenceSnapshotWhenPreferredNameIsInvalidatedWithoutReplacement() {
    val staleSnapshotPayload = Json {
      ignoreUnknownKeys = true
      encodeDefaults = true
      explicitNulls = true
    }.encodeToString(
      InteractionPreferenceState.serializer(),
      InteractionPreferenceState(
        preferredNaming = "阿澄",
        preferredNamingSupport = 1,
        lastUpdatedAtEpochMs = 1_200L,
      ),
    )
    val memoryStore = InMemoryMemoryStore().apply {
      upsert(
        MemoryRecord(
          id = "pref-old",
          content = "Preferred user naming is 阿澄",
          createdAtEpochMs = 1_000L,
          updatedAtEpochMs = 1_100L,
          tags = listOf("kind:user_preference", "scope:user", "status:active"),
          extensions = mapOf(
            "kind" to "user_preference",
            "scope" to "user",
            "status" to "active",
            "source" to "user_input",
            "source_session_id" to "session-old",
            "preference_key" to MemoryPreferenceKeys.USER_PREFERRED_NAME,
            "preference_value" to "阿澄",
            "last_confirmed_at_epoch_ms" to "1100",
          ),
        ),
      )
      upsert(
        MemoryRecord(
          id = "interaction-user-snapshot",
          content = "Internal interaction_preference_state snapshot for user scope",
          createdAtEpochMs = 1_050L,
          updatedAtEpochMs = 1_200L,
          tags = listOf("kind:project_fact", "scope:user", "status:active"),
          extensions = mapOf(
            "kind" to "project_fact",
            "scope" to "user",
            "status" to "active",
            "source" to "assistant_output",
            "source_session_id" to "session-old",
            "last_confirmed_at_epoch_ms" to "1200",
            SoulMemoryExtensionKeys.OBJECT_TYPE to SoulMemoryObjectTypes.INTERACTION_PREFERENCE_STATE,
            SoulMemoryExtensionKeys.OBJECT_SCHEMA_VERSION to "1",
            SoulMemoryExtensionKeys.OBJECT_PAYLOAD_JSON to staleSnapshotPayload,
          ),
        ),
      )
    }
    val coordinator = ChatMemoryIngestionCoordinator(
      memoryStore = memoryStore,
      workspaceIdProvider = { "workspace-main" },
      candidateExtractor = MemoryCandidateExtractor(
        userIntentInterpreter = FixedUserIntentInterpreter(
          interpretation = UserMemoryIntentInterpretation.Success(intents = emptyList()),
        ),
      ),
      memoryStewardshipService = MemoryStewardshipService(
        clock = { 2_000L },
        interpreter = FixedMemoryStewardshipInterpreter(
          interpretation = MemoryStewardshipInterpretation.Success(
            decisions = listOf(
              MemoryStewardshipDecision(
                action = MemoryStewardshipAction.RESOLVE_RECORD,
                recordId = "pref-old",
                resolutionReason = MemoryStewardshipResolutionReason.INVALIDATED,
              ),
            ),
          ),
        ),
        recordOnlyReviewKinds = setOf(MemoryKind.USER_PREFERENCE),
      ),
      writer = MemoryWriter(store = memoryStore, clock = { 2_000L }),
      taskCommitmentResolver = TaskCommitmentResolver(store = memoryStore, clock = { 2_000L }),
    )

    val summary = coordinator.ingestCompletedTurn(
      sessionId = "session-record-only-resolve-snapshot",
      task = promptTask(
        id = "task-record-only-resolve-snapshot",
        input = "以后不要再叫我阿澄了。",
      ),
      result = successResult(taskId = "task-record-only-resolve-snapshot"),
      assistantOutput = "知道了，不再这么叫你。",
      toolObservations = emptyList(),
    )

    assertEquals(listOf("pref-old"), summary.resolvedRecords.map { record -> record.id })
    assertEquals("resolved", memoryStore.list().single { record -> record.id == "pref-old" }.extensions["status"])
    val latestSnapshotRecord = memoryStore.list()
      .filter { record ->
        record.extensions[SoulMemoryExtensionKeys.OBJECT_TYPE] ==
          SoulMemoryObjectTypes.INTERACTION_PREFERENCE_STATE
      }
      .maxByOrNull(MemoryRecord::updatedAtEpochMs)
    assertTrue("Expected an updated interaction-preference snapshot to be written.", latestSnapshotRecord != null)
    val snapshotState = Json {
      ignoreUnknownKeys = true
      encodeDefaults = true
      explicitNulls = true
    }.decodeFromString(
      InteractionPreferenceState.serializer(),
      checkNotNull(latestSnapshotRecord!!.extensions[SoulMemoryExtensionKeys.OBJECT_PAYLOAD_JSON]),
    )
    assertTrue(snapshotState.preferredNaming == null)
    assertEquals(0, snapshotState.preferredNamingSupport)

    val overlay = MemoryBackedSoulProfileResolver().overlay(
      baseProfile = RuntimeSoulProfile(
        presetName = "BUILDER",
        voice = "decisive and direct",
      ),
      records = memoryStore.list(),
      sessionId = "session-record-only-resolve-snapshot-followup",
      workspaceId = "workspace-main",
    )
    assertTrue(overlay?.extensions?.get(SoulProfileExtensionKeys.PREFERRED_NAMING) == null)
  }

  @Test
  fun ingestCompletedTurnCanCombineRecordOnlyResolveWithProjectFactCandidateWrite() {
    val memoryStore = InMemoryMemoryStore().apply {
      upsert(
        MemoryRecord(
          id = "pref-old",
          content = "Preferred user naming is 阿澄",
          createdAtEpochMs = 1_000L,
          updatedAtEpochMs = 1_100L,
          tags = listOf("kind:user_preference", "scope:user", "status:active"),
          extensions = mapOf(
            "kind" to "user_preference",
            "scope" to "user",
            "status" to "active",
            "source" to "user_input",
            "source_session_id" to "session-old",
            "preference_key" to MemoryPreferenceKeys.USER_PREFERRED_NAME,
            "preference_value" to "阿澄",
            "last_confirmed_at_epoch_ms" to "1100",
          ),
        ),
      )
    }
    val coordinator = ChatMemoryIngestionCoordinator(
      memoryStore = memoryStore,
      workspaceIdProvider = { "workspace-main" },
      candidateExtractor = MemoryCandidateExtractor(
        userIntentInterpreter = FixedUserIntentInterpreter(
          interpretation = UserMemoryIntentInterpretation.Success(
            intents = listOf(
              UserMemoryIntent(
                kind = MemoryKind.PROJECT_FACT,
                scope = MemoryScope.WORKSPACE,
                content = "Project runs on port 8000",
              ),
            ),
          ),
        ),
      ),
      memoryStewardshipService = MemoryStewardshipService(
        clock = { 2_000L },
        interpreter = FixedMemoryStewardshipInterpreter(
          interpretation = MemoryStewardshipInterpretation.Success(
            decisions = listOf(
              MemoryStewardshipDecision(
                action = MemoryStewardshipAction.RESOLVE_RECORD,
                recordId = "pref-old",
                resolutionReason = MemoryStewardshipResolutionReason.INVALIDATED,
              ),
            ),
          ),
        ),
        recordOnlyReviewKinds = setOf(MemoryKind.USER_PREFERENCE),
      ),
      writer = MemoryWriter(store = memoryStore, clock = { 2_000L }),
      taskCommitmentResolver = TaskCommitmentResolver(store = memoryStore, clock = { 2_000L }),
    )

    val summary = coordinator.ingestCompletedTurn(
      sessionId = "session-record-only-plus-candidate",
      task = promptTask(
        id = "task-record-only-plus-candidate",
        input = "以后不要再叫我阿澄了。记住项目现在跑在 8000 端口。",
      ),
      result = successResult(taskId = "task-record-only-plus-candidate"),
      assistantOutput = "知道了。",
      toolObservations = emptyList(),
    )

    assertEquals(listOf("pref-old"), summary.resolvedRecords.map { record -> record.id })
    assertTrue(summary.writtenRecords.any { record ->
      record.extensions["kind"] == "project_fact" &&
        record.content == "Project runs on port 8000"
    })
    assertEquals("resolved", memoryStore.list().single { record -> record.id == "pref-old" }.extensions["status"])
  }

  @Test
  fun ingestCompletedTurnWritesRelationshipEventsAndSnapshotThroughPlanner() {
    val memoryStore = InMemoryMemoryStore()
    val coordinator = ChatMemoryIngestionCoordinator(
      memoryStore = memoryStore,
      relationshipEventInterpreter = FixedRelationshipEventInterpreter(
        RelationshipEventInterpretation.Success(
          events = listOf(
            RelationshipEvent(
              eventType = RelationshipEventType.SUPPORTIVE_RESPONSE,
              valence = RelationshipEventValence.POSITIVE,
              confidence = RelationshipEventConfidence.MEDIUM,
              scope = RelationshipEventScope.USER,
              summary = "Supportive response after stress.",
              occurredAtEpochMs = 2_000L,
            ),
          ),
        ),
      ),
    )

    val summary = coordinator.ingestCompletedTurn(
      sessionId = "session-relationship",
      task = promptTask(
        id = "task-relationship",
        input = "Please continue.",
      ),
      result = successResult(taskId = "task-relationship"),
      assistantOutput = "I am here.",
      toolObservations = emptyList(),
    )

    assertEquals(2, summary.writtenRecords.size)
    assertTrue(memoryStore.list().any { record ->
      record.extensions["soul_object_type"] == SoulMemoryObjectTypes.RELATIONSHIP_EVENT
    })
    assertTrue(memoryStore.list().any { record ->
      record.extensions["soul_object_type"] == SoulMemoryObjectTypes.RELATIONSHIP_STATE
    })
  }

  private fun promptTask(id: String, input: String): AgentTask = AgentTask(
    id = id,
    type = AgentTaskType.PROMPT,
    input = input,
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "TEST_ALLOW",
    ),
    createdAtEpochMs = 1_000L,
  )

  private fun successResult(taskId: String): ExecutionResult = ExecutionResult(
    taskId = taskId,
    status = ExecutionStatus.SUCCESS,
    stdout = "Done.",
    startedAtEpochMs = 1_000L,
    finishedAtEpochMs = 1_001L,
  )

  private fun conversationMessage(
    role: RuntimeConversationRole,
    content: String,
  ): RuntimeConversationMessage = RuntimeConversationMessage(
    role = role,
    content = content,
  )

  private fun semanticUserCandidateExtractor(): MemoryCandidateExtractor =
    MemoryCandidateExtractor(
      userIntentInterpreter = object : UserMemoryIntentInterpreter {
        override fun interpret(
          request: UserMemoryIntentRequest,
        ): UserMemoryIntentInterpretation {
          val input = request.userInput
          val intents = buildList {
            if (input.contains("Simplified Chinese", ignoreCase = true)) {
              add(
                UserMemoryIntent(
                  kind = MemoryKind.USER_PREFERENCE,
                  scope = MemoryScope.USER,
                  content = "Default to Simplified Chinese for explanations",
                ),
              )
            }
            if (input.contains("PowerShell", ignoreCase = true)) {
              add(
                UserMemoryIntent(
                  kind = MemoryKind.USER_PREFERENCE,
                  scope = MemoryScope.USER,
                  content = "Default to PowerShell commands",
                ),
              )
            }
            if (input.contains("concise Chinese replies", ignoreCase = true)) {
              add(
                UserMemoryIntent(
                  kind = MemoryKind.USER_PREFERENCE,
                  scope = MemoryScope.USER,
                  content = "Default to concise Chinese replies",
                ),
              )
            }
            if (input.contains("git reset --hard", ignoreCase = true)) {
              add(
                UserMemoryIntent(
                  kind = MemoryKind.DURABLE_INSTRUCTION,
                  scope = MemoryScope.WORKSPACE,
                  content = "Do not use git reset --hard in this repo",
                ),
              )
            }
            if (input.contains("Gradle wrapper", ignoreCase = true)) {
              add(
                UserMemoryIntent(
                  kind = MemoryKind.PROJECT_FACT,
                  scope = MemoryScope.WORKSPACE,
                  content = "Project uses the Gradle wrapper from the repo root",
                ),
              )
            }
            if (input.contains("pnpm workspaces", ignoreCase = true)) {
              add(
                UserMemoryIntent(
                  kind = MemoryKind.PROJECT_FACT,
                  scope = MemoryScope.WORKSPACE,
                  content = "Project uses pnpm workspaces for package management",
                ),
              )
            }
            val preferredName = Regex("以后叫我([^。！，!?\\s]+)")
              .find(input)
              ?.groupValues
              ?.getOrNull(1)
              ?: Regex("叫我([^。！，!?\\s]+)")
                .findAll(input)
                .lastOrNull()
                ?.groupValues
                ?.getOrNull(1)
            if (!preferredName.isNullOrBlank()) {
              add(
                UserMemoryIntent(
                  kind = MemoryKind.USER_PREFERENCE,
                  scope = MemoryScope.USER,
                  preferenceKey = MemoryPreferenceKeys.USER_PREFERRED_NAME,
                  preferenceValue = preferredName,
                ),
              )
            }
          }
          return UserMemoryIntentInterpretation.Success(intents = intents)
        }
      },
    )

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

  private class FixedSoulIntentInterpreter(
    private val interpretation: SoulMemoryIntentInterpretation,
  ) : SoulMemoryIntentInterpreter {
    override fun interpret(
      request: SoulMemoryIntentRequest,
    ): SoulMemoryIntentInterpretation = interpretation
  }

  private class FixedUserIntentInterpreter(
    private val interpretation: UserMemoryIntentInterpretation,
  ) : UserMemoryIntentInterpreter {
    override fun interpret(
      request: UserMemoryIntentRequest,
    ): UserMemoryIntentInterpretation = interpretation
  }

  private class FixedTaskCommitmentIntentInterpreter(
    private val interpretation: TaskCommitmentIntentInterpretation,
  ) : TaskCommitmentIntentInterpreter {
    override fun interpret(
      request: TaskCommitmentIntentRequest,
    ): TaskCommitmentIntentInterpretation = interpretation
  }

  private class FixedRelationshipEventInterpreter(
    private val interpretation: RelationshipEventInterpretation,
  ) : RelationshipEventInterpreter {
    override fun interpret(
      request: RelationshipEventRequest,
    ): RelationshipEventInterpretation = interpretation
  }

  private class FixedMemoryStewardshipInterpreter(
    private val interpretation: MemoryStewardshipInterpretation,
  ) : MemoryStewardshipInterpreter {
    override fun interpret(
      request: MemoryStewardshipRequest,
    ): MemoryStewardshipInterpretation = interpretation
  }
}
