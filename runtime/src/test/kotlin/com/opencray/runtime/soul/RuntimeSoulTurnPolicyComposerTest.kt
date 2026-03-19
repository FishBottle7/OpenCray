package com.opencray.runtime.soul

import com.opencray.runtime.context.RuntimeSoulProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSoulTurnPolicyComposerTest {
  private val composer = RuntimeSoulTurnPolicyComposer()

  @Test
  fun composeRendersDeterministicTurnPolicyFromRuntimeSoulAndSignal() {
    val rendered = composer.compose(
      profile = RuntimeSoulProfile(
        presetName = "WARM",
        extensions = mapOf(
          SoulProfileExtensionKeys.INITIATIVE_PREFERENCE_OFFSET to "1",
          SoulProfileExtensionKeys.REASSURANCE_PREFERENCE_OFFSET to "1",
          SoulProfileExtensionKeys.SUPPORTIVE_REASSURANCE_ALLOWED to "true",
          SoulProfileExtensionKeys.PROACTIVE_RELATIONAL_CHECK_IN_ALLOWED to "true",
          SoulProfileExtensionKeys.LIGHT_PLAYFULNESS_ALLOWED to "true",
        ),
      ),
      signal = SoulTurnSemanticSignal(
        isTaskBearingRequest = true,
        userAffect = SoulTurnUserAffect.STRAINED,
        clarificationNeeded = true,
      ),
    )

    assertTrue(rendered.contains("task_priority=task_first"))
    assertTrue(rendered.contains("response_shape=short_support_then_answer"))
    assertTrue(rendered.contains("clarification_mode=proactive_task_focused"))
    assertTrue(rendered.contains("reassurance_mode=brief_grounded"))
    assertTrue(rendered.contains("relational_check_in_mode=secondary_only"))
    assertTrue(rendered.contains("directives:"))
    assertTrue(rendered.contains("Lead with useful task progress before optional relational add-ons."))
  }

  @Test
  fun composeReturnsEmptyWhenSignalIsMissing() {
    assertEquals(
      "",
      composer.compose(
        profile = RuntimeSoulProfile(presetName = "STEADY"),
        signal = null,
      ),
    )
  }
}
