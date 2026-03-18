package com.opencray.runtime.soul

import com.opencray.persistence.model.MemoryRecord
import com.opencray.runtime.memory.MemoryCandidate
import com.opencray.runtime.memory.MemoryScope

data class RelationshipMemoryWritePlan(
  val eventCandidates: List<MemoryCandidate> = emptyList(),
  val stateSnapshotCandidates: List<MemoryCandidate> = emptyList(),
) {
  val candidates: List<MemoryCandidate>
    get() = eventCandidates + stateSnapshotCandidates
}

class RelationshipMemoryWritePlanner(
  private val projector: RelationshipStateProjector = RelationshipStateProjector(),
  private val updater: RelationshipStateUpdater = RelationshipStateUpdater(),
  private val candidateFactory: SoulMemoryCandidateFactory = SoulMemoryCandidateFactory(),
) {
  fun plan(
    existingRecords: List<MemoryRecord>,
    events: List<RelationshipEvent>,
    plasticity: SoulPlasticity,
    sourceSessionId: String,
    workspaceId: String? = null,
    sourceTaskId: String? = null,
  ): RelationshipMemoryWritePlan {
    if (events.isEmpty()) {
      return RelationshipMemoryWritePlan()
    }

    val groupedEvents = events
      .mapNotNull { event ->
        resolveMemoryScope(event.scope, workspaceId)?.let { scope ->
          scope to event
        }
      }
      .groupBy(
        keySelector = { (scope, _) -> scope },
        valueTransform = { (_, event) -> event },
      )

    if (groupedEvents.isEmpty()) {
      return RelationshipMemoryWritePlan()
    }

    val eventCandidates = mutableListOf<MemoryCandidate>()
    val snapshotCandidates = mutableListOf<MemoryCandidate>()
    groupedEvents.forEach { (scope, scopedEvents) ->
      var projectedState = projector.project(
        records = existingRecords,
        scope = scope,
        plasticity = plasticity,
        sessionId = sourceSessionId,
        workspaceId = workspaceId,
      ).state
      scopedEvents
        .sortedWith(
          compareBy<RelationshipEvent> { event -> event.occurredAtEpochMs }
            .thenBy { event -> event.summary },
        )
        .forEach { event ->
          eventCandidates += candidateFactory.relationshipEventCandidate(
            event = event,
            scope = scope,
            sourceSessionId = sourceSessionId,
            workspaceId = workspaceId.takeIf { scope == MemoryScope.WORKSPACE },
            sourceTaskId = sourceTaskId,
          )
          projectedState = updater.apply(
            state = projectedState,
            event = event,
            plasticity = plasticity,
          )
        }
      snapshotCandidates += candidateFactory.relationshipStateCandidate(
        state = projectedState,
        scope = scope,
        sourceSessionId = sourceSessionId,
        workspaceId = workspaceId.takeIf { scope == MemoryScope.WORKSPACE },
        sourceTaskId = sourceTaskId,
      )
    }

    return RelationshipMemoryWritePlan(
      eventCandidates = eventCandidates,
      stateSnapshotCandidates = snapshotCandidates,
    )
  }

  private fun resolveMemoryScope(
    eventScope: RelationshipEventScope,
    workspaceId: String?,
  ): MemoryScope? = when (eventScope) {
    RelationshipEventScope.USER -> MemoryScope.USER
    RelationshipEventScope.WORKSPACE -> {
      if (workspaceId.isNullOrBlank()) {
        null
      } else {
        MemoryScope.WORKSPACE
      }
    }
  }
}
