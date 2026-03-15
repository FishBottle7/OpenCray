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
          "verbosity" to "expansive",
          "tool_use_bias" to "tool_forward",
          "escalation_rules" to "Raise blockers early.|Reconfirm destructive steps.",
        ),
      ),
    )

    assertTrue(rendered.contains("display_name=Night Shift"))
    assertTrue(rendered.contains("preset=BUILDER"))
    assertTrue(rendered.contains("voice=calm but direct"))
    assertTrue(rendered.contains("verbosity=expansive"))
    assertTrue(rendered.contains("tool_use_bias=tool_forward"))
    assertTrue(rendered.contains("- Raise blockers early."))
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
