package com.opencray.runtime.soul

import com.opencray.persistence.model.MemoryRecord
import com.opencray.runtime.memory.MemoryScope

data class ProjectedInteractionPreferenceState(
  val state: InteractionPreferenceState,
  val snapshotRecordId: String? = null,
)

class InteractionPreferenceStateProjector(
  private val clock: () -> Long = System::currentTimeMillis,
) {
  fun project(
    records: List<MemoryRecord>,
    scope: MemoryScope,
    sessionId: String? = null,
    workspaceId: String? = null,
  ): ProjectedInteractionPreferenceState {
    val latestSnapshot = records
      .mapNotNull { record ->
        val envelope = record.parseSoulMemoryEnvelopeOrNull(nowEpochMs = clock()) ?: return@mapNotNull null
        if (!soulProjectionScopeMatches(envelope = envelope, scope = scope, sessionId = sessionId, workspaceId = workspaceId)) {
          return@mapNotNull null
        }
        val stateSnapshot = record.parseInteractionPreferenceStateOrNull() ?: return@mapNotNull null
        PersistedInteractionPreferenceRecord(
          recordId = record.id,
          referenceEpochMs = envelope.referenceEpochMs ?: record.updatedAtEpochMs,
          stateSnapshot = stateSnapshot,
        )
      }
      .maxWithOrNull(
        compareBy<PersistedInteractionPreferenceRecord> { persisted -> persisted.referenceEpochMs }
          .thenBy { persisted -> persisted.recordId },
      )

    return ProjectedInteractionPreferenceState(
      state = latestSnapshot?.stateSnapshot ?: InteractionPreferenceState(),
      snapshotRecordId = latestSnapshot?.recordId,
    )
  }

  private data class PersistedInteractionPreferenceRecord(
    val recordId: String,
    val referenceEpochMs: Long,
    val stateSnapshot: InteractionPreferenceState,
  )
}
