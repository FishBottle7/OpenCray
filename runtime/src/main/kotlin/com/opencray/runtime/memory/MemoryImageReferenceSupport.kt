package com.opencray.runtime.memory

import com.opencray.persistence.model.MemoryRecord
import com.opencray.runtime.OpenCrayImageReference
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.Locale

object MemoryImageReferenceSupport {
  private val json: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  fun decodeFromExtensions(extensions: Map<String, String>): List<OpenCrayImageReference> {
    val payload = extensions[MemoryRecordExtensionKeys.IMAGE_REFS_JSON]
      ?.trim()
      ?.takeIf(String::isNotEmpty)
      ?: return emptyList()
    return runCatching {
      json.decodeFromString(ListSerializer(OpenCrayImageReference.serializer()), payload)
    }.getOrDefault(emptyList())
  }

  fun encodeIntoExtensions(
    extensions: Map<String, String>,
    imageReferences: List<OpenCrayImageReference>,
  ): Map<String, String> {
    val filtered = extensions.toMutableMap()
    if (imageReferences.isEmpty()) {
      filtered.remove(MemoryRecordExtensionKeys.IMAGE_REFS_JSON)
      return filtered
    }
    filtered[MemoryRecordExtensionKeys.IMAGE_REFS_JSON] = json.encodeToString(
      ListSerializer(OpenCrayImageReference.serializer()),
      imageReferences,
    )
    return filtered
  }

  fun mergeImageReferences(
    existing: List<OpenCrayImageReference>,
    incoming: List<OpenCrayImageReference>,
  ): List<OpenCrayImageReference> {
    if (incoming.isEmpty()) {
      return existing
    }
    val merged = existing.toMutableList()
    incoming.forEach { candidate ->
      val existingIndex = merged.indexOfFirst { current ->
        current.matchesMemoryImageReference(candidate)
      }
      if (existingIndex < 0) {
        merged += candidate
      } else {
        merged[existingIndex] = merged[existingIndex].mergeDuplicateMemoryImageReference(candidate)
      }
    }
    return merged
  }
}

internal fun MemoryRecord.imageReferences(): List<OpenCrayImageReference> =
  MemoryImageReferenceSupport.decodeFromExtensions(extensions)

private fun OpenCrayImageReference.matchesMemoryImageReference(
  other: OpenCrayImageReference,
): Boolean {
  val normalizedSha = sha256?.lowercase(Locale.US)
  val otherNormalizedSha = other.sha256?.lowercase(Locale.US)
  if (normalizedSha != null && otherNormalizedSha != null && normalizedSha == otherNormalizedSha) {
    return true
  }
  if (storageScope == other.storageScope && relativePath == other.relativePath) {
    return true
  }
  return refId == other.refId
}

private fun OpenCrayImageReference.mergeDuplicateMemoryImageReference(
  incoming: OpenCrayImageReference,
): OpenCrayImageReference = copy(
  mimeType = mimeType ?: incoming.mimeType,
  sha256 = sha256 ?: incoming.sha256,
  widthPx = widthPx ?: incoming.widthPx,
  heightPx = heightPx ?: incoming.heightPx,
  caption = caption ?: incoming.caption,
  sourceLabel = sourceLabel ?: incoming.sourceLabel,
  sourceSessionId = sourceSessionId ?: incoming.sourceSessionId,
  sourceMessageId = sourceMessageId ?: incoming.sourceMessageId,
  createdAtEpochMs = minOf(createdAtEpochMs, incoming.createdAtEpochMs),
)
