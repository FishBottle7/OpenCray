package com.opencray.app

import com.opencray.persistence.model.MemoryRecord
import com.opencray.runtime.memory.MemoryCandidate
import com.opencray.runtime.memory.MemoryEvidenceSource
import com.opencray.runtime.memory.MemoryKind
import com.opencray.runtime.memory.MemoryPolicy
import com.opencray.runtime.memory.MemoryPreferenceKeys
import com.opencray.runtime.memory.MemoryRecordExtensionKeys
import com.opencray.runtime.memory.MemoryScope
import com.opencray.runtime.memory.MemoryStatus
import com.opencray.runtime.memory.MemoryStewardshipService
import com.opencray.runtime.memory.MemoryTurnEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LiveMemoryStewardshipServiceSmokeTest {
  @Test
  fun planRefreshesEquivalentProjectFactInsteadOfKeepingDuplicateCandidate() {
    val candidate = workspaceProjectFactCandidate(
      content = "Project runs on port 8000",
    )
    val existing = workspaceProjectFactRecord(
      id = "fact-8000",
      content = "Project runs on port 8000",
    )

    val plan = liveService().plan(
      existingRecords = listOf(existing),
      evidence = turnEvidence(
        userInput = "对，还是 8000，项目端口没变。",
      ),
      proposedCandidates = listOf(candidate),
    )

    assertTrue(plan.acceptedCandidates.isEmpty())
    assertEquals(listOf(candidate), plan.droppedCandidates)
    assertEquals(listOf(existing.id), plan.reaffirmedRecords.map(MemoryRecord::id))
    assertEquals(
      "5000",
      plan.reaffirmedRecords.single().extensions[MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS],
    )
    assertTrue(plan.resolvedRecords.isEmpty())
  }

  @Test
  fun planDropsClearlyTemporaryPreferenceCandidate() {
    val candidate = sessionVerbosityCandidate(
      verbosity = "terse",
      content = "Reply briefly for this turn only.",
    )

    val plan = liveService().plan(
      existingRecords = emptyList(),
      evidence = turnEvidence(
        userInput = "这次就简短一点，不用记住，下次恢复正常。",
      ),
      proposedCandidates = listOf(candidate),
    )

    assertTrue(plan.acceptedCandidates.isEmpty())
    assertEquals(listOf(candidate), plan.droppedCandidates)
    assertTrue(plan.resolvedRecords.isEmpty())
    assertTrue(plan.reaffirmedRecords.isEmpty())
  }

  @Test
  fun planResolvesOldPreferenceWhileAcceptingSeparateNewFactInSameTurn() {
    val oldPreferredName = userPreferredNameRecord(
      id = "pref-old-name",
      preferredName = "阿澄",
    )
    val newFact = workspaceProjectFactCandidate(
      content = "Project runs on port 8000",
    )

    val plan = liveService().plan(
      existingRecords = listOf(oldPreferredName),
      evidence = turnEvidence(
        userInput = "以后不要再叫我阿澄了，这个项目跑在 8000 端口，不是 3000。",
      ),
      proposedCandidates = listOf(newFact),
    )

    assertEquals(listOf(newFact), plan.acceptedCandidates)
    assertTrue(plan.droppedCandidates.isEmpty())
    assertEquals(listOf(oldPreferredName.id), plan.resolvedRecords.map(MemoryRecord::id))
    assertTrue(plan.reaffirmedRecords.isEmpty())
    assertEquals(
      "5000",
      plan.resolvedRecords.single().extensions[MemoryRecordExtensionKeys.RESOLVED_AT_EPOCH_MS],
    )
  }

  @Test
  fun planReplacesOldPreferredNameWithNewPreferredName() {
    val oldPreferredName = userPreferredNameRecord(
      id = "pref-old-name",
      preferredName = "阿澄",
    )
    val newPreferredName = userPreferredNameCandidate(
      preferredName = "阿青",
      scope = MemoryScope.USER,
    )

    val plan = liveService().plan(
      existingRecords = listOf(oldPreferredName),
      evidence = turnEvidence(
        userInput = "别再叫我阿澄了，以后叫我阿青。",
      ),
      proposedCandidates = listOf(newPreferredName),
    )

    assertEquals(listOf(newPreferredName), plan.acceptedCandidates)
    assertTrue(plan.droppedCandidates.isEmpty())
    assertEquals(listOf(oldPreferredName.id), plan.resolvedRecords.map(MemoryRecord::id))
    val resolutionReason = plan.resolvedRecords.single().extensions[MemoryRecordExtensionKeys.RESOLUTION_REASON]
    assertTrue(
      "Expected a replacement-style resolution reason, got '$resolutionReason'",
      resolutionReason == "superseded" || resolutionReason == "invalidated",
    )
  }

  private fun liveService(): MemoryStewardshipService {
    val config = requireConfigOrSkip()
    return MemoryStewardshipService(
      policy = MemoryPolicy(),
      clock = { 5_000L },
      interpreter = LiteLlmMemoryStewardshipInterpreter(
        llmSettingsProvider = { config.toSettingsState() },
        providerClient = OpenAiCompatibleLiteLlmProviderClient(),
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
    )
  }

  private fun requireConfigOrSkip(): LocalLiveLlmTestConfig {
    assumeTrue(
      "Live LLM smoke tests are disabled. Set ${LocalLiveLlmTestConfig.ENABLED_PROPERTY}=true or ${LocalLiveLlmTestConfig.ENABLED_ENV}=true to run them.",
      LocalLiveLlmTestConfig.isLiveTestExecutionEnabled(),
    )
    val config = LocalLiveLlmTestConfig.load()
    assumeTrue(
      "Missing local live LLM test config. Create ${LocalLiveLlmTestConfig.defaultConfigPath()} first.",
      config != null,
    )
    return config!!
  }

  private fun turnEvidence(
    userInput: String,
  ): MemoryTurnEvidence = MemoryTurnEvidence(
    sessionId = "live-memory-stewardship-service",
    workspaceId = "workspace-main",
    userInput = userInput,
  )

  private fun workspaceProjectFactCandidate(
    content: String,
  ): MemoryCandidate = MemoryCandidate(
    kind = MemoryKind.PROJECT_FACT,
    scope = MemoryScope.WORKSPACE,
    status = MemoryStatus.ACTIVE,
    content = content,
    source = MemoryEvidenceSource.USER_INPUT,
    sourceSessionId = "live-memory-stewardship-service",
    workspaceId = "workspace-main",
    ttlMs = MemoryPolicy().ttlMsFor(MemoryKind.PROJECT_FACT),
  )

  private fun userPreferredNameCandidate(
    preferredName: String,
    scope: MemoryScope,
  ): MemoryCandidate = MemoryCandidate(
    kind = MemoryKind.USER_PREFERENCE,
    scope = scope,
    status = MemoryStatus.ACTIVE,
    content = "Preferred user naming is $preferredName",
    source = MemoryEvidenceSource.USER_INPUT,
    sourceSessionId = "live-memory-stewardship-service",
    extensions = linkedMapOf(
      MemoryRecordExtensionKeys.PREFERENCE_KEY to MemoryPreferenceKeys.USER_PREFERRED_NAME,
      MemoryRecordExtensionKeys.PREFERENCE_VALUE to preferredName,
      MemoryRecordExtensionKeys.PREFERENCE_TEMPORALITY to preferenceTemporalityFor(scope),
    ),
  )

  private fun sessionVerbosityCandidate(
    verbosity: String,
    content: String,
  ): MemoryCandidate = MemoryCandidate(
    kind = MemoryKind.USER_PREFERENCE,
    scope = MemoryScope.SESSION,
    status = MemoryStatus.ACTIVE,
    content = content,
    source = MemoryEvidenceSource.USER_INPUT,
    sourceSessionId = "live-memory-stewardship-service",
    extensions = linkedMapOf(
      MemoryRecordExtensionKeys.PREFERENCE_KEY to MemoryPreferenceKeys.AGENT_VERBOSITY,
      MemoryRecordExtensionKeys.PREFERENCE_VALUE to verbosity,
      MemoryRecordExtensionKeys.PREFERENCE_TEMPORALITY to MemoryPreferenceKeys.TEMPORALITY_SESSION,
    ),
  )

  private fun workspaceProjectFactRecord(
    id: String,
    content: String,
  ): MemoryRecord = baseRecord(
    id = id,
    content = content,
    tags = listOf("kind:project_fact", "scope:workspace", "status:active"),
    extensions = linkedMapOf(
      MemoryRecordExtensionKeys.KIND to "project_fact",
      MemoryRecordExtensionKeys.SCOPE to "workspace",
      MemoryRecordExtensionKeys.STATUS to "active",
      MemoryRecordExtensionKeys.SOURCE to "user_input",
      MemoryRecordExtensionKeys.SOURCE_SESSION_ID to "session-0",
      MemoryRecordExtensionKeys.WORKSPACE_ID to "workspace-main",
      MemoryRecordExtensionKeys.TTL_MS to (90L * 24L * 60L * 60L * 1000L).toString(),
      MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS to "1000",
    ),
  )

  private fun userPreferredNameRecord(
    id: String,
    preferredName: String,
  ): MemoryRecord = baseRecord(
    id = id,
    content = "Preferred user naming is $preferredName",
    tags = listOf("kind:user_preference", "scope:user", "status:active"),
    extensions = linkedMapOf(
      MemoryRecordExtensionKeys.KIND to "user_preference",
      MemoryRecordExtensionKeys.SCOPE to "user",
      MemoryRecordExtensionKeys.STATUS to "active",
      MemoryRecordExtensionKeys.SOURCE to "user_input",
      MemoryRecordExtensionKeys.SOURCE_SESSION_ID to "session-0",
      MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS to "1000",
      MemoryRecordExtensionKeys.PREFERENCE_KEY to MemoryPreferenceKeys.USER_PREFERRED_NAME,
      MemoryRecordExtensionKeys.PREFERENCE_VALUE to preferredName,
      MemoryRecordExtensionKeys.PREFERENCE_TEMPORALITY to MemoryPreferenceKeys.TEMPORALITY_DURABLE,
    ),
  )

  private fun baseRecord(
    id: String,
    content: String,
    tags: List<String>,
    extensions: Map<String, String>,
  ): MemoryRecord = MemoryRecord(
    id = id,
    content = content,
    tags = tags,
    createdAtEpochMs = 1_000L,
    updatedAtEpochMs = 2_000L,
    extensions = extensions,
  )

  private fun preferenceTemporalityFor(scope: MemoryScope): String = when (scope) {
    MemoryScope.SESSION -> MemoryPreferenceKeys.TEMPORALITY_SESSION
    MemoryScope.USER,
    MemoryScope.WORKSPACE,
    -> MemoryPreferenceKeys.TEMPORALITY_DURABLE
  }
}
