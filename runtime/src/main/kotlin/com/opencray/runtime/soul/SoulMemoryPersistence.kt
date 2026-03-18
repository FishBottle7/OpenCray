package com.opencray.runtime.soul

import com.opencray.persistence.model.MemoryRecord
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale

object SoulMemoryObjectTypes {
  const val INTERACTION_PREFERENCE_STATE: String = "interaction_preference_state"
  const val RELATIONSHIP_STATE: String = "relationship_state"
  const val RELATIONSHIP_EVENT: String = "relationship_event"
}

object SoulMemoryExtensionKeys {
  const val OBJECT_TYPE: String = "soul_object_type"
  const val OBJECT_SCHEMA_VERSION: String = "soul_object_schema_version"
  const val OBJECT_PAYLOAD_JSON: String = "soul_object_payload_json"
}

internal fun buildInteractionPreferenceStateMemoryExtensions(
  state: InteractionPreferenceState,
): Map<String, String> = buildSoulMemoryExtensions(
  objectType = SoulMemoryObjectTypes.INTERACTION_PREFERENCE_STATE,
  payloadJson = SoulMemoryJson.instance.encodeToString(
    InteractionPreferenceState.serializer(),
    state,
  ),
)

internal fun buildRelationshipStateMemoryExtensions(
  state: RelationshipState,
): Map<String, String> = buildSoulMemoryExtensions(
  objectType = SoulMemoryObjectTypes.RELATIONSHIP_STATE,
  payloadJson = SoulMemoryJson.instance.encodeToString(
    RelationshipState.serializer(),
    state,
  ),
)

internal fun buildRelationshipEventMemoryExtensions(
  event: RelationshipEvent,
): Map<String, String> = buildSoulMemoryExtensions(
  objectType = SoulMemoryObjectTypes.RELATIONSHIP_EVENT,
  payloadJson = SoulMemoryJson.instance.encodeToString(
    RelationshipEvent.serializer(),
    event,
  ),
)

internal fun MemoryRecord.parseInteractionPreferenceStateOrNull(): InteractionPreferenceState? =
  parseSoulMemoryPayloadOrNull(
    expectedObjectType = SoulMemoryObjectTypes.INTERACTION_PREFERENCE_STATE,
    decode = { payload ->
      SoulMemoryJson.instance.decodeFromString(
        InteractionPreferenceState.serializer(),
        payload,
      )
    },
  )

internal fun MemoryRecord.parseRelationshipStateOrNull(): RelationshipState? =
  parseSoulMemoryPayloadOrNull(
    expectedObjectType = SoulMemoryObjectTypes.RELATIONSHIP_STATE,
    decode = { payload ->
      SoulMemoryJson.instance.decodeFromString(
        RelationshipState.serializer(),
        payload,
      )
    },
  )

internal fun MemoryRecord.parseRelationshipEventOrNull(): RelationshipEvent? =
  parseSoulMemoryPayloadOrNull(
    expectedObjectType = SoulMemoryObjectTypes.RELATIONSHIP_EVENT,
    decode = { payload ->
      SoulMemoryJson.instance.decodeFromString(
        RelationshipEvent.serializer(),
        payload,
      )
    },
  )

internal fun MemoryRecord.soulObjectTypeOrNull(): String? =
  extensions[SoulMemoryExtensionKeys.OBJECT_TYPE]
    ?.trim()
    ?.lowercase(Locale.US)
    ?.takeIf(String::isNotBlank)

internal fun MemoryRecord.hasSoulObjectPayload(): Boolean = soulObjectTypeOrNull() != null

private fun buildSoulMemoryExtensions(
  objectType: String,
  payloadJson: String,
): Map<String, String> = linkedMapOf(
  SoulMemoryExtensionKeys.OBJECT_TYPE to objectType,
  SoulMemoryExtensionKeys.OBJECT_SCHEMA_VERSION to SoulMemoryJson.SCHEMA_VERSION.toString(),
  SoulMemoryExtensionKeys.OBJECT_PAYLOAD_JSON to payloadJson,
)

private fun <T> MemoryRecord.parseSoulMemoryPayloadOrNull(
  expectedObjectType: String,
  decode: (String) -> T,
): T? {
  if (soulObjectTypeOrNull() != expectedObjectType) {
    return null
  }
  val payload = extensions[SoulMemoryExtensionKeys.OBJECT_PAYLOAD_JSON]
    ?.takeIf(String::isNotBlank)
    ?: return null
  return runCatching { decode(payload) }.getOrNull()
}

private object SoulMemoryJson {
  const val SCHEMA_VERSION: Int = 1

  @OptIn(ExperimentalSerializationApi::class)
  val instance: Json = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = true
    prettyPrint = false
  }
}
