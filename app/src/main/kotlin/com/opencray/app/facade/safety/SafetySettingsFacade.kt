package com.opencray.app.facade.safety

import android.content.Context
import com.opencray.app.SafetySettingsState
import com.opencray.app.SafetySettingsStore
import com.opencray.policy.ExternalAccessMode
import com.opencray.policy.SafetyAutomationMode
import com.opencray.policy.ToolPolicyOverride
import com.opencray.policy.WorkspaceAccessProfile

data class SafetySettingsLocationSnapshot(
  val id: String,
  val enabled: Boolean,
)

data class SafetySettingsSnapshot(
  val automationMode: SafetyAutomationMode,
  val rollbackJournalEnabled: Boolean,
  val maxFilesPerBatch: Int,
  val undoWindowHours: Int,
  val fileChangesPolicy: ToolPolicyOverride,
  val fileDeletesPolicy: ToolPolicyOverride,
  val shellCommandsPolicy: ToolPolicyOverride,
  val externalAccessMode: ExternalAccessMode,
  val locations: List<SafetySettingsLocationSnapshot>,
  val workspaceAccessProfile: WorkspaceAccessProfile,
  val readOnlyOutsideWorkspace: Boolean,
)

data class SaveSafetySettingsRequest(
  val automationModeId: String,
  val rollbackJournalEnabled: Boolean,
  val maxFilesPerBatch: Int,
  val undoWindowHours: Int,
  val fileChangesPolicyId: String,
  val fileDeletesPolicyId: String,
  val shellCommandsPolicyId: String,
  val externalAccessModeId: String,
  val photoLibraryEnabled: Boolean,
  val downloadsEnabled: Boolean,
  val documentsEnabled: Boolean,
  val recordingsEnabled: Boolean,
  val workspaceAccessProfileId: String,
  val readOnlyOutsideWorkspace: Boolean,
)

interface SafetySettingsFacade {
  fun load(): SafetySettingsSnapshot

  fun save(request: SaveSafetySettingsRequest): SafetySettingsSnapshot
}

object EmptySafetySettingsFacade : SafetySettingsFacade {
  private var state: SafetySettingsSnapshot = SafetySettingsState().toSnapshot()

  override fun load(): SafetySettingsSnapshot = state

  override fun save(request: SaveSafetySettingsRequest): SafetySettingsSnapshot {
    state = request.toState().toSnapshot()
    return state
  }
}

internal class LocalSafetySettingsFacade(
  private val store: SafetySettingsStore,
) : SafetySettingsFacade {
  override fun load(): SafetySettingsSnapshot = store.load().toSnapshot()

  override fun save(request: SaveSafetySettingsRequest): SafetySettingsSnapshot {
    val state = request.toState()
    store.save(state)
    return state.toSnapshot()
  }

  companion object {
    fun fromContext(context: Context): LocalSafetySettingsFacade = LocalSafetySettingsFacade(
      store = SafetySettingsStore.fromContext(context),
    )
  }
}

private fun SaveSafetySettingsRequest.toState(): SafetySettingsState = SafetySettingsState(
  automationMode = SafetyAutomationMode.fromWireValue(automationModeId),
  rollbackJournalEnabled = rollbackJournalEnabled,
  maxFilesPerBatch = maxFilesPerBatch,
  undoWindowHours = undoWindowHours,
  fileChangesPolicy = ToolPolicyOverride.fromWireValue(fileChangesPolicyId),
  fileDeletesPolicy = ToolPolicyOverride.fromWireValue(fileDeletesPolicyId),
  shellCommandsPolicy = ToolPolicyOverride.fromWireValue(shellCommandsPolicyId),
  externalAccessMode = ExternalAccessMode.fromWireValue(externalAccessModeId),
  photoLibraryEnabled = photoLibraryEnabled,
  downloadsEnabled = downloadsEnabled,
  documentsEnabled = documentsEnabled,
  recordingsEnabled = recordingsEnabled,
  workspaceAccessProfile = WorkspaceAccessProfile.fromWireValue(workspaceAccessProfileId),
  readOnlyOutsideWorkspace = readOnlyOutsideWorkspace,
).sanitized()

private fun SafetySettingsState.toSnapshot(): SafetySettingsSnapshot = SafetySettingsSnapshot(
  automationMode = automationMode,
  rollbackJournalEnabled = rollbackJournalEnabled,
  maxFilesPerBatch = maxFilesPerBatch,
  undoWindowHours = undoWindowHours,
  fileChangesPolicy = fileChangesPolicy,
  fileDeletesPolicy = fileDeletesPolicy,
  shellCommandsPolicy = shellCommandsPolicy,
  externalAccessMode = externalAccessMode,
  locations = listOf(
    SafetySettingsLocationSnapshot(id = "photo_library", enabled = photoLibraryEnabled),
    SafetySettingsLocationSnapshot(id = "downloads", enabled = downloadsEnabled),
    SafetySettingsLocationSnapshot(id = "documents", enabled = documentsEnabled),
    SafetySettingsLocationSnapshot(id = "recordings", enabled = recordingsEnabled),
  ),
  workspaceAccessProfile = workspaceAccessProfile,
  readOnlyOutsideWorkspace = readOnlyOutsideWorkspace,
)
