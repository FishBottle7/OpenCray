package com.opencray.runtime.soul

import com.opencray.persistence.model.MemoryRecord
import com.opencray.runtime.memory.MemoryCandidate
import com.opencray.runtime.memory.MemoryEvidenceSource
import com.opencray.runtime.memory.MemoryInteractionPreferenceExtensionKeys
import com.opencray.runtime.memory.MemoryKind
import com.opencray.runtime.memory.MemoryPreferenceKeys
import com.opencray.runtime.memory.MemoryRecordExtensionKeys
import com.opencray.runtime.memory.MemoryScope
import com.opencray.runtime.memory.MemoryStatus
import com.opencray.runtime.memory.stableMemoryRecordId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractionPreferenceMemoryWritePlannerTest {
  private val planner = InteractionPreferenceMemoryWritePlanner(clock = { 2_000L })

  @Test
  fun planCreatesUpdatedUserSnapshotDirectlyFromInteractionPreferenceCandidates() {
    val plan = planner.plan(
      existingRecords = listOf(
        interactionPreferenceStateRecord(
          id = "existing-user-snapshot",
          scope = MemoryScope.USER,
          state = InteractionPreferenceState(
            warmth = PreferenceAxisState(offset = 1, higherSupport = 1),
            formality = PreferenceAxisState(offset = -1, lowerSupport = 1),
          ),
          updatedAtEpochMs = 1_000L,
        ),
      ),
      sourceCandidates = listOf(
        interactionPreferenceSignalCandidate(
          scope = MemoryScope.USER,
          preferenceValue = "warmth_higher__formality_lower",
          preferenceExtensions = mapOf(
            MemoryInteractionPreferenceExtensionKeys.WARMTH_DIRECTION to "higher",
            MemoryInteractionPreferenceExtensionKeys.FORMALITY_DIRECTION to "lower",
          ),
        ),
      ),
      plasticity = SoulPlasticity.HIGH,
      sourceSessionId = "session-main",
    )

    assertEquals(1, plan.stateSnapshotCandidates.size)
    val snapshotState = interactionPreferenceStateFrom(plan.stateSnapshotCandidates.single())
    assertEquals(2, snapshotState.warmth.offset)
    assertEquals(-2, snapshotState.formality.offset)
  }

  @Test
  fun planCreatesUpdatedUserSnapshotFromInteractionPreferenceSignals() {
    val plan = planner.plan(
      existingRecords = listOf(
        interactionPreferenceStateRecord(
          id = "existing-user-snapshot",
          scope = MemoryScope.USER,
          state = InteractionPreferenceState(
            warmth = PreferenceAxisState(offset = 1, higherSupport = 1),
            formality = PreferenceAxisState(offset = -1, lowerSupport = 1),
          ),
          updatedAtEpochMs = 1_000L,
        ),
      ),
      sourceRecords = listOf(
        interactionPreferenceSignalRecord(
          id = "interaction-warm",
          scope = MemoryScope.USER,
          preferenceValue = "warmth_higher__formality_lower",
          updatedAtEpochMs = 2_000L,
          recordVersion = 1L,
          extraExtensions = mapOf(
            MemoryInteractionPreferenceExtensionKeys.WARMTH_DIRECTION to "higher",
            MemoryInteractionPreferenceExtensionKeys.FORMALITY_DIRECTION to "lower",
          ),
        ),
      ),
      plasticity = SoulPlasticity.HIGH,
      sourceSessionId = "session-main",
    )

    assertEquals(1, plan.stateSnapshotCandidates.size)
    val snapshotState = interactionPreferenceStateFrom(plan.stateSnapshotCandidates.single())
    assertEquals(2, snapshotState.warmth.offset)
    assertEquals(-2, snapshotState.formality.offset)
  }

  @Test
  fun planSkipsWorkspaceSignalsWhenWorkspaceIdIsMissing() {
    val plan = planner.plan(
      existingRecords = emptyList(),
      sourceRecords = listOf(
        interactionPreferenceSignalRecord(
          id = "interaction-workspace",
          scope = MemoryScope.WORKSPACE,
          preferenceValue = "warmth_higher__formality_lower",
          updatedAtEpochMs = 2_000L,
          extraExtensions = mapOf(
            MemoryInteractionPreferenceExtensionKeys.WARMTH_DIRECTION to "higher",
            MemoryInteractionPreferenceExtensionKeys.FORMALITY_DIRECTION to "lower",
          ),
        ),
      ),
      plasticity = SoulPlasticity.MEDIUM,
      sourceSessionId = "session-main",
    )

    assertTrue(plan.stateSnapshotCandidates.isEmpty())
  }

  @Test
  fun planProjectsPreferredNamingAndAddressStyleIntoSnapshot() {
    val plan = planner.plan(
      existingRecords = emptyList(),
      sourceRecords = listOf(
        preferenceRecord(
          id = "preferred-name",
          scope = MemoryScope.USER,
          preferenceKey = MemoryPreferenceKeys.USER_PREFERRED_NAME,
          preferenceValue = "阿澄",
          updatedAtEpochMs = 2_000L,
        ),
        preferenceRecord(
          id = "address-style",
          scope = MemoryScope.USER,
          preferenceKey = MemoryPreferenceKeys.USER_ADDRESS_STYLE,
          preferenceValue = "friendly",
          updatedAtEpochMs = 2_100L,
          recordVersion = 2L,
        ),
      ),
      plasticity = SoulPlasticity.MEDIUM,
      sourceSessionId = "session-main",
    )

    assertEquals(1, plan.stateSnapshotCandidates.size)
    val snapshotState = interactionPreferenceStateFrom(plan.stateSnapshotCandidates.single())
    assertEquals("阿澄", snapshotState.preferredNaming)
    assertEquals(1, snapshotState.preferredNamingSupport)
    assertEquals(PreferredAddressStyle.FRIENDLY, snapshotState.addressStyle.selectedStyle)
    assertEquals(2, snapshotState.addressStyle.friendlySupport)
  }

  @Test
  fun planPredictsNextSupportWeightFromExistingMatchingPreferenceWhenUsingCandidates() {
    val incomingCandidate = interactionPreferenceSignalCandidate(
      scope = MemoryScope.USER,
      preferenceValue = "warmth_higher__formality_lower",
      preferenceExtensions = mapOf(
        MemoryInteractionPreferenceExtensionKeys.WARMTH_DIRECTION to "higher",
        MemoryInteractionPreferenceExtensionKeys.FORMALITY_DIRECTION to "lower",
      ),
    )
    val plan = planner.plan(
      existingRecords = listOf(
        interactionPreferenceSignalRecord(
          id = stableMemoryRecordId(incomingCandidate),
          scope = MemoryScope.USER,
          preferenceValue = "warmth_higher__formality_lower",
          updatedAtEpochMs = 1_500L,
          recordVersion = 2L,
          extraExtensions = mapOf(
            MemoryInteractionPreferenceExtensionKeys.WARMTH_DIRECTION to "higher",
            MemoryInteractionPreferenceExtensionKeys.FORMALITY_DIRECTION to "lower",
          ),
        ),
      ),
      sourceCandidates = listOf(incomingCandidate),
      plasticity = SoulPlasticity.HIGH,
      sourceSessionId = "session-main",
    )

    val snapshotState = interactionPreferenceStateFrom(plan.stateSnapshotCandidates.single())
    assertEquals(2, snapshotState.warmth.offset)
    assertEquals(3, snapshotState.warmth.higherSupport)
    assertEquals(3, snapshotState.formality.lowerSupport)
  }

  @Test
  fun planProjectsInteractionPreferenceSignalsUsingTypedExtensionsOnly() {
    val plan = planner.plan(
      existingRecords = emptyList(),
      sourceRecords = listOf(
        interactionPreferenceSignalRecord(
          id = "interaction-conflicted",
          scope = MemoryScope.USER,
          preferenceValue = "adaptive",
          updatedAtEpochMs = 2_000L,
          extraExtensions = mapOf(
            MemoryInteractionPreferenceExtensionKeys.WARMTH_DIRECTION to "lower",
            MemoryInteractionPreferenceExtensionKeys.FORMALITY_DIRECTION to "higher",
          ),
        ),
      ),
      plasticity = SoulPlasticity.HIGH,
      sourceSessionId = "session-main",
    )

    val snapshotState = interactionPreferenceStateFrom(plan.stateSnapshotCandidates.single())
    assertEquals(-1, snapshotState.warmth.offset)
    assertEquals(1, snapshotState.formality.offset)
    assertEquals(1, snapshotState.warmth.lowerSupport)
    assertEquals(1, snapshotState.formality.higherSupport)
  }

  @Test
  fun planProjectsExplicitInteractionPreferenceSignalRecordsWithoutLegacyStyleValue() {
    val plan = planner.plan(
      existingRecords = emptyList(),
      sourceRecords = listOf(
        preferenceRecord(
          id = "interaction-signal",
          scope = MemoryScope.USER,
          preferenceKey = MemoryPreferenceKeys.INTERACTION_PREFERENCE_SIGNAL,
          preferenceValue = "adaptive",
          updatedAtEpochMs = 2_000L,
          extraExtensions = mapOf(
            MemoryInteractionPreferenceExtensionKeys.WARMTH_DIRECTION to "higher",
            MemoryInteractionPreferenceExtensionKeys.INITIATIVE_DIRECTION to "lower",
          ),
        ),
      ),
      plasticity = SoulPlasticity.HIGH,
      sourceSessionId = "session-main",
    )

    val snapshotState = interactionPreferenceStateFrom(plan.stateSnapshotCandidates.single())
    assertEquals(1, snapshotState.warmth.offset)
    assertEquals(1, snapshotState.warmth.higherSupport)
    assertEquals(-1, snapshotState.initiative.offset)
    assertEquals(1, snapshotState.initiative.lowerSupport)
  }

  @Test
  fun planProjectsPlayfulnessAndReassuranceExtensionsIntoSnapshot() {
    val plan = planner.plan(
      existingRecords = emptyList(),
      sourceRecords = listOf(
        preferenceRecord(
          id = "interaction-signal-rich",
          scope = MemoryScope.USER,
          preferenceKey = MemoryPreferenceKeys.INTERACTION_PREFERENCE_SIGNAL,
          preferenceValue = "adaptive",
          updatedAtEpochMs = 2_000L,
          extraExtensions = mapOf(
            MemoryInteractionPreferenceExtensionKeys.PLAYFULNESS_DIRECTION to "higher",
            MemoryInteractionPreferenceExtensionKeys.REASSURANCE_DIRECTION to "lower",
          ),
        ),
      ),
      plasticity = SoulPlasticity.HIGH,
      sourceSessionId = "session-main",
    )

    val snapshotState = interactionPreferenceStateFrom(plan.stateSnapshotCandidates.single())
    assertEquals(1, snapshotState.playfulness.offset)
    assertEquals(1, snapshotState.playfulness.higherSupport)
    assertEquals(-1, snapshotState.reassurance.offset)
    assertEquals(1, snapshotState.reassurance.lowerSupport)
  }

  private fun interactionPreferenceSignalRecord(
    id: String,
    scope: MemoryScope,
    preferenceValue: String,
    updatedAtEpochMs: Long,
    recordVersion: Long = 1L,
    extraExtensions: Map<String, String> = emptyMap(),
  ): MemoryRecord = MemoryRecord(
    id = id,
    content = "relationship style preference",
    recordVersion = recordVersion,
    createdAtEpochMs = updatedAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    tags = listOf(
      "kind:${MemoryKind.USER_PREFERENCE.name.lowercase()}",
      "scope:${scope.name.lowercase()}",
      "status:${MemoryStatus.ACTIVE.name.lowercase()}",
    ),
    extensions = mapOf(
      MemoryRecordExtensionKeys.KIND to MemoryKind.USER_PREFERENCE.name.lowercase(),
      MemoryRecordExtensionKeys.SCOPE to scope.name.lowercase(),
      MemoryRecordExtensionKeys.STATUS to MemoryStatus.ACTIVE.name.lowercase(),
      MemoryRecordExtensionKeys.PREFERENCE_KEY to MemoryPreferenceKeys.INTERACTION_PREFERENCE_SIGNAL,
      MemoryRecordExtensionKeys.PREFERENCE_VALUE to preferenceValue,
      MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS to updatedAtEpochMs.toString(),
    ) + extraExtensions,
  )

  private fun preferenceRecord(
    id: String,
    scope: MemoryScope,
    preferenceKey: String,
    preferenceValue: String,
    updatedAtEpochMs: Long,
    recordVersion: Long = 1L,
    extraExtensions: Map<String, String> = emptyMap(),
  ): MemoryRecord = MemoryRecord(
    id = id,
    content = "interaction preference",
    recordVersion = recordVersion,
    createdAtEpochMs = updatedAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    tags = listOf(
      "kind:${MemoryKind.USER_PREFERENCE.name.lowercase()}",
      "scope:${scope.name.lowercase()}",
      "status:${MemoryStatus.ACTIVE.name.lowercase()}",
    ),
    extensions = mapOf(
      MemoryRecordExtensionKeys.KIND to MemoryKind.USER_PREFERENCE.name.lowercase(),
      MemoryRecordExtensionKeys.SCOPE to scope.name.lowercase(),
      MemoryRecordExtensionKeys.STATUS to MemoryStatus.ACTIVE.name.lowercase(),
      MemoryRecordExtensionKeys.PREFERENCE_KEY to preferenceKey,
      MemoryRecordExtensionKeys.PREFERENCE_VALUE to preferenceValue,
      MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS to updatedAtEpochMs.toString(),
    ) + extraExtensions,
  )

  private fun interactionPreferenceSignalCandidate(
    scope: MemoryScope,
    preferenceValue: String,
    preferenceExtensions: Map<String, String>,
  ): MemoryCandidate = MemoryCandidate(
    kind = MemoryKind.USER_PREFERENCE,
    scope = scope,
    status = MemoryStatus.ACTIVE,
    content = "Interaction preference should gradually adapt: ${preferenceSummary(preferenceExtensions)}",
    source = MemoryEvidenceSource.USER_INPUT,
    sourceSessionId = "session-main",
    extensions = mapOf(
      MemoryRecordExtensionKeys.PREFERENCE_KEY to MemoryPreferenceKeys.INTERACTION_PREFERENCE_SIGNAL,
      MemoryRecordExtensionKeys.PREFERENCE_VALUE to preferenceValue,
    ) + preferenceExtensions,
  )

  private fun preferenceSummary(
    preferenceExtensions: Map<String, String>,
  ): String = buildList {
    preferenceExtensions[MemoryInteractionPreferenceExtensionKeys.WARMTH_DIRECTION]?.let { direction ->
      add("warmth $direction")
    }
    preferenceExtensions[MemoryInteractionPreferenceExtensionKeys.FORMALITY_DIRECTION]?.let { direction ->
      add("formality $direction")
    }
    preferenceExtensions[MemoryInteractionPreferenceExtensionKeys.INITIATIVE_DIRECTION]?.let { direction ->
      add("initiative $direction")
    }
    preferenceExtensions[MemoryInteractionPreferenceExtensionKeys.PLAYFULNESS_DIRECTION]?.let { direction ->
      add("playfulness $direction")
    }
    preferenceExtensions[MemoryInteractionPreferenceExtensionKeys.REASSURANCE_DIRECTION]?.let { direction ->
      add("reassurance $direction")
    }
  }.joinToString(separator = ", ")

  private fun interactionPreferenceStateRecord(
    id: String,
    scope: MemoryScope,
    state: InteractionPreferenceState,
    updatedAtEpochMs: Long,
  ): MemoryRecord = MemoryRecord(
    id = id,
    content = "internal interaction preference snapshot",
    createdAtEpochMs = updatedAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    extensions = mapOf(
      MemoryRecordExtensionKeys.SCOPE to scope.name.lowercase(),
      MemoryRecordExtensionKeys.STATUS to MemoryStatus.ACTIVE.name.lowercase(),
      MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS to updatedAtEpochMs.toString(),
    ) + buildInteractionPreferenceStateMemoryExtensions(state),
  )

  private fun interactionPreferenceStateFrom(
    candidate: com.opencray.runtime.memory.MemoryCandidate,
  ): InteractionPreferenceState = MemoryRecord(
    id = "temp",
    content = candidate.content,
    createdAtEpochMs = 1L,
    updatedAtEpochMs = 1L,
    extensions = candidate.extensions,
  ).parseInteractionPreferenceStateOrNull() ?: error("Expected interaction preference state payload.")
}
