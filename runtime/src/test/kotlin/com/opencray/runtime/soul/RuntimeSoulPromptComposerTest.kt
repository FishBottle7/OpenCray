package com.opencray.runtime.soul

import com.opencray.runtime.context.RuntimeSoulProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSoulPromptComposerTest {
  private val composer = RuntimeSoulPromptComposer()

  @Test
  fun composeBuildsStructuredPromptFromRuntimeSoulProfile() {
    val rendered = composer.compose(
      RuntimeSoulProfile(
        presetName = "builder",
        displayName = "Night Shift",
        voice = "calm but direct",
        customGuidance = "Stay terse.",
        extensions = mapOf(
          "preferred_naming" to "阿澄",
          "preferred_address_style" to "friendly",
          "warmth_preference_offset" to "1",
          "initiative_preference_offset" to "1",
          "playfulness_preference_offset" to "1",
          "reassurance_preference_offset" to "-1",
          "intimacy_permission_band" to "warm",
          "playfulness_permission_band" to "familiar",
          "supportive_reassurance_allowed" to "true",
          "proactive_relational_check_in_allowed" to "true",
          "light_playfulness_allowed" to "true",
          "playful_teasing_allowed" to "false",
          "high_intimacy_behavior_allowed" to "true",
          "playful_affection_allowed" to "false",
          "verbosity" to "expansive",
          "tool_use_bias" to "tool_forward",
          "escalation_rules" to "Raise blockers early.|Reconfirm destructive steps.",
        ),
      ),
    )

    assertTrue(rendered.contains("display_name=Night Shift"))
    assertTrue(rendered.contains("preset=BUILDER"))
    assertTrue(rendered.contains("voice=calm but direct"))
    assertTrue(rendered.contains("preferred_naming=阿澄"))
    assertTrue(rendered.contains("preferred_address_style=friendly"))
    assertTrue(rendered.contains("warmth_preference_offset=1"))
    assertTrue(rendered.contains("initiative_preference_offset=1"))
    assertTrue(rendered.contains("playfulness_preference_offset=1"))
    assertTrue(rendered.contains("reassurance_preference_offset=-1"))
    assertTrue(rendered.contains("intimacy_permission_band=warm"))
    assertTrue(rendered.contains("playfulness_permission_band=familiar"))
    assertTrue(rendered.contains("supportive_reassurance_allowed=true"))
    assertTrue(rendered.contains("proactive_relational_check_in_allowed=true"))
    assertTrue(rendered.contains("light_playfulness_allowed=true"))
    assertTrue(rendered.contains("playful_teasing_allowed=false"))
    assertTrue(rendered.contains("high_intimacy_behavior_allowed=true"))
    assertTrue(rendered.contains("playful_affection_allowed=false"))
    assertTrue(rendered.contains("verbosity=expansive"))
    assertTrue(rendered.contains("tool_use_bias=tool_forward"))
    assertTrue(rendered.contains("- Raise blockers early."))
    assertTrue(rendered.contains("behavior_guidance:"))
    assertTrue(rendered.contains("- For task-bearing requests, keep concrete progress primary; relational tone should support the work rather than replace it."))
    assertTrue(rendered.contains("- Use added initiative first on useful task clarifications, next steps, and blockers; keep relational check-ins brief and secondary."))
    assertTrue(rendered.contains("- Brief proactive relational check-ins are allowed when they are clearly relevant and do not derail the task."))
    assertTrue(rendered.contains("- Do not tease the user."))
    assertTrue(rendered.contains("- Do not default to soothing reassurance unless the user clearly needs it."))
    assertTrue(rendered.contains("custom_guidance=Stay terse."))
  }

  @Test
  fun composeReturnsBlankForEmptyRuntimeSoulProfile() {
    assertEquals("", composer.compose(RuntimeSoulProfile()))
    assertEquals("", composer.compose(null))
  }

  @Test
  fun composeNormalizesMixedExtensionKeyFormatsEndToEnd() {
    val rendered = composer.compose(
      RuntimeSoulProfile(
        presetName = "steady",
        extensions = mapOf(
          "toolUseBias" to "tool-forward",
          "collaboration preferences" to " Keep the user posted. ",
          "voice" to " calm \n direct ",
        ),
      ),
    )

    assertTrue(rendered.contains("tool_use_bias=tool_forward"))
    assertTrue(rendered.contains("voice=calm direct"))
    assertTrue(rendered.contains("- Keep the user posted."))
  }
}
