package com.opencray.runtime.skills

import com.opencray.skills.SkillExecutionContext
import com.opencray.skills.SkillInvocationControl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillInventoryPromptLayerTest {
  @Test
  fun renderMinimalKeepsOnlyTopEntriesWithoutVerboseMetadata() {
    val layer = SkillInventoryPromptLayer(
      config = SkillInventoryPromptLayerConfig(
        maxSkills = 5,
        maxDescriptionChars = 120,
        maxCompactSkills = 3,
        maxCompactDescriptionChars = 72,
        maxMinimalSkills = 1,
        maxMinimalDescriptionChars = 48,
      ),
    )
    val inventory = SkillInventory(
      skills = listOf(
        VisibleSkill(
          name = "fun-brainstorming",
          description = "Use before architecture work to compare multiple concrete rollout options.",
          relativePath = ".codex/skills/fun-brainstorming/SKILL.md",
          invocationControl = SkillInvocationControl.EXPLICIT_AND_IMPLICIT,
          userInvocable = true,
          executionContext = SkillExecutionContext.FORK,
        ),
        VisibleSkill(
          name = "humanizer",
          description = "Rewrite text so it reads more naturally and less machine-generated.",
          relativePath = ".codex/skills/humanizer/SKILL.md",
          invocationControl = SkillInvocationControl.EXPLICIT_ONLY,
          userInvocable = true,
          executionContext = SkillExecutionContext.INLINE,
        ),
      ),
      invalidSkillCount = 1,
    )

    val rendered = layer.render(
      inventory = inventory,
      detailMode = SkillInventoryPromptDetailMode.MINIMAL,
    )

    assertTrue(rendered.text.contains("Visible skills:"))
    assertTrue(rendered.text.contains("name=fun-brainstorming"))
    assertTrue(rendered.text.contains("invocation=explicit-and-implicit"))
    assertTrue(rendered.text.contains("path=.codex/skills/fun-brainstorming/SKILL.md"))
    assertFalse(rendered.text.contains("description="))
    assertFalse(rendered.text.contains("user_invocable="))
    assertFalse(rendered.text.contains("execution_context="))
    assertTrue(rendered.text.contains("+ 1 more visible skill(s)."))
    assertTrue(rendered.text.contains("Ignored 1 invalid skill file(s)."))
  }
}
