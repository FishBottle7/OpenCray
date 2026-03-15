package com.opencray.runtime.soul

import com.opencray.runtime.context.RuntimeSoulProfile

class RuntimeSoulProfileSeedFactory {
  fun create(profile: RuntimeSoulProfile?): SoulProfileSeed? {
    if (profile == null) {
      return null
    }

    val presetName = normalizeSoulScalarOrNull(profile.presetName)?.uppercase()
    val displayName = normalizeSoulScalarOrNull(profile.displayName)
    val customGuidance = normalizeSoulScalarOrNull(profile.customGuidance)
    val extensions = normalizeSoulExtensions(profile.extensions).toMutableMap().apply {
      normalizeSoulScalarOrNull(profile.voice)?.let { voice ->
        put(SoulProfileExtensionKeys.VOICE, voice)
      }
    }

    if (presetName == null && displayName == null && customGuidance == null && extensions.isEmpty()) {
      return null
    }

    return SoulProfileSeed(
      presetName = presetName,
      displayName = displayName,
      customGuidance = customGuidance,
      extensions = extensions,
    )
  }
}
