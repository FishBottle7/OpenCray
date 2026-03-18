package com.opencray.runtime.soul

import com.opencray.persistence.model.MemoryRecord
import com.opencray.runtime.memory.MemoryScope

data class ProjectedRelationshipState(
  val state: RelationshipState,
  val snapshotRecordId: String? = null,
  val appliedEventRecordIds: List<String> = emptyList(),
)

class RelationshipStateProjector(
  private val updater: RelationshipStateUpdater = RelationshipStateUpdater(),
  private val clock: () -> Long = System::currentTimeMillis,
) {
  fun project(
    records: List<MemoryRecord>,
    scope: MemoryScope,
    plasticity: SoulPlasticity,
    sessionId: String? = null,
    workspaceId: String? = null,
  ): ProjectedRelationshipState {
    val visibleRecords = records.mapNotNull { record ->
      val envelope = record.parseSoulMemoryEnvelopeOrNull(nowEpochMs = clock()) ?: return@mapNotNull null
      if (!soulProjectionScopeMatches(envelope = envelope, scope = scope, sessionId = sessionId, workspaceId = workspaceId)) {
        return@mapNotNull null
      }
      val stateSnapshot = record.parseRelationshipStateOrNull()
      val relationshipEvent = record.parseRelationshipEventOrNull()
      if (stateSnapshot == null && relationshipEvent == null) {
        return@mapNotNull null
      }
      PersistedRelationshipRecord(
        recordId = record.id,
        referenceEpochMs = envelope.referenceEpochMs ?: record.updatedAtEpochMs,
        stateSnapshot = stateSnapshot,
        relationshipEvent = relationshipEvent,
      )
    }
    val latestSnapshot = visibleRecords
      .asSequence()
      .filter { persisted -> persisted.stateSnapshot != null }
      .maxWithOrNull(
        compareBy<PersistedRelationshipRecord> { persisted -> persisted.referenceEpochMs }
          .thenBy { persisted -> persisted.recordId },
      )

    var currentState = latestSnapshot?.stateSnapshot ?: RelationshipState()
    val appliedEventRecordIds = mutableListOf<String>()
    visibleRecords
      .asSequence()
      .filter { persisted -> persisted.relationshipEvent != null }
      .filter { persisted ->
        latestSnapshot == null || persisted.referenceEpochMs > latestSnapshot.referenceEpochMs
      }
      .sortedWith(
        compareBy<PersistedRelationshipRecord> { persisted ->
          checkNotNull(persisted.relationshipEvent).occurredAtEpochMs
        }.thenBy { persisted ->
          persisted.referenceEpochMs
        }.thenBy { persisted ->
          persisted.recordId
        },
      )
      .forEach { persisted ->
        currentState = updater.apply(
          state = currentState,
          event = checkNotNull(persisted.relationshipEvent),
          plasticity = plasticity,
        )
        appliedEventRecordIds += persisted.recordId
      }

    return ProjectedRelationshipState(
      state = currentState,
      snapshotRecordId = latestSnapshot?.recordId,
      appliedEventRecordIds = appliedEventRecordIds,
    )
  }

  private data class PersistedRelationshipRecord(
    val recordId: String,
    val referenceEpochMs: Long,
    val stateSnapshot: RelationshipState? = null,
    val relationshipEvent: RelationshipEvent? = null,
  )
}
