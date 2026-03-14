package com.opencray.runtime.soul

import com.opencray.runtime.context.RuntimeSoulProfile

class RuntimeSoulProfileSeedFactory {
  fun create(profile: RuntimeSoulProfile?): SoulProfileSeed? {
    if (profile == null) {
      return null
    }

    val presetName = profile.presetName.normalizedScalarOrNull()?.uppercase()
    val displayName = profile.displayName.normalizedScalarOrNull()
    val customGuidance = profile.customGuidance.normalizedScalarOrNull()
    val extensions = buildMap {
      profile.voice.normalizedScalarOrNull()?.let { voice ->
        put("voice", voice)
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

  private fun String?.normalizedScalarOrNull(): String? =
    this
      ?.replace(Regex("\\s+"), " ")
      ?.trim()
      ?.takeIf(String::isNotEmpty)
}
