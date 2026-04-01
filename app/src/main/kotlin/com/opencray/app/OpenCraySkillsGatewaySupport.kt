package com.opencray.app

import com.opencray.app.facade.skills.InstallSourceSnapshot
import com.opencray.app.facade.skills.InstalledSkillSnapshot
import com.opencray.app.facade.skills.SkillInstructionsSnapshot
import com.opencray.app.facade.skills.SkillsSnapshot
import com.opencray.app.facade.skills.SuggestedSkillSnapshot
import com.opencray.runtime.skills.SkillPackageBatchInstallResult
import com.opencray.runtime.skills.SkillPackageCheckReport
import com.opencray.runtime.skills.SkillPackageCheckResult
import com.opencray.runtime.skills.SkillPackageCheckStatus
import com.opencray.runtime.skills.SkillPackageUpdateReport
import com.opencray.runtime.skills.SkillPackageUpdateResult
import com.opencray.runtime.skills.SkillPackageUpdateStatus
import com.opencray.runtime.skills.SkillSourceInspectionCandidate
import com.opencray.runtime.skills.SkillSourceInspectionResult
import java.util.Locale

internal fun SkillsSnapshot.toGatewayMap(): Map<String, Any?> = mapOf(
  "installedSkills" to installedSkills.map { skill -> skill.toGatewayMap() },
  "installSources" to installSources.map { source -> source.toGatewayMap() },
  "suggestedSkills" to suggestedSkills.map { suggestion -> suggestion.toGatewayMap() },
  "suggestedSkillsMayHaveMore" to suggestedSkillsMayHaveMore,
)

internal fun SkillSourceInspectionResult.toGatewayMap(): Map<String, Any?> = mapOf(
  "sourceType" to sourceType,
  "sourceRef" to sourceRef,
  "sourcePath" to sourcePath.orEmpty(),
  "resolvedRevision" to resolvedRevision.orEmpty(),
  "resolvedCommitSha" to resolvedCommitSha.orEmpty(),
  "candidates" to candidates.map { candidate -> candidate.toGatewayMap() },
)

internal fun SkillInstructionsSnapshot.toGatewayMap(): Map<String, Any?> = mapOf(
  "id" to id,
  "name" to name,
  "description" to description,
  "body" to body,
  "sourceDirectoryPath" to sourceDirectoryPath,
  "isEnabled" to isEnabled,
  "canDelete" to canDelete,
)

private fun InstalledSkillSnapshot.toGatewayMap(): Map<String, Any?> = mapOf(
  "id" to id,
  "name" to name,
  "description" to description,
  "isEnabled" to isEnabled,
  "sourceDirectoryPath" to sourceDirectoryPath,
  "canDelete" to canDelete,
)

private fun InstallSourceSnapshot.toGatewayMap(): Map<String, Any?> = mapOf(
  "id" to id,
  "title" to title,
  "subtitle" to subtitle,
  "actionLabel" to actionLabel,
  "isAvailable" to isAvailable,
)

private fun SuggestedSkillSnapshot.toGatewayMap(): Map<String, Any?> = mapOf(
  "id" to id,
  "name" to name,
  "description" to description,
  "sourceRef" to sourceRef,
  "sourceLabel" to sourceLabel,
  "installs" to installs,
  "detailUrl" to detailUrl,
)

private fun SkillSourceInspectionCandidate.toGatewayMap(): Map<String, Any?> = mapOf(
  "name" to name,
  "description" to description,
  "relativePath" to relativePath,
)

internal fun renderInstalledSkillMessage(
  installedSkillId: String?,
  selectedSkillName: String,
  sourceRef: String,
  skillInstalled: (String) -> String,
): String = installedSkillId
  ?.trim()
  ?.takeIf(String::isNotBlank)
  ?.let(skillInstalled)
  ?: skillInstalled(selectedSkillName.takeIf(String::isNotBlank) ?: sourceRef)

internal fun renderInstalledSkillBatchMessage(
  selectedSkillNames: List<String>,
  result: SkillPackageBatchInstallResult,
  skillInstalled: (String) -> String,
): String = if (selectedSkillNames.size == 1) {
  skillInstalled(selectedSkillNames.single())
} else {
  "Installed ${result.installedCount} skills."
}

internal fun renderInstalledSkillUpdateCheckMessage(
  requestedSkillId: String?,
  report: SkillPackageCheckReport,
  localeTag: String,
): String {
  val requestedResult = requestedSkillId?.let { skillId ->
    report.results.firstOrNull { result -> result.skillId == skillId }
  }
  if (requestedResult != null) {
    return renderInstalledSkillUpdateCheckResult(requestedResult, localeTag)
  }
  if (requestedSkillId != null) {
    return if (isChineseLocaleTag(localeTag)) {
      "未找到已安装的技能 '$requestedSkillId'。"
    } else {
      "Installed skill '$requestedSkillId' was not found."
    }
  }
  if (report.results.isEmpty()) {
    return if (isChineseLocaleTag(localeTag)) {
      "没有可检查更新的已安装技能。"
    } else {
      "No installed skills to check for updates."
    }
  }
  return if (isChineseLocaleTag(localeTag)) {
    "已检查 ${report.results.size} 个技能：可更新 ${report.updateAvailableCount} 个，已是最新 ${report.upToDateCount} 个，检查失败 ${report.sourceUnavailableCount + report.unsupportedCount} 个。"
  } else {
    "Checked ${report.results.size} skills: ${report.updateAvailableCount} update available, ${report.upToDateCount} up to date, ${report.sourceUnavailableCount + report.unsupportedCount} failed."
  }
}

internal fun renderInstalledSkillUpdateMessage(
  requestedSkillId: String?,
  report: SkillPackageUpdateReport,
  localeTag: String,
): String {
  val requestedResult = requestedSkillId?.let { skillId ->
    report.results.firstOrNull { result -> result.skillId == skillId }
  }
  if (requestedResult != null) {
    return renderInstalledSkillUpdateResult(requestedResult, localeTag)
  }
  if (requestedSkillId != null) {
    return if (isChineseLocaleTag(localeTag)) {
      "未找到已安装的技能 '$requestedSkillId'。"
    } else {
      "Installed skill '$requestedSkillId' was not found."
    }
  }
  if (report.results.isEmpty()) {
    return if (isChineseLocaleTag(localeTag)) {
      "没有可更新的已安装技能。"
    } else {
      "No installed skills to update."
    }
  }
  if (report.updatedCount == 0 && report.failedCount == 0) {
    return if (isChineseLocaleTag(localeTag)) {
      "所有已安装技能都已是最新版本。"
    } else {
      "All installed skills are already up to date."
    }
  }
  return if (isChineseLocaleTag(localeTag)) {
    "技能更新完成：已更新 ${report.updatedCount} 个，跳过 ${report.skippedCount} 个，失败 ${report.failedCount} 个。"
  } else {
    "Skill update finished: ${report.updatedCount} updated, ${report.skippedCount} skipped, ${report.failedCount} failed."
  }
}

private fun renderInstalledSkillUpdateCheckResult(
  result: SkillPackageCheckResult,
  localeTag: String,
): String {
  val errorMessage = result.errorMessage?.trim()?.takeIf(String::isNotBlank)
  return when (result.status) {
    SkillPackageCheckStatus.UP_TO_DATE -> if (isChineseLocaleTag(localeTag)) {
      "技能 '${result.skillId}' 已是最新版本。"
    } else {
      "Skill '${result.skillId}' is up to date."
    }

    SkillPackageCheckStatus.UPDATE_AVAILABLE -> if (isChineseLocaleTag(localeTag)) {
      "技能 '${result.skillId}' 有可用更新。"
    } else {
      "Update available for '${result.skillId}'."
    }

    SkillPackageCheckStatus.SOURCE_UNAVAILABLE,
    SkillPackageCheckStatus.UNSUPPORTED_SOURCE,
    -> errorMessage ?: if (isChineseLocaleTag(localeTag)) {
      "无法检查技能 '${result.skillId}' 的更新。"
    } else {
      "Unable to check '${result.skillId}' for updates."
    }
  }
}

private fun renderInstalledSkillUpdateResult(
  result: SkillPackageUpdateResult,
  localeTag: String,
): String {
  val errorMessage = result.errorMessage?.trim()?.takeIf(String::isNotBlank)
  return when (result.status) {
    SkillPackageUpdateStatus.UPDATED -> if (isChineseLocaleTag(localeTag)) {
      "已更新技能 '${result.skillId}'。"
    } else {
      "Updated '${result.skillId}'."
    }

    SkillPackageUpdateStatus.SKIPPED -> if (result.checkStatus == SkillPackageCheckStatus.UP_TO_DATE) {
      if (isChineseLocaleTag(localeTag)) {
        "技能 '${result.skillId}' 已是最新版本。"
      } else {
        "Skill '${result.skillId}' is already up to date."
      }
    } else if (isChineseLocaleTag(localeTag)) {
      "已跳过技能 '${result.skillId}'。"
    } else {
      "Skipped '${result.skillId}'."
    }

    SkillPackageUpdateStatus.FAILED -> errorMessage ?: if (isChineseLocaleTag(localeTag)) {
      "无法更新技能 '${result.skillId}'。"
    } else {
      "Unable to update '${result.skillId}'."
    }
  }
}

private fun isChineseLocaleTag(localeTag: String): Boolean =
  localeTag.trim().lowercase(Locale.US).startsWith("zh")
