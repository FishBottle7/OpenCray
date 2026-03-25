package com.opencray.app

import com.opencray.app.facade.safety.SafetySettingsSnapshot
import com.opencray.policy.SafetySettingsMetadataKeys

internal fun buildTaskSafetyMetadata(
  snapshot: SafetySettingsSnapshot,
  approvedReadRoots: ApprovedReadRootsSnapshot,
): Map<String, String> = buildMap {
  put(SafetySettingsMetadataKeys.CHAT_MODE, snapshot.automationMode.chatMetadataLabel)
  put(
    SafetySettingsMetadataKeys.EXECUTION_MODE,
    snapshot.automationMode.executionMode.name,
  )
  put(
    SafetySettingsMetadataKeys.FILE_CHANGES_POLICY_ID,
    snapshot.fileChangesPolicy.wireValue,
  )
  put(
    SafetySettingsMetadataKeys.FILE_DELETES_POLICY_ID,
    snapshot.fileDeletesPolicy.wireValue,
  )
  put(
    SafetySettingsMetadataKeys.SHELL_COMMANDS_POLICY_ID,
    snapshot.shellCommandsPolicy.wireValue,
  )
  put(
    SafetySettingsMetadataKeys.EXTERNAL_ACCESS_MODE_ID,
    snapshot.externalAccessMode.wireValue,
  )
  put(
    SafetySettingsMetadataKeys.WORKSPACE_ACCESS_PROFILE_ID,
    snapshot.workspaceAccessProfile.wireValue,
  )
  put(
    SafetySettingsMetadataKeys.READ_ONLY_OUTSIDE_WORKSPACE,
    snapshot.readOnlyOutsideWorkspace.toString(),
  )
  put(
    SafetySettingsMetadataKeys.APPROVED_READ_ROOTS,
    approvedReadRoots.summary,
  )
}
