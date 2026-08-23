package com.opencray.app

import com.opencray.app.facade.skills.SkillsFacade

internal class ServiceOwnedSkillsGateway(
  @Suppress("unused")
  private val delegate: OpenCraySkillsGateway? = null,
  private var skillsFacade: SkillsFacade,
  private var localeTag: String,
  private var skillInstalled: (String) -> String,
  private var skillRemoved: (String) -> String,
  private var skillsReloaded: String,
  private val snapshotNotifier: () -> Unit,
  private val mainThreadPoster: MainThreadPoster = ImmediateMainThreadPoster,
) : OpenCraySkillsGateway {
  private val lock = Any()
  private val listeners = linkedSetOf<(Map<String, Any?>) -> Unit>()

  override fun loadSkillsSnapshot(
    query: String,
    suggestedLimit: Int,
  ): Map<String, Any?> {
    val normalizedQuery = query.trim()
    val snapshot = if (normalizedQuery.isEmpty() && suggestedLimit <= 0) {
      skillsFacade.loadSnapshot()
    } else {
      skillsFacade.loadSnapshot(
        query = normalizedQuery,
        suggestedLimit = suggestedLimit,
      )
    }
    return snapshot.toGatewayMap()
  }

  override fun observeSkills(listener: (Map<String, Any?>) -> Unit): () -> Unit {
    synchronized(lock) {
      listeners += listener
    }
    mainThreadPoster.post {
      listener(loadSkillsSnapshot(query = "", suggestedLimit = 0))
    }
    return {
      synchronized(lock) {
        listeners -= listener
      }
    }
  }

  override fun setSkillEnabled(skillId: String, enabled: Boolean) {
    require(skillsFacade.setSkillEnabled(skillId = skillId, enabled = enabled)) {
      "Skill '$skillId' is not installed."
    }
    notifySkillsSnapshotChanged()
  }

  override fun installSuggestedSkill(skillId: String): String =
    installSkillSource(sourceRef = skillId, selectedSkillName = "")

  override fun installSkillSource(
    sourceRef: String,
    selectedSkillName: String,
  ): String {
    val normalizedSourceRef = sourceRef.trim()
    val normalizedSelectedSkillName = selectedSkillName.trim()
    require(normalizedSourceRef.isNotEmpty()) {
      "Skill source cannot be blank."
    }
    val result = skillsFacade.installSkillSource(
      sourceRef = normalizedSourceRef,
      selectedSkillName = normalizedSelectedSkillName,
    )
    require(result.succeeded) {
      result.errorMessage?.trim()?.takeIf(String::isNotBlank)
        ?: "Unable to install '$normalizedSourceRef'."
    }
    notifySkillsSnapshotChanged()
    return renderInstalledSkillMessage(
      installedSkillId = result.installedSkillId,
      selectedSkillName = normalizedSelectedSkillName,
      sourceRef = normalizedSourceRef,
      skillInstalled = skillInstalled,
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
    val attempt = skillsFacade.installSkillSourceBatch(
      sourceRef = normalizedSourceRef,
      selectedSkillNames = normalizedSelectedSkillNames,
    )
    val result = requireNotNull(attempt.result) {
      attempt.errorMessage?.trim()?.takeIf(String::isNotBlank)
        ?: "Unable to install selected skills from '$normalizedSourceRef'."
    }
    if (result.failedCount > 0) {
      throw IllegalStateException(
        result.entries.firstNotNullOfOrNull { entry ->
          entry.errorMessage?.trim()?.takeIf(String::isNotBlank)
        } ?: "Unable to install selected skills from '$normalizedSourceRef'.",
      )
    }
    notifySkillsSnapshotChanged()
    return renderInstalledSkillBatchMessage(
      selectedSkillNames = normalizedSelectedSkillNames,
      result = result,
      skillInstalled = skillInstalled,
    )
  }

  override fun inspectSkillSource(sourceRef: String): Map<String, Any?> {
    val normalizedSourceRef = sourceRef.trim()
    require(normalizedSourceRef.isNotEmpty()) {
      "Skill source cannot be blank."
    }
    val attempt = skillsFacade.inspectSkillSource(normalizedSourceRef)
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
    val removed = skillsFacade.deleteInstalledSkill(normalizedSkillId)
    require(removed) {
      "Unable to remove '$normalizedSkillId'."
    }
    notifySkillsSnapshotChanged()
    return skillRemoved(normalizedSkillId)
  }

  override fun refreshSkills(): String {
    skillsFacade.refresh()
    notifySkillsSnapshotChanged()
    return skillsReloaded
  }

  override fun checkInstalledSkillUpdates(skillId: String): String {
    val normalizedSkillId = skillId.trim()
    val report = skillsFacade.checkInstalledSkillUpdates(normalizedSkillId)
    return renderInstalledSkillUpdateCheckMessage(
      requestedSkillId = normalizedSkillId.takeIf(String::isNotBlank),
      report = report,
      localeTag = localeTag,
    )
  }

  override fun updateInstalledSkill(skillId: String): String {
    val normalizedSkillId = skillId.trim()
    val report = skillsFacade.updateInstalledSkill(normalizedSkillId)
    if (report.updatedCount == 0 && report.failedCount > 0 && report.skippedCount == 0) {
      throw IllegalStateException(
        report.results.firstNotNullOfOrNull { result ->
          result.errorMessage?.trim()?.takeIf(String::isNotBlank)
        } ?: "SkillsUpdate failed.",
      )
    }
    notifySkillsSnapshotChanged()
    return renderInstalledSkillUpdateMessage(
      requestedSkillId = normalizedSkillId.takeIf(String::isNotBlank),
      report = report,
      localeTag = localeTag,
    )
  }

  override fun loadSkillInstructions(skillId: String): Map<String, Any?> {
    val instructions = skillsFacade.loadInstructions(skillId)
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
    val instructions = skillsFacade.loadSuggestedInstructions(
      sourceRef = normalizedSourceRef,
      selectedSkillName = selectedSkillName.trim(),
    )
    requireNotNull(instructions) {
      "Skill source '$normalizedSourceRef' is unavailable."
    }
    return instructions.toGatewayMap()
  }

  override fun activateSkillsInstallSource(sourceId: String): String =
    skillsFacade.activateInstallSource(sourceId)

  internal fun updateLocalizedResources(
    skillsFacade: SkillsFacade,
    localeTag: String,
    skillInstalled: (String) -> String,
    skillRemoved: (String) -> String,
    skillsReloaded: String,
  ) {
    synchronized(lock) {
      this.skillsFacade = skillsFacade
      this.localeTag = localeTag
      this.skillInstalled = skillInstalled
      this.skillRemoved = skillRemoved
      this.skillsReloaded = skillsReloaded
    }
  }

  internal fun emitLocalizedSnapshotChanged() {
    emitSkillsSnapshot()
  }

  private fun notifySkillsSnapshotChanged() {
    emitSkillsSnapshot()
    snapshotNotifier()
  }

  private fun emitSkillsSnapshot() {
    val currentListeners = synchronized(lock) { listeners.toList() }
    if (currentListeners.isEmpty()) {
      return
    }
    val payload = loadSkillsSnapshot(query = "", suggestedLimit = 0)
    mainThreadPoster.post {
      currentListeners.forEach { listener -> listener(payload) }
    }
  }
}
