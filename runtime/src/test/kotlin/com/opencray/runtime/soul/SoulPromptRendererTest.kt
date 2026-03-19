package com.opencray.runtime.soul

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoulPromptRendererTest {
  private val renderer = SoulPromptRenderer()

  @Test
  fun renderProducesStructuredSectionsForTypedSoulProfile() {
    val rendered = renderer.render(
      SoulProfile(
        displayName = "Night Shift",
        presetName = "BUILDER",
        voice = "calm but direct",
        preferredNaming = "阿澄",
        preferredAddressStyle = PreferredAddressStyle.FRIENDLY,
        warmthPreferenceOffset = 1,
        formalityPreferenceOffset = -1,
        initiativePreferenceOffset = 1,
        playfulnessPreferenceOffset = 1,
        reassurancePreferenceOffset = -1,
        intimacyPermissionBand = RelationshipBand.WARM,
        playfulnessPermissionBand = RelationshipBand.FAMILIAR,
        supportiveReassuranceAllowed = true,
        proactiveRelationalCheckInAllowed = false,
        lightPlayfulnessAllowed = true,
        playfulTeasingAllowed = false,
        highIntimacyBehaviorAllowed = true,
        playfulAffectionAllowed = false,
        tone = SoulTone.BUILDER,
        verbosity = SoulVerbosity.TERSE,
        userRelationshipStyle = UserRelationshipStyle.DIRECT,
        riskTolerance = RiskTolerance.BALANCED,
        toolUseBias = ToolUseBias.TOOL_FORWARD,
        escalationRules = listOf("Raise blockers early."),
        forbiddenBehaviors = listOf("Do not bluff."),
        collaborationPreferences = listOf("Stay implementation-first."),
        customGuidance = "Stay terse.",
      ),
    )

    assertTrue(rendered.contains("display_name=Night Shift"))
    assertTrue(rendered.contains("preset=BUILDER"))
    assertTrue(rendered.contains("voice=calm but direct"))
    assertTrue(rendered.contains("preferred_naming=阿澄"))
    assertTrue(rendered.contains("preferred_address_style=friendly"))
    assertTrue(rendered.contains("warmth_preference_offset=1"))
    assertTrue(rendered.contains("formality_preference_offset=-1"))
    assertTrue(rendered.contains("initiative_preference_offset=1"))
    assertTrue(rendered.contains("playfulness_preference_offset=1"))
    assertTrue(rendered.contains("reassurance_preference_offset=-1"))
    assertTrue(rendered.contains("intimacy_permission_band=warm"))
    assertTrue(rendered.contains("playfulness_permission_band=familiar"))
    assertTrue(rendered.contains("supportive_reassurance_allowed=true"))
    assertTrue(rendered.contains("proactive_relational_check_in_allowed=false"))
    assertTrue(rendered.contains("light_playfulness_allowed=true"))
    assertTrue(rendered.contains("playful_teasing_allowed=false"))
    assertTrue(rendered.contains("high_intimacy_behavior_allowed=true"))
    assertTrue(rendered.contains("playful_affection_allowed=false"))
    assertTrue(rendered.contains("tone=builder"))
    assertTrue(rendered.contains("verbosity=terse"))
    assertTrue(rendered.contains("escalation_rules:"))
    assertTrue(rendered.contains("- Raise blockers early."))
    assertTrue(rendered.contains("behavior_guidance:"))
    assertTrue(rendered.contains("- For task-bearing requests, keep concrete progress primary; relational tone should support the work rather than replace it."))
    assertTrue(rendered.contains("- Lean slightly warmer than the base soul, but stay within the current relationship ceiling."))
    assertTrue(rendered.contains("- Use added initiative for task progress only, not for relationship-oriented follow-up."))
    assertTrue(rendered.contains("- Do not start proactive relational check-ins or bonding-oriented questions on your own."))
    assertTrue(rendered.contains("- Light playfulness is allowed when it stays context-aware, safe, and non-disruptive."))
    assertTrue(rendered.contains("- Do not default to soothing reassurance unless the user clearly needs it."))
    assertFalse(rendered.contains("- Avoid adding playful joking or banter on your own."))
    assertTrue(rendered.contains("custom_guidance=Stay terse."))
  }

  @Test
  fun renderReturnsBlankForNullOrMeaninglessProfile() {
    assertEquals("", renderer.render(null))
    assertEquals("", renderer.render(SoulProfile()))
  }

  @Test
  fun renderNormalizesWhitespaceInScalarAndListFields() {
    val rendered = renderer.render(
      SoulProfile(
        voice = " calm   but \n direct ",
        escalationRules = listOf(" Keep   the user\nposted "),
      ),
    )

    assertTrue(rendered.contains("voice=calm but direct"))
    assertTrue(rendered.contains("- Keep the user posted"))
  }
}
