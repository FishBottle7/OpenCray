package com.opencray.app

internal interface OpenCraySkillsGateway {
  fun loadSkillsSnapshot(
    query: String = "",
    suggestedLimit: Int = 0,
  ): Map<String, Any?>

  fun observeSkills(listener: (Map<String, Any?>) -> Unit): () -> Unit

  fun setSkillEnabled(skillId: String, enabled: Boolean)

  fun installSuggestedSkill(skillId: String): String

  fun installSkillSource(
    sourceRef: String,
    selectedSkillName: String = "",
  ): String

  fun installSkillSourceBatch(
    sourceRef: String,
    selectedSkillNames: List<String>,
  ): String

  fun inspectSkillSource(sourceRef: String): Map<String, Any?>

  fun deleteInstalledSkill(skillId: String): String

  fun refreshSkills(): String

  fun checkInstalledSkillUpdates(skillId: String = ""): String

  fun updateInstalledSkill(skillId: String = ""): String

  fun loadSkillInstructions(skillId: String): Map<String, Any?>

  fun loadSuggestedSkillInstructions(
    sourceRef: String,
    selectedSkillName: String = "",
  ): Map<String, Any?>

  fun activateSkillsInstallSource(sourceId: String): String
}

internal sealed interface OpenCraySkillsWriteCommand {
  data class SetSkillEnabled(
    val skillId: String,
    val enabled: Boolean,
  ) : OpenCraySkillsWriteCommand

  data class InstallSuggestedSkill(
    val skillId: String,
  ) : OpenCraySkillsWriteCommand

  data class InstallSkillSource(
    val sourceRef: String,
    val selectedSkillName: String,
  ) : OpenCraySkillsWriteCommand

  data class InstallSkillSourceBatch(
    val sourceRef: String,
    val selectedSkillNames: List<String>,
  ) : OpenCraySkillsWriteCommand

  data class InspectSkillSource(
    val sourceRef: String,
  ) : OpenCraySkillsWriteCommand

  data class DeleteInstalledSkill(
    val skillId: String,
  ) : OpenCraySkillsWriteCommand

  data object RefreshSkills : OpenCraySkillsWriteCommand

  data class CheckInstalledSkillUpdates(
    val skillId: String,
  ) : OpenCraySkillsWriteCommand

  data class UpdateInstalledSkill(
    val skillId: String,
  ) : OpenCraySkillsWriteCommand

  data class ActivateSkillsInstallSource(
    val sourceId: String,
  ) : OpenCraySkillsWriteCommand
}

internal sealed interface OpenCraySkillsWriteDispatchResult {
  data object Completed : OpenCraySkillsWriteDispatchResult

  data class Message(
    val value: String,
  ) : OpenCraySkillsWriteDispatchResult

  data class Payload(
    val value: Map<String, Any?>,
  ) : OpenCraySkillsWriteDispatchResult
}

internal fun OpenCraySkillsGateway.dispatchSkillsWriteCommand(
  command: OpenCraySkillsWriteCommand,
): OpenCraySkillsWriteDispatchResult = when (command) {
  is OpenCraySkillsWriteCommand.SetSkillEnabled -> {
    setSkillEnabled(
      skillId = command.skillId,
      enabled = command.enabled,
    )
    OpenCraySkillsWriteDispatchResult.Completed
  }

  is OpenCraySkillsWriteCommand.InstallSuggestedSkill -> OpenCraySkillsWriteDispatchResult.Message(
    installSuggestedSkill(command.skillId),
  )

  is OpenCraySkillsWriteCommand.InstallSkillSource -> OpenCraySkillsWriteDispatchResult.Message(
    installSkillSource(
      sourceRef = command.sourceRef,
      selectedSkillName = command.selectedSkillName,
    ),
  )

  is OpenCraySkillsWriteCommand.InstallSkillSourceBatch -> OpenCraySkillsWriteDispatchResult.Message(
    installSkillSourceBatch(
      sourceRef = command.sourceRef,
      selectedSkillNames = command.selectedSkillNames,
    ),
  )

  is OpenCraySkillsWriteCommand.InspectSkillSource -> OpenCraySkillsWriteDispatchResult.Payload(
    inspectSkillSource(command.sourceRef),
  )

  is OpenCraySkillsWriteCommand.DeleteInstalledSkill -> OpenCraySkillsWriteDispatchResult.Message(
    deleteInstalledSkill(command.skillId),
  )

  OpenCraySkillsWriteCommand.RefreshSkills -> OpenCraySkillsWriteDispatchResult.Message(
    refreshSkills(),
  )

  is OpenCraySkillsWriteCommand.CheckInstalledSkillUpdates -> OpenCraySkillsWriteDispatchResult.Message(
    checkInstalledSkillUpdates(command.skillId),
  )

  is OpenCraySkillsWriteCommand.UpdateInstalledSkill -> OpenCraySkillsWriteDispatchResult.Message(
    updateInstalledSkill(command.skillId),
  )

  is OpenCraySkillsWriteCommand.ActivateSkillsInstallSource -> OpenCraySkillsWriteDispatchResult.Message(
    activateSkillsInstallSource(command.sourceId),
  )
}
