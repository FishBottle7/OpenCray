package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.runtime.skills.FetchedRemoteSkillSource
import com.opencray.runtime.skills.RemoteSkillSearchClient
import com.opencray.runtime.skills.RemoteSkillSearchHit
import com.opencray.runtime.skills.RemoteSkillSearchRequest
import com.opencray.runtime.skills.RemoteSkillSearchResponse
import com.opencray.runtime.skills.RemoteSkillSourceInspector
import com.opencray.runtime.skills.RemoteSkillSourceFetcher
import com.opencray.runtime.skills.ResolvedRemoteSkillSource
import com.opencray.runtime.skills.RemoteSkillSourceVersion
import com.opencray.runtime.skills.RemoteSkillSourceVersionAttempt
import com.opencray.runtime.skills.SkillInstallManifestStore
import com.opencray.runtime.skills.SkillInstallSourceType
import com.opencray.runtime.skills.SkillPackageManager
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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
  fun skillsFindIncludesRemoteResultsFromUnifiedToolSurface() {
    val packageManager = packageManager(
      remoteSearchClient = FakeRemoteSkillSearchClient(
        hits = listOf(
          RemoteSkillSearchHit(
            id = "roin-orca/skills/find-skills",
            name = "find-skills",
            source = "roin-orca/skills",
            installs = 42,
            installRef = "roin-orca/skills@find-skills",
            detailUrl = "https://skills.sh/roin-orca/skills",
          ),
        ),
      ),
    )
    writeSkill(
      root = packageManager.catalogRootPath(),
      relativeDirectory = "local-find-skills",
      frontMatter = """
        name: local-find-skills
        description: Discover skills from the local catalog.
      """.trimIndent(),
      body = "Use the local catalog first.",
    )
    val dispatcher = dispatcher(packageManager)

    val result = dispatcher.dispatch(
      task = task(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "SkillsFind",
        arguments = buildJsonObject {
          put("query", "find")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("read_skill_package", result.metadata["capabilityKind"])
    assertEquals("1", result.metadata["remoteResultCount"])
    assertEquals("1", result.metadata["localResultCount"])
    assertTrue(result.content.contains("install_ref=roin-orca/skills@find-skills"))
    assertTrue(result.content.contains("local-find-skills"))
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
  fun skillsCheckReportsAvailableUpdatesFromRecordedProvenance() {
    val packageManager = packageManager(
      remoteSourceInspector = FakeRemoteSkillSourceInspector(
        resolvedRevision = "main",
        resolvedCommitSha = "feedface",
      ),
      remoteSourceFetcher = FakeRemoteSkillSourceFetcher(
        skillDirectoryName = "find-skills",
        skillName = "find-skills",
        description = "Discover skills from the remote source.",
      ),
    )
    check(packageManager.installFromRemoteSource("roin-orca/skills@find-skills").succeeded)
    val dispatcher = dispatcher(packageManager)

    val result = dispatcher.dispatch(
      task = task(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(toolName = "SkillsCheck", arguments = JsonObject(emptyMap())),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("check_skill_update", result.metadata["capabilityKind"])
    assertEquals("1", result.metadata["checkedCount"])
    assertEquals("1", result.metadata["updateAvailableCount"])
    assertTrue(result.content.contains("update_available"))
    assertTrue(result.content.contains("latest_commit=feedface"))
  }

  @Test
  fun skillsAddInstallsRemoteSkillThroughPackageManager() {
    val packageManager = packageManager(
      remoteSourceFetcher = FakeRemoteSkillSourceFetcher(
        skillDirectoryName = "find-skills",
        skillName = "find-skills",
        description = "Discover skills from the remote source.",
      ),
    )
    val dispatcher = dispatcher(packageManager)

    val result = dispatcher.dispatch(
      task = task(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "SkillsAdd",
        arguments = buildJsonObject {
          put("source_ref", "roin-orca/skills@find-skills")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("install_skill", result.metadata["capabilityKind"])
    assertEquals(SkillInstallSourceType.REMOTE_GITHUB.wireValue, result.metadata["sourceType"])
    assertTrue(result.content.contains("remote source"))
    assertTrue(File(packageManager.managedRootPath(), "find-skills").resolve("SKILL.md").isFile)
    assertEquals(1, packageManager.listInstallations().size)
  }

  @Test
  fun skillsAddBatchInstallsMultipleRemoteSkillsThroughPackageManager() {
    val packageManager = packageManager(
      remoteSourceFetcher = FakeRemoteSkillSourceFetcher(
        skillDirectoryName = "skills/find-skills",
        skillName = "find-skills",
        description = "Discover skills from the remote source.",
        extraSkills = listOf(
          FakeRemoteSkill(
            relativeDirectory = "skills/review-skills",
            skillName = "review-skills",
            description = "Review changes from the remote source.",
          ),
        ),
      ),
    )
    val dispatcher = dispatcher(packageManager)

    val result = dispatcher.dispatch(
      task = task(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "SkillsAddBatch",
        arguments = buildJsonObject {
          put("source_ref", "roin-orca/skills")
          putJsonArray("skills") {
            add(JsonPrimitive("find-skills"))
            add(JsonPrimitive("review-skills"))
          }
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("install_skill", result.metadata["capabilityKind"])
    assertEquals("2", result.metadata["requestedCount"])
    assertEquals("2", result.metadata["installedCount"])
    assertEquals("0", result.metadata["failedCount"])
    assertTrue(result.content.contains("batch_install"))
    assertTrue(File(packageManager.managedRootPath(), "find-skills").resolve("SKILL.md").isFile)
    assertTrue(File(packageManager.managedRootPath(), "review-skills").resolve("SKILL.md").isFile)
    assertEquals(2, packageManager.listInstallations().size)
  }

  @Test
  fun skillsAddInstallsGitlabSkillThroughPackageManager() {
    val packageManager = packageManager(
      remoteSourceFetcher = FakeRemoteSkillSourceFetcher(
        skillDirectoryName = "find-skills",
        skillName = "find-skills",
        description = "Discover skills from the remote source.",
      ),
    )
    val dispatcher = dispatcher(packageManager)

    val result = dispatcher.dispatch(
      task = task(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "SkillsAdd",
        arguments = buildJsonObject {
          put("source_ref", "https://gitlab.com/acme/platform/skills/-/tree/main/skills/find-skills")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals(SkillInstallSourceType.REMOTE_GITLAB.wireValue, result.metadata["sourceType"])
    assertTrue(File(packageManager.managedRootPath(), "find-skills").resolve("SKILL.md").isFile)
  }

  @Test
  fun skillsAddInstallsGitlabShorthandSkillThroughPackageManager() {
    val packageManager = packageManager(
      remoteSourceFetcher = FakeRemoteSkillSourceFetcher(
        skillDirectoryName = "find-skills",
        skillName = "find-skills",
        description = "Discover skills from the remote source.",
      ),
    )
    val dispatcher = dispatcher(packageManager)

    val result = dispatcher.dispatch(
      task = task(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "SkillsAdd",
        arguments = buildJsonObject {
          put("source_ref", "gitlab:acme/platform/skills@find-skills")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals(SkillInstallSourceType.REMOTE_GITLAB.wireValue, result.metadata["sourceType"])
    assertTrue(File(packageManager.managedRootPath(), "find-skills").resolve("SKILL.md").isFile)
  }

  @Test
  fun skillsAddInstallsSkillFromExplicitLocalPath() {
    val packageManager = packageManager()
    val workspaceRoot = temporaryFolder.newFolder("dispatcher-local-workspace").toPath()
    val localSourceRoot = workspaceRoot.resolve("local-sources")
    Files.createDirectories(localSourceRoot.resolve("find-skills"))
    Files.write(
      localSourceRoot.resolve("find-skills").resolve("SKILL.md"),
      """
      ---
      name: find-skills
      description: Discover skills from an explicit local path.
      ---
      Use the local path.
      """.trimIndent().toByteArray(StandardCharsets.UTF_8),
    )
    val dispatcher = dispatcher(packageManager, workspaceRoot)

    val result = dispatcher.dispatch(
      task = task(),
      call = AgentToolCall(
        toolName = "SkillsAdd",
        arguments = buildJsonObject {
          put("source_ref", "./local-sources")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("install_skill", result.metadata["capabilityKind"])
    assertEquals(SkillInstallSourceType.LOCAL_PATH.wireValue, result.metadata["sourceType"])
    assertTrue(result.content.contains("local source"))
    assertTrue(File(packageManager.managedRootPath(), "find-skills").resolve("SKILL.md").isFile)
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
  fun skillsInspectListsCandidatesFromExplicitLocalPath() {
    val packageManager = packageManager()
    val workspaceRoot = temporaryFolder.newFolder("dispatcher-inspect-local-workspace").toPath()
    val localSourceRoot = workspaceRoot.resolve("local-sources").resolve("pack-a")
    Files.createDirectories(localSourceRoot.resolve("find-skills"))
    Files.createDirectories(localSourceRoot.resolve("review-skills"))
    Files.write(
      localSourceRoot.resolve("find-skills").resolve("SKILL.md"),
      """
      ---
      name: find-skills
      description: Discover skills from an explicit local path.
      ---
      Use the local path.
      """.trimIndent().toByteArray(StandardCharsets.UTF_8),
    )
    Files.write(
      localSourceRoot.resolve("review-skills").resolve("SKILL.md"),
      """
      ---
      name: review-skills
      description: Review workspace changes from an explicit local path.
      ---
      Review the workspace.
      """.trimIndent().toByteArray(StandardCharsets.UTF_8),
    )
    val dispatcher = dispatcher(packageManager, workspaceRoot)

    val result = dispatcher.dispatch(
      task = task(),
      call = AgentToolCall(
        toolName = "SkillsInspect",
        arguments = buildJsonObject {
          put("source_ref", "./local-sources/pack-a")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("read_skill_package", result.metadata["capabilityKind"])
    assertEquals("2", result.metadata["candidateCount"])
    assertTrue(result.content.contains("inspection\tlocal_path"))
    assertTrue(result.content.contains("candidate\tfind-skills"))
    assertTrue(result.content.contains("candidate\treview-skills"))
  }

  @Test
  fun skillsInspectListsCandidatesFromRemoteSource() {
    val packageManager = packageManager(
      remoteSourceFetcher = FakeRemoteSkillSourceFetcher(
        skillDirectoryName = "skills/find-skills",
        skillName = "find-skills",
        description = "Discover skills from the remote source.",
        extraSkills = listOf(
          FakeRemoteSkill(
            relativeDirectory = "skills/review-skills",
            skillName = "review-skills",
            description = "Review changes from the remote source.",
          ),
        ),
      ),
    )
    val dispatcher = dispatcher(packageManager)

    val result = dispatcher.dispatch(
      task = task(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "SkillsInspect",
        arguments = buildJsonObject {
          put("source_ref", "roin-orca/skills")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("read_skill_package", result.metadata["capabilityKind"])
    assertEquals("network", result.metadata["targetKind"])
    assertEquals("2", result.metadata["candidateCount"])
    assertEquals(SkillInstallSourceType.REMOTE_GITHUB.wireValue, result.metadata["sourceType"])
    assertTrue(result.content.contains("inspection\tremote_github"))
    assertTrue(result.content.contains("candidate\tfind-skills"))
    assertTrue(result.content.contains("candidate\treview-skills"))
  }

  @Test
  fun skillsUpdateRefreshesCatalogSkillThroughUnifiedToolSurface() {
    val packageManager = packageManager()
    writeSkill(
      root = packageManager.catalogRootPath(),
      relativeDirectory = "find-skills",
      frontMatter = """
        name: find-skills
        description: Discover skills from the local catalog.
      """.trimIndent(),
      body = "Use the original catalog copy.",
    )
    requireNotNull(packageManager.installFromCatalog("find-skills"))
    writeSkill(
      root = packageManager.catalogRootPath(),
      relativeDirectory = "find-skills",
      frontMatter = """
        name: find-skills
        description: Discover skills from the local catalog.
      """.trimIndent(),
      body = "Use the refreshed catalog copy.",
    )
    val dispatcher = dispatcher(packageManager)

    val result = dispatcher.dispatch(
      task = task(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(toolName = "SkillsUpdate", arguments = JsonObject(emptyMap())),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("update_skill", result.metadata["capabilityKind"])
    assertEquals("1", result.metadata["updatedCount"])
    assertTrue(result.content.contains("updated"))
    assertTrue(File(packageManager.managedRootPath(), "find-skills").resolve("SKILL.md").readText().contains("Use the refreshed catalog copy."))
  }

  @Test
  fun skillsFindRequiresApprovalInSafeModeForRemoteSearch() {
    val packageManager = packageManager(
      remoteSearchClient = FakeRemoteSkillSearchClient(
        hits = listOf(
          RemoteSkillSearchHit(
            id = "roin-orca/skills/find-skills",
            name = "find-skills",
            source = "roin-orca/skills",
            installs = 42,
            installRef = "roin-orca/skills@find-skills",
            detailUrl = "https://skills.sh/roin-orca/skills",
          ),
        ),
      ),
    )
    val dispatcher = dispatcher(packageManager)

    val result = dispatcher.dispatch(
      task = task(metadata = mapOf("chatMode" to "SAFE")),
      call = AgentToolCall(
        toolName = "SkillsFind",
        arguments = buildJsonObject {
          put("query", "find")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.DENIED, result.status)
    assertEquals("HIGH_RISK_APPROVAL_REQUIRED", result.errorCode)
    assertEquals("SkillsFind", result.toolName)
    assertEquals("SkillsFind", result.metadata["requestedToolName"])
    assertEquals("SkillsFind", result.metadata["normalizedToolName"])
    assertEquals("network_access", result.metadata["capabilityKind"])
    assertEquals("network", result.metadata["targetKind"])
  }

  @Test
  fun skillsCheckRequiresApprovalInSafeModeForRemoteNetworkAccess() {
    val packageManager = packageManager(
      remoteSourceFetcher = FakeRemoteSkillSourceFetcher(
        skillDirectoryName = "find-skills",
        skillName = "find-skills",
        description = "Discover skills from the remote source.",
      ),
    )
    check(packageManager.installFromRemoteSource("roin-orca/skills@find-skills").succeeded)
    val dispatcher = dispatcher(packageManager)

    val result = dispatcher.dispatch(
      task = task(metadata = mapOf("chatMode" to "SAFE")),
      call = AgentToolCall(toolName = "SkillsCheck", arguments = JsonObject(emptyMap())),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.DENIED, result.status)
    assertEquals("APPROVAL_REQUIRED", result.errorCode)
    assertEquals("SkillsCheck", result.toolName)
    assertEquals("SkillsCheck", result.metadata["requestedToolName"])
    assertEquals("SkillsCheck", result.metadata["normalizedToolName"])
    assertEquals("check_skill_update", result.metadata["capabilityKind"])
    assertEquals("directory", result.metadata["targetKind"])
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
  fun skillsAddRequiresApprovalInSafeModeForRemoteNetworkAccess() {
    val packageManager = packageManager(
      remoteSourceFetcher = FakeRemoteSkillSourceFetcher(
        skillDirectoryName = "find-skills",
        skillName = "find-skills",
        description = "Discover skills from the remote source.",
      ),
    )
    val dispatcher = dispatcher(packageManager)

    val result = dispatcher.dispatch(
      task = task(metadata = mapOf("chatMode" to "SAFE")),
      call = AgentToolCall(
        toolName = "SkillsAdd",
        arguments = buildJsonObject {
          put("source_ref", "roin-orca/skills@find-skills")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.DENIED, result.status)
    assertEquals("HIGH_RISK_APPROVAL_REQUIRED", result.errorCode)
    assertEquals("SkillsAdd", result.toolName)
    assertEquals("SkillsAdd", result.metadata["requestedToolName"])
    assertEquals("SkillsAdd", result.metadata["normalizedToolName"])
    assertEquals("network_access", result.metadata["capabilityKind"])
    assertEquals("network", result.metadata["targetKind"])
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

  @Test
  fun skillsInspectRequiresApprovalInSafeModeForRemoteNetworkAccess() {
    val packageManager = packageManager(
      remoteSourceFetcher = FakeRemoteSkillSourceFetcher(
        skillDirectoryName = "find-skills",
        skillName = "find-skills",
        description = "Discover skills from the remote source.",
      ),
    )
    val dispatcher = dispatcher(packageManager)

    val result = dispatcher.dispatch(
      task = task(metadata = mapOf("chatMode" to "SAFE")),
      call = AgentToolCall(
        toolName = "SkillsInspect",
        arguments = buildJsonObject {
          put("source_ref", "roin-orca/skills")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.DENIED, result.status)
    assertEquals("HIGH_RISK_APPROVAL_REQUIRED", result.errorCode)
    assertEquals("SkillsInspect", result.toolName)
    assertEquals("SkillsInspect", result.metadata["requestedToolName"])
    assertEquals("SkillsInspect", result.metadata["normalizedToolName"])
    assertEquals("network_access", result.metadata["capabilityKind"])
    assertEquals("network", result.metadata["targetKind"])
  }

  @Test
  fun skillsAddBatchRequiresApprovalInSafeModeForRemoteNetworkAccess() {
    val packageManager = packageManager(
      remoteSourceFetcher = FakeRemoteSkillSourceFetcher(
        skillDirectoryName = "skills/find-skills",
        skillName = "find-skills",
        description = "Discover skills from the remote source.",
        extraSkills = listOf(
          FakeRemoteSkill(
            relativeDirectory = "skills/review-skills",
            skillName = "review-skills",
            description = "Review changes from the remote source.",
          ),
        ),
      ),
    )
    val dispatcher = dispatcher(packageManager)

    val result = dispatcher.dispatch(
      task = task(metadata = mapOf("chatMode" to "SAFE")),
      call = AgentToolCall(
        toolName = "SkillsAddBatch",
        arguments = buildJsonObject {
          put("source_ref", "roin-orca/skills")
          putJsonArray("skills") {
            add(JsonPrimitive("find-skills"))
            add(JsonPrimitive("review-skills"))
          }
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.DENIED, result.status)
    assertEquals("HIGH_RISK_APPROVAL_REQUIRED", result.errorCode)
    assertEquals("SkillsAddBatch", result.toolName)
    assertEquals("SkillsAddBatch", result.metadata["requestedToolName"])
    assertEquals("SkillsAddBatch", result.metadata["normalizedToolName"])
    assertEquals("network_access", result.metadata["capabilityKind"])
    assertEquals("network", result.metadata["targetKind"])
  }

  @Test
  fun skillsUpdateRequiresApprovalInSafeModeForManagedRootMutation() {
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
    writeSkill(
      root = packageManager.catalogRootPath(),
      relativeDirectory = "find-skills",
      frontMatter = """
        name: find-skills
        description: Discover skills from the local catalog.
      """.trimIndent(),
      body = "Use the updated catalog copy.",
    )
    val dispatcher = dispatcher(packageManager)

    val result = dispatcher.dispatch(
      task = task(metadata = mapOf("chatMode" to "SAFE")),
      call = AgentToolCall(toolName = "SkillsUpdate", arguments = JsonObject(emptyMap())),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.DENIED, result.status)
    assertEquals("APPROVAL_REQUIRED", result.errorCode)
    assertEquals("ASK_SAFE_WRITE", result.metadata["policyReasonCode"])
    assertEquals("update_skill", result.metadata["capabilityKind"])
    assertEquals("outside_workspace", result.metadata["workspaceRelation"])
  }

  private fun packageManager(
    remoteSearchClient: RemoteSkillSearchClient = FakeRemoteSkillSearchClient(),
    remoteSourceInspector: RemoteSkillSourceInspector = FakeRemoteSkillSourceInspector(
      resolvedRevision = "main",
      resolvedCommitSha = "deadbeef",
    ),
    remoteSourceFetcher: RemoteSkillSourceFetcher = FakeRemoteSkillSourceFetcher(
      skillDirectoryName = "unused-skill",
      skillName = "unused-skill",
      description = "Unused remote skill fixture.",
    ),
  ): SkillPackageManager {
    val managedRoot = temporaryFolder.newFolder("managed-skills")
    val catalogRoot = temporaryFolder.newFolder("catalog-skills")
    val manifestFile = File(temporaryFolder.root, "skills-manifest.json")
    return SkillPackageManager(
      managedRoot = managedRoot,
      catalogRoot = catalogRoot,
      manifestStore = SkillInstallManifestStore.fromFile(manifestFile),
      remoteSearchClient = remoteSearchClient,
      remoteSourceInspector = remoteSourceInspector,
      remoteSourceFetcher = remoteSourceFetcher,
    )
  }

  private fun dispatcher(
    packageManager: SkillPackageManager,
    workspaceRoot: java.nio.file.Path = temporaryFolder.newFolder("dispatcher-workspace").toPath(),
  ): OpenCrayToolDispatcher {
    Files.createDirectories(workspaceRoot)
    return OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        extraPolicyReadRoots = packageManager.policyReadRoots().map { file -> file.toPath() }.toSet(),
        extraPolicyWriteRoots = packageManager.policyWriteRoots().map { file -> file.toPath() }.toSet(),
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

  private class FakeRemoteSkillSearchClient(
    private val hits: List<RemoteSkillSearchHit> = emptyList(),
  ) : RemoteSkillSearchClient {
    override fun search(request: RemoteSkillSearchRequest): RemoteSkillSearchResponse =
      RemoteSkillSearchResponse(
        providerName = "skills.sh",
        hits = hits.take(request.limit),
      )
  }

  private class FakeRemoteSkillSourceFetcher(
    private val skillDirectoryName: String,
    private val skillName: String,
    private val description: String,
    private val extraSkills: List<FakeRemoteSkill> = emptyList(),
  ) : RemoteSkillSourceFetcher {
    override fun fetch(
      source: ResolvedRemoteSkillSource,
      stagingRoot: File,
    ): FetchedRemoteSkillSource {
      val repositoryRoot = File(stagingRoot, "repo")
      val skillDirectory = File(repositoryRoot, skillDirectoryName)
      Files.createDirectories(skillDirectory.toPath())
      Files.write(
        File(skillDirectory, "SKILL.md").toPath(),
        """
        ---
        name: $skillName
        description: $description
        ---
        Use the remote source.
        """.trimIndent().toByteArray(StandardCharsets.UTF_8),
      )
      extraSkills.forEach { extraSkill ->
        val extraSkillDirectory = File(repositoryRoot, extraSkill.relativeDirectory)
        Files.createDirectories(extraSkillDirectory.toPath())
        Files.write(
          File(extraSkillDirectory, "SKILL.md").toPath(),
          """
          ---
          name: ${extraSkill.skillName}
          description: ${extraSkill.description}
          ---
          ${extraSkill.body}
          """.trimIndent().toByteArray(StandardCharsets.UTF_8),
        )
      }
      return FetchedRemoteSkillSource(
        repositoryRoot = repositoryRoot,
        searchRoot = repositoryRoot,
        repositoryUrl = source.repositoryUrl,
        resolvedRevision = source.ref ?: "main",
        resolvedCommitSha = "deadbeef",
      )
    }
  }

  private data class FakeRemoteSkill(
    val relativeDirectory: String,
    val skillName: String,
    val description: String,
    val body: String = "Use the remote source.",
  )

  private class FakeRemoteSkillSourceInspector(
    private val resolvedRevision: String,
    private val resolvedCommitSha: String? = null,
  ) : RemoteSkillSourceInspector {
    override fun inspect(source: ResolvedRemoteSkillSource): RemoteSkillSourceVersionAttempt =
      RemoteSkillSourceVersionAttempt(
        version = RemoteSkillSourceVersion(
          repositoryUrl = source.repositoryUrl,
          resolvedRevision = resolvedRevision,
          resolvedCommitSha = resolvedCommitSha,
        ),
      )
  }
}
