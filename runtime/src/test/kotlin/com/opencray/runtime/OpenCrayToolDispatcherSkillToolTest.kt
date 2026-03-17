package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenCrayToolDispatcherSkillToolTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun skillsListReportsStandardizedMetadata() {
    val skillsRoot = temporaryFolder.newFolder("dispatcher-skills-list-root")
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
      body = "# UI UX Pro Max",
    )
    val dispatcher = dispatcher(skillsRoot)

    val result = dispatcher.dispatch(
      task = task(),
      call = AgentToolCall(toolName = "skills_list", arguments = JsonObject(emptyMap())),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("read_skill", result.metadata["capabilityKind"])
    assertEquals("none", result.metadata["workspaceRelation"])
    assertEquals("1", result.metadata["skillCount"])
    assertTrue(result.content.contains("ui-ux-pro-max"))
  }

  @Test
  fun skillReadReportsStandardizedMetadata() {
    val skillsRoot = temporaryFolder.newFolder("dispatcher-skill-read-root")
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

        Audit the current interface first.
      """.trimIndent(),
    )
    val dispatcher = dispatcher(skillsRoot)

    val result = dispatcher.dispatch(
      task = task(),
      call = AgentToolCall(
        toolName = "skill_read",
        arguments = buildJsonObject {
          put("name", "ui-ux-pro-max")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("read_skill", result.metadata["capabilityKind"])
    assertEquals("file", result.metadata["targetKind"])
    assertEquals("none", result.metadata["workspaceRelation"])
    assertEquals("ui-ux-pro-max/SKILL.md", result.metadata["primaryTargetPath"])
    assertEquals("ui-ux-pro-max", result.metadata["skillName"])
    assertTrue(result.content.contains("Audit the current interface first."))
  }

  private fun dispatcher(skillsRoot: File): OpenCrayToolDispatcher {
    val workspaceRoot = temporaryFolder.newFolder("dispatcher-skills-workspace").toPath()
    Files.createDirectories(workspaceRoot)
    return OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        skillsRoots = listOf(skillsRoot),
      ),
    )
  }

  private fun task(): AgentTask = AgentTask(
    id = "task-skill-tool",
    type = AgentTaskType.PROMPT,
    input = "Inspect skills.",
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "TEST_ALLOW",
    ),
    createdAtEpochMs = 1L,
  )

  private fun runtimeHooks(): RuntimeExecutionHooks = RuntimeExecutionHooks(
    isCancellationRequested = { false },
    requestRetry = { _ -> Unit },
  )

  private fun writeSkill(
    root: File,
    relativeDirectory: String,
    frontMatter: String,
    body: String,
  ): File {
    val skillDirectory = root.resolve(relativeDirectory)
    Files.createDirectories(skillDirectory.toPath())
    val skillFile = skillDirectory.resolve("SKILL.md")
    val content = buildString {
      appendLine("---")
      appendLine(frontMatter)
      appendLine("---")
      appendLine(body)
    }
    Files.write(skillFile.toPath(), content.toByteArray(StandardCharsets.UTF_8))
    return skillFile
  }
}
