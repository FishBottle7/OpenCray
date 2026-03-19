package com.opencray.runtime.soul

import com.opencray.persistence.model.MemoryRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoulMemoryPersistenceTest {
  @Test
  fun interactionPreferenceStateRoundTripsThroughMemoryExtensions() {
    val state = InteractionPreferenceState(
      warmth = PreferenceAxisState(offset = 1, higherSupport = 3, lowerSupport = 1, lastUpdatedAtEpochMs = 100L),
      formality = PreferenceAxisState(offset = -1, higherSupport = 0, lowerSupport = 2, lastUpdatedAtEpochMs = 100L),
      initiative = PreferenceAxisState(offset = 0, higherSupport = 1, lowerSupport = 1, lastUpdatedAtEpochMs = 90L),
      playfulness = PreferenceAxisState(offset = 1, higherSupport = 2, lastUpdatedAtEpochMs = 95L),
      reassurance = PreferenceAxisState(offset = -1, lowerSupport = 2, lastUpdatedAtEpochMs = 96L),
      addressStyle = PreferredAddressState(
        selectedStyle = PreferredAddressStyle.FRIENDLY,
        neutralSupport = 0,
        friendlySupport = 3,
        intimateSupport = 1,
        lastUpdatedAtEpochMs = 100L,
      ),
      preferredNaming = "小雨",
      preferredNamingSupport = 2,
      lastUpdatedAtEpochMs = 100L,
    )

    val record = memoryRecord(
      extensions = buildInteractionPreferenceStateMemoryExtensions(state),
    )

    assertEquals(SoulMemoryObjectTypes.INTERACTION_PREFERENCE_STATE, record.soulObjectTypeOrNull())
    assertEquals(state, record.parseInteractionPreferenceStateOrNull())
    assertNull(record.parseRelationshipStateOrNull())
    assertNull(record.parseRelationshipEventOrNull())
  }

  @Test
  fun relationshipStateRoundTripsThroughMemoryExtensions() {
    val state = RelationshipState(
      familiarity = 44,
      trust = 38,
      safety = 52,
      intimacyPermission = 21,
      playfulnessPermission = 18,
      affectionTendency = 12,
      reciprocity = 27,
      lastPositiveEventAtEpochMs = 1_000L,
      lastNegativeEventAtEpochMs = 900L,
      lastUpdatedAtEpochMs = 1_000L,
    )

    val record = memoryRecord(
      extensions = buildRelationshipStateMemoryExtensions(state),
    )

    assertEquals(SoulMemoryObjectTypes.RELATIONSHIP_STATE, record.soulObjectTypeOrNull())
    assertEquals(state, record.parseRelationshipStateOrNull())
  }

  @Test
  fun relationshipEventRoundTripsThroughMemoryExtensions() {
    val event = RelationshipEvent(
      eventType = RelationshipEventType.SUPPORTIVE_RESPONSE,
      valence = RelationshipEventValence.POSITIVE,
      confidence = RelationshipEventConfidence.HIGH,
      scope = RelationshipEventScope.WORKSPACE,
      sourceSessionId = "session-1",
      sourceTurnId = "turn-7",
      summary = "Responded supportively after a stressful moment.",
      occurredAtEpochMs = 5_000L,
      deltaHints = listOf(
        RelationshipDeltaHint(RelationshipDimension.SAFETY, 2),
        RelationshipDeltaHint(RelationshipDimension.AFFECTION_TENDENCY, 1),
      ),
    )

    val record = memoryRecord(
      extensions = buildRelationshipEventMemoryExtensions(event),
    )

    assertEquals(SoulMemoryObjectTypes.RELATIONSHIP_EVENT, record.soulObjectTypeOrNull())
    assertEquals(event, record.parseRelationshipEventOrNull())
  }

  @Test
  fun parserIgnoresUnknownFieldsInStoredPayload() {
    val record = memoryRecord(
      extensions = mapOf(
        SoulMemoryExtensionKeys.OBJECT_TYPE to SoulMemoryObjectTypes.RELATIONSHIP_EVENT,
        SoulMemoryExtensionKeys.OBJECT_SCHEMA_VERSION to "1",
        SoulMemoryExtensionKeys.OBJECT_PAYLOAD_JSON to """
          {
            "eventType":"SUPPORTIVE_RESPONSE",
            "valence":"POSITIVE",
            "confidence":"MEDIUM",
            "scope":"USER",
            "sourceSessionId":"session-2",
            "sourceTurnId":"turn-3",
            "summary":"Supportive response with forward-compatible field.",
            "occurredAtEpochMs":1234,
            "deltaHints":[{"dimension":"SAFETY","delta":2}],
            "futureField":"ignored"
          }
        """.trimIndent(),
      ),
    )

    val parsed = record.parseRelationshipEventOrNull()

    requireNotNull(parsed)
    assertEquals(RelationshipEventType.SUPPORTIVE_RESPONSE, parsed.eventType)
    assertEquals(RelationshipEventConfidence.MEDIUM, parsed.confidence)
    assertEquals(1, parsed.deltaHints.size)
  }

  @Test
  fun parserReturnsNullForWrongTypeOrBrokenPayload() {
    val wrongTypeRecord = memoryRecord(
      extensions = buildRelationshipEventMemoryExtensions(
        RelationshipEvent(
          eventType = RelationshipEventType.RECIPROCAL_WARMTH,
          valence = RelationshipEventValence.POSITIVE,
          confidence = RelationshipEventConfidence.MEDIUM,
          summary = "Reciprocal warmth.",
          occurredAtEpochMs = 100L,
        ),
      ),
    )
    val brokenPayloadRecord = memoryRecord(
      extensions = mapOf(
        SoulMemoryExtensionKeys.OBJECT_TYPE to SoulMemoryObjectTypes.RELATIONSHIP_STATE,
        SoulMemoryExtensionKeys.OBJECT_SCHEMA_VERSION to "1",
        SoulMemoryExtensionKeys.OBJECT_PAYLOAD_JSON to "{not-json}",
      ),
    )

    assertNull(wrongTypeRecord.parseRelationshipStateOrNull())
    assertNull(brokenPayloadRecord.parseRelationshipStateOrNull())
  }

  @Test
  fun buildersStampSchemaVersionAndPayload() {
    val extensions = buildRelationshipStateMemoryExtensions(RelationshipState(trust = 12))

    assertEquals("1", extensions[SoulMemoryExtensionKeys.OBJECT_SCHEMA_VERSION])
    assertTrue(extensions[SoulMemoryExtensionKeys.OBJECT_PAYLOAD_JSON].orEmpty().contains("\"trust\":12"))
  }

  private fun memoryRecord(
    extensions: Map<String, String>,
  ): MemoryRecord = MemoryRecord(
    id = "mem-test",
    content = "internal soul object",
    createdAtEpochMs = 1L,
    updatedAtEpochMs = 1L,
    extensions = extensions,
  )
}
