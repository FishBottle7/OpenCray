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
import com.opencray.runtime.memory.MemoryInteractionPreferenceExtensionKeys
import com.opencray.runtime.memory.MemoryCandidateExtractor
import com.opencray.runtime.memory.MemoryKind
import com.opencray.runtime.memory.MemoryPreferenceKeys
import com.opencray.runtime.memory.MemoryRecallFilterReason
import com.opencray.runtime.memory.MemoryRecallRequest
import com.opencray.runtime.memory.MemoryRecordExtensionKeys
import com.opencray.runtime.memory.MemoryRetriever
import com.opencray.runtime.memory.MemoryStewardshipService
import com.opencray.runtime.soul.InteractionPreferenceState
import com.opencray.runtime.soul.MemoryBackedSoulProfileResolver
import com.opencray.runtime.soul.SoulMemoryExtensionKeys
import com.opencray.runtime.soul.SoulMemoryObjectTypes
import com.opencray.runtime.soul.SoulProfileExtensionKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSerializationApi::class)
class LiveCrossSessionMemoryFlowSmokeTest {
  @Test
  fun ingestWritesDurableGenericMemoryThatIsRecalledInAnotherSession() {
    val store = InMemoryMemoryStore()
    val components = liveComponents(store)
    val extractedCandidates = components.candidateExtractor.extract(
      memoryEvidence(
        sessionId = "session-memory-write",
        taskId = "task-memory-write",
        userInput = "以后这个项目不要用 git reset --hard，而且这个项目使用 Gradle wrapper。",
        assistantOutput = "记住了。",
      ),
    )
    val coordinator = components.coordinator

    val summary = coordinator.ingestCompletedTurn(
      sessionId = "session-memory-write",
      task = promptTask(
        id = "task-memory-write",
        input = "以后这个项目不要用 git reset --hard，而且这个项目使用 Gradle wrapper。",
      ),
      result = successResult("task-memory-write"),
      userInput = "以后这个项目不要用 git reset --hard，而且这个项目使用 Gradle wrapper。",
      assistantOutput = "记住了。",
      toolObservations = emptyList(),
    )

    assertTrue(
      "Expected live ingestion to write durable memory, extracted=$extractedCandidates written=${summary.writtenRecords}",
      summary.writtenRecords.isNotEmpty(),
    )
    val storedRecords = store.list()
    assertTrue(storedRecords.any { record ->
      record.extensions[MemoryRecordExtensionKeys.KIND] == "durable_instruction" &&
        record.content.contains("git reset --hard", ignoreCase = true)
    })
    assertTrue(storedRecords.any { record ->
      record.extensions[MemoryRecordExtensionKeys.KIND] == "project_fact" &&
        record.content.contains("Gradle wrapper", ignoreCase = true)
    })

    val recalled = MemoryRetriever().retrieve(
      records = storedRecords,
      request = MemoryRecallRequest(
        sessionId = "session-memory-recall",
        workspaceId = "workspace-main",
        userInput = "检查一下 Gradle wrapper，并且不要用 git reset --hard。",
      ),
    )

    assertTrue(
      "Expected cross-session recall to include the durable instruction, got ${recalled.memories}",
      recalled.memories.any { memory ->
        memory.kind == MemoryKind.DURABLE_INSTRUCTION &&
          memory.content.contains("git reset --hard", ignoreCase = true)
      },
    )
    assertTrue(
      "Expected cross-session recall to include the project fact, got ${recalled.memories}",
      recalled.memories.any { memory ->
        memory.kind == MemoryKind.PROJECT_FACT &&
          memory.content.contains("Gradle wrapper", ignoreCase = true)
      },
    )
  }

  @Test
  fun ingestCarriesPreferredNameAcrossSessionsAndSupersedesOldValueWhenUserCorrectsIt() {
    val store = InMemoryMemoryStore()
    val components = liveComponents(store)
    val firstExtractedCandidates = components.candidateExtractor.extract(
      memoryEvidence(
        sessionId = "session-name-a",
        taskId = "task-name-a",
        userInput = "以后叫我阿澄。",
        assistantOutput = "知道了。",
      ),
    )
    val coordinator = components.coordinator

    val firstSummary = coordinator.ingestCompletedTurn(
      sessionId = "session-name-a",
      task = promptTask(
        id = "task-name-a",
        input = "以后叫我阿澄。",
      ),
      result = successResult("task-name-a"),
      userInput = "以后叫我阿澄。",
      assistantOutput = "知道了。",
      toolObservations = emptyList(),
    )
    assertTrue(
      "Expected first preferred-name turn to write memory, extracted=$firstExtractedCandidates written=${firstSummary.writtenRecords}",
      firstSummary.writtenRecords.isNotEmpty(),
    )

    val sessionBOverlay = MemoryBackedSoulProfileResolver().overlay(
      baseProfile = RuntimeSoulProfile(
        presetName = "BUILDER",
        voice = "decisive and direct",
      ),
      records = store.list(),
      sessionId = "session-name-b",
      workspaceId = "workspace-main",
    )
    assertEquals("阿澄", sessionBOverlay?.extensions?.get(SoulProfileExtensionKeys.PREFERRED_NAMING))

    val secondSummary = coordinator.ingestCompletedTurn(
      sessionId = "session-name-b",
      task = promptTask(
        id = "task-name-b",
        input = "别再叫我阿澄了，以后叫我阿青。",
      ),
      result = successResult("task-name-b"),
      userInput = "别再叫我阿澄了，以后叫我阿青。",
      assistantOutput = "好，以后叫你阿青。",
      toolObservations = emptyList(),
    )

    val activePreferredName = store.list().firstOrNull { record ->
      record.extensions[MemoryRecordExtensionKeys.STATUS] == "active" &&
        record.extensions[MemoryRecordExtensionKeys.PREFERENCE_KEY] == MemoryPreferenceKeys.USER_PREFERRED_NAME
    }
    assertNotNull("Expected an active preferred-name memory after the correction.", activePreferredName)
    assertEquals("阿青", activePreferredName!!.extensions[MemoryRecordExtensionKeys.PREFERENCE_VALUE])
    assertTrue(
      "Expected the old preferred-name record to be resolved, got ${secondSummary.resolvedRecords}",
      secondSummary.resolvedRecords.any { record ->
        record.extensions[MemoryRecordExtensionKeys.PREFERENCE_KEY] == MemoryPreferenceKeys.USER_PREFERRED_NAME &&
          record.extensions[MemoryRecordExtensionKeys.RESOLUTION_REASON] in setOf("superseded", "invalidated")
      },
    )

    val sessionCOverlay = MemoryBackedSoulProfileResolver().overlay(
      baseProfile = RuntimeSoulProfile(
        presetName = "BUILDER",
        voice = "decisive and direct",
      ),
      records = store.list(),
      sessionId = "session-name-c",
      workspaceId = "workspace-main",
    )
    assertEquals("阿青", sessionCOverlay?.extensions?.get(SoulProfileExtensionKeys.PREFERRED_NAMING))
  }

  @Test
  fun ingestProjectsDurableAdaptivePreferenceIntoCrossSessionSoulOverlay() {
    val store = InMemoryMemoryStore()
    val components = liveComponents(store)
    val extractedCandidates = components.candidateExtractor.extract(
      memoryEvidence(
        sessionId = "session-reassurance-a",
        taskId = "task-reassurance-a",
        userInput = "以后可以多安慰我一点。",
        assistantOutput = "我会更注意安抚你的情绪。",
      ),
    )
    val summary = components.coordinator.ingestCompletedTurn(
      sessionId = "session-reassurance-a",
      task = promptTask(
        id = "task-reassurance-a",
        input = "以后可以多安慰我一点。",
      ),
      result = successResult("task-reassurance-a"),
      userInput = "以后可以多安慰我一点。",
      assistantOutput = "我会更注意安抚你的情绪。",
      toolObservations = emptyList(),
    )

    val adaptiveSignal = store.list().firstOrNull { record ->
      record.extensions[MemoryRecordExtensionKeys.STATUS] == "active" &&
        record.extensions[MemoryRecordExtensionKeys.PREFERENCE_KEY] ==
        MemoryPreferenceKeys.INTERACTION_PREFERENCE_SIGNAL
    }
    assertNotNull(
      "Expected a durable adaptive preference signal, extracted=$extractedCandidates written=${summary.writtenRecords}",
      adaptiveSignal,
    )
    assertEquals(
      "higher",
      adaptiveSignal!!.extensions[MemoryInteractionPreferenceExtensionKeys.REASSURANCE_DIRECTION],
    )

    val snapshotRecord = store.list().firstOrNull { record ->
      record.extensions[SoulMemoryExtensionKeys.OBJECT_TYPE] ==
        SoulMemoryObjectTypes.INTERACTION_PREFERENCE_STATE
    }
    assertNotNull("Expected an interaction-preference snapshot record to be written.", snapshotRecord)
    val snapshotState = Json {
      ignoreUnknownKeys = true
      encodeDefaults = true
      explicitNulls = true
    }.decodeFromString(
      InteractionPreferenceState.serializer(),
      checkNotNull(snapshotRecord!!.extensions[SoulMemoryExtensionKeys.OBJECT_PAYLOAD_JSON]),
    )
    assertTrue(
      "Expected reassurance support to increase after the durable reassurance request, got ${snapshotState.reassurance}",
      snapshotState.reassurance.higherSupport > 0,
    )

    val sessionBOverlay = MemoryBackedSoulProfileResolver().overlay(
      baseProfile = RuntimeSoulProfile(
        presetName = "BUILDER",
        voice = "decisive and direct",
      ),
      records = store.list(),
      sessionId = "session-reassurance-b",
      workspaceId = "workspace-main",
    )
    val reassuranceOffset = sessionBOverlay
      ?.extensions
      ?.get(SoulProfileExtensionKeys.REASSURANCE_PREFERENCE_OFFSET)
      ?.toIntOrNull()
    assertNotNull("Expected cross-session soul overlay to expose a reassurance preference offset.", reassuranceOffset)
    assertEquals(
      "Expected cross-session soul overlay to reflect the persisted reassurance offset.",
      snapshotState.reassurance.offset,
      reassuranceOffset,
    )
  }

  @Test
  fun ingestConvergesProjectFactAcrossReaffirmationCorrectionAndCrossSessionRecall() {
    val store = InMemoryMemoryStore()
    val components = liveComponents(store)
    val coordinator = components.coordinator

    val firstSummary = coordinator.ingestCompletedTurn(
      sessionId = "session-port-a",
      task = promptTask(
        id = "task-port-a",
        input = "记住这个项目现在跑在 3000 端口。",
      ),
      result = successResult("task-port-a"),
      userInput = "记住这个项目现在跑在 3000 端口。",
      assistantOutput = "知道了。",
      toolObservations = emptyList(),
    )
    assertTrue(
      "Expected the first project-port turn to write memory, written=${firstSummary.writtenRecords}",
      firstSummary.writtenRecords.isNotEmpty(),
    )
    val firstActivePortFact = singleActiveProjectFact(store)
    assertTrue(
      "Expected the first active project fact to mention port 3000, got ${firstActivePortFact.content}",
      firstActivePortFact.content.contains("3000"),
    )

    val secondSummary = coordinator.ingestCompletedTurn(
      sessionId = "session-port-b",
      task = promptTask(
        id = "task-port-b",
        input = "记住一下，现在项目端口还是 3000。",
      ),
      result = successResult("task-port-b"),
      userInput = "记住一下，现在项目端口还是 3000。",
      assistantOutput = "知道了，还是 3000。",
      toolObservations = emptyList(),
    )
    val reaffirmedPortFact = singleActiveProjectFact(store)
    assertEquals(
      "Expected reaffirmation to keep the original active project-fact record instead of writing a duplicate.",
      firstActivePortFact.id,
      reaffirmedPortFact.id,
    )
    assertTrue(
      "Expected the second turn to reaffirm the existing project-fact record, got ${secondSummary.reaffirmedRecords}",
      secondSummary.reaffirmedRecords.any { record -> record.id == firstActivePortFact.id },
    )
    assertEquals(
      "Expected there to still be exactly one active project-fact record after reaffirmation.",
      1,
      activeProjectFactRecords(store).size,
    )

    val thirdSummary = coordinator.ingestCompletedTurn(
      sessionId = "session-port-c",
      task = promptTask(
        id = "task-port-c",
        input = "记住项目现在跑在 8000 端口，不是 3000。",
      ),
      result = successResult("task-port-c"),
      userInput = "记住项目现在跑在 8000 端口，不是 3000。",
      assistantOutput = "知道了，现在是 8000。",
      toolObservations = emptyList(),
    )

    assertTrue(
      "Expected the old port fact to be resolved after the correction, got ${thirdSummary.resolvedRecords}",
      thirdSummary.resolvedRecords.any { record ->
        record.id == firstActivePortFact.id &&
          record.extensions[MemoryRecordExtensionKeys.RESOLUTION_REASON] in setOf("superseded", "invalidated")
      },
    )
    val correctedPortFact = singleActiveProjectFact(store)
    assertTrue(
      "Expected the corrected active project fact to mention port 8000, got ${correctedPortFact.content}",
      correctedPortFact.content.contains("8000"),
    )

    val recalled = MemoryRetriever().retrieve(
      records = store.list(),
      request = MemoryRecallRequest(
        sessionId = "session-port-recall",
        workspaceId = "workspace-main",
        userInput = "check whether the project still runs on port 8000",
      ),
    )
    assertTrue(
      "Expected cross-session recall to expose the corrected port fact, got ${recalled.memories}",
      recalled.memories.any { memory ->
        memory.kind == MemoryKind.PROJECT_FACT &&
          memory.id == correctedPortFact.id
      },
    )
    assertEquals(
      "Expected resolved records to be filtered out of recall rather than returned as live project facts.",
      firstActivePortFact.id,
      thirdSummary.resolvedRecords.single { record -> record.id == firstActivePortFact.id }.id,
    )
    assertTrue(
      "Expected the recall trace to report at least one resolved record being filtered, got ${recalled.trace.filteredCounts}",
      (recalled.trace.filteredCounts[MemoryRecallFilterReason.RESOLVED] ?: 0) >= 1,
    )
  }

  @Test
  fun ingestRemovesPreferredNameAcrossSessionsAfterExplicitInvalidation() {
    val store = InMemoryMemoryStore()
    val components = liveComponents(store)
    val coordinator = components.coordinator

    val firstSummary = coordinator.ingestCompletedTurn(
      sessionId = "session-mixed-a",
      task = promptTask(
        id = "task-mixed-a",
        input = "以后叫我阿澄。",
      ),
      result = successResult("task-mixed-a"),
      userInput = "以后叫我阿澄。",
      assistantOutput = "知道了。",
      toolObservations = emptyList(),
    )
    assertTrue(
      "Expected the first preferred-name turn to write memory, written=${firstSummary.writtenRecords}",
      firstSummary.writtenRecords.isNotEmpty(),
    )
    val initialOverlay = MemoryBackedSoulProfileResolver().overlay(
      baseProfile = RuntimeSoulProfile(
        presetName = "BUILDER",
        voice = "decisive and direct",
      ),
      records = store.list(),
      sessionId = "session-mixed-b",
      workspaceId = "workspace-main",
    )
    assertEquals("阿澄", initialOverlay?.extensions?.get(SoulProfileExtensionKeys.PREFERRED_NAMING))

    val secondSummary = coordinator.ingestCompletedTurn(
      sessionId = "session-mixed-b",
      task = promptTask(
        id = "task-mixed-b",
        input = "以后不要再叫我阿澄了。",
      ),
      result = successResult("task-mixed-b"),
      userInput = "以后不要再叫我阿澄了。",
      assistantOutput = "知道了，不再这么叫你。",
      toolObservations = emptyList(),
    )

    assertTrue(
      "Expected the invalidation turn to either resolve or at least stop exposing the preferred name, summary=${secondSummary.resolvedRecords} store=${store.list()}",
      secondSummary.resolvedRecords.any { record ->
        record.extensions[MemoryRecordExtensionKeys.PREFERENCE_KEY] == MemoryPreferenceKeys.USER_PREFERRED_NAME &&
          record.extensions[MemoryRecordExtensionKeys.RESOLUTION_REASON] in setOf("superseded", "invalidated", "obsolete")
      } || activePreferredNameRecords(store).isEmpty(),
    )
    assertTrue(
      "Expected there to be no active preferred-name record after explicit invalidation, got ${store.list()}",
      activePreferredNameRecords(store).isEmpty(),
    )

    val followupOverlay = MemoryBackedSoulProfileResolver().overlay(
      baseProfile = RuntimeSoulProfile(
        presetName = "BUILDER",
        voice = "decisive and direct",
      ),
      records = store.list(),
      sessionId = "session-mixed-c",
      workspaceId = "workspace-main",
    )
    assertNull(
      "Expected the cross-session soul overlay to stop exposing the invalidated preferred name.",
      followupOverlay?.extensions?.get(SoulProfileExtensionKeys.PREFERRED_NAMING),
    )
  }

  private fun liveComponents(
    store: MemoryStore,
  ): LiveCoordinatorComponents {
    val config = requireConfigOrSkip()
    val providerClient = OpenAiCompatibleLiteLlmProviderClient()
    val candidateExtractor = MemoryCandidateExtractor(
      userIntentInterpreter = LiteLlmUserMemoryIntentInterpreter(
        llmSettingsProvider = { config.toSettingsState() },
        providerClient = providerClient,
      ),
      soulIntentInterpreter = LiteLlmSoulMemoryIntentInterpreter(
        llmSettingsProvider = { config.toSettingsState() },
        providerClient = providerClient,
      ),
    )
    val coordinator = ChatMemoryIngestionCoordinator(
      memoryStore = store,
      workspaceIdProvider = { "workspace-main" },
      candidateExtractor = candidateExtractor,
      memoryStewardshipService = MemoryStewardshipService(
        interpreter = LiteLlmMemoryStewardshipInterpreter(
          llmSettingsProvider = { config.toSettingsState() },
          providerClient = providerClient,
        ),
        failClosedOnInterpreterUnavailable = true,
        candidateOnlyReviewKinds = setOf(
          MemoryKind.USER_PREFERENCE,
          MemoryKind.PROJECT_FACT,
          MemoryKind.DURABLE_INSTRUCTION,
        ),
        recordOnlyReviewKinds = setOf(
          MemoryKind.USER_PREFERENCE,
          MemoryKind.PROJECT_FACT,
          MemoryKind.DURABLE_INSTRUCTION,
        ),
      ),
    )
    return LiveCoordinatorComponents(
      coordinator = coordinator,
      candidateExtractor = candidateExtractor,
    )
  }

  private fun requireConfigOrSkip(): LocalLiveLlmTestConfig {
    val config = LocalLiveLlmTestConfig.load()
    assumeTrue(
      "Missing local live LLM test config. Create ${LocalLiveLlmTestConfig.defaultConfigPath()} first.",
      config != null,
    )
    return config!!
  }

  private fun promptTask(
    id: String,
    input: String,
  ): AgentTask = AgentTask(
    id = id,
    type = AgentTaskType.PROMPT,
    input = input,
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "LIVE_TEST_ALLOW",
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

  private fun memoryEvidence(
    sessionId: String,
    taskId: String,
    userInput: String,
    assistantOutput: String,
  ) = com.opencray.runtime.memory.MemoryTurnEvidence(
    sessionId = sessionId,
    taskId = taskId,
    workspaceId = "workspace-main",
    userInput = userInput,
    assistantOutput = assistantOutput,
  )

  private data class LiveCoordinatorComponents(
    val coordinator: ChatMemoryIngestionCoordinator,
    val candidateExtractor: MemoryCandidateExtractor,
  )

  private fun activeProjectFactRecords(
    store: InMemoryMemoryStore,
  ): List<MemoryRecord> = store.list().filter { record ->
    record.extensions[MemoryRecordExtensionKeys.STATUS] == "active" &&
      record.extensions[MemoryRecordExtensionKeys.KIND] == "project_fact"
  }

  private fun singleActiveProjectFact(
    store: InMemoryMemoryStore,
  ): MemoryRecord {
    val activeProjectFacts = activeProjectFactRecords(store)
    assertEquals(
      "Expected exactly one active project-fact record, got $activeProjectFacts",
      1,
      activeProjectFacts.size,
    )
    return activeProjectFacts.single()
  }

  private fun activePreferredNameRecords(
    store: InMemoryMemoryStore,
  ): List<MemoryRecord> = store.list().filter { record ->
    record.extensions[MemoryRecordExtensionKeys.STATUS] == "active" &&
      record.extensions[MemoryRecordExtensionKeys.PREFERENCE_KEY] == MemoryPreferenceKeys.USER_PREFERRED_NAME
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
