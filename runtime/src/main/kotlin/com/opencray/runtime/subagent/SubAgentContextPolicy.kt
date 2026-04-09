package com.opencray.runtime.subagent

import java.util.Locale

data class SubAgentContextModeResolution(
  val mode: SubAgentContextMode,
)

data class SubAgentContextPolicy(
  val defaultContextMode: SubAgentContextMode? = null,
  val profileOverrides: Map<String, SubAgentContextMode> = emptyMap(),
) {
  init {
    require(profileOverrides.keys.all { key -> key.trim().isNotBlank() }) {
      "SubAgentContextPolicy profileOverrides keys must not be blank."
    }
  }

  private val normalizedProfileOverrides: Map<String, SubAgentContextMode> = profileOverrides
    .entries
    .associate { (profileId, mode) ->
      normalizeProfileId(profileId) to mode
    }

  fun resolve(
    profile: SubAgentProfile,
    explicitMode: SubAgentContextMode? = null,
  ): SubAgentContextModeResolution {
    explicitMode?.let { mode ->
      return SubAgentContextModeResolution(mode = mode)
    }
    normalizedProfileOverrides[normalizeProfileId(profile.id)]?.let { mode ->
      return SubAgentContextModeResolution(mode = mode)
    }
    defaultContextMode?.let { mode ->
      return SubAgentContextModeResolution(mode = mode)
    }
    return SubAgentContextModeResolution(mode = profile.defaultContextMode)
  }

  private fun normalizeProfileId(profileId: String): String {
    val normalized = profileId.trim().lowercase(Locale.US)
    return BuiltInSubAgentProfiles.resolve(normalized)?.id?.lowercase(Locale.US) ?: normalized
  }
}
