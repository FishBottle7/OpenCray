package com.opencray.app

import com.opencray.runtime.OpenCraySoulVisualIdentity
import com.opencray.runtime.context.RuntimeSoulProfile
import com.opencray.runtime.soul.SoulVisualIdentitySupport

internal data class WorkspaceSoulProfile(
  val presetName: String,
  val customLabel: String,
  val customGuidance: String,
  val visualIdentity: OpenCraySoulVisualIdentity? = null,
  val extensions: Map<String, String> = emptyMap(),
)

internal fun WorkspaceSoulProfile.toRuntimeSoulProfile(): RuntimeSoulProfile =
  RuntimeSoulProfile(
    presetName = presetName.ifBlank { null },
    displayName = customLabel.ifBlank { null },
    customGuidance = customGuidance.ifBlank { null },
    extensions = SoulVisualIdentitySupport.encodeIntoExtensions(
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
      visualIdentity = visualIdentity,
    ),
  )

private val RESERVED_RUNTIME_SOUL_PROFILE_KEYS: Set<String> = setOf(
  "preset",
  "custom_guidance",
)
