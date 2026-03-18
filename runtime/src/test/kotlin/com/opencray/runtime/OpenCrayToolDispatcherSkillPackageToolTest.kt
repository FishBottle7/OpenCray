package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.runtime.skills.SkillInstallManifestStore
import com.opencray.runtime.skills.SkillPackageManager
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenCrayToolDispatcherSkillPackageToolTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun skillsFindListsCatalogPackagesWithUnifiedMetadata() {
    val packageManager = packageManager()
    writeSkill(
      root = packageManager.catalogRootPath(),
      relativeDirectory = "find-skills",
      frontMatter = """
        name: find-skills
        description: Discover skills from the local catalog.
      """.trimIndent(),
      body = "Use the local catalog first.",
    )
    val dispatcher = dispatcher(packageManager)

    val result = dispatcher.dispatch(
      task = task(),
      call = AgentToolCall(toolName = "SkillsFind", arguments = JsonObject(emptyMap())),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("read_skill_package", result.metadata["capabilityKind"])
    assertEquals("outside_workspace", result.metadata["workspaceRelation"])
    assertEquals("1", result.metadata["resultCount"])
    assertTrue(result.content.contains("find-skills"))
  }

  @Test
  fun skillsListReportsManagedInstallations() {
    val packageManager = packageManager()
    writeSkill(
      root = packageManager.catalogRootPath(),
      relativeDirectory = "find-skills",
      frontMatter = """
        name: find-skills
        description: Discover skills from the local catalog.
      """.trimIndent(),
      body = "Use the local catalog first.",
    )
    requireNotNull(packageManager.installFromCatalog("find-skills"))
    val dispatcher = dispatcher(packageManager)

    val result = dispatcher.dispatch(
      task = task(),
      call = AgentToolCall(toolName = "SkillsList", arguments = JsonObject(emptyMap())),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("read_skill_package", result.metadata["capabilityKind"])
    assertEquals("1", result.metadata["skillCount"])
    assertTrue(result.content.contains("find-skills"))
    assertTrue(result.content.contains("local_catalog"))
  }

  @Test
  fun skillsAddInstallsSkillThroughPackageManager() {
    val packageManager = packageManager()
    writeSkill(
      root = packageManager.catalogRootPath(),
      relativeDirectory = "find-skills",
      frontMatter = """
        name: find-skills
        description: Discover skills from the local catalog.
      """.trimIndent(),
      body = "Use the local catalog first.",
    )
    val dispatcher = dispatcher(packageManager)

    val result = dispatcher.dispatch(
      task = task(),
      call = AgentToolCall(
        toolName = "SkillsAdd",
        arguments = buildJsonObject {
          put("skill_id", "find-skills")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("install_skill", result.metadata["capabilityKind"])
    assertEquals("outside_workspace", result.metadata["workspaceRelation"])
    assertTrue(result.content.contains("Installed skill 'find-skills'"))
    assertTrue(File(packageManager.managedRootPath(), "find-skills").resolve("SKILL.md").isFile)
    assertEquals(1, packageManager.listInstallations().size)
  }

  @Test
  fun skillsRemoveDeletesManagedSkillThroughPackageManager() {
    val packageManager = packageManager()
    writeSkill(
      root = packageManager.catalogRootPath(),
      relativeDirectory = "find-skills",
      frontMatter = """
        name: find-skills
        description: Discover skills from the local catalog.
      """.trimIndent(),
      body = "Use the local catalog first.",
    )
    requireNotNull(packageManager.installFromCatalog("find-skills"))
    val dispatcher = dispatcher(packageManager)

    val result = dispatcher.dispatch(
      task = task(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "SkillsRemove",
        arguments = buildJsonObject {
          put("skill_id", "find-skills")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("remove_skill", result.metadata["capabilityKind"])
    assertFalse(File(packageManager.managedRootPath(), "find-skills").exists())
    assertTrue(packageManager.listInstallations().isEmpty())
  }

  @Test
  fun skillsAddRequiresApprovalInSafeModeForManagedRootMutation() {
    val packageManager = packageManager()
    writeSkill(
      root = packageManager.catalogRootPath(),
      relativeDirectory = "find-skills",
      frontMatter = """
        name: find-skills
        description: Discover skills from the local catalog.
      """.trimIndent(),
      body = "Use the local catalog first.",
    )
    val dispatcher = dispatcher(packageManager)

    val result = dispatcher.dispatch(
      task = task(metadata = mapOf("chatMode" to "SAFE")),
      call = AgentToolCall(
        toolName = "SkillsAdd",
        arguments = buildJsonObject {
          put("skill_id", "find-skills")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.DENIED, result.status)
    assertEquals("APPROVAL_REQUIRED", result.errorCode)
    assertEquals("ASK_SAFE_WRITE", result.metadata["policyReasonCode"])
    assertEquals("install_skill", result.metadata["capabilityKind"])
    assertEquals("outside_workspace", result.metadata["workspaceRelation"])
  }

  private fun packageManager(): SkillPackageManager {
    val managedRoot = temporaryFolder.newFolder("managed-skills")
    val catalogRoot = temporaryFolder.newFolder("catalog-skills")
    val manifestFile = File(temporaryFolder.root, "skills-manifest.json")
    return SkillPackageManager(
      managedRoot = managedRoot,
      catalogRoot = catalogRoot,
      manifestStore = SkillInstallManifestStore.fromFile(manifestFile),
    )
  }

  private fun dispatcher(packageManager: SkillPackageManager): OpenCrayToolDispatcher {
    val workspaceRoot = temporaryFolder.newFolder("dispatcher-workspace").toPath()
    Files.createDirectories(workspaceRoot)
    return OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        extraPolicyReadRoots = setOf(
          packageManager.managedRootPath().toPath(),
          packageManager.catalogRootPath().toPath(),
        ),
        extraPolicyWriteRoots = setOf(packageManager.managedRootPath().toPath()),
        skillPackageManager = packageManager,
      ),
    )
  }

  private fun task(
    metadata: Map<String, String> = emptyMap(),
  ): AgentTask = AgentTask(
    id = "task-skill-package-tool",
    type = AgentTaskType.PROMPT,
    input = "Manage skills.",
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "TEST_ALLOW",
    ),
    metadata = metadata,
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
