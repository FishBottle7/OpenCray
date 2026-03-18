package com.opencray.runtime.soul

import com.opencray.persistence.model.MemoryRecord
import com.opencray.runtime.memory.MemoryKind
import com.opencray.runtime.memory.MemoryPreferenceKeys
import com.opencray.runtime.memory.MemoryRecordExtensionKeys
import com.opencray.runtime.memory.MemoryScope
import com.opencray.runtime.memory.MemoryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractionPreferenceMemoryWritePlannerTest {
  private val planner = InteractionPreferenceMemoryWritePlanner()

  @Test
  fun planCreatesUpdatedUserSnapshotFromRelationshipStyleSignals() {
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
        relationshipStylePreferenceRecord(
          id = "relationship-warm",
          scope = MemoryScope.USER,
          preferenceValue = "warm",
          updatedAtEpochMs = 2_000L,
          recordVersion = 1L,
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
        relationshipStylePreferenceRecord(
          id = "relationship-workspace",
          scope = MemoryScope.WORKSPACE,
          preferenceValue = "warm",
          updatedAtEpochMs = 2_000L,
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

  private fun relationshipStylePreferenceRecord(
    id: String,
    scope: MemoryScope,
    preferenceValue: String,
    updatedAtEpochMs: Long,
    recordVersion: Long = 1L,
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
      MemoryRecordExtensionKeys.PREFERENCE_KEY to MemoryPreferenceKeys.RELATIONSHIP_STYLE_PROFILE,
      MemoryRecordExtensionKeys.PREFERENCE_VALUE to preferenceValue,
      MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS to updatedAtEpochMs.toString(),
    ),
  )

  private fun preferenceRecord(
    id: String,
    scope: MemoryScope,
    preferenceKey: String,
    preferenceValue: String,
    updatedAtEpochMs: Long,
    recordVersion: Long = 1L,
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
    ),
  )

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
