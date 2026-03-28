package com.opencray.runtime

import com.opencray.runtime.context.RuntimeConversationAttachment
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object OpenCrayPromptSupplementMetadataKeys {
  const val TEXT: String = "_host.promptSupplementText"
  const val ATTACHMENTS_JSON: String = "_host.promptSupplementAttachmentsJson"
}

object OpenCrayPromptSupplementMetadata {
  fun encodeMetadata(
    json: Json,
    text: String? = null,
    attachments: List<RuntimeConversationAttachment> = emptyList(),
  ): Map<String, String> {
    val normalizedText = text?.trim()?.takeIf(String::isNotBlank)
    if (normalizedText == null && attachments.isEmpty()) {
      return emptyMap()
    }
    return buildMap {
      normalizedText?.let { put(OpenCrayPromptSupplementMetadataKeys.TEXT, it) }
      if (attachments.isNotEmpty()) {
        put(
          OpenCrayPromptSupplementMetadataKeys.ATTACHMENTS_JSON,
          json.encodeToString(
            ListSerializer(RuntimeConversationAttachment.serializer()),
            attachments,
          ),
        )
      }
    }
  }

  fun decodeText(metadata: Map<String, String>): String? =
    metadata[OpenCrayPromptSupplementMetadataKeys.TEXT]
      ?.trim()
      ?.takeIf(String::isNotBlank)

  fun decodeAttachments(
    metadata: Map<String, String>,
    json: Json,
  ): List<RuntimeConversationAttachment> =
    metadata[OpenCrayPromptSupplementMetadataKeys.ATTACHMENTS_JSON]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { encoded ->
        runCatching {
          json.decodeFromString(
            ListSerializer(RuntimeConversationAttachment.serializer()),
            encoded,
          )
        }.getOrDefault(emptyList())
      }
      ?: emptyList()
}
