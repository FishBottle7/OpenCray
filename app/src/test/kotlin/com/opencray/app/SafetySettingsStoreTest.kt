package com.opencray.app

import com.opencray.app.facade.safety.LocalSafetySettingsFacade
import com.opencray.policy.ExternalAccessMode
import com.opencray.policy.SafetyAutomationMode
import com.opencray.policy.ToolPolicyOverride
import com.opencray.policy.WorkspaceAccessProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetySettingsStoreTest {
  @Test
  fun loadDefaultsMatchPrototypeBaseline() {
    val store = SafetySettingsStore(InMemorySafetySettingsKeyValueStore())

    val state = store.load()

    assertEquals(SafetyAutomationMode.AUTO, state.automationMode)
    assertTrue(state.rollbackJournalEnabled)
    assertEquals(20, state.maxFilesPerBatch)
    assertEquals(0, state.maxAgentTurns)
    assertEquals(0, state.maxToolCalls)
    assertEquals(24, state.undoWindowHours)
    assertEquals(ToolPolicyOverride.INHERIT, state.fileChangesPolicy)
    assertEquals(ToolPolicyOverride.INHERIT, state.fileDeletesPolicy)
    assertEquals(ToolPolicyOverride.INHERIT, state.shellCommandsPolicy)
    assertEquals(ExternalAccessMode.SELECT_PATHS, state.externalAccessMode)
    assertTrue(state.photoLibraryEnabled)
    assertTrue(state.downloadsEnabled)
    assertFalse(state.documentsEnabled)
    assertFalse(state.recordingsEnabled)
    assertEquals(WorkspaceAccessProfile.WORK, state.workspaceAccessProfile)
    assertTrue(state.readOnlyOutsideWorkspace)
    assertTrue(state.memoryToolsEnabled)
  }

  @Test
  fun saveAndLoadPersistsAllSafetySettings() {
    val store = SafetySettingsStore(InMemorySafetySettingsKeyValueStore())
    val saved = SafetySettingsState(
      automationMode = SafetyAutomationMode.DEV,
      rollbackJournalEnabled = false,
      maxFilesPerBatch = 8,
      maxAgentTurns = 0,
      maxToolCalls = 18,
      undoWindowHours = 12,
      fileChangesPolicy = ToolPolicyOverride.ALLOW,
      fileDeletesPolicy = ToolPolicyOverride.BLOCK,
      shellCommandsPolicy = ToolPolicyOverride.ASK,
      externalAccessMode = ExternalAccessMode.BLOCK_ALL,
      photoLibraryEnabled = false,
      downloadsEnabled = true,
      documentsEnabled = true,
      recordingsEnabled = true,
      workspaceAccessProfile = WorkspaceAccessProfile.OPEN,
      readOnlyOutsideWorkspace = false,
      memoryToolsEnabled = false,
    )

    store.save(saved)

    assertEquals(saved, store.load())
  }

  @Test
  fun localSafetyFacadeLoadReconcilesUnauthorizedExternalLocations() {
    val store = SafetySettingsStore(InMemorySafetySettingsKeyValueStore())
    store.save(
      SafetySettingsState(
        photoLibraryEnabled = true,
        downloadsEnabled = true,
        documentsEnabled = true,
        recordingsEnabled = true,
      ),
    )
    val facade = LocalSafetySettingsFacade(
      store = store,
      reconcileState = { state ->
        state.copy(
          photoLibraryEnabled = false,
          downloadsEnabled = false,
        )
      },
    )

    val snapshot = facade.load()
    val persisted = store.load()

    assertFalse(snapshot.locations.first { it.id == "photo_library" }.enabled)
    assertFalse(snapshot.locations.first { it.id == "downloads" }.enabled)
    assertTrue(snapshot.locations.first { it.id == "documents" }.enabled)
    assertTrue(snapshot.locations.first { it.id == "recordings" }.enabled)
    assertFalse(persisted.photoLibraryEnabled)
    assertFalse(persisted.downloadsEnabled)
  }

  @Test
  fun localSafetyFacadeSavePersistsReconciledExternalLocations() {
    val store = SafetySettingsStore(InMemorySafetySettingsKeyValueStore())
    val facade = LocalSafetySettingsFacade(
      store = store,
      reconcileState = { state ->
        state.copy(
          photoLibraryEnabled = false,
          downloadsEnabled = false,
        )
      },
    )

    val snapshot = facade.save(
      com.opencray.app.facade.safety.SaveSafetySettingsRequest(
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
        memoryToolsEnabled = false,
      ),
    )
    val persisted = store.load()

    assertFalse(snapshot.locations.first { it.id == "photo_library" }.enabled)
    assertFalse(snapshot.locations.first { it.id == "downloads" }.enabled)
    assertFalse(persisted.photoLibraryEnabled)
    assertFalse(persisted.downloadsEnabled)
    assertFalse(snapshot.memoryToolsEnabled)
    assertFalse(persisted.memoryToolsEnabled)
  }

  @Test
  fun localSafetyFacadePersistsLiveContextModeSeparatelyFromSafetyStore() {
    val store = SafetySettingsStore(InMemorySafetySettingsKeyValueStore())
    val liveContextModeStore = LiveContextModeStore(
      InMemoryLiveContextModeKeyValueStore(),
    )
    val facade = LocalSafetySettingsFacade(
      store = store,
      liveContextModeStore = liveContextModeStore,
    )

    val snapshot = facade.save(
      com.opencray.app.facade.safety.SaveSafetySettingsRequest(
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
      ),
    )

    assertEquals(LiveContextMode.NO_SOUL, snapshot.liveContextMode)
    assertEquals(LiveContextMode.NO_SOUL, facade.load().liveContextMode)
    assertEquals(LiveContextMode.NO_SOUL, liveContextModeStore.load())
    assertFalse(snapshot.memoryToolsEnabled)
    assertFalse(facade.load().memoryToolsEnabled)
  }
}
