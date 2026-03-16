package com.opencray.app

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
  }

  @Test
  fun saveAndLoadPersistsAllSafetySettings() {
    val store = SafetySettingsStore(InMemorySafetySettingsKeyValueStore())
    val saved = SafetySettingsState(
      automationMode = SafetyAutomationMode.DEV,
      rollbackJournalEnabled = false,
      maxFilesPerBatch = 8,
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
    )

    store.save(saved)

    assertEquals(saved, store.load())
  }
}
