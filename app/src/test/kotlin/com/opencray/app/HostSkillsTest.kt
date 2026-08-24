package com.opencray.app

import com.opencray.app.facade.skills.InstallSourceSnapshot
import com.opencray.app.facade.skills.InstalledSkillSnapshot
import com.opencray.app.facade.skills.SkillInstallRequestResult
import com.opencray.app.facade.skills.SkillInstructionsSnapshot
import com.opencray.app.facade.skills.SkillsSnapshot
import com.opencray.app.facade.skills.SuggestedSkillSnapshot
import com.opencray.runtime.skills.SkillPackageBatchInstallAttempt
import com.opencray.runtime.skills.SkillPackageBatchInstallEntry
import com.opencray.runtime.skills.SkillPackageBatchInstallResult
import com.opencray.runtime.skills.SkillPackageCheckReport
import com.opencray.runtime.skills.SkillPackageCheckResult
import com.opencray.runtime.skills.SkillPackageCheckStatus
import com.opencray.runtime.skills.SkillPackageUpdateReport
import com.opencray.runtime.skills.SkillPackageUpdateResult
import com.opencray.runtime.skills.SkillPackageUpdateStatus
import com.opencray.runtime.skills.SkillSourceInspectionAttempt
import com.opencray.runtime.skills.SkillSourceInspectionCandidate
import com.opencray.runtime.skills.SkillSourceInspectionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostSkillsTest : HostRuntimeTestBase() {
  @Test
  fun loadSkillsSnapshotQueryUsesFacadeSearchResults() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-skills-query"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val skillsFacade = TestSkillsFacade().apply {
      snapshot = SkillsSnapshot(
        installedSkills = emptyList(),
        installSources = listOf(
          InstallSourceSnapshot(
            id = "github-url",
            title = "GitHub URL",
            subtitle = "Enter a source ref.",
            actionLabel = "Inspect",
            isAvailable = true,
          ),
        ),
        suggestedSkills = listOf(
          SuggestedSkillSnapshot(
            id = "roin-orca/skills/find-skills",
            name = "find-skills",
            description = "roin-orca/skills via skills.sh",
            sourceRef = "roin-orca/skills@find-skills",
            sourceLabel = "skills.sh",
            installs = 42,
            detailUrl = "https://skills.sh/roin-orca/skills",
          ),
        ),
        suggestedSkillsMayHaveMore = true,
      )
    }
    val handle = RecordingSessionHandle(sessionId = sessionId)
    val runtimeManager = RecordingRuntimeManager().apply {
      putHandle(handle)
    }
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = runtimeManager,
      skillsFacade = skillsFacade,
    )

    val payload = hostRuntime.loadSkillsSnapshot(query = "find", suggestedLimit = 8)

    assertEquals("find", skillsFacade.lastLoadedQuery)
    assertEquals(8, skillsFacade.lastSuggestedLimit)
    assertTrue(handle.submittedTasks.isEmpty())
    val suggestedSkills = payload["suggestedSkills"] as List<*>
    val firstResult = suggestedSkills.first() as Map<*, *>
    assertEquals("roin-orca/skills@find-skills", firstResult["sourceRef"])
    assertEquals("skills.sh", firstResult["sourceLabel"])
    assertEquals(42, firstResult["installs"])
    assertEquals("https://skills.sh/roin-orca/skills", firstResult["detailUrl"])
    assertEquals(true, payload["suggestedSkillsMayHaveMore"])
  }

  @Test
  fun installSkillSourceUsesSkillsFacadeAndReturnsInstalledSkillMessage() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-install-source"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val skillsFacade = TestSkillsFacade().apply {
      installResult = SkillInstallRequestResult(installedSkillId = "find-skills")
    }
    val handle = RecordingSessionHandle(sessionId = sessionId)
    val runtimeManager = RecordingRuntimeManager().apply {
      putHandle(handle)
    }
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = runtimeManager,
      skillsFacade = skillsFacade,
    )

    val message = hostRuntime.installSkillSource(
      sourceRef = "roin-orca/skills@find-skills",
      selectedSkillName = "",
    )

    assertEquals("roin-orca/skills@find-skills", skillsFacade.lastInstalledSourceRef)
    assertEquals("", skillsFacade.lastInstalledSelectedSkillName)
    assertTrue(handle.submittedTasks.isEmpty())
    assertEquals("Installed find-skills.", message)
  }

  @Test
  fun installSkillSourcePassesSelectedSkillNameThroughSkillsFacade() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-install-source-selected"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val skillsFacade = TestSkillsFacade().apply {
      installResult = SkillInstallRequestResult(installedSkillId = "review-skills")
    }
    val handle = RecordingSessionHandle(sessionId = sessionId)
    val runtimeManager = RecordingRuntimeManager().apply {
      putHandle(handle)
    }
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = runtimeManager,
      skillsFacade = skillsFacade,
    )

    val message = hostRuntime.installSkillSource(
      sourceRef = "roin-orca/skills",
      selectedSkillName = "review-skills",
    )

    assertEquals("roin-orca/skills", skillsFacade.lastInstalledSourceRef)
    assertEquals("review-skills", skillsFacade.lastInstalledSelectedSkillName)
    assertTrue(handle.submittedTasks.isEmpty())
    assertEquals("Installed review-skills.", message)
  }

  @Test
  fun installSkillSourceBatchPassesSelectedSkillNamesThroughSkillsFacade() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-install-source-batch"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val skillsFacade = TestSkillsFacade().apply {
      batchInstallResult = SkillPackageBatchInstallAttempt(
        result = SkillPackageBatchInstallResult(
          sourceType = "remote_github",
          sourceRef = "roin-orca/skills",
          entries = listOf(
            SkillPackageBatchInstallEntry(
              requestedSkillName = "find-skills",
              installedSkillId = "find-skills",
            ),
            SkillPackageBatchInstallEntry(
              requestedSkillName = "review-skills",
              installedSkillId = "review-skills",
            ),
          ),
        ),
      )
    }
    val handle = RecordingSessionHandle(sessionId = sessionId)
    val runtimeManager = RecordingRuntimeManager().apply {
      putHandle(handle)
    }
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = runtimeManager,
      skillsFacade = skillsFacade,
    )

    val message = hostRuntime.installSkillSourceBatch(
      sourceRef = "roin-orca/skills",
      selectedSkillNames = listOf("find-skills", "review-skills"),
    )

    assertEquals("roin-orca/skills", skillsFacade.lastBatchInstalledSourceRef)
    assertEquals(listOf("find-skills", "review-skills"), skillsFacade.lastBatchInstalledSkillNames)
    assertTrue(handle.submittedTasks.isEmpty())
    assertEquals("Installed 2 skills.", message)
  }

  @Test
  fun inspectSkillSourceUsesSkillsFacadeAndReturnsInspectionPayload() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-inspect-source"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val skillsFacade = TestSkillsFacade().apply {
      inspectResult = SkillSourceInspectionAttempt(
        result = SkillSourceInspectionResult(
          sourceType = "remote_github",
          sourceRef = "roin-orca/skills",
          sourcePath = "https://github.com/roin-orca/skills",
          resolvedRevision = "main",
          resolvedCommitSha = "deadbeef",
          candidates = listOf(
            SkillSourceInspectionCandidate(
              name = "find-skills",
              description = "Discover skills",
              relativePath = "skills/find-skills/SKILL.md",
            ),
            SkillSourceInspectionCandidate(
              name = "review-skills",
              description = "Review changes",
              relativePath = "skills/review-skills/SKILL.md",
            ),
          ),
        ),
      )
    }
    val handle = RecordingSessionHandle(sessionId = sessionId)
    val runtimeManager = RecordingRuntimeManager().apply {
      putHandle(handle)
    }
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = runtimeManager,
      skillsFacade = skillsFacade,
    )

    val payload = hostRuntime.inspectSkillSource("roin-orca/skills")

    assertEquals("roin-orca/skills", skillsFacade.lastInspectedSourceRef)
    assertTrue(handle.submittedTasks.isEmpty())
    assertEquals("remote_github", payload["sourceType"])
    assertEquals("roin-orca/skills", payload["sourceRef"])
    val candidates = payload["candidates"] as List<*>
    assertEquals(2, candidates.size)
    assertEquals("find-skills", (candidates[0] as Map<*, *>)["name"])
    assertEquals("review-skills", (candidates[1] as Map<*, *>)["name"])
  }

  @Test
  fun deleteInstalledSkillUsesSkillsFacadeAndReturnsRemovedSkillMessage() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-delete-skill"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val skillsFacade = TestSkillsFacade().apply {
      deleteResult = true
    }
    val handle = RecordingSessionHandle(sessionId = sessionId)
    val runtimeManager = RecordingRuntimeManager().apply {
      putHandle(handle)
    }
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = runtimeManager,
      skillsFacade = skillsFacade,
    )

    val message = hostRuntime.deleteInstalledSkill("find-skills")

    assertEquals("find-skills", skillsFacade.lastDeletedSkillId)
    assertTrue(handle.submittedTasks.isEmpty())
    assertEquals("Removed find-skills.", message)
  }

  @Test
  fun loadSkillsSnapshotWithoutQueryUsesFacadeSnapshot() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-skills-default"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val skillsFacade = TestSkillsFacade().apply {
      snapshot = SkillsSnapshot(
        installedSkills = listOf(
          InstalledSkillSnapshot(
            id = "find-skills",
            name = "find-skills",
            description = "Fallback description",
            isEnabled = false,
            sourceDirectoryPath = "/managed/find-skills",
            canDelete = true,
          ),
        ),
        installSources = listOf(
          InstallSourceSnapshot(
            id = "github-url",
            title = "GitHub URL",
            subtitle = "Enter a source ref.",
            actionLabel = "Inspect",
            isAvailable = true,
          ),
        ),
        suggestedSkills = listOf(
          SuggestedSkillSnapshot(
            id = "acme/skills/remote-skill",
            name = "remote-skill",
            description = "acme/skills via skills.sh",
            sourceRef = "acme/skills@remote-skill",
            sourceLabel = "skills.sh",
          ),
        ),
      )
    }
    val handle = RecordingSessionHandle(sessionId = sessionId)
    val runtimeManager = RecordingRuntimeManager().apply {
      putHandle(handle)
    }
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = runtimeManager,
      skillsFacade = skillsFacade,
    )

    val payload = hostRuntime.loadSkillsSnapshot(query = "", suggestedLimit = 0)

    assertEquals("", skillsFacade.lastLoadedQuery)
    assertEquals(0, skillsFacade.lastSuggestedLimit)
    assertTrue(handle.submittedTasks.isEmpty())
    val installedSkills = payload["installedSkills"] as List<*>
    val installed = installedSkills.single() as Map<*, *>
    assertEquals("find-skills", installed["id"])
    assertEquals("Fallback description", installed["description"])
    assertEquals(false, installed["isEnabled"])
    assertEquals("/managed/find-skills", installed["sourceDirectoryPath"])
    val suggestedSkills = payload["suggestedSkills"] as List<*>
    val suggested = suggestedSkills.single() as Map<*, *>
    assertEquals("acme/skills@remote-skill", suggested["sourceRef"])
    assertEquals("skills.sh", suggested["sourceLabel"])
    assertEquals(false, payload["suggestedSkillsMayHaveMore"])
  }

  @Test
  fun loadSuggestedSkillInstructionsUsesFacadePreview() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-suggested-instructions"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val skillsFacade = TestSkillsFacade().apply {
      suggestedInstructions = SkillInstructionsSnapshot(
        id = "find-skills",
        name = "find-skills",
        description = "Find and install useful skills.",
        body = "## Usage\nUse this skill to discover skills.",
        sourceDirectoryPath = "https://skills.sh/roin-orca/skills",
        isEnabled = false,
        canDelete = false,
      )
    }
    val handle = RecordingSessionHandle(sessionId = sessionId)
    val runtimeManager = RecordingRuntimeManager().apply {
      putHandle(handle)
    }
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = runtimeManager,
      skillsFacade = skillsFacade,
    )

    val payload = hostRuntime.loadSuggestedSkillInstructions(
      sourceRef = "roin-orca/skills@find-skills",
      selectedSkillName = "find-skills",
    )

    assertEquals("roin-orca/skills@find-skills", skillsFacade.lastSuggestedInstructionsSourceRef)
    assertEquals("find-skills", skillsFacade.lastSuggestedInstructionsSkillName)
    assertEquals("find-skills", payload["name"])
    assertEquals("https://skills.sh/roin-orca/skills", payload["sourceDirectoryPath"])
    assertTrue(handle.submittedTasks.isEmpty())
  }

  @Test
  fun checkInstalledSkillUpdatesUsesSkillsFacade() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-skills-check"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val skillsFacade = TestSkillsFacade().apply {
      checkReport = SkillPackageCheckReport(
        results = listOf(
          SkillPackageCheckResult(
            skillId = "find-skills",
            sourceType = "remote_github",
            sourceRef = "roin-orca/skills",
            status = SkillPackageCheckStatus.UP_TO_DATE,
            checkedAtEpochMs = 1_000L,
          ),
        ),
      )
    }
    val handle = RecordingSessionHandle(sessionId = sessionId)
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = RecordingRuntimeManager().apply { putHandle(handle) },
      skillsFacade = skillsFacade,
    )

    val message = hostRuntime.checkInstalledSkillUpdates("find-skills")

    assertEquals("find-skills", skillsFacade.lastCheckedSkillId)
    assertTrue(handle.submittedTasks.isEmpty())
    assertEquals("Skill 'find-skills' is up to date.", message)
  }

  @Test
  fun updateInstalledSkillUsesSkillsFacade() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-skills-update"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val skillsFacade = TestSkillsFacade().apply {
      updateReport = SkillPackageUpdateReport(
        results = listOf(
          SkillPackageUpdateResult(
            skillId = "find-skills",
            sourceType = "remote_github",
            sourceRef = "roin-orca/skills",
            status = SkillPackageUpdateStatus.UPDATED,
          ),
        ),
      )
    }
    val handle = RecordingSessionHandle(sessionId = sessionId)
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = RecordingRuntimeManager().apply { putHandle(handle) },
      skillsFacade = skillsFacade,
    )

    val message = hostRuntime.updateInstalledSkill("find-skills")

    assertEquals("find-skills", skillsFacade.lastUpdatedSkillId)
    assertTrue(handle.submittedTasks.isEmpty())
    assertEquals("Updated 'find-skills'.", message)
  }
}
