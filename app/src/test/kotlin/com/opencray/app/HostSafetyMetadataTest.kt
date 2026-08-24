package com.opencray.app

import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.policy.ExternalAccessMode
import com.opencray.policy.SafetyAutomationMode
import com.opencray.policy.ToolPolicyOverride
import com.opencray.policy.WorkspaceAccessProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class HostSafetyMetadataTest : HostRuntimeTestBase() {
  @Test
  fun submitChatMessageDoesNotAttachHostOnlyPolicyDetailToPromptTask() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-policy-detail"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Check approval behavior")

    val submittedTask = handle.submittedTasks.single()

    assertEquals(PolicyDecisionOutcome.ALLOW, submittedTask.policyDecision.outcome)
    assertEquals("FLUTTER_CHAT_ALLOW", submittedTask.policyDecision.reasonCode)
    assertEquals(null, submittedTask.policyDecision.detail)
  }

  @Test
  fun submitChatMessageIncludesCurrentSafetyMetadataOverrides() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-safety-metadata"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val safetyFacade = RecordingSafetySettingsFacade(
      snapshot = defaultSafetySettingsSnapshot().copy(
        automationMode = SafetyAutomationMode.DEV,
        fileChangesPolicy = ToolPolicyOverride.ALLOW,
        fileDeletesPolicy = ToolPolicyOverride.BLOCK,
        shellCommandsPolicy = ToolPolicyOverride.ASK,
      ),
    )
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      safetySettingsFacade = safetyFacade,
    )

    hostRuntime.submitChatMessage("Check the current guardrails")

    val submittedTask = handle.submittedTasks.single()

    assertEquals("DEV", submittedTask.metadata["chatMode"])
    assertEquals("DEVELOPER", submittedTask.metadata["executionMode"])
    assertEquals("allow", submittedTask.metadata["fileChangesPolicyId"])
    assertEquals("block", submittedTask.metadata["fileDeletesPolicyId"])
    assertEquals("ask", submittedTask.metadata["shellCommandsPolicyId"])
  }

  @Test
  fun submitChatMessageIncludesApprovedReadRootsMetadata() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-approved-read-roots"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      approvedReadRootsProvider = {
        ApprovedReadRootsSnapshot(
          roots = emptySet(),
          summary = "workspace=/workspace | photo_library=/storage/emulated/0/DCIM,/storage/emulated/0/Pictures",
        )
      },
    )

    hostRuntime.submitChatMessage("Check external read roots")

    val submittedTask = handle.submittedTasks.single()

    assertEquals("select_paths", submittedTask.metadata["externalAccessModeId"])
    assertEquals("true", submittedTask.metadata["readOnlyOutsideWorkspace"])
    assertEquals(
      "workspace=/workspace | photo_library=/storage/emulated/0/DCIM,/storage/emulated/0/Pictures",
      submittedTask.metadata["approvedReadRoots"],
    )
  }

  @Test
  fun chatSnapshotReflectsCurrentSafetyModeLabel() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-safety-mode"))
    val manager = RecordingRuntimeManager()
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      safetySettingsFacade = RecordingSafetySettingsFacade(
        snapshot = defaultSafetySettingsSnapshot().copy(
          automationMode = SafetyAutomationMode.SAFE,
        ),
      ),
    )

    val snapshot = hostRuntime.loadChatSnapshot()

    assertEquals("SAFE", snapshot["modeLabel"])
  }

  @Test
  fun saveSafetySettingsPersistsLiveContextMode() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-live-context-mode"))
    val safetyFacade = RecordingSafetySettingsFacade()
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = RecordingRuntimeManager(),
      safetySettingsFacade = safetyFacade,
    )

    val payload = hostRuntime.saveSafetySettings(
      automationModeId = SafetyAutomationMode.AUTO.wireValue,
      rollbackJournalEnabled = true,
      maxFilesPerBatch = 20,
      maxAgentTurns = 0,
      maxToolCalls = 0,
      undoWindowHours = 24,
      fileChangesPolicyId = ToolPolicyOverride.INHERIT.wireValue,
      fileDeletesPolicyId = ToolPolicyOverride.INHERIT.wireValue,
      shellCommandsPolicyId = ToolPolicyOverride.INHERIT.wireValue,
      externalAccessModeId = ExternalAccessMode.SELECT_PATHS.wireValue,
      photoLibraryEnabled = true,
      downloadsEnabled = true,
      documentsEnabled = false,
      recordingsEnabled = false,
      workspaceAccessProfileId = WorkspaceAccessProfile.WORK.wireValue,
      readOnlyOutsideWorkspace = true,
      liveContextModeId = LiveContextMode.NO_SOUL.wireValue,
      memoryToolsEnabled = false,
    )

    assertEquals(LiveContextMode.NO_SOUL.wireValue, payload["liveContextModeId"])
    assertEquals(false, payload["memoryToolsEnabled"])
    assertEquals(LiveContextMode.NO_SOUL.wireValue, safetyFacade.lastSavedRequest?.liveContextModeId)
    assertEquals(false, safetyFacade.lastSavedRequest?.memoryToolsEnabled)
    assertEquals(LiveContextMode.NO_SOUL, safetyFacade.snapshot.liveContextMode)
    assertEquals(false, safetyFacade.snapshot.memoryToolsEnabled)
  }

  @Test
  fun saveSandboxSettingsPersistsCloudRoutingAndApiKey() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-sandbox-settings"))
    val repository = testSandboxSettingsRepository()
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = RecordingRuntimeManager(),
      sandboxSettingsRepository = repository,
    )

    val initialPayload = hostRuntime.loadSandboxSettings()
    val savedPayload = hostRuntime.saveSandboxSettings(
      mapOf(
        "enabled" to true,
        "providerId" to "e2b",
        "defaultBackend" to "sandbox",
        "sessionMode" to "sticky",
        "autoResume" to true,
        "idleTimeoutMinutes" to 25,
        "startupTimeoutMs" to 45_000L,
        "requestTimeoutMs" to 600_000L,
        "timeoutAction" to "pause",
        "templateId" to "python-sandbox",
        "e2bApiKey" to "e2b_secret",
      ),
    )
    val resolved = repository.load()

    assertEquals("local", initialPayload["defaultBackend"])
    assertEquals(false, initialPayload["apiKeyConfigured"])
    assertEquals(true, savedPayload["enabled"])
    assertEquals("sandbox", savedPayload["defaultBackend"])
    assertEquals("sticky", savedPayload["sessionMode"])
    assertEquals("pause", savedPayload["timeoutAction"])
    assertEquals("e2b_secret", savedPayload["e2bApiKey"])
    assertEquals(true, savedPayload["apiKeyConfigured"])
    assertEquals("sandbox", resolved.state.defaultBackend)
    assertEquals("sticky", resolved.state.sessionMode)
    assertEquals("pause", resolved.state.timeoutAction)
    assertEquals("python-sandbox", resolved.state.templateId)
    assertEquals("e2b_secret", resolved.e2bApiKey)
  }
}
