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
