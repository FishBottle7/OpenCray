package com.opencray.runtime.soul

import com.opencray.persistence.store.MemoryStore
import com.opencray.runtime.memory.MemoryScope
import com.opencray.runtime.memory.MemoryWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoulMemoryCandidateFactoryTest {
  private val factory = SoulMemoryCandidateFactory()

  @Test
  fun stateSnapshotsOverwriteSameScopedRecordWhileEventsAppend() {
    val store = InMemoryMemoryStore()
    val writer = MemoryWriter(
      store = store,
      clock = IncrementingClock(start = 1_000L)::next,
    )

    writer.write(
      listOf(
        factory.relationshipStateCandidate(
          state = RelationshipState(trust = 10),
          scope = MemoryScope.USER,
          sourceSessionId = "session-main",
        ),
      ),
    )
    writer.write(
      listOf(
        factory.relationshipStateCandidate(
          state = RelationshipState(trust = 22),
          scope = MemoryScope.USER,
          sourceSessionId = "session-main",
        ),
      ),
    )
    writer.write(
      listOf(
        factory.relationshipEventCandidate(
          event = RelationshipEvent(
            eventType = RelationshipEventType.KEPT_PROMISE,
            valence = RelationshipEventValence.POSITIVE,
            confidence = RelationshipEventConfidence.MEDIUM,
            summary = "Kept a promise after saying it would.",
            occurredAtEpochMs = 3_000L,
          ),
          scope = MemoryScope.USER,
          sourceSessionId = "session-main",
        ),
        factory.relationshipEventCandidate(
          event = RelationshipEvent(
            eventType = RelationshipEventType.SUPPORTIVE_RESPONSE,
            valence = RelationshipEventValence.POSITIVE,
            confidence = RelationshipEventConfidence.MEDIUM,
            summary = "Responded supportively.",
            occurredAtEpochMs = 4_000L,
          ),
          scope = MemoryScope.USER,
          sourceSessionId = "session-main",
        ),
      ),
    )

    val relationshipStateRecords = store.list().filter { record ->
      record.soulObjectTypeOrNull() == SoulMemoryObjectTypes.RELATIONSHIP_STATE
    }
    val relationshipEventRecords = store.list().filter { record ->
      record.soulObjectTypeOrNull() == SoulMemoryObjectTypes.RELATIONSHIP_EVENT
    }

    assertEquals(1, relationshipStateRecords.size)
    assertEquals(22, relationshipStateRecords.single().parseRelationshipStateOrNull()?.trust)
    assertEquals(2, relationshipEventRecords.size)
  }

  @Test
  fun eventCandidateCarriesSummaryIntoStableInternalContent() {
    val candidate = factory.relationshipEventCandidate(
      event = RelationshipEvent(
        eventType = RelationshipEventType.BOUNDARY_PRESSURE,
        valence = RelationshipEventValence.NEGATIVE,
        confidence = RelationshipEventConfidence.HIGH,
        summary = "Pressure was applied after a clear refusal.",
        occurredAtEpochMs = 5_000L,
      ),
      scope = MemoryScope.USER,
      sourceSessionId = "session-main",
    )

    assertTrue(candidate.content.contains("boundary_pressure"))
    assertTrue(candidate.content.contains("Pressure was applied"))
    assertEquals(SoulMemoryObjectTypes.RELATIONSHIP_EVENT, candidate.extensions[SoulMemoryExtensionKeys.OBJECT_TYPE])
  }

  private class InMemoryMemoryStore : MemoryStore {
    private val records = linkedMapOf<String, com.opencray.persistence.model.MemoryRecord>()

    override fun list(): List<com.opencray.persistence.model.MemoryRecord> = records.values.toList()

    override fun upsert(record: com.opencray.persistence.model.MemoryRecord) {
      records[record.id] = record
    }

    override fun delete(id: String): Boolean = records.remove(id) != null

    override fun clear(): Boolean {
      val hadRecords = records.isNotEmpty()
      records.clear()
      return hadRecords
    }
  }

  private class IncrementingClock(
    start: Long,
  ) {
    private var value = start

    fun next(): Long = value++
  }
}
