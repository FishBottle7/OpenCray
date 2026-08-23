package com.opencray.app

import com.opencray.app.facade.skills.SkillsSnapshot

internal class HostSkillsGatewayImpl(
  private val host: OpenCrayHostRuntime,
) : OpenCraySkillsGateway {
  override fun loadSkillsSnapshot(
    query: String,
    suggestedLimit: Int,
  ): Map<String, Any?> {
    val normalizedQuery = query.trim()
    val snapshot = if (normalizedQuery.isEmpty() && suggestedLimit <= 0) {
      loadDefaultSkillsSnapshot()
    } else {
      loadQueriedSkillsSnapshot(
        query = normalizedQuery,
        suggestedLimit = suggestedLimit,
      )
    }
    return snapshot.toGatewayMap()
  }

  override fun observeSkills(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    host.observeWithInitial(
      listeners = host.skillsListeners,
      initialPayload = loadSkillsSnapshot(query = "", suggestedLimit = 0),
      listener = listener,
    )

  override fun setSkillEnabled(skillId: String, enabled: Boolean) {
    synchronized(host.lock) {
      require(host.skillsFacade.setSkillEnabled(skillId = skillId, enabled = enabled)) {
        "Skill '$skillId' is not installed."
      }
    }
    host.emitSkillsSnapshot()
  }

  override fun installSuggestedSkill(skillId: String): String {
    return installSkillSource(sourceRef = skillId, selectedSkillName = "")
  }

  override fun installSkillSource(
    sourceRef: String,
    selectedSkillName: String,
  ): String {
    val normalizedSourceRef = sourceRef.trim()
    val normalizedSelectedSkillName = selectedSkillName.trim()
    require(normalizedSourceRef.isNotEmpty()) {
      "Skill source cannot be blank."
    }
    val result = synchronized(host.lock) {
      host.skillsFacade.installSkillSource(
        sourceRef = normalizedSourceRef,
        selectedSkillName = normalizedSelectedSkillName,
      )
    }
    require(result.succeeded) {
      result.errorMessage?.trim()?.takeIf(String::isNotBlank)
        ?: "Unable to install '$normalizedSourceRef'."
    }
    host.emitSkillsSnapshot()
    return renderInstalledSkillMessage(
      installedSkillId = result.installedSkillId,
      selectedSkillName = normalizedSelectedSkillName,
      sourceRef = normalizedSourceRef,
      skillInstalled = host.strings.skillInstalled,
    )
  }

  override fun installSkillSourceBatch(
    sourceRef: String,
    selectedSkillNames: List<String>,
  ): String {
    val normalizedSourceRef = sourceRef.trim()
    val normalizedSelectedSkillNames = selectedSkillNames
      .asSequence()
      .map(String::trim)
      .filter(String::isNotBlank)
      .distinct()
      .toList()
    require(normalizedSourceRef.isNotEmpty()) {
      "Skill source cannot be blank."
    }
    require(normalizedSelectedSkillNames.isNotEmpty()) {
      "At least one skill must be selected."
    }
    val attempt = synchronized(host.lock) {
      host.skillsFacade.installSkillSourceBatch(
        sourceRef = normalizedSourceRef,
        selectedSkillNames = normalizedSelectedSkillNames,
      )
    }
    val result = requireNotNull(attempt.result) {
      attempt.errorMessage?.trim()?.takeIf(String::isNotBlank)
        ?: "Unable to install selected skills from '$normalizedSourceRef'."
    }
    if (result.failedCount > 0) {
      throw IllegalStateException(
        result.entries.firstNotNullOfOrNull { entry -> entry.errorMessage?.trim()?.takeIf(String::isNotBlank) }
          ?: "Unable to install selected skills from '$normalizedSourceRef'.",
      )
    }
    host.emitSkillsSnapshot()
    return renderInstalledSkillBatchMessage(
      selectedSkillNames = normalizedSelectedSkillNames,
      result = result,
      skillInstalled = host.strings.skillInstalled,
    )
  }

  override fun inspectSkillSource(sourceRef: String): Map<String, Any?> {
    val normalizedSourceRef = sourceRef.trim()
    require(normalizedSourceRef.isNotEmpty()) {
      "Skill source cannot be blank."
    }
    val attempt = synchronized(host.lock) {
      host.skillsFacade.inspectSkillSource(normalizedSourceRef)
    }
    val result = requireNotNull(attempt.result) {
      attempt.errorMessage?.trim()?.takeIf(String::isNotBlank)
        ?: "Unable to inspect '$normalizedSourceRef'."
    }
    return result.toGatewayMap()
  }

  override fun deleteInstalledSkill(skillId: String): String {
    val normalizedSkillId = skillId.trim()
    require(normalizedSkillId.isNotEmpty()) {
      "Skill id cannot be blank."
    }
    val removed = synchronized(host.lock) {
      host.skillsFacade.deleteInstalledSkill(normalizedSkillId)
    }
    require(removed) {
      "Unable to remove '$normalizedSkillId'."
    }
    host.emitSkillsSnapshot()
    return host.strings.skillRemoved(normalizedSkillId)
  }

  override fun refreshSkills(): String {
    synchronized(host.lock) {
      host.skillsFacade.refresh()
    }
    host.emitSkillsSnapshot()
    return host.strings.skillsReloaded
  }

  override fun checkInstalledSkillUpdates(skillId: String): String {
    val normalizedSkillId = skillId.trim()
    val report = synchronized(host.lock) {
      host.skillsFacade.checkInstalledSkillUpdates(normalizedSkillId)
    }
    return renderInstalledSkillUpdateCheckMessage(
      requestedSkillId = normalizedSkillId.takeIf(String::isNotBlank),
      report = report,
      localeTag = host.strings.localeTag,
    )
  }

  override fun updateInstalledSkill(skillId: String): String {
    val normalizedSkillId = skillId.trim()
    val report = synchronized(host.lock) {
      host.skillsFacade.updateInstalledSkill(normalizedSkillId)
    }
    if (report.updatedCount == 0 && report.failedCount > 0 && report.skippedCount == 0) {
      throw IllegalStateException(
        report.results.firstNotNullOfOrNull { result -> result.errorMessage?.trim()?.takeIf(String::isNotBlank) }
          ?: "SkillsUpdate failed.",
      )
    }
    host.emitSkillsSnapshot()
    return renderInstalledSkillUpdateMessage(
      requestedSkillId = normalizedSkillId.takeIf(String::isNotBlank),
      report = report,
      localeTag = host.strings.localeTag,
    )
  }

  override fun loadSkillInstructions(skillId: String): Map<String, Any?> {
    val instructions = synchronized(host.lock) {
      host.skillsFacade.loadInstructions(skillId)
    }
    requireNotNull(instructions) {
      "Skill '$skillId' is unavailable."
    }
    return instructions.toGatewayMap()
  }

  override fun loadSuggestedSkillInstructions(
    sourceRef: String,
    selectedSkillName: String,
  ): Map<String, Any?> {
    val normalizedSourceRef = sourceRef.trim()
    require(normalizedSourceRef.isNotEmpty()) {
      "Skill source cannot be blank."
    }
    val instructions = synchronized(host.lock) {
      host.skillsFacade.loadSuggestedInstructions(
        sourceRef = normalizedSourceRef,
        selectedSkillName = selectedSkillName.trim(),
      )
    }
    requireNotNull(instructions) {
      "Skill source '$normalizedSourceRef' is unavailable."
    }
    return instructions.toGatewayMap()
  }

  override fun activateSkillsInstallSource(sourceId: String): String =
    synchronized(host.lock) { host.skillsFacade.activateInstallSource(sourceId) }

  private fun loadDefaultSkillsSnapshot(): SkillsSnapshot {
    return synchronized(host.lock) { host.skillsFacade.loadSnapshot() }
  }

  private fun loadQueriedSkillsSnapshot(
    query: String,
    suggestedLimit: Int,
  ): SkillsSnapshot = synchronized(host.lock) {
    host.skillsFacade.loadSnapshot(
      query = query,
      suggestedLimit = suggestedLimit,
    )
  }
}
