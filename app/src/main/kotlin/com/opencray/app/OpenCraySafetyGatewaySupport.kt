package com.opencray.app

import com.opencray.app.facade.safety.SaveSafetySettingsRequest
import com.opencray.app.facade.safety.SafetySettingsLocationSnapshot
import com.opencray.app.facade.safety.SafetySettingsSnapshot

internal fun SafetySettingsSnapshot.toSafetyGatewayMap(): Map<String, Any?> = mapOf(
  "automationModeId" to automationMode.wireValue,
  "rollbackJournalEnabled" to rollbackJournalEnabled,
  "maxFilesPerBatch" to maxFilesPerBatch,
  "maxAgentTurns" to maxAgentTurns,
  "maxToolCalls" to maxToolCalls,
  "undoWindowHours" to undoWindowHours,
  "fileChangesPolicyId" to fileChangesPolicy.wireValue,
  "fileDeletesPolicyId" to fileDeletesPolicy.wireValue,
  "shellCommandsPolicyId" to shellCommandsPolicy.wireValue,
  "externalAccessModeId" to externalAccessMode.wireValue,
  "locations" to locations.map { location -> location.toSafetyGatewayMap() },
  "workspaceAccessProfileId" to workspaceAccessProfile.wireValue,
  "readOnlyOutsideWorkspace" to readOnlyOutsideWorkspace,
  "liveContextModeId" to liveContextMode.wireValue,
  "memoryToolsEnabled" to memoryToolsEnabled,
)

internal fun safetySaveRequest(
  automationModeId: String,
  rollbackJournalEnabled: Boolean,
  maxFilesPerBatch: Int,
  maxAgentTurns: Int,
  maxToolCalls: Int,
  undoWindowHours: Int,
  fileChangesPolicyId: String,
  fileDeletesPolicyId: String,
  shellCommandsPolicyId: String,
  externalAccessModeId: String,
  photoLibraryEnabled: Boolean,
  downloadsEnabled: Boolean,
  documentsEnabled: Boolean,
  recordingsEnabled: Boolean,
  workspaceAccessProfileId: String,
  readOnlyOutsideWorkspace: Boolean,
  liveContextModeId: String,
  memoryToolsEnabled: Boolean,
): SaveSafetySettingsRequest = SaveSafetySettingsRequest(
  automationModeId = automationModeId,
  rollbackJournalEnabled = rollbackJournalEnabled,
  maxFilesPerBatch = maxFilesPerBatch,
  maxAgentTurns = maxAgentTurns,
  maxToolCalls = maxToolCalls,
  undoWindowHours = undoWindowHours,
  fileChangesPolicyId = fileChangesPolicyId,
  fileDeletesPolicyId = fileDeletesPolicyId,
  shellCommandsPolicyId = shellCommandsPolicyId,
  externalAccessModeId = externalAccessModeId,
  photoLibraryEnabled = photoLibraryEnabled,
  downloadsEnabled = downloadsEnabled,
  documentsEnabled = documentsEnabled,
  recordingsEnabled = recordingsEnabled,
  workspaceAccessProfileId = workspaceAccessProfileId,
  readOnlyOutsideWorkspace = readOnlyOutsideWorkspace,
  liveContextModeId = liveContextModeId,
  memoryToolsEnabled = memoryToolsEnabled,
)

private fun SafetySettingsLocationSnapshot.toSafetyGatewayMap(): Map<String, Any?> = mapOf(
  "id" to id,
  "enabled" to enabled,
)
