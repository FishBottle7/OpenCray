package com.opencray.app.facade.skills

import android.content.Context
import com.opencray.app.AppSkillsStorage
import com.opencray.app.OpenCrayLocaleManager
import com.opencray.runtime.skills.SkillInstallManifestStore
import com.opencray.runtime.skills.SkillPackageManager
import com.opencray.runtime.skills.SkillPackageInstallAttempt
import com.opencray.skills.LoadedSkill
import com.opencray.skills.SkillLoader
import java.io.File
import org.opencray.app.R

private const val PREFERENCES_NAME = "opencray.skills.workspace"
private const val PREF_PREFIX_ENABLED = "enabled:"

private const val INSTALL_SOURCE_CURATED = "curated-library"
private const val INSTALL_SOURCE_LOCAL = "local-path"
private const val INSTALL_SOURCE_GITHUB = "github-url"
private const val INSTALL_SOURCE_GITLAB = "gitlab-url"
private val WINDOWS_ABSOLUTE_PATH_REGEX: Regex = Regex("^[A-Za-z]:[\\\\/].+")

data class SkillsSnapshot(
  val installedSkills: List<InstalledSkillSnapshot>,
  val installSources: List<InstallSourceSnapshot>,
  val suggestedSkills: List<SuggestedSkillSnapshot>,
)

data class InstalledSkillSnapshot(
  val id: String,
  val name: String,
  val description: String,
  val isEnabled: Boolean,
  val sourceDirectoryPath: String,
  val canDelete: Boolean,
)

data class InstallSourceSnapshot(
  val id: String,
  val title: String,
  val subtitle: String,
  val actionLabel: String,
  val isAvailable: Boolean,
)

data class SuggestedSkillSnapshot(
  val id: String,
  val name: String,
  val description: String,
  val sourceRef: String,
  val sourceLabel: String,
)

data class SkillInstructionsSnapshot(
  val id: String,
  val name: String,
  val description: String,
  val body: String,
  val sourceDirectoryPath: String,
  val isEnabled: Boolean,
  val canDelete: Boolean,
)

data class SkillInstallRequestResult(
  val installedSkillId: String? = null,
  val errorMessage: String? = null,
) {
  val succeeded: Boolean
    get() = installedSkillId != null
}

interface SkillsFacade {
  fun loadSnapshot(query: String = ""): SkillsSnapshot

  fun setSkillEnabled(skillId: String, enabled: Boolean): Boolean

  fun installSkillSource(sourceRef: String): SkillInstallRequestResult

  fun installSuggestedSkill(skillId: String): Boolean

  fun deleteInstalledSkill(skillId: String): Boolean

  fun refresh()

  fun loadInstructions(skillId: String): SkillInstructionsSnapshot?

  fun enabledSkillRoots(): List<File>

  fun activateInstallSource(sourceId: String): String
}

internal class LocalSkillsFacade private constructor(
  private val context: Context,
) : SkillsFacade {
  private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
  private val managedRoot = AppSkillsStorage.managedSkillsRootForContext(context)
  private val catalogRoot = AppSkillsStorage.catalogSkillsRootForContext(context)
  private val packageManager = SkillPackageManager(
    managedRoot = managedRoot,
    catalogRoot = catalogRoot,
    manifestStore = SkillInstallManifestStore.fromFile(
      AppSkillsStorage.manifestFileForContext(context),
    ),
  )

  override fun loadSnapshot(query: String): SkillsSnapshot {
    val normalizedQuery = query.trim()
    val installedSkills = loadInstalledSkills()
    return SkillsSnapshot(
      installedSkills = installedSkills,
      installSources = installSources(),
      suggestedSkills = loadSuggestedSkills(
        query = normalizedQuery,
        installedSkills = installedSkills,
      ),
    )
  }

  override fun installSkillSource(sourceRef: String): SkillInstallRequestResult {
    val normalizedSourceRef = sourceRef.trim()
    if (normalizedSourceRef.isEmpty()) {
      return SkillInstallRequestResult(
        errorMessage = context.getString(R.string.skills_install_error_source_blank),
      )
    }
    if (loadCatalogSkills().any { skill -> skill.name == normalizedSourceRef }) {
      val result = runCatching {
        packageManager.installFromCatalog(normalizedSourceRef)
      }.getOrNull() ?: return SkillInstallRequestResult(
        errorMessage = context.getString(
          R.string.skills_install_error_source_failed,
          normalizedSourceRef,
        ),
      )
      enableInstalledSkill(result.skillId)
      return SkillInstallRequestResult(installedSkillId = result.skillId)
    }
    packageManager.resolveRemoteSource(normalizedSourceRef)?.let {
      return installAttempt(
        attempt = packageManager.installFromRemoteSource(normalizedSourceRef),
        fallbackMessage = context.getString(
          R.string.skills_install_error_source_failed,
          normalizedSourceRef,
        ),
      )
    }
    if (looksLikeExplicitLocalSkillSource(normalizedSourceRef)) {
      return installAttempt(
        attempt = packageManager.installFromLocalSource(
          sourcePath = File(normalizedSourceRef),
          sourceRef = normalizedSourceRef,
        ),
        fallbackMessage = context.getString(
          R.string.skills_install_error_source_failed,
          normalizedSourceRef,
        ),
      )
    }
    return SkillInstallRequestResult(
      errorMessage = context.getString(
        R.string.skills_install_error_source_unrecognized,
        normalizedSourceRef,
      ),
    )
  }

  override fun setSkillEnabled(skillId: String, enabled: Boolean): Boolean {
    val skillName = skillId.trim()
    if (skillName.isEmpty()) {
      return false
    }
    val installed = loadManagedSkills().any { skill -> skill.name == skillName }
    if (!installed) {
      return false
    }
    preferences.edit().putBoolean(preferenceKey(skillName), enabled).apply()
    return true
  }

  override fun installSuggestedSkill(skillId: String): Boolean {
    val result = installSkillSource(skillId)
    return result.succeeded
  }

  override fun deleteInstalledSkill(skillId: String): Boolean {
    val result = runCatching {
      packageManager.removeInstalledSkill(skillId)
    }.getOrNull() ?: return false
    preferences.edit().remove(preferenceKey(result.skillId)).apply()
    return true
  }

  override fun refresh() {
    // Filesystem-backed snapshots reload on every access, so refresh is an explicit rescan hook.
    runCatching {
      packageManager.refreshManifest()
    }
    loadManagedSkills()
    loadCatalogSkills()
  }

  override fun loadInstructions(skillId: String): SkillInstructionsSnapshot? {
    val skill = loadManagedSkills().firstOrNull { it.name == skillId }
      ?: loadCatalogSkills().firstOrNull { it.name == skillId }
      ?: return null
    val sourceDirectory = File(skill.source.skillDirectoryPath)
    return SkillInstructionsSnapshot(
      id = skill.name,
      name = skill.name,
      description = skill.metadata.skillSpec.description,
      body = skill.document.markdownBody,
      sourceDirectoryPath = sourceDirectory.invariantSeparatorsPath,
      isEnabled = preferences.getBoolean(preferenceKey(skill.name), true),
      canDelete = isInsideManagedRoot(sourceDirectory),
    )
  }

  override fun enabledSkillRoots(): List<File> = loadManagedSkills()
    .filter { skill -> preferences.getBoolean(preferenceKey(skill.name), true) }
    .map { skill -> File(skill.source.skillDirectoryPath) }
    .filter(File::exists)
    .sortedBy { file -> file.invariantSeparatorsPath }

  override fun activateInstallSource(sourceId: String): String = when (sourceId) {
    INSTALL_SOURCE_CURATED -> if (loadCatalogSkills().isEmpty()) {
      context.getString(R.string.skills_activate_curated_empty)
    } else {
      context.getString(R.string.skills_activate_curated_available)
    }

    INSTALL_SOURCE_LOCAL -> context.getString(R.string.skills_activate_local_ready)
    INSTALL_SOURCE_GITHUB -> context.getString(R.string.skills_activate_github_ready)
    INSTALL_SOURCE_GITLAB -> context.getString(R.string.skills_activate_gitlab_ready)
    else -> context.getString(R.string.skills_activate_unknown)
  }

  private fun loadInstalledSkills(): List<InstalledSkillSnapshot> = loadManagedSkills().map { skill ->
    val sourceDirectory = File(skill.source.skillDirectoryPath)
    InstalledSkillSnapshot(
      id = skill.name,
      name = skill.name,
      description = skill.metadata.skillSpec.description,
      isEnabled = preferences.getBoolean(preferenceKey(skill.name), true),
      sourceDirectoryPath = sourceDirectory.invariantSeparatorsPath,
      canDelete = isInsideManagedRoot(sourceDirectory),
    )
  }

  private fun loadSuggestedSkills(
    query: String,
    installedSkills: List<InstalledSkillSnapshot>,
  ): List<SuggestedSkillSnapshot> {
    val installedNames = installedSkills.mapTo(linkedSetOf()) { item -> item.name }
    val localMatches = loadCatalogSkills()
      .asSequence()
      .filter { skill -> skill.name !in installedNames }
      .map { skill ->
        SuggestedSkillSnapshot(
          id = skill.name,
          name = skill.name,
          description = skill.metadata.skillSpec.description,
          sourceRef = skill.name,
          sourceLabel = context.getString(R.string.skills_suggested_source_local_catalog),
        )
      }
      .filter { item ->
        query.isEmpty() ||
          item.name.contains(query, ignoreCase = true) ||
          item.description.contains(query, ignoreCase = true)
      }
      .toMutableList()
    if (query.isNotEmpty()) {
      val remoteResponse = packageManager.searchRemoteSkills(
        query = query,
        limit = 12,
      )
      remoteResponse.hits
        .asSequence()
        .filter { hit -> hit.name !in installedNames }
        .map { hit ->
          SuggestedSkillSnapshot(
            id = hit.id,
            name = hit.name,
            description = if (hit.installs > 0) {
              context.getString(
                R.string.skills_suggested_remote_description_with_installs,
                hit.source,
                hit.installs,
              )
            } else {
              context.getString(
                R.string.skills_suggested_remote_description,
                hit.source,
              )
            },
            sourceRef = hit.installRef,
            sourceLabel = context.getString(R.string.skills_suggested_source_remote_index),
          )
        }
        .forEach { remote ->
          val alreadyPresent = localMatches.any { local ->
            local.name.equals(remote.name, ignoreCase = true)
          }
          if (!alreadyPresent) {
            localMatches += remote
          }
        }
    }
    return localMatches
  }

  private fun installSources(): List<InstallSourceSnapshot> {
    val catalogAvailable = loadCatalogSkills().isNotEmpty()
    return listOf(
      InstallSourceSnapshot(
        id = INSTALL_SOURCE_CURATED,
        title = context.getString(R.string.skills_install_source_curated_title),
        subtitle = if (catalogAvailable) {
          context.getString(R.string.skills_install_source_curated_subtitle_available)
        } else {
          context.getString(R.string.skills_install_source_curated_subtitle_empty)
        },
        actionLabel = if (catalogAvailable) {
          context.getString(R.string.skills_install_source_curated_action_browse)
        } else {
          context.getString(R.string.skills_install_source_curated_action_empty)
        },
        isAvailable = catalogAvailable,
      ),
      InstallSourceSnapshot(
        id = INSTALL_SOURCE_LOCAL,
        title = context.getString(R.string.skills_install_source_local_title),
        subtitle = context.getString(R.string.skills_install_source_local_subtitle_ready),
        actionLabel = context.getString(R.string.skills_install_source_action_inspect),
        isAvailable = true,
      ),
      InstallSourceSnapshot(
        id = INSTALL_SOURCE_GITHUB,
        title = context.getString(R.string.skills_install_source_github_title),
        subtitle = context.getString(R.string.skills_install_source_github_subtitle_ready),
        actionLabel = context.getString(R.string.skills_install_source_action_inspect),
        isAvailable = true,
      ),
      InstallSourceSnapshot(
        id = INSTALL_SOURCE_GITLAB,
        title = context.getString(R.string.skills_install_source_gitlab_title),
        subtitle = context.getString(R.string.skills_install_source_gitlab_subtitle_ready),
        actionLabel = context.getString(R.string.skills_install_source_action_inspect),
        isAvailable = true,
      ),
    )
  }

  private fun loadManagedSkills(): List<LoadedSkill> = loadSkillsFrom(managedRoot)

  private fun loadCatalogSkills(): List<LoadedSkill> = loadSkillsFrom(catalogRoot)

  private fun loadSkillsFrom(root: File): List<LoadedSkill> {
    if (!root.exists()) {
      return emptyList()
    }
    return SkillLoader.load(root).loadedSkills
  }

  private fun preferenceKey(skillName: String): String = PREF_PREFIX_ENABLED + skillName

  private fun enableInstalledSkill(skillId: String) {
    preferences.edit().putBoolean(preferenceKey(skillId), true).apply()
  }

  private fun isInsideManagedRoot(candidate: File): Boolean {
    val rootPath = runCatching { managedRoot.canonicalFile.toPath() }.getOrElse { return false }
    val candidatePath = runCatching { candidate.canonicalFile.toPath() }.getOrElse { return false }
    return candidatePath.startsWith(rootPath)
  }

  private fun installAttempt(
    attempt: SkillPackageInstallAttempt,
    fallbackMessage: String,
  ): SkillInstallRequestResult {
    val result = attempt.result ?: return SkillInstallRequestResult(
      errorMessage = attempt.errorMessage ?: fallbackMessage,
    )
    enableInstalledSkill(result.skillId)
    return SkillInstallRequestResult(installedSkillId = result.skillId)
  }

  private fun looksLikeExplicitLocalSkillSource(sourceRef: String): Boolean {
    val normalized = sourceRef.trim()
    return normalized.startsWith(".") ||
      normalized.startsWith("/") ||
      normalized.startsWith("\\") ||
      normalized.contains("\\") ||
      WINDOWS_ABSOLUTE_PATH_REGEX.matches(normalized)
  }

  companion object {
    fun fromContext(context: Context): LocalSkillsFacade =
      LocalSkillsFacade(OpenCrayLocaleManager.wrap(context.applicationContext))
  }
}

internal object EmptySkillsFacade : SkillsFacade {
  override fun loadSnapshot(query: String): SkillsSnapshot = SkillsSnapshot(
    installedSkills = emptyList(),
    installSources = emptyList(),
    suggestedSkills = emptyList(),
  )

  override fun setSkillEnabled(skillId: String, enabled: Boolean): Boolean = false

  override fun installSkillSource(sourceRef: String): SkillInstallRequestResult =
    SkillInstallRequestResult(errorMessage = "Skills host support is unavailable.")

  override fun installSuggestedSkill(skillId: String): Boolean = false

  override fun deleteInstalledSkill(skillId: String): Boolean = false

  override fun refresh() {}

  override fun loadInstructions(skillId: String): SkillInstructionsSnapshot? = null

  override fun enabledSkillRoots(): List<File> = emptyList()

  override fun activateInstallSource(sourceId: String): String =
    "Skills host support is unavailable."
}
