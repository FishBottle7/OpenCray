package com.opencray.runtime.skills

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveSkillPromptLayerTest {
  @Test
  fun renderMinimalKeepsCoreMetadataAndRestrictionsWithShorterInstructions() {
    val layer = ActiveSkillPromptLayer()
    val capsule = ActiveSkillCapsule(
      name = "ui-ux-pro-max",
      description = "High-end UI review workflow.",
      relativePath = ".codex/skills/ui-ux-pro-max/SKILL.md",
      invocationControl = "explicit-only",
      executionContext = "fork",
      activationSource = "skill_read",
      pinned = true,
      markdownBody = """
        # UI UX Pro Max

        1. Audit the current interface in detail.
        2. Produce a concrete design system.
        3. Verify the implementation against the design system.
        4. Document the remaining gaps clearly.
      """.trimIndent(),
      toolPermissionSummary = listOf("read:allow", "write:allow", "search:allow"),
      allowedToolKeys = setOf("read", "write", "search"),
    )

    val rendered = layer.render(
      capsule = capsule,
      detailMode = ActiveSkillPromptDetailMode.MINIMAL,
    )

    assertTrue(rendered.text.contains("name=ui-ux-pro-max"))
    assertTrue(rendered.text.contains("activation_source=skill_read"))
    assertTrue(rendered.text.contains("pinned=true"))
    assertTrue(rendered.text.contains("allowed_tools=read,search,write"))
    assertTrue(rendered.text.contains("[Instructions]"))
    assertFalse(rendered.text.contains("- description="))
    assertFalse(rendered.text.contains("tool_permissions=read:allow,write:allow,search:allow"))
    assertTrue(rendered.trace.pinned)
    assertTrue(rendered.trace.truncated)
  }
}
