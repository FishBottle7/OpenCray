package com.opencray.runtime.soul

import com.opencray.persistence.model.MemoryRecord
import com.opencray.runtime.memory.MemoryRecordExtensionKeys
import com.opencray.runtime.memory.MemoryScope
import com.opencray.runtime.memory.MemoryStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class RelationshipStateProjectorTest {
  private val projector = RelationshipStateProjector(
    clock = { 10_000L },
  )

  @Test
  fun projectStartsFromLatestSnapshotAndAppliesLaterEventsInOrder() {
    val snapshot = record(
      id = "snapshot-1",
      scope = MemoryScope.USER,
      updatedAtEpochMs = 1_000L,
      lastConfirmedAtEpochMs = 1_000L,
      extensions = buildRelationshipStateMemoryExtensions(
        RelationshipState(
          familiarity = 20,
          trust = 18,
          safety = 25,
          intimacyPermission = 10,
          playfulnessPermission = 8,
          affectionTendency = 5,
          reciprocity = 16,
        ),
      ),
    )
    val supportiveEvent = record(
      id = "event-1",
      scope = MemoryScope.USER,
      updatedAtEpochMs = 1_100L,
      lastConfirmedAtEpochMs = 1_100L,
      extensions = buildRelationshipEventMemoryExtensions(
        RelationshipEvent(
          eventType = RelationshipEventType.SUPPORTIVE_RESPONSE,
          valence = RelationshipEventValence.POSITIVE,
          confidence = RelationshipEventConfidence.MEDIUM,
          summary = "Supportive response after stress.",
          occurredAtEpochMs = 1_100L,
        ),
      ),
    )
    val reciprocalWarmthEvent = record(
      id = "event-2",
      scope = MemoryScope.USER,
      updatedAtEpochMs = 1_200L,
      lastConfirmedAtEpochMs = 1_200L,
      extensions = buildRelationshipEventMemoryExtensions(
        RelationshipEvent(
          eventType = RelationshipEventType.RECIPROCAL_WARMTH,
          valence = RelationshipEventValence.POSITIVE,
          confidence = RelationshipEventConfidence.MEDIUM,
          summary = "Mutual warmth was established.",
          occurredAtEpochMs = 1_200L,
        ),
      ),
    )

    val projected = projector.project(
      records = listOf(reciprocalWarmthEvent, snapshot, supportiveEvent),
      scope = MemoryScope.USER,
      plasticity = SoulPlasticity.MEDIUM,
    )

    assertEquals("snapshot-1", projected.snapshotRecordId)
    assertEquals(listOf("event-1", "event-2"), projected.appliedEventRecordIds)
    assertEquals(21, projected.state.familiarity)
    assertEquals(18, projected.state.trust)
    assertEquals(27, projected.state.safety)
    assertEquals(18, projected.state.reciprocity)
  }

  @Test
  fun projectIgnoresEventsOutsideRequestedWorkspace() {
    val matchingWorkspaceEvent = record(
      id = "workspace-event-main",
      scope = MemoryScope.WORKSPACE,
      workspaceId = "workspace-main",
      updatedAtEpochMs = 1_100L,
      lastConfirmedAtEpochMs = 1_100L,
      extensions = buildRelationshipEventMemoryExtensions(
        RelationshipEvent(
          eventType = RelationshipEventType.KEPT_PROMISE,
          valence = RelationshipEventValence.POSITIVE,
          confidence = RelationshipEventConfidence.MEDIUM,
          summary = "A promise in this workspace was kept.",
          occurredAtEpochMs = 1_100L,
        ),
      ),
    )
    val otherWorkspaceEvent = record(
      id = "workspace-event-other",
      scope = MemoryScope.WORKSPACE,
      workspaceId = "workspace-other",
      updatedAtEpochMs = 1_200L,
      lastConfirmedAtEpochMs = 1_200L,
      extensions = buildRelationshipEventMemoryExtensions(
        RelationshipEvent(
          eventType = RelationshipEventType.PUNISHED_VULNERABILITY,
          valence = RelationshipEventValence.NEGATIVE,
          confidence = RelationshipEventConfidence.MEDIUM,
          summary = "A vulnerability was punished in another workspace.",
          occurredAtEpochMs = 1_200L,
        ),
      ),
    )

    val projected = projector.project(
      records = listOf(matchingWorkspaceEvent, otherWorkspaceEvent),
      scope = MemoryScope.WORKSPACE,
      workspaceId = "workspace-main",
      plasticity = SoulPlasticity.MEDIUM,
    )

    assertEquals(listOf("workspace-event-main"), projected.appliedEventRecordIds)
    assertEquals(2, projected.state.trust)
    assertEquals(1, projected.state.familiarity)
    assertEquals(0, projected.state.safety)
  }

  @Test
  fun projectIgnoresResolvedAndExpiredSoulRecords() {
    val resolvedEvent = record(
      id = "resolved-event",
      scope = MemoryScope.USER,
      status = MemoryStatus.RESOLVED,
      updatedAtEpochMs = 1_000L,
      lastConfirmedAtEpochMs = 1_000L,
      extensions = buildRelationshipEventMemoryExtensions(
        RelationshipEvent(
          eventType = RelationshipEventType.SUPPORTIVE_RESPONSE,
          valence = RelationshipEventValence.POSITIVE,
          confidence = RelationshipEventConfidence.MEDIUM,
          summary = "Resolved event should be ignored.",
          occurredAtEpochMs = 1_000L,
        ),
      ),
    )
    val expiredEvent = record(
      id = "expired-event",
      scope = MemoryScope.USER,
      ttlMs = 100L,
      updatedAtEpochMs = 1_000L,
      lastConfirmedAtEpochMs = 1_000L,
      extensions = buildRelationshipEventMemoryExtensions(
        RelationshipEvent(
          eventType = RelationshipEventType.KEPT_PROMISE,
          valence = RelationshipEventValence.POSITIVE,
          confidence = RelationshipEventConfidence.MEDIUM,
          summary = "Expired event should be ignored.",
          occurredAtEpochMs = 1_000L,
        ),
      ),
    )

    val projected = projector.project(
      records = listOf(resolvedEvent, expiredEvent),
      scope = MemoryScope.USER,
      plasticity = SoulPlasticity.HIGH,
    )

    assertEquals(emptyList<String>(), projected.appliedEventRecordIds)
    assertEquals(RelationshipState(), projected.state)
  }

  private fun record(
    id: String,
    scope: MemoryScope,
    status: MemoryStatus = MemoryStatus.ACTIVE,
    workspaceId: String? = null,
    ttlMs: Long? = null,
    updatedAtEpochMs: Long,
    lastConfirmedAtEpochMs: Long,
    extensions: Map<String, String>,
  ): MemoryRecord = MemoryRecord(
    id = id,
    content = "internal relationship object",
    createdAtEpochMs = updatedAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    extensions = mapOf(
      MemoryRecordExtensionKeys.SCOPE to scope.name.lowercase(),
      MemoryRecordExtensionKeys.STATUS to status.name.lowercase(),
      MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS to lastConfirmedAtEpochMs.toString(),
    ) + buildMap {
      workspaceId?.let { put(MemoryRecordExtensionKeys.WORKSPACE_ID, it) }
      ttlMs?.let { put(MemoryRecordExtensionKeys.TTL_MS, it.toString()) }
      putAll(extensions)
    },
  )
}
