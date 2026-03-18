package com.opencray.runtime.soul

import com.opencray.runtime.memory.MemoryCandidate
import com.opencray.runtime.memory.MemoryEvidenceSource
import com.opencray.runtime.memory.MemoryKind
import com.opencray.runtime.memory.MemoryScope

class SoulMemoryCandidateFactory {
  fun interactionPreferenceStateCandidate(
    state: InteractionPreferenceState,
    scope: MemoryScope,
    sourceSessionId: String,
    workspaceId: String? = null,
    sourceTaskId: String? = null,
  ): MemoryCandidate = MemoryCandidate(
    kind = MemoryKind.PROJECT_FACT,
    scope = scope,
    status = com.opencray.runtime.memory.MemoryStatus.ACTIVE,
    content = buildStateSnapshotContent(
      objectType = SoulMemoryObjectTypes.INTERACTION_PREFERENCE_STATE,
      scope = scope,
    ),
    source = MemoryEvidenceSource.ASSISTANT_OUTPUT,
    sourceSessionId = sourceSessionId,
    sourceTaskId = sourceTaskId,
    workspaceId = workspaceId,
    ttlMs = null,
    extensions = buildInteractionPreferenceStateMemoryExtensions(state),
  )

  fun relationshipStateCandidate(
    state: RelationshipState,
    scope: MemoryScope,
    sourceSessionId: String,
    workspaceId: String? = null,
    sourceTaskId: String? = null,
  ): MemoryCandidate = MemoryCandidate(
    kind = MemoryKind.PROJECT_FACT,
    scope = scope,
    status = com.opencray.runtime.memory.MemoryStatus.ACTIVE,
    content = buildStateSnapshotContent(
      objectType = SoulMemoryObjectTypes.RELATIONSHIP_STATE,
      scope = scope,
    ),
    source = MemoryEvidenceSource.ASSISTANT_OUTPUT,
    sourceSessionId = sourceSessionId,
    sourceTaskId = sourceTaskId,
    workspaceId = workspaceId,
    ttlMs = null,
    extensions = buildRelationshipStateMemoryExtensions(state),
  )

  fun relationshipEventCandidate(
    event: RelationshipEvent,
    scope: MemoryScope,
    sourceSessionId: String,
    workspaceId: String? = null,
    sourceTaskId: String? = null,
  ): MemoryCandidate = MemoryCandidate(
    kind = MemoryKind.PROJECT_FACT,
    scope = scope,
    status = com.opencray.runtime.memory.MemoryStatus.ACTIVE,
    content = buildEventContent(event),
    source = MemoryEvidenceSource.ASSISTANT_OUTPUT,
    sourceSessionId = sourceSessionId,
    sourceTaskId = sourceTaskId,
    workspaceId = workspaceId,
    ttlMs = null,
    extensions = buildRelationshipEventMemoryExtensions(event),
  )

  private fun buildStateSnapshotContent(
    objectType: String,
    scope: MemoryScope,
  ): String = "Internal $objectType snapshot for ${scope.name.lowercase()} scope"

  private fun buildEventContent(
    event: RelationshipEvent,
  ): String = buildString {
    append("Internal relationship event ")
    append(event.eventType.name.lowercase())
    append(" @ ")
    append(event.occurredAtEpochMs)
    append(": ")
    append(event.summary)
  }.take(MAX_EVENT_CONTENT_CHARS)

  private companion object {
    const val MAX_EVENT_CONTENT_CHARS: Int = 160
  }
}
