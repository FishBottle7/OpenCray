package com.opencray.runtime.soul

import com.opencray.persistence.model.MemoryRecord
import com.opencray.runtime.memory.MemoryScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelationshipMemoryWritePlannerTest {
  private val planner = RelationshipMemoryWritePlanner()

  @Test
  fun planCreatesEventCandidatesAndOneUpdatedUserSnapshot() {
    val plan = planner.plan(
      existingRecords = listOf(
        relationshipStateRecord(
          id = "existing-user-snapshot",
          scope = MemoryScope.USER,
          state = RelationshipState(trust = 10, safety = 12, reciprocity = 8),
          updatedAtEpochMs = 1_000L,
        ),
      ),
      events = listOf(
        RelationshipEvent(
          eventType = RelationshipEventType.KEPT_PROMISE,
          valence = RelationshipEventValence.POSITIVE,
          confidence = RelationshipEventConfidence.MEDIUM,
          summary = "Kept a promise.",
          occurredAtEpochMs = 2_000L,
        ),
        RelationshipEvent(
          eventType = RelationshipEventType.SUPPORTIVE_RESPONSE,
          valence = RelationshipEventValence.POSITIVE,
          confidence = RelationshipEventConfidence.MEDIUM,
          summary = "Responded supportively.",
          occurredAtEpochMs = 3_000L,
        ),
      ),
      plasticity = SoulPlasticity.MEDIUM,
      sourceSessionId = "session-main",
    )

    assertEquals(2, plan.eventCandidates.size)
    assertEquals(1, plan.stateSnapshotCandidates.size)
    assertTrue(plan.eventCandidates.all { candidate ->
      candidate.extensions[SoulMemoryExtensionKeys.OBJECT_TYPE] == SoulMemoryObjectTypes.RELATIONSHIP_EVENT
    })
    val snapshotState = relationshipStateFrom(plan.stateSnapshotCandidates.single())
    assertEquals(12, snapshotState.trust)
    assertEquals(14, snapshotState.safety)
  }

  @Test
  fun planCreatesSeparateSnapshotsForUserAndWorkspaceEvents() {
    val plan = planner.plan(
      existingRecords = emptyList(),
      events = listOf(
        RelationshipEvent(
          eventType = RelationshipEventType.RECIPROCAL_WARMTH,
          valence = RelationshipEventValence.POSITIVE,
          confidence = RelationshipEventConfidence.MEDIUM,
          scope = RelationshipEventScope.USER,
          summary = "Mutual warmth.",
          occurredAtEpochMs = 2_000L,
        ),
        RelationshipEvent(
          eventType = RelationshipEventType.KEPT_PROMISE,
          valence = RelationshipEventValence.POSITIVE,
          confidence = RelationshipEventConfidence.MEDIUM,
          scope = RelationshipEventScope.WORKSPACE,
          summary = "Workspace-local promise kept.",
          occurredAtEpochMs = 2_100L,
        ),
      ),
      plasticity = SoulPlasticity.MEDIUM,
      sourceSessionId = "session-main",
      workspaceId = "workspace-main",
    )

    assertEquals(2, plan.eventCandidates.size)
    assertEquals(2, plan.stateSnapshotCandidates.size)
    assertTrue(plan.stateSnapshotCandidates.any { candidate -> candidate.scope == MemoryScope.USER })
    assertTrue(plan.stateSnapshotCandidates.any { candidate -> candidate.scope == MemoryScope.WORKSPACE })
  }

  @Test
  fun planSkipsWorkspaceScopedEventsWhenWorkspaceIdIsMissing() {
    val plan = planner.plan(
      existingRecords = emptyList(),
      events = listOf(
        RelationshipEvent(
          eventType = RelationshipEventType.KEPT_PROMISE,
          valence = RelationshipEventValence.POSITIVE,
          confidence = RelationshipEventConfidence.MEDIUM,
          scope = RelationshipEventScope.WORKSPACE,
          summary = "Workspace-local promise kept.",
          occurredAtEpochMs = 2_100L,
        ),
      ),
      plasticity = SoulPlasticity.MEDIUM,
      sourceSessionId = "session-main",
    )

    assertEquals(0, plan.eventCandidates.size)
    assertEquals(0, plan.stateSnapshotCandidates.size)
  }

  private fun relationshipStateRecord(
    id: String,
    scope: MemoryScope,
    state: RelationshipState,
    updatedAtEpochMs: Long,
  ): MemoryRecord = MemoryRecord(
    id = id,
    content = "internal relationship snapshot",
    createdAtEpochMs = updatedAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    extensions = mapOf(
      "scope" to scope.name.lowercase(),
      "status" to "active",
      "last_confirmed_at_epoch_ms" to updatedAtEpochMs.toString(),
    ) + buildRelationshipStateMemoryExtensions(state),
  )

  private fun relationshipStateFrom(
    candidate: com.opencray.runtime.memory.MemoryCandidate,
  ): RelationshipState = MemoryRecord(
    id = "temp",
    content = candidate.content,
    createdAtEpochMs = 1L,
    updatedAtEpochMs = 1L,
    extensions = candidate.extensions,
  ).parseRelationshipStateOrNull() ?: error("Expected relationship state payload.")
}
