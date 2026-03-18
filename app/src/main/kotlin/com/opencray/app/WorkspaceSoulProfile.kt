package com.opencray.app

import com.opencray.runtime.context.RuntimeSoulProfile

internal data class WorkspaceSoulProfile(
  val presetName: String,
  val customLabel: String,
  val customGuidance: String,
  val extensions: Map<String, String> = emptyMap(),
)

internal fun WorkspaceSoulProfile.toRuntimeSoulProfile(): RuntimeSoulProfile =
  RuntimeSoulProfile(
    presetName = presetName.ifBlank { null },
    displayName = customLabel.ifBlank { null },
    customGuidance = customGuidance.ifBlank { null },
    extensions = extensions
      .mapNotNull { (rawKey, rawValue) ->
        val key = rawKey.trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
        val value = rawValue.trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
        val normalizedKey = PersonalizationSoulExtensionFactory.normalizeKey(key) ?: return@mapNotNull null
        if (normalizedKey in RESERVED_RUNTIME_SOUL_PROFILE_KEYS) {
          return@mapNotNull null
        }
        key to value
      }
      .toMap(linkedMapOf()),
  )

private val RESERVED_RUNTIME_SOUL_PROFILE_KEYS: Set<String> = setOf(
  "preset",
  "custom_guidance",
)
