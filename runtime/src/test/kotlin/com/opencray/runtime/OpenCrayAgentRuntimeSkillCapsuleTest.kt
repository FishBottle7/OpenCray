package com.opencray.runtime

import com.opencray.core.contracts.ExecutionStatus
import com.opencray.runtime.context.AgentRuntimeSessionContext
import com.opencray.runtime.skills.SkillCatalogResolver
import com.opencray.runtime.skills.SkillInventory
import com.opencray.runtime.skills.SkillInventoryTrace
import com.opencray.runtime.skills.VisibleSkill
import com.opencray.runtime.skills.VisibleSkillTrace
import com.opencray.skills.SkillExecutionContext
import com.opencray.skills.SkillInvocationControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCrayAgentRuntimeSkillCapsuleTest : OpenCrayAgentRuntimeTestBase() {
  @Test
  fun runPromptTaskExposesSkillInventoryMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("agent-skill-inventory-metadata")
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"Used the visible skill inventory."}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        sessionContext = AgentRuntimeSessionContext(
          skillInventory = SkillInventory(
            skills = listOf(
              VisibleSkill(
                name = "ui-ux-pro-max",
                description = "High-end UI review workflow.",
                relativePath = ".codex/skills/ui-ux-pro-max/SKILL.md",
                invocationControl = SkillInvocationControl.EXPLICIT_ONLY,
                userInvocable = true,
                executionContext = SkillExecutionContext.INLINE,
              ),
              VisibleSkill(
                name = "fun-brainstorming",
                description = "Fast architectural brainstorming workflow.",
                relativePath = ".codex/skills/fun-brainstorming/SKILL.md",
                invocationControl = SkillInvocationControl.EXPLICIT_AND_IMPLICIT,
                userInvocable = true,
                executionContext = SkillExecutionContext.FORK,
              ),
            ),
            invalidSkillCount = 1,
            trace = SkillInventoryTrace(
              visible = listOf(
                VisibleSkillTrace(
                  name = "ui-ux-pro-max",
                  relativePath = ".codex/skills/ui-ux-pro-max/SKILL.md",
                  invocationControl = "explicit-only",
                  userInvocable = true,
                  executionContext = "inline",
                  descriptionPreview = "High-end UI review workflow.",
                ),
                VisibleSkillTrace(
                  name = "fun-brainstorming",
                  relativePath = ".codex/skills/fun-brainstorming/SKILL.md",
                  invocationControl = "explicit-and-implicit",
                  userInvocable = true,
                  executionContext = "fork",
                  descriptionPreview = "Fast architectural brainstorming workflow.",
                ),
              ),
              totalVisibleSkillCount = 2,
              implicitSkillCount = 1,
              invalidSkillCount = 1,
            ),
          ),
        ),
      ),
      clock = IncrementingClock(start = 2_650L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Use the right skill workflow before answering."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("2", result.metadata["contextVisibleSkillCount"])
    assertEquals("2", result.metadata["contextInjectedSkillCount"])
    assertEquals("0", result.metadata["contextOmittedSkillCount"])
    assertEquals("1", result.metadata["contextImplicitSkillCount"])
    assertEquals("1", result.metadata["contextInvalidSkillCount"])
    assertEquals(
      "ui-ux-pro-max@.codex/skills/ui-ux-pro-max/SKILL.md[explicit-only|true|inline];" +
        "fun-brainstorming@.codex/skills/fun-brainstorming/SKILL.md[explicit-and-implicit|true|fork]",
      result.metadata["contextVisibleSkillSummary"],
    )
    assertTrue(gateway.requests.single().prompt.contains("[Skill Inventory]"))
    assertTrue(gateway.requests.single().prompt.contains("name=ui-ux-pro-max"))
  }

  @Test
  fun runPromptTaskPromotesReadSkillIntoActiveCapsule() {
    val workspaceRoot = temporaryFolder.newFolder("agent-active-skill-workspace")
    val skillsRoot = temporaryFolder.newFolder("agent-active-skill-root")
    writeSkill(
      root = skillsRoot,
      relativeDirectory = "ui-ux-pro-max",
      frontMatter = """
        name: ui-ux-pro-max
        description: High-end UI review workflow.
        invocation-control: explicit-only
        user-invocable: true
        allowed-tools: [ read, write ]
      """.trimIndent(),
      body = """
        # UI UX Pro Max

        Audit the current interface first, then apply a concrete design system.
      """.trimIndent(),
    )
    val skillCatalog = SkillCatalogResolver().resolve(listOf(skillsRoot))
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"skill_read","arguments":{"name":"ui-ux-pro-max"}}""",
        """{"type":"final","answer":"Used the active skill capsule."}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
          skillsRoots = listOf(skillsRoot),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        sessionContext = AgentRuntimeSessionContext(
          skillInventory = skillCatalog.inventory,
          skillCatalog = skillCatalog,
        ),
      ),
      clock = IncrementingClock(start = 2_700L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Load the UI skill, then follow it."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("ui-ux-pro-max", result.metadata["contextActiveSkillName"])
    assertEquals("skill_read", result.metadata["contextActiveSkillActivationSource"])
    assertEquals("false", result.metadata["contextActiveSkillPinned"])
    assertEquals("true", result.metadata["contextActiveSkillToolRestrictionEnabled"])
    assertEquals("read,write", result.metadata["contextActiveSkillAllowedTools"])
    assertEquals(2, gateway.requests.size)
    assertTrue(gateway.requests[1].prompt.contains("[Active Skill]"))
    assertTrue(gateway.requests[1].prompt.contains("name=ui-ux-pro-max"))
    assertTrue(gateway.requests[1].prompt.contains("Audit the current interface first"))
    assertTrue(gateway.requests[1].prompt.contains("- Read:"))
    assertFalse(gateway.requests[1].prompt.contains("- Bash:"))
  }

  @Test
  fun runPromptTaskPromotesReadSkillAsPinnedOnlyWhenRequested() {
    val workspaceRoot = temporaryFolder.newFolder("agent-active-skill-pinned-workspace")
    val skillsRoot = temporaryFolder.newFolder("agent-active-skill-pinned-root")
    writeSkill(
      root = skillsRoot,
      relativeDirectory = "ui-ux-pro-max",
      frontMatter = """
        name: ui-ux-pro-max
        description: High-end UI review workflow.
        invocation-control: explicit-only
        user-invocable: true
        allowed-tools: [ read, write ]
      """.trimIndent(),
      body = "# UI UX Pro Max\n\nAudit the interface.",
    )
    val skillCatalog = SkillCatalogResolver().resolve(listOf(skillsRoot))
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"skill_read","arguments":{"name":"ui-ux-pro-max","pin":true}}""",
        """{"type":"final","answer":"Used pinned skill."}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
          skillsRoots = listOf(skillsRoot),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        sessionContext = AgentRuntimeSessionContext(
          skillInventory = skillCatalog.inventory,
          skillCatalog = skillCatalog,
        ),
      ),
      clock = IncrementingClock(start = 2_750L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Load the UI skill as pinned, then follow it."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("ui-ux-pro-max", result.metadata["contextActiveSkillName"])
    assertEquals("true", result.metadata["contextActiveSkillPinned"])
    assertTrue(gateway.requests[1].prompt.contains("[Active Skill]"))
    assertTrue(gateway.requests[1].prompt.contains("pinned=true"))
  }

  @Test
  fun runPromptTaskExecutesInlineSkillAsActiveCapsule() {
    val workspaceRoot = temporaryFolder.newFolder("agent-skill-execute-inline-workspace")
    val skillsRoot = temporaryFolder.newFolder("agent-skill-execute-inline-root")
    writeSkill(
      root = skillsRoot,
      relativeDirectory = "review-skill",
      frontMatter = """
        name: review-skill
        description: Review workflow.
        invocation-control: explicit-and-implicit
        execution-context: inline
        user-invocable: true
        allowed-tools: [ read ]
      """.trimIndent(),
      body = "# Review Skill\n\nRead first, then answer.",
    )
    val skillCatalog = SkillCatalogResolver().resolve(listOf(skillsRoot))
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"skill_execute","arguments":{"name":"review-skill"}}""",
        """{"type":"final","answer":"Used skill_execute."}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
          skillsRoots = listOf(skillsRoot),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        sessionContext = AgentRuntimeSessionContext(
          skillInventory = skillCatalog.inventory,
          skillCatalog = skillCatalog,
        ),
      ),
      clock = IncrementingClock(start = 2_760L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Execute the review skill."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("review-skill", result.metadata["contextActiveSkillName"])
    assertEquals("skill_execute", result.metadata["contextActiveSkillActivationSource"])
    assertEquals("false", result.metadata["contextActiveSkillPinned"])
    assertTrue(gateway.requests[1].prompt.contains("[Active Skill]"))
    assertTrue(gateway.requests[1].prompt.contains("name=review-skill"))
  }

  @Test
  fun runPromptTaskBlocksDisallowedToolWhenActiveSkillRestrictsTools() {
    val workspaceRoot = temporaryFolder.newFolder("agent-active-skill-policy-workspace")
    val skillsRoot = temporaryFolder.newFolder("agent-active-skill-policy-root")
    writeSkill(
      root = skillsRoot,
      relativeDirectory = "ui-ux-pro-max",
      frontMatter = """
        name: ui-ux-pro-max
        description: High-end UI review workflow.
        invocation-control: explicit-only
        user-invocable: true
        allowed-tools: [ read, write ]
      """.trimIndent(),
      body = """
        # UI UX Pro Max

        Stay within the read/write design workflow.
      """.trimIndent(),
    )
    val skillCatalog = SkillCatalogResolver().resolve(listOf(skillsRoot))
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"skill_read","arguments":{"name":"ui-ux-pro-max"}}""",
        """{"type":"tool_call","tool_name":"Bash","arguments":{"command":"git status"}}""",
        """{"type":"final","answer":"Stopped after the skill policy blocked Bash."}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
          skillsRoots = listOf(skillsRoot),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        sessionContext = AgentRuntimeSessionContext(
          skillInventory = skillCatalog.inventory,
          skillCatalog = skillCatalog,
        ),
      ),
      clock = IncrementingClock(start = 2_800L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Load the UI skill and then try Bash anyway."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("ui-ux-pro-max", result.metadata["contextActiveSkillName"])
    assertEquals(3, gateway.requests.size)
    assertTrue(gateway.requests[2].prompt.contains("SKILL_TOOL_POLICY_BLOCKED"))
    assertTrue(gateway.requests[2].prompt.contains("outside the active allowlist"))
  }
}
