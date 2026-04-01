package com.opencray.runtime.soul

import com.opencray.runtime.OpenCraySoulVisualIdentity
import kotlinx.serialization.json.Json

object SoulVisualIdentitySupport {
  private val json: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  fun decodeFromExtensions(extensions: Map<String, String>): OpenCraySoulVisualIdentity? {
    val payload = extensions[SoulProfileExtensionKeys.VISUAL_IDENTITY_JSON]
      ?.trim()
      ?.takeIf(String::isNotEmpty)
      ?: return null
    return runCatching {
      json.decodeFromString(OpenCraySoulVisualIdentity.serializer(), payload)
    }.getOrNull()?.takeIf(OpenCraySoulVisualIdentity::isMeaningful)
  }

  fun encodeIntoExtensions(
    extensions: Map<String, String>,
    visualIdentity: OpenCraySoulVisualIdentity?,
  ): Map<String, String> {
    val filtered = extensions.toMutableMap()
    if (visualIdentity == null || !visualIdentity.isMeaningful()) {
      filtered.remove(SoulProfileExtensionKeys.VISUAL_IDENTITY_JSON)
      return filtered
    }
    filtered[SoulProfileExtensionKeys.VISUAL_IDENTITY_JSON] = json.encodeToString(
      OpenCraySoulVisualIdentity.serializer(),
      visualIdentity,
    )
    return filtered
  }
}
