package com.opencray.runtime.subagent

import org.junit.Assert.assertEquals
import org.junit.Test

class SubAgentContextPolicyTest {
  @Test
  fun resolveFallsBackToProfileDefaultWhenPolicyIsEmpty() {
    val resolution = SubAgentContextPolicy().resolve(
      profile = BuiltInSubAgentProfiles.researcher,
    )

    assertEquals(SubAgentContextMode.MINIMAL, resolution.mode)
    assertEquals(SubAgentContextModeResolutionSource.PROFILE_DEFAULT, resolution.source)
  }

  @Test
  fun resolveUsesPolicyDefaultWhenPresent() {
    val resolution = SubAgentContextPolicy(
      defaultContextMode = SubAgentContextMode.MINIMAL,
    ).resolve(
      profile = BuiltInSubAgentProfiles.generalPurpose,
    )

    assertEquals(SubAgentContextMode.MINIMAL, resolution.mode)
    assertEquals(SubAgentContextModeResolutionSource.POLICY_DEFAULT, resolution.source)
  }

  @Test
  fun resolveUsesProfileOverrideAndNormalizesBuiltInAliases() {
    val resolution = SubAgentContextPolicy(
      profileOverrides = mapOf("explorer" to SubAgentContextMode.DELEGATED),
    ).resolve(
      profile = BuiltInSubAgentProfiles.researcher,
    )

    assertEquals(SubAgentContextMode.DELEGATED, resolution.mode)
    assertEquals(SubAgentContextModeResolutionSource.POLICY_PROFILE_OVERRIDE, resolution.source)
  }

  @Test
  fun resolveGivesExplicitModeHighestPriority() {
    val resolution = SubAgentContextPolicy(
      defaultContextMode = SubAgentContextMode.MINIMAL,
      profileOverrides = mapOf("general-purpose" to SubAgentContextMode.MINIMAL),
    ).resolve(
      profile = BuiltInSubAgentProfiles.generalPurpose,
      explicitMode = SubAgentContextMode.DELEGATED,
    )

    assertEquals(SubAgentContextMode.DELEGATED, resolution.mode)
    assertEquals(SubAgentContextModeResolutionSource.EXPLICIT_REQUEST, resolution.source)
  }
}
