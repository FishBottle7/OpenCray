package com.opencray.runtime.soul

import org.junit.Assert.assertEquals
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
        intimacyPermissionBand = RelationshipBand.WARM,
        playfulnessPermissionBand = RelationshipBand.FAMILIAR,
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
    assertTrue(rendered.contains("intimacy_permission_band=warm"))
    assertTrue(rendered.contains("playfulness_permission_band=familiar"))
    assertTrue(rendered.contains("high_intimacy_behavior_allowed=true"))
    assertTrue(rendered.contains("playful_affection_allowed=false"))
    assertTrue(rendered.contains("tone=builder"))
    assertTrue(rendered.contains("verbosity=terse"))
    assertTrue(rendered.contains("escalation_rules:"))
    assertTrue(rendered.contains("- Raise blockers early."))
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
