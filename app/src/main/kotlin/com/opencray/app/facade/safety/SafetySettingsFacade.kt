package com.opencray.app.facade.safety

import android.content.Context
import com.opencray.app.ApprovedReadRootsResolver
import com.opencray.app.InMemoryLiveContextModeKeyValueStore
import com.opencray.app.LiveContextMode
import com.opencray.app.LiveContextModeStore
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
  val maxAgentTurns: Int = SafetySettingsState.DEFAULT_MAX_AGENT_TURNS,
  val maxToolCalls: Int = SafetySettingsState.DEFAULT_MAX_TOOL_CALLS,
  val undoWindowHours: Int,
  val fileChangesPolicy: ToolPolicyOverride,
  val fileDeletesPolicy: ToolPolicyOverride,
  val shellCommandsPolicy: ToolPolicyOverride,
  val externalAccessMode: ExternalAccessMode,
  val locations: List<SafetySettingsLocationSnapshot>,
  val workspaceAccessProfile: WorkspaceAccessProfile,
  val readOnlyOutsideWorkspace: Boolean,
  val liveContextMode: LiveContextMode = LiveContextMode.FULL,
  val memoryToolsEnabled: Boolean = true,
)

data class SaveSafetySettingsRequest(
  val automationModeId: String,
  val rollbackJournalEnabled: Boolean,
  val maxFilesPerBatch: Int,
  val maxAgentTurns: Int = SafetySettingsState.DEFAULT_MAX_AGENT_TURNS,
  val maxToolCalls: Int = SafetySettingsState.DEFAULT_MAX_TOOL_CALLS,
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
  val liveContextModeId: String = LiveContextMode.FULL.wireValue,
  val memoryToolsEnabled: Boolean = true,
)

interface SafetySettingsFacade {
  fun load(): SafetySettingsSnapshot

  fun save(request: SaveSafetySettingsRequest): SafetySettingsSnapshot
}

object EmptySafetySettingsFacade : SafetySettingsFacade {
  private var state: SafetySettingsSnapshot = SafetySettingsState().toSnapshot()

  override fun load(): SafetySettingsSnapshot = state

  override fun save(request: SaveSafetySettingsRequest): SafetySettingsSnapshot {
    state = request.toState().toSnapshot(
      liveContextMode = request.toLiveContextMode(),
    )
    return state
  }
}

internal class LocalSafetySettingsFacade(
  private val store: SafetySettingsStore,
  private val liveContextModeStore: LiveContextModeStore = LiveContextModeStore(
    InMemoryLiveContextModeKeyValueStore(),
  ),
  private val reconcileState: (SafetySettingsState) -> SafetySettingsState = { it },
) : SafetySettingsFacade {
  override fun load(): SafetySettingsSnapshot {
    val stored = store.load()
    val reconciled = reconcileState(stored).sanitized()
    val liveContextMode = liveContextModeStore.load()
    if (reconciled != stored) {
      store.save(reconciled)
    }
    return reconciled.toSnapshot(liveContextMode = liveContextMode)
  }

  override fun save(request: SaveSafetySettingsRequest): SafetySettingsSnapshot {
    val state = reconcileState(request.toState()).sanitized()
    val liveContextMode = request.toLiveContextMode()
    store.save(state)
    liveContextModeStore.save(liveContextMode)
    return state.toSnapshot(liveContextMode = liveContextMode)
  }

  companion object {
    fun fromContext(context: Context): LocalSafetySettingsFacade = LocalSafetySettingsFacade(
      store = SafetySettingsStore.fromContext(context),
      liveContextModeStore = LiveContextModeStore.fromContext(context),
      reconcileState = { state -> reconcileExternalAccessAuthorization(context, state) },
    )
  }
}

private fun SaveSafetySettingsRequest.toState(): SafetySettingsState = SafetySettingsState(
  automationMode = SafetyAutomationMode.fromWireValue(automationModeId),
  rollbackJournalEnabled = rollbackJournalEnabled,
  maxFilesPerBatch = maxFilesPerBatch,
  maxAgentTurns = maxAgentTurns,
  maxToolCalls = maxToolCalls,
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
  memoryToolsEnabled = memoryToolsEnabled,
).sanitized()

private fun SaveSafetySettingsRequest.toLiveContextMode(): LiveContextMode =
  LiveContextMode.fromWireValue(liveContextModeId)

private fun SafetySettingsState.toSnapshot(
  liveContextMode: LiveContextMode = LiveContextMode.FULL,
): SafetySettingsSnapshot = SafetySettingsSnapshot(
  automationMode = automationMode,
  rollbackJournalEnabled = rollbackJournalEnabled,
  maxFilesPerBatch = maxFilesPerBatch,
  maxAgentTurns = maxAgentTurns,
  maxToolCalls = maxToolCalls,
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
  liveContextMode = liveContextMode,
  memoryToolsEnabled = memoryToolsEnabled,
)

private fun reconcileExternalAccessAuthorization(
  context: Context,
  state: SafetySettingsState,
): SafetySettingsState = state.copy(
  photoLibraryEnabled = state.photoLibraryEnabled &&
    ApprovedReadRootsResolver.hasAccessibleLocation(context, "photo_library"),
  downloadsEnabled = state.downloadsEnabled &&
    ApprovedReadRootsResolver.hasAccessibleLocation(context, "downloads"),
  documentsEnabled = state.documentsEnabled &&
    ApprovedReadRootsResolver.hasAccessibleLocation(context, "documents"),
  recordingsEnabled = state.recordingsEnabled &&
    ApprovedReadRootsResolver.hasAccessibleLocation(context, "recordings"),
)
