package com.opencray.app

import com.opencray.app.facade.skills.SkillsSnapshot
import com.opencray.runtime.skills.SkillPackageCheckReport
import com.opencray.runtime.skills.SkillPackageCheckResult
import com.opencray.runtime.skills.SkillPackageCheckStatus
import com.opencray.runtime.skills.SkillPackageUpdateReport
import com.opencray.runtime.skills.SkillPackageUpdateResult
import com.opencray.runtime.skills.SkillPackageUpdateStatus

internal class HostSkillsGatewayImpl(
  private val host: OpenCrayHostRuntime,
) : OpenCraySkillsGateway {
  override fun loadSkillsSnapshot(
    query: String,
    suggestedLimit: Int,
  ): Map<String, Any?> {
    val normalizedQuery = query.trim()
    val snapshot = if (normalizedQuery.isEmpty()) {
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
    return result.installedSkillId
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let(host.strings.skillInstalled)
      ?: host.strings.skillInstalled(
        normalizedSelectedSkillName.takeIf(String::isNotBlank) ?: normalizedSourceRef,
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
    return if (normalizedSelectedSkillNames.size == 1) {
      host.strings.skillInstalled(normalizedSelectedSkillNames.single())
    } else {
      "Installed ${result.installedCount} skills."
    }
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
    )
  }

  private fun renderInstalledSkillUpdateCheckMessage(
    requestedSkillId: String?,
    report: SkillPackageCheckReport,
  ): String {
    val requestedResult = requestedSkillId?.let { skillId ->
      report.results.firstOrNull { result -> result.skillId == skillId }
    }
    if (requestedResult != null) {
      return renderInstalledSkillUpdateCheckResult(requestedResult)
    }
    if (requestedSkillId != null) {
      return if (host.isChineseHostLocale()) {
        "未找到已安装的技能 '$requestedSkillId'。"
      } else {
        "Installed skill '$requestedSkillId' was not found."
      }
    }
    if (report.results.isEmpty()) {
      return if (host.isChineseHostLocale()) {
        "没有可检查更新的已安装技能。"
      } else {
        "No installed skills to check for updates."
      }
    }
    return if (host.isChineseHostLocale()) {
      "已检查 ${report.results.size} 个技能：可更新 ${report.updateAvailableCount} 个，已是最新 ${report.upToDateCount} 个，检查失败 ${report.sourceUnavailableCount + report.unsupportedCount} 个。"
    } else {
      "Checked ${report.results.size} skills: ${report.updateAvailableCount} update available, ${report.upToDateCount} up to date, ${report.sourceUnavailableCount + report.unsupportedCount} failed."
    }
  }

  private fun renderInstalledSkillUpdateCheckResult(
    result: SkillPackageCheckResult,
  ): String {
    val errorMessage = result.errorMessage?.trim()?.takeIf(String::isNotBlank)
    return when (result.status) {
      SkillPackageCheckStatus.UP_TO_DATE -> if (host.isChineseHostLocale()) {
        "技能 '${result.skillId}' 已是最新版本。"
      } else {
        "Skill '${result.skillId}' is up to date."
      }

      SkillPackageCheckStatus.UPDATE_AVAILABLE -> if (host.isChineseHostLocale()) {
        "技能 '${result.skillId}' 有可用更新。"
      } else {
        "Update available for '${result.skillId}'."
      }

      SkillPackageCheckStatus.SOURCE_UNAVAILABLE,
      SkillPackageCheckStatus.UNSUPPORTED_SOURCE,
      -> errorMessage ?: if (host.isChineseHostLocale()) {
        "无法检查技能 '${result.skillId}' 的更新。"
      } else {
        "Unable to check '${result.skillId}' for updates."
      }
    }
  }

  private fun renderInstalledSkillUpdateMessage(
    requestedSkillId: String?,
    report: SkillPackageUpdateReport,
  ): String {
    val requestedResult = requestedSkillId?.let { skillId ->
      report.results.firstOrNull { result -> result.skillId == skillId }
    }
    if (requestedResult != null) {
      return renderInstalledSkillUpdateResult(requestedResult)
    }
    if (requestedSkillId != null) {
      return if (host.isChineseHostLocale()) {
        "未找到已安装的技能 '$requestedSkillId'。"
      } else {
        "Installed skill '$requestedSkillId' was not found."
      }
    }
    if (report.results.isEmpty()) {
      return if (host.isChineseHostLocale()) {
        "没有可更新的已安装技能。"
      } else {
        "No installed skills to update."
      }
    }
    if (report.updatedCount == 0 && report.failedCount == 0) {
      return if (host.isChineseHostLocale()) {
        "所有已安装技能都已是最新版本。"
      } else {
        "All installed skills are already up to date."
      }
    }
    return if (host.isChineseHostLocale()) {
      "技能更新完成：已更新 ${report.updatedCount} 个，跳过 ${report.skippedCount} 个，失败 ${report.failedCount} 个。"
    } else {
      "Skill update finished: ${report.updatedCount} updated, ${report.skippedCount} skipped, ${report.failedCount} failed."
    }
  }

  private fun renderInstalledSkillUpdateResult(
    result: SkillPackageUpdateResult,
  ): String {
    val errorMessage = result.errorMessage?.trim()?.takeIf(String::isNotBlank)
    return when (result.status) {
      SkillPackageUpdateStatus.UPDATED -> if (host.isChineseHostLocale()) {
        "已更新技能 '${result.skillId}'。"
      } else {
        "Updated '${result.skillId}'."
      }

      SkillPackageUpdateStatus.SKIPPED -> if (result.checkStatus == SkillPackageCheckStatus.UP_TO_DATE) {
        if (host.isChineseHostLocale()) {
          "技能 '${result.skillId}' 已是最新版本。"
        } else {
          "Skill '${result.skillId}' is already up to date."
        }
      } else if (host.isChineseHostLocale()) {
        "已跳过技能 '${result.skillId}'。"
      } else {
        "Skipped '${result.skillId}'."
      }

      SkillPackageUpdateStatus.FAILED -> errorMessage ?: if (host.isChineseHostLocale()) {
        "无法更新技能 '${result.skillId}'。"
      } else {
        "Unable to update '${result.skillId}'."
      }
    }
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
