package com.opencray.runtime.subagent

import java.util.Locale

data class SubAgentContextModeResolution(
  val mode: SubAgentContextMode,
  val source: SubAgentContextModeResolutionSource,
)

enum class SubAgentContextModeResolutionSource(
  val wireValue: String,
) {
  EXPLICIT_REQUEST("explicit_request"),
  POLICY_PROFILE_OVERRIDE("policy_profile_override"),
  POLICY_DEFAULT("policy_default"),
  PROFILE_DEFAULT("profile_default");

  companion object {
    fun fromWireValue(value: String?): SubAgentContextModeResolutionSource? {
      val normalized = value
        ?.trim()
        ?.lowercase(Locale.US)
        ?.takeIf(String::isNotBlank)
        ?: return null
      return values().firstOrNull { source -> source.wireValue == normalized }
    }
  }
}

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
      return SubAgentContextModeResolution(
        mode = mode,
        source = SubAgentContextModeResolutionSource.EXPLICIT_REQUEST,
      )
    }
    normalizedProfileOverrides[normalizeProfileId(profile.id)]?.let { mode ->
      return SubAgentContextModeResolution(
        mode = mode,
        source = SubAgentContextModeResolutionSource.POLICY_PROFILE_OVERRIDE,
      )
    }
    defaultContextMode?.let { mode ->
      return SubAgentContextModeResolution(
        mode = mode,
        source = SubAgentContextModeResolutionSource.POLICY_DEFAULT,
      )
    }
    return SubAgentContextModeResolution(
      mode = profile.defaultContextMode,
      source = SubAgentContextModeResolutionSource.PROFILE_DEFAULT,
    )
  }

  private fun normalizeProfileId(profileId: String): String {
    val normalized = profileId.trim().lowercase(Locale.US)
    return BuiltInSubAgentProfiles.resolve(normalized)?.id?.lowercase(Locale.US) ?: normalized
  }
}
