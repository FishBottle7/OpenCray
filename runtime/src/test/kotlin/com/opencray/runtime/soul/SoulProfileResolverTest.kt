package com.opencray.runtime.soul

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoulProfileResolverTest {
  private val resolver = SoulProfileResolver()

  @Test
  fun resolveMapsBuilderPresetIntoTypedRuntimeFields() {
    val profile = resolver.resolve(
      SoulProfileSeed(
        presetName = "builder",
        displayName = "Night Shift",
        customGuidance = "Stay terse.",
      ),
    )

    requireNotNull(profile)
    assertEquals("BUILDER", profile.presetName)
    assertEquals("Night Shift", profile.displayName)
    assertEquals(SoulTone.BUILDER, profile.tone)
    assertEquals(SoulVerbosity.TERSE, profile.verbosity)
    assertEquals(SoulPlasticity.LOW, profile.plasticity)
    assertEquals(UserRelationshipStyle.DIRECT, profile.userRelationshipStyle)
    assertEquals(RiskTolerance.BALANCED, profile.riskTolerance)
    assertEquals(ToolUseBias.TOOL_FORWARD, profile.toolUseBias)
    assertEquals("Stay terse.", profile.customGuidance)
  }

  @Test
  fun resolveAppliesTypedOverridesFromExtensions() {
    val profile = resolver.resolve(
      SoulProfileSeed(
        presetName = "steady",
        extensions = mapOf(
          "voice" to " calm but direct ",
          "preferred_naming" to "阿澄",
          "preferred_address_style" to "friendly",
          "intimacy_permission_band" to "warm",
          "playfulness_permission_band" to "familiar",
          "high_intimacy_behavior_allowed" to "true",
          "playful_affection_allowed" to "false",
          "verbosity" to "expansive",
          "plasticity" to "high",
          "risk_tolerance" to "bold",
          "tool_use_bias" to "tool forward",
          "escalation_rules" to "Log the trade-offs.\nReconfirm destructive steps.",
          "collaboration_preferences" to "Show the plan first.|Keep the user in the loop.",
          "custom_guidance" to "  Preserve technical precision.  ",
        ),
      ),
    )

    requireNotNull(profile)
    assertEquals("calm but direct", profile.voice)
    assertEquals("阿澄", profile.preferredNaming)
    assertEquals(PreferredAddressStyle.FRIENDLY, profile.preferredAddressStyle)
    assertEquals(RelationshipBand.WARM, profile.intimacyPermissionBand)
    assertEquals(RelationshipBand.FAMILIAR, profile.playfulnessPermissionBand)
    assertEquals(true, profile.highIntimacyBehaviorAllowed)
    assertEquals(false, profile.playfulAffectionAllowed)
    assertEquals(SoulVerbosity.EXPANSIVE, profile.verbosity)
    assertEquals(SoulPlasticity.HIGH, profile.plasticity)
    assertEquals(RiskTolerance.BOLD, profile.riskTolerance)
    assertEquals(ToolUseBias.TOOL_FORWARD, profile.toolUseBias)
    assertTrue(profile.escalationRules.contains("Log the trade-offs."))
    assertTrue(profile.escalationRules.contains("Reconfirm destructive steps."))
    assertTrue(profile.collaborationPreferences.contains("Show the plan first."))
    assertEquals("Preserve technical precision.", profile.customGuidance)
  }

  @Test
  fun resolveCarriesForwardExtensionBackedForbiddenBehaviors() {
    val profile = resolver.resolve(
      SoulProfileSeed(
        presetName = "warm",
        extensions = mapOf(
          "forbidden_behaviors" to "Do not shame the user.|Avoid bluffing.",
        ),
      ),
    )

    requireNotNull(profile)
    assertTrue(profile.forbiddenBehaviors.contains("Do not shame the user."))
    assertTrue(profile.forbiddenBehaviors.contains("Avoid bluffing."))
    assertTrue(profile.forbiddenBehaviors.any { it.contains("fabricate workspace facts") })
  }

  @Test
  fun resolveReturnsNullForCompletelyEmptySeed() {
    val profile = resolver.resolve(SoulProfileSeed())

    assertEquals(null, profile)
  }

  @Test
  fun resolveReturnsNullWhenSeedOnlyContainsBlankExtensions() {
    val profile = resolver.resolve(
      SoulProfileSeed(
        extensions = mapOf(
          "voice" to "   ",
          "toolUseBias" to "\n",
        ),
      ),
    )

    assertEquals(null, profile)
  }

  @Test
  fun resolveNormalizesAndDeduplicatesListEntries() {
    val profile = resolver.resolve(
      SoulProfileSeed(
        presetName = "builder",
        extensions = mapOf(
          "forbidden_behaviors" to "Avoid bluffing.| Avoid bluffing. |\nDo not hide uncertainty behind confident wording.",
        ),
      ),
    )

    requireNotNull(profile)
    assertEquals(3, profile.forbiddenBehaviors.size)
    assertTrue(profile.forbiddenBehaviors.contains("Avoid bluffing."))
  }

  @Test
  fun resolveSupportsMixedExtensionKeyFormats() {
    val profile = resolver.resolve(
      SoulProfileSeed(
        presetName = "builder",
        extensions = mapOf(
          "toolUseBias" to "tool-forward",
          "user-relationship-style" to "supportive",
          "preferred address style" to "intimate",
          "collaboration preferences" to " Keep the user posted. ",
        ),
      ),
    )

    requireNotNull(profile)
    assertEquals(ToolUseBias.TOOL_FORWARD, profile.toolUseBias)
    assertEquals(UserRelationshipStyle.SUPPORTIVE, profile.userRelationshipStyle)
    assertEquals(PreferredAddressStyle.INTIMATE, profile.preferredAddressStyle)
    assertTrue(profile.collaborationPreferences.contains("Keep the user posted."))
  }
}
